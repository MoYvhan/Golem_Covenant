package com.example.golem_covenant.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * Server -> client screen-shake trigger (spec 13.1).
 *
 * <p>Screen shake is a purely client-side cosmetic, so the server only sends
 * the two numbers that describe it: how hard and for how long. The client
 * multiplies those by its own {@code shakeLevel} preference, which means a
 * player who set shake to OFF never has their camera moved even though the
 * server still sends the beat - the preference is respected locally rather
 * than by suppressing the packet.
 *
 * @param intensity 0.0 to 1.0, relative strength of the beat
 * @param ticks     how long the beat lasts
 */
public record RitualShakePayload(float intensity, int ticks)
		implements CustomPacketPayload {

	public static final Type<RitualShakePayload> TYPE =
			new Type<>(GolemCovenantMod.id("ritual_shake"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RitualShakePayload>
			STREAM_CODEC = StreamCodec.composite(
					ByteBufCodecs.FLOAT, RitualShakePayload::intensity,
					ByteBufCodecs.VAR_INT, RitualShakePayload::ticks,
					RitualShakePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
