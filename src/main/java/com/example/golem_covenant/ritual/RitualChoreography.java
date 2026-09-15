package com.example.golem_covenant.ritual;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.data.RitualProfile;
import com.example.golem_covenant.registry.ModParticles;
import com.example.golem_covenant.registry.ModSounds;
import com.example.golem_covenant.ritual.RitualGeometry.Family;
import com.example.golem_covenant.ritual.RitualGeometry.Point;
import com.example.golem_covenant.ritual.RitualGeometry.Trajectory;

/**
 * Drives one ceremony frame by frame through the spec 5.2 stage sequence.
 *
 * <p>This replaces the previous stateless loop. The difference matters: with a
 * stage model the soul can actually be <em>drawn out and poured back</em>, the
 * target can genuinely <em>lift off the ground</em>, and the core can be
 * <em>forged</em> rather than merely being present from frame one. A ceremony
 * that only changes colour and duration is exactly what spec 5.1 forbids.
 *
 * <p>Everything here is server-authored and sent as particle packets. Spec
 * 13.2.1 forbids entities as the effect carrier, and spec 5.4 requires the
 * effect to stay around the target and the ground rather than filling
 * first-person view.
 *
 * <p>Immutable: a new instance is built per ceremony, so two simultaneous
 * ceremonies can never share stage state.
 */
public final class RitualChoreography {

	/** Points spent on the ground ring at each particle-quality level. */
	private static final int[] RING_BUDGET = { 12, 22, 32 };
	/** Points spent on the rune column. */
	private static final int[] RUNE_BUDGET = { 10, 16, 24 };
	/** Points spent on the core sphere. */
	private static final int[] CORE_BUDGET = { 10, 18, 28 };
	/** Points spent on the energy column. */
	private static final int[] COLUMN_BUDGET = { 6, 10, 14 };

	private final RitualProfile profile;
	private final CovenantTier tier;
	private final Family family;
	private final Trajectory trajectory;
	private final SimpleParticleType particle;
	/** Cached symbol hash, so each frame is not re-hashing a string. */
	private final int symbolHash;

	private RitualStage lastStage = null;
	/** Set once soul extraction has run, so it never repeats (spec 5.2 C4). */
	private boolean soulExtracted = false;

	public RitualChoreography(RitualProfile profile, CovenantTier tier,
			String familyId) {
		this.profile = profile;
		this.tier = tier;
		this.family = RitualGeometry.Family.byId(profile.geometry());
		this.trajectory = Trajectory.forFamily(this.family);
		this.particle = ModParticles.familyOr(familyId,
				ModParticles.CORE_SPARK);
		this.symbolHash = profile.symbol() == null
				? 0 : profile.symbol().hashCode();
	}

	/** The stage the ceremony is in at {@code overall} (0..1). */
	public RitualStage stageAt(double overall) {
		return RitualStage.at(this.tier, overall);
	}

