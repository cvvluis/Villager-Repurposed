package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Paid, physical annex for the registered livestock pen. Its block layout is the persisted upgrade state. */
public final class PenExpansion {
	private static final int ANNEX_CAPACITY_BONUS = 4;
	private static final int ANNEX_NEAR = 5;
	private static final int ANNEX_FAR = 9;
	private static final int ANNEX_HALF_WIDTH = 2;
	private static final int ANNEX_GATE_COST = 1;

	private PenExpansion() { }

	record Gate(BlockPos position, Direction outward) {
		Gate {
			position = position.immutable();
		}
		BlockPos inside() { return position.relative(outward.getOpposite()); }
		BlockPos outside() { return position.relative(outward); }
	}

	private record Layout(BlockState baseGate, Direction baseEntrance, Direction outward, Direction lateral,
		List<BlockPos> fenceCells, BlockPos annexGate) { }

	public static boolean overCapacity(ServerLevel level, BlockPos pen) {
		if (level == null || pen == null || !level.hasChunkAt(pen)) return false;
		AABB area = area(level, pen);
		List<Animal> herd = level.getEntitiesOfClass(Animal.class, area, Animal::isAlive);
		return overCapacity(level, pen, herd, List.of());
	}

	static boolean overCapacity(ServerLevel level, VillageRef ref) {
		if (level == null || ref == null || ref.data().extra.pen.isEmpty()) return false;
		BlockPos pen = ref.data().extra.pen.get();
		if (!level.hasChunkAt(pen)) return false;
		return overCapacity(level, ref.data().extra.pen.get(),
			level.getEntitiesOfClass(Animal.class, area(level, ref.data().extra.pen.get()), Animal::isAlive), validPastures(ref));
	}

