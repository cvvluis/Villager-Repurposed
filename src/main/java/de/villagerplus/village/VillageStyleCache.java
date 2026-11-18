package de.villagerplus.village;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;

/** Caches {@link VillageStyle#detect} per bell for ~1200 ticks. */
public final class VillageStyleCache {
	private record Key(ResourceKey<Level> dimension, long bell) { }
	private record Entry(VillageStyle style, long until) { }

	private static final long TTL = 1200L;
	private static final Map<Key, Entry> CACHE = new HashMap<>();

	private VillageStyleCache() { }

	public static VillageStyle detect(ServerLevel level, BlockPos bell, Villager hint) {
		long now = level.getGameTime();
		Key key = new Key(level.dimension(), bell.asLong());
		Entry entry = CACHE.get(key);
		if (entry != null && now < entry.until()) return entry.style();
		VillageStyle style = VillageStyle.detect(level, bell, hint);
		CACHE.put(key, new Entry(style, now + TTL));
		return style;
	}

	public static void invalidate(ServerLevel level, BlockPos bell) {
		CACHE.remove(new Key(level.dimension(), bell.asLong()));
	}

	static void clear() {
		CACHE.clear();
	}

	static void forgetDimension(ResourceKey<Level> dimension) {
		CACHE.keySet().removeIf(key -> key.dimension() == dimension);
	}
}