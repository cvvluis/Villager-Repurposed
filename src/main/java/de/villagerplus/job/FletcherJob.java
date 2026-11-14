package de.villagerplus.job;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Pfeilmacher: faellt Baeume in der Umgebung (Holz fuer das Dorf), stellt Bretter/Zuschnitte her und fertigt Pfeile
 * (Feuerstein + Stock + Feder) und Stoecke.
 */
public class FletcherJob implements Job {
	private record ChestDemand(int pending, int publicStock) {
		boolean shortfall() { return pending > publicStock; }
	}

	private static final List<Craft.Recipe> RECIPES = List.of(
		Craft.recipe("Pfeile", () -> new ItemStack(Items.ARROW, 4),
			Craft.of(Items.FLINT, 1), Craft.of(Items.STICK, 1), Craft.of(Items.FEATHER, 1)),
		Craft.recipe("Stöcke", () -> new ItemStack(Items.STICK, 4), Craft.of(ItemTags.PLANKS, 2)));

	@Override
	public Predicate<ItemStack> produce() {
		return s -> !Meals.isFood(s);
	}

	private boolean wood(Ctx c) {
		Woodcutting.diagnose(c, "wood() erreicht, Dorf=" + (c.village() != null));
		if (c.village() == null) {
			return false;
		}
		if (JobUtil.depositSurplus(c, produce(), 48, 0)) {
			return true;
		}
		return Woodcutting.tick(c);
	}

	@Override
	public boolean primary(Ctx c) {
		return wood(c) || Workshop.tick(c) || craft(c);
	}

	@Override
	public boolean secondary(Ctx c) {
		return Workshop.tick(c) || craft(c) || wood(c);
	}

	private boolean craft(Ctx c) {
		if (c.idle(1)) {
			return false;
		}
		ChestDemand chestDemand = chestDemand(c);
		if (craftPendingPrivateChest(c, chestDemand)) {
			return true;
		}
		if (chestDemand.shortfall()) {
			// Arrows do not consume the planks reserved for residents; defer stick production until
			// every loaded resident with a pending request can be supplied from public stock.
			if (Craft.run(c, List.of(RECIPES.getFirst()))) {
				Anim.hold(c, Items.ARROW);
				return true;
			}
			c.setIdle(1, 400);
			return false;
		}
		if (Craft.run(c, RECIPES)) {
			Anim.hold(c, Items.ARROW);
			return true;
		}
		c.setIdle(1, 400);
		return false;
	}

	/** The woodcutter turns real public planks into loaded-recipe chests only when residents are waiting for one. */
	private boolean craftPendingPrivateChest(Ctx c, ChestDemand demand) {
		if (!demand.shortfall()) return false;
		int pending = demand.pending();
		if (pending <= 0) return false;
		BlockPos depotPos = c.village().chestPos(c.level());
		Container depot = JobUtil.box(c);
		if (depotPos == null || depot == null) return false;

		int inDepot = JobUtil.count(depot, stack -> stack.is(Items.CHEST));
		int otherStock = Math.max(0, demand.publicStock() - inDepot);
		int target = de.villagerplus.village.PrivateChestProvisioning.targetPrimaryStock(pending, otherStock);
		if (inDepot >= target) return false;

		BlockPos table = Workstation.find(c.level(), depotPos, Blocks.CRAFTING_TABLE, 8);
		if (table == null) return Workstation.ensure(c, Blocks.CRAFTING_TABLE, depotPos);
		int planks = JobUtil.count(depot, stack -> stack.is(ItemTags.PLANKS));
		if (planks < 8) return RecipeCrafting.craft(c, depot, table, stack -> stack.is(ItemTags.PLANKS), 16);
		return RecipeCrafting.craft(c, depot, table, stack -> stack.is(Items.CHEST), target);
	}

	private ChestDemand chestDemand(Ctx c) {
		if (c.village() == null) return new ChestDemand(0, 0);
		return new ChestDemand(
			de.villagerplus.village.PrivateChestProvisioning.pendingResidents(c.level(), c.village()),
			de.villagerplus.village.PrivateChestProvisioning.publicChestStock(c.level(), c.village()));
	}
}
