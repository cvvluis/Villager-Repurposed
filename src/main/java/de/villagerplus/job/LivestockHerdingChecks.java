package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Synthetic server check for a bounded route to a real pen gate and safe plan interruption. */
public final class LivestockHerdingChecks {
	private static final int MAX_DEFERRED_FEED_TICKS = 160;
	private static final Map<ServerLevel, DeferredFeedFixture> DEFERRED_FEEDS = new WeakHashMap<>();

	static {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				DeferredFeedFixture fixture = DEFERRED_FEEDS.remove(level);
				if (fixture != null) cleanupFixture(fixture);
			}
		});
	}

	private LivestockHerdingChecks() { }

	private record BlockSnapshot(BlockState state, CompoundTag blockEntity) { }

	private static final class DeferredFeedFixture {
		final ServerLevel level;
		VillageRef ref;
		final BlockPos pen;
		final BlockPos gate;
		final BlockPos granary;
		final Map<BlockPos, BlockSnapshot> before;
		final Map<java.util.UUID, GlobalPos> suppressedMeetings;
		final List<Entity> spawned;
		final Optional<BlockPos> oldPen;
		final long oldNextCare;
		final List<de.villagerplus.village.ZonePolicy.Zone> oldZones;
		final Map<String, BlockPos> oldSpecials;
		final List<ItemStack> oldStock;
		final Container originalCommunal;
		final Villager worker;
		final Animal cow;
		final Animal secondCow;
		final Animal competingSheep;
		final int checksBeforeFeed;
		final ChunkPos entityChunk;
		final boolean ownsEntityTickTicket;
		final long deferredAt;
		int retryTicks;

		DeferredFeedFixture(ServerLevel level, VillageRef ref, BlockPos pen, BlockPos gate, BlockPos granary,
			Map<BlockPos, BlockSnapshot> before, Map<java.util.UUID, GlobalPos> suppressedMeetings,
			List<Entity> spawned, Optional<BlockPos> oldPen, long oldNextCare,
			List<de.villagerplus.village.ZonePolicy.Zone> oldZones, Map<String, BlockPos> oldSpecials, List<ItemStack> oldStock,
			Container originalCommunal, Villager worker, Animal cow, Animal secondCow,
			Animal competingSheep, int checksBeforeFeed, ChunkPos entityChunk, boolean ownsEntityTickTicket) {
			this.level = level;
			this.ref = ref;
			this.pen = pen.immutable();
			this.gate = gate.immutable();
			this.granary = granary.immutable();
			this.before = before;
			this.suppressedMeetings = suppressedMeetings;
			this.spawned = spawned;
			this.oldPen = oldPen;
			this.oldNextCare = oldNextCare;
			this.oldZones = oldZones;
			this.oldSpecials = oldSpecials;
			this.oldStock = List.copyOf(oldStock);
			this.originalCommunal = originalCommunal;
			this.worker = worker;
			this.cow = cow;
			this.secondCow = secondCow;
			this.competingSheep = competingSheep;
			this.checksBeforeFeed = checksBeforeFeed;
			this.entityChunk = entityChunk;
			this.ownsEntityTickTicket = ownsEntityTickTicket;
			this.deferredAt = level.getGameTime();
		}
	}

	public static int run(ServerLevel level, VillageRef ref) {
		DeferredFeedFixture pending = DEFERRED_FEEDS.get(level);
		if (pending != null) return resumeDeferredFeed(level, ref, pending);

		// Keep the staged floor outside the village chest search area and fit the pen plus annex
		// inside one entity-ticking chunk for the live entity queries in the follow-up checks.
		BlockPos pen = ref.bell().offset(34, 0, 30);
		BlockPos gate = pen.north(4);
		BlockPos inside = gate.south();
		// Keep the idle fixture worker beside the entrance, rather than shoulder-to-shoulder with
		// the incoming herd where its body would block the real pathfinder collision check.
		BlockPos herderStand = pen.offset(3, 0, -5);
		Map<BlockPos, BlockSnapshot> before = new LinkedHashMap<>();
		Map<java.util.UUID, GlobalPos> suppressedSelfBellMeetings = new LinkedHashMap<>();
		List<Entity> spawned = new ArrayList<>();
		Optional<BlockPos> oldPen = ref.data().extra.pen;
		long oldNextCare = ref.data().extra.nextCare;
		List<de.villagerplus.village.ZonePolicy.Zone> oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		Map<String, BlockPos> oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		Container communal = ref.chest(level);
		if (communal == null) throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: village has no communal feed container");
		List<ItemStack> oldStock = new ArrayList<>();
		for (int slot = 0; slot < communal.getContainerSize(); slot++) oldStock.add(communal.getItem(slot).copy());
		Villager worker = null;
		Animal cow = null;
		boolean preserveForRetry = false;
		ChunkPos entityTickChunk = null;
		boolean ownsEntityTickTicket = false;
		int checks = 0;
		SimpleContainer penRetryStock = new SimpleContainer(4);
		penRetryStock.setItem(0, new ItemStack(Items.BREAD, 32));
		String foodOnlyStock = FarmerJob.initialPenStockSignature(penRetryStock);
		String noHerdReady = FarmerJob.initialPenReadinessMarker(true, false, false,
			1, Builder.MIN_INITIAL_PEN_POPULATION, false, true, 2, foodOnlyStock);
		String preFieldHerdReady = FarmerJob.initialPenReadinessMarker(true, false, false,
			0, Builder.MIN_INITIAL_PEN_POPULATION, true, true, 2, foodOnlyStock);
		String eligibleReady = FarmerJob.initialPenReadinessMarker(true, false, false,
			1, Builder.MIN_INITIAL_PEN_POPULATION, true, true, 2, foodOnlyStock);
		// Current rule (see shouldStartInitialPen / FarmerExpansionChecks): either a completed field or a real herd unlocks
		// the first pen; neither of them, or missing public storage, keeps it blocked.
		String neitherFieldNorHerd = FarmerJob.initialPenReadinessMarker(true, false, false,
			0, Builder.MIN_INITIAL_PEN_POPULATION, false, true, 2, foodOnlyStock);
		require(noHerdReady != null && preFieldHerdReady != null && neitherFieldNorHerd == null && eligibleReady != null
			&& FarmerJob.shouldWakeInitialPenRetry(null, eligibleReady)
			&& !FarmerJob.shouldWakeInitialPenRetry(eligibleReady, eligibleReady),
			"a completed starter field or a real eligible herd arms the first paid pen without waiting for a food reserve"); checks++;
		penRetryStock.setItem(1, new ItemStack(Items.OAK_FENCE, 24));
		String fundedPenReady = FarmerJob.initialPenReadinessMarker(true, false, false,
			1, Builder.MIN_INITIAL_PEN_POPULATION, true, true, 2,
			FarmerJob.initialPenStockSignature(penRetryStock));
		require(!foodOnlyStock.equals(FarmerJob.initialPenStockSignature(penRetryStock))
			&& FarmerJob.shouldWakeInitialPenRetry(eligibleReady, fundedPenReady)
			&& FarmerJob.initialPenReadinessMarker(true, false, true, 1,
				Builder.MIN_INITIAL_PEN_POPULATION, true, true, 2, "funded") == null
			&& FarmerJob.initialPenReadinessMarker(true, true, false, 1,
				Builder.MIN_INITIAL_PEN_POPULATION, true, true, 2, "funded") == null,
			"public pen materials wake a throttled retry while saved field or pen projects prevent duplicate plans"); checks++;
		try {
			stagePen(level, before, pen, gate);
			ref.data().extra.pen = Optional.empty();
			ref.data().extra.nextCare = 0;
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of());
			ref.dirty();

			worker = EntityTypes.VILLAGER.spawn(level, herderStand, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: worker fixture did not spawn");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			spawned.add(worker);
			de.villagerplus.village.Villages.bindResident(level, ref, worker, null);
			require(Livestock.canPlanInitialPen(level, ref, worker),
				"the first paid, enclosed pen may be planned before any livestock is available"); checks++;
			ref.data().extra.pen = Optional.of(pen);
			ref.dirty();

			BlockPos expansionCell = pen.south(5).east(2);
			worker.setPos(expansionCell.getX() + 0.5, expansionCell.getY(), expansionCell.getZ() + 0.5);
			require(PenExpansion.entityOccupiesFenceCell(expansionCell, worker),
				"the spawned villager's bounding box occupies the intended expansion cell even before a spatial re-query"); checks++;
			worker.setPos(herderStand.getX() + 0.5, herderStand.getY(), herderStand.getZ() + 0.5);
			BlockPos gateBlocker = expansionCell.south();
			BlockState gateBlockerBefore = level.getBlockState(gateBlocker);
			level.setBlock(gateBlocker, Blocks.OAK_FENCE_GATE.defaultBlockState(), 3);
			require(PenExpansion.plan(level, pen).isEmpty()
				&& level.getBlockState(gateBlocker).getBlock() instanceof FenceGateBlock
				&& level.getBlockState(expansionCell).isAir(),
				"an existing gate blocks the entire expansion row without being overwritten or leaving a partial fence"); checks++;
			level.setBlock(gateBlocker, gateBlockerBefore, 3);
			require(PenExpansion.plan(level, pen).size() == 12
				&& PenExpansion.capacity(level, pen, List.of()) == Livestock.PEN_CAPACITY_PER_SPECIES,
				"the 12 real fence cells remain pending and the base capacity stays active until the gate closes the annex"); checks++;

			BlockPos reachablePen = gate.north(2);
			cow = EntityTypes.COW.spawn(level, reachablePen, EntitySpawnReason.COMMAND);
			if (cow == null) throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: cow fixture did not spawn");
			cow.setInvulnerable(true);
			cow.setAge(0);
			spawned.add(cow);
			cow.move(MoverType.SELF, new Vec3(0.0, -0.1, 0.0)); // Resolve the staged floor before vanilla pathfinding.
			require(!Livestock.hasReachableInitialHerd(level, ref, worker),
				"one reachable adult is not reported as a complete initial breeding pair"); checks++;
			Animal secondCow = EntityTypes.COW.spawn(level, reachablePen.east(4), EntitySpawnReason.COMMAND);
			if (secondCow == null) throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: second cow fixture did not spawn");
			secondCow.setInvulnerable(true);
			secondCow.setAge(0);
			spawned.add(secondCow);
			secondCow.move(MoverType.SELF, new Vec3(0.0, -0.1, 0.0));
			require(Livestock.hasReachableInitialHerd(level, ref, worker),
				"two real adult cattle with a bounded path to the farmer form a reachable initial pair"); checks++;

			boolean villageKnownAtPen = de.villagerplus.village.Villages.knownBellsNear(level, pen,
				de.villagerplus.Config.VILLAGE_RADIUS + 24).contains(ref.bell());
			boolean villageKnownAtCow = de.villagerplus.village.Villages.knownBellsNear(level, reachablePen,
				de.villagerplus.Config.VILLAGE_RADIUS).contains(ref.bell());
			require(villageKnownAtPen && villageKnownAtCow,
				"fixture worker binds both the pen and its outside herd to the same registered village"); checks++;
			BlockState closedGate = level.getBlockState(gate);
			level.setBlock(gate, closedGate.setValue(FenceGateBlock.OPEN, true), 3);
			Path reachableCowPath = cow.getNavigation().createPath(inside, 0);
			Path gateCellPath = cow.getNavigation().createPath(gate, 0);
			Path outsidePath = cow.getNavigation().createPath(gate.north(), 0);
			Path selfPath = cow.getNavigation().createPath(cow.blockPosition(), 0);
			String routeTypes = describeRouteTypes(level, cow, gate, inside);
			String routeSweep = describeRouteSweep(level, cow, inside);
			BlockPos gateLeft = gate.west();
			BlockPos gateRight = gate.east();
			BlockState gateLeftState = level.getBlockState(gateLeft);
			BlockState gateRightState = level.getBlockState(gateRight);
			Path wideOpeningPath;
			String wideRouteTypes;
			String wideRouteSweep;
			try {
				level.setBlock(gateLeft, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(gateRight, Blocks.AIR.defaultBlockState(), 3);
				wideOpeningPath = cow.getNavigation().createPath(inside, 0);
				wideRouteTypes = describeRouteTypes(level, cow, gate, inside);
				wideRouteSweep = describeRouteSweep(level, cow, inside);
			} finally {
				level.setBlock(gateLeft, gateLeftState, 3);
				level.setBlock(gateRight, gateRightState, 3);
			}
			BlockState openGateState = level.getBlockState(gate);
			int gateDropsBefore = level.getEntitiesOfClass(ItemEntity.class, new AABB(gate).inflate(2)).size();
			level.setBlock(gate, Blocks.AIR.defaultBlockState(), 3);
			String airRouteTypes;
			Path nakedAirPath;
			try {
				airRouteTypes = describeRouteTypes(level, cow, gate, inside);
				cow.getNavigation().stop();
				nakedAirPath = cow.getNavigation().createPath(inside, 0);
			} finally {
				level.setBlock(gate, openGateState, 3);
			}
			String nakedAirPathDescription = describePath(nakedAirPath);
			Path gateAwarePath = Livestock.createPathThroughGate(level, cow, gate, inside);
			BlockState restoredGateState = level.getBlockState(gate);
			BlockState restoredGateLeftState = level.getBlockState(gateLeft);
			BlockState restoredGateRightState = level.getBlockState(gateRight);
			int gateDropsAfter = level.getEntitiesOfClass(ItemEntity.class, new AABB(gate).inflate(2)).size();
			PathType openGatePathType = pathTypeAt(level, cow, gate);
			require(openGatePathType == PathType.FENCE
				&& !Livestock.isBoundedGatePath(reachableCowPath, gate, inside)
				&& !Livestock.isBoundedGatePath(wideOpeningPath, gate, inside)
				&& Livestock.isBoundedGatePath(gateAwarePath, gate, inside)
				&& restoredGateState.equals(openGateState) && restoredGateLeftState.equals(gateLeftState)
				&& restoredGateRightState.equals(gateRightState) && gateDropsAfter == gateDropsBefore,
				"vanilla A* cannot cross the open FENCE-classified gate even with side posts removed; the per-animal route reaches the pen while preserving every exact passage state and drop count [airTypes="
					+ airRouteTypes + ", nakedAirPath=" + nakedAirPathDescription + ", path="
					+ describePath(gateAwarePath) + ", gateBefore=" + openGateState + ", gateAfter=" + restoredGateState
					+ ", sideStates=" + gateLeftState + "/" + gateRightState + "->"
					+ restoredGateLeftState + "/" + restoredGateRightState
					+ ", itemDrops=" + gateDropsBefore + "->" + gateDropsAfter + "]"); checks++;
			boolean reachableCowCanPath = reachableCowPath != null && reachableCowPath.canReach()
				&& reachableCowPath.getNodeCount() > 0;
			int reachableCowPathNodes = reachableCowPath == null ? 0 : reachableCowPath.getNodeCount();
			String reachableCowNodes = describePath(reachableCowPath);
			String gateCellNodes = describePath(gateCellPath);
			String wideOpeningNodes = describePath(wideOpeningPath);
			String outsideNodes = describePath(outsidePath);
			String selfNodes = describePath(selfPath);
			boolean outsidePathExists = outsidePath != null && outsidePath.getNodeCount() > 0;
			boolean selfPathExists = selfPath != null && selfPath.getNodeCount() > 0;
			BlockState openGate = level.getBlockState(gate);
			boolean gatePathable = openGate.isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND);
			boolean gateCollisionEmpty = openGate.getCollisionShape(level, gate).isEmpty();
			BlockState insideFloor = level.getBlockState(inside.below());
			BlockState insideFeet = level.getBlockState(inside);
			BlockState insideHead = level.getBlockState(inside.above());
			boolean cowNoAi = cow.isNoAi();
			boolean cowGrounded = cow.onGround();
			BlockState cowFloor = level.getBlockState(cow.blockPosition().below());
			omitSelfBellMeetingPoints(level, ref.bell(), suppressedSelfBellMeetings);
			List<BlockPos> indexedAtPenWithoutSelf = de.villagerplus.village.Villages.knownBellsNear(level, pen,
				de.villagerplus.Config.VILLAGE_RADIUS + 24);
			List<BlockPos> indexedAtCowWithoutSelf = de.villagerplus.village.Villages.knownBellsNear(level,
				cow.blockPosition(), de.villagerplus.Config.VILLAGE_RADIUS);
			boolean selfBellFoundByFallback = (indexedAtPenWithoutSelf.contains(ref.bell())
				|| indexedAtCowWithoutSelf.contains(ref.bell())) && cow.isAlive() && secondCow.isAlive();
			require(selfBellFoundByFallback,
				"loaded residents without a meeting point still resolve to their nearest village bell for the animal index [penIndex="
					+ indexedAtPenWithoutSelf + ", cowIndex=" + indexedAtCowWithoutSelf
					+ ", cow=" + cow.blockPosition() + ", secondCow=" + secondCow.blockPosition() + "]"); checks++;
			level.setBlock(gate, closedGate, 3);
			Ctx context = context(level, ref, worker, level.getGameTime() + 1L);
			boolean started = Livestock.tick(context);
			BlockState opened = level.getBlockState(gate);
			boolean gateOpen = opened.getBlock() instanceof FenceGateBlock && opened.getValue(FenceGateBlock.OPEN);
			require(selfBellFoundByFallback && started && gateOpen
				&& worker.entityTags().stream().anyMatch(tag -> tag.startsWith("vp_livestock_herd_plan:"))
				&& cow.entityTags().stream().anyMatch(tag -> tag.startsWith("vp_livestock_gate_recovery:")),
				"normal farmer work starts a persistent real-animal route through the 9-by-9 pen after nearest-bell fallback discovery [started=" + started
					+ ", gate=" + opened + ", bellAtPen=" + villageKnownAtPen + ", bellAtCow=" + villageKnownAtCow
					+ ", selfBellFound=" + selfBellFoundByFallback
					+ ", cowCanPath=" + reachableCowCanPath + ", pathNodes=" + reachableCowPathNodes
					+ ", cowPath=" + reachableCowNodes + ", gatePath=" + gateCellNodes
					+ ", wideOpeningPath=" + wideOpeningNodes
					+ ", routeTypes=" + routeTypes + ", routeSweep=" + routeSweep
					+ ", wideRouteTypes=" + wideRouteTypes + ", wideRouteSweep=" + wideRouteSweep
					+ ", outsidePath=" + outsideNodes + ", selfPath=" + selfNodes
				+ ", gateLandPathable=" + gatePathable + ", gateCollisionEmpty=" + gateCollisionEmpty
					+ ", gatePathType=" + openGatePathType
					+ ", inside=" + insideFeet + ", insideFloor=" + insideFloor + ", insideHead=" + insideHead
					+ ", outsidePath=" + outsidePathExists + ", selfPath=" + selfPathExists + ", noAi=" + cowNoAi
					+ ", grounded=" + cowGrounded + ", floor=" + cowFloor
					+ ", workerTags=" + worker.entityTags() + ", cow=" + cow.blockPosition() + "]"); checks++;

			tickHerdingPlans(level);
			Path path = cow.getNavigation().getPath();
			require(cow.getNavigation().isInProgress() && Livestock.isBoundedGatePath(path, gate, inside),
				"the nearby cow receives a reachable, bounded route through the gate to the inner pen cell"); checks++;
			require(!Livestock.isBoundedHerdingPath(path, gate),
				"the herd route cannot accept the outside gate as its destination"); checks++;
			require(level.getBlockState(gate).getValue(FenceGateBlock.OPEN)
				&& worker.entityTags().stream().anyMatch(tag -> tag.startsWith("vp_livestock_herd_plan:"))
				&& cow.entityTags().stream().anyMatch(tag -> tag.startsWith("vp_livestock_gate_recovery:")),
				"the owned gate and recovery markers remain active while the cow follows its route"); checks++;
			List<BlockPos> passagePositions = List.of(gateLeft, gateRight, gate);
			List<BlockState> originalPassage = List.of(gateLeftState, gateRightState, closedGate);
			require(hasExactPassageRecovery(worker, passagePositions, originalPassage)
				&& hasExactPassageRecovery(cow, passagePositions, originalPassage),
				"both entities persist all three exact original passage states before the route can be interrupted"); checks++;
			Path activePath = cow.getNavigation().getPath();
			Path repeatRoute = Livestock.createPathThroughGate(level, cow, gate, inside);
			int routeDropsAfter = level.getEntitiesOfClass(ItemEntity.class, new AABB(gate).inflate(2)).size();
		boolean repeatRouteBounded = Livestock.isBoundedGatePath(repeatRoute, gate, inside);
		require(repeatRouteBounded,
			"a repeated per-animal route check returns a bounded route through the gate [route="
				+ describePath(repeatRoute) + "]"); checks++;
		boolean activeNavigationRetained = activePath != null && cow.getNavigation().getPath() == activePath;
		require(activeNavigationRetained,
			"building a repeat route leaves the cow's active navigation object intact [same="
				+ (cow.getNavigation().getPath() == activePath) + ", before=" + describePath(activePath)
				+ ", after=" + describePath(cow.getNavigation().getPath()) + "]"); checks++;
		boolean passageBlocksIntact = level.getBlockState(gate).equals(opened)
			&& level.getBlockState(gateLeft).equals(gateLeftState)
			&& level.getBlockState(gateRight).equals(gateRightState);
		require(passageBlocksIntact,
			"a repeated route check preserves the open gate and both exact side blocks [gate="
				+ level.getBlockState(gate) + ", left=" + level.getBlockState(gateLeft)
				+ ", right=" + level.getBlockState(gateRight) + "]"); checks++;
		boolean recoverySnapshotsIntact = hasExactPassageRecovery(worker, passagePositions, originalPassage)
			&& hasExactPassageRecovery(cow, passagePositions, originalPassage);
		require(recoverySnapshotsIntact,
			"a repeated route check preserves exact passage recovery snapshots [worker="
				+ hasExactPassageRecovery(worker, passagePositions, originalPassage) + ", cow="
				+ hasExactPassageRecovery(cow, passagePositions, originalPassage) + "]"); checks++;
		require(routeDropsAfter == gateDropsAfter,
			"a repeated route check does not drop passage blocks [drops=" + gateDropsAfter + "->" + routeDropsAfter + "]"); checks++;

			boolean navigatedThroughRestoredPassage = false;
			for (int tick = 0; tick < 400 && !navigatedThroughRestoredPassage; tick++) {
				cow.tick();
				// This is the same order as the real server: entity AI/navigation first,
				// followed by Livestock's registered END_LEVEL_TICK plan maintenance.
				tickHerdingPlans(level);
				navigatedThroughRestoredPassage = inside.equals(cow.blockPosition());
			}
			BlockState gateAfterCrossing = level.getBlockState(gate);
			boolean gateSafeAfterCrossing = gateAfterCrossing.equals(opened) || gateAfterCrossing.equals(closedGate);
			require(navigatedThroughRestoredPassage && gateSafeAfterCrossing
				&& level.getBlockState(gateLeft).equals(gateLeftState)
				&& level.getBlockState(gateRight).equals(gateRightState),
				"the cow physically crosses into the pen on the generated route with every original passage block restored [cow="
					+ cow.position() + ", gate=" + gateAfterCrossing + ", sides="
					+ level.getBlockState(gateLeft) + "/" + level.getBlockState(gateRight)
					+ ", initialPath=" + describePath(path) + ", finalPath=" + describePath(cow.getNavigation().getPath()) + "]"); checks++;

			cow.setPos(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
			tickHerdingPlans(level);
			require(!level.getBlockState(gate).getValue(FenceGateBlock.OPEN)
				&& hasNoHerdingMarkers(worker) && hasNoHerdingMarkers(cow),
				"normal pen arrival ends the route, closes its gate, and removes recovery markers"); checks++;
			restoreMeetingPoints(level, suppressedSelfBellMeetings);
			suppressedSelfBellMeetings.clear();

			cow.setPos(reachablePen.getX() + 0.5, reachablePen.getY(), reachablePen.getZ() + 0.5);
			ref.data().extra.nextCare = 0;
			boolean restartPlanStarted = Livestock.tick(context(level, ref, worker, level.getGameTime() + 2L));
			require(restartPlanStarted && level.getBlockState(gate).getValue(FenceGateBlock.OPEN)
				&& worker.entityTags().stream().anyMatch(tag -> tag.startsWith("vp_livestock_herd_plan:"))
				&& cow.entityTags().stream().anyMatch(tag -> tag.startsWith("vp_livestock_gate_recovery:")),
				"a second bounded herd route persists its restart markers before traversing the temporary opening"); checks++;
			level.setBlock(gateLeft, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(gateRight, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(gate, Blocks.AIR.defaultBlockState(), 3);
			forgetVolatilePlans(level);
			recoverEntityPlan(worker, level);
			require(level.getBlockState(gate).equals(closedGate)
				&& level.getBlockState(gateLeft).equals(gateLeftState)
				&& level.getBlockState(gateRight).equals(gateRightState)
				&& hasNoHerdingMarkers(worker) && hasNoHerdingMarkers(cow),
				"restart recovery restores all three missing passage blocks from saved states and clears both entities' markers"); checks++;

			ref.data().extra.nextCare = 0;
			boolean abortPlanStarted = Livestock.tick(context(level, ref, worker, level.getGameTime() + 3L));
			require(abortPlanStarted && level.getBlockState(gate).getValue(FenceGateBlock.OPEN),
				"a new transfer can start after restart recovery"); checks++;
			onEntityUnload(level, cow);
			require(level.getBlockState(gate).equals(closedGate)
				&& level.getBlockState(gateLeft).equals(gateLeftState)
				&& level.getBlockState(gateRight).equals(gateRightState)
				&& hasNoHerdingMarkers(worker) && hasNoHerdingMarkers(cow),
				"entity-unload abort restores all three exact passage states and clears persisted route markers"); checks++;

			// Recruitment leaves the gate closed and the farmer outside. Verify that this layout
			// can still complete the real feed transaction when cattle are away from the entrance.
			worker.setPos(pen.getX() + 0.5, pen.getY(), pen.getZ() - 4.5);
			cow.teleportTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5);
			secondCow.teleportTo(pen.getX() + 1.5, pen.getY(), pen.getZ() + 0.5);
			cow.setAge(0);
			secondCow.setAge(0);
			cow.resetLove();
			secondCow.resetLove();
			// This real, unpaired sheep is an eligible intake candidate. With the old
			// herding-before-feeding order it could claim the farmer's tick before the ready cow pair.
			Animal competingSheep = EntityTypes.SHEEP.spawn(level, pen.south(9), EntitySpawnReason.COMMAND);
			if (competingSheep == null) throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: competing sheep fixture did not spawn");
			competingSheep.setInvulnerable(true);
			competingSheep.setAge(0);
			spawned.add(competingSheep);
			competingSheep.move(MoverType.SELF, new Vec3(0.0, -0.1, 0.0));
			BlockPos granary = pen.east(12);
			set(level, before, granary.below(), Blocks.STONE.defaultBlockState());
			set(level, before, granary, Blocks.CHEST.defaultBlockState());
			Specials.set(ref, Blueprints.GRANARY, granary);
			if (Lager.publicContainerAt(level, granary) == null) {
				throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: registered public granary fixture chest was not created");
			}
			entityTickChunk = new ChunkPos(pen.getX() >> 4, pen.getZ() >> 4);
			boolean alreadyForced = level.getChunkSource().getForceLoadedChunks().contains(entityTickChunk.pack());
			if (!alreadyForced) {
				if (!level.setChunkForced(entityTickChunk.x(), entityTickChunk.z(), true)) {
					throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: could not keep the pen entity-ticking for its deferred feed assertion");
				}
				ownsEntityTickTicket = true;
			}
			level.getChunkAt(pen);
			level.waitForEntities(entityTickChunk, 0);
			DeferredFeedFixture deferred = new DeferredFeedFixture(level, ref, pen, gate, granary, before,
				suppressedSelfBellMeetings, spawned, oldPen, oldNextCare, oldZones, oldSpecials, oldStock, communal,
				worker, cow, secondCow, competingSheep, checks, entityTickChunk, ownsEntityTickTicket);
			DEFERRED_FEEDS.put(level, deferred);
			preserveForRetry = true;
			return -1;
		} finally {
			if (!preserveForRetry) {
				cleanupFixture(level, ref, gate, cow, worker, spawned, suppressedSelfBellMeetings,
					oldPen, oldNextCare, oldZones, oldSpecials, communal, oldStock, before, entityTickChunk, ownsEntityTickTicket);
			}
		}
	}

	private static int resumeDeferredFeed(ServerLevel level, VillageRef ref, DeferredFeedFixture fixture) {
		if (!fixture.ref.bell().equals(ref.bell())) {
			DEFERRED_FEEDS.remove(level);
			cleanupFixture(fixture);
			throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: deferred feed fixture was retried for another village");
		}
		fixture.ref = ref;
		level.getChunkAt(fixture.pen);
		level.waitForEntities(fixture.entityChunk, 0);
		fixture.retryTicks++;
		AABB penArea = new AABB(fixture.pen).inflate(5, 3, 5);
		List<Animal> pennedAnimals = level.getEntitiesOfClass(Animal.class, penArea, Animal::isAlive);
		AABB herdArea = new AABB(fixture.pen).inflate(24, 4, 24);
		List<Animal> nearbyAnimals = level.getEntitiesOfClass(Animal.class, herdArea, Animal::isAlive);
		boolean animalsIndexed = pennedAnimals.contains(fixture.cow) && pennedAnimals.contains(fixture.secondCow)
			&& nearbyAnimals.contains(fixture.competingSheep);
		boolean entityTicking = level.isPositionEntityTicking(fixture.pen);
		if (!entityTicking || !animalsIndexed) {
			if (fixture.retryTicks < MAX_DEFERRED_FEED_TICKS
				&& level.getGameTime() - fixture.deferredAt < MAX_DEFERRED_FEED_TICKS) return -1;
			DEFERRED_FEEDS.remove(level);
			String diagnostic = "entity-ticking=" + entityTicking
				+ ",penAnimals=" + pennedAnimals + ",nearbyAnimals=" + nearbyAnimals
				+ ",retryTicks=" + fixture.retryTicks;
			cleanupFixture(fixture);
			throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: pen animals did not enter the world query within the bounded retry window ["
				+ diagnostic + "]");
		}

		DEFERRED_FEEDS.remove(level);
		try {
			ref.data().extra.pen = Optional.of(fixture.pen);
			ref.data().extra.nextCare = 0;
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(new de.villagerplus.village.ZonePolicy.Zone(
				"livestock-bootstrap-fixture", "pasture", fixture.pen.getX() - 3, fixture.pen.getZ() - 3,
				fixture.pen.getX() + 3, fixture.pen.getZ() + 3)));
			ref.dirty();
			// Match the production trigger's no-pen precondition without letting unrelated test-world
			// animals outside this fixture pasture satisfy the village livestock query.
			ref.data().extra.pen = Optional.empty();
			ref.dirty();
			boolean detectsRealHerd = Livestock.hasEligibleLivestockForInitialPen(level, ref);
			fixture.cow.setCustomName(net.minecraft.network.chat.Component.literal("Protected fixture cow"));
			fixture.secondCow.setCustomName(net.minecraft.network.chat.Component.literal("Protected fixture cow"));
			boolean ignoresNamedAnimals = !Livestock.hasEligibleLivestockForInitialPen(level, ref);
			fixture.cow.setCustomName(null);
			fixture.secondCow.setCustomName(null);
			ref.data().extra.pen = Optional.of(fixture.pen);
			ref.dirty();
			boolean ignoresAlreadyManagedPen = !Livestock.hasEligibleLivestockForInitialPen(level, ref);
			require(detectsRealHerd && ignoresNamedAnimals && ignoresAlreadyManagedPen,
				"initial-pen livestock detection accepts real village cattle, respects name protection and stops after a managed pen is registered"
					+ " [eligible=" + detectsRealHerd + ", namedRejected=" + ignoresNamedAnimals
					+ ", registeredPenRejected=" + ignoresAlreadyManagedPen + "]");
			Container testGranary = Lager.publicContainerAt(level, fixture.granary);
			if (testGranary == null) {
				throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: registered granary chest missing after deferred entity tick ["
					+ describeGranaries(ref, Specials.containers(level, ref, Blueprints.GRANARY)) + "]");
			}
			testGranary.clearContent();
			testGranary.setItem(0, new ItemStack(Items.WHEAT, 4));
			ItemStack writtenSlotZero = testGranary.getItem(0);
			List<Container> feedGranaries = Specials.containers(level, ref, Blueprints.GRANARY);
			int feedWheatBefore = countContainers(feedGranaries, Items.WHEAT);
			String granaryAfterFeedSet = describeGranaries(ref, feedGranaries)
				+ ",directSlot0=" + writtenSlotZero + ",fixtureContainer=" + testGranary.getClass().getName()
				+ ",materialChest=" + describeChest(level, ref, ref.chest(level));
			if (!writtenSlotZero.is(Items.WHEAT) || writtenSlotZero.getCount() != 4 || feedWheatBefore != 4) {
				throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: feed fixture write was not visible through the registered granary before Livestock.tick"
					+ " [granaryWheatAfterSet=" + feedWheatBefore + ", state=" + granaryAfterFeedSet + "]");
			}

			Ctx feedContext = context(level, ref, fixture.worker, level.getGameTime() + 4L);
			String feedStateBefore = feedState(level, fixture.pen, fixture.gate, fixture.worker,
				fixture.cow, fixture.secondCow, feedGranaries, feedContext);
			boolean fedOutsideThePen = Livestock.tick(feedContext);
			List<Container> feedGranariesAfter = Specials.containers(level, ref, Blueprints.GRANARY);
			int wheatAfter = countContainers(feedGranariesAfter, Items.WHEAT);
			String feedStateAfter = feedState(level, fixture.pen, fixture.gate, fixture.worker,
				fixture.cow, fixture.secondCow, feedGranariesAfter, feedContext);
			BlockState gateAfterFeed = level.getBlockState(fixture.gate);
			boolean gateClosedAfterFeed = gateAfterFeed.getBlock() instanceof FenceGateBlock
				&& !gateAfterFeed.getValue(FenceGateBlock.OPEN);
			require(fedOutsideThePen && fixture.cow.isInLove() && fixture.secondCow.isInLove()
				&& wheatAfter == 2
				&& gateClosedAfterFeed
				&& !new AABB(fixture.pen).inflate(3.0, 2.0, 3.0).contains(fixture.competingSheep.position())
				&& hasNoHerdingMarkers(fixture.competingSheep)
				&& hasNoHerdingMarkers(fixture.worker) && hasNoHerdingMarkers(fixture.cow) && hasNoHerdingMarkers(fixture.secondCow),
				"a ready cow pair is fed with real wheat before a nearby unpaired sheep can start herding or reopen the gate"
					+ " [granaryAfterFeedSet=" + granaryAfterFeedSet + ", before=" + feedStateBefore
					+ ", after=" + feedStateAfter + ", tickResult=" + fedOutsideThePen
					+ ", inLove=" + fixture.cow.isInLove() + "/" + fixture.secondCow.isInLove()
					+ ", gateClosed=" + gateClosedAfterFeed
					+ ", granaryWheatAfter=" + wheatAfter
					+ ", sheep=" + fixture.competingSheep.position() + "/outside="
					+ !new AABB(fixture.pen).inflate(3.0, 2.0, 3.0).contains(fixture.competingSheep.position())
					+ ", markers=" + hasNoHerdingMarkers(fixture.worker) + "/" + hasNoHerdingMarkers(fixture.cow)
					+ "/" + hasNoHerdingMarkers(fixture.secondCow) + "/" + hasNoHerdingMarkers(fixture.competingSheep) + "]");
			// Keep the real paid annex, effective-cap, damage/retry, and annex-breeding cycle in
			// the same targeted livestockherding mode rather than only in the broad role suite.
			fixture.cow.discard();
			fixture.secondCow.discard();
			fixture.competingSheep.discard();
			return fixture.checksBeforeFeed + 2 + LivestockZoneChecks.run(level, ref);
		} finally {
			cleanupFixture(fixture);
		}
	}

	private static void cleanupFixture(DeferredFeedFixture fixture) {
		cleanupFixture(fixture.level, fixture.ref, fixture.gate, fixture.cow, fixture.worker, fixture.spawned,
			fixture.suppressedMeetings, fixture.oldPen, fixture.oldNextCare, fixture.oldZones, fixture.oldSpecials,
			fixture.originalCommunal, fixture.oldStock, fixture.before, fixture.entityChunk, fixture.ownsEntityTickTicket);
	}

	private static void cleanupFixture(ServerLevel level, VillageRef ref, BlockPos gate, Animal cow, Villager worker,
		List<Entity> spawned, Map<java.util.UUID, GlobalPos> suppressedMeetings, Optional<BlockPos> oldPen,
		long oldNextCare, List<de.villagerplus.village.ZonePolicy.Zone> oldZones, Map<String, BlockPos> oldSpecials,
		Container originalCommunal,
		List<ItemStack> oldStock, Map<BlockPos, BlockSnapshot> before, ChunkPos entityChunk, boolean ownsEntityTickTicket) {
		if (cow != null && !cow.isRemoved()) {
			try {
				onEntityUnload(level, cow);
			} catch (RuntimeException ignored) {
				// Continue with unconditional gate and fixture cleanup if the check failed mid-transfer.
			}
		}
		restoreMeetingPoints(level, suppressedMeetings);
		if (level.hasChunkAt(gate) && level.getBlockState(gate).getBlock() instanceof FenceGateBlock
			&& level.getBlockState(gate).getValue(FenceGateBlock.OPEN)) {
			level.setBlock(gate, level.getBlockState(gate).setValue(FenceGateBlock.OPEN, false), 3);
		}
		for (Entity entity : spawned) {
			for (String tag : List.copyOf(entity.entityTags())) {
				if (tag.startsWith("vp_livestock_herd_plan:") || tag.startsWith("vp_livestock_gate_recovery:")) entity.removeTag(tag);
			}
			entity.discard();
		}
		ref.data().extra.pen = oldPen;
		ref.data().extra.nextCare = oldNextCare;
		Container restoreChest = ref.chest(level);
		if (restoreChest == null) restoreChest = originalCommunal;
		if (restoreChest != null) {
			for (int slot = 0; slot < Math.min(restoreChest.getContainerSize(), oldStock.size()); slot++) {
				restoreChest.setItem(slot, oldStock.get(slot).copy());
			}
		}
		if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
		else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
		if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
		else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
		ref.dirty();
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, snapshot) -> level.setBlock(pos, snapshot.state(), 3));
		before.forEach((pos, snapshot) -> {
			if (snapshot.blockEntity() == null) return;
			BlockEntity blockEntity = BlockEntity.loadStatic(pos, snapshot.state(), snapshot.blockEntity().copy(), level.registryAccess());
			if (blockEntity != null) level.setBlockEntity(blockEntity);
		});
		if (ownsEntityTickTicket && entityChunk != null) level.setChunkForced(entityChunk.x(), entityChunk.z(), false);
	}

	private static String feedState(ServerLevel level, BlockPos pen, BlockPos gate, Villager worker, Animal cow,
		Animal secondCow, List<Container> granaries, Ctx context) {
		Vec3 workerPosition = worker.position();
		Vec3 currentSafeSpot = JobUtil.safeSpot(level, Vec3.atBottomCenterOf(worker.blockPosition()));
		int publicWheat = countContainers(granaries, Items.WHEAT);
		int privateWheat = JobUtil.count(worker.getInventory(), stack -> stack.is(Items.WHEAT));
		String chest = describeChest(level, context.village(), context.village().chest(level));
		String granary = describeGranaries(context.village(), granaries);
		List<String> stands = List.of(pen.north(5), pen.south(5), pen.east(5), pen.west(5)).stream()
			.map(position -> {
				Vec3 safe = JobUtil.safeSpot(level, Vec3.atBottomCenterOf(position));
				return position + "->" + safe + ",workerD2=" + (safe == null ? "n/a" : workerPosition.distanceToSqr(safe))
					+ ",cowD2=" + (safe == null ? "n/a" : safe.distanceToSqr(cow.position()))
					+ ",secondD2=" + (safe == null ? "n/a" : safe.distanceToSqr(secondCow.position()));
			}).toList();
		AABB localArea = new AABB(pen).inflate(5, 3, 5);
		List<String> localAnimals = level.getEntitiesOfClass(Animal.class, localArea, Animal::isAlive).stream()
			.map(animal -> animal.getType() + "/" + animal.position() + "/age=" + animal.getAge() + "/love=" + animal.isInLove())
			.toList();
		AABB nearbyArea = new AABB(pen).inflate(24, 4, 24);
		List<String> nearbyHerd = level.getEntitiesOfClass(Animal.class, nearbyArea, animal -> animal.isAlive()
			&& (animal.getType() == EntityTypes.COW || animal.getType() == EntityTypes.SHEEP
				|| animal.getType() == EntityTypes.PIG || animal.getType() == EntityTypes.CHICKEN))
			.stream().map(animal -> animal.getType() + "/" + animal.blockPosition() + "/age=" + animal.getAge())
			.toList();
		return "worldTime=" + level.getGameTime() + ",now=" + context.now() + ",worker=" + workerPosition + "/" + worker.blockPosition()
			+ ",workerSafe=" + currentSafeSpot + ",workerSafeD2="
			+ (currentSafeSpot == null ? "n/a" : workerPosition.distanceToSqr(currentSafeSpot))
			+ ",noAi=" + worker.isNoAi() + ",cow=" + cow.position() + "/" + cow.blockPosition()
			+ ",cowD2=" + workerPosition.distanceToSqr(cow.position()) + ",cowAge=" + cow.getAge()
			+ ",secondCow=" + secondCow.position() + "/" + secondCow.blockPosition()
			+ ",secondCowD2=" + workerPosition.distanceToSqr(secondCow.position()) + ",secondAge=" + secondCow.getAge()
			+ ",publicGranaryWheat=" + publicWheat + ",privateWheat=" + privateWheat
			+ ",materialChest=" + chest + ",granary=" + granary
			+ ",nextCare=" + context.village().data().extra.nextCare + ",cooldown=" + context.st().cooldownUntil
			+ ",walkStart=" + context.st().walkStart + ",lastMove=" + context.st().lastMoveAt
			+ ",stuck=" + context.st().stuckCount + ",walkTarget="
			+ worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).map(Object::toString).orElse("none")
			+ ",activity=" + context.st().activity + ",gate=" + level.getBlockState(gate)
			+ ",stands=" + stands + ",localAnimals=" + localAnimals + ",nearbyHerd=" + nearbyHerd;
	}

	private static String describeChest(ServerLevel level, VillageRef ref, Container cached) {
		BlockPos position = ref.data().chest.orElse(null);
		BlockState state = position == null || !level.hasChunkAt(position) ? null : level.getBlockState(position);
		BlockEntity blockEntity = position == null || !level.hasChunkAt(position) ? null : level.getBlockEntity(position);
		Container refQuery = ref.chest(level);
		Container publicQuery = position == null ? null : Lager.publicContainerAt(level, position);
		return "dataChest=" + position + ",state=" + state
			+ ",blockEntity=" + (blockEntity == null ? "null" : blockEntity.getClass().getName())
			+ ",cached=" + describeContainer(cached)
			+ ",refQuery=" + describeContainer(refQuery)
			+ ",publicAt=" + describeContainer(publicQuery);
	}

	private static String describeGranaries(VillageRef ref, List<Container> granaries) {
		return "origin=" + Specials.get(ref, Blueprints.GRANARY) + ",containers="
			+ granaries.stream().map(LivestockHerdingChecks::describeContainer).toList()
			+ ",count=" + countContainers(granaries, Items.WHEAT);
	}

	private static int countContainers(List<Container> containers, net.minecraft.world.item.Item item) {
		int total = 0;
		for (Container container : containers) total += JobUtil.count(container, stack -> stack.is(item));
		return total;
	}

	private static String describeContainer(Container container) {
		if (container == null) return "null";
		List<String> occupied = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.isEmpty()) occupied.add(slot + "=" + stack);
		}
		return container.getClass().getName() + "/size=" + container.getContainerSize()
			+ "/wheat=" + JobUtil.count(container, stack -> stack.is(Items.WHEAT))
			+ "/occupied=" + occupied;
	}

	private static void stagePen(ServerLevel level, Map<BlockPos, BlockSnapshot> before, BlockPos pen, BlockPos gate) {
		for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) {
			BlockPos feet = pen.offset(dx, 0, dz);
			set(level, before, feet.below(), Blocks.STONE.defaultBlockState());
			set(level, before, feet, Blocks.AIR.defaultBlockState());
			set(level, before, feet.above(), Blocks.AIR.defaultBlockState());
		}
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			if (Math.abs(dx) != 4 && Math.abs(dz) != 4) continue;
			BlockPos wall = pen.offset(dx, 0, dz);
			if (wall.equals(gate)) continue;
			set(level, before, wall, Blocks.OAK_FENCE.defaultBlockState());
		}
		BlockState closedGate = Blocks.OAK_FENCE_GATE.defaultBlockState()
			.setValue(FenceGateBlock.FACING, Direction.NORTH)
			.setValue(FenceGateBlock.OPEN, false);
		set(level, before, gate, closedGate);
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager worker, long now) {
		return new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static void tickHerdingPlans(ServerLevel level) {
		try {
			Method method = Livestock.class.getDeclaredMethod("tickHerdingPlans", ServerLevel.class);
			method.setAccessible(true);
			method.invoke(null, level);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: could not advance the actual server herding path", failure);
		}
	}

	private static void onEntityUnload(ServerLevel level, Entity entity) {
		try {
			Method method = Livestock.class.getDeclaredMethod("onEntityUnload", Entity.class, ServerLevel.class);
			method.setAccessible(true);
			method.invoke(null, entity, level);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: could not safely cancel its active route", failure);
		}
	}

	private static void recoverEntityPlan(Entity entity, ServerLevel level) {
		try {
			Method method = Livestock.class.getDeclaredMethod("recoverEntityPlan", Entity.class, ServerLevel.class);
			method.setAccessible(true);
			method.invoke(null, entity, level);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: could not recover saved herding state", failure);
		}
	}

	private static void forgetVolatilePlans(ServerLevel level) {
		try {
			Field field = Livestock.class.getDeclaredField("HERDING_PLANS");
			field.setAccessible(true);
			Map<?, ?> plans = (Map<?, ?>)field.get(null);
			plans.remove(level);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: could not simulate a server restart", failure);
		}
	}

	private static boolean hasNoHerdingMarkers(Entity entity) {
		return entity.entityTags().stream().noneMatch(tag -> tag.startsWith("vp_livestock_herd_plan:")
			|| tag.startsWith("vp_livestock_gate_recovery:"));
	}

	private static boolean hasExactPassageRecovery(Entity entity, List<BlockPos> positions, List<BlockState> states) {
		String prefix = "vp_livestock_gate_recovery:";
		List<String> markers = entity.entityTags().stream().filter(tag -> tag.startsWith(prefix)).toList();
		if (markers.size() != positions.size() || states.size() != positions.size()) return false;
		for (int i = 0; i < positions.size(); i++) {
			String expectedPrefix = prefix + positions.get(i).asLong() + "|";
			String expectedState = "|" + BlockStateParser.serialize(states.get(i));
			long matching = markers.stream().filter(tag -> tag.startsWith(expectedPrefix) && tag.endsWith(expectedState)).count();
			if (matching != 1) return false;
		}
		return true;
	}

	private static void set(ServerLevel level, Map<BlockPos, BlockSnapshot> before, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		before.computeIfAbsent(pos, p -> {
			BlockEntity blockEntity = level.getBlockEntity(p);
			return new BlockSnapshot(level.getBlockState(p), blockEntity == null ? null
				: blockEntity.saveWithFullMetadata(level.registryAccess()));
		});
		level.setBlock(pos, state, 3);
	}

	private static void omitSelfBellMeetingPoints(ServerLevel level, BlockPos bell,
		Map<java.util.UUID, GlobalPos> saved) {
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof Villager villager)) continue;
			Optional<GlobalPos> meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
			if (meeting.isEmpty() || !meeting.get().dimension().equals(level.dimension())
				|| !meeting.get().pos().equals(bell)) continue;
			saved.put(villager.getUUID(), meeting.get());
			villager.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
		}
	}

	private static void restoreMeetingPoints(ServerLevel level, Map<java.util.UUID, GlobalPos> saved) {
		for (Map.Entry<java.util.UUID, GlobalPos> entry : saved.entrySet()) {
			if (level.getEntity(entry.getKey()) instanceof Villager villager) {
				villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, entry.getValue());
			}
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("LIVESTOCK HERD CHECK FAILED: " + message);
	}

	private static String describePath(Path path) {
		if (path == null) return "null";
		List<String> nodes = new ArrayList<>();
		for (int i = 0; i < path.getNodeCount(); i++) nodes.add(path.getNodePos(i).toString());
		return "reachable=" + path.canReach() + ",target=" + path.getTarget() + ",nodes=" + nodes;
	}

	private static String describeRouteTypes(ServerLevel level, Animal animal, BlockPos gate, BlockPos inside) {
		BlockPos start = animal.blockPosition();
		BlockPos min = new BlockPos(Math.min(start.getX(), inside.getX()) - 2,
			Math.min(start.getY(), inside.getY()) - 2, Math.min(start.getZ(), inside.getZ()) - 2);
		BlockPos max = new BlockPos(Math.max(start.getX(), inside.getX()) + 2,
			Math.max(start.getY(), inside.getY()) + 2, Math.max(start.getZ(), inside.getZ()) + 2);
		PathNavigationRegion region = new PathNavigationRegion(level, min, max);
		WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
		evaluator.prepare(region, animal);
		PathfindingContext context = new PathfindingContext(region, animal);
		List<String> cells = new ArrayList<>();
		int direction = Integer.compare(inside.getZ(), start.getZ());
		for (int z = start.getZ(); ; z += direction) {
			BlockPos pos = new BlockPos(gate.getX(), start.getY(), z);
			cells.add(describeCell(level, animal, evaluator, context, pos));
			if (z == inside.getZ()) break;
		}
		cells.add("gateSides=" + describeCell(level, animal, evaluator, context, gate.west())
			+ "," + describeCell(level, animal, evaluator, context, gate.east()));
		evaluator.done();
		return cells.toString();
	}

	private static String describeCell(ServerLevel level, Animal animal, WalkNodeEvaluator evaluator,
		PathfindingContext context, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		PathType raw = evaluator.getPathType(context, pos.getX(), pos.getY(), pos.getZ());
		PathType mob = evaluator.getPathTypeOfMob(context, pos.getX(), pos.getY(), pos.getZ(), animal);
		return pos.getX() + "," + pos.getY() + "," + pos.getZ() + "=" + state + "/" + raw + "/" + mob;
	}

	private static PathType pathTypeAt(ServerLevel level, Animal animal, BlockPos position) {
		PathNavigationRegion region = new PathNavigationRegion(level, position.offset(-2, -2, -2), position.offset(2, 2, 2));
		WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
		evaluator.prepare(region, animal);
		try {
			return evaluator.getPathType(new PathfindingContext(region, animal),
				position.getX(), position.getY(), position.getZ());
		} finally {
			evaluator.done();
		}
	}

	private static String describeRouteSweep(ServerLevel level, Animal animal, BlockPos target) {
		Vec3 position = animal.position();
		double dx = target.getX() + 0.5 - position.x;
		double dy = target.getY() - position.y;
		double dz = target.getZ() + 0.5 - position.z;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		int steps = Math.max(1, (int)Math.ceil(distance / 0.125));
		StringBuilder result = new StringBuilder("clear=");
		for (int step = 1; step <= steps; step++) {
			double fraction = (double)step / steps;
			if (step > 1) result.append(',');
			result.append(level.noCollision(animal, animal.getBoundingBox().move(dx * fraction, dy * fraction, dz * fraction)));
		}
		return result.toString();
	}
}
