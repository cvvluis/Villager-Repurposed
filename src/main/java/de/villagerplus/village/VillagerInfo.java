package de.villagerplus.village;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobManager;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;

/**
 * Steckbrief eines Dorfbewohners fuer das Handelsmenue. Zeilenformat: {@code Label|Wert|Prozent}
 * (Prozent -1 = kein Balken).
 */
public final class VillagerInfo {
	private static final int MAX_PRIVATE_CHEST_ROWS = 32;

	private VillagerInfo() {
	}

	public static List<String> lines(ServerLevel level, Villager v, Player player) {
		VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
		Wellbeing.Status s = Wellbeing.of(level, v, mem);
		VillagerData data = v.getVillagerData();
		int rep = player == null ? 0 : v.getPlayerReputation(player);

		List<String> out = new ArrayList<>();
		out.add("Name|" + (mem.name.isEmpty() ? "unbekannt" : mem.name) + "|-1");
		out.add("Beruf|" + Names.profession(data.profession()) + ", Stufe " + data.level() + "|-1");
		if (v.isBaby()) {
			out.add("Alter|Kind|-1");
		}
		out.add("Fertigkeit|" + de.villagerplus.job.Skills.title(de.villagerplus.job.Skills.level(mem)) + " (Stufe " + de.villagerplus.job.Skills.level(mem) + ")|" + de.villagerplus.job.Skills.progress(mem));
		String activity = JobManager.activityOf(v, level);
		String target = v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
			.map(w -> w.getTarget().currentBlockPosition().toShortString()).orElse("kein aktives Wegziel");
		out.add("Tätigkeit|" + (activity == null ? "untätig" : activity) + "|-1");
		out.add("Ziel|" + target + "|-1");
		int hunger = 100 - s.satiety();
		out.add("Hunger|" + hunger + "%|" + hunger);
		out.add("Stimmung|" + s.moodLabel() + " (" + s.mood() + "%)|" + s.mood());
		out.add("Energie|" + s.energy() + "%|" + s.energy());
		out.add("Soziales|" + s.social() + "%|" + s.social());
		out.add("Gesundheit|" + Math.round(v.getHealth()) + " / " + Math.round(v.getMaxHealth()) + "|" + s.health());
		out.add("Haus|" + (s.home() ? "ja" + (s.chest() ? ", mit eigener Kiste" : "") : "obdachlos") + "|-1");
		out.addAll(privateChestLines(level, v.getUUID(), mem));
		out.add("Besitz|" + s.emeralds() + " Smaragde|-1");
		out.add("Ruf bei dir|" + (rep > 5 ? "gut" : rep < -5 ? "schlecht" : "neutral") + " (" + rep + ")|-1");
		int gifts = player == null ? 0 : mem.gifts.getOrDefault(player.getUUID(), 0);
		out.add("Geschenke|" + gifts + " von dir|-1");
		out.add("Arbeit|" + mem.jobsDone + " erledigt|-1");
		out.add("Gespräche|" + mem.chats + "|-1");
		VillageRef vr = Villages.find(level, v);
		if (vr != null) {
			addConstructionInfo(level, vr, out);
			String stalled = stallLine(vr.data(), level.getGameTime());
			if (stalled != null) out.add(stalled);
			String role = Market.role(vr,v); if (!role.isEmpty()) out.add("Marktrolle|"+role+"|-1");
			int pop = (int) level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(vr.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS), x -> x.isAlive() && !Guests.isGuest(x)).size();
			int score = Wealth.score(level, vr, pop);
			out.add("Dorfreichtum|" + score + " (Stufe " + Wealth.tier(score) + ")|-1");
		}
		out.add("Preise|" + priceText(Wellbeing.priceModifier(s)) + "|-1");
		return out;
	}

	private static void addConstructionInfo(ServerLevel level, VillageRef ref, List<String> out) {
		VillageData village = ref.data();
		out.addAll(constructionStatusLines(village));
		Map<String, Long> demand = new TreeMap<>();
		for (VillageData.BuildProject project : village.buildProjects) {
			if (project.active()) accumulateDemand(demand, project.wanted());
		}
		// Pre-multi-project saves retain the singleton plan and its wanted list until migration.
		if (demand.isEmpty() && village.buildProjects.isEmpty() && village.buildProjectVersion == 0 && village.hasPlan()) {
			accumulateDemand(demand, village.wanted);
		}
		if (demand.isEmpty()) {
			out.add("Fehlendes Baumaterial|keins|-1");
			return;
		}

		out.add("Fehlendes Baumaterial|" + formatMaterials(demand) + "|-1");
		Map<String, Long> publicStock = publicStock(level, ref, demand.keySet());
		out.add("Öffentlicher Vorrat|" + (publicStock.isEmpty() ? "keine passenden Baustoffe" : formatMaterials(publicStock)) + "|-1");
		Map<String, Long> toAcquire = new TreeMap<>();
		for (Map.Entry<String, Long> entry : demand.entrySet()) {
			long shortage = Math.max(0L, entry.getValue() - publicStock.getOrDefault(entry.getKey(), 0L));
			if (shortage > 0) toAcquire.put(entry.getKey(), shortage);
		}
		if (!toAcquire.isEmpty()) out.add("Im Dorf noch zu beschaffen|" + formatMaterials(toAcquire) + "|-1");
	}

	private static Map<String, Long> publicStock(ServerLevel level, VillageRef ref, Set<String> requestedIds) {
		Map<String, Long> stock = new TreeMap<>();
		List<Container> storage = Balance.storage(level, ref);
		for (String id : requestedIds) {
			var identifier = net.minecraft.resources.Identifier.tryParse(id);
			Item item = identifier == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
			if (item == null) continue;
			long count = 0;
			for (Container box : storage) {
				for (int slot = 0; slot < box.getContainerSize(); slot++) {
					ItemStack stack = box.getItem(slot);
					if (!stack.is(item)) continue;
					count = saturatingAdd(count, stack.getCount());
				}
			}
			if (count > 0) stock.put(id, count);
		}
		return stock;
	}

	private static String formatMaterials(Map<String, Long> materials) {
		List<String> entries = materials.entrySet().stream()
			.map(entry -> materialName(entry.getKey()) + " ×" + NumberFormat.getIntegerInstance(Locale.GERMANY).format(entry.getValue()))
			.toList();
		int shown = Math.min(4, entries.size());
		String summary = entries.stream().limit(shown).collect(Collectors.joining(", "));
		int remaining = entries.size() - shown;
		return remaining == 0 ? summary : summary + " … und " + remaining + " weitere";
	}

	private static String materialName(String id) {
		String german = switch (id) {
			case "minecraft:bricks" -> "Ziegel";
			case "minecraft:glass" -> "Glas";
			case "minecraft:oak_fence" -> "Eichenholzzaun";
			case "minecraft:oak_planks" -> "Eichenholzbretter";
			case "minecraft:stone" -> "Stein";
			case "minecraft:cobblestone" -> "Bruchstein";
			case "minecraft:torch" -> "Fackel";
			case "minecraft:scaffolding" -> "Gerüst";
			case "minecraft:crafting_table" -> "Werkbank";
			default -> null;
		};
		if (german != null) return german;
		var identifier = net.minecraft.resources.Identifier.tryParse(id);
		Item item = identifier == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		return item == null ? id : new ItemStack(item).getHoverName().getString();
	}

	static List<String> constructionStatusLines(VillageData village) {
		List<VillageData.BuildProject> active = village.buildProjects.stream().filter(VillageData.BuildProject::active).toList();
		int materialWaits = 0;
		int builderWaits = 0;
		if (!active.isEmpty()) {
			for (VillageData.BuildProject project : active) {
				if (!project.wanted().isEmpty() || project.materialWait().isPresent()) materialWaits++;
				if (project.ownerUnavailableSince() > 0L) builderWaits++;
			}
		} else if (village.hasPlan()) {
			if (!village.wanted.isEmpty() || village.extra.planMaterialWait.isPresent()) materialWaits = 1;
			if (village.planBuilderUnavailableSince() > 0L) builderWaits = 1;
		}

		List<String> lines = new ArrayList<>(2);
		if (materialWaits > 0) {
			lines.add("Baustatus|" + materialWaits + (materialWaits == 1
				? " Bauabschnitt wartet auf Baumaterial" : " Bauabschnitte warten auf Baumaterial") + "|-1");
		}
		if (builderWaits > 0) {
			lines.add("Baustatus|" + builderWaits + (builderWaits == 1
				? " Bauabschnitt wartet auf einen verfügbaren Baumeister" : " Bauabschnitte warten auf verfügbare Baumeister") + "|-1");
		}
		return lines;
	}

	static String stallLine(VillageData village, long now) {
		long mostRecentEligibleProgress = -1L;
		boolean hasActiveProject = false;
		boolean hasEligibleProjectWithoutTimestamp = false;
		for (VillageData.BuildProject project : village.buildProjects) {
			if (!project.active()) continue;
			hasActiveProject = true;
			// These states are intentional pauses. The material rows explain the first; the builder's
			// owner grace period handles the second. Neither is an idle construction cursor.
			if (!VillageStats.constructionStallEligible(project)) continue;
			long progress = project.progress();
			if (progress > 0L && progress <= now) mostRecentEligibleProgress = Math.max(mostRecentEligibleProgress, progress);
			else hasEligibleProjectWithoutTimestamp = true;
		}
		if (!hasActiveProject && village.hasPlan()) {
			if (!village.wanted.isEmpty() || village.extra.planMaterialWait.isPresent() || village.planBuilderUnavailableSince() > 0L) return null;
			if (village.planProgress > 0L && village.planProgress <= now) mostRecentEligibleProgress = village.planProgress;
		}
		if (hasEligibleProjectWithoutTimestamp || mostRecentEligibleProgress <= 0L) return null;
		long idleTicks = now - mostRecentEligibleProgress;
		if (idleTicks < VillageStats.CONSTRUCTION_STALL_TICKS) return null;
		return "Stillstand|" + VillageStats.formatDuration(idleTicks / 20L) + "|-1";
	}

	private static void accumulateDemand(Map<String, Long> totals, List<String> entries) {
		for (String entry : entries) {
			if (entry == null) continue;
			int split = entry.lastIndexOf('#');
			if (split <= 0) continue;
			try {
				long amount = Long.parseLong(entry.substring(split + 1));
				if (amount > 0) totals.merge(entry.substring(0, split), amount, VillagerInfo::saturatingAdd);
			} catch (NumberFormatException ignored) { }
		}
	}

	private static long saturatingAdd(long left, long right) {
		return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static List<String> privateChestLines(ServerLevel level, UUID villagerId, VillagerMemory memory) {
		if (memory.homeChest.isEmpty()) return List.of("Privattruhe|nicht gefunden|-1");
		var pos = memory.homeChest.get();
		if (!level.hasChunkAt(pos)) return List.of("Privattruhe|nicht geladen|-1");
		if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
			|| !de.villagerplus.village.Ownership.isOwnedBy(level, pos, villagerId)) {
			return List.of("Privattruhe|keine nachweislich eigene Kiste|-1");
		}
		var state = level.getBlockState(pos);
		if (state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			var otherPos = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
			if (!level.hasChunkAt(otherPos)
				|| !(level.getBlockState(otherPos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
				|| !de.villagerplus.village.Ownership.isOwnedBy(level, otherPos, villagerId)) {
				return List.of("Privattruhe|nicht vollständig als eigenes Doppelkistenpaar bestätigt|-1");
			}
		}
		net.minecraft.world.Container chest = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, pos);
		if (chest == null) return List.of("Privattruhe|nicht geladen|-1");
		List<String> contents = new ArrayList<>();
		int nonEmpty = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			var stack = chest.getItem(i);
			if (!stack.isEmpty()) {
				nonEmpty++;
				if (contents.size() < MAX_PRIVATE_CHEST_ROWS) {
					contents.add("Privattruhe|" + stack.getCount() + "× " + stack.getHoverName().getString() + "|-1");
				}
			}
		}
		if (contents.isEmpty()) return List.of("Privattruhe|leer|-1");
		if (nonEmpty > contents.size()) contents.add("Privattruhe|… und " + (nonEmpty - contents.size()) + " weitere Stapel|-1");
		return contents;
	}

	private static String priceText(double mod) {
		if (mod < 0) {
			return "Rabatt wegen guter Laune";
		} else if (mod > 0) {
			return "Aufschlag wegen schlechter Laune";
		}
		return "normal";
	}
}