	static boolean overCapacity(ServerLevel level, BlockPos pen, List<Animal> herd, List<ZonePolicy.Zone> pastures) {
		AABB area = area(level, pen);
		int capacity = capacity(level, pen, pastures);
		for (var type : List.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN)) {
			long count = herd.stream().filter(animal -> animal != null && animal.isAlive() && animal.getType() == type
				&& Livestock.inPasture(animal, area, pastures) && contains(level, pen, animal)).count();
			if (count > capacity) return true;
		}
		return false;
	}

	/** Compatibility helper for geometry-only checks that do not have a loaded world or a registered annex. */
	static boolean overCapacity(BlockPos pen, List<Animal> herd, List<ZonePolicy.Zone> pastures) {
		AABB area = new AABB(pen).inflate(5, 3, 5);
		for (var type : List.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN)) {
			long count = herd.stream().filter(animal -> animal != null && animal.isAlive() && animal.getType() == type
				&& Livestock.inPasture(animal, area, pastures)).count();
			if (count > Config.CULL_CAP) return true;
		}
		return false;
	}

	/** The stall retry becomes due at the base cap, but only if a complete, safe annex can be built here. */
	static boolean dueAtCapacity(ServerLevel level, VillageRef ref) {
		if (level == null || ref == null || ref.data().extra.pen.isEmpty()) return false;
		BlockPos pen = ref.data().extra.pen.get();
		if (!level.hasChunkAt(pen) || isExpanded(level, pen, validPastures(ref))) return false;
		Layout layout = layout(level, pen);
		if (layout == null || !pastureCoversAnnex(pen, layout, validPastures(ref))
			|| !usableAnnexInterior(level, pen, layout)) return false;
		AABB area = area(level, pen);
		List<Animal> herd = level.getEntitiesOfClass(Animal.class, area, Animal::isAlive);
		return dueAtCapacity(level, pen, herd, validPastures(ref));
	}

	static boolean dueAtCapacity(BlockPos pen, List<Animal> herd, List<ZonePolicy.Zone> pastures) {
		AABB area = new AABB(pen).inflate(5, 3, 5);
		for (var type : List.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN)) {
			long count = herd.stream().filter(animal -> animal != null && animal.isAlive() && animal.getType() == type
				&& Livestock.inPasture(animal, area, pastures)).count();
			if (count >= Config.CULL_CAP) return true;
		}
		return false;
	}

	private static boolean dueAtCapacity(ServerLevel level, BlockPos pen, List<Animal> herd,
		List<ZonePolicy.Zone> pastures) {
		AABB area = area(level, pen);
		for (var type : List.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN)) {
			long count = herd.stream().filter(animal -> animal != null && animal.isAlive() && animal.getType() == type
				&& Livestock.inPasture(animal, area, pastures) && contains(level, pen, animal)).count();
			if (count >= Config.CULL_CAP) return true;
		}
		return false;
	}

	public static int capacity(ServerLevel level, VillageRef ref) {
		if (ref == null || ref.data().extra.pen.isEmpty()) return Config.CULL_CAP;
		return capacity(level, ref.data().extra.pen.get(), validPastures(ref));
	}

	static int capacity(ServerLevel level, BlockPos pen, List<ZonePolicy.Zone> pastures) {
		return isExpanded(level, pen, pastures) ? Config.CULL_CAP + ANNEX_CAPACITY_BONUS : Config.CULL_CAP;
	}

	static boolean isExpanded(ServerLevel level, BlockPos pen) {
		return isExpanded(level, pen, List.of());
	}

	static boolean isExpanded(ServerLevel level, BlockPos pen, List<ZonePolicy.Zone> pastures) {
		Layout layout = layout(level, pen);
		if (layout == null || !pastureCoversAnnex(pen, layout, pastures) || !usableAnnexInterior(level, pen, layout)) return false;
		for (BlockPos pos : layout.fenceCells()) {
			if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(BlockTags.FENCES)
				|| level.getBlockEntity(pos) != null || !supportedFloor(level, pos)) return false;
		}
		BlockPos gatePos = layout.annexGate();
		if (!level.hasChunkAt(gatePos) || level.getBlockEntity(gatePos) != null || !supportedFloor(level, gatePos)) return false;
		BlockState gate = level.getBlockState(gatePos);
		return gate.getBlock() == layout.baseGate().getBlock()
			&& gate.getBlock() instanceof FenceGateBlock
			&& gate.getValue(FenceGateBlock.FACING) == layout.outward();
	}

	/** Managed query bounds include a partial annex so restart retries still see any real animals already there. */
	static AABB area(ServerLevel level, BlockPos pen) {
		AABB base = new AABB(pen).inflate(5, 3, 5);
		if (level == null || pen == null || !level.hasChunkAt(pen)) return base;
		Layout layout = layout(level, pen);
		if (layout == null || !hasAnnexFootprint(level, layout)) return base;
		double minX = base.minX, minY = base.minY, minZ = base.minZ;
		double maxX = base.maxX, maxY = base.maxY, maxZ = base.maxZ;
		List<BlockPos> positions = new ArrayList<>(layout.fenceCells());
		positions.add(layout.annexGate());
		for (int along = ANNEX_NEAR; along <= ANNEX_FAR; along++) {
			for (int across = -1; across <= 1; across++) positions.add(offset(pen, layout.outward(), layout.lateral(), along, across));
		}
		for (BlockPos pos : positions) {
			minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX() + 1);
			minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ() + 1);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	static boolean contains(ServerLevel level, BlockPos pen, Entity entity) {
		if (entity == null) return false;
		if (new AABB(pen).inflate(3.0, 2.0, 3.0).contains(entity.position())) return true;
		if (level == null || !level.hasChunkAt(pen)) return false;
		Layout layout = layout(level, pen);
		if (layout == null || !isExpanded(level, pen, List.of())) return false;
		AABB annex = annexInterior(pen, layout);
		return annex.contains(entity.position());
	}

	static boolean inAnnex(ServerLevel level, BlockPos pen, Entity entity) {
		if (level == null || entity == null || !level.hasChunkAt(pen)) return false;
		Layout layout = layout(level, pen);
		return layout != null && isExpanded(level, pen, List.of()) && annexInterior(pen, layout).contains(entity.position());
	}

	static int sectionCapacity(ServerLevel level, BlockPos pen, Entity entity) {
		if (level == null || pen == null || !isExpanded(level, pen, List.of())) return Config.CULL_CAP;
		return inAnnex(level, pen, entity) ? ANNEX_CAPACITY_BONUS : Config.CULL_CAP;
	}

	static boolean sameSection(ServerLevel level, BlockPos pen, Entity first, Entity second) {
		boolean firstAnnex = inAnnex(level, pen, first);
		boolean secondAnnex = inAnnex(level, pen, second);
		return firstAnnex == secondAnnex;
	}

	static Gate herdingGate(ServerLevel level, BlockPos pen) {
		return herdingGate(level, pen, List.of());
	}

	static Gate herdingGate(ServerLevel level, BlockPos pen, List<ZonePolicy.Zone> pastures) {
		if (level == null || pen == null || !level.hasChunkAt(pen)) return null;
		Layout layout = layout(level, pen);
		if (layout == null) return null;
		if (isExpanded(level, pen, pastures)) return new Gate(layout.annexGate(), layout.outward());
		BlockPos base = baseGatePosition(pen, layout.baseEntrance());
		return new Gate(base, layout.baseEntrance());
	}

	static List<BlockPos> feedStands(ServerLevel level, BlockPos pen) {
		List<BlockPos> stands = new ArrayList<>(List.of(pen.north(5), pen.south(5), pen.east(5), pen.west(5)));
		if (level == null || pen == null || !level.hasChunkAt(pen)) return List.copyOf(stands);
		Layout layout = layout(level, pen);
		if (layout == null || !isExpanded(level, pen, List.of())) return List.copyOf(stands);
		stands.add(layout.annexGate().relative(layout.outward()));
		stands.add(offset(pen, layout.outward(), layout.lateral(), 6, -5));
		stands.add(offset(pen, layout.outward(), layout.lateral(), 6, 5));
		return List.copyOf(stands);
	}

	public static boolean work(Ctx c) {
		if (c.village() == null || c.village().data().extra.pen.isEmpty()) return false;
		ServerLevel level = c.level();
		BlockPos pen = c.village().data().extra.pen.get();
		if (!dueAtCapacity(level, c.village())) return false;
		Layout layout = layout(level, pen);
		if (layout == null || !pastureCoversAnnex(pen, layout, validPastures(c.village()))
			|| !usableAnnexInterior(level, pen, layout)) return false;
		Container chest = communal(c);
		if (chest == null) return false;
		List<BlockPos> missingFences = missingFenceCells(level, layout, c.v());
		boolean missingGate = missingGate(level, layout);
		if (missingFences == null || !validGateSite(level, layout, c.v()) || missingFences.isEmpty() && !missingGate) return false;
		Item gateItem = layout.baseGate().getBlock().asItem();
		if (gateItem == Items.AIR) return false;
		int fenceCount = missingFences.size();
		if (JobUtil.count(chest, stack -> stack.is(Items.OAK_FENCE)) < fenceCount
			|| missingGate && JobUtil.count(chest, stack -> stack.is(gateItem)) < ANNEX_GATE_COST) return false;
		BlockPos stand = workStand(level, pen, layout, missingFences, missingGate, c.v());
		if (stand == null) return false;
		c.setActivity("erweitert den Stall");
		Anim.hold(c, missingGate && missingFences.isEmpty() ? gateItem : Items.OAK_FENCE);
		if (!JobUtil.approach(c, stand, 0.75)) return true;

		// Re-read every cell at the point of use; a blocked or unloaded annex is left untouched.
		Layout current = layout(level, pen);
		if (current == null || !current.equals(layout) || !pastureCoversAnnex(pen, current, validPastures(c.village()))
			|| !usableAnnexInterior(level, pen, current)) return false;
		missingFences = missingFenceCells(level, current, c.v());
		missingGate = missingGate(level, current);
		if (missingFences == null || !validGateSite(level, current, c.v()) || missingFences.isEmpty() && !missingGate
			|| !safeWorkStand(level, stand, c.v())) return false;
		fenceCount = missingFences.size();
		if (JobUtil.count(chest, stack -> stack.is(Items.OAK_FENCE)) < fenceCount
			|| missingGate && JobUtil.count(chest, stack -> stack.is(gateItem)) < ANNEX_GATE_COST) return false;

		Map<BlockPos, BlockState> changedBlocks = new LinkedHashMap<>();
		List<ItemStack> stockBefore = snapshot(chest);
		for (BlockPos cell : missingFences) {
			if (JobUtil.take(chest, stack -> stack.is(Items.OAK_FENCE), 1) != 1) {
				rollback(level, changedBlocks, chest, stockBefore);
				return false;
			}
			BlockState before = level.getBlockState(cell);
			if (!before.isAir() || level.getBlockEntity(cell) != null
				|| !safeFenceCell(level, cell, c.v())
				|| !level.setBlock(cell, Blocks.OAK_FENCE.defaultBlockState(), 3)) {
				rollback(level, changedBlocks, chest, stockBefore);
				return false;
			}
			changedBlocks.put(cell.immutable(), before);
		}
		if (missingGate) {
			if (JobUtil.take(chest, stack -> stack.is(gateItem), ANNEX_GATE_COST) != ANNEX_GATE_COST) {
				rollback(level, changedBlocks, chest, stockBefore);
				return false;
			}
			BlockPos gatePos = current.annexGate();
			BlockState before = level.getBlockState(gatePos);
			BlockState state = current.baseGate().getBlock().defaultBlockState()
				.setValue(FenceGateBlock.FACING, current.outward()).setValue(FenceGateBlock.OPEN, false);
			if (!before.isAir() || level.getBlockEntity(gatePos) != null || !safeFenceCell(level, gatePos, c.v())
				|| !level.setBlock(gatePos, state, 3)) {
				rollback(level, changedBlocks, chest, stockBefore);
				return false;
			}
			changedBlocks.put(gatePos.immutable(), before);
		}
		if (!isExpanded(level, pen, validPastures(c.village()))) {
			rollback(level, changedBlocks, chest, stockBefore);
			return false;
		}
		BlockPos soundAt = changedBlocks.keySet().stream().findFirst().orElse(current.annexGate());
		level.playSound(null, soundAt, net.minecraft.sounds.SoundEvents.WOOD_PLACE,
			net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 40;
		return true;
	}

	static List<BlockPos> plan(ServerLevel level, BlockPos pen) {
		if (level == null || pen == null || !level.hasChunkAt(pen)) return List.of();
		Layout layout = layout(level, pen);
		if (layout == null) return List.of();
		List<BlockPos> missing = missingFenceCells(level, layout, null);
		return missing == null || !validGateSite(level, layout, null) || !usableAnnexInterior(level, pen, layout)
			? List.of() : List.copyOf(missing);
	}

	static BlockPos workStand(ServerLevel level, BlockPos pen, Entity ignoredEntity) {
		Layout layout = layout(level, pen);
		if (layout == null) return null;
		List<BlockPos> missing = missingFenceCells(level, layout, ignoredEntity);
		boolean needsGate = missingGate(level, layout);
		if (missing == null || !validGateSite(level, layout, ignoredEntity) || missing.isEmpty() && !needsGate) return null;
		return workStand(level, pen, layout, missing, needsGate, ignoredEntity);
	}

	private static BlockPos workStand(ServerLevel level, BlockPos pen, Layout layout,
		List<BlockPos> missingFences, boolean missingGate, Entity ignoredEntity) {
		BlockPos target = !missingFences.isEmpty() ? missingFences.getFirst() : layout.annexGate();
		BlockPos stand = target.relative(layout.lateral(), -1);
		if (!missingFences.isEmpty() && safeWorkStand(level, stand, ignoredEntity)) return stand;
		stand = target.relative(layout.lateral());
		if (!missingFences.isEmpty() && safeWorkStand(level, stand, ignoredEntity)) return stand;
		stand = missingGate ? layout.annexGate().relative(layout.outward()) : null;
		return stand != null && safeWorkStand(level, stand, ignoredEntity) ? stand : null;
	}

	private static boolean missingGate(ServerLevel level, Layout layout) {
		BlockPos pos = layout.annexGate();
		if (!level.hasChunkAt(pos)) return false;
		BlockState state = level.getBlockState(pos);
		return state.isAir();
	}

	private static boolean validGateSite(ServerLevel level, Layout layout, Entity ignoredEntity) {
		BlockPos pos = layout.annexGate();
		if (!level.hasChunkAt(pos) || !supportedFloor(level, pos) || level.getBlockEntity(pos) != null) return false;
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() == layout.baseGate().getBlock() && state.getBlock() instanceof FenceGateBlock
			&& state.getValue(FenceGateBlock.FACING) == layout.outward()) return true;
		return state.isAir() && safeFenceCell(level, pos, ignoredEntity);
	}

	private static List<BlockPos> missingFenceCells(ServerLevel level, Layout layout, Entity ignoredEntity) {
		List<BlockPos> missing = new ArrayList<>();
		for (BlockPos pos : layout.fenceCells()) {
			if (!level.hasChunkAt(pos) || !supportedFloor(level, pos) || level.getBlockEntity(pos) != null) return null;
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.FENCES)) continue;
			if (!state.isAir() || !safeFenceCell(level, pos, ignoredEntity)) return null;
			missing.add(pos.immutable());
		}
		return List.copyOf(missing);
	}

	private static Layout layout(ServerLevel level, BlockPos pen) {
		if (level == null || pen == null || !level.hasChunkAt(pen)) return null;
		Direction entrance = null;
		BlockState baseGate = null;
		for (Direction outward : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
			BlockPos gate = pen.relative(outward, 4);
			if (!level.hasChunkAt(gate)) continue;
			BlockState state = level.getBlockState(gate);
			if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.FACING) == outward) {
				entrance = outward;
				baseGate = state;
				break;
			}
		}
		if (entrance == null) return null;
		Direction outward = entrance.getOpposite();
		Direction lateral = outward.getClockWise();
		List<BlockPos> fences = new ArrayList<>();
		for (int along = ANNEX_NEAR; along < ANNEX_FAR; along++) {
			fences.add(offset(pen, outward, lateral, along, -ANNEX_HALF_WIDTH));
			fences.add(offset(pen, outward, lateral, along, ANNEX_HALF_WIDTH));
		}
		for (int across = -ANNEX_HALF_WIDTH; across <= ANNEX_HALF_WIDTH; across++) {
			if (across != 0) fences.add(offset(pen, outward, lateral, ANNEX_FAR, across));
		}
		return new Layout(baseGate, entrance, outward, lateral, List.copyOf(fences),
			offset(pen, outward, lateral, ANNEX_FAR, 0));
	}

	private static BlockPos baseGatePosition(BlockPos pen, Direction entrance) {
		return pen.relative(entrance, 4);
	}

	private static BlockPos offset(BlockPos pen, Direction outward, Direction lateral, int along, int across) {
		return pen.relative(outward, along).relative(lateral, across).immutable();
	}

	private static AABB annexInterior(BlockPos pen, Layout layout) {
		BlockPos first = offset(pen, layout.outward(), layout.lateral(), ANNEX_NEAR, -1);
		BlockPos last = offset(pen, layout.outward(), layout.lateral(), ANNEX_FAR - 1, 1);
		double minX = Math.min(first.getX(), last.getX());
		double maxX = Math.max(first.getX(), last.getX()) + 1;
		double minZ = Math.min(first.getZ(), last.getZ());
		double maxZ = Math.max(first.getZ(), last.getZ()) + 1;
		return new AABB(minX, pen.getY() - 2, minZ, maxX, pen.getY() + 3, maxZ);
	}

	private static boolean hasAnnexFootprint(ServerLevel level, Layout layout) {
		if (level.hasChunkAt(layout.annexGate())
			&& level.getBlockState(layout.annexGate()).getBlock() instanceof FenceGateBlock) return true;
		for (BlockPos pos : layout.fenceCells()) {
			if (level.hasChunkAt(pos) && level.getBlockState(pos).is(BlockTags.FENCES)) return true;
		}
		return false;
	}

	/** The 3×4 usable floor and its headroom must be loaded, supported and walkable. */
	private static boolean usableAnnexInterior(ServerLevel level, BlockPos pen, Layout layout) {
		for (int along = ANNEX_NEAR; along < ANNEX_FAR; along++) {
			for (int across = -1; across <= 1; across++) {
				BlockPos cell = offset(pen, layout.outward(), layout.lateral(), along, across);
				BlockPos head = cell.above();
				if (!level.hasChunkAt(cell) || !level.hasChunkAt(head) || !supportedFloor(level, cell)) return false;
				BlockState state = level.getBlockState(cell);
				if (!state.getCollisionShape(level, cell).isEmpty()
					|| !level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return false;
			}
		}
		return true;
	}

	private static boolean pastureCoversAnnex(BlockPos pen, Layout layout, List<ZonePolicy.Zone> pastures) {
		if (pastures.isEmpty()) return true;
		for (int along = ANNEX_NEAR; along < ANNEX_FAR; along++) {
			for (int across = -1; across <= 1; across++) {
				BlockPos cell = offset(pen, layout.outward(), layout.lateral(), along, across);
				if (pastures.stream().noneMatch(zone -> cell.getX() >= zone.minX() && cell.getX() <= zone.maxX()
					&& cell.getZ() >= zone.minZ() && cell.getZ() <= zone.maxZ())) return false;
			}
		}
		return true;
	}

	private static boolean supportedFloor(ServerLevel level, BlockPos cell) {
		BlockPos floor = cell.below();
		return level.hasChunkAt(floor) && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
	}

	private static boolean safeWorkStand(ServerLevel level, BlockPos stand, Entity ignoredEntity) {
		BlockPos floor = stand.below();
		if (!level.hasChunkAt(stand) || !level.getBlockState(stand).isAir() || level.getBlockEntity(stand) != null
			|| !level.hasChunkAt(floor) || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return false;
		return level.getEntitiesOfClass(Entity.class, new AABB(stand), entity -> entity != ignoredEntity
			&& entity.isAlive() && !entity.isSpectator()).isEmpty();
	}

	private static boolean safeFenceCell(ServerLevel level, BlockPos cell, Entity ignoredEntity) {
		if (!level.hasChunkAt(cell) || !level.getBlockState(cell).isAir() || level.getBlockEntity(cell) != null) return false;
		return level.getEntitiesOfClass(Entity.class, new AABB(cell), entity -> entity != ignoredEntity
			&& entityOccupiesFenceCell(cell, entity)).isEmpty();
	}

	static boolean entityOccupiesFenceCell(BlockPos cell, Entity entity) {
		return cell != null && entity != null && entity.isAlive() && !entity.isSpectator()
			&& entity.getBoundingBox().intersects(new AABB(cell));
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> contents = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) contents.add(container.getItem(slot).copy());
		return List.copyOf(contents);
	}

	private static void rollback(ServerLevel level, Map<BlockPos, BlockState> changed, Container chest, List<ItemStack> stock) {
		changed.forEach((pos, state) -> {
			if (level.hasChunkAt(pos)) level.setBlock(pos, state, 3);
		});
		for (int slot = 0; slot < Math.min(chest.getContainerSize(), stock.size()); slot++) chest.setItem(slot, stock.get(slot).copy());
		chest.setChanged();
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null || !c.level().hasChunkAt(pos)) return null;
		var entity = c.level().getBlockEntity(pos);
		if (entity == null || entity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}

	private static List<ZonePolicy.Zone> validPastures(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(zone -> "pasture".equals(zone.kind())
				&& ZonePolicy.validBounds(ref, zone.kind(), zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
	}
}
