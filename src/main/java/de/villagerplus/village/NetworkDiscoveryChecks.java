package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Checks the production caravan-contact transition, persistence, and board-facing partner rows. */
public final class NetworkDiscoveryChecks {
	private NetworkDiscoveryChecks() { }

	public static int run() {
		int checks = 0;
		checks += relationStageChecks();
		checks += loadedVillageDiscoveryChecks();
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
		require(Network.canDiscoverLoadedNeighbor(west, east, dimension, westBell, eastBell, 5),
			"a loaded real neighbor with residents is eligible for automatic discovery"); checks++;
		require(!Network.canDiscoverLoadedNeighbor(west, null, dimension, westBell, eastBell, 5)
			&& !Network.canDiscoverLoadedNeighbor(west, east, dimension, westBell, eastBell, 0),
			"an unloaded bell or one without living residents is not a discovery candidate"); checks++;

		Network.DiscoveryPair found = Network.discoverLoadedPair(west, east, dimension, westBell, eastBell,
			"Westheim", "Ostheim", 4, 5, 12L);
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
			westBell, eastBell, "Westheim", "Ostheim", 4, 5, 12L);
		require(!duplicate.changed() && duplicate.source().links().size() == 1 && duplicate.target().links().size() == 1,
			"repeated discovery is idempotent and never duplicates either village link"); checks++;

		Network.DiscoveryPair unloaded = Network.discoverLoadedPair(west, null, dimension, westBell, eastBell,
			"Westheim", "Ostheim", 4, 5, 12L);
		require(!unloaded.changed() && unloaded.source().equals(west) && unloaded.target() == null
			&& !Network.reciprocalLink(unloaded.source(), unloaded.target(), dimension, westBell, eastBell),
			"an unloaded neighboring bell remains undiscovered and cannot become an action target"); checks++;

		List<TradeNetwork.Link> fullLinks = new java.util.ArrayList<>();
		for (int i = 0; i < TradeNetwork.MAX_LINKS; i++) {
			fullLinks.add(new TradeNetwork.Link(dimension, new BlockPos(1000 + i * 32, 64, -300), "Existing" + i, 10, false, 1, 1L));
		}
		TradeNetwork fullWest = west.withLinks(fullLinks);
		require(!Network.canDiscoverLoadedNeighbor(fullWest, east, dimension, westBell, eastBell, 5)
			&& !Network.canDiscoverLoadedNeighbor(west, east.withLinks(fullLinks), dimension, westBell, eastBell, 5),
			"discovery respects the link limit at both ends instead of creating a one-way overflow"); checks++;

		TradeNetwork oneSided = west.withLinks(List.of(new TradeNetwork.Link(dimension, eastBell, "Ostheim", 42, true, 2, 10L)));
		Network.DiscoveryPair repaired = Network.discoverLoadedPair(oneSided, east, dimension, westBell, eastBell,
			"Westheim", "Ostheim", 4, 5, 12L);
		require(repaired.changed() && Network.reciprocalLink(repaired.source(), repaired.target(), dimension, westBell, eastBell)
			&& repaired.target().links().getFirst().relation() == 42 && repaired.target().links().getFirst().route(),
			"a real loaded encounter completes a one-sided link while preserving its relation and route state"); checks++;

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
