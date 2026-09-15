package com.example.golem_covenant.client;

/**
 * Client-side screen-shake state (spec 13.1).
 *
 * <p>Pure state and maths, with no rendering dependency, so it can be driven
 * from the packet handler and read from the mixin without either knowing about
 * the other. That separation is also what makes the decay curve testable.
 *
 * <p>The shake is deliberately a <em>rotational</em> jitter around the
 * player's own view direction rather than a positional offset. A positional
 * shake can push the camera inside terrain, which reads as a glitch; a small
 * roll and pitch jitter reads as impact and never clips geometry.
 */
public final class CameraShake {

	private CameraShake() {
	}

	/** Remaining strength, decays to 0. */
	private static float intensity = 0.0f;
	/** Total beats currently queued, so overlapping beats do not stack into
	 * an unreadable mess. */
	private static float peak = 1.0f;
	private static int remainingTicks = 0;
	private static int totalTicks = 0;

	/** The client's own preference multiplier (spec 13.1 shakeLevel). */
	private static float preference = 1.0f;

	/**
	 * Queues a shake beat.
	 *
	 * @param intensity 0..1 relative strength from the server
	 * @param ticks     duration in ticks
	 */
	public static void trigger(float intensity, int ticks) {
		if (intensity <= 0.0f || ticks <= 0) {
			return;
		}
		// Take the max rather than summing: two simultaneous ceremonies
		// should read as one strong beat, not shake the camera off its axis.
		CameraShake.intensity = Math.max(CameraShake.intensity, intensity);
		CameraShake.peak = Math.max(CameraShake.peak, intensity);
		CameraShake.remainingTicks = Math.max(CameraShake.remainingTicks, ticks);
		CameraShake.totalTicks = Math.max(CameraShake.totalTicks, ticks);
	}

	/** Spec 13.1: 0.0 off, 0.35 weak, 1.0 normal, 1.75 strong. */
	public static void setPreference(int shakeOrdinal) {
		preference = switch (shakeOrdinal) {
			case 0 -> 0.0f;
			case 1 -> 0.35f;
			case 2 -> 1.0f;
			case 3 -> 1.75f;
			default -> 1.0f;
		};
	}

	public static void tick() {
		if (remainingTicks > 0) {
			remainingTicks--;
			if (remainingTicks == 0) {
				intensity = 0.0f;
				peak = 1.0f;
				totalTicks = 0;
			}
		}
	}

	/** Current effective strength after preference and decay. */
	public static float strength() {
		if (remainingTicks <= 0 || totalTicks <= 0) {
			return 0.0f;
		}
		float life = remainingTicks / (float) totalTicks;
		// Quadratic falloff: the beat lands hard and settles quickly, which
		// is what makes a short impact feel like impact.
		return intensity * life * life * preference;
	}

	/**
	 * The roll (z-axis) offset in degrees for this frame.
	 *
	 * @param partialTick sub-tick interpolation, so the shake is smooth
	 *                    rather than stepping at 20 Hz
	 */
	public static float rollDegrees(float partialTick) {
		float s = strength();
		if (s <= 0.0f) {
			return 0.0f;
		}
		double t = (System.nanoTime() / 1.0e9) * 18.0;
		// A single sine reads as a smooth sway; the second harmonic adds the
		// "crack" that makes it feel like an impact instead of a wobble.
		return (float) (Math.sin(t) * 0.6 + Math.sin(t * 2.7) * 0.4)
				* s * 3.2f;
	}

	/**
	 * The pitch offset in degrees for this frame.
	 *
	 * <p>Kept smaller than the roll: vertical camera movement is the one most
	 * likely to cause motion discomfort, so it is deliberately restrained.
	 */
	public static float pitchDegrees(float partialTick) {
		float s = strength();
		if (s <= 0.0f) {
			return 0.0f;
		}
		double t = (System.nanoTime() / 1.0e9) * 14.0;
		return (float) (Math.sin(t * 1.3 + 0.7)) * s * 1.8f;
	}

	public static boolean active() {
		return remainingTicks > 0;
	}

	public static void reset() {
		intensity = 0.0f;
		peak = 1.0f;
		remainingTicks = 0;
		totalTicks = 0;
	}

	/** Exposed for diagnostics / the debug HUD. */
	public static int remainingTicks() {
		return remainingTicks;
	}
}
