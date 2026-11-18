package de.villagerplus.village;

import de.villagerplus.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Haelt Doerfer am Leben: Der bekannte Dorf-Footprint um die Glocke wird dauerhaft geladen; aktive Mauer- und Minenkorridore
 * nutzen befristete Arbeits-Tickets. Damit arbeiten, bauen, minen und handeln die Villager auch dann weiter, wenn kein Spieler
 * in der Naehe ist. Abschaltbar ueber {@code Config.KEEP_VILLAGES_LOADED}.
 */
public final class Anchor {
	/** Short-lived work tickets load and simulate the active wall corridor without changing persistent /forceload state. */
	public static final TicketType WALL_WORK = new TicketType(240L,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
	private static final long WORK_TICKET_TIMEOUT = Config.HOUSING_INTERVAL + 40L;
	/** Refresh halfway through the 240-tick lease so a continuously requested chunk never falls out of coverage. */
	static final long WORK_TICKET_REFRESH_INTERVAL = WORK_TICKET_TIMEOUT / 2L;
	/** Mine corridor leases outlive the 200-tick housing refresh by 40 ticks and never persist as /forceload entries. */
	public static final TicketType MINE_WORK = new TicketType(WORK_TICKET_TIMEOUT,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
	/** Expiring ticket that keeps the already loaded centre of the kept village simulated while no player is close. */
	public static final TicketType FOOTPRINT_WORK = new TicketType(WORK_TICKET_TIMEOUT,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
	/** Refreshable lease for a tracked resident loaded outside the village's capped persistent footprint. */
	public static final TicketType VILLAGER_WORK = new TicketType(WORK_TICKET_TIMEOUT,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
	/**
	 * A radius-0 ticket only reaches chunk level 33 (loaded, not entity-ticking), so a lease on the resident's own chunk
	 * would keep it loaded but frozen; radius 2 yields level 31 (entity-ticking) for exactly that chunk.
	 */
	static final int VILLAGER_LEASE_RADIUS = 2;
	private static final int MINE_HORIZONTAL_LIMIT = 56;
	private static final int MINE_LOOKAHEAD = 48;
	private static boolean registered;
	public static void register() {
		if (!registered) {
			Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("villagerplus", "wall_work"), WALL_WORK);
			Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("villagerplus", "mine_work"), MINE_WORK);
			Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("villagerplus", "villager_work"), VILLAGER_WORK);
			Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("villagerplus", "footprint_work"), FOOTPRINT_WORK);
			ServerLifecycleEvents.SERVER_STARTED.register(server -> clearWorldCaches());
			registered = true;
		}
	}

	/** Keep a managed resident's current chunk entity-ticking when the player leaves its village. */
	public static void keepVillagerActive(ServerLevel level, Villager villager) {
		if (!Config.KEEP_VILLAGES_LOADED) return;
		ChunkPos chunk = villager.chunkPosition();
		long now = level.getGameTime();
		WorkTicketKey key = workTicketKey(level, VILLAGER_WORK, chunk);
		boolean existingLease = WORK_TICKET_REFRESHES.hasUnexpiredLease(key, now, WORK_TICKET_TIMEOUT);
		if (!shouldLeaseVillagerChunk(true, level.isPositionEntityTicking(villager.blockPosition()), existingLease)) return;
		// Never let a lease generate terrain: only pin a chunk whose neighbours are already loaded, and never under load.
		if (overloaded(level) || !pinnable(level, chunk)) return;
		addWorkTicket(level, VILLAGER_WORK, chunk, VILLAGER_LEASE_RADIUS, now, key);
	}

	/** Keep a returning miner's corridor loaded even after its mine has been archived as completed. */
	public static void keepMineRouteLoaded(ServerLevel level, Villager villager) {
		if (!Config.KEEP_VILLAGES_LOADED || villager == null) return;
		VillageData.Mine route = de.villagerplus.job.Mining.taggedMine(villager, level);
		if (route == null || !shouldLeaseMineRoute(Config.KEEP_VILLAGES_LOADED,
			villager.getY() < route.entrance.getY() - 0.5)) return;
		long now = level.getGameTime();
		for (ChunkPos chunk : mineWorkChunks(route, level.getMaxY() - level.getMinY())) {
			addWorkTicket(level, MINE_WORK, chunk, 0, now);
		}
	}

