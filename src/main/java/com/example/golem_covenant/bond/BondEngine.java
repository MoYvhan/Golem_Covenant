package com.example.golem_covenant.bond;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.registry.ModAttachments;

/**
 * 契合度 (Bond) 成长引擎 (spec ch.9).
 *
 * <p>Spec 9.2 is explicit that Bond must NOT be a "likeability" score, and 9.3
 * is explicit that it must NOT grant raw stats. Bond is earned through
 * <b>behaviour</b> and its only payoff is unlocking the <b>deep rules of the
 * soul anchor</b> (spec 9.3 "低 Bond: 伙伴只使用基础规则 / 高 Bond: 解锁灵魂
 * 锚点深层规则").
 *
 * <p>Therefore this engine only does two things:
 * <ol>
 *   <li>{@link #award} - observes behaviour and moves the Bond value;</li>
 *   <li>{@link #allowedDepth} - reports how deep the anchor may go, which the
 *       anchor implementations consult before using their deep behaviour.</li>
 * </ol>
 *
 * <p>Award sources, per spec 9.4:
 * <table>
 *   <caption>bond award sources</caption>
 *   <tr><th>行为</th><th>增量</th></tr>
 *   <tr><td>跟随主人（每 30 秒在场）</td><td>+1</td></tr>
 *   <tr><td>共同击杀（主人与伙伴同场击杀）</td><td>+2</td></tr>
 *   <tr><td>伙伴救助玩家 / 玩家救助伙伴</td><td>+3</td></tr>
 *   <tr><td>完成锚点事件（深层机制被成功使用）</td><td>+2</td></tr>
 *   <tr><td>长时间远离主人（>64 格，每 30 秒）</td><td>-1</td></tr>
 *   <tr><td>被强制解除契约</td><td>清零</td></tr>
 * </table>
 */
public final class BondEngine {

	private BondEngine() {
	}

	// ------------------------------------------------------------------
	// award amounts (spec 9.4)
	// ------------------------------------------------------------------

	public static final int AWARD_FOLLOW = 1;
	public static final int AWARD_SHARED_KILL = 2;
	public static final int AWARD_RESCUE = 3;
	public static final int AWARD_ANCHOR_EVENT = 2;
	public static final int PENALTY_ABSENT = -1;

	/** Bond is capped at 100 (spec 9.4 full covenant). */
	public static final int MAX_BOND = 100;

	/** Distance beyond which a companion counts as "长时间远离主人". */
	public static final double ABSENT_DISTANCE = 64.0;

	/** How often the passive follow / absence awards are evaluated. */
	private static final int PASSIVE_INTERVAL = 600; // 30 seconds

	/**
	 * Anti-farm guards: a companion cannot gain passive Bond faster than this
	 * per day, so standing AFK next to a mob is not a strategy.
	 */
	private static final int PASSIVE_DAILY_CAP = 40;

	/** entity UUID -> points already earned from passive sources today. */
	private static final Map<UUID, Integer> PASSIVE_TODAY = new HashMap<>();

	/** entity UUID -> game day the counter above belongs to. */
	private static final Map<UUID, Long> PASSIVE_DAY = new HashMap<>();

