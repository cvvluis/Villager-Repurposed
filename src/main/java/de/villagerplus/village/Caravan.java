package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Builder;
import de.villagerplus.build.PlanSupplyOrder;
import de.villagerplus.build.Supply;

import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Ctx;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.HashSet;

import java.util.Iterator;

import java.util.List;

import java.util.Map;

import java.util.Optional;

import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;

import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.network.chat.Component;

import net.minecraft.resources.Identifier;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.Container;

import net.minecraft.world.entity.Entity;

import net.minecraft.world.entity.EntitySpawnReason;

import net.minecraft.world.entity.EntityType;

import net.minecraft.world.entity.EntityTypes;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.Display.ItemDisplay;

import net.minecraft.world.entity.npc.villager.Villager;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import de.villagerplus.entity.CaravanTraderEntity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;

import net.minecraft.world.item.ItemStack;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**

 * Handelskarawane: Alle paar Tage kommt ein wandernder Haendler ins Dorf und tauscht mit einem "imaginaeren" Dorf

 * Ressourcen. Er kauft Ueberschuss aus der Dorfkiste (Erde, Kies, Bruchstein ueber dem Bedarf, Erze, Nahrung, Wolle ...)

 * gegen Smaragde, verkauft dafuer Fehlendes von der Wunschliste (Baumaterial), Saatgut, Setzlinge und Fackeln und liefert

 * die ersten Tiere fuer das Gehege.

 */

public final class Caravan {

	static final class State {

		final UUID trader;

		final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;

		final BlockPos bell, spawn;

		final int partner;

		final List<UUID> llamas = new ArrayList<>(); // beladene Esel (Feldname historisch)

		final List<BlockPos> camp = new ArrayList<>();

		UUID guard;
		UUID armedGuardVisual;

		final List<net.minecraft.world.level.block.state.BlockState> campBlocks = new ArrayList<>();

		boolean campBuilt;
		long campRetryAt;
		boolean guardDutyEnded;
		boolean armedGuardRemoved;

		final long born;

		boolean traded;

		long leaveAt = Long.MAX_VALUE;

		long nextUnload;

		long nextDonkeyFollowAt;
		boolean waitingForDonkeys;
		/** Recomputed on each follow retry; never let the trader progress if a loaded pack animal has no usable path. */
		boolean departurePathReady = true;

		int uses, reward;
		String roadKey;
		int roadCursor = -1;
		/** Real linked village from which this caravan enters; null means a legacy name-only partner visit. */
		String routeDimension;
		BlockPos routeBell;
		int routeRelationDelta;
		long routeVisitDay = -1L;

		State(AbstractVillager trader, ServerLevel level, VillageRef ref, BlockPos spawn, int partner) {

			this.trader = trader.getUUID(); this.dimension = level.dimension(); this.bell = ref.bell();

			this.spawn = spawn; this.partner = partner; this.born = level.getGameTime();

		}

		State(WanderingTrader trader, ServerLevel level, VillageRef ref, BlockPos spawn, int partner) {
			this((AbstractVillager) trader, level, ref, spawn, partner);
		}

		State(UUID trader, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			BlockPos bell, BlockPos spawn, int partner, long born) {
			this.trader = trader; this.dimension = dimension; this.bell = bell.immutable();
			this.spawn = spawn.immutable(); this.partner = partner; this.born = born;
		}

	}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();
	private static final String CARAVAN_TAG = "vp_caravan";
	private static final String BELL_TAG_PREFIX = "vp_caravan_bell:";
	private static final String RECOVERY_TAG = "vp_caravan_recovery_v1";
	private static final String RECOVERY_SPAWN_PREFIX = "vp_caravan_recovery_spawn:";
	private static final String RECOVERY_PARTNER_PREFIX = "vp_caravan_recovery_partner:";
	private static final String RECOVERY_BORN_PREFIX = "vp_caravan_recovery_born:";
	private static final String RECOVERY_LEAVE_PREFIX = "vp_caravan_recovery_leave:";
	private static final String RECOVERY_ROAD_KEY_PREFIX = "vp_caravan_recovery_road_key:";
	private static final String RECOVERY_ROAD_CURSOR_PREFIX = "vp_caravan_recovery_road_cursor:";
	private static final String RECOVERY_ROUTE_DIMENSION_PREFIX = "vp_caravan_recovery_route_dimension:";
	private static final String RECOVERY_ROUTE_BELL_PREFIX = "vp_caravan_recovery_route_bell:";
	private static final String RECOVERY_ROUTE_RELATION_PREFIX = "vp_caravan_recovery_route_relation:";
	private static final String RECOVERY_ROUTE_VISIT_DAY_PREFIX = "vp_caravan_recovery_route_visit_day:";
	static record RouteAssignment(String dimension, BlockPos bell, int relationDelta, long visitDay) { }
	private record PendingCaravanOrphan(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
		BlockPos lastPosition, long retryAt) { }
	private static final Map<UUID, PendingCaravanOrphan> PENDING_CARAVAN_ORPHANS = new HashMap<>();
	private record PendingCaravanAdoption(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
		BlockPos lastPosition, String villageTag, long retryAt) { }
	private record MerchantCargoTake(net.minecraft.world.entity.animal.equine.Donkey donkey, int slot,
		ItemStack original, int count) { }
	private static final Map<UUID, PendingCaravanAdoption> PENDING_CARAVAN_ADOPTIONS = new HashMap<>();
	private static final Set<UUID> UNLOADED_CARAVAN_TRADERS = new HashSet<>();
	/** Bell-keyed camp blocks awaiting cleanup because their chunks are currently unloaded. */
	private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Map<Long, List<CaravanCampData.CampBlock>>> PENDING_CAMP_CLEANUP = new HashMap<>();
	/** Disposable render-only cargo markers; donkey inventory remains the sole source of truth. */
	private static final Map<UUID, UUID> CARGO_VISUALS = new HashMap<>();
	private static final String PORTER_VISUAL_TAG = "vp_caravan_porter_visual";
	private static final Map<UUID, PorterCarry> PORTER_CARRIES = new HashMap<>();
	private static final Map<UUID, UUID> PORTER_CLAIMS = new HashMap<>();
	private static final Map<UUID, String> PORTER_DIAGNOSTICS = new HashMap<>();
	private static String OVERFLOW_PACK_MULE_DIAGNOSTIC = "not attempted";
	private static final String PLAN_FREIGHT_SLOT_PREFIX = "vp_r02_plan_freight_slot:";
	private static final class PorterCarry {
		final UUID donkey, porter;
		UUID visual;
		final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
		final int slot, count;
		final BlockPos bell;
		final ItemStack expected;
		BlockPos target;
		BlockPos donkeyStand;
		boolean pickedUp;
		boolean waitingAtDepot;
		UUID traderLeash;
		long followProgressAt;
		long nextDonkeyRouteAt;
		double followBest = Double.POSITIVE_INFINITY;
		PorterCarry(UUID donkey, UUID porter, UUID visual, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			int slot, ItemStack expected, BlockPos bell, BlockPos target) {
			this.donkey=donkey; this.porter=porter; this.visual=visual; this.dimension=dimension; this.slot=slot;
			this.count=expected.getCount(); this.expected=expected.copy(); this.bell=bell.immutable(); this.target=target.immutable();
		}
	}
	record DeliveryTarget(BlockPos pos, String depot, Container container) {}
	private record PorterDonkeyRoute(BlockPos stand, net.minecraft.world.level.pathfinder.Path path) { }
	private static final Map<UUID, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> CARGO_RECONCILE = new HashMap<>();
	private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long> CARGO_STARTUP_SCAN = new HashMap<>();
	private static final Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> ARMED_GUARD_STARTUP_RECONCILE = new HashSet<>();
	private static final long ARMED_GUARD_RECONCILE_INTERVAL = 20L;
	private static final long ORPHAN_GUARD_RECONCILE_INTERVAL = 1200L;

	private Caravan() {}

	public static void register() {

		ServerTickEvents.END_LEVEL_TICK.register(Caravan::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
			for (var be : chunk.getBlockEntities().values()) {
				if (be instanceof net.minecraft.world.level.block.entity.BellBlockEntity bell) queueOrphanCampCleanup(world, bell);
			}
			processPendingCampCleanups(world);
		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (PorterCarry carry : List.copyOf(PORTER_CARRIES.values())) {
				ServerLevel l=server.getLevel(carry.dimension); if(l!=null) releasePorter(l,carry.porter);
			}

			for (State st : ACTIVE.values()) {
				ServerLevel l = server.getLevel(st.dimension);
				if (l != null) {
					removeCamp(l, st);
					preserveCargo(l, st);
					persistVisitState(l, st);
				}
			}

		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> { ACTIVE.clear(); PENDING_CARAVAN_ORPHANS.clear(); PENDING_CARAVAN_ADOPTIONS.clear(); UNLOADED_CARAVAN_TRADERS.clear(); CARGO_VISUALS.clear(); CARGO_RECONCILE.clear(); PORTER_CARRIES.clear(); PORTER_CLAIMS.clear(); PORTER_DIAGNOSTICS.clear(); PENDING_CAMP_CLEANUP.clear(); ARMED_GUARD_STARTUP_RECONCILE.clear(); });
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				CARGO_STARTUP_SCAN.put(level.dimension(), level.getGameTime() + 20L);
				ARMED_GUARD_STARTUP_RECONCILE.add(level.dimension());
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> CARGO_STARTUP_SCAN.clear());

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			UNLOADED_CARAVAN_TRADERS.remove(entity.getUUID());
			if (entity instanceof AbstractVillager trader && trader.entityTags().contains(CARAVAN_TAG)) {
				trader.setPersistenceRequired();
				if (trader instanceof CaravanTraderEntity custom && custom.visitData() != null) {
					restoreVisitState(level, custom, custom.visitData());
					if (!custom.backedTradeOffersInitialized() || !custom.backedOffersCovered()) custom.disableUnbackedOffers();
				} else if (adoptTaggedCaravan(level, trader)) {
					PENDING_CARAVAN_ADOPTIONS.remove(trader.getUUID());
				} else {
					if (trader instanceof CaravanTraderEntity custom) custom.disableUnbackedOffers();
					else disableUnbackedLegacyOffers(trader);
					queueCaravanAdoption(level, trader);
				}
			}
			if (entity instanceof ItemDisplay display && display.entityTags().contains(PORTER_VISUAL_TAG)
				&& PORTER_CARRIES.values().stream().noneMatch(carry -> display.getUUID().equals(carry.visual))) display.discard();

