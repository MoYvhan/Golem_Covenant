package com.example.golem_covenant.summon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.compat.GolemizationCompat;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.data.FormsRegistry;
import com.example.golem_covenant.data.SoulProfile;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.ritual.RitualEngine;

/**
 * 灵魂召唤管理器 (spec 4.7 / 4.9 / 11.7 / 11.10).
 *
 * <p>Server-authoritative. Runs the slot-check pipeline, owns the A/B/C slot
 * ledger and the soul-capacity ledger, and applies every解除 / 撤销 rule.
 *
 * <p>Player ledgers are held in memory and rebuilt from the live companion
 * entities on demand, so a restart cannot desync a player's used slots
 * (spec 11.10.3).
 */
public final class SummonManager {

	private SummonManager() {
	}

	/** player -> per-tier slot expansion purchased (spec 4.3). */
	private static final Map<UUID, Map<CovenantTier, Integer>> SLOT_BONUS =
			new HashMap<>();

	/** player -> soul capacity expansion (spec 11.9). */
	private static final Map<UUID, Integer> CAPACITY_BONUS = new HashMap<>();

	/** spec 11.9.1: base soul capacity is 20. */
	public static final int BASE_SOUL_CAPACITY = 20;

	/** entity UUID -> contract data, mirrors the attachment for fast lookup. */
	private static final Map<UUID, CovenantData> LEDGER = new HashMap<>();

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	public static void register() {
		// track companion entities so the ledger can be rebuilt after restart
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			CovenantData d = entity.getAttached(ModAttachments.COVENANT);
			if (d != null && d.active() && d.formIdOpt().isPresent()) {
				LEDGER.put(entity.getUUID(), d);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) ->
				LEDGER.remove(entity.getUUID()));

