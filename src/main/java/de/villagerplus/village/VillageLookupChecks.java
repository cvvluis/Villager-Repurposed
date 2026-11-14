package de.villagerplus.village;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Regression check for stale known-bell entries left by restored development fixtures. */
public final class VillageLookupChecks {
	private VillageLookupChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		Villager villager = null;
		Villager addedVillager = null;
		int checks = 0;
		BlockPos staleBell = origin.immutable();
		BlockPos validBell = origin.east(8).immutable();
		BlockPos spawnSpot = origin.east(2).immutable();
		try {
			snapshot(level, before, staleBell.below());
			snapshot(level, before, staleBell);
			snapshot(level, before, validBell.below());
			snapshot(level, before, validBell);
			snapshot(level, before, spawnSpot.below());
			level.setBlock(staleBell.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(staleBell, Blocks.BELL.defaultBlockState(), 3);
			level.setBlock(validBell.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(validBell, Blocks.BELL.defaultBlockState(), 3);
			level.setBlock(spawnSpot.below(), Blocks.STONE.defaultBlockState(), 3);
			Villages.noteChunk(level.getChunkAt(staleBell));
			if (!(level.getBlockEntity(staleBell) instanceof net.minecraft.world.level.block.entity.BellBlockEntity)
				|| !(level.getBlockEntity(validBell) instanceof net.minecraft.world.level.block.entity.BellBlockEntity)) {
				throw new IllegalStateException("Village lookup fixture could not create both bells");
			}
			villager = EntityTypes.VILLAGER.spawn(level, spawnSpot, EntitySpawnReason.COMMAND);
			if (villager == null) throw new IllegalStateException("Village lookup fixture could not create villager");
			villager.setNoAi(true);
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> otherDimension =
				net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
					net.minecraft.resources.Identifier.fromNamespaceAndPath("villagerplus", "village_lookup_check"));
			villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
				GlobalPos.of(otherDimension, validBell));
			if (Villages.knownBellsNear(level, staleBell, 32).contains(validBell)) {
				throw new IllegalStateException("Nearby map lookup accepted a village meeting point from a different dimension");
			}
			checks++;
			villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
				GlobalPos.of(level.dimension(), validBell));
			if (!Villages.knownBellsNear(level, staleBell, 32).contains(validBell)) {
				throw new IllegalStateException("Nearby map lookup did not expose a bell with an active resident meeting point");
			}
			checks++;

			// Simulate a fixture that registers a bell and then restores its original non-bell block.
			level.setBlock(staleBell, Blocks.AIR.defaultBlockState(), 3);
			List<BlockPos> knownNearby = Villages.knownBellsNear(level, validBell, 32);
			if (knownNearby.contains(staleBell) || !Villages.knownBellsNear(level, staleBell, 32).contains(validBell)) {
				throw new IllegalStateException("Nearby map lookup retained a loaded stale bell or hid a valid neighbor");
			}
			checks++;
			villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT);
			Villages.forget(villager.getUUID());
			if (!Villages.knownBellsNear(level, staleBell, 32).contains(validBell)) {
				throw new IllegalStateException("Nearby map lookup hid a village whose only loaded resident uses the existing nearest-bell fallback");
			}
			checks++;

			VillageRef found = Villages.find(level, villager);
			if (found == null || !found.bell().equals(validBell)) {
				throw new IllegalStateException("Stale nearest bell hid farther valid village: expected=" + validBell
					+ ", found=" + (found == null ? "null" : found.bell()));
			}
			checks++;

			Villages.VillagerIndexSnapshot firstIndex = Villages.residentIndex(level);
			Villages.VillagerIndexSnapshot sameTickIndex = Villages.residentIndex(level);
			if (firstIndex != sameTickIndex) throw new IllegalStateException("Resident villager index was rebuilt twice during one game tick");
			checks++;

			List<Villager> initialResidents = Villages.residents(level, found, 16, v -> true);
			if (!initialResidents.contains(villager)) throw new IllegalStateException("Unassigned villager near its only valid bell was omitted");
			checks++;

			// Position and meeting-memory mutations must remain visible. A cross-section teleport may
			// emit entity unload/load events and legitimately invalidate the level snapshot.
			villager.setPos(validBell.getX() + 100.5, validBell.getY() + 1.0, validBell.getZ() + 0.5);
			if (Villages.residents(level, found, 16, v -> true).contains(villager)) {
				throw new IllegalStateException("Moved villager remained a nearby resident through a same-tick index hit");
			}
			checks++;
			villager.setPos(spawnSpot.getX() + 0.5, spawnSpot.getY() + 1.0, spawnSpot.getZ() + 0.5);
			villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
				GlobalPos.of(level.dimension(), staleBell));
			if (Villages.residents(level, found, 16, v -> true).contains(villager)) {
				throw new IllegalStateException("Changed meeting point remained in the previous village's resident result");
			}
			checks++;
			villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT);
			checks++;

			Villager loaded = EntityTypes.VILLAGER.spawn(level, spawnSpot.east(), EntitySpawnReason.COMMAND);
			if (loaded == null) throw new IllegalStateException("Resident index check could not add its second villager fixture");
			addedVillager = loaded;
			loaded.setNoAi(true);
			loaded.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
				GlobalPos.of(level.dimension(), validBell));
			Villages.VillagerIndexSnapshot afterLoad = Villages.residentIndex(level);
			if (afterLoad == firstIndex || !afterLoad.villagers().contains(loaded)) {
				throw new IllegalStateException("Loading a villager did not invalidate and refresh the same-tick entity index");
			}
			checks++;
			List<Villager> filtered = Villages.residents(level, found, 16, v -> v == loaded);
			if (filtered.size() != 1 || filtered.get(0) != loaded || Villages.residentIndex(level) != afterLoad) {
				throw new IllegalStateException("Resident filter was not applied to the refreshed base villager index");
			}
			checks++;

			loaded.discard();
			Villages.VillagerIndexSnapshot afterUnload = Villages.residentIndex(level);
			if (afterUnload == afterLoad || afterUnload.villagers().contains(loaded)
				|| Villages.residents(level, found, 16, v -> true).contains(loaded)) {
				throw new IllegalStateException("Unloading a villager left it in the same-tick resident index or result");
			}
			checks++;
			return checks;
		} finally {
			if (addedVillager != null) {
				Villages.forget(addedVillager.getUUID());
				addedVillager.discard();
			}
			if (villager != null) {
				Villages.forget(villager.getUUID());
				villager.discard();
			}
			restore(level, before);
		}
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		BlockPos immutable = pos.immutable();
		level.getChunkAt(immutable);
		BlockEntity entity = level.getBlockEntity(immutable);
		before.putIfAbsent(immutable, new Cell(level.getBlockState(immutable),
			entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), Block.UPDATE_ALL));
		before.forEach((pos, cell) -> {
			if (cell.nbt() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private record Cell(BlockState state, CompoundTag nbt) { }
}
