package de.villagerplus.build;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Stable residential and profession-specific building forms built from the detected village palette. */
public final class HouseDesignCatalog {
	/** House-only marker in bit 0; callers must give saved HouseTemplate source plans precedence. */
	private static final int CATALOG_MARKER = 1;
	private static final long DESIGN_SEED_MASK = (1L << 30) - 1L;
	private static final long CURRENT_DESIGN_SEED_MASK = (1L << 26) - 1L;
	private static final int CURRENT_TIER_SHIFT = 26;
	private static final int MULTI_STOREY_MARKER = 1 << 2;
	private static final long DECORATION_SALT = 0x6A09E667F3BCC909L;
	private static final long ENTRANCE_SALT = 0x3C6EF372FE94F82BL;
	// Persisted Deco flags owned by Architect; preserve their values when a profile is reconstructed.
	private static final int JOB_HUT_ROLE_MARKER = 1 << 3;
	private static final int JOB_HUT_MULTI_STOREY_MARKER = 1 << 2;

	/** The presets vary massing, entry position, roofline and annex shape while paid surfaces use the local village palette. */
	public enum Preset {
		COMPACT_COTTAGE("compact_cottage", "Kompaktes Fachwerkhäuschen", Architect.SizeTier.COMPACT,
			2, 2, 3, true, 1, true, true, true, true, false, 2, false, 1, 1, 0),
		COMPACT_PORCH("compact_porch", "Häuschen mit überdachtem Eingang", Architect.SizeTier.COMPACT,
			2, 2, 4, false, 1, true, true, true, true, false, 2, true, 1, 1, 0),
		COMPACT_LEAN_TO("compact_lean_to", "Häuschen mit seitlichem Anbau", Architect.SizeTier.COMPACT,
			2, 2, 3, true, 1, true, true, true, true, false, 2, false, 1, 1, -1),
		COMPACT_GARDEN_HOME("compact_garden_home", "Schmales Gartenhaus", Architect.SizeTier.COMPACT,
			2, 3, 3, false, 0, true, true, false, true, true, 2, false, 1, 1, 0),
		// Five wall courses give the street facade room for its paired paid upper windows.
		GABLE_PORCH("gable_porch", "Giebelhaus mit Veranda", Architect.SizeTier.STANDARD,
			3, 2, 5, true, 1, true, true, true, true, false, 2, true, 1, 1, 0),
		LONG_GABLE("long_gable", "Längliches Giebelhaus", Architect.SizeTier.STANDARD,
			4, 2, 5, true, 1, true, true, false, true, false, 3, true, 2, -1, 0),
		STANDARD_ANNEX("standard_annex", "Haupthaus mit Werkstattflügel", Architect.SizeTier.STANDARD,
			3, 3, 5, false, 1, true, true, true, true, false, 2, true, 1, 1, 1),
		STANDARD_COURTYARD("standard_courtyard", "Breites Haus mit geschütztem Eingang", Architect.SizeTier.STANDARD,
			3, 3, 5, true, 1, true, true, true, true, true, 2, true, 2, 1, 0),
		TWO_STOREY_GABLE("two_storey_gable", "Zweigeschossiges Giebelhaus", Architect.SizeTier.MULTI_STOREY,
			3, 3, 6, true, 1, true, true, true, true, true, 2, true, 1, 1, 0),
		TALL_TOWNHOUSE("tall_townhouse", "Großes zweigeschossiges Stadthaus", Architect.SizeTier.MULTI_STOREY,
			4, 3, 6, true, 2, true, true, true, true, true, 2, true, 2, -1, 0),
		MULTI_STOREY_ANNEX("multi_storey_annex", "Mehrstöckiges Haus mit niedrigem Seitenflügel", Architect.SizeTier.MULTI_STOREY,
			3, 3, 6, false, 1, true, true, true, true, false, 2, true, 1, 1, 1),
		MULTI_STOREY_GARDEN_HOUSE("multi_storey_garden_house", "Mehrstöckiges Haus mit tiefem Vorgiebel", Architect.SizeTier.MULTI_STOREY,
			4, 3, 6, false, 1, true, true, false, true, true, 3, false, 2, 1, 0);

		private final String id;
		private final String displayName;
		private final Architect.SizeTier tier;
		private final int halfWidth;
		private final int halfDepth;
		private final int wallHeight;
		private final boolean ridgeAlongPerp;
		private final int overhang;
		private final boolean base;
		private final boolean frameBeams;
		private final boolean halfTimber;
		private final boolean porch;
		private final boolean chimney;
		private final int windowSpacing;
		private final boolean twoToneUpperWall;
		private final int beds;
		private final int doorOffset;
		private final int wing;

