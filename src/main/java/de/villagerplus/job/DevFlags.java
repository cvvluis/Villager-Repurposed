package de.villagerplus.job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Developer test switches: marker files next to the server properties (for example {@code vp-noquarry.flag}).
 * The result is cached for a few seconds so the file system is not touched on every call.
 */
final class DevFlags {
	private DevFlags() { }

	private static final long CACHE_MILLIS = 5_000L;
	private static final Map<String, long[]> CACHE = new HashMap<>();

	static synchronized boolean has(String name) {
		long now = System.currentTimeMillis();
		long[] entry = CACHE.get(name);
		if (entry == null || now - entry[0] > CACHE_MILLIS) {
			boolean present = Files.exists(Path.of(name));
			entry = new long[] {now, present ? 1L : 0L};
			CACHE.put(name, entry);
		}
		return entry[1] == 1L;
	}
}
