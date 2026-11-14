package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Focused in-world checks for paid, protected and reload-stable new-house yards. */
public final class HouseYardChecks {
	private HouseYardChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Direction dir = Direction.EAST;
		int style = Builder.Paths.yardStyle(level, origin, dir);
		List<Step> yard = Builder.Paths.yardSteps(level, origin, dir, style);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		try {
			for (Step step : yard) {
				BlockPos pos = step.pos();
				for (int dy = -1; dy <= 5; dy++) {
					BlockPos p = pos.above(dy).immutable();
					snapshot(level, before, p);
					BlockState state = dy == -1 ? yardGround(style)
						: dy == 0 && style == Builder.Paths.YARD_SNOW ? Blocks.SNOW.defaultBlockState()
						: Blocks.AIR.defaultBlockState();
					level.setBlock(p, state, 3);
				}
			}
			require(Builder.Paths.houseYardFits(level, List.of(Step.block(origin, Blocks.STONE_BRICKS)), origin, dir, style),
				"clear yard stays outside the house and door approach");
			int expectedSteps = style == Builder.Paths.YARD_FENCE ? 8 : 5;
			require(yard.size() == expectedSteps, "biome style creates a complete compact yard plan");
			require(yard.stream().allMatch(step -> !step.free() && !step.optional() && step.item() != null && step.cost() == 1),
				"every visible yard block has a one-item village-stock cost");

			Step first = yard.get(0);
			String plan = signature(yard);
			level.setBlock(first.pos(), Blocks.CHEST.defaultBlockState(), 3);
			require(!Builder.Paths.yardCellClear(level, first, style), "a later chest is protected from yard placement");
			require(!Builder.Paths.yardPlotFits(level, yard, style), "a newly occupied yard cell invalidates the open-space check");

			level.setBlock(first.pos(), first.make().apply(Block.byItem(first.item())), 3);
			List<Step> resumed = Builder.Paths.yardSteps(level, origin, dir, style);
			require(plan.equals(signature(resumed)), "yard positions and paid materials are unchanged after partial placement/reload");
			require(Builder.Paths.isYardStep(level, origin, dir, first.pos()), "the placed yard cursor is still identifiable after reload");
			return 7;
		} finally {
			restore(level, before);
		}
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		level.getChunkAt(pos);
		BlockEntity entity = level.getBlockEntity(pos);
		before.putIfAbsent(pos.immutable(), new Cell(level.getBlockState(pos), entity == null ? null
			: entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		before.keySet().forEach(level::removeBlockEntity);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.nbt() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private static BlockState yardGround(int style) {
		return switch (style) {
			case Builder.Paths.YARD_DESERT -> Blocks.SAND.defaultBlockState();
			case Builder.Paths.YARD_FENCE -> Blocks.STONE_BRICKS.defaultBlockState();
			default -> Blocks.GRASS_BLOCK.defaultBlockState();
		};
	}

	private static String signature(List<Step> steps) {
		return steps.stream().map(step -> step.pos().asLong() + ":" + step.item() + ":" + step.cost()).toList().toString();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("House yard: " + message);
	}

	private record Cell(BlockState state, CompoundTag nbt) { }
}
