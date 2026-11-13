package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Plan-only quantities for the loaded-recipe cobblestone to cracked-brick chain. */
public final class PlanStoneSupply {
	private PlanStoneSupply() { }

	/** True only when missing downstream inputs can usefully be supplied from the real mine. */
	public static boolean needsCobble(ServerLevel level, VillageData data, Container stock) {
		if (!data.hasPlan() || stock == null) return false;
		// Dev aid: a flag file forces stone demand so the mine fallback can be exercised in a natural world.
		if (DevFlags.has("vp-forcestone.flag")) return true;
		int stoneTarget = stoneTarget(data, stock);
		int missingStone = Math.max(0, stoneTarget - plannedCount(level, data, stock, Items.STONE));
		int requestedCobble = wanted(data, Items.COBBLESTONE);
		int spareCobble = Math.max(0, count(stock, Items.COBBLESTONE) - requestedCobble);
		if (missingStone <= spareCobble || missingStone == 0) return false;
		return supports(level, stoneTarget > wanted(data, Items.STONE), wanted(data, Items.CRACKED_STONE_BRICKS) > 0)
			&& spareCobble < Math.min(16, missingStone);
	}

	/** A load of this many cobblestones is worth the walk to the public chest while the plan waits for stone. */
	static final int DELIVERY_LOAD = 12;

	/** Cobblestone the plan asks for (directly, or as stairs and slabs to craft) beyond what the public chest holds. */
	static int cobbleShortage(VillageData data, Container stock) {
		if (data == null || stock == null || !data.hasPlan()) return 0;
		int stairs = wanted(data, Items.COBBLESTONE_STAIRS);
		int slabs = wanted(data, Items.COBBLESTONE_SLAB);
		int demand = wanted(data, Items.COBBLESTONE) + (stairs * 3 + 1) / 2 + (slabs + 1) / 2;
		return Math.max(0, demand - count(stock, Items.COBBLESTONE));
	}

	/**
	 * True when a resident who carries {@code carried} cobblestones should bring them to the public chest now: the plan (or its
	 * stone chain) is short of cobblestone and the load is either a full delivery or covers the whole shortage.
	 */
	public static boolean cobbleDeliveryDue(ServerLevel level, VillageData data, Container stock, int carried) {
		if (carried <= 0 || data == null || stock == null || !data.hasPlan()) return false;
		int shortage = cobbleShortage(data, stock);
		if (shortage <= 0 && !needsCobble(level, data, stock)) return false;
		return carried >= DELIVERY_LOAD || (shortage > 0 && carried >= shortage);
	}

	/** Starts plan mining with an actual stored pickaxe or one paid from loaded recipes and public stock. */
	public static boolean supplyMiningPickaxe(Ctx c, Container stock) {
		return supplyMiningPickaxe(c, stock, false);
	}

	/** {@code force} skips the mine-based cobble need check (used when the plan asks for cobblestone directly). */
	public static boolean supplyMiningPickaxe(Ctx c, Container stock, boolean force) {
		if (c == null || c.village() == null || stock == null || (!force && !needsCobble(c.level(), c.village().data(), stock))
			|| !Mining.pickaxe(c.v().getInventory()).isEmpty()) return false;
		StoredPickaxe stored = storedTool(c.level(), c.village());
		if (stored != null) {
			c.setActivity("holt eine Spitzhacke aus der Werkzeugkammer für den Bauplan");
			if (!JobUtil.approachLong(c, stored.origin(), 8.0)) return true;
			if (!Mining.pickaxe(c.v().getInventory()).isEmpty()) return false;
			ItemStack available = stored.container().getItem(stored.slot());
			if (!usablePickaxe(available)) return false;
			SimpleContainer preview = copy(c.v().getInventory());
			if (!HopperBlockEntity.addItem(null, preview, available.copyWithCount(1), null).isEmpty()) return false;
			ItemStack paid = stored.container().removeItem(stored.slot(), 1);
			if (paid.isEmpty()) return false;
			if (!usablePickaxe(paid)) {
				stored.container().setItem(stored.slot(), paid);
				stored.container().setChanged();
				return false;
			}
			stored.container().setChanged();
			ItemStack left = HopperBlockEntity.addItem(null, c.v().getInventory(), paid, null);
			if (!left.isEmpty()) {
				HopperBlockEntity.addItem(null, stored.container(), left, null);
				return false;
			}
			c.st().cooldownUntil = c.now() + 40;
			c.mem().jobsDone++;
			Anim.hold(c, paid.getItem());
			return true;
		}

		if (craftPlanPickaxe(c.level(), stock, c.v().getInventory())) {
			c.setActivity("fertigt eine Spitzhacke aus Dorfmaterial für den Bauplan");
			c.st().cooldownUntil = c.now() + 80;
			c.mem().jobsDone++;
			Anim.hold(c, Mining.pickaxe(c.v().getInventory()).getItem());
			return true;
		}
		return false;
	}

