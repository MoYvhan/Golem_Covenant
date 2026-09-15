package com.example.golem_covenant.data;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Structured data carried by a B / C covenant item stack (spec 3.2).
 *
 * <p>Spec 3.2: "B / C 物品的附加信息使用 Custom Data Components 保存契约物的结构化数据".
 * This is the payload of the component registered in {@code ModComponents}. It
 * is deliberately separate from {@link CovenantData}:
 *
 * <ul>
 *   <li>{@code CovenantData} is the state of a <em>bound companion</em>, lives
 *       on the entity as a Fabric attachment, and mutates every tick (bond,
 *       cooldowns, death-will state).</li>
 *   <li>{@code CovenantItemData} is the state of an <em>item stack</em>: which
 *       form it is attuned to, how much capacity it was charged with, and who
 *       it was last given to. It is immutable value data, which is exactly what
 *       a data component is for.</li>
 * </ul>
 *
 * <p>Keeping the two apart matters for stack merging: an un-attuned item has no
 * component at all, so two fresh items still stack, while two items attuned to
 * different forms correctly refuse to merge because their components differ.
 *
 * @param formId     the attuned form ("zombie_zombie_adult"), or "" if none
 * @param capacity   capacity charges stored in this item (spec 4.3.1)
 * @param boundOwner UUID string of the last player it was issued to, or ""
 */
public record CovenantItemData(String formId, int capacity, String boundOwner) {

	/** Empty payload - the attuned-nothing state, which still stacks. */
	public static final CovenantItemData EMPTY = new CovenantItemData("", 0, "");

	public static final Codec<CovenantItemData> CODEC =
			RecordCodecBuilder.create(instance -> instance.group(
					Codec.STRING.optionalFieldOf("form", "").forGetter(CovenantItemData::formId),
					Codec.INT.optionalFieldOf("capacity", 0).forGetter(CovenantItemData::capacity),
					Codec.STRING.optionalFieldOf("owner", "").forGetter(CovenantItemData::boundOwner)
			).apply(instance, CovenantItemData::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, CovenantItemData> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, CovenantItemData::formId,
					ByteBufCodecs.VAR_INT, CovenantItemData::capacity,
					ByteBufCodecs.STRING_UTF8, CovenantItemData::boundOwner,
					CovenantItemData::new);

	/** True when this stack is attuned to a form. */
	public boolean attuned() {
		return !this.formId.isEmpty();
	}

	/** The attuned profile, if the form id is still present in the registry. */
	public Optional<SoulProfile> profile() {
		return this.formId.isEmpty()
				? Optional.empty()
				: FormsRegistry.byFormId(this.formId);
	}

	public CovenantItemData withForm(String newFormId) {
		return new CovenantItemData(newFormId, this.capacity, this.boundOwner);
	}

	public CovenantItemData withCapacity(int newCapacity) {
		return new CovenantItemData(this.formId, newCapacity, this.boundOwner);
	}

	/**
	 * Spec 11.14: a form removed by an update must degrade safely rather than
	 * leaving a dangling reference. Dropping an id we no longer recognise keeps
	 * the stack usable instead of resolving to a missing profile at use time.
	 */
	public CovenantItemData migrated() {
		if (this.formId.isEmpty() || FormsRegistry.byFormId(this.formId).isPresent()) {
			return this;
		}
		return new CovenantItemData("", this.capacity, this.boundOwner);
	}
}
