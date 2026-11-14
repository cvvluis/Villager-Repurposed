package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.job.JobUtil;
import de.villagerplus.build.HouseLotPreparation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.phys.AABB;

/** Bounded, restart-safe preparation of natural residential and construction sites. */
public final class NaturalSitePreparation {
	private NaturalSitePreparation() { }

	public enum Status { READY, CLEARABLE, CLEARED, WAITING_FOR_CHUNK, WAITING_FOR_ENTITY, WAITING_FOR_INVENTORY, WAITING_FOR_TOOL, CANCELLED, BLOCKED }
	public record Scan(Status status, BlockPos next, int obstacleCount) { }
	public record Grade(Status status, int lowSurfaceY, int highSurfaceY) { }

	private static final int MAX_SITE_VOLUME = 4096;
	private static final int MAX_SITE_HALF = 8;
	private static final int MAX_SITE_HEIGHT = 32;
	private static final int MAX_NATURAL_TREE_HEIGHT = 32;
	private static final int MAX_SITE_OBSTACLES = 4096;
	private static final int MAX_RESIDENTIAL_RELIEF = 3;
	private static final int MAX_PREPARABLE_MARKET_RELIEF = 5;
	private static final int MAX_STRUCTURE_SCAN_CHUNKS = 64;
	private static final int MAX_STRUCTURE_REFERENCE_ENTRIES = 4096;
	private static final int MAX_STRUCTURE_STARTS = 512;
	private static final int MAX_STRUCTURE_PIECES = 4096;
	private static final int MAX_TERRACED_MARKET_RELIEF = 3;
	private record Candidate(BlockPos pos, BlockState state, int priority) { }
	private record TreeSpan(int x, int z, int minY, int maxY, Status status) { }
	private record VillageStartKey(String structureId, long chunkPos) { }

