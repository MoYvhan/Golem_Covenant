package com.example.golem_covenant.item;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.data.CovenantTier;

/**
 * The three covenant items (spec 3.1).
 *
 * <ul>
 *   <li>A 借魂金苹果 / 黄金契约 - 1 golden apple</li>
 *   <li>B 傀儡灵魂果 - 4 golden apples + 4 golden carrots</li>
 *   <li>C 灵魂圣契 - 4 enchanted golden apples + 2 golden apples + 2 golden carrots</li>
 * </ul>
 */
public final class ModItems {

	private ModItems() {
	}

	public static final CovenantItem GOLDEN_COVENANT = register("golden_covenant",
			new GoldenCovenantItem(new Item.Properties()
					.setId(key("golden_covenant"))
					.rarity(Rarity.UNCOMMON)
					.stacksTo(16)));

	public static final CovenantItem SOUL_FRUIT = register("soul_fruit",
			new SoulFruitItem(new Item.Properties()
					.setId(key("soul_fruit"))
					.rarity(Rarity.RARE)
					.stacksTo(16)));

	public static final CovenantItem SOUL_COVENANT = register("soul_covenant",
			new SoulCovenantItem(new Item.Properties()
					.setId(key("soul_covenant"))
					.rarity(Rarity.EPIC)
					.stacksTo(16)));

	/** 契约容量碎片 - A/B slot expansion resource (spec 4.3.1 / 4.3.2). */
	public static final Item CAPACITY_SHARD = registerItem("capacity_shard",
			new Item(new Item.Properties()
					.setId(key("capacity_shard"))
					.rarity(Rarity.RARE)
					.stacksTo(64)));

	/** 灵魂圣坛核心 - used to build the B-tier expansion altar (spec 4.3.2). */
	public static final Item ALTAR_CORE = registerItem("altar_core",
			new Item(new Item.Properties()
					.setId(key("altar_core"))
					.rarity(Rarity.EPIC)
					.stacksTo(16)));

	private static ResourceKey<Item> key(String path) {
		return ResourceKey.create(Registries.ITEM, GolemCovenantMod.id(path));
	}

	private static <T extends Item> T register(String path, T item) {
		Registry.register(BuiltInRegistries.ITEM, GolemCovenantMod.id(path), item);
		return item;
	}

	private static Item registerItem(String path, Item item) {
		return register(path, item);
	}

	public static CovenantItem forTier(CovenantTier tier) {
		return switch (tier) {
			case A -> GOLDEN_COVENANT;
			case B -> SOUL_FRUIT;
			case C -> SOUL_COVENANT;
		};
	}

	public static void register() {
		GolemCovenantMod.LOGGER.debug("covenant items registered");
	}
}