		Preset(String id, String displayName, Architect.SizeTier tier, int halfWidth, int halfDepth, int wallHeight,
			boolean ridgeAlongPerp, int overhang, boolean base, boolean frameBeams, boolean halfTimber,
			boolean porch, boolean chimney, int windowSpacing, boolean twoToneUpperWall, int beds, int doorOffset, int wing) {
			this.id = id;
			this.displayName = displayName;
			this.tier = tier;
			this.halfWidth = halfWidth;
			this.halfDepth = halfDepth;
			this.wallHeight = wallHeight;
			this.ridgeAlongPerp = ridgeAlongPerp;
			this.overhang = overhang;
			this.base = base;
			this.frameBeams = frameBeams;
			this.halfTimber = halfTimber;
			this.porch = porch;
			this.chimney = chimney;
			this.windowSpacing = windowSpacing;
			this.twoToneUpperWall = twoToneUpperWall;
			this.beds = beds;
			this.doorOffset = doorOffset;
			this.wing = wing;
		}

		public String id() { return this.id; }
		public String displayName() { return this.displayName; }
		public Architect.SizeTier tier() { return this.tier; }

		public Architect.Design design(long seed) {
			long decorationRoll = mix(seed ^ DECORATION_SALT);
			int deco = (int) (decorationRoll & 3L);
			// Architect's legacy fourth accent hard-codes a spruce-log yard pile. New catalog houses
			// must stay visually tied to the detected village palette, including in dry biomes.
			if (deco == 3) deco = 1 + (int) ((decorationRoll >>> 2) & 1L);
			int entryOffset = (mix(seed ^ ENTRANCE_SALT) & 1L) == 0L ? this.doorOffset : -this.doorOffset;
			if (this.tier == Architect.SizeTier.MULTI_STOREY) deco |= MULTI_STOREY_MARKER;
			return new Architect.Design(Architect.Kind.HOUSE, this.halfWidth, this.halfDepth, this.wallHeight,
				this.ridgeAlongPerp, this.overhang, this.base, this.frameBeams, this.halfTimber, this.porch,
				this.chimney, this.windowSpacing, entryOffset, this.beds, this.twoToneUpperWall, this.wing, deco);
		}
	}

	/** Fixed, profession-readable architecture profiles for new public work buildings. */
	public enum JobHutProfile {
		SCHOOLHOUSE("schoolhouse", "Schulhaus", Architect.Kind.SCHOOL),
		FREIGHT_SHED("freight_shed", "Frachtlager", Architect.Kind.STORAGE),
		GRAIN_BARN("grain_barn", "Kornscheune", Architect.Kind.GRANARY),
		WORKSHOP("workshop", "Werkstatt", Architect.Kind.TOOLSHED),
		COOKHOUSE("cookhouse", "Kochhaus", Architect.Kind.KITCHEN),
		RAIL_DEPOT("rail_depot", "Güterstation", Architect.Kind.STATION);

		private final String id;
		private final String displayName;
		private final Architect.Kind kind;

		JobHutProfile(String id, String displayName, Architect.Kind kind) {
			this.id = id;
			this.displayName = displayName;
			this.kind = kind;
		}

		public String id() { return this.id; }
		public String displayName() { return this.displayName; }
		public Architect.Kind kind() { return this.kind; }

		private Architect.Design design(long seed, Architect.Design base) {
			int accent = base.deco() & 3;
			if (accent == 3) accent = 1 + (int) (mix(seed ^ DECORATION_SALT) & 1L);
			int deco = accent | JOB_HUT_ROLE_MARKER;
			Architect.SizeTier tier = Architect.sizeTier(seed);
			return switch (this) {
				case SCHOOLHOUSE -> new Architect.Design(this.kind, 3, 3, 4, false, 1, true, true,
					true, true, false, 2, 0, 0, true, 0, deco & ~JOB_HUT_MULTI_STOREY_MARKER);
				case FREIGHT_SHED -> {
					int halfWidth = tier == Architect.SizeTier.COMPACT ? 3 : 4;
					int halfDepth = tier == Architect.SizeTier.MULTI_STOREY ? 3 : 2;
					int wallHeight = tier == Architect.SizeTier.MULTI_STOREY ? 6 : 3;
					int storey = tier == Architect.SizeTier.MULTI_STOREY
						? deco | JOB_HUT_MULTI_STOREY_MARKER : deco & ~JOB_HUT_MULTI_STOREY_MARKER;
					yield new Architect.Design(this.kind, halfWidth, halfDepth, wallHeight, true, 1, true, true,
						true, false, false, 2, 0, 0, tier == Architect.SizeTier.MULTI_STOREY, 0, storey);
				}
				// The low front gable puts the paid hay crest directly above the entrance without a second storey.
				case GRAIN_BARN -> new Architect.Design(this.kind, 4, 3, 3, false, 1, base.useBase(), true,
					false, false, false, 3, 0, 0, true, 0,
					deco & ~JOB_HUT_MULTI_STOREY_MARKER);
				// A low eaves-front workshop keeps the tool shop distinct from the school and barn gables.
				case WORKSHOP -> new Architect.Design(this.kind, 3, 2, 3, true, 1, base.useBase(), true,
					false, false, false, 2, 0, 0, true, 0,
					deco & ~JOB_HUT_MULTI_STOREY_MARKER);
				case COOKHOUSE -> new Architect.Design(this.kind, 4, 3, 4, true, 1, base.useBase(), true,
					true, true, true, 2, base.doorOffset(), 0, true, 0,
					deco & ~JOB_HUT_MULTI_STOREY_MARKER);
				case RAIL_DEPOT -> railDepotDesign(base, deco, tier);
			};
		}

