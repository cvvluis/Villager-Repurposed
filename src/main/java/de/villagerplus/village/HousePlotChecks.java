package de.villagerplus.village;

import de.villagerplus.build.HouseTemplate;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

/** Isolated in-world checks for house spacing and saved-street access. Restores the fixture. */
public final class HousePlotChecks {
	private HousePlotChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		try {
			for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = -1; y <= 7; y++) {
				BlockPos p = origin.offset(x, y, z).immutable();
				level.getChunkAt(p);
				BlockEntity entity = level.getBlockEntity(p);
				before.put(p, new Cell(level.getBlockState(p), entity == null ? null
					: entity.saveWithFullMetadata(level.registryAccess())));
				level.setBlock(p, (y == -1 ? Blocks.GRASS_BLOCK : Blocks.AIR).defaultBlockState(), 3);
			}
			BlockPos bellPos = origin.east(5).above();
			level.setBlock(bellPos, Blocks.BELL.defaultBlockState(), 3);
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) {
				throw new IllegalStateException("House plot: could not create saved-road fixture bell");
			}
			VillageData villageData = bell.getAttachedOrCreate(VillagerPlus.VILLAGE);
			VillageRef ref = new VillageRef(bellPos, villageData, bell);
			BlockPos plaza = origin.east(4).above();
			Market.save(ref, new MarketData(Optional.of(plaza), List.of(), 0L, 0L));
			bell.setAttached(VillagerPlus.ROADS, Map.of("center", 2L));
			level.setBlock(origin.east(4).below(), Blocks.DIRT_PATH.defaultBlockState(), 3);
			level.setBlock(origin.east(5).below(), Blocks.DIRT_PATH.defaultBlockState(), 3);
			Villages.noteChunk(level.getChunkAt(bellPos));

			int yardChecks = de.villagerplus.build.HouseYardChecks.run(level, origin);
			int checks = 7;
			require(Sites.HOUSE_MARGIN == 12, "new houses keep the documented twelve-block separation belt from generated village homes");
			checks++;
			BoundingBox terracottaPieceBounds = new BoundingBox(
				origin.getX() + 8, origin.getY() - 1, origin.getZ() - 1,
				origin.getX() + 10, origin.getY() + 5, origin.getZ() + 1);
			HouseTemplate.Footprint terracottaPiece = HouseTemplate.boundedFootprint(terracottaPieceBounds);
			require(VillageStyle.natural(Blocks.TERRACOTTA.defaultBlockState())
				&& terracottaPiece != null && Sites.houseBuildingPiece(terracottaPieceBounds)
				&& Sites.structurePieceBlocksHousePlot(terracottaPieceBounds,
					origin.getX() - 1, origin.getX() + 1, origin.getZ() - 1, origin.getZ() + 1,
					origin.getY() - 3, origin.getY() + 6),
				"bounded natural-material building-piece footprint keeps new houses outside its twelve-block border");
			checks++;
			require(!Sites.structurePieceBlocksHousePlot(terracottaPieceBounds,
				origin.getX() - 8, origin.getX() - 5, origin.getZ() - 1, origin.getZ() + 1,
				origin.getY() - 3, origin.getY() + 6),
				"bounded structure spacing accepts a candidate with twelve clear blocks to its footprint");
			checks++;
			require(Sites.structurePieceBlocksHousePlot(terracottaPieceBounds,
				origin.getX() - 7, origin.getX() - 4, origin.getZ() - 1, origin.getZ() + 1,
				origin.getY() - 3, origin.getY() + 6),
				"bounded structure spacing rejects a candidate with only eleven clear blocks");
			checks++;
			require(!Sites.houseBuildingPiece(new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + 8, origin.getY() + 1, origin.getZ() + 1)),
				"road-like village pieces do not count as buildings");
			checks++;
			require(!Sites.houseBuildingPiece(new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + 8, origin.getY() + 1, origin.getZ() + 8)),
				"low field-like village pieces do not count as buildings");
			checks++;

			require(plot(level, origin, -1, 1), "empty parcel is accepted");
			BlockPos coal = origin.below(2);
			BlockPos copper = origin.west(6).below();
			BlockState oldCoal = level.getBlockState(coal);
			BlockState oldCopper = level.getBlockState(copper);
			try {
				level.setBlock(coal, Blocks.COAL_ORE.defaultBlockState(), 3);
				level.setBlock(copper, Blocks.COPPER_ORE.defaultBlockState(), 3);
				require(plot(level, origin, -1, 1), "natural ores below the footprint or in the margin are not buildings");
				checks++;
				level.setBlock(copper, Blocks.COBBLED_DEEPSLATE.defaultBlockState(), 3);
				require(!plot(level, origin, -1, 1), "an unmarked cobbled-deepslate block still protects the building margin");
				checks++;
			} finally {
				level.setBlock(coal, oldCoal, 3);
				level.setBlock(copper, oldCopper, 3);
			}
			BlockPos wall = origin.east(12);
			level.setBlock(wall, Blocks.STONE_BRICKS.defaultBlockState(), 3);
			require(!plot(level, origin, -1, 1), "building within twelve-block border is rejected");
			BlockPos westCandidate = origin.west(12);
			BlockPos westClearance = westCandidate.offset(-1 - Sites.HOUSE_MARGIN, 0, 0);
			if (!level.hasChunkAt(westClearance)) {
				require(!plot(level, westCandidate, -1, 1), "house spacing defers a candidate whose clearance reaches an unloaded chunk");
				require(!level.hasChunkAt(westClearance), "house spacing does not force-load an unloaded clearance chunk");
				checks += 2;
			}
			loadClearanceChunks(level, origin);
			require(plot(level, westCandidate, -1, 1), "a candidate beyond the twelve-block border remains usable");
			checks++;
			level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
			loadStructureEnvelope(level, origin, 5);
			var structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			Structure vanillaVillage = null;
			Structure ctovVillage = null;
			Identifier vanillaVillageId = Identifier.withDefaultNamespace("village_plains");
			for (Structure structure : structureRegistry) {
				Identifier id = structureRegistry.getKey(structure);
				if (vanillaVillageId.equals(id)) vanillaVillage = structure;
				if (id != null && "ctov".equals(id.getNamespace()) && id.getPath().contains("village")) ctovVillage = structure;
			}
			require(vanillaVillage != null, "the vanilla plains-village structure is registered for bounds checks");
			checks++;
			checks += checkRegisteredVillageBounds(level, origin, vanillaVillage);
			if (ctovVillage != null) checks += checkRegisteredVillageBounds(level, origin, ctovVillage);
			else System.out.println("HOUSE PLOT CHECK SKIPPED CTOV bounds assertion: no registered CTOV village structure");
			require(plot(level, origin, -1, 1), "a normal natural surface remains eligible after the temporary structure bounds are restored");
			checks++;
			level.setBlock(origin.east(14), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			require(plot(level, origin, -1, 1), "building with twelve clear blocks outside the border is accepted");
			require(!plot(level, origin, -1, 3), "larger asymmetric footprint gets its own twelve-block border");
			level.setBlock(origin.east(14), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(origin.east(3), Blocks.OAK_FENCE.defaultBlockState(), 3);
			require(!plot(level, origin, -1, 1), "fence blocks the approach even though it is not a house wall");
			level.setBlock(origin.east(3), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(origin.east(3).below(), Blocks.WATER.defaultBlockState(), 3);
			require(!plot(level, origin, -1, 1), "water in the approach is rejected");
			level.setBlock(origin.east(3).below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);

			require(plotRect(level, origin, -2, 1, -1, 3, Direction.EAST),
				"non-square east-facing footprint reaches the saved street from its transformed front center");
			checks++;
			level.setBlock(origin.east(2).south(1), Blocks.OAK_FENCE.defaultBlockState(), 3);
			require(!plotRect(level, origin, -2, 1, -1, 3, Direction.EAST),
				"rotated non-square footprint keeps its own three-block entrance line clear");
			checks++;
			level.setBlock(origin.east(2).south(1), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(origin.east(8).south(3), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			require(!plotRect(level, origin, -2, 1, -1, 3, Direction.EAST),
				"twelve-block house spacing follows the full non-square world footprint");
			checks++;
			level.setBlock(origin.east(8).south(3), Blocks.AIR.defaultBlockState(), 3);

			require(plotRect(level, origin, -1, 3, -2, 1, Direction.NORTH),
				"quarter-turned non-square footprint preserves a walkable entrance to the saved road");
			checks++;
			level.setBlock(origin.east(1).north(4), Blocks.OAK_FENCE.defaultBlockState(), 3);
			require(!plotRect(level, origin, -1, 3, -2, 1, Direction.NORTH),
				"north-facing entrance checks the transformed center column");
			checks++;
			level.setBlock(origin.east(1).north(4), Blocks.AIR.defaultBlockState(), 3);

			level.setBlock(origin.east(2), Blocks.DIRT.defaultBlockState(), 3);
			require(plot(level, origin, -1, 1), "one-block entrance step remains walkable to the saved street");
			checks++;
			level.setBlock(origin.east(2), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(origin.east(2), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(origin.east(2).above(), Blocks.DIRT.defaultBlockState(), 3);
			require(!plot(level, origin, -1, 1), "two-block rise is rejected instead of claiming a walkable entrance");
			checks++;
			level.setBlock(origin.east(2), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(origin.east(2).above(), Blocks.AIR.defaultBlockState(), 3);

			bell.setAttached(VillagerPlus.ROADS, Map.of("center", 1L));
			level.setBlock(origin.east(4).below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(origin.east(2).below(), Blocks.DIRT_PATH.defaultBlockState(), 3);
			require(!plot(level, origin, -1, 1), "a nearby stray path cannot substitute for a missing saved street surface");
			checks++;
			level.setBlock(origin.east(2).below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(origin.east(4).below(), Blocks.DIRT_PATH.defaultBlockState(), 3);

			BlockPos isolatedBed = origin.west(8).above();
			Map<BlockPos, BlockState> bedBefore = new LinkedHashMap<>();
			var poiManager = level.getPoiManager();
			boolean injectedHomePoi = false;
			try {
				// Keep the bed supported so the fixture creates a real HOME POI; the prior
				// floating bed could be removed by vanilla neighbor updates before the query.
				setRemembering(level, bedBefore, isolatedBed.below(), Blocks.SPRUCE_PLANKS.defaultBlockState());
				setRemembering(level, bedBefore, isolatedBed.east().below(), Blocks.SPRUCE_PLANKS.defaultBlockState());
				BlockState foot = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
					.setValue(BedBlock.PART, BedPart.FOOT);
				setRemembering(level, bedBefore, isolatedBed, foot);
				setRemembering(level, bedBefore, isolatedBed.east(), foot.setValue(BedBlock.PART, BedPart.HEAD));
				if (poiManager.getType(isolatedBed).isEmpty()) {
					poiManager.add(isolatedBed, net.minecraft.core.registries.BuiltInRegistries.POINT_OF_INTEREST_TYPE
						.getOrThrow(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME));
					injectedHomePoi = true;
				}
				require(poiManager.getType(isolatedBed).filter(type -> type.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME)).isPresent()
					&& HouseTemplate.homeSpacingFootprint(level, isolatedBed) == null && !plot(level, origin, -1, 1),
					"an unrecognized ordinary HOME bed retains the conservative bed-center fallback");
				checks++;
			} finally {
				bedBefore.forEach((pos, state) -> level.setBlock(pos, state, 3));
				if (injectedHomePoi) poiManager.remove(isolatedBed);
			}

			BlockPos homeBase = origin.west(8).south(3);
			BlockPos homeBed = homeBase.offset(-1, 1, 0);
			Map<BlockPos, BlockState> ordinaryHouseBefore = new LinkedHashMap<>();
			try {
				for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
					setRemembering(level, ordinaryHouseBefore, homeBase.offset(x, 0, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
					for (int dy = 1; dy <= 2; dy++) {
						if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;
						BlockPos wallPos = homeBase.offset(x, dy, z);
						if (wallPos.equals(homeBase.offset(0, 1, -2)) || wallPos.equals(homeBase.offset(0, 2, -2))) continue;
						setRemembering(level, ordinaryHouseBefore, wallPos, Blocks.SPRUCE_PLANKS.defaultBlockState());
					}
					setRemembering(level, ordinaryHouseBefore, homeBase.offset(x, 3, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
				}
				BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
					.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
				setRemembering(level, ordinaryHouseBefore, homeBase.offset(0, 1, -2), door);
				setRemembering(level, ordinaryHouseBefore, homeBase.offset(0, 2, -2), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
				BlockState bedFoot = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
					.setValue(BedBlock.PART, BedPart.FOOT);
				setRemembering(level, ordinaryHouseBefore, homeBed, bedFoot);
				setRemembering(level, ordinaryHouseBefore, homeBed.east(), bedFoot.setValue(BedBlock.PART, BedPart.HEAD));
				HouseTemplate.Footprint ordinary = HouseTemplate.homeSpacingFootprint(level, homeBed);
				require(ordinary != null && ordinary.minX() == homeBase.getX() - 2 && ordinary.maxX() == homeBase.getX() + 2
					&& ordinary.minZ() == homeBase.getZ() - 2 && ordinary.maxZ() == homeBase.getZ() + 2,
					"loaded ordinary bed-and-door house uses a small bounded connected-block fallback footprint");
				checks++;
				require(ordinary.tooCloseTo(origin.getX() - 1, origin.getX() + 1, origin.getZ() - 1, origin.getZ() + 1,
					Sites.HOUSE_MARGIN), "an ordinary generated-style home keeps the full separation belt from a new house");
				checks++;
			} finally {
				ordinaryHouseBefore.forEach((pos, state) -> level.setBlock(pos, state, 3));
			}

			BlockPos unloaded = unloadedCandidate(level, origin);
			require(unloaded != null, "fixture has an unloaded chunk for the no-force-load check");
			checks++;
			require(!plot(level, unloaded, -1, 1), "unloaded parcel is rejected without reading it");
			checks++;
			require(HouseTemplate.homeSpacingFootprint(level, unloaded) == null && !level.hasChunkAt(unloaded),
				"home-footprint fallback leaves an unloaded POI position untouched");
			checks++;

			checks += checkWalkBack(level, origin);

			int roadChecks = de.villagerplus.build.HouseRoadAccessChecks.run(level, origin.offset(30, 0, 0));
			int expansionChecks = ExpansionParcelChecks.run(level, ref, origin.offset(160, 0, 0));
			return checks + yardChecks + roadChecks + expansionChecks;
		} finally {
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, cell) -> level.setBlock(p, cell.state(), 3));
			before.forEach((p, cell) -> {
				if (cell.nbt() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(p, cell.state(), cell.nbt().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
		}
	}

	/**
	 * A quarry stand below a ledge passes the outbound path check (vanilla drops down) but strands the worker:
	 * the way back must climb at most one block per step. Uses the south strip of the cleared fixture (z+8..z+12,
	 * x+4..x+14 as a three-block plateau); everything is restored by {@link #run}.
	 */
	private static int checkWalkBack(ServerLevel level, BlockPos origin) {
		int checks = 0;
		BlockState dirt = Blocks.DIRT.defaultBlockState();
		for (int x = 4; x <= 14; x++) for (int z = 8; z <= 12; z++) for (int y = 0; y <= 2; y++) {
			level.setBlock(origin.offset(x, y, z), dirt, 3);
		}
		BlockPos lowland = origin.offset(-10, 0, 10);
		BlockPos plateau = origin.offset(10, 3, 10);
		require(Sites.walksBackTo(level, lowland, lowland.east(1)),
			"a stand finds a goal beside itself on flat ground");
		checks++;
		require(!Sites.walksBackTo(level, lowland, plateau),
			"a three-block ledge cannot be climbed back: lowland stand to plateau street is rejected");
		checks++;
		require(Sites.walksBackTo(level, plateau, lowland),
			"dropping three blocks off a ledge is allowed on the way back");
		checks++;
		level.setBlock(origin.offset(2, 0, 10), dirt, 3);
		level.setBlock(origin.offset(3, 0, 10), dirt, 3);
		level.setBlock(origin.offset(3, 1, 10), dirt, 3);
		require(Sites.walksBackTo(level, lowland, plateau),
			"a one-block-per-step ramp makes the plateau reachable from the lowland");
		checks++;
		level.setBlock(origin.offset(2, 0, 10), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(origin.offset(3, 0, 10), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(origin.offset(3, 1, 10), Blocks.AIR.defaultBlockState(), 3);
		require(!Sites.walksBackTo(level, lowland, plateau),
			"removing the ramp makes the plateau unreachable from the lowland again");
		checks++;

		// A walled yard (three-block walls) is entered through a closed wooden door, as in a built village.
		// The goal radius is four blocks, so the yard is nine wide and the walker starts well outside it.
		for (int x = -12; x <= -4; x++) for (int z = 6; z <= 14; z++) {
			if (x != -12 && x != -4 && z != 6 && z != 14) continue;
			for (int y = 0; y <= 2; y++) level.setBlock(origin.offset(x, y, z), dirt, 3);
		}
		BlockPos yard = origin.offset(-8, 0, 10);
		BlockPos outside = origin.offset(-15, 0, 10);
		require(!Sites.walksBackTo(level, outside, yard),
			"a walled yard without an opening cannot be walked into");
		checks++;
		BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
		level.setBlock(origin.offset(-12, 0, 10), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
		level.setBlock(origin.offset(-12, 1, 10), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
		require(Sites.walksBackTo(level, outside, yard),
			"a closed wooden door lets the walk-back search pass into the walled yard");
		checks++;
		return checks;
	}

	private static boolean plot(ServerLevel level, BlockPos pos, int minX, int maxX) {
		return plotRect(level, pos, minX, maxX, -1, 1, Direction.EAST);
	}

	private static boolean plotRect(ServerLevel level, BlockPos pos, int minX, int maxX, int minZ, int maxZ, Direction outward) {
		return Sites.housePlot(level, pos, minX, maxX, minZ, maxZ, 3, outward);
	}

	private static void setRemembering(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos pos, BlockState state) {
		before.putIfAbsent(pos.immutable(), level.getBlockState(pos));
		level.setBlock(pos, state, 3);
	}

	private static BlockPos unloadedCandidate(ServerLevel level, BlockPos origin) {
		for (int offset = 512; offset <= 16384; offset *= 2) {
			BlockPos candidate = origin.offset(offset, 0, offset).immutable();
			if (!level.hasChunkAt(candidate)) return candidate;
		}
		return null;
	}

	/** Load every chunk column touched by the test parcels' house-clearance envelopes. */
	private static void loadClearanceChunks(ServerLevel level, BlockPos origin) {
		int minX = origin.getX() - 12 - 1 - Sites.HOUSE_MARGIN;
		int maxX = origin.getX() + 3 + Sites.HOUSE_MARGIN;
		int minZ = origin.getZ() - 2 - Sites.HOUSE_MARGIN;
		int maxZ = origin.getZ() + 3 + Sites.HOUSE_MARGIN;
		for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
				BlockPos sample = new BlockPos((chunkX << 4) + 8, origin.getY(), (chunkZ << 4) + 8);
				if (!level.hasChunkAt(sample)) level.getChunkAt(sample);
			}
		}
	}

	private static void loadStructureEnvelope(ServerLevel level, BlockPos center, int half) {
		int minX = center.getX() - half - Sites.HOUSE_MARGIN;
		int maxX = center.getX() + half + Sites.HOUSE_MARGIN;
		int minZ = center.getZ() - half - Sites.HOUSE_MARGIN;
		int maxZ = center.getZ() + half + Sites.HOUSE_MARGIN;
		for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
				BlockPos sample = new BlockPos((chunkX << 4) + 8, center.getY(), (chunkZ << 4) + 8);
				level.getChunkAt(sample);
			}
		}
	}

	private static int checkRegisteredVillageBounds(ServerLevel level, BlockPos origin, Structure structure) {
		ChunkPos sourcePos = ChunkPos.containing(origin);
		ChunkAccess source = level.getChunkAt(origin);
		Map<Structure, StructureStart> startsBefore = new HashMap<>(source.getAllStarts());
		Map<Structure, LongSet> referencesBefore = new HashMap<>();
		source.getAllReferences().forEach((key, values) -> {
			LongSet copy = new LongOpenHashSet();
			for (long value : values) copy.add(value);
			referencesBefore.put(key, copy);
		});
		BoundingBox bounds = new BoundingBox(origin.getX() + 10, origin.getY() - 1, origin.getZ() - 1,
			origin.getX() + 12, origin.getY() + 5, origin.getZ() + 1);
		try {
			StructureStart start = new StructureStart(structure, sourcePos, 0,
				new PiecesContainer(List.of(new TestHousePiece(bounds))));
			source.setStartForStructure(structure, start);
			source.addReferenceForStructure(structure, sourcePos.pack());
			var id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
			// StructureStart may inflate its aggregate envelope for terrain adaptation; the individual piece bounds stay exact.
			BoundingBox startBounds = start.getBoundingBox();
			require(start.isValid() && source.getStartForStructure(structure) == start
				&& source.getReferencesForStructure(structure).contains(sourcePos.pack())
				&& start.getPieces().size() == 1
				&& start.getPieces().get(0).getBoundingBox().equals(bounds)
				&& containsBounds(startBounds, bounds),
				"loaded " + id + " start metadata retains its exact house-piece bounds inside the structure envelope");
			require(!plot(level, origin, -1, 1),
				"the loaded " + id + " piece bounds reject the adjacent new-house parcel without a nearby test wall");
			NaturalSitePreparation.Scan preparation = NaturalSitePreparation.inspect(level,
				new NaturalSitePreparation.SitePlan(origin, 5, 8, 1, 128, false));
			require(preparation.status() == NaturalSitePreparation.Status.BLOCKED
					|| preparation.status() == NaturalSitePreparation.Status.WAITING_FOR_CHUNK,
				"residential preparation blocks or safely defers the same loaded " + id + " house piece before clearing natural ground"
					+ " [status=" + preparation.status() + ", next=" + preparation.next()
					+ ", inspected=" + preparation.obstacleCount() + "]");
			return 3;
		} finally {
			source.setAllStarts(startsBefore);
			source.setAllReferences(referencesBefore);
		}
	}

	private static boolean containsBounds(BoundingBox outer, BoundingBox inner) {
		return outer.minX() <= inner.minX() && outer.minY() <= inner.minY() && outer.minZ() <= inner.minZ()
			&& outer.maxX() >= inner.maxX() && outer.maxY() >= inner.maxY() && outer.maxZ() >= inner.maxZ();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("House plot: " + message);
	}

	private static final class TestHousePiece extends StructurePiece {
		private TestHousePiece(BoundingBox bounds) {
			super(StructurePieceType.JIGSAW, 0, bounds);
		}

		@Override
		protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) { }

		@Override
		public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator chunkGenerator,
			RandomSource random, BoundingBox bounds, ChunkPos chunkPos, BlockPos pivot) { }
	}

	private record Cell(BlockState state, CompoundTag nbt) { }
}