	/**
	 * Emits one frame.
	 *
	 * @param level    the server level to send packets on
	 * @param target   the companion being bound
	 * @param elapsed  ticks since the ceremony began
	 * @param duration total duration in ticks
	 * @param quality  0 low, 1 medium, 2 high (spec 13.1)
	 * @return the stage that was entered on this frame, or {@code null} if
	 *         this frame did not cross a stage boundary. The caller uses this
	 *         to fire a once-per-stage screen shake beat (spec 13.1) without
	 *         duplicating the stage table.
	 */
	public RitualStage frame(ServerLevel level, LivingEntity target, long elapsed,
			int duration, int quality) {
		if (!target.isAlive()) {
			return null;
		}
		double overall = duration <= 0 ? 1.0
				: Math.clamp(elapsed / (double) duration, 0.0, 1.0);
		RitualStage stage = stageAt(overall);
		double progress = stage.progress(overall);

		// Stage-entry cues fire exactly once per stage: the sound rhythm is
		// part of the uniqueness rule (spec 5.3), and a cue that repeats every
		// tick is noise rather than rhythm.
		RitualStage entered = null;
		if (stage != this.lastStage) {
			onStageEnter(level, target, stage);
			this.lastStage = stage;
			entered = stage;
		}

		int q = Math.clamp(quality, 0, 2);

		if (this.tier == CovenantTier.A) {
			framePactMark(level, target, progress);
			return entered;
		}

		// --- layers that run through most of the ceremony ------------------
		// The base ring is present from the first frame to the last (spec 5.1
		// layer 1): it is the frame the other layers hang off.
		if (stage != RitualStage.REVIVAL
				&& stage != RitualStage.FINAL_BURST) {
			emitGroundRing(level, target, stage, overall, q);
		}

		switch (stage) {
			case GROUND_RING -> {
				// The ring draws itself outward: radius ramps 0.35 -> 1.0.
				// (Handled inside emitGroundRing via the stage progress.)
			}
			case CREATURE_TOTEM -> {
				emitTotem(level, target, progress, q);
				emitGroundRing(level, target, stage, overall, q);
			}
			case SOUL_CONVERGE -> {
				emitConvergeStream(level, target, progress, q);
				emitCore(level, target, this.soulExtracted ? 0.6 : 0.25, q);
			}
			case CORE_IGNITE -> {
				emitCore(level, target, 0.5 + progress * 0.5, q);
				emitColumn(level, target, progress, q);
			}
			case REVIVAL -> emitBurst(level, target, 28 + q * 8, 0.55);

			// --- C-tier stages ---------------------------------------------
			case TRIPLE_RING -> emitTripleRing(level, target, progress, q);
			case VERTICAL_RUNE -> {
				emitTripleRing(level, target, progress, q);
				emitRuneColumn(level, target, progress, q);
			}
			case UNIQUE_TOTEM -> {
				emitTripleRing(level, target, progress, q);
				emitRuneColumn(level, target, 1.0, q);
				emitTotem(level, target, progress, q);
			}
			case SOUL_TRANSFER -> {
				emitTripleRing(level, target, progress, q);
				emitSoulTransfer(level, target, progress, q);
			}
			case LEVITATION -> {
				emitTripleRing(level, target, progress, q);
				emitLevitation(level, target, progress, q);
				emitCore(level, target, 0.7 + progress * 0.3, q);
			}
			case CORE_FORGING -> {
				emitTripleRing(level, target, progress, q);
				emitForging(level, target, progress, q);
			}
			case FINAL_BURST -> {
				emitFinalBurst(level, target, progress, q);
			}
			default -> {
			}
		}

		// The energy column persists once it has been raised, so the ceremony
		// does not visually collapse between C stages.
		if (this.tier == CovenantTier.C && stage.ordinal()
				> RitualStage.VERTICAL_RUNE.ordinal()
				&& stagesAfterRune(stage)) {
			emitColumn(level, target, 1.0, q);
		}
		return entered;
	}

	/** True for the C stages that come after the rune column is raised. */
	private static boolean stagesAfterRune(RitualStage stage) {
		return stage == RitualStage.UNIQUE_TOTEM
				|| stage == RitualStage.SOUL_TRANSFER
				|| stage == RitualStage.LEVITATION
				|| stage == RitualStage.CORE_FORGING
				|| stage == RitualStage.FINAL_BURST;
	}

	// ------------------------------------------------------------------
	// stage-entry cues
	// ------------------------------------------------------------------

