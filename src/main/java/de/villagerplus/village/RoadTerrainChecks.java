package de.villagerplus.village;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.Lighting;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.RoadBuilderJob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** In-world regression checks for terrain-aware village roads. Every touched cell is restored. */
public final class RoadTerrainChecks {
    private RoadTerrainChecks() { }

    public static int run(ServerLevel level, BlockPos origin) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        BlockPos tree = origin, placedLog = origin.east(4), water = origin.east(8), abyss = origin.east(12);
        BlockPos gap = origin.east(16), ridge = origin.east(20), protectedSpot = origin.east(24);
        BlockPos repairGap = origin.east(32);
        for (int x = -10; x <= 10; x++) snapshot(level, before, repairGap.offset(x, 0, 0), -2, 3);
        snapshot(level, before, tree, 0, 8);
        snapshot(level, before, placedLog, 0, 4);
        snapshot(level, before, water, -1, 6);
        snapshot(level, before, abyss, -1, 28);
        snapshot(level, before, gap, -2, 5);
        snapshot(level, before, ridge, -2, 5);
        snapshot(level, before, protectedSpot, -2, 5);
        try {
            level.setBlock(tree.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            for (int y = 0; y < 5; y++) level.setBlock(tree.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
            level.setBlock(tree.offset(1, 4, 0), Blocks.OAK_LEAVES.defaultBlockState(), 3);
            Roads.pavePath(level, tree.getX(), tree.getZ());
            require(level.getBlockState(tree.below()).is(Blocks.DIRT_PATH)
                && level.getBlockState(tree).isAir() && level.getBlockState(tree.above(4)).isAir(),
                "path clears a rooted, leafy natural trunk and paves its soil");

            level.setBlock(placedLog.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            level.setBlock(placedLog, Blocks.OAK_LOG.defaultBlockState(), 3);
            Roads.pavePath(level, placedLog.getX(), placedLog.getZ());
            require(level.getBlockState(placedLog).is(Blocks.OAK_LOG)
                && level.getBlockState(placedLog.below()).is(Blocks.GRASS_BLOCK),
                "path leaves isolated placed timber and its ground untouched");

            level.setBlock(water.below(), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 4; y++) level.setBlock(water.above(y), Blocks.WATER.defaultBlockState(), 3);
            Roads.pavePath(level, water.getX(), water.getZ());
            require(level.getBlockState(water.above(3)).is(Blocks.SPRUCE_PLANKS)
                && level.getBlockState(water.above(2)).is(Blocks.SPRUCE_LOG)
                && level.getBlockState(water.below()).is(Blocks.STONE),
                "shallow water gets a plank deck and log piers down to solid bed");

            level.setBlock(abyss.below(), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 27; y++) level.setBlock(abyss.above(y), Blocks.WATER.defaultBlockState(), 3);
            Roads.pavePath(level, abyss.getX(), abyss.getZ());
            require(level.getBlockState(abyss).is(Blocks.WATER)
                && level.getBlockState(abyss.above()).is(Blocks.WATER),
                "deep water without reachable footing stays unchanged");

            level.setBlock(gap.below(2), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(gap.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            Roads.pavePath(level, gap.getX(), gap.getZ(), gap.getY());
            require(level.getBlockState(gap).is(Blocks.DIRT_PATH)
                && level.getBlockState(gap.below()).is(Blocks.GRASS_BLOCK), "one-block low gap is filled into a walkable path");

            level.setBlock(ridge.below(2), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(ridge.below(), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(ridge, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            Roads.pavePath(level, ridge.getX(), ridge.getZ(), ridge.getY()-1);
            require(level.getBlockState(ridge.below()).is(Blocks.DIRT_PATH)
                && level.getBlockState(ridge).isAir(), "one-block natural ridge is cut to a walkable grade");

            level.setBlock(protectedSpot.below(2), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(protectedSpot.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            Roads.pavePath(level, protectedSpot.getX(), protectedSpot.getZ(), protectedSpot.getY());
            require(level.getBlockState(protectedSpot.below()).is(Blocks.FARMLAND)
                && level.getBlockState(protectedSpot).isAir(), "field edge is not filled or replaced");
            for (int x = -10; x <= 10; x++) {
                BlockPos p = repairGap.offset(x, 0, 0);
                level.setBlock(p.below(), Blocks.DIRT.defaultBlockState(), 3);
                for (int y = 0; y <= 3; y++) level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(repairGap.west(), Blocks.DIRT_PATH.defaultBlockState(), 3);
            level.setBlock(repairGap.east(), Blocks.DIRT_PATH.defaultBlockState(), 3);
            require(Roads.gapTerrain(level, repairGap)
                && Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "a supported one-block gap is bounded by existing path");
            level.setBlock(repairGap.east(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(2), Blocks.DIRT_PATH.defaultBlockState(), 3);
            require(Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "two supported gap cells between same-height path anchors are accepted");
            level.setBlock(repairGap.east(2), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(3), Blocks.DIRT_PATH.defaultBlockState(), 3);
            require(Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "three supported gap cells between same-height path anchors are accepted");
            level.setBlock(repairGap.east(), Blocks.DIRT.defaultBlockState(), 3);
            require(Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST)
                && Roads.gapAnchored(level, repairGap.east(2), net.minecraft.core.Direction.EAST),
                "a paid dirt cell does not prevent either remaining cell from being revalidated");
            level.setBlock(repairGap.east(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(3), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(4), Blocks.DIRT_PATH.defaultBlockState(), 3);
            require(Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "four supported gap cells between same-height path anchors are accepted");
            for (int x = 4; x < 8; x++) level.setBlock(repairGap.east(x), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(8), Blocks.DIRT_PATH.defaultBlockState(), 3);
            require(Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "eight supported gap cells remain within the bounded repair");
            level.setBlock(repairGap.east(8), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(9), Blocks.DIRT_PATH.defaultBlockState(), 3);
            require(!Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "nine-cell gaps remain outside the bounded repair");
            level.setBlock(repairGap.east(9), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(3), Blocks.DIRT_PATH.defaultBlockState(), 3);
            level.setBlock(repairGap.east().below(), Blocks.WATER.defaultBlockState(), 3);
            require(!Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "water under any gap cell blocks the entire candidate");
            level.setBlock(repairGap.east().below(), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(repairGap.east(2).above(2), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            require(!Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "built wood in another cell's headroom blocks the entire candidate");
            level.setBlock(repairGap.east(2).above(2), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(2), Blocks.CHEST.defaultBlockState(), 3);
            require(!Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST)
                && level.getBlockState(repairGap.east(2)).is(Blocks.CHEST),
                "an occupied container in the corridor is untouched and blocks the candidate");
            level.setBlock(repairGap.east(2), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(2).below(), Blocks.FARMLAND.defaultBlockState(), 3);
            require(!Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "farmland beneath any gap cell blocks the entire candidate");
            level.setBlock(repairGap.east(2).below(), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(repairGap.east(3), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.east(3).above(), Blocks.DIRT_PATH.defaultBlockState(), 3);
            require(!Roads.gapAnchored(level, repairGap, net.minecraft.core.Direction.EAST),
                "a higher anchor is not treated as a flat connection");
            level.setBlock(repairGap.above(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            require(!Roads.gapTerrain(level, repairGap), "a building obstruction blocks gap repair");
            level.setBlock(repairGap.above(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(repairGap.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            require(!Roads.gapTerrain(level, repairGap), "farmland is not support for a paid fill");
            level.setBlock(repairGap.below(), Blocks.WATER.defaultBlockState(), 3);
            require(!Roads.gapTerrain(level, repairGap), "water is not support for a paid fill");
            require(Roads.grade(8, 6) == 7, "rising route changes by at most one block");
            require(Roads.grade(2, 6) == 5, "falling route changes by at most one block");
            require(Roads.grade(6, 6) == 6, "flat route keeps its previous level");
            return 24 + paidGapJob(level, origin.east(100)) + RoadGradingChecks.run(level,origin.east(1000));
        } finally {
            before.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
    }

    /** Exercise the real road job across a four-cell gap, recreating volatile job state after each block. */
    private static int paidGapJob(ServerLevel level, BlockPos bell) {
        Map<BlockPos, Cell> before = new LinkedHashMap<>();
        BlockPos plaza = bell.west(20), gap = plaza.east(8), chest = bell.east(3);
        snapshot(level, before, bell);
        snapshot(level, before, bell.below());
        snapshot(level, before, chest);
        snapshot(level, before, chest.below());
        for (int x = plaza.getX(); x <= bell.getX(); x++) {
            BlockPos ground = new BlockPos(x, bell.getY(), bell.getZ());
            snapshot(level, before, ground.below());
            snapshot(level, before, ground);
            for (int y = ground.getY() + 1; y < level.getMaxY(); y++) snapshot(level, before, new BlockPos(x, y, ground.getZ()));
        }
        BlockPos field = gap.east().south(), water = gap.east(2).north(), building = gap.east(4).north();
        BlockPos protectedGap = gap.east(6);
        snapshot(level, before, field.below()); snapshot(level, before, field);
        snapshot(level, before, water.below()); snapshot(level, before, water);
        snapshot(level, before, building.below()); snapshot(level, before, building);

        net.minecraft.world.entity.npc.villager.Villager worker = null;
        try {
            setPathFixtureBlock(level, bell.below(), Blocks.STONE.defaultBlockState());
            setPathFixtureBlock(level, bell, Blocks.BELL.defaultBlockState());
            BlockEntity bellEntity = level.getBlockEntity(bell);
            VillageData village = new VillageData();
            village.chest = Optional.of(chest);
            bellEntity.setAttached(VillagerPlus.VILLAGE, village);
            bellEntity.setAttached(VillagerPlus.MARKET, new MarketData(Optional.of(plaza), java.util.List.of(), 0, 0));
            bellEntity.setAttached(VillagerPlus.ROADS, Map.of("center", 21L, "repair:center", 0L));
            bellEntity.setAttached(VillagerPlus.ZONES, java.util.List.of(new ZonePolicy.Zone("protected-road-check", "work",
                protectedGap.getX(), protectedGap.getZ(), protectedGap.getX(), protectedGap.getZ())));
            setPathFixtureBlock(level, chest.below(), Blocks.STONE.defaultBlockState());
            setPathFixtureBlock(level, chest, Blocks.CHEST.defaultBlockState());
            VillageRef ref = new VillageRef(bell, village, bellEntity);
            var depot = ref.chest(level);
            require(depot != null, "gap job fixture has a real communal depot");

            for (int x = plaza.getX(); x <= bell.getX(); x++) {
                BlockPos ground = new BlockPos(x, bell.getY(), bell.getZ());
                setPathFixtureBlock(level, ground.below(), Blocks.DIRT.defaultBlockState());
                if (x != bell.getX()) {
                    setPathFixtureBlock(level, ground, x >= gap.getX() && x < gap.getX() + 4
                        ? Blocks.AIR.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState());
                }
                // Keep this synthetic route at a deterministic heightmap surface, above arbitrary terrain.
                for (int y = ground.getY() + 1; y < level.getMaxY(); y++)
                    setPathFixtureBlock(level, new BlockPos(x, y, ground.getZ()), Blocks.AIR.defaultBlockState());
            }
            setPathFixtureBlock(level, protectedGap, Blocks.AIR.defaultBlockState());
            // Adjacent field and water surfaces look like corridor candidates but cannot support paid fill.
            setPathFixtureBlock(level, field.below(), Blocks.FARMLAND.defaultBlockState());
            setPathFixtureBlock(level, field, Blocks.AIR.defaultBlockState());
            setPathFixtureBlock(level, water.below(), Blocks.WATER.defaultBlockState());
            setPathFixtureBlock(level, water, Blocks.AIR.defaultBlockState());
            setPathFixtureBlock(level, building.below(), Blocks.DIRT.defaultBlockState());
            setPathFixtureBlock(level, building, Blocks.CHEST.defaultBlockState());
            setPathFixtureBlock(level, gap.above(), Blocks.DANDELION.defaultBlockState());

            int checks = 0;
            require(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                gap.getX(), gap.getZ()) == gap.getY(), "four-cell route gap is the exposed heightmap surface");
            checks++;
            require(Roads.trustedVillagePathCell(level, ref, gap) && Roads.gapTerrain(level, gap)
                && Roads.gapAnchored(level, gap, net.minecraft.core.Direction.EAST)
                && !Lighting.protectedVillageCell(level, ref, gap) && !Roads.isProtectedLaneCell(level, gap),
                "the saved four-cell gap fixture is trusted, supported, anchored, and unprotected");
            checks++;
            BlockPos plazaFixture = Market.of(ref).plaza().orElseThrow();
            int fixtureLength = Roads.routeLength(plazaFixture, ref.bell());
            int fixtureBuilt = (int) Math.min((long) fixtureLength + 1L,
                bellEntity.getAttachedOrElse(VillagerPlus.ROADS, Map.of()).getOrDefault("center", 0L));
            boolean savedLaneAnchors = Roads.gapAnchored(level, gap, net.minecraft.core.Direction.EAST, p ->
                Roads.straightVillageLaneCell(plazaFixture, ref.bell(), fixtureLength, fixtureBuilt, p, true, 0)
                    && !Lighting.protectedVillageCell(level, ref, p) && !Roads.isProtectedLaneCell(level, p));
            require(savedLaneAnchors, "both real anchors belong to the same saved straight village lane; west="
                + level.getBlockState(gap.west()) + ", east=" + level.getBlockState(gap.east(4)) + ", westLane="
                + Roads.straightVillageLaneCell(plazaFixture, ref.bell(), fixtureLength, fixtureBuilt, gap.west(), true, 0)
                + ", eastLane=" + Roads.straightVillageLaneCell(plazaFixture, ref.bell(), fixtureLength, fixtureBuilt,
                    gap.east(4), true, 0));
            checks++;
            BlockPos worksitePreview = Roads.villageGapWorksite(level, ref, gap);
            require(worksitePreview != null,
                "the saved four-cell gap has a protected, supported existing-path worksite; worksite=" + worksitePreview);
            checks++;
            Map<String, Long> roadStateBeforeProbe = bellEntity.getAttached(VillagerPlus.ROADS);
            Roads.Repair preview = Roads.nextRepair(level, ref);
            boolean previewRepairable = preview != null && Roads.repairable(level, preview);
            bellEntity.setAttached(VillagerPlus.ROADS, roadStateBeforeProbe);
            require(preview != null && previewRepairable && preview.ground().getX() >= gap.getX()
                && preview.ground().getX() < gap.getX() + 4
                && preview.action() == Roads.WorkAction.GAP_CLEAR && preview.ground().equals(gap.above()),
                "the normal repair scan selects the single safe flower obstruction before paving; task="
                    + (preview == null ? "null" : preview.ground().toShortString())
                    + ", repairable=" + previewRepairable);
            checks++;

            worker = EntityTypes.VILLAGER.spawn(level, chest.south(2), EntitySpawnReason.COMMAND);
            worker.setNoAi(true);
            worker.setInvulnerable(true);
            worker.addTag("vp090_fixture");
            var memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
            long now = level.getGameTime() + 1000;

            JobState clearingState = new JobState();
            clearingState.roadRepair = preview;
            worker.teleportTo(preview.worksite().getX() + .5, preview.worksite().getY(), preview.worksite().getZ() + .5);
            for (int stroke = 0; stroke <= 10; stroke++) {
                boolean worked = RoadBuilderJob.work(new Ctx(level, worker, clearingState, memory, ref,
                    now + stroke * 6L));
                if (stroke < 10) require(worked && level.getBlockState(gap.above()).is(Blocks.DANDELION),
                    "the natural plant stays until its paced break reaches the final stroke");
                else require(worked && level.getBlockState(gap.above()).isAir()
                    && JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DANDELION)) == 1,
                    "one safe plant is removed with its real drop preserved");
            }
            checks += 2;

            JobState noStockState = new JobState();
            noStockState.roadScanAt = 0;
            worker.teleportTo(chest.getX() + .5, chest.getY() + 1, chest.getZ() + 1.5);
            Ctx noStock = new Ctx(level, worker, noStockState, memory, ref, now++);
            require(!RoadBuilderJob.work(noStock) && level.getBlockState(gap).isAir()
                && depot.getItem(0).isEmpty(), "a real job tick cannot fill a gap without carried or communal material");
            checks++;

            // Reset the scan after the no-stock probe, then simulate a restart between each paid block.
            bellEntity.setAttached(VillagerPlus.ROADS, Map.of("center", 21L, "repair:center", 0L));
            depot.setItem(0, new ItemStack(Items.DIRT, 5));
            Set<BlockPos> repaired = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                JobState state = new JobState(); // Volatile work state is deliberately recreated per block.
                state.roadScanAt = 0;
                worker.teleportTo(chest.getX() + .5, chest.getY() + 1, chest.getZ() + 1.5);
                Ctx context = new Ctx(level, worker, state, memory, ref, now++);
                int chestBeforePickup = depot.getItem(0).getCount();
                boolean pickupTick = RoadBuilderJob.work(context);
                int stockAfterPickup = depot.getItem(0).getCount();
                int carriedDirt = JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT));
                require(pickupTick && stockAfterPickup == chestBeforePickup - 1 && carriedDirt == 1,
                    "each real gap-work cycle picks up exactly one dirt block; tick=" + pickupTick
                        + ", stock=" + chestBeforePickup + "->" + stockAfterPickup + ", carried=" + carriedDirt
                        + ", task=" + (state.roadRepair == null ? "null" : state.roadRepair.ground().toShortString())
                        + ", roadCursor=" + state.roadScanAt);
                checks++;

                Roads.Repair task = state.roadRepair;
                int worksiteDistance = task == null ? -1
                    : Math.abs(task.ground().getX() - task.worksite().getX())
                        + Math.abs(task.ground().getZ() - task.worksite().getZ());
                require(task != null && task.ground().getY() == gap.getY()
                    && task.ground().getX() >= gap.getX() && task.ground().getX() < gap.getX() + 4
                    && worksiteDistance >= 2 && worksiteDistance <= 3,
                    "the stored corridor cursor selects a gap cell with a safe, reachable worksite setback; distance="
                        + worksiteDistance);
                BlockPos target = task.ground();
                worker.teleportTo(plaza.getX() + .5, plaza.getY() + 1, plaza.getZ() + .5);
                var routeContext = new Ctx(level, worker, state, memory, ref, now++);
                var walk = de.villagerplus.build.Builder.workPath(routeContext, task.worksite());
                require(walk != null && walk.canReach() && walk.getNodeCount() > 1,
                    "builder pathfinder finds an actual route from the existing village path to the gap worksite; target="
                        + target.toShortString() + ", worksite=" + task.worksite().toShortString() + ", worker="
                        + worker.blockPosition().toShortString() + ", path="
                        + (walk == null ? "null" : "reachable=" + walk.canReach() + ", nodes=" + walk.getNodeCount())
                        + ", startFloor=" + level.getBlockState(worker.blockPosition().below())
                        + ", worksiteFloor=" + level.getBlockState(task.worksite().below())
                        + ", feet=" + level.getBlockState(task.worksite())
                        + ", head=" + level.getBlockState(task.worksite().above()));
                checks++;
                worker.teleportTo(task.worksite().getX() + .5, task.worksite().getY(), task.worksite().getZ() + .5);
                require(RoadBuilderJob.work(context) && level.getBlockState(target).is(Blocks.DIRT_PATH)
                    && JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
                    "arrival places one walkable dirt path and consumes the carried dirt exactly once");
                checks++;
                repaired.add(target);
            }
            require(repaired.size() == 4 && depot.getItem(0).getCount() == 1
                && level.getBlockState(gap).is(Blocks.DIRT_PATH)
                && level.getBlockState(gap.east()).is(Blocks.DIRT_PATH)
                && level.getBlockState(gap.east(2)).is(Blocks.DIRT_PATH)
                && level.getBlockState(gap.east(3)).is(Blocks.DIRT_PATH)
                && level.getBlockState(gap.above()).isAir()
                && level.getBlockState(gap.east().above()).isAir()
                && level.getBlockState(gap.east(2).above()).isAir()
                && level.getBlockState(gap.east(3).above()).isAir()
                && !Roads.gapTerrain(level, gap.above())
                && !Roads.gapTerrain(level, gap.east().above())
                && !Roads.gapTerrain(level, gap.east(2).above())
                && !Roads.gapTerrain(level, gap.east(3).above()),
                "four paid fills become completed path cells, leave one reserve dirt, and do not expose a second gap layer");
            checks++;
            JobState protectedState = new JobState();
            protectedState.roadScanAt = 0;
            worker.teleportTo(chest.getX() + .5, chest.getY() + 1, chest.getZ() + 1.5);
            Ctx protectedContext = new Ctx(level, worker, protectedState, memory, ref, now++);
            boolean protectedWorked = RoadBuilderJob.work(protectedContext);
            int protectedStock = depot.getItem(0).getCount();
            int protectedCarried = JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT));
            require(!protectedWorked && protectedStock == 1 && protectedCarried == 0
                && level.getBlockState(protectedGap).isAir(),
                "a protected village zone blocks the next gap and preserves its material; worked=" + protectedWorked
                    + ", depotDirt=" + protectedStock + ", carriedDirt=" + protectedCarried
                    + ", nextTask=" + (protectedState.roadRepair == null ? "null"
                        : protectedState.roadRepair.ground().toShortString() + "/" + protectedState.roadRepair.action())
                    + ", protectedCell=" + level.getBlockState(protectedGap));
            checks++;
            require(level.getBlockState(field.below()).is(Blocks.FARMLAND)
                && level.getBlockState(field).isAir()
                && level.getBlockState(water.below()).is(Blocks.WATER)
                && level.getBlockState(water).isAir()
                && level.getBlockState(building).is(Blocks.CHEST),
                "field, water, and a building cell remain unchanged during paid gap work");
            checks++;
            return checks;
        } finally {
            if (worker != null) worker.discard();
            restore(level, before);
        }
    }

    private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
        level.getChunkAt(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        before.putIfAbsent(pos.immutable(), new Cell(level.getBlockState(pos),
            entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
    }

    private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
        for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
        before.forEach((pos, cell) -> setPathFixtureBlock(level, pos, cell.state()));
        before.forEach((pos, cell) -> {
            if (cell.nbt() == null) return;
            BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
            if (restored != null) level.setBlockEntity(restored);
        });
    }

    /** Synthetic blocks in non-entity-ticking chunks do not necessarily invalidate this cache on their own. */
    private static void setPathFixtureBlock(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
        level.getPathTypeCache().invalidate(pos);
    }

    private record Cell(BlockState state, CompoundTag nbt) { }

    private static void snapshot(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos column, int min, int max) {
        for (int y = min; y <= max; y++) {
            BlockPos pos = column.above(y).immutable();
            level.getChunkAt(pos);
            before.putIfAbsent(pos, level.getBlockState(pos));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Road terrain: " + message);
    }
}
