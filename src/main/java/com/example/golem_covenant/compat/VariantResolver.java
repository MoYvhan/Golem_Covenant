package com.example.golem_covenant.compat;

import java.util.Locale;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.DyeColor;

/**
 * Resolves the variant discriminator for an entity.
 *
 * <p>Spec 11.1.1 records that the base mod reuses vanilla entity types, so the
 * "变种" axis (adult / baby / colour / profession / element / size /
 * archetype) has to be derived from the entity itself. This class is the
 * single place that knowledge lives.
 *
 * <p>All package paths are the Minecraft 26.2 layout, which groups animals by
 * family directory ({@code animal.wolf.Wolf}, {@code animal.equine.Horse},
 * {@code npc.villager.Villager}, {@code monster.cubemob.Slime}, ...).
 */
public final class VariantResolver {

	private VariantResolver() {
	}

	/** Wildcard used when only the entity type matters. */
	public static final String WILDCARD = "*";

	/**
	 * @return a lowercase variant key such as {@code adult}, {@code baby},
	 *         {@code color:red}, {@code profession:farmer}, {@code size:2}
	 */
	public static String resolve(Entity entity) {
		if (!(entity instanceof LivingEntity living)) {
			return WILDCARD;
		}

		// --- 幼年 (spec 7.2) ------------------------------------------------
		if (living instanceof AgeableMob ageable && ageable.isBaby()) {
			return "baby";
		}

		// --- 职业 (spec 7.3) ------------------------------------------------
		if (living instanceof Villager villager) {
			VillagerData data = villager.getVillagerData();
			return "profession:" + keyPath(data.profession(), "none");
		}

		// --- 颜色 (spec 7.1) ------------------------------------------------
		if (living instanceof Sheep sheep) {
			if (sheep.getColor() != DyeColor.WHITE) {
				return "color:" + sheep.getColor().getName();
			}
		}
		if (living instanceof Cat cat) {
			return "color:" + keyPath(cat.getVariant(), "black");
		}
		if (living instanceof Parrot parrot) {
			return "color:" + parrot.getVariant().getSerializedName();
		}
		if (living instanceof Rabbit rabbit) {
			return "color:" + rabbit.getVariant().getSerializedName();
		}
		if (living instanceof Axolotl axolotl) {
			// Spec 7.1 lists "黄金美西螈" as its own form; gold is the only
			// colour that carries a distinct covenant, the rest fold to wild.
			return "color:" + (axolotl.getVariant() == Axolotl.Variant.GOLD
					? "gold" : "wild");
		}
		if (living instanceof Horse horse) {
			return "color:" + horse.getVariant().getSerializedName();
		}
		if (living instanceof MushroomCow mooshroom) {
			return "color:" + mooshroom.getVariant().getSerializedName();
		}
		if (living instanceof Fox fox) {
			return "color:" + fox.getVariant().getSerializedName();
		}
		if (living instanceof Panda panda) {
			return "color:" + panda.getMainGene().getSerializedName();
		}

		// --- 生物群系 (spec 6.3.9 青蛙) -------------------------------------
		if (living instanceof Frog frog) {
			return "biome:" + keyPath(frog.getVariant(), "temperate");
		}

		// --- 尺寸 (spec 7.5) ------------------------------------------------
		if (living instanceof Slime slime) {
			return "size:" + slime.getSize();
		}

		// --- 元素 / 状态 (spec 7.4) -----------------------------------------
		if (living instanceof Creeper creeper && creeper.isPowered()) {
			return "element:charged";
		}
		if (living instanceof Goat goat && goat.isScreamingGoat()) {
			return "element:screaming";
		}
		if (living instanceof Mob mob && mob.isOnFire()) {
			return "element:burning";
		}

		return "adult";
	}

	/** Reads the registry path out of a {@code Holder}, with a safe fallback. */
	private static String keyPath(Holder<?> holder, String fallback) {
		return holder.unwrapKey()
				.map(ResourceKey::identifier)
				.map(Identifier::getPath)
				.orElse(fallback);
	}

	/** Normalises a variant key into the {@code variantType} used by the registry. */
	public static String variantType(String variant) {
		if (variant == null) {
			return "adult";
		}
		String v = variant.toLowerCase(Locale.ROOT);
		if (v.startsWith("color:")) {
			return "color";
		}
		if (v.startsWith("profession:")) {
			return "profession";
		}
		if (v.startsWith("biome:")) {
			return "biome";
		}
		if (v.startsWith("size:")) {
			return "size";
		}
		if (v.startsWith("element:")) {
			return "element";
		}
		if (v.equals("baby")) {
			return "baby";
		}
		return "adult";
	}

	/**
	 * Builds the registry lookup key for exactly this entity: the most specific
	 * variant first, then progressively broader fallbacks ({@code baby} →
	 * {@code adult} → {@code *}).
	 *
	 * @return an ordered array of candidate keys, most specific first
	 */
	public static String[] lookupKeys(Entity entity) {
		String variant = resolve(entity);
		String type = variantType(variant);
		// e.g. ["color:gold", "color:*", "*"]
		if ("adult".equals(variant)) {
			return new String[] { "*" };
		}
		return new String[] { variant, type + ":*", "*" };
	}
}
