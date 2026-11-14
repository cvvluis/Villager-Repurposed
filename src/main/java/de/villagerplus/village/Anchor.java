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
	private static final int MAX_FORCED_CHUNKS = 256;
	private static final int FOOTPRINT_MARGIN_CHUNKS = 1;
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	private record AnchorKey(ResourceKey<Level> dimension, long pos) { }
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
		keepWallWorkLoaded(level, ref, ref.bell());
		// Generated CTOV villages can extend far beyond a bell-centered fixed radius.
		// Load the known structure/POI/built footprint plus a one-chunk pathing margin,
		// while keeping a deterministic hard cap on forced chunks per village.
		Long ringDone = RING.get(key);
		if (ringDone == null || now - ringDone > 12000L) {
			RING.put(key, now);
			forceFootprint(level, ref, key, now);
		}
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

	private static void forceFootprint(ServerLevel level, VillageRef ref, AnchorKey key, long now) {
		BoundingBox box = Development.bounds(level, ref);
		int bellChunkX = ref.bell().getX() >> 4;
		int bellChunkZ = ref.bell().getZ() >> 4;
		int minX = Math.min(box.minX() >> 4, bellChunkX - Config.ANCHOR_RADIUS_CHUNKS) - FOOTPRINT_MARGIN_CHUNKS;
		int maxX = Math.max(box.maxX() >> 4, bellChunkX + Config.ANCHOR_RADIUS_CHUNKS) + FOOTPRINT_MARGIN_CHUNKS;
		int minZ = Math.min(box.minZ() >> 4, bellChunkZ - Config.ANCHOR_RADIUS_CHUNKS) - FOOTPRINT_MARGIN_CHUNKS;
		int maxZ = Math.max(box.maxZ() >> 4, bellChunkZ + Config.ANCHOR_RADIUS_CHUNKS) + FOOTPRINT_MARGIN_CHUNKS;
		var chunks = new ArrayList<int[]>();
		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) chunks.add(new int[] {cx, cz});
		}
		chunks.sort(Comparator.<int[]>comparingLong(c -> (long)(c[0] - bellChunkX) * (c[0] - bellChunkX)
			+ (long)(c[1] - bellChunkZ) * (c[1] - bellChunkZ)).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
		int count = Math.min(MAX_FORCED_CHUNKS, chunks.size());
		for (int i = 0; i < count; i++) level.setChunkForced(chunks.get(i)[0], chunks.get(i)[1], true);
		if (chunks.size() > MAX_FORCED_CHUNKS && now >= WARNED.getOrDefault(key, 0L)) {
			WARNED.put(key, now + 12000L);
			LOGGER.warn("Village footprint at {} spans {} chunks; forced-loading the nearest {} only (hard cap).",
				ref.bell().toShortString(), chunks.size(), MAX_FORCED_CHUNKS);
		}
	}
}
