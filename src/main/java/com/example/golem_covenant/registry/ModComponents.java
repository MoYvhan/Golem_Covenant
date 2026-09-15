package com.example.golem_covenant.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.data.CovenantItemData;

/**
 * Custom Data Components for covenant item stacks (spec 3.2).
 *
 * <p>Spec 3.2 requires B / C items to carry their structured data in custom
 * data components rather than in NBT. A component gives us three things NBT
 * did not:
 *
 * <ul>
 *   <li><b>Typed access.</b> {@code stack.get(COMPONENT)} returns a
 *       {@link CovenantItemData} directly - no tag-string lookups.</li>
 *   <li><b>Stack semantics for free.</b> Minecraft compares the component map
 *       when merging stacks, so an item attuned to one form will not merge with
 *       one attuned to another. With NBT this had to be enforced by hand.</li>
 *   <li><b>Network sync.</b> {@code networkSynchronized} makes the value
 *       survive the trip to the client, which the tooltip in
 *       {@code CovenantItem.appendHoverText} needs.</li>
 * </ul>
 *
 * <p>Registration runs before item registration (see
 * {@code GolemCovenantMod.onInitialize}) so an item can reference the component
 * from its own static initialiser.
 */
public final class ModComponents {

	private ModComponents() {
	}

	/** The attuned-form payload for B / C covenant items (spec 3.2). */
	public static final DataComponentType<CovenantItemData> COVENANT_ITEM =
			Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
					GolemCovenantMod.id("covenant_item"),
					DataComponentType.<CovenantItemData>builder()
							.persistent(CovenantItemData.CODEC)
							.networkSynchronized(CovenantItemData.STREAM_CODEC)
							.build());

	/**
	 * Read the component, falling back to the empty payload.
	 *
	 * <p>An item that was never attuned simply has no component, so callers
	 * should not have to null-check at every use site.
	 */
	public static CovenantItemData get(ItemStack stack) {
		CovenantItemData data = stack.get(COVENANT_ITEM);
		return data == null ? CovenantItemData.EMPTY : data.migrated();
	}

	public static void register() {
		GolemCovenantMod.LOGGER.debug("data component registered: {}",
				Identifier.fromNamespaceAndPath(GolemCovenantMod.MOD_ID,
						"covenant_item"));
	}
}
