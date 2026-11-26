package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.RailTransport.Trip;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.Heightmap;

/** Verifies that the registered scheduler carries named real inventory over a physical freight route. */
public final class RailTransportDispatchChecks {
	private static final long DISPATCH_PERIOD = 200;
	private static final long TIMEOUT = 1100;
	private static final Map<ServerLevel, Fixture> PENDING = new IdentityHashMap<>();
	private static boolean observerRegistered;

	private RailTransportDispatchChecks() { }

	/**
	 * First call prepares a small, fully approved rail link and returns -1. Call again after server ticks; the
	 * registered END_LEVEL_TICK handler is the only code that may create the tested train.
	 */
	public static int run(ServerLevel level, BlockPos at) {
		registerObserver();
		Fixture fixture = PENDING.get(level);
		if (fixture == null) {
			fixture = new Fixture(level.getGameTime());
			PENDING.put(level, fixture);
			// No player attends these far-away settlements. Production only simulates a village that a player attends
			// or that is inside the one-village load budget (Anchor), and its budget release frees /forceload
			// chunks, so the fixture holds both settlements and the track with expiring work leases instead.
			fixture.leased = leaseChunks(at, at.east(64));
			leaseChunks(level, fixture);
			return -1;
		}
		if (!fixture.prepared) {
			// Build on a later tick so the entity sections of the leased chunks are loaded before residents are spawned.
			leaseChunks(level, fixture);
			for (net.minecraft.world.level.ChunkPos chunk : fixture.leased) {
				if (!de.villagerplus.world.LoadedColumns.readyChunk(level, chunk.x(), chunk.z())
					|| !level.isPositionEntityTicking(chunk.getMiddleBlockPosition(level.getMinY() + 64))) return -1;
			}
			try {
				prepare(level, fixture, at);
			} catch (RuntimeException error) {
				PENDING.remove(level);
				throw error;
			}
			fixture.prepared = true;
			return -1;
		}
		leaseChunks(level, fixture);
		if (fixture.observation == null) {
			releaseFuelAfterObservedWait(level, fixture);
			if (level.getGameTime() - fixture.started < TIMEOUT) return -1;
			boolean homeReady = Roads.railReady(level, fixture.home);
			boolean peerReady = Roads.railReady(level, fixture.peer);
			boolean approved = Network.projectApproved(fixture.home, fixture.peer);
			String homeGates = readinessDetails(level, fixture.home);
			String peerGates = readinessDetails(level, fixture.peer);
			cleanup(level, fixture);
			PENDING.remove(level);
			throw new IllegalStateException("Rail dispatch: named freight did not reach the destination depot over the normal physical route; "
				+ "railReady=" + homeReady + "/" + peerReady + ", networkApproved=" + approved
				+ ", namedLoaded=" + fixture.namedCargoLoaded + ", fuelWaitObserved=" + fixture.fuelWaitObserved
				+ "; home{" + homeGates + "}; peer{" + peerGates + "}");
		}

		Observation observed = fixture.observation;
		Trip trip = observed.trip();
		try {
			check(observed.tick() % 20 == 0,
				"the destination depot changes only on the scheduled dock transaction");
			check(fixture.dispatchTick % DISPATCH_PERIOD == 0,
				"the cargo route begins at the normal 200-tick server dispatch boundary");
			check(observed.freightCart() && trip != null && trip.home().equals(fixture.home.bell())
				&& trip.other().equals(fixture.peer.bell()) && !trip.toOther() && !trip.travelling() && trip.unloading(),
				"a real chest minecart physically reaches the peer station and enters its saved unload phase");
			check(fixture.namedCargoLoaded && observed.firstLoadedItem() == Items.IRON_INGOT
				&& observed.targetIronAfter() > fixture.targetIronBefore,
				"the destination's named iron demand loads before generic surplus and arrives in public depot stock");
			check(fixture.fuelWaitObserved && fixture.fuelReleased,
				"the same persistent freight trip waits for missing public fuel and resumes when it is supplied");
			check(observed.routeId().equals(observed.cartId()) && observed.stationStockBefore() == 1
				&& observed.stationStockAfter() == 0 && observed.routeId().equals(fixture.initialRouteId)
				&& observed.sourceIronAfter() < fixture.sourceIronBefore
				&& observed.sourceIronAfter() + observed.targetIronAfter() == fixture.sourceIronBefore + fixture.targetIronBefore,
				"the same reserved cart consumes real depot cargo and conserves the exact item balance");
			return 5;
		} finally {
			cleanup(level, fixture);
			PENDING.remove(level);
		}
	}