	/** Persisted site identity and bounded clearance envelope; work resumes from remaining world blocks after reload. */
	public record SitePlan(BlockPos center, int half, int clearanceAbove, int maxRelief, int maxObstacles, boolean cancelled) {
		public static final Codec<SitePlan> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("center").forGetter(SitePlan::center),
			Codec.INT.optionalFieldOf("half", 4).forGetter(SitePlan::half),
			Codec.INT.optionalFieldOf("clearance_above", 12).forGetter(SitePlan::clearanceAbove),
			Codec.INT.optionalFieldOf("max_relief", 1).forGetter(SitePlan::maxRelief),
			Codec.INT.optionalFieldOf("max_obstacles", 256).forGetter(SitePlan::maxObstacles),
			Codec.BOOL.optionalFieldOf("cancelled", false).forGetter(SitePlan::cancelled)
		).apply(i, SitePlan::new));

		public SitePlan {
			center = center == null ? BlockPos.ZERO : center.immutable();
		}

		public boolean valid() {
			if (half < 1 || half > MAX_SITE_HALF || clearanceAbove < 1 || clearanceAbove > MAX_SITE_HEIGHT
				|| maxRelief < 0 || maxRelief > MAX_RESIDENTIAL_RELIEF
				|| maxObstacles < 1 || maxObstacles > MAX_SITE_OBSTACLES) return false;
			long width = half * 2L + 1L;
			return width * width * (clearanceAbove + 1L) <= MAX_SITE_VOLUME;
		}

		public BlockPos min() { return center.offset(-half, 0, -half); }
		public BlockPos max() { return center.offset(half, clearanceAbove, half); }
		public SitePlan cancel() { return new SitePlan(center, half, clearanceAbove, maxRelief, maxObstacles, true); }
	}

	/** Check a natural pad with the requested bounded relief; never excavate trees, structures or fluids. */
	public static Grade inspectNaturalGrade(ServerLevel level, BlockPos center, int half, int maxRelief) {
		if (level == null || center == null || half < 1 || half > 8 || maxRelief < 0 || maxRelief > MAX_RESIDENTIAL_RELIEF) {
			return new Grade(Status.BLOCKED, Integer.MIN_VALUE, Integer.MIN_VALUE);
		}
		int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
		for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
			int x = center.getX() + dx, z = center.getZ() + dz;
			BlockPos column = new BlockPos(x, center.getY(), z);
			if (!level.hasChunkAt(column)) return new Grade(Status.WAITING_FOR_CHUNK, low, high);
			int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos ground = new BlockPos(x, surfaceY - 1, z);
			BlockState state = level.getBlockState(ground);
			if (!safeNaturalGround(level, ground, state)) return new Grade(Status.BLOCKED, low, high);
			low = Math.min(low, surfaceY);
			high = Math.max(high, surfaceY);
		}
		return high - low <= maxRelief ? new Grade(Status.READY, low, high) : new Grade(Status.BLOCKED, low, high);
	}

	/** Residential pads allow three natural soil layers because the mason removes them one at a time before construction. */
	public static Grade inspectResidentialGrade(ServerLevel level, BlockPos center, int half, int maxRelief) {
		return inspectMarketGrade(level, center, half, maxRelief, MAX_RESIDENTIAL_RELIEF);
	}

	/** Market grade treats a verified rooted tree trunk as vegetation above its natural soil, not as a hill. */
	public static Grade inspectMarketGrade(ServerLevel level, BlockPos center, int half, int maxRelief) {
		return inspectMarketGrade(level, center, half, maxRelief, 1);
	}

	/** Market-only grading permits a bounded three-block terrace; existing site plans keep their one-block contract. */
	public static Grade inspectTerracedMarketGrade(ServerLevel level, BlockPos center, int half, int maxRelief) {
		return inspectMarketGrade(level, center, half, maxRelief, MAX_TERRACED_MARKET_RELIEF);
	}

	/** Last-resort market candidates may be leveled across five natural blocks after ordinary plots fail. */
	public static Grade inspectPreparableMarketGrade(ServerLevel level, BlockPos center, int half) {
		return inspectMarketGrade(level, center, half, MAX_PREPARABLE_MARKET_RELIEF, MAX_PREPARABLE_MARKET_RELIEF);
	}

	private static Grade inspectMarketGrade(ServerLevel level, BlockPos center, int half, int maxRelief, int maximumRelief) {
		if (level == null || center == null || half < 1 || half > 8 || maxRelief < 0 || maxRelief > maximumRelief) {
			return new Grade(Status.BLOCKED, Integer.MIN_VALUE, Integer.MIN_VALUE);
		}
		int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
		for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
			int x = center.getX() + dx, z = center.getZ() + dz;
			BlockPos column = new BlockPos(x, center.getY(), z);
			if (!level.hasChunkAt(column)) return new Grade(Status.WAITING_FOR_CHUNK, low, high);
			int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos ground = new BlockPos(x, surfaceY - 1, z);
			BlockState state = level.getBlockState(ground);
			if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
				Status tree = naturalTreeLogStatus(level, ground, state);
				if (tree != Status.READY) return new Grade(tree, low, high);
				BlockPos root = ground;
				int rootDepth = 0;
				while (rootDepth < 32 && root.getY() > level.getMinY() + 1) {
					BlockPos below = root.below();
					if (!level.hasChunkAt(below)) return new Grade(Status.WAITING_FOR_CHUNK, low, high);
					if (!verticalNaturalLog(level.getBlockState(below))) break;
					root = below;
					rootDepth++;
				}
				if (rootDepth == 32) {
					if (!level.hasChunkAt(root.below())) return new Grade(Status.WAITING_FOR_CHUNK, low, high);
					if (verticalNaturalLog(level.getBlockState(root.below()))) return new Grade(Status.BLOCKED, low, high);
				}
				surfaceY = root.getY();
				ground = new BlockPos(x, surfaceY - 1, z);
				state = level.getBlockState(ground);
			}
			if (!safeNaturalGround(level, ground, state)) return new Grade(Status.BLOCKED, low, high);
			low = Math.min(low, surfaceY);
			high = Math.max(high, surfaceY);
		}
		return high - low <= maxRelief ? new Grade(Status.READY, low, high) : new Grade(Status.BLOCKED, low, high);
	}

	/** A cancelled or invalid persisted site never reads the world or starts a new work step. */
	public static Scan inspect(ServerLevel level, SitePlan plan) {
		if (plan == null) return new Scan(Status.BLOCKED, null, 0);
		if (plan.cancelled()) return new Scan(Status.CANCELLED, null, 0);
		if (!plan.valid()) return new Scan(Status.BLOCKED, null, 0);
		if (level == null || plan.center().getY() < level.getMinY() + 1
			|| plan.center().getY() + plan.clearanceAbove() >= level.getMaxY()) return new Scan(Status.BLOCKED, null, 0);
		Scan structureSpacing = inspectVillageStructureSpacing(level, plan);
		if (structureSpacing.status() != Status.READY) return structureSpacing;
		Grade grade = inspectResidentialGrade(level, plan.center(), plan.half(), plan.maxRelief());
		if (grade.status() == Status.WAITING_FOR_CHUNK) return new Scan(Status.WAITING_FOR_CHUNK, null, 0);
		if (grade.status() != Status.READY) return new Scan(Status.BLOCKED, null, 0);
		return inspect(level, plan.min(), plan.max(), plan.maxObstacles());
	}

	/**
	 * A residential lot must be separated from actual vanilla/CTOV house-piece bounds. Walk only
	 * loaded chunk structure references, then require each referenced start chunk to be loaded too;
	 * missing or incomplete metadata defers the lot instead of treating an unseen structure as absent.
	 */
	public static Scan inspectVillageStructureSpacing(ServerLevel level, SitePlan plan) {
		long minXLong = (long)plan.center().getX() - plan.half() - Sites.HOUSE_MARGIN;
		long maxXLong = (long)plan.center().getX() + plan.half() + Sites.HOUSE_MARGIN;
		long minZLong = (long)plan.center().getZ() - plan.half() - Sites.HOUSE_MARGIN;
		long maxZLong = (long)plan.center().getZ() + plan.half() + Sites.HOUSE_MARGIN;
		if (minXLong < Integer.MIN_VALUE || maxXLong > Integer.MAX_VALUE
			|| minZLong < Integer.MIN_VALUE || maxZLong > Integer.MAX_VALUE) {
			return new Scan(Status.BLOCKED, null, 0);
		}
		int minX = (int)minXLong, maxX = (int)maxXLong;
		int minZ = (int)minZLong, maxZ = (int)maxZLong;
		int minY = Math.max(level.getMinY(), plan.center().getY() - 3);
		int maxY = Math.min(level.getMaxY() - 1, plan.center().getY() + plan.clearanceAbove() + 3);
		int minChunkX = minX >> 4, maxChunkX = maxX >> 4;
		int minChunkZ = minZ >> 4, maxChunkZ = maxZ >> 4;
		long chunkCount = (long)(maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
		if (chunkCount <= 0 || chunkCount > MAX_STRUCTURE_SCAN_CHUNKS) return new Scan(Status.BLOCKED, null, 0);

		var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Set<VillageStartKey> visitedStarts = new HashSet<>();
		int inspectedStructureEntries = 0;
		int inspectedPieces = 0;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				ChunkAccess referenceChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (referenceChunk == null
					|| !referenceChunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_REFERENCES)) {
					return new Scan(Status.WAITING_FOR_CHUNK, null, inspectedPieces);
				}
				for (var entry : referenceChunk.getAllReferences().entrySet()) {
					if (++inspectedStructureEntries > MAX_STRUCTURE_REFERENCE_ENTRIES) {
						return new Scan(Status.BLOCKED, null, inspectedPieces);
					}
					Structure structure = entry.getKey();
					Identifier id = structures.getKey(structure);
					if (id == null) return new Scan(Status.BLOCKED, null, inspectedPieces);
					if (!id.getPath().toLowerCase(java.util.Locale.ROOT).contains("village")) continue;
					for (long sourceChunkPos : entry.getValue()) {
						VillageStartKey key = new VillageStartKey(id.toString(), sourceChunkPos);
						if (!visitedStarts.add(key)) continue;
						if (visitedStarts.size() > MAX_STRUCTURE_STARTS) return new Scan(Status.BLOCKED, null, inspectedPieces);
						int sourceX = ChunkPos.getX(sourceChunkPos), sourceZ = ChunkPos.getZ(sourceChunkPos);
						ChunkAccess startChunk = level.getChunkSource().getChunkNow(sourceX, sourceZ);
						if (startChunk == null
							|| !startChunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
							return new Scan(Status.WAITING_FOR_CHUNK, null, inspectedPieces);
						}
						StructureStart start = startChunk.getStartForStructure(structure);
						if (start == null || !start.isValid() || !start.getChunkPos().equals(ChunkPos.unpack(sourceChunkPos))) {
							return new Scan(Status.BLOCKED, null, inspectedPieces);
						}
						if (!intersects(start.getBoundingBox(), minX, maxX, minY, maxY, minZ, maxZ)) continue;
						for (StructurePiece piece : start.getPieces()) {
							if (++inspectedPieces > MAX_STRUCTURE_PIECES) return new Scan(Status.BLOCKED, null, inspectedPieces);
							BoundingBox bounds = piece.getBoundingBox();
							if (Sites.houseBuildingPiece(bounds)
								&& Sites.structurePieceBlocksHousePlot(bounds,
									(int)(minXLong + Sites.HOUSE_MARGIN), (int)(maxXLong - Sites.HOUSE_MARGIN),
									(int)(minZLong + Sites.HOUSE_MARGIN), (int)(maxZLong - Sites.HOUSE_MARGIN), minY, maxY)) {
								return new Scan(Status.BLOCKED, new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()), inspectedPieces);
							}
						}
					}
				}
			}
		}
		return new Scan(Status.READY, null, inspectedPieces);
	}

	private static boolean intersects(BoundingBox box, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		return box.maxX() >= minX && box.minX() <= maxX && box.maxY() >= minY && box.minY() <= maxY
			&& box.maxZ() >= minZ && box.minZ() <= maxZ;
	}

	/** Inspect an inclusive, fixed volume without loading chunks or changing blocks. */
	public static Scan inspect(ServerLevel level, BlockPos min, BlockPos max, int maxObstacles) {
		if (level == null || min == null || max == null || maxObstacles < 0
			|| min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
			return new Scan(Status.BLOCKED, null, 0);
		}
		long widthX = (long)max.getX() - min.getX() + 1L;
		long widthY = (long)max.getY() - min.getY() + 1L;
		long widthZ = (long)max.getZ() - min.getZ() + 1L;
		if (widthX <= 0 || widthY <= 0 || widthZ <= 0
			|| widthX > MAX_SITE_VOLUME || widthY > MAX_SITE_VOLUME || widthZ > MAX_SITE_VOLUME
			|| widthX > MAX_SITE_VOLUME / widthY || widthX * widthY > MAX_SITE_VOLUME / widthZ) {
			return new Scan(Status.BLOCKED, null, 0);
		}

		List<Candidate> candidates = new ArrayList<>();
		List<TreeSpan> trees = new ArrayList<>();
		Set<Long> extendedTrees = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (!level.hasChunkAt(pos)) return new Scan(Status.WAITING_FOR_CHUNK, null, candidates.size());
			BlockState state = level.getBlockState(pos);
			if (state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()
				|| hasPoiAt(level, pos)) {
				return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
			}
			if (state.isAir()) continue;
			int priority;
			if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
				Status treeStatus = naturalTreeLogStatus(level, pos, state, trees);
				if (treeStatus == Status.WAITING_FOR_CHUNK)
					return new Scan(Status.WAITING_FOR_CHUNK, pos.immutable(), candidates.size());
				TreeSpan tree = treeAt(trees, pos);
				if (treeStatus != Status.READY || tree == null || !treeRootInsideBounds(tree, min, max))
					return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
				// The committed lot's vertical clearance can end below a mature tree's crown. Finish
				// only the verified trunk rooted inside this reservation; never widen its X/Z work area.
				BlockPos root = new BlockPos(tree.x(), tree.minY(), tree.z());
				if (tree.maxY() > max.getY() && extendedTrees.add(root.asLong())) {
					for (int y = max.getY() + 1; y <= tree.maxY(); y++) {
						BlockPos continuation = new BlockPos(tree.x(), y, tree.z());
						if (!level.hasChunkAt(continuation))
							return new Scan(Status.WAITING_FOR_CHUNK, continuation, candidates.size());
						BlockState continuationState = level.getBlockState(continuation);
						if (!verticalNaturalLog(continuationState) || continuationState.hasBlockEntity()
							|| level.getBlockEntity(continuation) != null || !continuationState.getFluidState().isEmpty()
							|| hasPoiAt(level, continuation)) {
							return new Scan(Status.BLOCKED, continuation.immutable(), candidates.size());
						}
						candidates.add(new Candidate(continuation.immutable(), continuationState, 0));
						if (candidates.size() > maxObstacles)
							return new Scan(Status.BLOCKED, continuation.immutable(), candidates.size());
					}
				}
				priority = 0;
			} else if (safeNaturalLeaves(state)) {
				Status leafStatus = naturalLeafStatus(level, pos, min, max);
				if (leafStatus == Status.WAITING_FOR_CHUNK)
					return new Scan(Status.WAITING_FOR_CHUNK, pos.immutable(), candidates.size());
				if (leafStatus != Status.READY) return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
				priority = 1;
			} else if (safeNaturalPlant(state)) {
				Status plantStatus = naturalPlantStatus(level, pos, min, max);
				if (plantStatus == Status.WAITING_FOR_CHUNK)
					return new Scan(Status.WAITING_FOR_CHUNK, pos.immutable(), candidates.size());
				if (plantStatus != Status.READY) return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
				priority = state.is(Blocks.SNOW) ? 0 : 2;
			} else if (HouseLotPreparation.isNaturalEarthCutCandidate(level, pos, state, min, max)) {
				priority = 3;
			} else if (HouseLotPreparation.isNaturalEarthSupport(level, pos, state, min, max)
				|| HouseLotPreparation.isEarthUnderNaturalCover(level, pos, state, min, max)) {
				// Keep buried layers beneath an eligible rise as support; the exposed top remains the cursor target.
				continue;
			} else {
				return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
			}
			candidates.add(new Candidate(pos.immutable(), state, priority));
			if (candidates.size() > maxObstacles) return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
		}
		if (candidates.isEmpty()) return new Scan(Status.READY, null, 0);
		Candidate next = candidates.stream().min(Comparator
			.comparingInt(Candidate::priority)
			.thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.pos().getY()).reversed())
			.thenComparingInt(candidate -> candidate.pos().getX())
			.thenComparingInt(candidate -> candidate.pos().getZ())).orElseThrow();
		return new Scan(Status.CLEARABLE, next.pos(), candidates.size());
	}

	/** Clear one block from a persisted site; the remaining world is the restart-safe cursor. */
	public static Scan clearOne(ServerLevel level, SitePlan plan, Villager worker) {
		if (plan == null || worker == null) return new Scan(Status.BLOCKED, null, 0);
		Scan scan = inspect(level, plan);
		if (scan.status() != Status.CLEARABLE) return scan;
		return clearOne(level, plan.min(), plan.max(), plan.maxObstacles(), worker);
	}

	/** Clear at most one safe natural block per call and retain every predicted drop in the resident inventory. */
	public static Scan clearOne(ServerLevel level, BlockPos min, BlockPos max, int maxObstacles, Entity worker) {
		Scan scan = inspect(level, min, max, maxObstacles);
		if (scan.status() != Status.CLEARABLE) return scan;
		if (!(worker instanceof Villager villager)) return new Scan(Status.BLOCKED, scan.next(), scan.obstacleCount());
		BlockPos next = scan.next();
		BlockState state = level.getBlockState(next);
		AABB cell = HouseLotPreparation.isLevelableNaturalEarth(state)
			? new AABB(next.getX(), next.getY(), next.getZ(), next.getX() + 1.0, next.getY() + 2.0, next.getZ() + 1.0)
			: new AABB(next);
		if (!level.getEntitiesOfClass(LivingEntity.class, cell,
			entity -> entity.isAlive() && entity != villager).isEmpty()) {
			return new Scan(Status.WAITING_FOR_ENTITY, scan.next(), scan.obstacleCount());
		}
		BlockPos pos = next;
		if (!safeNaturalTarget(level, pos, state, min, max)) return new Scan(Status.BLOCKED, pos, scan.obstacleCount());
		ItemStack tool = carriedTool(villager.getInventory(), state);
		if (HouseLotPreparation.isLevelableNaturalEarth(state) && tool.isEmpty()) {
			return new Scan(Status.WAITING_FOR_TOOL, pos, scan.obstacleCount());
		}
		List<ItemStack> drops = Block.getDrops(state, level, pos, null, villager, tool.copy());
		if (!canFitAllDrops(villager.getInventory(), drops)) return new Scan(Status.WAITING_FOR_INVENTORY, pos, scan.obstacleCount());
		if (!level.getBlockState(pos).equals(state) || !level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
			return new Scan(Status.BLOCKED, pos, scan.obstacleCount());
		}
		if (!tool.isEmpty() && tool.isDamageableItem()) tool.hurtAndBreak(1, level, null, ignored -> { });
		for (ItemStack drop : drops) if (!drop.isEmpty()) JobUtil.give(level, villager, drop.copy());
		return new Scan(Status.CLEARED, pos, scan.obstacleCount());
	}

	/** Inspect one paid market target. Surface soil can remain under the paid floor; only safe natural cover and rooted tree parts can be removed. */
	public static Scan inspectMarketTarget(ServerLevel level, BlockPos pos, boolean paidFloor) {
		if (level == null || pos == null) return new Scan(Status.BLOCKED, null, 0);
		if (!level.hasChunkAt(pos)) return new Scan(Status.WAITING_FOR_CHUNK, null, 0);
		BlockState state = level.getBlockState(pos);
		if (state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) {
			return new Scan(Status.BLOCKED, null, 0);
		}
		if (state.isAir()) return new Scan(Status.READY, null, 0);
		if (paidFloor && safeNaturalGround(level, pos, state)) return new Scan(Status.READY, null, 0);
		if (safeNaturalPlant(state) || safeNaturalLeaves(state)) return new Scan(Status.CLEARABLE, pos.immutable(), 1);
		if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
			Status tree = naturalTreeLogStatus(level, pos, state);
			return tree == Status.READY ? new Scan(Status.CLEARABLE, pos.immutable(), 1)
				: new Scan(tree, null, 0);
		}
		return new Scan(Status.BLOCKED, null, 0);
	}

	/** Clear one verified market-target block and conserve every predicted drop in the worker inventory. */
	public static Scan clearOneMarketTarget(ServerLevel level, BlockPos pos, boolean paidFloor, Villager worker) {
		Scan scan = inspectMarketTarget(level, pos, paidFloor);
		if (scan.status() != Status.CLEARABLE) return scan;
		AABB cell = new AABB(pos);
		if (!level.getEntitiesOfClass(LivingEntity.class, cell,
			LivingEntity::isAlive).isEmpty()) {
			return new Scan(Status.WAITING_FOR_ENTITY, pos.immutable(), scan.obstacleCount());
		}
		BlockState state = level.getBlockState(pos);
		ItemStack tool = state.is(BlockTags.OVERWORLD_NATURAL_LOGS) ? new ItemStack(Items.IRON_AXE)
			: state.is(BlockTags.LEAVES) ? new ItemStack(Items.SHEARS) : ItemStack.EMPTY;
		java.util.List<ItemStack> drops = Block.getDrops(state, level, pos, null, worker, tool);
		if ((!drops.isEmpty() && worker == null) || (worker != null && !canFitAllDrops(worker.getInventory(), drops))) {
			return new Scan(Status.WAITING_FOR_INVENTORY, pos.immutable(), scan.obstacleCount());
		}
		if (!level.getBlockState(pos).equals(state) || !level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
			return new Scan(Status.BLOCKED, pos.immutable(), scan.obstacleCount());
		}
		for (ItemStack drop : drops) {
			if (!drop.isEmpty()) de.villagerplus.job.JobUtil.give(level, worker, drop.copy());
		}
		return new Scan(Status.CLEARED, pos.immutable(), scan.obstacleCount());
	}

	private static boolean safeNaturalLeaves(BlockState state) {
		return !state.hasBlockEntity() && state.getFluidState().isEmpty() && state.is(BlockTags.LEAVES)
			&& state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}

	private static Status naturalPlantStatus(ServerLevel level, BlockPos pos, BlockPos min, BlockPos max) {
		BlockPos support = pos.below();
		for (int depth = 0; depth < 3; depth++, support = support.below()) {
			if (!level.hasChunkAt(support)) return Status.WAITING_FOR_CHUNK;
			BlockState below = level.getBlockState(support);
			if (safeNaturalGround(level, support, below)) return Status.READY;
			if (level.getBlockState(pos).is(Blocks.SNOW) && safeNaturalLeaves(below)) return naturalLeafStatus(level, support, min, max);
			if (level.getBlockState(pos).is(Blocks.SNOW) && verticalNaturalLog(below)) {
				List<TreeSpan> trees = new ArrayList<>();
				Status status = naturalTreeLogStatus(level, support, below, trees);
				TreeSpan tree = treeAt(trees, support);
				return status == Status.READY && tree != null && treeRootInsideBounds(tree, min, max)
					? Status.READY : status == Status.WAITING_FOR_CHUNK ? status : Status.BLOCKED;
			}
			if (depth < 2 && safeNaturalPlant(below)) continue;
			return Status.BLOCKED;
		}
		return Status.BLOCKED;
	}

	/** Leaves are cleared only over natural ground or a verified natural trunk, never beside built features. */
	private static Status naturalLeafStatus(ServerLevel level, BlockPos pos, BlockPos min, BlockPos max) {
		if (!safeNaturalLeaves(level.getBlockState(pos))) return Status.BLOCKED;
		BlockPos localMin = pos.offset(-2, -1, -2), localMax = pos.offset(2, 2, 2);
		if (!loadedBox(level, localMin, localMax)) return Status.WAITING_FOR_CHUNK;
		if (nearBuiltTreeFeature(level, pos, pos)) return Status.BLOCKED;
		BlockPos below = pos.below();
		for (int depth = 0; depth < 32 && below.getY() > level.getMinY(); depth++, below = below.below()) {
			if (!level.hasChunkAt(below)) return Status.WAITING_FOR_CHUNK;
			BlockState state = level.getBlockState(below);
			if (safeNaturalGround(level, below, state)) return Status.READY;
			if (verticalNaturalLog(state)) {
				List<TreeSpan> trees = new ArrayList<>();
				Status treeStatus = naturalTreeLogStatus(level, below, state, trees);
				TreeSpan tree = treeAt(trees, below);
				return treeStatus == Status.READY && tree != null && treeRootInsideBounds(tree, min, max)
					? Status.READY : treeStatus == Status.WAITING_FOR_CHUNK ? treeStatus : Status.BLOCKED;
			}
			if (state.isAir() || safeNaturalLeaves(state) || safeNaturalPlant(state)) continue;
			return Status.BLOCKED;
		}
		return Status.BLOCKED;
	}

	private static boolean hasPoiAt(ServerLevel level, BlockPos pos) {
		return level.getPoiManager().getInRange(poi -> true, pos, 0, PoiManager.Occupancy.ANY).findAny().isPresent();
	}

	private static TreeSpan treeAt(List<TreeSpan> trees, BlockPos pos) {
		for (TreeSpan tree : trees) {
			if (tree.x() == pos.getX() && tree.z() == pos.getZ()
				&& pos.getY() >= tree.minY() && pos.getY() <= tree.maxY()) return tree;
		}
		return null;
	}

	private static boolean treeRootInsideBounds(TreeSpan tree, BlockPos min, BlockPos max) {
		return tree.x() >= min.getX() && tree.x() <= max.getX() && tree.z() >= min.getZ() && tree.z() <= max.getZ()
			&& tree.minY() >= min.getY() && tree.minY() <= max.getY();
	}

	private static boolean safeNaturalTarget(ServerLevel level, BlockPos pos, BlockState state, BlockPos min, BlockPos max) {
		if (state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty() || hasPoiAt(level, pos)) return false;
		if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
			List<TreeSpan> trees = new ArrayList<>();
			Status status = naturalTreeLogStatus(level, pos, state, trees);
			TreeSpan tree = treeAt(trees, pos);
			return status == Status.READY && tree != null && treeRootInsideBounds(tree, min, max);
		}
		if (safeNaturalLeaves(state)) return naturalLeafStatus(level, pos, min, max) == Status.READY;
		return safeNaturalPlant(state) && naturalPlantStatus(level, pos, min, max) == Status.READY
			|| HouseLotPreparation.isNaturalEarthCutCandidate(level, pos, state, min, max);
	}

	private static ItemStack carriedTool(Container inventory, BlockState state) {
		if (inventory == null) return ItemStack.EMPTY;
		if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) return findTool(inventory, stack -> stack.is(ItemTags.AXES));
		if (state.is(BlockTags.LEAVES)) return findTool(inventory, stack -> stack.is(Items.SHEARS));
		if (HouseLotPreparation.isLevelableNaturalEarth(state)) return findTool(inventory, stack -> stack.is(ItemTags.SHOVELS));
		return ItemStack.EMPTY;
	}

	private static ItemStack findTool(Container inventory, java.util.function.Predicate<ItemStack> matches) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matches.test(stack)) return stack;
		}
		return ItemStack.EMPTY;
	}

	/** Require a vertical natural-log column rooted in natural soil with nearby nonpersistent canopy. */
	private static Status naturalTreeLogStatus(ServerLevel level, BlockPos pos, BlockState state) {
		return naturalTreeLogStatus(level, pos, state, new ArrayList<>());
	}

	private static Status naturalTreeLogStatus(ServerLevel level, BlockPos pos, BlockState state, List<TreeSpan> knownTrees) {
		if (!state.hasProperty(RotatedPillarBlock.AXIS)
			|| state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) return Status.BLOCKED;
		BlockPos root = pos.immutable();
		int searched = 0;
		while (searched < MAX_NATURAL_TREE_HEIGHT && root.getY() > level.getMinY() + 1) {
			BlockPos below = root.below();
			if (!level.hasChunkAt(below)) return Status.WAITING_FOR_CHUNK;
			BlockState belowState = level.getBlockState(below);
			if (!verticalNaturalLog(belowState)) break;
			root = below;
			searched++;
		}
		if (searched == MAX_NATURAL_TREE_HEIGHT) {
			if (!level.hasChunkAt(root.below())) return Status.WAITING_FOR_CHUNK;
			if (verticalNaturalLog(level.getBlockState(root.below()))) return Status.BLOCKED;
		}
		for (TreeSpan tree : knownTrees) {
			if (tree.x() == root.getX() && tree.z() == root.getZ() && tree.minY() == root.getY()) return tree.status();
		}
		BlockPos soil = root.below();
		if (!level.hasChunkAt(soil)) return Status.WAITING_FOR_CHUNK;
		if (!safeNaturalGround(level, soil, level.getBlockState(soil))) return Status.BLOCKED;

		BlockPos top = pos.immutable();
		searched = 0;
		while (searched < MAX_NATURAL_TREE_HEIGHT && top.getY() < level.getMaxY() - 1) {
			BlockPos above = top.above();
			if (!level.hasChunkAt(above)) return Status.WAITING_FOR_CHUNK;
			if (!verticalNaturalLog(level.getBlockState(above))) break;
			top = above;
			searched++;
		}
		if (searched == MAX_NATURAL_TREE_HEIGHT) {
			if (!level.hasChunkAt(top.above())) return Status.WAITING_FOR_CHUNK;
			if (verticalNaturalLog(level.getBlockState(top.above()))) return Status.BLOCKED;
		}
		BlockPos canopyMin = root.offset(-3, -1, -3);
		// Keep the original canopy discoverable after an upper trunk segment was cleared on a prior tick.
		BlockPos canopyMax = naturalCanopyMax(level, root, top);
		if (!loadedBox(level, canopyMin, canopyMax)) return Status.WAITING_FOR_CHUNK;
		Status canopy = naturalCanopyStatus(level, root, top);
		Status result = canopy;
		if (result == Status.READY && nearBuiltTreeFeature(level, root, top)) {
			result = Status.BLOCKED;
		}
		knownTrees.add(new TreeSpan(root.getX(), root.getZ(), root.getY(), top.getY(), result));
		return result;
	}

	private static boolean verticalNaturalLog(BlockState state) {
		return state.is(BlockTags.OVERWORLD_NATURAL_LOGS) && state.hasProperty(RotatedPillarBlock.AXIS)
			&& state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y
			&& !state.hasBlockEntity() && state.getFluidState().isEmpty();
	}

	private static Status naturalCanopyStatus(ServerLevel level, BlockPos root, BlockPos top) {
		BlockPos min = root.offset(-3, -1, -3);
		BlockPos max = naturalCanopyMax(level, root, top);
		boolean unloaded = false;
		for (BlockPos leaf : BlockPos.betweenClosed(min, max)) {
			if (!level.hasChunkAt(leaf)) { unloaded = true; continue; }
			if (safeNaturalLeaves(level.getBlockState(leaf))) return Status.READY;
		}
		return unloaded ? Status.WAITING_FOR_CHUNK : Status.BLOCKED;
	}

	private static BlockPos naturalCanopyMax(ServerLevel level, BlockPos root, BlockPos top) {
		int searchTop = Math.max(top.getY() + 8, root.getY() + MAX_NATURAL_TREE_HEIGHT + 8);
		return new BlockPos(root.getX() + 3, Math.min(level.getMaxY() - 1, searchTop), root.getZ() + 3);
	}

	private static boolean nearBuiltTreeFeature(ServerLevel level, BlockPos root, BlockPos top) {
		BlockPos max = new BlockPos(root.getX() + 2,
			Math.min(level.getMaxY() - 1, Math.max(top.getY() + 2,
				root.getY() + MAX_NATURAL_TREE_HEIGHT + 8)), root.getZ() + 2);
		for (BlockPos pos : BlockPos.betweenClosed(root.offset(-2, -1, -2), max)) {
			if (!level.hasChunkAt(pos)) return true;
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.OVERWORLD_NATURAL_LOGS) || state.is(BlockTags.LEAVES)
				|| safeNaturalPlant(state) || state.is(BlockTags.FLOWERS) || state.is(Blocks.VINE)
				|| state.is(Blocks.RED_MUSHROOM) || state.is(Blocks.BROWN_MUSHROOM)
				|| state.is(Blocks.SWEET_BERRY_BUSH) || VillageStyle.natural(state)) continue;
			return true;
		}
		return false;
	}

	private static boolean canFitAllDrops(Container inventory, java.util.List<ItemStack> drops) {
		SimpleContainer simulated = new SimpleContainer(inventory.getContainerSize());
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) simulated.setItem(slot, inventory.getItem(slot).copy());
		for (ItemStack drop : drops) if (!drop.isEmpty() && !simulated.addItem(drop.copy()).isEmpty()) return false;
		return true;
	}

	private static boolean loadedBox(ServerLevel level, BlockPos min, BlockPos max) {
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) if (!level.hasChunkAt(pos)) return false;
		return true;
	}

	/** Only common replaceable ground cover is eligible. Logs, leaves, flowers, crops, fluids and built blocks stay. */
	private static boolean safeNaturalPlant(BlockState state) {
		if (!state.canBeReplaced() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) return false;
		return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
			|| state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
			|| state.is(Blocks.DEAD_BUSH) || state.is(Blocks.SNOW);
	}

	private static boolean safeNaturalGround(ServerLevel level, BlockPos pos, BlockState state) {
		return !state.hasBlockEntity() && level.getBlockEntity(pos) == null && state.getFluidState().isEmpty()
			&& state.isFaceSturdy(level, pos, Direction.UP) && VillageStyle.natural(state)
			&& !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !state.is(Blocks.BEDROCK)
			&& !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BLUE_ICE);
	}
}
