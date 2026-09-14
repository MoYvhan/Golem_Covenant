package com.example.golem_covenant.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * Server -> client covenant status sync (spec 11.10.1 / 11.9.3).
 *
 * <p>Pushes the player's soul-capacity usage so the HUD can stay correct
 * without polling. Sent on change only, per spec 11.10.1.
 */
public record CovenantSyncPayload(int usedCapacity, int maxCapacity,
		int activeA, int limitA, int activeB, int limitB, int activeC,
		int limitC) implements CustomPacketPayload {

	public static final Type<CovenantSyncPayload> TYPE =
			new Type<>(GolemCovenantMod.id("covenant_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CovenantSyncPayload>
			STREAM_CODEC = StreamCodec.composite(
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::usedCapacity,
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::maxCapacity,
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::activeA,
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::limitA,
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::activeB,
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::limitB,
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::activeC,
					ByteBufCodecs.VAR_INT, CovenantSyncPayload::limitC,
					CovenantSyncPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
