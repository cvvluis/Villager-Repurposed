package de.villagerplus.village;

import de.villagerplus.Config;

import de.villagerplus.VillagerMemory;

import net.minecraft.core.BlockPos;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.Container;

import net.minecraft.world.entity.npc.villager.Villager;

import net.minecraft.world.item.Items;

import net.minecraft.world.level.block.ChestBlock;

import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**

 * Befinden eines Dorfbewohners. Die Werte werden aus echten Ereignissen abgeleitet (letzte Mahlzeit, letzter Schlaf,

 * letztes Gespraech, Gesundheit, Haus, Besitz) und wirken wirklich:

 * <ul>

 * <li>Arbeitstempo: schlechte Stimmung = langsamer arbeiten ({@link #workQuarters})</li>

 * <li>Hunger: wer sehr hungrig ist, isst zwischendurch einen Happen aus dem Inventar</li>

 * <li>Preise: gluecklich = kleiner Rabatt, elend = Aufschlag (Mixin auf die Sonderpreise)</li>

 * <li>Handel: voellig erschoepft = verweigert den Handel</li>

 * </ul>

 */

public final class Wellbeing {

	/** Ticks bis die Energie von 100 auf 0 faellt (nach dem Aufwachen). */

	public static final int ENERGY_TICKS = 20000;

	/** Ticks bis Soziales von 100 auf 0 faellt (nach dem letzten Gespraech). */

	public static final int SOCIAL_TICKS = 15000;

	public record Status(int satiety, int energy, int social, int health, int mood, int emeralds, boolean home, boolean chest) {

		public String moodLabel() {

			if (mood >= 80) {

				return "Fröhlich";

			} else if (mood >= 60) {

				return "Zufrieden";

			} else if (mood >= 40) {

				return "Neutral";

			} else if (mood >= 20) {

				return "Bedrückt";

			}

			return "Elend";

		}

	}

	private static final java.util.Map<java.util.UUID, long[]> EMERALD_CACHE = new java.util.HashMap<>();

	private Wellbeing() {

	}

	/** Cache-Eintrag eines entladenen Dorfbewohners entfernen. */

	public static void forget(java.util.UUID id) {

		EMERALD_CACHE.remove(id);

	}

	private static int clamp(double v) {

		return (int) Math.max(0, Math.min(100, Math.round(v)));

	}

	public static Status of(ServerLevel level, Villager v, VillagerMemory mem) {

		long now = level.getGameTime();

		int satiety = mem.lastMeal == 0 ? 80 : clamp(100.0 - 100.0 * (now - mem.lastMeal) / Config.HUNGER_AFTER);

		int energy = mem.lastSleep == 0 ? 80 : clamp(100.0 - (double) (now - mem.lastSleep) / (ENERGY_TICKS / 100.0));

		int social = mem.lastChat == 0 ? 70 : clamp(100.0 - (double) (now - mem.lastChat) / (SOCIAL_TICKS / 100.0));

		int health = clamp(100.0 * v.getHealth() / v.getMaxHealth());

		boolean home = mem.homeBed.isPresent();

		// Das Zaehlen der Smaragde (Inventar + Hauskiste) ist der teuerste Teil: nur alle 100 Ticks neu, sonst aus dem Cache.

		long[] cached = EMERALD_CACHE.get(v.getUUID());

		int emeralds;

		boolean chest;

		if (cached != null && now < cached[0]) {

			emeralds = (int) cached[1];

			chest = cached[2] != 0;

		} else {

			emeralds = JobCount.emeralds(v.getInventory());

			chest = false;

			if (mem.homeChest.isPresent()) {

				BlockPos p = mem.homeChest.get();

				if (level.hasChunkAt(p) && level.getBlockState(p).getBlock() instanceof ChestBlock) {

					chest = true;

					Container box = HopperBlockEntity.getContainerAt(level, p);

					if (box != null) {

						emeralds += JobCount.emeralds(box);

					}

				}

			}

			EMERALD_CACHE.put(v.getUUID(), new long[] {now + 100L, emeralds, chest ? 1 : 0});

		}

		double mood = 0.30 * satiety + 0.25 * energy + 0.20 * social + 0.15 * (home ? 100 : 0) + 0.10 * health + Math.min(10, emeralds / 2.0);
		long prepared = v.getAttachedOrElse(de.villagerplus.VillagerPlus.PREPARED_MEAL, 0L);
		if (prepared != 0 && now >= prepared && now - prepared < 6000L) mood += 12;

		VillageRef village = Villages.find(level, v);

		if (village != null) mood += Network.moodBonus(level, village);

		return new Status(satiety, energy, social, health, clamp(mood), emeralds, home, chest);

	}

	/** Von 4 Arbeitsabschnitten arbeitet der Dorfbewohner so viele (4 = volles Tempo). */

	public static int workQuarters(Status s) {
		return workQuarters(s, false);
	}

	/** Hunger begrenzt das Tempo direkt; eine gekochte Mahlzeit gibt einen zusätzlichen Arbeitsabschnitt. */
	public static int workQuarters(Status s, boolean preparedMeal) {
		int pace;
		if (s.mood() >= 50) pace = 4;
		else if (s.mood() >= 35) pace = 3;
		else if (s.mood() >= 20) pace = 2;
		else pace = 1;
		if (s.satiety() < 25) pace = Math.min(pace, 2);
		if (preparedMeal) pace = Math.min(4, pace + 1);
		return pace;
	}

	/** Preisaenderung fuer Handelsangebote in Prozent (negativ = Rabatt). */

	public static double priceModifier(Status s) {

		if (s.mood() >= 80) {

			return -0.05;

		} else if (s.mood() < 25) {

			return 0.10;

		}

		return 0.0;

	}

	/** Voellig erschoepft/hungrig: der Dorfbewohner will gerade nicht handeln. */

	public static boolean refusesTrade(Status s) {

		return s.mood() < 10;

	}

	/** Hilfsklasse zum Zaehlen von Smaragden. */

	private static final class JobCount {

		static int emeralds(Container c) {

			int n = 0;

			for (int i = 0; i < c.getContainerSize(); i++) {

				if (c.getItem(i).is(Items.EMERALD)) {

					n += c.getItem(i).getCount();

				}

			}

			return n;

		}

	}

}

