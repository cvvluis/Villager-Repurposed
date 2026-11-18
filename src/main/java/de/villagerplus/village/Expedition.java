package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Skills;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Expeditionen: Gelegentlich brechen mutige Dorfbewohner zu einer Erkundung auf (Ruinen, Hoehlen, verlassene Festungen). Sie
 * sind einen halben Tag fort, danach zaehlen ihre Gesundheit und ihre Fertigkeitsstufe: Sie kommen verletzt zurueck - oder gar
 * nicht. Heimkehrer bringen Beute mit, die in die Dorfkiste (sonst ins Lager) wandert. Alles steht auf der Dorftafel.
 */
public final class Expedition {
	public static final String TAG = "vp_away";

	private record Dest(String name, int tier) { }

	private static final Dest[] DESTS = {
		new Dest("die alten Ruinen im Wald", 1), new Dest("eine tiefe Höhle", 2), new Dest("eine verlassene Festung", 3)
	};

	/** Beute je Stufe: Gegenstand, Mindest- und Hoechstmenge, Gewicht. */
	private record Loot(Item item, int min, int max, int weight) { }

	private static final Loot[][] LOOT = {
		{new Loot(Items.BREAD, 2, 5, 10), new Loot(Items.ARROW, 4, 12, 8), new Loot(Items.STRING, 2, 6, 8), new Loot(Items.IRON_NUGGET, 3, 9, 8),
			new Loot(Items.COAL, 3, 8, 8), new Loot(Items.BONE, 2, 5, 6), new Loot(Items.FEATHER, 2, 6, 6), new Loot(Items.LEATHER, 1, 3, 5)},
		{new Loot(Items.IRON_INGOT, 1, 4, 10), new Loot(Items.GOLD_NUGGET, 3, 8, 8), new Loot(Items.REDSTONE, 3, 8, 8), new Loot(Items.LAPIS_LAZULI, 2, 6, 6),
			new Loot(Items.COPPER_INGOT, 2, 6, 8), new Loot(Items.GUNPOWDER, 2, 5, 6), new Loot(Items.SADDLE, 1, 1, 1), new Loot(Items.NAME_TAG, 1, 1, 2)},
		{new Loot(Items.GOLD_INGOT, 1, 3, 10), new Loot(Items.IRON_INGOT, 2, 6, 10), new Loot(Items.DIAMOND, 1, 1, 2), new Loot(Items.ENDER_PEARL, 1, 2, 3),
			new Loot(Items.GOLDEN_APPLE, 1, 1, 2), new Loot(Items.EMERALD, 1, 1, 1), new Loot(Items.EXPERIENCE_BOTTLE, 1, 3, 4), new Loot(Items.SADDLE, 1, 1, 2)}
	};

	private Expedition() {
	}

	/** Auf dem Weg zum Dorfrand (sichtbarer Aufbruch) bzw. zurueck zur Dorfkiste (sichtbare Heimkehr). */
	public static final String LEAVING = "vp_leaving";
	public static final String RETURNING = "vp_returning";
	private static final int EXIT_DISTANCE = 44;

	private record Walk(BlockPos target, BlockPos bell, boolean leaving, long deadline, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) { }

	/** Laufende Wege (nur im Speicher; nach einem Neustart raeumt {@link #register()} per Entity-Tag auf). */
	private static final java.util.Map<UUID, Walk> WALKS = new java.util.HashMap<>();