	public static void register() {
		// spec 9.4 "完成击杀" - shared kills deepen the bond
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity.level() instanceof ServerLevel level) {
				onKill(level, entity, source);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % PASSIVE_INTERVAL != 0) {
				return;
			}
			if (server.getTickCount() == 0) {
				return;
			}
			tickPassive(server);
		});
	}

	// ------------------------------------------------------------------
	// event-driven awards
	// ------------------------------------------------------------------

	/**
	 * A mob died. If a companion and its owner both had a hand in it, that is
	 * a "shared kill" (spec 9.4 共同击杀).
	 */
	private static void onKill(ServerLevel level, LivingEntity victim,
			net.minecraft.world.damagesource.DamageSource source) {
		if (!(source.getEntity() instanceof ServerPlayer killer)) {
			return;
		}
		double radius = 24.0;
		for (LivingEntity companion : level.getEntitiesOfClass(
				LivingEntity.class,
				killer.getBoundingBox().inflate(radius),
				e -> isCompanionOf(e, killer))) {
			award(companion, killer, AWARD_SHARED_KILL, "shared_kill");
		}
	}

	/**
	 * Called when a companion actually protected its owner (intercepted a hit,
	 * pulled aggro, killed something that was targeting the owner, ...).
	 * spec 9.4 伙伴救助玩家.
	 */
	public static void onCompanionRescuedOwner(LivingEntity companion,
			ServerPlayer owner) {
		award(companion, owner, AWARD_RESCUE, "rescue_owner");
	}

	/**
	 * Called when the owner healed / pulled a companion out of lethal danger.
	 * spec 9.4 玩家救助伙伴.
	 */
	public static void onOwnerRescuedCompanion(ServerPlayer owner,
			LivingEntity companion) {
		award(companion, owner, AWARD_RESCUE, "rescue_companion");
	}

	/**
	 * Called when the companion successfully expressed its anchor's deep
	 * mechanism. This is the "完成锚点事件" source in spec 9.4, and it is what
	 * makes high Bond both earned and self-reinforcing.
	 */
	public static void onAnchorEvent(LivingEntity companion, String anchorId) {
		CovenantData data = companion.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active()) {
			return;
		}
		awardRaw(companion, data.bond() + AWARD_ANCHOR_EVENT);
		GolemCovenantMod.LOGGER.debug("anchor event {} on {} -> bond {}",
				anchorId, companion.getUUID(), data.bond() + AWARD_ANCHOR_EVENT);
	}

	// ------------------------------------------------------------------
	// passive tick: follow award / absence penalty
	// ------------------------------------------------------------------

	private static void tickPassive(net.minecraft.server.MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.level() instanceof ServerLevel level)) {
				continue;
			}
			// companions near their owner -> follow award;
			// companions that drifted far away -> absence penalty
			for (LivingEntity companion : com.example.golem_covenant.summon
					.SummonManager.ownedCompanionsOf(player)) {
				if (companion.level() != level) {
					continue;
				}
				double dist = companion.distanceTo(player);
				if (dist > ABSENT_DISTANCE) {
					award(companion, player, PENALTY_ABSENT, "absent");
				} else if (dist <= 16.0) {
					passiveAward(companion, player, AWARD_FOLLOW, level);
				}
			}
		}
	}

	/** Applies the daily passive cap so AFK farming does not work. */
	private static void passiveAward(LivingEntity companion, ServerPlayer owner,
			int delta, ServerLevel level) {
		long day = level.getGameTime() / 24_000L;
		UUID id = companion.getUUID();
		Long recorded = PASSIVE_DAY.get(id);
		if (recorded == null || recorded != day) {
			PASSIVE_DAY.put(id, day);
			PASSIVE_TODAY.put(id, 0);
		}
		int used = PASSIVE_TODAY.getOrDefault(id, 0);
		if (used + delta > PASSIVE_DAILY_CAP) {
			return;
		}
		PASSIVE_TODAY.merge(id, delta, Integer::sum);
		award(companion, owner, delta, "follow");
	}

	// ------------------------------------------------------------------
	// the single write path
	// ------------------------------------------------------------------

	public static void award(LivingEntity companion, ServerPlayer owner,
			int delta, String reason) {
		if (companion == null || owner == null || delta == 0) {
			return;
		}
		CovenantData data = companion.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active()) {
			return;
		}
		if (!data.ownedBy(owner.getUUID())) {
			return;
		}
		// spec 9.1: Bond is a B/C-tier feature; A-tier is a 10-minute borrow
		if (data.tier() == CovenantTier.A) {
			return;
		}
		int before = data.anchorDepth();
		awardRaw(companion, data.bond() + delta);
		CovenantData after = companion.getAttached(ModAttachments.COVENANT);
		if (after != null && after.anchorDepth() > before) {
			announceStage(owner, companion, after);
		}
		GolemCovenantMod.LOGGER.debug("bond {} by {} on {} ({})", delta, reason,
				companion.getUUID(), data.bond());
	}

	/** Writes a raw Bond value, clamped, without re-checking thresholds. */
	private static void awardRaw(LivingEntity companion, int newBond) {
		CovenantData data = companion.getAttached(ModAttachments.COVENANT);
		if (data == null) {
			return;
		}
		int clamped = Math.clamp(newBond, 0, MAX_BOND);
		if (clamped == data.bond()) {
			return;
		}
		companion.setAttached(ModAttachments.COVENANT, data.withBond(clamped));
	}

	/** Spec 9.4: reaching a threshold unlocks a title, not endless stats. */
	private static void announceStage(ServerPlayer owner, LivingEntity companion,
			CovenantData data) {
		String stageId = data.bondStage().name().toLowerCase(java.util.Locale.ROOT);
		owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
				"golem_covenant.bond.advanced",
				companion.getDisplayName(),
				net.minecraft.network.chat.Component.translatable(
						"golem_covenant.bond." + stageId),
				String.valueOf(data.bond())));
	}

	// ------------------------------------------------------------------
	// release / revoke (spec 11.7)
	// ------------------------------------------------------------------

	/** Spec 9.4: being forcibly released zeroes the bond. */
	public static void onReleased(LivingEntity companion) {
		UUID id = companion.getUUID();
		PASSIVE_TODAY.remove(id);
		PASSIVE_DAY.remove(id);
		CovenantData data = companion.getAttached(ModAttachments.COVENANT);
		if (data == null) {
			return;
		}
		companion.setAttached(ModAttachments.COVENANT, data.withBond(0));
	}

	public static void onServerStopped() {
		PASSIVE_TODAY.clear();
		PASSIVE_DAY.clear();
	}

	// ------------------------------------------------------------------
	// the payoff: how deep may the anchor go (spec 9.3)
	// ------------------------------------------------------------------

	/**
	 * Spec 9.3: Bond gates anchor DEPTH, it does not add numbers. Anchor
	 * implementations call this before using a deep behaviour.
	 *
	 * @return 0 (base rules only) .. 4 (fully unlocked)
	 */
	public static int allowedDepth(LivingEntity companion) {
		CovenantData data = companion.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active()) {
			return 0;
		}
		return data.anchorDepth();
	}

	/** Convenience gate: has this companion unlocked at least {@code depth}? */
	public static boolean hasDepth(LivingEntity companion, int depth) {
		return allowedDepth(companion) >= depth;
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static boolean isCompanionOf(LivingEntity entity, ServerPlayer owner) {
		CovenantData data = entity.getAttached(ModAttachments.COVENANT);
		return data != null && data.active() && data.ownedBy(owner.getUUID())
				&& entity instanceof Mob;
	}

	/** Diagnostic view of the passive counters. */
	public static Map<UUID, Integer> passiveSnapshot() {
		return Map.copyOf(PASSIVE_TODAY);
	}
}
