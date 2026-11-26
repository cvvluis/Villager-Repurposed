package de.villagerplus.job;

import de.villagerplus.mixin.ItemEntityPickupAccessor;
import de.villagerplus.world.LoadedColumns;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Small, local transactions for real item entities. */
public final class LooseItemPickup {
	static final int OFFER_SCAN_INTERVAL = 40;
	static final double REACH = 1.75;
	static final double OFFER_RADIUS = 8.0;
	private static final long CLAIM_TICKS = 100L;
	private static final long TARGET_TIMEOUT = 1_600L;
	private static final int MAX_CANDIDATES = 8;

	private LooseItemPickup() { }

	/** Let a resident collect a nearby player throw, walking to it when it is close enough to notice. */
	public static boolean offerNearby(Ctx c) {
		JobState state = c.st();
		if (!allowsPickup(c)) {
			releaseTarget(c);
			return false;
		}
		ItemEntity target = state.offeredItemTarget;
		if (target != null) {
			if (!target.isAlive() || !canCollect(c, target)
				|| c.now() - state.offeredItemTargetSince > TARGET_TIMEOUT) {
				releaseTarget(c);
				target = null;
			} else {
				state.offeredItemClaimUntil = c.now() + CLAIM_TICKS;
				if (inReach(c, target)) {
					boolean collected = collectInReach(c, target);
					if (!target.isAlive()) releaseTarget(c);
					if (collected) {
						state.cooldownUntil = c.now() + 20;
						c.mem().jobsDone++;
						c.setActivity("räumt auf");
					}
					return collected;
				}
				c.setActivity("räumt auf");
				JobUtil.approach(c, target.position(), REACH);
				return true;
			}
		}
		if (c.now() < state.nextOfferedItemScan) return false;
		state.nextOfferedItemScan = c.now() + OFFER_SCAN_INTERVAL;
		var candidates = new ArrayList<ItemEntity>();
		c.level().getEntities(EntityTypeTest.forClass(ItemEntity.class), c.v().getBoundingBox().inflate(OFFER_RADIUS),
			drop -> c.v().distanceToSqr(drop) <= OFFER_RADIUS * OFFER_RADIUS
				&& canCollect(c, drop) && drop.getOwner() instanceof Player,
			candidates, MAX_CANDIDATES);
		candidates.sort(Comparator.comparingDouble(drop -> drop.distanceToSqr(c.v())));
		for (ItemEntity drop : candidates) {
			if (!claim(c, drop)) continue;
			state.offeredItemTarget = drop;
			state.offeredItemTargetSince = c.now();
			state.offeredItemClaimUntil = c.now() + CLAIM_TICKS;
			if (inReach(c, drop)) {
				boolean collected = collectInReach(c, drop);
				if (!drop.isAlive()) releaseTarget(c);
				if (collected) {
					state.cooldownUntil = c.now() + 20;
					c.mem().jobsDone++;
					c.setActivity("räumt auf");
				}
				return collected;
			}
			c.setActivity("räumt auf");
			JobUtil.approach(c, drop.position(), REACH);
			return true;
		}
		return false;
	}

	static boolean allowsPickup(Ctx c) {
		return c.v().isAlive() && c.v().canPickUpLoot()
			&& c.level().isPositionEntityTicking(c.v().blockPosition())
			&& c.level().getGameRules().get(GameRules.MOB_GRIEFING);
	}

	static boolean canCollect(Ctx c, ItemEntity drop) {
		if (drop.level() != c.level() || !drop.isAlive() || drop.hasPickUpDelay()
			|| reservedForOther(c, drop)
			|| !c.level().isPositionEntityTicking(drop.blockPosition())) return false;
		ItemStack stack = drop.getItem();
		return !stack.isEmpty() && c.v().getInventory().canAddItem(stack);
	}

	static boolean isReserved(ItemEntity drop) {
		return ((ItemEntityPickupAccessor) drop).villagerplus$pickupTarget() != null
			|| BuildHaul.isMarkedCargo(drop.getItem());
	}

	private static boolean reservedForOther(Ctx c, ItemEntity drop) {
		UUID target = ((ItemEntityPickupAccessor) drop).villagerplus$pickupTarget();
		if (target == null) return BuildHaul.isMarkedCargo(drop.getItem());
		if (target.equals(c.v().getUUID()) && c.st().offeredItemTarget == drop) return BuildHaul.isMarkedCargo(drop.getItem());
		if (c.level().getEntity(target) instanceof net.minecraft.world.entity.npc.villager.Villager owner) {
			JobState ownerState = JobManager.state(owner);
			if (ownerState.offeredItemTarget == drop && c.now() <= ownerState.offeredItemClaimUntil) return true;
			((ItemEntityPickupAccessor) drop).villagerplus$setPickupTarget(null);
			return BuildHaul.isMarkedCargo(drop.getItem());
		}
		// Player-targeted and other vanilla claims are not ours to take over.
		return true;
	}

	private static boolean claim(Ctx c, ItemEntity drop) {
		if (reservedForOther(c, drop)) return false;
		((ItemEntityPickupAccessor) drop).villagerplus$setPickupTarget(c.v().getUUID());
		return true;
	}

	private static void releaseTarget(Ctx c) {
		JobState state = c.st();
		ItemEntity target = state.offeredItemTarget;
		if (target != null && target.isAlive()) {
			ItemEntityPickupAccessor accessor = (ItemEntityPickupAccessor) target;
			if (c.v().getUUID().equals(accessor.villagerplus$pickupTarget())) accessor.villagerplus$setPickupTarget(null);
		}
		state.offeredItemTarget = null;
		state.offeredItemTargetSince = 0;
		state.offeredItemClaimUntil = 0;
	}

	static boolean inReach(Ctx c, ItemEntity drop) {
		return c.v().distanceToSqr(drop) <= REACH * REACH;
	}

	/** Check that a collision ray can run without waiting for chunk promotion. */
	static boolean clearReach(Ctx c, ItemEntity drop) {
		if (!inReach(c, drop)) return false;
		Vec3 from = c.v().getEyePosition();
		Vec3 to = drop.getEyePosition();
		int minX = SectionPos.blockToSectionCoord(Mth.floor(Math.min(from.x, to.x) - 1.0E-6));
		int maxX = SectionPos.blockToSectionCoord(Mth.floor(Math.max(from.x, to.x) + 1.0E-6));
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(Math.min(from.z, to.z) - 1.0E-6));
		int maxZ = SectionPos.blockToSectionCoord(Mth.floor(Math.max(from.z, to.z) + 1.0E-6));
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!LoadedColumns.readyChunk(c.level(), x, z)) return false;
			}
		}
		return c.v().hasLineOfSight(drop);
	}

	static boolean collectInReach(Ctx c, ItemEntity drop) {
		if (!allowsPickup(c) || !canCollect(c, drop) || !clearReach(c, drop)) return false;
		return takeAvailable(c, drop);
	}

	/** Transfer the accepted part of an already validated item entity. */
	static boolean takeAvailable(Ctx c, ItemEntity drop) {
		if (!drop.isAlive()) return false;
		ItemStack offered = drop.getItem();
		if (offered.isEmpty()) return false;
		ItemStack remainder = c.v().getInventory().addItem(offered.copy());
		int taken = offered.getCount() - remainder.getCount();
		if (taken <= 0) return false;
		try {
			c.v().onItemPickup(drop);
			c.v().take(drop, taken);
		} finally {
			// Leave the unaccepted amount in the original entity, with its age and ownership intact.
			if (remainder.isEmpty()) drop.discard();
			else drop.setItem(remainder);
		}
		return true;
	}
}