	static boolean shouldLeaseVillagerChunk(boolean keepVillagesLoaded, boolean entityTicking, boolean existingLease) {
		return keepVillagesLoaded && (!entityTicking || existingLease);
	}

	static boolean shouldLeaseMineRoute(boolean keepVillagesLoaded, boolean underground) {
		return keepVillagesLoaded && underground;
	}

	private static WorkTicketKey workTicketKey(ServerLevel level, TicketType type, ChunkPos chunk) {
		return new WorkTicketKey(level.dimension(), BuiltInRegistries.TICKET_TYPE.getKey(type), chunk.pack());
	}

	private static void addWorkTicket(ServerLevel level, TicketType type, ChunkPos chunk, int radius, long now) {
		addWorkTicket(level, type, chunk, radius, now, workTicketKey(level, type, chunk));
	}

	private static void addWorkTicket(ServerLevel level, TicketType type, ChunkPos chunk, int radius, long now, WorkTicketKey key) {
		if (!WORK_TICKET_REFRESHES.refreshDue(key, now)) return;
		level.getChunkSource().addTicketWithRadius(type, chunk, radius);
		WORK_TICKET_REFRESHES.recordRefresh(key, now);
	}
	private static final Map<Long, Long> LAST = new HashMap<>();
	private static final Map<AnchorKey, Long> LAST_BY_VILLAGE = new HashMap<>();
	private static final Map<AnchorKey, Long> RING = new HashMap<>();
	private static final Map<AnchorKey, Long> WARNED = new HashMap<>();
	/**
	 * Performance budget: at most this many villages per level are held loaded at once (nearest to a player first).
	 * Every further village is released, including chunks that older versions saved as /forceload entries. Override with
	 * the JVM property -Dvillagerplus.keptVillages=N (0 = never force-load villages).
	 */
	public static final int MAX_KEPT_VILLAGES = Math.max(0, Integer.getInteger("villagerplus.keptVillages", 1));
	/** Hard cap of forced chunks per kept village (older versions forced up to {@link #LEGACY_MAX_FORCED_CHUNKS}). */
	private static final int MAX_FORCED_CHUNKS = 49;
	private static final int LEGACY_MAX_FORCED_CHUNKS = 256;
	private static final int LEGACY_RADIUS_CHUNKS = 3;
	private static final int FOOTPRINT_MARGIN_CHUNKS = 1;
	/** A village competes for the budget only while one of its residents ticked within this many ticks. */
	private static final long SEEN_WINDOW = 1200L;
	/** Villages already kept need to fall clearly behind before they are dropped (avoids load/unload flapping). */
	private static final double KEPT_DISTANCE_BONUS = 0.5625D;
	private static final long RELEASE_INTERVAL = 12000L;
	/** Average tick time above which nothing is pinned any more (the tick budget is 50 ms). */
	private static final long OVERLOAD_TICK_NANOS = 40_000_000L;
	record SeenVillage(BlockPos bell, long seen) { }
	record ForcedChunk(ResourceKey<Level> dimension, long pos) { }
	private static final Map<AnchorKey, SeenVillage> SEEN = new HashMap<>();
	private static final Map<AnchorKey, Set<ForcedChunk>> KEPT = new HashMap<>();
	private static final Map<AnchorKey, Long> RELEASED = new HashMap<>();
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	record AnchorKey(ResourceKey<Level> dimension, long pos) { }
	private static final int MAX_WORK_TICKET_CACHE_ENTRIES = 8192;
	private static final WorkTicketRefreshCache WORK_TICKET_REFRESHES = new WorkTicketRefreshCache(MAX_WORK_TICKET_CACHE_ENTRIES);
	static record WorkTicketKey(ResourceKey<Level> dimension, Identifier ticketType, long chunkPos) { }

