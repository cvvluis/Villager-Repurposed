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
			checkLoadedRecipe(level, "villagerplus:chest_access", de.villagerplus.block.ModItems.CHEST_ACCESS,
				new ItemStack(Items.PAPER), new ItemStack(Items.EMERALD));
			checks++;

			// R01: lever (loaded shaped recipe) and a leftover-container recipe (cake returns its milk buckets) end to end.
			checkLoadedRecipe(level, "minecraft:lever", Items.LEVER, new ItemStack(Items.STICK), new ItemStack(Items.COBBLESTONE));
			checks++;
			box.clearContent();
			box.setItem(0, new ItemStack(Items.STICK, 1));
			box.setItem(1, new ItemStack(Items.COBBLESTONE, 1));
			box.setChanged();
			Ctx leverCtx = new Ctx(level, villager, new JobState(),
				villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 1050);
			boolean leverCrafted = RecipeCrafting.craft(leverCtx, box, null, stack -> stack.is(Items.LEVER), 1);
			check(leverCrafted && JobUtil.count(box, stack -> stack.is(Items.LEVER)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.STICK)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.COBBLESTONE)) == 0,
				"a lever is crafted from one stick and one cobblestone through the loaded recipe path");
			checks++;
			SimpleContainer pantry = new SimpleContainer(27);
			for (int i = 0; i < 3; i++) pantry.setItem(i + 10, new ItemStack(Items.MILK_BUCKET, 1));
			pantry.setItem(1, new ItemStack(Items.SUGAR, 2));
			pantry.setItem(2, new ItemStack(Items.EGG, 1));
			pantry.setItem(3, new ItemStack(Items.WHEAT, 3));
			var cake = level.recipeAccess().getRecipes().stream()
				.filter(entry -> entry.id().identifier().toString().equals("minecraft:cake"))
				.map(entry -> (CraftingRecipe) entry.value()).findFirst()
				.orElseThrow(() -> new IllegalStateException("CRAFT RECIPE CHECKS: loaded recipe is missing: minecraft:cake"));
			var cakeInput = RecipeCrafting.inputFor(cake, pantry, level);
			boolean cakeCommitted = cakeInput != null && RecipeCrafting.commit(pantry, cakeInput, cake.assemble(cakeInput),
				cake.getRemainingItems(cakeInput));
			check(cakeCommitted && JobUtil.count(pantry, stack -> stack.is(Items.CAKE)) == 1
				&& JobUtil.count(pantry, stack -> stack.is(Items.BUCKET)) == 3
				&& JobUtil.count(pantry, stack -> stack.is(Items.MILK_BUCKET)) == 0,
				"cake returns its three empty buckets as leftover containers instead of destroying them");
			checks++;
			checks += sweepAllCraftingRecipes(level);

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

			// A shape the plan asks for is crafted in batches at one stop of the table (fifty roof stairs used to cost thirteen stops).
			box.clearContent();
			box.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 18));
			var data = ref.data();
			var savedProjects = List.copyOf(data.buildProjects);
			try {
				data.buildProjects.clear();
				data.putBuildProject(new de.villagerplus.village.VillageData.BuildProject("granary", at, 0, 0, 0, java.util.Optional.empty(), 1L,
					java.util.Optional.empty(), 0L, List.of("minecraft:spruce_stairs#12"), de.villagerplus.village.VillageData.ProjectHaul.empty(),
					java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false, false));
				data.syncPrimaryProjectView();
				JobState stairsState = new JobState();
				Ctx stairsCtx = new Ctx(level, villager, stairsState, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 900);
				boolean stairsCrafted = RecipeCrafting.craft(stairsCtx, box, null, stack -> stack.is(Items.SPRUCE_STAIRS), 12);
				check(stairsCrafted && JobUtil.count(box, stack -> stack.is(Items.SPRUCE_STAIRS)) == 12
					&& JobUtil.count(box, stack -> stack.is(Items.SPRUCE_PLANKS)) == 0
					&& stairsState.cooldownUntil >= stairsCtx.now() + 100 + RecipeCrafting.PLANK_BATCH_PAUSE * 2,
					"a requested shape is crafted in one stop up to the plan's request (12 stairs from 18 planks, not 4)");
				checks++;
				box.clearContent();
				box.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 18));
				data.buildProjects.clear();
				data.clearPlanViewForProjectSlot();
				stairsState = new JobState();
				stairsCtx = new Ctx(level, villager, stairsState, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 950);
				RecipeCrafting.craft(stairsCtx, box, null, stack -> stack.is(Items.SPRUCE_STAIRS), 12);
				check(JobUtil.count(box, stack -> stack.is(Items.SPRUCE_STAIRS)) == 4,
					"without a plan request a shape is still crafted one recipe at a time");
				checks++;
			} finally {
				data.buildProjects.clear();
				for (var saved : savedProjects) data.putBuildProject(saved);
				data.syncPrimaryProjectView();
			}

			// Perf guard: an idle crafter (nothing craftable) must not scan the recipe list on every tick.
			box.clearContent();
			box.setItem(0, new ItemStack(Items.FLINT, 1));
			box.setChanged();
			JobState idleState = new JobState();
			long idleNow = level.getGameTime() + 2000;
			long scansBefore = de.villagerplus.village.RecipeIndex.scans();
			for (int tick = 0; tick < 100; tick++) {
				Ctx idleCtx = new Ctx(level, villager, idleState, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, idleNow + tick);
				if (Craft.run(idleCtx, List.of(legacyStickRecipe))) throw new IllegalStateException("CRAFT RECIPE CHECKS: idle craft must not succeed");
			}
			long idleScans = de.villagerplus.village.RecipeIndex.scans() - scansBefore;
			check(idleScans <= 2, "100 idle Craft.run ticks scan the recipe list at most twice (" + idleScans + ")");
			checks++;
			box.setItem(1, new ItemStack(Items.OAK_PLANKS, 2));
			Ctx wakeCtx = new Ctx(level, villager, idleState, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, idleNow + 101);
			check(Craft.run(wakeCtx, List.of(legacyStickRecipe)) && JobUtil.count(box, stack -> stack.is(Items.STICK)) == 4,
				"a stock change wakes the idle crafter immediately despite the negative memo");
			checks++;

			// Perf guard: Workshop.tick with an unsatisfiable plan request stays off the recipe lists between changes.
			box.clearContent();
			box.setItem(0, new ItemStack(Items.FLINT, 1));
			box.setChanged();
			var perfData = ref.data();
			var perfSaved = List.copyOf(perfData.buildProjects);
			try {
				perfData.buildProjects.clear();
				perfData.putBuildProject(new de.villagerplus.village.VillageData.BuildProject("granary", at, 0, 0, 0, java.util.Optional.empty(), 1L,
					java.util.Optional.empty(), 0L, List.of("minecraft:spruce_stairs#12"), de.villagerplus.village.VillageData.ProjectHaul.empty(),
					java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false, false));
				perfData.syncPrimaryProjectView();
				JobState shopState = new JobState();
				long shopNow = level.getGameTime() + 3000;
				scansBefore = de.villagerplus.village.RecipeIndex.scans();
				for (int tick = 0; tick < 100; tick++) {
					Ctx shopCtx = new Ctx(level, villager, shopState, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, shopNow + tick);
					Workshop.tick(shopCtx);
				}
				long shopScans = de.villagerplus.village.RecipeIndex.scans() - scansBefore;
				check(shopScans <= 12, "100 idle Workshop.tick calls do not scan the recipe lists per tick (" + shopScans + ")");
				checks++;
				box.setItem(1, new ItemStack(Items.SPRUCE_PLANKS, 18));
				Ctx shopWake = new Ctx(level, villager, shopState, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, shopNow + 101);
				check(Workshop.tick(shopWake) && JobUtil.count(box, stack -> stack.is(Items.SPRUCE_STAIRS)) > 0,
					"a stock change wakes the idle Workshop immediately despite the negative memo");
				checks++;
			} finally {
				perfData.buildProjects.clear();
				for (var saved : perfSaved) perfData.putBuildProject(saved);
				perfData.syncPrimaryProjectView();
			}

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
			stockRawBeef(level, ref, box);
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
			// Cooked food is not a material: JobUtil.insert routes it through StockPolicy.insertVillage to a granary, and with
			// none the public material chest refuses it, so the real cooked item stays in the cook's own inventory (never lost).
			check(cookingRan && JobUtil.count(box, stack -> stack.is(Items.BEEF)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.COOKED_BEEF)) == 0
				&& JobUtil.count(villager.getInventory(), stack -> stack.is(Items.COOKED_BEEF)) == 1,
				"explicit manual cooking consumes raw beef and keeps the cooked beef as a real item with the cook");
			checks++;
			return checks;
		} finally {
			villager.discard();
			for (int slot = 0; slot < originalStock.size(); slot++) box.setItem(slot, originalStock.get(slot));
			box.setChanged();
		}
	}

	/**
	 * The fixture chest is a registered public material store: direct writes of food are refused by StockPolicy
	 * (StockPolicyDirectWriteChecks), so the raw beef is stocked through the controlled pre-granary food route.
	 */
	private static void stockRawBeef(ServerLevel level, de.villagerplus.village.VillageRef ref, Container box) {
		ItemStack rest = de.villagerplus.village.StockPolicy.insertFoodBridge(level, ref, box, new ItemStack(Items.BEEF, 1));
		check(rest.isEmpty() && JobUtil.count(box, stack -> stack.is(Items.BEEF)) == 1,
			"raw beef fixture reaches the public chest through the food route");
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

	/**
	 * R01 sweep over every loaded shaped/shapeless crafting recipe: stock exactly one real item per ingredient slot and require
	 * that the job path (placement info, ingredient assignment, geometry search) finds a matching grid that assembles a
	 * non-empty result and computes its leftover list. Dynamic special recipes (dyeing, banners, fireworks ...) need
	 * component-specific inputs the placement contract cannot express; they are counted, not asserted.
	 */
	private static int sweepAllCraftingRecipes(ServerLevel level) {
		List<ItemStack> candidates = new ArrayList<>();
		for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (item != Items.AIR) candidates.add(new ItemStack(item));
		}
		int swept = 0, skippedNoItem = 0, skippedUnplaceable = 0, special = 0, specialFailed = 0, withRemainder = 0;
		List<String> failures = new ArrayList<>();
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			boolean plain = RecipeCrafting.preview(recipe) != null;
			var placement = recipe.placementInfo();
			var required = placement.ingredients();
			if (placement.isImpossibleToPlace() || required.isEmpty() || required.size() > 9) {
				skippedUnplaceable++;
				continue;
			}
			SimpleContainer stock = new SimpleContainer(54);
			boolean complete = true;
			for (var ingredient : required) {
				ItemStack pick = null;
				for (ItemStack candidate : candidates) {
					if (ingredient.test(candidate)) { pick = candidate; break; }
				}
				if (pick == null) { complete = false; break; }
				stock.addItem(pick.copyWithCount(1));
			}
			if (!complete) { skippedNoItem++; continue; }
			var input = RecipeCrafting.inputFor(recipe, stock, level);
			ItemStack result = null;
			boolean ok = false;
			try {
				ok = input != null && recipe.matches(input, level) && !(result = recipe.assemble(input)).isEmpty()
					&& recipe.getRemainingItems(input) != null;
			} catch (RuntimeException ignored) {
				ok = false;
			}
			if (plain) {
				swept++;
				if (ok && recipe.getRemainingItems(input).stream().anyMatch(stack -> !stack.isEmpty())) withRemainder++;
				if (!ok) failures.add(holder.id().identifier().toString());
			} else {
				special++;
				if (!ok) specialFailed++;
			}
		}
		System.out.println("CRAFT RECIPE SWEEP plain=" + swept + " special=" + special + " specialUnmatched=" + specialFailed
			+ " noItemForIngredient=" + skippedNoItem + " unplaceable=" + skippedUnplaceable + " withLeftoverContainers=" + withRemainder
			+ " failures=" + failures);
		check(swept >= 800 && failures.isEmpty(),
			"every loaded shaped/shapeless crafting recipe (" + swept + " swept) is placeable from real items; failures=" + failures);
		check(withRemainder >= 3, "the sweep found recipes with leftover containers (" + withRemainder + ")");
		return 2;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("CRAFT RECIPE CHECKS: " + message);
		System.out.println("CRAFT RECIPE PASS " + message);
	}
}
