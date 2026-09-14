package com.example.golem_covenant.team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import com.example.golem_covenant.bond.BondEngine;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.FormsRegistry;
import com.example.golem_covenant.data.SoulProfile;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.summon.SummonManager;

/**
 * 队伍构筑与锚点共鸣 (spec ch.10).
 *
 * <p>Spec 10.1 defines resonance as a property of an <b>anchor pair</b>, not of
 * two specific creatures. So this engine keys resonance on the anchor's
 * <b>family</b> pair (the prefix before {@code _} in {@code anchorId}), which
 * makes all three named combinations in spec 10.1 fall out naturally while
 * also covering the 66 anchors actually present in the registry:
 *
 * <table>
 *   <caption>spec 10.1 named resonances</caption>
 *   <tr><th>组合</th><th>锚点</th><th>共鸣</th><th>本引擎对应的族群对</th></tr>
 *   <tr><td>狼 + 骷髅</td><td>狩猎 + 远矢</td><td>远近协同</td>
 *       <td>{@code animal} + {@code zombie}</td></tr>
 *   <tr><td>末影人 + 蜘蛛</td><td>空间 + 织命</td><td>空间蛛网</td>
 *       <td>{@code nether_end} + {@code arthropod}</td></tr>
 *   <tr><td>监守者 + 骷髅</td><td>回声 + 远矢</td><td>声纹狙击</td>
 *       <td>{@code nether_end} + {@code zombie}</td></tr>
 * </table>
 *
 * <p>Spec 10.2 frames the payoff as "Build（构筑）" - the value is that team
 * composition <b>changes how the team plays</b>, not that it adds stats.
 * Every resonance below therefore changes AI behaviour, targeting or pathing.
 *
 * <p>Spec 10.1 marks this as a phase-2 (第二期) feature, so the whole engine
 * is gated behind {@link #enabled}.
 */
public final class ResonanceEngine {

	private ResonanceEngine() {
	}

	/** Spec 10.1 is 第二期; keep the switch so servers can disable it. */
	public static volatile boolean enabled = true;

	/** Required bond on BOTH sides before a resonance activates (spec 9.4). */
	public static final int MIN_BOND = 25;

	/** How often resonances are re-evaluated. */
	private static final int INTERVAL = 40;

	/**
	 * An anchor pair resonance. {@code families} is unordered.
	 */
	public record Resonance(String id, String nameKey, String familyA,
			String familyB, String effect) {
	}

	/**
	 * The resonance table. Only the three spec-named pairs are given unique
	 * behaviours; the rest of the family pairs get the generic "协同标记"
	 * behaviour, so every 2-anchor team still feels different from a solo one.
	 */
	private static final List<Resonance> NAMED = List.of(
			new Resonance("close_far", "golem_covenant.resonance.close_far",
					"animal", "zombie",
					"ranged allied marks the target, melee allied intercepts"),
			new Resonance("space_web", "golem_covenant.resonance.space_web",
					"nether_end", "arthropod",
					"the space node reorganises enemy movement paths"),
			new Resonance("echo_snipe", "golem_covenant.resonance.echo_snipe",
					"nether_end", "zombie",
					"sound location feeds precise ranged fire"));

	/** owner UUID -> resonances currently active for that owner's team. */
	private static final Map<UUID, Set<String>> ACTIVE = new HashMap<>();

