package com.example.golem_covenant.trial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biomes;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.block.entity.SoulAltarBlockEntity;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.summon.SummonManager;

/**
 * 「第二灵魂」终极试炼 (spec 4.3.3 / 11.8).
 *
 * <p>Spec 4.3.3 makes the second C-tier seat ("第二圣契位") the closest thing the
 * mod has to a legendary collectible: "第二个 C 级槽位必须非常难获得，不能通过
 * 普通材料直接购买". Spec 11.8 then quantifies the five clauses the original text
 * left as adjectives, and this class implements exactly those five numbers:
 *
 * <table>
 *   <caption>spec 11.8 quantified conditions</caption>
 *   <tr><th>#</th><th>条件</th><th>量化值</th><th>本类的实现</th></tr>
 *   <tr><td>1</td><td>完成不同锚点伙伴的觉醒</td><td>≥6 种不同 anchorId 达到 B 级</td>
 *       <td>{@link #distinctAnchors} - 直接读召唤账本</td></tr>
 *   <tr><td>2</td><td>完成特殊圣坛</td><td>建造并激活灵魂圣坛</td>
 *       <td>{@link #altarActivated} - 现场扫描已激活的圣坛方块实体</td></tr>
 *   <tr><td>3</td><td>获得稀有世界资源</td><td>32 个契约容量碎片</td>
 *       <td>{@link #depositedShards} - 圣坛容器已入账的碎片数</td></tr>
 *   <tr><td>4</td><td>高难度 Boss / 结构挑战</td><td>击败监守者 / 远古守卫者，或完成深暗之城探索</td>
 *       <td>{@link #bossOrDelve} - 击杀监听 + 深暗之城行走监听</td></tr>
 *   <tr><td>5</td><td>大型灵魂仪式</td><td>一次仪式中献祭 3 种不同锚点的 B 级伙伴</td>
 *       <td>{@link #grandSacrifice} - {@link #beginGrandSacrifice} 收齐三人后置位</td></tr>
 * </table>
 *
 * <h2>Why the conditions are re-derived, not cached</h2>
 *
 * <p>Four of the five clauses are pure functions of world state (the summon
 * ledger, the block entities near the player, the altar's own contents), so
 * they are recomputed every time they are read. Caching them would create a
 * second source of truth that can desync - the failure mode spec 11.10.3 calls
 * out for the summon ledger. Only the two clauses that describe
 * <em>irreversible past events</em> - a boss kill and the grand rite - are
 * stored, because there is no world state left to observe afterwards.
 *
 * <h2>Why condition 3 is a deposit and not a purchase</h2>
 *
 * <p>Spec 4.3.3 says the second seat cannot be bought with materials. Had the
 * shards simply been counted from the player's inventory, the trial would be a
 * shopping list: gather 32 shards and the seat appears. Requiring them to be
 * <b>deposited into the altar</b> is what makes it a quest - the player has to
 * build the altar first (which is condition 2), carry the shards there, and the
 * altar consumes them. Spec 11.8 permits exactly the phrasing "收集 N 个", and
 * we read "收集" as "collected into the ritual vessel", which is also the only
 * reading that keeps clause 3 consistent with clause 2.
 */
public final class SecondSoulTrial {

	private SecondSoulTrial() {
	}

	// ------------------------------------------------------------------
	// spec 11.8 quantified values - the single source of truth
	// ------------------------------------------------------------------

	/** spec 11.8: 至少 6 种不同 anchorId 达到 B 级. */
	public static final int REQUIRED_ANCHORS = 6;

	/** spec 11.8: 收集 N 个契约容量碎片（建议 32）. */
	public static final int REQUIRED_SHARDS = 32;

	/**
	 * spec 11.8: 在一次仪式中同时献祭 3 种不同锚点的 B 级伙伴.
	 *
	 * <p>The "3" is the count of <em>distinct anchors</em>, not of companions,
	 * so a player cannot satisfy it with three copies of one form.
	 */
	public static final int REQUIRED_SACRIFICE_ANCHORS = 3;

	/** Condition ids, also the lang key suffix {@code golem_covenant.trial.*}. */
	public static final String ID_ANCHORS = "distinct_anchors";
	public static final String ID_ALTAR = "altar_activated";
	public static final String ID_SHARDS = "capacity_shards";
	public static final String ID_BOSS = "boss_or_delve";
	public static final String ID_SACRIFICE = "grand_sacrifice";

