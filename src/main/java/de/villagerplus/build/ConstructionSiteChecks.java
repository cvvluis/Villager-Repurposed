package de.villagerplus.build;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** B02 lifecycle, compatibility, persistence, worksite cleanup and material-conservation checks. */
public final class ConstructionSiteChecks {
	private ConstructionSiteChecks() { }

	private record SavedPlan(String type, Optional<BlockPos> origin, int dir, int step, int variant,
		Optional<BlockPos> source, long progress, long nextBuild, List<String> wanted,
		Optional<VillageData.Extra.BuilderPlan> owner, Optional<String> materialWait) {
		static SavedPlan of(VillageData data) {
			return new SavedPlan(data.planType, data.planOrigin, data.planDir, data.planStep, data.planVariant, data.planSource,
				data.planProgress, data.nextBuild, List.copyOf(data.wanted),
				data.extra.planBuilder.map(plan -> new VillageData.Extra.BuilderPlan(plan.owner, plan.unavailableSince)),
				data.extra.planMaterialWait);
		}
		void restore(VillageData data) {
			data.planType = type; data.planOrigin = origin; data.planDir = dir; data.planStep = step; data.planVariant = variant;
			data.planSource = source; data.planProgress = progress; data.nextBuild = nextBuild;
			data.wanted.clear(); data.wanted.addAll(wanted); data.extra.planBuilder = owner; data.extra.planMaterialWait = materialWait;
		}
	}

