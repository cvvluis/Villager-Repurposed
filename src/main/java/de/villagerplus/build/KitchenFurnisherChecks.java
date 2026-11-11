package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.resources.Identifier;

/** In-world checks for complete, non-destructive, idempotent kitchen furnishing. */
public final class KitchenFurnisherChecks {
	private KitchenFurnisherChecks() { }

	private record Cell(BlockState state, CompoundTag entity) { }

	public static int run(ServerLevel level, BlockPos origin) {
		BlockPos min = origin.offset(-3, -1, -2);
		BlockPos max = origin.offset(3, 4, 2);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			BlockPos p = pos.immutable();
			level.getChunkAt(p);
			BlockEntity entity = level.getBlockEntity(p);
			before.put(p, new Cell(level.getBlockState(p), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
		}
		BoundingBox room = new BoundingBox(origin.getX() - 3, origin.getY(), origin.getZ() - 2,
			origin.getX() + 3, origin.getY() + 2, origin.getZ() + 2);
		BlockPos obstruction = origin.offset(-2, 0, 0);
		BlockPos pantryChest = origin;
		try {
			for (BlockPos p : BlockPos.betweenClosed(min, max)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
			for (int x = -3; x <= 3; x++) for (int z = -2; z <= 2; z++) {
				level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(origin.offset(x, 3, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
				if (Math.abs(x) == 3 || Math.abs(z) == 2) {
					for (int y = 0; y <= 2; y++) level.setBlock(origin.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
				}
			}
			level.setBlock(obstruction, Blocks.BARREL.defaultBlockState(), 3);
			Container barrel = (Container) level.getBlockEntity(obstruction);
			barrel.setItem(0, new ItemStack(Items.APPLE, 7));
			level.setBlock(pantryChest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.NORTH), 3);
			Container chest = (Container) level.getBlockEntity(pantryChest);
			chest.setItem(0, new ItemStack(Items.BREAD, 5));

			Block pot = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(Blueprints.FARMERS_DELIGHT_COOKING_POT)).orElse(null);
			Block board = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(Blueprints.FARMERS_DELIGHT_CUTTING_BOARD)).orElse(null);
			Furnisher.furnishSpecial(level, room, Blueprints.KITCHEN);
			int checks = 0;
			check(pot == null ? count(level, room, Blocks.BARREL) == 1 : count(level, room, pot) == 1,
				"registered Farmer's Delight cooking pot is placed exactly once (no barrel stand-in)"); checks++;
			check(board == null ? count(level, room, Blocks.COMPOSTER) == 0 : count(level, room, board) == 1,
				"registered Farmer's Delight cutting board is placed exactly once (no composter stand-in)"); checks++;
			check(count(level, room, Blocks.FURNACE) == 1 && count(level, room, Blocks.SMOKER) == 1
				&& count(level, room, Blocks.BLAST_FURNACE) == 1 && count(level, room, Blocks.CRAFTING_TABLE) == 1
				&& count(level, room, Blocks.CAULDRON) == 1,
				"kitchen retains its distinct vanilla oven, smoker, blast furnace, crafting table, and cauldron"); checks++;
			check(level.getBlockState(obstruction).is(Blocks.BARREL) && count((Container) level.getBlockEntity(obstruction), Items.APPLE) == 7,
				"furnishing preserves an existing storage block and its real contents"); checks++;
			check(level.getBlockState(pantryChest).is(Blocks.CHEST)
				&& count((Container) level.getBlockEntity(pantryChest), Items.BREAD) == 5,
				"furnishing preserves an existing chest and its real contents"); checks++;

			int potCount = pot == null ? 0 : count(level, room, pot);
			int boardCount = board == null ? 0 : count(level, room, board);
			int furnaceCount = count(level, room, Blocks.FURNACE);
			int smokerCount = count(level, room, Blocks.SMOKER);
			int blastCount = count(level, room, Blocks.BLAST_FURNACE);
			int workbenchCount = count(level, room, Blocks.CRAFTING_TABLE);
			int cauldronCount = count(level, room, Blocks.CAULDRON);
			int chestCount = Furnisher.collectChests(level, room).size();
			Furnisher.furnishSpecial(level, room, Blueprints.KITCHEN);
			check((pot == null || count(level, room, pot) == potCount) && (board == null || count(level, room, board) == boardCount)
				&& count(level, room, Blocks.FURNACE) == furnaceCount && count(level, room, Blocks.SMOKER) == smokerCount
				&& count(level, room, Blocks.BLAST_FURNACE) == blastCount && count(level, room, Blocks.CRAFTING_TABLE) == workbenchCount
				&& count(level, room, Blocks.CAULDRON) == cauldronCount && Furnisher.collectChests(level, room).size() == chestCount,
				"repeated furnishing is idempotent and does not add duplicate stations or chest pairs"); checks++;
			return checks;
		} finally {
			for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) {
				BlockPos pos = entry.getKey();
				Cell cell = entry.getValue();
				level.setBlock(pos, cell.state(), 2);
				if (cell.entity() == null) {
					if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
				} else {
					BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.entity().copy(), level.registryAccess());
					if (restored != null) level.setBlockEntity(restored);
				}
			}
		}
	}

	private static int count(ServerLevel level, BoundingBox box, Block block) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			if (level.getBlockState(pos).is(block)) count++;
		}
		return count;
	}

	private static int count(Container container, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).is(item)) count += container.getItem(slot).getCount();
		}
		return count;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("KITCHEN FURNISHER CHECK FAILED: " + message);
	}
}
