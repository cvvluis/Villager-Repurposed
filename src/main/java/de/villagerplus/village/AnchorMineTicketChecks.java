package de.villagerplus.village;

import de.villagerplus.Config;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Focused checks for expiring, nonpersistent tickets along active mine corridors. */
public final class AnchorMineTicketChecks {
	private static int checks;
	private AnchorMineTicketChecks() { }

	public static int check() {
		checks = 0;
		check(!Anchor.clockRewound(1000L, 1050L) && !Anchor.refreshDue(1000L, 1050L),
			"village housing refresh throttle holds within the same world's interval");
		check(Anchor.clockRewound(1000L, 20L) && Anchor.refreshDue(1000L, 20L),
			"a lower game clock after a world switch resets the village refresh");
		check(Anchor.refreshDue(null, 20L), "a village without cached refresh state immediately requests its work");
		check(Anchor.WORK_TICKET_REFRESH_INTERVAL == 120L && Anchor.MINE_WORK.timeout() == 240L
			&& Anchor.VILLAGER_WORK.timeout() == 240L,
			"mine and resident leases retain their 240-tick timeout and refresh halfway through it");

		Anchor.WorkTicketRefreshCache leases = new Anchor.WorkTicketRefreshCache(2);
		ChunkPos leasedChunk = new ChunkPos(4, -1);
		Anchor.WorkTicketKey key = new Anchor.WorkTicketKey(Level.OVERWORLD,
			Identifier.fromNamespaceAndPath("villagerplus", "mine_work"), leasedChunk.pack());
		check(leases.refreshDue(key, 1000L), "the first request for a dimension/type/chunk lease is allowed");
		leases.recordRefresh(key, 1000L);
		check(!leases.refreshDue(key, 1119L) && leases.hasUnexpiredLease(key, 1119L, Anchor.MINE_WORK.timeout()),
			"repeated route and resident requests deduplicate within the 120-tick lease window");
		check(leases.refreshDue(key, 1120L) && leases.hasUnexpiredLease(key, 1120L, Anchor.MINE_WORK.timeout()),
			"the shared ticket renews at 120 ticks while its original 240-tick lease is still active");
		leases.recordRefresh(key, 1120L);
		check(leases.hasUnexpiredLease(key, 1359L, Anchor.MINE_WORK.timeout())
			&& !leases.hasUnexpiredLease(key, 1360L, Anchor.MINE_WORK.timeout()),
			"a renewed work ticket remains tracked until its full 240-tick timeout");
		check(leases.refreshDue(key, 20L) && leases.hasUnexpiredLease(key, 20L, Anchor.MINE_WORK.timeout()),
			"a rewound level clock immediately renews and preserves an already tracked lease");
		leases.recordRefresh(key, 20L);
		Anchor.WorkTicketKey otherType = new Anchor.WorkTicketKey(Level.OVERWORLD,
			Identifier.fromNamespaceAndPath("villagerplus", "villager_work"), leasedChunk.pack());
		check(leases.refreshDue(otherType, 20L), "mine and resident ticket types keep independent lease records");
		leases.recordRefresh(otherType, 20L);
		Anchor.WorkTicketKey otherDimension = new Anchor.WorkTicketKey(Level.NETHER,
			Identifier.fromNamespaceAndPath("villagerplus", "mine_work"), leasedChunk.pack());
		check(leases.refreshDue(otherDimension, 20L), "the same chunk coordinates use an independent lease per dimension");
		leases.recordRefresh(otherDimension, 20L);
		Anchor.WorkTicketKey otherChunk = new Anchor.WorkTicketKey(Level.OVERWORLD,
			Identifier.fromNamespaceAndPath("villagerplus", "mine_work"), new ChunkPos(5, -1).pack());
		check(leases.refreshDue(otherChunk, 20L), "neighboring chunks have independent lease refreshes");
		leases.recordRefresh(otherChunk, 20L);
		check(leases.size() <= 2, "the lease refresh cache stays within its configured bound");
		leases.clear();
		check(leases.refreshDue(key, 20L), "server/world cache reset clears tracked work leases");
		check(Anchor.shouldLeaseMineRoute(true, true) && !Anchor.shouldLeaseMineRoute(true, false)
			&& !Anchor.shouldLeaseMineRoute(false, true),
			"a completed mine keeps a route lease only while its resident is underground and village loading is enabled");
		VillageData.Mine active = new VillageData.Mine(new BlockPos(15, 70, -1),
			Direction.EAST.get2DDataValue(), 20, 62, false);
		Set<ChunkPos> requested = Anchor.mineWorkChunks(active, 384);
		Set<ChunkPos> expected = new HashSet<>();
		for (int x = 0; x <= 5; x++) expected.add(new ChunkPos(x, -1));
		check(requested.equals(expected), "active mine leases every corridor chunk through a non-aligned lookahead endpoint");

		active.done = true;
		check(Anchor.mineWorkChunks(active, 384).isEmpty(),
			"completed mine has no background corridor lease; underground route owners renew theirs separately");
		VillageData.Mine malformed = new VillageData.Mine(new BlockPos(15, 70, -1), 4, 20, 62, false);
		check(Anchor.mineWorkChunks(malformed, 384).isEmpty(), "invalid saved direction cannot create a remote chunk-ticket corridor");
		VillageData.Mine oversized = new VillageData.Mine(new BlockPos(15, 70, -1), Direction.EAST.get2DDataValue(), Integer.MAX_VALUE, 62, false);
		check(Anchor.mineWorkChunks(oversized, 384).isEmpty(), "out-of-range saved cursor cannot request an unbounded chunk-ticket corridor");

		check(Anchor.MINE_WORK.doesLoad() && Anchor.MINE_WORK.doesSimulate() && !Anchor.MINE_WORK.persist()
			&& Anchor.MINE_WORK.timeout() == Config.HOUSING_INTERVAL + 40L
			&& Identifier.fromNamespaceAndPath("villagerplus", "mine_work").equals(BuiltInRegistries.TICKET_TYPE.getKey(Anchor.MINE_WORK)),
			"registered mine tickets simulate only the requested chunk and expire after the housing refresh interval");
		return checks;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Anchor mine ticket check failed: " + message);
		checks++;
	}
}
