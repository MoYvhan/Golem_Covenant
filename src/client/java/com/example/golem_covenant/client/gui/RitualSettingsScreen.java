package com.example.golem_covenant.client.gui;

import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.example.golem_covenant.client.RitualClientSettings;
import com.example.golem_covenant.network.ParticleQualityPayload;
import com.example.golem_covenant.network.RitualSettingsPayload;

/**
 * The 仪式特效设置 screen (spec 13.1).
 *
 * <p>Spec 13.1 lists three settings - particle quality, ritual effects on/off,
 * and screen shake - and spec 13.2.3/13.2.5 require that every strong visual
 * effect have a switch. The {@code /golem ritual ...} commands already expose
 * all three, but a command is not discoverable; this screen is the same three
 * settings with the values visible at a glance.
 *
 * <p>It never mutates engine state directly. Every change goes out as a
 * {@link ParticleQualityPayload}, and the screen only re-renders from the
 * server's echoed {@link RitualSettingsPayload} - so the GUI, the command and
 * the actual behaviour can never disagree (spec 12.4: the server decides).
 */
public class RitualSettingsScreen extends Screen {

	private static final int ROW_HEIGHT = 24;
	private static final int COL_WIDTH = 200;

	private final Screen parent;

	public RitualSettingsScreen(Screen parent) {
		super(Component.translatable("golem_covenant.screen.ritual.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// The client mirror, not the raw packet: the screen must show the
		// value the player just clicked, before the server's echo lands.
		RitualSettingsPayload current = RitualClientSettings.current();
		int cx = this.width / 2;
		int y = this.height / 2 - 60;

		this.addRenderableWidget(new StringWidget(cx - COL_WIDTH / 2, y - 34,
				COL_WIDTH, 20, this.title, this.font));

		// --- spec 13.1: 仪式特效 开 / 关 -------------------------------
		y += ROW_HEIGHT;
		this.addRenderableWidget(Button.builder(toggleLabel(
						"golem_covenant.setting.rituals",
						current.enabled()),
				b -> send(!current.enabled(), current.quality(),
						current.shakeLevel()))
				.bounds(cx - COL_WIDTH / 2, y, COL_WIDTH, 20).build());

		// --- spec 13.1: 粒子质量 低 / 中 / 高 --------------------------
		y += ROW_HEIGHT;
		this.addRenderableWidget(Button.builder(cycleLabel(
						"golem_covenant.setting.quality",
						qualityName(current.quality())),
				b -> send(current.enabled(), (current.quality() + 1) % 3,
						current.shakeLevel()))
				.bounds(cx - COL_WIDTH / 2, y, COL_WIDTH, 20).build());

		// --- spec 13.1: 屏幕震动 关 / 弱 / 普通 / 强 -------------------
		y += ROW_HEIGHT;
		this.addRenderableWidget(Button.builder(cycleLabel(
						"golem_covenant.setting.shake",
						shakeName(current.shakeLevel())),
				b -> send(current.enabled(), current.quality(),
						(current.shakeLevel() + 1) % 4))
				.bounds(cx - COL_WIDTH / 2, y, COL_WIDTH, 20).build());

		// --- spec 13.2: a short note on what the low setting keeps -----
		y += ROW_HEIGHT + 10;
		for (Component line : hintLines()) {
			this.addRenderableWidget(new StringWidget(cx - COL_WIDTH / 2, y,
					COL_WIDTH, 12, line, this.font));
			y += 12;
		}

		// --- done -----------------------------------------------------
		y += 10;
		this.addRenderableWidget(Button.builder(
						Component.translatable("gui.done"),
						b -> this.onClose())
				.bounds(cx - 50, y, 100, 20).build());
	}

	/** Spec 13.2.1: low quality keeps only the outline and the core. */
	private static List<Component> hintLines() {
		return List.of(
				Component.translatable("golem_covenant.setting.hint.low"),
				Component.translatable("golem_covenant.setting.hint.scale"));
	}

	/** Sends the wish; the server resolves it and echoes back (spec 12.4). */
	private void send(boolean enabled, int quality, int shake) {
		RitualClientSettings.request(enabled, quality, shake);
		this.rebuildWidgets();
	}

	private Component toggleLabel(String key, boolean on) {
		return Component.translatable(key).append(": ").append(
				Component.translatable(on
						? "golem_covenant.value.on"
						: "golem_covenant.value.off"));
	}

	private Component cycleLabel(String key, String valueKey) {
		return Component.translatable(key).append(": ")
				.append(Component.translatable(valueKey));
	}

	private static String qualityName(int quality) {
		return switch (quality) {
			case 0 -> "golem_covenant.value.quality.low";
			case 1 -> "golem_covenant.value.quality.medium";
			default -> "golem_covenant.value.quality.high";
		};
	}

	private static String shakeName(int shake) {
		return switch (shake) {
			case 0 -> "golem_covenant.value.shake.off";
			case 1 -> "golem_covenant.value.shake.weak";
			case 3 -> "golem_covenant.value.shake.strong";
			default -> "golem_covenant.value.shake.normal";
		};
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.parent);
		}
	}
}
