package de.villagerplus.block;

import de.villagerplus.village.Board;
import de.villagerplus.world.LoadedColumns;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Completes the whole assembly before BlockItem reports success and consumes the stack. */
final class DorftafelItem extends BlockItem {
	private record Cell(BlockPos pos, BlockState before, BlockState board) { }

	DorftafelItem(DorftafelBlock block, Properties properties) {
		super(block, properties);
	}

	@Override
	protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
		if (!(context.getLevel() instanceof ServerLevel level)) return super.placeBlock(context, state);
		BlockPos anchor = context.getClickedPos();
		BlockState base = DorftafelBlock.withLocalWood(level, anchor, state);
		var savedProperties = context.getItemInHand().get(DataComponents.BLOCK_STATE);
		// Vanilla would apply these properties to the root alone after placement.
		if (savedProperties != null && savedProperties.apply(base) != base) return false;
		BlockPos[] columns = DorftafelBlock.parts(base, anchor);
		var player = context.getPlayer();
		CollisionContext collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
		List<Cell> cells = new ArrayList<>(columns.length * 2);
		for (int i = 0; i < columns.length; i++) {
			BlockState column = base.setValue(DorftafelBlock.PART, DorftafelBlock.partAt(columns.length == 4, i));
			for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
				BlockPos pos = half == DoubleBlockHalf.LOWER ? columns[i] : columns[i].above();
				BlockState old = LoadedColumns.state(level, pos);
				BlockState part = column.setValue(DorftafelBlock.HALF, half);
				if (old == null || !old.canBeReplaced(context) || old.hasBlockEntity()
					|| (player != null && !player.mayInteract(level, pos)) || !level.isUnobstructed(part, pos, collision)) return false;
				cells.add(new Cell(pos, old, part));
			}
		}
		int written = 0;
		for (Cell cell : cells) {
			if (LoadedColumns.state(level, cell.pos()) != cell.before()
				|| !level.setBlock(cell.pos(), cell.board(), Block.UPDATE_ALL_IMMEDIATE)) {
				rollback(level, cells, written);
				return false;
			}
			written++;
		}
		if (!Board.playerPlaced(level, anchor, base, context.getItemInHand())) {
			rollback(level, cells, written);
			return false;
		}
		return true;
	}

	private static void rollback(ServerLevel level, List<Cell> cells, int written) {
		for (int i = written - 1; i >= 0; i--) {
			Cell cell = cells.get(i);
			if (LoadedColumns.state(level, cell.pos()) == cell.board()) {
				level.setBlock(cell.pos(), cell.before(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}
}
