package de.villagerplus.village;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.Mining;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.text.NumberFormat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Alle Dorfstatistiken als Textzeilen (fuer die Dorftafel und den Statusbefehl). */
public final class VillageStats {
	/** Mirrors Builder.STUCK_TICKS so a regular work interval is not labelled as a stalled build. */
	static final long CONSTRUCTION_STALL_TICKS = 36_000L;
	private static final int MATERIALS_PER_STATUS_LINE = 4;

	private VillageStats() {
	}

	public static List<String> lines(ServerLevel level, VillageRef ref) {
		VillageData d = ref.data();
		List<Villager> people = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> true);
		List<Villager> residents = people.stream().filter(v -> !Guests.isGuest(v)).toList();
		long guests = people.size() - residents.size();
		int pop = residents.size();

		Map<String, Integer> jobs = new TreeMap<>();
		int homeless = 0;
		int moodSum = 0;
		for (Villager v : residents) {
			jobs.merge(Names.profession(v.getVillagerData().profession()), 1, Integer::sum);
			if (!Housing.hasHome(v)) {
				homeless++;
			}
			VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			moodSum += Wellbeing.of(level, v, mem).mood();
		}
		int score = Wealth.score(level, ref, pop);

		List<String> out = new ArrayList<>();
		out.add("=== Dorftafel ===");
		out.add("Einwohner: " + pop + (guests > 0 ? " (+ " + guests + " Gäste)" : "") + ", ohne Haus: " + homeless
			+ ", Stimmung Ø " + (pop == 0 ? 0 : moodSum / pop) + "%");
		out.add("Berufe: " + jobs);
		out.add("Verwaltung: " + VillageAdmin.summary(ref));
		out.add("Bauwerke: " + d.houses + " neue Häuser, " + d.farms + " Felder, Brunnen " + yes(d.wellBuilt) + ", Rathaus " + yes(d.townHallBuilt)
			+ ", Stadtmauer " + yes(d.wallBuilt) + ", Gehege " + yes(d.extra.pen.isPresent()));
		out.add("Dorfreichtum: " + score + " Punkte (Stufe " + Wealth.tier(score) + "), Aufbauten: " + d.extra.decor);
		Container box = ref.chest(level);
		if (box != null) {
			out.add("Dorfkiste: " + JobUtil.count(box, s -> s.is(Items.EMERALD)) + " Smaragde, " + JobUtil.count(box, s -> s.is(Items.IRON_INGOT))
				+ " Eisen, " + JobUtil.count(box, s -> s.is(Items.COBBLESTONE)) + " Bruchstein, " + JobUtil.count(box, de.villagerplus.job.Meals::isFood) + " Nahrung");
		}
		out.add("Dorfkasse: " + Network.treasuryEmeralds(ref) + " Smaragde");
		if (d.extra.lager.isPresent()) {
			int[] f = Lager.fill(level, d);
			out.add("Lager: " + Lager.centers(d).size() + " Schuppen, " + f[0] + " von " + f[1] + " Plätzen belegt");
		}
		if (d.extra.pen.isPresent()) {
			long animals = level.getEntitiesOfClass(Animal.class, new AABB(d.extra.pen.get()).inflate(6, 3, 6), Animal::isAlive).size();
			out.add("Tiere im Gehege: " + animals);
		}
		List<VillageData.BuildProject> activeProjects = d.buildProjects.stream().filter(VillageData.BuildProject::active).toList();
		String currentBuild = !activeProjects.isEmpty()
			? activeProjects.size() + " aktive Baustelle" + (activeProjects.size() == 1 ? "" : "n")
				+ " (Hauptplan " + planName(activeProjects.getFirst().type()) + ", Schritt " + activeProjects.getFirst().step() + ")"
			: d.hasPlan() ? planName(d.planType) + " (Schritt " + d.planStep + ")" : "keiner";
		out.add("Aktueller Bau: " + currentBuild + ", Mine: " + Mining.describe(d));
		out.addAll(liveSite(level, ref, residents));
		// Nur die noch FEHLENDEN Sorten stehen auf der Wunschliste; alles Uebrige liegt schon bereit. Angezeigt: vorhanden/benoetigt.
		var savedConstruction = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		int planSize = de.villagerplus.build.Builder.planSize(d, savedConstruction);
		String planSummary = !activeProjects.isEmpty()
			? activeProjects.size() + (activeProjects.size() == 1 ? " aktive Baustelle" : " aktive Baustellen")
			: d.hasPlan() ? planName(d.planType) + " · " + planSize + " Bausteine" : "keiner";
		List<Container> storage = Balance.storage(level, ref);
		List<String> wanted = activeProjects.isEmpty() ? d.wanted : combineWanted(activeProjects);
		if (wanted.isEmpty() && !d.wanted.isEmpty()) wanted = d.wanted;
		List<MaterialNeed> needs = materialNeeds(wanted, storage);
		long missingKinds = needs.stream().filter(n -> n.shortage() > 0).count();
		out.add("Bauplan: " + planSummary + (missingKinds == 0 ? " · Material bereit" : " · " + missingKinds + " Materialarten fehlen"));
		if (!needs.isEmpty()) addRequestedMaterials(out, "Zur Baustelle", needs);
		List<MaterialNeed> stocked = needs.stream().filter(n -> n.available() > 0).toList();
		if (!stocked.isEmpty()) addAvailableMaterials(out, "Öffentliches Lager", stocked);
		List<MaterialNeed> procurement = needs.stream().filter(n -> n.shortage() > 0).toList();
		if (!procurement.isEmpty()) addShortageMaterials(out, "Noch zu beschaffen", procurement);
		String nextAction = materialNextAction(procurement);
		if (nextAction != null) out.add(nextAction);
		long caravanIn = Math.max(0, (d.extra.nextCaravan - level.getGameTime()) / 20 / 60);
		out.add("Nächste Handelskarawane in ca. " + caravanIn + " Minuten");
		long fest = Festival.daysUntil(level);
		out.add("Dorffest: " + (fest == 0 ? "heute Abend!" : "in " + fest + " Tagen") + " (alle " + de.villagerplus.Config.FESTIVAL_PERIOD_DAYS + " Tage, ab " + de.villagerplus.Config.FESTIVAL_MIN_POP + " Einwohnern)");
		out.addAll(Market.lines(ref));
		return out;
	}

	private static String yes(boolean b) {
		return b ? "ja" : "nein";
	}

	/** Baustelle live: Planschritte, Bauarbeiter und konkrete Wartegruende; Aktualisierung alle zwei Sekunden. */
	private static List<String> liveSite(ServerLevel level, VillageRef ref, List<Villager> residents) {
		VillageData d = ref.data();
		List<VillageData.BuildProject> projects = d.buildProjects.stream().filter(VillageData.BuildProject::active).toList();
		if (!projects.isEmpty()) return liveProjectSites(level, ref, residents, projects);
		return legacyLiveSite(level, ref, residents);
	}

	private static List<String> liveProjectSites(ServerLevel level, VillageRef ref, List<Villager> residents,
			List<VillageData.BuildProject> projects) {
		List<String> out = new ArrayList<>();
		List<Container> storage = Balance.storage(level, ref);
		de.villagerplus.build.ConstructionSite attached = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		for (VillageData.BuildProject project : projects) {
			de.villagerplus.build.ConstructionSite site = project.construction()
				.filter(saved -> saved.origin().equals(project.origin())).orElseGet(() -> attached != null
					&& attached.origin().equals(project.origin()) ? attached : null);
			int total = site == null || site.phaseEnds().isEmpty() ? 0
				: Math.max(0, site.phaseEnds().get(site.phaseEnds().size() - 1));
			int step = project.step();
			int pct = total <= 0 ? 0 : Math.min(100, step * 100 / total);
			String progress = total <= 0 ? "Schritt " + step + "/?" : "Schritt " + step + "/" + total + ", " + pct + " %";
			out.add("Baustelle LIVE: " + planName(project.type()) + " – " + progress);
			out.add("  Ort: " + project.origin().getX() + " " + project.origin().getY() + " " + project.origin().getZ());
			if (site != null && total > 0 && !site.phaseEnds().isEmpty()) {
				int phase = site.phaseFor(step, total);
				out.add("  Bauphase " + (phase + 1) + "/" + site.phaseEnds().size() + ": "
					+ de.villagerplus.build.ConstructionSite.phaseName(phase) + " – "
					+ site.phaseCompleted(step, phase) + "/" + site.phaseTotal(phase) + " Schritte ("
					+ site.phasePercent(step, phase) + " %)");
				out.add("  Vorbereitung: " + (site.prepared() ? "bereit" : "steht aus") + ", "
					+ scaffoldText(site.scaffoldCount()) + ", Werkbank " + (site.hasWorkbench() ? "aufgestellt" : "nicht aufgestellt"));
			} else if (step == 0) {
				out.add("  Materialprüfung und Geländevorbereitung");
			}
			out.add("  Baustellen-Team: " + projectWorkers(level, project, residents));
			String stall = constructionStallLine(project, level.getGameTime());
			if (stall != null) out.add("  " + stall);
			if (!project.wanted().isEmpty()) {
				List<MaterialNeed> needs = materialNeeds(project.wanted(), storage);
				if (!needs.isEmpty()) addRequestedMaterials(out, "  Noch zu liefern", needs);
			}
			for (String waitLine : constructionWaitLines(project)) out.add("  " + waitLine);
		}
		return out;
	}

	static String constructionStallLine(VillageData.BuildProject project, long now) {
		if (project == null || !constructionStallEligible(project)) return null;
		return constructionStallLine(project.progress(), now);
	}

	static String legacyConstructionStallLine(VillageData data, long now) {
		if (data == null || !data.hasPlan() || !data.wanted.isEmpty()
			|| data.extra.planMaterialWait.isPresent() || data.planBuilderUnavailableSince() > 0L) return null;
		return constructionStallLine(data.planProgress, now);
	}

	static boolean constructionStallEligible(VillageData.BuildProject project) {
		return project != null && project.active() && project.wanted().isEmpty()
			&& project.materialWait().isEmpty() && project.ownerUnavailableSince() == 0L;
	}

	static List<String> constructionWaitLines(VillageData.BuildProject project) {
		List<String> lines = new ArrayList<>(2);
		if (!project.wanted().isEmpty() && project.materialWait().isEmpty()) lines.add("Wartet auf Baumaterial.");
		project.materialWait().filter(reason -> !reason.isBlank()).ifPresent(reason -> lines.add("Wartet: " + reason));
		if (project.materialWait().isPresent() && project.materialWait().get().isBlank()) lines.add("Wartet auf Baumaterial.");
		if (project.ownerUnavailableSince() > 0L) lines.add("Wartet auf einen verfügbaren Baumeister.");
		return lines;
	}

	static String constructionStallLine(long progress, long now) {
		if (progress <= 0L || progress > now) return null;
		long idleTicks = now - progress;
		if (idleTicks < CONSTRUCTION_STALL_TICKS) return null;
		return "Kein Bau- oder Materialfortschritt seit " + formatDuration(idleTicks / 20L) + " Spielzeit.";
	}

	private static String projectWorkers(ServerLevel level, VillageData.BuildProject project, List<Villager> residents) {
		Set<UUID> linked = new HashSet<>();
		project.owner().ifPresent(linked::add);
		for (String row : project.cargoManifest()) {
			String[] fields = row.split("\\|", -1);
			if (fields.length != 4) continue;
			try { linked.add(UUID.fromString(fields[0])); }
			catch (IllegalArgumentException ignored) { }
		}
		List<String> workers = new ArrayList<>();
		for (Villager villager : residents) {
			String activity = JobManager.activityOf(villager, level);
			boolean atProject = project.bounds().map(bounds -> villager.getBlockX() >= bounds.minX() - 12
				&& villager.getBlockX() <= bounds.maxX() + 12 && villager.getBlockZ() >= bounds.minZ() - 12
				&& villager.getBlockZ() <= bounds.maxZ() + 12).orElseGet(() ->
					villager.blockPosition().distSqr(project.origin()) <= 24L * 24L);
			if (!linked.contains(villager.getUUID()) && !(atProject && constructionActivity(activity))) continue;
			String name = villager.getAttachedOrCreate(VillagerPlus.MEMORY).name;
			workers.add((name.isEmpty() ? "Bauarbeiter" : name) + " – " + activity);
		}
		return workers.isEmpty() ? "niemand aktuell zugeordnet/aktiv" : String.join("; ", workers);
	}

	private static boolean constructionActivity(String activity) {
		if (activity == null) return false;
		String lower = activity.toLowerCase(java.util.Locale.ROOT);
		return lower.startsWith("baut") || lower.contains("baumaterial") || lower.contains("bauplan")
			|| lower.contains("bauholz") || lower.contains("baustein");
	}

	private static List<String> legacyLiveSite(ServerLevel level, VillageRef ref, List<Villager> residents) {
		VillageData d = ref.data();
		List<String> out = new ArrayList<>();
		de.villagerplus.build.ConstructionSite construction = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		if (!d.hasPlan()) {
			if (construction != null && !construction.temporary().isEmpty()) {
				out.add("Baustelle: Bau beendet/abgebrochen; sicherer Abbau ausstehend.");
				out.add("  Noch verwaltet: " + scaffoldText(construction.scaffoldCount()) + ", Werkbank " + (construction.hasWorkbench() ? "ja" : "nein"));
			} else {
				out.add("Baustelle: keine – es wird nichts gebaut.");
			}
			return out;
		}
		int size = de.villagerplus.build.Builder.planSize(d, construction);
		int pct = size <= 0 ? 0 : Math.min(100, d.planStep * 100 / size);
		StringBuilder bar = new StringBuilder();
		for (int i = 0; i < 20; i++) {
			bar.append(i < pct / 5 ? '█' : '░');
		}
		out.add("Baustelle LIVE: " + planName(d.planType) + " " + bar + " " + pct + " % (" + d.planStep + "/" + size + ")");
		if (construction != null && d.planOrigin.filter(construction.origin()::equals).isPresent()) {
			int phase = construction.phaseFor(d.planStep, size);
			int phaseTotal = construction.phaseTotal(phase);
			int phaseDone = construction.phaseCompleted(d.planStep, phase);
			int phasePercent = construction.phasePercent(d.planStep, phase);
			out.add("  Bauphase " + (phase + 1) + "/5: " + de.villagerplus.build.ConstructionSite.phaseName(phase)
				+ " – " + phaseDone + "/" + phaseTotal + " Schritte (" + phasePercent + " %)");
			out.add("  Vorbereitung: " + (construction.prepared() ? "bereit" : "steht aus") + ", "
				+ scaffoldText(construction.scaffoldCount()) + ", Werkbank " + (construction.hasWorkbench() ? "aufgestellt" : "nicht aufgestellt"));
		} else {
			out.add(d.planStep == 0 ? "  Materialprüfung und Geländevorbereitung" : "  Altplan: gespeicherte Cursorfolge wird unverändert fortgesetzt");
		}
		String stepDiagnostic = de.villagerplus.build.Builder.planStepDiagnostic(d);
		if (stepDiagnostic != null) out.add("  " + stepDiagnostic);
		d.planOrigin.ifPresent(o -> out.add("  Ort: " + o.getX() + " " + o.getY() + " " + o.getZ()));
		List<VillageData.BuildProject> legacy = d.hasPlan() ? List.of(new VillageData.BuildProject(d.planType,
			d.planOrigin.orElseThrow(), d.planDir, d.planStep, d.planVariant, d.planSource, d.planProgress, d.planBuilder(),
			d.planBuilderUnavailableSince(), List.copyOf(d.wanted), VillageData.ProjectHaul.empty(), d.extra.planMaterialWait,
			construction != null && construction.origin().equals(d.planOrigin.orElseThrow()) ? java.util.Optional.of(construction) : java.util.Optional.empty(),
			java.util.Optional.empty(), false, false)) : List.of();
		if (!legacy.isEmpty()) {
			out.add("  Baustellen-Team: " + projectWorkers(level, legacy.getFirst(), residents));
			if (!d.wanted.isEmpty()) {
				List<Container> storage = Balance.storage(level, ref);
				List<MaterialNeed> needs = materialNeeds(d.wanted, storage);
				if (!needs.isEmpty()) addRequestedMaterials(out, "  Noch zu liefern", needs);
				List<MaterialNeed> procurement = needs.stream().filter(n -> n.shortage() > 0).toList();
				if (!procurement.isEmpty()) addShortageMaterials(out, "  Davon fehlen im Dorf", procurement);
			}
			for (String waitLine : constructionWaitLines(legacy.getFirst())) out.add("  " + waitLine);
		} else {
			out.add("  Bauarbeiter vor Ort: niemand");
		}
		String stall = legacyConstructionStallLine(d, level.getGameTime());
		if (stall != null) out.add("  " + stall);
		return out;
	}

	static record MaterialNeed(String id, String name, int requested, int available) {
		int shortage() { return Math.max(0, requested - available); }
	}

	static List<String> combineWanted(List<VillageData.BuildProject> projects) {
		Map<String, Integer> combined = new TreeMap<>();
		for (VillageData.BuildProject project : projects) {
			for (String row : project.wanted()) {
				String[] parts = row.split("#", 2);
				if (parts.length != 2) continue;
				try { combined.merge(parts[0], Math.max(0, Integer.parseInt(parts[1])), Integer::sum); }
				catch (NumberFormatException ignored) { }
			}
		}
		if (combined.isEmpty()) return List.of();
		return combined.entrySet().stream().map(e -> e.getKey() + "#" + e.getValue()).toList();
	}

	static List<MaterialNeed> materialNeeds(List<String> rows, List<Container> storage) {
		Map<String, Integer> merged = new TreeMap<>();
		for (String row : rows) {
			String[] parts = row.split("#", 2);
			if (parts.length != 2 || parts[0].isBlank()) continue;
			try { merged.merge(parts[0], Math.max(0, Integer.parseInt(parts[1])), Integer::sum); }
			catch (NumberFormatException ignored) { }
		}
		List<MaterialNeed> result = new ArrayList<>();
		for (var entry : merged.entrySet()) {
			int required = entry.getValue();
			if (required <= 0) continue;
			var id = net.minecraft.resources.Identifier.tryParse(entry.getKey());
			var item = id == null ? java.util.Optional.<net.minecraft.world.item.Item>empty()
				: net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id);
			String name = item.map(value -> new net.minecraft.world.item.ItemStack(value).getHoverName().getString())
				.orElseGet(() -> friendlyItemId(entry.getKey()));
			int have = item.map(value -> storage.stream().mapToInt(c -> JobUtil.count(c, s -> s.is(value))).sum()).orElse(0);
			result.add(new MaterialNeed(entry.getKey(), name, required, have));
		}
		return result;
	}

	private static String friendlyItemId(String id) {
		int colon = id.indexOf(':');
		String path = colon >= 0 ? id.substring(colon + 1) : id;
		String[] words = path.replace('_', ' ').split("\\s+");
		StringBuilder out = new StringBuilder();
		for (String word : words) {
			if (word.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.isEmpty() ? "Unbekanntes Material" : out.toString();
	}

	/** Group a few exact material counts per line so large palettes remain readable in board and chat output. */
	private static void addRequestedMaterials(List<String> out, String label, List<MaterialNeed> needs) {
		addMaterialLines(out, label, needs, need -> formatMaterialCount(need, need.requested()));
	}

	private static void addAvailableMaterials(List<String> out, String label, List<MaterialNeed> needs) {
		addMaterialLines(out, label, needs, need -> formatMaterialCount(need, need.available()));
	}

	static void addShortageMaterials(List<String> out, String label, List<MaterialNeed> needs) {
		addMaterialLines(out, label, needs.stream().filter(need -> need.shortage() > 0).toList(), VillageStats::formatMaterialShortage);
	}

	private static void addMaterialLines(List<String> out, String label, List<MaterialNeed> needs,
		java.util.function.Function<MaterialNeed, String> format) {
		if (needs == null || needs.isEmpty()) return;
		for (int start = 0; start < needs.size(); start += MATERIALS_PER_STATUS_LINE) {
			int end = Math.min(needs.size(), start + MATERIALS_PER_STATUS_LINE);
			String prefix = start == 0 ? label + ": " : "  ";
			out.add(prefix + String.join(" · ", needs.subList(start, end).stream().map(format).toList()));
		}
	}

	static String formatMaterialShortage(MaterialNeed need) {
		return need.name() + " ×" + formatCount(need.shortage()) + " (" + formatCount(need.available()) + "/"
			+ formatCount(need.requested()) + " bereit)";
	}

	private static String formatMaterialCount(MaterialNeed need, int count) {
		return need.name() + " ×" + formatCount(count);
	}

	private static String formatCount(int count) {
		return NumberFormat.getIntegerInstance(Locale.GERMANY).format(Math.max(0, count));
	}

	static String materialNextAction(List<MaterialNeed> shortages) {
		return shortages == null || shortages.stream().noneMatch(need -> need.shortage() > 0) ? null
			: "Nächster Schritt: Besorge die angezeigten fehlenden Materialien und lege sie in die öffentliche Dorfkiste.";
	}

	/** Compact, localized status summary used by the debug status command instead of serialized item IDs. */
	public static String materialSummary(ServerLevel level, VillageRef ref) {
		List<VillageData.BuildProject> projects = ref.data().buildProjects.stream().filter(VillageData.BuildProject::active).toList();
		List<String> wanted = projects.isEmpty() ? ref.data().wanted : combineWanted(projects);
		if (wanted.isEmpty() && !ref.data().wanted.isEmpty()) wanted = ref.data().wanted;
		if (wanted.isEmpty()) return "keine offenen Baumaterialien";
		List<MaterialNeed> shortages = materialNeeds(wanted, Balance.storage(level, ref)).stream()
			.filter(need -> need.shortage() > 0).toList();
		return formatMaterialSummary(shortages);
	}

	static String formatMaterialSummary(List<MaterialNeed> shortages) {
		return shortages == null || shortages.isEmpty() ? "keine offenen Baumaterialien"
			: String.join(" · ", shortages.stream().map(VillageStats::formatMaterialShortage).toList());
	}

	static String formatDuration(long seconds) {
		long safe = Math.max(0L, seconds);
		long days = safe / 86_400L;
		long hours = safe % 86_400L / 3_600L;
		long minutes = safe % 3_600L / 60L;
		long remainder = safe % 60L;
		List<String> parts = new ArrayList<>(3);
		if (days > 0) parts.add(days + " Tg.");
		if (hours > 0) parts.add(hours + " Std.");
		if (minutes > 0) parts.add(minutes + " Min.");
		if (remainder > 0 || parts.isEmpty()) parts.add(remainder + " Sek.");
		return String.join(" ", parts);
	}

	private static String planName(String type) {
		return switch (type) {
			case Blueprints.HOUSE -> "Wohnhaus";
			case Blueprints.FARM -> "Bauernhof";
			case Blueprints.WELL -> "Brunnen";
			case Blueprints.TOWNHALL -> "Rathaus";
			case Blueprints.WALL -> "Dorfmauer";
			case Blueprints.PEN -> "Tiergehege";
			case Blueprints.DECOR -> "Dorfplatz";
			case Blueprints.STORAGE -> "Lagerhaus";
			case Blueprints.SCHOOL -> "Schule";
			case Blueprints.GRANARY -> "Kornspeicher";
			case Blueprints.TOOLSHED -> "Werkzeugschuppen";
			case Blueprints.KITCHEN -> "Dorfküche";
			case Blueprints.STATION -> "Bahnhof";
			default -> friendlyItemId(type);
		};
	}

	private static String scaffoldText(int count) {
		return count + (count == 1 ? " Gerüst" : " Gerüste");
	}
}
