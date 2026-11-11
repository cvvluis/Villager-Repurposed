package de.villagerplus.job;

import de.villagerplus.village.VillageRef;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Focused deterministic checks for demand thresholds and safe small-field placement. */
public final class FarmingChecks {
	private FarmingChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		check(FarmerJob.foodTarget(0) == 0 && FarmerJob.foodTarget(1) == 5 && FarmerJob.foodTarget(7) == 35
			&& FarmerJob.belowFoodTarget(14, 3) && !FarmerJob.belowFoodTarget(15, 3),
			"farm reserve threshold is five stocked food units per resident"); checks++;
		check(FarmerJob.shouldExpand(0, FarmerJob.belowFoodTarget(15, 3))
			&& FarmerJob.shouldExpand(1, FarmerJob.belowFoodTarget(14, 3))
			&& !FarmerJob.shouldExpand(1, FarmerJob.belowFoodTarget(15, 3))
			&& !FarmerJob.shouldExpand(de.villagerplus.Config.MAX_FARMS, true),
			"the first field can seed food production, later fields respond exactly below five food per resident, and the global farm cap holds"); checks++;

		BlockPos site = ref.bell().offset(80, 0, 80);
		level.getChunk(site);
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			BlockPos cell = site.offset(dx, 0, dz);
			for (BlockPos pos : new BlockPos[] {cell.below(), cell, cell.above()}) {
				before.putIfAbsent(pos, level.getBlockState(pos));
			}
			level.setBlock(cell.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
		}
		try {
			BlockPos naturalPlant = site.offset(-2, 0, -2);
			level.setBlock(naturalPlant, Blocks.POPPY.defaultBlockState(), 3);
			check(FarmerJob.safeClusterSite(level, site), "empty dirt plot with a naturally growing flower accepts irrigated crop and fence geometry"); checks++;
			check(FarmerJob.safeStarterClusterSite(level, site), "the compact first field accepts its paid footprint when small natural plants need clearing"); checks++;
			BlockPos obstacle = site.east(2);
			level.setBlock(obstacle, Blocks.CHEST.defaultBlockState(), 3);
			check(!FarmerJob.safeClusterSite(level, site), "farm layout preserves a pre-existing structure block"); checks++;
		} finally {
			before.forEach((pos, state) -> level.setBlock(pos, state, 3));
		}
		return checks;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("FARMING CHECK FAILED: " + message);
	}
}
