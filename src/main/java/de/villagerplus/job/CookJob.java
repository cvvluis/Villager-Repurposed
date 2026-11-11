package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Specials;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Cook/Chef: uses food ingredients already stored in the village pantry to make meals. */
public final class CookJob implements Job {
	@Override
	public Predicate<ItemStack> produce() {
		return Meals::isFood;
	}

	@Override
	public int keep() {
		return 0;
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) return false;
		if (JobUtil.depositSurplus(c, produce(), Config.FARM_DEPOSIT_THRESHOLD, keep())) return true;
		if (cookFromGranary(c)) return true;
		c.setIdle(0, 200);
		return false;
	}

	@Override
	public boolean secondary(Ctx c) {
		if (c.idle(1)) return false;
		if (cookFromGranary(c)) return true;
		c.setIdle(1, 400);
		return false;
	}

	@Override
	public boolean indoorsDuringStorm() { return true; }

	private static boolean cookFromGranary(Ctx c) {
		if (c.village() == null) return false;
		BlockPos origin = Specials.get(c.village(), Blueprints.GRANARY);
		if (origin == null || !c.level().hasChunkAt(origin)) return false;
		ServerLevel level = c.level();
		BlockPos kitchen = Specials.get(c.village(), Blueprints.KITCHEN);
		BlockPos stationAnchor = kitchen == null ? origin : kitchen;
		List<Container> pantries = Specials.containers(level, c.village(), Blueprints.GRANARY);
		if (pantries.isEmpty()) return DelightKitchenCrafting.cookInOven(c) || SmithSmelting.cook(c);
		BlockPos cookingPot = null;
		BlockPos cuttingBoard = null;
		boolean farmersDelight = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("farmersdelight");
		if (farmersDelight) {
			Identifier potId = Identifier.parse(Blueprints.FARMERS_DELIGHT_COOKING_POT);
			var pot = BuiltInRegistries.BLOCK.getOptional(potId).orElse(null);
			if (pot != null && Workstation.ensure(c, pot, stationAnchor)) return true;
			Identifier boardId = Identifier.parse(Blueprints.FARMERS_DELIGHT_CUTTING_BOARD);
			var board = BuiltInRegistries.BLOCK.getOptional(boardId).orElse(null);
			if (board != null && Workstation.ensure(c, board, stationAnchor)) return true;
			cookingPot = DelightKitchenCrafting.findStation(level, stationAnchor, potId);
			cuttingBoard = DelightKitchenCrafting.findStation(level, stationAnchor, boardId);
		}
		if (Workstation.ensure(c, net.minecraft.world.level.block.Blocks.FURNACE, stationAnchor)) return true;
		for (Container box : pantries) {
			// Cooking-pot recipes produce prepared dishes even when an addon's item name
			// does not match the vanilla meal-name heuristic (for example, "bolognese").
			if (farmersDelight && DelightKitchenCrafting.cookFromPot(c, box, cookingPot, Meals::isFood, 24)) return true;
			if (farmersDelight && DelightKitchenCrafting.prepareOnBoard(c, box, cuttingBoard, 64)) return true;
			if (RecipeCrafting.craft(c, box, origin, stack -> Meals.isPreparedFood(level, stack), 24)) return true;
		}
		return DelightKitchenCrafting.cookInOven(c) || SmithSmelting.cook(c);
	}
}
