package com.example.golem_covenant.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.client.gui.RitualSettingsScreen;
import com.example.golem_covenant.network.RitualSettingsPayload;
import com.example.golem_covenant.network.RitualShakePayload;

/**
 * Client entrypoint (spec 12.4: the client only renders the ritual layer).
 *
 * <p>The client owns exactly four things: the particle providers, the camera
 * shake, the settings screen and its keybind, and the mirror of the player's
 * own settings. It never decides anything about a ceremony (spec 12.4 /
 * 11.10.2).
 */
public class GolemCovenantClient implements ClientModInitializer {

	/** Spec 13.1: opens the ritual settings screen. Unbound by default. */
	private static KeyMapping settingsKey;

	@Override
	public void onInitializeClient() {
		// Spec 5.7 / 12.4: bind a ParticleProvider to every registered type.
		// Without this the ritual particles fall back to the vanilla renderer
		// and the whole magic-circle layer looks like generic smoke.
		RitualParticles.register();

		// Spec 13.1: the settings screen needs a discoverable entry point.
		// The key is deliberately unbound (GLFW_KEY_UNKNOWN) so it cannot
		// collide with a player's existing bindings - the screen is also
		// reachable from the mod list's config button.
		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.golem_covenant.ritual_settings",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				KeyMapping.Category.MISC));

		// Spec 13.1: the two cosmetics the server drives for us.
		ClientPlayNetworking.registerGlobalReceiver(RitualShakePayload.TYPE,
				(payload, context) -> CameraShake.trigger(payload.intensity(),
						payload.ticks()));
		ClientPlayNetworking.registerGlobalReceiver(RitualSettingsPayload.TYPE,
				(payload, context) -> RitualClientSettings.apply(payload));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Spec 13.1: decay the shake every tick, so a beat that is over
			// stops affecting the camera even if no further packet arrives.
			CameraShake.tick();

			if (settingsKey != null && settingsKey.consumeClick()
					&& client.canInterruptScreen()) {
				client.setScreenAndShow(new RitualSettingsScreen(null));
			}
		});

		// Leaving a world must clear the transient state, or the camera would
		// keep jittering in the main menu (spec 11.14).
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			CameraShake.reset();
			RitualClientSettings.reset();
		});

		GolemCovenantMod.LOGGER.debug("client initialized ({} particle styles)",
				RitualParticles.styleCount());
	}

	/** The settings the client last resolved, for the screen and the HUD. */
	public static RitualSettingsPayload settings() {
		return RitualClientSettings.current();
	}
}
