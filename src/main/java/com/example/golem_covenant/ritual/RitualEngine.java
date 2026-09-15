package com.example.golem_covenant.ritual;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.data.RitualProfile;
import com.example.golem_covenant.data.SoulProfile;
import com.example.golem_covenant.network.ModNetworking;
import com.example.golem_covenant.registry.ModParticles;
import com.example.golem_covenant.registry.ModSounds;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 法阵与复生演出引擎 (spec ch.5).
 *
 * <p>Server side only: it authors the particle *trajectory* and the sound
 * rhythm, then emits vanilla particle packets. Spec 13.2.1 forbids using
 * entities as the effect carrier, and 5.4 requires the circle to stay around
 * the target and the ground rather than filling first-person view.
 *
 * <p>This class owns the ceremony <em>lifecycle</em> - which ceremonies are
 * running, for how long, and at what quality. The frame-by-frame visual
 * sequence lives in {@link RitualChoreography}, and the raw point maths in
 * {@link RitualGeometry}, so the stage table can change without touching the
 * ticking code.
 */
public final class RitualEngine {

	private RitualEngine() {
	}

	/** Active ceremonies, ticked to completion. */
	private static final List<Ceremony> ACTIVE = new ArrayList<>();

	/** Spec 13.1 settings, controlled per-player. */
	public static volatile boolean ritualsEnabled = true;
	public static volatile int particleQuality = 2; // 0 low, 1 medium, 2 high

	/**
	 * Spec 13.1: screen shake is a separate, individually-set cosmetic.
	 *
	 * <p>It is deliberately independent of {@link #ritualsEnabled}: a player
	 * who wants the ritual but not the camera movement can have exactly that,
	 * which is the point of listing the three settings separately in the spec.
	 */
	public enum ShakeLevel {
		/** No camera movement at all. */
		OFF(0.0f, 0),
		/** Barely perceptible. */
		WEAK(0.12f, 6),
		/** The default. */
		NORMAL(0.28f, 10),
		/** For players who want the ceremony to hit hard. */
		STRONG(0.5f, 16);

		private final float intensity;
		private final int ticks;

		ShakeLevel(float intensity, int ticks) {
			this.intensity = intensity;
			this.ticks = ticks;
		}

		public float intensity() {
			return this.intensity;
		}

		public int ticks() {
			return this.ticks;
		}
	}

	public static volatile ShakeLevel shakeLevel = ShakeLevel.NORMAL;

	/**
	 * An immutable view of the three spec 13.1 settings, so a caller cannot
	 * read them half-updated while another thread is writing.
	 */
	public record Settings(boolean ritualsEnabled, int particleQuality,
			ShakeLevel shakeLevel) {

		/** {@code "low" / "medium" / "high"} for the command and HUD. */
		public String particleQualityName() {
			return switch (this.particleQuality) {
				case 0 -> "low";
				case 1 -> "medium";
				default -> "high";
			};
		}

		/** {@code "off" / "weak" / "normal" / "strong"}. */
		public String shakeName() {
			return this.shakeLevel.name().toLowerCase();
		}
	}

	public static Settings settings() {
		return new Settings(ritualsEnabled, particleQuality, shakeLevel);
	}

	public static void setRitualsEnabled(boolean enabled) {
		ritualsEnabled = enabled;
		if (!enabled) {
			// Stopping mid-ceremony would leave rings frozen in the air, so
			// the active list is flushed when the player turns effects off.
			ACTIVE.clear();
		}
	}

	public static void setParticleQuality(int quality) {
		particleQuality = Math.clamp(quality, 0, 2);
	}

	public static void setShakeLevel(ShakeLevel level) {
		shakeLevel = level;
	}

	/**
	 * A running ceremony.
	 *
	 * <p>The {@link RitualChoreography} is built once, when the ceremony
	 * starts, and then driven frame by frame. It is deliberately not rebuilt
	 * per tick: it carries the stage cursor and the "soul already extracted"
	 * latch, both of which must persist across a stage boundary.
	 */
	private record Ceremony(ServerLevel level, LivingEntity target,
			RitualChoreography choreography, CovenantTier tier, long startTick,
			int durationTicks, ServerPlayer owner) {
	}