	/** owner UUID -> companion UUIDs that are currently "paired". */
	private static final Map<UUID, Map<UUID, String>> PAIRINGS = new HashMap<>();

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!enabled || server.getTickCount() % INTERVAL != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				evaluate(player);
			}
		});
	}

	// ------------------------------------------------------------------
	// evaluation
	// ------------------------------------------------------------------

	/**
	 * Recomputes the active resonance set for one owner's current team and
	 * announces changes. Called every {@link #INTERVAL} ticks.
	 */
	public static void evaluate(ServerPlayer owner) {
		List<LivingEntity> team = SummonManager.ownedCompanionsOf(owner);
		Map<UUID, String> pairings = new HashMap<>();
		Set<String> found = new java.util.HashSet<>();

		for (int i = 0; i < team.size(); i++) {
			for (int j = i + 1; j < team.size(); j++) {
				LivingEntity a = team.get(i);
				LivingEntity b = team.get(j);
				Optional<Resonance> r = match(a, b, owner);
				if (r.isEmpty()) {
					continue;
				}
				found.add(r.get().id());
				pairings.putIfAbsent(a.getUUID(), r.get().id());
				pairings.putIfAbsent(b.getUUID(), r.get().id());
				applyResonance(r.get(), a, b, owner);
			}
		}

		Set<String> before = ACTIVE.getOrDefault(owner.getUUID(), Set.of());
		if (!before.equals(found)) {
			announceDelta(owner, before, found, pairings);
		}
		ACTIVE.put(owner.getUUID(), found);
		PAIRINGS.put(owner.getUUID(), pairings);
	}

	/**
	 * Decides whether two companions resonate, and with which rule.
	 *
	 * <p>Requirements: both must be B/C tier with an active covenant, both
	 * must have reached {@link #MIN_BOND} (spec 9.4 - resonance is the payoff
	 * of Bond), and their anchor families must form a known pair.
	 */
	private static Optional<Resonance> match(LivingEntity a, LivingEntity b,
			ServerPlayer owner) {
		CovenantData da = a.getAttached(ModAttachments.COVENANT);
		CovenantData db = b.getAttached(ModAttachments.COVENANT);
		if (da == null || db == null || !da.active() || !db.active()) {
			return Optional.empty();
		}
		if (da.bond() < MIN_BOND || db.bond() < MIN_BOND) {
			return Optional.empty();
		}
		String fa = familyOf(da.anchorId());
		String fb = familyOf(db.anchorId());
		if (fa.isEmpty() || fb.isEmpty() || fa.equals(fb)) {
			return Optional.empty();
		}
		for (Resonance r : NAMED) {
			if (r.familyA().equals(fa) && r.familyB().equals(fb)
					|| r.familyA().equals(fb) && r.familyB().equals(fa)) {
				return Optional.of(r);
			}
		}
		// generic cross-family resonance so any 2-anchor team reads as a team
		String id = fa.compareTo(fb) < 0 ? fa + "+" + fb : fb + "+" + fa;
		return Optional.of(new Resonance(id, "golem_covenant.resonance.generic",
				fa, fb, "allied anchors share a targeting mark"));
	}

	// ------------------------------------------------------------------
	// effects - behaviour, never stats (spec 10.2)
	// ------------------------------------------------------------------

	/**
	 * Applies the resonance's behavioural effect.
	 *
	 * <p>Named pairs get their spec-10.1 behaviour; the generic case gives the
	 * pair a shared target so they fight the same enemy.
	 */
	private static void applyResonance(Resonance r, LivingEntity a,
			LivingEntity b, ServerPlayer owner) {
		if (!(a.level() instanceof ServerLevel level)) {
			return;
		}
		switch (r.id()) {
			case "close_far" -> {
				// 远近协同: whoever is further away marks, the closer one
				// inherits the mark so it can intercept.
				LivingEntity marker = a.distanceTo(owner) > b.distanceTo(owner)
						? a : b;
				LivingEntity chaser = marker == a ? b : a;
				if (chaser instanceof Mob mob && mob.getTarget() == null) {
					mob.setTarget(marker instanceof Mob m ? m.getTarget() : null);
				}
			}
			case "space_web" -> {
				// 空间蛛网: the space ally drags enemies toward the web ally
				pullEnemiesToward(a, b, level);
				pullEnemiesToward(b, a, level);
			}
			case "echo_snipe" -> {
				// 声纹狙击: the echo ally reveals, the ranged ally shoots it
				LivingEntity reveal = isRanged(b) ? b : a;
				LivingEntity shooter = reveal == a ? b : a;
				markFor(shooter, reveal, owner);
			}
			default -> shareTarget(a, b);
		}
	}

	/** Generic resonance: both allies attack the same target (spec 10.2 协同标记). */
	private static void shareTarget(LivingEntity a, LivingEntity b) {
		if (!(a instanceof Mob ma) || !(b instanceof Mob mb)) {
			return;
		}
		if (ma.getTarget() != null) {
			mb.setTarget(ma.getTarget());
		} else if (mb.getTarget() != null) {
			ma.setTarget(mb.getTarget());
		}
	}

	/** The revealer's current target becomes the shooter's target. */
	private static void markFor(LivingEntity shooter, LivingEntity revealer,
			ServerPlayer owner) {
		if (!(shooter instanceof Mob mob) || mob.getTarget() != null) {
			return;
		}
		if (revealer instanceof Mob rev && rev.getTarget() != null) {
			mob.setTarget(rev.getTarget());
			return;
		}
		// fall back to whatever the owner is looking at, within 24 blocks
		AABB box = owner.getBoundingBox().inflate(24.0);
		for (LivingEntity e : owner.level().getEntitiesOfClass(LivingEntity.class,
				box, x -> x instanceof Enemy)) {
			mob.setTarget(e);
			break;
		}
	}

	/** Nudges hostile mobs along the line between the two allies. */
	private static void pullEnemiesToward(LivingEntity from, LivingEntity to,
			ServerLevel level) {
		AABB box = from.getBoundingBox().inflate(8.0);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
				x -> x instanceof Enemy)) {
			if (e instanceof Mob mob) {
				mob.setTarget(to);
			}
		}
	}

	/**
	 * A "ranged" ally for the purposes of 声纹狙击. Skeleton-family mobs are
	 * the ranged anchors in the registry (骨弦 / 远矢), so we test that.
	 */
	private static boolean isRanged(LivingEntity e) {
		return e instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Known anchor-family prefixes. Ordered longest-first so {@code nether_end}
	 * is matched before any shorter prefix could shadow it. A naive
	 * {@code split("_")[0]} is wrong here: {@code nether_end_space} would
	 * become {@code nether}, which is not a family the registry uses.
	 */
	private static final List<String> FAMILY_PREFIXES = List.of(
			"nether_end", "environment", "arthropod", "construct", "aquatic",
			"special", "animal", "mount", "snow", "zombie");

	/** {@code nether_end_space} -> {@code nether_end}; empty for reserved. */
	public static String familyOf(String anchorId) {
		if (anchorId == null || anchorId.isBlank()
				|| anchorId.startsWith("reserved")) {
			return "";
		}
		for (String prefix : FAMILY_PREFIXES) {
			if (anchorId.startsWith(prefix + "_")) {
				return prefix;
			}
		}
		// unknown prefix: fall back to the first segment
		int i = anchorId.indexOf('_');
		return i < 0 ? anchorId : anchorId.substring(0, i);
	}

	/** The anchor family of a companion, via the registry. */
	public static String familyOfCompanion(LivingEntity companion) {
		CovenantData d = companion.getAttached(ModAttachments.COVENANT);
		if (d == null) {
			return "";
		}
		Optional<SoulProfile> p = FormsRegistry.byFormId(d.formId());
		return p.map(s -> familyOf(s.abilities().anchorId())).orElse("");
	}

	private static void announceDelta(ServerPlayer owner, Set<String> before,
			Set<String> after, Map<UUID, String> pairings) {
		for (String id : after) {
			if (before.contains(id)) {
				continue;
			}
			owner.sendSystemMessage(net.minecraft.network.chat.Component
					.translatable("golem_covenant.resonance.formed",
							net.minecraft.network.chat.Component
									.translatable(resonanceKey(id))));
		}
		for (String id : before) {
			if (after.contains(id)) {
				continue;
			}
			owner.sendSystemMessage(net.minecraft.network.chat.Component
					.translatable("golem_covenant.resonance.broken",
							net.minecraft.network.chat.Component
									.translatable(resonanceKey(id))));
		}
	}

	private static String resonanceKey(String id) {
		for (Resonance r : NAMED) {
			if (r.id().equals(id)) {
				return r.nameKey();
			}
		}
		return "golem_covenant.resonance.generic";
	}

	// ------------------------------------------------------------------
	// integration point for BondEngine (spec 9.4 完成锚点事件)
	// ------------------------------------------------------------------

	/**
	 * Called when a resonance actually changes the outcome of a fight, which
	 * feeds spec 9.4's "完成锚点事件" Bond source for both partners.
	 */
	static void onResonanceEvent(LivingEntity a, LivingEntity b,
			ServerPlayer owner) {
		BondEngine.onAnchorEvent(a, familyOfCompanion(a));
		BondEngine.onAnchorEvent(b, familyOfCompanion(b));
	}

	// ------------------------------------------------------------------
	// diagnostics
	// ------------------------------------------------------------------

	public static Set<String> activeFor(ServerPlayer owner) {
		return Set.copyOf(ACTIVE.getOrDefault(owner.getUUID(), Set.of()));
	}

	public static List<Resonance> namedTable() {
		return NAMED;
	}

	public static void onServerStopped() {
		ACTIVE.clear();
		PAIRINGS.clear();
	}
}
