package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.VillageRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fixture checks for the P4 food chain (m11 hunger): remote field harvest, the pre-granary food bridge and the farm-plan
 * priority of a pen without a site. Synthetic fixtures; not evidence for a natural village.
 */
public final class FoodChainChecks {
	private FoodChainChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		int checks = 0;
		level.getChunkAt(at);
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager carrier = null;
		Map<String, Integer> oldPolicies = null;
		VillageRef ref = null;
		try {
			ref = Checks090.fixtureForEquipment(level, at);
			oldPolicies = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of());
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			// ---- remote field cells and the nearest ripe crop
			BlockPos ripe = at.east(60), young = at.east(61), far = at.east(110), bare = at.east(62);
			for (BlockPos p : new BlockPos[] {ripe, young, far, bare}) level.getChunkAt(p);
			plant(level, before, ripe, 7);
			plant(level, before, young, 2);
			plant(level, before, far, 7);
			remember(level, before, bare.below());
			remember(level, before, bare);
			level.setBlock(bare.below(), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(bare, Blocks.WHEAT.defaultBlockState(), 3);
			FarmerJob.clearCropScans();
			List<BlockPos> cells = FarmerJob.villageCropCells(level, ref);
			check(cells.contains(ripe) && cells.contains(young)
				&& !cells.contains(far) && !cells.contains(bare),
				"the village crop scan lists planted field cells inside the scan radius, not far-away or unsoiled crops"); checks++;
			check(ripe.equals(FarmerJob.nearestRipeVillageCrop(level, ref, at, List.of())),
				"a farmer without job site or work memory finds the ripe generated-field crop 60 blocks away, not the unripe one"); checks++;
			level.setBlock(ripe, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0), 3);
			check(FarmerJob.nearestRipeVillageCrop(level, ref, at, List.of()) == null,
				"a harvested (replanted age 0) crop is not offered again until it ripens"); checks++;
			check(!de.villagerplus.build.Builder.penSiteRetryPending(ref, level.getGameTime())
				&& !de.villagerplus.build.Builder.penSiteRetryPending(null, level.getGameTime()),
				"a village without a failed pen search has no pen-site retry pending"); checks++;

			check(FarmerJob.keepHarvesting(true, 1, true) && FarmerJob.keepHarvesting(true, 5, true)
				&& !FarmerJob.keepHarvesting(true, 6, true) && !FarmerJob.keepHarvesting(true, 3, false)
				&& !FarmerJob.keepHarvesting(false, 3, true) && !FarmerJob.keepHarvesting(true, 0, true),
				"a deficit farmer keeps harvesting a small load while ripe crops remain and delivers a full batch or the last load"); checks++;
			net.minecraft.world.SimpleContainer woodStock = new net.minecraft.world.SimpleContainer(9);
			check(FarmerJob.woodSignature(List.of(woodStock)) == 0, "an empty depot has no fence wood"); checks++;
			woodStock.setItem(0, new ItemStack(Items.OAK_LOG, 3));
			woodStock.setItem(1, new ItemStack(Items.SPRUCE_PLANKS, 5));
			woodStock.setItem(2, new ItemStack(Items.STICK, 2));
			woodStock.setItem(3, new ItemStack(Items.OAK_FENCE, 4));
			woodStock.setItem(4, new ItemStack(Items.COBBLESTONE, 40));
			check(FarmerJob.woodSignature(List.of(woodStock)) == 14, "the fence-wood signature counts logs, planks, sticks and fences only"); checks++;

