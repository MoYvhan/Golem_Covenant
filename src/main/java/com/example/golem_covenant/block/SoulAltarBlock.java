package com.example.golem_covenant.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.example.golem_covenant.block.entity.SoulAltarBlockEntity;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.summon.SummonManager;
import com.example.golem_covenant.trial.SecondSoulTrial;

/**
 * 灵魂圣坛 Soul Altar - the B-tier seat expansion block (spec 4.3.2) and the
 * vessel for the 第二灵魂 trial's offering (spec 11.8 clause 3).
 *
 * <p>Spec 4.3.2 is explicit that B seats must <b>not</b> be bought with
 * materials: "B 级不能靠简单堆材料无限增加，主要通过灵魂席位升级", where each seat
 * comes from completing a <em>partner task</em> (protect villagers, finish a
 * dangerous delve, defeat a specific enemy type, accumulate assisted kills, or
 * clear a soul-anchor trial).
 *
 * <p>So the altar is deliberately <b>not</b> a converter. Right-clicking it
 * opens a task board; the five task types are credited by the engines that
 * actually observe them, and {@link SummonManager#expandSlot} is called with
 * {@code altarTaskCompleted} once a task lands. That boolean is the same one the
 * engine already gates B on, so the block cannot be used to bypass the rule.
 *
 * <p>The altar also holds state (which tasks are done, and how many capacity
 * shards have been offered), so it is a {@link BaseEntityBlock} backed by
 * {@link SoulAltarBlockEntity}.
 */
public class SoulAltarBlock extends BaseEntityBlock {

	public static final MapCodec<SoulAltarBlock> CODEC =
			simpleCodec(SoulAltarBlock::new);

	public SoulAltarBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<SoulAltarBlock> codec() {
		return CODEC;
	}

