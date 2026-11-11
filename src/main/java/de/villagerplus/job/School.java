package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Expedition;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Journal;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** Children attend the real school room, with qualification XP awarded only for an active teacher-led lesson. */
public final class School {
	private static final int LESSON_TICKS = 200;
	private static final int LESSON_WINDOW_TICKS = 400;
	private static final int TEACHER_CACHE_TICKS = 100;
	private static final int FURNISH_RETRY_TICKS = 40;

	private record LocationKey(ResourceKey<Level> dimension, long position) {}
	private record TeacherCache(long checkedAt, UUID teacher) {}
	private record Lesson(UUID teacher, long gameTime, long lectern) {}
	private record Seat(BlockPos bench, BlockPos stand) {}
	private record StudentSeat(Villager child, Seat seat) {}
	private record Classroom(BlockPos school, BlockPos lectern, Direction facing, List<Seat> seats) {}

	private static final Map<LocationKey, BlockPos> LECTERNS = new HashMap<>();
	private static final Map<LocationKey, Long> FURNISH_TRIED = new HashMap<>();
	private static final Map<LocationKey, TeacherCache> TEACHERS = new HashMap<>();
	private static final Map<LocationKey, Lesson> TAUGHT = new HashMap<>();
	private static final Map<LocationKey, Long> JOURNAL_DAY = new HashMap<>();

	private School() {}

	static boolean isSchoolActivity(String activity) {
		return activity != null && activity.startsWith("lernt ");
	}

	/** Slows natural ageing by retaining a fraction of the ticks that vanilla removes. */
	public static void slowGrowth(Villager villager) {
		int age = villager.getAge();
		if (age < 0 && Config.GROWTH_FACTOR > 1) {
			int back = (int) (Config.JOB_INTERVAL * (1.0 - 1.0 / Config.GROWTH_FACTOR));
			villager.setAge(age - back);
		}
	}

	/** Finds an existing lectern inside the registered school footprint without adopting a porch or placing free furniture. */
	public static BlockPos lectern(ServerLevel level, BlockPos school) {
		if (school == null || !schoolFootprintLoaded(level, school)) return null;
		LocationKey key = new LocationKey(level.dimension(), school.asLong());
		BoundingBox box = schoolBox(school);
		BlockPos cached = LECTERNS.get(key);
		if (cached != null && box.isInside(cached) && level.hasChunkAt(cached)
			&& level.getBlockState(cached).getBlock() instanceof LecternBlock
			&& de.villagerplus.build.Furnisher.isSchoolInterior(level, cached)) {
			return cached;
		}
		LECTERNS.remove(key);
		BlockPos existing = findLectern(level, box);
		if (existing != null) {
			LECTERNS.put(key, existing);
			return existing;
		}
		return null;
	}

	private static boolean ensureFurnished(Ctx c, VillageRef ref) {
		BlockPos school = ref.data().extra.school.orElse(null);
		if (school == null || !schoolFootprintLoaded(c.level(), school)) return false;
		LocationKey key = new LocationKey(c.level().dimension(), school.asLong());
		if (c.now() < FURNISH_TRIED.getOrDefault(key, Long.MIN_VALUE)) return false;
		FURNISH_TRIED.put(key, c.now() + FURNISH_RETRY_TICKS);
		return de.villagerplus.build.Furnisher.furnishSchool(c, ref, schoolBox(school), null);
	}