	/** A real loaded wooden/stone pickaxe recipe, paid from the worker's carried items and public village stock. */
	static boolean craftPlanPickaxe(ServerLevel level, Container stock, Container inventory) {
		if (level == null || stock == null || inventory == null) return false;
		Container available = new CompoundContainer(inventory, stock);
		// Last resort: no sticks anywhere but planks in stock -> craft sticks first, the pickaxe follows on the next call.
		boolean noSticks = JobUtil.count(available, stack -> stack.is(Items.STICK)) < 2;
		for (Item target : noSticks ? new Item[] {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.STICK}
				: new Item[] {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE}) {
			boolean stickStep = target == Items.STICK;
			for (var holder : level.recipeAccess().getRecipes()) {
				if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
				CraftingInput input = RecipeCrafting.inputFor(recipe, available, level);
				if (input == null) continue;
				ItemStack output = recipe.assemble(input);
				if (!output.is(target)) continue;
				if (!stickStep && (!output.is(ItemTags.PICKAXES) || output.getCount() != 1 || !output.isDamageableItem())) continue;
				List<ItemStack> costs = costs(input);
				List<ItemStack> missing = missing(inventory, costs);
				SimpleContainer preview = copy(inventory);
				boolean fits = true;
				for (ItemStack ingredient : missing) {
					if (JobUtil.count(stock, stack -> ItemStack.isSameItemSameComponents(stack, ingredient)) < ingredient.getCount()
						|| !HopperBlockEntity.addItem(null, preview, ingredient.copy(), null).isEmpty()) {
						fits = false;
						break;
					}
				}
				List<ItemStack> remainders = recipe.getRemainingItems(input);
				if (!fits || !RecipeCrafting.commit(preview, input, output, remainders)) continue;

				for (ItemStack ingredient : missing) {
					int taken = JobUtil.take(stock,
						stack -> ItemStack.isSameItemSameComponents(stack, ingredient), ingredient.getCount());
					if (taken > 0) {
						ItemStack left = HopperBlockEntity.addItem(null, inventory,
							ingredient.copyWithCount(taken), null);
						if (!left.isEmpty()) HopperBlockEntity.addItem(null, stock, left, null);
					}
					if (taken != ingredient.getCount()) return false;
				}
				int sticksBefore = JobUtil.count(inventory, stack -> stack.is(Items.STICK));
				boolean crafted = RecipeCrafting.commit(inventory, input, output, remainders);
				if (crafted && stickStep) {
					// Sticks are public village stock, so whichever worker comes next can finish the pickaxe.
					// Only the sticks just crafted move; the worker's own sticks stay in the inventory.
					int crafted0 = Math.max(0, JobUtil.count(inventory, stack -> stack.is(Items.STICK)) - sticksBefore);
					int made = crafted0 == 0 ? 0 : JobUtil.take(inventory, stack -> stack.is(Items.STICK), crafted0);
					if (made > 0) {
						ItemStack left = HopperBlockEntity.addItem(null, stock, new ItemStack(Items.STICK, made), null);
						if (!left.isEmpty()) HopperBlockEntity.addItem(null, inventory, left, null);
					}
				}
				return crafted;
			}
		}
		return false;
	}

	private record StoredPickaxe(Container container, int slot, BlockPos origin) { }