	/** Small bounded LRU cache; tick time is per level, and backwards time permits an immediate lease refresh. */
	static final class WorkTicketRefreshCache {
		private final int capacity;
		private final LinkedHashMap<WorkTicketKey, Long> lastRefresh = new LinkedHashMap<>(16, 0.75f, true);

		WorkTicketRefreshCache(int capacity) {
			this.capacity = Math.max(1, capacity);
		}

		boolean refreshDue(WorkTicketKey key, long now) {
			Long last = lastRefresh.get(key);
			return last == null || now < last || now - last >= WORK_TICKET_REFRESH_INTERVAL;
		}

		boolean hasUnexpiredLease(WorkTicketKey key, long now, long timeout) {
			Long last = lastRefresh.get(key);
			return last != null && (now < last || now - last < timeout);
		}

		void recordRefresh(WorkTicketKey key, long now) {
			lastRefresh.put(key, now);
			if (lastRefresh.size() <= capacity) return;
			Iterator<WorkTicketKey> oldest = lastRefresh.keySet().iterator();
			if (oldest.hasNext()) {
				oldest.next();
				oldest.remove();
			}
		}

		void clear() {
			lastRefresh.clear();
		}

		int size() {
			return lastRefresh.size();
		}
	}

	private Anchor() {
	}

	public static void keepLoaded(ServerLevel level, VillageRef ref, long now) {
		if (!Config.KEEP_VILLAGES_LOADED) {
			return;
		}
		AnchorKey key = new AnchorKey(level.dimension(), ref.bell().asLong());
		Long last = LAST_BY_VILLAGE.get(key);
		boolean clockRewound = clockRewound(last, now);
		if (!refreshDue(last, now)) return;
		if (clockRewound) {
			// Static caches can outlive a single-player world switch. Forget the previous
			// world's timestamps so mine leases and the permanent footprint refresh now.
			RING.remove(key);
			WARNED.remove(key);
		}
		LAST_BY_VILLAGE.put(key, now);
		SEEN.put(key, new SeenVillage(ref.bell(), now));
		boolean inBudget = withinBudget(level, key, ref.bell(), now);
		if (!inBudget || attended(level, ref.bell()) || overloaded(level)) {
			// A village near a player is simulated by the game itself; a village outside the budget or under server load is
			// simply not pinned any more (its tickets expire). Chunks that older versions saved as /forceload are freed.
			if (!inBudget || attended(level, ref.bell())) release(level, ref, key, now);
			else KEPT.remove(key);
			return;
		}
		RELEASED.remove(key);
		keepWallWorkLoaded(level, ref, ref.bell());
		pinFootprint(level, ref, key, now);
		// Refresh active mine leases with the village. An underground route owner renews its
		// own corridor lease while returning, even after the mine has been archived.
		ref.data().mine.filter(m -> !m.done).ifPresent(m -> {
			for (ChunkPos chunk : mineWorkChunks(m, level.getMaxY() - level.getMinY())) {
				addWorkTicket(level, MINE_WORK, chunk, 0, now);
			}
		});
	}

	private static void clearWorldCaches() {
		LAST.clear();
		LAST_BY_VILLAGE.clear();
		RING.clear();
		WARNED.clear();
		SEEN.clear();
		KEPT.clear();
		RELEASED.clear();
		WORK_TICKET_REFRESHES.clear();
	}

	static boolean clockRewound(Long last, long now) {
		return last != null && now < last;
	}

	/** True when the village refresh interval elapsed or the server clock moved to another world/save. */
	static boolean refreshDue(Long last, long now) {
		return last == null || clockRewound(last, now) || now - last >= 100L;
	}

