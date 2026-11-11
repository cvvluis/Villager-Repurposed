package de.villagerplus.job;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;

/** Applies loaded server recipes to village inventory as single, component-aware transactions. */
public final class RecipeCrafting {
	private static final int INTERMEDIATE_OUTPUT_CAP = 64;
	private static final String FARMERS_DELIGHT_COOKING_POT_RECIPE =
		"vectorwing.farmersdelight.common.crafting.CookingPotRecipe";
	private static final String FARMERS_DELIGHT_CUTTING_BOARD_RECIPE =
		"vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe";
	private RecipeCrafting() { }

	static boolean craft(Ctx c, Container chest, Predicate<ItemStack> outputFilter, int outputCap) {
		return craft(c, chest, JobUtil.chest(c), outputFilter, outputCap);
	}

	public static boolean craft(Ctx c, Container chest, BlockPos workPos, Predicate<ItemStack> outputFilter, int outputCap) {
		for (RecipeHolder<?> holder : c.level().recipeAccess().getRecipes()) {
			if (holder.value() instanceof CraftingRecipe recipe) {
				ItemStack preview = preview(recipe);
				if (preview != null && (preview.isEmpty() || !outputFilter.test(preview)
					|| !outputAllowed(c, chest, outputFilter, preview, outputCap, true))) {
					continue;
				}
				if (craftLoadedRecipe(c, chest, workPos, recipe, outputFilter, outputCap, true)) return true;
			} else if (holder.value() instanceof StonecutterRecipe recipe
				&& craftStonecutterRecipe(c, chest, workPos, recipe, outputFilter, outputCap, true)) {
				return true;
			} else if (holder.value() instanceof CampfireCookingRecipe recipe
				&& craftCampfireCookingRecipe(c, chest, workPos, recipe, outputFilter, outputCap, true)) {
				return true;
			} else if (holder.value() instanceof SmithingRecipe recipe
				&& craftSmithingRecipe(c, chest, workPos, recipe, outputFilter, outputCap, true)) {
				return true;
			} else if (holder.value().getClass().getName().equals(FARMERS_DELIGHT_COOKING_POT_RECIPE)
				&& craftFarmersDelightCookingPotRecipe(c, chest, workPos, holder.value(), outputFilter, outputCap, true)) {
				return true;
			} else if (holder.value().getClass().getName().equals(FARMERS_DELIGHT_CUTTING_BOARD_RECIPE)
				&& craftFarmersDelightCuttingBoardRecipe(c, chest, workPos, holder.value(), outputFilter, outputCap, true)) {
				return true;
			}
		}
		return false;
	}

	/** Read-only check for a loaded vanilla crafting recipe that can produce the requested output from this stock. */
	public static boolean canCraftCraftingOutput(Ctx c, Container chest, Predicate<ItemStack> outputFilter, int outputCap) {
		if (c == null || chest == null || outputFilter == null || outputCap < 0) return false;
		for (RecipeHolder<?> holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			ItemStack preview = preview(recipe);
			if (preview != null && (preview.isEmpty() || !outputFilter.test(preview)
				|| !outputAllowed(c, chest, outputFilter, preview, outputCap, true))) continue;
			CraftingInput input = inputFor(recipe, chest, c.level());
			if (input == null) continue;
			ItemStack output = recipe.assemble(input);
			if (output.isEmpty() || !outputFilter.test(output)
				|| !outputAllowed(c, chest, outputFilter, output, outputCap, true)) continue;
			if (commit(copy(chest), input, output, recipe.getRemainingItems(input))) return true;
		}
		return false;
	}

	/** Applies one loaded Farmer's Delight cooking-pot recipe, including its declared container remainder. */
	static boolean craftFarmersDelightCookingPotRecipe(Ctx c, Container chest, BlockPos workPos, Object rawRecipe,
		Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
		return FarmersDelightCookingPot.craft(c, chest, workPos, rawRecipe, outputFilter, outputCap, allowPlanBatch);
	}

	/** Applies one loaded Farmer's Delight cutting-board recipe, its chance results, and its damaged tool atomically. */
	static boolean craftFarmersDelightCuttingBoardRecipe(Ctx c, Container chest, BlockPos workPos, Object rawRecipe,
		Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
		return FarmersDelightCuttingBoard.craft(c, chest, workPos, rawRecipe, outputFilter, outputCap, allowPlanBatch);
	}

