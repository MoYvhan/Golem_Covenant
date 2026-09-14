package com.example.golem_covenant.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.summon.SummonManager;

/**
 * A 级 借魂金苹果 / 黄金契约 (spec 2.2).
 *
 * <p>Temporary 10-minute contract. Grants no permanent growth and no death
 * will; the companion simply follows, protects and fights nearby.
 */
public class GoldenCovenantItem extends CovenantItem {

	public GoldenCovenantItem(Properties properties) {
		super(CovenantTier.A, properties);
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player,
			LivingEntity target, InteractionHand hand) {
		return SummonManager.attemptContract(stack, player, target, CovenantTier.A);
	}
}
