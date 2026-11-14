package de.villagerplus.job;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** Loaded-recipe and exact-inventory checks for on-demand resident chest supply. */
public final class FletcherChestChecks {
	private FletcherChestChecks() { }

	public static int run(ServerLevel level) {
		CraftingRecipe chestRecipe = level.recipeAccess().getRecipes().stream()
			.filter(holder -> holder.id().identifier().toString().equals("minecraft:chest"))
			.map(holder -> holder.value())
			.filter(CraftingRecipe.class::isInstance)
			.map(CraftingRecipe.class::cast)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("FLETCHER CHESTS: loaded minecraft:chest recipe is missing"));

		SimpleContainer shortStock = new SimpleContainer(1);
		shortStock.setItem(0, new ItemStack(Items.OAK_PLANKS, 7));
		check(RecipeCrafting.inputFor(chestRecipe, shortStock, level) == null
			&& JobUtil.count(shortStock, stack -> stack.is(Items.OAK_PLANKS)) == 7,
			"seven real planks cannot produce a chest and remain untouched");

		SimpleContainer stocked = new SimpleContainer(1);
		stocked.setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
		var input = RecipeCrafting.inputFor(chestRecipe, stocked, level);
		check(input != null && chestRecipe.matches(input, level) && chestRecipe.assemble(input).is(Items.CHEST),
			"woodcutter uses the loaded vanilla chest recipe with a valid crafting grid");
		if (input != null) {
			ItemStack output = chestRecipe.assemble(input);
			List<ItemStack> remainders = chestRecipe.getRemainingItems(input);
			check(RecipeCrafting.commit(stocked, input, output, remainders)
				&& JobUtil.count(stocked, stack -> stack.is(Items.OAK_PLANKS)) == 0
				&& JobUtil.count(stocked, stack -> stack.is(Items.CHEST)) == 1,
				"exactly eight planks are consumed and one real chest enters the stock transaction");
		}
		return input == null ? 2 : 3;
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new IllegalStateException("FLETCHER CHEST CHECK FAILED: " + label);
		System.out.println("FLETCHER CHEST PASS " + label);
	}
}
