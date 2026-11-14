package de.villagerplus.build;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Anchor;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BellBlockEntity;

/** Focused persistence and ownership checks for independent village construction projects and crews. */
public final class BuilderProjectChecks {
	private static final int FLAGS = 2 | 16 | 32;
	private static final int FIXTURE_TICKET_RADIUS = 2;
	private static final int EXPECTED_HELPER_CAPACITY = 3;
	private record PendingFixture(BlockPos bell, ChunkPos chunk) { }
	private static final Map<ResourceKey<Level>, PendingFixture> PENDING_FIXTURES = new HashMap<>();

	private BuilderProjectChecks() { }

	public static int run(ServerLevel level, VillageRef baseRef) throws Exception {
		BlockPos fixtureBell = prepareFixture(level, baseRef.bell());
		if (fixtureBell == null) return -1;
		try {
			return runPrepared(level, fixtureBell);
		} finally {
			releasePendingFixtureTicket(level);
		}
	}

	private static int runPrepared(ServerLevel level, BlockPos fixtureBell) throws Exception {
		int checks = 0;
		UUID firstOwner = UUID.randomUUID();
		UUID secondOwner = UUID.randomUUID();
		UUID thirdOwner = UUID.randomUUID();
		UUID shopOwner = UUID.randomUUID();
		BlockPos firstOrigin = new BlockPos(120, 64, -80);
		BlockPos secondOrigin = new BlockPos(160, 64, -80);
		BlockPos thirdOrigin = new BlockPos(200, 64, -80);
		String firstCargo = UUID.randomUUID() + "|minecraft:oak_planks|2|" + new BlockPos(100, 64, -80).asLong();
		String secondCargo = UUID.randomUUID() + "|minecraft:cobblestone|3|" + new BlockPos(101, 64, -80).asLong();
		String thirdCargo = UUID.randomUUID() + "|minecraft:wheat_seeds|1|" + new BlockPos(102, 64, -80).asLong();
		ConstructionSite firstSite = new ConstructionSite(firstOrigin,
			List.of(new ConstructionSite.TemporaryBlock(firstOrigin.east(), false)), List.of(5, 11), true, List.of("oak"));
		VillageData saved = new VillageData();
		VillageData.BuildProject first = new VillageData.BuildProject(Blueprints.SCHOOL, firstOrigin, Direction.EAST.get2DDataValue(),
			13, 7, Optional.of(firstOrigin.west()), 510L, Optional.of(firstOwner), 0L,
			List.of("minecraft:oak_planks#7", "minecraft:cobblestone#4"),
			new VillageData.ProjectHaul(List.of(new ItemStack(Items.OAK_PLANKS, 3), new ItemStack(Items.SCAFFOLDING, 1)),
				List.of(firstCargo)), Optional.of("waiting for material"), Optional.of(firstSite),
			Optional.of(new VillageData.PlanBounds(110, 130, -90, -70)), false, false);
		VillageData.BuildProject second = new VillageData.BuildProject(Blueprints.TOOLSHED, secondOrigin, Direction.NORTH.get2DDataValue(),
			29, 4, Optional.empty(), 620L, Optional.of(secondOwner), 0L, List.of("minecraft:oak_planks#11"),
			new VillageData.ProjectHaul(List.of(new ItemStack(Items.COBBLESTONE, 6)), List.of(secondCargo)),
			Optional.empty(), Optional.empty(), Optional.of(new VillageData.PlanBounds(150, 170, -90, -70)), false, false);
		VillageData.BuildProject third = new VillageData.BuildProject(Blueprints.FARM, thirdOrigin, Direction.SOUTH.get2DDataValue(),
			5, 2, Optional.empty(), 710L, Optional.of(thirdOwner), 0L, List.of("minecraft:wheat_seeds#4"),
			new VillageData.ProjectHaul(List.of(new ItemStack(Items.WHEAT_SEEDS, 2)), List.of(thirdCargo)),
			Optional.empty(), Optional.empty(), Optional.of(new VillageData.PlanBounds(190, 210, -90, -70)), false, false);
		saved.putBuildProject(first);
		saved.putBuildProject(second);
		saved.putBuildProject(third);
		saved.addMarketDemand(Optional.of(shopOwner), "minecraft:oak_planks", 2);
		saved.syncPrimaryProjectView();
		var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
		var encoded = VillageData.CODEC.encodeStart(ops, saved).getOrThrow();
		VillageData restored = VillageData.CODEC.parse(ops, encoded).getOrThrow();
		VillageData.BuildProject restoredFirst = restored.buildProject(firstOrigin).orElseThrow();
		VillageData.BuildProject restoredSecond = restored.buildProject(secondOrigin).orElseThrow();
		VillageData.BuildProject restoredThird = restored.buildProject(thirdOrigin).orElseThrow();
		check(restored.buildProjects.size() == 3 && restored.activeBuildProjectCount() == 3
			&& restoredFirst.step() == 13 && restoredSecond.step() == 29 && restoredThird.step() == 5
			&& restoredFirst.owner().equals(Optional.of(firstOwner)) && restoredSecond.owner().equals(Optional.of(secondOwner))
			&& restoredThird.owner().equals(Optional.of(thirdOwner))
			&& List.of(firstOwner, secondOwner, thirdOwner).stream().distinct().count() == 3,
			"save/reload preserves three independent active cursors and their distinct owners"); checks++;
		check(count(restoredFirst.siteStock(), Items.OAK_PLANKS) == 3
			&& count(restoredFirst.siteStock(), Items.SCAFFOLDING) == 1
			&& count(restoredSecond.siteStock(), Items.COBBLESTONE) == 6
			&& count(restoredThird.siteStock(), Items.WHEAT_SEEDS) == 2
			&& restoredFirst.cargoManifest().equals(List.of(firstCargo))
			&& restoredSecond.cargoManifest().equals(List.of(secondCargo))
			&& restoredThird.cargoManifest().equals(List.of(thirdCargo))
			&& restoredFirst.construction().orElseThrow().temporary().equals(firstSite.temporary()),
			"save/reload keeps each site's own stock, in-transit reservation, and temporary worksite blocks"); checks++;
		check(count(restoredFirst.siteStock(), Items.OAK_PLANKS)
			+ de.villagerplus.job.BuildHaul.inTransit(restoredFirst.cargoManifest()).getOrDefault(Items.OAK_PLANKS, 0) == 5
			&& count(restoredSecond.siteStock(), Items.COBBLESTONE)
			+ de.villagerplus.job.BuildHaul.inTransit(restoredSecond.cargoManifest()).getOrDefault(Items.COBBLESTONE, 0) == 9
			&& count(restoredThird.siteStock(), Items.WHEAT_SEEDS)
			+ de.villagerplus.job.BuildHaul.inTransit(restoredThird.cargoManifest()).getOrDefault(Items.WHEAT_SEEDS, 0) == 3,
			"project stock plus real cargo reservation stays conserved independently across all three plans"); checks++;
		Map<String, Integer> aggregate = wanted(restored.wanted);
		check(aggregate.getOrDefault("minecraft:oak_planks", 0) == 20
			&& aggregate.getOrDefault("minecraft:cobblestone", 0) == 4
			&& aggregate.getOrDefault("minecraft:wheat_seeds", 0) == 4
			&& restored.marketDemands.size() == 1 && restored.marketDemands.getFirst().amount() == 2,
			"village demand sums all three project targets while preserving separate market demand"); checks++;

		VillageData oldSave = new VillageData();
		oldSave.planType = Blueprints.HOUSE;
		oldSave.planOrigin = Optional.of(new BlockPos(15, 70, -15));
		oldSave.planStep = 23;
		oldSave.planVariant = 6;
		oldSave.wanted.add("minecraft:oak_planks#12");
		JsonObject legacyJson = VillageData.CODEC.encodeStart(JsonOps.INSTANCE, oldSave).getOrThrow().getAsJsonObject();
		legacyJson.remove("build_project_version");
		legacyJson.remove("build_projects");
		legacyJson.remove("market_demands");
		VillageData migrated = VillageData.CODEC.parse(JsonOps.INSTANCE, legacyJson).getOrThrow();
		check(migrated.buildProjectVersion == 0 && migrated.buildProjects.isEmpty() && migrated.hasPlan()
			&& migrated.planStep == 23 && migrated.wanted.equals(List.of("minecraft:oak_planks#12")),
			"pre-multiproject saves retain their legacy cursor and demand for safe lazy migration"); checks++;

		checks += crewOwnershipAndDispatch(level, fixtureBell);
		return checks;
	}

