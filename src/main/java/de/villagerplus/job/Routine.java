package de.villagerplus.job;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die festen Routinen im Tagesplan:
 * <ul>
 * <li>Morgen: Fruehstueck zu Hause (aus der eigenen Kiste), dann Waren zur Dorfkiste, dann Plausch.</li>
 * <li>Pause: Plausch mit Nachbarn.</li>
 * <li>Mittag: Waren abliefern, gemeinsames Essen am Dorfplatz, Plausch.</li>
 * <li>Abend: Abendessen zu Hause, persoenliche Sachen in die eigene Kiste legen (Privateigentum).</li>
 * </ul>
 */
public final class Routine {
	private static final int MAX_GATHER_PATH_CHECKS_PER_ATTEMPT = 4;
	private static final long GATHER_RETRY_BASE_TICKS = 120L;
	private static final long GATHER_RETRY_JITTER_TICKS = 200L;
	private static final long GATHER_PATH_RECHECK_TICKS = 200L;
	private static final Map<UUID, GatherSearch> GATHER_SEARCHES = new HashMap<>();
	private record GatherKey(BlockPos anchor, Phase phase, boolean plazaBuilt) { }
	private static final class GatherSearch {
		final GatherKey key;
		final List<BlockPos> columns;
		int cursor;
		BlockPos destination;
		long retryAt;
		long pathCheckedAt;
		boolean walking;

		GatherSearch(GatherKey key, List<BlockPos> columns) {
			this.key = key;
			this.columns = columns;
		}
	}

	private Routine() {
	}

	static void forgetGatherSearch(Villager villager) {
		if (villager != null) GATHER_SEARCHES.remove(villager.getUUID());
	}

	static void clearGatherSearches() {
		GATHER_SEARCHES.clear();
	}

	/** true = beschaeftigt. */
	public static boolean run(Ctx c, Job job, Phase phase) {
		JobState st = c.st();
		if (st.routinePhase != phase) {
			st.routinePhase = phase;
			st.stage = 0;
			st.clearTarget();
			if (phase == Phase.MORNING) ringMorningBell(c);
		}
		switch (phase) {
			case MORNING -> {
				if (st.stage == 0) {
					if (homeMeal(c, "frühstückt zu Hause")) {
						return true;
					}
					st.stage = 1;
				}
				if (st.stage == 1) {
					if (deliver(c, job)) {
						return true;
					}
					st.stage = 2;
				}
				return Social.tick(c);
			}
			case LUNCH -> {
				if (st.stage == 0) {
					if (deliver(c, job)) {
						return true;
					}
					st.stage = 1;
				}
				if (st.stage == 1) {
					centerMeal(c);
					st.stage = 2;
				}
				if (gather(c)) return true;
				return Social.tick(c);
			}
			case EVENING -> {
				if (st.stage == 0) {
					if (de.villagerplus.village.Tavern.visit(c)) return true;
					// If no generated tavern, meal stock, or safe route is available, leave the tavern stage once.
					st.stage = 1;
				}
				if (st.stage == 1) {
					if (homeMeal(c, "isst zu Abend")) {
						return true;
					}
					st.stage = 2;
				}
				if (st.stage == 2) {
					if (store(c, job)) {
						return true;
					}
					st.stage = 3;
				}
				c.setActivity("ruht sich zu Hause aus");
				return false;
			}
			case BREAK -> {
				if (gather(c)) return true;
				return Social.tick(c);
			}
			default -> {
				return false;
			}
		}
	}

	/** The routine's morning transition announces the day; ensureCenter remains a safe daily fallback. */
	private static void ringMorningBell(Ctx c) {
		if (c.village() == null) return;
		long day = Math.floorDiv(c.level().getOverworldClockTime(), 24_000L);
		de.villagerplus.village.Villages.ringMorningBell(c.level(), c.village(), day);
	}

