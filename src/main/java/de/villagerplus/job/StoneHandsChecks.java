package de.villagerplus.job;

import de.villagerplus.Compat;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Staffing;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fixture checks for the extra stone hands: demand over all active plans, idle residents claiming stone work (capped), the mason
 * without a mine breaking surface stone, the milestone hold rule and the temporary extra mason. Synthetic: green results are no
 * proof of natural behaviour.
 */
public final class StoneHandsChecks {
	private StoneHandsChecks() { }

	private static VillageData.BuildProject project(String type, BlockPos origin, List<String> wanted, boolean cleanupOnly) {
		return new VillageData.BuildProject(type, origin, 0, 0, 0, Optional.empty(), 1L, Optional.empty(), 0L, wanted,
			VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, cleanupOnly);
	}

	public static int run(ServerLevel level, VillageRef ref, BlockPos bell) {
		Map<BlockPos, BlockState> before = new HashMap<>();
		List<Villager> workers = new ArrayList<>();
		VillageData data = ref.data();
		List<VillageData.BuildProject> savedProjects = List.copyOf(data.buildProjects);
		Optional<VillageData.Mine> savedMine = data.mine;
		Map<String, Integer> savedQuotas = ref.bellEntity().getAttachedOrElse(VillagerPlus.QUOTAS, Map.of());
		Container chest = ref.chest(level);
		int checks = 0;
		StoneHands.resetForTest();
		SurfaceStone.resetMemory();
		try {
			// Residents are only found where the chunks around them are loaded as well.
			for (int dx = -32; dx <= 48; dx += 8) for (int dz = -32; dz <= 32; dz += 8) level.getChunk(bell.offset(dx, 0, dz));
			Villages.noteChunk(level.getChunkAt(bell));
			// 1. Pure rules.
			check(StoneHands.handsFor(0) == 1 && StoneHands.handsFor(16) == 1 && StoneHands.handsFor(17) == 2
				&& StoneHands.handsFor(33) == 3 && StoneHands.handsFor(1000) == StoneHands.MAX_HANDS, "hands wanted: one per 16 missing cobblestones, 1..3"); checks++;
			check(de.villagerplus.build.Builder.shouldHoldForSpecial(true, true, true, true)
				&& de.villagerplus.build.Builder.shouldHoldForSpecial(true, true, false, false)
				&& !de.villagerplus.build.Builder.shouldHoldForSpecial(true, true, false, true)
				&& !de.villagerplus.build.Builder.shouldHoldForSpecial(true, false, false, false)
				&& !de.villagerplus.build.Builder.shouldHoldForSpecial(false, true, false, false),
				"a mason without a plan of his own is released once the pending milestone has a running project; owners and waiting builders stay held"); checks++;

			check(Mining.usesKit(false, false) && Mining.usesKit(false, true) && !Mining.usesKit(true, false) && Mining.usesKit(true, true),
				"a stone hand follows the mine kit rules even without a pickaxe (supports and torches), a lone miner by hand keeps his old rule"); checks++;

			// 2. Demand over all active plans (the legacy view only shows the primary project).
			BlockPos granary = bell.offset(40, 0, 40);
			BlockPos storage = bell.offset(40, 0, 60);
			BlockPos old = bell.offset(40, 0, 80);
			data.buildProjects.clear();
			data.putBuildProject(project("granary", granary, List.of("minecraft:oak_planks#4"), false));
			data.putBuildProject(project("storage", storage, List.of("minecraft:cobblestone#20", "minecraft:cobblestone_stairs#4"), false));
			data.putBuildProject(project("house", old, List.of("minecraft:cobblestone#99"), true));
			data.syncPrimaryProjectView();
			chest.clearContent();
			int shortage = PlanStoneSupply.villageCobbleShortage(data, chest);
			check(shortage == 26, "cobblestone 20 + 4 stairs (6 cobblestone) of the second plan count, a cleanup-only project does not; got " + shortage); checks++;
			chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
			check(PlanStoneSupply.villageCobbleShortage(data, chest) == 16, "the public chest's cobblestone is deducted"); checks++;
			check(!PlanStoneSupply.needsCobble(level, data, chest) && Mining.needed(level, ref, data, chest, Mining.Need.STONE),
				"the stone supply starts for a plan that is not shown in the primary view"); checks++;
			check(PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 14) && !PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 3),
				"a load for the second plan is delivered, a handful is not"); checks++;
			chest.clearContent();

