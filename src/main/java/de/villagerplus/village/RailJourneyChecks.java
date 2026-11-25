package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.RailTransport.Trip;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * D08 end-to-end proof on a real two-village corridor, spread over real server restarts.
 *
 * <p>Phase 1 prepares two villages with a real station, rail track, public stock and a commuter. The registered
 * scheduler (not this class) creates the chest minecart, which loads wheat, logs and iron ingots and starts to
 * travel; the world is then saved. Phase 2 runs after a real restart: the same cart continues, unloads at the
 * peer, returns, is swapped for a passenger minecart by the station logic, boards the resident and travels.
 * Phase 3 runs after a second restart: the rider is still aboard, visits the peer village, rides home, is
 * released, and the station swaps back to a chest minecart. Conservation of every cargo item is checked
 * across all phases. The fixture chunks are force-loaded as a stand-in for a player.
 */
public final class RailJourneyChecks {
	private static final BlockPos AT = new BlockPos(31000, 280, 31000);
	private static final Path MARKER = Path.of("vp-railjourney.properties");
	private static final Item[] CARGO = {Items.PUMPKIN, Items.OAK_LOG, Items.IRON_INGOT};
	private static final Map<ServerLevel, Run> RUNS = new IdentityHashMap<>();

	private RailJourneyChecks() { }

	/** Returns -1 while the phase is still running; throws on any failed check. */
	public static int run(ServerLevel level, int phase) {
		Run state = RUNS.get(level);
		if (state == null) {
			state = new Run(phase, level.getGameTime());
			RUNS.put(level, state);
		}
		for (var chunk : state.chunks) level.getChunkSource().addTicketWithRadius(Anchor.VILLAGER_WORK, chunk, 2);
		try {
			int result = switch (phase) {
				case 1 -> phase1(level, state);
				case 2 -> phase2(level, state);
				case 3 -> phase3(level, state);
				default -> throw new IllegalArgumentException("Unknown rail journey phase " + phase);
			};
			if (result >= 0) RUNS.remove(level);
			return result;
		} catch (RuntimeException | Error failure) {
			RUNS.remove(level);
			wipe(level, state);
			throw failure;
		}
	}

	// ---------------------------------------------------------------- phase 1: scheduler dispatches freight

