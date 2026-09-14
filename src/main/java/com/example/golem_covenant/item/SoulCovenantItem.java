package com.example.golem_covenant.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.summon.SummonManager;

/**
 * C 级 灵魂圣契 (spec 2.4).
 *
 * <p>Complete revival: B's core mechanic upgraded, a second independent
 * mechanic, the ultimate anchor, a large ritual and a one-shot death will.
 */
public class SoulCovenantItem extends CovenantItem {

	public SoulCovenantItem(Properties properties) {
		super(CovenantTier.C, properties);
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player,
			LivingEntity target, InteractionHand hand) {
		return SummonManager.attemptContract(stack, player, target, CovenantTier.C);
	}
}
