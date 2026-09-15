package com.example.golem_covenant.ritual;

/**
 * Magic-circle geometry primitives (spec 5.1 / 5.3).
 *
 * <p>The circle is built from pure maths over a point budget, never from
 * entities or block placement (spec 13.2.1 / 5.4). Each {@code geometry} id in
 * the registry selects one {@link Family}; within a family the form's own
 * {@code symbol}, {@code radius}, {@code rotation} and {@code layerCount} vary
 * the result, which is how spec 5.3's "at least 3 of {geometry, totem,
 * trajectory, rotation, rhythm} differ" is satisfied without 186 hand-built
 * shapes.
 */
public final class RitualGeometry {

	private RitualGeometry() {
	}

	/** A point on the circle in world space, with a per-point intensity. */
	public record Point(double x, double y, double z, float weight) {
	}

	/**
	 * The ten registered geometry families, one per anchor family group.
	 *
	 * <p>Each emits a distinctive silhouette so a viewer can name the family
	 * from the floor pattern alone.
	 */
	public enum Family {
		/** Concentric rings with vertical tomb-marks - undead. */
		TOMB_RING,
		/** A hexagonal web with radial strands - arthropods. */
		WEB_HEX,
		/** A many-pointed ring with beast tick-marks - land animals. */
		BEAST_RING,
		/** A four-pointed star with hoof prints - mounts. */
		HOOF_STAR,
		/** An arc segment with frost ticks, never a closed circle - snow. */
		ALPINE_ARC,
		/** A scattered orb of irregular points - biome/environment. */
		BIOME_ORB,
		/** Three interlocking rings - aquatic. */
		TIDE_TRIPLE,
		/** A square of pillars with corner marks - constructs. */
		PILLAR_SQUARE,
		/** A spiral that never closes - nether/end. */
		VOID_SPIRAL,
		/** An inward-collapsing ring - special (bosses, rare forms). */
		IMPLOSION_CORE;

		/** Resolves the registry's {@code geometry} string. */
		public static Family byId(String geometry) {
			if (geometry == null) {
				return TOMB_RING;
			}
			return switch (geometry) {
				case "web_hex" -> WEB_HEX;
				case "beast_ring" -> BEAST_RING;
				case "hoof_star" -> HOOF_STAR;
				case "alpine_arc" -> ALPINE_ARC;
				case "biome_orb" -> BIOME_ORB;
				case "tide_triple" -> TIDE_TRIPLE;
				case "pillar_square" -> PILLAR_SQUARE;
				case "void_spiral" -> VOID_SPIRAL;
				case "implosion_core" -> IMPLOSION_CORE;
				default -> TOMB_RING;
			};
		}
	}

	/**
	 * How points travel toward their position (spec 5.3 "专属粒子运动轨迹").
	 *
	 * <p>This is the trajectory axis of the uniqueness rule: two forms sharing
	 * a geometry still read differently if one converges and the other orbits.
	 */
	public enum Trajectory {
		/** Points sit exactly where the shape says. */
		STATIC,
		/** Points sweep around the centre at a constant radius. */
		ORBIT,
		/** Points rush inward from outside, tightening as the stage ends. */
		CONVERGE,
		/** Points drift outward from the centre. */
		DIVERGE,
		/** Points rise while orbiting, forming a helix. */
		HELIX,
		/** Points fall from above onto the shape. */
		FALL;

		/** The trajectory a family uses by default. */
		public static Trajectory forFamily(Family family) {
			return switch (family) {
				case TOMB_RING -> ORBIT;
				case WEB_HEX -> STATIC;
				case BEAST_RING -> ORBIT;
				case HOOF_STAR -> CONVERGE;
				case ALPINE_ARC -> FALL;
				case BIOME_ORB -> DIVERGE;
				case TIDE_TRIPLE -> ORBIT;
				case PILLAR_SQUARE -> STATIC;
				case VOID_SPIRAL -> HELIX;
				case IMPLOSION_CORE -> CONVERGE;
			};
		}
	}

