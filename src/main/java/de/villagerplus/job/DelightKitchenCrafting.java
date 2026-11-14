package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipeInput;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;
import vectorwing.farmersdelight.refabricated.inventory.ItemStackHandler;
import vectorwing.farmersdelight.refabricated.inventory.RecipeWrapper;

/** Runs loaded Farmer's Delight cooking recipes and any compatible addon recipes from real village pantry stock. */
final class DelightKitchenCrafting {
	private static final String MOD_ID = "farmersdelight";

	private DelightKitchenCrafting() { }

	static BlockPos findStation(ServerLevel level, BlockPos kitchen, Identifier stationId) {
		if (kitchen == null || !FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
		Block station = BuiltInRegistries.BLOCK.getOptional(stationId).orElse(null);
		if (station == null) return null;
		for (BlockPos pos : BlockPos.betweenClosed(kitchen.offset(-8, -3, -8), kitchen.offset(8, 5, 8))) {
			if (level.hasChunkAt(pos) && level.getBlockState(pos).is(station)) return pos.immutable();
		}
		return null;
	}

	static CookingPotRecipe firstPreparedPotRecipe(ServerLevel level, Predicate<ItemStack> outputFilter) {
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (holder.value() instanceof CookingPotRecipe recipe && recipe.getType() == ModRecipeTypes.COOKING.get()
				&& outputFilter.test(recipe.result().create())) return recipe;
		}
		return null;
	}

