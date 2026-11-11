package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipeInput;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;
import vectorwing.farmersdelight.refabricated.inventory.ItemStackHandler;
import vectorwing.farmersdelight.refabricated.inventory.RecipeWrapper;

/** Development assertions for custom CraftingRecipe implementations using only the public placement contract. */
public final class RecipeCraftingChecks {
	private RecipeCraftingChecks() { }

	public static int run(ServerLevel level) {
		int passed = 0;
		SimpleContainer chest = new SimpleContainer(9);
		chest.setItem(0, new ItemStack(Items.BIRCH_PLANKS));
		chest.setItem(1, new ItemStack(Items.STICK));
		chest.setItem(2, new ItemStack(Items.STICK));

		Ingredient anyPlank = Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.PLANKS));
		List<Optional<Ingredient>> pattern = List.of(Optional.of(anyPlank), Optional.empty(),
			Optional.of(Ingredient.of(Items.STICK)), Optional.of(Ingredient.of(Items.STICK)));
		CustomRecipe customShaped = new CustomRecipe(pattern, 2, 2, Items.CRAFTING_TABLE);
		CraftingInput shaped = RecipeCrafting.inputFor(customShaped, chest, level);
		check(shaped != null && shaped.width() == 2 && shaped.height() == 2
			&& shaped.getItem(0).is(Items.BIRCH_PLANKS) && shaped.getItem(1).isEmpty()
			&& shaped.getItem(2).is(Items.STICK) && shaped.getItem(3).is(Items.STICK),
			"non-vanilla shaped recipe placement preserves holes and resolves a registered item tag"); passed++;

		chest.setItem(2, ItemStack.EMPTY);
		check(RecipeCrafting.inputFor(customShaped, chest, level) == null,
			"generic shaped recipe rejects missing ingredients without constructing a partial grid"); passed++;

		chest.clearContent();
		chest.setItem(0, new ItemStack(Items.BIRCH_PLANKS));
		chest.setItem(1, new ItemStack(Items.STICK));
		CustomRecipe customShapeless = CustomRecipe.shapeless(List.of(anyPlank, Ingredient.of(Items.STICK)), Items.PAPER);
		CraftingInput shapeless = RecipeCrafting.inputFor(customShapeless, chest, level);
		check(shapeless != null && shapeless.ingredientCount() == 2
			&& (shapeless.getItem(0).is(Items.BIRCH_PLANKS) || shapeless.getItem(1).is(Items.BIRCH_PLANKS)),
			"non-vanilla shapeless recipe uses placement indices and item tags"); passed++;

		ItemStack railOutput = ItemStack.EMPTY;
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			ItemStack candidate = RecipeCrafting.preview(recipe);
			if (candidate == null) continue;
			if (candidate.is(Items.RAIL) && candidate.getCount() > 1) {
				railOutput = candidate.copy();
				break;
			}
		}
		check(!railOutput.isEmpty(), "the loaded Minecraft recipe manager exposes a multi-item vanilla rail result"); passed++;
		if (!railOutput.isEmpty()) {
			check(RecipeCrafting.outputWithinCap(0, railOutput, 16)
				&& !RecipeCrafting.outputWithinCap(8, railOutput, 16)
				&& RecipeCrafting.outputWithinCap(8, railOutput, 24),
				"a 16-rail loaded recipe is rejected when its full result would exceed the remaining stock cap"); passed++;
		}

		StonecutterRecipe stonecutter = null;
		ItemStack stonecutterInput = ItemStack.EMPTY;
		ItemStack stonecutterOutput = ItemStack.EMPTY;
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof StonecutterRecipe candidate)) continue;
			for (var item : candidate.input().items().toList()) {
				ItemStack sample = new ItemStack(item.value());
				SingleRecipeInput input = new SingleRecipeInput(sample);
				ItemStack output = candidate.assemble(input);
				if (!output.isEmpty() && candidate.matches(input, level) && !output.is(sample.getItem())) {
					stonecutter = candidate;
					stonecutterInput = sample;
					stonecutterOutput = output;
					break;
				}
			}
			if (stonecutter != null) break;
		}
		check(stonecutter != null,
			"the loaded recipe manager exposes a stonecutter recipe with a real matching ingredient and result"); passed++;
		if (stonecutter != null) {
			ItemStack expectedStonecutterOutput = stonecutterOutput.copy();
			SimpleContainer stock = new SimpleContainer(3);
			ItemStack decorated = stonecutterInput.copyWithCount(2);
			Component customName = Component.literal("stonecutter exact input check");
			decorated.set(DataComponents.CUSTOM_NAME, customName);
			stock.setItem(0, decorated);
			stock.setItem(1, stonecutterInput.copy());
			Ctx context = new Ctx(level, null, new JobState(), new VillagerMemory(), null, level.getGameTime());
			boolean crafted = RecipeCrafting.craftStonecutterRecipe(context, stock, null, stonecutter,
				stack -> stack.is(expectedStonecutterOutput.getItem()), 64, false);
			ItemStack exactOutput = ItemStack.EMPTY;
			for (int slot = 0; slot < stock.getContainerSize(); slot++) {
				ItemStack value = stock.getItem(slot);
				if (ItemStack.isSameItemSameComponents(value, expectedStonecutterOutput)) exactOutput = value;
			}
			check(crafted && stock.getItem(0).getCount() == 1
				&& customName.equals(stock.getItem(0).get(DataComponents.CUSTOM_NAME))
				&& ItemStack.isSameItemSameComponents(stock.getItem(1), stonecutterInput)
				&& !exactOutput.isEmpty() && exactOutput.getCount() == expectedStonecutterOutput.getCount(),
				"loaded stonecutter crafting consumes one exact component-bearing input and inserts the exact assembled output"); passed++;

			SimpleContainer full = new SimpleContainer(1);
			full.setItem(0, stonecutterInput.copyWithCount(2));
			ItemStack before = full.getItem(0).copy();
			boolean fit = RecipeCrafting.craftStonecutterRecipe(context, full, null, stonecutter,
				stack -> stack.is(expectedStonecutterOutput.getItem()), 64, false);
			check(!fit && ItemStack.isSameItemSameComponents(full.getItem(0), before)
				&& full.getItem(0).getCount() == before.getCount(),
				"stonecutter input and output remain untouched when the complete result cannot fit"); passed++;
		}

		CampfireCookingRecipe campfire = null;
		ItemStack campfireInput = ItemStack.EMPTY;
		ItemStack campfireOutput = ItemStack.EMPTY;
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CampfireCookingRecipe candidate)) continue;
			for (var item : candidate.input().items().toList()) {
				ItemStack sample = new ItemStack(item.value());
				sample.set(DataComponents.CUSTOM_NAME, Component.literal("component-bearing campfire input"));
				SingleRecipeInput input = new SingleRecipeInput(sample);
				var loaded = level.recipeAccess().getRecipeFor(RecipeType.CAMPFIRE_COOKING, input, level);
				if (loaded.isEmpty() || loaded.get().value() != candidate || !candidate.matches(input, level)) continue;
				ItemStack output = candidate.assemble(input);
				if (output.isEmpty() || ItemStack.isSameItemSameComponents(sample, output)) continue;
				campfire = candidate;
				campfireInput = sample;
				campfireOutput = output;
				break;
			}
			if (campfire != null) break;
		}
		check(campfire != null,
			"the loaded recipe manager resolves a component-bearing input to its exact campfire-cooking recipe and result"); passed++;
		if (campfire != null) {
			ItemStack expectedCampfireInput = campfireInput.copy();
			ItemStack expectedCampfireOutput = campfireOutput.copy();
			Ctx context = new Ctx(level, null, new JobState(), new VillagerMemory(), null, level.getGameTime());
			SimpleContainer stock = new SimpleContainer(3);
			stock.setItem(0, campfireInput.copyWithCount(2));
			boolean cooked = RecipeCrafting.craftCampfireCookingRecipe(context, stock, null, campfire,
				stack -> stack.is(expectedCampfireOutput.getItem()), 64, false);
			check(cooked && stock.getItem(0).getCount() == 1
				&& ItemStack.isSameItemSameComponents(stock.getItem(0), expectedCampfireInput)
				&& containsExact(stock, expectedCampfireOutput),
				"campfire cooking consumes one exact component-bearing input and inserts the recipe-manager-assembled output"); passed++;

			SimpleContainer capped = new SimpleContainer(2);
			capped.setItem(0, campfireInput.copy());
			ItemStack[] cappedBefore = snapshot(capped);
			int tooSmallCap = Math.max(0, expectedCampfireOutput.getCount() - 1);
			boolean exceededCap = RecipeCrafting.craftCampfireCookingRecipe(context, capped, null, campfire,
				stack -> stack.is(expectedCampfireOutput.getItem()), tooSmallCap, false);
			check(!exceededCap && sameContents(capped, cappedBefore),
				"campfire cooking respects the output target cap without consuming its input"); passed++;

			SimpleContainer blocked = new SimpleContainer(1) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) {
					return !ItemStack.isSameItemSameComponents(stack, expectedCampfireOutput);
				}
			};
			blocked.setItem(0, campfireInput.copyWithCount(2));
			ItemStack[] blockedBefore = snapshot(blocked);
			boolean noRoom = RecipeCrafting.craftCampfireCookingRecipe(context, blocked, null, campfire,
				stack -> stack.is(expectedCampfireOutput.getItem()), 64, false);
			check(!noRoom && sameContents(blocked, blockedBefore),
				"a full pantry that rejects the campfire result leaves the complete input stack untouched"); passed++;
		}

		CookingPotProbe cookingPot = loadedCookingPotRecipe(level);
		check(cookingPot != null,
			"the loaded recipe manager exposes a Farmer's Delight cooking-pot recipe with real ingredients and a container remainder"); passed++;
		if (cookingPot != null) {
			Ctx context = new Ctx(level, null, new JobState(), new VillagerMemory(), null, level.getGameTime());
			SimpleContainer stock = cookingPotStock(cookingPot, 1, cookingPot.ingredients().size() + 2);
			boolean cooked = RecipeCrafting.craftFarmersDelightCookingPotRecipe(context, stock, null,
				cookingPot.recipe(), stack -> stack.is(cookingPot.output().getItem()), 64, false);
			boolean allIngredientsConsumed = true;
			for (int slot = 0; slot < cookingPot.ingredients().size(); slot++) {
				allIngredientsConsumed &= !containsExact(stock, cookingPot.ingredients().get(slot));
			}
			check(cooked && allIngredientsConsumed && containsExact(stock, cookingPot.output())
				&& containsExact(stock, cookingPot.remainder()) && context.mem().jobsDone == 1,
				"cooking-pot crafting accepts a manager-matched component-bearing ingredient set, consumes each real ingredient once, and atomically inserts the assembled food and exact container remainder"); passed++;

			SimpleContainer capped = cookingPotStock(cookingPot, 1, cookingPot.ingredients().size() + 2);
			ItemStack[] cappedBefore = snapshot(capped);
			int tooSmallCap = Math.max(0, cookingPot.output().getCount() - 1);
			boolean exceededCap = RecipeCrafting.craftFarmersDelightCookingPotRecipe(context, capped, null,
				cookingPot.recipe(), stack -> stack.is(cookingPot.output().getItem()), tooSmallCap, false);
			check(!exceededCap && sameContents(capped, cappedBefore),
				"cooking-pot crafting respects the complete output cap without consuming any ingredients"); passed++;

			SimpleContainer blockedRemainder = new SimpleContainer(cookingPot.ingredients().size()) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) {
					return !ItemStack.isSameItemSameComponents(stack, cookingPot.remainder());
				}
			};
			for (int slot = 0; slot < cookingPot.ingredients().size(); slot++) {
				blockedRemainder.setItem(slot, cookingPot.ingredients().get(slot).copy());
			}
			ItemStack[] blockedBefore = snapshot(blockedRemainder);
			boolean noRemainderRoom = RecipeCrafting.craftFarmersDelightCookingPotRecipe(context, blockedRemainder, null,
				cookingPot.recipe(), stack -> stack.is(cookingPot.output().getItem()), 64, false);
			check(!noRemainderRoom && sameContents(blockedRemainder, blockedBefore),
				"a cooking-pot result whose container remainder cannot fit rolls back every exact ingredient"); passed++;
		}

		CuttingBoardProbe cuttingBoard = loadedCuttingBoardRecipe(level);
		check(cuttingBoard != null,
			"the loaded recipe manager resolves a real multi-output cutting-board recipe with component-bearing input and damageable tool"); passed++;
		if (cuttingBoard != null) {
			check(cuttingBoard.input().has(DataComponents.CUSTOM_NAME) && cuttingBoard.tool().has(DataComponents.CUSTOM_NAME),
				"cutting-board input and tool ingredients accept their exact named stack components"); passed++;
			Ctx context = new Ctx(level, null, new JobState(), new VillagerMemory(), null, level.getGameTime());
			java.util.function.Predicate<ItemStack> cuttingOutput = stack -> cuttingBoard.outputs().stream()
				.anyMatch(expected -> ItemStack.isSameItemSameComponents(stack, expected));
			ItemStack expectedInputRemainder = cuttingBoard.input().copyWithCount(1);
			ItemStack expectedDamagedTool = damagedCuttingTool(level, cuttingBoard.tool());

			SimpleContainer stock = cuttingBoardStock(cuttingBoard, 2, cuttingBoard.outputs().size() + 4);
			boolean cut = RecipeCrafting.craftFarmersDelightCuttingBoardRecipe(context, stock, null,
				cuttingBoard.recipe(), cuttingOutput, 64, false);
			check(cut && exactStack(stock.getItem(0), expectedInputRemainder)
				&& containsAllExactOutputs(stock, cuttingBoard.outputs())
				&& containsExact(stock, expectedDamagedTool) && expectedDamagedTool.getDamageValue() == cuttingBoard.tool().getDamageValue() + 1
				&& context.mem().jobsDone == 1,
				"cutting-board crafting consumes one named input, inserts every loaded chance result, and returns its knife/tool with FD's one-point durability damage"); passed++;

			SimpleContainer capped = cuttingBoardStock(cuttingBoard, 1, cuttingBoard.outputs().size() + 2);
			ItemStack[] cappedBefore = snapshot(capped);
			int completeOutputCount = cuttingBoard.outputs().stream().mapToInt(ItemStack::getCount).sum();
			boolean exceededCap = RecipeCrafting.craftFarmersDelightCuttingBoardRecipe(context, capped, null,
				cuttingBoard.recipe(), cuttingOutput, Math.max(0, completeOutputCount - 1), false);
			check(!exceededCap && sameContents(capped, cappedBefore),
				"the complete combined cutting-board output count is capped before consuming or damaging either input"); passed++;

			SimpleContainer blockedOutput = new SimpleContainer(cuttingBoard.outputs().size() + 2) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) {
					return cuttingBoard.outputs().stream()
						.noneMatch(expected -> ItemStack.isSameItemSameComponents(stack, expected));
				}
			};
			blockedOutput.setItem(0, cuttingBoard.input().copyWithCount(1));
			blockedOutput.setItem(1, cuttingBoard.tool().copy());
			ItemStack[] blockedOutputBefore = snapshot(blockedOutput);
			boolean outputBlocked = RecipeCrafting.craftFarmersDelightCuttingBoardRecipe(context, blockedOutput, null,
				cuttingBoard.recipe(), cuttingOutput, 64, false);
			check(!outputBlocked && sameContents(blockedOutput, blockedOutputBefore),
				"a blocked cutting-board result rolls back the input and leaves the tool's durability unchanged"); passed++;

			SimpleContainer blockedToolReturn = new SimpleContainer(cuttingBoard.outputs().size() + 2) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) {
					return !ItemStack.isSameItemSameComponents(stack, expectedDamagedTool);
				}
			};
			blockedToolReturn.setItem(0, cuttingBoard.input().copyWithCount(1));
			blockedToolReturn.setItem(1, cuttingBoard.tool().copy());
			ItemStack[] blockedToolBefore = snapshot(blockedToolReturn);
			boolean toolReturnBlocked = RecipeCrafting.craftFarmersDelightCuttingBoardRecipe(context, blockedToolReturn, null,
				cuttingBoard.recipe(), cuttingOutput, 64, false);
			check(!toolReturnBlocked && sameContents(blockedToolReturn, blockedToolBefore),
				"a full pantry that rejects the damaged tool rolls back every cutting-board output and the input"); passed++;
		}

		SmithingRecipeInput transformProbe = new SmithingRecipeInput(
			new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
			new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.NETHERITE_INGOT));
		var transformHolder = level.recipeAccess().getRecipeFor(RecipeType.SMITHING, transformProbe, level);
		SmithingRecipe transform = transformHolder.map(holder -> holder.value()).orElse(null);
		check(transform instanceof SmithingTransformRecipe,
			"the loaded recipe manager resolves vanilla netherite smithing through the transform recipe type"); passed++;
		if (transform != null) {
			ItemStack namedSword = new ItemStack(Items.DIAMOND_SWORD);
			namedSword.set(DataComponents.CUSTOM_NAME, Component.literal("named base sword"));
			ItemStack namedIngot = new ItemStack(Items.NETHERITE_INGOT);
			namedIngot.set(DataComponents.CUSTOM_NAME, Component.literal("named smithing addition"));
			SmithingRecipeInput expectedInput = new SmithingRecipeInput(
				new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), namedSword.copy(), namedIngot.copy());
			ItemStack expectedTransform = transform.assemble(expectedInput);
			SimpleContainer transformStock = new SimpleContainer(5);
			transformStock.setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
			transformStock.setItem(1, namedSword.copy());
			ItemStack untouchedSword = new ItemStack(Items.DIAMOND_SWORD);
			transformStock.setItem(2, untouchedSword.copy());
			transformStock.setItem(3, namedIngot.copy());
			ItemStack untouchedIngot = new ItemStack(Items.NETHERITE_INGOT);
			transformStock.setItem(4, untouchedIngot.copy());
			Ctx context = new Ctx(level, null, new JobState(), new VillagerMemory(), null, level.getGameTime());
			boolean transformed = RecipeCrafting.craft(context, transformStock, null,
				stack -> stack.is(expectedTransform.getItem()), 64);
			check(transformed && transformStock.getItem(1).isEmpty() && transformStock.getItem(3).isEmpty()
				&& exactStack(transformStock.getItem(2), untouchedSword)
				&& exactStack(transformStock.getItem(4), untouchedIngot)
				&& containsExact(transformStock, expectedTransform),
				"smithing transform consumes the selected component-bearing inputs and inserts the exact assembled output"); passed++;

			SimpleContainer cappedStock = new SimpleContainer(3);
			cappedStock.setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
			cappedStock.setItem(1, namedSword.copy());
			cappedStock.setItem(2, namedIngot.copy());
			ItemStack[] cappedBefore = snapshot(cappedStock);
			boolean overCap = RecipeCrafting.craft(context, cappedStock, null,
				stack -> stack.is(expectedTransform.getItem()), 0);
			check(!overCap && sameContents(cappedStock, cappedBefore),
				"smithing transform respects the full output cap without consuming any inputs"); passed++;
		}

		SmithingRecipeInput trimProbe = new SmithingRecipeInput(
			new ItemStack(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
			new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.IRON_INGOT));
		var trimHolder = level.recipeAccess().getRecipeFor(RecipeType.SMITHING, trimProbe, level);
		SmithingRecipe trim = trimHolder.map(holder -> holder.value()).orElse(null);
		check(trim instanceof SmithingTrimRecipe,
			"the loaded recipe manager resolves vanilla armor trimming through the trim recipe type"); passed++;
		if (trim != null) {
			ItemStack namedHelmet = new ItemStack(Items.DIAMOND_HELMET);
			namedHelmet.set(DataComponents.CUSTOM_NAME, Component.literal("named trim base"));
			SmithingRecipeInput expectedInput = new SmithingRecipeInput(
				new ItemStack(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE), namedHelmet.copy(), new ItemStack(Items.IRON_INGOT));
			ItemStack expectedTrim = trim.assemble(expectedInput);
			Ctx context = new Ctx(level, null, new JobState(), new VillagerMemory(), null, level.getGameTime());
			SimpleContainer trimStock = new SimpleContainer(3);
			trimStock.setItem(0, expectedInput.template().copy());
			trimStock.setItem(1, namedHelmet.copy());
			trimStock.setItem(2, expectedInput.addition().copy());
			boolean trimmed = RecipeCrafting.craftSmithingRecipe(context, trimStock, null, trim,
				stack -> stack.is(expectedTrim.getItem()), 64, false);
			check(trimmed && trimStock.getItem(1).isEmpty() && trimStock.getItem(2).isEmpty()
				&& containsExact(trimStock, expectedTrim),
				"smithing trim preserves the recipe-assembled component result in the real inventory transaction"); passed++;

			SimpleContainer blockedOutput = new SimpleContainer(3) {
				@Override public boolean canPlaceItem(int slot, ItemStack stack) {
					return !ItemStack.isSameItemSameComponents(stack, expectedTrim);
				}
			};
			blockedOutput.setItem(0, expectedInput.template().copy());
			blockedOutput.setItem(1, namedHelmet.copy());
			blockedOutput.setItem(2, expectedInput.addition().copy());
			ItemStack[] blockedBefore = snapshot(blockedOutput);
			boolean noRoom = RecipeCrafting.craftSmithingRecipe(context, blockedOutput, null, trim,
				stack -> stack.is(expectedTrim.getItem()), 64, false);
			check(!noRoom && sameContents(blockedOutput, blockedBefore),
				"a smithing result that cannot be inserted leaves all three exact inputs untouched"); passed++;
		}
		return passed;
	}

	private static CookingPotProbe loadedCookingPotRecipe(ServerLevel level) {
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CookingPotRecipe recipe)) continue;
			List<Ingredient> required = recipe.input();
			if (required.isEmpty() || required.size() > CookingPotRecipe.INPUT_SLOTS) continue;

			net.minecraft.world.item.ItemStackTemplate containerTemplate = recipe.container();
			ItemStack remainder = containerTemplate == null ? ItemStack.EMPTY : containerTemplate.create();
			if (remainder.isEmpty()) continue;

			List<ItemStack> ingredients = new java.util.ArrayList<>(required.size());
			boolean usable = true;
			boolean componentBearing = false;
			for (Ingredient ingredient : required) {
				ItemStack selected = ItemStack.EMPTY;
				for (var item : ingredient.items().toList()) {
					ItemStack candidate = new ItemStack(item.value());
					if (!ingredient.test(candidate)) continue;
					ItemStack namedCandidate = candidate.copy();
					namedCandidate.set(DataComponents.CUSTOM_NAME,
						Component.literal("component-bearing cooking-pot input"));
					if (ingredient.test(namedCandidate)) {
						selected = namedCandidate;
						componentBearing = true;
					} else {
						selected = candidate;
					}
					break;
				}
				if (selected.isEmpty()) {
					usable = false;
					break;
				}
				ingredients.add(selected);
			}
			if (!usable || !componentBearing) continue;

			RecipeWrapper input = cookingPotInput(ingredients);
			var loaded = level.recipeAccess().getRecipeFor(recipe.getType(), input, level);
			if (loaded.isEmpty() || loaded.get().value() != recipe) continue;
			ItemStack output = loaded.get().value().assemble(input);
			if (output.isEmpty() || ItemStack.isSameItemSameComponents(output, remainder)) continue;
			boolean overlapsInput = false;
			for (ItemStack ingredient : ingredients) {
				overlapsInput |= ItemStack.isSameItemSameComponents(output, ingredient)
					|| ItemStack.isSameItemSameComponents(remainder, ingredient);
			}
			if (overlapsInput) continue;
			return new CookingPotProbe(recipe, List.copyOf(ingredients), output.copy(), remainder.copy());
		}
		return null;
	}

	private static RecipeWrapper cookingPotInput(List<ItemStack> ingredients) {
		ItemStackHandler handler = new ItemStackHandler(CookingPotRecipe.INPUT_SLOTS) {
			@Override
			public it.unimi.dsi.fastutil.ints.IntList getInputSlotIndexes() {
				return it.unimi.dsi.fastutil.ints.IntList.of(0, 1, 2, 3, 4, 5);
			}
		};
		for (int slot = 0; slot < ingredients.size(); slot++) handler.setStackInSlot(slot, ingredients.get(slot).copy());
		return new RecipeWrapper(handler);
	}

	private static SimpleContainer cookingPotStock(CookingPotProbe probe, int count, int size) {
		SimpleContainer stock = new SimpleContainer(size);
		for (int slot = 0; slot < probe.ingredients().size(); slot++) {
			stock.setItem(slot, probe.ingredients().get(slot).copyWithCount(count));
		}
		return stock;
	}

	private static CuttingBoardProbe loadedCuttingBoardRecipe(ServerLevel level) {
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CuttingBoardRecipe recipe)
				|| recipe.getType() != ModRecipeTypes.CUTTING.get()
				|| recipe.getRollableResults().size() < 2
				|| recipe.getRollableResults().stream().anyMatch(result -> result.chance() < 1.0f)) continue;

			ItemStack inputStack = componentBearingSample(recipe.getInput(), "component-bearing cutting-board input");
			ItemStack toolStack = componentBearingSample(recipe.getTool(), "component-bearing cutting-board tool");
			if (inputStack.isEmpty() || toolStack.isEmpty() || !toolStack.isDamageableItem()) continue;

			CuttingBoardRecipeInput input = new CuttingBoardRecipeInput(inputStack, toolStack);
			if (!recipe.matches(input, level)) continue;
			var loaded = level.recipeAccess().getRecipeFor(recipe.getType(), input, level);
			if (loaded.isEmpty() || loaded.get().value() != recipe) continue;

			List<ItemStack> outputs = recipe.rollResults(level.getRandom(), 0);
			if (outputs.size() < 2 || outputs.stream().anyMatch(ItemStack::isEmpty)) continue;
			return new CuttingBoardProbe(recipe, inputStack.copy(), toolStack.copy(), outputs.stream().map(ItemStack::copy).toList());
		}
		return null;
	}

	private static ItemStack componentBearingSample(Ingredient ingredient, String name) {
		for (var item : ingredient.items().toList()) {
			ItemStack base = new ItemStack(item.value());
			ItemStack named = base.copy();
			named.set(DataComponents.CUSTOM_NAME, Component.literal(name));
			if (ingredient.test(named)) return named;
		}
		return ItemStack.EMPTY;
	}

	private static SimpleContainer cuttingBoardStock(CuttingBoardProbe probe, int inputCount, int size) {
		SimpleContainer stock = new SimpleContainer(size);
		stock.setItem(0, probe.input().copyWithCount(inputCount));
		stock.setItem(1, probe.tool().copy());
		return stock;
	}

	private static ItemStack damagedCuttingTool(ServerLevel level, ItemStack tool) {
		ItemStack damaged = tool.copy();
		damaged.hurtAndBreak(1, level, null, ignored -> { });
		return damaged;
	}

	private static boolean containsAllExactOutputs(SimpleContainer inventory, List<ItemStack> expectedOutputs) {
		for (int index = 0; index < expectedOutputs.size(); index++) {
			ItemStack expected = expectedOutputs.get(index);
			boolean seenEarlier = false;
			int expectedCount = 0;
			for (int previous = 0; previous < index; previous++) {
				if (ItemStack.isSameItemSameComponents(expectedOutputs.get(previous), expected)) seenEarlier = true;
			}
			if (seenEarlier) continue;
			for (ItemStack output : expectedOutputs) {
				if (ItemStack.isSameItemSameComponents(output, expected)) expectedCount += output.getCount();
			}
			int actualCount = 0;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack actual = inventory.getItem(slot);
				if (!actual.isEmpty() && ItemStack.isSameItemSameComponents(actual, expected)) actualCount += actual.getCount();
			}
			if (actualCount < expectedCount) return false;
		}
		return true;
	}

	private static ItemStack[] snapshot(SimpleContainer inventory) {
		ItemStack[] result = new ItemStack[inventory.getContainerSize()];
		for (int slot = 0; slot < result.length; slot++) result[slot] = inventory.getItem(slot).copy();
		return result;
	}

	private static boolean sameContents(SimpleContainer inventory, ItemStack[] expected) {
		if (inventory.getContainerSize() != expected.length) return false;
		for (int slot = 0; slot < expected.length; slot++) {
			if (!exactStack(inventory.getItem(slot), expected[slot])) return false;
		}
		return true;
	}

	private static boolean containsExact(SimpleContainer inventory, ItemStack expected) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (exactStack(inventory.getItem(slot), expected)) return true;
		}
		return false;
	}

	private static boolean exactStack(ItemStack actual, ItemStack expected) {
		if (expected.isEmpty()) return actual.isEmpty();
		return ItemStack.isSameItemSameComponents(actual, expected) && actual.getCount() == expected.getCount();
	}

	private static final class CustomRecipe extends NormalCraftingRecipe {
		private final PlacementInfo placement;
		private final List<Optional<Ingredient>> slots;
		private final int width;
		private final int height;
		private final net.minecraft.world.item.Item result;
		private final boolean shapeless;

		private CustomRecipe(List<Optional<Ingredient>> slots, int width, int height, net.minecraft.world.item.Item result) {
			super(new net.minecraft.world.item.crafting.Recipe.CommonInfo(true),
				new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""));
			this.slots = List.copyOf(slots);
			this.placement = PlacementInfo.createFromOptionals(slots);
			this.width = width;
			this.height = height;
			this.result = result;
			this.shapeless = false;
		}

		private CustomRecipe(List<Ingredient> ingredients, net.minecraft.world.item.Item result) {
			super(new net.minecraft.world.item.crafting.Recipe.CommonInfo(true),
				new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""));
			this.slots = List.of();
			this.placement = PlacementInfo.create(ingredients);
			this.width = 0;
			this.height = 0;
			this.result = result;
			this.shapeless = true;
		}

		private static CustomRecipe shapeless(List<Ingredient> ingredients, net.minecraft.world.item.Item result) {
			return new CustomRecipe(ingredients, result);
		}

		@Override protected PlacementInfo createPlacementInfo() { return placement; }
		@Override public boolean matches(CraftingInput input, Level level) {
			if (shapeless) {
				if (input.ingredientCount() != placement.ingredients().size()) return false;
				boolean[] used = new boolean[input.size()];
				for (Ingredient ingredient : placement.ingredients()) {
					boolean found = false;
					for (int i = 0; i < input.size(); i++) if (!used[i] && ingredient.test(input.getItem(i))) {
						used[i] = true;
						found = true;
						break;
					}
					if (!found) return false;
				}
				return true;
			}
			if (input.width() != width || input.height() != height || input.size() != slots.size()) return false;
			for (int i = 0; i < slots.size(); i++) {
				if (!Ingredient.testOptionalIngredient(slots.get(i), input.getItem(i))) return false;
			}
			return true;
		}
		@Override public ItemStack assemble(CraftingInput input) { return new ItemStack(result); }
		@Override public RecipeSerializer<? extends NormalCraftingRecipe> getSerializer() { return null; }
	}

	private record CookingPotProbe(CookingPotRecipe recipe, List<ItemStack> ingredients, ItemStack output, ItemStack remainder) { }
	private record CuttingBoardProbe(CuttingBoardRecipe recipe, ItemStack input, ItemStack tool, List<ItemStack> outputs) { }

	private static void check(boolean condition, String label) {
		if (!condition) throw new IllegalStateException("RECIPE CRAFTING CHECK FAILED: " + label);
		System.out.println("RECIPE CRAFTING PASS " + label);
	}
}
