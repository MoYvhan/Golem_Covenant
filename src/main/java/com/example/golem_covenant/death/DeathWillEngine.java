package com.example.golem_covenant.death;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.DeathWillProfile;
import com.example.golem_covenant.data.SoulProfile;
import com.example.golem_covenant.registry.ModAttachments;

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
 */
public final class DeathWillEngine {

	/** Wills that arm a one-shot reaction instead of a timed buff. */
	private static final Map<String, WillEffect> EFFECTS = new HashMap<>();

	/** owner UUID -> armed one-shot reactions waiting for their trigger. */
	private static final Map<UUID, ArmedWill> ARMED = new HashMap<>();

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

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) {
				tickArmed(server);
			}
		});
	}

	/**
	 * Spec 8.2 + 11.7: only a C-tier companion with an ARMED will triggers one,
	 * and the will is consumed in the same step so it can never fire twice.
	 */
	private static void onCompanionDeath(ServerLevel level, LivingEntity entity) {
		CovenantData data = entity.getAttached(ModAttachments.COVENANT);
		if (data == null || !data.active() || data.tier() != com.example.golem_covenant.data.CovenantTier.C) {
			return;
		}
		if (!data.hasArmedWill()) {
			return;
		}

		Optional<SoulProfile> profile = com.example.golem_covenant.data.FormsRegistry
				.byFormId(data.formId());
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
			return;
		}
		applyWill(owner, will, data, profile.get().formId());
	}

	/**
	 * Applies the will to its owner. Timed wills refresh (never stack) per
	 * spec 8.3 / 15.2.4.
	 */
	public static void applyWill(ServerPlayer owner, DeathWillProfile will,
			CovenantData data, String formId) {
		if (!will.ownerOnly() || !data.ownedBy(owner.getUUID())) {
			return;
		}
		Optional<WillEffect> eff = will.effect();
		if (eff.isEmpty()) {
			return;
		}
		WillEffect effect = eff.get();

		if (effect.reaction()) {
			ARMED.put(owner.getUUID(), new ArmedWill(effect.id(), formId,
					will.amplifier(), owner.level().getGameTime()));
			owner.sendSystemMessage(net.minecraft.network.chat.Component
					.translatable("golem_covenant.death_will.armed",
							net.minecraft.network.chat.Component
									.translatable("golem_covenant.will." + effect.id())));
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
	}

	// ------------------------------------------------------------------
	// armed one-shot reactions (spec ch.6 named wills)
	// ------------------------------------------------------------------

	private static void tickArmed(net.minecraft.server.MinecraftServer server) {
		if (ARMED.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		ARMED.entrySet().removeIf(e -> {
			// reactions persist for 10 minutes of armed time
			return now - e.getValue().armedAt() > 12_000;
		});
	}

	/** Called by the damage hook when the owner is hurt. */
	public static boolean tryConsumeReaction(ServerPlayer owner, float incoming) {
		ArmedWill armed = ARMED.get(owner.getUUID());
		if (armed == null) {
			return false;
		}
		switch (armed.effectId()) {
			case "blink_shield" -> {
				if (incoming >= owner.getHealth()) {
					ARMED.remove(owner.getUUID());
					return true;
				}
			}
			case "hive_shard", "web_burst" -> {
				ARMED.remove(owner.getUUID());
				return true;
			}
			default -> {
				return false;
			}
		}
		return false;
	}

	public static boolean hasArmedReaction(UUID owner) {
		return ARMED.containsKey(owner);
	}

	/** Test/diagnostic helper. */
	public static Map<UUID, ArmedWill> armedSnapshot() {
		return Map.copyOf(ARMED);
	}
}
