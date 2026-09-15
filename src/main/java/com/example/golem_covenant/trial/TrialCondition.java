package com.example.golem_covenant.trial;

/**
 * One quantified requirement of a trial (spec 11.8).
 *
 * <p>Spec 11.8 exists precisely because the original text described the 第二灵魂
 * trial with words like "完成多个" / "获得稀有" / "高难度" that cannot be
 * implemented. The resolution was to fix a number for each clause, and this
 * record is that number carried at runtime:
 *
 * <ul>
 *   <li>{@code id} - a stable key, used to build the translation key
 *       {@code golem_covenant.trial.<id>} and to persist credit;</li>
 *   <li>{@code progress} - how much the player has actually done;</li>
 *   <li>{@code required} - the spec's quantified threshold.</li>
 * </ul>
 *
 * <p>Deliberately two numbers rather than a boolean: a trial whose parts only
 * report "done / not done" cannot show a player how close they are, and spec
 * 4.3.3 wants this to feel like a long goal rather than a locked door.
 *
 * @param id       stable condition key, e.g. {@code "distinct_anchors"}
 * @param progress current progress, clamped to {@code required}
 * @param required the spec 11.8 threshold
 */
public record TrialCondition(String id, int progress, int required) {

	/** True when this clause of the trial is satisfied. */
	public boolean done() {
		return this.progress >= this.required;
	}

	/** Translation key for the condition's description in the readout. */
	public String descriptionKey() {
		return "golem_covenant.trial." + this.id;
	}

	/** {@code "4 / 6"} - what the player reads in the trial readout. */
	public String progressText() {
		return Math.min(this.progress, this.required) + " / " + this.required;
	}

	/** Used when a condition is binary (done / not done) rather than counted. */
	public static TrialCondition flag(String id, boolean done) {
		return new TrialCondition(id, done ? 1 : 0, 1);
	}

	/** Builds a counted condition without letting progress exceed the target. */
	public static TrialCondition of(String id, int progress, int required) {
		return new TrialCondition(id, Math.clamp(progress, 0, required), required);
	}
}