	private static void registerObserver() {
		if (observerRegistered) return;
		observerRegistered = true;
		// RailTransport.register() installs its product listener during mod initialization, before this test command.
		ServerTickEvents.END_LEVEL_TICK.register(RailTransportDispatchChecks::observeNormalDispatch);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
	}

	private static void observeNormalDispatch(ServerLevel level) {
		Fixture fixture = PENDING.get(level);
		if (fixture == null || !fixture.prepared || fixture.observation != null) return;
		long now = level.getGameTime();
		if (now < fixture.firstBoundary) return;
		String key = Long.toString(fixture.peer.bell().asLong());
		Map<String, java.util.UUID> routes = fixture.home.bellEntity().getAttachedOrElse(RailTransport.ROUTES, Map.of());
		java.util.UUID id = routes.get(key);
		if (id == null || !(level.getEntity(id) instanceof AbstractMinecart cart)) return;
		if (fixture.initialRouteId == null) {
			fixture.initialRouteId = id;
			fixture.dispatchTick = now;
		}
		Trip trip = cart.getAttached(RailTransport.TRIP);
		if (cart instanceof MinecartChest freight) {
			if (fixture.firstLoadedItem == null) {
				for (int slot = 0; slot < freight.getContainerSize(); slot++) {
					ItemStack stack = freight.getItem(slot);
					if (!stack.isEmpty()) { fixture.firstLoadedItem = stack.getItem(); break; }
				}
			}
			if (count(freight, Items.IRON_INGOT) > 0) fixture.namedCargoLoaded = true;
		}
		int targetIron = StockPolicy.communalCount(level, fixture.peer, Items.IRON_INGOT);
		if (targetIron <= fixture.targetIronBefore) return;
		fixture.observation = new Observation(now, id, cart instanceof MinecartChest, trip, cart.getUUID(),
			count(fixture.stationInventory, Items.CHEST_MINECART), fixture.stationStockBefore,
			StockPolicy.communalCount(level, fixture.home, Items.IRON_INGOT), targetIron, fixture.firstLoadedItem);
	}

	/** Make fuel temporarily unavailable, then restore it only after this real cart has saved cargo at the dock. */
	private static void releaseFuelAfterObservedWait(ServerLevel level, Fixture fixture) {
		if (fixture.fuelReleased) return;
		String key = Long.toString(fixture.peer.bell().asLong());
		java.util.UUID id = fixture.home.bellEntity().getAttachedOrElse(RailTransport.ROUTES, Map.of()).get(key);
		if (id == null || !(level.getEntity(id) instanceof MinecartChest freight)) return;
		Trip trip = freight.getAttached(RailTransport.TRIP);
		if (trip == null || !trip.toOther() || trip.travelling() || trip.unloading()
			|| level.getGameTime() <= trip.next() || trip.fuelTicks() > 0
			|| count(freight, Items.IRON_INGOT) <= 0) return;
		Container source = fixture.home.chest(level);
		if (source == null) throw new IllegalStateException("Rail dispatch check lost its registered public home depot");
		int slot = -1;
		for (int index = 0; index < source.getContainerSize(); index++) if (source.getItem(index).isEmpty()) { slot = index; break; }
		if (slot < 0) throw new IllegalStateException("Rail dispatch check cannot add real public charcoal for the waiting freight trip");
		fixture.fuelWaitObserved = true;
		source.setItem(slot, new ItemStack(Items.CHARCOAL));
		source.setChanged();
		fixture.fuelReleased = true;
	}