			// ---- pre-granary food bridge
			BlockPos chestPos = ref.chestPos(level);
			Container chest = HopperBlockEntity.getContainerAt(level, chestPos);
			chest.clearContent();
			check(HopperBlockEntity.addItem(null, chest, new ItemStack(Items.BREAD, 1), null).getCount() == 1,
				"direct hopper insertion of food stays refused at the registered public chest"); checks++;
			ItemStack nonFood = StockPolicy.insertFoodBridge(level, ref, chest, new ItemStack(Items.OAK_LOG, 4));
			check(nonFood.getCount() == 4 && JobUtil.count(chest, s -> s.is(Items.OAK_LOG)) == 0,
				"the food bridge never accepts construction material"); checks++;
			ItemStack bridged = StockPolicy.insertFoodBridge(level, ref, chest, new ItemStack(Items.BREAD, 4));
			check(bridged.isEmpty() && JobUtil.count(chest, s -> s.is(Items.BREAD)) == 4,
				"the food bridge moves a real food stack into the public chest"); checks++;
			check(HopperBlockEntity.addItem(null, chest, new ItemStack(Items.BREAD, 1), null).getCount() == 1,
				"the bridge closes again: direct food insertion is still refused afterwards"); checks++;
			ItemStack capped = StockPolicy.insertFoodBridge(level, ref, chest, new ItemStack(Items.BREAD, 64));
			check(capped.getCount() == 20 && JobUtil.count(chest, s -> s.is(Items.BREAD)) == 48
				&& StockPolicy.foodBridgeRoom(level, ref) == 0,
				"the food bridge stops at its per-village cap and returns the remainder"); checks++;
			chest.clearContent();
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of("minecraft:bread", 2));
			ItemStack policy = StockPolicy.insertFoodBridge(level, ref, chest, new ItemStack(Items.BREAD, 4));
			check(policy.getCount() == 2 && JobUtil.count(chest, s -> s.is(Items.BREAD)) == 2,
				"a saved stock-policy target limits the food bridge"); checks++;
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			chest.clearContent();

			// ---- the real delivery path (JobUtil.depositSurplus) of a carrier
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos floor = chestPos.offset(dx, -1, dz);
				remember(level, before, floor);
				remember(level, before, floor.above());
				remember(level, before, floor.above(2));
				level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			carrier = EntityTypes.VILLAGER.spawn(level, chestPos.south(2), EntitySpawnReason.COMMAND);
			if (carrier == null) throw new IllegalStateException("food chain carrier did not spawn");
			carrier.addTag("vp090_fixture");
			carrier.setNoAi(true);
			carrier.setInvulnerable(true);
			VillagerMemory memory = carrier.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.name = "FoodCarrier";
			carrier.getInventory().setItem(0, new ItemStack(Items.BREAD, 5));
			carrier.getInventory().setItem(1, new ItemStack(Items.CARROT, 3));
			boolean delivered = deliver(level, ref, carrier, memory, level.getGameTime() + 10);
			check(delivered,
				"a farmer's harvest reaches the public chest through the real delivery path while no granary exists");
			checks++;
			check(JobUtil.count(carrier.getInventory(), Meals::isFood) == 0
				&& JobUtil.count(chest, s -> s.is(Items.BREAD)) == 5 && JobUtil.count(chest, s -> s.is(Items.CARROT)) == 3,
				"every carried food unit arrives as the same real stack (no food created or lost)"); checks++;

			// a registered granary with room still wins over the chest
			BlockPos granary = at.north(14);
			level.getChunkAt(granary);
			remember(level, before, granary.below());
			remember(level, before, granary);
			level.setBlock(granary.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(granary, Blocks.CHEST.defaultBlockState(), 3);
			Specials.set(ref, Blueprints.GRANARY, granary);
			Container pantry = HopperBlockEntity.getContainerAt(level, granary);
			pantry.clearContent();
			chest.clearContent();
			carrier.getInventory().setItem(0, new ItemStack(Items.BREAD, 5));
			carrier.getInventory().setItem(1, ItemStack.EMPTY);
			BlockPos granaryStand = granary.south(2);
			carrier.setPos(granaryStand.getX() + 0.5, granaryStand.getY(), granaryStand.getZ() + 0.5);
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos floor = granary.offset(dx, -1, dz);
				remember(level, before, floor);
				remember(level, before, floor.above());
				remember(level, before, floor.above(2));
				level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			deliver(level, ref, carrier, memory, level.getGameTime() + 5000);
			check(JobUtil.count(pantry, s -> s.is(Items.BREAD)) == 5 && JobUtil.count(chest, s -> s.is(Items.BREAD)) == 0,
				"a registered granary with room still receives the food and the public chest stays free of it"); checks++;

			// a full granary overflows into the bridge instead of leaving the food in the farmer's pocket
			for (int slot = 0; slot < pantry.getContainerSize(); slot++) pantry.setItem(slot, new ItemStack(Items.WHEAT, 64));
			carrier.getInventory().setItem(0, new ItemStack(Items.BREAD, 5));
			carrier.setPos(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 2.5);
			deliver(level, ref, carrier, memory, level.getGameTime() + 10000);
			check(JobUtil.count(chest, s -> s.is(Items.BREAD)) == 5 && JobUtil.count(carrier.getInventory(), s -> s.is(Items.BREAD)) == 0,
				"a full granary overflows into the capped public food bridge"); checks++;
			return checks;
		} finally {
			if (carrier != null) carrier.discard();
			if (ref != null) {
				Specials.remove(ref, Blueprints.GRANARY);
				ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, oldPolicies == null ? Map.of() : oldPolicies);
				Container chest = HopperBlockEntity.getContainerAt(level, ref.chestPos(level));
				if (chest != null) chest.clearContent();
			}
			FarmerJob.clearCropScans();
			before.forEach((pos, state) -> level.setBlock(pos, state, 3));
		}
	}

	/** Runs the delivery until the carrier holds no food or 60 attempts passed; the carrier is set on each stand the way a walk would. */
	private static boolean deliver(ServerLevel level, VillageRef ref, Villager carrier, VillagerMemory memory, long start) {
		JobState state = JobManager.state(carrier);
		state.clearTarget();
		state.depositBlockedUntil = 0;
		state.cooldownUntil = 0;
		state.walkStart = 0;
		for (int attempt = 0; attempt < 60; attempt++) {
			Ctx ctx = new Ctx(level, carrier, state, memory, ref, start + attempt);
			state.cooldownUntil = 0;
			boolean busy = JobUtil.depositSurplus(ctx, Meals::isFood, 1, 0);
			if (JobUtil.count(carrier.getInventory(), Meals::isFood) == 0) return true;
			if (state.target != null) carrier.setPos(state.target.getX() + 0.5, state.target.getY(), state.target.getZ() + 0.5);
			if (!busy && state.depositBlockedUntil > ctx.now()) return false;
		}
		return JobUtil.count(carrier.getInventory(), Meals::isFood) == 0;
	}

	private static void plant(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos crop, int age) {
		remember(level, before, crop.below());
		remember(level, before, crop);
		level.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
		level.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age), 3);
	}

	private static void remember(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos pos) {
		before.putIfAbsent(pos.immutable(), level.getBlockState(pos));
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("FOOD CHAIN CHECK FAILED: " + message);
	}
}
