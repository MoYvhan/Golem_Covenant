package com.example.golem_covenant.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;

import com.example.golem_covenant.data.CovenantTier;

import org.jetbrains.annotations.Nullable;

/**
 * Base class for the three covenant items (spec ch.3).
 *
 * <p>All covenant items are right-click-on-entity tools. The actual
 * contract logic lives in {@code SummonManager} (spec 3.3: "召唤管理器负责校验"),
 * so the items stay thin and only carry their tier + structured data.
 *
 * <p>Spec 11.9.3 requires the tooltip to show the soul load this form would
 * consume and the player's remaining capacity.
 */
public abstract class CovenantItem extends Item {

	private final CovenantTier tier;

	protected CovenantItem(CovenantTier tier, Properties properties) {
		super(properties);
		this.tier = tier;
	}

	public CovenantTier tier() {
		return this.tier;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
			TooltipDisplay display, Consumer<Component> adder, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, adder, flag);
		adder.accept(Component.translatable(
				"golem_covenant.tier." + this.tier.id())
				.withStyle(ChatFormatting.GOLD));
		adder.accept(Component.translatable(
				"golem_covenant.tooltip." + this.tier.id())
				.withStyle(ChatFormatting.GRAY));
		adder.accept(Component.translatable("golem_covenant.tooltip.soul_load",
				this.tier.soulLoad()).withStyle(ChatFormatting.DARK_AQUA));
		adder.accept(Component.translatable("golem_covenant.tooltip.slots",
				this.tier.defaultSlots(), this.tier.maxSlots())
				.withStyle(ChatFormatting.DARK_AQUA));
		if (this.tier == CovenantTier.C) {
			adder.accept(Component.translatable("golem_covenant.tooltip.has_will")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}
}