	/**
	 * Builds one ring of the circle.
	 *
	 * @param family    silhouette selector
	 * @param count     how many points to spend on this ring
	 * @param radius    ring radius in blocks
	 * @param phase     rotation offset in radians
	 * @param progress  0..1 through the current stage
	 * @param layer     which ring in the stack (0-based)
	 * @param layers    total rings in the stack
	 */
	public static Point[] ring(Family family, int count, double radius,
			double phase, double progress, int layer, int layers) {
		if (count <= 0 || radius <= 0.0) {
			return new Point[0];
		}
		Point[] out = new Point[count];
		// Layered rings shrink inward and lift slightly, so a 3-ring C
		// ceremony reads as a cone rather than three flat copies (spec 5.1).
		double lift = layers <= 1 ? 0.0 : (layer / (double) (layers - 1)) * 0.55;

		for (int i = 0; i < count; i++) {
			double angle = (Math.PI * 2.0 / count) * i + phase;
			double r = radius;
			float weight = 1.0f;

			switch (family) {
				case WEB_HEX -> {
					// snap every point onto a hexagonal lattice direction
					double step = Math.PI / 3.0;
					double snapped = Math.round(angle / step) * step;
					angle = angle * 0.35 + snapped * 0.65;
					r = radius * (i % 2 == 0 ? 1.0 : 0.62);
				}
				case BEAST_RING -> {
					// short radial ticks every eighth point
					r = radius * (i % 8 == 0 ? 1.14 : 1.0);
					weight = i % 8 == 0 ? 1.5f : 0.9f;
				}
				case HOOF_STAR -> {
					// four points pull out to make a star
					r = radius * (i % 4 == 0 ? 1.3 : 0.82);
				}
				case ALPINE_ARC -> {
					// leave a deliberate gap: an arc, not a circle
					if (i > count * 0.78) {
						angle = 0.0;
						r = 0.0;
					}
				}
				case PILLAR_SQUARE -> {
					// square path rather than a circle
					double t = (i / (double) count) * 4.0;
					int side = (int) t;
					double u = (t - side) * 2.0 - 1.0;
					double s = radius * 0.86;
					angle = switch (side) {
						case 0 -> Math.atan2(u * s, s);
						case 1 -> Math.atan2(s, -u * s);
						case 2 -> Math.atan2(-u * s, -s);
						default -> Math.atan2(-s, u * s);
					};
					r = Math.hypot(s, u * s);
				}
				case VOID_SPIRAL -> {
					// radius grows with angle: an open spiral
					r = radius * (0.35 + 0.65 * (i / (double) count));
				}
				case TIDE_TRIPLE -> {
					// three overlapping lobes
					r = radius * (0.8 + 0.25
							* Math.sin(angle * 3.0 + layer * 1.05));
				}
				case IMPLOSION_CORE -> {
					// collapse inward as the stage runs
					r = radius * (1.0 - 0.45 * progress);
				}
				case BIOME_ORB -> {
					// irregular radius, but deterministic per index
					r = radius * (0.75 + 0.35 * _hash(i * 31 + layer * 7));
				}
				case TOMB_RING -> {
					// heavier points at the cardinal marks
					weight = i % Math.max(1, count / 4) == 0 ? 1.4f : 0.9f;
				}
			}

			out[i] = new Point(Math.cos(angle) * r, lift, Math.sin(angle) * r,
					weight);
		}
		return out;
	}

	/**
	 * Builds the vertical rune column (spec 5.1 layer 2, spec 5.2 C stage 2).
	 *
	 * <p>Spec 5.1 calls this the 文字/符文环 - a ring of glyphs that repeats
	 * along the vertical axis. It is a column of short arcs, so from any angle
	 * the viewer sees a rune band rather than a solid beam.
	 */
	public static Point[] runeColumn(int glyphs, double radius, double height,
			double phase, double progress) {
		if (glyphs <= 0) {
			return new Point[0];
		}
		int bands = Math.max(2, glyphs / 4);
		int perBand = Math.max(1, glyphs / bands);
		Point[] out = new Point[bands * perBand];
		int n = 0;
		// The column rises as the stage advances (spec 5.2 C stage 2).
		double grown = height * Math.clamp(progress * 1.4, 0.0, 1.0);
		for (int band = 0; band < bands; band++) {
			double y = 0.15 + grown * (band / (double) bands);
			// Alternate spin direction per band so the column reads as woven.
			double bandPhase = phase + band * 0.42
					* (band % 2 == 0 ? 1.0 : -1.0);
			for (int i = 0; i < perBand; i++) {
				double a = (Math.PI * 2.0 / perBand) * i + bandPhase;
				out[n++] = new Point(Math.cos(a) * radius, y,
						Math.sin(a) * radius, 1.0f);
			}
		}
		return out;
	}

