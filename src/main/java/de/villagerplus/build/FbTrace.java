package de.villagerplus.build;

import de.villagerplus.VillagerMemory;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** TEMPORARY first-building instrumentation. Not for commit. */
public final class FbTrace {
	private static final Logger LOG = LoggerFactory.getLogger("FbTrace");
	private static final Map<UUID, Long> LAST = new HashMap<>();
	private static final Map<UUID, String> NAMES = new HashMap<>();
	private static final Map<UUID, Long> BELL = new HashMap<>();
	private static final Map<Long, VillageRef> REFS = new HashMap<>();
	private static final Map<UUID, Map<String, Long>> ACT = new HashMap<>();
	private static long nextDump = 0;
	private static ServerLevel LEVEL;

	private static boolean idle(String a) {
		return a.isEmpty() || a.contains("Pausenplatz") || a.contains("nichts zu tun") || a.contains("plaudert") || a.contains("hält sich")
			|| a.contains("macht Pause") || a.startsWith("<schlaf>");
	}

	public static void sample(ServerLevel level, Villager v, VillagerMemory mem, String activity, String prof, VillageRef village, long now) {
		LEVEL = level;
		Long last = LAST.put(v.getUUID(), now);
		NAMES.put(v.getUUID(), mem.name + "(" + prof + ")");
		if (village != null) {
			BELL.put(v.getUUID(), village.bell().asLong());
			REFS.put(village.bell().asLong(), village);
		}
		if (last != null) {
			long dt = Math.min(now - last, 400);
			String a = (v.isSleeping() ? "<schlaf> " : "") + activity.replaceAll("[-0-9]+", "#");
			if (a.length() > 70) a = a.substring(0, 70);
			ACT.computeIfAbsent(v.getUUID(), k -> new HashMap<>()).merge(a, dt, Long::sum);
		}
		if (now >= nextDump) {
			nextDump = now + 1200;
			dump(now);
		}
	}

	private static void dump(long now) {
		for (var ve : REFS.entrySet()) {
			VillageRef ref = ve.getValue();
			VillageData d = ref.data();
			Set<UUID> owners = new HashSet<>();
			StringBuilder sb = new StringBuilder();
			for (VillageData.BuildProject p : d.buildProjects) {
				if (!p.active()) continue;
				int total = Builder.fbTotal(p.origin().asLong());
				String owner = p.owner().map(id -> { owners.add(id); return NAMES.getOrDefault(id, "?"); }).orElse("-");
				sb.append("\n  PROJ ").append(p.type()).append('@').append(p.origin().toShortString()).append(" step ").append(p.step()).append('/').append(total)
					.append(" owner=").append(owner).append(" wanted=").append(p.wanted()).append(" wait=").append(p.materialWait().orElse("-"));
			}
			if (d.hasPlan()) sb.append("\n  VIEW ").append(d.planType).append(" step ").append(d.planStep).append(" wanted=").append(d.wanted);
			int pop = 0;
			for (var e : BELL.entrySet()) if (e.getValue().equals(ve.getKey())) pop++;
			String stock = "";
			try {
				var box = de.villagerplus.village.Lager.combined(LEVEL, ref);
				if (box != null) {
					stock = " seeds=" + de.villagerplus.job.JobUtil.count(box, x -> x.is(net.minecraft.world.item.Items.WHEAT_SEEDS))
						+ " cobble=" + de.villagerplus.job.JobUtil.count(box, x -> x.is(net.minecraft.world.item.Items.COBBLESTONE))
						+ " logs=" + de.villagerplus.job.JobUtil.count(box, x -> x.is(net.minecraft.tags.ItemTags.LOGS))
						+ " planks=" + de.villagerplus.job.JobUtil.count(box, x -> x.is(net.minecraft.tags.ItemTags.PLANKS))
						+ " sticks=" + de.villagerplus.job.JobUtil.count(box, x -> x.is(net.minecraft.world.item.Items.STICK));
				}
			} catch (RuntimeException ex) { stock = " stock?"; }
			LOG.info("VPFB tick {} bell {} samplesPop {} farms {} well {}{} projects:{}", now, ref.bell().toShortString(), pop, d.farms, d.wellBuilt, stock, sb);
			for (var e : ACT.entrySet()) {
				if (!ve.getKey().equals(BELL.get(e.getKey()))) continue;
				Map<String, Long> m = e.getValue();
				long work = 0;
				for (var x : m.entrySet()) if (!idle(x.getKey())) work += x.getValue();
				if (work == 0) continue;
				String top = m.entrySet().stream().filter(x -> !idle(x.getKey())).sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6)
					.map(x -> x.getValue() + "=" + x.getKey()).reduce((a, b) -> a + " | " + b).orElse("");
				LOG.info("VPFB act {}{} work {} : {}", NAMES.getOrDefault(e.getKey(), "?"), owners.contains(e.getKey()) ? " [OWNER]" : "", work, top);
			}
		}
		ACT.clear();
	}

	private static final Map<String, Long> TR = new HashMap<>();
	public static void trace(String key, long now, long every, String msg) {
		Long l = TR.get(key);
		if (l != null && now - l < every) return;
		TR.put(key, now);
		LOG.info("VPFB tr {} tick {} {}", key, now, msg);
	}

	public static void ev(String m) { LOG.info("VPFB ev {}", m); }

	public static void cost(String type, List<Step> steps) {
		Map<String, Integer> m = new TreeMap<>();
		int free = 0, opt = 0, linked = 0;
		for (Step st : steps) {
			if (st.free()) { free++; continue; }
			if (st.linkedTo() != null) { linked++; continue; }
			if (st.optional()) opt++;
			String k = st.item() != null ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.item()).getPath() : "#" + st.tag().location().getPath();
			m.merge(k, st.cost(), Integer::sum);
		}
		LOG.info("VPFB cost {} steps={} free={} optional={} linked={} paid={}", type, steps.size(), free, opt, linked, m);
	}
}
