package de.villagerplus.dev;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Development-only: {@code VP_AUTOTEST=roles} runs one headless check mode and exits. */
public final class AutoTest {
	private static final String[] G02 = {"milestones", "woodcutting", "stand"};
	private static final String[] FEATURES = {"milestones", "woodcutting", "stand", "equipment", "school", "charcoal",
		"mining", "cookdelight", "demolition", "lightspacing", "planmaterialwait", "a03weather", "villagerename",
		"butcher", "healer", "roadlighting", "thermalrecipes", "smithsmelting", "supply"};
	private static final String[] WAVE1 = {"equipment", "a03weather", "villagerename", "butcher", "healer",
		"roadlighting", "thermalrecipes", "smithsmelting", "supply"};
	private static final String[] WAVE2 = {"houseplot", "roads", "healer"};
	private static final String[] WAVE3 = {"caravandelivery", "marketlifecycle", "networkdiscovery"};
	private static final String[] WAVE4 = {"caravandelivery", "marketlifecycle", "networkdiscovery",
		"planstonesupply", "cookdelight", "woodcuttinggroup"};
	private static final String[] WAVE5 = {"caravandelivery", "marketlifecycle", "networkdiscovery",
		"planstonesupply", "cookdelight", "woodcuttinggroup", "roads", "roadwork", "railtransport",
		"railmetro", "constructionsite", "roles"};
	private static final String[] WAVE6 = {"caravandelivery", "networkdiscovery", "planstonesupply", "woodcuttinggroup",
		"gravelcycle", "roads", "roadwork", "roles"};
	private static final String[] WAVE6_FIXES = {"woodcuttinggroup", "roles"};
	private static final String[] WAVE7 = {"roads", "planstonesupply", "miningcycle", "gravelcycle", "roles"};
	private static final String[] WAVE8 = {"demolition", "charcoal", "equipment"};
	private static final String[] WAVE8_FIXES = {"demolition"};
	private static final String[] WAVE9 = {"farmexpand", "caravansites", "marketlifecycle"};
	private static final String[] WAVE9_FIXES = {"farmexpand", "marketlifecycle"};
	private static final String[] WAVE10 = {"marketlifecycle", "networkdiscovery", "gravelcycle"};
	private static final String[] WAVE10_FIXES = {"marketlifecycle", "gravelcycle"};
	private static final String[] WAVE11 = {"cookdelight", "planstonesupply", "renamevillage", "equipment"};
	private static final String[] WAVE12 = {"marketlifecycle", "roadwork", "kitchenfurnisher", "woodcuttinggroup"};
	private static final String[] WAVE12_FIXES = {"marketlifecycle", "kitchenfurnisher", "woodcuttinggroup"};
	private static final String[] WAVE13 = {"marketlifecycle", "woodcuttinggroup", "lightspacing", "roadlighting", "smithanvil"};
	private static final String[] WAVE13_REST = {"marketlifecycle", "lightspacing", "roadlighting", "smithanvil"};
	private static final String[] WAVE14 = {"marketlifecycle", "lightspacing", "roadlighting", "smithanvil",
		"stockpolicy", "tavernservice", "railmetro", "roles"};
	private static final String[] WAVE14_FIXES = {"smithanvil", "roadlighting", "railmetro"};
	private static final String[] WAVE15 = {"roads", "caravandelivery", "farmexpand", "constructionsite", "plazageometry", "a03weather"};
	private static final String[] WAVE15_FIXES = {"caravandelivery", "a03weather"};
	private static final String[] WAVE16 = {"houses", "constructionsite", "supply", "planstonesupply", "caravandelivery", "a03weather"};
	private static final String[] WAVE16_FIXES = {"houses", "caravandelivery", "a03weather"};
	private static final String[] WAVE16_REMAINING = {"caravandelivery", "a03weather"};
	private static final String[] WAVE17 = {"caravandelivery", "a03weather", "thermalrecipes", "smithsmelting"};
	private static final String[] WAVE18 = {"farmexpand", "cookdelight", "houseplot", "mealcycle", "roadgrading",
		"roadwidth", "rolevisual", "neighbors", "caravanr02"};
	private static final String[] WAVE18_FIXES = {"farmexpand", "cookdelight", "mealcycle", "roadgrading", "caravanr02",
		"villagelookup", "roads", "roadwork", "railwork"};
	private static final String[] WAVE18_INFRA = {"supply", "caravanr02", "roadwork", "railwork", "lightspacing", "roadlighting"};
	private static final String[] WAVE19_PROGRESS = {"farmexpand", "roadwork", "railwork", "lightspacing"};
	private static final String[] WAVE19_FIXES = {"farmexpand", "railwork"};
	private static final String[] WAVE20 = {"caravanr02", "smithanvil", "stockpolicy"};
	private static final String[] WAVE20_FIXES = {"stockpolicy"};
	private static final String[] WAVE21 = {"woodcuttinggroup", "lightspacing", "mining"};
	private static final String[] WAVE22 = {"houses", "roadclear", "rolevisual"};
	private static final String[] WAVE23 = {"roads", "lightspacing", "cookdelight"};
	private static final String[] WAVE23_FIXES = {"lightspacing", "cookdelight", "stockpolicy"};
	private static final String[] WAVE24 = {"charcoal", "railmetro"};
	private static final String[] WAVE25 = {"houses", "a03weather", "villagerinfo"};
	private static final String[] WAVE25_FIXES = {"houses", "a03weather"};
	private static final String[] WAVE26_LIGHTING = {"roads", "lightspacing"};
	private static final String[] WAVE27_PARALLEL_FEATURES = {"roads", "lightspacing", "roadlighting", "houseplot",
		"houseconnection", "houses", "rolevisual"};
	private static final String[] WAVE27_FIXES = {"lightspacing", "roadlighting", "houseplot", "rolevisual", "privatechest"};
	private static final String[] WAVE28_FEATURES = {"houseplot", "privatechest", "equipment", "smithrail",
		"railtransportdispatch", "lightspacing", "roadlighting", "constructionsite"};
	private static final String[] WAVE29_FEATURES = {"lightspacing", "roadlighting", "houseplot", "wallconstruction",
		"caravanroad", "roadwork", "constructionsite", "planmaterialwait", "milestones", "marketlifecycle",
		"tavernmeals", "mealcycle", "cookdelight", "recipecrafting", "charcoal", "gravelcycle", "miningcycle",
		"caravansites", "roles"};
	private static final String[] WAVE30_FEATURES = {"lightspacing", "houseplot", "wallconstruction", "roadwork",
		"milestones", "marketlifecycle", "recipecrafting", "gravelcycle", "demolition", "caravansites",
		"minetickets", "buildingrelocation", "houses", "stockpolicy", "privatechest", "stationshed", "farmexpand",
		"craftrecipes", "networkdiscovery", "neighbors", "wellbeing"};
	private static final String[] WAVE31_FEATUREBATCH = {"houses", "stockpolicy", "privatechest", "mealcycle"};
	private static final String[] WAVE32_FEATUREBATCH = {"equipment", "developmentwall"};
	private static final String[] WAVE33_FEATUREBATCH = {"houses", "farmexpand", "stockpolicy", "mining", "cookdelight", "marketlifecycle", "roads", "milestones"};
	private static final String[] WAVE34_FEATUREBATCH = {"demolition", "lightspacing", "stockpolicy", "constructionsite", "wallconstruction", "roads", "builderchecks"};
	private static final String[] WAVE35_FIXES = {"demolition", "lightspacing", "wallconstruction"};
	private static final String[] WAVE36_RETRY = {"demolition", "wallconstruction"};
	private static final String[] RESUME_FIX_BATCH = {"cookdelight", "marketlifecycle", "milestones"};
	private static final String[] NEXT_FEATURE_BATCH = {"lightspacing", "constructionsite", "caravandelivery", "caravanr02", "privatechest", "zonepolicy", "railmetro"};
	private static final String[] FEATURE_FIX_BATCH = {"caravanr02", "stockpolicy"};
	private static final String[] MASTER_PROGRESS_BATCH = {"lightspacing", "roadlighting", "recipecrafting",
		"gravelcycle", "milestones", "planstonesupply"};
	private static final String[] CONTINUATION_BATCH = {"marketlifecycle", "equipment", "privatechest", "roads", "roadroute", "roadwork",
		"charcoal", "caravansites"};
	private static final String[] CONTINUATION_RETRY = {"marketlifecycle", "a04survival"};
	private static final String[] FEATURE_BUNDLE = {"constructionsite", "recipecrafting", "caravansites"};
	private static final String[] FEATURE_BUNDLE_2 = {"neighbors", "zoneexpansion", "houses", "lightspacing", "roadlighting"};
	private static final String[] CURRENT_FEATURE_SLICES = {"a01behavior", "mining", "marketlifecycle", "tavernmeals", "villagerinfo"};
	private static final String[] FEATURE_CONTINUATION = {"lightspacing", "recipecrafting", "milestones", "planstonesupply", "builderchecks", "builderpaths", "roadbuild", "roadterrain"};
	private static final String[] VILLAGE_EXPANSION = {"builderchecks", "developmentwall", "houseplot", "builderprojects",
		"roadbuild", "roadclear", "neighbors", "networkdiscovery"};
	private static final String[] CITYSCAPE = {"builderchecks", "houses", "marketplan", "marketlifecycle", "plazageometry"};
	private static final String[] FARM_START = {"builderchecks", "farmexpand", "livestockherding"};
	private static final String[] SETTLEMENT_NEXT = {"builderchecks", "houseplot", "marketplan", "privatechest", "roadclear", "socialcrowd", "neighbors",
		"farmexpand", "livestockherding", "woodcuttinggroup", "gravelcycle", "zonepolicy", "a03weather", "a04survival", "caravanr02"};
	private static final String[] PERFORMANCE = {"wellbeing", "mealcycle", "caravansites", "builderpaths", "villagelookup", "g02fairness"};
	private static final String[] FEATURE_WAVE = {"socialcrowd", "g02fairness", "railfuel", "woodcuttinggroup",
		"planmaterialwait", "mining", "miningcycle", "minelifecycle", "minetickets", "railmetro",
		"houseplot", "houses", "palette", "builderchecks", "builderprojects", "builderpaths", "buildhaul", "marketplan", "marketlifecycle", "u07quota",
		"caravansites", "caravanr02", "caravanroad", "caravandelivery"};
	private static final String[] FEATURE_WAVE_FIXES = {"woodcuttinggroup", "minelifecycle", "houseplot", "builderprojects",
		"buildhaul", "marketlifecycle", "u07quota"};
	private static final String[] FEATURE_BATCH_37 = {"builderchecks", "houseplot", "houseconnection", "houses", "marketplan",
		"marketlifecycle", "plazageometry", "farmexpand", "livestockherding", "neighbors", "networkdiscovery", "mining",
		"miningcycle", "minelifecycle", "caravansites"};
	private static final String[] FEATURE_BATCH_38 = {"builderprojects", "buildhaul", "demolition", "caravansites"};
	private static final String[] FEATURE_BATCH_39 = {"builderchecks", "builderpaths", "farmexpand", "caravansites",
		"rolevisual", "smithanvil", "equipment"};
	private static final String[] FEATURE_BATCH_40 = {"builderchecks", "builderpaths", "houses", "farmexpand", "livestockherding",
		"mining", "miningcycle", "minelifecycle", "privatechest", "marketplan", "plazageometry", "villagerinfo",
		"woodcuttinggroup", "caravansites", "zonepolicy"};
	private static final String[] FEATURE_BATCH_40_RETRY = {"builderchecks", "houses", "caravansites"};
	private static final String[] FEATURE_BATCH_41 = {"houses", "caravansites", "privatechest", "marketplan", "miningcycle", "minelifecycle", "plazageometry", "stockpolicy", "farmexpand", "livestockherding", "naturalprep", "g02fairness"};
	private static final String[] FEATURE_BATCH_41_RETRY = {"houses", "caravansites", "privatechest", "plazageometry", "stockpolicy", "farmexpand", "livestockherding", "naturalprep", "networkdiscovery"};
	private static final String[] FEATURE_BATCH_41_FIXES = {"houses", "caravansites", "privatechest", "marketplan", "houseplot", "farmexpand", "livestockherding", "naturalprep"};
	private static final String[] FEATURE_BATCH_42_RECOVERY = {"houses", "marketplan", "livestockherding", "farmexpand",
		"caravanr02", "houseplot", "naturalprep", "mining", "minelifecycle", "school", "builderchecks", "builderprojects"};
	private static final String[] FEATURE_BATCH_42_FIXES = {"marketprep", "caravandeparture", "houseplot", "naturalprep",
		"plazageometry", "mining", "builderprojects", "privatechest", "roadbuild", "developmentwall",
		"demolition", "woodcuttinggroup", "minelifecycle"};
	private static final String[] FEATURE_BATCH_42_REMAINING = {"marketprep", "mining"};
	private static final String[] FEATURE_BUNDLE_43 = {"builderchecks", "farmexpand", "livestockherding", "houses",
		"school", "rolevisual", "plazageometry", "marketprep", "mining", "demolition"};
	private static final String[] FEATURE_BUNDLE_44 = {"builderchecks", "privatechest", "caravandeparture", "socialcrowd"};
	private static final String[] FEATURE_BUNDLE_44_RETRY = {"privatechest", "caravandeparture"};
	private static final String[] FEATURE_BUNDLE_45 = {"builderchecks", "farmexpand", "livestockherding", "marketprep", "rolevisual"};
	private static final String[] FEATURE_BUNDLE_45_RETRY = {"livestockherding", "marketprep", "privatechest"};
	private static final String[] FEATURE_BUNDLE_46 = {"builderchecks", "civic", "plazageometry", "houses", "marketprep",
		"farmexpand", "livestockherding", "socialcrowd", "caravandeparture", "mining", "rolevisual", "villagelookup"};
	private static final String[] FEATURE_BUNDLE_46_RETRY = {"builderchecks", "civic", "houses", "farmexpand",
		"livestockherding", "caravandeparture"};
	private static final String[] FEATURE_BUNDLE_47 = {"builderchecks", "houseplot", "houses", "marketprep",
		"privatechest", "caravandeparture", "mining", "minelifecycle", "neighbors"};
	private static final String[] FEATURE_BUNDLE_48 = {"socialcrowd", "plazageometry", "rolevisual", "a04survival",
		"minetickets", "mining", "minelifecycle", "gravelcycle", "buildhaul", "supply", "planmaterialwait",
		"marketprep", "marketplan", "marketlifecycle", "farmexpand", "livestockherding", "statusformat", "houses",
		"kitchenfurnisher", "builderchecks", "caravansites"};
	private static final String[] FEATURE_BUNDLE_48_RETRY = {"gravelcycle"};
	private static final String[] FEATURE_BUNDLE_50 = {"builderchecks", "farmexpand", "livestockherding", "marketprep"};
	private static final String[] FEATURE_BUNDLE_51 = {"builderchecks", "houseplot", "marketprep", "neighbors"};
	private static final String[] FEATURE_BUNDLE_51_RETRY = {"houseplot", "marketprep", "neighbors"};
	private static final String[] FEATURE_BUNDLE_53 = {"houses", "plazageometry", "builderprojects", "farmexpand",
		"livestockherding", "marketprep", "marketplan"};
	/** One focused integration pass for the current farm, market, housing, and civic-center slices. */
	private static final String[] FEATURE_BUNDLE_56 = {"farmexpand", "livestockherding", "marketprep", "houseplot",
		"roadbuild", "houses", "plazageometry", "caravansites", "caravandeparture", "caravandelivery",
		"buildhaul", "builderchecks", "builderpaths", "builderprojects", "board"};
	private static final String[] FEATURE_BUNDLE_56_RETRY = {"houses", "caravansites", "caravandelivery"};
	/** Targeted integration for this batch's production changes; no unrelated master suites. */
	private static final String[] FEATURE_BUNDLE_58 = {"socialcrowd", "farmexpand", "naturalprep", "marketprep", "marketplan",
		"plazageometry", "privatechest", "minelifecycle", "buildhaul", "builderchecks", "builderprojects", "school"};
	private static final String[] FEATURE_BUNDLE_58_RETRY = {"farmexpand", "marketplan", "builderchecks", "school"};
	private static final String[] FEATURE_BUNDLE_58_RETRY2 = {"farmexpand", "marketplan"};
	/** Focused verification for the next farm, livestock, haul, and mining-crowding fixes. */
	private static final String[] FEATURE_BUNDLE_59 = {"farmexpand", "livestockherding", "butcher", "roles",
		"buildhaul", "builderchecks", "miningcycle", "mining", "g01spacing", "marketplan"};
	private static final String[] FEATURE_BUNDLE_59_RETRY = {"livestockherding", "butcher", "roles"};
	/** Focused verification for crop siting, continued housing expansion, partner actions, and trader visuals. */
	private static final String[] FEATURE_BUNDLE_60 = {"farmexpand", "houseplot", "developmentwall", "rolevisual"};
	/** Focused verification for path repair, villager flow, safe storage, plaza design, and caravan departure. */
	private static final String[] FEATURE_BUNDLE_61 = {"lightspacing", "socialcrowd", "privatechest", "plazageometry", "caravandeparture", "miningcycle"};
	private static final String[] FEATURE_BUNDLE_61_RETRY = {"lightspacing", "plazageometry"};
	/** Focused checks for the next housing, woodland, farm, mine, storage, and caravan visual repairs. */
	private static final String[] FEATURE_BUNDLE_62 = {"woodcuttinggroup", "farmexpand", "mining", "minelifecycle", "a01behavior", "buildhaul", "houses", "plazageometry"};
	/** House-site search, 12-block reservation and builder path regressions. */
	private static final String[] HOUSE_SEARCH = {"builderpaths", "houseplot", "builderchecks", "supply", "planstonesupply"};
	private static final String[] EQUIPMENT_BUNDLE = {"smithanvil", "equipment"};
	private static final String[] NEXT_VISIBLE = {"school", "houseplot", "caravanroad", "caravanr02", "projectvisibility"};
	private static final String[] NEXT_VISIBLE_FIX = {"caravanr02", "projectvisibility"};
	private static final String[] NEXT_GAMEPLAY = {"school", "villagerinfo", "demolition", "buildingrelocation", "a03weather",
		"farmexpand", "roles", "rolevisual", "privatechest", "mining", "minelifecycle", "caravansites", "caravanroad",
		"caravandelivery", "buildhaul", "builderprojects", "socialcrowd", "plazageometry", "marketlifecycle", "marketplan", "recipecrafting"};
	private static final String[] NEXT_GAMEPLAY_FIX = {"school", "roles", "stockpolicy", "caravansites"};
	private static final String[] HEADLESS = {
		"roles", "equipment", "mining", "roads", "roadwork", "roadclear", "woodcutting", "caravansites", "minelifecycle",
		"houses", "civic", "smithrail", "milestones", "farmexpand", "railtransport", "railmetro", "caravandelivery",
		"cookdelight", "compat"
	};

