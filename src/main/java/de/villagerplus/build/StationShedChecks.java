package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.block.entity.BlockEntity;

/** In-world regression checks for a usable station-powered rail beside obstructed furniture cells. */
public final class StationShedChecks {
	private record Cell(BlockState state, CompoundTag data) { }
	private StationShedChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		BoundingBox box = new BoundingBox(at.getX() - 3, at.getY(), at.getZ() - 2,
			at.getX() + 3, at.getY() + 3, at.getZ() + 2);
		Map<BlockPos, Cell> before = capture(level, at);
		int checks = 0;
		try {
			prepare(level, at, false);
			int placed = StationShed.furnish(level, box);
			BlockPos poweredRail = new BlockPos(at.getX() - 1, at.getY(), at.getZ());
			BlockPos preferredPower = poweredRail.south();
			BlockPos alternatePower = poweredRail.north();
			BlockState poweredState = level.getBlockState(poweredRail);
			boolean completeSpan = true;
			for (int x = at.getX() - 3; x <= at.getX() + 1; x++) {
				completeSpan &= level.getBlockState(new BlockPos(x, at.getY(), at.getZ()))
					.is(x == poweredRail.getX() ? Blocks.POWERED_RAIL : Blocks.RAIL);
			}
			check(placed > 0 && completeSpan && poweredState.is(Blocks.POWERED_RAIL)
				&& poweredState.getValue(PoweredRailBlock.POWERED)
				&& level.getBlockState(alternatePower).is(Blocks.REDSTONE_BLOCK),
				"a blocked preferred power cell uses the clear opposite-side support and powers the terminal rail"); checks++;
			check(level.getBlockState(preferredPower).is(Blocks.BARREL) && hasDiamond(level, preferredPower),
				"the station keeps inventory in the existing preferred-side barrel"); checks++;
			check(StationShed.furnish(level, box) == 0 && level.getBlockState(poweredRail).getValue(PoweredRailBlock.POWERED),
				"station track furnishing is idempotent after the powered span is complete"); checks++;

			prepare(level, at, true);
			StationShed.furnish(level, box);
			BlockState fallback = level.getBlockState(poweredRail);
			check(fallback.is(Blocks.RAIL) && !level.getBlockState(preferredPower).is(Blocks.REDSTONE_BLOCK)
				&& !level.getBlockState(alternatePower).is(Blocks.REDSTONE_BLOCK)
				&& hasDiamond(level, preferredPower) && hasDiamond(level, alternatePower),
				"when both power cells are occupied, the middle track stays an ordinary rail and both inventories survive"); checks++;
			return checks;
		} finally {
			restore(level, before);
		}
	}

	private static void prepare(ServerLevel level, BlockPos at, boolean blockBothSides) {
		clear(level, at);
		for (int x = at.getX() - 3; x <= at.getX() + 3; x++) {
			level.setBlock(new BlockPos(x, at.getY() - 1, at.getZ()), Blocks.STONE.defaultBlockState(), 3);
		}
		BlockPos middle = new BlockPos(at.getX() - 1, at.getY(), at.getZ());
		placeBarrelWithDiamond(level, middle.south());
		if (blockBothSides) placeBarrelWithDiamond(level, middle.north());
		for (BlockPos support : new BlockPos[] {middle.south().below(), middle.north().below()}) {
			level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
		}
	}

	private static void placeBarrelWithDiamond(ServerLevel level, BlockPos pos) {
		level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 3);
		if (level.getBlockEntity(pos) instanceof Container inventory) {
			inventory.setItem(0, new ItemStack(Items.DIAMOND));
			inventory.setChanged();
		}
	}

	private static boolean hasDiamond(ServerLevel level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof Container inventory
			&& inventory.getItem(0).is(Items.DIAMOND) && inventory.getItem(0).getCount() == 1;
	}

	private static Map<BlockPos, Cell> capture(ServerLevel level, BlockPos at) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (int x = at.getX() - 4; x <= at.getX() + 4; x++) {
			for (int y = at.getY() - 1; y <= at.getY() + 4; y++) {
				for (int z = at.getZ() - 3; z <= at.getZ() + 3; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					level.getChunkAt(pos);
					BlockEntity blockEntity = level.getBlockEntity(pos);
					before.put(pos, new Cell(level.getBlockState(pos),
						blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess())));
				}
			}
		}
		return before;
	}

	private static void clear(ServerLevel level, BlockPos at) {
		for (int x = at.getX() - 4; x <= at.getX() + 4; x++) {
			for (int y = at.getY() - 1; y <= at.getY() + 4; y++) {
				for (int z = at.getZ() - 3; z <= at.getZ() + 3; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					level.removeBlockEntity(pos);
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		for (BlockPos pos : before.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) level.setBlock(entry.getKey(), entry.getValue().state(), 3);
		for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) if (entry.getValue().data() != null) {
			BlockEntity blockEntity = BlockEntity.loadStatic(entry.getKey(), entry.getValue().state(),
				entry.getValue().data().copy(), level.registryAccess());
			if (blockEntity != null) level.setBlockEntity(blockEntity);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Station shed: " + message);
	}
}
