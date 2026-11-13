package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.StockPolicy;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Real, recipe-registry-driven cooking and smelting in marked village furnaces. */
final class SmithSmelting {
	private static final long SCAN_INTERVAL = 1200L;
	private enum Worker { SMITH, COOK, BUTCHER }

	private SmithSmelting() { }

	static boolean work(Ctx c) {
		return work(c, Worker.SMITH);
	}

	static boolean cook(Ctx c) {
		return work(c, Worker.COOK);
	}

	/**
	 * Butcher: smokes raw meat and fish from the public food store (granary) in the marked kitchen smoker, with fuel from public
	 * stock only, and returns the cooked result to the food store. Shares the furnace claim protocol with the cook.
	 */
	static boolean cookMeat(Ctx c) {
		return work(c, Worker.BUTCHER);
	}

	private static boolean smokes(Worker worker) {
		return worker != Worker.SMITH;
	}

	/** Uses only a marked village-owned furnace next to the communal depot for the active plan's stone chain. */
	static boolean workPlanStone(Ctx c, VillageRef ref, Container stock) {
		if (ref == null || stock == null) return false;
		BlockPos depot = ref.chestPos(c.level());
		if (depot == null || !c.level().hasChunkAt(depot)) return false;
		BlockPos pos = findPlanFurnace(c.level(), depot);
		if (pos == null) return false;
		AbstractFurnaceBlockEntity furnace = furnaceAt(c.level(), pos, Worker.SMITH);
		if (furnace == null) return false;
		ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		if (claim == null) return false;
		if (!claim.isEmpty()) {
			if (!JobUtil.approach(c, pos, 3.0)) return true;
			ItemStack output = furnace.getItem(2);
			if (output.isEmpty()) {
				if (furnace.getItem(0).isEmpty()) {
					furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
					furnace.setChanged();
					return false;
				}
				ItemStack input = furnace.getItem(0);
				AbstractCookingRecipe recipe = matchingRecipe(c.level(), input, recipeType(c.level().getBlockState(pos)));
				if (recipe == null || !ItemStack.isSameItemSameComponents(
					recipe.assemble(new SingleRecipeInput(input)), claim)) return false;
				if (furnace.getItem(1).isEmpty()) {
					ItemStack fuel = ItemStack.EMPTY;
					for (Container store : Balance.storage(c.level(), ref)) {
						fuel = withdraw(store, s -> s.is(ItemTags.COALS));
						if (!fuel.isEmpty()) break;
					}
					if (fuel.isEmpty()) return false;
					furnace.setItem(1, fuel);
				}
				if (!furnace.getItem(1).is(ItemTags.COALS)) return false;
				furnace.setChanged();
				c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
				c.setActivity("schmilzt Planmaterial nach geladenem Rezept");
				Anim.hold(c, input.getItem());
				JobUtil.swing(c.v());
				c.mem().jobsDone++;
				c.st().cooldownUntil = c.now() + 100;
				return true;
			}
			int moved = deliverClaimedOutput(c.level(), ref, furnace);
			if (moved <= 0) return false;
			c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
			c.setActivity("lagert planbezogene Steinverarbeitung ein");
			Anim.hold(c, claim.getItem());
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 80;
			return true;
		}
		if (!ref.data().hasPlan()) return false;
		if (furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) != 0
			|| !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()) return false;
		PlanInput wanted = planInput(c.level(), ref, stock);
		if (wanted == null || !hasVillageStorageCapacity(c.level(), ref, wanted.output())
			|| furnace.getItem(1).isEmpty() && count(Balance.storage(c.level(), ref), s -> s.is(ItemTags.COALS)) == 0
			|| !JobUtil.approach(c, pos, 3.0)) return wanted != null;
		if (!loadBatch(c.level(), furnace, stock, wanted.slot(), wanted.recipe(), Worker.SMITH)) return false;
		c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
		c.setActivity("beschickt den Dorfofen für den aktiven Bauplan");
		Anim.hold(c, wanted.input().getItem());
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 80;
		return true;
	}

	private static PlanInput planInput(ServerLevel level, VillageRef ref, Container stock) {
		for (Item input : new Item[] {Items.COBBLESTONE, Items.STONE_BRICKS}) {
			Item expected = input == Items.COBBLESTONE ? Items.STONE : Items.CRACKED_STONE_BRICKS;
			if (!PlanStoneSupply.planNeedsSmelting(level, ref.data(), stock, input, expected)) continue;
			for (int slot = 0; slot < stock.getContainerSize(); slot++) {
				ItemStack available = stock.getItem(slot);
				if (available.isEmpty() || !available.is(input)) continue;
				ItemStack one = available.copyWithCount(1);
				AbstractCookingRecipe recipe = matchingRecipe(level, one, RecipeType.SMELTING);
				if (recipe == null || !recipe.matches(new SingleRecipeInput(one), level)
					|| !recipe.assemble(new SingleRecipeInput(one)).is(expected)
					|| recipe.assemble(new SingleRecipeInput(one)).getCount() != 1) continue;
				return new PlanInput(slot, one, recipe, recipe.assemble(new SingleRecipeInput(one)));
			}
		}
		return null;
	}

	private static boolean hasVillageStorageCapacity(ServerLevel level, VillageRef ref, ItemStack output) {
		if (output == null || output.isEmpty()) return false;
		if (StockPolicy.deliveryRoom(level, ref, output.getItem()) < output.getCount()) return false;
		List<Container> stores = new java.util.ArrayList<>();
		String depot = Specials.depotType(output);
		if (depot != null) {
			stores.addAll(Specials.containers(level, ref, depot));
		} else {
			Container home = ref.chest(level);
			if (home != null) stores.add(home);
			stores.addAll(Lager.containers(level, ref.data()));
		}
		ItemStack left = output.copy();
		for (Container store : stores) {
			SimpleContainer preview = new SimpleContainer(store.getContainerSize());
			for (int i = 0; i < store.getContainerSize(); i++) preview.setItem(i, store.getItem(i).copy());
			left = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, preview, left, null);
			if (left.isEmpty()) return true;
		}
		return false;
	}

	private static BlockPos findPlanFurnace(ServerLevel level, BlockPos depot) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(depot.offset(-8, -2, -8), depot.offset(8, 4, 8))) {
			if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.FURNACE)
				|| !(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)
				|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null) continue;
			ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
			boolean emptyForNewBatch = furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty()
				&& (claim == null || claim.isEmpty());
			boolean hasClaimedBatch = claim != null && (claim.is(Items.STONE) || claim.is(Items.CRACKED_STONE_BRICKS));
			if (!emptyForNewBatch && !hasClaimedBatch) continue;
			if (claim == null && emptyForNewBatch) {
				furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
				furnace.setChanged();
			}
			double distance = pos.distSqr(depot);
			if (distance < bestDistance) { best = pos.immutable(); bestDistance = distance; }
		}
		return best;
	}

	private static boolean work(Ctx c, Worker worker) {
		if (c.village() == null || !Specials.has(c.village(), Blueprints.KITCHEN)) return false;
		BlockPos kitchen = Specials.get(c.village(), Blueprints.KITCHEN);
		if (kitchen == null || !c.level().hasChunkAt(kitchen)) return false;
		BlockPos pos = c.st().target;
		AbstractFurnaceBlockEntity furnace = pos == null ? null : furnaceAt(c.level(), pos, worker);
		if (furnace == null || !insideKitchen(pos, kitchen)) {
			if (c.now() < c.st().buildCheckAt) return false;
			c.st().buildCheckAt = c.now() + SCAN_INTERVAL;
			pos = findFurnace(c.level(), kitchen, worker);
			furnace = pos == null ? null : furnaceAt(c.level(), pos, worker);
			if (furnace == null) return false;
			c.st().target = pos;
		}

		ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		if (claim == null) return false;
		if (claim.isEmpty()) {
			if (furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) != 0
				|| !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()) return false;
			Batch batch = findBatch(c.level(), c.village(), furnace, worker);
			// The butcher never parks raw meat in a smoker that cannot burn: no public fuel, no batch.
			if (batch != null && worker == Worker.BUTCHER && !hasFuel(c.level(), c.village(), furnace)) return false;
			if (batch == null || !JobUtil.approach(c, pos, 3.0)) return batch != null;
			if (!loadBatch(c.level(), furnace, batch.source(), batch.slot(), batch.recipe(), worker)) return false;
			ItemStack input = furnace.getItem(0);
			c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
			c.setActivity(worker == Worker.BUTCHER ? "beschickt den Räucherofen mit Rohfleisch"
				: worker == Worker.COOK ? "beschickt den Räucherofen nach Rezept" : "beschickt den Dorfofen nach Rezept");
			Anim.hold(c, input.getItem());
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 80;
			return true;
		}

		if (!JobUtil.approach(c, pos, 3.0)) return true;
		ItemStack output = furnace.getItem(2);
		if (!output.isEmpty()) {
			int moved = deliverClaimedOutput(c.level(), c.village(), furnace);
			if (moved <= 0) return false;
			c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
			c.setActivity(smokes(worker) ? "lagert geräucherte Mahlzeit ein" : "lagert Schmiedeerzeugnis ein");
			Anim.hold(c, claim.getItem());
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 80;
			return true;
		}

		ItemStack input = furnace.getItem(0);
		if (input.isEmpty()) {
			// An interrupted/removed batch cannot keep the public furnace locked forever.
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			furnace.setChanged();
			return false;
		}
		AbstractCookingRecipe recipe = matchingRecipe(c.level(), input, recipeType(c.level().getBlockState(pos)));
		if (recipe == null || !ItemStack.isSameItemSameComponents(recipe.assemble(new SingleRecipeInput(input)), claim)) return false;
		if (furnace.getItem(1).isEmpty()) {
			Container storage = Lager.combined(c.level(), c.village());
			ItemStack fuel = storage == null ? ItemStack.EMPTY : withdraw(storage, s -> s.is(ItemTags.COALS));
			if (fuel.isEmpty()) return false;
			furnace.setItem(1, fuel);
		}
		if (!furnace.getItem(1).is(ItemTags.COALS)) return false;
		furnace.setChanged();
		c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
		c.setActivity(smokes(worker) ? "räuchert eine Mahlzeit nach Rezept" : "schmilzt nach geladenem Rezept");
		Anim.hold(c, input.getItem());
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 100;
		return true;
	}

	private static Batch findBatch(ServerLevel level, de.villagerplus.village.VillageRef ref, AbstractFurnaceBlockEntity furnace, Worker worker) {
		List<Container> stores = smokes(worker)
			? Specials.containers(level, ref, Blueprints.GRANARY) : Balance.storage(level, ref);
		if (stores.isEmpty()) return null;
		RecipeType<? extends AbstractCookingRecipe> type = recipeType(level.getBlockState(furnace.getBlockPos()));
		if (type == null) return null;
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof AbstractCookingRecipe recipe) || recipe.getType() != type) continue;
			for (Container store : stores) for (int slot = 0; slot < store.getContainerSize(); slot++) {
				ItemStack stack = store.getItem(slot);
				if (stack.isEmpty() || !recipe.input().test(stack)) continue;
				if (worker == Worker.BUTCHER && !rawMeat(stack)) continue;
				ItemStack one = stack.copyWithCount(1);
				if (!recipe.matches(new SingleRecipeInput(one), level)
					|| matchingRecipe(level, one, type) != recipe) continue;
				ItemStack output = recipe.assemble(new SingleRecipeInput(one));
				if (validOutput(output, worker) && hasVillageStorageCapacity(level, ref, output)) return new Batch(store, slot, recipe);
			}
		}
		return null;
	}

	/** Raw meat and fish (vanilla and convention tags); the loaded smoking recipe still decides what actually cooks. */
	private static boolean rawMeat(ItemStack stack) {
		return stack.is(ItemTags.MEAT) || stack.is(ItemTags.FISHES)
			|| stack.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags.RAW_MEAT_FOODS)
			|| stack.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags.RAW_FISH_FOODS);
	}

	/** Fuel for the next batch: either already in the furnace fuel slot or available as coal in public village stock. */
	private static boolean hasFuel(ServerLevel level, VillageRef ref, AbstractFurnaceBlockEntity furnace) {
		if (furnace.getItem(1).is(ItemTags.COALS)) return true;
		Container storage = Lager.combined(level, ref);
		return storage != null && JobUtil.count(storage, s -> s.is(ItemTags.COALS)) > 0;
	}

	static AbstractCookingRecipe matchingRecipe(ServerLevel level, ItemStack input, RecipeType<? extends AbstractCookingRecipe> type) {
		SingleRecipeInput single = new SingleRecipeInput(input);
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (holder.value() instanceof AbstractCookingRecipe recipe && recipe.getType() == type && recipe.matches(single, level)) return recipe;
		}
		return null;
	}

	private static boolean loadBatch(ServerLevel level, AbstractFurnaceBlockEntity furnace, Container source, int slot,
		AbstractCookingRecipe recipe, Worker worker) {
		if (slot < 0 || slot >= source.getContainerSize()) return false;
		ItemStack available = source.getItem(slot);
		if (available.isEmpty()) return false;
		ItemStack input = available.copyWithCount(1);
		RecipeType<? extends AbstractCookingRecipe> type = recipeType(level.getBlockState(furnace.getBlockPos()));
		if (type == null || recipe.getType() != type || matchingRecipe(level, input, type) != recipe) return false;
		return commitBatch(source, slot, input, craftingRemainder(input), furnace, recipe, worker);
	}

	static boolean loadBatchForCheck(ServerLevel level, AbstractFurnaceBlockEntity furnace, Container source, int slot,
		AbstractCookingRecipe recipe, boolean cook) {
		return loadBatch(level, furnace, source, slot, recipe, cook ? Worker.COOK : Worker.SMITH);
	}

	static boolean loadBatchWithRemainderForCheck(ServerLevel level, AbstractFurnaceBlockEntity furnace,
		Container source, int slot, AbstractCookingRecipe recipe, ItemStack remainder) {
		if (slot < 0 || slot >= source.getContainerSize()) return false;
		ItemStack available = source.getItem(slot);
		if (available.isEmpty()) return false;
		ItemStack input = available.copyWithCount(1);
		RecipeType<? extends AbstractCookingRecipe> type = recipeType(level.getBlockState(furnace.getBlockPos()));
		if (type == null || recipe.getType() != type || matchingRecipe(level, input, type) != recipe) return false;
		return commitBatch(source, slot, input, remainder, furnace, recipe, Worker.SMITH);
	}

	private static boolean commitBatch(Container source, int slot, ItemStack input, ItemStack remainder,
		AbstractFurnaceBlockEntity furnace, AbstractCookingRecipe recipe, Worker worker) {
		if (remainder == null) remainder = ItemStack.EMPTY;
		ItemStack[] snapshot = new ItemStack[source.getContainerSize()];
		for (int i = 0; i < snapshot.length; i++) snapshot[i] = source.getItem(i).copy();
		if (!claimBatch(furnace, input, recipe, worker)) return false;

		ItemStack removed = source.removeItem(slot, 1);
		if (removed.getCount() != 1 || !ItemStack.isSameItemSameComponents(removed, input)) {
			rollbackBatch(source, snapshot, furnace);
			return false;
		}
		if (!remainder.isEmpty()) {
			ItemStack left = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, source, remainder.copy(), null);
			if (!left.isEmpty()) {
				rollbackBatch(source, snapshot, furnace);
				return false;
			}
		}
		source.setChanged();
		furnace.setChanged();
		return true;
	}

	private static void rollbackBatch(Container source, ItemStack[] snapshot, AbstractFurnaceBlockEntity furnace) {
		for (int i = 0; i < snapshot.length; i++) source.setItem(i, snapshot[i].copy());
		source.setChanged();
		furnace.setItem(0, ItemStack.EMPTY);
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
		furnace.setChanged();
	}

	static ItemStack craftingRemainder(ItemStack input) {
		if (input == null || input.isEmpty()) return ItemStack.EMPTY;
		var remainder = input.getItem().getCraftingRemainder();
		return remainder == null ? ItemStack.EMPTY : remainder.create();
	}

	static boolean claimBatch(AbstractFurnaceBlockEntity furnace, ItemStack input, AbstractCookingRecipe recipe) {
		return claimBatch(furnace, input, recipe, Worker.SMITH);
	}

	private static boolean claimBatch(AbstractFurnaceBlockEntity furnace, ItemStack input, AbstractCookingRecipe recipe, Worker worker) {
		if (furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null
			|| furnace.getAttached(VillagerPlus.SMITH_FURNACE) == null
			|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) != 0
			|| !furnace.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty()
			|| !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()
			|| input.isEmpty() || input.getCount() != 1
			|| !furnace.canPlaceItem(0, input)) return false;
		ItemStack expected = recipe.assemble(new SingleRecipeInput(input));
		if (!validOutput(expected, worker)) return false;
		// Persist ownership before exposing the input to vanilla furnace ticking.
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, expected.copy());
		furnace.setItem(0, input.copy());
		furnace.setChanged();
		return true;
	}

	static int deliverClaimedOutput(ServerLevel level, de.villagerplus.village.VillageRef ref, AbstractFurnaceBlockEntity furnace) {
		ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		ItemStack output = furnace.getItem(2);
		if (claim == null || claim.isEmpty() || output.isEmpty() || !ItemStack.isSameItemSameComponents(output, claim)) return 0;
		int offered = Math.min(output.getCount(), claim.getCount());
		ItemStack remainder = StockPolicy.insertVillage(level, ref, output.copyWithCount(offered));
		int moved = offered - remainder.getCount();
		if (moved <= 0) return 0;
		output.shrink(moved);
		furnace.setItem(2, output);
		ItemStack remainingClaim = claim.copy();
		remainingClaim.shrink(moved);
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, remainingClaim);
		furnace.setChanged();
		return moved;
	}

	static SmeltingRecipe matchingRecipeForCheck(ServerLevel level, ItemStack input) {
		AbstractCookingRecipe recipe = matchingRecipe(level, input, RecipeType.SMELTING);
		return recipe instanceof SmeltingRecipe smelting ? smelting : null;
	}

	private static boolean validOutput(ItemStack output, Worker worker) {
		if (output.isEmpty()) return false;
		if (smokes(worker)) return Meals.isPreparedFood(output);
		StockPolicy.Category category = StockPolicy.category(output.getItem());
		return category == StockPolicy.Category.MATERIAL || category == StockPolicy.Category.TOOLS;
	}

	private static ItemStack withdraw(Container store, Predicate<ItemStack> match) {
		for (int slot = 0; slot < store.getContainerSize(); slot++) {
			ItemStack stack = store.getItem(slot);
			if (!stack.isEmpty() && match.test(stack)) return store.removeItem(slot, 1);
		}
		return ItemStack.EMPTY;
	}

	private static int count(List<Container> stores, Predicate<ItemStack> match) {
		int count = 0;
		for (Container store : stores) count += JobUtil.count(store, match);
		return count;
	}

	private static AbstractFurnaceBlockEntity furnaceAt(ServerLevel level, BlockPos pos, Worker worker) {
		if (!level.hasChunkAt(pos)) return null;
		BlockState state = level.getBlockState(pos);
		if (recipeType(state) == null || smokes(worker) && !state.is(Blocks.SMOKER)
			|| worker == Worker.SMITH && state.is(Blocks.SMOKER)) return null;
		if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)
			|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null
			|| furnace.getAttached(VillagerPlus.SMITH_FURNACE) == null) return null;
		Integer pending = furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE);
		return worker == Worker.SMITH && pending != 0 ? null : furnace;
	}

	private static BlockPos findFurnace(ServerLevel level, BlockPos center, Worker worker) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 8, 16))) {
			AbstractFurnaceBlockEntity furnace = furnaceAt(level, pos, worker);
			if (furnace == null) continue;
			double distance = pos.distSqr(center);
			if (distance < bestDistance) { best = pos.immutable(); bestDistance = distance; }
		}
		return best;
	}

	private static boolean insideKitchen(BlockPos furnace, BlockPos origin) {
		return Math.abs(furnace.getX() - origin.getX()) <= 16 && Math.abs(furnace.getZ() - origin.getZ()) <= 16
			&& furnace.getY() >= origin.getY() - 4 && furnace.getY() <= origin.getY() + 16;
	}

	static RecipeType<? extends AbstractCookingRecipe> recipeType(BlockState state) {
		if (state.is(Blocks.FURNACE)) return RecipeType.SMELTING;
		if (state.is(Blocks.BLAST_FURNACE)) return RecipeType.BLASTING;
		if (state.is(Blocks.SMOKER)) return RecipeType.SMOKING;
		return null;
	}

	private record Batch(Container source, int slot, AbstractCookingRecipe recipe) { }
	private record PlanInput(int slot, ItemStack input, AbstractCookingRecipe recipe, ItemStack output) { }
}
