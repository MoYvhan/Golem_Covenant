package com.example.golem_covenant.ritual;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.data.RitualProfile;
import com.example.golem_covenant.data.SoulProfile;
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
 * <p>Each {@link RitualProfile} supplies geometry, colour, rotation, radius,
 * layer count, core symbol and sound pattern, so no two forms reuse the same
 * circle (spec 5.1 / 5.3).
 */
public final class RitualEngine {

	private RitualEngine() {
	}

	/** Active ceremonies, ticked to completion. */
	private static final List<Ceremony> ACTIVE = new ArrayList<>();

	/** Spec 13.1 settings, controlled per-player. */
	public static volatile boolean ritualsEnabled = true;
	public static volatile int particleQuality = 2; // 0 low, 1 medium, 2 high

	private record Ceremony(ServerLevel level, LivingEntity target,
			RitualProfile profile, CovenantTier tier, long startTick,
			int durationTicks, ServerPlayer owner) {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!ritualsEnabled || ACTIVE.isEmpty()) {
				return;
			}
			Iterator<Ceremony> it = ACTIVE.iterator();
			long now = server.overworld().getGameTime();
			while (it.hasNext()) {
				Ceremony c = it.next();
				long elapsed = now - c.startTick();
				if (elapsed > c.durationTicks()) {
					finish(c);
					it.remove();
					continue;
				}
				emit(c, elapsed);
			}
		});
	}

	/** Kicks off the ceremony for a freshly contracted companion. */
	public static void playCeremony(ServerPlayer owner, LivingEntity target,
			SoulProfile profile, CovenantTier tier) {
		if (!ritualsEnabled || tier == CovenantTier.A) {
			// Spec 5.2: A uses a short golden pact mark, no large ritual.
			if (ritualsEnabled) {
				quickPactMark(owner, target);
			}
			return;
		}
		RitualProfile rp = profile.ritual();
		ACTIVE.add(new Ceremony(owner.level(), target, rp, tier,
				owner.level().getGameTime(), rp.durationTicks(tier), owner));
		owner.level().playSound(null, target.blockPosition(),
				ModSounds.ritual(tier), SoundSource.PLAYERS, 1.0f, 1.0f);
	}

	/** Spec 5.2 / 5.5: a single-layer, low particle golden pact mark. */
	private static void quickPactMark(ServerPlayer owner, LivingEntity target) {
		ServerLevel level = (ServerLevel) owner.level();
		SimpleParticleType spark = ModParticles.CORE_SPARK;
		for (int i = 0; i < 16; i++) {
			double a = (Math.PI * 2 / 16) * i;
			double x = target.getX() + Math.cos(a) * 0.9;
			double z = target.getZ() + Math.sin(a) * 0.9;
			level.sendParticles(spark, x, target.getY() + 0.15, z, 1, 0, 0.02, 0, 0);
		}
	}

	/**
	 * Emits one frame of the ceremony.
	 *
	 * <p>Layers, rotation direction, colour and the core shape all come from
	 * the profile, so a viewer can tell two forms apart by their circle alone
	 * (spec 5.3).
	 */
	private static void emit(Ceremony c, long elapsed) {
		RitualProfile rp = c.profile();
		ServerLevel level = c.level();
		LivingEntity t = c.target();
		if (!t.isAlive()) {
			return;
		}
		SimpleParticleType particle = ModParticles.forFamily(c.profile().geometry()
				.contains("web") ? "arthropod" : familyOfGeometry(rp.geometry()))
				.orElse(ModParticles.CORE_SPARK);

		int layers = Math.max(1, rp.layerCount());
		int count = switch (particleQuality) {
			case 0 -> 8;
			case 1 -> 16;
			default -> 24;
		};
		double dir = rp.counterClockwise() ? -1 : 1;
		double spin = (elapsed * 0.08 * dir);
		double baseY = t.getY() + 0.1;

		for (int layer = 0; layer < layers; layer++) {
			double radius = rp.radius() * (1.0 - layer * 0.25);
			double y = baseY + layer * 0.35;
			for (int i = 0; i < count; i++) {
				double a = (Math.PI * 2 / count) * i + spin + layer * 0.6;
				double x = t.getX() + Math.cos(a) * radius;
				double z = t.getZ() + Math.sin(a) * radius;
				level.sendParticles(particle, x, y, z, 1, 0, 0, 0, 0);
			}
		}

		// energy column + soul core (spec 5.1 six-layer structure)
		if (particleQuality > 0) {
			for (int i = 0; i < 6; i++) {
				level.sendParticles(ModParticles.CORE_SPARK,
						t.getX(), baseY + i * 0.4, t.getZ(), 1, 0.1, 0.1, 0.1, 0);
			}
		}
	}

	/** Spec 5.6: the finishing burst. */
	private static void finish(Ceremony c) {
		ServerLevel level = c.level();
		LivingEntity t = c.target();
		if (!t.isAlive()) {
			return;
		}
		level.sendParticles(ModParticles.REVIVE_BURST, t.getX(),
				t.getY() + 0.6, t.getZ(), 40, 0.6, 0.6, 0.6, 0.12);
	}

	private static String familyOfGeometry(String geometry) {
		if (geometry.startsWith("tomb")) {
			return "zombie";
		}
		if (geometry.startsWith("beast") || geometry.startsWith("biome")) {
			return "animal";
		}
		if (geometry.startsWith("hoof")) {
			return "mount";
		}
		if (geometry.startsWith("alpine")) {
			return "snow";
		}
		if (geometry.startsWith("tide")) {
			return "aquatic";
		}
		if (geometry.startsWith("pillar")) {
			return "construct";
		}
		if (geometry.startsWith("void")) {
			return "nether_end";
		}
		if (geometry.startsWith("implosion")) {
			return "special";
		}
		return "arthropod";
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
}