	/** Applies one loaded campfire recipe through the recipe manager and a single atomic pantry transaction. */
	static boolean craftCampfireCookingRecipe(Ctx c, Container chest, BlockPos workPos, CampfireCookingRecipe recipe,
		Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stock = chest.getItem(slot);
			if (stock.isEmpty()) continue;
			ItemStack consumed = stock.copyWithCount(1);
			SingleRecipeInput input = new SingleRecipeInput(consumed);
			var loaded = c.level().recipeAccess().getRecipeFor(RecipeType.CAMPFIRE_COOKING, input, c.level());
			if (loaded.isEmpty() || loaded.get().value() != recipe) continue;
			ItemStack output = loaded.get().value().assemble(input);
			if (output.isEmpty() || !outputFilter.test(output)
				|| !outputAllowed(c, chest, outputFilter, output, outputCap, allowPlanBatch)) continue;
			if (workPos != null && !JobUtil.approachLong(c, workPos, 3.0)) return true;
			// CampfireCookingRecipe has no remainder contract in the 26.2 Recipe API.
			if (!commit(chest, List.of(consumed), List.of(output.copy()))) continue;
			c.setActivity("bereitet zu: " + output.getHoverName().getString());
			Anim.hold(c, output.getItem());
			c.st().cooldownUntil = c.now() + 100;
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	/** Applies one loaded vanilla stonecutter recipe using the exact input stack and assembled result. */
	static boolean craftStonecutterRecipe(Ctx c, Container chest, BlockPos workPos, StonecutterRecipe recipe,
		Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stock = chest.getItem(slot);
			if (stock.isEmpty()) continue;
			ItemStack consumed = stock.copyWithCount(1);
			SingleRecipeInput input = new SingleRecipeInput(consumed);
			if (!recipe.matches(input, c.level())) continue;
			ItemStack output = recipe.assemble(input);
			if (output.isEmpty() || !outputFilter.test(output)
				|| !outputAllowed(c, chest, outputFilter, output, outputCap, allowPlanBatch)) continue;
			if (workPos != null && !JobUtil.approachLong(c, workPos, 3.0)) return true;
			// SingleItemRecipe has no remainder contract; vanilla stonecutting returns no container items.
			if (!commit(chest, List.of(consumed), List.of(output.copy()))) continue;
			c.setActivity("stellt her: " + output.getHoverName().getString());
			Anim.hold(c, output.getItem());
			c.st().cooldownUntil = c.now() + 100;
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	/** Applies one loaded smithing transform or trim through the recipe manager and an atomic pantry transaction. */
	static boolean craftSmithingRecipe(Ctx c, Container chest, BlockPos workPos, SmithingRecipe recipe,
		Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
		SmithingRecipeInput input = smithingInputFor(recipe, chest, c.level());
		if (input == null) return false;
		var loaded = c.level().recipeAccess().getRecipeFor(RecipeType.SMITHING, input, c.level());
		if (loaded.isEmpty() || loaded.get().value() != recipe) return false;
		ItemStack output = loaded.get().value().assemble(input);
		if (output.isEmpty() || !outputFilter.test(output)
			|| !outputAllowed(c, chest, outputFilter, output, outputCap, allowPlanBatch)) return false;
		if (workPos != null && !JobUtil.approachLong(c, workPos, 3.0)) return true;
		if (!commit(chest, input, output, List.of())) return false;
		c.setActivity("stellt her: " + output.getHoverName().getString());
		Anim.hold(c, output.getItem());
		c.st().cooldownUntil = c.now() + 100;
		c.mem().jobsDone++;
		return true;
	}

	/** Fills only recipe-declared smithing slots, assigning each one real inventory unit exactly once. */
	private static SmithingRecipeInput smithingInputFor(SmithingRecipe recipe, Container chest, Level level) {
		List<Ingredient> required = new ArrayList<>(3);
		int templateIndex = -1;
		if (recipe.templateIngredient().isPresent()) {
			templateIndex = required.size();
			required.add(recipe.templateIngredient().orElseThrow());
		}
		int baseIndex = required.size();
		required.add(recipe.baseIngredient());
		int additionIndex = -1;
		if (recipe.additionIngredient().isPresent()) {
			additionIndex = required.size();
			required.add(recipe.additionIngredient().orElseThrow());
		}
		List<ItemStack> chosen = assign(chest, required);
		if (chosen == null) return null;
		SmithingRecipeInput input = new SmithingRecipeInput(
			templateIndex < 0 ? ItemStack.EMPTY : chosen.get(templateIndex),
			chosen.get(baseIndex),
			additionIndex < 0 ? ItemStack.EMPTY : chosen.get(additionIndex));
		return recipe.matches(input, level) ? input : null;
	}

	private static boolean craftLoadedRecipe(Ctx c, Container chest, BlockPos workPos, CraftingRecipe recipe,
		Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
		CraftingInput input = inputFor(recipe, chest, c.level());
		if (input == null) return false;
		ItemStack output = recipe.assemble(input);
		if (output.isEmpty() || !outputFilter.test(output)
			|| !outputAllowed(c, chest, outputFilter, output, outputCap, allowPlanBatch)) return false;
		List<ItemStack> remainder = recipe.getRemainingItems(input);
		if (workPos != null && !JobUtil.approachLong(c, workPos, 3.0)) return true;
		if (!commit(chest, input, output, remainder)) return false;
		c.setActivity("stellt her: " + output.getHoverName().getString());
		Anim.hold(c, output.getItem());
		c.st().cooldownUntil = c.now() + 100;
		c.mem().jobsDone++;
		return true;
	}

	/** A plan needs at least its requested count; one loaded recipe batch may exceed that minimum. */
	private static boolean outputAllowed(Ctx c, Container chest, Predicate<ItemStack> outputFilter,
		ItemStack output, int outputCap, boolean allowPlanBatch) {
		int existing = count(chest, outputFilter);
		if (!allowPlanBatch || outputCap < 0) return outputWithinCap(existing, output, outputCap);
		int requested = planDemand(c, output.getItem());
		if (requested <= 0 || existing >= outputCap
			|| count(chest, stack -> stack.is(output.getItem())) >= requested) {
			return outputWithinCap(existing, output, outputCap);
		}
		// Only this one recipe transaction may cross the plan's minimum. The next work cycle sees
		// that the request is met and cannot keep overproducing the same output.
		return outputWithinCap(existing, output, existing + output.getCount());
	}

	/** Checks the combined count of every rolled output selected by the target filter before inventory mutation. */
	private static boolean cuttingOutputsAllowed(Ctx c, Container chest, Predicate<ItemStack> outputFilter,
		List<ItemStack> outputs, int outputCap, boolean allowPlanBatch) {
		long added = 0;
		for (ItemStack output : outputs) if (!output.isEmpty() && outputFilter.test(output)) added += output.getCount();
		if (added <= 0 || added > Integer.MAX_VALUE || outputCap < 0) return false;

		int existing = count(chest, outputFilter);
		if ((long) existing + added <= outputCap) return true;
		if (!allowPlanBatch || existing >= outputCap) return false;

		// A single batch can cross the cap only to meet an outstanding village-plan request.
		for (ItemStack output : outputs) {
			if (output.isEmpty() || !outputFilter.test(output)) continue;
			int requested = planDemand(c, output.getItem());
			if (requested > 0 && count(chest, stack -> stack.is(output.getItem())) < requested) return true;
		}
		return false;
	}

	private static int planDemand(Ctx c, net.minecraft.world.item.Item item) {
		if (c.village() == null || !c.village().data().hasPlan()) return 0;
		String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
		int requested = 0;
		for (String entry : c.village().data().wanted) {
			String[] parts = entry.split("#", 2);
			if (!parts[0].equals(itemId)) continue;
			try {
				requested = (int) Math.min(Integer.MAX_VALUE,
					(long) requested + (parts.length == 2 ? Integer.parseInt(parts[1]) : 1));
			} catch (NumberFormatException ignored) {
				return 0;
			}
		}
		return requested;
	}

	/** Stock limits apply to the complete result stack, not just to how much stock existed before crafting. */
	static boolean outputWithinCap(int existingCount, ItemStack output, int outputCap) {
		return existingCount >= 0 && outputCap >= 0 && !output.isEmpty()
			&& (long) existingCount + output.getCount() <= outputCap;
	}

	/**
	 * Makes one real, currently craftable ingredient for a requested output when its loaded recipe is blocked on inputs.
	 * Repeating this on later work cycles builds short chains such as logs → planks → sticks → fences without inventing
	 * name-based conversions or consuming anything until the actual recipe transaction fits.
	 */
	static boolean craftPrerequisite(Ctx c, Container chest, BlockPos workPos, net.minecraft.world.item.Item target) {
		var recipes = c.level().recipeAccess().getRecipes();
		for (RecipeHolder<?> wantedHolder : recipes) {
			if (!(wantedHolder.value() instanceof CraftingRecipe wantedRecipe)) continue;
			ItemStack wantedPreview = preview(wantedRecipe);
			if (wantedPreview == null || !wantedPreview.is(target)) continue;
			PlacementInfo placement = wantedRecipe.placementInfo();
			if (placement.isImpossibleToPlace()) continue;
			BitSet alreadySatisfiable = matchedRecipeSlots(wantedRecipe, chest);
			for (Ingredient needed : placement.ingredients()) {
				for (RecipeHolder<?> candidateHolder : recipes) {
					if (!(candidateHolder.value() instanceof CraftingRecipe candidate)) continue;
					ItemStack preview = preview(candidate);
					if (preview != null && (preview.isEmpty() || preview.is(target) || !needed.test(preview))) continue;
					CraftingInput input = inputFor(candidate, chest, c.level());
					if (input == null) continue;
					ItemStack output = candidate.assemble(input);
					if (output.isEmpty() || output.is(target) || !needed.test(output)) continue;
					if (!outputWithinCap(count(chest, stack -> stack.is(output.getItem())), output, INTERMEDIATE_OUTPUT_CAP)) continue;
					Container projected = copy(chest);
					if (!commit(projected, input, output, candidate.getRemainingItems(input))) continue;
					BitSet afterCraft = matchedRecipeSlots(wantedRecipe, projected);
					BitSet lostDirectInputs = (BitSet) alreadySatisfiable.clone();
					lostDirectInputs.andNot(afterCraft);
					if (!lostDirectInputs.isEmpty()) continue;
					if (craftLoadedRecipe(c, chest, workPos, candidate,
						stack -> stack.is(output.getItem()), INTERMEDIATE_OUTPUT_CAP, false)) return true;
				}
			}
		}
		return false;
	}

	/** The exact target recipe slots currently fillable from stock, with each inventory item reserved once. */
	private static BitSet matchedRecipeSlots(CraftingRecipe recipe, Container chest) {
		PlacementInfo placement = recipe.placementInfo();
		if (placement.isImpossibleToPlace()) return new BitSet();
		List<Ingredient> ingredients = placement.ingredients();
		List<Ingredient> requiredSlots = new ArrayList<>();
		var slotMap = placement.slotsToIngredientIndex();
		for (int slot = 0; slot < slotMap.size(); slot++) {
			int ingredientIndex = slotMap.getInt(slot);
			if (ingredientIndex == PlacementInfo.EMPTY_SLOT) continue;
			if (ingredientIndex < 0 || ingredientIndex >= ingredients.size()) return new BitSet();
			requiredSlots.add(ingredients.get(ingredientIndex));
		}
		if (requiredSlots.isEmpty()) return new BitSet();

		List<ItemStack> units = new ArrayList<>();
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stack = chest.getItem(slot);
			if (stack.isEmpty()) continue;
			int unitsToAdd = Math.min(stack.getCount(), requiredSlots.size());
			for (int unit = 0; unit < unitsToAdd; unit++) units.add(stack.copyWithCount(1));
		}
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < requiredSlots.size(); i++) order.add(i);
		order.sort(java.util.Comparator.comparingInt(index -> matchingUnits(units, requiredSlots.get(index))));
		int[] assigned = new int[units.size()];
		java.util.Arrays.fill(assigned, -1);
		BitSet matched = new BitSet(requiredSlots.size());
		for (int requirement : order) {
			if (augment(requirement, requiredSlots, units, new boolean[units.size()], assigned)) matched.set(requirement);
		}
		return matched;
	}