	private static void prepare(ServerLevel level, Fixture fixture, BlockPos requested) {
		int x = requested.getX();
		int z = requested.getZ();
		level.getChunkAt(requested);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos homeBell = new BlockPos(x, y, z);
		BlockPos peerBell = homeBell.east(64);
		level.getChunkAt(peerBell);
		try {
			fixture.home = createVillage(level, fixture, homeBell, true);
			fixture.peer = createVillage(level, fixture, peerBell, false);
			stockNamedCargo(level, fixture);
			linkAndApprove(level, fixture.home, fixture.peer);
			placeTrack(level, fixture, homeBell, peerBell);
			spawnResidents(level, fixture, fixture.home);
			spawnResidents(level, fixture, fixture.peer);
			if (fixture.stationInventory == null) {
				throw new IllegalStateException("Rail dispatch check could not find the prepared home station chest");
			}
			fixture.stationStockBefore = count(fixture.stationInventory, Items.CHEST_MINECART);
			boolean homeReady = Roads.railReady(level, fixture.home);
			boolean peerReady = Roads.railReady(level, fixture.peer);
			if (fixture.stationStockBefore != 1 || !homeReady || !peerReady
				|| !Network.projectApproved(fixture.home, fixture.peer)
				|| !permittedAndConnected(level, fixture.home, fixture.peer)
				|| fixture.sourceIronBefore <= 32 || fixture.targetIronBefore >= 32) {
				throw new IllegalStateException("Rail dispatch fixture was not eligible for service"
					+ " [cart=" + fixture.stationStockBefore + ", iron=" + fixture.sourceIronBefore + "->" + fixture.targetIronBefore
					+ ", ready=" + homeReady + "/" + peerReady
					+ ", approved=" + Network.projectApproved(fixture.home, fixture.peer)
					+ "]; home{" + readinessDetails(level, fixture.home) + "}; peer{" + readinessDetails(level, fixture.peer) + "}");
			}
			fixture.started = level.getGameTime();
			fixture.firstBoundary = (fixture.started / DISPATCH_PERIOD + 1) * DISPATCH_PERIOD;
		} catch (RuntimeException error) {
			cleanup(level, fixture);
			throw error;
		}
	}

	static List<net.minecraft.world.level.ChunkPos> leaseChunks(BlockPos home, BlockPos peer) {
		List<net.minecraft.world.level.ChunkPos> chunks = new ArrayList<>();
		for (int cx = (home.getX() - 24) >> 4; cx <= (peer.getX() + 24) >> 4; cx++) {
			for (int cz = (home.getZ() - 24) >> 4; cz <= (home.getZ() + 24) >> 4; cz++) {
				chunks.add(new net.minecraft.world.level.ChunkPos(cx, cz));
			}
		}
		return chunks;
	}

	/** Renews the expiring entity-ticking lease on every fixture chunk; it lapses by itself after cleanup. */
	static void leaseChunks(ServerLevel level, Fixture fixture) {
		for (net.minecraft.world.level.ChunkPos chunk : fixture.leased) {
			level.getChunkSource().addTicketWithRadius(Anchor.VILLAGER_WORK, chunk, 2);
		}
	}

	private static void stockNamedCargo(ServerLevel level, Fixture fixture) {
		Container source = fixture.home.chest(level);
		if (source == null) throw new IllegalStateException("Rail dispatch fixture has no real public source depot");
		// Put generic surplus before the named cargo in chest slot order. The requested iron must still board first.
		source.setItem(3, new ItemStack(Items.OAK_PLANKS, 64));
		source.setItem(4, new ItemStack(Items.IRON_INGOT, 64));
		source.setChanged();
		fixture.peer.data().wanted.clear();
		fixture.peer.data().wanted.add("minecraft:iron_ingot#32");
		fixture.peer.dirty();
		fixture.sourceIronBefore = StockPolicy.communalCount(level, fixture.home, Items.IRON_INGOT);
		fixture.targetIronBefore = StockPolicy.communalCount(level, fixture.peer, Items.IRON_INGOT);
	}

