package com.example.golem_covenant.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 能力组件 profile (spec 12.2).
 *
 * <p>186 forms are produced by COMBINING components with different parameters
 * rather than by writing 186 AI classes (spec 12.6).
 *
 * <p>The component set mirrors the reference list in spec 12.2
 * (Follow / Protect / Mark / Area / Predict / Dash / Chain / Reflect /
 * Teleport / Resource / Summon / HealPulse / Elemental / DeathWill).
 */
public record AbilityProfile(
		String activeId,
		String activeName,
		String passiveId,
		String passiveName,
		String passiveDesc,
		String anchorId,
		String secondMechanicId,
		String secondMechanicName,
		String secondMechanicDesc,
		List<String> components,
		float range,
		int cooldownTicks,
		int targetPriority) {

	/**
	 * Ability component vocabulary (spec 12.2). Kept as string ids so the same
	 * list can drive both the JSON registry and runtime dispatch.
	 */
	public static final List<String> ALL_COMPONENTS = List.of(
			"follow", "protect", "mark", "area", "predict", "dash", "chain",
			"reflect", "teleport", "resource", "summon", "heal_pulse",
			"elemental", "death_will", "intercept", "taunt", "purify",
			"detect", "light", "exp_echo");

	/** 11.11 ability budget: cooldown bands per ability family. */
	public static final int CD_MARK = 120;
	public static final int CD_AREA = 400;
	public static final int CD_EMERGENCY = 800;
	public static final int CD_COUNTER = 700;
	public static final int CD_DEATH_WILL = 6000;

	public static final Codec<AbilityProfile> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					Codec.STRING.optionalFieldOf("activeId", "").forGetter(AbilityProfile::activeId),
					Codec.STRING.optionalFieldOf("activeName", "").forGetter(AbilityProfile::activeName),
					Codec.STRING.optionalFieldOf("passiveId", "").forGetter(AbilityProfile::passiveId),
					Codec.STRING.optionalFieldOf("passiveName", "").forGetter(AbilityProfile::passiveName),
					Codec.STRING.optionalFieldOf("passiveDesc", "").forGetter(AbilityProfile::passiveDesc),
					Codec.STRING.optionalFieldOf("anchorId", "").forGetter(AbilityProfile::anchorId),
					Codec.STRING.optionalFieldOf("secondId", "").forGetter(AbilityProfile::secondMechanicId),
					Codec.STRING.optionalFieldOf("secondName", "").forGetter(AbilityProfile::secondMechanicName),
					Codec.STRING.optionalFieldOf("secondDesc", "").forGetter(AbilityProfile::secondMechanicDesc),
					Codec.STRING.listOf().optionalFieldOf("components", List.of())
							.forGetter(AbilityProfile::components),
					Codec.FLOAT.optionalFieldOf("range", 12.0f).forGetter(AbilityProfile::range),
					Codec.INT.optionalFieldOf("cooldown", CD_MARK).forGetter(AbilityProfile::cooldownTicks),
					Codec.INT.optionalFieldOf("priority", 1).forGetter(AbilityProfile::targetPriority)
			).apply(i, AbilityProfile::new));

	/**
	 * Wire codec. {@link StreamCodec#composite} tops out at 12 tuples in
	 * 26.2 and this record has 13 fields, so {@code targetPriority} - which is
	 * a server-side AI tuning value the client never reads - is not
	 * transmitted. The client receives it as 0 and only uses it for display.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, AbilityProfile> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, AbilityProfile::activeId,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::activeName,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::passiveId,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::passiveName,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::passiveDesc,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::anchorId,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::secondMechanicId,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::secondMechanicName,
					ByteBufCodecs.STRING_UTF8, AbilityProfile::secondMechanicDesc,
					ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), AbilityProfile::components,
					ByteBufCodecs.FLOAT, AbilityProfile::range,
					ByteBufCodecs.VAR_INT, AbilityProfile::cooldownTicks,
					(activeId, activeName, passiveId, passiveName, passiveDesc,
							anchorId, secondId, secondName, secondDesc, components,
							range, cooldownTicks) -> new AbilityProfile(
									activeId, activeName, passiveId, passiveName,
									passiveDesc, anchorId, secondId, secondName,
									secondDesc, components, range, cooldownTicks, 0));

	public boolean hasComponent(String component) {
		return this.components.contains(component);
	}

	/** Spec 11.5: the second mechanic must answer "how does it change play?". */
	public Optional<String> secondMechanic() {
		return this.secondMechanicId == null || this.secondMechanicId.isBlank()
				? Optional.empty()
				: Optional.of(this.secondMechanicId);
	}
}
