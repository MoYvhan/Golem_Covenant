package com.example.golem_covenant.data;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Per-companion covenant state (spec 12.2 + 11.10 multi-player + 11.14 saves).
 *
 * <p>Attached to the golemized entity through a Fabric Data Attachment so that
 * it persists and syncs (spec 12.4). All fields needed by the server-side
 * authority checks live here.
 */
public record CovenantData(
		UUID ownerUUID,
		String formId,
		String anchorId,
		CovenantTier tier,
		int bond,
		long contractTime,
		int abilityCooldown,
		DeathWillState deathWillState,
		int slotIndex,
		int soulLoad,
		boolean active,
		int dataVersion) {

	/** Current schema version (spec 11.14). */
	public static final int CURRENT_DATA_VERSION = 1;

	public enum DeathWillState {
		/** Will is armed and has not fired (spec 8.2 once-only). */
		ARMED,
		/** Will already fired - cannot fire again without re-revival. */
		SPENT,
		/** Owner was offline at death; held for the next fight (spec 11.4.3). */
		PENDING,
		/** No will (A-tier, or B-tier without will). */
		NONE
	}

	public static final Codec<DeathWillState> DEATH_WILL_CODEC =
			Codec.STRING.xmap(DeathWillState::valueOf, DeathWillState::name);

	/** Wire codec; unknown ordinals fall back to {@link DeathWillState#NONE}. */
	public static final StreamCodec<RegistryFriendlyByteBuf, DeathWillState> DEATH_WILL_STREAM_CODEC =
			ByteBufCodecs.VAR_INT.map(CovenantData::deathWillByOrdinal, DeathWillState::ordinal)
					.cast();

	private static DeathWillState deathWillByOrdinal(int ordinal) {
		DeathWillState[] all = DeathWillState.values();
		return ordinal >= 0 && ordinal < all.length ? all[ordinal] : DeathWillState.NONE;
	}

	public static final Codec<CovenantData> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					UUIDUtil.CODEC.fieldOf("owner").forGetter(CovenantData::ownerUUID),
					Codec.STRING.optionalFieldOf("form", "").forGetter(CovenantData::formId),
					Codec.STRING.optionalFieldOf("anchor", "").forGetter(CovenantData::anchorId),
					CovenantTier.CODEC.optionalFieldOf("tier", CovenantTier.A).forGetter(CovenantData::tier),
					Codec.INT.optionalFieldOf("bond", 0).forGetter(CovenantData::bond),
					Codec.LONG.optionalFieldOf("contract_time", 0L).forGetter(CovenantData::contractTime),
					Codec.INT.optionalFieldOf("cooldown", 0).forGetter(CovenantData::abilityCooldown),
					DEATH_WILL_CODEC.optionalFieldOf("death_will", DeathWillState.NONE)
							.forGetter(CovenantData::deathWillState),
					Codec.INT.optionalFieldOf("slot", -1).forGetter(CovenantData::slotIndex),
					Codec.INT.optionalFieldOf("soul_load", 0).forGetter(CovenantData::soulLoad),
					Codec.BOOL.optionalFieldOf("active", false).forGetter(CovenantData::active),
					Codec.INT.optionalFieldOf("data_version", CURRENT_DATA_VERSION)
							.forGetter(CovenantData::dataVersion)
			).apply(i, CovenantData::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, CovenantData> STREAM_CODEC =
			StreamCodec.composite(
					UUIDUtil.STREAM_CODEC, CovenantData::ownerUUID,
					ByteBufCodecs.STRING_UTF8, CovenantData::formId,
					ByteBufCodecs.STRING_UTF8, CovenantData::anchorId,
					CovenantTier.STREAM_CODEC, CovenantData::tier,
					ByteBufCodecs.VAR_INT, CovenantData::bond,
					ByteBufCodecs.VAR_LONG, CovenantData::contractTime,
					ByteBufCodecs.VAR_INT, CovenantData::abilityCooldown,
					DEATH_WILL_STREAM_CODEC, CovenantData::deathWillState,
					ByteBufCodecs.VAR_INT, CovenantData::slotIndex,
					ByteBufCodecs.VAR_INT, CovenantData::soulLoad,
					ByteBufCodecs.BOOL, CovenantData::active,
					ByteBufCodecs.VAR_INT, CovenantData::dataVersion,
					CovenantData::new);

	public static CovenantData empty() {
		return new CovenantData(ZERO_UUID, "", "", CovenantTier.A, 0, 0L, 0,
				DeathWillState.NONE, -1, 0, false, CURRENT_DATA_VERSION);
	}

	private static final UUID ZERO_UUID = new UUID(0L, 0L);

	/** Creates an initial contract for a freshly-bound companion. */
	public static CovenantData create(UUID owner, SoulProfile profile,
			CovenantTier tier, long gameTime, int slotIndex) {
		boolean hasWill = tier == CovenantTier.C;
		return new CovenantData(
				owner,
				profile.formId(),
				profile.abilities().anchorId(),
				tier,
				0,
				gameTime,
				0,
				hasWill ? DeathWillState.ARMED : DeathWillState.NONE,
				slotIndex,
				profile.limits().soulLoad(tier),
				true,
				CURRENT_DATA_VERSION);
	}

	// ------------------------------------------------------------------
	// bond (spec ch.9)
	// ------------------------------------------------------------------

	public enum BondStage {
		FIRST_PACT("初契", 0, 24),
		ACKNOWLEDGED("认主", 25, 49),
		RESONANCE("共鸣", 50, 74),
		DEEP_PACT("深契", 75, 99),
		FULL_COVENANT("完全契约", 100, 100);

		private final String label;
		private final int min;
		private final int max;

		BondStage(String label, int min, int max) {
			this.label = label;
			this.min = min;
			this.max = max;
		}

		public String label() {
			return this.label;
		}

		public static BondStage of(int bond) {
			int b = Math.clamp(bond, 0, 100);
			for (BondStage s : values()) {
				if (b >= s.min && b <= s.max) {
					return s;
				}
			}
			return FULL_COVENANT;
		}
	}

	public BondStage bondStage() {
		return BondStage.of(this.bond);
	}

	/**
	 * Spec 9.3: bond must NOT grant raw stats. It only unlocks the deep rules
	 * of the soul anchor. This exposes which depth tier is currently unlocked.
	 */
	public int anchorDepth() {
		BondStage s = bondStage();
		return switch (s) {
			case FIRST_PACT -> 0;
			case ACKNOWLEDGED -> 1;
			case RESONANCE -> 2;
			case DEEP_PACT -> 3;
			case FULL_COVENANT -> 4;
		};
	}

	public CovenantData withBond(int newBond) {
		return new CovenantData(ownerUUID, formId, anchorId, tier,
				Math.clamp(newBond, 0, 100), contractTime, abilityCooldown,
				deathWillState, slotIndex, soulLoad, active, dataVersion);
	}

	public CovenantData withCooldown(int ticks) {
		return new CovenantData(ownerUUID, formId, anchorId, tier, bond,
				contractTime, Math.max(0, ticks), deathWillState, slotIndex,
				soulLoad, active, dataVersion);
	}

	public CovenantData withDeathWill(DeathWillState state) {
		return new CovenantData(ownerUUID, formId, anchorId, tier, bond,
				contractTime, abilityCooldown, state, slotIndex, soulLoad,
				active, dataVersion);
	}

	public CovenantData withActive(boolean isActive) {
		return new CovenantData(ownerUUID, formId, anchorId, tier, bond,
				contractTime, abilityCooldown, deathWillState, slotIndex,
				soulLoad, isActive, dataVersion);
	}

	public CovenantData withTier(CovenantTier newTier, int newSoulLoad) {
		DeathWillState will = newTier == CovenantTier.C
				? (deathWillState == DeathWillState.SPENT ? DeathWillState.SPENT
						: DeathWillState.ARMED)
				: deathWillState;
		return new CovenantData(ownerUUID, formId, anchorId, newTier, bond,
				contractTime, abilityCooldown, will, slotIndex, newSoulLoad,
				active, dataVersion);
	}

	public boolean ownedBy(UUID player) {
		return ownerUUID.equals(player);
	}

	public boolean hasArmedWill() {
		return deathWillState == DeathWillState.ARMED
				|| deathWillState == DeathWillState.PENDING;
	}

	/**
	 * Spec 11.14: migrate an older payload forward, filling new fields.
	 */
	public CovenantData migrated() {
		if (dataVersion == CURRENT_DATA_VERSION) {
			return this;
		}
		return new CovenantData(ownerUUID, formId, anchorId, tier, bond,
				contractTime, abilityCooldown, deathWillState, slotIndex,
				soulLoad, active, CURRENT_DATA_VERSION);
	}

	public Optional<String> formIdOpt() {
		return formId == null || formId.isBlank() ? Optional.empty()
				: Optional.of(formId);
	}
}
