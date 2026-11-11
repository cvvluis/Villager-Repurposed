package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Built;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Architect;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.core.registries.Registries;

/** Shared needs and extent calculations for the village development planner. */
public final class Development {
	public record ExpansionParcel(BlockPos center, int surfaceY, int half) {
		public ExpansionParcel {
			if (center == null || half < 1 || half > 8) throw new IllegalArgumentException("Invalid expansion parcel");
			center = center.immutable();
		}
	}

	/** Large enough for spread-out generated villages while keeping wall work bounded. */
	public static final int MAX_WALL_RADIUS = 192;
	/** Loaded chunks searched for original village starts when deriving the outer wall. */
	public static final int WALL_STRUCTURE_DISCOVERY_RADIUS = Math.max(Config.VILLAGE_RADIUS, MAX_WALL_RADIUS) + 16;
	/** POI queries are circular; this radius covers the corners of the supported square wall footprint. */
	public static final int HOME_POI_DISCOVERY_RADIUS = (int) Math.ceil(MAX_WALL_RADIUS * Math.sqrt(2.0));
	/** Include a neighboring village's meeting point whenever its home could be in our home scan area. */
	public static final int MEETING_POI_DISCOVERY_RADIUS = HOME_POI_DISCOVERY_RADIUS * 2;
	private static final int WALL_CLEARANCE = 14;
	/** One compact, saved natural pad prepared only while the next house is actually needed. */
	/** Covers the largest catalog house (half-width 7) plus one block for yard/approach clearance. */
	public static final int EXPANSION_PARCEL_HALF = 8;
	public static final int EXPANSION_PARCEL_CLEARANCE = 12;
	public static final int EXPANSION_PARCEL_MAX_RADIUS = Math.min(88, Config.VILLAGE_RADIUS - 8);
	/** Four outward steps lead to the outer ring; ten spaced bearings let the village fill it safely. */
	public static final int EXPANSION_PARCEL_OUTER_RING_DIRECTIONS = 10;
	/** The first fourteen parcels keep their saved geometry; later growth fills one staggered inner ring. */
	public static final int EXPANSION_PARCEL_BASE_COUNT = 4 + EXPANSION_PARCEL_OUTER_RING_DIRECTIONS;
	public static final int EXPANSION_PARCEL_INNER_RING_DIRECTIONS = 8;
	/** Default 17x17 housing support: fourteen legacy parcels plus eight additional inner-ring candidates. */
	public static final int EXPANSION_PARCEL_MAX_COUNT = EXPANSION_PARCEL_BASE_COUNT + EXPANSION_PARCEL_INNER_RING_DIRECTIONS;
	/** Maximum natural height difference villagers will cut down when preparing a new housing parcel. */
	public static final int EXPANSION_PARCEL_MAX_RELIEF = 2;
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	private static final java.util.Map<Long, Long> WALL_LIMIT_WARNED = new HashMap<>();

	private Development() {}

	/** Maintain a small housing reserve even when the population has been stable. */
	public static boolean needsHousing(long beds, int population) {
		return population > 0 && population < Config.MAX_VILLAGERS && beds <= population;
	}

	/** Do not pre-clear another parcel when a house (or another building) already owns active village work. */
	public static boolean needsExpansionPreparation(long beds, int population, boolean hasActiveBuild) {
		return !hasActiveBuild && needsHousing(beds, population);
	}

	/**
	 * A bounded candidate pad outside the current home ring. The first plots move outward; later plots fill
	 * a ten-bearing outer ring while keeping the final corners within the supported house-search radius.
	 */
	public static BlockPos expansionParcelCenter(BlockPos bell, long homeCount, int outerHomeRadius,
		int side, int half, int maxRadius) {
		return expansionParcelCenter(bell, homeCount, outerHomeRadius, 0L, side, half, maxRadius);
	}

