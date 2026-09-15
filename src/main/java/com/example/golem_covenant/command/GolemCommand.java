package com.example.golem_covenant.command;

import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import com.example.golem_covenant.anchor.AnchorRuntime;
import com.example.golem_covenant.anchor.Anchors;
import com.example.golem_covenant.bond.BondEngine;
import com.example.golem_covenant.data.AnchorProfile;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.CovenantTier;
import com.example.golem_covenant.registry.ModAttachments;
import com.example.golem_covenant.ritual.RitualEngine;
import com.example.golem_covenant.summon.SummonManager;
import com.example.golem_covenant.team.ResonanceEngine;
import com.example.golem_covenant.trial.SecondSoulTrial;

/**
 * The {@code /golem} command tree (spec 11.9.3, 11.10.4, 13.1).
 *
 * <p>Spec 11.9.3 requires the soul-capacity ledger to be queryable - a player
 * cannot plan a team from a HUD alone - and spec 13.1 requires the three
 * cosmetic settings to have a player-visible entry point. Both live here.
 *
 * <p>Everything the mod does is server-authoritative (spec 11.10.1), so these
 * commands read the server-side ledger and engine state, never client guesses.
 *
 * <pre>
 *   /golem covenant               team + slot + capacity overview
 *   /golem covenant capacity      soul capacity detail (spec 11.9.3)
 *   /golem anchor                 the owner's companions and their anchors
 *   /golem anchor list            all anchors grouped by family
 *   /golem anchor &lt;family&gt;      anchors inside one family
 *   /golem bond                   bond value and unlocked depth
 *   /golem trial                  第二灵魂终极试炼 progress (spec 4.3.3 / 11.8)
 *   /golem resonance              active anchor resonances (spec ch.10)
 *   /golem ritual                 show cosmetic settings
 *   /golem ritual particles &lt;low|medium|high&gt;
 *   /golem ritual effects &lt;on|off&gt;
 *   /golem ritual shake &lt;off|weak|normal|strong&gt;
 * </pre>
 */
public final class GolemCommand {

