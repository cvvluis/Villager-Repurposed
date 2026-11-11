package de.villagerplus.build;

import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Kopiervorlage: ein bestehendes Haus des Dorfes (um ein Bett herum). Damit sehen neue Haeuser genauso aus wie die
 * Haeuser in der Umgebung - auch bei Dorfern aus Weltgenerator-Mods (z.B. Overhauled Villages).
 *
 * <p>Abgrenzung: Bei generierten Doerfern liefert die Strukturverwaltung das Jigsaw-Teil (Piece), in dem das Bett liegt -
 * dessen Bounding Box ist genau das Haus, egal wie gross oder verschachtelt es ist. Bei selbstgebauten Haeusern wird
 * stattdessen per Flood-Fill um das Bett herum eingelesen. Bloecke mit Blockentity (Truhen, Schilder, Arbeitsbloecke)
 * werden nicht kopiert.
 */
public final class HouseTemplate {
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

	/** Keine praktische Obergrenze mehr: auch sehr grosse Vorlagenhaeuser werden uebernommen (Material wird bei Bedarf angefragt). */
	private static final int MAX_BLOCKS = 200_000;
	public static final int MAX_FOOTPRINT_SIZE = 96;
	private static final int MAX_SIZE = MAX_FOOTPRINT_SIZE;
	private static final int MAX_STRUCTURE_PIECES_TO_INSPECT = 1024;
	private static final int SPACING_FLOOD_RADIUS = 8;
	// Leave one empty safety layer below the bed-level floor: ordinary houses are expected to touch bedY - 1.
	private static final int SPACING_FLOOD_HEIGHT_BELOW = 2;
	private static final int SPACING_FLOOD_HEIGHT_ABOVE = 8;
	private static final int SPACING_FLOOD_MAX_BLOCKS = 1024;
	private static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};

	/** Ausdehnung nach der Drehung, relativ zum Bett (Anker). */
	public record Bounds(int minX, int maxX, int minZ, int maxZ, int maxY) { }

	/** Absolute, bounded world-space envelope used only for house-spacing checks. */
	public record Footprint(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		public boolean tooCloseTo(int otherMinX, int otherMaxX, int otherMinZ, int otherMaxZ, int margin) {
			int gapX = Math.max(0, Math.max(minX - otherMaxX, otherMinX - maxX));
			int gapZ = Math.max(0, Math.max(minZ - otherMaxZ, otherMinZ - maxZ));
			return gapX <= margin && gapZ <= margin;
		}

		public boolean overlapsY(int min, int max) {
			return maxY >= min && minY <= max;
		}
	}

	private record PieceLookup(boolean found, BoundingBox box) { }

	private record TBlock(BlockPos rel, BlockState state) { }

	private final List<TBlock> blocks;
	private final int sourceBlockCount;
	private final Bounds sourceBounds;
	private final Direction outward;
	private final Architect.SizeTier sizeTier;
	/** Bett des Originalhauses (zum erneuten Einlesen nach einem Neustart). */
	public final BlockPos sourceBed;

	private HouseTemplate(List<TBlock> blocks, int sourceBlockCount, Bounds sourceBounds, Direction outward, BlockPos sourceBed, Architect.SizeTier sizeTier) {
		this.sourceBed = sourceBed;
		this.blocks = blocks;
		this.sourceBlockCount = sourceBlockCount;
		this.sourceBounds = sourceBounds;
		this.outward = outward;
		this.sizeTier = sizeTier;
	}

	/**
	 * Builds a template from ready-made blocks (a shipped structure file) instead of capturing it from the world.
	 * {@code rel} positions are relative to the build centre (floor top = y -1); {@code outward} is the side of the door.
	 */
	public static HouseTemplate fromBlocks(java.util.Map<BlockPos, BlockState> rel, Direction outward) {
		List<TBlock> list = new ArrayList<>(rel.size());
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = 0;
		for (var e : rel.entrySet()) {
			BlockPos p = e.getKey();
			list.add(new TBlock(p, e.getValue()));
			minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
			maxY = Math.max(maxY, p.getY());
		}
		if (list.isEmpty()) return null;
		return new HouseTemplate(list, list.size(), new Bounds(minX, maxX, minZ, maxZ, maxY), outward, BlockPos.ZERO, Architect.SizeTier.COMPACT);
	}

	// --- Erfassen -------------------------------------------------------------------------------

	public static boolean built(ServerLevel level, BlockPos p) {
		return built(level, p, false);
	}

	/** Natural Badlands terracotta is only treated as house material inside a recognized generated piece. */
	static boolean built(ServerLevel level, BlockPos p, boolean generatedPiece) {
		BlockState s = level.getBlockState(p);
		return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced()
			&& (!VillageStyle.natural(s) || (generatedPiece && s.is(BlockTags.BADLANDS_TERRACOTTA)))
			&& !s.is(Blocks.DIRT_PATH) && !s.is(Blocks.FARMLAND);
	}

	/** Das kleinste Jigsaw-Teil einer generierten Struktur, in dem {@code pos} liegt. */
	private static PieceLookup pieceLookup(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return new PieceLookup(false, null);
		StructureStart start = level.structureManager().getStructureWithPieceAt(pos, holder -> true);
		if (start == null || !start.isValid()) {
			return new PieceLookup(false, null);
		}
		BoundingBox best = null;
		long bestVolume = Long.MAX_VALUE;
		int inspected = 0;
		for (StructurePiece piece : start.getPieces()) {
			if (++inspected > MAX_STRUCTURE_PIECES_TO_INSPECT) {
				return new PieceLookup(true, null);
			}
			BoundingBox b = piece.getBoundingBox();
			if (b.isInside(pos)) {
				long volume = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
				if (volume < bestVolume) {
					bestVolume = volume;
					best = b;
				}
			}
		}
		return new PieceLookup(true, best);
	}

	/** Use structure metadata first, then a small loaded-only flood for ordinary bed-and-door houses. */
	public static Footprint homeSpacingFootprint(ServerLevel level, BlockPos bed) {
		if (!level.hasChunkAt(bed) || !(level.getBlockState(bed).getBlock() instanceof BedBlock)) return null;
		PieceLookup lookup = pieceLookup(level, bed);
		// A structure start may contain no useful house piece, or its nearest piece may exceed our
		// accepted bounds. Use it only when it converts to a bounded footprint; otherwise resolve
		// the connected, loaded-only bed-and-door house with the same strict flood limits.
		Footprint structureFootprint = lookup.box() == null ? null : boundedFootprint(lookup.box());
		if (structureFootprint != null) return structureFootprint;
		return boundedHomeFloodFootprint(level, bed);
	}

	/** Converts a structure piece box only when its dimensions stay within the existing template bound. */
	public static Footprint boundedFootprint(BoundingBox box) {
		if (box == null || box.getXSpan() > MAX_SIZE || box.getZSpan() > MAX_SIZE || box.getYSpan() > MAX_SIZE + 10) return null;
		return new Footprint(box.minX(), box.maxX(), box.minY(), box.maxY(), box.minZ(), box.maxZ());
	}

	/** Bounded fallback: only connected house blocks in a small, fully loaded volume are accepted. */
	private static Footprint boundedHomeFloodFootprint(ServerLevel level, BlockPos bed) {
		int minX = bed.getX() - SPACING_FLOOD_RADIUS, maxX = bed.getX() + SPACING_FLOOD_RADIUS;
		int minZ = bed.getZ() - SPACING_FLOOD_RADIUS, maxZ = bed.getZ() + SPACING_FLOOD_RADIUS;
		int minY = bed.getY() - SPACING_FLOOD_HEIGHT_BELOW, maxY = bed.getY() + SPACING_FLOOD_HEIGHT_ABOVE;
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (BlockPos pos : BlockPos.betweenClosed(bed.offset(-2, -1, -2), bed.offset(2, 3, 2))) {
			if (!inside(pos, minX, maxX, minY, maxY, minZ, maxZ)) continue;
			if (!level.hasChunkAt(pos)) return null;
			if (built(level, pos) && seen.add(pos.asLong())) {
				queue.add(pos.immutable());
			}
		}
		int found = 0;
		int foundMinX = Integer.MAX_VALUE, foundMaxX = Integer.MIN_VALUE;
		int foundMinY = Integer.MAX_VALUE, foundMaxY = Integer.MIN_VALUE;
		int foundMinZ = Integer.MAX_VALUE, foundMaxZ = Integer.MIN_VALUE;
		boolean hasBed = false, hasDoor = false;
		while (!queue.isEmpty()) {
			BlockPos pos = queue.removeFirst();
			if (++found > SPACING_FLOOD_MAX_BLOCKS || onBoundary(pos, minX, maxX, minY, maxY, minZ, maxZ)) return null;
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) hasBed = true;
			if (state.getBlock() instanceof DoorBlock) hasDoor = true;
			foundMinX = Math.min(foundMinX, pos.getX()); foundMaxX = Math.max(foundMaxX, pos.getX());
			foundMinY = Math.min(foundMinY, pos.getY()); foundMaxY = Math.max(foundMaxY, pos.getY());
			foundMinZ = Math.min(foundMinZ, pos.getZ()); foundMaxZ = Math.max(foundMaxZ, pos.getZ());
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (next.getY() < bed.getY() - 1 || !inside(next, minX, maxX, minY, maxY, minZ, maxZ)
					|| seen.contains(next.asLong())) continue;
				if (!level.hasChunkAt(next)) return null;
				if (!built(level, next)) continue;
				seen.add(next.asLong());
				queue.addLast(next);
			}
		}
		if (!hasBed || !hasDoor || found < 12) return null;
		return new Footprint(foundMinX, foundMaxX, foundMinY, foundMaxY, foundMinZ, foundMaxZ);
	}

	private static boolean inside(BlockPos pos, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
			&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	private static boolean onBoundary(BlockPos pos, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		return pos.getX() == minX || pos.getX() == maxX || pos.getY() == minY || pos.getY() == maxY
			|| pos.getZ() == minZ || pos.getZ() == maxZ;
	}

	/** Das Jigsaw-Teil einer generierten Struktur, in dem {@code pos} liegt (kleinstes gewinnt), sonst null. */
	private static BoundingBox pieceBox(ServerLevel level, BlockPos pos) {
		return pieceLookup(level, pos).box();
	}

	/** Liest das Haus um ein Bett herum ein. null, wenn es kein klar abgegrenztes Haus mit Tuer ist. */
	public static HouseTemplate capture(ServerLevel level, BlockPos bed) {
		return capture(level, bed, null);
	}

	/** Wie oben; {@code hint} (z.B. die Dorfglocke) bestimmt die Ausrichtung, wenn das Gebaeude keine erkennbare Tuer hat. */
	public static HouseTemplate capture(ServerLevel level, BlockPos bed, BlockPos hint) {
		if (!(level.getBlockState(bed).getBlock() instanceof BedBlock)) {
			return reject(bed, "kein Bett");
		}
		List<BlockPos> found = new ArrayList<>();
		BoundingBox box = pieceBox(level, bed);
		if (box != null) {
			if (box.getXSpan() > MAX_SIZE || box.getZSpan() > MAX_SIZE || box.getYSpan() > MAX_SIZE + 10) {
				return reject(bed, "Struktur-Teil zu gross " + box.getXSpan() + "x" + box.getYSpan() + "x" + box.getZSpan());
			}
			for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
				if (p.getY() >= bed.getY() - 1 && built(level, p, true)) {
					found.add(p.immutable());
				}
			}
		} else if (!flood(level, bed, found)) {
			java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
			for (BlockPos q : found) {
				hist.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath(), 1, Integer::sum);
			}
			return reject(bed, "Flood-Fill zu gross oder unklar (" + found.size() + " Bloecke: " + hist + ")");
		}
		if (found.size() > MAX_BLOCKS) {
			return reject(bed, "zu viele Bloecke: " + found.size());
		}
		HouseTemplate t = assemble(level, bed, found, hint);
		// A Jigsaw sub-piece may contain the bed but omit the door or part of the connected shell.
		// Retry the bounded local scan before falling back to a generic box-shaped house.
		if (t == null && box != null) {
			found.clear();
			if (flood(level, bed, found)) t = assemble(level, bed, found, hint);
		}
		if (t == null) {
			return reject(bed, "keine Tuer, kein Bett oder zu wenige Bloecke (" + found.size() + ")");
		}
		LOGGER.info("Villagers: Repurposed: Haus-Vorlage bei {} uebernommen ({} Bloecke, {})", bed, t.blocks.size(), box != null ? "Struktur-Teil" : "Flood-Fill");
		return t;
	}

	private static HouseTemplate reject(BlockPos bed, String why) {
		LOGGER.info("Villagers: Repurposed: Haus bei {} ist keine Vorlage: {}", bed, why);
		return null;
	}

	/** Flood-Fill fuer Haeuser ohne Struktur-Information. false = zu gross/unklar. */
	private static boolean flood(ServerLevel level, BlockPos bed, List<BlockPos> found) {
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (BlockPos p : BlockPos.betweenClosed(bed.offset(-2, -1, -2), bed.offset(2, 3, 2))) {
			if (built(level, p) && seen.add(p.asLong())) {
				queue.add(p.immutable());
			}
		}
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			found.add(p);
			if (found.size() > MAX_BLOCKS) {
				return false;
			}
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
			if (maxX - minX >= MAX_SIZE || maxZ - minZ >= MAX_SIZE || maxY - minY >= MAX_SIZE) {
				return false;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (n.getY() >= bed.getY() - 1 && !seen.contains(n.asLong()) && built(level, n)) {
					seen.add(n.asLong());
					queue.add(n);
				}
			}
		}
		return true;
	}

	private static HouseTemplate assemble(ServerLevel level, BlockPos bed, List<BlockPos> found, BlockPos hint) {
		// Tueren: bevorzugt eine Aussentuer (eine Seite sieht den Himmel, die andere nicht), die dem Bett am naechsten ist.
		BlockPos outerDoor = null;
		Direction outerDir = null;
		double outerDist = Double.MAX_VALUE;
		BlockPos anyDoor = null;
		double anyDist = Double.MAX_VALUE;
		boolean hasBed = false;
		List<TBlock> blocks = new ArrayList<>();
		for (BlockPos p : found) {
			BlockState s = level.getBlockState(p);
			Block b = s.getBlock();
			if (b instanceof DoorBlock d && d.type().canOpenByHand() && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				double dist = p.distSqr(bed);
				if (dist < anyDist) {
					anyDoor = p;
					anyDist = dist;
				}
				Direction f = s.getValue(DoorBlock.FACING);
				boolean skyA = level.canSeeSky(p.relative(f));
				boolean skyB = level.canSeeSky(p.relative(f.getOpposite()));
				if (skyA != skyB && dist < outerDist) {
					outerDoor = p;
					outerDir = skyA ? f : f.getOpposite();
					outerDist = dist;
				}
			}
			if (b instanceof BedBlock) {
				hasBed |= s.getValue(BedBlock.PART) == BedPart.FOOT;
			} else if (s.hasBlockEntity() || (PoiTypes.hasPoi(s) && !s.is(BlockTags.BEDS))) {
				continue;
			}
			if (b.asItem() == Items.AIR && !(b instanceof DoorBlock)) {
				continue;
			}
			blocks.add(new TBlock(p.subtract(bed), s));
		}
		if (!hasBed || blocks.size() < 12 || anyDoor == null) {
			return null;
		}
		BlockPos frontDoor = outerDoor != null ? outerDoor : anyDoor;
		Direction outward = outerDir != null ? outerDir : Sites.facingTowards(bed, anyDoor);
		blocks = new ArrayList<>(quietExteriorDisplays(blocks, frontDoor.subtract(bed), outward));
		int minX = blocks.stream().mapToInt(b -> b.rel().getX()).min().orElse(0);
		int maxX = blocks.stream().mapToInt(b -> b.rel().getX()).max().orElse(0);
		int minZ = blocks.stream().mapToInt(b -> b.rel().getZ()).min().orElse(0);
		int maxZ = blocks.stream().mapToInt(b -> b.rel().getZ()).max().orElse(0);
		int maxY = blocks.stream().mapToInt(b -> b.rel().getY()).max().orElse(0);
		Bounds sourceBounds = new Bounds(minX, maxX, minZ, maxZ, maxY);
		int width = maxX - minX + 1;
		int depth = maxZ - minZ + 1;
		int interiorArea = Math.max(0, (width - 2) * (depth - 2));
		int floorThreshold = Math.max(4, (interiorArea + 1) / 2);
		Map<Integer, Integer> upperFloorBlocks = new java.util.HashMap<>();
		for (TBlock block : blocks) {
			BlockPos relative = block.rel();
			if (relative.getY() < 2 || relative.getY() >= maxY - 1
				|| relative.getX() <= minX || relative.getX() >= maxX
				|| relative.getZ() <= minZ || relative.getZ() >= maxZ) continue;
			BlockPos pos = bed.offset(relative);
			if (block.state().isFaceSturdy(level, pos, Direction.UP)) {
				upperFloorBlocks.merge(relative.getY(), 1, Integer::sum);
			}
		}
		int upperFloorY = upperFloorBlocks.entrySet().stream().filter(entry -> entry.getValue() >= floorThreshold)
			.mapToInt(Map.Entry::getKey).max().orElse(Integer.MIN_VALUE);
		boolean upperStorey = upperFloorY != Integer.MIN_VALUE;
		long footprint = (long) width * depth;
		Architect.SizeTier tier = upperStorey ? Architect.SizeTier.MULTI_STOREY
			: footprint <= 25 ? Architect.SizeTier.COMPACT : Architect.SizeTier.STANDARD;
		List<TBlock> finished = finishedFacade(level, bed, blocks, frontDoor.subtract(bed),
			level.getBlockState(frontDoor).getBlock(), outward, tier, upperFloorY);
		return new HouseTemplate(finished, blocks.size(), sourceBounds, outward, bed.immutable(), tier);
	}

	/** Clears loose-looking outside stacks in-place so saved template steps keep their positions and ordering. */
	private static List<TBlock> quietExteriorDisplays(List<TBlock> source, BlockPos door, Direction outward) {
		if (source.isEmpty() || door == null || outward == null) return source;
		Direction across = outward.getClockWise();
		List<TBlock> candidates = source.stream().filter(block -> looseExteriorDisplay(block, door)).toList();
		if (candidates.isEmpty()) return source;
		Set<BlockPos> candidatePositions = new HashSet<>();
		for (TBlock candidate : candidates) candidatePositions.add(candidate.rel());
		List<TBlock> core = source.stream().filter(block -> !candidatePositions.contains(block.rel())).toList();
		if (core.isEmpty()) return source;
		int minI = core.stream().mapToInt(block -> horizontalProjection(block.rel(), across)).min().orElse(0);
		int maxI = core.stream().mapToInt(block -> horizontalProjection(block.rel(), across)).max().orElse(0);
		int minJ = core.stream().mapToInt(block -> horizontalProjection(block.rel(), outward)).min().orElse(0);
		int maxJ = core.stream().mapToInt(block -> horizontalProjection(block.rel(), outward)).max().orElse(0);
		Set<BlockPos> clear = new HashSet<>();
		for (TBlock block : candidates) {
			int i = horizontalProjection(block.rel(), across);
			int j = horizontalProjection(block.rel(), outward);
			if (i < minI || i > maxI || j < minJ || j > maxJ) clear.add(block.rel());
		}
		if (clear.isEmpty()) return source;
		List<TBlock> result = new ArrayList<>(source.size());
		for (TBlock block : source) {
			result.add(clear.contains(block.rel())
				? new TBlock(block.rel(), Blocks.AIR.defaultBlockState()) : block);
		}
		return result;
	}

	private static boolean looseExteriorDisplay(TBlock block, BlockPos door) {
		BlockState state = block.state();
		Block material = state.getBlock();
		boolean looseMaterial = material == Blocks.HAY_BLOCK || material == Blocks.BOOKSHELF
			|| material == Blocks.CHISELED_BOOKSHELF
			|| state.is(BlockTags.LOGS) && state.hasProperty(RotatedPillarBlock.AXIS)
				&& state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
		return looseMaterial && block.rel().getY() >= door.getY() - 1
			&& block.rel().getY() <= door.getY() + 1;
	}

	/** Adds a restrained, village-matched front while keeping every addition inside the source X/Z footprint. */
	private static List<TBlock> finishedFacade(ServerLevel level, BlockPos bed, List<TBlock> source,
		BlockPos door, Block doorBlock, Direction outward, Architect.SizeTier tier, int upperFloorY) {
		if (source.isEmpty() || door == null || outward == null || doorBlock == null) return source;
		Map<BlockPos, TBlock> blocks = new LinkedHashMap<>();
		for (TBlock block : source) blocks.put(block.rel(), block);
		Direction across = outward.getClockWise();
		int doorI = horizontalProjection(door, across), doorJ = horizontalProjection(door, outward);
		int minI = blocks.keySet().stream().mapToInt(pos -> horizontalProjection(pos, across)).min().orElse(doorI);
		int maxI = blocks.keySet().stream().mapToInt(pos -> horizontalProjection(pos, across)).max().orElse(doorI);
		int minJ = blocks.keySet().stream().mapToInt(pos -> horizontalProjection(pos, outward)).min().orElse(doorJ);
		int maxJ = blocks.keySet().stream().mapToInt(pos -> horizontalProjection(pos, outward)).max().orElse(doorJ);

		Block frame = matchingFrame(source, doorBlock);
		Set<BlockPos> framed = new HashSet<>();
		if (frame != null) {
			for (int side : new int[]{-1, 1}) {
				for (int y = door.getY(); y <= door.getY() + 1; y++) {
					BlockPos pos = facadePos(across, outward, doorI + side, y, doorJ);
					TBlock existing = blocks.get(pos);
					if (!solidFacadeBlock(level, bed, pos, existing)) continue;
					blocks.put(pos, new TBlock(pos, verticalFrameState(frame)));
					framed.add(pos);
				}
			}
		}

		Block window = source.stream().map(block -> block.state().getBlock()).filter(HouseTemplate::windowBlock)
			.findFirst().orElse(Blocks.GLASS_PANE);
		int windowOffset = tier == Architect.SizeTier.COMPACT ? 1 : 2;
		for (int side : new int[]{-1, 1}) {
			BlockPos pos = frontWallCandidate(level, bed, blocks, framed, across, outward, doorI, doorJ,
				door.getY() + 1, side, windowOffset, minI, maxI);
			if (pos != null) putWindow(blocks, pos, window);
		}
		if (tier == Architect.SizeTier.MULTI_STOREY && upperFloorY != Integer.MIN_VALUE) {
			for (int side : new int[]{-1, 1}) {
				BlockPos pos = frontWallCandidate(level, bed, blocks, framed, across, outward, doorI, doorJ,
					upperFloorY + 1, side, 2, minI, maxI);
				if (pos != null) putWindow(blocks, pos, window);
			}
		}

		Block canopy = matchingCanopy(source, doorBlock, frame);
		if (canopy instanceof SlabBlock && minJ <= doorJ && doorJ <= maxJ) {
			for (int offset = -1; offset <= 1; offset++) {
				int i = doorI + offset;
				if (i < minI || i > maxI) continue;
				BlockPos pos = facadePos(across, outward, i, door.getY() + 2, doorJ);
				TBlock existing = blocks.get(pos);
				if (existing == null || solidFacadeBlock(level, bed, pos, existing)) {
					blocks.put(pos, new TBlock(pos, canopy.defaultBlockState()));
				}
			}
		}
		return List.copyOf(blocks.values());
	}

	private static BlockPos frontWallCandidate(ServerLevel level, BlockPos bed, Map<BlockPos, TBlock> blocks,
		Set<BlockPos> framed, Direction across, Direction outward, int doorI, int doorJ, int y,
		int side, int preferredDistance, int minI, int maxI) {
		int maxDistance = side < 0 ? doorI - minI : maxI - doorI;
		if (maxDistance <= 0) return null;
		for (int distance = 1; distance <= maxDistance; distance++) {
			if (distance != preferredDistance && distance < preferredDistance) continue;
			BlockPos pos = facadePos(across, outward, doorI + side * distance, y, doorJ);
			if (framed.contains(pos)) continue;
			TBlock existing = blocks.get(pos);
			if (existing != null && windowBlock(existing.state().getBlock())) return pos;
			if (solidFacadeBlock(level, bed, pos, existing)) return pos;
		}
		for (int distance = 1; distance < Math.min(preferredDistance, maxDistance + 1); distance++) {
			BlockPos pos = facadePos(across, outward, doorI + side * distance, y, doorJ);
			if (framed.contains(pos)) continue;
			TBlock existing = blocks.get(pos);
			if (existing != null && windowBlock(existing.state().getBlock())) return pos;
			if (solidFacadeBlock(level, bed, pos, existing)) return pos;
		}
		return null;
	}

	private static void putWindow(Map<BlockPos, TBlock> blocks, BlockPos pos, Block window) {
		TBlock existing = blocks.get(pos);
		if (existing != null && windowBlock(existing.state().getBlock())) return;
		blocks.put(pos, new TBlock(pos, window.defaultBlockState()));
	}

	private static boolean solidFacadeBlock(ServerLevel level, BlockPos bed, BlockPos relative, TBlock block) {
		if (block == null) return false;
		BlockState state = block.state();
		Block material = state.getBlock();
		return !(material instanceof DoorBlock) && !(material instanceof BedBlock)
			&& !(material instanceof StairBlock) && !(material instanceof SlabBlock)
			&& !state.hasBlockEntity() && !windowBlock(material) && !state.is(BlockTags.LOGS)
			&& state.isCollisionShapeFullBlock(level, bed.offset(relative));
	}

	private static int horizontalProjection(BlockPos relative, Direction direction) {
		return relative.getX() * direction.getStepX() + relative.getZ() * direction.getStepZ();
	}

	private static BlockPos facadePos(Direction across, Direction outward, int i, int y, int j) {
		return new BlockPos(across.getStepX() * i + outward.getStepX() * j, y,
			across.getStepZ() * i + outward.getStepZ() * j);
	}

	private static boolean windowBlock(Block block) {
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return path.contains("glass") || path.contains("pane") || block instanceof net.minecraft.world.level.block.IronBarsBlock;
	}

	private static Block matchingFrame(List<TBlock> source, Block door) {
		for (TBlock block : source) if (block.state().is(BlockTags.LOGS) && Supply.obtainable(block.state().getBlock())) return block.state().getBlock();
		for (Block candidate : new Block[]{door, source.stream().map(block -> block.state().getBlock())
			.filter(block -> block.defaultBlockState().is(BlockTags.PLANKS)).findFirst().orElse(null)}) {
			String family = woodFamily(candidate);
			Block derived = family == null ? null : registeredBlock(family + "_log");
			if (Supply.obtainable(derived)) return derived;
		}
		return null;
	}

	private static Block matchingCanopy(List<TBlock> source, Block door, Block frame) {
		for (int i = source.size() - 1; i >= 0; i--) {
			Block block = source.get(i).state().getBlock();
			if (block instanceof SlabBlock && Supply.obtainable(block)) return block;
		}
		for (int i = source.size() - 1; i >= 0; i--) {
			Block block = source.get(i).state().getBlock();
			if (block instanceof StairBlock) {
				String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
				if (path.endsWith("_stairs")) {
					Block slab = registeredBlock(path.substring(0, path.length() - "_stairs".length()) + "_slab");
					if (slab instanceof SlabBlock && Supply.obtainable(slab)) return slab;
				}
			}
		}
		for (Block candidate : new Block[]{frame, door, source.stream().map(block -> block.state().getBlock())
			.filter(block -> block.defaultBlockState().is(BlockTags.PLANKS)).findFirst().orElse(null)}) {
			String family = woodFamily(candidate);
			Block slab = family == null ? null : registeredBlock(family + "_slab");
			if (slab instanceof SlabBlock && Supply.obtainable(slab)) return slab;
		}
		return null;
	}

	private static String woodFamily(Block block) {
		if (block == null) return null;
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
		for (String suffix : new String[]{"_planks", "_log", "_wood", "_stem", "_hyphae", "_door", "_trapdoor", "_stairs", "_slab", "_fence"}) {
			if (path.endsWith(suffix)) return path.substring(0, path.length() - suffix.length());
		}
		return null;
	}

	private static Block registeredBlock(String path) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(path)).orElse(null);
	}

	private static BlockState verticalFrameState(Block frame) {
		BlockState state = frame.defaultBlockState();
		return state.hasProperty(RotatedPillarBlock.AXIS) ? state.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : state;
	}

	// --- Ausrichten -----------------------------------------------------------------------------

	private Rotation rotation(Direction target, boolean mirrored) {
		Direction out = mirrored ? Mirror.LEFT_RIGHT.mirror(outward) : outward;
		return ROTATIONS[Math.floorMod(target.get2DDataValue() - out.get2DDataValue(), 4)];
	}

	/** Spiegelt (links/rechts) vor dem Drehen: dasselbe Haus wirkt dann wie ein anderes. */
	private static BlockPos mirrorPos(BlockPos rel, boolean mirrored) {
		return mirrored ? new BlockPos(rel.getX(), rel.getY(), -rel.getZ()) : rel;
	}

	public Bounds bounds(Direction target) {
		return bounds(target, false);
	}

	public Architect.SizeTier sizeTier() {
		return sizeTier;
	}

	int sourceBlockCount() {
		return sourceBlockCount;
	}

	Bounds sourceBounds(Direction target, boolean mirrored) {
		Rotation rot = rotation(target, mirrored);
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (int x : new int[]{sourceBounds.minX(), sourceBounds.maxX()}) {
			for (int z : new int[]{sourceBounds.minZ(), sourceBounds.maxZ()}) {
				BlockPos relative = mirrorPos(new BlockPos(x, 0, z), mirrored).rotate(rot);
				minX = Math.min(minX, relative.getX()); maxX = Math.max(maxX, relative.getX());
				minZ = Math.min(minZ, relative.getZ()); maxZ = Math.max(maxZ, relative.getZ());
			}
		}
		return new Bounds(minX, maxX, minZ, maxZ, sourceBounds.maxY());
	}

	public Bounds bounds(Direction target, boolean mirrored) {
		Rotation rot = rotation(target, mirrored);
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = 0;
		for (TBlock b : blocks) {
			BlockPos r = mirrorPos(b.rel(), mirrored).rotate(rot);
			minX = Math.min(minX, r.getX());
			maxX = Math.max(maxX, r.getX());
			minZ = Math.min(minZ, r.getZ());
			maxZ = Math.max(maxZ, r.getZ());
			maxY = Math.max(maxY, r.getY());
		}
		return new Bounds(minX, maxX, minZ, maxZ, maxY);
	}

	/** Rejects a copied-house site when any block on the template's lowest foundation course would hang over air. */
	public boolean supportedFoundation(ServerLevel level, BlockPos anchor, Direction target, boolean mirrored) {
		Rotation rot = rotation(target, mirrored);
		int lowest = blocks.stream().mapToInt(b -> b.rel().getY()).min().orElse(Integer.MAX_VALUE);
		for (TBlock block : blocks) {
			if (block.rel().getY() != lowest) continue;
			BlockPos relative = mirrorPos(block.rel(), mirrored).rotate(rot);
			BlockPos foundation = anchor.offset(relative);
			BlockPos below = foundation.below();
			if (!level.hasChunkAt(below) || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
		}
		return lowest != Integer.MAX_VALUE;
	}

	/** Anzahl Betten (fuer die Statistik). */
	public int beds() {
		return (int) blocks.stream().filter(b -> b.state().getBlock() instanceof BedBlock && b.state().getValue(BedBlock.PART) == BedPart.FOOT).count();
	}

	/**
	 * Bauschritte fuer dieses Haus. {@code anchor} ist die Stelle, an der das Bett (Ursprung) landet.
	 *
	 * @param costPercent wieviel Prozent der Bloecke aus der Kiste bezahlt werden muessen
	 */
	public List<Step> steps(BlockPos anchor, Direction target, int costPercent) {
		return steps(anchor, target, costPercent, false);
	}

	public List<Step> steps(BlockPos anchor, Direction target, int costPercent, boolean mirrored) {
		return steps(anchor, target, costPercent, mirrored, b -> b);
	}

	/** {@code subst} ersetzt nicht beschaffbare Bloecke (null = Block entfaellt), siehe {@link Supply}. */
	public List<Step> steps(BlockPos anchor, Direction target, int costPercent, boolean mirrored,
		java.util.function.UnaryOperator<Block> subst) {
		return steps(anchor, target, costPercent, mirrored, subst, true);
	}

	/** {@code keepBeds = false}: Betten werden nicht mitgebaut (Lager, Schule - sonst wuerden dort neue Betten/Heimstaetten entstehen). */
	public List<Step> steps(BlockPos anchor, Direction target, int costPercent, boolean mirrored,
		java.util.function.UnaryOperator<Block> subst, boolean keepBeds) {
		Rotation rot = rotation(target, mirrored);
		List<TBlock> sorted = new ArrayList<>(blocks);
		sorted.sort(Comparator.<TBlock>comparingInt(b -> mirrorPos(b.rel(), mirrored).rotate(rot).getY())
			.thenComparingInt(b -> dependent(b.state()) ? 1 : 0)
			.thenComparingInt(b -> mirrorPos(b.rel(), mirrored).rotate(rot).getX())
			.thenComparingInt(b -> mirrorPos(b.rel(), mirrored).rotate(rot).getZ()));

		List<Step> steps = new ArrayList<>();
		int paidIndex = 0;
		for (TBlock tb : sorted) {
			BlockPos pos = anchor.offset(mirrorPos(tb.rel(), mirrored).rotate(rot));
			BlockState st = (mirrored ? tb.state().mirror(Mirror.LEFT_RIGHT) : tb.state()).rotate(rot);
			Block block = st.getBlock();
			boolean pays = costPercent >= 100 || (paidIndex * costPercent / 100) != ((paidIndex + 1) * costPercent / 100);
			if (block == Blocks.AIR) {
				steps.add(Step.free(pos, Blocks.AIR.defaultBlockState()));
				paidIndex++;
				continue;
			}

			if (block instanceof BedBlock) {
				if (!keepBeds) {
					continue;
				}
				Direction f = st.getValue(BedBlock.FACING);
				if (st.getValue(BedBlock.PART) == BedPart.FOOT) {
					steps.add(new Step(pos, block.asItem(), pays ? ItemTags.BEDS : null, pays ? 1 : 0, false, null,
						b -> b.defaultBlockState().setValue(BedBlock.FACING, f).setValue(BedBlock.PART, BedPart.FOOT)));
					paidIndex++;
				} else {
					steps.add(Step.linked(pos, pos.relative(f.getOpposite()),
						b -> b.defaultBlockState().setValue(BedBlock.FACING, f).setValue(BedBlock.PART, BedPart.HEAD)));
				}
			} else if (block instanceof DoorBlock) {
				Block resolved = subst.apply(block);
				if (!(resolved instanceof DoorBlock)) {
					continue;
				}
				BlockState resolvedState = Supply.transfer(st, resolved);
				if (st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
					steps.add(new Step(pos, resolved.asItem(), null, pays ? 1 : 0, false, null,
						b -> copyDoor(resolved, resolvedState)));
					paidIndex++;
				} else {
					steps.add(Step.linked(pos, pos.below(), b -> copyDoor(resolved, resolvedState)));
				}
			} else {
				Block sub = subst.apply(block);
				if (sub == null) {
					continue;
				}
				BlockState out = sub == block ? st : Supply.transfer(st, sub);
				if (pays) {
					steps.add(new Step(pos, sub.asItem(), null, 1, false, null, b -> out));
				} else {
					steps.add(Step.free(pos, out));
				}
				paidIndex++;
			}
		}
		return steps;
	}

	private static boolean dependent(BlockState s) {
		return (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD)
			|| (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER);
	}

	private static BlockState copyDoor(Block block, BlockState like) {
		BlockState s = block.defaultBlockState();
		if (!(block instanceof DoorBlock)) {
			return like;
		}
		return s.setValue(DoorBlock.FACING, like.getValue(DoorBlock.FACING))
			.setValue(DoorBlock.HINGE, like.getValue(DoorBlock.HINGE))
			.setValue(DoorBlock.HALF, like.getValue(DoorBlock.HALF));
	}
}
