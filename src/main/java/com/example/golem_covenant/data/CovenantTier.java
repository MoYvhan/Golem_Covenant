package com.example.golem_covenant.data;

import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * 契约等级 A / B / C (spec ch.2).
 *
 * <p>Deliberately NOT an ordinal power ladder: A = 借魂 (borrow),
 * B = 灵魂觉醒 (awaken), C = 灵魂圣契 (covenant).
 */
public enum CovenantTier implements StringRepresentable {
	/** 借魂 - 10 minute temporary contract (spec 2.2). */
	A("a", "borrow", 12_000, 5, 1),
	/** 灵魂觉醒 - permanent companion (spec 2.3). */
	B("b", "awaken", -1, 3, 3),
	/** 灵魂圣契 - complete covenant (spec 2.4). */
	C("c", "covenant", -1, 1, 8);

	public static final Codec<CovenantTier> CODEC =
			StringRepresentable.fromEnum(CovenantTier::values);

	/** Wire codec: ordinal-indexed, resolved through {@link #byId}. */
	public static final StreamCodec<RegistryFriendlyByteBuf, CovenantTier> STREAM_CODEC =
			ByteBufCodecs.VAR_INT.map(CovenantTier::byOrdinalOrA, CovenantTier::ordinal)
					.cast();

	/** Default concurrent summon cap per tier (spec 4.2). */
	private final String id;
	private final String semantic;
	/** A-tier lifetime in ticks; -1 = permanent. */
	private final int lifetimeTicks;
	/** Default slot count (spec 4.2). */
	private final int defaultSlots;
	/** Soul load consumed per companion (spec 4.4). */
	private final int soulLoad;

	CovenantTier(String id, String semantic, int lifetimeTicks, int defaultSlots,
			int soulLoad) {
		this.id = id;
		this.semantic = semantic;
		this.lifetimeTicks = lifetimeTicks;
		this.defaultSlots = defaultSlots;
		this.soulLoad = soulLoad;
	}

	@Override
	public String getSerializedName() {
		return this.id;
	}

	public String id() {
		return this.id;
	}

	public String semantic() {
		return this.semantic;
	}

	public boolean isTemporary() {
		return this.lifetimeTicks > 0;
	}

	public int lifetimeTicks() {
		return this.lifetimeTicks;
	}

	public int defaultSlots() {
		return this.defaultSlots;
	}

	public int soulLoad() {
		return this.soulLoad;
	}

	public static Optional<CovenantTier> byId(String id) {
		if (id == null) {
			return Optional.empty();
		}
		String k = id.toLowerCase(Locale.ROOT);
		for (CovenantTier t : values()) {
			if (t.id.equals(k)) {
				return Optional.of(t);
			}
		}
		return Optional.empty();
	}

	/** Ordinal lookup that tolerates out-of-range values from the network. */
	public static CovenantTier byOrdinalOrA(int ordinal) {
		CovenantTier[] all = values();
		return ordinal >= 0 && ordinal < all.length ? all[ordinal] : A;
	}

	/** Spec 4.3: slot ceilings (A 8, B 5, C 2). */
	public int maxSlots() {
		return switch (this) {
			case A -> 8;
			case B -> 5;
			case C -> 2;
		};
	}

	/** Spec 4.3: shard cost to unlock one extra slot, 0 = not shard-buyable. */
	public int shardCostPerSlot() {
		return switch (this) {
			case A -> 8;
			case B -> 16;
			case C -> 0;
		};
	}
}
