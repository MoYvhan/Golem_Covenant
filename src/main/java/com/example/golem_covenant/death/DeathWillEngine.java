package com.example.golem_covenant.death;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.data.DeathWillProfile;
import com.example.golem_covenant.data.FormsRegistry;
import com.example.golem_covenant.data.SoulProfile;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.registry.ModParticles;

/**
 * 死亡遗志引擎 (spec ch.8 + the quantified rules in 11.4).
 *
 * <p>Design intent (8.4): a companion's death should not simply be a loss, it
 * should be "it helped me one last time". Hence:
 * <ul>
 *   <li>only C-tier forms carry a will (spec 2.4 / 11.7);</li>
 *   <li>a will fires exactly ONCE and is then {@code SPENT} (spec 8.2);</li>
 *   <li>same-family wills refresh rather than stack (spec 8.3 / 15.2.4);</li>
 *   <li>cooldown 6000 ticks, and wills only ever apply to the owner
 *       (spec 11.4.1 / 11.10.2);</li>
 *   <li>if the owner is offline the will is parked as {@code PENDING} and
 *       fires in that owner's next fight (spec 11.4.3).</li>
 * </ul>
 *
 * <p>Runtime wiring:
 * <ul>
 *   <li>{@code AFTER_DEATH} - observe the companion's death and fire/park the will;</li>
 *   <li>{@code ALLOW_DAMAGE} - resolve armed reaction wills (blink shield, hive
 *       shard, ...) before the hit lands;</li>
 *   <li>{@code END_SERVER_TICK} - deliver parked {@code PENDING} wills once the
 *       owner is online and in combat, and expire stale armed reactions.</li>
 * </ul>
 */
public final class DeathWillEngine {

	/** Wills that arm a one-shot reaction instead of a timed buff. */
	private static final Map<String, WillEffect> EFFECTS = new HashMap<>();

	/** owner UUID -> armed one-shot reactions waiting for their trigger. */
	private static final Map<UUID, ArmedWill> ARMED = new HashMap<>();

	/** owner UUID -> tick at which that owner's will cooldown ends (spec 11.4.1). */
	private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

	/** Reactions stay armed for this long, matching the A-tier 10-minute window. */
	private static final long ARMED_LIFETIME = 12_000L;

	/** Spec 11.4.3: a parked will waits at most this long for its owner. */
	public static final long PENDING_LIFETIME = 240_000L;

	private DeathWillEngine() {
	}

	/**
	 * A resolvable will effect. {@code effectId} maps to a vanilla MobEffect
	 * where one exists; reaction-style wills are handled by {@link #ARMED}.
	 */
	public record WillEffect(String id, String kind, String effectPath,
			boolean reaction) {
	}

	public record ArmedWill(String effectId, String formId, int amplifier,
			long armedAt) {
	}

	// ------------------------------------------------------------------
	// effect registry
	// ------------------------------------------------------------------

	static {
		// timed buffs -> vanilla effects
		reg("resistance", "buff", "resistance", false);
		reg("speed", "buff", "speed", false);
		reg("knockback_resist", "buff", "resistance", false);
		reg("regeneration", "buff", "regeneration", false);
		reg("fire_resistance", "buff", "fire_resistance", false);
		reg("cold_resist", "buff", "resistance", false);
		reg("slow_resist", "buff", "speed", false);
		reg("water_breathing", "buff", "water_breathing", false);
		reg("water_power", "buff", "dolphins_grace", false);
		reg("night_vision", "buff", "night_vision", false);
		reg("strength", "buff", "strength", false);
		reg("ranged_boost", "buff", "strength", false);
		reg("poison_resist", "buff", "resistance", false);
		reg("glowing_enemies", "buff", "glowing", false);
		reg("jump_boost", "buff", "jump_boost", false);
		reg("haste", "buff", "haste", false);
		reg("mining_boost", "buff", "haste", false);
		reg("absorb", "buff", "absorption", false);
		reg("exp_boost", "buff", "luck", false);
		reg("negative_resist", "buff", "resistance", false);
		reg("melee_boost", "buff", "strength", false);
		reg("tool_boost", "buff", "haste", false);
		reg("lightning_resist", "buff", "resistance", false);
		reg("tracking", "buff", "glowing", false);
		reg("random_boon", "buff", "luck", false);
		// reaction wills -> armed one-shots (spec ch.6 named wills)
		reg("blink_shield", "reaction", "", true);
		reg("last_shot", "reaction", "", true);
		reg("space_door", "reaction", "", true);
		reg("echo_ping", "reaction", "", true);
		reg("hive_shard", "reaction", "", true);
		reg("web_burst", "reaction", "", true);
	}

