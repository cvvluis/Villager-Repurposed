package de.villagerplus.dev;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.ConstructionSite;
import de.villagerplus.build.Builder;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.Job;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobState;
import de.villagerplus.job.Phase;
import de.villagerplus.job.WoodcutterWork;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.resources.Identifier;

/** Bounded, in-memory development trace for staged G02 supply observation. */
public final class G02SupplyTrace {
	private static final int MAX_ENTRIES = 512;
	private static final Map<UUID, Trace> TRACES = new LinkedHashMap<>();
	private static final Map<UUID, Trace> DISPATCHES = new LinkedHashMap<>();
	private static final Map<UUID, Trace> SUPPLY_TRACES = new LinkedHashMap<>();
	private static final boolean DEV = FabricLoader.getInstance().isDevelopmentEnvironment();

	private G02SupplyTrace() { }

	/** Avoid diagnostic work in normal game runs; stage() itself is development-only. */
	public static boolean enabled() {
		return DEV;
	}

	private record Trace(UUID villager, String dimension, BlockPos bell, String profession, String job,
		String timePhase, long selectedAt, String stage, String detail, long stageAt) { }

	private record Request(String raw, String itemId, int amount, Item item) { }
	private record ChestView(Container container, String status) { }
	private record PlanView(BlockPos bell, BellBlockEntity bellEntity, VillageData data) { }

	/** Called once where the normal job manager has already chosen this resident's job. */
	public static void jobSelected(ServerLevel level, Villager villager, VillageRef village, Job job, Phase phase, JobState state, long now) {
		if (!DEV || village == null) return;
		String profession = villager.getVillagerData().profession().unwrapKey()
			.map(key -> key.identifier().toString()).orElse("unknown");
		remember(new Trace(villager.getUUID(), level.dimension().identifier().toString(),
			village.bell().immutable(), profession, job.getClass().getSimpleName(), phase.label, now,
			"job-selected", "cooldown=" + state.cooldownUntil, now));
	}

	/** Called by the normal bootstrap path; it only records the path already taken. */
	public static void stage(Ctx context, String stage, String detail) {
		if (!DEV || context.village() == null) return;
		UUID id = context.v().getUUID();
		Trace previous = TRACES.get(id);
		String dimension = context.level().dimension().identifier().toString();
		if (previous == null || !previous.dimension().equals(dimension)) {
			String profession = context.v().getVillagerData().profession().unwrapKey()
				.map(key -> key.identifier().toString()).orElse("unknown");
			previous = new Trace(id, dimension, context.village().bell().immutable(), profession,
				"not recorded", Phase.of(context.level().getOverworldClockTime()).label, context.now(),
				"none", "", context.now());
		}
		Trace trace = new Trace(id, dimension, context.village().bell().immutable(), previous.profession(),
			previous.job(), previous.timePhase(), previous.selectedAt(), stage, detail == null ? "" : detail, context.now());
		remember(trace);
		remember(DISPATCHES, trace);
		if (stage.startsWith("supply-")) remember(SUPPLY_TRACES, trace);
	}

	/** Compact in-memory stage snapshot for a failing world check; does not write to disk or mutate the plan. */
	public static String diagnostics(Villager villager) {
		if (!DEV || villager == null) return "trace=unavailable";
		UUID id = villager.getUUID();
		return "dispatch=" + describe(DISPATCHES.get(id)) + "; supply=" + describe(SUPPLY_TRACES.get(id));
	}