	/** Short public status for the village overview; incomplete furnishing retries from public stock. */
	public static String furnishingStatus(ServerLevel level, BlockPos school) {
		if (school == null) return "Schule fehlt";
		if (!schoolFootprintLoaded(level, school)) return "Schule nicht geladen";
		BoundingBox box = schoolBox(school);
		int lecterns = findLectern(level, box) == null ? 0 : 1;
		int shelves = 0;
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			if (!de.villagerplus.build.Furnisher.isSchoolInterior(level, pos)) continue;
			if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.BOOKSHELF)) shelves++;
		}
		var benches = de.villagerplus.build.Furnisher.schoolBenchPlan(level, box);
		int capacity = benches.capacity();
		int usable = benches.existing().size();
		boolean complete = lecterns > 0 && shelves >= 12 && capacity > 0 && usable >= capacity;
		return (complete ? "eingerichtet" : "Einrichtung offen") + " (Pult " + lecterns + "/1, Regale "
			+ Math.min(12, shelves) + "/12, Bänke " + usable + "/" + capacity
			+ (capacity == 0 ? ", kein sicherer Sitz-/Zugangspfad" : "") + ")";
	}

	private static BlockPos findLectern(ServerLevel level, BoundingBox box) {
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			if (level.getBlockState(pos).getBlock() instanceof LecternBlock
				&& de.villagerplus.build.Furnisher.isSchoolInterior(level, pos)) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static BoundingBox schoolBox(BlockPos school) {
		// The generated school is seven by five blocks, centered on its registered origin.
		return new BoundingBox(school.getX() - 3, school.getY() - 1, school.getZ() - 3,
			school.getX() + 3, school.getY() + 3, school.getZ() + 3);
	}

	private static boolean schoolFootprintLoaded(ServerLevel level, BlockPos school) {
		BoundingBox box = schoolBox(school);
		return level.hasChunkAt(school)
			&& level.hasChunkAt(new BlockPos(box.minX(), school.getY(), box.minZ()))
			&& level.hasChunkAt(new BlockPos(box.minX(), school.getY(), box.maxZ()))
			&& level.hasChunkAt(new BlockPos(box.maxX(), school.getY(), box.minZ()))
			&& level.hasChunkAt(new BlockPos(box.maxX(), school.getY(), box.maxZ()));
	}

	private static Classroom classroom(ServerLevel level, VillageRef ref) {
		Optional<BlockPos> registered = ref.data().extra.school;
		if (registered.isEmpty()) return null;
		BlockPos school = registered.get();
		BlockPos lectern = lectern(level, school);
		if (lectern == null || !schoolBox(school).isInside(lectern) || !insideClassroom(level, lectern)) return null;
		Direction facing = level.getBlockState(lectern).getValue(LecternBlock.FACING);
		List<Seat> seats = classroomSeats(level, school, lectern);
		return seats.isEmpty() ? null : new Classroom(school, lectern, facing, seats);
	}

	/** Returns only usable stand positions beside actual stair benches inside the built school footprint. */
	private static List<Seat> classroomSeats(ServerLevel level, BlockPos school, BlockPos lectern) {
		BoundingBox box = schoolBox(school);
		return de.villagerplus.build.Furnisher.schoolBenchPlan(level, box).existing().stream()
			.map(seat -> new Seat(seat.position(), seat.stand())).toList();
	}

	private static boolean insideClassroom(ServerLevel level, BlockPos position) {
		boolean roofed = false;
		for (int dy = 1; dy <= 5; dy++) {
			BlockPos above = position.above(dy);
			if (!level.hasChunkAt(above)) return false;
			var state = level.getBlockState(above);
			if (!state.isAir() && !state.canBeReplaced()) {
				roofed = true;
				break;
			}
		}
		if (!roofed) return false;
		int enclosedSides = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			boolean wall = false;
			for (int distance = 1; distance <= 4 && !wall; distance++) {
				BlockPos candidate = position.relative(direction, distance);
				wall = level.hasChunkAt(candidate)
					&& level.getBlockState(candidate).isFaceSturdy(level, candidate, direction.getOpposite());
			}
			if (wall) enclosedSides++;
		}
		// One side may be the classroom entrance; three enclosed sides plus a roof distinguish it from outdoor stairs.
		return enclosedSides >= 3;
	}

	private static boolean safeStand(ServerLevel level, BlockPos feet) {
		return level.hasChunkAt(feet) && level.hasChunkAt(feet.above()) && level.hasChunkAt(feet.below())
			&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
			&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
	}

	private static List<Villager> children(ServerLevel level, VillageRef ref) {
		List<Villager> children = Villages.residents(level, ref, Config.VILLAGE_RADIUS,
			villager -> villager.isBaby() && !Guests.isGuest(villager) && !Expedition.isAway(villager));
		children.sort(Comparator.comparing(Villager::getUUID));
		return children;
	}

	/** Rotates excess children through the real seats once per day; children never share a bench assignment. */
	private static List<StudentSeat> roster(ServerLevel level, VillageRef ref, Classroom classroom) {
		List<Villager> children = children(level, ref);
		if (children.isEmpty() || classroom.seats().isEmpty()) return List.of();
		int assigned = Math.min(children.size(), classroom.seats().size());
		long day = Math.floorDiv(level.getOverworldClockTime(), 24000L);
		int offset = (int) Math.floorMod(day, children.size());
		List<StudentSeat> roster = new ArrayList<>(assigned);
		for (int i = 0; i < assigned; i++) {
			Villager child = children.get((offset + i) % children.size());
			roster.add(new StudentSeat(child, classroom.seats().get(i)));
		}
		return List.copyOf(roster);
	}

	private static boolean atAssignedSeat(Villager villager, Seat seat) {
		return villager.position().distanceToSqr(Vec3.atBottomCenterOf(seat.stand())) <= 1.25 * 1.25;
	}

	/** Selects an adult librarian or cleric assigned to this exact village, never a nearby settlement's teacher. */
	private static Villager teacher(ServerLevel level, VillageRef ref) {
		LocationKey key = villageKey(level, ref);
		long now = level.getGameTime();
		TeacherCache cached = TEACHERS.get(key);
		if (cached != null && now >= cached.checkedAt() && now - cached.checkedAt() < TEACHER_CACHE_TICKS) {
			if (cached.teacher() == null) return null;
			if (level.getEntity(cached.teacher()) instanceof Villager villager && eligibleTeacher(villager)
				&& assignedTo(villager, level, ref)) return villager;
			TEACHERS.remove(key);
		}
		Villager selected = Villages.residents(level, ref, Config.VILLAGE_RADIUS, School::eligibleTeacher).stream()
			.min(Comparator.comparingInt(School::teacherPriority).thenComparing(Villager::getUUID)).orElse(null);
		TEACHERS.put(key, new TeacherCache(now, selected == null ? null : selected.getUUID()));
		return selected;
	}

	private static boolean eligibleTeacher(Villager villager) {
		if (!villager.isAlive() || villager.isBaby() || villager.isSleeping() || villager.isTrading()
			|| Guests.isGuest(villager) || Expedition.isAway(villager)) return false;
		String profession = professionPath(villager);
		return profession.equals("librarian") || profession.equals("cleric");
	}

	private static int teacherPriority(Villager villager) {
		return professionPath(villager).equals("librarian") ? 0 : 1;
	}

	private static String professionPath(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey().map(key -> key.identifier().getPath()).orElse("");
	}

	private static boolean assignedTo(Villager villager, ServerLevel level, VillageRef ref) {
		Optional<GlobalPos> meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
		if (meeting.isPresent()) {
			return meeting.get().dimension().equals(level.dimension()) && meeting.get().pos().equals(ref.bell());
		}
		if (villager.blockPosition().distSqr(ref.bell()) > (long) Config.VILLAGE_RADIUS * Config.VILLAGE_RADIUS) return false;
		VillageRef assigned = Villages.find(level, villager);
		return assigned != null && assigned.bell().equals(ref.bell());
	}

	private static LocationKey villageKey(ServerLevel level, VillageRef ref) {
		return new LocationKey(level.dimension(), ref.bell().asLong());
	}

	private static boolean atLectern(Villager teacher, Classroom classroom) {
		BlockPos stand = classroom.lectern().relative(classroom.facing());
		return teacher.position().distanceToSqr(Vec3.atBottomCenterOf(stand)) <= 1.8 * 1.8;
	}

	private static Lesson activeLesson(ServerLevel level, VillageRef ref, Classroom classroom, Villager teacher) {
		if (teacher == null || !eligibleTeacher(teacher) || !assignedTo(teacher, level, ref) || !atLectern(teacher, classroom)) return null;
		Lesson lesson = TAUGHT.get(villageKey(level, ref));
		long now = level.getGameTime();
		if (lesson == null || !lesson.teacher().equals(teacher.getUUID()) || lesson.lectern() != classroom.lectern().asLong() || now < lesson.gameTime()
			|| now - lesson.gameTime() > LESSON_WINDOW_TICKS) return null;
		return lesson;
	}

	/** True only while the assigned teacher is still at the school lectern after a recent lesson. */
	public static boolean taught(ServerLevel level, VillageRef ref) {
		Classroom classroom = classroom(level, ref);
		if (classroom == null) return false;
		return activeLesson(level, ref, classroom, teacher(level, ref)) != null;
	}

	/** Teacher work: hold a real lesson at the classroom lectern when at least one assigned pupil is seated. */
	public static boolean teach(Ctx c, Phase phase) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.school.isEmpty() || phase.routine || phase == Phase.NIGHT) return false;
		Villager teacher = teacher(c.level(), ref);
		if (teacher == null || !teacher.getUUID().equals(c.v().getUUID())) return false;
		if (ensureFurnished(c, ref)) return true;
		Classroom classroom = classroom(c.level(), ref);
		if (classroom == null) return false;
		List<StudentSeat> pupils = roster(c.level(), ref, classroom);
		if (pupils.stream().noneMatch(pupil -> pupil.child().isAlive() && !pupil.child().isSleeping() && atAssignedSeat(pupil.child(), pupil.seat()))) return false;

		c.setActivity("unterrichtet die Kinder am Lehrerpult");
		Anim.hold(c, Items.WRITTEN_BOOK);
		BlockPos teacherStand = classroom.lectern().relative(classroom.facing());
		if (!JobUtil.approachLong(c, teacherStand, 1.6)) return true;
		c.v().getLookControl().setLookAt(classroom.lectern().getX() + 0.5, c.v().getEyeY(), classroom.lectern().getZ() + 0.5);
		if (c.now() >= c.st().cooldownUntil) {
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + LESSON_TICKS;
			TAUGHT.put(villageKey(c.level(), ref), new Lesson(c.v().getUUID(), c.now(), classroom.lectern().asLong()));
			c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
				classroom.lectern().getX() + 0.5, classroom.lectern().getY() + 1.4, classroom.lectern().getZ() + 0.5,
				12, 0.3, 0.2, 0.3, 0.5);
			long day = c.now() / 24000L;
			LocationKey key = villageKey(c.level(), ref);
			if (!Long.valueOf(day).equals(JOURNAL_DAY.get(key))) {
				JOURNAL_DAY.put(key, day);
				Journal.add(c.level(), ref, "Befinden", c.mem().name, "unterrichtet die Dorfkinder in der Schule", 0);
			}
		}
		return true;
	}

	/** Children use an actual bench, and earn XP once per fresh lesson only while their teacher is present. */
	public static boolean attend(Ctx c, Phase phase) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.school.isEmpty() || phase.routine || phase == Phase.NIGHT) return false;
		Classroom classroom = classroom(c.level(), ref);
		if (classroom == null) {
			// A child may trigger furnishing work, but that does not make an empty room a
			// classroom or count as school attendance for this work cycle.
			ensureFurnished(c, ref);
			return false;
		}
		if (ensureFurnished(c, ref)) return true;
		StudentSeat assigned = roster(c.level(), ref, classroom).stream()
			.filter(pupil -> pupil.child().getUUID().equals(c.v().getUUID())).findFirst().orElse(null);
		if (assigned == null) return false;

		Villager teacher = teacher(c.level(), ref);
		Lesson lesson = activeLesson(c.level(), ref, classroom, teacher);
		String apprenticeship = Apprentices.activity(c.level(), c.v());
		c.setActivity(apprenticeship != null ? apprenticeship
			: lesson != null ? teacherActivity(teacher)
			: teacher == null ? "lernt in der Schule (Lehrer fehlt)" : "lernt in der Schule (wartet auf Unterricht)");
		Anim.hold(c, Items.BOOK);
		if (!JobUtil.approachLong(c, assigned.seat().stand(), 0.75)) return true;
		c.v().getLookControl().setLookAt(classroom.lectern().getX() + 0.5, c.v().getEyeY(), classroom.lectern().getZ() + 0.5);
		if (lesson == null || c.now() < c.st().cooldownUntil) return true;

		Apprentices.Progress progress = Apprentices.get(c.v());
		if (progress.lastLessonAt() == lesson.gameTime()) {
			c.st().cooldownUntil = c.now() + LESSON_TICKS;
			return true;
		}
		int lessonXp = 3;
		c.mem().jobsDone += lessonXp;
		Apprentices.awardLesson(c.v(), lessonXp, lesson.gameTime());
		c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
			c.v().getX(), c.v().getY() + 1.4, c.v().getZ(), 2, 0.2, 0.1, 0.2, 0.0);
		c.st().cooldownUntil = c.now() + LESSON_TICKS;
		return true;
	}

	private static String teacherActivity(Villager teacher) {
		VillagerMemory memory = teacher.getAttachedOrCreate(VillagerPlus.MEMORY);
		return memory.name == null || memory.name.isBlank() ? "lernt beim Lehrer" : "lernt beim Lehrer " + memory.name;
	}

	/** Lets the grouped in-world checks discard per-process school state after restoring their fixture. */
	static void clearTransientStateForChecks(ServerLevel level, VillageRef ref, BlockPos school) {
		if (school != null) {
			LocationKey schoolKey = new LocationKey(level.dimension(), school.asLong());
			LECTERNS.remove(schoolKey);
			FURNISH_TRIED.remove(schoolKey);
		}
		if (ref != null) {
			LocationKey key = villageKey(level, ref);
			TEACHERS.remove(key);
			TAUGHT.remove(key);
			JOURNAL_DAY.remove(key);
		}
	}
}
