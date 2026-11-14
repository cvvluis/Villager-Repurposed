package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.RailTransport;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

/** Träger belädt eine angedockte Truhenlore am Bahnhof mit Nahrung, Holz oder Barren. */
public final class FreightLoading {
	private FreightLoading() { }
	private record CargoSource(Container container, int slot) { }

	public static boolean docked(MinecartChest cart) {
		RailTransport.Trip trip = cart.getAttached(RailTransport.TRIP);
		return trip != null && !trip.travelling();
	}

	public static boolean work(Ctx c) {
		if (c.village() == null || !Specials.has(c.village(), Blueprints.STATION)) return false;
		BlockPos station = Specials.get(c.village(), Blueprints.STATION);
		if (station == null || !c.level().hasChunkAt(station)) return false;
		MinecartChest cart = c.level().getEntitiesOfClass(MinecartChest.class, new AABB(station).inflate(8, 4, 8), FreightLoading::docked)
			.stream().filter(candidate -> hasRoom(candidate)).findFirst().orElse(null);
		if (cart == null) return false;
		CargoSource source = findCargo(c);
		if (source == null) return false;
		Container chest = source.container();
		int slot = source.slot();
		ItemStack sample = chest.getItem(slot);
		c.setActivity("belädt die Güterlore");
		Anim.hold(c, sample.getItem());
		if (!JobUtil.approach(c, cart.blockPosition(), 3.0)) return true;
		ItemStack moving = chest.removeItem(slot, Math.min(16, chest.getItem(slot).getCount()));
		ItemStack rest = HopperBlockEntity.addItem(null, cart, moving, null);
		if (!rest.isEmpty()) HopperBlockEntity.addItem(null, chest, rest, null);
		chest.setChanged();
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static boolean hasRoom(Container cart) {
		for (int i = 0; i < cart.getContainerSize(); i++) if (cart.getItem(i).isEmpty()) return true;
		return false;
	}

	private static CargoSource findCargo(Ctx c) {
		// Food belongs in the public granary; it is intentionally rejected by the central material chest.
		for (Container granary : Specials.containers(c.level(), c.village(), Blueprints.GRANARY)) {
			CargoSource food = first(granary, StockPolicy.Category.FOOD);
			if (food != null) return food;
		}
		List<Container> materialStores = new ArrayList<>();
		for (BlockPos pos : Lager.materialChests(c.level(), c.village())) {
			Container chest = Lager.publicContainerAt(c.level(), pos);
			if (chest != null && !materialStores.contains(chest)) materialStores.add(chest);
		}
		for (Container stationStore : Specials.containers(c.level(), c.village(), Blueprints.STATION)) {
			if (!materialStores.contains(stationStore)) materialStores.add(stationStore);
		}
		for (Container store : materialStores) {
			CargoSource material = first(store, StockPolicy.Category.MATERIAL);
			if (material != null) return material;
		}
		return null;
	}

	private static CargoSource first(Container store, StockPolicy.Category category) {
		for (int slot = 0; slot < store.getContainerSize(); slot++) {
			ItemStack stack = store.getItem(slot);
			if (!stack.isEmpty() && StockPolicy.category(stack.getItem()) == category) return new CargoSource(store, slot);
		}
		return null;
	}
}
