package de.villagerplus.build;

import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Woodcutting;
import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Bounded, restart-safe natural clearance for a committed house lot. */
public final class HouseLotPreparation {
	private HouseLotPreparation() { }
	/** Must remain aligned with NaturalSitePreparation.SitePlan's bounded 17x17 maximum. */
	public static final int MAX_LOT_HALF = 8;
	private static final int MAX_INSPECT_VOLUME = 4096;
	private static final int MAX_NATURAL_EARTH_RELIEF = 3;

	/** A newly reserved lot keeps at least one usable block beyond its actual house-and-yard footprint when possible. */
	public static int minimumExpandedLotHalf(int footprintHalf) {
		if (footprintHalf < 0 || footprintHalf > MAX_LOT_HALF) return -1;
		return Math.min(MAX_LOT_HALF, footprintHalf + 1);
	}

	public enum Status { READY, CLEARABLE, CLEARED, WAITING_FOR_CHUNK, WAITING_FOR_ENTITY, WAITING_FOR_INVENTORY, WAITING_FOR_TOOL, BLOCKED }
	public record Scan(Status status, BlockPos next, int obstacleCount) { }
	public record Corridor(Status status, BlockPos road, List<BlockPos> cells) {
		public Corridor {
			cells = cells == null ? List.of() : List.copyOf(cells);
		}
	}
	private record Candidate(BlockPos pos, BlockState state, int priority) { }
	private record TreeSpan(int x, int z, int minY, int maxY, TreeStatus status) { }
	private record Ground(Status status, BlockPos pos) { }

	private static final int MAX_CORRIDOR_STEPS = 24;
	private static final int MAX_CORRIDOR_TREE_HEIGHT = 48;

