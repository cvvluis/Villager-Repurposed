package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.Villages;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tierhaltung: Im Gehege des Dorfes fuettern Dorfbewohner Kuehe, Schafe, Schweine und Huehner mit passendem Futter aus
 * der Dorfkiste, damit sie sich vermehren. Der Metzger haelt den Bestand klein, der Schaefer schert die Schafe.
 */
public final class Livestock {
	/** Per-species working capacity. Breeding pauses at capacity; the butcher may remove only unprotected excess. */
	public static final int PEN_CAPACITY_PER_SPECIES = Config.CULL_CAP;
	private static final int HERDING_SEARCH_RADIUS = 24;
	private static final int HERDING_TIMEOUT_TICKS = 600;
	private static final int HERDING_MAX_PATH_NODES = HERDING_SEARCH_RADIUS * 4;
	private static final int INITIAL_HERD_PATH_BUDGET = 24;
	private static final int MAX_HERDING_PLANS_PER_LEVEL = 8;
	private static final int INITIAL_PEN_ELIGIBILITY_REFRESH_TICKS = 20;
	private static final double PEN_FEED_STAND_REACH = 0.8;
	private static final double PEN_FEEDING_REACH = 6.0;
	private static final double PEN_FEED_PAIR_STAND_MAX_DISTANCE = 5.2;
	private static final String HERDING_PLAN_TAG = "vp_livestock_herd_plan:";
	private static final String HERDING_GATE_TAG = "vp_livestock_gate_recovery:";
	private static final List<EntityType<? extends Animal>> HERD_TYPES = List.of(
		EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.PIG, EntityTypes.CHICKEN);
	private static final Map<ServerLevel, Map<UUID, HerdingPlan>> HERDING_PLANS = new WeakHashMap<>();
	private static final Map<ServerLevel, Map<Long, InitialPenEligibility>> INITIAL_PEN_ELIGIBILITY = new WeakHashMap<>();
	private static final ThreadLocal<Integer> INITIAL_PEN_PLANNING_SUPPRESSION = ThreadLocal.withInitial(() -> 0);

	private record PenGate(BlockPos position, Direction outward) {
		BlockPos inside() { return position.relative(outward.getOpposite()); }
		BlockPos outside() { return position.relative(outward); }
	}

	private record BreedingPair(Animal first, Animal second, Vec3 exteriorStand) { }

	private record FeedSnapshot(Container container, List<ItemStack> contents) { }
	private record InitialPenEligibility(long checkedAt, boolean eligible) { }

	private enum FeedingApproach { READY, MOVING }

	record PassageBlock(BlockPos position, BlockState state, boolean owned) {
		PassageBlock {
			position = position.immutable();
		}
	}

	private static final class HerdingPlan {
		final BlockPos bell;
		final BlockPos gate;
		final UUID herder;
		final UUID animal;
		final long startedAt;
		final boolean ownsGate;
		final List<PassageBlock> passageBlocks;

		HerdingPlan(BlockPos bell, BlockPos gate, UUID herder, UUID animal, long startedAt,
			boolean ownsGate, List<PassageBlock> passageBlocks) {
			this.bell = bell.immutable();
			this.gate = gate.immutable();
			this.herder = herder;
			this.animal = animal;
			this.startedAt = startedAt;
			this.ownsGate = ownsGate;
			this.passageBlocks = List.copyOf(passageBlocks);
		}
	}

