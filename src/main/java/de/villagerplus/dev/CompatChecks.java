package de.villagerplus.dev;

import de.villagerplus.Compat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** Reports optional modpack integration surface without requiring those mods at compile time. */
public final class CompatChecks {
	private CompatChecks() { }

	private static final String[] OPTIONAL_MODS = {
		"farmersdelight", "chefsdelight", "morevillagers", "sereneseasons", "t_and_t", "village_taverns", "ctov"
	};

	private static final String[] OPTIONAL_BLOCKS = {
		"farmersdelight:cooking_pot", "farmersdelight:cutting_board", "farmersdelight:stove"
	};

	public static int run(ServerLevel level) {
		int checks = 0;
		for (String mod : OPTIONAL_MODS) {
			boolean present = modPresent(mod);
			System.out.println("COMPAT optional mod " + mod + "=" + (present ? "present" : "absent"));
			checks++;
		}
		for (String block : OPTIONAL_BLOCKS) {
			boolean present = BuiltInRegistries.BLOCK.containsKey(Identifier.parse(block));
			System.out.println("COMPAT optional block " + block + "=" + (present ? "present" : "absent"));
			checks++;
		}
		check(Compat.JOBS.length >= 11, "compat job tag table covers core professions"); checks++;
		var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION);
		long modded = registry.listElements().filter(h -> !h.key().identifier().getNamespace().equals("minecraft")).count();
		System.out.println("COMPAT modded villager professions=" + modded);
		check(modded >= 0, "compat scans modded professions without crashing"); checks++;
		return checks;
	}

	/** Fails when any listed dev dependency from {@code docs/modpack-dev-dependencies.md} is absent. */
	public static int runStrict(ServerLevel level) {
		int checks = 0;
		for (String mod : OPTIONAL_MODS) {
			check(modPresent(mod), "dev dependency namespace " + mod + " is loaded"); checks++;
		}
		for (String block : OPTIONAL_BLOCKS) {
			check(BuiltInRegistries.BLOCK.containsKey(Identifier.parse(block)), "block " + block + " is registered"); checks++;
		}
		check(Compat.JOBS.length >= 11, "compat job tag table covers core professions"); checks++;
		var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION);
		long modded = registry.listElements().filter(h -> !h.key().identifier().getNamespace().equals("minecraft")).count();
		check(modded > 0, "dev pack exposes at least one modded villager profession"); checks++;
		checks += professionMapping(registry);
		checks += seasonTags(level);
		checks += villageStructures(level);
		return checks;
	}

	/** Professions of More Villagers (ids read from its acquirable_job_site POI list) and Village Taverns that must exist once the mod is present. */
	private static final String[] EXPECTED_PROFESSIONS = {
		"morevillagers:oceanographer", "morevillagers:netherian", "morevillagers:woodworker", "morevillagers:enderian", "morevillagers:engineer",
		"morevillagers:florist", "morevillagers:hunter", "morevillagers:miner", "morevillagers:iceman", "morevillagers:explorer",
		"village_taverns:bartender"
	};

	/**
	 * Runtime view of the profession mapping: every modded profession is listed with the job {@link Compat#jobKey} derives (null = porter),
	 * and each expected profession of a loaded mod must be registered. The mapping itself is reported, not asserted: it is a design choice
	 * (see CLOUD-LOG.md: Compat.counterpart can replace Vanilla professions by modded ones with the same job).
	 */
	private static int professionMapping(net.minecraft.core.HolderLookup.RegistryLookup<VillagerProfession> registry) {
		int checks = 0;
		java.util.List<String> lines = new java.util.ArrayList<>();
		registry.listElements().filter(h -> !h.key().identifier().getNamespace().equals("minecraft")).forEach(h -> {
			String job = Compat.jobKey(h);
			lines.add(h.key().identifier() + " -> " + (job == null ? "porter (kein Tag/Schluesselwort)" : job));
		});
		java.util.Collections.sort(lines);
		for (String line : lines) System.out.println("COMPAT profession " + line);
		for (String id : EXPECTED_PROFESSIONS) {
			String namespace = id.substring(0, id.indexOf(':'));
			if (!modPresent(namespace)) continue;
			boolean registered = registry.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
				Identifier.parse(id))).isPresent();
			check(registered, "profession " + id + " is registered while " + namespace + " is loaded"); checks++;
		}
		for (String line : lines) {
			String job = line.substring(line.indexOf("-> ") + 3);
			check(job.startsWith("porter") || java.util.Arrays.asList(Compat.JOBS).contains(job), "modded profession maps to a known job: " + line); checks++;
		}
		return checks;
	}

	/**
	 * Villages of Towns and Towers must be part of the vanilla village structure tag (that is how bell/village detection and CTOV-style
	 * logic see them); reported with the count, asserted only when the mod is loaded.
	 */
	private static int villageStructures(ServerLevel level) {
		if (!modPresent("t_and_t")) return 0;
		var structures = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
		long members = structures.get(net.minecraft.tags.StructureTags.VILLAGE).map(t -> t.stream()
			.filter(h -> h.unwrapKey().map(k -> k.identifier().getNamespace().equals("towns_and_towers")).orElse(false)).count()).orElse(0L);
		System.out.println("COMPAT towns_and_towers village structures in #minecraft:village=" + members);
		check(members > 0, "Towns and Towers villages are members of the vanilla village structure tag"); 
		return 1;
	}

	/** Serene Seasons ships crop tags per season; with the mod loaded they must resolve to real blocks (soil for seasonal farming decisions). */
	private static int seasonTags(ServerLevel level) {
		if (!modPresent("sereneseasons")) return 0;
		int checks = 0;
		var blocks = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
		for (String season : new String[] { "spring", "summer", "autumn", "winter", "year_round" }) {
			var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
				Identifier.parse("sereneseasons:" + season + "_crops"));
			long members = blocks.get(tag).map(t -> t.stream().count()).orElse(0L);
			System.out.println("COMPAT season crop tag " + tag.location() + " members=" + members);
			check(blocks.get(tag).isPresent(), "block tag " + tag.location() + " is registered"); checks++;
		}
		return checks;
	}

	private static boolean modPresent(String mod) {
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod)) return true;
		return BuiltInRegistries.ITEM.keySet().stream().anyMatch(id -> id.getNamespace().equals(mod))
			|| BuiltInRegistries.BLOCK.keySet().stream().anyMatch(id -> id.getNamespace().equals(mod));
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Compat: " + message);
	}
}