	public static boolean isAway(Villager v) {
		var tags = v.entityTags();
		return tags.contains(TAG) || tags.contains(LEAVING) || tags.contains(RETURNING);
	}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(Expedition::walk);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> WALKS.clear());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!(entity instanceof Villager v) || WALKS.containsKey(v.getUUID())) {
				return;
			}
			// Der Weg ging beim Neustart verloren: Aufbrechende gelten als fort, Heimkehrer sind angekommen.
			if (v.entityTags().contains(LEAVING)) {
				v.removeTag(LEAVING);
				park(v, level.getMaxY() - 3);
			} else if (v.entityTags().contains(RETURNING)) {
				v.removeTag(RETURNING);
			}
		});
	}

	/** Fester Ausgangspunkt am Dorfrand, in der Naehe der Glocke geladen, je Expedition und Person leicht versetzt. */
	private static BlockPos exitPoint(ServerLevel level, BlockPos bell, long seed, int index) {
		double angle = Math.floorMod(seed * 31L + index * 23L, 360L) * Math.PI / 180.0;
		for (int dist = EXIT_DISTANCE; dist >= 16; dist -= 14) {
			BlockPos col = bell.offset((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
			if (de.villagerplus.world.LoadedColumns.ready(level, col)) {
				return de.villagerplus.world.LoadedColumns.heightmapPos(level, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col);
			}
		}
		return bell;
	}

	private static void walk(ServerLevel level) {
		long now = level.getGameTime();
		if (WALKS.isEmpty() || now % 10 != 0) {
			return;
		}
		for (var it = WALKS.entrySet().iterator(); it.hasNext();) {
			var entry = it.next();
			Walk w = entry.getValue();
			if (!w.dimension().equals(level.dimension())) {
				continue;
			}
			Entity e = level.getEntity(entry.getKey());
			if (!(e instanceof Villager v) || !v.isAlive()) {
				if (e == null && now < w.deadline() && !de.villagerplus.world.LoadedColumns.ready(level, w.target())) {
					continue;
				}
				it.remove();
				continue;
			}
			boolean arrived = v.position().distanceToSqr(w.target().getX() + 0.5, w.target().getY(), w.target().getZ() + 0.5) <= 9.0;
			if (arrived || now >= w.deadline()) {
				it.remove();
				if (w.leaving()) {
					v.removeTag(LEAVING);
					level.playSound(null, v.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_CELEBRATE, net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.0F);
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, v.getX(), v.getY() + 0.5, v.getZ(), 8, 0.3, 0.4, 0.3, 0.02);
					park(v, level.getMaxY() - 3);
				} else {
					v.removeTag(RETURNING);
				}
				continue;
			}
			v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
				new net.minecraft.world.entity.ai.memory.WalkTarget(w.target(), w.leaving() ? 0.6F : 0.5F, 1));
		}
	}

	private static void park(Villager v, double y) {
		v.addTag(TAG);
		v.getNavigation().stop();
		v.setNoAi(true);
		v.setInvisible(true);
		v.setInvulnerable(true);
		v.setSilent(true);
		v.setNoGravity(true);
		v.teleportTo(v.getX(), y, v.getZ());
	}

	// --- Ablauf ---------------------------------------------------------------------------------

	/** Wird regelmaessig von einem Dorfbewohner aufgerufen: startet bzw. beendet Expeditionen. */
	public static void tick(ServerLevel level, VillageRef ref, long now) {
		VillageData.Extra ex = ref.data().extra;
		if (ex.expActive.isPresent()) {
			String[] parts = ex.expActive.get().split(";");
			if (parts.length >= 3 && now >= parseLong(parts[1])) {
				finish(level, ref, parts, now);
			}
			return;
		}
		if (ex.expNext == 0L) {
			ex.expNext = now + 9000L + level.getRandom().nextInt(6000);
			ref.dirty();
			return;
		}
		if (now < ex.expNext) {
			return;
		}
		start(level, ref, now);
	}

	/** Nur fuer Entwicklungsbefehle: Expedition sofort starten (ignoriert den Zeitgeber). */
	public static boolean debugStart(ServerLevel level, VillageRef ref) {
		if (ref.data().extra.expActive.isPresent()) {
			return false;
		}
		start(level, ref, level.getGameTime());
		return ref.data().extra.expActive.isPresent();
	}

	private static void start(ServerLevel level, VillageRef ref, long now) {
		VillageData.Extra ex = ref.data().extra;
		RandomSource r = level.getRandom();
		List<Villager> residents = Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v) && !isAway(v));
		List<Villager> pool = new ArrayList<>();
		for (Villager v : residents) {
			boolean skilled = !v.getVillagerData().profession().is(VillagerProfession.NONE) && !v.getVillagerData().profession().is(VillagerProfession.NITWIT);
			// Maurer bleiben im Dorf, solange ein Bauauftrag laeuft (sonst steht der Bau still, bis sie zurueck sind).
			boolean builderBusy = ref.data().hasPlan() && v.getVillagerData().profession().is(VillagerProfession.MASON);
			if (!v.isBaby() && skilled && !builderBusy && v.getHealth() >= 14.0F && !v.isSleeping() && !v.isTrading()) {
				pool.add(v);
			}
		}
		ex.expNext = now + Config.EXPEDITION_INTERVAL + r.nextInt(12000);
		ref.dirty();
		if (residents.size() < 6 || pool.size() < 3) {
			return;
		}
		Collections.shuffle(pool, new java.util.Random(r.nextLong()));
		int count = 1 + (residents.size() >= 10 ? 1 : 0) + (residents.size() >= 18 ? 1 : 0);
		List<Villager> crew = pool.subList(0, Math.min(count, pool.size()));
		int levelSum = 0;
		StringBuilder ids = new StringBuilder();
		for (Villager v : crew) {
			levelSum += Skills.level(v.getAttachedOrCreate(VillagerPlus.MEMORY));
			ids.append(ids.length() == 0 ? "" : ",").append(v.getUUID());
		}
		int tier = Math.max(1, Math.min(3, 1 + levelSum / crew.size() / 2 + (r.nextInt(100) < 30 ? 1 : 0)));
		int dest = tier - 1;
		long end = now + Config.EXPEDITION_TICKS + r.nextInt(3000);
		ex.expActive = java.util.Optional.of(ids + ";" + end + ";" + dest);
		stat(ex, 0, 1);
		StringBuilder names = new StringBuilder();
		int index = 0;
		for (Villager v : crew) {
			names.append(names.length() == 0 ? "" : ", ").append(nameOf(v));
			goAway(level, ref, v, end, index++, now);
		}
		level.playSound(null, ref.bell(), net.minecraft.sounds.SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.BLOCKS, 1.2F, 0.8F);
		ref.dirty();
		JobUtil.tellNearbyPlayers(level, crew.get(0), 60, Component.literal("Expedition: " + names + " brechen auf nach " + DESTS[dest].name() + "."));
	}

	/** Aufbruch: Der Abenteurer verlaesst das Dorf zu Fuss (Dorfrand) und verschwindet erst dort. */
	private static void goAway(ServerLevel level, VillageRef ref, Villager v, long seed, int index, long now) {
		BlockPos exit = exitPoint(level, ref.bell(), seed, index);
		v.addTag(LEAVING);
		v.stopSleeping();
		v.setInvulnerable(true);
		WALKS.put(v.getUUID(), new Walk(exit, ref.bell(), true, now + 1800, level.dimension()));
	}

	/** Heimkehr: Der Abenteurer taucht am Dorfrand auf und geht sichtbar zur Dorfkiste. */
	private static void comeBack(ServerLevel level, VillageRef ref, Villager v, long seed, int index, boolean walkHome) {
		v.removeTag(TAG);
		v.removeTag(LEAVING);
		WALKS.remove(v.getUUID());
		v.setNoAi(false);
		v.setInvisible(false);
		v.setInvulnerable(false);
		v.setSilent(false);
		v.setNoGravity(false);
		BlockPos home = ref.chestPos(level) != null ? ref.chestPos(level) : ref.bell();
		BlockPos exit = walkHome ? exitPoint(level, ref.bell(), seed, index) : home;
		v.teleportTo(exit.getX() + 0.5, exit.getY() + 1.0, exit.getZ() + 0.5);
		v.resetFallDistance();
		if (walkHome && !exit.equals(home)) {
			v.addTag(RETURNING);
			WALKS.put(v.getUUID(), new Walk(home, ref.bell(), false, level.getGameTime() + 2400, level.dimension()));
			level.playSound(null, exit, net.minecraft.sounds.SoundEvents.VILLAGER_YES, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
		}
	}

	private static void finish(ServerLevel level, VillageRef ref, String[] parts, long now) {
		VillageData.Extra ex = ref.data().extra;
		RandomSource r = level.getRandom();
		int dest = (int) Math.max(0, Math.min(2, parseLong(parts[2])));
		int tier = DESTS[dest].tier();
		List<String> home = new ArrayList<>();
		List<String> fallen = new ArrayList<>();
		int loot = 0;
		StringBuilder lootText = new StringBuilder();
		Villager any = null;
		int crewIndex = -1;
		for (String id : parts[0].split(",")) {
			crewIndex++;
			Entity e = level.getEntity(UUID.fromString(id));
			if (!(e instanceof Villager v) || !v.isAlive()) {
				fallen.add("unbekannt");
				continue;
			}
			any = v;
			VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			int skill = Skills.level(mem);
			// Gefahr: je Stufe 3-10 Schadenspunkte, Hinterhalt (8 % je Stufe) verdoppelt sie; Erfahrung und Fertigkeit mildern.
			double danger = tier * (3 + r.nextInt(8));
			if (r.nextInt(100) < 8 * tier) {
				danger *= 2.0;
			}
			float damage = (float) (danger * (1.0 - 0.08 * skill) * (0.4 + r.nextDouble() * 0.6));
			String name = nameOf(v);
			if (v.getHealth() - damage <= 0.0F) {
				comeBack(level, ref, v, parseLong(parts[1]), crewIndex, false);
				fallen.add(name);
				stat(ex, 2, 1);
				v.kill(level);
				continue;
			}
			v.setHealth(v.getHealth() - damage);
			comeBack(level, ref, v, parseLong(parts[1]), crewIndex, true);
			mem.jobsDone += 5 * tier;
			home.add(name + " (" + Math.round(v.getHealth()) + "/" + Math.round(v.getMaxHealth()) + " ♥)");
			stat(ex, 1, 1);
			int rolls = 1 + tier + (skill >= 3 ? 1 : 0);
			for (int i = 0; i < rolls; i++) {
				ItemStack drop = roll(LOOT[dest], r);
				loot += drop.getCount();
				lootText.append(lootText.length() == 0 ? "" : ", ").append(drop.getCount()).append("x ").append(drop.getHoverName().getString());
				deposit(level, ref, drop);
			}
		}
		stat(ex, 3, loot);
		String line = "Tag " + (now / 24000L) + ": " + DESTS[dest].name() + " – zurück: " + (home.isEmpty() ? "niemand" : String.join(", ", home))
			+ (fallen.isEmpty() ? "" : "; nicht zurückgekehrt: " + String.join(", ", fallen))
			+ (lootText.length() == 0 ? "; keine Beute" : "; Beute: " + lootText);
		ex.expLog.add(line);
		while (ex.expLog.size() > 6) {
			ex.expLog.remove(0);
		}
		ex.expActive = java.util.Optional.empty();
		ref.dirty();
		if (any != null) {
			JobUtil.tellNearbyPlayers(level, any, 60, Component.literal("Expedition beendet: " + line));
		}
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Villagers: Repurposed: Expedition beendet – {}", line);
	}

	// --- Beute ------------------------------------------------------------------------------------

	private static ItemStack roll(Loot[] table, RandomSource r) {
		int total = 0;
		for (Loot l : table) {
			total += l.weight();
		}
		int pick = r.nextInt(total);
		for (Loot l : table) {
			pick -= l.weight();
			if (pick < 0) {
				return new ItemStack(l.item(), l.min() + r.nextInt(l.max() - l.min() + 1));
			}
		}
		return new ItemStack(table[0].item(), 1);
	}

	/** Beute in die Dorfkiste, bei Bedarf ins Lager, sonst vor die Kiste. */
	private static void deposit(ServerLevel level, VillageRef ref, ItemStack stack) {
		ItemStack rest = StockPolicy.insertVillage(level, ref, stack);
		if (!rest.isEmpty()) {
			BlockPos at = ref.chestPos(level) != null ? ref.chestPos(level) : ref.bell();
			net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(level, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, rest);
			level.addFreshEntity(item);
		}
	}

	// --- Hilfen / Anzeige -----------------------------------------------------------------------

	private static String nameOf(Villager v) {
		String n = v.getAttachedOrCreate(VillagerPlus.MEMORY).name;
		return (n.isEmpty() ? "Ein Dorfbewohner" : n) + " [" + Names.profession(v.getVillagerData().profession()) + ", Stufe "
			+ Skills.level(v.getAttachedOrCreate(VillagerPlus.MEMORY)) + "]";
	}

	private static void stat(VillageData.Extra ex, int index, int add) {
		while (ex.expStats.size() < 4) {
			ex.expStats.add(0);
		}
		ex.expStats.set(index, ex.expStats.get(index) + add);
	}

	private static long parseLong(String s) {
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	/** Zeilen fuer den Tab "Expeditionen" der Dorftafel. */
	public static List<String> lines(ServerLevel level, VillageRef ref, long now) {
		VillageData.Extra ex = ref.data().extra;
		List<String> out = new ArrayList<>();
		int started = ex.expStats.size() > 0 ? ex.expStats.get(0) : 0;
		int back = ex.expStats.size() > 1 ? ex.expStats.get(1) : 0;
		int died = ex.expStats.size() > 2 ? ex.expStats.get(2) : 0;
		int loot = ex.expStats.size() > 3 ? ex.expStats.get(3) : 0;
		out.add("Expeditionen: " + started + " gestartet, " + back + " Heimkehrer, " + died + " Gefallene, " + loot + " Beutestücke");
		if (ex.expActive.isPresent()) {
			String[] parts = ex.expActive.get().split(";");
			int dest = parts.length > 2 ? (int) Math.max(0, Math.min(2, parseLong(parts[2]))) : 0;
			long left = Math.max(0, (parseLong(parts[1]) - now) / 20 / 60);
			out.add("Unterwegs nach " + DESTS[dest].name() + " (Gefahr Stufe " + DESTS[dest].tier() + "), Rückkehr in ca. " + left + " Min.:");
			for (String id : parts[0].split(",")) {
				Entity e = level.getEntity(UUID.fromString(id));
				if (e instanceof Villager v && v.isAlive()) {
					String state = v.entityTags().contains(LEAVING) ? " – verlässt gerade das Dorf" : v.entityTags().contains(RETURNING) ? " – kehrt heim" : "";
					out.add("  " + nameOf(v) + " – Gesundheit " + Math.round(v.getHealth()) + "/" + Math.round(v.getMaxHealth()) + " ♥" + state);
				}
			}
		} else {
			long wait = Math.max(0, (ex.expNext - now) / 20 / 60);
			out.add("Keine Expedition unterwegs. Die nächste bricht frühestens in ca. " + wait + " Min. auf (nur mit mind. 6 Einwohnern).");
		}
		if (ex.expLog.isEmpty()) {
			out.add("Noch keine Berichte.");
		} else {
			out.add("Letzte Berichte:");
			for (int i = ex.expLog.size() - 1; i >= 0; i--) {
				out.add("  " + ex.expLog.get(i));
			}
		}
		return out;
	}
}
