package com.example.golem_covenant.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * Network payload registration (spec 11.10.1).
 *
 * <p>Only the client-facing bits are networked: ritual playback state and
 * particle-quality preferences. All authority (contract, targeting, death
 * wills) stays on the server (spec 11.10.2 / 12.4).
 */
public final class ModNetworking {

	private ModNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(
				ParticleQualityPayload.TYPE, ParticleQualityPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				CovenantSyncPayload.TYPE, CovenantSyncPayload.STREAM_CODEC);
		GolemCovenantMod.LOGGER.debug("network payloads registered");
	}
}
