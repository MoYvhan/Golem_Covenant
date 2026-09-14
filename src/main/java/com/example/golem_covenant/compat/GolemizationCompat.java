package com.example.golem_covenant.compat;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import com.example.golem_covenant.GolemCovenantMod;

/**
 * 傀儡化 Mod 兼容层 - THE single contact point with the base mod (spec 11.1.2).
 *
 * <p>Interface survey findings (spec 11.1.1 "接口勘察报告"), derived by
 * inspecting {@code 傀儡化.jar} (mod id {@code copper_enchant}, version 1.0.14,
 * MC 26.2 / Fabric):
 *
 * <ul>
 *   <li>The base mod <b>does not expose a public API</b> for golemization. Its
 *       mechanics live in
 *       {@code com.example.copper_enchant.effect.CopperizationEffect}, which is
 *       a {@code MobEffect}: a mob becomes a "golem" by carrying that effect,
 *       and is finally converted into a
 *       {@code CopperStatueBlockEntity} statue.</li>
 *   <li>The only stable public surface is static utility methods:
 *       {@code CopperizationEffect.isCopperizing(LivingEntity)} and
 *       {@code CopperizationEffect.getTickProgress(LivingEntity)}.</li>
 *   <li>The base mod <b>reuses vanilla Entity types</b> (there is no custom
 *       golem entity class). Therefore, exactly as spec 11.1.1 anticipates,
 *       the FormId cannot be read from the entity and must be
 *       <b>externally mapped</b> from the entity type + variant.</li>
 * </ul>
 *
 * <p>Because the base mod has no stable API to compile against, this class
 * resolves everything <b>reflectively</b>. That gives us spec 11.1.3 "软依赖"
 * for free:
 * <ul>
 *   <li>base mod missing -> {@link #isGolemized} always returns false, and
 *       using a covenant item produces a readable message instead of a crash;</li>
 *   <li>base mod present but a form unsupported -> that formId simply is not
 *       registered (see {@link #isSupportedForm(String)}).</li>
 * </ul>
 */
public final class GolemizationCompat {

	/** Fully-qualified base-mod classes we touch. Never referenced directly. */
	private static final String CLS_EFFECT =
			"com.example.copper_enchant.effect.CopperizationEffect";
	private static final String CLS_STATUE_BE =
			"com.example.copper_enchant.blockentity.CopperStatueBlockEntity";

	private static boolean initialized = false;
	private static boolean baseModPresent = false;
	private static boolean apiAvailable = false;

	private static Method mIsCopperizing;
	private static Method mGetTickProgress;
	private static Method mStatueGetEntity;

	/**
	 * formId -> mapping to a concrete vanilla entity id. Populated from the
	 * registry (spec 11.1.4) and filtered by {@link #bindSupportedForms}.
	 */
	private static final Map<String, String> FORM_TO_ENTITY = new ConcurrentHashMap<>();

	/** Set of formIds the base mod can actually produce. */
	private static final Set<String> SUPPORTED_FORMS = ConcurrentHashMap.newKeySet();

	/** Live ownership bindings, formId-independent, keyed by entity UUID. */
	private static final Map<UUID, UUID> OWNER_BINDINGS = new ConcurrentHashMap<>();

