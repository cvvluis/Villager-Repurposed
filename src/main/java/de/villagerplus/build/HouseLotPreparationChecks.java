package de.villagerplus.build;

import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import de.villagerplus.village.VillageData;

/** Focused in-world checks for one-at-a-time, drop-preserving house-lot preparation. */
public final class HouseLotPreparationChecks {
	private HouseLotPreparationChecks() { }

	public static int run(ServerLevel level, BlockPos origin, Villager worker) {
		BlockPos root = origin.offset(8, 0, 8);
		BlockPos upper = root.above();
		BlockPos earthTop = root.above(2);
		BlockPos earthHeadroom = root.above(3);
		BlockPos canopy = root.offset(1, 2, 0);
		BlockPos leafA = origin.offset(3, 3, 3);
		BlockPos leafB = origin.offset(4, 3, 3);
		BlockPos playerLeaves = origin.offset(5, 3, 3);
		BlockPos chest = origin.offset(6, 3, 3);
		BlockPos crop = origin.offset(7, 3, 3);
		BlockPos placedLog = origin.offset(8, 3, 3);
		BlockPos soil = root.below();
		BlockPos corridorSupport = origin.offset(9, 0, 0);
		BlockPos corridorLeaf = corridorSupport.above();
		BlockPos corridorRoad = origin.offset(10, 0, 0);
		BlockPos outerPoi = origin.offset(7, 1, 7);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		Container inventory = worker.getInventory();
		ItemStack[] inventoryBefore = new ItemStack[inventory.getContainerSize()];
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventoryBefore[slot] = inventory.getItem(slot).copy();
		int checks = 14;
		Entity earthBlocker = null;
		boolean injectedOuterPoi = false;
		try {
			for (BlockPos pos : new BlockPos[] {root, upper, earthTop, earthHeadroom, canopy, soil, leafA, leafB, playerLeaves, chest, crop, crop.below(), placedLog, placedLog.below(),
				corridorSupport, corridorLeaf, corridorRoad, corridorRoad.above(), corridorRoad.above(2), outerPoi}) {
				require(level.hasChunkAt(pos), "fixture stays in already-loaded chunks");
				snapshot(level, before, pos);
			}
			int expandedHalf = HouseLotPreparation.minimumExpandedLotHalf(7);
			VillageData.PlanBounds expandedBounds = Builder.houseLotReservationBounds(origin, expandedHalf);
			require(expandedHalf == 8 && expandedBounds != null
				&& expandedBounds.minX() == origin.getX() - 11 && expandedBounds.maxX() == origin.getX() + 11
				&& expandedBounds.minZ() == origin.getZ() - 11 && expandedBounds.maxZ() == origin.getZ() + 11
				&& Builder.houseLotWorkHalf(origin, expandedBounds, 7) == expandedHalf,
				"a saved new-house parcel reserves usable space beyond the complete plan footprint and reconstructs the exact work radius"); checks++;
		var yardSteps = Builder.Paths.yardSteps(level, origin, Direction.EAST, Builder.Paths.YARD_FENCE);
		require(yardSteps.size() == 8 && yardSteps.stream().allMatch(step ->
			Math.abs(step.pos().getX() - origin.getX()) <= expandedHalf
				&& Math.abs(step.pos().getZ() - origin.getZ()) <= expandedHalf && step.pos().getZ() != origin.getZ()),
			"the persisted private-garden footprint fits inside the expanded 17x17 work parcel and stays clear of the door approach"); checks++;
			var encodedBounds = VillageData.PlanBounds.CODEC.encodeStart(JsonOps.INSTANCE, expandedBounds).getOrThrow();
			var resumedBounds = VillageData.PlanBounds.CODEC.parse(JsonOps.INSTANCE, encodedBounds).getOrThrow();
			require(expandedBounds.equals(resumedBounds), "the expanded parcel bounds survive project-codec save and reload"); checks++;
			VillageData.BuildProject neighboringClaim = new VillageData.BuildProject(Blueprints.HOUSE, origin.east(10), 0,
				0, 0, java.util.Optional.empty(), 1L, java.util.Optional.empty(), 0L, java.util.List.of(),
				VillageData.ProjectHaul.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
				java.util.Optional.of(Builder.houseLotReservationBounds(origin.east(10), expandedHalf)), false, false);
			int reservationRadius = expandedBounds.maxX() - origin.getX();
			BlockPos separatedOrigin = neighboringClaim.origin().east(2 * reservationRadius + 1);
			require(Builder.houseLotConflicts(expandedBounds, origin, java.util.List.of(neighboringClaim))
				&& !Builder.houseLotConflicts(Builder.houseLotReservationBounds(separatedOrigin, expandedHalf), origin,
					java.util.List.of(neighboringClaim)),
				"expanded parcels respect an overlapping active-project claim and allow a separated lot"); checks++;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, ItemStack.EMPTY);
			inventory.setItem(0, new ItemStack(Items.IRON_AXE));
			inventory.setItem(1, new ItemStack(Items.SHEARS));

			require(HouseLotPreparation.isLevelableNaturalEarth(Blocks.GRASS_BLOCK.defaultBlockState()) && HouseLotPreparation.isLevelableNaturalEarth(Blocks.PODZOL.defaultBlockState()) && !HouseLotPreparation.isLevelableNaturalEarth(Blocks.FARMLAND.defaultBlockState()), "grass and podzol are levelable while farmland stays protected"); checks++;
			level.setBlock(soil, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(root, naturalLeaves(), 3);
			level.setBlock(upper, Blocks.SNOW.defaultBlockState(), 3);
			HouseLotPreparation.Scan snowOnLeaves = HouseLotPreparation.inspect(level, root, upper, 8);
			require(snowOnLeaves.status() == HouseLotPreparation.Status.CLEARABLE && upper.equals(snowOnLeaves.next()),
				"the actual house-lot cursor selects snow before its supporting natural leaves"); checks++;
			level.removeBlock(upper, false);
			level.removeBlock(root, false);
			level.setBlock(soil, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(root, Blocks.OAK_LOG.defaultBlockState(), 3);
			level.setBlock(upper, Blocks.OAK_LOG.defaultBlockState(), 3);
			level.setBlock(canopy, naturalLeaves(), 3);
			HouseLotPreparation.Scan tree = HouseLotPreparation.inspect(level, root, upper, 8);
			require(tree.status() == HouseLotPreparation.Status.CLEARABLE && upper.equals(tree.next()),
				"a rooted tree is accepted and its trunk cursor starts at the highest obstructing log");
			int logsBefore = count(inventory, Items.OAK_LOG);
			HouseLotPreparation.Scan firstLog = HouseLotPreparation.clearOne(level, root, upper, 8,
				new de.villagerplus.job.Ctx(level, worker, new de.villagerplus.job.JobState(), null, null, level.getGameTime()));
			require(firstLog.status() == HouseLotPreparation.Status.CLEARED && level.getBlockState(upper).isAir()
				&& level.getBlockState(root).is(Blocks.OAK_LOG) && count(inventory, Items.OAK_LOG) == logsBefore + 1,
				"one visible trunk block is cleared with the worker's axe and its drop enters their inventory");
			HouseLotPreparation.Scan resumedTree = HouseLotPreparation.inspect(level, root, upper, 8);
			require(resumedTree.status() == HouseLotPreparation.Status.CLEARABLE && root.equals(resumedTree.next()),
				"the next tree cursor is reconstructed from remaining blocks after a restart");

			level.setBlock(leafA, naturalLeaves(), 3);
			level.setBlock(leafB, naturalLeaves(), 3);
			HouseLotPreparation.Scan leaves = HouseLotPreparation.inspect(level, leafA, leafB, 8);
			require(leaves.status() == HouseLotPreparation.Status.CLEARABLE && leafA.equals(leaves.next()),
				"the stable scan order picks one natural leaf block at a time");
			int leavesBefore = count(inventory, Items.OAK_LEAVES);
			HouseLotPreparation.Scan firstLeaf = HouseLotPreparation.clearOne(level, leafA, leafB, 8,
				new de.villagerplus.job.Ctx(level, worker, new de.villagerplus.job.JobState(), null, null, level.getGameTime()));
			require(firstLeaf.status() == HouseLotPreparation.Status.CLEARED && level.getBlockState(leafA).isAir()
				&& !level.getBlockState(leafB).isAir() && count(inventory, Items.OAK_LEAVES) == leavesBefore + 1,
				"one leaf is removed with carried shears and its predicted drop is conserved");

			level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
			HouseLotPreparation.Scan protectedChest = HouseLotPreparation.inspect(level, chest, chest, 8);
			require(protectedChest.status() == HouseLotPreparation.Status.BLOCKED
				&& level.getBlockState(chest).is(Blocks.CHEST), "containers and their inventories are never cleared");
			level.setBlock(playerLeaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
			HouseLotPreparation.Scan protectedLeaves = HouseLotPreparation.inspect(level, playerLeaves, playerLeaves, 8);
			require(protectedLeaves.status() == HouseLotPreparation.Status.BLOCKED
				&& level.getBlockState(playerLeaves).getValue(LeavesBlock.PERSISTENT), "persistent, player-placed leaves stay protected");
			level.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
			level.setBlock(crop, Blocks.WHEAT.defaultBlockState(), 3);
			HouseLotPreparation.Scan protectedCrop = HouseLotPreparation.inspect(level, crop, crop, 8);
			require(protectedCrop.status() == HouseLotPreparation.Status.BLOCKED
				&& level.getBlockState(crop).is(Blocks.WHEAT), "crops and farmland are never cleared");
			level.setBlock(placedLog.below(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			level.setBlock(placedLog, Blocks.OAK_LOG.defaultBlockState(), 3);
			HouseLotPreparation.Scan protectedTimber = HouseLotPreparation.inspect(level, placedLog, placedLog, 8);
			require(protectedTimber.status() == HouseLotPreparation.Status.BLOCKED
				&& level.getBlockState(placedLog).is(Blocks.OAK_LOG), "unrooted placed timber is not mistaken for a tree");
			level.setBlock(root, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(upper, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(earthTop, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(earthHeadroom, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(earthHeadroom, Blocks.SHORT_GRASS.defaultBlockState(), 3);
			HouseLotPreparation.Scan coveredEarth = HouseLotPreparation.inspect(level, root, earthHeadroom, 8);
			require(coveredEarth.status() == HouseLotPreparation.Status.CLEARABLE && earthHeadroom.equals(coveredEarth.next()),
				"vegetation above a natural rise is cleared before any underlying earth"); checks++;
			level.setBlock(earthHeadroom, naturalLeaves(), 3);
			require(HouseLotPreparation.inspect(level, root, earthHeadroom, 8).status() == HouseLotPreparation.Status.CLEARABLE,
				"natural leaves above earth are cleared before leveling"); checks++;
			level.setBlock(earthHeadroom, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
			require(HouseLotPreparation.inspect(level, root, earthHeadroom, 8).status() == HouseLotPreparation.Status.BLOCKED,
				"persistent leaves above earth remain protected"); checks++;
			level.setBlock(earthHeadroom, Blocks.CHEST.defaultBlockState(), 3);
			require(HouseLotPreparation.inspect(level, root, earthHeadroom, 8).status() == HouseLotPreparation.Status.BLOCKED,
				"a protected container above earth still blocks the lot"); checks++;
			level.removeBlock(earthHeadroom, false);
			HouseLotPreparation.Scan earth = HouseLotPreparation.inspect(level, root, earthHeadroom, 8);
			require(earth.status() == HouseLotPreparation.Status.CLEARABLE && earthTop.equals(earth.next()),
				"a three-layer natural earth column exposes only its highest layer as the next safe shovel target"); checks++;
			HouseLotPreparation.Scan noShovel = HouseLotPreparation.clearOne(level, root, earthHeadroom, 8,
				new de.villagerplus.job.Ctx(level, worker, new de.villagerplus.job.JobState(), null, null, level.getGameTime()));
			require(noShovel.status() == HouseLotPreparation.Status.WAITING_FOR_TOOL && level.getBlockState(earthTop).is(Blocks.DIRT),
				"the earth cursor waits for a carried shovel and preserves the selected layer"); checks++;
			inventory.setItem(2, new ItemStack(Items.WOODEN_SHOVEL));
			Villager blocker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (blocker != null) {
				blocker.setNoAi(true);
				blocker.setInvulnerable(true);
				blocker.setPos(earthTop.getX() + 0.5, earthTop.getY() + 1.0, earthTop.getZ() + 0.5);
				if (level.addFreshEntity(blocker)) {
					earthBlocker = blocker;
					boolean indexed = level.getEntitiesOfClass(LivingEntity.class,
						new net.minecraft.world.phys.AABB(earthTop.getX(), earthTop.getY(), earthTop.getZ(),
							earthTop.getX() + 1.0, earthTop.getY() + 2.0, earthTop.getZ() + 1.0), Entity::isAlive).contains(blocker);
					if (indexed) {
						HouseLotPreparation.Scan occupiedEarth = HouseLotPreparation.clearOne(level, root, earthHeadroom, 8,
							new de.villagerplus.job.Ctx(level, worker, new de.villagerplus.job.JobState(), null, null, level.getGameTime()));
						require(occupiedEarth.status() == HouseLotPreparation.Status.WAITING_FOR_ENTITY
							&& level.getBlockState(earthTop).is(Blocks.DIRT),
							"an entity standing above the natural earth layer pauses clearance without removing it"); checks++;
					} else {
						System.out.println("HOUSE LOT CHECK SKIPPED earth-entity assertion: blocker was not queryable in the fixture chunk");
					}
				} else {
					blocker.discard();
					System.out.println("HOUSE LOT CHECK SKIPPED earth-entity assertion: server rejected its blocker entity");
				}
			} else {
				System.out.println("HOUSE LOT CHECK SKIPPED earth-entity assertion: could not create blocker entity");
			}
			if (earthBlocker != null) {
				earthBlocker.discard();
				earthBlocker = null;
			}
			int earthDropsBefore = count(inventory, Items.DIRT);
			HouseLotPreparation.Scan firstEarth = HouseLotPreparation.clearOne(level, root, earthHeadroom, 8,
				new de.villagerplus.job.Ctx(level, worker, new de.villagerplus.job.JobState(), null, null, level.getGameTime()));
			require(firstEarth.status() == HouseLotPreparation.Status.CLEARED && level.getBlockState(earthTop).isAir()
				&& level.getBlockState(upper).is(Blocks.DIRT) && level.getBlockState(root).is(Blocks.DIRT)
				&& count(inventory, Items.DIRT) == earthDropsBefore + 1,
				"one shovel action removes only the exposed earth layer and stores its real dirt drop"); checks++;
			require(HouseLotPreparation.inspect(level, root, earthHeadroom, 8).next().equals(upper),
				"the next layer cursor is recomputed from remaining earth after the first action"); checks++;
			for (int dy = 0; dy < 3; dy++) level.setBlock(root.above(dy), Blocks.DIRT.defaultBlockState(), 3);
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			inventory.setItem(0, new ItemStack(Items.WOODEN_SHOVEL));
			HouseLotPreparation.Scan noDropRoom = HouseLotPreparation.clearOne(level, root, earthHeadroom, 8,
				new de.villagerplus.job.Ctx(level, worker, new de.villagerplus.job.JobState(), null, null, level.getGameTime()));
			require(noDropRoom.status() == HouseLotPreparation.Status.WAITING_FOR_INVENTORY
				&& level.getBlockState(earthTop).is(Blocks.DIRT) && level.getBlockState(upper).is(Blocks.DIRT)
				&& level.getBlockState(root).is(Blocks.DIRT),
				"a full worker inventory pauses shovel work without losing any of the earth layers"); checks++;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, ItemStack.EMPTY);
			inventory.setItem(0, new ItemStack(Items.IRON_AXE));
			inventory.setItem(1, new ItemStack(Items.SHEARS));
			inventory.setItem(2, new ItemStack(Items.WOODEN_SHOVEL));

			BlockPos lotMin = origin.offset(-8, -1, -8);
			BlockPos lotMax = origin.offset(8, 8, 8);
			level.setBlock(corridorSupport, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(corridorRoad, Blocks.DIRT_PATH.defaultBlockState(), 3);
			// The corridor validator intentionally requires two clear blocks above the saved road endpoint.
			level.setBlock(corridorRoad.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(corridorRoad.above(2), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(corridorLeaf, naturalLeaves(), 3);
			HouseLotPreparation.Corridor corridor = HouseLotPreparation.accessCorridor(level, lotMin, lotMax, corridorRoad, 16);
			require(corridor.status() == HouseLotPreparation.Status.READY && corridor.road().equals(corridorRoad),
				"a short approach to a supported road surface is accepted without grading its ground");
			require(!corridor.cells().contains(corridorRoad) && corridor.cells().stream()
				.allMatch(pos -> pos.getX() == corridorSupport.getX() && pos.getZ() == corridorSupport.getZ()),
				"the corridor work mask is one column wide and excludes the saved road");
			int corridorLeavesBefore = count(inventory, Items.OAK_LEAVES);
			HouseLotPreparation.Scan clearedCorridor = HouseLotPreparation.clearOne(level, corridor.cells(), 8,
				new de.villagerplus.job.Ctx(level, worker, new de.villagerplus.job.JobState(), null, null, level.getGameTime()));
			require(clearedCorridor.status() == HouseLotPreparation.Status.CLEARED && level.getBlockState(corridorLeaf).isAir()
				&& level.getBlockState(corridorSupport).is(Blocks.GRASS_BLOCK) && level.getBlockState(corridorRoad).is(Blocks.DIRT_PATH)
				&& count(inventory, Items.OAK_LEAVES) == corridorLeavesBefore + 1,
				"a natural corridor leaf is cleared with its drop conserved while both ground cells stay unchanged");
			level.setBlock(corridorLeaf, Blocks.CHEST.defaultBlockState(), 3);
			HouseLotPreparation.Scan protectedCorridor = HouseLotPreparation.inspect(level, corridor.cells(), 8);
			require(protectedCorridor.status() == HouseLotPreparation.Status.BLOCKED
				&& level.getBlockState(corridorLeaf).is(Blocks.CHEST),
				"a protected container makes corridor preparation fail closed");
			level.removeBlock(corridorLeaf, false);
			level.setBlock(corridorSupport, Blocks.STONE_BRICKS.defaultBlockState(), 3);
			HouseLotPreparation.Corridor unsupportedGround = HouseLotPreparation.accessCorridor(level, lotMin, lotMax, corridorRoad, 16);
			require(unsupportedGround.status() == HouseLotPreparation.Status.BLOCKED
				&& level.getBlockState(corridorSupport).is(Blocks.STONE_BRICKS) && level.getBlockState(corridorRoad).is(Blocks.DIRT_PATH),
				"unsupported corridor ground fails closed and neither the ground nor saved road is moved");
			if (level.getPoiManager().getType(outerPoi).isEmpty()) {
				level.setBlock(outerPoi, naturalLeaves(), 3);
				level.getPoiManager().add(outerPoi, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(
					net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME));
				injectedOuterPoi = true;
			}
			require(outerPoi.getX() - origin.getX() == 7 && outerPoi.getZ() - origin.getZ() == 7
				&& Math.abs(outerPoi.getX() - origin.getX()) <= expandedHalf
				&& Math.abs(outerPoi.getZ() - origin.getZ()) <= expandedHalf
				&& HouseLotPreparation.inspect(level, outerPoi, outerPoi, 8).status() == HouseLotPreparation.Status.BLOCKED,
				"a HOME point of interest at the outer private-lot edge remains protected"); checks++;
			return checks;
		} finally {
			if (earthBlocker != null && earthBlocker.isAlive()) earthBlocker.discard();
			if (injectedOuterPoi) level.getPoiManager().remove(outerPoi);
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			before.forEach((pos, cell) -> {
				if (cell.nbt() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, inventoryBefore[slot]);
		}
	}

	private static BlockState naturalLeaves() {
		return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
	}

	private static int count(Container inventory, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		BlockEntity entity = level.getBlockEntity(pos);
		before.putIfAbsent(pos.immutable(), new Cell(level.getBlockState(pos), entity == null ? null
			: entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("House lot preparation: " + message);
	}

	private record Cell(BlockState state, CompoundTag nbt) { }
}
