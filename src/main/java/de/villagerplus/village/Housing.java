package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorfschildBlock;
import de.villagerplus.block.ModBlocks;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Built;
import de.villagerplus.build.Step;
import de.villagerplus.world.LoadedColumns;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Feste Haeuser: Jeder Dorfbewohner hat einen Namen und sein Haus bekommt ein Schild mit Name und Beruf. */
public final class Housing {
	private record PhysicalBed(boolean loaded, BlockPos head) { }
	private static final PhysicalBed UNREADABLE_BED = new PhysicalBed(false, null);
	private static final PhysicalBed MISSING_BED = new PhysicalBed(true, null);
	private static final Comparator<Villager> RESIDENT_ORDER = Comparator.comparing(Villager::getUUID);
	private static final long BUILT_HOUSE_BED_REPAIR_INTERVAL = 1200L;
	private record BuiltHouseRepairKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, long bell) { }
	private static final Map<BuiltHouseRepairKey, Long> NEXT_BUILT_HOUSE_BED_REPAIR = new HashMap<>();
	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> NEXT_BUILT_HOUSE_BED_REPAIR.clear());
	}

	private Housing() {
	}

	public static void update(ServerLevel level, Villager v, VillagerMemory mem) {
		ensureName(v, mem);
		VillageRef village = Villages.find(level, v);
		if (village != null) repairBuiltHouseBeds(level, village);
		PhysicalBed bed = trackHome(level, v, mem);
		if (!bed.loaded()) return;
		boolean hasCurrentHome = bed.head() != null && !releaseDuplicateBedClaim(level, v, mem, bed.head());
		if (hasCurrentHome) {
			BlockPos door = mem.homeDoor.orElse(null);
			BlockState doorState = LoadedColumns.state(level, door);
			if (door == null || (doorState != null && !(doorState.getBlock() instanceof DoorBlock))) {
				mem.homeDoor = findDoor(level, bed.head());
				mem.signPos = Optional.empty();
			}
		}
		// Record demand even before Vanilla assigns a bed. The persisted request lets the
		// normal Housing tick retry as soon as this resident gets a valid home.
		PrivateChestProvisioning.ensure(level, v, mem, village);
		if (!hasCurrentHome || mem.homeDoor.isEmpty()) {
			return;
		}
		// Das Schild aendert sich selten: ohne Schild sofort, sonst nur etwa alle 1200 Ticks pruefen (die Mitbewohner-Abfrage ist teuer).
		if (mem.signPos.isEmpty() || (level.getGameTime() / de.villagerplus.Config.HOUSING_INTERVAL + v.getId()) % 6 == 0) {
			updateSign(level, v, mem);
		}
	}

	/**
	 * Restore a second bed only in a completed mod-built Architect house with at least two adult residents physically inside.
	 * Candidate positions come from that house's saved design and are written only into air above solid floor blocks.
	 */
	public static void repairBuiltHouseBeds(ServerLevel level, VillageRef village) {
		if (level == null || village == null || village.bellEntity() == null) return;
		long now = level.getGameTime();
		BuiltHouseRepairKey villageKey = new BuiltHouseRepairKey(level.dimension(), village.bell().asLong());
		if (now < NEXT_BUILT_HOUSE_BED_REPAIR.getOrDefault(villageKey, Long.MIN_VALUE)) return;
		NEXT_BUILT_HOUSE_BED_REPAIR.put(villageKey, now + BUILT_HOUSE_BED_REPAIR_INTERVAL);
		List<Villager> adults = Villages.residents(level, village, Config.VILLAGE_RADIUS, resident -> !resident.isBaby());
		if (adults.size() < 2) return;
		List<Built> buildings = Builder.builtOf(village);
		if (buildings.isEmpty()) return;
		for (Built building : buildings) {
			if (!Blueprints.HOUSE.equals(building.type())) continue;
			List<Step> shape = Builder.stepsOfBuilt(level, village, building, null);
			if (shape == null || shape.isEmpty() || shape.size() > 10_000) continue;
			java.util.Set<Long> checkedChunks = new java.util.HashSet<>();
			boolean loaded = true;
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (Step step : shape) {
				BlockPos pos = step.pos();
				long chunk = net.minecraft.world.level.ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
				if (checkedChunks.add(chunk) && !LoadedColumns.ready(level, pos)) { loaded = false; break; }
				minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
				maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
			}
			if (!loaded || (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 10_000L) continue;
			final int houseMinX = minX, houseMinY = minY, houseMinZ = minZ, houseMaxX = maxX, houseMaxY = maxY, houseMaxZ = maxZ;
			long occupants = adults.stream().filter(resident -> inside(resident.blockPosition(), houseMinX, houseMinY,
				houseMinZ, houseMaxX, houseMaxY, houseMaxZ)).count();
			long assignedHomes = adults.stream().filter(resident -> resident.getBrain().getMemory(MemoryModuleType.HOME)
				.filter(home -> home.dimension().equals(level.dimension())
					&& inside(home.pos(), houseMinX, houseMinY, houseMinZ, houseMaxX, houseMaxY, houseMaxZ)).isPresent()).count();
			if (occupants < 2 || assignedHomes < 1) continue;
			List<List<Step>> pairs = Builder.houseBedPairsOfBuilt(level, village, building);
			if (pairs.size() != 2 || countBeds(level, minX, minY, minZ, maxX, maxY, maxZ) != 1) continue;
			Block existingBed = firstBedBlock(level, minX, minY, minZ, maxX, maxY, maxZ);
			if (existingBed == null) continue;
			for (List<Step> pair : pairs) {
				Step foot = pair.getFirst(), head = pair.get(1);
				if (level.getBlockState(foot.pos()).getBlock() instanceof BedBlock
					|| level.getBlockState(head.pos()).getBlock() instanceof BedBlock) continue;
				BlockState existingFoot = level.getBlockState(foot.pos());
				BlockState existingHead = level.getBlockState(head.pos());
				if (!existingFoot.isAir() || !(existingHead.isAir() || matchesSavedFreeBlock(shape, head.pos(), existingHead))) continue;
				if (!level.getBlockState(foot.pos().below()).isFaceSturdy(level, foot.pos().below(), Direction.UP)
					|| !level.getBlockState(head.pos().below()).isFaceSturdy(level, head.pos().below(), Direction.UP)) continue;
				BlockState footState = foot.make().apply(existingBed);
				BlockState headState = head.make().apply(existingBed);
				if (!level.setBlock(foot.pos(), footState, 2)) continue;
				if (!level.setBlock(head.pos(), headState, 3)) {
					level.setBlock(foot.pos(), existingFoot, 3);
					level.setBlock(head.pos(), existingHead, 3);
					continue;
				}
				village.dirty();
				break;
			}
		}
	}

	static void resetBuiltHouseBedRepairForChecks(ServerLevel level, VillageRef village) {
		if (level != null && village != null) NEXT_BUILT_HOUSE_BED_REPAIR.remove(
			new BuiltHouseRepairKey(level.dimension(), village.bell().asLong()));
	}

	private static boolean inside(BlockPos pos, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
			&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	private static int countBeds(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
			BlockState state = LoadedColumns.state(level, pos);
			if (state != null && state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD
				&& pos.equals(physicalBed(level, pos).head())) count++;
		}
		return count;
	}

	private static Block firstBedBlock(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
			BlockState state = LoadedColumns.state(level, pos);
			if (state != null && state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD
				&& physicalBed(level, pos).head() != null) return state.getBlock();
		}
		return null;
	}

	private static boolean matchesSavedFreeBlock(List<Step> shape, BlockPos pos, BlockState current) {
		for (Step step : shape) {
			if (!step.pos().equals(pos) || !step.free()) continue;
			if (step.make().apply(Blocks.AIR).equals(current)) return true;
		}
		return false;
	}

	public static boolean hasHome(Villager v) {
		return v.getBrain().getMemory(MemoryModuleType.HOME).isPresent();
	}

	private static void ensureName(Villager v, VillagerMemory mem) {
		if (v.hasCustomName()) {
			mem.name = v.getCustomName().getString();
			return;
		}
		if (mem.name.isEmpty()) {
			mem.name = uniqueName(v);
		}
		v.setCustomName(Component.literal(mem.name));
		v.setCustomNameVisible(false);
	}

	/** Das Bett, das die Vanilla-KI dem Dorfbewohner zugewiesen hat, wird zum festen Zuhause. */
	private static PhysicalBed trackHome(ServerLevel level, Villager v, VillagerMemory mem) {
		GlobalPos home = v.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
		if (home == null) {
			clearHome(mem);
			return MISSING_BED;
		}
		if (!home.dimension().equals(level.dimension())) return UNREADABLE_BED;
		PhysicalBed bed = physicalBed(level, home.pos());
		if (!bed.loaded()) return bed;
		if (bed.head() == null) {
			// An incomplete bed cannot have a valid resident. Release a remaining POI ticket, if any.
			v.releasePoi(MemoryModuleType.HOME);
			v.getBrain().eraseMemory(MemoryModuleType.HOME);
			clearHome(mem);
		} else if (mem.homeBed.filter(home.pos()::equals).isEmpty()) {
			clearHome(mem);
			mem.homeBed = Optional.of(home.pos());
		}
		return bed;
	}

	private static void clearHome(VillagerMemory memory) {
		memory.homeBed = Optional.empty();
		memory.homeDoor = Optional.empty();
		memory.signPos = Optional.empty();
		memory.signText = "";
		// PrivateChestProvisioning separately validates the prior chest before reuse or migration.
	}

	/** A HOME ticket belongs to one physical bed. Drop stale duplicate claims so only one resident can sleep there. */
	private static boolean releaseDuplicateBedClaim(ServerLevel level, Villager villager, VillagerMemory memory, BlockPos currentBedHead) {
		var nearby = level.getEntitiesOfClass(Villager.class, new AABB(currentBedHead).inflate(32), Villager::isAlive);
		for (Villager other : nearby) {
			if (RESIDENT_ORDER.compare(other, villager) >= 0) continue;
			GlobalPos otherHome = other.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
			if (otherHome == null || !otherHome.dimension().equals(level.dimension())) continue;
			if (!currentBedHead.equals(physicalBed(level, otherHome.pos()).head())) continue;
			// This POI still has a resident. Leave its one ticket reserved.
			villager.getBrain().eraseMemory(MemoryModuleType.HOME);
			clearHome(memory);
			return true;
		}
		return false;
	}

	/** Normalize either bed half to the head, and reject broken or unloaded bed pairs without loading chunks. */
	private static PhysicalBed physicalBed(ServerLevel level, BlockPos home) {
		BlockState state = LoadedColumns.state(level, home);
		if (state == null) return UNREADABLE_BED;
		if (!(state.getBlock() instanceof BedBlock)) return MISSING_BED;
		Direction facing = state.getValue(BedBlock.FACING);
		BedPart part = state.getValue(BedBlock.PART);
		BlockPos counterpart = home.relative(part == BedPart.FOOT ? facing : facing.getOpposite());
		BlockState other = LoadedColumns.state(level, counterpart);
		if (other == null) return UNREADABLE_BED;
		if (!other.is(state.getBlock()) || other.getValue(BedBlock.PART) == part
			|| other.getValue(BedBlock.FACING) != facing) return MISSING_BED;
		return new PhysicalBed(true, part == BedPart.HEAD ? home : counterpart);
	}

	private static Optional<BlockPos> findDoor(ServerLevel level, BlockPos bed) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(bed.offset(-8, -2, -8), bed.offset(8, 3, 8))) {
			BlockState s = LoadedColumns.state(level, p);
			if (s != null && s.getBlock() instanceof DoorBlock d && d.type().canOpenByHand() && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				double dist = p.distSqr(bed);
				if (dist < bestDist) {
					best = p.immutable();
					bestDist = dist;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	static void updateSign(ServerLevel level, Villager v, VillagerMemory mem) {
		BlockPos door = mem.homeDoor.get();
		var nearby = level.getEntitiesOfClass(Villager.class, new AABB(door).inflate(24), Villager::isAlive);
		List<Villager> residents = residentsWithBeds(level, nearby, memory -> memory.homeDoor.filter(door::equals).isPresent());
		// Only residents with distinct current beds maintain the shared house sign.
		if (residents.isEmpty() || residents.getFirst() != v) {
			return;
		}

		BlockState doorState = LoadedColumns.state(level, door);
		if (doorState == null || !(doorState.getBlock() instanceof DoorBlock)) return;
		Direction facing = doorState.getValue(DoorBlock.FACING);
		Direction outward = outsideOfDoor(level, door, mem.homeBed.orElseThrow(), facing);
		// Only a remembered mod sign on this door's wall is ours to move. A saved pointer can now
		// refer to a player's sign, so validate the block and mount before treating it as managed.
		List<BlockPos> previous = residents.stream().map(r -> r.getAttached(VillagerPlus.MEMORY).signPos)
			.flatMap(Optional::stream)
			.filter(pos -> trackedMount(level, door, facing, pos))
			.distinct().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
		BlockPos preferred = previous.stream()
			.filter(pos -> suitableMount(level, door, pos, outward))
			.findFirst().orElseGet(() -> placeSign(level, door, outward).orElse(null));
		BlockPos signPos = preferred != null ? preferred : previous.stream().findFirst().orElse(null);
		if (signPos == null) return;
		if (preferred != null) {
			for (BlockPos old : previous) {
				if (!old.equals(signPos)) level.removeBlock(old, false);
			}
		}
		Optional<BlockPos> rememberedSign = Optional.of(signPos);
		int icon = residents.size() > 1 ? 12 : iconFor(v.getVillagerData().profession());
		int stars = 0;
		for (Villager r : residents) {
			stars = Math.max(stars, Math.min(5, r.getVillagerData().level()));
		}
		String joined = icon + ":" + residents.size() + ":" + stars;
		BlockState cur = level.getBlockState(signPos);
		if (!joined.equals(mem.signText) || cur.getValue(DorfschildBlock.ICON) != icon || cur.getValue(DorfschildBlock.LEVEL) != stars) {
			level.setBlock(signPos, cur.setValue(DorfschildBlock.ICON, icon).setValue(DorfschildBlock.LEVEL, stars), 3);
			mem.signText = joined;
		}
		for (Villager r : residents) {
			VillagerMemory residentMemory = r.getAttached(VillagerPlus.MEMORY);
			if (preferred != null || residentMemory.signPos.isEmpty()) residentMemory.signPos = rememberedSign;
		}
	}

	/** Clicked and displayed house rosters both count distinct complete beds. */
	public static List<Villager> residentsWithBeds(ServerLevel level, List<Villager> nearby, Predicate<VillagerMemory> matchesHouse) {
		var byBed = new HashMap<BlockPos, Villager>();
		for (Villager resident : nearby) {
			VillagerMemory memory = resident.getAttached(VillagerPlus.MEMORY);
			if (memory == null || !matchesHouse.test(memory)) continue;
			GlobalPos home = resident.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
			if (home == null || !home.dimension().equals(level.dimension()) || memory.homeBed.filter(home.pos()::equals).isEmpty()) continue;
			BlockPos bed = physicalBed(level, home.pos()).head();
			if (bed != null) byBed.merge(bed, resident, (a, b) -> RESIDENT_ORDER.compare(a, b) <= 0 ? a : b);
		}
		return byBed.values().stream().sorted(RESIDENT_ORDER).toList();
	}

	/** Symbol-Nummer (siehe Texturen dorfschild_N) fuer einen Beruf: Vanilla direkt, fremde Berufe ueber ihre Taetigkeit. */
	private static int iconFor(net.minecraft.core.Holder<net.minecraft.world.entity.npc.villager.VillagerProfession> prof) {
		String job = de.villagerplus.Compat.jobKey(prof);
		String path = prof.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		if (job == null) {
			job = switch (path) {
				case "farmer" -> "farmer";
				case "shepherd" -> "shepherd";
				case "butcher" -> "butcher";
				case "fisherman" -> "fisherman";
				case "armorer", "toolsmith", "weaponsmith" -> "smith";
				case "cleric" -> "cleric";
				case "mason" -> "mason";
				case "fletcher" -> "fletcher";
				case "leatherworker" -> "leatherworker";
				case "cartographer" -> "cartographer";
				case "librarian" -> "librarian";
				default -> "";
			};
		}
		return switch (job) {
			case "farmer" -> 1;
			case "shepherd" -> 2;
			case "butcher" -> 3;
			case "fisherman" -> 4;
			case "smith" -> 5;
			case "cleric" -> 6;
			case "mason" -> 7;
			case "fletcher" -> 8;
			case "leatherworker" -> 9;
			case "cartographer" -> 10;
			case "librarian" -> 11;
			default -> path.equals("nitwit") ? 13 : 0;
		};
	}

	private static String trim(String s) {
		return s.length() > 15 ? s.substring(0, 15) : s;
	}

	/** Inside/outside follows the bed's side of the wall even when an overhang blocks sky on both sides. */
	private static Direction outsideOfDoor(ServerLevel level, BlockPos door, BlockPos bed, Direction facing) {
		int inside = (bed.getX() - door.getX()) * facing.getStepX() + (bed.getZ() - door.getZ()) * facing.getStepZ();
		if (inside > 0) return facing.getOpposite();
		if (inside < 0) return facing;
		BlockPos a = door.relative(facing), b = door.relative(facing.getOpposite());
		if (LoadedColumns.state(level, a) != null && LoadedColumns.state(level, b) != null
			&& !level.canSeeSky(a) && level.canSeeSky(b)) return facing.getOpposite();
		return facing;
	}

	private static boolean trackedMount(ServerLevel level, BlockPos door, Direction facing, BlockPos sign) {
		BlockState state = LoadedColumns.state(level, sign);
		if (state == null || !(state.getBlock() instanceof DorfschildBlock)) return false;
		Direction out = state.getValue(DorfschildBlock.FACING);
		if (out.getAxis() != facing.getAxis()) return false;
		BlockPos offset = sign.relative(out.getOpposite()).subtract(door);
		int normal = offset.getX() * out.getStepX() + offset.getZ() * out.getStepZ();
		int side = Math.abs(offset.getX() * out.getStepZ() - offset.getZ() * out.getStepX());
		return normal == 0 && side >= 1 && side <= 4 && offset.getY() >= 0 && offset.getY() <= 2;
	}

	private static boolean suitableMount(ServerLevel level, BlockPos door, BlockPos sign, Direction outward) {
		BlockState state = LoadedColumns.state(level, sign);
		if (state == null || !(state.getBlock() instanceof DorfschildBlock) || state.getValue(DorfschildBlock.FACING) != outward) return false;
		Direction side = outward.getClockWise();
		BlockPos wall = sign.relative(outward.getOpposite());
		BlockPos offset = wall.subtract(door);
		int lateral = Math.abs(offset.getX() * side.getStepX() + offset.getZ() * side.getStepZ());
		return lateral >= 2 && clearSignWallCell(level, wall, outward, side);
	}

	/** Look for an opaque facade cell clear of windows and trim, without moving blocks already there. */
	private static Optional<BlockPos> placeSign(ServerLevel level, BlockPos door, Direction outward) {
		Direction clockwise = outward.getClockWise();
		BlockPos upper = door.above();
		for (int height : new int[] {0, 1, -1}) {
			for (int distance : new int[] {2, 3, 4}) {
				for (Direction side : new Direction[] {clockwise, clockwise.getOpposite()}) {
					BlockPos wall = upper.relative(side, distance).above(height);
					BlockPos spot = wall.relative(outward);
					BlockState state = LoadedColumns.state(level, spot);
					if (state == null || !state.isAir() || !clearSignWallCell(level, wall, outward, side)) continue;
					BlockState sign = ModBlocks.DORFSCHILD.defaultBlockState().setValue(DorfschildBlock.FACING, outward);
					if (level.setBlock(spot, sign, 3)) return Optional.of(spot);
				}
			}
		}
		return Optional.empty();
	}

	/** Reject glass, panes and details around the support block so the sign never covers a windowed facade. */
	private static boolean clearSignWallCell(ServerLevel level, BlockPos wall, Direction out, Direction side) {
		BlockState state = LoadedColumns.state(level, wall);
		if (state == null || !state.isCollisionShapeFullBlock(level, wall) || !state.isFaceSturdy(level, wall, out)
			|| isWindowOrFacadeDetail(state)) {
			return false;
		}
		for (int lateral = -1; lateral <= 1; lateral++) {
			for (int vertical = -1; vertical <= 1; vertical++) {
				if (lateral == 0 && vertical == 0) continue;
				BlockPos neighbor = wall.relative(side, lateral).above(vertical);
				BlockState neighborState = LoadedColumns.state(level, neighbor);
				if (neighborState == null || isWindowOrFacadeDetail(neighborState)) return false;
			}
		}
		return true;
	}

	private static boolean isWindowOrFacadeDetail(BlockState state) {
		Block block = state.getBlock();
		return block instanceof TransparentBlock || block instanceof IronBarsBlock || block instanceof DoorBlock
			|| block instanceof TrapDoorBlock || block instanceof SignBlock;
	}

	/** Zufaelliger Name, der unter den Dorfbewohnern in der Naehe noch nicht vergeben ist (nach 12 Versuchen mit Nummer). */
	private static String uniqueName(Villager v) {
		java.util.Set<String> used = new java.util.HashSet<>();
		for (Villager o : v.level().getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(96), x -> x != v)) {
			used.add(o.getAttachedOrCreate(VillagerPlus.MEMORY).name);
			if (o.hasCustomName()) {
				used.add(o.getCustomName().getString());
			}
		}
		for (int i = 0; i < 12; i++) {
			String n = Names.random(v.getRandom());
			if (!used.contains(n)) {
				return n;
			}
		}
		String base = Names.random(v.getRandom());
		int k = 2;
		while (used.contains(base + " " + k)) {
			k++;
		}
		return base + " " + k;
	}}