	static VillageRef createVillage(ServerLevel level, Fixture fixture, BlockPos bell, boolean home) {
		place(level, fixture, bell.below(), Blocks.STONE.defaultBlockState());
		place(level, fixture, bell, Blocks.BELL.defaultBlockState());
		if (!(level.getBlockEntity(bell) instanceof BellBlockEntity entity)) {
			throw new IllegalStateException("Rail dispatch fixture could not create a village bell");
		}
		VillageData data = new VillageData();
		// Keep the registered center chest off the east-west track corridor. At the peer endpoint,
		// west(3) is exactly the final rail cell and would be overwritten later by placeTrack().
		BlockPos centerChest = bell.west(3).north(3);
		placeChest(level, fixture, centerChest);
		Container centerInventory = HopperBlockEntity.getContainerAt(level, centerChest);
		if (centerInventory == null || !Lager.publicStorageAt(level, centerChest)) {
			throw new IllegalStateException("Rail dispatch fixture could not create an unowned public village-center chest at "
				+ centerChest.toShortString());
		}
		data.chest = Optional.of(centerChest);
		BlockPos warehouse = bell.north(18);
		BlockPos warehouseChest = warehouse.east(2);
		placeChest(level, fixture, warehouseChest);
		data.extra.lager = Optional.of(warehouse);
		data.extra.lagerChests.add(warehouseChest);
		Container warehouseInventory = HopperBlockEntity.getContainerAt(level, warehouseChest);
		if (warehouseInventory == null || !Lager.publicStorageAt(level, warehouseChest)) {
			throw new IllegalStateException("Rail dispatch fixture could not create an unowned registered warehouse chest at "
				+ warehouseChest.toShortString());
		}
		entity.setAttached(VillagerPlus.VILLAGE, data);
		entity.setAttached(VillagerPlus.NETWORK, TradeNetwork.create(bell));
		VillageRef ref = new VillageRef(bell, data, entity);
		if (!RailResources.hasCentralStorage(level, ref)) {
			throw new IllegalStateException("Rail dispatch fixture central storage is not discoverable from its actual registered chest positions"
				+ " [center=" + centerChest.toShortString() + ", warehouse=" + warehouse.toShortString()
				+ ", registered=" + data.extra.lagerChests + "]");
		}
		stockMetallurgy(centerInventory, warehouseInventory);
		if (!RailResources.hasMetallurgyStock(level, ref)) {
			throw new IllegalStateException("Rail dispatch fixture's real center/warehouse stock does not satisfy the metallurgy gate"
				+ " [centerIron=" + count(centerInventory, Items.IRON_INGOT) + ", warehouseIron=" + count(warehouseInventory, Items.IRON_INGOT)
				+ ", centerGold=" + count(centerInventory, Items.GOLD_INGOT) + ", warehouseGold=" + count(warehouseInventory, Items.GOLD_INGOT)
				+ ", centerRedstone=" + count(centerInventory, Items.REDSTONE) + ", warehouseRedstone=" + count(warehouseInventory, Items.REDSTONE) + "]");
		}

		BlockPos granary = bell.north(8);
		BlockPos toolshed = bell.south(8);
		BlockPos station = home ? bell.east(6) : bell.west(6);
		BlockPos kitchen = home ? bell.west(8) : bell.east(8);
		BlockPos school = bell.west(16);
		level.getChunkAt(granary);
		level.getChunkAt(toolshed);
		level.getChunkAt(station);
		level.getChunkAt(kitchen);
		level.getChunkAt(school);
		Specials.set(ref, Blueprints.GRANARY, granary);
		Specials.set(ref, Blueprints.TOOLSHED, toolshed);
		Specials.set(ref, Blueprints.STATION, station);
		Specials.set(ref, Blueprints.KITCHEN, kitchen);
		data.extra.school = Optional.of(school);
		placeChest(level, fixture, granary.east(2));
		placeChest(level, fixture, toolshed.east(2));
		BlockPos stationChest = station.south(2);
		placeChest(level, fixture, stationChest);
		Container stationStock = HopperBlockEntity.getContainerAt(level, stationChest);
		if (stationStock == null) {
			throw new IllegalStateException("Rail dispatch fixture could not create a public station stock container");
		}
		if (home) {
			fixture.stationInventory = stationStock;
			fixture.stationInventory.setItem(0, new ItemStack(Items.CHEST_MINECART));
			fixture.stationInventory.setChanged();
			fixture.stationStockBefore = 1;
		}
		// Dispatch requires locally registered central storage and real metallurgy stock at both endpoints.
		// The station chest itself only supplies the physical vehicle consumed by this test.
		stationStock.setChanged();
		return ref;
	}

