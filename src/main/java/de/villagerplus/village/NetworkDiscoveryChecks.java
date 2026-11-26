package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import com.mojang.authlib.GameProfile;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.ButcherChecks;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/** Checks the production caravan-contact transition, persistence, and board-facing partner rows. */
public final class NetworkDiscoveryChecks {
	private NetworkDiscoveryChecks() { }

	/** Exercise the arrival handler used by the ordinary guest tick against two real village bells. */
	public static int guestArrival(ServerLevel level, BlockPos hostBell) {
		BlockPos originBell = hostBell.east(256);
		boolean hostReady = ButcherChecks.prepareEntityTickingFixture(level, hostBell);
		boolean originReady = ButcherChecks.prepareEntityTickingFixture(level, originBell);
		if (!hostReady || !originReady) return -1;
		Villager resident = null;
		Villager guest = null;
		try {
			VillageRef host = Checks090.fixtureForEquipment(level, hostBell);
			VillageRef origin = Checks090.fixtureForEquipment(level, originBell);
			resident = fixtureVillager(level, originBell.south(2));
			Villages.bindResident(level, origin, resident, null);
			BlockPos goal = host.chestPos(level);
			BlockPos startingPoint = goal.south(10);
			guest = fixtureVillager(level, startingPoint);
			guest.addTag(Guests.TAG);
			GlobalPos destination = GlobalPos.of(level.dimension(), hostBell);
			String dimension = Network.dim(level);
			long now = level.getGameTime();
			int checks = 0;
			Network.GuestOrigin visitorOrigin = Network.nextGuestOrigin(level, host);
			require(visitorOrigin != null && visitorOrigin.bell().pos().equals(originBell) && visitorOrigin.population() == 1,
				"natural guest selection captures an inhabited real bell and its current population"); checks++;
			require(level.players().stream().noneMatch(player -> player.blockPosition().distSqr(originBell) <= 40L * 40L),
				"no player is visiting the real origin"); checks++;
			ServerPlayer actionPlayer = new ServerPlayer(level.getServer(), level,
				new GameProfile(UUID.randomUUID(), "GuestNetworkFixture"), ClientInformation.createDefault());
			actionPlayer.setPos(Vec3.atBottomCenterOf(hostBell.south(2)));
			Guests.State visit = new Guests.State(destination, startingPoint, visitorOrigin.name(), visitorOrigin);
			require(!Guests.arrive(level, host, guest, goal, visit, now)
				&& Network.of(host.bellEntity()).links().isEmpty()
				&& !Network.act(level, actionPlayer, hostBell, Network.ACTION_GREET, 0, dimension, originBell).success(),
				"a guest still on the way unlocks no village action"); checks++;

			guest.setPos(Vec3.atBottomCenterOf(goal.south()));
			Guests.State fictional = new Guests.State(destination, startingPoint, visitorOrigin.name(), null);
			require(Guests.arrive(level, host, guest, goal, fictional, now) && Network.of(host.bellEntity()).links().isEmpty(),
				"a fictional visitor with the same displayed name never identifies a real village"); checks++;
			require(Guests.arrive(level, host, guest, goal, visit, now),
				"the arrival handler accepts the real visitor in the village center"); checks++;
			TradeNetwork hostNetwork = Network.of(host.bellEntity());
			TradeNetwork originNetwork = Network.of(origin.bellEntity());
			require(Network.reciprocalLink(hostNetwork, originNetwork, dimension, hostBell, originBell),
				"a real visitor arrival creates reciprocal actionable links without a player visiting"); checks++;
			require(hostNetwork.links().getFirst().relation() == 10 && !hostNetwork.links().getFirst().route()
				&& hostNetwork.links().getFirst().lastVisitDay() == -1L && originNetwork.links().getFirst().lastVisitDay() == -1L
				&& hostNetwork.partners().stream().allMatch(partner -> partner.lastVisitDay() == -1L),
				"guest discovery starts neutral and records no trade or automatic route"); checks++;
			require(!Guests.arrive(level, host, guest, goal, visit, now + 40)
				&& Network.of(host.bellEntity()).equals(hostNetwork) && Network.of(origin.bellEntity()).equals(originNetwork),
				"further arrival ticks do not duplicate the relationship"); checks++;
			TradeNetwork reloaded = TradeNetwork.CODEC.parse(JsonOps.INSTANCE,
				TradeNetwork.CODEC.encodeStart(JsonOps.INSTANCE, hostNetwork).getOrThrow()).getOrThrow();
			Network.save(host.bellEntity(), reloaded);
			require(reloaded.equals(hostNetwork) && Network.neighborRows(level, host).stream().anyMatch(row -> row.startsWith(visitorOrigin.name() + "|"))
				&& VillageMap.mapNeighborBells(level, host).contains(originBell),
				"the saved contact survives codec restoration and appears on the map and neighbor panel"); checks++;
			require(Network.act(level, actionPlayer, hostBell, Network.ACTION_GREET, 0, dimension, originBell).success(),
				"the host can greet the newly discovered origin at the board"); checks++;
			return checks;
		} finally {
			if (guest != null) guest.discard();
			if (resident != null) resident.discard();
			ButcherChecks.releaseEntityTickingFixture(level, hostBell);
			ButcherChecks.releaseEntityTickingFixture(level, originBell);
		}
	}

