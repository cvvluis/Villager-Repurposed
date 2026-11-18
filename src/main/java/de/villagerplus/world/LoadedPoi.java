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
}