	static void stockMetallurgy(Container center, Container warehouse) {
		center.setItem(0, new ItemStack(Items.IRON_INGOT, RailResources.MIN_IRON_INGOTS / 2));
		center.setItem(1, new ItemStack(Items.GOLD_INGOT, RailResources.MIN_GOLD_INGOTS / 2));
		center.setItem(2, new ItemStack(Items.REDSTONE, RailResources.MIN_REDSTONE / 2));
		warehouse.setItem(0, new ItemStack(Items.IRON_INGOT, RailResources.MIN_IRON_INGOTS / 2));
		warehouse.setItem(1, new ItemStack(Items.GOLD_INGOT, RailResources.MIN_GOLD_INGOTS / 2));
		warehouse.setItem(2, new ItemStack(Items.REDSTONE, RailResources.MIN_REDSTONE / 2));
		center.setChanged();
		warehouse.setChanged();
	}

	static void linkAndApprove(ServerLevel level, VillageRef home, VillageRef peer) {
		String dimension = Network.dim(level);
		TradeNetwork.Link outward = new TradeNetwork.Link(dimension, peer.bell(), "Dispatch fixture", Roads.METROPOLIS, true, Roads.RAIL_POP, 0);
		TradeNetwork.Link inward = new TradeNetwork.Link(dimension, home.bell(), "Dispatch fixture", Roads.METROPOLIS, true, Roads.RAIL_POP, 0);
		TradeNetwork homeNetwork = Network.toggleProjectVote(Network.of(home.bellEntity()).withLinks(List.of(outward)), home.bell(), peer.bell());
		TradeNetwork peerNetwork = Network.toggleProjectVote(Network.of(peer.bellEntity()).withLinks(List.of(inward)), peer.bell(), home.bell());
		Network.save(home.bellEntity(), homeNetwork);
		Network.save(peer.bellEntity(), peerNetwork);
	}

	static void placeTrack(ServerLevel level, Fixture fixture, BlockPos home, BlockPos peer) {
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		for (int x = home.getX() + 3; x <= peer.getX() - 3; x++) {
			BlockPos pos = new BlockPos(x, home.getY(), home.getZ());
			place(level, fixture, pos.below(), Blocks.STONE.defaultBlockState());
			place(level, fixture, pos, rail);
			place(level, fixture, pos.above(), Blocks.AIR.defaultBlockState());
			place(level, fixture, pos.above(2), Blocks.AIR.defaultBlockState());
		}
	}

	static void spawnResidents(ServerLevel level, Fixture fixture, VillageRef village) {
		for (int i = 0; i < Roads.RAIL_POP; i++) {
			BlockPos spawn = village.bell().offset((i % 4) - 2, 0, 5 + (i / 4) * 2);
			Villager villager = EntityTypes.VILLAGER.spawn(level, spawn, EntitySpawnReason.COMMAND);
			if (villager == null) throw new IllegalStateException("Rail dispatch check could not spawn resident " + i);
			villager.addTag("vp090_fixture");
			villager.setNoAi(true);
			villager.setInvulnerable(true);
			Villages.bindResident(level, village, villager, null);
			fixture.residents.add(villager);
		}
	}

	static boolean permittedAndConnected(ServerLevel level, VillageRef home, VillageRef peer) {
		if (!RailTransport.permitted(level, home, peer)) return false;
		BlockPos homeStation = Specials.get(home, Blueprints.STATION);
		BlockPos peerStation = Specials.get(peer, Blueprints.STATION);
		for (int x = home.bell().getX() + 3; x <= peer.bell().getX() - 3; x++) {
			if (!RailTransport.safeRail(level, new BlockPos(x, home.bell().getY(), home.bell().getZ()))) return false;
		}
		return homeStation != null && peerStation != null;
	}

