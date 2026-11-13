package de.villagerplus.job;

import de.villagerplus.compat.SeasonCompat;
import de.villagerplus.compat.SeasonCompat.Season;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Group {@code seasonfarm}: the Serene Seasons adapter and the farmer's seasonal planting choice. The season is simulated with
 * {@link SeasonCompat#setDevOverride}; the real mod is only consulted in {@link #realAdapter}. Without the mod the same group
 * proves the "no restriction" path. Fixtures (platform, field) are restored afterwards; they are not natural evidence.
 */
public final class SeasonFarmChecks {
	private SeasonFarmChecks() { }

	private static final Item[] SEEDS = { Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS };
	private static final Block[] CROPS = { Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS };

	/** Crop tags of Serene Seasons 26.1.2.0.6 (read from its jar): wheat summer+autumn, carrots spring+autumn, potatoes spring, beetroot autumn. */
	private static boolean expected(Season season, int crop) {
		return switch (crop) {
			case 0 -> season == Season.SUMMER || season == Season.AUTUMN;
			case 1 -> season == Season.SPRING || season == Season.AUTUMN;
			case 2 -> season == Season.SPRING;
			default -> season == Season.AUTUMN;
		};
	}

	public static int run(ServerLevel level, BlockPos site) {
		int checks = 0;
		boolean hadOverride = SeasonCompat.hasDevOverride();
		SeasonCompat.clearDevOverride();
		try {
			checks += realAdapter(level, site);
			checks += tagRules();
			checks += overrideHook(level, site);
			checks += seedChoice(level, site);
			checks += fieldFixture(level, site);
		} finally {
			SeasonCompat.clearDevOverride();
			if (hadOverride) SeasonCompat.setDevOverrideOff();
		}
		return checks;
	}

	/** The real mod (dev pack) or its absence, without any hook. */
	private static int realAdapter(ServerLevel level, BlockPos site) {
		int checks = 0;
		level.getChunk(site);
		boolean loaded = SeasonCompat.modLoaded();
		System.out.println("SEASONCHECK mod loaded=" + loaded + " apiResolved=" + SeasonCompat.apiResolved()
			+ " fertilityRule=" + SeasonCompat.fertilityRuleResolved() + " season=" + SeasonCompat.describe(level));
		if (!loaded) {
			check(!SeasonCompat.active(level) && SeasonCompat.currentSeason(level) == null
				&& SeasonCompat.describe(level).equals("absent")
				&& !SeasonCompat.apiResolved() && !SeasonCompat.fertilityRuleResolved(),
				"without Serene Seasons the adapter reports no season and resolves nothing"); checks++;
			for (Block crop : CROPS) {
				check(SeasonCompat.canPlant(level, site, crop), "without Serene Seasons " + crop + " has no restriction"); checks++;
			}
			check(FarmerJob.seasonalSeed(level, site, stockOf(20, 20, 20, 20), false) == Items.WHEAT_SEEDS
				&& FarmerJob.seasonalSeed(level, site, stockOf(8, 0, 0, 0), true) == Items.WHEAT_SEEDS
				&& FarmerJob.seasonalSeed(level, site, stockOf(0, 8, 0, 0), true) == null
				&& !FarmerJob.fieldsDormant(level, site),
				"without Serene Seasons the seed choice is the pre-season rule and fields are never dormant"); checks++;
			return checks;
		}
		check(SeasonCompat.apiResolved(), "the Serene Seasons API (hasSeasons, getSeasonState().getSeason()) resolves"); checks++;
		check(SeasonCompat.fertilityRuleResolved(), "the mod's own fertility rule resolves for exact biome and config handling"); checks++;
		check(SeasonCompat.active(level), "seasons are active in the overworld of the dev pack"); checks++;
		Season season = SeasonCompat.currentSeason(level);
		check(season != null, "the adapter returns a current season in the dev pack (" + season + ")"); checks++;
		boolean warm = level.getBiome(site).value().warmEnoughToRain(site, level.getSeaLevel());
		System.out.println("SEASONCHECK real season=" + season + " biome=" + level.getBiome(site).unwrapKey().map(k -> k.identifier().toString()).orElse("?")
			+ " warmEnoughToRain=" + warm);
		boolean tropical = SeasonCompat.tropical(level, site);
		System.out.println("SEASONCHECK tropical=" + tropical);
		if (warm) {
			for (int i = 0; i < CROPS.length; i++) {
				check(SeasonCompat.canPlant(level, site, CROPS[i]) == SeasonCompat.fertileIn(season, CROPS[i], tropical),
					"the mod's fertility rule agrees with the tag rule for " + CROPS[i] + " in " + season + " (tropical=" + tropical + ")"); checks++;
			}
		} else {
			System.out.println("SEASONCHECK site biome is cold: mod-vs-tag agreement not asserted");
		}
		return checks;
	}

	/** Tag membership of the four field crops per season, taken from the real block registry (no mod class involved). */
	private static int tagRules() {
		int checks = 0;
		boolean loaded = SeasonCompat.modLoaded();
		Season[] seasons = Season.values();
		for (Season season : seasons) {
			for (int i = 0; i < CROPS.length; i++) {
				boolean want = !loaded || expected(season, i);
				check(SeasonCompat.fertileIn(season, CROPS[i], false) == want,
					"tag rule: " + CROPS[i] + " in " + season + " = " + want); checks++;
			}
		}
		if (loaded) {
			for (Season season : seasons) {
				check(SeasonCompat.fertileIn(season, Blocks.NETHER_WART, false), "tag rule: year-round crop nether_wart grows in " + season); checks++;
			}
			check(!SeasonCompat.listed(Blocks.STONE) && SeasonCompat.fertileIn(Season.WINTER, Blocks.STONE, false),
				"tag rule: a block outside every season tag is not restricted"); checks++;
			for (int i = 0; i < CROPS.length; i++) {
				check(SeasonCompat.listed(CROPS[i]) && SeasonCompat.fertileIn(Season.AUTUMN, CROPS[i], true) == (i == 0),
					"tag rule: tropical biomes only keep the summer crop wheat (" + CROPS[i] + ")"); checks++;
			}
		}
		return checks;
	}

	/** The check hook switches seasons on and off and restores the real adapter. */
	private static int overrideHook(ServerLevel level, BlockPos site) {
		int checks = 0;
		for (Season season : Season.values()) {
			SeasonCompat.setDevOverride(season);
			boolean ok = SeasonCompat.active(level) && SeasonCompat.currentSeason(level) == season;
			for (int i = 0; i < CROPS.length; i++) {
				boolean want = !SeasonCompat.modLoaded() || expected(season, i);
				ok &= SeasonCompat.canPlant(level, site, CROPS[i]) == want;
			}
			check(ok, "hook " + season + ": active, season name and planting rule per crop"); checks++;
		}
		SeasonCompat.setDevOverrideOff();
		boolean allFree = !SeasonCompat.active(level) && SeasonCompat.currentSeason(level) == null;
		for (Block crop : CROPS) allFree &= SeasonCompat.canPlant(level, site, crop);
		check(allFree, "hook off: seasons inactive and every crop unrestricted (the disabled-mod path)"); checks++;
		SeasonCompat.clearDevOverride();
		check(!SeasonCompat.hasDevOverride(), "the hook can be cleared"); checks++;
		return checks;
	}

	/** FarmerJob.seasonalSeed and fieldsDormant for simulated seasons, with real Container stock. */
	private static int seedChoice(ServerLevel level, BlockPos site) {
		int checks = 0;
		boolean restrict = SeasonCompat.modLoaded();
		List<Container> full = stockOf(20, 20, 20, 20);
		Object[][] table = {
			// season, non-starter expectation with 20 of each, dormant
			{ Season.SPRING, Items.CARROT, false },
			{ Season.SUMMER, Items.WHEAT_SEEDS, false },
			{ Season.AUTUMN, Items.WHEAT_SEEDS, false },
			{ Season.WINTER, null, true },
		};
		for (Object[] row : table) {
			Season season = (Season) row[0];
			SeasonCompat.setDevOverride(season);
			Item got = FarmerJob.seasonalSeed(level, site, full, false);
			Item want = restrict ? (Item) row[1] : Items.WHEAT_SEEDS;
			check(got == want, "new field in " + season + " with 20 seeds of each kind sows " + want + " (got " + got + ")"); checks++;
			check(FarmerJob.fieldsDormant(level, site) == (restrict && (Boolean) row[2]),
				"fields are dormant exactly in winter: " + season); checks++;
		}
		if (restrict) {
			SeasonCompat.setDevOverride(Season.SPRING);
			check(FarmerJob.seasonalSeed(level, site, stockOf(20, 15, 25, 0), false) == Items.POTATO,
				"spring: the most plentiful in-season seed wins (potato 25 over carrot 15, wheat is out of season)"); checks++;
			check(FarmerJob.seasonalSeed(level, site, stockOf(40, 14, 14, 40), false) == null,
				"spring: out-of-season stock (wheat, beetroot) and sub-threshold carrots/potatoes plant nothing"); checks++;
			SeasonCompat.setDevOverride(Season.AUTUMN);
			check(FarmerJob.seasonalSeed(level, site, stockOf(15, 0, 0, 40), false) == Items.BEETROOT_SEEDS,
				"autumn: beetroot 40 beats wheat 15"); checks++;
			// first field: wheat or, out of season, another stocked in-season crop with the 8-seed payment
			for (Season season : Season.values()) {
				SeasonCompat.setDevOverride(season);
				boolean wheatNow = expected(season, 0);
				check((FarmerJob.seasonalSeed(level, site, stockOf(8, 0, 0, 0), true) == Items.WHEAT_SEEDS) == wheatNow,
					"first field with eight wheat seeds only: sown in " + season + " = " + wheatNow); checks++;
			}
			SeasonCompat.setDevOverride(Season.SPRING);
			check(FarmerJob.seasonalSeed(level, site, stockOf(8, 8, 0, 0), true) == Items.CARROT,
				"first field in spring switches to the stocked in-season carrot"); checks++;
			SeasonCompat.setDevOverride(Season.WINTER);
			check(FarmerJob.seasonalSeed(level, site, stockOf(8, 8, 8, 8), true) == null,
				"first field in winter waits although every seed is stocked"); checks++;
			check(FarmerJob.seasonBlocksSeed(level, site, Items.WHEAT_SEEDS) && FarmerJob.seasonBlocksSeed(level, site, Items.BEETROOT_SEEDS),
				"the crop-step gate blocks sowing in winter"); checks++;
			SeasonCompat.setDevOverride(Season.SUMMER);
			check(!FarmerJob.seasonBlocksSeed(level, site, Items.WHEAT_SEEDS) && FarmerJob.seasonBlocksSeed(level, site, Items.POTATO),
				"the crop-step gate lets wheat through and blocks potatoes in summer"); checks++;
		}
		SeasonCompat.setDevOverrideOff();
		check(FarmerJob.seasonalSeed(level, site, stockOf(20, 20, 20, 20), false) == Items.WHEAT_SEEDS
			&& FarmerJob.seasonalSeed(level, site, stockOf(8, 0, 0, 0), true) == Items.WHEAT_SEEDS
			&& FarmerJob.seasonalSeed(level, site, stockOf(0, 8, 0, 0), true) == null
			&& FarmerJob.seasonalSeed(level, site, stockOf(0, 14, 14, 14), false) == null
			&& !FarmerJob.fieldsDormant(level, site) && !FarmerJob.seasonBlocksSeed(level, site, Items.POTATO),
			"seasons off: the pre-season seed rule (wheat only for the first field, 15-seed threshold) is unchanged"); checks++;
		SeasonCompat.clearDevOverride();
		return checks;
	}

	/** A real 3x3 field on farmland is planted per simulated season with the crop the farmer chooses; winter plants nothing. */
	private static int fieldFixture(ServerLevel level, BlockPos site) {
		int checks = 0;
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		BlockPos center = site.offset(20, 0, 20);
		level.getChunk(center);
		try {
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				BlockPos cell = center.offset(dx, 0, dz);
				for (BlockPos p : new BlockPos[] { cell.below(2), cell.below(), cell, cell.above() }) before.putIfAbsent(p, level.getBlockState(p));
				level.setBlock(cell.below(2), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(cell.below(), Blocks.FARMLAND.defaultBlockState(), 3);
				level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
			}
			List<Container> stock = stockOf(20, 20, 20, 20);
			for (Season season : Season.values()) {
				SeasonCompat.setDevOverride(season);
				Item seed = FarmerJob.seasonalSeed(level, center, stock, false);
				int planted = 0;
				boolean allFit = true;
				for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
					BlockPos cell = center.offset(dx, 0, dz);
					level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
					if (seed == null || FarmerJob.seasonBlocksSeed(level, cell, seed)) continue;
					Block crop = CROPS[java.util.Arrays.asList(SEEDS).indexOf(seed)];
					level.setBlock(cell, crop.defaultBlockState(), 3);
					planted++;
					allFit &= SeasonCompat.fertileIn(season, level.getBlockState(cell).getBlock(), false) || !SeasonCompat.modLoaded();
				}
				boolean want = !SeasonCompat.modLoaded() || season != Season.WINTER;
				check(want ? planted == 9 && allFit : planted == 0,
					"field fixture in " + season + ": " + planted + " cells planted with " + seed
						+ (want ? ", every crop in the season tag" : ", winter leaves the farmland unplanted")); checks++;
			}
		} finally {
			SeasonCompat.clearDevOverride();
			before.forEach((pos, state) -> level.setBlock(pos, state, 3));
		}
		return checks;
	}

	private static List<Container> stockOf(int wheat, int carrot, int potato, int beetroot) {
		SimpleContainer box = new SimpleContainer(8);
		int[] counts = { wheat, carrot, potato, beetroot };
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] > 0) box.setItem(i, new ItemStack(SEEDS[i], counts[i]));
		}
		return List.of(box);
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("SEASON CHECK FAILED: " + message);
	}
}
