package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.JobManager;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic multi-tick check of the A04 activation without a player (Survival): a resident standing in a chunk that no
 * player keeps entity-ticking is not simulated; once the real per-villager scheduler runs for it
 * ({@link JobManager#tickForDevelopment}, which calls {@link Anchor#keepVillagerActive}) its chunk gets the temporary
 * resident lease and the resident really ticks, while an identical resident in an untouched chunk does not. The pure
 * predicate is covered by {@link A04SurvivalChecks}. The group returns -1 while waiting for the chunk to start ticking.
 * Synthetic fixture: no natural evidence.
 */
public final class A04LeaseChecks {
	private A04LeaseChecks() { }

	private static final class Run {
		final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager leased;
		Villager control;
		BlockPos leasedPos;
		BlockPos controlPos;
		long startedAt;
	}

	private static Run run;

	public static int run(ServerLevel level, BlockPos origin) {
		try {
			if (run == null) {
				run = new Run();
				start(level, origin, run);
				return -1;
			}
			Run r = run;
			if ((level.getGameTime() - r.startedAt) % 40 == 5) System.out.println("A04 LEASE WAIT t=" + (level.getGameTime() - r.startedAt)
				+ " ticking=" + level.isPositionEntityTicking(r.leasedPos) + " tickCount=" + r.leased.tickCount + " alive=" + r.leased.isAlive()
				+ " removed=" + r.leased.isRemoved() + " chunkLoaded=" + level.hasChunkAt(r.leasedPos) + " controlTicking=" + level.isPositionEntityTicking(r.controlPos) + " find=" + (Villages.find(level, r.leased) != null));
			if (!level.isPositionEntityTicking(r.leasedPos) || r.leased.tickCount == 0 || !r.leased.isAlive()) return -1;
			require(r.leased.isAlive() && level.isPositionEntityTicking(r.leasedPos) && r.leased.tickCount > 0,
				"the resident whose scheduler ran now ticks without any player nearby (after " + (level.getGameTime() - r.startedAt) + " ticks)");
			require(!level.isPositionEntityTicking(r.controlPos) && (r.control.tickCount == 0 || r.control.isRemoved()),
				"an identical villager that belongs to no village (so no lease) in an untouched chunk is not simulated");
			cleanup(level);
			return 2;
		} catch (Throwable failure) {
			cleanup(level);
			throw failure;
		}
	}

	private static void start(ServerLevel level, BlockPos origin, Run r) {
		BlockPos controlOrigin = origin.offset(400, 0, 0);
		level.getChunkAt(origin);
		level.getChunkAt(controlOrigin);
		for (BlockPos p : new BlockPos[] {origin, controlOrigin}) {
			for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) for (int dy = -2; dy <= 4; dy++) {
				BlockPos q = p.offset(dx, dy, dz);
				r.before.put(q, level.getBlockState(q));
				level.removeBlockEntity(q);
				level.setBlock(q, dy < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
		}
		VillageRef ref = Checks090.fixtureForEquipment(level, origin.offset(2, 0, 2));
		r.leasedPos = origin.offset(6, 0, 6);
		r.controlPos = controlOrigin.offset(6, 0, 6);
		r.leased = spawn(level, r.leasedPos, ref);
		r.control = spawn(level, r.controlPos, null);
		require(!level.isPositionEntityTicking(r.leasedPos) && !level.isPositionEntityTicking(r.controlPos),
			"precondition: neither fixture chunk is entity-ticking without a player or lease");
		r.startedAt = level.getGameTime();
		JobManager.state(r.leased).nextHousing = Long.MAX_VALUE;
		JobManager.tickForDevelopment(level, r.leased);
	}

	private static Villager spawn(ServerLevel level, BlockPos at, VillageRef ref) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, at, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("A04 LEASE CHECK FAILED: could not spawn resident");
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		if (ref != null) villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
		villager.getAttachedOrCreate(VillagerPlus.MEMORY).name = "A04Lease";
		return villager;
	}

	private static void cleanup(ServerLevel level) {
		Run r = run;
		run = null;
		if (r == null) return;
		if (r.leased != null) { r.leased.discard(); Villages.forget(r.leased.getUUID()); }
		if (r.control != null) { r.control.discard(); Villages.forget(r.control.getUUID()); }
		r.before.keySet().forEach(level::removeBlockEntity);
		r.before.forEach((p, s) -> level.setBlock(p, s, 3));
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("A04 LEASE CHECK FAILED: " + label);
	}
}
