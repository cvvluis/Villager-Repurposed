package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Network;
import de.villagerplus.village.RoadBridge;
import de.villagerplus.village.Roads;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Finds repairs for bridge spans already recorded on a completed overland route. */
public final class RoadMaintenance {
	private static final int MAX_ROUTE_POINTS = 689;

	private RoadMaintenance() { }

	/**
	 * Returns the next paid deck or pier placement for a recorded bridge, without loading chunks or
	 * changing the route cursor. Ordinary road-surface repairs are scanned first by RoadBuilderJob.
	 */
	public static Roads.Repair nextBridgeRepair(ServerLevel level, VillageRef ref) {
		if (level == null || ref == null || ref.bellEntity() == null) return null;
		Map<String, Long> state = ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
		for (TradeNetwork.Link link : Network.of(ref.bellEntity()).links()) {
			if (!link.dimension().equals(Network.dim(level)) || !(link.route() || link.relation() >= Roads.TRADE_PARTNER)) continue;
			String key = link.dimension() + link.bell().asLong();
			if (!validRouteState(state, key, ref.bell(), link.bell())) continue;
			int length = routeLength(state, key, ref.bell(), link.bell());
			if (length < 1 || length > MAX_ROUTE_POINTS) continue;
			int builtLimit = length / 2 + 2;
			int built = (int) Math.min(builtLimit, Math.max(0L, state.getOrDefault(key, 0L)));
			int start = (int) Math.max(0L, Math.min(built, state.getOrDefault(key + ":start", 0L)));
			for (int cursor = start; cursor < built; cursor++) {
				for (int side = -1; side <= 1; side++) {
					Long deckY = state.get(key + ":bridge:" + cursor + ":" + side);
					if (deckY == null || deckY < level.getMinY() || deckY >= level.getMaxY()) continue;
					BlockPos route = routePoint(state, key, ref.bell(), link.bell(), cursor, length);
					BlockPos adjacent = routePoint(state, key, ref.bell(), link.bell(), cursor < length ? cursor + 1 : cursor - 1, length);
					if (route == null || adjacent == null) continue;
					boolean alongX = route.getX() != adjacent.getX();
					int x = route.getX() + (alongX ? 0 : side);
					int z = route.getZ() + (alongX ? side : 0);
					BlockPos deck = new BlockPos(x, deckY.intValue(), z);
					if (!level.hasChunkAt(deck)) continue;
     RoadBridge.Plan plan = RoadBridge.plan(level, deck, ref.bell(), link.bell());
					if (plan != null && plan.next() != null) return plan.next();
				}
			}
		}
		return null;
	}

	private static int routeLength(Map<String, Long> state, String key, BlockPos from, BlockPos to) {
		long storedCount = state.getOrDefault(key + ":routeCount", 0L);
		if (storedCount > 0) return storedCount <= MAX_ROUTE_POINTS ? (int) storedCount - 1 : MAX_ROUTE_POINTS + 1;
		return Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
	}

	private static boolean validRouteState(Map<String, Long> state, String key, BlockPos from, BlockPos to) {
		long count = state.getOrDefault(key + ":routeCount", 0L);
		if (count == 0) return true;
		if (count < 2 || count > MAX_ROUTE_POINTS) return false;
		long low = Math.min(from.asLong(), to.asLong());
		long high = Math.max(from.asLong(), to.asLong());
		return state.getOrDefault(key + ":routeFrom", Long.MIN_VALUE) == low
			&& state.getOrDefault(key + ":routeTo", Long.MIN_VALUE) == high;
	}

	private static BlockPos routePoint(Map<String, Long> state, String key, BlockPos from, BlockPos to, int cursor, int length) {
		long count = state.getOrDefault(key + ":routeCount", 0L);
		if (count > 1 && count <= MAX_ROUTE_POINTS) {
			int bounded = Math.max(0, Math.min(length, cursor));
			int canonical = from.asLong() <= to.asLong() ? bounded : length - bounded;
			Long saved = state.get(key + ":routePoint:" + canonical);
			if (saved != null) {
				BlockPos point = BlockPos.of(saved);
				return new BlockPos(point.getX(), from.getY(), point.getZ());
			}
			return null;
		}
		if (count != 0) return null;
		return legacyPoint(from, to, cursor, length);
	}

	private static BlockPos legacyPoint(BlockPos from, BlockPos to, int cursor, int length) {
		if (length <= 0) return from;
		boolean forward = from.asLong() <= to.asLong();
		BlockPos start = forward ? from : to;
		BlockPos end = forward ? to : from;
		int index = Math.max(0, Math.min(length, forward ? cursor : length - cursor));
		int dx = end.getX() - start.getX();
		int dz = end.getZ() - start.getZ();
		int x = start.getX() + (int) Math.copySign(Math.min(Math.abs(dx), index), dx);
		int z = start.getZ() + (int) Math.copySign(Math.max(0, index - Math.abs(dx)), dz);
		return new BlockPos(x, from.getY(), z);
	}
}
