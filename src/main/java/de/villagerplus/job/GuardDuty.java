package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Market;
import de.villagerplus.village.Network;
import de.villagerplus.village.Roads;
import de.villagerplus.village.Specials;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.levelgen.Heightmap;

/** Wache, Ausguck und Abenteurer: Türme, Straßen, Bahn, Kampf und Raid-Alarm. */
public final class GuardDuty {
	private static final long POST_REFRESH_TICKS = 200L;
	private static final int MAX_ROAD_POSTS = 24;
	private static final int MAX_RAIL_POSTS = 12;
	private static final int MAX_RAIL_SCAN_RADIUS = 24;
	private static final int MAX_ROAD_PATROL_DISTANCE = 32;
	private GuardDuty() { }

	public static boolean work(Ctx c) {
		if (c.village() == null) return false;
		if (alarm(c)) return true;
		if (fight(c)) return true;
		if (c.st().guardPatrolPostsCheckAt <= c.now()) {
			c.st().guardPatrolPosts = posts(c.level(), c.village());
			c.st().guardPatrolPostsCheckAt = c.now() + POST_REFRESH_TICKS;
		}
		List<BlockPos> points = c.st().guardPatrolPosts;
		if (points.isEmpty()) return false;
		int index = Math.floorMod(c.st().patrolIndex, points.size());
		BlockPos target = points.get(index);
		c.st().target = target;
		c.setActivity("patrouilliert Straßen und Bahn");
		Anim.hold(c, Items.IRON_SWORD);
		if (!JobUtil.approach(c, target, 2.5)) return true;
		c.st().patrolIndex = (index + 1) % points.size();
		c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 40;
		c.mem().jobsDone++;
		return true;
	}

	public static boolean raidNearby(ServerLevel level, BlockPos bell) {
		var raids = level.getRaids();
		var raid = raids == null ? null : raids.getNearbyRaid(bell, 96 * 96);
		return raid != null && raid.isStarted() && raid.isActive() && !raid.isStopped();
	}

	public static List<BlockPos> posts(ServerLevel level, VillageRef ref) {
		List<BlockPos> points = new ArrayList<>();
		BlockPos center = Market.of(ref).plaza().orElse(ref.bell());
		points.add(walk(level, center));
		points.add(walk(level, ref.bell()));
		for (BlockPos tower : towers(level, ref.bell())) points.add(walk(level, tower));
		BlockPos station = Specials.get(ref, Blueprints.STATION);
		if (station != null && level.hasChunkAt(station)) points.add(walk(level, station));
		addLocalRoadPosts(level, center, points);
		addLinkedRoadPosts(level, ref, points);
		if (station != null) addRailPosts(level, station, points);
		return points.stream().filter(point -> level.hasChunkAt(point)).distinct().toList();
	}

	private static boolean alarm(Ctx c) {
		boolean raid = raidNearby(c.level(), c.village().bell());
		if (raid) {
			if (c.now() < c.st().guardRaidAlarmAt) return false;
			ringBell(c);
			c.st().guardRaidAlarmAt = c.now() + POST_REFRESH_TICKS;
			c.setActivity("läutet Raid-Alarm");
			return true;
		}
		boolean hostile = !c.level().getEntitiesOfClass(Monster.class, c.v().getBoundingBox().inflate(24), Monster::isAlive).isEmpty();
		if (!hostile || c.now() < c.st().lightAt) return false;
		ringBell(c);
		c.st().lightAt = c.now() + POST_REFRESH_TICKS;
		c.setActivity("schlägt bei Gefahr Alarm");
		return true;
	}

	private static void ringBell(Ctx c) {
		if (c.level().getBlockState(c.village().bell()).getBlock() instanceof BellBlock bell)
			bell.attemptToRing(c.v(), c.level(), c.village().bell(), null);
	}

	private static boolean fight(Ctx c) {
		Monster hostile = c.st().targetEntity instanceof Monster monster && monster.isAlive() ? monster
			: c.level().getEntitiesOfClass(Monster.class, c.v().getBoundingBox().inflate(16), Monster::isAlive).stream()
				.min(Comparator.comparingDouble(monster -> monster.distanceToSqr(c.v()))).orElse(null);
		if (hostile == null) {
			if (c.st().targetEntity instanceof Monster) c.st().clearTarget();
			return false;
		}
		c.st().targetEntity = hostile;
		c.setActivity("bekämpft ein Monster");
		Anim.hold(c, Items.IRON_SWORD);
		if (!JobUtil.approach(c, hostile.position(), 2.4)) return true;
		hostile.hurtServer(c.level(), c.level().damageSources().mobAttack(c.v()), 6.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		if (!hostile.isAlive()) c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static List<BlockPos> towers(ServerLevel level, BlockPos bell) {
		List<BlockPos> towers = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bell.offset(-24, 3, -24), bell.offset(24, 16, 24))) {
			if (!level.getBlockState(pos).is(Blocks.LANTERN)) continue;
			BlockPos post = pos.below();
			if (level.getBlockState(post).is(Blocks.OAK_FENCE) || level.getBlockState(post).is(Blocks.SPRUCE_FENCE)) towers.add(post.immutable());
		}
		return towers;
	}

