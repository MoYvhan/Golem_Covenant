package com.example.golem_covenant.ritual;

import java.util.List;

import com.example.golem_covenant.data.CovenantTier;

/**
 * The staged structure of a ceremony (spec 5.2).
 *
 * <p>Spec 5.2 does not describe a circle that spins for N ticks - it describes
 * an <em>ordered sequence of visual events</em>:
 *
 * <pre>
 *   B (1.5-2.8s)  ground ring -> creature totem -> soul particles
 *                 -> core ignition -> revival
 *   C (3.5-7s)    triple rotating ring -> vertical runes -> unique totem
 *                 -> soul extraction/return -> brief levitation
 *                 -> core forging -> final burst
 * </pre>
 *
 * <p>The previous implementation was a stateless per-tick loop, so B and C
 * differed only by layer count and duration: a C ceremony was just "a B
 * ceremony that lasts longer". This enum makes the sequence explicit, and
 * {@link RitualChoreography} gives each stage its own particle behaviour.
 *
 * <p>Stage boundaries are expressed as fractions of the total duration rather
 * than fixed tick counts, so the same choreography works for the B window
 * (30-56 ticks) and the C window (70-140 ticks) without a second table.
 */
public enum RitualStage {

	// --- A tier: a single quick mark, no large ceremony (spec 5.2) --------
	/** The golden pact mark: brief, low particle count, no rotation ramp. */
	PACT_MARK(0.0, 1.0),

	// --- B tier: five stages (spec 5.2) ----------------------------------
	/** 1. The ground ring draws itself outward. */
	GROUND_RING(0.0, 0.28),
	/** 2. The creature's totem appears inside the ring. */
	CREATURE_TOTEM(0.28, 0.50),
	/** 3. Soul particles converge on the companion. */
	SOUL_CONVERGE(0.50, 0.72),
	/** 4. The core lights up. */
	CORE_IGNITE(0.72, 0.88),
	/** 5. Revival - the ring snaps inward and the burst fires. */
	REVIVAL(0.88, 1.0),

	// --- C tier: seven stages (spec 5.2) ---------------------------------
	/** 1. Three counter-rotating rings at different heights. */
	TRIPLE_RING(0.0, 0.18),
	/** 2. Vertical rune column rises through the ring stack. */
	VERTICAL_RUNE(0.18, 0.36),
	/** 3. The form's unique totem traces itself above the rings. */
	UNIQUE_TOTEM(0.36, 0.52),
	/** 4. Soul is drawn out of the world and poured back into the target. */
	SOUL_TRANSFER(0.52, 0.68),
	/** 5. The target lifts off the ground. */
	LEVITATION(0.68, 0.80),
	/** 6. The core is forged: the sphere is hammered into shape. */
	CORE_FORGING(0.80, 0.92),
	/** 7. The final burst and covenant lock. */
	FINAL_BURST(0.92, 1.0);

	private final double from;
	private final double to;

	RitualStage(double from, double to) {
		this.from = from;
		this.to = to;
	}

	public double from() {
		return this.from;
	}

	public double to() {
		return this.to;
	}

	/** Progress through this stage, clamped to 0..1. */
	public double progress(double overall) {
		double span = this.to - this.from;
		if (span <= 0.0) {
			return 1.0;
		}
		return Math.clamp((overall - this.from) / span, 0.0, 1.0);
	}

	/** Whether {@code overall} (0..1) falls inside this stage. */
	public boolean covers(double overall) {
		return overall >= this.from && overall < this.to;
	}

	/** The ordered stage list for a tier (spec 5.2). */
	public static List<RitualStage> forTier(CovenantTier tier) {
		return switch (tier) {
			case A -> List.of(PACT_MARK);
			case B -> List.of(GROUND_RING, CREATURE_TOTEM, SOUL_CONVERGE,
					CORE_IGNITE, REVIVAL);
			case C -> List.of(TRIPLE_RING, VERTICAL_RUNE, UNIQUE_TOTEM,
					SOUL_TRANSFER, LEVITATION, CORE_FORGING, FINAL_BURST);
		};
	}

	/**
	 * The stage active at {@code overall}, or the last stage when the value
	 * pins at 1.0 (so the final frame still renders the closing effect).
	 */
	public static RitualStage at(CovenantTier tier, double overall) {
		List<RitualStage> stages = forTier(tier);
		for (RitualStage stage : stages) {
			if (stage.covers(overall)) {
				return stage;
			}
		}
		return stages.get(stages.size() - 1);
	}

	/** 1-based index of this stage within its tier's sequence. */
	public int indexIn(CovenantTier tier) {
		return forTier(tier).indexOf(this) + 1;
	}

	/**
	 * Relative screen-shake strength for this stage's entry (spec 13.1).
	 *
	 * <p>Not every stage shakes: a camera beat on all nine stages of a C
	 * ceremony would be noise, and the point of the setting is that the shake
	 * <em>marks</em> the ceremony's weight. The heavy beats are the ones where
	 * something physically consequential happens - the ring snapping closed,
	 * the soul being torn out, the core being struck, the final lock.
	 *
	 * @return 0.0 for no shake, otherwise 0.0-1.0
	 */
	public double shakeOnEnter() {
		return switch (this) {
			case PACT_MARK -> 0.20;
			case GROUND_RING, TRIPLE_RING -> 0.35;
			case CREATURE_TOTEM, UNIQUE_TOTEM -> 0.30;
			case SOUL_CONVERGE, SOUL_TRANSFER -> 0.75;
			case CORE_IGNITE -> 0.55;
			case VERTICAL_RUNE -> 0.25;
			case LEVITATION -> 0.45;
			case CORE_FORGING -> 0.85;
			case REVIVAL, FINAL_BURST -> 1.0;
		};
	}

	/**
	 * Whether this stage's entry beat warrants a shake at all.
	 *
	 * <p>Kept as a separate predicate because "zero intensity" and "no beat"
	 * are different things to the client, and unit tests assert on this.
	 */
	public boolean shakes() {
		return shakeOnEnter() > 0.0;
	}
}
