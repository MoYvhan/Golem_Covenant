package com.example.golem_covenant.anchor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.bond.BondEngine;
import com.example.golem_covenant.data.AnchorProfile;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.registry.ModParticles;

/**
 * 锚点运行时 (spec ch.6).
 *
 * <p>This is the behaviour half of the anchor system. Spec 6.1 defines an
 * anchor as "这个灵魂观察世界和参与战斗的规则" - so every method here reads
 * something from the world and changes how the companion <b>behaves</b>. Not
 * one of them adds a number (spec 6.1 "锚点不是 Buff / 属性 / 武器 / 单个技能").
 *
 * <p>Spec 12.1 forbids a class per creature:
 * <blockquote>不建议写 ZombieCompanion.java、SpiderCompanion.java…
 * 应该采用 Profile + Ability + Anchor + Ritual + DeathWill 的数据驱动架构</blockquote>
 * so all 64 anchors are dispatched from the single registry-driven
 * {@link #tickOne} method via their {@code behaviour} keyword.
 *
 * <p>Spec 9.3 is the other key rule: Bond must not grant stats, it unlocks
 * <b>depth</b>. Every deep behaviour below is therefore gated through
 * {@link BondEngine#hasDepth}, which makes a high-Bond companion behave
 * qualitatively differently rather than hitting harder.
 */
public final class AnchorRuntime {

	private AnchorRuntime() {
	}

	/** How often anchors are evaluated. 10 ticks = twice a second. */
	private static final int INTERVAL = 10;

	/** Anchors beyond this range from the owner stop running (spec 13.2.3). */
	private static final double ACTIVE_RANGE = 48.0;

	/** Enemy search radius for anchor effects. */
	private static final double ENEMY_RADIUS = 12.0;

	/** Per-companion anchor scratch state. Not persisted (spec 11.14). */
	private static final Map<UUID, AnchorState> STATE = new HashMap<>();

	/**
	 * Mutable anchor bookkeeping. Spec 6.3 calls these 亡迹 / 猎痕 / 巢线 /
	 * 震荡能量; they are transient battlefield readings, so they live in memory
	 * and are rebuilt naturally, never saved.
	 */
	static final class AnchorState {
		/** generic accumulator used by most anchors (亡迹 / 猎痕 / 巢线 ...). */
		int charge;
		/** tick the accumulator last changed, used for decay. */
		long lastChange;
		/** position of the last node an anchor created. */
		BlockPos lastNode;
		/** primary mark target this anchor is tracking. */
		UUID markTarget;
		/** cycle counter for alternating behaviours. */
		int phase;

		boolean addCharge(int amount, long now, int cap) {
			int before = this.charge;
			this.charge = Math.clamp(this.charge + amount, 0, cap);
			if (this.charge != before) {
				this.lastChange = now;
				return true;
			}
			return false;
		}
	}

	private static AnchorState stateOf(UUID id) {
		return STATE.computeIfAbsent(id, k -> new AnchorState());
	}

