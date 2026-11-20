package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/**
 * Fixture checks for the stone economy rules: the walk to the mine is watched for stalls (a resident who stands in a doorway
 * on "geht zur Mine" is put back on the village street, then the walk is dropped for a while), and progress or a long pause
 * start a fresh watch. Synthetic: green results are no proof of natural behaviour.
 */
public final class StoneEconomyChecks {
	private StoneEconomyChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos bell) {
		Villager worker = null;
		Villager other = null;
		int checks = 0;
		Mining.resetApproachForTest(); Mining.resetArrivalsForTest();
		try {
			BlockPos entrance = bell.offset(30, 0, 0);
			level.getChunk(entrance);
			level.getChunk(bell);
			// A walkable village street around the bell: this is where stalled walkers are put back.
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				if (dx == 0 && dz == 0 || dx == 3 && dz == 0) continue; // the bell and the fixture chest stay
				level.setBlock(bell.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState(), 3);
				level.setBlock(bell.offset(dx, 0, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(bell.offset(dx, 1, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
			}
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("STONE ECONOMY CHECK FAILED: villager missing");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			BlockPos chestPos = ref.chestPos(level);
			BlockPos start = chestPos.offset(-12, 0, 6);
			worker.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
			worker.setOnGround(true);
			level.addFreshEntity(worker);
			JobState state = new JobState();
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			VillageData.Mine mine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 0, entrance.getY() - 3, false);
			ref.data().mine = Optional.of(mine);
			long now = level.getGameTime() + 10_000L;
			double far = 900.0;

			// 1. A fresh walk is never a stall, and standing still inside the allowance is not either.
			check(!Mining.approachStalled(ctx(level, worker, state, memory, ref, now), far), "the first look at a walk starts the watch only"); checks++;
			boolean early = false;
			for (long dt = 100L; dt <= Mining.APPROACH_STALL_TICKS; dt += 100L) {
				early |= Mining.approachStalled(ctx(level, worker, state, memory, ref, now + dt), far);
			}
			check(!early, "standing still for no longer than the stall limit is not a stall"); checks++;

			// 2. No progress for the whole limit: the resident is put on the village street and its walk target is cleared.
			worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
				new net.minecraft.world.entity.ai.memory.WalkTarget(entrance, 0.5F, 1));
			BlockPos street = de.villagerplus.build.Builder.villageReturn(level, bell);
			boolean first = Mining.approachStalled(ctx(level, worker, state, memory, ref, now + Mining.APPROACH_STALL_TICKS + 40L), far);
			check(first && worker.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()
				&& state.cooldownUntil > now + Mining.APPROACH_STALL_TICKS + 40L
				&& street != null && worker.blockPosition().distSqr(street) <= 4,
				"after the stall limit the resident is put on the village street (street " + street + ", now at " + worker.blockPosition() + ")"); checks++;

			// 3. Getting closer renews the allowance: a walk that advances a little at a time is never reset.
			long t = now + Mining.APPROACH_STALL_TICKS + 100L;
			Mining.approachStalled(ctx(level, worker, state, memory, ref, t), far);
			double near = far;
			boolean reset = false;
			for (int i = 1; i <= 8; i++) {
				near -= 40.0;
				reset |= Mining.approachStalled(ctx(level, worker, state, memory, ref, t + i * 150L), near);
			}
			check(!reset, "a walk that keeps getting closer is never reset, even when it takes longer than the stall limit"); checks++;

			// 4. A long pause between two looks (night, other work) starts a fresh watch instead of counting as a stall.
			long later = t + 8 * 150L + 20_000L;
			check(!Mining.approachStalled(ctx(level, worker, state, memory, ref, later), near),
				"a pause of many ticks between two looks does not count as standing still"); checks++;

			// 5. Three stalls in a row: no more teleports, the walk is dropped for a while and the mine route is cleared.
			Mining.resetApproachForTest(); Mining.resetArrivalsForTest();
			long u = later + 1_000L;
			int teleports = 0;
			boolean gaveUp = false;
			Mining.approachStalled(ctx(level, worker, state, memory, ref, u), far);
			for (int round = 1; round <= Mining.APPROACH_STALLS_BEFORE_GIVING_UP; round++) {
				BlockPos before = worker.blockPosition();
				state.mineCheckAt = 0;
				boolean handled = false;
				for (long dt = 100L; dt <= Mining.APPROACH_STALL_TICKS + 100L && !handled; dt += 100L) {
					u += 100L;
					handled = Mining.approachStalled(ctx(level, worker, state, memory, ref, u), far);
				}
				if (handled && state.mineCheckAt > u) gaveUp = true;
				else if (handled && !worker.blockPosition().equals(before)) teleports++;
				// The fixture resident does not walk: put it back where it was stuck so the next round stalls again.
				worker.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
				u += 10L;
				Mining.approachStalled(ctx(level, worker, state, memory, ref, u), far);
			}
			check(gaveUp && state.mineCheckAt > u - 100L,
				"after three stalls the walk is dropped for a while (gave up " + gaveUp + ", teleports " + teleports + ", mineCheckAt in " + (state.mineCheckAt - u) + ")"); checks++;

			// 7. Circling on the last stretch (it keeps getting a little closer, then falls back) is a stall too: the resident is set down at
			// the portal, near it, instead of circling for hours.
			Mining.resetApproachForTest();
			worker.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
			long v7 = now + 400_000L;
			double circle = 300.0;
			boolean circled = false;
			boolean earlyCircle = false;
			Mining.approachStalled(ctx(level, worker, state, memory, ref, v7), circle, mine);
			for (long dt = 50L; dt <= Mining.APPROACH_NEAR_BAND_TICKS + 100L && !circled; dt += 50L) {
				circle = circle > 120.0 ? circle - 10.0 : 300.0;
				circled = Mining.approachStalled(ctx(level, worker, state, memory, ref, v7 + dt), circle, mine);
				if (circled && dt < Mining.APPROACH_NEAR_BAND_TICKS) earlyCircle = true;
			}
			check(circled && !earlyCircle && worker.blockPosition().distSqr(start) > 4,
				"circling on the last stretch for longer than the band limit sets the resident down elsewhere (circled " + circled + ", early " + earlyCircle + ")"); checks++;

			// 6. Free, safe lanes but no path to them (a resident on a terrace above the mine): after the allowance the resident is put
			// on the village street instead of asking again and again for hours.
			Mining.resetNoLanePathForTest();
			ref.data().mine = Optional.of(new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 1, entrance.getY() - 3, false));
			for (int dx = -4; dx <= 6; dx++) for (int dz = -4; dz <= 4; dz++) {
				level.setBlock(entrance.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
				for (int dy = 0; dy <= 3; dy++) level.setBlock(entrance.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos perch = entrance.offset(-10, 12, 0);
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				level.setBlock(perch.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
				for (int dy = 0; dy <= 3; dy++) level.setBlock(perch.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
			}
			worker.teleportTo(perch.getX() + 0.5, perch.getY(), perch.getZ() + 0.5);
			long v = level.getGameTime() + 40_000L;
			boolean lanes = Mining.freeLaneExists(ctx(level, worker, state, memory, ref, v), ref.data().mine.get());
			check(lanes, "the fixture mine offers free safe lanes"); checks++;
			boolean movedEarly = Mining.noLanePathWatchdog(ctx(level, worker, state, memory, ref, v), ref.data().mine.get())
				|| Mining.noLanePathWatchdog(ctx(level, worker, state, memory, ref, v + Mining.NO_LANE_PATH_TICKS - 100L), ref.data().mine.get());
			check(!movedEarly && worker.blockPosition().distSqr(perch) <= 4, "a resident without a path to free lanes is left alone within the allowance"); checks++;
			boolean moved = Mining.noLanePathWatchdog(ctx(level, worker, state, memory, ref, v + Mining.NO_LANE_PATH_TICKS + 50L), ref.data().mine.get());
			check(moved && worker.blockPosition().distSqr(perch) > 100 && state.mineCheckAt > v,
				"after the allowance the resident is put on the village street (moved " + moved + ", now at " + worker.blockPosition() + ")"); checks++;

			// 7. Residents set back on the street do not pile up on one block: a second one finds a free stand beside the first.
			BlockPos streetSpot = de.villagerplus.build.Builder.villageReturn(level, bell);
			other = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (other == null) throw new IllegalStateException("STONE ECONOMY CHECK FAILED: second villager missing");
			other.setNoAi(true);
			other.setInvulnerable(true);
			other.addTag("vp090_fixture");
			other.setPos(streetSpot.getX() + 0.5, streetSpot.getY(), streetSpot.getZ() + 0.5);
			level.addFreshEntity(other);
			BlockPos spread = Mining.spreadArrival(ctx(level, worker, state, memory, ref, v), streetSpot);
			check(spread != null && !spread.equals(streetSpot) && Math.abs(spread.getY() - streetSpot.getY()) <= 1
				&& other.position().distanceToSqr(Vec3.atBottomCenterOf(spread)) >= 0.8 * 0.8,
				"a resident set back on the street stands beside the one already there (spot " + spread + ", street " + streetSpot + ")"); checks++;
			other.discard();
			BlockPos freeSpot = Mining.spreadArrival(ctx(level, worker, state, memory, ref, v), streetSpot);
			check(streetSpot.equals(freeSpot), "a free street spot is used as it is (got " + freeSpot + ")"); checks++;

			// 8. A walk with no progress for a while is handed to the long-route search; progress takes it back.
			Mining.resetApproachForTest();
			long w = v + 100_000L;
			Mining.approachStalled(ctx(level, worker, state, memory, ref, w), far);
			check(!Mining.approachNeedsAssist(ctx(level, worker, state, memory, ref, w + 100L)), "a fresh walk needs no help"); checks++;
			Mining.approachStalled(ctx(level, worker, state, memory, ref, w + 150L), far);
			check(Mining.approachNeedsAssist(ctx(level, worker, state, memory, ref, w + Mining.APPROACH_ASSIST_TICKS + 20L)),
				"a walk without progress for the assist time is helped"); checks++;
			Mining.approachStalled(ctx(level, worker, state, memory, ref, w + 260L), far - 200.0);
			check(!Mining.approachNeedsAssist(ctx(level, worker, state, memory, ref, w + 300L)), "progress takes the help away again"); checks++;

			// 9. An arrival short of the waypoint centre that does not move the route on sets the resident onto the centre.
			check(!Mining.arrivalNeedsSnapForTest(0.1 * 0.1, 7L, 1000L, 7L, 1000L + Mining.ARRIVAL_SNAP_TICKS),
				"a resident already on the centre is never moved"); checks++;
			check(!Mining.arrivalNeedsSnapForTest(0.6 * 0.6, 7L, 1000L, 7L, 1000L + Mining.ARRIVAL_SNAP_TICKS - 1L),
				"a fresh arrival short of the centre waits for the route to move on"); checks++;
			check(!Mining.arrivalNeedsSnapForTest(0.6 * 0.6, 8L, 1000L, 7L, 1000L + 500L),
				"an arrival at another waypoint starts its own wait"); checks++;
			check(Mining.arrivalNeedsSnapForTest(0.6 * 0.6, 7L, 1000L, 7L, 1000L + Mining.ARRIVAL_SNAP_TICKS),
				"an arrival 0.6 blocks short that stays put is set onto the centre"); checks++;

			// 10. In the world: a resident who stopped 0.6 blocks short of a safe waypoint stands on its centre after the wait.
			Mining.resetArrivalsForTest();
			JobState walkState = new JobState();
			long x0 = level.getGameTime() + 200_000L;
			worker.setPos(entrance.getX() + 0.5 + 0.6, entrance.getY(), entrance.getZ() + 0.5);
			boolean firstArrival = Mining.approachMineStep(ctx(level, worker, walkState, memory, ref, x0), entrance) == Mining.MineWalk.ARRIVED;
			boolean stillShort = worker.position().distanceToSqr(Vec3.atBottomCenterOf(entrance)) > 0.5 * 0.5;
			boolean secondArrival = Mining.approachMineStep(ctx(level, worker, walkState, memory, ref, x0 + Mining.ARRIVAL_SNAP_TICKS), entrance) == Mining.MineWalk.ARRIVED;
			check(firstArrival && stillShort && secondArrival
				&& worker.position().distanceToSqr(Vec3.atBottomCenterOf(entrance)) < 0.05 * 0.05,
				"an arrival that stays 0.6 blocks short is set onto the waypoint centre (first " + firstArrival + ", short " + stillShort
					+ ", second " + secondArrival + ", at " + worker.position() + ")"); checks++;
			return checks;
		} finally {
			if (other != null) other.discard();
			if (worker != null) worker.discard();
			ref.data().mine = Optional.empty();
			Mining.resetApproachForTest(); Mining.resetArrivalsForTest();
			Mining.resetNoLanePathForTest();
		}
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory, VillageRef ref, long now) {
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("STONE ECONOMY CHECK FAILED: " + message);
	}
}
