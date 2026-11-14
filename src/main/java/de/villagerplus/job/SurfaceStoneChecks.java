package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.village.VillageRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fixture checks for the plan surface quarry: nearest-first candidate search, the whole-region memory of rejected cells
 * (a gorge floor the worker cannot leave again), and picking the next stone next to the worker after a break.
 */
public final class SurfaceStoneChecks {
	private SurfaceStoneChecks() { }

	/** Flat platform around the bell with single stone blocks standing on it, and a walled pit no walker climbs out of. */
	public static int run(ServerLevel level, VillageRef ref, BlockPos bell) {
		Map<BlockPos, BlockState> before = new HashMap<>();
		Villager worker = null;
		int checks = 0;
		try {
			int y0 = bell.getY();
			for (int x = -16; x <= 56; x++) for (int z = -16; z <= 16; z++) {
				level.getChunkAt(bell.offset(x, 0, z));
				for (int y = -6; y <= 6; y++) {
					BlockPos pos = bell.offset(x, y, z);
					if (isFixtureCore(bell, pos)) continue;
					before.putIfAbsent(pos, level.getBlockState(pos));
					level.setBlock(pos, y < 0 ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
				}
			}
			// Pit: an enclosure of ten by thirteen cells walled three blocks high (a walker rises one block at most), open to the sky.
			for (int x = 30; x <= 40; x++) for (int z = -6; z <= 6; z++) {
				if (x != 30 && x != 40 && z != -6 && z != 6) continue;
				for (int y = 0; y <= 2; y++) {
					BlockPos pos = bell.offset(x, y, z);
					before.putIfAbsent(pos, level.getBlockState(pos));
					level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
				}
			}
			BlockPos p2 = bell.offset(-10, 0, 0);
			BlockPos p1 = bell.offset(12, 0, 0);
			BlockPos p4 = bell.offset(-12, 0, 3);
			BlockPos p3 = bell.offset(14, 0, 2);
			BlockPos q1 = bell.offset(34, 0, 0);
			BlockPos q2 = bell.offset(36, 0, 2);
			BlockPos s1 = bell.offset(46, 0, -10);
			for (BlockPos pillar : new BlockPos[] {p1, p2, p3, p4, q1, q2, s1}) {
				before.putIfAbsent(pillar, level.getBlockState(pillar));
				level.setBlock(pillar, Blocks.STONE.defaultBlockState(), 2);
			}
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("SURFACE STONE CHECK FAILED: villager missing");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			BlockPos start = SurfaceStone.standFor(level, p2);
			if (start == null) throw new IllegalStateException("SURFACE STONE CHECK FAILED: fixture pillar has no stand");
			worker.setPos(start.getX() + .5, start.getY(), start.getZ() + .5);
			worker.setOnGround(true);
			level.addFreshEntity(worker);
			long now = level.getGameTime() + 1_000L;
			JobState state = new JobState();
			var memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);

			SurfaceStone.resetMemory();
			List<BlockPos> first = SurfaceStone.candidatesForTest(ctx(level, worker, state, memory, ref, now));
			check(first.size() == 4 && first.get(0).equals(p2) && first.get(1).equals(p4) && first.get(2).equals(p1) && first.get(3).equals(p3),
				"search offers the four exposed stones nearest to the worker, nearest first, got " + first); checks++;

			// A rejected cell condemns the cells around it, not just itself, until the memory runs out.
			SurfaceStone.rejectForTest(p1, now, 24_000L);
			check(SurfaceStone.inRejectedRegion(p3, now) && SurfaceStone.inRejectedRegion(p1.offset(0, 3, 0), now),
				"neighbours of a rejected quarry cell are rejected with it"); checks++;
			check(!SurfaceStone.inRejectedRegion(p2, now) && !SurfaceStone.inRejectedRegion(p1.offset(0, 8, 0), now),
				"cells beyond the region radius or above its height band stay available"); checks++;
			check(!SurfaceStone.inRejectedRegion(p3, now + 24_000L), "a rejected region expires, the terrain may change"); checks++;
			List<BlockPos> afterReject = SurfaceStone.candidatesForTest(ctx(level, worker, state, memory, ref, now));
			check(!afterReject.contains(p1) && !afterReject.contains(p3) && afterReject.contains(p2) && afterReject.contains(p4),
				"the search skips the whole rejected region and still offers the others, got " + afterReject); checks++;
			SurfaceStone.resetMemory();

			// Real tick: nearest stone first. The worker stands at its stand, so it breaks it at once.
			boolean busy = SurfaceStone.tick(ctx(level, worker, state, memory, ref, now));
			check(busy && level.getBlockState(p2).isAir()
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.COBBLESTONE)) == 1,
				"the quarry breaks the nearest exposed stone and hands out one cobblestone"); checks++;
			check(state.cooldownUntil == now + SurfaceStone.HAND_COOLDOWN_TICKS,
				"without a pickaxe the break is paced by the hand cooldown"); checks++;

