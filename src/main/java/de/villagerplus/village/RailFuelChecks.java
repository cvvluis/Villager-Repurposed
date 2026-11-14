package de.villagerplus.village;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/** Real-world public-inventory and serialized trip-state checks for charcoal-powered minecart travel. */
public final class RailFuelChecks {
	private RailFuelChecks() { }

	public static int run(ServerLevel level, VillageRef village) {
		List<Container> storage = new ArrayList<>();
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Container box : Balance.storage(level, village)) if (box != null && seen.add(box)) storage.add(box);
		if (storage.isEmpty()) throw new IllegalStateException("Rail fuel check needs a loaded public village inventory");
		List<List<ItemStack>> before = storage.stream().map(RailFuelChecks::snapshot).toList();
		BlockPos peerBellPos = village.bell().offset(32, 0, 0);
		BlockPos peerChestPos = peerBellPos.east(3);
		BlockPos railStart = village.bell().south(8);
		BlockPos railNext = railStart.east();
		level.getChunkAt(peerBellPos);
		level.getChunkAt(railStart);
		List<BlockPos> fixtureCells = List.of(peerBellPos, peerBellPos.below(), peerChestPos, peerChestPos.below(),
			railStart, railStart.below(), railStart.above(), railStart.above(2), railNext, railNext.below(), railNext.above(), railNext.above(2));
		Map<BlockPos, Cell> oldCells = new HashMap<>();
		for (BlockPos pos : fixtureCells) snapshotPosition(level, oldCells, pos);
		TradeNetwork oldNetwork = village.bellEntity().getAttached(VillagerPlus.NETWORK);
		Map<String, BlockPos> oldSpecials = village.bellEntity().getAttached(VillagerPlus.SPECIALS);
		Map<String, java.util.UUID> oldRoutes = village.bellEntity().getAttached(RailTransport.ROUTES);
		List<MinecartChest> fixtureCarts = new ArrayList<>();
		int[] checks = {0};
		try {
			clearCharcoal(storage);
			var trip = trip(village.bell());
			check(checks, RailTransport.burnFuelTick(level, village, trip) == null,
				"an empty public inventory pauses the trip without manufacturing fuel");

			Slot payment = firstUsableSlot(storage);
			if (payment == null) throw new IllegalStateException("Rail fuel check needs a free slot in public village storage");
			payment.box().setItem(payment.slot(), new ItemStack(Items.CHARCOAL, 2));
			payment.box().setChanged();
			var fueled = RailTransport.burnFuelTick(level, village, trip);
			check(checks, fueled != null && fueled.fuelTicks() == RailTransport.CHARCOAL_FUEL_TICKS - 1
				&& countCharcoal(storage) == 1,
				"one real charcoal is debited once and saved as 1,599 remaining movement ticks");

			var continued = RailTransport.burnFuelTick(level, village, fueled);
			check(checks, continued != null && continued.fuelTicks() == RailTransport.CHARCOAL_FUEL_TICKS - 2
				&& countCharcoal(storage) == 1,
				"paid fuel burns down across movement ticks without a second inventory debit");
			var encoded = RailTransport.Trip.CODEC.encodeStart(JsonOps.INSTANCE, continued).getOrThrow();
			var reloaded = RailTransport.Trip.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
			check(checks, reloaded.equals(continued) && reloaded.fuelTicks() == RailTransport.CHARCOAL_FUEL_TICKS - 2,
				"serialized trip reload preserves endpoints, travel phase, and remaining charcoal fuel");

			JsonObject legacy = encoded.getAsJsonObject().deepCopy();
			legacy.remove("fuel_ticks");
			var migrated = RailTransport.Trip.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();
			check(checks, migrated.fuelTicks() == 0 && migrated.home().equals(continued.home()),
				"legacy saved trips without fuel_ticks safely migrate to empty fuel");

			clearCharcoal(storage);
			var lastBurn = RailTransport.burnFuelTick(level, village, continued.withFuelTicks(1));
			check(checks, lastBurn != null && lastBurn.fuelTicks() == 0
				&& RailTransport.burnFuelTick(level, village, lastBurn) == null,
				"a depleted trip pauses without changing route state or moving on unpaid fuel");
			payment.box().setItem(payment.slot(), new ItemStack(Items.CHARCOAL, 1));
			payment.box().setChanged();
			var resumed = RailTransport.burnFuelTick(level, village, lastBurn);
			check(checks, resumed != null && resumed.fuelTicks() == RailTransport.CHARCOAL_FUEL_TICKS - 1
				&& countCharcoal(storage) == 0,
				"the same paused trip resumes after one new public charcoal is paid");

			exerciseProductionTick(level, village, peerBellPos, peerChestPos, railStart, railNext, payment,
				checks, fixtureCarts);
			return checks[0];
		} finally {
			fixtureCarts.forEach(MinecartChest::discard);
			if (oldNetwork == null) village.bellEntity().removeAttached(VillagerPlus.NETWORK);
			else village.bellEntity().setAttached(VillagerPlus.NETWORK, oldNetwork);
			if (oldSpecials == null) village.bellEntity().removeAttached(VillagerPlus.SPECIALS);
			else village.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			if (oldRoutes == null) village.bellEntity().removeAttached(RailTransport.ROUTES);
			else village.bellEntity().setAttached(RailTransport.ROUTES, oldRoutes);
			village.dirty();
			for (BlockPos pos : oldCells.keySet()) level.removeBlockEntity(pos);
			oldCells.keySet().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
			oldCells.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			oldCells.forEach((pos, cell) -> {
				if (cell.data() == null) return;
				BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			});
			for (int i = 0; i < storage.size(); i++) restore(storage.get(i), before.get(i));
		}
	}

	private static void exerciseProductionTick(ServerLevel level, VillageRef home, BlockPos peerBellPos,
			BlockPos peerChestPos, BlockPos railStart, BlockPos railNext, Slot payment, int[] checks,
			List<MinecartChest> fixtureCarts) {
		level.getChunkAt(peerBellPos);
		level.setBlock(peerBellPos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(peerBellPos, Blocks.BELL.defaultBlockState(), 3);
		BlockEntity peerEntity = level.getBlockEntity(peerBellPos);
		if (!(peerEntity instanceof BellBlockEntity peerBell)) throw new IllegalStateException("Rail fuel tick fixture could not create peer bell");
		VillageData peerData = new VillageData();
		peerBell.setAttached(VillagerPlus.NETWORK, TradeNetwork.create(peerBellPos));
		peerBell.setAttached(VillagerPlus.MARKET, MarketData.empty());
		peerBell.setAttached(VillagerPlus.VILLAGE, peerData);
		level.setBlock(peerChestPos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(peerChestPos, Blocks.CHEST.defaultBlockState(), 3);
		peerData.chest = java.util.Optional.of(peerChestPos);
		VillageRef peer = new VillageRef(peerBellPos, peerData, peerBell);

		String dimension = Network.dim(level);
		String approval = Network.pairKey(home, peer, "large_project_approval");
		Network.save(home.bellEntity(), new TradeNetwork(List.of(),
			List.of(new TradeNetwork.Link(dimension, peer.bell(), "Fuel check peer", Roads.METROPOLIS, true, 0, 0)), Map.of(approval, 1L), 0, 0));
		Network.save(peerBell, new TradeNetwork(List.of(),
			List.of(new TradeNetwork.Link(dimension, home.bell(), "Fuel check home", Roads.METROPOLIS, true, 0, 0)), Map.of(approval, 1L), 0, 0));
		Specials.set(home, Blueprints.STATION, railStart);
		Specials.set(peer, Blueprints.STATION, railNext);
		BlockState flatRail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		level.setBlock(railStart.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(railStart, flatRail, 3);
		level.setBlock(railStart.above(), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(railStart.above(2), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(railNext.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(railNext, Blocks.STONE.defaultBlockState(), 3); // Deliberately no connected rail yet.
		level.setBlock(railNext.above(), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(railNext.above(2), Blocks.AIR.defaultBlockState(), 3);
		clearCharcoal(Balance.storage(level, home));
		payment.box().setItem(payment.slot(), new ItemStack(Items.CHARCOAL, 1));
		payment.box().setChanged();

		MinecartChest invalid = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
		if (invalid == null) throw new IllegalStateException("Rail fuel tick fixture could not create a chest minecart");
		invalid.setInitialPos(railStart.getX() + .5, railStart.getY() + .0625, railStart.getZ() + .5);
		RailTransport.Trip invalidTrip = new RailTransport.Trip(home.bell(), peer.bell(), railStart, railNext,
			true, true, false, 0L, 0, 0);
		invalid.setAttached(RailTransport.TRIP, invalidTrip);
		if (!level.addFreshEntity(invalid)) throw new IllegalStateException("Rail fuel tick fixture could not add invalid-route minecart");
		fixtureCarts.add(invalid);
		setRouteReservation(home, peer, invalid);
		RailTransport.tick(level, invalid, level.getGameTime());
		check(checks, invalid.getAttached(RailTransport.TRIP).equals(invalidTrip)
			&& invalid.getAttached(RailTransport.TRIP).fuelTicks() == 0
			&& countCharcoal(Balance.storage(level, home)) == 1 && invalid.getDeltaMovement().lengthSqr() == 0,
			"the production tick pauses at an unconnected next rail without charging charcoal or moving");
		invalid.discard();

		level.setBlock(railNext, flatRail, 3);
		MinecartChest valid = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
		if (valid == null) throw new IllegalStateException("Rail fuel tick fixture could not create a valid-route chest minecart");
		valid.setInitialPos(railStart.getX() + .5, railStart.getY() + .0625, railStart.getZ() + .5);
		valid.setAttached(RailTransport.TRIP, invalidTrip);
		if (!level.addFreshEntity(valid)) throw new IllegalStateException("Rail fuel tick fixture could not add connected-route minecart");
		fixtureCarts.add(valid);
		setRouteReservation(home, peer, valid);
		long currentTick = level.getGameTime();
		long fuelAttemptTick = currentTick + ((20 - currentTick % 20) % 20);
		RailTransport.tick(level, valid, fuelAttemptTick);
		RailTransport.Trip paidTrip = valid.getAttached(RailTransport.TRIP);
		int charcoalAfterTravel = countCharcoal(Balance.storage(level, home));
		boolean movedWithPaidFuel = paidTrip != null && paidTrip.travelling()
			&& paidTrip.fuelTicks() == RailTransport.CHARCOAL_FUEL_TICKS - 1
			&& charcoalAfterTravel == 0 && valid.getDeltaMovement().lengthSqr() > 0;
		check(checks, movedWithPaidFuel,
			"a connected production tick debits exactly one charcoal, saves remaining fuel on the cart, and moves"
				+ " [serverTick=" + currentTick + ", fuelAttemptTick=" + fuelAttemptTick + ", modulo20=" + fuelAttemptTick % 20
				+ ", trip=" + paidTrip + ", charcoal=" + charcoalAfterTravel + ", motion=" + valid.getDeltaMovement() + "]");

		TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		valid.saveWithoutId(saved);
		MinecartChest loaded = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
		if (loaded == null) throw new IllegalStateException("Rail fuel attachment reload fixture could not create a minecart");
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved.buildResult()));
		fixtureCarts.add(loaded);
		RailTransport.Trip loadedTrip = loaded.getAttached(RailTransport.TRIP);
		check(checks, paidTrip.equals(loadedTrip) && loadedTrip.fuelTicks() == paidTrip.fuelTicks(),
			"the persistent rail_trip attachment and fuel survive a real minecart NBT save/load");
	}

	private static void setRouteReservation(VillageRef home, VillageRef peer, MinecartChest cart) {
		Map<String, java.util.UUID> routes = new HashMap<>(home.bellEntity().getAttachedOrElse(RailTransport.ROUTES, Map.of()));
		routes.put(Long.toString(peer.bell().asLong()), cart.getUUID());
		home.bellEntity().setAttached(RailTransport.ROUTES, Map.copyOf(routes));
	}

	private static RailTransport.Trip trip(BlockPos bell) {
		return new RailTransport.Trip(bell, bell.offset(96, 0, 0), bell.offset(2, 0, 0), bell.offset(94, 0, 0),
			true, true, false, 0L, 0, 0);
	}

	private static Slot firstUsableSlot(List<Container> boxes) {
		for (Container box : boxes) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack item = box.getItem(slot);
			if (item.isEmpty() || item.is(Items.CHARCOAL)) return new Slot(box, slot);
		}
		return null;
	}

	private static void clearCharcoal(List<Container> boxes) {
		for (Container box : boxes) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			if (box.getItem(slot).is(Items.CHARCOAL)) box.setItem(slot, ItemStack.EMPTY);
		}
		boxes.forEach(Container::setChanged);
	}

	private static int countCharcoal(List<Container> boxes) {
		int count = 0;
		for (Container box : boxes) for (int slot = 0; slot < box.getContainerSize(); slot++)
			if (box.getItem(slot).is(Items.CHARCOAL)) count += box.getItem(slot).getCount();
		return count;
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> result = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
		return result;
	}

	private static void restore(Container container, List<ItemStack> contents) {
		for (int slot = 0; slot < contents.size(); slot++) container.setItem(slot, contents.get(slot).copy());
		container.setChanged();
	}

	private static void snapshotPosition(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		if (before.containsKey(pos)) return;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		before.put(pos, new Cell(level.getBlockState(pos), blockEntity == null ? null
			: blockEntity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void check(int[] checks, boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Rail fuel: " + message);
		checks[0]++;
	}

	private record Slot(Container box, int slot) { }
	private record Cell(BlockState state, net.minecraft.nbt.CompoundTag data) { }
}
