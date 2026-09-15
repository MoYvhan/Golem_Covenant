package com.example.golem_covenant.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.core.particles.SimpleParticleType;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.client.particle.RitualParticle;
import com.example.golem_covenant.registry.ModParticles;

/**
 * Client-side particle registry (spec 5.7 / 12.4 / 13.2).
 *
 * <p>Before this class existed the mod registered twelve particle types and no
 * providers, so the client fell back to the vanilla renderer and every ritual
 * particle drew as a generic sprite (or the missing-texture checkerboard).
 * Here each type is bound to a {@link Style}: the physical character of the
 * particle. That is what lets a zombie's soul ash drift and fade while a
 * construct's iron spark drops and bounces - using one class, not twelve.
 *
 * <p>The styles are keyed by registry path and mirrored in
 * {@code tools/gen_assets.py}; {@code tools/validate_particles.py} keeps the
 * two in sync, so a type can never be registered with no provider again.
 *
 * @see Style
 */
public final class RitualParticles {

	private RitualParticles() {
	}

	/** Registry path -> style. Mirrors ModParticles' path set exactly. */
	private static final Map<String, Style> STYLES = new LinkedHashMap<>();

	/**
	 * The physical and colour character of one particle type.
	 *
	 * <p>All fields are deliberately plain values rather than an interface:
	 * a ritual effect is data, and spec 12.6 requires that adding a form must
	 * never require a new class.
	 *
	 * @param red            tint red, 0..1 (only used when {@code tint})
	 * @param green          tint green, 0..1
	 * @param blue           tint blue, 0..1
	 * @param tint           whether to multiply the atlas colour by the tint
	 * @param size           base quad size in blocks
	 * @param lifeMin        minimum lifetime in ticks
	 * @param lifeMax        maximum lifetime in ticks
	 * @param rise           constant per-tick vertical acceleration
	 * @param shrink         per-tick size multiplier, 1.0 to disable
	 * @param roll           per-tick self-rotation in radians
	 * @param buoyancy       extra vertical acceleration (negative sinks)
	 * @param gravity        vanilla gravity term
	 * @param friction       vanilla drag term
	 * @param hasPhysics     whether the particle collides with blocks
	 */
	public record Style(float red, float green, float blue, boolean tint,
			float size, int lifeMin, int lifeMax, float rise, float shrink,
			float roll, float buoyancy, float gravity, float friction,
			boolean hasPhysics) {

		/** A short-lived, gently rising ember that shrinks as it dies. */
		public static Style ember(float r, float g, float b, boolean tint) {
			return new Style(r, g, b, tint, 0.11f, 18, 34, 0.006f, 0.97f,
					0.0f, 0.0f, -0.008f, 0.98f, false);
		}

		/** A slow floating mote: drift upward, hang, fade in place. */
		public static Style mote(float r, float g, float b, boolean tint) {
			return new Style(r, g, b, tint, 0.09f, 30, 55, 0.004f, 1.0f,
					0.0f, 0.006f, -0.004f, 0.97f, false);
		}

		/** A casting spark that spins on its own axis. */
		public static Style spark(float r, float g, float b, boolean tint) {
			return new Style(r, g, b, tint, 0.10f, 12, 24, 0.0f, 0.94f,
					0.35f, 0.0f, 0.02f, 0.96f, true);
		}

		/** A heavy grain that falls: dust, grit, shards. */
		public static Style grain(float r, float g, float b, boolean tint) {
			return new Style(r, g, b, tint, 0.08f, 20, 40, 0.0f, 0.99f,
					0.0f, 0.0f, 0.05f, 0.99f, true);
		}

		/** A wide, brief flash for detonations and finishing bursts. */
		public static Style burst(float r, float g, float b, boolean tint) {
			return new Style(r, g, b, tint, 0.26f, 8, 18, 0.0f, 0.88f,
					0.0f, 0.0f, 0.0f, 0.90f, false);
		}
	}

	static {
		// --- family particles (mirrors ModParticles.FAMILY_PATHS) ----------
		// soul ash: necrotic residue, drifts up and fades
		STYLES.put("soul_ash", Style.mote(0.56f, 0.66f, 0.55f, true));
		// silk strand: pale, lighter than air, spins slowly
		STYLES.put("silk_strand", Style.mote(0.79f, 0.84f, 0.77f, true));
		// beast ember: warm, rises fast, flickers
		STYLES.put("beast_ember", Style.ember(0.71f, 0.46f, 0.24f, false));
		// hoof dust: kicked-up grit that settles
		STYLES.put("hoof_dust", Style.grain(0.63f, 0.55f, 0.42f, true));
		// frost mote: cold, slow, hangs in the air
		STYLES.put("frost_mote", Style.mote(0.75f, 0.89f, 0.95f, false));
		// biome spore: slow organic drift
		STYLES.put("biome_spore", Style.mote(0.50f, 0.65f, 0.36f, true));
		// tide drop: falls like water
		STYLES.put("tide_drop", Style.grain(0.31f, 0.61f, 0.77f, false));
		// iron spark: hard, fast, collides
		STYLES.put("iron_spark", Style.spark(0.79f, 0.64f, 0.15f, false));
		// void shard: slow arcane drift
		STYLES.put("void_shard", Style.mote(0.48f, 0.36f, 0.66f, false));
		// detonation glyph: brief violent flash
		STYLES.put("detonation_glyph", Style.burst(0.85f, 0.28f, 0.23f, false));
		// --- shared structural particles ----------------------------------
		// core spark: the golden ring / pillar / core material
		STYLES.put("core_spark", Style.ember(0.91f, 0.83f, 0.55f, true));
		// revive burst: the closing flash of every ceremony
		STYLES.put("revive_burst", Style.burst(0.62f, 0.90f, 0.69f, true));
	}

	/**
	 * Registers a provider for every particle type in {@link ModParticles}.
	 *
	 * <p>Called from the client initialiser. Any type with no declared style is
	 * skipped rather than crashing, but the build gate reports it as an error
	 * first (spec 11.1.3: a missing cosmetic must never take the game down).
	 */
	public static void register() {
		int bound = 0;
		List<String> unbound = new ArrayList<>();

		for (Map.Entry<String, SimpleParticleType> entry
				: ModParticles.allByPath().entrySet()) {
			String path = entry.getKey();
			Style style = STYLES.get(path);
			if (style == null) {
				unbound.add(path);
				continue;
			}
			// The sprite set comes from the particle definition file named
			// after the registry path, so art can be replaced by a resource
			// pack without touching code (spec 11.15).
			ParticleProviderRegistry.getInstance().register(
					entry.getValue(),
					sprites -> new RitualParticle.Provider(sprites, style));
			bound++;
		}

		if (!unbound.isEmpty()) {
			GolemCovenantMod.LOGGER.warn(
					"{} particle type(s) had no client style and will render "
							+ "as vanilla: {}", unbound.size(), unbound);
		}
		GolemCovenantMod.LOGGER.info(
				"registered {} custom particle providers", bound);
	}

	/** Exposed for the ritual engine's debug overlay. */
	public static int styleCount() {
		return STYLES.size();
	}

	/** Registry path -> style, for tests and tooling. */
	public static Map<String, Style> styles() {
		return Map.copyOf(STYLES);
	}
}
