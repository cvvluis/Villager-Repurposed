package de.villagerplus.job;

import de.villagerplus.village.VillageMessages;
import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.Vec3;

public final class JobUtil {
	private static final long APPROACH_RETRY_COOLDOWN = 40L;
	private static final long APPROACH_TASK_COOLDOWN = 4201L;
	private static final long LONG_ROUTE_CACHE_TICKS = 40L;
	private static final long SHARED_STAND_LEASE_TICKS = 80L;
	private static final int[][] DELIVERY_STAND_OFFSETS = {
		{ 3, 0 }, { 2, 2 }, { 0, 3 }, { -2, 2 }, { -3, 0 }, { -2, -2 }, { 0, -3 }, { 2, -2 },
		{ 3, 1 }, { 1, 3 }, { -1, 3 }, { -3, 1 }, { -3, -1 }, { -1, -3 }, { 1, -3 }, { 3, -1 }
	};
	private static final Map<UUID, String> LONG_ROUTE_DIAGNOSTICS = new HashMap<>();
	private static final Map<UUID, LongRouteAttempt> LONG_ROUTE_ATTEMPTS = new HashMap<>();
	private static final Map<ServerLevel, Map<UUID, SharedStandReservation>> SHARED_STAND_RESERVATIONS = new WeakHashMap<>();
	private static long longRouteSearches;
	private static long longRouteCacheReuses;
	private record LongRouteAttempt(BlockPos goal, BlockPos waypoint, long retryAt) { }
	private record SharedStandReservation(BlockPos target, BlockPos stand, long expiresAt) { }

	private JobUtil() {
	}

	public static String lastLongRouteDiagnostic() {
		return LONG_ROUTE_DIAGNOSTICS.values().stream().reduce((first, second) -> second).orElse("");
	}

	public static String lastLongRouteDiagnostic(Villager villager) {
		return villager == null ? "" : LONG_ROUTE_DIAGNOSTICS.getOrDefault(villager.getUUID(), "");
	}

	static boolean shouldReuseLongRoute(BlockPos cachedGoal, BlockPos requestedGoal, BlockPos waypoint,
		long now, long retryAt, Vec3 workerPosition) {
		return cachedGoal != null && requestedGoal != null && cachedGoal.equals(requestedGoal) && now < retryAt
			&& (waypoint == null || workerPosition.distanceToSqr(Vec3.atBottomCenterOf(waypoint)) > 4.0);
	}

	static String longRoutePerf() {
		return "Langweg A*-Suchen " + longRouteSearches + ", Cache-Wiederverwendungen " + longRouteCacheReuses;
	}

	static void clearLongRoute(Villager villager) {
		if (villager == null) return;
		LONG_ROUTE_ATTEMPTS.remove(villager.getUUID());
		LONG_ROUTE_DIAGNOSTICS.remove(villager.getUUID());
	}

	static void clearLongRoutes() {
		LONG_ROUTE_ATTEMPTS.clear();
		LONG_ROUTE_DIAGNOSTICS.clear();
		longRouteSearches = 0L;
		longRouteCacheReuses = 0L;
	}

	/** True while the worker's cached long-route search to exactly this goal found no complete path. */
	public static boolean longRouteKnownUnreachable(Villager villager, BlockPos goal) {
		LongRouteAttempt cached = LONG_ROUTE_ATTEMPTS.get(villager.getUUID());
		return cached != null && cached.waypoint() == null && cached.goal().equals(goal);
	}

	private static void rememberLongRouteDiagnostic(Villager villager, String diagnostic) {
		if (LONG_ROUTE_DIAGNOSTICS.size() > 256) LONG_ROUTE_DIAGNOSTICS.clear();
		String previous = LONG_ROUTE_DIAGNOSTICS.put(villager.getUUID(), diagnostic);
		// Dev aid: surface a worker's changed long-route outcome once instead of keeping it only in memory.
		if (previous == null || !previous.substring(0, Math.min(previous.length(), 12)).equals(diagnostic.substring(0, Math.min(diagnostic.length(), 12)))) {
			StringBuilder from = new StringBuilder();
			for (StackTraceElement frame : new Throwable().getStackTrace()) {
				String cls = frame.getClassName();
				if (cls.endsWith("JobUtil") || cls.endsWith("JobManager")) continue;
				from.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(' ');
				if (from.length() > 90) break;
			}
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Langweg {}: {} via {}", villager.getName().getString(), diagnostic, from.toString().trim());
		}
	}

