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
            checks += require(restoredSearchSave.extra.civicSiteSearch.orElseThrow().equals(savedSearch),
                "civic site phase, variants, pass and candidate cursor persist through village save/load");
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
            flatField.setAccessible(true);
            tolerantField.setAccessible(true);
            List<Set<Long>> flat = (List<Set<Long>>) flatField.get(search);
            List<Set<Long>> tolerant = (List<Set<Long>>) tolerantField.get(search);
            for (int i = 0; i < flat.size(); i++) {
                flat.get(i).add(100L + i);
                tolerant.get(i).add(200L + i);
            }
            var advance = searchType.getDeclaredMethod("advancePass", long.class);
            advance.setAccessible(true);
            boolean firstPassCompleted = (boolean) advance.invoke(search, 100L);
            int checks = require(!firstPassCompleted && flat.get(0).contains(100L)
                && tolerant.get(0).contains(200L),
                "civic visited columns remain cached between search passes");
            boolean fullCycleCompleted = false;
            for (int i = 0; i < 3; i++) fullCycleCompleted = (boolean) advance.invoke(search, 200L + i);
            boolean cachesCleared = flat.stream().allMatch(Set::isEmpty) && tolerant.stream().allMatch(Set::isEmpty);
            checks += require(fullCycleCompleted && cachesCleared,
                "a full civic re-scan clears visited columns so changed plots are reevaluated");
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
