package com.example.golem_covenant.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * Client -> server particle-quality preference (spec 13.1).
 *
 * <p>Particle quality is a client display setting; the client tells the server
 * so the server can thin out the particles it emits for that player's view.
 */
public record ParticleQualityPayload(int quality, boolean ritualsEnabled)
		implements CustomPacketPayload {

	public static final Type<ParticleQualityPayload> TYPE =
			new Type<>(GolemCovenantMod.id("particle_quality"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ParticleQualityPayload>
			STREAM_CODEC = StreamCodec.composite(
					ByteBufCodecs.VAR_INT, ParticleQualityPayload::quality,
					ByteBufCodecs.BOOL, ParticleQualityPayload::ritualsEnabled,
					ParticleQualityPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
