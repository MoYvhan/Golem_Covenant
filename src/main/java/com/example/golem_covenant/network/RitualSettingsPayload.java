package com.example.golem_covenant.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * Server -> client echo of the player's resolved ritual settings (spec 13.1).
 *
 * <p>The client needs these to render the HUD and the settings screen without
 * guessing, and sending the server's resolved value back is what makes the
 * command and the screen agree. The client sends its wish via
 * {@link ParticleQualityPayload}; this is the authoritative reply.
 *
 * @param quality    0 low, 1 medium, 2 high
 * @param enabled    whether rituals are on at all
 * @param shakeLevel 0 off, 1 weak, 2 normal, 3 strong
 */
public record RitualSettingsPayload(int quality, boolean enabled, int shakeLevel)
		implements CustomPacketPayload {

	public static final Type<RitualSettingsPayload> TYPE =
			new Type<>(GolemCovenantMod.id("ritual_settings"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RitualSettingsPayload>
			STREAM_CODEC = StreamCodec.composite(
					ByteBufCodecs.VAR_INT, RitualSettingsPayload::quality,
					ByteBufCodecs.BOOL, RitualSettingsPayload::enabled,
					ByteBufCodecs.VAR_INT, RitualSettingsPayload::shakeLevel,
					RitualSettingsPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
