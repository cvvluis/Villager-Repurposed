package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Palette;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageMap;
import de.villagerplus.village.VillageRef;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Isolated checks for completed-mine archival, map persistence, and retained shaft blocks. */
public final class MineLifecycleChecks {
	private MineLifecycleChecks() { }

	private record Cell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos at) throws ReflectiveOperationException {
		Map<BlockPos, Cell> before = new HashMap<>();
		Villager worker = null;
		boolean protectedPoiAdded = false;
		BlockPos protectedPoiPos = null;
		int checks = 0;
		BlockPos bellPos = at.immutable();
		BlockPos entrance = at.offset(8, 0, 8);
		BlockPos exposedPassage = entrance.relative(Direction.EAST);
		BlockPos retainedWalkway = entrance.relative(Direction.EAST).below(2);
		int minX = entrance.getX() - 8, maxX = entrance.getX() + 14;
		int minY = entrance.getY() - 8, maxY = entrance.getY() + 5;
		int minZ = entrance.getZ() - 8, maxZ = entrance.getZ() + 8;
		try {
			for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
				level.getChunkAt(new BlockPos(x, entrance.getY(), z));
				for (int y = minY; y <= maxY; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockEntity be = level.getBlockEntity(p);
					before.put(p, new Cell(level.getBlockState(p), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
					level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
				}
			}

			level.setBlock(bellPos, Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, Direction.NORTH), 2);
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) {
				throw new IllegalStateException("Mine lifecycle fixture bell was not created");
			}
			de.villagerplus.village.Villages.noteChunk(level.getChunkAt(bellPos));
            // Exclude persisted test depots so the paid-closure fixture has a known, single public stock.
            // The bell's original attachment is restored from the fixture snapshot in finally.
            VillageData data = new VillageData();
            bell.setAttached(VillagerPlus.VILLAGE, data);
			BlockPos stockPos = entrance.offset(-6, 0, 4);
			level.setBlock(stockPos, Blocks.CHEST.defaultBlockState(), 2);
			net.minecraft.world.Container publicStock = de.villagerplus.village.Lager.publicContainerAt(level, stockPos);
			if (publicStock == null) throw new IllegalStateException("Mine lifecycle public stock chest was not accessible");
			publicStock.clearContent();
			publicStock.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 18));
			publicStock.setItem(1, new ItemStack(Items.SPRUCE_SIGN, 1));
			if (!publicStock.getItem(1).is(Items.SPRUCE_SIGN)) {
				throw new IllegalStateException("Mine lifecycle fixture could not seed the wall sign before registering its chest");
			}
			data.chest = Optional.of(stockPos);
			// Include the first saved horizontal section after the five-block descent.
			VillageData.Mine mine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 6, entrance.getY() - 5, false);
			VillageData.Mine oversizedLegacyMine = new VillageData.Mine(entrance,
				Direction.EAST.get2DDataValue(), Integer.MAX_VALUE, entrance.getY() - 5, false);
			check(Mining.openedMineSections(oversizedLegacyMine) == 5 + Mining.MAX_HORIZONTAL,
				"closure scans only the maximum saved mine route for an oversized legacy section index"); checks++;
			data.mine = Optional.of(mine);
			VillageRef ref = new VillageRef(bellPos, data, bell);
			Palette entrancePalette = new Palette(Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.STRIPPED_SPRUCE_LOG,
				Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB,
				Blocks.GLASS_PANE, Blocks.BIRCH_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE);
			List<Mining.MineEntranceBlock> entrancePlan = Mining.mineEntrancePlan(mine, entrancePalette);
			Map<net.minecraft.world.item.Item, Integer> entranceCost = Mining.mineEntranceCost(mine, entrancePalette);
			Palette birchPortalPalette = new Palette(Blocks.BIRCH_PLANKS, Blocks.BIRCH_PLANKS, Blocks.BIRCH_LOG,
				Blocks.COBBLESTONE, Blocks.BIRCH_PLANKS, Blocks.BIRCH_STAIRS, Blocks.BIRCH_SLAB,
				Blocks.GLASS_PANE, Blocks.BIRCH_PLANKS, Blocks.BIRCH_DOOR, Blocks.BIRCH_FENCE);
			List<Mining.MineEntranceBlock> birchPortalPlan = Mining.mineEntrancePlan(mine, birchPortalPalette);
			Map<net.minecraft.world.item.Item, Integer> birchPortalCost = Mining.mineEntranceCost(mine, birchPortalPalette);
			List<BlockPos> entranceFootings = new java.util.ArrayList<>();
			Map<BlockPos, BlockState> originalFootings = new HashMap<>();
			Direction entranceDirection = Direction.from2DDataValue(mine.dir);
			Direction entranceSide = entranceDirection.getClockWise();
			BlockPos porch = entrance.relative(entranceDirection.getOpposite());
			BlockPos birchEave = porch.relative(entranceSide, -2).above(4);
			BlockPos birchRidge = porch.above(5);
			BlockState birchEaveState = birchPortalPlan.stream().filter(block -> block.pos().equals(birchEave))
				.map(Mining.MineEntranceBlock::state).findFirst().orElse(Blocks.AIR.defaultBlockState());
			boolean birchAccessClear = true;
			for (BlockPos opening : new BlockPos[] {entrance, porch}) {
				for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
					BlockPos clearCell = opening.relative(entranceSide, lane).above(y);
					birchAccessClear &= birchPortalPlan.stream().noneMatch(block -> block.pos().equals(clearCell));
				}
			}
			check(birchPortalPlan.stream().anyMatch(block -> block.state().is(Blocks.BIRCH_WALL_SIGN))
				&& birchPortalCost.getOrDefault(Items.BIRCH_SIGN, 0) == 1
				&& birchPortalCost.getOrDefault(Items.SPRUCE_SIGN, 0) == 0
				&& birchEaveState.is(Blocks.BIRCH_SLAB)
				&& birchEaveState.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
					== net.minecraft.world.level.block.state.properties.SlabType.BOTTOM
				&& birchPortalPlan.stream().anyMatch(block -> block.pos().equals(birchRidge)
					&& block.state().is(Blocks.BIRCH_PLANKS))
				&& birchPortalPlan.stream().filter(block -> block.state().is(Blocks.LANTERN)).count() == 2
				&& birchPortalCost.getOrDefault(Items.LANTERN, 0) == 2
				&& birchAccessClear,
				"the active mine portal uses paid biome-matched sign and roof materials, paired support lanterns, and a clear approach"); checks++;
			for (int sign : new int[] {-2, 2}) {
				entranceFootings.add(entrance.relative(entranceSide, sign).below());
				entranceFootings.add(porch.relative(entranceSide, sign).below());
			}
			List<BlockPos> outerFootings = new java.util.ArrayList<>();
			for (int sign : new int[] {-3, 3}) outerFootings.add(porch.relative(entranceSide, sign).below());
			List<BlockPos> allPortalFootings = new java.util.ArrayList<>(entranceFootings);
			allPortalFootings.addAll(outerFootings);
			for (BlockPos footing : allPortalFootings) {
				originalFootings.put(footing, level.getBlockState(footing));
				if (!level.getBlockState(footing).isFaceSturdy(level, footing, Direction.UP)) {
					level.setBlock(footing, Blocks.COBBLESTONE.defaultBlockState(), 2);
				}
			}
			publicStock.clearContent();
			int entranceCostSlot = 0;
			for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : entranceCost.entrySet()) {
				if (entranceCostSlot >= publicStock.getContainerSize()) {
					throw new IllegalStateException("Mine lifecycle entrance cost exceeded public chest capacity");
				}
				publicStock.setItem(entranceCostSlot++, new ItemStack(entry.getKey(), entry.getValue()));
			}
			BlockPos activeSignPos = entrancePlan.stream()
				.filter(block -> block.state().is(Blocks.SPRUCE_WALL_SIGN))
				.map(Mining.MineEntranceBlock::pos).findFirst()
				.orElseThrow(() -> new IllegalStateException("Mine entrance plan has no paid visitor sign"));
			boolean entranceBuilt = Mining.buildMineEntrance(level, ref, mine, entrancePalette);
			SignBlockEntity activeSign = level.getBlockEntity(activeSignPos) instanceof SignBlockEntity sign ? sign : null;
			String activeSignText = activeSign == null ? "" : signText(activeSign);
			long visitorLanterns = entrancePlan.stream().filter(block -> block.state().is(Blocks.LANTERN)).count();
			boolean entrancePlanBuilt = entrancePlan.stream()
				.allMatch(block -> level.getBlockState(block.pos()).equals(block.state()));
			List<BlockPos> facadeBeams = List.of(
				entrance.relative(entranceSide).above(3), porch.relative(entranceSide).above(3));
			boolean beamsFollowFacade = entrancePlan.stream().filter(block -> facadeBeams.contains(block.pos())).count() == 2
				&& entrancePlan.stream().filter(block -> facadeBeams.contains(block.pos())).allMatch(block ->
					block.state().hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
						&& block.state().getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == entranceSide.getAxis());
			boolean stoneFootedFrame = entranceFootings.stream().allMatch(footing ->
				level.getBlockState(footing.above()).equals(entrancePalette.base().defaultBlockState())
					&& level.getBlockState(footing.above(2)).getBlock() == entrancePalette.frame()
					&& level.getBlockState(footing.above(3)).getBlock() == entrancePalette.frame());
			boolean outerPiersAnchored = outerFootings.stream().allMatch(footing ->
				level.getBlockState(footing.above()).equals(entrancePalette.base().defaultBlockState())
					&& level.getBlockState(footing.above(2)).getBlock() == entrancePalette.frame()
					&& level.getBlockState(footing.above(3)).getBlock() == entrancePalette.frame()
					&& level.getBlockState(footing.above(4)).is(Blocks.LANTERN));
			boolean entranceOpeningClear = true;
			for (BlockPos opening : new BlockPos[] {entrance, porch}) {
				for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
					entranceOpeningClear &= level.getBlockState(opening.relative(entranceSide, lane).above(y)).isAir();
				}
			}
			boolean signFacesVisitors = level.getBlockState(activeSignPos).hasProperty(net.minecraft.world.level.block.WallSignBlock.FACING)
				&& level.getBlockState(activeSignPos).getValue(net.minecraft.world.level.block.WallSignBlock.FACING)
					== entranceDirection.getOpposite();
			List<BlockPos> oldTorchPositions = new java.util.ArrayList<>();
			for (int sign : new int[] {-2, 2}) {
				BlockPos oldTorch = porch.relative(entranceSide, sign).relative(entranceDirection.getOpposite()).above(1);
				oldTorchPositions.add(oldTorch);
				level.setBlock(oldTorch, Blocks.WALL_TORCH.defaultBlockState()
					.setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, entranceDirection.getOpposite()), 2);
			}
			boolean entranceIdempotent = Mining.buildMineEntrance(level, ref, mine, entrancePalette);
			boolean legacyLightsRemoved = oldTorchPositions.stream().allMatch(pos -> level.getBlockState(pos).isAir());
			check(entranceBuilt && entrancePlanBuilt && entranceOpeningClear && stoneFootedFrame && beamsFollowFacade && signFacesVisitors
				&& outerPiersAnchored && visitorLanterns == 2 && legacyLightsRemoved
				&& activeSignText.contains("DORFMINE") && activeSignText.contains("STOLLEN")
				&& entranceCost.getOrDefault(Items.SPRUCE_SIGN, 0) == 1
				&& entranceCost.getOrDefault(Items.LANTERN, 0) == 2
				&& entranceCost.getOrDefault(Items.TORCH, 0) == 0
				&& publicStock.isEmpty() && entranceIdempotent && publicStock.isEmpty()
				&& mine.index == 6 && mine.targetY == entrance.getY() - 5 && !mine.done,
				"paid mine entry has a readable sign, stable outer piers, spaced lanterns, a clear passage, and an unchanged saved route"); checks++;
			for (Mining.MineEntranceBlock block : entrancePlan) level.setBlock(block.pos(), Blocks.AIR.defaultBlockState(), 2);
			for (Map.Entry<BlockPos, BlockState> entry : originalFootings.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 2);
			}
			publicStock.clearContent();
			publicStock.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 18));
			publicStock.setItem(1, new ItemStack(Items.SPRUCE_SIGN, 1));
			Direction portalSide = Direction.EAST.getClockWise();
			for (int section = 0; section < mine.index; section++) for (int lane = -1; lane <= 1; lane++) {
				BlockPos roof = entrance.relative(Direction.EAST, section).below(section).relative(portalSide, lane).above(3);
				clearSkyColumn(level, before, roof);
			}
			setMineTerrainSurface(level, before, mine, entrance.getY());
			Mining.MineSurfaceSealPlan flatPlan = Mining.mineSurfaceSealPlan(level, mine);
			check(flatPlan.loaded() && flatPlan.coverCells().contains(entrance.relative(Direction.EAST).relative(portalSide, -1).below())
				&& flatPlan.roofCells().contains(entrance.relative(Direction.EAST, 5).below(5).relative(portalSide).above(3)),
				"flat terrain plans a continuous surface cap and the saved underground roof"
					+ " [loaded=" + flatPlan.loaded() + ", cover=" + flatPlan.coverCells().size() + ", roof=" + flatPlan.roofCells().size()
					+ ", coverAtEntranceY=" + flatPlan.coverCells().contains(entrance.relative(Direction.EAST).relative(portalSide, -1))
					+ ", coverBelowEntranceY=" + flatPlan.coverCells().contains(entrance.relative(Direction.EAST).relative(portalSide, -1).below())
					+ ", roofSection5=" + flatPlan.roofCells().contains(entrance.relative(Direction.EAST, 5).below(5).relative(portalSide).above(3)) + "]"); checks++;
			setMineTerrainSurface(level, before, mine, entrance.getY() - 2);
			Mining.MineSurfaceSealPlan lowTerrainPlan = Mining.mineSurfaceSealPlan(level, mine);
			BlockPos exposedBase = entrance.relative(Direction.EAST).below();
			check(lowTerrainPlan.loaded()
				&& lowTerrainPlan.coverCells().contains(exposedBase)
				&& lowTerrainPlan.coverCells().contains(exposedBase.above())
				&& lowTerrainPlan.coverCells().contains(exposedBase.above(2))
				&& lowTerrainPlan.coverCells().contains(exposedBase.above(3)),
				"low terrain seals the full exposed tunnel column and its roof instead of leaving a side opening"); checks++;
			setMineTerrainSurface(level, before, mine, entrance.getY() + 3);
			Mining.MineSurfaceSealPlan highTerrainPlan = Mining.mineSurfaceSealPlan(level, mine);
			BlockPos highTerrainRoof = entrance.relative(Direction.EAST).below().above(3);
			check(highTerrainPlan.loaded() && highTerrainPlan.roofCells().contains(highTerrainRoof),
				"high terrain seals the saved tunnel roof below the local surface"); checks++;
			setMineTerrainSurface(level, before, mine, entrance.getY());
			level.setBlock(retainedWalkway, Blocks.OAK_PLANKS.defaultBlockState(), 2);

			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("Mine lifecycle fixture villager was not created");
			worker.setPos(entrance.getX() - 5.5, entrance.getY(), entrance.getZ() + 0.5);
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			level.addFreshEntity(worker);

			long stuckAt = level.getGameTime();
			JobState stalledState = new JobState();
			Ctx stalledContext = new Ctx(level, worker, stalledState,
				worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, stuckAt);
			Mining.MineFrameMaterials birchTimber = Mining.mineFrameMaterials(birchPortalPalette);
			check(birchTimber.fence() == Blocks.BIRCH_FENCE && birchTimber.planks() == Blocks.BIRCH_PLANKS
				&& Mining.supportSection(2) && Mining.supportSection(6) && !Mining.supportSection(3)
				&& Mining.lightSection(3) && Mining.lightSection(9) && !Mining.lightSection(4),
				"inner mine ribs use matching obtainable village timber, with regular four-block supports and alternating six-block lights"); checks++;
			Direction gallerySide = Direction.EAST.getClockWise();
			BlockPos supportBase = entrance.relative(Direction.EAST, 6).below(5);
			for (int lane = -2; lane <= 2; lane++) {
				BlockPos floor = supportBase.relative(gallerySide, lane).below();
				level.setBlock(floor, Math.abs(lane) <= 1 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 2);
				for (int y = 0; y <= 3; y++) level.setBlock(supportBase.relative(gallerySide, lane).above(y), Blocks.AIR.defaultBlockState(), 2);
			}
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.BIRCH_FENCE, 6));
			worker.getInventory().setItem(1, new ItemStack(Items.BIRCH_PLANKS, 5));
			Mining.shape(stalledContext, mine, supportBase, 3, birchTimber);
			boolean ribsPlaced = true;
			for (int sign : new int[] {-1, 1}) for (int y = 0; y < 3; y++) {
				ribsPlaced &= level.getBlockState(supportBase.relative(gallerySide, sign * 2).above(y)).is(Blocks.BIRCH_FENCE);
			}
			for (int lane = -2; lane <= 2; lane++) {
				ribsPlaced &= level.getBlockState(supportBase.relative(gallerySide, lane).above(3)).is(Blocks.BIRCH_PLANKS);
			}
			boolean clearWalkway = true;
			for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
				clearWalkway &= level.getBlockState(supportBase.relative(gallerySide, lane).above(y)).isAir();
			}
			boolean timberPaid = worker.getInventory().isEmpty();
			check(ribsPlaced && clearWalkway && timberPaid,
				"a full birch support bay consumes six fences and five beams while leaving all nine walk cells clear"); checks++;
			BlockPos lightBase = entrance.relative(Direction.EAST, 3).below(3);
			for (int lane = -2; lane <= 2; lane++) {
				BlockPos floor = lightBase.relative(gallerySide, lane).below();
				level.setBlock(floor, Math.abs(lane) <= 1 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 2);
				for (int y = 0; y < 3; y++) level.setBlock(lightBase.relative(gallerySide, lane).above(y), Blocks.AIR.defaultBlockState(), 2);
			}
			BlockPos lightSpot = lightBase.relative(gallerySide, -2);
			BlockPos lightWall = lightSpot.relative(gallerySide, -1);
			level.setBlock(lightWall, Blocks.COBBLESTONE.defaultBlockState(), 2);
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.TORCH));
			VillageData.Mine lightMine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 3,
				entrance.getY() - 5, false);
			Mining.shape(stalledContext, lightMine, lightBase, 3, birchTimber);
			boolean lightWalkwayClear = true;
			for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
				lightWalkwayClear &= level.getBlockState(lightBase.relative(gallerySide, lane).above(y)).isAir();
			}
			check(level.getBlockState(lightSpot).is(Blocks.WALL_TORCH) && worker.getInventory().isEmpty() && lightWalkwayClear,
				"an alternating wall torch is paid from inventory and stays outside the three-wide passage"); checks++;
			worker.setPos(entrance.getX() + 2.5, entrance.getY() - 2, entrance.getZ() + 1.5);
			check(publicStock.getItem(0).getCount() == 18 && publicStock.getItem(1).is(Items.SPRUCE_SIGN),
				"occupied-closure stock is present immediately before barricade"); checks++;
			boolean accessOpenBefore = true;
			for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
				accessOpenBefore &= level.getBlockState(entrance.relative(Direction.SOUTH, lane).above(y)).isAir();
			}
			for (int section = 0; section < mine.index; section++) for (int lane = -1; lane <= 1; lane++) {
				BlockPos roof = entrance.relative(Direction.EAST, section).below(section).relative(portalSide, lane).above(3);
				accessOpenBefore &= level.getBlockState(roof).isAir() && !hasDirectSkyOccluder(level, roof);
			}
			check(!mine.done && accessOpenBefore,
				"an active mine keeps its existing entrance and route open until the normal finish workflow closes it"); checks++;
			check(!Mining.canSeal(level, mine),
				"an occupant in an outer lane of the widened descending shaft prevents mine closure"); checks++;
			Mining.barricade(level, ref, mine, true);
			check(level.getBlockState(entrance.relative(Direction.SOUTH)).isAir()
				&& level.getBlockState(entrance.relative(Direction.SOUTH).above()).isAir()
				&& publicStock.getItem(0).getCount() == 18 && publicStock.getItem(1).getCount() == 1,
				"occupied closure leaves entrance open and consumes no closure materials"
					+ " [portal=" + level.getBlockState(entrance.relative(Direction.SOUTH)) + "/"
					+ level.getBlockState(entrance.relative(Direction.SOUTH).above()) + ", planks="
					+ publicStock.getItem(0) + ", sign=" + publicStock.getItem(1) + "]"); checks++;
			worker.setPos(entrance.getX() + 1.5, entrance.getY() + 1, entrance.getZ() + 0.5);
			check(!Mining.canSeal(level, mine),
				"a villager standing on the surface cap delays closure until the walkway is clear"); checks++;
			Mining.barricade(level, ref, mine, true);
			check(level.getBlockState(exposedPassage).isAir() && publicStock.getItem(0).getCount() == 18
				&& publicStock.getItem(1).getCount() == 1,
				"surface closure does not place a cap beneath an occupant or consume stock"); checks++;
			worker.setPos(entrance.getX() + 1.5, entrance.getY() - 1, entrance.getZ() + 0.5);
			Mining.rememberMineRoute(worker, level, mine);
			double waypointDistance = worker.position().distanceTo(Vec3.atBottomCenterOf(entrance));
			stalledState.walkStart = stuckAt - 241;
			stalledState.lastMoveAt = stuckAt - 241;
			stalledState.bestDist = waypointDistance;
			stalledState.stuckCount = 2;
			check(Mining.approachMineStep(stalledContext, entrance) == Mining.MineWalk.STUCK
				&& stalledState.cooldownUntil == stuckAt + 40
				&& stalledState.idleUntil[0] == stuckAt + 4201
				&& stalledState.idleUntil[1] == stuckAt + 4201
				&& worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty(),
				"mine navigation preserves JobUtil anti-stuck abort instead of recreating the waypoint"); checks++;
			Mining.deferMineRouteAfterStall(stalledContext);
			check(stalledState.miningUntil == stuckAt && stalledState.cooldownUntil == stuckAt + 40
				&& Mining.taggedMine(worker, level) != null,
				"stalled underground miner resumes through its saved return route after a short cooldown"); checks++;

			// Preserve a distinctive block inside the passage; closing the entrance must not erase it.
			// Closure waits for every living entity to leave the passage, so move this fixture actor outside first.
			worker.setPos(entrance.getX() - 4.5, entrance.getY(), entrance.getZ() + 0.5);
			BlockPos shaft = entrance.relative(Direction.EAST, 2).below(2); // buried tunnel cell: the surface walkway above the cap must stay clear
			level.setBlock(shaft, Blocks.COBBLESTONE.defaultBlockState(), 2);
			BlockPos hiddenOpenRoof = entrance.relative(Direction.EAST, 5).below(5).relative(portalSide).above(3);
			BlockPos roofOccluder = hiddenOpenRoof.above();
			level.setBlock(roofOccluder, Blocks.STONE.defaultBlockState(), 3);
			check(level.getBlockState(hiddenOpenRoof).isAir() && hasDirectSkyOccluder(level, hiddenOpenRoof),
				"fixture includes an open shaft-roof cell directly covered by stone"); checks++;
			Ctx context = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			publicStock.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 17));
			Mining.barricade(level, ref, mine, true);
			check(level.getBlockState(entrance).isAir() && level.getBlockState(entrance.relative(portalSide).above(2)).isAir()
				&& level.getBlockState(hiddenOpenRoof).isAir()
				&& publicStock.getItem(0).getCount() == 17 && publicStock.getItem(1).getCount() == 1,
				"short closure stock leaves portal and every roof opening unchanged without partial payment"); checks++;
			level.setBlock(hiddenOpenRoof, Blocks.WATER.defaultBlockState(), 3);
			Mining.barricade(level, ref, mine, true);
			check(level.getBlockState(hiddenOpenRoof).is(Blocks.WATER) && level.getBlockState(entrance).isAir()
				&& publicStock.getItem(0).getCount() == 17 && publicStock.getItem(1).getCount() == 1,
				"closure leaves a fluid-filled roof cell untouched and does not partially pay"); checks++;
			level.setBlock(hiddenOpenRoof, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(hiddenOpenRoof, Blocks.CHEST.defaultBlockState(), 3);
			BlockEntity roofChest = level.getBlockEntity(hiddenOpenRoof);
			if (!(roofChest instanceof net.minecraft.world.Container roofChestInventory)) {
				throw new IllegalStateException("Mine lifecycle fixture could not create its protected roof chest");
			}
			roofChestInventory.setItem(0, new ItemStack(Items.EMERALD, 2));
			Mining.barricade(level, ref, mine, true);
			check(level.getBlockState(hiddenOpenRoof).is(Blocks.CHEST) && level.getBlockEntity(hiddenOpenRoof) == roofChest
				&& roofChestInventory.getItem(0).getCount() == 2 && level.getBlockState(entrance).isAir()
				&& publicStock.getItem(0).getCount() == 17 && publicStock.getItem(1).getCount() == 1,
				"closure leaves a block entity and its contents untouched without partial payment"); checks++;
			level.setBlock(hiddenOpenRoof, Blocks.AIR.defaultBlockState(), 3);
			// Prove POI protection independently of the earlier deliberate material shortage.
			publicStock.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 64));
			var poiManager = level.getPoiManager();
			protectedPoiPos = entrance.relative(Direction.EAST).relative(portalSide, -1).below(); // cap sits one block under the first surface air cell
			if (!Mining.mineSurfaceSealPlan(level, mine).coverCells().contains(protectedPoiPos)
				|| !level.getBlockState(protectedPoiPos).isAir()) {
				throw new IllegalStateException("Mine lifecycle fixture has no open surface-cap cell for POI protection");
			}
			if (poiManager.getInRange(holder -> true, protectedPoiPos, 0,
				net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isEmpty()) {
				poiManager.add(protectedPoiPos, net.minecraft.core.registries.BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(
					net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME));
				protectedPoiAdded = true;
			}
			Mining.barricade(level, ref, mine, true);
			check(level.getBlockState(entrance).isAir() && level.getBlockState(protectedPoiPos).isAir()
				&& poiManager.getInRange(holder -> true, protectedPoiPos, 0,
					net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent()
				&& publicStock.getItem(0).getCount() == 64 && publicStock.getItem(1).getCount() == 1,
				"a POI on the deterministic surface-cap footprint prevents closure even with enough stock"
					+ " [portal=" + level.getBlockState(entrance) + ", cap=" + level.getBlockState(protectedPoiPos)
					+ ", poi=" + poiManager.getInRange(holder -> true, protectedPoiPos, 0,
						net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent()
					+ ", inserted=" + protectedPoiAdded + ", planks=" + publicStock.getItem(0)
					+ ", sign=" + publicStock.getItem(1) + "]"); checks++;
			if (protectedPoiAdded) {
				poiManager.remove(protectedPoiPos);
				protectedPoiAdded = false;
				protectedPoiPos = null;
			}
			BlockPos partialCap = entrance.relative(Direction.EAST).relative(portalSide, -1).below(); // surface cap cell
			BlockState topSlab = Blocks.OAK_SLAB.defaultBlockState()
				.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
					net.minecraft.world.level.block.state.properties.SlabType.TOP);
			level.setBlock(partialCap, topSlab, 3);
			publicStock.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 64));
			Mining.barricade(level, ref, mine, true);
			check(level.getBlockState(partialCap).equals(topSlab) && level.getBlockState(entrance).isAir()
				&& publicStock.getItem(0).getCount() == 64 && publicStock.getItem(1).getCount() == 1,
				"a partial surface slab is preserved and prevents closure without partial payment"); checks++;

			Map<BlockPos, BlockState> completionBefore = new HashMap<>();
			for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
				BlockPos portal = entrance.relative(portalSide, lane).above(y);
				completionBefore.put(portal, level.getBlockState(portal));
			}
			Mining.MineSurfaceSealPlan completionPlan = Mining.mineSurfaceSealPlan(level, mine);
			for (BlockPos pos : completionPlan.coverCells()) completionBefore.put(pos, level.getBlockState(pos));
			for (BlockPos pos : completionPlan.roofCells()) completionBefore.put(pos, level.getBlockState(pos));
			for (BlockPos pos : completionBefore.keySet()) level.setBlock(pos, Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
			boolean fullSealCompletes = Mining.mineSealComplete(level, mine);
			level.setBlock(partialCap, topSlab, 3);
			boolean slabRejectedByCompletion = level.getBlockState(partialCap).isFaceSturdy(level, partialCap, Direction.UP)
				&& !level.getBlockState(partialCap).isCollisionShapeFullBlock(level, partialCap)
				&& !Mining.mineSealComplete(level, mine);
			for (Map.Entry<BlockPos, BlockState> entry : completionBefore.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 3);
			}
			check(fullSealCompletes && slabRejectedByCompletion,
				"mine completion requires full collision: a top slab cannot count as a closed cap"); checks++;
			level.setBlock(partialCap, Blocks.AIR.defaultBlockState(), 3);
			publicStock.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 24)); // the complete roof-and-cap seal of this fixture costs 24 planks
			worker.setPos(entrance.getX() + 2.5, entrance.getY() - 2, entrance.getZ() + 1.5);
			invokeFinish(context, ref, mine, "Die Mine ist ausgebeutet.");
			check(mine.done && data.mine.isEmpty() && data.extra.oldMines.size() == 1
				&& data.extra.oldMines.getFirst() == mine && level.getBlockState(entrance).isAir()
				&& publicStock.getItem(0).getCount() == 24 && publicStock.getItem(1).getCount() == 1,
				"finishMine archives an occupied shaft but defers closure and payment until its passage clears"); checks++;
			worker.setPos(entrance.getX() - 4.5, entrance.getY(), entrance.getZ() + 0.5);
			BlockPos legacyGate = entrance.relative(portalSide, -1).above(1);
			BlockState closedGate = Blocks.SPRUCE_FENCE_GATE.defaultBlockState()
				.setValue(FenceGateBlock.FACING, Direction.EAST)
				.setValue(FenceGateBlock.OPEN, false);
			level.setBlock(legacyGate, closedGate, 2);
			BlockPos legacyDoorLower = entrance.relative(portalSide, 1).above(1);
			BlockPos legacyDoorUpper = legacyDoorLower.above();
			BlockState lowerDoor = Blocks.SPRUCE_DOOR.defaultBlockState()
				.setValue(DoorBlock.FACING, Direction.EAST)
				.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
				.setValue(DoorBlock.OPEN, false);
			level.setBlock(legacyDoorLower, lowerDoor, 2);
			level.setBlock(legacyDoorUpper, lowerDoor.setValue(DoorBlock.HALF,
				net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
			BlockPos framePost = entrance.relative(portalSide, 2);
			for (int y = 0; y < 3; y++) level.setBlock(framePost.above(y), Blocks.SPRUCE_LOG.defaultBlockState(), 2);
			check(level.getBlockState(legacyGate).equals(closedGate)
				&& level.getBlockState(legacyDoorLower).getBlock() instanceof DoorBlock
				&& level.getBlockState(legacyDoorUpper).getBlock() instanceof DoorBlock,
				"legacy closed gate and double door occupy the portal before the deferred mine closure"); checks++;
			Mining.tickPendingMineSeals(level, level.getGameTime() + 5_000L);
			boolean legacyAccessReplaced = level.getBlockState(legacyGate).is(Blocks.SPRUCE_PLANKS)
				&& level.getBlockState(legacyDoorLower).is(Blocks.SPRUCE_PLANKS)
				&& level.getBlockState(legacyDoorUpper).is(Blocks.SPRUCE_PLANKS);
			boolean entranceFrameIntact = true;
			for (int y = 0; y < 3; y++) entranceFrameIntact &= level.getBlockState(framePost.above(y)).is(Blocks.SPRUCE_LOG);
			check(portalCollisionsBlock(level, entrance, portalSide)
				&& savedRoofCollisionsBlock(level, mine)
				&& surfaceSealFull(level, mine) && surfaceWalkwayClear(level, mine)
				&& level.getBlockState(retainedWalkway).is(Blocks.OAK_PLANKS)
				&& legacyAccessReplaced && entranceFrameIntact
				&& Mining.mineSealComplete(level, mine),
				"the deferred finishMine closure replaces a legacy gate and door with full paid blocks across the portal, seals the terrain-adaptive roof and surface cap, and preserves the interior walkway and entrance frame"
					+ " [portal=" + portalCollisionsBlock(level, entrance, portalSide) + ", roof=" + savedRoofCollisionsBlock(level, mine)
					+ ", sealFull=" + surfaceSealFull(level, mine) + ", walkwayClear=" + surfaceWalkwayClear(level, mine)
					+ ", retainedWalkway=" + level.getBlockState(retainedWalkway).is(Blocks.OAK_PLANKS)
					+ ", legacyReplaced=" + legacyAccessReplaced + ", frame=" + entranceFrameIntact
					+ ", walkwayFailure=" + walkwayFailure(level, mine) + ", complete=" + Mining.mineSealComplete(level, mine) + ", planksLeft=" + publicStock.getItem(0).getCount()
					+ ", signsLeft=" + publicStock.getItem(1).getCount() + "]"); checks++;
			boolean portalSealed = true;
			for (int lane = -1; lane <= 1; lane++) {
				for (int y = 0; y < 3; y++) {
					BlockPos portal = entrance.relative(portalSide, lane).above(y);
					portalSealed &= level.getBlockState(portal).is(Blocks.SPRUCE_PLANKS)
						&& level.getBlockState(portal).isCollisionShapeFullBlock(level, portal);
				}
			}
			check(portalSealed && level.getBlockState(shaft).is(Blocks.COBBLESTONE),
				"all nine portal cells are sealed while shaft contents remain"); checks++;
			boolean roofSealed = true;
			for (int section = 0; section < mine.index; section++) {
				BlockPos base = entrance.relative(Direction.EAST, section).below(section);
				for (int lane = -1; lane <= 1; lane++) {
					BlockPos roof = base.relative(portalSide, lane).above(3);
					if (roof.getY() >= entrance.getY()) continue; // from the surface level up the cap (one block below the first air cell) closes the shaft
					roofSealed &= level.getBlockState(roof).isCollisionShapeFullBlock(level, roof);
				}
			}
			check(roofSealed && level.getBlockState(roofOccluder).is(Blocks.STONE),
				"the paid lid seals underground roof openings, including an occluded one, without changing the shaft"); checks++;
			check(publicStock.getItem(0).isEmpty() && publicStock.getItem(1).isEmpty(),
				"closure consumes exactly twenty-four public spruce planks and one public wall sign"); checks++;
			check(level.getBlockEntity(entrance.above().relative(Direction.WEST)) instanceof SignBlockEntity sign
				&& signText(sign).contains("[VERSCHLOSSEN - ERSCHÖPFT]"), "closure sign visibly marks exhausted mine"); checks++;

			invokeFinish(context, ref, mine, "Die Mine ist ausgebeutet.");
			check(data.mine.isEmpty() && data.extra.oldMines.size() == 1, "repeated completion does not duplicate the archived entrance"); checks++;

			CompoundTag savedBell = bell.saveWithFullMetadata(level.registryAccess());
			BlockEntity restoredEntity = BlockEntity.loadStatic(bellPos, level.getBlockState(bellPos), savedBell.copy(), level.registryAccess());
			if (!(restoredEntity instanceof BellBlockEntity restoredBell)) {
				throw new IllegalStateException("Mine lifecycle bell attachment did not survive serialization");
			}
			VillageData restoredData = restoredBell.getAttachedOrElse(VillagerPlus.VILLAGE, new VillageData());
			VillageRef restoredRef = new VillageRef(bellPos, restoredData, restoredBell);
			byte[][] marks = VillageMap.grid(level, restoredRef);
			int[] origin = VillageMap.gridOrigin(level, restoredRef);
			int cellSize = VillageMap.gridCellSize(level, restoredRef);
			int col = (entrance.getX() - origin[0]) / cellSize;
			int row = (entrance.getZ() - origin[1]) / cellSize;
			check(restoredData.mine.isEmpty() && restoredData.extra.oldMines.size() == 1
				&& restoredData.extra.oldMines.getFirst().done
				&& col >= 0 && col < VillageMap.GRID && row >= 0 && row < VillageMap.GRID
				&& marks[1][row * VillageMap.GRID + col] == 8,
				"persisted map data retains the permanently closed old-mine marker after bell attachment round-trip"); checks++;
			int planksAfterRoundTrip = publicStock.getItem(0).getCount();
			Mining.tidyOldMines(level, restoredRef);
			check(restoredData.extra.oldMines.getFirst().done && surfaceSealFull(level, restoredData.extra.oldMines.getFirst())
				&& surfaceWalkwayClear(level, restoredData.extra.oldMines.getFirst())
				&& publicStock.getItem(0).getCount() == planksAfterRoundTrip,
				"old-mine cleanup preserves the serialized cap without reopening or charging again"); checks++;
			String rendered = VillageMap.render(level, restoredRef).stream().map(component -> component.getString()).reduce("", String::concat);
			check(rendered.contains("▽"), "rendered village map displays old-mine glyph"); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			if (protectedPoiAdded && protectedPoiPos != null) level.getPoiManager().remove(protectedPoiPos);
			for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) {
				BlockPos pos = entry.getKey();
				Cell cell = entry.getValue();
				level.setBlock(pos, cell.state(), 2);
				if (cell.blockEntity() == null) {
					if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
				} else {
					BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
					if (restored != null) level.setBlockEntity(restored);
				}
			}
		}
	}

	private static void invokeFinish(Ctx context, VillageRef ref, VillageData.Mine mine, String message) throws ReflectiveOperationException {
		Method finish = Mining.class.getDeclaredMethod("finishMine", Ctx.class, VillageRef.class, VillageData.Mine.class, String.class);
		finish.setAccessible(true);
		finish.invoke(null, context, ref, mine, message);
	}

	private static String signText(SignBlockEntity sign) {
		StringBuilder text = new StringBuilder();
		for (int line = 0; line < 4; line++) text.append(sign.getFrontText().getMessage(line, false).getString()).append(' ');
		return text.toString();
	}

	private static boolean portalCollisionsBlock(ServerLevel level, BlockPos entrance, Direction side) {
		for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
			BlockPos pos = entrance.relative(side, lane).above(y);
			if (!level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) return false;
		}
		return true;
	}

	private static boolean savedRoofCollisionsBlock(ServerLevel level, VillageData.Mine mine) {
		Mining.MineSurfaceSealPlan plan = Mining.mineSurfaceSealPlan(level, mine);
		if (!plan.loaded()) return false;
		for (BlockPos pos : plan.roofCells()) {
			if (!level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) return false;
		}
		return true;
	}

	private static boolean surfaceSealFull(ServerLevel level, VillageData.Mine mine) {
		Mining.MineSurfaceSealPlan plan = Mining.mineSurfaceSealPlan(level, mine);
		if (!plan.loaded()) return false;
		for (BlockPos pos : plan.coverCells()) {
			if (!level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) return false;
		}
		return true;
	}

	/** Dev diagnostic: first surface walkway cell that is not a sturdy floor with two air cells above it. */
	private static String walkwayFailure(ServerLevel level, VillageData.Mine mine) {
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		for (int section = 1; section < mine.index; section++) {
			BlockPos base = mine.entrance.relative(Direction.from2DDataValue(mine.dir), section).below(section);
			if (base.getY() + 3 < mine.entrance.getY()) break;
			for (int lane = -1; lane <= 1; lane++) {
				BlockPos column = base.relative(side, lane);
				BlockPos floor = new BlockPos(column.getX(), mine.entrance.getY() - 1, column.getZ());
				if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
					|| !level.getBlockState(floor.above()).isAir() || !level.getBlockState(floor.above(2)).isAir()) {
					return "s" + section + "/l" + lane + " floor=" + level.getBlockState(floor).getBlock() + " +1=" + level.getBlockState(floor.above()).getBlock()
						+ " +2=" + level.getBlockState(floor.above(2)).getBlock();
				}
			}
		}
		return "none";
	}

	private static boolean surfaceWalkwayClear(ServerLevel level, VillageData.Mine mine) {
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		for (int section = 0; section < mine.index; section++) {
			BlockPos base = mine.entrance.relative(Direction.from2DDataValue(mine.dir), section).below(section);
			if (base.getY() + 3 < mine.entrance.getY()) break;
			if (section == 0) continue; // the entrance gate itself occupies the first cap section
			for (int lane = -1; lane <= 1; lane++) {
				BlockPos column = base.relative(side, lane);
				// The cap block sits one level under the first surface air cell, so the walking floor is that cap.
				BlockPos floor = new BlockPos(column.getX(), mine.entrance.getY() - 1, column.getZ());
				if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
					|| !level.getBlockState(floor.above()).isAir()
					|| !level.getBlockState(floor.above(2)).isAir()) return false;
			}
		}
		return true;
	}

	/** Clear and preserve the fixture's vertical sightline; this avoids relying on cached skylight in test chunks. */
	private static void clearSkyColumn(ServerLevel level, Map<BlockPos, Cell> before, BlockPos roof) {
		for (int y = roof.getY(); y < level.getMaxY(); y++) {
			BlockPos pos = new BlockPos(roof.getX(), y, roof.getZ());
			BlockEntity blockEntity = level.getBlockEntity(pos);
			BlockState state = level.getBlockState(pos);
			if (blockEntity != null || state.hasBlockEntity()) {
				throw new IllegalStateException("Mine lifecycle sky fixture overlaps a protected block entity at " + pos.toShortString());
			}
			before.putIfAbsent(pos, new Cell(state, null));
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		}
	}

	/** Seed stable terrain probes outside the excavated three-wide shaft, then restore the fixture snapshot later. */
	private static void setMineTerrainSurface(ServerLevel level, Map<BlockPos, Cell> before,
			VillageData.Mine mine, int surfaceY) {
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		for (int section = 0; section < Mining.openedMineSections(mine); section++) {
			BlockPos route = mine.entrance.relative(Direction.from2DDataValue(mine.dir), section);
			BlockPos base = new BlockPos(route.getX(), Math.max(mine.targetY, mine.entrance.getY() - section), route.getZ());
			for (int probeLane : new int[]{-3, 3}) {
				BlockPos sample = base.relative(side, probeLane);
				if (!level.hasChunkAt(sample)) throw new IllegalStateException("Mine terrain fixture sample is not loaded at " + sample.toShortString());
				clearSkyColumn(level, before, new BlockPos(sample.getX(), mine.entrance.getY() - 4, sample.getZ()));
				BlockPos ground = new BlockPos(sample.getX(), surfaceY - 1, sample.getZ());
				before.putIfAbsent(ground, new Cell(level.getBlockState(ground), null));
				level.setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
			}
		}
	}

	/** Uses actual opaque block geometry rather than cached skylight data in a synthetic fixture. */
	private static boolean hasDirectSkyOccluder(ServerLevel level, BlockPos openCell) {
		for (int y = openCell.getY() + 1; y < level.getMaxY(); y++) {
			BlockPos above = new BlockPos(openCell.getX(), y, openCell.getZ());
			if (level.getBlockState(above).isCollisionShapeFullBlock(level, above)) return true;
		}
		return false;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("MINE LIFECYCLE CHECK FAILED: " + message);
	}
}
