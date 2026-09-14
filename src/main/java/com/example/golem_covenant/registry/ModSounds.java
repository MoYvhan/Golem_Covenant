package com.example.golem_covenant.registry;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * Sound events for the ritual layer (spec 11.12).
 *
 * <p>Spec 5.3 requires the sound rhythm to participate in telling forms apart.
 * We therefore expose 3 base ceremony sounds (A/B/C) + 6 death-will theme
 * sounds, matching the asset budget in 11.12.1. Audio assets themselves are
 * listed in docs/AUDIO_ASSETS.md; the events below are wired so a resource
 * pack can supply the actual ogg files.
 */
public final class ModSounds {

	private ModSounds() {
	}

	/** theme key -> SoundEvent (6 will themes, spec 11.12.1). */
	private static final List<String> WILL_THEMES = List.of(
			"guard", "scout", "heal", "element", "control", "mobility");

	private static final List<SoundEvent> ALL = new ArrayList<>();
	private static SoundEvent ritualA;
	private static SoundEvent ritualB;
	private static SoundEvent ritualC;

	public static void register() {
		ritualA = reg("ritual_a");
		ritualB = reg("ritual_b");
		ritualC = reg("ritual_c");
		for (String t : WILL_THEMES) {
			reg("death_will_" + t);
		}
	}

	private static SoundEvent reg(String path) {
		Identifier id = GolemCovenantMod.id(path);
		SoundEvent ev = SoundEvent.createVariableRangeEvent(id);
		Registry.register(BuiltInRegistries.SOUND_EVENT, id, ev);
		ALL.add(ev);
		return ev;
	}

	public static SoundEvent ritual(com.example.golem_covenant.data.CovenantTier tier) {
		return switch (tier) {
			case A -> ritualA;
			case B -> ritualB;
			case C -> ritualC;
		};
	}

	public static List<SoundEvent> all() {
		return List.copyOf(ALL);
	}
}
