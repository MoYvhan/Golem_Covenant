package com.example.golem_covenant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.ritual.RitualEngine;

/**
 * Client entrypoint (spec 12.4: the client only renders the ritual layer).
 */
public class GolemCovenantClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Spec 13.2.2: thin out particles when many companions are visible.
			if (client.level == null || client.player == null) {
				return;
			}
			int nearby = (int) client.level.getEntities(client.player,
							client.player.getBoundingBox().inflate(32.0))
					.stream()
					.filter(e -> e.getAttachedOrElse(
							com.example.golem_covenant.registry.ModAttachments.COVENANT,
							com.example.golem_covenant.data.CovenantData.empty())
							.active())
					.count();
			RitualEngine.particleQuality = RitualEngine.effectiveQuality(nearby);
		});
		GolemCovenantMod.LOGGER.debug("client initialized");
	}
}
