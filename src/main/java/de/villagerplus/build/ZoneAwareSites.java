package de.villagerplus.build;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/** Zone-aware construction siting. The two-block parcel border reserves room for scaffold and the workbench. */
public final class ZoneAwareSites {
	public static final int CLEARANCE = 2;
	static final int HOUSE_TO_HOUSE_CLEARANCE = 4;
	private ZoneAwareSites() { }

	/** Outdoor communal infrastructure keeps its own policy; expansion sites use their saved zone kind. */
	public static String kindFor(String type) {
		if (Blueprints.HOUSE.equals(type)) return "housing";
		if (Blueprints.FARM.equals(type)) return "farm";
		if (Blueprints.PEN.equals(type)) return "pasture";
		return Blueprints.STORAGE.equals(type) || Blueprints.SCHOOL.equals(type) || Blueprints.special(type)
			|| Blueprints.TOWNHALL.equals(type) ? "work" : null;
	}

	private static List<ZonePolicy.Zone> zones(VillageRef ref, String type) {
		String kind = kindFor(type);
		if (ref == null || kind == null) return List.of();
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(z -> kind.equals(z.kind()) && ZonePolicy.validBounds(ref, z.kind(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
	}

	public static boolean allowsRect(VillageRef ref, String type, BlockPos anchor, int minX, int maxX, int minZ, int maxZ) {
		List<ZonePolicy.Zone> zones = zones(ref, type);
		return zones.isEmpty() || zones.stream().anyMatch(z -> (long)anchor.getX() + minX - CLEARANCE >= z.minX()
			&& (long)anchor.getX() + maxX + CLEARANCE <= z.maxX()
			&& (long)anchor.getZ() + minZ - CLEARANCE >= z.minZ()
			&& (long)anchor.getZ() + maxZ + CLEARANCE <= z.maxZ());
	}

	/** Includes every planned decoration/roof/porch block, not only the Architect's main floor. */
	public static boolean allowsPlan(VillageRef ref, String type, List<Step> steps) {
		if (steps == null || steps.isEmpty()) return false;
		int minX = steps.stream().mapToInt(s -> s.pos().getX()).min().orElseThrow();
		int maxX = steps.stream().mapToInt(s -> s.pos().getX()).max().orElseThrow();
		int minZ = steps.stream().mapToInt(s -> s.pos().getZ()).min().orElseThrow();
		int maxZ = steps.stream().mapToInt(s -> s.pos().getZ()).max().orElseThrow();
		if (!allowsRect(ref, type, BlockPos.ZERO, minX, maxX, minZ, maxZ)) return false;
		return !Blueprints.HOUSE.equals(type) || hasHouseClearance(ref, minX, maxX, minZ, maxZ);
	}

	/** Keep newly planned houses apart, including where a village has no explicit housing zones. */
	static boolean hasHouseClearance(VillageRef ref, int minX, int maxX, int minZ, int maxZ) {
		if (ref == null) return true;
		if (ref.bellEntity().getLevel() instanceof ServerLevel level) {
			int homeKeepout = 6 + HOUSE_TO_HOUSE_CLEARANCE;
			BlockPos center = new BlockPos((minX + maxX) / 2, ref.bell().getY(), (minZ + maxZ) / 2);
			int searchRadius = homeKeepout + Math.max(maxX - minX, maxZ - minZ) / 2;
			for (BlockPos home : level.getPoiManager().getInRange(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME),
				center, searchRadius, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
				.map(p -> p.getPos().immutable()).toList()) {
				int gapX = home.getX() < minX ? minX - home.getX() : home.getX() > maxX ? home.getX() - maxX : 0;
				int gapZ = home.getZ() < minZ ? minZ - home.getZ() : home.getZ() > maxZ ? home.getZ() - maxZ : 0;
				if (gapX < homeKeepout && gapZ < homeKeepout) return false;
			}
		}
		for (Built existing : Builder.builtOf(ref)) {
			if (!Blueprints.HOUSE.equals(existing.type())) continue;
			Architect.Design design = Architect.design(Architect.Kind.HOUSE, existing.variant() >>> 2);
			int radius = design.half() + design.overhang();
			int oldMinX = existing.origin().getX() - radius;
			int oldMaxX = existing.origin().getX() + radius;
			int oldMinZ = existing.origin().getZ() - radius;
			int oldMaxZ = existing.origin().getZ() + radius;
			int gapX = Math.max(0, Math.max(oldMinX - maxX, minX - oldMaxX));
			int gapZ = Math.max(0, Math.max(oldMinZ - maxZ, minZ - oldMaxZ));
			if (gapX < HOUSE_TO_HOUSE_CLEARANCE && gapZ < HOUSE_TO_HOUSE_CLEARANCE) return false;
		}
		return true;
	}

	public static BlockPos candidate(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max) {
		int referenceY = Sites.villageReferenceY(level, ref);
		if (zones(ref, type).isEmpty()) return Sites.candidate(level, ref.bell(), random, min, max, referenceY);
		List<BlockPos> choices = candidates(ref, type, random, 0, 0, 0, 0);
		for (BlockPos p : choices) {
			if (!level.hasChunkAt(p)) continue;
			BlockPos candidate = surface(level, p);
			if (Sites.villageLevel(referenceY, candidate.getY())) return candidate;
		}
		return null;
	}

	public static BlockPos tolerantAnchor(ServerLevel level, VillageRef ref, String type, BlockPos candidate,
		int minX, int maxX, int minZ, int maxZ, int headroom) {
		if (!allowsRect(ref, type, candidate, minX, maxX, minZ, maxZ)) return null;
		BlockPos anchor = Sites.tolerantAnchor(level, candidate, minX, maxX, minZ, maxZ, headroom);
		return anchor != null && Sites.villageLevel(Sites.villageReferenceY(level, ref), anchor.getY()) ? anchor : null;
	}

	public static BlockPos find(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int half, int headroom, int attempts, Predicate<BlockPos> planFits) {
		int referenceY = Sites.villageReferenceY(level, ref);
		if (zones(ref, type).isEmpty()) return Sites.find(level, ref.bell(), random, min, max, half, headroom, attempts, referenceY, planFits);
		return findZoned(level, ref, type, random, -half, half, -half, half, headroom, attempts, false, planFits, referenceY);
	}

	public static BlockPos findTolerant(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int minX, int maxX, int minZ, int maxZ, int headroom, int attempts, Predicate<BlockPos> planFits) {
		int referenceY = Sites.villageReferenceY(level, ref);
		if (zones(ref, type).isEmpty()) return Sites.findTolerant(level, ref.bell(), random, min, max, minX, maxX, minZ, maxZ,
			headroom, attempts, referenceY, planFits);
		return findZoned(level, ref, type, random, minX, maxX, minZ, maxZ, headroom, attempts, true, planFits, referenceY);
	}

	public static BlockPos findSystematic(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		int referenceY = Sites.villageReferenceY(level, ref);
		if (zones(ref, type).isEmpty()) return Sites.findSystematic(level, ref.bell(), random, min, max, minX, maxX, minZ, maxZ, headroom, planFits, referenceY);
		return findZoned(level, ref, type, random, minX, maxX, minZ, maxZ, headroom, 4096, false, planFits, referenceY);
	}

	public static BlockPos findTolerantSystematic(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		int referenceY = Sites.villageReferenceY(level, ref);
		if (zones(ref, type).isEmpty()) return Sites.findTolerantSystematic(level, ref.bell(), random, min, max, minX, maxX, minZ, maxZ, headroom, planFits, referenceY);
		return findZoned(level, ref, type, random, minX, maxX, minZ, maxZ, headroom, 4096, true, planFits, referenceY);
	}

	/** Prefer civic plots closest to the measured town centre, while still applying the normal zone and full-plan guards. */
	public static BlockPos findCentral(ServerLevel level, VillageRef ref, String type, BlockPos center, RandomSource random, int radius,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		int referenceY = Sites.villageReferenceY(level, ref);
		if (zones(ref, type).isEmpty()) return Sites.findSystematic(level, center, random, 0, radius, minX, maxX, minZ, maxZ, headroom, planFits, referenceY);
		return findZonedCentral(level, ref, type, center, random, minX, maxX, minZ, maxZ, headroom, false, planFits, referenceY);
	}

	public static BlockPos findTolerantCentral(ServerLevel level, VillageRef ref, String type, BlockPos center, RandomSource random, int radius,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		int referenceY = Sites.villageReferenceY(level, ref);
		if (zones(ref, type).isEmpty()) return Sites.findTolerantSystematic(level, center, random, 0, radius, minX, maxX, minZ, maxZ, headroom, planFits, referenceY);
		return findZonedCentral(level, ref, type, center, random, minX, maxX, minZ, maxZ, headroom, true, planFits, referenceY);
	}

	private static BlockPos findZonedCentral(ServerLevel level, VillageRef ref, String type, BlockPos center, RandomSource random,
		int minX, int maxX, int minZ, int maxZ, int headroom, boolean tolerant, Predicate<BlockPos> planFits, int referenceY) {
		List<BlockPos> choices = candidates(ref, type, random, minX, maxX, minZ, maxZ);
		choices.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(center)));
		int checked = 0;
		for (BlockPos choice : choices) {
			if (!level.hasChunkAt(choice)) continue;
			if (checked++ >= 4096) break;
			BlockPos anchor = surface(level, choice);
			if (anchor != null && !Sites.villageLevel(referenceY, anchor.getY())) anchor = null;
			if (tolerant) anchor = anchor == null ? null : Sites.tolerantAnchor(level, anchor, minX, maxX, minZ, maxZ, headroom);
			else if (anchor != null && !Sites.okRect(level, anchor, minX, maxX, minZ, maxZ, headroom)) anchor = null;
			if (anchor != null && !Sites.villageLevel(referenceY, anchor.getY())) anchor = null;
			if (anchor != null && planFits.test(anchor)) return anchor;
		}
		return null;
	}

	private static BlockPos findZoned(ServerLevel level, VillageRef ref, String type, RandomSource random,
		int minX, int maxX, int minZ, int maxZ, int headroom, int attempts, boolean tolerant, Predicate<BlockPos> planFits, int referenceY) {
		int checked = 0;
		for (BlockPos choice : candidates(ref, type, random, minX, maxX, minZ, maxZ)) {
			if (!level.hasChunkAt(choice)) continue;
			if (checked++ >= attempts) break;
			BlockPos anchor = surface(level, choice);
			if (anchor != null && !Sites.villageLevel(referenceY, anchor.getY())) anchor = null;
			if (tolerant) anchor = anchor == null ? null : Sites.tolerantAnchor(level, anchor, minX, maxX, minZ, maxZ, headroom);
			else if (anchor != null && !Sites.okRect(level, anchor, minX, maxX, minZ, maxZ, headroom)) anchor = null;
			if (anchor != null && !Sites.villageLevel(referenceY, anchor.getY())) anchor = null;
			if (anchor != null && planFits.test(anchor)) return anchor;
		}
		return null;
	}

	private static BlockPos surface(ServerLevel level, BlockPos p) {
		return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
	}

	private static List<BlockPos> candidates(VillageRef ref, String type, RandomSource random, int minX, int maxX, int minZ, int maxZ) {
		List<BlockPos> choices = new ArrayList<>();
		HashSet<Long> seen = new HashSet<>();
		for (ZonePolicy.Zone zone : zones(ref, type)) {
			for (int x = zone.minX() - minX + CLEARANCE; x <= zone.maxX() - maxX - CLEARANCE; x++) {
				for (int z = zone.minZ() - minZ + CLEARANCE; z <= zone.maxZ() - maxZ - CLEARANCE; z++) {
					if (seen.add(BlockPos.asLong(x, 0, z))) choices.add(new BlockPos(x, ref.bell().getY(), z));
				}
			}
		}
		Collections.shuffle(choices, new java.util.Random(random.nextLong()));
		return choices;
	}
}