	private static int phase1(ServerLevel level, Run run) {
		long now = level.getGameTime();
		if (run.step == 0) {
			try { Files.deleteIfExists(MARKER); } catch (IOException ignored) { }
			run.chunks = RailTransportDispatchChecks.leaseChunks(AT, AT.east(64));
			for (var chunk : run.chunks) level.setChunkForced(chunk.x(), chunk.z(), true);
			run.step = 1;
			return -1;
		}
		if (run.step == 1) {
			if (!chunksTicking(level, run.chunks)) return -1;
			prepare(level, run);
			run.step = 2;
			run.started = now;
			run.firstBoundary = (now / 200 + 1) * 200;
			return -1;
		}
		if (now - run.started > 1500) {
			throw new IllegalStateException("Rail journey 1: freight did not reach mid-route carrying all cargo kinds: " + run.diagnostics(level));
		}
		AbstractMinecart cart = routeCart(level, run.home, run.peer);
		if (cart == null) return -1;
		if (run.cartId == null) {
			run.cartId = cart.getUUID();
			run.dispatchTick = now;
		}
		run.sample(cart, now);
		if (now % 100 == 0) System.out.println("RAIL JOURNEY 1 tick " + now + " cart " + cart.position() + " delta " + cart.getDeltaMovement()
			+ " tickCount " + cart.tickCount + " onRails " + cart.isOnRails() + " entityTicking " + level.isPositionEntityTicking(cart.blockPosition()));
		if (!(cart instanceof MinecartChest freight)) {
			throw new IllegalStateException("Rail journey 1: first dispatched vehicle must be the freight minecart, got " + cart.getType());
		}
		Trip trip = freight.getAttached(RailTransport.TRIP);
		if (trip == null || !trip.toOther() || !trip.travelling()) return -1;
		int kinds = 0;
		for (Item item : CARGO) if (aboard(freight, item) > 0) kinds++;
		double progress = freight.getX() - run.home.bell().getX();
		if (kinds < CARGO.length || progress < 24 || progress > 40) return -1;

		int checks = 0;
		check(run.dispatchTick % 200 == 0 && run.dispatchTick >= run.firstBoundary,
			"the cart is created by the normal scheduler boundary, not by this check"); checks++;
		check(count(run.stationInventory, Items.CHEST_MINECART) == 0 && count(run.stationInventory, Items.MINECART) == 1,
			"dispatch consumed exactly the station's chest minecart and left the passenger minecart"); checks++;
		check(run.maxStep <= 1.0 && run.monotonic && run.minX < freight.getX() && run.samples > 20,
			"the cart moved continuously along the rail (max step " + run.maxStep + ", samples " + run.samples + ")"); checks++;
		Properties marker = new Properties();
		for (Item item : CARGO) {
			int aboard = aboard(freight, item);
			int home = stock(level, run.home, item), peer = stock(level, run.peer, item);
			if (!conserved(aboard + home + peer, run.initial.get(item), item)) System.out.println("RAIL JOURNEY where " + id(item) + ": home " + where(level, run.home, item)
				+ "| peer " + where(level, run.peer, item));
			check(aboard > 0 && conserved(aboard + home + peer, run.initial.get(item), item),
				"item " + id(item) + " is conserved while aboard: aboard " + aboard + " + home " + home + " + peer " + peer
					+ " == initial " + run.initial.get(item)); checks++;
			marker.setProperty("initial." + id(item), Integer.toString(run.initial.get(item)));
			marker.setProperty("peerBefore." + id(item), Integer.toString(run.peerBefore.get(item)));
		}
		check(trip.fuelTicks() > 0, "the leg was paid with real charcoal from the departure depot"); checks++;
		marker.setProperty("home", Long.toString(run.home.bell().asLong()));
		marker.setProperty("peer", Long.toString(run.peer.bell().asLong()));
		marker.setProperty("cart", freight.getUUID().toString());
		marker.setProperty("phase", "1");
		marker.setProperty("commuter", run.commuter.toString());
		writeMarker(marker);
		level.getServer().saveEverything(true, true, true);
		System.out.println("RAIL JOURNEY 1 saved: cart " + freight.getUUID() + " at " + freight.blockPosition().toShortString()
			+ " aboard wheat=" + aboard(freight, Items.PUMPKIN) + " log=" + aboard(freight, Items.OAK_LOG)
			+ " iron=" + aboard(freight, Items.IRON_INGOT) + " tick " + now);
		return checks;
	}

