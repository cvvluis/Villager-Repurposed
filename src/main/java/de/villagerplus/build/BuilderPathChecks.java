package de.villagerplus.build;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** World regression checks for continuous door-to-village paths around buildings. */
public final class BuilderPathChecks {
    private BuilderPathChecks() { }

    public static int run(ServerLevel level, BlockPos origin) {
        Map<BlockPos, Cell> before = new LinkedHashMap<>();
        BlockPos doorPos = origin.offset(-11, 1, 0), start = origin.offset(-10, 1, 0), goal = origin.offset(10, 1, 0);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-16, 0, -10), origin.offset(16, 6, 10))) {
            BlockPos pos = p.immutable();
            level.getChunkAt(pos);
            BlockEntity be = level.getBlockEntity(pos);
            before.put(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
        }
        BlockPos roofPos = origin.offset(0, 30, 0);
        BlockPos elevatedBell = origin.offset(0, 20, 0);
        BlockEntity elevatedBellEntity = level.getBlockEntity(elevatedBell);
        before.put(elevatedBell, new Cell(level.getBlockState(elevatedBell), elevatedBellEntity == null ? null
            : elevatedBellEntity.saveWithFullMetadata(level.registryAccess())));
        BlockEntity roofEntity = level.getBlockEntity(roofPos);
        before.put(roofPos, new Cell(level.getBlockState(roofPos), roofEntity == null ? null : roofEntity.saveWithFullMetadata(level.registryAccess())));
        BlockPos savedBell = origin.offset(14, 20, 8);
        BlockEntity savedBellEntity = level.getBlockEntity(savedBell);
        before.put(savedBell, new Cell(level.getBlockState(savedBell), savedBellEntity == null ? null : savedBellEntity.saveWithFullMetadata(level.registryAccess())));
        int checks = 0;
        SimpleContainer publicDoorStock = new SimpleContainer(4);
        publicDoorStock.setItem(0, new ItemStack(Items.BIRCH_DOOR, 1));
        publicDoorStock.setItem(1, new ItemStack(Items.SPRUCE_DOOR, 2));
        Map<Item, Integer> doorDemand = Builder.publicTagDemand(publicDoorStock, Items.OAK_DOOR,
            ItemTags.WOODEN_DOORS, 4);
        checks += require(doorDemand.equals(Map.of(Items.BIRCH_DOOR, 1, Items.SPRUCE_DOOR, 2, Items.OAK_DOOR, 1)),
            "missing paid tag materials request real public door variants before the craftable fallback for physical hauling");
        publicDoorStock.setItem(2, new ItemStack(Items.OAK_DOOR, 1));
        Map<Item, Integer> stockedDoorDemand = Builder.publicTagDemand(publicDoorStock, Items.OAK_DOOR,
            ItemTags.WOODEN_DOORS, 3);
        checks += require(stockedDoorDemand.equals(Map.of(Items.BIRCH_DOOR, 1, Items.SPRUCE_DOOR, 1, Items.OAK_DOOR, 1)),
            "tag demand uses the preferred item when publicly stocked and splits the rest across matching variants");
        BlockPos streetApproach = new BlockPos(40, 64, 40);
        checks += require(Builder.shouldApproachVillageStreet(true, false, streetApproach.above(20), streetApproach),
            "new plans run their reachability check from the street even when no milestone is pending");
        checks += require(!Builder.shouldApproachVillageStreet(true, true, streetApproach.above(20), streetApproach)
            && !Builder.shouldApproachVillageStreet(false, false, streetApproach.above(20), streetApproach),
            "active plans and non-builders skip the street bootstrap");
        try {
            for (int x = -15; x <= 15; x++) for (int z = -9; z <= 9; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(origin.offset(x, 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int y = 2; y <= 6; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            BlockPos existingRoad = origin.offset(9, 1, 0);
            level.setBlock(existingRoad, Blocks.DIRT_PATH.defaultBlockState(), 3);
            BlockPos kitchenRoad = origin.offset(0, 1, 0);
            for (int x = -1; x <= 1; x++) level.setBlock(origin.offset(x, 1, 0), Blocks.DIRT_PATH.defaultBlockState(), 3);
            checks += require(de.villagerplus.village.Sites.villageLevel(level, elevatedBell, kitchenRoad.getY() + 1),
                "kitchen plot elevation is measured from the nearby street, not the elevated bell");
            checks += require(!de.villagerplus.village.Sites.villageLevel(level, elevatedBell, elevatedBell.getY()),
                "road reference still rejects unrelated roof-height plots");
            BlockPos groundCandidate = de.villagerplus.village.Sites.candidate(level, elevatedBell,
                net.minecraft.util.RandomSource.create(7L), 1, 1);
            checks += require(groundCandidate != null && groundCandidate.getY() == kitchenRoad.getY() + 1,
                "random site candidate accepts the connected road-height plot below the elevated bell");
            level.setBlock(origin.offset(0, 6, 2), Blocks.GRAVEL.defaultBlockState(), 3);
            checks += require(de.villagerplus.village.Sites.villageReferenceY(level, elevatedBell) == kitchenRoad.getY() + 1,
                "one nearby gravel block cannot replace the connected road reference");
            BlockPos padBell = origin.offset(-13, 20, 8);
            for (int x = -1; x <= 1; x++) level.setBlock(padBell.offset(x, -19, 0), Blocks.COBBLESTONE.defaultBlockState(), 3);
            checks += require(de.villagerplus.village.Sites.villageReferenceY(level, padBell) == padBell.getY(),
                "a three-block cobblestone foundation edge is not mistaken for a road");
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                level.setBlock(padBell.offset(x, -18, z), Blocks.GRAVEL.defaultBlockState(), 3);
            checks += require(de.villagerplus.village.Sites.villageReferenceY(level, padBell) == padBell.getY(),
                "a compact gravel pad is not mistaken for a road");

            level.setBlock(savedBell, Blocks.BELL.defaultBlockState(), 3);
            var bellEntity = (net.minecraft.world.level.block.entity.BellBlockEntity) level.getBlockEntity(savedBell);
            var bootstrapVillage = new de.villagerplus.village.VillageData();
            bellEntity.setAttached(de.villagerplus.VillagerPlus.VILLAGE, bootstrapVillage);
            bellEntity.setAttached(de.villagerplus.VillagerPlus.SPECIALS, Map.of());
            var savedRef = new de.villagerplus.village.VillageRef(savedBell, bootstrapVillage, bellEntity);
            var fullBootstrapStore = new net.minecraft.world.SimpleContainer(9);
            for (int slot = 0; slot < fullBootstrapStore.getContainerSize(); slot++) {
                fullBootstrapStore.setItem(slot, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
            }
            boolean bootstrapStorePressure = !de.villagerplus.job.JobUtil.hasEmptySlot(fullBootstrapStore)
                && de.villagerplus.village.Development.needsStorage(fullBootstrapStore, bootstrapVillage, level);
            String firstMilestone = Builder.nextSpecial(savedRef, 2);
            checks += require(Blueprints.GRANARY.equals(firstMilestone) && bootstrapStorePressure
                && !Builder.shouldExpandStorageUnderPressure(null, firstMilestone, bootstrapStorePressure),
                "a two-resident village still starts its required granary when its only store is full");
            checks += require(!Builder.shouldExpandStorageUnderPressure(Blueprints.GRANARY, firstMilestone, bootstrapStorePressure),
                "the first-block retry keeps the active granary plan instead of aborting it for storage capacity");
            var previousBuildings = bellEntity.getAttached(de.villagerplus.VillagerPlus.BUILDINGS);
            var spacingDesign = Architect.design(Architect.Kind.HOUSE, 0L);
            int existingHouseRadius = spacingDesign.half() + spacingDesign.overhang();
            try {
                bellEntity.setAttached(de.villagerplus.VillagerPlus.BUILDINGS, List.of(
                    new Built(Blueprints.HOUSE, origin, Direction.NORTH.get2DDataValue(), 0, List.of())));
                checks += require(!ZoneAwareSites.hasHouseClearance(savedRef,
                    origin.getX() + existingHouseRadius + 1, origin.getX() + existingHouseRadius + 2,
                    origin.getZ(), origin.getZ()),
                    "new house plots reject a too-close completed house even without zoning");
                checks += require(ZoneAwareSites.hasHouseClearance(savedRef,
                    origin.getX() + existingHouseRadius + ZoneAwareSites.HOUSE_TO_HOUSE_CLEARANCE,
                    origin.getX() + existingHouseRadius + ZoneAwareSites.HOUSE_TO_HOUSE_CLEARANCE + 2,
                    origin.getZ(), origin.getZ()),
                    "new house plots keep the configured clear gap from completed houses");
            } finally {
                if (previousBuildings == null) bellEntity.removeAttached(de.villagerplus.VillagerPlus.BUILDINGS);
                else bellEntity.setAttached(de.villagerplus.VillagerPlus.BUILDINGS, previousBuildings);
            }
            level.setBlock(elevatedBell, Blocks.BELL.defaultBlockState(), 3);
            var elevatedBellBlockEntity = (net.minecraft.world.level.block.entity.BellBlockEntity) level.getBlockEntity(elevatedBell);
            var elevatedRef = new de.villagerplus.village.VillageRef(elevatedBell,
                elevatedBellBlockEntity.getAttachedOrCreate(de.villagerplus.VillagerPlus.VILLAGE), elevatedBellBlockEntity);
            BlockPos streetAnchor = Builder.civicSiteAnchor(level, elevatedRef);
            checks += require(streetAnchor.getY() == kitchenRoad.getY() + 1
                && level.getBlockState(streetAnchor.below()).is(Blocks.DIRT_PATH),
                "civic-site planning anchors to the real street eighteen blocks below the raised bell");
            checks += require(Builder.shouldApproachVillageStreet(true, false, elevatedBell, streetAnchor),
                "builder descends to that street before checking a new plan's reachable work stand");
            var savedSearch = new de.villagerplus.village.VillageData.Extra.CivicSiteSearch(
                Blueprints.GRANARY, List.of(13, 29, 41, 57), 912345L, 2, 87, true, 123456L);
            var searchSave = new de.villagerplus.village.VillageData();
            searchSave.extra.civicSiteSearch = java.util.Optional.of(savedSearch);
            var encodedSearchSave = de.villagerplus.village.VillageData.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,
                searchSave).getOrThrow();
            var restoredSearchSave = de.villagerplus.village.VillageData.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                encodedSearchSave).getOrThrow();
            checks += require(restoredSearchSave.extra.civicSiteSearch.orElseThrow().equals(savedSearch)
                && !restoredSearchSave.extra.civicSiteSearch.orElseThrow().terraced(),
                "civic site phase, variants, pass and candidate cursor persist through village save/load");
            var savedTerraced = new de.villagerplus.village.VillageData.Extra.CivicSiteSearch(
                Blueprints.GRANARY, List.of(13, 29, 41, 57), 912345L, 1, 40, true, 123456L, true);
            var terracedSave = new de.villagerplus.village.VillageData();
            terracedSave.extra.civicSiteSearch = java.util.Optional.of(savedTerraced);
            var restoredTerraced = de.villagerplus.village.VillageData.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                de.villagerplus.village.VillageData.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,
                    terracedSave).getOrThrow()).getOrThrow();
            checks += require(restoredTerraced.extra.civicSiteSearch.orElseThrow().equals(savedTerraced),
                "the last-resort terraced civic pass persists through village save/load (old saves default to not terraced)");
            // Natural CTOV regression: Builder.tick clears the plan view before every worker call while no project
            // is active; that must not rewind the civic sweep to its innermost 96 candidates each time.
            searchSave.clearPlanViewForProjectSlot();
            checks += require(searchSave.extra.civicSiteSearch.orElseThrow().equals(savedSearch),
                "a fresh project-slot plan reset keeps the civic site sweep cursor instead of restarting it");
            var legacyVillage = de.villagerplus.village.VillageData.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                new com.google.gson.JsonObject()).getOrThrow();
            checks += require(legacyVillage.extra.civicSiteSearch.isEmpty(),
                "legacy village saves default to no in-progress civic site search");
            checks += civicSearchCycleChecks();
            int oldMargin = de.villagerplus.village.Sites.margin;
            try {
                de.villagerplus.village.Sites.margin = 2;
                long searchSeed = 261101L;
                BlockPos firstFlat = ZoneAwareSites.find(level, elevatedRef, Blueprints.STORAGE,
                    net.minecraft.util.RandomSource.create(searchSeed), 4, 12, 1, 3, 96, p -> true);
                BlockPos nextFlat = ZoneAwareSites.find(level, elevatedRef, Blueprints.STORAGE,
                    net.minecraft.util.RandomSource.create(searchSeed), 4, 12, 1, 3, 96, p -> !p.equals(firstFlat));
                checks += require(firstFlat != null && nextFlat != null && !firstFlat.equals(nextFlat),
                    "un-zoned random placement skips a previously rejected flat plot");
                BlockPos firstSlope = ZoneAwareSites.findTolerant(level, elevatedRef, Blueprints.STORAGE,
                    net.minecraft.util.RandomSource.create(searchSeed), 4, 12, -1, 1, -1, 1, 3, 96, p -> true);
                BlockPos nextSlope = ZoneAwareSites.findTolerant(level, elevatedRef, Blueprints.STORAGE,
                    net.minecraft.util.RandomSource.create(searchSeed), 4, 12, -1, 1, -1, 1, 3, 96,
                    p -> !p.equals(firstSlope));
                checks += require(firstSlope != null && nextSlope != null && !firstSlope.equals(nextSlope),
                    "un-zoned tolerant placement skips a previously rejected hill plot");

                BlockPos firstCivic = ZoneAwareSites.findSystematic(level, elevatedRef, Blueprints.GRANARY,
                    net.minecraft.util.RandomSource.create(searchSeed), 4, 12, -3, 3, -3, 3, 6, p -> true);
                BlockPos nextCivic = firstCivic == null ? null : ZoneAwareSites.findSystematic(level, elevatedRef,
                    Blueprints.GRANARY, net.minecraft.util.RandomSource.create(searchSeed), 4, 12, -3, 3, -3, 3, 6,
                    p -> !p.equals(firstCivic));
                checks += require(firstCivic != null && nextCivic != null && !firstCivic.equals(nextCivic),
                    "systematic civic search continues after a rejected granary plot");

                int candidateCount = de.villagerplus.village.Sites.systematicCandidateCount(4, 12);
                int referenceY = de.villagerplus.village.Sites.villageReferenceY(level, elevatedRef);
                Set<Long> pageVisited = new HashSet<>();
                var firstPage = de.villagerplus.village.Sites.findSystematicPage(level, elevatedBell, 4, 12,
                    -3, 3, -3, 3, 6, false, 0, 1, searchSeed, pageVisited, p -> false, referenceY);
                var secondPage = de.villagerplus.village.Sites.findSystematicPage(level, elevatedBell, 4, 12,
                    -3, 3, -3, 3, 6, false, firstPage.nextCandidate(), 1, searchSeed, pageVisited, p -> false, referenceY);
                checks += require(candidateCount > 2 && firstPage.nextCandidate() == 1 && secondPage.nextCandidate() == 2
                    && !firstPage.complete() && !secondPage.complete(),
                    "incremental civic search advances by its bounded candidate budget");

                for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                    int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        origin.getX() + x, origin.getZ() + z);
                    level.setBlock(new BlockPos(origin.getX() + x, surface + 2, origin.getZ() + z),
                        Blocks.OAK_LEAVES.defaultBlockState(), 3);
                }
                boolean[] canopyAccepted = {false};
                BlockPos[] normalizedAnchor = {null};
                var housePage = de.villagerplus.village.Sites.findHouseSitePage(level, elevatedBell, 4, 8,
                    0, 128, searchSeed, new HashSet<>(), p -> {
                        boolean underTree = level.getBlockState(p.above(2)).is(Blocks.OAK_LEAVES);
                        if (!underTree) return null;
                        canopyAccepted[0] = true;
                        normalizedAnchor[0] = p.below();
                        return normalizedAnchor[0];
                    }, referenceY);
                checks += require(housePage.site() != null && canopyAccepted[0]
                    && housePage.site().equals(normalizedAnchor[0]) && housePage.site().getY() == normalizedAnchor[0].getY(),
                    "systematic house search preserves the validated, height-normalized site beneath a tree canopy");
                for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                    int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        origin.getX() + x, origin.getZ() + z);
                    level.setBlock(new BlockPos(origin.getX() + x, surface + 2, origin.getZ() + z), Blocks.AIR.defaultBlockState(), 3);
                }

                BlockPos unloadedCenter = new BlockPos(20_000_000, elevatedBell.getY(), 20_000_000);
                boolean unloadedBefore = !level.hasChunkAt(unloadedCenter);
                boolean[] unloadedFitCalled = {false};
                var unloadedPage = de.villagerplus.village.Sites.findSystematicPage(level, unloadedCenter, 0, 0,
                    -3, 3, -3, 3, 6, false, 0, 1, searchSeed, new HashSet<>(), p -> {
                        unloadedFitCalled[0] = true;
                        return true;
                    }, unloadedCenter.getY());
                checks += require(unloadedBefore && unloadedPage.site() == null && !unloadedFitCalled[0]
                    && !level.hasChunkAt(unloadedCenter),
                    "incremental search skips an unloaded chunk without requesting or evaluating it");

                if (firstCivic != null) {
                    Set<Long> rejectedVisited = new HashSet<>();
                    rejectedVisited.add(BlockPos.asLong(firstCivic.getX(), 0, firstCivic.getZ()));
                    var afterRejectedPage = de.villagerplus.village.Sites.findSystematicPage(level, elevatedBell, 4, 12,
                        -3, 3, -3, 3, 6, false, 0, candidateCount, searchSeed, rejectedVisited,
                        p -> !p.equals(firstCivic), referenceY);
                    checks += require(nextCivic != null && nextCivic.equals(afterRejectedPage.site()),
                        "resumed civic search skips an already rejected site without repeating it");

                    Set<Long> planVisited = new HashSet<>();
                    var noPlanFit = de.villagerplus.village.Sites.findSystematicPage(level, elevatedBell, 4, 12,
                        -3, 3, -3, 3, 6, false, 0, candidateCount, searchSeed, planVisited, p -> false, referenceY);
                    checks += require(noPlanFit.site() == null && noPlanFit.complete(),
                        "systematic site search still requires the complete plan-fit predicate");
                }
            } finally {
                de.villagerplus.village.Sites.margin = oldMargin;
            }
            BlockPos savedPaving = origin.offset(14, 1, 9);
            level.setBlock(savedPaving, Blocks.COBBLESTONE.defaultBlockState(), 3);
            de.villagerplus.village.Market.save(savedRef, new de.villagerplus.village.MarketData(
                java.util.Optional.of(savedBell.west(8)), java.util.List.of(), 0, 0));
            bellEntity.setAttached(de.villagerplus.VillagerPlus.ROADS, java.util.Map.of("center", 9L));
            checks += require(de.villagerplus.village.Roads.trustedVillagePathCell(level, savedRef, savedPaving)
                && de.villagerplus.village.Sites.villageReferenceY(level, savedRef) == savedPaving.getY() + 1,
                "persistent market-to-bell road geometry validates a non-dirt paved street");
            level.setBlock(roofPos, Blocks.STONE.defaultBlockState(), 3);
            BlockPos roofCandidate = de.villagerplus.village.Sites.candidate(level, elevatedBell,
                net.minecraft.util.RandomSource.create(7L), 0, 0);
            checks += require(roofCandidate == null, "candidate path rejects a roof above the connected street");
            BlockPos flower = origin.offset(-5, 1, 0);
            level.setBlock(flower, Blocks.POPPY.defaultBlockState(), 3);
            BlockPos privateChest = origin.offset(-5, 1, 1);
            level.setBlock(privateChest, Blocks.CHEST.defaultBlockState(), 3);
            var doorState = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            Step door = Step.free(doorPos, doorState);
            BlockPos actualEntrance = Builder.Paths.entrance(java.util.List.of(door), origin, Direction.NORTH);
            checks += require(actualEntrance.equals(start), "planned house door determines the outward path start");
            // The center road above belongs to the separate site-elevation fixture. The ref-less
            // path overload intentionally joins the nearest existing dirt path, so remove this
            // unrelated segment before checking continuity to the road at the village endpoint.
            for (int x = -1; x <= 1; x++) level.setBlock(origin.offset(x, 1, 0), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            // A solid village building blocks the direct line; the path must go around its footprint.
            for (int z = -3; z <= 3; z++) for (int y = 1; y <= 3; y++)
                level.setBlock(origin.offset(0, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);

            Builder.Paths.lay(level, actualEntrance, goal);
            Set<BlockPos> path = new HashSet<>();
            for (int x = -15; x <= 15; x++) for (int z = -9; z <= 9; z++) {
                BlockPos p = origin.offset(x, 1, z);
                if (level.getBlockState(p).is(Blocks.DIRT_PATH)) path.add(p);
            }
            checks += require(!path.isEmpty(), "path planner lays a village connection");
            checks += require(path.stream().anyMatch(p -> p.distManhattan(start) <= 1)
                && path.stream().anyMatch(p -> p.distManhattan(goal) <= 1), "path reaches both endpoint approaches");
            checks += require(path.contains(existingRoad) && path.stream().anyMatch(p -> p.distManhattan(existingRoad) <= 1),
                "new house path joins and preserves the existing village road near the bell");
            checks += require(path.stream().noneMatch(p -> p.getX() == origin.getX() && Math.abs(p.getZ() - origin.getZ()) <= 3),
                "path routes around the solid building without replacing it");
            checks += require(connected(path, start, goal), "path tiles form one continuous four-neighbor route");
            checks += require(level.getBlockState(existingRoad).is(Blocks.DIRT_PATH), "existing road surface remains intact");
            checks += require(level.getBlockState(flower).is(Blocks.POPPY), "path finder preserves replaceable plants instead of clearing them");
            checks += require(level.getBlockState(privateChest).is(Blocks.CHEST) && level.getBlockEntity(privateChest) != null,
                "path finder preserves private chest and its block entity");
            for (int z = -3; z <= 3; z++) for (int y = 1; y <= 3; y++)
                checks += require(level.getBlockState(origin.offset(0, y, z)).is(Blocks.OAK_PLANKS), "building blocks remain untouched");

            Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_LOG,
                Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
                Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
            var lanterns = Landmarks.build(Blueprints.DECOR, 0, palette, origin, Direction.NORTH).stream()
                .filter(step -> step.free())
                .filter(step -> step.make().apply(Blocks.OAK_PLANKS).is(Blocks.LANTERN))
                .map(step -> step.pos()).toList();
            checks += require(lanterns.size() == 1, "the short lantern walk uses one lamp post instead of a close pair");
            checks += require(lanterns.get(0).equals(origin.offset(0, 2, 1)),
                "the lone lamp is centered behind the walkway inside the existing plot");
            checks += paletteLifecycleChecks(level, savedRef, origin);
            checks += terracedCivicSiteChecks(level, origin);
            checks += standAbsenceChecks();
            return checks;
        } finally {
            for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
            for (BlockPos pos : before.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
            before.forEach((pos, cell) -> {
                if (cell.data() != null) {
                    BlockEntity be = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
                    if (be != null) level.setBlockEntity(be);
                }
            });
        }
    }

    /**
     * Last-resort civic site: a 3-block slope that the flat and one-block-tolerant scans reject, with and without a rooted tree.
     * Verifies the scan, the safety of the anchor (highest column, no digging) and the verified tree clearing.
     */
    private static int terracedCivicSiteChecks(ServerLevel level, BlockPos origin) {
        BlockPos center = origin.offset(0, 0, 60);
        int baseY = origin.getY();
        int radius = 12;
        Map<BlockPos, BlockState> saved = new LinkedHashMap<>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) for (int y = -6; y <= 28; y++) {
            BlockPos pos = new BlockPos(center.getX() + x, baseY + y, center.getZ() + z);
            level.getChunkAt(pos);
            saved.put(pos, level.getBlockState(pos));
        }
        net.minecraft.world.entity.npc.villager.Villager worker = null;
        try {
            int checks = 0;
            int headroom = 6;
            int referenceY = baseY + 5;
            java.util.function.Predicate<BlockPos> any = p -> true;
            // Slope rising one block every three columns along x: x = -4..4 spans exactly three blocks of relief.
            buildSlope(level, center, baseY, radius);
            checks += require(de.villagerplus.village.Sites.findSystematicPage(level, center, 0, 0, -4, 4, -4, 4, headroom, 0, 0, 1, 7L,
                new HashSet<>(), any, referenceY).site() == null, "a three-block slope fails the flat civic scan");
            checks += require(de.villagerplus.village.Sites.findSystematicPage(level, center, 0, 0, -4, 4, -4, 4, headroom, 1, 0, 1, 7L,
                new HashSet<>(), any, referenceY).site() == null, "a three-block slope fails the one-block-tolerant civic scan");
            BlockPos terrace = de.villagerplus.village.Sites.findSystematicPage(level, center, 0, 0, -4, 4, -4, 4, headroom,
                de.villagerplus.village.Sites.TERRACED_CIVIC_RELIEF, 0, 1, 7L, new HashSet<>(), any, referenceY).site();
            checks += require(terrace != null && terrace.getY() == baseY + 5,
                "the terraced civic scan accepts the slope and anchors at its highest column (nothing is dug into the hill)");

            // A rooted natural spruce on the highest columns: trunk and canopy count as terrain unless trees are clearable.
            Map<BlockPos, BlockState> ground = new LinkedHashMap<>();
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
                for (int y = baseY - 3; y <= baseY + 5; y++) {
                    BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                    ground.put(pos, level.getBlockState(pos));
                }
            BlockPos root = new BlockPos(center.getX() + 3, baseY + 5, center.getZ());
            placeSpruce(level, root);
            checks += require(de.villagerplus.village.Sites.findSystematicPage(level, center, 0, 0, -4, 4, -4, 4, headroom,
                de.villagerplus.village.Sites.TERRACED_CIVIC_RELIEF, 0, 1, 7L, new HashSet<>(), any, referenceY).site() == null,
                "without clearable trees a spruce on the slope keeps the terraced scan from accepting it");
            BlockPos treeSite = de.villagerplus.village.Sites.findSystematicPage(level, center, 0, 0, -4, 4, -4, 4, headroom,
                de.villagerplus.village.Sites.TERRACED_CIVIC_RELIEF, true, 0, 1, 7L, new HashSet<>(), any, referenceY).site();
            checks += require(treeSite != null && treeSite.getY() == baseY + 5,
                "with clearable trees the rooted spruce is measured at its soil and the slope is accepted");
            List<Step> footprint = List.of(
                Step.free(treeSite.offset(-4, 0, -4), Blocks.AIR.defaultBlockState()),
                Step.free(treeSite.offset(4, 0, 4), Blocks.AIR.defaultBlockState()));
            BlockPos[] box = Builder.civicPrepBox(treeSite, footprint, headroom);
            checks += require(box[0].getY() == treeSite.getY() && box[0].getX() == center.getX() - 4 && box[1].getX() == center.getX() + 4,
                "the civic work volume is the exact plan footprint starting at the anchor height (never below it)");
            checks += require(de.villagerplus.village.NaturalSitePreparation.inspect(level, box[0], box[1], 1024).status()
                == de.villagerplus.village.NaturalSitePreparation.Status.CLEARABLE,
                "the verified natural-site inspection accepts the rooted spruce as clearable");
            worker = net.minecraft.world.entity.EntityTypes.VILLAGER.spawn(level, center.offset(0, 8, 0),
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (worker == null) throw new IllegalStateException("Builder path: could not spawn a civic clearing worker");
            worker.setNoAi(true);
            worker.setInvulnerable(true);
            worker.getInventory().clearContent();
            var status = de.villagerplus.village.NaturalSitePreparation.Status.CLEARED;
            int cleared = 0;
            for (int i = 0; i < 400 && status == de.villagerplus.village.NaturalSitePreparation.Status.CLEARED; i++) {
                status = de.villagerplus.village.NaturalSitePreparation.clearOne(level, box[0], box[1], 1024, worker).status();
                if (status == de.villagerplus.village.NaturalSitePreparation.Status.CLEARED) cleared++;
            }
            checks += require(status == de.villagerplus.village.NaturalSitePreparation.Status.READY && cleared >= 6,
                "one verified natural obstacle per call clears the whole spruce until the footprint reports READY");
            boolean treeGone = true;
            for (BlockPos pos : BlockPos.betweenClosed(box[0], box[1])) {
                BlockState state = level.getBlockState(pos);
                if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)) treeGone = false;
            }
            checks += require(treeGone, "no trunk or leaf block remains inside the cleared civic work volume");
            boolean soilIntact = true;
            for (Map.Entry<BlockPos, BlockState> entry : ground.entrySet()) {
                if (entry.getKey().getY() < baseY + 5 && !level.getBlockState(entry.getKey()).equals(entry.getValue())) soilIntact = false;
            }
            checks += require(soilIntact, "clearing removes only the tree: the soil under the slope is never dug");

            // A tree rooted on a lower column starts below the work volume: not verifiably clearable, so the site is refused.
            buildSlope(level, center, baseY, radius);
            placeSpruce(level, new BlockPos(center.getX() - 2, baseY + 3, center.getZ()));
            BlockPos[] lowBox = Builder.civicPrepBox(treeSite, footprint, headroom);
            checks += require(de.villagerplus.village.NaturalSitePreparation.inspect(level, lowBox[0], lowBox[1], 1024).status()
                != de.villagerplus.village.NaturalSitePreparation.Status.READY
                && de.villagerplus.village.NaturalSitePreparation.inspect(level, lowBox[0], lowBox[1], 1024).status()
                != de.villagerplus.village.NaturalSitePreparation.Status.CLEARABLE,
                "a tree rooted below the work volume is refused instead of being half-cleared");
            return checks;
        } finally {
            if (worker != null) worker.discard();
            saved.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
    }

    private static void buildSlope(ServerLevel level, BlockPos center, int baseY, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            int top = baseY + Math.floorDiv(x + 9, 3);
            for (int y = baseY - 6; y <= baseY + 28; y++) {
                BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                level.removeBlockEntity(pos);
                level.setBlock(pos, y < top ? Blocks.DIRT.defaultBlockState()
                    : y == top ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static void placeSpruce(ServerLevel level, BlockPos root) {
        for (int dy = 0; dy < 5; dy++) level.setBlock(root.above(dy), Blocks.SPRUCE_LOG.defaultBlockState(), 3);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 || dz != 0) level.setBlock(root.offset(dx, 3, dz), Blocks.SPRUCE_LEAVES.defaultBlockState(), 3);
        }
        level.setBlock(root.above(5), Blocks.SPRUCE_LEAVES.defaultBlockState(), 3);
        level.setBlock(root.above(4).east(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 3);
        level.setBlock(root.above(4).west(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 3);
    }

    /**
     * Natural-run regression: the plan owner walked off for supplies, came back after 1200+ ticks and the stale "no progress"
     * records aborted the plan at step 0. An absence must restart the walk tracking instead.
     */
    private static int standAbsenceChecks() {
        {
            long key = 0x5A17L;
            Map<Long, Double> best = staticMap("STAND_BEST");
            Map<Long, Long> failure = staticMap("STAND_FAILURE_SINCE");
            Map<Long, Integer> nodes = staticMap("STAND_BEST_NODES");
            best.put(key, 6.0);
            nodes.put(key, 9);
            failure.put(key, 1L);
            int checks = 0;
            checks += require(!Builder.noteStandVisit(key, 1000L) && Builder.standProgressAge(key, 1000L) == 0L,
                "the first stand evaluation of a plan starts a fresh progress clock");
            checks += require(!Builder.noteStandVisit(key, 1050L) && best.containsKey(key) && Builder.standProgressAge(key, 1250L) == 250L,
                "consecutive evaluations keep the distance record and the running clock");
            checks += require(Builder.standProgressAge(key, 1050L + 1200L) > 1200L,
                "without an absence a walk that makes no progress for 1200 ticks still counts as stalled");
            checks += require(Builder.noteStandVisit(key, 1050L + 1210L) && !best.containsKey(key) && !nodes.containsKey(key)
                && !failure.containsKey(key) && Builder.standProgressAge(key, 1050L + 1210L) == 0L,
                "an owner returning after an absence of 1200+ ticks starts its walk tracking over (no abort on the first call back)");
            checks += require(Builder.standProgressAge(key, 1050L + 1210L + 1201L) > 1200L,
                "after the reset a genuine stall of 1200 ticks still aborts the plan");
            java.util.UUID stuck = java.util.UUID.nameUUIDFromBytes("stuck-owner-check".getBytes());
            checks += require(!Builder.planOwnerExcluded(stuck, 100L), "an owner that was never benched is not excluded");
            Builder.excludePlanOwner(stuck, 100L + Builder.STUCK_OWNER_EXCLUSION_TICKS);
            checks += require(Builder.planOwnerExcluded(stuck, 100L + Builder.STUCK_OWNER_EXCLUSION_TICKS - 1)
                && !Builder.planOwnerExcluded(stuck, 100L + Builder.STUCK_OWNER_EXCLUSION_TICKS)
                && !Builder.planOwnerExcluded(stuck, 100L + Builder.STUCK_OWNER_EXCLUSION_TICKS + 5),
                "a benched owner is excluded from plan work exactly until the exclusion expires, then eligible again");
            Builder.forgetStandWatch(key);
            best.remove(key); nodes.remove(key); failure.remove(key);
            return checks;
        }
    }

    private record Cell(BlockState state, CompoundTag data) { }

    @SuppressWarnings("unchecked")
    private static int civicSearchCycleChecks() {
        try {
            Class<?> searchType = java.util.Arrays.stream(Builder.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("CivicSiteSearch"))
                .findFirst().orElseThrow();
            var constructor = searchType.getDeclaredConstructor(String.class, int[].class, long.class);
            constructor.setAccessible(true);
            Object search = constructor.newInstance(Blueprints.GRANARY, new int[]{13, 29}, 912345L);
            var flatField = searchType.getDeclaredField("flatVisited");
            var tolerantField = searchType.getDeclaredField("tolerantVisited");
            var terracedField = searchType.getDeclaredField("terracedVisited");
            var terracedFlag = searchType.getDeclaredField("terraced");
            flatField.setAccessible(true);
            tolerantField.setAccessible(true);
            terracedField.setAccessible(true);
            terracedFlag.setAccessible(true);
            List<Set<Long>> flat = (List<Set<Long>>) flatField.get(search);
            List<Set<Long>> tolerant = (List<Set<Long>>) tolerantField.get(search);
            List<Set<Long>> terraced = (List<Set<Long>>) terracedField.get(search);
            for (int i = 0; i < flat.size(); i++) {
                flat.get(i).add(100L + i);
                tolerant.get(i).add(200L + i);
                terraced.get(i).add(300L + i);
            }
            var advance = searchType.getDeclaredMethod("advancePass", long.class);
            advance.setAccessible(true);
            boolean firstPassCompleted = (boolean) advance.invoke(search, 100L);
            int checks = require(!firstPassCompleted && flat.get(0).contains(100L)
                && tolerant.get(0).contains(200L),
                "civic visited columns remain cached between search passes");
            // Per variant: flat, tolerant. Only after every variant failed on both: one terraced pass per variant, then the cycle ends.
            boolean[] results = new boolean[5];
            boolean[] terracedAfter = new boolean[5];
            for (int i = 0; i < 5; i++) {
                results[i] = (boolean) advance.invoke(search, 200L + i);
                terracedAfter[i] = terracedFlag.getBoolean(search);
            }
            boolean fullCycleCompleted = results[4];
            boolean cachesCleared = flat.stream().allMatch(Set::isEmpty) && tolerant.stream().allMatch(Set::isEmpty)
                && terraced.stream().allMatch(Set::isEmpty);
            checks += require(!results[0] && !results[1] && !results[2] && !results[3] && fullCycleCompleted && cachesCleared,
                "a full civic re-scan clears visited columns so changed plots are reevaluated");
            checks += require(!terracedAfter[0] && !terracedAfter[1] && terracedAfter[2] && terracedAfter[3] && !terracedAfter[4],
                "the terraced civic pass only starts after every variant failed on flat and one-block-tolerant ground");
            return checks;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Builder path: could not inspect civic search cycle", failure);
        }
    }

    private static int paletteLifecycleChecks(ServerLevel level, de.villagerplus.village.VillageRef ref, BlockPos origin) {
        Map<Long, Palette> palettes = staticMap("PALETTES");
        Map<Long, Set<Long>> footprints = staticMap("FOOT");
        Map<Long, Set<Long>> rejectedPlots = staticMap("REJECTED_PLOTS");
        long villageKey = ref.bell().asLong();
        Set<Long> savedRejectedPlots = rejectedPlots.containsKey(villageKey)
            ? new HashSet<>(rejectedPlots.get(villageKey)) : null;

        BlockPos rejected = origin.offset(11, 1, 7);
        BlockPos aborted = origin.offset(12, 1, 7);
        BlockPos completed = origin.offset(13, 1, 7);
        BlockPos neighbor = origin.offset(15, 1, 7);
        List<BlockPos> sites = List.of(rejected, aborted, completed, neighbor);
        Map<Long, Palette> savedPalettes = new LinkedHashMap<>();
        Set<Long> hadPalette = new HashSet<>();
        Map<Long, Set<Long>> savedFootprints = new LinkedHashMap<>();
        Set<Long> hadFootprint = new HashSet<>();
        for (BlockPos site : sites) {
            long key = site.asLong();
            if (palettes.containsKey(key)) {
                hadPalette.add(key);
                savedPalettes.put(key, palettes.get(key));
            }
            if (footprints.containsKey(key)) {
                hadFootprint.add(key);
                savedFootprints.put(key, new HashSet<>(footprints.get(key)));
            }
        }

        Palette marker = new Palette(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_LOG,
            Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
            Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
        int checks = 0;
        net.minecraft.world.entity.npc.villager.Villager worker = null;
        try {
            palettes.put(neighbor.asLong(), marker);
            palettes.put(rejected.asLong(), marker);
            Builder.rejectPlot(ref, rejected);
            checks += require(Builder.rejectedPlot(ref, rejected) && !palettes.containsKey(rejected.asLong())
                && palettes.get(neighbor.asLong()) == marker,
                "rejecting a candidate releases its palette and preserves a neighboring active plan");

            palettes.put(aborted.asLong(), marker);
            de.villagerplus.village.VillageData data = ref.data();
            data.planType = Blueprints.STORAGE;
            data.planOrigin = java.util.Optional.of(aborted);
            data.planStep = 0;
            data.wanted.clear();
            invokeBuilder("abort", new Class<?>[]{de.villagerplus.village.VillageData.class,
                de.villagerplus.village.VillageRef.class, long.class}, data, ref, Math.max(1L, level.getGameTime()));
            checks += require(!palettes.containsKey(aborted.asLong()) && palettes.get(neighbor.asLong()) == marker,
                "aborting a plan releases its palette and preserves a neighboring active plan");

            palettes.put(completed.asLong(), marker);
            data.planType = Blueprints.GRANARY;
            data.planOrigin = java.util.Optional.of(completed);
            data.planDir = Direction.NORTH.get2DDataValue();
            data.planStep = 0;
            data.planVariant = 0;
            data.planSource = java.util.Optional.empty();
            data.planProgress = Math.max(1L, level.getGameTime());
            footprints.put(completed.asLong(), Set.of(origin.asLong()));

            worker = net.minecraft.world.entity.EntityTypes.VILLAGER.spawn(level, origin.offset(-10, 1, 0),
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (worker == null) throw new IllegalStateException("Builder palette check: could not spawn completion worker");
            worker.addTag("vp090_palette_fixture");
            worker.setNoAi(true);
            worker.setInvulnerable(true);
            var state = new de.villagerplus.job.JobState();
            var memory = worker.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY);
            var context = new de.villagerplus.job.Ctx(level, worker, state, memory, ref, Math.max(1L, level.getGameTime()));
            invokeBuilder("finish", new Class<?>[]{de.villagerplus.job.Ctx.class,
                de.villagerplus.village.VillageRef.class, BlockPos.class}, context, ref, completed);
            boolean persisted = Builder.builtOf(ref).stream().anyMatch(building -> building.origin().equals(completed)
                && building.type().equals(Blueprints.GRANARY) && !building.palette().isEmpty());
            checks += require(!palettes.containsKey(completed.asLong()) && !footprints.containsKey(completed.asLong()) && persisted
                && palettes.get(neighbor.asLong()) == marker,
                "successful completion persists its palette, releases palette/footprint caches and preserves a neighboring plan");
        } finally {
            if (worker != null) {
                worker.discard();
                de.villagerplus.village.Villages.forget(worker.getUUID());
            }
            for (BlockPos site : sites) {
                long key = site.asLong();
                if (hadPalette.contains(key)) palettes.put(key, savedPalettes.get(key));
                else palettes.remove(key);
                if (hadFootprint.contains(key)) footprints.put(key, savedFootprints.get(key));
                else footprints.remove(key);
            }
            if (savedRejectedPlots == null) rejectedPlots.remove(villageKey);
            else rejectedPlots.put(villageKey, savedRejectedPlots);
        }
        return checks;
    }

    @SuppressWarnings("unchecked")
    private static <T> T staticMap(String name) {
        try {
            var field = Builder.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Builder palette check: could not inspect " + name, failure);
        }
    }

    private static void invokeBuilder(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            var method = Builder.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(null, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Builder palette check: could not invoke " + name, failure);
        }
    }

    private static boolean connected(Set<BlockPos> path, BlockPos start, BlockPos goal) {
        BlockPos source = path.stream().filter(p -> p.distManhattan(start) <= 1).findFirst().orElse(null);
        if (source == null) return false;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(source); visited.add(source);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (current.distManhattan(goal) <= 1) return true;
            for (BlockPos next : new BlockPos[]{current.north(), current.south(), current.east(), current.west()})
                if (path.contains(next) && visited.add(next)) queue.addLast(next);
        }
        return false;
    }

    private static int require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Builder path: " + message);
        return 1;
    }
}