	private GolemizationCompat() {
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	public static void init(boolean present) {
		if (initialized) {
			return;
		}
		initialized = true;
		baseModPresent = present;
		if (!present) {
			GolemCovenantMod.LOGGER.info("[compat] base mod absent - running degraded");
			return;
		}
		apiAvailable = resolveApi();
		GolemCovenantMod.LOGGER.info("[compat] base mod present, api={}", apiAvailable);
	}

	/**
	 * Reflectively resolves the base mod's static helpers.
	 *
	 * @return true when at least the primary predicate is usable
	 */
	private static boolean resolveApi() {
		Class<?> effect = tryClass(CLS_EFFECT);
		Class<?> statue = tryClass(CLS_STATUE_BE);
		boolean ok = false;
		if (effect != null) {
			mIsCopperizing = tryMethod(effect, "isCopperizing", LivingEntity.class);
			mGetTickProgress = tryMethod(effect, "getTickProgress", LivingEntity.class);
			ok = mIsCopperizing != null;
		}
		if (statue != null) {
			mStatueGetEntity = tryMethod(statue, "getEntity");
		}
		return ok;
	}

	// ------------------------------------------------------------------
	// public Compat API (spec 12.3)
	// ------------------------------------------------------------------

	/**
	 * Spec 11.1.3: when the base mod is missing this must be a safe false.
	 */
	public static boolean isGolemized(Entity entity) {
		if (!(entity instanceof LivingEntity living)) {
			return false;
		}
		if (!baseModPresent || !apiAvailable || mIsCopperizing == null) {
			return false;
		}
		try {
			Object r = mIsCopperizing.invoke(null, living);
			return r instanceof Boolean b && b;
		} catch (Throwable t) {
			degrade("isGolemized", t);
			return false;
		}
	}

	/** Copperization progress 0..100, or 0 when unavailable. */
	public static int getGolemProgress(LivingEntity living) {
		if (!baseModPresent || !apiAvailable || mGetTickProgress == null) {
			return 0;
		}
		try {
			Object r = mGetTickProgress.invoke(null, living);
			return r instanceof Integer i ? i : 0;
		} catch (Throwable t) {
			degrade("getTickProgress", t);
			return 0;
		}
	}

	/**
	 * Extracts the stored entity from a base-mod statue block entity
	 * ({@code CopperStatueBlockEntity#getEntity()}).
	 */
	public static LivingEntity getStoredStatueEntity(Object blockEntity) {
		if (blockEntity == null || mStatueGetEntity == null) {
			return null;
		}
		if (!CLS_STATUE_BE.equals(blockEntity.getClass().getName())) {
			return null;
		}
		try {
			Object r = mStatueGetEntity.invoke(blockEntity);
			return r instanceof LivingEntity le ? le : null;
		} catch (Throwable t) {
			degrade("getEntity", t);
			return null;
		}
	}

	/**
	 * Resolves the covenant FormId for an entity.
	 *
	 * <p>Since the base mod reuses vanilla entity types, the FormId is derived
	 * from the external mapping installed by the registry (spec 11.1.1.4).
	 */
	public static Optional<String> getFormId(Entity entity) {
		if (entity == null) {
			return Optional.empty();
		}
		String entityId = net.minecraft.world.entity.EntityType.getKey(entity.getType())
				.toString();
		String variant = VariantResolver.resolve(entity);
		String key = entityId + "#" + variant;
		String direct = FORM_TO_ENTITY.get(key);
		if (direct != null) {
			return Optional.of(direct);
		}
		// fall back to the entity-only key (base form)
		String base = FORM_TO_ENTITY.get(entityId + "#*");
		return Optional.ofNullable(base);
	}

	/** The entity type a formId maps to (spec 11.1.4 dynamic form list). */
	public static Optional<String> getOriginalForm(String formId) {
		return Optional.ofNullable(FORM_TO_ENTITY.get(formId));
	}

	/**
	 * Spec 11.1.3: the base mod cannot produce a form it does not know.
	 */
	public static boolean isSupportedForm(String formId) {
		return formId != null && SUPPORTED_FORMS.contains(formId);
	}

	public static Set<String> getSupportedForms() {
		return Collections.unmodifiableSet(SUPPORTED_FORMS);
	}

	/** Spec 11.1.3: base mod missing => simply no supported forms. */
	public static boolean isBaseReady() {
		return baseModPresent;
	}

	public static boolean isApiAvailable() {
		return apiAvailable;
	}

	// ------------------------------------------------------------------
	// ownership (spec 11.10 归属判定)
	// ------------------------------------------------------------------

	public static void bindOwner(Entity entity, Player player) {
		if (entity == null || player == null) {
			return;
		}
		OWNER_BINDINGS.put(entity.getUUID(), player.getUUID());
	}

	public static Optional<UUID> getBoundOwner(Entity entity) {
		if (entity == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(OWNER_BINDINGS.get(entity.getUUID()));
	}

	public static void unbindOwner(Entity entity) {
		if (entity != null) {
			OWNER_BINDINGS.remove(entity.getUUID());
		}
	}

	/**
	 * The base mod has no "restore form" entrypoint; the statue block entity
	 * itself can place its stored entity back ({@code restore(ServerLevel,pos)}).
	 * This is exposed for the C-tier revival演出 and returns false when
	 * unsupported so callers degrade instead of throwing.
	 */
	public static boolean restoreForm(Entity entity) {
		if (!baseModPresent) {
			return false;
		}
		// Restoring is performed through the statue block entity, not the
		// entity, so a bare entity cannot be restored here.
		return false;
	}

	// ------------------------------------------------------------------
	// registry binding
	// ------------------------------------------------------------------

	/** Installs the registry mapping (spec 11.1.4). */
	public static void installFormMapping(Map<String, String> mapping) {
		FORM_TO_ENTITY.clear();
		FORM_TO_ENTITY.putAll(mapping);
	}

	/**
	 * Filters registered forms against what the base mod can actually produce
	 * (spec 11.2.3 / 11.23). When the base mod is absent nothing is supported,
	 * but the registry keeps its data so the addon stays informative.
	 */
	public static void bindSupportedForms(List<String> formIds) {
		SUPPORTED_FORMS.clear();
		if (!baseModPresent) {
			return;
		}
		for (String id : formIds) {
			if (FORM_TO_ENTITY.containsKey(id)) {
				SUPPORTED_FORMS.add(id);
			}
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static Class<?> tryClass(String name) {
		try {
			return Class.forName(name);
		} catch (Throwable t) {
			return null;
		}
	}

	private static Method tryMethod(Class<?> owner, String name, Class<?>... params) {
		try {
			Method m = owner.getMethod(name, params);
			m.setAccessible(true);
			return m;
		} catch (Throwable t) {
			return null;
		}
	}

	private static void degrade(String op, Throwable t) {
		GolemCovenantMod.LOGGER.debug("[compat] {} degraded: {}", op, t.toString());
	}

	/** Exposed for the survey report / diagnostics command. */
	public static Map<String, String> survey() {
		Map<String, String> out = new LinkedHashMap<>();
		out.put("baseModPresent", String.valueOf(baseModPresent));
		out.put("apiAvailable", String.valueOf(apiAvailable));
		out.put("isCopperizing", String.valueOf(mIsCopperizing != null));
		out.put("getTickProgress", String.valueOf(mGetTickProgress != null));
		out.put("statueGetEntity", String.valueOf(mStatueGetEntity != null));
		out.put("mappedForms", String.valueOf(FORM_TO_ENTITY.size()));
		out.put("supportedForms", String.valueOf(SUPPORTED_FORMS.size()));
		return out;
	}

	/** Snapshot of owner bindings - used by persistence. */
	public static Map<UUID, UUID> ownerSnapshot() {
		return new HashMap<>(OWNER_BINDINGS);
	}
}
