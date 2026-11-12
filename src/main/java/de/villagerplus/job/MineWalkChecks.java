package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Synthetic checks for the mine-walk stall (P4b): (1) a resident that still carries the saved mine route tag while it walks over
 * open ground at entrance height must not be frozen by the movement guard; the route confinement stays active inside the gallery;
 * (2) a resident standing on a fence post (vanilla navigation cannot start a path there) is set down on the floor beside it.
 * Everything is restored afterwards. Fixtures are synthetic: green results are no proof of natural behaviour.
 */
public final class MineWalkChecks {
	private MineWalkChecks() { }

	/** Vertical part of a standing walker's pre-collision move vector (gravity after a cancelled fall). */
	private static final double GRAVITY_NUDGE = -0.0784;

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager worker = null;
		int checks = 0;
		JobUtil.clearLongRoutes();
		try {
			level.getChunk(origin);
			level.getChunk(origin.offset(40, 0, 0));
			BlockState air = Blocks.AIR.defaultBlockState();
			for (int dx = -6; dx <= 44; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = -2; dy <= 6; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.setBlock(p, dy < 0 ? (dy == -1 ? Blocks.STONE.defaultBlockState() : air) : air, 3);
			}
			BlockPos entrance = origin;
			VillageData.Mine mine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 4, entrance.getY() - 3, false);
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("MINE WALK CHECK FAILED: cannot create worker fixture");
			worker.setPos(origin.getX() + 20.5, origin.getY(), origin.getZ() + 0.5);
			worker.setOnGround(true);
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			if (!level.addFreshEntity(worker)) throw new IllegalStateException("MINE WALK CHECK FAILED: cannot add worker fixture");
			Mining.rememberMineRoute(worker, level, mine, 0);
			require(Mining.taggedMine(worker, level) != null, "the walker carries the saved mine route tag"); checks++;

			// A: the stall. Surface walker at entrance height, 17 blocks beyond the end of the gallery, tagged.
			Vec3 stand = worker.position();
			Vec3 step = new Vec3(0.1, GRAVITY_NUDGE, 0.0);
			require(stand.y + GRAVITY_NUDGE < entrance.getY() - 0.05,
				"the pre-collision destination of a standing walker is below the old entrance-height threshold"); checks++;
			require(!Mining.mineMovementAllowed(mine, stand, step, 0.6, 1.95),
				"the pure route test alone rejects that step (this is what froze the resident)"); checks++;
			require(Mining.allowMineMovement(worker, step), "a tagged surface walker at entrance height may take its step"); checks++;
			require(Mining.allowMineMovement(worker, new Vec3(0.0, GRAVITY_NUDGE, -0.1)), "also sideways"); checks++;
			double startX = worker.getX();
			worker.move(MoverType.SELF, step);
			require(worker.getX() > startX + 0.05, "a real move() call keeps its horizontal part (x " + startX + " -> " + worker.getX() + ")"); checks++;
			worker.setPos(origin.getX() + 20.5, origin.getY(), origin.getZ() + 0.5);
			worker.setOnGround(true);
			// Lowland far from the gallery (a hill entrance): not confined either.
			worker.setPos(origin.getX() + 20.5, origin.getY() - 1.0, origin.getZ() + 0.5);
			require(Mining.allowMineMovement(worker, step), "lowland far from the gallery is free"); checks++;
			// The portal cell itself and the surface right next to it.
			worker.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
			require(Mining.allowMineMovement(worker, step), "the portal cell at entrance height is free"); checks++;

			// B: confinement inside the gallery stays: cell 2 lies two blocks down and two blocks in.
			worker.setPos(origin.getX() + 2.5, origin.getY() - 2.0, origin.getZ() + 0.5);
			require(Mining.allowMineMovement(worker, new Vec3(0.08, 0.0, 0.0)), "walking along the gallery is allowed"); checks++;
			require(!Mining.allowMineMovement(worker, new Vec3(0.0, 0.0, 2.0)), "leaving the gallery sideways is still cancelled"); checks++;
			require(!Mining.allowMineMovement(worker, new Vec3(-2.0, 0.0, 0.0)), "leaving the gallery through its end is still cancelled"); checks++;
			require(!Mining.allowMineMovement(worker, new Vec3(0.0, -0.25, 0.0)), "digging down out of the route is still cancelled"); checks++;
			// A walker next to the gallery (3 blocks beside cell 2, inside the recovery radius) is still confined.
			worker.setPos(origin.getX() + 2.5, origin.getY() - 2.0, origin.getZ() + 3.5);
			require(!Mining.allowMineMovement(worker, new Vec3(0.0, 0.0, 0.5)), "a walker beside the gallery may not move away from it"); checks++;