	private static Villager fixtureVillager(ServerLevel level, BlockPos position) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, position, EntitySpawnReason.COMMAND);
		require(villager != null, "fixture villager spawned");
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setPos(Vec3.atBottomCenterOf(position));
		return villager;
	}

	public static int run() {
		int checks = 0;
		checks += relationStageChecks();
		checks += loadedVillageDiscoveryChecks();
		checks += unloadedGuestArrivalChecks();
		BlockPos bell = new BlockPos(120, 70, -45);
		TradeNetwork initial = TradeNetwork.create(bell, "desert");
		TradeNetwork forest = TradeNetwork.create(bell, "forest");
		TradeNetwork.Partner first = initial.partners().getFirst();
		String name = first.name();
		require(!name.isBlank() && first.lastVisitDay() < 0, "generated partner name remains stored before contact"); checks++;
		require(!initial.partners().equals(forest.partners()), "generated partner names depend on the source biome"); checks++;
		require(Network.partnerDisplayName(first).startsWith("Unbekannter Ort"), "undiscovered partner name is hidden"); checks++;
		List<String> beforeRows = Network.partnerRows(initial, 12);
		require(beforeRows.getFirst().startsWith("Unbekannter Ort"), "partnerRows hides the generated name before contact"); checks++;
		List<String> beforeVisible = VillageMap.partnerDisplayLines(initial, 12).stream().map(line -> line.getString()).toList();
		require(beforeVisible.getFirst().contains("Unbekannter Ort") && !beforeVisible.getFirst().contains(name),
			"visible map output uses the unknown partner row"); checks++;

		TradeNetwork reloadedUnknown = TradeNetwork.CODEC.parse(JsonOps.INSTANCE,
			TradeNetwork.CODEC.encodeStart(JsonOps.INSTANCE, initial).getOrThrow()).getOrThrow();
		require(reloadedUnknown.equals(initial) && Network.partnerRows(reloadedUnknown, 12).getFirst().startsWith("Unbekannter Ort"),
			"undiscovered state and hidden-name rule survive codec restoration"); checks++;

		TradeNetwork failed = Caravan.afterExchange(reloadedUnknown, 0, 12, false);
		require(failed.equals(reloadedUnknown), "failed caravan exchange aborts without recording contact"); checks++;
		require(Network.partnerRows(failed, 12).getFirst().startsWith("Unbekannter Ort"), "failed exchange leaves partner undiscovered"); checks++;

		TradeNetwork successful = Caravan.afterExchange(failed, 0, 12, true);
		require(successful.partners().getFirst().lastVisitDay() == 12, "successful caravan exchange records the contact day"); checks++;
		require(Network.partnerDisplayName(successful.partners().getFirst()).equals(name), "successful exchange reveals the stored generated name"); checks++;
		require(successful.partners().get(1).equals(initial.partners().get(1)), "contact only discovers the selected partner"); checks++;
		require(Network.partnerRows(successful, 12).getFirst().startsWith(name + "|"), "partnerRows exposes the name after successful contact"); checks++;
		List<String> visibleAfter = VillageMap.partnerDisplayLines(successful, 12).stream().map(line -> line.getString()).toList();
		require(visibleAfter.getFirst().contains(name) && !visibleAfter.getFirst().contains("Unbekannter Ort"),
			"visible map output exposes the discovered name"); checks++;

		TradeNetwork restored = TradeNetwork.CODEC.parse(JsonOps.INSTANCE,
			TradeNetwork.CODEC.encodeStart(JsonOps.INSTANCE, successful).getOrThrow()).getOrThrow();
		require(restored.equals(successful), "discovered partner state survives codec restoration"); checks++;
		require(Network.partnerRows(restored, 20).getFirst().startsWith(name + "|"), "discovered name remains visible after restoration"); checks++;

		TradeNetwork.Link realNeighbor = new TradeNetwork.Link("minecraft:overworld", new BlockPos(220, 70, -45), "Ostheim", 42, true, 5, 12L);
		TradeNetwork historyWithNeighbor = initial.withLinks(List.of(realNeighbor));
		TradeNetwork historyOnlyTrade = Caravan.afterExchange(historyWithNeighbor, 0, 12L, true);
		require(historyOnlyTrade.links().equals(historyWithNeighbor.links())
			&& historyOnlyTrade.partners().getFirst().lastVisitDay() == 12L,
			"a successful name-only legacy partner trade cannot inflate an unrelated real village relationship"); checks++;
		return checks;
	}

	private static int loadedVillageDiscoveryChecks() {
		int checks = 0;
		String dimension = "minecraft:overworld";
		BlockPos westBell = new BlockPos(-120, 68, 40);
		BlockPos eastBell = new BlockPos(96, 72, 40);
		TradeNetwork west = TradeNetwork.create(westBell, "plains");
		TradeNetwork east = TradeNetwork.create(eastBell, "forest");
		require(!Network.canDiscoverLoadedNeighbor(west, east, dimension, westBell, eastBell, 5, false),
			"an unknown loaded neighbor without a physical visit or trade contact is not discovered"); checks++;
		Network.DiscoveryPair noContact = Network.discoverLoadedPair(west, east, dimension, westBell, eastBell,
			"Westheim", "Ostheim", 4, 5, 12L, false);
		require(!noContact.changed() && noContact.source().links().isEmpty() && noContact.target().links().isEmpty(),
			"without contact neither village stores a link to the other"); checks++;
		require(Network.canDiscoverLoadedNeighbor(west, east, dimension, westBell, eastBell, 5, true),
			"a loaded real neighbor with residents is discovered once a player or resident has physically been there"); checks++;
		require(!Network.canDiscoverLoadedNeighbor(west, null, dimension, westBell, eastBell, 5, true)
			&& !Network.canDiscoverLoadedNeighbor(west, east, dimension, westBell, eastBell, 0, true),
			"an unloaded bell or one without living residents is not a discovery candidate"); checks++;

		Network.DiscoveryPair found = Network.discoverLoadedPair(west, east, dimension, westBell, eastBell,
			"Westheim", "Ostheim", 4, 5, 12L, true);
		TradeNetwork.Link westToEast = found.source().links().getFirst();
		TradeNetwork.Link eastToWest = found.target().links().getFirst();
		require(found.changed() && westToEast.bell().equals(eastBell) && westToEast.dimension().equals(dimension)
			&& westToEast.name().equals("Ostheim") && westToEast.population() == 5,
			"discovery persists the neighbor's actual dimension, bell, name, and resident count"); checks++;
		require(eastToWest.bell().equals(westBell) && eastToWest.dimension().equals(dimension)
			&& eastToWest.name().equals("Westheim") && eastToWest.population() == 4
			&& Network.reciprocalLink(found.source(), found.target(), dimension, westBell, eastBell),
			"automatic discovery writes the matching real village backlink"); checks++;
		require(Network.selectedLinkIndex(found.source(), 0, dimension, eastBell) == 0
			&& Network.selectedLinkIndex(found.target(), 0, dimension, westBell) == 0,
			"both loaded reciprocal links resolve as stable action targets"); checks++;

		Network.DiscoveryPair duplicate = Network.discoverLoadedPair(found.source(), found.target(), dimension,
			westBell, eastBell, "Westheim", "Ostheim", 4, 5, 12L, false);
		require(!duplicate.changed() && duplicate.source().links().size() == 1 && duplicate.target().links().size() == 1,
			"repeated discovery is idempotent and never duplicates either village link"); checks++;

		Network.DiscoveryPair unloaded = Network.discoverLoadedPair(west, null, dimension, westBell, eastBell,
			"Westheim", "Ostheim", 4, 5, 12L, true);
		require(!unloaded.changed() && unloaded.source().equals(west) && unloaded.target() == null
			&& !Network.reciprocalLink(unloaded.source(), unloaded.target(), dimension, westBell, eastBell),
			"an unloaded neighboring bell remains undiscovered and cannot become an action target"); checks++;

		List<TradeNetwork.Link> fullLinks = new java.util.ArrayList<>();
		for (int i = 0; i < TradeNetwork.MAX_LINKS; i++) {
			fullLinks.add(new TradeNetwork.Link(dimension, new BlockPos(1000 + i * 32, 64, -300), "Existing" + i, 10, false, 1, 1L));
		}
		TradeNetwork fullWest = west.withLinks(fullLinks);
		require(!Network.canDiscoverLoadedNeighbor(fullWest, east, dimension, westBell, eastBell, 5, true)
			&& !Network.canDiscoverLoadedNeighbor(west, east.withLinks(fullLinks), dimension, westBell, eastBell, 5, true),
			"discovery respects the link limit at both ends instead of creating a one-way overflow"); checks++;

		TradeNetwork oneSided = west.withLinks(List.of(new TradeNetwork.Link(dimension, eastBell, "Ostheim", 42, true, 2, 10L)));
		Network.DiscoveryPair repaired = Network.discoverLoadedPair(oneSided, east, dimension, westBell, eastBell,
			"Westheim", "Ostheim", 4, 5, 12L, false);
		require(repaired.changed() && Network.reciprocalLink(repaired.source(), repaired.target(), dimension, westBell, eastBell)
			&& repaired.target().links().getFirst().relation() == 42 && repaired.target().links().getFirst().route(),
			"a one-sided link saved earlier (legacy world) is completed without new contact and keeps its relation and route state"); checks++;
		TradeNetwork.Link visited = new TradeNetwork.Link(dimension, eastBell, "Ostheim", 33, false, 2, 10L, 11L);
		require(Network.canDiscoverLoadedNeighbor(west.withLinks(List.of(visited)), east, dimension, westBell, eastBell, 5, false),
			"a neighbor already saved after a successful visit stays refreshable without a new physical contact"); checks++;

		String nameA = Names.village("plains", westBell.asLong());
		String nameB = Names.village("desert", westBell.asLong());
		require(!nameA.isBlank() && nameA.equals(Names.village("plains", westBell.asLong()))
			&& !nameB.isBlank() && !nameA.equals(nameB) && !nameA.equals(Names.village("plains", eastBell.asLong())),
			"the own village name is procedural, stable for one bell, and depends on biome and bell position"); checks++;

		List<Integer> markerCells = VillageMap.nearbyVillageMarkerIndices(4, -32, -32, 16,
			new BlockPos(-24, 68, -24), List.of(new BlockPos(-24, 68, -24), new BlockPos(-24, 120, -24), new BlockPos(0, 70, 0),
				new BlockPos(1, 71, 1), new BlockPos(80, 64, 80)));
		require(markerCells.equals(List.of(10)),
			"the map omits its own bell and out-of-bounds villages and renders multiple neighbors in one cell once"); checks++;
		List<BlockPos> savedMapNeighbors = VillageMap.linkedNeighborBells(dimension, List.of(
			new TradeNetwork.Link(dimension, new BlockPos(-8, 68, 24), "Geladenes Dorf", 10, false, 3, 4L),
			new TradeNetwork.Link(dimension, new BlockPos(-8, 68, 24), "Doppelter Link", 10, false, 3, 4L),
			new TradeNetwork.Link("minecraft:the_nether", new BlockPos(0, 70, 0), "Anderes Reich", 10, false, 2, 4L)));
		require(savedMapNeighbors.equals(List.of(new BlockPos(-8, 68, 24)))
			&& VillageMap.nearbyVillageMarkerIndices(4, -32, -32, 16, new BlockPos(-24, 68, -24), savedMapNeighbors).equals(List.of(13)),
			"the board keeps a same-dimension discovered neighbor marked after unload and ignores duplicate or cross-dimension links"); checks++;

		List<String> mapNeighbors = VillageMap.neighborSummaryRows(List.of(
			"Ostheim|124 Blöcke Osten|Osten|5|10 (Neutral) · noch nie besucht|keine|1|kein Handel|dein Nein / Nachbar unbekannt|keine|0|minecraft:overworld|" + eastBell.asLong(),
			"Ostheim-alt|124 Blöcke Osten|Osten|5|10 (Neutral) · noch nie besucht|keine|1|kein Handel|dein Nein / Nachbar unbekannt|keine|0|minecraft:overworld|" + eastBell.asLong(),
			"Westheim|216 Blöcke Westen|Westen|4|10 (Neutral) · noch nie besucht|keine|0|nicht geladen|dein Nein / Nachbar unbekannt|keine|0|minecraft:overworld|" + westBell.asLong(),
			"Unbekannter Ort (noch kein Handelskontakt)|Erze & Werkzeug|5|noch nie"));
		require(mapNeighbors.size() == 2 && mapNeighbors.getFirst().startsWith("Ostheim · 124 Blöcke Osten · Beziehung 10 (Neutral)")
			&& mapNeighbors.get(1).contains("Westheim · 216 Blöcke Westen · Beziehung 10 (Neutral)")
			&& mapNeighbors.get(1).endsWith("nicht geladen")
			&& mapNeighbors.stream().noneMatch(row -> row.contains("Unbekannter Ort") || row.contains("Ostheim-alt")),
			"the map lists real neutral villages before trade, preserves unloaded identities, and suppresses duplicate bells and fictional partners"); checks++;
		return checks;
	}

	/** A confirmed arrival must remain actionable while the origin is unloaded, then reconcile when it becomes available. */
	private static int unloadedGuestArrivalChecks() {
		int checks = 0;
		String dimension = "minecraft:overworld";
		BlockPos hostBell = new BlockPos(120, 70, -45);
		BlockPos originBell = new BlockPos(376, 71, -45);
		TradeNetwork host = TradeNetwork.create(hostBell, "taiga");
		TradeNetwork origin = TradeNetwork.create(originBell, "plains");

		TradeNetwork contacted = Network.rememberUnloadedGuestContact(host, dimension, originBell, "Fernheim", 4, 8L);
		require(contacted != null && contacted.links().size() == 1 && origin.links().isEmpty(),
			"a real guest arriving from an unloaded origin persists the host-side contact without touching the origin or loading its chunk"); checks++;
		TradeNetwork.Link hostLink = contacted.links().getFirst();
		require(hostLink.dimension().equals(dimension) && hostLink.bell().equals(originBell) && hostLink.name().equals("Fernheim")
			&& hostLink.population() == 4 && hostLink.relation() == 10 && !hostLink.route() && hostLink.lastVisitDay() == -1L,
			"the remembered contact stores the confirmed bell identity and neutral, unvisited relationship state"); checks++;
		require(Network.rememberUnloadedGuestContact(contacted, dimension, originBell, "Fernheim", 4, 8L).equals(contacted),
			"repeated guest-arrival ticks do not duplicate the saved one-sided link"); checks++;

		TradeNetwork routeQueued = Network.queueRemoteRoute(contacted, dimension, originBell, 24000L);
		TradeNetwork greetQueued = Network.queueRemoteGreeting(routeQueued, dimension, originBell, 24000L);
		require(routeQueued != null && greetQueued != null && greetQueued.hasPendingAction(Network.ACTION_ROUTE, dimension, originBell)
			&& greetQueued.hasPendingAction(Network.ACTION_GREET, dimension, originBell),
			"route and greeting actions can be queued against a confirmed but currently unloaded visitor origin"); checks++;

		Network.DiscoveryPair loadedPair = Network.discoverLoadedPair(greetQueued, origin, dimension, hostBell, originBell,
			"Heimdorf", "Fernheim", 2, 4, 9L, false);
		require(loadedPair.changed() && Network.reciprocalLink(loadedPair.source(), loadedPair.target(), dimension, hostBell, originBell),
			"loading the visitor origin later repairs the reciprocal link using the stored physical contact"); checks++;
		Network.DiscoveryPair applied = Network.reconcilePendingActions(loadedPair.source(), loadedPair.target(),
			dimension, hostBell, originBell, 24001L);
		require(applied.changed() && Network.reciprocalLink(applied.source(), applied.target(), dimension, hostBell, originBell)
			&& applied.source().links().getFirst().route() && applied.target().links().getFirst().route()
			&& applied.source().links().getFirst().relation() == 15 && applied.target().links().getFirst().relation() == 15
			&& !applied.source().hasPendingAction(Network.ACTION_ROUTE, dimension, originBell)
			&& !applied.source().hasPendingAction(Network.ACTION_GREET, dimension, originBell),
			"deferred route and greeting actions apply once the reciprocal bell pair is available"); checks++;
		return checks;
	}

	private static int relationStageChecks() {
		int checks = 0;
		checkStage(-100, "Verfeindet"); checks++;
		checkStage(-1, "Verfeindet"); checks++;
		checkStage(0, "Neutral"); checks++;
		checkStage(29, "Neutral"); checks++;
		checkStage(30, "Handelspartner"); checks++;
		checkStage(59, "Handelspartner"); checks++;
		checkStage(60, "Bündnispartner"); checks++;
		checkStage(89, "Bündnispartner"); checks++;
		checkStage(90, "Metropolen-Verbund"); checks++;
		checkStage(100, "Metropolen-Verbund"); checks++;

		BlockPos bell = new BlockPos(120, 70, -45);
		// D03: road work between two villages starts at the trade-partner threshold (relation >= 30); an explicit route link overrides it.
		TradeNetwork.Link noRoute = new TradeNetwork.Link("minecraft:overworld", bell, "Grenzort", 0, false, 4, 7);
		require(!Roads.roadLinkActive(noRoute.withRelation(29)) && Roads.roadLinkActive(noRoute.withRelation(30))
			&& Roads.roadLinkActive(noRoute.withRelation(100)) && !Roads.roadLinkActive(noRoute.withRelation(-5)),
			"a neighbour without route link carries road work exactly from relation 30 (29 does not)"); checks++;
		require(Roads.roadLinkActive(noRoute.withRelation(0).withRoute(true)) && !Roads.roadLinkActive(null),
			"an explicit route link carries road work at any relation; a missing link never does"); checks++;
		TradeNetwork.Link base = new TradeNetwork.Link("minecraft:overworld", bell, "Grenzort", 0, true, 4, 7);
		TradeNetwork.Link clampedHigh = base.withRelation(101);
		require(clampedHigh.relation() == 100 && clampedHigh.route() && clampedHigh.population() == 4,
			"relation update caps above the documented maximum without changing link state"); checks++;
		TradeNetwork.Link clampedLow = base.withRelation(-101);
		require(clampedLow.relation() == -100 && clampedLow.route() && clampedLow.population() == 4,
			"relation update caps below the documented minimum without changing link state"); checks++;
		return checks;
	}

	private static void checkStage(int relation, String expected) {
		require(Network.stage(relation).equals(expected), "relation " + relation + " maps to " + expected);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Network discovery check failed: " + message);
	}
}