	/** Bounded, chunk-aligned lease path for the saved mine corridor; completed or malformed routes request nothing. */
	static Set<ChunkPos> mineWorkChunks(VillageData.Mine mine, int worldHeight) {
		if (mine == null || mine.done || mine.index < 0 || mine.dir < 0 || mine.dir > 3) return Set.of();
		long maximumIndex = (long) Math.max(0, worldHeight) + MINE_HORIZONTAL_LIMIT;
		if (mine.index > maximumIndex) return Set.of();
		long furthest = (long) mine.index + MINE_LOOKAHEAD;
		Direction direction = Direction.from2DDataValue(mine.dir);
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		for (long section = 0; section <= furthest; section += 8) {
			BlockPos pos = mine.entrance.relative(direction, (int) section);
			chunks.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
		}
		// The 8-block sampling interval can end just before a chunk boundary when the saved
		// cursor is not divisible by eight; lease that exact lookahead endpoint as well.
		if (furthest % 8 != 0) {
			BlockPos end = mine.entrance.relative(direction, (int) furthest);
			chunks.add(new ChunkPos(end.getX() >> 4, end.getZ() >> 4));
		}
		return Set.copyOf(chunks);
	}

	/** Refresh the current wall segment during work and routine phases, including old-wall removal after expansion. */
	public static void keepWallWorkLoaded(ServerLevel level, VillageRef ref, BlockPos worker) {
		if (!Config.KEEP_VILLAGES_LOADED || !de.villagerplus.build.Blueprints.WALL.equals(ref.data().planType)) return;
		int radius = ref.data().planVariant & 0xFF;
		int cursor = ref.data().planStep;
		if (radius <= 0 || radius > Development.MAX_WALL_RADIUS) return;
		if (cursor >= 8 * radius) {
			int old = (ref.data().planVariant >>> 8) & 0xFF;
			cursor -= 8 * radius;
			if (old <= 0 || old > Development.MAX_WALL_RADIUS || cursor >= 8 * old) return;
			radius = old;
		}
		for (ChunkPos chunk : wallWorkChunks(ref.bell(), worker, radius, cursor)) {
			level.getChunkSource().addTicketWithRadius(WALL_WORK, chunk, 2);
		}
	}

	/** Bounded path from the worker to the next segment; the expiring ticket radius supplies a two-chunk margin. */
	public static java.util.Set<ChunkPos> wallWorkChunks(BlockPos bell, BlockPos worker, int radius, int cursor) {
		if (radius <= 0 || radius > Development.MAX_WALL_RADIUS || cursor < 0 || cursor >= 8 * radius) return java.util.Set.of();
		java.util.Set<ChunkPos> chunks = new java.util.LinkedHashSet<>();
		int[] offset = de.villagerplus.build.Wall.ringOffset(radius, cursor);
		Direction inward = de.villagerplus.build.Wall.inward(radius, cursor);
		int targetX = (bell.getX() + offset[0] + inward.getStepX() * 11) >> 4;
		int targetZ = (bell.getZ() + offset[1] + inward.getStepZ() * 11) >> 4;
		int fromX = worker.getX() >> 4, fromZ = worker.getZ() >> 4;
		int steps = Math.max(Math.abs(targetX - fromX), Math.abs(targetZ - fromZ));
		// A legal village wall is at most 192 blocks from its bell. Do not accept unbounded remote-worker corridors.
		if (steps <= 48) {
			for (int i = 0; i <= steps; i++) {
				int x = steps == 0 ? targetX : fromX + (int)Math.round((double)(targetX - fromX) * i / steps);
				int z = steps == 0 ? targetZ : fromZ + (int)Math.round((double)(targetZ - fromZ) * i / steps);
				chunks.add(new ChunkPos(x, z));
			}
		}
		for (int i = cursor; i < Math.min(cursor + 4, 8 * radius); i++) {
			int[] next = de.villagerplus.build.Wall.ringOffset(radius, i);
			chunks.add(new ChunkPos((bell.getX() + next[0]) >> 4, (bell.getZ() + next[1]) >> 4));
		}
		return java.util.Set.copyOf(chunks);
	}

	/** True while this village is inside the load budget, so its residents may keep their chunk simulated. */
	public static boolean isKept(ServerLevel level, VillageRef ref) {
		return Config.KEEP_VILLAGES_LOADED && KEPT.containsKey(new AnchorKey(level.dimension(), ref.bell().asLong()));
	}

