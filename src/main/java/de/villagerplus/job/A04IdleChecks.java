package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Staffing;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.WeatherData;

/**
 * Synthetic checks for the A04 predicate "residents do not stand permanently idle in the village centre": (1) a resident who
 * finds no work for longer than the job timeout is given the most urgently needed job for a day, while a resident who
 * does find work keeps his job and his idle timer is cleared (end to end through the real per-villager scheduler
 * {@link JobManager#tickForDevelopment}); (2) during the break routine every resident gets a distinct, reachable stand slot on
 * the ring around the village centre instead of waiting at the bell. The activation without a player (chunk lease) is checked by
 * {@code A04LeaseChecks}. Needs the overworld clock inside a work period (the dispatcher sets it). Synthetic fixture:
 * no natural evidence.
 */
public final class A04IdleChecks {
	private A04IdleChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		List<Villager> villagers = new ArrayList<>();
		WeatherData weather = level.getWeatherData();
		int oldClear = weather.getClearWeatherTime(), oldRain = weather.getRainTime(), oldThunder = weather.getThunderTime();
		boolean oldRaining = weather.isRaining(), oldThundering = weather.isThundering();
		float oldRainLevel = level.getRainLevel(1.0F), oldThunderLevel = level.getThunderLevel(1.0F);
		int checks = 0;
		Routine.clearGatherSearches();
		try {
			setWeather(level, weather, false);
			Phase phase = Phase.of(level.getOverworldClockTime());
			require(phase == Phase.WORK_A || phase == Phase.WORK_B, "the overworld clock must be inside a work period (is " + phase + ")");
			for (int dx = -24; dx <= 24; dx += 8) for (int dz = -24; dz <= 24; dz += 8) level.getChunk(origin.offset(dx, 0, dz));
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) for (int dy = -2; dy <= 5; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.removeBlockEntity(p);
				level.setBlock(p, dy < 0 ? stone : air, 3);
			}
			VillageRef ref = Checks090.fixtureForEquipment(level, origin);
			var librarian = ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace("librarian"));
			List<Villager> readers = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				Villager v = resident(level, ref, origin.offset(-1 + (i % 4), 0, 1 + i / 4), librarian);
				villagers.add(v);
				readers.add(v);
			}
			Villager busy = resident(level, ref, origin.offset(6, 0, 4), VillagerProfession.NONE);
			Villager idle = resident(level, ref, origin.offset(6, 0, 6), VillagerProfession.NONE);
			villagers.add(busy);
			villagers.add(idle);
			Villages.noteChunk(level.getChunkAt(origin));

			// A: a porter with carried surplus has work (walking to the village chest): never reassigned, idle timer cleared.
			JobState busyState = JobManager.state(busy);
			busyState.nextHousing = Long.MAX_VALUE;
			busy.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 20));
			busy.teleportTo(origin.getX() + 6.5, origin.getY(), origin.getZ() + 6.5);
			for (int attempt = 0; attempt < 8; attempt++) {
				busyState.idleSince = level.getGameTime() - 4001L;
				busyState.cooldownUntil = 0L;
				JobManager.tickForDevelopment(level, busy);
				if (busyState.idleSince == 0L) break;
			}
			require(busyState.idleSince == 0L && busy.getAttached(VillagerPlus.TEMPORARY_JOB) == null
				&& busy.getVillagerData().profession().is(VillagerProfession.NONE),
				"a resident that has work ('" + busyState.activity + "') keeps his job and his idle timer is cleared"); checks++;

			// B: a porter without anything to do for longer than the job timeout takes the most urgently needed job.
			JobState idleState = JobManager.state(idle);
			idleState.nextHousing = Long.MAX_VALUE;
			long reassignedAt = 0L;
			for (int attempt = 0; attempt < 12 && idle.getAttached(VillagerPlus.TEMPORARY_JOB) == null; attempt++) {
				idleState.idleSince = level.getGameTime() - 4001L;
				idleState.cooldownUntil = 0L;
				reassignedAt = level.getGameTime();
				JobManager.tickForDevelopment(level, idle);
			}
			var assignment = idle.getAttached(VillagerPlus.TEMPORARY_JOB);
			require(assignment != null && assignment.original().equals("minecraft:none") && assignment.target().equals("minecraft:farmer")
				&& assignment.until() == reassignedAt + 24000L && idle.getVillagerData().profession().is(VillagerProfession.FARMER)
				&& idleState.idleSince == 0L,
				"a resident idle beyond the job timeout takes the largest staffing gap (assignment " + assignment + ", activity '"
					+ idleState.activity + "')"); checks++;

			// C: the guards: no reassignment for a jester (nitwit), a resident with experience, or while a temporary job runs.
			Villager nitwit = resident(level, ref, origin.offset(4, 0, 8), VillagerProfession.NITWIT);
			villagers.add(nitwit);
			require(!Staffing.reassignIdle(level, ref, nitwit) && nitwit.getVillagerData().profession().is(VillagerProfession.NITWIT),
				"the village jester is never reassigned"); checks++;
			Villager experienced = readers.get(0);
			experienced.setVillagerXp(10);
			require(!Staffing.reassignIdle(level, ref, experienced) && experienced.getAttached(VillagerPlus.TEMPORARY_JOB) == null,
				"a resident with trading experience is never reassigned"); checks++;
			experienced.setVillagerXp(0);
			require(!Staffing.reassignIdle(level, ref, idle), "a temporary assignment is not stacked on another one"); checks++;

			// D: break routine: every resident receives a distinct stand slot on the ring (not the bell), reachable by real navigation.
			Routine.clearGatherSearches();
			List<Ctx> contexts = new ArrayList<>();
			long base = level.getGameTime() + 100L;
			for (Villager v : readers) {
				v.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
				JobState state = new JobState();
				contexts.add(new Ctx(level, v, state, v.getAttachedOrCreate(VillagerPlus.MEMORY), ref, base));
			}
			Job porter = new PorterJob();
			for (int round = 0; round < 8; round++) {
				boolean all = true;
				for (int i = 0; i < contexts.size(); i++) {
					Ctx base0 = contexts.get(i);
					Ctx c = new Ctx(level, base0.v(), base0.st(), base0.mem(), ref, base + round * 400L + i);
					contexts.set(i, c);
					if (c.st().target == null) Routine.run(c, porter, Phase.BREAK);
					if (c.st().target == null) all = false;
				}
				if (all) break;
			}
			List<BlockPos> stands = new ArrayList<>();
			for (Ctx c : contexts) {
				BlockPos target = c.st().target;
				require(target != null, "resident " + c.v().getUUID() + " found a break stand slot ('" + c.st().activity + "')");
				long dx = target.getX() - origin.getX(), dz = target.getZ() - origin.getZ();
				require(dx * dx + dz * dz >= 16L && dx * dx + dz * dz <= 144L,
					"the break stand " + target.toShortString() + " lies on the ring 4 to 12 blocks around the bell, not at the bell");
				stands.add(target);
			}
			checks++;
			for (int i = 0; i < stands.size(); i++) for (int j = i + 1; j < stands.size(); j++) {
				require(stands.get(i).distSqr(stands.get(j)) > 4.0, "stand slots " + stands.get(i).toShortString() + " and "
					+ stands.get(j).toShortString() + " are not crowded together");
			}
			checks++;
			return checks;
		} finally {
			Routine.clearGatherSearches();
			for (Villager v : villagers) {
				v.discard();
				Villages.forget(v.getUUID());
			}
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
			weather.setClearWeatherTime(oldClear);
			weather.setRainTime(oldRain);
			weather.setThunderTime(oldThunder);
			weather.setRaining(oldRaining);
			weather.setThundering(oldThundering);
			level.setRainLevel(oldRainLevel);
			level.setThunderLevel(oldThunderLevel);
		}
	}

	private static Villager resident(ServerLevel level, VillageRef ref, BlockPos at, ResourceKey<VillagerProfession> profession) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, at, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("A04 IDLE CHECK FAILED: could not spawn resident");
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), profession));
		villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
		villager.getAttachedOrCreate(VillagerPlus.MEMORY).name = "A04-" + villager.getUUID().toString().substring(0, 4);
		return villager;
	}

	private static void setWeather(ServerLevel level, WeatherData weather, boolean raining) {
		weather.setClearWeatherTime(24000);
		weather.setRainTime(24000);
		weather.setThunderTime(24000);
		weather.setRaining(raining);
		weather.setThundering(false);
		level.setRainLevel(raining ? 1.0F : 0.0F);
		level.setThunderLevel(0.0F);
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("A04 IDLE CHECK FAILED: " + label);
	}
}