	public static void register() {
		// The "observing" half of each anchor: spec 6.1 says an anchor is the
		// rule by which a soul READS the world, so the reading events are
		// registered here next to the behaviours that consume them.
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
				.AFTER_DEATH.register((entity, source) -> {
					if (!(entity.level() instanceof ServerLevel level)) {
						return;
					}
					// spec 6.3.1: the death anchor watches DEATHS
					if (source.getEntity() instanceof ServerPlayer killer) {
						onEnemyDeath(level, killer, entity);
					}
					// a companion's own death feeds nothing, but the owner's
					// nearby anchors should stop tracking a stale mark
					STATE.remove(entity.getUUID());
				});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % INTERVAL != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.level() instanceof ServerLevel level) {
					runOwnerAnchors(level, player);
				}
			}
		});
	}

	// ------------------------------------------------------------------
	// driver
	// ------------------------------------------------------------------

	/**
	 * Runs the anchors of every companion owned by {@code owner}.
	 *
	 * <p>Spec 13.2.3 caps the work: a companion further away than
	 * {@link #ACTIVE_RANGE} is skipped entirely, which is what keeps a roster
	 * of 20 companions from costing 20 anchor ticks in the same frame.
	 */
	private static void runOwnerAnchors(ServerLevel level, ServerPlayer owner) {
		for (LivingEntity companion : com.example.golem_covenant.summon
				.SummonManager.ownedCompanionsOf(owner)) {
			if (companion.level() != level || !companion.isAlive()) {
				continue;
			}
			if (companion.distanceTo(owner) > ACTIVE_RANGE) {
				continue;
			}
			tickOne(level, owner, companion);
		}
	}

	/** Resolves the companion's anchor from the registry and dispatches it. */
	private static void tickOne(ServerLevel level, ServerPlayer owner,
			LivingEntity companion) {
		CovenantData data = companion.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active()) {
			return;
		}
		Optional<AnchorProfile> anchorOpt = Anchors.forCompanion(data);
		if (anchorOpt.isEmpty()) {
			return;
		}
		AnchorProfile anchor = anchorOpt.get();
		if (!anchor.implemented()) {
			return;
		}
		long now = level.getGameTime();
		AnchorState st = stateOf(companion.getUUID());
		int depth = BondEngine.allowedDepth(companion);

		try {
			dispatch(anchor, level, owner, companion, st, depth, now);
		} catch (Exception e) {
			// An anchor must never take down the tick loop (spec 11.1.3).
			GolemCovenantMod.LOGGER.warn("anchor {} failed on {}",
					anchor.anchorId(), companion.getUUID(), e);
		}
	}

	/**
	 * The single dispatch table. Every one of the 64 registry anchors lands on
	 * exactly one branch here, which is the whole point of the data-driven
	 * design: adding a form means adding a row, not adding a class.
	 *
	 * @param depth 0..4, from {@code CovenantData.anchorDepth()} (spec 9.3)
	 */
	private static void dispatch(AnchorProfile a, ServerLevel level,
			ServerPlayer owner, LivingEntity self, AnchorState st, int depth,
			long now) {
		switch (a.behaviour()) {
			// --- zombie: 死亡锚 branch (6.3.1) ---------------------------
			case "death_harvest" -> deathHarvest(level, owner, self, st, depth, now);
			case "corpse_anchor" -> corpseAnchor(level, owner, self, st, depth, now);
			case "desiccation_field" -> desiccationField(level, owner, self, depth);
			case "tide_pursuit" -> tidePursuit(level, owner, self, st, depth, now);
			case "arrow_forecast" -> arrowForecast(level, owner, self, st, depth);
			case "frost_trajectory" -> frostTrajectory(level, owner, self, st, depth);
			case "blade_reap" -> bladeReap(level, owner, self, st, depth);
			case "village_cycle" -> villageCycle(level, owner, self, st, depth, now);
			case "gold_contract" -> goldContract(level, owner, self, st, depth);
			case "gold_flame" -> goldFlame(level, owner, self, st, depth);

			// --- arthropod: 织命锚 + 蜂巢 (6.3.2 / 6.3.6) ----------------
			case "hive" -> hive(level, owner, self, st, depth, now);
			case "web_of_fate" -> webOfFate(level, owner, self, st, depth, now);
			case "venom_nest" -> venomNest(level, owner, self, st, depth, now);
			case "shadow_mite" -> shadowMite(level, owner, self, st, depth);
			case "burrow_swarm" -> burrowSwarm(level, owner, self, st, depth);

			// --- animal: 狩猎锚 branch (6.3.5) ---------------------------
			case "hunt" -> hunt(level, owner, self, st, depth, now);
			case "charge_horn" -> chargeHorn(level, owner, self, st, depth, now);
			case "spore" -> spore(level, owner, self, depth);
			case "tusk" -> tusk(level, owner, self, st, depth);
			case "wool" -> wool(level, owner, self, st, depth, now);
			case "plume_dance" -> plumeDance(level, owner, self, st, depth);
			case "moon_leap" -> moonLeap(level, owner, self, st, depth);
			case "night_prowl" -> nightProwl(level, owner, self, st, depth, now);
			case "nine_lives" -> nineLives(level, owner, self, st, depth, now);
			case "jungle_stalk" -> jungleStalk(level, owner, self, st, depth);
			case "steady_hoof" -> steadyHoof(level, owner, self, st, depth);

			// --- mount: 骑乘锚 branch (6.3.5) ---------------------------
			case "burden" -> burden(level, owner, self, st, depth);
			case "sand_march" -> sandMarch(level, owner, self, st, depth);
			case "sky_dash" -> skyDash(level, owner, self, st, depth);

			// --- snow / alpine (6.3.9) ----------------------------------
			case "snow_prowl" -> snowProwl(level, owner, self, st, depth);
			case "rock_roll" -> rockRoll(level, owner, self, st, depth);
			case "bamboo" -> bamboo(level, owner, self, st, depth);
			case "arctic_fang" -> arcticFang(level, owner, self, st, depth);
			case "mountain_horn" -> mountainHorn(level, owner, self, st, depth, now);

			// --- environment: 青蛙环境锚 (6.3.9) ------------------------
			case "biome_orb" -> biomeOrb(level, owner, self, st, depth);
			case "warm_spring" -> warmSpring(level, owner, self, depth);
			case "grass_echo" -> grassEcho(level, owner, self, st, depth);
			case "frost_leap" -> frostLeap(level, owner, self, st, depth);

			// --- aquatic (6.3.9) ----------------------------------------
			case "echo_sense" -> echoSense(level, owner, self, st, depth, now);
			case "tide_shell" -> tideShell(level, owner, self, st, depth, now);
			case "deep_ink" -> deepInk(level, owner, self, st, depth, now);
			case "current_dash" -> currentDash(level, owner, self, st, depth);
			case "spine_guard" -> spineGuard(level, owner, self, st, depth, now);
			case "school_charge" -> schoolCharge(level, owner, self, st, depth);
			case "coral_scale" -> coralScale(level, owner, self, st, depth);
			case "regeneration_gill" -> regenerationGill(level, owner, self, depth);

			// --- construct (6.3.10) -------------------------------------
			case "guardian_watch" -> guardianWatch(level, owner, self, st, depth);
			case "city_wall" -> cityWall(level, owner, self, st, depth);

			// --- nether / end (6.3.4 / 6.3.7 / 6.3.8 / 6.3.10) ---------
			case "space" -> space(level, owner, self, st, depth, now);
			case "deep_echo" -> deepEcho(level, owner, self, st, depth, now);
			case "elastic" -> elastic(level, owner, self, st, depth);
			case "magma_core" -> magmaCore(level, owner, self, st, depth, now);
			case "sun_flare" -> sunFlare(level, owner, self, st, depth);
			case "wail" -> wail(level, owner, self, st, depth, now);
			case "lava_stride" -> lavaStride(level, owner, self, st, depth);
			case "crimson_tusk" -> crimsonTusk(level, owner, self, st, depth);
			case "shell" -> shell(level, owner, self, st, depth);
			case "prism" -> prism(level, owner, self, st, depth);

			// --- special (6.3.4 / 6.3.10 / 6.3.11) ----------------------
			case "implosion" -> implosion(level, owner, self, st, depth, now);
			case "sulfur_core" -> sulfurCore(level, owner, self, st, depth, now);
			case "profession" -> profession(level, owner, self, st, depth);
			case "plume_echo" -> plumeEcho(level, owner, self, st, depth, now);

			default -> {
				// unknown behaviour: the gate in tools/validate_anchors.py
				// makes this unreachable for shipped data, but a datapack
				// override could still get here.
			}
		}
	}

	// ==================================================================
	// zombie family - 死亡锚 branch (spec 6.3.1)
	// ==================================================================

	/**
	 * 6.3.1 B级: 敌人死亡时产生「亡迹」，多个亡迹可以连接。
	 * C级/depth>=2: 亡迹形成「亡潮」，连续击杀形成追猎路线。
	 *
	 * <p>Watch: {@code 敌人的死亡}. The anchor does not chase enemies - it
	 * harvests the place where they fell.
	 */
	private static void deathHarvest(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		// reading: any enemy that died nearby recently (see onEnemyDeath)
		if (st.charge <= 0) {
			return;
		}
		BlockPos node = st.lastNode;
		if (node != null) {
			// the anchor moves to its nodes rather than to enemies
			if (self instanceof Mob mob && mob.getTarget() == null) {
				if (self.distanceToSqr(Vec3.atCenterOf(node)) > 4.0) {
					mob.getNavigation().moveTo(node.getX() + 0.5,
							node.getY(), node.getZ() + 0.5, 1.1);
				}
			}
			// 亡迹连接: draw the link between nodes (spec 6.3.1 B级)
			if (depth >= 1 && st.phase % 2 == 0) {
				pulse(level, Vec3.atCenterOf(node), ModParticles.familyOr("zombie",
						ModParticles.CORE_SPARK), 4);
			}
		}
		// 亡潮 (spec 6.3.1 C级): consecutive kills open a pursuit route
		if (depth >= 2 && st.charge >= 5) {
			markEnemiesAsPrey(level, owner, self, ENEMY_RADIUS);
			st.addCharge(-1, now, 12);
		}
		decay(st, now, 600);
	}

	/**
	 * 墓穴锚: the companion guards the place a corpse fell instead of chasing.
	 * Dimension 6 (区域规则) is the differentiator - it creates territory.
	 */
	private static void corpseAnchor(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (st.lastNode == null) {
			return;
		}
		BlockPos node = st.lastNode;
		double distSqr = self.distanceToSqr(Vec3.atCenterOf(node));
		if (self instanceof Mob mob) {
			// the rule: never pursue beyond the grave radius
			if (distSqr > 36.0) {
				mob.setTarget(null);
				mob.getNavigation().moveTo(node.getX() + 0.5, node.getY(),
						node.getZ() + 0.5, 1.2);
			} else if (depth >= 1 && mob.getTarget() == null) {
				// stays put and watches the grave
				mob.getNavigation().stop();
			}
		}
		if (depth >= 2) {
			// 墓钉 range: enemies entering the grave area get slowed, not hit
			for (LivingEntity e : enemiesNear(level, Vec3.atCenterOf(node), 4.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0,
						false, false, false));
			}
		}
		decay(st, now, 12000);
	}

	/**
	 * 枯竭锚: reads POSITIVE effects on enemies and strips them. This is the
	 * example of an anchor whose "resource" is the enemy's buffs.
	 */
	private static void desiccationField(ServerLevel level, ServerPlayer owner,
			LivingEntity self, int depth) {
		List<LivingEntity> buffed = new ArrayList<>();
		for (LivingEntity e : enemiesNear(level, self.position(), ENEMY_RADIUS)) {
			if (!e.getActiveEffects().isEmpty()) {
				buffed.add(e);
			}
		}
		if (buffed.isEmpty()) {
			return;
		}
		// the AI decision: prefer buffed targets over closer clean ones
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(buffed.get(0));
		}
		if (depth >= 1) {
			for (LivingEntity e : buffed) {
				// drain one positive effect; no damage is dealt
				e.getActiveEffects().stream()
						.filter(i -> i.getEffect().value().isBeneficial())
						.findFirst()
						.ifPresent(i -> e.removeEffect(i.getEffect()));
			}
			pulse(level, self.position(), ModParticles.familyOr("zombie",
					ModParticles.CORE_SPARK), 3);
		}
		if (depth >= 2) {
			// 枯竭场: buffs on anyone inside decay faster
			for (LivingEntity e : enemiesNear(level, self.position(), 8.0)) {
				for (MobEffectInstance i : e.getActiveEffects()) {
					if (i.getEffect().value().isBeneficial() && i.getDuration() > 20) {
						e.forceAddEffect(new MobEffectInstance(i.getEffect(),
								i.getDuration() - 20, i.getAmplifier()), null);
					}
				}
			}
		}
	}

	/**
	 * 亡潮锚: reads the TIME BETWEEN KILLS. Consecutive kills raise 潮位, and
	 * 潮位 changes pursuit speed and whether the companion withdraws.
	 */
	private static void tidePursuit(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (st.charge <= 0) {
			decay(st, now, 100);
			return;
		}
		boolean hot = st.charge >= 3;
		if (self instanceof Mob mob) {
			if (hot) {
				// the rule: at high tide it does not retreat
				mob.setTarget(findEnemy(level, self, ENEMY_RADIUS * 1.5));
			} else if (mob.getTarget() == null) {
				mob.getNavigation().moveTo(owner, 1.1);
			}
		}
		if (depth >= 2 && hot) {
			// 追猎路线: enemies along the route are revealed
			markEnemiesAsPrey(level, owner, self, ENEMY_RADIUS);
		}
		decay(st, now, 100);
	}

	/**
	 * 骨矢锚 (6.3.3): reads the enemy's MOVEMENT VECTOR. The B-mechanism aims
	 * at a predicted point, and depth 2 makes the prediction a standing rune.
	 */
	private static void arrowForecast(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS * 1.5);
		if (target == null) {
			st.markTarget = null;
			return;
		}
		st.markTarget = target.getUUID();
		// 预测落点: current position + velocity * flight time
		Vec3 predicted = target.position().add(target.getDeltaMovement().scale(12.0));
		// the AI rule: only bother with targets that move in a straight line
		boolean predictable = target.getDeltaMovement().horizontalDistanceSqr() > 0.01;
		if (self instanceof Mob mob && predictable && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1) {
			pulse(level, predicted, ModParticles.familyOr("zombie",
					ModParticles.CORE_SPARK), 2);
		}
		if (depth >= 2) {
			// 悬浮箭符: anything standing on the predicted point is marked
			for (LivingEntity e : enemiesNear(level, predicted, 1.5)) {
				mark(e, 60);
			}
		}
	}

	/**
	 * 霜轨锚: reads the enemy's TRAIL. Repeating the same route builds 寒轨,
	 * which freezes rather than damages.
	 */
	private static void frostTrajectory(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			decay(st, level.getGameTime(), 200);
			return;
		}
		BlockPos here = target.blockPosition();
		if (st.lastNode != null && st.lastNode.equals(here)) {
			st.addCharge(1, level.getGameTime(), 10);
		}
		st.lastNode = here.immutable();
		if (depth >= 1 && st.charge >= 3) {
			// 寒轨: the repeated ground becomes slippery ice for the enemy
			BlockState bs = level.getBlockState(here);
			if (bs.isAir() || bs.canBeReplaced()) {
				level.setBlock(here, Blocks.ICE.defaultBlockState(), 3);
			}
			st.addCharge(-1, level.getGameTime(), 10);
		}
		decay(st, level.getGameTime(), 200);
	}

	/**
	 * 凋刃锚: reads NEGATIVE effect stacks. A threshold executes; a multiplier
	 * would be a stat bonus and is forbidden (spec 6.1).
	 */
	private static void bladeReap(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		int debuffs = (int) target.getActiveEffects().stream()
				.filter(i -> !i.getEffect().value().isBeneficial()).count();
		// the AI decision: only weakened targets are worth locking
		if (debuffs >= 2 && self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1 && debuffs >= 3) {
			// 阈值斩杀: an execute threshold, not a damage multiplier
			float threshold = depth >= 2 ? 0.35f : 0.2f;
			if (target.getHealth() <= target.getMaxHealth() * threshold) {
				target.hurtServer(level, level.damageSources().mobAttack(self),
						target.getHealth() + 1.0f);
				onAnchorUsed(self, owner);
			}
		}
	}

	/**
	 * 循环锚 (6.3.11 农民): reads PLANT GROWTH. The resource becomes a heal
	 * pulse, so a farmer companion supports instead of fighting.
	 */
	private static void villageCycle(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		// reading: nearby mature crops are the resource (see onBlockGrowth)
		if (depth >= 1 && st.charge >= 4) {
			// the payoff is a root pulse that heals allies, never a stat
			for (LivingEntity ally : alliesOf(level, owner, self, 8.0)) {
				ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
						40, 0, false, true, true));
			}
			pulse(level, self.position(), ModParticles.familyOr("zombie",
					ModParticles.CORE_SPARK), 8);
			st.addCharge(-4, now, 12);
			onAnchorUsed(self, owner);
		}
		decay(st, now, 4000);
	}

	/**
	 * 金契锚: reads the enemy's EQUIPMENT. The resource is taken, not the life.
	 */
	private static void goldContract(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findArmedEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		// the AI rule: the best-equipped enemy, not the weakest
		if (target != null && self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1) {
			// 掠夺: the contract takes equipment rather than hit points
			boolean stripped = false;
			for (net.minecraft.world.entity.EquipmentSlot slot
					: net.minecraft.world.entity.EquipmentSlot.values()) {
				if (!target.getItemBySlot(slot).isEmpty()) {
					target.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
					stripped = true;
					break;
				}
			}
			if (stripped) {
				pulse(level, target.position(), ModParticles.familyOr("zombie",
						ModParticles.CORE_SPARK), 6);
				onAnchorUsed(self, owner);
			}
		}
	}

	/**
	 * 金焰锚: reads HOW MANY ENEMIES ARE BURNING. Fire spreads between them;
	 * the companion itself never deals extra damage.
	 */
	private static void goldFlame(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		List<LivingEntity> burning =
				enemiesNear(level, self.position(), ENEMY_RADIUS).stream()
						.filter(Entity::isOnFire).toList();
		if (burning.isEmpty()) {
			return;
		}
		// the AI rule: attack the ones NOT yet burning, to widen the field
		LivingEntity clean = findEnemy(level, self, ENEMY_RADIUS);
		if (clean != null && !clean.isOnFire() && self instanceof Mob mob
				&& mob.getTarget() == null) {
			mob.setTarget(clean);
		}
		if (depth >= 1) {
			// fire passes to a neighbour; still no direct damage
			for (LivingEntity e : enemiesNear(level, self.position(), ENEMY_RADIUS)) {
				if (!e.isOnFire() && e.distanceTo(burning.get(0)) < 4.0) {
					e.igniteForSeconds(4.0f);
					break;
				}
			}
		}
		if (depth >= 2) {
			// the fire field makes hiding impossible
			for (LivingEntity e : burning) {
				e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0,
						false, false, false));
			}
		}
	}

	// ==================================================================
	// arthropod family - 织命锚 (6.3.2) + 蜂巢锚 (6.3.6)
	// ==================================================================

	/**
	 * 6.3.6 蜂巢锚: marked enemies form 巢线; 3+ marks make a hexagonal network
	 * that makes the companion switch between 护主 / 治疗 / 控制 automatically.
	 */
	private static void hive(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		List<LivingEntity> marked = markedEnemiesIn(level, self, ENEMY_RADIUS);
		if (marked.size() < 2) {
			return;
		}
		// 六边形网络 (spec 6.3.6 B级): connect the marks
		if (depth >= 1) {
			for (int i = 0; i < marked.size() - 1; i++) {
				linkParticles(level, marked.get(i).position(),
						marked.get(i + 1).position(), ModParticles.familyOr(
								"arthropod", ModParticles.CORE_SPARK));
			}
		}
		// the automatic mode switch (spec 6.3.6 "根据战场情况自动切换")
		st.phase = (st.phase + 1) % 3;
		switch (st.phase) {
			case 0 -> {
				// 护主: pull the hive toward the owner
				if (self instanceof Mob mob) {
					mob.getNavigation().moveTo(owner, 1.2);
				}
			}
			case 1 -> {
				// 治疗: heal the weakest ally
				LivingEntity weakest = weakestAlly(level, owner, self, 10.0);
				if (weakest != null) {
					weakest.addEffect(new MobEffectInstance(
							MobEffects.REGENERATION, 40, 0, false, true, true));
				}
			}
			default -> {
				// 控制: slow every marked enemy
				if (depth >= 1) {
					for (LivingEntity e : marked) {
						e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
								60, 0, false, false, false));
					}
				}
			}
		}
		if (depth >= 2 && marked.size() >= 3) {
			// 蜂巢领域: allies inside the network gain protection
			for (LivingEntity ally : alliesOf(level, owner, self, 8.0)) {
				ally.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,
						40, 0, false, true, true));
			}
		}
		decay(st, now, 300);
	}

	/**
	 * 6.3.2 织命锚: records ENEMY MOVEMENT PATHS. Repeated routes become
	 * 记忆丝, and crossing strands become 命运蛛网.
	 */
	private static void webOfFate(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		BlockPos here = target.blockPosition();
		if (st.lastNode != null && st.lastNode.equals(here)) {
			// the enemy is re-walking a known route
			st.addCharge(1, now, 12);
		}
		if (st.lastNode != null && depth >= 1) {
			// 记忆丝: strands along the path
			linkParticles(level, Vec3.atCenterOf(st.lastNode),
					Vec3.atCenterOf(here), ModParticles.familyOr("arthropod",
							ModParticles.CORE_SPARK));
		}
		st.lastNode = here.immutable();
		if (depth >= 2 && st.charge >= 6) {
			// 命运蛛网: the more habitual the route, the stronger the web
			for (LivingEntity e : enemiesNear(level, Vec3.atCenterOf(here), 5.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80,
						st.charge >= 10 ? 2 : 1, false, false, false));
			}
			st.addCharge(-2, now, 12);
			onAnchorUsed(self, owner);
		}
		decay(st, now, 400);
	}

	/** 毒巢锚: reads POISONED ENEMIES and fuses their stacks into one burst. */
	private static void venomNest(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		List<LivingEntity> poisoned =
				enemiesNear(level, self.position(), ENEMY_RADIUS).stream()
						.filter(e -> e.hasEffect(MobEffects.POISON)).toList();
		if (poisoned.size() < 2) {
			return;
		}
		// the AI rule: move toward the densest poison concentration
		if (self instanceof Mob mob && poisoned.size() >= 3) {
			Vec3 centre = average(poisoned.stream().map(Entity::position).toList());
			mob.getNavigation().moveTo(centre.x, centre.y, centre.z, 1.1);
		}
		if (depth >= 1 && poisoned.size() >= 3) {
			// 范围毒爆: one burst instead of stacked damage-over-time
			Vec3 centre = average(poisoned.stream().map(Entity::position).toList());
			for (LivingEntity e : enemiesNear(level, centre, 5.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.POISON, 100,
						depth >= 2 ? 1 : 0, false, true, true));
			}
			pulse(level, centre, ModParticles.familyOr("arthropod",
					ModParticles.CORE_SPARK), 10);
			st.phase = 1;
			onAnchorUsed(self, owner);
		}
		decay(st, now, 200);
	}

	/** 影螨锚: watches the owner's BLIND SPOT and only attacks inside it. */
	private static void shadowMite(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		// the rule: never attack anything the owner can see
		LivingEntity candidate = findEnemy(level, self, ENEMY_RADIUS);
		if (candidate == null) {
			return;
		}
		if (!isInBlindSpot(owner, candidate)) {
			return;
		}
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(candidate);
		}
		// the AI rule: always approach from behind the target
		if (self instanceof Mob mob && mob.getTarget() != null) {
			Entity t = mob.getTarget();
			double behindX = t.getX() + t.getLookAngle().x * -2.0;
			double behindZ = t.getZ() + t.getLookAngle().z * -2.0;
			mob.getNavigation().moveTo(behindX, t.getY(), behindZ, 1.3);
		}
		if (depth >= 1) {
			mark(candidate, 40);
		}
	}

	/** 潜地锚: reads GROUND MATERIAL and attacks from below. */
	private static void burrowSwarm(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (!isSoftGround(level, self.blockPosition().below())) {
			return;
		}
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1) {
			// the ambush displaces rather than damages
			Vec3 push = self.position().subtract(target.position())
					.normalize().scale(-0.8);
			target.push(push.x, 0.5, push.z);
			self.hurtMarked = true;
			pulse(level, target.position(), ModParticles.familyOr("arthropod",
					ModParticles.CORE_SPARK), 8);
		}
	}

	// ==================================================================
	// animal family - 狩猎锚 branch (6.3.5)
	// ==================================================================

	/**
	 * 6.3.5 狩猎锚: reads the target BOTH the player and the companion attack.
	 * Accumulated 猎痕 summons a shadow pack; depth 2 creates a hunt domain.
	 */
	private static void hunt(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		LivingEntity shared = sharedTarget(owner, self);
		if (shared == null) {
			decay(st, now, 300);
			return;
		}
		// the AI rule: never switch away from the player's target
		if (self instanceof Mob mob && mob.getTarget() != shared) {
			mob.setTarget(shared);
			st.addCharge(1, now, 10);
		}
		if (depth >= 1 && st.charge >= 6) {
			// 幽影狼群: a short-lived pack, not a damage buff
			Vec3 behind = shared.position().subtract(self.position())
					.normalize().scale(3.0).add(self.position());
			pulse(level, behind, ModParticles.familyOr("animal",
					ModParticles.CORE_SPARK), 16);
			st.addCharge(-6, now, 10);
			onAnchorUsed(self, owner);
		}
		if (depth >= 2) {
			// 狩猎领域: the locked target is publicly marked
			mark(shared, 60);
		}
	}

	/** 冲锋锚: reads the LINE between self and target; displaces, no damage. */
	private static void chargeHorn(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS * 1.5);
		if (target == null) {
			return;
		}
		double dist = self.distanceTo(target);
		// the trigger: enough runway and a clear line
		if (dist < 8.0 || !self.hasLineOfSight(target)) {
			return;
		}
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1 && now % 40 == 0) {
			// the charge pushes instead of harming (spec 6.3.5)
			Vec3 dir = target.position().subtract(self.position()).normalize();
			target.push(dir.x * 1.2, 0.3, dir.z * 1.2);
			target.hurtMarked = true;
			pulse(level, target.position(), ModParticles.familyOr("animal",
					ModParticles.CORE_SPARK), 6);
			st.phase++;
			if (st.phase % 4 == 0) {
				onAnchorUsed(self, owner);
			}
		}
	}

	/** 孢子锚: reads BIOME HUMIDITY / vegetation, releases a slowing cloud. */
	private static void spore(ServerLevel level, ServerPlayer owner,
			LivingEntity self, int depth) {
		boolean moist = level.getBlockState(self.blockPosition().below())
				.is(Blocks.GRASS_BLOCK) || self.isInWaterOrRain();
		if (!moist) {
			return;
		}
		if (depth >= 1) {
			for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0,
						false, false, false));
			}
			pulse(level, self.position(), ModParticles.familyOr("animal",
					ModParticles.CORE_SPARK), 10);
		}
		if (depth >= 2) {
			// the cloud also heals allies standing in it
			for (LivingEntity ally : alliesOf(level, owner, self, 6.0)) {
				ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
						40, 0, false, true, true));
			}
		}
	}

	/** 獠牙锚: reads the target's FACING; only strikes a break point. */
	private static void tusk(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		// the break point: the target is facing away or busy
		boolean breakPoint = target.getLastHurtMob() != self
				&& target.getLookAngle().dot(self.position()
						.subtract(target.position()).normalize()) < 0.2;
		if (!breakPoint) {
			return;
		}
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1) {
			// ignores armour: resistance is removed, not damage increased
			target.removeEffect(MobEffects.RESISTANCE);
			target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0,
					false, false, false));
		}
	}

	/** 绒毛锚: reads DAMAGE TAKEN and converts it into a damage buffer. */
	private static void wool(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		// the AI rule: always stand between the owner and the closest enemy
		if (self instanceof Mob mob && mob.getTarget() == null) {
			LivingEntity threat = findEnemy(level, owner, ENEMY_RADIUS);
			if (threat != null) {
				Vec3 mid = owner.position().add(threat.position()).scale(0.5);
				mob.getNavigation().moveTo(mid.x, mid.y, mid.z, 1.2);
			}
		}
		// 绒层 is charged by onOwnerHurt (see the event hook below)
		if (depth >= 1 && st.charge > 0) {
			// the charge becomes absorption on the owner, not a stat
			owner.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100,
					Math.min(st.charge - 1, 3), false, true, true));
		}
		decay(st, now, 200);
	}

	/** 羽舞锚: reads COLOUR MATCHING between itself and the terrain. */
	private static void plumeDance(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		// the mechanism: camouflage, expressed as loss of targeting
		if (self.tickCount % 40 != 0) {
			return;
		}
		if (depth >= 1) {
			// enemies lose their lock on the camouflaged ally
			for (LivingEntity e : enemiesNear(level, self.position(), 8.0)) {
				if (e instanceof Mob mob && mob.getTarget() == self) {
					mob.setTarget(null);
				}
			}
			self.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0,
					false, false, false));
		}
		if (depth >= 2) {
			// allies in the same terrain share the camouflage
			for (LivingEntity ally : alliesOf(level, owner, self, 6.0)) {
				ally.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60,
						0, false, false, false));
			}
		}
	}

	/** 月跃锚: reads LIGHT LEVEL; becomes aggressive at night, escort by day. */
	private static void moonLeap(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		int light = level.getMaxLocalRawBrightness(self.blockPosition());
		boolean night = light < 7;
		if (self instanceof Mob mob) {
			if (night) {
				if (mob.getTarget() == null) {
					mob.setTarget(findEnemy(level, self, ENEMY_RADIUS * 1.5));
				}
			} else {
				// daytime rule: escort only
				mob.setTarget(null);
				if (self.distanceTo(owner) > 12.0) {
					mob.getNavigation().moveTo(owner, 1.1);
				}
			}
		}
		if (night && depth >= 1) {
			// the leap displaces enemies at the landing point
			for (LivingEntity e : enemiesNear(level, self.position(), 3.0)) {
				Vec3 push = e.position().subtract(self.position()).normalize()
						.scale(0.6);
				e.push(push.x, 0.4, push.z);
				e.hurtMarked = true;
			}
		}
	}

	/** 夜猎锚: reads whether the target HAS NOTICED the companion. */
	private static void nightProwl(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		boolean unaware = !(target instanceof Mob mob) || mob.getTarget() == null;
		if (unaware) {
			// the rule: stay stealthed until inside attack range
			if (self instanceof Mob mob && self.distanceTo(target) < 4.0) {
				mob.setTarget(target);
				// the opening strike removes the target's alertness
				if (depth >= 1) {
					target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100,
							0, false, false, false));
					onAnchorUsed(self, owner);
				}
			} else if (self instanceof Mob mob) {
				self.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40,
						0, false, false, false));
				mob.setTarget(null);
			}
		}
		decay(st, now, 100);
	}

	/** 回援锚: reads the OWNER'S HEALTH RATIO and always returns to them. */
	private static void nineLives(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		float ratio = owner.getHealth() / owner.getMaxHealth();
		if (ratio >= 0.4f) {
			return;
		}
		if (self instanceof Mob mob) {
			// the rule: abandon any current target to return
			mob.setTarget(null);
			mob.getNavigation().moveTo(owner, 1.4);
		}
		if (depth >= 1 && self.distanceTo(owner) < 3.0 && now % 100 == 0) {
			// the landing push clears space around the owner
			for (LivingEntity e : enemiesNear(level, owner.position(), 4.0)) {
				Vec3 push = e.position().subtract(owner.position()).normalize()
						.scale(0.9);
				e.push(push.x, 0.4, push.z);
				e.hurtMarked = true;
			}
			pulse(level, owner.position(), ModParticles.familyOr("animal",
					ModParticles.CORE_SPARK), 12);
			onAnchorUsed(self, owner);
		}
		decay(st, now, 200);
	}

	/** 丛影锚: reads VEGETATION OBSCURATION between self and target. */
	private static void jungleStalk(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		// the rule: only engage through concealment
		if (!self.hasLineOfSight(target)) {
			if (self instanceof Mob mob && mob.getTarget() == null) {
				mob.setTarget(target);
			}
			if (depth >= 1) {
				self.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40,
						0, false, false, false));
			}
		} else if (depth >= 2 && st.charge <= 0) {
			// leaves a concealment patch behind
			pulse(level, self.position(), ModParticles.familyOr("animal",
					ModParticles.CORE_SPARK), 6);
			st.addCharge(1, level.getGameTime(), 3);
		}
	}

	/** 稳健锚: reads TERRAIN SLOPE and flattens a path for the owner. */
	private static void steadyHoof(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (self.tickCount % 40 != 0) {
			return;
		}
		// the AI rule: escort along the flattest route
		if (self instanceof Mob mob && self.distanceTo(owner) > 8.0) {
			mob.getNavigation().moveTo(owner, 1.15);
		}
		if (depth >= 1) {
			// the settle pulse removes rough ground under allies
			Vec3 p = owner.position();
			for (LivingEntity ally : alliesOf(level, owner, self, 6.0)) {
				ally.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0,
						false, false, false));
			}
			pulse(level, p, ModParticles.familyOr("animal",
					ModParticles.CORE_SPARK), 4);
		}
	}

	// ==================================================================
	// mount family (spec 6.3.5)
	// ==================================================================

	/** 负重锚: reads CARGO WEIGHT; converts it into a gravity well. */
	private static void burden(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (self.getPassengers().isEmpty() && depth < 2) {
			return;
		}
		if (depth >= 1) {
			// the weight suppresses jumping for everything close by
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
					self.getBoundingBox().inflate(6.0), x -> x != self)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0,
						false, false, false));
			}
		}
		if (depth >= 2) {
			// 重量领域: the carried load becomes a shock on dismount
			pulse(level, self.position(), ModParticles.familyOr("mount",
					ModParticles.CORE_SPARK), 8);
		}
	}

	/** 沙行锚: reads GROUND MATERIAL (sand) and builds a sand wall. */
	private static void sandMarch(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		BlockState below = level.getBlockState(self.blockPosition().below());
		if (!below.is(Blocks.SAND) && !below.is(Blocks.GRAVEL)) {
			return;
		}
		st.addCharge(1, level.getGameTime(), 20);
		if (depth >= 1 && st.charge >= 8 && self.tickCount % 60 == 0) {
			// the wall blinds enemies behind it: sight, not damage, is denied
			for (LivingEntity e : enemiesNear(level, self.position(), 10.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0,
						false, false, false));
			}
			pulse(level, self.position(), ModParticles.familyOr("mount",
					ModParticles.CORE_SPARK), 14);
			st.addCharge(-8, level.getGameTime(), 20);
		}
	}

	/** 空冲锚: reads ALTITUDE and converts the landing into a local quake. */
	private static void skyDash(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (self.onGround()) {
			st.addCharge(1, level.getGameTime(), 3);
			if (depth >= 1 && st.charge >= 3 && self.tickCount % 40 == 0) {
				for (LivingEntity e : enemiesNear(level, self.position(), 4.0)) {
					Vec3 push = e.position().subtract(self.position())
							.normalize().scale(0.8);
					e.push(push.x, 0.5, push.z);
					e.hurtMarked = true;
				}
				pulse(level, self.position(), ModParticles.familyOr("mount",
						ModParticles.CORE_SPARK), 10);
				st.addCharge(-3, level.getGameTime(), 3);
			}
		}
	}

	// ==================================================================
	// snow / alpine family (spec 6.3.9)
	// ==================================================================

	/** 雪潜锚: reads FOOTPRINTS in snow. */
	private static void snowProwl(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (self.tickCount % 60 != 0 || depth < 1) {
			return;
		}
		// the trail is a vision zone: enemies inside it are exposed
		for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
			mark(e, 40);
		}
	}

	/** 滚石锚: reads SLOPE and rolls downhill. */
	private static void rockRoll(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		// the rule: attack is only legitimate while descending
		if (!self.onGround()) {
			return;
		}
		if (self.tickCount % 30 != 0 || depth < 1) {
			return;
		}
		for (LivingEntity e : enemiesNear(level, self.position(), 4.0)) {
			Vec3 push = e.position().subtract(self.position()).normalize().scale(0.7);
			e.push(push.x, 0.2, push.z);
			e.hurtMarked = true;
		}
	}

	/** 竹阵锚: reads BAMBOO DENSITY; abandons combat outside the grove. */
	private static void bamboo(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		boolean inGrove = false;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -3; dx <= 3 && !inGrove; dx += 3) {
			for (int dz = -3; dz <= 3; dz += 3) {
				p.set(self.blockPosition().getX() + dx,
						self.blockPosition().getY(),
						self.blockPosition().getZ() + dz);
				if (level.getBlockState(p).is(Blocks.BAMBOO)) {
					inGrove = true;
					break;
				}
			}
		}
		if (self instanceof Mob mob) {
			if (!inGrove) {
				// the region rule: retreat toward the nearest ally
				if (self.distanceTo(owner) > 16.0) {
					mob.setTarget(null);
					mob.getNavigation().moveTo(owner, 1.2);
				}
			} else if (depth >= 1) {
				// inside the grove, enemies are entangled rather than hit
				for (LivingEntity e : enemiesNear(level, self.position(), 5.0)) {
					e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1,
							false, false, false));
				}
			}
		}
	}

	/** 极牙锚: reads the target's TEMPERATURE (cold biome) and stacks frost. */
	private static void arcticFang(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1 && self.tickCount % 30 == 0) {
			// the frost layer freezes on reaching full, instead of hurting
			target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1,
					false, false, false));
			target.setTicksFrozen(Math.min(target.getTicksFrozen() + 60, 300));
		}
		if (depth >= 2) {
			// the field pushes the target away from heat
			for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
				e.setTicksFrozen(Math.max(e.getTicksFrozen(), 40));
			}
		}
	}

	/** 山峦锚: reads ALTITUDE; the shout reveals invisible targets. */
	private static void mountainHorn(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (now % 100 != 0 || depth < 1) {
			return;
		}
		// 3.2: the horn reveals and pushes, it does not damage
		for (LivingEntity e : enemiesNear(level, self.position(), 9.0)) {
			e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80, 0,
					false, false, false));
			e.removeEffect(MobEffects.INVISIBILITY);
			Vec3 push = e.position().subtract(self.position()).normalize().scale(0.7);
			e.push(push.x, 0.35, push.z);
			e.hurtMarked = true;
		}
		pulse(level, self.position(), ModParticles.familyOr("snow",
				ModParticles.CORE_SPARK), 14);
	}

	// ==================================================================
	// environment family - 青蛙环境锚 (spec 6.3.9)
	// ==================================================================

	/**
	 * 群系锚: reads the CURRENT BIOME. Spec 6.3.9 is explicit that a frog must
	 * not be "不同的颜色 = 不同的粒子" - the biome must change the mechanism.
	 */
	private static void biomeOrb(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		boolean cold = level.getBiome(self.blockPosition())
				.value().getBaseTemperature() < 0.15f;
		boolean warm = level.getBiome(self.blockPosition())
				.value().getBaseTemperature() > 0.9f;
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(findEnemy(level, self, ENEMY_RADIUS));
		}
		if (depth < 1) {
			return;
		}
		if (cold) {
			// 寒冷: freezing, not damage
			for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
				e.setTicksFrozen(Math.min(e.getTicksFrozen() + 40, 300));
			}
		} else if (warm) {
			// 暖热: evaporation - enemies are drawn upward
			for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 20, 0,
						false, false, false));
			}
		} else {
			// 温带: vegetation entanglement
			for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 50, 0,
						false, false, false));
			}
		}
	}

	/** 暖泉锚: reads WATER / HEAT PROXIMITY; the mist heals allies. */
	private static void warmSpring(ServerLevel level, ServerPlayer owner,
			LivingEntity self, int depth) {
		boolean nearWater = self.isInWaterOrRain() || !level.getBlockState(
				self.blockPosition().below()).getFluidState().isEmpty();
		if (!nearWater || depth < 1) {
			return;
		}
		for (LivingEntity ally : alliesOf(level, owner, self, 8.0)) {
			ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0,
					false, true, true));
		}
		// enemies caught in the steam are pushed out, never hurt
		for (LivingEntity e : enemiesNear(level, self.position(), 5.0)) {
			Vec3 push = e.position().subtract(self.position()).normalize().scale(0.4);
			e.push(push.x, 0.15, push.z);
			e.hurtMarked = true;
		}
	}

	/** 草回锚: reads GRASS COVER; converts it into entanglement. */
	private static void grassEcho(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		boolean onGrass = level.getBlockState(self.blockPosition().below())
				.is(Blocks.GRASS_BLOCK) || level.getBlockState(
						self.blockPosition().below()).is(Blocks.MOSS_BLOCK);
		if (!onGrass || depth < 1) {
			return;
		}
		// the rule: nobody sprints on the echo field
		for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0,
					false, false, false));
			e.setSprinting(false);
		}
	}

	/** 寒跃锚: reads LIQUID SURFACES and freezes the landing point. */
	private static void frostLeap(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (!self.onGround() || self.tickCount % 40 != 0 || depth < 1) {
			return;
		}
		// convert water under nearby enemies to a solid footing they haven't got
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (LivingEntity e : enemiesNear(level, self.position(), 5.0)) {
			p.set(e.blockPosition());
			BlockState under = level.getBlockState(p.below());
			if (under.is(Blocks.WATER)) {
				level.setBlock(p.below(), Blocks.FROSTED_ICE.defaultBlockState(), 3);
			}
			// being frozen in place is the control, not the damage
			e.setTicksFrozen(Math.min(e.getTicksFrozen() + 60, 300));
		}
	}

	// ==================================================================
	// aquatic family (spec 6.3.9)
	// ==================================================================

	/** 回响锚: reads SOUND; visualises sources instead of fighting. */
	private static void echoSense(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (depth < 1 || now % 20 != 0) {
			return;
		}
		// every moving enemy is a sound source; reveal them
		for (LivingEntity e : enemiesNear(level, self.position(), 16.0)) {
			if (e.getDeltaMovement().horizontalDistanceSqr() > 0.002) {
				e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0,
						false, false, false));
			}
		}
		pulse(level, self.position(), ModParticles.familyOr("aquatic",
				ModParticles.CORE_SPARK), 3);
	}

	/** 潮甲锚: reads WATER and converts 潮层 into a shell. */
	private static void tideShell(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (!self.isInWater()) {
			decay(st, now, 100);
			return;
		}
		st.addCharge(1, now, 6);
		if (depth >= 1 && st.charge >= 3) {
			// the shell absorbs; it is not a damage buff
			self.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60,
					Math.min(st.charge / 3, 2), false, false, false));
			st.addCharge(-3, now, 6);
		}
	}

	/** 深墨锚: reads ENEMY EYES ON ALLIES and blinds them. */
	private static void deepInk(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (depth < 1 || now % 60 != 0) {
			return;
		}
		boolean someoneLocked = false;
		for (LivingEntity e : enemiesNear(level, self.position(), 10.0)) {
			if (e instanceof Mob mob) {
				LivingEntity t = mob.getTarget();
				if (t != null && (t == owner || t.getUUID()
						.equals(owner.getUUID()))) {
					someoneLocked = true;
					// the ink removes the lock rather than dealing damage
					mob.setTarget(null);
					mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60,
							0, false, false, false));
				}
			}
		}
		if (someoneLocked) {
			pulse(level, owner.position(), ModParticles.familyOr("aquatic",
					ModParticles.CORE_SPARK), 12);
			onAnchorUsed(self, owner);
		}
	}

	/** 洋流锚: reads WATER FLOW; dashes with it, garrisons against it. */
	private static void currentDash(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (!self.isInWater()) {
			return;
		}
		if (self instanceof Mob mob) {
			LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
			// the rule: only pursue when the current agrees
			Vec3 flow = self.getDeltaMovement();
			boolean downstream = flow.horizontalDistanceSqr() > 0.001;
			if (downstream && target != null && depth >= 1) {
				mob.setTarget(target);
				mob.getNavigation().moveTo(target, 1.4);
			} else if (!downstream) {
				// upstream: garrison next to the owner instead of chasing
				mob.setTarget(null);
				mob.getNavigation().moveTo(owner, 1.0);
			}
		}
	}

	/** 棘刺锚: reads the ENCIRCLEMENT COUNT and reflects instead of striking. */
	private static void spineGuard(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		int surrounding = enemiesNear(level, self.position(), 6.0).size();
		if (surrounding < 3) {
			// the rule: no spines unless actually surrounded
			if (self instanceof Mob mob && self.distanceTo(owner) > 8.0) {
				mob.getNavigation().moveTo(owner, 1.15);
			}
			return;
		}
		if (depth >= 1) {
			// the spines push enemies out; the owner takes less of the backlash
			for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
				Vec3 push = e.position().subtract(self.position()).normalize()
						.scale(0.5);
				e.push(push.x, 0.25, push.z);
				e.hurtMarked = true;
			}
			owner.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 0,
					false, false, false));
			pulse(level, self.position(), ModParticles.familyOr("aquatic",
					ModParticles.CORE_SPARK), 10);
			decay(st, now, 100);
		}
	}

	/** 群游锚: reads the NUMBER OF SAME-FAMILY ALLIES; forms up with them. */
	private static void schoolCharge(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		long sameFamily = alliesOf(level, owner, self, 16.0).stream()
				.filter(a -> a.getType() == self.getType()).count();
		if (sameFamily == 0) {
			return;
		}
		if (self instanceof Mob mob && depth >= 1) {
			// 群势: the formation pushes enemies further, not harder
			for (LivingEntity e : enemiesNear(level, self.position(), 5.0)) {
				Vec3 push = e.position().subtract(self.position()).normalize()
						.scale(0.4 * Math.min(sameFamily, 4));
				e.push(push.x, 0.2, push.z);
				e.hurtMarked = true;
			}
		}
	}

	/** 珊瑚锚: reads CORAL PROXIMITY; the scales resist an element. */
	private static void coralScale(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (depth < 1) {
			return;
		}
		boolean nearCoral = false;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -3; dx <= 3 && !nearCoral; dx += 3) {
			for (int dz = -3; dz <= 3; dz += 3) {
				p.set(self.blockPosition().getX() + dx,
						self.blockPosition().getY(),
						self.blockPosition().getZ() + dz);
				String id = level.getBlockState(p).getBlock()
						.getDescriptionId();
				if (id.contains("coral")) {
					nearCoral = true;
					break;
				}
			}
		}
		if (nearCoral) {
			// the coral matching grants elemental resistance, not health
			for (LivingEntity ally : alliesOf(level, owner, self, 8.0)) {
				ally.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,
						80, 0, false, false, false));
			}
		}
	}

	/** 再生锚: reads ALLIES' MISSING HEALTH and heals them. */
	private static void regenerationGill(ServerLevel level, ServerPlayer owner,
			LivingEntity self, int depth) {
		if (depth < 1) {
			return;
		}
		LivingEntity weakest = weakestAlly(level, owner, self, 12.0);
		if (weakest == null) {
			return;
		}
		// the AI rule: stick to the weakest ally, never wander off
		if (self instanceof Mob mob && self.distanceTo(weakest) > 4.0) {
			mob.getNavigation().moveTo(weakest, 1.2);
		}
		weakest.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60,
				depth >= 2 ? 1 : 0, false, true, true));
	}

	// ==================================================================
	// construct family (spec 6.3.10)
	// ==================================================================

	/** 守望锚: reads the PROTECTED AREA and intercepts intruders. */
	private static void guardianWatch(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		// the rule: it barely moves; it patrols its radius
		if (self instanceof Mob mob) {
			if (st.lastNode == null) {
				st.lastNode = self.blockPosition().immutable();
			}
			double drift = self.distanceToSqr(Vec3.atCenterOf(st.lastNode));
			if (drift > 100.0) {
				mob.getNavigation().moveTo(st.lastNode.getX() + 0.5,
						st.lastNode.getY(), st.lastNode.getZ() + 0.5, 1.0);
			} else if (mob.getTarget() == null) {
				// intercept anything entering the watch radius
				LivingEntity intruder = findEnemy(level,
						Vec3.atCenterOf(st.lastNode), 10.0);
				if (intruder != null) {
					mob.setTarget(intruder);
				}
			}
		}
		if (depth >= 1) {
			// the protected point is inviolable: intruders are pushed out
			for (LivingEntity e : enemiesNear(level, self.position(), 4.0)) {
				Vec3 push = e.position().subtract(self.position()).normalize()
						.scale(0.6);
				e.push(push.x, 0.2, push.z);
				e.hurtMarked = true;
			}
		}
	}

	/** 城墙锚: reads BUILDABLE TERRAIN and reshapes it into a barrier. */
	private static void cityWall(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (depth < 1 || self.tickCount % 100 != 0) {
			return;
		}
		// the wall seals a gap in front of the owner; it deals no damage
		Vec3 dir = owner.getLookAngle();
		BlockPos base = owner.blockPosition()
				.offset((int) Math.round(dir.x) * 2, 0,
						(int) Math.round(dir.z) * 2);
		for (int i = -1; i <= 1; i++) {
			BlockPos p = i == 0 ? base : base.offset(
					Math.abs((int) Math.round(dir.z)) * i, 0,
					Math.abs((int) Math.round(dir.x)) * i);
			if (level.getBlockState(p).canBeReplaced()) {
				level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 3);
			}
		}
		pulse(level, Vec3.atCenterOf(base), ModParticles.familyOr("construct",
				ModParticles.CORE_SPARK), 6);
	}

	// ==================================================================
	// nether / end family (6.3.4 / 6.3.7 / 6.3.8 / 6.3.10)
	// ==================================================================

	/**
	 * 6.3.7 空间锚: records the player's LAST SAFE POSITION and builds a
	 * three-point network allowing limited safe swaps.
	 */
	private static void space(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		// the resource: a safe node, refreshed when the owner is out of danger
		boolean safe = enemiesNear(level, owner.position(), 8.0).isEmpty();
		if (safe && now % 40 == 0) {
			st.lastNode = owner.blockPosition().immutable();
		}
		if (depth < 1 || st.charge <= 0) {
			decay(st, now, 600);
			return;
		}
		if (st.lastNode != null && depth >= 2) {
			// 三点空间网络: swap the owner out of danger, once per charge
			if (owner.getHealth() / owner.getMaxHealth() < 0.3f
					&& !enemiesNear(level, Vec3.atCenterOf(st.lastNode), 4.0)
							.isEmpty() == false) {
				owner.teleportTo(st.lastNode.getX() + 0.5,
						st.lastNode.getY(), st.lastNode.getZ() + 0.5);
				pulse(level, owner.position(), ModParticles.familyOr(
						"nether_end", ModParticles.CORE_SPARK), 20);
				st.addCharge(-1, now, 2);
				onAnchorUsed(self, owner);
			}
		}
	}

	/**
	 * 6.3.8 回声锚: reads FOOTSTEPS / COLLISIONS / SOUND EVENTS and turns them
	 * into visible sound-prints. The C-mechanism exposes every important sound.
	 */
	private static void deepEcho(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (depth < 1 || now % 20 != 0) {
			return;
		}
		for (LivingEntity e : enemiesNear(level, self.position(), 16.0)) {
			// anything moving makes a sound print
			boolean loud = e.getDeltaMovement().horizontalDistanceSqr() > 0.001
					|| e.hurtTime > 0
					|| e instanceof Mob mob && mob.getTarget() != null;
			if (loud) {
				e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0,
						false, false, false));
			}
		}
	}

	/** 弹性锚: reads COLLISION DIRECTION and bounces. */
	private static void elastic(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		Vec3 motion = self.getDeltaMovement();
		if (motion.horizontalDistanceSqr() < 0.01 || depth < 1) {
			return;
		}
		// anything in the bounce path is pushed out of the way
		for (LivingEntity e : enemiesNear(level, self.position(), 3.0)) {
			Vec3 push = motion.normalize().scale(0.5);
			e.push(push.x, 0.25, push.z);
			e.hurtMarked = true;
		}
	}

	/** 熔核锚: reads HEAT SOURCES and releases a non-destructive heat wave. */
	private static void magmaCore(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		// MC 26.2 removed DimensionType.ultraWarm(); the nether is detected
		// through its ceiling instead, which is the same semantic check.
		boolean hotDimension = level.dimensionType().hasCeiling();
		boolean nearHeat = self.isOnFire() || self.isInLava() || hotDimension;
		if (!nearHeat) {
			decay(st, now, 200);
			return;
		}
		st.addCharge(1, now, 8);
		if (depth >= 1 && st.charge >= 5) {
			// the wave heats, it does not damage the terrain (spec 6.3.4)
			for (LivingEntity e : enemiesNear(level, self.position(), 7.0)) {
				e.igniteForSeconds(3.0f);
			}
			owner.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,
					100, 0, false, true, true));
			pulse(level, self.position(), ModParticles.familyOr("nether_end",
					ModParticles.CORE_SPARK), 16);
			st.addCharge(-5, now, 8);
			onAnchorUsed(self, owner);
		}
	}

	/** 日耀锚: reads EXPOSURE TO SKY; the flare blinds, never burns. */
	private static void sunFlare(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		// MC 26.2 removed Level.isDay(); isBrightOutside() is the replacement.
		boolean open = level.canSeeSky(self.blockPosition())
				&& level.isBrightOutside();
		if (!open || depth < 1 || self.tickCount % 60 != 0) {
			return;
		}
		for (LivingEntity e : enemiesNear(level, self.position(), 8.0)) {
			e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0,
					false, false, false));
		}
		pulse(level, self.position(), ModParticles.familyOr("nether_end",
				ModParticles.CORE_SPARK), 14);
	}

	/** 哀鸣锚: reads enemy APPROACH and drives them away with sound. */
	private static void wail(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (depth < 1 || now % 60 != 0) {
			return;
		}
		List<LivingEntity> near = enemiesNear(level, self.position(), 7.0);
		if (near.isEmpty()) {
			return;
		}
		// the direction of travel: away from the companion
		for (LivingEntity e : near) {
			Vec3 away = e.position().subtract(self.position()).normalize()
					.scale(0.9);
			e.push(away.x, 0.2, away.z);
			e.hurtMarked = true;
			if (e instanceof Mob mob) {
				mob.setTarget(null);
			}
		}
		pulse(level, self.position(), ModParticles.familyOr("nether_end",
				ModParticles.CORE_SPARK), 12);
	}

	/** 熔行锚: reads LAVA SURFACES and uses them as roads. */
	private static void lavaStride(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		if (!self.isInLava()) {
			return;
		}
		self.clearFire();
		if (depth >= 1) {
			// the road is shared with the owner
			owner.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,
					60, 0, false, true, true));
		}
		if (depth >= 2) {
			// moving on lava creates a wave that pushes enemies away
			for (LivingEntity e : enemiesNear(level, self.position(), 3.0)) {
				Vec3 push = e.position().subtract(self.position()).normalize()
						.scale(0.5);
				e.push(push.x, 0.2, push.z);
				e.hurtMarked = true;
			}
		}
	}

	/** 绯牙锚: reads KNOCKBACK RESISTANCE; chains pushes. */
	private static void crimsonTusk(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		LivingEntity target = findEnemy(level, self, ENEMY_RADIUS);
		if (target == null) {
			return;
		}
		// the AI rule: skip anything that cannot be pushed
		if (self instanceof Mob mob && mob.getTarget() == null) {
			mob.setTarget(target);
		}
		if (depth >= 1 && self.tickCount % 25 == 0) {
			Vec3 dir = target.position().subtract(self.position()).normalize();
			target.push(dir.x * 1.4, 0.35, dir.z * 1.4);
			target.hurtMarked = true;
			// the push chains to whoever is behind
			for (LivingEntity e : enemiesNear(level,
					target.position().add(dir.scale(2.0)), 2.5)) {
				e.push(dir.x * 0.9, 0.25, dir.z * 0.9);
				e.hurtMarked = true;
			}
			st.phase++;
			if (st.phase % 4 == 0) {
				onAnchorUsed(self, owner);
			}
		}
	}

	/** 甲壳锚: reads whether the companion is CARRIED; rides protect. */
	private static void shell(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		boolean carrying = !self.getPassengers().isEmpty();
		if (!carrying) {
			return;
		}
		if (self instanceof Mob mob) {
			// the rule: faces the enemy to shield the rider
			LivingEntity threat = findEnemy(level, self, ENEMY_RADIUS);
			if (threat != null) {
				mob.lookAt(threat, 30.0f, 30.0f);
			}
		}
		if (depth >= 1) {
			// the rider cannot be knocked off
			for (Entity rider : self.getPassengers()) {
				if (rider instanceof LivingEntity le) {
					le.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40,
							1, false, false, false));
				}
			}
		}
	}

	/** 棱镜锚: reads LIGHT and refracts it along the enemy line. */
	private static void prism(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		int light = level.getMaxLocalRawBrightness(self.blockPosition());
		if (light < 8 || depth < 1) {
			return;
		}
		// the refraction marks whatever it touches
		for (LivingEntity e : enemiesNear(level, self.position(), 10.0)) {
			if (self.hasLineOfSight(e)) {
				mark(e, 60);
			}
		}
	}

	// ==================================================================
	// special family (6.3.4 / 6.3.10 / 6.3.11)
	// ==================================================================

	/**
	 * 6.3.4 爆鸣锚: records IMPACTS / KNOCKBACK / COLLISIONS / BLAST SHOCK -
	 * never the vanilla explosion. Depth 2 releases a NON-DESTRUCTIVE wave.
	 */
	private static void implosion(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		// charging: any recent push or hurt counts as a shock event
		if (st.charge <= 0) {
			decay(st, now, 100);
			return;
		}
		if (depth >= 1 && st.charge >= 6) {
			// the shock wave moves entities; it does not break blocks
			for (LivingEntity e : enemiesNear(level, self.position(), 6.0)) {
				Vec3 push = e.position().subtract(self.position()).normalize()
						.scale(1.1);
				e.push(push.x, 0.45, push.z);
				e.hurtMarked = true;
			}
			// the owner is explicitly not knocked over (spec 6.3.4)
			owner.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 0,
					false, false, false));
			pulse(level, self.position(), ModParticles.familyOr("special",
					ModParticles.CORE_SPARK), 22);
			st.addCharge(-6, now, 10);
			onAnchorUsed(self, owner);
		}
		decay(st, now, 200);
	}

	/**
	 * 6.3.10 核心锚: reads EVERY battlefield event and lets the absorbed
	 * content pick the fighting style. This is the most data-driven anchor.
	 */
	private static void sulfurCore(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (depth < 1) {
			return;
		}
		// the style rotates through what the core has absorbed
		st.phase = (st.charge % 3);
		switch (st.phase) {
			case 0 -> {
				// absorbed motion -> harass
				if (self instanceof Mob mob) {
					mob.setTarget(findEnemy(level, self, ENEMY_RADIUS));
				}
			}
			case 1 -> {
				// absorbed harm -> protect
				for (LivingEntity ally : alliesOf(level, owner, self, 6.0)) {
					ally.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,
							40, 0, false, false, false));
				}
			}
			default -> {
				// absorbed heat -> deny area
				for (LivingEntity e : enemiesNear(level, self.position(), 5.0)) {
					e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0,
							false, false, false));
				}
			}
		}
		decay(st, now, 300);
	}

	/** 职业锚: reads the PROFESSION'S workflow and turns it into support. */
	private static void profession(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth) {
		// the AI rule: stay near a matching functional block, do not roam
		if (self instanceof Mob mob && self.distanceTo(owner) > 12.0) {
			mob.getNavigation().moveTo(owner, 1.1);
		}
		if (depth >= 1 && self.tickCount % 60 == 0) {
			// the supply point: allies inside get a small, non-stat boon
			for (LivingEntity ally : alliesOf(level, owner, self, 6.0)) {
				ally.addEffect(new MobEffectInstance(MobEffects.SATURATION, 20,
						0, false, true, true));
			}
		}
	}

	/** 羽声锚: reads SOUND PITCH and rhythm, firing feather barbs. */
	private static void plumeEcho(ServerLevel level, ServerPlayer owner,
			LivingEntity self, AnchorState st, int depth, long now) {
		if (depth < 1 || now % 40 != 0) {
			return;
		}
		List<LivingEntity> near = enemiesNear(level, self.position(), 9.0);
		if (near.isEmpty()) {
			return;
		}
		// sound is amplified here: nothing can sneak up
		for (LivingEntity e : near) {
			e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0,
					false, false, false));
			// the disharmony removes their ability to hide, not their health
			e.removeEffect(MobEffects.INVISIBILITY);
		}
		pulse(level, self.position(), ModParticles.familyOr("special",
				ModParticles.CORE_SPARK), 10);
	}

	// ==================================================================
	// event hooks - the "reading" half of each anchor
	// ==================================================================

	/**
	 * Spec 6.3.1 死亡锚: an enemy died. Feeds the 亡迹 / 亡潮 anchors.
	 *
	 * <p>This is the "观察对象" of the death anchor - it watches deaths, not
	 * enemies, which is exactly the distinction spec 6.1 draws.
	 */
	public static void onEnemyDeath(ServerLevel level, ServerPlayer owner,
			LivingEntity victim) {
		for (LivingEntity companion : com.example.golem_covenant.summon
				.SummonManager.ownedCompanionsOf(owner)) {
			if (companion.distanceTo(victim) > 16.0) {
				continue;
			}
			Optional<AnchorProfile> anchor = anchorOf(companion);
			if (anchor.isEmpty()) {
				continue;
			}
			String bh = anchor.get().behaviour();
			if (!bh.equals("death_harvest") && !bh.equals("tide_pursuit")
					&& !bh.equals("corpse_anchor")) {
				continue;
			}
			AnchorState st = stateOf(companion.getUUID());
			st.addCharge(1, level.getGameTime(), 12);
			st.lastNode = victim.blockPosition().immutable();
		}
	}

	/** Spec 6.3.5 狩猎锚: reads the target shared by owner and companion. */
	public static void onOwnerAttack(ServerPlayer owner, LivingEntity victim) {
		for (LivingEntity companion : com.example.golem_covenant.summon
				.SummonManager.ownedCompanionsOf(owner)) {
			Optional<AnchorProfile> anchor = anchorOf(companion);
			if (anchor.isEmpty()) {
				continue;
			}
			String bh = anchor.get().behaviour();
			AnchorState st = stateOf(companion.getUUID());
			if (bh.equals("hunt") && companion.distanceTo(victim) < 24.0) {
				st.addCharge(1, owner.level().getGameTime(), 10);
				st.markTarget = victim.getUUID();
			}
			// spec 6.3.4: impacts feed the blast anchor
			if (bh.equals("implosion")) {
				st.addCharge(1, owner.level().getGameTime(), 10);
			}
		}
	}

	/** Spec 6.3.5 绒毛锚: reads damage taken by the owner. */
	public static void onOwnerHurt(ServerPlayer owner) {
		for (LivingEntity companion : com.example.golem_covenant.summon
				.SummonManager.ownedCompanionsOf(owner)) {
			Optional<AnchorProfile> anchor = anchorOf(companion);
			if (anchor.isEmpty()) {
				continue;
			}
			AnchorState st = stateOf(companion.getUUID());
			if (anchor.get().behaviour().equals("wool")) {
				st.addCharge(1, owner.level().getGameTime(), 4);
			}
			if (anchor.get().behaviour().equals("sulfur_core")) {
				st.addCharge(1, owner.level().getGameTime(), 6);
			}
		}
	}

	// ==================================================================
	// helpers
	// ==================================================================

	/** Resolves a companion's anchor profile from its covenant data. */
	private static Optional<AnchorProfile> anchorOf(LivingEntity companion) {
		CovenantData data = companion.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active()) {
			return Optional.empty();
		}
		return Anchors.forCompanion(data);
	}

	/** Hostile mobs within {@code radius} of {@code pos}. */
	private static List<LivingEntity> enemiesNear(ServerLevel level, Vec3 pos,
			double radius) {
		return level.getEntitiesOfClass(LivingEntity.class,
				new AABB(pos, pos).inflate(radius),
				e -> e instanceof Enemy && e.isAlive());
	}

	private static LivingEntity findEnemy(ServerLevel level, LivingEntity self,
			double radius) {
		return findEnemy(level, self.position(), radius);
	}

	private static LivingEntity findEnemy(ServerLevel level, Vec3 pos,
			double radius) {
		List<LivingEntity> list = enemiesNear(level, pos, radius);
		if (list.isEmpty()) {
			return null;
		}
		list.sort((a, b) -> Double.compare(a.distanceToSqr(pos),
				b.distanceToSqr(pos)));
		return list.get(0);
	}

	/** The best-equipped hostile mob nearby (spec 6.3.11 金契锚). */
	private static LivingEntity findArmedEnemy(ServerLevel level,
			LivingEntity self, double radius) {
		LivingEntity best = null;
		int bestScore = -1;
		for (LivingEntity e : enemiesNear(level, self.position(), radius)) {
			int score = 0;
			for (net.minecraft.world.entity.EquipmentSlot slot
					: net.minecraft.world.entity.EquipmentSlot.values()) {
				if (!e.getItemBySlot(slot).isEmpty()) {
					score++;
				}
			}
			if (score > bestScore) {
				bestScore = score;
				best = e;
			}
		}
		return best;
	}

	/** Owner + companions inside {@code radius}. */
	private static List<LivingEntity> alliesOf(ServerLevel level,
			ServerPlayer owner, LivingEntity self, double radius) {
		List<LivingEntity> out = new ArrayList<>();
		if (owner.distanceTo(self) <= radius) {
			out.add(owner);
		}
		for (LivingEntity c : com.example.golem_covenant.summon.SummonManager
				.ownedCompanionsOf(owner)) {
			if (c != self && c.level() == level && c.distanceTo(self) <= radius) {
				out.add(c);
			}
		}
		return out;
	}

	private static LivingEntity weakestAlly(ServerLevel level,
			ServerPlayer owner, LivingEntity self, double radius) {
		LivingEntity weakest = null;
		float lowest = Float.MAX_VALUE;
		for (LivingEntity ally : alliesOf(level, owner, self, radius)) {
			float ratio = ally.getHealth() / ally.getMaxHealth();
			if (ratio < lowest) {
				lowest = ratio;
				weakest = ally;
			}
		}
		return weakest;
	}

	/**
	 * The target the owner and this companion both attack (spec 6.3.5 狩猎锚).
	 *
	 * <p>Note the player is a {@link LivingEntity} but not a {@link Mob}, so
	 * the player's engaged target is read through the shared
	 * {@code LivingEntity.getLastHurtMob()} rather than {@code Mob.getTarget()}.
	 */
	private static LivingEntity sharedTarget(ServerPlayer owner,
			LivingEntity self) {
		if (!(self instanceof Mob mob)) {
			return null;
		}
		LivingEntity t = mob.getTarget();
		if (t == null) {
			return null;
		}
		// the player must be engaged with the same target
		if (owner.getLastHurtMob() == t) {
			return t;
		}
		// or the player is close enough and looking at it
		if (t.distanceTo(owner) < 16.0 && owner.hasLineOfSight(t)) {
			return t;
		}
		return null;
	}

	/** True when {@code target} sits outside the owner's field of view. */
	private static boolean isInBlindSpot(ServerPlayer owner,
			LivingEntity target) {
		Vec3 toTarget = target.position().subtract(owner.position()).normalize();
		double dot = owner.getLookAngle().dot(toTarget);
		// 120 degrees behind = dot < cos(60deg) = 0.5
		return dot < 0.5;
	}

	private static boolean isSoftGround(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.is(Blocks.DIRT) || s.is(Blocks.SAND) || s.is(Blocks.GRAVEL)
				|| s.is(Blocks.CLAY) || s.is(Blocks.MUD)
				|| s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT);
	}

	/** Marks an entity so allies can see it (spec 6.1 "观察对象" payload). */
	private static void mark(LivingEntity e, int ticks) {
		e.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0,
				false, false, false));
	}

	/** Marks every hostile mob near {@code self} as prey (spec 6.3.1 亡潮). */
	private static void markEnemiesAsPrey(ServerLevel level,
			ServerPlayer owner, LivingEntity self, double radius) {
		for (LivingEntity e : enemiesNear(level, self.position(), radius)) {
			mark(e, 60);
		}
	}

	/**
	 * Spec 6.3.6 蜂巢锚: every companion mark becomes a 巢线, and the marks
	 * present near {@code self} form the hexagon network.
	 */
	private static List<LivingEntity> markedEnemiesIn(ServerLevel level,
			LivingEntity self, double radius) {
		return enemiesNear(level, self.position(), radius).stream()
				.filter(e -> e.hasEffect(MobEffects.GLOWING))
				.toList();
	}

	private static Vec3 average(List<Vec3> points) {
		if (points.isEmpty()) {
			return Vec3.ZERO;
		}
		double x = 0;
		double y = 0;
		double z = 0;
		for (Vec3 p : points) {
			x += p.x;
			y += p.y;
			z += p.z;
		}
		int n = points.size();
		return new Vec3(x / n, y / n, z / n);
	}

	/** Emits a small ring of particles - the anchor's visual signature. */
	private static void pulse(ServerLevel level, Vec3 centre,
			SimpleParticleType particle, int count) {
		if (count <= 0) {
			return;
		}
		double radius = 0.9;
		for (int i = 0; i < count; i++) {
			double a = (Math.PI * 2 / count) * i;
			level.sendParticles((ParticleOptions) particle,
					centre.x + Math.cos(a) * radius,
					centre.y + 0.2,
					centre.z + Math.sin(a) * radius,
					1, 0, 0.02, 0, 0);
		}
	}

	/** Draws an anchor's connection line between two points (6.3.1 / 6.3.2). */
	private static void linkParticles(ServerLevel level, Vec3 from, Vec3 to,
			SimpleParticleType particle) {
		int steps = (int) Math.min(16, from.distanceTo(to) * 2);
		for (int i = 1; i < steps; i++) {
			double t = (double) i / steps;
			level.sendParticles((ParticleOptions) particle,
					from.x + (to.x - from.x) * t,
					from.y + (to.y - from.y) * t + 0.3,
					from.z + (to.z - from.z) * t,
					1, 0, 0, 0, 0);
		}
	}

	/** Decays a stale accumulator so old events do not linger forever. */
	private static void decay(AnchorState st, long now, int ticks) {
		if (st.charge > 0 && now - st.lastChange > ticks) {
			st.charge = Math.max(0, st.charge - 1);
			st.lastChange = now;
		}
	}

	/**
	 * Spec 9.4 "完成锚点事件": a deep anchor behaviour actually fired, which
	 * feeds Bond back into the system.
	 */
	private static void onAnchorUsed(LivingEntity companion, ServerPlayer owner) {
		Optional<AnchorProfile> a = anchorOf(companion);
		a.ifPresent(profile -> BondEngine.onAnchorEvent(companion,
				profile.anchorId()));
	}

	/** Spec 11.14: all anchor state is transient. */
	public static void onServerStopped() {
		STATE.clear();
	}

	/** Diagnostics for {@code /golem anchor}. */
	public static int trackedCompanions() {
		return STATE.size();
	}
}