	private GolemCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess,
				environment) -> build(dispatcher));
	}

	private static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Spec 11.9.3: /golem is a player self-service command, so it needs no
		// permission gate. On 26.2 that means omitting `requires(...)`
		// entirely: the old int-level API no longer exists (PermissionSet /
		// Permission replaced it), and an unguarded node is exactly
		// "everyone may run this".
		dispatcher.register(Commands.literal("golem")
				.executes(ctx -> overview(ctx.getSource()))
				.then(Commands.literal("covenant")
						.executes(ctx -> covenant(ctx.getSource()))
						.then(Commands.literal("capacity")
								.executes(ctx -> capacity(ctx.getSource())))
						.then(Commands.literal("slots")
								.executes(ctx -> covenant(ctx.getSource()))))
				.then(Commands.literal("anchor")
						.executes(ctx -> ownAnchors(ctx.getSource()))
						.then(Commands.literal("list")
								.executes(ctx -> anchorList(ctx.getSource())))
						.then(Commands.argument("family",
										StringArgumentType.word())
								.suggests((ctx, builder) -> {
									for (String family : Anchors.families()) {
										builder.suggest(family);
									}
									return builder.buildFuture();
								})
								.executes(ctx -> anchorFamily(ctx.getSource(),
										StringArgumentType.getString(ctx,
												"family")))))
				.then(Commands.literal("bond")
						.executes(ctx -> bond(ctx.getSource())))
				.then(Commands.literal("trial")
						.executes(ctx -> trial(ctx.getSource())))
				.then(Commands.literal("resonance")
						.executes(ctx -> resonance(ctx.getSource())))
				.then(Commands.literal("ritual")
						.executes(ctx -> settings(ctx.getSource()))
						.then(Commands.literal("particles")
								.then(Commands.argument("quality",
												StringArgumentType.word())
										.suggests((ctx, builder) -> {
											for (String q : List.of("low",
													"medium", "high")) {
												builder.suggest(q);
											}
											return builder.buildFuture();
										})
										.executes(ctx -> setParticles(
												ctx.getSource(),
												StringArgumentType.getString(
														ctx, "quality")))))
						.then(Commands.literal("effects")
								.then(Commands.argument("state",
												StringArgumentType.word())
										.suggests((ctx, builder) -> {
											for (String s : List.of("on",
													"off")) {
												builder.suggest(s);
											}
											return builder.buildFuture();
										})
										.executes(ctx -> setEffects(
												ctx.getSource(),
												StringArgumentType.getString(
														ctx, "state")))))
						.then(Commands.literal("shake")
								.then(Commands.argument("level",
												StringArgumentType.word())
										.suggests((ctx, builder) -> {
											for (RitualEngine.ShakeLevel l
													: RitualEngine.ShakeLevel
															.values()) {
												builder.suggest(
														l.name().toLowerCase());
											}
											return builder.buildFuture();
										})
										.executes(ctx -> setShake(
												ctx.getSource(),
												StringArgumentType.getString(
														ctx, "level")))))));
	}

	// ------------------------------------------------------------------
	// /golem and /golem covenant
	// ------------------------------------------------------------------

	private static int overview(CommandSourceStack source) {
		if (!requirePlayer(source)) {
			return 0;
		}
		head(source);
		covenant(source);
		bond(source);
		return 1;
	}

	private static int covenant(CommandSourceStack source) {
		if (!requirePlayer(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		head(source);

		for (CovenantTier tier : CovenantTier.values()) {
			int used = switch (tier) {
				case A -> SummonManager.getActiveA(player);
				case B -> SummonManager.getActiveB(player);
				case C -> SummonManager.getActiveC(player);
			};
			int limit = SummonManager.slotLimit(player, tier);
			ChatFormatting colour = used >= limit
					? ChatFormatting.RED : ChatFormatting.GREEN;
			key(source, "golem_covenant.cmd.slots", colour,
					tierName(tier), Component.literal(used + " / " + limit));
		}

		int used = SummonManager.getUsedSoulCapacity(player);
		int cap = SummonManager.getSoulCapacity(player);
		key(source, "golem_covenant.cmd.soul_pool",
				used >= cap ? ChatFormatting.RED : ChatFormatting.AQUA,
				Component.literal(used + " / " + cap));
		return 1;
	}

	private static int capacity(CommandSourceStack source) {
		if (!requirePlayer(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		line(source, "golem_covenant.cmd.capacity_header",
				ChatFormatting.GOLD);

		int used = SummonManager.getUsedSoulCapacity(player);
		int cap = SummonManager.getSoulCapacity(player);
		key(source, "golem_covenant.cmd.soul_pool", ChatFormatting.AQUA,
				Component.literal(used + " / " + cap));

		// Per-companion breakdown, heaviest first, so the player can see what
		// to release when the pool is full (spec 4.4).
		List<CovenantData> owned = ownedData(player);
		owned.sort((a, b) -> Integer.compare(b.soulLoad(), a.soulLoad()));
		for (CovenantData data : owned) {
			key(source, "golem_covenant.cmd.capacity_row", ChatFormatting.GRAY,
					formName(data.formId()), tierName(data.tier()),
					Component.literal(String.valueOf(data.soulLoad())));
		}
		if (owned.isEmpty()) {
			line(source, "golem_covenant.cmd.no_companions",
					ChatFormatting.DARK_GRAY);
		}
		return 1;
	}

	// ------------------------------------------------------------------
	// /golem anchor
	// ------------------------------------------------------------------

	private static int ownAnchors(CommandSourceStack source) {
		if (!requirePlayer(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		line(source, "golem_covenant.cmd.anchor_header", ChatFormatting.GOLD);

		List<LivingEntity> companions = SummonManager.ownedCompanionsOf(player);
		if (companions.isEmpty()) {
			line(source, "golem_covenant.cmd.no_companions",
					ChatFormatting.DARK_GRAY);
			return 1;
		}
		for (LivingEntity companion : companions) {
			CovenantData data = companion.getAttached(
					ModAttachments.COVENANT);
			if (data == null) {
				continue;
			}
			AnchorProfile anchor = Anchors.forCompanion(data).orElse(null);
			if (anchor == null) {
				key(source, "golem_covenant.cmd.anchor_row", ChatFormatting.GRAY,
						formName(data.formId()),
						Component.translatable("golem_covenant.anchor.reserved"),
						Component.literal("0"));
				continue;
			}
			key(source, "golem_covenant.cmd.anchor_row", ChatFormatting.GRAY,
					formName(data.formId()),
					Component.translatable(anchor.nameKey().orElse(
							"golem_covenant.anchor.reserved")),
					Component.literal(String.valueOf(
							BondEngine.allowedDepth(companion))));
		}
		return 1;
	}

	private static int anchorList(CommandSourceStack source) {
		line(source, "golem_covenant.cmd.anchor_list_header",
				ChatFormatting.GOLD);
		for (Map.Entry<String, List<AnchorProfile>> entry
				: Anchors.groupedByFamily().entrySet()) {
			key(source, "golem_covenant.cmd.anchor_family", ChatFormatting.AQUA,
					familyName(entry.getKey()),
					Component.literal(String.valueOf(entry.getValue().size())));
		}
		key(source, "golem_covenant.cmd.anchor_total", ChatFormatting.GRAY,
				Component.literal(String.valueOf(Anchors.count())),
				Component.literal(String.valueOf(
						AnchorRuntime.trackedCompanions())));
		return 1;
	}

	private static int anchorFamily(CommandSourceStack source, String family) {
		List<AnchorProfile> list = Anchors.byFamily(family);
		if (list.isEmpty()) {
			msg(source, "golem_covenant.cmd.unknown_family", ChatFormatting.RED,
					Component.literal(family));
			return 0;
		}
		line(source, "golem_covenant.cmd.anchor_list_header",
				ChatFormatting.GOLD);
		for (AnchorProfile anchor : list) {
			key(source, "golem_covenant.cmd.anchor_list_row",
					ChatFormatting.GRAY,
					Component.translatable(anchor.nameKey().orElse(
							"golem_covenant.anchor.reserved")),
					Component.translatable("golem_covenant.behaviour."
							+ anchor.behaviour()));
		}
		return 1;
	}

	// ------------------------------------------------------------------
	// /golem bond and /golem resonance
	// ------------------------------------------------------------------

	private static int bond(CommandSourceStack source) {
		if (!requirePlayer(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		line(source, "golem_covenant.cmd.bond_header", ChatFormatting.GOLD);

		List<LivingEntity> companions = SummonManager.ownedCompanionsOf(player);
		if (companions.isEmpty()) {
			line(source, "golem_covenant.cmd.no_companions",
					ChatFormatting.DARK_GRAY);
			return 1;
		}
		for (LivingEntity companion : companions) {
			CovenantData data = companion.getAttached(ModAttachments.COVENANT);
			int value = data == null ? 0 : data.bond();
			ChatFormatting colour = value >= 75 ? ChatFormatting.GOLD
					: value >= 50 ? ChatFormatting.AQUA
					: value >= 25 ? ChatFormatting.GREEN
					: ChatFormatting.GRAY;
			key(source, "golem_covenant.cmd.bond_row", colour,
					formName(data == null ? "unknown" : data.formId()),
					Component.literal(String.valueOf(value)),
					Component.literal(String.valueOf(
							BondEngine.allowedDepth(companion))));
		}
		return 1;
	}

	// ------------------------------------------------------------------
	// /golem trial - the spec 4.3.3 / 11.8 第二灵魂 trial
	// ------------------------------------------------------------------

	/**
	 * The 第二灵魂试用 readout.
	 *
	 * <p>Spec 4.3.3 wants the second C seat to feel legendary, which only works
	 * if the player can see how far along they are. Each clause is printed with
	 * its own progress so the trial reads as five concrete goals rather than
	 * one opaque lock.
	 */
	private static int trial(CommandSourceStack source) {
		if (!requirePlayer(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		for (Component part : SecondSoulTrial.readout(player)) {
			source.sendSuccess(() -> part.copy()
					.withStyle(ChatFormatting.GOLD), false);
		}
		return 1;
	}

	private static int resonance(CommandSourceStack source) {
		if (!requirePlayer(source)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		line(source, "golem_covenant.cmd.resonance_header",
				ChatFormatting.GOLD);
		java.util.Set<String> active = ResonanceEngine.activeFor(player);
		if (active.isEmpty()) {
			line(source, "golem_covenant.cmd.resonance_none",
					ChatFormatting.DARK_GRAY);
			return 1;
		}
		for (String name : active) {
			key(source, "golem_covenant.cmd.resonance_row",
					ChatFormatting.LIGHT_PURPLE,
					Component.translatable("golem_covenant.resonance." + name));
		}
		return 1;
	}

	// ------------------------------------------------------------------
	// /golem ritual - the spec 13.1 cosmetic settings
	// ------------------------------------------------------------------

	private static int settings(CommandSourceStack source) {
		line(source, "golem_covenant.cmd.settings_header", ChatFormatting.GOLD);
		RitualEngine.Settings settings = RitualEngine.settings();
		key(source, "golem_covenant.cmd.setting_particles", ChatFormatting.GRAY,
				Component.translatable("golem_covenant.cmd.quality."
						+ settings.particleQualityName()));
		key(source, "golem_covenant.cmd.setting_effects", ChatFormatting.GRAY,
				Component.translatable("golem_covenant.cmd.toggle."
						+ (settings.ritualsEnabled() ? "on" : "off")));
		key(source, "golem_covenant.cmd.setting_shake", ChatFormatting.GRAY,
				Component.translatable("golem_covenant.cmd.shake."
						+ settings.shakeName()));
		key(source, "golem_covenant.cmd.setting_active", ChatFormatting.DARK_GRAY,
				Component.literal(String.valueOf(
						RitualEngine.activeCeremonies())));
		return 1;
	}

	private static int setParticles(CommandSourceStack source, String quality) {
		int level = switch (quality.toLowerCase()) {
			case "low" -> 0;
			case "medium" -> 1;
			case "high" -> 2;
			default -> -1;
		};
		if (level < 0) {
			badValue(source, quality, "low|medium|high");
			return 0;
		}
		RitualEngine.setParticleQuality(level);
		confirm(source, "golem_covenant.cmd.setting_particles",
				"golem_covenant.cmd.quality." + quality.toLowerCase());
		return 1;
	}

	private static int setEffects(CommandSourceStack source, String state) {
		boolean enabled;
		if ("on".equalsIgnoreCase(state)) {
			enabled = true;
		} else if ("off".equalsIgnoreCase(state)) {
			enabled = false;
		} else {
			badValue(source, state, "on|off");
			return 0;
		}
		RitualEngine.setRitualsEnabled(enabled);
		confirm(source, "golem_covenant.cmd.setting_effects",
				"golem_covenant.cmd.toggle." + (enabled ? "on" : "off"));
		return 1;
	}

	private static int setShake(CommandSourceStack source, String level) {
		RitualEngine.ShakeLevel parsed;
		try {
			parsed = RitualEngine.ShakeLevel.valueOf(level.toUpperCase());
		} catch (IllegalArgumentException ex) {
			badValue(source, level, "off|weak|normal|strong");
			return 0;
		}
		RitualEngine.setShakeLevel(parsed);
		confirm(source, "golem_covenant.cmd.setting_shake",
				"golem_covenant.cmd.shake." + parsed.name().toLowerCase());
		return 1;
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static List<CovenantData> ownedData(ServerPlayer player) {
		return SummonManager.ledger().values().stream()
				.filter(d -> d.active() && d.ownedBy(player.getUUID()))
				.toList();
	}

	private static boolean requirePlayer(CommandSourceStack source) {
		if (source.getPlayer() == null) {
			source.sendFailure(Component.translatable(
					"golem_covenant.cmd.players_only"));
			return false;
		}
		return true;
	}

	private static Component formName(String formId) {
		return Component.translatable("golem_covenant.form." + formId);
	}

	private static Component tierName(CovenantTier tier) {
		return Component.translatable("golem_covenant.tier." + tier.id());
	}

	private static Component familyName(String familyId) {
		return Component.translatable("golem_covenant.family." + familyId);
	}

	private static void head(CommandSourceStack source) {
		line(source, "golem_covenant.cmd.header", ChatFormatting.GOLD);
	}

	private static void line(CommandSourceStack source, String key,
			ChatFormatting colour) {
		source.sendSuccess(() -> Component.translatable(key)
				.withStyle(colour), false);
	}

	private static void key(CommandSourceStack source, String key,
			ChatFormatting colour, Component... args) {
		source.sendSuccess(() -> Component.translatable(key, (Object[]) args)
				.withStyle(colour), false);
	}

	private static void msg(CommandSourceStack source, String key,
			ChatFormatting colour, Component arg) {
		source.sendFailure(Component.translatable(key, arg)
				.withStyle(colour));
	}

	private static void badValue(CommandSourceStack source, String got,
			String expected) {
		source.sendFailure(Component.translatable("golem_covenant.cmd.bad_value",
				Component.literal(got), Component.literal(expected))
				.withStyle(ChatFormatting.RED));
	}

	/** Echoes the setting that was just changed and its new value. */
	private static void confirm(CommandSourceStack source, String settingKey,
			String valueKey) {
		key(source, "golem_covenant.cmd.set_ok", ChatFormatting.GREEN,
				Component.translatable(settingKey),
				Component.translatable(valueKey));
	}
}
