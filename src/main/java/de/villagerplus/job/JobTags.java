package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** Stable villager profession job tags; avoids allocating a new TagKey on every lookup. */
public final class JobTags {
	private static final Map<String, TagKey<VillagerProfession>> BY_NAME = new HashMap<>();

	private JobTags() { }

	public static TagKey<VillagerProfession> job(String job) {
		return BY_NAME.computeIfAbsent(job, key -> TagKey.create(Registries.VILLAGER_PROFESSION,
			Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/" + key)));
	}

	public static boolean has(Holder<VillagerProfession> profession, String job) {
		return profession != null && profession.is(job(job));
	}
}