	static String readinessDetails(ServerLevel level, VillageRef village) {
		int population = Villages.residents(level, village, de.villagerplus.Config.VILLAGE_RADIUS, Villager::isAlive).size();
		boolean centralStorage = RailResources.hasCentralStorage(level, village);
		boolean metallurgyStock = RailResources.hasMetallurgyStock(level, village);
		boolean granary = RailResources.hasDepot(level, village, Blueprints.GRANARY);
		boolean toolshed = RailResources.hasDepot(level, village, Blueprints.TOOLSHED);
		boolean station = RailResources.hasDepot(level, village, Blueprints.STATION);
		BlockPos kitchenPos = Specials.get(village, Blueprints.KITCHEN);
		boolean kitchen = Specials.has(village, Blueprints.KITCHEN) && kitchenPos != null && level.hasChunkAt(kitchenPos);
		BlockPos schoolPos = village.data().extra.school.orElse(null);
		boolean school = schoolPos != null && level.hasChunkAt(schoolPos);
		boolean progress = granary && toolshed && station && kitchen && school;
		boolean bellValid = level.hasChunkAt(village.bell()) && level.getBlockEntity(village.bell()) == village.bellEntity()
			&& village.bellEntity().getAttached(VillagerPlus.VILLAGE) == village.data();
		return "bellValid=" + bellValid + ", population=" + population + "/" + Roads.RAIL_POP
			+ ", centralStorage=" + centralStorage + ", metallurgyStock=" + metallurgyStock
			+ ", railProgressReady=" + progress + "[granary=" + granary + ", toolshed=" + toolshed
			+ ", station=" + station + ", kitchen=" + kitchen + ", school=" + school + "]";
	}

	static void placeChest(ServerLevel level, Fixture fixture, BlockPos pos) {
		place(level, fixture, pos.below(), Blocks.STONE.defaultBlockState());
		// The fixture can revisit a coordinate containing a private/adjacent generated chest from an earlier run.
		// Clear neighboring chest blocks through the same snapshot path so this one is an isolated public container.
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(direction);
			if (level.getBlockState(neighbor).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
				place(level, fixture, neighbor, Blocks.AIR.defaultBlockState());
			}
		}
		place(level, fixture, pos, Blocks.CHEST.defaultBlockState());
	}

	static void place(ServerLevel level, Fixture fixture, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		fixture.cells.computeIfAbsent(pos.immutable(), p -> {
			BlockEntity current = level.getBlockEntity(p);
			return new Cell(level.getBlockState(p), current == null ? null : current.saveWithFullMetadata(level.registryAccess()));
		});
		BlockState previous = level.getBlockState(pos);
		if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
		// Force a fresh block entity even when the saved world already has the same block (e.g. a private chest).
		if (!previous.isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(pos, state, 3);
	}

	static int count(Container box, Item item) {
		int total = 0;
		for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	private static void cleanup(ServerLevel level, Fixture fixture) {
		if (fixture.home != null && fixture.peer != null) {
			String key = Long.toString(fixture.peer.bell().asLong());
			java.util.UUID routeId = fixture.home.bellEntity().getAttachedOrElse(RailTransport.ROUTES, Map.of()).get(key);
			var entity = routeId == null ? null : level.getEntity(routeId);
			if (entity != null) entity.discard();
		}
		fixture.residents.forEach(v -> {
			Villages.forget(v.getUUID());
			v.discard();
		});
		for (BlockPos pos : fixture.cells.keySet()) {
			level.removeBlockEntity(pos);
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
		fixture.cells.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		fixture.cells.forEach((pos, cell) -> {
			if (cell.data() == null) return;
			BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
			if (restored != null) level.setBlockEntity(restored);
		});
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Rail dispatch: " + message);
	}

	record Cell(BlockState state, net.minecraft.nbt.CompoundTag data) { }
	private record Observation(long tick, java.util.UUID routeId, boolean freightCart, Trip trip,
		java.util.UUID cartId, int stationStockAfter, int stationStockBefore, int sourceIronAfter,
		int targetIronAfter, Item firstLoadedItem) { }

	static final class Fixture {
		final Map<BlockPos, Cell> cells = new LinkedHashMap<>();
		final List<Villager> residents = new ArrayList<>();
		List<net.minecraft.world.level.ChunkPos> leased = List.of();
		private boolean prepared;
		VillageRef home;
		VillageRef peer;
		Container stationInventory;
		private int stationStockBefore;
		private int sourceIronBefore;
		private int targetIronBefore;
		private Item firstLoadedItem;
		private java.util.UUID initialRouteId;
		private long dispatchTick;
		private boolean namedCargoLoaded;
		private boolean fuelWaitObserved;
		private boolean fuelReleased;
		private long started;
		private long firstBoundary;
		private Observation observation;

		Fixture(long started) { this.started = started; }
	}
}
