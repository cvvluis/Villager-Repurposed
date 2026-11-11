package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Mahlzeiten: Dorfbewohner essen morgens und abends zu Hause und mittags am Dorfplatz. */
public final class Meals {
	private static final Set<Item> EXTRA_FOOD = Set.of(
		Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_COD,
		Items.COOKED_SALMON, Items.BAKED_POTATO, Items.APPLE, Items.MELON_SLICE, Items.SWEET_BERRIES, Items.COOKIE,
		Items.PUMPKIN_PIE, Items.DRIED_KELP);

	private Meals() {
	}

	public static boolean isFood(ItemStack s) {
		return !s.isEmpty() && (s.has(DataComponents.FOOD) || Villager.FOOD_POINTS.containsKey(s.getItem()) || EXTRA_FOOD.contains(s.getItem()));
	}

	/** Prepared meals that the cook can produce and a tavern may serve. */
	public static boolean isPreparedFood(ItemStack s) {
		if (!isFood(s)) return false;
		String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().toLowerCase(java.util.Locale.ROOT);
		return s.is(Items.BAKED_POTATO) || path.startsWith("cooked_") || path.contains("bread") || path.contains("soup") || path.contains("stew")
			|| path.contains("pie") || path.contains("cookie") || path.contains("cake") || path.contains("meal")
			|| path.contains("feast") || path.contains("salad") || path.contains("sandwich") || path.contains("roast")
			|| path.contains("pudding") || path.contains("casserole");
	}

