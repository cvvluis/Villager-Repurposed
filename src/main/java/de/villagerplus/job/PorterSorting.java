package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Porter tidying of public depots: a stack that sits in the wrong public depot (tools or food in the material store, anything but
 * tools in the toolshed, anything but food in the granary) is carried to the depot StockPolicy routes it to. Only real stacks move
 * through {@link StockPolicy#insertVillage}; whatever the right depot cannot take (no depot, no room, policy limit) goes back into
 * its slot, so nothing is created or lost. Private containers are never part of the scan.
 */
final class PorterSorting {
	private PorterSorting() { }

	record Source(BlockPos pos, Container box, String depot) { }

	static boolean work(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null) return false;
		for (Source source : sources(c.level(), ref)) {
			if (!hasMisplaced(source)) continue;
			c.setActivity("sortiert Waren ins richtige Lager");
			if (!JobUtil.approach(c, source.pos(), 3.0)) return true;
			if (sortOne(c.level(), ref, source)) {
				JobUtil.swing(c.v());
				c.mem().jobsDone++;
				c.st().cooldownUntil = c.now() + 20;
				return true;
			}
		}
		return false;
	}

	static List<Source> sources(ServerLevel level, VillageRef ref) {
		List<Source> out = new ArrayList<>();
		List<Container> seen = new ArrayList<>();
		for (BlockPos pos : Lager.materialChests(level, ref)) {
			Container box = Lager.publicContainerAt(level, pos);
			if (box != null && !seen.contains(box)) {
				seen.add(box);
				out.add(new Source(pos, box, Blueprints.STORAGE));
			}
		}
		for (String depot : new String[] {Blueprints.TOOLSHED, Blueprints.GRANARY}) {
			for (var located : Specials.locatedContainers(level, ref, depot)) {
				if (!seen.contains(located.container())) {
					seen.add(located.container());
					out.add(new Source(located.pos(), located.container(), depot));
				}
			}
		}
		return out;
	}

	private static boolean misplaced(Source source, ItemStack stack) {
		if (stack.isEmpty()) return false;
		StockPolicy.Category category = StockPolicy.category(stack.getItem());
		if (category == StockPolicy.Category.OTHER) return false;
		return !StockPolicy.depotAccepts(source.depot(), stack.getItem());
	}

	private static boolean hasMisplaced(Source source) {
		for (int slot = 0; slot < source.box().getContainerSize(); slot++) {
			if (misplaced(source, source.box().getItem(slot))) return true;
		}
		return false;
	}

	/**
	 * Moves the first misplaced stack that its right depot accepts (fully or partly); returns whether any item moved. The
	 * copy is offered to the router first and only the accepted amount is removed from the slot, so a refused remainder
	 * never leaves its slot (the material store refuses direct writes of tools and would drop a put-back).
	 */
	static boolean sortOne(ServerLevel level, VillageRef ref, Source source) {
		Container box = source.box();
		for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (!misplaced(source, stack)) continue;
			int offered = stack.getCount();
			ItemStack rest = StockPolicy.insertVillage(level, ref, stack.copy());
			int moved = offered - rest.getCount();
			if (moved <= 0) continue;
			box.removeItem(slot, moved);
			box.setChanged();
			return true;
		}
		return false;
	}
}
