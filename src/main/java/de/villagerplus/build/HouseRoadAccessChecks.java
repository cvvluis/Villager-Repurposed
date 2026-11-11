package de.villagerplus.build;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.village.Market;
import de.villagerplus.village.MarketData;
import de.villagerplus.village.Roads;
import de.villagerplus.village.VillageRef;

/** Regression check that a house joins the saved village road instead of a stray dirt path. */
public final class HouseRoadAccessChecks {
	private HouseRoadAccessChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-12, -1, -12), origin.offset(12, 4, 12))) {
			BlockPos pos = p.immutable();
			level.getChunkAt(pos);
			BlockEntity entity = level.getBlockEntity(pos);
			before.put(pos, new Cell(level.getBlockState(pos), entity == null ? null
				: entity.saveWithFullMetadata(level.registryAccess())));
		}
		Villager worker = null;
		try {
			for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
				level.setBlock(origin.offset(x, -1, z), Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
				for (int y = 1; y <= 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
			}

			BlockPos bell = origin.east(5).above();
			level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);
			BellBlockEntity bellEntity = (BellBlockEntity) level.getBlockEntity(bell);
			var data = bellEntity.getAttachedOrCreate(VillagerPlus.VILLAGE);
			VillageRef ref = new VillageRef(bell, data, bellEntity);
			BlockPos plaza = bell.west(8);
			Market.save(ref, new MarketData(Optional.of(plaza), java.util.List.of(), 0L, 0L));
			bellEntity.setAttached(VillagerPlus.ROADS, Map.of("center", 9L));

			BlockPos savedRoad = plaza.below();
			BlockPos strayPath = bell.east(2).below();
			level.setBlock(savedRoad, Blocks.DIRT_PATH.defaultBlockState(), 3);
			level.setBlock(strayPath, Blocks.DIRT_PATH.defaultBlockState(), 3);
			int checks = require(Roads.trustedVillagePathCell(level, ref, savedRoad), "fixture saved-road cell is trusted");
			checks += require(!Roads.trustedVillagePathCell(level, ref, strayPath), "nearby stray dirt path is outside the saved route");

			BlockPos entrance = origin.north(8).above();
			checks += require(Builder.Paths.lay(level, entrance, bell, ref), "house entrance finds a route to the saved village road");
			checks += require(connected(level, entrance.below(), savedRoad), "new access path joins the saved village road");
			checks += require(!connected(level, entrance.below(), strayPath), "new access path does not join the nearer stray dirt path");

			BlockPos remote = origin.east(112).above();
			level.getChunkAt(remote);
			int gapChunkX = ((origin.getX() + remote.getX()) / 2) >> 4;
			int corridorChunkZ = origin.getZ() >> 4;
			checks += require(!level.hasChunk(gapChunkX, corridorChunkZ), "fixture keeps a corridor chunk unloaded before stand search");
			worker = EntityTypes.VILLAGER.spawn(level, remote, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("House road access: could not spawn unloaded-corridor worker");
			worker.addTag("vp_house_road_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.setPos(remote.getX() + 0.5, remote.getY(), remote.getZ() + 0.5);
			Ctx standContext = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref,
				level.getGameTime());
			BlockPos stand = Builder.workStand(standContext, origin, Direction.EAST,
				java.util.List.of(Step.block(origin, Blocks.STONE)));
			checks += require(stand == null && Builder.lastStandReport(worker).contains("unloaded/deferred"),
				"stand search defers when its worker-to-plot corridor is not loaded");
			checks += require(!level.hasChunk(gapChunkX, corridorChunkZ), "stand search does not load the missing corridor chunk");
			return checks;
		} finally {
			if (worker != null) {
				worker.discard();
				de.villagerplus.village.Villages.forget(worker.getUUID());
			}
			for (BlockPos p : before.keySet()) level.removeBlockEntity(p);
			for (BlockPos p : before.keySet()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
			before.forEach((p, cell) -> level.setBlock(p, cell.state(), 3));
			before.forEach((p, cell) -> {
				if (cell.nbt() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(p, cell.state(), cell.nbt().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
		}
	}

	private static boolean connected(ServerLevel level, BlockPos start, BlockPos goal) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty() && seen.size() <= 4096) {
			BlockPos current = queue.removeFirst();
			if (current.equals(goal)) return true;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos next = current.relative(direction);
				if (level.getBlockState(next).is(Blocks.DIRT_PATH) && seen.add(next)) queue.addLast(next);
			}
		}
		return false;
	}

	private static int require(boolean condition, String message) {
		if (!condition) throw new AssertionError("House road access: " + message);
		return 1;
	}

	private record Cell(BlockState state, CompoundTag nbt) { }
}