	/**
	 * A running <em>block-anchored</em> rite (spec 4.3.1).
	 *
	 * <p>Separate from {@link Ceremony} because the two are anchored
	 * differently and cannot share a type: a creature ceremony is driven by its
	 * target {@link LivingEntity} (the choreography checks {@code isAlive()} and
	 * positions every layer relative to it), while this rite is anchored to a
	 * fixed {@link BlockPos} and has no entity at all.
	 *
	 * <p>{@code onComplete} is the payoff. The stele's slot is granted here -
	 * on completion - rather than when the ritual began, which is what spec
	 * 4.3.1 means by "完成对应仪式后：+1 A 级槽位".
	 */
	private record BlockRite(ServerLevel level, BlockPos anchor, long startTick,
			int durationTicks, ServerPlayer owner, Runnable onComplete) {
	}

	/** How long the stele's expansion rite runs (spec 5.2's B window). */
	public static final int STELE_RITE_TICKS = 56;

	/** block rites currently running. */
	private static final List<BlockRite> ACTIVE_RITES = new ArrayList<>();

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (ACTIVE.isEmpty() && ACTIVE_RITES.isEmpty()) {
				return;
			}
			long now = server.overworld().getGameTime();
			if (ritualsEnabled) {
				tickCeremonies(now);
			}
			tickBlockRites(now);
		});
	}

	private static void tickCeremonies(long now) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		Iterator<Ceremony> it = ACTIVE.iterator();
		while (it.hasNext()) {
			Ceremony c = it.next();
			long elapsed = now - c.startTick();
			if (elapsed > c.durationTicks()) {
				it.remove();
				continue;
			}
			// Spec 13.2.2: density scales down when many companions share
			// the screen, so the per-frame quality is resolved here rather
			// than the ceremony reading the global setting itself.
			int q = effectiveQuality(nearbyCompanions(c));
			RitualStage entered = c.choreography().frame(c.level(),
					c.target(), elapsed, c.durationTicks(), q);
			if (entered != null && entered.shakes()) {
				// Spec 13.1: the camera beat marks the stage boundary.
				// The client scales it by the player's own shakeLevel, so
				// the packet is sent regardless of the local preference.
				float intensity = (float) (entered.shakeOnEnter()
						* shakeLevel.intensity() * 2.0f);
				ModNetworking.sendShake(c.owner(), intensity,
						shakeLevel.ticks());
			}
		}
	}

	/**
	 * Advance the block-anchored rites.
	 *
	 * <p>These run even when the player has particle quality turned down - only
	 * the visual layer is gated by {@code ritualsEnabled}, never the grant, or
	 * a cosmetic setting would silently cost a player their slot.
	 */
	private static void tickBlockRites(long now) {
		if (ACTIVE_RITES.isEmpty()) {
			return;
		}
		Iterator<BlockRite> it = ACTIVE_RITES.iterator();
		while (it.hasNext()) {
			BlockRite r = it.next();
			long elapsed = now - r.startTick();
			if (elapsed > r.durationTicks()) {
				it.remove();
				// The one moment that matters: award the slot.
				r.onComplete().run();
				continue;
			}
			if (ritualsEnabled) {
				emitBlockRiteFrame(r, elapsed);
			}
		}
	}

	/**
	 * Draw one frame of a block-anchored rite.
	 *
	 * <p>Reuses {@link RitualGeometry}'s ring maths so the stele's circle reads
	 * as part of the same visual language as a creature ceremony, but anchors
	 * it to the block centre and ignores the creature-only layers (totem, soul
	 * extraction) which have no meaning without an entity.
	 */
	private static void emitBlockRiteFrame(BlockRite r, long elapsed) {
		double overall = Math.clamp(elapsed / (double) r.durationTicks(), 0.0, 1.0);
		double radius = RitualGeometry.groundRadius(2.2, 3);
		// The ring draws itself outward over the first 60% then holds, then
		// contracts as the rite closes so the payoff reads as a collapse.
		double ramp = Math.min(1.0, overall / 0.6);
		double closing = overall > 0.85 ? 1.0 - (overall - 0.85) / 0.15 : 1.0;
		double current = radius * (0.35 + 0.65 * ramp) * closing;
		int points = 24;
		double yBase = r.anchor().getY() + 1.05;
		for (int i = 0; i < points; i++) {
			double angle = (Math.PI * 2 * i / points) + overall * Math.PI * 2;
			double x = r.anchor().getX() + 0.5 + Math.cos(angle) * current;
			double z = r.anchor().getZ() + 0.5 + Math.sin(angle) * current;
			r.level().sendParticles(ModParticles.CORE_SPARK, x, yBase, z, 1,
					0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * Begin the 契约石碑 expansion rite (spec 4.3.1).
	 *
	 * @return false if this stele already has a rite in flight, so a player
	 *         cannot stack rites to farm slots from one block
	 */
	public static boolean beginSteleRite(ServerPlayer owner, ServerLevel level,
			BlockPos anchor) {
		for (BlockRite r : ACTIVE_RITES) {
			if (r.anchor().equals(anchor) && r.level() == level) {
				return false;
			}
		}
		if (!ritualsEnabled) {
			// Spec 13.1: with rituals off the player still gets the slot, just
			// without the ceremony - so the callbacks still has to run.
			com.example.golem_covenant.block.CovenantSteleBlock
					.completeRite(owner);
			return true;
		}
		ACTIVE_RITES.add(new BlockRite(level, anchor, level.getGameTime(),
				STELE_RITE_TICKS, owner,
				() -> com.example.golem_covenant.block.CovenantSteleBlock
						.completeRite(owner)));
		level.playSound(null, anchor, ModSounds.ritual(CovenantTier.A),
				SoundSource.BLOCKS, 1.0f, 1.0f);
		return true;
	}

	public static int activeRites() {
		return ACTIVE_RITES.size();
	}

	/**
	 * How many other companions sit near this ceremony's target.
	 *
	 * <p>Used only for the spec 13.2.2 density falloff, so it is a cheap
	 * bounding-box count rather than anything exact.
	 */
	private static int nearbyCompanions(Ceremony c) {
		try {
			return c.level()
					.getEntitiesOfClass(LivingEntity.class,
							c.target().getBoundingBox().inflate(12.0),
							e -> e != c.target() && e.isAlive())
					.size();
		} catch (RuntimeException ex) {
			return 0;
		}
	}

	/** Kicks off the ceremony for a freshly contracted companion. */
	public static void playCeremony(ServerPlayer owner, LivingEntity target,
			SoulProfile profile, CovenantTier tier, String familyId) {
		if (!ritualsEnabled) {
			return;
		}
		RitualProfile rp = profile.ritual();
		if (tier == CovenantTier.A) {
			// Spec 5.2: A uses a short golden pact mark, no large ritual, so
			// it never enters the ACTIVE list - it is emitted in one shot.
			RitualChoreography quick = new RitualChoreography(rp, tier,
					familyId);
			quick.frame(owner.level(), target, 0, 20,
					Math.min(particleQuality, 1));
			owner.level().playSound(null, target.blockPosition(),
					ModSounds.ritual(tier), SoundSource.PLAYERS, 0.8f, 1.0f);
			return;
		}
		int duration = rp.durationTicks(tier);
		RitualChoreography choreography = new RitualChoreography(rp, tier,
				familyId);
		ACTIVE.add(new Ceremony(owner.level(), target, choreography, tier,
				owner.level().getGameTime(), duration, owner));
	}

	public static int activeCeremonies() {
		return ACTIVE.size();
	}

	/** Spec 13.2.2: density scales down when many companions share a screen. */
	public static int effectiveQuality(int nearbyCompanions) {
		if (nearbyCompanions > 12) {
			return 0;
		}
		if (nearbyCompanions > 6) {
			return Math.min(particleQuality, 1);
		}
		return particleQuality;
	}

	/** Spec 11.14: ceremonies are transient, never persisted across sessions. */
	public static void onServerStopped() {
		ACTIVE.clear();
	}
}
