package de.villagerplus.village;

import de.villagerplus.build.Blueprints;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Focused checks for localized construction shortages and elapsed stall-time wording. */
public final class VillageStatusFormatChecks {
	private VillageStatusFormatChecks() { }
	private static int checks;

	public static int run() {
		checks = 0;
		VillageData.BuildProject first = project("minecraft:birch_planks#10");
		VillageData.BuildProject second = project("minecraft:birch_planks#6");
		List<String> combined = VillageStats.combineWanted(List.of(first, second));
		require(combined.equals(List.of("minecraft:birch_planks#16")),
			"duplicate project requests combine to the exact shared material total");

		SimpleContainer publicStock = new SimpleContainer(2);
		publicStock.setItem(0, new ItemStack(Items.BIRCH_PLANKS, 5));
		List<VillageStats.MaterialNeed> needs = VillageStats.materialNeeds(combined, List.of(publicStock));
		require(needs.size() == 1 && needs.getFirst().requested() == 16
			&& needs.getFirst().available() == 5 && needs.getFirst().shortage() == 11,
			"localized material status keeps requested, public stock, and missing counts exact");
		String shortage = VillageStats.formatMaterialShortage(needs.getFirst());
		require(!shortage.contains("minecraft:") && shortage.contains("×11") && shortage.contains("5/16 bereit"),
			"the shortage uses the item display name and reports both missing and ready totals");
		String commandSummary = VillageStats.formatMaterialSummary(needs);
		require(!commandSummary.contains("minecraft:") && commandSummary.contains("×11") && commandSummary.contains("5/16 bereit"),
			"the compact summary consumed by the status command does not expose its serialized wishlist rows");
		String fallbackName = VillageStats.formatMaterialSummary(VillageStats.materialNeeds(
			List.of("examplemod:polished_copper_bricks#2"), List.of()));
		require(fallbackName.toLowerCase(java.util.Locale.ROOT).contains("polished copper bricks")
			&& !fallbackName.contains("examplemod:"),
			"an unavailable translation safely falls back to a readable item path without the namespace");
		require(VillageStats.formatMaterialShortage(new VillageStats.MaterialNeed("minecraft:stone", "Stein", 1_502, 2))
			.equals("Stein ×1.500 (2/1.502 bereit)"),
			"large material counts use readable German grouping separators");
		List<String> lines = new java.util.ArrayList<>();
		VillageStats.addShortageMaterials(lines, "Noch zu beschaffen", needs);
		require(lines.size() == 1 && lines.getFirst().startsWith("Noch zu beschaffen: ")
			&& !lines.getFirst().contains("minecraft:"),
			"the player-facing shortage line uses the item name instead of its serialized registry ID");
		List<VillageStats.MaterialNeed> groupedNeeds = VillageStats.materialNeeds(List.of(
			"minecraft:birch_planks#1", "minecraft:oak_planks#2", "minecraft:spruce_planks#3",
			"minecraft:dark_oak_planks#4", "minecraft:acacia_planks#5"), List.of());
		List<String> groupedLines = new java.util.ArrayList<>();
		VillageStats.addShortageMaterials(groupedLines, "Noch zu beschaffen", groupedNeeds);
		require(groupedLines.size() == 2 && groupedLines.getFirst().startsWith("Noch zu beschaffen: ")
			&& groupedLines.get(1).startsWith("  ") && groupedLines.stream().noneMatch(line -> line.contains("minecraft:")),
			"many missing item types are grouped into short continuation lines with localized names");
		String next = VillageStats.materialNextAction(needs);
		require(next != null && next.contains("öffentliche Dorfkiste") && !next.toLowerCase().contains("fortschritt"),
			"the next action tells the player where to put missing materials without claiming progress");

		require(VillageStats.formatDuration(7_000L).equals("1 Std. 56 Min. 40 Sek."),
			"7000 elapsed game seconds retain their exact hour, minute, and second breakdown");
		require(VillageStats.formatDuration(90_061L).equals("1 Tg. 1 Std. 1 Min. 1 Sek."),
			"longer stall durations retain all nonzero time units");
		long now = 100L + 20L * 7_000L;
		String stall = VillageStats.constructionStallLine(project(List.of(), 100L), now);
		require(stall != null && stall.equals("Kein Bau- oder Materialfortschritt seit 1 Std. 56 Min. 40 Sek. Spielzeit."),
			"stall text reports elapsed time since the last actual build or material progress in readable units");
		require(VillageStats.constructionStallLine(project(List.of(), now), now) == null
			&& VillageStats.constructionStallLine(project(List.of(), 100L), 100L + VillageStats.CONSTRUCTION_STALL_TICKS - 1) == null,
			"recent progress and ordinary work intervals do not produce a stall warning");
		require(VillageStats.constructionStallLine(project(List.of("minecraft:birch_planks#3"), 100L), now) == null,
			"a material shortage remains an explicit wait instead of being mislabeled as a stall");
		VillageData.BuildProject inactive = new VillageData.BuildProject("", BlockPos.ZERO, 0, 0, 0, Optional.empty(), 100L,
			Optional.empty(), 0L, List.of(), VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(),
			Optional.empty(), false, true);
		require(VillageStats.constructionStallLine(inactive, now) == null,
			"a completed or cleanup-only project does not appear stalled");
		VillageData idleVillage = new VillageData();
		idleVillage.planProgress = 100L;
		require(VillageStats.legacyConstructionStallLine(idleVillage, now) == null,
			"an idle village with no active plan does not display a stale construction timer");
		return checks;
	}

	private static VillageData.BuildProject project(String wanted) {
		return project(List.of(wanted), 0L);
	}

	private static VillageData.BuildProject project(List<String> wanted, long progress) {
		return new VillageData.BuildProject(Blueprints.HOUSE, BlockPos.ZERO, 0, 0, 0, Optional.empty(), progress,
			Optional.empty(), 0L, wanted, VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(),
			Optional.empty(), false, false);
	}

	private static void require(boolean condition, String message) {
		checks++;
		if (!condition) throw new IllegalStateException("Village status format checks: " + message);
	}
}
