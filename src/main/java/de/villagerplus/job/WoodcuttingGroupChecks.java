package de.villagerplus.job;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Builder;
import de.villagerplus.village.Development;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Live group fixture for multi-species tree cycles, exact sapling cost, bounded patch fallback, and protection. */
public final class WoodcuttingGroupChecks {
	private static final int WORLD_FLAGS = 3;
	private static final int MAX_CALLS_PER_TREE = 600;
	private static final int MAX_PLAN_SUPPLY_CALLS = 180;

	private WoodcuttingGroupChecks() { }

	/** Runs in a remote disposable area and restores every captured block and block entity on exit. */
	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, Cell> before = capture(level, origin);
		List<Villager> workers = new ArrayList<>();
		VillageRef ref = null;
		int checks = 0;
		try {
			prepareFloor(level, origin);
			BlockPos bellPos = origin;
			BlockPos chestPos = origin.east(3);
			level.setBlock(bellPos.below(), Blocks.STONE.defaultBlockState(), WORLD_FLAGS);
			level.setBlock(bellPos, Blocks.BELL.defaultBlockState(), WORLD_FLAGS);
			level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), WORLD_FLAGS);
			BlockEntity bell = level.getBlockEntity(bellPos);
			Container chest = level.getBlockEntity(chestPos) instanceof Container found ? found : null;
			require(bell != null && chest != null, "fixture village bell and communal chest exist"); checks++;

			VillageData data = new VillageData();
			data.chest = Optional.of(chestPos);
			data.planType = "woodcutting-group-fixture";
			data.planOrigin = Optional.of(origin);
			bell.setAttached(VillagerPlus.VILLAGE, data);
			ref = new VillageRef(bellPos, data, bell);

			BlockPos oakRoot = origin.offset(-9, 1, 0);
			BlockPos spruceRoot = origin.offset(9, 1, 0);
			makeTree(level, oakRoot, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
			makeTree(level, spruceRoot, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
			require(Woodcutting.isHarvestableTree(level, oakRoot) && Woodcutting.isHarvestableTree(level, spruceRoot),
				"rooted oak and spruce with a nonpersistent canopy qualify as separate natural tree types"); checks++;

			BlockPos placedRoot = origin.offset(0, 1, -6);
			makeTree(level, placedRoot, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
			for (BlockPos leaf : BlockPos.betweenClosed(placedRoot.offset(-2, 3, -2), placedRoot.offset(2, 5, 2))) {
				BlockState state = level.getBlockState(leaf);
				if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT))
					level.setBlock(leaf, state.setValue(LeavesBlock.PERSISTENT, true), WORLD_FLAGS);
			}
			require(!Woodcutting.isHarvestableTree(level, placedRoot), "placed persistent leaves do not turn a wood structure into a harvestable tree"); checks++;

			Villager oakWorker = spawnWorker(level, oakRoot.west(), workers);
			long expectedOak = expectedDrops(level, oakWorker, oakRoot, Items.OAK_LOG);
			data.wanted.clear(); data.wanted.add("minecraft:oak_log#1"); ref.dirty();
			oakWorker.getInventory().setItem(0, new ItemStack(Items.OAK_SAPLING, 1));
			WoodcutterWork oakWork = WoodcutterWork.empty().start(oakRoot, "minecraft:oak");
			oakWorker.setAttached(VillagerPlus.WOODCUTTER_WORK, oakWork);
			long now = driveTree(level, ref, oakWorker, expectedOak, oakRoot, Blocks.OAK_SAPLING);
			require(count(oakWorker, Items.OAK_LOG) == expectedOak && level.getBlockState(oakRoot).is(Blocks.OAK_SAPLING)
				&& count(oakWorker, Items.OAK_SAPLING) == 0,
				"oak loot reaches the worker inventory and one matching oak sapling is spent at its stump"); checks++;
			WoodcutterWork oakCycle = oakWorker.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
			List<WoodcutterWork.ReplantSite> queuedOak = oakCycle.replantSites().stream()
				.filter(site -> !site.pos().equals(oakRoot)).toList();
			WoodcutterWork resumedOak = WoodcutterWork.CODEC.parse(JsonOps.INSTANCE,
				WoodcutterWork.CODEC.encodeStart(JsonOps.INSTANCE, oakCycle).getOrThrow()).getOrThrow();
			require(queuedOak.size() == 3 && resumedOak.replantSites().equals(oakCycle.replantSites()),
				"completed oak harvest queues a bounded three-site plantation that survives a work-state round trip"); checks++;
			oakWorker.getInventory().setItem(1, new ItemStack(Items.OAK_SAPLING, 2));
			oakWorker.setAttached(VillagerPlus.WOODCUTTER_WORK, resumedOak);
			long plantationRetryAt = resumedOak.replantSites().stream()
				.mapToLong(WoodcutterWork.ReplantSite::retryAt).max().orElse(now + 20);
			Woodcutting.tick(context(level, ref, oakWorker, Math.max(now + 20, plantationRetryAt)));
			long plantedOakSites = queuedOak.stream().filter(site -> level.getBlockState(site.pos()).is(Blocks.OAK_SAPLING)).count();
			require(plantedOakSites == 2 && count(oakWorker, Items.OAK_SAPLING) == 0,
				"resumed plantation advances two nearby oak sites and charges exactly two real saplings"); checks++;

			Villager spruceWorker = spawnWorker(level, spruceRoot.west(), workers);
			long expectedSpruce = expectedDrops(level, spruceWorker, spruceRoot, Items.SPRUCE_LOG);
			data.wanted.clear(); data.wanted.add("minecraft:spruce_log#1"); ref.dirty();
			spruceWorker.getInventory().setItem(0, new ItemStack(Items.SPRUCE_SAPLING, 1));
			spruceWorker.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(spruceRoot, "minecraft:spruce"));
			driveTree(level, ref, spruceWorker, expectedSpruce, spruceRoot, Blocks.SPRUCE_SAPLING, now);
			require(count(spruceWorker, Items.SPRUCE_LOG) == expectedSpruce && level.getBlockState(spruceRoot).is(Blocks.SPRUCE_SAPLING)
				&& count(spruceWorker, Items.SPRUCE_SAPLING) == 0,
				"spruce loot and matching sapling are accounted independently from the oak cycle"); checks++;

			BlockPos blockedStump = origin.offset(0, 1, 8);
			level.setBlock(blockedStump.below(), Blocks.FARMLAND.defaultBlockState(), WORLD_FLAGS);
			for (BlockPos candidate : Woodcutting.fallbackPatchCandidates(blockedStump)) {
				level.setBlock(candidate.below(), Blocks.GRASS_BLOCK.defaultBlockState(), WORLD_FLAGS);
				level.setBlock(candidate, Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
				level.setBlock(candidate.above(), Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
				level.setBlock(candidate.above(2), Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
			}
			for (BlockPos candidate : Woodcutting.plantationCandidates(blockedStump))
				level.setBlock(candidate.below(), Blocks.FARMLAND.defaultBlockState(), WORLD_FLAGS);
			Villager patchWorker = spawnWorker(level, blockedStump.west(), workers);
			Ctx patchContext = context(level, ref, patchWorker, level.getGameTime() + 2_000);
			WoodcutterWork patch = WoodcutterWork.empty().start(blockedStump, "minecraft:birch").finishTree();
			WoodcutterWork queuedPatch = Woodcutting.queuePlantation(patchContext, patch, blockedStump, "minecraft:birch");
			List<WoodcutterWork.ReplantSite> fallback = queuedPatch.replantSites().stream()
				.filter(site -> !site.pos().equals(blockedStump)).toList();
			WoodcutterWork persistedPatch = WoodcutterWork.CODEC.parse(JsonOps.INSTANCE,
				WoodcutterWork.CODEC.encodeStart(JsonOps.INSTANCE, queuedPatch).getOrThrow()).getOrThrow();
			require(queuedPatch.replantSites().size() == 3 && fallback.size() == 3
				&& fallback.stream().allMatch(site -> site.pos().distManhattan(blockedStump) == 2)
				&& persistedPatch.replantSites().equals(queuedPatch.replantSites()),
				"normal harvest replanting uses and persists up to three two-block fallback gaps when the stump cannot be planted"); checks++;
			patchWorker.setAttached(VillagerPlus.WOODCUTTER_WORK, persistedPatch);
			Woodcutting.tick(context(level, ref, patchWorker, now + 2_050));
			WoodcutterWork waitingPatch = patchWorker.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
			require(fallback.stream().allMatch(site -> level.getBlockState(site.pos()).isAir())
				&& waitingPatch.replantSites().size() == fallback.size()
				&& waitingPatch.replantSites().stream().allMatch(site -> fallback.stream().anyMatch(queued -> queued.pos().equals(site.pos()))
					&& site.retryAt() > now + 2_050)
				&& patchWorker.getInventory().isEmpty(),
				"a sapling shortage leaves the plantation safely queued without creating free seedlings"); checks++;

			BlockPos noSpaceRoot = origin.offset(0, 1, -3);
			level.setBlock(noSpaceRoot.below(), Blocks.FARMLAND.defaultBlockState(), WORLD_FLAGS);
			for (BlockPos candidate : Woodcutting.plantationCandidates(noSpaceRoot))
				level.setBlock(candidate.below(), Blocks.FARMLAND.defaultBlockState(), WORLD_FLAGS);
			for (BlockPos candidate : Woodcutting.fallbackPatchCandidates(noSpaceRoot))
				level.setBlock(candidate.below(), Blocks.FARMLAND.defaultBlockState(), WORLD_FLAGS);
			WoodcutterWork noSpace = Woodcutting.queuePlantation(context(level, ref, patchWorker, now + 2_100),
				WoodcutterWork.empty(), noSpaceRoot, "minecraft:birch");
			require(noSpace.replantSites().isEmpty(), "a fully protected/farmland patch aborts cleanly when no safe planting site exists"); checks++;

			BlockPos placedWoodSite = oakRoot.offset(0, 0, 4);
		BlockPos adjacentBuiltSite = placedWoodSite.east();
			BlockPos nonNaturalSite = oakRoot.offset(0, 0, -4);
			level.setBlock(placedWoodSite.below(), Blocks.GRASS_BLOCK.defaultBlockState(), WORLD_FLAGS);
			level.setBlock(placedWoodSite, Blocks.OAK_PLANKS.defaultBlockState(), WORLD_FLAGS);
		level.setBlock(adjacentBuiltSite.below(), Blocks.GRASS_BLOCK.defaultBlockState(), WORLD_FLAGS);
		level.setBlock(adjacentBuiltSite, Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
		level.setBlock(adjacentBuiltSite.above(), Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
		level.setBlock(adjacentBuiltSite.above(2), Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
			level.setBlock(nonNaturalSite.below(), Blocks.FARMLAND.defaultBlockState(), WORLD_FLAGS);
			level.setBlock(nonNaturalSite, Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
		require(!Woodcutting.canPlantAt(level, null, adjacentBuiltSite, Blocks.OAK_SAPLING)
			&& !Woodcutting.canPlantAt(level, null, nonNaturalSite, Blocks.OAK_SAPLING),
			"planting rejects clear gaps beside built wood and rejects farmland soil"); checks++;
			oakWorker.getInventory().setItem(0, new ItemStack(Items.OAK_SAPLING, 2));
			WoodcutterWork protectedWork = WoodcutterWork.empty().updateReplantSites(List.of(
				new WoodcutterWork.ReplantSite(placedWoodSite, "minecraft:oak", 0),
				new WoodcutterWork.ReplantSite(nonNaturalSite, "minecraft:oak", 0)));
			oakWorker.setAttached(VillagerPlus.WOODCUTTER_WORK, protectedWork);
			Woodcutting.tick(context(level, ref, oakWorker, now + 1_300));
			require(level.getBlockState(placedWoodSite).is(Blocks.OAK_PLANKS)
				&& level.getBlockState(nonNaturalSite).isAir() && level.getBlockState(nonNaturalSite.below()).is(Blocks.FARMLAND)
				&& count(oakWorker, Items.OAK_SAPLING) == 2,
				"replanting preserves placed wood and nonforest terrain without charging saplings"); checks++;
			checks += reservedParcelCanopy(level, origin, ref, workers);
			checks += activePlanSupply(level, origin, ref, workers);
			return checks;
		} finally {
			for (Villager worker : workers) {
				Anim.cancelBreaking(worker);
				Villages.forget(worker.getUUID());
				worker.discard();
			}
			if (ref.data().hasPlan()) Builder.cancel(ref, level.getGameTime() + 600);
			restore(level, before);
		}
	}

	/** Saved outer housing parcel clearance resumes its paid tree cursor and leaves adjacent forest alone. */
	private static int reservedParcelCanopy(ServerLevel level, BlockPos origin, VillageRef ref, List<Villager> workers) {
		int checks = 0;
		var oldRoads = ref.bellEntity().getAttached(VillagerPlus.ROADS);
		var oldExtent = ref.bellEntity().getAttached(VillagerPlus.VILLAGE_EXTENT);
		List<String> oldWanted = new ArrayList<>(ref.data().wanted);
		BlockPos parcel = origin.offset(-7, 0, 5);
		BlockPos obstruction = origin.offset(-13, 1, 9);
		BlockPos forest = origin.offset(14, 1, 9);
		try {
			ref.data().wanted.clear();
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of(
				"vp_expansion:geometry", de.villagerplus.village.Roads.EXPANSION_GEOMETRY_VERSION, "vp_expansion:ready", 1L,
				"vp_expansion:x", (long) parcel.getX(), "vp_expansion:z", (long) parcel.getZ(),
				"vp_expansion:surface", (long) parcel.getY()));
			makeTree(level, obstruction, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
			makeTree(level, forest, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
			Villager worker = spawnWorker(level, obstruction.west(), workers);
			long expectedLogs = expectedDrops(level, worker, obstruction, Items.OAK_LOG);
			JobState state = JobManager.state(worker);
			state.woodCheckAt = 0;
			long now = level.getGameTime() + 5_000;
			Woodcutting.tick(context(level, ref, worker, now));
			WoodcutterWork saved = worker.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
			WoodcutterWork resumed = WoodcutterWork.CODEC.parse(JsonOps.INSTANCE,
				WoodcutterWork.CODEC.encodeStart(JsonOps.INSTANCE, saved).getOrThrow()).getOrThrow();
			require(obstruction.equals(resumed.root().orElse(null)) && Woodcutting.isReservedCanopyWork(resumed),
				"the woodcutter selects only a tree rooted inside the ready parcel and persists its clearance cursor"
					+ " [expected=" + obstruction.toShortString() + ", savedRoot=" + resumed.root().map(BlockPos::toShortString).orElse("none")
					+ ", reserved=" + Woodcutting.isReservedCanopyWork(resumed) + ", parcel=" + parcel.toShortString() + "]"); checks++;
			worker.setAttached(VillagerPlus.WOODCUTTER_WORK, resumed);
			for (int call = 0; call < MAX_CALLS_PER_TREE; call++, now += 6) {
				Woodcutting.tick(context(level, ref, worker, now));
				WoodcutterWork progress = worker.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
				if (level.getBlockState(obstruction).isAir() && progress.root().isEmpty()) break;
			}
			WoodcutterWork completed = worker.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
			require(level.getBlockState(obstruction).isAir() && completed.root().isEmpty()
				&& !level.getBlockState(forest).isAir() && count(worker, Items.OAK_LOG) == expectedLogs,
				"parcel clearing removes one bounded tree, preserves the surrounding forest, and credits real log drops"); checks++;

			Container publicStock = ref.chest(level);
			int stockBefore = (int) JobUtil.count(publicStock, stack -> stack.is(Items.OAK_LOG));
			BlockPos stand = JobUtil.sharedStandSpot(context(level, ref, worker, now + 100), ref.chestPos(level), 4.0);
			require(stand != null, "reserved-tree logs have a valid shared public-stock delivery stand");
			worker.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			JobUtil.depositSurplus(context(level, ref, worker, now + 100), stack -> stack.is(Items.OAK_LOG), 1, 0);
			require(JobUtil.count(publicStock, stack -> stack.is(Items.OAK_LOG)) > stockBefore && count(worker, Items.OAK_LOG) == 0,
				"cleared logs reach the public chest through the existing stock-policy delivery path"); checks++;
			return checks;
		} finally {
			if (oldRoads == null) ref.bellEntity().removeAttached(VillagerPlus.ROADS);
			else ref.bellEntity().setAttached(VillagerPlus.ROADS, oldRoads);
			if (oldExtent == null) ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);
			else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT, oldExtent);
			ref.data().wanted.clear();
			ref.data().wanted.addAll(oldWanted);
			ref.dirty();
		}
	}

	/** Drive one real, exact-species plan request through its assigned builder and communal delivery path. */
	private static int activePlanSupply(ServerLevel level, BlockPos origin, VillageRef ref, List<Villager> workers) {
		VillageData data = ref.data();
		Container box = ref.chest(level);
		if (box == null) throw new IllegalStateException("Woodcutting group: communal chest missing for plan supply");
		long now = level.getGameTime() + 3_000;
		Builder.cancel(ref, now);
		// This fixture stores the pre-project singleton fields below. Version zero is the
		// persisted legacy schema marker that makes Builder migrate that active plan into
		// the current per-project cursor before the woodcutting demand is inspected.
		data.buildProjectVersion = 0;
		data.planType = Blueprints.TOOLSHED;
		data.planOrigin = java.util.Optional.of(origin);
		data.planDir = 0;
		data.planStep = 0;
		data.planVariant = 64 << 2;
		data.planSource = java.util.Optional.empty();
		data.planProgress = now;
		data.wanted.clear();
		box.clearContent();

		BlockPos treeRoot = origin.offset(8, 1, 0);
		BlockPos builderPos = treeRoot.west(2);
		Villager builder = spawnWorker(level, builderPos, workers);
		builder.setPos(builderPos.getX() + 0.5, builderPos.getY(), builderPos.getZ() + 0.5);
		Villages.bindResident(level, ref, builder, origin.west(4));
		data.claimPlanBuilder(builder.getUUID());
		JobState state = JobManager.state(builder);
		state.woodCheckAt = Long.MAX_VALUE;
		Builder.bootstrapBuildWork(context(level, ref, builder, now));
		java.util.Set<String> planLogs = Woodcutting.demandedLogIds(data.wanted);
		require(data.planType.equals(Blueprints.TOOLSHED) && data.planOrigin.equals(java.util.Optional.of(origin))
			&& data.buildProjectVersion == 1
			&& data.buildProject(origin).filter(project -> project.active() && project.type().equals(Blueprints.TOOLSHED)
				&& project.owner().filter(builder.getUUID()::equals).isPresent()).isPresent()
			&& !data.wanted.isEmpty() && !planLogs.isEmpty(),
			"a persisted active toolshed plan exposes an exact requested natural-log species");
		String woodRequest = data.wanted.stream()
			.filter(request -> !Woodcutting.demandedLogIds(List.of(request)).isEmpty())
			.findFirst().orElseThrow(() -> new IllegalStateException("Woodcutting group: plan has no concrete wood material request"));
		java.util.Set<String> exactLogs = Woodcutting.demandedLogIds(List.of(woodRequest));
		String logId = exactLogs.stream().sorted().findFirst().orElseThrow();
		Block log = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(logId)).orElseThrow();
		require(Woodcutting.matchesRequestedLog(log, exactLogs), "the fixture tree uses the exact species the plan asks for");
		var bounds = Development.bounds(level, ref);
		require(treeRoot.getX() < bounds.minX() || treeRoot.getX() > bounds.maxX()
			|| treeRoot.getZ() < bounds.minZ() || treeRoot.getZ() > bounds.maxZ(),
			"the source tree grows outside the protected village footprint");
		makeTree(level, treeRoot, log, Blocks.OAK_LEAVES);
		int outsideX = treeRoot.getX() > bounds.maxX() ? 1 : treeRoot.getX() < bounds.minX() ? -1 : 0;
		int outsideZ = treeRoot.getZ() > bounds.maxZ() ? 1 : treeRoot.getZ() < bounds.minZ() ? -1 : 0;
		BlockPos roadTree = treeRoot.offset(outsideX * 3, 0, outsideZ * 3);
		BlockPos claimedTree = treeRoot.offset(outsideX * 6, 0, outsideZ * 6);
		BlockPos wrongSpeciesTree = treeRoot.offset(outsideX * 3 + (outsideZ == 0 ? 0 : 4), 0,
			outsideZ * 3 + (outsideX == 0 ? 4 : 0));
		Block otherLog = log == Blocks.OAK_LOG ? Blocks.SPRUCE_LOG : Blocks.OAK_LOG;
		makeTree(level, roadTree, log, Blocks.OAK_LEAVES);
		makeTree(level, claimedTree, log, Blocks.OAK_LEAVES);
		makeTree(level, wrongSpeciesTree, otherLog, Blocks.OAK_LEAVES);
		level.setBlock(roadTree.below().east(), Blocks.DIRT_PATH.defaultBlockState(), WORLD_FLAGS);
		Villager claimant = spawnWorker(level, claimedTree.east(2), workers);
		Villages.bindResident(level, ref, claimant, claimedTree.east(2));
		claimant.setAttached(VillagerPlus.WOODCUTTER_WORK,
			WoodcutterWork.empty().start(claimedTree, BuiltInRegistries.ITEM.getKey(log.asItem()).toString()));
		var candidateBounds = Development.bounds(level, ref);
		require(outside(candidateBounds, roadTree) && outside(candidateBounds, claimedTree) && outside(candidateBounds, wrongSpeciesTree),
			"road, claimed, and wrong-species fixture trees are outside the protected village footprint");
		builder.setPos(claimedTree.getX() + 0.5, claimedTree.getY(), claimedTree.getZ() + 0.5);
		BlockPos selectedPlanTree = Builder.selectPlanWoodTree(level, ref, builder, exactLogs);
		require(treeRoot.equals(selectedPlanTree),
			"plan tree selection skips a closer road tree, a concurrently claimed tree, and a closer wrong species");
		builder.setPos(treeRoot.getX() - 0.5, treeRoot.getY(), treeRoot.getZ() + 0.5);
		state.clearTarget();
		state.woodCheckAt = 0;
		builder.getNavigation().stop();
		builder.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
		// Fixture setup: place the no-AI resident on a reachable stand beside the natural tree once.
		builder.setPos(treeRoot.getX() - 0.5, treeRoot.getY(), treeRoot.getZ() + 0.5);

		int cursor = data.planStep;
		java.util.UUID owner = data.planBuilder().orElseThrow();
		Item requestedLog = log.asItem();
		int guard = 0;
		int dispatchTrue = 0;
		int dispatchFalse = 0;
		int ownerEntitySame = 0;
		int ownerEntityMissing = 0;
		int ownerEntityOther = 0;
		long firstCallNow = -1;
		long lastCallNow = -1;
		long gameTimeAtStart = level.getGameTime();
		int animMin = Integer.MAX_VALUE;
		int animMax = Integer.MIN_VALUE;
		int animChanges = 0;
		int lastAnimStage = Anim.breakingStage(builder);
		int workMin = Integer.MAX_VALUE;
		int workMax = Integer.MIN_VALUE;
		int workChanges = 0;
		int lastWorkStage = builder.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty()).stage();
		for (; guard < MAX_PLAN_SUPPLY_CALLS && JobUtil.count(box, stack -> stack.is(requestedLog)) == 0; guard++) {
			if (firstCallNow < 0) firstCallNow = now;
			lastCallNow = now;
			// Once real drops have reached the villager inventory, move to the adjacent depot stand;
			// felling and the item handoff remain fully driven by Builder/Woodcutting.
			if (JobUtil.count(builder.getInventory(), stack -> stack.is(requestedLog)) > 0) {
				BlockPos chest = ref.chestPos(level);
				BlockPos deliveryStand = JobUtil.sharedStandSpot(context(level, ref, builder, now), chest, 4.0);
				require(deliveryStand != null, "the wood-supply fixture resolves a free shared delivery stand");
				builder.setPos(deliveryStand.getX() + 0.5, deliveryStand.getY(), deliveryStand.getZ() + 0.5);
			}
			// Keep the initial and post-delivery bootstrap calls above/below to cover project
			// persistence. This no-AI fixture does not simulate the full route back from the
			// construction stand, so exercise the same production shortage dispatcher that
			// Builder invokes there and let its real woodcutting/inventory path run.
			boolean handled = Builder.supplyMissing(context(level, ref, builder, now));
			if (handled) dispatchTrue++; else dispatchFalse++;
			var indexedOwner = level.getEntity(owner);
			if (indexedOwner == builder) ownerEntitySame++;
			else if (indexedOwner == null) ownerEntityMissing++;
			else ownerEntityOther++;
			int animStage = Anim.breakingStage(builder);
			animMin = Math.min(animMin, animStage);
			animMax = Math.max(animMax, animStage);
			if (animStage != lastAnimStage) animChanges++;
			lastAnimStage = animStage;
			WoodcutterWork work = builder.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
			workMin = Math.min(workMin, work.stage());
			workMax = Math.max(workMax, work.stage());
			if (work.stage() != lastWorkStage) workChanges++;
			lastWorkStage = work.stage();
			if (guard == 0) require(work.root().filter(treeRoot::equals).isPresent(),
				"the active plan's wood supplier selects its exact natural tree rather than another species");
			now += 6;
		}
		int chestLogs = JobUtil.count(box, stack -> stack.is(requestedLog));
		WoodcutterWork finalWork = builder.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
		require(chestLogs > 0 && level.getBlockState(treeRoot.above(3)).isAir(),
			"the owning plan's real work cycle fells the requested tree and delivers its drops into the communal chest"
				+ " [calls=" + guard + ", plan=" + data.planType + ", step=" + data.planStep + ", wanted=" + data.wanted
				+ ", activity=" + state.activity + ", woodCheckAt=" + state.woodCheckAt
				+ ", target=" + (state.target == null ? "none" : state.target.toShortString())
				+ ", owner=" + data.planBuilder().map(Object::toString).orElse("none")
				+ ", requestedLog=" + logId
				+ ", workerPos=" + builder.position() + ", treeDist=" + builder.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(treeRoot))
				+ ", animStage=" + Anim.breakingStage(builder)
				+ ", workRoot=" + finalWork.root().map(BlockPos::toShortString).orElse("none")
				+ ", workLog=" + finalWork.log().map(BlockPos::toShortString).orElse("none")
				+ ", workSpecies=" + finalWork.species() + ", stage=" + finalWork.stage()
				+ ", pending=" + finalWork.removalPending() + "/" + finalWork.removalConfirmed()
				+ ", carriedLogs=" + JobUtil.count(builder.getInventory(), stack -> stack.is(requestedLog))
				+ ", chestLogs=" + chestLogs + ", treeRoot=" + level.getBlockState(treeRoot)
				+ ", treeTop=" + level.getBlockState(treeRoot.above(3))
				+ ", dispatchTrace=" + de.villagerplus.dev.G02SupplyTrace.diagnostics(builder)
				+ ", dispatchReturn=" + dispatchTrue + "/true," + dispatchFalse + "/false"
				+ ", indexedOwner=" + ownerEntitySame + "/same," + ownerEntityMissing + "/missing," + ownerEntityOther + "/other"
				+ ", now=" + firstCallNow + ".." + lastCallNow + "->" + now + ", gameTime=" + gameTimeAtStart + ".." + level.getGameTime()
				+ ", animStages=" + animMin + ".." + animMax + "/changes=" + animChanges
				+ ", animDiagnostic=" + Anim.breakingDiagnostic(builder)
				+ ", workStages=" + workMin + ".." + workMax + "/changes=" + workChanges + "]");
		String planType = data.planType;
		java.util.Optional<BlockPos> planOrigin = data.planOrigin;
		Builder.bootstrapBuildWork(context(level, ref, builder, now));
		require(data.planType.equals(planType) && data.planOrigin.equals(planOrigin)
			&& data.planStep >= cursor && data.planBuilder().filter(owner::equals).isPresent(),
			"after delivery the same plan, owner, and nonrewound cursor resume through the builder dispatch");
		VillageData restored = VillageData.CODEC.parse(JsonOps.INSTANCE,
			VillageData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow()).getOrThrow();
		require(restored.planType.equals(data.planType) && restored.planOrigin.equals(data.planOrigin)
			&& restored.planStep == data.planStep && restored.planBuilder().filter(owner::equals).isPresent(),
			"the same plan owner and cursor survive a saved-data round trip during wood supply");
		return 9;
	}

	private static boolean outside(BoundingBox bounds, BlockPos pos) {
		return pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
			|| pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ();
	}

	private static void prepareFloor(ServerLevel level, BlockPos origin) {
		for (int dx = -16; dx <= 16; dx++) for (int dz = -12; dz <= 12; dz++) {
			BlockPos ground = origin.offset(dx, 0, dz);
			level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), WORLD_FLAGS);
			for (int dy = 1; dy <= 8; dy++) level.setBlock(ground.above(dy), Blocks.AIR.defaultBlockState(), WORLD_FLAGS);
		}
	}

	private static void makeTree(ServerLevel level, BlockPos root, Block log, Block leaves) {
		level.setBlock(root.below(), Blocks.GRASS_BLOCK.defaultBlockState(), WORLD_FLAGS);
		for (int dy = 0; dy < 4; dy++) level.setBlock(root.above(dy), log.defaultBlockState(), WORLD_FLAGS);
		for (int dy = 3; dy <= 5; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			if (dx == 0 && dz == 0 && dy <= 3) continue;
			if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 4) <= 5)
				level.setBlock(root.offset(dx, dy, dz), leaves.defaultBlockState(), WORLD_FLAGS);
		}
	}

	private static Villager spawnWorker(ServerLevel level, BlockPos pos, List<Villager> workers) {
		Villager worker = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		require(worker != null, "woodcut worker spawns in the fixture patch");
		worker.setNoAi(true);
		worker.setInvulnerable(true);
		worker.setPersistenceRequired();
		worker.addTag("vp090_woodcut_group");
		workers.add(worker);
		return worker;
	}

	private static long expectedDrops(ServerLevel level, Villager worker, BlockPos root, Item item) {
		long count = 0;
		for (int dy = 0; dy < 4; dy++) {
			BlockPos pos = root.above(dy);
			for (ItemStack drop : Block.getDrops(level.getBlockState(pos), level, pos, null, worker, new ItemStack(Items.IRON_AXE)))
				if (drop.is(item)) count += drop.getCount();
		}
		return count;
	}

	private static long driveTree(ServerLevel level, VillageRef ref, Villager worker, long expectedLogs, BlockPos root, Block sapling) {
		return driveTree(level, ref, worker, expectedLogs, root, sapling, level.getGameTime() + 1_000);
	}

	private static long driveTree(ServerLevel level, VillageRef ref, Villager worker, long expectedLogs, BlockPos root, Block sapling, long now) {
		JobState state = JobManager.state(worker);
		state.cooldownUntil = now + 24_000;
		for (int call = 0; call < MAX_CALLS_PER_TREE; call++, now += 6) {
			Woodcutting.tick(context(level, ref, worker, now));
			if (level.getBlockState(root).is(sapling)
				&& count(worker, sapling == Blocks.OAK_SAPLING ? Items.OAK_SAPLING : Items.SPRUCE_SAPLING) == 0
				&& count(worker, sapling == Blocks.OAK_SAPLING ? Items.OAK_LOG : Items.SPRUCE_LOG) == expectedLogs) return now;
		}
		throw new IllegalStateException("Woodcutting group: live tree cycle exceeded its bounded call budget");
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager worker, long now) {
		VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
		return new Ctx(level, worker, JobManager.state(worker), memory, ref, now);
	}

	private static int count(Villager worker, Item item) {
		return (int) JobUtil.count(worker.getInventory(), stack -> stack.is(item));
	}

	private static Map<BlockPos, Cell> capture(ServerLevel level, BlockPos origin) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-16, -1, -12), origin.offset(16, 9, 12))) {
			BlockPos pos = p.immutable();
			level.getChunkAt(pos);
			BlockEntity blockEntity = level.getBlockEntity(pos);
			before.put(pos, new Cell(level.getBlockState(pos), blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess())));
		}
		return before;
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.keySet().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), WORLD_FLAGS));
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), WORLD_FLAGS));
		before.forEach((pos, cell) -> {
			if (cell.data() == null) return;
			BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
			if (restored != null) level.setBlockEntity(restored);
		});
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Woodcutting group: " + message);
	}

	private record Cell(BlockState state, CompoundTag data) { }
}
