package com.example.golem_covenant.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.ritual.RitualEngine;

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
		// Spec 13.1: the two ritual cosmetics the server has to drive.
		PayloadTypeRegistry.clientboundPlay().register(
				RitualShakePayload.TYPE, RitualShakePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				RitualSettingsPayload.TYPE, RitualSettingsPayload.STREAM_CODEC);
		GolemCovenantMod.LOGGER.debug("network payloads registered");
	}

	/** Sends a screen-shake beat to one player (spec 13.1). */
	public static void sendShake(ServerPlayer player, float intensity, int ticks) {
		if (player != null && intensity > 0.0f && ticks > 0) {
			ServerPlayNetworking.send(player,
					new RitualShakePayload(intensity, ticks));
		}
	}

	/** Echoes the resolved settings back to one player (spec 13.1). */
	public static void sendSettings(ServerPlayer player) {
		if (player == null) {
			return;
		}
		RitualEngine.Settings s = RitualEngine.settings();
		ServerPlayNetworking.send(player, new RitualSettingsPayload(
				s.particleQuality(), s.ritualsEnabled(),
				s.shakeLevel().ordinal()));
	}
}