	private static double nearestPlayerDistSqr(ServerLevel level, BlockPos bell) {
		double best = Double.MAX_VALUE;
		for (var player : level.players()) {
			best = Math.min(best, player.distanceToSqr(bell.getX() + 0.5, bell.getY(), bell.getZ() + 0.5));
		}
		return best;
	}

	/** Rank among the currently active villages of this level by distance to the nearest player. */
	static boolean withinBudget(ServerLevel level, AnchorKey key, BlockPos bell, long now) {
		if (MAX_KEPT_VILLAGES <= 0) return false;
		SEEN.values().removeIf(v -> now - v.seen() > SEEN_WINDOW);
		double mine = nearestPlayerDistSqr(level, bell) * (KEPT.containsKey(key) ? KEPT_DISTANCE_BONUS : 1.0D);
		int closer = 0;
		for (Map.Entry<AnchorKey, SeenVillage> e : SEEN.entrySet()) {
			AnchorKey other = e.getKey();
			if (other.equals(key) || !other.dimension().equals(key.dimension())) continue;
			double theirs = nearestPlayerDistSqr(level, e.getValue().bell()) * (KEPT.containsKey(other) ? KEPT_DISTANCE_BONUS : 1.0D);
			if (theirs < mine || (theirs == mine && other.pos() < key.pos())) closer++;
		}
		return closer < MAX_KEPT_VILLAGES;
	}

