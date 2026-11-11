package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Isolated in-world checks for path failure and retry; caller must use a disposable test world. */
public final class HouseConnectionChecks {
    private HouseConnectionChecks() { }

    public static int run(ServerLevel level, BlockPos origin) {
        Map<BlockPos, Cell> before = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-12, -1, -12), origin.offset(12, 5, 12))) {
            BlockPos pos = p.immutable();
            level.getChunkAt(pos);
            BlockEntity be = level.getBlockEntity(pos);
            before.put(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
        }
        try {
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int y = 1; y <= 5; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            BlockPos start = origin.offset(-5, 1, 0), bell = origin.offset(5, 1, 0);
            level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);
            var bellEntity = (net.minecraft.world.level.block.entity.BellBlockEntity) level.getBlockEntity(bell);
            var data = bellEntity.getAttachedOrCreate(de.villagerplus.VillagerPlus.VILLAGE);
            var ref = new de.villagerplus.village.VillageRef(bell, data, bellEntity);
            data.planType = Blueprints.HOUSE;
            data.planOrigin = java.util.Optional.of(origin);
            data.planStep = 250;
            data.houses = 3;
            // Surround the start on all four sides; a failed search must not alter any surface.
            for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL)
                for (int y = 1; y <= 3; y++) level.setBlock(start.relative(dir).above(y - 1), Blocks.STONE.defaultBlockState(), 3);
            Map<BlockPos, BlockState> surfaces = new LinkedHashMap<>();
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
                BlockPos p = origin.offset(x, 0, z);
                surfaces.put(p, level.getBlockState(p));
            }
            int checks = require(!Builder.connectCompletedHouse(level, ref, origin, start, 1234L),
                "blocked completed house reports failure");
            checks += require(data.planType.equals(Blueprints.HOUSE) && data.planOrigin.equals(java.util.Optional.of(origin))
                && data.planStep == 250 && data.houses == 3 && data.planProgress == 1234L,
                "blocked completion preserves house plan, origin, cursor and count");
            checks += require(surfaces.entrySet().stream().allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue())),
                "blocked route leaves every path surface unchanged");
            for (int y = 1; y <= 3; y++) level.setBlock(start.east().above(y - 1), Blocks.AIR.defaultBlockState(), 3);
            checks += require(Builder.Paths.lay(level, start, bell), "retry succeeds after opening the obstruction");
            checks += require(level.getBlockState(start.below()).is(Blocks.DIRT_PATH), "successful retry paves the entrance");

            // Reset the fallback and provide an existing path near the bell.
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++)
                level.setBlock(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            // Keep the existing road far enough from the bell that a direct bell fallback
            // cannot accidentally satisfy the road-connection assertion.
            BlockPos existingRoad = bell.east(4).below();
            level.setBlock(existingRoad, Blocks.DIRT_PATH.defaultBlockState(), 3);
            checks += require(Builder.Paths.lay(level, start, bell, ref), "existing road connection succeeds");
            checks += require(connected(level, start.below(), existingRoad),
                "new path joins the existing road through adjacent path cells");
            return checks;
        } finally {
            for (BlockPos p : before.keySet()) level.removeBlockEntity(p);
            for (BlockPos p : before.keySet()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            before.forEach((p, cell) -> level.setBlock(p, cell.state(), 3));
            before.forEach((p, cell) -> {
                if (cell.data() != null) {
                    BlockEntity be = BlockEntity.loadStatic(p, cell.state(), cell.data().copy(), level.registryAccess());
                    if (be != null) level.setBlockEntity(be);
                }
            });
        }
    }

    private static boolean connected(ServerLevel level, BlockPos start, BlockPos goal) {
        var queue = new java.util.ArrayDeque<BlockPos>();
        var seen = new java.util.HashSet<BlockPos>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() <= 4096) {
            BlockPos p = queue.remove();
            if (p.equals(goal)) return true;
            for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos next = p.relative(dir);
                if (level.getBlockState(next).is(Blocks.DIRT_PATH) && seen.add(next)) queue.add(next);
            }
        }
        return false;
    }

    private static int require(boolean condition, String message) {
        if (!condition) throw new AssertionError("HouseConnectionChecks: " + message);
        return 1;
    }

    private record Cell(BlockState state, CompoundTag data) { }
}
