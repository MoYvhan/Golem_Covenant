package com.example.golem_covenant.block.entity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.example.golem_covenant.registry.ModBlockEntities;
import com.example.golem_covenant.trial.SecondSoulTrial;

/**
 * The 灵魂圣坛's persistent ledger (spec 4.3.2) and its offering bowl
 * (spec 11.8 clause 3).
 *
 * <p>Two responsibilities, both about <em>remembering</em>:
 *
 * <ol>
 *   <li><b>Task ledger (spec 4.3.2).</b> Five partner tasks each raise the
 *       B-tier seat count: protect villagers, finish a dangerous delve, defeat
 *       a specific enemy type, accumulate assisted kills, and clear a
 *       soul-anchor trial. The altar has to remember which are already done,
 *       otherwise a repeated event would mint seats indefinitely - the exact
 *       "简单堆材料无限增加" outcome the spec forbids.</li>
 *   <li><b>Shard offering (spec 11.8 clause 3).</b> The 第二灵魂 trial requires
 *       collecting 32 契约容量碎片, and they must be <em>deposited here</em>
 *       rather than counted from the player's pockets. That distinction is
 *       what stops the second C seat from being a shopping list; see
 *       {@link SecondSoulTrial} for the reasoning.</li>
 * </ol>
 *
 * <p>Stored as a set of task keys rather than a counter so a task can only ever
 * be credited once, regardless of how many times the world fires the event.
 *
 * <h2>The container</h2>
 *
 * <p>This is a {@link WorldlyContainer} with a single input slot. Being a real
 * container rather than a count means hoppers can feed it, dispensers can
 * target it, and the standard block-entity save path persists it - none of
 * which would be true of a bare integer. {@code canPlaceItem} restricts the
 * slot to capacity shards so the altar cannot be used as free storage, and
 * {@code canTakeItemThroughFace} denies extraction so the offering, once made,
 * cannot be pulled back out.
 */
public class SoulAltarBlockEntity extends BlockEntity implements WorldlyContainer {

	/** The five spec 4.3.2 task kinds, in the order the spec lists them. */
	public static final List<String> TASKS = List.of(
			"protect_villagers",
			"dangerous_delve",
			"defeat_enemy_type",
			"assisted_kills",
			"soul_anchor_trial");

	public static final int TASK_COUNT = TASKS.size();

	/** The altar has exactly one slot: the shard offering. */
	public static final int SLOT_SHARDS = 0;

	/** spec 11.8 clause 3: 32 shards, matching {@link SecondSoulTrial}. */
	public static final int SHARDS_FOR_TRIAL = SecondSoulTrial.REQUIRED_SHARDS;

	/** Completed task keys. A set, so completing one twice is a no-op. */
	private final Set<String> completed = new LinkedHashSet<>();

	/** The offering slot contents. */
	private final NonNullList<ItemStack> items =
			NonNullList.withSize(1, ItemStack.EMPTY);

	/**
	 * Edge-triggered activation latch.
	 *
	 * <p>Spec 11.8 clause 2 asks the player to "建造并以特定仪式激活" the altar, so
	 * a bare placed block must not satisfy the trial. The altar arms itself the
	 * first time something is deposited - the offering <em>is</em> the ritual.
	 */
	private boolean activated = false;

