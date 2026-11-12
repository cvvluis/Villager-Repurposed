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
 * Synthetic checks for the route-less strand rescue (P1): a worker that keeps failing long routes (unreachable, at most
 * four nodes) from one spot is brought back to the village street, but only when the real blocks prove there is no way
 * home, and never while mining. The terrain is a flat stone platform around the fixture bell with a four-block deep pit;
 * everything is restored afterwards. Fixtures are synthetic: green results are no proof of natural behaviour.
 */
public final class StrandRescueChecks {
	private StrandRescueChecks() { }

	private static final long T0 = 500_000L;

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager worker = null;
		int checks = 0;
		JobUtil.clearLongRoutes();
		try {
			level.getChunk(origin);
			level.getChunk(origin.offset(24, 0, 0));
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = -6; dx <= 23; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -6; dy <= 4; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.setBlock(p, dy < 0 ? (dx <= 9 && dy == -1 ? stone : air) : air, 3);
			}
			// Solid block around the pit (x 10..22, z -4..4, y-5..y-1) with a 5x5 pit (x 15..19) carved four blocks deep.
			for (int dx = 10; dx <= 22; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -5; dy <= -1; dy++) {
				level.setBlock(origin.offset(dx, dy, dz), stone, 3);
			}
			for (int dx = 15; dx <= 19; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -4; dy <= -1; dy++) {
				level.setBlock(origin.offset(dx, dy, dz), air, 3);
			}
			VillageRef ref = Checks090.fixtureForEquipment(level, origin);
			BlockPos pit = origin.offset(17, -4, 0);
			worker = EntityTypes.VILLAGER.spawn(level, pit, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("STRAND RESCUE CHECK FAILED: could not spawn worker fixture");
			worker.addTag("vp090_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.name = "Strand";
			JobState state = JobManager.state(worker);
			java.util.UUID id = worker.getUUID();
			state.miningUntil = 0L;

			require(!de.villagerplus.village.Sites.walksBackTo(level, pit, origin.east(2)),
				"fixture pit cannot be climbed out of with step height one"); checks++;

			// A: no recorded failures, nothing happens.
			placeAt(worker, pit);
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0)) && atPit(worker, pit),
				"a worker in a pit without route failures is not touched"); checks++;

			// B/C: too few failures or too short a window.
			JobUtil.noteRouteFailure(id, pit, T0);
			JobUtil.noteRouteFailure(id, pit, T0 + 250L);
			require(!JobUtil.routeStrandSuspected(id, pit, T0 + 260L)
				&& !JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 260L)) && atPit(worker, pit),
				"two failures are not a strand"); checks++;
			JobUtil.noteRouteFailure(id, pit, T0 + 500L);
			require(!JobUtil.routeStrandSuspected(id, pit, T0 + 500L)
				&& !JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 500L)) && atPit(worker, pit),
				"three failures inside less than 600 ticks are not a strand yet"); checks++;

			// Mining exemption: a miner at work is never rescued, however many routes failed.
			JobUtil.noteRouteFailure(id, pit, T0 + 650L);
			state.miningUntil = T0 + 2_000L;
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 650L)) && atPit(worker, pit),
				"a worker inside its mining window is exempt from the route rescue"); checks++;
			state.miningUntil = 0L;

			// D: threshold reached, no way home -> rescued to the street near the bell.
			require(JobUtil.routeStrandSuspected(id, pit, T0 + 700L), "four failures over 700 ticks from one spot raise the suspicion"); checks++;
			boolean rescued = JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 700L));
			BlockPos now = worker.blockPosition();
			require(rescued && Math.abs(now.getX() - origin.getX()) <= 5 && Math.abs(now.getZ() - origin.getZ()) <= 5
				&& now.getY() >= origin.getY() - 1, "a pit-bound worker with a persistent route failure is returned to the village street"); checks++;
			require(!JobUtil.routeStrandSuspected(id, now, T0 + 710L), "the failure record is cleared by the rescue"); checks++;

			// E: quiet period after a rescue (1200 ticks) even when the failures start again.
			placeAt(worker, pit);
			for (long dt : new long[] { 800L, 1_050L, 1_300L, 1_550L }) JobUtil.noteRouteFailure(id, pit, T0 + dt);
			require(!JobUtil.routeStrandSuspected(id, pit, T0 + 1_600L)
				&& !JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 1_600L)) && atPit(worker, pit),
				"no second rescue inside the quiet period"); checks++;
			JobUtil.noteRouteFailure(id, pit, T0 + 1_800L);
			JobUtil.noteRouteFailure(id, pit, T0 + 2_000L);
			require(JobUtil.routeStrandSuspected(id, pit, T0 + 2_000L)
				&& JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 2_000L)),
				"after the quiet period the persistent failure is rescued again"); checks++;

			// F: with a one-block-per-step ramp the walk-back search finds the way: not stranded, record cleared.
			JobUtil.clearLongRoutes();
			for (int dy = -3; dy <= -1; dy++) level.setBlock(origin.offset(14, dy, 0), air, 3);
			for (int dy = -2; dy <= -1; dy++) level.setBlock(origin.offset(13, dy, 0), air, 3);
			level.setBlock(origin.offset(12, -1, 0), air, 3);
			placeAt(worker, pit);
			require(de.villagerplus.village.Sites.walksBackTo(level, pit, origin.east(2)), "the ramp makes the pit walkable"); checks++;
			for (long dt : new long[] { 0L, 200L, 400L, 600L, 800L }) JobUtil.noteRouteFailure(id, pit, T0 + 5_000L + dt);
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 5_800L)) && atPit(worker, pit)
				&& !JobUtil.routeStrandSuspected(id, pit, T0 + 5_810L),
				"a worker that can still walk home is left alone and its failure record is dropped"); checks++;

			// F2: a resident perched on a fence post. The block model may call that a walk home; real navigation finds no way off.
			JobUtil.clearLongRoutes();
			BlockPos post = origin.offset(4, 0, 3);
			level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
			worker.teleportTo(post.getX() + 0.5, post.getY() + 1.5, post.getZ() + 0.5);
			BlockPos perched = worker.blockPosition();
			for (long dt : new long[] { 0L, 200L, 400L, 600L, 800L }) JobUtil.noteRouteFailure(id, perched, T0 + 20_000L + dt);
			boolean perchRescued = JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 20_800L));
			require(perchRescued && worker.getY() < post.getY() + 1.0,
				"a resident perched on a fence post with persistent route failures is brought down even if the block model finds a way"); checks++;
			level.setBlock(post, Blocks.AIR.defaultBlockState(), 3);

			// G: moving more than two blocks restarts the count; stale failures restart it too.
			JobUtil.clearLongRoutes();
			JobUtil.noteRouteFailure(id, pit, T0 + 9_000L);
			JobUtil.noteRouteFailure(id, pit, T0 + 9_300L);
			JobUtil.noteRouteFailure(id, pit.east(5), T0 + 9_600L);
			JobUtil.noteRouteFailure(id, pit.east(5), T0 + 9_900L);
			require(!JobUtil.routeStrandSuspected(id, pit.east(5), T0 + 9_950L), "a move of five blocks restarts the failure count"); checks++;
			JobUtil.clearLongRoutes();
			JobUtil.noteRouteFailure(id, pit, T0 + 12_000L);
			JobUtil.noteRouteFailure(id, pit, T0 + 12_700L);
			JobUtil.noteRouteFailure(id, pit, T0 + 12_750L);
			require(!JobUtil.routeStrandSuspected(id, pit, T0 + 12_760L), "a gap above 400 ticks between failures restarts the count"); checks++;
			JobUtil.clearRouteFailures(id);
			require(!JobUtil.routeStrandSuspected(id, pit, T0 + 12_770L), "clearing the record removes the suspicion"); checks++;
			return checks;
		} finally {
			JobUtil.clearLongRoutes();
			if (worker != null) worker.discard();
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
		}
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory, VillageRef ref, long now) {
		state.cooldownUntil = 0L;
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static void placeAt(Villager worker, BlockPos pos) {
		worker.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}

	private static boolean atPit(Villager worker, BlockPos pit) {
		return worker.blockPosition().distManhattan(pit) <= 1;
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("STRAND RESCUE CHECK FAILED: " + label);
	}
}
