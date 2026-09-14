package com.example.golem_covenant.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 法阵 / 复生仪式 profile (spec 5.7).
 *
 * <p>Every form owns its own "magic-circle language": spec 5.3 forbids merely
 * recolouring one shared circle, and requires each form to change at least
 * three of {geometry, totem, particle path, rotation, sound rhythm}.
 */
public record RitualProfile(
		String geometry,
		String particleType,
		String particleColor,
		String rotation,
		float radius,
		int durationBTicks,
		int durationCTicks,
		String coreShape,
		String symbol,
		String soundPattern,
		int layerCount) {

	public static final Codec<RitualProfile> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					Codec.STRING.fieldOf("geometry").forGetter(RitualProfile::geometry),
					Codec.STRING.optionalFieldOf("particleType", "minecraft:end_rod").forGetter(RitualProfile::particleType),
					Codec.STRING.optionalFieldOf("particleColor", "#FFFFFF").forGetter(RitualProfile::particleColor),
					Codec.STRING.optionalFieldOf("rotation", "cw").forGetter(RitualProfile::rotation),
					Codec.FLOAT.optionalFieldOf("radius", 2.0f).forGetter(RitualProfile::radius),
					Codec.INT.optionalFieldOf("durationB", 36).forGetter(RitualProfile::durationBTicks),
					Codec.INT.optionalFieldOf("durationC", 100).forGetter(RitualProfile::durationCTicks),
					Codec.STRING.optionalFieldOf("coreShape", "core_orb").forGetter(RitualProfile::coreShape),
					Codec.STRING.optionalFieldOf("symbol", "").forGetter(RitualProfile::symbol),
					Codec.STRING.optionalFieldOf("soundPattern", "").forGetter(RitualProfile::soundPattern),
					Codec.INT.optionalFieldOf("layerCount", 2).forGetter(RitualProfile::layerCount)
			).apply(i, RitualProfile::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, RitualProfile> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, RitualProfile::geometry,
					ByteBufCodecs.STRING_UTF8, RitualProfile::particleType,
					ByteBufCodecs.STRING_UTF8, RitualProfile::particleColor,
					ByteBufCodecs.STRING_UTF8, RitualProfile::rotation,
					ByteBufCodecs.FLOAT, RitualProfile::radius,
					ByteBufCodecs.VAR_INT, RitualProfile::durationBTicks,
					ByteBufCodecs.VAR_INT, RitualProfile::durationCTicks,
					ByteBufCodecs.STRING_UTF8, RitualProfile::coreShape,
					ByteBufCodecs.STRING_UTF8, RitualProfile::symbol,
					ByteBufCodecs.STRING_UTF8, RitualProfile::soundPattern,
					ByteBufCodecs.VAR_INT, RitualProfile::layerCount,
					RitualProfile::new);

	/**
	 * Spec 5.2: A is short and has no large ceremony; B runs 1.5-2.8s;
	 * C runs 3.5-7s. Values are normalised into those windows here so a bad
	 * data file cannot produce an off-spec ceremony.
	 */
	public int durationTicks(CovenantTier tier) {
		return switch (tier) {
			case A -> 20;
			case B -> Math.clamp(this.durationBTicks, 30, 56);
			case C -> Math.clamp(this.durationCTicks, 70, 140);
		};
	}

	public boolean counterClockwise() {
		return "ccw".equalsIgnoreCase(this.rotation);
	}
}
