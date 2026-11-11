package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Narrow in-world checks for bookshelf exclusion from the actual village palette selection. */
public final class PaletteChecks {
	private PaletteChecks() { }

	private record Cell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos bed) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		PoiManager poi = level.getPoiManager();
		boolean manuallyAddedPoi = false;
		BlockPos head = bed.east();
		try {
			if (poi.getType(bed).isPresent() || poi.getType(head).isPresent()) {
				throw new IllegalStateException("Palette check needs an unused HOME POI fixture at " + bed.toShortString());
			}
			set(level, before, bed.below(), Blocks.DIRT.defaultBlockState());
			BlockState foot = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
				.setValue(BedBlock.PART, BedPart.FOOT);
			set(level, before, bed, foot);
			set(level, before, head, foot.setValue(BedBlock.PART, BedPart.HEAD));
			for (int y = 1; y <= 3; y++) {
				for (int x = -4; x <= 4; x++) {
					for (int z = -4; z <= 4; z++) {
						if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
						Block material = x == 4 ? Blocks.BRICKS : Blocks.BOOKSHELF;
						set(level, before, bed.offset(x, y, z), material.defaultBlockState());
					}
				}
			}
			if (poi.getType(bed).isEmpty()) {
				poi.add(bed, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME));
				manuallyAddedPoi = true;
			}
			if (poi.getType(bed).isEmpty() || !poi.getType(bed).orElseThrow().is(PoiTypes.HOME)) {
				throw new IllegalStateException("Palette check could not register its bed as HOME");
			}

			Palette dominantShelves = Palette.detect(level, bed, null, 0x51E1F, bed);
			require(dominantShelves.wall() == Blocks.BRICKS && dominantShelves.wall2() == Blocks.BRICKS
				&& !containsBookshelf(dominantShelves),
				"actual Palette.detect selects the minority brick wall and excludes the bookshelf-heavy facade");

			for (int y = 1; y <= 3; y++) {
				for (int z = -4; z <= 4; z++) set(level, before, bed.offset(4, y, z), Blocks.BOOKSHELF.defaultBlockState());
			}
			Palette shelvesOnly = Palette.detect(level, bed, null, 0x51E20, bed);
			require(shelvesOnly.wall() == Blocks.COBBLESTONE && !containsBookshelf(shelvesOnly),
				"actual palette fallback stays buildable when bookshelves are the only full-block facade material");
			return 2;
		} finally {
			if (manuallyAddedPoi) poi.remove(bed);
			for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
			before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			before.forEach((pos, cell) -> {
				if (cell.blockEntity() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
		}
	}

	private static void set(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		before.computeIfAbsent(pos, p -> {
			BlockEntity entity = level.getBlockEntity(p);
			return new Cell(level.getBlockState(p), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
		});
		level.setBlock(pos, state, 3);
	}

	private static boolean containsBookshelf(Palette palette) {
		return isBookshelf(palette.wall()) || isBookshelf(palette.wall2()) || isBookshelf(palette.frame())
			|| isBookshelf(palette.base()) || isBookshelf(palette.roofFull()) || isBookshelf(palette.roofStair())
			|| isBookshelf(palette.roofSlab()) || isBookshelf(palette.window()) || isBookshelf(palette.floor())
			|| isBookshelf(palette.door()) || isBookshelf(palette.fence());
	}

	private static boolean isBookshelf(Block block) {
		return block == Blocks.BOOKSHELF || block == Blocks.CHISELED_BOOKSHELF;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Palette check failed: " + message);
	}
}
