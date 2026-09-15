package com.example.golem_covenant.trial;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

import com.example.golem_covenant.block.SoulAltarBlock;
import com.example.golem_covenant.block.entity.SoulAltarBlockEntity;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.summon.SummonManager;

/**
 * The five partner-task observers behind spec 4.3.2.
 *
 * <p>{@link SoulAltarBlockEntity} can store which tasks are done, and
 * {@link SoulAltarBlock#onTaskCompleted} can award the seat, but neither can
 * know that a village was protected. That knowledge only exists where the world
 * events happen, so this class is the missing link: it watches the world and
 * calls {@code onTaskCompleted} when it sees one of the five spec 4.3.2
 * behaviours.
 *
 * <table>
 *   <caption>spec 4.3.2 partner tasks and how each is observed</caption>
 *   <tr><th>task</th><th>observable definition</th></tr>
 *   <tr><td>{@code protect_villagers}</td>
 *       <td>a companion kills a hostile mob that was targeting a villager, or
 *           that died within 12 blocks of one</td></tr>
 *   <tr><td>{@code dangerous_delve}</td>
 *       <td>the owner is below y=0 with an active companion and is in a
 *           structure that is not the deep dark (which has its own trial
 *           clause)</td></tr>
 *   <tr><td>{@code defeat_enemy_type}</td>
 *       <td>a companion has been credited with kills of 3 distinct enemy
 *           entity types</td></tr>
 *   <tr><td>{@code assisted_kills}</td>
 *       <td>a companion is credited with 10 kills while the owner is within
 *           24 blocks - "累计协助玩家完成战斗"</td></tr>
 *   <tr><td>{@code soul_anchor_trial}</td>
 *       <td>a companion reaches anchor depth 3, i.e. Bond 75+ (spec 9.4)</td></tr>
 * </table>
 *
 * <h2>Why the altar matters to the observation</h2>
 *
 * <p>The task is credited to the <b>nearest activated altar</b> rather than to
 * a global player record. Spec 4.3.2 frames the seat as something the altar
 * gives, so progress has to live on a block the player built - otherwise the
 * altar would be decoration and the seat would come from nowhere. A player
 * without an altar simply makes no progress, which is the correct reading.
 */
public final class TaskObserver {

	private TaskObserver() {
	}

	/** How often the location-based tasks (delve) are evaluated. */
	private static final int INTERVAL = 40;

	/** Kill radius that counts as a companion "assisting" (spec 4.3.2). */
	private static final double ASSIST_RADIUS = 24.0;

	/** Villagers within this range make a kill a "village defence". */
	private static final double VILLAGE_RADIUS = 12.0;

	/** spec 4.3.2 "累计协助玩家完成战斗" - ten assisted kills. */
	public static final int ASSISTED_KILL_TARGET = 10;

	/** spec 4.3.2 "击败特定类型敌人" - three distinct enemy types. */
	public static final int ENEMY_TYPE_TARGET = 3;

	/** spec 4.3.2 "完成对应灵魂锚点试炼" - Bond 75+, i.e. depth 3. */
	public static final int ANCHOR_TRIAL_DEPTH = 3;

	/** player -> distinct enemy entity-type ids their companions have killed. */
	private static final Map<UUID, java.util.Set<String>> ENEMY_KINDS =
			new HashMap<>();

	/** player -> assisted kill count. */
	private static final Map<UUID, Integer> ASSIST_KILLS = new HashMap<>();

	/** player -> altar being credited, remembered to avoid rescanning. */
	private static final Map<UUID, BlockPos> BOUND_ALTAR = new HashMap<>();

