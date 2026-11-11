package de.villagerplus.village;

import de.villagerplus.build.HouseTemplate;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

/** Bauplatz-Suche: ebene, freie Flaechen ohne Baeume, Wasser oder bestehende Gebaeude. */
public final class Sites {
	private Sites() {
	}

	/** Mindestabstand zu bestehenden Gebaeuden in Bloecken (Standard 3; Notloesung in engen Doerfern 2). */
	public static int margin = 3;
	/** Clear belt required around new houses, including parcels beside already generated village homes. */
	public static final int HOUSE_MARGIN = 12;
	private static final int HOUSE_POI_CENTER_FALLBACK = HOUSE_MARGIN + 3;
	private static final int MAX_HOUSE_POIS_TO_CHECK = 256;
	private static final int HOUSE_STRUCTURE_START_PAD = HouseTemplate.MAX_FOOTPRINT_SIZE;
	private static final int MAX_HOUSE_STRUCTURE_CHUNKS = 512;
	private static final int MAX_HOUSE_STRUCTURE_STARTS = 512;
	private static final int MAX_HOUSE_STRUCTURE_PIECES = 4096;
	private static final int HOUSE_ROAD_REACH = 96;
	private static final int HOUSE_ROAD_SEARCH_LIMIT = 8192;
	private static final int MAX_HOUSE_STRUCTURE_CACHE_CHUNKS = 512;
	private record StructureStartKey(String id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }
	private record StructureChunkKey(ServerLevel level, int x, int z) { }
	private record StructurePieceBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		private static StructurePieceBounds copyOf(BoundingBox box) {
			return new StructurePieceBounds(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
		}
		private BoundingBox box() { return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ); }
	}
	private record VillageStartSnapshot(StructureStartKey key, StructurePieceBounds bounds, int pieceCount,
		List<StructurePieceBounds> buildingPieces) { }
	private record StructureChunkSnapshot(ChunkAccess chunk, Map<Structure, StructureStart> sourceStarts,
		List<VillageStartSnapshot> villageStarts, boolean complete) { }
	private static long houseStructureCacheTick = Long.MIN_VALUE;
	private static final Map<StructureChunkKey, StructureChunkSnapshot> HOUSE_STRUCTURE_CACHE = new LinkedHashMap<>(32, 0.75F, true);

	/** One bounded portion of the deterministic site scan. */
	public record SearchPage(BlockPos site, int nextCandidate, boolean complete) { }

	/** Legacy bell-relative elevation bound; new site searches should use the loaded-world overload below. */
	public static boolean villageLevel(BlockPos around, int y) {
		return Math.abs(y - around.getY()) <= 12;
	}

	/** Use a nearby connected road surface as the elevation reference when a bell is mounted above street level. */
	public static boolean villageLevel(ServerLevel level, BlockPos around, int y) {
		return villageLevel(villageReferenceY(level, around), y);
	}

	public static boolean villageLevel(int referenceY, int y) {
		return Math.abs(y - referenceY) <= 12;
	}

	/** Finds a connected, already loaded road surface near the bell, including directly beneath a raised bell. */
	public static int villageReferenceY(ServerLevel level, BlockPos around) {
		return villageReferenceY(level, around, p -> false);
	}

	public static int villageReferenceY(ServerLevel level, VillageRef ref) {
		return villageReferenceY(level, ref.bell(), p -> Roads.trustedVillagePathCell(level, ref, p));
	}

	public static int villageReferenceY(ServerLevel level, BlockPos around, Predicate<BlockPos> trustedRoadCell) {
		Set<Long> road = new HashSet<>();
		Set<Long> trusted = new HashSet<>();
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			for (int dy = -32; dy <= 12; dy++) {
				BlockPos p = around.offset(dx, dy, dz);
				if (!level.hasChunkAt(p)) continue;
				BlockState state = level.getBlockState(p);
				if (state.is(Blocks.DIRT_PATH)) {
					road.add(p.asLong());
				} else if (roadMaterial(state)) {
					if (trustedRoadCell.test(p)) trusted.add(p.asLong());
				}
			}
		}
		BlockPos nearestRoad = null;
		int nearestDistance = Integer.MAX_VALUE;
		Set<Long> checked = new HashSet<>();
		for (long packed : road) {
			if (!checked.add(packed)) continue;
			BlockPos seed = BlockPos.of(packed);
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			Set<Long> component = new HashSet<>();
			queue.add(seed);
			component.add(packed);
			while (!queue.isEmpty()) {
				BlockPos current = queue.removeFirst();
				for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
					BlockPos horizontal = current.relative(direction);
					for (int dy = -1; dy <= 1; dy++) {
						BlockPos next = horizontal.offset(0, dy, 0);
						if (road.contains(next.asLong()) && component.add(next.asLong())) queue.addLast(next);
					}
				}
			}
			checked.addAll(component);
			if (component.size() < 3) continue;
			for (long tile : component) {
				BlockPos p = BlockPos.of(tile);
				int distance = Math.abs(p.getX() - around.getX()) + Math.abs(p.getZ() - around.getZ());
				if (distance < nearestDistance || (distance == nearestDistance && nearestRoad != null && p.getY() < nearestRoad.getY())) {
					nearestDistance = distance;
					nearestRoad = p;
				}
			}
		}
		for (long tile : trusted) {
			BlockPos p = BlockPos.of(tile);
			int distance = Math.abs(p.getX() - around.getX()) + Math.abs(p.getZ() - around.getZ());
			if (distance < nearestDistance || (distance == nearestDistance && nearestRoad != null && p.getY() < nearestRoad.getY())) {
				nearestDistance = distance;
				nearestRoad = p;
			}
		}
		return nearestRoad == null ? around.getY() : nearestRoad.getY() + 1;
	}

	private static boolean roadMaterial(BlockState state) {
		return state.is(Blocks.GRAVEL) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.SMOOTH_SANDSTONE) || state.is(Blocks.SPRUCE_PLANKS);
	}


	/** Findet einen Mittelpunkt (erste freie Luftschicht ueber dem Boden) im Ring minDist..maxDist um {@code around}. */
	public static BlockPos find(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int half, int headroom,
		int attempts) {
		return find(level, around, r, minDist, maxDist, half, headroom, attempts, villageReferenceY(level, around));
	}

	public static BlockPos find(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int half, int headroom,
		int attempts, int referenceY) {
		return find(level, around, r, minDist, maxDist, half, headroom, attempts, referenceY, p -> true);
	}

	/** Random search counterpart that also honors caller-specific plan filters. */
	public static BlockPos find(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int half, int headroom,
		int attempts, int referenceY, Predicate<BlockPos> planFits) {
		for (int i = 0; i < attempts; i++) {
			double angle = r.nextDouble() * Math.PI * 2;
			double dist = minDist + r.nextInt(Math.max(1, maxDist - minDist + 1));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * dist);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, around.getY(), z))) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (!villageLevel(referenceY, y)) continue;
			BlockPos c = new BlockPos(x, y, z);
			if (ok(level, c, half, headroom) && planFits.test(c)) {
				return c;
			}
		}
		return null;
	}

	/** Bounded full-area fallback after random placement retries fail for a large civic building. */
	public static BlockPos findSystematic(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> fits) {
		return findSystematic(level, around, random, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, fits,
			villageReferenceY(level, around));
	}

	public static BlockPos findSystematic(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> fits, int referenceY) {
		return findDistributed(level, around, random, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, false, fits, referenceY);
	}

	/** Uneven-terrain counterpart to {@link #findSystematic}. */
	public static BlockPos findTolerantSystematic(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> fits) {
		return findTolerantSystematic(level, around, random, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, fits,
			villageReferenceY(level, around));
	}

	public static BlockPos findTolerantSystematic(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> fits, int referenceY) {
		return findDistributed(level, around, random, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, true, fits, referenceY);
	}

	/** Number of candidates in the deterministic distributed ring used by the systematic searches. */
	public static int systematicCandidateCount(int minDist, int maxDist) {
		if (minDist < 0 || maxDist < minDist) return 0;
		final double spacing = 4.0;
		double inner = Math.max(0, minDist - spacing / 2);
		double outer = maxDist + spacing / 2;
		double sampledArea = Math.PI * (outer * outer - inner * inner);
		return Math.max(1, Math.min(4096, (int) Math.ceil(sampledArea / (spacing * spacing))));
	}

	/**
	 * Searches only {@code candidateBudget} positions and returns the next index for a later tick.
	 * Loaded columns are remembered in {@code visitedColumns}, so a resumed or re-probed scan does not
	 * repeat terrain and plan checks for the same column. Unloaded columns are left unmarked and can be
	 * considered if they become loaded during a later sweep.
	 */
	public static SearchPage findSystematicPage(ServerLevel level, BlockPos around, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, boolean tolerant, int startCandidate,
		int candidateBudget, long phaseSeed, Set<Long> visitedColumns, Predicate<BlockPos> fits, int referenceY) {
		int total = systematicCandidateCount(minDist, maxDist);
		if (total == 0 || candidateBudget <= 0) return new SearchPage(null, Math.max(0, startCandidate), true);
		int start = Math.max(0, Math.min(total, startCandidate));
		int end = Math.min(total, start + candidateBudget);
		double phase = RandomSource.create(phaseSeed).nextDouble() * Math.PI * 2.0;
		Set<Long> pageColumns = new HashSet<>();
		for (int i = start; i < end; i++) {
			double fraction = (i + 0.5) / total;
			double radius = Math.sqrt(minDist * (double) minDist
				+ (maxDist * (double) maxDist - minDist * (double) minDist) * fraction);
			double angle = phase + i * Math.PI * (3.0 - Math.sqrt(5.0));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * radius);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * radius);
			long column = BlockPos.asLong(x, 0, z);
			if (visitedColumns.contains(column) || !pageColumns.add(column)) continue;
			BlockPos probe = new BlockPos(x, around.getY(), z);
			if (!level.hasChunkAt(probe)) continue;
			visitedColumns.add(column);
			BlockPos candidate = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (!villageLevel(referenceY, candidate.getY())) continue;
			if (tolerant) {
				int base = tolerantBase(level, candidate, minX, maxX, minZ, maxZ, headroom);
				if (base == Integer.MIN_VALUE) continue;
				candidate = new BlockPos(x, base, z);
			} else if (!okRect(level, candidate, minX, maxX, minZ, maxZ, headroom)) {
				continue;
			}
			if (fits.test(candidate)) return new SearchPage(candidate, i + 1, i + 1 >= total);
		}
		return new SearchPage(null, end, end >= total);
	}

	/**
	 * House counterpart to {@link #findSystematicPage}: natural-house preparation validates grade and safe clearing
	 * itself, so this scan must not discard a valid ground plot just because a rooted tree covers its headroom.
	 */
	public static SearchPage findHouseSitePage(ServerLevel level, BlockPos around, int minDist, int maxDist,
		int startCandidate, int candidateBudget, long phaseSeed, Set<Long> visitedColumns,
		Function<BlockPos, BlockPos> resolveAnchor, int referenceY) {
		int total = systematicCandidateCount(minDist, maxDist);
		if (total == 0 || candidateBudget <= 0) return new SearchPage(null, Math.max(0, startCandidate), true);
		int start = Math.max(0, Math.min(total, startCandidate));
		int end = Math.min(total, start + candidateBudget);
		double phase = RandomSource.create(phaseSeed).nextDouble() * Math.PI * 2.0;
		Set<Long> pageColumns = new HashSet<>();
		for (int i = start; i < end; i++) {
			double fraction = (i + 0.5) / total;
			double radius = Math.sqrt(minDist * (double) minDist
				+ (maxDist * (double) maxDist - minDist * (double) minDist) * fraction);
			double angle = phase + i * Math.PI * (3.0 - Math.sqrt(5.0));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * radius);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * radius);
			long column = BlockPos.asLong(x, 0, z);
			if (visitedColumns.contains(column) || !pageColumns.add(column)) continue;
			BlockPos probe = new BlockPos(x, around.getY(), z);
			if (!level.hasChunkAt(probe)) continue;
			visitedColumns.add(column);
			BlockPos candidate = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (!villageLevel(referenceY, candidate.getY())) continue;
			BlockPos anchor = resolveAnchor.apply(candidate);
			if (anchor != null) return new SearchPage(anchor, i + 1, i + 1 >= total);
		}
		return new SearchPage(null, end, end >= total);
	}

	private static BlockPos findDistributed(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, boolean tolerant, Predicate<BlockPos> fits) {
		return findDistributed(level, around, random, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, tolerant, fits,
			villageReferenceY(level, around));
	}

	private static BlockPos findDistributed(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, boolean tolerant, Predicate<BlockPos> fits, int referenceY) {
		if (minDist < 0 || maxDist < minDist) return null;
		int candidates = systematicCandidateCount(minDist, maxDist);
		double phase = random.nextDouble() * Math.PI * 2.0;
		Set<Long> seen = new HashSet<>(candidates);
		for (int i = 0; i < candidates; i++) {
			double fraction = (i + 0.5) / candidates;
			double radius = Math.sqrt(minDist * (double) minDist
				+ (maxDist * (double) maxDist - minDist * (double) minDist) * fraction);
			double angle = phase + i * Math.PI * (3.0 - Math.sqrt(5.0));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * radius);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * radius);
			if (!seen.add(BlockPos.asLong(x, 0, z)) || !level.hasChunkAt(new BlockPos(x, around.getY(), z))) continue;
			BlockPos candidate = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (!villageLevel(referenceY, candidate.getY())) continue;
			if (tolerant) {
				int base = tolerantBase(level, candidate, minX, maxX, minZ, maxZ, headroom);
				if (base == Integer.MIN_VALUE) continue;
				candidate = new BlockPos(x, base, z);
			} else if (!okRect(level, candidate, minX, maxX, minZ, maxZ, headroom)) {
				continue;
			}
			if (fits.test(candidate)) return candidate;
		}
		return null;
	}

	/** Zufaelliger Kandidat im Ring minDist..maxDist um {@code around}: erste freie Luftschicht ueber dem Boden (null = Chunk nicht geladen). */
	public static BlockPos candidate(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist) {
		return candidate(level, around, r, minDist, maxDist, villageReferenceY(level, around));
	}

	public static BlockPos candidate(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int referenceY) {
		double angle = r.nextDouble() * Math.PI * 2;
		double dist = minDist + r.nextInt(Math.max(1, maxDist - minDist + 1));
		int x = around.getX() + (int) Math.round(Math.cos(angle) * dist);
		int z = around.getZ() + (int) Math.round(Math.sin(angle) * dist);
		if (!level.hasChunkAt(new BlockPos(x, around.getY(), z))) {
			return null;
		}
		BlockPos candidate = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		return villageLevel(referenceY, candidate.getY()) ? candidate : null;
	}

	/** Quadratischer Bauplatz um c (Innen |d| <= half, Rand 1 Block). */
	public static boolean ok(ServerLevel level, BlockPos c, int half, int headroom) {
		return okRect(level, c, -half, half, -half, half, headroom);
	}

	/** Rechteckiger Bauplatz relativ zu c: innen muss alles eben und frei sein, im 1-Block-Rand darf nichts hoeher aufragen. */
	public static boolean okRect(ServerLevel level, BlockPos c, int minX, int maxX, int minZ, int maxZ, int headroom) {
		if (Market.reserved(level, c, Math.max(Math.max(Math.abs(minX), Math.abs(maxX)), Math.max(Math.abs(minZ), Math.abs(maxZ))))) return false;
		for (int dx = minX - 1; dx <= maxX + 1; dx++) {
			for (int dz = minZ - 1; dz <= maxZ + 1; dz++) {
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (!level.hasChunkAt(new BlockPos(x, c.getY(), z))) {
					return false;
				}
				int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				boolean inner = dx >= minX && dx <= maxX && dz >= minZ && dz <= maxZ;
				if (!inner) {
					if (h > c.getY() + 1) {
						return false;
					}
					continue;
				}
				if (h != c.getY()) {
					return false;
				}
				BlockPos ground = new BlockPos(x, c.getY() - 1, z);
				BlockState gs = level.getBlockState(ground);
				if (!gs.isFaceSturdy(level, ground, Direction.UP) || !gs.getFluidState().isEmpty()
					|| gs.is(Blocks.FARMLAND) || gs.is(Blocks.DIRT_PATH) || gs.is(BlockTags.LEAVES)) {
					return false;
				}
				for (int dy = 0; dy < headroom; dy++) {
					BlockState s = level.getBlockState(new BlockPos(x, c.getY() + dy, z));
					if (!s.isAir() && !s.canBeReplaced()) {
						return false;
					}
				}
			}
		}
		return !touchesBuilding(level, c, minX, maxX, minZ, maxZ, headroom);
	}

	/**
	 * Abstandshalter: Im Ring von 2 Bloecken um den Bauplatz (Dachueberstand, Vorbau, Schornstein, Gasse) darf kein bestehendes
	 * Gebaeude stehen - sonst wuerden Haeuser ineinander gebaut.
	 */
	public static boolean touchesBuilding(ServerLevel level, BlockPos c, int minX, int maxX, int minZ, int maxZ, int headroom) {
		return buildingClearance(level, c, minX, maxX, minZ, maxZ, headroom, margin, false) != Clearance.CLEAR;
	}

	private enum Clearance { CLEAR, BUILDING, UNLOADED }

	private static Clearance buildingClearance(ServerLevel level, BlockPos c, int minX, int maxX, int minZ, int maxZ, int headroom, int m, boolean houseClearance) {
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int dx = minX - m; dx <= maxX + m; dx++) {
			for (int dz = minZ - m; dz <= maxZ + m; dz++) {
				boolean inner = dx >= minX && dx <= maxX && dz >= minZ && dz <= maxZ;
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (!level.hasChunkAt(q.set(x, c.getY(), z))) {
					return Clearance.UNLOADED;
				}
				for (int y = c.getY() - (inner ? 3 : 1); y <= c.getY() + headroom + 3; y++) {
					BlockState s = level.getBlockState(q.set(x, y, z));
					if (s.isAir() || s.canBeReplaced() || s.getFluidState().isEmpty() == false) {
						continue;
					}
					if (VillageStyle.natural(s) || s.is(BlockTags.LEAVES) || s.is(BlockTags.OVERWORLD_NATURAL_LOGS) || s.is(Blocks.SNOW)) {
						continue;
					}
					// Kleinkram (Zaeune, Laternen, Schilder, Fackeln, Toepfe, Pflanzen) zaehlt nicht als Gebaeude.
					if (!s.isCollisionShapeFullBlock(level, q) && !(s.getBlock() instanceof net.minecraft.world.level.block.StairBlock)
						&& !(s.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) && !(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
						continue;
					}
					if (houseClearance && (s.is(ConventionalBlockTags.ORES) || (!inner && isGeneratedVillageRoadGround(level, q, s)))) {
						continue;
					}
					if (houseClearance) {
						String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
						PLOT_BLOCKERS.merge(block, 1, Integer::sum);
						PLOT_BLOCKER_EXAMPLES.putIfAbsent(block, q.toShortString() + (inner ? " (Grundriss)" : " (Rand)"));
					}
					return Clearance.BUILDING;
				}
			}
		}
		return Clearance.CLEAR;
	}

	/** Existing CTOV roads follow the terrain, so their saved piece bounds identify columns, not exact heights. */
	public static boolean isGeneratedVillageRoadGround(ServerLevel level, BlockPos ground, BlockState state) {
		if (!state.is(Blocks.COBBLED_DEEPSLATE) && !state.is(Blocks.COBBLESTONE)
			&& !state.is(Blocks.SPRUCE_PLANKS) && !state.is(Blocks.DIRT_PATH)) return false;
		int x = ground.getX(), z = ground.getZ();
		if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) != ground.getY() + 1) return false;
		var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
		if (chunk == null) return false;
		var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		for (var reference : chunk.getAllReferences().entrySet()) {
			var id = structures.getKey(reference.getKey());
			if (id == null || !id.getNamespace().equals("ctov") || !id.getPath().contains("village_")) continue;
			for (long packed : reference.getValue()) {
				var startChunk = level.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed));
				if (startChunk == null) continue;
				var start = startChunk.getStartForStructure(reference.getKey());
				if (start == null || !start.isValid()) continue;
				for (StructurePiece piece : start.getPieces()) {
					BoundingBox bounds = piece.getBoundingBox();
					if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) continue;
					if (piece instanceof PoolElementStructurePiece pool && pool.getElement() instanceof SinglePoolElement element) {
						var template = element.getTemplateLocation();
						if (template.getNamespace().equals("ctov") && template.getPath().startsWith("village/")
							&& template.getPath().contains("/roads/")) return true;
					}
				}
			}
		}
		return false;
	}

	/** Extra house-only guard, applied after the normal terrain and zone checks. The supplied bounds
	 * must include the transformed roof and porch footprint. */
	public static boolean housePlot(ServerLevel level, BlockPos anchor, int minX, int maxX, int minZ, int maxZ,
		int headroom, Direction outward) {
		if (outward == null || outward == Direction.UP || outward == Direction.DOWN) return false;
		Clearance clearance = buildingClearance(level, anchor, minX, maxX, minZ, maxZ, headroom, HOUSE_MARGIN, true);
		if (clearance != Clearance.CLEAR) {
			PLOT_REJECT[clearance == Clearance.UNLOADED ? 5 : 0]++;
			return false;
		}
		int worldMinX = anchor.getX() + minX, worldMaxX = anchor.getX() + maxX;
		int worldMinZ = anchor.getZ() + minZ, worldMaxZ = anchor.getZ() + maxZ;
		if (!houseSpacingClear(level, anchor, worldMinX, worldMaxX, worldMinZ, worldMaxZ, headroom)) { PLOT_REJECT[1]++; return false; }
		int x = (minX + maxX) / 2, z = (minZ + maxZ) / 2;
		if (outward == Direction.EAST) x = maxX;
		else if (outward == Direction.WEST) x = minX;
		else if (outward == Direction.SOUTH) z = maxZ;
		else z = minZ;
		int previousY = anchor.getY();
		List<BlockPos> approach = new ArrayList<>(3);
		for (int i = 1; i <= 3; i++) {
			int px = anchor.getX() + x + outward.getStepX() * i;
			int pz = anchor.getZ() + z + outward.getStepZ() * i;
			BlockPos feet = walkableSurface(level, px, pz);
			if (feet == null || Math.abs(feet.getY() - previousY) > 1
				|| Math.abs(feet.getY() - anchor.getY()) > 1) { PLOT_REJECT[feet == null ? 2 : 3]++; return false; }
			approach.add(feet);
			previousY = feet.getY();
		}
		VillageRef ref = Villages.villageNear(level, anchor, 128);
		boolean streetOk = ref == null || !hasSavedVillageStreet(ref) || reachesSavedVillageStreet(level, approach.get(2), ref);
		if (!streetOk) PLOT_REJECT[4]++;
		return streetOk;
	}

	/** Diagnostic: house plot rejections [Gebaeude, Abstand, Zugang ohne Boden, Zugang Hoehe, Strasse, Ungeladen]. */
	public static final int[] PLOT_REJECT = new int[6];
	public static final Map<String, Integer> PLOT_BLOCKERS = new LinkedHashMap<>();
	public static final Map<String, String> PLOT_BLOCKER_EXAMPLES = new LinkedHashMap<>();

	/** Rejects a new house near actual existing home footprints and safely discoverable structure pieces. */
	private static boolean houseSpacingClear(ServerLevel level, BlockPos anchor, int minX, int maxX, int minZ, int maxZ, int headroom) {
		int centerX = (minX + maxX) / 2, centerZ = (minZ + maxZ) / 2;
		int halfX = (maxX - minX + 1) / 2, halfZ = (maxZ - minZ + 1) / 2;
		int searchRadius = (int) Math.ceil(Math.hypot(halfX, halfZ)) + HOUSE_MARGIN + HouseTemplate.MAX_FOOTPRINT_SIZE;
		List<BlockPos> homes = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME),
			new BlockPos(centerX, anchor.getY(), centerZ), searchRadius, PoiManager.Occupancy.ANY)
			.limit(MAX_HOUSE_POIS_TO_CHECK + 1L).map(poi -> poi.getPos().immutable()).toList();
		// A truncated POI query cannot prove safe spacing; defer this site instead of accepting an unchecked house.
		if (homes.size() > MAX_HOUSE_POIS_TO_CHECK) return false;
		for (BlockPos home : homes) {
			if (!level.hasChunkAt(home)) {
				if (homeCenterTooClose(home, minX, maxX, minZ, maxZ)) return false;
				continue;
			}
			HouseTemplate.Footprint footprint = HouseTemplate.homeSpacingFootprint(level, home);
			if (footprint == null) {
				// Preserve the established bed-center keepout when the bounded footprint is unknown.
				if (homeCenterTooClose(home, minX, maxX, minZ, maxZ)) return false;
			} else if (footprint.tooCloseTo(minX, maxX, minZ, maxZ, HOUSE_MARGIN)) {
				return false;
			}
		}
		return !hasUnbeddedStructureInClearance(level, anchor, minX, maxX, minZ, maxZ, headroom);
	}

	private static boolean homeCenterTooClose(BlockPos home, int minX, int maxX, int minZ, int maxZ) {
		int gapX = home.getX() < minX ? minX - home.getX() : home.getX() > maxX ? home.getX() - maxX : 0;
		int gapZ = home.getZ() < minZ ? minZ - home.getZ() : home.getZ() > maxZ ? home.getZ() - maxZ : 0;
		return gapX < HOUSE_POI_CENTER_FALLBACK && gapZ < HOUSE_POI_CENTER_FALLBACK;
	}

	/**
	 * Find structure-piece bounds from already loaded village starts. Thin roads and low fields are filtered
	 * by piece dimensions; no world blocks are sampled and no unloaded chunk is requested.
	 */
	private static boolean hasUnbeddedStructureInClearance(ServerLevel level, BlockPos anchor,
		int minX, int maxX, int minZ, int maxZ, int headroom) {
		int scanMinX = minX - HOUSE_MARGIN, scanMaxX = maxX + HOUSE_MARGIN;
		int scanMinZ = minZ - HOUSE_MARGIN, scanMaxZ = maxZ + HOUSE_MARGIN;
		int minY = anchor.getY() - 3, maxY = anchor.getY() + headroom + 3;
		int pad = HOUSE_STRUCTURE_START_PAD;
		int minChunkX = (scanMinX - pad) >> 4, maxChunkX = (scanMaxX + pad) >> 4;
		int minChunkZ = (scanMinZ - pad) >> 4, maxChunkZ = (scanMaxZ + pad) >> 4;
		long chunkCount = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
		if (chunkCount <= 0 || chunkCount > MAX_HOUSE_STRUCTURE_CHUNKS) return true;

		var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Set<StructureStartKey> visitedStarts = new HashSet<>();
		int inspectedPieces = 0;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				var chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				StructureChunkSnapshot snapshot = structureChunkSnapshot(level, cx, cz, chunk, structures);
				if (!snapshot.complete()) return true;
				for (VillageStartSnapshot start : snapshot.villageStarts()) {
					StructurePieceBounds bounds = start.bounds();
					if (!intersects(bounds.box(), scanMinX, scanMaxX, minY, maxY, scanMinZ, scanMaxZ)) continue;
					if (!visitedStarts.add(start.key())) continue;
					if (visitedStarts.size() > MAX_HOUSE_STRUCTURE_STARTS) return true;
					inspectedPieces += start.pieceCount();
					if (inspectedPieces > MAX_HOUSE_STRUCTURE_PIECES) return true;
					for (StructurePieceBounds piece : start.buildingPieces()) {
						if (structurePieceBlocksHousePlot(piece.box(), minX, maxX, minZ, maxZ, minY, maxY)) return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Copies loaded village-start metadata once per chunk and tick. Block states are never cached:
	 * terrain/POI checks still see edits immediately, while chunk identity and the copied start map
	 * invalidate metadata if a chunk is replaced or its starts change during the tick.
	 */
	private static StructureChunkSnapshot structureChunkSnapshot(ServerLevel level, int chunkX, int chunkZ,
		ChunkAccess chunk, net.minecraft.core.Registry<Structure> structures) {
		Map<Structure, StructureStart> liveStarts = chunk.getAllStarts();
		StructureChunkKey key = new StructureChunkKey(level, chunkX, chunkZ);
		synchronized (HOUSE_STRUCTURE_CACHE) {
			if (houseStructureCacheTick != level.getGameTime()) {
				HOUSE_STRUCTURE_CACHE.clear();
				houseStructureCacheTick = level.getGameTime();
			}
			StructureChunkSnapshot cached = HOUSE_STRUCTURE_CACHE.get(key);
			if (cached != null && cached.chunk() == chunk && cached.sourceStarts().equals(liveStarts)) return cached;
		}

		Map<Structure, StructureStart> sourceStarts = Map.copyOf(liveStarts);
		List<VillageStartSnapshot> villageStarts = new ArrayList<>();
		int totalPieces = 0;
		boolean complete = true;
		for (var entry : sourceStarts.entrySet()) {
			var id = structures.getKey(entry.getKey());
			if (id == null || !id.getPath().toLowerCase(java.util.Locale.ROOT).contains("village")) continue;
			StructureStart start = entry.getValue();
			if (!start.isValid()) continue;
			if (villageStarts.size() >= MAX_HOUSE_STRUCTURE_STARTS) { complete = false; break; }
			List<StructurePieceBounds> buildings = new ArrayList<>();
			int pieceCount = 0;
			for (StructurePiece piece : start.getPieces()) {
				if (++pieceCount > MAX_HOUSE_STRUCTURE_PIECES || ++totalPieces > MAX_HOUSE_STRUCTURE_PIECES) {
					complete = false;
					break;
				}
				BoundingBox box = piece.getBoundingBox();
				if (houseBuildingPiece(box)) buildings.add(StructurePieceBounds.copyOf(box));
			}
			if (!complete) break;
			BoundingBox startBounds = start.getBoundingBox();
			StructureStartKey startKey = new StructureStartKey(id.toString(), startBounds.minX(), startBounds.minY(), startBounds.minZ(),
				startBounds.maxX(), startBounds.maxY(), startBounds.maxZ());
			villageStarts.add(new VillageStartSnapshot(startKey, StructurePieceBounds.copyOf(startBounds), pieceCount, List.copyOf(buildings)));
		}
		StructureChunkSnapshot snapshot = new StructureChunkSnapshot(chunk, sourceStarts, List.copyOf(villageStarts), complete);
		synchronized (HOUSE_STRUCTURE_CACHE) {
			if (houseStructureCacheTick == level.getGameTime()) {
				HOUSE_STRUCTURE_CACHE.put(key, snapshot);
				while (HOUSE_STRUCTURE_CACHE.size() > MAX_HOUSE_STRUCTURE_CACHE_CHUNKS) {
					HOUSE_STRUCTURE_CACHE.remove(HOUSE_STRUCTURE_CACHE.keySet().iterator().next());
				}
			}
		}
		return snapshot;
	}

	private static boolean intersects(BoundingBox box, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		return box.maxX() >= minX && box.minX() <= maxX && box.maxY() >= minY && box.minY() <= maxY
			&& box.maxZ() >= minZ && box.minZ() <= maxZ;
	}

	static boolean houseBuildingPiece(BoundingBox box) {
		if (box == null) return false;
		int x = box.getXSpan(), y = box.getYSpan(), z = box.getZSpan();
		return x >= 3 && z >= 3 && y >= 4
			&& x <= HouseTemplate.MAX_FOOTPRINT_SIZE && z <= HouseTemplate.MAX_FOOTPRINT_SIZE
			&& y <= HouseTemplate.MAX_FOOTPRINT_SIZE + 10;
	}

	static boolean structurePieceBlocksHousePlot(BoundingBox box,
		int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
		return intersects(box, minX - HOUSE_MARGIN, maxX + HOUSE_MARGIN, minY, maxY, minZ - HOUSE_MARGIN, maxZ + HOUSE_MARGIN);
	}

	/** Older settlements can start before the mod has recorded a street; enforce the link once one is saved. */
	private static boolean hasSavedVillageStreet(VillageRef ref) {
		if (ref == null || ref.bellEntity() == null || Market.of(ref).plaza().isEmpty()) return false;
		Map<String, Long> roads = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.ROADS, Map.of());
		return roads.getOrDefault("center", 0L) > 0L;
	}

	/** Bounded surface search from the three-block entrance apron to an actually built cell on the saved road. */
	private static boolean reachesSavedVillageStreet(ServerLevel level, BlockPos start, VillageRef ref) {
		Set<Long> streetColumns = new HashSet<>();
		for (BlockPos ground : Roads.savedVillagePathGrounds(level, ref)) {
			if (!level.hasChunkAt(ground) || !streetSurface(level.getBlockState(ground))) continue;
			BlockPos feet = walkableSurface(level, ground.getX(), ground.getZ());
			if (feet != null && feet.below().equals(ground)) streetColumns.add(columnKey(ground.getX(), ground.getZ()));
		}
		if (streetColumns.isEmpty()) return false;

		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Map<Long, Integer> distance = new HashMap<>();
		long startKey = columnKey(start.getX(), start.getZ());
		if (streetColumns.contains(startKey)) return true;
		queue.addLast(start);
		distance.put(startKey, 0);
		while (!queue.isEmpty() && distance.size() < HOUSE_ROAD_SEARCH_LIMIT) {
			BlockPos current = queue.removeFirst();
			int currentDistance = distance.get(columnKey(current.getX(), current.getZ()));
			if (currentDistance >= HOUSE_ROAD_REACH) continue;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				int x = current.getX() + direction.getStepX();
				int z = current.getZ() + direction.getStepZ();
				long key = columnKey(x, z);
				if (distance.containsKey(key)) continue;
				BlockPos next = walkableSurface(level, x, z);
				if (next == null || Math.abs(next.getY() - current.getY()) > 1) continue;
				if (streetColumns.contains(key)) return true;
				distance.put(key, currentDistance + 1);
				queue.addLast(next);
			}
		}
		return false;
	}

	private static final int WALK_BACK_REACH = 128;
	private static final int WALK_BACK_NODE_LIMIT = 12_000;
	private static final int WALK_BACK_GOAL_RADIUS = 4;
	private static final int WALK_BACK_MAX_RISE = 1;
	private static final int WALK_BACK_MAX_DROP = 3;

	/**
	 * Bounded local-walking search proving that a walker standing at {@code start} can walk back to {@code goal}.
	 * A step may climb at most one block (vanilla step height) and drop at most three; wooden doors, stairs
	 * and slabs are passable; fences, closed gates, walls, leaves and water are not. Vanilla navigation will happily route a
	 * worker DOWN a ledge, so a successful path to a stand says nothing about the way home: a quarry stand below
	 * a cliff or in a pit passes the outbound check and then strands the worker for good. Walking is evaluated on
	 * the real blocks (not the height map) so streets under arches, doorways and gates still count.
	 * Unloaded chunks block the search (conservative: the caller rejects and retries later).
	 */
	public static boolean walksBackTo(ServerLevel level, BlockPos start, BlockPos goal) {
		if (!level.hasChunkAt(start) || !level.hasChunkAt(goal)) return false;
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Map<Long, Integer> distance = new HashMap<>();
		queue.addLast(start.immutable());
		distance.put(start.asLong(), 0);
		while (!queue.isEmpty() && distance.size() < WALK_BACK_NODE_LIMIT) {
			BlockPos current = queue.removeFirst();
			if (Math.max(Math.abs(current.getX() - goal.getX()), Math.abs(current.getZ() - goal.getZ())) <= WALK_BACK_GOAL_RADIUS
				&& Math.abs(current.getY() - goal.getY()) <= 2) return true;
			int currentDistance = distance.get(current.asLong());
			if (currentDistance >= WALK_BACK_REACH) continue;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				int x = current.getX() + direction.getStepX();
				int z = current.getZ() + direction.getStepZ();
				BlockPos next = walkStep(level, x, current.getY(), z);
				if (next == null || distance.containsKey(next.asLong())) continue;
				distance.put(next.asLong(), currentDistance + 1);
				queue.addLast(next);
			}
		}
		return false;
	}

	/** Feet cell a walker at height {@code y} reaches in column x/z (up one, down three), or null. */
	private static BlockPos walkStep(ServerLevel level, int x, int y, int z) {
		for (int ny = y + WALK_BACK_MAX_RISE; ny >= y - WALK_BACK_MAX_DROP; ny--) {
			BlockPos feet = new BlockPos(x, ny, z);
			if (!walkThrough(level, feet) || !walkThrough(level, feet.above())) {
				// A blocked cell at or below the current level also seals everything deeper in this column.
				if (ny <= y) return null;
				continue;
			}
			BlockPos floor = feet.below();
			if (!level.hasChunkAt(floor)) return null;
			BlockState ground = level.getBlockState(floor);
			if (ground.getFluidState().isEmpty() && (ground.isFaceSturdy(level, floor, Direction.UP) || ground.is(Blocks.DIRT_PATH)
				|| ground.is(Blocks.FARMLAND) || ground.is(net.minecraft.tags.BlockTags.SLABS)
				|| ground.is(net.minecraft.tags.BlockTags.STAIRS))) return feet;
		}
		return null;
	}

	private static boolean walkThrough(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return false;
		BlockState state = level.getBlockState(pos);
		if (!state.getFluidState().isEmpty()) return false;
		// Villagers open wooden doors; vanilla ground navigation never opens a closed fence gate.
		return state.getCollisionShape(level, pos).isEmpty() || state.is(net.minecraft.tags.BlockTags.WOODEN_DOORS);
	}

	/** Returns feet height only for an already loaded, stable surface with two clear blocks above it. */
	private static BlockPos walkableSurface(ServerLevel level, int x, int z) {
		BlockPos column = new BlockPos(x, level.getMinY(), z);
		if (!level.hasChunkAt(column)) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos feet = new BlockPos(x, y, z);
		BlockPos ground = feet.below();
		if (!level.hasChunkAt(ground) || !level.hasChunkAt(feet.above())) return null;
		BlockState floor = level.getBlockState(ground);
		// Dirt paths are genuinely walkable, but their collision shape is 1/16 block low and
		// therefore fails the full-face support predicate used by ordinary full-block floors.
		if (!floor.getFluidState().isEmpty()
			|| (!floor.isFaceSturdy(level, ground, Direction.UP) && !floor.is(Blocks.DIRT_PATH))) return null;
		for (int dy = 0; dy < 2; dy++) {
			BlockPos space = feet.above(dy);
			if (!level.hasChunkAt(space)) return null;
			BlockState state = level.getBlockState(space);
			if (!state.getFluidState().isEmpty() || (!state.isAir() && !state.canBeReplaced())) return null;
		}
		return feet;
	}

	private static boolean streetSurface(BlockState state) {
		return state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL) || state.is(Blocks.COBBLESTONE)
			|| state.is(Blocks.SMOOTH_SANDSTONE) || state.is(Blocks.SANDSTONE) || state.is(Blocks.SPRUCE_PLANKS);
	}

	private static long columnKey(int x, int z) {
		return BlockPos.asLong(x, 0, z);
	}

	/** Wie {@link #find}, aber fuer ein beliebiges Rechteck (Vorlagen-Haeuser). */
	public static BlockPos findRect(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int minX, int maxX,
		int minZ, int maxZ, int headroom, int attempts) {
		int referenceY = villageReferenceY(level, around);
		for (int i = 0; i < attempts; i++) {
			double angle = r.nextDouble() * Math.PI * 2;
			double dist = minDist + r.nextInt(Math.max(1, maxDist - minDist + 1));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * dist);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, around.getY(), z))) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (!villageLevel(referenceY, y)) continue;
			BlockPos c = new BlockPos(x, y, z);
			if (okRect(level, c, minX, maxX, minZ, maxZ, headroom)) {
				return c;
			}
		}
		return null;
	}
	public static Direction facingTowards(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		if (Math.abs(dx) > Math.abs(dz)) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/**
	 * Toleranter Bauplatz: hoechstens ein Block Hoehenunterschied, damit das Fundament dem Hang folgt statt eine hohe
	 * kuenstliche Plattform zu bilden. Der Untergrund muss natuerlich sein und frei von Gebaeuden, Wegen, Feldern und Wasser.
	 */
	public static BlockPos findTolerant(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int minX, int maxX,
		int minZ, int maxZ, int headroom, int attempts) {
		return findTolerant(level, around, r, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, attempts,
			villageReferenceY(level, around));
	}

	public static BlockPos findTolerant(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int minX, int maxX,
		int minZ, int maxZ, int headroom, int attempts, int referenceY) {
		return findTolerant(level, around, r, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, attempts, referenceY, p -> true);
	}

	/** Tolerant random search counterpart that also honors caller-specific plan filters. */
	public static BlockPos findTolerant(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int minX, int maxX,
		int minZ, int maxZ, int headroom, int attempts, int referenceY, Predicate<BlockPos> planFits) {
		for (int i = 0; i < attempts; i++) {
			BlockPos cand = candidate(level, around, r, minDist, maxDist, referenceY);
			if (cand == null) {
				continue;
			}
			int base = tolerantBase(level, cand, minX, maxX, minZ, maxZ, headroom);
			if (base != Integer.MIN_VALUE && villageLevel(referenceY, base)) {
				BlockPos anchor = new BlockPos(cand.getX(), base, cand.getZ());
				if (planFits.test(anchor)) return anchor;
			}
		}
		return null;
	}

	/** Wie {@link #findTolerant}, aber fuer eine feste Position: gibt den Anker (x, Basishoehe, z) oder null zurueck. */
	public static BlockPos tolerantAnchor(ServerLevel level, BlockPos cand, int minX, int maxX, int minZ, int maxZ, int headroom) {
		int base = tolerantBase(level, cand, minX, maxX, minZ, maxZ, headroom);
		return base == Integer.MIN_VALUE ? null : new BlockPos(cand.getX(), base, cand.getZ());
	}

	private static int tolerantBase(ServerLevel level, BlockPos c, int minX, int maxX, int minZ, int maxZ, int headroom) {
		if (Market.reserved(level,c,Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(Math.abs(minZ),Math.abs(maxZ))))) return Integer.MIN_VALUE;
		int w = maxX - minX + 1;
		int d = maxZ - minZ + 1;
		int[] heights = new int[w * d];
		int n = 0;
		for (int dx = minX; dx <= maxX; dx++) {
			for (int dz = minZ; dz <= maxZ; dz++) {
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (!level.hasChunkAt(new BlockPos(x, c.getY(), z))) {
					return Integer.MIN_VALUE;
				}
				heights[n++] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			}
		}
		int[] sorted = heights.clone();
		java.util.Arrays.sort(sorted);
		// Bauhoehe = hoechster Punkt der Flaeche; nur ein Block darf aufgefuellt werden.
		int base = sorted[sorted.length - 1];
		if (base - sorted[0] > 1) {
			return Integer.MIN_VALUE;
		}
		n = 0;
		for (int dx = minX; dx <= maxX; dx++) {
			for (int dz = minZ; dz <= maxZ; dz++) {
				int h = heights[n++];
				if (Math.abs(h - base) > 1) {
					return Integer.MIN_VALUE;
				}
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				BlockPos ground = new BlockPos(x, h - 1, z);
				BlockState gs = level.getBlockState(ground);
				if (!de.villagerplus.village.VillageStyle.natural(gs) || !gs.getFluidState().isEmpty()) {
					return Integer.MIN_VALUE;
				}
				for (int y = Math.max(h, base); y < base + headroom; y++) {
					BlockState s = level.getBlockState(new BlockPos(x, y, z));
					if (!s.isAir() && !s.canBeReplaced()) {
						return Integer.MIN_VALUE;
					}
				}
			}
		}
		return touchesBuilding(level, new BlockPos(c.getX(), base, c.getZ()), minX, maxX, minZ, maxZ, headroom) ? Integer.MIN_VALUE : base;
	}
}
