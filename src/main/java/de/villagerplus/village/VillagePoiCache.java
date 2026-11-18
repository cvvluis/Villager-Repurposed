package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.world.LoadedPoi;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;

/** Deduped HOME/MEETING POI counts per village (~100 ticks). */
public final class VillagePoiCache {
	private record Key(ResourceKey<Level> dimension, long bell) { }
	private record Entry(long homes, long freeHomes, long until) { }

	private static final long TTL = 100L;
	private static final Map<Key, Entry> CACHE = new HashMap<>();
	private static long lastBell;
	private static Entry lastEntry;

	private VillagePoiCache() { }

	public static long homeCount(ServerLevel level, BlockPos bell) {
		return counts(level, bell).homes();
	}

	public static long freeHomeCount(ServerLevel level, BlockPos bell) {
		return counts(level, bell).freeHomes();
	}

	public static Entry counts(ServerLevel level, BlockPos bell) {
		long now = level.getGameTime();
		long bellKey = bell.asLong();
		if (lastBell == bellKey && lastEntry != null && now < lastEntry.until()) {
			return lastEntry;
		}
		Key key = new Key(level.dimension(), bellKey);
		Entry cached = CACHE.get(key);
		if (cached != null && now < cached.until()) {
			lastBell = bellKey;
			lastEntry = cached;
			return cached;
		}
		int radius = Config.VILLAGE_RADIUS;
		long homes = LoadedPoi.countInRange(level, h -> h.is(PoiTypes.HOME), bell, radius, PoiManager.Occupancy.ANY);
		long free = LoadedPoi.countInRange(level, h -> h.is(PoiTypes.HOME), bell, radius, PoiManager.Occupancy.HAS_SPACE);
		Entry entry = new Entry(homes, free, now + TTL);
		CACHE.put(key, entry);
		lastBell = bellKey;
		lastEntry = entry;
		return entry;
	}

	static void clear() {
		CACHE.clear();
		lastEntry = null;
	}

	static void forgetDimension(ResourceKey<Level> dimension) {
		CACHE.keySet().removeIf(key -> key.dimension() == dimension);
		lastEntry = null;
	}
}