	/**
	 * A saved parcel ordinal keeps later housing plots distributed even when an older save has incomplete
	 * HOME POIs. The outer ring uses ten bearings whose adjacent pads retain the configured clearance.
	 */
	public static BlockPos expansionParcelCenter(BlockPos bell, long homeCount, int outerHomeRadius,
		long parcelOrdinal, int side, int half, int maxRadius) {
		if (bell == null || half < 1 || half > 8 || parcelOrdinal < 0 || maxRadius <= half + 1) return null;
		int supportedRadius = Math.min(maxRadius, Config.VILLAGE_RADIUS - 8);
		long cornerRadius = (long) Math.ceil(Math.sqrt(2.0) * half);
		long maxCenterRadius = (long) supportedRadius - cornerRadius;
		if (parcelOrdinal >= EXPANSION_PARCEL_BASE_COUNT) {
			List<BlockPos> innerRing = innerExpansionParcels(bell, half, maxCenterRadius);
			long limit = EXPANSION_PARCEL_BASE_COUNT + innerRing.size();
			if (parcelOrdinal >= limit || innerRing.isEmpty()) return null;
			return innerRing.get(Math.floorMod(side, innerRing.size()));
		}
		long step = (long) EXPANSION_PARCEL_CLEARANCE + half;
		long firstCenterRadius = Math.max(18L, step);
		if (maxCenterRadius < firstCenterRadius) return null;
		long additionalHomes = homeCount <= 1L ? 0L : homeCount - 1L;
		long byHomeCount = additionalHomes > supportedRadius ? supportedRadius + 1L : 18L + additionalHomes * 2L;
		long byParcelOrdinal = parcelOrdinal < 3L
			? firstCenterRadius + parcelOrdinal * step
			: maxCenterRadius;
		long desiredRadius = Math.min(maxCenterRadius, Math.max(firstCenterRadius, Math.max(byParcelOrdinal,
			Math.max((long) Math.max(0, outerHomeRadius) + step, byHomeCount))));
		int centerRadius = (int) desiredRadius;
		if (centerRadius <= 0) return null;
		if (parcelOrdinal < 4L) {
			return switch (Math.floorMod(side, 4)) {
				case 0 -> bell.offset(0, 0, -centerRadius);
				case 1 -> bell.offset(centerRadius, 0, 0);
				case 2 -> bell.offset(0, 0, centerRadius);
				default -> bell.offset(-centerRadius, 0, 0);
			};
		}
		int bearing = Math.floorMod(side - 4, EXPANSION_PARCEL_OUTER_RING_DIRECTIONS);
		double angle = -Math.PI / 2.0 + bearing * (Math.PI * 2.0 / EXPANSION_PARCEL_OUTER_RING_DIRECTIONS);
		int x = bell.getX() + (int) Math.round(Math.cos(angle) * centerRadius);
		int z = bell.getZ() + (int) Math.round(Math.sin(angle) * centerRadius);
		return new BlockPos(x, bell.getY(), z);
	}

	/** Number of distinct placement bearings available for the persisted parcel ordinal. */
	public static int expansionParcelSideCount(long parcelOrdinal, int half, int maxRadius) {
		if (parcelOrdinal < 0 || half < 1 || half > 8 || maxRadius <= half + 1) return 0;
		if (parcelOrdinal < EXPANSION_PARCEL_BASE_COUNT) return EXPANSION_PARCEL_OUTER_RING_DIRECTIONS;
		int count = innerExpansionParcels(BlockPos.ZERO, half,
			(long) Math.min(maxRadius, Config.VILLAGE_RADIUS - 8) - (long) Math.ceil(Math.sqrt(2.0) * half)).size();
		return parcelOrdinal < EXPANSION_PARCEL_BASE_COUNT + count ? count : 0;
	}

	/** The ordinal limit is geometry-dependent for worlds with a smaller configured housing radius. */
	public static long expansionParcelLimit(int half, int maxRadius) {
		if (half < 1 || half > 8 || maxRadius <= half + 1) return 0L;
		long maxCenterRadius = (long) Math.min(maxRadius, Config.VILLAGE_RADIUS - 8)
			- (long) Math.ceil(Math.sqrt(2.0) * half);
		return EXPANSION_PARCEL_BASE_COUNT + innerExpansionParcels(BlockPos.ZERO, half, maxCenterRadius).size();
	}

