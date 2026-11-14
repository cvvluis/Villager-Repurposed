package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Meals;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

/** J02 checks recipe-aware meals, private payment, and remainder storage through the tavern service path. */
public final class TavernMealFlowChecks {
	private int checks;

	private TavernMealFlowChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		if (!FabricLoader.getInstance().isModLoaded("farmersdelight")) {
			throw new IllegalStateException("J02 tavern meal checks require the installed Farmer's Delight mod");
		}
		TavernMealFlowChecks test = new TavernMealFlowChecks();
		level.getChunkAt(at);
		BlockPos granary = at.north(14), dinerPos = at.south(6), householdPos = at.north(6), privateWalletPos = at.west(14);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos pos : new BlockPos[] { at.below(), at, at.east(3).below(), at.east(3),
			granary.below(), granary, dinerPos.below(), dinerPos, dinerPos.above(),
			householdPos.below(), householdPos, householdPos.above(), privateWalletPos.below(), privateWalletPos }) snapshot(level, before, pos);
		Villager diner = null;
		Villager household = null;
		AABB itemArea = new AABB(at).inflate(24);
		java.util.Set<UUID> existingItems = level.getEntitiesOfClass(ItemEntity.class, itemArea).stream()
			.map(ItemEntity::getUUID).collect(java.util.stream.Collectors.toSet());
		try {
			var village = Checks090.fixtureForEquipment(level, at);
			level.setBlock(granary.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(granary, Blocks.CHEST.defaultBlockState(), 3);
			Specials.set(village, Blueprints.GRANARY, granary);
			Container pantry = (Container) level.getBlockEntity(granary);
			if (pantry == null) throw new IllegalStateException("J02 tavern meal fixture did not create a granary container");

			ItemStack recipeMeal = firstFoodPotMealWithoutNameHint(level);
			test.check(!recipeMeal.isEmpty(), "loaded cooking-pot recipes include a food output missed by the prepared-item-name heuristic");
			if (recipeMeal.isEmpty()) return test.checks;
			test.check(Meals.isPreparedFood(level, recipeMeal),
				"a real loaded cooking-pot recipe output is recognized as a prepared meal");
			long now = level.getGameTime();
			level.setBlock(householdPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(householdPos, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(householdPos.above(), Blocks.AIR.defaultBlockState(), 3);
			household = EntityTypes.VILLAGER.spawn(level, householdPos, EntitySpawnReason.COMMAND);
			if (household == null) throw new IllegalStateException("J02 household meal resident did not spawn");
			household.addTag("vp090_fixture");
			household.setNoAi(true);
			household.setInvulnerable(true);
			VillagerMemory householdMemory = household.getAttachedOrCreate(VillagerPlus.MEMORY);
			householdMemory.lastMeal = now - 4000L;
			household.getInventory().setItem(0, new ItemStack(Items.APPLE, 2));
			pantry.setItem(0, recipeMeal.copyWithCount(1));
			pantry.setChanged();
			Ctx householdCtx = new Ctx(level, household, JobManager.state(household), householdMemory, village, now);
			test.check(Meals.eat(householdCtx, null)
				&& JobUtil.count(household.getInventory(), stack -> stack.is(Items.APPLE)) == 2
				&& JobUtil.count(pantry, stack -> ItemStack.isSameItemSameComponents(stack, recipeMeal)) == 0,
				"a household consumes the loaded recipe meal before its ordinary carried food");
			test.check(household.getAttachedOrElse(VillagerPlus.PREPARED_MEAL, 0L) == now,
				"the household recipe meal activates its prepared-meal mood and work bonus");
			household.discard();
			household = null;

			level.setBlock(dinerPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(dinerPos, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(dinerPos.above(), Blocks.AIR.defaultBlockState(), 3);
			diner = EntityTypes.VILLAGER.spawn(level, dinerPos, EntitySpawnReason.COMMAND);
			if (diner == null) throw new IllegalStateException("J02 tavern meal diner did not spawn");
			diner.addTag("vp090_fixture");
			diner.setNoAi(true);
			diner.setInvulnerable(true);
			level.setBlock(privateWalletPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privateWalletPos, Blocks.CHEST.defaultBlockState(), 3);
			Container privateWallet = (Container) level.getBlockEntity(privateWalletPos);
			BlockEntity walletEntity = level.getBlockEntity(privateWalletPos);
			if (privateWallet == null || walletEntity == null) throw new IllegalStateException("J02 private meal wallet fixture did not create a chest");
			walletEntity.setAttached(VillagerPlus.OWNER, diner.getUUID());
			walletEntity.setChanged();
			VillagerMemory memory = diner.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.homeChest = java.util.Optional.of(privateWalletPos);
			memory.lastMeal = now - 4000L;
			diner.setAttached(VillagerPlus.PREPARED_MEAL, 0L);
			fillWalletAndInventory(diner.getInventory());
			pantry.setItem(0, recipeMeal.copyWithCount(1));
			pantry.setChanged();
			Ctx recipeCtx = new Ctx(level, diner, JobManager.state(diner), memory, village, now);
			long revenueBefore = village.bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE, 0L);
			test.check(Tavern.serve(recipeCtx, pantry),
				"the tavern sells a recipe-backed food whose item name does not identify it as a meal");
			test.check(JobUtil.count(diner.getInventory(), stack -> stack.is(Items.EMERALD)) == 63
				&& JobUtil.count(pantry, stack -> ItemStack.isSameItemSameComponents(stack, recipeMeal)) == 0
				&& village.bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE, 0L) == revenueBefore + 1,
				"one private emerald, one real pantry meal, and one tavern revenue unit are conserved together");
			test.check(memory.lastMeal == now && diner.getAttachedOrElse(VillagerPlus.PREPARED_MEAL, 0L) == now,
				"the recipe-backed tavern meal updates satiety and the prepared-meal bonus");

			pantry.clearContent();
			pantry.setItem(0, new ItemStack(Items.MUSHROOM_STEW));
			pantry.setChanged();
			long secondMealAt = now + 4000L;
			memory.lastMeal = now - 4000L;
			diner.getInventory().clearContent();
			for (int slot = 0; slot < diner.getInventory().getContainerSize(); slot++) diner.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 64));
			privateWallet.setItem(0, new ItemStack(Items.EMERALD));
			privateWallet.setChanged();
			Ctx remainderCtx = new Ctx(level, diner, JobManager.state(diner), memory, village, secondMealAt);
			walletEntity.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			walletEntity.setChanged();
			test.check(!Tavern.canAfford(remainderCtx), "emeralds in another resident's chest cannot fund a tavern meal");
			walletEntity.setAttached(VillagerPlus.OWNER, diner.getUUID());
			walletEntity.setChanged();
			test.check(Tavern.canAfford(remainderCtx), "the evening service recognizes a real emerald in the resident's own home chest");
			long secondRevenue = village.bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE, 0L);
			long mealClockBeforeFailure = memory.lastMeal;
			pantry.clearContent();
			pantry.setChanged();
			test.check(!Tavern.serve(remainderCtx, pantry), "the service refuses payment when no prepared meal is actually stocked");
			test.check(JobUtil.count(privateWallet, stack -> stack.is(Items.EMERALD)) == 1
				&& village.bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE, 0L) == secondRevenue
				&& memory.lastMeal == mealClockBeforeFailure,
				"failed service restores the chest emerald to its original slot without changing revenue or hunger");
			pantry.setItem(0, new ItemStack(Items.MUSHROOM_STEW));
			pantry.setChanged();
			test.check(Tavern.serve(remainderCtx, pantry), "a hungry resident can pay from their private home chest for a second prepared meal");
			test.check(JobUtil.count(diner.getInventory(), stack -> stack.is(Items.EMERALD)) == 0
				&& JobUtil.count(privateWallet, stack -> stack.is(Items.EMERALD)) == 0
				&& village.bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE, 0L) == secondRevenue + 1,
				"the second meal removes exactly one private chest emerald and adds one tavern revenue unit");
			boolean bowlKept = JobUtil.count(pantry, stack -> stack.is(Items.BOWL)) == 1;
			boolean bowlDropped = level.getEntitiesOfClass(ItemEntity.class, itemArea,
				item -> !existingItems.contains(item.getUUID()) && item.getItem().is(Items.BOWL)).size() > 0;
			test.check(bowlKept && !bowlDropped,
				"when the villager inventory is full, the stew bowl returns to pantry stock instead of dropping into the world");
			return test.checks;
		} finally {
			if (diner != null) diner.discard();
			if (household != null) household.discard();
			for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, itemArea)) {
				if (!existingItems.contains(item.getUUID())) item.discard();
			}
			restore(level, before);
		}
	}

	private static ItemStack firstFoodPotMealWithoutNameHint(ServerLevel level) {
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CookingPotRecipe recipe) || recipe.getType() != ModRecipeTypes.COOKING.get()) continue;
			ItemStack output = recipe.result().create();
			if (Meals.isFood(output) && !Meals.isPreparedFood(output)) return output;
		}
		return ItemStack.EMPTY;
	}

	private static void fillWalletAndInventory(Container inventory) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, new ItemStack(Items.DIAMOND, 64));
		if (inventory.getContainerSize() > 0) inventory.setItem(0, new ItemStack(Items.EMERALD, 64));
		inventory.setChanged();
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		BlockPos immutable = pos.immutable();
		if (before.containsKey(immutable)) return;
		level.getChunkAt(immutable);
		BlockState state = level.getBlockState(immutable);
		BlockEntity entity = level.getBlockEntity(immutable);
		before.put(immutable, new Cell(state, entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.nbt() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private record Cell(BlockState state, CompoundTag nbt) { }

	private void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("J02 tavern meal: " + label);
		checks++;
	}
}
