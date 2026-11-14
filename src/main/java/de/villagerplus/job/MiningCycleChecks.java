package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Focused in-world checks for real mining tools, drops, and full-inventory retry safety. */
public final class MiningCycleChecks {
	private MiningCycleChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new HashMap<>();
		List<Villager> queueWorkers = new ArrayList<>();
		Villager worker = null;
		int checks = 0;
		try {
			for (int x = -5; x <= 4; x++) {
				for (int y = -1; y <= 4; y++) for (int z = -16; z <= 16; z++) {
					BlockPos pos = origin.offset(x, y, z);
					level.getChunkAt(pos);
					if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Mining fixture intersects a block entity");
					before.put(pos, level.getBlockState(pos));
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
				}
			}
			// Keep the full 18-pad approach footprint flat and connected. UUID-first
			// choices can land on either side of the entrance, up to seven blocks out.
			for (int x = -5; x <= 0; x++) for (int z = -15; z <= 15; z++) {
				level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
			}
			for (int lane = -1; lane <= 1; lane++) {
				level.setBlock(origin.relative(Direction.WEST, lane).below(), Blocks.STONE.defaultBlockState(), 3);
			}
			for (int sign : new int[]{-1, 1}) {
				level.setBlock(origin.relative(Direction.WEST, sign * 2).relative(Direction.SOUTH, 2).below(), Blocks.STONE.defaultBlockState(), 3);
			}
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("Cannot create mining worker fixture");
			worker.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
			worker.setOnGround(true);
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			level.addFreshEntity(worker);
			Ctx context = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY), null, level.getGameTime());

			VillageData.Mine waitingMine = new VillageData.Mine(origin, Direction.EAST.get2DDataValue(), 4, origin.getY() - 2, false);
			int preferredA = Mining.preferredMineWaitingSlot(new UUID(0L, 1L));
			int preferredB = Mining.preferredMineWaitingSlot(new UUID(0L, 2L));
			int preferredC = Mining.preferredMineWaitingSlot(new UUID(0L, 3L));
			check(preferredA >= 0 && preferredA < Mining.WAITING_STAND_COUNT
				&& preferredB >= 0 && preferredB < Mining.WAITING_STAND_COUNT
				&& preferredC >= 0 && preferredC < Mining.WAITING_STAND_COUNT,
				"each villager gets a bounded preferred mine waiting pad"); checks++;
			check(preferredA != preferredB && preferredA != preferredC && preferredB != preferredC,
				"stable villager identities spread first choices across distinct mine waiting pads"); checks++;
			Set<BlockPos> waitingTargets = new HashSet<>();
			for (int slot = 0; slot < Mining.WAITING_STAND_COUNT; slot++) {
				BlockPos stand = Mining.mineWaitingStand(level, waitingMine, slot);
				check(stand != null && Mining.mineWaitingCellSafe(context, stand),
					"each queue pad has loaded, supported, collision-free headroom [slot=" + slot + ", stand=" + stand + "]"); checks++;
				boolean spaced = stand != null && waitingTargets.stream().allMatch(other -> {
					int dx = stand.getX() - other.getX();
					int dz = stand.getZ() - other.getZ();
					return dx * dx + dz * dz >= 4;
				});
				check(stand != null && !stand.equals(waitingMine.entrance) && spaced && waitingTargets.add(stand),
					"queue pads have distinct two-block horizontal spacing away from the doorway [slot=" + slot + ", stand=" + stand + "]"); checks++;
				if (stand == null) continue;
				var waitPath = worker.getNavigation().createPath(stand, 0);
				check(waitPath != null && waitPath.canReach(),
					"vanilla navigation reaches queue slot " + slot + " at " + stand
						+ " (path=" + (waitPath == null ? "none" : waitPath.canReach()) + ")"); checks++;
			}
			check(waitingTargets.size() == Mining.WAITING_STAND_COUNT,
				"all mine approach pads remain unique and spaced independently of the entrance cell"); checks++;
			BlockPos firstDescent = Mining.nextMineWaypoint(waitingMine, 0, waitingMine.index);
			check(Mining.nextMineWaypoint(waitingMine, -1, waitingMine.index).equals(waitingMine.entrance)
				&& firstDescent.equals(new BlockPos(origin.getX() + 1, origin.getY() - 1, origin.getZ()))
				&& !waitingTargets.contains(waitingMine.entrance),
				"distributed queue pads preserve the real center entrance and one-section descent route"); checks++;

			Mining.rememberMineRoute(worker, level, waitingMine, 0);
			Villager secondEntrant = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (secondEntrant == null) throw new IllegalStateException("Cannot create second simultaneous miner fixture");
			secondEntrant.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
			secondEntrant.setOnGround(true);
			secondEntrant.setNoAi(true);
			secondEntrant.setInvulnerable(true);
			secondEntrant.addTag("vp090_fixture");
			if (!level.addFreshEntity(secondEntrant)) throw new IllegalStateException("Cannot add second simultaneous miner fixture");
			queueWorkers.add(secondEntrant);
			Ctx secondContext = new Ctx(level, secondEntrant, new JobState(),
				secondEntrant.getAttachedOrCreate(VillagerPlus.MEMORY), null, level.getGameTime());
			int secondWaitingSlot = Mining.findMineWaitingSlot(secondContext, waitingMine);
			Mining.rememberMineWaiting(secondEntrant, level, waitingMine, secondWaitingSlot);
			BlockPos secondWaitingStand = Mining.mineWaitingStand(level, waitingMine, secondWaitingSlot);
			Mining.waitAtMineStand(secondContext, waitingMine, secondWaitingSlot);
			BlockPos secondWalkTarget = secondEntrant.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
				.map(target -> target.getTarget().currentBlockPosition()).orElse(null);
			check(Mining.mineEntranceAdmissionBlocked(secondContext, waitingMine)
				&& Mining.findMineWorkSlot(secondContext, waitingMine) == -1
				&& secondWaitingStand != null && !secondWaitingStand.equals(waitingMine.entrance)
				&& secondWalkTarget != null && !secondWalkTarget.equals(waitingMine.entrance)
				&& secondEntrant.position().distanceToSqr(origin.getX() + .5, origin.getY(), origin.getZ() + .5) < 0.01
				&& waitingMine.index == 4 && !waitingMine.done,
				"two simultaneous miners serialize portal admission: the second walks to its own retry stand without teleporting or advancing the shared cursor"); checks++;
			secondEntrant.discard();
			queueWorkers.remove(secondEntrant);
			worker.removeAttached(VillagerPlus.MINING_ROUTE);
			worker.setPos(origin.getX() - 8.5, origin.getY(), origin.getZ() - 14.5);

			Villager blockedEntrant = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (blockedEntrant == null) throw new IllegalStateException("Cannot create blocked mine entrance fixture");
			blockedEntrant.setPos(origin.getX() - 5.5, origin.getY(), origin.getZ() + .5);
			blockedEntrant.setOnGround(true);
			blockedEntrant.setNoAi(true);
			blockedEntrant.setInvulnerable(true);
			blockedEntrant.addTag("vp090_fixture");
			if (!level.addFreshEntity(blockedEntrant)) throw new IllegalStateException("Cannot add blocked mine entrance fixture");
			queueWorkers.add(blockedEntrant);
			Ctx blockedContext = new Ctx(level, blockedEntrant, new JobState(),
				blockedEntrant.getAttachedOrCreate(VillagerPlus.MEMORY), null, level.getGameTime());
			Mining.rememberMineRoute(blockedEntrant, level, waitingMine, 0);
			BlockPos beforeBlockedRetry = blockedEntrant.blockPosition();
			BlockState openEntrance = level.getBlockState(waitingMine.entrance);
			level.setBlock(waitingMine.entrance, Blocks.STONE.defaultBlockState(), 3);
			Mining.MineWalk blockedEntry = Mining.walkMineStep(blockedContext, waitingMine, waitingMine.entrance);
			Mining.waitOutsideBlockedMineEntry(blockedContext, waitingMine);
			Mining.MineRoute blockedSavedRoute = blockedEntrant.getAttached(VillagerPlus.MINING_ROUTE);
			BlockPos blockedRetryTarget = blockedEntrant.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
				.map(target -> target.getTarget().currentBlockPosition()).orElse(null);
			check(blockedEntry == Mining.MineWalk.STUCK && blockedSavedRoute != null && blockedSavedRoute.index() < 0
				&& blockedSavedRoute.entrance().equals(waitingMine.entrance) && blockedSavedRoute.targetY() == waitingMine.targetY
				&& blockedRetryTarget != null && !blockedRetryTarget.equals(waitingMine.entrance)
				&& blockedEntrant.blockPosition().equals(beforeBlockedRetry)
				&& waitingMine.index == 4 && !waitingMine.done,
				"a blocked entrance retains its saved mine route and cursor while normal navigation retries from a distinct surface stand"); checks++;
			level.setBlock(waitingMine.entrance, openEntrance, 3);

			Set<Integer> assignedWaitingSlots = new HashSet<>();
			Set<BlockPos> assignedWaitingTargets = new HashSet<>();
			for (int i = 0; i < 24; i++) {
				Villager queued = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
				if (queued == null) throw new IllegalStateException("Cannot create queue resident fixture " + i);
				queued.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
				queued.setOnGround(true);
				queued.setNoAi(true);
				queued.setInvulnerable(true);
				queued.addTag("vp090_fixture");
				if (!level.addFreshEntity(queued)) throw new IllegalStateException("Cannot add queue resident fixture " + i);
				queueWorkers.add(queued);
				Ctx queuedContext = new Ctx(level, queued, new JobState(), queued.getAttachedOrCreate(VillagerPlus.MEMORY), null, level.getGameTime());
				int slot = Mining.findMineWaitingSlot(queuedContext, waitingMine);
				check(slot >= 0 && assignedWaitingSlots.add(slot),
					"bunched resident receives an unused waiting reservation beyond the old 18-pad queue [resident=" + i + ", slot=" + slot + "]"); checks++;
				if (slot < 0) continue;
				Mining.rememberMineWaiting(queued, level, waitingMine, slot);
				BlockPos stand = Mining.mineWaitingStand(level, waitingMine, slot);
				var savedRoute = queued.getAttached(VillagerPlus.MINING_ROUTE);
				check(stand != null && assignedWaitingTargets.add(stand)
					&& savedRoute != null && savedRoute.index() == -slot - 1
					&& savedRoute.entrance().equals(waitingMine.entrance) && savedRoute.targetY() == waitingMine.targetY
					&& savedRoute.reservationUntil() > level.getGameTime(),
					"distinct queue assignment persists without advancing or replacing the shared mine cursor [slot=" + slot + "]"); checks++;
				Mining.waitAtMineStand(queuedContext, waitingMine, slot);
				BlockPos walkTarget = queued.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
					.map(target -> target.getTarget().currentBlockPosition()).orElse(null);
				check(stand.equals(walkTarget) && queued.position().distanceToSqr(origin.getX() + .5, origin.getY(), origin.getZ() + .5) < 0.01,
					"waiting worker gets a real distinct vanilla walk target and is not teleported [slot=" + slot + ", target=" + walkTarget + "]"); checks++;
			}
			check(assignedWaitingSlots.size() == 24 && assignedWaitingTargets.size() == 24
				&& waitingMine.index == 4 && !waitingMine.done,
				"more than 18 residents disperse while the active mine section remains unchanged"); checks++;

			BlockPos vein = origin.east();
			level.setBlock(vein, Blocks.STONE.defaultBlockState(), 3);
			check(!Mining.breakBlock(context, vein, new ItemStack(Items.IRON_SHOVEL))
				&& level.getBlockState(vein).is(Blocks.STONE), "non-pickaxe cannot remove mine stone"); checks++;

			worker.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			for (int i = 1; i < worker.getInventory().getContainerSize(); i++) {
				worker.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			}
			check(!Mining.breakBlock(context, vein, worker.getInventory().getItem(0))
				&& level.getBlockState(vein).is(Blocks.STONE), "full inventory leaves the mine face and its drops intact"); checks++;

			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			check(Mining.breakBlock(context, vein, worker.getInventory().getItem(0))
				&& level.getBlockState(vein).isAir(), "real pickaxe removes the natural stone block"); checks++;
			check(JobUtil.count(worker.getInventory(), stack -> stack.is(Items.COBBLESTONE)) == 1,
				"mined stone yields exactly its real vanilla pickaxe drop"); checks++;

			level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3);
			VillageData.Mine capacityMine = new VillageData.Mine(origin, 0, 0, origin.getY(), false);
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			for (int i = 1; i < worker.getInventory().getContainerSize(); i++) {
				worker.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			}
			List<ItemStack> blockedDrops = Mining.capacityBlockedSectionDrops(context, capacityMine, worker.getInventory().getItem(0));
			check(!blockedDrops.isEmpty() && level.getBlockState(origin).is(Blocks.STONE),
				"a full miner detects the current saved section without breaking its real drop source"); checks++;
			check(Mining.matchesMiningOutput(new ItemStack(Items.COBBLESTONE), blockedDrops)
				&& Mining.matchesMiningOutput(new ItemStack(Items.GRAVEL), blockedDrops)
				&& !Mining.matchesMiningOutput(new ItemStack(Items.OAK_FENCE), blockedDrops)
				&& !Mining.matchesMiningOutput(new ItemStack(Items.TORCH), blockedDrops)
				&& !Mining.matchesMiningOutput(new ItemStack(Items.IRON_PICKAXE), blockedDrops),
				"capacity haul accepts mine outputs but retains supports, lights, and tools"); checks++;
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			check(Mining.capacityBlockedSectionDrops(context, capacityMine, worker.getInventory().getItem(0)).isEmpty(),
				"available inventory capacity keeps the miner in the saved section"); checks++;

			check(Mining.cells(capacityMine, 0) == 3 && Mining.supportSection(2) && !Mining.supportSection(3)
				&& Mining.lightSection(3) && Mining.lightSection(9) && !Mining.lightSection(4) && !Mining.lightSection(5),
				"mine profile keeps three-block clearance with fixed support and light spacing"); checks++;
			VillageData.Mine supportMine = new VillageData.Mine(origin, 0, 2, origin.getY(), false);
			BlockPos supportBase = origin.relative(Direction.SOUTH, 2);
			worker.getInventory().setItem(1, new ItemStack(Items.OAK_FENCE, 6));
			// Three paid walkway pavers precede the five-block beam in shape().
			worker.getInventory().setItem(2, new ItemStack(Items.OAK_PLANKS, 8));
			Mining.shape(context, supportMine, supportBase, Mining.cells(supportMine, supportMine.index));
			boolean supportFrame = true;
			for (int sign : new int[]{-1, 1}) {
				BlockPos post = supportBase.relative(Direction.WEST, sign * 2);
				for (int y = 0; y < 3; y++) supportFrame &= level.getBlockState(post.above(y)).is(Blocks.OAK_FENCE);
			}
			for (int lane = -2; lane <= 2; lane++) {
				supportFrame &= level.getBlockState(supportBase.relative(Direction.WEST, lane).above(3)).is(Blocks.OAK_PLANKS);
			}
			boolean supportWalkway = level.getBlockState(supportBase.below()).is(Blocks.OAK_PLANKS);
			for (int sign : new int[]{-1, 1}) {
				supportWalkway &= level.getBlockState(supportBase.relative(Direction.WEST, sign * 2).below()).is(Blocks.OAK_PLANKS);
			}
			List<String> supportStates = new ArrayList<>();
			for (int sign : new int[]{-1, 1}) {
				BlockPos post = supportBase.relative(Direction.WEST, sign * 2);
				for (int y = 0; y < 3; y++) supportStates.add(post.above(y).toShortString() + "=" + level.getBlockState(post.above(y)));
			}
			for (int lane = -2; lane <= 2; lane++) {
				BlockPos beam = supportBase.relative(Direction.WEST, lane).above(3);
				supportStates.add(beam.toShortString() + "=" + level.getBlockState(beam));
			}
			check(supportFrame && supportWalkway && level.getBlockState(supportBase).isAir()
				&& level.getBlockState(supportBase.above()).isAir() && level.getBlockState(supportBase.above(2)).isAir()
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.OAK_FENCE)) == 0
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.OAK_PLANKS)) == 0,
				"visible three-wide support frame consumes real fence and plank stock without narrowing the walking lane"
					+ " [base=" + supportBase.toShortString() + ", frame=" + supportStates + ", walkway=" + supportWalkway
					+ ", floor=" + level.getBlockState(supportBase.below())
					+ ", fencesLeft=" + JobUtil.count(worker.getInventory(), stack -> stack.is(Items.OAK_FENCE))
					+ ", planksLeft=" + JobUtil.count(worker.getInventory(), stack -> stack.is(Items.OAK_PLANKS)) + "]"); checks++;
			VillageData.Mine lightMine = new VillageData.Mine(origin, 0, 3, origin.getY(), false);
			BlockPos lightBase = origin.relative(Direction.SOUTH, 3);
			Direction lightSide = Direction.from2DDataValue(lightMine.dir).getClockWise();
			int lightSign = Math.floorMod(lightMine.index / 8, 2) == 0 ? -1 : 1;
			BlockPos torch = lightBase.relative(lightSide, lightSign * 2);
			BlockPos torchWall = torch.relative(lightSide, lightSign);
			Direction torchFacing = lightSign > 0 ? lightSide.getOpposite() : lightSide;
			level.setBlock(torchWall, Blocks.STONE.defaultBlockState(), 3);
			worker.getInventory().setItem(3, new ItemStack(Items.TORCH));
			Mining.shape(context, lightMine, lightBase, Mining.cells(lightMine, lightMine.index));
			check(level.getBlockState(torch).is(Blocks.WALL_TORCH)
				&& level.getBlockState(torch).getValue(net.minecraft.world.level.block.WallTorchBlock.FACING) == torchFacing
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.TORCH)) == 0,
				"spaced wall light uses its supported mount, faces the gallery, and consumes the miner's real torch stock"
					+ " [dir=" + lightMine.dir + ", side=" + lightSide + ", target=" + torch.toShortString() + "=" + level.getBlockState(torch)
					+ ", mount=" + torchWall.toShortString() + "=" + level.getBlockState(torchWall)
					+ ", expectedFacing=" + torchFacing + ", torchesLeft="
					+ JobUtil.count(worker.getInventory(), stack -> stack.is(Items.TORCH)) + "]"); checks++;

			VillageData plan = new VillageData();
			plan.planType = "granary";
			plan.planOrigin = Optional.of(origin);
			plan.wanted.add("minecraft:oak_planks#4");
			SimpleContainer stock = new SimpleContainer(8);
			check(!Mining.needed(level, null, plan, stock, Mining.Need.STONE),
				"an unrelated active plan shortage does not authorize cobblestone mining"); checks++;
			plan.wanted.clear();
			plan.wanted.add("minecraft:stone_bricks#4");
			check(Mining.needed(level, null, plan, stock, Mining.Need.STONE),
				"a loaded-recipe stone-brick shortage authorizes only the required mine input"); checks++;
			return checks;
		} finally {
			for (Villager queued : queueWorkers) queued.discard();
			if (worker != null) worker.discard();
			before.forEach((pos, state) -> level.setBlock(pos, state, 2));
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("MINING CYCLE CHECK FAILED: " + message);
	}
}