	@Override
	public SoulAltarBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SoulAltarBlockEntity(pos, state);
	}

	/**
	 * Right-click without an item: report the altar's progress.
	 *
	 * <p>Reporting rather than granting is the whole point of spec 4.3.2 - a
	 * player must see which task is outstanding, otherwise the altar reads as
	 * broken.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level,
			BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!(level.getBlockEntity(pos) instanceof SoulAltarBlockEntity altar)) {
			return InteractionResult.PASS;
		}
		reportProgress(serverPlayer, altar);
		return InteractionResult.CONSUME;
	}

	/**
	 * Right-click with an item: deposit a capacity shard, or begin the rite.
	 *
	 * <p>Spec 4.3.2 is explicit that B seats must <b>not</b> be bought with
	 * materials, so this handler does not accept "payment" for a seat. It
	 * accepts exactly one material - the 契约容量碎片 - and only because spec
	 * 11.8 clause 3 names it as the offering for the 第二灵魂 trial. Every other
	 * item is refused with a message rather than silently consumed, which is
	 * what the original handler got wrong about which materials are welcome.
	 *
	 * <p>Once the offering is complete, the same right-click starts the grand
	 * soul rite (spec 11.8 clause 5).
	 */
	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state,
			Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!(level.getBlockEntity(pos) instanceof SoulAltarBlockEntity altar)) {
			return InteractionResult.PASS;
		}

		// spec 11.8 clause 3: the shard is the one accepted offering.
		if (SecondSoulTrial.isCapacityShard(stack)) {
			return depositShard(serverPlayer, altar, stack);
		}

		// spec 11.8 clause 5: with the offering complete, the altar offers the
		// grand rite instead of refusing the item.
		if (altar.offeringComplete()) {
			return startGrandRite(serverPlayer, altar, pos);
		}

		// spec 4.3.2: seats come from tasks, never from handed-in materials.
		serverPlayer.sendSystemMessage(Component.translatable(
				"golem_covenant.msg.altar_no_materials"));
		reportProgress(serverPlayer, altar);
		return InteractionResult.CONSUME;
	}

	/**
	 * Move shards from the player's hand into the altar's offering slot.
	 *
	 * <p>Takes one item stack at a time so the transfer is the same operation
	 * however the shards arrive (hand, hopper, dispenser), and so a player
	 * carrying 32 shards in a single stack is not forced to click 32 times.
	 */
	private static InteractionResult depositShard(ServerPlayer player,
			SoulAltarBlockEntity altar, ItemStack stack) {
		int room = SoulAltarBlockEntity.SHARDS_FOR_TRIAL
				- altar.depositedShards();
		if (room <= 0) {
			player.sendSystemMessage(Component.translatable(
					"golem_covenant.msg.altar_offering_full",
					SoulAltarBlockEntity.SHARDS_FOR_TRIAL));
			return InteractionResult.CONSUME;
		}
		int moved = Math.min(room, stack.getCount());
		ItemStack offered = altar.getItem(SoulAltarBlockEntity.SLOT_SHARDS);
		if (offered.isEmpty()) {
			altar.setItem(SoulAltarBlockEntity.SLOT_SHARDS,
					stack.copyWithCount(moved));
		} else {
			offered.grow(moved);
			altar.setItem(SoulAltarBlockEntity.SLOT_SHARDS, offered);
		}
		stack.shrink(moved);

		player.level().playSound(null, altar.getBlockPos(),
				SoundEvents.AMETHYST_BLOCK_CHIME,
				SoundSource.BLOCKS, 0.8f, 1.4f);
		player.sendSystemMessage(Component.translatable(
				"golem_covenant.msg.altar_shard_deposited",
				altar.depositedShards(),
				SoulAltarBlockEntity.SHARDS_FOR_TRIAL));
		if (altar.offeringComplete()) {
			// spec 11.8 clause 2: the completed offering is what activates it.
			player.sendSystemMessage(Component.translatable(
					"golem_covenant.msg.altar_activated"));
		}
		return InteractionResult.CONSUME;
	}

	/** spec 11.8 clause 5: kick off the grand soul rite. */
	private static InteractionResult startGrandRite(ServerPlayer player,
			SoulAltarBlockEntity altar, BlockPos pos) {
		SecondSoulTrial.GrandRiteResult result =
				SecondSoulTrial.beginGrandSacrifice(player, pos);
		switch (result) {
			case STARTED -> {
				player.sendSystemMessage(Component.translatable(
						"golem_covenant.msg.grand_rite_started",
						SecondSoulTrial.REQUIRED_SACRIFICE_ANCHORS));
				return InteractionResult.CONSUME;
			}
			case NEED_MORE -> player.sendSystemMessage(Component.translatable(
					"golem_covenant.msg.grand_rite_need_more",
					SecondSoulTrial.REQUIRED_SACRIFICE_ANCHORS));
			case ALREADY_RUNNING -> player.sendSystemMessage(
					Component.translatable(
							"golem_covenant.msg.grand_rite_running"));
			case ALREADY_DONE -> player.sendSystemMessage(
					Component.translatable(
							"golem_covenant.msg.grand_rite_done"));
		}
		return InteractionResult.CONSUME;
	}

	/**
	 * Record one completed partner task and award a seat if it lands.
	 *
	 * <p>Called by the engines that actually observe the task happening - the
	 * altar itself cannot know a village was protected. See
	 * {@link com.example.golem_covenant.trial.TaskObserver} for the five
	 * observing rules.
	 *
	 * @param task one of the spec 4.3.2 task kinds
	 * @return true when this task awarded a seat
	 */
	public static boolean onTaskCompleted(ServerPlayer player,
			SoulAltarBlockEntity altar, String task) {
		boolean recorded = altar.recordTask(task);
		if (!recorded) {
			// Already credited: a repeated event must not mint extra seats.
			return false;
		}
		boolean granted = SummonManager.expandSlot(player, CovenantTier.B, true);
		if (granted) {
			player.level().playSound(null, altar.getBlockPos(),
					SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 1.0f, 1.2f);
			player.sendSystemMessage(Component.translatable(
					"golem_covenant.msg.altar_seat_granted",
					SummonManager.slotLimit(player, CovenantTier.B)));
		}
		return granted;
	}

	private static void reportProgress(ServerPlayer player,
			SoulAltarBlockEntity altar) {
		player.sendSystemMessage(Component.translatable(
				"golem_covenant.cmd.altar_header"));
		player.sendSystemMessage(Component.translatable(
				"golem_covenant.cmd.altar_row",
				altar.completedCount(), SoulAltarBlockEntity.TASK_COUNT,
				SummonManager.slotLimit(player, CovenantTier.B),
				CovenantTier.B.maxSlots()));
		// spec 11.8 clause 3: the offering is the altar's other job, so it is
		// reported alongside the task count rather than buried elsewhere.
		player.sendSystemMessage(Component.translatable(
				"golem_covenant.cmd.altar_offering",
				altar.depositedShards(),
				SoulAltarBlockEntity.SHARDS_FOR_TRIAL,
				Component.translatable(altar.isActivated()
						? "golem_covenant.cmd.altar_state_active"
						: "golem_covenant.cmd.altar_state_dormant")));
		if (altar.completedCount() < SoulAltarBlockEntity.TASK_COUNT) {
			// The task key is an internal id; translate it, or the player reads
			// "protect_villagers" in chat.
			player.sendSystemMessage(Component.translatable(
					"golem_covenant.cmd.altar_next",
					Component.translatable("golem_covenant.cmd.task."
							+ altar.nextOutstandingTask())));
		}
	}
}