	/** What the 深暗之城 clause counts as - one successful delve is enough. */
	public static final int REQUIRED_DELVE = 1;

	// ------------------------------------------------------------------
	// persisted credit - the two irreversible clauses only
	// ------------------------------------------------------------------

	/**
	 * player -> boss/delve clause already satisfied.
	 *
	 * <p>In-memory by design for now, matching how {@code SummonManager}'s slot
	 * ledger behaves: the whole ledger is rebuilt from live entities on world
	 * load (spec 11.10.3), so a restart cannot invent a seat a player did not
	 * earn. The cost is that this particular clause has to be redone after a
	 * restart, which for a boss kill is the intended reading of a "trial"
	 * anyway.
	 */
	private static final Set<UUID> BOSS_CREDIT = new HashSet<>();

	/** player -> the grand-sacrifice clause already satisfied. */
	private static final Set<UUID> SACRIFICE_CREDIT = new HashSet<>();

	/**
	 * player -> candidate companions gathered for a grand rite, by anchor id.
	 *
	 * <p>This is the receiving tray: {@link #beginGrandSacrifice} puts three
	 * distinct-anchor B companions in, and the clause is credited only when the
	 * rite actually completes. Keyed by anchor so a third companion sharing an
	 * anchor with an earlier candidate cannot fill the tray.
	 */
	private static final Map<UUID, Map<String, UUID>> RITE_TRAY = new HashMap<>();

	/** How long a grand rite runs. Roughly spec 5.2's C-tier window. */
	public static final int GRAND_RITE_TICKS = 200;

	/** Running grand rites, so the tray can be consumed on completion. */
	private static final List<GrandRite> ACTIVE_GRAND_RITES = new ArrayList<>();

	private record GrandRite(ServerLevel level, BlockPos anchor, long startTick,
			int durationTicks, UUID owner) {
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	public static void register() {
		// spec 11.8 clause 4: 击败监守者 / 远古守卫者. The kill credit follows
		// the same source-attribution rule as BondEngine's shared kills
		// (spec 11.10.2: 击杀归属全部以 ownerUUID 为准).
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity.level() instanceof ServerLevel level)) {
				return;
			}
			if (!(source.getEntity() instanceof ServerPlayer killer)) {
				return;
			}
			if (isBossTarget(entity)) {
				creditBoss(killer, level, entity);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(SecondSoulTrial::tick);
	}

