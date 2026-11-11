package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Built;
import de.villagerplus.build.Builder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Transactional move for village-owned blueprint buildings, preserving block entities and inventories. */
public final class BuildingRelocation {
	private BuildingRelocation() { }
	private static final int MOVE_FLAGS = 2 | 16 | 32; // clients, known shape, suppress drops; notify neighbours after the transaction
	private record Snapshot(BlockPos pos, BlockState state, CompoundTag blockEntity, UUID owner, List<ItemStack> inventory) { }
	@FunctionalInterface interface Placement { boolean set(BlockPos pos, BlockState state); }

	public static String move(ServerLevel level, VillageRef ref, BlockPos requested, BlockPos destination) {
		return move(level, ref, requested, destination, (pos, state) -> put(level, pos, state));
	}

	/** Package-local placement seam lets the development world exercise rollback after an actual partial write. */
	static String move(ServerLevel level, VillageRef ref, BlockPos requested, BlockPos destination, Placement placement) {
		Built built = Builder.builtOf(ref).stream().filter(b -> Math.abs(b.origin().getX() - requested.getX()) <= 3
			&& Math.abs(b.origin().getZ() - requested.getZ()) <= 3).findFirst().orElse(null);
		if (built == null) return "Dort steht kein registriertes Dorfgebäude.";
		if (ref.data().hasPlan() && ref.data().planOrigin.filter(built.origin()::equals).isPresent()) return "Ein Gebäude im Bau kann nicht versetzt werden.";
		if (Demolition.jobs(ref).stream().anyMatch(j -> j.origin().equals(built.origin()))) return "Ein Gebäude mit laufendem Abrissauftrag kann nicht versetzt werden.";
		Villager witness = Villages.residents(level, ref, 96, v -> !v.isBaby()).stream().findFirst().orElse(null);
		if (witness == null) return "Zum Prüfen des Bauplans muss ein erwachsener Dorfbewohner geladen sein.";
		List<de.villagerplus.build.Step> steps = Builder.stepsOfBuilt(level, ref, built, witness);
		if (steps.isEmpty() || steps.size() > 8192) return "Dieses Gebäude hat keinen unterstützten, sicheren Versetzungsplan.";
		int dx = destination.getX() - built.origin().getX(), dz = destination.getZ() - built.origin().getZ();
		if (dx == 0 && dz == 0) return "Das Gebäude steht bereits dort.";
		if (destination.distSqr(ref.bell()) > 96L * 96L) return "Das Ziel liegt außerhalb des Dorfgebiets.";
		Set<BlockPos> footprint = new LinkedHashSet<>();
		Map<Long, Integer> roof = new HashMap<>();
		for (var step : steps) {
			BlockPos p = step.pos().immutable(); footprint.add(p);
			roof.merge(BlockPos.asLong(p.getX(), 0, p.getZ()), p.getY(), Math::max);
		}
		// Furnisher installs chests/workstations after Architect has finished. Include their enclosed floor columns,
		// including air, so contents move and an obstruction in the destination room is rejected as well.
		int buildingTop = roof.values().stream().mapToInt(Integer::intValue).max().orElse(built.origin().getY());
		int structureMinX = roof.keySet().stream().mapToInt(key -> BlockPos.getX(key)).min().orElse(built.origin().getX());
		int structureMaxX = roof.keySet().stream().mapToInt(key -> BlockPos.getX(key)).max().orElse(built.origin().getX());
		int structureMinZ = roof.keySet().stream().mapToInt(key -> BlockPos.getZ(key)).min().orElse(built.origin().getZ());
		int structureMaxZ = roof.keySet().stream().mapToInt(key -> BlockPos.getZ(key)).max().orElse(built.origin().getZ());
		for (var entry : roof.entrySet()) {
			BlockPos col = BlockPos.of(entry.getKey());
			BlockPos floor = new BlockPos(col.getX(), built.origin().getY() - 1, col.getZ());
			boolean insideWallLine = col.getX() > structureMinX && col.getX() < structureMaxX
				&& col.getZ() > structureMinZ && col.getZ() < structureMaxZ;
			if (!footprint.contains(floor) || !insideWallLine) continue;
			// Post-build furniture occupies interior air even in columns with no roof/stair step
			// directly overhead. Cover every enclosed floor column to the shared building top.
			for (int y = built.origin().getY(); y <= buildingTop; y++) footprint.add(new BlockPos(col.getX(), y, col.getZ()));
		}
		if (footprint.size() > 8192) return "Dieses Gebäude ist für eine sichere Versetzung zu groß.";
		Map<BlockPos, BlockPos> moves = new LinkedHashMap<>();
		for (BlockPos from : footprint.stream().sorted(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ())).toList()) {
			moves.put(from, from.offset(dx, 0, dz));
		}
		Set<Long> checkedColumns = new java.util.HashSet<>();
		// Roof overhangs and chimneys can occupy columns without a floor step. Their heightmap is still the old
		// building rather than new terrain, so every original structural column belongs to the overlap exemption.
		Set<Long> sourceStructuralColumns = roof.keySet();
		for (var e : moves.entrySet()) {
			BlockPos from = e.getKey(), to = e.getValue();
			if (!level.hasChunkAt(from) || !level.hasChunkAt(to) || to.distSqr(ref.bell()) > 96L * 96L || to.getY() < level.getMinY() || to.getY() >= level.getMaxY()) return "Quelle oder Ziel ist nicht vollständig geladen oder außerhalb des Dorfgebiets.";
			if (from.equals(ref.bell()) || to.equals(ref.bell())) return "Die Dorfglocke darf nicht überbaut werden.";
			if (moves.containsKey(to)) continue;
			BlockState target = level.getBlockState(to);
			boolean foundation = from.getY() < built.origin().getY();
			if (!target.isAir() && !(foundation && naturalGround(target))) return "Die Zielfläche ist nicht frei; nichts wurde verändert. Quelle "
				+ coordinates(from) + ", Ziel " + coordinates(to) + ", Block " + target + ", Fundament " + foundation + ".";
			if (level.getBlockEntity(to) != null) return "Die Zielfläche enthält ein Blockinventar; nichts wurde verändert.";
			long column = BlockPos.asLong(to.getX(), 0, to.getZ());
			if (foundation && !sourceStructuralColumns.contains(column) && checkedColumns.add(column)) {
				int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ());
				if (Math.abs(built.origin().getY() - surface) > 2) return "Der Baugrund am Ziel ist zu hoch oder zu tief. Ziel "
					+ coordinates(to) + ", Oberfläche " + surface + ", Gebäudehöhe " + built.origin().getY() + ".";
			}
		}
		VillageData.PlanBounds sourceBounds = boundsFor(footprint);
		VillageData.PlanBounds destinationBounds = boundsFor(moves.values());
		if (Demolition.overlapsConstructionReservation(ref, sourceBounds)
			|| Demolition.overlapsConstructionReservation(ref, destinationBounds)) {
			return "Quelle oder Ziel wird von einem laufenden Bauprojekt reserviert; nichts wurde verändert.";
		}
		Map<BlockPos, Snapshot> before = new LinkedHashMap<>();
		List<Snapshot> source = new ArrayList<>();
		for (var e : moves.entrySet()) {
			Snapshot saved = snapshot(level, e.getKey()); source.add(saved); before.putIfAbsent(saved.pos(), saved);
			before.computeIfAbsent(e.getValue(), p -> snapshot(level, p));
		}
		// Recheck immediately before the first world write, including non-primary and legacy reservations.
		if (Demolition.overlapsConstructionReservation(ref, sourceBounds)
			|| Demolition.overlapsConstructionReservation(ref, destinationBounds)) {
			return "Quelle oder Ziel wird von einem laufenden Bauprojekt reserviert; nichts wurde verändert.";
		}
		try {
			// One union snapshot and a dropless clear handle overlap and containers without cloning live inventories.
			clear(level, before.keySet());
			for (Snapshot s : source) {
				if (!moves.containsValue(s.pos()) && s.pos().getY() < built.origin().getY()) {
					// The displaced natural ground fills the old foundation rather than disappearing or being duplicated.
					BlockPos displaced = moves.get(s.pos());
					while (moves.containsKey(displaced)) displaced = moves.get(displaced);
					write(level, s.pos(), before.get(displaced), (pos, state) -> put(level, pos, state));
				}
			}
			for (Snapshot s : source) write(level, moves.get(s.pos()), s, placement);
		} catch (RuntimeException failed) {
			restore(level, before);
			return "Das Ziel hat die Änderung abgelehnt; das Gebäude wurde zurückgerollt.";
		}
		List<Built> buildings = new ArrayList<>(Builder.builtOf(ref));
		int index = buildings.indexOf(built);
		Built moved = new Built(built.type(), built.origin().offset(dx, 0, dz), built.dir(), built.variant(), built.palette());
		if (index >= 0) buildings.set(index, moved);
		ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, List.copyOf(buildings));
		Map<String, BlockPos> specials = new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.SPECIALS, Map.of()));
		specials.replaceAll((key, pos) -> pos.equals(built.origin()) ? moved.origin() : pos);
		ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(specials));
		if (ref.data().extra.school.filter(built.origin()::equals).isPresent()) ref.data().extra.school = java.util.Optional.of(moved.origin());
		if (ref.data().extra.lager.filter(built.origin()::equals).isPresent()) ref.data().extra.lager = java.util.Optional.of(moved.origin());
		ref.data().chest = ref.data().chest.map(pos -> moves.getOrDefault(pos, pos));
		for (int i = 0; i < ref.data().extra.lagerMore.size(); i++) if (ref.data().extra.lagerMore.get(i).equals(built.origin())) ref.data().extra.lagerMore.set(i, moved.origin());
		for (int i = 0; i < ref.data().extra.lagerChests.size(); i++) {
			BlockPos oldChest = ref.data().extra.lagerChests.get(i);
			ref.data().extra.lagerChests.set(i, moves.getOrDefault(oldChest, oldChest));
		}
		for (Villager resident : Villages.residents(level, ref, 96, v -> true)) {
			moveMemory(level, resident, MemoryModuleType.HOME, moves);
			moveMemory(level, resident, MemoryModuleType.JOB_SITE, moves);
			var memory = resident.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.homeBed = movePosition(memory.homeBed, moves);
			memory.homeDoor = movePosition(memory.homeDoor, moves);
			memory.homeChest = movePosition(memory.homeChest, moves);
			memory.signPos = movePosition(memory.signPos, moves);
		}
		ref.dirty();
		notifyNeighbours(level, before.keySet());
		return "Gebäude versetzt; Blockinventare und Registrierung wurden übernommen.";
	}

	private static void moveMemory(ServerLevel level, Villager resident, MemoryModuleType<GlobalPos> type, Map<BlockPos, BlockPos> moves) {
		resident.getBrain().getMemory(type).filter(p -> p.dimension() == level.dimension() && moves.containsKey(p.pos()))
			.ifPresent(p -> resident.getBrain().setMemory(type, GlobalPos.of(level.dimension(), moves.get(p.pos()))));
	}
	private static VillageData.PlanBounds boundsFor(Iterable<BlockPos> positions) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : positions) {
			minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
			minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
		}
		return new VillageData.PlanBounds(minX - 3, maxX + 3, minZ - 3, maxZ + 3);
	}
	private static Optional<BlockPos> movePosition(Optional<BlockPos> position, Map<BlockPos, BlockPos> moves) {
		return position.map(pos -> moves.getOrDefault(pos, pos));
	}
	private static String coordinates(BlockPos pos) {
		return pos.getX() + " / " + pos.getY() + " / " + pos.getZ();
	}
	private static boolean naturalGround(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.GRASS_BLOCKS) || state.is(BlockTags.DIRT)
			|| state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
			|| state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK);
	}
	private static boolean put(ServerLevel level, BlockPos pos, BlockState state) {
		return level.getBlockState(pos).equals(state) || level.setBlock(pos, state, MOVE_FLAGS);
	}
	private static Snapshot snapshot(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		List<ItemStack> inventory = new ArrayList<>();
		if (be instanceof Container container) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) inventory.add(container.getItem(slot).copy());
		}
		return new Snapshot(pos.immutable(), level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess()),
			be == null ? null : be.getAttached(VillagerPlus.OWNER), List.copyOf(inventory));
	}
	private static void clear(ServerLevel level, Iterable<BlockPos> positions) {
		for (BlockPos pos : positions) level.removeBlockEntity(pos);
		for (BlockPos pos : positions) if (!put(level, pos, Blocks.AIR.defaultBlockState())) throw new IllegalStateException("Cannot clear relocation cell " + pos);
	}
	private static void write(ServerLevel level, BlockPos pos, Snapshot s, Placement placement) {
		if (!placement.set(pos, s.state())) throw new IllegalStateException("Cannot write relocation cell " + pos);
		if (s.blockEntity() != null) {
			BlockEntity be = BlockEntity.loadStatic(pos, s.state(), s.blockEntity().copy(), level.registryAccess());
			if (be == null) throw new IllegalStateException("Cannot restore relocation block entity " + pos);
			if (s.owner() != null) be.setAttached(VillagerPlus.OWNER, s.owner());
			level.setBlockEntity(be);
			if (be instanceof Container container) {
				for (int slot = 0; slot < Math.min(container.getContainerSize(), s.inventory().size()); slot++) {
					container.setItem(slot, s.inventory().get(slot).copy());
				}
			}
			be.setChanged();
		}
	}
	private static void restore(ServerLevel level, Map<BlockPos, Snapshot> snapshots) {
		clear(level, snapshots.keySet());
		for (Snapshot s : snapshots.values().stream().sorted(Comparator.comparingInt(s -> s.pos().getY())).toList()) {
			write(level, s.pos(), s, (pos, state) -> put(level, pos, state));
		}
		notifyNeighbours(level, snapshots.keySet());
	}
	private static void notifyNeighbours(ServerLevel level, Iterable<BlockPos> positions) {
		for (BlockPos pos : positions) level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
	}
}



