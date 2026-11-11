package de.villagerplus;

import com.mojang.serialization.Codec;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.Mining;
import de.villagerplus.job.WoodcutterWork;
import de.villagerplus.net.Networking;
import de.villagerplus.village.Caravan;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Ownership;
import de.villagerplus.village.VillageData;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

public class VillagerPlus implements ModInitializer {
	public static final String MOD_ID = "villagerplus";

	public static final AttachmentType<VillagerMemory> MEMORY = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(MOD_ID, "memory"),
		builder -> builder.persistent(VillagerMemory.CODEC).initializer(VillagerMemory::new));

	/** Persists a resident's unfulfilled private-chest provisioning request across entity unloads and restarts. */
	public static final AttachmentType<Boolean> PRIVATE_CHEST_PENDING = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "private_chest_pending"), Codec.BOOL);

	/** Persists a miner's active one-cell route so interrupted visits can safely return after reload. */
	public static final AttachmentType<Mining.MineRoute> MINING_ROUTE = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "mining_route"), Mining.MineRoute.CODEC);

	/** Reversible one-day emergency job assignment, persisted separately from villager memory to keep its codec bounded. */
	public static final AttachmentType<de.villagerplus.village.TemporaryAssignment> TEMPORARY_JOB = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(MOD_ID, "temporary_job"),
		builder -> builder.persistent(de.villagerplus.village.TemporaryAssignment.CODEC).initializer(de.villagerplus.village.TemporaryAssignment::empty));

	/** Persisted school experience and planned profession for each child. */
	public static final AttachmentType<de.villagerplus.job.Apprentices.Progress> APPRENTICE = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(MOD_ID, "apprentice"),
		builder -> builder.persistent(de.villagerplus.job.Apprentices.Progress.CODEC).initializer(de.villagerplus.job.Apprentices.Progress::empty));

	/** Dorfdaten (Dorfmitte, Bauauftrag), haengen an der Dorfglocke. */
	public static final AttachmentType<VillageData> VILLAGE = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(MOD_ID, "village"),
		builder -> builder.persistent(VillageData.CODEC).initializer(VillageData::new));

	/** Besitzer (Villager-UUID) einer privaten Kiste. */
	public static final AttachmentType<UUID> OWNER = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "owner"), UUIDUtil.CODEC);

	/** Markiert das Schild der Dorftafel; speichert die Position der zugehoerigen Dorfglocke. */
	public static final AttachmentType<net.minecraft.core.BlockPos> BOARD = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "board"), net.minecraft.core.BlockPos.CODEC);

	/** Gebaeude, die das Dorf selbst errichtet hat (an der Dorfglocke); werden regelmaessig geprueft und ausgebessert. */
	public static final AttachmentType<java.util.List<de.villagerplus.build.Built>> BUILDINGS = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "buildings"), de.villagerplus.build.Built.CODEC.listOf());

	/** Conservative union of original generated and mod-built village bounds; only expands, including across unloads. */
	public static final AttachmentType<de.villagerplus.village.VillageExtent> VILLAGE_EXTENT = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "village_extent"), de.villagerplus.village.VillageExtent.CODEC);

	/** Mayor, environment focus and diplomacy bookkeeping of a village (own attachment because VillageData.Extra is full). */
	public static final AttachmentType<de.villagerplus.village.VillageAdmin.Admin> VILLAGE_ADMIN = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "village_admin"), de.villagerplus.village.VillageAdmin.Admin.CODEC);

	/** Opt-in phase ordering and temporary worksite blocks for the current village construction plan. */
	public static final AttachmentType<de.villagerplus.build.ConstructionSite> CONSTRUCTION = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "construction"), de.villagerplus.build.ConstructionSite.CODEC);

	/**
	 * Origins of plans that are built from a shipped template. The choice is persisted so a plan keeps its shape across
	 * restarts and repairs, while plans started before templates existed keep their old design.
	 */
	public static final AttachmentType<java.util.List<Long>> TEMPLATE_PLANS = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "template_plans"), com.mojang.serialization.Codec.LONG.listOf());

	/** Handelsnetz des Dorfes (Partnerorte, Nachbardoerfer, Abklingzeiten); eigenes Attachment, weil Extra voll ist. */
	public static final AttachmentType<de.villagerplus.village.TradeNetwork> NETWORK = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "network"), de.villagerplus.village.TradeNetwork.CODEC);

 public static final AttachmentType<de.villagerplus.village.MarketData> MARKET = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"market"),de.villagerplus.village.MarketData.CODEC);
 /** Emeralds paid for prepared meals; kept in the village ledger, never in the construction-material depot. */
 public static final AttachmentType<Long> TAVERN_REVENUE = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"tavern_revenue"),com.mojang.serialization.Codec.LONG);
 public static final AttachmentType<Long> LAST_SHOP = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"last_shop"),com.mojang.serialization.Codec.LONG);

 public static final AttachmentType<de.villagerplus.village.Journal.Data> JOURNAL = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"journal"),de.villagerplus.village.Journal.Data.CODEC);

 /** Pflicht-Meilensteine des Dorfes (Kornspeicher, Werkzeugkammer, Dorfkueche, spaeter Bahnhof): Typ -> Ursprung. */
 public static final AttachmentType<java.util.Map<String,net.minecraft.core.BlockPos>> SPECIALS = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"specials"),com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,net.minecraft.core.BlockPos.CODEC));

	/** Persistent marker and pending charcoal output claim for a furnace placed by the village kitchen furnisher. */
	public static final AttachmentType<Integer> COMMUNAL_FURNACE = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "communal_furnace"), com.mojang.serialization.Codec.INT);

	/** Expected output for the single in-flight smithing batch; EMPTY means the marked furnace is available. */
	public static final AttachmentType<net.minecraft.world.item.ItemStack> SMITH_FURNACE = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "smith_furnace"), net.minecraft.world.item.ItemStack.OPTIONAL_CODEC);

 /** Vom Spieler eingestellte Berufsquoten des Dorfes (Beruf -> Prozent der Einwohner); fehlende Eintraege nutzen die Standardanteile. */
 public static final AttachmentType<java.util.Map<String,Integer>> QUOTAS = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"quotas"),com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,com.mojang.serialization.Codec.INT));

 /** Per-item communal storage targets; missing item IDs mean unlimited stock. */
 public static final AttachmentType<java.util.Map<String,Integer>> STOCK_POLICIES = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"stock_policies"),com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,com.mojang.serialization.Codec.INT));

 /** Persistent user-defined rectangular management zones per village. */
 public static final AttachmentType<java.util.List<de.villagerplus.village.ZonePolicy.Zone>> ZONES = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"zones"),de.villagerplus.village.ZonePolicy.Zone.CODEC.listOf());

 /** Laufende Abrissauftraege des Dorfes (siehe Demolition). */
 public static final AttachmentType<java.util.List<de.villagerplus.village.Demolition.Job>> DEMOLITION = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"demolition"),de.villagerplus.village.Demolition.Job.CODEC.listOf());

 /** Vom Spieler vergebener Dorfname (ueberschreibt den prozeduralen Namen). */
 public static final AttachmentType<String> VILLAGE_NAME = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"village_name"),com.mojang.serialization.Codec.STRING);

 public static final AttachmentType<java.util.Map<String,Long>> ROADS = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"roads"),com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,com.mojang.serialization.Codec.LONG));

	/** Game time of the villager's last prepared meal. Kept off VillagerMemory because that codec is at its 16-field limit. */
	public static final AttachmentType<Long> PREPARED_MEAL = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "prepared_meal"), com.mojang.serialization.Codec.LONG);

	/** Last in-game day the village bell signaled the start of work. */
	public static final AttachmentType<Long> LAST_MORNING_BELL = AttachmentRegistry.createPersistent(
  Identifier.fromNamespaceAndPath(MOD_ID,"last_morning_bell"),com.mojang.serialization.Codec.LONG);

	/** Persistent cursor and pending drops for a woodcutter's in-flight tree. */
	public static final AttachmentType<WoodcutterWork> WOODCUTTER_WORK = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(MOD_ID, "woodcutter_work"),
		builder -> builder.persistent(WoodcutterWork.CODEC).initializer(WoodcutterWork::empty));

	/** Camp blocks from an interrupted caravan visit, retained until safe cleanup succeeds. */
	public static final AttachmentType<java.util.List<de.villagerplus.village.CaravanCampData.CampBlock>> CARAVAN_CAMPS = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(MOD_ID, "caravan_camps"), de.villagerplus.village.CaravanCampData.CODEC);

	@Override
	public void onInitialize() {
		de.villagerplus.entity.ModEntities.initialize();
		de.villagerplus.village.Anchor.register();
		de.villagerplus.build.Wall.register();
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> de.villagerplus.village.Villages.noteChunk(chunk));
		de.villagerplus.block.ModBlocks.initialize();
		de.villagerplus.block.ModItems.initialize();
		de.villagerplus.village.Network.register();
		de.villagerplus.village.Expedition.register();
		de.villagerplus.village.Market.register();
		de.villagerplus.village.Market.registerPlayerShop();
		de.villagerplus.job.BuildHaul.register();
		JobManager.register();
		Interactions.register();
		Ownership.register();
		Networking.register();
		de.villagerplus.village.StockPolicy.register();
		de.villagerplus.village.ZonePolicy.register();
		StatusCommand.register();
		Caravan.register();
		Guests.register();
		de.villagerplus.village.RailTransport.register();
		de.villagerplus.dev.AutoTest.register();
		de.villagerplus.village.Board.register();
	}
}