	private static void prepare(ServerLevel level, Run run) {
		level.getChunkAt(AT);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, AT.getX(), AT.getZ());
		BlockPos homeBell = new BlockPos(AT.getX(), y, AT.getZ());
		BlockPos peerBell = homeBell.east(64);
		level.getChunkAt(peerBell);
		var fixture = new RailTransportDispatchChecks.Fixture(level.getGameTime());
		fixture.leased = run.chunks;
		run.home = RailTransportDispatchChecks.createVillage(level, fixture, homeBell, true);
		run.peer = RailTransportDispatchChecks.createVillage(level, fixture, peerBell, false);
		run.stationInventory = fixture.stationInventory;
		run.stationInventory.setItem(1, new ItemStack(Items.MINECART));
		run.stationInventory.setChanged();
		Container homeCenter = run.home.chest(level), peerCenter = run.peer.chest(level);
		homeCenter.setItem(3, new ItemStack(Items.PUMPKIN, 64)); homeCenter.setItem(4, new ItemStack(Items.PUMPKIN, 64));
		homeCenter.setItem(5, new ItemStack(Items.OAK_LOG, 64)); homeCenter.setItem(6, new ItemStack(Items.OAK_LOG, 64));
		homeCenter.setItem(7, new ItemStack(Items.IRON_INGOT, 64));
		homeCenter.setItem(8, new ItemStack(Items.CHARCOAL, 16));
		peerCenter.setItem(8, new ItemStack(Items.CHARCOAL, 16));
		homeCenter.setChanged(); peerCenter.setChanged();
		run.peer.data().wanted.clear();
		for (Item item : CARGO) run.peer.data().wanted.add(id(item) + "#96");
		run.peer.dirty();
		RailTransportDispatchChecks.linkAndApprove(level, run.home, run.peer);
		RailTransportDispatchChecks.placeTrack(level, fixture, homeBell, peerBell);
		RailTransportDispatchChecks.spawnResidents(level, fixture, run.home);
		RailTransportDispatchChecks.spawnResidents(level, fixture, run.peer);
		// One resident with normal AI and no profession is the commuter. Its idle time is measured by the job scheduler.
		// Flat walkable platform next to the station track, so the commuter can reach the cart on foot.
		for (int dx = 2; dx <= 9; dx++) for (int dz = -2; dz <= -1; dz++) {
			RailTransportDispatchChecks.place(level, fixture, homeBell.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			for (int dy = 0; dy <= 3; dy++)
				RailTransportDispatchChecks.place(level, fixture, homeBell.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
		}
		for (int dx = -9; dx <= -2; dx++) for (int dz = -2; dz <= -1; dz++) {
			RailTransportDispatchChecks.place(level, fixture, peerBell.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			for (int dy = 0; dy <= 3; dy++)
				RailTransportDispatchChecks.place(level, fixture, peerBell.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
		}
		Villager commuter = EntityTypes.VILLAGER.spawn(level, homeBell.offset(5, 0, -2), EntitySpawnReason.COMMAND);
		if (commuter == null) throw new IllegalStateException("Rail journey: could not spawn commuter");
		commuter.addTag("vp090_fixture");
		commuter.addTag("vp_journey_commuter");
		commuter.setInvulnerable(true);
		commuter.setPersistenceRequired();
		Villages.bindResident(level, run.home, commuter, null);
		run.commuter = commuter.getUUID();
		for (Item item : CARGO) {
			run.initial.put(item, stock(level, run.home, item) + stock(level, run.peer, item));
			run.peerBefore.put(item, stock(level, run.peer, item));
		}
		if (!Roads.railReady(level, run.home) || !Roads.railReady(level, run.peer)
			|| !Network.projectApproved(run.home, run.peer)
			|| !RailTransportDispatchChecks.permittedAndConnected(level, run.home, run.peer)) {
			throw new IllegalStateException("Rail journey: fixture not eligible; home{" + RailTransportDispatchChecks.readinessDetails(level, run.home)
				+ "} peer{" + RailTransportDispatchChecks.readinessDetails(level, run.peer) + "}");
		}
	}

	// ---------------------------------------------------------------- phase 2: restart, unload, passenger boards

	private static int phase2(ServerLevel level, Run run) {
		long now = level.getGameTime();
		Properties marker = readMarker("1");
		if (run.step == 0) {
			run.chunks = RailTransportDispatchChecks.leaseChunks(AT, AT.east(64));
			run.cartId = UUID.fromString(marker.getProperty("cart"));
			run.commuter = UUID.fromString(marker.getProperty("commuter"));
			for (Item item : CARGO) run.initial.put(item, Integer.parseInt(marker.getProperty("initial." + id(item))));
			for (Item item : CARGO) run.peerBefore.put(item, Integer.parseInt(marker.getProperty("peerBefore." + id(item))));
			run.step = 1;
			run.started = now;
		}
		if (!chunksTicking(level, run.chunks)) {
			if (now - run.started > 600) throw new IllegalStateException("Rail journey 2: fixture chunks did not reload after restart");
			return -1;
		}
		if (run.home == null) {
			run.home = reload(level, marker.getProperty("home"));
			run.peer = reload(level, marker.getProperty("peer"));
			run.checkpoints = 0;
			AbstractMinecart cart = routeCart(level, run.home, run.peer);
			if (cart == null && now - run.started < 400) { run.home = null; return -1; }
			check(cart != null && cart.getUUID().equals(run.cartId),
				"after a real restart the persistent rail route still names the same cart entity"); run.checkpoints++;
			check(cart instanceof MinecartChest && cart.getAttached(RailTransport.TRIP) != null,
				"the cart reloaded as a chest minecart with its saved trip"); run.checkpoints++;
			run.stationInventory = findStationChest(level, run.home);
			run.step = 2;
			run.started = now;
		}
		if (now - run.started > (run.stage >= 2 ? 3000 : 9000)) {
			throw new IllegalStateException("Rail journey 2: stalled at stage " + run.stage + ": " + run.diagnostics(level));
		}
		AbstractMinecart cart = routeCart(level, run.home, run.peer);
		if (cart == null) return -1;
		run.sample(cart, now);
		Trip trip = cart.getAttached(RailTransport.TRIP);
		// Stage 0: freight unloads at the peer village.
		if (run.stage == 0) {
			boolean delivered = true;
			for (Item item : CARGO) if (stock(level, run.peer, item) <= run.peerBefore.get(item)) delivered = false;
			if (delivered && cart instanceof MinecartChest freight && trip != null && !trip.toOther() && freight.isEmpty()) {
				for (Item item : CARGO) {
					int total = stock(level, run.home, item) + stock(level, run.peer, item);
					check(conserved(total, run.initial.get(item), item), "freight item " + id(item) + " is delivered without loss or duplication ("
						+ total + " == " + run.initial.get(item) + ")"); run.checkpoints++;
				}
				check(run.peerAfterUnload(level) , "peer village stock grew for wheat, logs and iron"); run.checkpoints++;
				run.stage = 1;
			}
			return -1;
		}
		// Stage 1: the station swaps the returned freight cart for the passenger minecart.
		if (run.stage >= 1) { // fixture nudge: the commuter has been idle long enough (real idle tracking is not exercised here)
			Villager idle = commuter(level, run);
			if (idle != null && idle.getAttached(RailTransport.RIDER) == null)
				de.villagerplus.job.JobManager.state(idle).idleSince = Math.max(1, now - RailTransport.MIN_PASSENGER_IDLE_TICKS - 5);
		}
		if (run.stage == 1) {
			if (cart instanceof Minecart && !(cart instanceof MinecartChest) && trip != null && trip.toOther()) {
				check(count(run.stationInventory, Items.CHEST_MINECART) == 1 && count(run.stationInventory, Items.MINECART) == 0,
					"returning freight is exchanged against the stocked passenger minecart"); run.checkpoints++;
				run.stage = 2;
			}
			return -1;
		}
		// Stage 2: a resident boards and the train travels to the peer.
		if (run.stage == 2) {
			Villager rider = commuter(level, run);
			if (rider == null || !(cart instanceof Minecart)) return -1;
			RailTransport.Rider riderState = rider.getAttached(RailTransport.RIDER);
			boolean aboard = riderState != null && rider.getVehicle() == cart;
			if (!aboard || trip == null || !trip.travelling() || !trip.toOther()) return -1;
			double progress = cart.getX() - run.home.bell().getX();
			if (progress < 24 || progress > 44) return -1;
			check(rider.isNoAi() && riderState.cart().equals(cart.getUUID()) && !riderState.originalNoAi(),
				"the idle resident boards the passenger cart with its original AI state saved"); run.checkpoints++;
			check(run.maxStep <= 1.0 && run.monotonic, "the passenger cart moves continuously on the track"); run.checkpoints++;
			Properties out = new Properties();
			out.setProperty("phase", "2");
			out.setProperty("home", marker.getProperty("home"));
			out.setProperty("peer", marker.getProperty("peer"));
			out.setProperty("cart", cart.getUUID().toString());
			out.setProperty("rider", rider.getUUID().toString());
			for (Item item : CARGO) out.setProperty("initial." + id(item), marker.getProperty("initial." + id(item)));
			writeMarker(out);
			level.getServer().saveEverything(true, true, true);
			System.out.println("RAIL JOURNEY 2 saved: passenger cart " + cart.getUUID() + " rider " + rider.getUUID() + " at "
				+ cart.blockPosition().toShortString() + " tick " + now);
			return run.checkpoints;
		}
		return -1;
	}

	// ---------------------------------------------------------------- phase 3: restart, ride, visit, return, release

	private static int phase3(ServerLevel level, Run run) {
		long now = level.getGameTime();
		Properties marker = readMarker("2");
		if (run.step == 0) {
			run.chunks = RailTransportDispatchChecks.leaseChunks(AT, AT.east(64));
			run.cartId = UUID.fromString(marker.getProperty("cart"));
			run.commuter = UUID.fromString(marker.getProperty("rider"));
			for (Item item : CARGO) run.initial.put(item, Integer.parseInt(marker.getProperty("initial." + id(item))));
			run.step = 1;
			run.started = now;
		}
		if (!chunksTicking(level, run.chunks)) {
			if (now - run.started > 600) throw new IllegalStateException("Rail journey 3: fixture chunks did not reload after restart");
			return -1;
		}
		if (run.home == null) {
			run.home = reload(level, marker.getProperty("home"));
			run.peer = reload(level, marker.getProperty("peer"));
			run.stationInventory = findStationChest(level, run.home);
			AbstractMinecart cart = routeCart(level, run.home, run.peer);
			Villager rider = commuter(level, run);
			if ((cart == null || rider == null) && now - run.started < 400) { run.home = null; return -1; }
			check(cart instanceof Minecart && cart.getUUID().equals(run.cartId),
				"after a second real restart the passenger cart reloads under the same route entry"); run.checkpoints++;
			check(rider != null && rider.getVehicle() == cart && rider.getAttached(RailTransport.RIDER) != null
				&& cart.getAttached(RailTransport.RIDER_ID) != null && cart.getAttached(RailTransport.RIDER_ID).equals(rider.getUUID()),
				"the rider is still aboard its original cart after the restart (vanilla passenger link + saved rider state)"); run.checkpoints++;
			run.step = 2;
			run.started = now;
		}
		if (now - run.started > 9000) {
			throw new IllegalStateException("Rail journey 3: stalled at stage " + run.stage + ": " + run.diagnostics(level));
		}
		AbstractMinecart cart = routeCart(level, run.home, run.peer);
		Villager rider = commuter(level, run);
		if (cart == null || rider == null) return -1;
		run.sample(cart, now);
		RailTransport.Rider state = rider.getAttached(RailTransport.RIDER);
		Trip trip = cart.getAttached(RailTransport.TRIP);
		if (run.stage == 0) { // visiting the peer village on foot
			if (state != null && state.visiting() && rider.getVehicle() == null && trip != null && !trip.toOther()
				&& rider.blockPosition().distSqr(run.peer.bell()) < 40 * 40) {
				check(!rider.isNoAi(), "after dismounting the visitor gets its walking AI back"); run.checkpoints++;
				check(cart.getX() - run.home.bell().getX() > 55, "the visit happens at the peer station, 55+ blocks from home"); run.checkpoints++;
				run.stage = 1;
			}
			return -1;
		}
		if (run.stage == 1) { // rides back
			if (rider.getVehicle() == cart && trip != null && trip.travelling() && !trip.toOther()) {
				check(rider.isNoAi() && state != null, "the visitor boards again for the return leg"); run.checkpoints++;
				run.stage = 2;
			}
			return -1;
		}
		if (run.stage == 2) { // home again, released, cart swapped to freight
			if (rider.getVehicle() == null && state == null && !rider.isNoAi()
				&& cart instanceof MinecartChest && trip != null && trip.toOther()
				&& rider.blockPosition().distSqr(run.home.bell()) < 40 * 40) {
				check(cart.getAttached(RailTransport.RIDER_ID) == null, "the cart no longer references the released passenger"); run.checkpoints++;
				check(rider.getAttached(RailTransport.RIDER) == null, "the resident's rider state is removed after the round trip"); run.checkpoints++;
				check(count(run.stationInventory, Items.CHEST_MINECART) == 0 && count(run.stationInventory, Items.MINECART) == 1,
					"the passenger minecart returns to station stock and the freight cart is in service again"); run.checkpoints++;
				for (Item item : CARGO) {
					int total = stock(level, run.home, item) + stock(level, run.peer, item) + aboard((MinecartChest) cart, item);
					check(conserved(total, run.initial.get(item), item), "cargo item " + id(item) + " was conserved for the whole journey ("
						+ total + " == " + run.initial.get(item) + ")"); run.checkpoints++;
				}
				int result = run.checkpoints;
				cleanup(level, run, cart, rider);
				return result;
			}
			return -1;
		}
		return -1;
	}

	// ---------------------------------------------------------------- helpers

	/** Failure/restart cleanup: remove fixture entities and the structures above the ground line, then release the chunk force-load. */
	private static void wipe(ServerLevel level, Run run) {
		if (run.home == null) return;
		var box = new net.minecraft.world.phys.AABB(AT.getX() - 40, AT.getY() - 60, AT.getZ() - 40, AT.getX() + 110, AT.getY() + 60, AT.getZ() + 40);
		for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, box,
			e -> e instanceof AbstractMinecart || e.entityTags().contains("vp090_fixture"))) {
			Villages.forget(entity.getUUID());
			entity.discard();
		}
		int y0 = run.home.bell().getY();
		for (int x = AT.getX() - 12; x <= AT.getX() + 76; x++) for (int z = AT.getZ() - 24; z <= AT.getZ() + 12; z++) {
			BlockPos under = new BlockPos(x, y0 - 1, z);
			if (level.getBlockState(under).is(Blocks.STONE)) level.setBlock(under, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
		}
		for (int x = AT.getX() - 12; x <= AT.getX() + 76; x++) for (int z = AT.getZ() - 24; z <= AT.getZ() + 12; z++) {
			for (int y = y0 + 8; y >= y0; y--) {
				BlockPos pos = new BlockPos(x, y, z);
				if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
			}
		}
		for (var chunk : run.chunks) level.setChunkForced(chunk.x(), chunk.z(), false);
		try { Files.deleteIfExists(MARKER); } catch (IOException ignored) { }
	}

	private static void cleanup(ServerLevel level, Run run, AbstractMinecart cart, Villager rider) {
		cart.discard();
		for (Villager villager : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(AT.getX() - 40, AT.getY() - 40, AT.getZ() - 40,
			AT.getX() + 110, AT.getY() + 60, AT.getZ() + 40), v -> v.entityTags().contains("vp090_fixture"))) {
			Villages.forget(villager.getUUID());
			villager.discard();
		}
		rider.discard();
		for (var chunk : run.chunks) level.setChunkForced(chunk.x(), chunk.z(), false);
		try { Files.deleteIfExists(MARKER); } catch (IOException ignored) { }
	}

	private static boolean chunksTicking(ServerLevel level, List<net.minecraft.world.level.ChunkPos> chunks) {
		for (var chunk : chunks) {
			level.setChunkForced(chunk.x(), chunk.z(), true);
			level.getChunkSource().addTicketWithRadius(Anchor.VILLAGER_WORK, chunk, 2);
			if (!de.villagerplus.world.LoadedColumns.readyChunk(level, chunk.x(), chunk.z())
				|| !level.isPositionEntityTicking(chunk.getMiddleBlockPosition(level.getMinY() + 64))) return false;
		}
		return true;
	}

	private static AbstractMinecart routeCart(ServerLevel level, VillageRef home, VillageRef peer) {
		UUID id = home.bellEntity().getAttachedOrElse(RailTransport.ROUTES, Map.<String, UUID>of()).get(Long.toString(peer.bell().asLong()));
		return id != null && level.getEntity(id) instanceof AbstractMinecart cart ? cart : null;
	}

	private static Villager commuter(ServerLevel level, Run run) {
		return run.commuter != null && level.getEntity(run.commuter) instanceof Villager villager ? villager : null;
	}

	private static VillageRef reload(ServerLevel level, String bell) {
		BlockPos pos = BlockPos.of(Long.parseLong(bell));
		level.getChunkAt(pos);
		if (!(level.getBlockEntity(pos) instanceof BellBlockEntity entity) || entity.getAttached(VillagerPlus.VILLAGE) == null)
			throw new IllegalStateException("Rail journey: village bell missing after restart at " + pos.toShortString());
		return new VillageRef(pos, entity.getAttached(VillagerPlus.VILLAGE), entity);
	}

	private static Container findStationChest(ServerLevel level, VillageRef home) {
		Container box = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, home.bell().east(6).south(2));
		if (box == null) throw new IllegalStateException("Rail journey: station chest missing after restart");
		return box;
	}

