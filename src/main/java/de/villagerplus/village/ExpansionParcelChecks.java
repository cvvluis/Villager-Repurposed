package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Focused in-world checks for successive saved housing parcels and conservative woodland protection. */
public final class ExpansionParcelChecks {
	private ExpansionParcelChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos center) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		Map<String, Long> oldRoads = Map.copyOf(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
		Villager occupant = null;
		try {
			preparePad(level, center, before);
			int checks = 0;
			require(Roads.expansionParcelSuitable(level, ref, center), "flat natural 17-by-17 parcel is eligible"); checks++;
			require(Roads.expansionSurfaceWithinRelief(center.getY(), center.getY() + 1)
				&& Roads.expansionSurfaceWithinRelief(center.getY(), center.getY() + 2)
				&& !Roads.expansionSurfaceWithinRelief(center.getY(), center.getY() + 3)
				&& !Roads.expansionSurfaceWithinRelief(center.getY(), center.getY() - 1),
				"the live grading gate accepts both paid cuts in a two-block rise, but rejects steeper or below-grade ground"); checks++;
			BlockPos progressionBell = new BlockPos(100, center.getY(), 100);
			BlockPos first = Development.expansionParcelCenter(progressionBell, 2, 0, 0L, 0, 8, 88);
			BlockPos second = Development.expansionParcelCenter(progressionBell, 3, 20, 1L, 1, 8, 88);
			BlockPos third = Development.expansionParcelCenter(progressionBell, 4, 40, 2L, 2, 8, 88);
			BlockPos fourth = Development.expansionParcelCenter(progressionBell, 5, 60, 3L, 3, 8, 88);
			BlockPos fifth = Development.expansionParcelCenter(progressionBell, 6, 76, 4L, 4, 8, 88);
			require(first != null && second != null && third != null && fourth != null && fifth != null
				&& first.equals(progressionBell.north(20))
				&& second.equals(progressionBell.east(40))
				&& third.equals(progressionBell.south(60))
				&& fourth.equals(progressionBell.west(76))
				&& fifth.equals(progressionBell.north(76))
				&& Development.expansionParcelCenter(progressionBell, 16, 76,
					Development.EXPANSION_PARCEL_BASE_COUNT + Development.EXPANSION_PARCEL_INNER_RING_DIRECTIONS,
					0, 8, 88) == null,
				"successive housing deficits fill the first four outward plots and then the complete outer and inner rings"); checks++;
			java.util.List<BlockPos> outerRing = new java.util.ArrayList<>();
			for (int direction = 0; direction < Development.EXPANSION_PARCEL_OUTER_RING_DIRECTIONS; direction++) {
				BlockPos plot = Development.expansionParcelCenter(progressionBell, 6, 76, 4L + direction,
					4 + direction, 8, 88);
				require(plot != null && outerRing.stream().noneMatch(existing -> existing.equals(plot)),
					"outer-ring direction " + direction + " has a unique bounded plot"); checks++;
				outerRing.add(plot);
			}
			boolean everyOuterPadClearsItsNeighbors = true;
			for (int i = 0; i < outerRing.size(); i++) for (int j = i + 1; j < outerRing.size(); j++) {
				everyOuterPadClearsItsNeighbors &= Development.expansionParcelClearOfHome(outerRing.get(i), 8,
					outerRing.get(j), 8, Development.EXPANSION_PARCEL_CLEARANCE);
			}
			require(everyOuterPadClearsItsNeighbors,
				"every pair of outer-ring housing pads retains the configured clearance even at diagonal bearings"); checks++;
			java.util.List<BlockPos> innerRing = new java.util.ArrayList<>();
			for (int direction = 0; direction < Development.EXPANSION_PARCEL_INNER_RING_DIRECTIONS; direction++) {
				BlockPos plot = Development.expansionParcelCenter(progressionBell, 16, 76,
					Development.EXPANSION_PARCEL_BASE_COUNT + direction, direction, 8, 88);
				require(plot != null && innerRing.stream().noneMatch(existing -> existing.equals(plot)),
					"inner-ring direction " + direction + " has a unique additional housing plot"); checks++;
				innerRing.add(plot);
			}
			boolean innerRingClear = true;
			for (BlockPos inner : innerRing) for (BlockPos outer : outerRing) {
				innerRingClear &= Development.expansionParcelClearOfHome(inner, 8, outer, 8,
					Development.EXPANSION_PARCEL_CLEARANCE);
			}
			for (int i = 0; i < innerRing.size(); i++) for (int j = i + 1; j < innerRing.size(); j++) {
				innerRingClear &= Development.expansionParcelClearOfHome(innerRing.get(i), 8, innerRing.get(j), 8,
					Development.EXPANSION_PARCEL_CLEARANCE);
			}
			require(innerRingClear,
				"the new inner housing ring clears every outer plot and preserves spacing between its own pads"); checks++;
			require(!Development.expansionParcelClearOfHome(progressionBell, 8, progressionBell.east(20), 8,
				Development.EXPANSION_PARCEL_CLEARANCE)
				&& Development.expansionParcelClearOfHome(progressionBell, 8, progressionBell.east(28), 8,
					Development.EXPANSION_PARCEL_CLEARANCE),
				"prepared plots reject overlapping homes and accept the exact minimum edge clearance"); checks++;
		BlockPos unloaded = unloadedCandidate(level, center);
		if (unloaded != null) {
			require(!Roads.expansionParcelSuitable(level, ref, new BlockPos(unloaded.getX(), center.getY(), unloaded.getZ()))
				&& !level.hasChunkAt(unloaded), "an unloaded future plot is deferred without loading its chunk"); checks++;
		}

			Map<String, Long> saved = new HashMap<>(oldRoads);
			saved.put(Roads.EXPANSION + "geometry", Roads.EXPANSION_GEOMETRY_VERSION);
			saved.put(Roads.EXPANSION + "x", (long) center.getX());
			saved.put(Roads.EXPANSION + "z", (long) center.getZ());
			saved.put(Roads.EXPANSION + "surface", (long) center.getY());
			saved.put(Roads.EXPANSION + "phase", 1L);
			saved.put(Roads.EXPANSION + "ready", 0L);
			saved.put(Roads.EXPANSION + "cursor", 73L);
			saved.put(Roads.EXPANSION + "sequence", 2L);
			saved.put(Roads.EXPANSION + "side", 3L);
			saved.put(Roads.EXPANSION + "prepared_beds", 2L);
			saved.put(Roads.EXPANSION + "prepared_houses", 2L);
			saved.put(Roads.EXPANSION + "column:root", center.offset(3, 0, -4).asLong());
			saved.put(Roads.EXPANSION + "column:top", (long) center.getY() + 7L);
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(saved));
			CompoundTag bellNbt = ref.bellEntity().saveWithFullMetadata(level.registryAccess());
			BlockEntity reloadedEntity = BlockEntity.loadStatic(ref.bell(), level.getBlockState(ref.bell()),
				bellNbt.copy(), level.registryAccess());
			require(reloadedEntity instanceof BellBlockEntity, "saved village bell can be reconstructed for restart verification"); checks++;
			BellBlockEntity reloadedBell = (BellBlockEntity) reloadedEntity;
			Map<String, Long> reloadedRoads = reloadedBell.getAttached(VillagerPlus.ROADS);
			require(reloadedRoads != null
				&& reloadedRoads.getOrDefault(Roads.EXPANSION + "cursor", -1L) == 73L
				&& Roads.expansionSequence(reloadedRoads) == 2L
				&& reloadedRoads.getOrDefault(Roads.EXPANSION + "column:root", Long.MIN_VALUE)
					== center.offset(3, 0, -4).asLong(),
				"saved cursor, ordinal, and partial-tree evidence survive a bell attachment save/load round trip"); checks++;
			VillageRef reloadedRef = new VillageRef(ref.bell(), reloadedBell.getAttachedOrCreate(VillagerPlus.VILLAGE), reloadedBell);
			require(Roads.expansionPreparationActive(reloadedRef)
				&& Roads.preparedExpansionParcel(reloadedRef).isEmpty(),
				"an incomplete parcel resumes after reload without being exposed as a ready house site"); checks++;
			require(Roads.expansionPreparationActive(reloadedRoads, false)
				&& !Roads.expansionPreparationActive(reloadedRoads, true),
				"a paused parcel keeps its saved cursor but does not trigger the accelerated road-builder rescan during active construction"); checks++;
			reloadedRoads = new HashMap<>(reloadedRoads);
			reloadedRoads.put(Roads.EXPANSION + "ready", 1L);
			reloadedRoads.put(Roads.EXPANSION + "prepared_beds", 2L);
			reloadedBell.setAttached(VillagerPlus.ROADS, Map.copyOf(reloadedRoads));
			require(Roads.preparedExpansionParcel(reloadedRef).map(parcel -> parcel.center().equals(center)).orElse(false),
				"a fully surveyed parcel remains available to house planning after reload"); checks++;
			require(!Roads.expansionHousingProgressed(2L, 2L, 3L, 2, false)
				&& !Roads.expansionHousingProgressed(2L, 2L, 2L, 3, false)
				&& Roads.expansionHousingProgressed(2L, 2L, 3L, 2, true)
				&& Roads.expansionHousingProgressed(2L, 2L, 2L, 3, true)
				&& !Roads.expansionHousingProgressed(2L, 2L, 2L, 2, true),
				"only a home placed on the saved parcel together with bed or house growth advances the next outward plot"); checks++;
			Roads.consumePreparedExpansionParcel(reloadedRoads);
			require(Roads.expansionSequence(reloadedRoads) == 3L
				&& reloadedRoads.getOrDefault(Roads.EXPANSION + "side", -1L) == 3L
				&& !reloadedRoads.containsKey(Roads.EXPANSION + "x"),
				"only a consumed ready parcel advances the persistent outer-ring ordinal and keeps side rotation"); checks++;

			preparePad(level, center, before);
			BlockPos ground = center.below();
			setRemembering(level, before, ground, Blocks.FARMLAND.defaultBlockState());
			require(!Roads.expansionParcelSuitable(level, ref, center) && level.getBlockState(ground).is(Blocks.FARMLAND),
				"active farm soil is rejected and left untouched"); checks++;

			preparePad(level, center, before);
			setRemembering(level, before, ground, Blocks.DIRT_PATH.defaultBlockState());
			require(!Roads.expansionParcelSuitable(level, ref, center) && level.getBlockState(ground).is(Blocks.DIRT_PATH),
				"an existing road cell is rejected and left untouched"); checks++;

			preparePad(level, center, before);
			BlockPos chest = center.above(2);
			setRemembering(level, before, chest, Blocks.CHEST.defaultBlockState());
			require(!Roads.expansionParcelSuitable(level, ref, center) && level.getBlockState(chest).is(Blocks.CHEST),
				"a chest column is rejected without changing its block entity"); checks++;

			preparePad(level, center, before);
			setRemembering(level, before, ground, Blocks.WATER.defaultBlockState());
			require(!Roads.expansionParcelSuitable(level, ref, center) && !level.getBlockState(ground).getFluidState().isEmpty(),
				"fluid-bearing terrain is rejected and left untouched"); checks++;

			preparePad(level, center, before);
			BlockState permanentLeaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
			BlockPos leaves = center.above(4);
			setRemembering(level, before, leaves, permanentLeaves);
			require(!Roads.expansionParcelSuitable(level, ref, center)
				&& level.getBlockState(leaves).getValue(LeavesBlock.PERSISTENT),
				"permanent leaves block clearing and remain in place"); checks++;

			preparePad(level, center, before);
			BlockPos raisedBase = center.offset(8, 0, 8);
			BlockPos raisedCorner = center.offset(8, 1, 8);
			setRemembering(level, before, raisedBase, Blocks.DIRT.defaultBlockState());
			setRemembering(level, before, raisedCorner, Blocks.GRASS_BLOCK.defaultBlockState());
			require(Roads.expansionParcelSuitable(level, ref, center),
				"a naturally supported two-block rise is eligible for the road builders to flatten"); checks++;

			preparePad(level, center, before);
			setRemembering(level, before, center.offset(8, 0, 8), Blocks.DIRT.defaultBlockState());
			setRemembering(level, before, center.offset(8, 1, 8), Blocks.DIRT.defaultBlockState());
			setRemembering(level, before, center.offset(8, 2, 8), Blocks.GRASS_BLOCK.defaultBlockState());
			require(!Roads.expansionParcelSuitable(level, ref, center),
				"terrain more than two blocks above the parcel base remains unsuitable"); checks++;

			preparePad(level, center, before);
			BlockPos bed = center;
			setRemembering(level, before, bed, Blocks.BED.white().defaultBlockState());
			require(!Roads.expansionParcelSuitable(level, ref, center) && level.getBlockState(bed).is(Blocks.BED.white()),
				"a home POI footprint is rejected and preserved"); checks++;

			preparePad(level, center, before);
			Map<String, Long> activeParcel = new HashMap<>(oldRoads);
			activeParcel.put(Roads.EXPANSION + "geometry", Roads.EXPANSION_GEOMETRY_VERSION);
			activeParcel.put(Roads.EXPANSION + "x", (long) center.getX());
			activeParcel.put(Roads.EXPANSION + "z", (long) center.getZ());
			activeParcel.put(Roads.EXPANSION + "surface", (long) center.getY());
			activeParcel.put(Roads.EXPANSION + "phase", 1L);
			activeParcel.put(Roads.EXPANSION + "ready", 0L);
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(activeParcel));
			BlockPos tallTreeRoot = center.offset(8, 0, 8);
			for (int y = 0; y <= 24; y++) {
				BlockPos log = tallTreeRoot.above(y);
				setRemembering(level, before, log, Blocks.OAK_LOG.defaultBlockState());
			}
			BlockPos tallCrown = tallTreeRoot.above(25).east();
			setRemembering(level, before, tallCrown, Blocks.OAK_LEAVES.defaultBlockState());
			Map<String, Long> tallTreeCursor = new HashMap<>();
			RoadClearing.Column tallTree = RoadClearing.column(level, ref, tallTreeRoot.getX(), tallTreeRoot.getZ(),
				tallTreeCursor, "tall-tree");
			Roads.Repair tallTrunkTask = tallTree == null || tallTree.next() == null ? null
				: new Roads.Repair(tallTree.next().ground(), Blocks.AIR.defaultBlockState(), tallTree.next().worksite(), false,
					tallTree.next().removal(), tallTree.next().treeRoot(), null, ref.bell(), null,
					Roads.WorkAction.EXPANSION_CLEAR, null, -1, Integer.MIN_VALUE);
			require(Roads.expansionParcelSuitable(level, ref, center)
				&& tallTrunkTask != null && tallTrunkTask.ground().getY() > center.getY() + 12
				&& Roads.repairable(level, tallTrunkTask),
				"a naturally rooted tree above the old scan height remains a safe, reachable paid clearing task"); checks++;
			for (int y = 0; y <= 24; y++) level.setBlock(tallTreeRoot.above(y), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(tallCrown, Blocks.AIR.defaultBlockState(), 3);

			BlockState softLeaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
			BlockPos target = center.above(4);
			setRemembering(level, before, target, softLeaves);
			Roads.Repair clearTask = new Roads.Repair(target, Blocks.AIR.defaultBlockState(), center, false, softLeaves,
				null, null, ref.bell(), null, Roads.WorkAction.EXPANSION_CLEAR, null, -1, Integer.MIN_VALUE);
			require(Roads.repairable(level, clearTask), "a safe nonpersistent tree leaf can be cleared before an occupant arrives"); checks++;
			occupant = EntityTypes.VILLAGER.spawn(level, target, EntitySpawnReason.COMMAND);
			require(occupant != null, "the resident safety fixture spawns");
			occupant.setNoAi(true);
			occupant.setInvulnerable(true);
			occupant.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
			require(!Roads.repairable(level, clearTask) && level.getBlockState(target).equals(softLeaves),
				"an occupied tree block is rejected and remains intact"); checks++;
			occupant.discard();
			occupant = null;
			require(Roads.repairable(level, clearTask), "the same saved task resumes when the resident has left"); checks++;

			preparePad(level, center, before);
			BlockPos upperCut = center.offset(8, 1, 8);
			BlockPos lowerCut = center.offset(8, 0, 8);
			setRemembering(level, before, lowerCut, Blocks.DIRT.defaultBlockState());
			setRemembering(level, before, upperCut, Blocks.GRASS_BLOCK.defaultBlockState());
			BlockPos gradeWorksite = center.offset(7, 0, 8);
			Roads.Repair firstGrade = new Roads.Repair(upperCut, Blocks.AIR.defaultBlockState(), gradeWorksite,
				false, Blocks.GRASS_BLOCK.defaultBlockState(), null, null, ref.bell(), null,
				Roads.WorkAction.EXPANSION_GRADE_CUT, null, -1, Integer.MIN_VALUE);
			require(Roads.repairable(level, firstGrade),
				"the first paid shovel cut is valid on the upper block of a two-block rise"); checks++;
			level.setBlock(upperCut, Blocks.AIR.defaultBlockState(), 3);
			Roads.Repair secondGrade = new Roads.Repair(lowerCut, Blocks.AIR.defaultBlockState(), gradeWorksite,
				false, Blocks.DIRT.defaultBlockState(), null, null, ref.bell(), null,
				Roads.WorkAction.EXPANSION_GRADE_CUT, null, -1, Integer.MIN_VALUE);
			require(Roads.repairable(level, secondGrade),
				"after the first cut, the saved site can safely lower the remaining block to the parcel grade"); checks++;
			level.setBlock(lowerCut, Blocks.AIR.defaultBlockState(), 3);
			BlockPos excessiveCut = center.offset(-8, 2, -8);
			setRemembering(level, before, center.offset(-8, 0, -8), Blocks.DIRT.defaultBlockState());
			setRemembering(level, before, center.offset(-8, 1, -8), Blocks.DIRT.defaultBlockState());
			setRemembering(level, before, excessiveCut, Blocks.GRASS_BLOCK.defaultBlockState());
			Roads.Repair tooHigh = new Roads.Repair(excessiveCut, Blocks.AIR.defaultBlockState(), center.offset(-7, 0, -8),
				false, Blocks.GRASS_BLOCK.defaultBlockState(), null, null, ref.bell(), null,
				Roads.WorkAction.EXPANSION_GRADE_CUT, null, -1, Integer.MIN_VALUE);
			require(!Roads.repairable(level, tooHigh) && level.getBlockState(excessiveCut).is(Blocks.GRASS_BLOCK),
				"a third-block cut is rejected and left untouched"); checks++;
			return checks;
		} finally {
			if (occupant != null) occupant.discard();
			ref.bellEntity().setAttached(VillagerPlus.ROADS, oldRoads);
			ref.dirty();
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			before.forEach((pos, cell) -> {
				if (cell.nbt() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
		}
	}

	private static void preparePad(ServerLevel level, BlockPos center, Map<BlockPos, Cell> before) {
		for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
			for (int dy = -1; dy <= 12; dy++) {
				BlockPos pos = center.offset(dx, dy, dz).immutable();
				level.getChunkAt(pos);
				setRemembering(level, before, pos,
					dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
		}
	}

	private static void setRemembering(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos, BlockState state) {
		BlockEntity entity = level.getBlockEntity(pos);
		if (entity == null && level.getBlockState(pos).equals(state)) return;
		before.putIfAbsent(pos.immutable(), new Cell(level.getBlockState(pos), entity == null ? null
			: entity.saveWithFullMetadata(level.registryAccess())));
		level.setBlock(pos, state, 3);
	}

	private static BlockPos unloadedCandidate(ServerLevel level, BlockPos origin) {
		for (int offset = 512; offset <= 16384; offset *= 2) {
			BlockPos candidate = origin.offset(offset, 0, offset).immutable();
			if (!level.hasChunkAt(candidate)) return candidate;
		}
		return null;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Expansion parcel: " + message);
	}

	private record Cell(BlockState state, CompoundTag nbt) { }
}
