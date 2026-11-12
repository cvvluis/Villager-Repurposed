package de.villagerplus.build;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.MarketData;
import de.villagerplus.village.Sites;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fixture checks for the step-wise, persisted house site search ({@link Builder#stepHouseSiteSearch}).
 *
 * <p>Synthetic terrain: a flat meadow with a hilly, forested core (relief 4 inside every house footprint, trunks every
 * third block) around the bell. The inner pages of the search find nothing there, so the cursor has to advance page by
 * page until the meadow ring starts. This proves the mechanism, not that a natural village ever reaches it.
 */
public final class HouseSiteSearchChecks {
	private HouseSiteSearchChecks() { }

	private static final int PLATFORM_HALF = 64;
	private static final int HILL_OUTER = 34;
	private static final int MIN_RADIUS = 12;
	private static final int MAX_RADIUS = 52;
	private static final int Y_BELOW = 4;
	private static final int Y_ABOVE = 14;

	public static int run(ServerLevel level, BlockPos origin) {
		BlockPos base = origin.below(); // meadow surface block row (grass); candidates stand one above
		int minY = base.getY() - Y_BELOW, maxY = base.getY() + Y_ABOVE;
		int side = 2 * PLATFORM_HALF + 1;
		BlockState[][] snapshot = new BlockState[side * side][];
		for (int cx = (origin.getX() - PLATFORM_HALF) >> 4; cx <= (origin.getX() + PLATFORM_HALF) >> 4; cx++)
			for (int cz = (origin.getZ() - PLATFORM_HALF) >> 4; cz <= (origin.getZ() + PLATFORM_HALF) >> 4; cz++) level.getChunk(cx, cz);
		for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++) {
			BlockState[] column = new BlockState[maxY - minY + 1];
			for (int y = minY; y <= maxY; y++) column[y - minY] = level.getBlockState(new BlockPos(origin.getX() + dx, y, origin.getZ() + dz));
			snapshot[(dx + PLATFORM_HALF) * side + dz + PLATFORM_HALF] = column;
		}
		BlockPos bell = origin;
		Villager worker = null;
		int checks = 0;
		try {
			buildTerrain(level, origin, base);
			level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);
			var bellEntity = (net.minecraft.world.level.block.entity.BellBlockEntity) level.getBlockEntity(bell);
			VillageData data = new VillageData();
			bellEntity.setAttached(VillagerPlus.VILLAGE, data);
			bellEntity.setAttached(VillagerPlus.NETWORK, TradeNetwork.create(bell));
			bellEntity.setAttached(VillagerPlus.MARKET, MarketData.empty());
			VillageRef ref = new VillageRef(bell, data, bellEntity);
			// The worker stands more than 48 blocks from the bell: Builder.routeConnected then does not path-test (a rule of
			// the production code), so this fixture isolates the cursor/budget/persistence mechanism from navigation.
			worker = EntityTypes.VILLAGER.spawn(level, bell.offset(58, 0, 0), EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("House site fixture: could not spawn a builder");
			worker.addTag("vp090_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			VillageStyle style = VillageStyle.detect(level, bell, worker);
			long now0 = level.getGameTime() + 100_000L;
			long profile = Palette.profileCode(level, bell);
			int variant = Builder.newPlanVariant(Blueprints.HOUSE,
				Architect.withSizeTier(((long) 4711 << 6) | profile, Architect.SizeTier.COMPACT));
			int total = Sites.systematicCandidateCount(MIN_RADIUS, MAX_RADIUS);
			int budget = 96;

			// --- trigger condition -------------------------------------------------------------------------------------
			checks += require(Builder.needsNaturalHousePreparation(Blueprints.HOUSE, variant, false)
				&& !Builder.needsNaturalHousePreparation(Blueprints.HOUSE, variant, true)
				&& !Builder.needsNaturalHousePreparation(Blueprints.SCHOOL, variant, false),
				"the step-wise search applies to new catalog houses only (not legacy-source plans, not other building types)");
			checks += require(total > 3 * budget && total <= 4096,
				"the fixture ring needs several budget pages (" + total + " candidates)");
			long terrainHash = hash(level, origin, minY, maxY);

			// --- page 1: bounded, cursor advances, state is persisted ---------------------------------------------------
			Builder.forgetHouseSiteSearchMemory();
			checks += require(data.extra.houseSiteSearch.isEmpty() && !Builder.hasHouseSiteSearchInMemory(level, ref),
				"no house search state exists before the first step");
			Ctx c = ctx(level, worker, ref, now0);
			Builder.HouseSiteSearch ongoing = Builder.ongoingHouseSiteSearch(c, ref, MIN_RADIUS, false);
			checks += require(ongoing == null, "no ongoing search is invented for a village without saved state");
			Builder.HouseSiteStep step = Builder.stepHouseSiteSearch(c, ref, style, Blueprints.HOUSE, ongoing, variant,
				MIN_RADIUS, MAX_RADIUS, budget);
			checks += require(step.site() == null && !step.waiting() && !step.pageComplete(),
				"hilly forested ground around the bell yields no site on the first page");
			checks += require(step.evaluated() <= budget && step.evaluated() > 0,
				"the first page checks at most the candidate budget (" + step.evaluated() + " <= " + budget + ")");
			checks += require(step.search().nextCandidate() == budget,
				"the cursor advances by exactly the page budget (cursor " + step.search().nextCandidate() + ")");
			checks += require(Builder.hasHouseSiteSearchInMemory(level, ref) && data.extra.houseSiteSearch.isPresent()
				&& data.extra.houseSiteSearch.get().nextCandidate() == budget
				&& data.extra.houseSiteSearch.get().variant() == variant
				&& data.extra.houseSiteSearch.get().minRadius() == MIN_RADIUS,
				"the cursor, design variant and minimum radius are saved in the village data after the first page");
			checks += require(step.search().retryAt == now0 + 20L, "the next page is scheduled one retry interval later");

			// --- wait: before retryAt nothing is scanned -----------------------------------------------------------------
			Builder.HouseSiteStep waiting = Builder.stepHouseSiteSearch(ctx(level, worker, ref, now0 + 5L), ref, style,
				Blueprints.HOUSE, Builder.ongoingHouseSiteSearch(ctx(level, worker, ref, now0 + 5L), ref, MIN_RADIUS, false),
				variant, MIN_RADIUS, MAX_RADIUS, budget);
			checks += require(waiting.waiting() && waiting.site() == null && waiting.evaluated() == 0
				&& waiting.search().nextCandidate() == budget,
				"a call before the retry time scans nothing and keeps the cursor");

			// --- codec round trip and restart ----------------------------------------------------------------------------
			var saved = data.extra.houseSiteSearch.orElseThrow();
			VillageData restored = VillageData.CODEC.parse(JsonOps.INSTANCE,
				VillageData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow()).getOrThrow();
			checks += require(restored.extra.houseSiteSearch.isPresent() && restored.extra.houseSiteSearch.get().equals(saved),
				"the house search cursor survives a village data save/load round trip");
			VillageData legacy = VillageData.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow();
			checks += require(legacy.extra.houseSiteSearch.isEmpty(),
				"older village saves default to no house search");
			var sparse = new com.google.gson.JsonObject();
			var extraJson = new com.google.gson.JsonObject();
			extraJson.add("house_site_search", new com.google.gson.JsonObject());
			sparse.add("extra", extraJson);
			VillageData sparseData = VillageData.CODEC.parse(JsonOps.INSTANCE, sparse).getOrThrow();
			checks += require(sparseData.extra.houseSiteSearch.isPresent()
				&& sparseData.extra.houseSiteSearch.get().nextCandidate() == 0
				&& sparseData.extra.houseSiteSearch.get().minRadius() == 0,
				"a house search entry with missing fields reads with readable defaults");

			Builder.forgetHouseSiteSearchMemory();
			checks += require(!Builder.hasHouseSiteSearchInMemory(level, ref) && data.extra.houseSiteSearch.isPresent(),
				"a restart drops the memory entry but not the saved cursor");
			VillageRef restoredRef = new VillageRef(bell, restored, bellEntity);
			Ctx afterRestart = ctx(level, worker, restoredRef, now0 + 25L);
			Builder.HouseSiteSearch resumed = Builder.ongoingHouseSiteSearch(afterRestart, restoredRef, MIN_RADIUS, false);
			checks += require(resumed != null && resumed.nextCandidate() == budget && resumed.variant == variant
				&& resumed.phaseSeed == step.search().phaseSeed && resumed.retryAt == saved.retryAt(),
				"after a restart the search resumes from the saved cursor, variant and sweep phase (not from candidate 0)");
			Builder.HouseSiteStep second = Builder.stepHouseSiteSearch(afterRestart, restoredRef, style, Blueprints.HOUSE,
				resumed, variant ^ 0x7ffffff0, MIN_RADIUS, MAX_RADIUS, budget);
			checks += require(second.search().variant == variant,
				"the resumed search keeps its saved design instead of the fresh variant offered by the caller");
			checks += require(second.site() == null && second.evaluated() <= budget
				&& second.search().nextCandidate() > budget && second.search().nextCandidate() <= 2 * budget,
				"the resumed second page continues after the first page and stays inside its budget (cursor "
				+ second.search().nextCandidate() + ")");
			checks += require(restored.extra.houseSiteSearch.orElseThrow().nextCandidate() == second.search().nextCandidate(),
				"the resumed page saves its new cursor");

			// --- run to the meadow ------------------------------------------------------------------------------------------
			Ctx loop = afterRestart;
			Builder.HouseSiteStep last = second;
			long now = now0 + 25L;
			int pages = 2;
			int maxEvaluated = Math.max(step.evaluated(), second.evaluated());
			while (last.site() == null && !last.pageComplete() && pages < 12) {
				now += 20L;
				loop = ctx(level, worker, restoredRef, now);
				last = Builder.stepHouseSiteSearch(loop, restoredRef, style, Blueprints.HOUSE,
					Builder.ongoingHouseSiteSearch(loop, restoredRef, MIN_RADIUS, false), variant, MIN_RADIUS, MAX_RADIUS, budget);
				pages++;
				maxEvaluated = Math.max(maxEvaluated, last.evaluated());
			}
			checks += require(last.site() != null && pages >= 3 && pages <= 8,
				"the step-wise search reaches the meadow after several pages (" + pages + " pages)");
			BlockPos found = last.site();
			double distance = Math.sqrt(found.distSqr(bell.atY(found.getY())));
			checks += require(maxEvaluated <= budget, "no page evaluated more than the candidate budget (max " + maxEvaluated + ")");
			checks += require(distance >= MIN_RADIUS && distance <= MAX_RADIUS && distance > HILL_OUTER,
				"the found site lies in the flat meadow ring, outside the hilly core (distance " + Math.round(distance) + ")");
			checks += require(found.getY() == base.getY() + 1 && level.getBlockState(found.below()).is(Blocks.GRASS_BLOCK),
				"the site stands on the natural meadow surface (no pit, no raised pad)");
			checks += require(restoredRef.data().extra.houseSiteSearch.isPresent()
				&& restoredRef.data().extra.houseSiteSearch.get().nextCandidate() == last.search().nextCandidate()
				&& last.search().nextCandidate() > 2 * budget,
				"the saved cursor follows the search up to the found site (" + last.search().nextCandidate() + ")");
			checks += require(hash(level, origin, minY, maxY) == terrainHash,
				"searching only reads the world: not a single block changed");

			// --- a started plan ends the search: no residue -----------------------------------------------------------------
			Builder.dropHouseSiteSearch(level, restoredRef);
			checks += require(!Builder.hasHouseSiteSearchInMemory(level, restoredRef)
				&& restoredRef.data().extra.houseSiteSearch.isEmpty(),
				"starting the plan (drop) removes the memory entry and the saved cursor");
			checks += require(Builder.ongoingHouseSiteSearch(ctx(level, worker, restoredRef, now + 20L), restoredRef, MIN_RADIUS, false) == null,
				"after the drop no search resumes");

			// --- spacing: an existing house keeps the 12-block margin -------------------------------------------------------
			BlockPos houseCenter = found;
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int y = 1; y <= 4; y++)
				level.setBlock(houseCenter.offset(dx, y - 1, dz), Blocks.OAK_PLANKS.defaultBlockState(), 2);
			Builder.forgetHouseSiteSearchMemory();
			VillageData spacingData = new VillageData();
			bellEntity.setAttached(VillagerPlus.VILLAGE, spacingData);
			VillageRef spacingRef = new VillageRef(bell, spacingData, bellEntity);
			Builder.HouseSiteStep spaced = null;
			long spacedNow = now + 100L;
			int spacedPages = 0;
			Builder.HouseSiteSearch spacedSearch = null;
			while (spacedPages < 12) {
				spacedNow += 20L;
				Ctx sc = ctx(level, worker, spacingRef, spacedNow);
				spacedSearch = Builder.ongoingHouseSiteSearch(sc, spacingRef, MIN_RADIUS, false);
				spaced = Builder.stepHouseSiteSearch(sc, spacingRef, style, Blueprints.HOUSE, spacedSearch, variant, MIN_RADIUS,
					MAX_RADIUS, budget);
				spacedPages++;
				if (spaced.site() != null || spaced.pageComplete()) break;
			}
			checks += require(spaced != null && spaced.site() != null && !spaced.site().equals(found),
				"with a house already standing on the first meadow site the search finds a different site");
			int gap = Math.max(Math.abs(spaced.site().getX() - houseCenter.getX()), Math.abs(spaced.site().getZ() - houseCenter.getZ()))
				- 3 - spaced.half();
			checks += require(gap >= Sites.HOUSE_MARGIN,
				"the new site keeps the 12-block house margin to the existing building (gap " + gap + ")");
			Builder.dropHouseSiteSearch(level, spacingRef);
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int y = 1; y <= 4; y++)
				level.setBlock(houseCenter.offset(dx, y - 1, dz), Blocks.AIR.defaultBlockState(), 2);

			// --- abort paths: no residue ---------------------------------------------------------------------------------------
			Builder.forgetHouseSiteSearchMemory();
			VillageData abortData = new VillageData();
			bellEntity.setAttached(VillagerPlus.VILLAGE, abortData);
			VillageRef abortRef = new VillageRef(bell, abortData, bellEntity);
			long abortNow = now0 + 500_000L;
			Ctx ac = ctx(level, worker, abortRef, abortNow);
			Builder.stepHouseSiteSearch(ac, abortRef, style, Blueprints.HOUSE, null, variant, MIN_RADIUS, MAX_RADIUS, budget);
			checks += require(abortData.extra.houseSiteSearch.isPresent() && Builder.hasHouseSiteSearchInMemory(level, abortRef),
				"a new search for a given minimum radius is saved");
			Builder.HouseSiteSearch changedRadius = Builder.ongoingHouseSiteSearch(ctx(level, worker, abortRef, abortNow + 20L),
				abortRef, MIN_RADIUS + 6, false);
			checks += require(changedRadius == null && abortData.extra.houseSiteSearch.isEmpty()
				&& !Builder.hasHouseSiteSearchInMemory(level, abortRef),
				"a changed minimum radius (village grew) aborts the old search and clears its saved state");

			Builder.stepHouseSiteSearch(ctx(level, worker, abortRef, abortNow + 40L), abortRef, style, Blueprints.HOUSE, null,
				variant, MIN_RADIUS, MAX_RADIUS, budget);
			Builder.forgetHouseSiteSearchMemory();
			Builder.HouseSiteSearch stale = Builder.ongoingHouseSiteSearch(ctx(level, worker, abortRef, abortNow + 40L + 30_000L),
				abortRef, MIN_RADIUS, false);
			checks += require(stale == null && abortData.extra.houseSiteSearch.isEmpty(),
				"a saved search idle for more than 24000 ticks is dropped on the next look instead of resumed");

			Builder.stepHouseSiteSearch(ctx(level, worker, abortRef, abortNow + 60_000L), abortRef, style, Blueprints.HOUSE, null,
				variant, MIN_RADIUS, MAX_RADIUS, budget);
			Builder.HouseSiteSearch legacyPlan = Builder.ongoingHouseSiteSearch(ctx(level, worker, abortRef, abortNow + 60_020L),
				abortRef, MIN_RADIUS, true);
			checks += require(legacyPlan == null && abortData.extra.houseSiteSearch.isEmpty(),
				"a search whose plan is a legacy-source house is not continued and leaves no state");

			// --- completed sweep without a site restarts with a fresh design after the re-probe delay -----------------------
			Builder.forgetHouseSiteSearchMemory();
			VillageData sweepData = new VillageData();
			bellEntity.setAttached(VillagerPlus.VILLAGE, sweepData);
			VillageRef sweepRef = new VillageRef(bell, sweepData, bellEntity);
			long sweepNow = now0 + 900_000L;
			Ctx sc = ctx(level, worker, sweepRef, sweepNow);
			Builder.HouseSiteStep sweep = Builder.stepHouseSiteSearch(sc, sweepRef, style, Blueprints.HOUSE, null, variant,
				MIN_RADIUS, 20, budget);
			checks += require(sweep.site() == null && sweep.pageComplete() && sweep.search().nextCandidate() == 0
				&& sweep.search().retryAt == sweepNow + 1200L
				&& sweepData.extra.houseSiteSearch.get().retryAt() == sweepNow + 1200L
				&& sweepData.extra.houseSiteSearch.get().nextCandidate() == 0,
				"a fully swept ring without a site restarts at candidate 0 after the 1200-tick re-probe delay and saves that");
			Builder.HouseSiteStep early = Builder.stepHouseSiteSearch(ctx(level, worker, sweepRef, sweepNow + 600L), sweepRef,
				style, Blueprints.HOUSE, Builder.ongoingHouseSiteSearch(ctx(level, worker, sweepRef, sweepNow + 600L), sweepRef, MIN_RADIUS, false),
				variant, MIN_RADIUS, 20, budget);
			checks += require(early.waiting() && early.evaluated() == 0, "the re-probe delay is respected");
			Builder.dropHouseSiteSearch(level, sweepRef);
			checks += require(sweepData.extra.houseSiteSearch.isEmpty() && !Builder.hasHouseSiteSearchInMemory(level, sweepRef),
				"the swept search is removable without residue");
			Builder.forgetHouseSiteSearchMemory();
			checks += require(hash(level, origin, minY, maxY) == terrainHash,
				"all abort and restart paths left the terrain untouched");
			return checks;
		} finally {
			Builder.forgetHouseSiteSearchMemory();
			if (worker != null) worker.discard();
			level.removeBlockEntity(bell);
			for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++) {
				BlockState[] column = snapshot[(dx + PLATFORM_HALF) * side + dz + PLATFORM_HALF];
				for (int y = minY; y <= maxY; y++)
					level.setBlock(new BlockPos(origin.getX() + dx, y, origin.getZ() + dz), column[y - minY], 2 | 16);
			}
		}
	}

	private static Ctx ctx(ServerLevel level, Villager worker, VillageRef ref, long now) {
		return new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	/** Meadow with a hilly, forested core: relief 4 across every footprint, a trunk every third block. */
	private static void buildTerrain(ServerLevel level, BlockPos origin, BlockPos base) {
		for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++) {
			int x = origin.getX() + dx, z = origin.getZ() + dz;
			int r2 = dx * dx + dz * dz;
			int relief = r2 <= 8 * 8 || r2 >= HILL_OUTER * HILL_OUTER ? 0 : 3 * (((dx >> 1) ^ (dz >> 1)) & 1) + ((dx + dz) & 1);
			int surface = base.getY() + relief;
			for (int y = base.getY() - Y_BELOW; y <= base.getY() + Y_ABOVE; y++) {
				BlockState state = y < surface ? Blocks.DIRT.defaultBlockState()
					: y == surface ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
				level.setBlock(new BlockPos(x, y, z), state, 2 | 16);
			}
		}
		// Trees: a trunk every third block of the hilly core, with leaf cap.
		for (int dx = -HILL_OUTER; dx <= HILL_OUTER; dx++) for (int dz = -HILL_OUTER; dz <= HILL_OUTER; dz++) {
			int r2 = dx * dx + dz * dz;
			if (r2 <= 10 * 10 || r2 >= (HILL_OUTER - 1) * (HILL_OUTER - 1) || dx % 3 != 0 || dz % 3 != 0) continue;
			int x = origin.getX() + dx, z = origin.getZ() + dz;
			int surface = base.getY() + 3 * (((dx >> 1) ^ (dz >> 1)) & 1) + ((dx + dz) & 1);
			for (int h = 1; h <= 4; h++) level.setBlock(new BlockPos(x, surface + h, z), Blocks.OAK_LOG.defaultBlockState(), 2 | 16);
			for (int lx = -1; lx <= 1; lx++) for (int lz = -1; lz <= 1; lz++) {
				BlockPos leaf = new BlockPos(x + lx, surface + 5, z + lz);
				if (level.getBlockState(leaf).isAir()) level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), 2 | 16);
			}
		}
	}

	private static long hash(ServerLevel level, BlockPos origin, int minY, int maxY) {
		long h = 1125899906842597L;
		for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++)
			for (int y = minY; y <= maxY; y++)
				h = 31 * h + Block.getId(level.getBlockState(new BlockPos(origin.getX() + dx, y, origin.getZ() + dz)));
		return h;
	}

	private static int require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("House site search: " + message);
		return 1;
	}
}