		private Architect.Design railDepotDesign(Architect.Design base, int deco, Architect.SizeTier tier) {
			boolean compact = tier == Architect.SizeTier.COMPACT;
			boolean multiStorey = tier == Architect.SizeTier.MULTI_STOREY;
			int width = compact ? 3 : 4;
			int depth = multiStorey ? 3 : 2;
			int wallHeight = multiStorey ? 6 : compact ? 3 : 4;
			int storey = multiStorey ? deco | JOB_HUT_MULTI_STOREY_MARKER : deco & ~JOB_HUT_MULTI_STOREY_MARKER;
			// A low freight platform grows into a covered passenger hall and then a signal loft.
			// All extra space is included by Architect.Design.half()/headroom() before site checks.
			return new Architect.Design(this.kind, width, depth, wallHeight, true, 1, base.useBase(), true,
				false, true, false, 2, 0, 0, true, 0, storey);
		}
	}

	private static final List<Preset> PRESETS = List.copyOf(Arrays.asList(Preset.values()));
	private static final List<JobHutProfile> JOB_HUT_PROFILES = List.copyOf(Arrays.asList(JobHutProfile.values()));

	private HouseDesignCatalog() { }

	public static List<Preset> presets() { return PRESETS; }

	public static List<JobHutProfile> jobHutProfiles() { return JOB_HUT_PROFILES; }

	public static JobHutProfile jobHutProfile(Architect.Kind kind) {
		if (kind == null) return null;
		return JOB_HUT_PROFILES.stream().filter(profile -> profile.kind() == kind).findFirst().orElse(null);
	}

	/** Builds the profile selected by the live saved-plan route; the caller supplies the unprofiled seed design. */
	public static Architect.Design jobHutDesign(Architect.Kind kind, long seed, Architect.Design base) {
		JobHutProfile profile = jobHutProfile(kind);
		if (profile == null || base == null || base.kind() != kind) return base;
		return profile.design(seed, base);
	}

	public static List<Preset> presets(Architect.SizeTier tier) {
		Architect.SizeTier normalized = normalizeTier(tier);
		return PRESETS.stream().filter(preset -> preset.tier() == normalized).toList();
	}

	/** Selects the same named form for the same tier and seed, including after a save/restart. */
	public static Preset presetFor(Architect.SizeTier tier, long seed) {
		List<Preset> candidates = presets(tier);
		long mixed = mix(seed ^ ((long) normalizeTier(tier).ordinal() << 48));
		return candidates.get((int) Long.remainderUnsigned(mixed, candidates.size()));
	}

	public static Architect.Design design(Architect.SizeTier tier, long seed) {
		long stableSeed = seed & CURRENT_DESIGN_SEED_MASK;
		return presetFor(normalizeTier(tier), stableSeed).design(stableSeed);
	}

	/** Produces a persistent plan variant for a newly designed house without changing old unmarked plans. */
	public static int newPlanVariant(Architect.SizeTier tier, long seed) {
		long encodedSeed = (Integer.toUnsignedLong(normalizeTier(tier).ordinal()) << CURRENT_TIER_SHIFT)
			| (seed & CURRENT_DESIGN_SEED_MASK);
		return (int) (encodedSeed << 2) | CATALOG_MARKER;
	}

	/** Adds the catalog version marker to a generated house plan variant. */
	public static int markPlanVariant(int unmarkedVariant) {
		return unmarkedVariant | CATALOG_MARKER;
	}

	public static boolean usesCatalog(int planVariant) {
		return usesCatalog(planVariant, false);
	}

	/** Bit 0 remains the mirror flag whenever this saved plan still has a legacy HouseTemplate source. */
	public static boolean usesCatalog(int planVariant, boolean hasLegacySource) {
		return !hasLegacySource && (planVariant & CATALOG_MARKER) != 0 && (planVariant >>> 2) != 0;
	}

	/** Reconstructs catalog designs while preserving exact geometry for plans saved before catalog rollout. */
	public static Architect.Design designForPlanVariant(int planVariant) {
		return designForPlanVariant(planVariant, false);
	}