	/**
	 * The saved plan origin is the lot identity. The next target is always recomputed from the remaining world
	 * blocks in a stable order, so its cursor survives a save/restart without a second mutable counter.
	 */
	public static Scan inspect(ServerLevel level, BlockPos min, BlockPos max, int maxObstacles) {
		if (level == null || min == null || max == null || maxObstacles < 0
			|| min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
			return new Scan(Status.BLOCKED, null, 0);
		}
		long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
		if (volume <= 0 || volume > MAX_INSPECT_VOLUME) return new Scan(Status.BLOCKED, null, 0);
		List<BlockPos> positions = new ArrayList<>((int) volume);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) positions.add(pos.immutable());
		return inspect(level, positions, maxObstacles, min, max);
	}

	/** Inspect only the supplied cells; corridor preparation uses this to keep its work mask narrow and exact. */
	public static Scan inspect(ServerLevel level, List<BlockPos> positions, int maxObstacles) {
		return inspect(level, positions, maxObstacles, null, null);
	}

	private static Scan inspect(ServerLevel level, List<BlockPos> positions, int maxObstacles, BlockPos lotMin, BlockPos lotMax) {
		if (level == null || positions == null || maxObstacles < 0 || positions.size() > MAX_INSPECT_VOLUME) {
			return new Scan(Status.BLOCKED, null, 0);
		}

		List<Candidate> candidates = new ArrayList<>();
		List<TreeSpan> treeSpans = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		for (BlockPos supplied : positions) {
			if (supplied == null || !visited.add(supplied.asLong())) continue;
			BlockPos pos = supplied.immutable();
			if (!level.hasChunkAt(pos)) return new Scan(Status.WAITING_FOR_CHUNK, null, candidates.size());
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;
			if (state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) {
				return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
			}
			if (hasPoiAt(level, pos)) return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());

			int priority;
			if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
				TreeStatus tree = treeLogStatus(level, pos, state, treeSpans);
				if (tree == TreeStatus.WAITING_FOR_CHUNK) return new Scan(Status.WAITING_FOR_CHUNK, pos.immutable(), candidates.size());
				if (tree != TreeStatus.READY) return new Scan(Status.BLOCKED, pos.immutable(), candidates.size());
				priority = 0; // Keep the rooted trunk intact until its highest obstructing log is removed.
			} else if (isNonPersistentNaturalLeaf(state)) {
				priority = 1;
			} else if (isSmallNaturalPlant(state)) {
				priority = state.is(Blocks.SNOW) ? 0 : 2;
			} else if (isNaturalEarthCutCandidate(level, pos, state, lotMin, lotMax)) {
				priority = 3; // A shovel peels the highest verified earth layer after all cover and trees are gone.
			} else if (isNaturalEarthSupport(level, pos, state, lotMin, lotMax)
				|| isEarthUnderNaturalCover(level, pos, state, lotMin, lotMax)) {
				// Buried layers under an eligible rise are support, not obstacles. The exposed top
				// layer remains the only cursor target and becomes the next layer after it is removed.
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

	/**
	 * Builds a one-block-wide, deterministic access strip from just outside the reserved lot to a saved road cell.
	 * It never moves the ground: unsupported surfaces, steep steps, long routes, and unloaded chunks fail closed.
	 */
	public static Corridor accessCorridor(ServerLevel level, BlockPos lotMin, BlockPos lotMax, BlockPos road, int maxSteps) {
		if (road == null) return new Corridor(Status.READY, null, List.of());
		if (level == null || lotMin == null || lotMax == null || maxSteps < 1 || maxSteps > MAX_CORRIDOR_STEPS
			|| lotMin.getX() > lotMax.getX() || lotMin.getZ() > lotMax.getZ()) {
			return new Corridor(Status.BLOCKED, road, List.of());
		}
		if (!level.hasChunkAt(road)) return new Corridor(Status.WAITING_FOR_CHUNK, road, List.of());
		if (!isSavedRoadSurface(level.getBlockState(road))) return new Corridor(Status.BLOCKED, road, List.of());
		if (insideLotXZ(lotMin, lotMax, road)) {
			Ground ground = corridorGround(level, road.getX(), road.getZ(), true);
			if (ground.status() != Status.READY) return new Corridor(ground.status(), road, List.of());
			if (!road.equals(ground.pos())) return new Corridor(Status.BLOCKED, road, List.of());
			for (int dy = 1; dy <= 2; dy++) {
				BlockPos headroom = road.above(dy);
				if (!level.hasChunkAt(headroom)) return new Corridor(Status.WAITING_FOR_CHUNK, road, List.of());
				if (!level.getBlockState(headroom).isAir()) return new Corridor(Status.BLOCKED, road, List.of());
			}
			// A trusted road already inside the lot is the access endpoint, never a clearance target.
			return new Corridor(Status.READY, road.immutable(), List.of());
		}

		BlockPos start = corridorStart(lotMin, lotMax, road);
		if (start == null) return new Corridor(Status.BLOCKED, road, List.of());
		long distance = Math.abs((long) start.getX() - road.getX()) + Math.abs((long) start.getZ() - road.getZ());
		if (distance < 1 || distance > maxSteps) return new Corridor(Status.BLOCKED, road, List.of());

		List<BlockPos> columns = new ArrayList<>((int) distance + 1);
		int x = start.getX(), z = start.getZ();
		while (x != road.getX()) {
			columns.add(new BlockPos(x, road.getY(), z));
			x += Integer.signum(road.getX() - x);
		}
		while (z != road.getZ()) {
			columns.add(new BlockPos(x, road.getY(), z));
			z += Integer.signum(road.getZ() - z);
		}
		if (columns.isEmpty() || columns.get(columns.size() - 1).getX() == road.getX()
			&& columns.get(columns.size() - 1).getZ() == road.getZ()) {
			return new Corridor(Status.BLOCKED, road, List.of());
		}

		List<BlockPos> clearance = new ArrayList<>((int) distance * 8);
		BlockPos previousGround = null;
		for (int index = 0; index <= columns.size(); index++) {
			BlockPos column = index == columns.size() ? road : columns.get(index);
			Ground ground = corridorGround(level, column.getX(), column.getZ(), index == columns.size());
			if (ground.status() != Status.READY) return new Corridor(ground.status(), road, List.of());
			if (previousGround != null && Math.abs(previousGround.getY() - ground.pos().getY()) > 1) {
				return new Corridor(Status.BLOCKED, road, List.of());
			}
			previousGround = ground.pos();
			if (index == columns.size()) {
				for (int dy = 1; dy <= 2; dy++) {
					BlockPos headroom = ground.pos().above(dy);
					if (!level.hasChunkAt(headroom)) return new Corridor(Status.WAITING_FOR_CHUNK, road, List.of());
					if (!level.getBlockState(headroom).isAir()) return new Corridor(Status.BLOCKED, road, List.of());
				}
				continue; // The saved road cell itself is never part of the clearance work mask.
			}
			if (ground.pos().getY() + 3 >= level.getMaxY()) return new Corridor(Status.BLOCKED, road, List.of());
			for (int dy = 1; dy <= MAX_CORRIDOR_TREE_HEIGHT; dy++) {
				BlockPos cell = ground.pos().above(dy);
				if (cell.getY() >= level.getMaxY()) break;
				if (!level.hasChunkAt(cell)) return new Corridor(Status.WAITING_FOR_CHUNK, road, List.of());
				clearance.add(cell.immutable());
			}
		}
		return new Corridor(Status.READY, road.immutable(), clearance);
	}

	private static boolean insideLotXZ(BlockPos min, BlockPos max, BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
			&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	private static BlockPos corridorStart(BlockPos min, BlockPos max, BlockPos road) {
		int xGap = road.getX() < min.getX() ? min.getX() - road.getX()
			: road.getX() > max.getX() ? road.getX() - max.getX() : 0;
		int zGap = road.getZ() < min.getZ() ? min.getZ() - road.getZ()
			: road.getZ() > max.getZ() ? road.getZ() - max.getZ() : 0;
		if (xGap == 0 && zGap == 0) return null; // Do not turn a saved road inside the lot into a clearance target.
		if (xGap >= zGap && xGap > 0) {
			int x = road.getX() < min.getX() ? min.getX() - 1 : max.getX() + 1;
			int z = Math.max(min.getZ(), Math.min(max.getZ(), road.getZ()));
			return new BlockPos(x, road.getY(), z);
		}
		int z = road.getZ() < min.getZ() ? min.getZ() - 1 : max.getZ() + 1;
		int x = Math.max(min.getX(), Math.min(max.getX(), road.getX()));
		return new BlockPos(x, road.getY(), z);
	}

	private static Ground corridorGround(ServerLevel level, int x, int z, boolean road) {
		BlockPos column = new BlockPos(x, level.getMinY(), z);
		if (!level.hasChunkAt(column)) return new Ground(Status.WAITING_FOR_CHUNK, null);
		int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int minY = Math.max(level.getMinY(), topY - MAX_CORRIDOR_TREE_HEIGHT);
		for (int y = topY; y >= minY; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			if (!level.hasChunkAt(pos)) return new Ground(Status.WAITING_FOR_CHUNK, null);
			BlockState state = level.getBlockState(pos);
			if (corridorSupport(level, pos, state, road)) return new Ground(Status.READY, pos.immutable());
			if (!state.isAir() && !corridorOvergrowth(state)) return new Ground(Status.BLOCKED, pos.immutable());
		}
		return new Ground(Status.BLOCKED, null);
	}

	private static boolean corridorSupport(ServerLevel level, BlockPos pos, BlockState state, boolean road) {
		if (state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) return false;
		// Dirt paths have a deliberately lowered collision shape, so their UP face is not a full sturdy face.
		// A saved, whitelisted road surface is still a valid endpoint and must remain unmodified.
		boolean savedRoad = road && isSavedRoadSurface(state);
		if (!savedRoad && !state.isFaceSturdy(level, pos, Direction.UP)) return false;
		boolean soil = state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
			|| state.is(Blocks.COARSE_DIRT) || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT_PATH);
		return soil || savedRoad;
	}

	private static boolean corridorOvergrowth(BlockState state) {
		if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) return verticalNaturalLog(state);
		return isNonPersistentNaturalLeaf(state) || isSmallNaturalPlant(state);
	}

	private static boolean isSavedRoadSurface(BlockState state) {
		return state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL) || state.is(Blocks.SMOOTH_SANDSTONE)
			|| state.is(Blocks.SANDSTONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)
			|| state.is(Blocks.SPRUCE_PLANKS);
	}

	/** Remove one verified block per visit, using the builder's actual carried tool and conserving every predicted drop. */
	public static Scan clearOne(ServerLevel level, BlockPos min, BlockPos max, int maxObstacles, Ctx context) {
		return clearOne(level, inspect(level, min, max, maxObstacles), context, min, max);
	}

	/** Remove one verified block from the exact cells returned by {@link #accessCorridor}. */
	public static Scan clearOne(ServerLevel level, List<BlockPos> positions, int maxObstacles, Ctx context) {
		return clearOne(level, inspect(level, positions, maxObstacles), context, null, null);
	}

	private static Scan clearOne(ServerLevel level, Scan scan, Ctx context, BlockPos lotMin, BlockPos lotMax) {
		if (scan.status() != Status.CLEARABLE) return scan;
		if (context == null || context.v() == null) return new Scan(Status.BLOCKED, scan.next(), scan.obstacleCount());
		Villager worker = context.v();
		BlockPos pos = scan.next();
		BlockState state = level.getBlockState(pos);
		boolean earth = isLevelableNaturalEarth(state);
		AABB cell = earth ? new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 2.0, pos.getZ() + 1.0)
			: new AABB(pos);
		if (!level.getEntitiesOfClass(LivingEntity.class, cell, entity -> entity.isAlive() && entity != worker).isEmpty()) {
			return new Scan(Status.WAITING_FOR_ENTITY, pos, scan.obstacleCount());
		}
		if (state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()
			|| hasPoiAt(level, pos) || !safeTarget(level, pos, state, lotMin, lotMax)) {
			return new Scan(Status.BLOCKED, pos, scan.obstacleCount());
		}
		ItemStack tool = carriedTool(worker.getInventory(), state);
		if (earth && tool.isEmpty()) return new Scan(Status.WAITING_FOR_TOOL, pos, scan.obstacleCount());
		List<ItemStack> drops = Block.getDrops(state, level, pos, null, worker, tool.copy());
		if (!canFitAll(worker.getInventory(), drops)) return new Scan(Status.WAITING_FOR_INVENTORY, pos, scan.obstacleCount());
		if (!level.getBlockState(pos).equals(state) || !level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
			return new Scan(Status.BLOCKED, pos, scan.obstacleCount());
		}
		Anim.blockBreak(level, pos, state);
		Anim.hold(context, tool.isEmpty() ? Items.AIR : tool.getItem());
		if (!tool.isEmpty() && tool.isDamageableItem()) tool.hurtAndBreak(1, level, null, ignored -> { });
		JobUtil.swing(worker);
		for (ItemStack drop : drops) if (!drop.isEmpty()) JobUtil.give(level, worker, drop.copy());
		return new Scan(Status.CLEARED, pos, scan.obstacleCount());
	}

	static boolean isSmallNaturalPlant(BlockState state) {
		return state != null && state.canBeReplaced() && !state.hasBlockEntity() && state.getFluidState().isEmpty()
			&& (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)
				|| state.is(Blocks.LARGE_FERN) || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.SNOW));
	}

	static boolean isNonPersistentNaturalLeaf(BlockState state) {
		return state != null && state.is(BlockTags.LEAVES) && !state.hasBlockEntity() && state.getFluidState().isEmpty()
			&& state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}

	private static boolean safeTarget(ServerLevel level, BlockPos pos, BlockState state, BlockPos lotMin, BlockPos lotMax) {
		if (isSmallNaturalPlant(state) || isNonPersistentNaturalLeaf(state)) return true;
		return state.is(BlockTags.OVERWORLD_NATURAL_LOGS)
			&& treeLogStatus(level, pos, state, new ArrayList<>()) == TreeStatus.READY
			|| isNaturalEarthCutCandidate(level, pos, state, lotMin, lotMax);
	}

	/** Stable natural earth that can be pared down with a real shovel; gravity blocks, builds, farmland and stone are excluded. */
	public static boolean isLevelableNaturalEarth(BlockState state) {
		return state != null && !state.hasBlockEntity() && state.getFluidState().isEmpty()
			&& (state.is(BlockTags.DIRT) || state.is(BlockTags.GRASS_BLOCKS) || state.is(Blocks.MUD) || state.is(Blocks.CLAY))
			&& !state.is(Blocks.FARMLAND) && VillageStyle.natural(state);
	}

	/** Only the exposed top earth block above the lot's lowest natural surface may be removed. */
	public static boolean isNaturalEarthCutCandidate(ServerLevel level, BlockPos pos, BlockState state,
		BlockPos lotMin, BlockPos lotMax) {
		if (level == null || pos == null || lotMin == null || lotMax == null || !isLevelableNaturalEarth(state)
			|| pos.getY() < lotMin.getY() || pos.getY() > lotMax.getY() || !level.hasChunkAt(pos)) return false;
		BlockPos above = pos.above();
		if (!level.hasChunkAt(above) || !level.getBlockState(above).isAir()) return false;
		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		return surfaceY > lotMin.getY() && surfaceY - lotMin.getY() <= MAX_NATURAL_EARTH_RELIEF
			&& pos.getY() == surfaceY - 1;
	}

	/** A lower natural layer is safe support only while this column has an exposed, bounded layer to cut first. */
	public static boolean isNaturalEarthSupport(ServerLevel level, BlockPos pos, BlockState state,
		BlockPos lotMin, BlockPos lotMax) {
		if (level == null || pos == null || lotMin == null || lotMax == null || !isLevelableNaturalEarth(state)
			|| pos.getY() < lotMin.getY() || pos.getY() > lotMax.getY() || !level.hasChunkAt(pos)) return false;
		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		BlockPos exposed = new BlockPos(pos.getX(), surfaceY - 1, pos.getZ());
		return exposed.getY() > pos.getY() && exposed.getY() <= lotMax.getY()
			&& isNaturalEarthCutCandidate(level, exposed, level.getBlockState(exposed), lotMin, lotMax);
	}

	/** Defer earth beneath in-bounds vegetation until the normal ordered scan removes the cover. */
	public static boolean isEarthUnderNaturalCover(ServerLevel level, BlockPos pos, BlockState state,
		BlockPos lotMin, BlockPos lotMax) {
		if (level == null || pos == null || lotMin == null || lotMax == null || !isLevelableNaturalEarth(state)
			|| pos.getY() < lotMin.getY() || pos.getY() >= lotMax.getY()) return false;
		for (int y = pos.getY() + 1; y <= Math.min(lotMax.getY(), lotMin.getY() + MAX_NATURAL_EARTH_RELIEF); y++) {
			BlockPos above = new BlockPos(pos.getX(), y, pos.getZ());
			if (!level.hasChunkAt(above)) return false;
			BlockState cover = level.getBlockState(above);
			if (cover.hasBlockEntity() || level.getBlockEntity(above) != null || !cover.getFluidState().isEmpty()
				|| hasPoiAt(level, above)) return false;
			if (isLevelableNaturalEarth(cover)) continue;
			return isSmallNaturalPlant(cover) || isNonPersistentNaturalLeaf(cover) || verticalNaturalLog(cover);
		}
		return false;
	}

	private enum TreeStatus { READY, WAITING_FOR_CHUNK, BLOCKED }

	/** Only an upright trunk rooted in natural soil, with a natural canopy and no nearby built timber, can be removed. */
	private static TreeStatus treeLogStatus(ServerLevel level, BlockPos pos, BlockState state, List<TreeSpan> knownTrees) {
		for (TreeSpan span : knownTrees) {
			if (span.x() == pos.getX() && span.z() == pos.getZ() && pos.getY() >= span.minY() && pos.getY() <= span.maxY()) {
				return span.status();
			}
		}
		if (!verticalNaturalLog(state)) return TreeStatus.BLOCKED;
		BlockPos root = pos.immutable();
		int depth = 0;
		while (depth < 32 && root.getY() > level.getMinY() + 1) {
			BlockPos below = root.below();
			if (!level.hasChunkAt(below)) return TreeStatus.WAITING_FOR_CHUNK;
			if (!verticalNaturalLog(level.getBlockState(below))) break;
			root = below;
			depth++;
		}
		if (depth == 32) {
			BlockPos below = root.below();
			if (!level.hasChunkAt(below)) return TreeStatus.WAITING_FOR_CHUNK;
			if (verticalNaturalLog(level.getBlockState(below))) return TreeStatus.BLOCKED;
		}
		BlockPos soil = root.below();
		if (!level.hasChunkAt(soil)) return TreeStatus.WAITING_FOR_CHUNK;
		BlockState soilState = level.getBlockState(soil);
		if (!safeNaturalSoil(level, soil, soilState)) return TreeStatus.BLOCKED;

		int topY = root.getY();
		int height = 0;
		while (height < 48 && topY < level.getMaxY() - 1) {
			BlockPos above = new BlockPos(root.getX(), topY + 1, root.getZ());
			if (!level.hasChunkAt(above)) return TreeStatus.WAITING_FOR_CHUNK;
			if (!verticalNaturalLog(level.getBlockState(above))) break;
			topY++;
			height++;
		}
		if (height == 48) {
			BlockPos above = new BlockPos(root.getX(), topY + 1, root.getZ());
			if (!level.hasChunkAt(above)) return TreeStatus.WAITING_FOR_CHUNK;
			if (verticalNaturalLog(level.getBlockState(above))) return TreeStatus.BLOCKED;
		}
		int canopyMinY = root.getY() + Math.max(1, height - 1);
		int canopyMaxY = root.getY() + Math.min(48, height + 4);
		if (!loadedBox(level, root.offset(-3, canopyMinY - root.getY(), -3),
			new BlockPos(root.getX() + 3, canopyMaxY, root.getZ() + 3))) return TreeStatus.WAITING_FOR_CHUNK;
		if (!loadedBox(level, root.offset(-2, -1, -2), root.offset(2, 12, 2))) return TreeStatus.WAITING_FOR_CHUNK;
		TreeStatus result = Woodcutting.isHarvestableTree(level, root) ? TreeStatus.READY : TreeStatus.BLOCKED;
		knownTrees.add(new TreeSpan(root.getX(), root.getZ(), root.getY(), topY, result));
		return result;
	}

	private static boolean verticalNaturalLog(BlockState state) {
		return state.is(BlockTags.OVERWORLD_NATURAL_LOGS) && state.hasProperty(RotatedPillarBlock.AXIS)
			&& state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y
			&& !state.hasBlockEntity() && state.getFluidState().isEmpty();
	}

	private static boolean safeNaturalSoil(ServerLevel level, BlockPos pos, BlockState state) {
		return !state.hasBlockEntity() && level.getBlockEntity(pos) == null && state.getFluidState().isEmpty()
			&& state.isFaceSturdy(level, pos, Direction.UP) && VillageStyle.natural(state)
			&& !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !state.is(Blocks.FARMLAND)
			&& !state.is(Blocks.BEDROCK) && !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BLUE_ICE);
	}

	private static boolean loadedBox(ServerLevel level, BlockPos min, BlockPos max) {
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) if (!level.hasChunkAt(pos)) return false;
		return true;
	}

	private static boolean hasPoiAt(ServerLevel level, BlockPos pos) {
		return level.getPoiManager().getInRange(poi -> true, pos, 0, PoiManager.Occupancy.ANY).findAny().isPresent();
	}

	private static ItemStack carriedTool(Container inventory, BlockState state) {
		if (inventory == null) return ItemStack.EMPTY;
		if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) return findTool(inventory, stack -> stack.is(ItemTags.AXES));
		if (state.is(BlockTags.LEAVES)) return findTool(inventory, stack -> stack.is(Items.SHEARS));
		if (isLevelableNaturalEarth(state)) return findTool(inventory, stack -> stack.is(ItemTags.SHOVELS));
		return ItemStack.EMPTY;
	}

	private static ItemStack findTool(Container inventory, java.util.function.Predicate<ItemStack> matches) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matches.test(stack)) return stack;
		}
		return ItemStack.EMPTY;
	}

	private static boolean canFitAll(Container inventory, List<ItemStack> drops) {
		if (inventory == null) return drops.stream().allMatch(ItemStack::isEmpty);
		SimpleContainer simulated = new SimpleContainer(inventory.getContainerSize());
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) simulated.setItem(slot, inventory.getItem(slot).copy());
		for (ItemStack drop : drops) if (!drop.isEmpty() && !simulated.addItem(drop.copy()).isEmpty()) return false;
		return true;
	}
}
