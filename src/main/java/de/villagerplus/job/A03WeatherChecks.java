package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.Vec3;

/** Focused A03 checks for weather policy, sheltered routing, transaction preservation and resume. */
public final class A03WeatherChecks {
	private A03WeatherChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		BlockPos shelterFeet = origin.offset(0, 1, 0);
		BlockPos workerFeet = origin.offset(8, 1, 0);
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		JobState st = new JobState();
		Villager villager = null;
		WeatherData weather = level.getWeatherData();
		int oldClearTime = weather.getClearWeatherTime();
		int oldRainTime = weather.getRainTime();
		int oldThunderTime = weather.getThunderTime();
		boolean oldRaining = weather.isRaining();
		boolean oldThundering = weather.isThundering();
		float oldRainLevel = level.getRainLevel(1.0F);
		float oldThunderLevel = level.getThunderLevel(1.0F);
		long start = Math.max(1000L, level.getGameTime() + 1000L);
		int checks = 0;
		try {
			put(level, before, shelterFeet.below(), Blocks.STONE.defaultBlockState());
			put(level, before, shelterFeet, Blocks.AIR.defaultBlockState());
			put(level, before, shelterFeet.above(), Blocks.AIR.defaultBlockState());
			put(level, before, shelterFeet.above(2), Blocks.OAK_PLANKS.defaultBlockState());
			put(level, before, workerFeet.below(), Blocks.STONE.defaultBlockState());
			put(level, before, workerFeet, Blocks.AIR.defaultBlockState());
			put(level, before, workerFeet.above(), Blocks.AIR.defaultBlockState());
			// Keep the whole walkway on the same feet/floor levels as both stands.
			for (int x = 1; x < workerFeet.getX() - origin.getX(); x++) {
				BlockPos path = origin.offset(x, 1, 0);
				put(level, before, path, Blocks.AIR.defaultBlockState());
				put(level, before, path.above(), Blocks.AIR.defaultBlockState());
				put(level, before, path.below(), Blocks.STONE.defaultBlockState());
			}
			BlockPos bellPos = origin.offset(0, 1, 8);
			put(level, before, bellPos.below(), Blocks.STONE.defaultBlockState());
			put(level, before, bellPos, Blocks.BELL.defaultBlockState());
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bellEntity)) {
				throw new IllegalStateException("A03 WEATHER CHECK FAILED: bell fixture has no BellBlockEntity");
			}
			VillageRef bellRef = new VillageRef(bellPos,
				bellEntity.getAttachedOrCreate(VillagerPlus.VILLAGE), bellEntity);
			long today = Math.floorDiv(level.getOverworldClockTime(), 24_000L);
			long previousDay = today - 1L;
			check(Villages.ringMorningBell(level, bellRef, previousDay), "morning cue rings the village's real bell once"); checks++;
			check(bellEntity.shaking && bellEntity.clickDirection == Direction.UP,
				"morning cue triggers the vanilla bell animation event as well as sound"); checks++;
			check(!Villages.ringMorningBell(level, bellRef, previousDay), "repeated morning ticks do not ring twice on the same day"); checks++;
			bellEntity.shaking = false;

			villager = EntityTypes.VILLAGER.spawn(level, workerFeet, EntitySpawnReason.COMMAND);
			if (villager == null) throw new IllegalStateException("A03 WEATHER CHECK FAILED: cannot spawn fixture villager");
			villager.addTag("vp090_fixture");
			villager.setInvulnerable(true);
			villager.setNoAi(true);
			villager.setPos(workerFeet.getX() + 0.5, workerFeet.getY(), workerFeet.getZ() + 0.5);
			villager.setOnGround(true);
			var memory = villager.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.homeBed = Optional.of(shelterFeet);
			villager.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 8));
			BlockPos haulProject = origin.offset(40, 0, 0);
			villager.getInventory().setItem(1, BuildHaul.markForCheck(
				new ItemStack(Items.OAK_PLANKS, 5),
				new Ctx(level, villager, st, memory, bellRef, start), haulProject, bellPos));

			Job outdoor = c -> false;
			Job indoor = new Job() {
				@Override public boolean primary(Ctx c) { return false; }
				@Override public boolean indoorsDuringStorm() { return true; }
			};
			setWeather(level, weather, false, false);
			villager.setPos(shelterFeet.getX() + 0.5, shelterFeet.getY(), shelterFeet.getZ() + 0.5);
			Routine.run(new Ctx(level, villager, st, memory, bellRef, start), outdoor, Phase.MORNING);
			check(bellEntity.shaking && bellEntity.clickDirection == Direction.UP,
				"entering the morning routine emits the real bell's vanilla animation event"); checks++;
			check(bellEntity.getAttachedOrElse(VillagerPlus.LAST_MORNING_BELL, Long.MIN_VALUE) == today,
				"entering the morning routine claims and rings the real village bell for the current day"); checks++;
			check(!Villages.ringMorningBell(level, bellRef, today),
				"the routine's morning cue stays deduplicated by the persisted daily marker"); checks++;
			villager.setPos(workerFeet.getX() + 0.5, workerFeet.getY(), workerFeet.getZ() + 0.5);
			check(!JobManager.shouldShelterForWeather(level, Phase.WORK_A, outdoor), "clear level weather leaves the ordinary outdoor schedule active"); checks++;
			check(JobManager.allowOutdoorSupportWork(false), "clear weather permits shared construction and market dispatch"); checks++;
			setWeather(level, weather, true, false);
			check(level.isRaining() && JobManager.shouldShelterForWeather(level, Phase.WORK_A, outdoor), "actual level rain pauses outdoor work"); checks++;
			check(!JobManager.allowOutdoorSupportWork(JobManager.adverseWeather(level.isRaining(), level.isThundering())),
				"rain blocks shared construction dispatch before a helper or material carrier can start exterior work"); checks++;
			check(!JobManager.shouldShelterForWeather(level, Phase.WORK_A, indoor), "actual rain permits declared indoor work"); checks++;
			check(JobManager.shouldShelterForWeather(level, Phase.WORK_A, indoor, workerFeet)
				&& !JobManager.shouldShelterForWeather(level, Phase.WORK_A, indoor, shelterFeet),
				"an indoor-capable role seeks shelter before working, then may continue under a real roof"); checks++;
			BlockPos exteriorTarget = origin.offset(24, 1, 0);
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
				new WalkTarget(Vec3.atBottomCenterOf(exteriorTarget), 0.45F, 0));
			check(JobManager.hasExposedWeatherTarget(level, villager),
				"a nominally indoor job cannot keep an outside destination while a storm is active"); checks++;
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
				new WalkTarget(Vec3.atBottomCenterOf(shelterFeet), 0.45F, 0));
			check(!JobManager.hasExposedWeatherTarget(level, villager),
				"a job destination inside the sheltered work area remains eligible"); checks++;
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			setWeather(level, weather, true, true);
			check(level.isThundering() && JobManager.shouldShelterForWeather(level, Phase.WORK_A, outdoor), "actual level thunder pauses outdoor work"); checks++;
			check(!JobManager.allowOutdoorSupportWork(JobManager.adverseWeather(level.isRaining(), level.isThundering())),
				"thunder blocks shared construction dispatch just like rain"); checks++;
			setWeather(level, weather, false, false);
			check(JobManager.shouldShelterForWeather(true, Phase.LUNCH, indoor), "routine deliveries and travel pause even for indoor professions"); checks++;
			check(JobManager.isShelteredStand(level, shelterFeet), "fixture contains a clear, roofed standing position"); checks++;
			int firstWindow = JobManager.advanceWeatherCandidateCursor(0, 4, 12);
			int secondWindow = JobManager.advanceWeatherCandidateCursor(firstWindow, 4, 12);
			check(firstWindow == 4 && JobManager.weatherCandidateIndex(firstWindow, 0, 12) == 4
				&& JobManager.weatherCandidateIndex(secondWindow, 0, 12) == 8,
				"successive bounded shelter searches advance past the previously rejected candidate prefix"); checks++;
			check(JobManager.weatherCandidateIndex(JobManager.advanceWeatherCandidateCursor(secondWindow, 4, 12), 0, 12) == 0,
				"the candidate cursor wraps only after it has passed the remaining farther candidates"); checks++;
			long budgetTick = start - 10L;
			int firstGrant = JobManager.reserveWeatherPathBudget(level, budgetTick, 24);
			int laterGrant = JobManager.reserveWeatherPathBudget(level, budgetTick, 24);
			int deniedGrant = JobManager.reserveWeatherPathBudget(level, budgetTick, 4);
			int nextTickGrant = JobManager.reserveWeatherPathBudget(level, budgetTick + 1, 4);
			check(firstGrant == 24 && laterGrant == 8 && deniedGrant == 0 && nextTickGrant == 4,
				"path checks share one level-wide per-tick budget and later requesters receive the next tick's fresh budget"); checks++;
			long retryA = JobManager.weatherSearchRetryDelay(new java.util.UUID(0L, 11L));
			long retryB = JobManager.weatherSearchRetryDelay(new java.util.UUID(0L, 12L));
			check(retryA >= 120L && retryA < 320L && retryB >= 120L && retryB < 320L && retryA != retryB,
				"stable per-villager retry jitter staggers repeated searches instead of starving later residents"); checks++;

			st.target = origin.offset(24, 1, 0);
			st.targetEntity = villager;
			st.walkStart = start - 100L;
			st.lastMoveAt = start - 80L;
			st.idleSince = start - 60L;
			st.bestDist = 18.0;
			st.stuckCount = 1;
			st.cooldownUntil = start + 50L;
			Ctx paused = new Ctx(level, villager, st, memory, null, start);
			BlockPos selected = JobManager.findWeatherShelter(paused);
			check(selected != null && JobManager.isShelteredStand(level, selected) && hasWalkingPath(paused, selected),
				"weather routing selects a real sheltered stand; " + routeDiagnostic(paused, selected)); checks++;
			JobManager.pauseForWeather(paused);
			check(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
				|| JobManager.isShelteredStand(level, villager.blockPosition()), "villager walks to shelter or waits under an existing roof"); checks++;
			check(st.target.equals(origin.offset(24, 1, 0)) && st.targetEntity == villager
				&& st.walkStart == start - 100L && st.lastMoveAt == start - 80L
				&& st.idleSince == start - 60L && st.bestDist == 18.0 && st.stuckCount == 1,
				"pause preserves task target and anti-stuck progress"); checks++;
			check(count(villager, Items.OAK_LOG) == 8 && st.cooldownUntil == start + 50L,
				"pause leaves carried materials and action cooldown untouched"); checks++;
			var pausedManifest = BuildHaul.manifest(new Ctx(level, villager, st, memory, bellRef, start), haulProject, java.util.List.of());
			check(BuildHaul.inTransit(pausedManifest).getOrDefault(Items.OAK_PLANKS, 0) == 5
				&& villager.getInventory().getItem(1).getCount() == 5,
				"weather pause preserves the real tagged construction cargo and its project reservation"); checks++;

			JobManager.resumeAfterWeather(new Ctx(level, villager, st, memory, null, start + 600L));
			check(!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET), "resume clears only the shelter route"); checks++;
			check(st.target.equals(origin.offset(24, 1, 0)) && st.targetEntity == villager
				&& st.walkStart == start + 500L && st.lastMoveAt == start + 520L
				&& st.idleSince == start + 540L && st.bestDist == 18.0 && st.stuckCount == 1,
				"resume continues the same task without charging shelter time to its timeout"); checks++;
			check(count(villager, Items.OAK_LOG) == 8 && st.cooldownUntil == start + 50L,
				"resume preserves the in-flight materials and cooldown"); checks++;
			var resumedManifest = BuildHaul.manifest(new Ctx(level, villager, st, memory, bellRef, start + 600L), haulProject, pausedManifest);
			check(BuildHaul.inTransit(resumedManifest).getOrDefault(Items.OAK_PLANKS, 0) == 5
				&& villager.getInventory().getItem(1).getCount() == 5,
				"weather recovery retains the same real cargo reservation for the saved construction project"); checks++;

			// The geometrically nearest covered cell is sealed inside a room; use a farther reachable roof instead.
			put(level, before, shelterFeet.above(2), Blocks.AIR.defaultBlockState());
			BlockPos sealedFeet = origin.offset(6, 1, 0);
			for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
				BlockPos floor = sealedFeet.offset(x, -1, z);
				put(level, before, floor, Blocks.STONE.defaultBlockState());
				put(level, before, floor.above(3), Blocks.OAK_PLANKS.defaultBlockState());
				if (Math.abs(x) == 1 || Math.abs(z) == 1) {
					put(level, before, floor.above(1), Blocks.STONE_BRICKS.defaultBlockState());
					put(level, before, floor.above(2), Blocks.STONE_BRICKS.defaultBlockState());
				} else {
					put(level, before, floor.above(1), Blocks.AIR.defaultBlockState());
					put(level, before, floor.above(2), Blocks.AIR.defaultBlockState());
				}
			}
			// Use one known adjacent step, inside the same loaded chunk as the worker. This isolates
			// shelter validation from a long synthetic corridor and from neighboring generated terrain.
			BlockPos reachableFeet = workerFeet.south();
			put(level, before, reachableFeet.below(), Blocks.STONE.defaultBlockState());
			put(level, before, reachableFeet, Blocks.AIR.defaultBlockState());
			put(level, before, reachableFeet.above(), Blocks.AIR.defaultBlockState());
			put(level, before, reachableFeet.above(2), Blocks.OAK_PLANKS.defaultBlockState());
			villager.setPos(workerFeet.getX() + 0.5, workerFeet.getY(), workerFeet.getZ() + 0.5);
			villager.setOnGround(true);
			memory.homeBed = Optional.of(sealedFeet);
			check(JobManager.isShelteredStand(level, sealedFeet), "sealed shelter candidate is genuinely covered inside the room"); checks++;
			Ctx alternateCtx = new Ctx(level, villager, st, memory, null, start + 700L);
			check(!JobManager.weatherPathReachable(alternateCtx, sealedFeet),
				"sealed covered candidate is blocked by its room; east wall feet="
					+ level.getBlockState(sealedFeet.east()) + " head=" + level.getBlockState(sealedFeet.east().above())
					+ "; " + routeDiagnostic(alternateCtx, sealedFeet)); checks++;
			put(level, before, reachableFeet, Blocks.STONE_BRICKS.defaultBlockState());
			check(level.getPathTypeCache().getOrCompute(level, reachableFeet)
				== net.minecraft.world.level.pathfinder.PathType.BLOCKED,
				"the path type cache reflects the newly blocked fixture cell"); checks++;
			check(!JobManager.safeReachableStand(alternateCtx, reachableFeet),
				"distributed pause routing rejects the adjacent stand obstructed at foot level"); checks++;
			check(!JobManager.weatherPathReachable(alternateCtx, reachableFeet),
				"an adjacent roofed cell obstructed at foot level is not a reachable shelter"); checks++;
			put(level, before, reachableFeet, Blocks.AIR.defaultBlockState());
			check(JobManager.safeReachableStand(alternateCtx, reachableFeet),
				"distributed pause routing accepts the known clear, reachable roofed stand"); checks++;
			check(JobManager.weatherPathReachable(alternateCtx, reachableFeet),
				"known clear and roofed alternate path passes the production route gate; "
					+ routeDiagnostic(alternateCtx, reachableFeet)); checks++;
			BlockPos selectedAlternate = null;
			for (int attempt = 0; attempt < 64 && selectedAlternate == null; attempt++) {
				selectedAlternate = JobManager.findWeatherShelter(new Ctx(level, villager, st, memory, null,
					alternateCtx.now() + attempt));
			}
			check(reachableFeet.equals(selectedAlternate) && JobManager.isShelteredStand(level, selectedAlternate)
				&& JobManager.weatherPathReachable(alternateCtx, selectedAlternate),
				"weather routing selects the separate reachable roof; expected=" + reachableFeet.toShortString() + ", selected="
					+ (selectedAlternate == null ? "null" : selectedAlternate.toShortString())
					+ ", knownAlternateReachable=" + JobManager.weatherPathReachable(alternateCtx, reachableFeet) + "; "
					+ routeDiagnostic(alternateCtx, selectedAlternate)); checks++;

			long routeStart = start + 700L;
			Ctx route = new Ctx(level, villager, st, memory, null, routeStart);
			setWeather(level, weather, true, false);
			check(JobManager.shouldShelterForWeather(level, Phase.WORK_A, outdoor),
				"rain pauses the outdoor job before routing to the reachable shelter"); checks++;
			JobManager.pauseForWeather(route);
			check(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
				&& villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow().getTarget()
					.currentBlockPosition().equals(reachableFeet),
				"rain assigns the reachable alternate shelter as the actual walking target; "
					+ routeDiagnostic(route, reachableFeet)); checks++;
			var shelterWalk = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow();
			setWeather(level, weather, true, true);
			check(level.isThundering() && JobManager.shouldShelterForWeather(level, Phase.WORK_A, outdoor),
				"thunder keeps the outdoor job paused after rain ends"); checks++;
			JobManager.pauseForWeather(new Ctx(level, villager, st, memory, null, routeStart + 20L));
			check(villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow() == shelterWalk,
				"rain-to-thunder transition preserves the same shelter route instead of restarting navigation"); checks++;
			check(st.target.equals(origin.offset(24, 1, 0)) && st.targetEntity == villager && count(villager, Items.OAK_LOG) == 8,
				"rain and thunder preserve the paused work target and carried logs"); checks++;
			put(level, before, reachableFeet.above(2), Blocks.AIR.defaultBlockState());
			JobManager.pauseForWeather(new Ctx(level, villager, st, memory, null, routeStart + 40L));
			check(!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
				"during thunder, a shelter that loses its roof is rejected instead of routing to an exposed cell"); checks++;
			put(level, before, reachableFeet.above(2), Blocks.OAK_PLANKS.defaultBlockState());
			long retryAt = routeStart + 40L;
			for (int attempt = 0; attempt < 64 && !villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET); attempt++) {
				retryAt += JobManager.weatherSearchRetryDelay(villager.getUUID());
				JobManager.pauseForWeather(new Ctx(level, villager, st, memory, null, retryAt));
			}
			check(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
				"while thunder continues, a periodic retry finds the restored reachable shelter; "
					+ routeDiagnostic(new Ctx(level, villager, st, memory, null, retryAt), reachableFeet)); checks++;
			setWeather(level, weather, false, false);
			check(!level.isRaining() && !level.isThundering()
				&& !JobManager.shouldShelterForWeather(level, Phase.WORK_A, outdoor),
				"the job resumes only after both rain and thunder end"); checks++;
			JobManager.resumeAfterWeather(new Ctx(level, villager, st, memory, null, routeStart + 600L));
			check(!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET), "clear weather clears the shelter route"); checks++;
			check(st.target.equals(origin.offset(24, 1, 0)) && st.targetEntity == villager
				&& st.walkStart == start + 1100L && st.lastMoveAt == start + 1120L
				&& st.idleSince == start + 1140L && st.bestDist == 18.0 && st.stuckCount == 1,
				"after rain and thunder, the same job resumes with shelter time excluded from its timeout"); checks++;
			check(count(villager, Items.OAK_LOG) == 8 && st.cooldownUntil == start + 50L,
				"weather completion preserves carried materials and the pending job cooldown"); checks++;
			return checks;
		} finally {
			if (villager != null) {
				JobManager.resumeAfterWeather(new Ctx(level, villager, st,
					villager.getAttachedOrCreate(VillagerPlus.MEMORY), null, start + 600L));
				villager.getNavigation().stop();
				villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				villager.discard();
			}
			weather.setClearWeatherTime(oldClearTime);
			weather.setRainTime(oldRainTime);
			weather.setThunderTime(oldThunderTime);
			weather.setRaining(oldRaining);
			weather.setThundering(oldThundering);
			level.setRainLevel(oldRainLevel);
			level.setThunderLevel(oldThunderLevel);
			for (var entry : before.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 3);
				level.getPathTypeCache().invalidate(entry.getKey());
			}
		}
	}

	private static void setWeather(ServerLevel level, WeatherData weather, boolean raining, boolean thundering) {
		weather.setClearWeatherTime(0);
		weather.setRainTime(1200);
		weather.setThunderTime(1200);
		boolean effectiveRain = raining || thundering;
		weather.setRaining(effectiveRain);
		weather.setThundering(thundering);
		// The server's live weather predicates use these interpolated Level values, not WeatherData flags alone.
		level.setRainLevel(effectiveRain ? 1.0F : 0.0F);
		level.setThunderLevel(thundering ? 1.0F : 0.0F);
	}

	private static void put(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		if (level.getBlockEntity(pos) != null) {
			throw new IllegalStateException("A03 WEATHER CHECK FAILED: fixture overlaps a block entity at " + pos.toShortString());
		}
		before.putIfAbsent(pos.immutable(), level.getBlockState(pos));
		level.setBlock(pos, state, 3);
		// This distant synthetic fixture may be in a loaded, non-ticking chunk. In that case
		// Level.setBlock does not call sendBlockUpdated, which normally invalidates this cache.
		level.getPathTypeCache().invalidate(pos);
	}

	private static int count(Villager villager, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
			ItemStack stack = villager.getInventory().getItem(i);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static String routeDiagnostic(Ctx ctx, BlockPos target) {
		BlockPos from = ctx.v().blockPosition();
		var path = target == null ? null : de.villagerplus.build.Builder.workPath(ctx, target);
		return "from=" + from.toShortString() + " pos=" + ctx.v().position() + " onGround=" + ctx.v().onGround()
			+ " chunkStatus=" + ctx.level().getChunkAt(from).getFullStatus()
			+ " floor=" + ctx.level().getBlockState(from.below())
			+ " target=" + (target == null ? "null" : target.toShortString())
			+ (target == null ? "" : " targetFloor=" + ctx.level().getBlockState(target.below())
				+ " feet=" + ctx.level().getBlockState(target) + " head=" + ctx.level().getBlockState(target.above())
				+ " roof=" + ctx.level().getBlockState(target.above(2)))
			+ " path=" + (path == null ? "null" : path.canReach() + "/" + pathNodes(path));
	}

	private static String pathNodes(net.minecraft.world.level.pathfinder.Path path) {
		StringBuilder nodes = new StringBuilder().append(path.getNodeCount()).append('[');
		for (int i = 0; i < path.getNodeCount(); i++) {
			if (i > 0) nodes.append(',');
			nodes.append(path.getNodePos(i).toShortString());
		}
		return nodes.append(']').toString();
	}

	private static boolean hasWalkingPath(Ctx ctx, BlockPos target) {
		if (target == null) return false;
		var path = de.villagerplus.build.Builder.workPath(ctx, target);
		return path != null && path.canReach() && path.getNodeCount() > 0;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("A03 WEATHER CHECK FAILED: " + message);
	}
}
