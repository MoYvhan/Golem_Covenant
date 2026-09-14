package com.example.golem_covenant.data;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.compat.GolemizationCompat;

/**
 * Loads and indexes {@code data/golem_covenant/forms_registry.json}
 * (spec 11.2 "单一权威清单文件").
 *
 * <p>The registry is the single source of truth for all 186 designs. It is read
 * once at init from the mod's own resources. Although the file is not a
 * datapack registry, it is loaded through the same well-known path so a
 * datapack/resource pack can later override it.
 */
public final class FormsRegistry {

	private static final Map<String, SoulProfile> BY_FORM =
			new LinkedHashMap<>();
	private static final Map<String, List<SoulProfile>> BY_FAMILY =
			new LinkedHashMap<>();
	private static final Map<String, String> FORM_TO_ENTITY =
			new LinkedHashMap<>();

	/** Spec ch.6: the anchor table, keyed by anchorId. */
	private static final Map<String, AnchorProfile> ANCHORS =
			new LinkedHashMap<>();

	private static boolean loaded = false;
	private static int reservedCount = 0;

	private FormsRegistry() {
	}

	// ------------------------------------------------------------------
	// loading
	// ------------------------------------------------------------------

	public static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Identifier id = GolemCovenantMod.id("forms_registry.json");
		String path = "data/" + id.getNamespace() + "/" + id.getPath();
		try (InputStream in = FormsRegistry.class.getClassLoader()
				.getResourceAsStream(path)) {
			if (in == null) {
				GolemCovenantMod.LOGGER.error(
						"forms_registry.json missing at {} - no forms will load", path);
				return;
			}
			JsonObject root = JsonParser.parseReader(
					new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			parse(root);
			GolemCovenantMod.LOGGER.info(
					"loaded forms_registry: {} active, {} reserved, "
					+ "{} anchors (schema v{})",
					BY_FORM.size(), reservedCount, ANCHORS.size(),
					root.has("dataVersion") ? root.get("dataVersion").getAsInt() : 1);
		} catch (Exception e) {
			GolemCovenantMod.LOGGER.error("failed to load forms_registry", e);
		}
	}

	private static void parse(JsonObject root) {
		// spec ch.6: the anchor table must be read before the forms, because a
		// form resolves its behaviour through its anchorId.
		JsonObject anchors = obj(root, "anchors");
		if (anchors != null) {
			for (Map.Entry<String, JsonElement> e : anchors.entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				ANCHORS.put(e.getKey(),
						toAnchor(e.getKey(), e.getValue().getAsJsonObject()));
			}
		}

		JsonArray forms = root.getAsJsonArray("forms");
		if (forms == null) {
			return;
		}
		for (JsonElement el : forms) {
			if (!el.isJsonObject()) {
				continue;
			}
			SoulProfile profile = toProfile(el.getAsJsonObject());
			if (profile.reserved()) {
				reservedCount++;
				// 11.2.3: reserved forms are parsed but never registered.
				continue;
			}
			BY_FORM.put(profile.formId(), profile);
			BY_FAMILY.computeIfAbsent(profile.familyId(),
					k -> new ArrayList<>()).add(profile);
			FORM_TO_ENTITY.put(profile.formId(), profile.entityId());
		}
		// freezes the family lists
		BY_FAMILY.replaceAll((k, v) -> List.copyOf(v));
	}

	private static SoulProfile toProfile(JsonObject o) {
		String formId = str(o, "formId", "");
		String entityId = str(o, "entityId", "");
		String familyId = str(o, "familyId", "");
		String variantType = str(o, "variantType", "adult");
		String status = str(o, "status", "active");

		AbilityProfile abilities = new AbilityProfile(
				str(o, "bActiveId", ""),
				str(o, "bActive", ""),
				str(o, "bPassiveId", ""),
				str(o, "bPassiveName", ""),
				str(o, "bPassive", ""),
				str(o, "anchorId", ""),
				str(o, "cSecondId", ""),
				str(o, "cSecond", ""),
				str(o, "cSecondDesc", ""),
				componentListFor(familyId, variantType),
				12.0f,
				AbilityProfile.CD_MARK,
				1);

		RitualProfile ritual = toRitual(obj(o, "ritual"), str(o, "ritualTheme", ""));

		DeathWillProfile will = toWill(obj(o, "deathWillProfile"));

		return new SoulProfile(
				formId, entityId, familyId, variantType,
				str(o, "displayName", formId),
				str(o, "nameEn", formId),
				status,
				str(o, "aDesc", ""),
				str(o, "bActive", ""),
				str(o, "cUpgrade", ""),
				str(o, "ritualTheme", ""),
				abilities, ritual, will, SoulProfile.Limits.defaults());
	}

	/** Parses one entry of the registry's {@code anchors} block (spec ch.6). */
	private static AnchorProfile toAnchor(String id, JsonObject o) {
		return new AnchorProfile(
				id,
				str(o, "name", id),
				str(o, "familyId", ""),
				str(o, "behaviour", "reserved"),
				str(o, "specRef", ""),
				str(o, "watch", ""),
				str(o, "source", ""),
				str(o, "trigger", ""),
				str(o, "combat", ""),
				str(o, "ai", ""),
				str(o, "zone", ""),
				str(o, "interact", ""));
	}

