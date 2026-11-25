package de.villagerplus.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;

/** POI reads that never synchronously load chunks from disk. */
public final class LoadedPoi {
	private LoadedPoi() { }

	public static Stream<PoiRecord> inRange(ServerLevel level, Predicate<Holder<PoiType>> type, BlockPos origin, int radius,
		PoiManager.Occupancy occupancy) {
		return records(level, type, origin, radius, occupancy).stream();
	}

	public static long countInRange(ServerLevel level, Predicate<Holder<PoiType>> type, BlockPos origin, int radius,
		PoiManager.Occupancy occupancy) {
		return records(level, type, origin, radius, occupancy).size();
	}

	public static List<BlockPos> positionsInRange(ServerLevel level, Predicate<Holder<PoiType>> type, BlockPos origin,
		int radius, PoiManager.Occupancy occupancy) {
		return records(level, type, origin, radius, occupancy).stream().map(r -> r.getPos().immutable()).toList();
	}

	private static List<PoiRecord> records(ServerLevel level, Predicate<Holder<PoiType>> type, BlockPos origin, int radius,
		PoiManager.Occupancy occupancy) {
		if (level == null || origin == null || radius < 0) return List.of();
		PoiManager manager = level.getPoiManager();
		int minChunkX = SectionPos.blockToSectionCoord(origin.getX() - radius);
		int maxChunkX = SectionPos.blockToSectionCoord(origin.getX() + radius);
		int minChunkZ = SectionPos.blockToSectionCoord(origin.getZ() - radius);
		int maxChunkZ = SectionPos.blockToSectionCoord(origin.getZ() + radius);
		long radiusSquared = (long) radius * radius;
		List<PoiRecord> out = new ArrayList<>();
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) continue;
				try (Stream<PoiRecord> stream = manager.getInChunk(type, new ChunkPos(chunkX, chunkZ), occupancy)) {
					stream.filter(record -> record.getPos().distSqr(origin) <= radiusSquared).forEach(out::add);
				}
			}
		}
		return out;
	}

	private static int scanDepth;
	private static final java.util.Map<Long, java.util.Set<Long>> CHUNK_POIS = new java.util.HashMap<>();

	/**
	 * Opens a read-only scan scope. Inside it {@link #hasAt} answers from per-chunk POI position sets built once for the
	 * scope; the world must not change POIs until {@link #endScan()}. Scopes nest; the cache is dropped with the last one.
	 */
	public static void beginScan() {
		if (scanDepth++ == 0) CHUNK_POIS.clear();
	}

	public static void endScan() {
		if (scanDepth > 0 && --scanDepth == 0) CHUNK_POIS.clear();
	}

	/**
	 * Whether any POI record (any type, any occupancy) sits exactly at {@code pos}. Block-by-block lot scans used to
	 * issue one POI-manager range query per block; inside a scan scope the POI positions of a loaded chunk are
	 * collected once and reused. Outside a scope, or in a chunk that is not loaded, the exact per-position query runs.
	 */
	public static boolean hasAt(ServerLevel level, BlockPos pos) {
		int chunkX = SectionPos.blockToSectionCoord(pos.getX());
		int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
		if (scanDepth == 0 || level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
			return level.getPoiManager().getInRange(poi -> true, pos, 0, PoiManager.Occupancy.ANY).findAny().isPresent();
		}
		java.util.Set<Long> positions = CHUNK_POIS.computeIfAbsent(ChunkPos.pack(chunkX, chunkZ), key -> {
			java.util.Set<Long> found = new java.util.HashSet<>();
			try (Stream<PoiRecord> stream = level.getPoiManager().getInChunk(poi -> true, new ChunkPos(chunkX, chunkZ),
				PoiManager.Occupancy.ANY)) {
				stream.forEach(record -> found.add(record.getPos().asLong()));
			}
			return found;
		});
		return positions.contains(pos.asLong());
	}
}