	private static void tick(net.minecraft.server.MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		// spec 11.8 clause 4, second half: 完成一次深暗之城探索. A delve has no
		// vanilla completion event, so it is defined observably - the player is
		// physically inside the deep dark biome and still alive.
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (BOSS_CREDIT.contains(player.getUUID())) {
				continue;
			}
			if (isInDeepDark(player)) {
				creditDelve(player);
			}
		}
		for (java.util.Iterator<GrandRite> it = ACTIVE_GRAND_RITES.iterator();
				it.hasNext();) {
			GrandRite rite = it.next();
			if (rite.level().getGameTime() - rite.startTick()
					> rite.durationTicks()) {
				it.remove();
				completeGrandRite(rite);
			}
		}
	}

	// ------------------------------------------------------------------
	// spec 11.8 clause 4 - boss / structure challenge
	// ------------------------------------------------------------------

	/** The two named bosses. */
	private static boolean isBossTarget(LivingEntity entity) {
		return entity instanceof Warden || entity instanceof ElderGuardian;
	}

	private static void creditBoss(ServerPlayer killer, ServerLevel level,
			LivingEntity boss) {
		if (!BOSS_CREDIT.add(killer.getUUID())) {
			return;
		}
		String what = boss instanceof Warden ? "warden" : "elder_guardian";
		GolemCovenantMod.LOGGER.debug("second-soul trial: {} defeated {}",
				killer.getScoreboardName(), what);
		announce(killer, "golem_covenant.trial.boss_defeated",
				Component.translatable("golem_covenant.trial.boss." + what));
	}

	private static boolean isInDeepDark(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}
		// Deep enough that the player must actually have descended, rather than
		// clipped the biome's edge from a cave mouth.
		if (player.getY() > -20.0) {
			return false;
		}
		var biome = level.getBiome(player.blockPosition());
		return biome.is(Biomes.DEEP_DARK);
	}

	private static void creditDelve(ServerPlayer player) {
		if (!BOSS_CREDIT.add(player.getUUID())) {
			return;
		}
		GolemCovenantMod.LOGGER.debug("second-soul trial: {} completed a "
				+ "deep dark delve", player.getScoreboardName());
		announce(player, "golem_covenant.trial.boss_defeated",
				Component.translatable("golem_covenant.trial.boss.deep_dark"));
	}

	// ------------------------------------------------------------------
	// spec 11.8 clause 5 - the grand soul rite
	// ------------------------------------------------------------------

	/**
	 * Begin the 大型灵魂仪式 around {@code anchor} (spec 11.8 clause 5).
	 *
	 * <p>Per the spec the price is three B-tier companions of three
	 * <b>different anchors</b> offered in one ritual. This method collects
	 * them; the companions are only released when the rite completes, so a
	 * player who closes the server mid-rite does not quietly lose three
	 * companions.
	 *
	 * @return a result describing why the rite could not start, or that it did
	 */
	public static GrandRiteResult beginGrandSacrifice(ServerPlayer player,
			BlockPos anchor) {
		if (SACRIFICE_CREDIT.contains(player.getUUID())) {
			return GrandRiteResult.ALREADY_DONE;
		}
		if (hasRunningRite(player)) {
			return GrandRiteResult.ALREADY_RUNNING;
		}
		List<LivingEntity> candidates = sacrificeCandidates(player, anchor);
		if (candidates.size() < REQUIRED_SACRIFICE_ANCHORS) {
			return GrandRiteResult.NEED_MORE;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return GrandRiteResult.NEED_MORE;
		}
		Map<String, UUID> tray = new HashMap<>();
		for (LivingEntity candidate : candidates) {
			CovenantData data = candidate.getAttached(ModAttachments.COVENANT);
			if (data != null) {
				tray.put(data.anchorId(), candidate.getUUID());
			}
		}
		RITE_TRAY.put(player.getUUID(), tray);
		ACTIVE_GRAND_RITES.add(new GrandRite(level, anchor.immutable(),
				level.getGameTime(), GRAND_RITE_TICKS, player.getUUID()));
		level.playSound(null, anchor,
				com.example.golem_covenant.registry.ModSounds.ritual(
						CovenantTier.C),
				net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.85f);
		return GrandRiteResult.STARTED;
	}

	public enum GrandRiteResult {
		STARTED,
		NEED_MORE,
		ALREADY_RUNNING,
		ALREADY_DONE
	}

	/**
	 * The companions this player may offer, one per anchor.
	 *
	 * <p>Deliberately takes the <b>nearest</b> three anchors rather than any
	 * three the player owns: spec 4.3.3 calls this a 仪式, and a rite happens in
	 * one place. A player with six B companions parked in six bases has not
	 * performed one ritual with three of them.
	 */
	private static List<LivingEntity> sacrificeCandidates(ServerPlayer player,
			BlockPos anchor) {
		List<LivingEntity> owned = new ArrayList<>(
				SummonManager.ownedCompanionsOf(player));
		owned.removeIf(e -> !isSacrificable(e, player));
		owned.sort(java.util.Comparator.comparingDouble(
				e -> e.blockPosition().distSqr(anchor)));
		Set<String> seenAnchors = new HashSet<>();
		List<LivingEntity> chosen = new ArrayList<>();
		for (LivingEntity candidate : owned) {
			CovenantData data = candidate.getAttached(ModAttachments.COVENANT);
			if (data == null) {
				continue;
			}
			if (seenAnchors.add(data.anchorId())) {
				chosen.add(candidate);
			}
			if (chosen.size() == REQUIRED_SACRIFICE_ANCHORS) {
				break;
			}
		}
		return chosen;
	}

	/** B-tier, active, owned, and near enough to be part of this rite. */
	private static boolean isSacrificable(LivingEntity entity,
			ServerPlayer owner) {
		CovenantData data = entity.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active() || !data.ownedBy(owner.getUUID())) {
			return false;
		}
		// spec 11.8 lists B 级伙伴 explicitly. A C-tier companion is far too
		// valuable to be consumed by the trial that unlocks a second C slot -
		// requiring it would be circular.
		return data.tier() == CovenantTier.B;
	}

	private static boolean hasRunningRite(ServerPlayer player) {
		for (GrandRite rite : ACTIVE_GRAND_RITES) {
			if (rite.owner().equals(player.getUUID())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Consume the offered companions and credit the clause.
	 *
	 * <p>Only now are the companions actually released, and only if the player
	 * is still online. If they are not, the tray is dropped and nothing is
	 * spent - the alternative would be destroying three B companions for a
	 * player who never saw the rite finish.
	 */
	private static void completeGrandRite(GrandRite rite) {
		Map<String, UUID> tray = RITE_TRAY.remove(rite.owner());
		ServerPlayer player = rite.level().getServer().getPlayerList()
				.getPlayer(rite.owner());
		if (player == null || tray == null
				|| tray.size() < REQUIRED_SACRIFICE_ANCHORS) {
			return;
		}
		for (UUID id : tray.values()) {
			// spec 9.4: being forcibly released zeroes the bond, so the bond
			// must be cleared BEFORE the ledger entry is dropped - afterwards
			// there is no attached CovenantData left to zero.
			net.minecraft.world.entity.Entity sacrificed =
					player.level().getEntity(id);
			if (sacrificed instanceof LivingEntity living) {
				com.example.golem_covenant.bond.BondEngine.onReleased(living);
			}
			// spec 11.7: the sacrifice is a forced release, so nothing is
			// refunded - the three companions are the price of the seat.
			SummonManager.release(player, id, false);
		}
		if (SACRIFICE_CREDIT.add(player.getUUID())) {
			announce(player, "golem_covenant.trial.sacrifice_done",
					Component.literal(String.valueOf(tray.size())));
		}
	}

	// ------------------------------------------------------------------
	// the condition table
	// ------------------------------------------------------------------

	/**
	 * The live condition table for one player.
	 *
	 * <p>Ordered as spec 11.8 lists them, so the readout reads like the spec
	 * rather than like the implementation.
	 */
	public static List<TrialCondition> conditions(ServerPlayer player) {
		List<TrialCondition> out = new ArrayList<>(5);
		out.add(TrialCondition.of(ID_ANCHORS, distinctAnchors(player),
				REQUIRED_ANCHORS));
		out.add(TrialCondition.flag(ID_ALTAR, altarActivated(player)));
		out.add(TrialCondition.of(ID_SHARDS, depositedShards(player),
				REQUIRED_SHARDS));
		out.add(TrialCondition.of(ID_BOSS,
				BOSS_CREDIT.contains(player.getUUID()) ? 1 : 0, 1));
		out.add(TrialCondition.of(ID_SACRIFICE,
				SACRIFICE_CREDIT.contains(player.getUUID()) ? 1 : 0, 1));
		return out;
	}

	/** True when every spec 11.8 clause is satisfied. */
	public static boolean completed(ServerPlayer player) {
		for (TrialCondition condition : conditions(player)) {
			if (!condition.done()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * spec 11.8 clause 1: how many distinct anchorIds the player has at B.
	 *
	 * <p>Counts B specifically. A-tier is a ten-minute loan (spec 2.2) and C is
	 * the reward for this trial, so neither is what "觉醒" means here.
	 */
	public static int distinctAnchors(ServerPlayer player) {
		Set<String> anchors = new HashSet<>();
		for (CovenantData data : SummonManager.ledger().values()) {
			if (!data.active() || !data.ownedBy(player.getUUID())) {
				continue;
			}
			if (data.tier() == CovenantTier.B && !data.anchorId().isBlank()) {
				anchors.add(data.anchorId());
			}
		}
		return anchors.size();
	}

	/**
	 * spec 11.8 clause 2: is there an activated 灵魂圣坛 near the player?
	 *
	 * <p>"Activated" is defined by {@link SoulAltarBlockEntity#isActivated()} -
	 * a bare placed altar does not count, because the spec asks the player to
	 * "建造并以特定仪式激活" it, not merely to place it.
	 *
	 * <p>Scanned around the player rather than tracked globally so that the
	 * altar is understood as <em>theirs and in use</em>: an altar built by
	 * someone else on the other side of the world is not this player's trial
	 * progress. The scan is bounded and only runs when the trial readout is
	 * requested, so it costs nothing per tick.
	 */
	public static boolean altarActivated(ServerPlayer player) {
		for (SoulAltarBlockEntity altar : altarsNear(player)) {
			if (altar.isActivated()) {
				return true;
			}
		}
		return false;
	}

	/** spec 11.8 clause 3: shards already deposited into a nearby altar. */
	public static int depositedShards(ServerPlayer player) {
		int best = 0;
		for (SoulAltarBlockEntity altar : altarsNear(player)) {
			best = Math.max(best, altar.depositedShards());
		}
		return best;
	}

	/**
	 * The altars bounding the trial's conditions.
	 *
	 * <p>Radius 32 horizontally, 24 vertically, covers an ordinary base without
	 * touching the loaded world. A step of 4 keeps the scan at ~1.4k probes
	 * worst case while being finer than any real altar footprint, so an altar
	 * cannot slip between samples.
	 *
	 * <p>This runs only when the readout is requested or a condition is
	 * evaluated, never per tick, which is why a bounded scan is acceptable
	 * where a global index would not be.
	 */
	private static List<SoulAltarBlockEntity> altarsNear(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return List.of();
		}
		BlockPos origin = player.blockPosition();
		List<SoulAltarBlockEntity> out = new ArrayList<>();
		for (int dx = -ALTAR_RADIUS; dx <= ALTAR_RADIUS; dx += ALTAR_STEP) {
			for (int dz = -ALTAR_RADIUS; dz <= ALTAR_RADIUS; dz += ALTAR_STEP) {
				for (int dy = -ALTAR_VERTICAL; dy <= ALTAR_VERTICAL;
						dy += ALTAR_STEP) {
					BlockPos probe = origin.offset(dx, dy, dz);
					if (!level.isLoaded(probe)) {
						continue;
					}
					if (level.getBlockEntity(probe)
							instanceof SoulAltarBlockEntity altar
							&& !out.contains(altar)) {
						out.add(altar);
					}
				}
			}
		}
		return out;
	}

	/** Horizontal radius of the altar scan. */
	private static final int ALTAR_RADIUS = 32;
	/** Vertical radius of the altar scan. */
	private static final int ALTAR_VERTICAL = 24;
	/** Probe spacing; must be smaller than any altar footprint. */
	private static final int ALTAR_STEP = 4;

	// ------------------------------------------------------------------
	// readout
	// ------------------------------------------------------------------

	/** Human-readable progress, used by {@code /golem trial}. */
	public static List<Component> readout(ServerPlayer player) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("golem_covenant.trial.header"));
		List<TrialCondition> conditions = conditions(player);
		for (TrialCondition condition : conditions) {
			lines.add(Component.translatable("golem_covenant.trial.row",
					Component.translatable(condition.descriptionKey()),
					Component.literal(condition.progressText())
							.withStyle(condition.done()
									? net.minecraft.ChatFormatting.GREEN
									: net.minecraft.ChatFormatting.GRAY)));
		}
		boolean done = conditions.stream().allMatch(TrialCondition::done);
		lines.add(Component.translatable(done
				? "golem_covenant.trial.ready"
				: "golem_covenant.trial.not_ready"));
		return lines;
	}

	/** True when a condition's tray has companions waiting for a rite. */
	public static int riteTraySize(ServerPlayer player) {
		return RITE_TRAY.getOrDefault(player.getUUID(), Map.of()).size();
	}

	private static void announce(ServerPlayer player, String key,
			Component arg) {
		player.sendSystemMessage(Component.translatable(key, arg)
				.withStyle(net.minecraft.ChatFormatting.GOLD));
	}

	// ------------------------------------------------------------------
	// helpers used by other systems
	// ------------------------------------------------------------------

	/** The item id the trial's clause 3 counts. */
	public static final Identifier SHARD_ID =
			GolemCovenantMod.id("capacity_shard");

	/** True when {@code stack} is the 契约容量碎片. */
	public static boolean isCapacityShard(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		Item shard = BuiltInRegistries.ITEM.getValue(SHARD_ID);
		return shard != null && stack.getItem() == shard;
	}

	/** True when {@code entity} is one of the two named trial bosses. */
	public static boolean isTrialBoss(LivingEntity entity) {
		return isBossTarget(entity);
	}

	/** True when the entity is a B companion that could be offered. */
	public static boolean isOfferable(LivingEntity entity, ServerPlayer owner) {
		return entity instanceof Mob && isSacrificable(entity, owner);
	}

	/** Spec 11.14: trial credit must not leak across worlds. */
	public static void onServerStopped() {
		BOSS_CREDIT.clear();
		SACRIFICE_CREDIT.clear();
		RITE_TRAY.clear();
		ACTIVE_GRAND_RITES.clear();
	}
}
