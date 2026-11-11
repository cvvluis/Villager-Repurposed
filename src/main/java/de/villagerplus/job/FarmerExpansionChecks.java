package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.FarmPlanSizing;
import de.villagerplus.build.Step;
import de.villagerplus.village.Balance;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Exercises a real FarmerJob.secondary call through material reservation and field construction. */
public final class FarmerExpansionChecks {
	private FarmerExpansionChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		SimpleContainer granarySeeds = new SimpleContainer(2);
		granarySeeds.setItem(0, new ItemStack(Items.WHEAT_SEEDS, Config.FARM_MIN_SEEDS));
		int granaryWheatSeeds = FarmerJob.publicFarmSeedCount(List.of(granarySeeds));
		check(granaryWheatSeeds == Config.FARM_MIN_SEEDS
			&& !FarmerJob.shouldWaitForVisibleStarterFarm(0, false, granaryWheatSeeds),
			"the first-field gate releases at the eight seeds delivered to public granary stock");
		check(FarmerJob.shouldWaitForVisibleStarterFarm(0, false, 7),
			"the first visible field waits until its compact eight-seed payment is public");
		check(!FarmerJob.shouldWaitForVisibleStarterFarm(0, false, 8)
			&& !FarmerJob.shouldWaitForVisibleStarterFarm(0, false, 23)
			&& !FarmerJob.shouldWaitForVisibleStarterFarm(0, false, 24),
			"eight public wheat seeds release the compact first field without a 24-seed startup gate");
		check(!FarmerJob.shouldWaitForVisibleStarterFarm(0, true, 0)
			&& !FarmerJob.shouldWaitForVisibleStarterFarm(1, false, 0),
			"saved farmer work resumes below the seed gate and completed farms are unaffected");
		check(FarmerJob.shouldTryCompactStarterPlan(0, false, false, 8)
			&& !FarmerJob.shouldTryCompactStarterPlan(0, false, false, 7)
			&& !FarmerJob.shouldTryCompactStarterPlan(0, true, false, 24)
			&& !FarmerJob.shouldTryCompactStarterPlan(0, false, true, 24)
			&& !FarmerJob.shouldTryCompactStarterPlan(1, false, false, 24),
			"a fresh village starts the real paid wheat field at eight public seeds even when its food reserve is covered, without duplicating saved work");
		String fundedStarter = FarmerJob.starterFarmReadinessMarker(0, false, false, 8, true, 2, 2, "wheat=8");
		check(fundedStarter != null
			&& FarmerJob.starterFarmReadinessMarker(0, false, false, 8, true, 2, 2, "wheat=8") != null
			&& FarmerJob.starterFarmReadinessMarker(0, false, false, 7, true, 2, 2, "wheat=7") == null
			&& FarmerJob.starterFarmReadinessMarker(0, false, false, 8, false, 2, 2, "wheat=8") == null,
			"a starter retry is armed at the compact eight-seed threshold only when usable public storage exists");
		check(!FarmerJob.shouldWakeStarterFarmRetry(fundedStarter, fundedStarter),
			"an unchanged blocked starter site keeps Builder's terrain retry throttle instead of rescanning every farmer turn");
		check(FarmerJob.shouldWakeStarterFarmRetry(fundedStarter,
			FarmerJob.starterFarmReadinessMarker(0, false, false, 8, true, 1, 2, "wheat=8"))
			&& FarmerJob.shouldWakeStarterFarmRetry(fundedStarter,
				FarmerJob.starterFarmReadinessMarker(0, false, false, 8, true, 2, 3, "wheat=8"))
			&& FarmerJob.shouldWakeStarterFarmRetry(fundedStarter,
				FarmerJob.starterFarmReadinessMarker(0, false, false, 8, true, 2, 2, "wheat=8,fences=15,gate=1")),
			"a freed construction slot, new resident or funded fence kit reopens the first-field attempt promptly");
		SimpleContainer retryStock = new SimpleContainer(8);
		retryStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		String seedOnlyRetry = FarmerJob.starterFarmStockSignature(retryStock);
		retryStock.setItem(1, new ItemStack(Items.BREAD, 32));
		boolean foodDoesNotChangeRetry = seedOnlyRetry.equals(FarmerJob.starterFarmStockSignature(retryStock));
		retryStock.setItem(2, new ItemStack(Items.OAK_FENCE, 15));
		retryStock.setItem(3, new ItemStack(Items.OAK_FENCE_GATE, 1));
		retryStock.setItem(4, new ItemStack(Items.WATER_BUCKET, 1));
		String fenceAndWaterRetry = FarmerJob.starterFarmStockSignature(retryStock);
		retryStock.setItem(5, new ItemStack(Items.OAK_PLANKS, 22));
		retryStock.setItem(6, new ItemStack(Items.STICK, 14));
		String recipeInputsRetry = FarmerJob.starterFarmStockSignature(retryStock);
		retryStock.setItem(7, new ItemStack(Items.BUCKET, 1));
		check(foodDoesNotChangeRetry && !seedOnlyRetry.equals(fenceAndWaterRetry)
			&& !seedOnlyRetry.equals(recipeInputsRetry)
			&& !recipeInputsRetry.equals(FarmerJob.starterFarmStockSignature(retryStock)),
			"public fence/gate/water stock and planks/sticks/empty-bucket ingredients wake a blocked starter-field retry, while food does not");
		check(FarmerJob.starterFarmReadinessMarker(0, true, false, 8, true, 1, 2, "wheat=8") == null
			&& FarmerJob.starterFarmReadinessMarker(0, false, true, 8, true, 1, 2, "wheat=8") == null,
			"saved Builder and farmer cursors remain the sole owner of their field and cannot be duplicated by a retry");
		check(Blueprints.PEN.equals(Builder.nextFarmerPlanType(2, 1, 1, true, false, false, true))
			&& Builder.nextFarmerPlanType(2, 0, 0, true, false, false, false) == null
			&& Builder.nextFarmerPlanType(2, 1, 1, true, true, false, true) == null,
			"the paid animal pen follows a completed starter field, while storage and active farm cursors keep their priority");
		check(FarmerJob.shouldPrioritizeInitialPenBeforeNewExpansion(false, true, false)
			&& !FarmerJob.shouldPrioritizeInitialPenBeforeNewExpansion(true, true, false)
			&& !FarmerJob.shouldPrioritizeInitialPenBeforeNewExpansion(false, true, true),
			"a real due pen is attempted first, while persisted field and expansion cursors keep their saved ownership");
		check(FarmerJob.shouldStartInitialPen(0, true, 2, true, true, false)
			&& !FarmerJob.shouldStartInitialPen(0, true, 2, false, true, false)
			&& FarmerJob.shouldStartInitialPen(1, true, 2, false, true, false)
			&& !FarmerJob.shouldStartInitialPen(1, true, 2, false, false, false)
			&& !FarmerJob.shouldStartInitialPen(1, true, 2, true, false, false)
			&& FarmerJob.shouldStartInitialPen(0, true, 0, false, false, true),
			"a real herd may unlock the pen before the first field, a completed field unlocks it before animals arrive, public storage is still required, and saved work resumes");
		String readyPenWithoutHerd = FarmerJob.initialPenReadinessMarker(true, false, false,
			1, 2, false, true, 0, "oak_planks=32");
		check(readyPenWithoutHerd != null
			&& FarmerJob.shouldWakeInitialPenRetry(null, readyPenWithoutHerd)
			&& !FarmerJob.shouldWakeInitialPenRetry(readyPenWithoutHerd, readyPenWithoutHerd)
			&& FarmerJob.initialPenReadinessMarker(true, false, false, 0, 2, false, true, 0, "oak_planks=32") == null
			&& FarmerJob.initialPenReadinessMarker(true, false, false, 1, 2, false, false, 0, "oak_planks=32") == null,
			"a completed field wakes the first-pen retry without nearby animals, but no field or no public storage stays blocked");
		checkVisibleStarterFarmBlueprint();
		checkPaidStarterFootprint();
		BlockPos site = ref.bell().offset(50, 0, 50);
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		BlockPos[] privateFoodChestPos = {null};
		BlockPos[] publicStorageFixturePos = {null};
		List<Villager> fixtures = new ArrayList<>();
		var oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		var oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		int oldFarms = ref.data().farms;
		var oldPen = ref.data().extra.pen;
		long oldNextFarm = ref.data().extra.nextFarm;
		List<StorageSnapshot> oldStorage = snapshotStorage(Balance.storage(level, ref));
		Map<Villager, String> oldExpansionPlans = new LinkedHashMap<>();
		try {
			for (Villager resident : de.villagerplus.village.Villages.residents(level, ref, 96, v -> true)) {
				String plan = FarmerJob.expansionStateTag(resident);
				if (plan != null) {
					oldExpansionPlans.put(resident, plan);
					resident.removeTag(plan);
				}
			}
			for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 3; dz++) {
				BlockPos cell = site.offset(dx, 0, dz);
				level.getChunkAt(cell);
				for (BlockPos p : new BlockPos[]{cell.below(), cell, cell.above()}) before.putIfAbsent(p, level.getBlockState(p));
				level.setBlock(cell.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
				level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
			}
			preparePublicWaterPool(level, site.offset(-8, 0, -8), before);
			publicStorageFixturePos[0] = site.east(8).immutable();
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				BlockPos column = publicStorageFixturePos[0].offset(dx, 0, dz);
				level.getChunkAt(column);
				for (BlockPos cell : new BlockPos[]{column.below(), column, column.above(), column.above(2), column.above(3)}) {
					before.putIfAbsent(cell, level.getBlockState(cell));
				}
				level.setBlock(column.below(), Blocks.STONE.defaultBlockState(), 3);
				for (int y = 0; y <= 3; y++) level.setBlock(column.above(y), Blocks.AIR.defaultBlockState(), 3);
			}
			before.putIfAbsent(publicStorageFixturePos[0], level.getBlockState(publicStorageFixturePos[0]));
			level.setBlock(publicStorageFixturePos[0], Blocks.CHEST.defaultBlockState(), 3);
			var fixtureSpecials = new java.util.HashMap<>(de.villagerplus.village.Specials.all(ref));
			fixtureSpecials.put(de.villagerplus.build.Blueprints.GRANARY, publicStorageFixturePos[0]);
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(fixtureSpecials));
			Container box = (Container)level.getBlockEntity(publicStorageFixturePos[0]);
			List<Container> publicStores = Balance.storage(level, ref);
			if (box == null || !publicStores.contains(box))
				throw new IllegalStateException("FARM EXPANSION CHECK FAILED: isolated public chest was not registered as granary storage");
			for (Container store : publicStores) store.clearContent();
			put(box, 0, Items.WHEAT_SEEDS, 8);
			put(box, 1, Items.OAK_PLANKS, 22);
			put(box, 2, Items.STICK, 14);
			put(box, 3, Items.BUCKET, 1);
			check(JobUtil.count(box, stack -> stack.is(Items.WHEAT_SEEDS)) == 8
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS)) == 22
				&& JobUtil.count(box, stack -> stack.is(Items.STICK)) == 14
				&& FarmerJob.safeStarterClusterSite(level, site)
				&& level.getBlockState(site.offset(-8, 0, -8)).is(Blocks.WATER),
				"eight public seeds, recipe ingredients, one empty bucket and nearby natural water fund the starter plot");
			BlockPos starterFlower = site.offset(-2, 0, -2);
			BlockPos starterGrass = site.offset(-1, 0, -1);
			level.setBlock(starterFlower, Blocks.POPPY.defaultBlockState(), 3);
			level.setBlock(starterGrass, Blocks.SHORT_GRASS.defaultBlockState(), 3);
			check(FarmerJob.safeStarterClusterSite(level, site),
				"naturally growing flowers and grass are clearable plot vegetation, not a reason to reject the whole field site");
			ref.data().farms = 0;
			ref.data().extra.pen = Optional.of(ref.bell().south(24)); // Prevent the legacy auto-pen branch during this field check.
			ref.data().extra.nextFarm = 0;
			ZonePolicy.Zone zone = new ZonePolicy.Zone("live-farm-check", "farm", site.getX() - 6, site.getZ() - 6,
				site.getX() + 6, site.getZ() + 6);
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(zone));
			check(FarmerJob.clusterAllowedByZones(ref, site)
				&& !FarmerJob.clusterAllowedByZones(ref, site.east(4))
				&& FarmerJob.findClusterSite(level, ref, site) != null,
				"the 5-by-5 starter footprint is selected and committed only when its full bounds fit the farm zone");
			for (BlockPos candidate : BlockPos.betweenClosed(publicStorageFixturePos[0].offset(-8, -1, -8), publicStorageFixturePos[0].offset(8, 6, 8))) {
				if (level.hasChunkAt(candidate) && level.getBlockState(candidate).isAir() && level.getBlockEntity(candidate) == null
					&& !adjacentChest(level, candidate)) {
					privateFoodChestPos[0] = candidate.immutable();
					break;
				}
			}
			if (privateFoodChestPos[0] == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: no safe private-chest fixture cell");
			level.setBlock(privateFoodChestPos[0], Blocks.CHEST.defaultBlockState(), 3);
			Container privateFoodChest = (Container)level.getBlockEntity(privateFoodChestPos[0]);
			privateFoodChest.setItem(0, new ItemStack(Items.BREAD, 64));
			level.getBlockEntity(privateFoodChestPos[0]).setAttached(VillagerPlus.OWNER, java.util.UUID.randomUUID());
			privateFoodChest.setChanged();
			ref.dirty();

			for (int i = 0; i < 4; i++) {
				Villager villager = EntityTypes.VILLAGER.spawn(level, site.offset(i == 0 ? 0 : i, 0, 0), EntitySpawnReason.COMMAND);
				if (villager == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: could not spawn villager fixture");
				fixtures.add(villager);
				villager.addTag("vp090_fixture"); villager.setInvulnerable(true); villager.setNoAi(true);
				villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
				villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), site.offset(-12, 0, 0)));
			}
			Villager farmer = fixtures.getFirst();
			farmer.teleportTo(site.getX() + 0.5, site.getY() + 1, site.getZ() + 0.5);
			farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), site));
			JobState state = JobManager.state(farmer);
			state.idleUntil[1] = 0;
			VillagerMemory memory = farmer.getAttachedOrCreate(VillagerPlus.MEMORY);
			long actionTime = level.getGameTime() + 1;
			Ctx ctx = new Ctx(level, farmer, state, memory, ref, actionTime);
			check(FarmerJob.belowFoodTarget(ctx) && JobUtil.count(privateFoodChest, stack -> stack.is(Items.BREAD)) == 64,
				"a private food chest is excluded from the village reserve and remains untouched");
			BlockPos actual = new BlockPos(site.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site.getX(), site.getZ()), site.getZ());
			int reserveAtBoundary = FarmerJob.foodTarget(de.villagerplus.village.Villages.residents(level, ref, 96, v -> true).size());
			int deficitFood = Math.max(0, reserveAtBoundary - 1);
			int deficitSlot = 4;
			while (deficitFood > 0 && deficitSlot < box.getContainerSize()) {
				int stackSize = Math.min(64, deficitFood);
				put(box, deficitSlot++, Items.BREAD, stackSize);
				deficitFood -= stackSize;
			}
			if (deficitFood > 0) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: not enough storage for food-deficit fixture");
			check(FarmerJob.belowFoodTarget(ctx),
				"food one item below reserve stays deficient when public storage is also registered as the granary");
			for (int slot = 4; slot < box.getContainerSize(); slot++) box.setItem(slot, ItemStack.EMPTY);
			int boundaryFood = reserveAtBoundary;
			int boundarySlot = 4;
			while (boundaryFood > 0 && boundarySlot < box.getContainerSize()) {
				int stackSize = Math.min(64, boundaryFood);
				put(box, boundarySlot++, Items.BREAD, stackSize);
				boundaryFood -= stackSize;
			}
			if (boundaryFood > 0) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: not enough storage for reserve-boundary fixture");
			int stagedFood = JobUtil.count(box, stack -> stack.is(Items.BREAD));
			if (stagedFood != reserveAtBoundary) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: granary write was rejected or altered; expected "
				+ reserveAtBoundary + " bread, found " + stagedFood + ", storage=" + Balance.stock(Balance.storage(level, ref), Balance.Kind.FOOD));
			check(!FarmerJob.belowFoodTarget(ctx),
				"five-unit boundary fixture is not below the production reserve calculation");
			boolean worked;
			int procurementVisits = 0;
			int procurementVisitLimit = 32;
			int fencesBeforeProcurement = countPublicStock(publicStores, Items.OAK_FENCE);
			int gatesBeforeProcurement = countPublicStock(publicStores, Items.OAK_FENCE_GATE);
			int previousFences = fencesBeforeProcurement;
			int previousGates = gatesBeforeProcurement;
			int initialPlanks = countPublicStock(publicStores, Items.OAK_PLANKS);
			int initialSticks = countPublicStock(publicStores, Items.STICK);
			while ((countPublicStock(publicStores, Items.OAK_FENCE) < 15
				|| countPublicStock(publicStores, Items.OAK_FENCE_GATE) < 1) && procurementVisits < procurementVisitLimit) {
				worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
				int fencesNow = countPublicStock(publicStores, Items.OAK_FENCE);
				int gatesNow = countPublicStock(publicStores, Items.OAK_FENCE_GATE);
				int planksNow = countPublicStock(publicStores, Items.OAK_PLANKS);
				int sticksNow = countPublicStock(publicStores, Items.STICK);
				int seedsNow = JobUtil.count(box, stack -> stack.is(Items.WHEAT_SEEDS));
				int visit = procurementVisits + 1;
				int cursorNow = FarmerJob.expansionCursorForChecks(farmer);
				check(worked && ref.data().farms == 0 && cursorNow == -1
					&& seedsNow == 8,
					"the farmer uses the procurement visit for real field materials before saving the cursor [visit=" + visit
						+ ", worked=" + worked + ", cursor=" + cursorNow + ", seeds=" + seedsNow
						+ ", oak_fence=" + fencesNow + " (+" + (fencesNow - previousFences) + ")"
						+ ", oak_fence_gate=" + gatesNow + " (+" + (gatesNow - previousGates) + ")"
						+ ", oak_planks=" + planksNow + ", sticks=" + sticksNow + "]");
				procurementVisits = visit;
				previousFences = fencesNow;
				previousGates = gatesNow;
				actionTime = Math.max(actionTime + 120, state.cooldownUntil);
			}
			int procuredFences = countPublicStock(publicStores, Items.OAK_FENCE);
			int procuredGates = countPublicStock(publicStores, Items.OAK_FENCE_GATE);
			int remainingPlanks = countPublicStock(publicStores, Items.OAK_PLANKS);
			int remainingSticks = countPublicStock(publicStores, Items.STICK);
			check(procurementVisits > 0 && procurementVisits < procurementVisitLimit
				&& procuredFences >= 15 && procuredGates >= 1
				&& remainingPlanks == 0 && remainingSticks == 0
				&& initialPlanks == 22 && initialSticks == 14,
				"loaded vanilla recipes pay the full oak fence kit from public stock without assuming recipe batch timing"
					+ " [visits=" + procurementVisits + "/" + procurementVisitLimit + ", fences=" + procuredFences
					+ ", gates=" + procuredGates + ", planksRemaining=" + remainingPlanks
					+ ", sticksRemaining=" + remainingSticks + "]");
			ctx = new Ctx(level, farmer, state, memory, ref, actionTime);
			worked = new FarmerJob().secondary(ctx);
			BlockPos waterSource = FarmerJob.farmWaterSourceForChecks(farmer, level);
			String savedWaterPlan = FarmerJob.farmWaterPlanTagForChecks(farmer);
			check(worked && savedWaterPlan != null && waterSource != null
				&& JobUtil.count(box, stack -> stack.is(Items.BUCKET)) == 1
				&& JobUtil.count(farmer.getInventory(), stack -> stack.is(Items.WATER_BUCKET)) == 0,
				"the farmer saves a safe, reachable water-source route before consuming the public empty bucket");
			TagValueOutput waterSave = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			farmer.saveWithoutId(waterSave);
			Villager waterReload = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (waterReload == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: could not create water-route reload fixture");
			waterReload.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), waterSave.buildResult()));
			check(savedWaterPlan.equals(FarmerJob.farmWaterPlanTagForChecks(waterReload)),
				"the field site and natural-water route survive villager NBT reload");
			waterReload.discard();
			BlockPos waterStand = FarmerJob.farmWaterStandForChecks(level, waterSource);
			if (waterStand == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: planned water source has no safe reachable stand");
			farmer.teleportTo(waterStand.getX() + 0.5, waterStand.getY(), waterStand.getZ() + 0.5);
			actionTime = Math.max(actionTime + 120, state.cooldownUntil);
			worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(worked && FarmerJob.farmWaterPlanTagForChecks(farmer) == null
				&& JobUtil.count(box, stack -> stack.is(Items.BUCKET)) == 0
				&& JobUtil.count(farmer.getInventory(), stack -> stack.is(Items.WATER_BUCKET)) == 1,
				"a real public empty bucket is filled from the selected source and carried to its paid field step");
			// This fixture tests the saved field cursor after water pickup; route travel back to the site is covered separately.
			farmer.teleportTo(actual.getX() + 2.5, actual.getY() + 1.0, actual.getZ() + 0.5);
			actionTime = Math.max(actionTime + 120, state.cooldownUntil);
			worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
			int firstFieldCursor = FarmerJob.expansionCursorForChecks(farmer);
			int fieldBoundaryFood = JobUtil.count(box, stack -> stack.is(Items.BREAD));
			int fieldSeeds = JobUtil.count(box, stack -> stack.is(Items.WHEAT_SEEDS));
			int paidFences = countPublicStock(publicStores, Items.OAK_FENCE);
			int carriedWaterBuckets = JobUtil.count(farmer.getInventory(), stack -> stack.is(Items.WATER_BUCKET));
			check(worked && ref.data().farms == 0 && firstFieldCursor == 0
				&& fieldBoundaryFood == reserveAtBoundary && fieldSeeds == 8 && paidFences == 15
				&& carriedWaterBuckets == 1,
				"the first paid field cursor starts after real public procurement even when food covers the reserve"
					+ " [worked=" + worked + ", farms=" + ref.data().farms + ", cursor=" + firstFieldCursor
					+ ", bread=" + fieldBoundaryFood + "/" + reserveAtBoundary + ", seeds=" + fieldSeeds
					+ ", fences=" + paidFences + ", farmerWaterBuckets=" + carriedWaterBuckets + "]");
			for (int slot = 4; slot < box.getContainerSize(); slot++) box.setItem(slot, ItemStack.EMPTY);
			actionTime += 30;
			ctx = new Ctx(level, farmer, state, memory, ref, actionTime);
			// Stay close enough to the plot-selection point but outside reach of its first corner fence.
			farmer.teleportTo(actual.getX() + 2.5, actual.getY() + 1.0, actual.getZ() + 0.5);
			worked = new FarmerJob().secondary(ctx);
			check(worked && ref.data().farms == 0 && FarmerJob.expansionCursorForChecks(farmer) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.WHEAT_SEEDS)) == 8
				&& countPublicStock(publicStores, Items.OAK_FENCE) == 15,
				"the first saved field cursor waits for reach without charging its real materials early");
			int extraFood = FarmerJob.foodTarget(de.villagerplus.village.Villages.residents(level, ref, 96, v -> true).size()) + 1;
			int foodSlot = 4;
			while (extraFood > 0 && foodSlot < box.getContainerSize()) {
				int stackSize = Math.min(64, extraFood);
				put(box, foodSlot++, Items.BREAD, stackSize);
				extraFood -= stackSize;
			}
			check(extraFood == 0, "the field fixture can stage enough actual bread to clear its reserve threshold");
			actionTime += 30;
			worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(worked && FarmerJob.expansionCursorForChecks(farmer) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.WHEAT_SEEDS)) == 8
				&& countPublicStock(publicStores, Items.OAK_FENCE) == 15,
				"a recovered food reserve does not cancel the missing village's first saved field plan");
			for (int slot = 4; slot < box.getContainerSize(); slot++) box.setItem(slot, ItemStack.EMPTY);
			actionTime += 30;
			worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(worked && FarmerJob.expansionCursorForChecks(farmer) == 0,
			"the same first-field cursor remains resumable after reserve changes");
			BlockPos firstStep = FarmerJob.starterExpansionStepPosForChecks(actual, 0);
			farmer.teleportTo(firstStep.getX() + 0.5, firstStep.getY(), firstStep.getZ() + 0.5);
			actionTime += 30;
			worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(worked && FarmerJob.expansionCursorForChecks(farmer) == 1
				&& level.getBlockState(firstStep).is(Blocks.OAK_FENCE)
				&& JobUtil.count(farmer.getInventory(), stack -> stack.is(Items.POPPY)) == 1
				&& level.getBlockState(starterFlower).is(Blocks.OAK_FENCE)
				&& countPublicStock(publicStores, Items.OAK_FENCE) == 14,
				"the farmer clears a flower for the first paid fence, keeps its real drop, and advances the saved cursor");

			String savedPlan = FarmerJob.expansionStateTag(farmer);
			check(savedPlan != null && savedPlan.startsWith("vp_farm_expansion_v3|"),
				"new starter-field cursors are stored with their own versioned geometry");
			TagValueOutput savedVillager = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			farmer.saveWithoutId(savedVillager);
			Villager restoredFarmer = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (restoredFarmer == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: could not create reload fixture");
			restoredFarmer.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), savedVillager.buildResult()));
			check(savedPlan.equals(FarmerJob.expansionStateTag(restoredFarmer))
				&& FarmerJob.expansionCursorForChecks(restoredFarmer) == 1,
				"the field site, selected seed and construction cursor survive villager NBT reload");
			restoredFarmer.discard();

			removePublicStock(publicStores, Items.OAK_FENCE);
			BlockPos blockedStep = FarmerJob.starterExpansionStepPosForChecks(actual, 1);
			farmer.teleportTo(blockedStep.getX() + 0.5, blockedStep.getY(), blockedStep.getZ() + 0.5);
			actionTime += 30;
			worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(worked && FarmerJob.expansionCursorForChecks(farmer) == 1
				&& level.getBlockState(blockedStep).isAir()
				&& countPublicStock(publicStores, Items.OAK_FENCE) == 0,
				"a material shortage pauses an active field plan without consuming its next block or losing progress");
			put(box, 1, Items.OAK_FENCE, 14);

			for (int cursor = 1; cursor < 25; cursor++) {
				BlockPos nextStep = FarmerJob.starterExpansionStepPosForChecks(actual, cursor);
				farmer.teleportTo(nextStep.getX() + 0.5, nextStep.getY(), nextStep.getZ() + 0.5);
				actionTime += 30;
				worked = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
				boolean completed = FarmerJob.expansionStateTag(farmer) == null && ref.data().farms == 1;
				check(worked && (cursor == 24 ? completed : FarmerJob.expansionCursorForChecks(farmer) == cursor + 1),
					"field construction resumes paid step " + (cursor + 1) + " of 25" + (completed ? " and registers completion" : ""));
				if (cursor == 17) {
					check(level.getBlockState(starterGrass).is(Blocks.WHEAT),
						"the first crop cell clears natural grass and gets planted without losing the saved field cursor");
				}
			}
			check(ref.data().farms == 1 && FarmerJob.expansionStateTag(farmer) == null,
				"the completed field is registered once and clears its persisted construction plan");
			check(JobUtil.count(privateFoodChest, stack -> stack.is(Items.BREAD)) == 64,
				"paid field construction uses only public village stock while private food remains intact");
			check(level.getBlockState(actual.below()).is(Blocks.WATER)
				&& level.getBlockState(actual).isAir(),
				"the field keeps its water source at soil level so it does not replace adjacent crop blocks");
			int planted = 0, fences = 0;
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos crop = actual.offset(dx, 0, dz);
				if (level.getBlockState(crop).is(Blocks.WHEAT) && level.getBlockState(crop.below()).is(Blocks.FARMLAND)) planted++;
			}
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
				BlockState fence = level.getBlockState(actual.offset(dx, 0, dz));
				if (fence.is(Blocks.OAK_FENCE)) fences++;
			}
			check(planted == 8 && fences == 15
				&& level.getBlockState(actual.offset(0, 0, 2)).is(Blocks.OAK_FENCE_GATE)
				&& level.getBlockState(actual.offset(0, 0, 2)).getValue(FenceGateBlock.OPEN),
				"the completed starter plot has eight planted crops, 15 paid fence blocks and one open gate");
			check(JobUtil.count(box, stack -> stack.is(Items.WHEAT_SEEDS)) == 0
				&& countPublicStock(publicStores, Items.OAK_FENCE) == 0
				&& countPublicStock(publicStores, Items.OAK_FENCE_GATE) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.WATER_BUCKET)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.BUCKET)) == 0
				&& JobUtil.count(farmer.getInventory(), stack -> stack.is(Items.BUCKET)) == 1,
				"field construction consumes exact public seeds/fences and returns the paid bucket to its real carrier inventory");
			int rememberedCropSpots = 0;
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				if (memory.workSpots.contains(actual.offset(dx, 0, dz))) rememberedCropSpots++;
			}
			check(rememberedCropSpots == 8,
				"the saved field plan registers every planted crop for the farmer's harvest search");

			// Continue the same feature slice through a real mature crop harvest and communal food delivery.
			BlockPos cropPos = actual.offset(1, 0, 0);
			BlockState wheat = cropState(level.getBlockState(cropPos));
			level.setBlock(cropPos, wheat, 3);
			farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), ref.bell()));
			farmer.teleportTo(cropPos.getX() + 0.5, cropPos.getY(), cropPos.getZ() + 0.5);
			int storedWheatBefore = JobUtil.count(box, stack -> stack.is(Items.WHEAT));
			actionTime += 30;
			boolean harvested = new FarmerJob().primary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(harvested && level.getBlockState(cropPos).getBlock() instanceof CropBlock crop
				&& level.getBlockState(cropPos).getValue(CropBlock.AGE) == 0,
				"farmer's primary work cycle harvests a mature crop and replants it");
			int carriedFood = JobUtil.count(farmer.getInventory(), new FarmerJob().produce());
			check(carriedFood > 0, "harvested crop enters the farmer inventory");
			BlockPos foodChest = publicStorageFixturePos[0];
			BlockPos foodStand = JobUtil.sharedStandSpot(new Ctx(level, farmer, state, memory, ref, actionTime), foodChest, 4.0);
			if (foodStand == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: no reachable granary delivery stand");
			farmer.teleportTo(foodStand.getX() + 0.5, foodStand.getY(), foodStand.getZ() + 0.5);
			actionTime += 30;
			boolean delivered = new FarmerJob().primary(new Ctx(level, farmer, state, memory, ref, actionTime));
			int storedWheatAfter = JobUtil.count(box, stack -> stack.is(Items.WHEAT));
			check(delivered && storedWheatAfter > storedWheatBefore,
				"harvested wheat is delivered from the farmer inventory into the communal food store [before="
					+ storedWheatBefore + ", after=" + storedWheatAfter + ", carried="
					+ JobUtil.count(farmer.getInventory(), new FarmerJob().produce()) + ", stand=" + foodStand + "]");
			actionTime += 30;
			state.idleUntil[1] = 0;
			boolean waitingForGrowth = new FarmerJob().secondary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(waitingForGrowth && state.activity.equals("wartet auf reife Feldfrüchte"),
				"a farmer with a planted field stays on an explicit crop-growth duty until harvest work is ready");

			BlockPos outsideZoneCrop = null;
			for (int dx = 7; dx <= 16 && outsideZoneCrop == null; dx++) for (int dz = -16; dz <= 16; dz++) {
				BlockPos candidate = actual.offset(dx, 0, dz);
				if (level.hasChunkAt(candidate) && level.getBlockState(candidate).isAir() && level.getBlockEntity(candidate) == null) {
					outsideZoneCrop = candidate.immutable(); break;
				}
			}
			if (outsideZoneCrop == null) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: no loaded crop cell outside farm zone");
			before.putIfAbsent(outsideZoneCrop, level.getBlockState(outsideZoneCrop));
			BlockState unauthorizedCrop = cropState(Blocks.WHEAT.defaultBlockState());
			level.setBlock(outsideZoneCrop, unauthorizedCrop, 3);
			farmer.teleportTo(outsideZoneCrop.getX() + 0.5, outsideZoneCrop.getY(), outsideZoneCrop.getZ() + 0.5);
			state.clearTarget();
			memory.workSpots.clear();
			memory.workSpots.add(outsideZoneCrop.immutable());
			actionTime += 30;
			boolean unauthorizedHarvest = new FarmerJob().primary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(!unauthorizedHarvest && level.getBlockState(outsideZoneCrop).equals(unauthorizedCrop),
				"remembered mature crops outside an explicit farm zone are left for their owner");
			actionTime += 250;
			memory.workSpots.clear();
			boolean searchedUnauthorizedHarvest = new FarmerJob().primary(new Ctx(level, farmer, state, memory, ref, actionTime));
			check(!searchedUnauthorizedHarvest && level.getBlockState(outsideZoneCrop).equals(unauthorizedCrop),
				"the fallback field scan also ignores mature crops outside an explicit farm zone");

			// A full inventory must postpone the harvest instead of scattering crops around the field.
			var inventory = farmer.getInventory();
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
			BlockState mature = cropState(level.getBlockState(cropPos));
			level.setBlock(cropPos, mature, 3);
			state.clearTarget();
			state.target = cropPos.immutable();
			memory.workSpots.add(cropPos.immutable());
			farmer.teleportTo(cropPos.getX() + 0.5, cropPos.getY(), cropPos.getZ() + 0.5);
			long looseBefore = level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(cropPos).inflate(3)).size();
			// The preceding no-crop fallback intentionally idled the primary slot for 200
			// ticks. Make this separate full-inventory scenario runnable when the idle expires.
			actionTime = Math.max(actionTime + 30, state.idleUntil[0]);
			boolean deferred = new FarmerJob().primary(new Ctx(level, farmer, state, memory, ref, actionTime));
			long looseAfter = level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(cropPos).inflate(3)).size();
			check(deferred && level.getBlockState(cropPos).equals(mature)
				&& looseAfter == looseBefore && JobUtil.count(inventory, new FarmerJob().produce()) == 0,
				"a full farmer inventory leaves the mature crop intact and retries without spilling drops [deferred="
					+ deferred + ", crop=" + level.getBlockState(cropPos) + ", loose=" + looseBefore + "->" + looseAfter
					+ ", produced=" + JobUtil.count(inventory, new FarmerJob().produce()) + ", now=" + actionTime + "]");
			return 121;
		} finally {
			for (Villager villager : fixtures) villager.discard();
			oldExpansionPlans.forEach((villager, plan) -> { if (!villager.isRemoved()) villager.addTag(plan); });
			if (privateFoodChestPos[0] != null) {
				level.removeBlockEntity(privateFoodChestPos[0]);
				level.setBlock(privateFoodChestPos[0], Blocks.AIR.defaultBlockState(), 3);
			}
			if (publicStorageFixturePos[0] != null) {
				level.removeBlockEntity(publicStorageFixturePos[0]);
				level.setBlock(publicStorageFixturePos[0], before.getOrDefault(publicStorageFixturePos[0], Blocks.AIR.defaultBlockState()), 3);
			}
			before.forEach((pos, state) -> level.setBlock(pos, state, 3));
			restoreStorage(oldStorage);
			ref.data().farms = oldFarms; ref.data().extra.pen = oldPen; ref.data().extra.nextFarm = oldNextFarm;
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES); else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS); else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.dirty();
		}
	}

	private static void checkVisibleStarterFarmBlueprint() {
		SimpleContainer seedStock = new SimpleContainer(1);
		seedStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		int compactVariant = FarmPlanSizing.chooseVariant(0, seedStock);
		seedStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 24));
		int laterExpansionVariant = FarmPlanSizing.chooseVariant(1, seedStock);
		SimpleContainer mixedStarterStock = new SimpleContainer(2);
		mixedStarterStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		mixedStarterStock.setItem(1, new ItemStack(Items.CARROT, 24));
		int mixedStarterVariant = Builder.farmPlanVariant(0, mixedStarterStock);
		mixedStarterStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 7));
		int underfundedStarterVariant = Builder.farmPlanVariant(0, mixedStarterStock);
		mixedStarterStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 24));
		int largerWheatStarterVariant = Builder.farmPlanVariant(0, mixedStarterStock);
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			List<Step> compact = Blueprints.farm(null, BlockPos.ZERO, facing, compactVariant);
			List<Step> expansion = Blueprints.farm(null, BlockPos.ZERO, facing, laterExpansionVariant);
			java.util.Set<BlockPos> compactPositions = new java.util.HashSet<>();
			java.util.Set<BlockPos> expansionPositions = new java.util.HashSet<>();
			boolean unique = compact.stream().allMatch(step -> compactPositions.add(step.pos()))
				&& expansion.stream().allMatch(step -> expansionPositions.add(step.pos()));
			long starterSeeds = compact.stream().filter(step -> step.item() == Items.WHEAT_SEEDS && step.cost() == 1).count();
			long starterFences = compact.stream().filter(step -> step.item() == Blocks.OAK_FENCE.asItem()
				&& step.cost() == 1 && !step.optional()).count();
			List<Step> gates = compact.stream().filter(step -> step.item() == Blocks.OAK_FENCE_GATE.asItem()).toList();
			long starterFarmland = compact.stream().filter(Step::free)
				.filter(step -> step.make().apply(Blocks.AIR).is(Blocks.FARMLAND)).count();
			List<Step> starterFarmlandSteps = compact.stream().filter(Step::free)
				.filter(step -> step.make().apply(Blocks.AIR).is(Blocks.FARMLAND)).toList();
			List<Step> starterWater = compact.stream().filter(Step::free)
				.filter(step -> step.make().apply(Blocks.AIR).is(Blocks.WATER)).toList();
			boolean centralIrrigation = starterWater.size() == 1 && starterWater.getFirst().pos().equals(BlockPos.ZERO.below());
			long starterRows = compact.stream().filter(step -> step.item() == Items.WHEAT_SEEDS)
				.map(step -> step.pos().getZ()).distinct().count();
			boolean starterIrrigated = centralIrrigation && starterFarmlandSteps.stream().allMatch(step ->
				step.pos().getY() == starterWater.getFirst().pos().getY()
					&& Math.abs(step.pos().getX()) <= 2 && Math.abs(step.pos().getZ()) <= 2);
			long expansionSeeds = expansion.stream().filter(step -> step.item() == Items.WHEAT_SEEDS && step.cost() == 1).count();
			long expansionFences = expansion.stream().filter(step -> step.item() == Blocks.OAK_FENCE.asItem()
				&& step.cost() == 1 && !step.optional()).count();
			long expansionFarmland = expansion.stream().filter(Step::free)
				.filter(step -> step.make().apply(Blocks.AIR).is(Blocks.FARMLAND)).count();
			long expansionRows = expansion.stream().filter(step -> step.item() == Items.WHEAT_SEEDS)
				.map(step -> step.pos().getZ()).distinct().count();
			List<Step> expansionWater = expansion.stream().filter(Step::free)
				.filter(step -> step.make().apply(Blocks.AIR).is(Blocks.WATER)).toList();
			boolean expansionIrrigated = expansionWater.size() == 1
				&& expansionWater.getFirst().pos().equals(BlockPos.ZERO.below());
			boolean walkableGate = gates.size() == 1 && gates.getFirst().cost() == 1
				&& !gates.getFirst().optional()
				&& gates.getFirst().make().apply(Blocks.OAK_FENCE_GATE).getValue(FenceGateBlock.FACING) == facing
				&& gates.getFirst().make().apply(Blocks.OAK_FENCE_GATE).getValue(FenceGateBlock.OPEN);
			check(compactVariant == 10 && laterExpansionVariant == 20
				&& mixedStarterVariant == 10 && underfundedStarterVariant == -1 && largerWheatStarterVariant == 20 && unique
				&& starterSeeds == 8 && starterFarmland == 8 && starterRows == 3 && starterIrrigated
				&& starterFences == 15 && walkableGate
				&& expansionSeeds == 24 && expansionFarmland == 24 && expansionRows == 5
				&& expansionFences == 23 && expansionIrrigated,
				"eight seeds select a compact paid 3x3 first field; 24 later seeds select an irrigated 5x5 expansion facing " + facing);
		}
	}

	private static void checkPaidStarterFootprint() {
		BlockPos center = new BlockPos(40, 72, -18);
		java.util.Set<BlockPos> fencePositions = new java.util.HashSet<>();
		BlockPos gate = null;
		for (int cursor = 0; cursor < 20; cursor++) {
			BlockPos pos = FarmerJob.expansionStepPosForChecks(center, cursor);
			check(pos != null && fencePositions.add(pos), "starter fence cursor " + cursor + " has one unique perimeter cell");
			if (pos.equals(center.offset(0, 0, 3))) gate = pos;
		}
		java.util.Set<BlockPos> cropPositions = new java.util.HashSet<>();
		for (int cursor = 21; cursor < 36; cursor++) {
			BlockPos pos = FarmerJob.expansionStepPosForChecks(center, cursor);
			check(pos != null && cropPositions.add(pos) && pos.getX() >= center.getX() - 1
				&& pos.getX() <= center.getX() + 2 && pos.getZ() >= center.getZ() - 1
				&& pos.getZ() <= center.getZ() + 2 && !pos.equals(center),
				"starter crop cursor " + cursor + " stays inside the irrigated 4-by-4 bed");
		}
		check(fencePositions.size() == 20 && gate != null
			&& FarmerJob.expansionStepPosForChecks(center, 20).equals(center.below())
			&& cropPositions.size() == 15
			&& FarmerJob.expansionStepPosForChecks(center, 36) == null,
			"the later paid plot has 19 fence costs plus one gate, one water bucket, 15 seed costs and 36 resumable steps");
		String savedLegacyPlan = "vp_farm_expansion_v1|" + center.asLong()
			+ "|minecraft:wheat_seeds|minecraft:oak_fence|minecraft:oak_fence_gate|17";
		check(FarmerJob.expansionCursorForChecks(savedLegacyPlan) == 17
			&& FarmerJob.legacyExpansionStepPosForChecks(center, 16).equals(center.below())
			&& FarmerJob.legacyExpansionStepPosForChecks(center, 17).equals(center.offset(-1, 0, -1))
			&& FarmerJob.legacyExpansionStepPosForChecks(center, 24).equals(center.offset(1, 0, 1))
			&& FarmerJob.legacyExpansionStepPosForChecks(center, 25) == null,
			"a saved v1 cursor retains its original 3-by-3 field steps after the new footprint ships");
	}

	private static void put(net.minecraft.world.Container box, int slot, Item item, int count) { box.setItem(slot, new ItemStack(item, count)); }
	private static int countPublicStock(List<Container> stores, Item item) {
		int count = 0;
		for (Container store : stores) count += JobUtil.count(store, stack -> stack.is(item));
		return count;
	}
	private static void removePublicStock(List<Container> stores, Item item) {
		for (Container store : stores) {
			for (int slot = 0; slot < store.getContainerSize(); slot++) {
				if (store.getItem(slot).is(item)) store.setItem(slot, ItemStack.EMPTY);
			}
			store.setChanged();
		}
	}
	private static void preparePublicWaterPool(ServerLevel level, BlockPos center, Map<BlockPos, BlockState> before) {
		for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
			BlockPos surface = center.offset(dx, 0, dz);
			level.getChunkAt(surface);
			before.putIfAbsent(surface.below(), level.getBlockState(surface.below()));
			before.putIfAbsent(surface, level.getBlockState(surface));
			before.putIfAbsent(surface.above(), level.getBlockState(surface.above()));
			level.setBlock(surface.below(), Blocks.STONE.defaultBlockState(), 3);
			boolean water = dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
			level.setBlock(surface, water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(surface.above(), Blocks.AIR.defaultBlockState(), 3);
		}
	}
	private static boolean adjacentChest(ServerLevel level, BlockPos pos) {
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			if (level.getBlockState(pos.relative(direction)).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) return true;
		}
		return false;
	}
	private static List<StorageSnapshot> snapshotStorage(List<Container> stores) {
		List<StorageSnapshot> snapshots = new ArrayList<>();
		for (Container store : stores) {
			List<ItemStack> items = new ArrayList<>();
			for (int slot = 0; slot < store.getContainerSize(); slot++) items.add(store.getItem(slot).copy());
			snapshots.add(new StorageSnapshot(store, items));
		}
		return snapshots;
	}
	private static void restoreStorage(List<StorageSnapshot> snapshots) {
		for (StorageSnapshot snapshot : snapshots) {
			Container store = snapshot.store();
			store.clearContent();
			for (int slot = 0; slot < Math.min(store.getContainerSize(), snapshot.items().size()); slot++) {
				store.setItem(slot, snapshot.items().get(slot).copy());
			}
			store.setChanged();
		}
	}
	private static BlockState cropState(BlockState state) {
		return state.getBlock() instanceof CropBlock crop ? crop.getStateForAge(crop.getMaxAge()) : Blocks.WHEAT.defaultBlockState();
	}
	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("FARM EXPANSION CHECK FAILED: " + message);
		System.out.println("FARM EXPANSION PASS " + message);
	}
	private record StorageSnapshot(Container store, List<ItemStack> items) { }
}
