package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

/** Focused live check for a loaded food-baking recipe and its persistent oven claim. */
public final class DelightKitchenCraftingChecks {
	private DelightKitchenCraftingChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		if (!FabricLoader.getInstance().isModLoaded("farmersdelight")) {
			throw new IllegalStateException("Delight kitchen checks require the installed Farmer's Delight mod");
		}
		BlockPos base = at.offset(384, 0, 384);
		BlockPos granary = base.offset(14, 0, 0);
		BlockPos oven = base.offset(0, 0, 14);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos pos : new BlockPos[] { base.below(), base, base.east(3).below(), base.east(3),
			granary.below(), granary, oven.below(), oven }) snapshot(level, before, pos);
		Villager cook = null;
		int checks = 0;
		try {
			var ref = Checks090.fixtureForEquipment(level, base);
			level.setBlock(granary.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(granary, Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(oven.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(oven, Blocks.FURNACE.defaultBlockState(), 3);
			Specials.set(ref, Blueprints.GRANARY, granary);
			Specials.set(ref, Blueprints.KITCHEN, oven);
			Container pantry = (Container) level.getBlockEntity(granary);
			AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(oven);
			if (pantry == null || furnace == null) throw new IllegalStateException("Delight kitchen fixture blocks did not create block entities");
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			furnace.setChanged();
			var recipe = firstLoadedFoodSmeltingRecipe(level);
			cook = EntityTypes.VILLAGER.spawn(level, oven.above(), EntitySpawnReason.COMMAND);
			if (cook == null) throw new IllegalStateException("Delight kitchen villager did not spawn");
			cook.addTag("vp090_fixture");
			cook.setNoAi(true);
			cook.setInvulnerable(true);
			cook.setPos(oven.getX() + 0.5, oven.getY() + 1.0, oven.getZ() + 0.5);
			var memory = cook.getAttachedOrCreate(VillagerPlus.MEMORY);
			var state = JobManager.state(cook);
			check(recipe != null, "the runtime recipe manager has a food-producing Farmer's Delight oven recipe"); checks++;
			if (recipe == null) return checks;
			pantry.clearContent();
			pantry.setItem(0, recipe.input().copyWithCount(2));
			pantry.setChanged();
			Ctx ctx = new Ctx(level, cook, state, memory, ref, level.getGameTime() + 2200);
			boolean loaded = DelightKitchenCrafting.cookInOven(ctx);
			ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
			check(loaded && pantry.getItem(0).getCount() == 1 && furnace.getItem(0).is(recipe.input().getItem())
				&& claim != null && ItemStack.isSameItemSameComponents(claim, recipe.output())
				&& claim.getCount() == recipe.output().getCount(),
				"one real pantry input is reserved against the exact loaded oven output [recipe=" + recipe.id() + "]"); checks++;
			List<ItemStack> afterClaim = List.of(pantry.getItem(0).copy(), furnace.getItem(0).copy(), claim.copy());
			DelightKitchenCrafting.cookInOven(new Ctx(level, cook, state, memory, ref, level.getGameTime() + 2300));
			ItemStack currentClaim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
			check(pantry.getItem(0).getCount() == afterClaim.get(0).getCount()
				&& furnace.getItem(0).getCount() == afterClaim.get(1).getCount()
				&& ItemStack.isSameItemSameComponents(furnace.getItem(0), afterClaim.get(1))
				&& currentClaim != null && ItemStack.isSameItemSameComponents(currentClaim, afterClaim.get(2))
				&& currentClaim.getCount() == afterClaim.get(2).getCount(),
				"an active oven claim cannot consume the next pantry portion a second time"); checks++;
			CookingPotRecipe potRecipe = firstLoadedFoodPotRecipe(level);
			check(potRecipe != null, "the runtime recipe manager exposes a real Farmer's Delight cooking-pot recipe"); checks++;
			if (potRecipe != null) {
				Identifier potRecipeId = level.recipeAccess().getRecipes().stream()
					.filter(holder -> holder.value() == potRecipe).map(holder -> holder.id().identifier()).findFirst().orElseThrow();
				check(DelightKitchenCrafting.preparedPotRecipe(level, potRecipeId, Meals::isFood) == potRecipe,
					"recipe selection resolves the exact currently loaded recipe ID [recipe=" + potRecipeId + "]"); checks++;
				Identifier missingRecipeId = Identifier.parse("villagerplus:delight_recipe_not_loaded");
				check(DelightKitchenCrafting.preparedPotRecipe(level, missingRecipeId, Meals::isFood) == null,
					"a recipe ID absent from the runtime manager is safely rejected"); checks++;

				pantry.clearContent();
				int seeded = DelightKitchenCrafting.seedIngredients(pantry, potRecipe);
				List<ItemStack> realInputs = contents(pantry);
				boolean capRejected = seeded > 0 && !DelightKitchenCrafting.cookFromPot(
					new Ctx(level, cook, state, memory, ref, level.getGameTime() + 2400), pantry, oven,
					Meals::isFood, 0, potRecipe);
				check(capRejected && sameContents(pantry, realInputs),
					"output-cap rejection preserves every real cooking ingredient [recipe=" + potRecipeId + "]"); checks++;
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

	private static LoadedRecipe firstLoadedFoodSmeltingRecipe(ServerLevel level) {
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!holder.id().identifier().getNamespace().equals("farmersdelight")
				|| !(holder.value() instanceof AbstractCookingRecipe recipe) || recipe.getType() != RecipeType.SMELTING) continue;
			for (ItemStack candidate : recipe.input().items().map(Holder::value).map(net.minecraft.world.item.Item::getDefaultInstance)
				.filter(recipe.input()::test).toList()) {
				if (candidate.getItem().getCraftingRemainder() != null
					|| SmithSmelting.matchingRecipe(level, candidate, RecipeType.SMELTING) != recipe) continue;
				ItemStack output = recipe.assemble(new SingleRecipeInput(candidate));
				if (Meals.isPreparedFood(output)) return new LoadedRecipe(holder.id().toString(), candidate, output);
			}
		}
		return null;
	}

	private static CookingPotRecipe firstLoadedFoodPotRecipe(ServerLevel level) {
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (holder.value() instanceof CookingPotRecipe recipe && recipe.getType() == ModRecipeTypes.COOKING.get()
				&& holder.id().identifier().getNamespace().equals("farmersdelight")
				&& Meals.isFood(recipe.result().create())) return recipe;
		}
		return null;
	}

	private static List<ItemStack> contents(Container container) {
		List<ItemStack> snapshot = new java.util.ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) snapshot.add(container.getItem(slot).copy());
		return snapshot;
	}

	private static boolean sameContents(Container container, List<ItemStack> expected) {
		if (container.getContainerSize() != expected.size()) return false;
		for (int slot = 0; slot < expected.size(); slot++) {
			ItemStack actual = container.getItem(slot), before = expected.get(slot);
			if (actual.isEmpty() != before.isEmpty()) return false;
			if (!actual.isEmpty() && (!ItemStack.isSameItemSameComponents(actual, before)
				|| actual.getCount() != before.getCount())) return false;
		}
		return true;
	}

	private static void check(boolean passed, String message) {
		if (!passed) throw new IllegalStateException("Delight kitchen check: " + message);
		System.out.println("DELIGHT KITCHEN PASS " + message);
	}

	private record LoadedRecipe(String id, ItemStack input, ItemStack output) { }
}