	public static int run(ServerLevel level, VillageRef ref) throws Exception {
		int passed = 0;
		VillageData data = ref.data();
		SavedPlan oldPlan = SavedPlan.of(data);
		ConstructionSite oldSite = ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION);
		Map<String, Integer> oldPolicies = ref.bellEntity().getAttached(VillagerPlus.STOCK_POLICIES);
		Container chest = ref.chest(level);
		if (chest == null) throw new IllegalStateException("CONSTRUCTION SITE CHECK FAILED: village chest missing");
		List<ItemStack> oldStock = new ArrayList<>();
		for (int i = 0; i < chest.getContainerSize(); i++) oldStock.add(chest.getItem(i).copy());
		Map<BlockPos, BlockState> oldBlocks = new LinkedHashMap<>();
		Villager setupBuilder = null;
		BlockPos origin = new BlockPos(ref.bell().getX() + 128,
			Math.min(280, ref.bell().getY() + 20), ref.bell().getZ() + 128);
		try {
			var design = Architect.design(Architect.Kind.GRANARY, 73L);
			Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
				Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS,
				Blocks.OAK_DOOR, Blocks.OAK_FENCE);
			List<Step> oldOrder = List.of(
				Step.block(origin.above(design.wallH()), Blocks.COBBLESTONE),
				Step.block(origin.above(), Blocks.OAK_PLANKS),
				Step.block(origin.below(), Blocks.COBBLESTONE),
				Step.free(origin.above(design.wallH() + 1), Blocks.LANTERN.defaultBlockState()),
				Step.block(origin, Blocks.OAK_LOG));
			check(ConstructionSite.forPlan(oldOrder, origin, design, palette, null) == oldOrder,
				"pre-feature plans retain their exact saved block cursor order"); passed++;
			List<Step> ordered = ConstructionSite.order(oldOrder, origin, design, palette);
			List<Integer> ends = ConstructionSite.phaseEnds(ordered, origin, design, palette);
			ConstructionSite phased = new ConstructionSite(origin, List.of(), ends, false, palette.ids());
			check(ordered.get(0).pos().equals(origin.below()) && ordered.get(1).pos().equals(origin)
				&& ordered.get(2).pos().equals(origin.above()) && ordered.get(3).pos().equals(origin.above(design.wallH()))
				&& ordered.get(4).pos().equals(origin.above(design.wallH() + 1)) && ends.equals(List.of(1, 2, 3, 4, 5))
				&& ConstructionSite.forPlan(oldOrder, origin, design, palette, phased).equals(ordered),
				"new plans retain a deterministic five-phase cursor and phase boundaries"); passed++;
			Architect.Design houseDesign = Architect.design(Architect.Kind.HOUSE, 73L);
			List<Step> houseSteps = Architect.build(houseDesign, palette, origin, Direction.EAST);
			Step bedFoot = houseSteps.stream().filter(step -> step.item() == null && ItemTags.BEDS.equals(step.tag())).findFirst().orElse(null);
			Step bedHead = bedFoot == null ? null : houseSteps.stream()
				.filter(step -> bedFoot.pos().equals(step.linkedTo())).findFirst().orElse(null);
			Step roofBlock = houseSteps.stream()
				.filter(step -> !step.free() && step.pos().getY() >= origin.getY() + houseDesign.wallH()).findFirst().orElse(null);
			List<Step> orderedHouse = ConstructionSite.order(houseSteps, origin, houseDesign, palette);
			List<Integer> houseEnds = ConstructionSite.phaseEnds(orderedHouse, origin, houseDesign, palette);
			ConstructionSite houseSite = new ConstructionSite(origin, List.of(), houseEnds, false, palette.ids());
			int interiorStart = houseEnds.get(3);
			int bedFootIndex = bedFoot == null ? -1 : orderedHouse.indexOf(bedFoot);
			int bedHeadIndex = bedHead == null ? -1 : orderedHouse.indexOf(bedHead);
			int roofIndex = roofBlock == null ? -1 : orderedHouse.indexOf(roofBlock);
			check(houseDesign.beds() > 0 && bedFootIndex >= interiorStart && bedHeadIndex >= interiorStart && roofIndex >= 0
				&& roofIndex < interiorStart && houseSite.phaseFor(bedFootIndex, orderedHouse.size()) == 4
				&& houseSite.phaseFor(bedHeadIndex, orderedHouse.size()) == 4,
				"the real Architect house plan places both required bed blocks in the interior phase after its roof"); passed++;
			check(phased.phaseFor(2, 5) == 2 && phased.phaseCompleted(3, 2) == 1
				&& phased.phaseTotal(2) == 1 && phased.phasePercent(3, 2) == 100,
				"phase progress derives from the persisted cursor at exact boundaries"); passed++;
			List<Step> longPlan = new ArrayList<>();
			for (int i = 0; i < 100; i++) longPlan.add(Step.block(origin.offset(i, 0, 1), Blocks.COBBLESTONE));
			int firstMaterialWindow = Builder.materialDemandWindowEnd(longPlan, 0);
			int secondMaterialWindow = Builder.materialDemandWindowEnd(longPlan, firstMaterialWindow);
			check(firstMaterialWindow == 48 && secondMaterialWindow == 96
				&& Builder.materialDemandWindowEnd(longPlan, secondMaterialWindow) == 100
				&& Builder.materialDemandWindowEnd(longPlan, 200) == 100,
				"large plans expose material requests in cursor-following batches and a stale cursor safely has an empty window"); passed++;
			List<Step> stagedPlan = new ArrayList<>();
			stagedPlan.add(Step.free(origin, Blocks.AIR.defaultBlockState()));
			stagedPlan.add(new Step(origin.above(), Items.STICK, null, 1, true, null, block -> Blocks.AIR.defaultBlockState()));
			for (int i = 0; i < 48; i++) stagedPlan.add(Step.block(origin.offset(i, 1, 1), Blocks.COBBLESTONE));
			check(Builder.materialDemandWindowEnd(stagedPlan, 0) == stagedPlan.size()
				&& Builder.materialDemandWindowEnd(longPlan, 20) == 68,
				"free and optional blocks do not consume the supply window, which remains anchored to the saved cursor"); passed++;

