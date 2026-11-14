package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/** A01 state-machine checks; synthetic context times avoid ticking or changing the world's clock.
 * Arrival below is simulated explicitly: this does not claim physical pathfinding end-to-end coverage. */
public final class AntiStuckChecks {
	private AntiStuckChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		check(Routine.nextGatherCursorAfterReject(3, 8) == 4
			&& Routine.nextGatherCursorAfterReject(7, 8) == 8
			&& Routine.nextGatherCursorAfterReject(8, 8) == 8,
			"a rejected pause stand advances to the next candidate instead of retrying the same blocked route");
		BlockPos fixture = ref.bell().offset(12, 6, 12);
		Villager v = EntityTypes.VILLAGER.spawn(level, fixture, EntitySpawnReason.COMMAND);
		if (v == null) throw new IllegalStateException("ANTI-STUCK CHECK FAILED: cannot spawn fixture");
		v.addTag("vp090_fixture");
		v.setInvulnerable(true);
		v.setNoAi(true);
		// A separate state leaves the scheduler's state and all real villagers untouched.
		JobState st = new JobState();
		int checks = 1;
		try {
			Vec3 origin = v.position();
			Vec3 target = origin.add(16.0, 0.0, 0.0);
			st.target = BlockPos.containing(target);
			st.targetEntity = v;
			long start = Math.max(1L, level.getGameTime());
			check(!JobUtil.approach(context(level, ref, v, st, start), target, 1.0)
				&& st.walkStart == start && st.stuckCount == 0,
				"first approach starts progress tracking without a failed attempt"); checks++;
			check(v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
				"first approach installs a walking target"); checks++;
			check(!JobUtil.approach(context(level, ref, v, st, start + 240), target, 1.0)
				&& st.stuckCount == 0, "240 ticks alone do not trigger a retry"); checks++;

			for (int attempt = 1; attempt <= 2; attempt++) {
				long now = start + attempt * 241L;
				check(!JobUtil.approach(context(level, ref, v, st, now), target, 1.0)
					&& st.stuckCount == attempt && st.target != null && st.targetEntity == v
					&& st.cooldownUntil == 0 && st.lastMoveAt == now
					&& v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
					"no-progress attempt " + attempt + " retries walking without aborting the task"); checks++;
				check(v.position().equals(origin), "retry " + attempt + " does not teleport the villager"); checks++;
			}

			long abortAt = start + 3 * 241L;
			check(!JobUtil.approach(context(level, ref, v, st, abortAt), target, 1.0)
				&& st.target == null && st.targetEntity == null && st.walkStart == 0
				&& st.bestDist == 0.0 && st.stuckCount == 0,
				"third consecutive no-progress attempt clears the task and retry state"); checks++;
			check(v.getNavigation().isDone() && !v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
				"abort stops navigation and erases the walking target"); checks++;
			check(v.position().equals(origin), "abort does not teleport the villager"); checks++;
			long alternateWorkAt = abortAt + 40;
			long taskResumeAt = abortAt + 4201;
			check(st.cooldownUntil == alternateWorkAt && st.idleUntil[0] == taskResumeAt && st.idleUntil[1] == taskResumeAt,
				"abort yields the global scheduler briefly while holding both failed job slots past role-reassignment time"); checks++;
			Ctx paused = context(level, ref, v, st, alternateWorkAt - 1);
			check(paused.now() < st.cooldownUntil && paused.idle(0) && paused.idle(1),
				"the short scheduler cooldown and both failed job slots remain blocked before retry"); checks++;
			Ctx alternateWork = context(level, ref, v, st, alternateWorkAt);
			check(alternateWork.now() >= st.cooldownUntil && alternateWork.idle(0) && alternateWork.idle(1),
				"shared village work can run while the abandoned primary and secondary tasks stay idle"); checks++;
			Ctx resumed = context(level, ref, v, st, taskResumeAt);
			check(resumed.now() >= st.cooldownUntil && !resumed.idle(0) && !resumed.idle(1),
				"both job slots reopen exactly when their target cooldown expires"); checks++;
			check(taskResumeAt - (alternateWorkAt + 160) > 4000,
				"the abandoned target stays unavailable long enough for JobManager to choose an alternate role if no shared work appears"); checks++;

			Vec3 reachable = origin.add(2.0, 0.0, 0.0);
			st.target = BlockPos.containing(reachable);
			check(!JobUtil.approach(resumed, reachable, 0.5) && st.walkStart == taskResumeAt
				&& st.stuckCount == 0 && v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
				"a new nearby task can start a fresh approach after cooldown"); checks++;
			// Simulate ordinary arrival without ticking AI; the check asserts completion/reset semantics only.
			v.setPos(reachable.x, reachable.y, reachable.z);
			st.stuckCount = 2;
			check(JobUtil.approach(context(level, ref, v, st, taskResumeAt + 1), reachable, 0.5)
				&& st.walkStart == 0 && st.bestDist == 0.0 && st.stuckCount == 0,
				"arrival completes approach and resets previous failures"); checks++;

			JobState timed = new JobState();
			Vec3 timedTarget = v.position().add(20.0, 0.0, 0.0);
			long timedStart = taskResumeAt + 100;
			check(!JobUtil.approach(context(level, ref, v, timed, timedStart), timedTarget, 1.0)
				&& v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
				"general approach timeout fixture starts with an active walk target"); checks++;
			long timeoutAt = timedStart + 1501;
			check(!JobUtil.approach(context(level, ref, v, timed, timeoutAt), timedTarget, 1.0)
			&& timed.target == null && timed.targetEntity == null && timed.cooldownUntil == timeoutAt + 40
			&& timed.idleUntil[0] == timeoutAt + 4201 && timed.idleUntil[1] == timeoutAt + 4201
				&& v.getNavigation().isDone() && !v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
				"general 1500-tick timeout clears navigation but yields to alternative work while the target cools down"); checks++;
			return checks;
		} finally {
			v.getNavigation().stop();
			v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			v.discard();
		}
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager v, JobState st, long now) {
		return new Ctx(level, v, st, v.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ANTI-STUCK CHECK FAILED: " + message);
	}
}
