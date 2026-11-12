package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageRef;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic checks for the "deep" rescue rule: a resident that is more than eight blocks below the lowest village anchor is no longer
 * teleported on first sight (villages on slopes have terraces and gullies that deep, and residents walk back up on their own), only after
 * {@link JobManager#DEEP_STRAND_GRACE_TICKS} of continuous depth, while route failures without a walk-back still rescue at once. Terrain: a
 * flat stone platform around the fixture bell, a one-block-per-step ramp down to a lowland eleven blocks lower, later replaced by a sheer
 * wall. Fixtures are synthetic: green results are no proof of natural behaviour.
 */
public final class DeepStrandChecks {
	private DeepStrandChecks() { }

	private static final long T0 = 700_000L;
	private static final long GRACE = JobManager.DEEP_STRAND_GRACE_TICKS;

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager worker = null;
		int checks = 0;
		JobUtil.clearLongRoutes();
		JobManager.clearDeepWatches();
		try {
			level.getChunk(origin);
			level.getChunk(origin.offset(24, 0, 0));
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = -6; dx <= 32; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -16; dy <= 4; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.setBlock(p, dy == -1 && dx <= 9 ? stone : air, 3);
			}
			// Lowland: floor top at y-12 (feet y-11), x 10..32. The columns x 10..20 are a ramp (top y-2 down to y-12), one block per step.
			buildTerrain(level, origin, true);
			VillageRef ref = Checks090.fixtureForEquipment(level, origin);
			BlockPos lowland = origin.offset(26, -11, 0);
			worker = EntityTypes.VILLAGER.spawn(level, lowland, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("DEEP STRAND CHECK FAILED: could not spawn worker fixture");
			worker.addTag("vp090_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.name = "Deep";
			JobState state = JobManager.state(worker);
			java.util.UUID id = worker.getUUID();
			state.miningUntil = 0L;
			BlockPos street = origin.offset(3, 0, 0);

			require(de.villagerplus.village.Sites.walksBackTo(level, lowland, street), "fixture ramp can be walked up"); checks++;
			require(lowland.getY() < origin.getY() - JobManager.DEEP_MARGIN - 1, "fixture lowland is deeper than the deep margin"); checks++;

			// A: first sight of a deep resident is not a rescue.
			placeAt(worker, lowland);
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0)) && atSpot(worker, lowland),
				"a resident that is deep for the first time is not teleported"); checks++;

			// B: still deep, observed every 400 ticks: not rescued before the grace period is over.
			boolean early = false;
			for (long dt = 400L; dt < GRACE; dt += 400L) early |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + dt));
			early |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + GRACE - 1L));
			require(!early && atSpot(worker, lowland), "continuous depth below the grace period is not a rescue"); checks++;

			// C: grace period over -> rescued to the village street.
			boolean rescued = JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + GRACE));
			BlockPos now = worker.blockPosition();
			require(rescued && Math.abs(now.getX() - origin.getX()) <= 6 && Math.abs(now.getZ() - origin.getZ()) <= 6
				&& now.getY() >= origin.getY() - 1, "a resident that stays deep for the whole grace period is returned to the street"); checks++;
			require(JobManager.deepFor(id, T0 + GRACE + 1L) == 0L, "the deep timer is cleared by the rescue"); checks++;

			// D: leaving the depth between observations restarts the timer.
			long t1 = T0 + 10_000L;
			placeAt(worker, lowland);
			boolean any = JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1));
			for (long dt = 400L; dt <= 3_200L; dt += 400L) any |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1 + dt));
			placeAt(worker, origin.offset(11, -2, 0)); // one block below the street on the upper ramp: not deep
			any |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1 + 3_400L));
			placeAt(worker, lowland);
			long t1b = t1 + 3_600L;
			for (long dt = 0L; dt < GRACE; dt += 400L) any |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1b + dt));
			any |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1b + GRACE - 1L));
			require(!any && atSpot(worker, lowland),
				"a resident that climbed out of the depth and came back starts a fresh grace period"); checks++;
			require(JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1b + GRACE)),
				"the fresh grace period eventually runs out as well"); checks++;

			// E: a long gap without observations (night, sleeping indoors) restarts the timer too.
			long t2 = T0 + 30_000L;
			placeAt(worker, lowland);
			JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t2));
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t2 + GRACE + 5_000L)) && atSpot(worker, lowland),
				"after a gap of thousands of ticks without observation the first deep sighting does not rescue"); checks++;

			// F0: a deep resident at its task target (field, shore) with a way back up is never rescued, however long it stays.
			JobManager.clearDeepWatches();
			long t2b = T0 + 40_000L;
			placeAt(worker, lowland);
			state.target = lowland;
			boolean taskRescued = false;
			for (long dt = 0L; dt <= 3L * GRACE; dt += 400L) taskRescued |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t2b + dt));
			state.clearTarget();
			require(!taskRescued && atSpot(worker, lowland),
				"a deep resident at a task target with a walk-back is left alone for three grace periods"); checks++;

			// F: route failures with a walk-back (the ramp) leave the deep resident alone, as for every other resident.
			JobUtil.clearLongRoutes();
			JobManager.clearDeepWatches();
			long t3 = T0 + 50_000L;
			placeAt(worker, lowland);
			for (long dt : new long[] { 0L, 200L, 400L, 600L, 800L }) JobUtil.noteRouteFailure(id, lowland, t3 + dt);
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t3 + 800L)) && atSpot(worker, lowland),
				"a deep resident with route failures but a walk-back is left alone"); checks++;

			// G: replace the ramp with a sheer wall: route failures without any walk-back are rescued at once, not only after the grace period.
			buildTerrain(level, origin, false);
			JobUtil.clearLongRoutes();
			JobManager.clearDeepWatches();
			long t4 = T0 + 70_000L;
			placeAt(worker, lowland);
			require(!de.villagerplus.village.Sites.walksBackTo(level, lowland, street), "the wall removes the way back"); checks++;
			for (long dt : new long[] { 0L, 200L, 400L, 600L, 800L }) JobUtil.noteRouteFailure(id, lowland, t4 + dt);
			require(JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t4 + 800L)) && worker.getY() > origin.getY() - 2.0,
				"a deep resident behind a sheer wall with persistent route failures is rescued at once"); checks++;

			// H: behind the wall without route failures the grace period still ends in a rescue.
			JobUtil.clearLongRoutes();
			JobManager.clearDeepWatches();
			long t5 = T0 + 90_000L;
			placeAt(worker, lowland);
			boolean walled = false;
			for (long dt = 0L; dt < GRACE; dt += 400L) walled |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t5 + dt));
			require(!walled && JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t5 + GRACE)),
				"a resident walled in below the village is rescued once the grace period is over"); checks++;

			// I: the same task target without a way back up (sheer wall) is no exemption: rescued once the grace period is over.
			JobUtil.clearLongRoutes();
			JobManager.clearDeepWatches();
			long t6 = T0 + 110_000L;
			placeAt(worker, lowland);
			state.target = lowland;
			boolean trapEarly = false;
			for (long dt = 0L; dt < GRACE; dt += 400L) trapEarly |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t6 + dt));
			require(!trapEarly && JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t6 + GRACE)),
				"a task target in a walled-in lowland does not exempt the resident from the grace-period rescue"); checks++;
			state.clearTarget();

			// J: a resident that fell into the deep terrain (more than 3.5 blocks) cannot climb out: rescued at once, no grace period.
			JobUtil.clearLongRoutes();
			JobManager.clearDeepWatches();
			state.clearTarget();
			long t7 = T0 + 130_000L;
			placeAt(worker, lowland);
			JobManager.noteFall(id, t7 - 20L);
			require(JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t7)) && worker.getY() > origin.getY() - 2.0,
				"a deep resident that just fell in is rescued at once"); checks++;
			// K: an old fall (outside the window) does not shortcut the grace period.
			JobManager.clearDeepWatches();
			long t8 = T0 + 150_000L;
			placeAt(worker, lowland);
			JobManager.noteFall(id, t8 - JobManager.DEEP_FALL_WINDOW_TICKS - 100L);
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t8)) && atSpot(worker, lowland),
				"a fall long before the deep sighting is no shortcut"); checks++;
			return checks;
		} finally {
			JobUtil.clearLongRoutes();
			JobManager.clearDeepWatches();
			if (worker != null) worker.discard();
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
		}
	}

	/** Lowland floor top at y-12 for x 10..32; with {@code ramp} the columns x 10..20 descend one block per step from the platform edge. */
	private static void buildTerrain(ServerLevel level, BlockPos origin, boolean ramp) {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState stone = Blocks.STONE.defaultBlockState();
		for (int dx = 10; dx <= 32; dx++) for (int dz = -4; dz <= 4; dz++) {
			int top = ramp && dx <= 20 ? -2 - (dx - 10) : -12;
			for (int dy = -16; dy <= -2; dy++) level.setBlock(origin.offset(dx, dy, dz), dy <= top ? stone : air, 3);
		}
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory, VillageRef ref, long now) {
		state.cooldownUntil = 0L;
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static void placeAt(Villager worker, BlockPos pos) {
		worker.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}

	private static boolean atSpot(Villager worker, BlockPos spot) {
		return worker.blockPosition().distManhattan(spot) <= 1;
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("DEEP STRAND CHECK FAILED: " + label);
	}
}