	private static int matchingUnits(List<ItemStack> units, Ingredient ingredient) {
		int count = 0;
		for (ItemStack unit : units) if (ingredient.test(unit)) count++;
		return count;
	}

	private static boolean augment(int requirement, List<Ingredient> requirements, List<ItemStack> units,
		boolean[] visited, int[] assigned) {
		for (int unit = 0; unit < units.size(); unit++) {
			if (visited[unit] || !requirements.get(requirement).test(units.get(unit))) continue;
			visited[unit] = true;
			if (assigned[unit] < 0 || augment(assigned[unit], requirements, units, visited, assigned)) {
				assigned[unit] = requirement;
				return true;
			}
		}
		return false;
	}

	private static Container copy(Container chest) {
		SimpleContainer copy = new SimpleContainer(chest.getContainerSize());
		for (int slot = 0; slot < chest.getContainerSize(); slot++) copy.setItem(slot, chest.getItem(slot).copy());
		return copy;
	}

	/** Vanilla's concrete shaped/shapeless implementations return their fixed result even for an empty input. */
	static ItemStack preview(CraftingRecipe recipe) {
		if (recipe.getClass() == ShapedRecipe.class || recipe.getClass() == ShapelessRecipe.class) {
			return recipe.assemble(CraftingInput.EMPTY);
		}
		return null;
	}

