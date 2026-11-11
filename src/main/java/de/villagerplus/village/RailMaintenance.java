package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Repairs missing rails only inside a saved, already-built bilateral metropolis segment. */
public final class RailMaintenance {
	private static final long RESCAN_TICKS = 200;
	private static final int MAX_CACHE = 512;
	private static final Map<UUID, Scan> SCANS = new HashMap<>();

	private RailMaintenance() { }

	private record Scan(long nextScan, Repair repair) { }
	private record Site(BlockPos support, BlockPos rail, boolean redstoneSupport) { }

	public record Repair(BlockPos home, BlockPos peer, String routeKey, int cursor,
		BlockPos support, BlockPos rail, BlockState railState, Item railItem,
		boolean needsRedstoneSupport, BlockState oldSupport) { }

	/** Called after ordinary road-surface and bridge work; incomplete rail construction is never treated as damage. */
	public static boolean work(Ctx c) {
		if (c.village() == null || c.village().bellEntity() == null) return false;
		UUID worker = c.v().getUUID();
		Scan scan = SCANS.get(worker);
		if (scan == null || scan.repair() == null && c.now() >= scan.nextScan()) {
			Repair repair = nextRepair(c.level(), c.village());
			scan = new Scan(c.now() + RESCAN_TICKS, repair);
			cache(worker, scan);
		}
		Repair repair = scan.repair();
		if (repair == null) return false;
		if (!repairable(c.level(), repair)) {
			cache(worker, new Scan(c.now() + 1, null));
			return false;
		}

		Item missing = nextMissing(repair, c.v().getInventory());
		if (missing != null) {
			BlockPos source = RailResources.sourceFor(c.level(), c.village(), missing);
			if (source == null) {
				c.setActivity("wartet auf Schienenmaterial");
				cache(worker, new Scan(c.now() + RESCAN_TICKS, null));
				return false;
			}
			c.setActivity("holt Schienenmaterial");
			if (!JobUtil.approachLong(c, source, 3)) return c.st().cooldownUntil <= c.now();
			Container box = HopperBlockEntity.getContainerAt(c.level(), source);
			if (box == null || JobUtil.count(box, stack -> stack.is(missing)) < 1
				|| !c.v().getInventory().canAddItem(new ItemStack(missing, 1))) return true;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (!stack.is(missing)) continue;
				stack.shrink(1);
				if (stack.isEmpty()) box.setItem(slot, ItemStack.EMPTY);
				box.setChanged();
				c.v().getInventory().addItem(new ItemStack(missing, 1));
				return true;
			}
			return true;
		}

