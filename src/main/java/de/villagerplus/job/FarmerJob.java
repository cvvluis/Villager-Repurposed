package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Blueprints;
import de.villagerplus.compat.SeasonCompat;
import de.villagerplus.village.Balance;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Bauer: Vormittags ernten + neu pflanzen. Nachmittags Brot backen und neue Felder anlegen (wenn Saatgut in der Dorfkiste liegt).
 */
public class FarmerJob implements Job {
	private static final int CLUSTER_SEEDS = 15;
	private static final int STARTER_SEEDS = Config.FARM_MIN_SEEDS;
	private static final int VISIBLE_STARTER_FIELD_SEEDS = STARTER_SEEDS;
	private static final String STARTER_FARM_RETRY_TAG_PREFIX = "vp_farm_start_ready_v1|";
	private static final String STARTER_PEN_RETRY_TAG_PREFIX = "vp_pen_start_ready_v1|";
	private static final String FARM_WATER_PLAN_TAG_PREFIX = "vp_farm_water_v1|";
	private static final int CLUSTER_FENCES = 19;
	private static final int STARTER_FENCES = 15;
	private static final int MAX_CLUSTER_RADIUS = 36;
	private static final int STARTER_SEED_SEARCH_RADIUS = 32;
	private static final int STARTER_SEED_SEARCH_DIAMETER = STARTER_SEED_SEARCH_RADIUS * 2 + 1;
	private static final int MAX_STARTER_SEED_SEARCH_COLUMNS = STARTER_SEED_SEARCH_DIAMETER * STARTER_SEED_SEARCH_DIAMETER;
	private static final int FARM_WATER_SEARCH_RADIUS = 16;
	private static final String EXPANSION_TAG_PREFIX = "vp_farm_expansion_";
	private static final String LEGACY_EXPANSION_TAG_PREFIX = "vp_farm_expansion_v1|";
	private static final String CURRENT_EXPANSION_TAG_PREFIX = "vp_farm_expansion_v2|";
	private static final String STARTER_EXPANSION_TAG_PREFIX = "vp_farm_expansion_v3|";
	private static final int LEGACY_FENCE_STEPS = 16;
	private static final int LEGACY_WATER_STEP = LEGACY_FENCE_STEPS;
	private static final int LEGACY_CROP_STEP = LEGACY_WATER_STEP + 1;
	private static final int LEGACY_EXPANSION_STEPS = LEGACY_CROP_STEP + 8;
	private static final int FENCE_STEPS = 20;
	private static final int WATER_STEP = FENCE_STEPS;
	private static final int CROP_STEP = WATER_STEP + 1;
	private static final int EXPANSION_STEPS = CROP_STEP + CLUSTER_SEEDS;
	private static final List<SeedCrop> SEED_CROPS = List.of(
		new SeedCrop(Items.WHEAT_SEEDS, Blocks.WHEAT), new SeedCrop(Items.CARROT, Blocks.CARROTS),
		new SeedCrop(Items.POTATO, Blocks.POTATOES), new SeedCrop(Items.BEETROOT_SEEDS, Blocks.BEETROOTS));
	private record SeedCrop(Item seed, Block crop) { }

	/**
	 * Seed for a new field at {@code pos}: the first-field rule (eight wheat seeds) or the most plentiful stocked seed. With Serene
	 * Seasons active only crops that can grow now are candidates ({@link SeasonCompat#canPlant}); the starter then also accepts another
	 * stocked in-season crop when wheat is out of season. Without the mod the result is exactly the pre-season rule. Null = nothing suitable.
	 */
	static Item seasonalSeed(ServerLevel level, BlockPos pos, List<Container> stores, boolean starter) {
		int need = starter ? STARTER_SEEDS : CLUSTER_SEEDS;
		if (starter) {
			SeedCrop wheat = cropForSeed(Items.WHEAT_SEEDS);
			if (count(stores, wheat.seed()) >= need && SeasonCompat.canPlant(level, pos, wheat.crop())) return wheat.seed();
			if (!SeasonCompat.active(level)) return null;
		}
		Item inSeason = SEED_CROPS.stream()
			.filter(sc -> count(stores, sc.seed()) >= need && SeasonCompat.canPlant(level, pos, sc.crop()))
			.max(Comparator.comparingInt(sc -> count(stores, sc.seed()))).map(SeedCrop::seed).orElse(null);
		// Starter exception: a village without any field must not wait weeks (a new Serene Seasons world starts in late winter) for
		// the right season. Out of season the mod lets crops grow only slowly (default out_of_season_crop_behavior), not never: sow wheat.
		if (inSeason == null && starter && count(stores, Items.WHEAT_SEEDS) >= need) return Items.WHEAT_SEEDS;
		return inSeason;
	}

	/** Enough public seed exists for the field kind, regardless of the season (used to tell a seasonal wait from a seed shortage). */
	private static boolean seedStockIgnoringSeason(List<Container> stores, boolean starter) {
		return starter ? count(stores, Items.WHEAT_SEEDS) >= STARTER_SEEDS
			: SEED_CROPS.stream().anyMatch(sc -> count(stores, sc.seed()) >= CLUSTER_SEEDS);
	}

	/** True while Serene Seasons forbids planting this seed's crop at {@code pos}; always false without the mod. */
	static boolean seasonBlocksSeed(ServerLevel level, BlockPos pos, Item seed) {
		return !SeasonCompat.canPlant(level, pos, cropForSeed(seed).crop());
	}

	/** Seasons are active and none of the four field crops can be planted at {@code pos} (winter, cold biome): the fields are dormant. */
	static boolean fieldsDormant(ServerLevel level, BlockPos pos) {
		return SeasonCompat.active(level) && SEED_CROPS.stream().noneMatch(sc -> SeasonCompat.canPlant(level, pos, sc.crop()));
	}

	/** A farmer standing in fishing fallback during a dormant season re-scans for harvestable crops only this often (ticks). */
	private static final long DORMANT_RESCAN_TICKS = 200L;
	private static final Map<java.util.UUID, Long> DORMANT_RESCAN = new java.util.HashMap<>();

	private static boolean dormantFishing(Ctx c, BlockPos target) {
		return target != null && !mature(c.level(), target) && c.level().getFluidState(target).is(net.minecraft.tags.FluidTags.WATER)
			&& c.now() < DORMANT_RESCAN.getOrDefault(c.v().getUUID(), 0L) && fieldsDormant(c.level(), c.v().blockPosition());
	}
	private record FenceKit(Item fenceItem, Block fence, Item gateItem, Block gate) { }
	private record Taken(Container source, Item item, int count) { }
	private record ExpansionPlan(BlockPos center, Item seed, Item fenceItem, Item gateItem, int cursor, int version) {
		private ExpansionPlan next() { return new ExpansionPlan(center, seed, fenceItem, gateItem, cursor + 1, version); }
		private String encode() {
			String prefix = switch (version) {
				case 1 -> LEGACY_EXPANSION_TAG_PREFIX;
				case 3 -> STARTER_EXPANSION_TAG_PREFIX;
				default -> CURRENT_EXPANSION_TAG_PREFIX;
			};
			return prefix + center.asLong() + "|" + BuiltInRegistries.ITEM.getKey(seed) + "|"
				+ BuiltInRegistries.ITEM.getKey(fenceItem) + "|" + BuiltInRegistries.ITEM.getKey(gateItem) + "|" + cursor;
		}
	}
	private record FarmWaterPlan(BlockPos field, BlockPos source, int version, String dimension) {
		private String encode() {
			return FARM_WATER_PLAN_TAG_PREFIX + dimension + "|" + field.asLong() + "|" + source.asLong() + "|" + version;
		}
	}
	private enum ExpansionStepKind { FENCE, GATE, WATER, CROP }
	private record ExpansionStep(BlockPos pos, Item cost, ExpansionStepKind kind) { }
	private record ActiveExpansion(Villager owner, String encoded) { }
	private static final List<Craft.Recipe> RECIPES = List.of(
		Craft.recipe("Brot", () -> new ItemStack(Items.BREAD), Craft.of(Items.WHEAT, 3)));

	private static boolean isCrop(Item i) {
		return i == Items.WHEAT || i == Items.CARROT || i == Items.POTATO || i == Items.BEETROOT || i == Items.POISONOUS_POTATO || i == Items.BREAD || i == Items.WHEAT_SEEDS || i == Items.BEETROOT_SEEDS;
	}

