package com.example.golem_covenant.registry;

import java.util.Set;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.block.entity.SoulAltarBlockEntity;

/**
 * Block entity types (spec 4.3.2).
 *
 * <p>Split from {@code ModBlocks} because the two registries have a hard
 * ordering dependency: a {@link BlockEntityType} is constructed with the
 * {@link Block}s it is valid for, so the blocks must already be registered.
 * Keeping them in separate classes makes that order explicit in
 * {@code GolemCovenantMod.onInitialize}.
 */
public final class ModBlockEntities {

	private ModBlockEntities() {
	}

	/**
	 * The 灵魂圣坛's block entity.
	 *
	 * <p>Registered against the altar block alone - {@code BlockEntityType.isValid}
	 * consults this set, so listing a block that does not create this entity
	 * would leave a permanently empty block entity behind.
	 */
	public static final BlockEntityType<SoulAltarBlockEntity> SOUL_ALTAR =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
					GolemCovenantMod.id("soul_altar"),
					new BlockEntityType<>(
							SoulAltarBlockEntity::new,
							Set.of(ModBlocks.SOUL_ALTAR)));

	public static void register() {
		GolemCovenantMod.LOGGER.debug("block entity registered: soul_altar");
	}
}