		c.setActivity("repariert die Bahnstrecke");
		if (!JobUtil.approachLong(c, repair.rail(), 3)) return c.st().cooldownUntil <= c.now();
		if (!repairable(c.level(), repair)) {
			cache(worker, new Scan(c.now() + 1, null));
			return false;
		}
		if (!place(c.level(), repair)) {
			cache(worker, new Scan(c.now() + 1, null));
			return false;
		}
		JobUtil.take(c.v().getInventory(), stack -> stack.is(repair.railItem()), 1);
		if (repair.needsRedstoneSupport()) JobUtil.take(c.v().getInventory(), stack -> stack.is(Items.REDSTONE_BLOCK), 1);
		JobUtil.swing(c.v());
		c.level().playSound(null, repair.rail(), repair.railState().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1, 1);
		SCANS.remove(worker);
		return true;
	}

	/** Finds the first absent rail whose cursor is behind the saved rail-builder cursor. */
	public static Repair nextRepair(ServerLevel level, VillageRef home) {
		if (!validBell(level, home)) return null;
		TradeNetwork network = Network.of(home.bellEntity());
		String dimension = Network.dim(level);
		Map<String, Long> roads = home.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
		for (TradeNetwork.Link link : network.links()) {
			if (link.relation() < Roads.METROPOLIS || !link.route() || !dimension.equals(link.dimension())) continue;
			VillageRef peer = Network.resolve(level, link);
			if (!validBell(level, peer) || !reciprocalRailLink(level, home, peer)) continue;
			String routeKey = dimension + peer.bell().asLong();
			String railKey = "rail" + peer.bell().asLong();
			if (!storedRoute(roads, routeKey, home.bell(), peer.bell())) continue;
			int length = RoadRoute.length(roads, routeKey, home.bell(), peer.bell());
			int last = length / 2 + 1;
			int built = (int) Math.max(0, Math.min((long) last + 1, roads.getOrDefault(railKey, 0L)));
			if (built <= 0) continue;
			BlockPos station = Specials.get(home, Blueprints.STATION);
			if (station == null || !level.hasChunkAt(station)) continue;
			int start = stationStart(level, roads, routeKey, home.bell(), peer.bell(), station, length, last);
			if (start < 0 || start >= built) continue;
			for (int cursor = start; cursor < built; cursor++) {
				BlockPos point = RoadRoute.point(roads, routeKey, home.bell(), peer.bell(), cursor);
				Site site = findSite(level, point.getX(), point.getZ(), home.bell().getY());
				if (site == null || !usableSite(level, site)) continue;
				BlockState current = level.getBlockState(site.rail());
				if (current.getBlock() instanceof BaseRailBlock) continue;
				boolean powered = cursor % 6 == 3;
				boolean needsRedstone = powered && !site.redstoneSupport();
				BaseRailBlock rail = (BaseRailBlock) (powered ? Blocks.POWERED_RAIL : Blocks.RAIL);
				RailShape shape = RoadRoute.alongX(roads, routeKey, home.bell(), peer.bell(), cursor)
					? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
				BlockState railState = rail.defaultBlockState().setValue(rail.getShapeProperty(), shape);
				return new Repair(home.bell().immutable(), peer.bell().immutable(), routeKey, cursor,
					site.support().immutable(), site.rail().immutable(), railState,
					powered ? Items.POWERED_RAIL : Items.RAIL, needsRedstone, level.getBlockState(site.support()));
			}
		}
		return null;
	}

	private static boolean reciprocalRailLink(ServerLevel level, VillageRef home, VillageRef peer) {
		String dimension = Network.dim(level);
		return Network.of(peer.bellEntity()).links().stream().anyMatch(link -> link.bell().equals(home.bell())
			&& link.relation() >= Roads.METROPOLIS && link.route() && dimension.equals(link.dimension()));
	}

	private static boolean validBell(ServerLevel level, VillageRef ref) {
		if (ref == null || ref.bell() == null || ref.data() == null || ref.bellEntity() == null || !level.hasChunkAt(ref.bell())) return false;
		BlockEntity current = level.getBlockEntity(ref.bell());
		return current == ref.bellEntity() && current.getAttached(VillagerPlus.VILLAGE) == ref.data();
	}

	private static boolean storedRoute(Map<String, Long> roads, String key, BlockPos from, BlockPos to) {
		long low = from.asLong() <= to.asLong() ? from.asLong() : to.asLong();
		long high = from.asLong() <= to.asLong() ? to.asLong() : from.asLong();
		if (roads.getOrDefault(key + ":routeFrom", Long.MIN_VALUE) != low
			|| roads.getOrDefault(key + ":routeTo", Long.MIN_VALUE) != high) return false;
		long geometry = roads.getOrDefault(key + ":routeGeometry", 0L);
		long count = roads.getOrDefault(key + ":routeCount", -1L);
		if (geometry == 2L && count == 0L) return true;
		int length = RoadRoute.length(roads, key, from, to);
		if (geometry < 3L || count != (long) length + 1L) return false;
		for (int i = 0; i <= length; i++) if (!roads.containsKey(key + ":routePoint:" + i)) return false;
		return true;
	}

	private static int stationStart(ServerLevel level, Map<String, Long> roads, String routeKey,
		BlockPos from, BlockPos to, BlockPos station, int length, int last) {
		int selected = -1;
		double distance = 24 * 24;
		for (int cursor = 0; cursor <= last; cursor++) {
			BlockPos point = RoadRoute.point(roads, routeKey, from, to, cursor);
			if (!level.hasChunkAt(point) || findSite(level, point.getX(), point.getZ(), from.getY()) == null) continue;
			double candidate = station.distSqr(point);
			if (candidate < distance) { selected = cursor; distance = candidate; }
		}
		return selected;
	}

	private static Site findSite(ServerLevel level, int x, int z, int platformY) {
		if (!level.hasChunkAt(new BlockPos(x, platformY, z))) return null;
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int high = platformY > level.getMinY() + 4 ? Math.max(top, platformY + 1) : top;
		int low = Math.min(top - 3, platformY > level.getMinY() + 4 ? platformY - 4 : top - 3);
		for (int y = high; y >= low; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			if (!level.hasChunkAt(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof BaseRailBlock) {
				BlockPos support = pos.below();
				return new Site(support, pos, level.getBlockState(support).is(Blocks.REDSTONE_BLOCK));
			}
			if (state.is(Blocks.REDSTONE_BLOCK)) {
				BlockPos foundation = pos.below();
				if (level.getBlockState(foundation).isFaceSturdy(level, foundation, net.minecraft.core.Direction.UP)
					&& level.getBlockEntity(foundation) == null) return new Site(pos, pos.above(), true);
			}
			if (railRoad(state)) return new Site(pos, pos.above(), false);
		}
		return null;
	}

	private static boolean usableSite(ServerLevel level, Site site) {
		BlockState support = level.getBlockState(site.support());
		if (support.is(Blocks.REDSTONE_BLOCK)) {
			BlockPos foundation = site.support().below();
			if (!level.getBlockState(foundation).isFaceSturdy(level, foundation, net.minecraft.core.Direction.UP)
				|| level.getBlockEntity(foundation) != null) return false;
		} else if (!railRoad(support)) return false;
		if (level.getBlockEntity(site.rail()) != null) return false;
		BlockState target = level.getBlockState(site.rail());
		if (!target.isAir() && (!target.canBeReplaced() || !target.getFluidState().isEmpty())) return false;
		return level.getBlockState(site.rail().above()).getCollisionShape(level, site.rail().above()).isEmpty()
			&& level.getBlockState(site.rail().above()).getFluidState().isEmpty();
	}

	private static boolean railRoad(BlockState state) {
		return state.is(Blocks.GRAVEL) || state.is(Blocks.SMOOTH_SANDSTONE) || state.is(Blocks.SPRUCE_PLANKS);
	}

	public static boolean repairable(ServerLevel level, Repair repair) {
		if (repair == null || !level.hasChunkAt(repair.rail()) || !level.hasChunkAt(repair.peer())) return false;
		BlockEntity entity = level.getBlockEntity(repair.home());
		if (!(entity instanceof net.minecraft.world.level.block.entity.BellBlockEntity)) return false;
		VillageRef home = new VillageRef(repair.home(), entity.getAttached(VillagerPlus.VILLAGE), entity);
		TradeNetwork.Link link = Network.of(entity).links().stream().filter(candidate -> candidate.bell().equals(repair.peer())
			&& candidate.relation() >= Roads.METROPOLIS && candidate.route()
			&& candidate.dimension().equals(Network.dim(level))).findFirst().orElse(null);
		VillageRef peer = link == null ? null : Network.resolve(level, link);
		if (!validBell(level, home) || !validBell(level, peer) || !reciprocalRailLink(level, home, peer)) return false;
		Map<String, Long> roads = entity.getAttachedOrElse(VillagerPlus.ROADS, Map.of());
		int length = RoadRoute.length(roads, repair.routeKey(), repair.home(), repair.peer());
		int cursor = repair.cursor();
		if (cursor > length / 2 + 1 || roads.getOrDefault("rail" + repair.peer().asLong(), 0L) <= cursor) return false;
		if (!storedRoute(roads, repair.routeKey(), repair.home(), repair.peer())) return false;
		BlockPos point = RoadRoute.point(roads, repair.routeKey(), repair.home(), repair.peer(), cursor);
		if (point.getX() != repair.rail().getX() || point.getZ() != repair.rail().getZ()) return false;
		Site site = findSite(level, point.getX(), point.getZ(), repair.home().getY());
		return site != null && site.rail().equals(repair.rail()) && site.support().equals(repair.support())
			&& level.getBlockState(repair.support()).equals(repair.oldSupport())
			&& usableSite(level, site) && !(level.getBlockState(repair.rail()).getBlock() instanceof BaseRailBlock);
	}

	private static Item nextMissing(Repair repair, Container inventory) {
		if (JobUtil.count(inventory, stack -> stack.is(repair.railItem())) < 1) return repair.railItem();
		if (repair.needsRedstoneSupport() && JobUtil.count(inventory, stack -> stack.is(Items.REDSTONE_BLOCK)) < 1) return Items.REDSTONE_BLOCK;
		return null;
	}

	private static boolean place(ServerLevel level, Repair repair) {
		if (!repairable(level, repair)) return false;
		BlockState oldRail = level.getBlockState(repair.rail());
		BlockState oldSupport = level.getBlockState(repair.support());
		boolean changedSupport = repair.needsRedstoneSupport();
		if (changedSupport && !level.setBlock(repair.support(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3)) return false;
		if (!level.setBlock(repair.rail(), repair.railState(), 3) || !RailTransport.safeRail(level, repair.rail())) {
			level.setBlock(repair.rail(), oldRail, 3);
			if (changedSupport) level.setBlock(repair.support(), oldSupport, 3);
			return false;
		}
		return true;
	}

	private static void cache(UUID worker, Scan scan) {
		if (SCANS.size() >= MAX_CACHE && !SCANS.containsKey(worker)) SCANS.clear();
		SCANS.put(worker, scan);
	}
}