	/**
	 * Eight staggered positions fill the largest gaps between the original outer ring and the central village.
	 * Keep them clear of that ring and of each other; the live POI/structure survey rejects overlaps with the
	 * village's actual first four cardinal plots.
	 */
	private static List<BlockPos> innerExpansionParcels(BlockPos bell, int half, long maxCenterRadius) {
		long innerRadius = maxCenterRadius - (2L * half + EXPANSION_PARCEL_CLEARANCE + 4L);
		if (innerRadius < 2L * half + EXPANSION_PARCEL_CLEARANCE || innerRadius > maxCenterRadius) return List.of();
		List<BlockPos> candidates = new ArrayList<>(EXPANSION_PARCEL_INNER_RING_DIRECTIONS);
		long maxRadiusSquared = maxCenterRadius * maxCenterRadius;
		for (int direction = 0; direction < EXPANSION_PARCEL_INNER_RING_DIRECTIONS; direction++) {
			double angle = -Math.PI / 2.0 + direction * (Math.PI / 4.0);
			int dx = (int) Math.round(Math.cos(angle) * innerRadius);
			int dz = (int) Math.round(Math.sin(angle) * innerRadius);
			BlockPos candidate = bell.offset(dx, 0, dz);
			if ((long) dx * dx + (long) dz * dz > maxRadiusSquared) continue;
			boolean clear = true;
			for (int outer = 0; outer < EXPANSION_PARCEL_OUTER_RING_DIRECTIONS; outer++) {
				double outerAngle = -Math.PI / 2.0 + outer * (Math.PI * 2.0 / EXPANSION_PARCEL_OUTER_RING_DIRECTIONS);
				int outerX = (int) Math.round(Math.cos(outerAngle) * maxCenterRadius);
				int outerZ = (int) Math.round(Math.sin(outerAngle) * maxCenterRadius);
				if (!expansionParcelClearOfHome(candidate, half, bell.offset(outerX, 0, outerZ), half,
					EXPANSION_PARCEL_CLEARANCE)) {
					clear = false;
					break;
				}
			}
			if (!clear) continue;
			for (BlockPos existing : candidates) {
				if (!expansionParcelClearOfHome(candidate, half, existing, half, EXPANSION_PARCEL_CLEARANCE)) {
					clear = false;
					break;
				}
			}
			if (clear) candidates.add(candidate.immutable());
		}
		return List.copyOf(candidates);
	}

	/** Checks the saved square expansion footprint without changing terrain. */
	public static boolean inExpansionParcel(BlockPos center, int half, BlockPos pos) {
		return center != null && pos != null && half >= 1 && half <= 8
			&& Math.abs(pos.getX() - center.getX()) <= half && Math.abs(pos.getZ() - center.getZ()) <= half;
	}

	/** Keep two square building pads apart by the requested edge-to-edge gap, including diagonal neighbors. */
	public static boolean expansionParcelClearOfHome(BlockPos parcel, int parcelHalf, BlockPos home, int homeHalf, int clearance) {
		if (parcel == null || home == null || parcelHalf < 1 || homeHalf < 1 || clearance < 0) return false;
		long gapX = Math.max(0L, Math.abs((long) parcel.getX() - home.getX()) - parcelHalf - homeHalf);
		long gapZ = Math.max(0L, Math.abs((long) parcel.getZ() - home.getZ()) - parcelHalf - homeHalf);
		return gapX * gapX + gapZ * gapZ >= (long) clearance * clearance;
	}

	/** Bound the amount of natural soil that road builders may cut to level a prepared house pad. */
	public static boolean expansionReliefSupported(int lowSurfaceY, int highSurfaceY) {
		return highSurfaceY >= lowSurfaceY && highSurfaceY - lowSurfaceY <= EXPANSION_PARCEL_MAX_RELIEF;
	}

	/** Start storage before the village chest becomes completely full; never evict an active plan. */
	public static boolean needsStorage(Container combined, VillageData data, ServerLevel level) {
		if (combined == null || !storageSlotNeeded(data, level)) return false;
		int occupied = 0;
		for (int i = 0; i < combined.getContainerSize(); i++) {
			if (!combined.getItem(i).isEmpty()) occupied++;
		}
		return occupied * 4 >= combined.getContainerSize() * 3;
	}

	/** Keep a hamlet's first civic service chain ahead of capacity-only storage sheds. */
	public static boolean deferStorageExpansion(String pendingMilestone) {
		return Blueprints.GRANARY.equals(pendingMilestone) || Blueprints.TOOLSHED.equals(pendingMilestone)
			|| Blueprints.SCHOOL.equals(pendingMilestone) || Blueprints.KITCHEN.equals(pendingMilestone);
	}

	private static boolean storageSlotNeeded(VillageData data, ServerLevel level) {
		if (data.extra.lager.isEmpty()) return true;
		return Lager.chestWithRoom(level, data) == null && data.extra.lagerMore.size() < Lager.MAX_EXTRA_SHEDS;
	}

	/** Radius enclosing all known original structure pieces, homes, and mod-built structures. */
	public static int neededWallRadius(ServerLevel level, VillageRef ref) {
		BlockPos bell = ref.bell();
		BoundingBox footprint = bounds(level, ref, WALL_STRUCTURE_DISCOVERY_RADIUS);
		int extent = edgeDistance(footprint, bell);
		if (extent < Config.WALL_MIN_VILLAGE_RADIUS) return 0;
		int radius = extent + WALL_CLEARANCE;
		// 8 * radius is the persisted column cursor; radius stays in its low byte beside the prior radius.
		if (radius > MAX_WALL_RADIUS) {
			long now = level.getGameTime();
			if (now >= WALL_LIMIT_WARNED.getOrDefault(bell.asLong(), 0L)) {
				WALL_LIMIT_WARNED.put(bell.asLong(), now + 12000L);
				LOGGER.warn("Village at {} spans {} blocks; refusing to start a wall with the {}-block safety limit. No wall radius was clipped.",
					bell.toShortString(), extent, MAX_WALL_RADIUS);
			}
			return 0;
		}
		return radius;
	}