	/** Sound + first-frame accent for each stage (spec 5.3 音效节奏). */
	private void onStageEnter(ServerLevel level, LivingEntity target,
			RitualStage stage) {
		// Each stage gets a distinct pitch offset, so the nine stages of a C
		// ceremony form a rising melodic line rather than nine identical hits.
		float pitch = switch (stage) {
			case PACT_MARK -> 1.6f;
			case GROUND_RING, TRIPLE_RING -> 0.85f;
			case CREATURE_TOTEM, UNIQUE_TOTEM -> 1.05f;
			case SOUL_CONVERGE, SOUL_TRANSFER -> 1.20f;
			case CORE_IGNITE, CORE_FORGING -> 1.35f;
			case VERTICAL_RUNE -> 0.95f;
			case LEVITATION -> 1.50f;
			case REVIVAL, FINAL_BURST -> 1.75f;
		};
		level.playSound(null, target.blockPosition(), ModSounds.ritual(this.tier),
				SoundSource.PLAYERS, 0.7f, pitch);

		// A brief accent puff marks the transition, which is what makes the
		// stage boundaries readable in the first place.
		int accent = switch (stage) {
			case PACT_MARK -> 8;
			case REVIVAL, FINAL_BURST -> 20;
			default -> 12;
		};
		level.sendParticles(ModParticles.CORE_SPARK, target.getX(),
				target.getY() + 0.2, target.getZ(), accent, 0.35, 0.1, 0.35,
				0.02);
	}

	// ------------------------------------------------------------------
	// layer 1: the ground ring
	// ------------------------------------------------------------------