	private static int crewOwnershipAndDispatch(ServerLevel level, BlockPos bell) throws Exception {
		int checks = 0;
		Map<BlockPos, BlockState> oldBlocks = new LinkedHashMap<>();
		List<Villager> fixtures = new ArrayList<>();
		try {
			for (BlockPos pos : List.of(bell.below(), bell)) {
				level.getChunkAt(pos);
				if (level.getBlockEntity(pos) != null) throw new IllegalStateException("BUILDER PROJECT CHECK FAILED: fixture overlaps a block entity");
				oldBlocks.put(pos, level.getBlockState(pos));
			}
			level.setBlock(bell.below(), Blocks.STONE.defaultBlockState(), FLAGS);
			level.setBlock(bell, Blocks.BELL.defaultBlockState(), FLAGS);
			if (!(level.getBlockEntity(bell) instanceof BellBlockEntity bellEntity)) {
				throw new IllegalStateException("BUILDER PROJECT CHECK FAILED: village fixture bell missing");
			}
			VillageData data = bellEntity.getAttachedOrCreate(VillagerPlus.VILLAGE);
			VillageRef ref = new VillageRef(bell, data, bellEntity);
			Villager ownerA = spawn(level, ref, bell.offset(3, 1, 0), VillagerProfession.MASON, fixtures);
			Villager ownerB = spawn(level, ref, bell.offset(-3, 1, 0), VillagerProfession.MASON, fixtures);
			Villager farmerOwner = spawn(level, ref, bell.offset(0, 1, -3), VillagerProfession.FARMER, fixtures);
			Villager helperA = spawn(level, ref, bell.offset(0, 1, 3), VillagerProfession.NONE, fixtures);
			Villager helperB = spawn(level, ref, bell.offset(2, 1, 3), VillagerProfession.NONE, fixtures);
			Villager helperC = spawn(level, ref, bell.offset(-2, 1, 3), VillagerProfession.NONE, fixtures);
			Villager helperD = spawn(level, ref, bell.offset(4, 1, 3), VillagerProfession.NONE, fixtures);
			Villager helperE = spawn(level, ref, bell.offset(-4, 1, 3), VillagerProfession.MASON, fixtures);
			Villager helperF = spawn(level, ref, bell.offset(0, 1, 5), VillagerProfession.MASON, fixtures);
			List<Villager> helpers = List.of(helperA, helperB, helperC, helperD, helperE, helperF);
			check(sameChunk(bell, ownerA.blockPosition()) && sameChunk(bell, ownerB.blockPosition())
				&& sameChunk(bell, farmerOwner.blockPosition())
				&& helpers.stream().allMatch(helper -> sameChunk(bell, helper.blockPosition())),
				"fixture bell, all three role owners, and all six helpers share one loaded fixture chunk"); checks++;
			BlockPos a = bell.offset(16, 0, 16), b = bell.offset(-16, 0, -16);
			BlockPos first = a.asLong() < b.asLong() ? a : b;
			BlockPos second = first.equals(a) ? b : a;
			BlockPos third = bell.offset(0, 0, 8);
			Villager firstProjectOwner = first.equals(a) ? ownerA : ownerB;
			Villager secondProjectOwner = first.equals(a) ? ownerB : ownerA;
			data.putBuildProject(preparedProject(Blueprints.SCHOOL, first, firstProjectOwner.getUUID(), 3));
			data.putBuildProject(preparedProject(Blueprints.FARM, third, farmerOwner.getUUID(), 6));
			Map<UUID, ?> firstCrewSnapshot = crewAssignments(level, ref, data);
			check(firstCrewSnapshot == crewAssignments(level, ref, data),
				"prepared-build crew selection reuses one resident snapshot during the same server tick"); checks++;
			for (Activity busyActivity : List.of(Activity.PANIC, Activity.RAID, Activity.PRE_RAID, Activity.HIDE,
					Activity.FIGHT)) {
				for (Villager busyHelper : helpers.subList(1, helpers.size())) {
					busyHelper.getBrain().getActiveActivities().add(busyActivity);
				}
				Builder.clearBuildCrewSnapshotForCheck(level, ref);
				check(helpers.subList(1, helpers.size()).stream().allMatch(helper -> helper.getBrain().isActive(busyActivity))
					&& !Builder.canStartThirdProject(level, ref, data, secondProjectOwner, true),
					"third-project capacity excludes " + busyActivity + " villagers JobManager skips"); checks++;

				Map<UUID, ?> busyAssignments = crewAssignments(level, ref, data);
				boolean busyVillagersUnassigned = helpers.subList(1, helpers.size()).stream()
					.noneMatch(helper -> busyAssignments.containsKey(helper.getUUID()));
				Object remainingHelperAssignment = busyAssignments.get(helperA.getUUID());
				check(busyVillagersUnassigned && remainingHelperAssignment != null
					&& assignmentFlag(remainingHelperAssignment, "helper")
					&& assignmentOrigin(remainingHelperAssignment).equals(first),
					"crew assignment omits " + busyActivity + " villagers while retaining the only available helper"); checks++;

				for (Villager busyHelper : helpers.subList(1, helpers.size())) {
					// The fixture inserted the activity directly into Brain's active set; selecting the
					// default activity does not end a manually injected RAID/PRE_RAID entry.
					busyHelper.getBrain().getActiveActivities().remove(busyActivity);
				}
				Builder.clearBuildCrewSnapshotForCheck(level, ref);
				Map<UUID, ?> restoredAssignments = crewAssignments(level, ref, data);
				int eligibleAfterEnd = 0;
				int helperAssignmentsAfterEnd = 0;
				for (Object assignment : restoredAssignments.values()) {
					if (assignmentFlag(assignment, "helper")) helperAssignmentsAfterEnd++;
				}
				List<String> recoveryRows = new ArrayList<>();
				for (Villager helper : helpers) {
					boolean eligible = (boolean) invokeBuilder("eligibleBuildHelper",
						new Class<?>[]{ServerLevel.class, VillageRef.class, Villager.class}, level, ref, helper);
					if (eligible) eligibleAfterEnd++;
					Object assignment = restoredAssignments.get(helper.getUUID());
					boolean assignedAsHelper = assignment != null && assignmentFlag(assignment, "helper");
					recoveryRows.add(helper.getUUID() + "/active=" + helper.getBrain().getActiveActivities()
						+ "/eligible=" + eligible + "/assignment=" + (assignment == null ? "none"
							: assignmentOrigin(assignment) + ":slot=" + assignmentSlot(assignment)
								+ ":helper=" + assignmentFlag(assignment, "helper")));
				}
				boolean gateRestored = Builder.canStartThirdProject(level, ref, data, secondProjectOwner, true);
				List<Villager> possibleNextOwners = new ArrayList<>(helpers);
				possibleNextOwners.add(secondProjectOwner);
				boolean freeMasonCanClaimNextProject = possibleNextOwners.stream()
					.filter(helper -> helper.getVillagerData().profession().is(VillagerProfession.MASON))
					.filter(helper -> !restoredAssignments.containsKey(helper.getUUID()))
					.anyMatch(helper -> Builder.canStartThirdProject(level, ref, data, helper, true));
				boolean busyStateEnded = helpers.subList(1, helpers.size()).stream()
					.noneMatch(helper -> helper.getBrain().isActive(busyActivity));
				boolean crewRestored = eligibleAfterEnd == helpers.size()
					&& helperAssignmentsAfterEnd == EXPECTED_HELPER_CAPACITY && freeMasonCanClaimNextProject;
				check(busyStateEnded && gateRestored && crewRestored,
					"ending " + busyActivity + " restores helper availability"
						+ " [busyEnded=" + busyStateEnded + ", gate=" + gateRestored
						+ ", eligibleHelpers=" + eligibleAfterEnd + "/" + helpers.size()
						+ ", assignedHelpers=" + helperAssignmentsAfterEnd + "/" + EXPECTED_HELPER_CAPACITY
						+ ", freeMasonCanClaim=" + freeMasonCanClaimNextProject
						+ ", rows=" + recoveryRows + "]"); checks++;
			}
			for (Villager unavailableHelper : helpers.subList(1, helpers.size())) {
				unavailableHelper.addTag(de.villagerplus.village.Expedition.TAG);
			}
			check(!Builder.canStartThirdProject(level, ref, data, secondProjectOwner, false),
				"a Mason cannot claim the Farmer-only project lane"); checks++;
			check(!Builder.canStartThirdProject(level, ref, data, secondProjectOwner, true)
				&& data.activeBuildProjectCount() == 2
				&& data.buildProject(first).map(VillageData.BuildProject::step).orElse(-1) == 3
				&& data.buildProject(third).map(VillageData.BuildProject::step).orElse(-1) == 6,
				"third order waits when the remaining team has fewer free helpers than active Mason sites"); checks++;
			for (Villager unavailableHelper : helpers.subList(1, helpers.size())) {
				unavailableHelper.removeTag(de.villagerplus.village.Expedition.TAG);
			}
			check(data.buildProjects.stream().filter(VillageData.BuildProject::active)
				.noneMatch(project -> project.owner().filter(secondProjectOwner.getUUID()::equals).isPresent())
				&& Builder.canStartThirdProject(level, ref, data, secondProjectOwner, true),
				"third order opens with a distinct live Mason owner and enough idle crew for both team sites"); checks++;
			Builder.clearBuildCrewSnapshotForCheck(level, ref);
			Map<UUID, ?> nextProjectAssignments = crewAssignments(level, ref, data);
			int masonHelpersWorking = 0;
			for (Villager helper : helpers) {
				Object assignment = nextProjectAssignments.get(helper.getUUID());
				if (helper.getVillagerData().profession().is(VillagerProfession.MASON)
					&& assignment != null && assignmentFlag(assignment, "helper")) masonHelpersWorking++;
			}
			check(masonHelpersWorking > 0,
				"Masons beyond the single reserved next-project starter can help with a prepared building"); checks++;
			data.putBuildProject(preparedProject(Blueprints.TOOLSHED, second, secondProjectOwner.getUUID(), 9));
			data.syncPrimaryProjectView();
			List<Villager> fixtureResidents = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> true);
			check(level.getEntity(ownerA.getUUID()) == ownerA && level.getEntity(ownerB.getUUID()) == ownerB
				&& level.getEntity(farmerOwner.getUUID()) == farmerOwner
				&& fixtureResidents.stream().anyMatch(v -> v.getUUID().equals(ownerA.getUUID()))
				&& fixtureResidents.stream().anyMatch(v -> v.getUUID().equals(ownerB.getUUID()))
				&& fixtureResidents.stream().anyMatch(v -> v.getUUID().equals(farmerOwner.getUUID()))
				&& helpers.stream().allMatch(helper -> level.getEntity(helper.getUUID()) == helper
					&& fixtureResidents.stream().anyMatch(v -> v.getUUID().equals(helper.getUUID()))),
				"all three owners and six helpers are live, assigned residents in the same loaded fixture chunk"); checks++;
			check(data.activeBuildProjectCount() == 3
				&& data.buildProject(first).map(project -> project.owner().equals(Optional.of(firstProjectOwner.getUUID()))).orElse(false)
				&& data.buildProject(second).map(project -> project.owner().equals(Optional.of(secondProjectOwner.getUUID()))).orElse(false)
				&& data.buildProject(third).map(project -> project.owner().equals(Optional.of(farmerOwner.getUUID()))).orElse(false)
				&& List.of(firstProjectOwner, secondProjectOwner, farmerOwner).stream().map(Villager::getUUID).distinct().count() == 3
				&& firstProjectOwner.getVillagerData().profession().is(VillagerProfession.MASON)
				&& secondProjectOwner.getVillagerData().profession().is(VillagerProfession.MASON)
				&& farmerOwner.getVillagerData().profession().is(VillagerProfession.FARMER),
				"three active orders retain separate real owners with Mason and Farmer responsibilities"); checks++;
			Builder.clearBuildCrewSnapshotForCheck(level, ref);
			Map<UUID, ?> assignments = crewAssignments(level, ref, data);
			Object firstHelperAssignment = null;
			Object secondHelperAssignment = null;
			for (Villager helper : helpers) {
				Object assignment = assignments.get(helper.getUUID());
				if (assignment == null || !assignmentFlag(assignment, "helper")) continue;
				BlockPos assignedOrigin = assignmentOrigin(assignment);
				if (assignedOrigin.equals(first) && firstHelperAssignment == null) firstHelperAssignment = assignment;
				if (assignedOrigin.equals(second) && secondHelperAssignment == null) secondHelperAssignment = assignment;
			}
			boolean helpersSplitAcrossProjects = firstHelperAssignment != null && secondHelperAssignment != null;
			if (!helpersSplitAcrossProjects) {
				throw new IllegalStateException("BUILDER PROJECT CHECK FAILED: eligible idle adults are assigned across both live prepared Mason projects"
					+ "; firstProjectHelper="
					+ (firstHelperAssignment == null ? "none" : assignmentOrigin(firstHelperAssignment))
					+ ", secondProjectHelper="
					+ (secondHelperAssignment == null ? "none" : assignmentOrigin(secondHelperAssignment))
					+ "; " + crewFailureDetails(level, ref, data, helpers.getFirst(), assignments));
			}
			checks++;
			Object firstOwnerAssignment = assignments.get(firstProjectOwner.getUUID());
			Object secondOwnerAssignment = assignments.get(secondProjectOwner.getUUID());
			Object farmerOwnerAssignment = assignments.get(farmerOwner.getUUID());
			boolean helpersStayOnMasonSites = true;
			for (Villager helper : helpers) {
				Object assignment = assignments.get(helper.getUUID());
				if (assignment == null || !assignmentFlag(assignment, "helper")) {
					helpersStayOnMasonSites = false;
					break;
				}
				BlockPos assignedOrigin = assignmentOrigin(assignment);
				if (!assignedOrigin.equals(first) && !assignedOrigin.equals(second)) {
					helpersStayOnMasonSites = false;
					break;
				}
			}
			check(firstOwnerAssignment != null && !assignmentFlag(firstOwnerAssignment, "helper")
				&& assignmentSlot(firstOwnerAssignment) == 0 && assignmentOrigin(firstOwnerAssignment).equals(first)
				&& secondOwnerAssignment != null && !assignmentFlag(secondOwnerAssignment, "helper")
				&& assignmentSlot(secondOwnerAssignment) == 0 && assignmentOrigin(secondOwnerAssignment).equals(second)
				&& farmerOwnerAssignment == null
				&& helpersStayOnMasonSites,
				"Masons own their building sites; Farmer keeps the farm while helpers join only Mason sites"); checks++;
			int firstHelperCount = 0, secondHelperCount = 0, firstStandSlots = 0, secondStandSlots = 0;
			for (Villager helper : helpers) {
				Object assignment = assignments.get(helper.getUUID());
				if (assignment == null || !assignmentFlag(assignment, "helper")) continue;
				if (assignmentOrigin(assignment).equals(first)) {
					firstHelperCount++;
					firstStandSlots |= 1 << assignmentSlot(assignment);
				} else if (assignmentOrigin(assignment).equals(second)) {
					secondHelperCount++;
					secondStandSlots |= 1 << assignmentSlot(assignment);
				}
			}
			check(firstHelperCount == 3 && secondHelperCount == 3
				&& firstStandSlots == 0b1110 && secondStandSlots == 0b1110,
				"three active orders leave the Farmer task separate and split six helpers evenly across both Mason sites"); checks++;
			var threeProjectSave = VillageData.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), data).getOrThrow();
			VillageData threeProjectReload = VillageData.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), threeProjectSave).getOrThrow();
			check(threeProjectReload.activeBuildProjectCount() == 3
				&& threeProjectReload.buildProject(first).map(project -> project.step() == 3
					&& project.owner().equals(Optional.of(firstProjectOwner.getUUID()))).orElse(false)
				&& threeProjectReload.buildProject(second).map(project -> project.step() == 9
					&& project.owner().equals(Optional.of(secondProjectOwner.getUUID()))).orElse(false)
				&& threeProjectReload.buildProject(third).map(project -> project.step() == 6
					&& project.owner().equals(Optional.of(farmerOwner.getUUID()))).orElse(false),
				"save/reload preserves all three live-owner assignments and independent cursors"); checks++;

			firstProjectOwner.addTag(de.villagerplus.village.Expedition.TAG);
			Builder.clearBuildCrewSnapshotForCheck(level, ref);
			Map<UUID, ?> ownerAwayAssignments = crewAssignments(level, ref, data);
			int helpersAtRemainingProject = 0;
			for (Villager helper : helpers) {
				Object assignment = ownerAwayAssignments.get(helper.getUUID());
				if (assignment != null && assignmentFlag(assignment, "helper")
					&& assignmentOrigin(assignment).equals(second)) helpersAtRemainingProject++;
			}
			List<String> ownerAwayRows = new ArrayList<>();
			for (Villager helper : helpers) {
				Object assignment = ownerAwayAssignments.get(helper.getUUID());
				ownerAwayRows.add(helper.getUUID() + ":" + (assignment == null ? "none"
					: assignmentOrigin(assignment) + ":helper=" + assignmentFlag(assignment, "helper")));
			}
			Object awayOwnerAssignment = ownerAwayAssignments.get(firstProjectOwner.getUUID());
			Object remainingOwnerAssignment = ownerAwayAssignments.get(secondProjectOwner.getUUID());
			check(helpersAtRemainingProject == 3 && !ownerAwayAssignments.containsKey(firstProjectOwner.getUUID())
				&& ownerAwayAssignments.containsKey(secondProjectOwner.getUUID()),
				"when one owner is away, helpers reassign fairly up to the remaining site's three-helper cap"
					+ " [remainingHelpers=" + helpersAtRemainingProject + ", awayOwner="
					+ (awayOwnerAssignment == null ? "none" : assignmentOrigin(awayOwnerAssignment)
						+ ":helper=" + assignmentFlag(awayOwnerAssignment, "helper")) + ", remainingOwner="
					+ (remainingOwnerAssignment == null ? "none" : assignmentOrigin(remainingOwnerAssignment)
						+ ":helper=" + assignmentFlag(remainingOwnerAssignment, "helper")) + ", rows=" + ownerAwayRows + "]"); checks++;
			firstProjectOwner.removeTag(de.villagerplus.village.Expedition.TAG);

			Villager cancelOwner = firstProjectOwner;
			String cancelCargo = cancelOwner.getUUID() + "|minecraft:oak_planks|4|" + bell.offset(-8, 0, 0).asLong();
			ConstructionSite cancelSite = new ConstructionSite(first, List.of(
				new ConstructionSite.TemporaryBlock(first.east(), false),
				new ConstructionSite.TemporaryBlock(first.east(2), true)), List.of(4, 9), true, List.of("oak"));
			VillageData.BuildProject cancellable = data.buildProject(first).orElseThrow();
			data.putBuildProject(new VillageData.BuildProject(cancellable.type(), cancellable.origin(), cancellable.dir(),
				cancellable.step(), cancellable.variant(), cancellable.source(), cancellable.progress(), cancellable.owner(),
				cancellable.ownerUnavailableSince(), cancellable.wanted(),
				new VillageData.ProjectHaul(List.of(new ItemStack(Items.OAK_PLANKS, 6), new ItemStack(Items.SCAFFOLDING, 2)),
					List.of(cancelCargo)), cancellable.materialWait(), Optional.of(cancelSite), cancellable.bounds(), false, false));
			data.syncPrimaryProjectView();
			bellEntity.setAttached(VillagerPlus.CONSTRUCTION, cancelSite);
			Builder.cancel(ref, Math.max(1L, level.getGameTime() + 1L));
			VillageData.BuildProject cancelled = data.buildProject(first).orElseThrow();
			check(cancelled.cleanupOnly() && cancelled.cargoManifest().equals(List.of(cancelCargo))
				&& count(cancelled.siteStock(), Items.OAK_PLANKS) == 6
				&& count(cancelled.siteStock(), Items.SCAFFOLDING) == 2
				&& cancelled.construction().orElseThrow().temporary().equals(cancelSite.temporary()),
				"UI cancel immediately persists the original carrier reservation, site stock, and scaffold ownership"); checks++;
			check(data.activeBuildProjectCount() == 2 && data.buildProject(second).filter(VillageData.BuildProject::active).isPresent()
				&& data.buildProject(third).filter(VillageData.BuildProject::active).isPresent()
				&& data.planOrigin.equals(Optional.of(third)) && data.planStep == 6,
				"UI cancel leaves both unrelated project cursors and their owners active"); checks++;
			var cancelledSave = VillageData.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), data).getOrThrow();
			VillageData cancelledReload = VillageData.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), cancelledSave).getOrThrow();
			VillageData.BuildProject reloadedCleanup = cancelledReload.buildProject(first).orElseThrow();
			check(reloadedCleanup.cleanupOnly() && reloadedCleanup.cargoManifest().equals(List.of(cancelCargo))
				&& count(reloadedCleanup.siteStock(), Items.OAK_PLANKS) == 6
				&& count(reloadedCleanup.siteStock(), Items.SCAFFOLDING) == 2
				&& cancelledReload.buildProject(second).filter(VillageData.BuildProject::active).map(VillageData.BuildProject::step).orElse(-1) == 9
				&& cancelledReload.buildProject(third).filter(VillageData.BuildProject::active).map(VillageData.BuildProject::step).orElse(-1) == 6,
				"save/reload after UI cancel retains refund work and both unrelated active cursors"); checks++;
			return checks;
		} finally {
			for (Villager villager : fixtures) {
				Villages.forget(villager.getUUID());
				villager.discard();
			}
			oldBlocks.forEach((pos, state) -> level.setBlock(pos, state, FLAGS));
		}
	}

	/** Adds a nonblocking entity-ticking lease for the actual offset fixture bell and lets AutoTest retry later. */
	private static BlockPos prepareFixture(ServerLevel level, BlockPos center) {
		ResourceKey<Level> dimension = level.dimension();
		PendingFixture fixture = PENDING_FIXTURES.get(dimension);
		boolean firstPreparation = fixture == null;
		if (fixture == null) {
			BlockPos bell = findFixtureBell(level, center);
			fixture = new PendingFixture(bell, new ChunkPos(bell.getX() >> 4, bell.getZ() >> 4));
			PENDING_FIXTURES.put(dimension, fixture);
		}
		try {
			level.getChunkSource().addTicketWithRadius(Anchor.WALL_WORK, fixture.chunk(), FIXTURE_TICKET_RADIUS);
			var chunk = level.getChunkSource().getChunkNow(fixture.chunk().x(), fixture.chunk().z());
			FullChunkStatus status = chunk == null ? null : chunk.getFullStatus();
			if (firstPreparation || status == null || !status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
				System.out.println("BUILDER PROJECTS PREPARING fixture=" + fixture.bell() + " chunk=" + fixture.chunk()
					+ " status=" + (status == null ? "UNLOADED" : status)
					+ "; retry after server ticks; no checks counted");
				return null;
			}
			return fixture.bell();
		} catch (RuntimeException | Error failure) {
			releasePendingFixtureTicket(level);
			throw failure;
		}
	}

	/** Also called by AutoTest when a pending fixture exceeds its retry limit. */
	public static void releasePendingFixtureTicket(ServerLevel level) {
		if (level == null) return;
		PendingFixture fixture = PENDING_FIXTURES.remove(level.dimension());
		if (fixture != null) {
			level.getChunkSource().removeTicketWithRadius(Anchor.WALL_WORK, fixture.chunk(), FIXTURE_TICKET_RADIUS);
		}
	}

	private static BlockPos findFixtureBell(ServerLevel level, BlockPos center) {
		for (int radius = 192; radius <= 2048; radius += 64) {
			BlockPos raw = center.offset(radius, 0, radius / 2);
			BlockPos candidate = new BlockPos(Math.floorDiv(raw.getX(), 16) * 16 + 8, raw.getY(),
				Math.floorDiv(raw.getZ(), 16) * 16 + 8);
			level.getChunkAt(candidate.below());
			level.getChunkAt(candidate);
			if (level.getBlockEntity(candidate.below()) == null && level.getBlockEntity(candidate) == null) return candidate;
		}
		throw new IllegalStateException("BUILDER PROJECT CHECK FAILED: cannot reserve a temporary village bell position");
	}

	private static boolean sameChunk(BlockPos a, BlockPos b) {
		return Math.floorDiv(a.getX(), 16) == Math.floorDiv(b.getX(), 16)
			&& Math.floorDiv(a.getZ(), 16) == Math.floorDiv(b.getZ(), 16);
	}

	private static Villager spawn(ServerLevel level, VillageRef ref, BlockPos pos, ResourceKey<VillagerProfession> profession,
			List<Villager> fixtures) {
		level.getChunkAt(pos);
		Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("BUILDER PROJECT CHECK FAILED: cannot create villager fixture");
		villager.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		villager.setInvulnerable(true);
		villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), profession));
		if (!level.addFreshEntity(villager)) throw new IllegalStateException("BUILDER PROJECT CHECK FAILED: cannot register villager fixture");
		villager.addTag("vp090_fixture");
		Villages.bindResident(level, ref, villager, null);
		fixtures.add(villager);
		return villager;
	}

	private static VillageData.BuildProject preparedProject(String type, BlockPos origin, UUID owner, int cursor) {
		return new VillageData.BuildProject(type, origin, Direction.EAST.get2DDataValue(), cursor, 0, Optional.empty(), 100L,
			Optional.of(owner), 0L, List.of(), VillageData.ProjectHaul.empty(), Optional.empty(),
			Optional.of(new ConstructionSite(origin, List.of(), List.of(), true, List.of())),
			Optional.of(new VillageData.PlanBounds(origin.getX() - 5, origin.getX() + 5,
				origin.getZ() - 5, origin.getZ() + 5)), false, false);
	}

	@SuppressWarnings("unchecked")
	private static Map<UUID, ?> crewAssignments(ServerLevel level, VillageRef ref, VillageData data) throws Exception {
		Method method = Builder.class.getDeclaredMethod("buildCrewAssignments", ServerLevel.class, VillageRef.class, VillageData.class);
		method.setAccessible(true);
		return (Map<UUID, ?>) method.invoke(null, level, ref, data);
	}

	private static boolean assignmentFlag(Object assignment, String name) throws Exception {
		Method method = assignment.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return (boolean) method.invoke(assignment);
	}

	private static int assignmentSlot(Object assignment) throws Exception {
		Method method = assignment.getClass().getDeclaredMethod("standSlot");
		method.setAccessible(true);
		return (int) method.invoke(assignment);
	}

	private static BlockPos assignmentOrigin(Object assignment) throws Exception {
		Method method = assignment.getClass().getDeclaredMethod("origin");
		method.setAccessible(true);
		return (BlockPos) method.invoke(assignment);
	}

	private static String crewFailureDetails(ServerLevel level, VillageRef ref, VillageData data, Villager helper,
			Map<UUID, ?> assignments) throws Exception {
		List<Villager> residents = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> true);
		boolean helperResident = residents.stream().anyMatch(v -> v.getUUID().equals(helper.getUUID()));
		var profession = helper.getVillagerData().profession();
		boolean unemployed = profession.is(VillagerProfession.NONE) && !profession.is(VillagerProfession.NITWIT);
		boolean compatibleMason = (boolean) invokeBuilder("compatibleMason", new Class<?>[]{Villager.class}, helper);
		boolean helperEligible = (boolean) invokeBuilder("eligibleBuildHelper",
			new Class<?>[]{ServerLevel.class, VillageRef.class, Villager.class}, level, ref, helper);
		VillageRef helperVillage = Villages.find(level, helper);
		boolean projectOwner = data.buildProjects.stream().filter(VillageData.BuildProject::active)
			.flatMap(project -> project.owner().stream()).anyMatch(helper.getUUID()::equals);
		boolean cargoCarrier = data.buildProjects.stream().flatMap(project -> project.cargoManifest().stream())
			.anyMatch(row -> row.startsWith(helper.getUUID() + "|"));
		List<String> mapRows = new ArrayList<>();
		for (var entry : assignments.entrySet()) {
			Object assignment = entry.getValue();
			mapRows.add(entry.getKey() + "->" + assignmentOrigin(assignment) + "/slot=" + assignmentSlot(assignment)
				+ "/helper=" + assignmentFlag(assignment, "helper"));
		}
		List<String> projectRows = new ArrayList<>();
		for (VillageData.BuildProject project : data.buildProjects) {
			UUID ownerId = project.owner().orElse(null);
			Villager owner = ownerId == null ? null : residents.stream()
				.filter(v -> v.getUUID().equals(ownerId)).findFirst().orElse(null);
			if (owner == null && ownerId != null && level.getEntity(ownerId) instanceof Villager loaded) owner = loaded;
			boolean teamProject = (boolean) invokeBuilder("teamBuildProject",
				new Class<?>[]{VillageData.BuildProject.class}, project);
			boolean prepared = project.construction().filter(ConstructionSite::prepared).isPresent();
			boolean ownerAvailable = owner != null && (boolean) invokeBuilder("availablePlanOwner",
				new Class<?>[]{ServerLevel.class, VillageRef.class, Villager.class, boolean.class}, level, ref, owner, false);
			boolean ownerInResidents = ownerId != null && residents.stream().anyMatch(v -> v.getUUID().equals(ownerId));
			VillageRef ownerVillage = owner == null ? null : Villages.find(level, owner);
			projectRows.add("{" + project.type() + "@" + project.origin() + ",active=" + project.active()
				+ ",team=" + teamProject + ",prepared=" + prepared + ",owner=" + ownerId
				+ ",ownerEntity=" + (owner == null ? "none" : owner.getUUID())
				+ ",ownerInResidents=" + ownerInResidents
				+ ",ownerAvailable=" + ownerAvailable + ",ownerVillage="
				+ (ownerVillage == null ? "none" : ownerVillage.bell()) + "}");
		}
		return "helper={uuid=" + helper.getUUID() + ",pos=" + helper.blockPosition() + ",resident=" + helperResident
			+ ",alive=" + helper.isAlive() + ",baby=" + helper.isBaby() + ",sleeping=" + helper.isSleeping()
			+ ",trading=" + helper.isTrading() + ",noAI=" + helper.isNoAi() + ",guest="
			+ de.villagerplus.village.Guests.isGuest(helper) + ",away=" + de.villagerplus.village.Expedition.isAway(helper)
			+ ",railPassenger=" + de.villagerplus.village.RailTransport.isPassenger(helper)
			+ ",caravanCarrier=" + de.villagerplus.village.Caravan.carryingFor(helper)
			+ ",shopOwner=" + (de.villagerplus.village.Market.shop(ref, helper.getUUID()) != null)
			+ ",profession=" + profession.unwrapKey().map(key -> key.identifier().toString()).orElse("unkeyed")
			+ ",unemployed=" + unemployed + ",compatibleMason=" + compatibleMason + ",projectOwner=" + projectOwner
			+ ",cargoCarrier=" + cargoCarrier + ",eligible=" + helperEligible + ",village="
			+ (helperVillage == null ? "none" : helperVillage.bell()) + "}; residents="
			+ residents.stream().map(v -> v.getUUID() + "/" + v.getVillagerData().profession().unwrapKey()
				.map(key -> key.identifier().getPath()).orElse("unkeyed")).toList()
			+ "; projects=" + projectRows + "; assignments=" + mapRows;
	}

	private static Object invokeBuilder(String name, Class<?>[] signature, Object... args) throws Exception {
		Method method = Builder.class.getDeclaredMethod(name, signature);
		method.setAccessible(true);
		return method.invoke(null, args);
	}

	private static int count(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
		return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
	}

	private static Map<String, Integer> wanted(List<String> entries) {
		Map<String, Integer> result = new LinkedHashMap<>();
		for (String entry : entries) {
			int split = entry.lastIndexOf('#');
			if (split <= 0) continue;
			result.put(entry.substring(0, split), Integer.parseInt(entry.substring(split + 1)));
		}
		return result;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("BUILDER PROJECT CHECK FAILED: " + message);
	}
}
