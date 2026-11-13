package de.villagerplus.job;

import com.mojang.logging.LogUtils;
import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Caravan;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Housing;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import de.villagerplus.village.Wellbeing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Verwaltet alle geladenen Dorfbewohner und gibt ihnen einen festen Tagesplan:
 * Morgenrunde -> Arbeit (Hauptaufgabe) -> Mittagspause -> Arbeit (Nebenaufgabe) -> Abendrunde -> Nacht (Schlaf).
 * Bei den Runden treffen sich alle an der Dorfmitte, liefern Waren in die Dorfkiste und holen sich Essen.
 */
public final class JobManager {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Ein verwalteter Dorfbewohner samt Zustand und naechstem Takt. */
	static final class Entry {
		final Villager v;
		final JobState st;
		long nextAt;
		long queueOrder;
		int workPhase;

		Entry(Villager v, JobState st) {
			this.v = v;
			this.st = st;
		}
	}

	/** Ordered index: looking for the next worker is independent of the number of non-due workers. */
	static final class DueWorkQueue {
		private final NavigableSet<Entry> entries = new TreeSet<>(Comparator
			.comparingLong((Entry e) -> e.nextAt)
			.thenComparingLong(e -> e.queueOrder));

		void add(Entry entry) {
			if (!entries.add(entry)) {
				throw new IllegalStateException("Duplicate JobManager queue order");
			}
		}

		Entry pollDue(long now) {
			Entry next = entries.isEmpty() ? null : entries.first();
			return next == null || next.nextAt > now ? null : entries.pollFirst();
		}

		boolean remove(Entry entry) {
			return entries.remove(entry);
		}

		boolean isEmpty() {
			return entries.isEmpty();
		}
	}

	/** Die Villager einer Dimension; Zu- und Abgaenge werden gesammelt und erst zu Tickbeginn eingearbeitet. */
	static final class Group {
		final Map<Villager, Entry> entries = new HashMap<>();
		final ArrayList<Entry> add = new ArrayList<>();
		final Set<Villager> remove = new HashSet<>();
		final Set<Villager> reloaded = new HashSet<>();
		final DueWorkQueue due = new DueWorkQueue();
		long nextQueueOrder;
		int phaseInterval;

		void queueLoad(Entry entry) {
			if (remove.contains(entry.v)) {
				// A reload supersedes any older pending load for this Villager, but keep the
				// removal queued so the old entry is removed before the replacement is added.
				reloaded.add(entry.v);
				add.removeIf(pending -> pending.v == entry.v);
			}
			add.add(entry);
		}

		void queueUnload(Villager villager) {
			remove.add(villager);
			reloaded.remove(villager);
		}

		void applyPendingChanges() {
			if (!remove.isEmpty()) {
				for (Villager villager : remove) {
					Entry removed = entries.remove(villager);
					if (removed != null) due.remove(removed);
				}
			}
			if (!add.isEmpty()) {
				for (Entry entry : add) {
					boolean reloadedAfterUnload = reloaded.contains(entry.v);
					if ((!remove.contains(entry.v) || reloadedAfterUnload) && !entries.containsKey(entry.v)) {
						entry.queueOrder = nextQueueOrder++;
						entries.put(entry.v, entry);
						due.add(entry);
					}
				}
				add.clear();
			}
			remove.clear();
			reloaded.clear();
		}

		void applyPendingChanges(long now, int interval) {
			List<Entry> newlyAdded = new ArrayList<>();
			if (!remove.isEmpty()) {
				for (Villager villager : remove) {
					Entry removed = entries.remove(villager);
					if (removed != null) due.remove(removed);
				}
			}
			if (!add.isEmpty()) {
				for (Entry entry : add) {
					boolean reloadedAfterUnload = reloaded.contains(entry.v);
					if ((!remove.contains(entry.v) || reloadedAfterUnload) && !entries.containsKey(entry.v)) {
						entries.put(entry.v, entry);
						newlyAdded.add(entry);
					}
				}
				add.clear();
			}
			remove.clear();
			reloaded.clear();

			if (phaseInterval != interval) {
				List<Entry> ordered = new ArrayList<>(entries.values());
				ordered.sort(Comparator.comparing((Entry entry) -> entry.v.getUUID()));
				Set<Integer> occupied = new HashSet<>();
				for (Entry entry : ordered) due.remove(entry);
				for (Entry entry : ordered) scheduleFirst(entry, now, interval, occupied);
				phaseInterval = interval;
			} else if (!newlyAdded.isEmpty()) {
				Set<Integer> occupied = new HashSet<>();
				for (Entry entry : entries.values()) {
					if (!newlyAdded.contains(entry)) occupied.add(entry.workPhase);
				}
				newlyAdded.sort(Comparator.comparing((Entry entry) -> entry.v.getUUID()));
				for (Entry entry : newlyAdded) scheduleFirst(entry, now, interval, occupied);
			}
		}

		private void scheduleFirst(Entry entry, long now, int interval, Set<Integer> occupied) {
			entry.workPhase = allocateWorkPhase(entry.v.getUUID(), interval, occupied);
			entry.nextAt = firstWorkAt(now, interval, entry.workPhase);
			entry.queueOrder = nextQueueOrder++;
			due.add(entry);
		}
	}

	private static final Map<ServerLevel, Group> GROUPS = new java.util.IdentityHashMap<>();
	private static final Map<UUID, JobState> STATES = new HashMap<>();
	private static final int MAX_WEATHER_PATH_CHECKS_PER_SEARCH = 4;
	private static final int MAX_WEATHER_PATH_CHECKS_PER_LEVEL_TICK = 32;
	private static final int WEATHER_SEARCH_RETRY_BASE_TICKS = 120;
	private static final int WEATHER_SEARCH_RETRY_JITTER_TICKS = 200;
	private record WeatherPause(Optional<BlockPos> shelter, long startedAt, long shelterCheckedAt) { }
	private record WeatherPathBudget(long tick, int used) { }
	private static final Map<UUID, WeatherPause> WEATHER_PAUSES = new HashMap<>();
	private static final Map<UUID, Integer> WEATHER_SEARCH_CURSORS = new HashMap<>();
	private static final Map<ServerLevel, WeatherPathBudget> WEATHER_PATH_BUDGETS = new java.util.WeakHashMap<>();

