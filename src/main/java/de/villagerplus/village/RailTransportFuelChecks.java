package de.villagerplus.village;

import de.villagerplus.village.RailTransport.Trip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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

/** Server-fixture checks for long-leg charcoal payment and persistent rail-trip fuel. */
public final class RailTransportFuelChecks {
	private RailTransportFuelChecks() { }

	public static int run(ServerLevel level, VillageRef village) {
		List<Container> stores = publicStores(level, village);
		if (stores.isEmpty()) throw new IllegalStateException("Rail fuel: needs a loaded public village inventory");
		List<List<ItemStack>> before = snapshot(stores);
		Slot payment = firstPaymentSlot(stores);
		if (payment == null) throw new IllegalStateException("Rail fuel: needs an empty or charcoal slot in public storage");
		MinecartChest cart = null;
		MinecartChest loaded = null;
		try {
			clearCharcoal(stores);
			payment.box().setItem(payment.slot(), new ItemStack(Items.CHARCOAL, 3));
			payment.box().setChanged();

			List<BlockPos> longPath = syntheticPath(village.bell(), 201);
			int requiredTicks = RailTransport.requiredFuelTicks(longPath);
			require(requiredTicks == 2_000 && requiredTicks > RailTransport.CHARCOAL_FUEL_TICKS,
				"a 200-edge route requires more than one charcoal's 1,600 movement ticks");

			Trip initial = new Trip(village.bell(), village.bell().offset(200, 0, 0),
				village.bell().east(), village.bell().east(199), true, false, false, 0, 0, 0);
			Trip funded = RailTransport.fundLeg(level, village, initial, longPath);
			require(funded != null && funded.fuelTicks() == 2 * RailTransport.CHARCOAL_FUEL_TICKS
				&& countCharcoal(stores) == 1 && payment.box().getItem(payment.slot()).getCount() == 1,
				"the full 2,000-tick leg is funded by exactly two real charcoal, leaving one of three");

			Trip rejected = RailTransport.fundLeg(level, village, initial, longPath);
			require(rejected == null && initial.fuelTicks() == 0 && countCharcoal(stores) == 1,
				"one remaining charcoal cannot partially fund a two-charcoal route");

			cart = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
			if (cart == null) throw new IllegalStateException("Rail fuel: could not create persistence fixture minecart");
			cart.setAttached(RailTransport.TRIP, funded);
			TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			cart.saveWithoutId(saved);
			loaded = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
			if (loaded == null) throw new IllegalStateException("Rail fuel: could not create reloaded fixture minecart");
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved.buildResult()));
			Trip restored = loaded.getAttached(RailTransport.TRIP);
			require(funded.equals(restored) && restored.fuelTicks() == 3_200,
				"the actual minecart attachment save/load preserves the multi-item leg reserve");
			return 4;
		} finally {
			if (cart != null) cart.discard();
			if (loaded != null) loaded.discard();
			restore(stores, before);
		}
	}

	private static List<Container> publicStores(ServerLevel level, VillageRef village) {
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Container> stores = new ArrayList<>();
		for (Container box : Balance.storage(level, village)) if (box != null && seen.add(box)) stores.add(box);
		return stores;
	}

	private static List<BlockPos> syntheticPath(BlockPos start, int nodes) {
		List<BlockPos> path = new ArrayList<>(nodes);
		for (int i = 0; i < nodes; i++) path.add(start.east(i).immutable());
		return List.copyOf(path);
	}

	private static Slot firstPaymentSlot(List<Container> stores) {
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack item = box.getItem(slot);
			if (item.isEmpty() || item.is(Items.CHARCOAL)) return new Slot(box, slot);
		}
		return null;
	}

	private static void clearCharcoal(List<Container> stores) {
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			if (box.getItem(slot).is(Items.CHARCOAL)) box.setItem(slot, ItemStack.EMPTY);
		}
		stores.forEach(Container::setChanged);
	}

	private static int countCharcoal(List<Container> stores) {
		int total = 0;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++)
			if (box.getItem(slot).is(Items.CHARCOAL)) total += box.getItem(slot).getCount();
		return total;
	}

	private static List<List<ItemStack>> snapshot(List<Container> stores) {
		List<List<ItemStack>> result = new ArrayList<>();
		for (Container box : stores) {
			List<ItemStack> contents = new ArrayList<>();
			for (int slot = 0; slot < box.getContainerSize(); slot++) contents.add(box.getItem(slot).copy());
			result.add(contents);
		}
		return result;
	}

	private static void restore(List<Container> stores, List<List<ItemStack>> contents) {
		for (int box = 0; box < stores.size(); box++) {
			for (int slot = 0; slot < contents.get(box).size(); slot++)
				stores.get(box).setItem(slot, contents.get(box).get(slot).copy());
			stores.get(box).setChanged();
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Rail fuel: " + message);
	}

	private record Slot(Container box, int slot) { }
}
