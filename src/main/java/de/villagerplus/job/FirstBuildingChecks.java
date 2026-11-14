package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.village.VillageRef;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Synthetic checks for the seed-foraging changes that let a village fund its first field (batch delivery, re-search, far meadows). */
public final class FirstBuildingChecks {
	private FirstBuildingChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos column = ref.bell().offset(30, 0, 0);
		level.getChunkAt(column);
		BlockPos grass = new BlockPos(column.getX(), level.getHeight(
			net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager worker = null;
		int oldFarms = ref.data().farms;
		int checks = 0;
		try {
			set(level, before, grass.below(), Blocks.GRASS_BLOCK.defaultBlockState());
			set(level, before, grass, Blocks.SHORT_GRASS.defaultBlockState());
			set(level, before, grass.above(), Blocks.AIR.defaultBlockState());
			require(FarmerJob.safeSeedGrass(level, ref, grass) && !FarmerJob.nearbyHomePoi(level, grass), "the fixture grass is a safe seed source"); checks++;

			worker = EntityTypes.VILLAGER.spawn(level, grass.offset(1, 0, 0), EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("FIRST BUILDING CHECK FAILED: could not spawn the forager fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.setPos(grass.getX() + 1.5, grass.getY(), grass.getZ() + 0.5);
			JobState state = new JobState();
			VillagerMemory memory = worker.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY);
			Ctx context = new Ctx(level, worker, state, memory, ref, level.getGameTime() + 1L);

			// 1. A plant that just yielded nothing is searched again while it is the only one nearby.
			BlockPos first = FarmerJob.findSeedGrass(context, ref);
			require(grass.equals(first), "the fixture plant is selected first"); checks++;
			FarmerJob.rejectForageTarget(state, grass);
			require(grass.equals(FarmerJob.findSeedGrass(context, ref)),
				"the plant that gave no seed is searched again when no other plant lies within eight blocks"); checks++;

			// 2. A second plant close by is preferred over repeating the same one.
			BlockPos neighbour = grass.offset(0, 0, 5);
			set(level, before, neighbour.below(), Blocks.GRASS_BLOCK.defaultBlockState());
			set(level, before, neighbour, Blocks.SHORT_GRASS.defaultBlockState());
			set(level, before, neighbour.above(), Blocks.AIR.defaultBlockState());
			BlockPos next = FarmerJob.findSeedGrass(context, ref);
			require(neighbour.equals(next), "another plant within eight blocks is preferred over searching the same one again"); checks++;
			set(level, before, neighbour, Blocks.AIR.defaultBlockState());
			state.forageRejectedTarget = null;

			// 3. The ordinary 32-block search does not see a meadow 50 blocks away, the wide search does.
			worker.setPos(grass.getX() + 50.5, grass.getY(), grass.getZ() + 0.5);
			level.getChunkAt(worker.blockPosition());
			context = new Ctx(level, worker, state, memory, ref, level.getGameTime() + 2L);
			BlockPos ordinary = FarmerJob.findSeedGrass(context, ref);
			BlockPos wide = FarmerJob.findFarSeedGrass(context, ref);
			require(!grass.equals(ordinary), "the ordinary search ignores a meadow 50 blocks from the farmer"); checks++;
			require(wide != null && (grass.equals(wide) || wide.distSqr(worker.blockPosition()) < 50.0 * 50.0),
				"the wide search reaches the meadow 50 blocks away (or a nearer plant)"); checks++;
			set(level, before, grass, Blocks.AIR.defaultBlockState());
			context = new Ctx(level, worker, state, memory, ref, level.getGameTime() + 3L);
			require(!grass.equals(FarmerJob.findFarSeedGrass(context, ref)), "a plant that is gone is not selected"); checks++;

			// 4. Starter seeds are delivered in batches until the first field exists.
			ref.data().farms = 0;
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 1));
			state.forageLastRollAt = context.now();
			int publicSeeds = de.villagerplus.village.Balance.storage(level, ref).stream()
				.mapToInt(box -> JobUtil.count(box, s -> s.is(Items.WHEAT_SEEDS))).sum();
			require(FarmerJob.starterSeedDepositDue(context) == (publicSeeds + 1 >= 8),
				"a single starter seed is kept for a worthwhile trip unless it completes the field's eight seeds"); checks++;
			worker.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 2));
			require(FarmerJob.starterSeedDepositDue(context), "a batch of two starter seeds is delivered"); checks++;
			state.forageLastRollAt = context.now() - 1000L;
			require(FarmerJob.starterSeedDepositDue(context), "a forager that has stopped rolling delivers its single seed"); checks++;
			state.forageLastRollAt = context.now();
			ref.data().farms = 1;
			require(FarmerJob.starterSeedDepositDue(context), "once a field exists every seed is delivered"); checks++;
			worker.getInventory().clearContent();
			require(!FarmerJob.starterSeedDepositDue(context), "nothing to deliver without seeds"); checks++;
			return checks;
		} finally {
			ref.data().farms = oldFarms;
			if (worker != null) {
				JobUtil.clearLongRoute(worker);
				worker.getInventory().clearContent();
				worker.discard();
			}
			before.forEach((pos, old) -> level.setBlock(pos, old, 3));
		}
	}

	private static void set(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		before.computeIfAbsent(pos, level::getBlockState);
		level.setBlock(pos, state, 2);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("FIRST BUILDING CHECK FAILED: " + message);
	}
}