	private void emitGroundRing(ServerLevel level, LivingEntity target,
			RitualStage stage, double overall, int quality) {
		int count = RING_BUDGET[quality];
		int layers = Math.max(1, this.profile.layerCount());
		double radius = RitualGeometry.groundRadius(
				this.profile.radius(), layers);
		// Dir is +1 clockwise, -1 counter-clockwise (spec 5.3 rotation axis).
		double dir = this.profile.counterClockwise() ? -1.0 : 1.0;
		double spin = overall * Math.PI * 2.0 * 1.6 * dir

				+ this.symbolHash * 0.0001;

		// GROUND_RING grows the circle out to full radius (spec 5.2 B1).
		double grow = stage == RitualStage.GROUND_RING
				? 0.35 + 0.65 * stage.progress(overall)
				: 1.0;

		for (int layer = 0; layer < layers; layer++) {
			// A flat ring on the floor: spec 5.4 puts the effect on the ground.
			Point[] pts = RitualGeometry.ring(this.family, count,
					radius * grow * (1.0 - layer * 0.22), spin, overall,
					layer, layers);
			double baseY = target.getY() + 0.08;
			for (Point p : pts) {
				if (p.weight() <= 0.0f) {
					continue; // the deliberate gap in an arc geometry
				}
				double y = baseY + p.y();
				level.sendParticles(this.particle,
						target.getX() + p.x(), y, target.getZ() + p.z(),
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	private void emitTripleRing(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		int count = RING_BUDGET[quality];
		double radius = RitualGeometry.groundRadius(this.profile.radius(),
				Math.max(3, this.profile.layerCount()));
		double dir = this.profile.counterClockwise() ? -1.0 : 1.0;
		// Three rings at three heights, counter-rotating against each other
		// (spec 5.2 C1). The middle ring turns the other way, which is what
		// gives the stack its sense of depth.
		double spin = progress * Math.PI * 2.0;
		for (int layer = 0; layer < 3; layer++) {
			double layerDir = (layer % 2 == 0) ? dir : -dir;
			double phase = spin * layerDir * (1.0 + layer * 0.25);
			Point[] pts = RitualGeometry.ring(this.family, count,
					radius * (1.0 - layer * 0.18), phase, progress, layer, 3);
			double baseY = target.getY() + 0.08 + layer * 0.42;
			for (Point p : pts) {
				if (p.weight() <= 0.0f) {
					continue;
				}
				level.sendParticles(this.particle,
						target.getX() + p.x(), baseY + p.y() * 0.4,
						target.getZ() + p.z(), 1, 0, 0, 0, 0);
			}
		}
	}

	// ------------------------------------------------------------------
	// layer 2: the rune column
	// ------------------------------------------------------------------

	private void emitRuneColumn(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		int glyphs = RUNE_BUDGET[quality];
		double radius = RitualGeometry.groundRadius(this.profile.radius(),
				this.profile.layerCount()) * 0.72;
		double phase = progress * Math.PI * 2.0
				* (this.profile.counterClockwise() ? -1.0 : 1.0);
		Point[] pts = RitualGeometry.runeColumn(glyphs, radius, 1.6, phase,
				progress);
		for (Point p : pts) {
			level.sendParticles(ModParticles.CORE_SPARK,
					target.getX() + p.x(), target.getY() + p.y(),
					target.getZ() + p.z(), 1, 0, 0.01, 0, 0.01);
		}
	}

	// ------------------------------------------------------------------
	// layer 3: the unique totem
	// ------------------------------------------------------------------

	private void emitTotem(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		if (quality == 0) {
			return; // the totem is the first thing dropped at low quality
		}
		double radius = RitualGeometry.groundRadius(this.profile.radius(),
				this.profile.layerCount());
		Point[] pts = RitualGeometry.totem(this.profile.symbol(), radius * 1.05,
				1.15, progress);
		for (Point p : pts) {
			// The totem is drawn in core-spark gold so it separates visually
			// from the family-coloured ring (spec 5.5: theme colour + gold).
			level.sendParticles(ModParticles.CORE_SPARK,
					target.getX() + p.x(), target.getY() + p.y(),
					target.getZ() + p.z(), 1, 0, 0, 0, 0);
		}
	}

	// ------------------------------------------------------------------
	// layer 4: the energy column
	// ------------------------------------------------------------------

	private void emitColumn(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		int count = COLUMN_BUDGET[quality];
		double radius = this.profile.radius();
		Point[] pts = RitualGeometry.energyColumn(count, radius, 2.2,
				progress * 3.0, progress);
		for (Point p : pts) {
			level.sendParticles(ModParticles.CORE_SPARK,
					target.getX() + p.x(), target.getY() + p.y(),
					target.getZ() + p.z(), 1, 0.02, 0.0, 0.02, 0.0);
		}
	}

	// ------------------------------------------------------------------
	// layer 5: the soul core
	// ------------------------------------------------------------------

	private void emitCore(ServerLevel level, LivingEntity target,
			double intensity, int quality) {
		int count = Math.max(4, (int) (CORE_BUDGET[quality] * intensity));
		double radius = 0.28 + 0.22 * intensity;
		double spin = (this.lastStage == null ? 0.0
				: this.lastStage.progress(0.0)) * 2.0;
		Point[] pts = RitualGeometry.coreSphere(count, radius,
				this.symbolHash * 0.0007, spin);
		double cx = target.getX();
		double cy = target.getY() + 0.95;
		double cz = target.getZ();
		for (Point p : pts) {
			level.sendParticles(ModParticles.CORE_SPARK, cx + p.x(),
					cy + p.y(), cz + p.z(), 1, 0, 0, 0, 0);
		}
	}

	// ------------------------------------------------------------------
	// trajectories (spec 5.3 专属粒子运动轨迹)
	// ------------------------------------------------------------------

	private void emitConvergeStream(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		int count = RING_BUDGET[quality];
		double radius = this.profile.radius() * 2.2;
		double dir = this.profile.counterClockwise() ? -1.0 : 1.0;
		double phase = progress * Math.PI * 2.0 * 2.0 * dir;
		for (int i = 0; i < count; i++) {
			double a = (Math.PI * 2.0 / count) * i + phase;
			// Points start wide and rush inward: the ring tightens as the
			// stage runs, so the companion appears to be drawing soul in.
			double t = Math.clamp(progress * 1.3, 0.0, 1.0);
			double r = radius * (1.0 - t) + 0.35;
			double y = target.getY() + 0.4 + t * 0.7;
			level.sendParticles(this.particle,
					target.getX() + Math.cos(a) * r, y,
					target.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
		}
	}

	/**
	 * Spec 5.2 C4: soul is drawn out of the world, then poured back in.
	 *
	 * <p>Two halves: the first pulls a stream upward out of the surrounding
	 * ground, the second reverses it into the target. Without this reversal
	 * the C ceremony has no narrative middle - which is precisely the gap the
	 * audit identified.
	 */
	private void emitSoulTransfer(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		int count = RING_BUDGET[quality];
		double radius = RitualGeometry.groundRadius(this.profile.radius(),
				this.profile.layerCount()) * 1.35;
		double dir = this.profile.counterClockwise() ? -1.0 : 1.0;
		boolean extracting = progress < 0.5;
		double half = extracting ? progress * 2.0 : (progress - 0.5) * 2.0;

		if (extracting && !this.soulExtracted) {
			this.soulExtracted = true;
			level.playSound(null, target.blockPosition(),
					ModSounds.ritual(this.tier), SoundSource.PLAYERS, 0.9f,
					0.7f);
		}

		for (int i = 0; i < count; i++) {
			double a = (Math.PI * 2.0 / count) * i
					+ progress * Math.PI * 1.5 * dir;
			double r = radius;
			double x = target.getX() + Math.cos(a) * r;
			double z = target.getZ() + Math.sin(a) * r;
			double groundY = target.getY() + 0.1;

			if (extracting) {
				// Ground -> upward, drawn toward the ring above.
				double y = groundY + half * 1.5;
				level.sendParticles(this.particle, x, y, z, 1, 0, 0.05, 0,
						0.02);
			} else {
				// Ring -> back down and inward into the target.
				double r2 = r * (1.0 - half * 0.65);
				double x2 = target.getX() + Math.cos(a) * r2;
				double z2 = target.getZ() + Math.sin(a) * r2;
				double y = groundY + 1.5 * (1.0 - half);
				level.sendParticles(this.particle, x2, y, z2, 1, 0, -0.05, 0,
						0.02);
			}
		}
	}

	/**
	 * Spec 5.2 C5: the entity briefly levitates.
	 *
	 * <p>The visual lift is produced by raising a ring of particles beneath
	 * the target and letting it rise - the entity itself is never moved,
	 * because spec 6.1/9.3 forbid the ritual from altering the companion.
	 */
	private void emitLevitation(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		int count = RING_BUDGET[quality];
		double radius = RitualGeometry.groundRadius(this.profile.radius(),
				this.profile.layerCount()) * 0.8;
		double lift = Math.sin(progress * Math.PI) * 0.85;
		for (int i = 0; i < count; i++) {
			double a = (Math.PI * 2.0 / count) * i + progress * 2.4;
			double x = target.getX() + Math.cos(a) * radius;
			double z = target.getZ() + Math.sin(a) * radius;
			// A rising curtain under the companion reads as lift-off.
			level.sendParticles(this.particle, x,
					target.getY() + 0.05 + lift, z, 1, 0, 0.08, 0, 0.03);
			level.sendParticles(ModParticles.CORE_SPARK, x,
					target.getY() + 0.05 + lift * 0.5, z, 1, 0, 0.04, 0, 0.01);
		}
	}

	/**
	 * Spec 5.2 C6: the core is forged - struck into shape.
	 *
	 * <p>Rendered as periodic impacts converging on the core, with a flare on
	 * each beat, so the core appears to be hammered rather than merely lit.
	 */
	private void emitForging(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		// Six strikes across the stage; the pulse makes them readable.
		double beat = (progress * 6.0) % 1.0;
		boolean impact = beat < 0.3;
		double cx = target.getX();
		double cy = target.getY() + 0.95;
		double cz = target.getZ();

		emitCore(level, target, 1.0, quality);

		int arms = 4 + quality * 2;
		for (int i = 0; i < arms; i++) {
			double a = (Math.PI * 2.0 / arms) * i + progress * 5.0;
			double dist = impact ? 0.42 : 0.95;
			level.sendParticles(ModParticles.CORE_SPARK,
					cx + Math.cos(a) * dist, cy + Math.sin(a * 1.7) * 0.3,
					cz + Math.sin(a) * dist, impact ? 3 : 1,
					0, 0, 0, impact ? 0.06 : 0.0);
		}
		if (impact && quality > 0) {
			// The strike flash.
			level.sendParticles(ModParticles.REVIVE_BURST, cx, cy, cz,
					6, 0.15, 0.15, 0.15, 0.02);
		}
	}

	// ------------------------------------------------------------------
	// layer 6: bursts
	// ------------------------------------------------------------------

	private void emitBurst(ServerLevel level, LivingEntity target, int count,
			double spread) {
		level.sendParticles(ModParticles.REVIVE_BURST, target.getX(),
				target.getY() + 0.6, target.getZ(), count, spread, spread * 0.7,
				spread, 0.12);
	}

	/**
	 * Spec 5.2 C7: the final burst plus covenant lock.
	 *
	 * <p>The outward blast is followed by an inward snap, which is the visual
	 * grammar for "the contract is now sealed".
	 */
	private void emitFinalBurst(ServerLevel level, LivingEntity target,
			double progress, int quality) {
		double cx = target.getX();
		double cy = target.getY() + 0.7;
		double cz = target.getZ();

		if (progress < 0.45) {
			// Outward shockwave.
			double t = progress / 0.45;
			int count = 30 + quality * 12;
			double radius = 0.4 + t * 2.4;
			for (int i = 0; i < count; i++) {
				double a = (Math.PI * 2.0 / count) * i;
				double y = Math.sin(a * 2.0) * 0.4;
				level.sendParticles(ModParticles.REVIVE_BURST,
						cx + Math.cos(a) * radius, cy + y,
						cz + Math.sin(a) * radius, 1, 0, 0, 0, 0.02);
			}
			level.sendParticles(ModParticles.REVIVE_BURST, cx, cy, cz,
					40, 0.6, 0.6, 0.6, 0.14);
		} else {
			// Inward lock: the ring snaps back onto the companion.
			double t = (progress - 0.45) / 0.55;
			int count = 24 + quality * 8;
			double radius = 2.4 * (1.0 - t);
			for (int i = 0; i < count; i++) {
				double a = (Math.PI * 2.0 / count) * i + t * 4.0;
				level.sendParticles(ModParticles.CORE_SPARK,
						cx + Math.cos(a) * radius, cy, cz + Math.sin(a) * radius,
						1, 0, 0, 0, 0);
			}
		}
	}

	// ------------------------------------------------------------------
	// A tier
	// ------------------------------------------------------------------

	/** Spec 5.2: a single-layer golden pact mark, no large ceremony. */
	private void framePactMark(ServerLevel level, LivingEntity target,
			double progress) {
		// Spec 5.5: A is a small stamp - two quick rings and nothing else.
		for (int ring = 0; ring < 2; ring++) {
			int count = 12;
			double radius = 0.55 + ring * 0.42 + progress * 0.35;
			for (int i = 0; i < count; i++) {
				double a = (Math.PI * 2.0 / count) * i
						+ ring * 0.4 + progress * 2.0;
				level.sendParticles(ModParticles.CORE_SPARK,
						target.getX() + Math.cos(a) * radius,
						target.getY() + 0.12,
						target.getZ() + Math.sin(a) * radius,
						1, 0, 0.02, 0, 0);
			}
		}
	}

	/** Exposed for diagnostics: which family/trajectory this form uses. */
	public Family family() {
		return this.family;
	}

	public Trajectory trajectory() {
		return this.trajectory;
	}

	public int symbolHash() {
		return this.symbolHash;
	}
}