	public static void register() {
		// spec 11.10.2: kill attribution is server-side and keyed on the owner.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity.level() instanceof ServerLevel level) {
				onDeath(level, entity, source);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % INTERVAL != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				tickPlayer(player);
			}
		});
	}

	// ------------------------------------------------------------------
	// kill-driven tasks
	// ------------------------------------------------------------------

	/**
	 * A mob died. Decide which of the kill-driven tasks it advances.
	 *
	 * <p>Attribution follows the same rule as {@code BondEngine.onKill}: the
	 * owner must be the killer, and a companion must be within
	 * {@link #ASSIST_RADIUS}. That keeps a companion someone parked at a
	 * farm from earning tasks for a player who is elsewhere.
	 */
	private static void onDeath(ServerLevel level, LivingEntity victim,
			net.minecraft.world.damagesource.DamageSource source) {
		if (!(victim instanceof Enemy)) {
			return;
		}
		if (!(source.getEntity() instanceof ServerPlayer owner)) {
			return;
		}
		List<LivingEntity> companions = companionsAssisting(owner, victim);
		if (companions.isEmpty()) {
			return;
		}
		// spec 4.3.2 "累计协助玩家完成战斗"
		int assisted = ASSIST_KILLS.merge(owner.getUUID(), 1, Integer::sum);
		if (assisted >= ASSISTED_KILL_TARGET) {
			credit(owner, "assisted_kills");
		}
		// spec 4.3.2 "击败特定类型敌人"
		var kinds = ENEMY_KINDS.computeIfAbsent(owner.getUUID(),
				k -> new java.util.HashSet<>());
		kinds.add(victim.getType().toString());
		if (kinds.size() >= ENEMY_TYPE_TARGET) {
			credit(owner, "defeat_enemy_type");
		}
		// spec 4.3.2 "保护村民"
		if (diedNearVillager(level, victim)) {
			credit(owner, "protect_villagers");
		}
	}

	/** Companions of {@code owner} close enough to have taken part. */
	private static List<LivingEntity> companionsAssisting(ServerPlayer owner,
			LivingEntity victim) {
		return SummonManager.ownedCompanionsOf(owner).stream()
				.filter(e -> e.distanceTo(victim) <= ASSIST_RADIUS)
				.toList();
	}

	/**
	 * Was this a village defence?
	 *
	 * <p>True when the mob died beside a villager. "Beside" rather than
	 * "targeting" because a hostile mob killed mid-swing at a villager has
	 * often already lost its target, and requiring the live target reference
	 * would make the task fire only on a perfect interception.
	 *
	 * <p>Searches {@code AbstractVillager} rather than the {@code Npc} marker
	 * interface: {@code Npc} does not extend {@code Entity}, so it cannot
	 * satisfy {@code getEntitiesOfClass}'s bound. Wandering traders count,
	 * which is right - they are as much a village as the villagers are.
	 */
	private static boolean diedNearVillager(ServerLevel level,
			LivingEntity victim) {
		return !level.getEntitiesOfClass(AbstractVillager.class,
				victim.getBoundingBox().inflate(VILLAGE_RADIUS)).isEmpty();
	}

	// ------------------------------------------------------------------
	// location-driven tasks
	// ------------------------------------------------------------------

	private static void tickPlayer(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		// spec 4.3.2 "完成危险深入的探索": below y=0 with a companion in tow.
		// The deep dark is excluded because spec 11.8 already pays for it as a
		// separate trial clause, and double-crediting one journey as two
		// achievements would make the trial shorter than the spec intends.
		if (player.getY() < 0.0 && hasCompanionWith(player)
				&& !level.getBiome(player.blockPosition())
						.is(net.minecraft.world.level.biome.Biomes.DEEP_DARK)) {
			credit(player, "dangerous_delve");
		}
		// spec 4.3.2 "完成对应灵魂锚点试炼": a companion bonded deep enough.
		for (LivingEntity companion : SummonManager.ownedCompanionsOf(player)) {
			if (com.example.golem_covenant.bond.BondEngine.allowedDepth(
					companion) >= ANCHOR_TRIAL_DEPTH) {
				credit(player, "soul_anchor_trial");
				break;
			}
		}
	}

	private static boolean hasCompanionWith(ServerPlayer player) {
		for (LivingEntity companion : SummonManager.ownedCompanionsOf(player)) {
			CovenantData data = companion.getAttached(ModAttachments.COVENANT);
			if (data != null && data.active() && data.tier() != CovenantTier.A) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// crediting
	// ------------------------------------------------------------------

	/**
	 * Credit {@code task} to the player's nearest activated altar.
	 *
	 * <p>Silently does nothing when there is no altar in range - the task is
	 * not banked for later, because spec 4.3.2 has the altar <em>be</em> the
	 * thing that hands out the seat.
	 */
	private static void credit(ServerPlayer player, String task) {
		SoulAltarBlockEntity altar = nearestActivatedAltar(player);
		if (altar == null) {
			return;
		}
		if (altar.isComplete(task)) {
			return;
		}
		SoulAltarBlock.onTaskCompleted(player, altar, task);
	}

	/**
	 * The closest activated altar, with a one-entry cache.
	 *
	 * <p>The scan is the same bounded probe as the trial's, so it is cheap but
	 * not free, and every credited task would otherwise repeat it. The cache is
	 * keyed on the position last found rather than on the player alone, so an
	 * altar that gets broken simply stops matching and the next call rescans.
	 */
	private static SoulAltarBlockEntity nearestActivatedAltar(
			ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return null;
		}
		BlockPos cached = BOUND_ALTAR.get(player.getUUID());
		if (cached != null && level.isLoaded(cached)
				&& level.getBlockEntity(cached)
						instanceof SoulAltarBlockEntity altar
				&& altar.isActivated()) {
			return altar;
		}
		BOUND_ALTAR.remove(player.getUUID());
		SoulAltarBlockEntity found = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos origin = player.blockPosition();
		final int radius = 32;
		for (int dx = -radius; dx <= radius; dx += 4) {
			for (int dz = -radius; dz <= radius; dz += 4) {
				for (int dy = -24; dy <= 24; dy += 4) {
					BlockPos probe = origin.offset(dx, dy, dz);
					if (!level.isLoaded(probe)
							|| !(level.getBlockEntity(probe)
									instanceof SoulAltarBlockEntity altar)
							|| !altar.isActivated()) {
						continue;
					}
					double distance = probe.distSqr(origin);
					if (distance < bestDistance) {
						bestDistance = distance;
						found = altar;
					}
				}
			}
		}
		if (found != null) {
			BOUND_ALTAR.put(player.getUUID(), found.getBlockPos());
		}
		return found;
	}

	/** Diagnostic counters for the {@code /golem altar} readout. */
	public static int assistedKills(UUID player) {
		return ASSIST_KILLS.getOrDefault(player, 0);
	}

	public static int enemyKinds(UUID player) {
		return ENEMY_KINDS.getOrDefault(player, java.util.Set.of()).size();
	}

	public static void onServerStopped() {
		ENEMY_KINDS.clear();
		ASSIST_KILLS.clear();
		BOUND_ALTAR.clear();
	}
}