	private static void addLocalRoadPosts(ServerLevel level, BlockPos center, List<BlockPos> points) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			for (int distance = 4; distance <= 24; distance += 4) {
				BlockPos post = pavedWalkPosition(level, center.relative(direction, distance));
				if (post != null) points.add(post);
			}
		}
	}

	private static void addLinkedRoadPosts(ServerLevel level, VillageRef ref, List<BlockPos> points) {
		String dimension = Network.dim(level);
		Map<String, Long> roads = ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
		List<TradeNetwork.Link> links = Network.of(ref.bellEntity()).links().stream()
			.filter(link -> (link.route() || link.relation() >= Roads.TRADE_PARTNER) && link.dimension().equals(dimension))
			.sorted(Comparator.comparingLong((TradeNetwork.Link link) -> link.bell().asLong())).toList();
		int added = 0;
		for (TradeNetwork.Link link : links) {
			if (added >= MAX_ROAD_POSTS) break;
			String key = link.dimension() + link.bell().asLong();
			long storedCount = roads.getOrDefault(key + ":routeCount", 0L);
			int length = storedCount > 1L ? (int) Math.min(688L, storedCount - 1L)
				: Math.abs(link.bell().getX() - ref.bell().getX()) + Math.abs(link.bell().getZ() - ref.bell().getZ());
			if (length < 4) continue;
			for (int cursor = 8; cursor < Math.min(length, MAX_ROAD_PATROL_DISTANCE) && added < MAX_ROAD_POSTS; cursor += 8) {
				BlockPos lane = Roads.laneCell(roads, key, ref.bell(), link.bell(), cursor, 0);
				BlockPos post = pavedWalkPosition(level, lane);
				if (post != null) {
					points.add(post);
					added++;
				}
			}
		}
	}

	private static void addRailPosts(ServerLevel level, BlockPos station, List<BlockPos> points) {
		if (!level.hasChunkAt(station)) return;
		List<BlockPos> rails = new ArrayList<>();
		for (int x = station.getX() - MAX_RAIL_SCAN_RADIUS; x <= station.getX() + MAX_RAIL_SCAN_RADIUS; x++) {
			for (int z = station.getZ() - MAX_RAIL_SCAN_RADIUS; z <= station.getZ() + MAX_RAIL_SCAN_RADIUS; z++) {
				for (int y = station.getY() - 4; y <= station.getY() + 4; y++) {
					BlockPos rail = new BlockPos(x, y, z);
					if (level.hasChunkAt(rail) && level.getBlockState(rail).is(BlockTags.RAILS)) rails.add(rail);
				}
			}
		}
		rails.sort(Comparator.comparingDouble((BlockPos rail) -> rail.distSqr(station)).thenComparingLong(BlockPos::asLong));
		int stride = Math.max(1, (rails.size() + MAX_RAIL_POSTS - 1) / MAX_RAIL_POSTS);
		int added = 0;
		for (int i = 0; i < rails.size() && added < MAX_RAIL_POSTS; i += stride) {
			BlockPos post = besideRail(level, rails.get(i));
			if (post != null) {
				points.add(post);
				added++;
			}
		}
	}

	private static BlockPos besideRail(ServerLevel level, BlockPos rail) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos beside = rail.relative(direction);
			if (!level.hasChunkAt(beside)) continue;
			BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, beside);
			if (Math.abs(feet.getY() - rail.getY()) > 2 || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				|| !level.getBlockState(feet).getFluidState().isEmpty()
				|| !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				|| !level.getBlockState(feet.above()).getFluidState().isEmpty()
				|| !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) continue;
			return feet.immutable();
		}
		return null;
	}

	private static BlockPos pavedWalkPosition(ServerLevel level, BlockPos route) {
		if (!level.hasChunkAt(route)) return null;
		int x = route.getX(), z = route.getZ();
		BlockPos ground = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
		if (!level.getBlockState(ground).is(Blocks.GRAVEL) && !level.getBlockState(ground).is(Blocks.COBBLESTONE)
			&& !level.getBlockState(ground).is(Blocks.SANDSTONE) && !level.getBlockState(ground).is(Blocks.SMOOTH_SANDSTONE)
			&& !level.getBlockState(ground).is(Blocks.SPRUCE_PLANKS)) return null;
		BlockPos feet = ground.above();
		if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			|| !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
			|| !level.getBlockState(feet).getFluidState().isEmpty()) return null;
		return feet.immutable();
	}

	private static BlockPos walk(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return pos;
		return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
	}
}
