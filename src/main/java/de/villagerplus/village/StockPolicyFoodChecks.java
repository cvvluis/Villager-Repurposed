package de.villagerplus.village;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

/** Focused G04 check for pantry routing of ingredients in the loaded Farmer's Delight recipe set. */
public final class StockPolicyFoodChecks {
	private StockPolicyFoodChecks() { }

	public static int run(ServerLevel level) {
		var recipe = level.recipeAccess().getRecipes().stream()
			.filter(holder -> holder.id().identifier().toString().equals("farmersdelight:cooking/squid_ink_pasta"))
			.map(holder -> holder.value())
			.filter(CookingPotRecipe.class::isInstance)
			.map(CookingPotRecipe.class::cast)
			.findFirst().orElse(null);
		check(recipe != null,
			"installed Farmer's Delight pack has the registered squid-ink pasta cooking-pot recipe");
		if (recipe == null) return 1;

		boolean inkSacIsRecipeInput = recipe.input().stream().anyMatch(ingredient -> ingredient.test(new ItemStack(Items.INK_SAC)));
		check(inkSacIsRecipeInput, "registered squid-ink pasta recipe directly consumes minecraft:ink_sac");
		check(StockPolicy.category(Items.INK_SAC) == StockPolicy.Category.FOOD,
			"cooking ingredient minecraft:ink_sac is routed to the granary food category");

		var broth = level.recipeAccess().getRecipes().stream()
			.filter(holder -> holder.id().identifier().toString().equals("farmersdelight:cooking/bone_broth"))
			.map(holder -> holder.value())
			.filter(CookingPotRecipe.class::isInstance)
			.map(CookingPotRecipe.class::cast)
			.findFirst().orElse(null);
		check(broth != null,
			"installed Farmer's Delight pack has the registered bone-broth cooking-pot recipe");
		if (broth == null) return 3;
		check(broth.input().stream().anyMatch(ingredient -> ingredient.test(new ItemStack(Items.BONE))),
			"registered bone-broth recipe directly consumes minecraft:bone");
		check(StockPolicy.category(Items.BONE) == StockPolicy.Category.FOOD,
			"cooking ingredient minecraft:bone is routed to the granary food category");
		return 6;
	}

	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("STOCK POLICY FOOD CHECK FAILED: " + label);
		System.out.println("STOCK POLICY FOOD PASS " + label);
	}
}
