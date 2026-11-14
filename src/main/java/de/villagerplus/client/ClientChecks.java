package de.villagerplus.client;

import de.villagerplus.net.BoardData;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Explicit development-hook check of the scaled map's real pointer event handlers. */
final class ClientChecks {
	private ClientChecks() { }
	private static java.lang.reflect.Field field(String name) throws ReflectiveOperationException {
		var field = BoardScreen.class.getDeclaredField(name); field.setAccessible(true); return field;
	}
	private static Object inheritedField(Object target, String name) throws ReflectiveOperationException {
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			try {
				var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
			} catch (NoSuchFieldException ignored) { }
		}
		throw new NoSuchFieldException(name);
	}
	private static void check(boolean value, String label) {
		if (!value) throw new IllegalStateException("VPCLIENT FAILED: " + label);
	}
	static void map(BoardScreen screen) throws ReflectiveOperationException {
		int tab = field("tab").getInt(screen), cell = field("mapCell").getInt(screen);
		int panX = field("mapPanX").getInt(screen), panY = field("mapPanY").getInt(screen);
		try {
			field("tab").setInt(screen, 0); field("mapCell").setInt(screen, 3);
			field("mapPanX").setInt(screen, 0); field("mapPanY").setInt(screen, 0);
			float scale = field("uiScale").getFloat(screen);
			int left = field("left").getInt(screen), top = field("top").getInt(screen);
			check(left * scale >= 0 && top * scale >= 0 && (left + 460) * scale <= screen.width && (top + 280) * scale <= screen.height, "panel fits GUI");
			int[] viewport = BoardScreen.mapViewportBounds(left, top), frame = BoardScreen.mapFrameBounds(left, top);
			int actionTop = top + field("MAP_ACTION_ROW_Y").getInt(null);
			int helpTop = viewport[1] + viewport[3] + field("MAP_FRAME_BOTTOM_PADDING").getInt(null);
			int helpEnd = helpTop + field("MAP_HELP_LINE_HEIGHT").getInt(null);
			check(frame[1] + frame[3] <= helpTop && helpEnd <= actionTop
				&& frame[0] + frame[2] + 11 == viewport[0] + viewport[2] + 16
				&& BoardScreen.mapViewportContains(viewport[0] + 0.5, viewport[1] + 0.5, viewport[0], viewport[1], viewport[2])
				&& !BoardScreen.mapViewportContains(frame[0] + 0.5, viewport[1] + 0.5, viewport[0], viewport[1], viewport[2]),
				"map frame stays separate from the shared scroll/click viewport, detail column, and action row");
			int view = viewport[2];
			double x = (viewport[0] + view / 2.0) * scale, y = (viewport[1] + view / 2.0) * scale;
			screen.mouseScrolled(x, y, 0, 0);
			check(field("mapPanX").getInt(screen) == 12 && field("mapPanY").getInt(screen) == 12, "full map is centered in the clear viewport");
			screen.mouseScrolled(x, y, 0, 1);
			check(field("mapCell").getInt(screen) == 5 && field("mapPanX").getInt(screen) == -36 && field("mapPanY").getInt(screen) == -36, "zoom preserves pointer anchor");
			var right = new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
			check(screen.mouseClicked(right, false), "map accepts drag");
			screen.mouseDragged(right, 12 * scale, 8 * scale); screen.mouseReleased(right);
			check(field("mapPanX").getInt(screen) == -24 && field("mapPanY").getInt(screen) == -28, "drag uses scaled pointer delta");
			screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), true);
			check(field("mapCell").getInt(screen) == 3 && field("mapPanX").getInt(screen) == 12 && field("mapPanY").getInt(screen) == 12, "double click resets centered map");
		} finally {
			field("tab").setInt(screen, tab); field("mapCell").setInt(screen, cell);
			field("mapPanX").setInt(screen, panX); field("mapPanY").setInt(screen, panY);
			field("draggingMap").setBoolean(screen, false);
		}
		mapLocations(screen);
		mapNeighborPins();
		mapNeighborPinClicks(screen);
		mapZones(screen);
		mapGeometry();
		constructionText();
		networkActions(screen);
		caravanTraderProportions();
		CaravanTraderChecks.run();
		headerLayout(screen);
		footerLayout(screen);
		residentTalk(screen);
	}

	/** Uses actual neighbor-link rows to verify map projection and rejects rows without concrete local bell identity. */
	private static void mapNeighborPins() {
		int size = 8, cellSize = 4, originX = 1200, originZ = 2400;
		net.minecraft.core.BlockPos bell = new net.minecraft.core.BlockPos(1213, 64, 2426);
		net.minecraft.core.BlockPos outside = new net.minecraft.core.BlockPos(originX + size * cellSize, 64, originZ + 8);
		net.minecraft.core.BlockPos foreign = new net.minecraft.core.BlockPos(1204, 64, 2408);
		String valid = neighborMapRow("Nordheim", "3 (Handelspartner)", "minecraft:overworld", bell);
		String outOfMap = neighborMapRow("Hinterwald", "2 (Handelspartner)", "minecraft:overworld", outside);
		String otherDimension = neighborMapRow("Ferne", "2 (Handelspartner)", "minecraft:the_nether", foreign);
		List<String> neighborRows = List.of(valid, valid, outOfMap, otherDimension, "Ohne Bell|40 Blöcke Norden");
		BoardData discovered = new BoardData(new net.minecraft.core.BlockPos(1200, 64, 2400), "Fixture", size, cellSize,
			new byte[size * size], new byte[size * size], List.of(), List.of(), List.of(), List.of(), neighborRows,
			List.of("@guest|Mira|2|anwesend", "Altstadt|historische Verbindung ohne Bellposition"), List.of());
		List<BoardScreen.NeighborMapPin> pins = BoardScreen.neighborMapPins(discovered, "minecraft:overworld", originX, originZ);
		check(pins.size() == 1, "neighbor map pins deduplicate real links and suppress out-of-map, foreign-dimension, and incomplete rows");
		BoardScreen.NeighborMapPin pin = pins.get(0);
		check(pin.name().equals("Nordheim") && pin.worldX() == 1213 && pin.worldZ() == 2426
			&& pin.col() == 3 && pin.row() == 6,
			"neighbor bell world coordinates project into the correct map cell");
		String[] unloadedRow = valid.split("\\|", -1);
		unloadedRow[6] = "0";
		BoardData unloadedBoard = new BoardData(discovered.board(), discovered.village(), size, cellSize,
			new byte[size * size], new byte[size * size], List.of(), List.of(), List.of(), List.of(),
			List.of(String.join("|", unloadedRow)), discovered.partners(), List.of());
		List<BoardScreen.NeighborMapPin> unloadedPins = BoardScreen.neighborMapPins(unloadedBoard,
			"minecraft:overworld", originX, originZ);
		check(unloadedPins.size() == 1 && unloadedPins.getFirst().name().equals("Nordheim")
			&& unloadedPins.getFirst().status().equals("nicht geladen"),
			"a persisted neighbor keeps its saved name and map position while its bell is unloaded");
		check(BoardScreen.worldToMapPixelOffset(pin.worldX(), originX, cellSize, 3) == 9
			&& BoardScreen.worldToMapPixelOffset(pin.worldZ(), originZ, cellSize, 3) == 19
			&& BoardScreen.worldToMapPixelOffset(originX - 1, originX, cellSize, 3) == -1
			&& BoardScreen.worldToMapPixelOffset(originZ - 1, originZ, cellSize, 3) == -1,
			"map marker projection preserves sub-cell position and floors negative offsets consistently with hit testing");
		List<String> mergedRows = BoardScreen.relationshipRows(discovered.neighbors(), discovered.partners());
		check(BoardScreen.neighborMapRelationshipIndex(mergedRows, pin) == 0
			&& BoardScreen.neighborMapDetailLines(pin).equals(List.of("Beziehung: 3 (Handelspartner)",
				"Entfernung: 40 Blöcke Norden", "Status: geladen · Rückverbindung aktiv")),
			"pin selection maps to its exact linked relationship and exposes relationship, distance, and connection status");
		check(BoardScreen.neighborMapTooltip(pin).contains("Nordheim")
			&& BoardScreen.neighborMapTooltip(pin).contains("Handelspartner"),
			"neighbor pin tooltip shows its name and relationship");
		int mapLeft = 100, mapTop = 200, view = 168;
		double pinScreenX = mapLeft + BoardScreen.worldToMapPixelOffset(pin.worldX(), originX, cellSize, 3);
		double pinScreenY = mapTop + BoardScreen.worldToMapPixelOffset(pin.worldZ(), originZ, cellSize, 3);
		check(BoardScreen.neighborMapPinAt(pins, pinScreenX, pinScreenY, mapLeft, mapTop, view, 0, 0, 3,
			originX, originZ, cellSize) == pin
			&& BoardScreen.neighborMapPinAt(pins, mapLeft + view, pinScreenY, mapLeft, mapTop, view, 0, 0, 3,
				originX, originZ, cellSize) == null,
			"pin hit testing accepts its visible marker and rejects the outside viewport edge");
		BoardData historyOnly = new BoardData(discovered.board(), discovered.village(), size, cellSize,
			new byte[size * size], new byte[size * size], List.of(), List.of(), List.of(), List.of(), List.of(),
			List.of("@guest|Mira|2|anwesend", "Altstadt|historische Verbindung ohne Bellposition"), List.of());
		check(BoardScreen.neighborMapPins(historyOnly, "minecraft:overworld", originX, originZ).isEmpty(),
			"guest and historical relationship rows never become pins without neighbor bell coordinates");
	}

	/** Clicks the production map event path to verify neighbor selection and overlap priority with existing actions. */
	private static void mapNeighborPinClicks(BoardScreen screen) throws ReflectiveOperationException {
		var dataField = field("data");
		BoardData originalData = (BoardData)dataField.get(screen);
		List<String> originalQuota = BoardScreen.quota;
		int tab = field("tab").getInt(screen), mapCell = field("mapCell").getInt(screen);
		int panX = field("mapPanX").getInt(screen), panY = field("mapPanY").getInt(screen);
		int pickedBuilding = field("pickedBuilding").getInt(screen), pickedMapMarker = field("pickedMapMarker").getInt(screen);
		int pickedMapMarkerCode = field("pickedMapMarkerCode").getInt(screen), neighbor = field("neighbor").getInt(screen);
		long pickedNeighborBell = field("pickedNeighborMapBell").getLong(screen);
		String pickedNeighborDimension = (String)field("pickedNeighborMapDimension").get(screen);
		String neighborNotice = (String)field("neighborNotice").get(screen);
		boolean movePending = field("movePending").getBoolean(screen), zoneTool = field("zoneTool").getBoolean(screen);
		boolean drawingZone = field("drawingZone").getBoolean(screen);
		int zoneKind = field("zoneKind").getInt(screen), drawingZoneKind = field("drawingZoneKind").getInt(screen);
		int zoneStartX = field("zoneStartX").getInt(screen), zoneStartZ = field("zoneStartZ").getInt(screen);
		int zoneEndX = field("zoneEndX").getInt(screen), zoneEndZ = field("zoneEndZ").getInt(screen);
		boolean zoneClipped = field("zoneClipped").getBoolean(screen);
		String zoneNotice = (String)field("zoneNotice").get(screen);
		try {
			net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
			check(level != null, "neighbor pin click check has an active client dimension");
			int size = originalData.size(), cellSize = originalData.cellSize(), originX = 1200, originZ = 2400;
			check(size > 3 && cellSize > 0, "map fixture has usable world cells for neighbor pin clicks");
			int col = 1, row = 1;
			int bellX = originX + col * cellSize + cellSize / 2, bellZ = originZ + row * cellSize + cellSize / 2;
			net.minecraft.core.BlockPos bell = new net.minecraft.core.BlockPos(bellX, 64, bellZ);
			String dimension = level.dimension().identifier().toString();
			List<String> links = List.of(neighborMapRow("Nordheim", "3 (Handelspartner)", dimension, bell));
			byte[] marks = new byte[size * size];
			marks[0] = 1;
			BoardData fixture = new BoardData(originalData.board(), "Pin Fixture", size, cellSize,
				new byte[size * size], marks, originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), links, List.of("@guest|Mira|1|anwesend", "Altstadt|Handel|72|vor 2 Tagen"), originalData.journal());
			dataField.set(screen, fixture);
			BoardScreen.quota = List.of("@o|" + originX + "|" + originZ,
				"@b|school|Überlappende Schule|" + bellX + "|64|" + bellZ + "|0");
			field("tab").setInt(screen, 0); field("mapCell").setInt(screen, 3);
			field("mapPanX").setInt(screen, 0); field("mapPanY").setInt(screen, 0);
			field("pickedBuilding").setInt(screen, -1); field("pickedMapMarker").setInt(screen, -1);
			field("pickedMapMarkerCode").setInt(screen, 0); field("pickedNeighborMapDimension").set(screen, null);
			field("pickedNeighborMapBell").setLong(screen, 0); field("movePending").setBoolean(screen, false);
			field("zoneTool").setBoolean(screen, false); field("drawingZone").setBoolean(screen, false);
			field("zoneKind").setInt(screen, 0);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility"); visibility.setAccessible(true); visibility.invoke(screen);
			BoardScreen.quota = List.of("@o|" + originX + "|" + originZ);
			check(clickMapWorld(screen, bellX, bellZ, originX, originZ)
				&& field("pickedBuilding").getInt(screen) == -1
				&& dimension.equals(field("pickedNeighborMapDimension").get(screen))
				&& field("neighbor").getInt(screen) == 0,
				"a plain click on a valid discovered pin selects its exact linked relationship directly");
			field("pickedNeighborMapDimension").set(screen, null); field("pickedNeighborMapBell").setLong(screen, 0);
			field("neighbor").setInt(screen, -1);
			BoardScreen.quota = List.of("@o|" + originX + "|" + originZ,
				"@b|school|Überlappende Schule|" + bellX + "|64|" + bellZ + "|0");

			check(clickMapWorld(screen, bellX, bellZ, originX, originZ)
				&& field("pickedBuilding").getInt(screen) == 0
				&& ((Button)field("demolishButton").get(screen)).active && ((Button)field("moveButton").get(screen)).active,
				"a first click on an overlapping neighbor pin keeps the existing building selection and actions");
		check(clickMapWorld(screen, bellX, bellZ, originX, originZ)
			&& field("pickedBuilding").getInt(screen) == -1
			&& dimension.equals(field("pickedNeighborMapDimension").get(screen))
			&& field("pickedNeighborMapBell").getLong(screen) == bell.asLong()
			&& field("neighbor").getInt(screen) == 0
			&& !((Button)field("demolishButton").get(screen)).active && !((Button)field("moveButton").get(screen)).active,
			"a repeated overlap click selects the exact linked neighbor and clears building-only actions");
		check("Beziehung: 3 (Handelspartner)".equals(BoardScreen.neighborMapDetailLines(
			BoardScreen.neighborMapPins(fixture, dimension, originX, originZ).get(0)).get(0)),
			"selected map pin provides its relationship details in the map panel");

		float scale = field("uiScale").getFloat(screen);
		int left = field("left").getInt(screen), top = field("top").getInt(screen), view = field("MAP_VIEW").getInt(null);
		double outsideX = (left + 14 + view + 3) * scale, outsideY = (top + 54 + 12) * scale;
		check(!screen.mouseClicked(new MouseButtonEvent(outsideX, outsideY, new MouseButtonInfo(0, 0)), false)
			&& dimension.equals(field("pickedNeighborMapDimension").get(screen)),
			"a click beyond the map viewport cannot change the selected neighbor pin");
		check(clickMapWorld(screen, bellX, bellZ, originX, originZ)
			&& field("pickedBuilding").getInt(screen) == 0
			&& ((Button)field("demolishButton").get(screen)).active && ((Button)field("moveButton").get(screen)).active,
			"building selection and its actions remain available after selecting an overlapping neighbor pin");

		String foreignDimension = dimension.equals("minecraft:the_nether") ? "minecraft:overworld" : "minecraft:the_nether";
		BoardData foreignFixture = new BoardData(fixture.board(), fixture.village(), size, cellSize, fixture.cells(), fixture.marks(),
			fixture.bars(), fixture.residents(), fixture.details(), fixture.expeditions(),
			List.of(neighborMapRow("Fremddorf", "2 (Handelspartner)", foreignDimension, bell)), fixture.partners(), fixture.journal());
		dataField.set(screen, foreignFixture);
		int foreignClickCol = col + 2, foreignClickRow = row + 2;
		check(clickMapCell(screen, foreignClickCol, foreignClickRow)
			&& field("pickedNeighborMapDimension").get(screen) == null,
			"a foreign-dimension bell at the same map coordinates cannot be selected as a pin");

		dataField.set(screen, fixture);
		BoardScreen.quota = List.of("@o|" + originX + "|" + originZ);
		field("pickedNeighborMapDimension").set(screen, null); field("pickedNeighborMapBell").setLong(screen, 0);
		field("pickedBuilding").setInt(screen, -1); field("zoneTool").setBoolean(screen, true);
		field("zoneKind").setInt(screen, 0); field("drawingZone").setBoolean(screen, false);
		check(clickMapWorld(screen, bellX, bellZ, originX, originZ) && !field("drawingZone").getBoolean(screen)
			&& field("zoneTool").getBoolean(screen) && dimension.equals(field("pickedNeighborMapDimension").get(screen))
			&& field("neighbor").getInt(screen) == 0,
			"an armed zone tool yields a neighbor-pin click to relationship selection without starting a drag");
		} finally {
			dataField.set(screen, originalData);
			BoardScreen.quota = originalQuota;
			field("tab").setInt(screen, tab); field("mapCell").setInt(screen, mapCell);
			field("mapPanX").setInt(screen, panX); field("mapPanY").setInt(screen, panY);
			field("pickedBuilding").setInt(screen, pickedBuilding); field("pickedMapMarker").setInt(screen, pickedMapMarker);
			field("pickedMapMarkerCode").setInt(screen, pickedMapMarkerCode);
			field("pickedNeighborMapBell").setLong(screen, pickedNeighborBell);
			field("pickedNeighborMapDimension").set(screen, pickedNeighborDimension);
			field("neighbor").setInt(screen, neighbor); field("neighborNotice").set(screen, neighborNotice);
			field("movePending").setBoolean(screen, movePending); field("zoneTool").setBoolean(screen, zoneTool);
			field("drawingZone").setBoolean(screen, drawingZone); field("zoneKind").setInt(screen, zoneKind);
			field("drawingZoneKind").setInt(screen, drawingZoneKind);
			field("zoneStartX").setInt(screen, zoneStartX); field("zoneStartZ").setInt(screen, zoneStartZ);
			field("zoneEndX").setInt(screen, zoneEndX); field("zoneEndZ").setInt(screen, zoneEndZ);
			field("zoneClipped").setBoolean(screen, zoneClipped); field("zoneNotice").set(screen, zoneNotice);
			((Button)field("zoneButton").get(screen)).setMessage(Component.literal(zoneTool
				? BoardScreen.zoneButtonText(zoneKind) : "Zone zeichnen"));
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility"); visibility.setAccessible(true); visibility.invoke(screen);
		}
	}

	private static boolean clickMapWorld(BoardScreen screen, int worldX, int worldZ, int originX, int originZ) throws ReflectiveOperationException {
		float scale = field("uiScale").getFloat(screen);
		int left = field("left").getInt(screen), top = field("top").getInt(screen);
		int cell = field("mapCell").getInt(screen), panX = field("mapPanX").getInt(screen), panY = field("mapPanY").getInt(screen);
		int worldCell = ((BoardData)field("data").get(screen)).cellSize();
		double x = (left + 14 + panX + BoardScreen.worldToMapPixelOffset(worldX, originX, worldCell, cell)) * scale;
		double y = (top + 54 + panY + BoardScreen.worldToMapPixelOffset(worldZ, originZ, worldCell, cell)) * scale;
		return screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
	}

	private static String neighborMapRow(String name, String relationship, String dimension, net.minecraft.core.BlockPos bell) {
		return name + "|40 Blöcke Norden|Norden|12|" + relationship
			+ "|Straße vorhanden|1|Handel bereit|keine Zustimmung|keine Bahn|1|" + dimension + "|" + bell.asLong();
	}

	/** Exercises the exact resident report list consumed by the Talk renderer, including long chest/material lists. */
	private static void residentTalk(BoardScreen screen) throws ReflectiveOperationException {
		boolean tabButtonsFit = BoardScreen.tabCount() == 9;
		int previousTabRight = -1;
		for (int i = 0; i < BoardScreen.tabCount(); i++) {
			int[] bounds = BoardScreen.tabButtonBounds(10, 20, i);
			tabButtonsFit &= bounds[1] == 44 && bounds[3] == 18 && bounds[0] >= 18 && bounds[0] + bounds[2] <= 10 + 460 - 8
				&& (previousTabRight < 0 || bounds[0] >= previousTabRight + 4);
			previousTabRight = bounds[0] + bounds[2];
		}
		check(tabButtonsFit, "nine board tabs, including Talk, remain separated inside the existing panel width");
		BoardData originalData = (BoardData)field("data").get(screen);
		int originalTab = field("tab").getInt(screen);
		String originalResident = (String)field("talkResidentId").get(screen);
		int originalTalkRequestId = field("talkRequestId").getInt(screen);
		java.util.UUID residentId = java.util.UUID.fromString("c2cb57db-64c5-4496-b55d-2c1f8db812ef");
		List<String> chest = new java.util.ArrayList<>();
		for (int slot = 1; slot <= 24; slot++) {
			chest.add(slot + "#minecraft:oak_planks#" + slot);
		}
		List<String> residents = List.of("Marta|Bauer|holt Baustoffe|82|ja|" + residentId + "|minecraft:farmer|false|");
		List<String> materials = List.of(
			"Baustelle LIVE: Wohnhaus – Schritt 4/20, 20 %",
			"  Noch zu liefern: Eichenholzbretter ×24",
			"  Davon fehlen im Dorf: Eichenholzbretter ×8 · minecraft:oak_planks",
			"  Davon fehlen im Dorf: Bruchstein ×3 · minecraft:cobblestone",
			"Dorfkiste: 12 Smaragde"
		);
		String report = residentId + "|Marta|Bauer|holt Baustoffe|100 64 -20|110 66 -19|37|gut (82%)|Inhalt|" + String.join(";", chest);
		List<String> talkRows = List.of(report);
		try {
			field("data").set(screen, new BoardData(originalData.board(), originalData.village(), originalData.size(), originalData.cellSize(),
				originalData.cells(), originalData.marks(), originalData.bars(), residents, materials, originalData.expeditions(),
				originalData.neighbors(), originalData.partners(), originalData.journal(), 41, talkRows));
			field("talkResidentId").set(screen, residentId.toString());
			field("talkRequestId").setInt(screen, 41);
			field("tab").setInt(screen, 8);
			List<String> shown = screen.selectedResidentTalkDisplayLines();
			check(shown.contains("Bewohner: Marta") && shown.contains("Beruf: Bauer")
				&& shown.contains("Aktueller Auftrag: holt Baustoffe")
				&& shown.contains("Standort: 100 64 -20") && shown.contains("Wegziel: 110 66 -19")
				&& shown.contains("Hunger: 37%") && shown.contains("Stimmung: gut (82%)"),
				"the Talk renderer shows selected-resident job, current task, position, target, hunger, and mood from the matching server report");
			check(shown.contains("  Fach 1: 1 × " + BoardScreen.localizedMaterialName("minecraft:oak_planks"))
				&& shown.contains("  Fach 24: 24 × " + BoardScreen.localizedMaterialName("minecraft:oak_planks"))
				&& shown.stream().noneMatch(line -> line.contains("minecraft:oak_planks")),
				"the selected owner's private chest renders every occupied slot with localized item names and counts, without raw IDs");
			check(shown.contains("  Noch zu liefern: Eichenholzbretter ×24")
				&& shown.contains("  Davon fehlen im Dorf: Eichenholzbretter ×8")
				&& shown.contains("  Davon fehlen im Dorf: Bruchstein ×3")
				&& shown.stream().noneMatch(line -> line.contains("Dorfkiste")),
				"the Talk panel retains all active-project delivery and shortage rows while omitting unrelated village details");
			Font talkFont = (Font)inheritedField(screen, "font");
			int wrappedTalkLines = VillagerPlusClient.wrappedBoardLineCount(talkFont, shown, 432);
			int visibleTalkLines = BoardScreen.listVisibleRows(1);
			int[] talkThumbTop = VillagerPlusClient.talkScrollbarBounds(10, 20, wrappedTalkLines, 0);
			int[] talkThumbMiddle = VillagerPlusClient.talkScrollbarBounds(10, 20, wrappedTalkLines,
				(wrappedTalkLines - visibleTalkLines) / 2);
			int[] talkThumbBottom = VillagerPlusClient.talkScrollbarBounds(10, 20, wrappedTalkLines,
				wrappedTalkLines - visibleTalkLines);
			check(wrappedTalkLines > visibleTalkLines && talkThumbTop.length == 4 && talkThumbMiddle.length == 4
				&& talkThumbBottom.length == 4 && talkThumbTop[0] >= 10 + 12 + 432 + 4
				&& talkThumbTop[1] == 20 + 52 && talkThumbTop[1] < talkThumbMiddle[1]
				&& talkThumbMiddle[1] < talkThumbBottom[1]
				&& talkThumbBottom[1] + talkThumbBottom[2] <= talkThumbTop[1] + talkThumbTop[3]
				&& talkThumbTop[1] + talkThumbTop[3] <= 20 + BoardScreen.listFooterYOffset(1),
				"the Talk overflow thumb follows wrapped scroll position inside the content margin and clears the footer hint and controls");
			String emptyChestReport = residentId + "|Marta|Bauer|ruht|100 64 -20|kein aktives Wegziel|0|gut (82%)|leer|";
			List<String> emptyChestLines = BoardScreen.residentTalkDisplayLines(List.of(emptyChestReport), residentId.toString(), List.of(), id -> id);
			check(emptyChestLines.contains("Private Truhe: leer") && emptyChestLines.contains("  keine Gegenstände eingelagert")
				&& VillagerPlusClient.talkScrollbarBounds(10, 20,
					VillagerPlusClient.wrappedBoardLineCount(talkFont, emptyChestLines, 432), 0).length == 0,
				"an empty private chest is stated explicitly and a short report does not show a misleading scrollbar");
			String longTask = "bringt fehlende Baustoffe aus dem öffentlichen Lager zur aktiven Dorfbaustelle ".repeat(3);
			String longReport = residentId + "|Marta|Bauer|" + longTask + "|100 64 -20|110 66 -19|37|gut (82%)|leer|";
			List<String> longTalkLines = BoardScreen.residentTalkDisplayLines(List.of(longReport), residentId.toString(), List.of(), id -> id);
			check(VillagerPlusClient.wrappedBoardLineCount(talkFont, longTalkLines, 432) > longTalkLines.size(),
				"a long current task wraps into the same scroll layout instead of running into the right edge");
			check(shown.size() > BoardScreen.listVisibleRows(1)
				&& BoardScreen.listVisibleRows(1) * 11 + 52 <= BoardScreen.listFooterYOffset(1)
				&& shown.getLast().equals("  Nächster Schritt: Fehlendes Material im Dorf beschaffen"),
				"long resident and construction reports remain in the scroll source and clear its footer without truncating the final row");
			String otherId = "8ad5683c-6c46-4e7a-aa59-135e514e22b0";
			BoardData lateOtherResident = new BoardData(originalData.board(), originalData.village(), originalData.size(), originalData.cellSize(),
				originalData.cells(), originalData.marks(), originalData.bars(), residents, materials, originalData.expeditions(),
				originalData.neighbors(), originalData.partners(), originalData.journal(), 40,
				List.of(otherId + "|Other|Schmied|arbeitet|101 64 -20|102 64 -20|10|schlecht (15%)|Inhalt|1#minecraft:diamond#64#Diamant"));
			Object selectedData = field("data").get(screen);
			screen.update(lateOtherResident);
			check(field("data").get(screen) == selectedData && screen.selectedResidentTalkDisplayLines().contains("Bewohner: Marta"),
				"a late response for an older UUID/request id cannot replace or reveal another resident's private chest");
			check(BoardScreen.residentTalkDisplayLines(talkRows, "00000000-0000-0000-0000-000000000000", materials, id -> id)
				.getFirst().startsWith("Bewohnerdaten sind nicht mehr verfügbar")
				&& residents.getFirst().contains(residentId.toString())
				&& new de.villagerplus.net.BoardRequest(originalData.board(), residentId.toString(), 9).talkRequestId() == 9
				&& new de.villagerplus.net.BoardRequest(originalData.board()).talkResidentId().isBlank()
				&& new de.villagerplus.net.BoardRequest(originalData.board(), "not-a-uuid", 9).talkRequestId() == 0
				&& new BoardData(originalData.board(), originalData.village(), originalData.size(), originalData.cellSize(), originalData.cells(),
					originalData.marks(), originalData.bars(), residents, materials, originalData.expeditions(), originalData.neighbors(),
					originalData.partners(), originalData.journal(), 9, List.of(report, report)).residentTalk().size() == 1
				&& BoardScreen.nextTalkRequestId(Integer.MAX_VALUE) == 1,
				"UUID-bound Talk requests reject malformed identities, cap payloads at one report, ignore missing residents, and wrap IDs safely");
		} finally {
			field("data").set(screen, originalData);
			field("tab").setInt(screen, originalTab);
			field("talkResidentId").set(screen, originalResident);
			field("talkRequestId").setInt(screen, originalTalkRequestId);
		}
	}

	private static void caravanTraderProportions() {
		check(CaravanTraderModel.HAT_BRIM_WIDTH <= CaravanTraderModel.HEAD_WIDTH,
			"trader hat brim stays within the villager head width instead of making the head silhouette oversized");
	}

	private static void constructionText() {
		List<String> resolved = new java.util.ArrayList<>();
		List<String> details = List.of(
			"Baustelle LIVE: Wohnhaus – Schritt 4/20, 20 %",
			"  Ort: 0 64 0",
			"  Noch zu liefern: Birch Planks ×12000",
			"  Davon fehlen im Dorf: Birch Planks ×12345 · minecraft:birch_planks",
			"  Wartet auf Baumaterial.",
			"  Kein Baufortschritt seit 7315 s"
		);
		List<String> shown = BoardScreen.constructionDisplayLines(details, id -> {
			resolved.add(id);
			return "Birkenholzbretter";
		});
		check(resolved.contains("minecraft:birch_planks")
			&& shown.stream().anyMatch(line -> line.equals("  Noch zu liefern: Birkenholzbretter ×12.000"))
			&& shown.stream().anyMatch(line -> line.equals("  Davon fehlen im Dorf: Birkenholzbretter ×12.345"))
			&& shown.stream().noneMatch(line -> line.contains("minecraft:") || line.contains("7315 s")),
			"construction details localize registry material names, group quantities in German, and hide raw ids and seconds");
		check(shown.contains("  Stillstand: ca. 2 Std. 2 Min.")
			&& shown.getLast().equals("  Nächster Schritt: Fehlendes Material im Dorf beschaffen"),
			"construction status uses a compact approximate duration and derives an action from the actual village shortage");

		List<String> legacyStall = BoardScreen.constructionDisplayLines(List.of(
			"Baustelle LIVE: Brunnen – Schritt 8/20, 40 %",
			"  Kein Baufortschritt seit 2 Std. 1 Min. 55 Sek."
		), id -> id);
		List<String> builderWait = BoardScreen.constructionDisplayLines(List.of(
			"Baustelle LIVE: Wohnhaus – Schritt 8/20, 40 %",
			"  Wartet auf einen verfügbaren Baumeister."
		), id -> id);
		List<String> materialWait = BoardScreen.constructionDisplayLines(List.of(
			"Baustelle LIVE: Wohnhaus – Schritt 8/20, 40 %",
			"  Wartet auf Baumaterial."
		), id -> id);
		List<String> processingWait = BoardScreen.constructionDisplayLines(List.of(
			"Baustelle LIVE: Wohnhaus – Schritt 8/20, 40 %",
			"  Wartet: planbezogene Ofenverarbeitung; eine registrierte Dorfküche fehlt"
		), id -> id);
		List<String> progressing = BoardScreen.constructionDisplayLines(List.of(
			"Baustelle LIVE: Wohnhaus – Schritt 8/20, 40 %",
			"  Bauphase 2/4: Wände – 8/20 Schritte (40 %)"
		), id -> id);
		check(legacyStall.contains("  Stillstand: ca. 2 Std. 1 Min.")
			&& builderWait.getLast().endsWith("Baumeister verfügbar machen")
			&& materialWait.getLast().endsWith("Vorhandenes Lager-Material zur Baustelle bringen")
			&& processingWait.getLast().endsWith("Wartegrund prüfen")
			&& progressing.stream().noneMatch(line -> line.startsWith("  Nächster Schritt:")),
			"builder, supplied-material and opaque processing waits receive accurate next actions while progressing sites remain unlabelled");
		check(BoardScreen.detailStatusColor("Nächster Schritt:") == 0xFF3C6B39
			&& BoardScreen.listVisibleRows(0) * 11 + 52 <= BoardScreen.listFooterYOffset(0)
			&& BoardScreen.listVisibleRows(1) * 11 + 52 <= BoardScreen.listFooterYOffset(1),
			"the added status row is styled distinctly and the existing scroll viewport stays clear of its footer");
	}

	private static void networkActions(BoardScreen screen) throws ReflectiveOperationException {
		net.minecraft.core.BlockPos board = new net.minecraft.core.BlockPos(2, 64, 3);
		net.minecraft.core.BlockPos bell = new net.minecraft.core.BlockPos(-120, 70, 305);
		String[] linked = {"Eichenruh", "88 Blöcke Osten", "Osten", "3", "42", "Straße", "1",
			"keine Tausche", "Zustimmung offen", "keine", "1", "minecraft:overworld", Long.toString(bell.asLong())};
		String[] unloaded = linked.clone(); unloaded[6] = "0";
		String[] actions = {"swap", "route", "invite", "greet"};
		boolean allActionsTargetSelectedLink = true;
		for (int i = 0; i < actions.length; i++) {
			int requestId = 40 + i;
			var payload = BoardScreen.neighborActionPayload(board, actions[i], 2, linked, requestId);
			allActionsTargetSelectedLink &= payload != null && payload.action().equals(actions[i]) && payload.index() == 2
				&& payload.targetDimension().equals("minecraft:overworld") && payload.targetBell().equals(bell)
				&& payload.requestId() == requestId;
		}
		check(allActionsTargetSelectedLink, "all four visible action buttons target only the selected real neighbor and carry a matching request id");
		List<String> historyOnly = BoardScreen.relationshipRows(List.of(), List.of("Silberbach|Erze & Werkzeuge|13|vor 8 Tagen"));
		check(BoardScreen.neighborSelectionPrompt(List.of()).contains("Dorfkarten")
			&& BoardScreen.neighborSelectionPrompt(historyOnly).contains("Handelsverlauf, keine Dorfverbindung")
			&& BoardScreen.neighborSelectionPrompt(List.of(String.join("|", linked))).contains("verfügbare Aktionen")
			&& !BoardScreen.networkActionEnabled(null, false, false)
			&& !BoardScreen.networkActionEnabled(historyOnly.getFirst().split("\\|", -1), false, false)
			&& !BoardScreen.networkActionEnabled(null, true, false)
			&& BoardScreen.networkActionEnabled(linked, true, false)
			&& !BoardScreen.networkActionEnabled(linked, true, true)
			&& !BoardScreen.neighborActionEnabled("swap", null, "minecraft:overworld", false)
			&& !BoardScreen.neighborActionEnabled("swap", unloaded, "minecraft:overworld", false)
			&& BoardScreen.neighborActionEnabled("route", unloaded, "minecraft:overworld", false)
			&& BoardScreen.neighborActionEnabled("greet", unloaded, "minecraft:overworld", false)
			&& !BoardScreen.neighborActionEnabled("approve", linked, "minecraft:the_nether", false)
			&& BoardScreen.neighborActionFailure("approve", linked, "minecraft:the_nether").contains("derselben Dimension"),
			"target actions need a selected real link; unloaded links enable only queueable same-dimension route/greeting actions");
		String[] visibleNeighbor = java.util.Arrays.copyOf(linked, 16);
		visibleNeighbor[4] = "42 (Bündnispartner) · besucht vor 2 Tagen";
		visibleNeighbor[5] = "fertig";
		visibleNeighbor[7] = "Weizen +4, Eisen -2";
		visibleNeighbor[8] = "dein Ja / Nachbar unbekannt";
		visibleNeighbor[9] = "im Bau 35 %";
		visibleNeighbor[14] = "route_an,greet";
		visibleNeighbor[15] = "an";
		List<String> visibleLines = BoardScreen.neighborDisplayLines(visibleNeighbor,
			net.minecraft.client.Minecraft.getInstance().font, 436);
		check(visibleLines.size() == 4 && visibleLines.get(0).contains("Beziehung 42 (Bündnispartner)")
			&& visibleLines.get(1).contains("Straße: fertig") && visibleLines.get(1).contains("Bahn: im Bau 35 %")
			&& visibleLines.get(2).contains("Handelsbilanz: Weizen +4, Eisen -2")
			&& visibleLines.get(3).contains("Projektzustimmung: du: Ja / Nachbar unbekannt"),
			"a linked neighbor keeps its relationship, road/rail, trade balance, and project vote visible even with queued actions");
		clickNetworkActionWidgets(screen, board, linked, actions);
		historicalRelationshipSelection(screen, board);
		check(BoardScreen.neighborRowAt(30, 131, 10, 20, 0, 4) == 0
			&& BoardScreen.neighborRowAt(30, 167, 10, 20, 0, 4) == 1,
			"clicking a visible neighbor row selects the matching row, including row-boundary clicks");
		check(BoardScreen.neighborActionFailure("swap", null).contains("Wähle unten")
			&& BoardScreen.neighborActionPayload(board, "swap", 0, null, 41) == null,
			"an action without a selected target is rejected with an instruction to select a listed place");
		String[] stale = linked.clone(); stale[12] = "not-a-position";
		String[] unlinked = linked.clone(); unlinked[10] = "0";
		check(BoardScreen.neighborActionFailure("greet", stale) != null
			&& BoardScreen.neighborActionFailure("invite", unloaded) != null
			&& BoardScreen.neighborActionFailure("swap", unloaded) != null
			&& BoardScreen.neighborActionFailure("route", unloaded) == null
			&& BoardScreen.neighborActionFailure("greet", unloaded) == null
			&& BoardScreen.neighborActionFailure("swap", unlinked).contains("Rückverbindung"),
			"stale or one-sided targets fail, while route and greeting may be durably queued for an unloaded bell");
		check(BoardScreen.relationshipAvailabilityNotice(unloaded).startsWith("⏳ ")
			&& BoardScreen.relationshipAvailabilityNotice(unloaded).contains("dauerhaft vorgemerkt")
			&& BoardScreen.relationshipAvailabilityNotice(unloaded).contains("Tausch und Gäste"),
			"the neighbor row explains which unloaded-target actions can queue and which require a loaded counterpart");
		String[] pendingActions = new String[15];
		System.arraycopy(linked, 0, pendingActions, 0, linked.length);
		pendingActions[13] = ""; pendingActions[14] = "route_an,greet";
		check(BoardScreen.relationshipAvailabilityNotice(pendingActions).contains("Route an und Gruß vorgemerkt")
			&& BoardScreen.neighborActionFailure("greet", pendingActions).contains("bereits vorgemerkt")
			&& BoardScreen.neighborActionFailure("route", pendingActions) == null,
			"persistent route/greeting requests remain visible after refresh and do not enqueue duplicate greetings");
		check(de.villagerplus.net.Networking.networkActionAdmissionFailure(false, true, true, true, true) == null
			&& de.villagerplus.net.Networking.networkActionAdmissionFailure(true, true, true, true, true) != null
			&& de.villagerplus.net.Networking.networkActionAdmissionFailure(false, false, false, false, false).contains("nicht geladen")
			&& de.villagerplus.net.Networking.networkActionAdmissionFailure(false, true, true, false, true).contains("zu weit")
			&& de.villagerplus.net.Networking.networkActionAdmissionFailure(false, true, true, true, false).contains("keine Dorftafel"),
			"the server action protocol admits only a reachable real board and returns a specific refusal otherwise");
		var result = new de.villagerplus.net.NetworkActionResult(board, 42, true, "Gruß übermittelt.");
		check(BoardScreen.matchesNetworkActionResult(board, 42, result)
			&& !BoardScreen.matchesNetworkActionResult(board, 43, result)
			&& !BoardScreen.matchesNetworkActionResult(board.east(), 42, result),
			"only the current board and request accept a server result, so late responses cannot mark retries successful");
		check(BoardScreen.networkActionNotice(true, "Handel abgeschlossen.").startsWith("✓ ")
			&& BoardScreen.networkActionNotice(false, "Kein Überschuss.").startsWith("✕ ")
			&& BoardScreen.nextNetworkActionRequestId(Integer.MAX_VALUE) == 1,
			"confirmed success/failure have distinct UI states and request ids wrap safely");
		String detailedZoneReply = "✓ Feldzone gespeichert: X -100 bis -77, Z 296 bis 311.";
		var detailedLines = BoardScreen.wrapNoticeLines(detailedZoneReply, net.minecraft.client.Minecraft.getInstance().font, 110, 4);
		check(detailedLines.size() > 1 && String.join(" ", detailedLines).equals(detailedZoneReply)
			&& detailedLines.stream().allMatch(line -> net.minecraft.client.Minecraft.getInstance().font.width(line) <= 110),
			"multi-line map feedback preserves the complete server result and exact saved coordinates");
		int pendingId = field("pendingNetworkActionRequestId").getInt(screen);
		int pendingTicks = field("pendingNetworkActionTicks").getInt(screen);
		String pendingAction = (String)field("pendingNetworkAction").get(screen);
		String notice = (String)field("neighborNotice").get(screen);
		try {
			net.minecraft.core.BlockPos activeBoard = ((BoardData)field("data").get(screen)).board();
			field("pendingNetworkActionRequestId").setInt(screen, 42);
			field("pendingNetworkActionTicks").setInt(screen, 9);
			field("pendingNetworkAction").set(screen, "greet");
			check(screen.acceptNetworkActionResult(new de.villagerplus.net.NetworkActionResult(activeBoard, 42, true, "Gruß bestätigt."))
				&& field("neighborNotice").get(screen).equals("✓ Gruß bestätigt.")
				&& field("pendingNetworkAction").get(screen) == null,
				"the active screen shows success only after its matching server acknowledgment");
			field("pendingNetworkActionRequestId").setInt(screen, 43);
			field("pendingNetworkAction").set(screen, "swap");
			check(!screen.acceptNetworkActionResult(new de.villagerplus.net.NetworkActionResult(activeBoard, 42, true, "verspätet"))
				&& field("pendingNetworkAction").get(screen).equals("swap"),
				"a late result cannot complete a later action request");
		} finally {
			field("pendingNetworkActionRequestId").setInt(screen, pendingId);
			field("pendingNetworkActionTicks").setInt(screen, pendingTicks);
			field("pendingNetworkAction").set(screen, pendingAction);
			field("neighborNotice").set(screen, notice);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility"); visibility.setAccessible(true); visibility.invoke(screen);
		}
	}

	/** Historical partners/guest origins stay selectable, but cannot masquerade as real action targets. */
	private static void historicalRelationshipSelection(BoardScreen screen, net.minecraft.core.BlockPos board)
		throws ReflectiveOperationException {
		BoardData originalData = (BoardData)field("data").get(screen);
		int originalTab = field("tab").getInt(screen), originalNeighbor = field("neighbor").getInt(screen);
		int originalScroll = field("scroll").getInt(screen);
		int originalPendingId = field("pendingNetworkActionRequestId").getInt(screen);
		int originalPendingTicks = field("pendingNetworkActionTicks").getInt(screen);
		String originalPendingAction = (String)field("pendingNetworkAction").get(screen);
		String originalNotice = (String)field("neighborNotice").get(screen);
		float originalScale = field("uiScale").getFloat(screen);
		var senderField = field("networkActionSender");
		Object originalSender = senderField.get(screen);
		List<de.villagerplus.net.NetworkAction> sent = new java.util.ArrayList<>();
		try {
		List<String> contacts = List.of("Silberbach|Erze & Werkzeuge|13|vor 8 Tagen",
			"@guest|Gast aus Mondmarkt|2|anwesend", "@guest|Gast aus Silberbach|1|anwesend",
			"@guest|Gast aus Nordheim|2|anwesend", "@guest|Gast ohne Namen|1|anwesend");
		String[] nordheim = neighborMapRow("Nordheim", "3 (Handelspartner)", "minecraft:overworld",
			new net.minecraft.core.BlockPos(1200, 64, 2400)).split("\\|", -1);
		field("data").set(screen, new BoardData(board, originalData.village(), originalData.size(), originalData.cellSize(),
			originalData.cells(), originalData.marks(), originalData.bars(), originalData.residents(), originalData.details(),
			originalData.expeditions(), List.of(String.join("|", nordheim)), contacts, originalData.journal()));
		List<String> rows = BoardScreen.relationshipRows(List.of(String.join("|", nordheim)), contacts);
		String[] shownNordheim = rows.getFirst().split("\\|", -1);
		String[] silberbach = rows.get(1).split("\\|", -1);
		String[] mondmarkt = rows.get(2).split("\\|", -1);
		List<String> guestLabels = BoardScreen.guestPresenceLabels(List.of(String.join("|", nordheim)), contacts);
		String[] unloadedNordheim = nordheim.clone();
		unloadedNordheim[6] = "0";
		check(rows.size() == 3 && shownNordheim[0].equals("Nordheim") && !BoardScreen.historicalRelationship(shownNordheim)
			&& silberbach[0].equals("Silberbach") && silberbach[1].contains("Erze & Werkzeuge")
			&& BoardScreen.historicalRelationship(silberbach) && mondmarkt[0].equals("Mondmarkt")
			&& "Besuch".equals(mondmarkt[1]) && BoardScreen.historicalRelationship(mondmarkt)
			&& BoardScreen.relationshipRows(List.of(String.join("|", unloadedNordheim)), contacts).getFirst().startsWith("Nordheim|")
			&& BoardScreen.guestPresenceLabels(List.of(String.join("|", unloadedNordheim)), contacts).contains("Nordheim · 2 Gäste vor Ort")
			&& guestLabels.equals(List.of("Nordheim · 2 Gäste vor Ort", "Silberbach · 1 Gast vor Ort",
				"Mondmarkt · 2 Gäste (Besuchsort · keine Dorfverbindung)", "1 Gast ohne bekannten Herkunftsort"))
			&& BoardScreen.neighborIndex(rows, silberbach) == 1,
			"saved villages stay named while unloaded and named guest origins remain history-only with real counts");
			check(BoardScreen.neighborActionFailure("swap", silberbach).contains("Silberbach")
				&& BoardScreen.neighborActionFailure("swap", silberbach).contains("Dorfkarten")
				&& BoardScreen.neighborActionPayload(board, "swap", 0, silberbach) == null,
				"a history-only place explains that both real bell links are needed and never becomes a fake action target");
			field("tab").setInt(screen, 4);
			field("neighbor").setInt(screen, -1);
			field("scroll").setInt(screen, 0);
			field("pendingNetworkActionRequestId").setInt(screen, 0);
			field("pendingNetworkActionTicks").setInt(screen, 0);
			field("pendingNetworkAction").set(screen, null);
			field("neighborNotice").set(screen, null);
			senderField.set(screen, (java.util.function.Consumer<de.villagerplus.net.NetworkAction>)sent::add);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility");
			visibility.setAccessible(true);
			visibility.invoke(screen);
			float scale = originalScale;
			field("uiScale").setFloat(screen, scale);
			int left = field("left").getInt(screen), top = field("top").getInt(screen);
			boolean selectedVillage = screen.mouseClicked(new MouseButtonEvent((left + 24) * scale, (top + 120) * scale,
				new MouseButtonInfo(0, 0)), false);
			List<Button> buttons = (List<Button>)field("networkButtons").get(screen);
			Button approval = (Button)field("approveProjectButton").get(screen);
			Button mapCard = (Button)field("mapCardButton").get(screen);
			String villageNotice = (String)field("neighborNotice").get(screen);
			boolean selectedHistory = screen.mouseClicked(new MouseButtonEvent((left + 24) * scale, (top + 156) * scale,
				new MouseButtonInfo(0, 0)), false);
			String historyNotice = (String)field("neighborNotice").get(screen);
			check(selectedVillage && field("neighbor").getInt(screen) == 0 && villageNotice.startsWith("✓ Nordheim:")
				&& selectedHistory && field("neighbor").getInt(screen) == 1 && historyNotice.startsWith("✕ Silberbach:")
				&& buttons.stream().allMatch(button -> button.active) && !approval.active && mapCard.active && sent.isEmpty()
				&& historyNotice.contains("Dorfkarten"),
				"a history-only contact stays visible and explains the two-card linking requirement");
			boolean historyClicksExplainTheBlocker = true;
			for (int i = 0; i < buttons.size(); i++) {
				int[] actionBounds = BoardScreen.neighborActionBounds(left, top, i);
				boolean handled = screen.mouseClicked(new MouseButtonEvent(
					(actionBounds[0] + actionBounds[2] / 2.0) * scale,
					(actionBounds[1] + actionBounds[3] / 2.0) * scale,
					new MouseButtonInfo(0, 0)), false);
				historyClicksExplainTheBlocker &= handled && sent.isEmpty()
					&& field("pendingNetworkAction").get(screen) == null
					&& field("neighborNotice").get(screen).toString().startsWith("✕ Silberbach:")
					&& field("neighborNotice").get(screen).toString().contains("Dorfkarten");
			}
			int[] approvalBounds = BoardScreen.footerButtonBounds(left, top, 0);
			historyClicksExplainTheBlocker &= !screen.mouseClicked(new MouseButtonEvent(
				(approvalBounds[0] + approvalBounds[2] / 2.0) * scale,
				(approvalBounds[1] + approvalBounds[3] / 2.0) * scale,
				new MouseButtonInfo(0, 0)), false) && sent.isEmpty();
			check(historyClicksExplainTheBlocker,
				"clicking any action for a history-only contact gives visible linking feedback and sends no packet");
		} finally {
			field("data").set(screen, originalData);
			field("tab").setInt(screen, originalTab);
			field("neighbor").setInt(screen, originalNeighbor);
			field("scroll").setInt(screen, originalScroll);
			field("pendingNetworkActionRequestId").setInt(screen, originalPendingId);
			field("pendingNetworkActionTicks").setInt(screen, originalPendingTicks);
			field("pendingNetworkAction").set(screen, originalPendingAction);
			field("neighborNotice").set(screen, originalNotice);
			field("uiScale").setFloat(screen, originalScale);
			senderField.set(screen, originalSender);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility"); visibility.setAccessible(true); visibility.invoke(screen);
		}
	}

	/** Exercises the four real Screen button callbacks while intercepting their packet sender. */
	private static void clickNetworkActionWidgets(BoardScreen screen, net.minecraft.core.BlockPos board, String[] linked, String[] actions)
		throws ReflectiveOperationException {
		BoardData originalData = (BoardData)field("data").get(screen);
		int originalTab = field("tab").getInt(screen), originalNeighbor = field("neighbor").getInt(screen);
		int originalScroll = field("scroll").getInt(screen);
		int originalPendingId = field("pendingNetworkActionRequestId").getInt(screen);
		int originalPendingTicks = field("pendingNetworkActionTicks").getInt(screen);
		String originalPendingAction = (String)field("pendingNetworkAction").get(screen);
		String originalNotice = (String)field("neighborNotice").get(screen);
		float originalScale = field("uiScale").getFloat(screen);
		var senderField = field("networkActionSender");
		Object originalSender = senderField.get(screen);
		List<de.villagerplus.net.NetworkAction> sent = new java.util.ArrayList<>();
		try {
			field("data").set(screen, new BoardData(board, originalData.village(), originalData.size(), originalData.cellSize(),
				originalData.cells(), originalData.marks(), originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), List.of(String.join("|", linked)),
				List.of("Silberbach|Erze & Werkzeuge|13|vor 8 Tagen", "@guest|Gast aus Mondmarkt|2|anwesend"), originalData.journal()));
			field("tab").setInt(screen, 4);
			field("neighbor").setInt(screen, -1);
			field("scroll").setInt(screen, 0);
			field("pendingNetworkActionRequestId").setInt(screen, 0);
			field("pendingNetworkActionTicks").setInt(screen, 0);
			field("pendingNetworkAction").set(screen, null);
			field("neighborNotice").set(screen, null);
			senderField.set(screen, (java.util.function.Consumer<de.villagerplus.net.NetworkAction>)sent::add);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility");
			visibility.setAccessible(true);
			visibility.invoke(screen);
			List<Button> buttons = (List<Button>)field("networkButtons").get(screen);
			check(buttons.size() == actions.length, "the neighbor tab initializes all four action widgets");
			check(buttons.stream().allMatch(button -> button.visible && button.active)
				&& BoardScreen.neighborActionHitboxesDistinct(field("left").getInt(screen), field("top").getInt(screen)),
				"all four relationship actions stay clickable without a selected target so they can explain what is missing; hitboxes remain separate from each other and the footer");
			float secondScale = originalScale > 0.6F ? 0.5F
				: originalScale > 0.2F ? originalScale * 0.75F : Math.min(0.5F, originalScale + 0.1F);
			float[] clickScales = secondScale == originalScale
				? new float[]{originalScale} : new float[]{originalScale, secondScale};
			for (int scaleIndex = 0; scaleIndex < clickScales.length; scaleIndex++) {
				float scale = clickScales[scaleIndex];
				field("uiScale").setFloat(screen, scale);
				if (scaleIndex == 0) {
					for (int i = 0; i < actions.length; i++) {
						int[] bounds = BoardScreen.neighborActionBounds(field("left").getInt(screen), field("top").getInt(screen), i);
						boolean handled = screen.mouseClicked(new MouseButtonEvent(
							(bounds[0] + bounds[2] / 2.0) * scale,
							(bounds[1] + bounds[3] / 2.0) * scale,
							new MouseButtonInfo(0, 0)), false);
						check(handled && sent.isEmpty() && field("pendingNetworkAction").get(screen) == null
							&& buttons.stream().allMatch(button -> button.active)
							&& field("neighborNotice").get(screen).toString().startsWith("✕ "),
							"unselected " + actions[i] + " action explains the missing target and cannot send a targetless request");
					}
					int left = field("left").getInt(screen), top = field("top").getInt(screen);
					boolean rowClick = screen.mouseClicked(new MouseButtonEvent((left + 24) * scale, (top + 120) * scale,
						new MouseButtonInfo(0, 0)), false);
					check(rowClick && field("neighbor").getInt(screen) == 0
						&& field("neighborNotice").get(screen).toString().startsWith("✓ Eichenruh:")
						&& buttons.stream().allMatch(button -> button.visible && button.active),
						"the rendered neighbor-row hitbox selects the live partner, shows its reciprocal status, and enables the actions");
				}
				for (int i = 0; i < actions.length; i++) {
					int[] bounds = BoardScreen.neighborActionBounds(field("left").getInt(screen), field("top").getInt(screen), i);
					double x = (bounds[0] + bounds[2] / 2.0) * scale;
					double y = (bounds[1] + bounds[3] / 2.0) * scale;
					boolean handled = screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
					var payload = sent.isEmpty() ? null : sent.get(sent.size() - 1);
					String pendingNotice = (String)field("neighborNotice").get(screen);
					check(handled && sent.size() == scaleIndex * actions.length + i + 1 && payload != null && payload.action().equals(actions[i])
						&& payload.board().equals(board) && payload.index() == 0
						&& payload.targetDimension().equals(linked[11])
						&& payload.targetBell().equals(net.minecraft.core.BlockPos.of(Long.parseLong(linked[12])))
						&& payload.requestId() > 0 && actions[i].equals(field("pendingNetworkAction").get(screen))
						&& pendingNotice.startsWith("⏳ ") && pendingNotice.contains("Eichenruh")
						&& buttons.stream().noneMatch(button -> button.active),
						"scaled click on the " + actions[i] + " widget sends the selected link and shows pending feedback");
					boolean success = ((scaleIndex + i) & 1) == 0;
					String response = success ? "ack" : "Kein Überschuss.";
					check(screen.acceptNetworkActionResult(new de.villagerplus.net.NetworkActionResult(board, payload.requestId(), success, response))
						&& field("neighborNotice").get(screen).equals((success ? "✓ " : "✕ ") + response)
						&& field("pendingNetworkAction").get(screen) == null
						&& buttons.stream().allMatch(button -> button.active),
						"the " + actions[i] + " widget displays its matching server result and unlocks");
				}
			}
			Button approval = (Button)field("approveProjectButton").get(screen);
			float callbackScale = field("uiScale").getFloat(screen);
			int[] approvalBounds = BoardScreen.footerButtonBounds(field("left").getInt(screen), field("top").getInt(screen), 0);
			boolean approvalWasEnabled = approval.active;
			int beforeApproval = sent.size();
			boolean approvalClick = screen.mouseClicked(new MouseButtonEvent(
				(approvalBounds[0] + approvalBounds[2] / 2.0) * callbackScale,
				(approvalBounds[1] + approvalBounds[3] / 2.0) * callbackScale,
				new MouseButtonInfo(0, 0)), false);
			var approvalPayload = sent.size() > beforeApproval ? sent.getLast() : null;
			String approvalReply = "Deine Zustimmung für dieses Dorf ist gespeichert; auch das Nachbardorf muss separat zustimmen.";
			check(approvalClick && approvalWasEnabled && approvalPayload != null && approvalPayload.action().equals("approve")
				&& approvalPayload.index() == 0 && approvalPayload.targetDimension().equals(linked[11])
				&& approvalPayload.targetBell().equals(net.minecraft.core.BlockPos.of(Long.parseLong(linked[12])))
				&& screen.acceptNetworkActionResult(new de.villagerplus.net.NetworkActionResult(board,
					approvalPayload.requestId(), true, approvalReply))
				&& field("neighborNotice").get(screen).equals("✓ " + approvalReply),
				"the project approval button targets the selected bell and displays the server's persisted-vote confirmation");

			field("neighbor").setInt(screen, -1);
			visibility.invoke(screen);
			Button mapCard = (Button)field("mapCardButton").get(screen);
			boolean cardWasEnabled = mapCard.active;
			int[] cardBounds = BoardScreen.footerButtonBounds(field("left").getInt(screen), field("top").getInt(screen), 1);
			int beforeCard = sent.size();
			boolean cardClick = screen.mouseClicked(new MouseButtonEvent(
				(cardBounds[0] + cardBounds[2] / 2.0) * callbackScale,
				(cardBounds[1] + cardBounds[3] / 2.0) * callbackScale,
				new MouseButtonInfo(0, 0)), false);
			var cardPayload = sent.size() > beforeCard ? sent.getLast() : null;
			String cardReply = "Dorfkarte für Eichenruh ins Inventar gelegt.";
			check(cardClick && cardWasEnabled && cardPayload != null && cardPayload.action().equals("card")
				&& cardPayload.index() == -1 && screen.acceptNetworkActionResult(new de.villagerplus.net.NetworkActionResult(board,
					cardPayload.requestId(), true, cardReply))
				&& field("neighborNotice").get(screen).equals("✓ " + cardReply),
				"the village-card button executes without a selected neighbor and displays the server confirmation");
			field("neighbor").setInt(screen, 0);
			visibility.invoke(screen);
			senderField.set(screen, (java.util.function.Consumer<de.villagerplus.net.NetworkAction>)payload -> {
				throw new IllegalStateException("fixture send failure");
			});
			int[] failureBounds = BoardScreen.neighborActionBounds(field("left").getInt(screen), field("top").getInt(screen), 0);
			float failureScale = field("uiScale").getFloat(screen);
			int sendsBeforeFailure = sent.size();
			boolean handledFailure = screen.mouseClicked(new MouseButtonEvent(
				(failureBounds[0] + failureBounds[2] / 2.0) * failureScale,
				(failureBounds[1] + failureBounds[3] / 2.0) * failureScale,
				new MouseButtonInfo(0, 0)), false);
			check(handledFailure && sent.size() == sendsBeforeFailure
				&& field("pendingNetworkAction").get(screen) == null
				&& field("neighborNotice").get(screen).toString().startsWith("✕ Aktion konnte nicht gesendet")
				&& buttons.stream().allMatch(button -> button.active),
				"a local send failure is shown and releases the four action buttons without waiting for a server reply");
			String[] unloaded = linked.clone(); unloaded[6] = "0"; unloaded[10] = "0";
			field("data").set(screen, new BoardData(board, originalData.village(), originalData.size(), originalData.cellSize(),
				originalData.cells(), originalData.marks(), originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), List.of(String.join("|", unloaded)), originalData.partners(), originalData.journal()));
			field("neighbor").setInt(screen, -1);
			senderField.set(screen, (java.util.function.Consumer<de.villagerplus.net.NetworkAction>)sent::add);
			visibility.invoke(screen);
			float remoteScale = field("uiScale").getFloat(screen);
			int left = field("left").getInt(screen), top = field("top").getInt(screen);
			boolean rowSelected = screen.mouseClicked(new MouseButtonEvent((left + 24) * remoteScale,
				(top + 120) * remoteScale, new MouseButtonInfo(0, 0)), false);
			String remoteNotice = (String)field("neighborNotice").get(screen);
			check(rowSelected && remoteNotice.startsWith("⏳ ") && remoteNotice.contains("Tausch und Gäste")
				&& buttons.stream().allMatch(button -> button.active),
				"an unloaded real link keeps actions clickable and explains which ones need the counterpart to be loaded");
			for (int actionIndex : new int[]{1, 3}) {
				int[] bounds = BoardScreen.neighborActionBounds(left, top, actionIndex);
				int before = sent.size();
				boolean handled = screen.mouseClicked(new MouseButtonEvent((bounds[0] + bounds[2] / 2.0) * remoteScale,
					(bounds[1] + bounds[3] / 2.0) * remoteScale, new MouseButtonInfo(0, 0)), false);
				var payload = sent.size() > before ? sent.getLast() : null;
				String action = actions[actionIndex];
				String serverMessage = action.equals("route")
					? "Route lokal eingeschaltet; Partnerabgleich vorgemerkt."
					: "Gruß dauerhaft vorgemerkt; wartet auf Gegenort.";
				check(handled && payload != null && payload.action().equals(action)
					&& screen.acceptNetworkActionResult(new de.villagerplus.net.NetworkActionResult(board, payload.requestId(), true, serverMessage))
					&& field("neighborNotice").get(screen).equals("✓ " + serverMessage)
					&& field("pendingNetworkAction").get(screen) == null,
					"the " + action + " button returns the server's durable remote-queue acknowledgment instead of appearing inert");
			}
			boolean unloadedActionsExplained = true;
			for (int actionIndex : new int[]{0, 2}) {
				int[] bounds = BoardScreen.neighborActionBounds(left, top, actionIndex);
				boolean handled = screen.mouseClicked(new MouseButtonEvent((bounds[0] + bounds[2] / 2.0) * remoteScale,
					(bounds[1] + bounds[3] / 2.0) * remoteScale, new MouseButtonInfo(0, 0)), false);
				unloadedActionsExplained &= handled && sent.size() == sendsBeforeFailure + 2
					&& field("pendingNetworkAction").get(screen) == null && buttons.get(actionIndex).active
					&& field("neighborNotice").get(screen).toString().contains("nicht geladen");
			}
			check(unloadedActionsExplained,
				"barter and invitations explain the unloaded-counterpart requirement and cannot send an impossible request");
			String[] pending = linked.clone();
			String[] pendingRow = java.util.Arrays.copyOf(pending, 15);
			pendingRow[6] = "0"; pendingRow[10] = "0"; pendingRow[13] = ""; pendingRow[14] = "route_an,greet";
			screen.update(new BoardData(board, originalData.village(), originalData.size(), originalData.cellSize(),
				originalData.cells(), originalData.marks(), originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), List.of(String.join("|", pendingRow)), originalData.partners(), originalData.journal()));
			check(field("neighborNotice").get(screen).toString().contains("Route an und Gruß vorgemerkt")
				&& buttons.get(1).active && buttons.get(3).active,
				"the accepted remote actions keep their durable pending state visible after the live board refreshes");
			screen.update(new BoardData(board, originalData.village(), originalData.size(), originalData.cellSize(),
				originalData.cells(), originalData.marks(), originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), List.of(String.join("|", linked)), originalData.partners(), originalData.journal()));
			check(field("neighborNotice").get(screen).toString().startsWith("✓ Eichenruh:"),
				"once the reciprocal target is loaded and reconciled, its refreshed relationship status clears the pending notice");

			String[] scrolledTarget = linked.clone();
			scrolledTarget[0] = "Birkenhain";
			scrolledTarget[12] = Long.toString(new net.minecraft.core.BlockPos(240, 70, -180).asLong());
			String[] thirdTarget = linked.clone();
			thirdTarget[0] = "Tannengrund";
			thirdTarget[12] = Long.toString(new net.minecraft.core.BlockPos(360, 70, -260).asLong());
			String[] fourthTarget = linked.clone();
			fourthTarget[0] = "Felsenheim";
			fourthTarget[12] = Long.toString(new net.minecraft.core.BlockPos(480, 70, -340).asLong());
			field("data").set(screen, new BoardData(board, originalData.village(), originalData.size(), originalData.cellSize(),
				originalData.cells(), originalData.marks(), originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), List.of(String.join("|", linked), String.join("|", scrolledTarget),
					String.join("|", thirdTarget), String.join("|", fourthTarget)), originalData.partners(), originalData.journal()));
			field("neighbor").setInt(screen, -1);
			field("scroll").setInt(screen, 1);
			field("neighborNotice").set(screen, null);
			field("pendingNetworkActionRequestId").setInt(screen, 0);
			field("pendingNetworkActionTicks").setInt(screen, 0);
			field("pendingNetworkAction").set(screen, null);
			visibility.invoke(screen);
			float scrolledScale = field("uiScale").getFloat(screen);
			int scrolledLeft = field("left").getInt(screen), scrolledTop = field("top").getInt(screen);
			boolean scrolledRow = screen.mouseClicked(new MouseButtonEvent((scrolledLeft + 24) * scrolledScale,
				(scrolledTop + 120) * scrolledScale, new MouseButtonInfo(0, 0)), false);
			int[] inviteBounds = BoardScreen.neighborActionBounds(scrolledLeft, scrolledTop, 2);
			int beforeScrolledAction = sent.size();
			boolean scrolledAction = screen.mouseClicked(new MouseButtonEvent(
				(inviteBounds[0] + inviteBounds[2] / 2.0) * scrolledScale,
				(inviteBounds[1] + inviteBounds[3] / 2.0) * scrolledScale, new MouseButtonInfo(0, 0)), false);
			var scrolledPayload = sent.size() > beforeScrolledAction ? sent.getLast() : null;
			check(scrolledRow && field("neighbor").getInt(screen) == 1
				&& field("neighborNotice").get(screen).toString().startsWith("⏳ Gästeeinladung an Birkenhain")
				&& scrolledAction && scrolledPayload != null && scrolledPayload.action().equals("invite")
				&& scrolledPayload.index() == 1 && scrolledPayload.targetDimension().equals(scrolledTarget[11])
				&& scrolledPayload.targetBell().equals(net.minecraft.core.BlockPos.of(Long.parseLong(scrolledTarget[12]))),
				"clicking a scrolled neighbor row routes its advertised action to that exact row's bell");
		} finally {
			field("data").set(screen, originalData);
			field("tab").setInt(screen, originalTab);
			field("neighbor").setInt(screen, originalNeighbor);
			field("scroll").setInt(screen, originalScroll);
			field("pendingNetworkActionRequestId").setInt(screen, originalPendingId);
			field("pendingNetworkActionTicks").setInt(screen, originalPendingTicks);
			field("pendingNetworkAction").set(screen, originalPendingAction);
			field("neighborNotice").set(screen, originalNotice);
			field("uiScale").setFloat(screen, originalScale);
			senderField.set(screen, originalSender);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility");
			visibility.setAccessible(true);
			visibility.invoke(screen);
		}
	}

	/** Pins the shared world/screen transform used by clicks, whole-cell zone actions, previews, and known-village markers. */
	private static void mapGeometry() throws ReflectiveOperationException {
		boolean mapAreasStaySeparated = true;
		int actionRowOffset = field("MAP_ACTION_ROW_Y").getInt(null);
		int detailsGap = field("MAP_DETAILS_GAP").getInt(null);
		int frameBottomPadding = field("MAP_FRAME_BOTTOM_PADDING").getInt(null);
		int helpLineHeight = field("MAP_HELP_LINE_HEIGHT").getInt(null);
		for (int[] screenSize : new int[][]{{920, 408}, {460, 204}, {320, 180}, {160, 90}}) {
			float scale = BoardScreen.panelUiScale(screenSize[0], screenSize[1]);
			int[] origin = BoardScreen.panelOrigin(screenSize[0], screenSize[1], scale);
			int[] viewport = BoardScreen.mapViewportBounds(origin[0], origin[1]);
			int[] frame = BoardScreen.mapFrameBounds(origin[0], origin[1]);
			double screenFrameBottom = (frame[1] + frame[3]) * scale;
			double screenHelpBottom = (viewport[1] + viewport[3] + frameBottomPadding + helpLineHeight) * scale;
			double screenActionTop = (origin[1] + actionRowOffset) * scale;
			double screenFrameRight = (frame[0] + frame[2]) * scale;
			double screenDetailsLeft = (viewport[0] + viewport[2] + detailsGap) * scale;
			mapAreasStaySeparated &= frame[0] <= viewport[0] && frame[1] <= viewport[1]
				&& frame[0] + frame[2] >= viewport[0] + viewport[2]
				&& frame[1] + frame[3] >= viewport[1] + viewport[3]
				&& screenFrameBottom <= (viewport[1] + viewport[3] + frameBottomPadding) * scale
				&& screenHelpBottom <= screenActionTop && screenFrameRight < screenDetailsLeft
				&& BoardScreen.mapViewportContains(viewport[0] + 0.5, viewport[1] + 0.5,
					viewport[0], viewport[1], viewport[2])
				&& !BoardScreen.mapViewportContains(viewport[0] + viewport[2], viewport[1] + 0.5,
					viewport[0], viewport[1], viewport[2]);
		}
		check(mapAreasStaySeparated,
			"map frame, shared scroll/click viewport, details, and action row remain separate down to 160x90 scaled screens");

		boolean controlsFitAtSmallViewport = true;
		for (int[] viewport : new int[][]{{920, 408}, {460, 204}}) {
			float scale = BoardScreen.panelUiScale(viewport[0], viewport[1]);
			int[] origin = BoardScreen.panelOrigin(viewport[0], viewport[1], scale);
			controlsFitAtSmallViewport &= BoardScreen.neighborControlsFit(origin[0], origin[1], scale, viewport[0], viewport[1])
				&& BoardScreen.neighborActionHitboxesDistinct(origin[0], origin[1]);
			int[] actionRow = BoardScreen.neighborActionBounds(origin[0], origin[1], 0);
			int[] footerRow = BoardScreen.footerButtonBounds(origin[0], origin[1], 0);
			controlsFitAtSmallViewport &= actionRow[1] + actionRow[3] <= footerRow[1]
				&& footerRow[1] + footerRow[3] <= origin[1] + 280 - 6;
		}
		check(controlsFitAtSmallViewport,
			"neighbor actions, approval, village-card, close, and live controls stay clickable at 920x408 and scaled 460x204 viewports");

		check(BoardScreen.neighborRowAt(30, 131, 10, 20, 0, 4) == 0
			&& BoardScreen.neighborRowAt(30, 166.9, 10, 20, 0, 4) == 0
			&& BoardScreen.neighborRowAt(30, 167, 10, 20, 0, 4) == 1
			&& BoardScreen.neighborRowAt(30, 131, 10, 20, 1, 5) == 1
			&& BoardScreen.neighborRowAt(30, 203, 10, 20, 2, 4) == 3
			&& BoardScreen.neighborRowAt(30, 203, 10, 20, 0, 2) == -1
			&& BoardScreen.neighborRowAt(19.9, 131, 10, 20, 0, 4) == -1
			&& BoardScreen.neighborRowAt(30, 239, 10, 20, 0, 4) == -1,
			"neighbor row hitboxes map clicks to the visible, scrolled backing-list index only");

		net.minecraft.core.BlockPos board = new net.minecraft.core.BlockPos(2, 64, 3);
		net.minecraft.core.BlockPos targetBell = new net.minecraft.core.BlockPos(-120, 70, 305);
		String[] partner = {"Eichenruh", "88 Blöcke Osten", "Osten", "3", "42", "Straße", "1",
			"keine Tausche", "Zustimmung offen", "keine", "1", "minecraft:overworld", Long.toString(targetBell.asLong())};
		String[] actionNames = {"swap", "route", "invite", "greet"};
		boolean payloadsMatchSelectedRows = true;
		for (int i = 0; i < actionNames.length; i++) {
			String action = BoardScreen.neighborActionName(i);
			de.villagerplus.net.NetworkAction payload = BoardScreen.neighborActionPayload(board, action, i, partner);
			payloadsMatchSelectedRows &= action.equals(actionNames[i]) && payload != null
				&& payload.action().equals(action) && payload.index() == i
				&& payload.targetDimension().equals("minecraft:overworld") && payload.targetBell().equals(targetBell);
		}
		check(payloadsMatchSelectedRows, "all four neighbor buttons send the selected row's exact dimension, bell, index, and action");
		String[] otherPartner = partner.clone(); otherPartner[0] = "Other";
		otherPartner[12] = Long.toString(new net.minecraft.core.BlockPos(0, 64, 0).asLong());
		check(BoardScreen.neighborIndex(List.of(String.join("|", otherPartner), String.join("|", partner)), partner) == 1,
			"neighbor selection survives refreshed rows by stable dimension and bell identity");
		String[] unloadedPartner = partner.clone(); unloadedPartner[6] = "0";
		String[] invalidPartner = partner.clone(); invalidPartner[12] = "not-a-position";
		check(BoardScreen.neighborActionFailure("swap", null) != null
			&& BoardScreen.neighborActionPayload(board, "swap", -1, null) == null
			&& BoardScreen.neighborActionFailure("swap", unloadedPartner) != null
			&& BoardScreen.neighborActionFailure("approve", unloadedPartner) == null
			&& BoardScreen.neighborActionPayload(board, "swap", 0, invalidPartner) == null,
			"missing, stale, and unloaded neighbor targets give explicit feedback while local approval remains available");
		de.villagerplus.net.NetworkAction card = BoardScreen.neighborActionPayload(board, "card", -1, null);
		check(BoardScreen.neighborActionFailure("card", null) == null && card != null
			&& card.action().equals("card") && card.index() == -1,
			"the village-card action remains available without a selected partner row");

		check(java.util.Arrays.equals(BoardScreen.mapViewportGridCell(20.9, 30.9, 20, 30, 168, 0, 0, 4, 48, false), new int[]{0, 0})
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(187.9, 197.9, 20, 30, 168, -24, -24, 4, 48, false), new int[]{47, 47})
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(33.9, 57.8, 20, 30, 168, -4, 6, 3, 48, false), new int[]{5, 7})
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(111.9, 239.2, 20, 30, 252, -4, 6, 12, 48, false), new int[]{7, 16}),
			"zone pointer maps the viewport corners and normal/zoomed cells through one floor-based transform");
		check(BoardScreen.mapViewportGridCell(19.9, 40, 20, 30, 168, 0, 0, 4, 48, false) == null
			&& BoardScreen.mapViewportGridCell(20, 29.9, 20, 30, 168, 0, 0, 4, 48, false) == null
			&& BoardScreen.mapViewportGridCell(188, 40, 20, 30, 168, -24, 0, 4, 48, false) == null
			&& BoardScreen.mapViewportGridCell(40, 198, 20, 30, 168, 0, -24, 4, 48, false) == null
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(19.9, 40, 20, 30, 168, 0, 0, 4, 48, true), new int[]{0, 2})
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(40, 29.9, 20, 30, 168, 0, 0, 4, 48, true), new int[]{5, 0})
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(188.1, 40, 20, 30, 168, -24, 0, 4, 48, true), new int[]{47, 2})
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(40, 198.1, 20, 30, 168, 0, -24, 4, 48, true), new int[]{5, 47})
			&& BoardScreen.mapViewportGridCell(Double.NaN, 40, 20, 30, 168, 0, 0, 4, 48, true) == null
			&& BoardScreen.mapViewportGridCell(21, 31, 20, 30, 168, 12, 12, 3, 48, false) == null
			&& java.util.Arrays.equals(BoardScreen.mapViewportGridCell(21, 31, 20, 30, 168, 12, 12, 3, 48, true), new int[]{0, 0}),
			"map starts reject all outside edges, while drag endpoints safely clamp outside pointers and blank padding to visible grid cells");
		double[] staleDragFirst = BoardScreen.resolveZoneDragPointer(20.5, 30.5, 20.5, 30.5,
			20.5, 30.5, 13.5, 9.5);
		double[] staleDragNext = staleDragFirst == null ? null : BoardScreen.resolveZoneDragPointer(20.5, 30.5,
			staleDragFirst[0], staleDragFirst[1], 20.5, 30.5, 4.0, 4.0);
		double[] staleRelease = staleDragNext == null ? null : BoardScreen.resolveZoneReleasePointer(20.5, 30.5,
			staleDragNext[0], staleDragNext[1], 20.5, 30.5);
		check(staleRelease != null && java.util.Arrays.equals(BoardScreen.mapViewportGridCell(staleRelease[0], staleRelease[1], 20, 30,
				168, 0, 0, 4, 48, true), new int[]{4, 3}),
			"drag deltas preserve the current pointer when mouse events keep the original press pixel, and release commits its final raster cell");

		check(BoardScreen.mapPixelToWorldOffset(0, 7, 3) == 0
			&& BoardScreen.mapPixelToWorldOffset(2, 7, 3) == 4
			&& BoardScreen.mapPixelToWorldOffset(3, 7, 3) == 7
			&& BoardScreen.mapPixelToWorldOffset(-1, 7, 3) == -3,
			"world click coordinates use pixel offsets and floor consistently at zoom edges");

		int[] forward = BoardScreen.zoneWorldBounds(-100, 200, 7, 2, 3, 5, 7);
		int[] reverse = BoardScreen.zoneWorldBounds(-100, 200, 7, 5, 7, 2, 3);
		check(java.util.Arrays.equals(forward, new int[]{-86, 221, -59, 255})
			&& java.util.Arrays.equals(reverse, forward),
			"zone actions cover the same inclusive whole-cell world bounds in either drag direction");
		int[] topLeftCell = BoardScreen.zoneWorldBounds(-100, 200, 4, 0, 0, 0, 0);
		int[] bottomRightCell = BoardScreen.zoneWorldBounds(-100, 200, 4, 47, 47, 47, 47);
		int[] measuredRectangle = BoardScreen.zoneWorldBounds(-100, 200, 4, 0, 0, 2, 3);
		check(java.util.Arrays.equals(topLeftCell, new int[]{-100, 200, -97, 203})
			&& java.util.Arrays.equals(bottomRightCell, new int[]{88, 388, 91, 391})
			&& java.util.Arrays.equals(measuredRectangle, new int[]{-100, 200, -89, 215})
			&& ((long)measuredRectangle[2] - measuredRectangle[0] + 1L) == 12L
			&& ((long)measuredRectangle[3] - measuredRectangle[1] + 1L) == 16L
			&& BoardScreen.zoneRequestFailure(new de.villagerplus.net.SetZone(board,
				measuredRectangle[0], measuredRectangle[1], measuredRectangle[2], measuredRectangle[3], "farm", false, 1)) == null,
			"corner cells map to exact world bounds and multi-cell rectangles preserve their requested width, height, and area");
		int[] zoomedOut = BoardScreen.zoneScreenBounds(-100, 200, 7, 3, -13, 5,
			forward[0], forward[1], forward[2], forward[3]);
		int[] zoomedIn = BoardScreen.zoneScreenBounds(-100, 200, 7, 12, -20, -8,
			forward[0], forward[1], forward[2], forward[3]);
		BoardScreen.ZoneSelection fiveCells = new BoardScreen.ZoneSelection(2, 3, 5, 7, false);
		int[] livePreviewOut = BoardScreen.zoneCellScreenBounds(3, -13, 5, fiveCells);
		int[] livePreviewIn = BoardScreen.zoneCellScreenBounds(12, -20, -8, fiveCells);
		check(java.util.Arrays.equals(zoomedOut, new int[]{-7, 14, 5, 29})
			&& java.util.Arrays.equals(zoomedIn, new int[]{4, 28, 52, 88})
			&& java.util.Arrays.equals(livePreviewOut, zoomedOut)
			&& java.util.Arrays.equals(livePreviewIn, zoomedIn),
			"live zone previews cover exactly the selected map cells at both zoom levels, matching their sent world bounds");

		BoardScreen.ZoneSelection accepted = BoardScreen.boundedZoneSelection(-100, 200, 4, 48, -2, 298,
			24, 24, 27, 28);
		BoardScreen.ZoneSelection acceptedReverse = BoardScreen.boundedZoneSelection(-100, 200, 4, 48, -2, 298,
			27, 28, 24, 24);
		check(accepted != null && !accepted.clipped() && accepted.equals(acceptedReverse)
			&& java.util.Arrays.equals(BoardScreen.zoneWorldBounds(-100, 200, 4, accepted.minCol(), accepted.minRow(),
				accepted.maxCol(), accepted.maxRow()), new int[]{-4, 296, 11, 315}),
			"a valid zone drag submits exactly the same full-cell world rectangle in both directions");
		check("Arbeit · 4×5 Felder".equals(BoardScreen.zonePreviewText(2, accepted)),
			"the live zone preview identifies its category and the exact selected map-cell dimensions");

		BoardScreen.ZoneSelection boundedForward = BoardScreen.boundedZoneSelection(-100, 200, 4, 48, -2, 298,
			10, 10, 38, 38);
		BoardScreen.ZoneSelection boundedReverse = BoardScreen.boundedZoneSelection(-100, 200, 4, 48, -2, 298,
			38, 38, 10, 10);
		int[] boundedWorld = BoardScreen.zoneWorldBounds(-100, 200, 4, boundedForward.minCol(), boundedForward.minRow(),
			boundedForward.maxCol(), boundedForward.maxRow());
		int[] reverseBoundedWorld = BoardScreen.zoneWorldBounds(-100, 200, 4, boundedReverse.minCol(), boundedReverse.minRow(),
			boundedReverse.maxCol(), boundedReverse.maxRow());
		boolean anchorsStayPut = boundedForward != null && boundedReverse != null
			&& boundedForward.minCol() == 10 && boundedForward.minRow() == 10
			&& boundedReverse.maxCol() == 38 && boundedReverse.maxRow() == 38
			&& !boundedForward.equals(boundedReverse)
			&& ((long)boundedWorld[2] - boundedWorld[0] + 1) * ((long)boundedWorld[3] - boundedWorld[1] + 1) == 4096L
			&& ((long)reverseBoundedWorld[2] - reverseBoundedWorld[0] + 1)
				* ((long)reverseBoundedWorld[3] - reverseBoundedWorld[1] + 1) == 4096L;
		for (int xDirection = 0; xDirection < 2; xDirection++) for (int zDirection = 0; zDirection < 2; zDirection++) {
			int startCol = xDirection == 0 ? 10 : 38, endCol = xDirection == 0 ? 38 : 10;
			int startRow = zDirection == 0 ? 10 : 38, endRow = zDirection == 0 ? 38 : 10;
			BoardScreen.ZoneSelection directionSelection = BoardScreen.boundedZoneSelection(-100, 200, 4, 48, -2, 298,
				startCol, startRow, endCol, endRow);
			anchorsStayPut &= directionSelection != null && directionSelection.clipped()
				&& (endCol > startCol ? directionSelection.minCol() == startCol : directionSelection.maxCol() == startCol)
				&& (endRow > startRow ? directionSelection.minRow() == startRow : directionSelection.maxRow() == startRow)
				&& (long)(directionSelection.maxCol() - directionSelection.minCol() + 1)
					* (directionSelection.maxRow() - directionSelection.minRow() + 1) <= 256L;
		}
		check(anchorsStayPut,
			"oversized previews clip at the dragged endpoint, keep the clicked anchor fixed in all directions, and stay within 4096 blocks");
		BoardScreen.ZoneSelection radiusClipped = BoardScreen.boundedZoneSelection(-100, 200, 4, 48, -2, 298,
			24, 24, 47, 24);
		check(radiusClipped != null && radiusClipped.clipped() && radiusClipped.maxCol() == 38
			&& BoardScreen.boundedZoneSelection(-100, 200, 4, 48, -2, 298, 0, 0, 1, 1) == null,
			"zone selection visibly clips to the server's safe radius and rejects a start outside it");

		List<Integer> markerCells = de.villagerplus.village.VillageMap.nearbyVillageMarkerIndices(48, 0, 0, 8,
			new net.minecraft.core.BlockPos(1, 64, 1), List.of(new net.minecraft.core.BlockPos(1, 64, 1),
				new net.minecraft.core.BlockPos(16, 64, 16), new net.minecraft.core.BlockPos(17, 64, 17),
				new net.minecraft.core.BlockPos(500, 64, 0)));
		String[] markerNames = (String[])field("MARK_NAMES").get(null);
		check(markerCells.equals(List.of(2 * 48 + 2)) && markerNames[16].equals("Nachbardorf"),
			"recognized nearby village bells create one selectable map marker per raster cell");

		check(de.villagerplus.net.BoardData.guestRows(List.of("Gast (ohne Orts-ID)", "Gast (ohne Orts-ID)", "Gast ohne Namen"))
			.equals(List.of("@guest|Gast (ohne Orts-ID)|2|anwesend", "@guest|Gast ohne Namen|1|anwesend")),
			"relations show currently present guest names and counts without creating village identities");
		check(BoardScreen.caravanContactLabel(new String[]{"Eisenbach", "Eisen", "72", "vor 2 Tagen"})
			.equals("Eisenbach · Eisen · Beziehung 72 · vor 2 Tagen")
			&& BoardScreen.guestOriginLabel(new String[]{"@guest", "Gast aus Lindenau", "2", "anwesend"})
			.equals("Lindenau · 2 Gäste"),
			"relationship summaries expose the actual partner village and the guest's recorded origin name");
		String[] undiscoveredTrade = {"Unbekannter Ort (noch kein Handelskontakt)", "Erze & Werkzeuge", "8", "noch nie"};
		List<String> unlinkedGuest = List.of("@guest|Gast aus Mondmarkt|2|anwesend");
		List<String> unlinkedGuestRows = BoardScreen.relationshipRows(List.of(), List.of(String.join("|", undiscoveredTrade), unlinkedGuest.getFirst()));
		String[] unlinkedGuestRow = unlinkedGuestRows.getFirst().split("\\|", -1);
		check(!BoardScreen.confirmedTradeContact(undiscoveredTrade) && BoardScreen.caravanContactLabel(undiscoveredTrade).isEmpty()
			&& unlinkedGuestRows.size() == 1 && unlinkedGuestRow[0].equals("Mondmarkt")
			&& BoardScreen.historicalRelationship(unlinkedGuestRow)
			&& BoardScreen.neighborActionFailure("swap", unlinkedGuestRow).contains("Dorfkarten")
			&& BoardScreen.neighborActionPayload(board, "swap", 0, unlinkedGuestRow) == null
			&& BoardScreen.guestPresenceLabels(List.of(), unlinkedGuest).equals(List.of("Mondmarkt · 2 Gäste (Besuchsort · keine Dorfverbindung)")),
			"named guest destinations stay visible as non-actionable visit history while undiscovered trade placeholders stay hidden");
		check(BoardScreen.detailStatusColor("Stillstand:") == 0xFFB44A26
			&& BoardScreen.detailStatusColor("Noch zu liefern:") == 0xFF9A6818
			&& BoardScreen.detailStatusColor("Davon fehlen im Dorf:") == 0xFFC02D24,
			"construction wait labels receive consistent scan-friendly status colors");
	}

	/** Exercises the production title clip with a village name long enough to reach the rename control. */
	private static void headerLayout(BoardScreen screen) throws ReflectiveOperationException {
		Font font = (Font)inheritedField(screen, "font");
		BoardData data = (BoardData)field("data").get(screen);
		String longVillageName = "Dorfname-" + "Waldtal-".repeat(24);
		String title = BoardScreen.headerTitle(font, longVillageName, data.board());
		int titleWidth = font.width(title);
		check(title.endsWith("…") && title.length() < longVillageName.length(),
			"long village header is shortened with an ellipsis");
		check(titleWidth <= BoardScreen.headerTitleMaxWidth(),
			"long village header fits its measured width before the rename control");
		check(BoardScreen.HEADER_TEXT_X + titleWidth + BoardScreen.HEADER_BUTTON_GAP <= BoardScreen.RENAME_BUTTON_X,
			"header title leaves the reserved gap before the rename button");
		check(BoardScreen.HEADER_TEXT_Y + font.lineHeight < BoardScreen.LIVE_BUTTON_Y,
			"header title stays vertically clear of the live button band");
	}

	/** Verifies the exact resident selection hint from the screenshot and the overflow hints against both footer bands. */
	private static void footerLayout(BoardScreen screen) throws ReflectiveOperationException {
		Font font = (Font)inheritedField(screen, "font");
		BoardData data = (BoardData)field("data").get(screen);
		String residentHint = BoardScreen.residentHintText(data.residents().size());
		var hintMethod = BoardScreen.class.getDeclaredMethod("residentHintLines");
		hintMethod.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<String> residentLines = (List<String>)hintMethod.invoke(screen);
		check(String.join(" ", residentLines).equals(residentHint),
			"resident selection footer preserves the complete arrow-key/mouse assignment hint");
		check(!residentLines.isEmpty() && residentLines.stream().allMatch(line -> font.width(line) <= 460 - 32),
			"the resident selection footer wraps to the available panel width");
		for (boolean hasSelection : new boolean[]{false, true}) {
			int hintLines = residentLines.size();
			int contentBottom = BoardScreen.residentContentBottomOffset(hasSelection);
			int hintY = BoardScreen.residentFooterYOffset(hasSelection, hintLines);
			int rowsBottom = 64 + BoardScreen.residentVisibleRows(hasSelection, hintLines) * 11;
			int hintTextBottom = hintY + (hintLines - 1) * 11 + 9;
			check(rowsBottom <= hintY, "resident rows clear the actual selection footer");
			check(hintY + hintLines * 11 <= contentBottom,
				"actual resident selection footer clears the " + (hasSelection ? "selected-resident panel" : "content edge"));
			check(hintTextBottom < 280 - 24, "actual resident selection footer clears the live/task button band");
		}

		// Keep boundary coverage for longer resident labels and the separate details/list footer.
		for (boolean hasSelection : new boolean[]{false, true}) {
			for (int hintLines = 1; hintLines <= 3; hintLines++) {
				int contentBottom = BoardScreen.residentContentBottomOffset(hasSelection);
				int hintY = BoardScreen.residentFooterYOffset(hasSelection, hintLines);
				int rowsBottom = 64 + BoardScreen.residentVisibleRows(hasSelection, hintLines) * 11;
				int hintTextBottom = hintY + (hintLines - 1) * 11 + 9;
				check(rowsBottom <= hintY, "resident rows clear the " + hintLines + "-line footer");
				check(hintY + hintLines * 11 <= contentBottom,
					"resident footer clears the " + (hasSelection ? "selected-resident panel" : "content edge"));
				check(hintTextBottom < 280 - 24, "resident footer clears close/live and task buttons");
			}
		}
		for (int hintLines = 1; hintLines <= 3; hintLines++) {
			int hintY = BoardScreen.listFooterYOffset(hintLines);
			int rowsBottom = 52 + BoardScreen.listVisibleRows(hintLines) * 11;
			int hintTextBottom = hintY + (hintLines - 1) * 11 + 9;
			check(rowsBottom <= hintY, "scrollable detail rows clear the " + hintLines + "-line footer");
			check(hintY + hintLines * 11 <= 280 - 30, "scroll footer stays inside the content area");
			check(hintTextBottom < 280 - 24, "scroll footer clears close/live and task buttons");
		}
	}

	/** Pins the type/name mapping and release rules used by the real zone-drag event handler. */
	private static void mapZones(BoardScreen screen) throws ReflectiveOperationException {
		String[] ids = {"farm", "housing", "work", "pasture"};
		String[] labels = {"Zone: Feld", "Zone: Wohnen", "Zone: Arbeit", "Zone: Weide"};
		for (int kind = 0; kind < ids.length; kind++) {
			check(ids[kind].equals(BoardScreen.zoneKindId(kind)) && labels[kind].equals(BoardScreen.zoneButtonText(kind)),
				"zone selection keeps the visible name and stored category aligned for " + ids[kind]);
		}
		check("Zone löschen".equals(BoardScreen.zoneButtonText(ids.length)), "the fifth selection is the explicit delete tool");
		check(BoardScreen.zoneReleaseCommits(0), "left release commits after its endpoint is clamped to the visible map");
		check(!BoardScreen.zoneReleaseCommits(1), "right-button release cancels instead of committing a zone");
		check(BoardScreen.nextZoneRequestId(0) == 1 && BoardScreen.nextZoneRequestId(Integer.MAX_VALUE) == 1
			&& BoardScreen.matchesZoneResult(net.minecraft.core.BlockPos.ZERO, 7,
				new de.villagerplus.net.SetZone.Result(net.minecraft.core.BlockPos.ZERO, 7, true, "gespeichert"))
			&& !BoardScreen.matchesZoneResult(net.minecraft.core.BlockPos.ZERO, 7,
				new de.villagerplus.net.SetZone.Result(net.minecraft.core.BlockPos.ZERO.east(), 7, true, "fremde Tafel")),
			"zone acknowledgements use positive request IDs and cannot cross-match a different board");
		check(BoardScreen.zoneRequestFailure(new de.villagerplus.net.SetZone(net.minecraft.core.BlockPos.ZERO,
			0, 0, 0, 0, "unknown", false, 1)) != null
			&& BoardScreen.zoneRequestFailure(new de.villagerplus.net.SetZone(net.minecraft.core.BlockPos.ZERO,
				0, 0, 63, 64, "farm", false, 1)) != null,
			"client refuses an unknown zone type and over-area payload before sending");

		int zoneKind = field("zoneKind").getInt(screen), drawingZoneKind = field("drawingZoneKind").getInt(screen);
		int startX = field("zoneStartX").getInt(screen), startZ = field("zoneStartZ").getInt(screen);
		int endX = field("zoneEndX").getInt(screen), endZ = field("zoneEndZ").getInt(screen);
		int tab = field("tab").getInt(screen), mapCell = field("mapCell").getInt(screen);
		int panX = field("mapPanX").getInt(screen), panY = field("mapPanY").getInt(screen);
		float originalScale = field("uiScale").getFloat(screen);
		boolean drawingZone = field("drawingZone").getBoolean(screen), zoneTool = field("zoneTool").getBoolean(screen);
		boolean draggingMap = field("draggingMap").getBoolean(screen);
		BoardData originalData = (BoardData)field("data").get(screen);
		var senderField = field("zoneActionSender");
		Object originalSender = senderField.get(screen);
		List<de.villagerplus.net.SetZone> sent = new java.util.ArrayList<>();
		List<String> originalQuota = BoardScreen.quota;
		boolean zoneClipped = field("zoneClipped").getBoolean(screen);
		String zoneNotice = (String)field("zoneNotice").get(screen);
		int pendingZoneRequestId = field("pendingZoneRequestId").getInt(screen);
		int pendingZoneRequestTicks = field("pendingZoneRequestTicks").getInt(screen);
		boolean zoneButtonActive = ((Button)field("zoneButton").get(screen)).active;
		try {
			int size = originalData.size();
			int mapView = field("MAP_VIEW").getInt(null);
			float scale = field("uiScale").getFloat(screen);
			int left = field("left").getInt(screen), top = field("top").getInt(screen);
			byte[] marks = new byte[size * size];
			marks[(size / 2) * size + size / 2] = 1;
			field("data").set(screen, new BoardData(originalData.board(), originalData.village(), size, 4,
				new byte[size * size], marks, originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), originalData.neighbors(), originalData.partners(), originalData.journal()));
			BoardScreen.quota = List.of("@o|-100|200");
			field("tab").setInt(screen, 0);
			field("mapCell").setInt(screen, 3);
			field("mapPanX").setInt(screen, 12); field("mapPanY").setInt(screen, 12);
			field("zoneKind").setInt(screen, 2);
			field("zoneTool").setBoolean(screen, true);
			field("drawingZone").setBoolean(screen, false);
			senderField.set(screen, (java.util.function.Consumer<de.villagerplus.net.SetZone>)sent::add);
			double mapLeft = left + 14, mapTop = top + 54;
			double[][] outsideClicks = {
				{mapLeft - 0.5, mapTop + mapView / 2.0},
				{mapLeft + mapView, mapTop + mapView / 2.0},
				{mapLeft + mapView / 2.0, mapTop - 0.5},
				{mapLeft + mapView / 2.0, mapTop + mapView}
			};
			boolean outsideRejected = true;
			for (double[] point : outsideClicks) {
				boolean handled = screen.mouseClicked(new MouseButtonEvent(point[0] * scale, point[1] * scale,
					new MouseButtonInfo(0, 0)), false);
				outsideRejected &= !handled && !field("drawingZone").getBoolean(screen)
					&& field("zoneTool").getBoolean(screen) && sent.isEmpty();
			}
			check(outsideRejected, "mouse-down just outside each viewport edge cannot start or submit a zone drag");
			double emptyPaddingX = (left + 14 + 2) * scale;
			double emptyPaddingY = (top + 54 + mapView / 2.0) * scale;
			var emptyPaddingClick = new MouseButtonEvent(emptyPaddingX, emptyPaddingY, new MouseButtonInfo(0, 0));
			check(screen.mouseClicked(emptyPaddingClick, false) && !field("drawingZone").getBoolean(screen)
				&& field("zoneTool").getBoolean(screen) && ((String)field("zoneNotice").get(screen)).contains("Leeres Kartenfeld")
				&& sent.isEmpty(),
				"clicking centered-map padding explains that no zone can start there and leaves the tool ready to retry");
			field("zoneKind").setInt(screen, ids.length);
			field("zoneTool").setBoolean(screen, true);
			check(screen.mouseClicked(emptyPaddingClick, false) && field("zoneTool").getBoolean(screen)
				&& ((String)field("zoneNotice").get(screen)).contains("Leeres Kartenfeld") && sent.isEmpty(),
				"deleting a zone from centered-map padding also explains the empty target without sending a packet");
			field("zoneKind").setInt(screen, 2);
			field("zoneTool").setBoolean(screen, true);
			check(clickMapCell(screen, size / 2, size / 2) && field("drawingZone").getBoolean(screen),
				"left-clicking a visible map cell starts a real zone drag");
			double dragPointerX = (left + 14 + 12 + 27 * 3 + 1.5) * scale;
			double dragPointerY = (top + 54 + 12 + 26 * 3 + 1.5) * scale;
			var dragEvent = new MouseButtonEvent(dragPointerX, dragPointerY, new MouseButtonInfo(0, 0));
			check(screen.mouseDragged(dragEvent, 9 * scale, 6 * scale)
				&& field("zoneEndX").getInt(screen) == 27 && field("zoneEndZ").getInt(screen) == 26,
				"dragging across the viewport updates the endpoint from the scaled pointer position");
			int[] relativePreview = BoardScreen.zoneScreenBounds(-100, 200, 4, 3, 12, 12,
				-4, 296, 11, 307);
			check(java.util.Arrays.equals(relativePreview, new int[]{84, 84, 96, 93}),
				"zone preview remains relative to the panned map viewport at the selected cell bounds");
			check(screen.mouseReleased(dragEvent), "releasing the left button inside the viewport completes the drag");
			check(sent.size() == 1 && sent.get(0).board().equals(originalData.board())
				&& sent.get(0).minX() == -4 && sent.get(0).minZ() == 296
				&& sent.get(0).maxX() == 11 && sent.get(0).maxZ() == 307
				&& sent.get(0).kind().equals("work") && !sent.get(0).delete() && sent.get(0).requestId() > 0
				&& field("pendingZoneRequestId").getInt(screen) == sent.get(0).requestId()
				&& field("zonePreviewRequest").get(screen) == sent.get(0) && !field("zonePreviewAccepted").getBoolean(screen)
				&& !((Button)field("zoneButton").get(screen)).active,
				"the real drag release dispatches exact world-cell bounds and keeps a visible pending rectangle for that request");
			check(((String)field("zoneNotice").get(screen)).contains("Server prüft")
				&& !screen.acceptZoneResult(new de.villagerplus.net.SetZone.Result(originalData.board(), sent.get(0).requestId() + 1, true, "falsche Anfrage"))
				&& field("pendingZoneRequestId").getInt(screen) == sent.get(0).requestId(),
				"the map shows pending state and ignores an unrelated success response");
			check(screen.acceptZoneResult(new de.villagerplus.net.SetZone.Result(originalData.board(), sent.get(0).requestId(), false,
				"Zone abgelehnt: Testgrund")) && ((String)field("zoneNotice").get(screen)).startsWith("✕ Zone abgelehnt")
				&& field("pendingZoneRequestId").getInt(screen) == 0 && field("zonePreviewRequest").get(screen) == null
				&& ((Button)field("zoneButton").get(screen)).active,
				"an authoritative rejection appears on the map, clears the optimistic rectangle, and re-enables zone drawing");
			field("zoneTool").setBoolean(screen, true);
			check(clickMapCell(screen, 27, 26) && field("drawingZone").getBoolean(screen),
				"a reverse-direction zone drag can start after the first packet is dispatched");
			double reverseX = (left + 14 + 12 + 24 * 3 + 1.5) * scale;
			double reverseY = (top + 54 + 12 + 24 * 3 + 1.5) * scale;
			var reverseEvent = new MouseButtonEvent(reverseX, reverseY, new MouseButtonInfo(0, 0));
			check(screen.mouseDragged(reverseEvent, -9 * scale, -6 * scale)
				&& field("zoneEndX").getInt(screen) == 24 && field("zoneEndZ").getInt(screen) == 24
				&& screen.mouseReleased(reverseEvent) && sent.size() == 2
				&& sent.get(1).minX() == -4 && sent.get(1).minZ() == 296
				&& sent.get(1).maxX() == 11 && sent.get(1).maxZ() == 307,
				"reverse dragging dispatches the same normalized world rectangle");
			check(screen.acceptZoneResult(new de.villagerplus.net.SetZone.Result(originalData.board(), sent.get(1).requestId(), true,
				"Arbeitszone gespeichert.")) && ((String)field("zoneNotice").get(screen)).startsWith("✓ Arbeitszone gespeichert")
				&& field("zonePreviewRequest").get(screen) == sent.get(1) && field("zonePreviewAccepted").getBoolean(screen),
				"a matching server acceptance turns the pending rectangle into a green confirmed preview");
			var acceptedRows = List.of("@z|confirmed-zone|work|-4|296|11|307");
			check(BoardScreen.zonePreviewPresentInRows(acceptedRows, sent.get(1))
				&& !BoardScreen.zonePreviewPresentInRows(List.of("@z|different-bounds|work|-4|296|11|311"), sent.get(1))
				&& !BoardScreen.zonePreviewPresentInRows(acceptedRows,
					new de.villagerplus.net.SetZone(originalData.board(), -4, 296, 11, 307, "work", true, sent.get(1).requestId())),
				"the confirmed outline retires only when authoritative quota rows contain the same saved rectangle");
			field("zoneTool").setBoolean(screen, true);
			check(clickMapCell(screen, size / 2, size / 2) && field("drawingZone").getBoolean(screen),
				"a new zone drag can start after either direction completes");
			double outsideX = (left + 14 + mapView + 2) * scale;
			double outsideReleaseY = (top + 54 + 12 + 24 * 3 + 1.5) * scale;
			var outsideRelease = new MouseButtonEvent(outsideX, outsideReleaseY, new MouseButtonInfo(0, 0));
			check(screen.mouseDragged(outsideRelease, (outsideX - (left + 14 + 12 + 24 * 3 + 1.5) * scale), 0)
				&& field("zoneEndX").getInt(screen) == size - 1
				&& screen.mouseReleased(outsideRelease) && !field("drawingZone").getBoolean(screen) && sent.size() == 3,
				"dragging and releasing just beyond the viewport clamps to its last visible cell and still submits safely");
			var edgeSelection = BoardScreen.boundedZoneSelection(-100, 200, 4, size, -2, 298,
				size / 2, size / 2, size - 1, size / 2);
			int[] edgeBounds = BoardScreen.zoneWorldBounds(-100, 200, 4, edgeSelection.minCol(), edgeSelection.minRow(),
				edgeSelection.maxCol(), edgeSelection.maxRow());
			var edgeRequest = sent.get(2);
			int[] edgeOverlay = BoardScreen.zoneScreenBounds(-100, 200, 4, 3, 12, 12,
				edgeBounds[0], edgeBounds[1], edgeBounds[2], edgeBounds[3]);
			check(edgeRequest.minX() == edgeBounds[0] && edgeRequest.minZ() == edgeBounds[1]
				&& edgeRequest.maxX() == edgeBounds[2] && edgeRequest.maxZ() == edgeBounds[3]
				&& edgeRequest.minX() == -4 && edgeRequest.maxX() == 55
				&& java.util.Arrays.equals(edgeOverlay, new int[]{84, 84, 129, 87})
				&& screen.acceptZoneResult(new de.villagerplus.net.SetZone.Result(originalData.board(), edgeRequest.requestId(), true,
					"Zone gespeichert.")),
				"the edge-clamped request and saved-zone overlay share the same inclusive world-cell rectangle");
			field("zoneTool").setBoolean(screen, true);
			field("drawingZone").setBoolean(screen, false);
			check(clickMapCell(screen, size / 2, size / 2) && field("drawingZone").getBoolean(screen),
				"a valid map-cell press starts the drag before endpoint clamping");
			double paddingX = (left + 14 + 2) * scale, paddingY = (top + 54 + 2) * scale;
			var paddingEvent = new MouseButtonEvent(paddingX, paddingY, new MouseButtonInfo(0, 0));
			check(screen.mouseDragged(paddingEvent, 0, 0)
				&& field("zoneEndX").getInt(screen) == 0 && field("zoneEndZ").getInt(screen) == 0
				&& screen.mouseReleased(paddingEvent) && sent.size() == 4,
				"an active drag clamps its endpoint across blank viewport padding to the nearest grid corner");
			var clampedRequest = sent.get(3);
			check(clampedRequest.minX() == -60 && clampedRequest.minZ() == 240
				&& clampedRequest.maxX() == -1 && clampedRequest.maxZ() == 299
				&& clampedRequest.kind().equals("work") && !clampedRequest.delete()
				&& screen.acceptZoneResult(new de.villagerplus.net.SetZone.Result(originalData.board(),
					clampedRequest.requestId(), true, "Zone gespeichert.")),
				"the clamped drag sends its exact radius-bounded whole-cell world rectangle");
			BoardScreen.quota = List.of("@o|-100|200", "@z|test-farm|farm|-4|296|11|307");
			field("zoneTool").setBoolean(screen, true);
			field("zoneKind").setInt(screen, ids.length);
			int requestsBeforeDelete = sent.size();
			check(clickMapCell(screen, size / 2, size / 2) && !field("drawingZone").getBoolean(screen)
				&& sent.size() == requestsBeforeDelete + 1 && sent.get(sent.size() - 1).delete()
				&& sent.get(sent.size() - 1).kind().equals("test-farm")
				&& screen.acceptZoneResult(new de.villagerplus.net.SetZone.Result(originalData.board(),
					sent.get(sent.size() - 1).requestId(), true, "Zone gelöscht."))
				&& ((String)field("zoneNotice").get(screen)).startsWith("✓ Zone gelöscht"),
				"the delete tool remains a single map click that sends the saved zone identity without starting a drag");
			field("zoneKind").setInt(screen, 2);
			field("zoneTool").setBoolean(screen, true);
			field("drawingZone").setBoolean(screen, false);
			check(clickMapCell(screen, size / 2, size / 2) && field("drawingZone").getBoolean(screen)
				&& field("drawingZoneKind").getInt(screen) == 2,
				"zone drawing snapshots the chosen work type when the drag begins");
			field("zoneKind").setInt(screen, 3);
			check("work".equals(BoardScreen.zoneKindId(field("drawingZoneKind").getInt(screen))),
				"changing the next selection cannot change the active drag's category");
			check("Zonenvorschau: Arbeit".equals(BoardScreen.zonePreviewText(field("drawingZoneKind").getInt(screen))),
				"active zone drag shows the frozen category in its preview label");
			double insideX = (left + 14 + 12 + 24 * 3 + 1.5) * scale;
			double insideY = (top + 54 + 12 + 24 * 3 + 1.5) * scale;
			int requestsBeforeCancel = sent.size();
			check(screen.mouseClicked(new MouseButtonEvent(insideX, insideY, new MouseButtonInfo(1, 0)), false)
				&& !field("drawingZone").getBoolean(screen) && sent.size() == requestsBeforeCancel
				&& ((String)field("zoneNotice").get(screen)).contains("abgebrochen"),
				"right click cancels the active zone drag with a clear notice and sends nothing");
			field("zoneTool").setBoolean(screen, true);
			check(clickMapCell(screen, size / 2, size / 2) && field("drawingZone").getBoolean(screen),
				"zone drag can be started again after cancellation");
			check(screen.mouseClicked(new MouseButtonEvent(insideX, insideY, new MouseButtonInfo(1, 0)), false)
				&& !field("drawingZone").getBoolean(screen), "restarted zone drag remains cancellable");
			field("zoneTool").setBoolean(screen, true);
			check(clickMapCell(screen, size / 2, size / 2) && field("drawingZone").getBoolean(screen),
				"a valid selection can be retried after cancellation");
			senderField.set(screen, (java.util.function.Consumer<de.villagerplus.net.SetZone>)request -> {
				throw new IllegalStateException("fixture send failure");
			});
			int requestsBeforeFailure = sent.size();
			double failedSendX = (left + 14 + 12 + (size / 2) * 3 + 1.5) * scale;
			double failedSendY = (top + 54 + 12 + (size / 2) * 3 + 1.5) * scale;
			check(screen.mouseReleased(new MouseButtonEvent(failedSendX, failedSendY, new MouseButtonInfo(0, 0)))
				&& sent.size() == requestsBeforeFailure && field("pendingZoneRequestId").getInt(screen) == 0
				&& ((String)field("zoneNotice").get(screen)).startsWith("✕ Zone konnte nicht gesendet")
				&& ((Button)field("zoneButton").get(screen)).active,
				"a local packet-send failure is shown in the error color and clears pending state");
			senderField.set(screen, (java.util.function.Consumer<de.villagerplus.net.SetZone>)sent::add);

			float secondScale = originalScale > 0.6F ? 0.5F : Math.max(0.1F, originalScale * 0.75F);
			float[] scales = secondScale == originalScale ? new float[]{originalScale} : new float[]{originalScale, secondScale};
			int[] zoomCells = {3, 12};
			for (float scaleAt : scales) {
				field("uiScale").setFloat(screen, scaleAt);
				for (int zoomIndex = 0; zoomIndex < zoomCells.length; zoomIndex++) {
					int cell = zoomCells[zoomIndex];
					int pan = zoomIndex == 0 ? 12 : -120;
					int base = zoomIndex == 0 ? 24 : 12;
					int spanX = zoomIndex == 0 ? 3 : 2;
					int spanY = zoomIndex == 0 ? 2 : 1;
					field("mapCell").setInt(screen, cell);
					field("mapPanX").setInt(screen, pan); field("mapPanY").setInt(screen, pan);
					for (int direction = 0; direction < 4; direction++) {
						boolean forwardX = (direction & 1) != 0, forwardY = (direction & 2) != 0;
						int fromCol = base + (forwardX ? 0 : spanX), toCol = base + (forwardX ? spanX : 0);
						int fromRow = base + (forwardY ? 0 : spanY), toRow = base + (forwardY ? spanY : 0);
						double fromX = (left + 14 + pan + fromCol * cell + cell / 2.0) * scaleAt;
						double fromY = (top + 54 + pan + fromRow * cell + cell / 2.0) * scaleAt;
						double toX = (left + 14 + pan + toCol * cell + cell / 2.0) * scaleAt;
						double toY = (top + 54 + pan + toRow * cell + cell / 2.0) * scaleAt;
						field("zoneKind").setInt(screen, 2);
						field("zoneTool").setBoolean(screen, true);
						field("drawingZone").setBoolean(screen, false);
						int requestsBeforeDrag = sent.size();
						check(screen.mouseClicked(new MouseButtonEvent(fromX, fromY, new MouseButtonInfo(0, 0)), false)
							&& field("drawingZone").getBoolean(screen),
							"scaled map click starts a zone drag at zoom " + cell + " and UI scale " + scaleAt);
					int panBeforeScrollX = field("mapPanX").getInt(screen), panBeforeScrollY = field("mapPanY").getInt(screen);
					check(screen.mouseScrolled(fromX, fromY, 0, 1) && field("mapCell").getInt(screen) == cell
						&& field("mapPanX").getInt(screen) == panBeforeScrollX && field("mapPanY").getInt(screen) == panBeforeScrollY,
						"active zone drag keeps its map-coordinate transform stable against wheel zoom");
					var endEvent = new MouseButtonEvent(toX, toY, new MouseButtonInfo(0, 0));
					check(screen.mouseDragged(endEvent, toX - fromX, toY - fromY)
						&& field("zoneEndX").getInt(screen) == toCol && field("zoneEndZ").getInt(screen) == toRow
						&& screen.mouseReleased(endEvent),
						"scaled real drag tracks the endpoint in direction " + direction + " at zoom " + cell);
					int[] expected = BoardScreen.zoneWorldBounds(-100, 200, 4, fromCol, fromRow, toCol, toRow);
					var request = sent.get(sent.size() - 1);
					check(request.minX() == expected[0] && request.minZ() == expected[1]
						&& request.maxX() == expected[2] && request.maxZ() == expected[3]
						&& sent.size() == requestsBeforeDrag + 1 && request.board().equals(originalData.board())
						&& request.kind().equals("work") && !request.delete()
							&& request.requestId() > 0 && ((String)field("zoneNotice").get(screen)).contains("Server prüft"),
						"zone drag sends the exact normalized world rectangle and a pending server-result notice");
					check(screen.acceptZoneResult(new de.villagerplus.net.SetZone.Result(originalData.board(), request.requestId(), true,
						"Zone gespeichert.")) && field("pendingZoneRequestId").getInt(screen) == 0,
						"each zoomed drag clears its own pending state only after the matching server response");
				}
			}
			}
		} finally {
			field("data").set(screen, originalData);
			BoardScreen.quota = originalQuota;
			field("tab").setInt(screen, tab);
			field("mapCell").setInt(screen, mapCell);
			field("mapPanX").setInt(screen, panX); field("mapPanY").setInt(screen, panY);
			field("zoneClipped").setBoolean(screen, zoneClipped);
			field("zoneNotice").set(screen, zoneNotice);
			field("pendingZoneRequestId").setInt(screen, pendingZoneRequestId);
			field("pendingZoneRequestTicks").setInt(screen, pendingZoneRequestTicks);
			senderField.set(screen, originalSender);
			field("drawingZone").setBoolean(screen, false);
			field("zoneTool").setBoolean(screen, zoneTool);
			field("zoneKind").setInt(screen, zoneKind);
			field("drawingZoneKind").setInt(screen, drawingZoneKind);
			field("zoneStartX").setInt(screen, startX); field("zoneStartZ").setInt(screen, startZ);
			field("zoneEndX").setInt(screen, endX); field("zoneEndZ").setInt(screen, endZ);
			field("uiScale").setFloat(screen, originalScale);
			field("drawingZone").setBoolean(screen, drawingZone);
			field("draggingMap").setBoolean(screen, draggingMap);
			((Button)field("zoneButton").get(screen)).setMessage(Component.literal(zoneTool
				? BoardScreen.zoneButtonText(zoneKind) : "Zone zeichnen"));
			((Button)field("zoneButton").get(screen)).active = zoneButtonActive;
		}
	}

	/** Exercises marker selection and its map details using the screen's real left-click path. */
	private static void mapLocations(BoardScreen screen) throws ReflectiveOperationException {
		var dataField = field("data");
		BoardData originalData = (BoardData)dataField.get(screen);
		List<String> originalQuota = BoardScreen.quota;
		int tab = field("tab").getInt(screen), mapCell = field("mapCell").getInt(screen);
		int panX = field("mapPanX").getInt(screen), panY = field("mapPanY").getInt(screen);
		int pickedBuilding = field("pickedBuilding").getInt(screen);
		int pickedMapMarker = field("pickedMapMarker").getInt(screen);
		int pickedMapMarkerCode = field("pickedMapMarkerCode").getInt(screen);
		boolean movePending = field("movePending").getBoolean(screen);
		boolean zoneTool = field("zoneTool").getBoolean(screen), drawingZone = field("drawingZone").getBoolean(screen);
		try {
			String[] markerNames = (String[])field("MARK_NAMES").get(null);
			check("Aktive Baustelle".equals(markerNames[13]), "legend and selected construction-site marker use the same clear name");
			int size = originalData.size();
			check(size >= 40, "map data has room for all selectable location fixtures");
			byte[] marks = new byte[size * size];
			int[][] locations = {{4, 4, 4}, {10, 4, 5}, {16, 4, 7}, {22, 4, 13}};
			String[] names = {"Schule", "Lager", "Mine", "Aktive Baustelle"};
			for (int[] location : locations) marks[location[1] * size + location[0]] = (byte)location[2];
			dataField.set(screen, new BoardData(originalData.board(), originalData.village(), size, originalData.cellSize(),
				new byte[size * size], marks, originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), originalData.neighbors(), originalData.partners(), originalData.journal()));
			BoardScreen.quota = List.of("@o|1200|2400");
			field("tab").setInt(screen, 0); field("mapCell").setInt(screen, 3);
			field("mapPanX").setInt(screen, 0); field("mapPanY").setInt(screen, 0);
			field("pickedBuilding").setInt(screen, -1); field("pickedMapMarker").setInt(screen, -1);
			field("pickedMapMarkerCode").setInt(screen, 0); field("movePending").setBoolean(screen, false);
			field("zoneTool").setBoolean(screen, false); field("drawingZone").setBoolean(screen, false);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility"); visibility.setAccessible(true); visibility.invoke(screen);
			for (int i = 0; i < locations.length; i++) {
				int col = locations[i][0], row = locations[i][1], index = row * size + col;
				check(clickMapCell(screen, col, row), "left click reaches named map marker " + names[i]);
				check(names[i].equals(screen.selectedMapMarkerName())
					&& field("pickedMapMarker").getInt(screen) == index,
					"map selects and labels " + names[i] + " from its live marker payload");
				int cellSize = originalData.cellSize();
				String expectedLocation = "Kartenfeld etwa X " + (1200 + col * cellSize + cellSize / 2)
					+ " · Z " + (2400 + row * cellSize + cellSize / 2);
				check(expectedLocation.equals(screen.selectedMapMarkerLocation()),
					"selected " + names[i] + " reports its map cell coordinates");
				check(!((Button)field("demolishButton").get(screen)).active && !((Button)field("moveButton").get(screen)).active,
					"non-building location selection does not enable demolition or relocation");
			}
			check(clickMapCell(screen, 42, 42) && screen.selectedMapMarkerName() == null,
				"clicking an unmarked map cell clears the location selection");

			int cellSize = originalData.cellSize();
			String[] buildingKinds = {"school", "storage", "mine", "construction_site"};
			List<String> buildingRows = new java.util.ArrayList<>();
			buildingRows.add("@o|1200|2400");
			for (int i = 0; i < locations.length; i++) {
				int col = locations[i][0], row = locations[i][1];
				int buildingX = 1200 + col * cellSize + cellSize / 2;
				int buildingZ = 2400 + row * cellSize + cellSize / 2;
				buildingRows.add("@b|" + buildingKinds[i] + "|" + names[i] + "|" + buildingX + "|64|" + buildingZ + "|0");
			}
			BoardScreen.quota = buildingRows;
			dataField.set(screen, new BoardData(originalData.board(), originalData.village(), size, cellSize,
				new byte[size * size], marks, originalData.bars(), originalData.residents(), originalData.details(),
				originalData.expeditions(), originalData.neighbors(), originalData.partners(), originalData.journal()));
			for (int i = 0; i < locations.length; i++) {
				int col = locations[i][0], row = locations[i][1];
				int buildingIndex = i;
				check(clickMapCell(screen, col, row)
					&& field("pickedBuilding").getInt(screen) == buildingIndex && screen.selectedMapMarkerName() == null,
					"first click keeps the " + names[i] + " building selected");
				check(((Button)field("demolishButton").get(screen)).active && ((Button)field("moveButton").get(screen)).active,
					"building-first selection retains demolition and relocation actions for " + names[i]);
				check(clickMapCell(screen, col, row, true)
					&& field("pickedBuilding").getInt(screen) == -1 && names[i].equals(screen.selectedMapMarkerName()),
					"repeat click selects the overlapping " + names[i] + " marker instead of resetting zoom");
				String expectedLocation = "Kartenfeld etwa X " + (1200 + col * cellSize + cellSize / 2)
					+ " · Z " + (2400 + row * cellSize + cellSize / 2);
				check(expectedLocation.equals(screen.selectedMapMarkerLocation()),
					"overlapping " + names[i] + " marker keeps its approximate map coordinates");
				check(!((Button)field("demolishButton").get(screen)).active && !((Button)field("moveButton").get(screen)).active,
					"location selection does not expose building demolition or relocation actions");
				check(clickMapCell(screen, col, row)
					&& field("pickedBuilding").getInt(screen) == buildingIndex && screen.selectedMapMarkerName() == null
					&& ((Button)field("demolishButton").get(screen)).active && ((Button)field("moveButton").get(screen)).active,
					"another click switches back to the " + names[i] + " building actions");
			}

			int buildingCol = 20, buildingRow = 20;
			int buildingX = 1200 + buildingCol * cellSize + cellSize / 2;
			int buildingZ = 2400 + buildingRow * cellSize + cellSize / 2;
			BoardScreen.quota = List.of("@o|1200|2400", "@b|school|Dorfschule|" + buildingX + "|64|" + buildingZ + "|0");
			field("pickedBuilding").setInt(screen, -1); field("pickedMapMarker").setInt(screen, -1); field("pickedMapMarkerCode").setInt(screen, 0);
			field("mapCell").setInt(screen, 6);
			check(clickMapCell(screen, buildingCol, buildingRow, true) && field("mapCell").getInt(screen) == 3
				&& field("pickedBuilding").getInt(screen) == -1,
				"double click on an unmarked building area still resets map zoom without changing selection");
			check(clickMapCell(screen, buildingCol, buildingRow) && field("pickedBuilding").getInt(screen) == 0
				&& ((Button)field("demolishButton").get(screen)).active && ((Button)field("moveButton").get(screen)).active,
				"an unmarked building remains selectable after the zoom-reset gesture");
			field("zoneTool").setBoolean(screen, true);
			check(clickMapCell(screen, buildingCol, buildingRow) && !field("drawingZone").getBoolean(screen)
				&& field("zoneTool").getBoolean(screen) && field("pickedBuilding").getInt(screen) == 0
				&& ((Button)field("demolishButton").get(screen)).active && ((Button)field("moveButton").get(screen)).active,
				"an armed zone tool yields a building click to its existing selection and actions");
		} finally {
			dataField.set(screen, originalData);
			BoardScreen.quota = originalQuota;
			field("tab").setInt(screen, tab); field("mapCell").setInt(screen, mapCell);
			field("mapPanX").setInt(screen, panX); field("mapPanY").setInt(screen, panY);
			field("pickedBuilding").setInt(screen, pickedBuilding);
			field("pickedMapMarker").setInt(screen, pickedMapMarker);
			field("pickedMapMarkerCode").setInt(screen, pickedMapMarkerCode);
			field("movePending").setBoolean(screen, movePending);
			field("zoneTool").setBoolean(screen, zoneTool); field("drawingZone").setBoolean(screen, drawingZone);
			var visibility = BoardScreen.class.getDeclaredMethod("networkVisibility"); visibility.setAccessible(true); visibility.invoke(screen);
		}
	}

	private static boolean clickMapCell(BoardScreen screen, int col, int row) throws ReflectiveOperationException {
		return clickMapCell(screen, col, row, false);
	}

	private static boolean clickMapCell(BoardScreen screen, int col, int row, boolean doubleClick) throws ReflectiveOperationException {
		float scale = field("uiScale").getFloat(screen);
		int left = field("left").getInt(screen), top = field("top").getInt(screen);
		int cell = field("mapCell").getInt(screen), panX = field("mapPanX").getInt(screen), panY = field("mapPanY").getInt(screen);
		double x = (left + 14 + panX + col * cell + cell / 2.0) * scale;
		double y = (top + 54 + panY + row * cell + cell / 2.0) * scale;
		return screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), doubleClick);
	}
}
