package com.example.golem_covenant.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.example.golem_covenant.network.ParticleQualityPayload;
import com.example.golem_covenant.network.RitualSettingsPayload;

/**
 * The client's optimistic mirror of the ritual settings (spec 13.1 / 12.4).
 *
 * <p>Why a mirror at all? The server is authoritative (spec 12.4), so the
 * truth arrives in a {@link RitualSettingsPayload} echo one round trip later.
 * A settings screen that only re-renders on that echo feels broken - click a
 * button and nothing happens for a moment. So the client keeps its own
 * requested value, paints from it immediately, and lets the server's echo
 * overwrite it. If the server ever disagrees, the echo wins, which is the
 * correct direction for authority to flow.
 */
public final class RitualClientSettings {

	private RitualClientSettings() {
	}

	private static volatile boolean enabled = true;
	private static volatile int quality = 2;
	private static volatile int shakeLevel = 2;

	/** Applies a server-confirmed value (authoritative). */
	public static void apply(RitualSettingsPayload payload) {
		enabled = payload.enabled();
		quality = payload.quality();
		shakeLevel = payload.shakeLevel();
		CameraShake.setPreference(shakeLevel);
	}

	/**
	 * Records the player's wish and sends it to the server.
	 *
	 * <p>The local value is updated first on purpose: see the class doc.
	 */
	public static void request(boolean wantEnabled, int wantQuality,
			int wantShake) {
		enabled = wantEnabled;
		quality = Math.clamp(wantQuality, 0, 2);
		shakeLevel = Math.clamp(wantShake, 0, 3);
		CameraShake.setPreference(shakeLevel);
		if (ClientPlayNetworking.canSend(ParticleQualityPayload.TYPE)) {
			ClientPlayNetworking.send(
					new ParticleQualityPayload(quality, enabled));
		}
	}

	/** The current view of the settings, for the screen and the HUD. */
	public static RitualSettingsPayload current() {
		return new RitualSettingsPayload(quality, enabled, shakeLevel);
	}

	public static void reset() {
		enabled = true;
		quality = 2;
		shakeLevel = 2;
	}
}