	static CookingPotRecipe preparedPotRecipe(ServerLevel level, Identifier id, Predicate<ItemStack> outputFilter) {
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (holder.id().identifier().equals(id) && holder.value() instanceof CookingPotRecipe recipe
				&& recipe.getType() == ModRecipeTypes.COOKING.get() && outputFilter.test(recipe.result().create())) return recipe;
		}
		return null;
	}

	static int seedIngredients(Container pantry, CookingPotRecipe recipe) {
		int slot = 0;
		for (Ingredient ingredient : recipe.input()) {
			ItemStack sample = ingredient.items().map(Holder::value).map(net.minecraft.world.item.Item::getDefaultInstance)
				.filter(ingredient::test).findFirst().orElse(ItemStack.EMPTY);
			if (sample.isEmpty() || slot >= pantry.getContainerSize()) return 0;
			pantry.setItem(slot++, sample.copyWithCount(1));
		}
		pantry.setChanged();
		return slot;
	}

	static CuttingBoardRecipe firstGuaranteedPotPrepRecipe(ServerLevel level) {
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
		List<CookingPotRecipe> potRecipes = new ArrayList<>();
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (holder.value() instanceof CookingPotRecipe recipe && recipe.getType() == ModRecipeTypes.COOKING.get()) potRecipes.add(recipe);
		}
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CuttingBoardRecipe recipe) || recipe.getType() != ModRecipeTypes.CUTTING.get()) continue;
			boolean feedsKitchen = recipe.getRollableResults().stream().filter(result -> result.chance() >= 1.0F)
				.map(result -> result.stack().create()).anyMatch(stack -> isUsefulPrep(stack, potRecipes));
			boolean hasDurableTool = recipe.getTool().items().map(Holder::value)
				.anyMatch(item -> item.getDefaultInstance().isDamageableItem());
			if (feedsKitchen && hasDurableTool) return recipe;
		}
		return null;
	}

	static int seedCuttingIngredients(Container pantry, CuttingBoardRecipe recipe) {
		ItemStack input = recipe.getInput().items().map(Holder::value).map(net.minecraft.world.item.Item::getDefaultInstance)
			.filter(recipe.getInput()::test).findFirst().orElse(ItemStack.EMPTY);
		ItemStack tool = recipe.getTool().items().map(Holder::value).map(net.minecraft.world.item.Item::getDefaultInstance)
			.filter(stack -> recipe.getTool().test(stack) && stack.isDamageableItem()).findFirst().orElse(ItemStack.EMPTY);
		if (input.isEmpty() || tool.isEmpty() || pantry.getContainerSize() < 2) return 0;
		pantry.clearContent();
		pantry.setItem(0, input);
		pantry.setItem(1, tool);
		pantry.setChanged();
		return 2;
	}

	static boolean cookFromPot(Ctx c, Container pantry, BlockPos pot, Predicate<ItemStack> outputFilter, int cap) {
		return cookFromPot(c, pantry, pot, outputFilter, cap, null);
	}

	static boolean cookFromPot(Ctx c, Container pantry, BlockPos pot, Predicate<ItemStack> outputFilter, int cap,
			CookingPotRecipe onlyRecipe) {
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID) || pot == null) return false;
		for (RecipeHolder<?> holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CookingPotRecipe recipe) || recipe.getType() != ModRecipeTypes.COOKING.get()) continue;
			if (onlyRecipe != null && recipe != onlyRecipe) continue;
			List<ItemStack> chosen = select(pantry, recipe.input());
			if (chosen == null) continue;
			ItemStackHandler handler = new ItemStackHandler(CookingPotRecipe.INPUT_SLOTS) {
				@Override
				public IntList getInputSlotIndexes() {
					return IntList.of(0, 1, 2, 3, 4, 5);
				}
			};
			for (int i = 0; i < chosen.size(); i++) handler.setStackInSlot(i, chosen.get(i).copy());
			RecipeWrapper input = new RecipeWrapper(handler);
			if (!recipe.matches(input, c.level())) continue;
			ItemStack result = recipe.assemble(input);
			if (result.isEmpty() || !outputFilter.test(result) || count(pantry, result) + result.getCount() > cap) continue;
			if (!JobUtil.approachLong(c, pot, 3.0)) return true;
			List<ItemStack> returned = ingredientRemainders(chosen);
			var containerTemplate = recipe.container();
			if (containerTemplate != null) {
				ItemStack servingContainer = containerTemplate.create();
				if (!servingContainer.isEmpty()) returned.add(servingContainer);
			}
			if (!RecipeCrafting.commit(pantry, chosen, withResult(returned, result))) continue;
			c.setActivity("kocht am Topf: " + result.getHoverName().getString());
			Anim.hold(c, result.getItem());
			c.st().cooldownUntil = c.now() + Math.max(100, recipe.getCookTime());
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	static boolean prepareOnBoard(Ctx c, Container pantry, BlockPos board, int cap) {
		return prepareOnBoard(c, pantry, board, cap, c.level().getRandom());
	}

	static boolean prepareOnBoard(Ctx c, Container pantry, BlockPos board, int cap, RandomSource random) {
		return prepareOnBoard(c, pantry, board, cap, random, null);
	}

	static boolean prepareOnBoard(Ctx c, Container pantry, BlockPos board, int cap, RandomSource random,
			CuttingBoardRecipe requiredRecipe) {
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID) || board == null) return false;
		List<CookingPotRecipe> potRecipes = new ArrayList<>();
		for (RecipeHolder<?> holder : c.level().recipeAccess().getRecipes()) {
			if (holder.value() instanceof CookingPotRecipe recipe && recipe.getType() == ModRecipeTypes.COOKING.get()) potRecipes.add(recipe);
		}
		if (potRecipes.isEmpty()) return false;
		for (RecipeHolder<?> holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CuttingBoardRecipe recipe) || recipe.getType() != ModRecipeTypes.CUTTING.get()) continue;
			if (requiredRecipe != null && recipe != requiredRecipe) continue;
			boolean feedsKitchen = recipe.getRollableResults().stream()
				.map(result -> result.stack().create()).anyMatch(stack -> isUsefulPrep(stack, potRecipes));
			if (!feedsKitchen) continue;
			int inputSlot = find(pantry, recipe.getInput(), -1);
			if (inputSlot < 0) continue;
			int toolSlot = find(pantry, recipe.getTool(), inputSlot);
			if (toolSlot < 0) continue;
			ItemStack item = pantry.getItem(inputSlot).copyWithCount(1);
			ItemStack originalTool = pantry.getItem(toolSlot).copyWithCount(1);
			ItemStack tool = originalTool.copy();
			CuttingBoardRecipeInput input = new CuttingBoardRecipeInput(item, tool);
			if (!recipe.matches(input, c.level())) continue;
			List<ItemStack> results = recipe.rollResults(random, 1);
			if (countAll(pantry) + results.stream().mapToInt(ItemStack::getCount).sum() > cap) continue;
			if (!JobUtil.approachLong(c, board, 3.0)) return true;
			if (tool.isDamageableItem()) tool.hurtAndBreak(1, c.level(), null, ignored -> { });
			List<ItemStack> returned = new ArrayList<>();
			for (ItemStack result : results) returned.add(result.copy());
			if (!tool.isEmpty()) returned.add(tool);
			if (!RecipeCrafting.commit(pantry, List.of(item, originalTool), returned)) continue;
			String name = results.isEmpty() ? "Zutaten" : results.getFirst().getHoverName().getString();
			c.setActivity("bereitet am Schneidebrett vor: " + name);
			if (!results.isEmpty()) Anim.hold(c, results.getFirst().getItem());
			c.st().cooldownUntil = c.now() + 40;
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	/**
	 * Uses the marked kitchen furnace for a loaded smelting recipe that produces food. The expected result is attached
	 * before the pantry input is removed, so a save/reload during baking cannot make the cook consume the same input twice.
	 */
	static boolean cookInOven(Ctx c) {
		if (c.village() == null) return false;
		BlockPos kitchen = Specials.get(c.village(), Blueprints.KITCHEN);
		if (kitchen == null || !c.level().hasChunkAt(kitchen)) return false;
		BlockPos ovenPos = findMarkedOven(c.level(), kitchen);
		if (ovenPos == null || !(c.level().getBlockEntity(ovenPos) instanceof AbstractFurnaceBlockEntity furnace)) return false;
		Integer communal = furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE);
		ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		if (communal == null || communal != 0 || claim == null) return false;

		if (!claim.isEmpty()) {
			if (!JobUtil.approachLong(c, ovenPos, 3.0)) return true;
			if (!furnace.getItem(2).isEmpty()) {
				int moved = SmithSmelting.deliverClaimedOutput(c.level(), c.village(), furnace);
				if (moved <= 0) return false;
				c.level().sendBlockUpdated(ovenPos, c.level().getBlockState(ovenPos), c.level().getBlockState(ovenPos), 3);
				c.setActivity("lagert das gebackene Gericht im Kornspeicher ein");
				Anim.hold(c, claim.getItem());
				JobUtil.swing(c.v());
				c.mem().jobsDone++;
				c.st().cooldownUntil = c.now() + 80;
				return true;
			}
			ItemStack input = furnace.getItem(0);
			if (input.isEmpty()) {
				furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
				furnace.setChanged();
				return false;
			}
			AbstractCookingRecipe recipe = SmithSmelting.matchingRecipe(c.level(), input, RecipeType.SMELTING);
			ItemStack expected = recipe == null ? ItemStack.EMPTY : recipe.assemble(new SingleRecipeInput(input));
			if (expected.isEmpty() || !Meals.isPreparedFood(expected)
				|| expected.getCount() != claim.getCount() || !ItemStack.isSameItemSameComponents(expected, claim)) return false;
			if (furnace.getItem(1).isEmpty()) {
				Container storage = Lager.combined(c.level(), c.village());
				ItemStack fuel = withdrawFuel(storage);
				if (fuel.isEmpty()) return false;
				if (!furnace.canPlaceItem(1, fuel)) {
					HopperBlockEntity.addItem(null, storage, fuel, null);
					return false;
				}
				furnace.setItem(1, fuel);
			}
			if (!furnace.getItem(1).is(ItemTags.COALS)) return false;
			furnace.setChanged();
			c.level().sendBlockUpdated(ovenPos, c.level().getBlockState(ovenPos), c.level().getBlockState(ovenPos), 3);
			c.setActivity("bäckt nach geladenem Ofenrezept");
			Anim.hold(c, input.getItem());
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 100;
			return true;
		}

		if (!furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()) return false;
		OvenBatch batch = findOvenBatch(c.level(), c.village(), furnace);
		if (batch == null) return false;
		if (!JobUtil.approachLong(c, ovenPos, 3.0)) return true;
		if (!loadOvenBatch(c.level(), furnace, batch)) return false;
		c.level().sendBlockUpdated(ovenPos, c.level().getBlockState(ovenPos), c.level().getBlockState(ovenPos), 3);
		c.setActivity("beschickt den Ofen nach geladenem Rezept");
		Anim.hold(c, batch.input().getItem());
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 80;
		return true;
	}

	private static BlockPos findMarkedOven(ServerLevel level, BlockPos kitchen) {
		for (BlockPos pos : BlockPos.betweenClosed(kitchen.offset(-16, -4, -16), kitchen.offset(16, 8, 16))) {
			if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.FURNACE)
				|| !(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) continue;
			Integer communal = furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE);
			if (communal == null || communal != 0) continue;
			ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
			if (claim == null && furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty()) {
				// Older workstations may have the village marker but predate the empty recipe-claim attachment.
				furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
				furnace.setChanged();
				claim = ItemStack.EMPTY;
			}
			if (claim == null) continue;
			if (claim.isEmpty()) {
				if (furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty()) return pos.immutable();
				continue;
			}
			if (Meals.isPreparedFood(claim)) return pos.immutable();
		}
		return null;
	}

	private static OvenBatch findOvenBatch(ServerLevel level, de.villagerplus.village.VillageRef ref,
			AbstractFurnaceBlockEntity furnace) {
		List<Container> pantries = Specials.containers(level, ref, Blueprints.GRANARY);
		if (pantries.isEmpty()) return null;
		List<AbstractCookingRecipe> recipes = new ArrayList<>();
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (holder.value() instanceof AbstractCookingRecipe recipe && recipe.getType() == RecipeType.SMELTING) recipes.add(recipe);
		}
		for (Container pantry : pantries) {
			for (int slot = 0; slot < pantry.getContainerSize(); slot++) {
				ItemStack available = pantry.getItem(slot);
				if (available.isEmpty()) continue;
				ItemStack input = available.copyWithCount(1);
				for (AbstractCookingRecipe recipe : recipes) {
					if (!recipe.matches(new SingleRecipeInput(input), level)) continue;
					ItemStack output = recipe.assemble(new SingleRecipeInput(input));
					if (Meals.isPreparedFood(output) && furnace.canPlaceItem(0, input)) {
						return new OvenBatch(pantry, slot, input, recipe, output);
					}
					// Match vanilla recipe-manager priority; never skip a registered recipe to invent a conversion.
					break;
				}
			}
		}
		return null;
	}

	private static boolean loadOvenBatch(ServerLevel level, AbstractFurnaceBlockEntity furnace, OvenBatch batch) {
		Container pantry = batch.pantry();
		int slot = batch.slot();
		if (slot < 0 || slot >= pantry.getContainerSize() || !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()
			|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null
			|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) != 0) return false;
		ItemStack available = pantry.getItem(slot);
		if (available.isEmpty() || !ItemStack.isSameItemSameComponents(available, batch.input())) return false;
		AbstractCookingRecipe recipe = SmithSmelting.matchingRecipe(level, batch.input(), RecipeType.SMELTING);
		if (recipe != batch.recipe() || !recipe.matches(new SingleRecipeInput(batch.input()), level)) return false;
		ItemStack output = recipe.assemble(new SingleRecipeInput(batch.input()));
		ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		if (claim == null || !claim.isEmpty() || output.isEmpty() || !Meals.isPreparedFood(output)
			|| !ItemStack.isSameItemSameComponents(output, batch.output()) || output.getCount() != batch.output().getCount()
			|| !furnace.canPlaceItem(0, batch.input())) return false;

		ItemStack[] snapshot = new ItemStack[pantry.getContainerSize()];
		for (int i = 0; i < snapshot.length; i++) snapshot[i] = pantry.getItem(i).copy();
		// Persist the exact result claim before exposing the one-item input to vanilla furnace ticking.
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, output.copy());
		furnace.setItem(0, batch.input().copy());
		furnace.setChanged();
		ItemStack removed = pantry.removeItem(slot, 1);
		if (removed.getCount() != 1 || !ItemStack.isSameItemSameComponents(removed, batch.input())) {
			rollbackOvenBatch(pantry, snapshot, furnace);
			return false;
		}
		ItemStack remainder = SmithSmelting.craftingRemainder(batch.input());
		if (!remainder.isEmpty() && !HopperBlockEntity.addItem(null, pantry, remainder.copy(), null).isEmpty()) {
			rollbackOvenBatch(pantry, snapshot, furnace);
			return false;
		}
		pantry.setChanged();
		furnace.setChanged();
		return true;
	}

	private static void rollbackOvenBatch(Container pantry, ItemStack[] snapshot, AbstractFurnaceBlockEntity furnace) {
		for (int i = 0; i < snapshot.length; i++) pantry.setItem(i, snapshot[i].copy());
		pantry.setChanged();
		furnace.setItem(0, ItemStack.EMPTY);
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
		furnace.setChanged();
	}

	private static ItemStack withdrawFuel(Container storage) {
		if (storage == null) return ItemStack.EMPTY;
		for (int slot = 0; slot < storage.getContainerSize(); slot++) {
			ItemStack stack = storage.getItem(slot);
			if (!stack.isEmpty() && stack.is(ItemTags.COALS)) return storage.removeItem(slot, 1);
		}
		return ItemStack.EMPTY;
	}

	private static boolean isUsefulPrep(ItemStack result, List<CookingPotRecipe> potRecipes) {
		if (result.isEmpty()) return false;
		for (CookingPotRecipe recipe : potRecipes) {
			for (Ingredient ingredient : recipe.input()) if (ingredient.test(result)) return true;
		}
		return Meals.isFood(result);
	}

	private static List<ItemStack> select(Container pantry, List<Ingredient> ingredients) {
		if (ingredients.isEmpty() || ingredients.size() > CookingPotRecipe.INPUT_SLOTS) return null;
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < ingredients.size(); i++) order.add(i);
		order.sort(java.util.Comparator.comparingInt(index -> matchingSlots(pantry, ingredients.get(index))));
		List<ItemStack> chosen = new ArrayList<>(java.util.Collections.nCopies(ingredients.size(), ItemStack.EMPTY));
		return assign(pantry, ingredients, order, 0, new int[pantry.getContainerSize()], chosen) ? chosen : null;
	}

	private static int matchingSlots(Container pantry, Ingredient ingredient) {
		int count = 0;
		for (int i = 0; i < pantry.getContainerSize(); i++) {
			if (!pantry.getItem(i).isEmpty() && ingredient.test(pantry.getItem(i))) count++;
		}
		return count;
	}

	private static boolean assign(Container pantry, List<Ingredient> ingredients, List<Integer> order, int depth,
			int[] reserved, List<ItemStack> chosen) {
		if (depth == order.size()) return true;
		int ingredientIndex = order.get(depth);
		Ingredient ingredient = ingredients.get(ingredientIndex);
		for (int slot = 0; slot < pantry.getContainerSize(); slot++) {
			ItemStack stack = pantry.getItem(slot);
			if (stack.isEmpty() || !ingredient.test(stack) || stack.getCount() <= reserved[slot]) continue;
			reserved[slot]++;
			chosen.set(ingredientIndex, stack.copyWithCount(1));
			if (assign(pantry, ingredients, order, depth + 1, reserved, chosen)) return true;
			reserved[slot]--;
			chosen.set(ingredientIndex, ItemStack.EMPTY);
		}
		return false;
	}

	private static int find(Container pantry, Ingredient ingredient, int excluded) {
		for (int i = 0; i < pantry.getContainerSize(); i++) {
			if (i != excluded && !pantry.getItem(i).isEmpty() && ingredient.test(pantry.getItem(i))) return i;
		}
		return -1;
	}

	private static int count(Container pantry, ItemStack item) {
		int count = 0;
		for (int i = 0; i < pantry.getContainerSize(); i++) {
			ItemStack present = pantry.getItem(i);
			if (ItemStack.isSameItemSameComponents(present, item)) count += present.getCount();
		}
		return count;
	}

	private static int countAll(Container pantry) {
		int count = 0;
		for (int i = 0; i < pantry.getContainerSize(); i++) count += pantry.getItem(i).getCount();
		return count;
	}

	private static List<ItemStack> withResult(List<ItemStack> remainders, ItemStack result) {
		List<ItemStack> returned = new ArrayList<>(remainders);
		returned.add(result.copy());
		return returned;
	}

	private static List<ItemStack> ingredientRemainders(List<ItemStack> consumed) {
		List<ItemStack> returned = new ArrayList<>();
		for (ItemStack ingredient : consumed) {
			var craftingRemainder = ingredient.getCraftingRemainder();
			if (craftingRemainder != null) {
				ItemStack stack = craftingRemainder.create();
				if (!stack.isEmpty()) returned.add(stack);
				continue;
			}
			var override = vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES
				.get(ingredient.getItem());
			if (override != null) returned.add(override.getDefaultInstance());
		}
		return returned;
	}

	private record OvenBatch(Container pantry, int slot, ItemStack input, AbstractCookingRecipe recipe, ItemStack output) { }
}
