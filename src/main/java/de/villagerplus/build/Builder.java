package de.villagerplus.build;

import de.villagerplus.Config;
import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Livestock;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Development;
import de.villagerplus.village.Housing;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Wealth;
import de.villagerplus.village.Sites;
import de.villagerplus.village.Roads;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import de.villagerplus.village.Villages;
import de.villagerplus.village.NaturalSitePreparation;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Plant und baut Bauwerke des Dorfes (Haeuser, Felder, Brunnen). Der Bauauftrag liegt am Dorf (Glocke), damit
 * mehrere Handwerker gemeinsam daran weiterbauen und er einen Neustart ueberlebt.
 *
 * <p>Haeuser entstehen nur, wenn die Bevoelkerung waechst: wenn jemand kein Bett hat oder alle Betten belegt sind und
 * es mehr Einwohner gibt als beim letzten Hausbau. Als Bauplan dient eines der naechstgelegenen bestehenden Haeuser
 * (Struktur-Teil bei generierten Doerfern, also auch Overhauled Villages & Co.). Hoehenunterschiede am Bauplatz bleiben
 * auf einen Block begrenzt. Fehlendes Material landet als Wunschliste am Dorf, damit Handwerker, Holzfaeller und Bergleute es beschaffen.
 */
public final class Builder {
	private static final Map<Long, VillageStyle> STYLES = new HashMap<>();
	private static final Map<Long, List<Step>> STEPS = new HashMap<>();
	private static final Map<Long, String> PLAN_DIAGNOSTICS = new HashMap<>();
	private static final Map<Long, Optional<HouseTemplate>> TEMPLATES = new HashMap<>();
	private static final long STUCK_TICKS = 36000L;
	private static final int FARM_PLAN_RETRY_TICKS = 40;
	/** The first pen follows the starter field at the same minimum village size as the first farm. */
	public static final int MIN_INITIAL_PEN_POPULATION = 2;
	private static final int MATERIAL_DEMAND_WINDOW = 48;
	private static final int SUPPORT_DEPTH = 14;
	private static final int SUPPORT_BLOCKS_PER_TICK = 16;
	private static final int MAX_HOUSE_SITE_OBSTACLES = 1024;
	private static final int HOUSE_LOT_RESERVATION_BORDER = 3;
	private static final int HOUSE_LOT_CLEARANCE_ABOVE = 12;
	private static final int HOUSE_ACCESS_CORRIDOR_REACH = 24;
	/** A successful one-block clearance already is a visible work pulse; retry on the next worker tick. */
	static final long HOUSE_CLEARANCE_RETRY_TICKS = 0L;
	/** Keep a plan stable while its assigned worker is temporarily unloaded or unavailable. */
	private static final long PLAN_OWNER_GRACE = 1200L;
	private static final int MAX_BUILD_HELPERS_PER_PROJECT = 3;
	private static final int MAX_CREW_STAND_FALLBACK_PROBES = 3;
	private static final long CREW_STAND_RETRY_TICKS = 20L;
	private static final long CREW_STAND_PATH_RETRY_TICKS = 100L;
	private static final long CREW_STAND_STALL_TICKS = 120L;
	private static final long ROUTE_WAYPOINT_CACHE_TICKS = 20L;
	static final long PROJECT_STAFFING_RETRY_TICKS = 200L;
	static final int MAX_PROJECT_STAFFING_CANDIDATES = 8;
	private static final int MAX_PROJECT_STAFFING_STAND_PROBES = 8;
	private static final int PUBLIC_STOCK_SCAN_BLOCKS_PER_TICK = 128;
	private static final int PUBLIC_STOCK_SOURCES_PER_TICK = 8;
	private static final int MAX_PUBLIC_STOCK_SOURCES = 64;
	private static final int PUBLIC_STOCK_BUILDING_VOLUME = 19 * 11 * 19;
	private static final long PUBLIC_STOCK_REFRESH_TICKS = 1200L;
	private static final long PUBLIC_STOCK_RETRY_TICKS = 100L;
	private static final String[] PUBLIC_STOCK_BUILDINGS = {Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.STATION};
	private static final Map<ServerLevel, Map<Long, PublicStockScan>> PUBLIC_STOCK_SCANS = new WeakHashMap<>();
	/** Crew selection is identical for every resident tick in one village/server tick; keep one immutable snapshot. */
	private static final Map<ServerLevel, Map<Long, BuildCrewSnapshot>> BUILD_CREW_SNAPSHOTS = new WeakHashMap<>();
	/** Keep each crew member's verified stand until it is occupied, blocked, or has stopped making progress. */
	private static final Map<ServerLevel, Map<java.util.UUID, CrewStandCache>> BUILD_CREW_STANDS = new WeakHashMap<>();
	/** Reuse long route waypoints briefly; recomputing the same path for every work tick causes avoidable lag. */
	private static final Map<ServerLevel, Map<java.util.UUID, RouteWaypointCache>> ROUTE_WAYPOINTS = new WeakHashMap<>();
	/** Reassignment path probes run on a bounded per-project retry, not once for every resident tick. */
	private static final Map<ServerLevel, Map<BuildProjectRetryKey, Long>> BUILD_PROJECT_STAFFING_RETRIES = new WeakHashMap<>();
	private static final class PublicStockScan {
		final List<BlockPos> anchors;
		final List<BlockPos> sources = new ArrayList<>();
		int scanIndex;
		int sourceIndex;
		int sourcesVisited;
		String demandKey = "";
		long refreshAt;
		long retryAt;

		PublicStockScan(List<BlockPos> anchors) { this.anchors = List.copyOf(anchors); }
	}
	record BuildCrewAssignment(BlockPos origin, int standSlot, boolean helper) { }
	private record BuildCrewSnapshot(long gameTime, Map<java.util.UUID, BuildCrewAssignment> assignments) { }
	static record FreightReservations(Map<java.util.UUID, Long> projectOrigins, Set<java.util.UUID> carriers) { }
	private record CrewStandCache(long projectOrigin, int slot, int candidateSlot, BlockPos stand, double bestDistance,
		long lastProgressAt, long retryAt) { }
	private record RouteWaypointCache(BlockPos goal, BlockPos waypoint, long retryAt) { }
	private record BuildProjectRetryKey(long villageBell, long projectOrigin) { }
	private record BootstrapBuilder(java.util.UUID villager, long until) { }
	private static final Map<String, BootstrapBuilder> BOOTSTRAP_BUILDERS = new HashMap<>();
	private static long lastDiag;
	private static long lastPlanDiag = -100000;
	private record HouseLotSelection(VillageData.PlanBounds bounds, NaturalSitePreparation.Status status) { }
	/** Warum der Bau gerade steht (nur fuer das Diagnose-Log). */
	private static String why = "";
	/** Ein Lager-Anbau fand keinen Platz: bis dahin nicht erneut versuchen (sonst blockiert er alle anderen Bauten). */
	private static final java.util.Map<Long, Long> STORAGE_BLOCKED = new java.util.HashMap<>();

	/** New houses use curated designs; saved legacy template plans still resume through their persisted source bed. */
	private static boolean templated(String type) {
		return false;
	}

	/** Check the exact planned house footprint, with a free yard margin and a short approach on its outward side. */
	private static boolean housePlotFits(ServerLevel level, BlockPos anchor, List<Step> steps, Direction outward, int headroom) {
		if (steps == null || steps.isEmpty()) return false;
		int minX = steps.stream().mapToInt(s -> s.pos().getX() - anchor.getX()).min().orElseThrow();
		int maxX = steps.stream().mapToInt(s -> s.pos().getX() - anchor.getX()).max().orElseThrow();
		int minZ = steps.stream().mapToInt(s -> s.pos().getZ() - anchor.getZ()).min().orElseThrow();
		int maxZ = steps.stream().mapToInt(s -> s.pos().getZ() - anchor.getZ()).max().orElseThrow();
		return Sites.housePlot(level, anchor, minX, maxX, minZ, maxZ, headroom, outward);
	}

	static boolean needsNaturalHousePreparation(String type, int variant, boolean hasLegacySource) {
		return type.equals(Blueprints.HOUSE) && HouseDesignCatalog.usesCatalog(variant, hasLegacySource);
	}

	/** New catalog houses use the same safe, one-block natural-grade check as market pads. */
	private static BlockPos naturalHouseAnchor(ServerLevel level, String type, int variant, BlockPos candidate) {
		return naturalHouseAnchor(level, type, variant, false, candidate);
	}

	private static BlockPos naturalHouseAnchor(ServerLevel level, String type, int variant, boolean hasLegacySource, BlockPos candidate) {
		if (!needsNaturalHousePreparation(type, variant, hasLegacySource)) return candidate;
		Architect.Design design = designForPlan(Blueprints.HOUSE, variant, hasLegacySource);
		int half = siteHalf(Blueprints.HOUSE, variant, design, hasLegacySource);
		NaturalSitePreparation.Grade grade = NaturalSitePreparation.inspectResidentialGrade(level, candidate, half, 3);
		if (grade.status() != NaturalSitePreparation.Status.READY) {
			GRADE_REJECT.merge(grade.status().name(), 1, Integer::sum);
			return null;
		}
		return new BlockPos(candidate.getX(), grade.lowSurfaceY(), candidate.getZ());
	}

	/** Diagnostic: candidates tested / rejected per filter since the last "Kein Bauplatz" log line. */
	private static final int[] SITE_REJECT = new int[10];
	private static final java.util.Map<String, Integer> GRADE_REJECT = new java.util.TreeMap<>();
	/** Diagnostic civic search: [Plan-Pruefungen, Parzelle, Zone, Konflikt, Weg, bestanden] since the last log line. */
	private static final java.util.Map<Long, Long> CIVIC_DIAG_AT = new java.util.HashMap<>();
	private static final java.util.Map<Long, int[][]> CIVIC_REJECT_BY_VILLAGE = new java.util.HashMap<>();

	/** Candidate preflight uses the exact generated house and its approach yard, including safe-clearable plants. */
	private static boolean siteCandidateFits(Ctx c, VillageRef ref, String type, int variant, Architect.Design design,
			VillageStyle style, BlockPos candidate, BlockPos facingAnchor) {
		return siteCandidateAnchor(c, ref, type, variant, design, style, candidate, facingAnchor) != null;
	}

	private static BlockPos siteCandidateAnchor(Ctx c, VillageRef ref, String type, int variant, Architect.Design design,
			VillageStyle style, BlockPos candidate, BlockPos facingAnchor) {
		SITE_REJECT[0]++;
		BlockPos anchor = naturalHouseAnchor(c.level(), type, variant, candidate);
		if (anchor == null) { SITE_REJECT[1]++; return null; }
		if (!Sites.villageLevel(c.level(), ref.bell(), anchor.getY())) { SITE_REJECT[2]++; return null; }
		Direction outward = Sites.facingTowards(anchor, facingAnchor);
		List<Step> candidateSteps = newHouseTemplatePreview(c.level(), ref, type, design, anchor, outward);
		boolean templateCandidate = candidateSteps != null;
		if (!templateCandidate) candidateSteps = sitingSteps(c, ref, type, variant, design, style, anchor, facingAnchor);
		List<Step> candidatePlan = candidateSteps;
		if (type.equals(Blueprints.HOUSE)) {
			int clearance = design == null ? Blueprints.headroom(type) : siteHeadroom(type, variant, design);
			if (templateCandidate) clearance = Math.max(clearance, 10);
			if (!housePlotFits(c.level(), anchor, candidateSteps, outward, clearance)) { SITE_REJECT[3]++; return null; }
			if (!templateCandidate) {
				int yardStyle = Paths.yardStyle(c.level(), anchor, outward);
				List<Step> yard = Paths.yardSteps(c.level(), anchor, outward, yardStyle);
				if (yard.isEmpty() || !Paths.houseYardFits(c.level(), candidateSteps, anchor, outward, yardStyle)) { SITE_REJECT[4]++; return null; }
				candidatePlan = appendHouseYard(candidateSteps, yard);
			}
		}
		if (rejectedPlot(ref, anchor)) { SITE_REJECT[5]++; return null; }
		if (!ZoneAwareSites.allowsPlan(ref, type, candidatePlan)) { SITE_REJECT[6]++; return null; }
		if (planSiteConflicts(ref, candidatePlan, anchor)) { SITE_REJECT[7]++; return null; }
		if (needsNaturalHousePreparation(type, variant, false)
			&& fittingHouseLotBounds(ref, anchor, candidatePlan, variant, false) == null) { SITE_REJECT[8]++; return null; }
		if (!routeConnected(c, ref, anchor)) { SITE_REJECT[9]++; return null; }
		return anchor;
	}

	private static long routeCheckTick = Long.MIN_VALUE;
	private static int routeChecksThisTick;
	private static final int ROUTE_CHECKS_PER_TICK = 6;

	/**
	 * Site candidates must be walkable from where the builder stands: a complete route, without an absurd detour
	 * (terraces behind cliffs or water pass every terrain test but strand the crew). Path searches are expensive, so only
	 * a few run per tick; beyond that the later {@link #reachablePlot} check still decides.
	 */
	private static boolean routeConnected(Ctx c, VillageRef ref, BlockPos site) {
		if (c.now() != routeCheckTick) {
			routeCheckTick = c.now();
			routeChecksThisTick = 0;
		}
		if (++routeChecksThisTick > ROUTE_CHECKS_PER_TICK) return true;
		BlockPos from = c.v().blockPosition();
		// A worker far from the village says nothing about connectivity to it.
		if (from.distSqr(ref.bell()) > 48.0 * 48.0) return true;
		var route = workPath(c, site);
		if (route == null || !route.canReach()) return false;
		int horizontal = Math.abs(from.getX() - site.getX()) + Math.abs(from.getZ() - site.getZ());
		return route.getNodeCount() <= horizontal * 2 + 40;
	}

	/** House-only search: generic flat-site filters reject rooted trees before the safe-clearance preflight can see them. */
	private static BlockPos findHouseLot(Ctx c, VillageRef ref, int variant, Architect.Design design, VillageStyle style,
		int minRadius, int maxRadius, int attempts) {
		ServerLevel level = c.level();
		Set<Long> visited = new java.util.HashSet<>();
		for (int attempt = 0; attempt < attempts; attempt++) {
			BlockPos candidate = ZoneAwareSites.candidate(level, ref, Blueprints.HOUSE, c.v().getRandom(), minRadius, maxRadius);
			if (candidate == null || !visited.add(BlockPos.asLong(candidate.getX(), 0, candidate.getZ()))) continue;
			long dx = (long) candidate.getX() - ref.bell().getX();
			long dz = (long) candidate.getZ() - ref.bell().getZ();
			long distanceSquared = dx * dx + dz * dz;
			if (distanceSquared < (long) minRadius * minRadius || distanceSquared > (long) maxRadius * maxRadius) continue;
			BlockPos anchor = siteCandidateAnchor(c, ref, Blueprints.HOUSE, variant, design, style, candidate, ref.bell());
			if (anchor != null) return anchor;
		}
		return null;
	}

	/** The lot is a persisted project reservation, so resume uses the exact saved work envelope. */
	private static NaturalSitePreparation.SitePlan housePreparationPlan(VillageRef ref, BlockPos anchor,
		int variant, boolean hasLegacySource, List<Step> steps, VillageData.PlanBounds proposedBounds) {
		VillageData.BuildProject saved = ref == null ? null : ref.data().buildProject(anchor)
			.filter(project -> project.active() && project.type().equals(Blueprints.HOUSE)).orElse(null);
		int stableVariant = saved == null ? variant : saved.variant();
		boolean stableLegacySource = saved == null ? hasLegacySource : saved.source().isPresent();
		Architect.Design design = designForPlan(Blueprints.HOUSE, stableVariant, stableLegacySource);
		int footprintHalf = houseFootprintHalf(anchor, steps, stableVariant, stableLegacySource);
		VillageData.PlanBounds bounds = saved == null ? null : saved.bounds().orElse(null);
		if (bounds == null) bounds = proposedBounds;
		if (bounds == null) bounds = houseLotReservationBounds(anchor, Math.max(footprintHalf,
			HouseLotPreparation.minimumExpandedLotHalf(footprintHalf)));
		int half = houseLotWorkHalf(anchor, bounds, footprintHalf);
		if (half < 0 && saved != null) half = safeLegacyHouseWorkHalf(anchor, bounds, footprintHalf);
		if (half < 0) half = HouseLotPreparation.MAX_LOT_HALF + 1; // Invalid saved geometry must fail closed.
		// Leave enough vertical clearance to remove ordinary mature tree trunks before the roof is started.
		int clearance = Math.max(HOUSE_LOT_CLEARANCE_ABOVE, siteHeadroom(Blueprints.HOUSE, stableVariant, design, stableLegacySource));
		return new NaturalSitePreparation.SitePlan(anchor, half, clearance, 3, MAX_HOUSE_SITE_OBSTACLES, false);
	}

	static NaturalSitePreparation.Scan inspectHouseClearance(ServerLevel level, VillageRef ref, BlockPos anchor,
		int variant, boolean hasLegacySource, List<Step> steps, VillageData.PlanBounds proposedBounds) {
		return NaturalSitePreparation.inspect(level, housePreparationPlan(ref, anchor, variant, hasLegacySource, steps, proposedBounds));
	}

	/** Compatibility entry point for the existing natural-site regression fixtures. */
	static NaturalSitePreparation.Scan inspectHouseClearance(ServerLevel level, VillageRef ref, BlockPos anchor,
		int variant, boolean hasLegacySource) {
		return inspectHouseClearance(level, ref, anchor, variant, hasLegacySource, null, null);
	}

	private static int houseFootprintHalf(BlockPos anchor, List<Step> steps, int variant, boolean hasLegacySource) {
		if (anchor == null) return HouseLotPreparation.MAX_LOT_HALF + 1;
		Architect.Design design = designForPlan(Blueprints.HOUSE, variant, hasLegacySource);
		int half = siteHalf(Blueprints.HOUSE, variant, design, hasLegacySource);
		if (steps == null || steps.isEmpty()) return half;
		long stepHalf = Math.max(Math.max(Math.abs((long)steps.stream().mapToInt(step -> step.pos().getX()).min().orElse(anchor.getX()) - anchor.getX()),
			Math.abs((long)steps.stream().mapToInt(step -> step.pos().getX()).max().orElse(anchor.getX()) - anchor.getX())),
			Math.max(Math.abs((long)steps.stream().mapToInt(step -> step.pos().getZ()).min().orElse(anchor.getZ()) - anchor.getZ()),
				Math.abs((long)steps.stream().mapToInt(step -> step.pos().getZ()).max().orElse(anchor.getZ()) - anchor.getZ())));
		return stepHalf > HouseLotPreparation.MAX_LOT_HALF ? HouseLotPreparation.MAX_LOT_HALF + 1
			: Math.max(half, (int)stepHalf);
	}

	static VillageData.PlanBounds houseLotReservationBounds(BlockPos center, int workHalf) {
		if (center == null || workHalf < 0 || workHalf > HouseLotPreparation.MAX_LOT_HALF) return null;
		int radius = workHalf + HOUSE_LOT_RESERVATION_BORDER;
		return new VillageData.PlanBounds(center.getX() - radius, center.getX() + radius,
			center.getZ() - radius, center.getZ() + radius);
	}

	static int houseLotWorkHalf(BlockPos center, VillageData.PlanBounds bounds, int footprintHalf) {
		if (center == null || bounds == null || footprintHalf < 0 || footprintHalf > HouseLotPreparation.MAX_LOT_HALF) return -1;
		long workHalf = reservedWorkHalf(center, bounds);
		return workHalf < footprintHalf || workHalf > HouseLotPreparation.MAX_LOT_HALF ? -1 : (int)workHalf;
	}

	private static long reservedWorkHalf(BlockPos center, VillageData.PlanBounds bounds) {
		if (center == null || bounds == null) return -1L;
		long symmetricRadius = Math.min(Math.min((long)center.getX() - bounds.minX(), (long)bounds.maxX() - center.getX()),
			Math.min((long)center.getZ() - bounds.minZ(), (long)bounds.maxZ() - center.getZ()));
		return symmetricRadius - HOUSE_LOT_RESERVATION_BORDER;
	}

	private static int safeLegacyHouseWorkHalf(BlockPos center, VillageData.PlanBounds bounds, int footprintHalf) {
		long savedHalf = reservedWorkHalf(center, bounds);
		if (footprintHalf < 0 || footprintHalf > HouseLotPreparation.MAX_LOT_HALF || savedHalf < 0) return -1;
		// Old projects may reserve a wider garden than the current bounded preparation can scan. Keep their claim,
		// but resume clearance only within the previously supported 17x17 work envelope. The runtime caller separately
		// verifies that the restored house steps themselves fit inside this old radius before allowing any work.
		return (int)Math.min(savedHalf, HouseLotPreparation.MAX_LOT_HALF);
	}

	private static VillageData.PlanBounds fittingHouseLotBounds(VillageRef ref, BlockPos origin, List<Step> steps,
		int variant, boolean hasLegacySource) {
		int footprintHalf = houseFootprintHalf(origin, steps, variant, hasLegacySource);
		int minimumHalf = HouseLotPreparation.minimumExpandedLotHalf(footprintHalf);
		if (minimumHalf < 0) return null;
		for (int half = HouseLotPreparation.MAX_LOT_HALF; half >= minimumHalf; half--) {
			VillageData.PlanBounds bounds = houseLotReservationBounds(origin, half);
			if (houseLotReservationFits(ref, origin, steps, bounds)) return bounds;
		}
		return null;
	}

	private static boolean houseLotReservationFits(VillageRef ref, BlockPos origin, List<Step> steps,
		VillageData.PlanBounds reservation) {
		if (ref == null || origin == null || steps == null || steps.isEmpty() || reservation == null) return false;
		int half = houseLotWorkHalf(origin, reservation, 0);
		if (half < 0 || !stepsFitWorkSquare(origin, steps, half)
			|| !ZoneAwareSites.allowsRect(ref, Blueprints.HOUSE, origin, -half, half, -half, half)) return false;
		return ZoneAwareSites.allowsPlan(ref, Blueprints.HOUSE, steps)
			&& !planSiteConflicts(ref, reservation, origin);
	}

	private static boolean legacyHouseLotReservationFits(VillageRef ref, BlockPos origin, List<Step> houseSteps,
		VillageData.PlanBounds reservation) {
		if (ref == null || origin == null || houseSteps == null || houseSteps.isEmpty() || reservation == null) return false;
		long savedHalf = reservedWorkHalf(origin, reservation);
		if (savedHalf < 0 || !stepsFitWorkSquare(origin, houseSteps, savedHalf)) return false;
		return ZoneAwareSites.allowsRect(ref, Blueprints.HOUSE, BlockPos.ZERO,
			reservation.minX(), reservation.maxX(), reservation.minZ(), reservation.maxZ())
			&& ZoneAwareSites.allowsPlan(ref, Blueprints.HOUSE, houseSteps)
			&& !planSiteConflicts(ref, reservation, origin);
	}

	private static boolean stepsFitWorkSquare(BlockPos origin, List<Step> steps, long half) {
		if (origin == null || steps == null || steps.isEmpty() || half < 0) return false;
		long minX = (long)origin.getX() - half, maxX = (long)origin.getX() + half;
		long minZ = (long)origin.getZ() - half, maxZ = (long)origin.getZ() + half;
		return steps.stream().allMatch(step -> step.pos().getX() >= minX && step.pos().getX() <= maxX
			&& step.pos().getZ() >= minZ && step.pos().getZ() <= maxZ);
	}

	/** One normal builder action clears at most one verified natural obstacle from its persisted house lot. */
	static NaturalSitePreparation.Scan clearHouseClearance(ServerLevel level, VillageRef ref, BlockPos anchor,
		int variant, boolean hasLegacySource, BlockPos entrance, Ctx worker) {
		return clearHouseClearance(level, ref, anchor, variant, hasLegacySource, entrance, worker, false);
	}

	/** Production clearance rechecks the saved road approach immediately before changing a lot block. */
	private static NaturalSitePreparation.Scan clearHouseClearance(ServerLevel level, VillageRef ref, BlockPos anchor,
		int variant, boolean hasLegacySource, BlockPos entrance, Ctx worker, boolean requireRoadAccess) {
		NaturalSitePreparation.SitePlan plan = housePreparationPlan(ref, anchor, variant, hasLegacySource, null, null);
		if (!plan.valid()) {
			return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED, null, 0);
		}
		NaturalSitePreparation.Scan preflight = NaturalSitePreparation.inspect(level, plan);
		if (preflight.status() != NaturalSitePreparation.Status.CLEARABLE
			&& preflight.status() != NaturalSitePreparation.Status.READY) return preflight;
		if (ref == null || entrance == null) {
			return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED, null, 0);
		}
		HouseLotPreparation.Corridor corridor = new HouseLotPreparation.Corridor(HouseLotPreparation.Status.READY, null, List.of());
		if (requireRoadAccess) {
			BlockPos road = nearestHouseRoad(level, entrance, ref);
			if (road == null) return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED, null, 0);
			corridor = HouseLotPreparation.accessCorridor(level, plan.min(), plan.max(), road, HOUSE_ACCESS_CORRIDOR_REACH);
			if (corridor.status() != HouseLotPreparation.Status.READY) {
				return new NaturalSitePreparation.Scan(naturalSiteStatus(corridor.status()), null, 0);
			}
			if (!houseAccessAvailable(ref, anchor, corridor)) {
				return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED, null, 0);
			}
			HouseLotPreparation.Scan accessPreflight = HouseLotPreparation.inspect(level, corridor.cells(), plan.maxObstacles());
			if (accessPreflight.status() != HouseLotPreparation.Status.CLEARABLE
				&& accessPreflight.status() != HouseLotPreparation.Status.READY) return housePreparationScan(accessPreflight);
		}
		if (worker == null || worker.v() == null) return NaturalSitePreparation.clearOne(level, plan, null);
		// The persisted SitePlan bounds remain the exact work envelope after reload.
		HouseLotPreparation.Scan cleared = HouseLotPreparation.clearOne(
			level, plan.min(), plan.max(), plan.maxObstacles(), worker);
		if (cleared.status() != HouseLotPreparation.Status.READY) return housePreparationScan(cleared);
		return requireRoadAccess
			? housePreparationScan(HouseLotPreparation.clearOne(level, corridor.cells(), plan.maxObstacles(), worker))
			: new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.CLEARED, cleared.next(), cleared.obstacleCount());
	}

	private static BlockPos nearestHouseRoad(ServerLevel level, BlockPos from, VillageRef ref) {
		return Paths.nearbyRoad(level, from, ref, true);
	}

	private static boolean houseAccessAvailable(VillageRef ref, BlockPos origin, HouseLotPreparation.Corridor corridor) {
		if (ref == null || origin == null || corridor == null || corridor.status() != HouseLotPreparation.Status.READY) return false;
		if (corridor.road() == null) return true;
		int minX = corridor.road().getX(), maxX = minX, minZ = corridor.road().getZ(), maxZ = minZ;
		for (BlockPos cell : corridor.cells()) {
			minX = Math.min(minX, cell.getX()); maxX = Math.max(maxX, cell.getX());
			minZ = Math.min(minZ, cell.getZ()); maxZ = Math.max(maxZ, cell.getZ());
		}
		if (!ZoneAwareSites.allowsRect(ref, Blueprints.HOUSE, BlockPos.ZERO, minX, maxX, minZ, maxZ)) return false;
		VillageData.PlanBounds access = new VillageData.PlanBounds(minX, maxX, minZ, maxZ);
		for (VillageData.BuildProject project : ref.data().buildProjects) {
			if (!project.active() || project.origin().equals(origin)) continue;
			VillageData.PlanBounds reserved = project.bounds().orElseGet(() ->
				fallbackProjectBounds(project.type(), project.origin(), project.variant(), project.source().isPresent()));
			if (access.overlaps(reserved)) return false;
		}
		return true;
	}

	private static List<Step> housePlanWithYard(ServerLevel level, BlockPos origin, Direction dir, List<Step> houseSteps) {
		if (level == null || origin == null || dir == null || houseSteps == null || houseSteps.isEmpty()) return null;
		int yardStyle = Paths.yardStyle(level, origin, dir);
		List<Step> yard = Paths.yardSteps(level, origin, dir, yardStyle);
		if (yard.isEmpty() || !Paths.houseYardFits(level, houseSteps, origin, dir, yardStyle)) return null;
		return appendHouseYard(houseSteps, yard);
	}

	/** Pick the largest bounded lot that fits the current zone/project, terrain, protected-block and road constraints. */
	private static HouseLotSelection prepareHouseLot(ServerLevel level, VillageRef ref, BlockPos origin, Direction dir,
		List<Step> lotSteps, int variant, boolean hasLegacySource) {
		int footprintHalf = houseFootprintHalf(origin, lotSteps, variant, hasLegacySource);
		int minimumHalf = HouseLotPreparation.minimumExpandedLotHalf(footprintHalf);
		if (minimumHalf < 0) return new HouseLotSelection(null, NaturalSitePreparation.Status.BLOCKED);
		boolean waitingForChunk = false;
		java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
		for (int half = HouseLotPreparation.MAX_LOT_HALF; half >= minimumHalf; half--) {
			VillageData.PlanBounds bounds = houseLotReservationBounds(origin, half);
			if (!houseLotReservationFits(ref, origin, lotSteps, bounds)) { reasons.merge("reservation", 1, Integer::sum); continue; }
			NaturalSitePreparation.SitePlan plan = housePreparationPlan(ref, origin, variant, hasLegacySource, lotSteps, bounds);
			if (!plan.valid()) { reasons.merge("plan-invalid", 1, Integer::sum); continue; }
			NaturalSitePreparation.Scan site = NaturalSitePreparation.inspect(level, plan);
			if (site.status() == NaturalSitePreparation.Status.WAITING_FOR_CHUNK) {
				waitingForChunk = true;
				continue;
			}
			if (site.status() != NaturalSitePreparation.Status.CLEARABLE && site.status() != NaturalSitePreparation.Status.READY) { NaturalSitePreparation.Scan spacing = NaturalSitePreparation.inspectVillageStructureSpacing(level, plan); String cause = spacing.status() != NaturalSitePreparation.Status.READY ? "structure-" + spacing.status() : NaturalSitePreparation.inspectResidentialGrade(level, plan.center(), plan.half(), plan.maxRelief()).status() != NaturalSitePreparation.Status.READY ? "grade" : "volume"; reasons.merge("site-" + cause + "-block-" + (site.next() == null ? "none" : level.getBlockState(site.next()).getBlock().toString() + "-above-" + level.getBlockState(site.next().above()).getBlock().toString() + "-at-" + site.next().toShortString()) + "-count-" + site.obstacleCount(), 1, Integer::sum); continue; }
			HouseLotPreparation.Scan lot = HouseLotPreparation.inspect(level, plan.min(), plan.max(), plan.maxObstacles());
			if (lot.status() == HouseLotPreparation.Status.WAITING_FOR_CHUNK) {
				waitingForChunk = true;
				continue;
			}
			if (lot.status() != HouseLotPreparation.Status.CLEARABLE && lot.status() != HouseLotPreparation.Status.READY) { reasons.merge("lot-" + lot.status(), 1, Integer::sum); continue; }

			BlockPos entrance = Paths.entrance(lotSteps, origin, dir);
			BlockPos road = entrance == null ? null : nearestHouseRoad(level, entrance, ref);
			if (road == null) { reasons.merge("no-road", 1, Integer::sum); continue; }
			HouseLotPreparation.Corridor corridor = HouseLotPreparation.accessCorridor(level, plan.min(), plan.max(), road, HOUSE_ACCESS_CORRIDOR_REACH);
			if (corridor.status() == HouseLotPreparation.Status.WAITING_FOR_CHUNK) {
				waitingForChunk = true;
				continue;
			}
			if (corridor.status() != HouseLotPreparation.Status.READY) { reasons.merge("corridor-" + corridor.status(), 1, Integer::sum); continue; }
			if (!houseAccessAvailable(ref, origin, corridor)) { reasons.merge("corridor-reserved", 1, Integer::sum); continue; }
			HouseLotPreparation.Scan access = HouseLotPreparation.inspect(level, corridor.cells(), plan.maxObstacles());
			if (access.status() == HouseLotPreparation.Status.WAITING_FOR_CHUNK) {
				waitingForChunk = true;
				continue;
			}
			if (access.status() != HouseLotPreparation.Status.CLEARABLE && access.status() != HouseLotPreparation.Status.READY) { reasons.merge("access-" + access.status(), 1, Integer::sum); continue; }
			return new HouseLotSelection(bounds, NaturalSitePreparation.Status.READY);
		}
		LOGGER.info("Villagers: Repurposed: Wohnparzellen-Diagnose {}: {} wartende Chunks {}", origin.toShortString(), reasons, waitingForChunk);
		return new HouseLotSelection(null, waitingForChunk
			? NaturalSitePreparation.Status.WAITING_FOR_CHUNK : NaturalSitePreparation.Status.BLOCKED);
	}

	private static NaturalSitePreparation.Scan housePreparationScan(HouseLotPreparation.Scan scan) {
		return new NaturalSitePreparation.Scan(naturalSiteStatus(scan.status()), scan.next(), scan.obstacleCount());
	}

	private static NaturalSitePreparation.Status naturalSiteStatus(HouseLotPreparation.Status status) {
		return switch (status) {
			case READY -> NaturalSitePreparation.Status.READY;
			case CLEARABLE -> NaturalSitePreparation.Status.CLEARABLE;
			case CLEARED -> NaturalSitePreparation.Status.CLEARED;
			case WAITING_FOR_CHUNK -> NaturalSitePreparation.Status.WAITING_FOR_CHUNK;
			case WAITING_FOR_ENTITY -> NaturalSitePreparation.Status.WAITING_FOR_ENTITY;
			case WAITING_FOR_INVENTORY -> NaturalSitePreparation.Status.WAITING_FOR_INVENTORY;
			case WAITING_FOR_TOOL -> NaturalSitePreparation.Status.WAITING_FOR_TOOL;
			case BLOCKED -> NaturalSitePreparation.Status.BLOCKED;
		};
	}

	/** Revalidate the whole persisted lot before each natural-clear action. */
	private static boolean houseLotStillSafe(ServerLevel level, VillageRef ref, BlockPos origin, Direction dir,
		List<Step> houseSteps, List<Step> allSteps, int variant, boolean hasLegacySource) {
		if (houseSteps == null || houseSteps.isEmpty()) return false;
		Architect.Design design = designForPlan(Blueprints.HOUSE, variant, hasLegacySource);
		VillageData.PlanBounds bounds = savedHouseLotBounds(ref, origin, variant, hasLegacySource);
		int fullFootprintHalf = houseFootprintHalf(origin, allSteps, variant, hasLegacySource);
		if (!stepsFitWorkSquare(origin, allSteps, Math.max(0L, reservedWorkHalf(origin, bounds)))) {
			VillageData.PlanBounds migrated = fittingHouseLotBounds(ref, origin, allSteps, variant, hasLegacySource);
			if (migrated != null && !migrated.equals(bounds)) {
				persistHouseLotBounds(ref, origin, migrated);
				bounds = migrated;
			}
		}
		int half = houseLotWorkHalf(origin, bounds, fullFootprintHalf);
		if (half >= 0) {
			if (!houseLotReservationFits(ref, origin, allSteps, bounds)) return false;
		} else {
			int legacyFootprintHalf = houseFootprintHalf(origin, houseSteps, variant, hasLegacySource);
			half = safeLegacyHouseWorkHalf(origin, bounds, legacyFootprintHalf);
			if (half < 0 || !legacyHouseLotReservationFits(ref, origin, houseSteps, bounds)) return false;
		}
		NaturalSitePreparation.Grade grade = NaturalSitePreparation.inspectResidentialGrade(level, origin, half, 3);
		if (grade.status() != NaturalSitePreparation.Status.READY || origin.getY() < grade.lowSurfaceY()
			|| origin.getY() > grade.highSurfaceY()) return false;
		int headroom = siteHeadroom(Blueprints.HOUSE, variant, design);
		return housePlotFits(level, origin, houseSteps, dir, headroom);
	}

	private static void persistHouseLotBounds(VillageRef ref, BlockPos origin, VillageData.PlanBounds bounds) {
		if (ref == null || origin == null || bounds == null) return;
		VillageData.BuildProject saved = ref.data().buildProject(origin).orElse(null);
		if (saved == null || !saved.active() || !saved.type().equals(Blueprints.HOUSE)) return;
		VillageData.BuildProject migrated = new VillageData.BuildProject(saved.type(), saved.origin(), saved.dir(), saved.step(),
			saved.variant(), saved.source(), saved.progress(), saved.owner(), saved.ownerUnavailableSince(), saved.wanted(),
			saved.haul(), saved.materialWait(), saved.construction(), Optional.of(bounds), saved.repair(), saved.cleanupOnly());
		ref.data().putBuildProject(migrated);
		ref.data().syncPrimaryProjectView();
		ref.dirty();
	}

	private static VillageData.PlanBounds savedHouseLotBounds(VillageRef ref, BlockPos origin, int variant, boolean hasLegacySource) {
		VillageData.BuildProject saved = ref == null ? null : ref.data().buildProject(origin)
			.filter(project -> project.active() && project.type().equals(Blueprints.HOUSE)).orElse(null);
		if (saved != null && saved.bounds().isPresent()) return saved.bounds().orElseThrow();
		return fallbackProjectBounds(Blueprints.HOUSE, origin, variant, hasLegacySource);
	}

	private static boolean usesLegacySavedHouseYard(VillageRef ref, BlockPos origin) {
		VillageData.BuildProject saved = ref == null ? null : ref.data().buildProject(origin)
			.filter(project -> project.active() && project.type().equals(Blueprints.HOUSE)).orElse(null);
		if (saved == null || saved.bounds().isEmpty()) return false;
		VillageData.PlanBounds bounds = saved.bounds().orElseThrow();
		long radius = Math.max(Math.max((long)origin.getX() - bounds.minX(), (long)bounds.maxX() - origin.getX()),
			Math.max((long)origin.getZ() - bounds.minZ(), (long)bounds.maxZ() - origin.getZ()));
		// Previous commits reserved boundsOf(house + the old garden at (8,4)) plus the 3-block project border.
		return radius >= 12L;
	}

	private static List<Step> appendHouseYard(List<Step> house, List<Step> yard) {
		if (house == null || yard == null || yard.isEmpty()) return house;
		List<Step> result = new ArrayList<>(house.size() + yard.size());
		result.addAll(house);
		result.addAll(yard);
		return result;
	}

	/** Gebaeude, die der Architect im Dorfstil entwirft (jedes Mal anders). */
	private static boolean generated(String type) {
		return architect(type) || Landmarks.handles(type);
	}

	/** Naechster fehlender Pflicht-Meilenstein (null = alle vorhanden oder der Weiler hat noch weniger als zwei Bewohner). */
	public static String nextSpecial(VillageRef ref, int pop) {
		if (pop >= 2 && !de.villagerplus.village.Specials.has(ref, Blueprints.GRANARY)) {
			return Blueprints.GRANARY;
		}
		if (pop >= 2 && de.villagerplus.village.Specials.has(ref, Blueprints.GRANARY) && !de.villagerplus.village.Specials.has(ref, Blueprints.TOOLSHED)) {
			return Blueprints.TOOLSHED;
		}
		if (pop >= 2 && de.villagerplus.village.Specials.has(ref, Blueprints.TOOLSHED) && ref.data().extra.school.isEmpty()) {
			return Blueprints.SCHOOL;
		}
		if (pop >= 2 && ref.data().extra.school.isPresent() && de.villagerplus.village.Specials.has(ref, Blueprints.TOOLSHED)
			&& !de.villagerplus.village.Specials.has(ref, Blueprints.KITCHEN)) {
			return Blueprints.KITCHEN;
		}
		// Spaetphase: Bahnhof, sobald ein Nachbardorf Metropolen-Verbund (+90) erreicht hat und die Kueche steht.
		if (pop >= Roads_POP && de.villagerplus.village.Specials.has(ref, Blueprints.KITCHEN) && !de.villagerplus.village.Specials.has(ref, Blueprints.STATION)
			&& de.villagerplus.village.Network.of(ref.bellEntity()).links().stream().anyMatch(l -> l.relation() >= de.villagerplus.village.Roads.METROPOLIS)) {
			return Blueprints.STATION;
		}
		return null;
	}

	/** Mandatory civic milestones continue immediately after each completion instead of waiting a full house-build cooldown. */
	static boolean cooldownBlocksBuild(VillageRef ref, int population, long homeless, long now) {
		if (nextSpecial(ref, population) != null) return false;
		return now < ref.data().nextBuild;
	}

	/** Extra capacity sheds defer to early services; the first shared shed can answer genuine pressure after the granary. */
	static boolean shouldExpandStorageUnderPressure(String activePlan, String pendingMilestone, boolean storagePressure) {
		return shouldExpandStorageUnderPressure(activePlan, pendingMilestone, storagePressure, false);
	}

	/** The first public shed may answer early capacity pressure once the first granary is safely underway/completed. */
	static boolean shouldExpandStorageUnderPressure(String activePlan, String pendingMilestone, boolean storagePressure,
			boolean firstPublicStorageMissing) {
		return shouldExpandStorageUnderPressure(activePlan, pendingMilestone, storagePressure, firstPublicStorageMissing, false);
	}

	/** Storage pressure may choose the next plan, but never replaces any active plan. */
	static boolean shouldExpandStorageUnderPressure(String activePlan, String pendingMilestone, boolean storagePressure,
			boolean firstPublicStorageMissing, boolean needHouse) {
		// Capacity pressure chooses the next project; it never cancels a project that already owns a saved cursor.
		if (!storagePressure || activePlan != null) return false;
		if (Blueprints.GRANARY.equals(pendingMilestone)) return false;
		if (firstPublicStorageMissing) return !needHouse;
		return !Development.deferStorageExpansion(pendingMilestone);
	}

	/** Unknown/unloaded saved storage never counts as missing, so startup priority cannot create a duplicate shed. */
	static boolean firstPublicStorageMissing(boolean hasTrackedStorage, boolean storageLocationsLoaded,
			boolean hasUsablePublicStorage) {
		return !hasTrackedStorage || storageLocationsLoaded && !hasUsablePublicStorage;
	}

	private static boolean firstPublicStorageMissing(ServerLevel level, VillageData data) {
		if (level == null || data == null) return false;
		List<BlockPos> centers = Lager.centers(data);
		boolean tracked = !centers.isEmpty() || !data.extra.lagerChests.isEmpty();
		if (!tracked) return true;

		boolean loaded = centers.stream().allMatch(level::hasChunkAt)
			&& data.extra.lagerChests.stream().allMatch(level::hasChunkAt);
		// Older saves may have a center but no cached chest coordinates. Only treat an empty migration scan
		// as proof of a missing public store when its full bounded search area is loaded.
		if (loaded && data.extra.lagerChests.isEmpty()) {
			loaded = centers.stream().allMatch(center -> storageSearchAreaLoaded(level, center));
		}
		boolean usable = loaded && !Lager.containers(level, data).isEmpty();
		return firstPublicStorageMissing(tracked, loaded, usable);
	}

	private static boolean storageSearchAreaLoaded(ServerLevel level, BlockPos center) {
		for (int dx : new int[] {-5, 5}) for (int dz : new int[] {-5, 5}) {
			if (!level.hasChunkAt(center.offset(dx, -1, dz))) return false;
		}
		return true;
	}

	/** Build the first public shed from two residents onward, alongside an active granary or after its completion. */
	static boolean shouldPrioritizeEarlyPublicStorage(int population, boolean firstPublicStorageMissing, String pendingMilestone) {
		return shouldPrioritizeEarlyPublicStorage(population, firstPublicStorageMissing, pendingMilestone, false, 0);
	}

	/** The first shed may use any open project slot beside a saved granary; the third slot still passes the crew gate. */
	static boolean shouldPrioritizeEarlyPublicStorage(int population, boolean firstPublicStorageMissing, String pendingMilestone,
			boolean granaryProjectActive, int activeProjectCount) {
		if (population < 2 || !firstPublicStorageMissing) return false;
		if (!Blueprints.GRANARY.equals(pendingMilestone)) return true;
		return granaryProjectActive && hasActiveProjectSlot(activeProjectCount);
	}

	static boolean shouldStartEarlyPublicStorage(int population, boolean firstPublicStorageMissing, String pendingMilestone,
			boolean granaryProjectActive, int activeProjectCount, boolean storageProjectExists) {
		return !storageProjectExists && shouldPrioritizeEarlyPublicStorage(population, firstPublicStorageMissing,
			pendingMilestone, granaryProjectActive, activeProjectCount);
	}

	private static final int Roads_POP = de.villagerplus.village.Roads.RAIL_POP;

	/** Gebaeude, die der Architect entwirft (und deren Instandhaltung gemerkt wird). */
	private static boolean architect(String type) {
		return type.equals(Blueprints.HOUSE) || type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type);
	}

	private static Architect.Kind kindOf(String type) {
		return switch (type) {
			case Blueprints.SCHOOL -> Architect.Kind.SCHOOL;
			case Blueprints.STORAGE -> Architect.Kind.STORAGE;
			case Blueprints.GRANARY -> Architect.Kind.GRANARY;
			case Blueprints.TOOLSHED -> Architect.Kind.TOOLSHED;
			case Blueprints.KITCHEN -> Architect.Kind.KITCHEN;
			case Blueprints.STATION -> Architect.Kind.STATION;
			default -> Architect.Kind.HOUSE;
		};
	}

	/** Rebuild the exact design version stored in a generated plan or completed-building record. */
	static Architect.Design designForPlan(String type, int variant) {
		return designForPlan(type, variant, false);
	}

	static Architect.Design designForPlan(String type, int variant, boolean hasLegacySource) {
		return type.equals(Blueprints.HOUSE) ? HouseDesignCatalog.designForPlanVariant(variant, hasLegacySource)
			: Architect.designForPlan(kindOf(type), variant);
	}

	/** Catalog houses use bit 0 only when source-free; bit 1 remains the material-substitution marker. */
	static int newPlanVariant(String type, long seed) {
		return type.equals(Blueprints.HOUSE) ? HouseDesignCatalog.newPlanVariant(Architect.sizeTier(seed), seed)
			: Architect.withRoleMarkerVersion(kindOf(type), (int) (seed << 2));
	}

	/** The first public shed is a compact utility building; later capacity sheds keep the village's size progression. */
	static long civicDesignSeed(String type, boolean firstPublicStorageMissing, long seed, Architect.SizeTier requestedTier) {
		Architect.SizeTier tier = Blueprints.STORAGE.equals(type) && firstPublicStorageMissing
			? Architect.SizeTier.COMPACT : requestedTier;
		return Architect.withSizeTier(seed, tier);
	}

	private static boolean compactStorage(Architect.Design design) {
		return design != null && design.kind() == Architect.Kind.STORAGE
			&& design.hw() == 3 && design.hd() == 2 && design.wallH() == 3;
	}

	/** First shed's loading chest is an ordinary paid build step, placed at the back wall with its opening toward the door. */
	static Step firstStorageChestStep(List<Step> steps, BlockPos origin, Direction dir, Architect.Design design) {
		if (steps == null || origin == null || dir == null || !compactStorage(design)) return null;
		int backRow = -design.hd() + 1;
		for (int lateral = -design.hw() + 1; lateral <= design.hw() - 1; lateral++) {
			BlockPos chest = origin.relative(dir.getClockWise(), lateral).relative(dir, backRow);
			BlockPos floor = chest.below();
			boolean hasPlannedFloor = steps.stream().anyMatch(step -> step.pos().equals(floor));
			boolean occupied = steps.stream().anyMatch(step -> step.pos().equals(chest) || step.pos().equals(chest.above()));
			if (!hasPlannedFloor || occupied) continue;
			return Step.block(chest, Blocks.CHEST,
				block -> block.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, dir));
		}
		return null;
	}

	private static List<Step> addPaidFirstStorageChest(List<Step> steps, BlockPos origin, Direction dir, Architect.Design design) {
		if (!compactStorage(design)) return steps;
		Step chest = firstStorageChestStep(steps, origin, dir, design);
		if (chest == null) return null;
		if (steps.stream().anyMatch(step -> step.pos().equals(chest.pos()))) return steps;
		List<Step> result = new ArrayList<>(steps.size() + 1);
		result.addAll(steps);
		result.add(chest);
		return List.copyOf(result);
	}

	static int siteHalf(String type, int variant, Architect.Design design) {
		return siteHalf(type, variant, design, false);
	}

	private static int siteHalf(String type, int variant, Architect.Design design, boolean hasLegacySource) {
		return type.equals(Blueprints.HOUSE) && HouseDesignCatalog.usesCatalog(variant, hasLegacySource)
			? HouseDesignCatalog.siteHalf(design) : design.half();
	}

	static int siteHeadroom(String type, int variant, Architect.Design design) {
		return siteHeadroom(type, variant, design, false);
	}

	private static int siteHeadroom(String type, int variant, Architect.Design design, boolean hasLegacySource) {
		return type.equals(Blueprints.HOUSE) && HouseDesignCatalog.usesCatalog(variant, hasLegacySource)
			? HouseDesignCatalog.siteHeadroom(design) : design.headroom();

	}

	/** Homes and functional buildings progress through their own saved, deterministic size cycles. */
	private static Architect.SizeTier nextSizeTier(VillageRef ref, String type) {
		boolean home = type.equals(Blueprints.HOUSE);
		int completed = 0;
		for (Built building : builtOf(ref)) {
			if (architect(building.type()) && building.type().equals(Blueprints.HOUSE) == home) completed++;
		}
		return Blueprints.sizeTierForOrdinal(completed);
	}

	private static final Map<Long, Palette> PALETTES = new HashMap<>();
	/** Je Bauplatz: urspruengliche Gelaendehoehen der vorbereiteten Saeulen (fuer das Wiederherstellen nach einem Abbruch). */
	private static final Map<Long, Map<Long, Integer>> PREP_RECORD = new HashMap<>();
	/** Von /vpplan angelegte Testauftraege: werden nicht zugunsten eines dringenden Lagers abgebrochen. */
	private static final java.util.Set<Long> DEBUG_PLANS = new java.util.HashSet<>();

	/** Grundflaechen-Saeulen je Bauplan (siehe {@link Prep#footprint}). */
	private static final Map<Long, java.util.Set<Long>> FOOT = new HashMap<>();

	/**
	 * Schmuck ausserhalb der Grundflaeche (Blumen, Fass, Weg ...) wird an die tatsaechliche Bodenhoehe angepasst statt das Gelaende
	 * einzuebnen; null = weglassen (Hang zu steil oder kein natuerlicher Boden).
	 */
	private static Step snap(ServerLevel level, BlockPos origin, Step s) {
		int x = s.pos().getX();
		int z = s.pos().getZ();
		if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) {
			return s;
		}
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (Math.abs(surface - origin.getY()) > 3) {
			return null;
		}
		BlockState below = level.getBlockState(new BlockPos(x, surface - 1, z));
		if (!VillageStyle.natural(below) && !below.is(Blocks.DIRT_PATH)) {
			return null;
		}
		BlockPos np = new BlockPos(x, surface + (s.pos().getY() - origin.getY()), z);
		return new Step(np, s.item(), s.tag(), s.cost(), s.optional(), s.linkedTo(), s.make());
	}

	static boolean storageFree(Ctx c, VillageRef ref) {
		return c.now() >= STORAGE_BLOCKED.getOrDefault(ref.bell().asLong(), 0L);
	}
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

	private Builder() {
	}

	/** Bausteine eines frueher gebauten Gebaeudes (fuer Abriss und Instandhaltung), aus Entwurf und gespeicherter Palette. */
	public static List<Step> stepsOfBuilt(ServerLevel level, VillageRef ref, Built b, Villager v) {
		if (!architect(b.type())) {
			return List.of();
		}
		Palette palette = Palette.ofIds(b.palette());
		if (palette == null) {
			palette = PALETTES.computeIfAbsent(b.origin().asLong(), k -> Palette.detect(level, ref.bell(), v));
		}
		return Architect.build(designForPlan(b.type(), b.variant()), palette, b.origin(), Direction.from2DDataValue(b.dir()));
	}

	public static List<Built> builtOf(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.BUILDINGS, List.of());
	}

	/** Gebaeude aus der Instandhaltungsliste austragen (nach dem Abriss). */
	public static void forget(VillageRef ref, Built b) {
		List<Built> list = new ArrayList<>(builtOf(ref));
		list.removeIf(x -> x.origin().equals(b.origin()));
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.BUILDINGS, List.copyOf(list));
		ref.dirty();
	}

	/** Maurer bauen Haeuser und Brunnen, Bauern legen Felder an. true = beschaeftigt. */
	public static boolean tick(Ctx c, boolean mason) {
		return tick(c, mason, null, -1, false);
	}

	private static boolean tick(Ctx c, boolean mason, BlockPos requestedOrigin, int standSlot, boolean helper) {
		VillageRef ref = c.village();
		if (ref == null) return helper ? false : tickProject(c, mason,
			new net.minecraft.world.SimpleContainer(SITE_STOCK_SLOTS), new ArrayList<>(), -1, false);
		VillageData data = ref.data();
		migrateLegacyProjects(c, ref, data);
		reconcileProjectManifests(c, ref, data);
		Container box = Lager.combined(c.level(), ref);
		if (!helper && cleanOneAbandonedSite(c, ref, data, box)) return true;
		if (!helper) retryUnstaffedProjectOwner(c, ref, data, box);

		VillageData.BuildProject project = requestedOrigin == null
			? selectProjectForWorker(c, ref, data, mason)
			: data.buildProject(requestedOrigin).filter(VillageData.BuildProject::active).orElse(null);
		if (helper && (project == null || !eligibleBuildHelper(c.level(), ref, c.v())
			|| !supportsConstructionHaul(project))) return false;
		if (project == null && data.buildProjects.stream().anyMatch(active -> active.active()
			&& active.owner().filter(c.v().getUUID()::equals).isPresent())) {
			restorePrimaryProject(ref, data);
			ref.dirty();
			return false;
		}
		if (project == null && !canStartProject(c.level(), ref, data, c.v(), mason)) {
			restorePrimaryProject(ref, data);
			ref.dirty();
			return false;
		}
		if (project == null) {
			data.clearPlanViewForProjectSlot();
			ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		} else {
			loadProjectView(ref, data, project);
		}
		Container siteStock = project == null ? new net.minecraft.world.SimpleContainer(SITE_STOCK_SLOTS) : siteStock(project);
		List<String> cargoManifest = new ArrayList<>(project == null ? List.of()
			: de.villagerplus.job.BuildHaul.manifest(c, project.origin(), project.cargoManifest()));
		int selectedStandSlot = standSlot;
		if (!helper && project != null) {
			BuildCrewAssignment assignment = buildCrewAssignments(c.level(), ref, data).get(c.v().getUUID());
			if (assignment != null && !assignment.helper() && assignment.origin().equals(project.origin())) {
				selectedStandSlot = assignment.standSlot();
			}
		}
		try {
			return tickProject(c, mason, siteStock, cargoManifest, selectedStandSlot, helper);
		} finally {
			try {
				persistProjectView(c, ref, data, project, siteStock, cargoManifest);
			} finally {
				try {
					restorePrimaryProject(ref, data);
				} finally {
					ref.dirty();
				}
			}
		}
	}

	/** Per village, at most three live construction cursors may hold crew and reserved materials. */
	static final int MAX_ACTIVE_PROJECTS = 3;
	private static final int ACTIVE_PROJECTS_BEFORE_CREW_GATE = MAX_ACTIVE_PROJECTS - 1;
	private static final int SITE_STOCK_SLOTS = 256;

	/** A third cursor is opened only when three distinct role owners and a real minimum crew can staff it. */
	private static boolean canStartProject(ServerLevel level, VillageRef ref, VillageData data, Villager requester,
			boolean mason) {
		int activeCount = data.activeBuildProjectCount();
		if (!hasActiveProjectSlot(activeCount)) return false;
		if (activeCount < ACTIVE_PROJECTS_BEFORE_CREW_GATE) return true;
		return canStartThirdProject(level, ref, data, requester, mason);
	}

	/** The bound counts only live plans; cleanup-only refunds do not prevent new independent work. */
	static boolean hasActiveProjectSlot(int activeCount) {
		return activeCount >= 0 && activeCount < MAX_ACTIVE_PROJECTS;
	}

	static boolean canStartThirdProject(ServerLevel level, VillageRef ref, VillageData data, Villager requester,
			boolean mason) {
		List<VillageData.BuildProject> active = data.buildProjects.stream().filter(VillageData.BuildProject::active).toList();
		boolean farmerPlan = !mason;
		if (active.size() != ACTIVE_PROJECTS_BEFORE_CREW_GATE || requester == null
			|| !planOwnerRoleMatches(requester, farmerPlan)
			|| !eligiblePlanWorker(level, ref, requester, farmerPlan)) return false;

		List<Villager> residents = villagers(level, ref);
		Map<java.util.UUID, Villager> residentById = new HashMap<>();
		for (Villager resident : residents) residentById.put(resident.getUUID(), resident);
		Set<java.util.UUID> owners = new java.util.HashSet<>();
		int teamProjectCount = 0;
		for (VillageData.BuildProject project : active) {
			java.util.UUID ownerId = project.owner().orElse(null);
			if (ownerId == null || !owners.add(ownerId) || ownerId.equals(requester.getUUID())) return false;
			Villager owner = residentById.get(ownerId);
			if (owner == null && level.getEntity(ownerId) instanceof Villager loadedOwner) owner = loadedOwner;
			boolean ownerFarmerPlan = farmerProject(project.type());
			if (!planOwnerRoleMatches(owner, ownerFarmerPlan)
				|| !availablePlanOwner(level, ref, owner, ownerFarmerPlan)) return false;
			if (teamBuildProject(project)) teamProjectCount++;
		}

		owners.add(requester.getUUID());
		int requiredHelpers = teamProjectCount + (mason ? 1 : 0);
		if (requiredHelpers == 0) return true;
		Set<java.util.UUID> freightCarriers = new java.util.HashSet<>();
		for (VillageData.BuildProject project : data.buildProjects) {
			for (String row : project.cargoManifest()) {
				int separator = row.indexOf('|');
				if (separator <= 0) continue;
				try {
					freightCarriers.add(java.util.UUID.fromString(row.substring(0, separator)));
				} catch (IllegalArgumentException ignored) {
					// Invalid legacy carrier rows are reconciled by the haul flow.
				}
			}
		}
		long idleHelpers = residents.stream()
			.filter(v -> !owners.contains(v.getUUID()) && !freightCarriers.contains(v.getUUID()))
			.filter(v -> eligibleBuildHelper(level, ref, v))
			.count();
		return idleHelpers >= requiredHelpers;
	}

	private static boolean planOwnerRoleMatches(Villager villager, boolean farmerPlan) {
		if (villager == null) return false;
		if (farmerPlan) {
			return villager.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER);
		}
		return exactMason(villager) || compatibleMason(villager);
	}

	/** Run the existing single-cursor builder against the selected project's temporary legacy view. */
	private static boolean tickProject(Ctx c, boolean mason, Container siteStock, List<String> cargoManifest,
			int standSlot, boolean helper) {
		VillageRef ref = c.village();
		if (ref == null) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "builder-skipped", "no village assignment");
			return false;
		}
		if (!helper && mason && de.villagerplus.village.Demolition.work(c, ref)) {
			return true;
		}
		// Gebaut wird aus Dorfkiste UND Lager (gemeinsam gezaehlt und bezahlt).
		Container box = Lager.combined(c.level(), ref);
		if (box == null) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "builder-skipped", "village storage unavailable");
			return false;
		}
		VillageData d = ref.data();
		if (de.villagerplus.dev.G02SupplyTrace.enabled()) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "builder-enter", "mason=" + mason + "; plan=" + d.planType
				+ "; nextSpecial=" + nextSpecial(ref, villagers(c.level(), ref).size()));
		}
		// Descend to the village street before judging plots from a bell tower.
		// Keep the real worker in place: navigation, rather than a preview teleport,
		// must prove that the street and the eventual construction stand are reachable.
		boolean needsStreetBootstrap = !d.hasPlan();
		if (!helper && mason && needsStreetBootstrap) {
			BlockPos street = villageReturn(c.level(), ref.bell());
			if (shouldApproachVillageStreet(mason, d.hasPlan(), c.v().blockPosition(), street)) {
				JobUtil.approachLong(c, street, 1.0);
				c.setActivity("geht vom Glockenturm zur Dorfstrasse");
				return true;
			}
		}
		if (!helper && mason && c.v().getY() < villageGroundY(c.level(), ref.bell()) - 12 && !de.villagerplus.job.Woodcutting.onWoodExpedition(c)) {
			// A cave bed must not keep the mason underground: pathing from there never reaches a street stand.
			if (c.mem().homeBed.isPresent() && c.mem().homeBed.get().getY() < villageGroundY(c.level(), ref.bell()) - 12) {
				c.mem().homeBed = Optional.empty();
			}
			c.v().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
			BlockPos feet = villageReturn(c.level(), ref.bell());
			if (feet == null) return true;
			LOGGER.info("Villagers: Repurposed: Baumeister {} lag unter dem Dorf und wurde nach {} geholt",
				c.v().blockPosition().toShortString(), feet.toShortString());
			c.v().teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
			c.st().builderRelocations++;
			c.v().resetFallDistance();
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			c.st().miningUntil = c.now();
			return true;
		}
		if (d.hasPlan()) {
			boolean farmerPlan = d.planType.equals(Blueprints.FARM) || d.planType.equals(Blueprints.PEN);
			if (mason == farmerPlan) return false;
			if (helper ? farmerPlan : !ownsActivePlan(c, ref, farmerPlan)) return false;
			ConstructionSite helperSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			if (helper && (d.planType.equals(Blueprints.WALL) || helperSite == null
				|| !helperSite.origin().equals(d.planOrigin.orElseThrow()))) return false;
			boolean setupHaulOnly = helper && !helperSite.prepared();
			List<Villager> currentResidents = !helper && mason ? villagers(c.level(), ref) : List.of();
			String pendingSpecial = !helper && mason ? nextSpecial(ref, currentResidents.size()) : null;
			// A house whose persisted cursor has reached the end is waiting only for its
			// street connection. Do not abort it for a stale progress timer, preempt it
			// for a new milestone, request materials, or navigate back to a work stand.
			if (!helper && d.planType.equals(Blueprints.HOUSE) && !REPAIRS.contains(d.planOrigin.orElseThrow().asLong())) {
				BlockPos house = d.planOrigin.orElseThrow();
				List<Step> houseSteps = stepsFor(c.level(), ref, house, Direction.from2DDataValue(d.planDir), c);
				if (houseSteps != null && d.planStep >= houseSteps.size()) {
					if (c.now() >= c.st().buildCheckAt) finish(c, ref, house);
					else c.setActivity("wartet auf einen sicheren Weg zur Dorfstrasse");
					return true;
				}
			}
			boolean fullStoreNeedsExpansion = !helper && mason && d.planStep == 0 && !d.planType.equals(Blueprints.STORAGE)
				&& !JobUtil.hasEmptySlot(box) && Development.needsStorage(box, d, c.level()) && storageFree(c, ref);
			boolean firstPublicStorageMissing = firstPublicStorageMissing(c.level(), d);
			boolean needHouse = false;
			if (fullStoreNeedsExpansion && firstPublicStorageMissing) {
				long homeless = currentResidents.stream().filter(v -> !Housing.hasHome(v)).count();
				long homes = c.level().getPoiManager().getCountInRange(h -> h.is(PoiTypes.HOME), ref.bell(), Config.VILLAGE_RADIUS,
					PoiManager.Occupancy.ANY);
				needHouse = needsHouse(currentResidents.size(), homeless, homes);
			}
			if (!helper && shouldExpandStorageUnderPressure(d.planType, pendingSpecial, fullStoreNeedsExpansion,
				firstPublicStorageMissing, needHouse)) {
				// The first shed relieves pressure after the granary; extra sheds keep the existing civic priority.
				abort(d, ref, c.now());
				d.nextBuild = c.now();
				ref.dirty();
				return false;
			}
			if (!helper && c.now() - d.planProgress > STUCK_TICKS && d.wanted.isEmpty()) {
				// Haengender Auftrag (Platz nicht erreichbar, Chunk entladen ...): aufgeben.
				abort(d, ref, c.now());
				return false;
			}
			if (!helper && mason) {
				String special = pendingSpecial;
				if (shouldPreemptForSpecial(d.planType, special)) {
					abort(d, ref, c.now());
					return false;
				}
			}
			List<Step> preparedSteps = null;
			if (!d.planType.equals(Blueprints.WALL)) {
				BlockPos origin = d.planOrigin.orElseThrow();
				Direction dir = Direction.from2DDataValue(d.planDir);
				preparedSteps = stepsFor(c.level(), ref, origin, dir, c);
				if (helper && preparedSteps == null) return false;
				if (!helper && d.planType.equals(Blueprints.FARM) && preparedSteps != null
					&& !stageFarmSeeds(box, siteStock, preparedSteps, d.planStep, c.level())) {
					c.setActivity("wartet auf bezahlbares Saatgut für das Feld");
					return false;
				}
				if (preparedSteps != null && !d.planType.equals(Blueprints.FARM)) {
					boolean siteReady = helper && !setupHaulOnly
						? nextPlacementHasSiteMaterials(c, ref, preparedSteps, origin, d.planStep, siteStock)
						: updateWanted(c, ref, d.planType, preparedSteps, box, siteStock, cargoManifest, origin, d.planStep, false);
					if (setupHaulOnly) {
						BlockPos deliveryStand = workStand(c, origin, dir, preparedSteps,
							villageGroundY(c.level(), ref.bell()) - 12);
						boolean hauled = false;
						if (deliveryStand != null && (!d.wanted.isEmpty() || !cargoManifest.isEmpty())) {
							List<String> priorManifest = List.copyOf(cargoManifest);
							try {
								hauled = de.villagerplus.job.BuildHaul.tick(c, origin, deliveryStand,
									List.copyOf(d.wanted), true, siteStock);
							} finally {
								List<String> reconciled = de.villagerplus.job.BuildHaul.manifest(c, origin, priorManifest);
								cargoManifest.clear();
								cargoManifest.addAll(reconciled);
							}
						}
						if (hauled) {
							updateWanted(c, ref, d.planType, preparedSteps, box, siteStock, cargoManifest, origin, d.planStep, false);
							c.setActivity("baut: " + c.st().activity);
							return true;
						}
						c.setActivity(d.wanted.isEmpty() ? "wartet auf Baustellenausstattung"
							: "wartet auf Gerüst- und Werkbankmaterial");
						return false;
					}
					if (!helper && (!siteReady || !cargoManifest.isEmpty())) {
						BlockPos deliveryStand = workStand(c, origin, dir, preparedSteps, villageGroundY(c.level(), ref.bell()) - 12);
						if (deliveryStand != null) {
							List<String> priorManifest = List.copyOf(cargoManifest);
							boolean hauled;
							try {
								hauled = de.villagerplus.job.BuildHaul.tick(c, origin, deliveryStand, List.copyOf(d.wanted), true, siteStock);
							} finally {
								List<String> reconciled = de.villagerplus.job.BuildHaul.manifest(c, origin, priorManifest);
								cargoManifest.clear();
								cargoManifest.addAll(reconciled);
							}
							if (hauled) {
								List<String> wantedBeforeHaul = List.copyOf(d.wanted);
								updateWanted(c, ref, d.planType, preparedSteps, box, siteStock, cargoManifest, origin, d.planStep, false);
								if (!wantedBeforeHaul.equals(d.wanted)) d.planProgress = c.now();
								// The legacy board only recognizes construction activities; hauling is real work on this plan too.
								String haulActivity = c.st().activity;
								c.setActivity("baut: " + haulActivity);
								return true;
							}
						}
						PublicStockStage stage = stageSpecialBuildingStock(c, ref, d.wanted);
						if (stage == PublicStockStage.MOVED) {
							d.planProgress = c.now();
							ref.dirty();
							c.setActivity("bringt öffentliches Baumaterial zum Dorflager");
							return true;
						}
						if (stage == PublicStockStage.SCANNING) {
							c.setActivity("sucht öffentliches Baumaterial für die Baustelle");
							return true;
						}
					}
					if (!siteReady) {
						if (helper) {
							BlockPos deliveryStand = workStand(c, origin, dir, preparedSteps,
								villageGroundY(c.level(), ref.bell()) - 12);
							if (deliveryStand != null) {
								// Helpers participate in the physical supply chain when the next block
								// cannot be placed. Refresh demand before claiming public stock; BuildHaul
								// reserves the real inventory and delivers it using ordinary navigation.
								List<String> wantedBeforeHaul = List.copyOf(d.wanted);
								updateWanted(c, ref, d.planType, preparedSteps, box, siteStock, cargoManifest,
									origin, d.planStep, false);
								List<String> priorManifest = List.copyOf(cargoManifest);
								boolean hauled;
								try {
									hauled = de.villagerplus.job.BuildHaul.tick(c, origin, deliveryStand,
										List.copyOf(d.wanted), true, siteStock);
								} finally {
									List<String> reconciled = de.villagerplus.job.BuildHaul.manifest(c, origin, priorManifest);
									cargoManifest.clear();
									cargoManifest.addAll(reconciled);
								}
								if (hauled) {
									updateWanted(c, ref, d.planType, preparedSteps, box, siteStock,
										cargoManifest, origin, d.planStep, false);
									if (!wantedBeforeHaul.equals(d.wanted)) d.planProgress = c.now();
									String haulActivity = c.st().activity;
									c.setActivity("baut: " + haulActivity);
									return true;
								}
							}
							c.setActivity("wartet auf Material am Baustellendepot");
							return false;
						}
						// The plan owner stays on its real supplier cycle while a milestone is short on materials.
						// In particular, do not path back to the construction stand between tree-felling ticks.
						boolean supplierBusy = supplyMissing(c);
						// Stall = same step and same open demand for a long time, even while a supplier keeps looking busy
						// (e.g. endlessly searching a tree that never yields what the plan needs).
						long stallKey = d.planOrigin.map(BlockPos::asLong).orElse(0L);
						if (!helper) {
							String stallSignature = d.planStep + "|" + String.join(",", d.wanted);
							PlanStall stall = PLAN_STALLS.get(stallKey);
							if (stall == null || !stall.signature().equals(stallSignature)) {
								PLAN_STALLS.put(stallKey, new PlanStall(stallSignature, c.now()));
							} else if (c.now() - stall.since() >= STALLED_PLAN_PALETTE_FALLBACK_TICKS) {
								LOGGER.info("Villagers: Repurposed: Bauplan {} steht seit {} Ticks still, pruefe Ersatzpalette (Variante {}, Stämme in Kiste {}, Planbesitzer {}, aktueller Arbeiter '{}' ({}), Tätigkeit '{}', Ofen nötig={}, Bruchstein nötig={}, Kiste Bruchstein/Stein/Ziegel/Kohle={}/{}/{}/{}, fehlt: {})", d.planType, c.now() - stall.since(), d.planVariant, JobUtil.count(box, stk -> stk.is(net.minecraft.tags.ItemTags.LOGS)), d.planBuilder().map(id -> c.level().getEntity(id) instanceof Villager pv ? (pv.getName().getString() + " @ " + pv.blockPosition().toShortString()) : "nicht geladen/tot").orElse("keiner"), c.mem().name, c.v().blockPosition().toShortString(), c.st().activity, de.villagerplus.job.PlanStoneSupply.needsFurnace(c.level(), d, box), de.villagerplus.job.PlanStoneSupply.needsCobble(c.level(), d, box), JobUtil.count(box, stk -> stk.is(Items.COBBLESTONE)), JobUtil.count(box, stk -> stk.is(Items.STONE)), JobUtil.count(box, stk -> stk.is(Items.STONE_BRICKS)), JobUtil.count(box, stk -> stk.is(Items.COAL) || stk.is(Items.CHARCOAL)), PlanSupplyOrder.shortages(d.wanted).entrySet().stream().limit(8).map(e -> BuiltInRegistries.ITEM.getKey(e.getKey()) + "x" + e.getValue() + "/Kiste" + JobUtil.count(box, stk -> stk.is(e.getKey()))).toList());
								List<Step> diagSteps = STEPS.getOrDefault(stallKey, List.of());
								LOGGER.info("Villagers: Repurposed: Plandiagnose {}: Quelle={}, Variante={}, Stem='{}', Schritte={}, bezahlt ab Cursor={}, Wunschliste={}", d.planType,
									d.planSource.isPresent(), d.planVariant, de.villagerplus.village.VillageAdmin.woodStem(ref), diagSteps.size(),
									diagSteps.stream().skip(Math.max(0, d.planStep)).filter(st -> !st.free() && !st.optional() && st.linkedTo() == null && st.cost() > 0).count(),
									d.wanted);
								LOGGER.info("Villagers: Repurposed: Planstand {}: Schritt {}/{} bei {}", d.planType, d.planStep,
									STEPS.getOrDefault(stallKey, List.of()).size(), d.planOrigin.map(BlockPos::toShortString).orElse("-"));
								PLAN_STALLS.put(stallKey, new PlanStall(stallSignature, c.now()));
								// Site scaffolding is convenience equipment: when it never arrives, build without it instead of freezing the plan.
								waiveStalledScaffolding(ref, box);
								// R02: no supplier or caravan delivered the unobtainable material, so the plan falls back to the local palette itself.
								if (trySwitchToObtainablePlanFallback(c.level(), ref, box, true)) {
									LOGGER.info("Villagers: Repurposed: Bauplan {} wechselt nach langem Stillstand auf eine beschaffbare Ersatzpalette", d.planType);
									PLAN_STALLS.remove(stallKey);
									d.planProgress = c.now();
									ref.dirty();
									supplierBusy = true;
								} else if (trySwitchToRusticStone(c.level(), ref) || trySwitchToLocalWood(c.level(), ref) || trySwitchToStoneForWood(c.level(), ref)) {
									PLAN_STALLS.remove(stallKey);
									d.planProgress = c.now();
									ref.dirty();
									supplierBusy = true;
								} else if (WOODLESS_STEM.equals(de.villagerplus.village.VillageAdmin.woodStem(ref)) || de.villagerplus.village.VillageAdmin.rusticStone(ref)) {
									// No switch applied: a village that builds without wood or with rustic stone must not keep wishes from an older plan version.
									// Rebuild the steps with the current material rules and drop the stale wish list (the cursor index stays valid).
									STEPS.remove(stallKey);
									d.wanted.clear();
									ref.dirty();
									LOGGER.info("Villagers: Repurposed: Bauplan {} wird mit den aktuellen Materialregeln neu aufgebaut (veraltete Wunschliste)", d.planType);
								}
							}
						}
						if (!supplierBusy) {
							c.setActivity(de.villagerplus.job.Woodcutting.planNeedsWood(d)
								? "wartet auf passendes Bauholz" : "wartet auf Baumaterial für den Bauplan");
						}
						// Let JobManager run the villager's normal profession cycle when no plan supplier
						// could advance this shortage. Returning busy here strands bootstrap villages with
						// no source operation and prevents the resident from gathering the requested input.
						return supplierBusy;
					}
				}
			}
			boolean result = d.planType.equals(Blueprints.WALL) ? continueWall(c, ref, box)
				: continuePlan(c, ref, box, siteStock, cargoManifest, preparedSteps, standSlot, helper);
			if (!helper && d.hasPlan() && preparedSteps != null && !d.planType.equals(Blueprints.FARM)) {
				updateWanted(c, ref, d.planType, preparedSteps, box, siteStock, cargoManifest, d.planOrigin.orElseThrow(), d.planStep, false);
			}
			if (c.now() - lastDiag > 1200 && d.planStep == 0) {
				lastDiag = c.now();
				LOGGER.info("Villagers: Repurposed: Bau '{}' Schritt {} ({}) - Ergebnis {}, Wunschliste {}", d.planType, d.planStep, why, result, d.wanted);
			}
			// A saved material request must release the work cycle so the villager's
			// profession can mine, harvest or craft the missing supplies.
			return helper ? result : result || (d.wanted.isEmpty() && holdForSpecial(c, ref, mason));
		}
		if (helper) return false;
		if (!d.wanted.isEmpty()) {
			// Ohne Bauauftrag gibt es keine Wuensche mehr (alte Eintraege lassen sonst Bergleute und Haendler ins Leere arbeiten).
			d.wanted.clear();
			ref.dirty();
		}
		if (c.now() < c.st().buildCheckAt) {
			return holdForSpecial(c, ref, mason);
		}
		c.st().buildCheckAt = c.now() + 600;
		// Ausbessern hat Vorrang vor Neubauten (sonst kaeme es in einem wachsenden Dorf nie dazu); gepruft wird nur alle 3000 Ticks.
		if (mason && c.now() >= REPAIR_AT.getOrDefault(ref.bell().asLong(), 0L)) {
			REPAIR_AT.put(ref.bell().asLong(), c.now() + 3000L);
			if (startRepair(c, ref)) {
				return true;
			}
		}
		boolean started = startPlan(c, ref, box, mason, siteStock, cargoManifest);
		if (de.villagerplus.dev.G02SupplyTrace.enabled()) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "builder-plan-result", "started=" + started + "; plan=" + d.planType
				+ "; nextSpecial=" + nextSpecial(ref, villagers(c.level(), ref).size())
				+ (lastStandReport(c.v()).isEmpty() ? "" : "; stand=" + lastStandReport(c.v())));
		}
		return started || holdForSpecial(c, ref, mason);
	}

	private static void migrateLegacyProjects(Ctx c, VillageRef ref, VillageData data) {
		if (data.buildProjectVersion > 0) return;
		List<String> legacyWanted = List.copyOf(data.wanted);
		List<String> projectWanted = de.villagerplus.village.Market.migrateLegacyWants(c, legacyWanted);
		if (data.hasPlan()) {
			BlockPos origin = data.planOrigin.orElseThrow();
			ConstructionSite attached = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			Optional<ConstructionSite> site = attached != null && attached.origin().equals(origin) ? Optional.of(attached) : Optional.empty();
			boolean repair = builtOf(ref).stream().anyMatch(b -> b.origin().equals(origin) && b.type().equals(data.planType))
				|| data.planType.equals(Blueprints.WALL) && data.wallBuilt;
			VillageData.BuildProject migrated = new VillageData.BuildProject(data.planType, origin, data.planDir,
				data.planStep, data.planVariant, data.planSource, data.planProgress, data.planBuilder(),
				data.planBuilderUnavailableSince(), projectWanted, VillageData.ProjectHaul.empty(), data.extra.planMaterialWait, site,
				Optional.of(fallbackProjectBounds(data.planType, origin, data.planVariant, data.planSource.isPresent())), repair, false);
			data.putBuildProject(migrated);
		} else if (!projectWanted.isEmpty()) {
			// Orphaned legacy requests have no resumable plan; do not leave suppliers servicing them forever.
			data.wanted.clear();
		}
		data.buildProjectVersion = 1;
		data.syncPrimaryProjectView();
		ref.dirty();
	}

	private static VillageData.BuildProject selectProjectForWorker(Ctx c, VillageRef ref, VillageData data, boolean mason) {
		List<VillageData.BuildProject> active = data.buildProjects.stream().filter(VillageData.BuildProject::active).toList();
		java.util.UUID caller = c.v().getUUID();
		VillageData.BuildProject owned = ownedProjectForWorker(active, caller);
		if (owned != null) return owned;
		VillageData.BuildProject unowned = unownedProjectForWorker(active, mason);
		if (unowned != null) return unowned;
		for (VillageData.BuildProject project : active) {
			if (farmerProject(project.type()) == mason || project.owner().isEmpty()) continue;
			// A marked load belongs to its carrier. Keep that owner pinned until the carrier returns or dies;
			// BuildHaul's death listener releases a dead carrier's rows before replacement is allowed.
			if (!project.cargoManifest().isEmpty()) continue;
			Entity owner = c.level().getEntity(project.owner().orElseThrow());
			if (!(owner instanceof Villager villager) || !availablePlanOwner(c.level(), ref, villager,
				farmerProject(project.type()))) return project;
		}
		return null;
	}

	/** Retry an active cursor whose saved owner is absent or cannot reach its reserved work area. */
	private static void retryUnstaffedProjectOwner(Ctx c, VillageRef ref, VillageData data, Container publicStock) {
		if (c == null || ref == null || data == null) return;
		List<VillageData.BuildProject> active = data.buildProjects.stream()
			.filter(VillageData.BuildProject::active)
			.filter(project -> !project.cleanupOnly() && !project.type().isEmpty())
			.toList();
		long now = c.now();
		long villageBell = ref.bell().asLong();
		Map<BuildProjectRetryKey, Long> retryAt = BUILD_PROJECT_STAFFING_RETRIES.computeIfAbsent(c.level(), ignored -> new HashMap<>());
		Set<BuildProjectRetryKey> activeKeys = active.stream()
			.map(project -> new BuildProjectRetryKey(villageBell, project.origin().asLong()))
			.collect(Collectors.toSet());
		retryAt.keySet().removeIf(key -> key.villageBell() == villageBell && !activeKeys.contains(key));
		if (active.isEmpty()) return;
		List<VillageData.BuildProject> dueProjects = active.stream()
			.filter(project -> shouldRetryProjectStaffing(project.active(), !project.cargoManifest().isEmpty(), now,
				retryAt.getOrDefault(new BuildProjectRetryKey(villageBell, project.origin().asLong()), Long.MIN_VALUE)))
			.toList();
		if (dueProjects.isEmpty()) return;

		Set<UUID> reservedWorkers = active.stream().flatMap(project -> project.owner().stream())
			.collect(Collectors.toSet());
		for (VillageData.BuildProject project : data.buildProjects) {
			for (String row : project.cargoManifest()) {
				int separator = row.indexOf('|');
				if (separator <= 0) continue;
				try {
					reservedWorkers.add(UUID.fromString(row.substring(0, separator)));
				} catch (IllegalArgumentException ignored) {
					// The haul reconciler removes malformed legacy rows; they cannot reserve a worker here.
				}
			}
		}
		Map<UUID, BuildCrewAssignment> currentAssignments = currentBuildCrewAssignments(c.level(), ref, now);
		reservedWorkers.addAll(currentAssignments.keySet());
		List<Villager> residents = villagers(c.level(), ref);

		for (VillageData.BuildProject original : dueProjects) {
			BuildProjectRetryKey retryKey = new BuildProjectRetryKey(villageBell, original.origin().asLong());
			retryAt.put(retryKey, now + PROJECT_STAFFING_RETRY_TICKS);

			VillageData.BuildProject project = original;
			Villager owner = project.owner().map(c.level()::getEntity)
				.filter(Villager.class::isInstance).map(Villager.class::cast).orElse(null);
			boolean ownerReachable = owner != null
				&& availablePlanOwner(c.level(), ref, owner, farmerProject(project.type()))
				&& reachableProjectStand(c.level(), owner, project);
			if (ownerReachable) {
				if (project.ownerUnavailableSince() != 0L) {
					VillageData.BuildProject recovered = withProjectOwner(project, project.owner().orElseThrow(), 0L);
					data.putBuildProject(recovered);
					data.syncPrimaryProjectView();
					ref.dirty();
					invalidateBuildCrewAssignments(c.level(), ref);
				}
				continue;
			}

			if (project.owner().isPresent()) {
				long unavailableSince = project.ownerUnavailableSince();
				if (unavailableSince == 0L) {
					VillageData.BuildProject marked = withProjectOwner(project, project.owner().orElseThrow(), Math.max(1L, now));
					data.putBuildProject(marked);
					data.syncPrimaryProjectView();
					ref.dirty();
					invalidateBuildCrewAssignments(c.level(), ref);
					continue;
				}
				if (now - unavailableSince < PLAN_OWNER_GRACE) continue;
			}

			if (!hasPublicProjectMaterials(project, publicStock)) continue;
			List<Villager> candidates = residents.stream()
				.filter(v -> eligibleBuildHelper(c.level(), ref, v) && projectRoleMatches(v, project))
				.filter(v -> !reservedWorkers.contains(v.getUUID()))
				.sorted(Comparator.comparing((Villager v) -> !compatibleMason(v))
					.thenComparing(v -> !unemployedBuildCarrier(v))
					.thenComparing(v -> v.getUUID().toString()))
				.toList();
			if (candidates.isEmpty()) continue;

			int windows = Math.max(1, (candidates.size() + MAX_PROJECT_STAFFING_CANDIDATES - 1)
				/ MAX_PROJECT_STAFFING_CANDIDATES);
			int window = (int) Math.floorMod(now / PROJECT_STAFFING_RETRY_TICKS, (long) windows);
			int first = window * MAX_PROJECT_STAFFING_CANDIDATES;
			int end = Math.min(candidates.size(), first + MAX_PROJECT_STAFFING_CANDIDATES);
			for (int index = first; index < end; index++) {
				Villager candidate = candidates.get(index);
				if (!shouldAssignProjectWorker(eligibleBuildHelper(c.level(), ref, candidate),
					reachableProjectStand(c.level(), candidate, project),
					reservedWorkers.contains(candidate.getUUID()))) continue;
				VillageData.BuildProject reassigned = withProjectOwner(project, candidate.getUUID(), 0L);
				data.putBuildProject(reassigned);
				data.syncPrimaryProjectView();
				ref.dirty();
				reservedWorkers.add(candidate.getUUID());
				invalidateBuildCrewAssignments(c.level(), ref);
				LOGGER.info("Villagers: Repurposed: Bau '{}' bei {} bekommt nach begrenzter Crew-Suche Arbeiter {}",
					project.type(), project.origin().toShortString(), candidate.blockPosition().toShortString());
				break;
			}
		}
	}

	static boolean shouldRetryProjectStaffing(boolean active, boolean carrierReserved, long now, long retryAt) {
		return active && !carrierReserved && now >= retryAt;
	}

	static boolean shouldAssignProjectWorker(boolean eligible, boolean reachable, boolean alreadyAssigned) {
		return eligible && reachable && !alreadyAssigned;
	}

	private static boolean projectRoleMatches(Villager villager, VillageData.BuildProject project) {
		return villager != null && project != null
			&& (!farmerProject(project.type()) || villager.getVillagerData().profession()
				.is(net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER));
	}

	static boolean hasPublicProjectMaterials(VillageData.BuildProject project, Container publicStock) {
		if (project == null) return false;
		if (project.siteStock().stream().anyMatch(stack -> stack != null && !stack.isEmpty())) return true;
		if (publicStock == null) return false;
		Map<Item, Integer> wanted = de.villagerplus.build.PlanSupplyOrder.shortages(project.wanted());
		if (!wanted.isEmpty()) {
			return wanted.keySet().stream().anyMatch(item -> JobUtil.count(publicStock, stack -> stack.is(item)) > 0);
		}
		for (String row : project.wanted()) {
			if (row == null) continue;
			Identifier itemId = Identifier.tryParse(row.split("#", 2)[0]);
			Item item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
			if (item != null && JobUtil.count(publicStock, stack -> stack.is(item)) > 0) return true;
		}
		return project.wanted().isEmpty() && hasPublicBuildingMaterial(publicStock);
	}

	private static boolean hasPublicBuildingMaterial(Container stock) {
		if (stock == null) return false;
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			ItemStack stack = stock.getItem(slot);
			if (stack != null && !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) return true;
		}
		return false;
	}

	private static boolean reachableProjectStand(ServerLevel level, Villager villager, VillageData.BuildProject project) {
		if (level == null || villager == null || project == null || !level.hasChunkAt(project.origin())) return false;
		VillageData.PlanBounds bounds = project.bounds().orElseGet(() ->
			fallbackProjectBounds(project.type(), project.origin(), project.variant(), project.source().isPresent()));
		int midX = bounds.minX() + (bounds.maxX() - bounds.minX()) / 2;
		int midZ = bounds.minZ() + (bounds.maxZ() - bounds.minZ()) / 2;
		int y = project.origin().getY();
		List<BlockPos> standColumns = List.of(
			new BlockPos(midX, y, bounds.minZ() - 3), new BlockPos(midX, y, bounds.maxZ() + 3),
			new BlockPos(bounds.minX() - 3, y, midZ), new BlockPos(bounds.maxX() + 3, y, midZ),
			new BlockPos(bounds.minX() - 4, y, bounds.minZ() - 4), new BlockPos(bounds.maxX() + 4, y, bounds.minZ() - 4),
			new BlockPos(bounds.minX() - 4, y, bounds.maxZ() + 4), new BlockPos(bounds.maxX() + 4, y, bounds.maxZ() + 4));
		for (int index = 0; index < Math.min(MAX_PROJECT_STAFFING_STAND_PROBES, standColumns.size()); index++) {
			BlockPos column = standColumns.get(index);
			if (!level.hasChunkAt(column)) continue;
			BlockPos feet = standOnColumn(level, column.getX(), column.getZ(), y);
			if (feet == null || feet.getX() >= bounds.minX() && feet.getX() <= bounds.maxX()
				&& feet.getZ() >= bounds.minZ() && feet.getZ() <= bounds.maxZ()) continue;
			if (villager.blockPosition().distSqr(feet) <= 36.0) return true;
			var path = villager.getNavigation().createPath(feet, 0);
			if (path != null && path.canReach()) return true;
		}
		return false;
	}

	private static VillageData.BuildProject withProjectOwner(VillageData.BuildProject project, UUID owner,
			long unavailableSince) {
		return new VillageData.BuildProject(project.type(), project.origin(), project.dir(), project.step(),
			project.variant(), project.source(), project.progress(), Optional.ofNullable(owner), unavailableSince,
			project.wanted(), project.haul(), project.materialWait(), project.construction(), project.bounds(),
			project.repair(), project.cleanupOnly());
	}

	private static Map<UUID, BuildCrewAssignment> currentBuildCrewAssignments(ServerLevel level, VillageRef ref, long now) {
		Map<Long, BuildCrewSnapshot> snapshots = BUILD_CREW_SNAPSHOTS.get(level);
		BuildCrewSnapshot snapshot = snapshots == null || ref == null ? null : snapshots.get(ref.bell().asLong());
		return snapshot != null && snapshot.gameTime() == now ? snapshot.assignments() : Map.of();
	}

	private static void invalidateBuildCrewAssignments(ServerLevel level, VillageRef ref) {
		Map<Long, BuildCrewSnapshot> snapshots = BUILD_CREW_SNAPSHOTS.get(level);
		if (snapshots != null && ref != null) snapshots.remove(ref.bell().asLong());
	}

	/** Resume a resident's own cursor before considering takeover or opening another project slot. */
	static VillageData.BuildProject ownedProjectForWorker(List<VillageData.BuildProject> projects, UUID worker) {
		if (projects == null || worker == null) return null;
		for (VillageData.BuildProject project : projects) {
			if (project != null && project.active() && project.owner().filter(worker::equals).isPresent()) return project;
		}
		return null;
	}

	/** Only an ownerless cursor for this worker's role may be claimed from the shared village queue. */
	static VillageData.BuildProject unownedProjectForWorker(List<VillageData.BuildProject> projects, boolean mason) {
		if (projects == null) return null;
		boolean farmerLane = !mason;
		for (VillageData.BuildProject project : projects) {
			if (project != null && project.active() && farmerProject(project.type()) == farmerLane && project.owner().isEmpty()) {
				return project;
			}
		}
		return null;
	}

	private static void reconcileProjectManifests(Ctx c, VillageRef ref, VillageData data) {
		boolean changed = false;
		for (int i = 0; i < data.buildProjects.size(); i++) {
			VillageData.BuildProject project = data.buildProjects.get(i);
			if (!project.active() || project.cargoManifest().isEmpty()) continue;
			List<String> manifest = de.villagerplus.job.BuildHaul.manifest(c, project.origin(), project.cargoManifest());
			if (manifest.equals(project.cargoManifest())) continue;
			data.buildProjects.set(i, new VillageData.BuildProject(project.type(), project.origin(), project.dir(), project.step(),
				project.variant(), project.source(), project.progress(), project.owner(), project.ownerUnavailableSince(), project.wanted(),
				new VillageData.ProjectHaul(project.siteStock(), manifest), project.materialWait(), project.construction(),
				project.bounds(), project.repair(), project.cleanupOnly()));
			changed = true;
		}
		if (changed) {
			data.refreshWanted();
			ref.dirty();
		}
	}

	private static boolean farmerProject(String type) {
		return Blueprints.FARM.equals(type) || Blueprints.PEN.equals(type);
	}

	private static void loadProjectView(VillageRef ref, VillageData data, VillageData.BuildProject project) {
		data.planType = project.type();
		data.planOrigin = Optional.of(project.origin());
		data.planDir = project.dir();
		data.planStep = project.step();
		data.planVariant = project.variant();
		data.planSource = project.source();
		data.planProgress = project.progress();
		data.extra.planBuilder = project.owner().map(owner -> new VillageData.Extra.BuilderPlan(Optional.of(owner), project.ownerUnavailableSince()));
		data.extra.planMaterialWait = project.materialWait();
		data.wanted.clear();
		data.wanted.addAll(project.wanted());
		if (project.repair()) {
			if (project.type().equals(Blueprints.WALL)) REPAIRS.add(project.origin().asLong() ^ WALL_KEY);
			else REPAIRS.add(project.origin().asLong());
		}
		ConstructionSite site = project.construction().orElse(null);
		if (site == null) ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		else ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, site);
	}

	private static Container siteStock(VillageData.BuildProject project) {
		net.minecraft.world.SimpleContainer stock = new net.minecraft.world.SimpleContainer(Math.max(SITE_STOCK_SLOTS, project.siteStock().size()));
		for (int i = 0; i < project.siteStock().size(); i++) stock.setItem(i, project.siteStock().get(i).copy());
		return stock;
	}

	private static List<ItemStack> siteStockRows(Container stock) {
		List<ItemStack> rows = new ArrayList<>();
		if (stock == null) return List.of();
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			ItemStack stack = stock.getItem(slot);
			if (!stack.isEmpty()) rows.add(stack.copy());
		}
		return List.copyOf(rows);
	}

	private static boolean containerEmpty(Container stock) {
		if (stock == null) return true;
		for (int slot = 0; slot < stock.getContainerSize(); slot++) if (!stock.getItem(slot).isEmpty()) return false;
		return true;
	}

	private static void persistProjectView(Ctx c, VillageRef ref, VillageData data, VillageData.BuildProject selected,
			Container siteStock, List<String> cargoManifest) {
		persistProjectView(c, ref, data, selected, siteStock, cargoManifest, null);
	}

	private static void persistProjectView(Ctx c, VillageRef ref, VillageData data, VillageData.BuildProject selected,
			Container siteStock, List<String> cargoManifest, VillageData.PlanBounds lotBoundsOverride) {
		boolean selectedOwnsLegacyView = data.hasPlan() && (selected == null
			|| data.planOrigin.filter(selected.origin()::equals).isPresent());
		if (selectedOwnsLegacyView) {
			BlockPos origin = data.planOrigin.orElseThrow();
			VillageData.BuildProject previous = data.buildProject(origin).orElse(selected);
			ConstructionSite attached = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			Optional<ConstructionSite> site = attached != null && attached.origin().equals(origin)
				? Optional.of(attached) : previous == null ? Optional.empty() : previous.construction();
			List<Step> steps = STEPS.get(origin.asLong());
			boolean preparedHouse = needsNaturalHousePreparation(data.planType, data.planVariant, data.planSource.isPresent());
			Optional<VillageData.PlanBounds> bounds;
			if (lotBoundsOverride != null && preparedHouse) {
				bounds = Optional.of(lotBoundsOverride);
			} else if (steps == null || steps.isEmpty()) {
				bounds = previous == null ? Optional.of(fallbackProjectBounds(data.planType, origin, data.planVariant, data.planSource.isPresent())) : previous.bounds();
			} else if (preparedHouse && previous != null && previous.bounds().isPresent()) {
				// Do not recalculate a saved lot during normal progress or after reload; that would move its claim.
				bounds = previous.bounds();
			} else if (preparedHouse) {
				bounds = Optional.ofNullable(fittingHouseLotBounds(ref, origin, steps, data.planVariant, data.planSource.isPresent()));
			} else {
				bounds = Optional.of(boundsOf(steps));
			}
			boolean repair = REPAIRS.contains(origin.asLong()) || REPAIRS.contains(origin.asLong() ^ WALL_KEY)
				|| previous != null && previous.repair();
			List<String> savedManifest = List.copyOf(cargoManifest);
			VillageData.BuildProject saved = new VillageData.BuildProject(data.planType, origin, data.planDir, data.planStep,
				data.planVariant, data.planSource, data.planProgress, data.planBuilder(), data.planBuilderUnavailableSince(),
				List.copyOf(data.wanted), new VillageData.ProjectHaul(siteStockRows(siteStock), savedManifest),
				data.extra.planMaterialWait, site, bounds, repair, false);
			data.putBuildProject(saved);
		} else {
			if (selected != null && data.buildProject(selected.origin()).filter(VillageData.BuildProject::active).isPresent()) {
				data.removeBuildProject(selected.origin());
			}
			ConstructionSite attached = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			BlockPos cleanupOrigin = selected != null ? selected.origin() : attached == null ? null : attached.origin();
			VillageData.BuildProject previousCleanup = cleanupOrigin == null ? null
				: data.buildProject(cleanupOrigin).filter(VillageData.BuildProject::cleanupOnly).orElse(null);
			ConstructionSite site = attached != null && attached.origin().equals(cleanupOrigin) ? attached
				: previousCleanup == null ? null : previousCleanup.construction().orElse(null);
			List<String> savedManifest = cleanupOrigin == null ? List.of() : List.copyOf(cargoManifest);
			boolean hasCleanupState = site != null && !site.temporary().isEmpty()
				|| !containerEmpty(siteStock) || !savedManifest.isEmpty();
			if (cleanupOrigin != null && hasCleanupState) {
				VillageData.BuildProject source = selected != null ? selected : previousCleanup;
				Optional<UUID> owner = previousCleanup != null ? previousCleanup.owner()
					: source == null ? Optional.of(c.v().getUUID()) : source.owner();
				long unavailable = previousCleanup != null ? previousCleanup.ownerUnavailableSince()
					: source == null ? 0L : source.ownerUnavailableSince();
				Optional<VillageData.PlanBounds> bounds = source == null ? Optional.of(fallbackProjectBounds("", cleanupOrigin, 0))
					: source.bounds().or(() -> Optional.of(fallbackProjectBounds(source.type(), cleanupOrigin, source.variant(), source.source().isPresent())));
				VillageData.BuildProject cleanup = new VillageData.BuildProject("", cleanupOrigin, 0, 0, 0, Optional.empty(),
					c.now(), owner, unavailable, List.of(), new VillageData.ProjectHaul(siteStockRows(siteStock), savedManifest),
					Optional.empty(), Optional.ofNullable(site), bounds, false, true);
				data.putBuildProject(cleanup);
			} else if (cleanupOrigin != null && previousCleanup != null) {
				data.removeBuildProject(cleanupOrigin);
			}
			if (selected != null && attached != null && attached.origin().equals(selected.origin())
				&& (site == null || site.temporary().isEmpty())) {
				ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
			}
		}
		data.buildProjectVersion = 1;
		data.refreshWanted();
	}

	private static boolean cleanOneAbandonedSite(Ctx c, VillageRef ref, VillageData data, Container box) {
		VillageData.BuildProject cleanup = null;
		Container stock = null;
		List<String> manifest = List.of();
		boolean ownerCanRefund = false;
		boolean hasCargo = false;
		BlockPos refundStand = null;
		for (VillageData.BuildProject candidate : data.buildProjects.stream()
			.filter(VillageData.BuildProject::cleanupOnly)
			.sorted(Comparator.comparingLong((VillageData.BuildProject project) -> project.origin().asLong())).toList()) {
			List<String> candidateManifest = de.villagerplus.job.BuildHaul.manifest(c, candidate.origin(), candidate.cargoManifest());
			boolean candidateHasCargo = !de.villagerplus.job.BuildHaul.inTransit(candidateManifest).isEmpty();
			boolean candidateOwnerCanRefund = candidate.owner().filter(c.v().getUUID()::equals).isPresent();
			// A saved carrier reservation belongs to its project owner. Do not let an unavailable owner
			// monopolize the single cleanup pass while another project's refunds or scaffold can progress.
			if (candidateHasCargo && !candidateOwnerCanRefund) continue;
			Container candidateStock = siteStock(candidate);
			boolean candidateHasStock = !containerEmpty(candidateStock);
			BlockPos candidateStand = candidateHasStock ? cleanupWorkStand(c, candidate) : null;
			ConstructionSite candidateSite = candidate.construction().orElse(new ConstructionSite(candidate.origin(), List.of()));
			boolean canRemoveTemporary = box != null && !candidateSite.temporary().isEmpty();
			boolean staleEmptyRecord = !candidateHasCargo && !candidateHasStock && candidateSite.temporary().isEmpty();
			if ((candidateHasCargo && candidateOwnerCanRefund) || (candidateHasStock && candidateStand != null)
				|| canRemoveTemporary || staleEmptyRecord) {
				cleanup = candidate;
				stock = candidateStock;
				manifest = new ArrayList<>(candidateManifest);
				ownerCanRefund = candidateOwnerCanRefund;
				hasCargo = candidateHasCargo;
				refundStand = candidateStand;
				break;
			}
		}
		if (cleanup == null) return false;
		ConstructionSite site = cleanup.construction().orElse(new ConstructionSite(cleanup.origin(), List.of()));
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, site);
		ConstructionSite remaining = site;
		boolean haulBusy = false;
		try {
			boolean hasSiteStock = !containerEmpty(stock);
			if ((ownerCanRefund && hasCargo) || (hasSiteStock && refundStand != null)) {
				// Tagged carrier cargo refunds directly along its saved route; site stock needs a real, reachable
				// exterior stand. The block above the origin can be inside the structure or obstructed.
				haulBusy = de.villagerplus.job.BuildHaul.tick(c, cleanup.origin(), refundStand, List.of(), false, stock);
				manifest = new ArrayList<>(de.villagerplus.job.BuildHaul.manifest(c, cleanup.origin(), manifest));
			}
			if (!haulBusy && box != null && !site.temporary().isEmpty()) {
				remaining = ConstructionSite.cleanup(c.level(), site, box, ref);
			}
		} finally {
			try {
				manifest = new ArrayList<>(de.villagerplus.job.BuildHaul.manifest(c, cleanup.origin(), manifest));
			} finally {
				try {
					if (remaining.temporary().isEmpty() && containerEmpty(stock) && manifest.isEmpty()) {
						data.removeBuildProject(cleanup.origin());
						ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
					} else {
						data.putBuildProject(new VillageData.BuildProject("", cleanup.origin(), 0, 0, 0, Optional.empty(),
							c.now(), cleanup.owner(), cleanup.ownerUnavailableSince(), List.of(), new VillageData.ProjectHaul(siteStockRows(stock), manifest),
							Optional.empty(), remaining.temporary().isEmpty() ? Optional.empty() : Optional.of(remaining),
							cleanup.bounds(), false, true));
					}
				} finally {
					try {
					restorePrimaryProject(ref, data);
					} finally {
						ref.dirty();
					}
				}
			}
		}
		return haulBusy;
	}

	/** Resolve a saved cleanup project's bounds to a live, reachable exterior stand before site-stock refund. */
	static BlockPos cleanupWorkStand(Ctx c, VillageData.BuildProject cleanup) {
		if (c == null || cleanup == null || cleanup.origin() == null) return null;
		VillageData.PlanBounds bounds = cleanup.bounds().orElseGet(() -> fallbackProjectBounds("", cleanup.origin(), 0));
		int y = cleanup.origin().getY();
		List<Step> perimeter = List.of(
			Step.free(new BlockPos(bounds.minX(), y, bounds.minZ()), Blocks.AIR.defaultBlockState()),
			Step.free(new BlockPos(bounds.minX(), y, bounds.maxZ()), Blocks.AIR.defaultBlockState()),
			Step.free(new BlockPos(bounds.maxX(), y, bounds.minZ()), Blocks.AIR.defaultBlockState()),
			Step.free(new BlockPos(bounds.maxX(), y, bounds.maxZ()), Blocks.AIR.defaultBlockState()));
		int minimumY = c.village() == null ? Integer.MIN_VALUE : villageGroundY(c.level(), c.village().bell()) - 12;
		return workStand(c, cleanup.origin(), Direction.EAST, perimeter, minimumY);
	}

	private static void restorePrimaryProject(VillageRef ref, VillageData data) {
		data.syncPrimaryProjectView();
		VillageData.BuildProject primary = data.buildProjects.stream().filter(VillageData.BuildProject::active).findFirst().orElse(null);
		ConstructionSite site = primary == null ? null : primary.construction().orElse(null);
		if (site == null) ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		else ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, site);
	}

	private static VillageData.PlanBounds boundsOf(List<Step> steps) {
		int minX = steps.stream().mapToInt(step -> step.pos().getX()).min().orElse(0) - 3;
		int maxX = steps.stream().mapToInt(step -> step.pos().getX()).max().orElse(0) + 3;
		int minZ = steps.stream().mapToInt(step -> step.pos().getZ()).min().orElse(0) - 3;
		int maxZ = steps.stream().mapToInt(step -> step.pos().getZ()).max().orElse(0) + 3;
		return new VillageData.PlanBounds(minX, maxX, minZ, maxZ);
	}

	private static VillageData.PlanBounds fallbackProjectBounds(String type, BlockPos origin, int variant) {
		return fallbackProjectBounds(type, origin, variant, false);
	}

	private static VillageData.PlanBounds fallbackProjectBounds(String type, BlockPos origin, int variant, boolean hasLegacySource) {
		if (type == null || type.isEmpty()) return new VillageData.PlanBounds(origin.getX() - 3, origin.getX() + 3,
			origin.getZ() - 3, origin.getZ() + 3);
		int half = architect(type) ? siteHalf(type, variant, designForPlan(type, variant, hasLegacySource), hasLegacySource)
			: Landmarks.handles(type) ? Landmarks.half(type)
			: type.equals(Blueprints.FARM) ? Math.max(3, variant / 10) : Blueprints.half(type);
		if (type.equals(Blueprints.HOUSE) && HouseDesignCatalog.usesCatalog(variant, hasLegacySource)) {
			int expanded = HouseLotPreparation.minimumExpandedLotHalf(half);
			VillageData.PlanBounds lot = houseLotReservationBounds(origin, Math.max(half, expanded));
			if (lot != null) return lot;
		}
		half = Math.max(4, half) + 3;
		return new VillageData.PlanBounds(origin.getX() - half, origin.getX() + half,
			origin.getZ() - half, origin.getZ() + half);
	}

	private static boolean planSiteConflicts(VillageRef ref, List<Step> candidate, BlockPos origin) {
		if (candidate == null || candidate.isEmpty()) return false;
		return planSiteConflicts(ref, boundsOf(candidate), origin);
	}

	private static boolean planSiteConflicts(VillageRef ref, VillageData.PlanBounds bounds, BlockPos origin) {
		return ref != null && houseLotConflicts(bounds, origin, ref.data().buildProjects);
	}

	static boolean houseLotConflicts(VillageData.PlanBounds bounds, BlockPos origin,
		List<VillageData.BuildProject> projects) {
		if (bounds == null || projects == null) return true;
		for (VillageData.BuildProject project : projects) {
			if (project.active() && project.origin().equals(origin)) continue;
			VillageData.PlanBounds reserved = project.bounds().orElseGet(() -> fallbackProjectBounds(project.type(), project.origin(), project.variant(), project.source().isPresent()));
			if (bounds.overlaps(reserved)) return true;
		}
		return false;
	}

	/** While a civic milestone is missing, the elected builder does not drop back into mining between plan attempts. */
	/** A started house retains its saved cursor while a stalled milestone waits for a site. */
	static boolean shouldPreemptForSpecial(String planType, String special) {
		return special != null && !special.equals(planType) && Blueprints.DECOR.equals(planType);
	}

	static boolean shouldHoldForSpecial(boolean mason, boolean specialMissing) {
		return mason && specialMissing;
	}

	private static boolean holdForSpecial(Ctx c, VillageRef ref, boolean mason) {
		if (!shouldHoldForSpecial(mason, nextSpecial(ref, villagers(c.level(), ref).size()) != null)) return false;
		if (!ref.data().hasPlan()) {
			BlockPos street = villageReturn(c.level(), ref.bell());
			if (street != null) JobUtil.approachLong(c, street, 2.0);
		}
		return true;
	}

	/** Only the persisted plan owner may path, advance, or abort a shared village plan. */
	private static boolean ownsActivePlan(Ctx c, VillageRef ref, boolean farmerPlan) {
		VillageData data = ref.data();
		Villager caller = c.v();
		java.util.UUID callerId = caller.getUUID();
		java.util.Optional<java.util.UUID> owner = data.planBuilder();
		if (owner.isEmpty()) {
			// Backward compatibility: old saved plans have no owner; the first eligible worker claims them.
			if (!eligiblePlanWorker(c.level(), ref, caller, farmerPlan)) return false;
			data.claimPlanBuilder(callerId);
			ref.dirty();
			return true;
		}
		if (owner.get().equals(callerId)) {
			if (!availablePlanOwner(c.level(), ref, caller, farmerPlan)) return false;
			if (data.planBuilderUnavailableSince() != 0L) {
				data.claimPlanBuilder(callerId);
				ref.dirty();
			}
			return true;
		}

		Entity assigned = c.level().getEntity(owner.get());
		if (assigned instanceof Villager villager && availablePlanOwner(c.level(), ref, villager, farmerPlan)) {
			if (data.planBuilderUnavailableSince() != 0L) {
				data.claimPlanBuilder(owner.get());
				ref.dirty();
			}
			return false;
		}
		if (!eligiblePlanWorker(c.level(), ref, caller, farmerPlan)) return false;
		long since = data.planBuilderUnavailableSince();
		if (since == 0L) {
			since = Math.max(1L, c.now());
			data.markPlanBuilderUnavailable(since);
			ref.dirty();
		}
		if (c.now() - since < PLAN_OWNER_GRACE) return false;
		data.claimPlanBuilder(callerId);
		ref.dirty();
		LOGGER.info("Villagers: Repurposed: Bau '{}' wechselt nach Ausfall des Baumeisters zu {}", data.planType,
			caller.blockPosition().toShortString());
		return true;
	}

	private static boolean eligiblePlanWorker(ServerLevel level, VillageRef ref, Villager villager, boolean farmerPlan) {
		if (!availablePlanOwner(level, ref, villager, farmerPlan)) return false;
		List<Villager> residents = villagers(level, ref);
		if (residents.stream().noneMatch(resident -> resident.getUUID().equals(villager.getUUID()))) return false;
		return true;
	}

	/** Existing owners can finish a plan after walking outside the resident scan radius. */
	private static boolean availablePlanOwner(ServerLevel level, VillageRef ref, Villager villager, boolean farmerPlan) {
		if (villager == null || !villager.isAlive() || villager.isBaby()
			|| villager.isSleeping() || villager.isTrading() || preemptedFromWork(villager)
			|| de.villagerplus.village.Guests.isGuest(villager)
			|| de.villagerplus.village.Expedition.isAway(villager)
			|| de.villagerplus.village.RailTransport.isPassenger(villager)
			|| de.villagerplus.village.Market.shop(ref, villager.getUUID()) != null) return false;
		VillageRef assigned = Villages.find(level, villager);
		return assigned != null && assigned.bell().equals(ref.bell());
	}

	/** Match JobManager's dispatch exclusions so an owner unable to receive work cannot pin a plan indefinitely. */
	private static boolean preemptedFromWork(Villager villager) {
		var brain = villager.getBrain();
		return brain.isActive(Activity.PANIC) || brain.isActive(Activity.RAID) || brain.isActive(Activity.PRE_RAID)
			|| brain.isActive(Activity.HIDE) || brain.isActive(Activity.FIGHT);
	}

	/** Eligible building plans receive a temporary crew; an unprepared site is haul-only until its setup is paid. */
	private static boolean teamBuildProject(VillageData.BuildProject project) {
		return project != null && project.active() && !farmerProject(project.type())
			&& !Blueprints.WALL.equals(project.type());
	}

	/** An active, fixed-owner building cursor with a persisted setup site can accept real stock hauling. */
	static boolean supportsConstructionHaul(VillageData.BuildProject project) {
		return teamBuildProject(project) && project.construction()
			.filter(site -> site.origin().equals(project.origin())).isPresent();
	}

	private static Map<java.util.UUID, BuildCrewAssignment> buildCrewAssignments(ServerLevel level, VillageRef ref,
			VillageData data) {
		if (level == null || ref == null || data == null) return Map.of();
		long now = level.getGameTime();
		Map<Long, BuildCrewSnapshot> byVillage = BUILD_CREW_SNAPSHOTS.computeIfAbsent(level, ignored -> new HashMap<>());
		BuildCrewSnapshot cached = byVillage.get(ref.bell().asLong());
		if (cached != null && cached.gameTime() == now) return cached.assignments();
		Map<java.util.UUID, BuildCrewAssignment> assignments = new HashMap<>();
		List<VillageData.BuildProject> projects = data.buildProjects.stream()
			.filter(Builder::supportsConstructionHaul)
			.filter(project -> project.owner().isPresent())
			.sorted(Comparator.comparingLong(project -> project.origin().asLong())).toList();
		if (projects.isEmpty()) return cacheBuildCrewAssignments(level, ref, now, assignments);

		Set<java.util.UUID> projectOwners = data.buildProjects.stream()
			.filter(VillageData.BuildProject::active)
			.flatMap(project -> project.owner().stream())
			.collect(Collectors.toSet());
		Set<Long> staffedProjects = new java.util.HashSet<>();
		Set<java.util.UUID> staffedOwners = new java.util.HashSet<>();
		List<Villager> residents = villagers(level, ref);
		Map<java.util.UUID, Villager> residentById = new HashMap<>();
		for (Villager resident : residents) residentById.put(resident.getUUID(), resident);
		for (VillageData.BuildProject project : projects) {
			java.util.UUID ownerId = project.owner().orElseThrow();
			// Use the resident snapshot's live reference first; a just-added entity may precede UUID-index visibility.
			Villager owner = residentById.get(ownerId);
			if (owner == null) {
				Entity ownerEntity = level.getEntity(ownerId);
				if (ownerEntity instanceof Villager loadedOwner) owner = loadedOwner;
			}
			VillageRef ownerVillage = owner == null ? null : Villages.find(level, owner);
			boolean ownerStillPresent = owner != null && owner.isAlive() && !owner.isBaby()
				&& !de.villagerplus.village.Expedition.isAway(owner)
				&& ownerVillage != null && ownerVillage.bell().equals(ref.bell());
			boolean ownerAvailable = owner != null && availablePlanOwner(level, ref, owner, false);
			if (staffedOwners.contains(ownerId) || !shouldStaffPreparedProject(ownerAvailable, ownerStillPresent)) continue;
			staffedOwners.add(ownerId);
			staffedProjects.add(project.id());
		}
		FreightReservations freight = freightReservations(data.buildProjects);
		Set<java.util.UUID> freightCarriers = freight.carriers();
		Map<Long, VillageData.BuildProject> projectByOrigin = new HashMap<>();
		for (VillageData.BuildProject project : projects) projectByOrigin.put(project.origin().asLong(), project);
		Map<Long, Integer> helperCounts = new HashMap<>();
		freight.projectOrigins().entrySet().stream()
			.sorted(Comparator.comparing((Map.Entry<java.util.UUID, Long> entry) -> entry.getKey().toString()))
			.forEach(entry -> {
				VillageData.BuildProject project = projectByOrigin.get(entry.getValue());
				Villager carrier = residentById.get(entry.getKey());
				if (project == null || carrier == null || projectOwners.contains(entry.getKey())
					|| !eligibleBuildHelper(level, ref, carrier)) return;
				assignFreightCarrierToCrew(entry.getKey(), project, staffedProjects.contains(project.id()),
					assignments, helperCounts);
			});
		if (staffedProjects.isEmpty()) return cacheBuildCrewAssignments(level, ref, now, assignments);

		List<Villager> availableHelpers = residents.stream()
			.filter(v -> eligibleBuildHelper(level, ref, v))
			.filter(v -> isAvailableBuildHelper(v.getUUID(), projectOwners, freightCarriers))
			.sorted(Comparator.comparing((Villager v) -> !compatibleMason(v))
				.thenComparing(v -> !unemployedBuildCarrier(v))
				.thenComparing(v -> v.getUUID().toString()))
			.toList();
		// Reserve one capable Mason to open the next independent project. Excluding every Mason
		// who could start one used to turn the rest of the Masons into unavailable workers too.
		java.util.UUID reservedMason = availableHelpers.stream()
			.filter(v -> canClaimNextMasonProject(level, ref, data, v))
			.map(Villager::getUUID)
			.findFirst().orElse(null);
		List<Villager> helpers = availableHelpers.stream()
			.filter(v -> !v.getUUID().equals(reservedMason))
			.toList();
		for (Villager helper : helpers) {
			VillageData.BuildProject chosen = projects.stream()
				.filter(project -> staffedProjects.contains(project.id()))
				.filter(project -> helperCounts.getOrDefault(project.id(), 0) < MAX_BUILD_HELPERS_PER_PROJECT)
				.min(Comparator.comparingInt((VillageData.BuildProject project) -> helperCounts.getOrDefault(project.id(), 0))
					.thenComparingLong(project -> project.origin().asLong()))
				.orElse(null);
			if (chosen == null) break;
			int slot = 1 + helperCounts.getOrDefault(chosen.id(), 0);
			helperCounts.put(chosen.id(), slot);
			assignments.putIfAbsent(chosen.owner().orElseThrow(), new BuildCrewAssignment(chosen.origin(), 0, false));
			assignments.put(helper.getUUID(), new BuildCrewAssignment(chosen.origin(), slot, true));
		}
		return cacheBuildCrewAssignments(level, ref, now, assignments);
	}

	/** Keep every persisted carrier unavailable to new jobs, but bind valid, unambiguous loads to their exact project. */
	static FreightReservations freightReservations(List<VillageData.BuildProject> projects) {
		Map<java.util.UUID, Long> projectOrigins = new HashMap<>();
		Set<java.util.UUID> carriers = new java.util.HashSet<>();
		Set<java.util.UUID> ambiguous = new java.util.HashSet<>();
		if (projects != null) for (VillageData.BuildProject project : projects) {
			for (String row : project.cargoManifest()) {
				if (row == null) continue;
				String[] fields = row.split("\\|", -1);
				if (fields.length < 2) continue;
				java.util.UUID carrier;
				try {
					carrier = java.util.UUID.fromString(fields[0]);
				} catch (IllegalArgumentException ignored) {
					// Malformed legacy rows do not reserve a worker; the haul reconciler owns their cleanup.
					continue;
				}
				carriers.add(carrier);
				if (fields.length != 4 || !validManifestItem(fields[1]) || !positiveManifestCount(fields[2])
					|| !longManifestValue(fields[3]) || ambiguous.contains(carrier)) continue;
				long origin = project.origin().asLong();
				Long previous = projectOrigins.putIfAbsent(carrier, origin);
				if (previous != null && previous.longValue() != origin) {
					projectOrigins.remove(carrier);
					ambiguous.add(carrier);
				}
			}
		}
		return new FreightReservations(Map.copyOf(projectOrigins), Set.copyOf(carriers));
	}

	private static boolean positiveManifestCount(String value) {
		try { return Integer.parseInt(value) > 0; } catch (RuntimeException ignored) { return false; }
	}

	private static boolean validManifestItem(String value) {
		Identifier id = Identifier.tryParse(value);
		return id != null && BuiltInRegistries.ITEM.getOptional(id).isPresent();
	}

	private static boolean longManifestValue(String value) {
		try { Long.parseLong(value); return true; } catch (RuntimeException ignored) { return false; }
	}

	/** Reserved carriers keep a real helper slot until their marked inventory is deposited or recovered. */
	static boolean assignFreightCarrierToCrew(java.util.UUID carrierId, VillageData.BuildProject project,
			boolean ownerStaffed, Map<java.util.UUID, BuildCrewAssignment> assignments, Map<Long, Integer> helperCounts) {
		if (carrierId == null || project == null || assignments == null || helperCounts == null
			|| !teamBuildProject(project) || project.owner().isEmpty()
			|| project.owner().filter(carrierId::equals).isPresent() || assignments.containsKey(carrierId)) return false;
		int current = helperCounts.getOrDefault(project.id(), 0);
		int slot = 1 + Math.min(current, MAX_BUILD_HELPERS_PER_PROJECT - 1);
		helperCounts.put(project.id(), Math.min(MAX_BUILD_HELPERS_PER_PROJECT, current + 1));
		if (ownerStaffed) assignments.putIfAbsent(project.owner().orElseThrow(),
			new BuildCrewAssignment(project.origin(), 0, false));
		assignments.put(carrierId, new BuildCrewAssignment(project.origin(), slot, true));
		return true;
	}

	static boolean isAvailableBuildHelper(java.util.UUID villager, Set<java.util.UUID> projectOwners,
			Set<java.util.UUID> freightCarriers) {
		return villager != null && !projectOwners.contains(villager) && !freightCarriers.contains(villager);
	}

	private static Map<java.util.UUID, BuildCrewAssignment> cacheBuildCrewAssignments(ServerLevel level, VillageRef ref,
			long now, Map<java.util.UUID, BuildCrewAssignment> assignments) {
		Map<java.util.UUID, BuildCrewAssignment> snapshot = Map.copyOf(assignments);
		BUILD_CREW_SNAPSHOTS.computeIfAbsent(level, ignored -> new HashMap<>())
			.put(ref.bell().asLong(), new BuildCrewSnapshot(now, snapshot));
		return snapshot;
	}

	/** Explicitly invalidates the per-tick workforce view after a check fixture changes resident activity. */
	static void clearBuildCrewSnapshotForCheck(ServerLevel level, VillageRef ref) {
		Map<Long, BuildCrewSnapshot> snapshots = BUILD_CREW_SNAPSHOTS.get(level);
		if (snapshots != null && ref != null) snapshots.remove(ref.bell().asLong());
	}

	static void clearCrewRuntimeCachesForCheck(ServerLevel level, java.util.UUID villager) {
		Map<java.util.UUID, CrewStandCache> stands = BUILD_CREW_STANDS.get(level);
		if (stands != null) stands.remove(villager);
		Map<java.util.UUID, RouteWaypointCache> routes = ROUTE_WAYPOINTS.get(level);
		if (routes != null) routes.remove(villager);
	}

	/** Keep a prepared project's crew working while its living adult owner is temporarily unavailable. */
	static boolean shouldStaffPreparedProject(boolean ownerAvailable, boolean ownerStillPresent) {
		return ownerAvailable || ownerStillPresent;
	}

	/** Keep an eligible Mason available to create the next project instead of dispatching them as a helper first. */
	private static boolean canClaimNextMasonProject(ServerLevel level, VillageRef ref, VillageData data, Villager villager) {
		return compatibleMason(villager) && canStartProject(level, ref, data, villager, true);
	}

	private static boolean eligibleBuildHelper(ServerLevel level, VillageRef ref, Villager villager) {
		if (villager == null || !villager.isAlive() || villager.isBaby() || villager.isSleeping() || villager.isTrading()
			|| villager.isNoAi() || preemptedFromWork(villager) || Guests.isGuest(villager)
			|| de.villagerplus.village.Expedition.isAway(villager)
			|| de.villagerplus.village.RailTransport.isPassenger(villager)
			|| de.villagerplus.village.Caravan.carryingFor(villager)
			|| de.villagerplus.village.Market.shop(ref, villager.getUUID()) != null) return false;
		// Any idle adult resident can lend a hand. JobManager gives its normal profession
		// work first refusal before offering the shared construction task.
		if (villager.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.NITWIT)) return false;
		VillageRef assigned = Villages.find(level, villager);
		return assigned != null && assigned.bell().equals(ref.bell());
	}

	private static boolean unemployedBuildCarrier(Villager villager) {
		var profession = villager.getVillagerData().profession();
		return profession.is(net.minecraft.world.entity.npc.villager.VillagerProfession.NONE)
			&& !profession.is(net.minecraft.world.entity.npc.villager.VillagerProfession.NITWIT);
	}

	/** Let an available adult resume their own building cursor or take a reserved crew slot after normal job work. */
	public static boolean buildHelperWork(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || !eligibleBuildHelper(c.level(), ref, c.v())) return false;
		VillageData data = ref.data();
		retryUnstaffedProjectOwner(c, ref, data, Lager.combined(c.level(), ref));
		VillageData.BuildProject owned = ownedProjectForWorker(data.buildProjects, c.v().getUUID());
		if (teamBuildProject(owned)) return tick(c, true, owned.origin(), 0, false);
		Map<java.util.UUID, BuildCrewAssignment> assignments = buildCrewAssignments(c.level(), ref, data);
		BuildCrewAssignment assignment = assignments.get(c.v().getUUID());
		if (assignment == null || !assignment.helper()) return false;
		return tick(c, true, assignment.origin(), assignment.standSlot(), true);
	}

	/** Offer an assigned resident to a prepared construction crew only after that phase's main job declined work. */
	public static boolean shouldOfferConstructionSupport(boolean priorityJobWorked, boolean outdoorSupportAllowed,
			boolean preparedCrewProjectExists) {
		return !priorityJobWorked && outdoorSupportAllowed && preparedCrewProjectExists;
	}

	/** Cheap gate so idle residents can resume live building cursors and offer support to prepared crews. */
	public static boolean hasPreparedCrewProject(VillageData data) {
		if (data == null) return false;
		for (VillageData.BuildProject project : data.buildProjects) {
			if (teamBuildProject(project)) return true;
		}
		return false;
	}

	/** Elect one resident as a temporary builder when a small village has no mason yet. */
	public static boolean bootstrapBuilder(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || c.v().isBaby() || de.villagerplus.village.Guests.isGuest(c.v())
			|| de.villagerplus.village.Expedition.isAway(c.v()) || de.villagerplus.village.RailTransport.isPassenger(c.v())
			|| de.villagerplus.village.Market.shop(ref, c.v().getUUID()) != null) return false;
		for (VillageData.BuildProject project : ref.data().buildProjects) {
			if (!project.active() || project.owner().filter(c.v().getUUID()::equals).isEmpty()) continue;
			return availablePlanOwner(c.level(), ref, c.v(), farmerProject(project.type()));
		}
		if (ref.data().hasPlan() && ref.data().planBuilder().isPresent()) {
			java.util.UUID ownerId = ref.data().planBuilder().get();
			// The just-spawned owner may not be in the level UUID index until the next tick.
			// When this exact villager is the persisted owner, validate the live instance directly.
			if (ownerId.equals(c.v().getUUID())) return availablePlanOwner(c.level(), ref, c.v(), false);
			Entity owner = c.level().getEntity(ownerId);
			if (owner instanceof Villager villager && availablePlanOwner(c.level(), ref, villager, false)) {
				return villager.getUUID().equals(c.v().getUUID());
			}
		}
		String key = c.level().dimension().identifier() + "/" + ref.bell().asLong();
		BootstrapBuilder cached = BOOTSTRAP_BUILDERS.get(key);
		if (cached == null || c.now() >= cached.until() || c.level().getEntity(cached.villager()) == null) {
			var residents = villagers(c.level(), ref);
			boolean hasMason = residents.stream().anyMatch(v -> !v.isBaby() && !de.villagerplus.village.Expedition.isAway(v)
				&& !de.villagerplus.village.RailTransport.isPassenger(v)
				&& de.villagerplus.village.Market.shop(ref, v.getUUID()) == null
				&& compatibleMason(v));
			if (hasMason) {
				BOOTSTRAP_BUILDERS.remove(key);
				return false;
			}
			Villager chosen = bootstrapCandidate(c.level(), ref);
			if (chosen == null) {
				BOOTSTRAP_BUILDERS.remove(key);
				return false;
			}
			cached = new BootstrapBuilder(chosen.getUUID(), c.now() + 200L);
			BOOTSTRAP_BUILDERS.put(key, cached);
		}
		return cached.villager().equals(c.v().getUUID());
	}

	/** A temporary builder also handles the plan's material work when its normal profession cannot. */
	public static boolean bootstrapBuildWork(Ctx c) {
		if (!bootstrapBuilder(c)) return false;
		de.villagerplus.dev.G02SupplyTrace.stage(c, "bootstrap-dispatch", "selected as active plan worker");
		boolean ownsShortage = c.village() != null && c.village().data().buildProjects.stream()
			.anyMatch(project -> project.active() && !project.wanted().isEmpty()
				&& project.owner().filter(c.v().getUUID()::equals).isPresent());
		boolean specialPlan = ownsShortage && (c.village().data().planType.equals(Blueprints.FARM)
			|| c.village().data().planType.equals(Blueprints.PEN) || c.village().data().planType.equals(Blueprints.WALL));
		// Farmer and wall plans have their own continuation paths; keep their existing shared supplier dispatch.
		if (specialPlan && supplyMissing(c)) return true;
		if (tick(c, true)) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "bootstrap-builder-work", c.st().activity);
			return true;
		}
		return !ownsShortage && supplyMissing(c);
	}

	private static final java.util.Map<String, Long> STONE_TRACE = new java.util.HashMap<>();

	/** Dev aid: rate-limited per worker so the plan-stone chain explains where it stops. */
	private static void stoneTrace(Ctx c, String what) {
		String traceKey = c.v().getUUID() + what.substring(0, Math.min(10, what.length()));
		long last = STONE_TRACE.getOrDefault(traceKey, -1_000_000L);
		if (c.now() - last < 1200L) return;
		STONE_TRACE.put(traceKey, c.now());
		LOGGER.info("Steinkette {}: {}", c.mem().name, what);
	}

	/** Source and deliver only materials that can advance the active plan. */
	public static boolean supplyMissing(Ctx c) {
		boolean result = supplyMissingInner(c);
		VillageRef traced = c.village();
		if (traced != null && traced.data().hasPlan() && !traced.data().wanted.isEmpty()
			&& traced.data().planBuilder().filter(c.v().getUUID()::equals).isPresent()) {
			stoneTrace(c, "supplyMissing -> " + result + ", Aktivitaet='" + c.st().activity + "', Wunschliste=" + traced.data().wanted.size());
		}
		return result;
	}

	private static boolean supplyMissingInner(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-skipped", "no village");
			return false;
		}
		if (!ref.data().hasPlan() || ref.data().wanted.isEmpty()
			|| !ref.data().planBuilder().filter(c.v().getUUID()::equals).isPresent()) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-skipped", "no owned plan shortage");
			return false;
		}
		Container box = ref.chest(c.level());
		if (box == null) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-waiting-for-storage", "village chest unavailable");
			return false;
		}
		// Craft or fetch any wanted block already supported by the village workshop/lager first.
		de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-check-workshop", "checking supported recipes/storage");
		if (de.villagerplus.job.Workshop.tick(c)) {
			if (ref.data().extra.planMaterialWait.isPresent()) {
				ref.data().extra.planMaterialWait = Optional.empty();
				ref.dirty();
			}
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-workshop-active", c.st().activity);
			return true;
		}
		if (de.villagerplus.job.Woodcutting.progressStrippedPlanDemand(c)) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-strip-plan-logs", c.st().activity);
			return true;
		}
		Container combined = Lager.combined(c.level(), ref);
		var materialWait = PlanMaterialWait.forPlan(ref.data(), combined == null ? box : combined,
			de.villagerplus.village.Specials.has(ref, Blueprints.KITCHEN));
		if (!ref.data().extra.planMaterialWait.equals(materialWait)) {
			ref.data().extra.planMaterialWait = materialWait;
			ref.dirty();
		}

		boolean noPlanTree = false;
		// Division of labour: when the plan needs both timber and stone, the masons serve stone first,
		// so that the whole stone chain does not walk off to fell trees far away while the quarry/mine stands idle.
		boolean stoneFirst = false;
		if (de.villagerplus.job.Woodcutting.planNeedsWood(ref.data())
			&& (wantsCobblestone(ref.data()) || de.villagerplus.job.PlanStoneSupply.needsCobble(c.level(), ref.data(), box))) {
			// Masons (the stone trade) serve stone first; everybody else keeps timber first.
			stoneFirst = de.villagerplus.job.JobManager.isMason(c.v());
		}
		if (!stoneFirst && de.villagerplus.job.Woodcutting.planNeedsWood(ref.data())) {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-check-carried-wood", "checking worker inventory for logs");
			Set<String> demandedLogs = de.villagerplus.job.Woodcutting.demandedLogIds(ref.data().wanted);
			java.util.function.Predicate<net.minecraft.world.item.ItemStack> requestedLogs =
				stack -> de.villagerplus.job.Woodcutting.matchesRequestedLog(stack, demandedLogs);
			int carriedLogs = JobUtil.count(c.v().getInventory(), requestedLogs);
			if (carriedLogs > 0 && JobUtil.depositSurplus(c, requestedLogs, 0, 0)) {
				de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-deliver-carried-wood", c.st().activity);
				return true;
			}
			if (carriedLogs > 0) {
				c.setActivity("wartet auf Lagerplatz für Bauholz");
				de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-wood-storage-full", "keine sichere Zustellung; Holz bleibt im Villagerinventar");
				return true;
			}
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-woodcutting", "calling normal woodcutting task");
			if (ref.data().hasPlan()) {
				var work = c.v().getAttachedOrElse(de.villagerplus.VillagerPlus.WOODCUTTER_WORK,
					de.villagerplus.job.WoodcutterWork.empty());
				if (work.root().isPresent() && !work.removalPending()) {
					BlockPos savedRoot = work.root().get();
					if (!c.level().hasChunkAt(savedRoot)) return true;
					if (!planTreeNeighborhoodLoaded(c.level(), savedRoot)) {
						c.st().woodCheckAt = c.now() + 200;
						c.setActivity("wartet auf geladene Umgebung des Planbaums");
						return true;
					}
					var villageBounds = Development.bounds(c.level(), ref);
					boolean insideVillage = savedRoot.getX() >= villageBounds.minX() && savedRoot.getX() <= villageBounds.maxX()
						&& savedRoot.getY() >= villageBounds.minY() && savedRoot.getY() <= villageBounds.maxY()
						&& savedRoot.getZ() >= villageBounds.minZ() && savedRoot.getZ() <= villageBounds.maxZ();
					de.villagerplus.job.Woodcutting.startWalkBackSelection();
					if (insideVillage || !de.villagerplus.job.Woodcutting.matchesRequestedLog(
						c.level().getBlockState(savedRoot).getBlock(), demandedLogs)
						|| planTreeOnRoad(c.level(), savedRoot) || planTreeClaimed(c.level(), c.v(), savedRoot)
						|| (work.log().isEmpty() && work.stage() < 0 && de.villagerplus.job.Woodcutting.walkBackUnsafe(c.level(),
							de.villagerplus.job.Woodcutting.walkBackStreet(c.level(), ref), savedRoot))) {
						work = work.abandonTree();
						c.v().setAttached(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, work);
						c.st().clearTarget();
					}
				}
				if (work.root().isEmpty()) {
					// No free matching tree must not freeze the stone supply behind it: carry on with stone instead of returning.
					if (c.now() < c.st().woodCheckAt) noPlanTree = true;
					BlockPos tree = noPlanTree ? null : selectPlanWoodTree(c.level(), ref, c.v(), demandedLogs);
					if (tree == null) {
						if (!noPlanTree) c.st().woodCheckAt = c.now() + 1200;
						noPlanTree = true;
						c.setActivity("wartet auf einen freien passenden Baum für den Bauplan");
					} else {
					String species = BuiltInRegistries.ITEM.getKey(c.level().getBlockState(tree).getBlock().asItem()).toString();
					c.v().setAttached(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, work.start(tree, species));
					}
				}
			}
			if (!noPlanTree && de.villagerplus.job.Woodcutting.tick(c)) {
				de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-woodcutting-active", c.st().activity);
				return true;
			}
		} else {
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-wood-not-requested", "no wood item in active request");
		}

		// Only acquire a small raw buffer for plan outputs whose complete vanilla-shaped
		// recipe chain is actually loaded. If needed, build a marked communal furnace from depot stock.
		Container stoneStock = combined == null ? box : combined;
		boolean needsFurnaceNow = de.villagerplus.job.PlanStoneSupply.needsFurnace(c.level(), ref.data(), stoneStock);
		if (needsFurnaceNow) stoneTrace(c, "Ofen noetig, Bestand Bruchstein=" + JobUtil.count(stoneStock, st0 -> st0.is(Items.COBBLESTONE)));
		boolean furnaceBusy = needsFurnaceNow && de.villagerplus.job.Workstation.ensurePlanStoneFurnace(c);
		if (needsFurnaceNow) stoneTrace(c, "Ofen-Ergebnis=" + furnaceBusy + ", Aktivitaet='" + c.st().activity + "', needsCobble="
			+ de.villagerplus.job.PlanStoneSupply.needsCobble(c.level(), ref.data(), stoneStock));
		if (furnaceBusy) {
			if (ref.data().extra.planMaterialWait.isPresent()) {
				ref.data().extra.planMaterialWait = Optional.empty();
				ref.dirty();
			}
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-plan-furnace", c.st().activity);
			return true;
		}
		if (de.villagerplus.job.PlanStoneSupply.needsCobble(c.level(), ref.data(), stoneStock)) {
			if (de.villagerplus.job.PlanStoneSupply.supplyMiningPickaxe(c, stoneStock)) {
				stoneTrace(c, "Spitzhacke wird beschafft: " + c.st().activity);
				de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-plan-pickaxe", c.st().activity);
				return true;
			}
			int quarried = JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.COBBLESTONE));
			// Exposed natural stone first: it needs neither timber supports nor torches, unlike a real mine.
			if (quarried < 12 && de.villagerplus.job.SurfaceStone.tick(c)) {
				stoneTrace(c, "Oberflaechen-Steinbruch: " + c.st().activity + ", getragen=" + quarried);
				de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-plan-surface-quarry", c.st().activity);
				return true;
			}
			int carried = JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.COBBLESTONE));
			if (shouldDepositPlanStone(carried)) {
				if (JobUtil.depositSurplus(c, stack -> stack.is(Items.COBBLESTONE), 0, 0)) {
					de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-deliver-plan-stone", c.st().activity);
					return true;
				}
				c.setActivity("wartet auf Lagerplatz für Baustein");
				return true;
			}
			boolean planMining = de.villagerplus.job.Mining.tick(c, de.villagerplus.job.Mining.Need.STONE);
			stoneTrace(c, "Plan-Bergbau Ergebnis=" + planMining + ", Aktivitaet='" + c.st().activity + "', Pos=" + c.v().blockPosition().toShortString());
			if (planMining) {
				de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-plan-stone-mining", c.st().activity);
				return true;
			}
		}

		if (wantsCobblestone(ref.data())) {
			// A pickaxe first (from the tool shed or crafted from village stock), then exposed natural stone without a mine.
			if (de.villagerplus.job.PlanStoneSupply.supplyMiningPickaxe(c, stoneStock, true)) {
				stoneTrace(c, "Spitzhacke fuer Bruchstein-Wunsch: " + c.st().activity);
				return true;
			}
			int carriedCobble = JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.COBBLESTONE));
			if (carriedCobble < PLAN_STONE_BUFFER && de.villagerplus.job.SurfaceStone.tick(c)) {
				stoneTrace(c, "Oberflaechen-Steinbruch (Wunsch): " + c.st().activity + ", getragen=" + carriedCobble);
				return true;
			}
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-check-carried-stone", "checking worker inventory for cobblestone");
			if (shouldDepositPlanStone(carriedCobble)
				&& JobUtil.depositSurplus(c, stack -> stack.is(Items.COBBLESTONE), 0, 0)) {
				de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-deliver-carried-stone", c.st().activity);
				return true;
			}
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-mining", "calling normal stone-mining task");
			boolean mining = de.villagerplus.job.Mining.tick(c, de.villagerplus.job.Mining.Need.STONE);
			de.villagerplus.dev.G02SupplyTrace.stage(c, mining ? "supply-mining-active" : "supply-mining-no-progress", c.st().activity);
			return mining;
		}
		if (materialWait.isPresent()) {
			stoneTrace(c, "Ende supplyMissing: Wartegrund=" + materialWait.get() + ", Ofen noetig=" + de.villagerplus.job.PlanStoneSupply.needsFurnace(c.level(), ref.data(), stoneStock)
				+ ", Bruchstein noetig=" + de.villagerplus.job.PlanStoneSupply.needsCobble(c.level(), ref.data(), stoneStock)
				+ ", kombiniert Bruchstein/Stein=" + JobUtil.count(stoneStock, st0 -> st0.is(Items.COBBLESTONE)) + "/" + JobUtil.count(stoneStock, st0 -> st0.is(Items.STONE))
				+ ", Kiste Bruchstein=" + JobUtil.count(box, st0 -> st0.is(Items.COBBLESTONE)) + ", Kueche=" + de.villagerplus.village.Specials.has(ref, Blueprints.KITCHEN));
			c.setActivity(PlanMaterialWait.REASON);
			de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-waiting-plan-furnace", PlanMaterialWait.REASON);
			return false;
		}
		de.villagerplus.dev.G02SupplyTrace.stage(c, "supply-no-source", "no supported workshop, wood, or cobblestone source progressed");
		return false;
	}

	/**
	 * Plan-only tree selection pins a safe exact-species target into the persistent woodcutter cursor before the
	 * ordinary work cycle runs. This also keeps concurrent builders from choosing one another's claimed tree.
	 */
	public static BlockPos selectPlanWoodTree(ServerLevel level, VillageRef ref, Villager worker, Set<String> demandedLogs) {
		int nearRadius = de.villagerplus.job.Woodcutting.searchRadius(true);
		// Order: protected village footprint respected near, then only buildings protected near, then the same further out.
		BlockPos tree = selectPlanWoodTree(level, ref, worker, demandedLogs, nearRadius, false);
		if (tree == null) tree = selectPlanWoodTree(level, ref, worker, demandedLogs, nearRadius, true);
		if (tree == null) tree = selectPlanWoodTree(level, ref, worker, demandedLogs, de.villagerplus.job.Woodcutting.EXTENDED_PLAN_RADIUS, false);
		if (tree == null) tree = selectPlanWoodTree(level, ref, worker, demandedLogs, de.villagerplus.job.Woodcutting.EXTENDED_PLAN_RADIUS, true);
		// Only when even the wide search finds no matching tree does the nearest other species decide the village's wood.
		if (tree == null && lastWrongStem != null && ref != null) de.villagerplus.village.VillageAdmin.noteNearestLog(ref, lastWrongStem);
		return tree;
	}

	private static BlockPos selectPlanWoodTree(ServerLevel level, VillageRef ref, Villager worker, Set<String> demandedLogs, int radius, boolean relaxed) {
		if (level == null || ref == null || worker == null || demandedLogs == null || demandedLogs.isEmpty()) return null;
		BlockPos center = worker.blockPosition();
		var village = Development.bounds(level, ref);
		var sites = relaxed ? de.villagerplus.job.Woodcutting.buildingSites(level, ref) : null;
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		// Cheap filters while scanning; the costly ones (canopy, road, claim, walk-back search) run on the nearest candidates only, nearest first.
		java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
		int seenLogs = 0, inVillage = 0, wrongSpecies = 0, notHarvestable = 0, blocked = 0, unreachable = 0, unloaded = 0, noWayBack = 0, deferred = 0;
		long nearestWrong = Long.MAX_VALUE;
		String nearestWrongStem = null;
		for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
			long distance = (long)dx * dx + (long)dz * dz;
			int x = center.getX() + dx, z = center.getZ() + dz;
			if (!level.hasChunk(x >> 4, z >> 4)) continue;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			if (Math.abs(y - center.getY()) > 24 || !level.getBlockState(probe.set(x, y, z)).is(BlockTags.OVERWORLD_NATURAL_LOGS)) continue;
			int baseY = y;
			while (y - baseY < 48 && level.getBlockState(probe.set(x, baseY - 1, z)).is(BlockTags.LOGS)) baseY--;
			BlockPos root = new BlockPos(x, baseY, z);
			seenLogs++;
			if (!planTreeNeighborhoodLoaded(level, root)) { unloaded++; continue; }
			if (relaxed ? de.villagerplus.job.Woodcutting.nearSite(sites, root)
				: root.getX() >= village.minX() && root.getX() <= village.maxX() && root.getY() >= village.minY() && root.getY() <= village.maxY()
					&& root.getZ() >= village.minZ() && root.getZ() <= village.maxZ()) { inVillage++; continue; }
			if (!de.villagerplus.job.Woodcutting.matchesRequestedLog(level.getBlockState(root).getBlock(), demandedLogs)) {
				wrongSpecies++;
				if (distance < nearestWrong && de.villagerplus.job.Woodcutting.isHarvestableTree(level, root)) {
					String logPath = BuiltInRegistries.BLOCK.getKey(level.getBlockState(root).getBlock()).getPath();
					if (logPath.endsWith("_log")) {
						nearestWrong = distance;
						nearestWrongStem = logPath.substring(0, logPath.length() - "_log".length());
					}
				}
				continue;
			}
			if (de.villagerplus.job.Woodcutting.treeUnreachable(worker.getUUID(), root, level.getGameTime())) { unreachable++; continue; }
			candidates.add(root.immutable());
		}
		candidates.sort(java.util.Comparator.comparingLong(root -> {
			long ddx = root.getX() - center.getX(), ddz = root.getZ() - center.getZ();
			return ddx * ddx + ddz * ddz;
		}));
		BlockPos street = de.villagerplus.job.Woodcutting.walkBackStreet(level, ref);
		de.villagerplus.job.Woodcutting.startWalkBackSelection();
		BlockPos best = null;
		for (BlockPos root : candidates) {
			if (!de.villagerplus.job.Woodcutting.isHarvestableTree(level, root)) { notHarvestable++; continue; }
			if (planTreeOnRoad(level, root) || planTreeClaimed(level, worker, root)) { blocked++; continue; }
			// A tree whose stand cells cannot walk back to the village street is a strand trap (remembered, not offered again).
			if (!de.villagerplus.job.Woodcutting.walkBackAcceptable(level, street, root)) {
				if (de.villagerplus.job.Woodcutting.walkBackUnsafe(level, street, root)) noWayBack++; else deferred++;
				continue;
			}
			best = root;
			break;
		}
		lastWrongStem = best == null ? nearestWrongStem : null;
		if (best == null && level.getGameTime() >= TREE_SELECT_LOG.getOrDefault(worker.getUUID(), 0L)) {
			TREE_SELECT_LOG.put(worker.getUUID(), level.getGameTime() + 2400L);
			LOGGER.info("Villagers: Repurposed: Baumwahl {} (Radius {}, {}): Staemme {}, geladen-nein {}, im Dorf/Gebaeude {}, falsche Art {}, nicht erntbar {}, Weg/belegt {}, unerreichbar {}, kein Rueckweg {}, zurueckgestellt {}, gesucht {}",
				worker.getName().getString(), radius, relaxed ? "locker" : "streng", seenLogs, unloaded, inVillage, wrongSpecies, notHarvestable, blocked, unreachable, noWayBack, deferred, demandedLogs);
		}
		return best;
	}

	private static final java.util.Map<java.util.UUID, Long> TREE_SELECT_LOG = new java.util.HashMap<>();
	private static String lastWrongStem;

	private static boolean planTreeOnRoad(ServerLevel level, BlockPos root) {
		for (BlockPos pos : BlockPos.betweenClosed(root.offset(-1, -1, -1), root.offset(1, -1, 1))) {
			if (!level.hasChunkAt(pos)) continue;
			BlockState floor = level.getBlockState(pos);
			if (floor.is(Blocks.DIRT_PATH) || floor.is(Blocks.GRAVEL) || floor.is(Blocks.COBBLESTONE)
				|| floor.is(Blocks.SMOOTH_SANDSTONE) || floor.is(Blocks.SPRUCE_PLANKS)) return true;
		}
		return false;
	}

	private static boolean planTreeNeighborhoodLoaded(ServerLevel level, BlockPos root) {
		for (int x = root.getX() - 3; x <= root.getX() + 3; x++)
			for (int z = root.getZ() - 3; z <= root.getZ() + 3; z++)
				if (!level.hasChunkAt(new BlockPos(x, root.getY(), z))) return false;
		return true;
	}

	private static boolean planTreeClaimed(ServerLevel level, Villager worker, BlockPos root) {
		for (Villager resident : level.getEntitiesOfClass(Villager.class, new AABB(root).inflate(4),
			candidate -> candidate != worker && candidate.isAlive())) {
			var work = resident.getAttachedOrElse(de.villagerplus.VillagerPlus.WOODCUTTER_WORK,
				de.villagerplus.job.WoodcutterWork.empty());
			if (work.root().filter(root::equals).isPresent()) return true;
		}
		return false;
	}

	static final int PLAN_STONE_BUFFER = 12;

	static boolean shouldDepositPlanStone(int carried) {
		return carried >= PLAN_STONE_BUFFER;
	}

	private static boolean wantsCobblestone(VillageData data) {
		for (String entry : data.wanted) {
			String path = entry.split("#", 2)[0];
			if (path.equals("minecraft:cobblestone") || path.startsWith("minecraft:cobblestone_")) return true;
		}
		return false;
	}

	/** Prefer a real resident already closest to a safe village street for autonomous milestone work. */
	public static Villager bootstrapCandidate(ServerLevel level, VillageRef ref) {
		BlockPos street = villageReturn(level, ref.bell());
		var candidates = villagers(level, ref).stream().filter(v -> !v.isBaby()
			&& !de.villagerplus.village.Expedition.isAway(v)
			&& !de.villagerplus.village.RailTransport.isPassenger(v)
			&& de.villagerplus.village.Market.shop(ref, v.getUUID()) == null);
		if (street == null) return candidates.min(Comparator.comparing(Villager::getUUID)).orElse(null);
		var target = net.minecraft.world.phys.Vec3.atBottomCenterOf(street);
		return candidates.min(Comparator.<Villager>comparingDouble(v -> v.position().distanceToSqr(target))
			.thenComparing(Villager::getUUID)).orElse(null);
	}

	/** Select the actual worker that JobManager dispatches to MasonJob, preferring an exact mason profession. */
	public static Villager milestoneWorker(ServerLevel level, VillageRef ref) {
		if (ref.data().hasPlan() && ref.data().planBuilder().isPresent()) {
			Entity owner = level.getEntity(ref.data().planBuilder().get());
			if (owner instanceof Villager villager && availablePlanOwner(level, ref, villager, false)) return villager;
		}
		var eligible = villagers(level, ref).stream().filter(v -> !v.isBaby()
			&& !de.villagerplus.village.Expedition.isAway(v)
			&& de.villagerplus.village.Market.shop(ref, v.getUUID()) == null);
		List<Villager> candidates = eligible.toList();
		BlockPos street = villageReturn(level, ref.bell());
		var target = street == null ? null : net.minecraft.world.phys.Vec3.atBottomCenterOf(street);
		Comparator<Villager> byStreet = Comparator.<Villager>comparingDouble(v -> target == null ? 0.0
			: v.position().distanceToSqr(target)).thenComparing(Villager::getUUID);
		Villager exactMason = candidates.stream().filter(Builder::exactMason).min(byStreet).orElse(null);
		if (exactMason != null) return exactMason;
		Villager compatibleWorker = candidates.stream().filter(Builder::compatibleMason)
			.min(byStreet).orElse(null);
		return compatibleWorker != null ? compatibleWorker : bootstrapCandidate(level, ref);
	}

	private static boolean exactMason(Villager villager) {
		var profession = villager.getVillagerData().profession();
		return profession.unwrapKey().map(key -> key.identifier().getPath().contains("mason")
			&& !key.identifier().getPath().contains("miner")).orElse(false);
	}

	private static boolean compatibleMason(Villager villager) {
		return "mason".equals(de.villagerplus.Compat.jobKey(villager.getVillagerData().profession()));
	}

	/** Bauauftrag von aussen abbrechen (Dorftafel): Plan, Wunschliste und Zwischenstaende werden verworfen, das Dorf plant spaeter neu. */
	public static void cancel(VillageRef ref, long now) {
		if (ref.data().hasPlan()) {
			abort(ref.data(), ref, now);
		}
	}

	/** Returns the packed inner radius only when a legacy extension cursor already entered its demolition phase. */
	private static int legacyWallRestoreRadius(VillageData data) {
		if (!Blueprints.WALL.equals(data.planType)) return 0;
		int outerRadius = data.planVariant & 0xFF;
		int innerRadius = (data.planVariant >>> 8) & 0xFF;
		if (outerRadius <= 0 || outerRadius > Development.MAX_WALL_RADIUS
			|| innerRadius < Config.WALL_MIN_VILLAGE_RADIUS || innerRadius > Development.MAX_WALL_RADIUS
			|| innerRadius == outerRadius || data.planStep <= 8 * outerRadius) return 0;
		return innerRadius;
	}

	private static void abort(VillageData d, VillageRef ref, long now) {
		if (Blueprints.HOUSE.equals(d.planType)) {
			StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
			LOGGER.info("Villagers: Repurposed: Hausplan-Abbruch Glocke {} Ursprung {} Schritt {} Tick {} letzter Fortschritt {} Grund {} Aufrufer {}:{}",
				ref.bell().toShortString(), d.planOrigin, d.planStep, now, d.planProgress, why, caller.getMethodName(), caller.getLineNumber());
		}
		int restoreWallRadius = legacyWallRestoreRadius(d);
		ConstructionSite site = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		VillageData.BuildProject cleanup = restoreWallRadius > 0 ? null : cleanupRecordForAbort(d, site, now);
		if (d.planOrigin.isPresent()) {
			long planKey = d.planOrigin.get().asLong();
			STAND_BEST.remove(planKey);
			STAND_MOVE_ANCHOR.remove(planKey);
			STAND_BEST_NODES.remove(planKey);
			STAND_FAILURE_SINCE.remove(planKey);
			PALETTES.remove(planKey);
			Map<Long, Integer> dug = PREP_RECORD.remove(planKey);
			if (dug != null && d.planStep == 0 && ref.bellEntity().getLevel() instanceof ServerLevel sl) {
				// Bau kam nicht ueber die Vorbereitung hinaus: Gelaende wieder herrichten (keine verlassenen Gruben).
				Prep.restore(sl, d.planOrigin.get(), dug);
			}
			STEPS.remove(planKey);
			PLAN_DIAGNOSTICS.remove(planKey);
			STYLES.remove(planKey);
			REPAIRS.remove(planKey);
			REPAIRS.remove(planKey ^ WALL_KEY);
			FOOT.remove(planKey);
		}
		boolean farm = d.planType.equals(Blueprints.FARM) || d.planType.equals(Blueprints.PEN);
		BlockPos restoreWallOrigin = d.planOrigin.orElse(ref.bell());
		d.wanted.clear();
		d.clearPlan();
		if (cleanup != null) {
			// clearPlan removes the active cursor; reinsert a cleanup-only record with its exact real stock,
			// carrier reservations and temporary-block ownership before returning from the UI callback.
			d.putBuildProject(cleanup);
		} else if (site != null && site.origin().equals(restoreWallOrigin) && d.buildProject(site.origin()).isEmpty()) {
			ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		}
		if (restoreWallRadius > 0) {
			// Legacy extensions stored old-wall demolition progress after the outer-ring cursor.
			// Keep a normal paid wall plan alive so a stale abort cannot strand a partially removed inner wall.
			d.planType = Blueprints.WALL;
			d.planOrigin = Optional.of(restoreWallOrigin);
			d.planStep = 0;
			d.planVariant = restoreWallRadius;
			d.planProgress = now;
			d.nextBuild = now;
			LOGGER.warn("Legacy wall extension at {} was aborted after inner-ring demolition began; resuming paid restoration at radius {}.",
				ref.bell().toShortString(), restoreWallRadius);
			ref.dirty();
			return;
		}
		if (farm) {
			d.extra.nextFarm = now + 1200;
		} else {
			d.nextBuild = now + 1200;
		}
		d.syncPrimaryProjectView();
		restorePrimaryProject(ref, d);
		ref.dirty();
	}

	/** Snapshot owned materials before clearPlan can remove the only persistent project record. */
	static VillageData.BuildProject cleanupRecordForAbort(VillageData data, ConstructionSite attached, long now) {
		if (data == null || !data.hasPlan()) return null;
		BlockPos origin = data.planOrigin.orElseThrow();
		VillageData.BuildProject previous = data.buildProject(origin).orElseGet(() -> new VillageData.BuildProject(
			data.planType, origin, data.planDir, data.planStep, data.planVariant, data.planSource, data.planProgress,
			data.planBuilder(), data.planBuilderUnavailableSince(), List.copyOf(data.wanted), VillageData.ProjectHaul.empty(),
			data.extra.planMaterialWait, Optional.empty(), Optional.of(fallbackProjectBounds(data.planType, origin, data.planVariant, data.planSource.isPresent())),
			false, false));
		ConstructionSite savedSite = attached != null && attached.origin().equals(origin)
			? attached : previous.construction().filter(site -> site.origin().equals(origin)).orElse(null);
		List<ItemStack> stock = previous.siteStock();
		List<String> manifest = previous.cargoManifest();
		if ((savedSite == null || savedSite.temporary().isEmpty()) && stock.isEmpty() && manifest.isEmpty()) return null;
		VillageData.PlanBounds bounds = previous.bounds().orElseGet(() -> fallbackProjectBounds(previous.type(), origin, previous.variant(), previous.source().isPresent()));
		return new VillageData.BuildProject("", origin, 0, 0, 0, Optional.empty(), now, previous.owner(),
			previous.ownerUnavailableSince(), List.of(), new VillageData.ProjectHaul(stock, manifest), Optional.empty(),
			Optional.ofNullable(savedSite), Optional.of(bounds), false, true);
	}

	// --- Planung --------------------------------------------------------------------------------

	private static List<Villager> villagers(ServerLevel level, VillageRef ref) {
		return de.villagerplus.village.Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v));
	}

	/** The population cap blocks growth, not a real bed deficit; reserve capacity is only added below the cap. */
	static boolean needsHouse(int population, long homeless, long beds) {
		if (population <= 0) return false;
		if (beds < population) return true;
		if (beds > population) return false;
		return homeless > 0 || de.villagerplus.village.Development.needsHousing(beds, population);
	}

	/** Farmer planning is independent of the mason's queue; public material storage can fund the first field before the granary is finished. */
	public static String nextFarmerPlanType(int population, int completedFarms, int projectedFarms, boolean penMissing,
		boolean activeFarm, boolean activePen, boolean publicStorageReady) {
		return nextFarmerPlanType(population, completedFarms, projectedFarms, penMissing,
			activeFarm, activePen, publicStorageReady, false);
	}

	/** A real nearby herd may start the first paid pen even when an inaccessible starter field cannot begin. */
	static String nextFarmerPlanType(int population, int completedFarms, int projectedFarms, boolean penMissing,
		boolean activeFarm, boolean activePen, boolean publicStorageReady, boolean eligibleLivestock) {
		if (population <= 0) return null;
		boolean firstFieldUnderway = completedFarms == 0 && projectedFarms == 1 && activeFarm;
		boolean firstFieldCompleted = completedFarms >= 1 && !activeFarm;
		if (penMissing && population >= MIN_INITIAL_PEN_POPULATION
			&& (firstFieldUnderway || firstFieldCompleted || eligibleLivestock)
			&& !activePen && publicStorageReady) return Blueprints.PEN;
		if (completedFarms == 0 && projectedFarms == 0 && !publicStorageReady) return null;
		if (!activeFarm && projectedFarms < Math.min(Config.MAX_FARMS, 1 + population / 4)) return Blueprints.FARM;
		return null;
	}

	static long nextFarmerBuildAt(String completedType, int completedFarms, boolean penMissing, long now) {
		return Blueprints.FARM.equals(completedType) && completedFarms == 1 && penMissing
			? now : now + Config.BUILD_COOLDOWN;
	}

	/** The first visible plot is wheat and starts at its paid 3x3 minimum; later fields use the best fully funded crop. */
	public static int farmPlanVariant(int projectedFarms, Container stock) {
		if (projectedFarms < 0 || stock == null) return -1;
		if (projectedFarms == 0) {
			int wheatSeeds = JobUtil.count(stock, stack -> stack.is(Items.WHEAT_SEEDS));
			if (wheatSeeds >= 24) return 20;
			return wheatSeeds >= Config.FARM_MIN_SEEDS ? 10 : -1;
		}
		return FarmPlanSizing.chooseVariant(projectedFarms, stock);
	}

	/** Reserves every still-unbuilt crop seed from shared storage in the saved project stock. */
	static boolean stageFarmSeeds(Container publicStock, Container projectStock, List<Step> steps, int fromStep) {
		return stageFarmSeeds(publicStock, projectStock, steps, fromStep, null);
	}

	private static boolean stageFarmSeeds(Container publicStock, Container projectStock, List<Step> steps,
			int fromStep, ServerLevel level) {
		if (publicStock == null || projectStock == null || steps == null) return false;
		Map<Item, Integer> demand = new HashMap<>();
		for (int i = Math.max(0, fromStep); i < steps.size(); i++) {
			Step step = steps.get(i);
			if (!isFarmSeed(step.item()) || step.free() || step.linkedTo() != null || step.cost() <= 0) continue;
			if (level != null && level.getBlockState(step.pos()).is(Block.byItem(step.item()))) continue;
			demand.merge(step.item(), step.cost(), Integer::sum);
		}
		if (demand.isEmpty()) return true;

		Map<Item, Integer> missing = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : demand.entrySet()) {
			Item seed = entry.getKey();
			int inProject = JobUtil.count(projectStock, stack -> stack.is(seed));
			int amount = Math.max(0, entry.getValue() - inProject);
			if (amount == 0) continue;
			if (JobUtil.count(publicStock, stack -> stack.is(seed)) < amount || !canStoreFarmSeeds(projectStock, seed, amount)) {
				return false;
			}
			missing.put(seed, amount);
		}

		List<FarmSeedTransfer> moved = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
			Item seed = entry.getKey();
			int amount = entry.getValue();
			int taken = JobUtil.take(publicStock, stack -> stack.is(seed), amount);
			if (taken != amount) {
				if (taken > 0) storeFarmSeeds(publicStock, seed, taken);
				rollbackFarmSeedTransfers(publicStock, projectStock, moved);
				return false;
			}
			int beforeInProject = JobUtil.count(projectStock, stack -> stack.is(seed));
			if (!storeFarmSeeds(projectStock, seed, taken)) {
				int inserted = Math.max(0, JobUtil.count(projectStock, stack -> stack.is(seed)) - beforeInProject);
				if (inserted > 0) JobUtil.take(projectStock, stack -> stack.is(seed), inserted);
				storeFarmSeeds(publicStock, seed, taken);
				rollbackFarmSeedTransfers(publicStock, projectStock, moved);
				return false;
			}
			moved.add(new FarmSeedTransfer(seed, taken));
		}
		return true;
	}

	private static boolean canStoreFarmSeeds(Container stock, Item seed, int amount) {
		int capacity = 0;
		ItemStack sample = new ItemStack(seed);
		for (int i = 0; i < stock.getContainerSize() && capacity < amount; i++) {
			ItemStack existing = stock.getItem(i);
			if (existing.isEmpty()) capacity += sample.getMaxStackSize();
			else if (ItemStack.isSameItemSameComponents(existing, sample)) {
				capacity += existing.getMaxStackSize() - existing.getCount();
			}
		}
		return capacity >= amount;
	}

	private static boolean storeFarmSeeds(Container stock, Item seed, int amount) {
		if (amount <= 0) return true;
		if (!canStoreFarmSeeds(stock, seed, amount)) return false;
		int remaining = amount;
		ItemStack sample = new ItemStack(seed);
		for (int i = 0; i < stock.getContainerSize() && remaining > 0; i++) {
			ItemStack existing = stock.getItem(i);
			if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, sample)) continue;
			int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
			if (moved > 0) {
				existing.grow(moved);
				remaining -= moved;
			}
		}
		for (int i = 0; i < stock.getContainerSize() && remaining > 0; i++) {
			if (!stock.getItem(i).isEmpty()) continue;
			int moved = Math.min(remaining, sample.getMaxStackSize());
			stock.setItem(i, new ItemStack(seed, moved));
			remaining -= moved;
		}
		if (remaining > 0) return false;
		stock.setChanged();
		return true;
	}

	private record FarmSeedTransfer(Item item, int count) { }

	private static void rollbackFarmSeedTransfers(Container publicStock, Container projectStock, List<FarmSeedTransfer> moved) {
		for (FarmSeedTransfer transfer : moved) {
			int taken = JobUtil.take(projectStock, candidate -> candidate.is(transfer.item()), transfer.count());
			if (taken > 0) storeFarmSeeds(publicStock, transfer.item(), taken);
		}
	}

	private static boolean isFarmSeed(Item item) {
		if (item == null) return false;
		for (int kind = 0; kind < Blueprints.seedKinds(); kind++) {
			if (Blueprints.seed(kind).equals(item)) return true;
		}
		return false;
	}

	static boolean farmStepRequiresPayment(String type, Step step) {
		return step != null && (!step.optional()
			|| (Blueprints.FARM.equals(type) && step.cost() > 0 && isFarmSeed(step.item())));
	}

	/** Stable outward growth: start beyond the old 10-block ring, then account for both village scale signals. */
	static int houseExpansionMinRadius(long existingHomePois, int outerHomeRadius, int maxRadius) {
		int boundedMax = Math.min(88, Math.max(10, maxRadius));
		long byCount = 18L + Math.max(0L, existingHomePois - 1L) * 2L;
		long beyondEdge = Math.max(18L, Math.max(0, outerHomeRadius) + 8L);
		return (int) Math.min(boundedMax, Math.max(10L, Math.max(byCount, beyondEdge)));
	}

	private static int houseExpansionMinRadius(ServerLevel level, VillageRef ref) {
		List<BlockPos> homePois = level.getPoiManager().getInRange(home -> home.is(PoiTypes.HOME), ref.bell(),
			Config.VILLAGE_RADIUS, PoiManager.Occupancy.ANY).map(poi -> poi.getPos().immutable()).toList();
		int outerHomeRadius = 0;
		for (BlockPos home : homePois) {
			long dx = (long) home.getX() - ref.bell().getX();
			long dz = (long) home.getZ() - ref.bell().getZ();
			outerHomeRadius = Math.max(outerHomeRadius, (int) Math.floor(Math.sqrt(dx * dx + dz * dz)));
		}
		return houseExpansionMinRadius(Math.max(homePois.size(), ref.data().houses), outerHomeRadius,
			Math.min(88, Config.VILLAGE_RADIUS - 8));
	}

	/** Every newly selected catalog house with a bed deficit may use the parcel, regardless of prior house count. */
	static boolean shouldPreferPreparedExpansionParcel(String type, int variant, boolean hasLegacySource,
			int completedHouseCount, boolean needsBeds, boolean parcelReady) {
		return completedHouseCount >= 0 && needsBeds && parcelReady && Blueprints.HOUSE.equals(type)
			&& HouseDesignCatalog.usesCatalog(variant, hasLegacySource);
	}

	static boolean houseCandidateWithinSearchRadius(BlockPos bell, BlockPos candidate, int minRadius, int maxRadius) {
		if (bell == null || candidate == null || minRadius < 0 || maxRadius < minRadius) return false;
		long dx = (long) candidate.getX() - bell.getX();
		long dz = (long) candidate.getZ() - bell.getZ();
		long distanceSquared = dx * dx + dz * dz;
		return distanceSquared >= (long) minRadius * minRadius && distanceSquared <= (long) maxRadius * maxRadius;
	}

	/** Validate the ready road-prepared pad as an ordinary house candidate; block work remains in the persisted build plan. */
	private static BlockPos preparedExpansionHouseCandidate(Ctx c, VillageRef ref, Development.ExpansionParcel parcel,
			int variant, Architect.Design design, VillageStyle style, int minRadius, int maxRadius) {
		if (parcel == null) return null;
		BlockPos center = parcel.center();
		BlockPos candidate = new BlockPos(center.getX(), parcel.surfaceY(), center.getZ());
		if (!Development.inExpansionParcel(center, parcel.half(), candidate)
			|| !houseCandidateWithinSearchRadius(ref.bell(), candidate, minRadius, maxRadius)
			|| !Sites.villageLevel(Sites.villageReferenceY(c.level(), ref), candidate.getY())) return null;
		BlockPos anchor = naturalHouseAnchor(c.level(), Blueprints.HOUSE, variant, candidate);
		if (anchor == null || !houseCandidateWithinSearchRadius(ref.bell(), anchor, minRadius, maxRadius)
			|| !siteCandidateFits(c, ref, Blueprints.HOUSE, variant, design, style, candidate, ref.bell())) return null;
		return anchor;
	}

	private static boolean startPlan(Ctx c, VillageRef ref, Container box, boolean mason, Container siteStock, List<String> cargoManifest) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		ConstructionSite staleSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (staleSite != null) {
			ConstructionSite remaining = ConstructionSite.cleanup(level, staleSite, box, ref);
			if (!remaining.temporary().isEmpty()) {
				ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, remaining);
				return false;
			}
			ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		}
		List<Villager> all = villagers(level, ref);
		int pop = all.size();
		if (d.popAtLastHouse == 0) {
            var net=de.villagerplus.village.Network.of(ref.bellEntity());
            de.villagerplus.village.Network.save(ref.bellEntity(),net.withCooldown("population_settle_until",c.now()+1200));
			d.popAtLastHouse = pop;
			ref.dirty();
		}
		long homeless = all.stream().filter(v -> !Housing.hasHome(v)).count();
		String type;
		int variant = 0;
		boolean needsBeds = false;
		if (mason) {
			if (c.now() - lastPlanDiag > 2400) {
				lastPlanDiag = c.now();
				if (homeless > 0) {
					var pois = level.getPoiManager();
					long beds = pois.getCountInRange(h -> h.is(PoiTypes.HOME), ref.bell(), Config.VILLAGE_RADIUS, PoiManager.Occupancy.ANY);
					long freeBeds = pois.getCountInRange(h -> h.is(PoiTypes.HOME), ref.bell(), Config.VILLAGE_RADIUS, PoiManager.Occupancy.HAS_SPACE);
					long homelessAdults = all.stream().filter(v -> !v.isBaby() && !Housing.hasHome(v)).count();
					LOGGER.info("Villagers: Repurposed: Betten-Diagnose bei {} (Tick {}): Betten {}, freie POI-Tickets {}, Erwachsene ohne HOME {}, insgesamt ohne HOME {}, Hausbedarf {}",
						ref.bell().toShortString(), c.now(), beds, freeBeds, homelessAdults, homeless, needsHouse(pop, homeless, beds));
				}
				LOGGER.info("Villagers: Repurposed: Planpruefung: Einwohner {}, obdachlos {}, naechster Bau in {} Ticks, Kiste hat Platz {}, Lager {}, aktive Projekte {}, naechster Sonderbau {}",
					pop, homeless, d.nextBuild - c.now(), JobUtil.hasEmptySlot(box), d.extra.lager.isPresent(),
					d.buildProjects.stream().filter(VillageData.BuildProject::active).map(VillageData.BuildProject::type).toList(),
					nextSpecial(ref, pop));
			}
			if (cooldownBlocksBuild(ref, pop, homeless, c.now())) {
				return false;
			}
			long homes = level.getPoiManager().getCountInRange(h -> h.is(PoiTypes.HOME), ref.bell(), Config.VILLAGE_RADIUS,
				PoiManager.Occupancy.ANY);
			if (d.popAtLastHouse == 0) { d.popAtLastHouse = pop; ref.dirty(); }
			boolean needHouse = needsHouse(pop, homeless, homes);
			// Dev hook only: without bed demand (CTOV villages start with more beds than residents) no house is ever planned.
			boolean forcedHouseDemand = FORCE_HOUSE_STEP_SEARCH && pop >= 2 && !d.hasBuildProjectType(Blueprints.HOUSE);
			if (forcedHouseDemand) needHouse = true;
			needsBeds = needHouse;
			String special = nextSpecial(ref, pop);
			boolean deferStorageExpansion = Development.deferStorageExpansion(special);
			boolean firstPublicStorageMissing = firstPublicStorageMissing(level, d);
			boolean storageNeedsExpansion = Development.needsStorage(box, d, level) && storageFree(c, ref);
			if (forcedHouseDemand) {
				type = Blueprints.HOUSE;
			} else if (specialSiteSearchStalled(ref, special, c.now(), needHouse, homeless) && !d.hasBuildProjectType(Blueprints.HOUSE)) {
				// A milestone without any site must not starve the homeless: houses go first while the search keeps trying.
				type = Blueprints.HOUSE;
			} else if (shouldExpandStorageUnderPressure(null, special, storageNeedsExpansion, firstPublicStorageMissing, needHouse)
				&& !d.hasBuildProjectType(Blueprints.STORAGE)) {
				type = Blueprints.STORAGE;
			} else if (Blueprints.GRANARY.equals(special) && !d.hasBuildProjectType(special)) {
				// The first food depot starts even while some generated residents still lack a bed.
				type = special;
			} else if (shouldStartEarlyPublicStorage(pop, firstPublicStorageMissing, special,
				d.hasBuildProjectType(Blueprints.GRANARY), d.activeBuildProjectCount(), d.hasBuildProjectType(Blueprints.STORAGE))) {
				// A compact first shed may use the third slot while the granary is underway; tick() checks owner and crew capacity.
				type = Blueprints.STORAGE;
			} else if (!deferStorageExpansion && Development.needsStorage(box, d, level) && pop >= 4 && storageFree(c, ref)
				&& !d.hasBuildProjectType(Blueprints.STORAGE)) {
				// Dorfkiste ist voll: zuerst ein Lagerschuppen, damit nichts liegen bleibt oder verloren geht.
				type = Blueprints.STORAGE;
			} else if (special != null && !d.hasBuildProjectType(special)) {
				// Toolshed, school and kitchen follow even when a few generated villagers have no recorded bed.
				type = special;
			} else if (needHouse && !d.hasBuildProjectType(Blueprints.HOUSE)) {
				type = Blueprints.HOUSE;
			} else if (homeless == 0 && !d.townHallBuilt && pop >= Config.TOWNHALL_POP && !d.hasBuildProjectType(Blueprints.TOWNHALL)) {
				type = Blueprints.TOWNHALL;
			} else if (!d.wellBuilt && pop >= 3 && !d.hasBuildProjectType(Blueprints.WELL)) {
				type = Blueprints.WELL;
			} else if (homeless == 0 && firstPublicStorageMissing && pop >= Config.STORAGE_POP
				&& !d.hasBuildProjectType(Blueprints.STORAGE)) {
				type = Blueprints.STORAGE;
			} else if (homeless == 0 && d.townHallBuilt && pop >= Config.WALL_POP && (!d.wallBuilt || wallTooSmall(level, ref))
				&& !d.hasBuildProjectType(Blueprints.WALL)) {
				return startWall(c, ref, box, all);
			} else if (homeless == 0 && Wealth.tier(Wealth.score(level, ref, pop)) > d.extra.decor
				&& !d.hasBuildProjectType(Blueprints.DECOR)) {
				type = Blueprints.DECOR;
				variant = d.extra.decor;
			} else {
				return false;
			}
		} else {
			// Farmers run separately from the mason; only the first field waits for the completed public food depot.
			if (c.now() < d.extra.nextFarm) return false;
			boolean savedFarmPlan = d.hasBuildProjectType(Blueprints.FARM)
				|| d.hasPlan() && Blueprints.FARM.equals(d.planType);
			boolean savedPenPlan = d.hasBuildProjectType(Blueprints.PEN)
				|| d.hasPlan() && Blueprints.PEN.equals(d.planType);
			int activeFarms = (int) d.buildProjects.stream().filter(project -> project.active() && project.type().equals(Blueprints.FARM)).count();
			if (d.hasPlan() && Blueprints.FARM.equals(d.planType)
				&& d.buildProjects.stream().noneMatch(project -> project.active() && project.type().equals(Blueprints.FARM))) activeFarms++;
			int projectedFarms = d.farms + activeFarms;
			boolean publicStorageReady = !Balance.storage(level, ref).isEmpty();
			boolean eligibleInitialLivestock = d.extra.pen.isEmpty() && projectedFarms == 0
				&& pop >= MIN_INITIAL_PEN_POPULATION && !savedPenPlan && !savedFarmPlan
				&& publicStorageReady
				&& Livestock.hasEligibleLivestockForInitialPenCached(level, ref);
			// A pen that found no site must not shadow the starter field: until the retry time the farmer plans fields only.
			boolean penSiteRetryLater = c.now() < PEN_SITE_BLOCKED_UNTIL.getOrDefault(ref.bell().asLong(), 0L);
			String farmerPlan = nextFarmerPlanType(pop, d.farms, projectedFarms, d.extra.pen.isEmpty() && !penSiteRetryLater,
				savedFarmPlan, savedPenPlan, publicStorageReady, eligibleInitialLivestock && !penSiteRetryLater);
			if (Blueprints.PEN.equals(farmerPlan)) {
				type = Blueprints.PEN;
			} else if (Blueprints.FARM.equals(farmerPlan)) {
				type = Blueprints.FARM;
				variant = farmPlanVariant(projectedFarms, box);
				if (variant < 0) {
					if (d.farms == 0 && activeFarms == 0) c.st().buildCheckAt = c.now() + FARM_PLAN_RETRY_TICKS;
					return false;
				}
			} else {
				return false;
			}
		}
		// The POI edge and managed house count are sampled once for this complete, synchronous placement attempt.
		int houseMinRadiusForPlan = type.equals(Blueprints.HOUSE) ? houseExpansionMinRadius(level, ref) : 0;

		VillageStyle style = VillageStyle.detect(level, ref.bell(), c.v());
		Direction dir = null;
		BlockPos site = null;
		BlockPos source = null;
		Optional<Development.ExpansionParcel> preparedExpansionParcel = type.equals(Blueprints.HOUSE) && needsBeds
			? Roads.preparedExpansionParcel(ref) : Optional.empty();
		List<Step> steps = null;
		VillageData.PlanBounds plannedHouseLotBounds = null;
		List<Integer> constructionPhaseEnds = List.of();
		List<String> constructionPaletteIds = List.of();
		Architect.Design design = null;

		if (templated(type)) {
			Architect.SizeTier requestedTier = nextSizeTier(ref, type);
			int templateMin = type.equals(Blueprints.HOUSE) ? houseMinRadiusForPlan : 6;
			int templateMax = type.equals(Blueprints.HOUSE) ? Math.min(88, Config.VILLAGE_RADIUS - 8) : 28;
			List<BlockPos> beds = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), ref.bell(), Config.VILLAGE_RADIUS,
				PoiManager.Occupancy.ANY).map(r -> r.getPos().immutable()).collect(Collectors.toCollection(ArrayList::new));
			for (int attempt = 0; attempt < 40 && site == null && !beds.isEmpty(); attempt++) {
				BlockPos cand = ZoneAwareSites.candidate(level, ref, type, c.v().getRandom(), templateMin, templateMax);
				if (cand == null) {
					continue;
				}
				// Vorlagen aus den Haeusern, die dem Bauplatz am naechsten stehen -> der Stil der Umgebung.
				// Das naechste gueltige Haus bestimmt die Vorlage; zufaelliges Mischen liess Neubauten optisch aus
				// einem anderen Viertel stammen, obwohl dort passende lokale Vorlagen standen.
				List<BlockPos> nearest = beds.stream().sorted(Comparator.comparingDouble(b -> b.distSqr(cand))).limit(6).collect(Collectors.toCollection(ArrayList::new));
				int mirror = c.v().getRandom().nextBoolean() ? 1 : 0;
				for (BlockPos bed : nearest) {
					HouseTemplate t = TEMPLATES.computeIfAbsent(bed.asLong(), k -> Optional.ofNullable(HouseTemplate.capture(level, bed, ref.bell()))).orElse(null);
					if (t == null || t.sizeTier() != requestedTier) {
						continue;
					}
					Direction towards = Sites.facingTowards(cand, ref.bell());
					HouseTemplate.Bounds b = t.bounds(towards, mirror == 1);
					BlockPos anchor = ZoneAwareSites.tolerantAnchor(level, ref, type, cand, b.minX(), b.maxX(), b.minZ(), b.maxZ(), b.maxY() + 2);
					if (anchor != null && t.supportedFoundation(level, anchor, towards, mirror == 1)) {
						List<Step> candidateSteps = t.steps(anchor, towards, Config.MATERIAL_COST_PERCENT, mirror == 1, bl -> bl, type.equals(Blueprints.HOUSE));
						List<Step> candidatePlan = candidateSteps;
						int yardStyle = -1;
						if (type.equals(Blueprints.HOUSE)) {
							if (!housePlotFits(level, anchor, candidateSteps, towards, b.maxY() + 2)) continue;
							yardStyle = Paths.yardStyle(level, anchor, towards);
							List<Step> yard = Paths.yardSteps(level, anchor, towards, yardStyle);
							if (!Paths.houseYardFits(level, candidateSteps, anchor, towards, yardStyle)) continue;
							candidatePlan = appendHouseYard(candidateSteps, yard);
						}
						if (!ZoneAwareSites.allowsPlan(ref, type, candidatePlan) || planSiteConflicts(ref, candidatePlan, anchor)) continue;
						site = anchor;
						dir = towards;
						source = t.sourceBed;
						variant = mirror;
						steps = candidateSteps;
						break;
					}
				}
			}
			if (site == null) {
				// Kein passendes Vorlagenhaus dieser Groessenklasse: gleicher Dorfstil, aber mit dem angeforderten Grundriss.
				String fallbackType = type;
				int fallbackVariant = variant;
				if (type.equals(Blueprints.HOUSE)) {
					int fallbackMin = houseMinRadiusForPlan;
					int fallbackMax = Math.min(88, Config.VILLAGE_RADIUS - 8);
					for (int attempt = 0; attempt < 40 && site == null; attempt++) {
						BlockPos candidate = ZoneAwareSites.candidate(level, ref, type, c.v().getRandom(), fallbackMin, fallbackMax);
						if (candidate == null) continue;
						Direction candidateDir = Sites.facingTowards(candidate, ref.bell());
						long seed = ((long) c.v().getRandom().nextInt(1 << 22) << 6) | Palette.profileCode(level, ref.bell());
						seed = Architect.withSizeTier(seed, requestedTier);
						int candidateVariant = newPlanVariant(type, seed);
						Architect.Design candidateDesign = designForPlan(type, candidateVariant);
						int candidateHalf = siteHalf(type, candidateVariant, candidateDesign);
						int candidateHeadroom = siteHeadroom(type, candidateVariant, candidateDesign);
						BlockPos anchor = ZoneAwareSites.tolerantAnchor(level, ref, type, candidate, -candidateHalf, candidateHalf,
							-candidateHalf, candidateHalf, candidateHeadroom);
						if (anchor == null) continue;
						Palette palette = detectForPlan(level, ref, c.v(), seed, anchor);
						List<Step> candidateSteps = Architect.build(candidateDesign, palette, anchor, candidateDir);
						List<Step> candidatePlan = candidateSteps;
						int yardStyle = Paths.yardStyle(level, anchor, candidateDir);
						List<Step> yard = Paths.yardSteps(level, anchor, candidateDir, yardStyle);
						if (!housePlotFits(level, anchor, candidateSteps, candidateDir, candidateHeadroom)
							|| !Paths.houseYardFits(level, candidateSteps, anchor, candidateDir, yardStyle)) continue;
						candidatePlan = appendHouseYard(candidateSteps, yard);
						if (!ZoneAwareSites.allowsPlan(ref, fallbackType, candidatePlan) || planSiteConflicts(ref, candidatePlan, anchor)) continue;
						site = anchor;
						dir = candidateDir;
						steps = candidateSteps;
						variant = candidateVariant;
						PALETTES.put(anchor.asLong(), palette);
						constructionPaletteIds = palette.ids();
					}
				} else {
					site = ZoneAwareSites.find(level, ref, type, c.v().getRandom(), 10, 40, Blueprints.half(type), Blueprints.headroom(type), 30,
						p -> {
							List<Step> candidateSteps = Blueprints.create(fallbackType, style, p, Sites.facingTowards(p, ref.bell()), fallbackVariant);
							return ZoneAwareSites.allowsPlan(ref, fallbackType, candidateSteps) && !planSiteConflicts(ref, candidateSteps, p)
								&& routeConnected(c, ref, p);
						});
				}
				if (site == null) {
					c.st().buildCheckAt = Math.max(c.st().buildCheckAt, c.now() + FAILED_SITE_SWEEP_RETRY_TICKS);
					return false;
				}
				if (steps == null) {
					dir = Sites.facingTowards(site, ref.bell());
					steps = Blueprints.create(type, style, site, dir, variant);
				}
			}
		} else if (civicSite(type)) {
			CivicSiteSearch search = civicSiteSearch(c, ref, type);
			if (c.now() < search.retryAt) {
				c.st().buildCheckAt = search.retryAt;
				return false;
			}
			variant = Architect.withRoleMarkerVersion(kindOf(type), search.variants[search.variantIndex]);
			design = designForPlan(type, variant);
			int half = design.half();
			int headroom = design.headroom();
			int min = type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type) ? 6 : 10;
			int max = Config.VILLAGE_RADIUS - 8;
			String selectedType = type;
			int selectedVariant = variant;
			Architect.Design selectedDesign = design;
			BlockPos civicAnchor = civicSiteAnchor(level, ref);
			// Slots 0-15: column scan reasons (Sites.SCAN_REJECT delta of this search only), 16-21: plan checks.
			int[] civicReject = CIVIC_REJECT_BY_VILLAGE.computeIfAbsent(ref.bell().asLong(), k -> new int[3][23])[search.terraced ? 2 : search.tolerant ? 1 : 0];
			int[] scanBefore = Sites.SCAN_REJECT.clone();
			java.util.function.Predicate<BlockPos> fits = p -> {
				List<Step> candidateSteps = sitingSteps(c, ref, selectedType, selectedVariant, selectedDesign, style, p, civicAnchor);
				civicReject[16]++;
				if (rejectedPlot(ref, p)) { civicReject[17]++; return false; }
				if (!ZoneAwareSites.allowsPlan(ref, selectedType, candidateSteps)) { civicReject[18]++; return false; }
				if (planSiteConflicts(ref, candidateSteps, p)) { civicReject[19]++; return false; }
				if (search.terraced) {
					// Last-resort pass: rooted natural trees count as clearable vegetation, but only when the site
					// preparation can really remove every one of them (otherwise the plan would stall or skip steps).
					BlockPos[] prepBox = civicPrepBox(p, candidateSteps, selectedDesign.headroom());
					NaturalSitePreparation.Status preparable = NaturalSitePreparation.inspect(level, prepBox[0], prepBox[1],
						MAX_CIVIC_SITE_OBSTACLES).status();
					if (preparable != NaturalSitePreparation.Status.READY && preparable != NaturalSitePreparation.Status.CLEARABLE) {
						civicReject[21]++;
						return false;
					}
				}
				if (!routeConnected(c, ref, p)) { civicReject[20]++; return false; }
				civicReject[22]++;
				return true;
			};
			Sites.margin = 2;
			Sites.SearchPage page = Sites.findSystematicPage(level, ref.bell(), min, max,
				-half, half, -half, half, headroom + (search.tolerant ? 1 : 0), search.terraced ? Sites.TERRACED_CIVIC_RELIEF : search.tolerant ? 1 : 0, search.terraced, search.nextCandidate,
				CIVIC_SITE_CANDIDATE_BUDGET, search.phaseSeed, search.visitedColumns(), fits, civicAnchor.getY());
			for (int i = 0; i < 16; i++) civicReject[i] += Sites.SCAN_REJECT[i] - scanBefore[i];
			search.nextCandidate = page.nextCandidate();
			persistCivicSiteSearch(ref, search);
			Sites.margin = 3;
			if (page.site() == null) {
				noteSpecialSiteSearchFailure(ref, c.now());
				boolean completedCycle = page.complete() && search.advancePass(c.now());
				if (page.complete()) persistCivicSiteSearch(ref, search);
				c.st().buildCheckAt = search.retryAt > c.now() ? search.retryAt : c.now() + CIVIC_SITE_RETRY_TICKS;
				if (type.equals(Blueprints.STORAGE) && completedCycle) STORAGE_BLOCKED.put(ref.bell().asLong(), c.now() + 24000L);
				if (c.now() - CIVIC_DIAG_AT.getOrDefault(ref.bell().asLong(), Long.MIN_VALUE / 2) > 1200) {
					CIVIC_DIAG_AT.put(ref.bell().asLong(), c.now());
					LOGGER.info("Villagers: Repurposed: Bauplatzsuche fuer '{}' (Glocke {}) fortgesetzt (Variante {}, {}, Kandidat {}/{}, besuchte Spalten {}, halbe Kante {}, Hoehe {}), noch kein passender Standort (Bezugshoehe {}, Glocke y {}, Strassenanker {})",
						type, ref.bell().toShortString(), search.variantIndex + 1, search.terraced ? "terrassiertes Gelaende" : search.tolerant ? "unebenes Gelaende" : "ebener Bauplatz",
						search.nextCandidate, Sites.systematicCandidateCount(min, max), search.visitedColumns().size(), half, headroom,
						civicAnchor.getY(), ref.bell().getY(), civicAnchor.toShortString());
					if (design != null) {
						StringBuilder extents = new StringBuilder();
						for (Direction facing : Direction.Plane.HORIZONTAL) {
							extents.append(facing.getName()).append(java.util.Arrays.toString(civicPlanExtents(c, ref, selectedType, selectedVariant, selectedDesign, style, civicAnchor, facing))).append(' ');
						}
						LOGGER.info("Villagers: Repurposed: Bauplatzsuche '{}' (Glocke {}) Plan-Ausdehnung [minX, maxX, minZ, maxZ] je Tuerrichtung (Suchquadrat +-{}): {}", type, ref.bell().toShortString(), half, extents);
					}
					// Per pass since the last report: column reasons, then the full-plan checks of the columns that survived.
					String[] passNames = {"eben", "tolerant", "terrassiert"};
					int[][] passes = CIVIC_REJECT_BY_VILLAGE.get(ref.bell().asLong());
					for (int pass = 0; pass < passes.length; pass++) {
						if (java.util.Arrays.stream(passes[pass]).sum() == 0) continue;
						LOGGER.info("Villagers: Repurposed: Bauplatz-Ablehnung '{}' (Glocke {}) Pass {}: Spalten {} {} Plan [geprueft, Parzelle, Zone, Konflikt, Weg, Baumpflege, bestanden] {}",
							type, ref.bell().toShortString(), passNames[pass], Sites.SCAN_REJECT_LEGEND, java.util.Arrays.toString(java.util.Arrays.copyOfRange(passes[pass], 0, 16)),
							java.util.Arrays.toString(java.util.Arrays.copyOfRange(passes[pass], 16, 23)));
						java.util.Arrays.fill(passes[pass], 0);
					}
				}
				return false;
			}
			clearSpecialSiteSearchFailure(ref);
			site = page.site();
			dir = siteFacing(level, ref, type, site);
			Palette palette = detectForPlan(level, ref, c.v(), variant >>> 2, site);
			PALETTES.put(site.asLong(), palette);
			constructionPaletteIds = palette.ids();
			steps = Architect.build(design, palette, site, dir);
		} else {
			int min = type.equals(Blueprints.HOUSE) ? houseMinRadiusForPlan
				: type.equals(Blueprints.WELL) || type.equals(Blueprints.DECOR) ? 5 : type.equals(Blueprints.TOWNHALL) ? 7 : type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type) ? 6 : 10;
			int max = type.equals(Blueprints.HOUSE) ? Math.min(88, Config.VILLAGE_RADIUS - 8)
				: type.equals(Blueprints.WELL) ? 14 : type.equals(Blueprints.DECOR) ? 16 : type.equals(Blueprints.TOWNHALL) ? 22 : type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type) ? 72 : 36;
			int half = type.equals(Blueprints.FARM) ? variant / 10 : Blueprints.half(type);
			int headroom = Blueprints.headroom(type);
			HouseSiteSearch ongoingHouseSearch = type.equals(Blueprints.HOUSE)
				? ongoingHouseSiteSearch(c, ref, houseMinRadiusForPlan, source != null) : null;
			if (ongoingHouseSearch != null) {
				variant = ongoingHouseSearch.variant;
				design = designForPlan(type, variant);
				half = siteHalf(type, variant, design);
				headroom = siteHeadroom(type, variant, design);
				if (templateEligible(design)) {
					half = Math.max(half, 4);
					headroom = Math.max(headroom, 10);
				}
			}
			// Bis zu 4 Entwuerfe probieren: ein kleinerer passt oft noch auf einen engen oder huegeligen Platz.
			int variantBase = variant;
			for (int attempt = 0; attempt < (architect(type) ? 4 : 1) && site == null && ongoingHouseSearch == null; attempt++) {
				design = null;
				variant = variantBase;
				if (architect(type)) {
					// Frischer Entwurf (Groesse, Dach, Fachwerk ...): Der Seed steht im Plan, damit der Bau nach einem Neustart gleich bleibt.
					long seed = ((long) c.v().getRandom().nextInt(1 << 22) << 6) | Palette.profileCode(level, ref.bell());
					seed = Architect.withSizeTier(seed, nextSizeTier(ref, type));
					variant = newPlanVariant(type, seed);
					design = designForPlan(type, variant);
					half = siteHalf(type, variant, design);
					headroom = siteHeadroom(type, variant, design);
					if (Blueprints.HOUSE.equals(type) && templateEligible(design)) {
						half = Math.max(half, 4);
						headroom = Math.max(headroom, 10);
					}
				} else if (Landmarks.handles(type)) {
					long seed = type.equals(Blueprints.DECOR) ? Landmarks.decorSeed(variant, c.v().getRandom().nextInt(1 << 24))
						: c.v().getRandom().nextInt(1 << 24);
					variant = (int) (seed << 2);
					half = Landmarks.half(type);
					headroom = Landmarks.headroom(type);
				}
				// Die ersten Versuche halten 3 Bloecke Abstand zu Nachbarn, erst der letzte nimmt mit 2 vorlieb (sehr enge Doerfer).
				// Aufgelockerte Parzellen: die ersten Versuche halten 5 Bloecke Abstand (Vorgarten, Freiflaeche), spaeter 3, zuletzt 2.
				Sites.margin = attempt >= 3 ? 2 : attempt >= 2 ? 3 : 5;
				int searchMax = Math.min(Config.VILLAGE_RADIUS - 8, max + attempt * 8);
				int normalAttempts = architect(type) ? 48 : 30;
				int tolerantAttempts = architect(type) ? 96 : 60;
				// Dev hook (VP_FORCE_HOUSE_STEP_SEARCH=1): skip every random path so the step-wise search below is exercised.
				boolean skipRandomHousePaths = FORCE_HOUSE_STEP_SEARCH && Blueprints.HOUSE.equals(type)
					&& needsNaturalHousePreparation(type, variant, source != null);
				if (!skipRandomHousePaths && shouldPreferPreparedExpansionParcel(type, variant, source != null, d.houses, needsBeds,
					preparedExpansionParcel.isPresent())) {
					BlockPos preferred = preparedExpansionHouseCandidate(c, ref, preparedExpansionParcel.orElseThrow(),
						variant, design, style, min, searchMax);
					if (preferred != null) {
						site = preferred;
						dir = Sites.facingTowards(site, ref.bell());
						Palette palette = detectForPlan(level, ref, c.v(), variant >>> 2, site);
						PALETTES.put(site.asLong(), palette);
						constructionPaletteIds = palette.ids();
						steps = Architect.build(design, palette, site, dir);
					}
				}
				String selectedType = type;
				int selectedVariant = variant;
				Architect.Design selectedDesign = design;
				java.util.function.Predicate<BlockPos> fits = p -> {
					return siteCandidateFits(c, ref, selectedType, selectedVariant, selectedDesign, style, p, ref.bell());
				};
				if (type.equals(Blueprints.TOWNHALL)) {
					BlockPos center = de.villagerplus.village.Development.center(level, ref);
					site = ZoneAwareSites.findCentral(level, ref, type, center, c.v().getRandom(), 32,
						-half, half, -half, half, headroom, fits);
					if (site == null) site = ZoneAwareSites.findTolerantCentral(level, ref, type, center, c.v().getRandom(), 32,
						-half, half, -half, half, headroom + 1, fits);
				}
				if (site == null && !skipRandomHousePaths) {
						site = ZoneAwareSites.find(level, ref, type, c.v().getRandom(), min, searchMax, half, headroom, normalAttempts, fits);
				}
				if (site == null && !skipRandomHousePaths) {
					// Huegeliges Gelaende (z.B. Overhauled Villages): leicht unebene Plaetze werden eingeebnet.
					site = ZoneAwareSites.findTolerant(level, ref, type, c.v().getRandom(), min, searchMax, -half, half, -half, half,
						headroom + 1, tolerantAttempts, fits);
				}
				if (site == null && !skipRandomHousePaths && needsNaturalHousePreparation(type, variant, source != null)) {
					// Natural tree height makes the generic flat-site scan skip the ground beneath it. Search those
					// bounded candidates with the rooted-tree-aware house preflight before giving up on this design.
					site = findHouseLot(c, ref, variant, design, style, min, searchMax, normalAttempts);
				}
				Sites.margin = 3;

			}
			if (site == null && architect(type) && !needsNaturalHousePreparation(type, variant, source != null)) {
				// Dense CTOV fortifications can defeat random placement even when a safe outer plot exists.
				// Search the loaded village area systematically once before postponing this milestone.
				Sites.margin = 2;
				int systematicMax = Config.VILLAGE_RADIUS - 8;
				int systematicHalf = half;
				int systematicHeadroom = headroom;
				int systematicVariant = variant;
				Architect.Design systematicDesign = design;
				java.util.function.Predicate<BlockPos> systematicFits = p -> {
					return siteCandidateFits(c, ref, type, systematicVariant, systematicDesign, style, p, ref.bell());
				};
				site = ZoneAwareSites.findSystematic(level, ref, type, c.v().getRandom(), min, systematicMax,
					-systematicHalf, systematicHalf, -systematicHalf, systematicHalf, systematicHeadroom, systematicFits);
				if (site == null) {
					site = ZoneAwareSites.findTolerantSystematic(level, ref, type, c.v().getRandom(), min, systematicMax,
						-systematicHalf, systematicHalf, -systematicHalf, systematicHalf, systematicHeadroom + 1, systematicFits);
				}
				Sites.margin = 3;
			}
			if (site == null && needsNaturalHousePreparation(type, variant, source != null)) {
				HouseSiteStep step = stepHouseSiteSearch(c, ref, style, type, ongoingHouseSearch, variant, houseMinRadiusForPlan);
				ongoingHouseSearch = step.search();
				if (step.waiting()) {
					c.st().buildCheckAt = step.search().retryAt;
					return false;
				}
				if (step.site() == null) {
					c.st().buildCheckAt = step.search().retryAt;
				} else {
					site = step.site();
					variant = step.variant();
					design = step.design();
					half = step.half();
					headroom = step.headroom();
				}
			}
			if (site == null) {
				if (c.now() - lastDiag > 1200) {
					lastDiag = c.now();
					LOGGER.info("Villagers: Repurposed: Kein Bauplatz fuer '{}' (halbe Kante {}, Hoehe {}); Kandidaten/Ablehnung [geprueft, Anker, Hoehenlage, Grundstueck, Hof, Parzelle, Zone, Konflikt, Vorbereitung, Weg] = {}", type, half, headroom, java.util.Arrays.toString(SITE_REJECT));
					LOGGER.info("Villagers: Repurposed: Bauplatz-Spaltenablehnung {} {}", Sites.SCAN_REJECT_LEGEND, java.util.Arrays.toString(Sites.SCAN_REJECT));
					LOGGER.info("Villagers: Repurposed: Bauplatz-Diagnose Gelaende {} Grundstueck [Gebaeude, Abstand, Zugang-Boden, Zugang-Hoehe, Strasse, Ungeladen] {}", GRADE_REJECT, java.util.Arrays.toString(Sites.PLOT_REJECT));
					java.util.Arrays.fill(SITE_REJECT, 0);
					java.util.Arrays.fill(Sites.SCAN_REJECT, 0);
					GRADE_REJECT.clear();
					java.util.Arrays.fill(Sites.PLOT_REJECT, 0);
					if (!Sites.PLOT_BLOCKERS.isEmpty()) {
						LOGGER.info("Villagers: Repurposed: Bauplatz-Blocker {} (Beispiele {})", Sites.PLOT_BLOCKERS, Sites.PLOT_BLOCKER_EXAMPLES);
						Sites.PLOT_BLOCKERS.clear();
						Sites.PLOT_BLOCKER_EXAMPLES.clear();
					}
				}
				if (type.equals(Blueprints.PEN)) {
					PEN_SITE_BLOCKED_UNTIL.put(ref.bell().asLong(), c.now() + PEN_SITE_RETRY_TICKS);
				}
				if (type.equals(Blueprints.STORAGE)) {
					STORAGE_BLOCKED.put(ref.bell().asLong(), c.now() + 24000L);
				}
				// A full random + tolerant + systematic sweep that found nothing is expensive (hundreds of
				// milliseconds on a hilly CTOV village) and the terrain will not change within seconds.
				if (ongoingHouseSearch != null) c.st().buildCheckAt = Math.max(c.st().buildCheckAt, ongoingHouseSearch.retryAt);
				else c.st().buildCheckAt = Math.max(c.st().buildCheckAt, c.now() + FAILED_SITE_SWEEP_RETRY_TICKS);
				return false;
			}
			if (dir == null) {
				dir = siteFacing(level, ref, type, site);
			}
			if (design != null) {
				Palette palette = detectForPlan(level, ref, c.v(), variant >>> 2, site);
				PALETTES.put(site.asLong(), palette);
				constructionPaletteIds = palette.ids();
				steps = freshSteps(c, ref, type, variant, style, site, dir);
			} else if (Landmarks.handles(type)) {
				Palette palette = detectForPlan(level, ref, c.v(), variant >>> 2, site);
				PALETTES.put(site.asLong(), palette);
				steps = Landmarks.build(type, variant >>> 2, palette, site, dir);
			} else {
				steps = Blueprints.create(type, style, site, dir, variant);
			}
		}

		// Re-read current persisted zones before publishing the plan, including roof overhang, courtyard decoration and setup space.
		// A plot is committed only when workStand can already reach it; otherwise the same pit is chosen again for the whole budget.
	int plotAttempts = civicSite(type) ? 1 : 8;
		for (int plotTry = 0; plotTry < plotAttempts; plotTry++) {
			plannedHouseLotBounds = null;
			if (site == null || steps == null || dir == null) {
				c.st().buildCheckAt = c.now() + 40;
				return false;
			}
			if (compactStorage(design)) {
				steps = addPaidFirstStorageChest(steps, site, dir, design);
				if (steps == null) {
					rejectPlot(ref, site);
					c.st().buildCheckAt = c.now() + 40;
					return false;
				}
			}
			boolean naturalHouseBlocked = false;
			if (needsNaturalHousePreparation(type, variant, source != null)) {
				BlockPos prepared = naturalHouseAnchor(level, type, variant, source != null, site);
				if (prepared == null) {
					naturalHouseBlocked = true;
				} else {
					if (!prepared.equals(site)) {
						site = prepared;
						dir = siteFacing(level, ref, type, site);
						steps = freshSteps(c, ref, type, variant, style, site, dir);
						Palette palette = PALETTES.get(site.asLong());
						constructionPaletteIds = palette == null ? List.of() : palette.ids();
					}
					List<Step> lotSteps = source == null && newHouseTemplatePreview(level, ref, type, designForPlan(type, variant), site, dir) != null
						? steps : housePlanWithYard(level, site, dir, steps);
					HouseLotSelection lot = lotSteps == null
						? new HouseLotSelection(null, NaturalSitePreparation.Status.BLOCKED)
						: prepareHouseLot(level, ref, site, dir, lotSteps, variant, source != null);
					if (lot.status() == NaturalSitePreparation.Status.WAITING_FOR_CHUNK) {
						c.st().buildCheckAt = c.now() + 100;
						return false;
					}
					naturalHouseBlocked = lot.status() != NaturalSitePreparation.Status.READY;
					if (!naturalHouseBlocked) plannedHouseLotBounds = lot.bounds();
				}
			}
			if (naturalHouseBlocked) {
				rejectPlot(ref, site);
				LOGGER.info("Villagers: Repurposed: Wohnbauplatz {} verworfen (kein sicherer Naturboden oder zu viele Hindernisse)", site.toShortString());
			} else if (planSiteConflicts(ref, steps, site)) {
				rejectPlot(ref, site);
				LOGGER.info("Villagers: Repurposed: Bauplatz {} verworfen (reserviertes anderes Bauprojekt)", site.toShortString());
			} else if (!ZoneAwareSites.allowsPlan(ref, type, steps)) {
				rejectPlot(ref, site);
				LOGGER.info("Villagers: Repurposed: Bauplatz {} verworfen (Zone)", site.toShortString());
			} else {
				if (architect(type) && source == null) {
					Palette palette = PALETTES.get(site.asLong());
					Architect.Design planDesign = designForPlan(type, variant);
					steps = ConstructionSite.order(steps, site, planDesign, palette);
					constructionPhaseEnds = ConstructionSite.phaseEnds(steps, site, planDesign, palette);
				}
				// A farm with no walkable work edge can be paid and saved but leave the farmer
				// cycling forever at its fence. Reject it here and search another complete plot.
				if (reachablePlot(c, ref, site, dir, steps)) {
					break;
				}
				if (lastStandReport(c.v()).contains("unloaded/deferred")) {
					if (civicSite(type)) {
						CivicSiteSearch search = CIVIC_SITE_SEARCHES.get(ref.bell().asLong());
						if (search != null) {
							search.deferCandidate(site);
							search.retryAt = c.now() + CIVIC_SITE_REPROBE_TICKS;
							persistCivicSiteSearch(ref, search);
						}
					}
					c.st().buildCheckAt = c.now() + 40;
					LOGGER.info("Villagers: Repurposed: Bauplatz {} wartet auf geladene Chunks fuer die Erreichbarkeitspruefung", site.toShortString());
					return false;
				}
				rejectPlot(ref, site);
				LOGGER.info("Villagers: Repurposed: Bauplatz {} verworfen (kein erreichbarer, ausreichend hoher Stand) {}", site.toShortString(), lastStandReport(c.v()));
			}
			if (plotTry + 1 == plotAttempts || source != null) {
				c.st().buildCheckAt = c.now() + 40;
				return false;
			}
			site = anotherPlot(c, ref, type, variant, style,
				type.equals(Blueprints.HOUSE) && source == null ? houseMinRadiusForPlan : 0);
			if (site == null) {
				c.st().buildCheckAt = c.now() + 40;
				return false;
			}
			dir = siteFacing(level, ref, type, site);
			steps = freshSteps(c, ref, type, variant, style, site, dir);
			Palette palette = PALETTES.get(site.asLong());
			constructionPaletteIds = palette == null ? List.of() : palette.ids();
			constructionPhaseEnds = List.of();
			continue;
		}
		if (type.equals(Blueprints.HOUSE) && (source != null
			|| newHouseTemplatePreview(level, ref, type, designForPlan(type, variant), site, dir) == null)) {
			int yardStyle = Paths.yardStyle(level, site, dir);
			List<Step> yard = Paths.yardSteps(level, site, dir, yardStyle);
			if (yard.isEmpty() || !Paths.houseYardFits(level, steps, site, dir, yardStyle)) {
				c.st().buildCheckAt = c.now() + 40;
				return false;
			}
			steps = appendHouseYard(steps, yard);
			if (!ZoneAwareSites.allowsPlan(ref, type, steps) || planSiteConflicts(ref, steps, site)
				|| (needsNaturalHousePreparation(type, variant, source != null)
					&& !houseLotReservationFits(ref, site, steps, plannedHouseLotBounds))) {
				rejectPlot(ref, site);
				c.st().buildCheckAt = c.now() + 40;
				return false;
			}
		}
		if (type.equals(Blueprints.FARM) && !stageFarmSeeds(box, siteStock, steps, 0, level)) {
			c.st().buildCheckAt = c.now() + FARM_PLAN_RETRY_TICKS;
			return false;
		}
		d.planType = type;
		d.planOrigin = Optional.of(site);
		d.planDir = dir.get2DDataValue();
		d.planStep = 0;
		d.planVariant = variant;
		d.planSource = Optional.ofNullable(source);
		d.planProgress = c.now();
		d.claimPlanBuilder(c.v().getUUID());
		if (source == null && Blueprints.HOUSE.equals(type)
			&& newHouseTemplatePreview(level, ref, type, designForPlan(type, variant), site, dir) != null) {
			markTemplatePlan(ref, site);
		}
		String earlyPalette = source == null ? EARLY_PALETTE_SITES.remove(site.asLong()) : null;
		if (earlyPalette != null && !ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(site.asLong())) {
			// The village cannot obtain part of the detected palette (deepslate, fired bricks ...). The plan starts with the
			// look-alike palette it can build and carries the material-substitution marker (bit 1), so a restart keeps it and
			// no caravan wait or 3000-tick stall precedes the switch. Fixed role-marker full blocks follow the local wall.
			variant |= 2;
			d.planVariant = variant;
			steps = substituteUnobtainableFullBlocks(steps, 0);
			LOGGER.info("Villagers: Repurposed: Bauplan {} startet mit beschaffbarer Palette (Dorfpalette hat unbeschaffbar: {}; Palette jetzt: {})",
				type, earlyPalette, constructionPaletteIds);
		}
		if (!type.equals(Blueprints.FARM)) {
			// Der Auftrag wird sofort festgelegt (ein Haus, ein Platz); fehlendes Material wird waehrenddessen beschafft.
			updateWanted(c, ref, type, steps, box, siteStock, cargoManifest, site, 0, true);
		}
		if (civicSite(type)) {
			CIVIC_SITE_SEARCHES.remove(ref.bell().asLong());
			d.extra.civicSiteSearch = Optional.empty();
		}
		if (type.equals(Blueprints.HOUSE)) dropHouseSiteSearch(level, ref);
		if (architect(type) && source == null && !constructionPhaseEnds.isEmpty()) {
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION,
				new ConstructionSite(site, List.of(), constructionPhaseEnds, false, constructionPaletteIds));
		}
		ref.dirty();
		STYLES.put(site.asLong(), style);
		STEPS.put(site.asLong(), steps);
		if (needsNaturalHousePreparation(type, variant, source != null)) {
			persistProjectView(c, ref, d, null, siteStock, cargoManifest, plannedHouseLotBounds);
		}
		LOGGER.info("Villagers: Repurposed: Bauplan {} gestartet bei {} (Tick {}, {} Schritte, Variante {}, Vorlage {}, Glocke {})",
			type, site.toShortString(), c.now(), steps.size(), variant,
			ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of()).contains(site.asLong()),
			ref.bell().toShortString());
		return true;
	}

	/** Plots whose palette was swapped for an obtainable one while the plan was drawn up (site key -> unobtainable roles). */
	private static final Map<Long, String> EARLY_PALETTE_SITES = new HashMap<>();

	/**
	 * Palette for a NEW plan: the one detected from the neighbouring houses, but with every role the village cannot obtain
	 * itself (deepslate bricks from a foreign house, fired bricks ...) already replaced by its look-alike (stone bricks,
	 * cobblestone ...). Without this the plan would start with a wish list nobody can fill and only fall back after the
	 * 3000-tick stall. The detected palette is unchanged when everything is obtainable. Already running plans are not
	 * re-evaluated: they keep the palette they were saved with.
	 */
	static Palette detectForPlan(ServerLevel level, VillageRef ref, Villager hint, long seed, BlockPos site) {
		Palette detected = Palette.detect(level, ref.bell(), hint, seed, site);
		List<String> missing = detected.unobtainableRoles();
		if (missing.isEmpty()) {
			EARLY_PALETTE_SITES.remove(site.asLong());
			return detected;
		}
		if (EARLY_PALETTE_SITES.size() > 64) EARLY_PALETTE_SITES.clear();
		EARLY_PALETTE_SITES.put(site.asLong(), missing.toString());
		return Supply.lookAlikePalette(detected, biomeAt(level, site));
	}

	/** Build the exact candidate footprint before accepting a zoned site; Palette only changes materials at these coordinates. */
	private static List<Step> sitingSteps(Ctx c, VillageRef ref, String type, int variant, Architect.Design design, VillageStyle style, BlockPos site, BlockPos facingAnchor) {
		Direction facing = Sites.facingTowards(site, facingAnchor);
		if (design != null) {
			Palette palette = Palette.detect(c.level(), ref.bell(), c.v(), variant >>> 2, site);
			return Architect.build(design, palette, site, facing);
		}
		if (Landmarks.handles(type)) {
			Palette palette = Palette.detect(c.level(), ref.bell(), c.v(), variant >>> 2, site);
			return Landmarks.build(type, variant >>> 2, palette, site, facing);
		}
		return Blueprints.create(type, style, site, facing, variant);
	}

	/** Same footprint the committed plan will use, so the reach test matches the later work stand. */
	private static List<Step> freshSteps(Ctx c, VillageRef ref, String type, int variant, VillageStyle style, BlockPos site, Direction dir) {
		Architect.Design design = architect(type) ? designForPlan(type, variant) : null;
		if (design != null) {
			Palette palette = detectForPlan(c.level(), ref, c.v(), variant >>> 2, site);
			PALETTES.put(site.asLong(), palette);
			List<Step> template = newHouseTemplatePreview(c.level(), ref, type, design, site, dir);
			return template != null ? template : Architect.build(design, palette, site, dir);
		}
		if (Landmarks.handles(type)) {
			Palette palette = detectForPlan(c.level(), ref, c.v(), variant >>> 2, site);
			PALETTES.put(site.asLong(), palette);
			return Landmarks.build(type, variant >>> 2, palette, site, dir);
		}
		return Blueprints.create(type, style, site, dir, variant);
	}

	/** Another loaded plot for this design, skipping stands the builder has already rejected. */
	private static BlockPos anotherPlot(Ctx c, VillageRef ref, String type, int variant, VillageStyle style, int houseMinRadiusOverride) {
		if (templated(type) || (type.equals(Blueprints.HOUSE) && ref.data().planSource.isPresent())) return null;
		ServerLevel level = c.level();
		Architect.Design design = architect(type) ? designForPlan(type, variant) : null;
		int half = design != null ? siteHalf(type, variant, design) : Landmarks.handles(type) ? Landmarks.half(type) : Blueprints.half(type);
		int headroom = design != null ? siteHeadroom(type, variant, design) : Landmarks.handles(type) ? Landmarks.headroom(type) : Blueprints.headroom(type);
		if (Blueprints.HOUSE.equals(type) && templateEligible(design)) {
			half = Math.max(half, 4);
			headroom = Math.max(headroom, 10);
		}
		int min = type.equals(Blueprints.HOUSE) && houseMinRadiusOverride > 0 ? houseMinRadiusOverride
			: type.equals(Blueprints.WELL) || type.equals(Blueprints.DECOR) ? 5 : type.equals(Blueprints.TOWNHALL) ? 7
			: type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type) ? 6 : 10;
		int max = type.equals(Blueprints.HOUSE) && houseMinRadiusOverride > 0 ? Math.min(88, Config.VILLAGE_RADIUS - 8)
			: type.equals(Blueprints.WELL) ? 14 : type.equals(Blueprints.DECOR) ? 16 : type.equals(Blueprints.TOWNHALL) ? 22
			: type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type) ? 72 : 36;
		BlockPos facingAnchor = civicSite(type) ? civicSiteAnchor(level, ref) : ref.bell();
		java.util.function.Predicate<BlockPos> fits = p -> siteCandidateFits(c, ref, type, variant, design, style, p, facingAnchor);
		Sites.margin = 2;
		int searchMax = Math.min(Config.VILLAGE_RADIUS - 8, max);
		BlockPos site = null;
		if (civicSite(type)) {
			// After rejecting a walking-inaccessible plot, try remaining civic plots from near to far.
			site = ZoneAwareSites.findSystematic(level, ref, type, c.v().getRandom(), min, searchMax,
				-half, half, -half, half, headroom, fits);
			if (site == null) {
				site = ZoneAwareSites.findTolerantSystematic(level, ref, type, c.v().getRandom(), min, searchMax,
					-half, half, -half, half, headroom + 1, fits);
			}
		}
		if (site == null) site = ZoneAwareSites.find(level, ref, type, c.v().getRandom(), min, searchMax, half, headroom, 24, fits);
		if (site == null) {
			site = ZoneAwareSites.findTolerant(level, ref, type, c.v().getRandom(), min, searchMax, -half, half, -half, half, headroom + 1, 48, fits);
		}
		if (site == null && needsNaturalHousePreparation(type, variant, false)) {
			site = findHouseLot(c, ref, variant, design, style, min, searchMax, 96);
		}
		if (site == null && architect(type) && !needsNaturalHousePreparation(type, variant, false)) {
			int systematicMax = Config.VILLAGE_RADIUS - 8;
			site = ZoneAwareSites.findSystematic(level, ref, type, c.v().getRandom(), min, systematicMax, -half, half, -half, half, headroom, fits);
			if (site == null) {
				site = ZoneAwareSites.findTolerantSystematic(level, ref, type, c.v().getRandom(), min, systematicMax,
					-half, half, -half, half, headroom + 1, fits);
			}
		}
		Sites.margin = 3;
		return site == null ? null : naturalHouseAnchor(level, type, variant, site);
	}

	/** Upper bound on natural obstacles (trunks, leaves, plants) a civic site preparation may remove. */
	private static final int MAX_CIVIC_SITE_OBSTACLES = 1024;
	static final long CIVIC_CLEARANCE_RETRY_TICKS = 0L;
	/** Diagnostic: natural blocks removed so far per civic plan origin (log throttling only). */
	private static final java.util.Map<Long, Integer> CIVIC_CLEARED = new java.util.HashMap<>();
	private static final java.util.Map<Long, NaturalSitePreparation.Status> CIVIC_PREP_STATUS = new java.util.HashMap<>();

	/**
	 * Work volume of a civic site preparation: the exact plan footprint from the anchor height up to the design's headroom.
	 * It starts at the anchor so that natural soil below the building is never a removal target (no digging, no pits).
	 */
	static BlockPos[] civicPrepBox(BlockPos origin, List<Step> steps, int headroom) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (Step step : steps) {
			minX = Math.min(minX, step.pos().getX());
			maxX = Math.max(maxX, step.pos().getX());
			minZ = Math.min(minZ, step.pos().getZ());
			maxZ = Math.max(maxZ, step.pos().getZ());
		}
		return new BlockPos[]{new BlockPos(minX, origin.getY(), minZ), new BlockPos(maxX, origin.getY() + headroom + 1, maxZ)};
	}

	/** Diagnostic: actual plan extents [minX, maxX, minZ, maxZ] relative to the anchor for a door facing, from the generated steps. */
	private static int[] civicPlanExtents(Ctx c, VillageRef ref, String type, int variant, Architect.Design design,
			VillageStyle style, BlockPos civicAnchor, Direction doorFacing) {
		// facingTowards(site, anchor) == doorFacing for a site 40 blocks on the opposite side of the anchor.
		BlockPos probe = civicAnchor.relative(doorFacing.getOpposite(), 40);
		List<Step> steps = sitingSteps(c, ref, type, variant, design, style, probe, civicAnchor);
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (Step step : steps) {
			minX = Math.min(minX, step.pos().getX() - probe.getX());
			maxX = Math.max(maxX, step.pos().getX() - probe.getX());
			minZ = Math.min(minZ, step.pos().getZ() - probe.getZ());
			maxZ = Math.max(maxZ, step.pos().getZ() - probe.getZ());
		}
		return new int[]{minX, maxX, minZ, maxZ};
	}

	private static boolean civicSite(String type) {
		return type.equals(Blueprints.STORAGE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type);
	}

	/** The path check continuePlan will use, applied before the plan is stored. */
	private static boolean reachablePlot(Ctx c, VillageRef ref, BlockPos site, Direction dir, List<Step> steps) {
		int minimumStandY = villageGroundY(c.level(), ref.bell()) - 12;
		return workStand(c, site, dir, steps, minimumStandY) != null;
	}

	/**
	 * Wird von der Handelskarawane nach dem Einkauf aufgerufen: Fehlt dem laufenden Hausbau danach immer noch Material,
	 * das das Dorf weder herstellen noch abbauen kann, weicht der Plan auf beschaffbares Ersatzmaterial aus.
	 */
	public static void caravanVisited(ServerLevel level, VillageRef ref, Container box) {
		VillageData d = ref.data();
		LOGGER.info("Villagers: Repurposed: Karawanenbesuch prueft Ersatzpalette (aktive Projekte {})",
			d.buildProjects.stream().filter(VillageData.BuildProject::active).map(p -> p.type() + "/v" + p.variant()).toList());
		if (d.buildProjects.stream().noneMatch(VillageData.BuildProject::active)) {
			boolean changed = false;
			try {
				changed = trySwitchToObtainablePlanFallback(level, ref, box);
			} finally {
				if (changed) ref.dirty();
			}
			return;
		}

		// The legacy plan_* fields are only a projection of the first project. A caravan
		// visit must also let a second active project resolve its own permanent shortage.
		// loadProjectView temporarily exposes each saved cursor without changing its haul,
		// stock, owner, construction site, or demand rows.
		boolean changed = false;
		try {
			for (VillageData.BuildProject project : List.copyOf(d.buildProjects)) {
				if (project == null) continue;
				try {
					if (!project.active() || (project.variant() & 2) != 0
						|| (!generated(project.type()) && project.source().isEmpty())) continue;
					loadProjectView(ref, d, project);
					if (!trySwitchToObtainablePlanFallback(level, ref, box)) continue;
					ConstructionSite currentSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
					Optional<ConstructionSite> updatedConstruction = currentSite != null
						&& currentSite.origin().equals(project.origin()) ? Optional.of(currentSite) : project.construction();

					VillageData.BuildProject updated = new VillageData.BuildProject(project.type(), project.origin(), project.dir(),
						project.step(), d.planVariant, project.source(), project.progress(), project.owner(),
						project.ownerUnavailableSince(), List.copyOf(d.wanted), project.haul(), project.materialWait(),
						updatedConstruction, project.bounds(), project.repair(), project.cleanupOnly());
					d.putBuildProject(updated);
					changed = true;
				} catch (RuntimeException failure) {
					LOGGER.warn("Could not resolve unavailable materials for project {} at {}", project.type(),
						project.origin().toShortString(), failure);
				}
			}
		} finally {
			try {
				restorePrimaryProject(ref, d);
			} finally {
				// Persist successful earlier project switches even if restoring the legacy
				// projection fails while another project's data is malformed.
				if (changed) {
					try {
						d.refreshWanted();
					} finally {
						ref.dirty();
					}
				}
			}
		}
	}

	/** Uses the currently loaded single-plan view, keeping the legacy-save path and project path identical. */
	private static boolean trySwitchToObtainablePlanFallback(ServerLevel level, VillageRef ref, Container box) {
		return trySwitchToObtainablePlanFallback(level, ref, box, false);
	}

	/**
	 * @param stalled true when the plan has made no material progress for a long time: locally craftable tag items
	 *                (a door, a bed) then no longer keep an unobtainable palette in place, because nobody delivers them.
	 */
	/** After a long stall, drop the site-scaffolding request and mark the site prepared so building can start without it. */
	private static void waiveStalledScaffolding(VillageRef ref, Container box) {
		VillageData d = ref.data();
		boolean wantsScaffolding = PlanSupplyOrder.shortages(d.wanted).containsKey(Items.SCAFFOLDING)
			&& JobUtil.count(box, stack -> stack.is(Items.SCAFFOLDING)) == 0;
		ConstructionSite site = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (!wantsScaffolding || site == null || site.prepared()) return;
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION,
			new ConstructionSite(site.origin(), site.temporary(), site.phaseEnds(), true, site.paletteIds()));
		d.wanted.removeIf(row -> PlanSupplyOrder.shortages(List.of(row)).containsKey(Items.SCAFFOLDING));
		ref.dirty();
		LOGGER.info("Villagers: Repurposed: Bauplan {} baut ohne Baustellengeruest weiter (Geruest blieb nach langem Stillstand aus)", d.planType);
	}

	private static boolean trySwitchToObtainablePlanFallback(ServerLevel level, VillageRef ref, Container box, boolean stalled) {
		VillageData d = ref.data();
		if (!d.hasPlan() || (!generated(d.planType) && d.planSource.isEmpty()) || (d.planVariant & 2) != 0 || d.planOrigin.isEmpty()) {
			return false;
		}
		BlockPos origin = d.planOrigin.get();
		long key = origin.asLong();
		List<Step> current = STEPS.get(key);
		if (current == null) {
			try {
				current = stepsFor(level, ref, origin, Direction.from2DDataValue(d.planDir), null);
			} catch (RuntimeException failure) {
				LOGGER.warn("Could not inspect plan {} for its obtainable-material fallback", d.planType, failure);
				return false;
			}
		}
		if (current == null || current.isEmpty()) return false;

		// The current 48-step wish window still gates ordinary purchasable shortages.
		for (Map.Entry<Item, Integer> request : PlanSupplyOrder.shortages(d.wanted).entrySet()) {
			if (JobUtil.count(box, stack -> stack.is(request.getKey())) >= request.getValue()) continue;
			// Site temporaries (workbench, scaffolding) are independent of the plan's palette; waiting for them must not
			// keep an unobtainable palette (e.g. cracked/mossy stone bricks) from being replaced by a local one.
			if (request.getKey() == Items.SCAFFOLDING || request.getKey() == Items.CRAFTING_TABLE) continue;
			// A caravan callback must never skip a still-missing material the village can produce itself; after a long
			// stall that material has demonstrably not arrived, so waiting for it any longer only keeps the plan frozen.
			if (!stalled && Supply.obtainable(Block.byItem(request.getKey()))) return false;
		}
		RemainingMaterialDemand beforeDemand = remainingMaterialDemand(level, current, d.planStep, box);
		if (beforeDemand.unobtainableShortage() == 0 || (!stalled && beforeDemand.localTagShortage() > 0)) {
			LOGGER.info("Villagers: Repurposed: Ersatzpalette fuer {} abgelehnt: unbeschaffbar fehlend {}, lokal beschaffbare Tags fehlend {}",
				d.planType, beforeDemand.unobtainableShortage(), beforeDemand.localTagShortage());
			LOGGER.info("Villagers: Repurposed: Tag-Posten im Plan {}: {}", d.planType, current.stream().filter(step -> step.tag() != null)
				.map(step -> step.tag().location() + "x" + step.cost()).distinct().toList());
			return false;
		}

		int previousVariant = d.planVariant;
		ConstructionSite previousSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		try {
			d.planVariant |= 2;
			STEPS.remove(key);
			List<Step> candidate = stepsFor(level, ref, origin, Direction.from2DDataValue(d.planDir), null);
			List<Step> stable = candidate == null ? null : preservePlanGeometry(current, candidate, d.planStep);
			if (stable != null) stable = substituteUnobtainableFullBlocks(stable, d.planStep);
			RemainingMaterialDemand afterDemand = stable == null ? null : remainingMaterialDemand(level, stable, d.planStep, box);
			if (stable == null || afterDemand.unobtainableShortage() != 0 || (!stalled && afterDemand.localTagShortage() != 0)) {
				LOGGER.info("Villagers: Repurposed: Ersatzpalette fuer {} verworfen (Rollback): stabil={}, danach unbeschaffbar={}, danach Tags={}",
					d.planType, stable != null, afterDemand == null ? -1 : afterDemand.unobtainableShortage(),
					afterDemand == null ? -1 : afterDemand.localTagShortage());
				if (stable != null) {
					LOGGER.info("Villagers: Repurposed: Weiterhin unbeschaffbar im Ersatzplan: {}", stable.stream()
						.skip(Math.max(0, d.planStep)).filter(step -> !step.free() && !step.optional() && step.linkedTo() == null && step.cost() > 0)
						.filter(step -> step.tag() == null && step.item() != null && !Supply.obtainable(Block.byItem(step.item())))
						.map(step -> BuiltInRegistries.ITEM.getKey(step.item()) + "@" + step.pos().toShortString()).limit(8).toList());
				}
				rollbackPlanFallback(ref, key, current, previousVariant, previousSite);
				return false;
			}
			STEPS.put(key, stable);
			removeUnobtainablePlanRequests(d.wanted);
			return true;
		} catch (RuntimeException failure) {
			rollbackPlanFallback(ref, key, current, previousVariant, previousSite);
			LOGGER.warn("Could not rebuild plan {} for its obtainable-material fallback", d.planType, failure);
			return false;
		}
	}

	private static void rollbackPlanFallback(VillageRef ref, long key, List<Step> current, int previousVariant,
			ConstructionSite previousSite) {
		VillageData data = ref.data();
		data.planVariant = previousVariant;
		STEPS.put(key, current);
		if (previousSite == null) ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		else ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, previousSite);
	}

	/** A successful fallback no longer needs orders for the unavailable materials it replaced. */
	private static void removeUnobtainablePlanRequests(List<String> wanted) {
		java.util.Set<Item> replaced = new java.util.HashSet<>();
		for (Item item : PlanSupplyOrder.shortages(wanted).keySet()) {
			if (!Supply.obtainable(Block.byItem(item))) replaced.add(item);
		}
		if (replaced.isEmpty()) return;
		wanted.removeIf(row -> PlanSupplyOrder.shortages(List.of(row)).keySet().stream().anyMatch(replaced::contains));
	}

	private record RemainingMaterialDemand(long localTagShortage, long unobtainableShortage) { }

	/** Full remaining-plan demand scan; unlike the trader wish list, this is not bounded to 48 steps. */
	private static RemainingMaterialDemand remainingMaterialDemand(ServerLevel level, List<Step> steps, int fromStep, Container box) {
		if (box == null) return new RemainingMaterialDemand(0, 0);
		Map<Key, Integer> locallySuppliedTags = new HashMap<>();
		Map<Key, Integer> unavailable = new HashMap<>();
		for (int i = Math.max(0, fromStep); i < steps.size(); i++) {
			Step step = steps.get(i);
			if (i == fromStep && step.item() != null && level.getBlockState(step.pos()).is(Block.byItem(step.item()))) continue;
			if (step.free() || step.optional() || step.linkedTo() != null || step.cost() <= 0) continue;
			if (step.tag() != null) {
				Key key = new Key(step.item(), step.tag());
				boolean localOption = hasLocalTagSupplyOption(step.tag())
					|| (step.item() != null && Supply.obtainable(Block.byItem(step.item())));
				(localOption ? locallySuppliedTags : unavailable).merge(key, step.cost(), Integer::sum);
			} else if (step.item() != null && !Supply.obtainable(Block.byItem(step.item()))) {
				unavailable.merge(new Key(step.item(), null), step.cost(), Integer::sum);
			}
		}
		return new RemainingMaterialDemand(missingDemand(box, locallySuppliedTags), missingDemand(box, unavailable));
	}

	/** Beds have a local ShepherdJob recipe; other tags need at least one obtainable matching block item. */
	private static boolean hasLocalTagSupplyOption(TagKey<Item> tag) {
		if (tag.equals(ItemTags.BEDS)) return true;
		for (net.minecraft.core.Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
			Item item = holder.value();
			if (item instanceof net.minecraft.world.item.BlockItem && Supply.obtainable(Block.byItem(item))) return true;
		}
		return false;
	}

	private static long missingDemand(Container box, Map<Key, Integer> needed) {
		long missing = 0;
		for (Map.Entry<Key, Integer> entry : needed.entrySet()) {
			Key key = entry.getKey();
			int have = JobUtil.count(box, stack -> (key.item() != null && stack.is(key.item()))
				|| (key.tag() != null && stack.is(key.tag())));
			missing += Math.max(0, entry.getValue() - have);
		}
		return missing;
	}

	/**
	 * Fixed role-marker blocks (the granary's hay crest) are not part of the palette and so survive a palette swap.
	 * When such a full block cannot be obtained locally, its remaining placement is paid with the village's local wall
	 * material instead; shape-dependent blocks (stairs, doors, ...) are left to the palette substitution.
	 */
	static List<Step> substituteUnobtainableFullBlocks(List<Step> steps, int fromStep) {
		List<Step> result = new ArrayList<>(steps.size());
		for (int i = 0; i < steps.size(); i++) {
			Step step = steps.get(i);
			if (i < fromStep || step.free() || step.optional() || step.linkedTo() != null || step.cost() <= 0
				|| step.tag() != null || step.item() == null) {
				result.add(step);
				continue;
			}
			Block block = Block.byItem(step.item());
			if (Supply.obtainable(block)
				|| !block.defaultBlockState().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				result.add(step);
				continue;
			}
			Block local = Supply.substitute(block, (VillageStyle) null);
			if (local == null || local == block || !Supply.obtainable(local)) {
				result.add(step);
				continue;
			}
			result.add(new Step(step.pos(), local.asItem(), null, step.cost(), step.optional(), step.linkedTo(),
				ignored -> local.defaultBlockState()));
		}
		return List.copyOf(result);
	}

	/** Keep the persisted cursor's index and exact placement order while replacing future materials. */
	static List<Step> preservePlanGeometry(List<Step> current, List<Step> candidate, int cursor) {
		if (current.size() != candidate.size()) return null;
		Map<BlockPos, Step> replacements = new HashMap<>();
		for (Step step : candidate) if (replacements.put(step.pos(), step) != null) return null;
		if (replacements.size() != current.size()) return null;
		List<Step> merged = new ArrayList<>(current.size());
		for (int i = 0; i < current.size(); i++) {
			Step before = current.get(i);
			Step after = replacements.get(before.pos());
			if (after == null || before.cost() != after.cost() || before.free() != after.free()
				|| before.optional() != after.optional() || !java.util.Objects.equals(before.linkedTo(), after.linkedTo())
				|| !java.util.Objects.equals(before.tag(), after.tag())) return null;
			if (i < cursor) {
				merged.add(before);
			} else {
				merged.add(new Step(before.pos(), after.item(), before.tag(), before.cost(), before.optional(),
					before.linkedTo(), after.make()));
			}
		}
		return List.copyOf(merged);
	}

	/** Run the real caravan callback against a supplied in-flight plan, restoring the transient cache afterward. */
	public static List<Step> caravanVisitedForCheck(ServerLevel level, VillageRef ref, Container box, List<Step> steps) {
		long key = ref.data().planOrigin.orElseThrow().asLong();
		List<Step> previous = STEPS.put(key, List.copyOf(steps));
		try {
			caravanVisited(level, ref, box);
			return STEPS.get(key);
		} finally {
			if (previous == null) STEPS.remove(key);
			else STEPS.put(key, previous);
		}
	}

	/** Temporarily seed a paused plan's cache for an end-to-end caravan fixture. */
	public static List<Step> replaceCachedPlanForCheck(BlockPos origin, List<Step> steps) {
		return STEPS.put(origin.asLong(), List.copyOf(steps));
	}

	public static List<Step> cachedPlanForCheck(BlockPos origin) {
		return origin == null ? null : STEPS.get(origin.asLong());
	}

	public static void restoreCachedPlanForCheck(BlockPos origin, List<Step> previous) {
		if (previous == null) STEPS.remove(origin.asLong());
		else STEPS.put(origin.asLong(), previous);
	}

	/** Anzahl der Bausteine des laufenden Plans (0 = unbekannt/kein Plan). */
	public static int planSize(VillageData d) {
		return planSize(d, null);
	}

	/** Use persisted phase totals while the transient step cache is being rebuilt after world load. */
	public static int planSize(VillageData d, ConstructionSite savedSite) {
		if (!d.hasPlan() || d.planOrigin.isEmpty()) {
			return 0;
		}
		List<Step> steps = STEPS.get(d.planOrigin.get().asLong());
		if (steps != null) return steps.size();
		if (savedSite != null && savedSite.origin().equals(d.planOrigin.get()) && !savedSite.phaseEnds().isEmpty()) {
			return savedSite.phaseEnds().get(savedSite.phaseEnds().size() - 1);
		}
		return 0;
	}
	/** Braucht der laufende Bauauftrag diesen Gegenstand irgendwo (auch wenn er gerade vollzaehlig in der Kiste liegt)? */
	public static boolean planNeeds(VillageData d, ItemStack stack) {
		if (!d.hasPlan() || d.planOrigin.isEmpty()) {
			return false;
		}
		List<Step> steps = STEPS.get(d.planOrigin.get().asLong());
		if (steps == null) {
			return false;
		}
		for (Step s : steps) {
			if (!s.free() && s.linkedTo() == null && ((s.item() != null && stack.is(s.item())) || (s.tag() != null && stack.is(s.tag())))) {
				return true;
			}
		}
		return false;
	}
	/** Plans whose scaffolding availability was already judged (once per plan origin and session). */
	private static final java.util.Set<Long> SCAFFOLDING_JUDGED = new HashSet<>();

	/**
	 * Site scaffolding (vanilla: six bamboo and a string) is convenience equipment, but until it is delivered the unprepared
	 * construction site keeps the whole plan at step 0. If the public stock holds none and cannot craft any (a taiga village has
	 * neither bamboo nor string), nobody will ever make it: judge that at the first supply check instead of after the
	 * 3000-tick stall, and let the plan build without scaffolding. Returns the (possibly updated) construction site.
	 */
	private static ConstructionSite waiveUncraftableScaffolding(Ctx c, VillageRef ref, BlockPos origin,
			ConstructionSite construction, Container publicStock) {
		if (construction == null || !construction.origin().equals(origin) || construction.prepared()
			|| publicStock == null || c == null || !SCAFFOLDING_JUDGED.add(origin.asLong())) return construction;
		if (JobUtil.count(publicStock, stack -> stack.is(Items.SCAFFOLDING)) > 0
			|| de.villagerplus.job.RecipeCrafting.canCraftCraftingOutput(c, publicStock, stack -> stack.is(Items.SCAFFOLDING), 64)) {
			return construction;
		}
		ConstructionSite waived = new ConstructionSite(construction.origin(), construction.temporary(), construction.phaseEnds(),
			true, construction.paletteIds());
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, waived);
		ref.dirty();
		LOGGER.info("Villagers: Repurposed: Bauplan {} baut ohne Baustellengeruest weiter (Geruest ist im Dorf nicht herstellbar: kein Vorrat, kein Bambus/Schnur)",
			ref.data().planType);
		return waived;
	}

	/** Keep project-local demand equal to its remaining plan/setup need after site stock and marked cargo. */
	private static boolean updateWanted(Ctx c, VillageRef ref, String type, List<Step> steps, Container publicStock,
			Container siteStock, List<String> cargoManifest, BlockPos origin, int fromStep, boolean announce) {
		VillageData d = ref.data();
		int start = Math.max(0, Math.min(fromStep, steps.size()));
		int end = materialDemandWindowEnd(steps, start);
		List<Step> demandWindow = steps.subList(start, end);
		Map<Item, Integer> support = supportDemand(c, ref, origin, steps, start);
		net.minecraft.world.SimpleContainer empty = new net.minecraft.world.SimpleContainer(1);
		List<Gap> required = missing(c.level(), demandWindow, 0, empty, support);
		List<DemandTarget> targets = new ArrayList<>();
		for (Gap gap : required) {
			if (gap.item() != null && gap.total() > 0) targets.add(new DemandTarget(gap.item(), gap.tag(), gap.total()));
		}
		ConstructionSite construction = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		construction = waiveUncraftableScaffolding(c, ref, origin, construction, publicStock);
		if (construction != null && construction.origin().equals(origin) && !construction.prepared()) {
			Map<Item, Integer> setup = ConstructionSite.missingSetupMaterials(c.level(), construction, steps, origin,
				Direction.from2DDataValue(d.planDir), empty);
			for (Map.Entry<Item, Integer> entry : setup.entrySet()) {
				if (entry.getValue() > 0) targets.add(new DemandTarget(entry.getKey(), null, entry.getValue()));
			}
		}
		Map<Item, Integer> available = new java.util.LinkedHashMap<>();
		for (int slot = 0; siteStock != null && slot < siteStock.getContainerSize(); slot++) {
			ItemStack stack = siteStock.getItem(slot);
			if (!stack.isEmpty()) available.merge(stack.getItem(), stack.getCount(), Builder::saturatingCount);
		}
		Map<Item, Integer> cargo = de.villagerplus.job.BuildHaul.inTransit(cargoManifest);
		cargo.forEach((item, count) -> available.merge(item, count, Builder::saturatingCount));
		Map<String, Long> remaining = new java.util.TreeMap<>();
		for (DemandTarget target : targets) {
			int left = consumeAvailable(available, target.request(), target.count());
			if (target.tag() != null && left > 0) {
				List<Item> alternatives = new ArrayList<>(available.keySet());
				alternatives.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
				for (Item alternative : alternatives) {
					if (alternative.equals(target.request()) || !new ItemStack(alternative).is(target.tag())) continue;
					left = consumeAvailable(available, alternative, left);
					if (left == 0) break;
				}
			}
			if (left > 0) {
				Map<Item, Integer> requests = target.tag() == null ? Map.of(target.request(), left)
					: publicTagDemand(publicStock, target.request(), target.tag(), left);
				for (Map.Entry<Item, Integer> request : requests.entrySet()) {
					String id = BuiltInRegistries.ITEM.getKey(request.getKey()).toString();
					remaining.merge(id, (long) request.getValue(), (a, b) -> Math.min(Integer.MAX_VALUE, a + b));
				}
			}
		}
		d.wanted.clear();
		for (Map.Entry<String, Long> entry : remaining.entrySet()) {
			if (d.wanted.size() >= 256) break;
			d.wanted.add(entry.getKey() + "#" + Math.min(Integer.MAX_VALUE, entry.getValue()));
		}
		List<Gap> publicMissing = publicStock == null ? required
			: missing(c.level(), demandWindow, 0, new net.minecraft.world.CompoundContainer(publicStock, siteStock), support);
		if (announce && !publicMissing.isEmpty()) {
			MutableComponent msg = Component.literal("Für " + label(type) + " fehlt in der Dorfkiste (die Dorfbewohner beschaffen es): ");
			int shown = Math.min(7, publicMissing.size());
			for (int i = 0; i < shown; i++) {
				msg.append(i == 0 ? Component.empty() : Component.literal(", ")).append(publicMissing.get(i).text());
			}
			if (publicMissing.size() > shown) {
				msg.append(Component.literal(" …"));
			}
			JobUtil.tellNearbyPlayers(c.level(), c.v(), 32, msg);
		}
		ref.dirty();
		if (construction != null && construction.origin().equals(origin) && !construction.prepared()
			&& !ConstructionSite.missingSetupMaterials(c.level(), construction, steps, origin,
				Direction.from2DDataValue(d.planDir), siteStock).isEmpty()) return false;
		return nextPlacementHasSiteMaterials(c, ref, steps, origin, start, siteStock);
	}

	private enum PublicStockStage { MOVED, SCANNING, NONE }

	/** Bridge public milestone stocks into the haul pickup list, which intentionally reads only the village and Lager chests. */
	private static PublicStockStage stageSpecialBuildingStock(Ctx c, VillageRef ref, List<String> wanted) {
		if (c == null || c.level() == null || ref == null || wanted == null || wanted.isEmpty()) return PublicStockStage.NONE;
		BlockPos depotPos = ref.chestPos(c.level());
		if (depotPos == null || !Lager.publicStorageAt(c.level(), depotPos)) return PublicStockStage.NONE;
		Container depot = Lager.publicContainerAt(c.level(), depotPos);
		if (depot == null) return PublicStockStage.NONE;
		List<BlockPos> anchors = new ArrayList<>();
		for (String type : PUBLIC_STOCK_BUILDINGS) {
			BlockPos anchor = de.villagerplus.village.Specials.get(ref, type);
			if (anchor != null) anchors.add(anchor.immutable());
		}
		if (anchors.isEmpty()) return PublicStockStage.NONE;
		Map<Long, PublicStockScan> byVillage = PUBLIC_STOCK_SCANS.computeIfAbsent(c.level(), ignored -> new HashMap<>());
		long villageKey = ref.bell().asLong();
		PublicStockScan scan = byVillage.get(villageKey);
		if (scan == null || !scan.anchors.equals(anchors)) {
			scan = new PublicStockScan(anchors);
			byVillage.put(villageKey, scan);
		}
		String demandKey = String.join("|", wanted);
		if (!scan.demandKey.equals(demandKey)) {
			scan.demandKey = demandKey;
			scan.sourceIndex = 0;
			scan.sourcesVisited = 0;
			scan.retryAt = 0L;
		}
		int total = scan.anchors.size() * PUBLIC_STOCK_BUILDING_VOLUME;
		if (scan.scanIndex >= total && c.now() >= scan.refreshAt) {
			scan.sources.clear();
			scan.scanIndex = 0;
			scan.sourceIndex = 0;
			scan.sourcesVisited = 0;
			scan.retryAt = 0L;
		}
		int budget = PUBLIC_STOCK_SCAN_BLOCKS_PER_TICK;
		while (budget-- > 0 && scan.scanIndex < total && scan.sources.size() < MAX_PUBLIC_STOCK_SOURCES) {
			int index = scan.scanIndex++;
			BlockPos anchor = scan.anchors.get(index / PUBLIC_STOCK_BUILDING_VOLUME);
			int local = index % PUBLIC_STOCK_BUILDING_VOLUME;
			int dy = local / (19 * 19) - 2;
			int withinLayer = local % (19 * 19);
			int dx = withinLayer / 19 - 9;
			int dz = withinLayer % 19 - 9;
			BlockPos pos = anchor.offset(dx, dy, dz);
			if (pos.equals(depotPos) || !c.level().hasChunkAt(pos)) continue;
			var block = c.level().getBlockState(pos).getBlock();
			if (!(block instanceof ChestBlock) && !(block instanceof BarrelBlock)) continue;
			if (Lager.publicStorageAt(c.level(), pos)) scan.sources.add(pos.immutable());
		}
		if (scan.sources.size() >= MAX_PUBLIC_STOCK_SOURCES) scan.scanIndex = total;
		if (scan.scanIndex >= total && c.now() >= scan.refreshAt) {
			scan.refreshAt = c.now() + (scan.sources.isEmpty() ? PUBLIC_STOCK_RETRY_TICKS : PUBLIC_STOCK_REFRESH_TICKS);
		}
		if (scan.scanIndex < total) return PublicStockStage.SCANNING;
		if (scan.sources.isEmpty() || c.now() < scan.retryAt) return PublicStockStage.NONE;

		int budgetSources = Math.min(PUBLIC_STOCK_SOURCES_PER_TICK, scan.sources.size());
		for (int checked = 0; checked < budgetSources; checked++) {
			BlockPos sourcePos = scan.sources.get(scan.sourceIndex % scan.sources.size());
			scan.sourceIndex = (scan.sourceIndex + 1) % scan.sources.size();
			scan.sourcesVisited++;
			if (!c.level().hasChunkAt(sourcePos) || !Lager.publicStorageAt(c.level(), sourcePos)) continue;
			Container source = Lager.publicContainerAt(c.level(), sourcePos);
			int moved = moveRequestedPublicStock(source, depot, wanted, true, true);
			if (moved > 0) {
				scan.sourcesVisited = 0;
				scan.retryAt = 0L;
				return PublicStockStage.MOVED;
			}
		}
		if (scan.sourcesVisited >= scan.sources.size()) {
			scan.sourcesVisited = 0;
			scan.retryAt = c.now() + PUBLIC_STOCK_RETRY_TICKS;
			return PublicStockStage.NONE;
		}
		return PublicStockStage.SCANNING;
	}

	/** Move one requested stack at most, conserving real items and failing closed for private/non-public stores. */
	static int moveRequestedPublicStock(Container source, Container destination, List<String> wanted,
			boolean publicSource, boolean publicDestination) {
		if (!publicSource || !publicDestination || source == null || destination == null || source == destination
			|| wanted == null || wanted.isEmpty()) return 0;
		for (String row : wanted) {
			if (row == null) continue;
			String[] fields = row.split("#", 2);
			if (fields.length != 2) continue;
			Item item;
			int requested;
			try {
				item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(fields[0])).orElse(null);
				requested = Integer.parseInt(fields[1]);
			} catch (RuntimeException malformed) {
				continue;
			}
			if (item == null || requested <= 0) continue;
			int alreadyInDepot = JobUtil.count(destination, stack -> stack.is(item));
			int remaining = Math.max(0, requested - alreadyInDepot);
			if (remaining == 0) continue;
			for (int slot = 0; slot < source.getContainerSize(); slot++) {
				ItemStack stored = source.getItem(slot);
				if (stored.isEmpty() || !stored.is(item)) continue;
				int amount = Math.min(remaining, stored.getCount());
				ItemStack remainder = HopperBlockEntity.addItem(null, destination, stored.copyWithCount(amount), null);
				int moved = amount - remainder.getCount();
				if (moved <= 0) continue;
				stored.shrink(moved);
				source.setChanged();
				destination.setChanged();
				return moved;
			}
		}
		return 0;
	}

	private record DemandTarget(Item request, TagKey<Item> tag, int count) { }

	private static int saturatingCount(int left, int right) {
		return (int) Math.min(Integer.MAX_VALUE, (long) left + right);
	}

	private static int consumeAvailable(Map<Item, Integer> available, Item item, int requested) {
		if (requested <= 0) return 0;
		int have = available.getOrDefault(item, 0);
		int used = Math.min(have, requested);
		if (used == have) available.remove(item);
		else available.put(item, have - used);
		return requested - used;
	}

	/** Prefer exact stock, then deterministically request actual public items matching a paid plan tag. */
	static Map<Item, Integer> publicTagDemand(Container publicStock, Item preferred, TagKey<Item> tag, int requested) {
		if (preferred == null || requested <= 0) return Map.of();
		if (tag == null || publicStock == null) return Map.of(preferred, requested);
		List<Item> availableItems = new ArrayList<>();
		for (int slot = 0; slot < publicStock.getContainerSize(); slot++) {
			ItemStack stack = publicStock.getItem(slot);
			if (!stack.isEmpty() && stack.is(tag) && !availableItems.contains(stack.getItem())) availableItems.add(stack.getItem());
		}
		availableItems.sort(Comparator.comparing((Item item) -> !item.equals(preferred))
			.thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
		Map<Item, Integer> selected = new LinkedHashMap<>();
		int remaining = requested;
		for (Item item : availableItems) {
			int amount = Math.min(remaining, JobUtil.count(publicStock, stack -> stack.is(item)));
			if (amount <= 0) continue;
			selected.put(item, amount);
			remaining -= amount;
			if (remaining == 0) break;
		}
		if (remaining > 0) selected.merge(preferred, remaining, Builder::saturatingCount);
		return java.util.Collections.unmodifiableMap(selected);
	}

	private static boolean nextPlacementHasSiteMaterials(Ctx c, VillageRef ref, List<Step> steps, BlockPos origin,
			int fromStep, Container siteStock) {
		for (int i = Math.max(0, fromStep); i < steps.size(); i++) {
			Step step = steps.get(i);
			if (step.free()) return true;
			BlockState current = c.level().getBlockState(step.pos());
			boolean done = step.linkedTo() != null ? current.is(c.level().getBlockState(step.linkedTo()).getBlock())
				: step.item() != null && current.is(Block.byItem(step.item()));
			if (done) continue;
			if (step.free() || step.optional() || step.linkedTo() != null) return true;
			if (step.item() == null && step.tag() == null) return true;
			Map<Item, Integer> support = supportDemand(c, ref, origin, steps, i);
			for (Map.Entry<Item, Integer> entry : support.entrySet()) {
				if (JobUtil.count(siteStock, stack -> stack.is(entry.getKey())) < entry.getValue()) return false;
			}
			return JobUtil.count(siteStock, stack -> (step.item() != null && stack.is(step.item()))
				|| (step.tag() != null && stack.is(step.tag()))) >= step.cost();
		}
		return true;
	}

	/**
		 * Limit each material request to the next 48 paid placement steps. This lets a large plan
	 * begin with available local materials while later phases are sourced as the saved cursor advances.
	 */
	static int materialDemandWindowEnd(List<Step> steps, int fromStep) {
		int end = Math.max(0, Math.min(fromStep, steps.size()));
		int paidSteps = 0;
		while (end < steps.size()) {
			Step step = steps.get(end);
			end++;
			if (!step.free() && !step.optional() && step.linkedTo() == null && step.cost() > 0
				&& ++paidSteps >= MATERIAL_DEMAND_WINDOW) break;
		}
		return end;
	}

	private static Map<Item, Integer> supportDemand(Ctx c, VillageRef ref, BlockPos origin, List<Step> steps, int fromStep) {
		if (fromStep < 0 || fromStep >= steps.size()) return Map.of();
		Step step = steps.get(fromStep);
		if (step.free() || step.item() == null || step.optional() || step.linkedTo() != null
			|| step.pos().getY() > origin.getY()
			|| step.pos().getY() < origin.getY() - 1) return Map.of();
		Block base = supportBase(c, ref, origin);
		SupportColumn column = scanSupport(c.level(), step.pos());
		if (base == Blocks.AIR || column.blocked() || !column.anchored() || column.positions().isEmpty()) return Map.of();
		return Map.of(base.asItem(), column.positions().size());
	}

	private static Block supportBase(Ctx c, VillageRef ref, BlockPos origin) {
		VillageData data = ref.data();
		Palette palette = PALETTES.computeIfAbsent(origin.asLong(), key -> Palette.detect(c.level(), ref.bell(), c.v(),
			data.planVariant >>> 2, origin));
		palette = paletteForPlan(c.level(), origin, palette, biomeAt(c.level(), origin), data.planVariant);
		return palette.base();
	}

	private static Identifier biomeAt(ServerLevel level, BlockPos pos) {
		return level.getBiome(pos).unwrapKey().map(key -> key.identifier())
			.orElse(Identifier.withDefaultNamespace("plains"));
	}

	/** The same variant-aware palette selection used by generated plans; package-visible for focused checks. */
	static Palette paletteForPlan(Palette detected, Identifier biomeId, int variant) {
		return (variant & 2) != 0 ? Supply.obtainablePalette(detected, biomeId) : detected;
	}

	/** Variant-aware palette plus the village's local building wood, once the village has adopted one. */
	static Palette paletteForPlan(ServerLevel level, BlockPos origin, Palette detected, Identifier biomeId, int variant) {
		Palette base = paletteForPlan(detected, biomeId, variant);
		String stem = de.villagerplus.village.VillageAdmin.woodStemAt(level, origin);
		return stem.isEmpty() ? base : Supply.withWoodStem(base, stem);
	}

	/** Plain cobblestone instead of stone bricks and similar smelted full blocks: no furnace, coal or smelting time. */
	static List<Step> rusticStone(List<Step> steps, int fromStep) {
		List<Step> result = new ArrayList<>(steps.size());
		for (int i = 0; i < steps.size(); i++) {
			Step step = steps.get(i);
			// Wool and carpet are decoration: when unpaid the step is skipped instead of stopping the whole plan.
			if (i >= fromStep && !step.free() && !step.optional() && step.linkedTo() == null && step.cost() > 0 && step.tag() == null
				&& step.item() != null && isDecorCloth(step.item())) {
				result.add(new Step(step.pos(), step.item(), null, step.cost(), true, step.linkedTo(), step.make()));
				continue;
			}
			if (i < fromStep || step.free() || step.optional() || step.linkedTo() != null || step.cost() <= 0 || step.tag() != null
				|| step.item() == null || !(step.item() == Items.STONE_BRICKS || step.item() == Items.CRACKED_STONE_BRICKS
					|| step.item() == Items.MOSSY_STONE_BRICKS || step.item() == Items.STONE || step.item() == Items.SMOOTH_STONE)) {
				result.add(step);
				continue;
			}
			result.add(new Step(step.pos(), Items.COBBLESTONE, null, step.cost(), step.optional(), step.linkedTo(),
				ignored -> Blocks.COBBLESTONE.defaultBlockState()));
		}
		return List.copyOf(result);
	}

	private static <T extends Comparable<T>> BlockState copyProperty(BlockState target, BlockState source,
			net.minecraft.world.level.block.state.properties.Property<T> property) {
		return target.setValue(property, source.getValue(property));
	}

	/** Stone counterpart of a wooden stair, slab, fence or barrel (doors and trapdoors have none), or null. */
	private static Block stoneForWoodShape(Item item) {
		Block block = Block.byItem(item);
		BlockState state = block.defaultBlockState();
		if (state.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS)) return Blocks.COBBLESTONE_STAIRS;
		if (state.is(net.minecraft.tags.BlockTags.WOODEN_SLABS)) return Blocks.COBBLESTONE_SLAB;
		if (state.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)) return Blocks.COBBLESTONE_WALL;
		// Hay needs nine wheat, which a grass-free village cannot grow; a barrel is wood. Both become plain stone.
		if (block == Blocks.BARREL || block == Blocks.HAY_BLOCK) return Blocks.COBBLESTONE;
		return null;
	}

	/** Marker stored as the village's wood stem when it builds without wood (planks and logs become cobblestone). */
	private static final String WOODLESS_STEM = "stone";

	/** Full wooden blocks (planks, logs) become cobblestone: for places where the surroundings offer too little wood. */
	static List<Step> woodlessStone(List<Step> steps, int fromStep) {
		List<Step> result = new ArrayList<>(steps.size());
		for (int i = 0; i < steps.size(); i++) {
			Step step = steps.get(i);
			if (i >= fromStep && !step.free() && !step.optional() && step.linkedTo() == null && step.cost() > 0 && step.tag() != null
				&& step.tag().location().getPath().matches("(wooden_)?(doors|trapdoors)")) {
				// Doors cannot be had without wood: the opening stays open.
				result.add(new Step(step.pos(), step.item(), step.tag(), step.cost(), true, step.linkedTo(), step.make()));
				continue;
			}
			if (i < fromStep || step.free() || step.optional() || step.linkedTo() != null || step.cost() <= 0 || step.tag() != null
				|| step.item() == null) {
				result.add(step);
				continue;
			}
			// Without wood and a furnace nobody can deliver doors or glass: those openings stay open instead of freezing the plan.
			Block openingBlock = Block.byItem(step.item());
			BlockState openingState = openingBlock.defaultBlockState();
			if (openingState.is(net.minecraft.tags.BlockTags.WOODEN_DOORS) || openingState.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS)
				|| openingBlock == Blocks.GLASS_PANE || openingBlock == Blocks.GLASS || openingBlock == Blocks.CHEST) {
				result.add(new Step(step.pos(), step.item(), null, step.cost(), true, step.linkedTo(), step.make()));
				continue;
			}
			Block stoneShape = stoneForWoodShape(step.item());
			if (stoneShape != null) {
				Step original = step;
				Block originalBlock = Block.byItem(step.item());
				result.add(new Step(step.pos(), stoneShape.asItem(), null, step.cost(), step.optional(), step.linkedTo(), ignored -> {
					BlockState source = original.make().apply(originalBlock);
					BlockState target = stoneShape.defaultBlockState();
					for (net.minecraft.world.level.block.state.properties.Property<?> property : source.getProperties()) {
						if (target.hasProperty(property)) target = copyProperty(target, source, property);
					}
					return target;
				}));
				continue;
			}
			if (!(new ItemStack(step.item()).is(net.minecraft.tags.ItemTags.PLANKS)
				|| new ItemStack(step.item()).is(net.minecraft.tags.ItemTags.LOGS))) {
				result.add(step);
				continue;
			}
			result.add(new Step(step.pos(), Items.COBBLESTONE, null, step.cost(), step.optional(), step.linkedTo(),
				ignored -> Blocks.COBBLESTONE.defaultBlockState()));
		}
		return List.copyOf(result);
	}

	/** Last resort after a long stall on wood: build the remaining full blocks from cobblestone instead. */
	private static boolean trySwitchToStoneForWood(ServerLevel level, VillageRef ref) {
		VillageData d = ref.data();
		if (!d.hasPlan() || d.planOrigin.isEmpty() || (!generated(d.planType) && d.planSource.isEmpty())) return false;
		if (WOODLESS_STEM.equals(de.villagerplus.village.VillageAdmin.woodStem(ref))) return false;
		boolean needsWood = false;
		for (Item item : PlanSupplyOrder.shortages(d.wanted).keySet()) {
			ItemStack probe = new ItemStack(item);
			if (probe.is(net.minecraft.tags.ItemTags.PLANKS) || probe.is(net.minecraft.tags.ItemTags.LOGS) || stoneForWoodShape(item) != null) needsWood = true;
		}
		if (!needsWood) return false;
		BlockPos origin = d.planOrigin.get();
		long key = origin.asLong();
		List<Step> current = STEPS.get(key);
		if (current == null || current.isEmpty()) return false;
		String previousStem = de.villagerplus.village.VillageAdmin.woodStem(ref);
		ConstructionSite previousSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		de.villagerplus.village.VillageAdmin.setWoodStem(ref, WOODLESS_STEM);
		try {
			STEPS.remove(key);
			List<Step> candidate = stepsFor(level, ref, origin, Direction.from2DDataValue(d.planDir), null);
			List<Step> stable = candidate == null ? null : preservePlanGeometry(woodlessStone(current, 0), candidate, d.planStep);
			if (stable == null) {
				de.villagerplus.village.VillageAdmin.setWoodStem(ref, previousStem);
				rollbackPlanFallback(ref, key, current, d.planVariant, previousSite);
				return false;
			}
			STEPS.put(key, stable);
			// The wood stem is village-wide: other cached plans were generated with wood and must be rebuilt with the new stem.
			STEPS.keySet().removeIf(other -> other != key && !REPAIRS.contains(other)
				&& net.minecraft.core.BlockPos.of(other).distSqr(ref.bell()) < 200.0 * 200.0);
			d.wanted.clear();
			LOGGER.info("Villagers: Repurposed: Bauplan {} baut Bretter und Staemme als Bruchstein (zu wenig Holz in der Umgebung)", d.planType);
			return true;
		} catch (RuntimeException failure) {
			de.villagerplus.village.VillageAdmin.setWoodStem(ref, previousStem);
			rollbackPlanFallback(ref, key, current, d.planVariant, previousSite);
			LOGGER.warn("Could not rebuild plan {} without wood", d.planType, failure);
			return false;
		}
	}

	private static boolean isDecorCloth(Item item) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return path.endsWith("_wool") || path.endsWith("_carpet");
	}

	/** After a stall that needs smelted stone, the village builds the rest of the plan from plain cobblestone. */
	private static boolean trySwitchToRusticStone(ServerLevel level, VillageRef ref) {
		VillageData d = ref.data();
		if (!d.hasPlan() || d.planOrigin.isEmpty() || (!generated(d.planType) && d.planSource.isEmpty())) return false;
		if (de.villagerplus.village.VillageAdmin.rusticStone(ref)) return false;
		boolean needsSmelted = false;
		for (Item item : PlanSupplyOrder.shortages(d.wanted).keySet()) {
			if (item == Items.STONE_BRICKS || item == Items.CRACKED_STONE_BRICKS || item == Items.MOSSY_STONE_BRICKS
				|| item == Items.STONE || item == Items.SMOOTH_STONE || isDecorCloth(item)) needsSmelted = true;
		}
		if (!needsSmelted) return false;
		BlockPos origin = d.planOrigin.get();
		long key = origin.asLong();
		List<Step> current = STEPS.get(key);
		if (current == null || current.isEmpty()) return false;
		ConstructionSite previousSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		de.villagerplus.village.VillageAdmin.adoptRusticStone(ref);
		try {
			STEPS.remove(key);
			List<Step> candidate = stepsFor(level, ref, origin, Direction.from2DDataValue(d.planDir), null);
			List<Step> stable = candidate == null ? null : preservePlanGeometry(rusticStone(current, 0), candidate, d.planStep);
			if (stable == null) {
				de.villagerplus.village.VillageAdmin.clearRusticStone(ref);
				rollbackPlanFallback(ref, key, current, d.planVariant, previousSite);
				return false;
			}
			STEPS.put(key, stable);
			d.wanted.clear();
			LOGGER.info("Villagers: Repurposed: Bauplan {} baut ab jetzt mit Bruchstein statt Steinziegeln (kein Ofen noetig)", d.planType);
			return true;
		} catch (RuntimeException failure) {
			de.villagerplus.village.VillageAdmin.clearRusticStone(ref);
			rollbackPlanFallback(ref, key, current, d.planVariant, previousSite);
			LOGGER.warn("Could not rebuild plan {} with cobblestone", d.planType, failure);
			return false;
		}
	}

	/**
	 * After a long stall on a species the surroundings do not offer, the village adopts the wood that actually grows
	 * around it and rebuilds the remaining steps of the active plan with that species.
	 */
	private static boolean trySwitchToLocalWood(ServerLevel level, VillageRef ref) {
		VillageData d = ref.data();
		if (!d.hasPlan() || (!generated(d.planType) && d.planSource.isEmpty()) || d.planOrigin.isEmpty()) return false;
		BlockPos origin = d.planOrigin.get();
		long key = origin.asLong();
		List<Step> current = STEPS.get(key);
		if (current == null || current.isEmpty()) return false;
		// The village chooses its local wood once (nearest trees win) and then sticks to it; flipping between two sparse species
		// would restart the plan every stall.
		String previousStem = de.villagerplus.village.VillageAdmin.woodStem(ref);
		if (!previousStem.isEmpty()) return false;
		String stem = de.villagerplus.village.VillageAdmin.adoptLocalWood(level, ref);
		if (stem.isEmpty()) return false;
		ConstructionSite previousSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		try {
			STEPS.remove(key);
			List<Step> candidate = stepsFor(level, ref, origin, Direction.from2DDataValue(d.planDir), null);
			List<Step> stable = candidate == null ? null : preservePlanGeometry(current, candidate, d.planStep);
			if (stable == null) {
				de.villagerplus.village.VillageAdmin.setWoodStem(ref, previousStem);
				rollbackPlanFallback(ref, key, current, d.planVariant, previousSite);
				return false;
			}
			STEPS.put(key, stable);
			d.wanted.clear();
			LOGGER.info("Villagers: Repurposed: Bauplan {} baut ab jetzt mit der Holzart '{}' (naechste Baeume, vorher '{}')", d.planType, stem, previousStem);
			return true;
		} catch (RuntimeException failure) {
			de.villagerplus.village.VillageAdmin.setWoodStem(ref, previousStem);
			rollbackPlanFallback(ref, key, current, d.planVariant, previousSite);
			LOGGER.warn("Could not rebuild plan {} with local wood", d.planType, failure);
			return false;
		}
	}

	private record SupportColumn(List<BlockPos> positions, boolean anchored, boolean blocked) { }
	private record SupportResult(int placed, boolean complete, boolean waitingForMaterial, boolean blocked, boolean budgetLimited) { }

	/** Find a bounded gap without loading chunks; positions are returned bottom-up for safe placement. */
	private static SupportColumn scanSupport(ServerLevel level, BlockPos target) {
		List<BlockPos> gapTopDown = new ArrayList<>();
		for (int depth = 1; depth <= SUPPORT_DEPTH; depth++) {
			BlockPos pos = target.below(depth);
			if (!level.hasChunkAt(pos)) return new SupportColumn(List.copyOf(gapTopDown), false, true);
			BlockState state = level.getBlockState(pos);
			if (state.isFaceSturdy(level, pos, Direction.UP)) {
				return new SupportColumn(bottomUp(gapTopDown), true, false);
			}
			if (state.hasBlockEntity() || occupied(level, pos)) return new SupportColumn(bottomUp(gapTopDown), false, true);
			if (!state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty()) {
				return new SupportColumn(bottomUp(gapTopDown), false, true);
			}
			gapTopDown.add(pos);
		}
		return new SupportColumn(bottomUp(gapTopDown), false, false);
	}

	private static List<BlockPos> bottomUp(List<BlockPos> topDown) {
		List<BlockPos> result = new ArrayList<>(topDown);
		java.util.Collections.reverse(result);
		return List.copyOf(result);
	}

	/** Pay and place only support blocks that still occupy a replaceable gap below a verified solid anchor. */
	private static SupportResult fillSupport(Ctx c, VillageRef ref, BlockPos origin, Step step, Container box, int budget) {
		if (step.free() || step.item() == null || step.optional() || step.linkedTo() != null || step.pos().getY() > origin.getY()
			|| step.pos().getY() < origin.getY() - 1) {
			return new SupportResult(0, true, false, false, false);
		}
	ServerLevel level = c.level();
		Block base = supportBase(c, ref, origin);
		SupportColumn column = scanSupport(level, step.pos());
		if (base == Blocks.AIR || column.blocked() || !column.anchored()) return new SupportResult(0, false, false, true, false);
		int placed = 0;
		for (BlockPos pos : column.positions()) {
			if (placed >= budget) return new SupportResult(placed, false, false, false, true);
			if (!level.hasChunkAt(pos)) return new SupportResult(placed, false, false, true, false);
			BlockState previous = level.getBlockState(pos);
			if (previous.hasBlockEntity() || occupied(level, pos)
				|| (!previous.isAir() && !previous.canBeReplaced() && previous.getFluidState().isEmpty())) {
				return new SupportResult(placed, false, false, true, false);
			}
			Item baseItem = base.asItem();
			if (baseItem == Items.AIR || JobUtil.count(box, stack -> stack.is(baseItem)) < 1) {
				return new SupportResult(placed, false, true, false, false);
			}
			BlockState fill = base.defaultBlockState();
			level.setBlock(pos, fill, 3);
			if (!level.getBlockState(pos).is(base)) return new SupportResult(placed, false, false, true, false);
			if (JobUtil.take(box, stack -> stack.is(baseItem), 1) != 1) {
				level.setBlock(pos, previous, 3);
				return new SupportResult(placed, false, true, false, false);
			}
			placed++;
		}
		return new SupportResult(placed, true, false, false, false);
	}

	private static String label(String type) {
		return switch (type) {
			case Blueprints.HOUSE -> "ein neues Haus";
			case Blueprints.FARM -> "ein neues Feld";
			case Blueprints.PEN -> "ein Tiergehege";
			case Blueprints.DECOR -> "einen Aufbau fuer den Dorfreichtum";
			case Blueprints.STORAGE -> "einen Lagerschuppen";
			case Blueprints.SCHOOL -> "die Dorfschule";
			case Blueprints.GRANARY -> "den Kornspeicher";
			case Blueprints.TOOLSHED -> "die Werkzeug- und Rüstungskammer";
			case Blueprints.KITCHEN -> "die Dorfküche mit Bäckerei";
			case Blueprints.STATION -> "den Bahnhof";
			case Blueprints.TOWNHALL -> "das Rathaus";
			case Blueprints.WALL -> "die Stadtmauer";
			default -> "einen Brunnen";
		};
	}

	// --- Bauen ----------------------------------------------------------------------------------

	/** Houses of the compact and standard size tiers are built from the shipped small-house template. */
	private static boolean templateEligible(Architect.Design design) {
		return design != null && design.kind() == Architect.Kind.HOUSE && design.hw() <= 4 && design.hd() <= 3;
	}

	private static List<Step> newHouseTemplatePreview(ServerLevel level, VillageRef ref, String type,
		Architect.Design design, BlockPos origin, Direction dir) {
		if (!Blueprints.HOUSE.equals(type) || !templateEligible(design)) return null;
		List<Step> preview = previewTemplateSteps(level, ref, origin, dir, "haus_klein");
		return preview == null || preview.isEmpty() ? null : preview;
	}

	private static void markTemplatePlan(VillageRef ref, BlockPos origin) {
		List<Long> marked = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of());
		if (marked.contains(origin.asLong())) return;
		List<Long> next = new ArrayList<>(marked);
		next.add(origin.asLong());
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.copyOf(next));
		ref.dirty();
	}

	/**
	 * Reconstruct only explicitly marked template plans; unmarked plans retain their generated design.
	 */
	private static List<Step> templateSteps(ServerLevel level, VillageRef ref, BlockPos origin, Direction dir, VillageData d, String type) {
		List<Long> marked = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of());
		boolean isMarked = marked.contains(origin.asLong());
		// An unmarked saved plan was created by the generated-design path. Never change its
		// footprint on a cache miss, even before its first block has been placed.
		if (!isMarked) return null;
		List<Step> steps = previewTemplateSteps(level, ref, origin, dir, type);
		if (steps == null || steps.isEmpty()) return null;
		return steps;
	}

	/** Preview the exact paid template without marking an unaccepted candidate or altering its saved plan. */
	static List<Step> previewTemplateSteps(ServerLevel level, VillageRef ref, BlockPos origin, Direction dir, String type) {
		Identifier biome = biomeAt(level, origin);
		HouseTemplate template = TemplateLibrary.get(type, TemplateLibrary.family(biome));
		if (template == null) return null;
		VillageStyle finalStyle = VillageStyle.detect(level, ref.bell(), null);
		boolean mirrored = (Long.hashCode(origin.asLong()) & 1) == 1;
		return template.steps(origin, dir, Config.MATERIAL_COST_PERCENT, mirrored,
			bl -> Supply.substitute(bl, finalStyle, biome), true);
	}

	/** Dev check hook: the steps of the current plan, computed exactly as during building. */
	static List<Step> stepsForCheck(ServerLevel level, VillageRef ref, BlockPos origin, Direction dir) {
		return stepsFor(level, ref, origin, dir, null);
	}

	/** Dev check hook: drops the cached steps like a restart does. */
	static void forgetStepsForCheck(BlockPos origin) {
		STEPS.remove(origin.asLong());
	}

	private static List<Step> stepsFor(ServerLevel level, VillageRef ref, BlockPos origin, Direction dir, Ctx c) {
		VillageData d = ref.data();
		List<Step> steps = STEPS.get(origin.asLong());
		if (steps != null) {
			return steps;
		}
		boolean templated = false;
		if (generated(d.planType) && d.planSource.isEmpty()) {
			ConstructionSite savedSite = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			Palette savedPalette = savedSite != null && savedSite.origin().equals(origin) ? Palette.ofIds(savedSite.paletteIds()) : null;
			Palette palette = PALETTES.computeIfAbsent(origin.asLong(), k -> savedPalette != null ? savedPalette
				: Palette.detect(level, ref.bell(), c == null ? null : c.v(), d.planVariant >>> 2, origin));
			palette = paletteForPlan(level, origin, palette, biomeAt(level, origin), d.planVariant);
			if (savedSite != null && savedSite.origin().equals(origin) && !savedSite.paletteIds().equals(palette.ids())) {
				ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION,
					new ConstructionSite(savedSite.origin(), savedSite.temporary(), savedSite.phaseEnds(), savedSite.prepared(), palette.ids()));
				ref.dirty();
			}
			Architect.Design planDesign = architect(d.planType) ? designForPlan(d.planType, d.planVariant) : null;
			steps = architect(d.planType) ? Architect.build(planDesign, palette, origin, dir)
				: Landmarks.build(d.planType, d.planVariant >>> 2, palette, origin, dir);
			ConstructionSite site = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
			templated = false;
			if (d.planType.equals(Blueprints.HOUSE) && planDesign != null && templateEligible(planDesign)) {
				List<Step> fromTemplate = templateSteps(level, ref, origin, dir, d, "haus_klein");
				if (fromTemplate != null && !fromTemplate.isEmpty()) {
					steps = fromTemplate;
					templated = true;
				} else if (ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(origin.asLong())) {
					// A marked plan must never resume at the same cursor in a different design.
					return null;
				}
			}
			if (compactStorage(planDesign)) steps = addPaidFirstStorageChest(steps, origin, dir, planDesign);
			if (architect(d.planType)) {
				steps = ConstructionSite.forPlan(steps, origin, planDesign, palette, site);
				if (compactStorage(planDesign) && site != null && site.origin().equals(origin) && !site.phaseEnds().isEmpty()) {
					List<Integer> phaseEnds = ConstructionSite.phaseEnds(steps, origin, planDesign, palette);
					if (!phaseEnds.equals(site.phaseEnds())) {
						site = new ConstructionSite(site.origin(), site.temporary(), phaseEnds, site.prepared(), site.paletteIds());
						ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, site);
						ref.dirty();
					}
				}
			}
		} else if (d.planSource.isPresent()) {
			HouseTemplate t = HouseTemplate.capture(level, d.planSource.get(), ref.bell());
			if (t == null) {
				return null;
			}
			VillageStyle style = STYLES.computeIfAbsent(origin.asLong(), k -> VillageStyle.detect(level, ref.bell(), c == null ? null : c.v()));
			// Bit 0 = gespiegelt, Bit 1 = Ersatzmaterial (erst wenn die Haendler das Fehlende nicht liefern konnten).
			boolean substitute = (d.planVariant & 2) != 0;
			steps = t.steps(origin, dir, Config.MATERIAL_COST_PERCENT, (d.planVariant & 1) != 0,
				bl -> substitute ? Supply.substitute(bl, style, level.getBiome(origin).unwrapKey()
					.map(k -> k.identifier()).orElse(Identifier.withDefaultNamespace("plains"))) : bl,
				d.planType.equals(Blueprints.HOUSE));
		} else {
			VillageStyle style = STYLES.computeIfAbsent(origin.asLong(), k -> VillageStyle.detect(level, ref.bell(), c == null ? null : c.v()));
			steps = Blueprints.create(d.planType, style, origin, dir, d.planVariant);
		}
		if (d.planType.equals(Blueprints.HOUSE) && steps != null && !templated) {
			boolean legacyYard = usesLegacySavedHouseYard(ref, origin);
			int yardStyle = Paths.yardStyle(level, origin, dir, legacyYard);
			steps = appendHouseYard(steps, Paths.yardSteps(level, origin, dir, yardStyle, legacyYard));
		}
		if (steps != null && de.villagerplus.village.VillageAdmin.rusticStone(ref)) steps = rusticStone(steps, 0);
		if (steps != null && WOODLESS_STEM.equals(de.villagerplus.village.VillageAdmin.woodStem(ref))) steps = woodlessStone(steps, 0);
		STEPS.put(origin.asLong(), steps);
		return steps;
	}

	private static boolean continuePlan(Ctx c, VillageRef ref, Container box, Container siteStock,
			List<String> cargoManifest, List<Step> preparedSteps, int standSlot, boolean helper) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		BlockPos origin = d.planOrigin.get();
		Direction dir = Direction.from2DDataValue(d.planDir);
		List<Step> steps = preparedSteps == null ? stepsFor(level, ref, origin, dir, c) : preparedSteps;
		// Keep supplier demand aligned with the persisted cursor, but do not gate the plan on
		// materials for later blocks. The placement loop stops only at its first unavailable input.
		if (!helper && preparedSteps == null && steps != null && !d.planType.equals(Blueprints.FARM)) {
			updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
		}
		why = "baut";
		c.setActivity("baut " + label(d.planType));
		if (steps != null) {
			// Nicht mitten im Bauwerk stehen (sonst mauert sich der Baumeister ein): Standort vor dem Gebaeude.
			int minimumStandY = villageGroundY(level, ref.bell()) - 12;
			BlockPos stand = standSlot >= 0
				? workStand(c, origin, dir, steps, minimumStandY, standSlot)
				: workStand(c, origin, dir, steps, minimumStandY);
			if (stand != null && stand.getY() < villageGroundY(c.level(), ref.bell()) - 12) {
				if (helper) return false;
				rejectPlot(ref, origin);
				abort(d, ref, c.now());
				why = "Baustand liegt unter dem Dorf";
				return false;
			}
			if (stand == null) {
				if (helper) return false;
				why = "kein erreichbarer sicherer Arbeitsplatz";
				c.setActivity("wartet auf einen erreichbaren sicheren Arbeitsplatz");
				c.v().getNavigation().stop();
				c.v().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
				c.st().cooldownUntil = c.now() + 40;
				// A failed route from a distant worker is not proof that the plot is unreachable.
				// Re-approach the actual village street and retry from there before rejecting it.
				long standFailureSince = STAND_FAILURE_SINCE.computeIfAbsent(origin.asLong(), ignored -> c.now());
				BlockPos street = villageReturn(level, ref.bell());
				if (street != null && c.v().blockPosition().distSqr(street) > 4) {
					JobUtil.approachLong(c, street, 2.0);
					c.setActivity("geht zur Dorfstrasse und sucht einen Baustand");
				}
				if (c.now() - standFailureSince > STAND_GIVE_UP) {
					why = "kein Weg zum Bauplatz";
					LOGGER.info("Villagers: Repurposed: Bau '{}' Schritt {} wird abgebrochen ({}) {}", d.planType, d.planStep, why, lastStandReport(c.v()));
					rejectPlot(ref, origin);
					abort(d, ref, c.now());
					return false;
				}
				return true;
			}
			if (!helper) STAND_FAILURE_SINCE.remove(origin.asLong());
			if (!JobUtil.approach(c, stand, 6.0)) {
				// Vanilla path searches stop around 16 blocks, so hand the brain the next node on the
				// already verified route instead of the distant stand itself.
				BlockPos waypoint = routeWaypoint(c, stand);
				c.v().getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
					new net.minecraft.world.entity.ai.memory.WalkTarget(waypoint, (float) Math.min(0.55, 0.45 * de.villagerplus.job.Skills.speed(c.mem())), 1));
				// lastMoveAt is also refreshed by the stuck detector, so it cannot prove the mason is closing on the stand.
				double dist = c.v().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand));
				Double best = helper ? null : STAND_BEST.get(origin.asLong());
				// A winding route around cliffs lengthens the straight-line distance for a while; a worker that keeps
				// covering ground on its verified route is still making progress and must not be given up on.
				boolean movedOnRoute = false;
				if (!helper) {
					net.minecraft.world.phys.Vec3 here = c.v().position();
					net.minecraft.world.phys.Vec3 anchor = STAND_MOVE_ANCHOR.get(origin.asLong());
					if (anchor == null || here.distanceToSqr(anchor) >= 16.0) {
						// Moving only counts while the remaining verified route actually gets shorter (no aimless wandering).
						movedOnRoute = anchor != null && routeProgress(c, stand, origin.asLong());
						STAND_MOVE_ANCHOR.put(origin.asLong(), here);
					}
				}
				if (!helper && (best == null || dist < best - 1.5)) {
					STAND_BEST.put(origin.asLong(), dist);
					d.planProgress = c.now();
				} else if (movedOnRoute) {
					d.planProgress = c.now();
				} else if (!helper && d.planStep == 0 && c.now() - d.planProgress > STAND_GIVE_UP) {
					why = "Bauplatz nicht erreicht";
					LOGGER.info("Villagers: Repurposed: Bau '{}' Schritt {} wird abgebrochen ({}) Stand {}", d.planType, d.planStep, why, stand.toShortString());
					rejectPlot(ref, origin);
					abort(d, ref, c.now());
					return false;
				}
				why = "geht zur Baustelle " + stand.toShortString() + " von " + c.v().blockPosition().toShortString();
				return true;
			}
			if (!helper) { STAND_BEST.remove(origin.asLong()); STAND_MOVE_ANCHOR.remove(origin.asLong()); STAND_BEST_NODES.remove(origin.asLong()); }
			if (insideFootprint(c, steps)) {
				JobUtil.approachLong(c, stand, 1.0);
				return true;
			}
		} else if (!JobUtil.approach(c, origin, 9.0)) {
			return true;
		}
		if (steps == null) {
			// Vorlagenhaus ist verschwunden: Auftrag abbrechen.
			if (helper) return false;
			abort(d, ref, c.now());
			return false;
		}
		if (!helper && !d.planType.equals(Blueprints.FARM)) {
			updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
		}
		List<Step> prepSteps = d.planType.equals(Blueprints.HOUSE)
			? steps.stream().filter(step -> !Paths.isYardStep(level, origin, dir, step.pos())).toList() : steps;
		java.util.Set<Long> fp = d.planType.equals(Blueprints.FARM) ? null : FOOT.computeIfAbsent(origin.asLong(), k -> Prep.footprint(prepSteps, origin));
		if (d.planStep == 0 && civicSite(d.planType) && architect(d.planType) && !REPAIRS.contains(origin.asLong())) {
			// Trees inside a last-resort civic site are removed by the same verified natural-site clearing the house lots use.
			// A site without natural obstacles reports READY and costs nothing; anything it cannot clear keeps the old behaviour.
			BlockPos[] prepBox = civicPrepBox(origin, steps, designForPlan(d.planType, d.planVariant).headroom());
			if (helper) {
				// Helpers must not start the (free) foundation steps over standing trees; the plan owner clears them first.
				NaturalSitePreparation.Status pending = NaturalSitePreparation.inspect(level, prepBox[0], prepBox[1], MAX_CIVIC_SITE_OBSTACLES).status();
				if (pending == NaturalSitePreparation.Status.CLEARABLE || pending == NaturalSitePreparation.Status.WAITING_FOR_CHUNK) return false;
			} else {
				NaturalSitePreparation.Scan civicPrep = NaturalSitePreparation.clearOne(level, prepBox[0], prepBox[1],
					MAX_CIVIC_SITE_OBSTACLES, c.v());
				if (CIVIC_PREP_STATUS.put(origin.asLong(), civicPrep.status()) != civicPrep.status()) {
					LOGGER.info("Villagers: Repurposed: Bauplatz {} fuer '{}': Raeumung {} (naechstes Hindernis {}, {} offen)", origin.toShortString(),
						d.planType, civicPrep.status(), civicPrep.next() == null ? "-" : civicPrep.next().toShortString()
							+ " " + level.getBlockState(civicPrep.next()).getBlock().getDescriptionId(), civicPrep.obstacleCount());
				}
				if (civicPrep.status() == NaturalSitePreparation.Status.CLEARED) {
					int cleared = CIVIC_CLEARED.merge(origin.asLong(), 1, Integer::sum);
					if (cleared == 1 || cleared % 25 == 0) {
						LOGGER.info("Villagers: Repurposed: Bauplatz {} fuer '{}' wird geraeumt: {} Naturbloecke entfernt, zuletzt bei {}, vorher {} Hindernisse",
							origin.toShortString(), d.planType, cleared, civicPrep.next() == null ? "?" : civicPrep.next().toShortString(), civicPrep.obstacleCount());
					}
					c.setActivity("raeumt den Bauplatz");
					d.planProgress = c.now();
					c.st().cooldownUntil = c.now() + CIVIC_CLEARANCE_RETRY_TICKS;
					ref.dirty();
					return true;
				}
				if (civicPrep.status() == NaturalSitePreparation.Status.WAITING_FOR_CHUNK
					|| civicPrep.status() == NaturalSitePreparation.Status.WAITING_FOR_ENTITY
					|| civicPrep.status() == NaturalSitePreparation.Status.WAITING_FOR_TOOL) {
					c.setActivity("wartet auf einen freien Bauplatz");
					d.planProgress = c.now();
					c.st().cooldownUntil = c.now() + 100;
					ref.dirty();
					return true;
				}
				if (civicPrep.status() == NaturalSitePreparation.Status.WAITING_FOR_INVENTORY) {
					c.setActivity("schafft Platz fuer die Ernte vom Bauplatz");
					d.planProgress = c.now();
					ref.dirty();
					return false;
				}
			}
		}
		if (d.planStep == 0 && needsNaturalHousePreparation(d.planType, d.planVariant, d.planSource.isPresent())) {
			if (!houseLotStillSafe(level, ref, origin, dir, prepSteps, steps, d.planVariant, d.planSource.isPresent())) {
				why = "Hausbauplatz ist inzwischen geschuetzt oder nicht mehr sicher";
				rejectPlot(ref, origin);
				abort(d, ref, c.now());
				c.st().buildCheckAt = c.now() + 40;
				return false;
			}
			NaturalSitePreparation.Scan preparation = clearHouseClearance(level, ref, origin, d.planVariant,
				d.planSource.isPresent(), Paths.entrance(prepSteps, origin, dir), c, true);
			if (preparation.status() == NaturalSitePreparation.Status.CLEARED) {
				c.setActivity("bereitet den Hausbauplatz vor");
				d.planProgress = c.now();
				c.st().cooldownUntil = c.now() + HOUSE_CLEARANCE_RETRY_TICKS;
				ref.dirty();
				return true;
			}
			if (preparation.status() == NaturalSitePreparation.Status.WAITING_FOR_CHUNK
				|| preparation.status() == NaturalSitePreparation.Status.WAITING_FOR_ENTITY) {
				c.setActivity("wartet auf einen freien Hausbauplatz");
				d.planProgress = c.now();
				c.st().cooldownUntil = c.now() + 100;
				ref.dirty();
				return true;
			}
			if (preparation.status() == NaturalSitePreparation.Status.WAITING_FOR_INVENTORY) {
				c.setActivity("schafft Platz für die Ernte vom Hausbauplatz");
				d.planProgress = c.now();
				ref.dirty();
				return false;
			}
			if (preparation.status() == NaturalSitePreparation.Status.WAITING_FOR_TOOL) {
				c.setActivity("wartet auf eine Schaufel für den Hausbauplatz");
				d.planProgress = c.now();
				c.st().cooldownUntil = c.now() + 100;
				ref.dirty();
				return true;
			}
			if (preparation.status() == NaturalSitePreparation.Status.BLOCKED
				|| preparation.status() == NaturalSitePreparation.Status.CANCELLED) {
				why = "Hausbauplatz ist nicht sicher vorbereitbar";
				rejectPlot(ref, origin);
				abort(d, ref, c.now());
				c.st().buildCheckAt = c.now() + 40;
				return false;
			}
		}
		if (!helper && d.planStep == 0 && !REPAIRS.contains(origin.asLong())
			&& !Prep.run(level, origin, steps, 48, fp, PREP_RECORD.computeIfAbsent(origin.asLong(), k -> new HashMap<>()))) {
			why = "bereitet Gelaende vor";
			d.planProgress = c.now();
			ref.dirty();
			JobUtil.swing(c.v());
			return true;
		}
		ConstructionSite construction = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (!helper && construction != null && construction.origin().equals(origin) && d.planStep == 0) {
			ConstructionSite prepared = ConstructionSite.prepare(level, construction, steps, origin, dir, siteStock, c);
			if (!prepared.equals(construction)) {
				ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, prepared);
				ref.dirty();
			}
			if (!prepared.prepared()) {
				// Do not start the building while its paid worksite setup is only partial. Persist exact
				// shortages so the owner can return to its normal supplier cycle, then resume this setup.
				updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
				d.planProgress = c.now();
				ref.dirty();
				why = "wartet auf Baustellenausstattung";
				c.setActivity(d.wanted.isEmpty() ? "Baustellenausstattung wird erneut versucht" : "wartet auf Gerüst- und Werkbankmaterial");
				return false;
			}
		}

		int paidBudget = Config.BUILD_BLOCKS_PER_STEP;
		int freeBudget = 40;
		int supportBudget = SUPPORT_BLOCKS_PER_TICK;
		boolean madeProgress = false;
		while (paidBudget > 0 && freeBudget > 0) {
			if (d.planStep >= steps.size()) {
				if (helper) return madeProgress;
				finish(c, ref, origin);
				return true;
			}
			// Leave completion and its cleanup/registration side effects to the project owner.
			if (helper && d.planStep >= steps.size() - 1) return madeProgress;
			Step s = steps.get(d.planStep);
			PLAN_DIAGNOSTICS.put(origin.asLong(), describeStep(level, s, d.planStep, steps.size()));
			if (fp != null && s.free() && !Prep.contains(fp, s.pos().getX(), s.pos().getZ())) {
				s = snap(level, origin, s);
				if (s == null) {
					d.planStep++;
					madeProgress = true;
					freeBudget--;
					continue;
				}
			}
			BlockState cur = level.getBlockState(s.pos());
			boolean ground = s.pos().getY() < origin.getY();
			boolean replaceable = cur.isAir() || cur.canBeReplaced() || (ground && !cur.hasBlockEntity());
			boolean done;
			if (s.linkedTo() != null) {
				done = cur.is(level.getBlockState(s.linkedTo()).getBlock());
			} else if (s.free()) {
				done = cur.is(s.make().apply(Blocks.AIR).getBlock());
			} else {
				done = cur.is(Block.byItem(s.item()));
			}

			if (!done && replaceable && occupied(level, s.pos())) {
				// Ein Lebewesen steht im Weg: warten, nichts einmauern.
				List<String> blockers = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
					new AABB(s.pos()).inflate(0.1), e -> true).stream()
					.map(e -> e.getName().getString() + "[" + e.blockPosition().toShortString() + "]").limit(3).toList();
				PLAN_DIAGNOSTICS.put(origin.asLong(), PLAN_DIAGNOSTICS.get(origin.asLong()) + "; blockiert von " + String.join(", ", blockers));
				return true;
			}
			if (!done && d.planType.equals(Blueprints.HOUSE)
				&& Paths.isYardStep(level, origin, dir, s.pos())
				&& !Paths.yardCellClear(level, s, Paths.yardStyle(level, origin, dir))) {
				// A later tree, decoration or structure may have claimed this open yard cell.
				// Preserve it and advance without spending inventory.
				d.planStep++;
				d.planProgress = c.now();
				madeProgress = true;
				freeBudget--;
				continue;
			}
			if ((done || replaceable) && !s.free() && !s.optional() && s.linkedTo() == null) {
				SupportResult support = fillSupport(c, ref, origin, s, siteStock, supportBudget);
				supportBudget -= support.placed();
				if (!support.complete()) {
					if (!helper) updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
					if (helper) {
						if (support.placed() > 0) d.planProgress = c.now();
						ref.dirty();
						if (support.placed() > 0 || support.budgetLimited()) {
							c.setActivity("errichtet ein Fundament");
							return true;
						}
						return madeProgress;
					}
					if (support.waitingForMaterial()) {
						if (support.placed() > 0) d.planProgress = c.now();
						ref.dirty();
						why = "wartet auf Fundamentmaterial";
						c.setActivity("wartet auf Fundamentmaterial");
						return false;
					}
					if (support.budgetLimited()) {
						d.planProgress = c.now();
						ref.dirty();
						why = "fuellt Fundament";
						c.setActivity("fuellt Fundament");
						return true;
					}
					why = support.blocked() ? "unbrauchbare Fundamentstuetze" : "unvollstaendige Fundamentstuetze";
					LOGGER.info("Villagers: Repurposed: Bauplatz {} verworfen (keine sichere Fundamentstuetze bei {})",
						origin.toShortString(), s.pos().toShortString());
					rejectPlot(ref, origin);
					abort(d, ref, c.now());
					return false;
				}
			}

			boolean placed = false;
			if (!done && replaceable) {
				Payment payment = null;
				Block paid = Blocks.AIR;
				if (s.linkedTo() != null) {
					paid = level.getBlockState(s.linkedTo()).getBlock();
				} else if (!s.free()) {
					payment = paymentFor(s, siteStock);
					paid = payment == null ? null : payment.block();
				}
				if (paid == null) {
					if (farmStepRequiresPayment(d.planType, s)) {
						if (helper) {
							c.setActivity("wartet auf Baumaterial am Baustellendepot");
							return madeProgress;
						}
						JobUtil.tellNearbyPlayers(level, c.v(), 32,
							Component.literal("Dem Baumeister geht das Material aus – die Dorfbewohner beschaffen Nachschub."));
						updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
						d.planProgress = c.now();
						ref.dirty();
						// Let the mason's job continue into Workshop/Mining in this same work cycle.
						return false;
					}
				} else {
					BlockState ns;
					try {
						ns = s.make().apply(paid);
					} catch (RuntimeException invalidState) {
						if (!helper) updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
						PLAN_DIAGNOSTICS.put(origin.asLong(), describeStep(level, s, d.planStep, steps.size()) + "; ungueltiger Zielzustand");
						return madeProgress;
					}
					if (ns == null) {
						if (!helper) updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
						PLAN_DIAGNOSTICS.put(origin.asLong(), describeStep(level, s, d.planStep, steps.size()) + "; kein Zielzustand");
						return madeProgress;
					}
					if (payment != null) {
						Item paidItem = payment.item();
						int taken = JobUtil.take(siteStock, stack -> stack.is(paidItem), payment.count());
						if (taken != payment.count()) {
							if (taken > 0) refund(level, c, siteStock, new ItemStack(paidItem, taken));
							if (!helper) updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
							if (!helper) d.planProgress = c.now();
							ref.dirty();
							return madeProgress;
						}
					}
					boolean changed = level.setBlock(s.pos(), ns, 3);
					if (!level.getBlockState(s.pos()).is(ns.getBlock())) {
						if (payment != null) refund(level, c, siteStock, new ItemStack(payment.item(), payment.count()));
						if (!helper) updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
						if (!helper) d.planProgress = c.now();
						ref.dirty();
						PLAN_DIAGNOSTICS.put(origin.asLong(), describeStep(level, s, d.planStep, steps.size())
							+ (changed ? "; Blockzustand nach Platzierung abgewichen" : "; Platzierung abgelehnt"));
						return madeProgress;
					}
					Anim.blockPlace(level, s.pos(), ns, 1.0F, 1.0F);
					Anim.hold(c, s.item() != null ? s.item() : Items_BRICK());
					JobUtil.swing(c.v());
					placed = true;
				}
			}
			if (placed && !s.free()) {
				paidBudget--;
			} else {
				freeBudget--;
			}
			d.planStep++;
			d.planProgress = c.now();
			madeProgress = true;
		}
		if (!helper && !d.planType.equals(Blueprints.FARM)) {
			updateWanted(c, ref, d.planType, steps, box, siteStock, cargoManifest, origin, d.planStep, false);
		}
		ref.dirty();
		return madeProgress || !helper;
	}

	private static Item Items_BRICK() {
		return net.minecraft.world.item.Items.BRICK;
	}

	/** Bounded perimeter search: the building's anchor height says nothing about the ground outside it. */
	public static BlockPos constructionStand(Ctx c) {
		VillageData data = c.village().data();
		if (!data.hasPlan() || data.planOrigin.isEmpty()) return null;
		BlockPos origin = data.planOrigin.get();
		List<Step> steps = STEPS.get(origin.asLong());
		if (steps == null) return null;
		return workStand(c, origin, Direction.from2DDataValue(data.planDir), steps);
	}

	private static final java.util.Map<Long, java.util.Set<Long>> REJECTED_PLOTS = new java.util.HashMap<>();
	private static final long FAILED_SITE_SWEEP_RETRY_TICKS = 1800L;
	/** Since when the civic site search of the open milestone finds nothing (per village bell); houses may jump the queue after a while. */
	private static final java.util.Map<Long, Long> SPECIAL_SEARCH_FAILING_SINCE = new java.util.HashMap<>();
	static final long SPECIAL_SITE_STALL_TICKS = 3600L;

	static void noteSpecialSiteSearchFailure(VillageRef ref, long now) {
		SPECIAL_SEARCH_FAILING_SINCE.putIfAbsent(ref.bell().asLong(), now);
	}

	static void clearSpecialSiteSearchFailure(VillageRef ref) {
		SPECIAL_SEARCH_FAILING_SINCE.remove(ref.bell().asLong());
	}

	/** True when a milestone without a site has been searching for a while and homeless villagers need a house first. */
	static boolean specialSiteSearchStalled(VillageRef ref, String special, long now, boolean needHouse, long homeless) {
		if (special == null || !needHouse || homeless <= 0) return false;
		Long since = SPECIAL_SEARCH_FAILING_SINCE.get(ref.bell().asLong());
		return since != null && now - since >= SPECIAL_SITE_STALL_TICKS;
	}
	/** After a pen found no site the farmer builds its field first and only retries the pen after this long. */
	private static final long PEN_SITE_RETRY_TICKS = 12000L;
	private static final java.util.Map<Long, Long> PEN_SITE_BLOCKED_UNTIL = new java.util.HashMap<>();
	/** A plan without material progress for this long swaps unobtainable palette blocks for local ones without a caravan. */
	private static final long STALLED_PLAN_PALETTE_FALLBACK_TICKS = 3000L;
	/** Per plan origin: the open demand signature and the game tick since which it has not changed. */
	private record PlanStall(String signature, long since) { }
	private static final java.util.Map<Long, PlanStall> PLAN_STALLS = new java.util.HashMap<>();
	private static final int HOUSE_SITE_CANDIDATE_BUDGET = 96;
	private static final long HOUSE_SITE_RETRY_TICKS = 20L;
	private static final long HOUSE_SITE_REPROBE_TICKS = 1200L;
	/** An idle house search (no call for this long) is dropped, in memory and in the saved village data. */
	private static final long HOUSE_SITE_STALE_TICKS = 24000L;
	private static final Map<String, HouseSiteSearch> HOUSE_SITE_SEARCHES = new HashMap<>();
	/** Throttle of the "fortgesetzt" progress line per village bell. */
	private static final Map<Long, Long> HOUSE_SITE_DIAG_AT = new HashMap<>();
	/**
	 * Dev/test hook: with the environment variable {@code VP_FORCE_HOUSE_STEP_SEARCH=1} a new catalog house skips the random
	 * and tolerant site paths and goes straight to the step-wise search, and the mason plans a house first even without bed
	 * demand. Natural runs use it to observe that search on real terrain; the demand and the random paths otherwise pre-empt
	 * it. Never set in normal play.
	 */
	static final boolean FORCE_HOUSE_STEP_SEARCH = "1".equals(System.getenv("VP_FORCE_HOUSE_STEP_SEARCH"));

	/** Dev hook companion: {@code VP_HOUSE_SEARCH_BUDGET} shrinks the page budget so a natural run needs several pages. */
	private static int forcedHouseSearchBudget() {
		try {
			return Math.max(1, Math.min(HOUSE_SITE_CANDIDATE_BUDGET, Integer.parseInt(System.getenv("VP_HOUSE_SEARCH_BUDGET"))));
		} catch (RuntimeException ignored) {
			return HOUSE_SITE_CANDIDATE_BUDGET;
		}
	}

	static final class HouseSiteSearch {
		int variant;
		final int minRadius;
		long phaseSeed;
		int nextCandidate;
		long retryAt;
		long lastUsedAt;
		/** Not persisted: only de-duplicates columns inside one session; after a restart the cursor alone resumes the sweep. */
		final Set<Long> visitedColumns = new HashSet<>();

		HouseSiteSearch(int variant, long phaseSeed, int minRadius, long now) {
			this.variant = variant;
			this.phaseSeed = phaseSeed;
			this.minRadius = minRadius;
			this.lastUsedAt = now;
		}

		HouseSiteSearch(de.villagerplus.village.VillageData.Extra.HouseSiteSearch saved) {
			this(saved.variant(), saved.phaseSeed(), saved.minRadius(), saved.lastUsedAt());
			nextCandidate = saved.nextCandidate();
			retryAt = saved.retryAt();
		}

		void restart(int nextVariant, long nextPhaseSeed, long nextRetryAt) {
			variant = nextVariant;
			phaseSeed = nextPhaseSeed;
			nextCandidate = 0;
			retryAt = nextRetryAt;
			visitedColumns.clear();
		}

		int nextCandidate() { return nextCandidate; }
	}

	private static String houseSiteSearchKey(ServerLevel level, VillageRef ref) {
		return level.dimension().identifier().toString() + ":" + ref.bell().asLong();
	}

	private static void persistHouseSiteSearch(VillageRef ref, HouseSiteSearch search) {
		ref.data().extra.houseSiteSearch = Optional.of(new de.villagerplus.village.VillageData.Extra.HouseSiteSearch(
			search.variant, search.minRadius, search.phaseSeed, search.nextCandidate, search.retryAt, search.lastUsedAt));
		ref.dirty();
	}

	/** Ends the house search of this village: memory entry and the saved cursor, so nothing is left behind. */
	static void dropHouseSiteSearch(ServerLevel level, VillageRef ref) {
		HOUSE_SITE_SEARCHES.remove(houseSiteSearchKey(level, ref));
		HOUSE_SITE_DIAG_AT.remove(ref.bell().asLong());
		if (ref.data().extra.houseSiteSearch.isPresent()) {
			ref.data().extra.houseSiteSearch = Optional.empty();
			ref.dirty();
		}
	}

	/** Test seam: forget the in-memory searches, as a server restart would. The saved cursor stays in the village data. */
	static void forgetHouseSiteSearchMemory() {
		HOUSE_SITE_SEARCHES.clear();
		HOUSE_SITE_DIAG_AT.clear();
	}

	static boolean hasHouseSiteSearchInMemory(ServerLevel level, VillageRef ref) {
		return HOUSE_SITE_SEARCHES.containsKey(houseSiteSearchKey(level, ref));
	}

	/**
	 * The running step-wise house search of this village, if any: from memory, else resumed from the saved cursor.
	 * A search for another minimum radius or a non-catalog plan, or one idle for {@link #HOUSE_SITE_STALE_TICKS}, is dropped.
	 */
	static HouseSiteSearch ongoingHouseSiteSearch(Ctx c, VillageRef ref, int minRadius, boolean hasLegacySource) {
		ServerLevel level = c.level();
		String dimension = level.dimension().identifier().toString() + ":";
		HOUSE_SITE_SEARCHES.entrySet().removeIf(entry -> entry.getKey().startsWith(dimension)
			&& c.now() - entry.getValue().lastUsedAt > HOUSE_SITE_STALE_TICKS);
		HouseSiteSearch search = HOUSE_SITE_SEARCHES.get(houseSiteSearchKey(level, ref));
		if (search == null && ref.data().extra.houseSiteSearch.isPresent()) {
			var saved = ref.data().extra.houseSiteSearch.get();
			if (c.now() - saved.lastUsedAt() > HOUSE_SITE_STALE_TICKS) {
				dropHouseSiteSearch(level, ref);
			} else {
				search = new HouseSiteSearch(saved);
				HOUSE_SITE_SEARCHES.put(houseSiteSearchKey(level, ref), search);
				LOGGER.info("Villagers: Repurposed: Bauplatzsuche fuer 'house' (Glocke {}) aus gespeichertem Cursor wiederaufgenommen (Variante {}, Kandidat {}, Mindestradius {})",
					ref.bell().toShortString(), search.variant, search.nextCandidate, search.minRadius);
			}
		}
		if (search != null && (search.minRadius != minRadius || !needsNaturalHousePreparation(Blueprints.HOUSE, search.variant, hasLegacySource))) {
			dropHouseSiteSearch(level, ref);
			search = null;
		}
		return search;
	}

	/**
	 * Outcome of one bounded step. {@code waiting}: the retry time has not come, nothing was scanned.
	 * {@code site} null and not waiting: this page found nothing; {@code search.retryAt} says when the next page runs.
	 */
	record HouseSiteStep(HouseSiteSearch search, BlockPos site, int variant, Architect.Design design, int half, int headroom,
		boolean waiting, boolean pageComplete, int evaluated) { }

	/** One step of the persisted house site search; at most {@link #HOUSE_SITE_CANDIDATE_BUDGET} candidates are checked. */
	static HouseSiteStep stepHouseSiteSearch(Ctx c, VillageRef ref, VillageStyle style, String type, HouseSiteSearch ongoing,
			int newVariant, int minRadius) {
		return stepHouseSiteSearch(c, ref, style, type, ongoing, newVariant, minRadius,
			Math.min(88, Config.VILLAGE_RADIUS - 8), FORCE_HOUSE_STEP_SEARCH ? forcedHouseSearchBudget() : HOUSE_SITE_CANDIDATE_BUDGET);
	}

	/** {@code maxRadius} is a test seam (smaller fixture terrain); production always passes the village search radius. */
	static HouseSiteStep stepHouseSiteSearch(Ctx c, VillageRef ref, VillageStyle style, String type, HouseSiteSearch ongoing,
			int newVariant, int minRadius, int maxRadius, int budget) {
		ServerLevel level = c.level();
		HouseSiteSearch search = ongoing;
		if (search == null) {
			search = new HouseSiteSearch(newVariant, c.v().getRandom().nextLong(), minRadius, c.now());
			HOUSE_SITE_SEARCHES.put(houseSiteSearchKey(level, ref), search);
			LOGGER.info("Villagers: Repurposed: Bauplatzsuche fuer 'house' (Glocke {}) begonnen (Variante {}, Mindestradius {}): Zufalls- und Toleranzsuche ohne Treffer",
				ref.bell().toShortString(), search.variant, search.minRadius);
			persistHouseSiteSearch(ref, search);
		}
		search.lastUsedAt = c.now();
		if (c.now() < search.retryAt) {
			return new HouseSiteStep(search, null, search.variant, null, 0, 0, true, false, 0);
		}
		int searchMax = maxRadius;
		Architect.Design searchDesign = designForPlan(type, search.variant);
		int searchHalf = siteHalf(type, search.variant, searchDesign);
		int searchHeadroom = siteHeadroom(type, search.variant, searchDesign);
		int searchVariant = search.variant;
		if (templateEligible(searchDesign)) {
			searchHalf = Math.max(searchHalf, 4);
			searchHeadroom = Math.max(searchHeadroom, 10);
		}
		int oldMargin = Sites.margin;
		int evaluatedBefore = SITE_REJECT[0];
		Sites.margin = 2;
		Sites.SearchPage page;
		try {
			page = Sites.findHouseSitePage(level, ref.bell(), search.minRadius, searchMax,
				search.nextCandidate, budget, search.phaseSeed, search.visitedColumns,
				p -> siteCandidateAnchor(c, ref, type, searchVariant, searchDesign, style, p, ref.bell()),
				Sites.villageReferenceY(level, ref.bell()));
		} finally {
			Sites.margin = oldMargin;
		}
		search.nextCandidate = page.nextCandidate();
		if (page.site() == null) {
			if (page.complete()) {
				long seed = ((long) c.v().getRandom().nextInt(1 << 22) << 6) | Palette.profileCode(level, ref.bell());
				seed = Architect.withSizeTier(seed, nextSizeTier(ref, type));
				search.restart(newPlanVariant(type, seed), c.v().getRandom().nextLong(), c.now() + HOUSE_SITE_REPROBE_TICKS);
			} else {
				search.retryAt = c.now() + HOUSE_SITE_RETRY_TICKS;
			}
			persistHouseSiteSearch(ref, search);
			if (c.now() - HOUSE_SITE_DIAG_AT.getOrDefault(ref.bell().asLong(), Long.MIN_VALUE / 2) > 1200) {
				HOUSE_SITE_DIAG_AT.put(ref.bell().asLong(), c.now());
				LOGGER.info("Villagers: Repurposed: Bauplatzsuche fuer 'house' (Glocke {}) fortgesetzt (Variante {}, Kandidat {}/{}, besuchte Spalten {}, halbe Kante {}, Hoehe {}, Mindestradius {}), noch kein passender Standort (Bezugshoehe {}, Glocke y {})",
					ref.bell().toShortString(), searchVariant, page.nextCandidate(), Sites.systematicCandidateCount(search.minRadius, searchMax),
					search.visitedColumns.size(), searchHalf, searchHeadroom, search.minRadius,
					Sites.villageReferenceY(level, ref.bell()), ref.bell().getY());
			}
			return new HouseSiteStep(search, null, searchVariant, searchDesign, searchHalf, searchHeadroom, false, page.complete(), SITE_REJECT[0] - evaluatedBefore);
		}
		search.retryAt = c.now() + HOUSE_SITE_RETRY_TICKS;
		persistHouseSiteSearch(ref, search);
		LOGGER.info("Villagers: Repurposed: Bauplatzsuche fuer 'house' (Glocke {}) hat Standort {} gefunden (Variante {}, Kandidat {})",
			ref.bell().toShortString(), page.site().toShortString(), searchVariant, page.nextCandidate());
		return new HouseSiteStep(search, page.site(), searchVariant, searchDesign, searchHalf, searchHeadroom, false, false, SITE_REJECT[0] - evaluatedBefore);
	}

	private static final int CIVIC_SITE_CANDIDATE_BUDGET = 96;
	private static final long CIVIC_SITE_RETRY_TICKS = 20L;
	private static final long CIVIC_SITE_REPROBE_TICKS = 1200L;
	private static final java.util.Map<Long, CivicSiteSearch> CIVIC_SITE_SEARCHES = new java.util.HashMap<>();

	private static final class CivicSiteSearch {
		final String type;
		final int[] variants;
		final long phaseSeed;
		final java.util.List<java.util.Set<Long>> flatVisited = new java.util.ArrayList<>();
		final java.util.List<java.util.Set<Long>> tolerantVisited = new java.util.ArrayList<>();
		final java.util.List<java.util.Set<Long>> terracedVisited = new java.util.ArrayList<>();
		int variantIndex;
		int nextCandidate;
		boolean tolerant;
		/** Last-resort pass after every variant failed on flat and one-block-tolerant ground (implies tolerant). */
		boolean terraced;
		long retryAt;

		CivicSiteSearch(String type, int[] variants, long phaseSeed) {
			this.type = type;
			this.variants = variants;
			this.phaseSeed = phaseSeed;
			for (int ignored : variants) {
				flatVisited.add(new java.util.HashSet<>());
				tolerantVisited.add(new java.util.HashSet<>());
				terracedVisited.add(new java.util.HashSet<>());
			}
		}

		CivicSiteSearch(de.villagerplus.village.VillageData.Extra.CivicSiteSearch saved) {
			this(saved.type(), saved.variants().stream().mapToInt(Integer::intValue).toArray(), saved.phaseSeed());
			variantIndex = saved.variantIndex();
			nextCandidate = saved.nextCandidate();
			tolerant = saved.tolerant() || saved.terraced();
			terraced = saved.terraced();
			retryAt = saved.retryAt();
		}

		java.util.Set<Long> visitedColumns() {
			return terraced ? terracedVisited.get(variantIndex) : tolerant ? tolerantVisited.get(variantIndex) : flatVisited.get(variantIndex);
		}

		void deferCandidate(BlockPos plot) {
			nextCandidate = Math.max(0, nextCandidate - 1);
			visitedColumns().remove(BlockPos.asLong(plot.getX(), 0, plot.getZ()));
		}

		boolean advancePass(long now) {
			nextCandidate = 0;
			if (!terraced) {
				if (!tolerant) {
					tolerant = true;
					return false;
				}
				if (variantIndex + 1 < variants.length) {
					tolerant = false;
					variantIndex++;
					return false;
				}
				// Every variant failed on flat and one-block-tolerant ground: sweep them once more on terraced ground.
				terraced = true;
				variantIndex = 0;
				return false;
			}
			if (variantIndex + 1 < variants.length) {
				variantIndex++;
				return false;
			}
			terraced = false;
			tolerant = false;
			variantIndex = 0;
			flatVisited.forEach(java.util.Set::clear);
			tolerantVisited.forEach(java.util.Set::clear);
			terracedVisited.forEach(java.util.Set::clear);
			retryAt = now + CIVIC_SITE_REPROBE_TICKS;
			return true;
		}
	}

	private static CivicSiteSearch civicSiteSearch(Ctx c, VillageRef ref, String type) {
		long key = ref.bell().asLong();
		CivicSiteSearch search = CIVIC_SITE_SEARCHES.get(key);
		var persisted = ref.data().extra.civicSiteSearch.filter(saved -> saved.type().equals(type));
		if (search != null && search.type.equals(type) && persisted.isPresent()) return search;
		CIVIC_SITE_SEARCHES.remove(key);
		if (persisted.isPresent()) {
			search = new CivicSiteSearch(persisted.get());
			CIVIC_SITE_SEARCHES.put(key, search);
			return search;
		}
		int count = architect(type) ? 4 : 1;
		int[] variants = new int[count];
		for (int i = 0; i < count; i++) {
			if (architect(type)) {
				long seed = ((long) c.v().getRandom().nextInt(1 << 22) << 6) | Palette.profileCode(c.level(), ref.bell());
				seed = civicDesignSeed(type, firstPublicStorageMissing(c.level(), ref.data()), seed, nextSizeTier(ref, type));
				variants[i] = (int) (seed << 2);
			} else {
				variants[i] = 0;
			}
		}
		search = new CivicSiteSearch(type, variants, c.v().getRandom().nextLong());
		CIVIC_SITE_SEARCHES.put(key, search);
		persistCivicSiteSearch(ref, search);
		return search;
	}

	private static void persistCivicSiteSearch(VillageRef ref, CivicSiteSearch search) {
		List<Integer> variants = java.util.Arrays.stream(search.variants).boxed().toList();
		ref.data().extra.civicSiteSearch = Optional.of(new de.villagerplus.village.VillageData.Extra.CivicSiteSearch(
			search.type, variants, search.phaseSeed, search.variantIndex, search.nextCandidate, search.tolerant, search.retryAt, search.terraced));
		ref.dirty();
	}

	/** Closest distance to the current stand since the mason last made real progress. Not persisted. */
	private static final java.util.Map<Long, Double> STAND_BEST = new java.util.HashMap<>();
	/** First tick without a reachable stand; reset after recovery or server restart. */
	private static final java.util.Map<Long, Long> STAND_FAILURE_SINCE = new java.util.HashMap<>();
	/** Step 0 gives up well before the 20000-tick walk budget when the stand is not getting closer. */
	private static final long STAND_GIVE_UP = 1200L;
	/** Last position at which a worker was counted as having covered ground toward a plan's stand (by plan origin). */
	private static final java.util.Map<Long, net.minecraft.world.phys.Vec3> STAND_MOVE_ANCHOR = new java.util.HashMap<>();
	private static final java.util.Map<Long, Integer> STAND_BEST_NODES = new java.util.HashMap<>();

	/** True while the remaining route to the stand keeps getting shorter; unreachable stands never count as progress. */
	private static boolean routeProgress(Ctx c, BlockPos stand, long key) {
		var route = workPath(c, stand);
		int nodes = route == null || !route.canReach() ? Integer.MAX_VALUE : route.getNodeCount();
		Integer best = STAND_BEST_NODES.get(key);
		if (best == null || nodes < best - 1) {
			STAND_BEST_NODES.put(key, nodes);
			return true;
		}
		return false;
	}
	private record SavedBlock(BlockPos pos, BlockState state) { }

	/** Bell towers are not terrain: depth limits use the verified village street. */
	public static int villageGroundY(ServerLevel level, BlockPos bell) {
		BlockPos street = villageReturn(level, bell);
		return street == null ? bell.getY() : street.getY();
	}

	/** A street-level spot beside the bell, not the tower roof and not a cave under it. */
	public static BlockPos villageReturn(ServerLevel level, BlockPos bell) {
		BlockPos best = null;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos feet = standOnColumn(level, bell.getX() + dx, bell.getZ() + dz, bell.getY());
				if (feet == null || !safeWorkFloor(level, feet)) continue;
				// CTOV bells can sit several blocks above the road. Prefer an actual
				// path surface over the tower platform nearest the bell's height.
				boolean road = level.getBlockState(feet.below()).is(Blocks.DIRT_PATH);
				int verticalOffset = feet.getY() - bell.getY();
				if (road ? verticalOffset < -32 || verticalOffset > 12 : Math.abs(verticalOffset) > 12) continue;
				boolean bestRoad = best != null && level.getBlockState(best.below()).is(Blocks.DIRT_PATH);
				if (best == null || (road && !bestRoad)
					|| (road == bestRoad && feet.getY() < best.getY())) best = feet;
			}
		}
		return best;
	}

	static BlockPos civicSiteAnchor(ServerLevel level, VillageRef ref) {
		BlockPos street = villageReturn(level, ref.bell());
		return street == null ? ref.bell() : street;
	}

	private static Direction siteFacing(ServerLevel level, VillageRef ref, String type, BlockPos site) {
		BlockPos anchor = civicSite(type) ? civicSiteAnchor(level, ref) : ref.bell();
		return Sites.facingTowards(site, anchor);
	}

	static boolean shouldApproachVillageStreet(boolean mason, boolean hasPlan, BlockPos worker, BlockPos street) {
		return mason && !hasPlan && worker != null && street != null && worker.getY() > street.getY() + 2;
	}

	/** Remember a plot the builder could not reach so the next search chooses somewhere else. */
	static void rejectPlot(VillageRef ref, BlockPos plot) {
		if (ref == null || plot == null) return;
		java.util.Set<Long> plots = REJECTED_PLOTS.computeIfAbsent(ref.bell().asLong(), key -> new java.util.HashSet<>());
		if (plots.size() > 64) plots.clear();
		plots.add(BlockPos.asLong(plot.getX(), 0, plot.getZ()));
		// A rejected candidate can have been materialized just to test its footprint/stand.
		PALETTES.remove(plot.asLong());
	}

	static boolean rejectedPlot(VillageRef ref, BlockPos plot) {
		java.util.Set<Long> plots = ref == null ? null : REJECTED_PLOTS.get(ref.bell().asLong());
		return plots != null && plots.contains(BlockPos.asLong(plot.getX(), 0, plot.getZ()));
	}

	/** Why the latest workStand call found nothing. Diagnostic only. */
	private static final Map<java.util.UUID, String> STAND_REPORTS = new HashMap<>();

	public static String lastStandReport(Villager villager) {
		return villager == null ? "" : STAND_REPORTS.getOrDefault(villager.getUUID(), "");
	}

	private static void rememberStandReport(Villager villager, String report) {
		if (STAND_REPORTS.size() > 256) STAND_REPORTS.clear();
		STAND_REPORTS.put(villager.getUUID(), report);
	}

	static BlockPos workStand(Ctx c, BlockPos origin, Direction dir, List<Step> steps) {
		return workStand(c, origin, dir, steps, Integer.MIN_VALUE);
	}

	/** Search only stands that satisfy the village-ground depth limit, before clearing or path probing them. */
	static BlockPos workStand(Ctx c, BlockPos origin, Direction dir, List<Step> steps, int minimumStandY) {
		return workStand(c, origin, dir, steps, minimumStandY, -1);
	}

	/** Crew members use stable, separated slots so owner and helpers do not crowd the same work point. */
	private static BlockPos workStand(Ctx c, BlockPos origin, Direction dir, List<Step> steps,
			int minimumStandY, int crewSlot) {
		if (crewSlot >= 0) {
			CrewStandCache cached = cachedCrewStand(c, origin, crewSlot);
			if (cached != null) {
				if (cached.stand() == null) return null;
				rememberStandReport(c.v(), "worker=" + c.v().blockPosition().toShortString()
					+ " crew slot=" + crewSlot + " cached=" + cached.stand().toShortString());
				return cached.stand();
			}
		}
		int minX = origin.getX(), maxX = minX, minZ = origin.getZ(), maxZ = minZ, ext = 2;
		for (Step step : steps) {
			BlockPos p = step.pos();
			minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
			ext = Math.max(ext, (p.getX() - origin.getX()) * dir.getStepX() + (p.getZ() - origin.getZ()) * dir.getStepZ());
		}
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(origin.relative(dir, ext + 3));
		int midX = minX + (maxX - minX) / 2, midZ = minZ + (maxZ - minZ) / 2;
		for (int x : new int[] {midX, minX, maxX}) {
			candidates.add(new BlockPos(x, origin.getY(), minZ - 3));
			candidates.add(new BlockPos(x, origin.getY(), maxZ + 3));
		}
		for (int z : new int[] {midZ, minZ, maxZ}) {
			candidates.add(new BlockPos(minX - 3, origin.getY(), z));
			candidates.add(new BlockPos(maxX + 3, origin.getY(), z));
		}
		for (int gap = 4; gap <= 8; gap++) {
			candidates.add(new BlockPos(midX, origin.getY(), minZ - gap));
			candidates.add(new BlockPos(midX, origin.getY(), maxZ + gap));
			candidates.add(new BlockPos(minX - gap, origin.getY(), midZ));
			candidates.add(new BlockPos(maxX + gap, origin.getY(), midZ));
		}
		// Corners stay inside the eight-block ring. A wider ring would walk off the wet-perimeter fixture.
		for (int gap : new int[] {4, 6, 8}) {
			candidates.add(new BlockPos(minX - gap, origin.getY(), minZ - gap));
			candidates.add(new BlockPos(maxX + gap, origin.getY(), minZ - gap));
			candidates.add(new BlockPos(minX - gap, origin.getY(), maxZ + gap));
			candidates.add(new BlockPos(maxX + gap, origin.getY(), maxZ + gap));
		}
		List<BlockPos> floors = new ArrayList<>();
		BlockPos frontFeet = null;
		boolean frontColumn = true;
		boolean missingStandChunk = false;
		for (BlockPos column : candidates) {
			if (!c.level().hasChunkAt(column)) {
				frontColumn = false;
				missingStandChunk = true;
				continue;
			}
			BlockPos feet = standOnColumn(c.level(), column.getX(), column.getZ(), origin.getY());
			if (frontColumn) {
				frontFeet = feet;
				frontColumn = false;
			}
			if (feet == null) continue;
			if (feet.getX() >= minX && feet.getX() <= maxX && feet.getZ() >= minZ && feet.getZ() <= maxZ) continue;
			floors.add(feet);
		}
		if (floors.isEmpty()) {
			rememberStandReport(c.v(), "worker=" + c.v().blockPosition().toShortString() + " floors=0"
				+ (missingStandChunk ? " corridor=unloaded/deferred" : ""));
			return null;
		}
		// Drop pit columns only. A ring median (or the lowest height cluster) treats nearby roof
		// samples as the reference and throws away the real street beside them.
		int streetLevel = origin.getY();
		if (!floors.isEmpty()) {
			java.util.Map<Integer, Integer> heightVotes = new java.util.HashMap<>();
			for (BlockPos feet : floors) heightVotes.merge(feet.getY(), 1, Integer::sum);
			streetLevel = heightVotes.entrySet().stream()
				.max(Comparator.<java.util.Map.Entry<Integer, Integer>>comparingInt(java.util.Map.Entry::getValue)
					.thenComparingInt(e -> -Math.abs(e.getKey() - origin.getY())))
				.map(java.util.Map.Entry::getKey).orElse(origin.getY());
		}
		final int pitReference = streetLevel;
		floors.removeIf(feet -> pitReference - feet.getY() > 4 || feet.getY() < minimumStandY);
		boolean corridorLoaded = workCorridorLoaded(c.level(), c.v().blockPosition(), origin);
		if (crewSlot >= 0) {
			List<BlockPos> crewStands = new ArrayList<>();
			if (frontFeet != null && floors.contains(frontFeet)) crewStands.add(frontFeet);
			for (BlockPos feet : floors) {
				if (crewStands.contains(feet)) continue;
				boolean separated = crewStands.stream().allMatch(other -> {
					int dx = feet.getX() - other.getX(), dz = feet.getZ() - other.getZ();
					return dx * dx + dz * dz >= 4;
				});
				if (separated) crewStands.add(feet);
			}
			int probes = 0;
			int start = crewStands.isEmpty() ? 0 : Math.min(crewSlot, crewStands.size() - 1);
			for (int offset = 0; offset < crewStands.size(); offset++) {
				int candidateSlot = (start + offset) % crewStands.size();
				BlockPos candidate = crewStands.get(candidateSlot);
				if (crewSlotReservedByOther(c, origin, crewSlot, candidateSlot, candidate)) continue;
				if (!crewStandFree(c, origin, crewSlot, candidateSlot, candidate)) continue;
				if (probes >= MAX_CREW_STAND_FALLBACK_PROBES) break;
				probes++;
				if (!acceptStand(c, candidate, corridorLoaded)) continue;
				rememberCrewStand(c, origin, crewSlot, candidateSlot, candidate, 0L);
				rememberStandReport(c.v(), "worker=" + c.v().blockPosition().toShortString()
					+ " crew slot=" + crewSlot + " stand=" + candidate.toShortString()
					+ (candidateSlot == crewSlot ? "" : " fallback=" + candidateSlot));
				return candidate;
			}
			long retryDelay = probes == 0 ? CREW_STAND_RETRY_TICKS : CREW_STAND_PATH_RETRY_TICKS;
			rememberCrewStand(c, origin, crewSlot, -1, null, c.now() + retryDelay);
			rememberStandReport(c.v(), "worker=" + c.v().blockPosition().toShortString()
				+ " crew slot=" + crewSlot + " no free reachable stand after " + probes + " probes");
			return null;
		}
		if (frontFeet != null && floors.contains(frontFeet) && acceptStand(c, frontFeet, corridorLoaded)) return frontFeet;
		floors.remove(frontFeet);
		floors.sort(Comparator.comparingDouble(feet -> c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet))));
		StringBuilder report = new StringBuilder();
		report.append("worker=").append(c.v().blockPosition().toShortString()).append(" floors=").append(floors.size())
			.append(" corridor=").append(corridorLoaded ? "loaded" : "unloaded/deferred");
		int tried = 0;
		for (BlockPos feet : floors) {
			if (acceptStand(c, feet, corridorLoaded)) {
				rememberStandReport(c.v(), report.toString());
				return feet;
			}
			if (tried++ < 3) report.append(" miss=").append(feet.toShortString());
		}
		rememberStandReport(c.v(), report.toString());
		return null;
	}

	/** Reuses a crew stand until it becomes blocked or the worker stops approaching it. */
	private static CrewStandCache cachedCrewStand(Ctx c, BlockPos origin, int slot) {
		Map<java.util.UUID, CrewStandCache> stands = BUILD_CREW_STANDS.get(c.level());
		if (stands == null) return null;
		CrewStandCache cached = stands.get(c.v().getUUID());
		if (cached == null) return null;
		if (cached.projectOrigin() != origin.asLong() || cached.slot() != slot) {
			stands.remove(c.v().getUUID());
			return null;
		}
		if (cached.stand() == null) {
			if (c.now() < cached.retryAt()) return cached;
			stands.remove(c.v().getUUID());
			return null;
		}
		if (!crewStandFree(c, origin, slot, cached.candidateSlot(), cached.stand())) {
			stands.remove(c.v().getUUID());
			return null;
		}
		double distance = c.v().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(cached.stand()));
		if (distance <= 6.0) {
			CrewStandCache refreshed = new CrewStandCache(cached.projectOrigin(), cached.slot(), cached.candidateSlot(), cached.stand(),
				distance, c.now(), 0L);
			stands.put(c.v().getUUID(), refreshed);
			return refreshed;
		}
		if (distance < cached.bestDistance() - 1.5) {
			CrewStandCache refreshed = new CrewStandCache(cached.projectOrigin(), cached.slot(), cached.candidateSlot(), cached.stand(),
				distance, c.now(), 0L);
			stands.put(c.v().getUUID(), refreshed);
			return refreshed;
		}
		if (c.now() - cached.lastProgressAt() > CREW_STAND_STALL_TICKS) {
			stands.remove(c.v().getUUID());
			return null;
		}
		return cached;
	}

	private static void rememberCrewStand(Ctx c, BlockPos origin, int slot, int candidateSlot, BlockPos stand, long retryAt) {
		double distance = stand == null ? Double.MAX_VALUE
			: c.v().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand));
		BUILD_CREW_STANDS.computeIfAbsent(c.level(), ignored -> new HashMap<>()).put(c.v().getUUID(),
			new CrewStandCache(origin.asLong(), slot, candidateSlot, stand == null ? null : stand.immutable(), distance, c.now(), retryAt));
	}

	/** A crew member must not claim terrain occupied or already claimed by another slot this tick. */
	private static boolean crewStandFree(Ctx c, BlockPos origin, int slot, int candidateSlot, BlockPos feet) {
		if (!standable(c.level(), feet)) return false;
		AABB occupiedArea = new AABB(feet).inflate(0.35, 0.05, 0.35);
		if (!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, occupiedArea,
			entity -> entity.isAlive() && entity != c.v()).isEmpty()) return false;
		return !crewSlotReservedByOther(c, origin, slot, candidateSlot, feet);
	}

	private static boolean crewSlotReservedByOther(Ctx c, BlockPos origin, int ownSlot, int candidateSlot, BlockPos feet) {
		if (c.village() == null) return false;
		Map<Long, BuildCrewSnapshot> byVillage = BUILD_CREW_SNAPSHOTS.get(c.level());
		BuildCrewSnapshot snapshot = byVillage == null ? null : byVillage.get(c.village().bell().asLong());
		if (snapshot == null || snapshot.gameTime() != c.now()) return false;
		Map<java.util.UUID, CrewStandCache> stands = BUILD_CREW_STANDS.get(c.level());
		for (Map.Entry<java.util.UUID, BuildCrewAssignment> entry : snapshot.assignments().entrySet()) {
			if (entry.getKey().equals(c.v().getUUID())) continue;
			BuildCrewAssignment assignment = entry.getValue();
			if (!assignment.origin().equals(origin)) continue;
			if (assignment.standSlot() == candidateSlot && candidateSlot != ownSlot) return true;
			CrewStandCache stand = stands == null ? null : stands.get(entry.getKey());
			if (stand != null && stand.projectOrigin() == origin.asLong() && stand.stand() != null
				&& stand.stand().equals(feet)) return true;
		}
		return false;
	}

	/** Keep a cleared stand when the mason can use it; put leaves and the one-block lip back when the path fails. */
	private static boolean acceptStand(Ctx c, BlockPos feet, boolean corridorLoaded) {
		double distance = c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet));
		if (distance > 36.0 && !corridorLoaded) return false;
		List<SavedBlock> cleared = exposeStand(c.level(), feet);
		if (distance <= 36.0) return true;
		var path = workPath(c, feet);
		if (path != null && path.canReach()) return true;
		restoreStand(c.level(), cleared);
		return false;
	}

	/** Ground the worker can stand on: the heightmap, a one-block step, or the street under a roof. */
	static BlockPos standOnColumn(ServerLevel level, int x, int z, int preferY) {
		if (!level.hasChunkAt(new BlockPos(x, preferY, z))) return null;
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos surface = new BlockPos(x, top, z);
		BlockPos chosen = null;
		for (int dy : new int[] {0, -1, 1}) {
			BlockPos feet = surface.offset(0, dy, 0);
			if (standable(level, feet)) {
				chosen = feet;
				break;
			}
		}
		if (chosen == null) return null;
		if (chosen.getY() > preferY + 2) {
			BlockPos lower = shelteredFloor(level, chosen, preferY);
			if (lower != null) return lower;
		}
		return chosen;
	}

	/** Check without loading whether the corridor used by the navigation probe is already available. */
	private static boolean workCorridorLoaded(ServerLevel level, BlockPos from, BlockPos to) {
		int minX = (Math.min(from.getX(), to.getX()) >> 4) - 1;
		int maxX = (Math.max(from.getX(), to.getX()) >> 4) + 1;
		int minZ = (Math.min(from.getZ(), to.getZ()) >> 4) - 1;
		int maxZ = (Math.max(from.getZ(), to.getZ()) >> 4) + 1;
		if ((maxX - minX + 1L) * (maxZ - minZ + 1L) > 80) return false;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!level.hasChunk(x, z)) return false;
			}
		}
		return true;
	}

	/** A 2-block gap under a roof, so the stand is the street rather than the roof ridge. */
	private static BlockPos shelteredFloor(ServerLevel level, BlockPos roofFeet, int preferY) {
		int solids = 0;
		for (int y = roofFeet.getY() - 1; y >= Math.max(preferY - 2, roofFeet.getY() - 8); y--) {
			BlockPos feet = new BlockPos(roofFeet.getX(), y, roofFeet.getZ());
			if (standable(level, feet)) return feet;
			if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && !isSoft(level.getBlockState(feet)) && ++solids > 2) return null;
		}
		return null;
	}

	/** Leaves and replaceable plants, plus a single dirt lip a mason would shovel aside. Fluids stay. */
	private static boolean standable(ServerLevel level, BlockPos feet) {
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below()) || !level.hasChunkAt(feet.above())) return false;
		BlockPos below = feet.below();
		BlockState ground = level.getBlockState(below);
		if ((!ground.isFaceSturdy(level, below, Direction.UP) && !ground.is(Blocks.DIRT_PATH))
			|| !ground.getFluidState().isEmpty()) return false;
		boolean digFeet = needsDig(level, feet);
		boolean digHead = needsDig(level, feet.above());
		if (digFeet && digHead) return false;
		if (!digFeet && !opens(level, feet)) return false;
		if (!digHead && !opens(level, feet.above())) return false;
		if (digFeet && !opens(level, feet.above())) return false;
		if (digHead && !opens(level, feet)) return false;
		return true;
	}

	private static boolean opens(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.getFluidState().isEmpty()) return false;
		if (state.isAir() || isSoft(state)) return true;
		return state.getCollisionShape(level, pos).isEmpty();
	}

	private static boolean needsDig(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return isDiggableStep(state) && !state.getCollisionShape(level, pos).isEmpty()
			&& opens(level, pos.above());
	}

	private static boolean isSoft(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty()) return false;
		return state.is(BlockTags.LEAVES) || state.canBeReplaced();
	}

	private static boolean isDiggableStep(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL);
	}

	/** Clear foliage and at most one natural lip so the path test sees the floor the mason will actually use. */
	private static List<SavedBlock> exposeStand(ServerLevel level, BlockPos feet) {
		List<SavedBlock> saved = new ArrayList<>();
		for (BlockPos pos : List.of(feet, feet.above(), feet.above(2))) {
			BlockState state = level.getBlockState(pos);
			if (!isSoft(state)) continue;
			saved.add(new SavedBlock(pos, state));
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
		BlockPos head = feet.above();
		if (needsDig(level, head) && !needsDig(level, feet)) {
			saved.add(new SavedBlock(head, level.getBlockState(head)));
			level.setBlock(head, Blocks.AIR.defaultBlockState(), 3);
		} else if (needsDig(level, feet) && !needsDig(level, head)) {
			saved.add(new SavedBlock(feet, level.getBlockState(feet)));
			level.setBlock(feet, Blocks.AIR.defaultBlockState(), 3);
		}
		return saved;
	}

	private static void restoreStand(ServerLevel level, List<SavedBlock> saved) {
		for (int i = saved.size() - 1; i >= 0; i--) {
			SavedBlock block = saved.get(i);
			level.setBlock(block.pos(), block.state(), 3);
		}
	}

	/** Next node on a verified route, about eight blocks ahead, so vanilla navigation can recompute it. */
	public static BlockPos routeWaypoint(Ctx c, BlockPos goal) {
		Map<java.util.UUID, RouteWaypointCache> byWorker = ROUTE_WAYPOINTS.computeIfAbsent(c.level(), ignored -> new HashMap<>());
		RouteWaypointCache cached = byWorker.get(c.v().getUUID());
		if (cached != null && cached.goal().equals(goal) && c.now() < cached.retryAt()
			&& c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(cached.waypoint())) > 4.0) {
			return cached.waypoint();
		}
		var route = workPath(c, goal);
		if (route == null || !route.canReach() || route.getNodeCount() == 0) {
			byWorker.put(c.v().getUUID(), new RouteWaypointCache(goal.immutable(), goal.immutable(), c.now() + ROUTE_WAYPOINT_CACHE_TICKS));
			return goal;
		}
		BlockPos waypoint = null;
		for (int i = 0; i < route.getNodeCount(); i++) {
			BlockPos node = route.getNodePos(i);
			if (c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(node)) > 100.0) break;
			waypoint = node;
		}
		BlockPos result = waypoint != null ? waypoint.immutable() : goal.immutable();
		byWorker.put(c.v().getUUID(), new RouteWaypointCache(goal.immutable(), result, c.now() + ROUTE_WAYPOINT_CACHE_TICKS));
		return result;
	}

	/** Independent bounded search, leaving navigation range and node-budget settings untouched. */
	public static net.minecraft.world.level.pathfinder.Path workPath(Ctx c, BlockPos feet) {
		double distance = c.v().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet));
		if (distance > 192.0) return null;
		BlockPos from = c.v().blockPosition();
		int horizontal = Math.abs(from.getX() - feet.getX()) + Math.abs(from.getZ() - feet.getZ());
		// Straight-line distance understates CTOV streets; budget the walked perimeter, not the chord.
		int length = (int) Math.min(512, Math.max(96, horizontal + Math.max(48, (int) Math.ceil(distance))));
		var region = new net.minecraft.world.level.PathNavigationRegion(c.level(),
			from.offset(-length - 8, -length - 8, -length - 8), from.offset(length + 8, length + 8, length + 8));
		var finder = new net.minecraft.world.level.pathfinder.PathFinder(c.v().getNavigation().getNodeEvaluator(), length * 32);
		// A solid goal (chest, log, workstation) can never be stood in, so a path "to" it never counts as reached.
		// Aim at the open cells next to it instead; vanilla navigation does the same through its reach distance.
		java.util.Set<BlockPos> goals = new java.util.LinkedHashSet<>();
		if (c.level().hasChunkAt(feet) && !c.level().getBlockState(feet).getCollisionShape(c.level(), feet).isEmpty()) {
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos next = feet.relative(side);
				if (!c.level().hasChunkAt(next)) continue;
				boolean open = c.level().getBlockState(next).getCollisionShape(c.level(), next).isEmpty()
					&& c.level().getBlockState(next.above()).getCollisionShape(c.level(), next.above()).isEmpty();
				if (open) goals.add(next.immutable());
			}
		}
		if (goals.isEmpty()) goals.add(feet);
		return finder.findPath(region, c.v(), goals, length, 0, 1.0F);
	}

	static boolean safeWorkFloor(ServerLevel level, BlockPos feet) {
		BlockPos below = feet.below(), head = feet.above();
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(below) || !level.hasChunkAt(head)) return false;
		BlockState ground = level.getBlockState(below), body = level.getBlockState(feet), upper = level.getBlockState(head);
		return (ground.isFaceSturdy(level, below, Direction.UP) || ground.is(Blocks.DIRT_PATH)) && ground.getFluidState().isEmpty()
			&& body.getFluidState().isEmpty() && upper.getFluidState().isEmpty()
			&& body.getCollisionShape(level, feet).isEmpty() && upper.getCollisionShape(level, head).isEmpty();
	}

	static void addWanted(VillageData d, Step s, Container box) {
		Item requested = s.item();
		if (requested == null && s.tag() != null) {
			for (net.minecraft.core.Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(s.tag())) {
				requested = holder.value();
				break;
			}
		}
		if (requested != null && d.wanted.size() < 256) {
			final Item wantedItem = requested;
			// Gesamtbedarf = Bestand + 16 (die Wunschliste enthaelt immer die Zielmenge in der Kiste).
			String entry = BuiltInRegistries.ITEM.getKey(wantedItem) + "#" + (JobUtil.count(box, st -> st.is(wantedItem)) + 16);
			if (d.wanted.stream().noneMatch(w -> w.startsWith(BuiltInRegistries.ITEM.getKey(wantedItem) + "#"))) {
				d.wanted.add(entry);
			}
		}
	}

	private static String describeStep(ServerLevel level, Step s, int index, int total) {
		BlockState state = level.getBlockState(s.pos());
		String need = s.item() != null ? BuiltInRegistries.ITEM.getKey(s.item()).toString()
			: s.tag() != null ? "Tag " + s.tag().location() : s.linkedTo() != null ? "verbundener Block" : "kostenlos";
		return "Nächster Block " + (index + 1) + "/" + total + " bei " + s.pos().toShortString()
			+ ": benötigt " + need + ", aktuell " + BuiltInRegistries.BLOCK.getKey(state.getBlock());
	}

	/** Kurzdiagnose des nächsten konkreten Blockschritts, zur Anzeige auf der Dorftafel. */
	public static String planStepDiagnostic(VillageData data) {
		return data.planOrigin.map(p -> PLAN_DIAGNOSTICS.get(p.asLong())).orElse(null);
	}

	/** Befindet sich ein Lebewesen in diesem Block? (dann wird dort nicht gebaut) */
	private static boolean occupied(ServerLevel level, BlockPos p) {
		return !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(p).inflate(0.1), e -> true).isEmpty();
	}

	/** Steht der Dorfbewohner innerhalb der Bloecke, die noch gesetzt werden? */
	private static boolean insideFootprint(Ctx c, List<Step> steps) {
		BlockPos here = c.v().blockPosition();
		for (Step s : steps) {
			if (s.pos().equals(here) || s.pos().equals(here.above())) {
				return true;
			}
		}
		return false;
	}

	/** Attempts the connection before any house completion side effects. */
	static boolean connectCompletedHouse(ServerLevel level, VillageRef ref, BlockPos origin, BlockPos entrance, long now) {
		if (Paths.lay(level, entrance, ref.bell(), ref)) return true;
		ref.data().planProgress = now;
		PLAN_DIAGNOSTICS.put(origin.asLong(), "Haus fertig; Weg zur Dorfstrasse blockiert");
		ref.dirty();
		return false;
	}

	private static void finish(Ctx c, VillageRef ref, BlockPos origin) {
		VillageData d = ref.data();
		if (d.planType.equals(Blueprints.HOUSE) && !REPAIRS.contains(origin.asLong())) {
			List<Step> houseSteps = STEPS.get(origin.asLong());
			BlockPos entrance = Paths.entrance(houseSteps, origin, Direction.from2DDataValue(d.planDir));
			if (!connectCompletedHouse(c.level(), ref, origin, entrance, c.now())) {
				c.st().buildCheckAt = c.now() + 200;
				c.setActivity("wartet auf einen sicheren Weg zur Dorfstrasse");
				return;
			}
		}
		LOGGER.info("Villagers: Repurposed: Bauplan {} fertiggestellt bei {} (Tick {}, Schritt {}/{})",
			d.planType, origin.toShortString(), c.now(), d.planStep, STEPS.getOrDefault(origin.asLong(), List.of()).size());
		ConstructionSite construction = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.CONSTRUCTION, null);
		if (construction != null && construction.origin().equals(origin)) {
			ConstructionSite remaining = ConstructionSite.cleanup(c.level(), construction, Lager.combined(c.level(), ref), ref);
			if (remaining.temporary().isEmpty()) ref.bellEntity().removeAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
			else ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, remaining);
		}
		PREP_RECORD.remove(origin.asLong());
		STAND_BEST.remove(origin.asLong());
		PLAN_DIAGNOSTICS.remove(origin.asLong());
		if (REPAIRS.remove(origin.asLong())) {
			JobUtil.tellNearbyPlayers(c.level(), c.v(), 40, Component.literal("Ausgebessert: " + label(d.planType) + "."));
			PALETTES.remove(origin.asLong());
			FOOT.remove(origin.asLong());
			STEPS.remove(origin.asLong());
			d.wanted.clear();
			d.clearPlan();
			d.nextBuild = c.now() + 1200;
			ref.dirty();
			c.mem().jobsDone++;
			return;
		}
		if (architect(d.planType)) {
			List<Built> list = new ArrayList<>(ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.BUILDINGS, List.of()));
			Palette used = PALETTES.get(origin.asLong());
			if (used == null) {
				used = Palette.detect(c.level(), ref.bell(), c.v(), d.planVariant >>> 2, origin);
			}
			used = paletteForPlan(c.level(), origin, used, biomeAt(c.level(), origin), d.planVariant);
			list.add(new Built(d.planType, origin, d.planDir, d.planVariant, used.ids()));
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.BUILDINGS, list);
		}
		de.villagerplus.village.Journal.add(c.level(),ref,"Bauen",c.mem().name,label(d.planType)+" fertiggestellt",0);
		switch (d.planType) {
			case Blueprints.HOUSE -> {
				d.houses++;
				d.popAtLastHouse = villagers(c.level(),ref).size();
				Paths.garden(c.level(), origin, Direction.from2DDataValue(d.planDir), ref);
			}
			case Blueprints.FARM -> d.farms++;
			case Blueprints.PEN -> d.extra.pen = Optional.of(origin);
			case Blueprints.SCHOOL -> {
				d.extra.school = Optional.of(origin);
				List<Step> built = STEPS.get(origin.asLong());
				if (built != null && !built.isEmpty()) {
					VillageStyle st = STYLES.get(origin.asLong());
					Furnisher.furnishSchool(c, ref, Furnisher.boxOf(built), st == null ? null : st.stairs());
				}
			}
			case Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.KITCHEN, Blueprints.STATION -> {
				de.villagerplus.village.Specials.set(ref, d.planType, origin);
				List<Step> built = STEPS.get(origin.asLong());
				if (built != null && !built.isEmpty()) {
					Furnisher.furnishSpecial(c.level(), Furnisher.boxOf(built), d.planType);
				}
			}
			case Blueprints.STORAGE -> {
				if (d.extra.lager.isEmpty()) {
					d.extra.lager = Optional.of(origin);
				} else {
					d.extra.lagerMore.add(origin);
				}
				// Innen mit Doppelkisten einrichten und alle Kisten des Gebaeudes als Lagerkisten merken.
				List<Step> built = STEPS.get(origin.asLong());
				if (built != null && !built.isEmpty()) {
					net.minecraft.world.level.levelgen.structure.BoundingBox bb = Furnisher.boxOf(built);
					// The compact first shed already bought and placed its chest as a saved plan step.
					// Do not grant it an unpriced second stock of containers at completion.
					if (!compactStorage(designForPlan(Blueprints.STORAGE, d.planVariant))) {
						Furnisher.placeDoubleChests(c.level(), bb, 8);
					}
					for (BlockPos chest : Furnisher.collectChests(c.level(), bb)) {
						if (!d.extra.lagerChests.contains(chest)) {
							d.extra.lagerChests.add(chest);
						}
					}
				}
			}
			case Blueprints.DECOR -> d.extra.decor++;
			case Blueprints.TOWNHALL -> d.townHallBuilt = true;
			default -> d.wellBuilt = true;
		}
		JobUtil.tellNearbyPlayers(c.level(), c.v(), 40, Component.literal("Fertig: " + label(d.planType) + " – das Dorf wächst!"));
		// Completed buildings persist their palette in the Built record; the transient lookup cache is no longer needed.
		PALETTES.remove(origin.asLong());
		STYLES.remove(origin.asLong());
		STEPS.remove(origin.asLong());
		FOOT.remove(origin.asLong());
		Lager.invalidate();
		if (d.planType.equals(Blueprints.FARM) || d.planType.equals(Blueprints.PEN)) {
			d.extra.nextFarm = nextFarmerBuildAt(d.planType, d.farms, d.extra.pen.isEmpty(), c.now());
			if (Blueprints.FARM.equals(d.planType) && d.farms == 1 && d.extra.pen.isEmpty()) {
				c.st().buildCheckAt = c.now();
			}
		} else {
			d.nextBuild = c.now() + Config.BUILD_COOLDOWN;
		}
		d.wanted.clear();
		d.clearPlan();
		ref.dirty();
		c.mem().jobsDone++;
	}

	// --- Bezahlen -------------------------------------------------------------------------------

	/** Nimmt die Kosten aus der Kiste und gibt den zu setzenden Block zurueck (null = nicht bezahlbar). */
	private record Payment(Item item, int count, Block block) { }

	private static void refund(ServerLevel level, Ctx c, Container box, ItemStack stack) {
		JobUtil.depositTo(box, stack);
		if (!stack.isEmpty()) JobUtil.give(level, c.v(), stack);
	}

	/** Select a complete payment without mutating storage; consumption happens only when the block can be placed. */
	private static Payment paymentFor(Step s, Container box) {
		if (s.item() != null && JobUtil.count(box, st -> st.is(s.item())) >= s.cost()) {
			return new Payment(s.item(), s.cost(), Block.byItem(s.item()));
		}
		if (s.tag() != null) {
			for (int i = 0; i < box.getContainerSize(); i++) {
				ItemStack stack = box.getItem(i);
				if (!stack.isEmpty() && stack.is(s.tag())) {
					Item item = stack.getItem();
					if (JobUtil.count(box, st -> st.is(item)) >= s.cost()) {
						return new Payment(item, s.cost(), Block.byItem(item));
					}
				}
			}
		}
		return null;
	}

	private record Key(Item item, TagKey<Item> tag) { }

	/** Fehlendes Material: Gegenstand (oder Tag), Menge und Anzeigetext. */
	private record Gap(Item item, TagKey<Item> tag, int amount, int total, Component text) { }

	/** Welche Materialien fehlen in der Kiste fuer den kompletten Bau? (groesste Luecken zuerst) */
	private static List<Gap> missing(ServerLevel level, List<Step> steps, int fromStep, Container box,
		Map<Item, Integer> supportNeed) {
		Map<Key, Integer> need = new HashMap<>();
		for (int i = Math.max(0, fromStep); i < steps.size(); i++) {
			Step s = steps.get(i);
			if (i == fromStep && s.item() != null && level.getBlockState(s.pos()).is(Block.byItem(s.item()))) continue;
			if (!s.free() && !s.optional() && s.linkedTo() == null && s.cost() > 0) {
				need.merge(new Key(s.item(), s.tag()), s.cost(), Integer::sum);
			}
		}
		for (Map.Entry<Item, Integer> support : supportNeed.entrySet()) {
			need.merge(new Key(support.getKey(), null), support.getValue(), Integer::sum);
		}
		List<Gap> gaps = new ArrayList<>();
		for (Map.Entry<Key, Integer> e : need.entrySet()) {
			Key k = e.getKey();
			int have = JobUtil.count(box, st -> (k.item() != null && st.is(k.item())) || (k.tag() != null && st.is(k.tag())));
			if (have < e.getValue()) {
				int amount = e.getValue() - have;
				Component name = k.item() != null ? new ItemStack(k.item()).getHoverName()
					: Component.literal(k.tag().equals(ItemTags.BEDS) ? "Bett" : "Holztür");
				// Bett/Tuer mit Tag sind nur dann "Wunsch", wenn es keinen exakten Gegenstand gibt - Betten baut der Schaefer.
				Item request=k.tag()!=null && k.tag().equals(ItemTags.BEDS)?Items.BED.white() : k.item()!=null?k.item():Items.OAK_DOOR;
				gaps.add(new Gap(request, k.tag(), amount, e.getValue(), Component.literal(amount + "x ").append(name)));
			}
		}
		gaps.sort(Comparator.comparingInt(Gap::amount).reversed());
		return gaps;
	}

	// --- Instandhaltung -------------------------------------------------------------------------

	private static final java.util.Set<Long> REPAIRS = new java.util.HashSet<>();
	private static final Map<Long, Long> REPAIR_AT = new HashMap<>();
	private static final Map<Long, Integer> REPAIR_IDX = new HashMap<>();
	private static final long WALL_KEY = 0x57414C4CL;

	/** Maurer pruefen reihum ein eigenes Gebaeude bzw. die Stadtmauer und bessern fehlende Bloecke aus. true = Ausbesserung gestartet. */
	private static boolean startRepair(Ctx c, VillageRef ref) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		long key = ref.bell().asLong();
		int n = REPAIR_IDX.merge(key, 1, Integer::sum);
		List<Built> list = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.BUILDINGS, List.of());
		if (d.wallBuilt && (list.isEmpty() || n % 4 == 0)) {
			return startWallRepair(c, ref);
		}
		if (list.isEmpty()) {
			return false;
		}
		Built b = list.get(n % list.size());
		if (d.buildProject(b.origin()).isPresent()) return false;
		if (!level.hasChunkAt(b.origin())) {
			return false;
		}
		// Die damals benutzte Palette (gespeichert); nur ohne Eintrag wird neu gelesen (neuere Bauten wuerden sie verfaelschen).
		Palette palette = Palette.ofIds(b.palette());
		if (palette == null) {
			palette = PALETTES.computeIfAbsent(b.origin().asLong(), k -> Palette.detect(level, ref.bell(), c.v()));
		}
		palette = paletteForPlan(level, b.origin(), palette, biomeAt(level, b.origin()), b.variant());
		Direction dir = Direction.from2DDataValue(b.dir());
		List<Step> all = Architect.build(designForPlan(b.type(), b.variant()), palette, b.origin(), dir);
		List<Step> damaged = new ArrayList<>();
		int paid = 0;
		for (Step s : all) {
			BlockState cur = level.getBlockState(s.pos());
			if (s.free()) {
				continue;
			}
			paid++;
			boolean done = s.linkedTo() != null ? !cur.isAir() : cur.is(Block.byItem(s.item()));
			if (!done && (cur.isAir() || cur.canBeReplaced())) {
				damaged.add(s);
			}
		}
		if (damaged.isEmpty()) {
			return false;
		}
		if (damaged.size() * 2 > paid) {
			// Mehr als die Haelfte fehlt: abgerissen oder zerstoert - nicht von selbst neu bauen, Eintrag vergessen.
			List<Built> rest = new ArrayList<>(list);
			rest.remove(b);
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.BUILDINGS, rest);
			return false;
		}
		d.planType = b.type();
		d.planOrigin = Optional.of(b.origin());
		d.planDir = b.dir();
		d.planStep = 0;
		d.planVariant = b.variant();
		d.planSource = Optional.empty();
		d.planProgress = c.now();
		d.claimPlanBuilder(c.v().getUUID());
		STEPS.put(b.origin().asLong(), damaged);
		REPAIRS.add(b.origin().asLong());
		ref.dirty();
		LOGGER.info("Villagers: Repurposed: Ausbesserung von '{}' bei {}: {} Bloecke fehlen", b.type(), b.origin().toShortString(), damaged.size());
		return true;
	}

	/** Radius der äußersten vollständig gespeicherten Mauer; alte und neue Ringe bleiben beide bestehen. */
	private static int detectWallRadius(ServerLevel level, BlockPos bell, VillageStyle style) {
		int profiled = profiledWallRadius(level, bell);
		if (profiled > 0) return profiled;
		for (int r = Development.MAX_WALL_RADIUS; r >= Config.WALL_MIN_VILLAGE_RADIUS; r--) {
			int x = bell.getX();
			int z = bell.getZ() - r;
			if (!level.hasChunkAt(new BlockPos(x, bell.getY(), z))) {
				continue;
			}
			int h = Wall.groundHeight(level, x, z + 1, style.wall());
			if (level.getBlockState(new BlockPos(x, h + 4, z + 1)).is(style.wall()) && level.getBlockState(new BlockPos(x, h + 5, z + 1)).is(style.wall())
				&& !level.getBlockState(new BlockPos(x, h + 2, z + 1)).is(style.wall())) {
				return r;
			}
		}
		return 0;
	}

	/** Every wall column records its fixed terrain profile; a complete set survives chunk unloads and save/reload. */
	private static int profiledWallRadius(ServerLevel level, BlockPos bell) {
		var owner = level.getBlockEntity(bell);
		if (owner == null) return 0;
		Map<Integer, BitSet> columnsByRadius = new HashMap<>();
		for (String key : owner.getAttachedOrElse(Wall.PROFILES, Map.of()).keySet()) {
			int split = key.indexOf(':');
			if (split <= 0 || split == key.length() - 1) continue;
			try {
				int radius = Integer.parseInt(key.substring(0, split));
				int column = Integer.parseInt(key.substring(split + 1));
				if (radius < Config.WALL_MIN_VILLAGE_RADIUS || radius > Development.MAX_WALL_RADIUS || column < 0 || column >= 8 * radius) continue;
				columnsByRadius.computeIfAbsent(radius, ignored -> new BitSet(8 * radius)).set(column);
			} catch (NumberFormatException ignored) {
				// Ignore malformed/stale attachment keys; physical gate detection remains available below.
			}
		}
		for (int radius = Development.MAX_WALL_RADIUS; radius >= Config.WALL_MIN_VILLAGE_RADIUS; radius--) {
			BitSet columns = columnsByRadius.get(radius);
			if (columns != null && columns.nextClearBit(0) >= 8 * radius) return radius;
		}
		return 0;
	}

	private static boolean startWallRepair(Ctx c, VillageRef ref) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		if (d.hasBuildProjectType(Blueprints.WALL)) return false;
		VillageStyle style = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), c.v()));
		int r = detectWallRadius(level, ref.bell(), style);
		if (r == 0) {
			return false;
		}
		boolean damaged = false;
		for (int t = 0; t < 8 * r && !damaged; t++) {
			damaged = !columnIntact(level, ref.bell(), style, r, t);
		}
		if (!damaged) {
			return false;
		}
		d.planType = Blueprints.WALL;
		d.planOrigin = Optional.of(ref.bell());
		d.planStep = 0;
		d.planVariant = r;
		d.planProgress = c.now();
		d.claimPlanBuilder(c.v().getUUID());
		REPAIRS.add(ref.bell().asLong() ^ WALL_KEY);
		ref.dirty();
		LOGGER.info("Villagers: Repurposed: Ausbesserung der Stadtmauer (Radius {})", r);
		return true;
	}

	/** A column is complete only when its available plan and every structural/decorative cell match. */
	static boolean columnIntact(ServerLevel level, BlockPos bell, VillageStyle style, int r, int t) {
		if (r <= 0 || t < 0 || t >= 8 * r) return false;
		int[] o = Wall.ringOffset(r, t);
		if (!level.hasChunkAt(new BlockPos(bell.getX() + o[0], bell.getY(), bell.getZ() + o[1]))) {
			return false;
		}
		List<Wall.Cell> cells = Wall.column(level, bell, style.wall(), style.stairs(), style.fence(), r, t);
		if (cells.isEmpty()) return false;
		for (Wall.Cell cell : cells) if (!Wall.matches(cell, level.getBlockState(cell.pos()))) return false;
		return true;
	}

	/**
	 * Nur fuer die Entwicklungsumgebung: legt einen Bauauftrag an einer festen Stelle an, den die Maurer dann ganz normal abarbeiten
	 * (zum Testen des echten Bauablaufs). {@code seed} = Entwurfs-Seed; bei "wall" der Radius.
	 */
	public static boolean debugPlan(ServerLevel level, VillageRef ref, String type, BlockPos hintSite, Direction hintDir, long seed, Villager hint) {
		VillageData d = ref.data();
		if (d.hasPlan()) {
			return false;
		}
		d.wanted.clear();
		if (type.equals(Blueprints.WALL)) {
			d.planType = type;
			d.planOrigin = Optional.of(ref.bell());
			d.planStep = 0;
			d.planVariant = (int) seed;
			d.planProgress = level.getGameTime();
			if (hint != null) d.claimPlanBuilder(hint.getUUID());
			ref.dirty();
			return true;
		}
		int half;
		int headroom;
		int planVariant = (int) (seed << 2);
		Architect.Design architectDesign = null;
		if (architect(type)) {
			planVariant = newPlanVariant(type, seed);
			architectDesign = designForPlan(type, planVariant);
			half = architectDesign.half();
			headroom = architectDesign.headroom();
		} else if (Landmarks.handles(type)) {
			half = Landmarks.half(type);
			headroom = Landmarks.headroom(type);
		} else {
			return false;
		}
		// Platzsuche genau wie beim echten Bau (Abstand zu Nachbarn, tragender Boden, Huegeltoleranz).
		net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(seed * 31 + 7);
		BlockPos site = Sites.find(level, ref.bell(), rnd, 8, 44, half, headroom, 120);
		if (site == null) {
			site = Sites.findTolerant(level, ref.bell(), rnd, 8, 44, -half, half, -half, half, headroom + 1, 200,
				Sites.villageReferenceY(level, ref));
		}
		if (site == null) {
			return false;
		}
		Direction dir = Sites.facingTowards(site, ref.bell());
		Palette palette = Palette.detect(level, ref.bell(), hint, seed, site);
		PALETTES.put(site.asLong(), palette);
		List<Step> steps = architect(type) ? Architect.build(architectDesign, palette, site, dir)
			: Landmarks.build(type, seed, palette, site, dir);
		STEPS.put(site.asLong(), steps);
		DEBUG_PLANS.add(site.asLong());
		d.planType = type;
		d.planOrigin = Optional.of(site);
		d.planDir = dir.get2DDataValue();
		d.planStep = 0;
		d.planVariant = planVariant;
		d.planSource = Optional.empty();
		d.planProgress = level.getGameTime();
		if (hint != null) d.claimPlanBuilder(hint.getUUID());
		ref.dirty();
		return true;
	}

	/** Nur fuer die Entwicklungsumgebung: baut die komplette Stadtmauer sofort (zum Betrachten). */
	public static int debugWall(ServerLevel level, VillageRef ref, int r, Villager hint) {
		VillageStyle style = wallStyle(level, ref.bell(), hint);
		int placed = 0;
		for (int t = 0; t < 8 * r; t++) {
			for (Wall.Cell cell : Wall.column(level, ref.bell(), style.wall(), style.stairs(), style.fence(), r, t)) {
				level.setBlock(cell.pos(), cell.state(), 2);
				placed++;
			}
		}
		return placed;
	}

	// --- Stadtmauer -----------------------------------------------------------------------------

	/**
	 * Stil der Stadtmauer: eine Burgmauer ist aus Stein (Steinziegel, sonst Bruchstein; in Wuesten Sandstein) - nicht aus dem
	 * Wandmaterial der Haeuser (das in manchen Doerfern Terrakotta ist). Zaun und Tuer bleiben wie im Dorf.
	 */
	private static VillageStyle wallStyle(ServerLevel level, BlockPos bell, Villager hint) {
		VillageStyle style = VillageStyle.detect(level, bell, hint);
		Block wall;
		Block stairs;
		if (Palette.biomeKind(level, bell).equals("desert")) {
			wall = Blocks.SANDSTONE;
			stairs = Blocks.SANDSTONE_STAIRS;
		} else if (Supply.obtainable(Blocks.STONE_BRICKS)) {
			wall = Blocks.STONE_BRICKS;
			stairs = Blocks.STONE_BRICK_STAIRS;
		} else {
			wall = Blocks.COBBLESTONE;
			stairs = Blocks.COBBLESTONE_STAIRS;
		}
		return new VillageStyle(wall, style.floor(), style.roof(), stairs, style.door(), style.fence());
	}

	private static final Map<Long, VillageStyle> WALL_STYLES = new HashMap<>();
	private static final Map<Long, Long> WALL_CHECKED = new HashMap<>();

	/** Stehen inzwischen Haeuser ausserhalb (bzw. zu nah an) der Mauer? Geprueft hoechstens alle 6000 Ticks. */
	private static boolean wallTooSmall(ServerLevel level, VillageRef ref) {
		long now = level.getGameTime();
		if (now < WALL_CHECKED.getOrDefault(ref.bell().asLong(), 0L)) {
			return false;
		}
		WALL_CHECKED.put(ref.bell().asLong(), now + 6000L);
		int needed = neededWallRadius(level, ref);
		if (needed == 0) {
			return false;
		}
		VillageStyle style = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), null));
		int current = detectWallRadius(level, ref.bell(), style);
		return current > 0 && needed > current + 4;
	}

	/** Radius, den die Mauer bei der jetzigen Dorfgroesse braucht (aeusserstes Haus + 10), sonst 0 (Dorf zu klein/zu verstreut). */
	private static int neededWallRadius(ServerLevel level, VillageRef ref) {
		return Development.neededWallRadius(level, ref);
	}

	private static final int WALL_HEIGHT = 5;

	/** Position der Mauersaeule t auf dem quadratischen Ring mit Radius r um die Glocke (Seiten im Uhrzeigersinn). */
	private static int[] ringOffset(int r, int t) {
		int side = t / (2 * r);
		int k = t % (2 * r);
		return switch (side) {
			case 0 -> new int[] {-r + k, -r};
			case 1 -> new int[] {r, -r + k};
			case 2 -> new int[] {r - k, r};
			default -> new int[] {-r, r - k};
		};
	}

	private static boolean isGate(int r, int t) {
		int k = t % (2 * r);
		return Math.abs(k - r) <= 1;
	}

	private static boolean startWall(Ctx c, VillageRef ref, Container box, List<Villager> all) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		int radius = neededWallRadius(level, ref);
		if (radius == 0) {
			c.st().buildCheckAt = c.now() + 6000;
			return false;
		}
		VillageStyle style = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), c.v()));
		int old = d.wallBuilt ? detectWallRadius(level, ref.bell(), style) : 0;
		if (old >= radius) {
			c.st().buildCheckAt = c.now() + 6000;
			return false;
		}
		Item wallItem = style.wall().asItem();
		d.wanted.clear();
		if (JobUtil.count(box, s -> s.is(wallItem)) < 192) {
			d.wanted.add(BuiltInRegistries.ITEM.getKey(wallItem) + "#900");
			ref.dirty();
			c.st().buildCheckAt = c.now() + 1800;
			return false;
		}
		d.planType = Blueprints.WALL;
		d.planOrigin = Optional.of(ref.bell());
		d.planStep = 0;
		// Erweiterungen bauen einen neuen Außenring; der fertige Innenring bleibt unverändert stehen.
		d.planVariant = radius;
		d.planProgress = c.now();
		d.claimPlanBuilder(c.v().getUUID());
		ref.dirty();
		JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal(old > 0
			? "Das Dorf ist gewachsen: Die Stadtmauer wird weiter draussen neu gebaut (Radius " + old + " -> " + radius + ")."
			: "Das Dorf beginnt mit dem Bau einer Stadtmauer (Radius " + radius + ")."));
		return true;
	}

	private static boolean continueWall(Ctx c, VillageRef ref, Container box) {
		ServerLevel level = c.level();
		VillageData d = ref.data();
		int r = d.planVariant & 0xFF;
		if (r <= 0 || r > Development.MAX_WALL_RADIUS) { abort(d, ref, c.now()); return false; }
		de.villagerplus.village.Anchor.keepWallWorkLoaded(level, ref, c.v().blockPosition());
		VillageStyle wallStyle = WALL_STYLES.computeIfAbsent(ref.bell().asLong(), k -> wallStyle(level, ref.bell(), c.v()));
		for (int skip = 0; skip < 300 && d.planStep < 8 * r && columnIntact(level, ref.bell(), wallStyle, r, d.planStep); skip++) {
			d.planStep++;
		}
		de.villagerplus.village.Anchor.keepWallWorkLoaded(level, ref, c.v().blockPosition());
		int restoreRadius = legacyWallRestoreRadius(d);
		if (d.planStep >= 8 * r && restoreRadius > 0) {
			// A saved pre-extension plan may already have begun dismantling its inner ring.
			// Rebuild only that damaged ring, paying normally, instead of deleting more of it.
			d.planStep = 0;
			d.planVariant = restoreRadius;
			d.planProgress = c.now();
			ref.dirty();
			return true;
		}
		if (d.planStep >= 8 * r) {
			if (REPAIRS.remove(ref.bell().asLong() ^ WALL_KEY)) {
				d.clearPlan();
				d.nextBuild = c.now() + 1200;
				d.wanted.clear();
				ref.dirty();
				JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal("Die Stadtmauer wurde ausgebessert."));
				return true;
			}
			d.wallBuilt = true;
			d.clearPlan();
			d.nextBuild = c.now() + Config.BUILD_COOLDOWN;
			d.wanted.clear();
			ref.dirty();
			JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal("Die Stadtmauer ist fertig!"));
			c.mem().jobsDone++;
			return true;
		}
		VillageStyle style = wallStyle;
		Item wallItem = style.wall().asItem();
		c.setActivity("baut die Stadtmauer");
		Anim.hold(c, wallItem);
		int[] off = Wall.ringOffset(r, d.planStep);
		int bx = ref.bell().getX() + off[0];
		int bz = ref.bell().getZ() + off[1];
		if (!level.hasChunkAt(new BlockPos(bx, ref.bell().getY(), bz))) {
			// Chunk nicht geladen: Saeule spaeter, Auftrag wartet.
			return waitForWall(c, ref, "wartet auf den geladenen Mauerabschnitt");
		}
		// Etwas Abstand zur Mauer (innen), nie auf der Mauer selbst und nicht auf der Treppe.
		net.minecraft.core.Direction in = Wall.inward(r, d.planStep);
		int sx = bx + in.getStepX() * 11;
		int sz = bz + in.getStepZ() * 11;
		BlockPos standSpot = new BlockPos(sx, Wall.groundHeight(level, sx, sz, style.wall()), sz);
		if (!JobUtil.approach(c, standSpot, 7.0)) {
			return true;
		}
		BlockPos selfPos = c.v().blockPosition();
		if (Math.abs(selfPos.getX() - bx) < 5 && Math.abs(selfPos.getZ() - bz) < 5 && Math.abs(selfPos.getY() - standSpot.getY()) < 12) {
			// Zu nah an der Baustelle (z. B. auf dem Wehrgang): zurueck auf festen Boden.
			c.v().teleportTo(standSpot.getX() + 0.5, standSpot.getY(), standSpot.getZ() + 0.5);
			return true;
		}
		for (int col = 0; col < 2 && d.planStep < 8 * r; col++) {
			int t = d.planStep;
			List<Wall.Cell> cells = Wall.column(level, ref.bell(), style.wall(), style.stairs(), style.fence(), r, t);
			if (cells.isEmpty()) return waitForWall(c, ref, "wartet auf einen geladenen, tragfähigen Mauerabschnitt");
			// Do not consume resources or advance the cursor through a foreign building/obstacle.
			for (Wall.Cell cell : cells) {
				BlockState current = level.getBlockState(cell.pos());
				if (!Wall.matches(cell, current) && (!Wall.mayReplace(cell, current) || occupied(level, cell.pos())))
					return waitForWall(c, ref, "Mauerabschnitt blockiert bei " + cell.pos().toShortString());
			}
			if (t == 0 || t % 40 == 0) {
				LOGGER.info("Villagers: Repurposed: Stadtmauer Saeule {} von {} (Radius {})", t, 8 * r, r);
			}
			for (Wall.Cell cell : cells) {
				if (cell.paid()) {
					if (!placeWall(c, ref, box, style, cell.pos())) {
						// A recorded material request belongs to the normal supplier cycle; keep
						// the paid cursor in place and let the builder mine/craft/deliver stock.
						return d.wanted.isEmpty();
					}
				} else {
					BlockState cur = level.getBlockState(cell.pos());
					if (!Wall.matches(cell, cur) && Wall.mayReplace(cell, cur) && !occupied(level, cell.pos())) {
						if (level.setBlock(cell.pos(), cell.state(), 3)) {
							Anim.blockPlace(level, cell.pos(), cell.state(), 0.8F, 1.0F);
						}
					}
				}
			}
			if (!columnIntact(level, ref.bell(), style, r, t)) return waitForWall(c, ref, "wartet auf die Fertigstellung der Mauersäule");
			JobUtil.swing(c.v());
			d.planStep++;
			d.planProgress = c.now();
		}
		ref.dirty();
		return true;
	}

	private static boolean waitForWall(Ctx c, VillageRef ref, String activity) {
		c.setActivity(activity); c.st().cooldownUntil = c.now() + 20;
		ref.data().planProgress = c.now(); ref.dirty(); return true;
	}

	/** Setzt einen Mauerblock, bezahlt aus der Dorfkiste. false = Material fehlt oder Platz belegt (Bau wartet). */
	private static boolean placeWall(Ctx c, VillageRef ref, Container box, VillageStyle style, BlockPos p) {
		ServerLevel level = c.level();
		BlockState cur = level.getBlockState(p);
		if (cur.is(style.wall())) return true;
		if (!(cur.isAir() || cur.canBeReplaced() || !cur.getFluidState().isEmpty())) return false;
		if (occupied(level, p)) {
			return false;
		}
		Item wallItem = style.wall().asItem();
		if (JobUtil.take(box, s -> s.is(wallItem), 1) < 1) {
			addWanted(ref.data(), new Step(p, wallItem, null, 1, false, null, b -> style.wall().defaultBlockState()), box);
			ref.dirty();
			JobUtil.tellNearbyPlayers(level, c.v(), 32, Component.literal("Für die Stadtmauer fehlt Material – die Dorfbewohner beschaffen Nachschub."));
			ref.data().planProgress = c.now();
			c.st().cooldownUntil = c.now() + 600;
			return false;
		}
		BlockState ns = style.wall().defaultBlockState();
		if (!level.setBlock(p, ns, 3)) {
			ItemStack refund = new ItemStack(wallItem); JobUtil.depositTo(box, refund);
			if (!refund.isEmpty()) JobUtil.give(level, c.v(), refund);
			return false;
		}
		Anim.blockPlace(level, p, ns, 0.8F, 1.0F);
		return true;
	}
	// --- Wege -----------------------------------------------------------------------------------

	/** Streut einen Trampelpfad (nur auf Gras/Erde) von einem Haus bis zur Dorfglocke. */
	static final class Paths {
		private Paths() {
		}

		static final int YARD_PLANTS = 0;
		static final int YARD_DESERT = 1;
		static final int YARD_FENCE = 2;
		static final int YARD_SNOW = 3;

		/** A stable choice from the biome id lets an active plan reconstruct the same yard after reload. */
		static int yardStyle(ServerLevel level, BlockPos origin, Direction dir) {
			return yardStyle(level, origin, dir, false);
		}

		static int yardStyle(ServerLevel level, BlockPos origin, Direction dir, boolean legacyLayout) {
			String biome = level.getBiome(yardCenter(origin, dir, legacyLayout)).unwrapKey()
				.map(key -> key.identifier().getPath()).orElse("plains");
			if (biome.contains("desert")) return YARD_DESERT;
			if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice_spikes") || biome.contains("grove")) return YARD_SNOW;
			if (biome.contains("badlands") || biome.contains("stony") || biome.contains("windswept") || biome.contains("jagged")
				|| biome.contains("peak") || biome.contains("beach") || biome.contains("mushroom_fields")) return YARD_FENCE;
			return YARD_PLANTS;
		}

		/** Fixed positions and block choices are derived from the saved house anchor and biome, never current occupancy. */
		static List<Step> yardSteps(ServerLevel level, BlockPos origin, Direction dir, int style) {
			return yardSteps(level, origin, dir, style, false);
		}

		static List<Step> yardSteps(ServerLevel level, BlockPos origin, Direction dir, int style, boolean legacyLayout) {
			List<Step> steps = new ArrayList<>();
			BlockPos center = yardCenter(origin, dir, legacyLayout);
			Direction side = dir.getClockWise();
			if (style == YARD_DESERT) {
				steps.add(Step.block(center, Blocks.CACTUS));
				for (int forward : new int[] {-1, 1}) for (int lateral : new int[] {-1, 1}) {
					steps.add(Step.block(center.relative(dir, forward).relative(side, lateral), Blocks.DEAD_BUSH));
				}
				return List.copyOf(steps);
			}
			if (style == YARD_FENCE) {
				for (int forward = -1; forward <= 1; forward++) for (int lateral = -1; lateral <= 1; lateral++) {
					if (Math.abs(forward) == 1 || Math.abs(lateral) == 1) {
						steps.add(Step.block(center.relative(dir, forward).relative(side, lateral), Blocks.OAK_FENCE));
					}
				}
				return List.copyOf(steps);
			}

			String biome = level.getBiome(center).unwrapKey().map(key -> key.identifier().getPath()).orElse("plains");
			boolean snowy = style == YARD_SNOW;
			BlockPos plantingCenter = snowy ? center.below() : center;
			Block sapling = snowy || biome.contains("taiga") || biome.contains("grove") ? Blocks.SPRUCE_SAPLING
				: biome.contains("jungle") ? Blocks.JUNGLE_SAPLING
				: biome.contains("savanna") ? Blocks.ACACIA_SAPLING
				: biome.contains("birch") ? Blocks.BIRCH_SAPLING : Blocks.OAK_SAPLING;
			steps.add(Step.block(plantingCenter, sapling));
			Block[] flowers = {Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY};
			BlockPos[] flowerBeds = {plantingCenter.relative(dir), plantingCenter.relative(dir.getOpposite()), plantingCenter.relative(side), plantingCenter.relative(side.getOpposite())};
			for (int i = 0; i < flowerBeds.length; i++) steps.add(Step.block(flowerBeds[i], flowers[i]));
			return List.copyOf(steps);
		}

		private static BlockPos yardCenter(BlockPos origin, Direction dir) {
			return yardCenter(origin, dir, false);
		}

		private static BlockPos yardCenter(BlockPos origin, Direction dir, boolean legacyLayout) {
			if (legacyLayout) return origin.relative(dir, 8).relative(dir.getClockWise(), 4);
			// Keep the complete 3x3 private garden inside the bounded 17x17 lot envelope.
			return origin.relative(dir, 6).relative(dir.getClockWise(), 6);
		}

		/** Candidate houses need a clear, level yard. Every landscaping block must be paid from the village store. */
		static boolean yardPlotFits(ServerLevel level, List<Step> yard, int style) {
			return yard != null && !yard.isEmpty() && yard.stream().allMatch(step -> yardCellClear(level, step, style));
		}

		/** A yard belongs to the parcel only when it stays outside the house columns and the straight door approach. */
		static boolean houseYardFits(ServerLevel level, List<Step> house, BlockPos origin, Direction dir, int style) {
			List<Step> yard = yardSteps(level, origin, dir, style);
			if (house == null || !yardPlotFits(level, yard, style)) return false;
			Set<Long> houseColumns = house.stream().map(step -> columnKey(step.pos())).collect(Collectors.toSet());
			for (Step step : yard) {
				BlockPos pos = step.pos();
				if (houseColumns.contains(columnKey(pos))) return false;
				for (int distance = 3; distance <= 7; distance++) {
					if (pos.getX() == origin.getX() + dir.getStepX() * distance
						&& pos.getZ() == origin.getZ() + dir.getStepZ() * distance) return false;
				}
			}
			return true;
		}

		private static long columnKey(BlockPos pos) {
			return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
		}

		/** True only for a still-empty target with suitable ground and room for its eventual plant. */
		static boolean yardCellClear(ServerLevel level, Step step, int style) {
			BlockPos pos = step.pos();
			if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())) return false;
			boolean snowy = style == YARD_SNOW;
			BlockState surface = level.getBlockState(pos);
			if (snowy ? !surface.is(Blocks.SNOW) : !surface.isAir()) return false;
			BlockState ground = level.getBlockState(pos.below());
			if (!ground.getFluidState().isEmpty() || ground.hasBlockEntity()) return false;
			if (style == YARD_PLANTS || snowy) {
				if (!yardSoil(ground)) return false;
			} else if (style == YARD_DESERT) {
				if (!ground.is(net.minecraft.tags.BlockTags.SAND)) return false;
			} else if (!ground.isFaceSturdy(level, pos.below(), Direction.UP)) {
				return false;
			}
			Block target = Block.byItem(step.item());
			int clear = target instanceof net.minecraft.world.level.block.SaplingBlock ? 5 : 1;
			for (int dy = snowy ? 1 : 0; dy < clear; dy++) {
				BlockPos head = pos.above(dy);
				if (!level.hasChunkAt(head) || !level.getBlockState(head).isAir()) return false;
			}
			return true;
		}

		private static boolean yardSoil(BlockState ground) {
			return ground.is(net.minecraft.tags.BlockTags.DIRT) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL)
				|| ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.MYCELIUM) || ground.is(Blocks.MOSS_BLOCK)
				|| ground.is(Blocks.ROOTED_DIRT);
		}

		/** Used both by the placement guard and preparation filter so landscaping never clears existing terrain. */
		static boolean isYardStep(ServerLevel level, BlockPos origin, Direction dir, BlockPos pos) {
			int style = yardStyle(level, origin, dir);
			return yardSteps(level, origin, dir, style).stream().anyMatch(step -> step.pos().equals(pos));
		}

		/** Plant the planned house garden only when its complete footprint belongs to a housing parcel. */
		static void garden(ServerLevel level, BlockPos origin, Direction dir, VillageRef ref) {
			int style = yardStyle(level, origin, dir);
			List<Step> garden = yardSteps(level, origin, dir, style);
			if (!ZoneAwareSites.allowsPlan(ref, Blueprints.HOUSE, garden) || !yardPlotFits(level, garden, style)) return;
			Container store = Lager.combined(level, ref);
			if (store == null) return;
			for (Step step : garden) {
				Block block = Block.byItem(step.item());
				if (block == Blocks.AIR || JobUtil.take(store, stack -> stack.is(step.item()), 1) != 1) continue;
				BlockState planting = block.defaultBlockState();
				if (!level.setBlock(step.pos(), planting, 3)) {
					level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
						step.pos().getX() + 0.5, step.pos().getY() + 0.25, step.pos().getZ() + 0.5, new ItemStack(step.item())));
				}
			}
		}

		static boolean lay(ServerLevel level, BlockPos from, BlockPos to) {
			return lay(level, from, to, null);
		}

		static boolean lay(ServerLevel level, BlockPos from, BlockPos to, VillageRef ref) {
			// Join the nearest saved village path from the house entrance, then fall back to the bell
			// only when there is no nearby saved path or its route is not reachable.
			BlockPos road = nearbyRoad(level, from, ref, false);
			if (road != null && layTo(level, from, road, true)) return true;
			return layTo(level, from, to, false);
		}

		private static boolean layTo(ServerLevel level, BlockPos from, BlockPos to, boolean roadTarget) {
			// Bounded A*: start at the exterior door, follow supported ground and route around houses, fields and cliffs.
			int minX = Math.min(from.getX(), to.getX()) - 8, maxX = Math.max(from.getX(), to.getX()) + 8;
			int minZ = Math.min(from.getZ(), to.getZ()) - 8, maxZ = Math.max(from.getZ(), to.getZ()) + 8;
			BlockPos start = pathSurface(level, from.getX(), from.getZ());
			if (start == null) return false;
			long startKey = BlockPos.asLong(start.getX(), 0, start.getZ());
			java.util.PriorityQueue<PathNode> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
			Map<Long, Double> costs = new HashMap<>();
			Map<Long, Long> parent = new HashMap<>();
			Map<Long, BlockPos> surfaces = new HashMap<>();
			costs.put(startKey, 0.0); surfaces.put(startKey, start);
			open.add(new PathNode(startKey, 0, pathHeuristic(start, to)));
			long goal = Long.MIN_VALUE;
			int expanded = 0;
			while (!open.isEmpty() && expanded++ < 4096) {
				PathNode node = open.poll();
				BlockPos current = surfaces.get(node.key());
				if (current == null || node.cost() > costs.getOrDefault(node.key(), Double.POSITIVE_INFINITY)) continue;
				if (Math.abs(current.getX() - to.getX()) + Math.abs(current.getZ() - to.getZ()) <= 1
					&& (!roadTarget || Math.abs(current.getY() - to.getY()) <= 1)) { goal = node.key(); break; }
				for (Direction step : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
					int x = current.getX() + step.getStepX(), z = current.getZ() + step.getStepZ();
					if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
					BlockPos next = pathSurface(level, x, z);
					if (next == null || Math.abs(next.getY() - current.getY()) > 1) continue;
					long key = BlockPos.asLong(x, 0, z);
					double stepCost = 1.0 + Math.abs(next.getY() - current.getY()) * 0.8
						+ (level.getBlockState(next).is(Blocks.DIRT_PATH) ? -0.35 : 0.0);
					double candidate = node.cost() + stepCost;
					if (candidate >= costs.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
					costs.put(key, candidate); parent.put(key, node.key()); surfaces.put(key, next);
					open.add(new PathNode(key, candidate, candidate + pathHeuristic(next, to)));
				}
			}
			if (goal == Long.MIN_VALUE) return false;
			List<BlockPos> route = new ArrayList<>();
			for (long key = goal; key != startKey; key = parent.getOrDefault(key, Long.MIN_VALUE)) {
				BlockPos p = surfaces.get(key);
				if (p == null) return false;
				route.add(p);
			}
			java.util.Collections.reverse(route);
			route.add(0, start);
			// Validate every column before the first mutation. In particular, never leave
			// the first half of a path behind if a later cell is no longer safe.
			List<BlockState> previous = new ArrayList<>(route.size());
			for (BlockPos p : route) {
				BlockPos checked = pathSurface(level, p.getX(), p.getZ());
				if (!p.equals(checked)) return false;
				previous.add(level.getBlockState(p));
			}
			for (int i = 0; i < route.size(); i++) {
				BlockPos p = route.get(i);
				if (previous.get(i).is(Blocks.DIRT_PATH)) continue;
				if (!level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), 3)) {
					for (int j = i - 1; j >= 0; j--) {
						if (!previous.get(j).is(Blocks.DIRT_PATH)) level.setBlock(route.get(j), previous.get(j), 3);
					}
					return false;
				}
			}
			return true;
		}

		/** Prefer the recorded village route, then a generated CTOV road when the village has no recorded route yet. */
		private static BlockPos nearbyRoad(ServerLevel level, BlockPos center, VillageRef ref, boolean requireKnownRoad) {
			BlockPos bestSaved = null;
			double bestSavedDistance = Double.MAX_VALUE;
			BlockPos bestDirtPath = null;
			double bestDirtPathDistance = Double.MAX_VALUE;
			boolean hasSavedRoute = false;
			if (ref != null) {
				boolean hasPlaza = de.villagerplus.village.Market.of(ref).plaza().isPresent();
				long built = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.ROADS, Map.of())
					.getOrDefault("center", 0L);
				hasSavedRoute = hasPlaza && built > 0L;
			}
			for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) {
				int x = center.getX() + dx, z = center.getZ() + dz;
				BlockPos column = new BlockPos(x, level.getMinY(), z);
				if (!level.hasChunkAt(column)) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				BlockPos ground = new BlockPos(x, y, z);
				BlockState surface = level.getBlockState(ground);
				boolean dirtPath = surface.is(Blocks.DIRT_PATH);
				boolean paved = surface.is(Blocks.GRAVEL) || surface.is(Blocks.SMOOTH_SANDSTONE)
					|| surface.is(Blocks.SANDSTONE) || surface.is(Blocks.COBBLESTONE) || surface.is(Blocks.SPRUCE_PLANKS);
				boolean savedVillageRoad = ref != null && de.villagerplus.village.Roads.trustedVillagePathCell(level, ref, ground);
				if (!level.getBlockState(ground.above()).isAir()) continue;
				double distance = ground.distSqr(center);
				if (dirtPath && !savedVillageRoad) {
					if (distance < bestDirtPathDistance) { bestDirtPath = ground; bestDirtPathDistance = distance; }
					continue;
				}
				if (!savedVillageRoad || (!dirtPath && !paved)) continue;
				if (distance < bestSavedDistance) { bestSaved = ground; bestSavedDistance = distance; }
			}
			if (hasSavedRoute) return bestSaved;
			BlockPos generated = ref == null ? null : nearbyGeneratedRoad(level, center);
			return generated != null ? generated : requireKnownRoad ? null : bestDirtPath;
		}

		private static BlockPos nearbyGeneratedRoad(ServerLevel level, BlockPos center) {
			int reach = HOUSE_ACCESS_CORRIDOR_REACH + HouseLotPreparation.MAX_LOT_HALF;
			BlockPos best = null;
			double bestDistance = Double.MAX_VALUE;
			for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
				if (Math.abs(dx) + Math.abs(dz) > reach) continue;
				BlockPos column = center.offset(dx, 0, dz);
				if (!level.hasChunkAt(column)) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
				BlockPos ground = new BlockPos(column.getX(), y, column.getZ());
				double distance = ground.distSqr(center);
				if (distance >= bestDistance || !Sites.isGeneratedVillageRoadGround(level, ground, level.getBlockState(ground))) continue;
				if (!level.getBlockState(ground.above()).isAir() || !level.getBlockState(ground.above(2)).isAir()) continue;
				best = ground;
				bestDistance = distance;
			}
			return best;
		}

		private record PathNode(long key, double cost, double score) { }

		private static double pathHeuristic(BlockPos from, BlockPos to) {
			return Math.max(0, Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ()) - 1);
		}

		private static BlockPos pathSurface(ServerLevel level, int x, int z) {
			BlockPos column = new BlockPos(x, level.getMinY(), z);
			if (!level.hasChunkAt(column)) return null;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			BlockPos ground = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(ground);
			if (!state.getFluidState().isEmpty() || state.is(Blocks.FARMLAND) || state.hasBlockEntity()) return null;
			boolean soil = state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
				|| state.is(Blocks.COARSE_DIRT) || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT_PATH);
			if (!soil || !state.isFaceSturdy(level, ground, Direction.UP)) return null;
			BlockState above = level.getBlockState(ground.above());
			if (!above.isAir()) return null;
			return ground;
		}

		static BlockPos entrance(List<Step> steps, BlockPos origin, Direction facing) {
			if (steps != null) for (Step step : steps) {
				if (step.linkedTo() != null) continue;
				Block material = step.item() == null ? Blocks.AIR : Block.byItem(step.item());
				if (step.tag() != null && !ItemTags.WOODEN_DOORS.equals(step.tag())) continue;
				if (step.tag() != null && material == Blocks.AIR) material = Blocks.OAK_DOOR;
				if (step.item() != null && !(material instanceof net.minecraft.world.level.block.DoorBlock)) continue;
				BlockState state = step.make().apply(material);
				if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
					&& state.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
					return step.pos().relative(state.getValue(net.minecraft.world.level.block.DoorBlock.FACING));
				}
			}
			return origin.relative(facing, 5);
		}
	}
}
