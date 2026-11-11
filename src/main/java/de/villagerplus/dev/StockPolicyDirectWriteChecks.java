package de.villagerplus.dev;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** World checks for direct Container#setItem writes into registered public material storage. */
public final class StockPolicyDirectWriteChecks {
	private StockPolicyDirectWriteChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		List<BlockPos> oldLagerChests = new ArrayList<>(ref.data().extra.lagerChests);
		List<BlockPos> excluded = new ArrayList<>(oldLagerChests);
		BlockPos centerChest = ref.chestPos(level);
		if (centerChest != null) excluded.add(centerChest);

		BlockState leftState = Blocks.CHEST.defaultBlockState()
			.setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockState rightState = leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT);
		BlockPos leftPos = findDoubleChestLeft(level, ref.bell(), leftState, excluded);
		BlockPos rightPos = leftPos.relative(ChestBlock.getConnectedDirection(leftState));
		if (rightPos.equals(leftPos)) throw new IllegalStateException("Invalid direct-write double-chest fixture");
		excluded.add(leftPos);
		excluded.add(rightPos);
		BlockPos privatePos = findSingleChest(level, ref.bell(), excluded);
		excluded.add(privatePos);
		BlockPos unregisteredPos = findSingleChest(level, ref.bell(), excluded);

		Map<BlockPos, BlockState> oldStates = new LinkedHashMap<>();
		remember(level, oldStates, leftPos);
		remember(level, oldStates, rightPos);
		remember(level, oldStates, privatePos);
		remember(level, oldStates, unregisteredPos);
		int[] passed = {0};
		try {
			level.setBlock(leftPos, leftState, 3);
			level.setBlock(rightPos, rightState, 3);
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(unregisteredPos, Blocks.CHEST.defaultBlockState(), 3);
			ref.data().extra.lagerChests.add(leftPos.immutable());
			ref.dirty();
			level.getBlockEntity(privatePos).setAttached(VillagerPlus.OWNER, UUID.randomUUID());

			Container registeredLeft = containerAt(level, leftPos);
			Container registeredRight = containerAt(level, rightPos);
			Container privateChest = containerAt(level, privatePos);
			Container unregisteredChest = containerAt(level, unregisteredPos);
			ItemStack log = new ItemStack(Items.OAK_LOG, 3);
			ItemStack forbidden = new ItemStack(Items.BREAD, 1);

			registeredLeft.setItem(0, log.copy());
			check(passed, ItemStack.isSameItemSameComponents(registeredLeft.getItem(0), log),
				"direct Container#setItem accepts approved material in a registered storage double chest");
			registeredLeft.setItem(0, forbidden.copy());
			check(passed, ItemStack.isSameItemSameComponents(registeredLeft.getItem(0), log),
				"a rejected direct write preserves the existing slot stack without duplication");
			registeredRight.setItem(0, forbidden.copy());
			check(passed, registeredRight.getItem(0).isEmpty(),
				"direct writes to the other half of a registered double chest use the same material filter");
			check(passed, StockPolicy.rejectsDirectMaterialStoreInsert(level, rightPos, forbidden),
				"the registered double-chest partner resolves to its canonical storage anchor");
			check(passed, StockPolicy.matchesRegisteredMaterialStorePosition(rightPos, level.getBlockState(rightPos), List.of(leftPos)),
				"a loaded RIGHT half matches its persistent LEFT anchor without inspecting the partner chunk");

			privateChest.setItem(0, forbidden.copy());
			check(passed, ItemStack.isSameItemSameComponents(privateChest.getItem(0), forbidden),
				"private chests retain their normal direct setter behavior");
			unregisteredChest.setItem(0, forbidden.copy());
			check(passed, ItemStack.isSameItemSameComponents(unregisteredChest.getItem(0), forbidden),
				"unregistered public chests retain their normal direct setter behavior");

			level.getBlockEntity(rightPos).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			check(passed, !StockPolicy.rejectsDirectMaterialStoreInsert(level, leftPos, forbidden),
				"an owner marker on the other loaded double-chest half keeps the pair private");
			registeredLeft.setItem(1, forbidden.copy());
			check(passed, ItemStack.isSameItemSameComponents(registeredLeft.getItem(1), forbidden),
				"a registered pair with a privately owned loaded half retains normal direct setter behavior");
			return passed[0];
		} finally {
			for (BlockPos pos : List.of(leftPos, rightPos, privatePos, unregisteredPos)) {
				Container container = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, pos);
				if (container != null) container.clearContent();
			}
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.addAll(oldLagerChests);
			ref.dirty();
			for (Map.Entry<BlockPos, BlockState> entry : oldStates.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 3);
			}
		}
	}

	private static BlockPos findDoubleChestLeft(ServerLevel level, BlockPos bell, BlockState leftState, List<BlockPos> excluded) {
		for (int distance = 4; distance <= 48; distance += 2) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos left = bell.relative(direction, distance).above();
				BlockPos right = left.relative(ChestBlock.getConnectedDirection(leftState));
				if (isClearFixturePos(level, left, excluded) && isClearFixturePos(level, right, excluded)) return left;
			}
		}
		throw new IllegalStateException("Direct-write checks need two adjacent loaded air blocks for a temporary registered double chest");
	}

	private static BlockPos findSingleChest(ServerLevel level, BlockPos bell, List<BlockPos> excluded) {
		for (int distance = 6; distance <= 48; distance += 2) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos pos = bell.relative(direction, distance).above();
				if (isClearFixturePos(level, pos, excluded)) return pos;
			}
		}
		throw new IllegalStateException("Direct-write checks need another loaded air block for a temporary chest");
	}

	private static boolean isClearFixturePos(ServerLevel level, BlockPos pos, List<BlockPos> excluded) {
		if (excluded.contains(pos) || !level.hasChunkAt(pos) || !level.getBlockState(pos).isAir() || level.getBlockEntity(pos) != null) return false;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(direction);
			if (!level.hasChunkAt(neighbor)) return false;
			if (excluded.contains(neighbor) || level.getBlockState(neighbor).getBlock() instanceof ChestBlock) return false;
		}
		return true;
	}

	private static void remember(ServerLevel level, Map<BlockPos, BlockState> states, BlockPos pos) {
		states.put(pos, level.getBlockState(pos));
	}

	private static Container containerAt(ServerLevel level, BlockPos pos) {
		var blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof Container container) return container;
		throw new IllegalStateException("Direct-write chest fixture has no container at " + pos);
	}

	private static void check(int[] passed, boolean ok, String label) {
		if (!ok) throw new IllegalStateException("STOCK POLICY DIRECT WRITE CHECK FAILED: " + label);
		passed[0]++;
		System.out.println("STOCK POLICY DIRECT WRITE PASS " + label);
	}
}