			// Next stone: the one beside the worker (p4, 3 blocks away), not the next nearest to the bell (p1).
			now += 200L;
			busy = SurfaceStone.tick(ctx(level, worker, state, memory, ref, now));
			check(busy && p4.equals(SurfaceStone.targetFor(worker.getUUID())),
				"after a stone the next one is the one beside the worker, not the one nearest to the bell, got " + SurfaceStone.targetFor(worker.getUUID())); checks++;

			// An enclosure has no way home: the failed walk-back is remembered for the whole pit, not cell by cell.
			SurfaceStone.resetMemory();
			level.setBlock(p1, Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(p4, Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(p3, Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(s1, Blocks.AIR.defaultBlockState(), 2);
			BlockPos pitStand = SurfaceStone.standFor(level, q1);
			if (pitStand == null) throw new IllegalStateException("SURFACE STONE CHECK FAILED: pit pillar has no stand");
			worker.setPos(pitStand.getX() + .5, pitStand.getY(), pitStand.getZ() + .5);
			worker.setOnGround(true);
			now += 5_000L;
			List<BlockPos> pit = SurfaceStone.candidatesForTest(ctx(level, worker, state, memory, ref, now));
			check(pit.size() == 2 && pit.contains(q1) && pit.contains(q2), "only the two pit stones are left to offer, got " + pit); checks++;
			busy = SurfaceStone.tick(ctx(level, worker, state, memory, ref, now));
			check(!busy && level.getBlockState(q1).is(Blocks.STONE) && level.getBlockState(q2).is(Blocks.STONE),
				"a pit stone without a way home is not quarried"); checks++;
			check(SurfaceStone.inRejectedRegion(q2, now + 10L) && SurfaceStone.candidatesForTest(
					ctx(level, worker, state, memory, ref, now + 10L)).isEmpty(),
				"the pit's neighbour stone is rejected with it and the search offers nothing more"); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			SurfaceStone.resetMemory();
			for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 2);
		}
	}

	/** A resident who needs metal but carries no pickaxe takes one from the village toolshed instead of waiting for a tool nobody hands out. */
	public static int runMetalPickaxe(ServerLevel level, VillageRef ref, BlockPos bell) {
		Map<BlockPos, BlockState> before = new HashMap<>();
		Villager worker = null;
		try {
			BlockPos toolshed = bell.south(5);
			level.getChunkAt(toolshed);
			for (BlockPos pos : new BlockPos[] {toolshed, toolshed.below()}) before.putIfAbsent(pos, level.getBlockState(pos));
			level.setBlock(toolshed.below(), Blocks.STONE.defaultBlockState(), 2);
			level.setBlock(toolshed, Blocks.CHEST.defaultBlockState(), 2);
			if (!(level.getBlockEntity(toolshed) instanceof net.minecraft.world.Container shed))
				throw new IllegalStateException("SURFACE STONE CHECK FAILED: toolshed container missing");
			de.villagerplus.village.Specials.set(ref, de.villagerplus.build.Blueprints.TOOLSHED, toolshed);
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("SURFACE STONE CHECK FAILED: villager missing");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			worker.setPos(toolshed.getX() + .5, toolshed.getY(), toolshed.getZ() + 1.5);
			worker.setOnGround(true);
			level.addFreshEntity(worker);
			long now = level.getGameTime() + 1_000L;
			JobState state = new JobState();
			var memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			boolean busy = Mining.tick(ctx(level, worker, state, memory, ref, now), Mining.Need.METAL);
			check(!busy && JobUtil.count(worker.getInventory(), s -> s.is(Items.STONE_PICKAXE)) == 0,
				"without any pickaxe in the toolshed or the village stock a metal miner still waits"); 
			shed.setItem(0, new net.minecraft.world.item.ItemStack(Items.STONE_PICKAXE));
			state.mineCheckAt = 0L;
			busy = Mining.tick(ctx(level, worker, state, memory, ref, now + 300L), Mining.Need.METAL);
			check(busy && JobUtil.count(worker.getInventory(), s -> s.is(Items.STONE_PICKAXE)) == 1
				&& JobUtil.count(shed, s -> s.is(Items.STONE_PICKAXE)) == 0,
				"a metal miner without a tool withdraws a real pickaxe from the village toolshed");
			return 2;
		} finally {
			if (worker != null) worker.discard();
			for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 2);
		}
	}

	/** The bell, its base, the chest and the base of the chest are the village fixture itself and stay as they are. */
	private static boolean isFixtureCore(BlockPos bell, BlockPos pos) {
		int dx = pos.getX() - bell.getX(), dy = pos.getY() - bell.getY(), dz = pos.getZ() - bell.getZ();
		return dz == 0 && ((dx == 0 && dy >= -1 && dy <= 0) || (dx == 3 && dy >= -1 && dy <= 0));
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory,
			VillageRef ref, long now) {
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("SURFACE STONE CHECK FAILED: " + message);
	}
}
