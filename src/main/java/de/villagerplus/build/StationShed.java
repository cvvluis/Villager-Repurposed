package de.villagerplus.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Lokschuppen im Bahnhof: eine überdachte Schienenzunge mit Antriebsschiene und Redstone,
 * nur auf freien, tragfähigen Bodenflächen.
 */
public final class StationShed {
	private StationShed() { }

	public static int furnish(ServerLevel level, BoundingBox box) {
		int y = box.minY();
		int z = (box.minZ() + box.maxZ()) / 2;
		ListSpan span = span(level, box, y, z);
		if (span == null) return 0;
		int placed = 0;
		int middle = (span.from() + span.to()) / 2;
		BlockPos poweredRailPos = new BlockPos(middle, y, z);
		BlockPos powerPos = poweredRailPowerPos(level, box, poweredRailPos);
		boolean poweredRailPlaced = false;
		for (int x = span.from(); x <= span.to(); x++) {
			BlockPos railPos = new BlockPos(x, y, z);
			boolean powered = x == middle && powerPos != null;
			BlockState rail = powered
				? Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST)
				: Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
			if (level.setBlock(railPos, rail, 3)) {
				placed++;
				if (powered) poweredRailPlaced = true;
			}
			BlockPos roof = railPos.above(3);
			if (level.getBlockState(roof).isAir()) {
				if (level.setBlock(roof, Blocks.SPRUCE_SLAB.defaultBlockState(), 3)) placed++;
			}
		}
		if (poweredRailPlaced && powerPos != null) {
			if (level.setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)) {
				placed++;
			} else {
				// A failed support placement must not leave a powered rail that brakes the terminal cart.
				level.setBlock(poweredRailPos, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST), 3);
			}
		}
		BlockPos lamp = new BlockPos(span.from(), y + 2, z);
		if (level.getBlockState(lamp).isAir()) {
			level.setBlock(lamp, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false), 3);
			placed++;
		}
		return placed;
	}

	/** Use the preferred south-side support, then the opposite side; never overwrite a station furnishing. */
	private static BlockPos poweredRailPowerPos(ServerLevel level, BoundingBox box, BlockPos railPos) {
		for (Direction side : new Direction[] {Direction.SOUTH, Direction.NORTH}) {
			BlockPos power = railPos.relative(side);
			BlockPos floor = power.below();
			if (!box.isInside(power) || !level.hasChunkAt(power) || !level.hasChunkAt(floor)
				|| !level.getBlockState(power).isAir()
				|| !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
			return power;
		}
		return null;
	}

	private static ListSpan span(ServerLevel level, BoundingBox box, int y, int z) {
		int run = 0;
		int start = box.minX();
		for (int x = box.minX(); x <= box.maxX(); x++) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockPos floor = pos.below();
			boolean open = level.getBlockState(pos).isAir() && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
			if (open) {
				if (run == 0) start = x;
				run++;
				if (run >= 5) return new ListSpan(x - 4, x);
			} else run = 0;
		}
		return null;
	}

	private record ListSpan(int from, int to) { }
}