 /**
  * Pausen und Mittag: kleine Gespraechsgruppen verteilt um den Platz (6 Gruppenmittelpunkte, je Person ein eigener Standplatz
  * +-2 Bloecke), ein Teil geht stattdessen in die offenen Laeden oder bummelt - nicht alle auf einem Haufen am Schrein.
  */
 private static boolean gather(Ctx c) {
  if (c.village() == null || c.st().chatPartner != null) return false;
  var plaza = de.villagerplus.village.Market.of(c.village()).plaza();
  BlockPos center = gatherAnchor(c.village().bell(), plaza.orElse(null));
  boolean plazaBuilt = plaza.isPresent();
  Phase phase = c.st().routinePhase == null ? Phase.BREAK : c.st().routinePhase;
  GatherKey key = new GatherKey(center.immutable(), phase, plazaBuilt);
  GatherSearch search = GATHER_SEARCHES.get(c.v().getUUID());
  if (search == null || !search.key.equals(key)) {
   if (search != null && search.destination != null && search.destination.equals(c.st().target)) {
    c.v().getNavigation().stop();
    c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    c.st().clearTarget();
   }
   search = new GatherSearch(key, gatherColumns(c, center, plazaBuilt));
   GATHER_SEARCHES.put(c.v().getUUID(), search);
  }

  if (search.destination != null) {
   double reachSquared = 2.0 * 2.0;
   if (c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(search.destination)) <= reachSquared) {
    c.setActivity(plazaBuilt ? "plaudert verteilt am Dorfplatz" : "hält sich verteilt am Dorfzentrum auf");
    return Social.tick(c);
   }
   // JobUtil's anti-stuck path abort clears target and walk state. Move on to another
   // candidate after that bounded retry instead of restarting the same failed route.
   if (search.walking && c.st().target == null && c.st().walkStart == 0L) {
    rejectGatherDestination(c, search);
   }
   if (search.destination != null && c.now() - search.pathCheckedAt >= GATHER_PATH_RECHECK_TICKS
    && JobManager.reserveWeatherPathBudget(c.level(), c.now(), 1) > 0) {
    if (!JobManager.safeReachableStand(c, search.destination)) {
     rejectGatherDestination(c, search);
    } else {
     search.pathCheckedAt = c.now();
    }
   }
   if (search.destination != null) {
    if (!search.destination.equals(c.st().target)) {
     c.st().clearTarget();
     c.st().target = search.destination;
     search.walking = false;
    }
    c.setActivity(plazaBuilt ? "geht zu einem freien Pausenplatz am Dorfplatz" : "sucht einen freien Pausenplatz am Dorfzentrum");
    boolean arrived = JobUtil.approach(c, search.destination, 2.0);
    if (!arrived) search.walking = true;
    if (arrived) return Social.tick(c);
    return true;
   }
  }

  if (c.now() < search.retryAt || search.columns.isEmpty()) {
   c.setActivity("wartet auf einen freien Pausenplatz");
   return true;
  }

