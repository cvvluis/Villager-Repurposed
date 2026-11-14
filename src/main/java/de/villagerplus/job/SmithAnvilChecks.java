package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** World checks for the smith's real vanilla-anvil acquisition, craft and placement cycle. */
public final class SmithAnvilChecks {
	private static int passed;

	private SmithAnvilChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		passed = 0;
		BlockPos fixtureChestPos = at.east(3);
		level.getChunk(at);
		level.getChunk(fixtureChestPos);
		BlockState oldBellBlock = level.getBlockState(at);
		BlockState oldBellFloor = level.getBlockState(at.below());
		BlockState oldChestBlock = level.getBlockState(fixtureChestPos);
		BlockState oldChestFloor = level.getBlockState(fixtureChestPos.below());
		BlockEntity oldBellEntity = level.getBlockEntity(at);
		BlockEntity oldChestEntity = level.getBlockEntity(fixtureChestPos);
		var oldNetwork = oldBellEntity == null ? null : oldBellEntity.getAttached(VillagerPlus.NETWORK);
		var oldMarket = oldBellEntity == null ? null : oldBellEntity.getAttached(VillagerPlus.MARKET);
		var oldVillageData = oldBellEntity == null ? null : oldBellEntity.getAttached(VillagerPlus.VILLAGE);
		var oldSpecials = oldBellEntity == null ? null : oldBellEntity.getAttached(VillagerPlus.SPECIALS);
		var oldChestOwner = oldChestEntity == null ? null : oldChestEntity.getAttached(VillagerPlus.OWNER);
		List<ItemStack> oldFixtureChestStock = oldChestEntity instanceof Container container ? snapshot(container) : List.of();
		Map<BlockPos, Cell> oldBlocks = new LinkedHashMap<>();
		List<Entity> spawned = new ArrayList<>();
		VillageRef ref = null;
		BlockPos chestPos = fixtureChestPos;
		Container chest = null;
		BlockPos farTable = fixtureChestPos.south(6);
		BlockPos nearTable = fixtureChestPos.offset(-2, 0, 0);
		List<BlockPos> sites = ring(fixtureChestPos);
		try {
			ref = Checks090.fixtureForEquipment(level, at);
			chestPos = ref.chestPos(level);
			chest = ref.chest(level);
			if (chestPos == null || chest == null) throw new IllegalStateException("B01 anvil fixture has no communal depot");
			if (!chestPos.equals(fixtureChestPos)) throw new IllegalStateException("B01 fixture chest position changed unexpectedly");
			remember(level, oldBlocks, farTable);
			remember(level, oldBlocks, farTable.below());
			remember(level, oldBlocks, nearTable);
			remember(level, oldBlocks, nearTable.below());
			for (BlockPos site : sites) {
				remember(level, oldBlocks, site);
				remember(level, oldBlocks, site.below());
				remember(level, oldBlocks, site.above());
			}
			level.setBlock(farTable.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(farTable, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			for (BlockPos site : sites) {
				level.setBlock(site.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(site, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(site.above(), Blocks.AIR.defaultBlockState(), 3);
			}

			Villager smith = villager(level, farTable, spawned);
			JobState state = JobManager.state(smith);
			var memory = smith.getAttachedOrCreate(VillagerPlus.MEMORY);
			Ctx ctx = new Ctx(level, smith, state, memory, ref, level.getGameTime());

			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.IRON_BLOCK, 3));
			chest.setItem(1, new ItemStack(Items.IRON_INGOT, 3));
			List<ItemStack> shortage = snapshot(chest);
			boolean noShortageMutation = !Workstation.ensure(ctx, Blocks.ANVIL) && same(chest, shortage)
				&& JobUtil.count(chest, stack -> stack.is(Items.ANVIL)) == 0;
			check(noShortageMutation, "B01 missing ingot keeps every real anvil ingredient in the communal depot");

			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.IRON_BLOCK, 3));
			chest.setItem(1, new ItemStack(Items.IRON_INGOT, 4));
			for (BlockPos site : sites) level.setBlock(site, Blocks.STONE.defaultBlockState(), 3);
			List<ItemStack> blocked = snapshot(chest);
			check(!Workstation.ensure(new Ctx(level, smith, state, memory, ref, level.getGameTime() + 1), Blocks.ANVIL)
				&& same(chest, blocked) && JobUtil.count(chest, stack -> stack.is(Items.ANVIL)) == 0,
				"B01 blocked placement area leaves all three iron blocks and four ingots untouched");
			for (BlockPos site : sites) level.setBlock(site, Blocks.AIR.defaultBlockState(), 3);

			// A completely occupied depot cannot accept the output. The crafting transaction must not take any input.
			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.IRON_BLOCK, 64));
			chest.setItem(1, new ItemStack(Items.IRON_INGOT, 64));
			for (int slot = 2; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			List<ItemStack> full = snapshot(chest);
			check(!Workstation.ensure(new Ctx(level, smith, state, memory, ref, level.getGameTime() + 2), Blocks.ANVIL)
				&& same(chest, full), "B01 full communal depot keeps the iron recipe ingredients and creates no untracked anvil");

			// Put a table at a reachable, valid site edge. The smith itself runs the same production call as in normal work.
			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.IRON_INGOT, 31));
			level.setBlock(farTable, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(nearTable.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(nearTable, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			// Keep the first two candidates blocked so the first placeable site is adjacent to this workbench.
			level.setBlock(chestPos.offset(-2, 0, -2), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(chestPos.offset(-2, 0, -1), Blocks.STONE.defaultBlockState(), 3);
			smith.discard();
			Villager placingSmith = villager(level, nearTable.east(), spawned);
			JobState placingState = JobManager.state(placingSmith);
			var placingMemory = placingSmith.getAttachedOrCreate(VillagerPlus.MEMORY);
			boolean ironBlockChain = true;
			for (int blocks = 1; blocks <= 3; blocks++) {
				boolean madeBlock = Workstation.ensure(new Ctx(level, placingSmith, placingState, placingMemory,
					ref, level.getGameTime() + blocks * 101L), Blocks.ANVIL);
				ironBlockChain = ironBlockChain && madeBlock
					&& JobUtil.count(chest, stack -> stack.is(Items.IRON_BLOCK)) == blocks
					&& JobUtil.count(chest, stack -> stack.is(Items.IRON_INGOT)) == 31 - blocks * 9
					&& JobUtil.count(chest, stack -> stack.is(Items.ANVIL)) == 0;
			}
			check(ironBlockChain, "B01 three loaded iron-block crafts convert only 27 real ingots and retain four for the anvil");
			boolean crafted = Workstation.ensure(new Ctx(level, placingSmith, placingState, placingMemory,
				ref, level.getGameTime() + 404), Blocks.ANVIL);
			check(crafted && JobUtil.count(chest, stack -> stack.is(Items.ANVIL)) == 1
				&& JobUtil.count(chest, stack -> stack.is(Items.IRON_BLOCK)) == 0
				&& JobUtil.count(chest, stack -> stack.is(Items.IRON_INGOT)) == 0,
				"B01 loaded vanilla recipe consumes exactly three iron blocks and four ingots for one anvil"
					+ " [crafted=" + crafted + ", anvil=" + JobUtil.count(chest, stack -> stack.is(Items.ANVIL))
					+ ", blocks=" + JobUtil.count(chest, stack -> stack.is(Items.IRON_BLOCK))
					+ ", ingots=" + JobUtil.count(chest, stack -> stack.is(Items.IRON_INGOT))
					+ ", activity=" + placingState.activity + ", smith=" + placingSmith.blockPosition()
					+ ", table=" + nearTable + ", recipeIds=" + level.recipeAccess().getRecipes().stream()
						.filter(holder -> holder.id().identifier().getPath().contains("anvil"))
						.map(holder -> holder.id().toString()).toList() + "]");
			boolean placed = Workstation.ensure(new Ctx(level, placingSmith, placingState, placingMemory,
				ref, level.getGameTime() + 505), Blocks.ANVIL);
			BlockPos anvilPos = Workstation.find(level, chestPos, Blocks.ANVIL, 8);
			check(placed && anvilPos != null && JobUtil.count(chest, stack -> stack.is(Items.ANVIL)) == 0,
				"B01 smith places its crafted anvil on a supported clear spot and removes exactly one depot result");
			verifyAdditionalSmithWorkstations(level, ref, chest, chestPos, nearTable, spawned, oldBlocks);
			System.out.println("SMITH ANVIL PASS checks=" + passed + " anvil=" + anvilPos);
			return passed;
		} finally {
			spawned.forEach(Entity::discard);
			restore(level, oldBlocks);
			level.setBlock(fixtureChestPos.below(), oldChestFloor, 3);
			level.setBlock(at.below(), oldBellFloor, 3);
			level.setBlock(fixtureChestPos, oldChestBlock, 3);
			level.setBlock(at, oldBellBlock, 3);
			BlockEntity restoredBell = level.getBlockEntity(at);
			if (restoredBell != null) {
				restore(restoredBell, VillagerPlus.NETWORK, oldNetwork);
				restore(restoredBell, VillagerPlus.MARKET, oldMarket);
				restore(restoredBell, VillagerPlus.VILLAGE, oldVillageData);
				restore(restoredBell, VillagerPlus.SPECIALS, oldSpecials);
			}
			BlockEntity restoredChestEntity = level.getBlockEntity(fixtureChestPos);
			if (restoredChestEntity != null) restore(restoredChestEntity, VillagerPlus.OWNER, oldChestOwner);
			if (restoredChestEntity instanceof Container restoredChest && !oldFixtureChestStock.isEmpty()) {
				for (int slot = 0; slot < Math.min(restoredChest.getContainerSize(), oldFixtureChestStock.size()); slot++) {
					restoredChest.setItem(slot, oldFixtureChestStock.get(slot));
				}
				restoredChest.setChanged();
			}
		}
	}

	private static void verifyAdditionalSmithWorkstations(ServerLevel level, VillageRef ref, Container chest,
			BlockPos chestPos, BlockPos nearTable, List<Entity> spawned, Map<BlockPos, Cell> oldBlocks) {
		BlockPos blast = chestPos.offset(2, 0, 2);
		remember(level, oldBlocks, blast);
		level.setBlock(blast, Blocks.BLAST_FURNACE.defaultBlockState(), 3);

		CraftingRecipe brewingRecipe = recipeFor(level, Items.BREWING_STAND);
		check(brewingRecipe != null, "B01 loaded recipe manager supplies the vanilla brewing stand recipe");
		if (brewingRecipe == null) return;
		chest.clearContent();
		check(seedRecipeIngredients(chest, brewingRecipe, level),
			"B01 stages the actual loaded brewing stand recipe ingredients from communal stock");
		var brewingInput = RecipeCrafting.inputFor(brewingRecipe, chest, level);
		int brewingOutputCount = brewingInput == null ? 0 : brewingRecipe.assemble(brewingInput).getCount();
		Villager smith = villager(level, nearTable.east(), spawned);
		JobState state = JobManager.state(smith);
		var memory = smith.getAttachedOrCreate(VillagerPlus.MEMORY);
		boolean progressed = false;
		BlockPos brewingPos = null;
		for (int step = 0; step < 6 && brewingPos == null; step++) {
			Ctx context = new Ctx(level, smith, state, memory, ref, level.getGameTime() + 1200 + step * 100L);
			progressed |= SmithJob.ensureRoleWorkstations(context);
			brewingPos = Workstation.find(level, chestPos, Blocks.BREWING_STAND, 8);
		}
		check(progressed && brewingPos != null && brewingOutputCount > 0
			&& JobUtil.count(chest, stack -> stack.is(Items.BREWING_STAND)) == brewingOutputCount - 1,
			"B01 normal smith work crafts and places a brewing stand from the real communal recipe and materials");

		if (!FabricLoader.getInstance().isModLoaded("farmersdelight")) {
			System.out.println("B01 optional kitchen station checks skipped: Farmer's Delight is not loaded");
			return;
		}
		BlockPos kitchen = chestPos.offset(12, 0, 0);
		BlockPos table = kitchen.west(5);
		BlockPos smithFeet = kitchen.west(3);
		level.getChunk(kitchen);
		level.getChunk(table);
		level.getChunk(smithFeet);
		remember(level, oldBlocks, table);
		remember(level, oldBlocks, table.below());
		remember(level, oldBlocks, table.above());
		level.setBlock(table.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
		List<BlockPos> kitchenSites = ring(kitchen);
		for (BlockPos site : kitchenSites) {
			level.getChunk(site);
			remember(level, oldBlocks, site);
			remember(level, oldBlocks, site.below());
			remember(level, oldBlocks, site.above());
			level.setBlock(site.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(site, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(site.above(), Blocks.AIR.defaultBlockState(), 3);
		}
		Specials.set(ref, Blueprints.KITCHEN, kitchen);
		Villager kitchenSmith = villager(level, smithFeet, spawned);
		JobState kitchenState = JobManager.state(kitchenSmith);
		var kitchenMemory = kitchenSmith.getAttachedOrCreate(VillagerPlus.MEMORY);
		int testedKitchenStations = 0;
		for (String id : List.of(Blueprints.FARMERS_DELIGHT_COOKING_POT, Blueprints.FARMERS_DELIGHT_CUTTING_BOARD)) {
			Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
			if (block == null || block.asItem() == Items.AIR) {
				System.out.println("B01 optional kitchen station skipped: block is not registered: " + id);
				continue;
			}
			CraftingRecipe recipe = recipeFor(level, block.asItem());
			if (recipe == null) {
				System.out.println("B01 optional kitchen station skipped: no loaded crafting recipe: " + id);
				continue;
			}
			chest.clearContent();
			check(seedRecipeIngredients(chest, recipe, level), "B01 stages the loaded recipe for " + id);
			var stationInput = RecipeCrafting.inputFor(recipe, chest, level);
			int stationOutputCount = stationInput == null ? 0 : recipe.assemble(stationInput).getCount();
			List<ItemStack> stationInputs = snapshot(chest);
			Map<BlockPos, BlockState> blockedStates = new LinkedHashMap<>();
			for (BlockPos site : kitchenSites) {
				if (!level.getBlockState(site).isAir()) continue;
				blockedStates.put(site.immutable(), level.getBlockState(site));
				blockedStates.put(site.above().immutable(), level.getBlockState(site.above()));
				level.setBlock(site, Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(site.above(), Blocks.STONE.defaultBlockState(), 3);
			}
			Ctx blockedContext = new Ctx(level, kitchenSmith, kitchenState, kitchenMemory, ref, level.getGameTime() + 2000);
			boolean blockedWithoutLoss = !Workstation.ensure(blockedContext, block, kitchen) && same(chest, stationInputs);
			check(blockedWithoutLoss, "B01 blocked kitchen placement preserves every real ingredient for " + id);
			for (Map.Entry<BlockPos, BlockState> entry : blockedStates.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 3);
			}
			boolean stationProgressed = false;
			BlockPos stationPos = null;
			for (int step = 0; step < 6 && stationPos == null; step++) {
				Ctx context = new Ctx(level, kitchenSmith, kitchenState, kitchenMemory, ref,
					level.getGameTime() + 2100 + step * 100L);
				stationProgressed |= SmithJob.ensureRoleWorkstations(context);
				stationPos = Workstation.find(level, kitchen, block, 8);
			}
			check(stationProgressed && stationPos != null && stationOutputCount > 0
				&& JobUtil.count(chest, stack -> stack.is(block.asItem())) == stationOutputCount - 1,
				"B01 smith crafts the registered kitchen appliance at the marked kitchen from real recipe ingredients: " + id);
			testedKitchenStations++;
		}
		if (testedKitchenStations == 0) {
			System.out.println("B01 optional kitchen station checks skipped: no loaded Farmer's Delight station recipe");
		}
	}

	private static CraftingRecipe recipeFor(ServerLevel level, Item result) {
		for (var holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			SimpleContainer candidate = new SimpleContainer(36);
			if (!seedRecipeIngredients(candidate, recipe, level)) continue;
			var input = RecipeCrafting.inputFor(recipe, candidate, level);
			if (input != null && recipe.assemble(input).is(result)) return recipe;
		}
		return null;
	}

	private static boolean seedRecipeIngredients(Container chest, CraftingRecipe recipe, ServerLevel level) {
		var placement = recipe.placementInfo();
		if (placement.isImpossibleToPlace() || placement.ingredients().isEmpty()) return false;
		chest.clearContent();
		for (Ingredient ingredient : placement.ingredients()) {
			boolean inserted = false;
			for (var holder : ingredient.items().toList()) {
				ItemStack one = holder.value().getDefaultInstance();
				if (one.isEmpty()) continue;
				one.setCount(1);
				for (int slot = 0; slot < chest.getContainerSize(); slot++) {
					ItemStack existing = chest.getItem(slot);
					if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, one)
						&& existing.getCount() < existing.getMaxStackSize() && chest.canPlaceItem(slot, one)) {
						existing.grow(1);
						inserted = true;
						break;
					}
				}
				if (!inserted) for (int slot = 0; slot < chest.getContainerSize(); slot++) {
					if (chest.getItem(slot).isEmpty() && chest.canPlaceItem(slot, one)) {
						chest.setItem(slot, one);
						inserted = true;
						break;
					}
				}
				if (inserted) break;
			}
			if (!inserted) return false;
		}
		chest.setChanged();
		return RecipeCrafting.inputFor(recipe, chest, level) != null;
	}
	private static Villager villager(ServerLevel level, BlockPos pos, List<Entity> spawned) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("B01 anvil fixture could not spawn its smith");
		villager.addTag("vp090_fixture");
		villager.setInvulnerable(true);
		villager.setNoAi(false);
		spawned.add(villager);
		return villager;
	}

	private static List<BlockPos> ring(BlockPos center) {
		List<BlockPos> result = new ArrayList<>();
		for (int radius = 2; radius <= 4; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) == radius) result.add(center.offset(dx, 0, dz));
			}
		}
		return result;
	}

	private static void remember(ServerLevel level, Map<BlockPos, Cell> snapshot, BlockPos pos) {
		BlockPos immutable = pos.immutable();
		if (snapshot.containsKey(immutable)) return;
		level.getChunkAt(immutable);
		BlockEntity entity = level.getBlockEntity(immutable);
		snapshot.put(immutable, new Cell(level.getBlockState(immutable),
			entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> snapshot) {
		for (BlockPos pos : snapshot.keySet()) level.removeBlockEntity(pos);
		for (Map.Entry<BlockPos, Cell> entry : snapshot.entrySet()) level.setBlock(entry.getKey(), entry.getValue().state(), 3);
		for (Map.Entry<BlockPos, Cell> entry : snapshot.entrySet()) {
			if (entry.getValue().data() == null) continue;
			BlockEntity entity = BlockEntity.loadStatic(entry.getKey(), entry.getValue().state(),
				entry.getValue().data().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		}
	}

	private record Cell(BlockState state, CompoundTag data) { }
	private static <T> void restore(BlockEntity entity, net.fabricmc.fabric.api.attachment.v1.AttachmentType<T> type, T value) {
		if (value == null) entity.removeAttached(type);
		else entity.setAttached(type, value);
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> items = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) items.add(container.getItem(slot).copy());
		return items;
	}

	private static boolean same(Container container, List<ItemStack> items) {
		if (container.getContainerSize() != items.size()) return false;
		for (int slot = 0; slot < items.size(); slot++) {
			ItemStack expected = items.get(slot);
			ItemStack actual = container.getItem(slot);
			if (expected.isEmpty() != actual.isEmpty()) return false;
			if (!expected.isEmpty() && (!ItemStack.isSameItemSameComponents(expected, actual) || expected.getCount() != actual.getCount())) return false;
		}
		return true;
	}

	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("SMITH ANVIL CHECK FAILED: " + label);
		passed++;
		System.out.println("SMITH ANVIL PASS " + label);
	}
}
