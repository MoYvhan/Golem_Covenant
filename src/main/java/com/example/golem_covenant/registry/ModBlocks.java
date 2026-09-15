package com.example.golem_covenant.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.block.CovenantSteleBlock;
import com.example.golem_covenant.block.SoulAltarBlock;

/**
 * The addon's blocks (spec 4.3.1 / 4.3.2).
 *
 * <ul>
 *   <li>契约石碑 Covenant Stele - A-tier capacity, +1 slot per completed rite</li>
 *   <li>灵魂圣坛 Soul Altar - B-tier seats, one per completed partner task</li>
 * </ul>
 *
 * <p>Note on the 26.2 API: {@code BlockBehaviour.Properties} now has to be
 * given a registry id via {@code setId(ResourceKey<Block>)}. Without it the
 * block registers but its properties cannot be serialised, so this is not
 * optional boilerplate - it is why {@link #props} takes the key.
 */
public final class ModBlocks {

	private ModBlocks() {
	}

	public static final CovenantSteleBlock COVENANT_STELE = register("covenant_stele",
			new CovenantSteleBlock(props("covenant_stele")
					.mapColor(MapColor.COLOR_BLACK)
					.strength(3.5f, 6.0f)
					.sound(SoundType.STONE)
					// spec 4.3.1: a ritual anchor, not a piston-movable prop.
					.pushReaction(PushReaction.BLOCK)));

	public static final SoulAltarBlock SOUL_ALTAR = register("soul_altar",
			new SoulAltarBlock(props("soul_altar")
					.mapColor(MapColor.DEEPSLATE)
					.strength(4.0f, 6.0f)
					.sound(SoundType.DEEPSLATE)
					// spec 4.3.2: the altar holds trial progress, so it must not
					// be shuttled around by pistons mid-trial.
					.pushReaction(PushReaction.BLOCK)
					.lightLevel(state -> 7)));

	private static BlockBehaviour.Properties props(String path) {
		return BlockBehaviour.Properties.of()
				.setId(ResourceKey.create(Registries.BLOCK,
						GolemCovenantMod.id(path)));
	}

	private static <T extends Block> T register(String path, T block) {
		Registry.register(BuiltInRegistries.BLOCK, GolemCovenantMod.id(path), block);
		// Every block needs an item form or it is unobtainable, so pairing them
		// here means a new block cannot ship without one.
		Registry.register(BuiltInRegistries.ITEM, GolemCovenantMod.id(path),
				new BlockItem(block, new Item.Properties()
						.setId(ResourceKey.create(Registries.ITEM,
								GolemCovenantMod.id(path)))
						.rarity(Rarity.RARE)
						.stacksTo(16)));
		return block;
	}

	public static void register() {
		GolemCovenantMod.LOGGER.debug("covenant blocks registered");
	}
}