	private static boolean mature(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s);
	}

	/** Population-scaled reserve target used to decide when additional crop production is needed. */
	public static int foodTarget(int residents) {
		return Math.max(0, residents) * 5;
	}

	public static boolean belowFoodTarget(long storedFood, int residents) {
		return storedFood < foodTarget(residents);
	}

	public static boolean shouldExpand(int currentFarms, boolean foodDeficit) {
		return currentFarms >= 0 && currentFarms < Config.MAX_FARMS
			&& (currentFarms == 0 || foodDeficit);
	}

	/** Includes the village food stores, especially the granary, rather than counting only the public chest. */
	public static boolean belowFoodTarget(Ctx c) {
		if (c.village() == null) return false;
		int residents = de.villagerplus.village.Villages.residents(c.level(), c.village(), Config.VILLAGE_RADIUS, v -> true).size();
		List<Container> stores = Balance.storage(c.level(), c.village());
		long edible = Balance.stock(stores, Balance.Kind.FOOD);
		// Wheat is an ingredient rather than edible by itself; count only the flour/bread-equivalent reserve.
		for (Container store : stores) edible += JobUtil.count(store, stack -> stack.is(Items.WHEAT)) / 3;
		return belowFoodTarget(edible, residents);
	}

	/** Validate the full irrigated plot and fenced perimeter; only small natural plants may be cleared on paid steps. */
	public static boolean safeClusterSite(ServerLevel level, BlockPos cropLevel) {
		for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 3; dz++) {
			BlockPos p = cropLevel.offset(dx, 0, dz);
			if (!level.hasChunkAt(p.below()) || !level.hasChunkAt(p.above())) return false;
			BlockState floor = level.getBlockState(p.below());
			boolean perimeter = dx == -2 || dx == 3 || dz == -2 || dz == 3;
			if (perimeter) {
				if (!floor.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
					|| !farmCellClearable(level, p) || !farmCellClearable(level, p.above())) return false;
			} else {
				if (dx == 0 && dz == 0) {
					if (!farmableSoil(floor) || !farmCellClearable(level, p) || !farmCellClearable(level, p.above())) return false;
				} else if (!farmableSoil(floor)
					|| !farmCellClearable(level, p) || !farmCellClearable(level, p.above())) return false;
			}
		}
		return true;
	}

	/** The first field is a paid 3-by-3 crop bed with the same clearance rules as its saved blueprint. */
	static boolean safeStarterClusterSite(ServerLevel level, BlockPos cropLevel) {
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			BlockPos p = cropLevel.offset(dx, 0, dz);
			if (!level.hasChunkAt(p.below()) || !level.hasChunkAt(p.above())) return false;
			BlockState floor = level.getBlockState(p.below());
			boolean perimeter = Math.abs(dx) == 2 || Math.abs(dz) == 2;
			if (perimeter) {
				if (!floor.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
					|| !farmCellClearable(level, p) || !farmCellClearable(level, p.above())) return false;
			} else if (!farmableSoil(floor)
				|| !farmCellClearable(level, p) || !farmCellClearable(level, p.above())) return false;
		}
		return true;
	}

	/** Small natural ground plants are valid field cells: the farmer clears them during the paid build step. */
	private static boolean farmCellClearable(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return false;
		BlockState state = level.getBlockState(pos);
		return state.isAir() || clearableFarmPlant(state);
	}

	private static boolean clearableFarmPlant(BlockState state) {
		if (state == null || state.hasBlockEntity() || !state.getFluidState().isEmpty()
			|| state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) return false;
		// These explicitly listed, non-double plant blocks are safe to break even if a
		// particular 26.2 BlockState reports canBeReplaced() as false.
		return state.is(BlockTags.FLOWERS) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
			|| state.is(Blocks.FERN) || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.SNOW);
	}

	private static boolean farmableSoil(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(BlockTags.GRASS_BLOCKS) || state.is(Blocks.FARMLAND);
	}

	private static List<BlockPos> vegetationCells(ExpansionStep step) {
		BlockPos first = step.kind() == ExpansionStepKind.WATER ? step.pos().above() : step.pos();
		BlockPos second = first.above();
		return step.kind() == ExpansionStepKind.WATER ? List.of(first, second, second.above()) : List.of(first, second);
	}

	/** Return only small replaceable plants in the work cells; buildings, trees and block entities remain obstacles. */
	private static java.util.LinkedHashMap<BlockPos, BlockState> vegetationToClear(ServerLevel level, ExpansionStep step) {
		java.util.LinkedHashMap<BlockPos, BlockState> plants = new java.util.LinkedHashMap<>();
		for (BlockPos pos : vegetationCells(step)) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;
			if (!clearableFarmPlant(state) || level.getBlockEntity(pos) != null) return null;
			plants.put(pos.immutable(), state);
		}
		return plants;
	}

	private static List<ItemStack> vegetationDrops(Ctx c, Map<BlockPos, BlockState> plants) {
		List<ItemStack> drops = new ArrayList<>();
		for (var entry : plants.entrySet()) {
			drops.addAll(Block.getDrops(entry.getValue(), c.level(), entry.getKey(), null, c.v(), Items.AIR.getDefaultInstance()));
		}
		return drops;
	}

	private static boolean clearVegetation(Ctx c, Map<BlockPos, BlockState> plants) {
		for (var entry : plants.entrySet()) {
			BlockPos pos = entry.getKey();
			BlockState state = entry.getValue();
			if (!c.level().getBlockState(pos).equals(state)
				|| !c.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) return false;
			c.level().levelEvent(2001, pos, Block.getId(state));
			c.level().playSound(null, pos, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.7F, 0.9F + c.v().getRandom().nextFloat() * 0.2F);
		}
		return true;
	}

	private static void restoreVegetation(ServerLevel level, Map<BlockPos, BlockState> plants) {
		for (var entry : plants.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 3);
	}

	@Override
	public Predicate<ItemStack> produce() {
		return s -> isCrop(s.getItem());
	}

	@Override
	public int keep() {
		return Config.FARM_KEEP;
	}

	@Override
	public boolean primary(Ctx c) {
		boolean fbIdle = c.idle(0);
		de.villagerplus.build.FbTrace.trace("FP " + c.mem().name, c.now(), 300, "idle0=" + fbIdle + " act=" + c.st().activity + " farms=" + (c.village() == null ? -1 : c.village().data().farms) + " target=" + c.st().target);
		if (fbIdle) {
			return false;
		}
		boolean foodDeficit = belowFoodTarget(c);
		// Wheat seeds are a construction input stored in the public materials depot, while harvested
		// food belongs in the granary. Keep the two delivery routes separate so a mixed harvest cannot
		// be half-inserted into one depot and then reported as a failed delivery.
		Predicate<ItemStack> foodProduce = stack -> isCrop(stack.getItem()) && !stack.is(Items.WHEAT_SEEDS);
		if (JobUtil.depositSurplus(c, foodProduce, foodDeficit ? 1 : Config.FARM_DEPOSIT_THRESHOLD,
			foodDeficit ? 0 : Config.FARM_KEEP)) {
			return true;
		}
		if (starterSeedDepositDue(c) && JobUtil.depositSurplus(c, stack -> stack.is(Items.WHEAT_SEEDS), 1, 0)) {
			return true;
		}
		String waterPlanTag = farmWaterPlanTag(c.v());
		FarmWaterPlan waterPlan = decodeFarmWaterPlan(waterPlanTag, c.level());
		if (waterPlan != null && farmWaterPlanStillNeeded(c, waterPlan)) {
			c.setActivity("holt Wasser für das gespeicherte Feld");
			return true;
		}
		if (waterPlanTag != null) clearFarmWaterPlan(c.v());
		JobState st = c.st();
		ServerLevel level = c.level();
		BlockPos target = st.target;
		// Dormant season: keep fishing between the periodic harvest re-scans instead of clearing the water target every tick.
		if (dormantFishing(c, target) && FISHING_FALLBACK.primary(c)) return true;
		if (target == null || !mature(level, target) || !harvestAllowedByFarmZones(farmZones(c.village()), target)) {
			// Keep the approach timer while an existing starter-seed route is active.
			// JobUtil.approachLong owns that route while st.target remains null.
			if (target != null) st.clearTarget();
			target = find(c);
			if (target == null) {
				if (forageStarterWheatSeeds(c)) return true;
				// Nothing to harvest and (Serene Seasons) nothing that may be planted: fish instead of idling, like the seed-grass fallback.
				if (fieldsDormant(level, c.v().blockPosition())) {
					DORMANT_RESCAN.put(c.v().getUUID(), c.now() + DORMANT_RESCAN_TICKS);
					if (FISHING_FALLBACK.primary(c)) return true;
				}
				c.setIdle(0, 200);
				return false;
			}
			resetApproach(c);
			st.forageTarget = null;
			st.target = target;
		}
		c.setActivity("erntet auf dem Feld");
		Anim.hold(c, Items.IRON_HOE);
		if (!JobUtil.approach(c, target, 2.6)) {
			return true;
		}
		// Farm zones may have changed while the farmer was walking to a remembered crop.
		if (!harvestAllowedByFarmZones(farmZones(c.village()), target)) {
			st.clearTarget();
			c.setActivity("das Erntefeld liegt außerhalb der freigegebenen Farmzone");
			return true;
		}
		BlockState state = level.getBlockState(target);
		CropBlock crop = (CropBlock) state.getBlock();
		List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, target, null));
		// Erfahrene Bauern ernten oefter doppelt.
		if (!drops.isEmpty() && c.v().getRandom().nextFloat() < 0.1F * Skills.level(c.mem())) {
			drops.add(drops.getFirst().copy());
		}
		if (!canStoreAll(c.v().getInventory(), drops)) {
			// Never break a crop only to spill its harvest beside the field. Keep the target so
			// the farmer retries as soon as a later delivery frees inventory space.
			c.setActivity("wartet auf freien Platz für die Ernte");
			st.cooldownUntil = c.now() + 100;
			return true;
		}
		for (ItemStack drop : drops) {
			JobUtil.give(level, c.v(), drop);
		}
		level.setBlock(target, crop.getStateForAge(0), 3);
		level.levelEvent(2001, target, Block.getId(state));
		level.playSound(null, target, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
		JobUtil.swing(c.v());
		VillagerMemory.remember(c.mem().workSpots, target, Config.MEMORY_MAX_WORK_SPOTS);
		c.mem().jobsDone++;
		st.clearTarget();
		st.cooldownUntil = c.now() + 20;
		return true;
	}

	/**
	 * While the first field is unfunded the foragers keep their seeds until a trip is worth it (a small batch, or enough for the field
	 * together with the public stock); once a field exists every seed is delivered as before.
	 */
	private static boolean starterSeedDepositDue(Ctx c) {
		int carried = JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.WHEAT_SEEDS));
		if (carried <= 0) return false;
		VillageRef ref = c.village();
		if (ref == null || ref.data().farms > 0 || carried >= SEED_DEPOSIT_BATCH) return true;
		return publicFarmSeedCount(Balance.storage(c.level(), ref)) + carried >= VISIBLE_STARTER_FIELD_SEEDS;
	}

	/** Collects real vanilla seed drops from wild grass so a village without an existing field can start one. */
	static boolean forageStarterWheatSeeds(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().farms > 0) {
			c.st().forageTarget = null;
			c.st().forageRejectedTarget = null;
			return false;
		}
		List<Container> publicStores = Balance.storage(c.level(), ref);
		int publicSeeds = publicFarmSeedCount(publicStores);
		int carriedSeeds = JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.WHEAT_SEEDS));
		if (publicSeeds >= VISIBLE_STARTER_FIELD_SEEDS) {
			c.st().forageTarget = null;
			c.st().forageRejectedTarget = null;
			if (carriedSeeds > 0 && JobUtil.depositSurplus(c, stack -> stack.is(Items.WHEAT_SEEDS), 1, 0)) return true;
			return false;
		}

		// Send the recovered seeds to shared stock in batches; the farmer starts the visible field from communal supplies.
		boolean depositDue = carriedSeeds >= SEED_DEPOSIT_BATCH || carriedSeeds > 0 && publicSeeds + carriedSeeds >= VISIBLE_STARTER_FIELD_SEEDS;
		if (depositDue && JobUtil.depositSurplus(c, stack -> stack.is(Items.WHEAT_SEEDS), 1, 0)) { de.villagerplus.build.FbTrace.ev("DEPOSIT " + c.mem().name + " tick " + c.now() + " carried=" + carriedSeeds); return true; }
		int targetSeeds = VISIBLE_STARTER_FIELD_SEEDS;
		if (publicSeeds + carriedSeeds >= targetSeeds) return waitForStarterSeedStorage(c);

		BlockPos target = c.st().forageTarget;
		if (target == null || !safeSeedGrass(c.level(), ref, target)) {
			BlockPos previousTarget = target;
			target = findSeedGrass(c, ref);
			if (target == null && c.now() >= c.st().forageFarSearchAt) {
				target = findFarSeedGrass(c, ref);
				if (target == null) c.st().forageFarSearchAt = c.now() + FAR_SEED_SEARCH_RETRY_TICKS;
			}
			c.st().forageTarget = target;
			if (!java.util.Objects.equals(previousTarget, target)) resetApproach(c);
		}
		// Kein Saatgras (z. B. Badlands/Wueste): bis Saatgut verfuegbar ist, ernaehrt der Bauer das Dorf mit echtem Fischfang.
		if (target == null) return FISHING_FALLBACK.primary(c);
		c.setActivity("sammelt Weizensamen für das erste Dorffeld");
		Anim.hold(c, Items.WOODEN_HOE);
		de.villagerplus.build.FbTrace.trace("FTRACE " + c.mem().name, c.now(), 150, "pos=" + c.v().blockPosition().toShortString() + " target=" + target.toShortString()
			+ " d=" + (int) Math.sqrt(c.v().blockPosition().distSqr(target)) + " nav=" + (c.v().getNavigation().isDone() ? "done" : "active") + " walkStart=" + c.st().walkStart + " cd=" + (c.st().cooldownUntil - c.now()));
		long previousRouteStart = c.st().walkStart;
		if (!JobUtil.approachLong(c, target, 2.6)) {
			// A route abort clears JobState.walkStart and starts the shared approach cooldown.
			// Release this transient forage target so the next attempt can sample another patch.
			if (previousRouteStart > 0L && c.st().walkStart == 0L && c.st().cooldownUntil > c.now()) {
				rejectForageTarget(c.st(), target);
				c.setActivity("sucht nach einer unerreichbaren Saatgrasstelle eine neue Stelle");
			}
			return true;
		}
		List<Container> currentPublicStores = Balance.storage(c.level(), ref);
		int currentPublicSeeds = publicFarmSeedCount(currentPublicStores);
		int currentCarriedSeeds = JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.WHEAT_SEEDS));
		if (currentPublicSeeds >= VISIBLE_STARTER_FIELD_SEEDS) {
			c.st().forageTarget = null;
			if (currentCarriedSeeds > 0 && JobUtil.depositSurplus(c, stack -> stack.is(Items.WHEAT_SEEDS), 1, 0)) return true;
			return false;
		}
		int currentTargetSeeds = VISIBLE_STARTER_FIELD_SEEDS;
		if (currentPublicSeeds + currentCarriedSeeds >= currentTargetSeeds) {
			if (currentCarriedSeeds > 0 && JobUtil.depositSurplus(c, stack -> stack.is(Items.WHEAT_SEEDS), 1, 0)) return true;
			return waitForStarterSeedStorage(c);
		}
		if (!safeSeedGrass(c.level(), ref, target)) {
			c.st().forageTarget = null;
			return false;
		}

		BlockState state = c.level().getBlockState(target);
		ItemStack tool = Items.WOODEN_HOE.getDefaultInstance();
		List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, c.level(), target, null, c.v(), tool));
		de.villagerplus.build.FbTrace.ev("FORAGE " + c.mem().name + " tick " + c.now() + " roll=" + drops.stream().anyMatch(stack -> stack.is(Items.WHEAT_SEEDS)) + " at " + c.v().blockPosition().toShortString() + " bellDist=" + (int) Math.sqrt(c.v().blockPosition().distSqr(ref.bell())));
		if (drops.stream().noneMatch(stack -> stack.is(Items.WHEAT_SEEDS))) {
			// Wild grass has a low vanilla seed-drop chance. Preserve plants that yield no seeds.
			// Do not pin the farmer to the same unlucky grass block on every later attempt.
			c.st().forageRejectedTarget = target.immutable();
			c.st().forageTarget = null;
			c.st().cooldownUntil = c.now() + 40;
			return true;
		}
		if (!canStoreAll(c.v().getInventory(), drops)) {
			c.setActivity("wartet auf Platz für gesammelte Weizensamen");
			c.st().cooldownUntil = c.now() + 100;
			return true;
		}
		if (!c.level().setBlock(target, Blocks.AIR.defaultBlockState(), 3)) return false;
		for (ItemStack drop : drops) JobUtil.give(c.level(), c.v(), drop);
		c.level().levelEvent(2001, target, Block.getId(state));
		c.level().playSound(null, target, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.7F, 0.9F + c.v().getRandom().nextFloat() * 0.2F);
		JobUtil.swing(c.v());
		c.st().forageTarget = null;
		c.st().forageRejectedTarget = null;
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 40;
		return true;
	}

	private static void resetApproach(Ctx c) {
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		c.st().walkStart = 0;
		c.st().bestDist = 0.0;
		c.st().lastMoveAt = 0;
		c.st().stuckCount = 0;
	}

	private static boolean waitForStarterSeedStorage(Ctx c) {
		c.st().forageTarget = null;
		c.setActivity("bewahrt Weizensamen bis zum finanzierbaren ersten Feld auf");
		return true;
	}

	/** Hold only new starter work until it is funded; persisted farmer projects always resume before this gate. */
	static boolean shouldWaitForVisibleStarterFarm(int completedFarms, boolean savedFarmerPlan, int publicWheatSeeds) {
		return completedFarms == 0 && !savedFarmerPlan && publicWheatSeeds < VISIBLE_STARTER_FIELD_SEEDS;
	}

	/** With Serene Seasons active and no public seed that may be sown now, the Builder's compact wheat field must not start either. */
	private static boolean starterSeedBlockedBySeason(Ctx c, List<Container> publicStores) {
		return SeasonCompat.active(c.level()) && seasonalSeed(c.level(), c.v().blockPosition(), publicStores, true) == null;
	}

	/** Builder fallback for the same compact starter field when direct site selection cannot proceed. */
	static boolean shouldTryCompactStarterPlan(int completedFarms, boolean savedFarmerPlan,
		boolean savedFarmerExpansion, int publicWheatSeeds) {
		// The first field is production infrastructure, even when stored food currently covers the reserve.
		// Later fields remain shortage-driven through expandCluster's ordinary food target gate.
		return completedFarms == 0 && !savedFarmerPlan && !savedFarmerExpansion
			&& publicWheatSeeds >= Config.FARM_MIN_SEEDS;
	}

	/**
	 * A failed first-field attempt is normally throttled by Builder for several hundred ticks. Reopen that retry when
	 * its real funding/storage or construction-capacity prerequisites change, while identical blocked terrain remains
	 * throttled. The marker is saved on the farmer so a restart cannot manufacture another retry on every tick.
	 */
	static String starterFarmReadinessMarker(int completedFarms, boolean savedFarmerPlan,
		boolean savedFarmerExpansion, int publicWheatSeeds, boolean publicStorageReady,
		int activeProjects, int residents, String publicFarmStock) {
		if (completedFarms != 0 || savedFarmerPlan || savedFarmerExpansion
			|| publicWheatSeeds < Config.FARM_MIN_SEEDS || !publicStorageReady) return null;
		return STARTER_FARM_RETRY_TAG_PREFIX + publicWheatSeeds + "|" + Math.max(0, activeProjects)
			+ "|" + Math.max(0, residents) + "|" + (publicFarmStock == null ? "none" : publicFarmStock);
	}

	static boolean shouldWakeStarterFarmRetry(String previousMarker, String readinessMarker) {
		return readinessMarker != null && !readinessMarker.equals(previousMarker);
	}

	/** Counts starter seeds in all verified public depots, including the granary used by delivery routing. */
	static int publicFarmSeedCount(List<Container> publicStores) {
		return count(publicStores, Items.WHEAT_SEEDS);
	}

	private static void refreshStarterFarmRetry(Ctx c, VillageRef ref, boolean firstFarm,
		boolean savedFarmerPlan, boolean savedFarmerExpansion, int publicWheatSeeds, Container publicStock) {
		String previousMarker = starterFarmRetryTag(c.v());
		String readinessMarker = null;
		if (firstFarm && !savedFarmerPlan && !savedFarmerExpansion
			&& publicWheatSeeds >= Config.FARM_MIN_SEEDS) {
			boolean publicStorageReady = !Balance.storage(c.level(), ref).isEmpty();
			int activeProjects = ref.data().activeBuildProjectCount();
			int residents = Villages.residents(c.level(), ref, Config.VILLAGE_RADIUS, v -> true).size();
			readinessMarker = starterFarmReadinessMarker(ref.data().farms, false, false,
				publicWheatSeeds, publicStorageReady, activeProjects, residents, starterFarmStockSignature(publicStock));
		}
		boolean readinessChanged = shouldWakeStarterFarmRetry(previousMarker, readinessMarker);
		if (!readinessChanged && java.util.Objects.equals(previousMarker, readinessMarker)) return;
		for (String tag : List.copyOf(c.v().entityTags())) {
			if (tag.startsWith(STARTER_FARM_RETRY_TAG_PREFIX)) c.v().removeTag(tag);
		}
		if (readinessMarker != null) {
			c.v().addTag(readinessMarker);
			if (readinessChanged) {
				if (c.st().buildCheckAt > c.now()) c.st().buildCheckAt = c.now();
				if (c.st().materialWaitUntil > c.now()) c.st().materialWaitUntil = c.now();
				if (ref.data().farms == 0 && ref.data().extra.nextFarm > c.now()) {
					ref.data().extra.nextFarm = c.now();
					ref.dirty();
				}
			}
		}
		c.v().setPersistenceRequired();
	}

	/** Public first-field inputs only; private chests never wake or fund this retry. */
	static String starterFarmStockSignature(Container publicStock) {
		if (publicStock == null) return "none";
		Map<String, Integer> relevant = new TreeMap<>();
		for (int slot = 0; slot < publicStock.getContainerSize(); slot++) {
			ItemStack stack = publicStock.getItem(slot);
			if (stack.isEmpty()) continue;
			Block block = Block.byItem(stack.getItem());
			if (!stack.is(Items.WHEAT_SEEDS) && !stack.is(Items.WATER_BUCKET) && !stack.is(Items.BUCKET)
				&& !stack.is(Items.STICK) && !stack.is(Items.IRON_INGOT) && !stack.is(Items.IRON_BLOCK)
				&& !stack.is(Items.IRON_NUGGET) && !stack.is(ItemTags.LOGS) && !stack.is(ItemTags.PLANKS)
				&& !stack.is(ItemTags.WOODEN_FENCES) && !block.defaultBlockState().is(BlockTags.FENCE_GATES)) continue;
			relevant.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		return relevant.toString();
	}

	private static String starterFarmRetryTag(Villager villager) {
		return villager.entityTags().stream().filter(tag -> tag.startsWith(STARTER_FARM_RETRY_TAG_PREFIX)).findFirst().orElse(null);
	}

	/** Readiness for the first paid pen is independent of wheat reserves and uses public materials only. */
	static String initialPenReadinessMarker(boolean penMissing, boolean savedFieldPlan, boolean savedPenPlan,
		int completedFarms, int residents, boolean eligibleLivestock, boolean publicStorageReady,
		int activeProjects, String publicMaterialStock) {
		if (!penMissing || savedFieldPlan || savedPenPlan || !publicStorageReady
			|| residents < Builder.MIN_INITIAL_PEN_POPULATION || (completedFarms < 1 && !eligibleLivestock)) return null;
		return STARTER_PEN_RETRY_TAG_PREFIX + Math.max(0, completedFarms) + "|" + residents + "|"
			+ Math.max(0, activeProjects) + "|" + eligibleLivestock + "|"
			+ (publicMaterialStock == null ? "none" : publicMaterialStock);
	}

	static boolean shouldWakeInitialPenRetry(String previousMarker, String readinessMarker) {
		return readinessMarker != null && !readinessMarker.equals(previousMarker);
	}

	private static void refreshInitialPenRetry(Ctx c, VillageRef ref, String readinessMarker) {
		List<String> previousMarkers = c.v().entityTags().stream()
			.filter(tag -> tag.startsWith(STARTER_PEN_RETRY_TAG_PREFIX)).toList();
		String previousMarker = previousMarkers.stream().findFirst().orElse(null);
		boolean readinessChanged = shouldWakeInitialPenRetry(previousMarker, readinessMarker);
		if (!readinessChanged && java.util.Objects.equals(previousMarker, readinessMarker)) return;
		previousMarkers.forEach(c.v()::removeTag);
		if (readinessMarker != null && !c.v().addTag(readinessMarker)) {
			previousMarkers.forEach(c.v()::addTag);
			return;
		}
		if (readinessMarker != null) {
			if (readinessChanged) {
				if (c.st().buildCheckAt > c.now()) c.st().buildCheckAt = c.now();
				if (c.st().materialWaitUntil > c.now()) c.st().materialWaitUntil = c.now();
				// Builder.startPlan gates PEN and FARM on this shared field timer; a newly ready pen must wake it.
				if (ref.data().extra.nextFarm > c.now()) {
					ref.data().extra.nextFarm = c.now();
					ref.dirty();
				}
			}
		}
		c.v().setPersistenceRequired();
	}

	/** Stable compact fingerprint of non-food public items, which are the only inputs a pen blueprint can consume. */
	static String initialPenStockSignature(Container publicStock) {
		if (publicStock == null) return "none";
		Map<String, Integer> materials = new TreeMap<>();
		for (int slot = 0; slot < publicStock.getContainerSize(); slot++) {
			ItemStack stack = publicStock.getItem(slot);
			if (stack.isEmpty() || Meals.isFood(stack)) continue;
			materials.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		return Integer.toUnsignedString(materials.hashCode(), 36) + ":" + materials.size();
	}

	static void rejectForageTarget(JobState state, BlockPos target) {
		state.forageRejectedTarget = target.immutable();
		state.forageTarget = null;
	}

	static boolean rejectedForageTarget(JobState state, BlockPos target) {
		return target.equals(state.forageRejectedTarget);
	}

	static BlockPos seedGrassSurface(ServerLevel level, BlockPos column) {
		if (!level.hasChunkAt(column)) return null;
		return new BlockPos(column.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
			column.getX(), column.getZ()), column.getZ());
	}

	/** Grass regions (centre, until-tick) whose cells have no walk-back to the village street: a gully the forager cannot leave again. */
	private static final List<long[]> SEED_TRAPS = new java.util.ArrayList<>();
	private static final int SEED_TRAP_RADIUS = 6;
	private static final long SEED_TRAP_TICKS = 24_000L;
	private static final int SEED_WALKBACK_CHECKS = 3;

	private static boolean inSeedTrap(BlockPos pos, long now) {
		for (long[] trap : SEED_TRAPS) {
			if (trap[3] <= now) continue;
			long dx = pos.getX() - trap[0], dz = pos.getZ() - trap[1];
			if (dx * dx + dz * dz <= (long) SEED_TRAP_RADIUS * SEED_TRAP_RADIUS && Math.abs(pos.getY() - trap[2]) <= 2) return true; // a gully floor condemns its own level, not the rim above it
		}
		return false;
	}

	static void clearSeedTraps() {
		SEED_TRAPS.clear();
	}

	/**
	 * Search every column in a fixed local budget so sparse valid grass cannot be missed by random sampling.
	 * Vanilla navigation walks a forager down into a gully but not out of it (measured: the same farmers were rescued from one
	 * hollow again and again), so the nearest few candidates must also have a walk-back to the village street.
	 */
	/** The farmer stands next to the plant it just searched in vain: it searches that plant again unless another one lies within this many blocks. */
	private static final int SEED_REROLL_REACH = 4;
	private static final int SEED_REROLL_NEIGHBOUR = 8;
	/** Recovered seeds are walked to the depot in small batches, not one trip per seed. */
	private static final int SEED_DEPOSIT_BATCH = 3;
	private static final int FAR_SEED_SEARCH_RADIUS = 80;
	private static final long FAR_SEED_SEARCH_RETRY_TICKS = 600L;

	static BlockPos findSeedGrass(Ctx c, VillageRef ref) {
		return scanSeedGrass(c, ref, STARTER_SEED_SEARCH_RADIUS, 1);
	}

	/**
	 * The ordinary search only sees plants within 32 blocks of the farmer. A farmer standing in a built-up village often has none there
	 * while meadows lie 40-80 blocks away inside the village radius; this wider pass looks at every second column of that square.
	 */
	static BlockPos findFarSeedGrass(Ctx c, VillageRef ref) {
		return scanSeedGrass(c, ref, FAR_SEED_SEARCH_RADIUS, 2);
	}

	private static BlockPos scanSeedGrass(Ctx c, VillageRef ref, int radius, int stride) {
		BlockPos center = c.v().blockPosition();
		BlockPos bell = ref.bell();
		List<BlockPos> candidates = new java.util.ArrayList<>();
		List<Long> candidateDistance = new java.util.ArrayList<>();
		BlockPos rerollCandidate = null;
		int fbGrass = 0, fbVillage = 0;
		for (int xOffset = -radius; xOffset <= radius; xOffset += stride) for (int zOffset = -radius; zOffset <= radius; zOffset += stride) {
			BlockPos column = center.offset(xOffset, 0, zOffset);
			long distance = horizontalDistanceSquared(center, column);
			if (distance > (long) radius * radius) continue;
			if (candidates.size() >= SEED_WALKBACK_CHECKS && distance >= candidateDistance.get(candidateDistance.size() - 1)) continue;
			BlockPos pos = seedGrassSurface(c.level(), column);
			if (pos == null || !c.level().getBlockState(pos).is(Blocks.SHORT_GRASS)) continue;
			fbGrass++;
			long villageDistance = horizontalDistanceSquared(pos, bell);
			if (villageDistance < 10L * 10L || villageDistance > (long) Config.VILLAGE_RADIUS * Config.VILLAGE_RADIUS) { fbVillage++; continue; }
			if (rejectedForageTarget(c.st(), pos)) {
				// The plant that just yielded no seed stays standing; it may be searched again when no other plant is close.
				if (distance <= SEED_REROLL_REACH * SEED_REROLL_REACH && safeSeedGrass(c.level(), ref, pos)) rerollCandidate = pos.immutable();
				continue;
			}
			if (!safeSeedGrass(c.level(), ref, pos)) continue;
			if (nearbyHomePoi(c.level(), pos)) continue;
			if (inSeedTrap(pos, c.now())) continue;
			int at = candidates.size();
			while (at > 0 && candidateDistance.get(at - 1) > distance) at--;
			candidates.add(at, pos.immutable());
			candidateDistance.add(at, distance);
			if (candidates.size() > SEED_WALKBACK_CHECKS) {
				candidates.remove(candidates.size() - 1);
				candidateDistance.remove(candidateDistance.size() - 1);
			}
		}
		if (rerollCandidate != null && (candidates.isEmpty() || candidateDistance.get(0) > (long) SEED_REROLL_NEIGHBOUR * SEED_REROLL_NEIGHBOUR)) {
			return rerollCandidate;
		}
		de.villagerplus.build.FbTrace.trace("FSEARCH " + c.mem().name, c.now(), 300, "from " + center.toShortString() + " bellDist=" + (int) Math.sqrt(horizontalDistanceSquared(center, bell)) + " grassInR32=" + fbGrass + " outsideVillageRing=" + fbVillage + " candidates=" + candidates.size());
		if (candidates.isEmpty()) return null;
		BlockPos home = de.villagerplus.build.Builder.villageReturn(c.level(), bell);
		// No street cell known: keep the nearest candidate, as before this check existed.
		if (home == null) return candidates.get(0);
		for (BlockPos candidate : candidates) {
			if (inSeedTrap(candidate, c.now())) continue;
			BlockPos stand = JobUtil.safeSpot(c.level(), net.minecraft.world.phys.Vec3.atBottomCenterOf(candidate)) == null
				? candidate : BlockPos.containing(JobUtil.safeSpot(c.level(), net.minecraft.world.phys.Vec3.atBottomCenterOf(candidate)));
			if (de.villagerplus.village.Sites.walksBackTo(c.level(), stand, home)) return candidate;
			if (SEED_TRAPS.size() > 64) SEED_TRAPS.remove(0);
			SEED_TRAPS.add(new long[] { candidate.getX(), candidate.getZ(), candidate.getY(), c.now() + SEED_TRAP_TICKS });
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Saatgras {}: Ziel {} verworfen (kein Rueckweg zur Dorfstrasse {})",
				c.mem().name, candidate.toShortString(), home.toShortString());
		}
		return null;
	}

	/** Stable row-major index into the bounded square; callers skip its four corner triangles. */
	static BlockPos starterSeedSearchColumn(BlockPos center, int index) {
		if (index < 0 || index >= MAX_STARTER_SEED_SEARCH_COLUMNS) return null;
		int xOffset = index / STARTER_SEED_SEARCH_DIAMETER - STARTER_SEED_SEARCH_RADIUS;
		int zOffset = index % STARTER_SEED_SEARCH_DIAMETER - STARTER_SEED_SEARCH_RADIUS;
		return center.offset(xOffset, 0, zOffset);
	}

	static int starterSeedSearchColumnCount() {
		return MAX_STARTER_SEED_SEARCH_COLUMNS;
	}

	static boolean nearbyHomePoi(ServerLevel level, BlockPos pos) {
		return level.getPoiManager().getCountInRange(holder -> holder.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME),
			pos, 8, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY) > 0;
	}

	static boolean safeSeedGrass(ServerLevel level, VillageRef ref, BlockPos pos) {
		if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())) return false;
		BlockState plant = level.getBlockState(pos);
		if (!plant.is(Blocks.SHORT_GRASS) || plant.hasBlockEntity() || !plant.getFluidState().isEmpty()) return false;
		BlockState soil = level.getBlockState(pos.below());
		if (!soil.is(BlockTags.GRASS_BLOCKS) || soil.hasBlockEntity() || !soil.getFluidState().isEmpty()) return false;
		if (!level.getBlockState(pos.above()).isAir()) return false;
		if (level.getBlockEntity(pos) != null || level.getBlockEntity(pos.below()) != null) return false;
		if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(pos),
			entity -> entity.isAlive()).isEmpty()) return false;
		return horizontalDistanceSquared(ref.bell(), pos) <= (long)Config.VILLAGE_RADIUS * Config.VILLAGE_RADIUS;
	}

	private static long horizontalDistanceSquared(BlockPos a, BlockPos b) {
		long dx = (long)a.getX() - b.getX();
		long dz = (long)a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/** Simulate the complete harvest in a detached inventory before changing the crop block. */
	private static boolean canStoreAll(Container inventory, List<ItemStack> drops) {
		net.minecraft.world.SimpleContainer forecast = new net.minecraft.world.SimpleContainer(inventory.getContainerSize());
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) forecast.setItem(slot, inventory.getItem(slot).copy());
		for (ItemStack drop : drops) {
			if (drop.isEmpty()) continue;
			if (!forecast.addItem(drop.copy()).isEmpty()) return false;
		}
		return true;
	}

	@Override
	public boolean secondary(Ctx c) {
		if (c.idle(1)) {
			return false;
		}
		VillageRef ref = c.village();
		if (ref != null) rememberSavedFarmCropSpots(c, ref);
		boolean legacyBuilder = ref == null || ref.data().hasPlan() || farmZones(ref).isEmpty();
		// A new first field uses the farmer's paid, resumable crop-and-fence path. Saved Builder projects keep their old cursor.
		boolean firstFarm = ref != null && (ref.data().farms == 0 || ref.data().hasBuildProjectType(Blueprints.FARM));
		boolean savedFarmerPlan = ref != null && (ref.data().hasBuildProjectType(Blueprints.FARM)
			|| ref.data().hasBuildProjectType(Blueprints.PEN)
			|| ref.data().hasPlan() && (Blueprints.FARM.equals(ref.data().planType)
				|| Blueprints.PEN.equals(ref.data().planType)));
		boolean savedFieldPlan = ref != null && (ref.data().hasBuildProjectType(Blueprints.FARM)
			|| ref.data().hasPlan() && Blueprints.FARM.equals(ref.data().planType));
		List<Container> publicFarmStores = ref == null ? List.of() : Balance.storage(c.level(), ref);
		Container builderStock = combinedStock(publicFarmStores);
		int builderWheatSeeds = publicFarmSeedCount(publicFarmStores);
		boolean waitForVisibleStarterFarm = firstFarm && shouldWaitForVisibleStarterFarm(ref.data().farms,
			savedFarmerPlan, builderWheatSeeds);
		boolean initialPenDue = false;
		boolean savedPenPlan = false;
		if (ref != null) {
			String penReadinessMarker = null;
			if (ref.data().extra.pen.isEmpty()) {
				// The first pen uses its own readiness and retry lane; it must not inherit a farm-food cooldown.
				int residents = Villages.residents(c.level(), ref, Config.VILLAGE_RADIUS, v -> true).size();
				savedPenPlan = ref.data().hasBuildProjectType(Blueprints.PEN)
					|| ref.data().hasPlan() && Blueprints.PEN.equals(ref.data().planType);
				boolean publicStorageReady = !Balance.storage(c.level(), ref).isEmpty();
				boolean eligibleLivestock = !savedFieldPlan && !savedPenPlan
					&& residents >= Builder.MIN_INITIAL_PEN_POPULATION && publicStorageReady
					&& Livestock.hasEligibleLivestockForInitialPenCached(c.level(), ref);
				initialPenDue = shouldStartInitialPen(ref.data().farms, true, residents,
					eligibleLivestock, publicStorageReady, savedPenPlan)
					&& Livestock.canPlanInitialPen(c.level(), ref, c.v());
				penReadinessMarker = initialPenReadinessMarker(true, savedFieldPlan, savedPenPlan,
					ref.data().farms, residents, eligibleLivestock, publicStorageReady,
					ref.data().activeBuildProjectCount(), initialPenStockSignature(builderStock));
				if (!legacyBuilder) legacyBuilder = initialPenDue;
			}
			refreshInitialPenRetry(c, ref, penReadinessMarker);
		}
		boolean savedFarmExpansion = expansionStateTag(c.v()) != null
			|| ref != null && activeExpansion(c, ref) != null;
		boolean newStarterFarm = firstFarm && !savedFarmerPlan && !savedFarmExpansion;
		if (ref != null) {
			refreshStarterFarmRetry(c, ref, firstFarm, savedFarmerPlan, savedFarmExpansion,
				builderWheatSeeds, builderStock);
		}
		boolean prioritizeInitialPen = shouldPrioritizeInitialPenBeforeNewExpansion(
			savedFieldPlan, initialPenDue, savedFarmExpansion);
		if (prioritizeInitialPen && Builder.tick(c, false)) return true;
		// If the paid direct plot cannot find a safe site, let Builder try its terrain-aware compact plot.
		if ((firstFarm && savedFarmerPlan && !waitForVisibleStarterFarm && Builder.tick(c, false))
			|| (!prioritizeInitialPen && (savedFarmExpansion || !firstFarm || newStarterFarm) && expandCluster(c))
			|| (!prioritizeInitialPen && ref != null
				&& shouldTryCompactStarterPlan(ref.data().farms, savedFarmerPlan, savedFarmExpansion,
					builderWheatSeeds)
				&& !starterSeedBlockedBySeason(c, publicFarmStores)
				&& Livestock.withInitialPenPlanningSuppressed(() -> Builder.tick(c, false)))
			|| (legacyBuilder && !firstFarm && !prioritizeInitialPen && !savedFarmExpansion
				&& (ref == null || savedPenPlan || initialPenDue)
				&& Builder.tick(c, false))
			|| Livestock.tick(c) || Craft.run(c, RECIPES)) {
			return true;
		}
		if (waitingForFarmGrowth(c)) {
			c.setActivity("wartet auf reife Feldfrüchte");
			c.setIdle(1, 200);
			return true;
		}
		c.setIdle(1, 300);
		return false;
	}

	/** Keep seeded field cells available to the farmer's saved harvest search, including after a restart. */
	private static void rememberSavedFarmCropSpots(Ctx c, VillageRef ref) {
		var data = ref.data();
		for (var project : data.buildProjects) {
			if (project.active() && Blueprints.FARM.equals(project.type())) {
				rememberBlueprintCropSpots(c.mem(), project.origin(), project.variant());
			}
		}
		if (data.hasPlan() && Blueprints.FARM.equals(data.planType) && data.planOrigin.isPresent()) {
			rememberBlueprintCropSpots(c.mem(), data.planOrigin.get(), data.planVariant);
		}
	}

	private static void rememberBlueprintCropSpots(VillagerMemory memory, BlockPos origin, int variant) {
		if (origin == null || variant < 10 || variant % 10 >= SEED_CROPS.size()) return;
		for (var step : Blueprints.farm(origin, variant)) {
			if (step.cost() > 0 && cropForSeedOrNull(step.item()) != null) {
				VillagerMemory.remember(memory.workSpots, step.pos(), Config.MEMORY_MAX_WORK_SPOTS);
			}
		}
	}

	private static boolean waitingForFarmGrowth(Ctx c) {
		List<ZonePolicy.Zone> zones = farmZones(c.village());
		boolean growing = false;
		for (BlockPos pos : c.mem().workSpots) {
			if (!harvestAllowedByFarmZones(zones, pos)) continue;
			BlockState state = c.level().getBlockState(pos);
			if (!(state.getBlock() instanceof CropBlock crop)) continue;
			if (crop.isMaxAge(state)) return false;
			growing = true;
		}
		return growing;
	}

	/** Keep the first animal pen in the farmer's Builder route as soon as the starter field exists. */
	static boolean shouldBuildInitialPen(int completedFarms, boolean penMissing, int residents) {
		return penMissing && completedFarms >= 1 && residents >= de.villagerplus.build.Builder.MIN_INITIAL_PEN_POPULATION;
	}

	/** Production reaches this decision after the first field; a saved pen cursor remains resumable. */
	static boolean shouldBuildInitialPen(int completedFarms, boolean penMissing, int residents,
		boolean eligibleLivestock, boolean publicStorageReady, boolean savedPenPlan) {
		if (!penMissing) return false;
		if (savedPenPlan) return true;
		return publicStorageReady && residents >= de.villagerplus.build.Builder.MIN_INITIAL_PEN_POPULATION
			&& (completedFarms >= 1 || eligibleLivestock);
	}

	/** Match Builder's first-pen gate: a completed field is enough to start the enclosure; an eligible local animal can unlock it sooner. */
	static boolean shouldStartInitialPen(int completedFarms, boolean penMissing, int residents,
		boolean eligibleLivestock, boolean publicStorageReady, boolean savedPenPlan) {
		if (!penMissing) return false;
		if (savedPenPlan) return true;
		return residents >= Builder.MIN_INITIAL_PEN_POPULATION && publicStorageReady
			&& (completedFarms >= 1 || eligibleLivestock);
	}

	/** A due first pen can precede new work, while an active field or expansion cursor keeps its saved work order. */
	static boolean shouldPrioritizeInitialPenBeforeNewExpansion(boolean activeFieldPlan,
		boolean initialPenDue, boolean savedFarmExpansionCursor) {
		return !activeFieldPlan && initialPenDue && !savedFarmExpansionCursor;
	}

	static long nextClusterFarmBuildAt(int completedFarms, boolean penMissing, int residents, long now) {
		return shouldBuildInitialPen(completedFarms, penMissing, residents) ? now : now + Config.BUILD_COOLDOWN;
	}

	/** Creates the first compact field, then resumes paid expansions only when shared food is below reserve. */
	private static final FishermanJob FISHING_FALLBACK = new FishermanJob();
	private static final java.util.Map<java.util.UUID, Long> FARM_DIAG = new java.util.HashMap<>();

	private static void farmDiag(Ctx c, String message) {
		long last = FARM_DIAG.getOrDefault(c.v().getUUID(), -1_000_000L);
		if (c.now() - last < 2400L) return;
		FARM_DIAG.put(c.v().getUUID(), c.now());
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Farmstart {}: {}", c.mem().name, message);
	}

	private static boolean expandCluster(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null) return false;
		String ownPlan = expansionStateTag(c.v());
		ActiveExpansion active = ownPlan == null ? activeExpansion(c, ref) : new ActiveExpansion(c.v(), ownPlan);
		if (active != null) {
			clearFarmWaterPlan(c.v());
			if (active.owner() != c.v()) {
				// A farmer who changed jobs may hand the persisted cursor to the next farmer.
				if (isFarmer(active.owner())) return false;
				active.owner().removeTag(active.encoded());
				if (!c.v().addTag(active.encoded())) {
					active.owner().addTag(active.encoded());
					return true;
				}
				c.v().setPersistenceRequired();
			}
			ExpansionPlan plan = decodeExpansionPlan(active.encoded());
			if (plan == null) {
				c.setActivity("wartet auf Prüfung des gespeicherten Feldplans");
				return true;
			}
			if (ref.data().hasPlan() && ref.data().farms > 0) return false;
			return advanceExpansion(c, plan);
		}
		String waterPlanTag = farmWaterPlanTag(c.v());
		FarmWaterPlan waterPlan = decodeFarmWaterPlan(waterPlanTag, c.level());
		if (waterPlanTag != null) {
			if (waterPlan == null) {
				clearFarmWaterPlan(c.v());
			} else {
				return advanceFarmWaterPlan(c, waterPlan);
			}
		}
		var data = ref.data();
		farmDiag(c, "start farms=" + data.farms + " wait=" + (c.st().materialWaitUntil - c.now()) + " nextFarm=" + (data.extra.nextFarm - c.now())
			+ " plan=" + data.hasPlan() + " stores=" + Balance.storage(c.level(), ref).size());
		if (c.now() < c.st().materialWaitUntil
			|| data.farms >= Config.MAX_FARMS
			|| !shouldExpand(data.farms, data.farms == 0 || belowFoodTarget(c))) return false;
		var stores = Balance.storage(c.level(), ref);
		// The very first field is a food emergency and must not wait behind a stalled public building plan.
		if (stores.isEmpty() || c.now() < data.extra.nextFarm || (data.hasPlan() && data.farms > 0)) return false;
		List<Villager> residents = Villages.residents(c.level(), ref, Config.VILLAGE_RADIUS, v -> true);
		if (residents.isEmpty()) return false;
		boolean starter = data.farms == 0;
		int requiredFences = starter ? STARTER_FENCES : CLUSTER_FENCES;
		FenceKit kit = fenceKit(stores, requiredFences);
		Item seed = seasonalSeed(c.level(), c.v().blockPosition(), stores, starter);
		SeedCrop crop = seed == null ? null : cropForSeed(seed);
		if (crop == null) {
			c.setActivity(SeasonCompat.active(c.level()) && seedStockIgnoringSeason(stores, starter)
				? "wartet auf eine Jahreszeit, in der das Saatgut wächst"
				: starter ? "wartet auf acht öffentliche Weizensamen für das erste Feld" : "wartet auf Saatgut für die nächste Feldfläche");
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		BlockPos site = findClusterSite(c, ref, starter);
		if (site == null) {
			c.setActivity("wartet auf ein sicheres, freies Feldgrundstück");
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		c.setActivity(starter ? "legt das erste bewässerte Feld an" : "erweitert die bewässerten Nahrungsfelder");
		Anim.hold(c, Items.IRON_HOE);
		if (!JobUtil.approach(c, site, 3.0)) return true;
		int version = starter ? 3 : 2;
		if (!clusterAllowedByZones(ref, site, version)
			|| !(starter ? safeStarterClusterSite(c.level(), site) : safeClusterSite(c.level(), site))) return false;
		Container publicStock = combinedStock(stores);
		if (kit == null) {
			if (prepareFenceKit(c, stores, publicStock, requiredFences)) return true;
			kit = fenceKit(stores, requiredFences);
			if (kit == null) {
				c.setActivity("wartet auf öffentliche Zutaten für Zaun und Tor");
				c.st().materialWaitUntil = c.now() + 600;
				return false;
			}
		}
		if (!hasWaterBucket(c, stores)) {
			if (ensureEmptyFarmBucket(c, stores)) return true;
			if (!hasEmptyFarmBucket(c, stores)) {
				c.setActivity("wartet auf einen leeren Eimer für die Feldbewässerung");
				c.st().materialWaitUntil = c.now() + 600;
				return false;
			}
			BlockPos water = findFarmWaterSource(c.level(), ref, site);
			if (water == null) {
				c.setActivity("wartet auf einen sicheren Wasserquellblock in Dorfnähe");
				c.st().materialWaitUntil = c.now() + 600;
				return false;
			}
			FarmWaterPlan newWaterPlan = new FarmWaterPlan(site.immutable(), water.immutable(), version,
				c.level().dimension().identifier().toString());
			if (!saveFarmWaterPlan(c, newWaterPlan)) {
				c.setActivity("kann den Wasserweg für das Feld nicht sicher speichern");
				return true;
			}
			return advanceFarmWaterPlan(c, newWaterPlan);
		}
		ExpansionPlan plan = new ExpansionPlan(site.immutable(), crop.seed(), kit.fenceItem(), kit.gateItem(), 0, version);
		if (!saveExpansionPlan(c.v(), plan)) {
			c.setActivity("kann den Feldfortschritt nicht sicher speichern");
			return true;
		}
		return advanceExpansion(c, plan);
	}

	/**
	 * Converts real public wood stock through loaded vanilla recipes until the selected fence/gate family is funded.
	 * A recipe prerequisite is made on a later farmer turn, so logs, planks and sticks remain real paid inputs.
	 */
	private static boolean prepareFenceKit(Ctx c, List<Container> stores, Container publicStock, int requiredFences) {
		if (publicStock == null) return false;
		FenceKit kit = partialFenceKit(stores, requiredFences);
		if (kit == null) return false;
		int fences = count(stores, kit.fenceItem());
		if (fences < requiredFences) {
			Predicate<ItemStack> output = stack -> stack.is(kit.fenceItem());
			if (RecipeCrafting.craft(c, publicStock, null, output, requiredFences + 2)) return true;
			if (RecipeCrafting.craftPrerequisite(c, publicStock, null, kit.fenceItem())) return true;
		}
		if (count(stores, kit.gateItem()) < 1) {
			Predicate<ItemStack> output = stack -> stack.is(kit.gateItem());
			if (RecipeCrafting.craft(c, publicStock, null, output, 1)) return true;
			if (RecipeCrafting.craftPrerequisite(c, publicStock, null, kit.gateItem())) return true;
		}
		return false;
	}

	private static FenceKit partialFenceKit(List<Container> stores, int requiredFences) {
		FenceKit best = null;
		int bestScore = -1;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (stack.isEmpty()) continue;
			Item fence = stack.is(ItemTags.WOODEN_FENCES) ? stack.getItem() : fenceForGate(stack.getItem());
			FenceKit candidate = fenceKitForFence(fence);
			if (candidate == null) continue;
			int score = Math.min(requiredFences, count(stores, candidate.fenceItem())) * 2
				+ Math.min(1, count(stores, candidate.gateItem()));
			if (score > bestScore) {
				best = candidate;
				bestScore = score;
			}
		}
		return best != null ? best : fenceKitForFence(Blocks.OAK_FENCE.asItem());
	}

	private static Item fenceForGate(Item gateItem) {
		Identifier id = BuiltInRegistries.ITEM.getKey(gateItem);
		String path = id.getPath();
		if (!path.endsWith("_fence_gate")) return Items.AIR;
		Identifier fenceId = Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - 5));
		return BuiltInRegistries.ITEM.getOptional(fenceId).orElse(Items.AIR);
	}

	private static FenceKit fenceKitForFence(Item fenceItem) {
		if (fenceItem == null || fenceItem == Items.AIR || !new ItemStack(fenceItem).is(ItemTags.WOODEN_FENCES)) return null;
		Identifier id = BuiltInRegistries.ITEM.getKey(fenceItem);
		String path = id.getPath();
		if (!path.endsWith("_fence")) return null;
		Identifier gateId = Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - 6) + "_fence_gate");
		Item gateItem = BuiltInRegistries.ITEM.getOptional(gateId).orElse(Items.AIR);
		Block fence = Block.byItem(fenceItem), gate = Block.byItem(gateItem);
		if (gateItem == Items.AIR || !gate.defaultBlockState().is(BlockTags.FENCE_GATES)) return null;
		return new FenceKit(fenceItem, fence, gateItem, gate);
	}

	private static Container combinedStock(List<Container> stores) {
		if (stores.isEmpty()) return null;
		Container combined = stores.getFirst();
		for (int i = 1; i < stores.size(); i++) {
			Container next = stores.get(i);
			if (next != combined) combined = new CompoundContainer(combined, next);
		}
		return combined;
	}

	private static Container publicAndFarmerStock(List<Container> stores, Villager farmer) {
		Container publicStock = combinedStock(stores);
		return publicStock == null ? farmer.getInventory() : new CompoundContainer(publicStock, farmer.getInventory());
	}

	private static boolean hasWaterBucket(Ctx c, List<Container> stores) {
		return count(stores, Items.WATER_BUCKET) > 0
			|| JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.WATER_BUCKET)) > 0;
	}

	private static boolean hasEmptyFarmBucket(Ctx c, List<Container> stores) {
		return count(stores, Items.BUCKET) > 0
			|| JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.BUCKET)) > 0;
	}

	private static boolean ensureEmptyFarmBucket(Ctx c, List<Container> stores) {
		if (hasEmptyFarmBucket(c, stores)) return false;
		Container actualStock = publicAndFarmerStock(stores, c.v());
		Predicate<ItemStack> bucket = stack -> stack.is(Items.BUCKET);
		if (RecipeCrafting.craft(c, actualStock, null, bucket, 1)) return true;
		return RecipeCrafting.craftPrerequisite(c, actualStock, null, Items.BUCKET);
	}

	private static boolean farmWaterPlanStillNeeded(Ctx c, FarmWaterPlan plan) {
		VillageRef ref = c.village();
		if (ref == null || !plan.dimension().equals(c.level().dimension().identifier().toString())
			|| ref.data().hasPlan() || ref.data().hasBuildProjectType(Blueprints.FARM)
			|| ref.data().farms >= Config.MAX_FARMS) return false;
		boolean starter = plan.version() == 3;
		if (starter != (ref.data().farms == 0)) return false;
		if (!starter && !belowFoodTarget(c)) return false;
		List<Container> stores = Balance.storage(c.level(), ref);
		boolean seedStockReady = seasonalSeed(c.level(), plan.field(), stores, starter) != null;
		if (!seedStockReady) return false;
		if (!clusterAllowedByZones(ref, plan.field(), plan.version())
			|| !(starter ? safeStarterClusterSite(c.level(), plan.field()) : safeClusterSite(c.level(), plan.field()))) return false;
		return !hasWaterBucket(c, stores) && hasEmptyFarmBucket(c, stores);
	}

	private static boolean advanceFarmWaterPlan(Ctx c, FarmWaterPlan plan) {
		VillageRef ref = c.village();
		if (!farmWaterPlanStillNeeded(c, plan)) {
			clearFarmWaterPlan(c.v());
			resetApproach(c);
			return false;
		}
		List<Container> stores = Balance.storage(c.level(), ref);
		if (JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.BUCKET)) == 0
			&& !canStoreAll(c.v().getInventory(), List.of(new ItemStack(Items.WATER_BUCKET)))) {
			clearFarmWaterPlan(c.v());
			c.setActivity("wartet auf freien Platz für den gefüllten Wassereimer");
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		BlockPos source = safeFarmWaterSource(c.level(), ref, plan.field(), plan.source()) ? plan.source()
			: findFarmWaterSource(c.level(), ref, plan.field());
		if (source == null) {
			clearFarmWaterPlan(c.v());
			c.setActivity("wartet auf eine sichere Wasserquelle für das Feld");
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		if (!source.equals(plan.source())) {
			FarmWaterPlan refreshed = new FarmWaterPlan(plan.field(), source, plan.version(), plan.dimension());
			if (!saveFarmWaterPlan(c, refreshed)) return true;
			plan = refreshed;
		}
		BlockPos stand = farmWaterStand(c.level(), source);
		if (stand == null) {
			clearFarmWaterPlan(c.v());
			c.st().materialWaitUntil = c.now() + 600;
			return false;
		}
		c.setActivity("holt einen bezahlten Eimer Wasser für das neue Feld");
		Anim.hold(c, Items.BUCKET);
		long previousRouteStart = c.st().walkStart;
		if (!JobUtil.approachLong(c, stand, 0.6)) {
			if (previousRouteStart > 0L && c.st().walkStart == 0L && c.st().cooldownUntil > c.now()) {
				clearFarmWaterPlan(c.v());
				c.st().materialWaitUntil = c.st().cooldownUntil;
				c.setActivity("sucht nach einem unerreichbaren Wasserweg eine neue Quelle");
			}
			return true;
		}
		if (!safeFarmWaterSource(c.level(), ref, plan.field(), source)) {
			clearFarmWaterPlan(c.v());
			resetApproach(c);
			return true;
		}
		List<Taken> bucket = new ArrayList<>(1);
		List<Container> bucketSources = new ArrayList<>();
		bucketSources.add(c.v().getInventory());
		bucketSources.addAll(stores);
		if (!take(bucketSources, Items.BUCKET, 1, bucket)) {
			clearFarmWaterPlan(c.v());
			resetApproach(c);
			return false;
		}
		if (!c.level().setBlock(source, Blocks.AIR.defaultBlockState(), 3)) {
			restore(bucket);
			clearFarmWaterPlan(c.v());
			resetApproach(c);
			return false;
		}
		ItemStack remainder = c.v().getInventory().addItem(new ItemStack(Items.WATER_BUCKET));
		if (!remainder.isEmpty()) {
			c.level().setBlock(source, Blocks.WATER.defaultBlockState(), 3);
			restore(bucket);
			clearFarmWaterPlan(c.v());
			c.st().materialWaitUntil = c.now() + 200;
			c.setActivity("der Wassereimer passt noch nicht ins Farmerinventar");
			return true;
		}
		c.v().getInventory().setChanged();
		clearFarmWaterPlan(c.v());
		resetApproach(c);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		c.setActivity("holt Wasser für das neue Feld");
		return true;
	}

	private static BlockPos findFarmWaterSource(ServerLevel level, VillageRef ref, BlockPos field) {
		BlockPos best = null;
		long bestDistance = Long.MAX_VALUE;
		for (int dx = -FARM_WATER_SEARCH_RADIUS; dx <= FARM_WATER_SEARCH_RADIUS; dx++) {
			for (int dz = -FARM_WATER_SEARCH_RADIUS; dz <= FARM_WATER_SEARCH_RADIUS; dz++) {
				long horizontal = (long)dx * dx + (long)dz * dz;
				if (horizontal > (long)FARM_WATER_SEARCH_RADIUS * FARM_WATER_SEARCH_RADIUS || horizontal <= 36L) continue;
				BlockPos column = field.offset(dx, 0, dz);
				if (!level.hasChunkAt(column)) continue;
				for (int dy = -7; dy <= 3; dy++) {
					BlockPos candidate = column.above(dy);
					long distance = horizontal + (long)dy * dy;
					if (distance >= bestDistance || !safeFarmWaterSource(level, ref, field, candidate)) continue;
					if (farmWaterStand(level, candidate) == null) continue;
					best = candidate.immutable();
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	private static boolean safeFarmWaterSource(ServerLevel level, VillageRef ref, BlockPos field, BlockPos source) {
		if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, source)
			|| !level.hasChunkAt(source) || !level.hasChunkAt(source.above())) return false;
		BlockState state = level.getBlockState(source);
		if (!state.is(Blocks.WATER) || !state.getFluidState().isSource() || !level.getBlockState(source.above()).isAir()
			|| level.getBlockEntity(source) != null || level.getBlockState(source.below()).is(Blocks.FARMLAND)
			|| horizontalDistanceSquared(field, source) <= 36L || nearbyHomePoi(level, source)) return false;
		for (ZonePolicy.Zone zone : farmZones(ref)) {
			if (source.getX() >= zone.minX() && source.getX() <= zone.maxX()
				&& source.getZ() >= zone.minZ() && source.getZ() <= zone.maxZ()) return false;
		}
		BlockPos villageChest = ref.chestPos(level);
		if (villageChest != null && horizontalDistanceSquared(villageChest, source) <= 64L) return false;
		for (BlockPos special : de.villagerplus.village.Specials.all(ref).values()) {
			if (special != null && horizontalDistanceSquared(special, source) <= 81L) return false;
		}
		int neighboringSources = 0;
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = source.relative(direction);
			if (!level.hasChunkAt(neighbor)) continue;
			BlockState adjacent = level.getBlockState(neighbor);
			if (adjacent.is(Blocks.WATER) && adjacent.getFluidState().isSource()) neighboringSources++;
			if (adjacent.is(Blocks.FARMLAND)) return false;
		}
		return neighboringSources >= 2;
	}

	private static BlockPos farmWaterStand(ServerLevel level, BlockPos source) {
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			for (int dy = 0; dy >= -1; dy--) {
				BlockPos feet = source.relative(direction).above(dy);
				if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above()) || !level.hasChunkAt(feet.below())) continue;
				if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
					&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) return feet.immutable();
			}
		}
		return null;
	}

	private static boolean takeExpansionMaterial(Ctx c, List<Container> stores, ExpansionStep step, List<Taken> taken) {
		if (step.kind() != ExpansionStepKind.WATER) return take(stores, step.cost(), 1, taken);
		List<Container> sources = new ArrayList<>(stores.size() + 1);
		sources.add(c.v().getInventory());
		sources.addAll(stores);
		return take(sources, Items.WATER_BUCKET, 1, taken);
	}

	private static String farmWaterPlanTag(Villager villager) {
		return villager.entityTags().stream().filter(tag -> tag.startsWith(FARM_WATER_PLAN_TAG_PREFIX)).findFirst().orElse(null);
	}

	private static FarmWaterPlan decodeFarmWaterPlan(String encoded, ServerLevel level) {
		if (encoded == null) return null;
		try {
			String[] parts = encoded.substring(FARM_WATER_PLAN_TAG_PREFIX.length()).split("\\|", -1);
			if (parts.length != 4 || !parts[0].equals(level.dimension().identifier().toString())) return null;
			long field = Long.parseLong(parts[1]), source = Long.parseLong(parts[2]);
			int version = Integer.parseInt(parts[3]);
			if (version != 2 && version != 3) return null;
			return new FarmWaterPlan(BlockPos.of(field), BlockPos.of(source), version, parts[0]);
		} catch (RuntimeException invalid) {
			return null;
		}
	}

	private static boolean saveFarmWaterPlan(Ctx c, FarmWaterPlan plan) {
		String saved = plan.encode();
		List<String> old = c.v().entityTags().stream().filter(tag -> tag.startsWith(FARM_WATER_PLAN_TAG_PREFIX)).toList();
		if (old.size() == 1 && old.getFirst().equals(saved)) return true;
		old.forEach(c.v()::removeTag);
		if (!c.v().addTag(saved)) {
			old.forEach(c.v()::addTag);
			return false;
		}
		c.v().setPersistenceRequired();
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		JobUtil.clearLongRoute(c.v());
		c.st().clearTarget();
		c.st().walkStart = 0L;
		c.st().bestDist = 0.0;
		c.st().lastMoveAt = 0L;
		c.st().stuckCount = 0;
		return true;
	}

	private static void clearFarmWaterPlan(Villager villager) {
		List<String> tags = villager.entityTags().stream().filter(tag -> tag.startsWith(FARM_WATER_PLAN_TAG_PREFIX)).toList();
		if (tags.isEmpty()) return;
		tags.forEach(villager::removeTag);
		villager.setPersistenceRequired();
		JobUtil.clearLongRoute(villager);
		villager.getNavigation().stop();
		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
	}

	/** One visible, paid block task at a time; the villager tag stores the site and cursor in the save. */
	private static boolean advanceExpansion(Ctx c, ExpansionPlan plan) {
		VillageRef ref = c.village();
		int planSteps = expansionSteps(plan);
		if (plan.cursor() < 0 || plan.cursor() > planSteps) return true;
		if (plan.cursor() == 0 && ref.data().farms > 0 && !belowFoodTarget(c)) {
			removeExpansionPlan(c.v());
			c.setActivity("die Feldplanung endet, der Nahrungsvorrat ist wieder gedeckt");
			return true;
		}
		if (!clusterAllowedByZones(ref, plan.center(), plan.version())) {
			c.setActivity("wartet auf Freigabe der geplanten Feldzone");
			return true;
		}
		rememberExpansionCropSpots(c, plan, planSteps);
		if (plan.cursor() == planSteps) {
			completeExpansion(c, plan);
			return true;
		}
		ExpansionStep step = expansionStep(plan, plan.cursor());
		if (step == null) return true;
		if (!c.level().hasChunkAt(step.pos()) || !c.level().hasChunkAt(step.pos().below())
			|| !c.level().hasChunkAt(step.pos().above())) {
			c.setActivity("wartet auf geladenes Feldgebiet");
			return true;
		}
		// Serene Seasons: never sow a crop that cannot grow now. The cursor stays untouched (fences, water and gate steps are not
		// season-bound); returning false lets the farmer harvest, tend animals and craft until the season fits again.
		boolean firstField = c.village() != null && c.village().data().farms == 0;
		if (step.kind() == ExpansionStepKind.CROP && !stepAlreadyPlaced(c.level(), plan, step) && !firstField
			&& seasonBlocksSeed(c.level(), step.pos(), plan.seed())) {
			c.setActivity("wartet auf eine Jahreszeit, in der das Saatgut wächst");
			return false;
		}
		c.setActivity(activityFor(step.kind()));
		Anim.hold(c, Items.IRON_HOE);
		BlockPos workStand = step.kind() == ExpansionStepKind.WATER ? step.pos().above() : step.pos();
		if (!JobUtil.approach(c, workStand, 1.8)) return true;
		if (stepAlreadyPlaced(c.level(), plan, step)) {
			ExpansionPlan next = plan.next();
			if (!saveExpansionPlan(c.v(), next)) return true;
			if (next.cursor() == planSteps) completeExpansion(c, next);
			return true;
		}
		if (!safeExpansionStep(c.level(), step)) {
			c.setActivity("wartet auf freie Fläche für das neue Feld");
			c.st().materialWaitUntil = c.now() + 200;
			return true;
		}
		Map<BlockPos, BlockState> plants = vegetationToClear(c.level(), step);
		if (plants == null) {
			c.setActivity("wartet, weil ein Gebäude oder ein geschützter Block die Feldstelle belegt");
			c.st().materialWaitUntil = c.now() + 200;
			return true;
		}
		if (!plants.isEmpty()) c.setActivity("räumt Wildpflanzen für das neue Feld und " + activityFor(step.kind()));
		List<ItemStack> plantDrops = vegetationDrops(c, plants);
		if (!canStoreAll(c.v().getInventory(), plantDrops)) {
			c.setActivity("wartet auf Platz für die gerodeten Feldpflanzen");
			c.st().materialWaitUntil = c.now() + 100;
			return true;
		}
		List<Container> stores = Balance.storage(c.level(), ref);
		List<Taken> taken = new ArrayList<>(1);
		if (!takeExpansionMaterial(c, stores, step, taken)) {
			c.setActivity("wartet auf Saatgut oder Feldmaterial");
			c.st().materialWaitUntil = c.now() + 200;
			return true;
		}
		BlockPos pos = step.pos();
		BlockState oldSurface = c.level().getBlockState(pos);
		BlockState oldSoil = c.level().getBlockState(pos.below());
		if (!clearVegetation(c, plants)) {
			restoreVegetation(c.level(), plants);
			restore(taken);
			c.setActivity("Feldpflanzen konnten nicht sicher gerodet werden; Material wurde zurückgelegt");
			return true;
		}
		boolean placed;
		switch (step.kind()) {
			case FENCE -> placed = c.level().setBlock(pos, Block.byItem(step.cost()).defaultBlockState(), 3);
			case GATE -> {
				BlockState gate = Block.byItem(step.cost()).defaultBlockState();
				if (plan.version() >= 2 && gate.hasProperty(FenceGateBlock.OPEN)) gate = gate.setValue(FenceGateBlock.OPEN, true);
				placed = c.level().setBlock(pos, gate, 3);
			}
			case WATER -> placed = c.level().setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
			case CROP -> placed = c.level().setBlock(pos.below(), Blocks.FARMLAND.defaultBlockState(), 3)
				&& c.level().setBlock(pos, cropForSeed(plan.seed()).crop().defaultBlockState(), 3);
			default -> placed = false;
		}
		if (!placed) {
			c.level().setBlock(pos, oldSurface, 3);
			if (step.kind() == ExpansionStepKind.WATER || step.kind() == ExpansionStepKind.CROP) {
				c.level().setBlock(pos.below(), oldSoil, 3);
			}
			restoreVegetation(c.level(), plants);
			restore(taken);
			c.setActivity("Feldarbeit abgebrochen, Material wurde zurückgelegt");
			return true;
		}
		if (step.kind() == ExpansionStepKind.WATER) {
			Taken bucket = taken.getFirst();
			ItemStack remainder = HopperBlockEntity.addItem(null, bucket.source(), new ItemStack(Items.BUCKET), null);
			if (!remainder.isEmpty()) {
				c.level().setBlock(pos, oldSurface, 3);
				restoreVegetation(c.level(), plants);
				restore(taken);
				c.setActivity("wartet auf einen Platz für den leeren Wassereimer");
				return true;
			}
		}
		for (ItemStack drop : plantDrops) if (!drop.isEmpty()) JobUtil.give(c.level(), c.v(), drop.copy());
		if (!plants.isEmpty()) JobUtil.swing(c.v());
		ExpansionPlan next = plan.next();
		if (!saveExpansionPlan(c.v(), next)) {
			// The next invocation recognizes the block already placed and advances without charging it twice.
			return true;
		}
		ref.dirty();
		c.v().setPersistenceRequired();
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		c.setActivity(activityFor(step.kind()));
		if (next.cursor() == planSteps) completeExpansion(c, next);
		return true;
	}

	private static String activityFor(ExpansionStepKind kind) {
		return switch (kind) {
			case FENCE, GATE -> "zäunt ein neues Feld ein";
			case WATER -> "bewässert ein neues Feld";
			case CROP -> "pflanzt Saatgut auf dem neuen Feld";
		};
	}

	private static void completeExpansion(Ctx c, ExpansionPlan plan) {
		VillageRef ref = c.village();
		ref.data().farms++;
		int residents = Villages.residents(c.level(), ref, Config.VILLAGE_RADIUS, v -> true).size();
		ref.data().extra.nextFarm = nextClusterFarmBuildAt(ref.data().farms,
			ref.data().extra.pen.isEmpty(), residents, c.now());
		ref.dirty();
		removeExpansionPlan(c.v());
		de.villagerplus.village.Journal.add(c.level(), ref, "Landwirtschaft", c.mem().name,
			"legt ein bewässertes und umzäuntes Feld an", 0);
		c.setActivity("das bewässerte Feld ist fertig");
	}

	private static ExpansionStep expansionStep(ExpansionPlan plan, int cursor) {
		boolean compact = plan.version() == 1 || plan.version() == 3;
		int fenceSteps = compact ? LEGACY_FENCE_STEPS : FENCE_STEPS;
		int waterStep = compact ? LEGACY_WATER_STEP : WATER_STEP;
		int cropStep = compact ? LEGACY_CROP_STEP : CROP_STEP;
		int cropCount = compact ? STARTER_SEEDS : CLUSTER_SEEDS;
		if (cursor < fenceSteps) {
			int index = 0;
			int max = compact ? 2 : 3;
			for (int dx = -2; dx <= max; dx++) for (int dz = -2; dz <= max; dz++) {
				boolean perimeter = compact
					? Math.abs(dx) == 2 || Math.abs(dz) == 2
					: dx == -2 || dx == 3 || dz == -2 || dz == 3;
				if (!perimeter) continue;
				if (index++ != cursor) continue;
				boolean gate = dx == 0 && dz == max;
				return new ExpansionStep(plan.center().offset(dx, 0, dz), gate ? plan.gateItem() : plan.fenceItem(),
					gate ? ExpansionStepKind.GATE : ExpansionStepKind.FENCE);
			}
		}
		if (cursor == waterStep) return new ExpansionStep(plan.center().below(), Items.WATER_BUCKET, ExpansionStepKind.WATER);
		int cropIndex = cursor - cropStep;
		if (cropIndex >= 0 && cropIndex < cropCount) {
			int index = 0;
			int max = compact ? 1 : 2;
			for (int dx = -1; dx <= max; dx++) for (int dz = -1; dz <= max; dz++) {
				if (dx == 0 && dz == 0) continue;
				if (index++ == cropIndex) return new ExpansionStep(plan.center().offset(dx, 0, dz), plan.seed(), ExpansionStepKind.CROP);
			}
		}
		return null;
	}

	private static void rememberExpansionCropSpots(Ctx c, ExpansionPlan plan, int planSteps) {
		for (int cursor = plan.cursor(); cursor < planSteps; cursor++) {
			ExpansionStep step = expansionStep(plan, cursor);
			if (step != null && step.kind() == ExpansionStepKind.CROP) {
				VillagerMemory.remember(c.mem().workSpots, step.pos(), Config.MEMORY_MAX_WORK_SPOTS);
			}
		}
	}

	private static int expansionSteps(ExpansionPlan plan) {
		return plan.version() == 1 || plan.version() == 3 ? LEGACY_EXPANSION_STEPS : EXPANSION_STEPS;
	}

	private static boolean safeExpansionStep(ServerLevel level, ExpansionStep step) {
		BlockPos pos = step.pos();
		if (step.kind() == ExpansionStepKind.WATER) {
			return farmableSoil(level.getBlockState(pos)) && farmCellClearable(level, pos.above())
				&& farmCellClearable(level, pos.above(2));
		}
		if (!farmCellClearable(level, pos) || !farmCellClearable(level, pos.above())) return false;
		BlockState floor = level.getBlockState(pos.below());
		return switch (step.kind()) {
			case FENCE, GATE -> floor.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP);
			case CROP -> farmableSoil(floor);
			case WATER -> false;
		};
	}

	private static boolean stepAlreadyPlaced(ServerLevel level, ExpansionPlan plan, ExpansionStep step) {
		BlockState state = level.getBlockState(step.pos());
		return switch (step.kind()) {
			case FENCE, GATE -> state.is(Block.byItem(step.cost()));
			case WATER -> state.is(Blocks.WATER) && level.getBlockState(step.pos().above()).isAir();
			case CROP -> state.is(cropForSeed(plan.seed()).crop()) && level.getBlockState(step.pos().below()).is(Blocks.FARMLAND);
		};
	}

	private static SeedCrop cropForSeed(Item seed) {
		return SEED_CROPS.stream().filter(crop -> crop.seed() == seed).findFirst().orElse(SEED_CROPS.getFirst());
	}

	private static ActiveExpansion activeExpansion(Ctx c, VillageRef ref) {
		return Villages.residents(c.level(), ref, Config.VILLAGE_RADIUS, v -> true).stream()
			.filter(v -> v != c.v()).sorted(Comparator.comparing((Villager v) -> v.getUUID().toString()))
			.map(v -> new ActiveExpansion(v, expansionStateTag(v)))
			.filter(active -> active.encoded() != null).findFirst().orElse(null);
	}

	private static boolean isFarmer(Villager villager) {
		var profession = villager.getVillagerData().profession();
		return profession.is(net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER)
			|| "farmer".equals(de.villagerplus.Compat.jobKey(profession));
	}

	static String expansionStateTag(Villager villager) {
		return villager.entityTags().stream().filter(tag -> tag.startsWith(EXPANSION_TAG_PREFIX)).sorted().findFirst().orElse(null);
	}

	static String farmWaterPlanTagForChecks(Villager villager) {
		return farmWaterPlanTag(villager);
	}

	static BlockPos farmWaterSourceForChecks(Villager villager, ServerLevel level) {
		FarmWaterPlan plan = decodeFarmWaterPlan(farmWaterPlanTag(villager), level);
		return plan == null ? null : plan.source();
	}

	static BlockPos farmWaterStandForChecks(ServerLevel level, BlockPos source) {
		return farmWaterStand(level, source);
	}

	static int expansionCursorForChecks(Villager villager) {
		ExpansionPlan plan = decodeExpansionPlan(expansionStateTag(villager));
		return plan == null ? -1 : plan.cursor();
	}

	static int expansionCursorForChecks(String encoded) {
		ExpansionPlan plan = decodeExpansionPlan(encoded);
		return plan == null ? -1 : plan.cursor();
	}

	static BlockPos expansionStepPosForChecks(BlockPos center, int cursor) {
		ExpansionPlan fixture = new ExpansionPlan(center, Items.WHEAT_SEEDS, Items.OAK_FENCE, Items.OAK_FENCE_GATE, cursor, 2);
		ExpansionStep step = expansionStep(fixture, cursor);
		return step == null ? null : step.pos();
	}

	static BlockPos starterExpansionStepPosForChecks(BlockPos center, int cursor) {
		ExpansionPlan fixture = new ExpansionPlan(center, Items.WHEAT_SEEDS, Items.OAK_FENCE, Items.OAK_FENCE_GATE, cursor, 3);
		ExpansionStep step = expansionStep(fixture, cursor);
		return step == null ? null : step.pos();
	}

	static BlockPos legacyExpansionStepPosForChecks(BlockPos center, int cursor) {
		ExpansionPlan fixture = new ExpansionPlan(center, Items.WHEAT_SEEDS, Items.OAK_FENCE, Items.OAK_FENCE_GATE, cursor, 1);
		ExpansionStep step = expansionStep(fixture, cursor);
		return step == null ? null : step.pos();
	}

	private static ExpansionPlan decodeExpansionPlan(String encoded) {
		if (encoded == null || !encoded.startsWith(EXPANSION_TAG_PREFIX)) return null;
		try {
			String[] parts = encoded.split("\\|", -1);
			if (parts.length != 6) return null;
			int version = switch (parts[0]) {
				case "vp_farm_expansion_v1" -> 1;
				case "vp_farm_expansion_v2" -> 2;
				case "vp_farm_expansion_v3" -> 3;
				default -> 0;
			};
			if (version == 0) return null;
			BlockPos center = BlockPos.of(Long.parseLong(parts[1]));
			Item seed = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[2])).orElse(null);
			Item fence = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[3])).orElse(null);
			Item gate = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[4])).orElse(null);
			int cursor = Integer.parseInt(parts[5]);
			if (seed == null || fence == null || gate == null || cropForSeedOrNull(seed) == null
				|| !new ItemStack(fence).is(ItemTags.WOODEN_FENCES)
				|| !Block.byItem(gate).defaultBlockState().is(BlockTags.FENCE_GATES)
				|| cursor < 0 || cursor > expansionSteps(version)) return null;
			return new ExpansionPlan(center, seed, fence, gate, cursor, version);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static SeedCrop cropForSeedOrNull(Item seed) {
		return SEED_CROPS.stream().filter(crop -> crop.seed() == seed).findFirst().orElse(null);
	}

	private static int expansionSteps(int version) {
		return version == 1 || version == 3 ? LEGACY_EXPANSION_STEPS : EXPANSION_STEPS;
	}

	private static boolean saveExpansionPlan(Villager villager, ExpansionPlan plan) {
		List<String> previous = villager.entityTags().stream().filter(tag -> tag.startsWith(EXPANSION_TAG_PREFIX)).toList();
		previous.forEach(villager::removeTag);
		if (!villager.addTag(plan.encode())) {
			previous.forEach(villager::addTag);
			return false;
		}
		villager.setPersistenceRequired();
		return true;
	}

	private static void removeExpansionPlan(Villager villager) {
		villager.entityTags().stream().filter(tag -> tag.startsWith(EXPANSION_TAG_PREFIX)).toList().forEach(villager::removeTag);
	}

	private static BlockPos findClusterSite(Ctx c, VillageRef ref) {
		return findClusterSite(c, ref, ref.data().farms == 0);
	}

	private static BlockPos findClusterSite(Ctx c, VillageRef ref, boolean starter) {
		BlockPos center = c.v().getBrain().getMemory(MemoryModuleType.JOB_SITE)
			.filter(p -> p.dimension() == c.level().dimension()).map(GlobalPos::pos).orElse(ref.bell());
		return findClusterSite(c.level(), ref, center, starter);
	}

	/** Farm zones reserve the entire crop, irrigation and fence footprint; no outside fallback is allowed while one exists. */
	static BlockPos findClusterSite(ServerLevel level, VillageRef ref, BlockPos center) {
		return findClusterSite(level, ref, center, ref.data().farms == 0);
	}

	private static BlockPos findClusterSite(ServerLevel level, VillageRef ref, BlockPos center, boolean starter) {
		List<ZonePolicy.Zone> zones = farmZones(ref);
		int positiveExtent = starter ? 2 : 3;
		if (!zones.isEmpty()) {
			java.util.Set<Long> seen = new java.util.HashSet<>();
			List<BlockPos> candidates = new ArrayList<>();
			for (ZonePolicy.Zone zone : zones) {
				for (int x = zone.minX() + 2; x <= zone.maxX() - positiveExtent; x++) {
					for (int z = zone.minZ() + 2; z <= zone.maxZ() - positiveExtent; z++) {
						if (seen.add(BlockPos.asLong(x, 0, z))) candidates.add(new BlockPos(x, center.getY(), z));
					}
				}
			}
			candidates.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(center))
				.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
			for (BlockPos candidate : candidates) {
				if (!level.hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) continue;
				BlockPos site = new BlockPos(candidate.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					candidate.getX(), candidate.getZ()), candidate.getZ());
				if (starter ? safeStarterClusterSite(level, site) : safeClusterSite(level, site)) return site;
			}
			return null;
		}
		// Keep the established no-zone search order and radius for existing worlds.
		for (int radius = 8; radius <= MAX_CLUSTER_RADIUS; radius += 4) {
			// A four-block grid misses many viable gaps between houses and CTOV structures. Two-block
			// spacing keeps this bounded search cheap while roughly doubling its candidate coverage.
			for (int dx = -radius; dx <= radius; dx += 2) for (int dz = -radius; dz <= radius; dz += 2) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				int x = center.getX() + dx, z = center.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos candidate = new BlockPos(x, y, z);
				if (starter ? safeStarterClusterSite(level, candidate) : safeClusterSite(level, candidate)) return candidate;
			}
		}
		return null;
	}

	/** Re-read persisted zones at commit time so an edited zone cannot authorize an old walking target. */
	static boolean clusterAllowedByZones(VillageRef ref, BlockPos site) {
		return clusterAllowedByZones(ref, site, 2);
	}

	private static boolean clusterAllowedByZones(VillageRef ref, BlockPos site, int version) {
		List<ZonePolicy.Zone> zones = farmZones(ref);
		int max = version == 1 || version == 3 ? 2 : 3;
		return zones.isEmpty() || zones.stream().anyMatch(zone -> site.getX() - 2 >= zone.minX()
			&& site.getX() + max <= zone.maxX() && site.getZ() - 2 >= zone.minZ() && site.getZ() + max <= zone.maxZ());
	}

	private static List<ZonePolicy.Zone> farmZones(VillageRef ref) {
		if (ref == null) return List.of();
		return ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.ZONES, List.of()).stream()
			.filter(zone -> "farm".equals(zone.kind()) && ZonePolicy.validBounds(ref, zone.kind(),
				zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
	}

	private static FenceKit fenceKit(List<Container> stores, int requiredFences) {
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.is(ItemTags.WOODEN_FENCES)) continue;
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
			String path = id.getPath();
			if (!path.endsWith("_fence")) continue;
			Identifier gateId = Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - 6) + "_fence_gate");
			Item gateItem = BuiltInRegistries.ITEM.getOptional(gateId).orElse(Items.AIR);
			Block fence = Block.byItem(stack.getItem()), gate = Block.byItem(gateItem);
			if (gateItem != Items.AIR && gate.defaultBlockState().is(BlockTags.FENCE_GATES)
				&& count(stores, stack.getItem()) >= requiredFences && count(stores, gateItem) >= 1)
				return new FenceKit(stack.getItem(), fence, gateItem, gate);
		}
		return null;
	}

	private static int count(List<Container> stores, Item item) {
		return stores.stream().mapToInt(box -> JobUtil.count(box, stack -> stack.is(item))).sum();
	}

	private static boolean take(List<Container> stores, Item item, int amount, List<Taken> taken) {
		int left = amount;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize() && left > 0; slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.is(item)) continue;
			int n = Math.min(left, stack.getCount());
			box.removeItem(slot, n);
			box.setChanged();
			taken.add(new Taken(box, item, n));
			left -= n;
		}
		return left == 0;
	}

	private static void restore(List<Taken> taken) {
		for (Taken part : taken) {
			ItemStack remainder = HopperBlockEntity.addItem(null, part.source(), new ItemStack(part.item(), part.count()), null);
			if (!remainder.isEmpty()) throw new IllegalStateException("Farm cluster rollback could not restore communal stock");
		}
	}

	/** Erst die gemerkten Felder pruefen (billig), sonst die Umgebung des Arbeitsplatzes absuchen. */
	private static BlockPos find(Ctx c) {
		ServerLevel level = c.level();
		Villager v = c.v();
		List<ZonePolicy.Zone> zones = farmZones(c.village());
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : c.mem().workSpots) {
			if (mature(level, p) && harvestAllowedByFarmZones(zones, p)) {
				double d = p.distSqr(v.blockPosition());
				if (d < bestDist) {
					best = p;
					bestDist = d;
				}
			}
		}
		if (best != null) {
			return best;
		}
		BlockPos center = v.blockPosition();
		GlobalPos job = v.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
		if (job != null && job.dimension() == level.dimension()) {
			center = job.pos();
		}
		int r = Config.FARM_RADIUS;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -2, -r), center.offset(r, 2, r))) {
			if (mature(level, p) && harvestAllowedByFarmZones(zones, p)) {
				double d = p.distSqr(v.blockPosition());
				if (d < bestDist) {
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		return best;
	}

	/** With explicit farm zones, both remembered and searched crops must still belong to an authorized field. */
	private static boolean harvestAllowedByFarmZones(List<ZonePolicy.Zone> zones, BlockPos crop) {
		return zones.isEmpty() || zones.stream().anyMatch(zone -> crop.getX() >= zone.minX() && crop.getX() <= zone.maxX()
			&& crop.getZ() >= zone.minZ() && crop.getZ() <= zone.maxZ());
	}
}
