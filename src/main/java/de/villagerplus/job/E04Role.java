package de.villagerplus.job;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** Server-authoritative profession identities that receive the E04 visual treatment. */
public enum E04Role {
	NONE,
	TRADER,
	GUARD,
	ENGINEER,
	ADVENTURER;

	private static final Map<Identifier, E04Role> BY_PROFESSION = new ConcurrentHashMap<>();

	public static E04Role fromProfession(Holder<VillagerProfession> profession) {
		if (profession == null) return NONE;
		return profession.unwrapKey()
			.map(key -> BY_PROFESSION.computeIfAbsent(key.identifier(), id -> classify(profession, id)))
			.orElse(NONE);
	}

	private static E04Role classify(Holder<VillagerProfession> profession, Identifier id) {
		if (JobTags.has(profession, "adventurer")) return ADVENTURER;
		if (JobTags.has(profession, "guard") || JobTags.has(profession, "lookout")) return GUARD;
		if (JobTags.has(profession, "rail_builder")) return ENGINEER;
		if (JobTags.has(profession, "trader") || JobTags.has(profession, "merchant")) return TRADER;
		return fromProfessionId(id);
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
}