			List<BlockPos> prepTargets = prepTargets(origin);
			for (BlockPos target : prepTargets) setFixture(level, target, Blocks.AIR.defaultBlockState(), oldBlocks);
			for (BlockPos support : prepSupports(origin)) setFixture(level, support, Blocks.STONE.defaultBlockState(), oldBlocks);
			SimpleContainer prepStock = new SimpleContainer(2);
			prepStock.setItem(0, new ItemStack(Items.SCAFFOLDING, 8));
			prepStock.setItem(1, new ItemStack(Items.CRAFTING_TABLE, 1));
			List<Step> footprint = List.of(Step.block(origin.above(), Blocks.OAK_PLANKS));
			BlockPos builderPos = origin.offset(8, 1, 8);
			level.getChunkAt(builderPos);
			setupBuilder = EntityTypes.VILLAGER.spawn(level, builderPos, EntitySpawnReason.COMMAND);
			if (setupBuilder == null) throw new IllegalStateException("CONSTRUCTION SITE CHECK FAILED: cannot spawn setup builder");
			setupBuilder.setNoAi(true);
			JobState builderState = new JobState();
			builderState.activity = "construction setup check";
			Ctx builderContext = new Ctx(level, setupBuilder, builderState, new VillagerMemory(), ref, level.getGameTime() + 1);
			ConstructionSite prepared = ConstructionSite.prepare(level,
				new ConstructionSite(origin, List.of(), ends, false, palette.ids()), footprint, origin, Direction.EAST, prepStock, builderContext);
			int stagedScaffolds = (int) prepTargets.stream().filter(pos -> level.getBlockState(pos).is(Blocks.SCAFFOLDING)).count();
			int stagedWorkbenches = (int) prepTargets.stream().filter(pos -> level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)).count();
			String prepTrace = " [prepared=" + prepared.prepared() + "; trackedScaffolds=" + prepared.scaffoldCount()
				+ "; trackedWorkbench=" + (prepared.hasWorkbench() ? 1 : 0) + "; trackedTemporary=" + prepared.temporary().size()
				+ "; worldScaffolds=" + stagedScaffolds + "; worldWorkbenches=" + stagedWorkbenches
				+ "; inventory=" + inventorySummary(prepStock) + "]";
			check(prepared.prepared() && prepared.scaffoldCount() == 8 && prepared.hasWorkbench()
				&& itemCount(prepStock, Items.SCAFFOLDING) == 0 && itemCount(prepStock, Items.CRAFTING_TABLE) == 0,
				"the worksite visibly stages eight paid scaffold blocks and a paid workbench" + prepTrace); passed++;
			check(builderState.tool == Items.CRAFTING_TABLE && builderState.holding
				&& setupBuilder.getMainHandItem().is(Items.CRAFTING_TABLE),
				"the builder visibly holds the paid workbench item after the staged worksite setup"); passed++;
			ConstructionSite cleaned = ConstructionSite.cleanup(level, prepared, prepStock);
			check(cleaned.temporary().isEmpty() && itemCount(prepStock, Items.SCAFFOLDING) == 8
				&& itemCount(prepStock, Items.CRAFTING_TABLE) == 1,
				"top-down scaffold cleanup returns every temporary material exactly once"); passed++;

			BlockPos blockedOrigin = origin.offset(20, 0, 20);
		for (BlockPos pos : prepTargets(blockedOrigin)) setFixture(level, pos, Blocks.AIR.defaultBlockState(), oldBlocks);
		for (BlockPos pos : prepSupports(blockedOrigin)) setFixture(level, pos, Blocks.STONE.defaultBlockState(), oldBlocks);
		BlockPos preferredBench = blockedOrigin.east(2);
		BlockPos movedBench = preferredBench.east();
		setFixture(level, preferredBench, Blocks.DIAMOND_BLOCK.defaultBlockState(), oldBlocks);
		setFixture(level, movedBench, Blocks.AIR.defaultBlockState(), oldBlocks);
		SimpleContainer rerouteStock = new SimpleContainer(2);
		rerouteStock.setItem(0, new ItemStack(Items.SCAFFOLDING, 8));
		rerouteStock.setItem(1, new ItemStack(Items.CRAFTING_TABLE));
		List<Step> blockedFootprint = List.of(Step.block(blockedOrigin.above(), Blocks.OAK_PLANKS));
		ConstructionSite rerouted = ConstructionSite.prepare(level,
			new ConstructionSite(blockedOrigin, List.of(), ends, false, palette.ids()), blockedFootprint,
			blockedOrigin, Direction.EAST, rerouteStock);
		BlockPos actualBench = rerouted.temporary().stream().filter(ConstructionSite.TemporaryBlock::workbench)
			.map(ConstructionSite.TemporaryBlock::pos).findFirst().orElse(null);
		ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, rerouted);
		var reroutedSaved = ref.bellEntity().saveWithFullMetadata(level.registryAccess());
		BlockEntity reroutedReloadedEntity = BlockEntity.loadStatic(ref.bell(), level.getBlockState(ref.bell()), reroutedSaved, level.registryAccess());
		ConstructionSite resumedRerouted = reroutedReloadedEntity == null ? null
			: reroutedReloadedEntity.getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		check(rerouted.prepared() && actualBench != null && actualBench.equals(movedBench)
			&& level.getBlockState(preferredBench).is(Blocks.DIAMOND_BLOCK)
			&& level.getBlockState(movedBench).is(Blocks.CRAFTING_TABLE) && rerouted.equals(resumedRerouted),
			"an obstructed preferred workbench moves safely and its exact staged position survives bell save/load"); passed++;
		ConstructionSite rerouteCleaned = ConstructionSite.cleanup(level, resumedRerouted, rerouteStock);
		check(rerouteCleaned.temporary().isEmpty() && level.getBlockState(preferredBench).is(Blocks.DIAMOND_BLOCK)
			&& level.getBlockState(movedBench).isAir() && itemCount(rerouteStock, Items.SCAFFOLDING) == 8
			&& itemCount(rerouteStock, Items.CRAFTING_TABLE) == 1,
			"the relocated workbench is tracked, removed and refunded once while the obstructing block survives"); passed++;

			prepStock.clearContent();
			prepStock.setItem(0, new ItemStack(Items.SCAFFOLDING, 4));
			ConstructionSite partialPrep = ConstructionSite.prepare(level,
				new ConstructionSite(origin, List.of(), ends, false, palette.ids()), footprint, origin, Direction.EAST, prepStock);
			Map<net.minecraft.world.item.Item, Integer> missingSetup = ConstructionSite.missingSetupMaterials(
				level, partialPrep, footprint, origin, Direction.EAST, prepStock);
			check(!partialPrep.prepared() && partialPrep.scaffoldCount() == 4 && !partialPrep.hasWorkbench()
				&& itemCount(prepStock, Items.SCAFFOLDING) == 0
				&& missingSetup.getOrDefault(Items.SCAFFOLDING, 0) == 4
				&& missingSetup.getOrDefault(Items.CRAFTING_TABLE, 0) == 1,
				"partial real setup consumes only four paid scaffolds, remains resumable, and requests only the missing four plus workbench"); passed++;
			ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, partialPrep);
			var partialSaved = ref.bellEntity().saveWithFullMetadata(level.registryAccess());
			BlockEntity partialReloaded = BlockEntity.loadStatic(ref.bell(), level.getBlockState(ref.bell()), partialSaved, level.registryAccess());
			ConstructionSite resumedSite = partialReloaded == null ? null
				: partialReloaded.getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
			check(partialPrep.equals(resumedSite) && resumedSite != null && !resumedSite.prepared()
				&& resumedSite.scaffoldCount() == 4,
				"an incomplete paid worksite and its exact temporary-block ownership survive a bell save/reload"); passed++;
			prepStock.setItem(0, new ItemStack(Items.SCAFFOLDING, missingSetup.getOrDefault(Items.SCAFFOLDING, 0)));
			prepStock.setItem(1, new ItemStack(Items.CRAFTING_TABLE, missingSetup.getOrDefault(Items.CRAFTING_TABLE, 0)));
			ConstructionSite resumedPrep = ConstructionSite.prepare(level, resumedSite, footprint, origin, Direction.EAST, prepStock);
			check(resumedPrep.prepared() && resumedPrep.scaffoldCount() == 8 && resumedPrep.hasWorkbench()
				&& itemCount(prepStock, Items.SCAFFOLDING) == 0 && itemCount(prepStock, Items.CRAFTING_TABLE) == 0,
				"after real shortages arrive, the saved setup resumes without repaying its four surviving scaffolds"); passed++;
			ConstructionSite resumeClean = ConstructionSite.cleanup(level, resumedPrep, prepStock);
			check(resumeClean.temporary().isEmpty() && itemCount(prepStock, Items.SCAFFOLDING) == 8
				&& itemCount(prepStock, Items.CRAFTING_TABLE) == 1,
				"resumed setup abort returns all nine paid worksite blocks once and removes only owned blocks"); passed++;

			BlockPos cancelOrigin = origin.offset(12, 0, 12);
			BlockPos scaffold = cancelOrigin;
			BlockPos bench = cancelOrigin.east();
			BlockPos completedWall = cancelOrigin.above(3);
			setFixture(level, scaffold, Blocks.SCAFFOLDING.defaultBlockState(), oldBlocks);
			setFixture(level, bench, Blocks.CRAFTING_TABLE.defaultBlockState(), oldBlocks);
			setFixture(level, completedWall, Blocks.OAK_PLANKS.defaultBlockState(), oldBlocks);
			chest.clearContent();
			// The site already owns the single scaffold and workbench in-world; only unrelated stock remains before the refund.
			chest.setItem(0, new ItemStack(Items.STONE, 23));
			ConstructionSite cancelSite = new ConstructionSite(cancelOrigin,
				List.of(new ConstructionSite.TemporaryBlock(scaffold, false), new ConstructionSite.TemporaryBlock(bench, true)),
				ends, true, palette.ids());
			data.planType = Blueprints.GRANARY; data.planOrigin = Optional.of(cancelOrigin); data.planDir = 0;
			data.planStep = 2; data.planVariant = 292; data.planSource = Optional.empty();
			data.planProgress = level.getGameTime(); data.wanted.clear(); data.wanted.add("minecraft:stone#2");
			ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, cancelSite);
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			var saved = ref.bellEntity().saveWithFullMetadata(level.registryAccess());
			BlockEntity restored = BlockEntity.loadStatic(ref.bell(), level.getBlockState(ref.bell()), saved, level.registryAccess());
			check(restored != null && cancelSite.equals(restored.getAttachedOrElse(VillagerPlus.CONSTRUCTION, null)),
				"phase boundaries, palette and owned temporary blocks survive bell save/load"); passed++;
			List<String> status = VillageStats.lines(level, ref);
			check(status.stream().anyMatch(line -> line.contains("Bauphase 3/5"))
				&& status.stream().anyMatch(line -> line.contains("1 Gerüst"))
				&& status.stream().anyMatch(line -> line.contains("Werkbank aufgestellt")),
				"the village board reports current phase, phase progress and visible worksite setup"); passed++;

			Builder.cancel(ref, level.getGameTime() + 1);
			// Abort now leaves a cleanup-only record: the plan is gone at once, the visible worksite is dismantled and
			// refunded by a resident's ordinary build ticks (cleanOneAbandonedSite).
			check(!data.hasPlan() && data.buildProjects.stream().anyMatch(VillageData.BuildProject::cleanupOnly),
				"aborting a persisted plan clears the plan at once and queues a cleanup-only worksite record"); passed++;
			for (int cleanupTick = 2; cleanupTick < 400 && (!level.getBlockState(scaffold).isAir() || !level.getBlockState(bench).isAir()
					|| data.buildProjects.stream().anyMatch(VillageData.BuildProject::cleanupOnly)); cleanupTick++) {
				Builder.tick(new Ctx(level, setupBuilder, builderState, new VillagerMemory(), ref, level.getGameTime() + cleanupTick), true);
			}
			String cancelTrace = " [plan=" + data.planType + "; site=" + ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION)
				+ "; scaffold=" + level.getBlockState(scaffold) + "; bench=" + level.getBlockState(bench)
				+ "; completed=" + level.getBlockState(completedWall) + "; scaffoldStock=" + itemCount(chest, Items.SCAFFOLDING)
				+ "; benchStock=" + itemCount(chest, Items.CRAFTING_TABLE) + "; stoneStock=" + itemCount(chest, Items.STONE) + "]";
			check(!data.hasPlan() && ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION) == null
				&& level.getBlockState(scaffold).isAir() && level.getBlockState(bench).isAir()
				&& level.getBlockState(completedWall).is(Blocks.OAK_PLANKS)
				&& itemCount(chest, Items.SCAFFOLDING) == 1 && itemCount(chest, Items.CRAFTING_TABLE) == 1
				&& itemCount(chest, Items.STONE) == 23,
				"aborting a persisted plan removes tracked setup, refunds it once, and preserves completed work and unrelated stock" + cancelTrace); passed++;
			Builder.cancel(ref, level.getGameTime() + 2);
			check(itemCount(chest, Items.SCAFFOLDING) == 1 && itemCount(chest, Items.CRAFTING_TABLE) == 1,
				"repeated cancellation cannot duplicate already recovered setup material"); passed++;

			BlockPos changedPos = origin.offset(18, 0, 18);
			setFixture(level, changedPos, Blocks.DIAMOND_BLOCK.defaultBlockState(), oldBlocks);
			SimpleContainer noRefund = new SimpleContainer(2);
			ConstructionSite changed = new ConstructionSite(changedPos, List.of(new ConstructionSite.TemporaryBlock(changedPos, false)), ends, true, palette.ids());
			ConstructionSite changedClean = ConstructionSite.cleanup(level, changed, noRefund);
			check(level.getBlockState(changedPos).is(Blocks.DIAMOND_BLOCK) && changedClean.temporary().isEmpty()
				&& itemCount(noRefund, Items.SCAFFOLDING) == 0,
				"cleanup leaves a player-replaced block intact and does not refund a block the site no longer owns"); passed++;
			return passed;
		} finally {
			if (setupBuilder != null) setupBuilder.discard();
			oldBlocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
			chest.clearContent();
			for (int i = 0; i < oldStock.size(); i++) chest.setItem(i, oldStock.get(i));
			oldPlan.restore(data);
			if (oldSite == null) ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, oldSite);
			if (oldPolicies == null) ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
			else ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, oldPolicies);
			ref.dirty();
		}
	}

	private static List<BlockPos> prepTargets(BlockPos origin) {
		List<BlockPos> targets = new ArrayList<>();
		for (int[] corner : new int[][] {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}) {
			for (int dy = 0; dy < 2; dy++) targets.add(origin.offset(corner[0], dy, corner[1]));
		}
		targets.add(origin.east(2));
		return targets;
	}

	private static List<BlockPos> prepSupports(BlockPos origin) {
		List<BlockPos> supports = new ArrayList<>();
		for (int[] corner : new int[][] {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}) supports.add(origin.offset(corner[0], -1, corner[1]));
		supports.add(origin.east(2).below());
		return supports;
	}

	private static void setFixture(ServerLevel level, BlockPos pos, BlockState state, Map<BlockPos, BlockState> oldBlocks) {
		level.getChunkAt(pos);
		if (level.getBlockEntity(pos) != null) throw new IllegalStateException("CONSTRUCTION SITE CHECK FAILED: fixture would replace a block entity at " + pos);
		oldBlocks.putIfAbsent(pos, level.getBlockState(pos));
		level.setBlock(pos, state, 3);
	}

	private static int itemCount(Container container, net.minecraft.world.item.Item item) {
		int total = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	private static String inventorySummary(Container container) {
		List<String> stacks = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) stacks.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount() + "@" + i);
		}
		return stacks.isEmpty() ? "empty" : String.join(",", stacks);
	}

	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("CONSTRUCTION SITE CHECK FAILED: " + label);
		System.out.println("CONSTRUCTION SITE PASS " + label);
	}
}
