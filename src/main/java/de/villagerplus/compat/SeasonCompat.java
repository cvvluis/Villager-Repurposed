package de.villagerplus.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin, optional adapter for Serene Seasons ({@value #MOD_ID}).
 *
 * <p>There is no compile- or load-time dependency: the mod is reached only through {@link MethodHandle}s that are
 * resolved lazily after {@code FabricLoader.isModLoaded}. Without the mod (or when its API changed so that a handle cannot
 * be resolved, or an invocation throws) every query answers "no restriction", so callers keep their vanilla behaviour.
 * The crop tags {@code sereneseasons:<season>_crops} / {@code year_round_crops} are read from the real block registry and
 * need no mod classes at all.
 *
 * <p>When seasons are active, {@link #canPlant} asks the mod's own fertility rule ({@code ModFertility.isCropFertile}) if it
 * can be resolved: that rule also covers the seasonal-crops config switch, cold and tropical biomes, underground fields and
 * blacklisted dimensions. If only the public API is resolvable, the season tags plus the tropical flag decide.
 */
public final class SeasonCompat {
	private SeasonCompat() { }

	public static final String MOD_ID = "sereneseasons";

	public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

	private static final Logger LOG = LoggerFactory.getLogger("villagerplus");

	private static final TagKey<Block> SPRING = tag("spring_crops");
	private static final TagKey<Block> SUMMER = tag("summer_crops");
	private static final TagKey<Block> AUTUMN = tag("autumn_crops");
	private static final TagKey<Block> WINTER = tag("winter_crops");
	private static final TagKey<Block> YEAR_ROUND = tag("year_round_crops");

	private static TagKey<Block> tag(String path) {
		return TagKey.create(Registries.BLOCK, Identifier.parse(MOD_ID + ":" + path));
	}

	/** Dev/check hook: {@code season == null} means "seasons switched off". Absent = the real mod decides. */
	private record Override(Season season) { }

	private static volatile Override override;

	/** Handles into the mod; {@code null} until resolved, replaced by {@link #FAILED} when resolution or a call failed. */
	private record Handles(MethodHandle hasSeasons, MethodHandle season, MethodHandle tropical, MethodHandle cropFertile) { }

	private static final Handles FAILED = new Handles(null, null, null, null);
	private static volatile Handles handles;
	private static volatile Boolean modLoaded;

	/** Whether the mod is loaded in this runtime. */
	public static boolean modLoaded() {
		Boolean cached = modLoaded;
		if (cached == null) {
			cached = FabricLoader.getInstance().isModLoaded(MOD_ID);
			modLoaded = cached;
		}
		return cached;
	}

	/** Seasons are in effect for this level: the mod is usable and {@code SeasonHelper.hasSeasons(level)} is true (or a check hook says so). */
	public static boolean active(Level level) {
		Override o = override;
		if (o != null) return o.season != null;
		Handles h = resolved();
		if (h == null) return false;
		try {
			return (boolean) h.hasSeasons.invoke(level);
		} catch (Throwable t) {
			fail("hasSeasons", t);
			return false;
		}
	}

	/** Current season of the level, or {@code null} when seasons are not active. */
	public static Season currentSeason(Level level) {
		Override o = override;
		if (o != null) return o.season;
		Handles h = resolved();
		if (h == null) return null;
		try {
			if (!(boolean) h.hasSeasons.invoke(level)) return null;
			Object season = h.season.invoke(level);
			return season == null ? null : Season.valueOf(((Enum<?>) season).name());
		} catch (Throwable t) {
			fail("getSeasonState", t);
			return null;
		}
	}

	/** Whether the block belongs to any Serene Seasons crop tag. Blocks the mod does not list are never restricted by it. */
	public static boolean listed(Block crop) {
		BlockState state = crop.defaultBlockState();
		return state.is(SPRING) || state.is(SUMMER) || state.is(AUTUMN) || state.is(WINTER) || state.is(YEAR_ROUND);
	}

	/**
	 * Pure tag rule: a listed crop is fertile in {@code season} when it is in that season's tag or the year-round tag. Tropical
	 * biomes only have their dry/wet cycle, where the mod treats exactly the summer crops as fertile.
	 */
	public static boolean fertileIn(Season season, Block crop, boolean tropical) {
		if (!listed(crop)) return true;
		BlockState state = crop.defaultBlockState();
		if (tropical) return state.is(SUMMER);
		if (state.is(YEAR_ROUND)) return true;
		return switch (season) {
			case SPRING -> state.is(SPRING);
			case SUMMER -> state.is(SUMMER);
			case AUTUMN -> state.is(AUTUMN);
			case WINTER -> state.is(WINTER);
		};
	}

	/**
	 * Whether a villager may plant {@code crop} at {@code pos} now. {@code true} also means "no restriction": mod absent,
	 * seasons off or inactive in this dimension, an unresolvable API, or a crop the mod does not list.
	 */
	public static boolean canPlant(Level level, BlockPos pos, Block crop) {
		Override o = override;
		if (o != null) return o.season == null || fertileIn(o.season, crop, false);
		Handles h = resolved();
		if (h == null) return true;
		try {
			if (!(boolean) h.hasSeasons.invoke(level)) return true;
			if (h.cropFertile != null) {
				return (boolean) h.cropFertile.invoke(BuiltInRegistries.BLOCK.getKey(crop).toString(), level, pos);
			}
			Object season = h.season.invoke(level);
			if (season == null) return true;
			boolean tropical = h.tropical != null && (boolean) h.tropical.invoke(level, pos);
			return fertileIn(Season.valueOf(((Enum<?>) season).name()), crop, tropical);
		} catch (Throwable t) {
			fail("fertility", t);
			return true;
		}
	}

	/** Whether the mod applies its tropical (dry/wet) cycle at {@code pos}; false without the mod, seasons or a resolvable API. */
	public static boolean tropical(Level level, BlockPos pos) {
		if (override != null) return false;
		Handles h = resolved();
		if (h == null || h.tropical == null) return false;
		try {
			return (boolean) h.tropical.invoke(level, pos);
		} catch (Throwable t) {
			fail("usesTropicalSeasons", t);
			return false;
		}
	}

	/** Short text for logs and checks: the season name, {@code off} or {@code absent}. */
	public static String describe(Level level) {
		if (override == null && !modLoaded()) return "absent";
		Season season = currentSeason(level);
		return season == null ? "off" : season.name();
	}

	// ---- dev / check hook -------------------------------------------------------------------------------------------

	/** Check hook: pretend this season is active (tag rule only, no biome logic). Always undo with {@link #clearDevOverride()}. */
	public static void setDevOverride(Season season) {
		override = new Override(season);
	}

	/** Check hook: pretend seasons are not active, i.e. "no restriction". */
	public static void setDevOverrideOff() {
		override = new Override(null);
	}

	public static void clearDevOverride() {
		override = null;
	}

	/** Whether a check hook currently replaces the real mod. */
	public static boolean hasDevOverride() {
		return override != null;
	}

	/** Whether the real adapter (not the check hook) resolved the mod's API. Only meaningful with the mod present. */
	public static boolean apiResolved() {
		Handles h = resolved();
		return h != null && h.hasSeasons != null && h.season != null;
	}

	/** Whether the mod's own fertility rule is wired (otherwise the tag rule decides). */
	public static boolean fertilityRuleResolved() {
		Handles h = resolved();
		return h != null && h.cropFertile != null;
	}

	// ---- lazy resolution ---------------------------------------------------------------------------------------------

	private static Handles resolved() {
		if (!modLoaded()) return null;
		Handles h = handles;
		if (h == null) {
			synchronized (SeasonCompat.class) {
				h = handles;
				if (h == null) {
					h = resolve();
					handles = h;
				}
			}
		}
		return h == FAILED ? null : h;
	}

	private static Handles resolve() {
		try {
			ClassLoader loader = SeasonCompat.class.getClassLoader();
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper", false, loader);
			Class<?> stateType = Class.forName("sereneseasons.api.season.ISeasonState", false, loader);
			MethodHandle hasSeasons = lookup.findStatic(helper, "hasSeasons", MethodType.methodType(boolean.class, Level.class));
			MethodHandle getState = lookup.findStatic(helper, "getSeasonState", MethodType.methodType(stateType, Level.class));
			Class<?> seasonType = Class.forName("sereneseasons.api.season.Season", false, loader);
			MethodHandle getSeason = lookup.findVirtual(stateType, "getSeason", MethodType.methodType(seasonType));
			MethodHandle season = MethodHandles.filterReturnValue(getState, getSeason).asType(MethodType.methodType(Object.class, Level.class));
			MethodHandle tropical = optional(() -> lookup.findStatic(helper, "usesTropicalSeasons",
				MethodType.methodType(boolean.class, Level.class, BlockPos.class)));
			MethodHandle fertile = optional(() -> lookup.findStatic(
				Class.forName("sereneseasons.init.ModFertility", false, loader), "isCropFertile",
				MethodType.methodType(boolean.class, String.class, Level.class, BlockPos.class)));
			LOG.info("Serene Seasons found: season API resolved, tropical={}, mod fertility rule={}", tropical != null, fertile != null);
			return new Handles(hasSeasons, season, tropical, fertile);
		} catch (Throwable t) {
			LOG.warn("Serene Seasons is loaded but its API could not be resolved ({}); farmers ignore seasons", t.toString());
			return FAILED;
		}
	}

	private interface HandleSupplier { MethodHandle get() throws Throwable; }

	private static MethodHandle optional(HandleSupplier supplier) {
		try {
			return supplier.get();
		} catch (Throwable t) {
			return null;
		}
	}

	private static void fail(String what, Throwable t) {
		if (handles != FAILED) LOG.warn("Serene Seasons {} call failed ({}); farmers ignore seasons from now on", what, t.toString());
		handles = FAILED;
	}
}
