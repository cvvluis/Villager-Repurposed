package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic checks for the wood supply rules: plan trees are chosen nearest-first from the village chest in rings (not from wherever
 * the worker stands), a tree another resident already holds is skipped, logs reach the public chest between two trees as soon as a
 * batch is carried, a stop at the crafting table turns the logs on hand into planks as far as the request allows, and the limbs of a
 * tree fall faster than its foot. Fixtures are synthetic: green results are no proof of natural behaviour.
 */
public final class WoodSupplyChecks {
	private static final int FLAGS = 2;
	private static final long T0 = 800_000L;

	private WoodSupplyChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		List<Villager> workers = new ArrayList<>();
		VillageRef ref = null;
		int checks = 0;
		try {
			for (int cx = -8; cx <= 112; cx += 16) for (int cz = -32; cz <= 32; cz += 16) level.getChunk(origin.offset(cx, 0, cz));
			level.getChunk(origin.offset(112, 0, 32));
			for (BlockPos p : BlockPos.betweenClosed(origin.offset(-8, -2, -32), origin.offset(112, 12, 32))) {
				BlockPos pos = p.immutable();
				BlockEntity be = level.getBlockEntity(pos);
				before.put(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
			}
			for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
			for (int dx = -8; dx <= 112; dx++) for (int dz = -32; dz <= 32; dz++) {
				for (int dy = -2; dy <= 12; dy++) level.setBlock(origin.offset(dx, dy, dz), (dy < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), FLAGS);
				level.setBlock(origin.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
			}
			BlockPos bell = origin;
			BlockPos chestPos = origin.east(3);
			level.setBlock(bell.below(), Blocks.STONE.defaultBlockState(), FLAGS);
			level.setBlock(bell, Blocks.BELL.defaultBlockState(), FLAGS);
			level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), FLAGS);
			BlockEntity bellEntity = level.getBlockEntity(bell);
			Container chest = level.getBlockEntity(chestPos) instanceof Container found ? found : null;
			require(bellEntity != null && chest != null, "fixture village bell and communal chest exist"); checks++;
			VillageData data = new VillageData();
			data.chest = Optional.of(chestPos);
			data.planType = "woodsupply-fixture";
			data.planOrigin = Optional.of(origin);
			bellEntity.setAttached(VillagerPlus.VILLAGE, data);
			ref = new VillageRef(bell, data, bellEntity);
			data.wanted.add("minecraft:spruce_planks#12");
			ref.dirty();
			Set<String> spruce = Woodcutting.demandedLogIds(data.wanted);
			require(spruce.contains("minecraft:spruce_log") && Woodcutting.planNeedsWood(data), "a planks request is a plan wood demand for spruce logs"); checks++;

			// --- tree choice: nearest to the chest, rings outward, claimed trees skipped --------------------------------
			BlockPos near = origin.offset(23, 1, 0);   // 20 blocks from the chest
			BlockPos mid = origin.offset(43, 1, 4);    // about 40 blocks from the chest
			BlockPos far = origin.offset(98, 1, -4);   // about 95 blocks from the chest
			tree(level, near, Blocks.SPRUCE_LOG); tree(level, mid, Blocks.SPRUCE_LOG); tree(level, far, Blocks.SPRUCE_LOG);
			Villager scout = spawn(level, mid.west(2), workers);
			Woodcutting.resetWalkBackMemory();
			BlockPos picked = Woodcutting.findTree(ctx(level, ref, scout, T0), true, spruce);
			require(near.equals(picked), "a worker standing beside a far tree still takes the tree nearest the village chest"
				+ " [picked=" + (picked == null ? "none" : picked.toShortString()) + "]"); checks++;

			Villager holder = spawn(level, near.south(3), workers);
			holder.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(near, "minecraft:spruce"));
			require(Woodcutting.claimedByOther(level, scout, near) && !Woodcutting.claimedByOther(level, holder, near),
				"a tree held by another resident's woodcutter cursor counts as claimed for everybody else"); checks++;
			picked = Woodcutting.findTree(ctx(level, ref, scout, T0 + 10), true, spruce);
			require(mid.equals(picked), "with the nearest tree claimed the next nearest is chosen"
				+ " [picked=" + (picked == null ? "none" : picked.toShortString()) + "]"); checks++;
			holder.removeAttached(VillagerPlus.WOODCUTTER_WORK);
			level.setBlock(near, Blocks.AIR.defaultBlockState(), FLAGS);
			for (int dy = 1; dy < 4; dy++) level.setBlock(near.above(dy), Blocks.AIR.defaultBlockState(), FLAGS);
			level.setBlock(mid, Blocks.AIR.defaultBlockState(), FLAGS);
			for (int dy = 1; dy < 4; dy++) level.setBlock(mid.above(dy), Blocks.AIR.defaultBlockState(), FLAGS);
			picked = Woodcutting.findTree(ctx(level, ref, scout, T0 + 20), true, spruce);
			require(far.equals(picked), "the extended ring is searched only when the nearer rings hold no tree"
				+ " [picked=" + (picked == null ? "none" : picked.toShortString()) + "]"); checks++;
			tree(level, near, Blocks.SPRUCE_LOG);

