package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Builder;
import de.villagerplus.village.Lager;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Development checks for real recipe inputs transferred only from a village's public registered storage. */
public final class WorkshopSupplyChecks {
	private WorkshopSupplyChecks() { }

	public static int run(ServerLevel level, VillageRef ref) throws Exception {
		BlockPos at = ref.bell();
		BlockPos publicPos = at.east(8);
		BlockPos privatePos = at.east(10);
		BlockPos lagerCenter = at.east(5);
		Container communal = ref.chest(level);
		if (communal == null) throw new IllegalStateException("Workshop supply checks need a public village chest");
		MapCellSnapshot worldBefore = new MapCellSnapshot(level, List.of(publicPos, privatePos,
			publicPos.below(), privatePos.below(), lagerCenter.below()));
		List<ItemStack> communalBefore = snapshot(communal);
		Optional<BlockPos> oldLager = ref.data().extra.lager;
		List<BlockPos> oldLagerChests = List.copyOf(ref.data().extra.lagerChests);
		List<String> oldWanted = List.copyOf(ref.data().wanted);
		level.setBlock(lagerCenter.below(), Blocks.STONE.defaultBlockState(), 3);
		Villager worker = EntityTypes.VILLAGER.spawn(level, lagerCenter, EntitySpawnReason.COMMAND);
		if (worker == null) {
			worldBefore.restore(level);
			throw new IllegalStateException("Could not create workshop supply worker fixture");
		}
		worker.addTag("vp090_fixture");
		worker.setInvulnerable(true);
		worker.setNoAi(true);
		Ctx context = new Ctx(level, worker, JobManager.state(worker),
			worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
		int passed = 0;
		try {
			level.setBlock(publicPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(publicPos, Blocks.CHEST.defaultBlockState(), 3);
			Container publicLager = HopperBlockEntity.getContainerAt(level, publicPos);
			if (publicLager == null) throw new IllegalStateException("Could not create public warehouse chest fixture");
			ref.data().extra.lager = Optional.of(lagerCenter);
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(publicPos);
			ref.data().wanted.clear();
			ref.data().wanted.add("minecraft:crafting_table#1");
			communal.clearContent();
			publicLager.clearContent();
			publicLager.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
			check(Lager.publicContainerAt(level, publicPos) == publicLager
				&& fetchRecipeInputs(context, ref, communal, Items.CRAFTING_TABLE)
				&& JobUtil.count(communal, stack -> stack.is(Items.OAK_PLANKS)) == 4
				&& JobUtil.count(publicLager, stack -> stack.is(Items.OAK_PLANKS)) == 0,
				"recipe ingredients move as real items from a registered public warehouse into the communal chest");
			passed++;
			boolean crafted = RecipeCrafting.craft(context, communal, (stack) -> stack.is(Items.CRAFTING_TABLE), 1);
			check(crafted && JobUtil.count(communal, stack -> stack.is(Items.CRAFTING_TABLE)) == 1
				&& JobUtil.count(communal, stack -> stack.is(Items.OAK_PLANKS)) == 0,
				"the transferred recipe ingredients are consumed by the loaded crafting-table recipe");
			passed++;

			level.setBlock(privatePos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			BlockEntity privateEntity = level.getBlockEntity(privatePos);
			Container privateLager = HopperBlockEntity.getContainerAt(level, privatePos);
			if (privateEntity == null || privateLager == null) throw new IllegalStateException("Could not create private warehouse fixture");
			privateEntity.setAttached(VillagerPlus.OWNER, worker.getUUID());
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(privatePos);
			communal.clearContent();
			publicLager.clearContent();
			for (int slot = 0; slot < privateLager.getContainerSize(); slot++) {
				privateLager.setItem(slot, new ItemStack(Items.OAK_PLANKS, 64));
			}
			check(Lager.containers(level, ref.data()).isEmpty()
				&& !fetchRecipeInputs(context, ref, communal, Items.CRAFTING_TABLE)
				&& JobUtil.count(privateLager, stack -> stack.is(Items.OAK_PLANKS)) == privateLager.getContainerSize() * 64
				&& JobUtil.count(communal, stack -> !stack.isEmpty()) == 0,
				"recipe input search ignores a full owner-marked private warehouse without changing either inventory");
			passed++;

			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(publicPos);
			communal.clearContent();
			publicLager.clearContent();
			privateLager.clearContent();
			publicLager.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
			for (int slot = 0; slot < communal.getContainerSize(); slot++) communal.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			List<ItemStack> fullBefore = snapshot(communal);
			check(!fetchRecipeInputs(context, ref, communal, Items.CRAFTING_TABLE)
				&& sameContents(communal, fullBefore)
				&& JobUtil.count(publicLager, stack -> stack.is(Items.OAK_PLANKS)) == 4,
				"a full communal chest rejects the complete ingredient transfer without loss at either source or destination");
			passed++;

			communal.clearContent();
			publicLager.clearContent();
			publicLager.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
			List<ItemStack> missingSourceBefore = snapshot(publicLager);
			List<ItemStack> emptyCommunalBefore = snapshot(communal);
			check(!fetchRecipeInputs(context, ref, communal, Items.CRAFTING_TABLE)
				&& sameContents(communal, emptyCommunalBefore)
				&& sameContents(publicLager, missingSourceBefore),
				"incomplete recipe inputs stay untouched when the public warehouse cannot fund a complete batch");
			passed++;

			checkCargoOwnerSelection(level, ref, context);
			passed += 2;
			return passed;
		} finally {
			worker.discard();
			ref.data().extra.lager = oldLager;
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.addAll(oldLagerChests);
			ref.data().wanted.clear();
			ref.data().wanted.addAll(oldWanted);
			worldBefore.restore(level);
			restore(communal, communalBefore);
			ref.dirty();
		}
	}

	private static void checkCargoOwnerSelection(ServerLevel level, VillageRef ref, Ctx context) throws Exception {
		VillageData data = new VillageData();
		VillageRef isolated = new VillageRef(ref.bell(), data, ref.bellEntity());
		UUID missingOwner = UUID.randomUUID();
		BlockPos origin = ref.bell().east(20);
		VillageData.BuildProject noCargo = project(origin, missingOwner, List.of());
		data.buildProjects.add(noCargo);
		VillageData.BuildProject selected = selectProject(context, isolated, data);
		check(selected == noCargo, "an unavailable owner can release an unencumbered project for takeover");

		String cargoRow = UUID.randomUUID() + "|minecraft:oak_planks|1|" + origin.asLong();
		data.buildProjects.set(0, project(origin, missingOwner, List.of(cargoRow)));
		check(selectProject(context, isolated, data) == null,
			"a missing owner does not trigger reassignment while an item-carrying manifest is outstanding");
	}

	private static VillageData.BuildProject project(BlockPos origin, UUID owner, List<String> cargo) {
		return new VillageData.BuildProject(Blueprints.HOUSE, origin, 0, 0, 0, Optional.empty(), 1L,
			Optional.of(owner), 0L, List.of("minecraft:oak_planks#1"),
			new VillageData.ProjectHaul(List.of(), cargo), Optional.empty(), Optional.empty(), Optional.empty(), false, false);
	}

	private static VillageData.BuildProject selectProject(Ctx context, VillageRef ref, VillageData data) throws Exception {
		Method method = Builder.class.getDeclaredMethod("selectProjectForWorker", Ctx.class, VillageRef.class,
			VillageData.class, boolean.class);
		method.setAccessible(true);
		return (VillageData.BuildProject) method.invoke(null, context, ref, data, true);
	}

	private static boolean fetchRecipeInputs(Ctx context, VillageRef ref, Container chest,
		net.minecraft.world.item.Item target) throws Exception {
		Method method = Workshop.class.getDeclaredMethod("fetchRecipeInputsFromLager",
			Ctx.class, VillageRef.class, Container.class, net.minecraft.world.item.Item.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(null, context, ref, chest, target);
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> result = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
		return result;
	}

	private static boolean sameContents(Container container, List<ItemStack> expected) {
		if (container.getContainerSize() != expected.size()) return false;
		for (int slot = 0; slot < expected.size(); slot++) {
			ItemStack actual = container.getItem(slot);
			ItemStack before = expected.get(slot);
			if (actual.isEmpty() != before.isEmpty()) return false;
			if (!actual.isEmpty() && (!ItemStack.isSameItemSameComponents(actual, before)
				|| actual.getCount() != before.getCount())) return false;
		}
		return true;
	}

	private static void restore(Container container, List<ItemStack> contents) {
		for (int slot = 0; slot < contents.size(); slot++) container.setItem(slot, contents.get(slot).copy());
		container.setChanged();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("WORKSHOP SUPPLY CHECK FAILED: " + message);
	}

	private record Cell(BlockState state, CompoundTag data) { }

	private static final class MapCellSnapshot {
		private final java.util.Map<BlockPos, Cell> cells = new java.util.LinkedHashMap<>();
		MapCellSnapshot(ServerLevel level, List<BlockPos> positions) {
			for (BlockPos pos : positions) {
				BlockEntity entity = level.getBlockEntity(pos);
				cells.put(pos, new Cell(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
			}
		}
		void restore(ServerLevel level) {
			for (BlockPos pos : cells.keySet()) level.removeBlockEntity(pos);
			cells.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			cells.forEach((pos, cell) -> {
				if (cell.data() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
		}
	}
}