	/**
	 * Uses the public recipe placement contract rather than class-specific ingredient accessors. A PlacementInfo maps each
	 * crafting slot to an ingredient index (or EMPTY_SLOT); because that contract carries no width/height, try each legal
	 * rectangle whose area matches the slot map and let the recipe itself select its accepted geometry.
	 */
	static CraftingInput inputFor(CraftingRecipe recipe, Container chest, Level level) {
		PlacementInfo placement = recipe.placementInfo();
		List<Ingredient> required = placement.ingredients();
		var slotMap = placement.slotsToIngredientIndex();
		if (placement.isImpossibleToPlace() || required.isEmpty() || required.size() > 9 || slotMap.isEmpty() || slotMap.size() > 9) return null;
		List<ItemStack> chosen = assign(chest, required);
		if (chosen == null) return null;
		boolean[] used = new boolean[required.size()];
		for (int i = 0; i < slotMap.size(); i++) {
			int ingredient = slotMap.getInt(i);
			if (ingredient == PlacementInfo.EMPTY_SLOT) continue;
			if (ingredient < 0 || ingredient >= required.size() || used[ingredient]) return null;
			used[ingredient] = true;
		}
		for (boolean found : used) if (!found) return null;

		for (int width = 1; width <= 3; width++) {
			if (slotMap.size() % width != 0) continue;
			int height = slotMap.size() / width;
			if (height < 1 || height > 3) continue;
			List<ItemStack> grid = new ArrayList<>(java.util.Collections.nCopies(slotMap.size(), ItemStack.EMPTY));
			for (int slot = 0; slot < slotMap.size(); slot++) {
				int ingredient = slotMap.getInt(slot);
				if (ingredient != PlacementInfo.EMPTY_SLOT) grid.set(slot, chosen.get(ingredient));
			}
			CraftingInput input = CraftingInput.of(width, height, grid);
			if (recipe.matches(input, level)) return input;
		}
		return null;
	}