	/**
	 * Recognizes food made by an actually loaded cooking-pot recipe, including addon dishes whose item name
	 * does not say "soup", "stew", or "meal" (for example, Farmer's Delight pasta).
	 */
	public static boolean isPreparedFood(ServerLevel level, ItemStack stack) {
		if (isPreparedFood(stack)) return true;
		if (stack.isEmpty() || !isFood(stack)
			|| !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("farmersdelight")) return false;
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof vectorwing.farmersdelight.common.crafting.CookingPotRecipe recipe)
				|| recipe.getType() != vectorwing.farmersdelight.common.registry.ModRecipeTypes.COOKING.get()) continue;
			ItemStack output = recipe.result().create();
			if (isFood(output) && ItemStack.isSameItemSameComponents(output, stack)) return true;
		}
		return false;
	}

	/** Isst eine Portion aus dem eigenen Inventar oder, falls vorhanden, aus {@code source}. Nur wenn wieder Hunger da ist. */
	public static boolean eat(Ctx c, Container source) {
		if (onCooldown(c)) return false;

		// Give meals made by the cook a real household route even when the resident
		// still carries ordinary food or has it in the local home chest.
		if (eatFirst(c, c.v().getInventory(), s -> isPreparedFood(c.level(), s))) return true;
		if (eatFirst(c, source, s -> isPreparedFood(c.level(), s))) return true;
		if (eatFromGranary(c, s -> isPreparedFood(c.level(), s))) return true;

		// Keep the usual food fallback for villages without a prepared meal.
		if (eatFirst(c, c.v().getInventory(), Meals::isFood)) return true;
		if (eatFirst(c, source, Meals::isFood)) return true;
		return eatFromGranary(c, Meals::isFood);
	}

	/** Eat only from the specified stock source; used by services where inventory food is not what is being purchased. */
	public static boolean eatFrom(Ctx c, Container source, Predicate<ItemStack> filter) {
		if (source == null || onCooldown(c)) return false;
		return eatFirst(c, source, s -> isFood(s) && filter.test(s));
	}

	private static boolean onCooldown(Ctx c) {
		long last = c.mem().lastMeal;
		return last != 0 && c.now() - last < 3000L;
	}

	private static boolean eatFromGranary(Ctx c, Predicate<ItemStack> filter) {
		if (c.village() == null) return false;
		for (Container granary : de.villagerplus.village.Specials.containers(
			c.level(), c.village(), de.villagerplus.build.Blueprints.GRANARY)) {
			if (eatFirst(c, granary, filter)) return true;
		}
		return false;
	}

	private static boolean eatFirst(Ctx c, Container source, Predicate<ItemStack> filter) {
		if (source == null || onCooldown(c)) return false;
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			ItemStack present = source.getItem(slot);
			if (present.isEmpty() || !filter.test(present)) continue;
			ItemStack before = present.copy();
			ItemStack eaten = source.removeItem(slot, 1);
			if (eaten.isEmpty()) return false;
			source.setChanged();
			if (finishMeal(c, new MealPortion(eaten, source))) return true;
			source.setItem(slot, before);
			source.setChanged();
		}
		return false;
	}

	private static boolean finishMeal(Ctx c, MealPortion portion) {
		ItemStack eaten = portion.food();
		if (eaten != null) {
			var useRemainder = eaten.get(DataComponents.USE_REMAINDER);
			var craftingRemainder = eaten.getItem().getCraftingRemainder();
			ItemStack remainder = useRemainder != null ? useRemainder.convertInto().create()
				: craftingRemainder == null ? ItemStack.EMPTY : craftingRemainder.create();
			if (!remainder.isEmpty() && !storeRemainder(c.v().getInventory(), portion.source(), remainder)) return false;
			Anim.hold(c, eaten.getItem());
			Anim.eatParticles(c.level(), c.v(), eaten.getItem());
		}
		if (eaten != null && isPreparedFood(c.level(), eaten)) c.v().setAttached(de.villagerplus.VillagerPlus.PREPARED_MEAL, c.now());
		c.v().heal(2.0F);
		c.level().playSound(null, c.v().blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().lastMeal = c.now();
		return true;
	}

	/** Keeps meal containers in real inventories; a full resident inventory falls back to the source the meal left. */
	private static boolean storeRemainder(Container inventory, Container source, ItemStack remainder) {
		if (storeIn(inventory, remainder)) return true;
		return source != inventory && storeIn(source, remainder);
	}

	private static boolean storeIn(Container container, ItemStack remainder) {
		if (!canStore(container, remainder)) return false;
		ItemStack[] before = new ItemStack[container.getContainerSize()];
		for (int slot = 0; slot < before.length; slot++) before[slot] = container.getItem(slot).copy();
		if (insert(container, remainder.copy())) return true;
		for (int slot = 0; slot < before.length; slot++) container.setItem(slot, before[slot]);
		container.setChanged();
		return false;
	}

	private static boolean canStore(Container container, ItemStack moving) {
		if (container == null) return false;
		int room = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (!container.canPlaceItem(slot, moving)) continue;
			ItemStack present = container.getItem(slot);
			if (present.isEmpty()) room += Math.min(moving.getMaxStackSize(), container.getMaxStackSize());
			else if (ItemStack.isSameItemSameComponents(present, moving)) {
				room += Math.max(0, Math.min(present.getMaxStackSize(), container.getMaxStackSize()) - present.getCount());
			}
			if (room >= moving.getCount()) return true;
		}
		return false;
	}

	private static boolean insert(Container container, ItemStack moving) {
		for (int slot = 0; slot < container.getContainerSize() && !moving.isEmpty(); slot++) {
			ItemStack present = container.getItem(slot);
			if (present.isEmpty() || !container.canPlaceItem(slot, moving)
				|| !ItemStack.isSameItemSameComponents(present, moving)) continue;
			int room = Math.max(0, Math.min(present.getMaxStackSize(), container.getMaxStackSize()) - present.getCount());
			int moved = Math.min(room, moving.getCount());
			if (moved > 0) {
				present.grow(moved);
				moving.shrink(moved);
			}
		}
		for (int slot = 0; slot < container.getContainerSize() && !moving.isEmpty(); slot++) {
			if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, moving)) continue;
			int moved = Math.min(moving.getCount(), Math.min(moving.getMaxStackSize(), container.getMaxStackSize()));
			container.setItem(slot, moving.copyWithCount(moved));
			moving.shrink(moved);
		}
		container.setChanged();
		return moving.isEmpty();
	}

	private record MealPortion(ItemStack food, Container source) { }

	/** Lange nichts gegessen? Dann arbeitet der Dorfbewohner nur noch halb so schnell. */
	public static boolean hungry(Ctx c) {
		long last = c.mem().lastMeal;
		if (last == 0) {
			c.mem().lastMeal = c.now();
			return false;
		}
		return c.now() - last > Config.HUNGER_AFTER;
	}
}