	private static StoredPickaxe storedTool(ServerLevel level, VillageRef ref) {
		BlockPos origin = Specials.get(ref, Blueprints.TOOLSHED);
		if (origin == null || !level.hasChunkAt(origin)) return null;
		for (Container container : Specials.containers(level, ref, Blueprints.TOOLSHED)) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				if (usablePickaxe(container.getItem(slot))) return new StoredPickaxe(container, slot, origin);
			}
		}
		return null;
	}

	private static boolean usablePickaxe(ItemStack stack) {
		return !stack.isEmpty() && stack.is(ItemTags.PICKAXES)
			&& (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
	}

	private static List<ItemStack> costs(CraftingInput input) {
		List<ItemStack> costs = new ArrayList<>();
		for (int slot = 0; slot < input.size(); slot++) {
			ItemStack ingredient = input.getItem(slot);
			if (ingredient.isEmpty()) continue;
			ItemStack matching = costs.stream()
				.filter(existing -> ItemStack.isSameItemSameComponents(existing, ingredient)).findFirst().orElse(null);
			if (matching == null) costs.add(ingredient.copyWithCount(1));
			else matching.grow(1);
		}
		return costs;
	}

	private static List<ItemStack> missing(Container inventory, List<ItemStack> costs) {
		List<ItemStack> missing = new ArrayList<>();
		for (ItemStack cost : costs) {
			int amount = cost.getCount() - JobUtil.count(inventory,
				stack -> ItemStack.isSameItemSameComponents(stack, cost));
			if (amount > 0) missing.add(cost.copyWithCount(amount));
		}
		return missing;
	}

	private static SimpleContainer copy(Container source) {
		SimpleContainer copy = new SimpleContainer(source.getContainerSize());
		for (int slot = 0; slot < source.getContainerSize(); slot++) copy.setItem(slot, source.getItem(slot).copy());
		return copy;
	}

	/** Whether an unmet stone-family plan output still needs one or more conversion stages. */
	public static boolean needsFurnace(ServerLevel level, VillageData data, Container stock) {
		if (!data.hasPlan() || stock == null) return false;
		boolean stone = plannedCount(level, data, stock, Items.STONE) < wanted(data, Items.STONE);
		boolean bricks = count(stock, Items.STONE_BRICKS) < wanted(data, Items.STONE_BRICKS);
		boolean cracked = plannedCount(level, data, stock, Items.CRACKED_STONE_BRICKS) < wanted(data, Items.CRACKED_STONE_BRICKS);
		if (!stone && !bricks && !cracked) return false;
		return supports(level, bricks || cracked, cracked);
	}

	/**
	 * Crafts one real loaded stone-brick batch. Separately requested stone remains reserved, and the recipe's actual
	 * remainders and output must fit the same real depot transaction before anything is consumed.
	 */
	static boolean craftRequestedBricks(ServerLevel level, VillageData data, Container stock, boolean commit) {
		if (!data.hasPlan() || stock == null || count(stock, Items.STONE_BRICKS) >= brickTarget(data, stock)
		|| count(stock, Items.STONE) - wanted(data, Items.STONE)
			- claimedOutput(level, data, Items.STONE) < 4) return false;
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			var input = RecipeCrafting.inputFor(recipe, stock, level);
			if (input == null || input.size() != 4) continue;
			boolean onlyStone = true;
			for (int i = 0; i < input.size(); i++) if (!input.getItem(i).is(Items.STONE)) onlyStone = false;
			ItemStack output = recipe.assemble(input);
			if (!onlyStone || !output.is(Items.STONE_BRICKS) || output.getCount() != 4) continue;
			if (!commit) return true;
			return RecipeCrafting.commit(stock, input, output, recipe.getRemainingItems(input));
		}
		return false;
	}

	static boolean planNeedsSmelting(ServerLevel level, VillageData data, Container stock, Item input, Item output) {
		if (!data.hasPlan() || stock == null || input == null || output == null) return false;
		if (input == Items.COBBLESTONE && output == Items.STONE) {
			return plannedCount(level, data, stock, Items.STONE) < stoneTarget(data, stock)
				&& count(stock, Items.COBBLESTONE) > wanted(data, Items.COBBLESTONE)
				&& supports(level, stoneTarget(data, stock) > wanted(data, Items.STONE), wanted(data, Items.CRACKED_STONE_BRICKS) > 0);
		}
		if (input == Items.STONE_BRICKS && output == Items.CRACKED_STONE_BRICKS) {
			return plannedCount(level, data, stock, Items.CRACKED_STONE_BRICKS) < wanted(data, Items.CRACKED_STONE_BRICKS)
				&& count(stock, Items.STONE_BRICKS) > wanted(data, Items.STONE_BRICKS)
				&& supports(level, true, true);
		}
		return false;
	}

	/** Checks that every step uses the server's currently loaded recipe registry. */
	static boolean supports(ServerLevel level, boolean needsBricks, boolean needsCracked) {
		var stoneRecipe = SmithSmelting.matchingRecipeForCheck(level, new ItemStack(Items.COBBLESTONE));
		if (stoneRecipe == null) return false;
		ItemStack stoneResult = stoneRecipe.assemble(new SingleRecipeInput(new ItemStack(Items.COBBLESTONE)));
		if (!stoneResult.is(Items.STONE) || stoneResult.getCount() != 1) return false;
		if (needsBricks) {
			SimpleContainer input = new SimpleContainer(4);
			for (int i = 0; i < 4; i++) input.setItem(i, new ItemStack(Items.STONE));
			boolean found = false;
			for (var holder : level.recipeAccess().getRecipes()) {
				if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
				var grid = RecipeCrafting.inputFor(recipe, input, level);
				if (grid == null || grid.size() != 4) continue;
				ItemStack result = recipe.assemble(grid);
				if (result.is(Items.STONE_BRICKS) && result.getCount() == 4) { found = true; break; }
			}
			if (!found) return false;
		}
		if (needsCracked) {
			var recipe = SmithSmelting.matchingRecipeForCheck(level, new ItemStack(Items.STONE_BRICKS));
			if (recipe == null) return false;
			ItemStack result = recipe.assemble(new SingleRecipeInput(new ItemStack(Items.STONE_BRICKS)));
			if (!result.is(Items.CRACKED_STONE_BRICKS) || result.getCount() != 1) return false;
		}
		return true;
	}

	private static int stoneTarget(VillageData data, Container stock) {
		int brickBatches = divideRoundUp(Math.max(0, brickTarget(data, stock) - count(stock, Items.STONE_BRICKS)), 4);
		return saturatingAdd(wanted(data, Items.STONE), brickBatches * 4);
	}

	/** Requested bricks plus one real recipe input for each cracked brick still missing. */
	private static int brickTarget(VillageData data, Container stock) {
		int missingCracked = Math.max(0, wanted(data, Items.CRACKED_STONE_BRICKS) - count(stock, Items.CRACKED_STONE_BRICKS));
		return saturatingAdd(wanted(data, Items.STONE_BRICKS), missingCracked);
	}

	private static int divideRoundUp(int value, int divisor) {
		return value <= 0 ? 0 : 1 + (value - 1) / divisor;
	}

	private static int saturatingAdd(int a, int b) {
		long sum = (long) a + b;
		return (int) Math.min(Integer.MAX_VALUE, sum);
	}

	static int wanted(VillageData data, Item item) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
		return wanted(data, id);
	}

	static int wanted(VillageData data, String id) {
		int total = 0;
		for (String entry : data.wanted) {
			String[] parts = entry.split("#", 2);
			if (!parts[0].equals(id)) continue;
			try { total = Math.max(total, Math.max(0, parts.length == 2 ? Integer.parseInt(parts[1]) : 1)); }
			catch (NumberFormatException ignored) { }
		}
		return total;
	}

	private static int count(Container container, Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	/** Counts a still-owned plan batch in the village's marked furnace as already reserved output. */
	private static int plannedCount(ServerLevel level, VillageData data, Container stock, Item item) {
		long total = (long) count(stock, item) + claimedOutput(level, data, item);
		return (int) Math.min(Integer.MAX_VALUE, total);
	}

	private static int claimedOutput(ServerLevel level, VillageData data, Item item) {
		if (level == null || data == null || !data.hasPlan() || data.chest.isEmpty() || item == null) return 0;
		BlockPos depot = data.chest.get();
		int total = 0;
		for (BlockPos pos : BlockPos.betweenClosed(depot.offset(-8, -2, -8), depot.offset(8, 4, 8))) {
			if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FURNACE)
				|| !(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)
				|| !Integer.valueOf(0).equals(furnace.getAttached(de.villagerplus.VillagerPlus.COMMUNAL_FURNACE))) continue;
			ItemStack claim = furnace.getAttached(de.villagerplus.VillagerPlus.SMITH_FURNACE);
			if (claim == null || claim.isEmpty() || !claim.is(item)) continue;
			ItemStack input = furnace.getItem(0);
			ItemStack output = furnace.getItem(2);
			// Only count a claim while its real input or matching output still anchors it to this furnace.
			if (input.isEmpty() && output.isEmpty()) continue;
			if (!output.isEmpty() && !ItemStack.isSameItemSameComponents(output, claim)) continue;
			if (!input.isEmpty()) {
				var recipe = SmithSmelting.matchingRecipe(level, input, net.minecraft.world.item.crafting.RecipeType.SMELTING);
				if (recipe == null || !ItemStack.isSameItemSameComponents(
					recipe.assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(input)), claim)) continue;
			}
			total = (int) Math.min(Integer.MAX_VALUE, (long) total + claim.getCount());
		}
		return total;
	}
}