  Set<BlockPos> occupied = occupiedGatherStands(c, center, plazaBuilt ? 27 : 15);
  int checked = 0;
  while (checked < MAX_GATHER_PATH_CHECKS_PER_ATTEMPT && search.cursor < search.columns.size()) {
   BlockPos column = search.columns.get(search.cursor);
   if (!c.level().hasChunkAt(column)) {
    search.cursor++;
    continue;
   }
   BlockPos candidate = c.level().getHeightmapPos(
    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).immutable();
   if (!safeGatherStand(c, candidate) || crowdedGatherStand(candidate, occupied)) {
    search.cursor++;
    continue;
   }
   if (JobManager.reserveWeatherPathBudget(c.level(), c.now(), 1) == 0) break;
   checked++;
   if (!JobManager.safeReachableStand(c, candidate)) {
    search.cursor++;
    continue;
   }
   UNREACHABLE_GATHER.remove(c.v().getUUID());
   search.destination = candidate;
   search.pathCheckedAt = c.now();
   search.walking = false;
   c.st().clearTarget();
   c.st().target = candidate;
   c.setActivity(plazaBuilt ? "geht zu einem freien Pausenplatz am Dorfplatz" : "sucht einen freien Pausenplatz am Dorfzentrum");
   boolean arrived = JobUtil.approach(c, candidate, 2.0);
   if (!arrived) search.walking = true;
   if (arrived) return Social.tick(c);
   return true;
  }
  if (search.cursor >= search.columns.size()) search.cursor = 0;
  search.retryAt = c.now() + gatherRetryDelay(c.v().getUUID());
  c.setActivity("wartet auf einen erreichbaren Pausenplatz");
  // Dauerhaft kein erreichbarer Platz: vermutlich in einer Grube/Falle gefangen -> zur Dorfmitte zurueckholen.
  if (c.village() != null && c.village().bell() != null) {
   int misses = UNREACHABLE_GATHER.merge(c.v().getUUID(), 1, Integer::sum);
   if (misses >= 6) {
    UNREACHABLE_GATHER.remove(c.v().getUUID());
    var arrival = de.villagerplus.build.Builder.villageReturn(c.level(), c.village().bell());
    if (arrival != null && c.v().blockPosition().distSqr(arrival) > 36.0) {
     c.v().getNavigation().stop();
     c.v().teleportTo(arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5);
     c.v().resetFallDistance();
     org.slf4j.LoggerFactory.getLogger("villagerplus").info("Villagers: Repurposed: {} fand lange keinen erreichbaren Pausenplatz und wurde zur Dorfmitte zurueckgeholt", c.mem().name);
    }
   }
  }
  return true;
 }

 private static final java.util.Map<java.util.UUID, Integer> UNREACHABLE_GATHER = new java.util.HashMap<>();

 private static List<BlockPos> gatherColumns(Ctx c, BlockPos center, boolean plazaBuilt) {
  List<BlockPos> columns = new ArrayList<>();
  Set<BlockPos> unique = new HashSet<>();
  int hash = c.v().getUUID().hashCode();
  int mode = Math.floorMod(hash >>> 4, 5);
  var shops = de.villagerplus.village.Market.of(c.village()).shops().stream()
   .filter(shop -> shop.opened() > 0 && !shop.deceased()).toList();
  if (mode < 2 && !shops.isEmpty()) {
   var shop = shops.get(Math.floorMod(hash, shops.size()));
   addGatherColumn(columns, unique, shop.origin().offset(Math.floorMod(hash >>> 8, 5) - 2, 0,
    Math.floorMod(hash >>> 12, 3) + 3));
  }
  BlockPos first = gatherOffset(c.v().getUUID(), c.st().routinePhase, plazaBuilt);
  addGatherColumn(columns, unique, center.offset(first.getX(), 0, first.getZ()));
  int maxRadius = plazaBuilt ? 24 : 12;
  int minRadius = plazaBuilt ? 6 : 4;
  int span = maxRadius * 2 + 1;
  long seed = c.v().getUUID().getMostSignificantBits()
   ^ Long.rotateLeft(c.v().getUUID().getLeastSignificantBits(), 23)
   ^ (0xA24BAED4963EE407L * (c.st().routinePhase == null ? 1 : c.st().routinePhase.ordinal() + 1));
  for (int attempt = 0; attempt < 192 && columns.size() < 96; attempt++) {
   long bits = mixGatherBits(seed + 0x9E3779B97F4A7C15L * (attempt + 1L));
   int dx = (int) Math.floorMod(bits, (long) span) - maxRadius;
   int dz = (int) Math.floorMod(bits >>> 32, (long) span) - maxRadius;
   int distanceSquared = dx * dx + dz * dz;
   if (distanceSquared < minRadius * minRadius || distanceSquared > maxRadius * maxRadius) continue;
   addGatherColumn(columns, unique, center.offset(dx, 0, dz));
  }
  return List.copyOf(columns);
 }

 private static void addGatherColumn(List<BlockPos> columns, Set<BlockPos> unique, BlockPos column) {
  BlockPos stable = column.immutable();
  if (unique.add(stable)) columns.add(stable);
 }

 private static Set<BlockPos> occupiedGatherStands(Ctx c, BlockPos center, int radius) {
  Set<BlockPos> occupied = new HashSet<>();
  AABB area = new AABB(center).inflate(radius + 3.0);
  for (Villager other : c.level().getEntitiesOfClass(Villager.class, area, villager -> villager != c.v() && villager.isAlive())) {
   occupied.add(other.blockPosition().immutable());
   other.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
    .map(target -> target.getTarget().currentBlockPosition()).ifPresent(pos -> occupied.add(pos.immutable()));
  }
  return occupied;
 }

 private static boolean crowdedGatherStand(BlockPos candidate, Set<BlockPos> occupied) {
  for (BlockPos other : occupied) if (other.distSqr(candidate) <= 4.0) return true;
  return false;
 }

 private static boolean safeGatherStand(Ctx c, BlockPos feet) {
  BlockPos floor = feet.below();
  BlockPos head = feet.above();
  if (!c.level().hasChunkAt(floor) || !c.level().hasChunkAt(feet) || !c.level().hasChunkAt(head)) return false;
  var ground = c.level().getBlockState(floor);
  var body = c.level().getBlockState(feet);
  var above = c.level().getBlockState(head);
  return (ground.isFaceSturdy(c.level(), floor, net.minecraft.core.Direction.UP) || ground.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))
   && ground.getFluidState().isEmpty() && body.getFluidState().isEmpty() && above.getFluidState().isEmpty()
   && body.getCollisionShape(c.level(), feet).isEmpty() && above.getCollisionShape(c.level(), head).isEmpty();
 }

 private static long gatherRetryDelay(UUID resident) {
  return GATHER_RETRY_BASE_TICKS + Math.floorMod(resident.hashCode(), (int) GATHER_RETRY_JITTER_TICKS);
 }

 private static void rejectGatherDestination(Ctx c, GatherSearch search) {
  if (search.destination != null && search.destination.equals(c.st().target)) c.st().clearTarget();
  c.v().getNavigation().stop();
  c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
  search.cursor = nextGatherCursorAfterReject(search.cursor, search.columns.size());
  search.destination = null;
  search.walking = false;
  search.retryAt = c.now() + gatherRetryDelay(c.v().getUUID());
 }

 static int nextGatherCursorAfterReject(int cursor, int columnCount) {
  return cursor >= 0 && cursor < columnCount ? cursor + 1 : cursor;
 }

 /** Stable, pseudorandom 2D standing point that spreads residents around the plaza. */
 static BlockPos gatherOffset(UUID residentId) {
  return gatherOffset(residentId, Phase.BREAK);
 }

 static BlockPos gatherAnchor(BlockPos bell, BlockPos plaza) {
  return plaza == null ? bell : plaza;
 }

 /** A new stand point for the next social period prevents long pauses from pinning residents to one spot. */
 static BlockPos gatherOffset(UUID residentId, Phase phase) {
  return gatherOffset(residentId, phase, true);
 }

 /** Small settlements without a built plaza use a closer ring around the bell instead of leaving everyone at it. */
 static BlockPos gatherOffset(UUID residentId, Phase phase, boolean plazaBuilt) {
  int maxRadius = plazaBuilt ? 24 : 12;
  int minRadius = plazaBuilt ? 6 : 4;
  long seed = residentId.getMostSignificantBits() ^ Long.rotateLeft(residentId.getLeastSignificantBits(), 23)
   ^ (0xD1B54A32D192ED03L * (phase == null ? 0 : phase.ordinal() + 1));
  for (int attempt = 0; attempt < 8; attempt++) {
   long bits = mixGatherBits(seed + 0x9E3779B97F4A7C15L * attempt);
   int span = maxRadius * 2 + 1;
   int dx = (int) Math.floorMod(bits, (long) span) - maxRadius;
   int dz = (int) Math.floorMod(bits >>> 32, (long) span) - maxRadius;
   int distanceSquared = dx * dx + dz * dz;
   if (distanceSquared >= minRadius * minRadius && distanceSquared <= maxRadius * maxRadius) return new BlockPos(dx, 0, dz);
  }
  // A fixed ring fallback is practically unreachable, but keeps malformed seeds bounded.
  int direction = (int) Math.floorMod(mixGatherBits(seed), 8L);
  int cardinal = Math.max(minRadius, Math.min(maxRadius, Math.min(12, maxRadius)));
  int diagonal = Math.max(minRadius, (int) Math.floor(cardinal * Math.sqrt(0.5)));
  return switch (direction) {
   case 0 -> new BlockPos(cardinal, 0, 0);
   case 1 -> new BlockPos(diagonal, 0, diagonal);
   case 2 -> new BlockPos(0, 0, cardinal);
   case 3 -> new BlockPos(-diagonal, 0, diagonal);
   case 4 -> new BlockPos(-cardinal, 0, 0);
   case 5 -> new BlockPos(-diagonal, 0, -diagonal);
   case 6 -> new BlockPos(0, 0, -cardinal);
   default -> new BlockPos(diagonal, 0, -diagonal);
  };
 }

 private static long mixGatherBits(long value) {
  value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
  value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
  return value ^ (value >>> 31);
 }
	private static Container homeBox(Ctx c) {
		if (c.mem().homeChest.isPresent() && c.level().getBlockState(c.mem().homeChest.get()).getBlock() instanceof ChestBlock) {
			return HopperBlockEntity.getContainerAt(c.level(), c.mem().homeChest.get());
		}
		return null;
	}

	private static BlockPos homeSpot(Ctx c) {
		if (c.mem().homeChest.isPresent() && homeBox(c) != null) {
			return c.mem().homeChest.get();
		}
		return c.mem().homeBed.orElse(null);
	}

	/** Essen zu Hause aus dem eigenen Vorrat. true = noch unterwegs. */
	private static boolean homeMeal(Ctx c, String label) {
		BlockPos spot = homeSpot(c);
		if (spot == null) {
			Meals.eat(c, null);
			return false;
		}
		c.setActivity(label);
		if (!JobUtil.approach(c, spot, 3.5)) {
			return true;
		}
		if (Meals.eat(c, homeBox(c))) {
			c.st().cooldownUntil = c.now() + 60;
		}
		return false;
	}

	/** Waren der Dorfmitte uebergeben. true = noch unterwegs. */
	private static boolean deliver(Ctx c, Job job) {
		if (c.village() == null) {
			return false;
		}
		var produce = job.produce();
		int keep = job.keep();
		int carried = JobUtil.count(c.v().getInventory(), produce);
		if (carried < 1 || carried <= keep) {
			stopDeliveryApproachIfNeeded(c);
			return false;
		}
		// The delivery routine must not send every worker to the bell first. The actual
		// destination (including a granary/tool depot/storage shed) selects a spread stand
		// and JobUtil commits only after the resident reaches that interaction point.
		return JobUtil.depositSurplus(c, produce, 1, keep);
	}

	private static void stopDeliveryApproachIfNeeded(Ctx c) {
		var walk = c.v().getBrain().getMemory(MemoryModuleType.WALK_TARGET);
		if (walk.isEmpty()) return;
		BlockPos sharedSpot = c.village().chestPos(c.level());
		if (sharedSpot == null) sharedSpot = c.village().bell();
		BlockPos previousDeliveryStand = c.st().target;
		if (previousDeliveryStand == null || previousDeliveryStand.distSqr(sharedSpot) > 16) return;
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		c.st().clearTarget();
		c.st().stuckCount = 0;
		c.st().bestDist = 0.0;
		c.st().lastMoveAt = 0;
	}

	/** Gemeinsames Mittagessen; ausserdem Vorrat auffuellen (Nahrung im Inventar ermoeglicht den Nachwuchs). */
	private static void centerMeal(Ctx c) {
		Container box = JobUtil.box(c);
		Meals.eat(c, box);
		java.util.List<Container> pantry = new java.util.ArrayList<>();
		if (box != null) {
			pantry.add(box);
		}
		if (c.village() != null) {
			pantry.addAll(de.villagerplus.village.Specials.containers(c.level(), c.village(), de.villagerplus.build.Blueprints.GRANARY));
		}
		if (pantry.isEmpty()) {
			return;
		}
		int have = JobUtil.count(c.v().getInventory(), Meals::isFood);
		if (have < 3) {
			for (var food : Villager.FOOD_POINTS.keySet()) {
				for (Container b : pantry) {
					int got = JobUtil.take(b, s -> s.is(food), 3 - have);
					if (got > 0) {
						JobUtil.give(c.level(), c.v(), new ItemStack(food, got));
						return;
					}
				}
			}
		}
	}

	/** Abends persoenlichen Besitz (alles ausser Arbeitsware und etwas Proviant) in die eigene Kiste legen. */
	private static boolean store(Ctx c, Job job) {
		Container home = homeBox(c);
		if (home == null) {
			return false;
		}
		c.setActivity("räumt Besitz in die eigene Kiste");
		if (!JobUtil.approach(c, c.mem().homeChest.get(), 3.0)) {
			return true;
		}
		Container inv = c.v().getInventory();
		int keepFood = 4;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || job.produce().test(s)) {
				continue;
			}
			if (Meals.isFood(s)) {
				int keepHere = Math.min(keepFood, s.getCount());
				keepFood -= keepHere;
				if (s.getCount() == keepHere) {
					continue;
				}
				ItemStack extra = s.copyWithCount(s.getCount() - keepHere);
				ItemStack rest = JobUtil.insertRemainder(c, home, extra);
				s.setCount(keepHere + rest.getCount());
				continue;
			}
			ItemStack rest = JobUtil.insertRemainder(c, home, s.copy());
			inv.setItem(i, rest);
		}
		inv.setChanged();
		home.setChanged();
		c.st().cooldownUntil = c.now() + 40;
		return false;
	}
}
