package com.example.golem_covenant.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.ritual.RitualEngine;

/**
 * Server-side receivers for the client's ritual preferences (spec 13.1).
 *
 * <p>Spec 13.1 makes particle quality and the ritual toggle client-facing
 * settings, but the server is what actually emits the particles, so the
 * client has to tell it. This is the one place a client value is allowed to
 * drive server behaviour, and it is safe because it only narrows a cosmetic:
 * the worst a malicious client can do is ask for fewer particles.
 */
public final class RitualNetworking {

	private RitualNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ParticleQualityPayload.TYPE,
				(payload, context) -> {
					context.server().execute(() -> {
						RitualEngine.setParticleQuality(payload.quality());
						RitualEngine.setRitualsEnabled(payload.ritualsEnabled());
						// Echo the resolved value back so the client's HUD and
						// settings screen show exactly what the server will do
						// rather than the client's optimistic guess.
						ModNetworking.sendSettings(context.player());
					});
				});
		GolemCovenantMod.LOGGER.debug("ritual settings receiver registered");
	}

	/** Pushes the current settings to a player, e.g. on join. */
	public static void syncTo(net.minecraft.server.level.ServerPlayer player) {
		ModNetworking.sendSettings(player);
	}
}