			// 3. Temporary extra mason (the player's quota is not touched).
			int pop = 12;
			check(Staffing.stoneExtraMasons(level, ref, pop) == 0, "no extra mason without a mine to work in"); checks++;
			data.mine = Optional.of(new VillageData.Mine(bell.offset(30, 0, 0), 0, 0, bell.getY() - 3, false));
			check(Staffing.stoneExtraMasons(level, ref, pop) == 1 && Staffing.stoneExtraMasons(level, ref, 30) == 2,
				"stone shortage and a mine allow one extra mason per 12 residents"); checks++;
			chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
			check(Staffing.stoneExtraMasons(level, ref, pop) == 0, "no extra mason while the chest covers the demand"); checks++;
			chest.clearContent();
			Staffing.setQuota(ref, "mason", 0);
			check(Staffing.stoneExtraMasons(level, ref, pop) == 0, "a mason quota of 0 % is respected: no extra masons"); checks++;
			ref.bellEntity().setAttached(VillagerPlus.QUOTAS, savedQuotas);
			int percentBefore = Staffing.quotaPercent(ref, "mason");

			var librarian = ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace("librarian"));
			var mason = ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace("mason"));
			for (String job : new String[] {"farmer", "fletcher", "butcher", "shepherd", "toolsmith", "weaponsmith", "armorer", "cleric",
				"librarian", "cartographer", "fisherman", "leatherworker"}) {
				Staffing.setQuota(ref, job, 0);
			}
			List<Villager> crew = new ArrayList<>();
			for (int i = 0; i < pop; i++) {
				level.getChunkAt(bell.offset(6 + i % 4, 0, 6 + i / 4));
				Villager v = EntityTypes.VILLAGER.spawn(level, bell.offset(6 + i % 4, 0, 6 + i / 4), EntitySpawnReason.COMMAND);
				if (v == null) throw new IllegalStateException("STONE HANDS CHECK FAILED: staffing fixture missing");
				crew.add(v);
				workers.add(v);
				v.addTag("vp090_fixture");
				v.setNoAi(true);
				v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), i == 0 ? mason : librarian).withLevel(2));
				v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
			}
			Villager idle = crew.get(1);
			data.mine = Optional.empty();
			var seen = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, x -> !de.villagerplus.village.Guests.isGuest(x));
			String census = seen.size() + " residents, masons " + seen.stream().filter(x -> x.getVillagerData().profession().is(VillagerProfession.MASON)).count()
				+ ", quota mason " + Staffing.quotaPercent(ref, "mason") + "%, farmer " + Staffing.quotaPercent(ref, "farmer") + "%";
			check(!Staffing.reassignIdle(level, ref, idle), "at quota (one mason of twelve) nobody is reassigned (" + census + ")"); checks++;
			data.mine = Optional.of(new VillageData.Mine(bell.offset(30, 0, 0), 0, 0, bell.getY() - 3, false));
			check(Staffing.reassignIdle(level, ref, idle), "stone shortage with a mine: an idle resident helps out as a temporary extra mason"); checks++;
			var assignment = idle.getAttached(VillagerPlus.TEMPORARY_JOB);
			String expected = Compat.preferredKey(level, "mason", mason).identifier().toString();
			check(assignment != null && assignment.original().equals("minecraft:librarian") && assignment.target().equals(expected)
				&& assignment.until() == level.getGameTime() + 24000L, "the extra mason is temporary: original job and one-day return are recorded"); checks++;
			check(Staffing.quotaPercent(ref, "mason") == percentBefore, "the player's mason quota is unchanged"); checks++;
			check(!Staffing.reassignIdle(level, ref, crew.get(2)), "only the one extra mason per 12 residents is assigned"); checks++;
			for (Villager v : crew) { v.discard(); Villages.forget(v.getUUID()); workers.remove(v); }
			ref.bellEntity().setAttached(VillagerPlus.QUOTAS, savedQuotas);
			data.mine = Optional.empty();

			// 4. Stone hands on a flat platform with three exposed stones, no mine.
			for (int x = -16; x <= 30; x++) for (int z = -16; z <= 16; z++) {
				level.getChunkAt(bell.offset(x, 0, z));
				for (int y = -6; y <= 6; y++) {
					BlockPos pos = bell.offset(x, y, z);
					if (isCore(bell, pos)) continue;
					before.putIfAbsent(pos, level.getBlockState(pos));
					level.setBlock(pos, y < 0 ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
				}
			}
			BlockPos p1 = bell.offset(12, 0, 0), p2 = bell.offset(12, 0, 8), p3 = bell.offset(12, 0, -8), p4 = bell.offset(-12, 0, 8);
			for (BlockPos pillar : new BlockPos[] {p1, p2, p3, p4}) level.setBlock(pillar, Blocks.STONE.defaultBlockState(), 2);
			Villager w1 = worker(level, workers, SurfaceStone.standFor(level, p1));
			Villager w2 = worker(level, workers, SurfaceStone.standFor(level, p2));
			Villager w3 = worker(level, workers, SurfaceStone.standFor(level, p3));
			long now = level.getGameTime() + 5_000L;
			JobState s1 = new JobState(), s2 = new JobState(), s3 = new JobState();
			// demand: 26 cobblestone missing -> two hands
			chest.clearContent();
			check(PlanStoneSupply.villageCobbleShortage(data, chest) == 26 && StoneHands.handsFor(26) == 2, "fixture shortage asks for two hands"); checks++;

			check(!StoneHands.work(ctx(level, w1, s1, ref, now)), "a resident who was never idle does not become a hand"); checks++;
			s1.idleSince = now - 100L;
			check(!StoneHands.work(ctx(level, w1, s1, ref, now)), "a resident idle for less than the claim threshold does not become a hand"); checks++;
			s1.idleSince = now - 1000L;
			check(StoneHands.work(ctx(level, w1, s1, ref, now)) && StoneHands.claimed(w1.getUUID(), now)
				&& level.getBlockState(p1).isAir() && JobUtil.count(w1.getInventory(), s -> s.is(Items.COBBLESTONE)) == 1,
				"an idle resident breaks exposed stone for the waiting plan and keeps his claim"); checks++;
			s2.idleSince = now - 1000L;
			check(StoneHands.work(ctx(level, w2, s2, ref, now + 1)) && StoneHands.claimCount(now + 1) == 2, "a second hand joins"); checks++;
			s3.idleSince = now - 1000L;
			check(!StoneHands.work(ctx(level, w3, s3, ref, now + 2)) && !StoneHands.claimed(w3.getUUID(), now + 2)
				&& level.getBlockState(p3).is(Blocks.STONE), "a third resident is not taken on: the shortage asks for two hands"); checks++;

			// A carrier whose claim is not (or no longer) there still delivers a useful load: the cap on hands never strands cobblestone.
			w3.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 14));
			StoneHands.markCarrierForTest(w3.getUUID());
			check(StoneHands.carrier(w3.getUUID()) && StoneHands.work(ctx(level, w3, s3, ref, now + 2)) && s3.activity.length() > 0
				&& level.getBlockState(p3).is(Blocks.STONE),
				"a former hand with a useful load on board delivers it although the cap of hands is reached (activity '" + s3.activity + "')"); checks++;
			w3.getInventory().clearContent();
			StoneHands.markCarrierForTest(w3.getUUID());
			check(!StoneHands.work(ctx(level, w3, s3, ref, now + 2)) && !StoneHands.carrier(w3.getUUID()),
				"a carrier with empty pockets is released"); checks++;

			Villager nit = worker(level, workers, SurfaceStone.standFor(level, p3));
			nit.setVillagerData(nit.getVillagerData().withProfession(level.registryAccess(),
				ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace("nitwit"))));
			JobState sn = new JobState();
			sn.idleSince = now - 1000L;
			StoneHands.resetForTest();
			check(!StoneHands.work(ctx(level, nit, sn, ref, now + 3)) && level.getBlockState(p3).is(Blocks.STONE), "a nitwit never lends a hand"); checks++;

			// The claim lapses without stone work, and a finished shortage releases the hand.
			StoneHands.resetForTest();
			s1.idleSince = now - 1000L;
			check(StoneHands.work(ctx(level, w1, s1, ref, now + 10)), "the hand works again (next stone)"); checks++;
			check(StoneHands.claimed(w1.getUUID(), now + 10 + StoneHands.CLAIM_TICKS - 1) && !StoneHands.claimed(w1.getUUID(), now + 10 + StoneHands.CLAIM_TICKS),
				"a claim lapses after the claim time without stone work"); checks++;
			data.buildProjects.clear();
			data.clearPlanViewForProjectSlot();
			w1.getInventory().clearContent();
			check(!StoneHands.work(ctx(level, w1, s1, ref, now + 20)) && !StoneHands.claimed(w1.getUUID(), now + 20),
				"without a shortage the hand stops and keeps no claim"); checks++;

			// 5. A mason without a mine breaks exposed stone for the plans (he used to idle on "no mine, no pickaxe").
			data.putBuildProject(project("storage", storage, List.of("minecraft:cobblestone#20"), false));
			data.syncPrimaryProjectView();
			SurfaceStone.resetMemory();
			level.setBlock(p2, Blocks.STONE.defaultBlockState(), 2);
			Villager m = worker(level, workers, SurfaceStone.standFor(level, p2));
			m.setVillagerData(m.getVillagerData().withProfession(level.registryAccess(), mason).withLevel(2));
			var method = MasonJob.class.getDeclaredMethod("mine", Ctx.class);
			method.setAccessible(true);
			boolean busy = (boolean) method.invoke(new MasonJob(), ctx(level, m, new JobState(), ref, now + 30));
			check(busy && level.getBlockState(p2).isAir() && JobUtil.count(m.getInventory(), s -> s.is(Items.COBBLESTONE)) == 1,
				"a mason without a mine quarries exposed stone for the waiting plan"); checks++;

			// 6. End to end through the real per-villager scheduler (needs a work period on the overworld clock and clear weather):
			// a librarian whose own job finds nothing to do is offered the stone work last and breaks the next stone.
			var weather = level.getWeatherData();
			int oldClear = weather.getClearWeatherTime(), oldRain = weather.getRainTime(), oldThunder = weather.getThunderTime();
			boolean oldRaining = weather.isRaining(), oldThundering = weather.isThundering();
			try {
				weather.setClearWeatherTime(24000);
				weather.setRainTime(24000);
				weather.setThunderTime(24000);
				weather.setRaining(false);
				weather.setThundering(false);
				Phase phase = Phase.of(level.getOverworldClockTime());
				check(phase == Phase.WORK_A || phase == Phase.WORK_B, "the overworld clock must be inside a work period (is " + phase + ")"); checks++;
				StoneHands.resetForTest();
				SurfaceStone.resetMemory();
				level.setBlock(p4, Blocks.STONE.defaultBlockState(), 2);
				Villager lib = worker(level, workers, SurfaceStone.standFor(level, p4));
				lib.setVillagerData(lib.getVillagerData().withProfession(level.registryAccess(), librarian).withLevel(2));
				lib.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
				JobState libState = JobManager.state(lib);
				libState.nextHousing = Long.MAX_VALUE;
				boolean worked = false;
				for (int attempt = 0; attempt < 16 && !worked; attempt++) {
					libState.idleSince = level.getGameTime() - 1000L;
					libState.cooldownUntil = 0L;
					libState.mineCheckAt = 0L;
					JobManager.tickForDevelopment(level, lib);
					worked = level.getBlockState(p4).isAir();
				}
				check(worked && JobUtil.count(lib.getInventory(), s -> s.is(Items.COBBLESTONE)) == 1,
					"the scheduler offers an idle librarian the stone work: he broke the stone (activity '" + libState.activity + "')"); checks++;
				// Negative: without a shortage the same resident stays idle.
				data.buildProjects.clear();
				data.clearPlanViewForProjectSlot();
				level.setBlock(p4, Blocks.STONE.defaultBlockState(), 2);
				StoneHands.resetForTest();
				SurfaceStone.resetMemory();
				boolean stillWorked = false;
				for (int attempt = 0; attempt < 8; attempt++) {
					libState.idleSince = level.getGameTime() - 1000L;
					libState.cooldownUntil = 0L;
					JobManager.tickForDevelopment(level, lib);
					stillWorked |= level.getBlockState(p4).isAir();
				}
				check(!stillWorked, "without a shortage the idle librarian does not quarry"); checks++;
			} finally {
				weather.setClearWeatherTime(oldClear);
				weather.setRainTime(oldRain);
				weather.setThunderTime(oldThunder);
				weather.setRaining(oldRaining);
				weather.setThundering(oldThundering);
			}
			return checks;
		} catch (RuntimeException | ReflectiveOperationException ex) {
			if (ex instanceof RuntimeException runtime) throw runtime;
			throw new IllegalStateException("STONE HANDS CHECK FAILED: " + ex, ex);
		} finally {
			for (Villager v : workers) { v.getInventory().clearContent(); v.discard(); Villages.forget(v.getUUID()); }
			StoneHands.resetForTest();
			SurfaceStone.resetMemory();
			data.buildProjects.clear();
			data.buildProjects.addAll(savedProjects);
			data.syncPrimaryProjectView();
			data.mine = savedMine;
			ref.bellEntity().setAttached(VillagerPlus.QUOTAS, savedQuotas);
			if (chest != null) chest.clearContent();
			for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 2);
		}
	}

	private static Villager worker(ServerLevel level, List<Villager> workers, BlockPos stand) {
		if (stand == null) throw new IllegalStateException("STONE HANDS CHECK FAILED: fixture pillar has no stand");
		Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		if (v == null) throw new IllegalStateException("STONE HANDS CHECK FAILED: villager missing");
		v.setNoAi(true);
		v.setInvulnerable(true);
		v.addTag("vp090_fixture");
		v.setPos(stand.getX() + .5, stand.getY(), stand.getZ() + .5);
		v.setOnGround(true);
		level.addFreshEntity(v);
		workers.add(v);
		return v;
	}

	private static boolean isCore(BlockPos bell, BlockPos pos) {
		int dx = pos.getX() - bell.getX(), dy = pos.getY() - bell.getY(), dz = pos.getZ() - bell.getZ();
		return dz == 0 && ((dx == 0 && dy >= -1 && dy <= 0) || (dx == 3 && dy >= -1 && dy <= 0));
	}

	private static Ctx ctx(ServerLevel level, Villager v, JobState state, VillageRef ref, long now) {
		VillagerMemory memory = v.getAttachedOrCreate(VillagerPlus.MEMORY);
		return new Ctx(level, v, state, memory, ref, now);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("STONE HANDS CHECK FAILED: " + message);
	}
}
