package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.npc.villager.Villager;

/** Registry/device and source conservation checks for marked thermal appliances. */
public final class ThermalRecipeChecks {
	private ThermalRecipeChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos pos = ref.bell().offset(88, 0, 78);
		level.getChunkAt(pos);
		Cell old = cell(level, pos);
		int checks = 0;
		try {
			level.setBlock(pos, Blocks.BLAST_FURNACE.defaultBlockState(), 3);
			AbstractFurnaceBlockEntity blast = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
			mark(blast);
			ItemStack iron = new ItemStack(Items.RAW_IRON);
			var blasting = SmithSmelting.matchingRecipe(level, iron, RecipeType.BLASTING);
			var smelting = SmithSmelting.matchingRecipe(level, iron, RecipeType.SMELTING);
			check(blasting != null && blasting.assemble(new SingleRecipeInput(iron)).is(Items.IRON_INGOT)
				&& SmithSmelting.recipeType(level.getBlockState(pos)) == RecipeType.BLASTING,
				"blast furnace uses the loaded blasting recipe"); checks++;
			SimpleContainer source = new SimpleContainer(1);
			source.setItem(0, new ItemStack(Items.RAW_IRON, 2));
			check(smelting != null && !SmithSmelting.loadBatchForCheck(level, blast, source, 0, smelting, false)
				&& source.getItem(0).getCount() == 2 && blast.getItem(0).isEmpty(),
				"a recipe for a different appliance leaves source and furnace untouched"); checks++;
			check(SmithSmelting.loadBatchForCheck(level, blast, source, 0, blasting, false)
				&& source.getItem(0).getCount() == 1 && blast.getItem(0).is(Items.RAW_IRON)
				&& blast.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.IRON_INGOT),
				"one real source item enters the marked blast furnace with its output claimed"); checks++;
			check(!SmithSmelting.loadBatchForCheck(level, blast, source, 0, blasting, false)
				&& source.getItem(0).getCount() == 1,
				"an occupied furnace does not withdraw a second source item"); checks++;
			blast.setItem(0, ItemStack.EMPTY);
			blast.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			ItemStack namedIron = new ItemStack(Items.RAW_IRON, 2);
			namedIron.set(DataComponents.CUSTOM_NAME, Component.literal("sortiertes Roheisen"));
			source.setItem(0, namedIron.copy());
			check(SmithSmelting.loadBatchForCheck(level, blast, source, 0, blasting, false)
				&& source.getItem(0).getCount() == 1
				&& source.getItem(0).get(DataComponents.CUSTOM_NAME).equals(namedIron.get(DataComponents.CUSTOM_NAME))
				&& blast.getItem(0).get(DataComponents.CUSTOM_NAME).equals(namedIron.get(DataComponents.CUSTOM_NAME)),
				"component-bearing input keeps its exact component data on the withdrawn and retained stacks"); checks++;
			blast.setItem(0, ItemStack.EMPTY);
			blast.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			ItemStack milkBucket = new ItemStack(Items.MILK_BUCKET);
			check(SmithSmelting.craftingRemainder(milkBucket).is(Items.BUCKET),
				"thermal inputs expose their real item crafting remainder"); checks++;
			SimpleContainer remainderStore = new SimpleContainer(2);
			remainderStore.setItem(0, new ItemStack(Items.RAW_IRON, 2));
			int bucketStackLimit = new ItemStack(Items.BUCKET).getMaxStackSize();
			remainderStore.setItem(1, new ItemStack(Items.BUCKET, bucketStackLimit));
			check(!SmithSmelting.loadBatchWithRemainderForCheck(level, blast, remainderStore, 0, blasting, new ItemStack(Items.BUCKET))
				&& remainderStore.getItem(0).getCount() == 2 && remainderStore.getItem(1).getCount() == bucketStackLimit
				&& blast.getItem(0).isEmpty() && blast.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty(),
				"full remainder storage rolls back the input withdrawal and furnace claim atomically"); checks++;
			remainderStore.setItem(1, new ItemStack(Items.BUCKET, bucketStackLimit - 1));
			check(SmithSmelting.loadBatchWithRemainderForCheck(level, blast, remainderStore, 0, blasting, new ItemStack(Items.BUCKET))
				&& remainderStore.getItem(0).getCount() == 1 && remainderStore.getItem(1).getCount() == bucketStackLimit
				&& blast.getItem(0).is(Items.RAW_IRON)
				&& blast.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.IRON_INGOT),
				"successful thermal load commits exactly one input and its remainder with the output claim"); checks++;

			level.setBlock(pos, Blocks.SMOKER.defaultBlockState(), 3);
			AbstractFurnaceBlockEntity smoker = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
			mark(smoker);
			ItemStack beef = new ItemStack(Items.BEEF);
			var smoking = SmithSmelting.matchingRecipe(level, beef, RecipeType.SMOKING);
			check(smoking != null && smoking.assemble(new SingleRecipeInput(beef)).is(Items.COOKED_BEEF)
				&& SmithSmelting.recipeType(level.getBlockState(pos)) == RecipeType.SMOKING,
				"smoker uses the loaded smoking recipe"); checks++;
			source.setItem(0, new ItemStack(Items.BEEF, 2));
			check(SmithSmelting.loadBatchForCheck(level, smoker, source, 0, smoking, true)
				&& source.getItem(0).getCount() == 1 && smoker.getItem(0).is(Items.BEEF)
				&& smoker.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.COOKED_BEEF),
				"cook claims the loaded food output and withdraws exactly one raw input"); checks++;
			checks += normalWorkerTicks(level, ref, pos);
			return checks;
		} finally {
			restoreCell(level, pos, old);
		}
	}

	/** Exercises the production smith/cook handlers against real marked block entities and village inventories. */
	private static int normalWorkerTicks(ServerLevel level, VillageRef ref, BlockPos furnacePos) {
		BlockPos pantryPos = furnacePos.south(6);
		level.getChunkAt(pantryPos);
		level.getChunkAt(furnacePos.east());
		Cell oldFurnace = cell(level, furnacePos);
		Cell oldPantry = cell(level, pantryPos);
		AbstractCookingRecipe inputRecipe = SmithSmelting.matchingRecipe(level, new ItemStack(Items.RAW_IRON), RecipeType.SMELTING);
		ItemStack outputSample = inputRecipe == null ? ItemStack.EMPTY : inputRecipe.assemble(new SingleRecipeInput(new ItemStack(Items.RAW_IRON)));
		List<Container> outputStores = outputStores(level, ref, outputSample);
		List<Container> storesToRestore = new ArrayList<>(Balance.storage(level, ref));
		storesToRestore.addAll(outputStores);
		List<ContainerSnapshot> stock = snapshot(storesToRestore);
		Map<String, BlockPos> oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		Map<String, Integer> oldStockPolicies = ref.bellEntity().getAttached(VillagerPlus.STOCK_POLICIES);
		Villager resident = null;
		int checks = 0;
		try {
			Container publicChest = ref.chest(level);
			if (publicChest == null || publicChest.getContainerSize() < 3) {
				throw new IllegalStateException("Thermal work-tick fixture needs a public village chest with three slots");
			}
			for (Container store : Balance.storage(level, ref)) {
				for (int slot = 0; slot < store.getContainerSize(); slot++) store.setItem(slot, new ItemStack(Items.DIRT, 64));
				store.setChanged();
			}
			for (Container store : outputStores) {
				for (int slot = 0; slot < store.getContainerSize(); slot++) store.setItem(slot, new ItemStack(Items.DIRT, 64));
				store.setChanged();
			}
			publicChest.setItem(0, new ItemStack(Items.RAW_IRON, 2));
			publicChest.setItem(1, new ItemStack(Items.DIRT, 64));
			publicChest.setChanged();
			Specials.set(ref, Blueprints.KITCHEN, furnacePos);

			level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
			AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnacePos);
			mark(furnace);
			resident = EntityTypes.VILLAGER.spawn(level, furnacePos.east(), EntitySpawnReason.COMMAND);
			if (resident == null) throw new IllegalStateException("Thermal work-tick fixture could not spawn its smith/cook resident");
			resident.addTag("vp090_fixture");
			resident.setNoAi(true);
			resident.setInvulnerable(true);
			resident.setPos(furnacePos.getX() + 1.5, furnacePos.getY(), furnacePos.getZ() + 0.5);

			JobState smithState = new JobState();
			var smithMemory = resident.getAttachedOrCreate(VillagerPlus.MEMORY);
			long now = level.getGameTime() + 10L;
			Ctx smith = new Ctx(level, resident, smithState, smithMemory, ref, now);
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of("minecraft:iron_ingot", 0));
			check(!SmithSmelting.work(smith)
				&& publicChest.getItem(0).getCount() == 2 && furnace.getItem(0).isEmpty()
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty(),
				"smith keeps the recipe input when its loaded output has no remaining village target allowance"); checks++;
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			publicChest.setItem(2, new ItemStack(Items.DIRT, 64));
			publicChest.setChanged();
			check(!SmithSmelting.work(new Ctx(level, resident, smithState, smithMemory, ref, now + 1L))
				&& publicChest.getItem(0).getCount() == 2 && furnace.getItem(0).isEmpty()
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty(),
				"smith keeps the recipe input when all routed output storage is physically full"); checks++;
			if (outputStores.isEmpty()) throw new IllegalStateException("Thermal work-tick fixture has no route for the loaded output");
			Container capacityStore = outputStores.get(0);
			int capacitySlot = capacityStore.getContainerSize() - 1;
			capacityStore.setItem(capacitySlot, ItemStack.EMPTY);
			capacityStore.setChanged();
			check(SmithSmelting.work(smith)
				&& publicChest.getItem(0).getCount() == 1
				&& furnace.getItem(0).is(Items.RAW_IRON)
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.IRON_INGOT),
				"normal smith work tick withdraws a real recipe input and binds the furnace output"); checks++;
			publicChest.setItem(1, new ItemStack(Items.COAL, 2));
			publicChest.setChanged();
			check(SmithSmelting.work(new Ctx(level, resident, smithState, smithMemory, ref, now + 100L))
				&& publicChest.getItem(1).getCount() == 1
				&& furnace.getItem(1).is(Items.COAL)
				&& smithMemory.jobsDone >= 2,
				"normal smith work tick reserves real village fuel for its claimed recipe"); checks++;

			// The same normal handler must distinguish smokers and source cooking inputs from the marked granary.
			level.setBlock(furnacePos, Blocks.SMOKER.defaultBlockState(), 3);
			AbstractFurnaceBlockEntity smoker = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnacePos);
			mark(smoker);
			level.setBlock(pantryPos, Blocks.CHEST.defaultBlockState(), 3);
			Container pantry = (Container) level.getBlockEntity(pantryPos);
			if (pantry == null) throw new IllegalStateException("Thermal work-tick fixture could not create its granary container");
			pantry.setItem(0, new ItemStack(Items.BEEF, 2));
			pantry.setChanged();
			Specials.set(ref, Blueprints.GRANARY, pantryPos);
			JobState cookState = new JobState();
			Ctx cook = new Ctx(level, resident, cookState, smithMemory, ref, now + 200L);
			check(SmithSmelting.cook(cook)
				&& pantry.getItem(0).getCount() == 1
				&& smoker.getItem(0).is(Items.BEEF)
				&& smoker.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.COOKED_BEEF),
				"normal cook work tick withdraws food from the granary and binds the smoked output"); checks++;
			check(SmithSmelting.cook(new Ctx(level, resident, cookState, smithMemory, ref, now + 300L))
				&& publicChest.getItem(1).getCount() == 0
				&& smoker.getItem(1).is(Items.COAL)
				&& smithMemory.jobsDone >= 4,
				"normal cook work tick reserves real village fuel for the claimed smoking recipe"); checks++;
			return checks;
		} finally {
			if (resident != null) resident.discard();
			restore(stock);
			restoreCell(level, furnacePos, oldFurnace);
			restoreCell(level, pantryPos, oldPantry);
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
			else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			if (oldStockPolicies == null) ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
			else ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, oldStockPolicies);
		}
	}

	private static Cell cell(ServerLevel level, BlockPos pos) {
		BlockEntity entity = level.getBlockEntity(pos);
		return new Cell(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
	}

	private static void restoreCell(ServerLevel level, BlockPos pos, Cell cell) {
		level.removeBlockEntity(pos);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(pos, cell.state(), 3);
		if (cell.data() == null) return;
		BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
		if (entity != null) level.setBlockEntity(entity);
	}

	private static List<ContainerSnapshot> snapshot(List<Container> containers) {
		List<ContainerSnapshot> out = new ArrayList<>();
		for (Container container : containers) {
			List<ItemStack> items = new ArrayList<>();
			for (int slot = 0; slot < container.getContainerSize(); slot++) items.add(container.getItem(slot).copy());
			out.add(new ContainerSnapshot(container, items));
		}
		return out;
	}

	private static void restore(List<ContainerSnapshot> snapshots) {
		for (ContainerSnapshot snapshot : snapshots) {
			for (int slot = 0; slot < snapshot.items().size(); slot++) snapshot.container().setItem(slot, snapshot.items().get(slot).copy());
			snapshot.container().setChanged();
		}
	}

	private static List<Container> outputStores(ServerLevel level, VillageRef ref, ItemStack output) {
		String special = Specials.depotType(output);
		if (special != null) return Specials.containers(level, ref, special);
		List<Container> stores = new ArrayList<>();
		Container home = ref.chest(level);
		if (home != null) stores.add(home);
		stores.addAll(Lager.containers(level, ref.data()));
		return stores;
	}

	private record Cell(BlockState state, CompoundTag data) { }
	private record ContainerSnapshot(Container container, List<ItemStack> items) { }

	private static void mark(AbstractFurnaceBlockEntity furnace) {
		furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("THERMAL RECIPE CHECK FAILED: " + message);
	}
}
