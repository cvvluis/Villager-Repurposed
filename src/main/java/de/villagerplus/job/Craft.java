package de.villagerplus.job;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Herstellen von Waren: Zutaten aus der Dorfkiste nehmen, Ergebnis wieder hineinlegen. */
public final class Craft {
	private Craft() {
	}

	public record Ing(Predicate<ItemStack> test, int count) { }

	public record Recipe(String label, List<Ing> in, Supplier<ItemStack> out, boolean manualFallback) {
		public Recipe(String label, List<Ing> in, Supplier<ItemStack> out) {
			this(label, in, out, false);
		}
	}

	public static Ing of(Item item, int count) {
		return new Ing(s -> s.is(item), count);
	}

	public static Ing of(TagKey<Item> tag, int count) {
		return new Ing(s -> s.is(tag), count);
	}

	public static Ing any(Predicate<ItemStack> test, int count) {
		return new Ing(test, count);
	}

	public static Recipe recipe(String label, Supplier<ItemStack> out, Ing... in) {
		return new Recipe(label, List.of(in), out, false);
	}

	/** Explicitly permits a non-crafting job path after loaded crafting recipes have been tried. */
	public static Recipe manualRecipe(String label, Supplier<ItemStack> out, Ing... in) {
		return new Recipe(label, List.of(in), out, true);
	}

	/** Fuehrt das erste moegliche Rezept aus. true = beschaeftigt (unterwegs zur Kiste oder am Herstellen). */
	public static boolean run(Ctx c, List<Recipe> recipes) {
		BlockPos chest = JobUtil.chest(c);
		Container box = chest == null ? null : JobUtil.box(c);
		if (box == null) {
			return false;
		}
		for (Recipe recipe : recipes) {
			ItemStack expected = recipe.out().get();
			if (!expected.isEmpty() && RecipeCrafting.craft(c, box,
				stack -> ItemStack.isSameItemSameComponents(stack, expected), 64)) return true;
		}
		Recipe chosen = null;
		for (Recipe r : recipes) {
			if (r.manualFallback() && hasInputs(box, r)) {
				chosen = r;
				break;
			}
		}
		if (chosen == null) {
			return false;
		}
		c.setActivity("stellt her: " + chosen.label());
		Anim.hold(c, chosen.out().get().getItem());
		if (!JobUtil.approach(c, chest, 3.0)) {
			return true;
		}
		for (Ing ing : chosen.in()) {
			JobUtil.take(box, ing.test(), ing.count());
		}
		JobUtil.insert(c, box, chosen.out().get());
		JobUtil.swing(c.v());
		c.st().cooldownUntil = c.now() + 100;
		c.mem().jobsDone++;
		return true;
	}

	private static boolean hasInputs(Container box, Recipe recipe) {
		for (Ing ing : recipe.in()) if (JobUtil.count(box, ing.test()) < ing.count()) return false;
		return true;
	}
}