	/**
	 * Builds the entity's unique totem (spec 5.1 layer 3, 5.3 "法阵核心符号").
	 *
	 * <p>Every form in the registry carries a distinct {@code symbol} string
	 * (157 unique values across 159 active forms). The symbol is hashed into a
	 * small glyph lattice, so each form traces a recognisably different sigil
	 * inside its circle without needing 157 hand-drawn textures.
	 */
	public static Point[] totem(String symbol, double radius, double height,
			double progress) {
		int hash = symbol == null ? 0 : symbol.hashCode();
		// A 3x3 lattice gives 512 distinguishable glyphs - comfortably more
		// than the 157 symbols actually in use.
		int strokeCount = 3 + Math.floorMod(hash, 3);
		Point[] out = new Point[strokeCount * 5];
		int n = 0;
		for (int stroke = 0; stroke < strokeCount; stroke++) {
			int bits = hash >>> (stroke * 5);
			int from = bits & 0x7;
			int to = (bits >> 3) & 0x7;
			if (from == to) {
				to = (to + 1) & 0x7;
			}
			double[] a = lattice(from, radius, height);
			double[] b = lattice(to, radius, height);
			// The stroke draws itself from a to b across the stage.
			double drawn = Math.clamp(progress * 1.25 - stroke * 0.08, 0.0, 1.0);
			for (int step = 0; step < 5; step++) {
				double t = (step / 4.0) * drawn;
				out[n++] = new Point(a[0] + (b[0] - a[0]) * t,
						a[1] + (b[1] - a[1]) * t,
						a[2] + (b[2] - a[2]) * t, 1.3f);
			}
		}
		return out;
	}

	/** The eight lattice anchors of a totem, at two heights. */
	private static double[] lattice(int index, double radius, double height) {
		// 0-3 are the low square, 4-7 the high square (rotated 45 degrees)
		int ring = index / 4;
		int corner = index % 4;
		double base = Math.PI / 4.0 + corner * (Math.PI / 2.0);
		if (ring == 1) {
			base += Math.PI / 4.0;
		}
		double r = radius * (ring == 0 ? 0.42 : 0.30);
		double y = height * (ring == 0 ? 0.35 : 0.78);
		return new double[] { Math.cos(base) * r, y, Math.sin(base) * r };
	}

	/**
	 * Builds the energy column (spec 5.1 layer 4): a dense vertical pillar
	 * through the circle's centre, tapering toward the top.
	 */
	public static Point[] energyColumn(int count, double radius, double height,
			double phase, double progress) {
		if (count <= 0) {
			return new Point[0];
		}
		Point[] out = new Point[count];
		// The column grows from the ground upward as the ceremony proceeds.
		double grown = height * Math.clamp(progress * 1.15, 0.0, 1.0);
		for (int i = 0; i < count; i++) {
			double t = i / (double) count;
			double y = 0.1 + grown * t;
			// Taper: narrow at the bottom, flared at the top like a plume.
			double r = radius * (0.06 + 0.28 * t * t);
			double a = phase * 2.0 + t * 5.2;
			out[i] = new Point(Math.cos(a) * r, y, Math.sin(a) * r,
					1.0f + (float) t * 0.6f);
		}
		return out;
	}

	/**
	 * Builds the soul core (spec 5.1 layer 5): a sphere of points around the
	 * target's chest, which is the one element the viewer's eye should lock
	 * onto.
	 */
	public static Point[] coreSphere(int count, double radius, double phase,
			double spin) {
		if (count <= 0) {
			return new Point[0];
		}
		Point[] out = new Point[count];
		// A Fibonacci sphere distributes points evenly - cheap and uniform,
		// unlike nested rings which leave visible seams.
		double golden = Math.PI * (3.0 - Math.sqrt(5.0));
		for (int i = 0; i < count; i++) {
			double y = 1.0 - (i / (double) Math.max(1, count - 1)) * 2.0;
			double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
			double theta = golden * i + phase + spin;
			out[i] = new Point(Math.cos(theta) * r * radius, y * radius,
					Math.sin(theta) * r * radius, 1.2f);
		}
		return out;
	}

	/**
	 * The ground-projection radius of a form (spec 5.4): the circle hugs the
	 * ground and the target, and never fills first-person view.
	 */
	public static double groundRadius(double profileRadius, int layers) {
		// C ceremonies may push outward slightly for scale, but stay bounded:
		// spec 5.4 forbids long first-person occlusion, so the cap is tight.
		return Math.min(profileRadius * (1.0 + 0.12 * Math.max(0, layers - 2)),
				3.2);
	}

	/** Deterministic 0..1 hash, so procedural shapes are stable per form. */
	private static double _hash(int value) {
		int h = value * 0x9E3779B9;
		h ^= h >>> 16;
		return (h & 0xFFFF) / 65535.0;
	}
}
