package com.example.golem_covenant.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.ritual.RitualEngine;
import com.example.golem_covenant.summon.SummonManager;

/**
 * 契约石碑 Covenant Stele - the A-tier capacity expansion block (spec 4.3.1).
 *
 * <p>Spec 4.3.1 lists three ways to raise the A-tier slot count, and the stele
 * is the first: "玩家建立一个特殊石碑。完成对应仪式后：+1 A 级槽位。"
 *
 * <p>The important word is <b>完成对应仪式后</b> - the +1 is <em>not</em> granted
 * on placement. Placing the stele only opens the ritual; the slot is awarded
 * when the ritual actually completes (see {@link #completeRite}). This keeps the
 * stele consistent with spec 4.3's opening rule that a slot must never be a
 * simple "one item = +1", and it is why the block is not just a right-click
 * vending machine.
 *
 * <p>Spec 4.3.1 caps A at 8 (enforced by {@code CovenantTier.maxSlots()}), so a
 * stele on its own cannot exceed the tier ceiling; {@link SummonManager}
 * re-checks the cap and reports failure rather than silently discarding.
 */
public class CovenantSteleBlock extends Block {

	public static final MapCodec<CovenantSteleBlock> CODEC =
			simpleCodec(CovenantSteleBlock::new);

	public CovenantSteleBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	/**
	 * Spec 4.3.1 interactions. Right-clicking the stele is what starts the
	 * rite, so this must not add a slot by itself.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level,
			BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			// Render-side only: acknowledge the click so the arm swing plays,
			// and let the server decide what actually happens.
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		return beginRite(level, pos, serverPlayer);
	}

	/**
	 * Attempt to begin the A-tier expansion rite at this stele.
	 *
	 * <p>Split out from {@link #useWithoutItem} so the ritual layer can also
	 * drive it (spec 5.1 lets a ceremony be completed by its own engine rather
	 * than only by a click).
	 */
	public static InteractionResult beginRite(Level level, BlockPos pos,
			ServerPlayer player) {
		// The ceiling is a tier property, so check it before burning a rite.
		if (SummonManager.slotLimit(player, CovenantTier.A)
				>= CovenantTier.A.maxSlots()) {
			player.sendSystemMessage(Component.translatable(
					"golem_covenant.msg.stele_at_max",
					CovenantTier.A.maxSlots()));
			return InteractionResult.FAIL;
		}
		// RitualEngine emits server-side particle packets, so it needs the
		// concrete ServerLevel the caller's Level is backed by. The callers
		// already guarantee this via the isClientSide() guard; the pattern
		// match makes that guarantee explicit rather than assumed.
		if (!(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}
		boolean started = RitualEngine.beginSteleRite(player, serverLevel, pos);
		if (!started) {
			player.sendSystemMessage(Component.translatable(
					"golem_covenant.msg.stele_rite_running"));
			return InteractionResult.FAIL;
		}
		level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE,
				SoundSource.BLOCKS, 1.0f, 0.6f);
		player.sendSystemMessage(Component.translatable(
				"golem_covenant.msg.stele_rite_started"));
		return InteractionResult.CONSUME;
	}

	/**
	 * Award the slot. Called by the ritual layer when the rite completes.
	 *
	 * <p>Returns the same boolean contract as
	 * {@link SummonManager#expandSlot}: {@code false} means the cap was already
	 * reached, which the caller reports instead of claiming a slot was granted.
	 */
	public static boolean completeRite(ServerPlayer player) {
		boolean granted = SummonManager.expandSlot(player, CovenantTier.A, false);
		if (granted) {
			// getScoreboardName() is the 26.2 accessor; GameProfile.getName()
			// no longer exists on the profile object.
			GolemCovenantMod.LOGGER.debug(
					"A-tier slot granted via covenant stele to {}",
					player.getScoreboardName());
		}
		return granted;
	}
}