	/** Reconstructs a saved project without reinterpreting a legacy source plan's mirror bit as a catalog marker. */
	public static Architect.Design designForPlanVariant(int planVariant, boolean hasLegacySource) {
		if (!usesCatalog(planVariant, hasLegacySource)) return Architect.designForPlan(Architect.Kind.HOUSE, planVariant);
		long seed = Integer.toUnsignedLong(planVariant) >>> 2;
		if ((seed >>> 28) == 0L) {
			Architect.SizeTier tier = Architect.SizeTier.values()[(int) ((seed >>> CURRENT_TIER_SHIFT) & 3L)];
			return design(tier, seed & CURRENT_DESIGN_SEED_MASK);
		}
		return legacyCatalogDesign(Architect.sizeTier(seed), seed);
	}

	/** Bounds the full generated footprint, including the free path and optional outer porch lamp. */
	public static int siteHalf(Architect.Design design) {
		int entranceReach = design.hd() + (design.porch() ? 4 : 3);
		int roofReach = Math.max(design.hw(), design.hd()) + design.overhang();
		int chimneyReach = design.chimney() ? design.hw() + 1 : 0;
		int wingReach = design.wing() == 0 ? 0 : design.hw() + Architect.WING_W + 1;
		int yardReach = design.hw() + 3;
		return Math.max(Math.max(Math.max(design.half(), roofReach), chimneyReach),
			Math.max(Math.max(wingReach, yardReach), entranceReach));
	}

	/** Keeps clearance tall enough for the actual gable/chimney steps; Architect.Design.headroom is a legacy estimate. */
	public static int siteHeadroom(Architect.Design design) {
		int slopeHalf = design.ridgeAlongPerp() ? design.hd() : design.hw();
		int roofTop = design.wallH() + slopeHalf + design.overhang();
		if (design.chimney()) roofTop = Math.max(roofTop, design.wallH() + slopeHalf + design.overhang() + 1);
		if (design.porch()) roofTop = Math.max(roofTop, 2);
		return Math.max(design.headroom(), roofTop + 1);
	}

	public static List<Step> build(Architect.SizeTier tier, long seed, Palette palette, BlockPos center, Direction doorDirection) {
		return Architect.build(design(tier, seed), palette, center, doorDirection);
	}

	public static List<Step> buildForPlanVariant(int planVariant, Palette palette, BlockPos center, Direction doorDirection) {
		return Architect.build(designForPlanVariant(planVariant), palette, center, doorDirection);
	}

	private static long seedForTier(Architect.SizeTier tier, long seed) {
		return Architect.withSizeTier(seed & DESIGN_SEED_MASK, normalizeTier(tier));
	}

	private static Architect.SizeTier normalizeTier(Architect.SizeTier tier) {
		if (tier == null || tier == Architect.SizeTier.LEGACY) return Architect.SizeTier.STANDARD;
		return tier;
	}

	/** Reconstructs catalog-1 variants exactly; their tier bits are nonzero, unlike the current encoding. */
	private static Architect.Design legacyCatalogDesign(Architect.SizeTier tier, long seed) {
		Architect.SizeTier normalized = normalizeTier(tier);
		long stableSeed = seedForTier(normalized, seed);
		long mixed = mix(stableSeed ^ ((long) normalized.ordinal() << 48));
		int choice = (int) Long.remainderUnsigned(mixed, 2L);
		int deco = (int) (mix(stableSeed ^ DECORATION_SALT) & 3L);
		if (normalized == Architect.SizeTier.MULTI_STOREY) deco |= MULTI_STOREY_MARKER;

		return switch (normalized) {
			case COMPACT -> choice == 0
				? new Architect.Design(Architect.Kind.HOUSE, 2, 2, 3, true, 0, true, true, true, false, false, 2, 0, 1, false, 0, deco)
				: new Architect.Design(Architect.Kind.HOUSE, 2, 2, 3, false, 0, true, true, false, true, false, 2, 0, 1, false, 0, deco);
			case STANDARD -> choice == 0
				? new Architect.Design(Architect.Kind.HOUSE, 3, 2, 4, true, 0, true, true, true, true, false, 2, 0, 1, true, 0, deco)
				: new Architect.Design(Architect.Kind.HOUSE, 4, 2, 4, true, 0, true, true, false, false, false, 3, 0, 2, true, 0, deco);
			case MULTI_STOREY -> choice == 0
				? new Architect.Design(Architect.Kind.HOUSE, 3, 3, 6, true, 1, true, true, true, true, true, 2, 0, 1, true, 0, deco)
				: new Architect.Design(Architect.Kind.HOUSE, 4, 3, 6, true, 1, true, true, false, true, true, 3, 0, 2, true, 0, deco);
			case LEGACY -> throw new IllegalStateException("Legacy catalog plans must use a concrete size tier");
		};
	}

	private static long mix(long value) {
		long z = value + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