	private static final Job FARMER = new FarmerJob();
	private static final Job COOK = new CookJob();
	private static final Job CHARCOAL = new CharcoalJob();
	private static final Job HEALER = new HealerJob();
	private static final Job GUARD = new GuardJob();
	private static final Job SHEPHERD = new ShepherdJob();
	private static final Job BUTCHER = new ButcherJob();
	private static final Job SMITH = new SmithJob();
	private static final Job CLERIC = new ClericJob();
	private static final Job MASON = new MasonJob();
	private static final Job FISHERMAN = new FishermanJob();
	private static final Job FLETCHER = new FletcherJob();
	private static final Job LEATHERWORKER = CraftOnlyJob.leatherworker();
	private static final Job CARTOGRAPHER = CraftOnlyJob.cartographer();
	private static final Job LIBRARIAN = CraftOnlyJob.librarian();
	private static final Job PORTER = new PorterJob();
	private static final Job ROAD_BUILDER = new RoadBuilderJob();
	private static final Job LAMPLIGHTER = new LamplighterJob();

	private JobManager() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Villager v && !de.villagerplus.Compat.ignored(v)) {
				// The camp's no-AI guard is a display entity, never a worker or route candidate.
				if (v.entityTags().contains(de.villagerplus.village.CaravanCampSecurity.ARMED_GUARD_TAG)) return;
				// Gaeste aus einer frueheren Sitzung sind weitergezogen (ihr Zustand liegt nur im Speicher).
				if (Guests.onLoad(v)) {
					return;
				}
				Group g = GROUPS.computeIfAbsent(level, k -> new Group());
				Entry e = new Entry(v, state(v));
				g.queueLoad(e);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager v) {
				Group g = GROUPS.get(level);
				if (g != null) {
					g.queueUnload(v);
				}
				WEATHER_PAUSES.remove(v.getUUID());
				WEATHER_SEARCH_CURSORS.remove(v.getUUID());
				Routine.forgetGatherSearch(v);
				JobUtil.clearLongRoute(v);
				STATES.remove(v.getUUID());
				Wellbeing.forget(v.getUUID());
				Villages.forget(v.getUUID());
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(JobManager::tickLevel);
		// Villagers on CTOV hill villages are pushed or step off 5-8 block platforms; moderate falls must not kill residents.
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof Villager villager && source.is(net.minecraft.tags.DamageTypeTags.IS_FALL) && villager.fallDistance <= SAFE_VILLAGER_FALL));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WEATHER_PAUSES.clear();
			WEATHER_SEARCH_CURSORS.clear();
			WEATHER_PATH_BUDGETS.clear();
			Routine.clearGatherSearches();
			JobUtil.clearLongRoutes();
		});
	}

	/** Was macht der Dorfbewohner gerade? (fuer die Info-Anzeige) */
	public static String activityOf(Villager v, ServerLevel level) {
		Phase phase = Phase.of(level.getOverworldClockTime());
		JobState st = STATES.get(v.getUUID());
		if (v.isBaby()) {
			return st != null && !st.activity.isEmpty() ? st.activity : "spielt";
		}
		if (phase == Phase.NIGHT) {
			return phase.label;
		}
		return st == null || st.activity.isEmpty() ? phase.label : st.activity;
	}

	private static double avgMicros;
	private static double maxMicros;

	/** Messwerte fuer {@code /villagerplus perf}: gleitender Durchschnitt und Spitze der Zeit je Tick (Mikrosekunden). */
	public static String perf() {
		int total = 0;
		for (Group g : GROUPS.values()) {
			total += g.entries.size();
		}
		return String.format("Villagers: Repurposed: Ø %.0f µs je Tick (Spitze %.0f µs), %d Villager verwaltet, Budget %d µs; %s", avgMicros, maxMicros, total,
			Config.TICK_BUDGET_NANOS / 1000, JobUtil.longRoutePerf());
	}

	private static void tickLevel(ServerLevel level) {
		long t0 = System.nanoTime();
		try {
			tickLevelInner(level);
		} finally {
			double micros = (System.nanoTime() - t0) / 1000.0;
			avgMicros += (micros - avgMicros) * 0.01;
			maxMicros = Math.max(maxMicros * 0.999, micros);
		}
	}

	private static void tickLevelInner(ServerLevel level) {
		Group g = GROUPS.get(level);
		if (g == null) {
			return;
		}
		long now = level.getGameTime();
		int interval = Config.JOB_INTERVAL * loadFactor(level);
		g.applyPendingChanges(now, interval);
		if (g.due.isEmpty()) return;
		long deadline = System.nanoTime() + Config.TICK_BUDGET_NANOS;
		int processed = 0;
		while (true) {
			Entry e = g.due.pollDue(now);
			if (e == null) return;
			if (e.v.isRemoved()) {
				g.entries.remove(e.v, e);
				continue;
			}
			e.nextAt = nextWorkAt(now, interval, e.workPhase);
			e.queueOrder = g.nextQueueOrder++;
			try {
				tickVillager(level, e.v, now);
			} catch (RuntimeException ex) {
				LOGGER.warn("Villagers: Repurposed: Job von {} ist fehlgeschlagen", e.v, ex);
				e.st.cooldownUntil = now + 1200;
			}
			g.due.add(e);
			// Zeitbudget nach jedem Bewohner pruefen, damit teure Jobs nicht mehrere Nachfolger aus demselben Tick verdraengen.
			processed++;
			if (shouldYieldForBudget(processed, System.nanoTime(), deadline)) {
				return;
			}
		}
	}

	/** Stable resident phases spread each loaded group across its dispatch interval. */
	static int workPhase(UUID resident, int interval) {
		if (interval <= 0) throw new IllegalArgumentException("Job interval must be positive");
		long mixed = resident.getMostSignificantBits() ^ Long.rotateLeft(resident.getLeastSignificantBits(), 23);
		mixed ^= mixed >>> 30;
		mixed *= 0xBF58476D1CE4E5B9L;
		mixed ^= mixed >>> 27;
		mixed *= 0x94D049BB133111EBL;
		mixed ^= mixed >>> 31;
		return (int) Long.remainderUnsigned(mixed, interval);
	}

	/** Assign the resident's hash phase, probing forward to an unused tick slot when possible. */
	static int allocateWorkPhase(UUID resident, int interval, Set<Integer> occupied) {
		if (interval <= 0) throw new IllegalArgumentException("Job interval must be positive");
		int phase = workPhase(resident, interval);
		for (int offset = 0; offset < interval; offset++) {
			int candidate = (phase + offset) % interval;
			if (occupied.add(candidate)) return candidate;
		}
		// More residents than available ticks necessarily share a dispatch phase.
		return phase;
	}

	/** First scheduled dispatch may run on this tick if the allocated phase matches. */
	static long firstWorkAt(long now, int interval, UUID resident) {
		return firstWorkAt(now, interval, workPhase(resident, interval));
	}

	static long firstWorkAt(long now, int interval, int phase) {
		int currentPhase = (int) Math.floorMod(now, (long) interval);
		return now + Math.floorMod(phase - currentPhase, interval);
	}

	/** Keep the configured cadence exact while preserving the resident's allocated tick phase. */
	static long nextWorkAt(long now, int interval, UUID resident) {
		return nextWorkAt(now, interval, workPhase(resident, interval));
	}

	static long nextWorkAt(long now, int interval, int phase) {
		int delay = Math.floorMod(phase - (int) Math.floorMod(now, (long) interval), interval);
		return now + (delay == 0 ? interval : delay);
	}

	/** Bei hoher Serverlast (Tickzeit nahe/ueber dem Soll) werden die Villager seltener getaktet: 1x, 2x oder 4x. */
	private static int loadFactor(ServerLevel level) {
		var server = level.getServer();
		float perTick = server.tickRateManager().millisecondsPerTick();
		if (perTick < 49.0F) {
			return 1;
		}
		double avgMs = server.getAverageTickTimeNanos() / 1_000_000.0;
		return avgMs > perTick * 1.2 ? 4 : avgMs > perTick * 0.85 ? 2 : 1;
	}

	/** Laufzeit-Zustand eines Dorfbewohners (wird bei Bedarf angelegt). */
	public static JobState state(Villager v) {
		return STATES.computeIfAbsent(v.getUUID(), id -> new JobState());
	}

	/** Read-only lookup for development diagnostics; unlike {@link #state(Villager)}, this never creates state. */
	public static JobState stateIfKnown(Villager v) {
		return STATES.get(v.getUUID());
	}

	/** Fall height (blocks) villagers survive without damage; anything higher keeps vanilla fall damage. */
	static final double SAFE_VILLAGER_FALL = 10.0;
	private static final java.util.Map<java.util.UUID, Long> FALL_LOG = new java.util.HashMap<>();

	private static void tickVillager(ServerLevel level, Villager v, long now) {
		if (Guests.isGuest(v) || de.villagerplus.village.Expedition.isAway(v)
			|| de.villagerplus.village.RailTransport.isPassenger(v)) {
			Caravan.releasePorter(v);
			return;
		}
		VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
		VillageRef village = Villages.find(level, v);
		if (village != null) de.villagerplus.village.VillageAdmin.maintain(level, village, now);
		de.villagerplus.build.FbTrace.sample(level, v, mem, state(v).activity == null ? "" : state(v).activity, v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"), village, now);
		if (v.fallDistance > 3.5 && !v.onGround() && FALL_LOG.getOrDefault(v.getUUID(), -1000L) + 200L < now) {
			// Dev aid: where and while doing what do residents fall from heights?
			FALL_LOG.put(v.getUUID(), now);
			var walk = v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			LOGGER.info("Sturz {}: fallDistance={} pos={} Aktivitaet='{}' Ziel={} Navigation={}", mem.name, (int) v.fallDistance,
				v.blockPosition().toShortString(), state(v).activity, walk.map(w -> w.getTarget().currentBlockPosition().toShortString()).orElse("-"),
				v.getNavigation().isDone() ? "fertig" : "aktiv");
		}
		if (village != null) de.villagerplus.village.Anchor.keepVillagerActive(level, v);
		if (de.villagerplus.village.Staffing.restoreTemporary(level, v, mem, now)) {
			JobState restored = state(v);
			restored.idleSince = 0L;
			restored.clearTarget();
			restored.cooldownUntil = now + 40L;
			restored.activity = "kehrt zur früheren Arbeit zurück";
		}

		// Name, festes Haus, private Kiste, Schild und Dorfmitte pflegen (langsamer Takt).
		JobState scheduled = state(v);
		if (now >= scheduled.nextHousing) {
			scheduled.nextHousing = now + Config.HOUSING_INTERVAL;
			Housing.update(level, v, mem);
			if (village != null) {
				de.villagerplus.village.Anchor.keepLoaded(level, village, now);
				de.villagerplus.village.Expedition.tick(level, village, now);
				de.villagerplus.village.Staffing.tick(level, village, now);
				Villages.ensureCenter(level, village, v, now);
				Caravan.maybeSpawn(level, village, v, now);
				Guests.maybeSpawn(level, village, v, now);
			}
		}

		if (v.isSleeping()) {
			mem.lastSleep = now;
			// Im Schlaf erholt sich der Dorfbewohner langsam (wichtig nach Expeditionen).
			if (v.getHealth() < v.getMaxHealth()) {
				v.heal(0.25F);
			}
		}
		if (v.isBaby()) {
			School.slowGrowth(v);
			Phase kidPhase = Phase.of(level.getOverworldClockTime());
			if (!v.isSleeping() && kidPhase != Phase.NIGHT && !busy(v)) {
				JobState ks = state(v);
				if (now >= ks.cooldownUntil || School.isSchoolActivity(ks.activity)) {
					if (School.attend(new Ctx(level, v, ks, mem, village, now), kidPhase)) {
						Anim.apply(v, ks, now);
						return;
					}
					ks.activity = "";
				}
			}
			Anim.apply(v, state(v), now);
			Caravan.releasePorter(v);
			return;
		}
		Apprentices.completeIfAdult(level, v);
		if (v.isSleeping() || v.isTrading() || busy(v)) {
			Caravan.releasePorter(v);
			Anim.apply(v, state(v), now);
			return;
		}
		Phase phase = Phase.of(level.getOverworldClockTime());
		if (phase == Phase.NIGHT) {
			Caravan.releasePorter(v);
			return;
		}
		Job job = jobFor(v);
		JobState st = state(v);
		Ctx ctx = new Ctx(level, v, st, mem, village, now);
		de.villagerplus.dev.G02SupplyTrace.jobSelected(level, v, village, job, phase, st, now);
		boolean badWeather = adverseWeather(level.isRaining(), level.isThundering());
		if (shouldShelterForWeather(level, phase, job, v.blockPosition())) {
			pauseForWeather(ctx);
			return;
		}
		resumeAfterWeather(ctx);
		if (job != PORTER) Caravan.releasePorter(v);
		if (now < st.cooldownUntil) {
			Caravan.releasePorter(v);
			Anim.apply(v, st, now);
			return;
		}
		if (Mining.insideMine(ctx)) {
			Caravan.releasePorter(v);
			Mining.exitStep(ctx);
			return;
		}
		if (!Caravan.carryingFor(v) && rescueStranded(ctx)) {
			Caravan.releasePorter(v);
			return;
		}

		if (phase == Phase.EVENING && village != null && de.villagerplus.village.Festival.isOn(level, village) && de.villagerplus.village.Festival.attend(ctx)) {
			Caravan.releasePorter(v);
			Anim.apply(v, st, now);
			return;
		}
		if (phase.routine) Caravan.releasePorter(v);
		if (phase == Phase.LUNCH && de.villagerplus.village.Market.open(level) && de.villagerplus.village.Market.work(ctx)) { Anim.apply(v, st, now); return; }
		if (phase.routine && phase != Phase.MORNING && de.villagerplus.village.Market.visit(ctx)) { Anim.apply(v, st, now); return; }
		if (phase.routine) {
			Caravan.releasePorter(v);
			if (!Routine.run(ctx, job, phase)) {
				// Routine fertig: ruhig stehen bleiben, bis die naechste Phase beginnt.
				if (st.activity.isEmpty()) {
					ctx.setActivity(phase.label);
				}
			}
			Anim.apply(v, st, now);
			return;
		}

		st.routinePhase = null;
		if (School.teach(ctx, phase)) {
			Caravan.releasePorter(v);
			Anim.apply(v, st, now);
			return;
		}
		// Befinden wirkt auf die Arbeit: schlechte Stimmung = nur ein Teil der Zeit arbeiten; grosser Hunger = Happen essen.
		Wellbeing.Status status = Wellbeing.of(level, v, mem);
		if (status.satiety() < 25) {
			Meals.eat(ctx, null);
		}
		long preparedAt = v.getAttachedOrElse(VillagerPlus.PREPARED_MEAL, 0L);
		boolean preparedMeal = preparedAt != 0 && now >= preparedAt && now - preparedAt < 6000L;
		if (!workAttemptAllowed(st, Wellbeing.workQuarters(status, preparedMeal))) {
			de.villagerplus.dev.G02SupplyTrace.stage(ctx, "work-schedule", "skipped by wellbeing quarter gate; job="
				+ job.getClass().getSimpleName() + "; cooldown=" + st.cooldownUntil);
			ctx.setActivity(status.satiety() < 25 ? "ist hungrig und arbeitet langsam" : "ist " + status.moodLabel().toLowerCase() + " und arbeitet langsam");
			Anim.apply(v, st, now);
			return;
		}
		de.villagerplus.dev.G02SupplyTrace.stage(ctx, "work-schedule", "allowed; job="
			+ job.getClass().getSimpleName() + "; cooldown=" + st.cooldownUntil);
		if (phase == Phase.WORK_A && village != null && de.villagerplus.village.VillageAdmin.govern(ctx)) {
			Anim.apply(v, st, now);
			return;
		}
		boolean first = phase == Phase.WORK_A;
		long cooldownBefore = st.cooldownUntil;
		boolean carryingCargo = job == PORTER && Caravan.carryingFor(v) && Caravan.porterDelivery(ctx);
		// Shared market errands and construction can target outdoor sites even for a normally indoor role.
		boolean sharedOutdoorWork = allowOutdoorSupportWork(badWeather) && (((job == MASON || job == PORTER)
			&& de.villagerplus.build.Builder.buildHelperWork(ctx))
			|| de.villagerplus.village.Market.work(ctx)
			|| de.villagerplus.build.Builder.bootstrapBuildWork(ctx));
		boolean busy = carryingCargo || sharedOutdoorWork;
		if (!busy) {
			// Preserve this phase's first-choice profession work, then let an assigned resident join a
			// prepared building before falling through to the other profession phase.
			boolean priorityJobWorked = first ? job.primary(ctx) : job.secondary(ctx);
			busy = priorityJobWorked;
			if (job != MASON && job != PORTER
				&& de.villagerplus.build.Builder.shouldOfferConstructionSupport(priorityJobWorked,
					allowOutdoorSupportWork(badWeather),
					de.villagerplus.build.Builder.hasPreparedCrewProject(ctx.village() == null ? null : ctx.village().data()))) {
				busy = de.villagerplus.build.Builder.buildHelperWork(ctx);
			}
			if (!busy) busy = first ? job.secondary(ctx) : job.primary(ctx);
		}
		if (badWeather && isWorkPeriod(phase) && job.indoorsDuringStorm() && hasExposedWeatherTarget(level, v)) {
			// Mixed indoor roles may fetch or assist outside. Let them work only while covered and
			// cancel any outdoor destination produced by this tick before it becomes an outside task.
			pauseForWeather(ctx);
			return;
		}
		// Fertigkeit: Pausen zwischen den Handgriffen werden mit der Stufe kuerzer.
		if (st.cooldownUntil > cooldownBefore && st.cooldownUntil > now) {
			st.cooldownUntil = now + (long) ((st.cooldownUntil - now) / Skills.speed(mem));
		}
		announceLevelUp(ctx);
		if (!busy) {
			ctx.setActivity("hat gerade nichts zu tun");
			if (st.idleSince == 0) {
				st.idleSince = now;
			} else if (now - st.idleSince > 4000 && village != null) {
				// Job-Timeout: kann der Beruf lange nicht ausgeuebt werden, uebernimmt er den am dringendsten benoetigten Beruf.
				st.idleSince = 0;
				de.villagerplus.village.Staffing.reassignIdle(level, village, v);
			}
		} else {
			st.idleSince = 0;
		}
		payWage(ctx);
		Anim.apply(v, st, now);
	}

	/**
	 * Wellbeing limits how many of four work attempts are allowed. Advance on each real
	 * scheduled attempt so a fixed scheduler cadence cannot pin a villager to one quarter.
	 */
	static boolean workAttemptAllowed(JobState state, int workQuarters) {
		int quarter = Math.floorMod(state.workQuarterCursor, 4);
		state.workQuarterCursor = (quarter + 1) & 3;
		return quarter < workQuarters;
	}

	static boolean shouldYieldForBudget(int processed, long nowNanos, long deadlineNanos) {
		return processed > 0 && nowNanos > deadlineNanos;
	}

	static boolean adverseWeather(boolean raining, boolean thundering) {
		return raining || thundering;
	}

	/** Shared construction/market dispatch always targets exterior work sites, including for indoor professions. */
	static boolean allowOutdoorSupportWork(boolean badWeather) {
		return !badWeather;
	}

	static boolean shouldShelterForWeather(boolean badWeather, Phase phase, Job job) {
		if (!badWeather) return false;
		return !isWorkPeriod(phase) || !job.indoorsDuringStorm();
	}

	/** Single production gate so tests and scheduled work consume the same live level weather state. */
	static boolean shouldShelterForWeather(ServerLevel level, Phase phase, Job job) {
		return shouldShelterForWeather(adverseWeather(level.isRaining(), level.isThundering()), phase, job);
	}

	static boolean shouldShelterForWeather(ServerLevel level, Phase phase, Job job, BlockPos workerStand) {
		if (!adverseWeather(level.isRaining(), level.isThundering())) return false;
		if (!isWorkPeriod(phase) || !job.indoorsDuringStorm()) return true;
		return !isShelteredStand(level, workerStand);
	}

	private static boolean isWorkPeriod(Phase phase) {
		return phase == Phase.WORK_A || phase == Phase.WORK_B;
	}

	/** A role may continue indoor work only when any destination it selects is also under cover. */
	static boolean hasExposedWeatherTarget(ServerLevel level, Villager villager) {
		var target = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
		if (target.isEmpty()) return false;
		BlockPos feet = target.get().getTarget().currentBlockPosition();
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (isShelteredStand(level, feet.offset(dx, dy, dz))) return false;
		}
		return true;
	}

	static long weatherSearchRetryDelay(UUID villager) {
		return WEATHER_SEARCH_RETRY_BASE_TICKS
			+ Math.floorMod(villager.hashCode(), WEATHER_SEARCH_RETRY_JITTER_TICKS);
	}

	static int reserveWeatherPathBudget(ServerLevel level, long tick, int requested) {
		if (requested <= 0) return 0;
		WeatherPathBudget budget = WEATHER_PATH_BUDGETS.get(level);
		int used = budget != null && budget.tick() == tick ? budget.used() : 0;
		int granted = Math.min(requested, MAX_WEATHER_PATH_CHECKS_PER_LEVEL_TICK - used);
		if (granted <= 0) return 0;
		WEATHER_PATH_BUDGETS.put(level, new WeatherPathBudget(tick, used + granted));
		return granted;
	}

	static int weatherCandidateIndex(int offset, int step, int candidateCount) {
		return candidateCount <= 0 ? 0 : Math.floorMod(offset + step, candidateCount);
	}

	static int advanceWeatherCandidateCursor(int offset, int examined, int candidateCount) {
		return candidateCount <= 0 ? 0 : Math.floorMod(offset + examined, candidateCount);
	}

	/** Pause outdoor work without running routine storage/delivery code or changing its task target. */
	static void pauseForWeather(Ctx c) {
		Villager villager = c.v();
		UUID id = villager.getUUID();
		WeatherPause pause = WEATHER_PAUSES.get(id);
		if (pause == null) {
			pause = new WeatherPause(Optional.ofNullable(findWeatherShelter(c)), c.now(), c.now());
			WEATHER_PAUSES.put(id, pause);
		}
		BlockPos shelter = pause.shelter().orElse(null);
		if (shelter != null && !isShelteredStand(c.level(), shelter)) {
			pause = new WeatherPause(Optional.ofNullable(findWeatherShelter(c)), pause.startedAt(), c.now());
			WEATHER_PAUSES.put(id, pause);
			shelter = pause.shelter().orElse(null);
		} else if (shelter != null && !villager.blockPosition().equals(shelter)
			&& c.now() - pause.shelterCheckedAt() >= weatherSearchRetryDelay(id)) {
			// A shelter can remain roofed after a door closes or terrain changes. Recheck the route
			// occasionally and choose another covered position if the current target is no longer reachable.
			Boolean reachable = weatherPathReachableWithinBudget(c, shelter);
			if (reachable == null) {
				pause = new WeatherPause(pause.shelter(), pause.startedAt(), c.now());
			} else if (!reachable) {
				pause = new WeatherPause(Optional.ofNullable(findWeatherShelter(c)), pause.startedAt(), c.now());
			} else {
				pause = new WeatherPause(pause.shelter(), pause.startedAt(), c.now());
			}
			WEATHER_PAUSES.put(id, pause);
			shelter = pause.shelter().orElse(null);
		} else if (shelter == null && c.now() - pause.shelterCheckedAt() >= weatherSearchRetryDelay(id)) {
			pause = new WeatherPause(Optional.ofNullable(findWeatherShelter(c)), pause.startedAt(), c.now());
			WEATHER_PAUSES.put(id, pause);
			shelter = pause.shelter().orElse(null);
		}

		if (shelter != null && !villager.blockPosition().equals(shelter)) {
			var currentWalk = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
			boolean alreadyHeadingToShelter = currentWalk.isPresent()
				&& currentWalk.get().getTarget().currentBlockPosition().equals(shelter);
			if (!alreadyHeadingToShelter) {
				villager.getNavigation().stop();
				villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
					new WalkTarget(Vec3.atBottomCenterOf(shelter), 0.45F, 0));
			}
			c.setActivity("sucht Schutz vor dem Unwetter");
		} else if (shelter != null) {
			villager.getNavigation().stop();
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			c.setActivity("wartet geschützt auf das Ende des Unwetters");
		} else {
			villager.getNavigation().stop();
			villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			c.setActivity("wartet wegen des Unwetters auf einen sicheren Arbeitsort");
		}
		Anim.cancelBreaking(villager);
		c.st().toolUntil = c.now();
		Anim.apply(villager, c.st(), c.now());
	}

	/** Resume the existing task and exclude the shelter trip from its movement/idle timeouts. */
	static void resumeAfterWeather(Ctx c) {
		WeatherPause pause = WEATHER_PAUSES.remove(c.v().getUUID());
		WEATHER_SEARCH_CURSORS.remove(c.v().getUUID());
		if (pause == null) return;
		long pausedFor = Math.max(0L, c.now() - pause.startedAt());
		JobState st = c.st();
		if (st.walkStart != 0L) st.walkStart += pausedFor;
		if (st.lastMoveAt != 0L) st.lastMoveAt += pausedFor;
		if (st.idleSince != 0L) st.idleSince += pausedFor;
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		c.setActivity("kehrt nach dem Unwetter zur Arbeit zurück");
	}

	static BlockPos findWeatherShelter(Ctx c) {
		ServerLevel level = c.level();
		UUID villagerId = c.v().getUUID();
		BlockPos current = c.v().blockPosition();
		if (isShelteredStand(level, current)) {
			WEATHER_SEARCH_CURSORS.remove(villagerId);
			return current.immutable();
		}

		List<BlockPos> anchors = new ArrayList<>();
		c.mem().homeBed.ifPresent(anchors::add);
		c.mem().homeChest.ifPresent(anchors::add);
		if (c.village() != null) anchors.add(c.village().bell());
		anchors.add(current);

		List<BlockPos> candidates = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		for (BlockPos anchor : anchors) {
			if (!level.hasChunkAt(anchor)) continue;
			for (int dx = -6; dx <= 6; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					for (int dz = -6; dz <= 6; dz++) {
						BlockPos feet = anchor.offset(dx, dy, dz);
						if (!seen.add(feet) || !isShelteredStand(level, feet)) continue;
						candidates.add(feet.immutable());
					}
				}
			}
		}
		candidates.sort(java.util.Comparator.comparingDouble((BlockPos feet) ->
			c.v().position().distanceToSqr(Vec3.atBottomCenterOf(feet))));
		if (candidates.isEmpty()) {
			WEATHER_SEARCH_CURSORS.remove(villagerId);
			return null;
		}
		int cursor = Math.floorMod(WEATHER_SEARCH_CURSORS.getOrDefault(villagerId, 0), candidates.size());
		int allowance = reserveWeatherPathBudget(level, c.now(), Math.min(MAX_WEATHER_PATH_CHECKS_PER_SEARCH, candidates.size()));
		if (allowance == 0) return null;
		for (int step = 0; step < allowance; step++) {
			BlockPos candidate = candidates.get(weatherCandidateIndex(cursor, step, candidates.size()));
			if (weatherPathReachableUnchecked(c, candidate)) {
				WEATHER_SEARCH_CURSORS.remove(villagerId);
				return candidate;
			}
		}
		WEATHER_SEARCH_CURSORS.put(villagerId, advanceWeatherCandidateCursor(cursor, allowance, candidates.size()));
		return null;
	}

	static boolean weatherPathReachable(Ctx c, BlockPos shelter) {
		return Boolean.TRUE.equals(weatherPathReachableWithinBudget(c, shelter));
	}

	private static Boolean weatherPathReachableWithinBudget(Ctx c, BlockPos shelter) {
		if (reserveWeatherPathBudget(c.level(), c.now(), 1) == 0) return null;
		return weatherPathReachableUnchecked(c, shelter);
	}

	private static boolean weatherPathReachableUnchecked(Ctx c, BlockPos shelter) {
		var path = de.villagerplus.build.Builder.workPath(c, shelter);
		if (path == null || !path.canReach() || path.getNodeCount() == 0) return false;
		if (!path.getNodePos(path.getNodeCount() - 1).equals(shelter)) return false;
		Villager villager = c.v();
		ServerLevel level = c.level();
		AABB body = villager.getBoundingBox();
		for (int i = 0; i < path.getNodeCount(); i++) {
			BlockPos feet = path.getNodePos(i);
			BlockPos floor = feet.below();
			BlockPos head = feet.above();
			if (!level.hasChunkAt(feet) || !level.hasChunkAt(floor) || !level.hasChunkAt(head)) return false;
			if (!level.getFluidState(floor).isEmpty() || !level.getFluidState(feet).isEmpty()
				|| !level.getFluidState(head).isEmpty()) return false;
			var ground = level.getBlockState(floor);
			if (!ground.isFaceSturdy(level, floor, Direction.UP) && !ground.is(Blocks.DIRT_PATH)) return false;

			// Check the villager-sized box at each node, including collision shapes intruding from adjacent blocks.
			AABB atNode = body.move(feet.getX() + 0.5 - villager.getX(), feet.getY() - villager.getY(),
				feet.getZ() + 0.5 - villager.getZ());
			if (!level.noBlockCollision(villager, atNode)) return false;
		}
		return true;
	}

	/** Check that an individual pause stand has a complete, collision-free vanilla route. */
	static boolean safeReachableStand(Ctx c, BlockPos destination) {
		if (c == null || destination == null || !c.level().hasChunkAt(destination)
			|| !c.level().hasChunkAt(destination.below()) || !c.level().hasChunkAt(destination.above())) return false;
		var path = de.villagerplus.build.Builder.workPath(c, destination);
		if (path == null || !path.canReach() || path.getNodeCount() == 0
			|| !path.getNodePos(path.getNodeCount() - 1).equals(destination)) return false;
		var villager = c.v();
		ServerLevel level = c.level();
		AABB body = villager.getBoundingBox();
		for (int i = 0; i < path.getNodeCount(); i++) {
			BlockPos feet = path.getNodePos(i);
			BlockPos floor = feet.below();
			BlockPos head = feet.above();
			if (!level.hasChunkAt(feet) || !level.hasChunkAt(floor) || !level.hasChunkAt(head)) return false;
			if (!level.getFluidState(floor).isEmpty() || !level.getFluidState(feet).isEmpty()
				|| !level.getFluidState(head).isEmpty()) return false;
			var ground = level.getBlockState(floor);
			if (!ground.isFaceSturdy(level, floor, Direction.UP) && !ground.is(Blocks.DIRT_PATH)) return false;
			AABB atNode = body.move(feet.getX() + 0.5 - villager.getX(), feet.getY() - villager.getY(),
				feet.getZ() + 0.5 - villager.getZ());
			if (!level.noBlockCollision(villager, atNode)) return false;
		}
		return true;
	}

	static boolean isShelteredStand(ServerLevel level, BlockPos feet) {
		BlockPos head = feet.above();
		BlockPos roof = feet.above(2);
		if (feet.getY() <= level.getMinY() || roof.getY() >= level.getMaxY() || !level.hasChunkAt(roof)) return false;
		if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			|| !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
			|| !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) return false;
		// A real collision ceiling is enough to protect the standing cell; sky-light queries can be stale
		// immediately after a roof is placed and are not a reliable shelter test during construction.
		return !level.getBlockState(roof).getCollisionShape(level, roof).isEmpty();
	}

	/** Drive the normal villager job logic from a deterministic development walk. */
	public static void tickForDevelopment(ServerLevel level, Villager villager) {
		tickVillager(level, villager, level.getGameTime());
	}

	/** Vertical margin below the lowest village anchor (bed, bell, plaza) that counts as "deep". */
	static final int DEEP_MARGIN = 8;
	/**
	 * A resident that is deep (see {@link #DEEP_MARGIN}) is only stranded after staying deep this long without a break. Villages on slopes
	 * have terraces, gullies and shores more than eight blocks below the bell or a hill-top bed; residents stroll, chat and take their breaks
	 * there and walk back up on their own (measured in natural runs: 10 to 2180 ticks). A rescue on first sight teleported them to the
	 * village chest in the middle of the walk home, from where the same stroll or break stand led them straight back down.
	 */
	static final long DEEP_STRAND_GRACE_TICKS = 3_600L;
	/** A gap of at least this many ticks without a deep observation (night, sleep, indoors) restarts the deep timer. */
	static final long DEEP_STRAND_GAP_TICKS = 600L;
	private record DeepWatch(long since, long lastSeen) { }
	private static final Map<UUID, DeepWatch> DEEP_WATCH = new HashMap<>();

	/** True once {@code id} has been observed deep, with gaps of less than {@link #DEEP_STRAND_GAP_TICKS}, for {@link #DEEP_STRAND_GRACE_TICKS}. */
	static boolean deepStrandConfirmed(UUID id, long now) {
		if (DEEP_WATCH.size() > 512) DEEP_WATCH.clear();
		DeepWatch watch = DEEP_WATCH.get(id);
		if (watch == null || now - watch.lastSeen() >= DEEP_STRAND_GAP_TICKS || now < watch.since()) {
			DEEP_WATCH.put(id, new DeepWatch(now, now));
			return false;
		}
		DEEP_WATCH.put(id, new DeepWatch(watch.since(), now));
		return now - watch.since() >= DEEP_STRAND_GRACE_TICKS;
	}

	/** A fall of more than 3.5 blocks into deep terrain (vanilla paths never drop that far) leaves no way back up: no grace period. */
	static final long DEEP_FALL_WINDOW_TICKS = 600L;

	static void noteFall(UUID id, long now) {
		FALL_LOG.put(id, now);
	}

	static boolean fellRecently(UUID id, long now) {
		Long at = FALL_LOG.get(id);
		return at != null && now >= at && now - at <= DEEP_FALL_WINDOW_TICKS;
	}

	static void clearDeepWatch(UUID id) {
		DEEP_WATCH.remove(id);
	}

	static void clearDeepWatches() {
		DEEP_WATCH.clear();
		FALL_LOG.clear();
		WALK_HOME.clear();
	}

	private record WalkHome(long checkedAt, BlockPos at, boolean ok) { }
	private static final Map<UUID, WalkHome> WALK_HOME = new HashMap<>();
	private static final long WALK_HOME_RECHECK_TICKS = 400L;

	/** Bounded walk-back search from the resident to the village street, cached per resident (the search is the expensive part). */
	private static boolean walksHomeCached(Ctx c, VillageRef ref) {
		if (WALK_HOME.size() > 512) WALK_HOME.clear();
		BlockPos at = c.v().blockPosition();
		WalkHome cached = WALK_HOME.get(c.v().getUUID());
		if (cached != null && c.now() >= cached.checkedAt() && c.now() - cached.checkedAt() < WALK_HOME_RECHECK_TICKS && cached.at().distSqr(at) <= 16.0) {
			return cached.ok();
		}
		BlockPos arrival = de.villagerplus.build.Builder.villageReturn(c.level(), ref.bell());
		boolean ok = arrival != null && de.villagerplus.village.Sites.walksBackTo(c.level(), at, arrival);
		WALK_HOME.put(c.v().getUUID(), new WalkHome(c.now(), at.immutable(), ok));
		return ok;
	}

	/** Ticks {@code id} has been continuously deep (0 when not tracked). */
	static long deepFor(UUID id, long now) {
		DeepWatch watch = DEEP_WATCH.get(id);
		return watch == null ? 0L : Math.max(0L, now - watch.since());
	}

	/**
	 * Gestrandete Dorfbewohner (weit vom Dorf weg oder dauerhaft tief unter der Dorfmitte, z. B. in einer Hoehle gefallen) werden zur
	 * Dorfkiste zurueckgeholt. Bergleute bei der Arbeit und Expeditionsmitglieder sind ausgenommen.
	 */
	static boolean rescueStranded(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || c.now() < c.st().miningUntil + 400L) {
			return false;
		}
		net.minecraft.core.BlockPos home = c.mem().homeBed.orElse(ref.bell());
		double distance=Double.MAX_VALUE;
		int lowest=Math.min(home.getY(), de.villagerplus.build.Builder.villageGroundY(c.level(), ref.bell()));
		java.util.List<net.minecraft.core.BlockPos> anchors=new java.util.ArrayList<>(java.util.List.of(home,ref.bell()));
		de.villagerplus.village.Market.of(ref).plaza().ifPresent(anchors::add);
		for(var anchor:anchors) {
			double dx=c.v().getX()-anchor.getX(),dz=c.v().getZ()-anchor.getZ();
			distance=Math.min(distance,dx*dx+dz*dz);lowest=Math.min(lowest,anchor.getY());
		}
		// A woodcutter walking to a plan tree beyond the village radius is on a legitimate (bounded) expedition.
		double farLimit = Woodcutting.onWoodExpedition(c) ? Woodcutting.EXPEDITION_LIMIT : Config.VILLAGE_RADIUS;
		boolean far=distance>farLimit*farLimit;
		boolean expedition = Woodcutting.onWoodExpedition(c);
		boolean deep = c.v().getY()<(expedition ? Math.min(lowest, Woodcutting.expeditionTarget(c).getY()) - 12 : lowest-DEEP_MARGIN);

		// Fischer am (tiefer liegenden) Ufer sind nicht gestrandet.
		if (deep && !far && c.st().target != null && c.level().getFluidState(c.st().target).is(net.minecraft.tags.FluidTags.WATER)
			&& c.v().getY() >= c.st().target.getY() - 3 && c.st().target.getY() >= lowest - 14) {
			deep = false;
		}
		// Deep terrain inside the village radius is a rescue case only when the resident stays there (see DEEP_STRAND_GRACE_TICKS), and
		// never while it works at a task target down there (field, shore, stand on a lower terrace) and can walk back up.
		if (!deep) {
			clearDeepWatch(c.v().getUUID());
		} else if (!far && !expedition) {
			net.minecraft.core.BlockPos goal = c.st().target;
			boolean lowTask = goal != null && c.v().getY() >= goal.getY() - 3 && goal.getY() >= lowest - 14;
			if (lowTask && walksHomeCached(c, ref)) {
				clearDeepWatch(c.v().getUUID());
				deep = false;
			} else {
				deep = deepStrandConfirmed(c.v().getUUID(), c.now()) | fellRecently(c.v().getUUID(), c.now());
			}
		}
		net.minecraft.core.BlockPos arrival = null;
		// Route-less strand: repeated unreachable long routes (<= 4 nodes) from one spot. The bounded walk-back search on the
		// real blocks decides; a worker that can walk home is on a legitimate task (or merely has an unreachable goal).
		boolean routeStuck = false;
		if (!far && !deep && !expedition && JobUtil.routeStrandSuspected(c.v().getUUID(), c.v().blockPosition(), c.now())) {
			arrival = de.villagerplus.build.Builder.villageReturn(c.level(), ref.bell());
			boolean modelWalksHome = arrival != null && de.villagerplus.village.Sites.walksBackTo(c.level(), c.v().blockPosition(), arrival);
			// The block model is only a model: a resident perched on a fence post (collision height 1.5) "walks home" in it, while vanilla
			// navigation finds a single node and never gets off. Real navigation that cannot leave the spot (at most a few nodes, no
			// complete path) outweighs the model.
			boolean navigationTrapped = false;
			if (modelWalksHome) {
				var homeRoute = de.villagerplus.build.Builder.workPath(c, arrival);
				navigationTrapped = homeRoute == null || (!homeRoute.canReach() && homeRoute.getNodeCount() <= JobUtil.STRAND_MAX_ROUTE_NODES);
			}
			if (arrival == null || (modelWalksHome && !navigationTrapped)) {
				JobUtil.clearRouteFailures(c.v().getUUID());
			} else {
				routeStuck = true;
			}
		}
		if (!far && !deep && !routeStuck) {
			return false;
		}
		if (arrival == null) arrival = de.villagerplus.build.Builder.villageReturn(c.level(), ref.bell());
		if (arrival == null) return false;
		net.minecraft.core.BlockPos rescuedFrom = c.v().blockPosition();
		long deepTicks = deepFor(c.v().getUUID(), c.now());
		clearDeepWatch(c.v().getUUID());
		c.v().getNavigation().stop();
		c.v().teleportTo(arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5);
		c.st().builderRelocations++;
		c.v().resetFallDistance();
		c.st().cooldownUntil = c.now() + 40;
		JobUtil.markRescued(c.v().getUUID(), c.now());
		LOGGER.info("Villagers: Repurposed: {} war gestrandet und wurde zur Dorfkiste zurueckgeholt (far={}, deep={}, route={}, Expedition={}, Ziel={}, Position={}, Abstand={}, tief seit={} Ticks, Aktivitaet='{}')", c.mem().name, far, deep, routeStuck, expedition, c.st().target == null ? "-" : c.st().target.toShortString(), rescuedFrom.toShortString(), (int) Math.sqrt(distance), deepTicks, c.st().activity);
		return true;
	}

	private static void announceLevelUp(Ctx c) {
		int level = Skills.level(c.mem());
		JobState st = c.st();
		if (st.lastSkill >= 0 && level > st.lastSkill) {
			String name = c.mem().name.isEmpty() ? "Ein Dorfbewohner" : c.mem().name;
			c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, c.v().getX(), c.v().getY() + 2.0, c.v().getZ(), 12, 0.4, 0.4, 0.4, 0.0);
			c.level().playSound(null, c.v().blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_YES, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.2F);
			JobUtil.tellNearbyPlayers(c.level(), c.v(), 24, net.minecraft.network.chat.Component.literal(name + " ist jetzt " + Skills.title(level) + " (Stufe " + level + ")!"));
		}
		st.lastSkill = level;
	}

	/**
	 * Lohn: alle {@code JOBS_PER_WAGE} erledigten Aufgaben ein Smaragd - aber aus der Dorfkasse (Dorfkiste), nicht aus dem
	 * Nichts. Ist keiner da, entfaellt der Lohn. So bleiben Smaragde knapp wie in Vanilla.
	 */
	private static void payWage(Ctx c) {
		int due = c.mem().jobsDone / Config.JOBS_PER_WAGE;
		if (due > c.mem().wages) {
			net.minecraft.world.Container box = c.village() == null ? null : c.village().chest(c.level());
			int paid = box == null ? 0 : JobUtil.take(box, s -> s.is(Items.EMERALD), due - c.mem().wages);
			if (paid > 0) {
				JobUtil.give(c.level(), c.v(), new ItemStack(Items.EMERALD, paid));
			}
			c.mem().wages = due;
		}
	}

	private static boolean busy(Villager v) {
		var brain = v.getBrain();
		return brain.isActive(Activity.PANIC) || brain.isActive(Activity.RAID) || brain.isActive(Activity.PRE_RAID)
			|| brain.isActive(Activity.HIDE) || brain.isActive(Activity.FIGHT);
	}
	/** True when the resident's resolved village job is the stone trade (mason / miner). */
	public static boolean isMason(Villager v) {
		return jobFor(v) == MASON;
	}

	private static Job jobFor(Villager v) {
		var prof = v.getVillagerData().profession();
		// Modded culinary professions only. Do not turn Vanilla farmers or butchers into cooks.
		boolean culinaryTag = prof.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/cook")))
			|| prof.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/chef")));
		String professionId = prof.unwrapKey().map(k -> k.identifier().toString().toLowerCase(java.util.Locale.ROOT)).orElse("");
		if (culinaryTag || (!professionId.startsWith("minecraft:") && (professionId.contains("chef") || professionId.contains("cook")))) {
			return COOK;
		}
		boolean charcoalTag = hasJobTag(prof, "charcoal");
		if(hasJobTag(prof,"road_builder")||hasJobTag(prof,"civil_engineer")||hasJobTag(prof,"rail_builder")
			||(!professionId.startsWith("minecraft:")&&(professionId.contains("road_builder")||professionId.contains("civil_engineer")||professionId.contains("rail_builder")))) return ROAD_BUILDER;
		if (charcoalTag || (!professionId.startsWith("minecraft:") && (professionId.contains("charcoal") || professionId.contains("coal_burner") || professionId.endsWith(":burner")))) return CHARCOAL;
		boolean healerTag = hasJobTag(prof, "healer") || hasJobTag(prof, "medic");
		if (healerTag || (!professionId.startsWith("minecraft:") && (professionId.contains("healer") || professionId.contains("medic")))) return HEALER;
		boolean lamplighterTag = hasJobTag(prof, "lamplighter");
		if (lamplighterTag || (!professionId.startsWith("minecraft:") && professionId.contains("lamplight"))) return LAMPLIGHTER;
		boolean guardTag = hasJobTag(prof, "guard") || hasJobTag(prof, "lookout") || hasJobTag(prof, "adventurer");
		if (guardTag || (!professionId.startsWith("minecraft:") && (professionId.contains("guard") || professionId.contains("lookout") || professionId.contains("adventurer")))) return GUARD;
		if (prof.is(VillagerProfession.FARMER)) {
			return FARMER;
		} else if (prof.is(VillagerProfession.SHEPHERD)) {
			return SHEPHERD;
		} else if (prof.is(VillagerProfession.BUTCHER)) {
			return BUTCHER;
		} else if (prof.is(VillagerProfession.ARMORER) || prof.is(VillagerProfession.WEAPONSMITH) || prof.is(VillagerProfession.TOOLSMITH)) {
			return SMITH;
		} else if (prof.is(VillagerProfession.CLERIC)) {
			return CLERIC;
		} else if (prof.is(VillagerProfession.MASON)) {
			return MASON;
		} else if (prof.is(VillagerProfession.FISHERMAN)) {
			return FISHERMAN;
		} else if (prof.is(VillagerProfession.FLETCHER)) {
			return FLETCHER;
		} else if (prof.is(VillagerProfession.LEATHERWORKER)) {
			return LEATHERWORKER;
		} else if (prof.is(VillagerProfession.CARTOGRAPHER)) {
			return CARTOGRAPHER;
		} else if (prof.is(VillagerProfession.LIBRARIAN)) {
			return LIBRARIAN;
		}
		// Beruf einer anderen Mod: ueber Datapack-Tag oder Schluesselwort im Namen einer Taetigkeit zuordnen.
		String key = de.villagerplus.Compat.jobKey(prof);
		if (key != null) {
			switch (key) {
				case "farmer": return FARMER;
				case "shepherd": return SHEPHERD;
				case "butcher": return BUTCHER;
				case "smith": return SMITH;
				case "cleric": return CLERIC;
				case "mason": return MASON;
				case "fisherman": return FISHERMAN;
				case "fletcher": return FLETCHER;
				case "leatherworker": return LEATHERWORKER;
				case "cartographer": return CARTOGRAPHER;
				case "librarian": return LIBRARIAN;
				default: break;
			}
		}
		return PORTER;
	}

	private static boolean hasJobTag(net.minecraft.core.Holder<VillagerProfession> profession, String job) {
		return profession.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "job/" + job)));
	}
}
