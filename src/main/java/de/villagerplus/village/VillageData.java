package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.build.ConstructionSite;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

/** Pro Dorf gespeicherte Daten (haengen als Attachment an der Dorfglocke). */
public class VillageData {
	/** Stollen-Mine: Eingang an der Oberflaeche, Treppe nach unten, dann waagerechter Stollen. */
	public static class Mine {
		public static final Codec<Mine> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("entrance").forGetter(m -> m.entrance),
			Codec.INT.optionalFieldOf("dir", 0).forGetter(m -> m.dir),
			Codec.INT.optionalFieldOf("index", 0).forGetter(m -> m.index),
			Codec.INT.optionalFieldOf("target_y", 0).forGetter(m -> m.targetY),
			Codec.BOOL.optionalFieldOf("done", false).forGetter(m -> m.done)
		).apply(i, Mine::new));

		public final BlockPos entrance;
		public final int dir;
		public int index;
		public final int targetY;
		public boolean done;

		public Mine(BlockPos entrance, int dir, int index, int targetY, boolean done) {
			this.entrance = entrance;
			this.dir = dir;
			this.index = index;
			this.targetY = targetY;
			this.done = done;
		}
	}

	/** Persisted footprint reservation shared by all active projects in one village. */
	public record PlanBounds(int minX, int maxX, int minZ, int maxZ) {
		public static final Codec<PlanBounds> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("min_x").forGetter(PlanBounds::minX),
			Codec.INT.fieldOf("max_x").forGetter(PlanBounds::maxX),
			Codec.INT.fieldOf("min_z").forGetter(PlanBounds::minZ),
			Codec.INT.fieldOf("max_z").forGetter(PlanBounds::maxZ)
		).apply(i, PlanBounds::new));

		public PlanBounds {
			if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Invalid construction reservation bounds");
		}

		public boolean overlaps(PlanBounds other) {
			return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
		}
	}

	/** A single resumable project. The origin is its stable identity and its construction attachment key. */
	public record ProjectHaul(List<ItemStack> siteStock, List<String> cargoManifest) {
		public static final Codec<ProjectHaul> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.CODEC.listOf().optionalFieldOf("site_stock", List.of()).forGetter(ProjectHaul::siteStock),
			Codec.STRING.listOf().optionalFieldOf("cargo_manifest", List.of()).forGetter(ProjectHaul::cargoManifest)
		).apply(i, ProjectHaul::new));

		public ProjectHaul {
			siteStock = siteStock == null ? List.of() : siteStock.stream().filter(stack -> stack != null && !stack.isEmpty())
				.map(ItemStack::copy).toList();
			cargoManifest = cargoManifest == null ? List.of() : List.copyOf(cargoManifest);
		}

		public static ProjectHaul empty() { return new ProjectHaul(List.of(), List.of()); }
	}

	public record BuildProject(String type, BlockPos origin, int dir, int step, int variant, Optional<BlockPos> source,
		long progress, Optional<UUID> owner, long ownerUnavailableSince, List<String> wanted, ProjectHaul haul,
		Optional<String> materialWait, Optional<ConstructionSite> construction, Optional<PlanBounds> bounds,
		boolean repair, boolean cleanupOnly) {
		public static final Codec<BuildProject> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.optionalFieldOf("type", "").forGetter(BuildProject::type),
			BlockPos.CODEC.fieldOf("origin").forGetter(BuildProject::origin),
			Codec.INT.optionalFieldOf("dir", 0).forGetter(BuildProject::dir),
			Codec.INT.optionalFieldOf("step", 0).forGetter(BuildProject::step),
			Codec.INT.optionalFieldOf("variant", 0).forGetter(BuildProject::variant),
			BlockPos.CODEC.optionalFieldOf("source").forGetter(BuildProject::source),
			Codec.LONG.optionalFieldOf("progress", 0L).forGetter(BuildProject::progress),
			UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(BuildProject::owner),
			Codec.LONG.optionalFieldOf("owner_unavailable_since", 0L).forGetter(BuildProject::ownerUnavailableSince),
			Codec.STRING.listOf().optionalFieldOf("wanted", List.of()).forGetter(BuildProject::wanted),
			ProjectHaul.CODEC.optionalFieldOf("haul", ProjectHaul.empty()).forGetter(BuildProject::haul),
			Codec.STRING.optionalFieldOf("material_wait").forGetter(BuildProject::materialWait),
			ConstructionSite.CODEC.optionalFieldOf("construction").forGetter(BuildProject::construction),
			PlanBounds.CODEC.optionalFieldOf("bounds").forGetter(BuildProject::bounds),
			Codec.BOOL.optionalFieldOf("repair", false).forGetter(BuildProject::repair),
			Codec.BOOL.optionalFieldOf("cleanup_only", false).forGetter(BuildProject::cleanupOnly)
		).apply(i, BuildProject::new));

		public BuildProject {
			type = type == null ? "" : type;
			if (origin == null) throw new IllegalArgumentException("A build project needs a stable origin");
			origin = origin.immutable();
			step = Math.max(0, step);
			source = source == null ? Optional.empty() : source;
			owner = owner == null ? Optional.empty() : owner;
			wanted = wanted == null ? List.of() : List.copyOf(wanted);
			haul = haul == null ? ProjectHaul.empty() : haul;
			materialWait = materialWait == null ? Optional.empty() : materialWait;
			construction = construction == null ? Optional.empty() : construction;
			bounds = bounds == null ? Optional.empty() : bounds;
			ownerUnavailableSince = Math.max(0L, ownerUnavailableSince);
		}

		public List<ItemStack> siteStock() { return haul.siteStock(); }
		public List<String> cargoManifest() { return haul.cargoManifest(); }

		public boolean active() {
			return !cleanupOnly && !type.isEmpty();
		}

		public long id() {
			return origin.asLong();
		}
	}

	/** Market hut materials stay independent from construction-project requests. */
	public record MarketDemand(Optional<UUID> owner, String item, int amount) {
		public static final Codec<MarketDemand> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(MarketDemand::owner),
			Codec.STRING.fieldOf("item").forGetter(MarketDemand::item),
			Codec.INT.optionalFieldOf("amount", 0).forGetter(MarketDemand::amount)
		).apply(i, MarketDemand::new));

		public MarketDemand {
			owner = owner == null ? Optional.empty() : owner;
			item = item == null ? "" : item;
			amount = Math.max(0, amount);
		}
	}

	/** Weitere Dorfdaten: Tiergehege, Abklingzeiten fuer Felder und Handelskarawanen. */
	public static class Extra {
		private static final MapCodec<Extra> BASE_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BlockPos.CODEC.optionalFieldOf("pen").forGetter(e -> e.pen),
			Codec.LONG.optionalFieldOf("next_caravan", 0L).forGetter(e -> e.nextCaravan),
			Codec.LONG.optionalFieldOf("next_farm", 0L).forGetter(e -> e.nextFarm),
			Codec.LONG.optionalFieldOf("next_care", 0L).forGetter(e -> e.nextCare),
			Mine.CODEC.listOf().optionalFieldOf("old_mines", List.of()).forGetter(e -> e.oldMines),
			Codec.INT.optionalFieldOf("decor", 0).forGetter(e -> e.decor),
			Codec.LONG.optionalFieldOf("next_guests", 0L).forGetter(e -> e.nextGuests),
			BlockPos.CODEC.optionalFieldOf("board").forGetter(e -> e.board),
			BlockPos.CODEC.optionalFieldOf("lager").forGetter(e -> e.lager),
			BlockPos.CODEC.listOf().optionalFieldOf("lager_more", List.of()).forGetter(e -> e.lagerMore),
			Codec.STRING.optionalFieldOf("exp_active").forGetter(e -> e.expActive),
			Codec.STRING.listOf().optionalFieldOf("exp_log", List.of()).forGetter(e -> e.expLog),
			Codec.INT.listOf().optionalFieldOf("exp_stats", List.of()).forGetter(e -> e.expStats),
			Codec.LONG.optionalFieldOf("exp_next", 0L).forGetter(e -> e.expNext),
			BlockPos.CODEC.optionalFieldOf("school").forGetter(e -> e.school),
			BlockPos.CODEC.listOf().optionalFieldOf("lager_chests", List.of()).forGetter(e -> e.lagerChests)
		).apply(i, Extra::new));
		public static final Codec<Extra> CODEC = BASE_CODEC.dependent(
			BuilderPlan.CODEC.optionalFieldOf("plan_builder"),
			extra -> com.mojang.datafixers.util.Pair.of(extra.planBuilder, BuilderPlan.CODEC.optionalFieldOf("plan_builder")),
			(extra, owner) -> {
				extra.planBuilder = owner;
				return extra;
			}
		).dependent(
			Codec.STRING.optionalFieldOf("plan_material_wait"),
			extra -> com.mojang.datafixers.util.Pair.of(extra.planMaterialWait, Codec.STRING.optionalFieldOf("plan_material_wait")),
			(extra, wait) -> { extra.planMaterialWait = wait; return extra; }
		).dependent(
			CivicSiteSearch.CODEC.optionalFieldOf("civic_site_search"),
			extra -> com.mojang.datafixers.util.Pair.of(extra.civicSiteSearch, CivicSiteSearch.CODEC.optionalFieldOf("civic_site_search")),
			(extra, search) -> { extra.civicSiteSearch = search; return extra; }
		).codec();

		/** Small persisted cursor for resuming a bounded civic building-site scan after reload. */
		public record CivicSiteSearch(String type, List<Integer> variants, long phaseSeed, int variantIndex,
			int nextCandidate, boolean tolerant, long retryAt, boolean terraced) {
			public CivicSiteSearch(String type, List<Integer> variants, long phaseSeed, int variantIndex,
				int nextCandidate, boolean tolerant, long retryAt) {
				this(type, variants, phaseSeed, variantIndex, nextCandidate, tolerant, retryAt, false);
			}

			public static final Codec<CivicSiteSearch> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.optionalFieldOf("type", "").forGetter(CivicSiteSearch::type),
				Codec.INT.listOf().optionalFieldOf("variants", List.of(0)).forGetter(CivicSiteSearch::variants),
				Codec.LONG.optionalFieldOf("phase_seed", 0L).forGetter(CivicSiteSearch::phaseSeed),
				Codec.INT.optionalFieldOf("variant_index", 0).forGetter(CivicSiteSearch::variantIndex),
				Codec.INT.optionalFieldOf("next_candidate", 0).forGetter(CivicSiteSearch::nextCandidate),
				Codec.BOOL.optionalFieldOf("tolerant", false).forGetter(CivicSiteSearch::tolerant),
				Codec.LONG.optionalFieldOf("retry_at", 0L).forGetter(CivicSiteSearch::retryAt),
				Codec.BOOL.optionalFieldOf("terraced", false).forGetter(CivicSiteSearch::terraced)
			).apply(i, CivicSiteSearch::new));

			public CivicSiteSearch {
				type = type == null ? "" : type;
				variants = variants == null || variants.isEmpty() ? List.of(0) : List.copyOf(variants);
				variantIndex = Math.max(0, Math.min(variants.size() - 1, variantIndex));
				nextCandidate = Math.max(0, nextCandidate);
				retryAt = Math.max(0L, retryAt);
			}
		}

		/** Persistent ownership of an active build. Missing owners are replaced only after the grace period. */
		public static class BuilderPlan {
			public static final Codec<BuilderPlan> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(p -> p.owner),
				Codec.LONG.optionalFieldOf("unavailable_since", 0L).forGetter(p -> p.unavailableSince)
			).apply(i, BuilderPlan::new));

			public Optional<UUID> owner;
			public long unavailableSince;

			public BuilderPlan(Optional<UUID> owner, long unavailableSince) {
				this.owner = owner;
				this.unavailableSince = unavailableSince;
			}
		}

		/** Mittelpunkt des Tiergeheges (leer = noch keins). */
		public Optional<BlockPos> pen;
		public long nextCaravan;
		public long nextFarm;
		/** Naechste Fuetterungsrunde im Gehege. */
		public long nextCare;
		/** Fruehere (ausgebeutete) Minen: Eingaenge bleiben bekannt, damit niemand im Stollen stecken bleibt. */
		public final List<Mine> oldMines = new ArrayList<>();
		/** Anzahl der bereits aufgestellten Reichtums-Aufbauten (Stufe des Dorfreichtums). */
		public int decor;
		public long nextGuests;
		/** Position der Dorftafel (Schild in der Dorfmitte). */
		public Optional<BlockPos> board = Optional.empty();
		/** Mittelpunkt des Lagerschuppens (Ueberschuss, wenn die Dorfkiste voll ist). */
		public Optional<BlockPos> lager = Optional.empty();
		/** Weitere Lagerschuppen (das Lager wird erweitert, wenn alles voll ist). */
		public final List<BlockPos> lagerMore = new ArrayList<>();
		/** Laufende Expedition als Text "uuid,uuid;endeZeit;zielIndex" (leer = keine). */
		public Optional<String> expActive = Optional.empty();
		/** Die letzten Expeditionsberichte (neueste zuletzt). */
		public final List<String> expLog = new ArrayList<>();
		/** Statistik: [gestartet, heimgekehrt, gefallen, Beutestuecke]. */
		public final List<Integer> expStats = new ArrayList<>();
		public long expNext;
		/** Mittelpunkt der Dorfschule (dort lernen die Kinder). */
		public Optional<BlockPos> school = Optional.empty();
		/** Alle Kisten der Lagerhaeuser (bei Doppelkisten die linke Haelfte, eine Position je Kiste). */
		public final List<BlockPos> lagerChests = new ArrayList<>();
		/** Owner UUID and unavailable grace clock for the current plan; absent for legacy/unclaimed plans. */
		public Optional<BuilderPlan> planBuilder = Optional.empty();
		/** Diagnostic for a blocked material chain, including the plan identity; absent in older saves. */
		public Optional<String> planMaterialWait = Optional.empty();
		/** Resumable pre-plan civic site search; absent when no civic search is in progress. */
		public Optional<CivicSiteSearch> civicSiteSearch = Optional.empty();

		public Extra() {
			this(Optional.empty(), 0L, 0L, 0L, List.of(), 0, 0L, Optional.empty(), Optional.empty(), List.of(), Optional.empty(), List.of(), List.of(), 0L,
				Optional.empty(), List.of());
		}

		public Extra(Optional<BlockPos> pen, long nextCaravan, long nextFarm, long nextCare, List<Mine> oldMines, int decor, long nextGuests,
			Optional<BlockPos> board, Optional<BlockPos> lager, List<BlockPos> lagerMore, Optional<String> expActive, List<String> expLog,
			List<Integer> expStats, long expNext, Optional<BlockPos> school, List<BlockPos> lagerChests) {
			this.school = school;
			this.lagerChests.addAll(lagerChests);
			this.board = board;
			this.lager = lager;
			this.lagerMore.addAll(lagerMore);
			this.expActive = expActive;
			this.expLog.addAll(expLog);
			this.expStats.addAll(expStats);
			this.expNext = expNext;
			this.decor = decor;
			this.nextGuests = nextGuests;
			this.oldMines.addAll(oldMines);
			this.pen = pen;
			this.nextCaravan = nextCaravan;
			this.nextFarm = nextFarm;
			this.nextCare = nextCare;
		}
	}

	private static final int FLAG_WELL = 1;
	private static final int FLAG_HALL = 2;
	private static final int FLAG_WALL = 4;

	private static final com.mojang.serialization.MapCodec<VillageData> BASE_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BlockPos.CODEC.optionalFieldOf("chest").forGetter(d -> d.chest),
		Codec.STRING.optionalFieldOf("plan_type", "").forGetter(d -> d.planType),
		BlockPos.CODEC.optionalFieldOf("plan_origin").forGetter(d -> d.planOrigin),
		Codec.INT.optionalFieldOf("plan_dir", 0).forGetter(d -> d.planDir),
		Codec.INT.optionalFieldOf("plan_step", 0).forGetter(d -> d.planStep),
		Codec.INT.optionalFieldOf("plan_variant", 0).forGetter(d -> d.planVariant),
		Codec.LONG.optionalFieldOf("next_build", 0L).forGetter(d -> d.nextBuild),
		Codec.INT.optionalFieldOf("flags", 0).forGetter(VillageData::flags),
		Codec.INT.optionalFieldOf("houses", 0).forGetter(d -> d.houses),
		Codec.INT.optionalFieldOf("farms", 0).forGetter(d -> d.farms),
		BlockPos.CODEC.optionalFieldOf("plan_source").forGetter(d -> d.planSource),
		Codec.INT.optionalFieldOf("pop_at_last_house", 0).forGetter(d -> d.popAtLastHouse),
		Codec.LONG.optionalFieldOf("plan_progress", 0L).forGetter(d -> d.planProgress),
		Mine.CODEC.optionalFieldOf("mine").forGetter(d -> d.mine),
		Codec.STRING.listOf().optionalFieldOf("wanted", List.of()).forGetter(d -> d.wanted),
		Extra.CODEC.optionalFieldOf("extra").forGetter(d -> Optional.of(d.extra))
	).apply(i, VillageData::new));

	public static final Codec<VillageData> CODEC = BASE_CODEC
		.dependent(Codec.INT.optionalFieldOf("build_project_version", 0),
			data -> com.mojang.datafixers.util.Pair.of(data.buildProjectVersion, Codec.INT.optionalFieldOf("build_project_version", 0)),
			(data, version) -> { data.buildProjectVersion = Math.max(0, version); return data; })
		.dependent(BuildProject.CODEC.listOf().optionalFieldOf("build_projects", List.of()),
			data -> com.mojang.datafixers.util.Pair.of(List.copyOf(data.buildProjects), BuildProject.CODEC.listOf().optionalFieldOf("build_projects", List.of())),
			(data, projects) -> { data.replaceBuildProjects(projects); return data; })
		.dependent(MarketDemand.CODEC.listOf().optionalFieldOf("market_demands", List.of()),
			data -> com.mojang.datafixers.util.Pair.of(List.copyOf(data.marketDemands), MarketDemand.CODEC.listOf().optionalFieldOf("market_demands", List.of())),
			(data, demands) -> { data.replaceMarketDemands(demands); return data; })
		.codec();

	/** Linke Haelfte der Doppelkiste in der Dorfmitte. */
	public Optional<BlockPos> chest = Optional.empty();
	/** Aktueller Bauauftrag: "house", "farm", "well", "townhall", "wall", "pen" oder leer. */
	public String planType = "";
	public Optional<BlockPos> planOrigin = Optional.empty();
	public int planDir;
	public int planStep;
	public int planVariant;
	public long nextBuild;
	public boolean wellBuilt;
	public boolean townHallBuilt;
	public boolean wallBuilt;
	public int houses;
	public int farms;
	/** Bett eines bestehenden Hauses, das als Vorlage fuer den Bau kopiert wird (leer = Standardhaus im Dorfstil). */
	public Optional<BlockPos> planSource = Optional.empty();
	/** Einwohnerzahl, als das letzte Haus geplant wurde. Ein neues Haus gibt es nur, wenn die Bevoelkerung darueber liegt. */
	public int popAtLastHouse;
	/** Zeitpunkt (Spielzeit) des letzten Baufortschritts - Wachhund gegen haengende Bauauftraege. */
	public long planProgress;
	public Optional<Mine> mine = Optional.empty();
	/** Fehlende Baumaterialien als "item_id#anzahl": sagt Schreinern, Steinmetzen, Bergleuten und Holzfaellern, was gebraucht wird. */
	public final List<String> wanted = new ArrayList<>();
	/** Independent saved cursors; the legacy plan_* fields expose the first active project to existing readers. */
	public final List<BuildProject> buildProjects = new ArrayList<>();
	/** Marketplace construction requests, keyed by shop owner and item. */
	public final List<MarketDemand> marketDemands = new ArrayList<>();
	/** Zero means a pre-project save whose singleton plan and shared wishlist have not been migrated yet. */
	public int buildProjectVersion = 1;
	public Extra extra = new Extra();

	public VillageData() {
	}

	private VillageData(Optional<BlockPos> chest, String planType, Optional<BlockPos> planOrigin, int planDir, int planStep,
		int planVariant, long nextBuild, int flags, int houses, int farms, Optional<BlockPos> planSource, int popAtLastHouse,
		long planProgress, Optional<Mine> mine, List<String> wanted, Optional<Extra> extra) {
		this.chest = chest;
		this.planType = planType;
		this.planOrigin = planOrigin;
		this.planDir = planDir;
		this.planStep = planStep;
		this.planVariant = planVariant;
		this.nextBuild = nextBuild;
		this.wellBuilt = (flags & FLAG_WELL) != 0;
		this.townHallBuilt = (flags & FLAG_HALL) != 0;
		this.wallBuilt = (flags & FLAG_WALL) != 0;
		this.houses = houses;
		this.farms = farms;
		this.planSource = planSource;
		this.popAtLastHouse = popAtLastHouse;
		this.planProgress = planProgress;
		this.mine = mine;
		this.wanted.addAll(wanted);
		this.extra = extra.orElseGet(Extra::new);
	}

	private void replaceBuildProjects(List<BuildProject> saved) {
		buildProjects.clear();
		if (saved != null) buildProjects.addAll(saved.stream().filter(java.util.Objects::nonNull).toList());
		if (!buildProjects.isEmpty()) {
			buildProjectVersion = 1;
			syncPrimaryProjectView();
		}
	}

	private void replaceMarketDemands(List<MarketDemand> saved) {
		marketDemands.clear();
		if (saved != null) marketDemands.addAll(saved.stream().filter(java.util.Objects::nonNull).toList());
		if (buildProjectVersion > 0) refreshWanted();
	}

	private int flags() {
		return (wellBuilt ? FLAG_WELL : 0) | (townHallBuilt ? FLAG_HALL : 0) | (wallBuilt ? FLAG_WALL : 0);
	}

	public boolean hasPlan() {
		return !planType.isEmpty() && planOrigin.isPresent();
	}

	public boolean hasBuildProjects() {
		return buildProjects.stream().anyMatch(BuildProject::active);
	}

	/**
	 * Clear only the legacy single-plan projection before Builder runs a fresh project slot.
	 * The persisted civic site search is deliberately kept: this runs on every worker tick while no
	 * project is active, and wiping the sweep cursor there restarted the search at its innermost
	 * 96 candidates (about 22 blocks around the bell) forever. A search for a different building type
	 * is already discarded by {@code Builder.civicSiteSearch}.
	 */
	public void clearPlanViewForProjectSlot() {
		clearPlanView();
		wanted.clear();
	}

	public int activeBuildProjectCount() {
		return (int) buildProjects.stream().filter(BuildProject::active).count();
	}

	public Optional<BuildProject> buildProject(BlockPos origin) {
		if (origin == null) return Optional.empty();
		long id = origin.asLong();
		return buildProjects.stream().filter(project -> project.id() == id).findFirst();
	}

	public void putBuildProject(BuildProject project) {
		if (project == null) return;
		for (int i = 0; i < buildProjects.size(); i++) {
			if (buildProjects.get(i).id() == project.id()) {
				buildProjects.set(i, project);
				buildProjectVersion = 1;
				return;
			}
		}
		buildProjects.add(project);
		buildProjectVersion = 1;
	}

	public boolean hasBuildProjectType(String type) {
		return type != null && buildProjects.stream().anyMatch(project -> project.active() && project.type().equals(type));
	}

	public void removeBuildProject(BlockPos origin) {
		if (origin == null) return;
		long id = origin.asLong();
		buildProjects.removeIf(project -> project.id() == id);
		refreshWanted();
	}

	/** Keep legacy fields synchronized for status/board readers that have not migrated yet. */
	public void syncPrimaryProjectView() {
		BuildProject primary = buildProjects.stream().filter(BuildProject::active).findFirst().orElse(null);
		if (primary == null) {
			clearPlanView();
			refreshWanted();
			return;
		}
		planType = primary.type();
		planOrigin = Optional.of(primary.origin());
		planDir = primary.dir();
		planStep = primary.step();
		planVariant = primary.variant();
		planSource = primary.source();
		planProgress = primary.progress();
		extra.planBuilder = primary.owner().map(owner -> new Extra.BuilderPlan(Optional.of(owner), primary.ownerUnavailableSince()));
		extra.planMaterialWait = primary.materialWait();
		refreshWanted();
	}

	/** One shared-stock target assembled from every project's demand plus separate marketplace requests. */
	public void refreshWanted() {
		Map<String, Long> totals = new TreeMap<>();
		for (BuildProject project : buildProjects) {
			if (!project.active()) continue;
			for (String entry : project.wanted()) accumulateWanted(totals, entry);
		}
		for (MarketDemand demand : marketDemands) {
			if (!demand.item().isBlank() && demand.amount() > 0) {
				totals.merge(demand.item(), (long) demand.amount(), VillageData::saturatingAdd);
			}
		}
		wanted.clear();
		for (Map.Entry<String, Long> entry : totals.entrySet()) {
			if (wanted.size() >= 256) break;
			wanted.add(entry.getKey() + "#" + Math.min(Integer.MAX_VALUE, Math.max(0L, entry.getValue())));
		}
	}

	public void addMarketDemand(Optional<UUID> owner, String item, int amount) {
		if (item == null || item.isBlank() || amount <= 0) return;
		Optional<UUID> normalizedOwner = owner == null ? Optional.empty() : owner;
		for (int i = 0; i < marketDemands.size(); i++) {
			MarketDemand current = marketDemands.get(i);
			if (current.owner().equals(normalizedOwner) && current.item().equals(item)) {
				if (amount > current.amount()) marketDemands.set(i, new MarketDemand(normalizedOwner, item, amount));
				refreshWanted();
				return;
			}
		}
		marketDemands.add(new MarketDemand(normalizedOwner, item, amount));
		refreshWanted();
	}

	public void removeMarketDemands(UUID owner) {
		if (owner == null) return;
		marketDemands.removeIf(demand -> demand.owner().filter(owner::equals).isPresent());
		refreshWanted();
	}

	private static void accumulateWanted(Map<String, Long> totals, String entry) {
		if (entry == null) return;
		int split = entry.lastIndexOf('#');
		if (split <= 0) return;
		String item = entry.substring(0, split);
		try {
			long amount = Math.max(0L, Long.parseLong(entry.substring(split + 1)));
			if (amount > 0L) totals.merge(item, amount, VillageData::saturatingAdd);
		} catch (NumberFormatException ignored) { }
	}

	private static long saturatingAdd(long left, long right) {
		if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
		return left + right;
	}

	private void clearPlanView() {
		planType = "";
		planOrigin = Optional.empty();
		planSource = Optional.empty();
		planStep = 0;
		planVariant = 0;
		planDir = 0;
		planProgress = 0L;
		extra.planBuilder = Optional.empty();
		extra.planMaterialWait = Optional.empty();
	}

	public void clearPlan() {
		planOrigin.ifPresent(this::removeBuildProject);
		clearPlanView();
		extra.civicSiteSearch = Optional.empty();
		refreshWanted();
	}

	public void claimPlanBuilder(UUID owner) {
		extra.planBuilder = Optional.of(new Extra.BuilderPlan(Optional.of(owner), 0L));
	}

	public Optional<UUID> planBuilder() {
		return extra.planBuilder.flatMap(plan -> plan.owner);
	}

	public long planBuilderUnavailableSince() {
		return extra.planBuilder.map(plan -> plan.unavailableSince).orElse(0L);
	}

	public void markPlanBuilderUnavailable(long since) {
		planBuilder().ifPresent(owner -> extra.planBuilder = Optional.of(new Extra.BuilderPlan(Optional.of(owner), since)));
	}
}
