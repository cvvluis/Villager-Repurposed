package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Last house-site rejection histogram for the Dorftafel details tab. */
public record SiteSearchDiagnostics(long searchedAt, int[] siteReject, Map<String, Integer> gradeReject,
	Map<String, Integer> plotBlockers, String planType) {
	public static final Codec<SiteSearchDiagnostics> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.LONG.fieldOf("searched_at").forGetter(SiteSearchDiagnostics::searchedAt),
		Codec.INT.listOf().fieldOf("site_reject").forGetter(d -> Arrays.stream(d.siteReject).boxed().toList()),
		Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("grade_reject", Map.of()).forGetter(SiteSearchDiagnostics::gradeReject),
		Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("plot_blockers", Map.of()).forGetter(SiteSearchDiagnostics::plotBlockers),
		Codec.STRING.optionalFieldOf("plan_type", "").forGetter(SiteSearchDiagnostics::planType)
	).apply(i, SiteSearchDiagnostics::fromCodec));

	private static SiteSearchDiagnostics fromCodec(long searchedAt, java.util.List<Integer> siteReject,
		Map<String, Integer> gradeReject, Map<String, Integer> plotBlockers, String planType) {
		int[] reject = new int[10];
		for (int i = 0; i < Math.min(10, siteReject.size()); i++) reject[i] = siteReject.get(i);
		return new SiteSearchDiagnostics(searchedAt, reject, gradeReject, plotBlockers, planType);
	}

	public static void record(VillageRef ref, long now, String planType, int[] siteReject,
		Map<String, Integer> gradeReject, Map<String, Integer> plotBlockers) {
		if (ref == null) return;
		int[] copy = siteReject == null ? new int[10] : siteReject.clone();
		ref.bellEntity().setAttached(VillagerPlus.SITE_SEARCH, new SiteSearchDiagnostics(now, copy,
			gradeReject == null ? Map.of() : Map.copyOf(gradeReject),
			plotBlockers == null ? Map.of() : Map.copyOf(plotBlockers), planType == null ? "" : planType));
		ref.dirty();
	}

	public static SiteSearchDiagnostics of(VillageRef ref) {
		return ref == null ? null : ref.bellEntity().getAttached(VillagerPlus.SITE_SEARCH);
	}

	public static void clear() { }

	/** Human-facing summary for the board (translation keys resolved server-side). */
	public Component summary(ServerLevel level) {
		int total = Arrays.stream(siteReject).skip(1).sum();
		if (total <= 0) {
			return Component.translatable("board.villagerplus.site_search.none");
		}
		int steep = siteReject[1] + gradeReject.values().stream().mapToInt(Integer::intValue).sum();
		int zone = siteReject[6];
		int blocked = siteReject[5] + siteReject[7] + siteReject[8] + plotBlockers.values().stream().mapToInt(Integer::intValue).sum();
		int other = Math.max(0, total - steep - zone - blocked);
		int pctSteep = steep * 100 / total;
		int pctZone = zone * 100 / total;
		int pctBlocked = blocked * 100 / total;
		int pctOther = Math.max(0, 100 - pctSteep - pctZone - pctBlocked);
		long age = level.getGameTime() - searchedAt;
		String typeLabel = planType == null || planType.isBlank() ? "?" : planType;
		return Component.translatable("board.villagerplus.site_search.summary", typeLabel, pctSteep, pctZone, pctBlocked, pctOther, age / 20L);
	}

	public String detailLine(ServerLevel level) {
		return summary(level).getString();
	}
}