	public SoulAltarBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.SOUL_ALTAR, pos, state);
	}

	// ------------------------------------------------------------------
	// task ledger (spec 4.3.2)
	// ------------------------------------------------------------------

	/**
	 * Credit one task.
	 *
	 * @return true when this is the first time the task was recorded, false when
	 *         it was already done (the caller must not award a seat)
	 */
	public boolean recordTask(String task) {
		if (!TASKS.contains(task)) {
			return false;
		}
		boolean added = this.completed.add(task);
		if (added) {
			this.setChanged();
		}
		return added;
	}

	public int completedCount() {
		return this.completed.size();
	}

	public boolean isComplete(String task) {
		return this.completed.contains(task);
	}

	/** The first spec-listed task not yet done, for the progress readout. */
	public String nextOutstandingTask() {
		for (String task : TASKS) {
			if (!this.completed.contains(task)) {
				return task;
			}
		}
		return TASKS.get(TASKS.size() - 1);
	}

	// ------------------------------------------------------------------
	// shard offering (spec 11.8 clause 3 / clause 2 activation)
	// ------------------------------------------------------------------

	/**
	 * How many shards have actually been offered.
	 *
	 * <p>Counted from the slot, not accumulated into a separate field: one
	 * source of truth means a shard cannot be both counted and handed back.
	 */
	public int depositedShards() {
		ItemStack shards = this.items.get(SLOT_SHARDS);
		return shards.isEmpty() ? 0 : shards.getCount();
	}

	/** spec 11.8 clause 2: has the altar been activated by an offering? */
	public boolean isActivated() {
		return this.activated;
	}

	/**
	 * Consume the offering and replace it with a single 灵魂圣坛核心-styled
	 * receipt token of shards.
	 *
	 * <p>Called when the offering completes. The shards are actually removed
	 * rather than left in the slot, otherwise the same 32 shards could be
	 * counted for a second player who walks up to the same altar.
	 *
	 * @return true when the offering was complete and has been consumed
	 */
	public boolean consumeOffering() {
		if (depositedShards() < SHARDS_FOR_TRIAL) {
			return false;
		}
		this.items.set(SLOT_SHARDS, ItemStack.EMPTY);
		this.activated = false;
		this.setChanged();
		return true;
	}

	/** True when the slot holds the full trial offering. */
	public boolean offeringComplete() {
		return depositedShards() >= SHARDS_FOR_TRIAL;
	}

	// ------------------------------------------------------------------
	// Container
	// ------------------------------------------------------------------

	@Override
	public int getContainerSize() {
		return this.items.size();
	}

	@Override
	public boolean isEmpty() {
		return this.items.get(SLOT_SHARDS).isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return this.items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack result = ContainerHelper.removeItem(this.items, slot, amount);
		if (!result.isEmpty()) {
			this.setChanged();
		}
		return result;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ContainerHelper.takeItem(this.items, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		this.items.set(slot, stack);
		stack.limitSize(this.getMaxStackSize(stack));
		// Spec 11.8 clause 2: an offering is what activates the altar, so the
		// latch is set here rather than from the block's interaction handler.
		// Setting it in setItem means every path that fills the slot counts -
		// a player right-clicking, a hopper feeding it, a dispenser firing -
		// instead of only the one path someone remembered to wire up.
		if (!stack.isEmpty() && SecondSoulTrial.isCapacityShard(stack)) {
			this.activated = true;
		}
		this.setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	/**
	 * Required by {@link WorldlyContainer}'s {@code Clearable} supertype.
	 *
	 * <p>26.2 added this to the container contract. For a single-slot vessel
	 * "clear" has an obvious meaning - drop the offering - so this empties the
	 * slot rather than throwing {@code UnsupportedOperationException}, which
	 * would otherwise break any code that clears containers generically (a
	 * structure block, a {@code /data} operation, a debug tool).
	 */
	@Override
	public void clearContent() {
		this.items.set(SLOT_SHARDS, ItemStack.EMPTY);
		this.activated = false;
		this.setChanged();
	}

	/** spec 11.8 clause 3: only capacity shards are ever accepted. */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == SLOT_SHARDS && SecondSoulTrial.isCapacityShard(stack);
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		return new int[] { SLOT_SHARDS };
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack,
			Direction side) {
		return this.canPlaceItem(slot, stack);
	}

	/**
	 * Nothing comes back out.
	 *
	 * <p>A deposited offering is a commitment: if a hopper could pull the
	 * shards out again, a player could satisfy clause 3 with 32 shards on loan
	 * from a chest.
	 */
	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack,
			Direction side) {
		return false;
	}

	// ------------------------------------------------------------------
	// persistence
	// ------------------------------------------------------------------

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		// spec 11.10.3: progress must survive a server restart, or a player
		// loses seats they already earned.
		output.store("completed", com.mojang.serialization.Codec.STRING.listOf(),
				List.copyOf(this.completed));
		output.store("activated", com.mojang.serialization.Codec.BOOL,
				this.activated);
		output.store("shards", ItemStack.CODEC, this.items.get(SLOT_SHARDS));
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.completed.clear();
		input.read("completed", com.mojang.serialization.Codec.STRING.listOf())
				.ifPresent(list -> {
					for (String task : list) {
						if (TASKS.contains(task)) {
							this.completed.add(task);
						}
					}
				});
		this.activated = input.read("activated",
				com.mojang.serialization.Codec.BOOL).orElse(false);
		this.items.set(SLOT_SHARDS, input.read("shards", ItemStack.CODEC)
				.filter(stack -> SecondSoulTrial.isCapacityShard(stack))
				.orElse(ItemStack.EMPTY));
	}
}

