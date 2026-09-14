package com.example.golem_covenant.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * Custom ParticleTypes for the ritual layer (spec 11.15.2 / 13.2.1).
 *
 * <p>Spec 13.1/13.2 is explicit that circles must NOT be built from entities:
 * they are client-side particles. We register one particle per anchor family
 * (10 in total, inside the 20-30 the spec budgets) plus the shared core spark.
 */
public final class ModParticles {

	private ModParticles() {
	}

	private static final Map<String, SimpleParticleType> BY_FAMILY = new HashMap<>();
	private static final List<SimpleParticleType> ALL = new ArrayList<>();

	/** Family -> registry path, matching the ritual `particleType` in JSON. */
	private static final Map<String, String> FAMILY_PATHS = Map.of(
			"zombie", "soul_ash",
			"arthropod", "silk_strand",
			"animal", "beast_ember",
			"mount", "hoof_dust",
			"snow", "frost_mote",
			"environment", "biome_spore",
			"aquatic", "tide_drop",
			"construct", "iron_spark",
			"nether_end", "void_shard",
			"special", "detonation_glyph");

	public static SimpleParticleType CORE_SPARK;
	public static SimpleParticleType REVIVE_BURST;

	public static void register() {
		for (Map.Entry<String, String> e : FAMILY_PATHS.entrySet()) {
			SimpleParticleType t = register(e.getValue());
			BY_FAMILY.put(e.getKey(), t);
		}
		CORE_SPARK = register("core_spark");
		REVIVE_BURST = register("revive_burst");
	}

	private static SimpleParticleType register(String path) {
		SimpleParticleType type = FabricParticleTypes.simple();
		Registry.register(BuiltInRegistries.PARTICLE_TYPE,
				GolemCovenantMod.id(path), type);
		ALL.add(type);
		return type;
	}

	public static Optional<SimpleParticleType> forFamily(String familyId) {
		return Optional.ofNullable(BY_FAMILY.get(familyId));
	}

	/**
	 * A family's particle, falling back to {@code fallback} when the family is
	 * unknown or has not been registered yet. Used by the anchor runtime, which
	 * must never fail just because a particle is missing (spec 11.1.3).
	 */
	public static SimpleParticleType familyOr(String familyId,
			SimpleParticleType fallback) {
		SimpleParticleType t = BY_FAMILY.get(familyId);
		return t != null ? t : fallback;
	}

	public static List<SimpleParticleType> all() {
		return List.copyOf(ALL);
	}
}
