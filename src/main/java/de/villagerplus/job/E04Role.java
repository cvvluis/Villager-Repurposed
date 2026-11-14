package de.villagerplus.job;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** Server-authoritative profession identities that receive the E04 visual treatment. */
public enum E04Role {
	NONE,
	TRADER,
	GUARD,
	ENGINEER,
	ADVENTURER;

	public static E04Role fromProfession(Holder<VillagerProfession> profession) {
		if (hasJobTag(profession, "adventurer")) return ADVENTURER;
		if (hasJobTag(profession, "guard") || hasJobTag(profession, "lookout")) return GUARD;
		if (hasJobTag(profession, "rail_builder")) return ENGINEER;
		if (hasJobTag(profession, "trader") || hasJobTag(profession, "merchant")) return TRADER;
		return profession.unwrapKey()
			.map(key -> fromProfessionId(key.identifier()))
			.orElse(NONE);
	}

	/** Classifies the registry key only; this also makes the role rules directly checkable. */
	public static E04Role fromProfessionId(Identifier id) {
		String path = id.getPath().toLowerCase(java.util.Locale.ROOT);
		if (containsAny(path, "adventurer", "explorer", "ranger")) return ADVENTURER;
		if (containsAny(path, "guard", "lookout", "sentinel")) return GUARD;
		if (containsAny(path, "rail", "train", "locomotive", "conductor", "engineer")) return ENGINEER;
		if (containsAny(path, "trader", "merchant")) return TRADER;
		return NONE;
	}

	public String textureName() {
		return switch (this) {
			case TRADER -> "trader";
			case GUARD -> "guard";
			case ENGINEER -> "engineer";
			case ADVENTURER -> "adventurer";
			case NONE -> "";
		};
	}

	private static boolean containsAny(String value, String... needles) {
		for (String needle : needles) {
			if (value.contains(needle)) return true;
		}
		return false;
	}

	private static boolean hasJobTag(Holder<VillagerProfession> profession, String job) {
		TagKey<VillagerProfession> tag = TagKey.create(Registries.VILLAGER_PROFESSION,
			Identifier.fromNamespaceAndPath("villagerplus", "job/" + job));
		return profession.is(tag);
	}
}
