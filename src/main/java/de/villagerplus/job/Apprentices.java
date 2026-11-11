package de.villagerplus.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Names;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** Persistent career wishes and school-earned XP for village children. */
public final class Apprentices {
	public static final int QUALIFICATION_XP = 300;
	private static final int MAX_LESSON_XP = 100_000;
	private static final long NO_LESSON = Long.MIN_VALUE;

	public record Progress(String target, int lessonXp, long lastLessonAt, boolean quotaAssigned, boolean quotaOptOut) {
		public static final Codec<Progress> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.optionalFieldOf("target", "").forGetter(Progress::target),
			Codec.INT.optionalFieldOf("lesson_xp", 0).forGetter(Progress::lessonXp),
			Codec.LONG.optionalFieldOf("last_lesson_at", NO_LESSON).forGetter(Progress::lastLessonAt),
			Codec.BOOL.optionalFieldOf("quota_assigned", false).forGetter(Progress::quotaAssigned),
			Codec.BOOL.optionalFieldOf("quota_opt_out", false).forGetter(Progress::quotaOptOut)
		).apply(i, Progress::new));

		public Progress(String target, int lessonXp) {
			this(target, lessonXp, NO_LESSON, false, false);
		}

		public Progress(String target, int lessonXp, long lastLessonAt) {
			this(target, lessonXp, lastLessonAt, false, false);
		}

		public Progress {
			target = target == null ? "" : target;
			lessonXp = Math.max(0, Math.min(MAX_LESSON_XP, lessonXp));
		}

		public static Progress empty() { return new Progress("", 0, NO_LESSON, false, false); }
	}

	private Apprentices() {}

	public static Progress get(Villager villager) {
		Progress progress = villager.getAttached(VillagerPlus.APPRENTICE);
		return progress == null ? Progress.empty() : progress;
	}

	/** Selects a manual future profession. "minecraft:none" records a persistent opt-out from automatic allocation. */
	public static void plan(Villager villager, String profession) {
		if (profession == null || profession.equals("minecraft:none")) {
			villager.setAttached(VillagerPlus.APPRENTICE, new Progress("", 0, NO_LESSON, false, true));
			return;
		}
		Progress old = get(villager);
		villager.setAttached(VillagerPlus.APPRENTICE, new Progress(profession, old.lessonXp(), old.lastLessonAt(), false, false));
	}

	/** Sets an automatically selected target which may be removed when the village quota is reduced. */
	public static void planByQuota(Villager villager, String profession) {
		if (profession == null || profession.isBlank() || profession.equals("minecraft:none")) return;
		Progress old = get(villager);
		villager.setAttached(VillagerPlus.APPRENTICE, new Progress(profession, old.lessonXp(), old.lastLessonAt(), true, false));
	}

	/** A manual none-selection remains distinct from a child who has never received a target. */
	public static boolean quotaOptOut(Villager villager) {
		return get(villager).quotaOptOut();
	}

	public static boolean quotaAssigned(Villager villager) {
		return get(villager).quotaAssigned();
	}

	/** Removes only a quota-managed target while retaining school progress. */
	public static void clearQuotaPlan(Villager villager) {
		Progress old = get(villager);
		if (!old.quotaAssigned()) return;
		villager.setAttached(VillagerPlus.APPRENTICE, new Progress("", old.lessonXp(), old.lastLessonAt(), false, false));
	}

	public static void clear(Villager villager) {
		villager.setAttached(VillagerPlus.APPRENTICE, Progress.empty());
	}

	/** Awards both real villager XP and persistent qualification XP for a school lesson. */
	public static int awardLesson(Villager villager, int amount) {
		return awardLesson(villager, amount, NO_LESSON);
	}

	/** Awards a concrete classroom lesson at most once per villager, including across an entity reload. */
	public static int awardLesson(Villager villager, int amount, long lessonAt) {
		if (amount <= 0) return get(villager).lessonXp();
		Progress old = get(villager);
		if (lessonAt != NO_LESSON && old.lastLessonAt() == lessonAt) return old.lessonXp();
		int lessonXp = (int) Math.min(MAX_LESSON_XP, (long) old.lessonXp() + amount);
		long lastLessonAt = lessonAt == NO_LESSON ? old.lastLessonAt() : lessonAt;
		Progress next = new Progress(old.target(), lessonXp, lastLessonAt, old.quotaAssigned(), old.quotaOptOut());
		villager.setAttached(VillagerPlus.APPRENTICE, next);
		villager.setVillagerXp((int) Math.min(1_000_000L, (long) villager.getVillagerXp() + amount));
		return next.lessonXp();
	}

	public static String boardLabel(Villager villager) {
		Progress p = get(villager);
		return villager.isBaby() && !p.target().isEmpty() ? "Lehrling → " + professionName(p.target()) + " · " + p.lessonXp() + "/" + QUALIFICATION_XP + " XP" : null;
	}

	public static String activity(ServerLevel level, Villager villager) {
		Progress p = get(villager);
		if (p.target().isEmpty()) return null;
		return "lernt für " + professionName(level, p.target()) + " (" + p.lessonXp() + "/" + QUALIFICATION_XP + " XP)";
	}

	public static String professionName(String id) {
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return id;
		String path = parsed.getPath();
		if (path.isEmpty()) return id;
		return switch (path) {
			case "armorer" -> "Rüstungsschmied";
			case "butcher" -> "Metzger";
			case "cartographer" -> "Kartograph";
			case "cleric" -> "Kleriker";
			case "farmer" -> "Bauer";
			case "fisherman" -> "Fischer";
			case "fletcher" -> "Pfeilmacher";
			case "leatherworker" -> "Gerber";
			case "librarian" -> "Bibliothekar";
			case "mason" -> "Maurer";
			case "shepherd" -> "Schäfer";
			case "toolsmith" -> "Werkzeugschmied";
			case "weaponsmith" -> "Waffenschmied";
			default -> Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
		};
	}

	private static String professionName(ServerLevel level, String id) {
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return id;
		ResourceKey<VillagerProfession> key = ResourceKey.create(Registries.VILLAGER_PROFESSION, parsed);
		return level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).get(key).map(Names::profession).orElse(parsed.getPath());
	}

	/** Applies a child's selected profession once, when JobManager first sees it as an adult. */
	public static boolean completeIfAdult(ServerLevel level, Villager villager) {
		if (villager.isBaby()) return false;
		Progress progress = get(villager);
		if (progress.target().isEmpty()) {
			if (progress.quotaAssigned() || progress.quotaOptOut()) clearTarget(villager, progress);
			return false;
		}
		Identifier id = Identifier.tryParse(progress.target());
		if (id == null) {
			clearTarget(villager, progress);
			return false;
		}
		ResourceKey<VillagerProfession> key = ResourceKey.create(Registries.VILLAGER_PROFESSION, id);
		var profession = level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).get(key);
		if (profession.isEmpty()) {
			clearTarget(villager, progress);
			return false;
		}
		villager.releasePoi(MemoryModuleType.JOB_SITE);
		villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
		// Level 1 novices can lose a manually assigned profession without a workstation.
		int rank = progress.lessonXp() >= QUALIFICATION_XP ? 3 : 2;
		villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), key).withLevel(rank));
		villager.setVillagerXp(VillagerData.getMinXpPerLevel(rank));
		villager.setOffers(null);
		villager.refreshBrain(level);
		// Clearing the target makes this hook idempotent and leaves later player or vanilla career changes alone.
		villager.setAttached(VillagerPlus.APPRENTICE, new Progress("", progress.lessonXp(), progress.lastLessonAt(), false, false));
		return true;
	}

	private static void clearTarget(Villager villager, Progress progress) {
		villager.setAttached(VillagerPlus.APPRENTICE, new Progress("", progress.lessonXp(), progress.lastLessonAt(), false, false));
	}
}