	/** Combined horizontal footprint of the bell, original generated village, and tracked mod buildings. */
	public static BoundingBox bounds(ServerLevel level, VillageRef ref) {
		return bounds(level, ref, Config.VILLAGE_RADIUS + 16);
	}

	/** Caller-selected loaded-chunk search range; wall sizing passes the full supported wall footprint. */
	private static BoundingBox bounds(ServerLevel level, VillageRef ref, int structureDiscoveryRadius) {
		BlockPos bell = ref.bell();
		BoundingBox observed = new BoundingBox(bell.getX(), bell.getY(), bell.getZ(), bell.getX(), bell.getY(), bell.getZ());
		Set<String> visited = new HashSet<>();
		var structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		int minChunkX = (bell.getX() - structureDiscoveryRadius) >> 4;
		int maxChunkX = (bell.getX() + structureDiscoveryRadius) >> 4;
		int minChunkZ = (bell.getZ() - structureDiscoveryRadius) >> 4;
		int maxChunkZ = (bell.getZ() + structureDiscoveryRadius) >> 4;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				var chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (var entry : chunk.getAllStarts().entrySet()) {
					var id = structureRegistry.getKey(entry.getKey());
					if (id == null || !id.getPath().toLowerCase(java.util.Locale.ROOT).contains("village")) continue;
					StructureStart start = entry.getValue();
					if (!start.isValid() || !ownsVillageStart(start.getBoundingBox(), bell)) continue;
					for (StructurePiece piece : start.getPieces()) {
						BoundingBox box = piece.getBoundingBox();
						String key = box.minX() + ":" + box.minZ() + ":" + box.maxX() + ":" + box.maxZ();
						if (visited.add(key)) observed = union(observed, box);
					}
				}
			}
		}
		var meetings = level.getPoiManager().getInRange(h -> h.is(PoiTypes.MEETING), bell,
			MEETING_POI_DISCOVERY_RADIUS, PoiManager.Occupancy.ANY).map(p -> p.getPos().immutable()).toList();
		var homes = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), bell,
			HOME_POI_DISCOVERY_RADIUS, PoiManager.Occupancy.ANY).map(p -> p.getPos().immutable()).toList();
		for (BlockPos pos : homes) {
			if (!belongsToBell(pos, bell, meetings)) continue;
			observed = union(observed, new BoundingBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ()));
		}
		for (Built built : Builder.builtOf(ref)) {
			int half = builtHalf(built);
			observed = union(observed, new BoundingBox(built.origin().getX() - half, built.origin().getY(), built.origin().getZ() - half,
				built.origin().getX() + half, built.origin().getY() + 16, built.origin().getZ() + half));
		}
		// A cleared outer housing pad is already part of village growth before its house receives a HOME POI.
		ExpansionParcel expansion = Roads.preparedExpansionParcel(ref).orElse(null);
		if (expansion != null) {
			BlockPos center = new BlockPos(expansion.center().getX(), expansion.surfaceY(), expansion.center().getZ());
			observed = union(observed, footprint(center, expansion.half(), 16));
		}
		// Include the current civic center and managed additions even when their chunks/POIs are not loaded.
		BlockPos plaza = Market.of(ref).plaza().orElse(null);
		if (plaza != null) observed = union(observed, footprint(plaza, 12, 8));
		for (MarketData.Shop shop : Market.of(ref).shops()) observed = union(observed, footprint(shop.origin(), 10, 8));
		for (BlockPos origin : Specials.all(ref).values()) observed = union(observed, footprint(origin, 12, 12));
		for (BlockPos center : Lager.centers(ref.data())) observed = union(observed, footprint(center, 8, 8));
		for (BlockPos origin : new BlockPos[] {
			ref.data().chest.orElse(null), ref.data().planOrigin.orElse(null), ref.data().extra.school.orElse(null),
			ref.data().extra.board.orElse(null), ref.data().extra.pen.orElse(null)
		}) if (origin != null) observed = union(observed, footprint(origin, 4, 4));
		VillageExtent previous = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.VILLAGE_EXTENT, null);
		VillageExtent remembered = previous == null ? VillageExtent.of(observed) : previous.union(observed);
		if (!remembered.equals(previous)) {
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.VILLAGE_EXTENT, remembered);
			ref.dirty();
		}
		return remembered.box();
	}

	/** Checks a small loaded neighborhood against the actual pieces of this village's generated structures. */
	public static boolean nearOriginalStructure(ServerLevel level, VillageRef ref, BlockPos pos, int padding) {
		if (ref == null || pos == null || padding < 0) return false;
		var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		int chunkPadding = Math.max(1, (padding + 32) >> 4);
		int minChunkX = (pos.getX() >> 4) - chunkPadding;
		int maxChunkX = (pos.getX() >> 4) + chunkPadding;
		int minChunkZ = (pos.getZ() >> 4) - chunkPadding;
		int maxChunkZ = (pos.getZ() >> 4) + chunkPadding;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
			var chunk = level.getChunkSource().getChunkNow(cx, cz);
			if (chunk == null) continue;
			for (var entry : chunk.getAllStarts().entrySet()) {
				var id = structures.getKey(entry.getKey());
				if (id == null || !id.getPath().toLowerCase(java.util.Locale.ROOT).contains("village")) continue;
				StructureStart start = entry.getValue();
				if (!start.isValid() || !ownsVillageStart(start.getBoundingBox(), ref.bell())) continue;
				for (StructurePiece piece : start.getPieces()) {
					BoundingBox box = piece.getBoundingBox();
					if (pos.getX() >= box.minX() - padding && pos.getX() <= box.maxX() + padding
						&& pos.getZ() >= box.minZ() - padding && pos.getZ() <= box.maxZ() + padding
						&& pos.getY() >= box.minY() - padding && pos.getY() <= box.maxY() + padding) return true;
				}
			}
		}
		return false;
	}

	private static BoundingBox union(BoundingBox a, BoundingBox b) {
		return new BoundingBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
			Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
	}

	private static BoundingBox footprint(BlockPos center, int radius, int height) {
		return new BoundingBox(center.getX() - radius, center.getY() - 2, center.getZ() - radius,
			center.getX() + radius, center.getY() + height, center.getZ() + radius);
	}

	/** A generated village start belongs to this bell only when its complete start footprint contains the bell in X/Z. */
	public static boolean ownsVillageStart(BoundingBox box, BlockPos pos) {
		return pos.getX() >= box.minX() && pos.getX() <= box.maxX() && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ();
	}

	private static boolean belongsToBell(BlockPos point, BlockPos bell, java.util.List<BlockPos> meetings) {
		if (meetings.isEmpty()) return false;
		BlockPos nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (BlockPos meeting : meetings) {
			double distance = meeting.distSqr(point);
			if (distance < nearestDistance) {
				nearest = meeting;
				nearestDistance = distance;
			}
		}
		return bell.equals(nearest);
	}

	/** Horizontal midpoint of {@link #bounds}; keeps the village bell's Y for map/plaza targeting. */
	public static BlockPos center(ServerLevel level, VillageRef ref) {
		BoundingBox box = bounds(level, ref);
		return new BlockPos(box.minX() + (box.maxX() - box.minX()) / 2, ref.bell().getY(),
			box.minZ() + (box.maxZ() - box.minZ()) / 2);
	}

	private static int edgeDistance(BoundingBox box, BlockPos bell) {
		return Math.max(Math.max(Math.abs(box.minX() - bell.getX()), Math.abs(box.maxX() - bell.getX())),
			Math.max(Math.abs(box.minZ() - bell.getZ()), Math.abs(box.maxZ() - bell.getZ())));
	}

	private static int builtHalf(Built built) {
		// Fields are variant-sized, and their paid fence plus corner decoration extends two blocks past crops.
		if (built.type().equals(Blueprints.FARM)) return Math.max(3, built.variant() / 10) + 2;
		if (!built.type().equals(Blueprints.HOUSE) && !built.type().equals(Blueprints.STORAGE)
			&& !built.type().equals(Blueprints.SCHOOL) && !Blueprints.special(built.type())) return 4;
		Architect.Kind kind = switch (built.type()) {
			case Blueprints.SCHOOL -> Architect.Kind.SCHOOL;
			case Blueprints.STORAGE -> Architect.Kind.STORAGE;
			case Blueprints.GRANARY -> Architect.Kind.GRANARY;
			case Blueprints.TOOLSHED -> Architect.Kind.TOOLSHED;
			case Blueprints.KITCHEN -> Architect.Kind.KITCHEN;
			case Blueprints.STATION -> Architect.Kind.STATION;
			default -> Architect.Kind.HOUSE;
		};
		return Architect.designForPlan(kind, built.variant()).half() + 3;
	}
}
