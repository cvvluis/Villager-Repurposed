package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.net.InfoResponse;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

/** Focused world and payload checks for the villager talk/info panel. */
public final class VillagerInfoChecks {
	private VillagerInfoChecks() { }

	public static int run(ServerLevel level, ServerPlayer player, BlockPos origin) {
		level.getChunkAt(origin);
		Villager villager = EntityTypes.VILLAGER.spawn(level, origin, EntitySpawnReason.COMMAND);
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		villager.addTag("vp090_fixture");
		try {
			VillagerMemory memory = villager.getAttachedOrCreate(VillagerPlus.MEMORY);
			long now = Math.max(1L, level.getGameTime());
			memory.lastMeal = Math.max(1L, now - Config.HUNGER_AFTER / 2L);
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			Wellbeing.Status status = Wellbeing.of(level, villager, memory);
			List<String> lines = VillagerInfo.lines(level, villager, player);
			int hunger = 100 - status.satiety();
			check(lines.contains("Hunger|" + hunger + "%|" + hunger),
				"the talk payload exposes hunger as the inverse of the existing satiety meter");
			check(lines.contains("Stimmung|" + status.moodLabel() + " (" + status.mood() + "%)|" + status.mood()),
				"the talk payload exposes the live mood label, value and progress bar");
			check(lines.contains("Ziel|kein aktives Wegziel|-1")
				&& lines.stream().anyMatch(line -> line.startsWith("Hunger|"))
				&& lines.stream().anyMatch(line -> line.startsWith("Stimmung|")),
				"hunger and mood remain visible when the villager has no active walk target");
			checkAdditionalMaterialDemand(level, villager, origin);
			checkPrivateChestOwnership(level, villager, memory, origin);
			if (player == null) {
				check(lines.contains("Ruf bei dir|neutral (0)|-1"),
					"a headless talk request uses neutral reputation when no player is available");
				check(lines.contains("Geschenke|0 von dir|-1"),
					"a headless talk request uses a zero gift count when no player is available");
			}

			List<String> oversized = new ArrayList<>();
			String longValue = "\uD83D\uDE80".repeat(InfoResponse.MAX_LINE_CODE_POINTS * 2);
			for (int i = 0; i < InfoResponse.MAX_LINES + 8; i++) oversized.add("Bestand|" + longValue + "|42");
			InfoResponse bounded = new InfoResponse(7, oversized);
			check(bounded.lines().size() == InfoResponse.MAX_LINES
				&& bounded.lines().stream().allMatch(line -> line.codePointCount(0, line.length()) <= InfoResponse.MAX_LINE_CODE_POINTS),
				"the info payload caps row count and Unicode line length");
			String first = bounded.lines().get(0);
			check(first.startsWith("Bestand|") && first.endsWith("|42") && first.codePointCount(0, first.length()) <= InfoResponse.MAX_LINE_CODE_POINTS,
				"bounded long values preserve the label and progress suffix for the client renderer");
			return player == null ? 24 : 22;
		} finally {
			villager.discard();
			Wellbeing.forget(villager.getUUID());
		}
	}

