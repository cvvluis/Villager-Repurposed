package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Pure regression checks for the Wellbeing gate under the scheduled job cadence. */
public final class JobManagerFairnessChecks {
	private JobManagerFairnessChecks() { }

	public static int run() {
		int checks = 0;
		int intervalAtLoadFactorFour = Config.JOB_INTERVAL * 4;
		if (intervalAtLoadFactorFour != 40) {
			throw new IllegalStateException("G02 fairness check expected 40 ticks at load factor four, got "
				+ intervalAtLoadFactorFour);
		}
		checks++;
		BlockPos routeGoal = new BlockPos(12, 64, 0);
		BlockPos routeWaypoint = new BlockPos(5, 64, 0);
		Vec3 routeStart = new Vec3(0.5, 64.0, 0.5);
		if (!JobUtil.shouldReuseLongRoute(routeGoal, routeGoal, routeWaypoint, 20L, 40L, routeStart)
			|| !JobUtil.shouldReuseLongRoute(routeGoal, routeGoal, null, 20L, 40L, routeStart)
			|| JobUtil.shouldReuseLongRoute(routeGoal, routeGoal.offset(1, 0, 0), routeWaypoint, 20L, 40L, routeStart)
			|| JobUtil.shouldReuseLongRoute(routeGoal, routeGoal, routeWaypoint, 40L, 40L, routeStart)
			|| JobUtil.shouldReuseLongRoute(routeGoal, routeGoal, routeWaypoint, 20L, 40L, Vec3.atBottomCenterOf(routeWaypoint))) {
			throw new IllegalStateException("G02 fairness check failed to reuse only fresh, still-relevant long routes");
		}
		checks++;

		// Reproduce the scheduler's fixed cadence from an offset whose absolute-time gate
		// remains in one quarter. Each attempt must now receive the next per-villager quarter.
		long firstAttempt = 17L;
		int fixedAbsoluteQuarter = (int) ((firstAttempt / Config.JOB_INTERVAL) % 4);
		for (int workQuarters = 1; workQuarters <= 4; workQuarters++) {
			JobState state = new JobState();
			int allowed = 0;
			for (int attempt = 0; attempt < 16; attempt++) {
				long now = firstAttempt + (long) attempt * intervalAtLoadFactorFour;
				int absoluteQuarter = (int) ((now / Config.JOB_INTERVAL) % 4);
				if (absoluteQuarter != fixedAbsoluteQuarter) {
					throw new IllegalStateException("G02 fairness check cadence did not stay phase-locked");
				}
				if (JobManager.workAttemptAllowed(state, workQuarters)) allowed++;
			}
			int expected = workQuarters * 4;
			if (allowed != expected) {
				throw new IllegalStateException("G02 fairness check expected " + expected
					+ " allowed attempts for " + workQuarters + " quarters, got " + allowed);
			}
			checks++;
		}

		long sharedStartTick = 17L;
		BlockPos commonStart = new BlockPos(18000, 64, 18000);
		BlockPos commonGoal = commonStart.offset(8, 0, 0);
		long[] residentIds = { 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L };
		for (int phaseInterval : new int[] { Config.JOB_INTERVAL, intervalAtLoadFactorFour }) {
			List<SharedStartWorker> clustered = new ArrayList<>();
			Set<UUID> residents = new HashSet<>();
			Set<Integer> occupiedPhases = new HashSet<>();
			Set<Long> distinctDueTicks = new HashSet<>();
			JobManager.DueWorkQueue distributed = new JobManager.DueWorkQueue();
			for (long id : residentIds) {
				UUID resident = new UUID(0L, id);
				int phase = JobManager.allocateWorkPhase(resident, phaseInterval, occupiedPhases);
				long dueAt = JobManager.firstWorkAt(sharedStartTick, phaseInterval, phase);
				clustered.add(new SharedStartWorker(resident, commonStart, commonGoal, dueAt));
				if (!residents.add(resident) || dueAt < sharedStartTick || dueAt >= sharedStartTick + phaseInterval
					|| !distinctDueTicks.add(dueAt)) {
					throw new IllegalStateException("G02 fairness check synchronized residents sharing a start/work target");
				}
				distributed.add(queueEntry(dueAt, id));
			}
			Set<Long> dispatchedTicks = new HashSet<>();
			int dispatched = 0;
			for (long tick = sharedStartTick; tick < sharedStartTick + phaseInterval; tick++) {
				JobManager.Entry entry;
				while ((entry = distributed.pollDue(tick)) != null) {
					if (!dispatchedTicks.add(tick)) {
						throw new IllegalStateException("G02 fairness check dispatched multiple same-goal residents on one tick");
					}
					dispatched++;
				}
			}
			if (clustered.size() != residentIds.length || clustered.stream().anyMatch(worker ->
				!worker.start().equals(commonStart) || !worker.goal().equals(commonGoal))
				|| dispatched != residentIds.length || dispatchedTicks.size() != residentIds.length) {
				throw new IllegalStateException("G02 fairness check failed to stagger residents at a shared start point");
			}
		}
		checks++;

		JobState extremes = new JobState();
		extremes.workQuarterCursor = Integer.MAX_VALUE;
		if (!JobManager.workAttemptAllowed(extremes, 4) || extremes.workQuarterCursor != 0) {
			throw new IllegalStateException("G02 fairness check failed to normalize Integer.MAX_VALUE cursor");
		}
		extremes.workQuarterCursor = Integer.MIN_VALUE;
		if (!JobManager.workAttemptAllowed(extremes, 1) || extremes.workQuarterCursor != 1) {
			throw new IllegalStateException("G02 fairness check failed to normalize Integer.MIN_VALUE cursor");
		}
		checks++;
		if (!JobManager.shouldYieldForBudget(1, 11L, 10L) || JobManager.shouldYieldForBudget(0, 11L, 10L)
			|| JobManager.shouldYieldForBudget(1, 9L, 10L)) {
			throw new IllegalStateException("G02 fairness check failed to enforce the job budget after one completed villager");
		}
		checks++;

		JobManager.DueWorkQueue ordered = new JobManager.DueWorkQueue();
		JobManager.Entry first = queueEntry(20L, 1L);
		JobManager.Entry second = queueEntry(20L, 2L);
		JobManager.Entry later = queueEntry(30L, 3L);
		ordered.add(later);
		ordered.add(second);
		ordered.add(first);
		if (ordered.pollDue(19L) != null || ordered.pollDue(20L) != first || ordered.pollDue(20L) != second
			|| ordered.pollDue(29L) != null || ordered.pollDue(30L) != later) {
			throw new IllegalStateException("G02 due-work queue failed due-time ordering or stable tie ordering");
		}
		checks++;

		JobManager.DueWorkQueue fair = new JobManager.DueWorkQueue();
		JobManager.Entry[] pending = { queueEntry(0L, 1L), queueEntry(0L, 2L), queueEntry(0L, 3L) };
		for (JobManager.Entry entry : pending) fair.add(entry);
		for (int i = 0; i < pending.length; i++) {
			JobManager.Entry selected = fair.pollDue(i);
			if (selected != pending[i]) {
				throw new IllegalStateException("G02 due-work queue starved an older pending worker after budget yield");
			}
			selected.nextAt = 100L;
			selected.queueOrder = 4L + i;
			fair.add(selected);
		}
		if (fair.pollDue(pending.length) != null) {
			throw new IllegalStateException("G02 due-work queue ran a rescheduled worker before its next interval");
		}
		checks++;

		JobManager.DueWorkQueue cleanup = new JobManager.DueWorkQueue();
		JobManager.Entry retained = queueEntry(4L, 1L);
		JobManager.Entry unloaded = queueEntry(4L, 2L);
		cleanup.add(retained);
		cleanup.add(unloaded);
		if (!cleanup.remove(unloaded) || cleanup.remove(unloaded) || cleanup.pollDue(3L) != null
			|| cleanup.pollDue(4L) != retained || cleanup.pollDue(4L) != null) {
			throw new IllegalStateException("G02 due-work queue failed to remove an unloaded worker");
		}
		checks++;

		JobManager.Group lifecycle = new JobManager.Group();
		JobManager.Entry original = queueEntry(0L, 1L);
		lifecycle.queueLoad(original);
		lifecycle.applyPendingChanges();
		lifecycle.queueUnload(null);
		lifecycle.queueLoad(queueEntry(0L, 2L));
		JobManager.Entry replacement = queueEntry(0L, 3L);
		lifecycle.queueLoad(replacement);
		lifecycle.applyPendingChanges();
		if (lifecycle.entries.size() != 1 || lifecycle.entries.get(null) != replacement
			|| lifecycle.due.pollDue(0L) != replacement || !lifecycle.due.isEmpty()) {
			throw new IllegalStateException("G02 due-work queue retained a stale entry across unload/reload before tick cleanup");
		}
		checks++;
		return checks;
	}

	private static JobManager.Entry queueEntry(long nextAt, long order) {
		JobManager.Entry entry = new JobManager.Entry(null, null);
		entry.nextAt = nextAt;
		entry.queueOrder = order;
		return entry;
	}

	private record SharedStartWorker(UUID resident, BlockPos start, BlockPos goal, long dueAt) { }
}
