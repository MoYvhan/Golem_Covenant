package com.example.golem_covenant.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;

import com.example.golem_covenant.data.CovenantItemData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.data.SoulProfile;
import com.example.golem_covenant.registry.ModComponents;

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
		// spec 11.13: an attuned stack names its form and lists the abilities
		// that form actually grants. Read from the data component (spec 3.2)
		// rather than an attachment, because the tooltip is built client-side
		// and the client has no access to the base mod's block entity.
		CovenantItemData attunement = ModComponents.get(stack);
		attunement.profile().ifPresent(profile ->
				appendFormTooltip(profile, adder, flag));
	}

	/**
	 * Render the spec 11.13 per-form lines.
	 *
	 * <p>Each line is emitted only when the form actually has that ability, so
	 * the tooltip stays honest: a form without a death will does not claim one.
	 *
	 * <p>Depth is gated on {@link TooltipFlag#isAdvanced()} (the F3+H detail
	 * toggle) so a full covenant does not swamp the tooltip of an item sitting
	 * in a hotbar.
	 *
	 * <p>Note for anyone porting older code: 26.2 removed the shift check from
	 * the tooltip path entirely. {@code TooltipFlag} now exposes only
	 * {@code isAdvanced()} / {@code isCreative()}, and {@code hasShiftDown()}
	 * is an instance method on {@code InputWithModifiers} (a key or click
	 * event) - it is not reachable from {@code appendHoverText}, which runs on
	 * both sides from a data-driven call site.
	 */
	private static void appendFormTooltip(SoulProfile profile,
			Consumer<Component> adder, TooltipFlag flag) {
		adder.accept(Component.translatable(profile.translationKey("name"))
				.withStyle(ChatFormatting.GOLD));
		adder.accept(Component.translatable("golem_covenant.tooltip.ritual",
				Component.translatable(profile.translationKey("ritual")))
				.withStyle(ChatFormatting.DARK_AQUA));
		if (!flag.isAdvanced()) {
			adder.accept(Component.translatable(
					"golem_covenant.tooltip.shift_for_details")
					.withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		ability(adder, profile, "b_active", ChatFormatting.GREEN);
		ability(adder, profile, "b_passive", ChatFormatting.BLUE);
		ability(adder, profile, "c_second", ChatFormatting.AQUA);
		ability(adder, profile, "death_will", ChatFormatting.LIGHT_PURPLE);
	}

	private static void ability(Consumer<Component> adder, SoulProfile profile,
			String suffix, ChatFormatting colour) {
		Component label = abilityLabel(suffix);
		adder.accept(Component.translatable("golem_covenant.tooltip.ability",
				label, Component.translatable(profile.translationKey(suffix)))
				.withStyle(colour));
	}

	private static Component abilityLabel(String suffix) {
		return Component.translatable("golem_covenant.tooltip.ability."
				+ suffix);
	}
}