	private static void checkAdditionalMaterialDemand(ServerLevel level, Villager villager, BlockPos origin) {
		BlockPos bellPos = origin.east(8).immutable();
		BlockPos publicChestPos = bellPos.south().immutable();
		level.getChunkAt(bellPos);
		level.getChunkAt(publicChestPos);
		BlockState beforeState = level.getBlockState(bellPos);
		BlockEntity beforeEntity = level.getBlockEntity(bellPos);
		CompoundTag beforeData = beforeEntity == null ? null : beforeEntity.saveWithFullMetadata(level.registryAccess());
		BlockState chestBeforeState = level.getBlockState(publicChestPos);
		BlockEntity chestBeforeEntity = level.getBlockEntity(publicChestPos);
		CompoundTag chestBeforeData = chestBeforeEntity == null ? null : chestBeforeEntity.saveWithFullMetadata(level.registryAccess());
		try {
			level.setBlock(bellPos, Blocks.BELL.defaultBlockState(), 3);
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) {
				throw new IllegalStateException("Villager info fixture could not place its temporary village bell");
			}
			VillageData data = bell.getAttachedOrCreate(VillagerPlus.VILLAGE);
			level.setBlock(publicChestPos, Blocks.CHEST.defaultBlockState(), 3);
			net.minecraft.world.Container publicChest = Lager.publicContainerAt(level, publicChestPos);
			if (publicChest == null) {
				throw new IllegalStateException("Villager info fixture could not place its temporary public stock chest");
			}
			data.chest = java.util.Optional.of(publicChestPos);
			publicChest.setItem(0, new ItemStack(Items.BRICKS, 3));
			publicChest.setItem(1, new ItemStack(Items.OAK_PLANKS, 2));
			data.buildProjects.clear();
			long projectProgress = Math.max(1L, level.getGameTime() - 72_000L);
			data.buildProjects.add(project("house", origin.east(12), List.of("minecraft:oak_planks#4", "minecraft:stone#3",
				"minecraft:glass#2", "minecraft:bricks#1200"), projectProgress));
			data.buildProjects.add(project("school", origin.east(24), List.of("minecraft:oak_fence#8", "minecraft:torch#2", "minecraft:bricks#3"), projectProgress));
			data.marketDemands.add(new VillageData.MarketDemand(java.util.Optional.empty(), "minecraft:emerald", 32));
			data.refreshWanted();
			Villages.bindResident(level, new VillageRef(bellPos, data, bell), villager, null);
			List<String> lines = VillagerInfo.lines(level, villager, null);
			InfoResponse response = new InfoResponse(villager.getId(), lines);
			check(response.lines().contains("Fehlendes Baumaterial|Ziegel ×1.203, Glas ×2, Eichenholzzaun ×8, Eichenholzbretter ×4 … und 2 weitere|-1"),
				"the construction shortage is readable German text with grouped quantities and all active project demand");
			check(response.lines().contains("Öffentlicher Vorrat|Ziegel ×3, Eichenholzbretter ×2|-1")
				&& response.lines().contains("Im Dorf noch zu beschaffen|Ziegel ×1.200, Glas ×2, Eichenholzzaun ×8, Eichenholzbretter ×2 … und 2 weitere|-1"),
				"the public village stock is shown separately from what is still missing there");
			check(response.lines().stream().filter(line -> line.startsWith("Fehlendes Baumaterial|")
				|| line.startsWith("Öffentlicher Vorrat|") || line.startsWith("Im Dorf noch zu beschaffen|"))
				.noneMatch(line -> line.contains("emerald")),
				"market demands remain separate from the construction shortage and public stock rows");
			check(response.lines().contains("Baustatus|2 Bauabschnitte warten auf Baumaterial|-1"),
				"active material requests are stated as a wait in the same readable construction report");
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null
				&& VillageStats.constructionStallLine(data.buildProjects.getFirst(), projectProgress + 72_000L) == null
				&& VillageStats.constructionWaitLines(data.buildProjects.getFirst()).contains("Wartet auf Baumaterial."),
				"a long-lived material request is a clear board wait, not a construction stall");

			data.buildProjects.clear();
			data.buildProjects.add(project("house", origin.east(12), List.of(), projectProgress));
			check(VillagerInfo.stallLine(data, projectProgress + 35_999L) == null
				&& VillageStats.constructionStallLine(data.buildProjects.getFirst(), projectProgress + 35_999L) == null,
				"the normal pause between construction steps stays below the builder's own stall timeout");
			check(("Stillstand|1 Std.|-1").equals(VillagerInfo.stallLine(data, projectProgress + 72_000L))
				&& "Kein Baufortschritt seit 1 Std.".equals(VillageStats.constructionStallLine(data.buildProjects.getFirst(), projectProgress + 72_000L)),
				"a runnable project with no progress beyond the builder timeout receives a compact German stall duration");

