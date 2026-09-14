package com.example.golem_covenant.anchor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.golem_covenant.data.AnchorProfile;
import com.example.golem_covenant.data.CovenantData;
import com.example.golem_covenant.data.FormsRegistry;
import com.example.golem_covenant.data.SoulProfile;

/**
 * Anchor lookup index (spec ch.6 + 11.2).
 *
 * <p>The {@code anchors} block of {@code forms_registry.json} is the single
 * authoritative definition of the 64 anchors. This class indexes it once so the
 * runtime can resolve an anchor from three different directions:
 * <ul>
 *   <li>by anchor id ({@link #byId});</li>
 *   <li>by a form ({@link #byFormId});</li>
 *   <li>by a live companion's {@link CovenantData} ({@link #forCompanion}).</li>
 * </ul>
 */
public final class Anchors {

	private Anchors() {
	}

	private static final Map<String, AnchorProfile> BY_ID = new LinkedHashMap<>();
	private static boolean loaded = false;

	/** Loads the anchor table from the already-parsed registry. */
	public static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		for (SoulProfile profile : FormsRegistry.all()) {
			String anchorId = profile.abilities().anchorId();
			if (anchorId == null || anchorId.isBlank()) {
				continue;
			}
			BY_ID.computeIfAbsent(anchorId,
					id -> FormsRegistry.anchorById(id).orElse(null));
		}
		// drop any nulls a partially-declared anchor may have produced
		BY_ID.values().removeIf(java.util.Objects::isNull);
	}

	/** Every declared anchor. */
	public static Map<String, AnchorProfile> all() {
		load();
		return Map.copyOf(BY_ID);
	}

	public static Optional<AnchorProfile> byId(String anchorId) {
		load();
		if (anchorId == null) {
			return Optional.empty();
		}
		if (!BY_ID.containsKey(anchorId)) {
			FormsRegistry.anchorById(anchorId)
					.ifPresent(p -> BY_ID.put(anchorId, p));
		}
		return Optional.ofNullable(BY_ID.get(anchorId));
	}

	/** The anchor a given form belongs to. */
	public static Optional<AnchorProfile> byFormId(String formId) {
		load();
		return FormsRegistry.byFormId(formId)
				.map(p -> byId(p.abilities().anchorId()).orElse(null))
				.filter(java.util.Objects::nonNull);
	}

	/** The anchor of a live companion, via its covenant data. */
	public static Optional<AnchorProfile> forCompanion(CovenantData data) {
		if (data == null) {
			return Optional.empty();
		}
		// the data carries the anchor id directly, so no registry round-trip
		Optional<AnchorProfile> direct = byId(data.anchorId());
		if (direct.isPresent()) {
			return direct;
		}
		return byFormId(data.formId());
	}

	public static int count() {
		load();
		return BY_ID.size();
	}

	/** All anchors belonging to a family (spec 6.2 grouping). */
	public static List<AnchorProfile> byFamily(String familyId) {
		load();
		return BY_ID.values().stream()
				.filter(a -> familyId.equals(a.familyId()))
				.toList();
	}
}