	/** The anchor definition for an id, if the registry declared one. */
	public static Optional<AnchorProfile> anchorById(String anchorId) {
		return Optional.ofNullable(ANCHORS.get(anchorId));
	}

	/** Every declared anchor (spec 6.2: 64 across 10 families). */
	public static List<AnchorProfile> anchors() {
		return List.copyOf(ANCHORS.values());
	}

	private static RitualProfile toRitual(JsonObject o, String theme) {
		if (o == null) {
			return new RitualProfile("beast_ring", "minecraft:end_rod",
					"#FFFFFF", "cw", 2.0f, 36, 100, "core_orb", theme, "", 2);
		}
		return new RitualProfile(
				str(o, "geometry", "beast_ring"),
				str(o, "particleType", "minecraft:end_rod"),
				str(o, "particleColor", "#FFFFFF"),
				str(o, "rotation", "cw"),
				o.has("radius") ? o.get("radius").getAsFloat() : 2.0f,
				intOf(o, "durationB", 36),
				intOf(o, "durationC", 100),
				str(o, "coreShape", "core_orb"),
				str(o, "symbol", theme),
				str(o, "soundPattern", ""),
				intOf(o, "layerCount", 2));
	}

	private static DeathWillProfile toWill(JsonObject o) {
		if (o == null) {
			return new DeathWillProfile("resistance", 0, 200, 6000, true, true,
					true, "");
		}
		return new DeathWillProfile(
				str(o, "effect", "resistance"),
				intOf(o, "amplifier", 0),
				intOf(o, "durationTicks", 200),
				intOf(o, "cooldownTicks", 6000),
				boolOf(o, "once", true),
				boolOf(o, "refreshOnly", true),
				boolOf(o, "ownerOnly", true),
				str(o, "triggerCondition", ""));
	}

	/**
	 * Derives the ability component list from family + variant, so the 186
	 * designs share 20 components instead of 186 bespoke classes (spec 12.6).
	 */
	private static List<String> componentListFor(String familyId, String variantType) {
		List<String> out = new ArrayList<>(List.of("follow", "protect", "mark",
				"death_will"));
		switch (familyId) {
			case "zombie" -> out.addAll(List.of("intercept", "taunt"));
			case "arthropod" -> out.addAll(List.of("area", "chain"));
			case "animal" -> out.addAll(List.of("dash", "predict"));
			case "mount" -> out.addAll(List.of("dash", "resource"));
			case "snow" -> out.addAll(List.of("area", "reflect"));
			case "environment" -> out.addAll(List.of("area", "elemental"));
			case "aquatic" -> out.addAll(List.of("heal_pulse", "elemental"));
			case "construct" -> out.addAll(List.of("area", "intercept"));
			case "nether_end" -> out.addAll(List.of("teleport", "elemental"));
			case "special" -> out.addAll(List.of("chain", "elemental"));
			default -> out.add("area");
		}
		switch (variantType) {
			case "baby" -> out.addAll(List.of("summon", "resource"));
			case "profession" -> out.addAll(List.of("purify", "exp_echo"));
			case "color" -> out.addAll(List.of("detect", "elemental"));
			case "element" -> out.addAll(List.of("elemental", "reflect"));
			case "size" -> out.addAll(List.of("dash", "area"));
			case "archetype" -> out.addAll(List.of("reflect", "chain"));
			case "biome" -> out.addAll(List.of("area", "elemental"));
			default -> out.add("light");
		}
		return List.copyOf(out);
	}

	// ------------------------------------------------------------------
	// accessors
	// ------------------------------------------------------------------

	public static Optional<SoulProfile> byFormId(String formId) {
		return Optional.ofNullable(BY_FORM.get(formId));
	}

	public static Optional<SoulProfile> byEntityAndVariant(String entityId, String variant) {
		for (SoulProfile p : BY_FORM.values()) {
			if (p.entityId().equals(entityId) && p.variantType().equals(variant)) {
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}

	public static List<SoulProfile> byFamily(String familyId) {
		return BY_FAMILY.getOrDefault(familyId, List.of());
	}

	public static List<SoulProfile> all() {
		return List.copyOf(BY_FORM.values());
	}

	public static int activeCount() {
		return BY_FORM.size();
	}

	public static int reservedCount() {
		return reservedCount;
	}

	public static int totalCount() {
		return BY_FORM.size() + reservedCount;
	}

	public static Map<String, String> formToEntityMap() {
		return Collections.unmodifiableMap(FORM_TO_ENTITY);
	}

	/**
	 * Spec 11.2.3 / 11.1.3: push the form list into the compat layer so
	 * unsupported forms are dropped when the base mod cannot produce them.
	 */
	public static void bindAgainstCompat() {
		GolemizationCompat.installFormMapping(FORM_TO_ENTITY);
		GolemizationCompat.bindSupportedForms(new ArrayList<>(BY_FORM.keySet()));
	}

	// ------------------------------------------------------------------
	// json helpers
	// ------------------------------------------------------------------

	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static String str(JsonObject o, String key, String def) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? def : e.getAsString();
	}

	private static int intOf(JsonObject o, String key, int def) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? def : e.getAsInt();
	}

	private static boolean boolOf(JsonObject o, String key, boolean def) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? def : e.getAsBoolean();
	}
}
