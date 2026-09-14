package com.example.golem_covenant.data;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import com.example.golem_covenant.death.DeathWillEngine;

/**
 * 死亡遗志 profile (spec 8.5 + the quantified table in 11.4).
 *
 * <p>The source documents only described wills in prose and reused 24 strings
 * across 138 of 186 rows. Spec 11.4 requires real numbers plus an explicit
 * stacking policy, which is what this record carries.
 */
public record DeathWillProfile(
		String effectId,
		int amplifier,
		int durationTicks,
		int cooldownTicks,
		boolean once,
		boolean refreshOnly,
		boolean ownerOnly,
		String triggerCondition) {

	public static final Codec<DeathWillProfile> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					Codec.STRING.fieldOf("effect").forGetter(DeathWillProfile::effectId),
					Codec.INT.optionalFieldOf("amplifier", 0).forGetter(DeathWillProfile::amplifier),
					Codec.INT.optionalFieldOf("duration_ticks", 200).forGetter(DeathWillProfile::durationTicks),
					Codec.INT.optionalFieldOf("cooldown_ticks", 6000).forGetter(DeathWillProfile::cooldownTicks),
					Codec.BOOL.optionalFieldOf("once", true).forGetter(DeathWillProfile::once),
					Codec.BOOL.optionalFieldOf("refresh_only", true).forGetter(DeathWillProfile::refreshOnly),
					Codec.BOOL.optionalFieldOf("owner_only", true).forGetter(DeathWillProfile::ownerOnly),
					Codec.STRING.optionalFieldOf("trigger", "").forGetter(DeathWillProfile::triggerCondition)
			).apply(i, DeathWillProfile::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, DeathWillProfile> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, DeathWillProfile::effectId,
					ByteBufCodecs.VAR_INT, DeathWillProfile::amplifier,
					ByteBufCodecs.VAR_INT, DeathWillProfile::durationTicks,
					ByteBufCodecs.VAR_INT, DeathWillProfile::cooldownTicks,
					ByteBufCodecs.BOOL, DeathWillProfile::once,
					ByteBufCodecs.BOOL, DeathWillProfile::refreshOnly,
					ByteBufCodecs.BOOL, DeathWillProfile::ownerOnly,
					ByteBufCodecs.STRING_UTF8, DeathWillProfile::triggerCondition,
					DeathWillProfile::new);

	/** Resolves the stat effect this will applies (spec 8.3 theme table). */
	public Optional<DeathWillEngine.WillEffect> effect() {
		return DeathWillEngine.resolveEffect(this.effectId);
	}

	/** Effective duration clamped to the spec's 120..600 tick window. */
	public int clampedDuration() {
		if (instant()) {
			return 1;
		}
		return Math.clamp(this.durationTicks, 120, 600);
	}

	/**
	 * True for wills that arm a one-shot reaction rather than applying a timed
	 * buff (spec 8.3 "控制型" / the named one-shot wills in ch.6).
	 */
	public boolean instant() {
		return this.durationTicks <= 1;
	}
}
