package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.ints.IntList;
import vectorwing.farmersdelight.refabricated.inventory.ItemStackHandler;
import vectorwing.farmersdelight.refabricated.inventory.RecipeWrapper;

/** J02: cook turns actual communal pantry stock into loaded Delight meals at its kitchen stations. */
public final class CookDelightChecks {
	private CookDelightChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		BlockPos granary = at.north(14), kitchen = granary.east();
		BlockPos stationSite = kitchen.offset(-2, 0, -2);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos pos : new BlockPos[] { at.below(), at, at.above(), at.east(3).below(), at.east(3),
			granary.below(), granary, kitchen.below(), kitchen, kitchen.north() }) snapshot(level, before, pos);
		var potId = Identifier.parse("farmersdelight:cooking_pot");
		var boardId = Identifier.parse("farmersdelight:cutting_board");
		Block potBlock = BuiltInRegistries.BLOCK.getOptional(potId).orElse(null);
		Block boardBlock = BuiltInRegistries.BLOCK.getOptional(boardId).orElse(null);
		Villager cook = null;
		int checks = 0;
		try {
			prepareStationPad(level, before, kitchen, kitchen, kitchen.north());
			level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
			var ref = Checks090.fixtureForEquipment(level, at);
			level.setBlock(granary.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(kitchen.below(), Blocks.STONE.defaultBlockState(), 3);
			if (potBlock != null) level.setBlock(kitchen, potBlock.defaultBlockState(), 3);
			else level.setBlock(kitchen, Blocks.FURNACE.defaultBlockState(), 3);
			if (boardBlock != null) level.setBlock(kitchen.north(), boardBlock.defaultBlockState(), 3);
			Specials.set(ref, Blueprints.GRANARY, granary);
			Specials.set(ref, Blueprints.KITCHEN, kitchen);
			level.setBlock(granary, Blocks.CHEST.defaultBlockState(), 3);
			Container pantry = (Container) level.getBlockEntity(granary);
			if (pantry == null) throw new IllegalStateException("granary chest did not create a container");
			pantry.clearContent();
			pantry.setItem(0, new ItemStack(Items.WHEAT, 9));
			Container communal = ref.chest(level);
			if (communal == null) throw new IllegalStateException("village communal chest did not create a container");
			communal.clearContent();
			communal.setItem(0, new ItemStack(Blocks.CRAFTING_TABLE));
			communal.setItem(1, new ItemStack(Blocks.FURNACE));
			// Keep the villager within Workstation.ensure's interaction reach of the first supported kitchen site,
			// so this single check observes the placed block instead of JobUtil's transient WALK_TARGET.
			cook = EntityTypes.VILLAGER.spawn(level, stationSite.east(), EntitySpawnReason.COMMAND);
			if (cook == null) throw new IllegalStateException("cook villager did not spawn");
			cook.addTag("vp090_fixture"); cook.setNoAi(true); cook.setInvulnerable(true);
			check(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("chefsdelight"),
				"Chef's Delight addon is loaded in the dev pack"); checks++;
			check(potBlock != null, "Farmer's Delight cooking pot is installed in the dev pack"); checks++;
			check(boardBlock != null, "Farmer's Delight cutting board is installed in the dev pack"); checks++;
			var chefPotRecipeIds = level.recipeAccess().getRecipes().stream()
				.filter(holder -> holder.id().toString().startsWith("chefsdelight:"))
				.filter(holder -> holder.value() instanceof vectorwing.farmersdelight.common.crafting.CookingPotRecipe)
				.map(holder -> holder.id().toString()).toList();
			System.out.println("COOK DELIGHT INFO Chef's Delight loaded cooking-pot recipe IDs=" + chefPotRecipeIds);
			if (chefPotRecipeIds.isEmpty()) System.out.println("COOK DELIGHT INFO Chef's Delight has no cooking-pot recipes in this installed pack; no Chef's pot compatibility is claimed");
			var mem = cook.getAttachedOrCreate(VillagerPlus.MEMORY);
			var st = JobManager.state(cook);
			Ctx ctx = new Ctx(level, cook, st, mem, ref, level.getGameTime() + 100);
			CookJob job = new CookJob();
			check(job.primary(ctx) || job.secondary(new Ctx(level, cook, st, mem, ref, level.getGameTime() + 200)),
				"cook job progresses using granary stores and a real Delight or furnace workstation"); checks++;
			BlockPos placedStation = placedKitchenWorkstation(level, kitchen);
			check(placedStation != null && placedStation.distSqr(kitchen) >= 4 && placedStation.distSqr(kitchen) <= 32
				&& level.getBlockState(placedStation.below()).is(Blocks.STONE) && level.getBlockState(placedStation.above()).isAir(),
				"missing kitchen workstation is placed on a supported site beside the registered kitchen"
					+ " [placed=" + (placedStation == null ? "none" : placedStation.toShortString()) + "]"); checks++;
			check(JobUtil.count(pantry, s -> s.is(Items.WHEAT)) < 9 || JobUtil.count(pantry, s -> s.is(Items.BREAD)) > 0
			|| placedStation != null,
			"cook consumes pantry wheat, crafts bread, or places a kitchen workstation"); checks++;
			if (potBlock != null) {
				var chefRecipe = level.recipeAccess().getRecipes().stream()
					.filter(holder -> holder.id().identifier().getNamespace().equals("chefsdelight"))
					.filter(holder -> holder.value() instanceof vectorwing.farmersdelight.common.crafting.CookingPotRecipe recipe
						&& Meals.isFood(recipe.result().create()))
					.map(holder -> (vectorwing.farmersdelight.common.crafting.CookingPotRecipe) holder.value())
					.findFirst().orElse(null);
				var potRecipe = chefRecipe;
				if (potRecipe == null) potRecipe = DelightKitchenCrafting.preparedPotRecipe(level,
					Identifier.parse("farmersdelight:cooking/vegetable_soup"), Meals::isPreparedFood);
				if (potRecipe == null) potRecipe = DelightKitchenCrafting.firstPreparedPotRecipe(level, Meals::isPreparedFood);
				String potRecipeId = recipeId(level, potRecipe);
				check(potRecipe != null, "a loaded Farmer's/compatible-addon cooking-pot recipe produces a recognized meal"); checks++;
				if (potRecipe != null) {
					pantry.clearContent();
					int seeded = DelightKitchenCrafting.seedIngredients(pantry, potRecipe);
					List<ItemStack> potInputs = nonEmpty(snapshot(pantry));
					List<ItemStack> expectedRemainders = new java.util.ArrayList<>();
					for (int i = 0; i < seeded; i++) {
						ItemStack ingredient = pantry.getItem(i).copyWithCount(1);
						var remainder = ingredient.getCraftingRemainder();
						if (remainder != null) {
							ItemStack stack = remainder.create();
							if (!stack.isEmpty()) expectedRemainders.add(stack);
						} else {
							var override = vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES
								.get(ingredient.getItem());
							if (override != null) expectedRemainders.add(override.getDefaultInstance());
						}
					}
					var container = potRecipe.container();
					if (container != null) {
						ItemStack stack = container.create();
						if (!stack.isEmpty()) expectedRemainders.add(stack);
					}
					ItemStack expected = assembleSeededPotRecipe(level, pantry, potRecipe);
					cook.setPos(kitchen.getX() + 0.5, kitchen.getY() + 1, kitchen.getZ() + 0.5);
					boolean crafted = DelightKitchenCrafting.cookFromPot(
						new Ctx(level, cook, st, mem, ref, level.getGameTime() + 500), pantry, kitchen,
						Meals::isFood, 24, potRecipe);
					check(seeded > 0 && crafted, "villager runs a loaded Farmer's or Chef's Delight cooking-pot recipe at the built kitchen station"
						+ " [recipe=" + potRecipeId + ", inputs=" + describe(potInputs)
						+ ", consumed=one-per-ingredient, seeded=" + seeded
						+ ", crafted=" + crafted + ", pantry=" + java.util.stream.IntStream.range(0, pantry.getContainerSize())
							.mapToObj(pantry::getItem).filter(stack -> !stack.isEmpty()).toList() + "]"); checks++;
					check(JobUtil.count(pantry, stack -> ItemStack.isSameItemSameComponents(stack, expected)) >= expected.getCount(),
						"cooking-pot output is committed to the village pantry as the exact recipe result"
							+ " [recipe=" + potRecipeId + ", output=" + describe(List.of(expected))
							+ ", remainders=" + describe(expectedRemainders) + "]"); checks++;
					boolean potRemaindersAccounted = expectedRemainders.stream().allMatch(expectedRemainder ->
						JobUtil.count(pantry, present -> ItemStack.isSameItemSameComponents(present, expectedRemainder)) >= expectedRemainder.getCount());
					check(potRemaindersAccounted, "cooking-pot ingredient remainders and recipe serving container are returned to the pantry"); checks++;
					boolean potInputsAccounted = potInputs.stream().allMatch(input -> {
						int expectedRemainderCount = expectedRemainders.stream()
							.filter(remainder -> ItemStack.isSameItemSameComponents(input, remainder))
							.mapToInt(ItemStack::getCount).sum();
						return JobUtil.count(pantry, present -> ItemStack.isSameItemSameComponents(present, input)) == expectedRemainderCount;
					});
					check(potInputsAccounted, "each real pantry ingredient is consumed once, with only declared remainders returned"
						+ " [recipe=" + potRecipeId + ", consumed=" + describe(potInputs)
						+ ", returned=" + describe(expectedRemainders) + "]"); checks++;

					pantry.clearContent();
					int cappedSeeded = DelightKitchenCrafting.seedIngredients(pantry, potRecipe);
					List<ItemStack> beforeCap = snapshot(pantry);
					boolean capRejected = cappedSeeded > 0 && !DelightKitchenCrafting.cookFromPot(
						new Ctx(level, cook, st, mem, ref, level.getGameTime() + 600), pantry, kitchen,
						Meals::isFood, 0, potRecipe);
					check(capRejected && sameContents(pantry, beforeCap),
						"output-cap rejection leaves every cooking ingredient in the pantry unchanged [recipe=" + potRecipeId + "]"); checks++;

					pantry.clearContent();
					int fullSeeded = DelightKitchenCrafting.seedIngredients(pantry, potRecipe);
					boolean inputsCanStayInFullSlots = fullSeeded > 0;
					for (int i = 0; i < fullSeeded; i++) {
						ItemStack ingredient = pantry.getItem(i);
						if (ingredient.getMaxStackSize() <= 1) inputsCanStayInFullSlots = false;
						pantry.setItem(i, ingredient.copyWithCount(ingredient.getMaxStackSize()));
					}
					fillEmptySlots(pantry);
					List<ItemStack> beforeFullPantry = snapshot(pantry);
					boolean noOutputSlot = inputsCanStayInFullSlots && isFull(pantry)
						&& !DelightKitchenCrafting.cookFromPot(
							new Ctx(level, cook, st, mem, ref, level.getGameTime() + 700), pantry, kitchen,
							Meals::isFood, Integer.MAX_VALUE, potRecipe);
					check(noOutputSlot && sameContents(pantry, beforeFullPantry),
						"full pantry with no compatible output slot keeps all inputs unchanged [recipe=" + potRecipeId
							+ ", material=" + describe(nonEmpty(beforeFullPantry)) + "]"); checks++;
				}
			}
			if (boardBlock != null) {
				var boardRecipe = DelightKitchenCrafting.firstGuaranteedPotPrepRecipe(level);
				check(boardRecipe != null, "loaded cutting-board recipe has a guaranteed result used by a loaded cooking-pot recipe"); checks++;
				if (boardRecipe != null) {
					String boardRecipeId = recipeId(level, boardRecipe);
					pantry.clearContent();
					int seeded = DelightKitchenCrafting.seedCuttingIngredients(pantry, boardRecipe);
					ItemStack inputBefore = pantry.getItem(0).copy();
					ItemStack toolBefore = pantry.getItem(1).copy();
					int oldToolDamage = toolBefore.getDamageValue();
					long seed = 741258963L;
					var expectedResults = boardRecipe.rollResults(RandomSource.create(seed), 1);
					cook.setPos(kitchen.getX() + 0.5, kitchen.getY() + 1, kitchen.getZ() + 0.5);
					boolean prepared = DelightKitchenCrafting.prepareOnBoard(
						new Ctx(level, cook, st, mem, ref, level.getGameTime() + 800), pantry, kitchen.north(), 64,
						RandomSource.create(seed), boardRecipe);
					check(seeded == 2 && prepared, "villager prepares the selected loaded recipe at the real cutting-board station"
						+ " [recipe=" + boardRecipeId + ", input=" + describe(List.of(inputBefore))
						+ ", output=" + describe(expectedResults) + "]"); checks++;
					check(JobUtil.count(pantry, stack -> ItemStack.isSameItemSameComponents(stack, inputBefore)) == 0,
						"cutting-board input is consumed exactly once"); checks++;
					boolean resultsAccounted = expectedResults.stream().allMatch(expected ->
						JobUtil.count(pantry, present -> ItemStack.isSameItemSameComponents(present, expected)) >= expected.getCount());
					check(resultsAccounted, "cutting-board recipe results are stored with their exact item counts"); checks++;
					int expectedDamage = oldToolDamage + 1;
					ItemStack returnedTool = ItemStack.EMPTY;
					for (int i = 0; i < pantry.getContainerSize(); i++) {
						ItemStack candidate = pantry.getItem(i);
						if (!candidate.isEmpty() && candidate.is(toolBefore.getItem())) { returnedTool = candidate; break; }
					}
					boolean toolAccounted = expectedDamage < toolBefore.getMaxDamage()
						? !returnedTool.isEmpty() && returnedTool.getCount() == toolBefore.getCount() && returnedTool.getDamageValue() == expectedDamage
						: returnedTool.isEmpty();
					check(toolAccounted, "cutting tool returns with one durability consumed or is removed when it breaks"); checks++;

					pantry.clearContent();
					int fullBoardSeeded = DelightKitchenCrafting.seedCuttingIngredients(pantry, boardRecipe);
					ItemStack stockedInput = pantry.getItem(0);
					if (stockedInput.getMaxStackSize() > 1) {
						pantry.setItem(0, stockedInput.copyWithCount(stockedInput.getMaxStackSize()));
					}
					fillEmptySlots(pantry);
					List<ItemStack> beforeFullBoard = snapshot(pantry);
					boolean boardNoOutputSlot = fullBoardSeeded == 2 && stockedInput.getMaxStackSize() > 1
						&& isFull(pantry) && !DelightKitchenCrafting.prepareOnBoard(
							new Ctx(level, cook, st, mem, ref, level.getGameTime() + 900), pantry, kitchen.north(),
							Integer.MAX_VALUE, RandomSource.create(seed), boardRecipe);
					check(boardNoOutputSlot && sameContents(pantry, beforeFullBoard),
						"full pantry with no cutting-board output slot preserves the ingredient and tool [recipe=" + boardRecipeId
							+ ", material=" + describe(nonEmpty(beforeFullBoard)) + "]"); checks++;
				}
			}
			return checks;
		} finally {
			if (cook != null) cook.discard();
			restore(level, before);
		}
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		BlockPos immutable = pos.immutable();
		if (before.containsKey(immutable)) return;
		level.getChunkAt(immutable);
		BlockState state = level.getBlockState(immutable);
		BlockEntity entity = level.getBlockEntity(immutable);
		before.put(immutable, new Cell(state, entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void prepareStationPad(ServerLevel level, Map<BlockPos, Cell> before, BlockPos kitchen,
			BlockPos keepA, BlockPos keepB) {
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			BlockPos pos = kitchen.offset(dx, 0, dz);
			snapshot(level, before, pos.below());
			snapshot(level, before, pos);
			snapshot(level, before, pos.above());
			level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 2);
			if (!pos.equals(keepA) && !pos.equals(keepB)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
		}
	}

	private static BlockPos placedKitchenWorkstation(ServerLevel level, BlockPos kitchen) {
		for (BlockPos pos : BlockPos.betweenClosed(kitchen.offset(-4, 0, -4), kitchen.offset(4, 0, 4))) {
			double distance = pos.distSqr(kitchen);
			if (distance < 4 || distance > 32) continue;
			if (!level.getBlockState(pos).is(Blocks.CRAFTING_TABLE) && !level.getBlockState(pos).is(Blocks.FURNACE)) continue;
			if (!level.getBlockState(pos.below()).is(Blocks.STONE) || !level.getBlockState(pos.above()).isAir()) continue;
			return pos.immutable();
		}
		return null;
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.nbt() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private record Cell(BlockState state, CompoundTag nbt) { }

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Cook delight: " + message);
		System.out.println("COOK DELIGHT PASS " + message);
	}

	private static String recipeId(ServerLevel level, Object recipe) {
		if (recipe == null) return "missing";
		return level.recipeAccess().getRecipes().stream().filter(holder -> holder.value() == recipe)
			.map(holder -> holder.id().toString()).findFirst().orElse("unknown");
	}

	private static ItemStack assembleSeededPotRecipe(ServerLevel level, Container pantry,
			vectorwing.farmersdelight.common.crafting.CookingPotRecipe recipe) {
		ItemStackHandler handler = new ItemStackHandler(vectorwing.farmersdelight.common.crafting.CookingPotRecipe.INPUT_SLOTS) {
			@Override
			public IntList getInputSlotIndexes() {
				return IntList.of(0, 1, 2, 3, 4, 5);
			}
		};
		for (int slot = 0; slot < pantry.getContainerSize() && slot < 6; slot++) {
			if (!pantry.getItem(slot).isEmpty()) handler.setStackInSlot(slot, pantry.getItem(slot).copy());
		}
		RecipeWrapper input = new RecipeWrapper(handler);
		return recipe.matches(input, level) ? recipe.assemble(input) : ItemStack.EMPTY;
	}

	private static List<ItemStack> snapshot(Container pantry) {
		List<ItemStack> copy = new java.util.ArrayList<>(pantry.getContainerSize());
		for (int i = 0; i < pantry.getContainerSize(); i++) copy.add(pantry.getItem(i).copy());
		return copy;
	}

	private static List<ItemStack> nonEmpty(List<ItemStack> stacks) {
		return stacks.stream().filter(stack -> !stack.isEmpty()).toList();
	}

	private static boolean sameContents(Container pantry, List<ItemStack> expected) {
		if (pantry.getContainerSize() != expected.size()) return false;
		for (int i = 0; i < pantry.getContainerSize(); i++) {
			ItemStack current = pantry.getItem(i);
			ItemStack before = expected.get(i);
			if (current.isEmpty() != before.isEmpty()) return false;
			if (!current.isEmpty() && (!ItemStack.isSameItemSameComponents(current, before)
				|| current.getCount() != before.getCount())) return false;
		}
		return true;
	}

	private static void fillEmptySlots(Container pantry) {
		for (int i = 0; i < pantry.getContainerSize(); i++) {
			if (pantry.getItem(i).isEmpty()) pantry.setItem(i, new ItemStack(Items.STONE, 64));
		}
		pantry.setChanged();
	}

	private static boolean isFull(Container pantry) {
		for (int i = 0; i < pantry.getContainerSize(); i++) if (pantry.getItem(i).isEmpty()) return false;
		return true;
	}

	private static String describe(List<ItemStack> stacks) {
		return nonEmpty(stacks).stream()
			.map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount()
				+ (stack.isDamageableItem() ? "(damage=" + stack.getDamageValue() + ")" : ""))
			.toList().toString();
	}
}
