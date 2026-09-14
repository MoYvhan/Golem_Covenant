package com.example.golem_covenant;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.golem_covenant.compat.GolemizationCompat;
import com.example.golem_covenant.data.FormsRegistry;
import com.example.golem_covenant.death.DeathWillEngine;
import com.example.golem_covenant.item.ModItems;
import com.example.golem_covenant.network.ModNetworking;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.registry.ModParticles;
import com.example.golem_covenant.registry.ModSounds;
import com.example.golem_covenant.ritual.RitualEngine;
import com.example.golem_covenant.summon.SummonManager;

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

		// 2. Bind the registry against the base mod's real capabilities
		//    (spec 11.1.4 / 11.2.3). Missing base mod => soft-disable.
		GolemizationCompat.init(baseModPresent);
		FormsRegistry.bindAgainstCompat();

		// 3. Registries.
		ModParticles.register();
		ModSounds.register();
		ModAttachments.register();
		ModItems.register();
		ModNetworking.register();

		// 4. Engines.
		SummonManager.register();
		RitualEngine.register();
		DeathWillEngine.register();

		if (baseModPresent) {
			LOGGER.info("傀儡契约 loaded - base mod '{}' detected, {} forms active.",
					BASE_MOD_ID, FormsRegistry.activeCount());
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
