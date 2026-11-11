package de.villagerplus.job;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Journal;
import de.villagerplus.village.Lager;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Grouped classroom, lesson, XP persistence, and adult-promotion checks. */
public final class SchoolChecks {
	private record Cell(BlockState state, CompoundTag data) {}

	private SchoolChecks() {}

	public static int run(ServerLevel level, VillageRef ref) {
		// Keep the fixture in the reserved test patch, but vary its exact origin so a stale
		// persisted school-cargo marker from a prior run cannot be mistaken for this fixture's work.
		BlockPos patchCenter = ref.bell().offset(40, 0, 40);
		long runSeed = level.getGameTime() ^ java.util.UUID.randomUUID().getLeastSignificantBits();
		int dx = (int) Math.floorMod(runSeed, 5L) - 2;
		int dz = (int) Math.floorMod(runSeed >>> 3, 5L) - 2;
		if (dx == 0 && dz == 0) dx = 1;
		BlockPos plot = patchCenter.offset(dx, 0, dz);
		level.getChunkAt(plot);
		int floorY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, plot.getX(), plot.getZ());
		BlockPos school = new BlockPos(plot.getX(), floorY, plot.getZ());
		BlockPos fixtureBell = school.west(12);
		BlockPos fixtureChest = fixtureBell.west();
		BlockPos privateChest = fixtureBell.east();
		BlockPos fixtureStand = fixtureChest.west(3);
		Map<BlockPos, Cell> before = snapshot(level, school, fixtureBell, fixtureBell.below(), fixtureChest, privateChest,
			fixtureStand.below(), fixtureStand, fixtureStand.above());
		VillageRef fixture = null;
		Container schoolStock = null;
		Container privateStock = null;
		Journal.Data oldJournal = null;
		Villager child = null;
		Villager foreignChild = null;
		Villager teacher = null;
		int checks = 0;
		try {
			checks += schoolBlueprintChecks();
			level.setBlock(fixtureBell.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(fixtureBell, Blocks.BELL.defaultBlockState(), 3);
			level.setBlock(fixtureChest, Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(privateChest, Blocks.CHEST.defaultBlockState(), 3);
			// Provide one known safe approach tile on the side opposite the private chest.
			// Its three cells are included in the block-entity snapshot above and restored in finally.
			if (level.getBlockEntity(fixtureStand.below()) != null || level.getBlockEntity(fixtureStand) != null
				|| level.getBlockEntity(fixtureStand.above()) != null) {
				throw new IllegalStateException("School checks: reserved public-chest approach pad contains a block entity");
			}
			level.setBlock(fixtureStand.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(fixtureStand, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(fixtureStand.above(), Blocks.AIR.defaultBlockState(), 3);
			if (!safeStand(level, fixtureStand)) throw new IllegalStateException("School checks: public-chest approach pad is not safe");
			var bellEntity = level.getBlockEntity(fixtureBell);
			if (bellEntity == null) throw new IllegalStateException("School checks: village bell fixture is missing");
			VillageData fixtureData = new VillageData();
			fixtureData.chest = Optional.of(fixtureChest);
			fixtureData.extra.lagerChests.add(privateChest);
			bellEntity.setAttached(VillagerPlus.VILLAGE, fixtureData);
			fixture = new VillageRef(fixtureBell, fixtureData, bellEntity);
			schoolStock = Lager.publicContainerAt(level, fixtureChest);
			privateStock = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, privateChest);
			if (schoolStock == null) throw new IllegalStateException("School checks: public school supply chest is missing");
			if (privateStock == null) throw new IllegalStateException("School checks: private school fixture chest is missing");
			level.getBlockEntity(privateChest).setAttached(VillagerPlus.OWNER, java.util.UUID.randomUUID());
			// Clear only public stores; owner metadata must be in place first so the adjacent
			// private chest and its pre-existing contents are never swept into the fixture reset.
			clearPublicFixtureStock(level, fixture);
			privateStock.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LECTERN, 1));
			privateStock.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 36));
			privateStock.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOOK, 12));
			check(!Lager.publicStorageAt(level, privateChest) && !Lager.materialChests(level, fixture).contains(privateChest),
				"the owner-marked side chest is excluded from the public school supply list");
			checks++;
			oldJournal = bellEntity.getAttached(VillagerPlus.JOURNAL);
			fixture.data().extra.school = Optional.of(school);
			openPlot(level, school);
			child = EntityTypes.VILLAGER.spawn(level, school, EntitySpawnReason.COMMAND);
			if (child == null) throw new IllegalStateException("School checks: child fixture could not spawn");
			child.addTag("vp_school_fixture");
			child.setInvulnerable(true);
			child.setNoAi(true);
			child.setAge(-24000);
			place(child, school);
			child.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), fixtureBell));
			Apprentices.plan(child, "minecraft:farmer");

			Ctx childCtx = context(level, child, fixture, level.getGameTime());
			check(!School.attend(childCtx, Phase.WORK_A) && Apprentices.get(child).lessonXp() == 0,
				"an unbuilt classroom cannot enroll a child or award lesson XP");
			checks++;
			BlockPos outsideLectern = school.east(2);
			level.setBlock(outsideLectern, Blocks.LECTERN.defaultBlockState(), 3);
			level.setBlock(outsideLectern.above(3), Blocks.OAK_PLANKS.defaultBlockState(), 3);
			School.clearTransientStateForChecks(level, fixture, school);
			check(School.lectern(level, school) == null,
				"an outdoor lectern under a small roof is not accepted as a classroom");
			checks++;
			openPlot(level, school);

			buildRoom(level, school, false);
			School.clearTransientStateForChecks(level, fixture, school);
			childCtx = context(level, child, fixture, level.getGameTime());
			check(!School.attend(childCtx, Phase.WORK_A) && Apprentices.get(child).lessonXp() == 0,
				"a lectern without an actual bench is not a usable classroom");
			checks++;

			buildRoom(level, school, true);
			School.clearTransientStateForChecks(level, fixture, school);
			BlockPos lectern = School.lectern(level, school);
			if (lectern == null) throw new IllegalStateException("School checks: classroom lectern was not found");
			Direction facing = level.getBlockState(lectern).getValue(LecternBlock.FACING);
			BlockPos teacherStand = lectern.relative(facing);
			BlockPos studentStand = school;
			place(child, studentStand);
			childCtx = context(level, child, fixture, level.getGameTime());
			check(School.attend(childCtx, Phase.WORK_A) && Apprentices.get(child).lessonXp() == 0,
				"a seated child may wait for a teacher but cannot earn XP without one");
			checks++;

			teacher = EntityTypes.VILLAGER.spawn(level, teacherStand, EntitySpawnReason.COMMAND);
			if (teacher == null) throw new IllegalStateException("School checks: teacher fixture could not spawn");
			teacher.addTag("vp_school_fixture");
			teacher.setInvulnerable(true);
			teacher.setNoAi(true);
			ResourceKey<VillagerProfession> librarian = ResourceKey.create(Registries.VILLAGER_PROFESSION,
				Identifier.fromNamespaceAndPath("minecraft", "librarian"));
			teacher.setVillagerData(teacher.getVillagerData().withProfession(level.registryAccess(), librarian).withLevel(1));
			place(teacher, teacherStand);
			teacher.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), fixtureBell));
			School.clearTransientStateForChecks(level, fixture, school);
			childCtx = context(level, child, fixture, level.getGameTime());
			check(School.attend(childCtx, Phase.WORK_A) && Apprentices.get(child).lessonXp() == 0,
				"a teacher at the lectern grants no XP before starting a lesson");
			checks++;

			Ctx teacherCtx = context(level, teacher, fixture, level.getGameTime());
			foreignChild = EntityTypes.VILLAGER.spawn(level, studentStand, EntitySpawnReason.COMMAND);
			if (foreignChild == null) throw new IllegalStateException("School checks: neighboring child fixture could not spawn");
			foreignChild.addTag("vp_school_fixture");
			foreignChild.setInvulnerable(true);
			foreignChild.setNoAi(true);
			foreignChild.setAge(-24000);
			place(child, school.west(2));
			place(foreignChild, studentStand);
			foreignChild.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
			check(!School.attend(context(level, foreignChild, fixture, level.getGameTime()), Phase.WORK_A)
				&& Apprentices.get(foreignChild).lessonXp() == 0,
				"a child assigned to a neighboring bell cannot enroll at this school");
			checks++;
			check(!School.teach(teacherCtx, Phase.WORK_A),
				"a nearby neighboring child cannot start a lesson for this village");
			checks++;
			place(child, studentStand);
			check(School.teach(teacherCtx, Phase.WORK_A) && School.taught(level, fixture),
				"the assigned teacher starts a lesson only with an eligible pupil on a real bench");
			checks++;
			long lessonAt = level.getGameTime();
			childCtx = context(level, child, fixture, lessonAt);
			check(School.attend(childCtx, Phase.WORK_A) && Apprentices.get(child).lessonXp() == 3 && child.getVillagerXp() == 3,
				"one active classroom lesson awards three persistent and real villager XP");
			checks++;
			childCtx = context(level, child, fixture, lessonAt + 200);
			check(School.attend(childCtx, Phase.WORK_A) && Apprentices.get(child).lessonXp() == 3 && child.getVillagerXp() == 3,
				"the same lesson cannot award XP twice, even after the student cooldown");
			checks++;

			School.slowGrowth(child);
			check(child.isBaby(), "school attendance leaves a child in the child age range");
			checks++;
			Apprentices.awardLesson(child, Apprentices.QUALIFICATION_XP - Apprentices.get(child).lessonXp());
			var persisted = Apprentices.Progress.CODEC.parse(JsonOps.INSTANCE,
				Apprentices.Progress.CODEC.encodeStart(JsonOps.INSTANCE, Apprentices.get(child)).getOrThrow()).getOrThrow();
			check(persisted.equals(Apprentices.get(child)) && persisted.lastLessonAt() == lessonAt,
				"apprenticeship target, qualification XP, and the last lesson survive codec reload");
			checks++;
			child.setAge(0);
			child.setAttached(VillagerPlus.APPRENTICE, persisted);
			check(Apprentices.completeIfAdult(level, child)
				&& professionPath(child).equals("farmer")
				&& child.getVillagerData().level() == 3
				&& Apprentices.get(child).target().isEmpty(),
				"a codec-restored qualified apprentice becomes a rank-three adult once");
			checks++;
			ResourceKey<VillagerProfession> mason = ResourceKey.create(Registries.VILLAGER_PROFESSION,
				Identifier.fromNamespaceAndPath("minecraft", "mason"));
			child.setVillagerData(child.getVillagerData().withProfession(level.registryAccess(), mason).withLevel(3));
			check(!Apprentices.completeIfAdult(level, child) && professionPath(child).equals("mason"),
				"adult promotion does not overwrite a later career change");
			checks++;

			// A covered porch shares the building bounds but is not a classroom.
			buildRoom(level, school, false);
			BlockPos porch = school.east(4);
			level.setBlock(porch.below(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
			level.setBlock(porch, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(porch.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(porch.above(2), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(porch.above(3), Blocks.OAK_PLANKS.defaultBlockState(), 3);
			var bounds = new net.minecraft.world.level.levelgen.structure.BoundingBox(
				school.getX() - 3, school.getY() - 1, school.getZ() - 3,
				school.getX() + 4, school.getY() + 3, school.getZ() + 3);
			Ctx furnishing = context(level, teacher, fixture, level.getGameTime());
			BlockPos classroomLectern = school.north(2);
			// Reset between the attendance and furnishing scenarios so this shortage assertion
			// cannot inherit any public stock written by an earlier grouped check.
			clearPublicFixtureStock(level, fixture);
			String publicSourcesBefore = publicStockSummary(level, fixture);
			int nearbyCargoBefore = schoolCargoCarrierCount(level, school);
			boolean missingFurnitureWaited = !de.villagerplus.build.Furnisher.furnishSchool(furnishing, fixture, bounds, Blocks.OAK_STAIRS);
			check(missingFurnitureWaited
				&& level.getBlockState(classroomLectern).is(Blocks.LECTERN),
				"an existing classroom lectern is respected and missing furniture waits without consuming absent stock"
					+ " (waited=" + missingFurnitureWaited + ", planks="
					+ JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS))
					+ ", books=" + JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.BOOK))
					+ ", cargo=" + hasSchoolCargo(teacher) + "/" + nearbyCargoBefore
					+ ", public sources=" + publicSourcesBefore + ", pult="
					+ level.getBlockState(classroomLectern).getBlock() + ")");
			checks++;
			check(JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS)
				|| stack.is(net.minecraft.world.item.Items.BOOK)) == 0,
				"a material shortage leaves the real public chest unchanged");
			checks++;
			check(JobUtil.count(privateStock, stack -> stack.is(net.minecraft.world.item.Items.LECTERN)) == 1,
				"school furnishing never treats a registered private chest as public material stock");
			checks++;
			check(JobUtil.count(privateStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS)
				|| stack.is(net.minecraft.world.item.Items.BOOK)) == 48,
				"loaded crafting recipes cannot consume inputs from an owner-marked private chest");
			checks++;
			clearPublicFixtureStock(level, fixture);
			// Vanilla's loaded bookshelf recipe costs six planks and three books for one shelf;
			// twelve shelves plus eight paid stairs therefore need 84 planks and 36 books.
			schoolStock.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 64));
			schoolStock.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOOK, 36));
			schoolStock.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LECTERN, 1));
			schoolStock.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 20));
			schoolStock.setChanged();
			int remotePlanks = JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS));
			int remoteBooks = JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.BOOK));
			boolean remoteRequested = de.villagerplus.build.Furnisher.furnishSchool(furnishing, fixture, bounds, Blocks.OAK_STAIRS);
			BlockPos remoteTarget = furnishing.st().target;
			check(remoteRequested
				&& JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS)) == remotePlanks
				&& JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.BOOK)) == remoteBooks
				&& remoteTarget != null && !remoteTarget.equals(fixtureChest)
				&& remoteTarget.distSqr(fixtureChest) <= 9.0
				&& safeStand(level, remoteTarget),
				"a remote teacher only receives a route request and cannot withdraw or craft public materials from afar"
					+ " (requested=" + remoteRequested + ", planks=" + remotePlanks + "->"
					+ JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS))
					+ ", books=" + remoteBooks + "->"
					+ JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.BOOK))
					+ ", target=" + (remoteTarget == null ? "none" : remoteTarget.toShortString())
					+ ", targetSafe=" + (remoteTarget != null && safeStand(level, remoteTarget))
					+ ", rangeSq=" + (remoteTarget == null ? "n/a" : remoteTarget.distSqr(fixtureChest))
					+ ", sources=" + publicStockSummary(level, fixture) + ")");
			checks++;
			place(teacher, fixtureChest.east());
			JobState furnishingState = furnishing.st();
			long furnishNow = level.getGameTime();
			int furnishingAttempts = 0;
			boolean furnishingLoopContinued = false;
			boolean lowerShelfStandObserved = false;
			String lowerShelfStandEvidence = "none";
			String furnishingStop = "attempt-limit";
			for (int attempt = 0; attempt < 600; attempt++) {
				furnishingAttempts = attempt + 1;
				Ctx step = new Ctx(level, teacher, furnishingState, teacher.getAttachedOrCreate(VillagerPlus.MEMORY), fixture,
					furnishNow + attempt * 200L);
				furnishingLoopContinued = de.villagerplus.build.Furnisher.furnishSchool(step, fixture, bounds, Blocks.OAK_STAIRS);
				if (!furnishingLoopContinued) {
					furnishingStop = "furnishSchool=false";
					break;
				}
				furnishingStop = "furnishSchool=true";
				BlockPos markedTarget = schoolCargoTarget(teacher, net.minecraft.world.item.Items.BOOKSHELF);
				if (markedTarget != null && furnishingState.target != null
					&& furnishingState.target.getY() == markedTarget.getY() - 1
					&& bounds.isInside(furnishingState.target)
					&& de.villagerplus.build.Furnisher.isSchoolInterior(level, furnishingState.target)
					&& safeStand(level, furnishingState.target)
					&& withinSchoolPlacementReach(furnishingState.target, markedTarget)) {
					lowerShelfStandObserved = true;
					lowerShelfStandEvidence = furnishingState.target.toShortString() + " -> " + markedTarget.toShortString();
				}
				// This deterministic check advances a no-AI fixture along the requested WALK_TARGET;
				// production villagers still use vanilla navigation and only commit once physically there.
				if (furnishingState.target != null) place(teacher, furnishingState.target);
			}
			String furnishingStatus = School.furnishingStatus(level, school);
			int furnishedBenches = benchCount(furnishingStatus);
			check(furnishingStatus.startsWith("eingerichtet"),
				"loaded bookshelf and stair recipes repeatedly furnish a complete classroom from paid public materials"
					+ " (" + furnishingStatus + ", public planks="
					+ JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS))
					+ ", books=" + JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.BOOK))
					+ ", cargo=" + schoolCargoSummary(teacher) + ", teacher=" + teacher.blockPosition().toShortString()
					+ ", activity=" + furnishingState.activity + ", attempts=" + furnishingAttempts
					+ ", loop=" + furnishingStop + "/" + furnishingLoopContinued + ", last target="
					+ (furnishingState.target == null ? "none" : furnishingState.target.toShortString())
					+ ", targetSafe=" + (furnishingState.target != null && safeStand(level, furnishingState.target))
					+ ", cargoStand=" + schoolCargoStandSummary(level, bounds, teacher)
					+ ", lowerShelfStand=" + lowerShelfStandObserved + "[" + lowerShelfStandEvidence + "]"
					+ ", publicSlots=" + publicStockSlots(schoolStock) + ")");
			checks++;
			check(lowerShelfStandObserved,
				"an upper bookshelf can be installed from a safe, physically reachable lower adjacent floor tile"
					+ " (stand=" + lowerShelfStandEvidence + ", cargo=" + schoolCargoSummary(teacher) + ")");
			checks++;
			check(furnishedBenches > 1,
				"the paid stair recipe adds reachable seats beyond the existing classroom bench (" + furnishingStatus + ")");
			checks++;
			check(!hasSchoolCargo(teacher)
				&& teacher.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty(),
				"completed school deliveries clear their cargo marker and restore the teacher's previous hand");
			checks++;
			check(JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS)) <= 6
				&& JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.BOOK)) == 0
				&& JobUtil.count(schoolStock, stack -> stack.is(net.minecraft.world.item.Items.LECTERN)) == 1,
				"paid shelf and stair recipes consume exact inputs while an existing lectern avoids duplicate purchase");
			checks++;
			check(JobUtil.count(privateStock, stack -> stack.is(net.minecraft.world.item.Items.LECTERN)) == 1
				&& JobUtil.count(privateStock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS)
					|| stack.is(net.minecraft.world.item.Items.BOOK)) == 48,
				"the later public-stock furnishing retry still leaves private household materials untouched");
			checks++;
			check(level.getBlockState(porch).isAir() && level.getBlockState(porch.above()).isAir(),
				"school furniture does not spill onto a roofed exterior porch");
			checks++;
			boolean shelvesInside = false;
			for (BlockPos p : BlockPos.betweenClosed(school.offset(-2, 0, -2), school.offset(2, 1, 2))) {
				if (level.getBlockState(p).is(Blocks.BOOKSHELF)) { shelvesInside = true; break; }
			}
			check(shelvesInside, "enclosed classroom still receives bookshelves");
			checks++;
			return checks;
		} finally {
			if (teacher != null) {
				Villages.forget(teacher.getUUID());
				teacher.discard();
			}
			if (foreignChild != null) {
				Villages.forget(foreignChild.getUUID());
				foreignChild.discard();
			}
			if (child != null) {
				Villages.forget(child.getUUID());
				child.discard();
			}
			School.clearTransientStateForChecks(level, fixture, school);
			if (fixture != null) {
				if (oldJournal == null) fixture.bellEntity().removeAttached(VillagerPlus.JOURNAL);
				else fixture.bellEntity().setAttached(VillagerPlus.JOURNAL, oldJournal);
			}
			restore(level, before);
		}
	}

	private static int schoolBlueprintChecks() {
		int checks = 0;
		de.villagerplus.build.Palette[] palettes = {
			new de.villagerplus.build.Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
				Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE),
			new de.villagerplus.build.Palette(Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE,
				Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB, Blocks.GLASS_PANE, Blocks.SANDSTONE, Blocks.OAK_DOOR, Blocks.OAK_FENCE)
		};
		long[] seeds = {0L, 1L, 17L, 4096L};
		for (long seed : seeds) {
			var design = de.villagerplus.build.Architect.design(de.villagerplus.build.Architect.Kind.SCHOOL, seed, true);
			check(design.hw() == 3 && design.hd() == 3 && design.wallH() == 4 && !design.ridgeAlongPerp()
				&& design.doorOffset() == 0 && design.porch() && !design.chimney() && design.half() == 5
				&& !de.villagerplus.build.Architect.multiStorey(design) && design.headroom() >= design.wallH() + design.hd() + design.overhang() + 1,
				"new schools keep the seven-by-seven, four-course classroom and allow a two-block covered entrance within half=5 clearance");
			checks++;
			int legacyVariant = (int)(seed << 2);
			check(de.villagerplus.build.Architect.designForPlan(de.villagerplus.build.Architect.Kind.SCHOOL, legacyVariant)
				.equals(de.villagerplus.build.Architect.design(de.villagerplus.build.Architect.Kind.SCHOOL, seed, false)),
				"saved school plans without the new role marker retain their original seeded design");
			checks++;
			int currentVariant = de.villagerplus.build.Architect.withRoleMarkerVersion(de.villagerplus.build.Architect.Kind.SCHOOL, legacyVariant);
			check(de.villagerplus.build.Architect.designForPlan(de.villagerplus.build.Architect.Kind.SCHOOL, currentVariant).equals(design),
				"new role-marked school plans reconstruct the exact classroom blueprint from their persisted variant");
			checks++;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				for (de.villagerplus.build.Palette palette : palettes) {
					BlockPos center = BlockPos.ZERO;
					java.util.List<de.villagerplus.build.Step> steps = de.villagerplus.build.Architect.build(design, palette, center, direction);
					java.util.Map<BlockPos, de.villagerplus.build.Step> at = new java.util.HashMap<>();
					for (var step : steps) at.put(step.pos(), step);
					boolean classroomWalls = true;
					for (int y = 0; y < design.wallH(); y++) {
						for (int side = -design.hw(); side <= design.hw(); side++) {
							classroomWalls &= at.containsKey(schoolAt(center, direction, side, design.hd(), y))
								&& at.containsKey(schoolAt(center, direction, side, -design.hd(), y));
							if (Math.abs(side) < design.hw()) {
								classroomWalls &= at.containsKey(schoolAt(center, direction, design.hw(), side, y))
									&& at.containsKey(schoolAt(center, direction, -design.hw(), side, y));
							}
						}
					}
					check(classroomWalls,
						"the enclosed classroom keeps a complete seven-by-seven perimeter through all four wall courses");
					checks++;
					Block porchRoof = palette.roofSlab() instanceof net.minecraft.world.level.block.SlabBlock
						? palette.roofSlab() : palette.roofFull();
					boolean porchShape = design.porch() && design.half() == 5;
					for (int side : new int[] {-1, 1}) {
						for (int y = 0; y <= 1; y++) {
							porchShape &= stateAt(at, schoolAt(center, direction, side, design.hd() + 2, y), Blocks.AIR).getBlock() == palette.fence();
						}
					}
					for (int side = -1; side <= 1; side++) {
						for (int depth = design.hd() + 1; depth <= design.hd() + 2; depth++) {
							porchShape &= stateAt(at, schoolAt(center, direction, side, depth, 2), Blocks.AIR).getBlock() == porchRoof;
						}
					}
					boolean withinClearance = steps.stream().allMatch(step ->
						Math.max(Math.abs(step.pos().getX() - center.getX()), Math.abs(step.pos().getZ() - center.getZ())) <= design.half());
					check(porchShape && withinClearance,
						"the covered entry is exactly two blocks deep with two-block posts, and every build step stays within half=5");
					checks++;
					BlockPos door = schoolAt(center, direction, 0, 3, 0);
					var lowerDoor = stateAt(at, door, Blocks.OAK_DOOR);
					var upperDoor = stateAt(at, door.above(), Blocks.OAK_DOOR);
					check(steps.size() == at.size() && lowerDoor.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
						&& lowerDoor.getValue(net.minecraft.world.level.block.DoorBlock.FACING) == direction.getOpposite()
						&& lowerDoor.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
						&& upperDoor.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
						&& stateAt(at, schoolAt(center, direction, -1, 3, 0), Blocks.AIR).getBlock() == palette.frame()
						&& stateAt(at, schoolAt(center, direction, 1, 3, 1), Blocks.AIR).getBlock() == palette.frame(),
						"school door remains framed, paired, and correctly oriented toward " + direction);
					checks++;
					BlockPos leftWindow = schoolAt(center, direction, -2, 3, 1), rightWindow = schoolAt(center, direction, 2, 3, 1);
					check(stateAt(at, leftWindow, Blocks.AIR).getBlock() == palette.window()
						&& stateAt(at, rightWindow, Blocks.AIR).getBlock() == palette.window()
						&& stateAt(at, schoolAt(center, direction, 0, 3, 6), Blocks.AIR).getBlock() == palette.window(),
						"school facade has symmetric front windows and a window under its street-facing gable");
					checks++;
					BlockPos lectern = schoolAt(center, direction, 0, -2, 0);
					check(stateAt(at, lectern, Blocks.AIR).is(Blocks.LECTERN)
						&& stateAt(at, lectern, Blocks.AIR).getValue(net.minecraft.world.level.block.LecternBlock.FACING) == direction
						&& stateAt(at, schoolAt(center, direction, -2, -2, 0), Blocks.AIR).is(Blocks.BOOKSHELF)
						&& stateAt(at, schoolAt(center, direction, 2, -2, 0), Blocks.AIR).is(Blocks.BOOKSHELF),
						"the teaching lectern and both paid bookcases are inside the room facing the entry");
					checks++;
					Block stair = palette.roofStair() instanceof net.minecraft.world.level.block.StairBlock ? palette.roofStair() : Blocks.OAK_STAIRS;
					Block schoolMarkerStone = de.villagerplus.build.Supply.obtainable(Blocks.STONE_BRICKS)
						? Blocks.STONE_BRICKS : Blocks.COBBLESTONE;
					check(stateAt(at, schoolAt(center, direction, -1, 0, 0), stair).getBlock() == stair
						&& stateAt(at, schoolAt(center, direction, -1, 0, 0), stair).getValue(net.minecraft.world.level.block.StairBlock.FACING) == direction.getOpposite()
						&& stateAt(at, schoolAt(center, direction, 1, 0, 0), stair).getBlock() == stair
						&& stateAt(at, schoolAt(center, direction, 0, 3, 2), Blocks.AIR).getBlock() == schoolMarkerStone
						&& stateAt(at, schoolAt(center, direction, -1, 3, 2), Blocks.AIR).getBlock() == palette.frame()
						&& stateAt(at, schoolAt(center, direction, 1, 3, 2), Blocks.AIR).getBlock() == palette.frame()
						&& steps.stream().filter(step -> step.item() == schoolMarkerStone.asItem() && step.cost() == 1).count() == 1
						&& steps.stream().filter(step -> step.item() == Blocks.BOOKSHELF.asItem()).count() == 2
						&& steps.stream().noneMatch(step -> step.item() == Blocks.CHISELED_BOOKSHELF.asItem()),
						"two palette-matched seats and a paid stone lintel between timber brackets keep bookshelves inside");
					checks++;
					boolean walkableCenter = true;
					for (int j = 2; j >= -1; j--) {
						BlockPos feet = schoolAt(center, direction, 0, j, 0);
						BlockPos head = feet.above();
						walkableCenter &= !at.containsKey(feet) && !at.containsKey(head);
					}
					BlockPos leftGableStair = schoolAt(center, direction, -design.hw(), design.hd(), design.wallH() + 1);
					BlockPos rightGableStair = schoolAt(center, direction, design.hw(), design.hd(), design.wallH() + 1);
					var leftGableState = stateAt(at, leftGableStair, Blocks.AIR);
					var rightGableState = stateAt(at, rightGableStair, Blocks.AIR);
					Direction acrossFacade = direction.getClockWise();
					check(walkableCenter && leftGableState.getBlock() == palette.roofStair()
						&& leftGableState.getValue(net.minecraft.world.level.block.StairBlock.FACING) == acrossFacade
						&& rightGableState.getBlock() == palette.roofStair()
						&& rightGableState.getValue(net.minecraft.world.level.block.StairBlock.FACING) == acrossFacade.getOpposite(),
						"the entry-to-teacher centerline stays clear and both pitched gable stairs rise toward the roof ridge");
					checks++;
					boolean statesValid = true;
					for (var step : steps) {
						net.minecraft.world.level.block.Block source = step.item() == null
							? (step.linkedTo() == null ? Blocks.AIR : Blocks.OAK_DOOR)
							: net.minecraft.world.level.block.Block.byItem(step.item());
						statesValid &= step.make().apply(source) != null;
					}
					check(statesValid, "all school palette and door states construct safely for " + direction);
					checks++;
				}
			}
		}
		return checks;
	}

	private static BlockPos schoolAt(BlockPos center, Direction direction, int side, int depth, int y) {
		return center.relative(direction.getClockWise(), side).relative(direction, depth).above(y);
	}

	private static net.minecraft.world.level.block.state.BlockState stateAt(
		java.util.Map<BlockPos, de.villagerplus.build.Step> steps, BlockPos pos, net.minecraft.world.level.block.Block linkedBlock) {
		var step = steps.get(pos);
		if (step == null) return Blocks.AIR.defaultBlockState();
		net.minecraft.world.level.block.Block source = step.item() == null
			? (step.linkedTo() == null ? Blocks.AIR : linkedBlock)
			: net.minecraft.world.level.block.Block.byItem(step.item());
		return step.make().apply(source);
	}

	private static Ctx context(ServerLevel level, Villager villager, VillageRef ref, long now) {
		return new Ctx(level, villager, new JobState(), villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static String professionPath(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey().map(key -> key.identifier().getPath()).orElse("");
	}

	private static boolean hasSchoolCargo(Villager villager) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			var custom = villager.getInventory().getItem(slot).get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
			if (custom != null && custom.copyTag().contains("vp_school_furnish_dimension")) return true;
		}
		return false;
	}

	private static String schoolCargoSummary(Villager villager) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			var stack = villager.getInventory().getItem(slot);
			var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
			if (custom == null || !custom.copyTag().contains("vp_school_furnish_dimension")) continue;
			var tag = custom.copyTag();
			return stack.getItem() + "x" + stack.getCount()
				+ " target=" + schoolCargoPos(tag.getStringOr("vp_school_furnish_target", ""))
				+ " source=" + schoolCargoPos(tag.getStringOr("vp_school_furnish_source", ""))
				+ " origin=" + tag.getStringOr("vp_school_furnish_origin", "?")
				+ " dimension=" + tag.getStringOr("vp_school_furnish_dimension", "?");
		}
		return "none";
	}

	private static String schoolCargoPos(String packed) {
		try { return BlockPos.of(Long.parseLong(packed)).toShortString(); }
		catch (RuntimeException ignored) { return "invalid"; }
	}

	private static String schoolCargoStandSummary(ServerLevel level,
		net.minecraft.world.level.levelgen.structure.BoundingBox bounds, Villager villager) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			var cargo = villager.getInventory().getItem(slot);
			var custom = cargo.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
			if (custom == null || !custom.copyTag().contains("vp_school_furnish_dimension")) continue;
			var tag = custom.copyTag();
			BlockPos target = schoolCargoBlockPos(tag.getStringOr("vp_school_furnish_target", ""));
			if (target == null) return "invalid-target";
			var block = net.minecraft.world.level.block.Block.byItem(cargo.getItem());
			if (block instanceof StairBlock) {
				var plan = de.villagerplus.build.Furnisher.schoolBenchPlan(level, bounds);
				return plan.additions().stream().filter(seat -> seat.position().equals(target))
					.map(seat -> seat.stand().toShortString() + ":safe=" + safeStand(level, seat.stand()))
					.findFirst().orElse("no-seat-entry; additions=" + plan.additions().size());
			}
			java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos adjacent = target.relative(direction);
				candidates.add(adjacent);
				candidates.add(adjacent.below());
			}
			return candidates.stream().filter(bounds::isInside)
				.map(pos -> pos.toShortString() + ":safe=" + safeStand(level, pos)
					+ ":interior=" + de.villagerplus.build.Furnisher.isSchoolInterior(level, pos)
					+ ":range=" + withinSchoolPlacementReach(pos, target))
				.toList().toString();
		}
		return "no-cargo";
	}

	private static BlockPos schoolCargoTarget(Villager villager, net.minecraft.world.item.Item item) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			var stack = villager.getInventory().getItem(slot);
			if (!stack.is(item)) continue;
			var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
			if (custom == null || !custom.copyTag().contains("vp_school_furnish_dimension")) continue;
			return schoolCargoBlockPos(custom.copyTag().getStringOr("vp_school_furnish_target", ""));
		}
		return null;
	}

	private static boolean withinSchoolPlacementReach(BlockPos stand, BlockPos target) {
		double dx = stand.getX() - target.getX();
		double dy = stand.getY() + 1.62 - (target.getY() + 0.5);
		double dz = stand.getZ() - target.getZ();
		return dx * dx + dy * dy + dz * dz <= 4.5 * 4.5;
	}

	private static BlockPos schoolCargoBlockPos(String packed) {
		try { return BlockPos.of(Long.parseLong(packed)); }
		catch (RuntimeException ignored) { return null; }
	}

	private static String publicStockSlots(Container stock) {
		java.util.List<String> slots = new java.util.ArrayList<>();
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			var stack = stock.getItem(slot);
			if (!stack.isEmpty()) slots.add(slot + ":" + stack.getItem() + "x" + stack.getCount());
		}
		return slots.toString();
	}

	private static int schoolCargoCarrierCount(ServerLevel level, BlockPos school) {
		String origin = Long.toString(school.asLong());
		String dimension = level.dimension().identifier().toString();
		return level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(school).inflate(160.0), villager -> {
			for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
				var custom = villager.getInventory().getItem(slot).get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
				if (custom == null) continue;
				var tag = custom.copyTag();
				if (tag.getStringOr("vp_school_furnish_origin", "").equals(origin)
					&& tag.getStringOr("vp_school_furnish_dimension", "").equals(dimension)) return true;
			}
			return false;
		}).size();
	}

	private static String publicStockSummary(ServerLevel level, VillageRef ref) {
		return Lager.materialChests(level, ref).stream().map(pos -> {
			Container stock = Lager.publicContainerAt(level, pos);
			return pos.toShortString() + "[planks="
				+ JobUtil.count(stock, stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS))
				+ ", books=" + JobUtil.count(stock, stack -> stack.is(net.minecraft.world.item.Items.BOOK))
				+ ", stairs=" + JobUtil.count(stock, stack -> stack.is(net.minecraft.world.item.Items.OAK_STAIRS))
				+ ", lecterns=" + JobUtil.count(stock, stack -> stack.is(net.minecraft.world.item.Items.LECTERN)) + "]";
		}).toList().toString();
	}

	private static void clearPublicFixtureStock(ServerLevel level, VillageRef ref) {
		for (BlockPos pos : Lager.materialChests(level, ref)) {
			Container stock = Lager.publicContainerAt(level, pos);
			if (stock == null) continue;
			stock.clearContent();
			stock.setChanged();
		}
	}

	private static int benchCount(String status) {
		int label = status.indexOf("Bänke ");
		if (label < 0) return 0;
		int start = label + "Bänke ".length();
		int end = status.indexOf('/', start);
		if (end < 0) return 0;
		try { return Integer.parseInt(status.substring(start, end)); }
		catch (NumberFormatException ignored) { return 0; }
	}

	private static void place(Villager villager, BlockPos pos) {
		villager.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}

	private static boolean safeStand(ServerLevel level, BlockPos feet) {
		return level.hasChunkAt(feet) && level.hasChunkAt(feet.above()) && level.hasChunkAt(feet.below())
			&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
			&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
	}

	private static void buildRoom(ServerLevel level, BlockPos school, boolean withBench) {
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
			level.setBlock(school.offset(x, -1, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
			for (int y = 0; y <= 2; y++) {
				boolean wall = Math.abs(x) == 3 || Math.abs(z) == 3;
				level.setBlock(school.offset(x, y, z), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
			level.setBlock(school.offset(x, 3, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
		}
		BlockPos door = school.south(3);
		var doorState = Blocks.OAK_DOOR.defaultBlockState()
			.setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.NORTH);
		level.setBlock(door, doorState, 3);
		level.setBlock(door.above(), doorState
			.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
				net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
		BlockPos lectern = school.north(2);
		level.setBlock(lectern, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH), 3);
		if (withBench) {
			level.setBlock(school.south(1), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH), 3);
		}
	}

	private static void openPlot(ServerLevel level, BlockPos school) {
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
			level.setBlock(school.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int y = 0; y <= 3; y++) level.setBlock(school.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static Map<BlockPos, Cell> snapshot(ServerLevel level, BlockPos school, BlockPos... extra) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(school.offset(-3, -1, -3), school.offset(4, 3, 3))) {
			save(level, before, pos.immutable());
		}
		for (BlockPos pos : extra) save(level, before, pos);
		return before;
	}

	private static void save(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		level.getChunkAt(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		before.put(pos, new Cell(level.getBlockState(pos),
			blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		before.keySet().forEach(level::removeBlockEntity);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.data() != null) {
				BlockEntity blockEntity = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
				if (blockEntity != null) level.setBlockEntity(blockEntity);
			}
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("School: " + message);
	}
}
