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

/**
 * Fixture checks for the stone economy rules: the walk to the mine is watched for stalls (a resident who stands in a doorway
 * on "geht zur Mine" is put back on the village street, then the walk is dropped for a while), and progress or a long pause
 * start a fresh watch. Synthetic: green results are no proof of natural behaviour.
 */
public final class StoneEconomyChecks {
	private StoneEconomyChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos bell) {
		Villager worker = null;
		int checks = 0;
		Mining.resetApproachForTest();
		try {
			BlockPos entrance = bell.offset(30, 0, 0);
			level.getChunk(entrance);
			level.getChunk(bell);
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
				&& (street == null || worker.blockPosition().distSqr(street) <= 4),
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
			Mining.resetApproachForTest();
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
			return checks;
		} finally {
			if (worker != null) worker.discard();
			ref.data().mine = Optional.empty();
			Mining.resetApproachForTest();
		}
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory, VillageRef ref, long now) {
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("STONE ECONOMY CHECK FAILED: " + message);
	}
}
