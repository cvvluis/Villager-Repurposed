package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Skills;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Village administration: one resident serves as mayor, the mayor's village orients itself towards what its surroundings
 * offer (wood, farmland, stone or water), and the mayor tends the relations to neighbouring villages.
 */
public final class VillageAdmin {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("villagerplus");

	private VillageAdmin() { }

	/** What a village concentrates on, derived from the terrain around its bell. */
	public enum Focus {
		FOREST("Holz & Waldwirtschaft", Balance.Kind.WOOD),
		FARM("Landwirtschaft", Balance.Kind.FOOD),
		STONE("Stein & Bergbau", Balance.Kind.STONE),
		WATER("Fischerei & Wasser", Balance.Kind.FOOD);

		public final String label;
		public final Balance.Kind kind;

		Focus(String label, Balance.Kind kind) {
			this.label = label;
			this.kind = kind;
		}

		static Optional<Focus> parse(String name) {
			for (Focus f : values()) if (f.name().equals(name)) return Optional.of(f);
			return Optional.empty();
		}
	}

	public record Admin(Optional<UUID> mayor, String mayorName, String focus, long focusDay, long diplomacyDay, String woodStem, boolean rusticStone) {
		public static final Codec<Admin> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.optionalFieldOf("mayor").forGetter(Admin::mayor),
			Codec.STRING.optionalFieldOf("mayor_name", "").forGetter(Admin::mayorName),
			Codec.STRING.optionalFieldOf("focus", "").forGetter(Admin::focus),
			Codec.LONG.optionalFieldOf("focus_day", -1L).forGetter(Admin::focusDay),
			Codec.LONG.optionalFieldOf("diplomacy_day", -1L).forGetter(Admin::diplomacyDay),
			Codec.STRING.optionalFieldOf("wood_stem", "").forGetter(Admin::woodStem),
			Codec.BOOL.optionalFieldOf("rustic_stone", false).forGetter(Admin::rusticStone)
		).apply(i, Admin::new));

		public static final Admin EMPTY = new Admin(Optional.empty(), "", "", -1L, -1L, "", false);

		public Admin {
			mayorName = mayorName == null ? "" : mayorName;
			focus = focus == null ? "" : focus;
			woodStem = woodStem == null ? "" : woodStem;
		}
	}

	private static final long REVIEW_INTERVAL_TICKS = 1200L;
	private static final long FOCUS_REFRESH_TICKS = 72_000L;
	private static final long DIPLOMACY_INTERVAL_TICKS = 24_000L;
	/** First part of the morning work phase the mayor spends on administration at the village street. */
	private static final long GOVERN_START = 1000L;
	private static final long GOVERN_END = 1700L;
	private static final Map<Long, Long> NEXT_REVIEW = new HashMap<>();

	public static Admin of(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.VILLAGE_ADMIN, Admin.EMPTY);
	}

	private static void save(VillageRef ref, Admin admin) {
		ref.bellEntity().setAttached(VillagerPlus.VILLAGE_ADMIN, admin);
		ref.bellEntity().setChanged();
	}

	public static Optional<Focus> focusOf(VillageRef ref) {
		return Focus.parse(of(ref).focus());
	}

	/** The resource group the village concentrates on, or null while it has not chosen yet. */
	public static Balance.Kind focusKind(VillageRef ref) {
		return focusOf(ref).map(f -> f.kind).orElse(null);
	}

	/** Multiplier for a profession's share of the population: the focus professions get more people. */
	public static double quotaBoost(VillageRef ref, String profession) {
		Optional<Focus> focus = focusOf(ref);
		if (focus.isEmpty()) return 1.0;
		return switch (focus.get()) {
			case FOREST -> profession.equals("fletcher") ? 1.6 : 1.0;
			case FARM -> profession.equals("farmer") ? 1.4 : profession.equals("butcher") || profession.equals("shepherd") ? 1.25 : 1.0;
			case STONE -> profession.equals("mason") ? 1.5 : profession.equals("toolsmith") ? 1.25 : 1.0;
			case WATER -> profession.equals("fisherman") ? 2.0 : 1.0;
		};
	}

	/** Wood species the village builds with locally (e.g. "birch"), or "" while construction uses the planned species. */
	public static String woodStem(VillageRef ref) {
		return of(ref).woodStem();
	}

	/** Local wood stem for the village nearest to a build origin ("" if none or not chosen). */
	public static String woodStemAt(ServerLevel level, BlockPos origin) {
		VillageRef ref = Villages.villageNear(level, origin, 200);
		return ref == null ? "" : woodStem(ref);
	}

	public static void setWoodStem(VillageRef ref, String stem) {
		Admin admin = of(ref);
		if (!admin.woodStem().equals(stem)) save(ref, new Admin(admin.mayor(), admin.mayorName(), admin.focus(), admin.focusDay(), admin.diplomacyDay(), stem, admin.rusticStone()));
	}

	public static void clearLocalWood(VillageRef ref) {
		Admin admin = of(ref);
		if (!admin.woodStem().isEmpty()) save(ref, new Admin(admin.mayor(), admin.mayorName(), admin.focus(), admin.focusDay(), admin.diplomacyDay(), "", admin.rusticStone()));
	}

	/**
	 * Picks the most common natural log species around the village and remembers it as the local building wood.
	 * Returns the stem (e.g. "acacia"), or "" when no tree was found.
	 */
	private static final Map<Long, String> NEAREST_SEEN_STEM = new HashMap<>();

	/** A builder that found no matching tree reports the species of the nearest log it did see; it wins over sampling. */
	public static void noteNearestLog(VillageRef ref, String stem) {
		if (stem != null && !stem.isBlank()) NEAREST_SEEN_STEM.put(ref.bell().asLong(), stem);
	}

	public static String adoptLocalWood(ServerLevel level, VillageRef ref) {
		String seen = NEAREST_SEEN_STEM.remove(ref.bell().asLong());
		if (seen != null) {
			Admin known = of(ref);
			save(ref, new Admin(known.mayor(), known.mayorName(), known.focus(), known.focusDay(), known.diplomacyDay(), seen, known.rusticStone()));
			return seen;
		}
		// Nearest tree group first: rings of 24/40/56/72 blocks; the first ring that has trees decides the species.
		@SuppressWarnings("unchecked")
		Map<String, Integer>[] rings = new Map[] {new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()};
		BlockPos bell = ref.bell();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -72; dx <= 72; dx += 3) {
			for (int dz = -72; dz <= 72; dz += 3) {
				int x = bell.getX() + dx, z = bell.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				for (int dy = 0; dy <= 14; dy++) {
					BlockState state = level.getBlockState(pos.set(x, top - dy, z));
					if (!state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) continue;
					String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
					int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
					int ring = chebyshev <= 24 ? 0 : chebyshev <= 40 ? 1 : chebyshev <= 56 ? 2 : 3;
					if (path.endsWith("_log")) rings[ring].merge(path.substring(0, path.length() - "_log".length()), 1, Integer::sum);
					break;
				}
			}
		}
		String best = "";
		int bestCount = 0;
		for (Map<String, Integer> counts : rings) {
			if (counts.isEmpty()) continue;
			for (Map.Entry<String, Integer> entry : counts.entrySet()) {
				if (entry.getValue() > bestCount) {
					best = entry.getKey();
					bestCount = entry.getValue();
				}
			}
			break;
		}
		if (best.isEmpty()) return "";
		Admin admin = of(ref);
		save(ref, new Admin(admin.mayor(), admin.mayorName(), admin.focus(), admin.focusDay(), admin.diplomacyDay(), best, admin.rusticStone()));
		return best;
	}

	/** Whether the village builds with plain cobblestone instead of smelted stone bricks (no furnace or fuel needed). */
	public static boolean rusticStone(VillageRef ref) {
		return of(ref).rusticStone();
	}

	public static void adoptRusticStone(VillageRef ref) {
		Admin admin = of(ref);
		if (!admin.rusticStone()) save(ref, new Admin(admin.mayor(), admin.mayorName(), admin.focus(), admin.focusDay(), admin.diplomacyDay(), admin.woodStem(), true));
	}

	public static void clearRusticStone(VillageRef ref) {
		Admin admin = of(ref);
		if (admin.rusticStone()) save(ref, new Admin(admin.mayor(), admin.mayorName(), admin.focus(), admin.focusDay(), admin.diplomacyDay(), admin.woodStem(), false));
	}

	public static boolean isMayor(VillageRef ref, Villager v) {
		return of(ref).mayor().filter(v.getUUID()::equals).isPresent();
	}

	/** One-line description for the status command and the village board. */
	public static String summary(VillageRef ref) {
		Admin admin = of(ref);
		String mayor = admin.mayorName().isBlank() ? "kein Bürgermeister" : "Bürgermeister " + admin.mayorName();
		String focus = focusOf(ref).map(f -> "Schwerpunkt " + f.label).orElse("Schwerpunkt noch offen");
		return mayor + ", " + focus;
	}

	/** Cheap, self-rate-limited upkeep: elects a mayor when needed, refreshes the focus and tends neighbour relations. */
	public static void maintain(ServerLevel level, VillageRef ref, long now) {
		long key = ref.bell().asLong();
		if (now < NEXT_REVIEW.getOrDefault(key, 0L)) return;
		NEXT_REVIEW.put(key, now + REVIEW_INTERVAL_TICKS);
		if (NEXT_REVIEW.size() > 256) NEXT_REVIEW.clear();

		Admin admin = of(ref);
		Villager mayor = admin.mayor().map(level::getEntity).filter(e -> e instanceof Villager).map(e -> (Villager) e)
			.filter(v -> v.isAlive() && !v.isBaby()).orElse(null);
		if (mayor == null) {
			Villager elected = elect(level, ref);
			if (elected == null) return;
			VillagerMemory mem = elected.getAttachedOrCreate(VillagerPlus.MEMORY);
			admin = new Admin(Optional.of(elected.getUUID()), mem.name, admin.focus(), admin.focusDay(), admin.diplomacyDay(), admin.woodStem(), admin.rusticStone());
			save(ref, admin);
			LOGGER.info("Villagers: Repurposed: {} ist jetzt Buergermeister (Glocke {})", mem.name, ref.bell().toShortString());
			JobUtil.tellNearbyPlayers(level, elected, 64, Component.literal(mem.name + " ist jetzt Bürgermeister des Dorfes."));
			mayor = elected;
		}

		if (admin.focus().isBlank() || now - admin.focusDay() >= FOCUS_REFRESH_TICKS) {
			Focus scanned = scan(level, ref.bell());
			if (!scanned.name().equals(admin.focus())) {
				LOGGER.info("Villagers: Repurposed: Dorf an Glocke {} richtet sich auf {} aus", ref.bell().toShortString(), scanned.label);
				JobUtil.tellNearbyPlayers(level, mayor, 64, Component.literal(admin.mayorName() + " richtet das Dorf auf "
					+ scanned.label + " aus."));
			}
			admin = new Admin(admin.mayor(), admin.mayorName(), scanned.name(), now, admin.diplomacyDay(), admin.woodStem(), admin.rusticStone());
			save(ref, admin);
		}

		if (admin.diplomacyDay() < 0 || now - admin.diplomacyDay() >= DIPLOMACY_INTERVAL_TICKS) {
			tendRelations(level, ref);
			admin = new Admin(admin.mayor(), admin.mayorName(), admin.focus(), admin.focusDay(), now, admin.woodStem(), admin.rusticStone());
			save(ref, admin);
		}
	}

	/** The most experienced adult resident becomes mayor (ties: stable by id). */
	private static Villager elect(ServerLevel level, VillageRef ref) {
		List<Villager> candidates = Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v) && !v.entityTags().contains("vp090_fixture") && !v.isBaby()
			&& v.isAlive() && !v.getVillagerData().profession().is(VillagerProfession.NITWIT));
		Villager best = null;
		int bestLevel = Integer.MIN_VALUE;
		for (Villager v : candidates) {
			int skill = Skills.level(v.getAttachedOrCreate(VillagerPlus.MEMORY));
			if (best == null || skill > bestLevel || (skill == bestLevel && v.getUUID().compareTo(best.getUUID()) < 0)) {
				best = v;
				bestLevel = skill;
			}
		}
		return best;
	}

	/** Samples the terrain around the bell and returns what the surroundings offer most. */
	static Focus scan(ServerLevel level, BlockPos bell) {
		int trees = 0, water = 0, stone = 0, farm = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -48; dx <= 48; dx += 4) {
			for (int dz = -48; dz <= 48; dz += 4) {
				int x = bell.getX() + dx, z = bell.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				BlockState top = level.getBlockState(pos.set(x, y, z));
				if (top.is(BlockTags.LEAVES) || top.is(BlockTags.LOGS)) trees++;
				else if (!top.getFluidState().isEmpty()) water++;
				else if (top.is(BlockTags.BASE_STONE_OVERWORLD) || top.is(Blocks.GRAVEL)) stone++;
				else if (top.is(BlockTags.DIRT)) farm++;
			}
		}
		double forest = trees * 1.0, lake = water * 0.9, rock = stone * 1.5, field = farm * 0.5;
		double best = field;
		Focus focus = Focus.FARM;
		if (forest > best) { best = forest; focus = Focus.FOREST; }
		if (rock > best) { best = rock; focus = Focus.STONE; }
		if (lake > best) { focus = Focus.WATER; }
		return focus;
	}

	/**
	 * Neighbouring villages with the same focus compete for the same resources and drift apart; complementary
	 * neighbours trade well and drift together. Only loaded neighbours in the same dimension are tended.
	 */
	private static void tendRelations(ServerLevel level, VillageRef ref) {
		Optional<Focus> own = focusOf(ref);
		if (own.isEmpty()) return;
		TradeNetwork network = Network.of(ref.bellEntity());
		String dimension = Network.dim(level);
		for (TradeNetwork.Link link : network.links()) {
			if (!dimension.equals(link.dimension())) continue;
			VillageRef other = Villages.villageNear(level, link.bell(), 8);
			if (other == null) continue;
			Optional<Focus> theirs = focusOf(other);
			if (theirs.isEmpty()) continue;
			int delta = theirs.get() == own.get() ? -2 : 1;
			LOGGER.info("Villagers: Repurposed: Beziehung {} -> {} um {} ({} / {})", ref.bell().toShortString(), link.bell().toShortString(), delta, own.get().label, theirs.get().label);
			Network.nudgeRelation(ref.bellEntity(), dimension, link.bell(), delta);
		}
	}

	/**
	 * The mayor spends the start of the morning at the village street, administering. Returns true while busy.
	 */
	public static boolean govern(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || !isMayor(ref, c.v())) return false;
		// Never pull a resident away from an active mine route lease or an ongoing work cycle.
		if (c.v().getAttached(VillagerPlus.MINING_ROUTE) != null || c.st().miningUntil > c.now()) return false;
		// The morning duty is a visible nicety: only perform it when a player could actually see it.
		if (c.level().getNearestPlayer(ref.bell().getX() + 0.5, ref.bell().getY(), ref.bell().getZ() + 0.5, 96.0, false) == null) return false;
		long time = Math.floorMod(c.level().getOverworldClockTime(), 24_000L);
		if (time < GOVERN_START || time >= GOVERN_END) return false;
		BlockPos stand = de.villagerplus.build.Builder.villageReturn(c.level(), ref.bell());
		if (stand == null) stand = ref.bell();
		if (!JobUtil.approachLong(c, stand, 3.0)) {
			c.setActivity("geht zur Dorfmitte, um das Dorf zu verwalten");
			return true;
		}
		c.setActivity("verwaltet das Dorf als Bürgermeister (" + focusOf(ref).map(f -> f.label).orElse("Schwerpunkt offen") + ")");
		c.st().cooldownUntil = c.now() + 100;
		return true;
	}
}
