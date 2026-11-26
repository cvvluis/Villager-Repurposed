package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Architect;
import de.villagerplus.build.Built;
import de.villagerplus.build.Builder;
import de.villagerplus.build.HouseDesignCatalog;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Focused retrofit check for a saved mod-built house that has two adult residents but only one physical bed. */
public final class HousingBedRepairChecks {
	private HousingBedRepairChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		BlockPos primary = at;
		BlockPos protectedHouse = at.east(28);
		BlockPos untrackedHouse = at.east(56);
		BlockPos bellPos = at.west(16);
		return runFixture(level, at, primary, protectedHouse, untrackedHouse, bellPos);
	}

	private static int runFixture(ServerLevel level, BlockPos at, BlockPos primary, BlockPos protectedHouse,
			BlockPos untrackedHouse, BlockPos bellPos) {
		// A pre-two-resident catalog variant reproduces saved houses from older worlds.
		int oldVariant = HouseDesignCatalog.newPlanVariant(Architect.SizeTier.COMPACT, 17L) & ~2;
		var oldDesign = HouseDesignCatalog.designForPlanVariant(oldVariant);
		if (oldDesign.beds() != 1) throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: fixture is not a one-bed legacy design");
		Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
			Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		Built primaryBuilt = new Built(Blueprints.HOUSE, primary, Direction.SOUTH.get2DDataValue(), oldVariant, palette.ids());
		Built protectedBuilt = new Built(Blueprints.HOUSE, protectedHouse, Direction.SOUTH.get2DDataValue(), oldVariant, palette.ids());
		Built foreignBuilt = new Built(Blueprints.HOUSE, untrackedHouse, Direction.SOUTH.get2DDataValue(), oldVariant, palette.ids());
		List<Step> primarySteps = Architect.build(oldDesign, palette, primary, Direction.SOUTH);
		List<Step> protectedSteps = Architect.build(oldDesign, palette, protectedHouse, Direction.SOUTH);
		List<Step> foreignSteps = Architect.build(oldDesign, palette, untrackedHouse, Direction.SOUTH);
		if (primarySteps.isEmpty() || protectedSteps.isEmpty() || foreignSteps.isEmpty()) {
			throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: house design has no steps");
		}
		List<List<Step>> primaryPairs = null, protectedPairs = null, foreignPairs = null;
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		List<Villager> spawned = new ArrayList<>();
		VillageRef fixtureRef = null;
		try {
			for (int x = -20; x <= 78; x++) for (int y = -3; y <= 14; y++) for (int z = -18; z <= 20; z++) {
				BlockPos pos = at.offset(x, y, z);
				level.getChunkAt(pos);
				before.put(pos, level.getBlockState(pos));
				level.setBlock(pos, y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
			buildSavedShape(level, primarySteps);
			buildSavedShape(level, protectedSteps);
			buildSavedShape(level, foreignSteps);

			level.setBlock(bellPos, Blocks.BELL.defaultBlockState(), 3);
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) {
				throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: fixture bell was not created");
			}
			VillageData data = new VillageData();
			bell.setAttached(VillagerPlus.VILLAGE, data);
			VillageRef ref = new VillageRef(bellPos, data, bell);
			fixtureRef = ref;
			bell.setAttached(VillagerPlus.BUILDINGS, List.of(primaryBuilt, protectedBuilt));
			primaryPairs = Builder.houseBedPairsOfBuilt(level, ref, primaryBuilt);
			protectedPairs = Builder.houseBedPairsOfBuilt(level, ref, protectedBuilt);
			foreignPairs = Builder.houseBedPairsOfBuilt(level, ref, foreignBuilt);
			if (primaryPairs.size() != 2 || protectedPairs.size() != 2 || foreignPairs.size() != 2) {
				throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: two bed candidate pairs were not reconstructed");
			}
			List<Step> primaryMissing = missingPair(primaryPairs, primarySteps, level);
			List<Step> protectedMissing = missingPair(protectedPairs, protectedSteps, level);
			List<Step> foreignMissing = missingPair(foreignPairs, foreignSteps, level);
			if (primaryMissing == null || protectedMissing == null || foreignMissing == null) {
				throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: fixture beds are not in expected positions");
			}
			level.setBlock(protectedMissing.getFirst().pos(), Blocks.CHEST.defaultBlockState(), 3);
			spawnPair(level, ref, primary, primaryPairs.getFirst(), spawned);
			spawnPair(level, ref, protectedHouse, protectedPairs.getFirst(), spawned);
			spawnPair(level, ref, untrackedHouse, foreignPairs.getFirst(), spawned);

			Housing.repairBuiltHouseBeds(level, ref);
			check(completeBedPairs(level, primaryPairs) == 2,
				"a tracked old house with two adults and one bed receives its saved-design second bed");
			check(level.getBlockState(protectedMissing.getFirst().pos()).is(Blocks.CHEST)
				&& completeBedPairs(level, protectedPairs) == 1,
				"the retrofit leaves a player chest at the candidate bed footprint untouched");
			check(completeBedPairs(level, foreignPairs) == 1,
				"a matching but untracked foreign house is not modified");

			List<Built> reloadedBuildings = Built.CODEC.listOf().parse(net.minecraft.nbt.NbtOps.INSTANCE,
				Built.CODEC.listOf().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, List.of(primaryBuilt, protectedBuilt)).getOrThrow()).getOrThrow();
			bell.setAttached(VillagerPlus.BUILDINGS, reloadedBuildings);
			BlockState repairedFoot = level.getBlockState(primaryPairs.getLast().getFirst().pos());
			BlockState repairedHead = level.getBlockState(primaryPairs.getLast().get(1).pos());
			Housing.resetBuiltHouseBedRepairForChecks(level, ref);
			Housing.repairBuiltHouseBeds(level, ref);
			check(reloadedBuildings.equals(List.of(primaryBuilt, protectedBuilt))
				&& level.getBlockState(primaryPairs.getLast().getFirst().pos()).equals(repairedFoot)
				&& level.getBlockState(primaryPairs.getLast().get(1).pos()).equals(repairedHead)
				&& completeBedPairs(level, primaryPairs) == 2,
				"the saved building record survives codec reload and the bed retrofit is idempotent");
			return 4;
		} finally {
			for (Villager villager : spawned) if (villager.isAlive()) villager.discard();
			for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
				level.removeBlockEntity(entry.getKey());
				level.setBlock(entry.getKey(), entry.getValue(), 3);
			}
			Villages.resetAssignmentCache();
			Housing.resetBuiltHouseBedRepairForChecks(level, fixtureRef);
		}
	}

	private static int completeBedPairs(ServerLevel level, List<List<Step>> pairs) {
		int count = 0;
		for (List<Step> pair : pairs) {
			BlockState foot = level.getBlockState(pair.getFirst().pos()), head = level.getBlockState(pair.get(1).pos());
			if (foot.getBlock() instanceof BedBlock && head.is(foot.getBlock())
				&& foot.getValue(BedBlock.PART) == BedPart.FOOT && head.getValue(BedBlock.PART) == BedPart.HEAD
				&& foot.getValue(BedBlock.FACING) == head.getValue(BedBlock.FACING)) count++;
		}
		return count;
	}

	private static List<Step> missingPair(List<List<Step>> pairs, List<Step> originalShape, ServerLevel level) {
		return pairs.stream().filter(pair -> level.getBlockState(pair.getFirst().pos()).isAir()
			&& (level.getBlockState(pair.get(1).pos()).isAir()
				|| matchesSavedFreeBlock(originalShape, pair.get(1).pos(), level.getBlockState(pair.get(1).pos()))))
			.findFirst().orElse(null);
	}

	private static boolean matchesSavedFreeBlock(List<Step> shape, BlockPos pos, BlockState current) {
		for (Step step : shape) if (step.pos().equals(pos) && step.free() && step.make().apply(Blocks.AIR).equals(current)) return true;
		return false;
	}

	private static void buildSavedShape(ServerLevel level, List<Step> steps) {
		for (Step step : steps) {
			Block source;
			if (step.item() != null) source = Block.byItem(step.item());
			else if (step.tag() != null && step.tag().equals(net.minecraft.tags.ItemTags.BEDS)) source = Blocks.BED.white();
			else if (step.linkedTo() != null) source = level.getBlockState(step.linkedTo()).getBlock();
			else source = Blocks.AIR;
			level.setBlock(step.pos(), step.make().apply(source), 3);
		}
	}

	private static void spawnPair(ServerLevel level, VillageRef ref, BlockPos origin, List<Step> bedPair, List<Villager> spawned) {
		BlockPos foot = bedPair.getFirst().pos();
		BlockPos firstPos = freeInterior(level, origin, foot);
		BlockPos secondPos = freeInterior(level, origin, firstPos);
		Villager first = spawn(level, firstPos), second = spawn(level, secondPos);
		spawned.add(first); spawned.add(second);
		Villages.bindResident(level, ref, first, foot);
		Villages.bindResident(level, ref, second, null);
	}

	private static BlockPos freeInterior(ServerLevel level, BlockPos origin, BlockPos avoid) {
		for (int y = 1; y <= 5; y++) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
			BlockPos pos = origin.offset(x, y, z);
			if (!pos.equals(avoid) && level.getBlockState(pos).isAir()
				&& level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return pos;
		}
		throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: no interior spawn point near " + origin);
	}

	private static Villager spawn(ServerLevel level, BlockPos pos) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: could not spawn a villager");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		return villager;
	}

	private static void check(boolean success, String message) {
		if (!success) throw new IllegalStateException("HOUSING BED REPAIR CHECK FAILED: " + message);
	}
}