			// --- delivery between two trees ---------------------------------------------------------------------------
			Villager carrier = spawn(level, chestPos.west(2), workers);
			Villages.bindResident(level, ref, carrier, chestPos);
			JobState carrierState = JobManager.state(carrier);
			carrierState.woodCheckAt = 0;
			carrier.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 2));
			Woodcutting.tick(ctx(level, ref, carrier, T0 + 100));
			require(JobUtil.count(chest, s -> s.is(Items.SPRUCE_LOG)) == 0 && JobUtil.count(carrier.getInventory(), s -> s.is(Items.SPRUCE_LOG)) == 2,
				"a carried batch below the plan's shortfall is not carried back and forth between trees"); checks++;
			carrier.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 5));
			BlockPos stand = JobUtil.sharedStandSpot(ctx(level, ref, carrier, T0 + 200), chestPos, 4.0);
			require(stand != null, "the public chest has a free delivery stand"); checks++;
			carrier.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			carrierState.depositBlockedUntil = 0;
			Woodcutting.tick(ctx(level, ref, carrier, T0 + 200));
			require(JobUtil.count(chest, s -> s.is(Items.SPRUCE_LOG)) == 5 && JobUtil.count(carrier.getInventory(), s -> s.is(Items.SPRUCE_LOG)) == 0,
				"between two trees the carried logs reach the public chest at once when the plan lacks them (no log lost or duplicated)"); checks++;
			chest.clearContent();

			// --- bulk planks at the table ---------------------------------------------------------------------------------
			Villager crafter = spawn(level, chestPos.west(2), workers);
			Villages.bindResident(level, ref, crafter, chestPos);
			chest.setItem(0, new ItemStack(Items.SPRUCE_LOG, 8));
			data.wanted.clear(); data.wanted.add("minecraft:spruce_planks#8"); ref.dirty();
			Ctx craftCtx = ctx(level, ref, crafter, T0 + 300);
			boolean crafted = RecipeCrafting.craft(craftCtx, chest, null, s -> s.is(Items.SPRUCE_PLANKS), 8);
			int planks = JobUtil.count(chest, s -> s.is(Items.SPRUCE_PLANKS)), logs = JobUtil.count(chest, s -> s.is(Items.SPRUCE_LOG));
			require(crafted && planks == 8 && logs == 6, "one stop turns two logs into exactly the 8 planks the plan asks for, not more"
				+ " [planks=" + planks + ", logs=" + logs + "]"); checks++;
			require(JobManager.state(crafter).cooldownUntil == T0 + 300 + 100 + RecipeCrafting.PLANK_BATCH_PAUSE,
				"every extra plank batch costs a short pause on top of the normal crafting pause"); checks++;
			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.SPRUCE_LOG, 12));
			data.wanted.clear(); data.wanted.add("minecraft:spruce_planks#64"); ref.dirty();
			crafted = RecipeCrafting.craft(ctx(level, ref, crafter, T0 + 400), chest, null, s -> s.is(Items.SPRUCE_PLANKS), 64);
			planks = JobUtil.count(chest, s -> s.is(Items.SPRUCE_PLANKS)); logs = JobUtil.count(chest, s -> s.is(Items.SPRUCE_LOG));
			require(crafted && planks == 4 * RecipeCrafting.MAX_PLANK_BATCHES && logs == 12 - RecipeCrafting.MAX_PLANK_BATCHES,
				"a stop crafts at most " + RecipeCrafting.MAX_PLANK_BATCHES + " batches and leaves the remaining logs in the chest"
					+ " [planks=" + planks + ", logs=" + logs + "]"); checks++;
			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.SPRUCE_LOG, 4));
			data.wanted.clear(); ref.dirty();
			crafted = RecipeCrafting.craft(ctx(level, ref, crafter, T0 + 500), chest, null, s -> s.is(Items.SPRUCE_PLANKS), 6);
			planks = JobUtil.count(chest, s -> s.is(Items.SPRUCE_PLANKS));
			require(planks <= 6 && planks == 4, "without a plan request the stock cap still bounds the output"
				+ " [planks=" + planks + "]"); checks++;
			chest.clearContent();

			// --- limbs fall faster than the foot ------------------------------------------------------------------------
			BlockPos grove = origin.offset(10, 1, 20);
			tree(level, grove, Blocks.SPRUCE_LOG);
			Villager feller = spawn(level, grove.west(), workers);
			Villages.bindResident(level, ref, feller, grove.west());
			data.wanted.clear(); data.wanted.add("minecraft:spruce_planks#12"); ref.dirty();
			feller.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(grove, "minecraft:spruce"));
			JobState fellerState = JobManager.state(feller);
			fellerState.woodCheckAt = 0;
			long now = T0 + 1_000, start = now;
			int footTicks = -1;
			for (int call = 0; call < 400; call++, now += 10) {
				fellerState.cooldownUntil = 0;
				Woodcutting.tick(ctx(level, ref, feller, now));
				if (footTicks < 0 && level.getBlockState(grove.above(1)).isAir() && level.getBlockState(grove).isAir()) footTicks = (int) (now - start);
				if (level.getBlockState(grove).isAir() && feller.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty()).root().isEmpty()) break;
			}
			int total = (int) (now - start);
			int logsGot = JobUtil.count(feller.getInventory(), s -> s.is(Items.SPRUCE_LOG));
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("WoodSupply: four-log tree felled in {} ticks (foot free after {})", total, footTicks);
			require(logsGot == 4 && level.getBlockState(grove).isAir(), "a four-log tree gives its four real logs"
				+ " [logs=" + logsGot + ", ticks=" + total + "]"); checks++;
			require(total <= 400, "four logs are felled within 400 ticks (limbs start half cut) [ticks=" + total + "]"); checks++;
			return checks;
		} finally {
			for (Villager worker : workers) {
				Anim.cancelBreaking(worker);
				Villages.forget(worker.getUUID());
				worker.discard();
			}
			for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
			before.keySet().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS));
			before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), FLAGS));
			before.forEach((pos, cell) -> {
				if (cell.data() == null) return;
				BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			});
			Woodcutting.resetWalkBackMemory();
		}
	}

	private static void tree(ServerLevel level, BlockPos root, Block log) {
		level.setBlock(root.below(), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
		for (int dy = 0; dy < 4; dy++) level.setBlock(root.above(dy), log.defaultBlockState(), FLAGS);
		for (int dy = 3; dy <= 5; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			if (dx == 0 && dz == 0 && dy <= 3) continue;
			if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 4) <= 5) level.setBlock(root.offset(dx, dy, dz), Blocks.SPRUCE_LEAVES.defaultBlockState(), FLAGS);
		}
	}

	private static Villager spawn(ServerLevel level, BlockPos pos, List<Villager> workers) {
		Villager worker = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		require(worker != null, "worker spawns in the fixture");
		worker.setNoAi(true);
		worker.setInvulnerable(true);
		worker.setPersistenceRequired();
		workers.add(worker);
		return worker;
	}

	private static Ctx ctx(ServerLevel level, VillageRef ref, Villager worker, long now) {
		VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
		return new Ctx(level, worker, JobManager.state(worker), memory, ref, now);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Wood supply: " + message);
	}

	private record Cell(BlockState state, CompoundTag data) { }
}