	static {
		ServerEntityEvents.ENTITY_LOAD.register(Livestock::recoverEntityPlan);
		ServerEntityEvents.ENTITY_UNLOAD.register(Livestock::onEntityUnload);
		ServerTickEvents.END_LEVEL_TICK.register(Livestock::tickHerdingPlans);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				Map<UUID, HerdingPlan> plans = HERDING_PLANS.get(level);
				if (plans != null) {
					for (HerdingPlan plan : List.copyOf(plans.values())) cancelHerding(level, plan, true);
				}
			}
			HERDING_PLANS.clear();
			INITIAL_PEN_ELIGIBILITY.clear();
		});
	}

	private Livestock() {
	}

	/** Let the existing Builder search for the first field without letting its animal shortcut choose a pen first. */
	static boolean withInitialPenPlanningSuppressed(BooleanSupplier planning) {
		int depth = INITIAL_PEN_PLANNING_SUPPRESSION.get();
		INITIAL_PEN_PLANNING_SUPPRESSION.set(depth + 1);
		try {
			return planning.getAsBoolean();
		} finally {
			if (depth == 0) INITIAL_PEN_PLANNING_SUPPRESSION.remove();
			else INITIAL_PEN_PLANNING_SUPPRESSION.set(depth);
		}
	}

	private record Species(EntityType<? extends Animal> type, Item food, String name) { }

	private static final List<Species> SPECIES = List.of(
		new Species(EntityTypes.COW, Items.WHEAT, "Kühe"),
		new Species(EntityTypes.SHEEP, Items.WHEAT, "Schafe"),
		new Species(EntityTypes.PIG, Items.CARROT, "Schweine"),
		new Species(EntityTypes.PIG, Items.POTATO, "Schweine"),
		new Species(EntityTypes.PIG, Items.BEETROOT, "Schweine"),
		new Species(EntityTypes.CHICKEN, Items.WHEAT_SEEDS, "Hühner"));

	public static boolean canBreed(int adults, int total, int availableFeed) {
		return canBreed(adults, total, availableFeed, PEN_CAPACITY_PER_SPECIES);
	}

	static boolean canBreed(int adults, int total, int availableFeed, int effectiveCapacity) {
		return adults >= 2 && total < effectiveCapacity && availableFeed >= 2;
	}

	private static boolean canBreedHerd(ServerLevel level, BlockPos pen, List<Animal> adults,
		List<Animal> herd, int availableFeed, int capacity) {
		if (!canBreed(adults.size(), herd.size(), availableFeed, capacity)) return false;
		for (int i = 0; i < adults.size(); i++) {
			Animal first = adults.get(i);
			if (sectionCount(level, pen, first, herd) >= PenExpansion.sectionCapacity(level, pen, first)) continue;
			for (int j = i + 1; j < adults.size(); j++) {
				if (breedingPairEligible(level, pen, first, adults.get(j), herd)) return true;
			}
		}
		return false;
	}

	/** Bounded read-only preflight: does a real adult pair have a vanilla path to this worker? */
	static boolean hasReachableInitialHerd(ServerLevel level, VillageRef ref, Villager herder) {
		if (level == null || ref == null || herder == null || herder.level() != level) return false;
		// knownBellsNear deliberately excludes its center. Include this village's own bell before
		// the ownership check below; otherwise the initial pair preflight rejects every animal.
		List<BlockPos> nearbyBells = herdingBellsNear(level, ref.bell(), ref.bell(),
			Config.VILLAGE_RADIUS + HERDING_SEARCH_RADIUS);
		AABB sourceArea = new AABB(ref.bell()).inflate(Config.VILLAGE_RADIUS, 4, Config.VILLAGE_RADIUS);
		List<ZonePolicy.Zone> pastures = validPastures(ref);
		List<Animal> candidates = level.getEntitiesOfClass(Animal.class, sourceArea,
			animal -> herdable(animal) && !hasActiveAnimalPlan(level, animal.getUUID())
				&& inPastureZone(animal, pastures) && belongsToVillage(level, ref, animal, nearbyBells))
			.stream().sorted(Comparator.comparingDouble((Animal animal) -> animal.distanceToSqr(herder))
				.thenComparing(animal -> animal.getUUID().toString())).toList();
		Map<EntityType<?>, Integer> reachableByType = new HashMap<>();
		BlockPos target = herder.blockPosition();
		int checkedPaths = 0;
		for (Animal animal : candidates) {
			if (checkedPaths >= INITIAL_HERD_PATH_BUDGET) break;
			checkedPaths++;
			Path path = animal.getNavigation().createPath(target, 0);
			if (!isBoundedHerdingPath(path, target)) continue;
			int reachable = reachableByType.merge(animal.getType(), 1, Integer::sum);
			if (reachable >= 2) return true;
		}
		return false;
	}

	/**
	 * Read-only trigger for the paid first-pen planner: is at least one real, unprotected animal
	 * available to this village, while it still has no registered pen? A single animal is enough
	 * to justify a safe enclosure; the normal herd work can recruit a compatible mate later.
	 */
	public static boolean hasEligibleLivestockForInitialPen(ServerLevel level, VillageRef ref) {
		if (INITIAL_PEN_PLANNING_SUPPRESSION.get() > 0 || level == null || ref == null
			|| !ref.data().extra.pen.isEmpty() || !level.hasChunkAt(ref.bell())) return false;
		List<BlockPos> nearbyBells = herdingBellsNear(level, ref.bell(), ref.bell(), Config.VILLAGE_RADIUS);
		if (!nearbyBells.contains(ref.bell())) return false;
		List<ZonePolicy.Zone> pastures = validPastures(ref);
		AABB villageArea = new AABB(ref.bell()).inflate(Config.VILLAGE_RADIUS, 4, Config.VILLAGE_RADIUS);
		return !level.getEntitiesOfClass(Animal.class, villageArea,
			animal -> herdable(animal) && !hasActiveAnimalPlan(level, animal.getUUID())
				&& inPastureZone(animal, pastures) && belongsToVillage(level, ref, animal, nearbyBells)).isEmpty();
	}

	/** Cached planner variant; repeated farmer/build ticks share one bounded neighborhood entity query per second. */
	public static boolean hasEligibleLivestockForInitialPenCached(ServerLevel level, VillageRef ref) {
		if (INITIAL_PEN_PLANNING_SUPPRESSION.get() > 0 || level == null || ref == null
			|| !ref.data().extra.pen.isEmpty() || !level.hasChunkAt(ref.bell())) return false;
		long now = level.getGameTime();
		Map<Long, InitialPenEligibility> cache = INITIAL_PEN_ELIGIBILITY.computeIfAbsent(level, ignored -> new HashMap<>());
		long villageKey = ref.bell().asLong();
		InitialPenEligibility cached = cache.get(villageKey);
		if (cached != null && now - cached.checkedAt() < INITIAL_PEN_ELIGIBILITY_REFRESH_TICKS) return cached.eligible();
		boolean eligible = hasEligibleLivestockForInitialPen(level, ref);
		cache.put(villageKey, new InitialPenEligibility(now, eligible));
		if (cache.size() > 256) cache.entrySet().removeIf(entry -> now - entry.getValue().checkedAt() >= 1200);
		return eligible;
	}

	/**
	 * A safe first pen is still useful when no wild breeding pair is reachable yet: once it exists,
	 * normal herd work can recruit genuine nearby animals through its checked gate as they become available.
	 * Keep this separate from {@link #hasReachableInitialHerd} so the latter remains a truthful preflight.
	 */
	static boolean canPlanInitialPen(ServerLevel level, VillageRef ref, Villager farmer) {
		return level != null && ref != null && farmer != null && farmer.isAlive() && farmer.level() == level
			&& ref.data().extra.pen.isEmpty();
	}

	/** Feed a market may sell to a resident for an active, under-supplied breeding pair. */
	public static int marketFeedShortage(ServerLevel level, VillageRef ref, Villager buyer, Item food) {
		if (ref == null || buyer == null || ref.data().extra.pen.isEmpty()) return 0;
		String job = de.villagerplus.Compat.jobKey(buyer.getVillagerData().profession());
		if (!"farmer".equals(job) && !"shepherd".equals(job) && !"butcher".equals(job)) return 0;
		if (SPECIES.stream().noneMatch(species -> species.food() == food)) return 0;
		BlockPos pen = ref.data().extra.pen.get();
		if (!level.hasChunkAt(pen)) return 0;
		List<Container> granaries = feedContainers(level, ref);
		int feedOnHand = countFeed(granaries, food)
			+ JobUtil.count(buyer.getInventory(), stack -> stack.is(food));
		int shortage = Math.max(0, 2 - feedOnHand);
		if (shortage == 0) return 0;

		AABB area = PenExpansion.area(level, pen);
		List<ZonePolicy.Zone> pastures = ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(z -> "pasture".equals(z.kind())
				&& ZonePolicy.validBounds(ref, z.kind(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
		int capacity = PenExpansion.capacity(level, ref);
		for (Species species : SPECIES) {
			if (species.food() != food) continue;
			List<Animal> herd = level.getEntitiesOfClass(Animal.class, area,
				a -> a.isAlive() && a.getType() == species.type() && inPasture(a, area, pastures)
					&& PenExpansion.contains(level, pen, a));
			List<Animal> adultsInLoveEligible = herd.stream().filter(a -> a.getAge() == 0 && !a.isInLove()).toList();
			if (canBreedHerd(level, pen, adultsInLoveEligible, herd, 2, capacity)) return shortage;
		}
		return 0;
	}

	private static int countFeed(List<Container> granaries, Item food) {
		int total = 0;
		for (Container granary : granaries) total += JobUtil.count(granary, stack -> stack.is(food));
		return total;
	}

	/** One real public village reservoir plus public granaries; private chests and identity duplicates fail closed. */
	static List<Container> feedContainers(ServerLevel level, VillageRef ref) {
		List<Container> stores = new ArrayList<>();
		for (Container granary : Specials.containers(level, ref, Blueprints.GRANARY)) addUnique(stores, granary);
		BlockPos publicChest = ref.chestPos(level);
		if (publicChest != null) addUnique(stores, Lager.publicContainerAt(level, publicChest));
		return List.copyOf(stores);
	}

	private static void addUnique(List<Container> stores, Container candidate) {
		if (candidate != null && stores.stream().noneMatch(existing -> existing == candidate)) stores.add(candidate);
	}

	/** Removes one breeding portion from public granaries first, then the resident's inventory, or restores every slot. */
	private static boolean takeFeedTransactionally(Ctx c, List<Container> granaries, Item food, int amount) {
		List<Container> sources = new ArrayList<>(granaries);
		sources.add(c.v().getInventory());
		List<FeedSnapshot> snapshots = new ArrayList<>();
		for (Container source : sources) {
			if (snapshots.stream().anyMatch(snapshot -> snapshot.container() == source)) continue;
			List<ItemStack> contents = new ArrayList<>(source.getContainerSize());
			for (int slot = 0; slot < source.getContainerSize(); slot++) contents.add(source.getItem(slot).copy());
			snapshots.add(new FeedSnapshot(source, contents));
		}

		int remaining = amount;
		for (Container source : sources) {
			if (remaining == 0) break;
			remaining -= JobUtil.take(source, stack -> stack.is(food), remaining);
		}
		if (remaining == 0) return true;

		for (FeedSnapshot snapshot : snapshots) {
			Container source = snapshot.container();
			for (int slot = 0; slot < snapshot.contents().size(); slot++) {
				source.setItem(slot, snapshot.contents().get(slot).copy());
			}
			source.setChanged();
		}
		return false;
	}

	/** true = beschaeftigt. */
	public static boolean tick(Ctx c) {
		ServerLevel level = c.level();
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.pen.isEmpty()) {
			HerdingPlan active = activePlan(level, c.v().getUUID());
			if (active != null) cancelHerding(level, active, true);
			else recoverEntityPlan(c.v(), level);
			return false;
		}
		HerdingPlan active = activePlan(level, c.v().getUUID());
		if (active == null && planForVillage(level, ref.bell()) != null) return false;
		if (active == null) {
			// The job class may first load after entity-load events during a server restart.
			// Recover any persisted interrupted transfer before starting another one.
			recoverEntityPlan(c.v(), level);
			recoverNearbyAnimalMarkers(level, ref.data().extra.pen.get());
		}
		if (active == null && c.now() < ref.data().extra.nextCare) {
			return false;
		}
		if (active != null) {
			if (!active.bell.equals(ref.bell())) {
				cancelHerding(level, active, true);
			} else if (continueHerding(c, ref, active)) {
				return true;
			}
		}
		BlockPos pen = ref.data().extra.pen.get();
		AABB area = PenExpansion.area(level, pen);
		// A drawn pasture restricts this pen; it never expands its query into another settlement's herd.
		// Re-read on every care tick so a zone edit also applies while the worker is approaching an animal.
		List<ZonePolicy.Zone> pastures = ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(z -> "pasture".equals(z.kind()) && ZonePolicy.validBounds(ref, z.kind(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
		List<Container> granaries = feedContainers(level, ref);
		int capacity = PenExpansion.capacity(level, ref);
		for (Species sp : SPECIES) {
			List<Animal> herd = level.getEntitiesOfClass(Animal.class, area,
				a -> a.isAlive() && a.getType() == sp.type() && inPasture(a, area, pastures)
					&& PenExpansion.contains(level, pen, a));
			List<Animal> adults = herd.stream().filter(a -> a.getAge() == 0 && !a.isInLove()).toList();
			int total = herd.size();
			int publicFeed = countFeed(granaries, sp.food());
			int privateFeed = JobUtil.count(c.v().getInventory(), s -> s.is(sp.food()));
			int availableFeed = publicFeed + privateFeed;
			if (!canBreedHerd(level, pen, adults, herd, availableFeed, capacity)) {
				continue;
			}
			BreedingPair pair = breedingPair(c, pen, adults, herd);
			if (pair == null) continue;
			c.setActivity("füttert " + sp.name());
			Anim.hold(c, sp.food());
			FeedingApproach approach = approachForFeeding(c, pair);
			if (approach == FeedingApproach.MOVING) return true;
			if (!takeFeedTransactionally(c, granaries, sp.food(), 2)) {
				// Preserve the exact source slots if another inventory change invalidated the preflight.
				return false;
			}
			for (Animal a : List.of(pair.first(), pair.second())) {
				a.setInLove(null);
				level.sendParticles(ParticleTypes.HEART, a.getX(), a.getY() + 1.0, a.getZ(), 3, 0.3, 0.3, 0.3, 0.0);
			}
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 60;
			ref.data().extra.nextCare = c.now() + 400;
			ref.dirty();
			return true;
		}
		// Complete an already-fed breeding pair before opening a route for another species.
		// If no penned pair and real food are ready, normal herd intake still gets its turn.
		if (tryHerdNearbyAnimals(c, ref, pen, area, pastures)) return true;
		ref.data().extra.nextCare = c.now() + 600;
		return false;
	}

	/**
	 * Selects a real, unpaired adult couple and a safe place for the worker to feed them.
	 * Villagers cannot reliably path through a closed fence gate, so penned animals are served
	 * from one of the clear cells just outside the 9-by-9 enclosure instead of sending the farmer
	 * toward a cow behind the fence and letting that unreachable target stall the care cycle.
	 */
	private static BreedingPair breedingPair(Ctx c, BlockPos pen, List<Animal> adults, List<Animal> herd) {
		BreedingPair best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		String bestKey = "";
		if (insidePen(c.level(), pen, c.v()) && adults.stream()
			.anyMatch(animal -> PenExpansion.sameSection(c.level(), pen, c.v(), animal))) {
			for (int firstIndex = 0; firstIndex < adults.size(); firstIndex++) {
				Animal first = adults.get(firstIndex);
				if (!PenExpansion.sameSection(c.level(), pen, c.v(), first)) continue;
				for (int secondIndex = firstIndex + 1; secondIndex < adults.size(); secondIndex++) {
					Animal second = adults.get(secondIndex);
					if (!breedingPairEligible(c.level(), pen, first, second, herd)) continue;
					double score = Math.max(c.v().position().distanceToSqr(first.position()), c.v().position().distanceToSqr(second.position()));
					String key = pairKey(first, second);
					if (betterPair(score, key, bestScore, bestKey)) {
						best = new BreedingPair(first, second, null);
						bestScore = score;
						bestKey = key;
					}
				}
			}
			return best;
		}

		ServerLevel level = c.level();
		Vec3 currentStand = JobUtil.safeSpot(level, Vec3.atBottomCenterOf(c.v().blockPosition()));
		if (currentStand != null && c.v().position().distanceToSqr(currentStand)
			<= PEN_FEED_STAND_REACH * PEN_FEED_STAND_REACH) {
			BreedingPair alreadyReachable = closestPairAtStand(level, pen, adults, herd, currentStand);
			if (alreadyReachable != null) return alreadyReachable;
		}
		List<Vec3> stands = PenExpansion.feedStands(level, pen).stream()
			.map(position -> JobUtil.safeSpot(level, Vec3.atBottomCenterOf(position)))
			.filter(java.util.Objects::nonNull).distinct().toList();
		for (int firstIndex = 0; firstIndex < adults.size(); firstIndex++) {
			Animal first = adults.get(firstIndex);
			for (int secondIndex = firstIndex + 1; secondIndex < adults.size(); secondIndex++) {
				Animal second = adults.get(secondIndex);
				if (!breedingPairEligible(level, pen, first, second, herd)) continue;
				String key = pairKey(first, second);
				for (Vec3 stand : stands) {
					double farthestAnimal = Math.max(stand.distanceToSqr(first.position()), stand.distanceToSqr(second.position()));
					// Allow a small approach tolerance while keeping both adults within feeding distance.
					if (farthestAnimal > PEN_FEED_PAIR_STAND_MAX_DISTANCE * PEN_FEED_PAIR_STAND_MAX_DISTANCE) continue;
					double score = farthestAnimal * 1000.0 + c.v().position().distanceToSqr(stand);
					String candidateKey = key + "@" + BlockPos.containing(stand).asLong();
					if (betterPair(score, candidateKey, bestScore, bestKey)) {
						best = new BreedingPair(first, second, stand);
						bestScore = score;
						bestKey = candidateKey;
					}
				}
			}
		}
		return best;
	}

	private static boolean breedingPairEligible(ServerLevel level, BlockPos pen, Animal first, Animal second,
		List<Animal> herd) {
		return PenExpansion.sameSection(level, pen, first, second)
			&& sectionCount(level, pen, first, herd) < PenExpansion.sectionCapacity(level, pen, first);
	}

	private static long sectionCount(ServerLevel level, BlockPos pen, Animal animal, List<Animal> herd) {
		return herd.stream().filter(member -> PenExpansion.sameSection(level, pen, animal, member)).count();
	}

	private static BreedingPair closestPairAtStand(ServerLevel level, BlockPos pen, List<Animal> adults,
		List<Animal> herd, Vec3 stand) {
		BreedingPair best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		String bestKey = "";
		for (int firstIndex = 0; firstIndex < adults.size(); firstIndex++) {
			Animal first = adults.get(firstIndex);
			for (int secondIndex = firstIndex + 1; secondIndex < adults.size(); secondIndex++) {
				Animal second = adults.get(secondIndex);
				if (!breedingPairEligible(level, pen, first, second, herd)) continue;
				double farthestAnimal = Math.max(stand.distanceToSqr(first.position()), stand.distanceToSqr(second.position()));
				if (farthestAnimal > PEN_FEED_PAIR_STAND_MAX_DISTANCE * PEN_FEED_PAIR_STAND_MAX_DISTANCE) continue;
				String key = pairKey(first, second);
				if (betterPair(farthestAnimal, key, bestScore, bestKey)) {
					best = new BreedingPair(first, second, stand);
					bestScore = farthestAnimal;
					bestKey = key;
				}
			}
		}
		return best;
	}

	private static FeedingApproach approachForFeeding(Ctx c, BreedingPair pair) {
		Vec3 target = pair.exteriorStand() == null
			? pair.first().position().add(pair.second().position()).scale(0.5)
			: pair.exteriorStand();
		double reach = pair.exteriorStand() == null ? 1.5 : PEN_FEED_STAND_REACH;
		if (!JobUtil.approach(c, target, reach)) return FeedingApproach.MOVING;
		if (c.v().position().distanceToSqr(pair.first().position()) > PEN_FEEDING_REACH * PEN_FEEDING_REACH
			|| c.v().position().distanceToSqr(pair.second().position()) > PEN_FEEDING_REACH * PEN_FEEDING_REACH) {
			// Entity movement can shift a couple after stand selection; recompute on the next care tick.
			return FeedingApproach.MOVING;
		}
		return FeedingApproach.READY;
	}

	private static boolean betterPair(double score, String key, double bestScore, String bestKey) {
		return score < bestScore || Double.compare(score, bestScore) == 0 && (bestKey.isEmpty() || key.compareTo(bestKey) < 0);
	}

	private static String pairKey(Animal first, Animal second) {
		String firstId = first.getUUID().toString();
		String secondId = second.getUUID().toString();
		return firstId.compareTo(secondId) <= 0 ? firstId + ":" + secondId : secondId + ":" + firstId;
	}

	/** Starts one bounded herd transfer when a supported breeding pair is missing from the registered pen. */
	private static boolean tryHerdNearbyAnimals(Ctx c, VillageRef ref, BlockPos pen, AABB area,
		List<ZonePolicy.Zone> pastures) {
		ServerLevel level = c.level();
		Map<UUID, HerdingPlan> activePlans = HERDING_PLANS.get(level);
		if (activePlans != null && activePlans.size() >= MAX_HERDING_PLANS_PER_LEVEL) return false;
		List<BlockPos> nearbyBells = herdingBellsNear(level, pen, ref.bell(),
			Config.VILLAGE_RADIUS + HERDING_SEARCH_RADIUS);
		if (!nearbyBells.contains(ref.bell())) return false;
		PenGate gate = findPenGate(level, pen, pastures);
		if (gate == null) return false;
		AABB sourceArea = new AABB(pen).inflate(HERDING_SEARCH_RADIUS, 4, HERDING_SEARCH_RADIUS);
		boolean annexTarget = PenExpansion.isExpanded(level, pen, pastures);
		int effectiveCapacity = PenExpansion.capacity(level, ref);
		for (EntityType<? extends Animal> type : HERD_TYPES) {
			List<Animal> penned = level.getEntitiesOfClass(Animal.class, area,
				a -> a.isAlive() && a.getType() == type && insidePen(level, pen, a)
				&& inPasture(a, area, pastures));
			if (penned.size() >= effectiveCapacity) continue;
			List<Animal> destinationHerd = penned.stream()
				.filter(a -> PenExpansion.inAnnex(level, pen, a) == annexTarget).toList();
			int sectionCapacity = annexTarget ? effectiveCapacity - PEN_CAPACITY_PER_SPECIES : PEN_CAPACITY_PER_SPECIES;
			if (destinationHerd.size() >= sectionCapacity) continue;
			long insideAdults = destinationHerd.stream().filter(a -> !a.isBaby()).count();
			if (insideAdults >= 2) continue;
			List<Animal> candidates = level.getEntitiesOfClass(Animal.class, sourceArea,
				a -> a.getType() == type && herdable(a) && !insidePen(level, pen, a)
					&& !hasActiveAnimalPlan(level, a.getUUID())
					&& inPastureZone(a, pastures) && belongsToVillage(level, ref, a, nearbyBells))
				.stream().sorted(Comparator.comparingDouble(a -> a.distanceToSqr(gate.position().getX() + 0.5,
					gate.position().getY(), gate.position().getZ() + 0.5))).toList();
			if (candidates.isEmpty()) continue;
			Vec3 stand = herderStand(level, gate);
			if (stand == null) continue;
			// Move the worker out of the animal's approach corridor before asking vanilla navigation
			// for a through-gate path. Entity collisions are part of that path check, so a farmer beside
			// a cow can otherwise make a physically open entrance look unreachable.
			if (c.v().position().distanceToSqr(stand) > 1.8 * 1.8) {
				c.setActivity("stellt sich zum Treiben neben das Gehege");
				JobUtil.approach(c, stand, 1.8);
				return true;
			}
			for (Animal candidate : candidates) {
				BlockState originalGateState = level.getBlockState(gate.position());
				if (!(originalGateState.getBlock() instanceof FenceGateBlock)) continue;
				boolean ownsGate = !originalGateState.getValue(FenceGateBlock.OPEN);
				List<PassageBlock> passageBlocks = captureGatePassage(level, gate);
				if (passageBlocks.isEmpty()) continue;
				HerdingPlan plan = new HerdingPlan(ref.bell(), gate.position(), c.v().getUUID(), candidate.getUUID(),
					c.now(), ownsGate, passageBlocks);
				c.v().addTag(planTag(plan));
				List<String> recoveries = passageRecoveryTags(passageBlocks);
				for (String recovery : recoveries) {
					c.v().addTag(recovery);
					candidate.addTag(recovery);
				}
				// Herding now owns this mob's route. Stop its unrelated vanilla goal path before
				// opening a closed gate, whose real collision update would otherwise schedule a replan.
				candidate.getNavigation().stop();
				if (ownsGate) {
					if (!setGateOpen(level, gate.position(), true)) {
						boolean gateSettled = settlePassage(level, passageBlocks);
						c.v().removeTag(planTag(plan));
						if (gateSettled) {
							removeTags(c.v(), recoveries);
							removeTags(candidate, recoveries);
						}
						return false;
					}
				}

				// Do not let one penned-off or terrain-blocked animal monopolize every care cycle.
				// Vanilla classifies even an open gate as FENCE on this game version, so construct only
				// a collision-checked per-animal crossing from the ordinary outer approach path.
				var path = createPathThroughGate(level, candidate, gate.position(), gate.inside(), passageBlocks);
				if (!isBoundedGatePath(path, gate.position(), gate.inside())) {
					boolean gateSettled = settlePassage(level, passageBlocks);
					c.v().removeTag(planTag(plan));
					if (gateSettled) {
						removeTags(c.v(), recoveries);
						removeTags(candidate, recoveries);
					} else {
						// Keep the recovery markers and stop here; a later load will retry closing the gate.
						return false;
					}
					continue;
				}

				plans(level).put(plan.herder, plan);
				ref.data().extra.nextCare = c.now() + 40;
				ref.dirty();
				return continueHerding(c, ref, plan);
			}
		}
		return false;
	}

	/** Keeps the villager outside the entrance while the server-side animal path goes through the open gate. */
	private static boolean continueHerding(Ctx c, VillageRef ref, HerdingPlan plan) {
		ServerLevel level = c.level();
		Entity entity = level.getEntity(plan.animal);
		if (!(entity instanceof Animal animal) || !animal.isAlive()
			|| c.now() - plan.startedAt > HERDING_TIMEOUT_TICKS
			|| !ref.bell().equals(plan.bell)
			|| !herdable(animal)
			|| !belongsToVillage(level, ref, animal,
				herdingBellsNear(level, animal.blockPosition(), ref.bell(), Config.VILLAGE_RADIUS))) {
			cancelHerding(level, plan, true);
			return false;
		}
		BlockPos pen = ref.data().extra.pen.orElse(null);
		if (pen == null) {
			cancelHerding(level, plan, true);
			return false;
		}
		AABB area = PenExpansion.area(level, pen);
		List<ZonePolicy.Zone> pastures = validPastures(ref);
		PenGate gate = findPenGate(level, pen, pastures);
		if (gate != null && insidePen(level, pen, animal)
			&& PenExpansion.inAnnex(level, pen, animal) == PenExpansion.isExpanded(level, pen, pastures)
			&& inPasture(animal, area, pastures)) {
			finishHerding(level, plan);
			return false;
		}
		if (!inPastureZone(animal, pastures)) {
			cancelHerding(level, plan, true);
			return false;
		}
		if (gate == null || !gate.position().equals(plan.gate)
			|| !level.getBlockState(gate.position()).getValue(FenceGateBlock.OPEN)) {
			cancelHerding(level, plan, true);
			return false;
		}
		Vec3 stand = herderStand(level, gate);
		if (stand == null) {
			cancelHerding(level, plan, true);
			return false;
		}
		c.setActivity("treibt " + herdName(animal) + " in den Stall");
		Anim.hold(c, foodFor(animal));
		JobUtil.approach(c, stand, 1.8);
		ref.data().extra.nextCare = c.now() + 20;
		ref.dirty();
		return true;
	}

	private static void tickHerdingPlans(ServerLevel level) {
		Map<UUID, HerdingPlan> plans = HERDING_PLANS.get(level);
		if (plans == null || plans.isEmpty()) return;
		long now = level.getGameTime();
		for (HerdingPlan plan : List.copyOf(plans.values())) {
			if (now - plan.startedAt > HERDING_TIMEOUT_TICKS) {
				cancelHerding(level, plan, true);
				continue;
			}
			Entity herderEntity = level.getEntity(plan.herder);
			Entity animalEntity = level.getEntity(plan.animal);
			if (!(herderEntity instanceof Villager herder) || !herder.isAlive()
				|| !(animalEntity instanceof Animal animal) || !animal.isAlive()) {
				cancelHerding(level, plan, true);
				continue;
			}
			if (!level.hasChunkAt(plan.bell) || !(level.getBlockEntity(plan.bell) instanceof BellBlockEntity bellEntity)) {
				cancelHerding(level, plan, true);
				continue;
			}
			VillageData data = bellEntity.getAttached(VillagerPlus.VILLAGE);
			if (data == null || data.extra.pen.isEmpty()) {
				cancelHerding(level, plan, true);
				continue;
			}
			VillageRef ref = new VillageRef(plan.bell, data, bellEntity);
			if (!herdable(animal)
				|| !belongsToVillage(level, ref, animal,
					herdingBellsNear(level, animal.blockPosition(), plan.bell, Config.VILLAGE_RADIUS))) {
				cancelHerding(level, plan, true);
				continue;
			}
			BlockPos pen = data.extra.pen.get();
			AABB area = PenExpansion.area(level, pen);
			List<ZonePolicy.Zone> pastures = validPastures(ref);
			PenGate gate = findPenGate(level, pen, pastures);
			if (gate != null && insidePen(level, pen, animal)
				&& PenExpansion.inAnnex(level, pen, animal) == PenExpansion.isExpanded(level, pen, pastures)
				&& inPasture(animal, area, pastures)) {
				finishHerding(level, plan);
				continue;
			}
			if (!inPastureZone(animal, pastures) || gate == null || !gate.position().equals(plan.gate)
				|| !level.getBlockState(gate.position()).getValue(FenceGateBlock.OPEN)) {
				cancelHerding(level, plan, true);
				continue;
			}
			var navigation = animal.getNavigation();
			// Keep an active vanilla path intact; restarting it every care interval made long
			// approaches crawl. Rebuild only after navigation stops, always to the pen's inner gate cell.
			if (navigation.isDone()) {
				BlockPos inside = gate.inside();
				var path = createPathThroughGate(level, animal, gate.position(), inside, plan.passageBlocks);
				if (!isBoundedGatePath(path, gate.position(), inside) || !navigation.moveTo(path, 1.0)) {
					cancelHerding(level, plan, true);
					continue;
				}
			}
		}
		if (plans.isEmpty()) HERDING_PLANS.remove(level);
	}

	private static void onEntityUnload(Entity entity, ServerLevel level) {
		Map<UUID, HerdingPlan> plans = HERDING_PLANS.get(level);
		if (plans != null) {
			for (HerdingPlan plan : List.copyOf(plans.values())) {
				if (plan.herder.equals(entity.getUUID()) || plan.animal.equals(entity.getUUID())) {
					cancelHerding(level, plan, true);
				}
			}
		}
		recoverEntityPlan(entity, level);
	}

	/** A crash/reload or entity unload must not leave a job-opened gate permanently open. */
	private static void recoverEntityPlan(Entity entity, ServerLevel level) {
		if (entity instanceof Animal animal && HERD_TYPES.contains(animal.getType())) {
			INITIAL_PEN_ELIGIBILITY.remove(level);
		}
		for (String tag : List.copyOf(entity.entityTags())) {
			if (tag.startsWith(HERDING_GATE_TAG)) {
				PassageBlock recovery = parsePassageRecoveryTag(level, tag);
				BlockPos legacyGate = recovery == null && tag.indexOf('|', HERDING_GATE_TAG.length()) < 0
					? parsePositionTag(tag, HERDING_GATE_TAG) : null;
				boolean settled = recovery != null ? settlePassageBlock(level, recovery)
					: legacyGate != null && closeRecoveryGate(level, legacyGate);
				if (settled) entity.removeTag(tag);
			} else if (tag.startsWith(HERDING_PLAN_TAG)) {
				HerdingPlan stale = parsePlanTag(level, tag);
				if (stale != null) cancelHerding(level, stale, true);
				if (stale != null) entity.removeTag(tag);
			}
		}
	}

	private static void recoverNearbyAnimalMarkers(ServerLevel level, BlockPos pen) {
		AABB area = new AABB(pen).inflate(HERDING_SEARCH_RADIUS, 4, HERDING_SEARCH_RADIUS);
		for (Animal animal : level.getEntitiesOfClass(Animal.class, area, a -> true)) {
			if (hasActiveAnimalPlan(level, animal.getUUID())) continue;
			for (String tag : List.copyOf(animal.entityTags())) {
				if (!tag.startsWith(HERDING_GATE_TAG)) continue;
				PassageBlock recovery = parsePassageRecoveryTag(level, tag);
				BlockPos legacyGate = recovery == null && tag.indexOf('|', HERDING_GATE_TAG.length()) < 0
					? parsePositionTag(tag, HERDING_GATE_TAG) : null;
				boolean settled = recovery != null ? settlePassageBlock(level, recovery)
					: legacyGate != null && closeRecoveryGate(level, legacyGate);
				if (settled) animal.removeTag(tag);
			}
		}
	}

	private static boolean belongsToVillage(ServerLevel level, VillageRef ref, Animal animal, List<BlockPos> nearbyBells) {
		long ownDistance = horizontalDistanceSquared(ref.bell(), animal.blockPosition());
		if (ownDistance > (long)Config.VILLAGE_RADIUS * Config.VILLAGE_RADIUS || !nearbyBells.contains(ref.bell())) return false;
		for (BlockPos bell : nearbyBells) {
			if (bell.equals(ref.bell())) continue;
			if (horizontalDistanceSquared(bell, animal.blockPosition()) <= ownDistance) return false;
		}
		return !isInOtherRegisteredPen(level, ref, animal, nearbyBells);
	}

	/** The village-location query deliberately excludes its center; herd ownership must include the working village. */
	private static List<BlockPos> herdingBellsNear(ServerLevel level, BlockPos center, BlockPos ownBell, int radius) {
		List<BlockPos> bells = new ArrayList<>(Villages.knownBellsNear(level, center, radius));
		if (!bells.contains(ownBell)) bells.add(ownBell.immutable());
		return List.copyOf(bells);
	}

	private static boolean isInOtherRegisteredPen(ServerLevel level, VillageRef ref, Animal animal, List<BlockPos> nearbyBells) {
		for (BlockPos bell : nearbyBells) {
			if (bell.equals(ref.bell()) || !level.hasChunkAt(bell)) continue;
			var entity = level.getBlockEntity(bell);
			if (entity == null) continue;
			VillageData other = entity.getAttached(VillagerPlus.VILLAGE);
			if (other == null || other.extra.pen.isEmpty()) continue;
			if (new AABB(other.extra.pen.get()).inflate(5, 3, 5).contains(animal.position())) return true;
		}
		return false;
	}

	private static long horizontalDistanceSquared(BlockPos a, BlockPos b) {
		long dx = (long)a.getX() - b.getX();
		long dz = (long)a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	private static boolean herdable(Animal animal) {
		return animal.isAlive() && !animal.isBaby() && !animal.isInLove() && !animal.isLeashed()
			&& !animal.isPassenger() && !animal.isVehicle() && !animal.hasCustomName()
			&& HERD_TYPES.contains(animal.getType())
			&& animal.entityTags().stream().noneMatch(tag -> tag.startsWith("vp_") && !tag.startsWith(HERDING_GATE_TAG));
	}

	static boolean isBoundedHerdingPath(Path path, BlockPos target) {
		return path != null && path.canReach() && path.getNodeCount() > 0
			&& path.getNodeCount() <= HERDING_MAX_PATH_NODES && target.equals(path.getTarget());
	}

	static boolean isBoundedGatePath(Path path, BlockPos gate, BlockPos target) {
		if (!isBoundedHerdingPath(path, target)) return false;
		for (int i = 0; i < path.getNodeCount(); i++) {
			if (gate.equals(path.getNodePos(i))) return true;
		}
		return false;
	}

	private static boolean isReusableGatePath(Path path, BlockPos outside, BlockPos gate, BlockPos target) {
		if (!isBoundedGatePath(path, gate, target) || path.getNodeCount() < 3) return false;
		int last = path.getNodeCount() - 1;
		if (!outside.equals(path.getNodePos(last - 2)) || !gate.equals(path.getNodePos(last - 1))
			|| !target.equals(path.getNodePos(last))) return false;
		for (int i = 0; i < last - 1; i++) {
			if (gate.equals(path.getNodePos(i)) || target.equals(path.getNodePos(i))) return false;
		}
		return true;
	}

	private static boolean inPastureZone(Animal animal, List<ZonePolicy.Zone> pastures) {
		if (pastures.isEmpty()) return true;
		return pastures.stream().anyMatch(z -> animal.getX() >= z.minX()
			&& animal.getX() < (double)z.maxX() + 1 && animal.getZ() >= z.minZ()
			&& animal.getZ() < (double)z.maxZ() + 1);
	}

	private static List<ZonePolicy.Zone> validPastures(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(z -> "pasture".equals(z.kind()) && ZonePolicy.validBounds(ref, z.kind(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
	}

	private static boolean insidePen(ServerLevel level, BlockPos pen, Entity entity) {
		return PenExpansion.contains(level, pen, entity);
	}

	private static PenGate findPenGate(ServerLevel level, BlockPos pen, List<ZonePolicy.Zone> pastures) {
		PenExpansion.Gate gate = PenExpansion.herdingGate(level, pen, pastures);
		if (gate == null || !level.hasChunkAt(gate.inside()) || !level.hasChunkAt(gate.outside())) return null;
		return new PenGate(gate.position(), gate.outward());
	}

	private static Vec3 herderStand(ServerLevel level, PenGate gate) {
		for (Direction side : List.of(gate.outward().getClockWise(), gate.outward().getCounterClockWise())) {
			// Stand three blocks to one side of the lane, just beyond the entrance. The closer
			// two-block position can still obstruct vanilla's wide-mob path search at the gate.
			BlockPos position = gate.outside().relative(side, 3);
			Vec3 stand = JobUtil.safeSpot(level, Vec3.atBottomCenterOf(position));
			if (stand != null) return stand;
		}
		return null;
	}

	/** Snapshots the gate and connected fence/wall posts so interrupted herding can restore its exact passage. */
	private static List<PassageBlock> captureGatePassage(ServerLevel level, PenGate gate) {
		if (!level.hasChunkAt(gate.position())) return List.of();
		BlockState gateState = level.getBlockState(gate.position());
		if (!(gateState.getBlock() instanceof FenceGateBlock)) return List.of();
		List<PassageBlock> blocks = new ArrayList<>();
		for (Direction side : List.of(gate.outward().getClockWise(), gate.outward().getCounterClockWise())) {
			BlockPos sidePos = gate.position().relative(side);
			if (!level.hasChunkAt(sidePos)) return List.of();
			BlockState sideState = level.getBlockState(sidePos);
			if (sideState.is(BlockTags.FENCES) || sideState.is(BlockTags.WALLS)) {
				blocks.add(new PassageBlock(sidePos, sideState, false));
			}
		}
		blocks.add(new PassageBlock(gate.position(), gateState, !gateState.getValue(FenceGateBlock.OPEN)));
		return List.copyOf(blocks);
	}

	static Path createPathThroughGate(ServerLevel level, Animal animal, BlockPos gate, BlockPos target) {
		if (level == null || gate == null) return null;
		PenGate penGate = findGateAt(level, gate);
		return penGate == null ? null : createPathThroughGate(level, animal, gate, target, captureGatePassage(level, penGate));
	}

	private static Path createPathThroughGate(ServerLevel level, Animal animal, BlockPos gate, BlockPos target,
		List<PassageBlock> plannedPassage) {
		if (level == null || animal == null || gate == null || target == null || plannedPassage == null
			|| plannedPassage.isEmpty() || plannedPassage.size() > 3
			|| !level.hasChunkAt(gate) || !level.hasChunkAt(target)) return null;
		BlockState openGate = level.getBlockState(gate);
		if (!(openGate.getBlock() instanceof FenceGateBlock) || !openGate.getValue(FenceGateBlock.OPEN)
			|| !gate.relative(openGate.getValue(FenceGateBlock.FACING).getOpposite()).equals(target)) return null;
		List<PassageBlock> passage = new ArrayList<>(plannedPassage.size());
		List<BlockPos> validPassagePositions = List.of(gate,
			gate.relative(openGate.getValue(FenceGateBlock.FACING).getClockWise()),
			gate.relative(openGate.getValue(FenceGateBlock.FACING).getCounterClockWise()));
		boolean includesGate = false;
		for (PassageBlock planned : plannedPassage) {
			BlockPos position = planned.position();
			if (!validPassagePositions.contains(position) || !level.hasChunkAt(position)
				|| passage.stream().anyMatch(block -> block.position().equals(position))) return null;
			BlockState current = level.getBlockState(position);
			if (position.equals(gate)) {
				if (current.getBlock() != openGate.getBlock() || !current.getValue(FenceGateBlock.OPEN)) return null;
				includesGate = true;
			} else if (!(current.is(BlockTags.FENCES) || current.is(BlockTags.WALLS))
				|| !current.equals(planned.state())) {
				return null;
			}
			passage.add(new PassageBlock(position, current, planned.owned()));
		}
		if (!includesGate) return null;
		var navigation = animal.getNavigation();
		BlockPos outside = gate.relative(openGate.getValue(FenceGateBlock.FACING));
		if (!level.hasChunkAt(outside) || !isClearGateLane(level, animal, navigation, outside, gate, target)) return null;
		// After an earlier custom route, vanilla still remembers the outside cell as its
		// navigation target. createPath(outside) then returns the active through-gate path
		// from its cache; reuse that exact, structurally verified route instead of treating
		// it as a plain approach and rejecting its already-appended gate nodes.
		Path activePath = navigation.getPath();
		if (navigation.isInProgress() && isReusableGatePath(activePath, outside, gate, target)) return activePath;
		// Vanilla 26.2 finds an ordinary route to the near side, but classifies even the open
		// gate as FENCE. Append only the two verified cells that cross this one open gate.
		Path approach = navigation.createPath(outside, 0);
		if (!isBoundedHerdingPath(approach, outside) || approach.getEndNode() == null
			|| approach.getNodeCount() > HERDING_MAX_PATH_NODES - 2
			|| !outside.equals(approach.getEndNode().asBlockPos())) return null;
		for (int i = 0; i < approach.getNodeCount(); i++) {
			BlockPos node = approach.getNodePos(i);
			if (gate.equals(node) || target.equals(node)) return null;
		}
		List<Node> nodes = new ArrayList<>(approach.getNodeCount() + 2);
		for (int i = 0; i < approach.getNodeCount(); i++) nodes.add(approach.getNode(i));
		nodes.add(walkableNode(gate));
		nodes.add(walkableNode(target));
		return new Path(nodes, target.immutable(), true);
	}

	private static Node walkableNode(BlockPos position) {
		Node node = new Node(position.getX(), position.getY(), position.getZ());
		node.type = PathType.WALKABLE;
		return node;
	}

	private static boolean isClearGateLane(ServerLevel level, Animal animal,
		PathNavigation navigation, BlockPos outside, BlockPos gate, BlockPos inside) {
		if (!navigation.isStableDestination(outside) || !navigation.isStableDestination(gate)
			|| !navigation.isStableDestination(inside)) return false;
		return hasCollisionFreeSegment(level, animal, outside, gate)
			&& hasCollisionFreeSegment(level, animal, gate, inside);
	}

	private static boolean hasCollisionFreeSegment(ServerLevel level, Animal animal, BlockPos from, BlockPos to) {
		double startX = from.getX() + 0.5;
		double startY = from.getY();
		double startZ = from.getZ() + 0.5;
		double deltaX = to.getX() + 0.5 - startX;
		double deltaY = to.getY() - startY;
		double deltaZ = to.getZ() + 0.5 - startZ;
		int steps = Math.max(1, (int)Math.ceil(Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ) * 16.0));
		AABB originalBounds = animal.getBoundingBox();
		for (int step = 0; step <= steps; step++) {
			double amount = (double)step / steps;
			double x = startX + deltaX * amount;
			double y = startY + deltaY * amount;
			double z = startZ + deltaZ * amount;
			BlockPos sampleCell = BlockPos.containing(x, y, z);
			if (!level.hasChunkAt(sampleCell) || !level.hasChunkAt(sampleCell.above())) return false;
			AABB bounds = originalBounds.move(x - animal.getX(), y - animal.getY(), z - animal.getZ());
			if (!level.noCollision(animal, bounds)) return false;
		}
		return true;
	}

	private static PenGate findGateAt(ServerLevel level, BlockPos gate) {
		if (level == null || gate == null || !level.hasChunkAt(gate)
			|| !(level.getBlockState(gate).getBlock() instanceof FenceGateBlock)) return null;
		return new PenGate(gate.immutable(), level.getBlockState(gate).getValue(FenceGateBlock.FACING));
	}

	private static Item foodFor(Animal animal) {
		if (animal.getType() == EntityTypes.PIG) return Items.CARROT;
		if (animal.getType() == EntityTypes.CHICKEN) return Items.WHEAT_SEEDS;
		return Items.WHEAT;
	}

	private static String herdName(Animal animal) {
		if (animal.getType() == EntityTypes.PIG) return "Schweine";
		if (animal.getType() == EntityTypes.CHICKEN) return "Hühner";
		return animal.getType() == EntityTypes.SHEEP ? "Schafe" : "Kühe";
	}

	private static boolean setGateOpen(ServerLevel level, BlockPos position, boolean open) {
		if (!level.hasChunkAt(position)) return false;
		var state = level.getBlockState(position);
		if (!(state.getBlock() instanceof FenceGateBlock)) return false;
		if (state.getValue(FenceGateBlock.OPEN) == open) return true;
		return level.setBlock(position, state.setValue(FenceGateBlock.OPEN, open), 3);
	}

	private static boolean settlePassage(ServerLevel level, List<PassageBlock> passage) {
		boolean settled = true;
		for (int i = passage.size() - 1; i >= 0; i--) {
			settled &= settlePassageBlock(level, passage.get(i));
		}
		return settled;
	}

	/** Restores a temporarily removed block, but leaves any non-air player replacement untouched. */
	private static boolean settlePassageBlock(ServerLevel level, PassageBlock passage) {
		BlockPos position = passage.position();
		if (!level.hasChunkAt(position)) return false;
		BlockState originalState = passage.state();
		BlockState current = level.getBlockState(position);
		if (originalState == null) return !passage.owned() || closeRecoveryGate(level, position);
		if (current.equals(originalState)) return true;
		if (current.isAir()) {
			level.setBlock(position, originalState, 3);
			return level.getBlockState(position).equals(originalState);
		}
		if (current.getBlock() != originalState.getBlock() || !passage.owned()) return true;
		level.setBlock(position, originalState, 3);
		return level.getBlockState(position).equals(originalState);
	}

	/** Keeps recovery tags until an unloaded gate can be closed on a later entity/chunk load. */
	private static boolean closeRecoveryGate(ServerLevel level, BlockPos position) {
		if (position == null) return true;
		if (!level.hasChunkAt(position)) return false;
		var state = level.getBlockState(position);
		if (!(state.getBlock() instanceof FenceGateBlock) || !state.getValue(FenceGateBlock.OPEN)) return true;
		level.setBlock(position, state.setValue(FenceGateBlock.OPEN, false), 3);
		var current = level.getBlockState(position);
		return !(current.getBlock() instanceof FenceGateBlock) || !current.getValue(FenceGateBlock.OPEN);
	}

	private static Map<UUID, HerdingPlan> plans(ServerLevel level) {
		return HERDING_PLANS.computeIfAbsent(level, ignored -> new HashMap<>());
	}

	private static boolean hasActiveAnimalPlan(ServerLevel level, UUID animal) {
		Map<UUID, HerdingPlan> plans = HERDING_PLANS.get(level);
		return plans != null && plans.values().stream().anyMatch(plan -> plan.animal.equals(animal));
	}

	private static HerdingPlan activePlan(ServerLevel level, UUID herder) {
		Map<UUID, HerdingPlan> plans = HERDING_PLANS.get(level);
		return plans == null ? null : plans.get(herder);
	}

	private static HerdingPlan planForVillage(ServerLevel level, BlockPos bell) {
		Map<UUID, HerdingPlan> plans = HERDING_PLANS.get(level);
		return plans == null ? null : plans.values().stream().filter(plan -> plan.bell.equals(bell)).findFirst().orElse(null);
	}

	private static String planTag(HerdingPlan plan) {
		String base = HERDING_PLAN_TAG + plan.bell.asLong() + "|" + plan.gate.asLong() + "|" + plan.herder + "|"
			+ plan.animal + "|" + plan.startedAt + "|" + plan.ownsGate;
		return base + "|" + encodePassage(plan.passageBlocks);
	}

	private static List<String> passageRecoveryTags(List<PassageBlock> passage) {
		return passage.stream().map(Livestock::passageRecoveryTag).toList();
	}

	private static String passageRecoveryTag(PassageBlock passage) {
		if (passage.state() == null) return HERDING_GATE_TAG + passage.position().asLong();
		return HERDING_GATE_TAG + passage.position().asLong() + "|" + passage.owned()
			+ "|" + BlockStateParser.serialize(passage.state());
	}

	private static void removeTags(Entity entity, List<String> tags) {
		for (String tag : tags) entity.removeTag(tag);
	}

	private static String encodePassage(List<PassageBlock> passage) {
		return passage.stream().map(block -> block.position().asLong() + "~" + block.owned() + "~"
			+ (block.state() == null ? "" : BlockStateParser.serialize(block.state())))
			.collect(java.util.stream.Collectors.joining(";"));
	}

	private static List<PassageBlock> decodePassage(ServerLevel level, String encoded) {
		if (encoded.isEmpty()) return List.of();
		List<PassageBlock> passage = new ArrayList<>();
		try {
			for (String entry : encoded.split(";", -1)) {
				String[] parts = entry.split("~", 3);
				if (parts.length != 3 || !(parts[1].equals("true") || parts[1].equals("false")) || parts[2].isEmpty()) return List.of();
				BlockState state = parseBlockState(level, parts[2]);
				if (state == null) return List.of();
				passage.add(new PassageBlock(BlockPos.of(Long.parseLong(parts[0])), state, Boolean.parseBoolean(parts[1])));
			}
		} catch (IllegalArgumentException ignored) {
			return List.of();
		}
		return List.copyOf(passage);
	}

	private static BlockState parseBlockState(ServerLevel level, String encoded) {
		try {
			return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), encoded, false).blockState();
		} catch (Exception ignored) {
			return null;
		}
	}

	private static HerdingPlan parsePlanTag(ServerLevel level, String tag) {
		try {
			String[] parts = tag.substring(HERDING_PLAN_TAG.length()).split("\\|");
			if (parts.length != 6 && parts.length != 7) return null;
			BlockPos bell = BlockPos.of(Long.parseLong(parts[0]));
			BlockPos gate = BlockPos.of(Long.parseLong(parts[1]));
			if (!(parts[5].equals("true") || parts[5].equals("false"))) return null;
			boolean ownsGate = Boolean.parseBoolean(parts[5]);
			List<PassageBlock> passage;
			if (parts.length == 7 && parts[6].contains("~")) {
				passage = decodePassage(level, parts[6]);
			} else if (parts.length == 7) {
				// Older plans persisted only the gate state as comma-separated properties.
				BlockState oldGateState = decodeGateState(parts[6]);
				if (oldGateState == null) return null;
				passage = List.of(new PassageBlock(gate, oldGateState, ownsGate));
			} else {
				// Pre-snapshot plans still recover safely by closing a gate they originally opened.
				passage = List.of(new PassageBlock(gate, null, ownsGate));
			}
			if (passage.isEmpty()) return null;
			return new HerdingPlan(bell, gate, UUID.fromString(parts[2]), UUID.fromString(parts[3]),
				Long.parseLong(parts[4]), ownsGate, passage);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static PassageBlock parsePassageRecoveryTag(ServerLevel level, String tag) {
		try {
			String[] parts = tag.substring(HERDING_GATE_TAG.length()).split("\\|", -1);
			BlockPos position = BlockPos.of(Long.parseLong(parts[0]));
			if (parts.length == 1) return new PassageBlock(position, null, true);
			if (parts.length != 3) return null;
			if (parts[1].equals("true") || parts[1].equals("false")) {
				BlockState original = parseBlockState(level, parts[2]);
				return original == null ? null : new PassageBlock(position, original, Boolean.parseBoolean(parts[1]));
			}
			// Backward compatibility for the former gate-only marker format.
			if (parts[2].equals("true") || parts[2].equals("false")) {
				BlockState original = decodeGateState(parts[1]);
				return original == null ? null : new PassageBlock(position, original, Boolean.parseBoolean(parts[2]));
			}
			return null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String encodeGateState(BlockState state) {
		if (state == null || !(state.getBlock() instanceof FenceGateBlock)) return "";
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "," + state.getValue(FenceGateBlock.FACING).getName()
			+ "," + state.getValue(FenceGateBlock.OPEN) + "," + state.getValue(FenceGateBlock.POWERED)
			+ "," + state.getValue(FenceGateBlock.IN_WALL);
	}

	private static BlockState decodeGateState(String encoded) {
		String[] parts = encoded.split(",", -1);
		if (parts.length != 5 || !(parts[2].equals("true") || parts[2].equals("false"))
			|| !(parts[3].equals("true") || parts[3].equals("false"))
			|| !(parts[4].equals("true") || parts[4].equals("false"))) return null;
		try {
			Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(parts[0])).orElse(null);
			Direction facing = Direction.byName(parts[1]);
			if (!(block instanceof FenceGateBlock) || facing == null || !facing.getAxis().isHorizontal()) return null;
			return block.defaultBlockState()
				.setValue(FenceGateBlock.FACING, facing)
				.setValue(FenceGateBlock.OPEN, Boolean.parseBoolean(parts[2]))
				.setValue(FenceGateBlock.POWERED, Boolean.parseBoolean(parts[3]))
				.setValue(FenceGateBlock.IN_WALL, Boolean.parseBoolean(parts[4]));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static BlockPos parsePositionTag(String tag, String prefix) {
		try {
			String position = tag.substring(prefix.length()).split("\\|", 2)[0];
			return BlockPos.of(Long.parseLong(position));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static void finishHerding(ServerLevel level, HerdingPlan plan) {
		removePlan(level, plan, false);
	}

	private static void cancelHerding(ServerLevel level, HerdingPlan plan, boolean stopAnimal) {
		removePlan(level, plan, stopAnimal);
	}

	private static void removePlan(ServerLevel level, HerdingPlan plan, boolean stopAnimal) {
		boolean passageSettled = settlePassage(level, plan.passageBlocks);
		Map<UUID, HerdingPlan> plans = HERDING_PLANS.get(level);
		if (plans != null) {
			plans.remove(plan.herder, plan);
			if (plans.isEmpty()) HERDING_PLANS.remove(level);
		}
		String savedPlanTag = planTag(plan);
		List<String> recoveries = passageRecoveryTags(plan.passageBlocks);
		Entity herder = level.getEntity(plan.herder);
		if (herder != null) {
			herder.removeTag(savedPlanTag);
			if (passageSettled) removeTags(herder, recoveries);
		}
		Entity animal = level.getEntity(plan.animal);
		if (animal != null) {
			if (passageSettled) removeTags(animal, recoveries);
			if (stopAnimal && animal instanceof Mob mob) mob.getNavigation().stop();
		}
	}

	static boolean inPasture(Animal animal, AABB pen, List<ZonePolicy.Zone> pastures) {
		if (!pen.contains(animal.position())) return false;
		if (pastures.isEmpty()) return true;
		// Entity queries use bounding-box intersection. Use the animal's center for parcel membership so
		// a cow leaning across a fence is not counted or fed from the neighboring parcel.
		return pastures.stream().anyMatch(z -> animal.getX() >= z.minX()
			&& animal.getX() < (double)z.maxX() + 1 && animal.getZ() >= z.minZ() && animal.getZ() < (double)z.maxZ() + 1);
	}
}