	/** A cached failed search must not leave vanilla navigation retrying the same long target each AI tick. */
	private static void clearUnreachableWalkTarget(Villager villager) {
		villager.getNavigation().stop();
		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
	}

	/** Laeuft zum Ziel. Gibt true zurueck, sobald der Dorfbewohner nah genug ist. */
	public static boolean approach(Ctx c, Vec3 pos, double reach) {
		JobState st = c.st();
		if (c.v().position().distanceToSqr(pos) <= reach * reach) {
			st.stuckCount = 0;
			st.walkStart = 0;
			st.bestDist = 0.0;
			return true;
		}
		if (st.walkStart == 0) {
			st.walkStart = c.now();
		} else if (c.now() - st.walkStart > 1500) {
			// Kommt nicht hin: dieses Ziel aufgeben; waehrenddessen bleiben gemeinsame
			// Dorfaufgaben moeglich, und ohne Ersatzarbeit greift anschliessend Staffing.
			abortApproach(c, APPROACH_TASK_COOLDOWN);
			return false;
		}
		// Haengen-Erkennung: kommt der Dorfbewohner dem Ziel ueber lange Zeit nicht merklich naeher (z.B. weil die
		// Wegfindung scheitert und er nur herumirrt), wird er sicher in die Naehe des Ziels gesetzt.
		Vec3 here = c.v().position();
		double dist = here.distanceTo(pos);
		if (st.walkStart == c.now() || st.bestDist <= 0.0 || dist < st.bestDist - 1.5) {
			st.bestDist = dist;
			st.lastMoveAt = c.now();
		} else if (c.now() - st.lastMoveAt > 240) {
			// Anti-Haengetask: kein Teleport mehr. Zwei Neuversuche der Wegfindung, beim dritten Mal wird der Task abgebrochen,
			// bekommt Abklingzeit und der Dorfbewohner waehlt eine andere Aufgabe.
			st.lastMoveAt = c.now();
			st.stuckCount++;
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			if (st.stuckCount >= 3) {
				abortApproach(c, APPROACH_TASK_COOLDOWN);
				return false;
			}
		}		c.v().getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, (float) Math.min(0.55, 0.45 * Skills.speed(c.mem())), 1));
		return false;
	}

	/**
	 * Abandons one unreachable target without freezing the whole villager for its task cooldown.
	 * The global cooldown only yields briefly; task slots stay idle beyond JobManager's idle-role
	 * reassignment threshold, which lets shared market/build work run or lets Staffing choose a role.
	 */
	private static void abortApproach(Ctx c, long taskCooldown) {
		JobState st = c.st();
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		st.clearTarget();
		st.stuckCount = 0;
		st.bestDist = 0.0;
		st.cooldownUntil = c.now() + APPROACH_RETRY_COOLDOWN;
		st.idleUntil[0] = c.now() + taskCooldown;
		st.idleUntil[1] = c.now() + taskCooldown;
	}

	/** Nahe am Ziel eine Stelle, an der ein Dorfbewohner (2 Bloecke hoch) frei stehen kann und die Luft/Boden hat. */
	public static Vec3 safeSpot(ServerLevel level, Vec3 target) {
		BlockPos base = BlockPos.containing(target);
		for (int dy = 0; dy <= 6; dy++) {
			BlockPos feet = base.above(dy);
			if (!level.hasChunkAt(feet)) {
				return null;
			}
			if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) {
				return Vec3.atBottomCenterOf(feet);
			}
		}
		return null;
	}

	public static boolean approach(Ctx c, BlockPos pos, double reach) {
		return approach(c, Vec3.atBottomCenterOf(pos), reach);
	}

	/** Stable resident-specific slot among a distributed ring around a shared delivery point. */
	static int deliveryStandSlot(UUID residentId) {
		long mixed = residentId.getMostSignificantBits()
			^ Long.rotateLeft(residentId.getLeastSignificantBits(), 23);
		mixed ^= mixed >>> 30;
		mixed *= 0xBF58476D1CE4E5B9L;
		mixed ^= mixed >>> 27;
		return (int) Long.remainderUnsigned(mixed, DELIVERY_STAND_OFFSETS.length);
	}

	/**
	 * Picks a loaded, supported, two-block-clear position close enough to interact with the
	 * shared container. Short leases reserve each selected slot; loaded walkers and occupants
	 * also block a slot so a villager does not join an existing queue. No chunk is requested.
	 */
	public static BlockPos sharedStandSpot(Ctx c, BlockPos target, double interactionDistance) {
		return sharedStandSpot(c, target, Vec3.atBottomCenterOf(target), interactionDistance);
	}

	/** Chooses a stand around a block while enforcing range to the actual interacting entity. */
	public static BlockPos sharedStandSpot(Ctx c, BlockPos target, Vec3 interactionPoint, double interactionDistance) {
		long now = c.now();
		UUID resident = c.v().getUUID();
		synchronized (SHARED_STAND_RESERVATIONS) {
			Map<UUID, SharedStandReservation> reservations = SHARED_STAND_RESERVATIONS.computeIfAbsent(c.level(), ignored -> new HashMap<>());
			reservations.values().removeIf(reservation -> reservation.expiresAt() <= now);
			SharedStandReservation own = reservations.get(resident);
			if (own != null && !own.target().equals(target)) {
				reservations.remove(resident);
				own = null;
			}

			Set<BlockPos> occupied = new HashSet<>();
			for (Villager other : c.level().getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(target).inflate(8.0),
				villager -> villager != c.v() && villager.isAlive())) {
				BlockPos feet = BlockPos.containing(other.position());
				if (feet.distSqr(target) <= 25.0) occupied.add(feet);
				other.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
					.map(WalkTarget::getTarget).map(targetTracker -> targetTracker.currentBlockPosition())
					.filter(walk -> walk.distSqr(target) <= 25.0).ifPresent(occupied::add);
			}

			if (own != null && availableSharedStand(c, target, own.stand(), interactionPoint, interactionDistance,
				reservations, occupied, resident)) {
				reservations.put(resident, new SharedStandReservation(target.immutable(), own.stand(), now + SHARED_STAND_LEASE_TICKS));
				return own.stand();
			}
			if (own != null) reservations.remove(resident);

			int rotation = (int) Math.floorMod(now / 20L, DELIVERY_STAND_OFFSETS.length);
			int start = (deliveryStandSlot(resident) + rotation) % DELIVERY_STAND_OFFSETS.length;
			for (int attempt = 0; attempt < DELIVERY_STAND_OFFSETS.length; attempt++) {
				int[] offset = DELIVERY_STAND_OFFSETS[(start + attempt) % DELIVERY_STAND_OFFSETS.length];
				BlockPos candidate = target.offset(offset[0], 0, offset[1]);
				if (!c.level().hasChunkAt(candidate)) continue;
				BlockPos surface = c.level().getHeightmapPos(
					net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candidate);
				Vec3 safe = safeSpot(c.level(), Vec3.atBottomCenterOf(surface));
				if (safe == null || safe.distanceToSqr(interactionPoint) > interactionDistance * interactionDistance) continue;
				BlockPos stand = BlockPos.containing(safe);
				if (!availableSharedStand(c, target, stand, interactionPoint, interactionDistance,
					reservations, occupied, resident)) continue;
				reservations.put(resident, new SharedStandReservation(target.immutable(), stand, now + SHARED_STAND_LEASE_TICKS));
				return stand;
			}
			reservations.remove(resident);
			return null;
		}
	}

	private static boolean availableSharedStand(Ctx c, BlockPos target, BlockPos stand, Vec3 interactionPoint,
		double interactionDistance, Map<UUID, SharedStandReservation> reservations, Set<BlockPos> occupied, UUID resident) {
		if (!c.level().hasChunkAt(stand) || occupied.contains(stand)
			|| Vec3.atBottomCenterOf(stand).distanceToSqr(interactionPoint) > interactionDistance * interactionDistance) return false;
		Vec3 safe = safeSpot(c.level(), Vec3.atBottomCenterOf(stand));
		if (safe == null || !BlockPos.containing(safe).equals(stand)) return false;
		for (Map.Entry<UUID, SharedStandReservation> entry : reservations.entrySet()) {
			SharedStandReservation reservation = entry.getValue();
			if (!entry.getKey().equals(resident) && reservation.target().equals(target) && reservation.stand().equals(stand)) return false;
		}
		return true;
	}

	private static BlockPos deliveryStandSpot(Ctx c, BlockPos chest) {
		return sharedStandSpot(c, chest, 4.0);
	}

	/** Approaches a stable shared-interaction slot while preserving anti-stuck state for that slot. */
	public static boolean approachShared(Ctx c, BlockPos stand, double reach) {
		if (!stand.equals(c.st().target) || c.st().targetEntity != null) {
			c.st().target = stand.immutable();
			c.st().targetEntity = null;
			c.st().walkStart = 0;
			c.st().bestDist = 0.0;
			c.st().lastMoveAt = 0;
			c.st().stuckCount = 0;
		}
		return approachLong(c, stand, reach);
	}

	/** Uses the builder's bounded long-route search to feed short waypoints to vanilla navigation. */
	public static boolean approachLong(Ctx c, BlockPos pos, double reach) {
		if (approach(c,pos,reach)) {
			LONG_ROUTE_ATTEMPTS.remove(c.v().getUUID());
			return true;
		}
		if (c.st().cooldownUntil>c.now()) {
			rememberLongRouteDiagnostic(c.v(), "cooldown from=" + c.v().blockPosition().toShortString() + " goal=" + pos.toShortString());
			return false;
		}
		UUID workerId = c.v().getUUID();
		LongRouteAttempt cached = LONG_ROUTE_ATTEMPTS.get(workerId);
		if (cached != null && shouldReuseLongRoute(cached.goal(), pos, cached.waypoint(), c.now(), cached.retryAt(), c.v().position())) {
			longRouteCacheReuses++;
			if (cached.waypoint() != null) {
				var walk = c.v().getBrain().getMemory(MemoryModuleType.WALK_TARGET);
				if (walk.isPresent()) c.v().getBrain().setMemory(MemoryModuleType.WALK_TARGET,
					new WalkTarget(cached.waypoint(), walk.get().getSpeedModifier(), 1));
			} else clearUnreachableWalkTarget(c.v());
			return false;
		}
		longRouteSearches++;
		var route=de.villagerplus.build.Builder.workPath(c,pos);
		if(route==null||!route.canReach()) {
			LONG_ROUTE_ATTEMPTS.put(workerId, new LongRouteAttempt(pos.immutable(), null, c.now() + LONG_ROUTE_CACHE_TICKS));
			clearUnreachableWalkTarget(c.v());
			rememberLongRouteDiagnostic(c.v(), "unreachable from=" + c.v().blockPosition().toShortString() + " goal=" + pos.toShortString()
				+ " route=" + (route == null ? "null" : route.getNodeCount() + " nodes"));
			return false;
		}
		BlockPos waypoint=pos;
		for(int i=0;i<route.getNodeCount();i++) {
			BlockPos node=route.getNodePos(i);
			if(c.v().position().distanceToSqr(Vec3.atBottomCenterOf(node))>100.0) break;
			waypoint=node;
		}
		LONG_ROUTE_ATTEMPTS.put(workerId, new LongRouteAttempt(pos.immutable(), waypoint.immutable(), c.now() + LONG_ROUTE_CACHE_TICKS));
		var walk=c.v().getBrain().getMemory(MemoryModuleType.WALK_TARGET);
		if(walk.isPresent()) c.v().getBrain().setMemory(MemoryModuleType.WALK_TARGET,
			new WalkTarget(waypoint,walk.get().getSpeedModifier(),1));
		rememberLongRouteDiagnostic(c.v(), "reachable from=" + c.v().blockPosition().toShortString() + " goal=" + pos.toShortString()
			+ " waypoint=" + waypoint.toShortString() + " nodes=" + route.getNodeCount());
		return false;
	}

	public static void swing(Villager v) {
		v.swing(InteractionHand.MAIN_HAND);
	}

	/** Steckt das Item ins Inventar, Reste landen auf dem Boden. */
	public static void give(ServerLevel level, Villager v, ItemStack stack) {
		ItemStack rest = v.getInventory().addItem(stack);
		if (!rest.isEmpty()) {
			v.spawnAtLocation(level, rest);
		}
	}

	public static int count(Container c, Predicate<ItemStack> p) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && p.test(s)) {
				n += s.getCount();
			}
		}
		return n;
	}

	/** Entnimmt bis zu {@code amount} Items. Gibt die entnommene Menge zurueck. */
	public static int take(Container c, Predicate<ItemStack> p, int amount) {
		int taken = 0;
		for (int i = 0; i < c.getContainerSize() && taken < amount; i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && p.test(s)) {
				int n = Math.min(amount - taken, s.getCount());
				c.removeItem(i, n);
				taken += n;
			}
		}
		if (taken > 0) {
			c.setChanged();
		}
		return taken;
	}

	/** Legt Items in einen Behaelter; was nicht passt, faellt neben den Dorfbewohner. */
	public static void insert(Ctx c, Container box, ItemStack stack) {
		ItemStack rest = insertRemainder(c, box, stack);
		if (!rest.isEmpty()) {
			give(c.level(), c.v(), rest);
		}
	}

	/** Performs a quota-aware insert and returns every unaccepted item to the caller. */
	public static ItemStack insertRemainder(Ctx c, Container box, ItemStack stack) {
		return c.village() == null ? HopperBlockEntity.addItem(null, box, stack, null)
			: de.villagerplus.village.StockPolicy.insertVillage(c.level(), c.village(), stack);
	}

	/** Sammelt frisch gedroppte Items in der Naehe auf. */
	public static void collectDrops(Ctx c, double radius, int maxAge) {
		List<ItemEntity> items = c.level().getEntitiesOfClass(ItemEntity.class, c.v().getBoundingBox().inflate(radius),
			e -> e.isAlive() && e.getAge() < maxAge);
		for (ItemEntity e : items) {
			give(c.level(), c.v(), e.getItem().copy());
			e.discard();
		}
	}

	/** Dorfkiste der Dorfmitte, sonst eine gemerkte/nahe Truhe. */
	public static BlockPos chest(Ctx c) {
		if (c.village() != null) {
			return communalDeliveryChest(c);
		}
		return findNearbyChest(c);
	}

	public static Container box(Ctx c) {
		BlockPos p = chest(c);
		return p == null ? null : HopperBlockEntity.getContainerAt(c.level(), p);
	}

	private static BlockPos findNearbyChest(Ctx c) {
		ServerLevel level = c.level();
		Villager v = c.v();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : c.mem().containers) {
			if (level.hasChunkAt(p) && level.getBlockState(p).getBlock() instanceof ChestBlock && unownedChest(level, p)) {
				double d = p.distSqr(v.blockPosition());
				if (d <= 40.0 * 40.0 && d < bestDist) {
					best = p;
					bestDist = d;
				}
			}
		}
		if (best != null || c.now() < c.st().chestCheckAt) {
			return best;
		}
		c.st().chestCheckAt = c.now() + 600;
		int r = Config.CHEST_SEARCH_RADIUS;
		BlockPos center = v.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 4, r))) {
			if (level.hasChunkAt(p) && level.getBlockState(p).getBlock() instanceof ChestBlock && unownedChest(level, p)) {
				double d = p.distSqr(center);
				if (d < bestDist) {
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		if (best != null) {
			VillagerMemory.remember(c.mem().containers, best, Config.MEMORY_MAX_CONTAINERS);
		}
		return best;
	}

	private static boolean unownedChest(ServerLevel level, BlockPos pos) {
		return de.villagerplus.village.Lager.publicContainerAt(level, pos) != null;
	}

	/**
	 * Bringt Ueberschuss zur Dorfkiste. Gibt true zurueck, solange der Dorfbewohner damit beschaeftigt ist.
	 *
	 * @param keep soviele passende Items behaelt der Dorfbewohner selbst
	 */
	public static boolean depositSurplus(Ctx c, Predicate<ItemStack> matches, int threshold, int keep) {
		int total = count(c.v().getInventory(), matches);
		if (total < threshold || total <= keep || c.now() < c.st().depositBlockedUntil) {
			return false;
		}
		Container inv = c.v().getInventory();
		int toMove = total - keep;
		java.util.Map<net.minecraft.world.item.Item, Integer> roomByItem = new java.util.IdentityHashMap<>();
		java.util.Map<net.minecraft.world.item.Item, Integer> carriedByItem = new java.util.IdentityHashMap<>();
		if (c.village() != null) {
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.isEmpty() || !matches.test(stack)) continue;
				var item = stack.getItem();
				carriedByItem.merge(item, stack.getCount(), Integer::sum);
			}
			roomByItem.putAll(de.villagerplus.village.StockPolicy.deliveryRooms(c.level(), c.village(), carriedByItem.keySet()));
			int policyAllowed = 0;
			for (var entry : carriedByItem.entrySet()) policyAllowed += Math.min(entry.getValue(), roomByItem.getOrDefault(entry.getKey(), 0));
			toMove = Math.min(toMove, policyAllowed);
		}
		if (toMove <= 0) return false;
		BlockPos chest = c.village() == null ? chest(c) : communalDeliveryChest(c);
		if (chest == null) {
			return false;
		}
		// Meilenstein-Gebaeude haben Vorrang: Nahrung -> Kornspeicher, Werkzeug/Ruestung -> Werkzeugkammer.
		String depotLabel = null;
		String destinationDepot = c.village() == null ? null : de.villagerplus.build.Blueprints.STORAGE;
		if (c.village() != null) {
			ItemStack sample = ItemStack.EMPTY;
			Container invNow = c.v().getInventory();
			for (int i = 0; i < invNow.getContainerSize() && sample.isEmpty(); i++) {
				ItemStack candidate = invNow.getItem(i);
				if (matches.test(candidate) && roomByItem.getOrDefault(candidate.getItem(), Integer.MAX_VALUE) > 0) {
					sample = candidate;
				}
			}
			String depot = de.villagerplus.village.Specials.depotType(sample);
			BlockPos special = depot == null ? null : de.villagerplus.village.Specials.depotWithRoom(c.level(), c.village(), depot, sample);
			if (special != null) {
				chest = special;
				destinationDepot = depot;
				depotLabel = depot.equals(de.villagerplus.build.Blueprints.GRANARY) ? "bringt Waren zum Kornspeicher" : "bringt Waren zur Werkzeugkammer";
			} else if (de.villagerplus.build.Blueprints.GRANARY.equals(depot)) {
				// Before the first granary exists, food (including starter seeds) uses the ordinary village chest.
				// Keep the food policy for this internal public delivery so the material-store filter still rejects direct insertion.
				destinationDepot = de.villagerplus.build.Blueprints.GRANARY;
			}
		}
		// Ist die Dorfkiste voll, geht der Ueberschuss in den Lagerschuppen (falls das Dorf einen hat).
		boolean toLager = false;
		Container home = HopperBlockEntity.getContainerAt(c.level(), chest);
		if (depotLabel == null && home != null && !hasEmptySlot(home) && c.village() != null && c.village().data().extra.lager.isPresent()) {
			BlockPos lagerChest = de.villagerplus.village.Lager.chestWithRoom(c.level(), c.village().data());
			if (lagerChest != null) {
				chest = lagerChest;
				toLager = true;
				destinationDepot = de.villagerplus.build.Blueprints.STORAGE;
			}
		}
		BlockPos stand = deliveryStandSpot(c, chest);
		if (stand == null) {
			// A missing or obstructed stand is not permission to insert remotely. Keep all
			// carried goods and let the ordinary task retry cooldown choose when to try again.
			c.st().depositBlockedUntil = Math.max(c.st().depositBlockedUntil, c.now() + APPROACH_RETRY_COOLDOWN);
			return false;
		}
		// Keep the walk/anti-stuck state for this destination across job ticks. Clearing it
		// unconditionally here restarted the movement timer every tick, so a long route
		// could never time out or accumulate the three stuck retries.
		c.setActivity(depotLabel != null ? depotLabel : toLager ? "bringt Waren ins Lager" : "bringt Waren zur Dorfkiste");
		if (!approachShared(c, stand, 1.0)) {
			// A normal route attempt remains active. Anti-stuck abort clears the target;
			// surface that as a completed attempt so routine stages do not restart it forever.
			return c.st().target != null;
		}
		Container box = HopperBlockEntity.getContainerAt(c.level(), chest);
		if (box == null) {
			return false;
		}
		// Stock may have changed while this worker was walking to the chest. Recount at commit time so two
		// workers cannot both consume the same remaining target allowance.
		if (c.village() != null) {
			int plannedBeforeArrival = toMove;
			roomByItem.clear();
			roomByItem.putAll(de.villagerplus.village.StockPolicy.deliveryRooms(c.level(), c.village(), carriedByItem.keySet()));
			toMove = 0;
			for (var entry : carriedByItem.entrySet()) toMove += Math.min(entry.getValue(), roomByItem.getOrDefault(entry.getKey(), 0));
			toMove = Math.min(toMove, plannedBeforeArrival);
		}
		for (int i = 0; i < inv.getContainerSize() && toMove > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !matches.test(s)) {
				continue;
			}
			int itemRoom = c.village() == null ? Integer.MAX_VALUE : roomByItem.getOrDefault(s.getItem(), 0);
			int planned = Math.min(toMove, Math.min(s.getCount(), itemRoom));
			if (planned <= 0) continue;
			ItemStack part = s.copyWithCount(planned);
			int before = part.getCount();
			ItemStack rest = c.village() == null ? HopperBlockEntity.addItem(null, box, part, null)
				: de.villagerplus.village.StockPolicy.insertInto(c.level(), c.village(), destinationDepot, box, part);
			int moved = before - rest.getCount();
			s.shrink(moved);
			toMove -= moved;
			if (c.village() != null) roomByItem.computeIfPresent(s.getItem(), (item, room) -> Math.max(0, room - moved));
			if (!rest.isEmpty()) {
				break;
			}
		}
		inv.setChanged();
		swing(c.v());
		c.st().cooldownUntil = c.now() + 100;
		if (toMove > 0) {
			// Alles voll (Dorfkiste und ggf. Lager): nichts wird weggeworfen. Spaeter erneut versuchen statt alle Arbeit zu blockieren;
			// das Dorf baut bei voller Kiste vorrangig einen Lagerschuppen.
			c.st().depositBlockedUntil = c.now() + 2400;
			return false;
		} else {
			c.mem().jobsDone++;
		}
		return true;
	}

	/** Resolves only village-owned storage; a nearby private house chest is never a communal delivery destination. */
	public static BlockPos communalDeliveryChest(Ctx c) {
		if (c.village() == null) return null;
		BlockPos home = c.village().chestPos(c.level());
		if (home != null) return home;
		return de.villagerplus.village.Lager.chestWithRoom(c.level(), c.village().data());
	}

	/** Legt einen Stapel in die Kiste (erst auf gleiche Stapel, dann in freie Plaetze); Rest geht verloren (nur fuer Rueckbau-Material). */
	public static void depositTo(Container box, ItemStack stack) {
		for (int i = 0; i < box.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack cur = box.getItem(i);
			if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
				int move = Math.min(stack.getCount(), cur.getMaxStackSize() - cur.getCount());
				cur.grow(move);
				stack.shrink(move);
				box.setChanged();
			}
		}
		for (int i = 0; i < box.getContainerSize() && !stack.isEmpty(); i++) {
			if (box.getItem(i).isEmpty()) {
				box.setItem(i, stack.copy());
				stack.setCount(0);
			}
		}
	}

	public static boolean hasEmptySlot(Container box) {
		for (int i = 0; i < box.getContainerSize(); i++) {
			if (box.getItem(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public static void tellNearbyPlayers(ServerLevel level, Entity at, double radius, Component msg) {
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(at) <= radius * radius) {
				VillageMessages.overlay(p, msg);
			}
		}
	}
}