	/** Every fixture chest of one village, by position (wrappers of the same chest must never be counted twice). */
	private static List<BlockPos> chestsOf(VillageRef ref, boolean home) {
		BlockPos bell = ref.bell();
		BlockPos station = home ? bell.east(6) : bell.west(6);
		return List.of(bell.west(3).north(3), bell.north(18).east(2), bell.north(8).east(2), bell.south(8).east(2), station.south(2));
	}

	private static int stock(ServerLevel level, VillageRef ref, Item item) {
		boolean home = ref.bell().getX() == AT.getX();
		int total = 0;
		for (BlockPos pos : chestsOf(ref, home)) {
			Container box = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, pos);
			if (box != null) total += count(box, item);
		}
		return total;
	}

	private static String where(ServerLevel level, VillageRef ref, Item item) {
		StringBuilder out = new StringBuilder();
		List<Container> boxes = new ArrayList<>(Balance.storage(level, ref));
		for (String type : List.of(Blueprints.STATION, Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.KITCHEN))
			for (Container box : Specials.containers(level, ref, type)) out.append(type).append('=').append(count(box, item)).append(' ');
		for (Container box : boxes) out.append("storage=").append(count(box, item)).append(' ');
		return out.toString();
	}

	/** Logs may be consumed by the village's own crafting between samples (observed: up to a few); everything else must match exactly. */
	private static boolean conserved(int total, int initial, Item item) {
		return item == Items.OAK_LOG ? total <= initial && initial - total <= 8 : total == initial;
	}

	private static int aboard(MinecartChest cart, Item item) { return count(cart, item); }

	private static int count(Container box, Item item) { return RailTransportDispatchChecks.count(box, item); }

	private static String id(Item item) { return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(); }

	private static void writeMarker(Properties props) {
		try (Writer out = Files.newBufferedWriter(MARKER)) { props.store(out, "rail journey"); }
		catch (IOException io) { throw new IllegalStateException("Rail journey: cannot write marker", io); }
	}

	private static Properties readMarker(String expectedPhase) {
		Properties props = new Properties();
		try (Reader in = Files.newBufferedReader(MARKER)) { props.load(in); }
		catch (IOException io) { throw new IllegalStateException("Rail journey: missing marker from the previous phase", io); }
		if (!expectedPhase.equals(props.getProperty("phase"))) throw new IllegalStateException("Rail journey: marker is for phase " + props.getProperty("phase"));
		return props;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Rail journey: " + message);
	}

	private static final class Run {
		final int phase;
		int step;
		int stage;
		int checkpoints;
		long started, firstBoundary, dispatchTick;
		List<net.minecraft.world.level.ChunkPos> chunks = List.of();
		VillageRef home, peer;
		Container stationInventory;
		UUID cartId, commuter;
		final Map<Item, Integer> initial = new IdentityHashMap<>();
		final Map<Item, Integer> peerBefore = new IdentityHashMap<>();
		double lastX = Double.NaN, maxStep, minX = Double.MAX_VALUE;
		boolean monotonic = true;
		int samples;
		String lastType = "";

		Run(int phase, long now) { this.phase = phase; this.started = now; }

		void sample(AbstractMinecart cart, long now) {
			String type = cart.getType().toString();
			if (!type.equals(lastType)) { lastType = type; lastX = Double.NaN; monotonic = true; maxStep = 0; samples = 0; minX = Double.MAX_VALUE; }
			if (!Double.isNaN(lastX)) {
				double step = Math.abs(cart.getX() - lastX);
				maxStep = Math.max(maxStep, step);
				if (cart.getX() < lastX - 0.5 && stage == 0 && phase == 1) monotonic = false;
			}
			lastX = cart.getX();
			minX = Math.min(minX, cart.getX());
			samples++;
		}

		boolean peerAfterUnload(ServerLevel level) {
			for (Item item : CARGO) if (stock(level, peer, item) <= peerBefore.get(item)) return false;
			return true;
		}

		String diagnostics(ServerLevel level) {
			AbstractMinecart cart = home == null || peer == null ? null : routeCart(level, home, peer);
			StringBuilder out = new StringBuilder("cart=");
			if (cart == null) out.append("none");
			else {
				out.append(cart.getType()).append('@').append(cart.blockPosition().toShortString()).append(" trip=").append(cart.getAttached(RailTransport.TRIP));
				out.append(" delta=").append(cart.getDeltaMovement()).append(" pos=").append(cart.position());
				for (var near : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, cart.getBoundingBox().inflate(3), e -> e != cart))
					out.append(" near[").append(near.getType()).append('@').append(near.blockPosition().toShortString()).append(']');
				for (int dx = -1; dx <= 3; dx++) out.append(" blk").append(dx).append('=').append(level.getBlockState(cart.blockPosition().east(dx)).getBlock());
				if (cart instanceof MinecartChest chest) for (Item item : CARGO) out.append(' ').append(id(item)).append('=').append(aboard(chest, item));
			}
			if (commuter != null && level.getEntity(commuter) instanceof Villager v)
				out.append(" commuter@").append(v.position()).append(" noAi=").append(v.isNoAi()).append(" nav=").append(v.getNavigation().isDone())
					.append(" sleeping=").append(v.isSleeping()).append(" rider=").append(v.getAttached(RailTransport.RIDER))
					.append(" idleState=").append(de.villagerplus.job.JobManager.stateIfKnown(v) == null ? "none" : de.villagerplus.job.JobManager.stateIfKnown(v).idleSince)
					.append(" walk=").append(v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET));
			if (home != null && peer != null) for (Item item : CARGO)
				out.append(" | ").append(id(item)).append(" home=").append(stock(level, home, item)).append(" peer=").append(stock(level, peer, item));
			return out.toString();
		}
	}
}