			// B2: terraced floor. In the upper half of cell 3 (along 2.8) the real floor is the cell's terrace at entrance y - 3, which the
			// old sloped model put 0.2 below its allowed band: the walker hovered above it, frozen, with the gravity step cancelled.
			worker.setPos(origin.getX() + 3.3, origin.getY() - 3.0 + 0.086, origin.getZ() + 0.5);
			require(Mining.allowMineMovement(worker, new Vec3(0.0, GRAVITY_NUDGE, 0.0)), "a walker may land on the terrace in the upper half of its cell"); checks++;
			worker.setPos(origin.getX() + 2.45, origin.getY() - 2.0, origin.getZ() + 0.5);
			require(Mining.allowMineMovement(worker, new Vec3(0.12, GRAVITY_NUDGE, 0.0)), "a walker stepping off the edge of a terrace may start to fall"); checks++;
			worker.setPos(origin.getX() + 3.3, origin.getY() - 3.0, origin.getZ() + 0.5);
			require(!Mining.allowMineMovement(worker, new Vec3(0.0, -0.25, 0.0)), "digging down below the terrace floor is still cancelled"); checks++;

			// C: perch. A fence post (collision height 1.5) next to a barrel, a villager standing on the post.
			BlockPos post = origin.offset(30, 0, 0);
			level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
			level.setBlock(post.east(), Blocks.BARREL.defaultBlockState(), 3);
			worker.setPos(post.getX() + 0.5, post.getY() + 1.5, post.getZ() + 0.5);
			worker.setOnGround(true);
			Vec3 away = JobUtil.perchStepOff(level, worker);
			require(away != null, "a villager standing on a fence post is perched"); checks++;
			require(away.y <= origin.getY() + 0.01 && Math.max(Math.abs(away.x - worker.getX()), Math.abs(away.z - worker.getZ())) <= 2.0,
				"the target is the floor next to the post, not the barrel top (" + away + ")"); checks++;
			worker.setPos(post.east().getX() + 0.5, post.getY() + 1.0, post.getZ() + 0.5);
			require(JobUtil.perchStepOff(level, worker) == null, "standing on the barrel top (collision height 1) is not a perch"); checks++;
			worker.setPos(post.getX() + 5.5, origin.getY(), post.getZ() + 0.5);
			require(JobUtil.perchStepOff(level, worker) == null, "standing on the floor is not a perch"); checks++;
			// A wall around the post one block out (feet and head level): the stand two blocks away is behind it and not used.
			worker.setPos(post.getX() + 0.5, post.getY() + 1.5, post.getZ() + 0.5);
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				if (dx == 1 && dz == 0) continue; // the barrel stands there already
				level.setBlock(post.offset(dx, 1, dz), stone, 3);
				level.setBlock(post.offset(dx, 2, dz), stone, 3);
			}
			level.setBlock(post.east().above(), stone, 3);
			level.setBlock(post.east().above(2), stone, 3);
			require(JobUtil.perchStepOff(level, worker) == null, "a perch walled in on every side is left alone"); checks++;
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				level.setBlock(post.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(post.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), 3);
			}
			// Water beside the post is no landing: only the dry side is used.
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlock(post.west().offset(0, -1, dz), Blocks.WATER.defaultBlockState(), 3);
				level.setBlock(post.west(2).offset(0, -1, dz), Blocks.WATER.defaultBlockState(), 3);
			}
			Vec3 dry = JobUtil.perchStepOff(level, worker);
			require(dry != null && dry.x >= worker.getX() - 0.5, "no landing in water: the target is not on the water side (" + dry + ")"); checks++;
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlock(post.west().offset(0, -1, dz), stone, 3);
				level.setBlock(post.west(2).offset(0, -1, dz), stone, 3);
			}
			// The action: rate limited, bounded move, no longer perched afterwards.
			JobState state = new JobState();
			var memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			long now = 700_000L;
			Vec3 before0 = worker.position();
			boolean pushed = JobUtil.stepOffPerch(new Ctx(level, worker, state, memory, null, now));
			double moved = worker.position().distanceTo(before0);
			require(pushed && moved > 0.5 && moved <= 3.5 && JobUtil.perchStepOff(level, worker) == null
				&& level.noCollision(worker, worker.getBoundingBox()),
				"the villager is set down within three blocks on a free floor (moved " + moved + ")"); checks++;
			worker.setPos(post.getX() + 0.5, post.getY() + 1.5, post.getZ() + 0.5);
			worker.setOnGround(true);
			require(!JobUtil.stepOffPerch(new Ctx(level, worker, state, memory, null, now + JobUtil.PERCH_NUDGE_SPACING - 1)),
				"the action is rate limited"); checks++;
			require(!JobUtil.stepOffPerch(new Ctx(level, worker, state, memory, null, now + 2 * JobUtil.PERCH_NUDGE_SPACING - 1)),
				"a villager that climbs back up right away is moved less often (spacing doubles)"); checks++;
			require(JobUtil.stepOffPerch(new Ctx(level, worker, state, memory, null, now + 2 * JobUtil.PERCH_NUDGE_SPACING)),
				"and is moved again after the doubled spacing"); checks++;
			require(!JobUtil.stepOffPerch(new Ctx(level, worker, state, memory, null, now + 10 * JobUtil.PERCH_NUDGE_SPACING)),
				"a villager that stands on a normal floor is never moved"); checks++;
			return checks;
		} finally {
			JobUtil.clearLongRoutes();
			if (worker != null) {
				worker.removeAttached(VillagerPlus.MINING_ROUTE);
				worker.discard();
			}
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
		}
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("MINE WALK CHECK FAILED: " + label);
	}
}
