package de.villagerplus.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;

/** Focused world checks for filling short village-path gaps across safe one-block slopes. */
public final class RoadPathGapChecks {
	private RoadPathGapChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		BlockPos target = at.offset(120, 0, 120);
		clear(level, target);
		setGap(level, target);
		setPath(level, target.east().above());
		setPath(level, target.west().below());
		require(Roads.gapAnchored(level, target, Direction.EAST, ignored -> true),
			"a supported empty cell links real path blocks one level up and down");
		int checks = 1;

		clear(level, target);
		setGap(level, target);
		setPath(level, target.east().above());
		setPath(level, target.west());
		require(Roads.gapAnchored(level, target, Direction.EAST, ignored -> true),
			"a one-block rise remains directly walkable through the repaired gap");
		checks++;

		clear(level, target);
		setGap(level, target);
		setPath(level, target.east().above(2));
		setPath(level, target.west());
		require(!Roads.gapAnchored(level, target, Direction.EAST, ignored -> true),
			"a two-block jump is rejected instead of creating an inaccessible path");
		checks++;

		clear(level, target);
		setGap(level, target);
		setPath(level, target.east().above());
		setPath(level, target.west());
		level.setBlock(target.east().above(3), Blocks.STONE.defaultBlockState(), 3);
		require(!Roads.gapAnchored(level, target, Direction.EAST, ignored -> true),
			"path with blocked headroom is never accepted as a safe endpoint");
		checks++;

		clear(level, target);
		setGap(level, target);
		setPath(level, target.east());
		setPath(level, target.west());
		level.setBlock(target.above(), Blocks.DANDELION.defaultBlockState(), 3);
		require(Roads.gapTerrain(level, target) && Roads.gapObstacle(level, target.above())
			&& Roads.gapAnchored(level, target, Direction.EAST, ignored -> true),
			"one natural flower in the clearance box is selected for a safe paid repair");
		checks++;

		level.setBlock(target.above(), Blocks.STONE.defaultBlockState(), 3);
		require(!Roads.gapTerrain(level, target) && !Roads.gapObstacle(level, target.above()),
			"solid valuable blocks remain outside the clearance removal allowlist");
		checks++;
		level.setBlock(target.above(), Blocks.DANDELION.defaultBlockState(), 3);
		level.setBlock(target.above(2), Blocks.FERN.defaultBlockState(), 3);
		require(!Roads.gapTerrain(level, target),
			"multiple stacked obstructions exceed the single-cell clearance allowance");
		checks++;
		level.setBlock(target.above(), Blocks.CHEST.defaultBlockState(), 3);
		require(!Roads.gapTerrain(level, target) && !Roads.gapObstacle(level, target.above()),
			"a private container in the clearance box is never treated as removable");
		checks++;
		level.setBlock(target.above(), Blocks.WATER.defaultBlockState(), 3);
		require(!Roads.gapTerrain(level, target) && !Roads.gapObstacle(level, target.above()),
			"water in the clearance box remains untouched");
		checks++;

		clear(level, target);
		setGap(level, target);
		setPath(level, target.west());
		for (int x = 1; x < 8; x++) setGap(level, target.east(x));
		setPath(level, target.east(8));
		Roads.GapAnchor east = Roads.gapAnchorDistance(level, target, Direction.EAST, ignored -> true);
		Roads.GapAnchor west = Roads.gapAnchorDistance(level, target, Direction.WEST, ignored -> true);
		require(Roads.gapAnchored(level, target, Direction.EAST, ignored -> true),
			"an eight-cell saved path gap is within the paid repair bound; terrain=" + Roads.gapTerrain(level, target)
				+ ", east=" + east + ", west=" + west + ", target=" + level.getBlockState(target)
				+ ", support=" + level.getBlockState(target.below()) + ", targetHead=" + level.getBlockState(target.above())
				+ ", clearance=" + clearance(level, target) + " | " + clearance(level, target.above())
				+ " | " + clearance(level, target.above(2))
				+ ", east8=" + level.getBlockState(target.east(8))
				+ ", east8Support=" + level.getBlockState(target.east(8).below()));
		checks++;

		setGap(level, target.east(8));
		setPath(level, target.east(9));
		require(!Roads.gapAnchored(level, target, Direction.EAST, ignored -> true),
			"a nine-cell opening remains outside the paid repair bound");
		checks++;
		return checks;
	}

	private static void setGap(ServerLevel level, BlockPos target) {
		level.setBlock(target.below(), Blocks.DIRT.defaultBlockState(), 3);
		level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(target.above(), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(target.above(2), Blocks.AIR.defaultBlockState(), 3);
	}

	private static void setPath(ServerLevel level, BlockPos path) {
		level.setBlock(path.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(path, Blocks.DIRT_PATH.defaultBlockState(), 3);
		level.setBlock(path.above(), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(path.above(2), Blocks.AIR.defaultBlockState(), 3);
	}

	private static void clear(ServerLevel level, BlockPos center) {
		for (int x = -12; x <= 12; x++) for (int y = -4; y <= 5; y++) for (int z = -2; z <= 2; z++) {
			BlockPos pos = center.offset(x, y, z);
			level.getChunkAt(pos);
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
		AABB fixture = new AABB(center.getX() - 12, center.getY() - 4, center.getZ() - 2,
			center.getX() + 13, center.getY() + 6, center.getZ() + 3);
		level.getEntitiesOfClass(ItemEntity.class, fixture).forEach(ItemEntity::discard);
	}

	private static String clearance(ServerLevel level, BlockPos pos) {
		AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
		return pos + "{loaded=" + level.hasChunkAt(pos) + ",state=" + level.getBlockState(pos)
			+ ",entities=" + level.getEntitiesOfClass(ItemEntity.class, box).stream()
				.map(entity -> entity.getType() + "@" + entity.blockPosition()).toList() + "}";
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Road path gap: " + message);
	}
}
