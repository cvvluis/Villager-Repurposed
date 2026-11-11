package de.villagerplus.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Direct world checks for target selection, camp protection, return-to-post, and cancellation. */
public final class CaravanCampSecurityChecks {
	private CaravanCampSecurityChecks() { }

	public static int runWorld(ServerLevel level, BlockPos at) {
		int checks = 0;
		BlockPos campCenter = at.immutable();
		List<BlockPos> campCells = List.of(campCenter.offset(-3, 0, -2), campCenter.offset(3, 0, 2),
			campCenter.offset(0, 3, 0), campCenter.offset(-1, 0, 0));
		BlockPos post = CaravanCampSecurity.post(campCells);
		if (post == null) throw new IllegalStateException("Caravan camp security fixture could not derive its exterior post");
		BlockPos guardFeet = post.east(8);
		BlockPos hostileFeet = post.west(8);
		BlockPos outsideFeet = post.north(13);
		Map<BlockPos, BlockState> floorBefore = new LinkedHashMap<>();
		List<BlockPos> pathClearance = new ArrayList<>();
		List<Entity> spawned = new ArrayList<>();
		for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) {
			BlockPos floor = post.offset(dx, -1, dz);
			if (!level.hasChunkAt(floor)) level.getChunkAt(floor);
			floorBefore.put(floor, level.getBlockState(floor));
		}
		// Isolate navigation from naturally generated terrain: a golem is almost three
		// blocks tall, so its direct route corridor needs real headroom as well as a floor.
		for (int dx = -10; dx <= 10; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = 0; dy <= 2; dy++) {
			BlockPos clear = post.offset(dx, dy, dz);
			if (!level.hasChunkAt(clear)) level.getChunkAt(clear);
			floorBefore.putIfAbsent(clear, level.getBlockState(clear));
			pathClearance.add(clear);
		}
		try {
			for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++)
				level.setBlock(post.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
			for (BlockPos clear : pathClearance) level.setBlock(clear, Blocks.AIR.defaultBlockState(), 3);
			check(CaravanCampSecurity.safePost(level, campCenter, campCells),
				"the exterior guard post has supported clear space for both combat security and its visible guard"); checks++;
			UUID visualOwner = UUID.randomUUID();
			var armedVisual = CaravanCampSecurity.ensureArmedGuard(level, visualOwner, campCells);
			BlockPos visualSpot = CaravanSites.armedGuardSpot(post);
			check(armedVisual != null && !armedVisual.isRemoved()
				&& armedVisual.blockPosition().equals(visualSpot)
				&& armedVisual.getMainHandItem().is(Items.IRON_SWORD)
				&& !armedVisual.isNoAi() && armedVisual.isPersistenceRequired()
				&& !armedVisual.isNoGravity() && armedVisual.isInvulnerable(),
				"the armed guard holds a real iron sword and remains a persistent, navigable entity at the validated exterior post"); checks++;
			check(de.villagerplus.job.JobManager.stateIfKnown(armedVisual) == null,
				"a camp guard is excluded from worker state and therefore cannot receive a job route"); checks++;
			var repeatedVisual = CaravanCampSecurity.ensureArmedGuard(level, visualOwner, campCells);
			check(repeatedVisual == armedVisual && countArmedGuards(level, visualOwner) == 1,
				"reconciling the same persisted caravan owner reuses one armed guard instead of duplicating it"); checks++;
			BlockPos displacedSpot = visualSpot.east(3);
			armedVisual.setPos(displacedSpot.getX() + 0.5, displacedSpot.getY(), displacedSpot.getZ() + 0.5);
			check(CaravanCampSecurity.ensureArmedGuard(level, visualOwner, campCells) == armedVisual
				&& armedVisual.blockPosition().equals(displacedSpot),
				"periodic owner reconciliation keeps a displaced guard in place instead of teleporting it out of combat"); checks++;
			check(CaravanCampSecurity.removeArmedGuard(level, visualOwner) == 1
				&& armedVisual.isRemoved() && countArmedGuards(level, visualOwner) == 0,
				"camp removal discards the owner-bound armed guard and leaves no loaded duplicate"); checks++;
			var restoredOrphan = CaravanCampSecurity.ensureArmedGuard(level, visualOwner, campCells);
			Caravan.reconcileArmedGuardVisuals(level);
			check(restoredOrphan != null && restoredOrphan.isRemoved() && countArmedGuards(level, visualOwner) == 0,
				"a persisted guard visual is discarded after reload when its transient caravan owner no longer exists"); checks++;
			check(CaravanCampSecurity.followsSameRoute(post, post, true)
				&& !CaravanCampSecurity.followsSameRoute(post, post, false)
				&& !CaravanCampSecurity.followsSameRoute(post, post.north(), true),
				"an active route to the same post is retained; completed or changed routes remain refreshable"); checks++;
			IronGolem guard = spawn(level, EntityTypes.IRON_GOLEM, guardFeet, spawned);
			Zombie inside = spawn(level, EntityTypes.ZOMBIE, hostileFeet, spawned);
			Zombie outside = spawn(level, EntityTypes.ZOMBIE, outsideFeet, spawned);
			Cow cow = spawn(level, EntityTypes.COW, post.east(1), spawned);
			WanderingTrader trader = spawn(level, EntityTypes.WANDERING_TRADER, post.south(1), spawned);
			Donkey donkey = spawn(level, EntityTypes.DONKEY, post.west(1), spawned);

			check(armedVisual.isRemoved() && countArmedGuards(level, visualOwner) == 0,
				"removing a caravan camp leaves no active armed-guard entity"); checks++;
			UUID combatOwner = UUID.randomUUID();
			Villager armedCombat = CaravanCampSecurity.ensureArmedGuard(level, combatOwner, campCells);
			if (armedCombat == null) throw new IllegalStateException("Caravan armed guard fixture could not spawn its combat villager");
			spawned.add(armedCombat);
			armedCombat.setOnGround(true);
			armedCombat.setDeltaMovement(Vec3.ZERO);
			armedCombat.tickCount = 0;
			inside.setNoAi(true);
			inside.setDeltaMovement(Vec3.ZERO);
			BlockPos combatStart = armedCombat.blockPosition();
			float hostileHealthBefore = inside.getHealth();
			check(CaravanCampSecurity.defendArmedGuard(level, armedCombat, campCells, true)
				&& armedCombat.getTarget() == inside
				&& combatStart.equals(CaravanSites.armedGuardSpot(post))
				&& armedCombat.distanceToSqr(inside) > 16.0
				&& armedCombat.getNavigation().isInProgress()
				&& armedCombat.getNavigation().getPath() != null
				&& armedCombat.getNavigation().getPath().canReach(),
				"an armed guard at its exterior post starts a reachable vanilla path toward a hostile several blocks away"); checks++;
			boolean movedBeforeFirstHit = false;
			boolean firstHitFollowedMovement = false;
			for (int tick = 0; tick < 240 && inside.getHealth() >= hostileHealthBefore; tick++) {
				CaravanCampSecurity.defendArmedGuard(level, armedCombat, campCells, true);
				armedCombat.tick();
				if (armedCombat.distanceToSqr(Vec3.atBottomCenterOf(combatStart)) > 0.25) movedBeforeFirstHit = true;
				if (inside.getHealth() < hostileHealthBefore) {
					firstHitFollowedMovement = movedBeforeFirstHit;
					break;
				}
			}
			check(movedBeforeFirstHit,
				"normal Villager brain and navigation ticks physically move the armed guard away from its post while pursuing the hostile"); checks++;
			check(inside.getHealth() < hostileHealthBefore && firstHitFollowedMovement,
				"the armed guard lands a real sword hit only after its navigation has moved it toward the hostile"); checks++;
			check(CaravanCampSecurity.defend(level, guard, campCells, true) && guard.getTarget() == inside,
				"the active camp guard assigns an in-radius hostile to normal Iron Golem combat"); checks++;
			check(guard.getTarget() != cow && guard.getTarget() != trader && guard.getTarget() != donkey,
				"neutral animals, the caravan trader, and its donkey are never selected as camp threats"); checks++;

			inside.discard();
			check(!CaravanCampSecurity.defend(level, guard, campCells, true) && guard.getTarget() == null,
				"a hostile outside the safe camp radius is ignored after the local threat ends"); checks++;
			check(CaravanCampSecurity.defendArmedGuard(level, armedCombat, campCells, true)
				&& armedCombat.getTarget() == null
				&& CaravanCampSecurity.followsSameRoute(CaravanSites.armedGuardSpot(post),
					armedCombat.getNavigation().getTargetPos(), armedCombat.getNavigation().isInProgress()),
				"after its nearby hostile disappears, the armed guard starts a normal navigation route back to its post"); checks++;
			check(armedCombat.getTarget() != cow && armedCombat.getTarget() != trader
				&& armedCombat.getTarget() != donkey && armedCombat.getTarget() == null,
				"the armed guard ignores the nearby cow, trader, and donkey while an out-of-radius zombie remains"); checks++;
			BlockPos combatReturnStart = armedCombat.blockPosition();
			for (int tick = 0; tick < 240
				&& armedCombat.distanceToSqr(Vec3.atBottomCenterOf(CaravanSites.armedGuardSpot(post))) > 4.0; tick++) {
				CaravanCampSecurity.defendArmedGuard(level, armedCombat, campCells, true);
				if (tick % 20 == 0) CaravanCampSecurity.ensureArmedGuard(level, combatOwner, campCells);
				armedCombat.tick();
			}
			check(!armedCombat.blockPosition().equals(combatReturnStart)
				&& armedCombat.distanceToSqr(Vec3.atBottomCenterOf(CaravanSites.armedGuardSpot(post))) <= 4.0,
				"normal entity ticks return the armed guard to its exterior post without a teleport"); checks++;
			check(CaravanCampSecurity.removeArmedGuard(level, combatOwner) == 1 && armedCombat.isRemoved(),
				"camp teardown removes the active combat guard after its return"); checks++;
		outside.discard();
		cow.discard();
		trader.discard();
		donkey.discard();

		BlockPos returnStart = guard.blockPosition();
		check(guard.getTarget() == null,
			"the ended threat is cleared from the guard before its return starts"); checks++;
		check(level.hasChunkAt(guard.blockPosition()) && level.hasChunkAt(post),
			"the displaced guard and exterior post are in loaded chunks for the return"); checks++;
		check(CaravanSites.canStand(level, guard, post, List.of()),
			"the exterior post still has safe, supported space for the live guard"); checks++;
		guard.setOnGround(true);
		guard.setDeltaMovement(Vec3.ZERO);
		var vanillaReturnPath = guard.getNavigation().createPath(post, 0);
		check(vanillaReturnPath != null && vanillaReturnPath.canReach(),
			"vanilla navigation finds a reachable path from the displaced guard to the exterior post"
				+ " [guard=" + guard.blockPosition() + ", onGround=" + guard.onGround()
				+ ", path=" + (vanillaReturnPath == null ? "none" : "reachable=" + vanillaReturnPath.canReach()
					+ ", nodes=" + vanillaReturnPath.getNodeCount() + ", target=" + vanillaReturnPath.getTarget()) + "]"); checks++;
		check(guard.getNavigation().moveTo(vanillaReturnPath, 1.1),
			"vanilla navigation accepts its reachable path for the displaced guard"); checks++;
		guard.getNavigation().stop();
		check(CaravanCampSecurity.returnTo(level, guard, post),
			"after combat ends, normal Iron Golem navigation starts a return to the exterior post"); checks++;
		var returnPath = guard.getNavigation().getPath();
		check(returnPath != null && returnPath.canReach()
			&& CaravanCampSecurity.followsSameRoute(post, guard.getNavigation().getTargetPos(), guard.getNavigation().isInProgress())
			&& guard.blockPosition().equals(returnStart),
			"the return uses a reachable vanilla path to the post without teleporting the displaced guard"); checks++;
		check(CaravanCampSecurity.returnTo(level, guard, post) && guard.getNavigation().getPath() == returnPath,
			"repeated return checks retain the active path to the unchanged post"); checks++;
		for (int tick = 0; tick < 240 && guard.distanceToSqr(Vec3.atBottomCenterOf(post)) > 4.0; tick++) {
			if (tick % 20 == 0) CaravanCampSecurity.returnTo(level, guard, post);
			guard.tick();
		}
		check(guard.distanceToSqr(Vec3.atBottomCenterOf(post)) <= 4.0,
			"normal Iron Golem entity ticks physically bring the guard back to the exterior post"); checks++;

		guard.getNavigation().stop();
		guard.setPos(returnStart.getX() + 0.5, returnStart.getY(), returnStart.getZ() + 0.5);
		guard.setOnGround(true);
		level.setBlock(post.below(), Blocks.CACTUS.defaultBlockState(), 3);
		check(!CaravanCampSecurity.returnTo(level, guard, post) && !guard.getNavigation().isInProgress(),
			"a post made unsafe after combat is rejected and its previous route is stopped"); checks++;

			Zombie renewedThreat = spawn(level, EntityTypes.ZOMBIE, hostileFeet, spawned);
			check(CaravanCampSecurity.defend(level, guard, campCells, true) && guard.getTarget() == renewedThreat,
				"the guard resumes threat response when a new hostile enters the active camp radius"); checks++;
			CaravanCampSecurity.end(guard);
			check(guard.getTarget() == null,
				"camp teardown clears the guard target so no camp combat survives its owner"); checks++;

			guard.setTarget(renewedThreat);
			check(!CaravanCampSecurity.defend(level, guard, campCells, false) && guard.getTarget() == null,
				"ending camp duty cancels active targeting safely"); checks++;
			renewedThreat.discard();
			check(Caravan.guardTarget(campCells, true, false, guardFeet).equals(post),
				"the camp guard selects the validated exterior post; physical path execution remains a natural-world check"); checks++;

			BlockPos remote = new BlockPos(1_100_000, post.getY(), 1_100_000);
			check(!level.hasChunkAt(remote) && !CaravanCampSecurity.defend(level, guard, List.of(remote), true)
				&& !level.hasChunkAt(remote), "camp security never loads an unloaded camp chunk"); checks++;

			guard.discard();
			check(!CaravanCampSecurity.defend(level, guard, campCells, true),
				"a dead or removed guard cannot keep camp security active"); checks++;
		} finally {
			for (Entity entity : spawned) if (!entity.isRemoved()) entity.discard();
			for (var entry : floorBefore.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 3);
		}
		return checks;
	}

	private static <T extends Mob> T spawn(ServerLevel level, EntityType<T> type, BlockPos pos, List<Entity> spawned) {
		// Keep the guard on Minecraft's normal mob-spawn path so goals, spawn finalization,
		// and navigation are initialized. Hostile fixture mobs still bypass difficulty gates.
		boolean registered = type.equals(EntityTypes.IRON_GOLEM);
		T entity = registered
			? type.spawn(level, pos, EntitySpawnReason.COMMAND)
			: type.create(level, new EntitySpawnRequest(EntitySpawnReason.COMMAND, true));
		if (entity == null) throw new IllegalStateException("Caravan camp security fixture could not spawn " + type + " at " + pos);
		if (!registered) {
			entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			if (!level.addFreshEntity(entity)) {
				entity.discard();
				throw new IllegalStateException("Caravan camp security fixture could not register " + type + " at " + pos);
			}
		}
		entity.setPersistenceRequired();
		spawned.add(entity);
		return entity;
	}

	private static long countArmedGuards(ServerLevel level, UUID owner) {
		long count = 0;
		for (Entity entity : level.getAllEntities()) {
			if (!entity.isRemoved() && CaravanCampSecurity.isOwnedArmedGuard(entity, owner)) count++;
		}
		return count;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("Caravan camp security: " + message);
	}
}
