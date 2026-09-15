package com.example.golem_covenant;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.golem_covenant.anchor.AnchorRuntime;
import com.example.golem_covenant.anchor.Anchors;
import com.example.golem_covenant.bond.BondEngine;
import com.example.golem_covenant.command.GolemCommand;
import com.example.golem_covenant.compat.GolemizationCompat;
import com.example.golem_covenant.data.FormsRegistry;
import com.example.golem_covenant.death.DeathWillEngine;
import com.example.golem_covenant.item.ModItems;
import com.example.golem_covenant.network.ModNetworking;
import com.example.golem_covenant.network.RitualNetworking;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.registry.ModBlockEntities;
import com.example.golem_covenant.registry.ModBlocks;
import com.example.golem_covenant.registry.ModComponents;
import com.example.golem_covenant.registry.ModParticles;
import com.example.golem_covenant.registry.ModSounds;
import com.example.golem_covenant.ritual.RitualEngine;
import com.example.golem_covenant.summon.SummonManager;
import com.example.golem_covenant.team.ResonanceEngine;
import com.example.golem_covenant.trial.SecondSoulTrial;
import com.example.golem_covenant.trial.TaskObserver;

/**
 * 傀儡契约 / Golem Covenant - addon entrypoint.
 *
 * <p>This mod is an ADDON for the existing "傀儡化 / Golemization" mod. All
 * contact with the base mod goes through {@link GolemizationCompat}; nothing
 * else in this code base may reference base-mod classes.
 */
public class GolemCovenantMod implements ModInitializer {
	public static final String MOD_ID = "golem_covenant";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Base (前置) mod id - the "傀儡化" mod. */
	public static final String BASE_MOD_ID = "copper_enchant";

	private static boolean baseModPresent = false;

	@Override
	public void onInitialize() {
		baseModPresent = FabricLoader.getInstance().isModLoaded(BASE_MOD_ID);

		// 1. Load the authoritative 186-form registry (spec 11.2).
		FormsRegistry.load();
		// spec ch.6: index the anchor table that lives beside the forms.
		Anchors.load();

		// 2. Bind the registry against the base mod's real capabilities
		//    (spec 11.1.4 / 11.2.3). Missing base mod => soft-disable.
		GolemizationCompat.init(baseModPresent);
		FormsRegistry.bindAgainstCompat();

		// 3. Registries.
		// spec 3.2: data components come first - an item's Properties may
		// reference one from its own static initialiser, so the component has
		// to exist before ModItems' statics run.
		ModComponents.register();
		ModParticles.register();
		ModSounds.register();
		ModAttachments.register();
		ModItems.register();
		// spec 4.3.1 / 4.3.2: blocks, then their block entities. The order is
		// load-bearing - a BlockEntityType is built with the Blocks it is valid
		// for, so the blocks must already be in the registry.
		ModBlocks.register();
		ModBlockEntities.register();
		ModNetworking.register();
		// spec 13.1: the client's quality / ritual-toggle wishes are received
		// here, since the server is what actually emits the particles.
		RitualNetworking.register();

		// 4. Engines.
		SummonManager.register();
		RitualEngine.register();
		DeathWillEngine.register();
		BondEngine.register();
		ResonanceEngine.register();
		// spec ch.6: the anchor runtime drives every companion's behaviour.
		AnchorRuntime.register();
		// spec 4.3.2: the five partner tasks have no observer without this, and
		// spec 11.8 clause 4/5 need their own event hooks.
		TaskObserver.register();
		SecondSoulTrial.register();

		// 5. Commands (spec 11.9.3 / 11.10.4 / 13.1).
		GolemCommand.register();

		// 6. Session state must not leak across worlds (spec 11.14).
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
				.SERVER_STOPPED.register(server -> {
					DeathWillEngine.onServerStopped();
					RitualEngine.onServerStopped();
					SummonManager.onServerStopped();
					BondEngine.onServerStopped();
					ResonanceEngine.onServerStopped();
					AnchorRuntime.onServerStopped();
					TaskObserver.onServerStopped();
					SecondSoulTrial.onServerStopped();
				});

		// spec 13.1: push the resolved settings on join, so the HUD and the
		// settings screen never open on a stale value.
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN
				.register((handler, sender, server) -> RitualNetworking
						.syncTo(handler.getPlayer()));

		if (baseModPresent) {
			LOGGER.info("傀儡契约 loaded - base mod '{}' detected, {} forms "
					+ "across {} anchors active.",
					BASE_MOD_ID, FormsRegistry.activeCount(), Anchors.count());
		} else {
			LOGGER.warn("傀儡契约 loaded WITHOUT base mod '{}'. "
					+ "Covenant items are inert; no crash (spec 11.1.3).",
					BASE_MOD_ID);
		}
	}

	public static boolean isBaseModPresent() {
		return baseModPresent;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
