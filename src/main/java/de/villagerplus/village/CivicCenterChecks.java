package de.villagerplus.village;

import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Palette;
import de.villagerplus.build.ZoneAwareSites;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** World checks for centre-first civic building and plaza placement. */
public final class CivicCenterChecks {
    private CivicCenterChecks() { }
    private record Cell(BlockState state, CompoundTag data) { }

    public static int run(ServerLevel level, VillageRef ref) {
        Map<BlockPos, Cell> before = new LinkedHashMap<>();
        java.util.Optional<BlockPos> originalChest = ref.data().chest;
        java.util.Optional<BlockPos> originalBoard = ref.data().extra.board;
        BlockPos center = ref.bell().offset(40, 0, 40);
        int min = -61, max = 61;
        for (int x = min; x <= max; x++) for (int z = min; z <= max; z++) for (int y = -2; y <= 14; y++) {
            BlockPos pos = center.offset(x, y, z).immutable(); level.getChunkAt(pos);
            BlockEntity be = level.getBlockEntity(pos);
            before.put(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
        }
        try {
            for (int x = min; x <= max; x++) for (int z = min; z <= max; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int y = 0; y <= 6; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            // A civic obstruction occupies the exact map centre. Both searches must find a nearby usable plot around it.
            level.setBlock(center.below(), Blocks.COBBLESTONE.defaultBlockState(), 3);
            BlockPos townHall = ZoneAwareSites.findCentral(level, ref, Blueprints.TOWNHALL, center,
                net.minecraft.util.RandomSource.create(991), 32, -7, 7, -7, 7, 10, p -> true);
            require(townHall != null && !townHall.equals(center) && townHall.distSqr(center) < 30 * 30,
                "town hall search stays near the measured civic centre and avoids its blocked footprint");

            BlockPos plaza = Plaza.findCentralSite(level, center, ref.bell(), ref.bell().asLong());
            require(plaza != null && plaza.distSqr(center) >= 12 * 12 && plaza.distSqr(center) < 34 * 34,
                "plaza search moves around existing central construction and stays in the inner village");
            int layoutChecks = checkBroadPlazaLayout(center);
            int details = decorateWithoutFountain(level, ref, center.offset(40, 0, 0));
            int compactCenter = checkCompactCenterAccessibility(level, ref, center.offset(0, 0, 30));
            return 2 + layoutChecks + details + compactCenter;
        } finally {
            ref.data().chest = originalChest;
            ref.data().extra.board = originalBoard;
            ref.dirty();
            before.keySet().forEach(level::removeBlockEntity);
            before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
            before.forEach((pos, cell) -> {
                if (cell.data() != null) {
                    BlockEntity be = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
                    if (be != null) level.setBlockEntity(be);
                }
            });
        }
    }

    /** A steep generated roof must not become the village depot when ground-level terrain is nearby. */
    private static int checkCompactCenterAccessibility(ServerLevel level, VillageRef ref, BlockPos center) {
        ref.data().chest = java.util.Optional.empty();
        ref.data().extra.board = java.util.Optional.empty();
        ref.dirty();

        // The nearest compact sites are on two isolated platforms above the village floor.
        // A later ring contains ordinary ground and is reachable from the worker below.
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            level.setBlock(center.offset(x, 7, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
            if ((Math.abs(x) == 3 || Math.abs(z) == 3) && !(x == -3 && z == 0)) {
                for (int y = 0; y < 7; y++) level.setBlock(center.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
        for (int x = 4; x <= 6; x++) for (int z = -3; z <= 3; z++) {
            level.setBlock(center.offset(x, 3, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
            if (x == 4 || x == 6 || Math.abs(z) == 3) {
                for (int y = 0; y < 3; y++) level.setBlock(center.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }

        Villager worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        require(worker != null, "a center-placement worker spawns in the fixture");
        // Start on the open village ground outside the obstructed inner platform.
        worker.setPos(center.getX() - 12.5, center.getY(), center.getZ() + 0.5);
        level.addFreshEntity(worker);
        try {
            require(Villages.placeCompactCenter(level, ref, center, worker), "a compact village center is found near the measured midpoint");
            BlockPos chest = ref.chestPos(level);
            require(chest != null && chest.getY() <= center.getY() + 3,
                "the compact village chest avoids an isolated roof when reachable ground is nearby");
            require(hasSafeChestStand(level, worker, chest),
                "the compact village chest has a safe resident stand within interaction range");
            return 3;
        } finally {
            worker.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }

    private static boolean hasSafeChestStand(ServerLevel level, Villager worker, BlockPos chest) {
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos column = chest.offset(dx, 0, dz);
                if (!level.hasChunkAt(column)) continue;
                BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
                Vec3 safe = de.villagerplus.job.JobUtil.safeSpot(level, Vec3.atBottomCenterOf(surface));
                if (safe == null) continue;
                BlockPos stand = BlockPos.containing(safe);
                Vec3 eye = Vec3.atBottomCenterOf(stand).add(0.0, worker.getEyeHeight(), 0.0);
                if (eye.distanceToSqr(Vec3.atCenterOf(chest)) > 4.5 * 4.5) continue;
                return true;
            }
        }
        return false;
    }

    /** Civic seating starts from public stock even when the water bucket for the fountain is unavailable. */
    private static int decorateWithoutFountain(ServerLevel level, VillageRef ref, BlockPos center) {
        BlockPos testChest = center.offset(-30, 0, 0);
        level.setBlock(testChest, Blocks.CHEST.defaultBlockState(), 3);
        ref.data().chest = java.util.Optional.of(testChest);
        ref.dirty();
        Container chest = ref.chest(level);
        require(chest != null, "public stock chest is available for plaza details");
        List<ItemStack> saved = new ArrayList<>(chest.getContainerSize());
        for (int slot = 0; slot < chest.getContainerSize(); slot++) saved.add(chest.getItem(slot).copy());
        try {
            chest.clearContent();
            Palette palette = Palette.detect(level, ref.bell(), null, center.asLong(), center);
            List<net.minecraft.world.level.block.Block> materials = List.of(palette.base(), palette.floor(),
                palette.roofStair() == null ? palette.base() : palette.roofStair());
            int slot = 0;
            for (net.minecraft.world.level.block.Block material : materials) {
                if (material.asItem() == Items.AIR || chest.getContainerSize() <= slot) continue;
                chest.setItem(slot++, new ItemStack(material, 64));
            }
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(center.offset(dx, 0, dz), palette.base().defaultBlockState(), 2);
            }
            int layoutChecks = 0;
            if (palette.base() != palette.floor()) {
                int stockBefore = count(chest, palette.base().asItem());
                Plaza.SurfaceBatch outerFrame = Plaza.upgradeSurface(level, ref, center, palette, 84, java.util.Set.of());
                require(outerFrame.cursor() == 92 && !outerFrame.pending()
                    && level.getBlockState(center.offset(-10, -1, -10)).is(palette.base())
                    && level.getBlockState(center.offset(-10, -1, -3)).is(palette.base())
                    && count(chest, palette.base().asItem()) == stockBefore - 8,
                    "the broad 21-by-21 outer frame is built in a safe batch and paid from public stock");
                layoutChecks++;
            }
            boolean noWaterBucket = true;
            for (int waterSlot = 0; waterSlot < chest.getContainerSize(); waterSlot++) noWaterBucket &= !chest.getItem(waterSlot).is(Items.WATER_BUCKET);
            require(noWaterBucket, "fountain has no water-bucket funding in this fixture");

            Plaza.decorate(level, ref, null, center, level.getGameTime());
            BlockPos firstBench = center.offset(-7, 0, -4);
            require(level.getBlockState(firstBench).is(palette.roofStair())
                && !level.getBlockState(center).is(Blocks.WATER),
                "paid civic details progress while the fountain is still waiting for its own water bucket");
            return layoutChecks + 2;
        } finally {
            chest.clearContent();
            for (int slot = 0; slot < saved.size(); slot++) chest.setItem(slot, saved.get(slot));
        }
    }

    private static int checkBroadPlazaLayout(BlockPos center) {
        require(Plaza.inside(center, center.east(13), 0)
            && Plaza.inside(center, center.offset(13, 0, 9), 0)
            && !Plaza.inside(center, center.east(14), 0),
            "the plaza reserves a 27-block-wide octagonal courtyard rather than only the fountain footprint");
        require(Plaza.outerSquareBorderOffset(10, 0) && Plaza.accentPavingOffset(10, 0)
            && Plaza.basePavingOffset(10, 0) && Plaza.surfaceUpgradeOffsetSafe(10, 0),
            "the nested 21-by-21 courtyard frame is part of the paid, bounded paving design");
        boolean pathsConnected = true;
        for (int n = 0; n <= 13; n++) for (int side = -2; side <= 2; side++) {
            pathsConnected &= Plaza.basePavingOffset(n, side) && Plaza.basePavingOffset(side, n);
        }
        for (int n = 14; n <= 18; n++) for (int side = -2; side <= 2; side++) {
            pathsConnected &= Plaza.roadArmOffset(n, side) && Plaza.roadArmOffset(side, n);
        }
        require(pathsConnected, "all four five-wide approaches connect the fountain to the outside road arms");
        return 3;
    }

    private static int count(Container container, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Civic centre: " + message);
    }
}
