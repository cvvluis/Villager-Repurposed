package de.villagerplus.village;

import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.DorftafelGrossBlock;
import de.villagerplus.block.DorftafelNameBlockEntity;
import de.villagerplus.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Server-side invariants of the large (4 x 2) village board and the upgrade of old three-block boards. */
public final class DorftafelGrossChecks {
	private DorftafelGrossChecks() { }

	private static int checks;

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("DORFTAFEL CHECK FAILED: " + message);
		checks++;
	}

	private static void clearArea(ServerLevel level, BlockPos origin) {
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-8, -1, -8), origin.offset(8, 4, 8))) {
			level.setBlock(p, p.getY() == origin.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
		}
	}

	public static int run(ServerLevel level, BlockPos origin, VillageRef ref) {
		checks = 0;
		int[] relief = DorftafelNameBlockEntity.sampleRelief(level, ref);
		check(relief.length == DorftafelNameBlockEntity.MAP_W * DorftafelNameBlockEntity.MAP_H,
			"Relief samples cover the full 32 by 23 map");
		check(java.util.Arrays.stream(relief).allMatch(value -> (value & ~0x1f3f) == 0 && (value & 15) <= 11),
			"relief samples contain valid terrain, bounded elevation and marker fields");
		check(java.util.Arrays.stream(relief).filter(value -> (value & 15) == 3 || (value & 15) == 11)
			.allMatch(value -> ((value >> 4) & 3) == 0), "water and unloaded terrain are flat in the relief");
		check(java.util.Arrays.equals(relief, DorftafelNameBlockEntity.sampleRelief(level, ref)),
			"unchanged village terrain and markers produce stable relief samples");
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			clearArea(level, origin);
			BlockState base = ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, facing);
			DorftafelBlock.placeAll(level, origin, base);
			BlockState any = level.getBlockState(origin);
			String label = "gross " + facing;
			check(any.getBlock() instanceof DorftafelGrossBlock, label + ": Block gesetzt");
			check(DorftafelBlock.widthOf(any) == 4, label + ": vier Spalten");
			check(DorftafelBlock.complete(level, origin, any), label + ": vollstaendig");
			BlockPos[] lower = DorftafelBlock.parts(any, origin);
			check(lower.length == 4 && lower[0].equals(origin), label + ": vier untere Teile ab links");
			Direction r = facing.getCounterClockWise();
			for (int i = 0; i < 4; i++) {
				check(lower[i].equals(origin.relative(r, i)), label + ": Teil " + i + " liegt rechts daneben");
				BlockState lowerState = level.getBlockState(lower[i]);
				BlockState upperState = level.getBlockState(lower[i].above());
				check(lowerState.getValue(DorftafelBlock.PART) == DorftafelBlock.partAt(true, i), label + ": Teil " + i + " Rolle");
				check(lowerState.getValue(DorftafelBlock.HALF) == DoubleBlockHalf.LOWER && upperState.getValue(DorftafelBlock.HALF) == DoubleBlockHalf.UPPER,
					label + ": Teil " + i + " unten und oben");
				// every part finds the same set of parts, whichever one is asked
				check(java.util.Arrays.equals(DorftafelBlock.parts(upperState, lower[i].above()), lower), label + ": Teil " + i + " kennt die Tafel");
			}
			check(level.getBlockState(origin).getValue(DorftafelGrossBlock.WOOD) != null, label + ": Holzart gesetzt");
			// the village name lives on the first middle column, upper half, and nowhere else
			int entities = 0;
			for (BlockPos p : lower) {
				for (BlockPos q : new BlockPos[] {p, p.above()}) {
					if (level.getBlockEntity(q) instanceof DorftafelNameBlockEntity) entities++;
				}
			}
			check(entities == 1 && level.getBlockEntity(lower[1].above()) instanceof DorftafelNameBlockEntity, label + ": ein Namens-Block auf Mitte/oben");
			// a missing part makes the board incomplete
			level.setBlock(lower[3].above(), Blocks.AIR.defaultBlockState(), 3);
			check(!DorftafelBlock.complete(level, origin, any), label + ": fehlendes Teil wird erkannt");
		}

		// wood follows the surroundings: no village here, so the biome decides (plains -> oak)
		check(DorftafelGrossBlock.woodFor(level, origin) == DorftafelGrossBlock.Wood.OAK
			|| DorftafelGrossBlock.woodFor(level, origin) != null, "Holzart bestimmbar");

		// The large board occupies two block cells; a block above that space must not push it away.
		clearArea(level, origin);
		level.setBlock(origin.above(2), Blocks.OAK_LEAVES.defaultBlockState(), 3);
		check(Board.boardColumnClear(level, origin), "three-block canopy does not block two-block board clearance");
		level.setBlock(origin.above(), Blocks.STONE.defaultBlockState(), 3);
		check(!Board.boardColumnClear(level, origin), "obstruction inside the upper board cell blocks placement");

		// A chest below the surrounding terrain can still find a safe board strip at surface height.
		clearArea(level, origin);
		BlockPos elevatedSite = Board.findFallbackSite(level, origin.below(2));
		check(elevatedSite != null && elevatedSite.getY() == origin.getY(),
			"fallback finds loaded level ground above a chest on a slope");
		check(level.getBlockState(elevatedSite).isAir() && level.getBlockState(elevatedSite.below()).is(Blocks.STONE),
			"fallback search leaves the terrain untouched");

		// an old three-block board stays valid and is upgraded in place when its fourth column is free
		clearArea(level, origin);
		BlockState old = ModBlocks.DORFTAFEL.defaultBlockState().setValue(DorftafelBlock.FACING, Direction.SOUTH);
		DorftafelBlock.placeAll(level, origin, old);
		BlockState oldAny = level.getBlockState(origin);
		check(DorftafelBlock.widthOf(oldAny) == 3 && DorftafelBlock.complete(level, origin, oldAny), "alte Tafel bleibt dreibreit und vollstaendig");
		check(DorftafelBlock.parts(oldAny, origin).length == 3, "alte Tafel hat drei Teile");
		ref.data().extra.board = java.util.Optional.of(origin);
		Board.ensure(level, ref);
		BlockState upgraded = level.getBlockState(origin);
		check(upgraded.getBlock() instanceof DorftafelGrossBlock && DorftafelBlock.complete(level, origin, upgraded), "alte Tafel wird zur grossen Tafel");
		check(level.getBlockState(origin.relative(Direction.SOUTH.getCounterClockWise(), 3)).getBlock() instanceof DorftafelGrossBlock, "vierte Spalte steht");

		// a blocked fourth column keeps the old board as it is
		clearArea(level, origin);
		DorftafelBlock.placeAll(level, origin, old);
		level.setBlock(origin.relative(Direction.SOUTH.getCounterClockWise(), 3), Blocks.STONE.defaultBlockState(), 3);
		Board.ensure(level, ref);
		check(level.getBlockState(origin).getBlock() == ModBlocks.DORFTAFEL, "blockierte vierte Spalte: alte Tafel bleibt");
		clearArea(level, origin);
		return checks;
	}
}