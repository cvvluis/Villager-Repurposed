package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Builder;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic checks for the walk-back rule of tree selection (P4c): a tree whose stand cells have no walk-back to the village
 * street (pit, plateau without a ramp, fence ring) is rejected and remembered, the nearest SAFE tree is chosen instead, a saved
 * cursor on a trap tree is dropped before the first cut, far trees (beyond the direct search reach) are still judged, and the
 * memory is bounded and expires. The terrain is one flat grass platform with hand-built traps; every block is restored afterwards.
 * Fixtures are synthetic: green results are no proof of natural behaviour.
 */
public final class TreeWalkbackChecks {
	private TreeWalkbackChecks() { }

	private static final int FLAGS = 2;
	private static final long T0 = 700_000L;

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager worker = null;
		int checks = 0;
		Woodcutting.resetWalkBackMemory();
		try {
			for (int cx = -6; cx <= 84; cx += 16) for (int cz = -28; cz <= 28; cz += 16) level.getChunk(origin.offset(cx, 0, cz));
			level.getChunk(origin.offset(84, 0, 28));
			for (BlockPos p : BlockPos.betweenClosed(origin.offset(-6, -3, -28), origin.offset(84, 14, 28))) before.put(p.immutable(), level.getBlockState(p));
			for (int dx = -6; dx <= 84; dx++) for (int dz = -28; dz <= 28; dz++) {
				for (int dy = -3; dy <= 14; dy++) level.setBlock(origin.offset(dx, dy, dz), (dy < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), FLAGS);
				level.setBlock(origin.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
			}
			VillageRef ref = Checks090.fixtureForEquipment(level, origin);
			BlockPos street = Builder.villageReturn(level, ref.bell());
			require(street != null, "the fixture village has a street cell"); checks++;

			BlockPos open = origin.offset(20, 0, 0);
			BlockPos pit = origin.offset(32, 0, 12);
			BlockPos plateau = origin.offset(26, 6, -12);
			BlockPos ramped = origin.offset(50, 6, -12);
			BlockPos ring = origin.offset(46, 0, 12);
			BlockPos farOpen = origin.offset(76, 0, 0);
			BlockPos farPit = origin.offset(70, 0, -16);
			tree(level, open, Blocks.OAK_LOG); tree(level, pit, Blocks.OAK_LOG);
			tree(level, plateau, Blocks.SPRUCE_LOG); tree(level, ramped, Blocks.SPRUCE_LOG); tree(level, ring, Blocks.SPRUCE_LOG);
			tree(level, farOpen, Blocks.SPRUCE_LOG); tree(level, farPit, Blocks.SPRUCE_LOG);
			// Pit: the trunk stands inside a 7x7 room with four-block walls (the way out is a four-block climb).
			walls(level, pit, 4, 4, 3);
			walls(level, farPit, 4, 4, 3);
			// Plateau: a six-block stone block with the tree on top and no ramp; the second one gets a staircase of one-block steps to the west.
			plateau(level, plateau, false);
			plateau(level, ramped, true);
			// Fence ring: trunk inside a fence ring (the pavilion lesson: a fence is neither walked through nor climbed).
			for (int d = -3; d <= 3; d++) for (int e = -3; e <= 3; e++) {
				if (Math.max(Math.abs(d), Math.abs(e)) == 3) level.setBlock(ring.offset(d, 0, e), Blocks.SPRUCE_FENCE.defaultBlockState(), FLAGS);
			}

			long now = T0;
			require(Woodcutting.isHarvestableTree(level, open) && Woodcutting.isHarvestableTree(level, pit),
				"the fixture trees are harvestable natural trees"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, open, now) == Woodcutting.WALKBACK_SAFE, "a tree on open flat ground has a walk-back"); checks++;
			var stands = Woodcutting.treeStands(level, open, street);
			require(stands.size() == 3, "the three stand cells nearest the street are examined"); checks++;
			for (BlockPos stand : stands) {
				require(level.getBlockState(stand.below()).isSolid() && level.getBlockState(stand).getCollisionShape(level, stand).isEmpty(),
					"a stand cell is a clear cell on solid ground"); checks++;
			}
			require(Woodcutting.treeStands(level, ring, street).stream().noneMatch(stand -> level.getBlockState(stand.below()).is(Blocks.SPRUCE_FENCE)),
				"a fence top is never a stand cell"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, pit, now) == Woodcutting.WALKBACK_UNSAFE, "a trunk inside a walled pit has no walk-back"); checks++;
			require(Woodcutting.walkBackRemembered(pit, now + 10L), "the pit tree is remembered as a trap"); checks++;
			require(!Woodcutting.walkBackRemembered(open, now + 10L), "the safe tree is not remembered as a trap"); checks++;
			// A remembered trap is rejected from memory: no search budget needed.
			Woodcutting.startWalkBackSelection();
			Woodcutting.walkBackSpendBudgetForTest();
			require(Woodcutting.walkBack(level, street, pit, now + 100L) == Woodcutting.WALKBACK_UNSAFE, "a remembered trap tree is rejected again from memory"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, plateau, now) == Woodcutting.WALKBACK_UNSAFE, "a trunk on a six-block plateau without a ramp has no walk-back"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, ramped, now) == Woodcutting.WALKBACK_SAFE, "a plateau with a one-block staircase down has a walk-back"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, ring, now) == Woodcutting.WALKBACK_UNSAFE, "a trunk inside a fence ring has no walk-back"); checks++;

			// Wood expeditions: a tree far beyond the direct search reach is judged against a nearer goal, not rejected on distance.
			require(farOpen.distSqr(street) > (double) Woodcutting.WALKBACK_NEAR * Woodcutting.WALKBACK_NEAR, "the far tree lies beyond the direct search reach"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, farOpen, now) == Woodcutting.WALKBACK_SAFE, "a far tree on open ground (wood expedition) stays acceptable"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, farPit, now) == Woodcutting.WALKBACK_UNSAFE, "a far trunk inside a walled pit is still rejected"); checks++;
			require(Woodcutting.EXPEDITION_LIMIT == Woodcutting.EXTENDED_PLAN_RADIUS + 48, "the expedition ceiling is unchanged"); checks++;

			// Budget: once the per-call searches are spent, a tree not judged yet is deferred (not remembered) and judged on the next call.
			Woodcutting.resetWalkBackMemory();
			Woodcutting.startWalkBackSelection();
			Woodcutting.walkBackSpendBudgetForTest();
			require(Woodcutting.walkBack(level, street, farOpen, now) == Woodcutting.WALKBACK_DEFERRED && !Woodcutting.walkBackRemembered(farOpen, now),
				"with the per-call searches spent an unjudged tree is deferred and not remembered"); checks++;
			Woodcutting.startWalkBackSelection();
			require(Woodcutting.walkBack(level, street, farOpen, now) == Woodcutting.WALKBACK_SAFE, "the deferred tree is judged on the next call"); checks++;

			// Expiry and bound.
			Woodcutting.resetWalkBackMemory();
			Woodcutting.startWalkBackSelection();
			Woodcutting.walkBack(level, street, pit, now);
			require(Woodcutting.walkBackRemembered(pit, now + Woodcutting.WALKBACK_BAD_TICKS - 1), "a trap verdict is kept for a day"); checks++;
			require(!Woodcutting.walkBackRemembered(pit, now + Woodcutting.WALKBACK_BAD_TICKS), "a trap verdict expires"); checks++;
			Woodcutting.resetWalkBackMemory();
			for (int i = 0; i < 700; i++) Woodcutting.walkBack(level, street, origin.offset(10 + i % 60, 12, -20 + i / 60), now + i); // roots in the air: judged without a search
			require(Woodcutting.walkBackMemorySize() <= Woodcutting.WALKBACK_MEMORY_LIMIT && Woodcutting.walkBackMemorySize() > 0,
				"the remembered verdicts stay bounded (size " + Woodcutting.walkBackMemorySize() + ")"); checks++;
			Woodcutting.resetWalkBackMemory();

			// Selection: the plan's wood chooser skips the nearer trap tree and offers the farther safe one.
			worker = EntityTypes.VILLAGER.spawn(level, pit.offset(-6, 0, 0), EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("TREE WALKBACK CHECK FAILED: could not spawn worker fixture");
			worker.addTag("vp090_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			Set<String> oak = Set.of("minecraft:oak_log");
			require(pit.distSqr(worker.blockPosition()) < open.distSqr(worker.blockPosition()), "the trap tree is the nearer oak"); checks++;
			BlockPos chosen = Builder.selectPlanWoodTree(level, ref, worker, oak);
			require(open.equals(chosen), "plan tree selection skips the nearer trap tree for the farther safe one (chose " + chosen + ")"); checks++;
			require(Woodcutting.walkBackRemembered(pit, level.getGameTime()), "the skipped trap tree is remembered"); checks++;

			// A saved cursor on the trap tree is dropped before the first cut; a cursor on the safe tree stays.
			ref.data().planType = "tree-walkback-fixture";
			ref.data().planOrigin = java.util.Optional.of(origin);
			ref.data().wanted.clear();
			ref.data().wanted.add("minecraft:oak_log#4");
			ref.dirty();
			JobState state = JobManager.state(worker);
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			state.woodCheckAt = 0L;
			worker.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(pit, "minecraft:oak"));
			Woodcutting.resetWalkBackMemory();
			Woodcutting.tick(new Ctx(level, worker, state, memory, ref, level.getGameTime() + 5_000L));
			require(worker.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty()).root().isEmpty() && Woodcutting.walkBackRemembered(pit, level.getGameTime()),
				"a saved cursor on a walk-back trap tree is dropped and the tree remembered"); checks++;
			worker.teleportTo(open.getX() - 3 + 0.5, open.getY(), open.getZ() + 0.5);
			state.woodCheckAt = 0L;
			worker.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(open, "minecraft:oak"));
			Woodcutting.tick(new Ctx(level, worker, state, memory, ref, level.getGameTime() + 6_000L));
			require(worker.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty()).root().filter(open::equals).isPresent(),
				"a saved cursor on a safe tree is kept"); checks++;

			// A stranded worker (walled in) must not poison the tree memory: his failed route is not the tree's fault.
			Villager trapped = EntityTypes.VILLAGER.spawn(level, pit.west(), EntitySpawnReason.COMMAND);
			if (trapped == null) throw new IllegalStateException("TREE WALKBACK CHECK FAILED: could not spawn trapped worker fixture");
			trapped.addTag("vp090_fixture");
			trapped.setNoAi(true);
			trapped.setInvulnerable(true);
			try {
				JobState trappedState = JobManager.state(trapped);
				VillagerMemory trappedMemory = trapped.getAttachedOrCreate(VillagerPlus.MEMORY);
				long t = level.getGameTime() + 8_000L;
				require(Woodcutting.workerStranded(new Ctx(level, trapped, trappedState, trappedMemory, ref, t)), "a worker walled into the pit has no long route out"); checks++;
				require(!Woodcutting.workerStranded(new Ctx(level, worker, state, memory, ref, t)), "a worker on open ground is not stranded"); checks++;
				trappedState.woodCheckAt = 0L;
				trapped.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(open, "minecraft:oak"));
				for (int i = 0; i < 3; i++) {
					trappedState.cooldownUntil = 0L;
					trappedState.woodCheckAt = 0L;
					Woodcutting.tick(new Ctx(level, trapped, trappedState, trappedMemory, ref, t + i * 50L));
				}
				require(!Woodcutting.treeUnreachable(trapped.getUUID(), open, t + 200L)
					&& trapped.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty()).root().filter(open::equals).isPresent(),
					"a stranded worker's failed route neither marks the safe tree unreachable nor drops it"); checks++;
			} finally {
				trapped.discard();
			}
			return checks;
		} finally {
			Woodcutting.resetWalkBackMemory();
			if (worker != null) worker.discard();
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, FLAGS));
		}
	}

	private static void tree(ServerLevel level, BlockPos root, Block log) {
		level.setBlock(root.below(), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
		for (int dy = 0; dy < 5; dy++) level.setBlock(root.above(dy), log.defaultBlockState(), FLAGS);
		Block leaves = log == Blocks.OAK_LOG ? Blocks.OAK_LEAVES : Blocks.SPRUCE_LEAVES;
		for (int dy = 3; dy <= 6; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			if (dx == 0 && dz == 0 && dy <= 4) continue;
			if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 5) <= 4) level.setBlock(root.offset(dx, dy, dz), leaves.defaultBlockState(), FLAGS);
		}
	}

	/** Stone ring (thickness one) around {@code center}: cells beyond {@code innerHalf} within {@code outerHalf}, {@code height} blocks up from the floor level. */
	private static void walls(ServerLevel level, BlockPos center, int outerHalf, int height, int innerHalf) {
		for (int d = -outerHalf; d <= outerHalf; d++) for (int e = -outerHalf; e <= outerHalf; e++) {
			if (Math.max(Math.abs(d), Math.abs(e)) <= innerHalf) continue;
			for (int dy = 0; dy < height; dy++) level.setBlock(center.offset(d, dy, e), Blocks.STONE.defaultBlockState(), FLAGS);
		}
	}

	/** Six-block stone plateau under {@code root} (grass top just below it); with a ramp it steps down one block at a time to the west. */
	private static void plateau(ServerLevel level, BlockPos root, boolean ramp) {
		int top = root.getY() - 1;
		int floor = root.getY() - 7;
		for (int d = -5; d <= 5; d++) for (int e = -5; e <= 5; e++) {
			for (int y = floor + 1; y < top; y++) level.setBlock(new BlockPos(root.getX() + d, y, root.getZ() + e), Blocks.STONE.defaultBlockState(), FLAGS);
			level.setBlock(new BlockPos(root.getX() + d, top, root.getZ() + e), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
		}
		if (!ramp) return;
		for (int step = 1; step <= 5; step++) for (int e = -1; e <= 1; e++) {
			for (int y = floor + 1; y < top - step; y++) level.setBlock(new BlockPos(root.getX() - 5 - step, y, root.getZ() + e), Blocks.STONE.defaultBlockState(), FLAGS);
			level.setBlock(new BlockPos(root.getX() - 5 - step, top - step, root.getZ() + e), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
		}
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("TREE WALKBACK CHECK FAILED: " + label);
	}
}
