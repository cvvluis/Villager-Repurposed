package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** Focused checks for the loaded-recipe path used by simple job crafting. */
public final class CraftRecipeChecks {
	private CraftRecipeChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		var ref = Checks090.fixtureForEquipment(level, at);
		Container box = ref.chest(level);
		if (box == null) throw new IllegalStateException("CRAFT RECIPE CHECKS: fixture communal chest is missing");
		List<ItemStack> originalStock = new ArrayList<>();
		for (int slot = 0; slot < box.getContainerSize(); slot++) originalStock.add(box.getItem(slot).copy());
		BlockPos chestPos = ref.chestPos(level);
		Villager villager = EntityTypes.VILLAGER.spawn(level, chestPos.above(), EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("CRAFT RECIPE CHECKS: fixture villager did not spawn");
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		int checks = 0;
		try {
			boolean loadedStickRecipe = level.recipeAccess().getRecipes().stream()
				.anyMatch(holder -> holder.value() instanceof CraftingRecipe recipe
					&& RecipeCrafting.preview(recipe) != null
					&& RecipeCrafting.preview(recipe).is(Items.STICK));
			check(loadedStickRecipe, "loaded vanilla stick recipe is available to job crafting");
			checks++;
			checkLoadedRecipe(level, "minecraft:iron_ingot_from_nuggets", Items.IRON_INGOT,
				new ItemStack(Items.IRON_NUGGET, 9));
			checks++;
			checkLoadedRecipe(level, "minecraft:bread", Items.BREAD,
				new ItemStack(Items.WHEAT, 3));
			checks++;
			checkLoadedRecipe(level, "minecraft:arrow", Items.ARROW,
				new ItemStack(Items.FLINT), new ItemStack(Items.STICK), new ItemStack(Items.FEATHER));
			checks++;
			checkLoadedRecipe(level, "minecraft:stick", Items.STICK,
				new ItemStack(Items.OAK_PLANKS, 2));
			checks++;
			checkLoadedRecipe(level, "minecraft:white_bed", Items.BED.white(),
				new ItemStack(Items.WOOL.white(), 3), new ItemStack(Items.OAK_PLANKS, 3));
			checks++;

			// The legacy recipe deliberately says flint, while the loaded recipe uses two planks.
			box.clearContent();
			box.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
			box.setChanged();
			Craft.Recipe legacyStickRecipe = Craft.recipe("legacy sticks",
				() -> new ItemStack(Items.STICK, 4), Craft.of(Items.FLINT, 1));
			Ctx ctx = new Ctx(level, villager, new JobState(),
				villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 1000);
			boolean crafted = Craft.run(ctx, List.of(legacyStickRecipe));
			check(crafted && JobUtil.count(box, stack -> stack.is(Items.STICK)) == 4
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.FLINT)) == 0,
				"loaded plank ingredients craft sticks even when the legacy ingredient differs");
			checks++;

			// With only the stale ingredient present, a normal job recipe must not use a manual fallback.
			box.clearContent();
			box.setItem(0, new ItemStack(Items.FLINT, 1));
			box.setChanged();
			ctx = new Ctx(level, villager, new JobState(),
				villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 1100);
			boolean attempted = Craft.run(ctx, List.of(legacyStickRecipe));
			check(!attempted && JobUtil.count(box, stack -> stack.is(Items.FLINT)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.STICK)) == 0,
				"missing loaded ingredients preserve stock and cannot trigger stale manual crafting");
			checks++;

			// Cooking is an intentional non-crafting job path and must opt in explicitly.
			box.clearContent();
			box.setItem(0, new ItemStack(Items.BEEF, 1));
			box.setChanged();
			check(!loadedRecipeProduces(level, box, Items.COOKED_BEEF),
				"raw beef has no matching loaded crafting recipe for cooked beef in the test inventory");
			checks++;
			Craft.Recipe unmarkedCook = Craft.recipe("unmarked cooked beef",
				() -> new ItemStack(Items.COOKED_BEEF), Craft.of(Items.BEEF, 1));
			ctx = new Ctx(level, villager, new JobState(),
				villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 1200);
			boolean unmarkedCookingRan = Craft.run(ctx, List.of(unmarkedCook));
			check(!unmarkedCookingRan && JobUtil.count(box, stack -> stack.is(Items.BEEF)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.COOKED_BEEF)) == 0,
				"default loaded-only recipe cannot consume ingredients through a manual cook fallback");
			checks++;

			Craft.Recipe explicitCook = Craft.manualRecipe("manual cooked beef",
				() -> new ItemStack(Items.COOKED_BEEF), Craft.of(Items.BEEF, 1));
			ctx = new Ctx(level, villager, new JobState(),
				villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 1300);
			boolean cookingRan = Craft.run(ctx, List.of(explicitCook));
			check(cookingRan && JobUtil.count(box, stack -> stack.is(Items.BEEF)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.COOKED_BEEF)) == 1,
				"explicit manual cooking consumes raw beef and deposits cooked beef");
			checks++;
			return checks;
		} finally {
			villager.discard();
			for (int slot = 0; slot < originalStock.size(); slot++) box.setItem(slot, originalStock.get(slot));
			box.setChanged();
		}
	}

	private static boolean loadedRecipeProduces(ServerLevel level, Container stock, Item output) {
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			var input = RecipeCrafting.inputFor(recipe, stock, level);
			if (input == null) continue;
			ItemStack result = recipe.assemble(input);
			if (!result.isEmpty() && result.is(output)) return true;
		}
		return false;
	}

	private static void checkLoadedRecipe(ServerLevel level, String id, Item expectedOutput, ItemStack... ingredients) {
		var holder = level.recipeAccess().getRecipes().stream()
			.filter(entry -> entry.id().identifier().toString().equals(id))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("CRAFT RECIPE CHECKS: loaded recipe is missing: " + id));
		if (!(holder.value() instanceof CraftingRecipe recipe)) {
			throw new IllegalStateException("CRAFT RECIPE CHECKS: loaded recipe is not a crafting recipe: " + id);
		}
		SimpleContainer stock = new SimpleContainer(9);
		for (int i = 0; i < ingredients.length; i++) stock.setItem(i, ingredients[i].copy());
		var input = RecipeCrafting.inputFor(recipe, stock, level);
		check(input != null && recipe.matches(input, level) && recipe.assemble(input).is(expectedOutput),
			"loaded recipe " + id + " assembles from its real Vanilla ingredients");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("CRAFT RECIPE CHECKS: " + message);
		System.out.println("CRAFT RECIPE PASS " + message);
	}
}
