package de.villagerplus.village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/** Server-wide recipe lists rebuilt when recipes reload; avoids scanning every holder on each villager tick. */
public final class RecipeIndex {
	private static volatile List<RecipeHolder<?>> all = List.of();
	private static volatile List<RecipeHolder<CraftingRecipe>> crafting = List.of();
	private static volatile Map<Item, List<RecipeHolder<?>>> byResult = Map.of();
	/** Bumped on every rebuild; caches keyed on recipe content compare it to drop stale entries after a reload. */
	private static volatile int generation;
	/** Previews of plain shaped/shapeless recipes (fixed result), and the remaining ("opaque") crafting recipes. */
	private static volatile IdentityHashMap<CraftingRecipe, ItemStack> previews = new IdentityHashMap<>();
	private static volatile Map<Item, List<RecipeHolder<CraftingRecipe>>> byPreview = Map.of();
	private static volatile List<RecipeHolder<CraftingRecipe>> opaque = List.of();
	private static volatile IdentityHashMap<RecipeHolder<?>, Integer> position = new IdentityHashMap<>();
	private static final Map<Item, List<RecipeHolder<CraftingRecipe>>> candidateCache = new HashMap<>();
	private static final IdentityHashMap<Ingredient, List<RecipeHolder<CraftingRecipe>>> ingredientCache = new IdentityHashMap<>();
	/** Number of full recipe-list iterations requested since start; the in-world guard asserts it stays flat per tick. */
	private static long scans;

	private RecipeIndex() { }

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(RecipeIndex::rebuild);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
			if (success) rebuild(server);
		});
	}

	static void rebuild(MinecraftServer server) {
		ServerLevel level = server.overworld();
		List<RecipeHolder<?>> loaded = List.copyOf(level.recipeAccess().getRecipes());
		List<RecipeHolder<CraftingRecipe>> craft = new ArrayList<>();
		Map<Item, List<RecipeHolder<?>>> results = new HashMap<>();
		for (RecipeHolder<?> holder : loaded) {
			Recipe<?> recipe = holder.value();
			if (recipe instanceof CraftingRecipe cr) {
				@SuppressWarnings("unchecked")
				RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) holder;
				craft.add(typed);
				// Some recipes (for example the vanilla decorated pot recipe) cannot assemble an empty grid and throw.
				// One of them must never take down the server start, so such a recipe simply has no indexed result.
				try {
					var stack = cr.assemble(CraftingInput.EMPTY);
					if (!stack.isEmpty()) {
						results.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>()).add(holder);
					}
				} catch (RuntimeException ignored) {
					// no result known for this recipe
				}
			}
		}
		IdentityHashMap<CraftingRecipe, ItemStack> preview = new IdentityHashMap<>();
		Map<Item, List<RecipeHolder<CraftingRecipe>>> byPrev = new HashMap<>();
		List<RecipeHolder<CraftingRecipe>> opaqueList = new ArrayList<>();
		IdentityHashMap<RecipeHolder<?>, Integer> pos = new IdentityHashMap<>();
		int index = 0;
		for (RecipeHolder<CraftingRecipe> holder : craft) {
			pos.put(holder, index++);
			CraftingRecipe cr = holder.value();
			ItemStack stack = null;
			if (cr.getClass() == ShapedRecipe.class || cr.getClass() == ShapelessRecipe.class) {
				try {
					stack = cr.assemble(CraftingInput.EMPTY);
				} catch (RuntimeException ignored) {
					stack = null;
				}
			}
			if (stack == null) {
				opaqueList.add(holder);
				continue;
			}
			preview.put(cr, stack);
			if (!stack.isEmpty()) byPrev.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>()).add(holder);
		}
		opaque = List.copyOf(opaqueList);
		position = pos;
		byPreview = byPrev;
		synchronized (candidateCache) {
			candidateCache.clear();
			ingredientCache.clear();
		}
		previews = preview;
		all = loaded;
		crafting = List.copyOf(craft);
		byResult = Map.copyOf(results);
		generation++;
	}

	public static int generation() {
		return generation;
	}

	/** Cached fixed result of a plain shaped/shapeless recipe (a copy), or null when the recipe has no fixed preview. */
	public static ItemStack preview(CraftingRecipe recipe) {
		ItemStack cached = previews.get(recipe);
		if (cached != null) return cached.copy();
		if (recipe.getClass() == ShapedRecipe.class || recipe.getClass() == ShapelessRecipe.class) {
			try {
				return recipe.assemble(CraftingInput.EMPTY);
			} catch (RuntimeException ignored) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Crafting recipes that can produce {@code item}: plain recipes whose fixed result is the item plus every recipe
	 * without a fixed preview, in registry order. Replaces a scan of all recipes with a lookup.
	 */
	public static List<RecipeHolder<CraftingRecipe>> producing(Item item) {
		synchronized (candidateCache) {
			return candidateCache.computeIfAbsent(item, i -> merge(byPreview.get(i)));
		}
	}

	/** Crafting recipes whose fixed result is accepted by {@code ingredient} plus every recipe without a fixed preview. */
	public static List<RecipeHolder<CraftingRecipe>> producing(Ingredient ingredient) {
		synchronized (candidateCache) {
			List<RecipeHolder<CraftingRecipe>> cached = ingredientCache.get(ingredient);
			if (cached != null) return cached;
			List<RecipeHolder<CraftingRecipe>> found = new ArrayList<>();
			ingredient.items().forEach(holder -> {
				List<RecipeHolder<CraftingRecipe>> list = byPreview.get(holder.value());
				if (list != null) found.addAll(list);
			});
			List<RecipeHolder<CraftingRecipe>> merged = merge(found);
			ingredientCache.put(ingredient, merged);
			return merged;
		}
	}

	private static List<RecipeHolder<CraftingRecipe>> merge(List<RecipeHolder<CraftingRecipe>> plain) {
		List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
		if (plain != null) out.addAll(plain);
		out.addAll(opaque);
		IdentityHashMap<RecipeHolder<?>, Integer> pos = position;
		out.sort((a, b) -> Integer.compare(pos.getOrDefault(a, 0), pos.getOrDefault(b, 0)));
		return Collections.unmodifiableList(out);
	}

	public static List<RecipeHolder<?>> all() {
		scans++;
		return all;
	}

	public static List<RecipeHolder<CraftingRecipe>> crafting() {
		scans++;
		return crafting;
	}

	public static long scans() {
		return scans;
	}

	public static Stream<RecipeHolder<?>> ofType(RecipeType<?> type) {
		scans++;
		return all.stream().filter(holder -> holder.value().getType() == type);
	}

	public static List<RecipeHolder<?>> result(Item item) {
		return byResult.getOrDefault(item, List.of());
	}
}