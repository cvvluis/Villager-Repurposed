package de.villagerplus.build;

import de.villagerplus.job.Ctx;
import de.villagerplus.job.Anim;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.RecipeCrafting;
import de.villagerplus.job.JobManager;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Lager;
import de.villagerplus.village.VillageRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Einrichtung: Lager und Schule sind Kopien echter Dorfgebaeude (gleicher Stil wie die Umgebung); innen werden sie nachtraeglich
 * eingerichtet - das Lager mit Doppelkisten an den Waenden, die Schule mit Buecherregalen und Baenken. Eingerichtet wird nur
 * auf freien, ueberdachten Bodenzellen, nie direkt an der Tuer.
 */
public final class Furnisher {
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	private static final String SCHOOL_CARGO = "vp_school_furnish_dimension";
	private static final String SCHOOL_CARGO_ORIGIN = "vp_school_furnish_origin";
	private static final String SCHOOL_CARGO_TARGET = "vp_school_furnish_target";
	private static final String SCHOOL_CARGO_SOURCE = "vp_school_furnish_source";
	private static final String SCHOOL_CARGO_FACING = "vp_school_furnish_facing";
	private record SchoolCargo(long school, String dimension, BlockPos target, BlockPos source, Direction facing) { }
	public record SchoolBench(BlockPos position, BlockPos stand) { }
	public record SchoolBenchPlan(List<SchoolBench> existing, List<SchoolBench> additions) {
		public SchoolBenchPlan {
			existing = List.copyOf(existing);
			additions = List.copyOf(additions);
		}
		public int capacity() { return existing.size() + additions.size(); }
	}
	private Furnisher() {
	}

