package com.example.golem_covenant.data;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 灵魂锚点 profile (spec ch.6 + 12.2).
 *
 * <p>Spec 6.1 is emphatic that an anchor is <b>not</b> a buff, an attribute, a
 * weapon or a single skill. It is "这个灵魂观察世界和参与战斗的规则" - the rule
 * set by which a soul reads the world and joins a fight. This record is the
 * data half of that idea; {@code anchor.AnchorRuntime} is the behaviour half.
 *
 * <p>The seven textual dimensions below are exactly spec 6.2's dimensions 1-7.
 * Dimensions 8 (死亡遗志) and 9 (仪式结构) are carried per-form by
 * {@link DeathWillProfile} and {@link RitualProfile}, which together complete
 * the nine-dimension test enforced by {@code tools/validate_anchors.py}.
 *
 * <table>
 *   <caption>spec 6.2 nine dimensions</caption>
 *   <tr><th>#</th><th>dimension</th><th>field</th></tr>
 *   <tr><td>1</td><td>观察对象</td><td>{@link #watch}</td></tr>
 *   <tr><td>2</td><td>资源来源</td><td>{@link #source}</td></tr>
 *   <tr><td>3</td><td>触发条件</td><td>{@link #trigger}</td></tr>
 *   <tr><td>4</td><td>战斗方式</td><td>{@link #combat}</td></tr>
 *   <tr><td>5</td><td>AI 决策</td><td>{@link #ai}</td></tr>
 *   <tr><td>6</td><td>区域规则</td><td>{@link #zone}</td></tr>
 *   <tr><td>7</td><td>玩家互动</td><td>{@link #interact}</td></tr>
 *   <tr><td>8</td><td>死亡遗志</td><td>{@link DeathWillProfile}</td></tr>
 *   <tr><td>9</td><td>仪式结构</td><td>{@link RitualProfile}</td></tr>
 * </table>
 *
 * <p>{@link #behaviour} is the runtime dispatch keyword. 64 anchors map onto 62
 * behaviours, which is what keeps this system data-driven instead of one class
 * per creature (spec 12.1 "不建议写 ZombieCompanion.java").
 */
public record AnchorProfile(
		String anchorId,
		String name,
		String familyId,
		String behaviour,
		String specRef,
		String watch,
		String source,
		String trigger,
		String combat,
		String ai,
		String zone,
		String interact) {

	public static final Codec<AnchorProfile> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					Codec.STRING.fieldOf("id").forGetter(AnchorProfile::anchorId),
					Codec.STRING.optionalFieldOf("name", "").forGetter(AnchorProfile::name),
					Codec.STRING.optionalFieldOf("familyId", "").forGetter(AnchorProfile::familyId),
					Codec.STRING.optionalFieldOf("behaviour", "").forGetter(AnchorProfile::behaviour),
					Codec.STRING.optionalFieldOf("specRef", "").forGetter(AnchorProfile::specRef),
					Codec.STRING.optionalFieldOf("watch", "").forGetter(AnchorProfile::watch),
					Codec.STRING.optionalFieldOf("source", "").forGetter(AnchorProfile::source),
					Codec.STRING.optionalFieldOf("trigger", "").forGetter(AnchorProfile::trigger),
					Codec.STRING.optionalFieldOf("combat", "").forGetter(AnchorProfile::combat),
					Codec.STRING.optionalFieldOf("ai", "").forGetter(AnchorProfile::ai),
					Codec.STRING.optionalFieldOf("zone", "").forGetter(AnchorProfile::zone),
					Codec.STRING.optionalFieldOf("interact", "").forGetter(AnchorProfile::interact)
			).apply(i, AnchorProfile::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, AnchorProfile> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, AnchorProfile::anchorId,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::name,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::familyId,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::behaviour,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::specRef,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::watch,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::source,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::trigger,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::combat,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::ai,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::zone,
					ByteBufCodecs.STRING_UTF8, AnchorProfile::interact,
					AnchorProfile::new);

	/** Placeholder used when a form is outside the registered set. */
	public static AnchorProfile none() {
		return new AnchorProfile("reserved", "未实装", "reserved", "reserved",
				"", "", "", "", "", "", "", "");
	}

	public boolean implemented() {
		return !"reserved".equals(this.behaviour) && !this.behaviour.isBlank();
	}

	/** Translation key for this anchor's display name (spec 11.15.4). */
	public Optional<String> nameKey() {
		return this.anchorId == null || this.anchorId.isBlank()
				? Optional.empty()
				: Optional.of("golem_covenant.anchor." + this.anchorId);
	}

	/** How many of the seven textual dimensions differ from {@code other}. */
	public int dimensionsDifferingFrom(AnchorProfile other) {
		int n = 0;
		if (!this.watch.equals(other.watch)) n++;
		if (!this.source.equals(other.source)) n++;
		if (!this.trigger.equals(other.trigger)) n++;
		if (!this.combat.equals(other.combat)) n++;
		if (!this.ai.equals(other.ai)) n++;
		if (!this.zone.equals(other.zone)) n++;
		if (!this.interact.equals(other.interact)) n++;
		return n;
	}
}
