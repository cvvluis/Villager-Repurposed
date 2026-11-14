package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Wellbeing;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Focused real-inventory checks for injury treatment and low-satiety resident care. */
public final class HealerCareChecks {
	private HealerCareChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos chestPos = ref.chestPos(level);
		Container chest = ref.chest(level);
		if (chestPos == null || chest == null) throw new IllegalStateException("Healer care checks: public village chest missing");
		List<ItemStack> oldChest = copy(chest);
		var chestEntity = level.getBlockEntity(chestPos);
		UUID oldOwner = chestEntity == null ? null : chestEntity.getAttached(VillagerPlus.OWNER);
		Villager healer = null;
		Villager injured = null;
		Villager hungry = null;
		Villager exhausted = null;
		Villager nextHealer = null;
		int checks = 0;
		try {
			chest.clearContent();
			level.getChunkAt(chestPos.south(4));
			level.getChunkAt(chestPos.north(2));
			healer = spawn(level, chestPos.south(1));
			injured = spawn(level, chestPos.south(3));
			hungry = spawn(level, chestPos.south(4));
			exhausted = spawn(level, chestPos.east(2));
			nextHealer = spawn(level, chestPos.north(2));
			long now = level.getGameTime();

			VillagerMemory injuredMemory = injured.getAttachedOrCreate(VillagerPlus.MEMORY);
			injuredMemory.lastMeal = now;
			float injuredHealth = injured.getMaxHealth() - 4.0F;
			injured.setHealth(injuredHealth);
			healer.getInventory().setItem(0, new ItemStack(Items.BREAD, 2));
			JobState injuredState = new JobState();
			injuredState.targetEntity = injured;
			Ctx injuredContext = context(level, healer, injuredState, ref, now);
			check(new HealerJob().secondary(injuredContext)
				&& injured.getHealth() > injuredHealth
				&& JobUtil.count(healer.getInventory(), stack -> stack.is(Items.BREAD)) == 1,
				"an injured resident still receives one real carried food item and heals"); checks++;
			check(injuredMemory.lastMeal == now,
				"food used for injury treatment also refreshes the resident's meal clock"); checks++;

			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.BREAD, 3));
			healer.getInventory().clearContent();
			VillagerMemory hungryMemory = hungry.getAttachedOrCreate(VillagerPlus.MEMORY);
			hungry.setHealth(hungry.getMaxHealth());
			hungryMemory.lastMeal = now - Config.HUNGER_AFTER / 2L;
			JobState moderateState = new JobState();
			moderateState.targetEntity = hungry;
			check(!new HealerJob().secondary(context(level, healer, moderateState, ref, now))
				&& chest.getItem(0).getCount() == 3,
				"a healthy resident above the low-satiety threshold is not fed"); checks++;

			hungryMemory.lastMeal = oldMealTime(now);
			check(Wellbeing.of(level, hungry, hungryMemory).satiety() < HealerJob.LOW_SATIETY_THRESHOLD,
				"the hungry fixture is genuinely below the explicit satiety threshold"); checks++;
			JobState hungryState = new JobState();
			hungryState.targetEntity = hungry;
			Ctx hungryContext = context(level, healer, hungryState, ref, now);
			check(new HealerJob().secondary(hungryContext)
				&& chest.getItem(0).getCount() == 2
				&& JobUtil.count(healer.getInventory(), stack -> stack.is(Items.BREAD)) == 1
				&& hungryMemory.lastMeal == oldMealTime(now),
				"the healer withdraws exactly one real bread from the communal chest before feeding"); checks++;
			check(new HealerJob().secondary(hungryContext)
				&& chest.getItem(0).getCount() == 2
				&& healer.getInventory().isEmpty()
				&& hungryMemory.lastMeal == now,
				"one carried food item refreshes the healthy resident's meal state"); checks++;

			JobState nextState = new JobState();
			check(!new HealerJob().secondary(context(level, nextHealer, nextState, ref, now))
				&& chest.getItem(0).getCount() == 2,
				"a second healer does not feed the resident again after the meal clock refreshes"); checks++;

			// A healthy, well-fed resident at full energy is not a care target.
			hungryMemory.lastMeal = now;
			hungryMemory.lastSleep = now;
			JobState fullEnergyState = new JobState();
			fullEnergyState.targetEntity = hungry;
			check(Wellbeing.of(level, hungry, hungryMemory).energy() >= HealerJob.LOW_ENERGY_THRESHOLD
				&& !new HealerJob().secondary(context(level, nextHealer, fullEnergyState, ref, now))
				&& chest.getItem(0).getCount() == 2,
				"a healthy resident with full meal satiety and energy is not fed"); checks++;

			// Low energy alone is sufficient for food care even when the meal clock is recent.
			VillagerMemory exhaustedMemory = exhausted.getAttachedOrCreate(VillagerPlus.MEMORY);
			exhaustedMemory.lastMeal = now;
			exhaustedMemory.lastSleep = now - 16_000L;
			check(Wellbeing.of(level, exhausted, exhaustedMemory).satiety() >= HealerJob.LOW_SATIETY_THRESHOLD
				&& Wellbeing.of(level, exhausted, exhaustedMemory).energy() < HealerJob.LOW_ENERGY_THRESHOLD,
				"the exhaustion fixture is healthy and well-fed but below the energy threshold"); checks++;
			JobState exhaustedState = new JobState();
			exhaustedState.targetEntity = exhausted;
			Ctx exhaustedContext = context(level, nextHealer, exhaustedState, ref, now);
			check(new HealerJob().secondary(exhaustedContext)
				&& chest.getItem(0).getCount() == 1
				&& JobUtil.count(nextHealer.getInventory(), stack -> stack.is(Items.BREAD)) == 1,
				"the healer withdraws one real bread for an exhausted healthy resident"); checks++;
			check(new HealerJob().secondary(exhaustedContext)
				&& chest.getItem(0).getCount() == 1
				&& nextHealer.getInventory().isEmpty()
				&& exhaustedMemory.lastMeal == now
				&& Wellbeing.of(level, exhausted, exhaustedMemory).energy() >= HealerJob.LOW_ENERGY_THRESHOLD,
				"food restores the exhausted resident's care state without duplicating or losing the item"); checks++;
			chest.setItem(0, new ItemStack(Items.BREAD, 2));

			// A full inventory leaves the real public stock in place instead of dropping a withdrawn item.
			hungryMemory.lastMeal = oldMealTime(now);
			for (int slot = 0; slot < nextHealer.getInventory().getContainerSize(); slot++) {
				nextHealer.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
			}
			JobState fullInventoryState = new JobState();
			fullInventoryState.targetEntity = hungry;
			check(!new HealerJob().secondary(context(level, nextHealer, fullInventoryState, ref, now))
				&& chest.getItem(0).getCount() == 2,
				"a full healer inventory cannot remove communal food or spill it into the world"); checks++;

			if (chestEntity != null) chestEntity.setAttached(VillagerPlus.OWNER, hungry.getUUID());
			nextHealer.getInventory().clearContent();
			JobState privateChestState = new JobState();
			privateChestState.targetEntity = hungry;
			Ctx privateChestContext = context(level, nextHealer, privateChestState, ref, now);
			check(JobUtil.box(privateChestContext) != chest,
				"a privately owned chest is never exposed as healer communal stock"); checks++;
			return checks;
		} finally {
			if (healer != null) healer.discard();
			if (injured != null) injured.discard();
			if (hungry != null) hungry.discard();
			if (exhausted != null) exhausted.discard();
			if (nextHealer != null) nextHealer.discard();
			if (chestEntity != null) {
				if (oldOwner == null) chestEntity.removeAttached(VillagerPlus.OWNER);
				else chestEntity.setAttached(VillagerPlus.OWNER, oldOwner);
			}
			restore(chest, oldChest);
		}
	}

	private static Villager spawn(ServerLevel level, BlockPos pos) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("Healer care checks: villager fixture failed to spawn");
		villager.addTag("vp_healer_care_fixture");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		return villager;
	}

	private static Ctx context(ServerLevel level, Villager healer, JobState state, VillageRef ref, long now) {
		return new Ctx(level, healer, state, healer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static long oldMealTime(long now) {
		long value = now - Config.HUNGER_AFTER;
		return value == 0 ? -1 : value;
	}

	private static List<ItemStack> copy(Container container) {
		List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
		for (int i = 0; i < container.getContainerSize(); i++) stacks.add(container.getItem(i).copy());
		return stacks;
	}

	private static void restore(Container container, List<ItemStack> stacks) {
		container.clearContent();
		for (int i = 0; i < Math.min(container.getContainerSize(), stacks.size()); i++) {
			container.setItem(i, stacks.get(i).copy());
		}
		container.setChanged();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Healer care checks: " + message);
	}
}
