package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.compat.SeasonCompat;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Restores an entirely bare field through the farmer's normal primary job. */
public final class FarmlandRestoreChecks {
	private FarmlandRestoreChecks() { }

	public static int run(ServerLevel level, BlockPos bell) {
		BlockPos field = bell.offset(50, 0, 3);
		BlockPos outsideVillage = bell.offset(104, 0, 3);
		List<BlockPos> requiredChunks = List.of(bell, field, outsideVillage);
		boolean ready = true;
		for (BlockPos pos : requiredChunks) ready &= ButcherChecks.prepareEntityTickingFixture(level, pos);
		if (!ready) return -1;

		List<BlockPos> cells = List.of(field, field.south(), field.east(), field.east().south());
		BlockPos protectedSoil = field.east(3);
		BlockPos unzonedSoil = field.east(5);
		BlockPos outsideComposter = outsideVillage.east(2);
		Map<BlockPos, BlockState> original = new LinkedHashMap<>();
		VillageRef ref = Checks090.fixtureForEquipment(level, bell);
		Villager worker = null;
		SeasonCompat.setDevOverrideOff();
		try {
			for (BlockPos pos : cells) soil(level, original, pos);
			for (BlockPos pos : List.of(protectedSoil, unzonedSoil, outsideVillage)) soil(level, original, pos);
			stage(level, original, field.west().below(2), Blocks.STONE.defaultBlockState());
			stage(level, original, field.west().below(), Blocks.WATER.defaultBlockState());
			stage(level, original, outsideComposter.below(), Blocks.STONE.defaultBlockState());
			stage(level, original, outsideComposter, Blocks.COMPOSTER.defaultBlockState());
			ChunkPos unloaded = unloadedProbe(level, bell);
			worker = EntityTypes.VILLAGER.spawn(level, field, EntitySpawnReason.COMMAND);
			require(worker != null, "farmer spawned in the ticking field chunk");
			worker.addTag("vp090_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), bell));
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, cells.size()));
			FarmerJob.clearCropScans();
			FarmerJob farmer = new FarmerJob();
			JobState state = new JobState();
			VillagerMemory memory = new VillagerMemory();
			long now = level.getGameTime() + 1;
			for (BlockPos pos : cells) {
				workAt(farmer, level, ref, worker, state, memory, pos, now);
				require(level.getBlockState(pos).is(Blocks.WHEAT), "unmapped bare field restored at " + pos.toShortString());
				now += 30;
			}
			require(JobUtil.count(worker.getInventory(), stack -> stack.is(Items.WHEAT_SEEDS)) == 0,
				"the first restoration consumes one seed for every planted cell");
			require(level.getChunkSource().getChunkNow(unloaded.x(), unloaded.z()) == null,
				"field discovery leaves unrequested chunks unloaded");

			ZonePolicy.Zone allowed = new ZonePolicy.Zone("bare-field", "farm", field.getX(), field.getZ(),
				protectedSoil.getX(), field.south().getZ());
			ZonePolicy.Zone blocked = new ZonePolicy.Zone("protected-soil", "protected", protectedSoil.getX(), protectedSoil.getZ(),
				protectedSoil.getX(), protectedSoil.getZ());
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(allowed, blocked));
			for (BlockPos pos : cells) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			memory.workSpots.clear();
			worker.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, cells.size() + 1));
			for (BlockPos pos : cells) {
				workAt(farmer, level, ref, worker, state, memory, pos, now);
				require(level.getBlockState(pos).is(Blocks.WHEAT), "cached field restored again at " + pos.toShortString());
				now += 30;
			}
			workAt(farmer, level, ref, worker, state, memory, unzonedSoil, now);
			require(level.getBlockState(unzonedSoil).isAir() && level.getBlockState(protectedSoil).isAir()
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.WHEAT_SEEDS)) == 1,
				"farmland outside the farm zone and farmland in a protected zone stay untouched");
			ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			worker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), outsideComposter));
			memory.workSpots.add(outsideVillage);
			workAt(farmer, level, ref, worker, new JobState(), memory, outsideVillage, now + 300);
			require(level.getBlockState(outsideVillage).isAir() && JobUtil.count(worker.getInventory(), stack -> stack.is(Items.WHEAT_SEEDS)) == 1,
				"remembered soil by a composter outside the village stays untouched");
			return 12;
		} finally {
			if (worker != null) worker.discard();
			ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			original.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
			FarmerJob.clearCropScans();
			SeasonCompat.clearDevOverride();
			for (BlockPos pos : requiredChunks) ButcherChecks.releaseEntityTickingFixture(level, pos);
		}
	}

	private static void workAt(FarmerJob farmer, ServerLevel level, VillageRef ref, Villager worker, JobState state,
			VillagerMemory memory, BlockPos pos, long now) {
		worker.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		state.forageLastRollAt = now;
		farmer.primary(new Ctx(level, worker, state, memory, ref, now));
	}

	private static void soil(ServerLevel level, Map<BlockPos, BlockState> original, BlockPos pos) {
		stage(level, original, pos.below(2), Blocks.STONE.defaultBlockState());
		stage(level, original, pos.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
		stage(level, original, pos, Blocks.AIR.defaultBlockState());
	}

	private static void stage(ServerLevel level, Map<BlockPos, BlockState> original, BlockPos pos, BlockState state) {
		require(level.getBlockEntity(pos) == null, "no block entity at the fixture cell " + pos.toShortString());
		original.putIfAbsent(pos, level.getBlockState(pos));
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
	}

	private static ChunkPos unloadedProbe(ServerLevel level, BlockPos bell) {
		for (int dx = -4; dx <= -2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				ChunkPos pos = new ChunkPos((bell.getX() >> 4) + dx, (bell.getZ() >> 4) + dz);
				if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) == null) return pos;
			}
		}
		throw new IllegalStateException("FARMLAND RESTORE CHECK FAILED: no unloaded chunk inside the field scan");
	}

	private static void require(boolean condition, String detail) {
		if (!condition) throw new IllegalStateException("FARMLAND RESTORE CHECK FAILED: " + detail);
	}
}
