package de.villagerplus.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/** Server-wide recipe lists rebuilt when recipes reload; avoids scanning every holder on each villager tick. */
public final class RecipeIndex {
	private static volatile List<RecipeHolder<?>> all = List.of();
	private static volatile List<RecipeHolder<CraftingRecipe>> crafting = List.of();
	private static volatile Map<Item, List<RecipeHolder<?>>> byResult = Map.of();

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
		all = loaded;
		crafting = List.copyOf(craft);
		byResult = Map.copyOf(results);
	}

	public static List<RecipeHolder<?>> all() {
		return all;
	}

	public static List<RecipeHolder<CraftingRecipe>> crafting() {
		return crafting;
	}

	public static Stream<RecipeHolder<?>> ofType(RecipeType<?> type) {
		return all.stream().filter(holder -> holder.value().getType() == type);
	}

	public static List<RecipeHolder<?>> result(Item item) {
		return byResult.getOrDefault(item, List.of());
	}
}