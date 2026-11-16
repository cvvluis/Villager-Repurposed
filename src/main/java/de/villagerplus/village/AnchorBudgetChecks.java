package de.villagerplus.village;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Checks of the village load budget: only the nearest villages stay force-loaded and old forced chunks are freed. */
public final class AnchorBudgetChecks {
	private static int checks;
	private AnchorBudgetChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		checks = 0;
		try {
			Anchor.resetBudgetForTest();
			long now = level.getGameTime();
			BlockPos[] bells = {origin, origin.offset(4000, 0, 0), origin.offset(8000, 0, 0), origin.offset(12000, 0, 0)};
			int kept = 0;
			var seen = new java.util.ArrayList<Boolean>();
			for (BlockPos b : bells) Anchor.seenForTest(level, b, now);
			for (BlockPos b : bells) {
				boolean in = Anchor.withinBudget(level, Anchor.seenForTest(level, b, now), b, now);
				seen.add(in);
				if (in) kept++;
			}
			check(kept == Anchor.MAX_KEPT_VILLAGES,
				"of four active villages exactly the budget of " + Anchor.MAX_KEPT_VILLAGES + " is kept (kept " + kept + " " + seen + ")");
			check(Anchor.MAX_KEPT_VILLAGES >= 1 && Anchor.MAX_KEPT_VILLAGES <= 4, "the default budget is a small number of villages");

			// A village that was not seen for longer than the window does not compete for the budget any more.
			Anchor.resetBudgetForTest();
			Anchor.seenForTest(level, bells[0], now - 5000L);
			for (int i = 1; i < bells.length; i++) Anchor.seenForTest(level, bells[i], now);
			check(Anchor.withinBudget(level, Anchor.seenForTest(level, bells[3], now), bells[3], now) || Anchor.MAX_KEPT_VILLAGES < 3,
				"a stale village does not take a budget slot from active ones");

			// Release: forced chunks are freed except the ones another kept village still holds.
			int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
			List<int[]> candidates = List.of(new int[] {cx, cz}, new int[] {cx + 1, cz}, new int[] {cx + 2, cz});
			for (int[] c : candidates) level.setChunkForced(c[0], c[1], true);
			int released = Anchor.releaseChunks(level, candidates, null, Set.of(Anchor.forcedChunkForTest(level, cx + 2, cz)));
			check(released == 2, "two of three forced chunks are released and the shared one stays (released " + released + ")");
			check(level.getChunkSource().getForceLoadedChunks().contains(net.minecraft.world.level.ChunkPos.pack(cx + 2, cz))
				&& !level.getChunkSource().getForceLoadedChunks().contains(net.minecraft.world.level.ChunkPos.pack(cx, cz)),
				"the shared chunk is still forced and the released one is not");
			level.setChunkForced(cx + 2, cz, false);
			check(Anchor.releaseChunks(level, candidates, null, Set.of()) == 0, "releasing already free chunks changes nothing");
			return checks;
		} finally {
			Anchor.resetBudgetForTest();
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("ANCHOR BUDGET CHECK FAILED: " + message);
		checks++;
	}
}
