package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** G08/D06–D08: metropolis gates, bilateral track construction, and chest-minecart freight on a real corridor. */
public final class RailEndToEndChecks {
	private RailEndToEndChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1;
		at = new BlockPos(at.getX(), groundY + 1, at.getZ());
		BlockPos peerAt = new BlockPos(at.getX() + 256, at.getY(), at.getZ());
		prepareFlatRoute(level, at, peerAt);
		level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
		var ref = Checks090.fixtureForEquipment(level, at);
		level.getChunkAt(peerAt);
		level.setBlock(peerAt.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(peerAt.above(), Blocks.AIR.defaultBlockState(), 3);
		var peer = Checks090.fixtureForEquipment(level, peerAt);
		moveCenterChest(level, ref);
		moveCenterChest(level, peer);
		Container homeBox = ref.chest(level);
		Container peerBox = peer.chest(level);
		var oldA = Network.of(ref.bellEntity());
		var oldB = Network.of(peer.bellEntity());
		List<Villager> fixtures = new ArrayList<>();
		List<MinecartChest> carts = new ArrayList<>();
		int checks = 0;
		try {
			checks += RailFuelChecks.run(level, ref);
			linkMetropolis(level, ref, peer);
			clearBoundResidents(level, ref);
			clearBoundResidents(level, peer);
			forceSettlement(level, ref.bell());
			forceSettlement(level, peer.bell());
			List<Villager> peerResidents = spawnResidents(level, peer, fixtures);
			Villages.resetAssignmentCache();
			List<Villager> homeResidents = spawnResidents(level, ref, fixtures);
			for (int i = 0; i < homeResidents.size(); i++) {
				Villager v = homeResidents.get(i);
				v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
			}
			for (int i = 0; i < peerResidents.size(); i++) {
				Villager v = peerResidents.get(i);
				v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), peer.bell()));
			}
			check(peerResidents.stream().filter(Villager::isAlive).count() == Roads.RAIL_POP,
				"all peer fixtures stay alive after spawn"); checks++;
			for (Villager v : homeResidents) level.getChunkAt(v.blockPosition());
			for (Villager v : peerResidents) level.getChunkAt(v.blockPosition());
			level.tick(() -> false);
			retainResidents(level, homeResidents);
			retainResidents(level, peerResidents);
			check(homeResidents.stream().filter(v -> v.getBrain().getMemory(MemoryModuleType.MEETING_POINT)
				.filter(g -> g.pos().equals(ref.bell())).isPresent()).count() == Roads.RAIL_POP,
				"home fixtures remember the village bell meeting point"); checks++;
			check(Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> true).size() == Roads.RAIL_POP,
				"resident census counts every bound home villager (" + Roads.RAIL_POP + ")"); checks++;
			int peerCensus = Villages.residents(level, peer, de.villagerplus.Config.VILLAGE_RADIUS, v -> true).size();
			check(peerCensus == Roads.RAIL_POP,
				"peer census counts every bound peer villager (" + peerCensus + "/" + Roads.RAIL_POP + ")"); checks++;
			DepotLayout home = layoutDepots(level, ref, homeBox);
			DepotLayout peerLayout = layoutDepots(level, peer, peerBox);
			stockMetallurgy(homeBox);
			stockMetallurgy(peerBox);
			stockRailsInStation(level, home.station());
			stockRailsInStation(level, peerLayout.station());
			addRails(homeBox, 48);
			addRails(peerBox, 48);
			check(Roads.railReady(level, ref) && Roads.railReady(level, peer),
				"both endpoints pass railReady with milestones, population and metallurgy"); checks++;
			check(level.getBlockEntity(ref.bell()) == ref.bellEntity()
				&& level.getBlockEntity(peer.bell()) == peer.bellEntity(),
				"paving the corridor preserves both village bells and their stored approvals"); checks++;
			check(Roads.railProjectReady(level, ref, peer),
				"reciprocal metropolis approvals unlock the bilateral rail project"
					+ " [approved=" + Network.projectApproved(ref, peer)
					+ ", homeLinks=" + Network.of(ref.bellEntity()).links()
					+ ", peerLinks=" + Network.of(peer.bellEntity()).links() + "]"); checks++;
			String routeKey = Network.dim(level) + peer.bell().asLong();
			String peerKey = Network.dim(level) + ref.bell().asLong();
			Map<String, Long> roads = new HashMap<>();
			Map<String, Long> peerRoads = new HashMap<>();
			check(RoadRoute.ensureWithRetry(level, ref.bell(), peer.bell(), roads, routeKey, peerRoads, peerKey,
				level.getGameTime(), false),
				"flat synthetic corridor produces committed route geometry before paving"); checks++;
			ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
			peer.bellEntity().setAttached(VillagerPlus.ROADS, peerRoads);
			int length = RoadRoute.length(roads, routeKey, ref.bell(), peer.bell());
			paveCorridor(level, roads, routeKey, ref.bell(), peer.bell(), length);
			BlockPos station = Specials.get(ref, Blueprints.STATION);
			int stationStart = Roads.stationRailStartForCheck(level, ref.bell(), peer.bell(), station, roads, routeKey);
			check(stationStart >= 0, "station boarding cursor resolves on the paved corridor (got " + stationStart + ")"); checks++;
			check(RoadRoute.ensureWithRetry(level, ref.bell(), peer.bell(), roads, routeKey, peerRoads, peerKey, level.getGameTime(), false),
				"shared route geometry is committed before bilateral rail advances"); checks++;
			BlockPos routeAtStation = RoadRoute.point(roads, routeKey, ref.bell(), peer.bell(), stationStart);
			BlockPos railBed = railCell(level, routeAtStation.getX(), routeAtStation.getZ(), ref.bell().getY());
			check(railBed != null && level.getBlockState(railBed).isAir(),
				"station route cell exposes an empty rail bed before construction"); checks++;
			checks += stationPlatformPaymentChecks(level, ref, peer, roads, routeKey, stationStart, routeAtStation);
			check(de.villagerplus.village.RailResources.hasMetallurgyStock(level, ref)
				&& de.villagerplus.village.RailResources.canPay(level, ref, false, false),
				"village stores still cover metallurgy and a plain rail segment after paving"); checks++;
			check(de.villagerplus.village.RailResources.canPay(level, ref, true, true),
				"station stock includes one powered rail and one redstone block for the next elevated segment"); checks++;
			String railKey = "rail" + peer.bell().asLong();
			int firstPoweredCursor = stationStart + Math.floorMod(3 - stationStart, 6);
			check(firstPoweredCursor <= length / 2 + 1, "a scheduled powered-rail point exists on the home half"); checks++;
			BlockPos poweredRoute = RoadRoute.point(roads, routeKey, ref.bell(), peer.bell(), firstPoweredCursor);
			BlockPos poweredCell = railCell(level, poweredRoute.getX(), poweredRoute.getZ(), ref.bell().getY());
			check(poweredCell != null && level.getBlockState(poweredCell).isAir(),
				"scheduled powered-rail point starts as an empty rail bed"); checks++;
			boolean poweredAlongX = RoadRoute.alongX(roads, routeKey, ref.bell(), peer.bell(), firstPoweredCursor);
			var ordinaryShape = poweredAlongX
				? net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST
				: net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH;
			check(level.setBlock(poweredCell, Blocks.RAIL.defaultBlockState()
				.setValue(net.minecraft.world.level.block.RailBlock.SHAPE, ordinaryShape), 3),
				"fixture places an ordinary rail at the scheduled powered-rail point"); checks++;
			int attemptsToPowerPoint = (firstPoweredCursor - stationStart) / 4 + 1;
			List<RailStockSnapshot> savedPoweredRails = clearRailItem(level, ref, Items.POWERED_RAIL);
			List<RailStockSnapshot> savedRedstoneBlocks = clearRailItem(level, ref, Items.REDSTONE_BLOCK);
			try {
				advanceRailTicks(level, ref, attemptsToPowerPoint, 0);
			} finally {
				restoreRailItems(savedPoweredRails);
				restoreRailItems(savedRedstoneBlocks);
			}
			roads = ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
			check(roads.getOrDefault(railKey, -1L) == firstPoweredCursor
				&& level.getBlockState(poweredCell).is(Blocks.RAIL)
				&& !level.getBlockState(poweredCell.below()).is(Blocks.REDSTONE_BLOCK),
				"without powered rail and redstone stock, the cursor waits and preserves the ordinary track"); checks++;
			int poweredRailBeforeUpgrade = countRailItem(level, ref, Items.POWERED_RAIL);
			int redstoneBlockBeforeUpgrade = countRailItem(level, ref, Items.REDSTONE_BLOCK);
			advanceRailTicks(level, ref, 1, 1);
			check(level.getBlockState(poweredCell).is(Blocks.POWERED_RAIL)
				&& level.getBlockState(poweredCell.below()).is(Blocks.REDSTONE_BLOCK)
				&& countRailItem(level, ref, Items.POWERED_RAIL) == poweredRailBeforeUpgrade - 1
				&& countRailItem(level, ref, Items.REDSTONE_BLOCK) == redstoneBlockBeforeUpgrade - 1,
				"with depot stock, the existing ordinary rail upgrades to powered track and consumes one rail plus redstone block"); checks++;
			for (int attempt = 0; attempt < 128; attempt++) {
				roads = new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
				roads.put("next", 0L);
				ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
				Roads.tick(level, ref, level.getGameTime() + 100L + attempt);
				peerRoads = new HashMap<>(peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
				peerRoads.put("next", 0L);
				peer.bellEntity().setAttached(VillagerPlus.ROADS, peerRoads);
				Roads.tick(level, peer, level.getGameTime() + 100L + attempt);
			}
			roads = ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
			long cursor = roads.getOrDefault(railKey, 0L);
			int rails = countRailsOnRoute(level, roads, routeKey, ref.bell(), peer.bell(), length);
			BlockPos previousRoute = RoadRoute.point(roads, routeKey, ref.bell(), peer.bell(), 2);
			BlockPos previousRail = railCell(level, previousRoute.getX(), previousRoute.getZ(), ref.bell().getY());
			check(level.getBlockState(poweredCell).is(Blocks.POWERED_RAIL)
				&& level.getBlockState(poweredCell.below()).is(Blocks.REDSTONE_BLOCK),
				"scheduled power point remains powered and supported after the bilateral route advances"); checks++;
			String poweredColumn = java.util.stream.IntStream.rangeClosed(ref.bell().getY() - 2, ref.bell().getY() + 3)
				.mapToObj(y -> y + ":" + level.getBlockState(new BlockPos(poweredRoute.getX(), y, poweredRoute.getZ())))
				.collect(java.util.stream.Collectors.joining(","));
			check(cursor > 0 && rails >= 3,
				"metropolis ticks advance the rail cursor (" + cursor + ") and place at least three connected segments (found " + rails
					+ ", poweredCell=" + poweredCell + "/" + (poweredCell == null ? "none" : level.getBlockState(poweredCell))
					+ ", poweredSupport=" + (poweredCell == null ? "none" : level.getBlockState(poweredCell.below()))
					+ ", poweredColumn=" + poweredColumn
					+ ", previousRail=" + previousRail + "/" + (previousRail == null ? "none" : level.getBlockState(previousRail))
					+ "]"); checks++;
			BlockPos startRail = firstRailOnRoute(level, roads, routeKey, ref.bell(), peer.bell(), length);
			check(startRail != null, "corridor exposes a rail cell for freight"); checks++;
			MinecartChest cart = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
			cart.setPos(startRail.getX() + 0.5, startRail.getY() + 0.5, startRail.getZ() + 0.5);
			cart.setNoGravity(true);
			check(level.addFreshEntity(cart), "chest minecart loads on the built track"); checks++;
			carts.add(cart);
			cart.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
			Container depot = ref.chest(level);
			int before = JobUtil.count(depot, s -> s.is(Items.IRON_INGOT));
			var trip = new RailTransport.Trip(ref.bell(), peer.bell(), startRail, startRail, true, false, true, level.getGameTime(), 0);
			cart.setAttached(RailTransport.TRIP, trip);
			RailTransport.transfer(cart, 0, depot, 16);
			check(JobUtil.count(depot, s -> s.is(Items.IRON_INGOT)) == before + 16 && cart.getItem(0).getCount() == 16,
				"freight unloads from the physical chest minecart into communal storage"); checks++;
			return checks;
		} finally {
			carts.forEach(MinecartChest::discard);
			fixtures.forEach(v -> {
				v.discard();
				Villages.forget(v.getUUID());
			});
			Network.save(ref.bellEntity(), oldA);
			Network.save(peer.bellEntity(), oldB);
		}
	}

	private record DepotLayout(BlockPos station, BlockPos granary, BlockPos toolshed) {
		BoundingBox shedBox() {
			int y = station.getY() - 1;
			return new BoundingBox(station.getX() - 4, y, station.getZ() - 1, station.getX() + 4, y + 5, station.getZ() + 1);
		}
	}

	private record RailStockSnapshot(Container container, int slot, ItemStack stack) { }
	private record PlatformLayout(Map<BlockPos, Block> blocks, List<BlockPos> floor) { }

	private static int stationPlatformPaymentChecks(ServerLevel level, VillageRef ref, VillageRef peer,
			Map<String, Long> roads, String routeKey, int stationStart, BlockPos routeAtStation) {
		boolean alongX = RoadRoute.alongX(roads, routeKey, ref.bell(), peer.bell(), stationStart);
		PlatformLayout layout = stationPlatform(routeAtStation, ref.bell().getY() - 1, alongX);
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new LinkedHashMap<>();
		Map<Item, Integer> missing = new LinkedHashMap<>();
		boolean canBuild = true;
		for (var entry : layout.blocks().entrySet()) {
			BlockPos pos = entry.getKey();
			if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != null) canBuild = false;
			var state = level.getBlockState(pos);
			before.put(pos, state);
			if (state.is(entry.getValue())) continue;
			missing.merge(entry.getValue().asItem(), 1, Integer::sum);
			boolean floor = layout.floor().contains(pos);
			boolean replaceable = floor && (state.isAir() || state.canBeReplaced() || state.is(BlockTags.DIRT)
				|| state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SAND));
			if (!state.isAir() && !replaceable) canBuild = false;
		}

		List<RailStockSnapshot> saved = new ArrayList<>();
		List<RailStockSnapshot> originalStock = snapshotRailStores(level, ref);
		for (Item item : platformItems()) saved.addAll(clearRailItem(level, ref, item));
		Map<Item, Integer> baseline = stockCounts(level, ref);
		String railKey = "rail" + peer.bell().asLong();
		Item stationRailItem = stationStart % 6 == 3 ? Items.POWERED_RAIL : Items.RAIL;
		int stationRailBefore = countRailItem(level, ref, stationRailItem);
		BlockPos stationRail = railCell(level, routeAtStation.getX(), routeAtStation.getZ(), ref.bell().getY());
		if (stationRail == null) throw new IllegalStateException("station rail cell disappeared during platform fixture");
		var originalStationRail = level.getBlockState(stationRail);
		var originalStationSupport = level.getBlockState(stationRail.below());
		try {
			check(Roads.advanceMetropolisRailCheck(level, ref, peer, roads, 1),
				"station platform payment probe reaches the approved rail builder");
			ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
			long waitedCursor = roads.getOrDefault(railKey, -1L);
			boolean unchanged = before.entrySet().stream()
				.allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
			if (missing.isEmpty()) {
				check(unchanged && waitedCursor == stationStart + 1L
					&& sameStockCounts(stockCounts(level, ref), baseline)
					&& countRailItem(level, ref, stationRailItem) == stationRailBefore - 1,
					"already-built platform blocks are reused without another platform-material charge");
				return 1;
			}
			check(unchanged && waitedCursor == stationStart
				&& sameStockCounts(stockCounts(level, ref), baseline)
				&& countRailItem(level, ref, stationRailItem) == stationRailBefore - 1,
				"missing platform materials pause the station cursor without partial placement or lost stock");

			restoreRailItems(saved);
			saved.clear();
			baseline = stockCounts(level, ref);
			for (var item : missing.entrySet()) addStockItem(level, ref, item.getKey(), item.getValue());
			Map<Item, Integer> funded = stockCounts(level, ref);
			check(Roads.advanceMetropolisRailCheck(level, ref, peer, roads, 1),
				"station platform retries after public stock is supplied");
			ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
			boolean complete = layout.blocks().entrySet().stream()
				.allMatch(entry -> level.getBlockState(entry.getKey()).is(entry.getValue()));
			long expectedCursor = canBuild ? stationStart + 1L : stationStart;
			Map<Item, Integer> expectedStock = canBuild ? baseline : funded;
			check(complete == canBuild && roads.getOrDefault(railKey, -1L) == expectedCursor
				&& sameStockCounts(stockCounts(level, ref), expectedStock),
				canBuild ? "funded platform is visible and debits only its missing blocks"
					: "blocked platform keeps its paid materials and waits without overwriting the obstruction");
			if (canBuild) {
				Map<Item, Integer> afterBuild = stockCounts(level, ref);
				roads.put(railKey, (long) stationStart);
				ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
				check(Roads.advanceMetropolisRailCheck(level, ref, peer, roads, 1),
					"rail builder reuses the completed platform at its saved cursor");
				ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
				check(sameStockCounts(stockCounts(level, ref), afterBuild),
					"advancing past the platform does not charge its materials twice");
				return 4;
			}
			return 2;
		} finally {
			roads.put(railKey, (long) stationStart);
			ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
			if (originalStationRail.isAir() && level.getBlockState(stationRail).getBlock()
				instanceof net.minecraft.world.level.block.BaseRailBlock) {
				level.setBlock(stationRail, originalStationRail, 3);
				level.setBlock(stationRail.below(), originalStationSupport, 3);
			}
			restoreRailItems(originalStock);
		}
	}

	private static PlatformLayout stationPlatform(BlockPos center, int y, boolean alongX) {
		int sx = alongX ? 0 : 1;
		int sz = alongX ? 1 : 0;
		Map<BlockPos, Block> blocks = new LinkedHashMap<>();
		List<BlockPos> floor = new ArrayList<>();
		for (int along = -2; along <= 2; along++) for (int width = 2; width <= 3; width++) {
			BlockPos pos = new BlockPos(center.getX() + (alongX ? along : width * sx), y,
				center.getZ() + (alongX ? width * sz : along));
			blocks.put(pos, Blocks.STONE_BRICKS);
			floor.add(pos);
		}
		for (int along = -2; along <= 2; along += 4) {
			BlockPos post = new BlockPos(center.getX() + (alongX ? along : 3 * sx), y + 1,
				center.getZ() + (alongX ? 3 * sz : along));
			for (int height = 0; height < 3; height++) blocks.put(post.above(height), Blocks.SPRUCE_FENCE);
		}
		for (int along = -2; along <= 2; along++) for (int width = 2; width <= 3; width++) {
			BlockPos roof = new BlockPos(center.getX() + (alongX ? along : width * sx), y + 4,
				center.getZ() + (alongX ? width * sz : along));
			blocks.put(roof, Blocks.SPRUCE_SLAB);
		}
		blocks.put(new BlockPos(center.getX() + (alongX ? 0 : 3 * sx), y + 1,
			center.getZ() + (alongX ? 3 * sz : 0)), Blocks.SPRUCE_STAIRS);
		blocks.put(new BlockPos(center.getX() + (alongX ? 0 : 2 * sx), y + 3,
			center.getZ() + (alongX ? 2 * sz : 0)), Blocks.LANTERN);
		return new PlatformLayout(Map.copyOf(blocks), List.copyOf(floor));
	}

	private static List<Item> platformItems() {
		return List.of(Blocks.STONE_BRICKS.asItem(), Blocks.SPRUCE_FENCE.asItem(), Blocks.SPRUCE_SLAB.asItem(),
			Blocks.SPRUCE_STAIRS.asItem(), Blocks.LANTERN.asItem());
	}

	private static List<RailStockSnapshot> snapshotRailStores(ServerLevel level, VillageRef ref) {
		List<RailStockSnapshot> saved = new ArrayList<>();
		for (Container container : RailResources.stores(level, ref)) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				saved.add(new RailStockSnapshot(container, slot, stack.copy()));
			}
		}
		return saved;
	}

	private static Map<Item, Integer> stockCounts(ServerLevel level, VillageRef ref) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		for (Item item : platformItems()) counts.put(item, countRailItem(level, ref, item));
		return counts;
	}

	private static boolean sameStockCounts(Map<Item, Integer> actual, Map<Item, Integer> expected) {
		return platformItems().stream().allMatch(item -> actual.getOrDefault(item, 0).equals(expected.getOrDefault(item, 0)));
	}

	private static void addStockItem(ServerLevel level, VillageRef ref, Item item, int amount) {
		int remaining = amount;
		for (Container box : RailResources.stores(level, ref)) {
			remaining = addStockItem(box, item, remaining);
			if (remaining <= 0) return;
		}
		throw new IllegalStateException("fixture stores have no room for platform stock: " + item);
	}

	private static int addStockItem(Container box, Item item, int amount) {
		int initial = amount;
		int remaining = amount;
		for (int slot = 0; slot < box.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.is(item) || stack.getCount() >= stack.getMaxStackSize()) continue;
			int add = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
			stack.grow(add);
			remaining -= add;
		}
		for (int slot = 0; slot < box.getContainerSize() && remaining > 0; slot++) {
			if (!box.getItem(slot).isEmpty()) continue;
			int add = Math.min(remaining, ItemStack.EMPTY.getMaxStackSize());
			box.setItem(slot, new ItemStack(item, add));
			remaining -= add;
		}
		if (remaining != initial) box.setChanged();
		return remaining;
	}

	private static int countRailItem(ServerLevel level, VillageRef ref, Item item) {
		int count = 0;
		for (Container container : RailResources.stores(level, ref)) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
			}
		}
		return count;
	}

	private static List<RailStockSnapshot> clearRailItem(ServerLevel level, VillageRef ref, Item item) {
		List<RailStockSnapshot> saved = new ArrayList<>();
		for (Container container : RailResources.stores(level, ref)) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || !stack.is(item)) continue;
				saved.add(new RailStockSnapshot(container, slot, stack.copy()));
				container.setItem(slot, ItemStack.EMPTY);
				container.setChanged();
			}
		}
		return saved;
	}

	private static void restoreRailItems(List<RailStockSnapshot> saved) {
		for (RailStockSnapshot entry : saved) {
			entry.container().setItem(entry.slot(), entry.stack().copy());
			entry.container().setChanged();
		}
	}

	private static void advanceRailTicks(ServerLevel level, VillageRef ref, int attempts, long offset) {
		for (int attempt = 0; attempt < attempts; attempt++) {
			Map<String, Long> roads = new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
			roads.put("next", 0L);
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roads));
			Roads.tick(level, ref, level.getGameTime() + 1000L + offset + attempt);
		}
	}

	private static void linkMetropolis(ServerLevel level, VillageRef ref, VillageRef peer) {
		Network.save(ref.bellEntity(), new TradeNetwork(List.of(), List.of(
			new TradeNetwork.Link(Network.dim(level), peer.bell(), "Metro peer", Roads.METROPOLIS, true, 0, 0)), Map.of(), 0, 0));
		Network.save(peer.bellEntity(), new TradeNetwork(List.of(), List.of(
			new TradeNetwork.Link(Network.dim(level), ref.bell(), "Metro home", Roads.METROPOLIS, true, 0, 0)), Map.of(), 0, 0));
		String approval = Network.pairKey(ref, peer, "large_project_approval");
		Network.save(ref.bellEntity(), Network.of(ref.bellEntity()).withCooldown(approval, 1L));
		Network.save(peer.bellEntity(), Network.of(peer.bellEntity()).withCooldown(approval, 1L));
	}

	private static void retainResidents(ServerLevel level, List<Villager> residents) {
		for (Villager villager : residents) {
			if (!villager.isAlive() || villager.isRemoved()) {
				throw new IllegalStateException("resident fixture dropped before census at " + villager.blockPosition().toShortString());
			}
			if (level.getEntity(villager.getUUID()) != villager) {
				level.getChunkAt(villager.blockPosition());
				if (!level.addFreshEntity(villager)) {
					throw new IllegalStateException("resident fixture left the level before census at " + villager.blockPosition().toShortString());
				}
			}
		}
	}

	private static void forceSettlement(ServerLevel level, BlockPos bell) {
		int cx = bell.getX() >> 4;
		int cz = bell.getZ() >> 4;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			level.setChunkForced(cx + dx, cz + dz, true);
			level.getChunk(cx + dx, cz + dz);
		}
	}

	private static void clearBoundResidents(ServerLevel level, VillageRef ref) {
		java.util.List<Villager> stale = new java.util.ArrayList<>();
		for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
			if (!(entity instanceof Villager villager) || !villager.isAlive()) continue;
			var meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
			boolean bound = meeting.isPresent() && meeting.get().pos().equals(ref.bell());
			boolean nearby = villager.blockPosition().distSqr(ref.bell()) <= (long) de.villagerplus.Config.VILLAGE_RADIUS * de.villagerplus.Config.VILLAGE_RADIUS;
			if (bound || nearby) stale.add(villager);
		}
		for (Villager villager : stale) {
			villager.discard();
			Villages.forget(villager.getUUID());
		}
	}

	private static List<Villager> spawnResidents(ServerLevel level, VillageRef ref, List<Villager> fixtures) {
		List<Villager> out = new ArrayList<>();
		for (int i = 0; i < Roads.RAIL_POP; i++) {
			int chunkX = ref.bell().getX() >> 4;
			int chunkZ = ref.bell().getZ() >> 4;
			BlockPos spawn = new BlockPos((chunkX << 4) + 2 + (i % 4), ref.bell().getY(), (chunkZ << 4) + 2 + (i / 4));
			level.getChunkAt(spawn);
			level.setBlock(spawn, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(spawn.above(), Blocks.AIR.defaultBlockState(), 3);
			Villager villager = EntityTypes.VILLAGER.spawn(level, spawn, EntitySpawnReason.COMMAND);
			if (villager == null) throw new IllegalStateException("resident fixture did not spawn");
			if (level.getEntity(villager.getUUID()) != villager) {
				level.getChunkAt(spawn);
				if (!level.addFreshEntity(villager)) throw new IllegalStateException("resident fixture was not tracked at " + spawn.toShortString());
			}
			villager.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
			villager.addTag("vp090_fixture");
			villager.setInvulnerable(true);
			villager.setNoAi(true);
			villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
			villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), ref.bell().north(4 + i)));
			level.getChunkAt(ref.bell());
			Villages.noteChunk(level.getChunkAt(ref.bell()));
			Villages.find(level, villager);
			fixtures.add(villager);
			out.add(villager);
		}
		return out;
	}

	private static DepotLayout layoutDepots(ServerLevel level, VillageRef ref, Container stock) {
		BlockPos granary = ref.bell().north(16);
		BlockPos toolshed = ref.bell().north(24);
		BlockPos storage = ref.bell().north(32);
		BlockPos kitchen = ref.bell().north(40);
		// Keep synthetic depots and their public chests clear of the east-west
		// corridor so the A* route planner sees the prepared, flat test surface.
		BlockPos school = ref.bell().south(16);
		// Station sits beside the eastbound centerline so its chest is not the rail bed.
		BlockPos station = ref.bell().north(8);
		preparePad(level, granary);
		preparePad(level, toolshed);
		preparePad(level, storage);
		preparePad(level, kitchen);
		preparePad(level, school);
		preparePad(level, station);
		registerChest(level, ref, Blueprints.GRANARY, granary, granary.east(2));
		registerChest(level, ref, Blueprints.TOOLSHED, toolshed, toolshed.east(2));
		registerChest(level, ref, Blueprints.STORAGE, storage, storage.east(2));
		registerChest(level, ref, Blueprints.KITCHEN, kitchen, kitchen.east(2));
		registerChest(level, ref, Blueprints.SCHOOL, school, school.east(2));
		registerChest(level, ref, Blueprints.STATION, station, station.east(2));
		ref.data().extra.lager = java.util.Optional.of(storage);
		ref.data().extra.lagerChests.add(storage.east(2));
		ref.data().extra.school = java.util.Optional.of(school);
		ref.dirty();
		stockMetallurgy(stock);
		return new DepotLayout(station, granary, toolshed);
	}

	private static void preparePad(ServerLevel level, BlockPos origin) {
		level.getChunkAt(origin);
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			BlockPos p = origin.offset(dx, 0, dz);
			level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(p.above(2), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void registerChest(ServerLevel level, VillageRef ref, String type, BlockPos origin, BlockPos chestPos) {
		Specials.set(ref, type, origin);
		level.setBlock(chestPos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
	}

	private static void moveCenterChest(ServerLevel level, VillageRef ref) {
		BlockPos old = ref.data().chest.orElseThrow();
		BlockPos moved = ref.bell().north(3);
		level.setBlock(old, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(old.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(moved.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(moved, Blocks.CHEST.defaultBlockState(), 3);
		ref.data().chest = java.util.Optional.of(moved);
		ref.dirty();
	}

	private static void stockMetallurgy(Container box) {
		box.clearContent();
		box.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
		box.setItem(1, new ItemStack(Items.GOLD_INGOT, 16));
		box.setItem(2, new ItemStack(Items.REDSTONE, 32));
	}

	private static void stockRailsInStation(ServerLevel level, BlockPos stationOrigin) {
		BlockPos chestPos = stationOrigin.east(2);
		var box = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, chestPos);
		if (box == null) throw new IllegalStateException("station chest missing at " + chestPos);
		addRails(box, 48);
		box.setItem(1, new ItemStack(Items.POWERED_RAIL, 8));
		box.setItem(2, new ItemStack(Items.REDSTONE_BLOCK, 8));
	}

	private static void addRails(Container box, int count) {
		for (int slot = 0; slot < box.getContainerSize() && count > 0; slot++) {
			ItemStack existing = box.getItem(slot);
			if (!existing.isEmpty() && !existing.is(Items.RAIL)) continue;
			int room = existing.isEmpty() ? 64 : existing.getMaxStackSize() - existing.getCount();
			if (room <= 0) continue;
			int stack = Math.min(room, count);
			if (existing.isEmpty()) box.setItem(slot, new ItemStack(Items.RAIL, stack));
			else existing.grow(stack);
			count -= stack;
		}
		if (count > 0) throw new IllegalStateException("material chest has no room for rail stock (size=" + box.getContainerSize() + ")");
	}

	private static void paveCorridor(ServerLevel level, Map<String, Long> roads, String routeKey, BlockPos a, BlockPos b, int length) {
		int floorY = a.getY() - 1;
		for (int cursor = 0; cursor <= length / 2 + 1; cursor++) {
			BlockPos route = RoadRoute.point(roads, routeKey, a, b, cursor);
			boolean alongX = RoadRoute.alongX(roads, routeKey, a, b, cursor);
			for (int side = -1; side <= 1; side++) {
				int x = route.getX() + (alongX ? 0 : side);
				int z = route.getZ() + (alongX ? side : 0);
				BlockPos floor = new BlockPos(x, floorY, z);
				if (Roads.isProtectedLaneCell(level, floor)) continue;
				level.getChunkAt(floor);
				level.setBlock(floor.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(floor, (side == 0 ? Blocks.GRAVEL : Blocks.COBBLESTONE).defaultBlockState(), 3);
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	/** Gives the rail integration fixture a level three-lane approach independent of its world seed. */
	private static void prepareFlatRoute(ServerLevel level, BlockPos a, BlockPos b) {
		int minX = Math.min(a.getX(), b.getX()) - 12;
		int maxX = Math.max(a.getX(), b.getX()) + 12;
		for (int x = minX; x <= maxX; x++) for (int z = a.getZ() - 4; z <= a.getZ() + 4; z++) {
			level.getChunkAt(new BlockPos(x, a.getY(), z));
			int previousSurface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				x, z) - 1;
			level.setBlock(new BlockPos(x, a.getY() - 2, z), Blocks.STONE.defaultBlockState(), 3);
			// The route planner only accepts replaceable/repairable natural ground
			// here; a stone surface makes every synthetic lane look obstructed.
			level.setBlock(new BlockPos(x, a.getY() - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int y = a.getY(); y <= Math.max(a.getY() + 5, previousSurface + 2); y++) {
				level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	private static int countRailsOnRoute(ServerLevel level, Map<String, Long> roads, String routeKey, BlockPos a, BlockPos b, int length) {
		int rails = 0;
		for (int cursor = 0; cursor <= length / 2 + 1; cursor++) {
			BlockPos route = RoadRoute.point(roads, routeKey, a, b, cursor);
			BlockPos rail = railCell(level, route.getX(), route.getZ(), a.getY());
			if (rail != null && level.getBlockState(rail).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) rails++;
		}
		return rails;
	}

	private static BlockPos firstRailOnRoute(ServerLevel level, Map<String, Long> roads, String routeKey, BlockPos a, BlockPos b, int length) {
		for (int cursor = 0; cursor <= length / 2 + 1; cursor++) {
			BlockPos route = RoadRoute.point(roads, routeKey, a, b, cursor);
			BlockPos rail = railCell(level, route.getX(), route.getZ(), a.getY());
			if (rail != null && level.getBlockState(rail).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) return rail;
		}
		return null;
	}

	private static BlockPos railCell(ServerLevel level, int x, int z, int platformY) {
		int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int high = Math.max(top, platformY + 1);
		int low = Math.min(top - 3, platformY - 4);
		for (int y = high; y >= low; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) return pos;
			if (railRoad(state)) return pos.above();
		}
		return null;
	}

	private static boolean railRoad(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(Blocks.GRAVEL) || state.is(Blocks.SMOOTH_SANDSTONE) || state.is(Blocks.SPRUCE_PLANKS);
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Rail end-to-end: " + message);
		System.out.println("RAIL E2E PASS " + message);
	}
}