	/** Umschliessender Quader aller Bauschritte. */
	public static BoundingBox boxOf(List<Step> steps) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Step s : steps) {
			BlockPos p = s.pos();
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static boolean sturdy(ServerLevel level, BlockPos p, Direction face) {
		return level.getBlockState(p).isFaceSturdy(level, p, face);
	}

	/** Freie, physisch ueberdachte Bodenzelle (Luft, Luft darueber, fester Boden), nicht an einer Tuer. */
	private static boolean free(ServerLevel level, BlockPos p) {
		if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() || !sturdy(level, p.below(), Direction.UP)
			|| !roofed(level, p)) {
			return false;
		}
		for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 1, 1))) {
			if (!level.hasChunkAt(n)) return false;
			if (level.getBlockState(n).getBlock() instanceof DoorBlock) {
				return false;
			}
		}
		return true;
	}

	/** Liegt ein fester Block ueber der Zelle? (Nicht ueber das Himmelslicht: Es wird direkt nach dem Bau noch nicht neu berechnet.) */
	private static boolean roofed(ServerLevel level, BlockPos p) {
		for (int dy = 2; dy <= 14; dy++) {
			var s = level.getBlockState(p.above(dy));
			if (!s.isAir() && !s.canBeReplaced()) {
				return true;
			}
		}
		return false;
	}

	/** In allen vier Himmelsrichtungen folgt innerhalb von 10 Bloecken eine feste Wand (also im Raum, nicht auf dem Vorbau). */
	private static boolean enclosed(ServerLevel level, BlockPos p) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			boolean hit = false;
			for (int k = 1; k <= 10 && !hit; k++) {
				BlockPos wall = p.relative(d, k);
				if (!level.hasChunkAt(wall)) return false;
				// A doorway still bounds the classroom even when its door is open.
				hit = level.getBlockState(wall).getBlock() instanceof DoorBlock || sturdy(level, wall, d.getOpposite());
			}
			if (!hit) {
				return false;
			}
		}
		return true;
	}

	/** Shared by classroom discovery and furnishing so an exterior lectern is never adopted. */
	public static boolean isSchoolInterior(ServerLevel level, BlockPos pos) {
		return level.hasChunkAt(pos) && roofed(level, pos) && enclosed(level, pos);
	}

	/** Die Richtung, in der eine feste Wand direkt neben der Zelle liegt (null = keine). */
	private static Direction wallSide(ServerLevel level, BlockPos p) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = p.relative(d);
			if (sturdy(level, n, d.getOpposite()) && sturdy(level, n.above(), d.getOpposite())) {
				return d;
			}
		}
		return null;
	}

	/**
	 * Stellt Doppelkisten an die Waende (je zwei nebeneinander, mit dem Ruecken zur Wand).
	 *
	 * @return die linken Haelften der gesetzten Doppelkisten (eine Position je Doppelkiste)
	 */
	public static List<BlockPos> placeDoubleChests(ServerLevel level, BoundingBox bb, int maxDoubles) {
		List<BlockPos> placed = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			if (placed.size() >= maxDoubles) {
				break;
			}
			BlockPos p = pos.immutable();
			if (!free(level, p)) {
				continue;
			}
			Direction wall = wallSide(level, p);
			if (wall == null) {
				continue;
			}
			Direction facing = wall.getOpposite();
			BlockPos q = p.relative(facing.getClockWise());
			if (!free(level, q) || wallSide(level, q) != wall) {
				continue;
			}
			// Ein Platz vor der Kiste muss frei bleiben, damit man sie oeffnen kann.
			if (!level.getBlockState(p.relative(facing)).isAir() || !level.getBlockState(q.relative(facing)).isAir()) {
				continue;
			}
			level.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
			level.setBlock(q, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
			placed.add(p);
		}
		return placed;
	}

	/** Adds at most one school fixture per visit, paid from public stock or a loaded recipe. */
	public static boolean furnishSchool(Ctx c, VillageRef ref, BoundingBox bb, Block stairs) {
		if (c == null || ref == null || bb == null || !schoolLoaded(c.level(), bb)) return false;
		ServerLevel level = c.level();
		BlockPos school = ref.data().extra.school.orElse(null);
		if (school == null) return false;
		// The active worker is authoritative. In particular, a fixture or a villager that has
		// just crossed a chunk boundary can have a stale spatial-section entry until the next
		// entity tick; an AABB query must not make its already-paid inventory disappear.
		ItemStack localCargo = findSchoolCargo(c.v(), school, level);
		Villager carrier = localCargo != null ? c.v() : findSchoolCargoCarrier(level, school);
		if (carrier != null) {
			Ctx carrierContext = carrier == c.v() ? c : new Ctx(level, carrier, JobManager.state(carrier),
				carrier.getAttachedOrCreate(VillagerPlus.MEMORY), ref, c.now());
			ItemStack cargo = carrier == c.v() ? localCargo : findSchoolCargo(carrier, school, level);
			if (cargo == null) return false;
			if (carrier.isSleeping() || carrier.isTrading()) {
				c.setActivity("wartet auf Schulmaterial-Fracht");
				return true;
			}
			return continueSchoolCargo(carrierContext, ref, bb, school, cargo);
		}
		BlockPos lectern = findInterior(level, bb, Blocks.LECTERN);
		if (lectern == null) {
			for (BlockPos pos : cells(bb)) {
				BlockPos p = pos.immutable();
				if (!free(level, p) || !isSchoolInterior(level, p)) continue;
				Direction wall = wallSide(level, p);
				if (wall == null || !level.getBlockState(p.relative(wall.getOpposite())).isAir()
					|| !level.getBlockState(p.relative(wall.getOpposite(), 2)).isAir()) continue;
				if (placePaid(c, ref, school, p, Blocks.LECTERN,
					Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, wall.getOpposite()))) return true;
				break;
			}
		}

		if (countInterior(level, bb, Blocks.BOOKSHELF) < 12) {
			for (BlockPos pos : cells(bb)) {
				BlockPos p = pos.immutable();
				if (free(level, p) && isSchoolInterior(level, p) && wallSide(level, p) != null) {
					if (placePaid(c, ref, school, p, Blocks.BOOKSHELF, Blocks.BOOKSHELF.defaultBlockState())) return true;
					break;
				}
			}
		}

		Block bench = stairs instanceof StairBlock ? stairs : schoolStairs(level, bb);
		if (!(bench instanceof StairBlock)) bench = Blocks.OAK_STAIRS;
		SchoolBenchPlan seating = schoolBenchPlan(level, bb);
		if (!seating.additions().isEmpty()) {
			BlockPos p = seating.additions().getFirst().position();
			return placePaid(c, ref, school, p, bench,
				bench.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		}
		return false;
	}

	private static boolean schoolLoaded(ServerLevel level, BoundingBox bb) {
		for (BlockPos pos : cells(bb)) if (!level.hasChunkAt(pos)) return false;
		return true;
	}

	/**
	 * Computes only seats that can be reached from a real school doorway while keeping the
	 * teacher's lectern route open, including when the chosen student positions are occupied.
	 */
	public static SchoolBenchPlan schoolBenchPlan(ServerLevel level, BoundingBox bb) {
		if (level == null || bb == null || !schoolLoaded(level, bb)) return new SchoolBenchPlan(List.of(), List.of());
		BlockPos lectern = findInterior(level, bb, Blocks.LECTERN);
		if (lectern == null) return new SchoolBenchPlan(List.of(), List.of());
		Direction facing = level.getBlockState(lectern).getValue(net.minecraft.world.level.block.LecternBlock.FACING);
		BlockPos teacherStand = lectern.relative(facing);
		if (!schoolWalkable(level, bb, teacherStand)) return new SchoolBenchPlan(List.of(), List.of());

		List<BlockPos> entries = schoolDoorEntries(level, bb);
		SchoolBenchPlan best = new SchoolBenchPlan(List.of(), List.of());
		for (BlockPos entry : entries) {
			SchoolBenchPlan candidate = schoolBenchPlanFromEntry(level, bb, lectern, teacherStand, entry);
			if (candidate.capacity() > best.capacity()) best = candidate;
		}
		return best;
	}

	private static SchoolBenchPlan schoolBenchPlanFromEntry(ServerLevel level, BoundingBox bb,
		BlockPos lectern, BlockPos teacherStand, BlockPos entry) {
		if (!schoolRouteExists(level, bb, entry, teacherStand, Set.of(lectern))) {
			return new SchoolBenchPlan(List.of(), List.of());
		}
		Set<BlockPos> doorEntries = new HashSet<>(schoolDoorEntries(level, bb));
		Set<BlockPos> reserved = new HashSet<>();
		List<SchoolBench> existing = new ArrayList<>();
		List<BlockPos> stairs = new ArrayList<>();
		int floorY = bb.minY() + 1;
		for (BlockPos p : BlockPos.betweenClosed(bb.minX(), floorY, bb.minZ(), bb.maxX(), floorY, bb.maxZ())) {
			if (level.getBlockState(p).getBlock() instanceof StairBlock && isSchoolInterior(level, p)) stairs.add(p.immutable());
		}
		stairs.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(lectern))
			.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
		for (BlockPos bench : stairs) {
			for (BlockPos stand : schoolSeatStands(level, bb, bench, lectern, teacherStand, doorEntries, reserved)) {
				if (!schoolRouteExists(level, bb, entry, stand, reserved)
					|| !schoolRouteExists(level, bb, entry, teacherStand, with(reserved, stand))) continue;
				existing.add(new SchoolBench(bench, stand));
				reserved.add(stand);
				break;
			}
		}

		List<SchoolBench> additions = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), floorY, bb.minZ(), bb.maxX(), floorY, bb.maxZ())) {
			BlockPos bench = pos.immutable();
			if (!free(level, bench) || !isSchoolInterior(level, bench) || wallSide(level, bench) != null
				|| doorEntries.contains(bench) || bench.equals(teacherStand) || bench.equals(lectern)) continue;
			Set<BlockPos> benchBlocked = with(reserved, bench);
			for (BlockPos stand : schoolSeatStands(level, bb, bench, lectern, teacherStand, doorEntries, reserved)) {
				if (!schoolRouteExists(level, bb, entry, stand, benchBlocked)) continue;
				Set<BlockPos> occupiedSeat = with(benchBlocked, stand);
				if (!schoolRouteExists(level, bb, entry, teacherStand, occupiedSeat)) continue;
				additions.add(new SchoolBench(bench, stand));
				reserved.add(bench);
				reserved.add(stand);
				break;
			}
		}
		return new SchoolBenchPlan(existing, additions);
	}

	private static List<BlockPos> schoolSeatStands(ServerLevel level, BoundingBox bb, BlockPos bench,
		BlockPos lectern, BlockPos teacherStand, Set<BlockPos> doorEntries, Set<BlockPos> reserved) {
		List<Direction> approaches = new ArrayList<>(List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST));
		approaches.sort(Comparator.comparingInt((Direction direction) ->
			-((lectern.getX() - bench.getX()) * direction.getStepX() + (lectern.getZ() - bench.getZ()) * direction.getStepZ())));
		List<BlockPos> stands = new ArrayList<>();
		for (Direction direction : approaches) {
			BlockPos stand = bench.relative(direction);
			if (!safeSchoolStand(level, stand) || !isSchoolInterior(level, stand)
				|| stand.equals(teacherStand) || doorEntries.contains(stand) || reserved.contains(stand)) continue;
			stands.add(stand.immutable());
		}
		return stands;
	}

	private static List<BlockPos> schoolDoorEntries(ServerLevel level, BoundingBox bb) {
		Set<BlockPos> entries = new java.util.LinkedHashSet<>();
		int floorY = bb.minY() + 1;
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), floorY, bb.minZ(), bb.maxX(), floorY, bb.maxZ())) {
			if (!(level.getBlockState(pos).getBlock() instanceof DoorBlock)
				|| level.getBlockState(pos).getValue(DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) continue;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos inside = pos.relative(direction);
				if (schoolWalkable(level, bb, inside) && isSchoolInterior(level, inside)) entries.add(inside.immutable());
			}
		}
		return List.copyOf(entries);
	}

	private static boolean schoolWalkable(ServerLevel level, BoundingBox bb, BlockPos feet) {
		return bb.isInside(feet) && isSchoolInterior(level, feet) && safeSchoolStand(level, feet);
	}

	private static Set<BlockPos> with(Set<BlockPos> existing, BlockPos added) {
		Set<BlockPos> combined = new HashSet<>(existing);
		combined.add(added);
		return combined;
	}

	private static boolean schoolRouteExists(ServerLevel level, BoundingBox bb, BlockPos start,
		BlockPos goal, Set<BlockPos> blocked) {
		if (!schoolWalkable(level, bb, start) || !schoolWalkable(level, bb, goal)
			|| blocked.contains(start) || blocked.contains(goal)) return false;
		ArrayDeque<BlockPos> pending = new ArrayDeque<>();
		Set<BlockPos> reached = new HashSet<>();
		pending.add(start);
		reached.add(start);
		while (!pending.isEmpty()) {
			BlockPos current = pending.removeFirst();
			if (current.equals(goal)) return true;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos next = current.relative(direction);
				if (blocked.contains(next) || reached.contains(next) || !schoolWalkable(level, bb, next)) continue;
				reached.add(next);
				pending.addLast(next);
			}
		}
		return false;
	}

	private static Iterable<BlockPos> cells(BoundingBox bb) {
		return BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
	}

	private static BlockPos findInterior(ServerLevel level, BoundingBox bb, Block block) {
		for (BlockPos pos : cells(bb)) if (level.getBlockState(pos).is(block) && isSchoolInterior(level, pos)) return pos.immutable();
		return null;
	}

	private static int countInterior(ServerLevel level, BoundingBox bb, Block block) {
		int count = 0;
		for (BlockPos pos : cells(bb)) if (level.getBlockState(pos).is(block) && isSchoolInterior(level, pos)) count++;
		return count;
	}

	private static int countInteriorStairs(ServerLevel level, BoundingBox bb) {
		int count = 0;
		for (BlockPos pos : cells(bb)) if (level.getBlockState(pos).getBlock() instanceof StairBlock && isSchoolInterior(level, pos)) count++;
		return count;
	}

	private static Block schoolStairs(ServerLevel level, BoundingBox bb) {
		for (BlockPos pos : cells(bb)) {
			Block block = level.getBlockState(pos).getBlock();
			if (block instanceof StairBlock && isSchoolInterior(level, pos)) return block;
		}
		return Blocks.OAK_STAIRS;
	}

	private static boolean placePaid(Ctx c, VillageRef ref, BlockPos school, BlockPos pos, Block block,
		net.minecraft.world.level.block.state.BlockState state) {
		Item item = block.asItem();
		if (item == Items.AIR || !levelCanPlace(c.level(), pos)) return false;
		return takeSchoolItem(c, ref, school, pos, state, item);
	}

	private static boolean levelCanPlace(ServerLevel level, BlockPos pos) {
		return level.hasChunkAt(pos) && level.hasChunkAt(pos.above()) && level.hasChunkAt(pos.below())
			&& level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
			&& sturdy(level, pos.below(), Direction.UP);
	}

	/** Public stock is removed only after the carrier reaches its loaded source chest. */
	private static boolean takeSchoolItem(Ctx c, VillageRef ref, BlockPos school, BlockPos target,
		net.minecraft.world.level.block.state.BlockState state, Item item) {
		List<BlockPos> sources = publicSources(c, ref);
		boolean waitingForStand = false;
		for (BlockPos source : sources) {
			Container stock = Lager.publicContainerAt(c.level(), source);
			if (stock == null || JobUtil.count(stock, stack -> stack.is(item)) <= 0) continue;
			BlockPos stand = publicChestStand(c, source);
			if (stand == null) { waitingForStand = true; continue; }
			c.setActivity("holt bezahltes Schulmaterial aus dem Lager");
			if (!approachSchoolGoal(c, stand, 0.9)) return true;
			if (!Lager.publicStorageAt(c.level(), source)) { c.st().clearTarget(); continue; }
			stock = Lager.publicContainerAt(c.level(), source);
			if (stock == null) { c.st().clearTarget(); continue; }
			for (int slot = 0; slot < stock.getContainerSize(); slot++) {
				ItemStack available = stock.getItem(slot);
				if (available.isEmpty() || !available.is(item)) continue;
				ItemStack sample = available.copyWithCount(1);
				markSchoolCargo(sample, c.level(), school, target, source, state);
				if (!c.v().getInventory().canAddItem(sample)) {
					c.setActivity("braucht freien Inventarplatz für Schulmaterial");
					c.st().cooldownUntil = Math.max(c.st().cooldownUntil, c.now() + 40L);
					return true;
				}
				ItemStack before = available.copy();
				ItemStack taken = stock.removeItem(slot, 1);
				if (taken.getCount() != 1) {
					stock.setItem(slot, before);
					continue;
				}
				markSchoolCargo(taken, c.level(), school, target, source, state);
				ItemStack remainder = c.v().getInventory().addItem(taken);
				if (!remainder.isEmpty()) {
					stock.setItem(slot, before);
					stock.setChanged();
					c.v().getInventory().setChanged();
					return true;
				}
				stock.setChanged();
				c.v().getInventory().setChanged();
				c.st().clearTarget();
				c.setActivity("trägt Schulmaterial zur Schule");
				Anim.hold(c, item);
				Anim.apply(c.v(), c.st(), c.now());
				return true;
			}
			c.st().clearTarget();
		}

		// Recipe outputs are also paid by real stock. Craft only against one public chest and
		// require the carrier to reach that chest before RecipeCrafting commits its transaction.
		for (BlockPos source : sources) {
			Container stock = Lager.publicContainerAt(c.level(), source);
			// A blocked empty store cannot provide or craft this fixture. Do not turn it into
			// a fictitious movement task just because no interaction stand can be reserved.
			if (stock == null || JobUtil.count(stock, stack -> !stack.isEmpty()) == 0) continue;
			if (!RecipeCrafting.canCraftCraftingOutput(c, stock, stack -> stack.is(item), 4096)) continue;
			BlockPos stand = publicChestStand(c, source);
			if (stand == null) { waitingForStand = true; continue; }
			prepareSchoolGoal(c, stand);
			c.setActivity("stellt Schulmaterial im Lager her");
			int outputBefore = JobUtil.count(stock, stack -> stack.is(item));
			if (RecipeCrafting.craft(c, stock, stand, stack -> stack.is(item), 4096)) {
				if (JobUtil.count(stock, stack -> stack.is(item)) > outputBefore) {
					// RecipeCrafting's generic animation depicts its result as held. Here the
					// result remains in the public chest until a separate pickup route succeeds.
					c.st().tool = null;
					c.st().toolUntil = c.now();
					Anim.apply(c.v(), c.st(), c.now());
				}
				return true;
			}
		}
		if (waitingForStand) {
			c.setActivity("wartet auf sicheren Standplatz am öffentlichen Lager");
			return true;
		}
		return false;
	}

	private static List<BlockPos> publicSources(Ctx c, VillageRef ref) {
		return Lager.materialChests(c.level(), ref).stream()
			.filter(pos -> c.level().hasChunkAt(pos) && Lager.publicStorageAt(c.level(), pos))
			.distinct().sorted(Comparator.comparingDouble(pos -> c.v().position().distanceToSqr(Vec3.atBottomCenterOf(pos))))
			.toList();
	}

	private static boolean approachSchoolGoal(Ctx c, BlockPos goal, double reach) {
		prepareSchoolGoal(c, goal);
		return JobUtil.approachLong(c, goal, reach);
	}

	/** Selects a loaded, public, walkable interaction position beside a school supply chest. */
	private static BlockPos publicChestStand(Ctx c, BlockPos chest) {
		if (chest == null || !c.level().hasChunkAt(chest) || !Lager.publicStorageAt(c.level(), chest)) return null;
		return JobUtil.sharedStandSpot(c, chest, 3.0);
	}

	private static void prepareSchoolGoal(Ctx c, BlockPos goal) {
		if (goal.equals(c.st().target) && c.st().targetEntity == null) return;
		c.st().target = goal.immutable();
		c.st().targetEntity = null;
		c.st().walkStart = 0L;
		c.st().bestDist = 0.0;
		c.st().lastMoveAt = 0L;
		c.st().stuckCount = 0;
	}

	private static Villager findSchoolCargoCarrier(ServerLevel level, BlockPos school) {
		AABB search = new AABB(school).inflate(160.0);
		return level.getEntitiesOfClass(Villager.class, search,
			villager -> villager.isAlive() && findSchoolCargo(villager, school, level) != null)
			.stream().min(Comparator.comparing(Villager::getUUID)).orElse(null);
	}

	private static ItemStack findSchoolCargo(Villager villager, BlockPos school, ServerLevel level) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			SchoolCargo cargo = schoolCargo(stack);
			if (cargo != null && cargo.school() == school.asLong()
				&& cargo.dimension().equals(level.dimension().identifier().toString())) return stack;
		}
		return null;
	}

	private static SchoolCargo schoolCargo(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return null;
		CompoundTag tag = custom.copyTag();
		if (!tag.contains(SCHOOL_CARGO) || !tag.contains(SCHOOL_CARGO_ORIGIN)
			|| !tag.contains(SCHOOL_CARGO_TARGET) || !tag.contains(SCHOOL_CARGO_SOURCE)) return null;
		try {
			String facingName = tag.getStringOr(SCHOOL_CARGO_FACING, "");
			return new SchoolCargo(Long.parseLong(tag.getStringOr(SCHOOL_CARGO_ORIGIN, "")),
				tag.getStringOr(SCHOOL_CARGO, ""), BlockPos.of(Long.parseLong(tag.getStringOr(SCHOOL_CARGO_TARGET, ""))),
				BlockPos.of(Long.parseLong(tag.getStringOr(SCHOOL_CARGO_SOURCE, ""))),
				facingName.isBlank() ? null : Direction.byName(facingName));
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static void markSchoolCargo(ItemStack stack, ServerLevel level, BlockPos school, BlockPos target,
		BlockPos source, net.minecraft.world.level.block.state.BlockState state) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = custom == null ? new CompoundTag() : custom.copyTag();
		tag.putString(SCHOOL_CARGO, level.dimension().identifier().toString());
		tag.putString(SCHOOL_CARGO_ORIGIN, Long.toString(school.asLong()));
		tag.putString(SCHOOL_CARGO_TARGET, Long.toString(target.asLong()));
		tag.putString(SCHOOL_CARGO_SOURCE, Long.toString(source.asLong()));
		Direction facing = state.hasProperty(HorizontalDirectionalBlock.FACING)
			? state.getValue(HorizontalDirectionalBlock.FACING) : null;
		tag.putString(SCHOOL_CARGO_FACING, facing == null ? "" : facing.getName());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private static void retargetSchoolCargo(ItemStack stack, ServerLevel level, BlockPos school,
		BlockPos target, net.minecraft.world.level.block.state.BlockState state) {
		SchoolCargo cargo = schoolCargo(stack);
		if (cargo != null) markSchoolCargo(stack, level, school, target, cargo.source(), state);
	}

	private static void clearSchoolCargo(ItemStack stack) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return;
		CompoundTag tag = custom.copyTag();
		tag.remove(SCHOOL_CARGO);
		tag.remove(SCHOOL_CARGO_ORIGIN);
		tag.remove(SCHOOL_CARGO_TARGET);
		tag.remove(SCHOOL_CARGO_SOURCE);
		tag.remove(SCHOOL_CARGO_FACING);
		if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
		else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private static boolean continueSchoolCargo(Ctx c, VillageRef ref, BoundingBox bb, BlockPos school, ItemStack cargoStack) {
		SchoolCargo cargo = schoolCargo(cargoStack);
		if (cargo == null) {
			c.setActivity("Schulmaterial-Fracht ist beschädigt und bleibt erhalten");
			return true;
		}
		Block block = Block.byItem(cargoStack.getItem());
		if (block == Blocks.AIR) return returnSchoolCargo(c, ref, cargoStack, cargo);
		Anim.hold(c, cargoStack.getItem());
		Anim.apply(c.v(), c.st(), c.now());
		net.minecraft.world.level.block.state.BlockState desired = schoolBlockState(block, cargo.facing());
		// A save can land after setBlock but before the inventory decrement. Treat the exact
		// marked destination as the committed placement so reload cannot place a duplicate.
		if (bb.isInside(cargo.target()) && c.level().hasChunkAt(cargo.target())
			&& isSchoolInterior(c.level(), cargo.target())
			&& c.level().getBlockState(cargo.target()).equals(desired)) {
			if (consumeSchoolCargo(c, cargoStack)) {
				c.setActivity("Schulmaterial-Einbau nach Neustart abgeglichen");
				c.mem().jobsDone++;
				ref.dirty();
				syncSchoolCarryVisual(c, school);
			}
			return true;
		}
		if (!schoolNeeds(c.level(), bb, block)) return returnSchoolCargo(c, ref, cargoStack, cargo);

		BlockPos target = cargo.target();
		if (!validSchoolCell(c.level(), bb, target, block)) target = findSchoolCell(c.level(), bb, block);
		if (target == null) {
			c.setActivity("wartet auf freien Platz für Schulmaterial");
			return true;
		}
		if (!target.equals(cargo.target())) retargetSchoolCargo(cargoStack, c.level(), school, target, desired);

		BlockPos stand = block instanceof StairBlock
			? schoolBenchStand(c.level(), bb, target)
			: placementStand(c.level(), bb, target, c.v().blockPosition());
		if (stand == null) {
			c.setActivity("wartet auf sicheren Schulstandplatz");
			return true;
		}
		c.setActivity("bringt bezahltes Schulmaterial zum Einbauplatz");
		if (!approachSchoolGoal(c, stand, 0.9)) return true;
		if (!validSchoolCell(c.level(), bb, target, block)) {
			BlockPos replacement = findSchoolCell(c.level(), bb, block);
			if (replacement == null) returnSchoolCargo(c, ref, cargoStack, cargo);
			else retargetSchoolCargo(cargoStack, c.level(), school, replacement, desired);
			return true;
		}
		if (cargoStack.isEmpty()) return true;
		if (!c.level().setBlock(target, desired, 3) || !c.level().getBlockState(target).is(block)) return true;
		if (!consumeSchoolCargo(c, cargoStack)) return true;
		c.setActivity("richtet die Schule ein");
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = Math.max(c.st().cooldownUntil, c.now() + 40L);
		syncSchoolCarryVisual(c, school);
		ref.dirty();
		return true;
	}

	private static void syncSchoolCarryVisual(Ctx c, BlockPos school) {
		ItemStack next = findSchoolCargo(c.v(), school, c.level());
		if (next == null) {
			c.st().tool = null;
			c.st().toolUntil = c.now();
		} else {
			Anim.hold(c, next.getItem());
		}
		Anim.apply(c.v(), c.st(), c.now());
	}

	private static int inventorySlot(Villager villager, ItemStack stack) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			if (villager.getInventory().getItem(slot) == stack) return slot;
		}
		return -1;
	}

	private static boolean consumeSchoolCargo(Ctx c, ItemStack cargoStack) {
		int slot = inventorySlot(c.v(), cargoStack);
		if (slot < 0 || cargoStack.isEmpty()) return false;
		cargoStack.shrink(1);
		if (cargoStack.isEmpty()) c.v().getInventory().setItem(slot, ItemStack.EMPTY);
		c.v().getInventory().setChanged();
		return true;
	}

	private static boolean schoolNeeds(ServerLevel level, BoundingBox bb, Block block) {
		if (block == Blocks.LECTERN) return findInterior(level, bb, Blocks.LECTERN) == null;
		if (block == Blocks.BOOKSHELF) return countInterior(level, bb, Blocks.BOOKSHELF) < 12;
		return block instanceof StairBlock && !schoolBenchPlan(level, bb).additions().isEmpty();
	}

	private static boolean validSchoolCell(ServerLevel level, BoundingBox bb, BlockPos pos, Block block) {
		if (pos == null || !bb.isInside(pos) || !free(level, pos) || !isSchoolInterior(level, pos)) return false;
		Direction wall = wallSide(level, pos);
		if (block == Blocks.LECTERN) return wall != null && level.getBlockState(pos.relative(wall.getOpposite())).isAir()
			&& level.getBlockState(pos.relative(wall.getOpposite(), 2)).isAir();
		if (block == Blocks.BOOKSHELF) return wall != null;
		return block instanceof StairBlock && schoolBenchPlan(level, bb).additions().stream()
			.anyMatch(seat -> seat.position().equals(pos));
	}

	private static BlockPos findSchoolCell(ServerLevel level, BoundingBox bb, Block block) {
		if (block instanceof StairBlock) {
			return schoolBenchPlan(level, bb).additions().stream().map(SchoolBench::position).findFirst().orElse(null);
		}
		for (BlockPos pos : cells(bb)) if (validSchoolCell(level, bb, pos, block)) return pos.immutable();
		return null;
	}

	private static net.minecraft.world.level.block.state.BlockState schoolBlockState(Block block, Direction facing) {
		var state = block.defaultBlockState();
		if (facing != null && state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			state = state.setValue(HorizontalDirectionalBlock.FACING, facing);
		}
		return state;
	}

	private static BlockPos placementStand(ServerLevel level, BoundingBox bb, BlockPos target, BlockPos from) {
		List<BlockPos> candidates = new ArrayList<>();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos adjacent = target.relative(direction);
			candidates.add(adjacent);
			// A high shelf can occupy every same-height neighbor while leaving the classroom
			// floor beside it clear. A villager standing on that floor can still reach the
			// shelf directly, so include that real lower interaction position.
			candidates.add(adjacent.below());
		}
		return candidates.stream()
			.filter(pos -> bb.isInside(pos) && isSchoolInterior(level, pos) && safeSchoolStand(level, pos))
			.filter(pos -> withinSchoolPlacementReach(pos, target))
			.min(Comparator.comparingDouble(pos -> pos.distSqr(from))).orElse(null);
	}

	private static boolean withinSchoolPlacementReach(BlockPos stand, BlockPos target) {
		double dx = stand.getX() + 0.5 - (target.getX() + 0.5);
		double dy = stand.getY() + 1.62 - (target.getY() + 0.5);
		double dz = stand.getZ() + 0.5 - (target.getZ() + 0.5);
		// Keep placement within normal block-interaction distance from a standing villager's eyes.
		return dx * dx + dy * dy + dz * dz <= 4.5 * 4.5;
	}

	private static BlockPos schoolBenchStand(ServerLevel level, BoundingBox bb, BlockPos target) {
		return schoolBenchPlan(level, bb).additions().stream().filter(seat -> seat.position().equals(target))
			.map(SchoolBench::stand).findFirst().orElse(null);
	}

	private static boolean safeSchoolStand(ServerLevel level, BlockPos feet) {
		return level.hasChunkAt(feet) && level.hasChunkAt(feet.above()) && level.hasChunkAt(feet.below())
			&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
			&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
	}

	private static boolean returnSchoolCargo(Ctx c, VillageRef ref, ItemStack cargoStack, SchoolCargo cargo) {
		if (cargo == null) return true;
		ItemStack plain = cargoStack.copy();
		clearSchoolCargo(plain);
		List<BlockPos> targets = new ArrayList<>(publicSources(c, ref));
		targets.sort(Comparator.comparingInt((BlockPos pos) -> pos.equals(cargo.source()) ? 0 : 1)
			.thenComparingDouble(pos -> c.v().position().distanceToSqr(Vec3.atBottomCenterOf(pos))));
		BlockPos target = targets.stream().filter(pos -> storageCanAccept(Lager.publicContainerAt(c.level(), pos), plain))
			.findFirst().orElse(null);
		if (target == null) {
			c.setActivity("wartet auf öffentlichen Lagerplatz für Schulmaterial");
			return true;
		}
		BlockPos stand = publicChestStand(c, target);
		if (stand == null) {
			c.setActivity("wartet auf sicheren Standplatz am öffentlichen Lager");
			return true;
		}
		c.setActivity("bringt überschüssiges Schulmaterial ins Lager zurück");
		if (!approachSchoolGoal(c, stand, 0.9)) return true;
		if (!Lager.publicStorageAt(c.level(), target)) return true;
		Container stock = Lager.publicContainerAt(c.level(), target);
		if (!storageCanAccept(stock, plain)) return true;
		int slot = inventorySlot(c.v(), cargoStack);
		if (slot < 0) return true;
		ItemStack offered = cargoStack.copy();
		clearSchoolCargo(offered);
		int before = cargoStack.getCount();
		ItemStack remainder = HopperBlockEntity.addItem(null, stock, offered, null);
		int moved = before - remainder.getCount();
		if (moved > 0) {
			cargoStack.shrink(moved);
			if (cargoStack.isEmpty()) c.v().getInventory().setItem(slot, ItemStack.EMPTY);
			stock.setChanged();
			c.v().getInventory().setChanged();
			c.st().clearTarget();
			syncSchoolCarryVisual(c, BlockPos.of(cargo.school()));
		}
		return true;
	}

	private static boolean storageCanAccept(Container storage, ItemStack offered) {
		if (storage == null || offered == null || offered.isEmpty()) return false;
		SimpleContainer preview = new SimpleContainer(storage.getContainerSize());
		for (int slot = 0; slot < storage.getContainerSize(); slot++) preview.setItem(slot, storage.getItem(slot).copy());
		return HopperBlockEntity.addItem(null, preview, offered.copy(), null).getCount() < offered.getCount();
	}

	private static Block optionalBlock(String id) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(id)).orElse(null);
	}

	private static boolean contains(ServerLevel level, BoundingBox bb, Block block) {
		if (block == null) return false;
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			if (level.getBlockState(pos).is(block)) return true;
		}
		return false;
	}

	/** Keeps the requested appliance present without replacing player/building blocks or duplicating it. */
	private static boolean placeUniqueStation(ServerLevel level, BoundingBox bb, Block block, boolean communalFurnace) {
		if (block == null || contains(level, bb, block)) return block != null;
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			BlockPos p = pos.immutable();
			if (!free(level, p)) continue;
			Direction wall = wallSide(level, p);
			if (!sturdy(level, p.below(), Direction.UP)) continue;
			var state = block.defaultBlockState();
			if (wall != null && state.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
				state = state.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, wall.getOpposite());
			}
			if (!level.setBlock(p, state, 3) || !level.getBlockState(p).is(block)) continue;
			if (communalFurnace && level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
				furnace.setAttached(de.villagerplus.VillagerPlus.COMMUNAL_FURNACE, 0);
				furnace.setAttached(de.villagerplus.VillagerPlus.SMITH_FURNACE, net.minecraft.world.item.ItemStack.EMPTY);
				furnace.setChanged();
			}
			return true;
		}
		return false;
	}

	/** Required mod appliances are placed explicitly before generic kitchen furniture. */
	private static void furnishKitchen(ServerLevel level, BoundingBox bb) {
		Block pot = optionalBlock(Blueprints.FARMERS_DELIGHT_COOKING_POT);
		Block board = optionalBlock(Blueprints.FARMERS_DELIGHT_CUTTING_BOARD);
		if (pot == null) LOGGER.warn("Villagers: Repurposed: Farmer's Delight cooking pot is unavailable; kitchen is not substituting a barrel");
		if (board == null) LOGGER.warn("Villagers: Repurposed: Farmer's Delight cutting board is unavailable; kitchen is not substituting a composter");
		Block[] stations = {Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CRAFTING_TABLE, Blocks.CAULDRON, pot, board};
		for (Block station : stations) {
			if (station == null) continue;
			boolean communalFurnace = station == Blocks.FURNACE || station == Blocks.SMOKER || station == Blocks.BLAST_FURNACE;
			if (!placeUniqueStation(level, bb, station, communalFurnace)) {
				LOGGER.warn("Villagers: Repurposed: kitchen has no safe free furnished cell for {}", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(station));
			}
		}
	}

	/**
	 * Einrichtung der Meilenstein-Gebaeude: Kornspeicher (Doppelkisten, Fass-Reihen, Heu), Werkzeug- und Ruestungskammer
	 * (Kisten, Amboss, Schleifstein, Schmiedetisch), Dorfkueche (Ofen, Raeucherofen, Werkbank, Kessel, Komposter; mit Farmer's Delight
	 * Kochtopf und Schneidebrett).
	 */
	public static void furnishSpecial(ServerLevel level, BoundingBox bb, String type) {
		int chests = type.equals(Blueprints.KITCHEN) ? 1 : type.equals(Blueprints.TOOLSHED) ? 2 : 4;
		if (type.equals(Blueprints.KITCHEN)) {
			if (collectChests(level, bb).isEmpty()) placeDoubleChests(level, bb, chests);
			furnishKitchen(level, bb);
			return;
		}
		placeDoubleChests(level, bb, chests);
		Block[] pool = switch (type) {
			case Blueprints.GRANARY -> new Block[] {Blocks.BARREL, Blocks.BARREL, Blocks.HAY_BLOCK, Blocks.BARREL, Blocks.COMPOSTER};
			case Blueprints.STATION -> new Block[] {Blocks.BARREL, Blocks.BARREL, Blocks.HAY_BLOCK, Blocks.BARREL, Blocks.RAIL, Blocks.POWERED_RAIL};
			case Blueprints.TOOLSHED -> new Block[] {Blocks.ANVIL, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.BARREL, Blocks.BARREL};
		default -> new Block[] {Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CRAFTING_TABLE, Blocks.CAULDRON};
		};
		int placed = 0;
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			if (placed >= pool.length * 2) {
				break;
			}
			BlockPos p = pos.immutable();
			if (!free(level, p) || wallSide(level, p) == null || !(level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP))) {
				continue;
			}
			if ((p.getX() * 7 + p.getZ() * 3 + placed) % 2 != 0) {
				continue;
			}
			Block b = pool[placed % pool.length];
			var state = b.defaultBlockState();
			if (state.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
				state = state.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, wallSide(level, p).getOpposite());
			}
			level.setBlock(p, state, 3);
			// Claim only thermal blocks this kitchen placement actually created; never adopt a nearby/player furnace.
			if (type.equals(Blueprints.KITCHEN) && (b == Blocks.FURNACE || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE)
				&& level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
				furnace.setAttached(de.villagerplus.VillagerPlus.COMMUNAL_FURNACE, 0);
				furnace.setAttached(de.villagerplus.VillagerPlus.SMITH_FURNACE, net.minecraft.world.item.ItemStack.EMPTY);
				furnace.setChanged();
			}
			placed++;
		}
		if (type.equals(Blueprints.STATION)) StationShed.furnish(level, bb);
	}

	/** Alle Kisten eines Gebaeudes (bei Doppelkisten nur die linke Haelfte), z.B. fuer ein Lager aus einer Blaupause. */
	public static List<BlockPos> collectChests(ServerLevel level, BoundingBox bb) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			var s = level.getBlockState(pos);
			if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.RIGHT) {
				out.add(pos.immutable());
			}
		}
		return out;
	}
}
