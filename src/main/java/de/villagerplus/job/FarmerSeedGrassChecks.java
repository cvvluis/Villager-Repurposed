package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Balance;
import de.villagerplus.village.VillageRef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

/** Synthetic checks for safe starter-seed foraging, home protection, and communal seed delivery. */
public final class FarmerSeedGrassChecks {
	private FarmerSeedGrassChecks() { }

	private record BlockSnapshot(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos column = ref.bell().offset(30, 0, 0);
		level.getChunkAt(column);
		BlockPos candidate = new BlockPos(column.getX(), level.getHeight(
			net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
		BlockPos homePoi = candidate.south(2);
		Map<BlockPos, BlockSnapshot> before = new LinkedHashMap<>();
		PoiManager poi = level.getPoiManager();
		boolean addedHomePoi = false;
		int checks = 0;
		try {
			require(!FarmerJob.shouldBuildInitialPen(0, true, 2)
				&& !FarmerJob.shouldBuildInitialPen(1, false, 2)
				&& !FarmerJob.shouldBuildInitialPen(1, true, 1)
				&& FarmerJob.shouldBuildInitialPen(1, true, 2),
				"the first pen is routed to the Builder after one field in a two-resident village, but never duplicated or built before the field"); checks++;
			require(FarmerJob.shouldBuildInitialPen(0, true, 2, true, true, false)
				&& !FarmerJob.shouldBuildInitialPen(0, true, 2, false, true, false)
				&& !FarmerJob.shouldBuildInitialPen(0, true, 2, true, false, false)
				&& FarmerJob.shouldBuildInitialPen(0, true, 2, false, false, true)
				&& !FarmerJob.shouldBuildInitialPen(0, false, 2, true, true, true),
				"a real herd can unlock a paid first pen before the starter field, while missing animals/storage and an existing pen suppress a new request"); checks++;
			require(FarmerJob.shouldPrioritizeInitialPenBeforeNewExpansion(false, true, false)
				&& !FarmerJob.shouldPrioritizeInitialPenBeforeNewExpansion(true, true, false)
				&& !FarmerJob.shouldPrioritizeInitialPenBeforeNewExpansion(false, true, true)
				&& !FarmerJob.shouldPrioritizeInitialPenBeforeNewExpansion(false, false, false),
				"a due first pen precedes a new field expansion, while first-field work and a saved expansion cursor keep priority"); checks++;
			int minPenPopulation = de.villagerplus.build.Builder.MIN_INITIAL_PEN_POPULATION;
			require(FarmerJob.nextClusterFarmBuildAt(1, true, minPenPopulation, 100L) == 100L
				&& FarmerJob.nextClusterFarmBuildAt(1, true, minPenPopulation - 1, 100L) == 100L + de.villagerplus.Config.BUILD_COOLDOWN
				&& FarmerJob.nextClusterFarmBuildAt(2, false, minPenPopulation, 100L) == 100L + de.villagerplus.Config.BUILD_COOLDOWN,
				"a saved field cursor that completes the first farm makes its eligible pen available immediately; other farm completions retain cooldown"); checks++;
			if (poi.getType(homePoi).isPresent()) throw new IllegalStateException("SEED GRASS CHECK FAILED: HOME POI fixture position is already occupied");
			set(level, before, candidate.below(), Blocks.GRASS_BLOCK.defaultBlockState());
			set(level, before, candidate, Blocks.SHORT_GRASS.defaultBlockState());
			set(level, before, candidate.above(), Blocks.AIR.defaultBlockState());
			require(FarmerJob.safeSeedGrass(level, ref, candidate), "short grass over natural grass soil with clear headroom is safe"); checks++;
			require(candidate.equals(FarmerJob.seedGrassSurface(level, candidate)),
				"starter-seed search resolves the actual surface height instead of sampling at the villager's y level"); checks++;
			require(!FarmerJob.nearbyHomePoi(level, candidate), "grass without a nearby HOME POI remains eligible for the distance gate"); checks++;
			checks += starterSeedSearchChecks(level, ref, candidate);
			JobState forageState = new JobState();
			forageState.forageTarget = candidate;
			FarmerJob.rejectForageTarget(forageState, candidate);
			require(forageState.forageTarget == null && FarmerJob.rejectedForageTarget(forageState, candidate),
				"a grass patch with no seed drop is released so the farmer can try another patch"); checks++;
			checks += starterSeedForagingWithoutStorage(level, ref, candidate, before);

			set(level, before, candidate, Blocks.DANDELION.defaultBlockState());
			require(!FarmerJob.safeSeedGrass(level, ref, candidate), "flowers and other non-grass plants are left untouched"); checks++;

			set(level, before, candidate, Blocks.SHORT_GRASS.defaultBlockState());
			set(level, before, candidate.below(), Blocks.DIRT.defaultBlockState());
			require(!FarmerJob.safeSeedGrass(level, ref, candidate), "short grass over ordinary dirt is not treated as a safe natural seed source"); checks++;

			set(level, before, candidate.below(), Blocks.FARMLAND.defaultBlockState());
			require(!FarmerJob.safeSeedGrass(level, ref, candidate), "farmland is excluded from wild-grass seed collection"); checks++;

			set(level, before, candidate.below(), Blocks.GRASS_BLOCK.defaultBlockState());
			set(level, before, candidate.above(), Blocks.STONE.defaultBlockState());
			require(!FarmerJob.safeSeedGrass(level, ref, candidate), "blocked headroom prevents breaking the grass block"); checks++;

			set(level, before, candidate.above(), Blocks.AIR.defaultBlockState());
			require(FarmerJob.safeSeedGrass(level, ref, candidate), "restoring open headroom restores the safe-grass result"); checks++;
			Villager occupant = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (occupant == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: could not create the occupied-grass fixture");
			occupant.setPos(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
			occupant.setNoAi(true);
			try {
				if (!level.addFreshEntity(occupant)) throw new IllegalStateException("SEED GRASS CHECK FAILED: occupied-grass fixture was rejected");
				require(!FarmerJob.safeSeedGrass(level, ref, candidate), "living entities are not displaced to collect wild grass"); checks++;
			} finally {
				occupant.discard();
			}

			poi.add(homePoi, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME));
			addedHomePoi = true;
			require(poi.getType(homePoi).filter(type -> type.is(PoiTypes.HOME)).isPresent(), "the synthetic nearby HOME POI is registered"); checks++;
			require(FarmerJob.safeSeedGrass(level, ref, candidate) && FarmerJob.nearbyHomePoi(level, candidate),
				"grass remains structurally safe while the shared search gate excludes nearby HOME POIs"); checks++;

			BlockPos unloaded = unloadedPosition(level, ref.bell());
			if (unloaded != null) {
				require(!FarmerJob.safeSeedGrass(level, ref, unloaded) && !level.hasChunkAt(unloaded),
					"an unloaded chunk is rejected without being loaded by the safety check"); checks++;
				require(FarmerJob.seedGrassSurface(level, unloaded) == null && !level.hasChunkAt(unloaded),
					"surface lookup also skips unloaded chunks without loading them"); checks++;
			}

			checks += starterSeedDeliveryChecks(level, ref, before);
			return checks;
		} finally {
			if (addedHomePoi) poi.remove(homePoi);
			for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
			before.forEach((pos, snapshot) -> level.setBlock(pos, snapshot.state(), 3));
			before.forEach((pos, snapshot) -> {
				if (snapshot.blockEntity() == null) return;
				BlockEntity blockEntity = BlockEntity.loadStatic(pos, snapshot.state(), snapshot.blockEntity().copy(), level.registryAccess());
				if (blockEntity != null) level.setBlockEntity(blockEntity);
			});
		}
	}

	/** The local forage pass covers a fixed square once and deterministically finds valid loaded grass. */
	private static int starterSeedSearchChecks(ServerLevel level, VillageRef ref, BlockPos grass) {
		int checks = 0;
		BlockPos center = new BlockPos(120, grass.getY(), -240);
		Set<Long> visited = new HashSet<>();
		int columns = FarmerJob.starterSeedSearchColumnCount();
		for (int index = 0; index < columns; index++) {
			BlockPos column = FarmerJob.starterSeedSearchColumn(center, index);
			if (column == null || !visited.add(column.asLong())) {
				throw new IllegalStateException("SEED GRASS CHECK FAILED: bounded starter search has a missing or duplicate column at " + index);
			}
		}
		BlockPos first = FarmerJob.starterSeedSearchColumn(center, 0);
		BlockPos last = FarmerJob.starterSeedSearchColumn(center, columns - 1);
		require(visited.size() == columns && first.equals(center.offset(-32, 0, -32))
			&& last.equals(center.offset(32, 0, 32))
			&& FarmerJob.starterSeedSearchColumn(center, -1) == null
			&& FarmerJob.starterSeedSearchColumn(center, columns) == null,
			"seed search uses a complete, unique 65x65 column budget with no out-of-range index"); checks++;

		Villager finder = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		if (finder == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: could not create deterministic search fixture");
		try {
			finder.setNoAi(true);
			finder.setInvulnerable(true);
			finder.setPos(grass.getX() - 0.5, grass.getY(), grass.getZ() + 0.5);
			JobState state = new JobState();
			VillagerMemory memory = finder.getAttachedOrCreate(VillagerPlus.MEMORY);
			Ctx context = new Ctx(level, finder, state, memory, ref, level.getGameTime() + 1L);
			BlockPos firstFound = FarmerJob.findSeedGrass(context, ref);
			BlockPos repeatedFound = FarmerJob.findSeedGrass(context, ref);
			require(firstFound != null && firstFound.equals(repeatedFound)
				&& FarmerJob.safeSeedGrass(level, ref, firstFound)
				&& !FarmerJob.nearbyHomePoi(level, firstFound),
				"a repeatable bounded search selects a safe loaded seed patch without crossing HOME protection"); checks++;
			return checks;
		} finally {
			finder.discard();
		}
	}

	/** A starter farmer must forage and preserve its real seed drops even before the first shared chest exists. */
	private static int starterSeedForagingWithoutStorage(ServerLevel level, VillageRef ref, BlockPos grass,
		Map<BlockPos, BlockSnapshot> before) {
		var villageData = ref.data();
		var oldChestPos = villageData.chest;
		var oldLager = villageData.extra.lager;
		var oldLagerMore = new java.util.ArrayList<>(villageData.extra.lagerMore);
		var oldLagerChests = new java.util.ArrayList<>(villageData.extra.lagerChests);
		var oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		int oldFarms = villageData.farms;
		Villager worker = null;
		try {
			villageData.chest = java.util.Optional.empty();
			villageData.extra.lager = java.util.Optional.empty();
			villageData.extra.lagerMore.clear();
			villageData.extra.lagerChests.clear();
			villageData.farms = 0;
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.of());
			ref.dirty();
			require(Balance.storage(level, ref).isEmpty(), "the fixture genuinely has no public storage before its first depot");

			worker = EntityTypes.VILLAGER.spawn(level, grass.offset(1, 0, 0), EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: could not spawn no-depot farmer fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.setPos(grass.getX() + 1.5, grass.getY(), grass.getZ() + 0.5);
			JobState state = JobManager.state(worker);
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			state.forageTarget = grass.immutable();
			Ctx context = new Ctx(level, worker, state, memory, ref, level.getGameTime() + 20L);
			boolean triedGrass = FarmerJob.forageStarterWheatSeeds(context);
			require(triedGrass && (level.getBlockState(grass).isAir() || FarmerJob.rejectedForageTarget(state, grass)),
				"farmer continues real seed foraging while the village has no public chest");

			set(level, before, grass, Blocks.SHORT_GRASS.defaultBlockState());
			worker.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 15));
			state.forageTarget = grass.immutable();
			context = new Ctx(level, worker, state, memory, ref, level.getGameTime() + 40L);
			boolean retained = FarmerJob.forageStarterWheatSeeds(context);
			require(retained && count(worker.getInventory(), Items.WHEAT_SEEDS) == 15
				&& level.getBlockState(grass).is(Blocks.SHORT_GRASS) && state.forageTarget == null,
				"15 recovered seeds are preserved while the farmer waits for the first public depot");

			// Simulate the normal long-route timeout without waiting 1,500 game ticks.
			worker.getInventory().clearContent();
			worker.teleportTo(grass.getX() + 20.5, grass.getY(), grass.getZ() + 0.5);
			state.forageTarget = grass.immutable();
			state.forageRejectedTarget = null;
			state.walkStart = 0;
			state.cooldownUntil = 0;
			state.bestDist = 0;
			state.lastMoveAt = 0;
			state.stuckCount = 0;
			state.idleUntil[0] = 0;
			state.idleUntil[1] = 0;
			state.target = null;
			memory.workSpots.clear();
			worker.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
			long routeStart = Math.max(level.getGameTime() + 60L, 2_000L);
			context = new Ctx(level, worker, state, memory, ref, routeStart);
			boolean stillApproaching = new FarmerJob().primary(context);
			require(stillApproaching && grass.equals(state.forageTarget) && state.walkStart > 0,
				"a valid distant seed patch remains selected while the farmer follows its route");

			long routeFollowup = routeStart + 1L;
			context = new Ctx(level, worker, state, memory, ref, routeFollowup);
			boolean keptRoute = new FarmerJob().primary(context);
			require(keptRoute && grass.equals(state.forageTarget) && state.walkStart == routeStart,
				"the no-crop primary cycle preserves the active starter-seed route timer");

			state.walkStart = routeFollowup - 1501L;
			long routeAbortAt = routeFollowup + 1L;
			context = new Ctx(level, worker, state, memory, ref, routeAbortAt);
			boolean released = new FarmerJob().primary(context);
			require(released && state.forageTarget == null && FarmerJob.rejectedForageTarget(state, grass)
				&& state.cooldownUntil > routeAbortAt && level.getBlockState(grass).is(Blocks.SHORT_GRASS),
				"after a bounded unreachable-route abort, the farmer releases that patch and can choose another without altering it");
			return 5;
		} finally {
			if (worker != null) {
				JobUtil.clearLongRoute(worker);
				worker.getInventory().clearContent();
				worker.discard();
			}
			villageData.chest = oldChestPos;
			villageData.extra.lager = oldLager;
			villageData.extra.lagerMore.clear();
			villageData.extra.lagerMore.addAll(oldLagerMore);
			villageData.extra.lagerChests.clear();
			villageData.extra.lagerChests.addAll(oldLagerChests);
			villageData.farms = oldFarms;
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
			else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.dirty();
		}
	}

	/** Exercises the actual communal deposit path before and after the Granary special is registered. */
	private static int starterSeedDeliveryChecks(ServerLevel level, VillageRef ref,
		Map<BlockPos, BlockSnapshot> before) {
		BlockPos mainChestPos = ref.chestPos(level);
		if (mainChestPos == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: fixture has no registered public village chest");
		Container mainChest = HopperBlockEntity.getContainerAt(level, mainChestPos);
		if (mainChest == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: public village chest has no container");
		remember(level, before, mainChestPos);
		Map<String, BlockPos> oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		Map<String, Integer> oldPolicies = ref.bellEntity().getAttached(VillagerPlus.STOCK_POLICIES);
		Villager worker = EntityTypes.VILLAGER.spawn(level, mainChestPos, EntitySpawnReason.COMMAND);
		if (worker == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: could not spawn seed-delivery villager");
		worker.setNoAi(true);
		worker.setInvulnerable(true);
		int checks = 0;
		BlockPos granaryChestPos = mainChestPos.offset(32, 0, 0);
		try {
			Map<String, BlockPos> specialsWithoutGranary = new HashMap<>(oldSpecials == null ? Map.of() : oldSpecials);
			specialsWithoutGranary.remove(Blueprints.GRANARY);
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(specialsWithoutGranary));
			Map<String, Integer> policiesWithoutSeedCap = new HashMap<>(oldPolicies == null ? Map.of() : oldPolicies);
			policiesWithoutSeedCap.remove("minecraft:wheat_seeds");
			policiesWithoutSeedCap.remove("minecraft:bread");
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.copyOf(policiesWithoutSeedCap));
			mainChest.clearContent();

			JobState state = JobManager.state(worker);
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			long now = level.getGameTime() + 20L;
			Ctx context = new Ctx(level, worker, state, memory, ref, now);
			prepareDeliveryRing(level, before, mainChestPos);
			worker.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 2));
			BlockPos stand = JobUtil.sharedStandSpot(context, mainChestPos, 4.0);
			if (stand == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: public chest has no fixture delivery stand");
			worker.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			boolean depositedWithoutGranary = JobUtil.depositSurplus(context,
				stack -> stack.is(Items.WHEAT_SEEDS), 1, 0);
			require(depositedWithoutGranary && count(mainChest, Items.WHEAT_SEEDS) == 2
				&& count(worker.getInventory(), Items.WHEAT_SEEDS) == 0,
				"starter wheat seeds reach the real public village chest before a Granary exists"); checks++;

			preparePublicChest(level, before, granaryChestPos);
			prepareDeliveryRing(level, before, granaryChestPos);
			Map<String, BlockPos> specialsWithGranary = new HashMap<>(specialsWithoutGranary);
			specialsWithGranary.put(Blueprints.GRANARY, granaryChestPos);
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(specialsWithGranary));
			worker.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 3));
			state.clearTarget();
			context = new Ctx(level, worker, state, memory, ref, now + 100L);
			stand = JobUtil.sharedStandSpot(context, mainChestPos, 4.0);
			if (stand == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: public material chest has no fixture seed-delivery stand");
			worker.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			boolean depositedToBuilderStock = JobUtil.depositSurplus(context,
				stack -> stack.is(Items.WHEAT_SEEDS), 1, 0);
			require(depositedToBuilderStock && count(HopperBlockEntity.getContainerAt(level, granaryChestPos), Items.WHEAT_SEEDS) == 0
				&& count(mainChest, Items.WHEAT_SEEDS) == 5
				&& JobUtil.count(de.villagerplus.village.Lager.combined(level, ref), stack -> stack.is(Items.WHEAT_SEEDS)) == 5
				&& count(worker.getInventory(), Items.WHEAT_SEEDS) == 0,
				"registered Granary does not hide starter seeds from the Builder's public construction stock"); checks++;

			worker.getInventory().setItem(0, new ItemStack(Items.BREAD, 4));
			state.clearTarget();
			context = new Ctx(level, worker, state, memory, ref, now + 200L);
			stand = JobUtil.sharedStandSpot(context, granaryChestPos, 4.0);
			if (stand == null) throw new IllegalStateException("SEED GRASS CHECK FAILED: Granary has no fixture food-delivery stand");
			worker.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			boolean foodStillGoesToGranary = JobUtil.depositSurplus(context,
				stack -> stack.is(Items.BREAD), 1, 0);
			require(foodStillGoesToGranary && count(HopperBlockEntity.getContainerAt(level, granaryChestPos), Items.BREAD) == 4
				&& count(mainChest, Items.BREAD) == 0 && count(worker.getInventory(), Items.BREAD) == 0,
				"ordinary bread still prefers the registered Granary"); checks++;
			return checks;
		} finally {
			worker.getInventory().clearContent();
			worker.discard();
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
			else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			if (oldPolicies == null) ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
			else ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, oldPolicies);
			ref.dirty();
		}
	}

	private static void preparePublicChest(ServerLevel level, Map<BlockPos, BlockSnapshot> before, BlockPos pos) {
		set(level, before, pos.below(), Blocks.STONE.defaultBlockState());
		set(level, before, pos, Blocks.AIR.defaultBlockState());
		set(level, before, pos.above(), Blocks.AIR.defaultBlockState());
		set(level, before, pos, Blocks.CHEST.defaultBlockState());
		if (HopperBlockEntity.getContainerAt(level, pos) == null)
			throw new IllegalStateException("SEED GRASS CHECK FAILED: Granary chest fixture has no container");
	}

	private static void prepareDeliveryRing(ServerLevel level, Map<BlockPos, BlockSnapshot> before, BlockPos chest) {
		for (int[] offset : new int[][]{{3,0},{2,2},{0,3},{-2,2},{-3,0},{-2,-2},{0,-3},{2,-2},
			{3,1},{1,3},{-1,3},{-3,1},{-3,-1},{-1,-3},{1,-3},{3,-1}}) {
			BlockPos stand = chest.offset(offset[0], 0, offset[1]);
			set(level, before, stand.below(), Blocks.STONE.defaultBlockState());
			set(level, before, stand, Blocks.AIR.defaultBlockState());
			set(level, before, stand.above(), Blocks.AIR.defaultBlockState());
		}
	}

	private static int count(Container box, net.minecraft.world.item.Item item) {
		return JobUtil.count(box, stack -> stack.is(item));
	}

	private static void remember(ServerLevel level, Map<BlockPos, BlockSnapshot> before, BlockPos pos) {
		level.getChunkAt(pos);
		before.computeIfAbsent(pos, p -> {
			BlockEntity blockEntity = level.getBlockEntity(p);
			return new BlockSnapshot(level.getBlockState(p), blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess()));
		});
	}

	private static BlockPos unloadedPosition(ServerLevel level, BlockPos origin) {
		for (int offset = 4096; offset <= 1_048_576; offset *= 2) {
			BlockPos position = origin.offset(offset, 0, 0);
			if (!level.hasChunkAt(position)) return position;
		}
		return null;
	}

	private static void set(ServerLevel level, Map<BlockPos, BlockSnapshot> before, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		before.computeIfAbsent(pos, p -> {
			BlockEntity blockEntity = level.getBlockEntity(p);
			return new BlockSnapshot(level.getBlockState(p), blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess()));
		});
		level.setBlock(pos, state, 2);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("SEED GRASS CHECK FAILED: " + message);
	}
}