	private AutoTest() { }

	private static String readModeFile() {
		for (String path : new String[] {"vp-autotest.txt", "run/vp-autotest.txt"}) {
			try {
				var file = java.nio.file.Path.of(path);
				if (java.nio.file.Files.isRegularFile(file)) return java.nio.file.Files.readString(file).trim();
			} catch (java.io.IOException ignored) {
				return null;
			}
		}
		return null;
	}

	public static void register() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
		String configured = System.getenv("VP_AUTOTEST");
		if (configured == null || configured.isBlank()) configured = System.getProperty("vp.autotest");
		if (configured == null || configured.isBlank()) configured = readModeFile();
		if (configured == null || configured.isBlank()) return;
		String mode = configured.trim();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
			ServerTickEvents.END_SERVER_TICK.register(tickedServer -> {
				if (tickedServer != server || !started.compareAndSet(false, true)) return;
				server.execute(() -> {
			int code = 0;
			try {
				var source = server.createCommandSourceStack();
				String[] modes = mode.equals("headless") ? HEADLESS : mode.equals("g02") ? G02
					: mode.equals("features") ? FEATURES : mode.equals("wave1") ? WAVE1 : mode.equals("wave2") ? WAVE2
					: mode.equals("wave3") ? WAVE3 : mode.equals("wave4") ? WAVE4 : mode.equals("wave5") ? WAVE5
					: mode.equals("wave6") ? WAVE6 : mode.equals("wave6fixes") ? WAVE6_FIXES
					: mode.equals("wave7") ? WAVE7 : mode.equals("wave8") ? WAVE8
					: mode.equals("wave8fixes") ? WAVE8_FIXES : mode.equals("wave9") ? WAVE9
					: mode.equals("wave9fixes") ? WAVE9_FIXES : mode.equals("wave10") ? WAVE10
					: mode.equals("wave10fixes") ? WAVE10_FIXES : mode.equals("wave11") ? WAVE11
					: mode.equals("wave12") ? WAVE12 : mode.equals("wave12fixes") ? WAVE12_FIXES
					: mode.equals("wave13") ? WAVE13 : mode.equals("wave13rest") ? WAVE13_REST
					: mode.equals("wave14") ? WAVE14 : mode.equals("wave14fixes") ? WAVE14_FIXES
					: mode.equals("wave15") ? WAVE15 : mode.equals("wave15fixes") ? WAVE15_FIXES
					: mode.equals("wave16") ? WAVE16 : mode.equals("wave16fixes") ? WAVE16_FIXES
					: mode.equals("wave16remaining") ? WAVE16_REMAINING : mode.equals("wave17") ? WAVE17
					: mode.equals("wave18") ? WAVE18 : mode.equals("wave18fixes") ? WAVE18_FIXES
					: mode.equals("wave18infra") ? WAVE18_INFRA : mode.equals("wave19progress") ? WAVE19_PROGRESS
					: mode.equals("wave19fixes") ? WAVE19_FIXES : mode.equals("wave20") ? WAVE20
					: mode.equals("wave20fixes") ? WAVE20_FIXES : mode.equals("wave21") ? WAVE21
					: mode.equals("wave22") ? WAVE22 : mode.equals("wave23") ? WAVE23
					: mode.equals("wave23fixes") ? WAVE23_FIXES : mode.equals("wave24") ? WAVE24
					: mode.equals("wave25") ? WAVE25 : mode.equals("wave25fixes") ? WAVE25_FIXES
					: mode.equals("wave26lighting") ? WAVE26_LIGHTING
					: mode.equals("wave27parallel") ? WAVE27_PARALLEL_FEATURES
					: mode.equals("wave27fixes") ? WAVE27_FIXES
					: mode.equals("wave28features") ? WAVE28_FEATURES : mode.equals("wave29features") ? WAVE29_FEATURES
					: mode.equals("wave30features") ? WAVE30_FEATURES
					: mode.equals("wave31featurebatch") ? WAVE31_FEATUREBATCH
					: mode.equals("wave32featurebatch") ? WAVE32_FEATUREBATCH
					: mode.equals("wave33featurebatch") ? WAVE33_FEATUREBATCH
					: mode.equals("wave34featurebatch") ? WAVE34_FEATUREBATCH
					: mode.equals("wave35fixes") ? WAVE35_FIXES
					: mode.equals("wave36retry") ? WAVE36_RETRY
					: mode.equals("nextfeaturebatch") ? NEXT_FEATURE_BATCH
					: mode.equals("featurefixbatch") ? FEATURE_FIX_BATCH
					: mode.equals("masterprogress") ? MASTER_PROGRESS_BATCH
					: mode.equals("continuationbatch") ? CONTINUATION_BATCH
					: mode.equals("continuationretry") ? CONTINUATION_RETRY
					: mode.equals("featurebundle") ? FEATURE_BUNDLE
					: mode.equals("featurebundle2") ? FEATURE_BUNDLE_2
					: mode.equals("currentfeatureslices") ? CURRENT_FEATURE_SLICES
					: mode.equals("featurecontinuation") ? FEATURE_CONTINUATION
					: mode.equals("villageexpansion") ? VILLAGE_EXPANSION
					: mode.equals("cityscape") ? CITYSCAPE
					: mode.equals("farmstart") ? FARM_START
					: mode.equals("settlementnext") ? SETTLEMENT_NEXT
					: mode.equals("featurewave") ? FEATURE_WAVE
					: mode.equals("featurewavefix") ? FEATURE_WAVE_FIXES
					: mode.equals("featurebatch37") ? FEATURE_BATCH_37
					: mode.equals("featurebatch38") ? FEATURE_BATCH_38
					: mode.equals("featurebatch39") ? FEATURE_BATCH_39
					: mode.equals("featurebatch40") ? FEATURE_BATCH_40
					: mode.equals("featurebatch40retry") ? FEATURE_BATCH_40_RETRY
					: mode.equals("featurebatch41") ? FEATURE_BATCH_41
					: mode.equals("featurebatch41retry") ? FEATURE_BATCH_41_RETRY
					: mode.equals("featurebatch41fixes") ? FEATURE_BATCH_41_FIXES
					: mode.equals("featurebatch42recovery") ? FEATURE_BATCH_42_RECOVERY
					: mode.equals("featurebatch42fixes") ? FEATURE_BATCH_42_FIXES
					: mode.equals("featurebatch42remaining") ? FEATURE_BATCH_42_REMAINING
					: mode.equals("featurebundle43") ? FEATURE_BUNDLE_43
					: mode.equals("featurebundle44") ? FEATURE_BUNDLE_44
					: mode.equals("featurebundle44retry") ? FEATURE_BUNDLE_44_RETRY
					: mode.equals("featurebundle45") ? FEATURE_BUNDLE_45
					: mode.equals("featurebundle45retry") ? FEATURE_BUNDLE_45_RETRY
					: mode.equals("featurebundle46") ? FEATURE_BUNDLE_46
					: mode.equals("featurebundle46retry") ? FEATURE_BUNDLE_46_RETRY
					: mode.equals("featurebundle47") ? FEATURE_BUNDLE_47
					: mode.equals("featurebundle48") ? FEATURE_BUNDLE_48
					: mode.equals("featurebundle48retry") ? FEATURE_BUNDLE_48_RETRY
					: mode.equals("featurebundle50") ? FEATURE_BUNDLE_50
					: mode.equals("featurebundle51") ? FEATURE_BUNDLE_51
					: mode.equals("featurebundle51retry") ? FEATURE_BUNDLE_51_RETRY
					: mode.equals("featurebundle53") ? FEATURE_BUNDLE_53
					: mode.equals("featurebundle56") ? FEATURE_BUNDLE_56
					: mode.equals("featurebundle56retry") ? FEATURE_BUNDLE_56_RETRY
					: mode.equals("featurebundle58") ? FEATURE_BUNDLE_58
					: mode.equals("featurebundle58retry") ? FEATURE_BUNDLE_58_RETRY
					: mode.equals("featurebundle58retry2") ? FEATURE_BUNDLE_58_RETRY2
					: mode.equals("featurebundle59") ? FEATURE_BUNDLE_59
					: mode.equals("featurebundle59retry") ? FEATURE_BUNDLE_59_RETRY
					: mode.equals("featurebundle60") ? FEATURE_BUNDLE_60
					: mode.equals("featurebundle61") ? FEATURE_BUNDLE_61
					: mode.equals("featurebundle61retry") ? FEATURE_BUNDLE_61_RETRY
					: mode.equals("featurebundle62") ? FEATURE_BUNDLE_62
					: mode.equals("housesearch") ? HOUSE_SEARCH
					: mode.equals("equipmentbundle") ? EQUIPMENT_BUNDLE
					: mode.equals("nextvisible") ? NEXT_VISIBLE
					: mode.equals("nextvisiblefix") ? NEXT_VISIBLE_FIX
					: mode.equals("nextgameplay") ? NEXT_GAMEPLAY
					: mode.equals("nextgameplayfix") ? NEXT_GAMEPLAY_FIX
					: mode.equals("performance") ? PERFORMANCE
					: mode.equals("resumefixbatch") ? RESUME_FIX_BATCH : new String[] {mode};
				System.out.println("VP_AUTOTEST MODES " + String.join(",", modes));
				int passed = 0;
				var failedModes = new java.util.ArrayList<String>();
				var pendingModes = new java.util.ArrayList<String>();
				for (String part : modes) {
					try {
						int groupPassed;
						if (part.equals("builderchecks")) {
							groupPassed = de.villagerplus.build.BuilderChecks.run();
						} else if (part.equals("railtransportdispatch")) {
							groupPassed = de.villagerplus.village.RailTransportDispatchChecks.run(server.overworld(), new net.minecraft.core.BlockPos(25200, 280, 25200));
							if (groupPassed < 0) {
								pendingModes.add(part);
								System.out.println("VP_AUTOTEST GROUP PREPARING railtransportdispatch: waiting for the normal 200-tick dispatch");
								continue;
							}
						} else groupPassed = Checks090.run(source, part);
						if (groupPassed < 0) {
							pendingModes.add(part);
							System.out.println("VP_AUTOTEST GROUP PREPARING " + part + ": waiting for readiness");
							continue;
						}
						if (part.equals("u07quota") && groupPassed == 0) {
							throw new IllegalStateException("u07quota fixture became ready without executing checks");
						}
						passed += groupPassed;
						System.out.println("VP_AUTOTEST GROUP PASS " + part + " " + groupPassed);
					} catch (Throwable failure) {
						code = 1;
						failedModes.add(part);
						if (part.equals("marketlifecycle")) {
							de.villagerplus.village.MarketLifecycleChecks.releasePendingEntityTickTickets(server.overworld());
						}
						System.err.println("VP_AUTOTEST GROUP FAIL " + part + ": " + failure);
						failure.printStackTrace();
					}
				}
				if (!pendingModes.isEmpty()) {
					schedulePendingGroups(server, pendingModes, passed, code, failedModes);
					return;
				}
				if (failedModes.isEmpty()) System.out.println("VP_AUTOTEST PASS " + passed);
				else System.out.println("VP_AUTOTEST PARTIAL " + passed + " FAILED " + String.join(",", failedModes));
			} catch (Throwable failure) {
				code = 1;
				failure.printStackTrace();
			}
			haltAfterChecks(code);
				});
			});
		});
	}

	private static void schedulePendingGroups(net.minecraft.server.MinecraftServer server, java.util.List<String> pendingModes,
		int passed, int exitCode, java.util.List<String> failedModes) {
		var pending = new java.util.LinkedHashMap<String, Integer>();
		for (String mode : pendingModes) pending.putIfAbsent(mode, 0);
		var totalPassed = new java.util.concurrent.atomic.AtomicInteger(passed);
		var finalExitCode = new java.util.concurrent.atomic.AtomicInteger(exitCode);
		var failures = new java.util.ArrayList<>(failedModes);
		var finished = new java.util.concurrent.atomic.AtomicBoolean();
		ServerTickEvents.END_SERVER_TICK.register(tickedServer -> {
			if (tickedServer != server || finished.get()) return;
			var iterator = pending.entrySet().iterator();
			while (iterator.hasNext()) {
				var entry = iterator.next();
				String mode = entry.getKey();
				int age = entry.getValue() + 1;
				entry.setValue(age);
				int timeoutTicks = mode.equals("railtransportdispatch") || mode.equals("featurebundle55") ? 1200 : 200;
				if (age > timeoutTicks) {
					failPendingGroup(server, mode, failures, finalExitCode,
						"fixture did not become ready within " + timeoutTicks + " ticks", null);
					iterator.remove();
					continue;
				}
				try {
					int groupPassed = runPendingGroup(server, mode);
					if (groupPassed < 0) continue;
					if ((mode.equals("u07quota") || mode.equals("caravandelivery")) && groupPassed == 0) {
						throw new IllegalStateException("fixture became ready without executing checks");
					}
					totalPassed.addAndGet(groupPassed);
					System.out.println("VP_AUTOTEST GROUP PASS " + mode + " " + groupPassed);
					iterator.remove();
				} catch (Throwable failure) {
					failPendingGroup(server, mode, failures, finalExitCode, failure.toString(), failure);
					iterator.remove();
				}
			}
			if (!pending.isEmpty() || !finished.compareAndSet(false, true)) return;
			if (failures.isEmpty()) System.out.println("VP_AUTOTEST PASS " + totalPassed.get());
			else System.out.println("VP_AUTOTEST PARTIAL " + totalPassed.get() + " FAILED " + String.join(",", failures));
			haltAfterChecks(finalExitCode.get());
		});
	}

	private static int runPendingGroup(net.minecraft.server.MinecraftServer server, String mode) throws Exception {
		if (mode.equals("featurebundle55")) {
			int freight = de.villagerplus.village.RailTransportDispatchChecks.run(server.overworld(),
				new net.minecraft.core.BlockPos(25200, 280, 25200));
			if (freight < 0) return -1;
			var source = server.createCommandSourceStack();
			return freight + Checks090.run(source, "plazageometry")
				+ Checks090.run(source, "caravandeparture");
		}
		if (mode.equals("railtransportdispatch")) {
			return de.villagerplus.village.RailTransportDispatchChecks.run(server.overworld(),
				new net.minecraft.core.BlockPos(25200, 280, 25200));
		}
		return Checks090.run(server.createCommandSourceStack(), mode);
	}

	private static void failPendingGroup(net.minecraft.server.MinecraftServer server, String mode, java.util.List<String> failures,
		java.util.concurrent.atomic.AtomicInteger exitCode, String reason, Throwable failure) {
		exitCode.set(1);
		if (!failures.contains(mode)) failures.add(mode);
		if (mode.equals("marketlifecycle")) {
			de.villagerplus.village.MarketLifecycleChecks.releasePendingEntityTickTickets(server.overworld());
		}
		if (mode.equals("builderprojects")) {
			de.villagerplus.build.BuilderProjectChecks.releasePendingFixtureTicket(server.overworld());
		}
		if (mode.equals("mining")) {
			de.villagerplus.job.MiningSafetyChecks.releasePendingFixtureTickets(server.overworld());
		}
		System.err.println("VP_AUTOTEST GROUP FAIL " + mode + ": " + reason);
		if (failure != null) failure.printStackTrace();
	}

	private static void haltAfterChecks(int exit) {
		Thread halt = new Thread(() -> {
			try {
				Thread.sleep(1500);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			Runtime.getRuntime().halt(exit);
		}, "vp-autotest-halt");
		halt.setDaemon(true);
		halt.start();
	}
}
