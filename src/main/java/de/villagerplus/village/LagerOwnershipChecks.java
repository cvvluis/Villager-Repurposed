package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** World checks for community carriers and private single/double chest boundaries. */
public final class LagerOwnershipChecks {
	private LagerOwnershipChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos origin) {
		BlockPos communityPos = ref.chestPos(level);
		Container community = ref.chest(level);
		if (communityPos == null || community == null) throw new IllegalStateException("Lager ownership checks need a public village chest");
		BlockPos privatePos = origin.offset(-8, 1, 6);
		BlockPos pairLeft = origin.offset(8, 1, 6);
		BlockPos pairRight = pairLeft.east();
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		Map<BlockPos, BlockState> standSupportBefore = new LinkedHashMap<>();
		for (BlockPos pos : List.of(communityPos, privatePos, pairLeft, pairRight)) snapshotPosition(level, before, pos);
		List<ItemStack> communityBefore = snapshot(community);
		List<BlockPos> oldLagerChests = List.copyOf(ref.data().extra.lagerChests);
		Map<String, BlockPos> oldSpecials = Map.copyOf(Specials.all(ref));
		Villager worker = EntityTypes.VILLAGER.spawn(level, communityPos, EntitySpawnReason.COMMAND);
		if (worker == null) throw new IllegalStateException("Could not create lager ownership worker fixture");
		int[] passed = {0};
		try {
			worker.addTag("vp090_fixture");
			worker.setInvulnerable(true);
			worker.setNoAi(true);
			worker.setPos(communityPos.getX() + 1.5, communityPos.getY() + 1.0, communityPos.getZ() + 0.5);
			community.clearContent();
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			BlockEntity privateEntity = level.getBlockEntity(privatePos);
			Container privateBox = HopperBlockEntity.getContainerAt(level, privatePos);
			if (privateEntity == null || privateBox == null) throw new IllegalStateException("Private single-chest fixture failed");
			privateEntity.setAttached(VillagerPlus.OWNER, worker.getUUID());
			for (int slot = 0; slot < privateBox.getContainerSize(); slot++) privateBox.setItem(slot, new ItemStack(Items.WHEAT, 64));
			privateBox.setChanged();
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(privatePos);
			check(passed, Lager.containers(level, ref.data()).isEmpty() && !Lager.materialChests(level, ref).contains(privatePos),
				"legacy warehouse and material-source scans exclude a full owner-marked single chest");

			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 5));
			Ctx context = new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			prepareDeliveryStand(level, communityPos, standSupportBefore);
			BlockPos deliveryStand = JobUtil.sharedStandSpot(context, communityPos, 4.0);
			if (deliveryStand == null) throw new IllegalStateException("Lager ownership delivery fixture has no safe public stand");
			worker.teleportTo(deliveryStand.getX() + 0.5, deliveryStand.getY(), deliveryStand.getZ() + 0.5);
			boolean delivering = JobUtil.depositSurplus(context, stack -> stack.is(Items.OAK_LOG), 1, 0);
			check(passed, delivering && JobUtil.count(community, stack -> stack.is(Items.OAK_LOG)) == 5
				&& JobUtil.count(privateBox, stack -> stack.is(Items.WHEAT)) == privateBox.getContainerSize() * 64
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.OAK_LOG)) == 0,
				"a full private chest beside an empty communal chest cannot intercept or lose a carrier delivery");
			check(passed, JobUtil.communalDeliveryChest(context).equals(communityPos),
				"communal delivery resolves the registered public chest, never the nearest private one");

			var facingNorth = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
			level.setBlock(pairLeft, facingNorth.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
			level.setBlock(pairRight, facingNorth.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
			BlockEntity privateHalf = level.getBlockEntity(pairRight);
			Container pair = HopperBlockEntity.getContainerAt(level, pairLeft);
			if (privateHalf == null || pair == null || pair.getContainerSize() != 54) {
				throw new IllegalStateException("Private double-chest fixture did not form a 54-slot container");
			}
			privateHalf.setAttached(VillagerPlus.OWNER, worker.getUUID());
			pair.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
			pair.setChanged();
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(pairLeft);
			Specials.set(ref, de.villagerplus.build.Blueprints.GRANARY, pairLeft);
			BlockPos depotTarget = Specials.depotWithRoom(level, ref, de.villagerplus.build.Blueprints.GRANARY, new ItemStack(Items.BREAD));
			check(passed, Lager.publicContainerAt(level, pairLeft) == null && Lager.publicContainerAt(level, pairRight) == null,
				"a private owner on either double-chest half makes the entire container inaccessible to carriers");
			check(passed, Lager.containers(level, ref.data()).isEmpty()
				&& !Lager.materialChests(level, ref).contains(pairLeft)
				&& Specials.containers(level, ref, de.villagerplus.build.Blueprints.GRANARY).stream()
					.noneMatch(box -> JobUtil.count(box, stack -> stack.is(Items.COBBLESTONE)) > 0)
				&& (depotTarget == null || (!depotTarget.equals(pairLeft) && !depotTarget.equals(pairRight))),
				"warehouse and milestone source/destination scans reject the privately owned half-pair");
			return passed[0];
		} finally {
			worker.discard();
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.addAll(oldLagerChests);
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.dirty();
			restore(community, communityBefore);
			for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
			before.keySet().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
			before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			before.forEach((pos, cell) -> {
				if (cell.data() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
			standSupportBefore.forEach((pos, state) -> level.setBlock(pos, state, 3));
		}
	}

	private static void prepareDeliveryStand(ServerLevel level, BlockPos target, Map<BlockPos, BlockState> before) {
		int[][] offsets = {{3,0},{2,2},{0,3},{-2,2},{-3,0},{-2,-2},{0,-3},{2,-2},
			{3,1},{1,3},{-1,3},{-3,1},{-3,-1},{-1,-3},{1,-3},{3,-1}};
		for (int[] offset : offsets) {
			BlockPos support = target.offset(offset[0], -1, offset[1]);
			BlockState state = level.getBlockState(support);
			if (level.getBlockEntity(support) != null || state.hasBlockEntity()) {
				throw new IllegalStateException("Lager ownership stand fixture overlaps a protected block entity at " + support.toShortString());
			}
			if (state.isAir()) {
				before.putIfAbsent(support.immutable(), state);
				level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
			}
		}
	}

	private static void check(int[] passed, boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Lager ownership: " + message);
		passed[0]++;
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> result = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) result.add(container.getItem(i).copy());
		return result;
	}

	private static void snapshotPosition(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		if (!before.containsKey(pos)) {
			BlockEntity be = level.getBlockEntity(pos);
			before.put(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
		}
		if (!level.hasChunkAt(pos)) return;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return;
		BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
		if (level.hasChunkAt(partner) && !before.containsKey(partner)) {
			BlockEntity partnerEntity = level.getBlockEntity(partner);
			before.put(partner, new Cell(level.getBlockState(partner), partnerEntity == null ? null
				: partnerEntity.saveWithFullMetadata(level.registryAccess())));
		}
	}

	private static void restore(Container container, List<ItemStack> contents) {
		for (int i = 0; i < Math.min(container.getContainerSize(), contents.size()); i++) container.setItem(i, contents.get(i).copy());
		container.setChanged();
	}

	private record Cell(BlockState state, CompoundTag data) { }
}
