package de.villagerplus.job;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Remembers that an expensive, side-effect-free recipe scan found nothing for a given input state. While the container
 * contents (signature) stay the same and the TTL has not run out, the identical scan is skipped; any change to the
 * contents or the elapsed TTL makes it run again, so results are unchanged apart from a bounded delay on external
 * changes (recipe reload, plan edits). Only negative results are cached; a successful scan is never suppressed.
 */
final class ScanThrottle {
	private record Entry(long signature, long validUntil) { }

	private static final int MAX_ENTRIES = 512;
	private final long ttl;
	private final Map<Long, Entry> entries = new HashMap<>();

	ScanThrottle(final long ttl) {
		this.ttl = ttl;
	}

	/** true = the same scan already came back empty for this exact state and is still fresh. */
	boolean known(final long key, final long signature, final long now) {
		Entry e = entries.get(key);
		return e != null && e.signature == signature && now < e.validUntil;
	}

	void remember(final long key, final long signature, final long now) {
		if (entries.size() >= MAX_ENTRIES) entries.values().removeIf(e -> e.validUntil <= now);
		if (entries.size() >= MAX_ENTRIES) entries.clear();
		entries.put(key, new Entry(signature, now + ttl));
	}

	static long key(final long villageBell, final Object target) {
		return villageBell * 31L + target.hashCode();
	}

	/** Order-sensitive hash of every slot's item, count and components: one cheap pass, no recipe work. */
	static long signature(final long seed, final Container... containers) {
		long h = seed * 1000003L + 17L;
		for (Container container : containers) {
			int size = container.getContainerSize();
			h = h * 31L + size;
			for (int slot = 0; slot < size; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty()) { h = h * 31L; continue; }
				h = h * 31L + System.identityHashCode(stack.getItem());
				h = h * 31L + stack.getCount();
				h = h * 31L + stack.getComponents().hashCode();
			}
		}
		return h;
	}
}
