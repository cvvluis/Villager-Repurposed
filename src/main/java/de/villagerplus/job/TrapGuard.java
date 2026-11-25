package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import de.villagerplus.world.LoadedColumns;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * One-way drops (a pit or terrace three blocks below the rim, a deck inside a hole): vanilla navigation happily walks DOWN a three-block
 * drop onto a floor it can never climb out of again. Natural runs (seed 4242, pit with a slab deck at x 103..111, z -396..-388): about
 * 13 % of all working-hour villager samples stood on that deck, for 800 to 23,000 ticks per episode. They entered it to chat with, take a
 * break next to, or walk to a home or bed beside a villager already inside; a stuck resident does not request any route while it chats,
 * eats or "has nothing to do", so the failed-route strand watch never saw it.
 *
 * <p>Two guards, both using the bounded block model {@link Sites#walksBackTo} (up one, down three, doors passable) that the strand watch
 * already trusts: (1) mod-chosen stands (break places, chat meeting spots) must walk back to the village street, so nobody is sent into a
 * pit on purpose; (2) a resident that stays where it cannot walk back (model AND vanilla navigation agree) for
 * {@link #TRAP_GRACE_TICKS} is handed to the existing strand rescue whatever it is doing.
 */
final class TrapGuard {
	private TrapGuard() { }

	/** Ticks between two model checks of one resident. */
	static final long TRAP_CHECK_TICKS = 200L;
	/** Ticks a resident must keep failing the walk-back check (two checks at least this far apart) before it counts as trapped. */
	static final long TRAP_GRACE_TICKS = 400L;
	private static final long STAND_CACHE_TICKS = 1_200L;
	private static final int NEAR_STREET_RADIUS_SQ = 36;
	private static final int LOW_SPOT_DROP = 2;
	/** A partial vanilla path with at most this many nodes never left the spot (measured on the deck: 1 to 8 nodes). */
	static final int TRAP_MAX_ROUTE_NODES = 12;
	private static long navCheckTick = -1L;

	/** Dev-check switch: the route-watch checks of {@link StrandRescueChecks} switch the idle watch off to test one mechanism at a time. */
	static boolean enabled = true;

	private record StandResult(boolean ok, long until) { }
	private static final Map<Long, StandResult> STANDS = new HashMap<>();
	private static final Map<UUID, Long> TRAP_SINCE = new HashMap<>();
	private static final Map<UUID, Long> TRAP_NEXT = new HashMap<>();

	/**
	 * True unless the block model proves that nobody standing on {@code stand} could walk back to the street of the nearest village. Unknown
	 * (no village, no street, unloaded chunks, stand within a few blocks of the street) counts as fine: this must only ever remove stands
	 * that are provably one-way, never shrink the village's usable ground on a guess.
	 */
	static boolean standWalksBack(ServerLevel level, BlockPos stand) {
		VillageRef ref = Villages.villageNear(level, stand, Config.VILLAGE_RADIUS + 32);
		if (ref == null) return true;
		BlockPos arrival = de.villagerplus.build.Builder.villageReturn(level, ref.bell());
		if (arrival == null || nearStreet(stand, arrival)) return true;
		long now = level.getGameTime();
		StandResult cached = STANDS.get(stand.asLong());
		if (cached != null && now < cached.until()) return cached.ok();
		if (!LoadedColumns.ready(level, stand) || !LoadedColumns.ready(level, arrival)) return true;
		boolean ok = Sites.walksBackTo(level, stand, arrival);
		if (STANDS.size() > 2048) STANDS.clear();
		STANDS.put(stand.asLong(), new StandResult(ok, now + STAND_CACHE_TICKS));
		return ok;
	}

	private static boolean nearStreet(BlockPos pos, BlockPos arrival) {
		int dx = pos.getX() - arrival.getX(), dz = pos.getZ() - arrival.getZ();
		return dx * dx + dz * dz <= NEAR_STREET_RADIUS_SQ && Math.abs(pos.getY() - arrival.getY()) <= 3;
	}

	/**
	 * True when the resident has failed to walk back to {@code arrival} (block model) on two checks at least {@link #TRAP_GRACE_TICKS}
	 * apart without a successful one in between and vanilla navigation confirms there is no complete path. Cheap: one bounded search
	 * per resident every {@link #TRAP_CHECK_TICKS} ticks, and a trapped resident's search ends after its small enclosed area.
	 */
	static boolean idleTrapped(Ctx c, BlockPos arrival) {
		if (!enabled) return false;
		UUID id = c.v().getUUID();
		long now = c.now();
		Long next = TRAP_NEXT.get(id);
		if (next != null && now < next) return false;
		if (TRAP_NEXT.size() > 512) { TRAP_NEXT.clear(); TRAP_SINCE.clear(); }
		TRAP_NEXT.put(id, now + TRAP_CHECK_TICKS);
		BlockPos at = c.v().blockPosition();
		if (nearStreet(at, arrival) || !LoadedColumns.ready(c.level(), at) || !LoadedColumns.ready(c.level(), arrival)) {
			TRAP_SINCE.remove(id);
			return false;
		}
		// The block model is too generous on slab decks (it climbs from a deck slab onto the rim), real navigation is the authority: trapped
		// means vanilla finds no complete way to the street and its best partial path never leaves the spot's neighbourhood. The model only
		// widens the candidates (a spot it already rejects, or one well below the street where one-way drops lead).
		boolean candidate = !Sites.walksBackTo(c.level(), at, arrival) || at.getY() <= arrival.getY() - LOW_SPOT_DROP;
		if (!candidate) {
			TRAP_SINCE.remove(id);
			return false;
		}
		if (navCheckTick == now) {
			TRAP_NEXT.put(id, now + 1L);
			return false;
		}
		navCheckTick = now;
		var route = de.villagerplus.build.Builder.workPath(c, arrival);
		boolean stuck = route == null || (!route.canReach() && route.getNodeCount() <= TRAP_MAX_ROUTE_NODES);
		if (!stuck) {
			TRAP_SINCE.remove(id);
			return false;
		}
		Long since = TRAP_SINCE.putIfAbsent(id, now);
		if (since == null || now - since < TRAP_GRACE_TICKS) return false;
		return true;
	}

	/** Forget the resident (after a rescue or when it leaves the watch). */
	static void forget(UUID id) {
		TRAP_SINCE.remove(id);
		TRAP_NEXT.remove(id);
	}

	/** Test hook: ticks the resident has been failing the walk-back check, or 0. */
	static long trappedFor(UUID id, long now) {
		Long since = TRAP_SINCE.get(id);
		return since == null ? 0L : Math.max(0L, now - since);
	}

	static void clear() {
		STANDS.clear();
		TRAP_SINCE.clear();
		TRAP_NEXT.clear();
	}
}
