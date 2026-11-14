package de.villagerplus.job;

import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Werkstatt: Wenn dem Dorf fuer einen Bau ein bestimmtes Material fehlt ({@code VillageData.wanted}), versucht der
 * Handwerker, es mit den tatsächlich geladenen Rezepten herzustellen. Fehlende Rezept-Zwischenprodukte können in
 * späteren Arbeitstakten ebenfalls nur über geladene Rezepte entstehen; unbekannte Namensähnlichkeiten gelten nicht als Rezept.
 * Nicht herstellbares (Bruchstein, Sandstein ...) muss abgebaut werden ({@link Mining}).
 */
public final class Workshop {
	private Workshop() {
	}

	/** true = beschaeftigt. */
	public static boolean tick(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null) return false;
		BlockPos chest = JobUtil.chest(c);
		Container box = chest == null ? null : JobUtil.box(c);
		if (box == null) {
			return false;
		}
		if (ref.data().wanted.isEmpty()) return SmithSmelting.workPlanStone(c, ref, box);
		if (fetchFromLager(c, ref, box)) {
			return true;
		}
		if (ref.data().hasPlan() && PlanStoneSupply.craftRequestedBricks(c.level(), ref.data(), box, false)) {
			if (!JobUtil.approachLong(c, chest, 3.0)) return true;
			if (PlanStoneSupply.craftRequestedBricks(c.level(), ref.data(), box, true)) {
				c.setActivity("stellt Steinziegel für den Bauplan her");
				Anim.hold(c, Items.STONE_BRICKS);
				c.st().cooldownUntil = c.now() + 100;
				c.mem().jobsDone++;
				return true;
			}
		}
		if (ref.data().hasPlan() && SmithSmelting.workPlanStone(c, ref, box)) {
			return true;
		}
		for (String entry : new ArrayList<>(ref.data().wanted)) {
			String[] parts = entry.split("#");
			Optional<Item> target = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));
			if (target.isEmpty()) {
				continue;
			}
			int wanted = parseInt(parts.length > 1 ? parts[1] : "1");
			int missing = wanted - JobUtil.count(box, s -> s.is(target.get()));
			if (missing <= 0) {
				continue;
			}
			if (RecipeCrafting.craft(c, box, stack -> stack.is(target.get()), wanted)) {
				return true;
			}
			if (fetchRecipeInputsFromLager(c, ref, box, target.get())) return true;
			if (RecipeCrafting.craftPrerequisite(c, box, chest, target.get())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Move only the exact missing inputs for one loaded recipe from registered public lager chests into the communal
	 * chest. RecipeCrafting operates on the communal chest, so ingredients that exist only in a warehouse otherwise
	 * remain invisible to both target crafting and its prerequisite chain.
	 */
	private static boolean fetchRecipeInputsFromLager(Ctx c, VillageRef ref, Container chest, Item target) {
		if (ref.data().extra.lager.isEmpty()) return false;
		List<Container> stores = de.villagerplus.village.Lager.containers(c.level(), ref.data()).stream()
			.filter(store -> store != chest).distinct().toList();
		if (stores.isEmpty()) return false;
		Container combined = de.villagerplus.village.Lager.combined(c.level(), ref);
		if (combined == null) return false;

		for (RecipeHolder<?> holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			CraftingInput input = RecipeCrafting.inputFor(recipe, combined, c.level());
			if (input == null) continue;
			ItemStack output = recipe.assemble(input);
			if (output.isEmpty() || !output.is(target) || JobUtil.count(chest, stack -> stack.is(target)) >= targetDemand(ref, target)) continue;

			List<StackNeed> needs = new ArrayList<>();
			for (int slot = 0; slot < input.size(); slot++) {
				ItemStack ingredient = input.getItem(slot);
				if (ingredient.isEmpty()) continue;
				int found = -1;
				for (int i = 0; i < needs.size(); i++) {
					if (ItemStack.isSameItemSameComponents(needs.get(i).sample(), ingredient)) { found = i; break; }
				}
				if (found < 0) needs.add(new StackNeed(ingredient.copyWithCount(1), 1));
				else {
					StackNeed prior = needs.get(found);
					needs.set(found, new StackNeed(prior.sample(), prior.count() + 1));
				}
			}

			List<Withdrawal> withdrawals = new ArrayList<>();
			Map<StoreSlot, Integer> reserved = new LinkedHashMap<>();
			boolean complete = true;
			for (StackNeed need : needs) {
				int missing = need.count() - JobUtil.count(chest,
					stack -> ItemStack.isSameItemSameComponents(stack, need.sample()));
				for (Container store : stores) {
					for (int slot = 0; slot < store.getContainerSize() && missing > 0; slot++) {
						ItemStack stack = store.getItem(slot);
						if (!ItemStack.isSameItemSameComponents(stack, need.sample())) continue;
						StoreSlot key = new StoreSlot(store, slot);
						int available = stack.getCount() - reserved.getOrDefault(key, 0);
						int amount = Math.min(missing, available);
						if (amount <= 0) continue;
						reserved.merge(key, amount, Integer::sum);
						withdrawals.add(new Withdrawal(store, slot, need.sample(), amount));
						missing -= amount;
					}
				}
				if (missing > 0) { complete = false; break; }
			}
			if (!complete || withdrawals.isEmpty()) continue;

			SimpleContainer preview = copy(chest);
			for (Withdrawal withdrawal : withdrawals) {
				ItemStack remainder = HopperBlockEntity.addItem(null, preview,
					withdrawal.sample().copyWithCount(withdrawal.count()), null);
				if (!remainder.isEmpty()) { complete = false; break; }
			}
			if (!complete) continue;
			CraftingInput stagedInput = RecipeCrafting.inputFor(recipe, preview, c.level());
			if (stagedInput == null || !recipe.assemble(stagedInput).is(target)
				|| !RecipeCrafting.commit(copy(preview), stagedInput, recipe.assemble(stagedInput), recipe.getRemainingItems(stagedInput))) continue;
			c.setActivity("holt Zutaten für den Bauplan aus dem öffentlichen Lager");
			if (!JobUtil.approach(c, ref.data().extra.lager.get(), 4.5)) return true;
			if (!applyWithdrawals(chest, withdrawals)) continue;
			c.st().cooldownUntil = c.now() + 60;
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	private static int targetDemand(VillageRef ref, Item target) {
		String id = BuiltInRegistries.ITEM.getKey(target).toString();
		int requested = 0;
		for (String entry : ref.data().wanted) {
			String[] fields = entry.split("#", 2);
			if (!fields[0].equals(id)) continue;
			try { requested = (int)Math.min(Integer.MAX_VALUE, (long)requested + (fields.length == 2 ? Integer.parseInt(fields[1]) : 1)); }
			catch (NumberFormatException ignored) { return 0; }
		}
		return requested;
	}

	private static SimpleContainer copy(Container source) {
		SimpleContainer copy = new SimpleContainer(source.getContainerSize());
		for (int slot = 0; slot < source.getContainerSize(); slot++) copy.setItem(slot, source.getItem(slot).copy());
		return copy;
	}

	private static boolean applyWithdrawals(Container chest, List<Withdrawal> withdrawals) {
		Map<Container, List<ItemStack>> before = new IdentityHashMap<>();
		before.put(chest, snapshot(chest));
		for (Withdrawal withdrawal : withdrawals) before.computeIfAbsent(withdrawal.store(), Workshop::snapshot);
		for (Withdrawal withdrawal : withdrawals) {
			ItemStack source = withdrawal.store().getItem(withdrawal.slot());
			if (!ItemStack.isSameItemSameComponents(source, withdrawal.sample()) || source.getCount() < withdrawal.count()) {
				restore(before);
				return false;
			}
			ItemStack paid = withdrawal.store().removeItem(withdrawal.slot(), withdrawal.count());
			if (paid.getCount() != withdrawal.count()
				|| !HopperBlockEntity.addItem(null, chest, paid, null).isEmpty()) {
				restore(before);
				return false;
			}
		}
		before.keySet().forEach(Container::setChanged);
		return true;
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> result = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
		return result;
	}

	private static void restore(Map<Container, List<ItemStack>> snapshots) {
		for (Map.Entry<Container, List<ItemStack>> entry : snapshots.entrySet()) {
			Container container = entry.getKey();
			List<ItemStack> contents = entry.getValue();
			for (int slot = 0; slot < contents.size(); slot++) container.setItem(slot, contents.get(slot).copy());
			container.setChanged();
		}
	}

	private record StackNeed(ItemStack sample, int count) { }
	private record StoreSlot(Container container, int slot) { }
	private record Withdrawal(Container store, int slot, ItemStack sample, int count) { }

	/**
	 * Fehlt der Dorfkiste etwas aus der Wunschliste, das im Lagerschuppen liegt, holt der Handwerker es dort ab (fuer Bretter
	 * auch Staemme). Nur wenn in der Dorfkiste Platz ist.
	 */
	private static boolean fetchFromLager(Ctx c, VillageRef ref, Container box) {
		VillageData d = ref.data();
		if (d.extra.lager.isEmpty()) {
			return false;
		}
		List<Container> lagers = de.villagerplus.village.Lager.containers(c.level(), d);
		Container allStock = de.villagerplus.village.Lager.combined(c.level(), ref);
		boolean planStoneChain = PlanStoneSupply.needsFurnace(c.level(), d, allStock == null ? box : allStock);
		for (String entry : new ArrayList<>(d.wanted)) {
			String[] parts = entry.split("#");
			Optional<Item> target = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));
			if (target.isEmpty()) {
				continue;
			}
			int missing = parseInt(parts.length > 1 ? parts[1] : "1") - JobUtil.count(box, s -> s.is(target.get()));
			if (missing <= 0) {
				continue;
			}
			boolean planks = parts[0].endsWith("_planks");
			boolean stoneBricks = target.get() == Items.STONE_BRICKS
				&& PlanStoneSupply.supports(c.level(), true, false);
			boolean planCobble = planStoneChain;
			if (stoneBricks) {
				int requestedStone = 0;
				for (String request : d.wanted) {
					String[] fields = request.split("#", 2);
					if (fields[0].equals("minecraft:stone"))
						requestedStone = Math.max(requestedStone, parseInt(fields.length > 1 ? fields[1] : "1"));
				}
				missing -= Math.max(0, JobUtil.count(box, s -> s.is(Items.STONE)) - requestedStone);
				if (missing <= 0) continue;
			}
			java.util.Set<String> requestedLogs = planks ? Woodcutting.demandedLogIds(d.wanted) : java.util.Set.of();
			Predicate<ItemStack> match = s -> s.is(target.get()) || (planks && Woodcutting.matchesRequestedLog(s, requestedLogs))
				|| (stoneBricks && s.is(Items.STONE)) || (planCobble && s.is(Items.COBBLESTONE));
			int inLager = 0;
			for (Container l : lagers) {
				inLager += JobUtil.count(l, match);
			}
			if (inLager == 0) {
				continue;
			}
			c.setActivity("holt Material aus dem Lager");
			if (!JobUtil.approach(c, d.extra.lager.get(), 4.5)) {
				return true;
			}
			// Dorfkiste voll: erst einen Platz frei raeumen (unwichtige Ware ins Lager), nichts wird weggeworfen.
			if (!de.villagerplus.village.Lager.freeSlot(c.level(), d, box, s -> {
				String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
				return d.wanted.stream().anyMatch(w -> w.startsWith(id + "#")) || s.is(Items.EMERALD) || de.villagerplus.build.Builder.planNeeds(d, s);
			})) {
				continue;
			}
			int left = Math.min(Math.max(missing, planks || planCobble ? 16 : 1), 4096);
			for (Container l : lagers) {
				for (int i = 0; i < l.getContainerSize() && left > 0; i++) {
					ItemStack stack = l.getItem(i);
					if (stack.isEmpty() || !match.test(stack)) {
						continue;
					}
					ItemStack part = stack.copyWithCount(Math.min(left, stack.getCount()));
					int before = part.getCount();
					ItemStack rest = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, box, part, null);
					int moved = before - rest.getCount();
					stack.shrink(moved);
					l.setChanged();
					left -= moved;
					if (!rest.isEmpty()) {
						left = 0;
					}
				}
			}
			JobUtil.swing(c.v());
			c.st().cooldownUntil = c.now() + 60;
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

}
