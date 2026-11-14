package de.villagerplus.village;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded camp defense for the caravan's golem and armed villager. */
public final class CaravanCampSecurity {
	static final double CAMP_RADIUS = 12.0;
	public static final String ARMED_GUARD_TAG = "vp_caravan_armed_guard";
	private static final String ARMED_GUARD_OWNER_PREFIX = "vp_caravan_armed_guard_owner:";
	private static final double GUARD_POST_RADIUS = 16.0;
	private static final double RETURN_TOLERANCE_SQUARED = 4.0;
	private CaravanCampSecurity() { }

	/**
	 * Selects only vanilla hostile mobs near the camp and hands combat to the golem's normal AI.
	 * Called from the existing active-caravan loop; this does not install a world-wide poll or ticket chunks.
	 */
	static boolean defend(ServerLevel level, IronGolem guard, List<BlockPos> campCells, boolean activeCamp) {
		if (guard == null || !guard.isAlive() || guard.isRemoved()) return false;
		if (!activeCamp || campCells == null || campCells.isEmpty()) {
			end(guard);
			return false;
		}

		BlockPos post = post(campCells);
		if (post == null || !level.hasChunkAt(post) || !level.hasChunkAt(guard.blockPosition())) {
			end(guard);
			return false;
		}

		LivingEntity current = guard.getTarget();
		if (isCampHostile(level, post, current)) return true;
		if (current != null) {
			guard.setTarget(null);
			guard.getNavigation().stop();
		}

		// A caravan guard first returns to the camp after arrival; it does not pull enemies
		// toward the camp from the caravan's distant spawn point.
		if (guard.distanceToSqr(Vec3.atBottomCenterOf(post)) > GUARD_POST_RADIUS * GUARD_POST_RADIUS) return false;

		Mob hostile = nearbyHostiles(level, post).stream()
			.min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(Vec3.atCenterOf(post))))
			.orElse(null);
		if (hostile == null) return false;
		guard.getNavigation().stop();
		guard.setTarget(hostile);
		return true;
	}

	/** Ends camp-owned targeting without discarding the caravan guard entity. */
	static void end(IronGolem guard) {
		if (guard == null || !guard.isAlive() || guard.isRemoved()) return;
		if (guard.getTarget() != null) guard.setTarget(null);
		guard.getNavigation().stop();
	}

	/** Resume the normal escort/post route after combat, without loading its destination chunk. */
	static boolean returnTo(ServerLevel level, IronGolem guard, BlockPos target) {
		if (guard == null || !guard.isAlive() || guard.isRemoved() || guard.getTarget() != null || target == null) return false;
		if (!level.hasChunkAt(guard.blockPosition()) || !level.hasChunkAt(target)) return false;
		var navigation = guard.getNavigation();
		// The camp post was safe when selected, but blocks and terrain may change while
		// the guard fights. Never keep following a route to a destination that can no
		// longer support the whole golem or safely contain its collision box.
		if (!CaravanSites.canStand(level, guard, target, List.of())) {
			navigation.stop();
			return false;
		}
		if (guard.distanceToSqr(Vec3.atBottomCenterOf(target)) <= RETURN_TOLERANCE_SQUARED) return false;
		if (followsSameRoute(target, navigation.getTargetPos(), navigation.isInProgress())) return true;
		return navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1);
	}

	static boolean followsSameRoute(BlockPos target, BlockPos navigationTarget, boolean inProgress) {
		return target != null && inProgress && target.equals(navigationTarget);
	}

	static BlockPos post(List<BlockPos> cells) {
		if (cells == null || cells.isEmpty()) return null;
		int minX = cells.stream().mapToInt(BlockPos::getX).min().orElseThrow();
		int maxX = cells.stream().mapToInt(BlockPos::getX).max().orElseThrow();
		int minZ = cells.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
		int maxZ = cells.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
		int groundY = cells.stream().mapToInt(BlockPos::getY).min().orElseThrow();
		BlockPos center = new BlockPos(Math.floorDiv(minX + maxX, 2), groundY, Math.floorDiv(minZ + maxZ, 2));
		return center.north(3);
	}

	/** Confirms the exterior post has support, clear golem space, and no overlap with the planned tent. */
	static boolean safePost(ServerLevel level, BlockPos center, Iterable<BlockPos> plannedCampCells) {
		BlockPos guardPost = center.north(3);
		if (!level.hasChunkAt(guardPost)) return false;
		IronGolem template = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
		if (template == null) return false;
		AABB guardBox = CaravanSites.bounds(template, guardPost);
		List<BlockPos> campCells = new ArrayList<>();
		for (BlockPos campCell : plannedCampCells) {
			campCells.add(campCell);
			if (guardBox.intersects(new AABB(campCell))) return false;
		}
		if (!CaravanSites.canStand(level, template, guardPost, List.of())) return false;
		Villager armedGuard = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		if (armedGuard == null) return false;
		armedGuard.setNoAi(false);
		armedGuard.setNoGravity(false);
		return CaravanSites.canStandArmedGuard(level, armedGuard, guardPost, campCells);
	}

	/** Ensure one visibly armed guard is present beside the validated exterior post. */
	static Villager ensureArmedGuard(ServerLevel level, UUID owner, List<BlockPos> campCells) {
		if (owner == null || campCells == null || campCells.isEmpty()) return null;
		BlockPos post = post(campCells);
		BlockPos spot = CaravanSites.armedGuardSpot(post);
		if (post == null || spot == null || !level.hasChunkAt(spot)) return null;

		List<Villager> owned = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof Villager villager && villager.isAlive() && !villager.isRemoved()
				&& isOwnedArmedGuard(villager, owner)) owned.add(villager);
		}
		owned.sort(Comparator.comparingDouble(guard -> guard.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(spot))));
		Villager guard = owned.isEmpty() ? null : owned.getFirst();
		for (int i = 1; i < owned.size(); i++) owned.get(i).discard();

		boolean created = guard == null;
		if (created) {
			guard = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
			if (guard == null) return null;
		}
		prepareArmedGuard(guard, owner);
		if (!CaravanSites.canStandArmedGuard(level, guard, post, campCells)) {
			if (created) guard.discard();
			else removeArmedGuard(level, owner);
			return null;
		}
		// Only place a new guard. Reconciliation runs while a guard may be fighting;
		// snapping an existing one back every 20 ticks would cancel its combat route.
		if (created) {
			guard.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
			guard.setYRot(45.0F);
			guard.setYHeadRot(45.0F);
		}
		if (created && !level.addFreshEntity(guard)) {
			guard.discard();
			return null;
		}
		return guard;
	}

	/** The armed villager attacks only local hostiles, then walks back to its camp post. */
	static boolean defendArmedGuard(ServerLevel level, Villager guard, List<BlockPos> campCells, boolean activeCamp) {
		if (guard == null || !guard.isAlive() || guard.isRemoved() || !isArmedGuard(guard)) return false;
		if (!activeCamp || campCells == null || campCells.isEmpty()) {
			endArmedGuard(guard);
			return false;
		}

		BlockPos campPost = post(campCells);
		BlockPos guardPost = CaravanSites.armedGuardSpot(campPost);
		if (campPost == null || guardPost == null || !level.hasChunkAt(campPost)
			|| !level.hasChunkAt(guardPost) || !level.hasChunkAt(guard.blockPosition())) {
			endArmedGuard(guard);
			return false;
		}
		if (guard.distanceToSqr(Vec3.atBottomCenterOf(campPost)) > GUARD_POST_RADIUS * GUARD_POST_RADIUS) {
			endArmedGuard(guard);
			return returnArmedGuardToPost(level, guard, campPost, guardPost, campCells);
		}

		LivingEntity target = guard.getTarget();
		if (!isCampHostile(level, campPost, target)) {
			if (target != null) {
				guard.setTarget(null);
				stopArmedGuardWalking(guard);
			}
			target = nearbyHostiles(level, campPost).stream()
				.min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(guard.position())))
				.orElse(null);
		}

		if (target == null) return returnArmedGuardToPost(level, guard, campPost, guardPost, campCells);

		guard.setTarget(target);
		guard.getLookControl().setLookAt(target, 30.0F, 30.0F);
		setArmedGuardWalkTarget(guard, target, 1.0F, 1);
		var navigation = guard.getNavigation();
		// Villager brain activities can replace a direct navigation command with a
		// work, rest, or idle route. Keep its normal MoveToTargetSink pointed at the
		// hostile as well as starting a path immediately; both use vanilla navigation.
		if (guard.distanceToSqr(target) <= 4.0 + target.getBbWidth() && guard.hasLineOfSight(target)) {
			stopArmedGuardWalking(guard);
			if (guard.tickCount % 20 == 0) {
				guard.swing(InteractionHand.MAIN_HAND);
				hitWithSword(level, guard, target);
			}
		} else if (!followsSameRoute(target.blockPosition(), navigation.getTargetPos(), navigation.isInProgress())) {
			navigation.moveTo(target, 1.0);
		}
		return true;
	}

	private static boolean hitWithSword(ServerLevel level, Villager guard, LivingEntity target) {
		// Villagers do not register a melee ATTACK_DAMAGE attribute. Apply ordinary mob
		// damage directly so the visible iron sword is backed by reliable server combat.
		boolean hit = target.hurtServer(level, level.damageSources().mobAttack(guard), 4.0F);
		if (hit) {
			guard.setLastHurtMob(target);
			guard.getMainHandItem().hurtEnemy(target, guard);
		}
		return hit;
	}

	private static boolean returnArmedGuardToPost(ServerLevel level, Villager guard, BlockPos campPost,
		BlockPos guardPost, List<BlockPos> campCells) {
		var navigation = guard.getNavigation();
		if (!CaravanSites.canStandArmedGuard(level, guard, campPost, campCells)) {
			stopArmedGuardNavigation(guard);
			return false;
		}
		if (guard.distanceToSqr(Vec3.atBottomCenterOf(guardPost)) <= RETURN_TOLERANCE_SQUARED) {
			stopArmedGuardNavigation(guard);
			return false;
		}
		setArmedGuardWalkTarget(guard, guardPost, 0.9F, 0);
		if (followsSameRoute(guardPost, navigation.getTargetPos(), navigation.isInProgress())) return true;
		return navigation.moveTo(guardPost.getX() + 0.5, guardPost.getY(), guardPost.getZ() + 0.5, 0.9);
	}

	static void endArmedGuard(Villager guard) {
		if (guard == null || !guard.isAlive() || guard.isRemoved()) return;
		if (guard.getTarget() != null) guard.setTarget(null);
		stopArmedGuardNavigation(guard);
	}

	private static void setArmedGuardWalkTarget(Villager guard, LivingEntity target, float speed, int closeEnough) {
		var brain = guard.getBrain();
		brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, speed, closeEnough));
		brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
	}

	private static void setArmedGuardWalkTarget(Villager guard, BlockPos target, float speed, int closeEnough) {
		guard.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, speed, closeEnough));
	}

	private static void stopArmedGuardWalking(Villager guard) {
		guard.getNavigation().stop();
		guard.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		guard.getBrain().eraseMemory(MemoryModuleType.PATH);
	}

	private static void stopArmedGuardNavigation(Villager guard) {
		stopArmedGuardWalking(guard);
		guard.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
	}

	/** Removes every loaded visual owned by this caravan; no chunk is loaded to do so. */
	static int removeArmedGuard(ServerLevel level, UUID owner) {
		if (owner == null) return 0;
		List<Villager> owned = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof Villager villager && !villager.isRemoved() && isOwnedArmedGuard(villager, owner)) owned.add(villager);
		}
		for (Villager villager : owned) villager.discard();
		return owned.size();
	}

	static boolean isArmedGuard(Entity entity) {
		return entity instanceof Villager && entity.entityTags().contains(ARMED_GUARD_TAG);
	}

	static boolean isOwnedArmedGuard(Entity entity, UUID owner) {
		return owner != null && isArmedGuard(entity)
			&& entity.entityTags().contains(ARMED_GUARD_OWNER_PREFIX + owner);
	}

	private static void prepareArmedGuard(Villager guard, UUID owner) {
		guard.addTag(ARMED_GUARD_TAG);
		guard.addTag(ARMED_GUARD_OWNER_PREFIX + owner);
		guard.setPersistenceRequired();
		guard.setNoAi(false);
		guard.setNoGravity(false);
		guard.setInvulnerable(true);
		guard.setSilent(true);
		guard.setCustomName(Component.literal("Karawanenwache"));
		guard.setCustomNameVisible(false);
		if (!guard.getMainHandItem().is(Items.IRON_SWORD)) {
			guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		}
	}

	private static java.util.List<Mob> nearbyHostiles(ServerLevel level, BlockPos post) {
		double x = post.getX() + 0.5;
		double y = post.getY() + 0.5;
		double z = post.getZ() + 0.5;
		// Expand for entity hitboxes, then apply the exact spherical radius below.
		AABB area = new AABB(x - CAMP_RADIUS - 2, y - CAMP_RADIUS - 2, z - CAMP_RADIUS - 2,
			x + CAMP_RADIUS + 2, y + CAMP_RADIUS + 2, z + CAMP_RADIUS + 2);
		Vec3 center = Vec3.atCenterOf(post);
		return level.getEntitiesOfClass(Mob.class, area,
			candidate -> candidate.isAlive() && !candidate.isRemoved() && isVanillaHostile(candidate)
				&& candidate.distanceToSqr(center) <= CAMP_RADIUS * CAMP_RADIUS);
	}

	private static boolean isCampHostile(ServerLevel level, BlockPos post, LivingEntity candidate) {
		if (!(candidate instanceof Mob mob) || !candidate.isAlive() || candidate.isRemoved() || !isVanillaHostile(mob)) return false;
		return level.hasChunkAt(candidate.blockPosition())
			&& candidate.distanceToSqr(Vec3.atCenterOf(post)) <= CAMP_RADIUS * CAMP_RADIUS;
	}

	private static boolean isVanillaHostile(Mob candidate) {
		return candidate instanceof Enemy
			&& BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).getNamespace().equals("minecraft");
	}
}
