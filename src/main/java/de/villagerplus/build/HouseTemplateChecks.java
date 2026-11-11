package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

/** In-world check that small, recognizable village homes remain eligible as construction templates. */
public final class HouseTemplateChecks {
    private HouseTemplateChecks() { }

    public static int run(ServerLevel level, BlockPos bed) {
        Map<net.minecraft.world.level.block.Block, Integer> firstTally = new LinkedHashMap<>();
        firstTally.put(Blocks.SPRUCE_PLANKS, 8);
        firstTally.put(Blocks.OAK_PLANKS, 8);
        Map<net.minecraft.world.level.block.Block, Integer> reversedTally = new LinkedHashMap<>();
        reversedTally.put(Blocks.OAK_PLANKS, 8);
        reversedTally.put(Blocks.SPRUCE_PLANKS, 8);
        require(Palette.top(firstTally, null, Blocks.BRICKS) == Palette.top(reversedTally, null, Blocks.BRICKS),
            "equal-weight village palette candidates resolve identically regardless of registry iteration order");
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Map<BlockPos, BlockState> designBefore = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(bed.offset(-2, -1, -2), bed.offset(23, 8, 8))) {
            BlockPos pos = p.immutable(); level.getChunkAt(pos); before.put(pos, level.getBlockState(pos));
        }
        try {
            for (BlockPos p : BlockPos.betweenClosed(bed.offset(0, -1, 0), bed.offset(3, -1, 3)))
                level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            // Compact 5x4 cottage: enough front width for a clear door frame and paired windows.
            for (int x = 0; x < 5; x++) for (int z = 0; z < 4; z++) {
                if (x == 0 || x == 4 || z == 0 || z == 3) {
                    if (!(x == 2 && z == 0)) level.setBlock(bed.offset(x, 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
                }
            }
            BlockPos door = bed.offset(2, 1, 0);
            BlockState lowerDoor = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            level.setBlock(door, lowerDoor, 3);
            level.setBlock(door.above(), lowerDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            level.setBlock(bed.offset(1, 2, 0), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            level.setBlock(bed.offset(3, 2, 0), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            // Keep an unavailable iron door in the copied home to exercise fallback conversion separately.
            BlockPos fallbackDoor = bed.offset(4, 1, 1);
            BlockState lowerFallbackDoor = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            level.setBlock(fallbackDoor, lowerFallbackDoor, 3);
            level.setBlock(fallbackDoor.above(), lowerFallbackDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            // Connected display stacks sit just beyond the side wall; capture should clear them without shifting source steps.
            level.setBlock(bed.offset(5, 1, 2), Blocks.HAY_BLOCK.defaultBlockState(), 3);
            BlockState horizontalLog = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
            level.setBlock(bed.offset(5, 1, 1), horizontalLog, 3);
            level.setBlock(bed.offset(5, 2, 1), horizontalLog, 3);
            // Roof stairs sit on the back corners only; the front corners stay solid wall so the finished facade has room for
            // a paired window outside the door frame (finishedFacade never replaces stairs or slabs).
            for (int x = 0; x < 5; x++) for (int z = 0; z < 4; z++) {
                if ((x == 0 || x == 4) && z == 3)
                    level.setBlock(bed.offset(x, 2, z), Blocks.SPRUCE_STAIRS.defaultBlockState(), 3);
                else if ((x == 0 || x == 4) && z == 0)
                    level.setBlock(bed.offset(x, 2, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            }
			for (int x = 1; x <= 3; x++) for (int z = 1; z <= 2; z++)
				level.setBlock(bed.offset(x, 2, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            BlockPos head = bed.offset(2, 1, 2);
            level.setBlock(bed.offset(1, 1, 2), Blocks.BED.white().defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(head, Blocks.BED.white().defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD), 3);

            HouseTemplate template = HouseTemplate.capture(level, bed.offset(1, 1, 2), bed);
            require(template != null, "compact small home with a real door and bed is accepted as a village template");
            require(template.sizeTier() == Architect.SizeTier.COMPACT,
                "small single-storey source homes are classified as compact templates");
			// Seven by nine is a common generated-house footprint and guards the home-spacing fallback.
			// Leave two clear columns after the compact display stack; the next synthetic home must not
			// become one connected flood-fill component with that earlier fixture.
			SourceFixture standardSource = placeHouseSource(level, bed.offset(8, 1, 0), 7, 9, false);
			HouseTemplate standardTemplate = HouseTemplate.capture(level, standardSource.bed(), bed.offset(8, 1, 0));
			SourceFixture multiSource = placeHouseSource(level, bed.offset(16, 1, 0), 7, 7, true);
			HouseTemplate multiTemplate = HouseTemplate.capture(level, multiSource.bed(), bed.offset(16, 1, 0));
			require(standardTemplate != null && standardTemplate.sizeTier() == Architect.SizeTier.STANDARD,
				"medium single-storey source home is classified as a standard template");
			require(multiTemplate != null && multiTemplate.sizeTier() == Architect.SizeTier.MULTI_STOREY,
				"larger source home with a real upper floor is classified as multi-storey");
			HouseTemplate.Footprint nineBlockRoom = HouseTemplate.homeSpacingFootprint(level, standardSource.bed());
			require(nineBlockRoom != null
				&& nineBlockRoom.minX() == standardSource.bed().getX() - 1
				&& nineBlockRoom.maxX() == standardSource.bed().getX() + 5
				&& nineBlockRoom.minY() == standardSource.bed().getY() - 1
				&& nineBlockRoom.maxY() == standardSource.bed().getY() + 3
				&& nineBlockRoom.minZ() == standardSource.bed().getZ() - 7
				&& nineBlockRoom.maxZ() == standardSource.bed().getZ() + 1,
				"the bounded home-footprint fallback resolves the full offset 7-by-9 house");
			checkFinishedTemplateQuality(template, bed.offset(8, 1, 0), Architect.SizeTier.COMPACT, template.sourceBlockCount());
			checkFinishedTemplateQuality(standardTemplate, bed.offset(8, 1, 0), Architect.SizeTier.STANDARD, standardSource.blockCount());
			checkFinishedTemplateQuality(multiTemplate, bed.offset(8, 1, 0), Architect.SizeTier.MULTI_STOREY, multiSource.blockCount());
            List<Step> compactFacadeSteps = template.steps(bed.offset(8, 1, 0), Direction.NORTH, 100, false,
                block -> Supply.substitute(block, null, Identifier.withDefaultNamespace("plains")), true);
            BlockPos compactAnchor = bed.offset(8, 1, 0);
            boolean clearsHay = compactFacadeSteps.stream().anyMatch(step -> step.pos().equals(compactAnchor.offset(4, 0, 0))
                && step.free() && step.linkedTo() == null && step.make().apply(Blocks.HAY_BLOCK).isAir());
            // A linked bed half also has no paid item, but its make-function requires a bed block.
            // Only probe true free placement/clear steps with generic source blocks.
            boolean clearsLogStack = compactFacadeSteps.stream().filter(step -> step.free() && step.linkedTo() == null
                    && step.make().apply(Blocks.SPRUCE_LOG).isAir())
                .map(Step::pos).collect(java.util.stream.Collectors.toSet()).containsAll(Set.of(
                    compactAnchor.offset(4, 0, -1), compactAnchor.offset(4, 1, -1)));
            long compactEntryLogs = compactFacadeSteps.stream().filter(step -> step.item() == Blocks.SPRUCE_LOG.asItem()).count();
            require(clearsHay && clearsLogStack && compactEntryLogs == 4
                && compactFacadeSteps.stream().noneMatch(step -> step.item() == Blocks.HAY_BLOCK.asItem()
                    || step.item() == Blocks.BOOKSHELF.asItem() || step.item() == Blocks.CHISELED_BOOKSHELF.asItem()),
                "compact copied homes clear connected hay/log display stacks in-place and keep only the paid doorway frame, paired windows, canopy, and roof detail");
			BlockPos terracottaWall = bed.offset(0, 1, 1);
			BlockState oldTerracottaWall = level.getBlockState(terracottaWall);
			try {
				level.setBlock(terracottaWall, Blocks.TERRACOTTA.defaultBlockState(), 3);
				BlockState terracotta = level.getBlockState(terracottaWall);
				require(terracotta.is(net.minecraft.tags.BlockTags.BADLANDS_TERRACOTTA)
					&& !HouseTemplate.built(level, terracottaWall)
					&& HouseTemplate.built(level, terracottaWall, true),
					"natural terracotta stays excluded from flood-fill but is retained from a recognized CTOV structure piece");
			} finally {
				level.setBlock(terracottaWall, oldTerracottaWall, 3);
			}
            var steps = template.steps(bed.offset(8, 1, 0), Direction.SOUTH, 100);
            require(steps.stream().anyMatch(s -> s.item() == Blocks.SPRUCE_PLANKS.asItem())
                && steps.stream().anyMatch(s -> s.item() == Blocks.SPRUCE_STAIRS.asItem()),
                "copied home retains its neighborhood palette and roof detail");
            var repeatedSteps = template.steps(bed.offset(8, 1, 0), Direction.SOUTH, 100);
            require(stepSignature(steps).equals(stepSignature(repeatedSteps)),
                "the same accepted house template produces the same ordered plan when repeated");
            BlockPos target = bed.offset(8, 1, 0);
            Step originalDoor = steps.stream().filter(step -> step.item() == Blocks.IRON_DOOR.asItem())
                .findFirst().orElse(null);
            VillageStyle unavailable = new VillageStyle(Blocks.BRICKS, Blocks.BRICKS, Blocks.BRICKS,
                Blocks.BRICK_STAIRS, Blocks.IRON_DOOR, Blocks.IRON_BARS);
            var fallbackSteps = template.steps(target, Direction.SOUTH, 100, false,
                block -> Supply.substitute(block, unavailable, Identifier.withDefaultNamespace("desert")), true);
            Step fallbackLower = fallbackSteps.stream().filter(step -> step.item() == Blocks.OAK_DOOR.asItem())
                .findFirst().orElse(null);
            Step fallbackUpper = fallbackSteps.stream().filter(step -> fallbackLower != null
                && fallbackLower.pos().equals(step.linkedTo())).findFirst().orElse(null);
            require(fallbackLower != null && fallbackUpper != null && fallbackUpper.linkedTo().equals(fallbackLower.pos())
                && fallbackLower.make().apply(Blocks.OAK_DOOR).getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && fallbackUpper.make().apply(Blocks.OAK_DOOR).getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                && originalDoor != null && fallbackLower.make().apply(Blocks.OAK_DOOR).getValue(DoorBlock.FACING)
                    == originalDoor.make().apply(Blocks.IRON_DOOR).getValue(DoorBlock.FACING),
                "unobtainable iron template doors use the desert wood palette and preserve both oriented halves");
            HouseTemplate.Bounds targetBounds = template.bounds(Direction.SOUTH, false);
            for (int x = targetBounds.minX(); x <= targetBounds.maxX(); x++)
                for (int z = targetBounds.minZ(); z <= targetBounds.maxZ(); z++)
                    level.setBlock(target.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            require(template.supportedFoundation(level, target, Direction.SOUTH, false),
                "level house site has solid support below every foundation block");
            int minY = template.steps(target, Direction.SOUTH, 100).stream().mapToInt(s -> s.pos().getY()).min().orElseThrow();
            var foundationStep = steps.stream().filter(s -> s.pos().getY() == minY).findFirst().orElseThrow();
            level.setBlock(foundationStep.pos().below(), Blocks.AIR.defaultBlockState(), 3);
            require(!template.supportedFoundation(level, target, Direction.SOUTH, false),
                "unsupported foundation edge rejects a floating copied-house site");

            require(Blueprints.sizeTierForOrdinal(0) == Architect.SizeTier.COMPACT
                && Blueprints.sizeTierForOrdinal(1) == Architect.SizeTier.STANDARD
                && Blueprints.sizeTierForOrdinal(2) == Architect.SizeTier.MULTI_STOREY
                && Blueprints.sizeTierForOrdinal(3) == Architect.SizeTier.COMPACT,
                "completed buildings advance through a repeatable compact, standard, multi-storey cycle");

            Palette villagePalette = new Palette(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG,
                Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE,
                Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE);
            BlockPos designOrigin = bed.offset(10, 1, 0);
            long designSeed = 123456L & ~(1L << 6);
            Architect.Design compactHome = Architect.design(Architect.Kind.HOUSE,
                Architect.withSizeTier(designSeed, Architect.SizeTier.COMPACT));
            Architect.Design standardHome = Architect.design(Architect.Kind.HOUSE,
                Architect.withSizeTier(designSeed, Architect.SizeTier.STANDARD));
            Architect.Design multiHome = Architect.design(Architect.Kind.HOUSE,
                Architect.withSizeTier(designSeed, Architect.SizeTier.MULTI_STOREY));
            require(compactHome.hw() == 2 && compactHome.hd() == 2 && compactHome.wallH() == 3
                && standardHome.hw() >= 3 && standardHome.hd() >= 3 && standardHome.wallH() <= 5
                && multiHome.hw() >= 3 && multiHome.hd() >= 3 && multiHome.wallH() == 6
                && Architect.multiStorey(multiHome),
                "residential plans have visibly different compact, standard, and upper-storey dimensions");

            Architect.Design chimneyMulti = null;
            for (long seed = 0; seed < 16 && chimneyMulti == null; seed++) {
                Architect.Design candidate = Architect.design(Architect.Kind.GRANARY,
                    Architect.withSizeTier(seed, Architect.SizeTier.MULTI_STOREY));
                if (candidate.chimney()) chimneyMulti = candidate;
            }
            int chimneyTop = chimneyMulti == null ? Integer.MAX_VALUE : chimneyMulti.wallH()
                + (chimneyMulti.ridgeAlongPerp() ? chimneyMulti.hd() : chimneyMulti.hw()) + chimneyMulti.overhang() + 1;
            require(chimneyMulti != null && chimneyMulti.headroom() > chimneyTop,
                "multi-storey site clearance includes the optional chimney campfire at the exclusive top bound");

            Direction designDir = Direction.SOUTH;
            Architect.Design compactPorch = Architect.design(Architect.Kind.HOUSE,
                Architect.withSizeTier(designSeed, Architect.SizeTier.COMPACT));
            Architect.Design compactOpenEntry = Architect.design(Architect.Kind.HOUSE,
                Architect.withSizeTier(designSeed | (1L << 6), Architect.SizeTier.COMPACT));
            int compactDoorI = Math.max(-compactPorch.hw() + 1, Math.min(compactPorch.hw() - 1, compactPorch.doorOffset()));
            BlockPos leftPost = designOrigin.relative(designDir.getClockWise(), compactDoorI - 1).relative(designDir, compactPorch.hd() + 2);
            BlockPos rightPost = designOrigin.relative(designDir.getClockWise(), compactDoorI + 1).relative(designDir, compactPorch.hd() + 2);
            BlockPos canopy = designOrigin.relative(designDir.getClockWise(), compactDoorI).relative(designDir, compactPorch.hd() + 1).above(2);
            List<Step> compactPorchSteps = Architect.build(compactPorch, villagePalette, designOrigin, designDir);
            List<Step> compactOpenSteps = Architect.build(compactOpenEntry, villagePalette, designOrigin, designDir);
            require(compactPorch.porch() && !compactOpenEntry.porch()
                && compactPorchSteps.stream().anyMatch(step -> step.pos().equals(leftPost) && step.item() == villagePalette.fence().asItem())
                && compactPorchSteps.stream().anyMatch(step -> step.pos().equals(rightPost) && step.item() == villagePalette.fence().asItem())
                && compactPorchSteps.stream().anyMatch(step -> step.pos().equals(canopy) && step.item() == villagePalette.roofSlab().asItem())
                && compactOpenSteps.stream().noneMatch(step -> step.pos().equals(leftPost) || step.pos().equals(rightPost)),
                "compact cottages deterministically vary between an open entry and a palette-matched covered porch");
            List<Step> multiHomeSteps = Architect.build(multiHome, villagePalette, designOrigin, designDir);
            int stairI = multiHome.hw() - 1;
            int stairJ = multiHome.hd() - 2;
            int expectedUpperFloorCells = (multiHome.hw() * 2 - 1) * (multiHome.hd() * 2 - 1) - 3;
            int upperFloorCells = 0;
            for (int i = -multiHome.hw() + 1; i < multiHome.hw(); i++) {
                for (int j = -multiHome.hd() + 1; j < multiHome.hd(); j++) {
                    if (i == stairI && j >= stairJ - 2 && j <= stairJ) continue;
                    BlockPos floorPos = designOrigin.relative(designDir.getClockWise(), i).relative(designDir, j).above(3);
                    if (multiHomeSteps.stream().anyMatch(step -> step.pos().equals(floorPos)
                        && step.item() == villagePalette.floor().asItem())) upperFloorCells++;
                }
            }
            int stairCells = 0;
            for (int levelY = 0; levelY < 3; levelY++) {
                BlockPos stairPos = designOrigin.relative(designDir.getClockWise(), multiHome.hw() - 1)
                    .relative(designDir, multiHome.hd() - 2 - levelY).above(levelY);
                if (multiHomeSteps.stream().anyMatch(step -> step.pos().equals(stairPos)
                    && step.item() == villagePalette.roofStair().asItem())) stairCells++;
            }
            boolean stairwellOpen = true;
            for (int step = 0; step < 3; step++) {
                BlockPos opening = designOrigin.relative(designDir.getClockWise(), stairI)
                    .relative(designDir, stairJ - step).above(3);
                stairwellOpen &= multiHomeSteps.stream().noneMatch(plan -> plan.pos().equals(opening));
            }
            require(upperFloorCells == expectedUpperFloorCells && stairCells == 3
                && stairwellOpen,
                "multi-storey home geometry: upperFloorCells=" + upperFloorCells + "/" + expectedUpperFloorCells
                    + ", stairCells=" + stairCells + ", full stairwell=" + stairwellOpen);

            placeBlueprintForCheck(level, multiHomeSteps, designBefore);
            require(multiStoreyPathWalkable(level, multiHome, designOrigin, designDir),
                "in-world multi-storey plan has a clear entrance-to-stair-to-landing route");
            require(hasUpstairsLight(level, multiHome, designOrigin, designDir),
                "multi-storey plan lights the upper floor without occupying the stair route");
            BlockPos obstructedHeadroom = designOrigin.relative(designDir.getClockWise(), stairI)
                .relative(designDir, stairJ - 1).above(3);
            BlockState previousHeadroom = level.getBlockState(obstructedHeadroom);
            boolean rejectsBlockedStairwell;
            try {
                level.setBlock(obstructedHeadroom, villagePalette.floor().defaultBlockState(), 3);
                rejectsBlockedStairwell = !multiStoreyPathWalkable(level, multiHome, designOrigin, designDir);
            } finally {
                level.setBlock(obstructedHeadroom, previousHeadroom, 3);
            }
            require(rejectsBlockedStairwell,
                "the in-world route check rejects an upper-floor block that removes stair headroom");

            Architect.Design compactFunction = Architect.design(Architect.Kind.GRANARY,
                Architect.withSizeTier(designSeed, Architect.SizeTier.COMPACT));
            Architect.Design standardFunction = Architect.design(Architect.Kind.GRANARY,
                Architect.withSizeTier(designSeed, Architect.SizeTier.STANDARD));
            Architect.Design multiFunction = Architect.design(Architect.Kind.GRANARY,
                Architect.withSizeTier(designSeed, Architect.SizeTier.MULTI_STOREY));
            Architect.Design compactFunctionPorch = Architect.design(Architect.Kind.GRANARY,
                Architect.withSizeTier(designSeed | (1L << 6), Architect.SizeTier.COMPACT));
            Architect.Design multiFunctionPorch = Architect.design(Architect.Kind.GRANARY,
                Architect.withSizeTier(designSeed | (1L << 7), Architect.SizeTier.MULTI_STOREY));
            List<Step> multiFunctionSteps = Architect.build(multiFunction, villagePalette, designOrigin, designDir);
            List<Step> compactFunctionPorchSteps = Architect.build(compactFunctionPorch, villagePalette, designOrigin, designDir);
            List<Step> multiFunctionPorchSteps = Architect.build(multiFunctionPorch, villagePalette, designOrigin, designDir);
            require(compactFunction.hw() == 3 && compactFunction.hd() == 2
                && standardFunction.hw() >= 3 && standardFunction.hd() >= 3
                && multiFunction.wallH() == 6 && Architect.multiStorey(multiFunction)
                && multiFunctionSteps.stream().anyMatch(step -> step.pos().getY() == designOrigin.getY() + 3
                    && step.item() == villagePalette.floor().asItem())
                && multiFunctionSteps.stream().anyMatch(step -> step.item() == Blocks.SPRUCE_PLANKS.asItem())
                && compactFunctionPorch.porch() && !compactFunction.porch()
                && multiFunctionPorch.porch() && !multiFunction.porch()
                && hasPalettePorch(compactFunctionPorch, villagePalette, designOrigin, designDir, compactFunctionPorchSteps)
                && hasPalettePorch(multiFunctionPorch, villagePalette, designOrigin, designDir, multiFunctionPorchSteps)
                && hasFrontPath(compactFunctionPorch, designOrigin, designDir, compactFunctionPorchSteps)
                && hasFrontPath(multiFunctionPorch, designOrigin, designDir, multiFunctionPorchSteps)
                && hasThreeStepStair(multiFunctionPorch, villagePalette, designOrigin, designDir, multiFunctionPorchSteps),
                "compact and multi-storey functional plans vary their palette-matched porch while preserving the entrance path and stair");

			int toolVariant = Architect.withRoleMarkerVersion(Architect.Kind.TOOLSHED, (int) (designSeed << 2));
			Architect.Design toolShed = Architect.designForPlan(Architect.Kind.TOOLSHED, toolVariant);
			List<Step> toolShedSteps = Architect.build(toolShed, villagePalette, designOrigin, designDir);
			int toolDoorI = Math.max(-toolShed.hw() + 1, Math.min(toolShed.hw() - 1, toolShed.doorOffset()));
			int canopyFront = toolShed.hd() + 2;
			boolean paidSupports = true;
			Set<BlockPos> canopyPositions = new HashSet<>();
			for (int side : new int[] {-2, 2}) for (int y = 0; y <= 1; y++) {
				BlockPos support = designOrigin.relative(designDir.getClockWise(), toolDoorI + side)
					.relative(designDir, canopyFront).above(y);
				canopyPositions.add(support);
				paidSupports &= toolShedSteps.stream().anyMatch(step -> step.pos().equals(support)
					&& step.item() == villagePalette.fence().asItem() && step.cost() == 1);
			}
			boolean paidRoof = true;
			for (int i = toolDoorI - 2; i <= toolDoorI + 2; i++) {
				BlockPos roof = designOrigin.relative(designDir.getClockWise(), i)
					.relative(designDir, canopyFront).above(2);
				canopyPositions.add(roof);
				paidRoof &= toolShedSteps.stream().anyMatch(step -> step.pos().equals(roof)
					&& step.item() == villagePalette.roofSlab().asItem() && step.cost() == 1);
			}
			boolean clearCenterApproach = true;
			for (int j = toolShed.hd() + 1; j <= toolShed.hd() + 3; j++) for (int y = 0; y <= 1; y++) {
				BlockPos approachCell = designOrigin.relative(designDir.getClockWise(), toolDoorI)
					.relative(designDir, j).above(y);
				clearCenterApproach &= toolShedSteps.stream().noneMatch(step -> step.pos().equals(approachCell));
			}
			Set<BlockPos> toolShedPositions = new HashSet<>();
			boolean withinSiteMargin = true;
			for (BlockPos canopyPosition : canopyPositions) {
				BlockPos local = canopyPosition.subtract(designOrigin);
				int lateral = projection(local, designDir.getClockWise());
				int forward = projection(local, designDir);
				withinSiteMargin &= Math.abs(lateral) <= toolShed.half() + 1 && Math.abs(forward) <= toolShed.half() + 1;
			}
			require(paidSupports && paidRoof && clearCenterApproach && withinSiteMargin
				&& toolShedSteps.stream().allMatch(step -> toolShedPositions.add(step.pos())),
				"the saved tool-shed marker plan adds a paid five-wide work canopy beyond the facade while keeping its center path clear and within the site margin");

			int schoolVariant = Architect.withRoleMarkerVersion(Architect.Kind.SCHOOL,
				(int) (Architect.withSizeTier(designSeed, Architect.SizeTier.STANDARD) << 2));
			Architect.Design school = Architect.designForPlan(Architect.Kind.SCHOOL, schoolVariant);
			Block schoolStone = Supply.obtainable(Blocks.STONE_BRICKS) ? Blocks.STONE_BRICKS : Blocks.COBBLESTONE;
			boolean schoolFacadeReadable = school.useBase() && school.halfTimber() && school.frameBeams()
				&& school.porch() && !school.ridgeAlongPerp() && !Architect.multiStorey(school);
			boolean schoolClassroomReadable = true;
			for (Direction facing : Direction.Plane.HORIZONTAL) {
				List<Step> schoolSteps = Architect.build(school, villagePalette, designOrigin, facing);
				Map<BlockPos, Step> schoolByPos = new HashMap<>();
				for (Step step : schoolSteps) schoolByPos.put(step.pos(), step);
				Direction lateral = facing.getClockWise();
				int schoolDoorI = Math.max(-school.hw() + 1, Math.min(school.hw() - 1, school.doorOffset()));
				BlockPos schoolDoor = designOrigin.relative(lateral, schoolDoorI).relative(facing, school.hd());
				Step lower = schoolByPos.get(schoolDoor);
				Step upper = schoolByPos.get(schoolDoor.above());
				Step lintel = schoolByPos.get(schoolDoor.above(2));
				BlockPos leftWindow = designOrigin.relative(lateral, -(school.hw() - 1)).relative(facing, school.hd()).above();
				BlockPos rightWindow = designOrigin.relative(lateral, school.hw() - 1).relative(facing, school.hd()).above();
				boolean pairedWindows = schoolWindow(schoolByPos.get(leftWindow), villagePalette.window())
					&& schoolWindow(schoolByPos.get(rightWindow), villagePalette.window());
				boolean pairedStoneHeads = schoolStone(schoolByPos.get(leftWindow.above()), schoolStone)
					&& schoolStone(schoolByPos.get(rightWindow.above()), schoolStone);
				boolean timberEntry = schoolTimber(schoolByPos.get(schoolDoor.relative(lateral)), villagePalette.frame())
					&& schoolTimber(schoolByPos.get(schoolDoor.relative(lateral.getOpposite())), villagePalette.frame());
				boolean timberStoneLintel = schoolTimber(schoolByPos.get(schoolDoor.relative(lateral).above(2)), villagePalette.frame())
					&& schoolStone(lintel, schoolStone)
					&& schoolTimber(schoolByPos.get(schoolDoor.relative(lateral.getOpposite()).above(2)), villagePalette.frame());
				BlockPos schoolCanopy = designOrigin.relative(lateral, schoolDoorI).relative(facing, school.hd() + 1).above(2);
				boolean recognizableEntry = lower != null && lower.item() == villagePalette.door().asItem()
					&& lower.cost() == 1 && lower.tag() != null && lower.linkedTo() == null
					&& lower.make().apply(villagePalette.door()).getValue(DoorBlock.FACING) == facing.getOpposite()
					&& upper != null && upper.linkedTo() != null && upper.linkedTo().equals(schoolDoor) && upper.cost() == 0
					&& schoolByPos.containsKey(schoolCanopy) && schoolByPos.get(schoolCanopy).item() == villagePalette.roofSlab().asItem();
				long exteriorBookBlocks = schoolSteps.stream()
					.filter(step -> step.item() == Blocks.BOOKSHELF.asItem() || step.item() == Blocks.CHISELED_BOOKSHELF.asItem())
					.filter(step -> {
						BlockPos relative = step.pos().subtract(designOrigin);
						return Math.abs(projection(relative, lateral)) == school.hw()
							|| Math.abs(projection(relative, facing)) == school.hd();
					}).count();
				boolean paidSchoolSteps = schoolSteps.stream().allMatch(step -> step.linkedTo() != null || step.free()
					? step.cost() == 0 : step.cost() == 1 && (step.item() != null || step.tag() != null));
				boolean schoolWithinSite = schoolSteps.stream().allMatch(step -> {
					BlockPos relative = step.pos().subtract(designOrigin);
					return Math.abs(projection(relative, lateral)) <= school.half()
						&& Math.abs(projection(relative, facing)) <= school.half();
				});
				schoolFacadeReadable &= pairedWindows && pairedStoneHeads && timberEntry && timberStoneLintel
					&& recognizableEntry && exteriorBookBlocks == 0 && paidSchoolSteps && schoolWithinSite;

				int back = -school.hd() + 1;
				BlockPos leftShelf = designOrigin.relative(lateral, -2).relative(facing, back);
				BlockPos rightShelf = designOrigin.relative(lateral, 2).relative(facing, back);
				BlockPos lecternPos = designOrigin.relative(facing, back);
				Step leftShelfStep = schoolByPos.get(leftShelf);
				Step rightShelfStep = schoolByPos.get(rightShelf);
				Step lecternStep = schoolByPos.get(lecternPos);
				Step leftSeat = schoolByPos.get(designOrigin.relative(lateral, -1));
				Step rightSeat = schoolByPos.get(designOrigin.relative(lateral, 1));
				boolean classroomItemsInside = schoolSteps.stream()
					.filter(step -> step.item() == Blocks.BOOKSHELF.asItem()
						|| step.item() == Blocks.CHISELED_BOOKSHELF.asItem() || step.item() == Blocks.LECTERN.asItem())
					.allMatch(step -> {
						BlockPos relative = step.pos().subtract(designOrigin);
						return Math.abs(projection(relative, lateral)) < school.hw()
							&& Math.abs(projection(relative, facing)) < school.hd() && relative.getY() == 0;
					});
				schoolClassroomReadable &= schoolShelf(leftShelfStep) && schoolShelf(rightShelfStep)
					&& lecternStep != null && lecternStep.item() == Blocks.LECTERN.asItem() && lecternStep.cost() == 1
					&& lecternStep.make().apply(Blocks.LECTERN).getValue(net.minecraft.world.level.block.LecternBlock.FACING) == facing
					&& schoolSeat(leftSeat, villagePalette.roofStair(), facing)
					&& schoolSeat(rightSeat, villagePalette.roofStair(), facing)
					&& schoolSteps.stream().filter(step -> step.item() == Blocks.BOOKSHELF.asItem()).count() == 2
					&& schoolSteps.stream().noneMatch(step -> step.item() == Blocks.CHISELED_BOOKSHELF.asItem())
					&& schoolSteps.stream().filter(step -> step.item() == Blocks.LECTERN.asItem()).count() == 1
					&& classroomItemsInside;
			}
			require(schoolFacadeReadable,
				"the saved school plan rotates a compact timber-and-stone facade with paired windows, a clear entry and no exterior bookshelves");
			require(schoolClassroomReadable,
				"school bookshelves, teacher lectern and two oriented seats stay inside the classroom for every facing");
			int granaryVariant = Architect.withRoleMarkerVersion(Architect.Kind.GRANARY,
				(int) (Architect.withSizeTier(designSeed, Architect.SizeTier.COMPACT) << 2));
			Architect.Design granary = Architect.designForPlan(Architect.Kind.GRANARY, granaryVariant);
			List<Step> granarySteps = Architect.build(granary, villagePalette, designOrigin, designDir);
			int granaryDoorI = Math.max(-granary.hw() + 1, Math.min(granary.hw() - 1, granary.doorOffset()));
			boolean granaryEntryPathWithinBounds = true;
			for (int j = granary.hd() + 1; j <= granary.hd() + 3; j++) {
				BlockPos pathCell = designOrigin.relative(designDir.getClockWise(), granaryDoorI)
					.relative(designDir, j).below();
				boolean pathCellWithinSite = Math.max(Math.abs(granaryDoorI), j) <= granary.half();
				boolean pathCellPlanned = granarySteps.stream().anyMatch(step -> step.pos().equals(pathCell)
					&& step.free() && step.make().apply(Blocks.AIR).is(Blocks.DIRT_PATH));
				granaryEntryPathWithinBounds &= pathCellWithinSite && pathCellPlanned;
			}
			require(granaryEntryPathWithinBounds,
				"a compact role hut reserves its actual decorated entry path instead of letting it escape the saved site bound");

		Architect.Design broadEaves = new Architect.Design(Architect.Kind.STATION, 4, 1, 3, true, 2,
				true, true, false, false, false, 2, 0, 0, false, 0, 0);
			List<Step> eavesSteps = Architect.build(broadEaves, villagePalette, designOrigin, designDir);
			int furthestStep = eavesSteps.stream().mapToInt(step -> {
				BlockPos relative = step.pos().subtract(designOrigin);
				return Math.max(Math.abs(projection(relative, designDir.getClockWise())), Math.abs(projection(relative, designDir)));
			}).max().orElse(Integer.MAX_VALUE);
			require(furthestStep <= broadEaves.half() + 1,
				"two-block eaves and the decorated approach stay within the site's one-block margin");
			return 27;
        } finally {
            designBefore.forEach((pos, state) -> level.setBlock(pos, state, 3));
            before.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
    }

	private record SourceFixture(BlockPos bed, int blockCount) { }

	private static SourceFixture placeHouseSource(ServerLevel level, BlockPos base, int width, int depth, boolean multiStorey) {
		Set<BlockPos> placed = new HashSet<>();
		int centerX = width / 2;
		for (int x = 1; x < width - 1; x++) for (int z = 1; z < depth - 1; z++) {
			putFixtureBlock(level, placed, base.offset(x, -1, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
		}
		for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
			if (x != 0 && x != width - 1 && z != 0 && z != depth - 1) continue;
			for (int y = 0; y <= 2; y++) {
				if (z == 0 && x == centerX && y <= 1) continue;
				putFixtureBlock(level, placed, base.offset(x, y, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
			}
		}
		if (multiStorey) {
			for (int x = 1; x < width - 1; x++) for (int z = 1; z < depth - 1; z++) {
				putFixtureBlock(level, placed, base.offset(x, 3, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
			}
			// The capture flood follows face-connected blocks. This wall-supported plank joins the upper floor
			// to the perimeter without introducing a material the village cannot supply.
			putFixtureBlock(level, placed, base.offset(0, 3, 3), Blocks.SPRUCE_PLANKS.defaultBlockState());
			for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
				if (x != 0 && x != width - 1 && z != 0 && z != depth - 1) continue;
				for (int y = 4; y <= 5; y++) putFixtureBlock(level, placed, base.offset(x, y, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
			}
		}
		int roofY = multiStorey ? 6 : 3;
		for (int x = 1; x < width - 1; x++) for (int z = 1; z < depth - 1; z++) {
			putFixtureBlock(level, placed, base.offset(x, roofY, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
		}
		for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
			if (x == 0 || x == width - 1 || z == 0 || z == depth - 1)
				putFixtureBlock(level, placed, base.offset(x, roofY, z), Blocks.SPRUCE_STAIRS.defaultBlockState());
		}
		BlockState lowerDoor = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
			.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		putFixtureBlock(level, placed, base.offset(centerX, 0, 0), lowerDoor);
		putFixtureBlock(level, placed, base.offset(centerX, 1, 0), lowerDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		BlockPos foot = base.offset(1, 0, depth - 2);
		putFixtureBlock(level, placed, foot, Blocks.BED.white().defaultBlockState()
			.setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
			putFixtureBlock(level, placed, foot.relative(Direction.EAST), Blocks.BED.white().defaultBlockState()
			.setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
		return new SourceFixture(foot, placed.size());
	}

	private static void putFixtureBlock(ServerLevel level, Set<BlockPos> placed, BlockPos pos, BlockState state) {
		level.setBlock(pos, state, 3);
		placed.add(pos.immutable());
	}

	private static void checkFinishedTemplateQuality(HouseTemplate template, BlockPos anchor,
		Architect.SizeTier tier, int sourceBlockCount) {
		String failure = finishedTemplateQualityFailure(template, anchor, tier, sourceBlockCount);
		require(failure == null, tier + " completed home quality: " + failure);
	}

	private static String finishedTemplateQualityFailure(HouseTemplate template, BlockPos anchor,
		Architect.SizeTier tier, int sourceBlockCount) {
		if (template == null) return "template missing";
		for (Direction direction : Direction.Plane.HORIZONTAL) for (boolean mirrored : new boolean[]{false, true}) {
			// Judge the attainable plan after material fallback; the compact source deliberately contains an iron door.
			List<Step> steps = template.steps(anchor, direction, 100, mirrored,
				block -> Supply.substitute(block, null, Identifier.withDefaultNamespace("plains")), true);
			Set<BlockPos> positions = new HashSet<>();
			HouseTemplate.Bounds bounds = template.bounds(direction, mirrored);
			HouseTemplate.Bounds sourceBounds = template.sourceBounds(direction, mirrored);
			boolean footprintPreserved = bounds.minX() == sourceBounds.minX() && bounds.maxX() == sourceBounds.maxX()
				&& bounds.minZ() == sourceBounds.minZ() && bounds.maxZ() == sourceBounds.maxZ();
			boolean uniqueAndInsideBounds = true;
			boolean affordableSteps = true;
			int paid = 0;
			for (Step step : steps) {
				BlockPos relative = step.pos().subtract(anchor);
				uniqueAndInsideBounds &= positions.add(step.pos())
					&& relative.getX() >= bounds.minX() && relative.getX() <= bounds.maxX()
					&& relative.getZ() >= bounds.minZ() && relative.getZ() <= bounds.maxZ()
					&& relative.getY() <= bounds.maxY();
				if (step.linkedTo() != null || step.free()) affordableSteps &= step.cost() == 0;
				else {
					affordableSteps &= (step.item() != null || step.tag() != null) && step.cost() == 1
						&& (step.tag() != null || Supply.obtainable(Block.byItem(step.item())));
					paid += step.cost();
				}
			}
			// The fixture also has a side iron door for fallback testing. After mirroring it can sort
			// before the front door, so select the actual facade entrance by its final outward facing.
			Step door = steps.stream().filter(step -> step.item() != null && Block.byItem(step.item()) instanceof DoorBlock)
				.filter(step -> step.make().apply(Block.byItem(step.item())).getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
				.filter(step -> step.make().apply(Block.byItem(step.item())).getValue(DoorBlock.FACING) == direction)
				.findFirst().orElse(null);
			String orientation = " direction=" + direction + " mirrored=" + mirrored;
			if (door == null) return "lower door missing" + orientation;
			BlockState doorState = door.make().apply(Block.byItem(door.item()));
			int doorI = projection(door.pos().subtract(anchor), direction.getClockWise());
			int doorJ = projection(door.pos().subtract(anchor), direction);
			long lowerWindows = steps.stream().filter(HouseTemplateChecks::windowStep)
				.filter(step -> step.pos().getY() == door.pos().getY() + 1)
				.filter(step -> projection(step.pos().subtract(anchor), direction) == doorJ).count();
			long upperWindows = steps.stream().filter(HouseTemplateChecks::windowStep)
				.filter(step -> step.pos().getY() > door.pos().getY() + 2)
				.filter(step -> projection(step.pos().subtract(anchor), direction) == doorJ).count();
			long entryFrames = steps.stream().filter(step -> step.item() != null && Block.byItem(step.item()).defaultBlockState().is(BlockTags.LOGS))
				.filter(step -> projection(step.pos().subtract(anchor), direction) == doorJ)
				.filter(step -> Math.abs(projection(step.pos().subtract(anchor), direction.getClockWise()) - doorI) == 1)
				.filter(step -> step.pos().getY() == door.pos().getY()).count();
			long entryCanopy = steps.stream().filter(step -> step.item() != null && Block.byItem(step.item()) instanceof SlabBlock)
				.filter(step -> step.pos().getY() == door.pos().getY() + 2)
				.filter(step -> projection(step.pos().subtract(anchor), direction) == doorJ).count();
			long roofStairs = steps.stream().filter(step -> step.item() != null && Block.byItem(step.item()) instanceof StairBlock).count();
			boolean featureCounts = lowerWindows >= 2 && entryCanopy >= 1 && roofStairs >= 1 && entryFrames >= 2
				&& (tier != Architect.SizeTier.MULTI_STOREY || upperWindows >= 2);
			if (!footprintPreserved) return "facade changed footprint bounds" + orientation;
			if (!uniqueAndInsideBounds) return "plan positions are duplicated or outside bounds" + orientation;
			if (!affordableSteps) return "a free/linked step costs material or a paid step lacks obtainable material" + orientation;
			if (lowerWindows < 2 || upperWindows < 2 && tier == Architect.SizeTier.MULTI_STOREY
				|| entryCanopy < 1 || entryFrames < 2 || roofStairs < 1)
				return "facade details missing (lowerWindows=" + lowerWindows + ", upperWindows=" + upperWindows
					+ ", canopy=" + entryCanopy + ", entryFrames=" + entryFrames + ", roofStairs=" + roofStairs + ")" + orientation
					+ " facadeRow(y=door+1)=" + steps.stream().filter(step -> step.pos().getY() == door.pos().getY() + 1)
						.filter(step -> projection(step.pos().subtract(anchor), direction) == doorJ)
						.sorted(java.util.Comparator.comparingInt(step -> projection(step.pos().subtract(anchor), direction.getClockWise())))
						.map(step -> (projection(step.pos().subtract(anchor), direction.getClockWise()) - doorI) + ":"
							+ (step.item() == null ? "null" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(step.item()).getPath()))
						.collect(java.util.stream.Collectors.joining(","));
			if (doorState.getValue(DoorBlock.FACING) != direction)
				return "rotated doorway faces " + doorState.getValue(DoorBlock.FACING) + orientation;
			if (sourceBlockCount >= 0 && paid > sourceBlockCount + 3)
				return "plan requires " + paid + " paid blocks for " + sourceBlockCount + " source blocks" + orientation;
		}
		return null;
	}

	private static boolean windowStep(Step step) {
		if (step.item() == null) return false;
		String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Block.byItem(step.item())).getPath();
		return path.contains("glass") || path.contains("pane");
	}

	private static int projection(BlockPos relative, Direction direction) {
		return relative.getX() * direction.getStepX() + relative.getZ() * direction.getStepZ();
	}

    private static void placeBlueprintForCheck(ServerLevel level, List<Step> steps, Map<BlockPos, BlockState> before) {
        Map<BlockPos, Step> byPos = new HashMap<>();
        for (Step step : steps) byPos.put(step.pos().immutable(), step);
        int minX = steps.stream().mapToInt(step -> step.pos().getX()).min().orElseThrow() - 1;
        int maxX = steps.stream().mapToInt(step -> step.pos().getX()).max().orElseThrow() + 1;
        int minY = steps.stream().mapToInt(step -> step.pos().getY()).min().orElseThrow() - 1;
        int maxY = steps.stream().mapToInt(step -> step.pos().getY()).max().orElseThrow() + 2;
        int minZ = steps.stream().mapToInt(step -> step.pos().getZ()).min().orElseThrow() - 1;
        int maxZ = steps.stream().mapToInt(step -> step.pos().getZ()).max().orElseThrow() + 1;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockPos immutable = pos.immutable();
            level.getChunkAt(immutable);
            before.put(immutable, level.getBlockState(immutable));
            level.setBlock(immutable, Blocks.AIR.defaultBlockState(), 3);
        }
        for (Step step : steps) {
            Block base = step.item() != null ? Block.byItem(step.item())
                : step.tag() != null ? Blocks.BED.white()
                : step.linkedTo() != null ? linkedBlock(step.linkedTo(), byPos) : Blocks.AIR;
            level.setBlock(step.pos(), step.make().apply(base), 3);
        }
    }

    private static Block linkedBlock(BlockPos linkedTo, Map<BlockPos, Step> byPos) {
        Step source = byPos.get(linkedTo);
        if (source == null) return Blocks.AIR;
        if (source.item() != null) return Block.byItem(source.item());
        if (source.tag() != null) return Blocks.BED.white();
        return source.make().apply(Blocks.AIR).getBlock();
    }

    private static boolean multiStoreyPathWalkable(ServerLevel level, Architect.Design design, BlockPos origin, Direction dir) {
        int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
        Direction perp = dir.getClockWise();
        BlockPos entrance = origin.relative(perp, doorI).relative(dir, design.hd() - 1);
        BlockPos stairBase = origin.relative(perp, design.hw() - 1).relative(dir, design.hd() - 2);
        if (!groundReachesStair(level, design, origin, dir, entrance, stairBase)) return false;

        Direction uphill = dir.getOpposite();
        BlockPos previous = null;
        BlockPos top = null;
        for (int step = 0; step < 3; step++) {
            BlockPos stair = stairBase.relative(uphill, step).above(step);
            BlockState state = level.getBlockState(stair);
            if (!(state.getBlock() instanceof StairBlock)
                || state.getValue(StairBlock.HALF) != Half.BOTTOM
                || state.getValue(StairBlock.FACING) != uphill
                || !clear(level, stair.above()) || !clear(level, stair.above(2))) return false;
            if (previous != null && !stair.equals(previous.relative(uphill).above())) return false;
            previous = stair;
            top = stair;
        }

        BlockPos landing = top.relative(uphill).above();
        BlockState floor = level.getBlockState(landing);
        return floor.isFaceSturdy(level, landing, Direction.UP)
            && clear(level, landing.above()) && clear(level, landing.above(2));
    }

    private static boolean groundReachesStair(ServerLevel level, Architect.Design design, BlockPos origin,
        Direction dir, BlockPos entrance, BlockPos stairBase) {
        if (!groundStandable(level, entrance)) return false;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(entrance);
        seen.add(entrance.asLong());
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(side);
                if (!insideGroundFloor(design, origin, dir, next)) continue;
                if (!groundStandable(level, next)) continue;
                if (horizontalNeighbor(next, stairBase)) return true;
                if (seen.add(next.asLong())) queue.addLast(next);
            }
        }
        return false;
    }

    private static boolean insideGroundFloor(Architect.Design design, BlockPos origin, Direction dir, BlockPos pos) {
        BlockPos relative = pos.subtract(origin);
        Direction perp = dir.getClockWise();
        int i = relative.getX() * perp.getStepX() + relative.getZ() * perp.getStepZ();
        int j = relative.getX() * dir.getStepX() + relative.getZ() * dir.getStepZ();
        return i > -design.hw() && i < design.hw() && j > -design.hd() && j < design.hd();
    }

    private static boolean horizontalNeighbor(BlockPos a, BlockPos b) {
        return a.getY() == b.getY()
            && Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) == 1;
    }

    private static boolean groundStandable(ServerLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        return level.hasChunkAt(ground) && level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
            && clear(level, feet) && clear(level, feet.above());
    }

    private static boolean clear(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean hasUpstairsLight(ServerLevel level, Architect.Design design, BlockPos origin, Direction dir) {
        Direction perp = dir.getClockWise();
        BlockPos lamp = origin.relative(perp, -design.hw() + 1).relative(dir, -design.hd() + 1).above(4);
        BlockState state = level.getBlockState(lamp);
        BlockPos floor = lamp.below();
        return state.is(Blocks.LANTERN) && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    private static boolean hasPalettePorch(Architect.Design design, Palette palette, BlockPos origin, Direction dir, List<Step> steps) {
        if (!design.porch() || palette.fence() == null) return false;
        int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
        BlockPos leftPost = origin.relative(dir.getClockWise(), doorI - 1).relative(dir, design.hd() + 2);
        BlockPos rightPost = origin.relative(dir.getClockWise(), doorI + 1).relative(dir, design.hd() + 2);
        BlockPos canopy = origin.relative(dir.getClockWise(), doorI).relative(dir, design.hd() + 1).above(2);
        return steps.stream().anyMatch(step -> step.pos().equals(leftPost) && step.item() == palette.fence().asItem())
            && steps.stream().anyMatch(step -> step.pos().equals(rightPost) && step.item() == palette.fence().asItem())
            && steps.stream().anyMatch(step -> step.pos().equals(canopy) && step.item() == palette.roofSlab().asItem());
    }

    private static boolean hasFrontPath(Architect.Design design, BlockPos origin, Direction dir, List<Step> steps) {
        int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
        BlockPos path = origin.relative(dir.getClockWise(), doorI).relative(dir, design.hd() + 1).below();
        return steps.stream().anyMatch(step -> step.pos().equals(path) && step.free()
            && step.make().apply(Blocks.AIR).is(Blocks.DIRT_PATH));
    }

    private static boolean hasThreeStepStair(Architect.Design design, Palette palette, BlockPos origin, Direction dir, List<Step> steps) {
        if (!(palette.roofStair() instanceof net.minecraft.world.level.block.StairBlock)) return false;
        int stairI = design.hw() - 1;
        int stairJ = design.hd() - 2;
        for (int levelY = 0; levelY < 3; levelY++) {
            BlockPos stair = origin.relative(dir.getClockWise(), stairI).relative(dir, stairJ - levelY).above(levelY);
            if (steps.stream().noneMatch(step -> step.pos().equals(stair) && step.item() == palette.roofStair().asItem())) return false;
        }
        return true;
    }

    private static List<String> stepSignature(List<Step> steps) {
        return steps.stream().map(step -> step.pos() + "|"
            + (step.item() == null ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(step.item()))
            + "|" + step.cost() + "|" + step.linkedTo()).toList();
    }

    private static boolean schoolWindow(Step step, Block window) {
        return step != null && step.item() == window.asItem() && step.cost() == 1;
    }

    private static boolean schoolStone(Step step, Block stone) {
        return step != null && step.item() == stone.asItem() && step.cost() == 1;
    }

    private static boolean schoolTimber(Step step, Block timber) {
        return timber != null && step != null && step.item() == timber.asItem() && step.cost() == 1;
    }

    private static boolean schoolShelf(Step step) {
        return step != null && step.item() == Blocks.BOOKSHELF.asItem() && step.cost() == 1;
    }

    private static boolean schoolSeat(Step step, Block stair, Direction facing) {
        return stair instanceof StairBlock && step != null && step.item() == stair.asItem() && step.cost() == 1
            && step.make().apply(stair).getValue(StairBlock.FACING) == facing.getOpposite();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("House template: " + message);
    }
}