			data.buildProjects.clear();
			data.buildProjects.add(project("house", origin.east(12), List.of(), projectProgress, 0L,
				java.util.Optional.of("wartet auf Baumaterial")));
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null
				&& VillagerInfo.constructionStatusLines(data).contains("Baustatus|1 Bauabschnitt wartet auf Baumaterial|-1")
				&& VillageStats.constructionStallLine(data.buildProjects.getFirst(), projectProgress + 72_000L) == null
				&& VillageStats.constructionWaitLines(data.buildProjects.getFirst()).contains("Wartet: wartet auf Baumaterial"),
				"an explicit production material wait gets a clear status without a false idle duration");

			data.buildProjects.clear();
			data.buildProjects.add(project("house", origin.east(12), List.of(), projectProgress, projectProgress,
				java.util.Optional.empty()));
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null
				&& VillagerInfo.constructionStatusLines(data).contains("Baustatus|1 Bauabschnitt wartet auf einen verfügbaren Baumeister|-1")
				&& VillageStats.constructionStallLine(data.buildProjects.getFirst(), projectProgress + 72_000L) == null
				&& VillageStats.constructionWaitLines(data.buildProjects.getFirst()).contains("Wartet auf einen verfügbaren Baumeister."),
				"an unavailable project owner is shown as a reassignment wait instead of a construction stall");

			data.buildProjects.clear();
			data.buildProjects.add(project("house", origin.east(12), List.of(), projectProgress));
			data.buildProjects.add(project("school", origin.east(24), List.of(), projectProgress + 71_990L));
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null,
				"recent progress in a parallel project prevents a false village-wide stall");
			data.buildProjects.add(project("well", origin.east(36), List.of(), 0L));
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null
				&& VillageStats.constructionStallLine(data.buildProjects.getLast(), projectProgress + 72_000L) == null,
				"a project without a saved progress timestamp is never assigned a fabricated idle duration");

			data.buildProjects.clear();
			data.planType = "house";
			data.planOrigin = java.util.Optional.of(origin.east(12));
			data.planProgress = projectProgress;
			data.wanted.clear();
			data.wanted.add("minecraft:bricks#4");
			data.extra.planMaterialWait = java.util.Optional.empty();
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null
				&& VillagerInfo.constructionStatusLines(data).contains("Baustatus|1 Bauabschnitt wartet auf Baumaterial|-1")
				&& VillageStats.legacyConstructionStallLine(data, projectProgress + 72_000L) == null,
				"the legacy singleton plan preserves the same explicit material-wait status");
			data.wanted.clear();
			data.extra.planMaterialWait = java.util.Optional.of("wartet auf Baumaterial");
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null
				&& VillageStats.legacyConstructionStallLine(data, projectProgress + 72_000L) == null,
				"the legacy singleton plan does not report its saved material-wait reason as idle time");
			data.extra.planMaterialWait = java.util.Optional.empty();
			data.extra.planBuilder = java.util.Optional.of(new VillageData.Extra.BuilderPlan(
				java.util.Optional.of(java.util.UUID.randomUUID()), projectProgress));
			check(VillagerInfo.stallLine(data, projectProgress + 72_000L) == null
				&& VillageStats.legacyConstructionStallLine(data, projectProgress + 72_000L) == null,
				"a legacy plan in its builder reassignment grace period is not reported as stalled");
			data.extra.planBuilder = java.util.Optional.empty();
			check(("Stillstand|1 Std.|-1").equals(VillagerInfo.stallLine(data, projectProgress + 72_000L))
				&& "Kein Baufortschritt seit 1 Std.".equals(VillageStats.legacyConstructionStallLine(data, projectProgress + 72_000L)),
				"a legacy plan without a known wait still reports genuinely stale progress");
		} finally {
			Villages.forget(villager.getUUID());
			level.setBlock(publicChestPos, chestBeforeState, 3);
			if (chestBeforeData != null) {
				BlockEntity restored = BlockEntity.loadStatic(publicChestPos, chestBeforeState, chestBeforeData.copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			}
			level.setBlock(bellPos, beforeState, 3);
			if (beforeData != null) {
				BlockEntity restored = BlockEntity.loadStatic(bellPos, beforeState, beforeData.copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			}
		}
	}

	private static VillageData.BuildProject project(String type, BlockPos origin, List<String> wanted) {
		return project(type, origin, wanted, 1L);
	}

	private static VillageData.BuildProject project(String type, BlockPos origin, List<String> wanted, long progress) {
		return project(type, origin, wanted, progress, 0L, java.util.Optional.empty());
	}

	private static VillageData.BuildProject project(String type, BlockPos origin, List<String> wanted, long progress,
			long ownerUnavailableSince, java.util.Optional<String> materialWait) {
		return new VillageData.BuildProject(type, origin, 0, 0, 0, java.util.Optional.empty(), progress,
			java.util.Optional.empty(), ownerUnavailableSince, wanted, VillageData.ProjectHaul.empty(), materialWait,
			java.util.Optional.empty(), java.util.Optional.empty(), false, false);
	}

	private static void checkPrivateChestOwnership(ServerLevel level, Villager villager, VillagerMemory memory, BlockPos origin) {
		BlockPos firstPos = origin.north(8).immutable();
		BlockPos secondPos = firstPos.east().immutable();
		level.getChunkAt(firstPos);
		level.getChunkAt(secondPos);
		BlockState firstBefore = level.getBlockState(firstPos);
		BlockState secondBefore = level.getBlockState(secondPos);
		BlockEntity firstEntityBefore = level.getBlockEntity(firstPos);
		BlockEntity secondEntityBefore = level.getBlockEntity(secondPos);
		CompoundTag firstData = firstEntityBefore == null ? null : firstEntityBefore.saveWithFullMetadata(level.registryAccess());
		CompoundTag secondData = secondEntityBefore == null ? null : secondEntityBefore.saveWithFullMetadata(level.registryAccess());
		var oldHomeChest = memory.homeChest;
		try {
			level.setBlock(firstPos, Blocks.CHEST.defaultBlockState(), 3);
			BlockEntity foreignChest = level.getBlockEntity(firstPos);
			if (foreignChest == null || !(foreignChest instanceof net.minecraft.world.Container foreignContainer)) {
				throw new IllegalStateException("Villager info fixture could not create its temporary private chest");
			}
			foreignChest.setAttached(VillagerPlus.OWNER, java.util.UUID.randomUUID());
			foreignContainer.setItem(0, new ItemStack(Items.EMERALD, 3));
			memory.homeChest = java.util.Optional.of(firstPos);
			List<String> foreignLines = VillagerInfo.lines(level, villager, null);
			check(foreignLines.contains("Privattruhe|keine nachweislich eigene Kiste|-1")
				&& foreignLines.stream().noneMatch(line -> line.startsWith("Privattruhe|") && line.contains("Smaragd")),
				"a stale home chest pointer cannot reveal another villager's private inventory");

			// Set the paired states explicitly. A test fixture should not depend on vanilla's
			// neighbor-update ordering when both block entities are created in the same tick.
			level.setBlock(firstPos, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(secondPos, Blocks.AIR.defaultBlockState(), 3);
			BlockState leftChest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.NORTH)
				.setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
			BlockState rightChest = leftChest.setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
			level.setBlock(firstPos, leftChest, 3);
			level.setBlock(secondPos, rightChest, 3);
			BlockEntity first = level.getBlockEntity(firstPos);
			BlockEntity second = level.getBlockEntity(secondPos);
			if (first == null || second == null || level.getBlockState(firstPos).getValue(ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
				throw new IllegalStateException("Villager info fixture could not create a double chest");
			}
			first.setAttached(VillagerPlus.OWNER, villager.getUUID());
			((net.minecraft.world.Container) first).setItem(0, new ItemStack(Items.EMERALD, 3));
			memory.homeChest = java.util.Optional.of(firstPos);
			List<String> lines = VillagerInfo.lines(level, villager, null);
			check(lines.contains("Privattruhe|nicht vollständig als eigenes Doppelkistenpaar bestätigt|-1")
				&& lines.stream().noneMatch(line -> line.startsWith("Privattruhe|") && line.contains("Smaragd")),
				"a private chest report does not expose contents from a double chest with an unowned half");
		} finally {
			memory.homeChest = oldHomeChest;
			level.setBlock(firstPos, firstBefore, 3);
			level.setBlock(secondPos, secondBefore, 3);
			if (firstData != null) {
				BlockEntity restored = BlockEntity.loadStatic(firstPos, firstBefore, firstData.copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			}
			if (secondData != null) {
				BlockEntity restored = BlockEntity.loadStatic(secondPos, secondBefore, secondData.copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			}
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Villager info: " + message);
	}
}
