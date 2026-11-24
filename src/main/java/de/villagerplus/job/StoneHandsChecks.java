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
import net.minecraft.world.SimpleContainer;
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
			check(StoneHands.handsForStall(5, 0L) == 1 && StoneHands.handsForStall(5, StoneHands.STALL_FULL_HANDS - 1) == 1
				&& StoneHands.handsForStall(5, StoneHands.STALL_FULL_HANDS) == StoneHands.MAX_HANDS
				&& StoneHands.handsForStall(0, 99999L) == 1, "a stalled plan with a stone shortage staffs every mine lane"); checks++;
			check(WoodHands.handsFor(0) == 1 && WoodHands.handsFor(8) == 1 && WoodHands.handsFor(9) == 2
				&& WoodHands.handsFor(1000) == WoodHands.MAX_HANDS, "wood hands wanted: one per 8 missing logs, 1..4"); checks++;
			// The log shortage counts the planks behind wished wooden shapes (a granary asks for dozens of stairs and nothing else).
			SimpleContainer noStock = new SimpleContainer(27);
			check(Woodcutting.planLogShortfall(List.of("minecraft:spruce_stairs#50"), noStock) == 20
				&& Woodcutting.planLogShortfall(List.of("minecraft:spruce_slab#50"), noStock) == 7
				&& Woodcutting.planLogShortfall(List.of("minecraft:cobblestone_stairs#50", "minecraft:stone_slab#9"), noStock) == 0,
				"fifty spruce stairs need twenty logs for thirteen whole recipe batches, fifty slabs seven, stone shapes none");
			checks++;
			SimpleContainer stairsStock = new SimpleContainer(27);
			stairsStock.setItem(0, new ItemStack(Items.SPRUCE_STAIRS, 20));
			stairsStock.setItem(1, new ItemStack(Items.SPRUCE_LOG, 5));
			check(Woodcutting.planLogShortfall(List.of("minecraft:spruce_stairs#50"), stairsStock) == 7
				&& Woodcutting.planLogShortfall(List.of("minecraft:spruce_planks#8", "minecraft:spruce_log#3", "minecraft:spruce_stairs#4"), noStock) == 7,
				"stairs already in the chest and logs in stock are deducted, planks and logs still count as before");
			checks++;
			SimpleContainer processedWood = new SimpleContainer(27);
			processedWood.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 32));
			check(Woodcutting.planLogShortfall(List.of("minecraft:spruce_stairs#20"), processedWood) == 0
				&& Woodcutting.planLogShortfall(List.of("minecraft:spruce_stairs#20", "minecraft:spruce_log#3"), processedWood) == 3,
				"matching processed planks cover shape inputs but cannot replace direct structural logs"); checks++;
			processedWood.setItem(0, new ItemStack(Items.OAK_PLANKS, 32));
			processedWood.setItem(1, new ItemStack(Items.OAK_LOG, 32));
			check(Woodcutting.planLogShortfall(List.of("minecraft:spruce_stairs#20"), processedWood) == 8,
				"another species' planks and logs cannot hide a spruce supply shortage"); checks++;
			processedWood.clearContent();
			processedWood.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 10));
			check(Woodcutting.planLogShortfall(List.of("minecraft:spruce_planks#8", "minecraft:spruce_stairs#4", "minecraft:spruce_log#3"), processedWood) == 4,
				"direct planks and shape inputs share each stored plank only once, leaving one conversion log plus three structural logs"); checks++;
			processedWood.clearContent();
			processedWood.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 2));
			boolean singleStairNeedsLog = Woodcutting.planLogShortfall(List.of("minecraft:spruce_stairs#1"), processedWood) == 1;
			processedWood.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 8));
			check(singleStairNeedsLog && Woodcutting.planLogShortfall(List.of("minecraft:spruce_stairs#5"), processedWood) == 1,
				"partial stair requests still fund whole loaded recipe batches before crediting stored planks"); checks++;
			processedWood.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 5));
			boolean fenceNeedsStickBatch = Woodcutting.planLogShortfall(List.of("minecraft:spruce_fence#1"), processedWood) == 1;
			processedWood.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 4));
			processedWood.setItem(1, new ItemStack(Items.STICK, 2));
			check(fenceNeedsStickBatch && Woodcutting.planLogShortfall(List.of("minecraft:spruce_fence#1"), processedWood) == 0,
				"fences budget the loaded stick batch and can use already stored sticks without demanding another tree"); checks++;
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

			VillageData deliveryData = new VillageData();
			deliveryData.putBuildProject(project("granary", granary, List.of("minecraft:cobblestone#5"), false));
			deliveryData.putBuildProject(project("storage", storage, List.of("minecraft:cobblestone#20"), false));
			deliveryData.syncPrimaryProjectView();
			SimpleContainer deliveryStock = new SimpleContainer(27), deliveryLoad = new SimpleContainer(8);
			deliveryLoad.setItem(0, new ItemStack(Items.COBBLESTONE, 9));
			check(PlanStoneSupply.stockGap(deliveryData, deliveryStock) == 64
				&& PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
				"a real nine-cobblestone load funds one project's current five-stone request despite the public64 buffer and another project's20"); checks++;
			deliveryLoad.setItem(0, new ItemStack(Items.COBBLESTONE, 6));
			check(!PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
				"a load that cannot fund one direct request after the two-stone reserve keeps the normal deep policy"); checks++;
			deliveryLoad.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
			deliveryLoad.setItem(1, new ItemStack(Items.DIORITE, 7));
			boolean mixedFoundationRejected = !PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad);
			deliveryLoad.setItem(0, ItemStack.EMPTY); deliveryLoad.setItem(1, new ItemStack(Items.DIORITE, 9));
			check(mixedFoundationRejected && !PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
				"mixed or variant-only loads cannot fund a raw foundation request without a proven substitutable window"); checks++;
			deliveryLoad.clearContent(); deliveryLoad.setItem(0, new ItemStack(Items.COBBLESTONE, 9));
			deliveryData.buildProjects.clear();
			deliveryData.putBuildProject(project("granary", granary, List.of("minecraft:cobblestone#5"), true));
			deliveryData.putBuildProject(project("storage", storage, List.of("minecraft:cobblestone#20"), false));
			deliveryData.syncPrimaryProjectView();
			check(!PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
				"a cleanup project's small request cannot trigger the short mine delivery"); checks++;
			deliveryData.buildProjects.clear();
			deliveryData.putBuildProject(project("granary", granary, List.of("minecraft:cobblestone#5"), false));
			deliveryData.putBuildProject(project("storage", storage, List.of("minecraft:cobblestone#20"), false));
			deliveryData.syncPrimaryProjectView();
			deliveryStock.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
			check(!PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
				"an already funded small request does not return a partial load for the larger project"); checks++;
			deliveryStock.clearContent();
			var deliveryStepsField = de.villagerplus.build.Builder.class.getDeclaredField("STEPS");
			deliveryStepsField.setAccessible(true);
			@SuppressWarnings("unchecked") var deliverySteps = (Map<Long, List<de.villagerplus.build.Step>>) deliveryStepsField.get(null);
			var oldDeliverySteps = deliverySteps.put(granary.asLong(), java.util.stream.IntStream.range(0, 5)
				.mapToObj(i -> de.villagerplus.build.Step.block(granary.above(3).east(i), Blocks.COBBLESTONE)).toList());
			try {
				var softProject = new VillageData.BuildProject("granary", granary, 0, 0, 0, Optional.empty(), 1L,
					Optional.empty(), 0L, List.of("minecraft:cobblestone#5"), VillageData.ProjectHaul.empty(), Optional.empty(),
					Optional.of(new de.villagerplus.build.ConstructionSite(granary, List.of(), List.of(0, 0, 0, 5, 5), true)),
					Optional.empty(), false, false);
				deliveryData.buildProjects.clear(); deliveryData.putBuildProject(softProject); deliveryData.syncPrimaryProjectView();
				deliveryLoad.clearContent(); deliveryLoad.setItem(0, new ItemStack(Items.COBBLESTONE, 2)); deliveryLoad.setItem(1, new ItemStack(Items.DIORITE, 7));
				check(PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
					"a prepared cached above-ground substitutable window can use the actual two-cobble seven-diorite load"); checks++;
				deliveryStock.setItem(0, new ItemStack(Items.DIORITE, 5));
				check(!PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
					"public variants already paying a substitutable request do not trigger another short trip"); checks++;
				deliveryStock.clearContent();
				deliveryLoad.clearContent();
				Villager markerWorker = worker(level, workers, bell.above());
				try {
					var markerContext = ctx(level, markerWorker, new JobState(), ref, level.getGameTime());
					deliveryLoad.setItem(0, BuildHaul.markForCheck(new ItemStack(Items.COBBLESTONE, 9), markerContext, storage, ref.chestPos(level)));
					check(!PlanStoneSupply.projectRequestFunded(deliveryData, deliveryStock, deliveryLoad),
						"another project's marked freight is never counted as a fundable mine return"); checks++;
				} finally {
					markerWorker.discard(); Villages.forget(markerWorker.getUUID()); workers.remove(markerWorker);
				}
			} finally {
				if (oldDeliverySteps == null) deliverySteps.remove(granary.asLong()); else deliverySteps.put(granary.asLong(), oldDeliverySteps);
			}

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
			data.planProgress = now; // the plan has just moved: the size of the shortage alone decides

			check(!StoneHands.work(ctx(level, w1, s1, ref, now)), "a resident who was never idle does not become a hand"); checks++;
			s1.idleSince = now - 100L;
			check(!StoneHands.work(ctx(level, w1, s1, ref, now)), "a resident idle for less than the claim threshold does not become a hand"); checks++;
			s1.idleSince = now - 1000L;
			check(StoneHands.work(ctx(level, w1, s1, ref, now)) && StoneHands.claimed(w1.getUUID(), now)
				&& level.getBlockState(p1).isAir() && JobUtil.count(w1.getInventory(), s -> s.is(Items.COBBLESTONE)) == 1,
				"an idle resident breaks exposed stone for the waiting plan and keeps his claim"); checks++;
			check(!Mining.holdLoadUnderground(ctx(level, w1, s1, ref, now), 80), "a resident on the surface never holds a load back: only the gallery does"); checks++;
			s2.idleSince = now - 1000L;
			check(StoneHands.work(ctx(level, w2, s2, ref, now + 1)) && StoneHands.claimCount(now + 1) == 2, "a second hand joins"); checks++;
			s3.idleSince = now - 1000L;
			check(!StoneHands.work(ctx(level, w3, s3, ref, now + 2)) && !StoneHands.claimed(w3.getUUID(), now + 2)
				&& level.getBlockState(p3).is(Blocks.STONE), "a third resident is not taken on: the shortage asks for two hands"); checks++;

			// A plan that has not moved for a while is served by the whole mine crew whatever the size of its shortage.
			data.planProgress = now - StoneHands.STALL_FULL_HANDS;
			Villager w4 = worker(level, workers, SurfaceStone.standFor(level, p4));
			JobState s4 = new JobState();
			s4.idleSince = now - 1000L;
			check(StoneHands.work(ctx(level, w4, s4, ref, now + 2)) && StoneHands.claimed(w4.getUUID(), now + 2)
				&& level.getBlockState(p4).isAir(), "a stalled plan takes on the third hand although its shortage asks for two"); checks++;
			data.planProgress = now;

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

			// A resident who carries logs the plan asks for hands them in at once although the cap of hands is reached
			// (a cap limits new felling, never a load already in the pockets).
			WoodHands.resetForTest();
			data.buildProjects.clear();
			data.putBuildProject(project("granary", granary, List.of("minecraft:spruce_stairs#50"), false));
			data.syncPrimaryProjectView();
			chest.clearContent();
			check(Woodcutting.planNeedsWood(data) && Woodcutting.planLogShortfall(data.wanted, chest) == 20 && WoodHands.handsFor(20) == 3,
				"a stairs-only wish list asks for timber (19 logs, three hands)"); checks++;
			Villager logCarrier = worker(level, workers, SurfaceStone.standFor(level, p3));
			logCarrier.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 12));
			WoodHands.markClaimsForTest(WoodHands.MAX_HANDS, now + 40);
			JobState logState = new JobState();
			logState.idleSince = now - 1000L;
			check(WoodHands.work(ctx(level, logCarrier, logState, ref, now + 40)) && logState.activity.length() > 0,
				"an unclaimed resident with plan logs in his pockets hands them in although every hand is taken (activity '" + logState.activity + "')"); checks++;
			WoodHands.resetForTest();

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
				// A restarted former helper has no idle timer or transient carrier claim, but its real stone must still reach a plan.
				data.putBuildProject(project("granary", granary, List.of("minecraft:cobblestone#40"), false));
				data.syncPrimaryProjectView();
				chest.clearContent();
				StoneHands.resetForTest();
				lib.getInventory().clearContent();
				lib.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 32));
				boolean startedReturn = false;
				for (int attempt = 0; attempt < 8 && !startedReturn; attempt++) {
					libState.idleSince = 0L;
					libState.cooldownUntil = 0L;
					JobManager.tickForDevelopment(level, lib);
					startedReturn = libState.activity.equals("bringt Waren zur Dorfkiste") || JobUtil.count(chest, stack -> stack.is(Items.COBBLESTONE)) > 0;
				}
				check(startedReturn, "the normal scheduler returns a former helper's thirty-two stones before unrelated profession work, without an idle claim [" + libState.activity + "]"); checks++;
				if (libState.target != null) lib.setPos(libState.target.getX() + .5, libState.target.getY(), libState.target.getZ() + .5);
				for (int attempt = 0; attempt < 8 && JobUtil.count(chest, stack -> stack.is(Items.COBBLESTONE)) < 32; attempt++) {
					libState.cooldownUntil = 0L;
					JobManager.tickForDevelopment(level, lib);
				}
				check(JobUtil.count(chest, stack -> stack.is(Items.COBBLESTONE)) == 32
					&& JobUtil.count(lib.getInventory(), stack -> stack.is(Items.COBBLESTONE)) == 0,
					"the scheduled hand-in physically conserves all thirty-two real stones"); checks++;
				chest.clearContent();
				lib.getInventory().setItem(0, BuildHaul.markForCheck(new ItemStack(Items.COBBLESTONE, 20),
					ctx(level, lib, libState, ref, level.getGameTime()), granary, ref.chestPos(level)));
				libState.idleSince = level.getGameTime() - 1000L;
				check(!StoneHands.deliverStrayPlanStone(ctx(level, lib, libState, ref, level.getGameTime()))
					&& !StoneHands.work(ctx(level, lib, libState, ref, level.getGameTime()))
					&& JobUtil.count(chest, stack -> stack.is(Items.COBBLESTONE)) == 0
					&& JobUtil.count(lib.getInventory(), stack -> BuildHaul.isMarkedCargo(stack)) == 20,
					"stray-stone recovery never consumes another project's reserved cargo"); checks++;
				data.buildProjects.clear();
				data.putBuildProject(project("granary", granary, List.of("minecraft:spruce_stairs#20"), false));
				data.syncPrimaryProjectView();
				chest.clearContent();
				lib.getInventory().clearContent();
				lib.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 6));
				lib.setVillagerData(lib.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
				before.putIfAbsent(p4.below(), level.getBlockState(p4.below()));
				before.putIfAbsent(p4, level.getBlockState(p4));
				level.setBlock(p4.below(), Blocks.FARMLAND.defaultBlockState(), 2);
				level.setBlock(p4, Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 7), 2);
				libState.clearTarget();
				WoodHands.resetForTest();
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
				check(WoodHands.deliverStrayPlanLogs(ctx(level, lib, libState, ref, level.getGameTime())),
					"six unclaimed plan logs receive a real hand-in offer without waiting for the profession's idle timer"); checks++;
				for (int attempt = 0; attempt < 16 && JobUtil.count(chest, stack -> stack.is(Items.SPRUCE_LOG)) < 6; attempt++) {
					libState.idleSince = 0L;
					libState.cooldownUntil = 0L;
					JobManager.tickForDevelopment(level, lib);
					if (libState.target != null && libState.target.distSqr(ref.chestPos(level)) <= 25)
						lib.setPos(libState.target.getX() + .5, libState.target.getY(), libState.target.getZ() + .5);
				}
				check(JobUtil.count(chest, stack -> stack.is(Items.SPRUCE_LOG)) == 6
					&& JobUtil.count(lib.getInventory(), stack -> stack.is(Items.SPRUCE_LOG)) == 0,
					"the normal scheduler hands in six logs from a completed side job before unrelated profession work"); checks++;
				chest.clearContent();
				var woodContext = ctx(level, lib, libState, ref, level.getGameTime());
				lib.getInventory().setItem(0, BuildHaul.markForCheck(new ItemStack(Items.SPRUCE_LOG, 5), woodContext, granary, ref.chestPos(level)));
				var strayLogs = WoodHands.strayPlanLogs(woodContext);
				check((strayLogs == null || !strayLogs.test(lib.getInventory().getItem(0)))
					&& !WoodHands.deliverStrayPlanLogs(woodContext) && !WoodHands.work(woodContext)
					&& JobUtil.count(lib.getInventory(), stack -> BuildHaul.isMarkedCargo(stack)) == 5,
					"stray-log hand-in, including the routine path, never takes reserved construction cargo"); checks++;
				libState.routinePhase = Phase.MORNING;
				libState.stage = 1;
				libState.depositBlockedUntil = 0L;
				Routine.run(woodContext, new RoadBuilderJob(), Phase.MORNING);
				if (libState.target != null) lib.setPos(libState.target.getX() + .5, libState.target.getY(), libState.target.getZ() + .5);
				libState.stage = 1;
				Routine.run(woodContext, new RoadBuilderJob(), Phase.MORNING);
				check(JobUtil.count(lib.getInventory(), BuildHaul::isMarkedCargo) == 5
					&& JobUtil.count(chest, BuildHaul::isMarkedCargo) == 0,
					"the real morning routine's generic road-builder produce branch preserves marked cargo"); checks++;
				BlockPos privateBoxPos = bell.offset(20, 0, -12);
				level.setBlock(privateBoxPos, Blocks.CHEST.defaultBlockState(), 2);
				var privateBox = (Container) level.getBlockEntity(privateBoxPos);
				var previousHomeChest = woodContext.mem().homeChest;
				woodContext.mem().homeChest = Optional.of(privateBoxPos);
				try {
					lib.setPos(privateBoxPos.getX() + 1.5, privateBoxPos.getY(), privateBoxPos.getZ() + .5);
					libState.routinePhase = Phase.EVENING;
					libState.stage = 2;
					Routine.run(woodContext, new FarmerJob(), Phase.EVENING);
					check(JobUtil.count(lib.getInventory(), BuildHaul::isMarkedCargo) == 5 && privateBox.isEmpty(),
						"the real evening routine preserves another project's freight instead of storing it as private property"); checks++;
				} finally {
					woodContext.mem().homeChest = previousHomeChest;
				}
				lib.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 5));
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(p4, "minecraft:spruce_log"));
				check(!WoodHands.deliverStrayPlanLogs(woodContext)
					&& JobUtil.count(lib.getInventory(), stack -> stack.is(Items.SPRUCE_LOG)) == 5,
					"an unfinished felling cursor keeps its normal tree-work cycle instead of starting a stray-log trip"); checks++;
				level.setBlock(p4.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
				level.setBlock(p4, Blocks.SPRUCE_LOG.defaultBlockState(), 2);
				lib.setPos(p4.getX() + 1.5, p4.getY(), p4.getZ() + .5);
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(p4, "minecraft:spruce_log").select(p4).withStage(6));
				libState.woodCheckAt = 0L;
				check(WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 100L)),
					"a saved partial tree gets a finite continuation offer before a new profession task"); checks++;
				WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 140L));
				var advancedWood = lib.getAttachedOrCreate(VillagerPlus.WOODCUTTER_WORK);
				check((advancedWood.stage() > 6 || advancedWood.removalPending()) && level.getBlockState(p4).is(Blocks.SPRUCE_LOG),
					"resuming the same partial tree advances real break work without replacing it with a new tree"); checks++;
				data.buildProjects.clear();
				data.syncPrimaryProjectView();
				check(!WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 160L))
					&& lib.getAttachedOrCreate(VillagerPlus.WOODCUTTER_WORK).equals(advancedWood),
					"after civic demand clears, early tree continuation yields to the profession without losing the cursor"); checks++;
				data.putBuildProject(project("granary", granary, List.of("minecraft:spruce_stairs#20"), false));
				data.syncPrimaryProjectView();
				chest.clearContent();
				chest.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 32));
				check(!WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 165L))
					&& lib.getAttachedOrCreate(VillagerPlus.WOODCUTTER_WORK).equals(advancedWood),
					"enough public planks for every requested stair end early felling priority even while the general log reserve is low"); checks++;
				chest.clearContent();
				data.buildProjects.clear();
				data.putBuildProject(project("granary", granary, List.of("minecraft:oak_log#1", "minecraft:spruce_log#1"), false));
				data.syncPrimaryProjectView();
				chest.setItem(0, new ItemStack(Items.OAK_LOG, 32));
				level.setBlock(p4, Blocks.OAK_LOG.defaultBlockState(), 2);
				var surplusOakWork = WoodcutterWork.empty().start(p4, "minecraft:oak_log").select(p4).withStage(6);
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, surplusOakWork);
				check(!WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 168L))
					&& lib.getAttachedOrCreate(VillagerPlus.WOODCUTTER_WORK).equals(surplusOakWork),
					"an oak cursor yields when only spruce remains deficient, despite oak also appearing in the requested materials"); checks++;
				chest.clearContent();
				level.setBlock(p4, Blocks.SPRUCE_LOG.defaultBlockState(), 2);
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, advancedWood);
				data.buildProjects.clear();
				data.putBuildProject(project("granary", granary, List.of("minecraft:spruce_stairs#20"), false));
				data.syncPrimaryProjectView();
				BlockPos crown = p4.above(6);
				before.putIfAbsent(crown, level.getBlockState(crown));
				level.setBlock(crown, Blocks.SPRUCE_LOG.defaultBlockState(), 2);
				var pendingWood = WoodcutterWork.empty().start(p4, "minecraft:spruce_log").select(crown)
					.claimRemoval(List.of(new ItemStack(Items.SPRUCE_LOG)), Blocks.SPRUCE_LOG);
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, pendingWood);
				lib.setPos(p4.getX() + 14.5, p4.getY(), p4.getZ() + .5);
				WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 170L));
				check(level.getBlockState(crown).is(Blocks.SPRUCE_LOG)
					&& lib.getAttachedOrCreate(VillagerPlus.WOODCUTTER_WORK).equals(pendingWood),
					"a distant resumed removal preserves its crown and unpaid drops while returning to the root"); checks++;
				lib.setPos(p4.getX() + 1.5, p4.getY(), p4.getZ() + .5);
				WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 175L));
				check(level.getBlockState(crown).isAir()
					&& lib.getAttachedOrCreate(VillagerPlus.WOODCUTTER_WORK).removalConfirmed(),
					"a tall crown can be removed from the reached root stand without approaching the crown itself"); checks++;
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, advancedWood);
				for (int slot = 0; slot < lib.getInventory().getContainerSize(); slot++) lib.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
				check(!WoodHands.resumeTree(ctx(level, lib, libState, ref, level.getGameTime() + 180L))
					&& lib.getAttachedOrCreate(VillagerPlus.WOODCUTTER_WORK).equals(advancedWood),
					"a full helper inventory yields for normal delivery work and preserves its unfinished tree cursor"); checks++;

				lib.getInventory().clearContent();
				lib.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
				chest.clearContent();
				chest.setItem(0, new ItemStack(Items.SPRUCE_LOG));
				level.setBlock(p4, Blocks.AIR.defaultBlockState(), 2);
				BlockPos post = bell.offset(24, 0, 12);
				level.setBlock(post.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
				level.setBlock(post, Blocks.AIR.defaultBlockState(), 2);
				var roadTask = new de.villagerplus.village.Roads.Repair(post, Blocks.SPRUCE_LOG.defaultBlockState(),
					post.east(), false, null, null, "vp_supply_check");
				libState.roadRepair = roadTask;
				libState.roadBuilding = false;
				libState.depositBlockedUntil = 0L;
				libState.cooldownUntil = 0L;
				lib.setPos(ref.chestPos(level).getX() + 1.5, ref.chestPos(level).getY(), ref.chestPos(level).getZ() + .5);
				check(RoadBuilderJob.work(ctx(level, lib, libState, ref, level.getGameTime() + 200L))
					&& JobUtil.count(lib.getInventory(), stack -> stack.is(Items.SPRUCE_LOG)) == 1,
					"the live road-lighting task physically withdraws its one post log [" + libState.activity + "; task=" + libState.roadRepair + "; stock=" + JobUtil.count(chest, stack -> stack.is(Items.SPRUCE_LOG)) + "; repairable=" + de.villagerplus.village.Roads.repairable(level, roadTask) + "]"); checks++;
				check(!WoodHands.deliverStrayPlanLogs(ctx(level, lib, libState, ref, level.getGameTime() + 240L)),
					"simultaneous civic stair demand cannot divert a live road task's fetched post log"); checks++;
				lib.setPos(post.getX() + 1.5, post.getY(), post.getZ() + .5);
				check(RoadBuilderJob.work(ctx(level, lib, libState, ref, level.getGameTime() + 240L))
					&& level.getBlockState(post).is(Blocks.SPRUCE_LOG)
					&& JobUtil.count(lib.getInventory(), stack -> stack.is(Items.SPRUCE_LOG)) == 0,
					"the following road attempt consumes the fetched log in the actual post placement"); checks++;

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
