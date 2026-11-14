package de.villagerplus;

/** Zentrale Stellschrauben des Mods. */
public final class Config {
	private Config() {
	}

	/** Alle wie viele Ticks ein Dorfbewohner seinen Job "denkt". */
	public static final int JOB_INTERVAL = 10;
	/** Alle wie viele Ticks Name/Haus/Schild eines Dorfbewohners geprueft werden. */
	public static final int HOUSING_INTERVAL = 200;
	/** Berufe anderer Mods ersetzen ihr Vanilla-Gegenstueck (z. B. ein Mod-Bauer den Vanilla-Bauern), falls es sie gibt. */
	public static final boolean PREFER_MODDED_PROFESSIONS = true;
	/** Dorffest: alle N Spieltage, ab dieser Einwohnerzahl. */
	public static final int FESTIVAL_PERIOD_DAYS = 7;
	public static final int FESTIVAL_MIN_POP = 8;
	/** Zeitbudget (Nanosekunden) fuer Villager-Takte pro Spiel-Tick; der Rest wird im naechsten Tick fortgesetzt. */
	public static final long TICK_BUDGET_NANOS = 2_000_000L;

	/** Gedaechtnis: Das Vanilla-Vergessen (Gossip-Decay) passiert nur jeden N-ten Tag. 4 = 4x laengeres Gedaechtnis. */
	public static final int GOSSIP_DECAY_DIVISOR = 4;
	public static final int MEMORY_MAX_CONTAINERS = 6;
	public static final int MEMORY_MAX_WORK_SPOTS = 24;

	/**
	 * Tagesplan (Ticks seit Sonnenaufgang, 0..24000):
	 * Morgen (Fruehstueck zu Hause, Abliefern) - Arbeit - Pause - Arbeit - Mittag - Arbeit - Pause - Arbeit - Abend (Essen, Heimkehr).
	 */
	public static final int MORNING_END = 1000;
	public static final int WORK_A1_END = 2800;
	public static final int BREAK_A_END = 3300;
	public static final int WORK_A_END = 5000;
	public static final int LUNCH_END = 6500;
	public static final int WORK_B1_END = 8300;
	public static final int BREAK_B_END = 8800;
	public static final int WORK_B_END = 10500;
	public static final int EVENING_END = 12000;

	/** Hunger: Wer so lange (Ticks) nichts gegessen hat, arbeitet nur noch halb so schnell. */
	public static final long HUNGER_AFTER = 30000L;
	/** Gespraech in der Pause: Dauer in Ticks und Abstand bis zum naechsten Gespraech. */
	public static final int CHAT_DURATION = 200;
	public static final int CHAT_COOLDOWN = 100;
	public static final int CHAT_RADIUS = 24;

	/** Privateigentum: jeder Villager bekommt eine eigene Kiste in seinem Haus; alle N erledigten Aufgaben gibt es einen Smaragd Lohn. */
	public static final boolean PRIVATE_CHESTS = true;
	public static final int JOBS_PER_WAGE = 80;

	/** Wieviel Prozent der Bloecke eines Hauses wirklich aus der Dorfkiste bezahlt werden muessen (100 = alles, 0 = gratis). */
	public static final int MATERIAL_COST_PERCENT = 100;

	/** Bauer. */
	public static final int FARM_RADIUS = 9;
	public static final int FARM_DEPOSIT_THRESHOLD = 24;
	public static final int FARM_KEEP = 12;
	public static final int FARM_MIN_SEEDS = 8;
	public static final int MAX_FARMS = 6;

	/** Schaefer. */
	public static final int SHEEP_RADIUS = 14;
	public static final int WOOL_DEPOSIT_THRESHOLD = 12;

	/** Metzger: ab wie vielen erwachsenen Tieren einer Art er welche schlachtet. */
	public static final int CULL_RADIUS = 16;
	public static final int CULL_CAP = 8;

	/** Fischer. */
	public static final int FISH_RADIUS = 12;
	public static final int FISH_INTERVAL = 300;

	/** Schmiede: Golem-Reparatur. */
	public static final int GOLEM_RADIUS = 24;
	public static final float GOLEM_HEAL_WITH_INGOT = 25.0F;
	public static final float GOLEM_HEAL_SCRAP = 5.0F;
	public static final int GOLEM_SCRAP_COOLDOWN = 600;

	/** Kleriker. */
	public static final int CLERIC_RADIUS = 16;
	public static final int CLERIC_COOLDOWN = 300;

	/** Traeger (Arbeitslose/Dorftrottel): sammeln Items auf, die schon so lange herumliegen. */
	public static final int PORTER_RADIUS = 24;
	public static final int PORTER_MIN_ITEM_AGE = 6000;

	/** Dorfausbau. */
	public static final int VILLAGE_RADIUS = 96;
	public static final int MAX_VILLAGERS = 128;
	/** Paid blocks per scheduled builder visit; raises progress without extra workers or pathfinding calls. */
	/**
	 * Dorf-Tempo: ein einziger Regler fuer die Entwicklungsgeschwindigkeit der Doerfer (1 = urspruenglich).
	 * Er skaliert nur Baufortschritt pro Besuch und die Pause zwischen zwei Projekten, nie die globale Tickrate.
	 */
	public static final int VILLAGE_TEMPO = 3;
	public static final int BUILD_BLOCKS_PER_STEP = 12 * VILLAGE_TEMPO;
	public static final long BUILD_COOLDOWN = 6000L / VILLAGE_TEMPO;
	public static final int CHEST_SEARCH_RADIUS = 12;
	public static final int TORCH_COOLDOWN = 400;

	/** Stadtentwicklung: ab dieser Einwohnerzahl entstehen Tiergehege, Rathaus und Stadtmauer. */
	public static final int PEN_POP = 4;
	public static final int TOWNHALL_POP = 12;
	/** Doerfer (und ihre aktive Mine) dauerhaft laden, damit sie auch ohne Spieler in der Naehe weiterarbeiten. */
	public static final boolean KEEP_VILLAGES_LOADED = true;
	/** Radius in Chunks um die Glocke, der geladen bleibt (3 = 7x7 Chunks, rund 100 Bloecke Kantenlaenge). */
	public static final int ANCHOR_RADIUS_CHUNKS = 3;
	/** Abstand zwischen Expeditionen (Ticks) und ihre Dauer. */
	public static final long EXPEDITION_INTERVAL = 36000L;
	public static final long EXPEDITION_TICKS = 9000L;
	/** Kinder wachsen so viel langsamer auf (3 = dreimal so lange wie in Vanilla). */
	public static final int GROWTH_FACTOR = 3;
	/** Ab dieser Einwohnerzahl baut jedes Dorf eine kleine Schule fuer die Kinder. */
	public static final int SCHOOL_POP = 5;
	/** Ab dieser Einwohnerzahl baut das Dorf einen Lagerschuppen fuer Ueberschuss. */
	public static final int STORAGE_POP = 8;
	public static final int WALL_POP = 36;
	/** Stadtmauer nur, wenn die Haeuser nicht zu eng (Mindestradius) und nicht zu weit verstreut (Ring <= Maximalradius) stehen. */
	public static final int WALL_MIN_VILLAGE_RADIUS = 26;
	public static final int WALL_MAX_RADIUS = 64;

	/** Handelskarawane (wandernder Haendler): Abstand zwischen zwei Besuchen und Mindesteinwohner. */
	public static final long CARAVAN_INTERVAL = 36000L;
	public static final int CARAVAN_MIN_POP = 5;
	public static final int ANIMAL_TARGET = 4;
}
