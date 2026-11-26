package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageRef;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
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
		TrapGuard.enabled = false;
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

			// L2: with the ramp the pit is walkable: neither the stand check nor the idle watch touches it, however long it stays.
			TrapGuard.enabled = true;
			TrapGuard.clear();
			JobUtil.clearRouteFailures(id);
			placeAt(worker, pit);
			require(TrapGuard.standWalksBack(level, pit), "with the ramp a pit-floor stand walks back"); checks++;
			boolean touched = false;
			for (long dt = 0L; dt <= 1_600L; dt += 200L) touched |= JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 200_000L + dt));
			require(!touched && atPit(worker, pit), "a resident that can walk back is never taken for trapped"); checks++;

			TrapGuard.enabled = false;

			// F2: a resident on a fence post (collision height 1.5). Where real navigation finds a complete way down (measured: 10 nodes on
			// a flat platform) the resident is left alone; walled in on every side it is brought back. Navigation, not only the block model, decides.
			JobUtil.clearLongRoutes();
			BlockPos post = origin.offset(4, 0, 3);
			level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
			worker.teleportTo(post.getX() + 0.5, post.getY() + 1.5, post.getZ() + 0.5);
			BlockPos perched = worker.blockPosition();
			for (long dt : new long[] { 0L, 200L, 400L, 600L, 800L }) JobUtil.noteRouteFailure(id, perched, T0 + 20_000L + dt);
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 20_800L)) && worker.getY() > post.getY() + 1.0,
				"a resident on a fence post with a complete vanilla path down is left alone"); checks++;
			for (Direction side : Direction.Plane.HORIZONTAL) for (int dy = 0; dy <= 3; dy++) {
				level.setBlock(post.relative(side).above(dy), stone, 3);
			}
			worker.teleportTo(post.getX() + 0.5, post.getY() + 1.5, post.getZ() + 0.5);
			JobUtil.clearLongRoutes();
			for (long dt : new long[] { 0L, 200L, 400L, 600L, 800L }) JobUtil.noteRouteFailure(id, perched, T0 + 30_000L + dt);
			require(JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 30_800L)) && worker.getY() < post.getY() + 1.0,
				"a resident on a fence post walled in on every side is brought back to the street"); checks++;
			for (Direction side : Direction.Plane.HORIZONTAL) for (int dy = 0; dy <= 3; dy++) level.setBlock(post.relative(side).above(dy), air, 3);
			level.setBlock(post, air, 3);

			// H: starter-seed grass on the pit floor is nearer to the forager than grass on the platform, but there is no way back out of
			// the pit: the forager must be sent to the platform grass, and the pit region is remembered as a trap.
			// The ramp from F is still in place: close it again so the pit has no way out.
			for (int dy = -3; dy <= -1; dy++) level.setBlock(origin.offset(14, dy, 0), stone, 3);
			for (int dy = -2; dy <= -1; dy++) level.setBlock(origin.offset(13, dy, 0), stone, 3);
			level.setBlock(origin.offset(12, -1, 0), stone, 3);
			JobUtil.clearLongRoutes();
			FarmerJob.clearSeedTraps();
			BlockPos pitGrass = origin.offset(17, -4, 0);
			BlockPos platformGrass = origin.offset(13, 0, 3);
			level.setBlock(pitGrass.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(pitGrass, Blocks.SHORT_GRASS.defaultBlockState(), 3);
			level.setBlock(platformGrass.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(platformGrass, Blocks.SHORT_GRASS.defaultBlockState(), 3);
			worker.teleportTo(origin.getX() + 16.5, origin.getY() - 4.0, origin.getZ() + 1.5);
			BlockPos chosen = FarmerJob.findSeedGrass(ctx(level, worker, state, memory, ref, T0 + 40_000L), ref);
			require(platformGrass.equals(chosen), "seed grass on a pit floor without a walk-back is skipped for the nearest grass that has one (chose "
				+ (chosen == null ? "none" : chosen.toShortString()) + ")"); checks++;
			require(FarmerJob.findSeedGrass(ctx(level, worker, state, memory, ref, T0 + 40_100L), ref) != null
				&& !pitGrass.equals(FarmerJob.findSeedGrass(ctx(level, worker, state, memory, ref, T0 + 40_200L), ref)),
				"the pit region stays remembered as a trap"); checks++;
			FarmerJob.clearSeedTraps();

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
			// I: a resident boxed into a small room behind a wall-to-wall counter (trapdoor row under a block row, as in the taiga
			// kitchen of the natural world: partial route of 5 nodes, which the old four-node limit never counted). The real long-route
			// attempts feed the strand watch and the walk-back model proves the room is sealed, so the resident is brought back.
			JobUtil.clearLongRoutes();
			JobUtil.clearRouteFailures(id);
			BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
			for (int dx = 2; dx <= 8; dx++) for (int dz = -6; dz <= -1; dz++) for (int dy = 0; dy <= 3; dy++) {
				boolean ring = dx == 2 || dx == 8 || dz == -6 || dz == -1;
				level.setBlock(origin.offset(dx, dy, dz), ring ? wall : air, 3);
			}
			level.setBlock(origin.offset(5, 0, -1), Blocks.SPRUCE_DOOR.defaultBlockState(), 3);
			level.setBlock(origin.offset(5, 1, -1), Blocks.SPRUCE_DOOR.defaultBlockState()
				.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
			for (int dx = 3; dx <= 7; dx++) {
				level.setBlock(origin.offset(dx, 0, -3), Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
					.setValue(net.minecraft.world.level.block.TrapDoorBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP), 3);
				level.setBlock(origin.offset(dx, 1, -3), Blocks.BARREL.defaultBlockState(), 3);
			}
			BlockPos room = origin.offset(3, 0, -5);
			BlockPos street = de.villagerplus.build.Builder.villageReturn(level, ref.bell());
			require(street != null && !de.villagerplus.village.Sites.walksBackTo(level, room, street),
				"the sealed back room has no way to the street in the walk-back model (street " + street + ", room " + room + ")"); checks++;
			worker.teleportTo(room.getX() + 0.5, room.getY(), room.getZ() + 0.5);
			require(JobUtil.countsAsStrandFailure(5) && JobUtil.countsAsStrandFailure(JobUtil.STRAND_MAX_ROUTE_NODES)
				&& JobUtil.countsAsStrandFailure(8) && !JobUtil.countsAsStrandFailure(9),
				"an unreachable partial route of up to eight nodes (the kitchen back room measured five) counts as a strand failure, a longer one does not"); checks++;
			for (long dt : new long[] { 0L, 200L, 400L, 600L, 800L }) JobUtil.noteRouteFailure(id, worker.blockPosition(), T0 + 60_000L + dt);
			require(JobUtil.routeStrandSuspected(id, worker.blockPosition(), T0 + 60_800L),
				"repeated long-route failures from inside the sealed room raise the strand suspicion"); checks++;
			require(JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 60_800L))
				&& worker.blockPosition().distManhattan(room) > 6,
				"the resident boxed into the sealed room is brought back to the street"); checks++;
			for (int dx = 2; dx <= 8; dx++) for (int dz = -6; dz <= -1; dz++) for (int dy = 0; dy <= 3; dy++) {
				level.setBlock(origin.offset(dx, dy, dz), air, 3);
			}
			// J: a villager pinned by vanilla's "go to potential job site" goal (natural run: plan owner in a doorway for 70k ticks) is
			// released from that claim, but only when it has not moved since the previous stall check.
			var brain = worker.getBrain();
			var site = net.minecraft.core.GlobalPos.of(level.dimension(), origin.offset(3, 0, -5));
			placeAt(worker, origin.offset(12, 0, 0));
			brain.setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE, site);
			JobUtil.dropPinningJobSite(ctx(level, worker, state, memory, ref, T0 + 70_000L));
			require(brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE),
				"the first stall check only records the position and keeps the job-site claim"); checks++;
			placeAt(worker, origin.offset(12, 0, 4));
			JobUtil.dropPinningJobSite(ctx(level, worker, state, memory, ref, T0 + 70_240L));
			require(brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE),
				"a worker that did move between two stall checks keeps its job-site claim"); checks++;
			JobUtil.dropPinningJobSite(ctx(level, worker, state, memory, ref, T0 + 70_480L));
			require(!brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE),
				"a worker that stayed put between two stall checks loses the unreachable job-site claim"); checks++;
			require(!de.villagerplus.block.ModBlocks.DORFTAFEL_GROSS.defaultBlockState()
					.isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND),
				"the village board is a thin panel but land paths must go around it (vanilla would route through it)"); checks++;
			// K: a resident who climbs back onto the same fence post after every step-off (natural run: nine to nineteen set-downs in a row,
			// about 2500 ticks each time) walks home fine while he stands on the ground, so the route checks never rescue him. Three step-offs
			// in a row from one spot do, whatever the walk-back model says; two do not, nor do step-offs far apart in time.
			JobUtil.clearLongRoutes();
			JobUtil.clearRouteFailures(id);
			placeAt(worker, origin.offset(9, 0, 5));
			BlockPos loopSpot = worker.blockPosition();
			JobUtil.notePerchNudge(id, loopSpot, T0 + 80_000L);
			JobUtil.notePerchNudge(id, loopSpot, T0 + 80_300L);
			require(!JobUtil.perchLoopSuspected(id, T0 + 80_310L)
				&& !JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 80_310L)) && worker.blockPosition().equals(loopSpot),
				"two step-offs from a perch are not a loop yet"); checks++;
			JobUtil.notePerchNudge(id, loopSpot.east(2), T0 + 80_600L);
			require(JobUtil.perchLoopSuspected(id, T0 + 80_610L), "the third step-off within 1500 ticks near one spot is a perch loop"); checks++;
			state.miningUntil = T0 + 82_000L;
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 80_610L)) && worker.blockPosition().equals(loopSpot),
				"a worker inside its mining window is exempt from the perch-loop rescue"); checks++;
			state.miningUntil = 0L;
			require(JobManager.rescueStranded(ctx(level, worker, state, memory, ref, T0 + 80_610L))
				&& worker.blockPosition().distManhattan(loopSpot) > 6,
				"a resident caught in a perch loop is brought back to the street although he can walk home from the ground"); checks++;
			require(!JobUtil.perchLoopSuspected(id, T0 + 80_620L), "the rescue clears the perch-loop record"); checks++;
			JobUtil.notePerchNudge(id, loopSpot, T0 + 90_000L);
			JobUtil.notePerchNudge(id, loopSpot, T0 + 91_700L);
			JobUtil.notePerchNudge(id, loopSpot, T0 + 93_400L);
			require(!JobUtil.perchLoopSuspected(id, T0 + 93_410L), "step-offs more than 1500 ticks apart are separate incidents, not a loop"); checks++;
			long t1 = T0 + 250_000L;
			// Remove the ramp again: the pit is a one-way drop for the idle-trap checks.
			for (int dy = -3; dy <= -1; dy++) level.setBlock(origin.offset(14, dy, 0), stone, 3);
			for (int dy = -2; dy <= -1; dy++) level.setBlock(origin.offset(13, dy, 0), stone, 3);
			level.setBlock(origin.offset(12, -1, 0), stone, 3);
			JobUtil.clearLongRoutes();
			// L: idle trap. A resident who chats, eats or idles in the pit requests no route, so the failed-route watch never fires. The
			// walk-back model and vanilla navigation both fail for it: after the grace it is brought back whatever it is doing; sooner it is not.
			TrapGuard.enabled = true;
			TrapGuard.clear();
			JobUtil.clearRouteFailures(id);
			placeAt(worker, pit);
			state.activity = "plaudert mit Test";
			require(!TrapGuard.standWalksBack(level, pit) && TrapGuard.standWalksBack(level, origin.east(2)),
				"a stand on the pit floor cannot walk back to the street, one beside the street can"); checks++;
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1)) && atPit(worker, pit),
				"the first walk-back failure only starts the clock"); checks++;
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1 + 200L)) && atPit(worker, pit),
				"a chatting resident in the pit is not rescued before the grace"); checks++;
			state.miningUntil = t1 + 2_000L;
			require(!JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1 + 400L)) && atPit(worker, pit),
				"a worker inside its mining window is exempt from the idle-trap rescue"); checks++;
			state.miningUntil = 0L;
			worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pit, 0.45F, 1));
			require(JobManager.rescueStranded(ctx(level, worker, state, memory, ref, t1 + 800L))
				&& Math.abs(worker.getX() - origin.getX()) <= 6 && worker.getY() >= origin.getY() - 1,
				"a resident idling in the pit without any route request is returned to the street after the grace"); checks++;
			require(worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty(),
				"the rescue clears the stale vanilla walk target into the pit"); checks++;
			TrapGuard.enabled = false;

			return checks;
		} finally {
			JobUtil.clearLongRoutes();
			TrapGuard.enabled = true;
			TrapGuard.clear();
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