			if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey)
				&& (donkey.entityTags().contains(CARAVAN_TAG)
					|| donkey.getCustomName() != null && donkey.getCustomName().getString().equals("Karawanen-Frachtesel"))) {
				donkey.setPersistenceRequired();
				if (!ACTIVE.values().stream().anyMatch(state -> state.llamas.contains(donkey.getUUID()))
					&& donkey.getCustomName() == null) {
					donkey.setCustomName(Component.literal("Karawanen-Frachtesel"));
					donkey.setCustomNameVisible(false);
				}
				syncCargoVisual(level, donkey);
				CARGO_RECONCILE.put(donkey.getUUID(), level.dimension());
			}
			if (entity instanceof ItemDisplay display) reconcileCargoDisplay(level, display);
			// Runtime routes are transient; preserve real cargo donkeys but discard empty route leftovers.

			if (entity.entityTags().contains(CARAVAN_TAG) && !ACTIVE.containsKey(entity.getUUID())

				&& ACTIVE.values().stream().noneMatch(st -> st.llamas.contains(entity.getUUID()))) {

				if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey
					|| entity instanceof net.minecraft.world.entity.animal.golem.IronGolem) {
					if (attachTaggedMember(level, entity)) {
						if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) resumePackDonkeyAfterLoad(level, donkey);
						return;
					}
					if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
					// Chunks can load in any order; keep the real entity and its inventory until
					// the tagged merchant has had a chance to resume.
					PENDING_CARAVAN_ORPHANS.put(entity.getUUID(), new PendingCaravanOrphan(level.dimension(), entity.blockPosition().immutable(), level.getGameTime() + 200L));
				} else if (entity instanceof CaravanTraderEntity || entity instanceof WanderingTrader) {
					queueCaravanAdoption(level, (AbstractVillager) entity);
				} else {
					if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
				}
			}
			if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) resumePackDonkeyAfterLoad(level, donkey);

		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CaravanTraderEntity && ACTIVE.containsKey(entity.getUUID())) UNLOADED_CARAVAN_TRADERS.add(entity.getUUID());
			if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) pausePackDonkeyBeforeUnload(level, donkey);
			if (entity instanceof Villager villager) releasePorter(level, villager.getUUID());
			if (entity instanceof ItemDisplay display && display.entityTags().contains(PORTER_VISUAL_TAG)) {
				for (PorterCarry carry : List.copyOf(PORTER_CARRIES.values())) {
					if (display.getUUID().equals(carry.visual)) { releasePorter(level, carry.porter); break; }
				}
			}
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey
				&& entity.level() instanceof ServerLevel level) {
				settleDeadPackDonkey(level, donkey);
				return;
			}
			if (!(entity instanceof CaravanTraderEntity trader) || !(entity.level() instanceof ServerLevel level)) return;
			State state = ACTIVE.get(trader.getUUID());
			for (ItemStack stock : trader.backedTradeStockSnapshot()) {
				ItemStack remaining = stock.copy();
				if (state != null) {
					int packed = packCargo(level, state, remaining);
					if (packed > 0) trader.consumeBackedTradeStock(stock.copyWithCount(packed));
				}
				while (!remaining.isEmpty()) {
					int dropped = Math.min(remaining.getCount(), remaining.getMaxStackSize());
					ItemStack drop = remaining.copyWithCount(dropped);
					var item = new net.minecraft.world.entity.item.ItemEntity(level, trader.getX(), trader.getY() + 0.5, trader.getZ(), drop);
					if (!level.addFreshEntity(item)) {
						org.slf4j.LoggerFactory.getLogger("villagerplus").error(
							"Could not place backed caravan trade stock after merchant death: {}", remaining);
						break;
					}
					trader.consumeBackedTradeStock(drop);
					remaining.shrink(dropped);
				}
			}
		});

		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {

			if (!(world instanceof ServerLevel level) || hand != net.minecraft.world.InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) return net.minecraft.world.InteractionResult.PASS;

			State state = ACTIVE.get(entity.getUUID());

			if (state == null || !player.getItemInHand(hand).is(Items.EMERALD)) return net.minecraft.world.InteractionResult.PASS;

			VillageRef ref = resolve(level, state.bell);

			if (ref != null && validPartner(ref, state) && state.reward < 10) {

				player.getItemInHand(hand).shrink(1); changeRelation(ref, state.partner, 2, -1);

				state.reward += 2;
				persistVisitState(level, state);

				VillageMessages.system(player, Component.literal("Das Partnerdorf bedankt sich für dein Geschenk (+2 Beziehung)."));

			}

			return net.minecraft.world.InteractionResult.SUCCESS_SERVER;

		});

	}

	/**
	 * AFTER_DEATH runs after vanilla dropEquipment: ordinary donkey inventory has already
	 * been spawned as item entities, but vanilla leaves its slots populated. Clear those
	 * duplicate in-entity copies; only PREVENT_EQUIPMENT_DROP stacks need a new drop here.
	 */
	static void settleDeadPackDonkey(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey) {
		State owner = ACTIVE.values().stream().filter(state -> state.dimension.equals(level.dimension())
			&& state.llamas.contains(donkey.getUUID())).findFirst().orElse(null);
		if (owner == null) return;

		for (int slot = 2; slot < 17; slot++) {
			var access = donkey.getSlot(500 + slot);
			if (access == null || access.get().isEmpty()) continue;
			ItemStack cargo = access.get();
			if (net.minecraft.world.item.enchantment.EnchantmentHelper.has(cargo,
				net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
				var drop = new net.minecraft.world.entity.item.ItemEntity(level, donkey.getX(), donkey.getY() + 0.5, donkey.getZ(), cargo.copy());
				if (!level.addFreshEntity(drop)) {
					org.slf4j.LoggerFactory.getLogger("villagerplus").error(
						"Could not recover protected caravan donkey cargo after death; keeping its saved membership: {}", cargo);
					return;
				}
			}
			access.set(ItemStack.EMPTY);
		}
		owner.llamas.remove(donkey.getUUID());
		donkey.removeTag(CARAVAN_TAG);
		persistVisitState(level, owner);
	}

	private static net.minecraft.world.level.block.entity.BellBlockEntity loadedBell(ServerLevel level, BlockPos pos) {
		return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BellBlockEntity bell ? bell : null;
	}

	private static void queueCaravanAdoption(ServerLevel level, AbstractVillager trader) {
		String village = taggedVillage(trader);
		if (village == null) {
			trader.setPersistenceRequired();
			return;
		}
		PENDING_CARAVAN_ADOPTIONS.putIfAbsent(trader.getUUID(), new PendingCaravanAdoption(
			level.dimension(), trader.blockPosition().immutable(), village, level.getGameTime() + 200L));
		trader.setPersistenceRequired();
	}

	private static String taggedVillage(Entity entity) {
		for (String tag : entity.entityTags()) if (tag.startsWith(BELL_TAG_PREFIX)) return tag;
		return null;
	}

	private static BlockPos taggedBell(Entity entity) {
		String tag = taggedVillage(entity);
		if (tag == null) return null;
		try { return BlockPos.of(Long.parseLong(tag.substring(BELL_TAG_PREFIX.length()))).immutable(); }
		catch (NumberFormatException ignored) { return null; }
	}

	private static Long taggedLong(Entity entity, String prefix) {
		for (String tag : entity.entityTags()) if (tag.startsWith(prefix)) {
			try { return Long.parseLong(tag.substring(prefix.length())); }
			catch (NumberFormatException ignored) { return null; }
		}
		return null;
	}

	private static String taggedString(Entity entity, String prefix) {
		for (String tag : entity.entityTags()) if (tag.startsWith(prefix)) return tag.substring(prefix.length());
		return null;
	}

	private static void replaceLongTag(Entity entity, String prefix, long value) {
		for (String tag : List.copyOf(entity.entityTags())) if (tag.startsWith(prefix)) entity.removeTag(tag);
		entity.addTag(prefix + value);
	}

	private static int legacyPartnerIndex(VillageRef ref, AbstractVillager trader) {
		TradeNetwork network = Network.of(ref.bellEntity());
		String name = trader.getCustomName() == null ? "" : trader.getCustomName().getString();
		String partnerName = trader instanceof CaravanTraderEntity custom ? custom.partnerName() : "";
		if (name.startsWith("Händler aus ")) name = name.substring("Händler aus ".length());
		for (int i = 0; i < network.partners().size(); i++) {
			String known = network.partners().get(i).name();
			if ((!partnerName.isBlank() && !partnerName.equalsIgnoreCase("unbekannt") && partnerName.equalsIgnoreCase(known))
				|| (!name.isBlank() && name.equalsIgnoreCase(known))) return i;
		}
		if (trader instanceof CaravanTraderEntity custom) {
			int match = -1;
			for (int i = 0; i < network.partners().size(); i++) if (network.partners().get(i).specialty() == custom.tradeSpecialty()) {
				if (match >= 0) return -1;
				match = i;
			}
			return match;
		}
		return -1;
	}

	private static void disableUnbackedLegacyOffers(AbstractVillager trader) {
		for (var offer : trader.getOffers()) offer.setToOutOfStock();
	}

	/** Adopts marker-only saves without repeating an exchange whose commit state was not persisted. */
	private static boolean adoptTaggedCaravan(ServerLevel level, AbstractVillager trader) {
		if (ACTIVE.containsKey(trader.getUUID())) return true;
		BlockPos bellPos = taggedBell(trader);
		if (bellPos == null || !level.hasChunkAt(bellPos)) return false;
		VillageRef ref = resolve(level, bellPos);
		if (ref == null) return false;

		boolean recovered = trader.entityTags().contains(RECOVERY_TAG);
		BlockPos spawn = recovered && taggedLong(trader, RECOVERY_SPAWN_PREFIX) != null
			? BlockPos.of(taggedLong(trader, RECOVERY_SPAWN_PREFIX)).immutable()
			: trader.blockPosition().immutable();
		Long savedPartner = recovered ? taggedLong(trader, RECOVERY_PARTNER_PREFIX) : null;
		int partner = savedPartner == null ? legacyPartnerIndex(ref, trader)
			: savedPartner >= -1 && savedPartner <= Integer.MAX_VALUE ? savedPartner.intValue() : -1;
		long born = recovered && taggedLong(trader, RECOVERY_BORN_PREFIX) != null
			? taggedLong(trader, RECOVERY_BORN_PREFIX) : level.getGameTime();
		State state = new State(trader.getUUID(), level.dimension(), bellPos, spawn, partner, born);
		restoreRouteAssignment(level, trader, state);
		state.traded = true;
		Long savedLeaveAt = recovered ? taggedLong(trader, RECOVERY_LEAVE_PREFIX) : null;
		state.leaveAt = savedLeaveAt == null ? Long.MAX_VALUE : savedLeaveAt;
		String savedRoadKey = recovered ? taggedString(trader, RECOVERY_ROAD_KEY_PREFIX) : null;
		Long savedRoadCursor = recovered ? taggedLong(trader, RECOVERY_ROAD_CURSOR_PREFIX) : null;
		state.roadKey = savedRoadKey == null || savedRoadKey.isBlank() ? null : savedRoadKey;
		state.roadCursor = savedRoadCursor == null || savedRoadCursor < 0 || savedRoadCursor > Integer.MAX_VALUE
			? -1 : savedRoadCursor.intValue();
		state.uses = trader.getOffers().stream().mapToInt(net.minecraft.world.item.trading.MerchantOffer::getUses).sum();
		var bell = loadedBell(level, bellPos);
		if (bell != null) for (CaravanCampData.CampBlock block : attachedCampBlocks(bell)) {
			state.camp.add(block.pos().immutable());
			state.campBlocks.add(block.expected());
		}
		state.campBuilt = !state.camp.isEmpty() && state.camp.size() == state.campBlocks.size();
		state.guardDutyEnded = state.leaveAt != Long.MAX_VALUE;
		state.armedGuardRemoved = state.leaveAt != Long.MAX_VALUE;
		ACTIVE.put(trader.getUUID(), state);
		attachTaggedMembers(level, state);

		if (trader instanceof CaravanTraderEntity custom) {
			custom.setPersistenceRequired();
			if (!custom.backedTradeOffersInitialized() || !custom.backedOffersCovered()) custom.disableUnbackedOffers();
			BlockPos depot = ref.chestPos(level);
			BlockPos target = depot == null ? null : safeDepotArrivalStand(level, custom, depot);
			if (target == null) target = safePackWaitSpot(level, custom);
			custom.setRouteTarget(target);
		} else {
			// Vanilla offers have no persisted physical stock ledger. Preserve their saved records,
			// but make them unavailable until the caravan has verifiable cargo-backed offers.
			disableUnbackedLegacyOffers(trader);
			if (trader instanceof WanderingTrader wandering) {
				BlockPos depot = ref.chestPos(level);
				BlockPos target = depot == null ? null : safeDepotArrivalStand(level, wandering, depot);
				if (target == null) target = safePackWaitSpot(level, wandering);
				wandering.setWanderTarget(target);
			}
		}
		persistVisitState(level, state);
		PENDING_CARAVAN_ADOPTIONS.remove(trader.getUUID());
		return true;
	}

	private static void processPendingCaravanAdoptions(ServerLevel level) {
		long now = level.getGameTime();
		for (var entry : Map.copyOf(PENDING_CARAVAN_ADOPTIONS).entrySet()) {
			PendingCaravanAdoption pending = entry.getValue();
			if (!pending.dimension().equals(level.dimension()) || now < pending.retryAt()) continue;
			Entity entity = level.getEntity(entry.getKey());
			if (!(entity instanceof AbstractVillager trader) || !trader.entityTags().contains(CARAVAN_TAG)) {
				PENDING_CARAVAN_ADOPTIONS.remove(entry.getKey());
				continue;
			}
			if (adoptTaggedCaravan(level, trader)) continue;
			PENDING_CARAVAN_ADOPTIONS.put(entry.getKey(), new PendingCaravanAdoption(
				pending.dimension(), pending.lastPosition(), pending.villageTag(), now + 200L));
		}
	}

	private static void attachTaggedMembers(ServerLevel level, State state) {
		String village = villageTag(state.bell);
		Entity trader = level.getEntity(state.trader);
		for (Entity member : level.getAllEntities()) {
			if (!member.isAlive() || !member.entityTags().contains(CARAVAN_TAG) || !member.entityTags().contains(village)) continue;
			if (!(member instanceof net.minecraft.world.entity.animal.equine.Donkey
				|| member instanceof net.minecraft.world.entity.animal.golem.IronGolem)) continue;
			if (!state.llamas.contains(member.getUUID())) state.llamas.add(member.getUUID());
			PENDING_CARAVAN_ORPHANS.remove(member.getUUID());
			if (member instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
			if (member instanceof net.minecraft.world.entity.animal.golem.IronGolem) state.guard = member.getUUID();
			if (member instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && trader != null
				&& donkey.getLeashHolder() == null) donkey.setLeashedTo(trader, true);
		}
	}

	private static boolean attachTaggedMember(ServerLevel level, Entity member) {
		String village = taggedVillage(member);
		if (village == null) return false;
		for (State state : ACTIVE.values()) {
			if (!state.dimension.equals(level.dimension()) || !villageTag(state.bell).equals(village)) continue;
			if (!state.llamas.contains(member.getUUID())) state.llamas.add(member.getUUID());
			PENDING_CARAVAN_ORPHANS.remove(member.getUUID());
			if (member instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
			if (member instanceof net.minecraft.world.entity.animal.golem.IronGolem) state.guard = member.getUUID();
			Entity trader = level.getEntity(state.trader);
			if (member instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && trader != null
				&& donkey.getLeashHolder() == null) donkey.setLeashedTo(trader, true);
			persistVisitState(level, state);
			return true;
		}
		return false;
	}

	/** Reset only the follow cadence when a pack animal becomes available again; its route and freight stay entity-backed. */
	static void resumePackDonkeyAfterLoad(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey) {
		for (State state : ACTIVE.values()) {
			if (!state.dimension.equals(level.dimension()) || !state.llamas.contains(donkey.getUUID())) continue;
			state.nextDonkeyFollowAt = 0L;
			return;
		}
	}

	/** Make an active departure wait before its registered donkey becomes unavailable to navigation. */
	static void pausePackDonkeyBeforeUnload(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey) {
		for (State state : ACTIVE.values()) {
			if (!state.dimension.equals(level.dimension()) || !state.llamas.contains(donkey.getUUID())) continue;
			state.nextDonkeyFollowAt = 0L;
			if (departureStarted(state, level.getGameTime())) {
				state.departurePathReady = false;
				state.waitingForDonkeys = true;
			}
			return;
		}
	}

	static void restoreVisitState(ServerLevel level, CaravanTraderEntity trader, CaravanTraderEntity.VisitData data) {
		State active = ACTIVE.get(trader.getUUID());
		if (active != null) {
			// The same server may reload the trader while its runtime state remains active.
			// Reattach loaded pack animals whose vanilla leash holder was lost across the unload.
			attachTaggedMembers(level, active);
			return;
		}
		CaravanTraderEntity.TravelData travel = data.travel();
		CaravanTraderEntity.CampData camp = data.camp();
		int partner = travel.partner();
		State state = new State(trader.getUUID(), level.dimension(), travel.bell(), travel.spawn(), partner,
			travel.born() > 0 ? travel.born() : level.getGameTime());
		restoreRouteAssignment(level, trader, state);
		for (String member : travel.members().stream().limit(32).toList()) {
			try { state.llamas.add(UUID.fromString(member)); } catch (IllegalArgumentException ignored) { }
		}
		try { if (!travel.guard().isBlank()) state.guard = UUID.fromString(travel.guard()); } catch (IllegalArgumentException ignored) { }
		state.camp.addAll(camp.positions().stream().limit(128).map(BlockPos::immutable).toList());
		state.campBlocks.addAll(camp.states().stream().limit(128).toList());
		state.campBuilt = camp.built() && !state.camp.isEmpty() && state.camp.size() == state.campBlocks.size();
		state.campRetryAt = Math.max(level.getGameTime(), camp.retryAt());
		// Temporary armed guards and camp work are recreated from the persisted camp plan.
		state.guardDutyEnded = false;
		state.armedGuardRemoved = false;
		state.traded = travel.traded();
		state.leaveAt = travel.leaveAt();
		state.nextUnload = Math.max(0, travel.nextUnload());
		int loadedUses = trader.getOffers().stream().mapToInt(net.minecraft.world.item.trading.MerchantOffer::getUses).sum();
		state.uses = Math.max(Math.max(0, travel.uses()), loadedUses);
		state.reward = Math.max(0, Math.min(10, travel.reward()));
		state.roadKey = travel.roadKey().isBlank() ? null : travel.roadKey();
		state.roadCursor = Math.max(-1, travel.roadCursor());
		ACTIVE.put(trader.getUUID(), state);
		attachTaggedMembers(level, state);
		VillageRef ref = resolve(level, state.bell);
		BlockPos depot = ref == null ? null : ref.chestPos(level);
		BlockPos nextTarget = state.traded && state.leaveAt != Long.MAX_VALUE ? state.spawn
			: depot == null ? safePackWaitSpot(level, trader) : safeDepotArrivalStand(level, trader, depot);
		if (nextTarget == null) nextTarget = safePackWaitSpot(level, trader);
		trader.setRouteTarget(nextTarget);
		persistVisitState(level, state);
	}

	static void persistVisitState(ServerLevel level, State state) {
		Entity entity = level.getEntity(state.trader);
		if (entity != null) persistRouteAssignment(entity, state);
		if (entity instanceof CaravanTraderEntity trader) {
			trader.setVisitData(new CaravanTraderEntity.VisitData(
				new CaravanTraderEntity.TravelData(state.bell, state.spawn, state.partner,
					state.llamas.stream().map(UUID::toString).toList(), state.guard == null ? "" : state.guard.toString(),
					state.born, state.traded, state.leaveAt, state.nextUnload, state.uses, state.reward,
					state.roadKey == null ? "" : state.roadKey, state.roadCursor),
				new CaravanTraderEntity.CampData(List.copyOf(state.camp), List.copyOf(state.campBlocks), state.campBuilt,
					state.campRetryAt, state.guardDutyEnded, state.armedGuardRemoved)));
		} else if (entity instanceof AbstractVillager trader) {
			trader.addTag(RECOVERY_TAG);
			replaceLongTag(trader, RECOVERY_SPAWN_PREFIX, state.spawn.asLong());
			replaceLongTag(trader, RECOVERY_PARTNER_PREFIX, state.partner);
			replaceLongTag(trader, RECOVERY_BORN_PREFIX, state.born);
			replaceLongTag(trader, RECOVERY_LEAVE_PREFIX, state.leaveAt);
			replaceStringTag(trader, RECOVERY_ROAD_KEY_PREFIX, state.roadKey == null ? "" : state.roadKey);
			replaceLongTag(trader, RECOVERY_ROAD_CURSOR_PREFIX, state.roadCursor);
		}
	}

	private static void replaceStringTag(Entity entity, String prefix, String value) {
		for (String tag : List.copyOf(entity.entityTags())) if (tag.startsWith(prefix)) entity.removeTag(tag);
		entity.addTag(prefix + value);
	}

	private static void clearTaggedValue(Entity entity, String prefix) {
		for (String tag : List.copyOf(entity.entityTags())) if (tag.startsWith(prefix)) entity.removeTag(tag);
	}

	private static void restoreRouteAssignment(ServerLevel level, Entity entity, State state) {
		RouteAssignment route = routeAssignmentFromTags(entity.entityTags(), Network.dim(level));
		if (route == null) return;
		state.routeDimension = route.dimension();
		state.routeBell = route.bell();
		state.routeRelationDelta = route.relationDelta();
		state.routeVisitDay = route.visitDay();
	}

	private static void persistRouteAssignment(Entity entity, State state) {
		clearTaggedValue(entity, RECOVERY_ROUTE_DIMENSION_PREFIX);
		clearTaggedValue(entity, RECOVERY_ROUTE_BELL_PREFIX);
		clearTaggedValue(entity, RECOVERY_ROUTE_RELATION_PREFIX);
		clearTaggedValue(entity, RECOVERY_ROUTE_VISIT_DAY_PREFIX);
		for (String tag : routeAssignmentTags(state.routeDimension, state.routeBell, state.routeRelationDelta, state.routeVisitDay)) {
			entity.addTag(tag);
		}
	}

	static List<String> routeAssignmentTags(String dimension, BlockPos bell, int relationDelta, long visitDay) {
		if (dimension == null || dimension.isBlank() || dimension.length() > 128 || bell == null) return List.of();
		return List.of(RECOVERY_ROUTE_DIMENSION_PREFIX + dimension, RECOVERY_ROUTE_BELL_PREFIX + bell.asLong(),
			RECOVERY_ROUTE_RELATION_PREFIX + Math.max(0, Math.min(100, relationDelta)),
			RECOVERY_ROUTE_VISIT_DAY_PREFIX + Math.max(-1L, visitDay));
	}

	static RouteAssignment routeAssignmentFromTags(Iterable<String> tags, String currentDimension) {
		if (tags == null || currentDimension == null || currentDimension.isBlank()) return null;
		String dimension = uniqueTaggedString(tags, RECOVERY_ROUTE_DIMENSION_PREFIX);
		Long bell = uniqueTaggedLong(tags, RECOVERY_ROUTE_BELL_PREFIX);
		if (dimension == null || dimension.isBlank() || dimension.length() > 128 || !dimension.equals(currentDimension) || bell == null) return null;
		Long relation = uniqueTaggedLong(tags, RECOVERY_ROUTE_RELATION_PREFIX);
		Long visitDay = uniqueTaggedLong(tags, RECOVERY_ROUTE_VISIT_DAY_PREFIX);
		return new RouteAssignment(dimension, BlockPos.of(bell).immutable(),
			(int)Math.max(0L, Math.min(100L, relation == null ? 0L : relation)),
			visitDay == null || visitDay < 0 ? -1L : visitDay);
	}

	private static String uniqueTaggedString(Iterable<String> tags, String prefix) {
		String found = null;
		for (String tag : tags) {
			if (tag == null || !tag.startsWith(prefix)) continue;
			String value = tag.substring(prefix.length());
			if (found != null && !found.equals(value)) return null;
			found = value;
		}
		return found;
	}

	private static Long uniqueTaggedLong(Iterable<String> tags, String prefix) {
		Long found = null;
		for (String tag : tags) {
			if (tag == null || !tag.startsWith(prefix)) continue;
			long value;
			try { value = Long.parseLong(tag.substring(prefix.length())); }
			catch (NumberFormatException ignored) { return null; }
			if (found != null && found.longValue() != value) return null;
			found = value;
		}
		return found;
	}

	static int routeAssignmentChecks() {
		String dimension = "minecraft:overworld";
		BlockPos bell = new BlockPos(-132, 72, 640);
		List<String> stored = routeAssignmentTags(dimension, bell, 7, 24L);
		RouteAssignment restored = routeAssignmentFromTags(stored, dimension);
		if (restored == null || !restored.dimension().equals(dimension) || !restored.bell().equals(bell)
			|| restored.relationDelta() != 7 || restored.visitDay() != 24L) {
			throw new IllegalStateException("Caravan destination check failed: real destination identity and pending visit survive tag restoration");
		}
		if (routeAssignmentFromTags(stored, "minecraft:the_nether") != null) {
			throw new IllegalStateException("Caravan destination check failed: route identity cannot cross dimensions");
		}
		if (routeAssignmentFromTags(List.of(RECOVERY_ROUTE_DIMENSION_PREFIX + dimension), dimension) != null
			|| !routeAssignmentTags(dimension, null, 3, 24L).isEmpty()) {
			throw new IllegalStateException("Caravan destination check failed: partial identity cannot invent a village");
		}
		List<String> ambiguous = new ArrayList<>(stored);
		ambiguous.add(RECOVERY_ROUTE_BELL_PREFIX + new BlockPos(500, 72, 500).asLong());
		if (routeAssignmentFromTags(ambiguous, dimension) != null) {
			throw new IllegalStateException("Caravan destination check failed: conflicting persisted bells are rejected");
		}
		return 4;
	}

	private static boolean activeAt(ServerLevel level, BlockPos bell) {
		return ACTIVE.values().stream().anyMatch(state -> state.dimension.equals(level.dimension()) && state.bell.equals(bell));
	}

	private static void processPendingCaravanOrphans(ServerLevel level) {
		long now = level.getGameTime();
		for (var entry : Map.copyOf(PENDING_CARAVAN_ORPHANS).entrySet()) {
			PendingCaravanOrphan pending = entry.getValue();
			if (!pending.dimension().equals(level.dimension())) continue;
			Entity entity = level.getEntity(entry.getKey());
			if (entity == null) {
				if (level.hasChunkAt(pending.lastPosition())) PENDING_CARAVAN_ORPHANS.remove(entry.getKey());
				continue;
			}
			if (ACTIVE.containsKey(entity.getUUID()) || ACTIVE.values().stream().anyMatch(state -> state.llamas.contains(entity.getUUID()))) {
				PENDING_CARAVAN_ORPHANS.remove(entry.getKey());
				continue;
			}
			if (now < pending.retryAt()) continue;
			if (hasPendingAdoptionFor(level, entity)) {
				PENDING_CARAVAN_ORPHANS.put(entry.getKey(), new PendingCaravanOrphan(
					pending.dimension(), pending.lastPosition(), now + 200L));
				continue;
			}
			PENDING_CARAVAN_ORPHANS.remove(entry.getKey());
			releaseUnadoptedCaravanMember(level, entity);
		}
	}

	private static boolean hasPendingAdoptionFor(ServerLevel level, Entity member) {
		String village = taggedVillage(member);
		return PENDING_CARAVAN_ADOPTIONS.values().stream().anyMatch(pending -> pending.dimension().equals(level.dimension())
			&& (pending.villageTag().isBlank() || pending.villageTag().equals(village)));
	}

	private static void releaseUnadoptedCaravanMember(ServerLevel level, Entity entity) {
		if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {
			donkey.removeLeash();
			if (hasCargo(donkey)) {
				donkey.setPersistenceRequired();
				if (donkey.getCustomName() == null) donkey.setCustomName(Component.literal("Karawanen-Frachtesel"));
				donkey.setCustomNameVisible(false);
				syncCargoVisual(level, donkey);
				CARGO_RECONCILE.put(donkey.getUUID(), level.dimension());
				return;
			}
		}
		if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
		if (entity instanceof net.minecraft.world.entity.animal.golem.IronGolem golem) golem.setPlayerCreated(true);
		// Keep marker tags and the entity: a later merchant load can reattach it by its bell tag.
	}

	private static List<CaravanCampData.CampBlock> attachedCampBlocks(net.minecraft.world.level.block.entity.BellBlockEntity bell) {
		return bell.getAttachedOrElse(de.villagerplus.VillagerPlus.CARAVAN_CAMPS, List.of());
	}

	static void saveCampBlocks(net.minecraft.world.level.block.entity.BellBlockEntity bell, List<CaravanCampData.CampBlock> blocks) {
		bell.setAttached(de.villagerplus.VillagerPlus.CARAVAN_CAMPS, List.copyOf(blocks));
		bell.setChanged();
	}

	private static boolean sameCampBlock(net.minecraft.world.level.block.state.BlockState current, net.minecraft.world.level.block.state.BlockState expected) {
		if (current.equals(expected)) return true;
		// Spruce fences derive their four connection properties from neighboring blocks.
		// Those updates do not mean a camp post was replaced or claimed by someone else.
		if (expected.is(net.minecraft.world.level.block.Blocks.SPRUCE_FENCE)
			&& current.is(net.minecraft.world.level.block.Blocks.SPRUCE_FENCE)) {
			var unconnected = current
				.setValue(net.minecraft.world.level.block.FenceBlock.NORTH, expected.getValue(net.minecraft.world.level.block.FenceBlock.NORTH))
				.setValue(net.minecraft.world.level.block.FenceBlock.EAST, expected.getValue(net.minecraft.world.level.block.FenceBlock.EAST))
				.setValue(net.minecraft.world.level.block.FenceBlock.SOUTH, expected.getValue(net.minecraft.world.level.block.FenceBlock.SOUTH))
				.setValue(net.minecraft.world.level.block.FenceBlock.WEST, expected.getValue(net.minecraft.world.level.block.FenceBlock.WEST));
			if (unconnected.equals(expected)) return true;
		}
		return expected.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) && current.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
			&& current.setValue(net.minecraft.world.level.block.CampfireBlock.LIT, expected.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)).equals(expected);
	}

	/** Removes only matching camp-owned states; unloaded cells remain recorded for the next chunk load. */
	static List<CaravanCampData.CampBlock> clearLoadedCampBlocks(ServerLevel level, List<CaravanCampData.CampBlock> records) {
		List<CaravanCampData.CampBlock> pending = new ArrayList<>();
		for (CaravanCampData.CampBlock record : records) {
			BlockPos pos = record.pos();
			if (!level.hasChunkAt(pos)) {
				pending.add(record);
				continue;
			}
			var current = level.getBlockState(pos);
			if (current.isAir() || !sameCampBlock(current, record.expected())) continue;
			var blockEntity = level.getBlockEntity(pos);
			if (blockEntity instanceof Container container && !container.isEmpty()) continue;
			// A player may have renamed an otherwise empty camp barrel. Keep that
			// block entity (and its name) instead of treating its block state alone
			// as proof that it is still untouched camp property.
			if (blockEntity instanceof net.minecraft.world.Nameable nameable && nameable.hasCustomName()) continue;
			if (!level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)) pending.add(record);
		}
		return List.copyOf(pending);
	}

	private static void queuePendingCampCleanup(ServerLevel level, BlockPos bell, List<CaravanCampData.CampBlock> records) {
		Map<Long, List<CaravanCampData.CampBlock>> byBell = PENDING_CAMP_CLEANUP.computeIfAbsent(level.dimension(), key -> new HashMap<>());
		if (records.isEmpty()) byBell.remove(bell.asLong());
		else byBell.put(bell.asLong(), List.copyOf(records));
		if (byBell.isEmpty()) PENDING_CAMP_CLEANUP.remove(level.dimension());
	}

	private static void queueOrphanCampCleanup(ServerLevel level, net.minecraft.world.level.block.entity.BellBlockEntity bell) {
		List<CaravanCampData.CampBlock> records = attachedCampBlocks(bell);
		if (!records.isEmpty() && !activeAt(level, bell.getBlockPos())) {
			queuePendingCampCleanup(level, bell.getBlockPos(), records);
			processPendingCampCleanup(level, bell.getBlockPos(), bell);
		}
	}

	private static void processPendingCampCleanups(ServerLevel level) {
		Map<Long, List<CaravanCampData.CampBlock>> byBell = PENDING_CAMP_CLEANUP.get(level.dimension());
		if (byBell == null || byBell.isEmpty()) return;
		for (var entry : Map.copyOf(byBell).entrySet()) {
			BlockPos bellPos = BlockPos.of(entry.getKey());
			if (activeAt(level, bellPos)) continue;
			var bell = loadedBell(level, bellPos);
			processPendingCampCleanup(level, bellPos, bell);
		}
	}

	private static void processPendingCampCleanup(ServerLevel level, BlockPos bellPos, net.minecraft.world.level.block.entity.BellBlockEntity bell) {
		Map<Long, List<CaravanCampData.CampBlock>> byBell = PENDING_CAMP_CLEANUP.get(level.dimension());
		if (byBell == null) return;
		List<CaravanCampData.CampBlock> records = byBell.get(bellPos.asLong());
		if (records == null || activeAt(level, bellPos)) return;
		if (bell != null) {
			List<CaravanCampData.CampBlock> saved = attachedCampBlocks(bell);
			if (!saved.isEmpty()) records = saved;
		}
		List<CaravanCampData.CampBlock> remaining = clearLoadedCampBlocks(level, records);
		if (bell != null) saveCampBlocks(bell, remaining);
		queuePendingCampCleanup(level, bellPos, remaining);
	}

	private static boolean persistCampPlan(ServerLevel level, BlockPos bellPos, List<CaravanCampData.CampBlock> planned) {
		var bell = loadedBell(level, bellPos);
		if (bell == null) return false;
		List<CaravanCampData.CampBlock> previous = attachedCampBlocks(bell);
		if (!previous.isEmpty()) {
			List<CaravanCampData.CampBlock> remaining = clearLoadedCampBlocks(level, previous);
			saveCampBlocks(bell, remaining);
			if (!remaining.isEmpty()) {
				queuePendingCampCleanup(level, bellPos, remaining);
				return false;
			}
			queuePendingCampCleanup(level, bellPos, List.of());
		}
		saveCampBlocks(bell, planned);
		return true;
	}

	public static void maybeSpawn(ServerLevel level, VillageRef ref, Villager who, long now) {

		if (ref.data().extra.nextCaravan == 0) { ref.data().extra.nextCaravan = now + 6000; ref.dirty(); return; }

		if (now < ref.data().extra.nextCaravan || ref.chestPos(level) == null) return;

		if (Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v)).size() < Config.CARAVAN_MIN_POP) return;

		spawn(level, ref);

	}

	private static TradeNetwork.Link chooseLoadedCaravanRoute(ServerLevel level, VillageRef ref) {
		List<TradeNetwork.Link> routes = new ArrayList<>(Network.loadedReciprocalCaravanRoutes(level, ref));
		TradeNetwork source = Network.of(ref.bellEntity());
		routes.removeIf(link -> {
			if (!link.dimension().equals(Network.dim(level)) || ref.bell().distSqr(link.bell()) > 96L * 96L) return true;
			VillageRef target = Network.resolve(level, link);
			return target == null || !reciprocalRouteEnabledBothWays(source, Network.of(target.bellEntity()),
				link.dimension(), ref.bell(), link.bell());
		});
		if (routes.isEmpty()) return null;
		return routes.get(level.getRandom().nextInt(routes.size()));
	}

	static boolean reciprocalRouteEnabledBothWays(TradeNetwork source, TradeNetwork target, String dimension,
		BlockPos sourceBell, BlockPos targetBell) {
		if (!Network.reciprocalLink(source, target, dimension, sourceBell, targetBell)) return false;
		int sourceIndex = source.linkIndex(dimension, targetBell);
		int targetIndex = target.linkIndex(dimension, sourceBell);
		return sourceIndex >= 0 && targetIndex >= 0
			&& source.links().get(sourceIndex).route() && target.links().get(targetIndex).route();
	}

	public static boolean spawn(ServerLevel level, VillageRef ref) {

		if (ref.chestPos(level) == null || ACTIVE.values().stream().anyMatch(st -> st.dimension.equals(level.dimension()) && st.bell.equals(ref.bell()))) return false;

		long now = level.getGameTime();

		TradeNetwork net = Network.of(ref.bellEntity());

		long day = now / 24000;

		long decayDay = net.cooldowns().getOrDefault("partner_decay", day);

		if (day > decayDay) {

			List<TradeNetwork.Partner> ps = new ArrayList<>(net.partners());

			for (int i = 0; i < ps.size(); i++) {

				var part = ps.get(i);

				if (part.lastVisitDay() >= 0 && day - part.lastVisitDay() > 3) ps.set(i, part.withRelation(part.relation() - (int) Math.min(100, (day - decayDay) / (net.links().stream().anyMatch(TradeNetwork.Link::route) ? 2 : 1))));

			}

			net = net.withPartners(ps);

		}

		net = net.withCooldown("partner_decay", day); Network.save(ref.bellEntity(), net);

		int legacyPartner = level.getRandom().nextInt(net.partners().size());
		var part = net.partners().get(legacyPartner);
		TradeNetwork.Link routeLink = chooseLoadedCaravanRoute(level, ref);

		CaravanSites.Group group = routeLink == null ? null : arrival(level, routeLink.bell(), 24, 36);
		if (group == null) routeLink = null;
		if (group == null) group = arrival(level, ref.bell(), 70, 100);

		if (group == null) group = arrival(level, ref.bell(), 34, 46);

		if (group == null) { ref.data().extra.nextCaravan=now+1200; ref.dirty(); return false; }
		BlockPos at=group.trader();

		CaravanTraderEntity trader = de.villagerplus.entity.ModEntities.CARAVAN_TRADER.spawn(level, at, EntitySpawnReason.EVENT);

		if (trader == null) { ref.data().extra.nextCaravan=now+1200; ref.dirty(); return false; }
		if(!hasSafeTraderStand(level,trader,trader.blockPosition())) {trader.discard();ref.data().extra.nextCaravan=now+1200;ref.dirty();return false;}
		BlockPos depot = ref.chestPos(level);
		BlockPos depotStand = depot == null ? null : safeDepotArrivalStand(level, trader, depot);
		if (depotStand == null) { trader.discard(); ref.data().extra.nextCaravan=now+1200; ref.dirty(); return false; }
		BlockPos routeTarget = depotStand;
		State routeProbe = null;
		if (routeLink != null) {
			routeProbe = new State(new UUID(0L, 0L), level.dimension(), ref.bell(), at, -1, now);
			routeProbe.routeDimension = routeLink.dimension();
			routeProbe.routeBell = routeLink.bell().immutable();
			routeTarget = roadWaypoint(level, ref, at, depotStand, routeProbe, false);
			if (routeTarget == null) {
				// Do not assign a real town unless the actual loaded paved road connects both route endpoints.
				trader.discard();
				routeLink = null;
				group = arrival(level, ref.bell(), 70, 100);
				if (group == null) group = arrival(level, ref.bell(), 34, 46);
				if (group == null) { ref.data().extra.nextCaravan = now + 1200; ref.dirty(); return false; }
				at = group.trader();
				trader = de.villagerplus.entity.ModEntities.CARAVAN_TRADER.spawn(level, at, EntitySpawnReason.EVENT);
				if (trader == null || !hasSafeTraderStand(level, trader, trader.blockPosition())) {
					if (trader != null) trader.discard();
					ref.data().extra.nextCaravan = now + 1200; ref.dirty(); return false;
				}
				depotStand = safeDepotArrivalStand(level, trader, depot);
				if (depotStand == null) { trader.discard(); ref.data().extra.nextCaravan = now + 1200; ref.dirty(); return false; }
				routeTarget = depotStand;
				routeProbe = null;
			}
		}
		int arrivalRelation = routeLink == null ? part.relation() : routeLink.relation();
		long nextVisit = now + (long) (Config.CARAVAN_INTERVAL * (routeLink != null ? 0.6 : 1.0) * (1.0 - arrivalRelation / 500.0));
		ref.data().extra.nextCaravan=nextVisit; ref.dirty();

		int assignedPartner = routeLink == null ? legacyPartner : -1;
		State state = new State(trader, level, ref, at, assignedPartner);
		if (routeLink != null) {
			state.routeDimension = routeLink.dimension();
			state.routeBell = routeLink.bell().immutable();
			state.roadKey = routeProbe == null ? null : routeProbe.roadKey;
			state.roadCursor = routeProbe == null ? -1 : routeProbe.roadCursor;
		}
		ACTIVE.put(trader.getUUID(), state);

		trader.addTag("vp_caravan"); trader.addTag(villageTag(ref.bell()));

		String partnerName = routeLink == null ? part.name() : routeLink.name();
		int specialty = routeLink == null ? part.specialty() : Math.floorMod(routeLink.bell().hashCode(), TradeNetwork.SPECIALTIES.length);
		long lastVisitDay = routeLink == null ? part.lastVisitDay() : routeLink.lastVisitDay();
		trader.setCustomName(Component.literal(lastVisitDay < 0 ? "Fremder Händler" : "Händler aus " + partnerName)); trader.setCustomNameVisible(false);

		trader.setTradeProfile(specialty, arrivalRelation, partnerName);
		trader.setRouteTarget(routeTarget);

		persistVisitState(level, state);

		for (int i = 0; i < 2; i++) {

			var llama = EntityTypes.DONKEY.spawn(level, group.donkeys().get(i), EntitySpawnReason.EVENT);
			if(llama==null||!CaravanSites.canStand(level,llama,llama.blockPosition(),List.of())) {
				if(llama!=null)llama.discard();return cancelSpawn(level,ref,state,trader,now);
			}

			if (llama != null) { llama.addTag("vp_caravan"); llama.addTag(villageTag(ref.bell())); { var chestSlot = llama.getSlot(499); if (chestSlot != null) chestSlot.set(new ItemStack(Items.CHEST)); } llama.setPersistenceRequired(); llama.setLeashedTo(trader, true); state.llamas.add(llama.getUUID()); }

		}

		var guard = EntityTypes.IRON_GOLEM.spawn(level, group.guard(), EntitySpawnReason.EVENT);
		if(guard==null||!CaravanSites.canStand(level,guard,guard.blockPosition(),List.of())) {
			if(guard!=null)guard.discard();return cancelSpawn(level,ref,state,trader,now);
		}

		if (guard != null) { guard.addTag("vp_caravan"); guard.addTag(villageTag(ref.bell())); guard.setPlayerCreated(true); guard.setCustomName(Component.literal("Karawanenwache")); guard.setCustomNameVisible(false); state.llamas.add(guard.getUUID()); state.guard = guard.getUUID(); }

		persistVisitState(level, state);

		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawane {}: Start {}, Glocke {}, Abstand {}", part.name(), at, ref.bell(), Math.sqrt(at.distSqr(ref.bell())));

		return true;

	}

	private static boolean cancelSpawn(ServerLevel level,VillageRef ref,State state,AbstractVillager trader,long now) {
		for(UUID id:state.llamas){Entity e=level.getEntity(id);if(e!=null)e.discard();}
		ACTIVE.remove(trader.getUUID());trader.discard();ref.data().extra.nextCaravan=now+1200;ref.dirty();return false;
	}

	private static CaravanSites.Group arrival(ServerLevel level, BlockPos bell, int min, int max) {
		var traderProbe = de.villagerplus.entity.ModEntities.CARAVAN_TRADER.create(
			level, EntitySpawnReason.EVENT);
		if (traderProbe == null) return null;

		for (int i = 0; i < 20; i++) {

			// Check chunk BEFORE heightmap access; never load remote chunks for a caravan.

			double angle = level.getRandom().nextDouble() * Math.PI * 2;

			double radius = min + level.getRandom().nextDouble() * (max - min);

			BlockPos column = bell.offset((int) Math.round(Math.cos(angle) * radius), 0, (int) Math.round(Math.sin(angle) * radius));

			if (!level.hasChunkAt(column)) continue;

			BlockPos p = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);

			double horizontal = Math.hypot(p.getX() - bell.getX(), p.getZ() - bell.getZ());

			if (horizontal < min || horizontal > max || !level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()

				|| !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) continue;

			var group=CaravanSites.plan(level,p);
			if(group!=null&&hasSafeTraderStand(level,traderProbe,group.trader()))return group;

		}

		return null;

	}

	private static VillageRef resolve(ServerLevel level, BlockPos bell) {

		if (!level.hasChunkAt(bell) || !(level.getBlockEntity(bell) instanceof net.minecraft.world.level.block.entity.BellBlockEntity be)) return null;

		return new VillageRef(bell, be.getAttachedOrCreate(de.villagerplus.VillagerPlus.VILLAGE), be);

	}

	private static void changeRelation(VillageRef ref, int index, int delta, long day) {

		Network.save(ref.bellEntity(), updatedRelation(Network.of(ref.bellEntity()), index, delta, day));

	}

	static TradeNetwork updatedRelation(TradeNetwork n, int index, int delta, long day) {

		List<TradeNetwork.Partner> ps = new ArrayList<>(n.partners());

		if (index < 0 || index >= ps.size()) return n;

		var p = ps.get(index).withRelation(ps.get(index).relation() + delta);

		ps.set(index, day >= 0 ? p.visited(day) : p);

		return n.withPartners(ps);

	}

	/** Commits discovery only after an exchange actually transferred goods. */
	static TradeNetwork afterExchange(TradeNetwork n, int index, long day, boolean successful) {
		return successful ? updatedRelation(n, index, 3, day) : n;
	}

	private static void recordExchange(VillageRef ref, int index, long day, boolean successful) {
		TradeNetwork network = Network.of(ref.bellEntity());
		Network.save(ref.bellEntity(), afterExchange(network, index, day, successful));
	}

	private static void recordLinkedRouteVisit(VillageRef ref, State state, long day, int relationDelta) {
		if (ref == null || state == null || state.routeDimension == null || state.routeBell == null || day < 0) return;
		state.routeVisitDay = day;
		state.routeRelationDelta = Math.min(100, Math.max(0, state.routeRelationDelta) + Math.max(0, relationDelta));
		Network.recordCaravanLinkVisit(ref, state.routeDimension, state.routeBell, day, relationDelta);
	}

	private static boolean recordReturnedLinkedRouteVisit(ServerLevel level, VillageRef source, State state) {
		if (state.routeBell == null || state.routeDimension == null || state.routeRelationDelta <= 0 || state.routeVisitDay < 0) return true;
		if (!state.routeDimension.equals(Network.dim(level)) || !level.hasChunkAt(state.routeBell)) return false;
		VillageRef origin = resolve(level, state.routeBell);
		if (origin == null) return false;
		TradeNetwork sourceNetwork = Network.of(source.bellEntity());
		TradeNetwork originNetwork = Network.of(origin.bellEntity());
		if (!Network.reciprocalLink(sourceNetwork, originNetwork, state.routeDimension, source.bell(), state.routeBell)) {
			// The real villages no longer have a reciprocal route; do not recreate a deleted link from old visit state.
			state.routeRelationDelta = 0;
			return true;
		}
		Network.recordCaravanLinkVisit(origin, Network.dim(level), source.bell(), state.routeVisitDay, state.routeRelationDelta);
		state.routeRelationDelta = 0;
		return true;
	}

	/** Packt echte Handelsware in freie Plaetze der beiden Eselstruhen. */
	static int packCargo(ServerLevel level, State state, ItemStack stack) {
		return packCargo(level, state, stack, null, null);
	}

	private static PlanPackResult packPlanCargo(ServerLevel level, State state, Item item, int requested) {
		if (requested <= 0) return new PlanPackResult(0, List.of());
		List<PlanCargoSlot> newSlots = new ArrayList<>();
		int loaded = packCargo(level, state, new ItemStack(item, requested), item, newSlots);
		return new PlanPackResult(loaded, List.copyOf(newSlots));
	}

	private static int packCargo(ServerLevel level, State state, ItemStack stack, Item planItem,
			List<PlanCargoSlot> newlyPackedPlanSlots) {

		int before = stack.getCount();
		OVERFLOW_PACK_MULE_DIAGNOSTIC = "overflow not needed";
		if (stack.isEmpty()) return 0;

		// Reserve all required overflow animals first. If placement fails, keep the
		// existing caravan intact and use only the cargo room it already has.
		long available = cargoSpace(level, state, stack, planItem);
		long missing = Math.max(0L, stack.getCount() - available);
		List<net.minecraft.world.entity.animal.equine.Donkey> staged = new ArrayList<>();
		boolean overflowReady = true;
		if (missing > 0) {
			Entity leader = level.getEntity(state.trader);
			AbstractVillager trader = leader instanceof AbstractVillager found ? found : null;
			if (trader == null || !trader.isAlive()) {
				OVERFLOW_PACK_MULE_DIAGNOSTIC = "caravan trader unavailable; using current cargo capacity";
				overflowReady = false;
			}
			while (overflowReady && missing > 0) {
				var mule = spawnOverflowPackDonkey(level, state, trader, staged);
				if (mule == null) {
					for (var created : staged) created.discard();
					staged.clear();
					overflowReady = false;
					break;
				}
				staged.add(mule);
				long muleSpace = cargoSpace(mule, stack, planItem);
				if (muleSpace <= 0) {
					mule.discard();
					for (var created : staged) created.discard();
					staged.clear();
					OVERFLOW_PACK_MULE_DIAGNOSTIC = "spawned mule has no accessible cargo slots; using current cargo capacity";
					overflowReady = false;
					break;
				}
				missing -= muleSpace;
			}
		}

		if (overflowReady) {
			for (var mule : staged) state.llamas.add(mule.getUUID());
			if (!staged.isEmpty()) OVERFLOW_PACK_MULE_DIAGNOSTIC = "spawned " + staged.size() + " mule(s)";
		}

		for (UUID id : List.copyOf(state.llamas)) {
			if (!(level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)) continue;

			for (int slot = 2; slot < 17 && !stack.isEmpty(); slot++) {

				var access = donkey.getSlot(500 + slot); if (access == null) continue;

				ItemStack current = access.get();

				if (current.isEmpty()) {
					int moved = Math.min(stack.getCount(), stack.getMaxStackSize());
					if (access.set(stack.copyWithCount(moved))) {
						stack.shrink(moved);
						if (planItem != null) newlyPackedPlanSlots.add(new PlanCargoSlot(id, slot));
						syncCargoVisual(level, donkey);
					}

				} else if (ItemStack.isSameItemSameComponents(current, stack)
					&& current.getCount() < current.getMaxStackSize()
					&& (planItem == null
						? !isPlanFreightSlot(donkey, slot, current.getItem())
						: isPlanFreightSlot(donkey, slot, planItem))) {

					int moved = Math.min(stack.getCount(), current.getMaxStackSize() - current.getCount());
					ItemStack updated = current.copy();
					updated.grow(moved);
					if (access.set(updated)) {
						stack.shrink(moved);
						syncCargoVisual(level, donkey);
					}

				}
			}
		}

		int packed = before - stack.getCount();
		if (!overflowReady && packed > 0) OVERFLOW_PACK_MULE_DIAGNOSTIC += "; packed " + packed + " item(s) in existing capacity";
		return packed;

	}

	private static long cargoSpace(ServerLevel level, State state, ItemStack stack, Item planItem) {
		long space = 0;
		for (UUID id : state.llamas) {
			if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {
				space += cargoSpace(donkey, stack, planItem);
			}
		}
		return space;
	}

	private static long cargoSpace(net.minecraft.world.entity.animal.equine.Donkey donkey, ItemStack stack, Item planItem) {
		long space = 0;
		for (int slot = 2; slot < 17; slot++) {
			var access = donkey.getSlot(500 + slot);
			if (access == null) continue;
			ItemStack current = access.get();
			if (current.isEmpty()) space += stack.getMaxStackSize();
			else if (ItemStack.isSameItemSameComponents(current, stack)
				&& (planItem == null
					? !isPlanFreightSlot(donkey, slot, current.getItem())
					: isPlanFreightSlot(donkey, slot, planItem))) {
				space += Math.max(0, current.getMaxStackSize() - current.getCount());
			}
		}
		return space;
	}

	/** Creates a chest donkey only after finding a supported, clear, loaded cell. */
	private static net.minecraft.world.entity.animal.equine.Donkey spawnOverflowPackDonkey(ServerLevel level, State state,
			AbstractVillager trader, List<net.minecraft.world.entity.animal.equine.Donkey> staged) {
		var template = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
		if (template == null) {
			OVERFLOW_PACK_MULE_DIAGNOSTIC = "donkey entity factory returned null";
			return null;
		}
		template.setTamed(true);
		template.setChest(true);
		List<net.minecraft.world.phys.AABB> occupied = new ArrayList<>();
		for (UUID id : state.llamas) {
			Entity member = level.getEntity(id);
			if (member != null && member.isAlive()) occupied.add(member.getBoundingBox());
		}
		for (var mule : staged) if (mule.isAlive()) occupied.add(mule.getBoundingBox());
		occupied.add(trader.getBoundingBox());
		BlockPos origin = trader.blockPosition();
		int loadedColumns = 0, heightRejected = 0, templateRejected = 0, candidates = 0;
		int eventSpawnRejected = 0, commandSpawnRejected = 0, unregistered = 0, unsafeAfterSpawn = 0, chestRejected = 0;
		for (int radius = 1; radius <= 5; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos column = origin.offset(dx, 0, dz);
				if (!level.hasChunkAt(column)) continue;
				loadedColumns++;
				BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
				if (Math.abs(feet.getY() - origin.getY()) > 2) { heightRejected++; continue; }
				if (!CaravanSites.canStand(level, template, feet, occupied)) { templateRejected++; continue; }
				candidates++;
				// A preflight template is not enough: spawn may adjust the actual position.
				// Validate the spawned mule's real bounding box before committing it.
				var donkey = EntityTypes.DONKEY.spawn(level, feet, EntitySpawnReason.EVENT);
				if (donkey == null) {
					eventSpawnRejected++;
					// The cell has already passed the supported/clear placement checks. Use
					// command-style creation as a fallback when event spawn rules reject it.
					donkey = EntityTypes.DONKEY.spawn(level, feet, EntitySpawnReason.COMMAND);
				}
				if (donkey == null) { commandSpawnRejected++; continue; }
				if (level.getEntity(donkey.getUUID()) != donkey) { donkey.discard(); unregistered++; continue; }
				if (!CaravanSites.canStand(level, donkey, donkey.blockPosition(), occupied)) {
					donkey.discard(); unsafeAfterSpawn++; continue;
				}
				donkey.setTamed(true);
				var chest = donkey.getSlot(499);
				if (chest == null || !chest.set(new ItemStack(Items.CHEST)) || !donkey.hasChest()) {
					donkey.discard(); chestRejected++; continue;
				}
				if (!CaravanSites.canStand(level, donkey, donkey.blockPosition(), occupied)) {
					donkey.discard(); unsafeAfterSpawn++; continue;
				}
				donkey.addTag("vp_caravan");
				donkey.addTag(villageTag(state.bell));
				donkey.setPersistenceRequired();
				donkey.setLeashedTo(trader, true);
				OVERFLOW_PACK_MULE_DIAGNOSTIC = "spawned safely at " + donkey.blockPosition();
				return donkey;
			}
		}
		OVERFLOW_PACK_MULE_DIAGNOSTIC = "no mule placed: loadedColumns=" + loadedColumns + ", heightRejected=" + heightRejected
			+ ", templateRejected=" + templateRejected + ", safeCandidates=" + candidates
			+ ", eventSpawnRejected=" + eventSpawnRejected + ", commandSpawnRejected=" + commandSpawnRejected
			+ ", unregistered=" + unregistered + ", unsafeAfterSpawn=" + unsafeAfterSpawn + ", chestRejected=" + chestRejected;
		return null;
	}

	static String overflowPackDonkeyDiagnostic() { return OVERFLOW_PACK_MULE_DIAGNOSTIC; }

	/** Shows a non-pickup render-only cargo item; donkey inventory is always the source of truth. */
	static void syncCargoVisual(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey) {
		ItemStack cargo = ItemStack.EMPTY;
		for (int slot = 2; slot < 17; slot++) {
			var access = donkey.getSlot(500 + slot);
			if (access != null && !access.get().isEmpty()) { cargo = access.get(); break; }
		}
		UUID id = CARGO_VISUALS.get(donkey.getUUID());
		Entity mapped = id == null ? null : level.getEntity(id);
		String ownerTag = "vp_caravan_cargo_owner:" + donkey.getUUID();
		List<ItemDisplay> candidates = level.getEntitiesOfClass(ItemDisplay.class, donkey.getBoundingBox().inflate(4.0), e -> e.entityTags().contains(ownerTag));
		ItemDisplay display = mapped instanceof ItemDisplay itemDisplay ? itemDisplay
			: candidates.stream().filter(candidate -> candidate.getVehicle() == donkey).findFirst().orElseGet(() -> candidates.stream().findFirst().orElse(null));
		UUID porterId = PORTER_CLAIMS.get(donkey.getUUID());
		PorterCarry carry = porterId == null ? null : PORTER_CARRIES.get(porterId);
		boolean visibleInPorterHand = carry != null && carry.pickedUp;
		if (cargo.isEmpty() || visibleInPorterHand) {
			if (mapped != null) mapped.discard();
			for (ItemDisplay candidate : candidates) if (candidate != mapped) candidate.discard();
			CARGO_VISUALS.remove(donkey.getUUID());
			return;
		}
		for (ItemDisplay candidate : candidates) if (candidate != display) candidate.discard();
		if (display == null) {
			display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (display == null) return;
			display.addTag("vp_caravan_cargo_visual");
			display.addTag("vp_caravan_cargo_owner:" + donkey.getUUID());
			display.setNoGravity(true);
			display.setInvulnerable(true);
			display.setSilent(true);
			display.setItemTransform(ItemDisplayContext.GROUND);
			display.setItemStack(cargo.copyWithCount(1));
			// Spawn inside the loaded donkey chunk before adding it to the level.
			// Starting at the default origin can unload/discard the display before it mounts.
			display.setPos(donkey.getX(), donkey.getY() + 1.2, donkey.getZ());
			display.setYRot(donkey.getYRot());
			CARGO_VISUALS.put(donkey.getUUID(), display.getUUID());
			if (!level.addFreshEntity(display)) { CARGO_VISUALS.remove(donkey.getUUID()); return; }
		}
		if (!ItemStack.isSameItemSameComponents(display.getItemStack(), cargo)) display.setItemStack(cargo.copyWithCount(1));
		if (display.getVehicle() != donkey && !display.startRiding(donkey, true, true)) {
			display.setPos(donkey.getX(), donkey.getY() + 1.2, donkey.getZ());
			display.setYRot(donkey.getYRot());
		}
	}

	private static String villageTag(BlockPos bell) { return BELL_TAG_PREFIX + bell.asLong(); }

	private static boolean validPartner(VillageRef ref, State state) {
		return state.partner >= 0 && state.partner < Network.of(ref.bellEntity()).partners().size();
	}

	private static String partnerName(VillageRef ref, State state) {
		if (state.routeBell != null && state.routeDimension != null) {
			TradeNetwork network = Network.of(ref.bellEntity());
			int index = network.linkIndex(state.routeDimension, state.routeBell);
			return index >= 0 ? network.links().get(index).name() : "verknüpftes Nachbardorf";
		}
		return validPartner(ref, state) ? Network.of(ref.bellEntity()).partners().get(state.partner).name() : "unbekannt";
	}

	private static List<Item> merchantGoods(int specialty) {
		return switch (Math.floorMod(specialty, 4)) {
			case 0 -> List.of(Items.IRON_INGOT, Items.COAL, Items.COPPER_INGOT);
			case 1 -> List.of(Items.STRING, Items.LEATHER, Items.WOOL.white());
			case 2 -> List.of(Items.BREAD, Items.CARROT, Items.POTATO);
			default -> List.of(Items.SPRUCE_LOG, Items.OAK_LOG, Items.STONE_BRICKS);
		};
	}

	private static int availableMerchantCargo(ServerLevel level, State state, Item item) {
		int count = 0;
		ItemStack plainItem = new ItemStack(item);
		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access == null || access.get().isEmpty() || !ItemStack.isSameItemSameComponents(access.get(), plainItem)
					|| isPlanFreightSlot(donkey, slot, item)) continue;
				count = (int)Math.min(Integer.MAX_VALUE, (long)count + access.get().getCount());
			}
		}
		return count;
	}

	private static int takeMerchantCargo(ServerLevel level, State state, Item item, int requested) {
		if (requested <= 0) return 0;
		int remaining = requested;
		ItemStack plainItem = new ItemStack(item);
		List<MerchantCargoTake> planned = new ArrayList<>();
		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {
			for (int slot = 2; slot < 17 && remaining > 0; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access == null) continue;
				ItemStack current = access.get();
				if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, plainItem)
					|| isPlanFreightSlot(donkey, slot, item)) continue;
				int moved = Math.min(remaining, current.getCount());
				planned.add(new MerchantCargoTake(donkey, slot, current.copy(), moved));
				remaining -= moved;
			}
		}
		// Never publish an offer from a partial reservation. Check the whole debit before
		// changing any donkey slot, then restore the already-written prefix on an access failure.
		if (remaining > 0) return 0;
		int applied = 0;
		for (MerchantCargoTake take : planned) {
			var access = take.donkey().getSlot(500 + take.slot());
			if (access == null) {
				rollbackMerchantCargo(planned, applied);
				return 0;
			}
			ItemStack current = access.get();
			if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, take.original())
				|| current.getCount() != take.original().getCount() || current.getCount() < take.count()) {
				rollbackMerchantCargo(planned, applied);
				return 0;
			}
			ItemStack after = current.copy();
			after.shrink(take.count());
			if (!access.set(after)) {
				rollbackMerchantCargo(planned, applied + 1);
				return 0;
			}
			applied++;
		}
		for (MerchantCargoTake take : planned) syncCargoVisual(level, take.donkey());
		return requested;
	}

	private static void rollbackMerchantCargo(List<MerchantCargoTake> planned, int attempted) {
		boolean restored = true;
		for (int i = Math.min(attempted, planned.size()) - 1; i >= 0; i--) {
			MerchantCargoTake take = planned.get(i);
			var access = take.donkey().getSlot(500 + take.slot());
			if (access == null || !access.set(take.original().copy())) restored = false;
		}
		if (!restored) org.slf4j.LoggerFactory.getLogger("villagerplus").error(
			"Could not roll back an incomplete caravan trade-stock reservation; inspect the listed donkey inventories");
	}

	private static void prepareBackedMerchantOffers(ServerLevel level, State state, CaravanTraderEntity trader) {
		if (trader.backedTradeOffersInitialized()) return;
		List<ItemStack> reserved = new ArrayList<>();
		int perTrade = trader.itemsPerTrade();
		for (Item item : merchantGoods(trader.tradeSpecialty())) {
			int count = availableMerchantCargo(level, state, item);
			int uses = CaravanTraderEntity.backedOfferUses(count, perTrade);
			int amount = uses * perTrade;
			if (amount <= 0) continue;
			int moved = takeMerchantCargo(level, state, item, amount);
			if (moved > 0) reserved.add(new ItemStack(item, moved));
		}
		trader.installBackedTradeOffers(reserved);
		persistVisitState(level, state);
	}

	/** The porter escorts the loaded donkey; its real inventory is debited only at the reached depot. */
	public static boolean porterDelivery(Ctx c) {
		PorterCarry carry = PORTER_CARRIES.get(c.v().getUUID());
		VillageRef ref=c.village()!=null?c.village():carry==null?null:resolve(c.level(),carry.bell);
		if (ref == null) { if(carry!=null) releasePorter(c.level(),c.v().getUUID()); return false; }
		if (carry == null) {
			var donkey = c.level().getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Donkey.class,
				new AABB(ref.bell()).inflate(112), d -> d.isAlive() && hasCargo(d)
					&& hasDeliverableCargo(c.level(),ref,d)
					&& d.entityTags().contains(villageTag(ref.bell())) && !PORTER_CLAIMS.containsKey(d.getUUID())
				).stream().min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(c.v()))).orElse(null);
			if (donkey == null) return false;
			int slot = -1; ItemStack cargo = ItemStack.EMPTY; DeliveryTarget target = null;
			// Deliver the exact plan shipment before same-item trade stock.
			for (int planPass=0;planPass<2&&slot<0;planPass++) {
				for (int cashPass=0;cashPass<2&&slot<0;cashPass++) {
					for (int i=2;i<17;i++) {
						var access=donkey.getSlot(500+i);
						if(access==null||access.get().isEmpty()||access.get().is(Items.EMERALD)!=(cashPass==1)) continue;
						boolean planSlot=isPlanFreightSlot(donkey,i,access.get().getItem());
						if(planSlot!=(planPass==0)) continue;
						DeliveryTarget candidate=deliveryTarget(c.level(),ref,access.get());
						if(candidate==null) continue;
						slot=i; cargo=access.get().copy(); target=candidate; break;
					}
				}
			}
			if (slot < 0 || target == null) return false;
			carry=new PorterCarry(donkey.getUUID(),c.v().getUUID(),null,c.level().dimension(),slot,cargo,ref.bell(),target.pos());
			PORTER_DIAGNOSTICS.remove(c.v().getUUID());
			PORTER_CARRIES.put(c.v().getUUID(),carry); PORTER_CLAIMS.put(donkey.getUUID(),c.v().getUUID());
			c.st().clearTarget(); c.st().bestDist=0.0; c.st().lastMoveAt=0; c.st().stuckCount=0;
		}
		if (!carry.dimension.equals(c.level().dimension())) { releasePorter(c.level(),carry.porter); return false; }
		Entity sourceEntity=c.level().getEntity(carry.donkey);
		if (!(sourceEntity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) || !donkey.isAlive()
			|| !donkey.entityTags().contains(villageTag(ref.bell()))) { releasePorter(c.level(),carry.porter); return false; }
		var source=donkey.getSlot(500+carry.slot);
		if (source==null || source.get().isEmpty() || !ItemStack.isSameItemSameComponents(source.get(),carry.expected)
			|| source.get().getCount()<carry.count) { releasePorter(c.level(),carry.porter); return false; }

		if (!carry.pickedUp) {
			c.setActivity("holt Karawanenfracht ab");
			if (!isPorterStand(c.level(),donkey.blockPosition(),c.st().target)) {
				BlockPos pickupStand=reachablePorterStand(c,donkey.blockPosition());
				if(pickupStand==null) { releasePorter(c.level(),carry.porter); c.st().cooldownUntil=c.now()+200; return true; }
				c.st().target=pickupStand; c.st().targetEntity=null; c.st().walkStart=0; c.st().bestDist=0.0; c.st().lastMoveAt=0; c.st().stuckCount=0;
			}
			if (!JobUtil.approachLong(c,c.st().target,1.5)) {
				if(c.st().cooldownUntil>c.now()) releasePorter(c.level(),carry.porter);
				return true;
			}
			// Transfer a tracked merchant's lead; an unbound but village-registered loaded
			// cargo donkey is also eligible for an ordinary porter escort. Never steal a
			// lead from an unrelated entity.
			Entity leashHolder=donkey.getLeashHolder();
			if(leashHolder==null) {
				donkey.setLeashedTo(c.v(),true);
			} else if(leashHolder!=c.v()) {
				for(State caravan:ACTIVE.values()) {
					if(caravan.dimension.equals(c.level().dimension())&&caravan.llamas.contains(donkey.getUUID())
						&&caravan.trader.equals(leashHolder.getUUID())&&c.level().getEntity(caravan.trader) instanceof AbstractVillager) {
						carry.traderLeash=caravan.trader;
						donkey.setLeashedTo(c.v(),true);
						break;
					}
				}
			}
			if(donkey.getLeashHolder()!=c.v()) {
				releasePorter(c.level(),carry.porter); c.st().cooldownUntil=c.now()+200; return true;
			}
			carry.pickedUp=true;
			// Anim.apply owns the render-only hand stack and its original-hand restore;
			// the donkey slot remains the sole cargo source until physical delivery.
			carry.followProgressAt=c.now(); syncCargoVisual(c.level(),donkey); JobUtil.swing(c.v());
			c.level().playSound(null,donkey.blockPosition(),net.minecraft.sounds.SoundEvents.ITEM_PICKUP,net.minecraft.sounds.SoundSource.NEUTRAL,0.6F,0.9F);
			c.st().walkStart=0; c.st().bestDist=0; c.st().stuckCount=0; c.st().target=null; c.st().targetEntity=null;
		}

		DeliveryTarget target=deliveryTarget(c.level(),ref,carry.expected);
		BlockPos targetPos=target==null?null:target.pos(); Container destination=target==null?null:target.container();
		BlockPos stand=null;
		if(targetPos!=null) {
			if(!isPorterStand(c.level(),targetPos,c.st().target)) {
				stand=reachableDepotPorterStand(c,targetPos,donkey);
				c.st().target=stand; c.st().targetEntity=null; c.st().walkStart=0; c.st().bestDist=0.0; c.st().lastMoveAt=0; c.st().stuckCount=0;
			} else stand=c.st().target;
		}
		boolean treasury=target!=null&&"treasury".equals(target.depot());
		if(targetPos==null||stand==null||!targetPos.equals(carry.target)
			|| (!treasury&&(destination==null||StockPolicy.deliveryRoom(c.level(),ref,carry.expected.getItem())<=0||!canAccept(destination,carry.expected)))) {
			releasePorter(c.level(),carry.porter); c.st().cooldownUntil=c.now()+200; return true;
		}
		if(!stand.equals(c.st().target)) { c.st().target=stand; c.st().targetEntity=null; c.st().walkStart=0; c.st().bestDist=0; c.st().stuckCount=0; }
		String destinationName=switch(target.depot()) {
			case "treasury" -> "zur Dorfkasse";
			case de.villagerplus.build.Blueprints.GRANARY -> "zum Kornspeicher";
			case de.villagerplus.build.Blueprints.TOOLSHED -> "zur Werkzeugkammer";
			default -> target.pos().equals(ref.chestPos(c.level())) ? "zur Dorfkiste" : "ins Lager";
		};
		c.setActivity("begleitet den Frachtesel "+destinationName);
		// Keep the animal close enough to follow the same walkable route. Never
		// complete remotely when its normal navigation is blocked or it unloads.
		double separation=donkey.distanceToSqr(c.v());
		double porterToStand=c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand));
		double donkeyToDepot=donkey.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(targetPos));
		boolean porterArrived=porterToStand<=2.25;
		boolean donkeyArrived=donkeyToDepot<=25.0;
		if (porterArrived && !donkeyArrived) {
			if(!carry.waitingAtDepot) {
				carry.waitingAtDepot=true; carry.followBest=Double.POSITIVE_INFINITY; carry.followProgressAt=c.now();
				carry.donkeyStand=null; carry.nextDonkeyRouteAt=c.now();
			}
			// Vanilla's leash goal owns navigation for a donkey attached to this porter.
			// Competing manual paths are replaced by that goal each tick and can strand it
			// behind the porter; only recover a manually routed animal if the lead is gone.
			if(donkey.getLeashHolder()!=c.v()) {
				List<AABB> porterReservation=List.of(c.v().getBoundingBox(),CaravanSites.bounds(c.v(),stand));
				var donkeyNavigation=donkey.getNavigation();
				boolean donkeyRouteActive=carry.donkeyStand!=null&&donkeyNavigation.isInProgress()
					&&carry.donkeyStand.equals(donkeyNavigation.getTargetPos())
					&&CaravanSites.canStand(c.level(),donkey,carry.donkeyStand,porterReservation);
				if(!donkeyRouteActive&&c.now()>=carry.nextDonkeyRouteAt) {
					PorterDonkeyRoute route=reachableDonkeyDepotRoute(c,donkey,targetPos,stand);
					carry.nextDonkeyRouteAt=c.now()+20L;
					if(route!=null&&donkeyNavigation.moveTo(route.path(),1.0)) carry.donkeyStand=route.stand();
					else carry.donkeyStand=null;
				}
			}
			if(donkeyToDepot < carry.followBest-0.5) { carry.followBest=donkeyToDepot; carry.followProgressAt=c.now(); }
			else if(c.now()-carry.followProgressAt>400) {
				releasePorter(c.level(),carry.porter); c.st().cooldownUntil=c.now()+200; return true;
			}
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			return true;
		}
		carry.waitingAtDepot=false; carry.donkeyStand=null;
		// Once both are at the depot, separation does not prevent unloading.
		// The animal may naturally stop behind the porter in a narrow corridor.
		if(!porterArrived && separation>9.0) {
			donkey.getNavigation().moveTo(c.v(),1.0);
			if(separation>25.0) {
				if(separation < carry.followBest-0.5) { carry.followBest=separation; carry.followProgressAt=c.now(); }
				else if(c.now()-carry.followProgressAt>400) {
					releasePorter(c.level(),carry.porter); c.st().cooldownUntil=c.now()+200; return true;
				}
				c.v().getNavigation().stop();
				c.v().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
				return true;
			}
			carry.followBest=Double.POSITIVE_INFINITY; carry.followProgressAt=c.now();
		} else { carry.followBest=Double.POSITIVE_INFINITY; carry.followProgressAt=c.now(); }
		if(!JobUtil.approachLong(c,stand,1.5)) {
			if(c.st().cooldownUntil>c.now()) releasePorter(c.level(),carry.porter);
			return true;
		}
		// Re-resolve the category depot, ownership, source and destination at arrival.
		target=deliveryTarget(c.level(),ref,source.get());
		if(target==null||!target.pos().equals(carry.target)||c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand))>2.25
			|| donkey.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(target.pos()))>25.0) {
			releasePorter(c.level(),carry.porter); return true;
		}
		if("treasury".equals(target.depot())) {
			long room=Long.MAX_VALUE-Network.treasuryEmeralds(ref);
			int allowed=(int)Math.min(Math.min((long)carry.count,source.get().getCount()),room);
			if(!source.get().is(Items.EMERALD)||allowed<=0) { releasePorter(c.level(),carry.porter); return true; }
			ItemStack offered=source.get().copyWithCount(allowed), after=source.get().copy(); after.shrink(allowed);
			if(!source.set(after)) { releasePorter(c.level(),carry.porter); return true; }
			if(!Network.creditTreasuryEmeralds(ref,allowed)) {
				restoreCargoSlot(source,offered); releasePorter(c.level(),carry.porter); return true;
			}
			c.mem().jobsDone++;
			c.level().playSound(null,target.pos(),net.minecraft.sounds.SoundEvents.CHEST_CLOSE,net.minecraft.sounds.SoundSource.NEUTRAL,0.7F,1.1F);
			c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,c.v().getX(),c.v().getY()+1.2,c.v().getZ(),4,0.25,0.2,0.25,0.0);
			JobUtil.swing(c.v()); releasePorter(c.level(),carry.porter); c.st().clearTarget(); c.st().cooldownUntil=c.now()+20;
			return true;
		}
		int allowed=Math.min(carry.count,Math.min(source.get().getCount(),StockPolicy.deliveryRoom(c.level(),ref,source.get().getItem())));
		int planRoom = planStockRoomInChest(c.level(), ref, source.get().getItem());
		if (planRoom > 0) allowed = Math.min(allowed, planRoom);
		if(allowed<=0) { releasePorter(c.level(),carry.porter); return true; }
		boolean planDelivery = isPlanFreightSlot(donkey, carry.slot, source.get().getItem())
			|| planStockNeeded(c.level(), ref, source.get().getItem());
		ItemStack offered=source.get().copyWithCount(allowed); ItemStack after=source.get().copy(); after.shrink(allowed);
		if(!source.set(after)) { releasePorter(c.level(),carry.porter); return true; }
		ItemStack rest;
		try {
			rest = planDelivery
				? net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, target.container(), offered.copy(), null)
				: StockPolicy.insertInto(c.level(),ref,target.depot(),target.container(),offered.copy());
		}
		catch(RuntimeException ex) { restoreCargoSlot(source,offered); releasePorter(c.level(),carry.porter); throw ex; }
		int moved=allowed-rest.getCount();
		if(!rest.isEmpty()&&!restoreCargoSlot(source,rest)) {
			var drop=new net.minecraft.world.entity.item.ItemEntity(c.level(),target.pos().getX()+0.5,target.pos().getY()+1.0,target.pos().getZ()+0.5,rest.copy());
			drop.setDefaultPickUpDelay(); c.level().addFreshEntity(drop);
			org.slf4j.LoggerFactory.getLogger("villagerplus").error("Karawanen-Restfracht konnte nicht in den Esel-Slot zurückgelegt werden: {} bei {}",rest,target.pos());
		}
		if(moved<=0) { releasePorter(c.level(),carry.porter); return true; }
		if (planDelivery) {
			clearPlanFreightTagIfEmpty(donkey, offered.getItem(), carry.slot);
			Container materialDepot = ref.chest(c.level());
			if (materialDepot != null && !hasAnyPlanFreight(c.level(), ref)
				&& planShortagesSatisfied(c.level(), ref, materialDepot)) {
				Builder.caravanVisited(c.level(), ref, materialDepot);
			}
		}
		c.mem().jobsDone++;
		c.level().playSound(null,target.pos(),net.minecraft.sounds.SoundEvents.CHEST_CLOSE,net.minecraft.sounds.SoundSource.NEUTRAL,0.7F,1.1F);
		c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,c.v().getX(),c.v().getY()+1.2,c.v().getZ(),4,0.25,0.2,0.25,0.0);
		JobUtil.swing(c.v()); releasePorter(c.level(),carry.porter); c.st().clearTarget(); c.st().cooldownUntil=c.now()+20;
		return true;
	}

	private static boolean canAccept(Container box,ItemStack stack) {
		for(int i=0;i<box.getContainerSize();i++) { ItemStack current=box.getItem(i); if(current.isEmpty()) return true;
			if(ItemStack.isSameItemSameComponents(current,stack)&&current.getCount()<current.getMaxStackSize()) return true; }
		return false;
	}

	private static boolean hasDeliverableCargo(ServerLevel level,VillageRef ref,net.minecraft.world.entity.animal.equine.Donkey donkey) {
		for(int i=2;i<17;i++) { var access=donkey.getSlot(500+i); if(access!=null&&!access.get().isEmpty()&&deliveryTarget(level,ref,access.get())!=null)return true; }
		return false;
	}

	/** Cargo has to match a communal depot or the persistent cash ledger. */
	static DeliveryTarget deliveryTarget(ServerLevel level,VillageRef ref,ItemStack stack) {
		if(stack.isEmpty()) return null;
		if(stack.is(Items.EMERALD)) return new DeliveryTarget(ref.bell().immutable(),"treasury",null);
		boolean planStockNeeded = planStockNeeded(level, ref, stack.getItem());
		boolean planFreight = hasPlanFreightTag(level, ref, stack.getItem());
		if (!planStockNeeded && planFreight) {
			clearSatisfiedPlanFreight(level, ref, stack.getItem());
			planFreight = hasPlanFreightTag(level, ref, stack.getItem());
		}
		if(StockPolicy.deliveryRoom(level,ref,stack.getItem())<=0) return null;
		if (planStockNeeded || planFreight) {
			BlockPos pos = ref.chestPos(level);
			Container chest = pos == null ? null : ref.chest(level);
			return chest != null && canAccept(chest, stack)
				? new DeliveryTarget(pos.immutable(), de.villagerplus.build.Blueprints.STORAGE, chest) : null;
		}
		String depot;
		BlockPos pos;
		switch(StockPolicy.category(stack.getItem())) {
			case FOOD -> { depot=de.villagerplus.build.Blueprints.GRANARY; pos=Specials.depotWithRoom(level,ref,depot,stack); }
			case TOOLS -> { depot=de.villagerplus.build.Blueprints.TOOLSHED; pos=Specials.depotWithRoom(level,ref,depot,stack); }
			case MATERIAL -> {
				depot=de.villagerplus.build.Blueprints.STORAGE;
				pos=ref.chestPos(level);
				Container chest=pos==null?null:ref.chest(level);
				if(chest==null||!canAccept(chest,stack)) {
					pos=Lager.chestWithRoom(level,ref.data(),stack);
				}
			}
			case OTHER -> {
				if (!isRawSmeltingSupply(stack.getItem())) return null;
				depot=de.villagerplus.build.Blueprints.STORAGE;
				pos=ref.chestPos(level);
				Container chest=pos==null?null:ref.chest(level);
				if(chest==null||!canAccept(chest,stack)) pos=Lager.chestWithRoom(level,ref.data(),stack);
			}
			default -> { return null; }
		}
		Container destination=pos==null?null:HopperBlockEntity.getContainerAt(level,pos);
		return destination!=null&&canAccept(destination,stack)?new DeliveryTarget(pos.immutable(),depot,destination):null;
	}

	/** These are the only non-building OTHER items explicitly accepted by StockPolicy's public STORAGE depot. */
	private static boolean isRawSmeltingSupply(Item item) {
		Identifier id=BuiltInRegistries.ITEM.getKey(item);
		return id.getNamespace().equals("minecraft")
			&& (id.getPath().equals("raw_iron") || id.getPath().equals("raw_copper") || id.getPath().equals("raw_gold"));
	}

	private static boolean restoreCargoSlot(net.minecraft.world.entity.SlotAccess access,ItemStack rest) {
		if(rest.isEmpty()) return true;
		ItemStack current=access.get();
		if(current.isEmpty()) return access.set(rest.copy());
		if(!ItemStack.isSameItemSameComponents(current,rest)||current.getCount()+rest.getCount()>current.getMaxStackSize()) return false;
		current.grow(rest.getCount()); return access.set(current);
	}

	static BlockPos porterStand(ServerLevel level,BlockPos chest) {
		for(net.minecraft.core.Direction dir:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos adjacent=chest.relative(dir); if(!level.hasChunkAt(adjacent)) continue;
			var safe=JobUtil.safeSpot(level,net.minecraft.world.phys.Vec3.atBottomCenterOf(adjacent));
			if(safe!=null) return BlockPos.containing(safe);
		}
		return null;
	}

	private static boolean isPorterStand(ServerLevel level,BlockPos target,BlockPos stand) {
		if(stand==null) return false;
		for(net.minecraft.core.Direction dir:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos adjacent=target.relative(dir); if(!level.hasChunkAt(adjacent)) continue;
			var safe=JobUtil.safeSpot(level,net.minecraft.world.phys.Vec3.atBottomCenterOf(adjacent));
			if(safe!=null&&BlockPos.containing(safe).equals(stand)) return true;
		}
		return false;
	}

	/** Selects a free adjacent stand only if the villager's bounded work-path search can reach it. */
	private static BlockPos reachablePorterStand(Ctx c,BlockPos target) {
		List<BlockPos> candidates=new ArrayList<>();
		for(net.minecraft.core.Direction dir:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos adjacent=target.relative(dir); if(!c.level().hasChunkAt(adjacent)) continue;
			var safe=JobUtil.safeSpot(c.level(),net.minecraft.world.phys.Vec3.atBottomCenterOf(adjacent));
			if(safe!=null) candidates.add(BlockPos.containing(safe));
		}
		candidates.sort(java.util.Comparator.comparingDouble(p->p.distSqr(c.v().blockPosition())));
		for(BlockPos candidate:candidates) {
			var route=de.villagerplus.build.Builder.workPath(c,candidate);
			if(route!=null&&route.canReach()) return candidate;
		}
		return null;
	}

	/** Choose a reachable depot side beyond the animal's approach so its lead-follow path ends near storage. */
	private static BlockPos reachableDepotPorterStand(Ctx c,BlockPos target,
		net.minecraft.world.entity.animal.equine.Donkey donkey) {
		List<BlockPos> candidates=new ArrayList<>();
		for(net.minecraft.core.Direction dir:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos adjacent=target.relative(dir); if(!c.level().hasChunkAt(adjacent)) continue;
			var safe=JobUtil.safeSpot(c.level(),net.minecraft.world.phys.Vec3.atBottomCenterOf(adjacent));
			if(safe!=null) candidates.add(BlockPos.containing(safe));
		}
		// With the porter between the incoming donkey and the chest, leash-following
		// leaves the animal on the wrong side of the village. Prefer the opposite side.
		candidates.sort(java.util.Comparator.comparingDouble(
			(BlockPos p)->donkey.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(p))).reversed());
		for(BlockPos candidate:candidates) {
			var route=de.villagerplus.build.Builder.workPath(c,candidate);
			if(route!=null&&route.canReach()) return candidate;
		}
		return null;
	}

	/** Finds a bounded, real-navigation stand near the depot, separate from the porter lane. */
	private static PorterDonkeyRoute reachableDonkeyDepotRoute(Ctx c,
		net.minecraft.world.entity.animal.equine.Donkey donkey, BlockPos depot, BlockPos porterStand) {
		List<AABB> reserved=new ArrayList<>();
		for(State caravan:ACTIVE.values()) {
			if(!caravan.dimension.equals(c.level().dimension())||!caravan.llamas.contains(donkey.getUUID())) continue;
			Entity traderEntity=c.level().getEntity(caravan.trader);
			if(traderEntity instanceof AbstractVillager trader) {
				reserved.addAll(packDonkeyReservedBounds(c.level(),caravan,donkey,trader));
				break;
			}
		}
		reserved.add(c.v().getBoundingBox());
		reserved.add(CaravanSites.bounds(c.v(),porterStand));
		List<BlockPos> candidates=new ArrayList<>();
		for(int radius=2;radius<=3&&candidates.size()<24;radius++) {
			for(int dx=-radius;dx<=radius&&candidates.size()<24;dx++)for(int dz=-radius;dz<=radius&&candidates.size()<24;dz++) {
				if(Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
				BlockPos column=depot.offset(dx,0,dz);
				if(!c.level().hasChunkAt(column)) continue;
				BlockPos feet=c.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column);
				if(Math.abs(feet.getY()-depot.getY())>2||feet.distSqr(depot)>25L
					||!CaravanSites.canStand(c.level(),donkey,feet,reserved)) continue;
				candidates.add(feet.immutable());
			}
		}
		candidates.sort(java.util.Comparator.comparingDouble((BlockPos feet)->feet.distSqr(depot))
			.thenComparingDouble(feet->donkey.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet))));
		for(BlockPos candidate:candidates) {
			var path=donkey.getNavigation().createPath(candidate,1);
			if(usableDonkeyPath(c.level(),donkey,path,candidate,reserved)) return new PorterDonkeyRoute(candidate,path);
		}
		return null;
	}

	public static boolean carryingFor(Villager villager) { return PORTER_CARRIES.containsKey(villager.getUUID()); }

	static String porterDeliveryDiagnostic(ServerLevel level, Villager porter) {
		PorterCarry carry=PORTER_CARRIES.get(porter.getUUID());
		return carry==null ? PORTER_DIAGNOSTICS.getOrDefault(porter.getUUID(), "no delivery") : describeDelivery(level,porter,carry);
	}

	static String porterRetryDiagnostic(ServerLevel level, VillageRef ref, Villager porter,
			net.minecraft.world.entity.animal.equine.Donkey donkey, ItemStack cargo) {
		DeliveryTarget target=deliveryTarget(level,ref,cargo);
		return "porter="+porter.position()+", donkey="+donkey.position()+", alive="+donkey.isAlive()
			+", villageTag="+donkey.entityTags().contains(villageTag(ref.bell()))
			+", inSearchArea="+new AABB(ref.bell()).inflate(112).intersects(donkey.getBoundingBox())
			+", hasCargo="+hasCargo(donkey)+", claimedBy="+PORTER_CLAIMS.get(donkey.getUUID())
			+", category="+StockPolicy.category(cargo.getItem())+", policyRoom="+StockPolicy.deliveryRoom(level,ref,cargo.getItem())
			+", target="+(target==null?"none":target.depot()+"@"+target.pos())
			+", "+porterDeliveryDiagnostic(level,porter);
	}

	private static String describeDelivery(ServerLevel level, Villager porter, PorterCarry carry) {
		Entity donkey=level.getEntity(carry.donkey);
		var depot=net.minecraft.world.phys.Vec3.atBottomCenterOf(carry.target);
		return "carry.target="+carry.target+", porter="+porter.position()+", porterDepotSq="+porter.position().distanceToSqr(depot)
			+", donkey="+(donkey==null?"unloaded":donkey.position()+", donkeyDepotSq="+donkey.position().distanceToSqr(depot)
				+", separationSq="+donkey.distanceToSqr(porter))+", pickedUp="+carry.pickedUp;
	}

	/** Equips the same real chest item as caravan spawn, then writes freight through the donkey's exposed cargo slots. */
	public static void prepareDonkeyCargo(net.minecraft.world.entity.animal.equine.Donkey donkey, int cargoSlot, ItemStack stack) {
		var chestLayout = donkey.getSlot(499);
		if (chestLayout == null) throw new IllegalStateException("donkey chest equipment slot unavailable");
		if ((!donkey.hasChest() || !chestLayout.get().is(Items.CHEST))
			&& (!chestLayout.set(new ItemStack(Items.CHEST)) || !donkey.hasChest())) {
			throw new IllegalStateException("donkey chest equipment unavailable after equipping: " + chestLayout.get());
		}
		var access = donkey.getSlot(500 + cargoSlot);
		if (access != null && access.set(stack.copy())) return;
		for (int slot = 2; slot < 17; slot++) {
			var fallback = donkey.getSlot(500 + slot);
			if (fallback == null) continue;
			if (fallback.set(stack.copy())) return;
		}
		throw new IllegalStateException("donkey cargo slot unavailable: " + cargoSlot
			+ " (hasChest=" + donkey.hasChest() + ", chest=" + chestLayout.get() + ")");
	}

	public static void releasePorter(Villager villager) {
		if(villager.level() instanceof ServerLevel level) releasePorter(level,villager.getUUID());
	}

	private static void releasePorter(ServerLevel level,UUID porter) {
		PorterCarry carry=PORTER_CARRIES.remove(porter); if(carry==null) return;
		if(level.getEntity(porter) instanceof Villager v) {
			if(PORTER_DIAGNOSTICS.size()>=256) PORTER_DIAGNOSTICS.clear();
			PORTER_DIAGNOSTICS.put(porter,describeDelivery(level,v,carry));
		}
		PORTER_CLAIMS.remove(carry.donkey,porter);
		if(level.getEntity(porter) instanceof Villager villager) {
			villager.getNavigation().stop();
			villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
		}
		Entity visual=carry.visual==null?null:level.getEntity(carry.visual); if(visual!=null) visual.discard();
		Entity donkey=level.getEntity(carry.donkey);
		if(donkey instanceof net.minecraft.world.entity.animal.equine.Donkey d) {
			d.getNavigation().stop();
			if(carry.traderLeash!=null) {
				Entity trader=level.getEntity(carry.traderLeash);
				if(trader instanceof AbstractVillager && trader.isAlive()) d.setLeashedTo(trader,true);
				else d.removeLeash();
			} else {
				Entity holder=d.getLeashHolder();
				if(holder!=null&&holder.getUUID().equals(carry.porter)) d.removeLeash();
			}
			syncCargoVisual(level,d);
		}
	}

	/** Reconciles chunk-persisted render markers after either their owner donkey or the marker itself loads. */
	private static void reconcileCargoDisplay(ServerLevel level, ItemDisplay display) {
		for (String tag : display.entityTags()) {
			if (!tag.startsWith("vp_caravan_cargo_owner:")) continue;
			try {
				UUID owner = UUID.fromString(tag.substring("vp_caravan_cargo_owner:".length()));
				if (level.getEntity(owner) instanceof net.minecraft.world.entity.animal.equine.Donkey) {
					CARGO_RECONCILE.put(owner, level.dimension());
				}
			} catch (IllegalArgumentException ignored) { }
			return;
		}
	}

	private static boolean hasCargo(ServerLevel level, State state) {

		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {

			for (int slot = 2; slot < 17; slot++) { var access = donkey.getSlot(500 + slot); if (access != null && !access.get().isEmpty()) return true; }

		}

		return false;

	}

	private static boolean hasCargo(net.minecraft.world.entity.animal.equine.Donkey donkey) {

		for (int slot = 2; slot < 17; slot++) { var access = donkey.getSlot(500 + slot); if (access != null && !access.get().isEmpty()) return true; }

		return false;

	}

	/** Beim Abbruch oder Serverende bleibt nicht entladene Ware auf einem persistenten Frachtesel erhalten. */
	private static void preserveCargo(ServerLevel level, State state) {

		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey)) {

			Entity leashHolder = donkey.getLeashHolder();
			if (leashHolder == null || leashHolder.getUUID().equals(state.trader)) donkey.removeLeash();
			donkey.removeTag(CARAVAN_TAG); donkey.setPersistenceRequired();

			donkey.setCustomName(Component.literal("Karawanen-Frachtesel")); donkey.setCustomNameVisible(false);
			syncCargoVisual(level, donkey);

		}

	}

	/** Entlaedt hoechstens einen Stapel pro Aufruf und behaelt ihn im Esel, wenn Dorfkiste und Lager voll sind. */
	private static boolean unloadOne(ServerLevel level, VillageRef ref, State state) {

		for (UUID id : state.llamas) if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {

			for (int slot = 2; slot < 17; slot++) {

				var access = donkey.getSlot(500 + slot); if (access == null) continue;

				ItemStack cargo = access.get(); if (cargo.isEmpty()) continue;

				int before = cargo.getCount(); ItemStack rest = StockPolicy.insertVillage(level, ref, cargo);

				int moved = before - rest.getCount();

				if (moved > 0) {

					cargo.shrink(moved); access.set(cargo.isEmpty() ? ItemStack.EMPTY : cargo);
					syncCargoVisual(level, donkey);

					level.playSound(null, donkey.blockPosition(), net.minecraft.sounds.SoundEvents.CHEST_CLOSE, net.minecraft.sounds.SoundSource.NEUTRAL, 0.7F, 1.1F);

					level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, donkey.getX(), donkey.getY() + 1.2, donkey.getZ(), 3, 0.25, 0.2, 0.25, 0.0);

					return true;

				}

			}

		}

		return false;

	}

	/** Flache Stelle ~36 Bloecke von der Glocke in Richtung Startpunkt fuer das Rastlager. */
	private static BlockPos campSite(ServerLevel level, BlockPos bell, BlockPos spawn) {
		List<BlockPos> candidates = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		double dx = spawn.getX() - bell.getX(), dz = spawn.getZ() - bell.getZ(), len = Math.max(1.0, Math.hypot(dx, dz));

		for (int dist : new int[] {36, 32, 40, 28}) {

			for (int lateral : new int[] {0, 6, -6, 12, -12}) {

				int x = bell.getX() + (int) Math.round(dx / len * dist - dz / len * lateral), z = bell.getZ() + (int) Math.round(dz / len * dist + dx / len * lateral);

				BlockPos col = new BlockPos(x, bell.getY(), z);

				if (!level.hasChunkAt(col.offset(-3, 0, -3)) || !level.hasChunkAt(col.offset(3, 0, 3))) continue;

				BlockPos c = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col);
				if (seen.add(c.asLong())) candidates.add(c.immutable());

			}

		}

		return firstUsableCampSite(candidates, c -> campSiteUsable(level, c));

	}

	/** Selects the first complete safe site in the deterministic search order; later clear candidates remain fallbacks. */
	static BlockPos firstUsableCampSite(List<BlockPos> candidates, java.util.function.Predicate<BlockPos> usable) {
		if (candidates == null || usable == null) return null;
		for (BlockPos candidate : candidates) {
			if (candidate != null && usable.test(candidate)) return candidate.immutable();
		}
		return null;
	}

	/** Caravan-owned temporary camp: striped canopy, two animal hitching points, and a separate cooking corner. */
	static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> campLayout(BlockPos c) {
		var blocks = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
		var fence = Blocks.SPRUCE_FENCE.defaultBlockState();
		// Raised tent frame and a red-and-white 5x3 canopy read as one temporary shelter from the road.
		for (int x : new int[] {-2, 2}) for (int z : new int[] {-1, 1}) {
			for (int y = 0; y < 3; y++) blocks.put(c.offset(x, y, z), fence);
		}
		// The canopy also covers the two tether posts (x = -3, z = +-2) so every post is anchored to the roof.
		for (int x = -3; x <= 2; x++) for (int z = -2; z <= 2; z++) {
			blocks.put(c.offset(x, 3, z), ((x + z) & 1) == 0
				? Blocks.WOOL.red().defaultBlockState() : Blocks.WOOL.white().defaultBlockState());
		}
		blocks.put(c.above(4), Blocks.WOOL.red().defaultBlockState());

		// The left-hand side is the pack-animal stop: two tall tether posts with hay within reach.
		for (int z : new int[] {-2, 2}) {
			for (int y = 0; y < 3; y++) blocks.put(c.offset(-3, y, z), fence);
			blocks.put(c.offset(-2, 0, z), Blocks.HAY_BLOCK.defaultBlockState());
		}
		blocks.put(c.offset(-3, 0, 0), Blocks.BARREL.defaultBlockState());

		// A cobble pad sets the campfire apart from the wool shelter; the smoker and cauldron make the cooking corner legible.
		for (BlockPos pad : List.of(c.offset(2, 0, 0), c.offset(3, 0, -1), c.offset(3, 0, 1))) {
			blocks.put(pad, Blocks.COBBLESTONE.defaultBlockState());
		}
		blocks.put(c.offset(3, 0, 0), unlitCampfireState());
		blocks.put(c.offset(3, 0, 2), Blocks.SMOKER.defaultBlockState());
		blocks.put(c.offset(2, 0, 2), Blocks.CAULDRON.defaultBlockState());
		return blocks;
	}

	static net.minecraft.world.level.block.state.BlockState unlitCampfireState() {
		return net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState()
			.setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false);
	}

	static boolean campSiteUsable(ServerLevel level, BlockPos c) {
		var layout = campLayout(c);
		return CaravanSites.canPlaceCampAt(level, c, layout.keySet())
			&& CaravanCampSecurity.safePost(level, c, layout.keySet());
	}

	/** Lights the camp only after taking one real coal or charcoal from a communal village store. */
	static boolean tryIgniteCampfire(ServerLevel level, BlockPos pos, List<Container> stores) {
		if (pos == null || !level.hasChunkAt(pos)) return false;
		var fire = level.getBlockState(pos);
		if (!fire.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)) return false;
		if (fire.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)) return true;

		for (Container store : stores) {
			if (store == null) continue;
			for (int slot = 0; slot < store.getContainerSize(); slot++) {
				ItemStack available = store.getItem(slot);
				if (available.isEmpty() || (!available.is(Items.COAL) && !available.is(Items.CHARCOAL))) continue;
				ItemStack original = available.copy();
				ItemStack consumed = store.removeItem(slot, 1);
				if (consumed.isEmpty()) continue;
				store.setChanged();

				boolean lit = level.setBlock(pos, fire.setValue(net.minecraft.world.level.block.CampfireBlock.LIT, true), 3)
					&& level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
					&& level.getBlockState(pos).getValue(net.minecraft.world.level.block.CampfireBlock.LIT);
				if (lit) return true;

				// The source slot was just freed on this server thread, so a failed world update can
				// return the exact original stack without dropping fuel or overwriting another item.
				store.setItem(slot, original);
				store.setChanged();
				return false;
			}
		}
		return false;
	}

	private static void serviceCampfire(ServerLevel level, State state, VillageRef ref) {
		long now = level.getGameTime();
		if (!state.campBuilt || now < state.campRetryAt) return;
		for (BlockPos pos : state.camp) {
			if (!level.hasChunkAt(pos)) continue;
			var fire = level.getBlockState(pos);
			if (!fire.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)) continue;
			if (!fire.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)) {
				tryIgniteCampfire(level, pos, Balance.storage(level, ref));
			}
			state.campRetryAt = now + 200;
			return;
		}
		state.campRetryAt = now + 200;
	}

	/** Temporaeres Lager: Zeltplane auf Zaunpfosten, Feuerstelle, Fass und Heuballen. Alles wird beim Abzug wieder abgebaut. */
	private static boolean buildCamp(ServerLevel level, State s, BlockPos bell) {
		long now = level.getGameTime();
		if (now < s.campRetryAt) return false;
		BlockPos c = campSite(level, bell, s.spawn);
		if (c == null) {
			s.campRetryAt = now + 200;
			return false;
		}
		var layout = campLayout(c);
		if (!campSiteUsable(level, c)) {
			s.campRetryAt = now + 200;
			return false;
		}
		List<CaravanCampData.CampBlock> planned = layout.entrySet().stream()
			.map(entry -> new CaravanCampData.CampBlock(entry.getKey(), entry.getValue())).toList();
		if (!persistCampPlan(level, bell, planned)) {
			s.campRetryAt = now + 200;
			return false;
		}
		for (var entry : layout.entrySet()) {
			if (!level.setBlock(entry.getKey(), entry.getValue(), 3)) {
				removeCamp(level, s);
				s.campRetryAt = now + 200;
				return false;
			}
			s.camp.add(entry.getKey().immutable());
			s.campBlocks.add(entry.getValue());
		}
		s.campBuilt = true;
		s.guardDutyEnded = false;
		s.armedGuardRemoved = false;
		level.playSound(null, c, net.minecraft.sounds.SoundEvents.WOOL_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawanen-Lager bei {} ({} Bloecke)", c, s.camp.size());
		return true;
	}

	/** Baut nur die Bloecke wieder ab, die noch genau so dort stehen (nichts Fremdes anfassen). */
	private static void removeCamp(ServerLevel level, State s) {
		CaravanCampSecurity.removeArmedGuard(level, s.trader);
		s.armedGuardVisual = null;
		s.armedGuardRemoved = true;
		if (s.guard != null && level.getEntity(s.guard) instanceof net.minecraft.world.entity.animal.golem.IronGolem guard) {
			CaravanCampSecurity.end(guard);
		}
		s.guardDutyEnded = true;
		List<CaravanCampData.CampBlock> records = new ArrayList<>();
		for (int i = 0; i < Math.min(s.camp.size(), s.campBlocks.size()); i++) records.add(new CaravanCampData.CampBlock(s.camp.get(i), s.campBlocks.get(i)));
		var bell = loadedBell(level, s.bell);
		if (bell != null) {
			List<CaravanCampData.CampBlock> persisted = attachedCampBlocks(bell);
			if (!persisted.isEmpty()) records = persisted;
		}
		List<CaravanCampData.CampBlock> remaining = clearLoadedCampBlocks(level, records);
		if (bell != null) saveCampBlocks(bell, remaining);
		queuePendingCampCleanup(level, s.bell, remaining);
		s.camp.clear(); s.campBlocks.clear();
		s.campBuilt = false;

	}

	/** Clear the temporary camp before the merchant and pack animals start their return route. */
	private static boolean clearCampForDeparture(ServerLevel level, State state) {
		if (!state.campBuilt && state.camp.isEmpty() && state.campBlocks.isEmpty()) return false;
		removeCamp(level, state);
		return true;
	}

	static boolean clearCampForDepartureForChecks(ServerLevel level, State state) {
		return clearCampForDeparture(level, state);
	}

	private static boolean returnBackedTradeStock(ServerLevel level, State state) {
		if (!(level.getEntity(state.trader) instanceof CaravanTraderEntity trader) || !trader.hasBackedTradeStock()) return true;
		for (ItemStack stock : trader.backedTradeStockSnapshot()) {
			if (stock.isEmpty()) continue;
			ItemStack remaining = stock.copy();
			int packed = packCargo(level, state, remaining);
			if (packed > 0 && !trader.consumeBackedTradeStock(stock.copyWithCount(packed))) return false;
			if (!remaining.isEmpty()) return false;
		}
		return !trader.hasBackedTradeStock();
	}

	private static boolean cleanup(ServerLevel level, State s) {
		// A due return must bring every live registered donkey home before cleanup. An unloaded or
		// blocked member keeps the active visit and its real inventory intact for a later retry.
		if (departureStarted(s, level.getGameTime()) && !packAnimalsArrivedOrReleased(level, s)) return false;
		if (level.getEntity(s.trader) instanceof AbstractVillager trader && trader.getTradingPlayer() != null) return false;
		// Unspent reserved goods and real emerald payments return to caravan inventory before
		// the merchant can be removed. If capacity is unavailable, keep the active entity/state.
		if (!returnBackedTradeStock(level, s)) return false;

		removeCamp(level, s);

		preserveCargo(level, s);
		releasePackDonkeysAfterCleanup(level, s);
		// Persist the empty membership list before removing the trader. A save between
		// release and entity removal can then resume cleanup without reattaching animals.
		persistVisitState(level, s);

		for (UUID id : List.copyOf(s.llamas)) {
			Entity e = level.getEntity(id);
			if (e != null) e.discard();
		}

		Entity e = level.getEntity(s.trader); if (e != null) e.discard();
		return true;

	}

	/** Final cleanup releases every registered pack donkey from this visit without deleting empty animals. */
	private static void releasePackDonkeysAfterCleanup(ServerLevel level, State state) {
		String village = villageTag(state.bell);
		for (UUID id : List.copyOf(state.llamas)) {
			if (!(level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)) continue;
			Entity leashHolder = donkey.getLeashHolder();
			if (leashHolder == null || leashHolder.getUUID().equals(state.trader)) donkey.removeLeash();
			donkey.removeTag(CARAVAN_TAG);
			donkey.removeTag(village);
			donkey.setPersistenceRequired();
			if (hasCargo(donkey)) {
				donkey.setCustomName(Component.literal("Karawanen-Frachtesel"));
				donkey.setCustomNameVisible(false);
			}
			syncCargoVisual(level, donkey);
			state.llamas.remove(id);
		}
	}

	static boolean cleanupForChecks(ServerLevel level, State state) {
		return cleanup(level, state);
	}

	/** Keep the camp guard at its post while the trader is serving the village; on departure it rejoins the caravan. */
	static BlockPos guardTarget(List<BlockPos> camp, boolean campBuilt, boolean leaving, BlockPos caravanTarget) {
		if (!campBuilt || leaving || camp == null || camp.isEmpty()) return caravanTarget;
		BlockPos post = CaravanCampSecurity.post(camp);
		return post == null ? caravanTarget : post;
	}

	/** Drops persisted visuals whose transient caravan owner no longer exists and collapses duplicate saves. */
	static void reconcileArmedGuardVisuals(ServerLevel level) {
		List<Entity> visuals = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (!entity.isRemoved() && CaravanCampSecurity.isArmedGuard(entity)) visuals.add(entity);
		}
		for (Entity entity : visuals) {
			State owner = null;
			for (State candidate : ACTIVE.values()) {
				if (candidate.dimension.equals(level.dimension())
					&& CaravanCampSecurity.isOwnedArmedGuard(entity, candidate.trader)) {
					owner = candidate;
					break;
				}
			}
			if (owner == null || !owner.campBuilt || owner.guardDutyEnded || owner.armedGuardRemoved) {
				entity.discard();
				continue;
			}
			if (owner.armedGuardVisual == null) owner.armedGuardVisual = entity.getUUID();
			else if (!owner.armedGuardVisual.equals(entity.getUUID())) entity.discard();
		}
	}

	private static boolean hasActiveGuardCamp(ServerLevel level) {
		for (State candidate : ACTIVE.values()) {
			if (candidate.dimension.equals(level.dimension()) && candidate.campBuilt
				&& !candidate.guardDutyEnded && !candidate.armedGuardRemoved) return true;
		}
		return false;
	}

	static boolean shouldReconcileArmedGuardVisuals(long gameTime, boolean activeGuardCamp, boolean startupScanPending) {
		if (gameTime % ARMED_GUARD_RECONCILE_INTERVAL != 0L) return false;
		return activeGuardCamp || startupScanPending || gameTime % ORPHAN_GUARD_RECONCILE_INTERVAL == 0L;
	}

	static boolean shouldRunCargoStartupScan(long gameTime, Long scanAt) {
		return scanAt != null && gameTime >= scanAt;
	}

	private static void tick(ServerLevel level) {
		if (level.getGameTime() % 20L == 0L) {
			processPendingCampCleanups(level);
			processPendingCaravanAdoptions(level);
			processPendingCaravanOrphans(level);
			boolean startupScanPending = ARMED_GUARD_STARTUP_RECONCILE.remove(level.dimension());
			if (shouldReconcileArmedGuardVisuals(level.getGameTime(), hasActiveGuardCamp(level), startupScanPending)) {
				reconcileArmedGuardVisuals(level);
			}
		}
		Long cargoStartupScanAt = CARGO_STARTUP_SCAN.get(level.dimension());
		if (shouldRunCargoStartupScan(level.getGameTime(), cargoStartupScanAt)) {
			// Do one reconciliation after startup; every donkey/display loaded later is
			// already covered by ENTITY_LOAD and CARGO_RECONCILE above.
			CARGO_STARTUP_SCAN.remove(level.dimension());
			for (Entity candidate : level.getAllEntities()) {
				if (candidate instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && hasCargo(donkey)
					&& donkey.getCustomName() != null && donkey.getCustomName().getString().equals("Karawanen-Frachtesel")) syncCargoVisual(level, donkey);
			}
		}
		for (var entry : new HashMap<>(CARGO_RECONCILE).entrySet()) {
			if (!entry.getValue().equals(level.dimension())) continue;
			Entity owner = level.getEntity(entry.getKey());
			if (owner instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) syncCargoVisual(level, donkey);
			CARGO_RECONCILE.remove(entry.getKey());
		}

		if (level.getGameTime() % 4 != 0) return;

		for (Iterator<State> it = ACTIVE.values().iterator(); it.hasNext();) {

			State s = it.next(); if (!s.dimension.equals(level.dimension())) continue;

			Entity e = level.getEntity(s.trader);

			if (!(e instanceof AbstractVillager trader) || !trader.isAlive()) {
				if (e == null && UNLOADED_CARAVAN_TRADERS.contains(s.trader)) continue;

				if (e == null && !level.hasChunkAt(s.spawn) && level.getGameTime() < s.born + 10000) continue;

				if (cleanup(level, s)) it.remove();
				else persistVisitState(level, s);
				continue;

			}

			boolean leaving = departureStarted(s, level.getGameTime());
			if (leaving && trader.getTradingPlayer() == null) clearCampForDeparture(level, s);
			if (!level.hasChunkAt(s.bell)) {
				// A due caravan must keep travelling even after its departure village unloads.
				// The saved spawn and donkey UUIDs are enough to continue safely; never load the bell chunk.
				if (departureCanAdvanceWithoutVillage(level, s)
					&& continueDepartureWithoutVillage(level, s, trader)) it.remove();
				continue;
			}
			VillageRef ref = resolve(level, s.bell);
			if (ref == null && s.traded) {
				if (!leaving) {
					if (beginDepartureNow(s, level.getGameTime())) persistVisitState(level, s);
				}
				if (continueDepartureWithoutVillage(level, s, trader)) it.remove();
				continue;
			}

			if (shouldExpireWithoutCargo(s, level.getGameTime(), hasCargo(level, s))) {
				if (s.traded) {
					beginDepartureNow(s, level.getGameTime());
					leaving = true;
					persistVisitState(level, s);
				} else {
					if (cleanup(level, s)) it.remove();
					else persistVisitState(level, s);
					continue;
				}
			}
			if (ref == null) {
				if (cleanup(level, s)) it.remove();
				else persistVisitState(level, s);
				continue;
			}
			for (UUID donkeyId : s.llamas) if (level.getEntity(donkeyId) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) syncCargoVisual(level, donkey);

			int uses = trader.getOffers().stream().mapToInt(net.minecraft.world.item.trading.MerchantOffer::getUses).sum();

			int delta = Math.max(0, uses - s.uses); s.uses = uses;

			if (delta > 0 && (s.routeBell != null || validPartner(ref, s))) {

				int reward = Math.min(10 - s.reward, delta * 2); s.reward += reward;
				long visitDay = level.getGameTime() / 24000;
				if (s.routeBell != null) recordLinkedRouteVisit(ref, s, visitDay, reward);
				else changeRelation(ref, s.partner, reward, visitDay);
				showPartnerName(ref, s, trader);

				var player = level.getNearestPlayer(trader, 12);

				if (player != null) VillageMessages.system(player, Component.literal("Handel abgeschlossen: Beziehung +" + reward));

			}

			if (!s.campBuilt && trader.position().distanceToSqr(ref.bell().getX() + 0.5, ref.bell().getY(), ref.bell().getZ() + 0.5) <= 60 * 60) buildCamp(level, s, ref.bell());
			serviceCampfire(level, s, ref);

			if (leaving && !s.armedGuardRemoved) {
				CaravanCampSecurity.removeArmedGuard(level, s.trader);
				s.armedGuardVisual = null;
				s.armedGuardRemoved = true;
			}
			if (s.guard != null && level.getEntity(s.guard) instanceof net.minecraft.world.entity.animal.golem.IronGolem guard) {
				if (leaving) {
					if (!s.guardDutyEnded) CaravanCampSecurity.end(guard);
					s.guardDutyEnded = true;
				} else if (s.campBuilt && !s.guardDutyEnded) {
					CaravanCampSecurity.defend(level, guard, s.camp, true);
				}
			}
			if (s.armedGuardVisual != null
				&& level.getEntity(s.armedGuardVisual) instanceof Villager armedGuard) {
				CaravanCampSecurity.defendArmedGuard(level, armedGuard, s.camp,
					s.campBuilt && !leaving && !s.guardDutyEnded && !s.armedGuardRemoved);
			}
			if (s.campBuilt && !leaving && !s.guardDutyEnded && !s.armedGuardRemoved && level.getGameTime() % 20L == 0L) {
				var armedGuard = CaravanCampSecurity.ensureArmedGuard(level, s.trader, s.camp);
				s.armedGuardVisual = armedGuard == null ? null : armedGuard.getUUID();
			}

			// The caravan no longer commits cargo remotely. Porters carry it to this crate and commit only on arrival.
			if (scheduleDepartureAfterTrade(s, level.getGameTime())) persistVisitState(level, s);
			BlockPos chest = ref.chestPos(level);
			if (chest == null && !leaving) continue;

			boolean waitForDonkeys = false;
			boolean wasWaitingForDonkeys = s.waitingForDonkeys;
			if (leaving && trader.getTradingPlayer() == null) {
				guideDepartingDonkeys(level, s, trader, level.getGameTime());
				waitForDonkeys = s.waitingForDonkeys
					? !packAnimalsCanResume(level, s, trader)
					: !packAnimalsCanKeepUp(level, s, trader);
				s.waitingForDonkeys = waitForDonkeys;
			} else {
				guideVisitingDonkeys(level, s, trader, level.getGameTime());
				waitForDonkeys = s.waitingForDonkeys
					? !packAnimalsWithinDistance(level, s, trader, DONKEY_RESUME_DISTANCE_SQR, false)
					: !packAnimalsWithinDistance(level, s, trader, DONKEY_WAIT_DISTANCE_SQR, false);
				s.waitingForDonkeys = waitForDonkeys;
			}
			BlockPos depotStand = leaving ? null : safeDepotArrivalStand(level, trader, chest);
			BlockPos target = waitForDonkeys ? holdForPackAnimals(level, trader, !wasWaitingForDonkeys)
				: leaving ? s.spawn : depotStand == null ? safePackWaitSpot(level, trader) : depotStand;
			if (target == null) target = trader.blockPosition();

			if (s.guard != null && level.getGameTime() % 20 == 0
				&& level.getEntity(s.guard) instanceof net.minecraft.world.entity.animal.golem.IronGolem g) {
				BlockPos guardTarget = guardTarget(s.camp, s.campBuilt, leaving, target);
				CaravanCampSecurity.returnTo(level, g, guardTarget);
			}

			if (level.getGameTime() % 20 == 0 && trader.getTradingPlayer() == null) {
				if (!waitForDonkeys && (leaving || depotStand != null)) {
					BlockPos roadTarget = roadWaypoint(level, ref, trader.blockPosition(), target, s, leaving);
					if (roadTarget != null) target = roadTarget;
					else if (s.routeBell != null && !routeEndpointNearby(trader, target, s, leaving)) {
						// A real-destination caravan waits if a route section unloads or becomes blocked;
						// it never abandons the persisted destination for an impossible direct path.
						target = trader.blockPosition();
						trader.getNavigation().stop();
					}
				}
				if (trader instanceof CaravanTraderEntity caravanTrader) {
					caravanTrader.setRouteTarget(target);
				} else if (trader instanceof WanderingTrader wanderingTrader) {
					wanderingTrader.setWanderTarget(target);
					trader.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5);
				}

			}
			boolean atChest = depotStand != null
				&& trader.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(depotStand)) <= 1.0
				&& trader.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(chest)) <= 16
				&& !trader.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(chest));
			if (s.traded && atChest && trader instanceof CaravanTraderEntity custom && !custom.backedTradeOffersInitialized()) {
				prepareBackedMerchantOffers(level, s, custom);
			}

			if (!s.traded && atChest) {

				if (ref.chest(level) == null) continue;
				s.traded = true; s.leaveAt = Long.MAX_VALUE; s.nextUnload = level.getGameTime() + 20;

				boolean successful = exchange(level, ref, trader);
				if (s.routeBell != null) {
					if (successful) recordLinkedRouteVisit(ref, s, level.getGameTime() / 24000, 3);
				} else {
					recordExchange(ref, s.partner, level.getGameTime() / 24000, successful);
				}
				if (trader instanceof CaravanTraderEntity custom) prepareBackedMerchantOffers(level, s, custom);
				if (successful) {
					showPartnerName(ref, s, trader);
				}

				org.slf4j.LoggerFactory.getLogger("villagerplus").info("Karawane angekommen bei {}, Distanz {}", trader.blockPosition(), Math.sqrt(trader.blockPosition().distSqr(chest)));

			} else if (leaving && trader.getTradingPlayer() == null
				&& trader.blockPosition().distSqr(s.spawn) <= 16
				&& packAnimalsAtSpawn(level, s)) {

				if (!recordReturnedLinkedRouteVisit(level, ref, s)) {
					persistVisitState(level, s);
					continue;
				}
				persistVisitState(level, s);

				if (cleanup(level, s)) it.remove();
				else persistVisitState(level, s);

			}

			persistVisitState(level, s);

		}

	}

	private static boolean departureStarted(State state, long now) {
		return state.traded && state.leaveAt != Long.MAX_VALUE && now >= state.leaveAt;
	}

	/** A due return only needs its saved destination and members when the source village is unloaded. */
	static boolean departureCanAdvanceWithoutVillage(ServerLevel level, State state) {
		return departureStarted(state, level.getGameTime()) && !level.hasChunkAt(state.bell);
	}

	/** Start the normal post-trade visit timer even when the pack animals still carry undelivered freight. */
	static boolean scheduleDepartureAfterTrade(State state, long now) {
		if (!state.traded || state.leaveAt != Long.MAX_VALUE) return false;
		state.leaveAt = now + 1200L;
		return true;
	}

	/** A traded visit whose village disappeared still gets a real, safe return trip. */
	static boolean beginDepartureNow(State state, long now) {
		if (!state.traded || state.leaveAt != Long.MAX_VALUE && state.leaveAt <= now) return false;
		state.leaveAt = now;
		return true;
	}

	/** A stale-visit timeout must never discard empty pack animals from a departure still in progress. */
	static boolean shouldExpireWithoutCargo(State state, long now, boolean hasCargo) {
		return now > state.born + 12000L && !hasCargo && !departureStarted(state, now);
	}

	/** Keep a due departure alive if its village block entity disappeared; use normal navigation to finish safely. */
	static boolean continueDepartureWithoutVillage(ServerLevel level, State state, AbstractVillager trader) {
		long now = level.getGameTime();
		if (trader.getTradingPlayer() == null) clearCampForDeparture(level, state);
		guideDepartingDonkeys(level, state, trader, now);
		boolean wasWaiting = state.waitingForDonkeys;
		boolean waitForDonkeys = wasWaiting
			? !packAnimalsCanResume(level, state, trader)
			: !packAnimalsCanKeepUp(level, state, trader);
		state.waitingForDonkeys = waitForDonkeys;
		BlockPos target = waitForDonkeys || !level.hasChunkAt(state.spawn)
			? holdForPackAnimals(level, trader, !wasWaiting) : state.spawn;
		if (now % 20L == 0L && trader.getTradingPlayer() == null) {
			if (trader instanceof CaravanTraderEntity custom) {
				custom.setRouteTarget(target);
			} else if (trader instanceof WanderingTrader wandering) {
				wandering.setWanderTarget(target);
				trader.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5);
			}
		}
		boolean arrived = trader.getTradingPlayer() == null && trader.blockPosition().distSqr(state.spawn) <= 16L
			&& packAnimalsAtSpawn(level, state);
		if (arrived && cleanup(level, state)) return true;
		persistVisitState(level, state);
		return false;
	}

	/**
	 * Keep the real pack animals travelling under their own navigation. The vanilla lead
	 * remains attached for short-range following, while an explicit path to the loaded
	 * trader lets a donkey recover after a long leash snap or a blocked route. This never
	 * looks up an unloaded entity by loading its chunk and never moves an entity directly.
	 */
	static void guideDepartingDonkeys(ServerLevel level, State state, AbstractVillager trader, long now) {
		if (now < state.nextDonkeyFollowAt) return;
		state.nextDonkeyFollowAt = now + 20L;
		state.departurePathReady = true;
		for (UUID id : List.copyOf(state.llamas)) {
			if (id.equals(state.guard)) continue;
			Entity entity = level.getEntity(id);
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) || !donkey.isAlive()) {
				// A missing entity may be in an unloaded chunk. Keep its UUID and cargo reservation;
				// the trader waits and retries when that entity becomes available again.
				state.departurePathReady = false;
				continue;
			}
			donkey.setPersistenceRequired();
			// A different leash holder has taken control of this animal. Leave that leash
			// untouched and release the donkey from the caravan without discarding its cargo.
			Entity leashHolder = donkey.getLeashHolder();
			if (leashHolder != null && leashHolder != trader) {
				donkey.removeTag(CARAVAN_TAG);
				donkey.removeTag(villageTag(state.bell));
				if (hasCargo(donkey)) {
					donkey.setCustomName(Component.literal("Karawanen-Frachtesel"));
					donkey.setCustomNameVisible(false);
					syncCargoVisual(level, donkey);
				}
				state.llamas.remove(id);
				persistVisitState(level, state);
				continue;
			}
			// The caravan owns this lead. Reattach a broken lead, then refresh the moving
			// trader target once per cadence so the donkey does not follow a stale path.
			if (donkey.getLeashHolder() == null) donkey.setLeashedTo(trader, true);
			// In the last four blocks of the return, the trader can satisfy its arrival
			// check before a donkey reaches one of the safe cells beside the saved point.
			// Keep guiding to the saved spawn through that final approach.
			boolean returningHome = trader.blockPosition().distSqr(state.spawn) <= 16L;
			boolean donkeyArrived = returningHome
				? safeHomeArrivalCell(donkey, donkey.blockPosition(), state.spawn, trader)
				: donkey.distanceToSqr(trader) <= DONKEY_FOLLOW_DISTANCE_SQR;
			if (!donkeyArrived) {
				boolean guided = returningHome
					? guideDonkeyAlongUsablePath(level, state, donkey, trader, state.spawn, true)
					: guideDonkeyAlongUsablePath(level, state, donkey, trader);
				if (!guided) {
					// Do not let the trader walk out of the animal's loaded follow area when
					// terrain/another caravan member blocks the donkey's real navigation.
					donkey.getNavigation().stop();
					state.departurePathReady = false;
				}
			} else if (donkey.getNavigation().isInProgress()) {
				donkey.getNavigation().stop();
			}
		}
	}

	/** Keep the pack close during arrival and trade so their lead cannot trail across the village. */
	static void guideVisitingDonkeys(ServerLevel level, State state, AbstractVillager trader, long now) {
		if (now < state.nextDonkeyFollowAt) return;
		state.nextDonkeyFollowAt = now + 20L;
		for (UUID id : List.copyOf(state.llamas)) {
			if (id.equals(state.guard)) continue;
			Entity entity = level.getEntity(id);
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) || !donkey.isAlive()) continue;
			Entity leashHolder = donkey.getLeashHolder();
			if (leashHolder != null && leashHolder != trader) continue;
			if (leashHolder == null) donkey.setLeashedTo(trader, true);
			if (donkey.distanceToSqr(trader) > DONKEY_FOLLOW_DISTANCE_SQR) {
				if (!guideDonkeyAlongUsablePath(level, state, donkey, trader)) donkey.getNavigation().stop();
			} else if (donkey.getNavigation().isInProgress()) {
				donkey.getNavigation().stop();
			}
		}
	}

	/** Pathfinding can return a nominally reachable path whose nodes cross a wall or skip an impossible step. */
	private static boolean usableDonkeyPath(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey,
		net.minecraft.world.level.pathfinder.Path path, BlockPos target, List<AABB> reserved) {
		return donkeyPathFailureReason(level, donkey, path, target, reserved) == null;
	}

	private static final int MAX_DONKEY_APPROACH_CANDIDATES = 20;

	/** Tries nearby safe goals in order and starts only a physically validated normal navigation path. */
	private static boolean guideDonkeyAlongUsablePath(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader) {
		return guideDonkeyAlongUsablePath(level, state, donkey, trader, trader.blockPosition(), false);
	}

	/** Return cargo animals to the persisted departure point while keeping ordinary, validated navigation. */
	private static boolean guideDonkeyAlongUsablePath(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader,
		BlockPos approachCenter, boolean returningHome) {
		var navigation = donkey.getNavigation();
		List<AABB> occupied = packDonkeyReservedBounds(level, state, donkey, trader, returningHome);
		// A home route was planned around the other animals' bodies and routes. Do not
		// invalidate it later just because another donkey's route has advanced through
		// one of its old intermediate cells; that makes both animals keep replacing
		// otherwise safe paths during the crowded final approach. Keep checking the
		// current bodies and destinations, and validate every route node against terrain.
		List<AABB> activeRouteOccupied = returningHome
			? packDonkeyReservedBounds(level, state, donkey, trader, returningHome, false) : occupied;
		BlockPos activeTarget = navigation.getTargetPos();
		if (navigation.isInProgress() && activeTarget != null
			&& activeTarget.distSqr(approachCenter) <= (returningHome ? PACK_HOME_APPROACH_DISTANCE_SQR : 25L)
			&& (!returningHome || safeHomeApproachGoal(donkey, activeTarget, approachCenter, trader))
			&& CaravanSites.canStand(level, donkey, activeTarget, activeRouteOccupied)
			&& usableDonkeyPath(level, donkey, navigation.getPath(), activeTarget, activeRouteOccupied)) {
			// Keep a still-safe approach while the merchant moves; replacing this route every
			// guidance tick makes pack animals jitter and can keep them permanently behind.
			return true;
		}
		for (BlockPos goal : safePackDonkeyApproaches(level, state, donkey, trader, approachCenter, returningHome)) {
			var path = donkey.getNavigation().createPath(goal, 1);
			if (returningHome && path != null && path.getNodeCount() > 0
				&& !safeHomeApproachGoal(donkey, path.getNodePos(path.getNodeCount() - 1), approachCenter, trader)) continue;
			if (usableDonkeyPath(level, donkey, path, goal, occupied) && donkey.getNavigation().moveTo(path, 1.0)) return true;
		}
		return false;
	}

	static String donkeyPathFailureReason(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey,
		net.minecraft.world.level.pathfinder.Path path, BlockPos target) {
		return donkeyPathFailureReason(level, donkey, path, target, List.of());
	}

	private static String donkeyPathFailureReason(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey,
		net.minecraft.world.level.pathfinder.Path path, BlockPos target, List<AABB> reserved) {
		if (path == null) return "path is null";
		if (target == null) return "no safe approach target";
		if (!path.canReach()) return "vanilla path cannot reach target";
		if (path.getNodeCount() == 1) {
			BlockPos onlyNode = path.getNodePos(0);
			if (!onlyNode.equals(target) || !onlyNode.equals(donkey.blockPosition())) {
				return "single-node path is not already at its exact target";
			}
			String nodeFailure = donkeyPathNodeFailure(level, donkey, onlyNode, reserved);
			return nodeFailure == null ? null : "completed one-node target is unsafe: " + nodeFailure;
		}
		if (path.getNodeCount() < 1) return "path has no nodes";
		if (path.getNodeCount() > 128) return "path exceeds 128-node safety bound";
		BlockPos end = path.getNodePos(path.getNodeCount() - 1);
		if (target.distSqr(end) > 2L) return "path ends at " + end + " outside the one-block approach tolerance of " + target;
		BlockPos previous = null;
		for (int i = 0; i < path.getNodeCount(); i++) {
			BlockPos feet = path.getNodePos(i);
			String nodeFailure = donkeyPathNodeFailure(level, donkey, feet, reserved);
			if (nodeFailure != null) return "node " + i + " at " + feet + ": " + nodeFailure;
			if (previous != null && (Math.abs(feet.getX() - previous.getX()) > 1
				|| Math.abs(feet.getY() - previous.getY()) > 1 || Math.abs(feet.getZ() - previous.getZ()) > 1)) {
				return "node " + i + " skips an adjacent navigable step from " + previous + " to " + feet;
			}
			previous = feet;
		}
		return null;
	}

	private static String donkeyPathNodeFailure(ServerLevel level,
		net.minecraft.world.entity.animal.equine.Donkey donkey, BlockPos feet, List<AABB> reserved) {
		String failure = donkeyPathNodeFailure(level, donkey, feet);
		if (failure != null) return failure;
		return CaravanSites.canStand(level, donkey, feet, reserved) ? null
			: "path node overlaps a reserved caravan body or is no longer a safe stand at " + feet;
	}

	/** Validate block clearance and ground support without rejecting a Vanilla path for nearby moving entities. */
	private static String donkeyPathNodeFailure(ServerLevel level,
		net.minecraft.world.entity.animal.equine.Donkey donkey, BlockPos feet) {
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())) return "path node chunk is not loaded";
		var body = donkey.getBoundingBox();
		var atNode = body.move(feet.getX() + 0.5 - donkey.getX(), feet.getY() - donkey.getY(), feet.getZ() + 0.5 - donkey.getZ());
		int minX = (int)Math.floor(atNode.minX), maxX = (int)Math.floor(atNode.maxX - 1.0E-7);
		int minZ = (int)Math.floor(atNode.minZ), maxZ = (int)Math.floor(atNode.maxZ - 1.0E-7);
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
			BlockPos floor = new BlockPos(x, feet.getY() - 1, z);
			if (!level.hasChunkAt(floor)) return "support block chunk is not loaded at " + floor;
			var support = level.getBlockState(floor);
			if (!support.getFluidState().isEmpty() || !support.isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)
				|| support.is(Blocks.MAGMA_BLOCK) || support.is(Blocks.CACTUS) || support.is(Blocks.CAMPFIRE)
				|| support.is(Blocks.SOUL_CAMPFIRE)) return "missing safe ground support at " + floor;
		}
		int bodyTop = (int)Math.ceil(atNode.maxY);
		for (int y = feet.getY(); y < bodyTop; y++) for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
			BlockPos occupied = new BlockPos(x, y, z);
			if (!level.hasChunkAt(occupied)) return "body clearance chunk is not loaded at " + occupied;
			if (!level.getFluidState(occupied).isEmpty()) return "fluid blocks body at " + occupied;
		}
		if (level.noBlockCollision(donkey, atNode)) return null;
		return donkeyBodyCollision(level, atNode);
	}

	private static String donkeyBodyCollision(ServerLevel level, AABB body) {
		int minX = (int)Math.floor(body.minX), maxX = (int)Math.floor(body.maxX - 1.0E-7);
		int minY = (int)Math.floor(body.minY), maxY = (int)Math.floor(body.maxY - 1.0E-7);
		int minZ = (int)Math.floor(body.minZ), maxZ = (int)Math.floor(body.maxZ - 1.0E-7);
		for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
			BlockPos pos = new BlockPos(x, y, z);
			if (!level.hasChunkAt(pos)) continue;
			var state = level.getBlockState(pos);
			var shape = state.getCollisionShape(level, pos);
			for (AABB localShape : shape.toAabbs()) {
				AABB worldShape = localShape.move(x, y, z);
				if (worldShape.intersects(body)) return "colliding block " + pos + " state=" + state + " shape=" + worldShape;
			}
		}
		return "block collision query rejected body bounds " + body + " but no overlapping block shape was found";
	}

	/** Find a loaded, collision-free stand beside the trader for a real donkey navigation goal. */
	static BlockPos safePackDonkeyApproach(ServerLevel level, net.minecraft.world.entity.animal.equine.Donkey donkey,
		AbstractVillager trader) {
		return safePackDonkeyApproach(level, null, donkey, trader);
	}

	/** Keep multiple registered pack animals from choosing the same approach cell and jamming each other. */
	private static BlockPos safePackDonkeyApproach(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader) {
		List<BlockPos> approaches = safePackDonkeyApproaches(level, state, donkey, trader);
		return approaches.isEmpty() ? null : approaches.getFirst();
	}

	private static List<AABB> packDonkeyReservedBounds(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader) {
		return packDonkeyReservedBounds(level, state, donkey, trader, false);
	}

	private static List<AABB> packDonkeyReservedBounds(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader,
		boolean returningHome) {
		return packDonkeyReservedBounds(level, state, donkey, trader, returningHome, true);
	}

	private static List<AABB> packDonkeyReservedBounds(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader,
		boolean returningHome, boolean reserveActivePaths) {
		List<AABB> reserved = new ArrayList<>();
		// PathNavigation treats an exact edge-touch as clear, while the wide donkey
		// can still stop at its waypoint tolerance and never enter that final cell.
		// Keep a small physical margin around a moving trader. At the saved home point,
		// allow a donkey to use a diagonally adjacent cell; the real entity bounds still
		// prevent overlap, while the extra margin would make every accepted home cell unreachable.
		reserved.add(returningHome ? trader.getBoundingBox() : trader.getBoundingBox().inflate(0.25));
		if (state == null) return reserved;
		for (UUID id : state.llamas) {
			if (id.equals(state.guard) || id.equals(donkey.getUUID())) continue;
			if (!(level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey other) || !other.isAlive()) continue;
			double homeClearance = returningHome ? 0.125 : 0.0;
			reserved.add(other.getBoundingBox().inflate(homeClearance));
			var navigation = other.getNavigation();
			BlockPos target = navigation.isInProgress() ? navigation.getTargetPos() : null;
			if (target != null && level.hasChunkAt(target)) reserved.add(CaravanSites.bounds(other, target).inflate(homeClearance));
			if (reserveActivePaths) {
				// New routes reserve the full active path so a second animal does not
				// choose a corridor through the first one's planned movement.
				var path = navigation.isInProgress() ? navigation.getPath() : null;
				if (path != null && path.getNodeCount() <= 128) {
					for (int node = 1; node < path.getNodeCount(); node++) {
						BlockPos feet = path.getNodePos(node);
						if (level.hasChunkAt(feet)) reserved.add(CaravanSites.bounds(other, feet).inflate(homeClearance));
					}
				}
			}
		}
		return reserved;
	}

	private static List<BlockPos> safePackDonkeyApproaches(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader) {
		return safePackDonkeyApproaches(level, state, donkey, trader, trader.blockPosition(), false);
	}

	private static List<BlockPos> safePackDonkeyApproaches(ServerLevel level, State state,
		net.minecraft.world.entity.animal.equine.Donkey donkey, AbstractVillager trader,
		BlockPos origin, boolean returningHome) {
		List<BlockPos> approaches = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		List<AABB> occupied = packDonkeyReservedBounds(level, state, donkey, trader, returningHome);
		for (int radius = 1; radius <= (returningHome ? 4 : MAX_DONKEY_TRADER_APPROACH_RADIUS); radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos column = origin.offset(dx, 0, dz);
				if (!level.hasChunkAt(column)) continue;
				BlockPos feet = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
				int horizontalDx = feet.getX() - origin.getX();
				int horizontalDz = feet.getZ() - origin.getZ();
				long horizontalDistanceSqr = (long)horizontalDx * horizontalDx + (long)horizontalDz * horizontalDz;
				if (Math.abs(feet.getY() - origin.getY()) <= 2
					&& (!returningHome || safeHomeApproachGoal(donkey, feet, origin, trader))
					&& CaravanSites.canStand(level, donkey, feet, occupied)) {
					if (!seen.add(feet)) continue;
					approaches.add(feet.immutable());
				}
			}
		}
		// Fixed coordinate-order selection sends animals from opposite sides of a
		// departure point across one another (for example, both east-side donkeys can
		// be assigned west-side goals). Their individually valid routes then compete
		// for the same narrow cells and can make no useful progress between refreshes.
		// Prefer each donkey's nearest safe approach so the pair converges from its
		// current side instead of crossing through the trader and each other.
		approaches.sort(java.util.Comparator.comparingDouble((BlockPos feet) -> donkey.distanceToSqr(
			feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5)));
		return approaches.size() <= MAX_DONKEY_APPROACH_CANDIDATES ? List.copyOf(approaches)
			: List.copyOf(approaches.subList(0, MAX_DONKEY_APPROACH_CANDIDATES));
	}

	/** Keep both the requested home goal and the actual navigation endpoint outside the trader's body lane. */
	static boolean safeHomeArrivalCell(net.minecraft.world.entity.animal.equine.Donkey donkey,
		BlockPos feet, BlockPos origin, Entity trader) {
		int dx = feet.getX() - origin.getX();
		int dz = feet.getZ() - origin.getZ();
		long horizontalDistanceSqr = (long)dx * dx + (long)dz * dz;
		return horizontalDistanceSqr >= MIN_HOME_APPROACH_HORIZONTAL_DISTANCE_SQR
			&& feet.distSqr(origin) <= PACK_HOME_APPROACH_DISTANCE_SQR
			&& !CaravanSites.bounds(donkey, feet).intersects(trader.getBoundingBox().inflate(0.25));
	}

	/** Request goals beyond the arrival ring so vanilla's wide-mob waypoint tolerance still ends in a safe cell. */
	private static boolean safeHomeApproachGoal(net.minecraft.world.entity.animal.equine.Donkey donkey,
		BlockPos feet, BlockPos origin, Entity trader) {
		int dx = feet.getX() - origin.getX();
		int dz = feet.getZ() - origin.getZ();
		long horizontalDistanceSqr = (long)dx * dx + (long)dz * dz;
		return horizontalDistanceSqr >= MIN_HOME_APPROACH_GOAL_HORIZONTAL_DISTANCE_SQR
			&& feet.distSqr(origin) <= PACK_HOME_APPROACH_DISTANCE_SQR
			&& !CaravanSites.bounds(donkey, feet).intersects(trader.getBoundingBox().inflate(0.25));
	}

	static State activeStateForChecks(UUID trader) {
		return ACTIVE.get(trader);
	}

	static void registerActiveStateForChecks(State state) {
		ACTIVE.put(state.trader, state);
	}

	static void removeActiveStateForChecks(UUID trader) {
		ACTIVE.remove(trader);
	}

	/** The physical follow target: a guide route is complete once the donkey is within three blocks of its trader. */
	static final double DONKEY_FOLLOW_DISTANCE_SQR = 9.0;
	/** Hold the trader beyond four blocks and resume only inside three, keeping the real leads local. */
	private static final double DONKEY_WAIT_DISTANCE_SQR = 16.0;
	private static final double DONKEY_RESUME_DISTANCE_SQR = 9.0;
	private static final int MAX_DONKEY_TRADER_APPROACH_RADIUS = 3;
	/** Keep return goals beside the saved point while leaving enough room for the trader's hitbox. */
	static final long PACK_HOME_APPROACH_DISTANCE_SQR = 16L;
	/** Two horizontal blocks avoid the trader and leave a separate approach lane for each donkey. */
	private static final long MIN_HOME_APPROACH_HORIZONTAL_DISTANCE_SQR = 4L;
	/** A three-block route goal gives Minecraft's donkey-width waypoint tolerance one safe block of margin. */
	private static final long MIN_HOME_APPROACH_GOAL_HORIZONTAL_DISTANCE_SQR = 9L;

	/** Pause departure as soon as any recorded live pack animal cannot stay within loaded follow range. */
	static boolean packAnimalsCanKeepUp(ServerLevel level, State state, AbstractVillager trader) {
		return packAnimalsWithinDistance(level, state, trader, DONKEY_WAIT_DISTANCE_SQR);
	}

	/** Keep a paused trader stopped until all loaded pack animals have caught up with hysteresis. */
	static boolean packAnimalsCanResume(ServerLevel level, State state, AbstractVillager trader) {
		return packAnimalsWithinDistance(level, state, trader, DONKEY_RESUME_DISTANCE_SQR);
	}

	private static boolean packAnimalsWithinDistance(ServerLevel level, State state, AbstractVillager trader, double maxDistanceSqr) {
		return packAnimalsWithinDistance(level, state, trader, maxDistanceSqr, true);
	}

	private static boolean packAnimalsWithinDistance(ServerLevel level, State state, AbstractVillager trader,
		double maxDistanceSqr, boolean requireDepartureRoutes) {
		if (requireDepartureRoutes && !state.departurePathReady) return false;
		for (UUID id : state.llamas) {
			if (id.equals(state.guard)) continue;
			Entity entity = level.getEntity(id);
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) || !donkey.isAlive()) return false;
			Entity holder = donkey.getLeashHolder();
			if (holder != null && holder != trader) continue;
			boolean returnedHome = trader.blockPosition().distSqr(state.spawn) <= 16L
				&& safeHomeArrivalCell(donkey, donkey.blockPosition(), state.spawn, trader);
			if (returnedHome) continue;
			double distance = donkey.distanceToSqr(trader);
			if (distance > maxDistanceSqr) return false;
			// A stale positive route result must not let the trader leave while a distant
			// pack animal has stopped navigating. Requiring renewed movement catches a
			// path that became obstructed between the 20-tick guidance refreshes.
			if (distance > DONKEY_FOLLOW_DISTANCE_SQR && !donkey.getNavigation().isInProgress()) return false;
		}
		return true;
	}

	/** Find a nearby loaded, supported place for the merchant to wait without requesting chunks. */
	static BlockPos safeDepotArrivalStand(ServerLevel level, AbstractVillager trader, BlockPos chest) {
		if (chest == null || !level.hasChunkAt(chest)) return null;
		var chestBox = new net.minecraft.world.phys.AABB(chest);
		var chestCenter = net.minecraft.world.phys.Vec3.atBottomCenterOf(chest);
		BlockPos best = null;
		double bestTraderDistance = Double.POSITIVE_INFINITY;
		double bestChestDistance = Double.POSITIVE_INFINITY;
		for (int radius = 1; radius <= 7; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos column = chest.offset(dx, 0, dz);
				if (!level.hasChunkAt(column)) continue;
				BlockPos feet = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
				if (Math.abs(feet.getY() - chest.getY()) > 1) continue;
				var standCenter = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet);
				double chestDx = standCenter.x - chestCenter.x;
				double chestDz = standCenter.z - chestCenter.z;
				double chestDistance = chestDx * chestDx + chestDz * chestDz;
				// Keep the merchant five to seven horizontal blocks from the chest. A height
				// difference must never make a spot directly above it look like a safe stand.
				if (chestDistance < 25.0 || chestDistance > 49.0
					|| CaravanSites.bounds(trader, feet).intersects(chestBox)
					|| !hasSafeTraderStand(level, trader, feet)) continue;
				double traderDistance = trader.position().distanceToSqr(standCenter);
				if (traderDistance < bestTraderDistance
					|| traderDistance == bestTraderDistance && chestDistance < bestChestDistance) {
					best = feet.immutable();
					bestTraderDistance = traderDistance;
					bestChestDistance = chestDistance;
				}
			}
		}
		return best;
	}

	/** A trader needs loaded collision-free body space and no block entity under or inside its footprint. */
	static boolean hasSafeTraderStand(ServerLevel level, AbstractVillager trader, BlockPos feet) {
		if (trader == null || feet == null) return false;
		var box = CaravanSites.bounds(trader, feet);
		int minX = (int) Math.floor(box.minX);
		int maxX = (int) Math.floor(box.maxX - 1.0E-7);
		int minZ = (int) Math.floor(box.minZ);
		int maxZ = (int) Math.floor(box.maxZ - 1.0E-7);
		int minY = (int) Math.floor(box.minY);
		int maxY = (int) Math.floor(box.maxY - 1.0E-7);
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
			BlockPos support = new BlockPos(x, feet.getY() - 1, z);
			if (!level.hasChunkAt(support) || hasBlockEntity(level, support)) return false;
			for (int y = minY; y <= maxY; y++) {
				BlockPos occupied = new BlockPos(x, y, z);
				if (!level.hasChunkAt(occupied) || hasBlockEntity(level, occupied)) return false;
			}
		}
		return CaravanSites.canStand(level, trader, feet, List.of());
	}

	private static boolean hasBlockEntity(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).hasBlockEntity() || level.getBlockEntity(pos) != null;
	}

	/** Find a nearby loaded, supported place for the merchant to wait without requesting chunks. */
	static BlockPos safePackWaitSpot(ServerLevel level, AbstractVillager trader) {
		BlockPos origin = trader.blockPosition();
		if (hasSafeTraderStand(level, trader, origin)) return origin;
		for (int radius = 1; radius <= 4; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos column = origin.offset(dx, 0, dz);
				if (!level.hasChunkAt(column)) continue;
				BlockPos feet = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
				if (Math.abs(feet.getY() - origin.getY()) <= 2 && hasSafeTraderStand(level, trader, feet)) return feet;
			}
		}
		return null;
	}

	static BlockPos holdForPackAnimals(ServerLevel level, AbstractVillager trader, boolean enteringWait) {
		BlockPos waitAt = safePackWaitSpot(level, trader);
		if (waitAt == null) {
			trader.getNavigation().stop();
			if (trader instanceof CaravanTraderEntity custom) custom.setRouteTarget(trader.blockPosition());
			else if (trader instanceof WanderingTrader wandering) wandering.setWanderTarget(trader.blockPosition());
			return trader.blockPosition();
		}
		if (trader instanceof CaravanTraderEntity custom) custom.setRouteTarget(waitAt);
		else if (trader instanceof WanderingTrader wandering) wandering.setWanderTarget(waitAt);
		if (waitAt.equals(trader.blockPosition())) trader.getNavigation().stop();
		else if (enteringWait || trader.getNavigation().isDone() || level.getGameTime() % 20L == 0L) {
			trader.getNavigation().moveTo(waitAt.getX() + 0.5, waitAt.getY(), waitAt.getZ() + 0.5, 0.5);
		}
		return waitAt;
	}

	/**
	 * Departure is complete only once every recorded pack donkey is loaded and physically
	 * in a safe stand cell beside the origin. A missing entity may simply
	 * be in an unloaded chunk, so retain the visit and retry rather than discarding its inventory.
	 */
	static boolean packAnimalsAtSpawn(ServerLevel level, State state) {
		Entity trader = level.getEntity(state.trader);
		if (!(trader instanceof AbstractVillager)) return false;
		for (UUID id : state.llamas) {
			if (id.equals(state.guard)) continue;
			Entity entity = level.getEntity(id);
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)
				|| !donkey.isAlive() || !safeHomeArrivalCell(donkey, donkey.blockPosition(), state.spawn, trader)) return false;
		}
		return true;
	}

	/** A due caravan may finish only after each live pack donkey arrived or was externally released. */
	private static boolean packAnimalsArrivedOrReleased(ServerLevel level, State state) {
		for (UUID id : state.llamas) {
			if (id.equals(state.guard)) continue;
			Entity entity = level.getEntity(id);
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) || !donkey.isAlive()) return false;
			Entity leashHolder = donkey.getLeashHolder();
			if (leashHolder != null && !leashHolder.getUUID().equals(state.trader)) continue;
			if (leashHolder != null && !safeHomeArrivalCell(donkey, donkey.blockPosition(), state.spawn, leashHolder)) return false;
		}
		return true;
	}

	/**
	 * Follow a finished, physically paved canonical overland route when it connects the
	 * caravan's current position to its current destination. The trader's ordinary
	 * navigator remains the fallback for legacy links, incomplete roads, distant
	 * starts, and unloaded route sections.
	 */
	private static boolean routeEndpointNearby(AbstractVillager trader, BlockPos destination, State caravan, boolean returning) {
		if (trader.blockPosition().distSqr(destination) <= 12L * 12L) return true;
		return returning && caravan.routeBell != null && trader.blockPosition().distSqr(caravan.routeBell) <= 12L * 12L;
	}

	private static BlockPos roadWaypoint(ServerLevel level, VillageRef ref, BlockPos current, BlockPos destination, State caravan) {
		return roadWaypoint(level, ref, current, destination, caravan, false);
	}

	private static BlockPos roadWaypoint(ServerLevel level, VillageRef ref, BlockPos current, BlockPos destination, State caravan, boolean returning) {
		Map<String, Long> roads = new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
		TradeNetwork network = Network.of(ref.bellEntity());
		String dimension = Network.dim(level);
		BlockPos routeDestination = returning && caravan.routeBell != null ? caravan.routeBell : destination;
		String selectedKey = null;
		int selectedCursor = -1;
		BlockPos selectedWaypoint = null;
		double best = Double.POSITIVE_INFINITY;

		for (TradeNetwork.Link link : network.links()) {
			if (!link.route() || !link.dimension().equals(dimension)) continue;
			BlockPos other = link.bell();
			String key = link.dimension() + other.asLong();
			VillageRef peer = Network.resolve(level, link);
			Map<String, Long> peerRoads = peer == null ? null
				: peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
			String peerKey = dimension + ref.bell().asLong();
			if (!RoadRoute.ensureWithRetry(level, ref.bell(), other, roads, key, peerRoads, peerKey,
				level.getGameTime(), false)) continue;
			int length = RoadRoute.length(roads, key, ref.bell(), other);
			if (length <= 0) continue;

			int hereCursor = -1, destinationCursor = -1;
			double hereDistance = Double.POSITIVE_INFINITY, destinationDistance = Double.POSITIVE_INFINITY;
			BlockPos[] walkablePoints = new BlockPos[length + 1];
			for (int i = 0; i <= length; i++) {
				BlockPos routePoint = RoadRoute.point(roads, key, ref.bell(), other, i);
				BlockPos feet = RoadRoute.walkablePavedFeet(level, routePoint);
				if (feet == null) continue;
				walkablePoints[i] = feet;
				double from = current.distSqr(feet);
				double to = routeDestination.distSqr(feet);
				if (from < hereDistance) { hereDistance = from; hereCursor = i; }
				if (to < destinationDistance) { destinationDistance = to; destinationCursor = i; }
			}
			// A short gap at either endpoint is fine; a route across the map is not a
			// useful detour for a caravan spawned or departing somewhere else.
			if (hereCursor < 0 || destinationCursor < 0 || hereDistance > 128 * 128 || destinationDistance > 12 * 12) continue;
			// Endpoints may intentionally stop inside a village's existing paths. Only
			// require the physical road to be continuous between the selected approach
			// and destination points; never bridge an unfinished or unloaded middle.
			boolean connected = true;
			BlockPos previousFeet = null;
			for (int i = Math.min(hereCursor, destinationCursor); i <= Math.max(hereCursor, destinationCursor); i++) {
				BlockPos feet = walkablePoints[i];
				if (feet == null || (previousFeet != null && Math.abs(feet.getY() - previousFeet.getY()) > 1)) {
					connected = false;
					break;
				}
				previousFeet = feet;
			}
			if (!connected) continue;
			int direction = Integer.compare(destinationCursor, hereCursor);
			if (direction == 0) continue;
			int cursor = hereCursor;
			int pendingCursor = -1;
			if (key.equals(caravan.roadKey) && caravan.roadCursor >= 0) {
				// roadCursor is the pending waypoint. Hold it across the four-tick caravan
				// scan and advance only after the trader reaches it or has already passed it.
				int savedCursor = Math.max(0, Math.min(length, caravan.roadCursor));
				boolean compatible = direction > 0 ? savedCursor <= destinationCursor : savedCursor >= destinationCursor;
				if (compatible) {
					BlockPos savedFeet = walkablePoints[savedCursor];
					boolean reached = savedFeet != null && current.distSqr(savedFeet) <= 4;
					boolean notPassed = direction > 0 ? hereCursor <= savedCursor : hereCursor >= savedCursor;
					if (notPassed && !reached) pendingCursor = savedCursor;
					else cursor = direction > 0 ? Math.max(cursor, savedCursor) : Math.min(cursor, savedCursor);
				}
			}
			int next = pendingCursor >= 0 ? pendingCursor
				: cursor + direction * Math.min(6, Math.abs(destinationCursor - cursor));
			BlockPos waypoint = walkablePoints[next];
			if (waypoint == null) continue;
			double score = hereDistance + destinationDistance * 0.25;
			if (score < best) {
				best = score; selectedKey = key; selectedCursor = next;
				selectedWaypoint = waypoint;
			}
		}
		ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roads));
		ref.dirty();
		if (selectedKey == null) return null;
		caravan.roadKey = selectedKey;
		caravan.roadCursor = selectedCursor;
		return selectedWaypoint;
	}

	// --- Handel mit dem imaginaeren Dorf --------------------------------------------------------

	/** Wert in "Einheiten" (64 Einheiten = 1 Smaragd). 0 = wird nie gehandelt. */

	static int value(Item item) {

		String p = BuiltInRegistries.ITEM.getKey(item).getPath();

		if (item == Items.EMERALD) {

			return 0;

		}

		return switch (p) {

			case "dirt", "gravel", "sand", "cobblestone", "cobbled_deepslate", "andesite", "diorite", "granite", "netherrack",

				"red_sand", "sandstone", "clay_ball" -> 1;

			case "coal", "charcoal", "copper_ingot" -> 8;

			case "raw_iron", "raw_copper" -> 16;

			case "iron_ingot" -> 24;

			case "raw_gold", "gold_ingot", "lapis_lazuli", "redstone" -> 32;

			case "wheat", "carrot", "potato", "beetroot", "sugar_cane", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "egg" -> 3;

			case "bread", "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cod", "salmon", "cooked_cod",

				"cooked_salmon", "beef", "porkchop", "mutton", "chicken", "baked_potato" -> 6;

			case "leather", "feather", "string", "bone", "flint", "stick", "paper" -> 4;

			case "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "cherry_log", "mangrove_log" -> 8;

			default -> p.endsWith("_wool") ? 6 : p.endsWith("_planks") ? 3 : 0;

		};

	}

	/** So viel behaelt das Dorf immer selbst. */

	static int keep(Item item) {

		String p = BuiltInRegistries.ITEM.getKey(item).getPath();

		return switch (p) {

			case "dirt", "gravel", "clay_ball", "netherrack", "red_sand", "sandstone" -> 32;

			case "sand" -> 64;

			case "cobblestone", "cobbled_deepslate", "andesite", "diorite", "granite" -> 288;

			case "coal", "charcoal", "raw_iron", "iron_ingot", "raw_copper", "copper_ingot" -> 24;

			case "wheat", "carrot", "potato", "beetroot", "bread", "wheat_seeds" -> 64;

			default -> p.endsWith("_planks") ? 160 : p.endsWith("_wool") ? 24 : 64;

		};

	}

	private static void showPartnerName(VillageRef ref, State caravan, AbstractVillager trader) {
		String name = partnerName(ref, caravan);
		if (!name.equals("unbekannt")) trader.setCustomName(Component.literal("Händler aus " + name));
	}

	private static boolean exchange(ServerLevel level, VillageRef ref, AbstractVillager trader) {

		Container box = ref.chest(level);

		if (box == null) {

			return false;

		}

		State caravan = ACTIVE.get(trader.getUUID());

		String town = caravan == null ? "Fernhausen" : partnerName(ref, caravan);

		List<String> sold = new ArrayList<>();

		List<String> bought = new ArrayList<>();

		long units = 0;

		// 1) Ueberschuss verkaufen.

		// Dorfkiste UND Lager: Was sich im Lager staut, nimmt der Haendler mit (so laeuft es nicht dauerhaft voll).

		List<Container> stores = new ArrayList<>();

		stores.add(box);

		stores.addAll(Lager.containers(level, ref.data()));

		Map<Item, Integer> totals = new HashMap<>();

		for (Container store : stores) {

			for (int i = 0; i < store.getContainerSize(); i++) {

				ItemStack s = store.getItem(i);

				if (!s.isEmpty() && value(s.getItem()) > 0) {

					totals.merge(s.getItem(), s.getCount(), Integer::sum);

				}

			}

		}

		for (Map.Entry<Item, Integer> e : totals.entrySet()) {

			int surplus = e.getValue() - keep(e.getKey());

			if (surplus >= 8) {

				Item item = e.getKey();

				int taken = 0;

				for (Container store : stores) {

					if (taken < surplus) {

						taken += JobUtil.take(store, s -> s.is(item), surplus - taken);

					}

				}

				units += (long) taken * value(item);

				sold.add(taken + "x " + new ItemStack(item).getHoverName().getString());

			}

		}

		// Der "imaginaere" Handelspartner zahlt wenig: hoechstens 6 Smaragde je Besuch, damit Smaragde knapp bleiben wie in Vanilla.

		int emeraldsIn = (int) Math.min(6, units / 96);

		if (emeraldsIn > 0) {

		insert(level, caravan, ref, box, new ItemStack(Items.EMERALD, emeraldsIn));

		}

		// Tauschhandel: Was verkauft wurde und nicht in Smaragde ging, bleibt als Guthaben und bezahlt Fehlendes direkt in Waren.

		long credit = units - emeraldsIn * 96L;

		// 2) Fehlendes (Wunschliste) und Grundbedarf kaufen.

		List<String> wishes = new ArrayList<>(ref.data().wanted);

		wishes.add("minecraft:wheat_seeds#32");

		wishes.add("minecraft:torch#16");

		wishes.add("minecraft:oak_sapling#4");

		for (String w : wishes) {

			String[] parts = w.split("#");

			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));

			if (item.isEmpty()) {

				continue;

			}

			boolean planOrder = PlanSupplyOrder.requests(ref.data(), item.get());
			int missing;
			if (planOrder) {
				missing = planOrderCount(level, ref, item.get());
			} else {
				int want = parts.length > 1 ? parseInt(parts[1]) : 8;
				missing = ordinaryTradeMissing(level, ref, box, item.get(), want);
			}

			if (missing <= 0) {

				continue;

			}

			// Erst mit dem Tausch-Guthaben bezahlen: Baumaterial kostet 3 Einheiten je Stueck, alles andere 12.

			long unit = item.get() instanceof net.minecraft.world.item.BlockItem ? 3 : 12;

			int byCredit = (int) Math.min(missing, credit / unit);

			if (byCredit > 0) {
				int acquired = planOrder
					? packPlanFreight(level, caravan, ref, item.get(), byCredit)
					: packCargo(level, caravan, new ItemStack(item.get(), byCredit));
				if (acquired > 0) {
					credit -= acquired * unit;
					bought.add(acquired + "x " + new ItemStack(item.get()).getHoverName().getString() + " (Tausch)");
					missing -= acquired;
				}

				if (missing <= 0) {

					continue;

				}

			}

			int price = Math.max(2, value(item.get()) * 2);

			if (value(item.get()) == 0) {

				// Baumaterial (Bloecke) ist guenstig: der Haendler bringt so Dinge, die das Dorf selbst nicht herstellen kann.

				price = item.get() == Items.TORCH ? 2 : item.get() == Items.OAK_SAPLING ? 16

					: item.get() instanceof net.minecraft.world.item.BlockItem ? 8 : 24;

			}

			int emeralds = JobUtil.count(box, s -> s.is(Items.EMERALD));
			long treasury=Network.treasuryEmeralds(ref);
			long funds=treasury>Long.MAX_VALUE-emeralds?Long.MAX_VALUE:treasury+emeralds;
			long tradeUnits=funds>Long.MAX_VALUE/64L?Long.MAX_VALUE:funds*64L;

			int affordable = (int)Math.min(missing, tradeUnits / price);

			if (affordable <= 0) {

				continue;

			}

			if (planOrder) {
				int acquired = buyPlanFreight(level, caravan, ref, box, item.get(), affordable, price);
				if (acquired > 0) bought.add(acquired + "x " + new ItemStack(item.get()).getHoverName().getString());
				continue;
			}

			int acquired = buyTradeFreight(level, caravan, ref, box, item.get(), affordable, price);
			if (acquired > 0) bought.add(acquired + "x " + new ItemStack(item.get()).getHoverName().getString());

		}

		// 3) Tiere fuer das Gehege.

		int animals = restockPen(level, ref, box);

		if (animals > 0) {

			bought.add(animals + " Tiere");

		}

		boolean transacted = !sold.isEmpty() || !bought.isEmpty() || animals > 0;
		if (!transacted) return false;

		Journal.add(level,ref,"Handel",town,"Karawane: verkauft ["+String.join(", ",sold)+"], gekauft ["+String.join(", ",bought)+"]",units);
		Component msg = Component.literal("Die Karawane aus " + town + " tauscht Waren: verkauft [" + String.join(", ", sold)

			+ "], gekauft [" + String.join(", ", bought) + "].");

		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Villagers: Repurposed: {} (Wunschliste {})", msg.getString(), wishes);

		JobUtil.tellNearbyPlayers(level, trader, 48, msg);

		ref.dirty();
		return true;

	}

	/** Compatibility entry point for caravan checks and any old vanilla-trader save still in flight. */
	private static boolean exchange(ServerLevel level, VillageRef ref, WanderingTrader trader) {
		return exchange(level, ref, (AbstractVillager) trader);
	}

	private static int parseInt(String s) {

		try {

			return Integer.parseInt(s);

		} catch (NumberFormatException e) {

			return 8;

		}
	}

	/** Counts real matching freight already on village donkeys before the next ordinary import is ordered. */
	static int ordinaryTradeMissing(ServerLevel level, VillageRef ref, Container box, Item item, int target) {
		if (box == null || item == null || target <= 0) return 0;
		int inTransit = planInboundCargoCount(level, ref, item);
		long have = (long)StockPolicy.communalCount(level, ref, item) + inTransit;
		long demandMissing = Math.max(0L, (long)target - Math.min(Integer.MAX_VALUE, have));
		long policyRoomAfterFreight = Math.max(0L, (long)StockPolicy.deliveryRoom(level, ref, item) - inTransit);
		return (int)Math.min(demandMissing, policyRoomAfterFreight);
	}

	private static int planDepotRoom(ServerLevel level, VillageRef ref, Item item, int requested) {
		if (requested <= 0) return 0;
		Container chest = ref.chest(level);
		if (chest == null) return 0;
		ItemStack sample = new ItemStack(item);
		long room = 0;
		int max = Math.min(sample.getMaxStackSize(), chest.getMaxStackSize());
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack current = chest.getItem(i);
			if (current.isEmpty()) room += max;
			else if (ItemStack.isSameItemSameComponents(current, sample)) {
				room += Math.max(0, Math.min(current.getMaxStackSize(), chest.getMaxStackSize()) - current.getCount());
			}
		}
		room = Math.min(room, StockPolicy.deliveryRoom(level, ref, item));
		return (int)Math.min(requested, room);
	}

	/** Loads ordinary imports before charging, and charges only for the quantity on a real donkey. */
	static int buyTradeFreight(ServerLevel level, State state, VillageRef ref, Container box, Item item,
			int requested, int pricePerItem) {
		if (state == null || box == null || item == null || requested <= 0 || pricePerItem <= 0) return 0;
		CargoSnapshot snapshot = snapshotCargo(level, state);
		int loaded = packCargo(level, state, new ItemStack(item, requested));
		if (loaded <= 0) return 0;

		int cost = PlanSupplyOrder.emeraldCost(loaded, pricePerItem);
		int cash = JobUtil.count(box, stack -> stack.is(Items.EMERALD));
		int fromBox = Math.min(cash, cost);
		long fromTreasury = cost - fromBox;
		if (fromTreasury > 0 && !Network.spendTreasuryEmeralds(ref, fromTreasury)) {
			restoreCargo(level, state, snapshot);
			return 0;
		}

		List<ItemStack> boxSnapshot = snapshotContainer(box);
		int removed = JobUtil.take(box, stack -> stack.is(Items.EMERALD), fromBox);
		if (removed != fromBox) {
			if (fromTreasury > 0 && !Network.creditTreasuryEmeralds(ref, fromTreasury)) {
				org.slf4j.LoggerFactory.getLogger("villagerplus").error("Karawanen-Rueckbuchung der Dorfkasse fehlgeschlagen: {} Smaragde", fromTreasury);
			}
			restoreContainer(box, boxSnapshot);
			restoreCargo(level, state, snapshot);
			return 0;
		}
		return loaded;
	}

	static int planOrderCount(ServerLevel level, VillageRef ref, Item item) {
		return planRequestCapacity(level, ref, item, Integer.MAX_VALUE);
	}

	private static int planRequestCapacity(ServerLevel level, VillageRef ref, Item item, int requested) {
		if (requested <= 0) return 0;
		clearSatisfiedPlanFreight(level, ref);
		// One material shipment reserves the public chest until it is delivered. This
		// prevents two different materials from each claiming the same empty slot.
		if (hasAnyPlanFreight(level, ref)) return 0;
		if (hasInboundForOtherPlanItem(level, ref, item)) return 0;
		if (!isFirstOrderablePlanItem(level, ref, item)) return 0;
		return orderablePlanCapacity(level, ref, item, requested);
	}

	private static boolean isFirstOrderablePlanItem(ServerLevel level, VillageRef ref, Item requested) {
		for (Map.Entry<Item, Integer> shortage : PlanSupplyOrder.shortages(ref.data()).entrySet()) {
			Item item = shortage.getKey();
			if (orderablePlanCapacity(level, ref, item, Integer.MAX_VALUE) > 0) return item == requested;
		}
		return false;
	}

	private static int orderablePlanCapacity(ServerLevel level, VillageRef ref, Item item, int requested) {
		int remaining = remainingPlanDemand(level, ref, item);
		if (remaining <= 0 || requested <= 0) return 0;
		int room = planDepotRoom(level, ref, item, Integer.MAX_VALUE);
		int inbound = Math.min(room, planInboundCargoCount(level, ref, item));
		return Math.min(requested, Math.min(remaining, room - inbound));
	}

	private static int remainingPlanDemand(ServerLevel level, VillageRef ref, Item item) {
		// Builder's saved plan shortage already subtracts its real site stock and BuildHaul manifest.
		// For caravan orders, include every live public village store (not just the primary build chest)
		// and all matching donkey freight before buying another shipment.
		int publicStock = StockPolicy.communalCount(level, ref, item);
		long reserved = (long)publicStock + planInboundCargoCount(level, ref, item);
		return PlanSupplyOrder.remaining(ref.data(), item, (int)Math.min(Integer.MAX_VALUE, reserved));
	}

	/** Counts only paid plan slots; their item-and-slot tags persist on the donkey across reloads. */
	private static int planFreight(ServerLevel level, VillageRef ref, Item item) {
		int count = 0;
		String tag = villageTag(ref.bell());
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)
				|| !donkey.isAlive() || !donkey.entityTags().contains(tag)) continue;
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access != null && !access.get().isEmpty() && access.get().is(item)
					&& isPlanFreightSlot(donkey, slot, item)) {
					count = (int)Math.min(Integer.MAX_VALUE, (long)count + access.get().getCount());
				}
			}
		}
		return count;
	}

	static int planFreightCount(ServerLevel level, VillageRef ref, Item item) {
		return planFreight(level, ref, item);
	}

	/** Real matching stock on village caravan donkeys, whether plan-tagged or existing trade freight. */
	static int planInboundCargoCount(ServerLevel level, VillageRef ref, Item item) {
		int count = 0;
		String tag = villageTag(ref.bell());
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)
				|| !donkey.isAlive() || !donkey.entityTags().contains(tag)) continue;
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access != null && !access.get().isEmpty() && access.get().is(item)) {
					count = (int)Math.min(Integer.MAX_VALUE, (long)count + access.get().getCount());
				}
			}
		}
		return count;
	}

	private static boolean hasInboundForOtherPlanItem(ServerLevel level, VillageRef ref, Item requested) {
		String village = villageTag(ref.bell());
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)
				|| !donkey.isAlive() || !donkey.entityTags().contains(village)) continue;
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access == null || access.get().isEmpty() || access.get().is(requested)) continue;
				Item item = access.get().getItem();
				if (PlanSupplyOrder.requests(ref.data(), item) && planStockNeeded(level, ref, item)) return true;
			}
		}
		return false;
	}

	private static boolean hasAnyPlanFreight(ServerLevel level, VillageRef ref) {
		String village = villageTag(ref.bell());
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)
				|| !donkey.isAlive() || !donkey.entityTags().contains(village)) continue;
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access != null && !access.get().isEmpty()
					&& isPlanFreightSlot(donkey, slot, access.get().getItem())) return true;
			}
		}
		return false;
	}

	/** Releases freight markers after a requested item is already present in the build depot. */
	private static void clearSatisfiedPlanFreight(ServerLevel level, VillageRef ref) {
		Map<Item, Integer> shortages = PlanSupplyOrder.shortages(ref.data());
		Container chest = ref.chest(level);
		String village = villageTag(ref.bell());
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)
				|| !donkey.isAlive() || !donkey.entityTags().contains(village)) continue;
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access == null || access.get().isEmpty()) continue;
				Item item = access.get().getItem();
				if (!isPlanFreightSlot(donkey, slot, item)) continue;
				int requested = shortages.getOrDefault(item, 0);
				boolean satisfied = requested <= 0
					|| (chest != null && JobUtil.count(chest, stack -> stack.is(item)) >= requested);
				if (satisfied) donkey.removeTag(planFreightTag(item, slot));
			}
		}
	}

	private static void clearSatisfiedPlanFreight(ServerLevel level, VillageRef ref, Item item) {
		Container chest = ref.chest(level);
		int requested = PlanSupplyOrder.shortages(ref.data()).getOrDefault(item, 0);
		if (requested > 0 && (chest == null || JobUtil.count(chest, stack -> stack.is(item)) < requested)) return;
		String village = villageTag(ref.bell());
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)
				|| !donkey.isAlive() || !donkey.entityTags().contains(village)) continue;
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access != null && !access.get().isEmpty() && access.get().is(item)) {
					donkey.removeTag(planFreightTag(item, slot));
				}
			}
		}
	}

	private static int packPlanFreight(ServerLevel level, State state, VillageRef ref, Item item, int requested) {
		int capacity = planRequestCapacity(level, ref, item, requested);
		if (state == null || capacity <= 0) return 0;
		PlanPackResult packed = packPlanCargo(level, state, item, capacity);
		if (packed.loaded() > 0) markPlanFreight(level, packed.newSlots(), item);
		return packed.loaded();
	}

	/** Packs before charging, then restores both the full physical cargo snapshot and payment on failure. */
	static int buyPlanFreight(ServerLevel level, State state, VillageRef ref, Container box, Item item,
			int requested, int pricePerItem) {
		int capacity = planRequestCapacity(level, ref, item, requested);
		if (state == null || capacity <= 0) return 0;
		CargoSnapshot snapshot = snapshotCargo(level, state);
		PlanPackResult packed = packPlanCargo(level, state, item, capacity);
		int loaded = packed.loaded();
		if (loaded <= 0) return 0;

		int cost = PlanSupplyOrder.emeraldCost(loaded, pricePerItem);
		int cash = JobUtil.count(box, stack -> stack.is(Items.EMERALD));
		int fromBox = Math.min(cash, cost);
		long fromTreasury = cost - fromBox;
		if (fromTreasury > 0 && !Network.spendTreasuryEmeralds(ref, fromTreasury)) {
			restoreCargo(level, state, snapshot);
			return 0;
		}
		List<ItemStack> boxSnapshot = snapshotContainer(box);
		int removed = JobUtil.take(box, stack -> stack.is(Items.EMERALD), fromBox);
		if (removed != fromBox) {
			if (fromTreasury > 0 && !Network.creditTreasuryEmeralds(ref, fromTreasury)) {
				org.slf4j.LoggerFactory.getLogger("villagerplus").error("R02-Rueckbuchung der Dorfkasse fehlgeschlagen: {} Smaragde", fromTreasury);
			}
			restoreContainer(box, boxSnapshot);
			restoreCargo(level, state, snapshot);
			return 0;
		}
		markPlanFreight(level, packed.newSlots(), item);
		return loaded;
	}

	private record CargoSnapshot(Map<UUID, List<ItemStack>> contents) { }

	private static CargoSnapshot snapshotCargo(ServerLevel level, State state) {
		Map<UUID, List<ItemStack>> contents = new HashMap<>();
		for (UUID id : state.llamas) {
			if (!(level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)) continue;
			List<ItemStack> slots = new ArrayList<>();
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				slots.add(access == null ? ItemStack.EMPTY : access.get().copy());
			}
			contents.put(id, slots);
		}
		return new CargoSnapshot(contents);
	}

	private static void restoreCargo(ServerLevel level, State state, CargoSnapshot snapshot) {
		for (UUID id : List.copyOf(state.llamas)) {
			if (!(level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey)) continue;
			List<ItemStack> old = snapshot.contents().get(id);
			if (old == null) {
				for (int slot = 2; slot < 17; slot++) {
					var access = donkey.getSlot(500 + slot);
					if (access != null) access.set(ItemStack.EMPTY);
				}
				syncCargoVisual(level, donkey);
				donkey.discard();
				state.llamas.remove(id);
				continue;
			}
			for (int slot = 2; slot < 17; slot++) {
				var access = donkey.getSlot(500 + slot);
				if (access != null) access.set(old.get(slot - 2).copy());
			}
			syncCargoVisual(level, donkey);
		}
	}

	private static List<ItemStack> snapshotContainer(Container container) {
		List<ItemStack> snapshot = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) snapshot.add(container.getItem(slot).copy());
		return snapshot;
	}

	private static void restoreContainer(Container container, List<ItemStack> snapshot) {
		for (int slot = 0; slot < Math.min(container.getContainerSize(), snapshot.size()); slot++) {
			container.setItem(slot, snapshot.get(slot).copy());
		}
		container.setChanged();
	}

	private record PlanCargoSlot(UUID donkey, int slot) { }
	private record PlanPackResult(int loaded, List<PlanCargoSlot> newSlots) { }

	private static String planFreightTag(Item item, int slot) {
		return PLAN_FREIGHT_SLOT_PREFIX + BuiltInRegistries.ITEM.getKey(item) + ":" + slot;
	}

	private static boolean isPlanFreightSlot(net.minecraft.world.entity.animal.equine.Donkey donkey, int slot, Item item) {
		return donkey.entityTags().contains(planFreightTag(item, slot));
	}

	static boolean isPlanFreightSlotForChecks(net.minecraft.world.entity.animal.equine.Donkey donkey, int slot, Item item) {
		return isPlanFreightSlot(donkey, slot, item);
	}

	private static void markPlanFreight(ServerLevel level, List<PlanCargoSlot> slots, Item item) {
		for (PlanCargoSlot packed : slots) {
			Entity entity = level.getEntity(packed.donkey());
			if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) {
				var access = donkey.getSlot(500 + packed.slot());
				if (access != null && !access.get().isEmpty() && access.get().is(item)) {
					donkey.addTag(planFreightTag(item, packed.slot()));
				}
			}
		}
	}

	private static boolean hasPlanFreightTag(ServerLevel level, VillageRef ref, Item item) {
		String village = villageTag(ref.bell());
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof net.minecraft.world.entity.animal.equine.Donkey donkey && donkey.isAlive()
				&& donkey.entityTags().contains(village)) {
				for (int slot = 2; slot < 17; slot++) {
					var access = donkey.getSlot(500 + slot);
					if (access != null && !access.get().isEmpty() && access.get().is(item)
						&& isPlanFreightSlot(donkey, slot, item)) return true;
				}
			}
		}
		return false;
	}

	private static void clearPlanFreightTagIfEmpty(net.minecraft.world.entity.animal.equine.Donkey donkey, Item item, int slot) {
		var access = donkey.getSlot(500 + slot);
		if (access == null || access.get().isEmpty() || !access.get().is(item)) {
			donkey.removeTag(planFreightTag(item, slot));
		}
	}

	/** Route every matching cargo stack to the builder chest until its saved demand is physically there. */
	private static boolean planStockNeeded(ServerLevel level, VillageRef ref, Item item) {
		return planStockRoomInChest(level, ref, item) > 0;
	}

	private static int planStockRoomInChest(ServerLevel level, VillageRef ref, Item item) {
		Container chest = ref.chest(level);
		if (chest == null) return 0;
		int requested = PlanSupplyOrder.shortages(ref.data()).getOrDefault(item, 0);
		return Math.max(0, requested - JobUtil.count(chest, stack -> stack.is(item)));
	}

	private static boolean planShortagesSatisfied(ServerLevel level, VillageRef ref, Container chest) {
		for (Map.Entry<Item, Integer> shortage : PlanSupplyOrder.shortages(ref.data()).entrySet()) {
			// A successful real shipment closes the caravan attempt for this visit. Local-source
			// shortages must still block the callback; permanently unavailable blocks are handed
			// to Builder's biome palette once no matching plan freight remains in transit.
			if (!Supply.obtainable(Block.byItem(shortage.getKey()))) continue;
			if (JobUtil.count(chest, stack -> stack.is(shortage.getKey())) < shortage.getValue()) return false;
		}
		return true;
	}

	private static void insert(ServerLevel level, State caravan, VillageRef ref, Container box, ItemStack stack) {

		// Echte Handelsware wird in die Eseltruhe geladen und nach Ankunft stapelweise entladen.

		ItemStack rest = stack.copy(); packCargo(level, caravan, rest);

		// Preserve the old safe fallback only when a physical pack animal cannot be placed.
		// If it is used, the log makes this visible because porter delivery was bypassed.
		if (!rest.isEmpty()) {
			Lager.deposit(level, ref, rest);
			org.slf4j.LoggerFactory.getLogger("villagerplus").warn("Karawanenfracht mangels sicherem Packesel direkt eingelagert: {} x {}", rest.getCount(), rest.getHoverName().getString());
		}

	}

	/** Kauft fehlende Tierarten (je 2 Tiere) fuer das Gehege, solange Smaragde da sind. */

	private static int restockPen(ServerLevel level, VillageRef ref, Container box) {

		if (ref.data().extra.pen.isEmpty()) {

			return 0;

		}

		BlockPos pen = ref.data().extra.pen.get();

		int bought = 0;

		Object[][] stock = {{EntityTypes.COW, 6}, {EntityTypes.SHEEP, 4}, {EntityTypes.PIG, 5}, {EntityTypes.CHICKEN, 2}};

		for (Object[] row : stock) {

			@SuppressWarnings("unchecked")

			EntityType<? extends Animal> type = (EntityType<? extends Animal>) row[0];

			int price = (Integer) row[1] * 2;

			long present = level.getEntitiesOfClass(Animal.class, new AABB(pen).inflate(6, 3, 6), a -> a.isAlive() && a.getType() == type).size();

			int physical=JobUtil.count(box, s -> s.is(Items.EMERALD));
			if (present >= 2 || (long)physical+Network.treasuryEmeralds(ref) < price) {

				continue;

			}

			int fromBox=Math.min(physical,price);
			long fromTreasury=price-fromBox;
			if(fromTreasury>0&&!Network.spendTreasuryEmeralds(ref,fromTreasury)) continue;
			JobUtil.take(box, s -> s.is(Items.EMERALD), fromBox);

			for (int i = 0; i < 2; i++) {

				BlockPos at = pen.offset(i == 0 ? -1 : 1, 0, i == 0 ? 1 : -1);

				if (type.spawn(level, at, EntitySpawnReason.EVENT) != null) {

					bought++;

				}

			}

		}

		return bought;

	}

}