	private static void reg(String id, String kind, String effectPath,
			boolean reaction) {
		EFFECTS.put(id, new WillEffect(id, kind, effectPath, reaction));
	}

	public static Optional<WillEffect> resolveEffect(String effectId) {
		return Optional.ofNullable(EFFECTS.get(effectId));
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	public static void register() {
		// spec 11.10.2: ownership/damage authority is server-side.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity.level() instanceof ServerLevel sl) {
				onCompanionDeath(sl, entity);
			}
		});

		// spec ch.6 named wills: the reaction must resolve BEFORE the hit lands.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player
					&& player.level() instanceof ServerLevel level) {
				return !absorbWithReaction(player, level, source, amount);
			}
			return true;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) {
				tickArmed(server);
				deliverPendingWills(server);
			}
		});
	}

	/**
	 * Spec 8.2 + 11.7: only a C-tier companion with an ARMED will triggers one,
	 * and the will is consumed in the same step so it can never fire twice.
	 */
	private static void onCompanionDeath(ServerLevel level, LivingEntity entity) {
		CovenantData data = entity.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active() || data.tier() != CovenantTier.C) {
			return;
		}
		if (!data.hasArmedWill()) {
			return;
		}

		Optional<SoulProfile> profile = FormsRegistry.byFormId(data.formId());
		if (profile.isEmpty()) {
			return;
		}
		DeathWillProfile will = profile.get().deathWill();

		// consume first - guarantees once-only even if the effect throws later
		entity.setAttached(ModAttachments.COVENANT, data.withDeathWill(
				CovenantData.DeathWillState.SPENT));

		ServerPlayer owner = level.getServer().getPlayerList()
				.getPlayer(data.ownerUUID());
		if (owner == null) {
			// spec 11.4.3: park until the owner returns
			entity.setAttached(ModAttachments.COVENANT, data.withDeathWill(
					CovenantData.DeathWillState.PENDING));
			PENDING.put(entity.getUUID(), new PendingWill(data.ownerUUID(),
					profile.get().formId(), level.getGameTime()));
			return;
		}
		applyWill(owner, will, data, profile.get().formId(), level);
	}

	/**
	 * Applies the will to its owner. Timed wills refresh (never stack) per
	 * spec 8.3 / 15.2.4.
	 */
	public static void applyWill(ServerPlayer owner, DeathWillProfile will,
			CovenantData data, String formId, ServerLevel level) {
		if (!will.ownerOnly() || !data.ownedBy(owner.getUUID())) {
			return;
		}
		// spec 11.4.1: high-strength wills carry a cooldown
		long now = owner.level().getGameTime();
		Long until = COOLDOWNS.get(owner.getUUID());
		if (until != null && now < until) {
			return;
		}
		Optional<WillEffect> eff = will.effect();
		if (eff.isEmpty()) {
			return;
		}
		WillEffect effect = eff.get();
		COOLDOWNS.put(owner.getUUID(), now + will.cooldownTicks());

		if (effect.reaction()) {
			ARMED.put(owner.getUUID(), new ArmedWill(effect.id(), formId,
					will.amplifier(), now));
			announce(owner, "golem_covenant.death_will.armed", effect.id());
			return;
		}

		// Registry.get returns Optional<Holder.Reference<T>>, which is already
		// a Holder<MobEffect> - no unwrapping needed.
		Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT
				.get(Identifier.withDefaultNamespace(effect.effectPath()))
				.<Holder<MobEffect>>map(h -> h)
				.orElse(MobEffects.RESISTANCE);
		MobEffectInstance existing = owner.getEffect(holder);
		int duration = will.clampedDuration();
		if (existing != null) {
			// refresh-only: take the longer remaining duration, never extend
			// beyond the profile's own duration, and never raise the amplifier
			int amp = Math.max(existing.getAmplifier(), will.amplifier());
			int newDur = Math.max(existing.getDuration(), duration);
			owner.removeEffect(holder);
			owner.addEffect(new MobEffectInstance(holder, newDur, amp, false,
					true, true));
		} else {
			owner.addEffect(new MobEffectInstance(holder, duration,
					will.amplifier(), false, true, true));
		}
		announce(owner, "golem_covenant.death_will.applied", effect.id());

		// spec 5.1: the will is a visual event too, not just a buff
		level.sendParticles(ModParticles.REVIVE_BURST,
				owner.getX(), owner.getY() + 1.0, owner.getZ(),
				24, 0.6, 0.8, 0.6, 0.02);
		level.playSound(null, owner.blockPosition(),
				SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
	}

	// ------------------------------------------------------------------
	// parked wills (spec 11.4.3)
	// ------------------------------------------------------------------

	/** A will whose owner was offline when the companion died. */
	public record PendingWill(UUID ownerUUID, String formId, long parkedAt) {
	}

	private static final Map<UUID, PendingWill> PENDING = new HashMap<>();

	/**
	 * Spec 11.4.3: a parked will fires in the owner's next fight. We look for
	 * an owner who is online and currently has an enemy within 12 blocks,
	 * which is the closest server-side analogue of "in combat".
	 */
	private static void deliverPendingWills(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		Iterator<Map.Entry<UUID, PendingWill>> it = PENDING.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, PendingWill> e = it.next();
			PendingWill pw = e.getValue();
			long age = now - pw.parkedAt();

			// expired: the companion's last gift is lost (spec 11.4.3 option B)
			if (age > PENDING_LIFETIME) {
				it.remove();
				continue;
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(pw.ownerUUID());
			if (owner == null) {
				continue;
			}
			// wait for an actual fight rather than dumping it on login
			if (!inCombat(owner)) {
				continue;
			}
			Optional<SoulProfile> profile = FormsRegistry.byFormId(pw.formId());
			if (profile.isEmpty()) {
				it.remove();
				continue;
			}
			CovenantData data = CovenantData.empty().withBond(0);
			// rebuild a minimal C-tier view so applyWill's owner check passes
			CovenantData ownerData = new CovenantData(pw.ownerUUID(), pw.formId(),
					profile.get().abilities().anchorId(), CovenantTier.C, 0,
					pw.parkedAt(), 0, CovenantData.DeathWillState.SPENT, -1,
					profile.get().limits().soulLoad(CovenantTier.C), true,
					CovenantData.CURRENT_DATA_VERSION);
			if (owner.level() instanceof ServerLevel sl) {
				// bypass the cooldown a parked will may have inherited
				COOLDOWNS.remove(pw.ownerUUID());
				applyWill(owner, profile.get().deathWill(), ownerData,
						pw.formId(), sl);
			}
			it.remove();
		}
	}

	/** Server-side "in combat" test: a hostile mob within 12 blocks. */
	private static boolean inCombat(ServerPlayer player) {
		AABB box = player.getBoundingBox().inflate(12.0);
		List<LivingEntity> near = player.level().getEntitiesOfClass(
				LivingEntity.class, box,
				e -> e instanceof Enemy && e.isAlive() && e != player);
		return !near.isEmpty();
	}

	// ------------------------------------------------------------------
	// armed one-shot reactions (spec ch.6 named wills)
	// ------------------------------------------------------------------

	private static void tickArmed(MinecraftServer server) {
		if (ARMED.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		ARMED.entrySet().removeIf(e -> now - e.getValue().armedAt() > ARMED_LIFETIME);
	}

	/**
	 * Resolves an armed reaction against an incoming hit.
	 *
	 * @return true when the reaction absorbed the hit (the caller must then
	 *         cancel the damage)
	 */
	private static boolean absorbWithReaction(ServerPlayer owner, ServerLevel level,
			net.minecraft.world.damagesource.DamageSource source, float incoming) {
		ArmedWill armed = ARMED.get(owner.getUUID());
		if (armed == null) {
			return false;
		}
		boolean lethal = incoming >= owner.getHealth() + owner.getAbsorptionAmount();
		switch (armed.effectId()) {
			case "blink_shield" -> {
				// 紧急位移保护: only worth spending on a killing blow
				if (!lethal) {
					return false;
				}
				ARMED.remove(owner.getUUID());
				teleportToRespawn(owner);
				owner.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,
						60, 4, false, true, true));
				announce(owner, "golem_covenant.death_will.triggered", "blink_shield");
				level.sendParticles(ModParticles.REVIVE_BURST, owner.getX(),
						owner.getY() + 1.0, owner.getZ(), 40, 0.4, 0.7, 0.4, 0.06);
				level.playSound(null, owner.blockPosition(),
						SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.2f);
				return true;
			}
			case "last_mark", "absorb" -> {
				// 护主标记: absorb one hit outright, any size
				ARMED.remove(owner.getUUID());
				announce(owner, "golem_covenant.death_will.triggered", armed.effectId());
				level.sendParticles(ModParticles.REVIVE_BURST, owner.getX(),
						owner.getY() + 1.0, owner.getZ(), 24, 0.5, 0.5, 0.5, 0.03);
				return true;
			}
			case "hive_shard" -> {
				// 蜂巢残片: protect AND retaliate with pollen
				ARMED.remove(owner.getUUID());
				owner.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,
						100, 2, false, true, true));
				pulseEnemies(owner, level, 5.0, MobEffects.POISON, 100, 0);
				announce(owner, "golem_covenant.death_will.triggered", "hive_shard");
				return true;
			}
			case "web_burst" -> {
				// 最后织命网: entangle everything around the owner
				ARMED.remove(owner.getUUID());
				pulseEnemies(owner, level, 6.0, MobEffects.SLOWNESS, 120, 3);
				announce(owner, "golem_covenant.death_will.triggered", "web_burst");
				return true;
			}
			case "echo_ping" -> {
				// 回声钟: reveal nearby threats, do not absorb the hit
				ARMED.remove(owner.getUUID());
				AABB box = owner.getBoundingBox().inflate(24.0);
				for (LivingEntity e : owner.level().getEntitiesOfClass(
						LivingEntity.class, box, x -> x instanceof Enemy)) {
					e.addEffect(new MobEffectInstance(MobEffects.GLOWING,
							200, 0, false, false, false));
				}
				announce(owner, "golem_covenant.death_will.triggered", "echo_ping");
				return false;
			}
			case "space_door" -> {
				// 空间门: escape without absorbing; move to the respawn anchor
				// when it is in this dimension, otherwise rise out of reach
				if (!lethal) {
					return false;
				}
				ARMED.remove(owner.getUUID());
				if (!teleportToRespawn(owner)) {
					owner.teleportTo(owner.getX(), owner.getY() + 12.0, owner.getZ());
				}
				owner.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,
						200, 0, false, true, true));
				announce(owner, "golem_covenant.death_will.triggered", "space_door");
				level.playSound(null, owner.blockPosition(),
						SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.2f);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** Applies a debuff to every hostile mob inside {@code radius}. */
	private static void pulseEnemies(ServerPlayer owner, ServerLevel level,
			double radius, Holder<MobEffect> effect, int duration, int amp) {
		AABB box = owner.getBoundingBox().inflate(radius);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
				x -> x instanceof Mob && x != owner)) {
			e.addEffect(new MobEffectInstance(effect, duration, amp, false,
					true, true));
		}
		level.sendParticles(ModParticles.CORE_SPARK, owner.getX(),
				owner.getY() + 0.5, owner.getZ(), 32, radius * 0.3, 0.2,
				radius * 0.3, 0.05);
	}

	/**
	 * Moves the player to their respawn anchor, but only when that anchor is
	 * in the dimension they are currently in - otherwise cross-dimension
	 * teleports would be a free escape.
	 *
	 * @return true when a teleport actually happened
	 */
	private static boolean teleportToRespawn(ServerPlayer owner) {
		var config = owner.getRespawnConfig();
		if (config == null) {
			return false;
		}
		var data = config.respawnData();
		if (!data.dimension().equals(owner.level().dimension())) {
			return false;
		}
		var pos = data.pos();
		owner.teleportTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
		return true;
	}

	private static void announce(ServerPlayer owner, String key, String willId) {
		owner.sendSystemMessage(net.minecraft.network.chat.Component
				.translatable(key, net.minecraft.network.chat.Component
						.translatable("golem_covenant.will." + willId)));
	}

	// ------------------------------------------------------------------
	// diagnostics
	// ------------------------------------------------------------------

	public static boolean hasArmedReaction(UUID owner) {
		return ARMED.containsKey(owner);
	}

	public static boolean isOnCooldown(UUID owner, Level level) {
		Long until = COOLDOWNS.get(owner);
		return until != null && level.getGameTime() < until;
	}

	public static int pendingCount() {
		return PENDING.size();
	}

	/** Test/diagnostic helper. */
	public static Map<UUID, ArmedWill> armedSnapshot() {
		return Map.copyOf(ARMED);
	}

	/**
	 * Spec 11.14: transient runtime state is rebuilt from persisted
	 * {@code CovenantData} on load, so nothing here needs serialising. This
	 * clears per-session maps when a server stops.
	 */
	public static void onServerStopped() {
		ARMED.clear();
		COOLDOWNS.clear();
		PENDING.clear();
		GolemCovenantMod.LOGGER.debug("death-will runtime state cleared");
	}
}
