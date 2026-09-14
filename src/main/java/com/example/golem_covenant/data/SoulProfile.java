package com.example.golem_covenant.data;

import java.util.Optional;

/**
 * 灵魂档案 SoulProfile (spec 12.2).
 *
 * <p>Aggregates everything that defines one of the 186 identities:
 * {@code formId / anchorId / passive / active / anchor / ritual / deathWill /
 * limits}. Immutable and shared; per-companion mutable state lives in
 * {@link CovenantData}.
 */
public record SoulProfile(
		String formId,
		String entityId,
		String familyId,
		String variantType,
		String displayName,
		String nameEn,
		String status,
		String aDesc,
		String bActive,
		String cUpgrade,
		String ritualTheme,
		AbilityProfile abilities,
		RitualProfile ritual,
		DeathWillProfile deathWill,
		Limits limits) {

	/** Limits / budgets for a form (spec 4.2, 4.4, 11.11). */
	public record Limits(int soulLoadA, int soulLoadB, int soulLoadC,
						float aContributionCap, float bContributionCap,
						float cContributionCap) {

		public static Limits defaults() {
			return new Limits(1, 3, 8, 0.25f, 0.60f, 1.00f);
		}

		public int soulLoad(CovenantTier tier) {
			return switch (tier) {
				case A -> this.soulLoadA;
				case B -> this.soulLoadB;
				case C -> this.soulLoadC;
			};
		}
	}

	public boolean active() {
		return "active".equals(this.status);
	}

	public boolean reserved() {
		return "reserved".equals(this.status);
	}

	/** Spec 11.2.3: 160..186 stay reserved and never enter phase-one play. */
	public Optional<SoulProfile> ifActive() {
		return this.active() ? Optional.of(this) : Optional.empty();
	}

	public String translationKey(String suffix) {
		return "golem_covenant.form." + this.formId + "." + suffix;
	}
}
