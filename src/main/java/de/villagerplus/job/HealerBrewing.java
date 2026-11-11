package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/**
 * Heiler beschickt einen echten Braustand mit Flaschen, Netherwarze, Goldener Melone und Lohenstaub.
 * Der Sud selbst läuft über den Vanilla-Braustand; fertige Heiltränke wandern ins Inventar.
 */
public final class HealerBrewing {
	public static final int INGREDIENT = 3;
	public static final int FUEL = 4;

	private HealerBrewing() { }

	public static boolean work(Ctx c) {
		if (c.village() == null) return false;
		if (Workstation.ensure(c, Blocks.BREWING_STAND)) return true;
		BlockPos origin = c.village().chestPos(c.level());
		if (origin == null) origin = c.village().bell();
		BlockPos standPos = Workstation.find(c.level(), origin, Blocks.BREWING_STAND, 8);
		if (standPos == null || !(c.level().getBlockEntity(standPos) instanceof BrewingStandBlockEntity stand)) return false;
		Container chest = communal(c);
		if (chest == null) return false;
		c.setActivity("braut am Braustand");
		Anim.hold(c, Items.BREWING_STAND);
		if (!JobUtil.approach(c, standPos, 3.0)) return true;
		if (moveFinishedPotion(c, stand)) return true;
		if (stand.getItem(FUEL).isEmpty() && takeInto(chest, stand, FUEL, stack -> stack.is(Items.BLAZE_POWDER))) return placed(c, standPos);
		int bottle = emptyBottleSlot(stand);
		if (bottle >= 0 && takeInto(chest, stand, bottle, HealerBrewing::waterOrAwkward)) return placed(c, standPos);
		String stage = bottleStage(stand);
		if (stand.getItem(INGREDIENT).isEmpty() && "water".equals(stage)
			&& takeInto(chest, stand, INGREDIENT, stack -> stack.is(Items.NETHER_WART))) return placed(c, standPos);
		if (stand.getItem(INGREDIENT).isEmpty() && "awkward".equals(stage)
			&& takeInto(chest, stand, INGREDIENT, stack -> stack.is(Items.GLISTERING_MELON_SLICE))) return placed(c, standPos);
		return false;
	}

	public static void tickStand(ServerLevel level, BlockPos pos, int times) {
		if (!(level.getBlockEntity(pos) instanceof BrewingStandBlockEntity stand)) return;
		var state = level.getBlockState(pos);
		for (int i = 0; i < times; i++) BrewingStandBlockEntity.serverTick(level, pos, state, stand);
	}

	public static String potionPath(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "";
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null || contents.potion().isEmpty()) return stack.is(Items.POTION) ? "water" : "";
		return contents.potion().flatMap(holder -> holder.unwrapKey()).map(key -> key.identifier().getPath()).orElse("");
	}

	private static boolean moveFinishedPotion(Ctx c, Container stand) {
		for (int slot = 0; slot < 3; slot++) {
			ItemStack stack = stand.getItem(slot);
			if (!HealerJob.isHealingPotion(stack)) continue;
			ItemStack taken = stand.removeItem(slot, 1);
			JobUtil.give(c.level(), c.v(), taken);
			c.setActivity("nimmt den Heiltrank aus dem Braustand");
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			return true;
		}
		return false;
	}

	private static int emptyBottleSlot(Container stand) {
		for (int slot = 0; slot < 3; slot++) if (stand.getItem(slot).isEmpty()) return slot;
		return -1;
	}

	private static String bottleStage(Container stand) {
		for (int slot = 0; slot < 3; slot++) {
			String path = potionPath(stand.getItem(slot));
			if (!path.isEmpty()) return path;
		}
		return "";
	}

	private static boolean waterOrAwkward(ItemStack stack) {
		String path = potionPath(stack);
		return stack.is(Items.POTION) && ("water".equals(path) || "awkward".equals(path));
	}

	private static boolean takeInto(Container chest, Container stand, int slot, java.util.function.Predicate<ItemStack> match) {
		if (!stand.getItem(slot).isEmpty()) return false;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (stack.isEmpty() || !match.test(stack)) continue;
			ItemStack one = chest.removeItem(i, 1);
			stand.setItem(slot, one);
			return true;
		}
		return false;
	}

	private static boolean placed(Ctx c, BlockPos standPos) {
		c.level().sendBlockUpdated(standPos, c.level().getBlockState(standPos), c.level().getBlockState(standPos), 3);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null) return null;
		var entity = c.level().getBlockEntity(pos);
		if (entity != null && entity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}
}