	private static List<ItemStack> assign(Container chest, List<Ingredient> ingredients) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < ingredients.size(); i++) order.add(i);
		order.sort(java.util.Comparator.comparingInt(index -> matchingStacks(chest, ingredients.get(index))));
		List<ItemStack> chosen = new ArrayList<>(java.util.Collections.nCopies(ingredients.size(), ItemStack.EMPTY));
		int[] reserved = new int[chest.getContainerSize()];
		return assignAt(chest, ingredients, order, 0, reserved, chosen) ? chosen : null;
	}

	private static int matchingStacks(Container chest, Ingredient ingredient) {
		int count = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) if (!chest.getItem(i).isEmpty() && ingredient.test(chest.getItem(i))) count++;
		return count;
	}

	private static boolean assignAt(Container chest, List<Ingredient> ingredients, List<Integer> order,
			int depth, int[] reserved, List<ItemStack> chosen) {
		if (depth == order.size()) return true;
		int index = order.get(depth);
		Ingredient ingredient = ingredients.get(index);
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stack = chest.getItem(slot);
			if (stack.isEmpty() || !ingredient.test(stack) || stack.getCount() <= reserved[slot]) continue;
			reserved[slot]++;
			chosen.set(index, stack.copyWithCount(1));
			if (assignAt(chest, ingredients, order, depth + 1, reserved, chosen)) return true;
			reserved[slot]--;
		}
		return false;
	}

	private static int count(Container chest, Predicate<ItemStack> filter) {
		int count = 0;
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack existing = chest.getItem(slot);
			if (!existing.isEmpty() && filter.test(existing)) count += existing.getCount();
		}
		return count;
	}

	static boolean commit(Container chest, RecipeInput input, ItemStack output, List<ItemStack> remainders) {
		List<ItemStack> consumed = new ArrayList<>();
		for (int i = 0; i < input.size(); i++) if (!input.getItem(i).isEmpty()) consumed.add(input.getItem(i).copyWithCount(1));
		List<ItemStack> returned = new ArrayList<>(remainders);
		returned.add(output.copy());
		return commit(chest, consumed, returned);
	}

	/** Atomically consume exact one-item inputs and fit every output/remainder before mutating the pantry. */
	static boolean commit(Container chest, List<ItemStack> consumed, List<ItemStack> returned) {
		List<ItemStack> simulated = new ArrayList<>(chest.getContainerSize());
		for (int i = 0; i < chest.getContainerSize(); i++) simulated.add(chest.getItem(i).copy());
		for (ItemStack ingredient : consumed) {
			if (ingredient.isEmpty()) continue;
			int left = 1;
			for (int i = 0; i < simulated.size() && left > 0; i++) {
				ItemStack stack = simulated.get(i);
				if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, ingredient)) {
					stack.shrink(left);
					if (stack.isEmpty()) simulated.set(i, ItemStack.EMPTY);
					left = 0;
				}
			}
			if (left > 0) return false;
		}
		for (ItemStack remaining : returned) if (!remaining.isEmpty() && !insert(simulated, chest, remaining.copy())) return false;
		for (int i = 0; i < simulated.size(); i++) chest.setItem(i, simulated.get(i));
		chest.setChanged();
		return true;
	}

	private static boolean insert(List<ItemStack> slots, Container chest, ItemStack moving) {
		for (int i = 0; i < slots.size() && !moving.isEmpty(); i++) {
			ItemStack existing = slots.get(i);
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, moving) && chest.canPlaceItem(i, moving)) {
				int room = Math.min(existing.getMaxStackSize(), chest.getMaxStackSize()) - existing.getCount();
				int count = Math.min(room, moving.getCount());
				if (count > 0) { existing.grow(count); moving.shrink(count); }
			}
		}
		for (int i = 0; i < slots.size() && !moving.isEmpty(); i++) {
			if (slots.get(i).isEmpty() && chest.canPlaceItem(i, moving)) {
				int count = Math.min(moving.getCount(), Math.min(moving.getMaxStackSize(), chest.getMaxStackSize()));
				slots.set(i, moving.copyWithCount(count));
				moving.shrink(count);
			}
		}
		return moving.isEmpty();
	}

	/** Isolated compat adapter: the outer class can load when Farmer's Delight is absent. */
	private static final class FarmersDelightCookingPot {
		private FarmersDelightCookingPot() { }

		private static boolean craft(Ctx c, Container chest, BlockPos workPos, Object rawRecipe,
			Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
			if (!(rawRecipe instanceof vectorwing.farmersdelight.common.crafting.CookingPotRecipe recipe)) return false;
			vectorwing.farmersdelight.refabricated.inventory.RecipeWrapper input = inputFor(recipe, chest);
			if (input == null) return false;
			var loaded = c.level().recipeAccess().getRecipeFor(recipe.getType(), input, c.level());
			if (loaded.isEmpty() || loaded.get().value() != recipe) return false;

			ItemStack output = loaded.get().value().assemble(input);
			if (output.isEmpty() || !outputFilter.test(output)
				|| !outputAllowed(c, chest, outputFilter, output, outputCap, allowPlanBatch)) return false;
			if (workPos != null && !JobUtil.approachLong(c, workPos, 3.0)) return true;

			List<ItemStack> remainders = new ArrayList<>(1);
			net.minecraft.world.item.ItemStackTemplate container = recipe.container();
			if (container != null) {
				ItemStack returnedContainer = container.create();
				if (!returnedContainer.isEmpty()) remainders.add(returnedContainer);
			}
			if (!commit(chest, input, output, remainders)) return false;
			c.setActivity("bereitet zu: " + output.getHoverName().getString());
			Anim.hold(c, output.getItem());
			c.st().cooldownUntil = c.now() + 100;
			c.mem().jobsDone++;
			return true;
		}

		private static vectorwing.farmersdelight.refabricated.inventory.RecipeWrapper inputFor(
			vectorwing.farmersdelight.common.crafting.CookingPotRecipe recipe, Container chest) {
			List<Ingredient> required = recipe.input();
			if (required.isEmpty() || required.size() > vectorwing.farmersdelight.common.crafting.CookingPotRecipe.INPUT_SLOTS) {
				return null;
			}
			List<ItemStack> chosen = assign(chest, required);
			if (chosen == null) return null;
			vectorwing.farmersdelight.refabricated.inventory.ItemStackHandler handler =
				new vectorwing.farmersdelight.refabricated.inventory.ItemStackHandler(
					vectorwing.farmersdelight.common.crafting.CookingPotRecipe.INPUT_SLOTS) {
					@Override
					public it.unimi.dsi.fastutil.ints.IntList getInputSlotIndexes() {
						return it.unimi.dsi.fastutil.ints.IntList.of(0, 1, 2, 3, 4, 5);
					}
				};
			for (int slot = 0; slot < chosen.size(); slot++) handler.setStackInSlot(slot, chosen.get(slot));
			return new vectorwing.farmersdelight.refabricated.inventory.RecipeWrapper(handler);
		}
	}

	/** Isolated compat adapter so RecipeCrafting remains loadable without Farmer's Delight installed. */
	private static final class FarmersDelightCuttingBoard {
		private FarmersDelightCuttingBoard() { }

		private static boolean craft(Ctx c, Container chest, BlockPos workPos, Object rawRecipe,
			Predicate<ItemStack> outputFilter, int outputCap, boolean allowPlanBatch) {
			if (!(rawRecipe instanceof vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe recipe)) return false;
			if (recipe.getType() != vectorwing.farmersdelight.common.registry.ModRecipeTypes.CUTTING.get()) return false;

			List<ItemStack> selected = assign(chest, List.of(recipe.getInput(), recipe.getTool()));
			if (selected == null) return false;
			vectorwing.farmersdelight.common.crafting.CuttingBoardRecipeInput input =
				new vectorwing.farmersdelight.common.crafting.CuttingBoardRecipeInput(selected.get(0), selected.get(1));
			if (!recipe.matches(input, c.level())) return false;

			var loaded = c.level().recipeAccess().getRecipeFor(recipe.getType(), input, c.level());
			if (loaded.isEmpty() || loaded.get().value() != recipe) return false;

			int fortune = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
				c.level().holderLookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
					.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FORTUNE), input.tool());
			List<ItemStack> results = recipe.rollResults(c.level().getRandom(), fortune);
			if (results.isEmpty() || results.stream().noneMatch(outputFilter)) return false;
			if (!cuttingOutputsAllowed(c, chest, outputFilter, results, outputCap, allowPlanBatch)) return false;
			if (workPos != null && !JobUtil.approachLong(c, workPos, 3.0)) return true;

			// FD's board applies one durability point to the held tool after resolving all chance results.
			ItemStack damagedTool = input.tool().copy();
			damagedTool.hurtAndBreak(1, c.level(), null, ignored -> { });
			List<ItemStack> returned = new ArrayList<>(results.size() + 2);
			for (ItemStack result : results) if (!result.isEmpty()) returned.add(result.copy());
			if (!damagedTool.isEmpty()) returned.add(damagedTool);

			if (!commit(chest, List.of(input.item(), input.tool()), returned)) return false;
			ItemStack displayedOutput = results.stream().filter(outputFilter).findFirst().orElseThrow();
			c.setActivity("verarbeitet: " + displayedOutput.getHoverName().getString());
			Anim.hold(c, displayedOutput.getItem());
			c.st().cooldownUntil = c.now() + 100;
			c.mem().jobsDone++;
			return true;
		}
	}
}