	/** Chunks of the village footprint, nearest to the bell first (deterministic order). */
	private static ArrayList<int[]> footprint(ServerLevel level, VillageRef ref, int radius) {
		BoundingBox box = Development.bounds(level, ref);
		int bellChunkX = ref.bell().getX() >> 4;
		int bellChunkZ = ref.bell().getZ() >> 4;
		int minX = Math.min(box.minX() >> 4, bellChunkX - radius) - FOOTPRINT_MARGIN_CHUNKS;
		int maxX = Math.max(box.maxX() >> 4, bellChunkX + radius) + FOOTPRINT_MARGIN_CHUNKS;
		int minZ = Math.min(box.minZ() >> 4, bellChunkZ - radius) - FOOTPRINT_MARGIN_CHUNKS;
		int maxZ = Math.max(box.maxZ() >> 4, bellChunkZ + radius) + FOOTPRINT_MARGIN_CHUNKS;
		var chunks = new ArrayList<int[]>();
		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) chunks.add(new int[] {cx, cz});
		}
		chunks.sort(Comparator.<int[]>comparingLong(c -> (long)(c[0] - bellChunkX) * (c[0] - bellChunkX)
			+ (long)(c[1] - bellChunkZ) * (c[1] - bellChunkZ)).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
		return chunks;
	}

	/** True when the server is already struggling: pinning more chunks would only make it worse. */
	static boolean overloaded(ServerLevel level) {
		return level.getServer().getAverageTickTimeNanos() > OVERLOAD_TICK_NANOS;
	}

	/** True when a player is close enough that the village is simulated by the game itself (simulation distance minus 3 chunks). */
	static boolean attended(ServerLevel level, BlockPos bell) {
		int blocks = Math.max(0, level.getServer().getPlayerList().getSimulationDistance() - 3) * 16;
		return nearestPlayerDistSqr(level, bell) < (double) blocks * blocks;
	}

	/** Only a chunk with all eight neighbours already loaded may be pinned: pinning must never trigger world generation. */
	static boolean pinnable(ServerLevel level, ChunkPos chunk) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (level.getChunkSource().getChunkNow(chunk.x() + dx, chunk.z() + dz) == null) return false;
			}
		}
		return true;
	}

	private static void pinFootprint(ServerLevel level, VillageRef ref, AnchorKey key, long now) {
		Set<ForcedChunk> kept = KEPT.computeIfAbsent(key, k -> new LinkedHashSet<>());
		Long ringDone = RING.get(key);
		if (ringDone == null || now - ringDone > 12000L) {
			RING.put(key, now);
			ArrayList<int[]> chunks = footprint(level, ref, Config.ANCHOR_RADIUS_CHUNKS);
			kept.clear();
			for (int i = 0; i < Math.min(MAX_FORCED_CHUNKS, chunks.size()); i++) {
				kept.add(new ForcedChunk(level.dimension(), ChunkPos.pack(chunks.get(i)[0], chunks.get(i)[1])));
			}
			if (chunks.size() > MAX_FORCED_CHUNKS && now >= WARNED.getOrDefault(key, 0L)) {
				WARNED.put(key, now + 12000L);
				LOGGER.info("Village footprint at {} spans {} chunks; pinning at most the nearest {} (only chunks that are already loaded).",
					ref.bell().toShortString(), chunks.size(), MAX_FORCED_CHUNKS);
			}
		}
		for (ForcedChunk c : kept) {
			ChunkPos chunk = new ChunkPos(ChunkPos.getX(c.pos()), ChunkPos.getZ(c.pos()));
			if (pinnable(level, chunk)) addWorkTicket(level, FOOTPRINT_WORK, chunk, VILLAGER_LEASE_RADIUS, now);
		}
	}

	/**
	 * Gives a village outside the budget back to normal chunk handling. Also frees chunks that earlier versions saved as
	 * /forceload entries around the bell (radius 3, up to 256 chunks); chunks shared with a kept village stay loaded.
	 */
	private static void release(ServerLevel level, VillageRef ref, AnchorKey key, long now) {
		Set<ForcedChunk> mine = KEPT.remove(key);
		RING.remove(key);
		Long last = RELEASED.get(key);
		if (mine == null && last != null && now >= last && now - last < RELEASE_INTERVAL) return;
		RELEASED.put(key, now);
		Set<ForcedChunk> others = new java.util.HashSet<>();
		for (Set<ForcedChunk> kept : KEPT.values()) others.addAll(kept);
		ArrayList<int[]> candidates = footprint(level, ref, LEGACY_RADIUS_CHUNKS);
		int released = releaseChunks(level, candidates.subList(0, Math.min(LEGACY_MAX_FORCED_CHUNKS, candidates.size())), mine, others);
		if (released > 0) {
			LOGGER.info("Village at {} is outside the load budget ({} kept villages); released {} forced chunks.",
				ref.bell().toShortString(), MAX_KEPT_VILLAGES, released);
		}
	}

	/** Unforces the candidate and own chunks except those another kept village still needs; returns how many were freed. */
	static int releaseChunks(ServerLevel level, java.util.List<int[]> candidates, Set<ForcedChunk> mine, Set<ForcedChunk> others) {
		int released = 0;
		for (int[] c : candidates) {
			if (others.contains(new ForcedChunk(level.dimension(), ChunkPos.pack(c[0], c[1])))) continue;
			if (level.setChunkForced(c[0], c[1], false)) released++;
		}
		if (mine != null) {
			for (ForcedChunk c : mine) {
				if (!others.contains(c) && level.setChunkForced(ChunkPos.getX(c.pos()), ChunkPos.getZ(c.pos()), false)) released++;
			}
		}
		return released;
	}

	/** Test hook: registers a village as recently active so the budget ranking sees it. */
	static AnchorKey seenForTest(ServerLevel level, BlockPos bell, long now) {
		AnchorKey key = new AnchorKey(level.dimension(), bell.asLong());
		SEEN.put(key, new SeenVillage(bell, now));
		return key;
	}

	/** Test hook: treats the village as inside the load budget so its residents may take the temporary chunk lease. */
	static void markKeptForTest(ServerLevel level, VillageRef ref) {
		KEPT.putIfAbsent(new AnchorKey(level.dimension(), ref.bell().asLong()), new LinkedHashSet<>());
	}

	static void resetBudgetForTest() {
		SEEN.clear();
		KEPT.clear();
	}

	static ForcedChunk forcedChunkForTest(ServerLevel level, int x, int z) {
		return new ForcedChunk(level.dimension(), ChunkPos.pack(x, z));
	}
}