	/** Print a read-only snapshot of plans reachable through loaded residents or recently observed plans. */
	public static void report(CommandSourceStack source, ServerLevel level) {
		if (!DEV) {
			source.sendFailure(Component.literal("G02 supply trace is available only in a development environment."));
			return;
		}
		long now = level.getGameTime();
		Map<UUID, Villager> loadedVillagers = new LinkedHashMap<>();
		LinkedHashSet<BlockPos> candidateBells = new LinkedHashSet<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof Villager villager) || !villager.isAlive()) continue;
			loadedVillagers.put(villager.getUUID(), villager);
			villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT)
				.filter(global -> global.dimension() == level.dimension())
				.map(GlobalPos::pos).ifPresent(pos -> candidateBells.add(pos.immutable()));
			Trace trace = trace(villager.getUUID(), level);
			if (trace != null && trace.bell() != null) candidateBells.add(trace.bell());
		}
		for (Trace trace : List.copyOf(TRACES.values())) {
			if (trace.dimension().equals(level.dimension().identifier().toString()) && trace.bell() != null) {
				candidateBells.add(trace.bell());
			}
		}

		List<String> lines = new ArrayList<>();
		lines.add("VP090 G02 supply status — READ ONLY; dimension=" + level.dimension().identifier()
			+ "; gameTick=" + now + "; loadedVillagers=" + loadedVillagers.size());
		Map<BlockPos, PlanView> plans = new LinkedHashMap<>();
		List<String> unavailable = new ArrayList<>();
		for (BlockPos bellPos : candidateBells) {
			if (!level.hasChunkAt(bellPos)) {
				unavailable.add("bell " + bellPos.toShortString() + ": chunk unloaded; plan data not read");
				continue;
			}
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) {
				unavailable.add("bell " + bellPos.toShortString() + ": no loaded bell block entity");
				continue;
			}
			VillageData data = bell.getAttachedOrElse(VillagerPlus.VILLAGE, null);
			if (data == null) {
				unavailable.add("bell " + bellPos.toShortString() + ": village attachment absent");
				continue;
			}
			if (data.hasPlan()) plans.put(bellPos.immutable(), new PlanView(bellPos.immutable(), bell, data));
		}
		lines.add("Observed bells=" + candidateBells.size() + "; active loaded plans=" + plans.size());
		for (String missing : unavailable) lines.add("  unavailable: " + missing);
		if (plans.isEmpty()) {
			lines.add("No active plan is present in loaded bell data. Any unloaded bell/data is listed above.");
			for (BlockPos bellPos : candidateBells) {
				if (!level.hasChunkAt(bellPos) || !(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) continue;
				VillageData data = bell.getAttachedOrElse(VillagerPlus.VILLAGE, null);
				if (data == null) continue;
				VillageRef ref = new VillageRef(bellPos, data, bell);
				List<Villager> residents = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS,
					villager -> !de.villagerplus.village.Guests.isGuest(villager));
				Villager candidate = Builder.bootstrapCandidate(level, ref);
				String candidateText = candidate == null ? "none" : candidate.getName().getString()
					+ " profession=" + candidate.getVillagerData().profession().unwrapKey()
						.map(key -> key.identifier().toString()).orElse("unknown")
					+ " at=" + candidate.blockPosition().toShortString();
				lines.add("  bell=" + bellPos.toShortString() + " residents=" + residents.size()
					+ " nextCivicMilestone=" + Builder.nextSpecial(ref, residents.size())
					+ " electedBootstrapCandidate=" + candidateText);
			}
			List<Trace> selected = TRACES.values().stream()
				.filter(trace -> trace.dimension().equals(level.dimension().identifier().toString())
					&& trace.bell() != null && candidateBells.contains(trace.bell()))
				.sorted(java.util.Comparator.comparingLong(Trace::selectedAt).reversed())
				.limit(5).toList();
			if (!selected.isEmpty()) {
				lines.add("  recent normal job selections:");
				for (Trace trace : selected) {
					lines.add("    villager=" + trace.villager() + " profession=" + trace.profession()
						+ " job=" + trace.job() + " phase=" + trace.timePhase()
						+ (trace.detail().isEmpty() ? "" : " " + trace.detail())
						+ " age=" + Math.max(0L, now - trace.selectedAt()) + " ticks");
				}
			}
			List<Trace> recent = DISPATCHES.values().stream()
				.filter(trace -> trace.dimension().equals(level.dimension().identifier().toString())
					&& trace.bell() != null && candidateBells.contains(trace.bell()))
				.sorted(java.util.Comparator.comparingLong(Trace::stageAt).reversed())
				.limit(5).toList();
			if (recent.isEmpty()) {
				lines.add("  no normal bootstrap dispatch trace observed yet");
			} else {
				lines.add("  recent normal job/build stages:");
				for (Trace trace : recent) {
					lines.add("    bell=" + trace.bell().toShortString() + " villager=" + trace.villager()
						+ " stage=" + trace.stage() + (trace.detail().isEmpty() ? "" : " detail=" + trace.detail())
						+ " age=" + Math.max(0L, now - trace.stageAt()) + " ticks profession=" + trace.profession());
				}
			}
		} else {
			int number = 0;
			for (PlanView plan : plans.values()) appendPlan(lines, level, plan, loadedVillagers, ++number, now);
		}
		for (String line : lines) source.sendSuccess(() -> Component.literal(line), false);
	}

	private static void appendPlan(List<String> lines, ServerLevel level, PlanView plan,
		Map<UUID, Villager> loadedVillagers, int number, long now) {
		VillageData data = plan.data();
		BlockPos origin = data.planOrigin.orElse(null);
		ConstructionSite construction = plan.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		String constructionPhase;
		if (construction != null && origin != null && construction.origin().equals(origin) && !construction.phaseEnds().isEmpty()) {
			int total = construction.phaseEnds().get(construction.phaseEnds().size() - 1);
			constructionPhase = ConstructionSite.phaseName(construction.phaseFor(data.planStep, total))
				+ " (step " + data.planStep + "/" + total + ")";
		} else {
			constructionPhase = "phase metadata unavailable (step " + data.planStep + ")";
		}
		String originText = origin == null ? "absent" : origin.toShortString()
			+ (level.hasChunkAt(origin) ? " (chunk loaded)" : " (chunk unloaded)");
		lines.add("");
		lines.add("Plan " + number + ": bell=" + plan.bell().toShortString() + " type=" + data.planType
			+ " origin=" + originText + " phase=" + constructionPhase);
		lines.add("  requested=" + (data.wanted.isEmpty() ? "none" : String.join(", ", data.wanted)));

		UUID ownerId = data.planBuilder().orElse(null);
		Villager owner = ownerId == null ? null : loadedVillagers.get(ownerId);
		Trace trace = ownerId == null ? null : trace(ownerId, level);
		if (ownerId == null) {
			lines.add("  owner=absent (unclaimed plan)");
			lines.add("  worker activity/location/walkTarget/cooldown/cursors=unavailable (no owner assigned)");
		} else if (owner == null) {
			lines.add("  owner=" + ownerId + " (unloaded); activity/location/walkTarget/cooldown/cursors unavailable");
			if (trace != null) appendTrace(lines, trace, now, "  last trace: ");
		} else {
			JobState state = JobManager.stateIfKnown(owner);
			String activity = state == null ? "not observed yet" : JobManager.activityOf(owner, level);
			String profession = owner.getVillagerData().profession().unwrapKey()
				.map(key -> key.identifier().toString()).orElse("unknown");
			String job = trace == null ? "not observed yet" : trace.job();
			String walkTarget = owner.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
				.map(walk -> walk.getTarget().currentBlockPosition().toShortString()).orElse("none");
			String cooldown = state == null ? "unknown (no job state)" : Math.max(0L, state.cooldownUntil - now)
				+ " ticks remaining; until=" + state.cooldownUntil;
			String timePhase = Phase.of(level.getOverworldClockTime()).label;
			lines.add("  owner=" + owner.getName().getString() + " uuid=" + ownerId + " profession=" + profession
				+ " job=" + job + " location=" + owner.blockPosition().toShortString() + " walkTarget=" + walkTarget);
			lines.add("  timePhase=" + timePhase + " activity=" + activity + " cooldown=" + cooldown);
			if (trace == null) lines.add("  latestTrace=unavailable (job selection not observed yet)");
			else appendTrace(lines, trace, now, "  latestTrace: ");
			Trace supplyTrace = SUPPLY_TRACES.get(ownerId);
			if (supplyTrace != null && supplyTrace.dimension().equals(level.dimension().identifier().toString())) {
				appendTrace(lines, supplyTrace, now, "  latestSupplyTrace: ");
			}
			appendCursors(lines, level, owner, data);
		}
		if (owner == null) lines.add("  worker cursors=unavailable (no loaded plan owner)");

		ChestView chest = chest(level, plan);
		if (data.wanted.isEmpty()) {
			lines.add("  stock: no requested item counts; village chest=" + chest.status()
				+ "; worker inventory=" + (owner == null ? "unavailable" : "loaded"));
		} else {
			for (String raw : data.wanted) {
				Request request = parseRequest(raw);
				if (request.item() == null) {
					lines.add("  stock " + raw + ": unrecognized item id; chest=" + chest.status()
						+ "; worker inventory=" + (owner == null ? "unavailable" : "loaded"));
					continue;
				}
				String chestCount = chest.container() == null ? chest.status()
					: Long.toString(count(chest.container(), request.item()));
				String workerCount = owner == null ? "unavailable (owner unloaded)"
					: Long.toString(count(owner.getInventory(), request.item()));
				lines.add("  stock " + request.itemId() + ": wanted=" + request.amount()
					+ " villageChest=" + chestCount + " workerInventory=" + workerCount);
			}
		}
	}

	private static void appendCursors(List<String> lines, ServerLevel level, Villager owner, VillageData data) {
		WoodcutterWork wood = owner.getAttachedOrElse(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
		BlockPos root = wood.root().orElse(null);
		BlockPos log = wood.log().orElse(null);
		lines.add("  woodcutterCursor: root=" + loadedPos(level, root) + " log=" + loadedPos(level, log)
			+ " crackStage=" + wood.stage() + " removalPending=" + wood.removalPending());
		if (data.mine.isPresent()) {
			VillageData.Mine mine = data.mine.get();
			lines.add("  mineCursor: entrance=" + loadedPos(level, mine.entrance) + " section=" + mine.index
				+ " targetY=" + mine.targetY + " done=" + mine.done);
		} else {
			lines.add("  mineCursor=absent (no active mine in village data)");
		}
	}

	private static ChestView chest(ServerLevel level, PlanView plan) {
		BlockPos pos = plan.data().chest.orElse(null);
		if (pos == null) return new ChestView(null, "unregistered");
		if (!level.hasChunkAt(pos)) return new ChestView(null, "chunk unloaded at " + pos.toShortString());
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)) return new ChestView(null, "registered block absent/not a chest at " + pos.toShortString());
		if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos otherHalf = pos.relative(ChestBlock.getConnectedDirection(state));
			if (!level.hasChunkAt(otherHalf)) {
				Container primaryHalf = level.getBlockEntity(pos) instanceof Container container ? container : null;
				return new ChestView(primaryHalf, "other double-chest half unloaded; primary half only");
			}
		}
		Container container = HopperBlockEntity.getContainerAt(level, pos);
		return container == null ? new ChestView(null, "loaded chest has no accessible container")
			: new ChestView(container, "loaded at " + pos.toShortString());
	}

	private static Request parseRequest(String raw) {
		int separator = raw.lastIndexOf('#');
		String id = separator < 0 ? raw : raw.substring(0, separator);
		int amount = 0;
		if (separator >= 0) {
			try { amount = Integer.parseInt(raw.substring(separator + 1)); }
			catch (NumberFormatException ignored) { amount = 0; }
		}
		Identifier parsed = Identifier.tryParse(id);
		Item item = parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
		return new Request(raw, id, amount, item);
	}

	private static long count(Container container, Item item) {
		long result = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.is(item)) result += stack.getCount();
		}
		return result;
	}

	private static void appendTrace(List<String> lines, Trace trace, long now, String prefix) {
		lines.add(prefix + "stage=" + trace.stage() + (trace.detail().isEmpty() ? "" : " detail=" + trace.detail())
			+ " age=" + Math.max(0L, now - trace.stageAt()) + " ticks; profession=" + trace.profession()
			+ " selectedJob=" + trace.job()
			+ " selectedPhase=" + trace.timePhase() + " selectedAge=" + Math.max(0L, now - trace.selectedAt()) + " ticks");
	}

	private static String describe(Trace trace) {
		if (trace == null) return "none";
		return trace.stage() + (trace.detail().isEmpty() ? "" : "(" + trace.detail() + ")")
			+ "@" + trace.stageAt();
	}

	private static Trace trace(UUID villager, ServerLevel level) {
		String dimension = level.dimension().identifier().toString();
		Trace dispatch = DISPATCHES.get(villager);
		if (dispatch != null && dispatch.dimension().equals(dimension)
			&& level.getGameTime() - dispatch.stageAt() <= 1200L) return dispatch;
		Trace found = TRACES.get(villager);
		return found != null && found.dimension().equals(dimension) ? found : null;
	}

	private static String loadedPos(ServerLevel level, BlockPos pos) {
		return pos == null ? "none" : pos.toShortString() + (level.hasChunkAt(pos) ? " (chunk loaded)" : " (chunk unloaded)");
	}

	private static void remember(Trace trace) { remember(TRACES, trace); }

	private static void remember(Map<UUID, Trace> target, Trace trace) {
		target.remove(trace.villager());
		target.put(trace.villager(), trace);
		while (target.size() > MAX_ENTRIES) target.remove(target.keySet().iterator().next());
	}
}