		ServerTickEvents.END_SERVER_TICK.register(SummonManager::tickCompanions);
	}

	// ------------------------------------------------------------------
	// contract pipeline (spec 4.7)
	// ------------------------------------------------------------------

	/**
	 * The full slot-check pipeline from spec 4.7:
	 * <pre>
	 * target -> is golem -> read FormId -> read tier -> count -> capacity
	 *        -> already owned -> contract conditions -> ritual -> bind
	 * </pre>
	 */
	public static InteractionResult attemptContract(ItemStack stack, Player player,
			LivingEntity target, CovenantTier tier) {
		if (player.level().isClientSide()) {
			// spec 12.4: only the server decides; the client optimistically
			// reports success so the swing animation plays.
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer sp)) {
			return InteractionResult.PASS;
		}

		// (0) soft dependency gate - spec 11.1.3, must never crash
		if (!GolemizationCompat.isBaseReady()) {
			msg(sp, "golem_covenant.msg.base_mod_missing", ChatFormatting.RED);
			return InteractionResult.FAIL;
		}

		// (1) is the target actually a golem?
		if (!GolemizationCompat.isGolemized(target)) {
			msg(sp, "golem_covenant.msg.not_golemized", ChatFormatting.YELLOW);
			return InteractionResult.FAIL;
		}

		// (2) resolve FormId from the external mapping
		Optional<String> formId = GolemizationCompat.getFormId(target);
		if (formId.isEmpty()) {
			msg(sp, "golem_covenant.msg.no_form", ChatFormatting.YELLOW);
			return InteractionResult.FAIL;
		}

		// (3) is this form supported by the base mod?
		if (!GolemizationCompat.isSupportedForm(formId.get())) {
			msg(sp, "golem_covenant.msg.unsupported_form", ChatFormatting.YELLOW);
			return InteractionResult.FAIL;
		}

		Optional<SoulProfile> profileOpt = FormsRegistry.byFormId(formId.get());
		if (profileOpt.isEmpty()) {
			msg(sp, "golem_covenant.msg.unknown_form", ChatFormatting.YELLOW);
			return InteractionResult.FAIL;
		}
		SoulProfile profile = profileOpt.get();

		// (4) duplicate rule - spec 4.8, one soul instance = one companion
		if (LEDGER.containsKey(target.getUUID())) {
			msg(sp, "golem_covenant.msg.already_bound", ChatFormatting.YELLOW);
			return InteractionResult.FAIL;
		}
		if (hasFormAlready(sp, profile.formId(), tier)) {
			msg(sp, "golem_covenant.msg.duplicate_form", ChatFormatting.YELLOW);
			return InteractionResult.FAIL;
		}

		// (5) slot check - spec 4.2 / 4.3
		if (!canSummon(sp, tier)) {
			msg(sp, "golem_covenant.msg.slots_full", ChatFormatting.RED,
					tier.defaultSlots() + slotBonus(sp, tier));
			return InteractionResult.FAIL;
		}

		// (6) soul capacity check - spec 4.4 / 11.9
		int load = profile.limits().soulLoad(tier);
		if (usedSoulCapacity(sp) + load > soulCapacity(sp)) {
			msg(sp, "golem_covenant.msg.capacity_full", ChatFormatting.RED,
					usedSoulCapacity(sp), soulCapacity(sp), load);
			return InteractionResult.FAIL;
		}

		// (7) bind + register + ceremony
		CovenantData data = CovenantData.create(sp.getUUID(), profile, tier,
				sp.level().getGameTime(), nextFreeSlot(sp, tier));
		target.setAttached(ModAttachments.COVENANT, data);
		GolemizationCompat.bindOwner(target, sp);
		LEDGER.put(target.getUUID(), data);
		tameIfPossible(target, sp);

		RitualEngine.playCeremony(sp, target, profile, tier,
				profile.familyId());

		// spec 3.3: consume one item unless the player is in creative
		if (!sp.isCreative()) {
			stack.shrink(1);
		}
		msg(sp, "golem_covenant.msg.contract_success", ChatFormatting.GREEN,
				profile.displayName());
		msg(sp, "golem_covenant.msg.capacity_status", ChatFormatting.AQUA,
				usedSoulCapacity(sp), soulCapacity(sp));
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------
	// slot / capacity ledger (spec 4.9 SummonManager interface)
	// ------------------------------------------------------------------

	public static List<CovenantData> activeCompanions(ServerPlayer player,
			CovenantTier tier) {
		List<CovenantData> out = new ArrayList<>();
		for (CovenantData d : LEDGER.values()) {
			if (d.active() && d.ownedBy(player.getUUID()) && d.tier() == tier) {
				out.add(d);
			}
		}
		return out;
	}

	public static int getActiveA(ServerPlayer player) {
		return activeCompanions(player, CovenantTier.A).size();
	}

	public static int getActiveB(ServerPlayer player) {
		return activeCompanions(player, CovenantTier.B).size();
	}

	public static int getActiveC(ServerPlayer player) {
		return activeCompanions(player, CovenantTier.C).size();
	}

	public static int slotBonus(ServerPlayer player, CovenantTier tier) {
		return SLOT_BONUS.getOrDefault(player.getUUID(), Map.of())
				.getOrDefault(tier, 0);
	}

	public static int slotLimit(ServerPlayer player, CovenantTier tier) {
		return Math.min(tier.defaultSlots() + slotBonus(player, tier),
				tier.maxSlots());
	}

	public static boolean canSummonA(ServerPlayer player) {
		return getActiveA(player) < slotLimit(player, CovenantTier.A);
	}

	public static boolean canSummonB(ServerPlayer player) {
		return getActiveB(player) < slotLimit(player, CovenantTier.B);
	}

	public static boolean canSummonC(ServerPlayer player) {
		return getActiveC(player) < slotLimit(player, CovenantTier.C);
	}

	public static boolean canSummon(ServerPlayer player, CovenantTier tier) {
		return switch (tier) {
			case A -> canSummonA(player);
			case B -> canSummonB(player);
			case C -> canSummonC(player);
		};
	}

	/** spec 11.9.1: base 20, plus purchased expansion. */
	public static int getSoulCapacity(ServerPlayer player) {
		return soulCapacity(player);
	}

	private static int soulCapacity(ServerPlayer player) {
		return BASE_SOUL_CAPACITY + CAPACITY_BONUS.getOrDefault(player.getUUID(), 0);
	}

	public static int getUsedSoulCapacity(ServerPlayer player) {
		return usedSoulCapacity(player);
	}

	private static int usedSoulCapacity(ServerPlayer player) {
		int total = 0;
		for (CovenantData d : LEDGER.values()) {
			if (d.active() && d.ownedBy(player.getUUID())) {
				total += d.soulLoad();
			}
		}
		return total;
	}

	public static void registerCompanion(UUID entityId, CovenantData data) {
		LEDGER.put(entityId, data);
	}

	public static void unregisterCompanion(UUID entityId) {
		LEDGER.remove(entityId);
	}

	// ------------------------------------------------------------------
	// expansion (spec 4.3 / 11.8 / 11.9)
	// ------------------------------------------------------------------

	/**
	 * Spends shards to unlock a slot.
	 *
	 * <p>{@code altarTaskCompleted} is load-bearing in two places, because the
	 * two non-purchasable tiers are gated on different things:
	 * <ul>
	 *   <li><b>B (spec 4.3.2)</b> - seats only ever come from 灵魂圣坛 tasks,
	 *       so the flag must be true.</li>
	 *   <li><b>C (spec 4.3.3)</b> - the second seat needs the whole 第二灵魂
	 *       trial, which the caller passes as the same flag. A partial trial
	 *       must not unlock it, which is why the callers pass
	 *       {@link com.example.golem_covenant.trial.SecondSoulTrial#completed}
	 *       rather than any single clause.</li>
	 * </ul>
	 */
	public static boolean expandSlot(ServerPlayer player, CovenantTier tier,
			boolean altarTaskCompleted) {
		int current = slotBonus(player, tier);
		if (tier.defaultSlots() + current >= tier.maxSlots()) {
			return false;
		}
		if (tier == CovenantTier.B && !altarTaskCompleted) {
			// spec 4.3.2: B slots only come from 灵魂圣坛 tasks
			return false;
		}
		if (tier == CovenantTier.C) {
			// spec 4.3.3: the second C slot needs the "第二灵魂" trial
			return altarTaskCompleted && current == 0;
		}
		SLOT_BONUS.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
				.merge(tier, 1, Integer::sum);
		return true;
	}

	/**
	 * spec 4.3.3 / 11.8: unlock the 第二圣契位 once the trial is complete.
	 *
	 * <p>Separate from {@link #expandSlot} so the trial can be checked here
	 * rather than trusted from the caller. A caller that passed {@code true}
	 * by mistake would otherwise hand out the mod's hardest reward for free.
	 *
	 * @return true when the seat was actually granted
	 */
	public static boolean grantSecondSoulSeat(ServerPlayer player) {
		if (!com.example.golem_covenant.trial.SecondSoulTrial
				.completed(player)) {
			return false;
		}
		return expandSlot(player, CovenantTier.C, true);
	}

	/** spec 11.9.2: capacity fragments raise the soul capacity pool. */
	public static boolean expandCapacity(ServerPlayer player, int amount) {
		int current = CAPACITY_BONUS.getOrDefault(player.getUUID(), 0);
		if (current >= 20) {
			return false;
		}
		CAPACITY_BONUS.put(player.getUUID(),
				Math.min(20, current + Math.max(1, amount)));
		return true;
	}

	/**
	 * spec 11.8: the live 第二灵魂 trial standing, as {@code condition -> progress}.
	 *
	 * <p>Delegates to {@link com.example.golem_covenant.trial.SecondSoulTrial}
	 * so the numbers the command prints and the numbers the gate checks are
	 * produced by one implementation. The earlier stub here returned three
	 * hardcoded zeroes, which meant the trial could be "complete" in the
	 * readout without anything having been observed.
	 *
	 * @deprecated prefer {@code SecondSoulTrial.conditions}, which keeps the
	 *             condition ids alongside their thresholds. Kept because the
	 *             map shape is convenient for a compact diagnostic dump.
	 */
	@Deprecated
	public static Map<String, Integer> trialProgress(ServerPlayer player) {
		Map<String, Integer> out = new HashMap<>();
		for (com.example.golem_covenant.trial.TrialCondition condition
				: com.example.golem_covenant.trial.SecondSoulTrial
						.conditions(player)) {
			out.put(condition.id(), condition.progress());
		}
		return out;
	}

	/** spec 11.8: true when every clause of the 第二灵魂 trial is satisfied. */
	public static boolean secondSoulTrialComplete(ServerPlayer player) {
		return com.example.golem_covenant.trial.SecondSoulTrial
				.completed(player);
	}

	// ------------------------------------------------------------------
	// release / revoke (spec 11.7)
	// ------------------------------------------------------------------

	/**
	 * Spec 11.7 release table. Voluntary release is allowed; it resets bond to
	 * 0 and refunds nothing (decision recorded here as the spec requires).
	 */
	public static boolean release(ServerPlayer player, UUID entityId,
			boolean voluntary) {
		CovenantData d = LEDGER.get(entityId);
		if (d == null || !d.ownedBy(player.getUUID())) {
			return false;
		}
		LEDGER.remove(entityId);
		GolemizationCompat.unbindOwner(player.level().getEntity(entityId));
		Entity e = player.level().getEntity(entityId);
		if (e != null) {
			e.setAttached(ModAttachments.COVENANT, CovenantData.empty());
		}
		if (voluntary) {
			msg(player, "golem_covenant.msg.released", ChatFormatting.GRAY);
		}
		return true;
	}

	/** Spec 11.7: contract transfer between players is explicitly forbidden. */
	public static boolean transfer(ServerPlayer from, ServerPlayer to, UUID id) {
		return false;
	}

	// ------------------------------------------------------------------
	// tick: A-tier timer, distance release, AI behaviour
	// ------------------------------------------------------------------

	private static void tickCompanions(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		List<UUID> expired = new ArrayList<>();
		for (Map.Entry<UUID, CovenantData> e : new HashMap<>(LEDGER).entrySet()) {
			ServerPlayer owner = server.getPlayerList()
					.getPlayer(e.getValue().ownerUUID());
			if (owner == null) {
				continue;
			}
			ServerLevel level = (ServerLevel) owner.level();
			Entity entity = level.getEntity(e.getKey());
			if (!(entity instanceof LivingEntity companion)) {
				continue;
			}
			CovenantData data = companion.getAttached(ModAttachments.COVENANT);
			if (data == null || !data.active()) {
				continue;
			}

			// A-tier 10 minute timer (spec 2.2)
			if (data.tier() == CovenantTier.A) {
				long age = level.getGameTime() - data.contractTime();
				if (age >= CovenantTier.A.lifetimeTicks()) {
					expired.add(e.getKey());
					continue;
				}
			}
			// distance leash: A-tier never chases far (spec 2.2)
			double dist = companion.distanceTo(owner);
			double leash = data.tier().defaultSlots() > 0 ? 24.0 : 0;
			if (dist > leash && companion instanceof Mob mob) {
				mob.getNavigation().moveTo(owner, 1.2);
				mob.setTarget(null);
			}
			// Bond accrual is owned by BondEngine (spec ch.9) - it owns the
			// daily caps and threshold announcements, so the ledger must not
			// write bond values itself.
		}
		for (UUID id : expired) {
			CovenantData d = LEDGER.remove(id);
			GolemCovenantMod.LOGGER.debug("A-tier contract expired: {}", id);
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Live companions owned by {@code player}. Used by {@code BondEngine} for
	 * the absence penalty and by the resonance engine for team evaluation.
	 */
	public static List<LivingEntity> ownedCompanionsOf(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return List.of();
		}
		List<LivingEntity> out = new ArrayList<>();
		for (UUID id : LEDGER.keySet()) {
			Entity e = level.getEntity(id);
			if (e instanceof LivingEntity living && isActiveCompanion(living,
					player.getUUID())) {
				out.add(living);
			}
		}
		return out;
	}

	/** True when {@code entity} is an active companion belonging to {@code owner}. */
	public static boolean isActiveCompanion(LivingEntity entity, UUID owner) {
		CovenantData d = entity.getAttached(ModAttachments.COVENANT);
		return d != null && d.active() && d.ownedBy(owner);
	}

	private static boolean hasFormAlready(ServerPlayer player, String formId,
			CovenantTier tier) {
		// spec 4.8: B/C cannot be duplicated; A-tier is a temporary loan so
		// the rule only applies to permanent tiers.
		if (tier == CovenantTier.A) {
			return false;
		}
		return LEDGER.values().stream().anyMatch(d -> d.active()
				&& d.ownedBy(player.getUUID()) && d.formId().equals(formId));
	}

	private static int nextFreeSlot(ServerPlayer player, CovenantTier tier) {
		java.util.Set<Integer> used = new java.util.HashSet<>();
		for (CovenantData d : activeCompanions(player, tier)) {
			used.add(d.slotIndex());
		}
		List<Integer> sorted = new ArrayList<>(used);
		sorted.sort(Comparator.naturalOrder());
		for (int i = 0; i < slotLimit(player, tier); i++) {
			if (!used.contains(i)) {
				return i;
			}
		}
		return used.size();
	}

	private static void tameIfPossible(LivingEntity target, ServerPlayer owner) {
		// making the companion follow its owner is delegation, not a stat buff
		if (target instanceof Mob mob) {
			mob.setPersistenceRequired();
			mob.setTarget(null);
		}
	}

	static void msg(ServerPlayer player, String key, ChatFormatting color,
			Object... args) {
		player.sendSystemMessage(
				Component.translatable(key, args).withStyle(color), false);
	}

	/** Diagnostic view for the {@code /golem covenant} command. */
	public static Map<UUID, CovenantData> ledger() {
		return Map.copyOf(LEDGER);
	}

	/**
	 * Spec 11.14: the ledger is a runtime index over entity attachments, so it
	 * is rebuilt on load and must not survive a server stop.
	 */
	public static void onServerStopped() {
		LEDGER.clear();
	}
}
