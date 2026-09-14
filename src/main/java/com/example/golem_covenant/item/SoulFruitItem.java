package com.example.golem_covenant.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.summon.SummonManager;

/**
 * B 级 傀儡灵魂果 / 灵魂觉醒 (spec 2.3).
 *
 * <p>Permanent binding. Grants an independent soul anchor, one active and one
 * passive ability, a mid-size ritual and a companion record.
 */
public class SoulFruitItem extends CovenantItem {

	public SoulFruitItem(Properties properties) {
		super(CovenantTier.B, properties);
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player,
			LivingEntity target, InteractionHand hand) {
		return SummonManager.attemptContract(stack, player, target, CovenantTier.B);
	}
}
