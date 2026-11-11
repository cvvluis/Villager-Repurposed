package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Focused fixture for the bounded, persisted market-site natural-terrace fallback. */
public final class MarketSitePreparationChecks {
	private record Cell(BlockState state, CompoundTag blockEntity) { }

	private MarketSitePreparationChecks() { }

	public static int run(ServerLevel level, BlockPos searchCenter) {
		if (level == null || searchCenter == null) throw new IllegalArgumentException("Market site preparation needs a level and search center");
		VillageRef ref = Villages.villageNear(level, searchCenter, 128);
		if (ref == null) throw new IllegalStateException("Market site preparation fixture needs a nearby village bell");
		MarketData originalMarket = ref.bellEntity().getAttached(VillagerPlus.MARKET);
		List<ZonePolicy.Zone> originalZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		Villager worker = null;
		int checks = 0;
		net.minecraft.world.SimpleContainer fullResident = new net.minecraft.world.SimpleContainer(1);
		fullResident.setItem(0, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 64));
		net.minecraft.world.SimpleContainer publicStorage = new net.minecraft.world.SimpleContainer(1);
		List<Container> noPublicCapacity = List.of(fullResident);
		List<Container> withPublicCapacity = List.of(fullResident, publicStorage);
		check(!Market.canFitAllMarketDrops(noPublicCapacity,
				List.of(new net.minecraft.world.item.ItemStack(Items.DIRT)))
			&& Market.canFitAllMarketDrops(withPublicCapacity,
				List.of(new net.minecraft.world.item.ItemStack(Items.DIRT)))
			&& fullResident.getItem(0).getCount() == 64 && publicStorage.isEmpty(),
			"market clearing can reserve an additional public container for drops when the resident is full, without mutating either during the capacity check"); checks++;
		check(!Market.hasCorrectMarketDropTool(Blocks.DIAMOND_ORE.defaultBlockState(),
				new net.minecraft.world.item.ItemStack(Items.WOODEN_PICKAXE))
			&& Market.hasCorrectMarketDropTool(Blocks.DIAMOND_ORE.defaultBlockState(),
				new net.minecraft.world.item.ItemStack(Items.IRON_PICKAXE)),
			"market grading waits for a tool tier that preserves required ore drops"); checks++;
		BlockPos chosen = null;
		BlockPos plannedOrigin = null;
		int fixtureEligibleCandidates = 0;
		int ordinaryTerraceCandidates = 0;
		int preparationRejectedCandidates = 0;
		boolean shiftedAnchorFoundSafeSite = false;
		Map<String, Integer> fixtureRejections = new LinkedHashMap<>();
		try {
			Market.save(ref, new MarketData(java.util.Optional.empty(), List.of(), 0, 0));
			// The reused fixture bell may retain zones from an earlier check; test a clean plot and restore them below.
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of());
			List<BlockPos> candidates = Market.preparableMarketSiteCandidates(ref, searchCenter);
			List<BlockPos> nextRetryCandidates = Market.preparableMarketSiteCandidates(ref, searchCenter, 1L);
			check(candidates.size() <= 64 && nextRetryCandidates.size() <= 64
				&& candidates.equals(Market.preparableMarketSiteCandidates(ref, searchCenter))
				&& nextRetryCandidates.equals(Market.preparableMarketSiteCandidates(ref, searchCenter, 1L))
				&& (candidates.size() < 64 || !candidates.equals(nextRetryCandidates)),
				"preparation retry batches stay deterministic and capped while rotating to alternate plots when more than 64 exist"); checks++;
			long outerRadiusSquared = (long) (de.villagerplus.Config.VILLAGE_RADIUS - 4)
				* (de.villagerplus.Config.VILLAGE_RADIUS - 4) / 4;
			int[] outerQuadrants = new int[4];
			for (BlockPos candidate : candidates) {
				long dx = (long) candidate.getX() - ref.bell().getX();
				long dz = (long) candidate.getZ() - ref.bell().getZ();
				if (dx * dx + dz * dz >= outerRadiusSquared) {
					int quadrant = (dx < 0 ? 1 : 0) | (dz < 0 ? 2 : 0);
					outerQuadrants[quadrant]++;
				}
			}
			check(java.util.Arrays.stream(outerQuadrants).allMatch(count -> count >= 6),
				"the bounded preparation samples cover outer natural-site opportunities in all four village quadrants: "
					+ java.util.Arrays.toString(outerQuadrants)); checks++;
			long localCandidateCount = candidates.stream().filter(candidate -> {
				long dx = (long) candidate.getX() - searchCenter.getX();
				long dz = (long) candidate.getZ() - searchCenter.getZ();
				return dx * dx + dz * dz <= 42L * 42L;
			}).count();
			check(localCandidateCount >= 40,
				"the capped batch reserves forty dense local candidates for compact natural clearings: "
					+ localCandidateCount); checks++;
			check(candidates.contains(searchCenter.offset(9, 0, 9))
				|| nextRetryCandidates.contains(searchCenter.offset(9, 0, 9)),
				"rotating local batches reach a three-block-offset clearing between the former seven-block samples"); checks++;
			for (BlockPos candidate : candidates) {
				if (chosen != null) break;
				candidate = candidate.immutable();
				loadFixtureCandidateArea(level, candidate);
				int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					candidate.getX(), candidate.getZ());
				candidate = new BlockPos(candidate.getX(), surfaceY, candidate.getZ());
				String rejection = fixtureAreaRejection(level, ref, candidate);
				if (rejection != null) {
					fixtureRejections.merge(rejection, 1, Integer::sum);
					continue;
				}
				fixtureEligibleCandidates++;
				rememberFixtureArea(level, candidate, before);
				prepareFlatMarketSite(level, candidate);
				BlockPos shiftedAnchor = candidate.above(8);
				BlockPos surfaceSite = Market.prepareMarketSite(level, ref, shiftedAnchor, Direction.NORTH);
				if (surfaceSite != null && surfaceSite.getX() == candidate.getX()
					&& surfaceSite.getY() == candidate.getY() && surfaceSite.getZ() == candidate.getZ()) {
					shiftedAnchorFoundSafeSite = true;
				}
				restoreFixtureArea(level, before);
				before.clear();
				rememberFixtureArea(level, candidate, before);
				prepareFourBlockTerrace(level, candidate);
				Direction facing = Direction.NORTH;
				if (Market.prepareMarketSite(level, ref, candidate, facing) != null) {
					ordinaryTerraceCandidates++;
					restoreFixtureArea(level, before);
					before.clear();
					continue;
				}
				BlockPos prepared = Market.findPreparableMarketSite(level, ref, searchCenter,
					Market.of(ref).shops(), List.of(candidate));
				if (prepared == null || prepared.getX() != candidate.getX() || prepared.getZ() != candidate.getZ()) {
					preparationRejectedCandidates++;
					restoreFixtureArea(level, before);
					before.clear();
					continue;
				}
				chosen = candidate;
				plannedOrigin = prepared;
			}
			if (chosen == null) {
				throw new IllegalStateException("Market site-preparation fixture needs a clean candidate among its first 64 bounded samples"
					+ " [samples=" + candidates.size() + ", fixtureEligible=" + fixtureEligibleCandidates
					+ ", ordinaryTerraceAccepted=" + ordinaryTerraceCandidates
					+ ", preparationRejected=" + preparationRejectedCandidates
					+ ", fixtureGuardRejected=" + fixtureRejections + "]");
			}
			check(shiftedAnchorFoundSafeSite,
				"site grading rebases a candidate to its own natural surface when the plaza anchor is eight blocks higher"); checks++;
			BlockPos candidate = chosen;
			BlockPos expectedOrigin = plannedOrigin;
			check(expectedOrigin.getY() == candidate.getY()
				&& Market.prepareMarketSite(level, ref, candidate, Direction.NORTH) == null,
				"the ordinary candidate rejects a four-block natural terrace that needs villager preparation"); checks++;
			BlockState lowFloorState = level.getBlockState(candidate);
			level.setBlock(candidate, Blocks.CHEST.defaultBlockState(), 3);
			check(Market.findPreparableMarketSite(level, ref, searchCenter, Market.of(ref).shops(), List.of(candidate)) == null
				&& level.getBlockState(candidate).is(Blocks.CHEST),
				"a block entity inside the proposed work volume disqualifies the natural-terrace fallback"); checks++;
			level.setBlock(candidate, lowFloorState, 3);
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(new ZonePolicy.Zone("market-prep-private", "housing",
				candidate.getX() - 4, candidate.getZ() - 4, candidate.getX() + 4, candidate.getZ() + 4)));
			check(Market.findPreparableMarketSite(level, ref, searchCenter, List.of(), List.of(candidate)) == null,
				"the natural-terrace fallback preserves a private housing zone"); checks++;
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of());
			Villager siteOccupant = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (siteOccupant == null) throw new IllegalStateException("Could not create market site occupancy fixture");
			siteOccupant.setPos(expectedOrigin.getX() + 0.5, expectedOrigin.getY() + 3.0, expectedOrigin.getZ() + 0.5);
			siteOccupant.addTag("vp090_fixture");
			siteOccupant.setNoAi(true);
			try {
				Direction candidateFacing = Market.marketFacingToward(searchCenter, candidate);
				List<BlockPos> targetCells = Market.marketSiteTargets(expectedOrigin, candidateFacing);
				var siteVolume = Market.marketSiteTargetVolume(targetCells);
				BlockPos spawnedBlock = expectedOrigin.above(3);
				boolean spawnedBlockIsTarget = targetCells.contains(spawnedBlock);
				boolean entityBoundsTouchTargets = Market.marketEntityOverlapsTargets(siteOccupant, targetCells);
				int actualSurfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					candidate.getX(), candidate.getZ());
				String occupancyDetails = " [candidate=" + candidate + ", surfaceAtCandidateXZ="
					+ new BlockPos(candidate.getX(), actualSurfaceY, candidate.getZ()) + ", siteOrigin=" + expectedOrigin
					+ ", facing=" + candidateFacing + ", targetCellCount=" + targetCells.size()
					+ ", spawnedBlock=" + spawnedBlock + ", spawnedBlockIsTarget=" + spawnedBlockIsTarget
					+ ", villagerBlockPos=" + siteOccupant.blockPosition() + ", villagerPos=" + siteOccupant.position()
					+ ", villagerBBox=" + siteOccupant.getBoundingBox() + ", entityBoundsTouchTargets="
					+ entityBoundsTouchTargets + ", siteVolume=" + siteVolume + "]";
				check(entityBoundsTouchTargets,
					"market occupancy geometry recognizes a villager bounding box intersecting target cells"
						+ (entityBoundsTouchTargets ? "" : occupancyDetails)); checks++;
			} finally {
				siteOccupant.discard();
			}
			check(expectedOrigin.equals(Market.findPreparableMarketSite(level, ref, searchCenter, List.of(), List.of(candidate))),
				"market candidate search finds the same safe plot after private zoning is gone"); checks++;

			prepareOverLimitMarketTerrace(level, expectedOrigin);
			Direction woodedFacing = Market.marketFacingToward(searchCenter, candidate);
			List<BlockPos> woodedTargets = Market.marketSiteTargets(expectedOrigin, woodedFacing);
			NaturalSitePreparation.Scan overLimit = Market.inspectMarketSite(level, expectedOrigin, woodedTargets);
			BlockPos overLimitSite = Market.findPreparableMarketSite(level, ref, searchCenter, List.of(), List.of(candidate));
			check(woodedTargets.size() >= 125 && overLimit.status() == NaturalSitePreparation.Status.CLEARABLE
				&& overLimit.obstacleCount() > 96 && expectedOrigin.equals(overLimitSite),
				"a safe four-block natural terrace with more than 96 removable cells stays eligible across its complete market volume"
					+ " [targets=" + woodedTargets.size() + ", status=" + overLimit.status() + ", obstacles="
					+ overLimit.obstacleCount() + ", selected=" + overLimitSite + ", expected=" + expectedOrigin + "]"); checks++;
			prepareFourBlockTerrace(level, expectedOrigin);

			MarketData.Shop elevatedShop = new MarketData.Shop(UUID.randomUUID(), expectedOrigin.above(16),
				Direction.NORTH.get2DDataValue(), 1, 0, 0, level.getGameTime(), List.of(), false, 0);
			check(Market.findPreparableMarketSite(level, ref, searchCenter, List.of(elevatedShop), List.of(candidate)) == null,
				"an existing stall reserves its horizontal footprint even when its saved Y coordinate differs by more than eight blocks"); checks++;

			BlockPos treeRoot = expectedOrigin.offset(-2, 0, 0);
			for (int dy = 0; dy <= 2; dy++) level.setBlock(treeRoot.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
			level.setBlock(treeRoot.above(4), Blocks.OAK_LEAVES.defaultBlockState()
				.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), 3);
			check(expectedOrigin.equals(Market.findPreparableMarketSite(level, ref, searchCenter, List.of(), List.of(candidate))),
				"the last-resort grader recognizes a rooted tree while selecting a four-block natural terrace"); checks++;
			Map<BlockPos, Cell> tallTreeBefore = new LinkedHashMap<>();
			for (int dy = 3; dy <= 19; dy++) {
				BlockPos pos = treeRoot.above(dy).immutable();
				BlockEntity entity = level.getBlockEntity(pos);
				tallTreeBefore.put(pos, new Cell(level.getBlockState(pos), entity == null ? null
					: entity.saveWithFullMetadata(level.registryAccess())));
			}
			try {
				for (int dy = 3; dy <= 6; dy++) level.setBlock(treeRoot.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
				level.setBlock(treeRoot.above(8), Blocks.OAK_LEAVES.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), 3);
				NaturalSitePreparation.Scan tallTree = Market.inspectMarketSite(level, expectedOrigin, woodedTargets);
				BlockPos tallTreeSite = Market.findPreparableMarketSite(level, ref, searchCenter, List.of(), List.of(candidate));
				check(tallTree.status() == NaturalSitePreparation.Status.CLEARABLE
					&& treeRoot.above(6).equals(tallTree.next())
					&& expectedOrigin.equals(tallTreeSite)
					&& level.getBlockState(treeRoot.above(6)).is(Blocks.OAK_LOG),
					"a bounded rooted trunk above the stand roof remains eligible and is scheduled from its top down"); checks++;
				for (int dy = 7; dy <= 17; dy++) level.setBlock(treeRoot.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
				level.setBlock(treeRoot.above(19), Blocks.OAK_LEAVES.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), 3);
				NaturalSitePreparation.Scan overHeightTree = Market.inspectMarketSite(level, expectedOrigin, woodedTargets);
				check(overHeightTree.status() == NaturalSitePreparation.Status.BLOCKED
					&& level.getBlockState(treeRoot.above(17)).is(Blocks.OAK_LOG),
					"a trunk above the fixed felling-height limit remains intact instead of becoming an unbounded job"); checks++;
			} finally {
				restoreFixtureArea(level, tallTreeBefore);
			}

			MarketData.Shop safetyFixture = new MarketData.Shop(UUID.randomUUID(), expectedOrigin,
				Direction.NORTH.get2DDataValue(), 1, 0, 0, level.getGameTime(), List.of(), false, 0);
			BlockPos waterTarget = expectedOrigin.above(3);
			level.setBlock(waterTarget, Blocks.WATER.defaultBlockState(), 3);
			var waterBlocked = Market.clearMarketSiteStep(level, ref, safetyFixture, null);
			check(waterBlocked.status() == NaturalSitePreparation.Status.BLOCKED
				&& !level.getBlockState(waterTarget).getFluidState().isEmpty(),
				"water inside an unfinished market footprint blocks preparation and remains untouched"); checks++;
			level.setBlock(waterTarget, Blocks.AIR.defaultBlockState(), 3);
			BlockPos valuableTarget = expectedOrigin.above(2);
			level.setBlock(valuableTarget, Blocks.DIAMOND_ORE.defaultBlockState(), 3);
			var valuableBlocked = Market.clearMarketSiteStep(level, ref, safetyFixture, null);
			check(valuableBlocked.status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(valuableTarget).is(Blocks.DIAMOND_ORE),
				"a valuable non-natural block blocks preparation and remains untouched"); checks++;
			level.setBlock(valuableTarget, Blocks.AIR.defaultBlockState(), 3);

			Map<BlockPos, BlockState> gravelRoadBefore = new LinkedHashMap<>();
			for (int dx = -2; dx <= 0; dx++) {
				int x = expectedOrigin.getX() + dx, z = expectedOrigin.getZ() + 2;
				int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos roadCell = new BlockPos(x, surfaceY - 1, z);
				gravelRoadBefore.put(roadCell, level.getBlockState(roadCell));
				level.setBlock(roadCell, Blocks.GRAVEL.defaultBlockState(), 3);
			}
			var gravelRoadBlocked = Market.clearMarketSiteStep(level, ref, safetyFixture, null);
			check(gravelRoadBlocked.status() == NaturalSitePreparation.Status.BLOCKED
				&& gravelRoadBefore.keySet().stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.GRAVEL)),
				"a CTOV-style gravel road inside the market footprint blocks preparation and remains intact"); checks++;
			gravelRoadBefore.forEach((pos, state) -> level.setBlock(pos, state, 3));

			UUID owner = UUID.randomUUID();
			long now = level.getGameTime() + 2_000L;
			MarketData.Shop savedShop = new MarketData.Shop(owner, expectedOrigin, Direction.NORTH.get2DDataValue(), 1,
				0, 0, now, List.of(), false, 0);
			MarketData current = Market.of(ref);
			List<MarketData.Shop> shops = new ArrayList<>(current.shops());
			shops.add(savedShop);
			Market.save(ref, new MarketData(current.plaza(), List.copyOf(shops), current.nextPlan(), current.sales()));
			MarketData savedMarket = MarketData.CODEC.parse(JsonOps.INSTANCE,
				MarketData.CODEC.encodeStart(JsonOps.INSTANCE, Market.of(ref)).getOrThrow()).getOrThrow();
			MarketData.Shop resumedShop = savedMarket.shops().stream().filter(shop -> shop.owner().equals(owner))
				.findFirst().orElseThrow();
			check(resumedShop.origin().equals(expectedOrigin) && resumedShop.progress() == 0
				&& resumedShop.opened() == 0 && !resumedShop.deceased() && !resumedShop.sitePrepared(),
				"the fallback terrace is persisted as an unopened, resumable market plot before any clearing"); checks++;
			BlockPos naturalCover = candidate.offset(-2, 0, 2);
			level.setBlock(naturalCover, Blocks.SHORT_GRASS.defaultBlockState(), 3);

			worker = EntityTypes.VILLAGER.spawn(level, candidate.west(4), EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("Could not create market terrace preparation worker");
			worker.addTag("vp090_fixture");
			worker.setInvulnerable(true);
			worker.setNoAi(true);
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(Items.IRON_AXE));
			worker.getInventory().setItem(1, new net.minecraft.world.item.ItemStack(Items.SHEARS));
			worker.getInventory().setItem(2, new net.minecraft.world.item.ItemStack(Items.IRON_SHOVEL));
			BlockPos firstCut = null;
			int dirtBefore = count(worker.getInventory(), Items.DIRT);
			int logsBefore = count(worker.getInventory(), Items.OAK_LOG);
			int cleared = 0;
			NaturalSitePreparation.Scan scan = Market.clearMarketSiteStep(level, ref, resumedShop, worker);
			if (scan.status() == NaturalSitePreparation.Status.CLEARED) {
				firstCut = scan.next();
				cleared++;
			}
			check(scan.status() == NaturalSitePreparation.Status.CLEARED && treeRoot.above(2).equals(firstCut)
				&& scan.obstacleCount() == 55 && level.getBlockState(firstCut).isAir()
				&& count(worker.getInventory(), Items.OAK_LOG) == logsBefore + 1,
				"a worker removes one verified canopy log from the wooded terrace and keeps its real drop"); checks++;

			// Loading the shop again leaves the saved origin fixed; each work visit rechecks the remaining terrain.
			resumedShop = MarketData.Shop.CODEC.parse(JsonOps.INSTANCE,
				MarketData.Shop.CODEC.encodeStart(JsonOps.INSTANCE, resumedShop).getOrThrow()).getOrThrow();
			while (scan.status() == NaturalSitePreparation.Status.CLEARED && cleared <= 55) {
				scan = Market.clearMarketSiteStep(level, ref, resumedShop, worker);
				if (scan.status() == NaturalSitePreparation.Status.CLEARED) cleared++;
			}
			check(scan.status() == NaturalSitePreparation.Status.READY && cleared == 55
				&& resumedShop.origin().equals(expectedOrigin)
				&& Market.shop(ref, owner) != null && Market.shop(ref, owner).progress() == 0
				&& Market.shop(ref, owner).opened() == 0 && level.getBlockState(naturalCover).isAir()
				&& count(worker.getInventory(), Items.DIRT) == dirtBefore + 50
				&& count(worker.getInventory(), Items.OAK_LOG) == logsBefore + 3
				&& count(worker.getInventory(), Items.OAK_LEAVES) == 1
				&& worker.getInventory().getItem(0).getDamageValue() > 0
				&& worker.getInventory().getItem(1).getDamageValue() > 0
				&& worker.getInventory().getItem(2).getDamageValue() > 0,
				"reloaded preparation levels the terrace in 55 visits, preserves drops, damages only carried clearing tools, and leaves build progress untouched"); checks++;
			MarketData.Shop readyShop = Market.persistSitePrepared(ref, resumedShop, now + 100);
			MarketData.Shop readyRoundTrip = MarketData.CODEC.parse(JsonOps.INSTANCE,
				MarketData.CODEC.encodeStart(JsonOps.INSTANCE, Market.of(ref)).getOrThrow()).getOrThrow()
				.shops().stream().filter(shop -> shop.owner().equals(owner)).findFirst().orElseThrow();
			check(readyShop != null && readyShop.sitePrepared() && readyShop.progress() == 0
				&& readyRoundTrip.sitePrepared() && readyRoundTrip.progress() == 0,
				"a ready graded woodland site is durably marked prepared before the first paid build step"); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			restoreFixtureArea(level, before);
			if (originalMarket == null) ref.bellEntity().removeAttached(VillagerPlus.MARKET);
			else ref.bellEntity().setAttached(VillagerPlus.MARKET, originalMarket);
			if (originalZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, originalZones);
			ref.dirty();
		}
	}

	private static String fixtureAreaRejection(ServerLevel level, VillageRef ref, BlockPos center) {
		if (center.getY() <= level.getMinY() + 1 || center.getY() >= level.getMaxY() - 5) return "height-range";
		AABB area = new AABB(center.getX() - 2, center.getY() - 1, center.getZ() - 2,
			center.getX() + 3, center.getY() + 5, center.getZ() + 3);
		if (!level.getEntitiesOfClass(LivingEntity.class, area, entity -> entity.isAlive()).isEmpty()) return "living-entity";
		if (level.getPoiManager().getInRange(poi -> true, center, 10,
			net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).anyMatch(poi ->
				poi.getPos().getX() >= center.getX() - 3 && poi.getPos().getX() <= center.getX() + 3
					&& poi.getPos().getZ() >= center.getZ() - 3 && poi.getPos().getZ() <= center.getZ() + 3)) return "poi";
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			int x = center.getX() + dx, z = center.getZ() + dz;
			BlockPos column = new BlockPos(x, center.getY(), z);
			if (!level.hasChunkAt(column)) return "unloaded-inner-chunk";
			int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (level.getBlockState(new BlockPos(x, surfaceY - 1, z)).is(Blocks.GRAVEL)) return "gravel-road-or-ground";
			if (surfaceY > center.getY() + 4) return "neighbor-relief-over-four";
			for (int y = center.getY() - 1; y <= center.getY() + 4; y++) {
				BlockPos pos = new BlockPos(x, y, z);
				BlockState state = level.getBlockState(pos);
				if (state.hasBlockEntity() || level.getBlockEntity(pos) != null) return "block-entity";
				if (!state.getFluidState().isEmpty()) return "fluid";
				if (!state.isAir() && !VillageStyle.natural(state) && !state.is(BlockTags.OVERWORLD_NATURAL_LOGS))
					return "built-or-nonnatural-block";
			}
		}
		for (ZonePolicy.Zone zone : ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of())) {
			if ("work".equals(zone.kind())) continue;
			if (zone.maxX() >= center.getX() - 3 && zone.minX() <= center.getX() + 3
				&& zone.maxZ() >= center.getZ() - 3 && zone.minZ() <= center.getZ() + 3) return "protected-zone:" + zone.kind();
		}
		for (VillageData.BuildProject project : ref.data().buildProjects) {
			if (!project.active()) continue;
			int half = project.type().isEmpty() ? 3 : Math.max(16, Blueprints.half(project.type()) + 3);
			BlockPos origin = project.origin();
			VillageData.PlanBounds bounds = project.bounds().orElseGet(() -> new VillageData.PlanBounds(
				origin.getX() - half, origin.getX() + half, origin.getZ() - half, origin.getZ() + half));
			if (bounds.maxX() >= center.getX() - 3 && bounds.minX() <= center.getX() + 3
				&& bounds.maxZ() >= center.getZ() - 3 && bounds.minZ() <= center.getZ() + 3) return "active-build-project";
		}
		if (Market.marketOverlapsSavedRoad(level, ref, center,
			center.getX() - 2, center.getX() + 2, center.getZ() - 2, center.getZ() + 2)) return "saved-road";
		if (Sites.touchesBuilding(level, center, -3, 3, -3, 3, 5)) return "building-or-clearance";
		return null;
	}

	private static void loadFixtureCandidateArea(ServerLevel level, BlockPos center) {
		int minChunkX = Math.floorDiv(center.getX() - 6, 16);
		int maxChunkX = Math.floorDiv(center.getX() + 6, 16);
		int minChunkZ = Math.floorDiv(center.getZ() - 6, 16);
		int maxChunkZ = Math.floorDiv(center.getZ() + 6, 16);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
				level.getChunk(new BlockPos(chunkX * 16, center.getY(), chunkZ * 16));
	}

	private static void rememberFixtureArea(ServerLevel level, BlockPos center, Map<BlockPos, Cell> before) {
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
			for (int dy = -1; dy <= 4; dy++) {
				BlockPos pos = center.offset(dx, dy, dz).immutable();
				BlockEntity entity = level.getBlockEntity(pos);
				before.put(pos, new Cell(level.getBlockState(pos), entity == null ? null
					: entity.saveWithFullMetadata(level.registryAccess())));
			}
	}

	private static void prepareFourBlockTerrace(ServerLevel level, BlockPos center) {
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			for (int dy = -1; dy <= 4; dy++) level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
			for (int dy = 0; dy < dx + 2; dy++)
				level.setBlock(center.offset(dx, dy, dz), Blocks.DIRT.defaultBlockState(), 2);
		}
	}

	private static void prepareOverLimitMarketTerrace(ServerLevel level, BlockPos center) {
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			for (int dy = -1; dy <= 4; dy++) level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
			int height = dx == -2 && dz == 0 ? 0 : dx == -1 && dz == 0 ? 3 : 4;
			for (int dy = 0; dy < height; dy++) level.setBlock(center.offset(dx, dy, dz), Blocks.DIRT.defaultBlockState(), 2);
		}
		BlockPos treeRoot = center.offset(-2, 0, 0);
		for (int dy = 0; dy <= 2; dy++) level.setBlock(treeRoot.above(dy), Blocks.OAK_LOG.defaultBlockState(), 2);
		level.setBlock(treeRoot.above(4), Blocks.OAK_LEAVES.defaultBlockState()
			.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), 2);
	}

	private static void prepareFlatMarketSite(ServerLevel level, BlockPos center) {
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			for (int dy = -1; dy <= 4; dy++) level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
		}
	}

	private static void restoreFixtureArea(ServerLevel level, Map<BlockPos, Cell> before) {
		before.keySet().forEach(level::removeBlockEntity);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.blockEntity() == null) return;
			BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
			if (restored != null) level.setBlockEntity(restored);
		});
	}

	private static int count(Container container, net.minecraft.world.item.Item item) {
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
		}
		return total;
	}

	private static void check(boolean condition, String description) {
		if (!condition) throw new IllegalStateException("MARKET SITE PREPARATION CHECK FAILED: " + description);
	}
}
