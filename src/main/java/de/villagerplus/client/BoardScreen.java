package de.villagerplus.client;

import de.villagerplus.net.AssignJob;
import de.villagerplus.net.NetworkAction;
import de.villagerplus.net.NetworkActionResult;
import de.villagerplus.net.BoardData;
import de.villagerplus.net.BoardRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Das Dorfverwaltungsmenue der Dorftafel. Der Talk-Reiter zeigt nur den angefragten, ausgewählten Bewohner und nutzt fuer lange Berichte dieselbe
 * scrollbare Zeilenansicht wie die Dorfdetails. Pergament-Look, Text ohne Schatten (gut lesbar).
 */
public class BoardScreen extends Screen {
	private static final int W = 460;
	private static final int H = 280;
	static final int HEADER_TEXT_X = 8;
	static final int HEADER_TEXT_Y = 6;
	static final int RENAME_BUTTON_X = W - 104;
	static final int HEADER_BUTTON_GAP = 8;
	private static final int MAP_VIEW = 168;
	private static final int MAP_VIEW_LEFT_INSET = 14;
	private static final int MAP_VIEW_TOP_INSET = 54;
	private static final int MAP_FRAME_PADDING = 5;
	private static final int MAP_FRAME_BOTTOM_PADDING = 1;
	private static final int MAP_HELP_LINE_HEIGHT = 9;
	private static final int MAP_DETAILS_GAP = 16;
	private static final int MAP_ACTION_ROW_Y = H - 48;
	private static final int MAP_BASE_CELL = 3;
	private static final int NEIGHBOR_ACTION_ROW_Y = H - 52;
	private static final int FOOTER_BUTTON_Y = H - 27;
	static final int LIVE_BUTTON_Y = FOOTER_BUTTON_Y;
	// Keep the client-side selector aligned with ZonePolicy.validBounds. These are blocks, not map cells.
	private static final int ZONE_MAX_AREA = 4096;
	private static final char DETAIL_INDENT_MARKER = '\u0001';
	private static final Pattern RAW_SECONDS = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(\\d{1,12})\\s*(?:s|sec|sek(?:unden?)?)\\b");
	private static final int ZONE_DELETE_KIND = 4;
	private static int zoneRequestSequence;
	private static final String[] ZONE_KIND_IDS = {"farm", "housing", "work", "pasture"};
	private static final String[] ZONE_KIND_NAMES = {"Feld", "Wohnen", "Arbeit", "Weide"};
	private static final String ACTION_ROUTE = "route";
	private static final String ACTION_GREET = "greet";
	private static final String[] NETWORK_ACTIONS = {"swap", ACTION_ROUTE, "invite", ACTION_GREET};
	private static final String HISTORICAL_RELATIONSHIP = "history";
	private static final String[] TABS = {"Karte", "Einw.", "Details", "Reisen", "Nachb.", "Journal", "Arbeit", "Lager", "Talk"};
	private static final int[] TERRAIN = {0xFF5E8A3E, 0xFF2F5E2B, 0xFFC9AC72, 0xFF3A74C8, 0xFFB5C24A, 0xFFB5523A, 0xFFE0CC8A, 0xFF8A8A84, 0xFFE8EEF2, 0xFFB86A44, 0xFFB8B4A8, 0xFF16120C};
	private static final String[] TERRAIN_NAMES = {"Wiese", "Wald", "Weg", "Wasser", "Feld", "Gebäude", "Sand", "Stein", "Schnee", "Tonland", "Platz", "unbekannt"};
	private static final String[] MARK_NAMES = {"", "Glocke", "Dorfkiste", "Dorftafel", "Schule", "Lager", "Gehege", "Mine", "alte Mine", "Bewohner", "Kornspeicher", "Werkzeugkammer", "Küche", "Aktive Baustelle", "Laden", "Bahnhof", "Nachbardorf"};
	private static final int[] MARK_COLORS = {0, 0xFFFFFFFF, 0xFFFFE04A, 0xFF4AE0FF, 0xFF5A7BFF, 0xFFB0B0B0, 0xFFFF5A5A, 0xFFC02020, 0xFF703030, 0xFF50FF50, 0xFFE0A030, 0xFF9A9A9A, 0xFFF08040, 0xFFFFFF40, 0xFFFF9AD0, 0xFF60C0C0, 0xFF57E7D4};
	private static final String[][] JOBS = {{"minecraft:farmer", "Bauer"}, {"minecraft:shepherd", "Schäfer"}, {"minecraft:butcher", "Metzger"},
		{"minecraft:fisherman", "Fischer"}, {"minecraft:armorer", "Rüstungsschmied"}, {"minecraft:toolsmith", "Werkzeugschmied"},
		{"minecraft:weaponsmith", "Waffenschmied"}, {"minecraft:cleric", "Kleriker"}, {"minecraft:mason", "Maurer"}, {"minecraft:fletcher", "Pfeilmacher"},
		{"minecraft:leatherworker", "Gerber"}, {"minecraft:cartographer", "Kartograph"}, {"minecraft:librarian", "Bibliothekar"}, {"minecraft:none", "ohne Beruf"}};
	private static final int INK = 0xFF2B1D0E;
	private static final int SOFT = 0xFF6A5236;

	private BoardData data;
	private int tab;
	private int scroll;
	private String neighborNotice;
	private int left;
	private int top;
	private float uiScale = 1.0F;
	private int selected = -1;
	/** Stable identity for the resident whose report is shown in the Talk tab. */
	private String talkResidentId;
	private static int talkRequestSequence;
	/** Latest request accepted by this screen; older reports must not replace the selected resident's data. */
	private int talkRequestId;
	private int neighbor = -1;
	private static int networkActionRequestSequence;
	private int pendingNetworkActionRequestId;
	private int pendingNetworkActionTicks;
	private String pendingNetworkAction;
	private int category;
	public static List<String> stockRows = List.of();
	private String stockItem = "", stockType = "material";
	private int stockCategory;
	private StockSlider stockSlider;
	private Button stockFilter, stockSave, stockReset;
	private static final String[] STOCK_TYPES = {"material", "food", "tools"};
	private static final String[] STOCK_NAMES = {"Baustoffe", "Lebensmittel", "Werkzeuge"};
	private int person = -1;
	private Button filterCategory, filterPerson, cancelBuild, cancelVillager, renameButton, renameOk, demolishButton, zoneButton, moveButton;
	/** Auf der Karte angeklicktes Gebaeude (Zeile aus den {@code @b}-Eintraegen), -1 = keins. */
	private int pickedBuilding = -1;
	/** Auf der Karte ausgewaehlte benannte Markierung, falls sie kein abreißbares Gebaeude ist. */
	private int pickedMapMarker = -1;
	private int pickedMapMarkerCode;
	private String pickedNeighborMapDimension;
	private long pickedNeighborMapBell;
	private boolean movePending;
	private int mapCell = MAP_BASE_CELL, mapPanX, mapPanY;
	private boolean draggingMap;
	private boolean drawingZone;
	private boolean zoneTool;
	private int zoneKind;
	private int drawingZoneKind;
	private int zoneStartX, zoneStartZ, zoneEndX, zoneEndZ;
	private double zoneDragStartPointerX, zoneDragStartPointerY, zonePointerX, zonePointerY;
	private boolean zoneClipped;
	private String zoneNotice;
	private int pendingZoneRequestId;
	private int pendingZoneRequestTicks;
	private de.villagerplus.net.SetZone zonePreviewRequest;
	private boolean zonePreviewAccepted;
	private double dragCarryX, dragCarryY;
	private net.minecraft.client.gui.components.EditBox renameBox;
	private final List<Button> networkButtons = new ArrayList<>();
	/** Allows the client check to exercise real button callbacks without sending a fixture action to the world. */
	private java.util.function.Consumer<NetworkAction> networkActionSender = ClientPlayNetworking::send;
	/** Lets the map check exercise the actual zone drag dispatch without changing the connected world. */
	private java.util.function.Consumer<de.villagerplus.net.SetZone> zoneActionSender = ClientPlayNetworking::send;
	private Button approveProjectButton, mapCardButton;
	private final Map<String, QuotaSlider> quotaSliders = new LinkedHashMap<>();
	private ResidentJobSlider residentJobSlider;
	private Button applyResidentJob;

	static {
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.SetZone.Result.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().gui.screen() instanceof BoardScreen open && open.acceptZoneResult(payload)) return;
			if (context.client().player != null) {
				context.client().player.sendSystemMessage(de.villagerplus.village.VillageMessages.format(Component.literal(
					zoneResultNotice(payload.success(), payload.message()))));
			}
		}));
	}

	private void networkVisibility() {
		if (stockFilter != null) stockFilter.visible = tab == 7;
		if (stockSlider != null) { stockSlider.visible = tab == 7; stockSlider.active = !stockItem.isEmpty(); }
		if (stockSave != null) { stockSave.visible = tab == 7; stockSave.active = !stockItem.isEmpty(); }
		if (stockReset != null) { stockReset.visible = tab == 7; stockReset.active = !stockItem.isEmpty(); }
		if (cancelBuild != null) cancelBuild.visible = tab == 2;
		String[] picked = selectedBuildingRow();
		if (demolishButton != null) {
			demolishButton.visible = tab == 0;
			demolishButton.active = picked != null && !picked[6].equals("1");
			demolishButton.setMessage(Component.literal(picked != null && picked[6].equals("1") ? "Abriss läuft" : "Abrissauftrag"));
		}
		if (zoneButton != null) { zoneButton.visible = tab == 0; zoneButton.active = tab == 0 && pendingZoneRequestId == 0; }
		if (moveButton != null) {
			moveButton.visible = tab == 0;
			moveButton.active = picked != null || movePending;
			moveButton.setMessage(Component.literal(movePending ? "Abbrechen" : "Versetzen"));
		}
		if (cancelVillager != null) { cancelVillager.visible = tab == 1; cancelVillager.active = selected >= 0 && selected < data.residents().size(); }
		if (filterCategory != null) filterCategory.visible = tab == 5;
		if (filterPerson != null) filterPerson.visible = tab == 5;
		String[] selectedNeighbor = selectedNeighborRow();
		String currentDimension = clientDimensionId();
		boolean linkedAndLoaded = selectedNeighbor != null && hasNeighborIdentity(selectedNeighbor)
			&& selectedNeighbor.length >= 11 && selectedNeighbor[6].equals("1") && selectedNeighbor[10].equals("1");
		for (int i = 0; i < networkButtons.size(); i++) {
			Button b = networkButtons.get(i);
			b.visible = tab == 4;
			// Keep unavailable actions clickable so sendNeighborAction can explain the exact
			// missing link/load condition in the panel instead of silently ignoring the click.
			b.active = tab == 4 && pendingNetworkAction == null;
		}
		if (approveProjectButton != null) {
			approveProjectButton.visible = tab == 4;
			boolean ownVote = selectedNeighbor != null && selectedNeighbor.length >= 9 && selectedNeighbor[8].startsWith("dein Ja");
			boolean validLocalLink = hasNeighborIdentity(selectedNeighbor);
			String approvalFailure = selectedNeighbor == null ? null
				: neighborActionFailure("approve", selectedNeighbor, currentDimension);
			approveProjectButton.active = tab == 4 && pendingNetworkAction == null && approvalFailure == null
				&& selectedNeighbor != null;
			String approvalCaption;
			if (!validLocalLink) {
				approvalCaption = selectedNeighbor == null ? "Nachbar auswählen" : "Verbindung aktualisieren";
			} else if (approvalFailure != null) {
				approvalCaption = "Andere Dimension";
			} else if (ownVote) {
				approvalCaption = "Eigenes Ja zurückziehen";
			} else if (!linkedAndLoaded) {
				approvalCaption = "Lokal zustimmen";
			} else {
				approvalCaption = "Großprojekt zustimmen";
			}
			approveProjectButton.setMessage(Component.literal(approvalCaption));
		}
		if (mapCardButton != null) { mapCardButton.visible = tab == 4; mapCardButton.active = tab == 4 && pendingNetworkAction == null; }
		if (residentJobSlider != null) {
			residentJobSlider.visible = tab == 1 && selected >= 0 && selected < data.residents().size();
			residentJobSlider.active = residentJobSlider.visible;
		}
		if (applyResidentJob != null) {
			applyResidentJob.visible = tab == 1 && selected >= 0 && selected < data.residents().size();
			applyResidentJob.active = applyResidentJob.visible;
		}
		for (Map.Entry<String, QuotaSlider> entry : quotaSliders.entrySet()) {
			entry.getValue().visible = tab == 6;
			entry.getValue().active = tab == 6 && !entry.getKey().equals("none");
		}
	}

	static float panelUiScale(int screenWidth, int screenHeight) {
		float scale = Math.min(1.0F, Math.min((screenWidth - 12.0F) / (W + 8.0F), (screenHeight - 12.0F) / (H + 8.0F)));
		return Math.max(0.1F, scale);
	}

	static int[] panelOrigin(int screenWidth, int screenHeight, float scale) {
		if (scale <= 0.0F) throw new IllegalArgumentException("Panel scale must be positive");
		return new int[]{(int)(screenWidth / scale - W) / 2, (int)(screenHeight / scale - H) / 2};
	}

	/** Shared logical geometry for map rendering and every map pointer hit test. */
	static int[] mapViewportBounds(int panelLeft, int panelTop) {
		return new int[]{panelLeft + MAP_VIEW_LEFT_INSET, panelTop + MAP_VIEW_TOP_INSET, MAP_VIEW, MAP_VIEW};
	}

	/** Decorative frame bounds, intentionally outside the interactive scroll/selection viewport. */
	static int[] mapFrameBounds(int panelLeft, int panelTop) {
		int[] viewport = mapViewportBounds(panelLeft, panelTop);
		return new int[]{viewport[0] - MAP_FRAME_PADDING, viewport[1] - MAP_FRAME_PADDING,
			viewport[2] + MAP_FRAME_PADDING * 2, viewport[3] + MAP_FRAME_PADDING + MAP_FRAME_BOTTOM_PADDING};
	}

	static int[] neighborActionBounds(int panelLeft, int panelTop, int index) {
		if (index < 0 || index >= NETWORK_ACTIONS.length) throw new IllegalArgumentException("Invalid neighbor action index: " + index);
		return new int[]{panelLeft + 10 + index * 100, panelTop + NEIGHBOR_ACTION_ROW_Y, 97, 18};
	}

	/** Footer button indices: approve, card, close, live. */
	static int[] footerButtonBounds(int panelLeft, int panelTop, int index) {
		int x, width;
		switch (index) {
			case 0 -> { x = panelLeft + 84; width = 144; }
			case 1 -> { x = panelLeft + 230; width = 126; }
			case 2 -> { x = panelLeft + 8; width = 70; }
			case 3 -> { x = panelLeft + W - 98; width = 90; }
			default -> throw new IllegalArgumentException("Invalid footer button index: " + index);
		}
		return new int[]{x, panelTop + FOOTER_BUTTON_Y, width, 18};
	}

	static int[] tabButtonBounds(int panelLeft, int panelTop, int index) {
		if (index < 0 || index >= TABS.length) throw new IllegalArgumentException("Invalid tab index: " + index);
		int width = (W - 16 - (TABS.length - 1) * 4) / TABS.length;
		return new int[]{panelLeft + 8 + index * (width + 4), panelTop + 24, width, 18};
	}

	static int tabCount() { return TABS.length; }

	static boolean neighborControlsFit(int panelLeft, int panelTop, float scale, int screenWidth, int screenHeight) {
		if (scale <= 0.0F) return false;
		if (!neighborActionHitboxesDistinct(panelLeft, panelTop)) return false;
		for (int i = 0; i < NETWORK_ACTIONS.length; i++) {
			if (!scaledBoundsFit(neighborActionBounds(panelLeft, panelTop, i), scale, screenWidth, screenHeight)) return false;
		}
		for (int i = 0; i < 4; i++) {
			if (!scaledBoundsFit(footerButtonBounds(panelLeft, panelTop, i), scale, screenWidth, screenHeight)) return false;
		}
		return true;
	}

	/** Keeps each relationship action's click target separate from its siblings and the footer actions. */
	static boolean neighborActionHitboxesDistinct(int panelLeft, int panelTop) {
		for (int i = 0; i < NETWORK_ACTIONS.length; i++) {
			int[] action = neighborActionBounds(panelLeft, panelTop, i);
			for (int j = i + 1; j < NETWORK_ACTIONS.length; j++) {
				if (boundsOverlap(action, neighborActionBounds(panelLeft, panelTop, j))) return false;
			}
			for (int footer = 0; footer < 4; footer++) {
				if (boundsOverlap(action, footerButtonBounds(panelLeft, panelTop, footer))) return false;
			}
		}
		return true;
	}

	private static boolean boundsOverlap(int[] first, int[] second) {
		return first[0] < second[0] + second[2] && first[0] + first[2] > second[0]
			&& first[1] < second[1] + second[3] && first[1] + first[3] > second[1];
	}

	private static boolean scaledBoundsFit(int[] bounds, float scale, int screenWidth, int screenHeight) {
		return bounds[0] * scale >= 0 && bounds[1] * scale >= 0
			&& (bounds[0] + bounds[2]) * scale <= screenWidth
			&& (bounds[1] + bounds[3]) * scale <= screenHeight;
	}

	public BoardScreen(BoardData data) {
		super(Component.literal("Dorftafel"));
		this.data = data;
	}

	/** Zahl der automatischen Abfragen, deren Antwort noch aussteht (eine Antwort nach dem Schliessen darf die Tafel nicht wieder oeffnen). */
	public static int autoPending;
	/** Arbeitsmarkt-Zeilen {@code schluessel|name|anzahl|soll|standard} (kommen als eigenes Paket zusammen mit den Tafeldaten). */
	public static java.util.List<String> quota = java.util.List.of();
	private int liveTicks;
	private int decay;
	private long updatedAt = System.currentTimeMillis();

	/** Live-Tracker: solange die Tafel offen ist, fragt sie alle 2 Sekunden die aktuellen Daten ab (kein manuelles Aktualisieren noetig). */
	@Override
	public void tick() {
		super.tick();
		ensureQuotaSliders();
		if (pendingZoneRequestId != 0 && ++pendingZoneRequestTicks >= 100) {
			pendingZoneRequestId = 0;
			pendingZoneRequestTicks = 0;
			zonePreviewRequest = null;
			zonePreviewAccepted = false;
			zoneNotice = "✕ Keine Serverantwort erhalten. Prüfe die Verbindung und versuche es erneut.";
			networkVisibility();
		}
		if (zonePreviewAccepted && zonePreviewRequest != null && zonePreviewPresentInRows(quota, zonePreviewRequest)) {
			zonePreviewRequest = null;
			zonePreviewAccepted = false;
		}
		if (pendingNetworkAction != null && ++pendingNetworkActionTicks >= 100) {
			pendingNetworkAction = null;
			pendingNetworkActionRequestId = 0;
			pendingNetworkActionTicks = 0;
			neighborNotice = "✕ Keine Serverantwort erhalten. Prüfe die Verbindung und versuche es erneut.";
			networkVisibility();
		}
		if (++liveTicks >= 40) {
			liveTicks = 0;
			if (++decay >= 5 && autoPending > 0) { autoPending--; decay = 0; } // verlorene Antworten (Spieler zu weit weg) verstopfen den Zaehler nicht
			if (autoPending < 3) {
				autoPending++;
				if (tab == 8 && talkResidentId != null) requestTalk();
				else ClientPlayNetworking.send(new BoardRequest(data.board()));
				if (tab == 7) requestStock();
			}
		}
	}

	/** Frische Daten vom Server (Aktualisieren, nach einer Berufszuteilung): Reiter und Auswahl bleiben. */
	public void update(BoardData fresh) {
		if (tab == 8 && talkRequestId > 0 && fresh.talkRequestId() != talkRequestId) return;
		String[] selectedNeighbor = selectedNeighborRow();
		this.data = fresh;
		this.updatedAt = System.currentTimeMillis();
		if (pickedMapMarker >= 0 && selectedMapMarkerCode() == 0) clearMapMarkerSelection();
		if (selected >= fresh.residents().size()) selected = -1;
		if (person >= fresh.residents().size()) person = -1;
		if (talkResidentId != null && fresh.residents().stream().noneMatch(row -> talkResidentId.equals(residentIdentity(row)))) {
			talkResidentId = null;
			talkRequestId = 0;
		}
		List<String> refreshedRelationships = relationshipRows(fresh.neighbors(), fresh.partners());
		neighbor = neighborIndex(refreshedRelationships, selectedNeighbor);
		if (neighbor >= 0 && neighbor < refreshedRelationships.size()) {
			String[] row = refreshedRelationships.get(neighbor).split("\\|", -1);
			boolean hasPending = row.length >= 15 && !row[14].isBlank();
			if (hasPending || neighborNotice != null && neighborNotice.startsWith("⏳ ") && neighborNotice.contains("vorgemerkt")) {
				neighborNotice = relationshipAvailabilityNotice(row, clientDimensionId());
			}
		}
		networkVisibility();
	}

	@Override
	protected void init() {
		uiScale = panelUiScale(width, height);
		int[] panelOrigin = panelOrigin(width, height, uiScale);
		left = panelOrigin[0];
		top = panelOrigin[1];
		clampMap();
		for (int i = 0; i < TABS.length; i++) {
			final int idx = i;
			int[] tabBounds = tabButtonBounds(left, top, i);
			addRenderableWidget(Button.builder(Component.literal(TABS[i]), b -> {
				if (idx == 5 && selected >= 0) { person = selected; if (filterPerson != null) filterPerson.setMessage(Component.literal("Bewohner: "+data.residents().get(person).split("\\|")[0])); }
				if (idx == 8 && selected >= 0 && selected < data.residents().size()) {
					talkResidentId = residentIdentity(data.residents().get(selected));
				}
				if (idx != 0) movePending = false;
				tab = idx;
				if (tab == 8) requestTalk();
				if (tab == 7) requestStock();
				scroll = 0;
				selected = -1;
				networkVisibility();
			}).bounds(tabBounds[0], tabBounds[1], tabBounds[2], tabBounds[3]).build());
		}
		filterCategory = addRenderableWidget(Button.builder(Component.literal("Kategorie: Alle"),b->{category=(category+1)%4;scroll=0;b.setMessage(Component.literal("Kategorie: "+new String[]{"Alle","Handel","Befinden","Bauen"}[category]));}).bounds(left+10,top+50,150,18).build());
        filterPerson = addRenderableWidget(Button.builder(Component.literal("Bewohner: Alle"),b->{person++;if(person>=data.residents().size())person=-1;scroll=0;b.setMessage(Component.literal("Bewohner: "+(person<0?"Alle":data.residents().get(person).split("\\|")[0])));}).bounds(left+166,top+50,W-176,18).build());
        networkButtons.clear();
		String[] captions = {"Waren tauschen", "Route an/aus", "Gäste einladen", "Gruß senden"};
		for (int i = 0; i < NETWORK_ACTIONS.length; i++) {
			final String action = NETWORK_ACTIONS[i];
			int[] bounds = neighborActionBounds(left, top, i);
			Button b = Button.builder(Component.literal(captions[i]), click -> sendNeighborAction(action))
				.bounds(bounds[0], bounds[1], bounds[2], bounds[3]).build();
			networkButtons.add(addRenderableWidget(b));
		}
		int[] approveBounds = footerButtonBounds(left, top, 0);
		approveProjectButton = addRenderableWidget(Button.builder(Component.literal("Großprojekt zustimmen"), click -> sendNeighborAction("approve"))
			.bounds(approveBounds[0], approveBounds[1], approveBounds[2], approveBounds[3]).build());
		int[] cardBounds = footerButtonBounds(left, top, 1);
		mapCardButton = addRenderableWidget(Button.builder(Component.literal("Dorfkarte mitnehmen"), click -> sendNeighborAction("card"))
			.bounds(cardBounds[0], cardBounds[1], cardBounds[2], cardBounds[3]).build());
		renameBox = new net.minecraft.client.gui.components.EditBox(font, left + 6, top + 2, 230, 16, Component.literal("Dorfname"));
		renameBox.setMaxLength(24);
		renameBox.visible = false;
		addRenderableWidget(renameBox);
		renameOk = addRenderableWidget(Button.builder(Component.literal("OK"), b -> {
			ClientPlayNetworking.send(new de.villagerplus.net.RenameVillage(data.board(), renameBox.getValue()));
			renameBox.visible = false;
			renameOk.visible = false;
			renameButton.visible = true;
		}).bounds(left + 240, top + 1, 40, 18).build());
		renameOk.visible = false;
		renameButton = addRenderableWidget(Button.builder(Component.literal("Umbenennen ✎"), b -> {
			renameBox.setValue(data.village());
			renameBox.visible = true;
			renameOk.visible = true;
			renameButton.visible = false;
			setFocused(renameBox);
		}).bounds(left + RENAME_BUTTON_X, top + 1, 100, 18).build());
		demolishButton = addRenderableWidget(Button.builder(Component.literal("Abriss"), b -> {
			String[] r = selectedBuildingRow();
			if (r != null && !r[6].equals("1")) {
				ClientPlayNetworking.send(new de.villagerplus.net.BuildingAction(data.board(), new net.minecraft.core.BlockPos(Integer.parseInt(r[3]), Integer.parseInt(r[4]), Integer.parseInt(r[5])), "demolish"));
			}
		}).bounds(left + 12, top + MAP_ACTION_ROW_Y, 86, 18).build());
		zoneButton = addRenderableWidget(Button.builder(Component.literal("Zone zeichnen"), b -> {
			if (!zoneTool) { zoneTool = true; b.setMessage(Component.literal(zoneButtonText(zoneKind))); }
			else { zoneKind = (zoneKind + 1) % (ZONE_DELETE_KIND + 1); b.setMessage(Component.literal(zoneButtonText(zoneKind))); }
		}).bounds(left + 100, top + MAP_ACTION_ROW_Y, 94, 18).build());
		moveButton = addRenderableWidget(Button.builder(Component.literal("Versetzen"), b -> {
			if (movePending) {
				movePending = false;
			} else if (selectedBuildingRow() != null) {
				movePending = true;
			}
			networkVisibility();
		}).bounds(left + 200, top + MAP_ACTION_ROW_Y, 100, 18).build());
		cancelBuild = addRenderableWidget(Button.builder(Component.literal("Bau abbrechen"), b -> ClientPlayNetworking.send(new de.villagerplus.net.CancelTask(data.board(), "build", new UUID(0L, 0L))))
			.bounds(left + 84, top + H - 24, 110, 18).build());
		cancelVillager = addRenderableWidget(Button.builder(Component.literal("Aufgabe abbrechen"), b -> {
			if (selected >= 0 && selected < data.residents().size()) {
				String[] p = data.residents().get(selected).split("\\|");
				if (p.length > 5) ClientPlayNetworking.send(new de.villagerplus.net.CancelTask(data.board(), "villager", UUID.fromString(p[5])));
			}
		}).bounds(left + 84, top + H - 24, 120, 18).build());
		residentJobSlider = addRenderableWidget(new ResidentJobSlider(left + 12, top + H - 30 - 52 + 25, 288));
		applyResidentJob = addRenderableWidget(Button.builder(Component.literal("Zuteilen"), b -> assignSelectedResidentJob())
			.bounds(left + 308, top + H - 30 - 52 + 25, 138, 20).build());
		stockFilter = addRenderableWidget(Button.builder(Component.literal("Lager: " + STOCK_NAMES[stockCategory]), b -> {
			stockCategory = (stockCategory + 1) % STOCK_TYPES.length; stockItem = ""; scroll = 0;
			b.setMessage(Component.literal("Lager: " + STOCK_NAMES[stockCategory])); networkVisibility(); requestStock();
		}).bounds(left + 12, top + 50, 150, 18).build());
		stockReset = addRenderableWidget(Button.builder(Component.literal("Unbegrenzt"), b -> {
			if (!stockItem.isEmpty()) ClientPlayNetworking.send(new de.villagerplus.net.SetStockPolicy(data.board(), stockItem, stockType, 0, true));
		}).bounds(left + W - 132, top + 50, 120, 18).build());
		stockSlider = addRenderableWidget(new StockSlider(left + 12, top + 225, W - 162));
		stockSave = addRenderableWidget(Button.builder(Component.literal("Soll übernehmen"), b -> {
			if (!stockItem.isEmpty()) ClientPlayNetworking.send(new de.villagerplus.net.SetStockPolicy(data.board(), stockItem, stockType, stockSlider.target(), false));
		}).bounds(left + W - 142, top + 225, 130, 20).build());
		requestStock();
		ensureQuotaSliders();

		networkVisibility();
		int[] liveBounds = footerButtonBounds(left, top, 3);
		addRenderableWidget(Button.builder(Component.literal("● Live"), b -> {
			if (tab == 8) requestTalk();
			else ClientPlayNetworking.send(new BoardRequest(data.board()));
		})
			.bounds(liveBounds[0], liveBounds[1], liveBounds[2], liveBounds[3]).build());
		int[] closeBounds = footerButtonBounds(left, top, 2);
		addRenderableWidget(Button.builder(Component.literal("Schließen"), b -> onClose())
			.bounds(closeBounds[0], closeBounds[1], closeBounds[2], closeBounds[3]).build());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		x /= uiScale; y /= uiScale;
		if (drawingZone) {
			if (insideMap(x, y)) zoneNotice = "Zoom während der Zonenauswahl pausiert.";
			return true;
		}
		if (insideMap(x, y)) {
			clampMap();
			int next = Math.max(MAP_BASE_CELL, Math.min(12, mapCell + (int)Math.signum(dy) * 2));
			double cx = x - left - 14, cy = y - top - 54;
			mapPanX = (int)Math.round(cx - (cx - mapPanX) * next / mapCell);
			mapPanY = (int)Math.round(cy - (cy - mapPanY) * next / mapCell);
			mapCell = next; clampMap(); return true;
		}
		scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
		return true;
	}



	private MouseButtonEvent scaledMouse(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo());
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(x / uiScale, y / uiScale);
	}

	private boolean insideMap(double x, double y) {
		int[] viewport = mapViewportBounds(left, top);
		return tab == 0 && mapViewportContains(x, y, viewport[0], viewport[1], viewport[2]);
	}

	/** A zone tool yields a plain click to the existing selectable map targets. */
	private boolean hasSelectableMapTarget(double x, double y) {
		if (!insideMap(x, y)) return false;
		int[] cell = mapGridCell(x, y);
		if (cell == null) return false;
		if (nearbySelectableMapMarker(cell[0], cell[1]) >= 0) return true;

		int[] origin = mapOrigin();
		if (origin == null) return false;
		if (neighborMapPinAtPointer(x, y, origin) != null) return true;
		int[] viewport = mapViewportBounds(left, top);
		int pixelX = (int)x - viewport[0] - mapPanX;
		int pixelY = (int)y - viewport[1] - mapPanY;
		int worldX = origin[0] + mapPixelToWorldOffset(pixelX, data.cellSize(), mapCell);
		int worldZ = origin[1] + mapPixelToWorldOffset(pixelY, data.cellSize(), mapCell);
		for (String[] building : buildingRows()) {
			long dx = (long)Integer.parseInt(building[3]) - worldX;
			long dz = (long)Integer.parseInt(building[5]) - worldZ;
			if (dx * dx + dz * dz < 49L) return true;
		}
		return false;
	}

	private NeighborMapPin neighborMapPinAtPointer(double x, double y, int[] origin) {
		if (origin == null) return null;
		net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) return null;
		List<NeighborMapPin> pins = neighborMapPins(data, level.dimension().identifier().toString(), origin[0], origin[1]);
		int[] viewport = mapViewportBounds(left, top);
		return neighborMapPinAt(pins, x, y, viewport[0], viewport[1], viewport[2],
			mapPanX, mapPanY, mapCell, origin[0], origin[1], data.cellSize());
	}

	static boolean zoneReleaseCommits(int button) {
		// Once a left drag starts on the map, its endpoint is safely clamped to the viewport.
		// Other buttons remain explicit cancellation gestures.
		return button == 0;
	}

	/**
	 * MouseButtonEvent normally carries the current pointer, but some dispatch paths retain the press
	 * coordinates while still supplying movement deltas. Keep the same pixel-space pointer either way.
	 */
	static double[] resolveZoneDragPointer(double startX, double startY, double previousX, double previousY,
		double eventX, double eventY, double deltaX, double deltaY) {
		if (!Double.isFinite(startX) || !Double.isFinite(startY) || !Double.isFinite(previousX)
			|| !Double.isFinite(previousY) || !Double.isFinite(eventX) || !Double.isFinite(eventY)
			|| !Double.isFinite(deltaX) || !Double.isFinite(deltaY)) return null;
		boolean eventStillAtPress = Math.abs(eventX - startX) < 0.01 && Math.abs(eventY - startY) < 0.01;
		return eventStillAtPress
			? new double[]{previousX + deltaX, previousY + deltaY}
			: new double[]{eventX, eventY};
	}

	/** Uses the tracked drag endpoint when a release callback still contains the original press position. */
	static double[] resolveZoneReleasePointer(double startX, double startY, double trackedX, double trackedY,
		double eventX, double eventY) {
		if (!Double.isFinite(startX) || !Double.isFinite(startY) || !Double.isFinite(trackedX)
			|| !Double.isFinite(trackedY) || !Double.isFinite(eventX) || !Double.isFinite(eventY)) return null;
		boolean eventStillAtPress = Math.abs(eventX - startX) < 0.01 && Math.abs(eventY - startY) < 0.01;
		boolean dragMoved = Math.abs(trackedX - startX) >= 0.01 || Math.abs(trackedY - startY) >= 0.01;
		return eventStillAtPress && dragMoved ? new double[]{trackedX, trackedY} : new double[]{eventX, eventY};
	}

	static boolean zonePreviewPresentInRows(List<String> rows, de.villagerplus.net.SetZone request) {
		if (rows == null || request == null || request.delete() || request.kind() == null) return false;
		int minX = Math.min(request.minX(), request.maxX()), maxX = Math.max(request.minX(), request.maxX());
		int minZ = Math.min(request.minZ(), request.maxZ()), maxZ = Math.max(request.minZ(), request.maxZ());
		for (String row : rows) {
			if (row == null || !row.startsWith("@z|")) continue;
			String[] fields = row.split("\\|", -1);
			if (fields.length < 7 || !request.kind().equals(fields[2])) continue;
			try {
				if (Integer.parseInt(fields[3]) == minX && Integer.parseInt(fields[4]) == minZ
					&& Integer.parseInt(fields[5]) == maxX && Integer.parseInt(fields[6]) == maxZ) return true;
			} catch (NumberFormatException ignored) { }
		}
		return false;
	}

	static int nextZoneRequestId(int current) {
		return current <= 0 || current == Integer.MAX_VALUE ? 1 : current + 1;
	}

	static boolean matchesZoneResult(net.minecraft.core.BlockPos board, int requestId, de.villagerplus.net.SetZone.Result result) {
		return result != null && requestId > 0 && result.requestId() == requestId && board != null && board.equals(result.board());
	}

	static String zoneResultNotice(boolean success, String message) {
		String details = message == null || message.isBlank() ? "Der Server hat keine Begründung übermittelt." : message;
		return (success ? "✓ " : "✕ ") + details;
	}

	static String zoneRequestFailure(de.villagerplus.net.SetZone request) {
		if (request == null || request.board() == null) return "Die Tafelposition fehlt.";
		if (request.requestId() <= 0) return "Die Anfragekennung ist ungültig.";
		if (request.delete()) {
			return request.kind() != null && !request.kind().isBlank() && request.kind().length() <= 36
				? null : "Die Kennung der zu löschenden Zone ist ungültig.";
		}
		if (!("farm".equals(request.kind()) || "housing".equals(request.kind())
			|| "work".equals(request.kind()) || "pasture".equals(request.kind()))) return "Die Zonenkategorie ist ungültig.";
		long width = (long)request.maxX() - request.minX() + 1L;
		long height = (long)request.maxZ() - request.minZ() + 1L;
		if (width <= 0 || height <= 0 || width > ZONE_MAX_AREA || height > ZONE_MAX_AREA
			|| width * height > ZONE_MAX_AREA) return "Die Zonenauswahl überschreitet 4096 Blöcke.";
		return null;
	}

	public boolean acceptZoneResult(de.villagerplus.net.SetZone.Result result) {
		if (pendingZoneRequestId == 0 || !matchesZoneResult(data.board(), pendingZoneRequestId, result)) return false;
		pendingZoneRequestId = 0;
		pendingZoneRequestTicks = 0;
		zonePreviewAccepted = result.success() && zonePreviewRequest != null && !zonePreviewRequest.delete();
		if (!zonePreviewAccepted) zonePreviewRequest = null;
		zoneNotice = zoneResultNotice(result.success(), result.message());
		networkVisibility();
		return true;
	}

	private boolean submitZone(int minX, int minZ, int maxX, int maxZ, String kind, boolean delete) {
		if (pendingZoneRequestId != 0) {
			zoneNotice = "Die vorige Zonenänderung wird noch vom Server geprüft.";
			return false;
		}
		int requestId = nextZoneRequestId(zoneRequestSequence);
		zoneRequestSequence = requestId;
		de.villagerplus.net.SetZone request = new de.villagerplus.net.SetZone(data.board(), minX, minZ, maxX, maxZ, kind, delete, requestId);
		String invalid = zoneRequestFailure(request);
		if (invalid != null) {
			zoneNotice = "Zone nicht gesendet: " + invalid;
			return false;
		}
		pendingZoneRequestId = requestId;
		pendingZoneRequestTicks = 0;
		zonePreviewRequest = delete ? null : request;
		zonePreviewAccepted = false;
		zoneNotice = "Anfrage gesendet · der Server prüft die Zone …";
		networkVisibility();
		try {
			zoneActionSender.accept(request);
			return true;
		} catch (RuntimeException failure) {
			pendingZoneRequestId = 0;
			pendingZoneRequestTicks = 0;
			zonePreviewRequest = null;
			zonePreviewAccepted = false;
			zoneNotice = "✕ Zone konnte nicht gesendet werden. Verbindung prüfen und erneut versuchen.";
			networkVisibility();
			return false;
		}
	}

	static boolean mapViewportContains(double screenX, double screenY, int mapLeft, int mapTop, int viewportSize) {
		return viewportSize > 0 && screenX >= mapLeft && screenY >= mapTop
			&& screenX < (double)mapLeft + viewportSize && screenY < (double)mapTop + viewportSize;
	}

	/**
	 * Converts a panel-space pointer into a visible map cell. Starts reject blank map padding;
	 * active drags clamp the pointer to the viewport, then clamp to the nearest actual grid cell.
	 */
	static int[] mapViewportGridCell(double screenX, double screenY, int mapLeft, int mapTop,
		int viewportSize, int panX, int panY, int pixelsPerCell, int gridSize, boolean clampEndpoint) {
		if (!Double.isFinite(screenX) || !Double.isFinite(screenY) || viewportSize <= 0
			|| pixelsPerCell <= 0 || gridSize <= 0) return null;
		if (!clampEndpoint && !mapViewportContains(screenX, screenY, mapLeft, mapTop, viewportSize)) return null;
		double pointerX = screenX, pointerY = screenY;
		if (clampEndpoint) {
			// The viewport is half-open. Clamp to its final drawable pixel so an edge or
			// slightly out-of-bounds release cannot turn into a missing endpoint.
			pointerX = Math.max(mapLeft, Math.min(pointerX, (double)mapLeft + viewportSize - 1.0));
			pointerY = Math.max(mapTop, Math.min(pointerY, (double)mapTop + viewportSize - 1.0));
		}
		long pixelX = (long)Math.floor(pointerX) - mapLeft - panX;
		long pixelY = (long)Math.floor(pointerY) - mapTop - panY;
		long col = Math.floorDiv(pixelX, (long)pixelsPerCell);
		long row = Math.floorDiv(pixelY, (long)pixelsPerCell);
		if (clampEndpoint) {
			col = Math.max(0L, Math.min((long)gridSize - 1L, col));
			row = Math.max(0L, Math.min((long)gridSize - 1L, row));
		} else if (col < 0 || row < 0 || col >= gridSize || row >= gridSize) {
			return null;
		}
		return new int[]{(int)col, (int)row};
	}

	/** Whole map-cell selection after enforcing the same safe radius and block-area cap as the server. */
	static ZoneSelection boundedZoneSelection(int originX, int originZ, int cellSize, int gridSize,
		int bellX, int bellZ, int startCol, int startRow, int endCol, int endRow) {
		if (cellSize <= 0 || gridSize <= 0) return null;
		// The map only stores the bell's cell, not its exact block. Keep one cell of headroom so
		// every submitted block remains inside the server's exact ±64-block check.
		int safeRadius = Math.max(0, de.villagerplus.village.ZonePolicy.MAX_RADIUS - cellSize);
		int minCol = Math.max(0, ceilDiv((long)bellX - safeRadius - originX, cellSize));
		int minRow = Math.max(0, ceilDiv((long)bellZ - safeRadius - originZ, cellSize));
		int maxCol = Math.toIntExact(Math.min((long)gridSize - 1L,
			Math.floorDiv((long)bellX + safeRadius - (cellSize - 1L) - originX, cellSize)));
		int maxRow = Math.toIntExact(Math.min((long)gridSize - 1L,
			Math.floorDiv((long)bellZ + safeRadius - (cellSize - 1L) - originZ, cellSize)));
		if (minCol > maxCol || minRow > maxRow || startCol < minCol || startCol > maxCol
			|| startRow < minRow || startRow > maxRow) return null;

		int requestedCol = endCol, requestedRow = endRow;
		int boundedCol = Math.max(minCol, Math.min(maxCol, endCol));
		int boundedRow = Math.max(minRow, Math.min(maxRow, endRow));
		int width = Math.abs(boundedCol - startCol) + 1;
		int height = Math.abs(boundedRow - startRow) + 1;
		int originalMinCol = Math.min(startCol, boundedCol), originalMaxCol = Math.max(startCol, boundedCol);
		int originalMinRow = Math.min(startRow, boundedRow), originalMaxRow = Math.max(startRow, boundedRow);
		int selectedMinCol = originalMinCol, selectedMaxCol = originalMaxCol;
		int selectedMinRow = originalMinRow, selectedMaxRow = originalMaxRow;
		long cellArea = (long)cellSize * cellSize;
		long maxCells = ZONE_MAX_AREA / cellArea;
		if (maxCells < 1L) return null;
		if ((long)width * height > maxCells) {
			double scale = Math.sqrt(maxCells / ((double)width * height));
			int boundedWidth = Math.max(1, (int)Math.floor(width * scale));
			int boundedHeight = Math.max(1, (int)Math.floor(height * scale));
			while ((long)boundedWidth * boundedHeight > maxCells) {
				if (boundedWidth >= boundedHeight && boundedWidth > 1) boundedWidth--;
				else if (boundedHeight > 1) boundedHeight--;
				else return null;
			}
			// Keep the clicked cell fixed as the drag anchor. Trimming both ends around the
			// rectangle's center makes the preview appear to slide away from the pointer.
			if (boundedCol < startCol) selectedMinCol = startCol - boundedWidth + 1;
			else selectedMaxCol = startCol + boundedWidth - 1;
			if (boundedRow < startRow) selectedMinRow = startRow - boundedHeight + 1;
			else selectedMaxRow = startRow + boundedHeight - 1;
		}
		return new ZoneSelection(selectedMinCol, selectedMinRow, selectedMaxCol, selectedMaxRow,
			requestedCol != boundedCol || requestedRow != boundedRow || selectedMinCol != originalMinCol
				|| selectedMaxCol != originalMaxCol || selectedMinRow != originalMinRow || selectedMaxRow != originalMaxRow);
	}

	private static int ceilDiv(long value, int divisor) {
		return Math.toIntExact(-Math.floorDiv(-value, (long)divisor));
	}

	record ZoneSelection(int minCol, int minRow, int maxCol, int maxRow, boolean clipped) { }

	static String zoneKindId(int kind) {
		if (kind < 0 || kind >= ZONE_KIND_IDS.length) throw new IllegalArgumentException("Invalid zone kind: " + kind);
		return ZONE_KIND_IDS[kind];
	}

	static String zoneButtonText(int kind) {
		if (kind == ZONE_DELETE_KIND) return "Zone löschen";
		if (kind < 0 || kind >= ZONE_KIND_NAMES.length) throw new IllegalArgumentException("Invalid zone kind: " + kind);
		return "Zone: " + ZONE_KIND_NAMES[kind];
	}

	static String zonePreviewText(int kind) {
		if (kind < 0 || kind >= ZONE_KIND_NAMES.length) throw new IllegalArgumentException("Invalid zone kind: " + kind);
		return "Zonenvorschau: " + ZONE_KIND_NAMES[kind];
	}

	static String zonePreviewText(int kind, ZoneSelection selection) {
		if (kind < 0 || kind >= ZONE_KIND_NAMES.length) throw new IllegalArgumentException("Invalid zone kind: " + kind);
		if (selection == null) throw new IllegalArgumentException("Zone selection is required");
		int width = selection.maxCol() - selection.minCol() + 1;
		int height = selection.maxRow() - selection.minRow() + 1;
		return ZONE_KIND_NAMES[kind] + " · " + width + "×" + height + " Felder";
	}

	/** Maps one rendered map pixel to the same world-block offset used by click targets. */
	static int mapPixelToWorldOffset(int mapPixelOffset, int worldBlocksPerCell, int pixelsPerCell) {
		if (worldBlocksPerCell <= 0 || pixelsPerCell <= 0) throw new IllegalArgumentException("Map scales must be positive");
		return (int)Math.floorDiv((long)mapPixelOffset * worldBlocksPerCell, pixelsPerCell);
	}

	/** Inclusive world bounds for a selection of whole map cells, in either drag direction. */
	static int[] zoneWorldBounds(int originX, int originZ, int worldBlocksPerCell,
		int startCol, int startRow, int endCol, int endRow) {
		if (worldBlocksPerCell <= 0) throw new IllegalArgumentException("World cell size must be positive");
		int minCol = Math.min(startCol, endCol), maxCol = Math.max(startCol, endCol);
		int minRow = Math.min(startRow, endRow), maxRow = Math.max(startRow, endRow);
		long minX = (long)originX + (long)minCol * worldBlocksPerCell;
		long minZ = (long)originZ + (long)minRow * worldBlocksPerCell;
		long maxX = (long)originX + ((long)maxCol + 1L) * worldBlocksPerCell - 1L;
		long maxZ = (long)originZ + ((long)maxRow + 1L) * worldBlocksPerCell - 1L;
		return new int[]{Math.toIntExact(minX), Math.toIntExact(minZ), Math.toIntExact(maxX), Math.toIntExact(maxZ)};
	}

	/** Screen rectangle of inclusive world bounds; also used by the saved-zone and live-drag previews. */
	static int[] zoneScreenBounds(int originX, int originZ, int worldBlocksPerCell, int pixelsPerCell,
		int panX, int panY, int minX, int minZ, int maxX, int maxZ) {
		if (worldBlocksPerCell <= 0 || pixelsPerCell <= 0) throw new IllegalArgumentException("Map scales must be positive");
		int left = panX + (int)Math.floorDiv(((long)minX - originX) * pixelsPerCell, worldBlocksPerCell);
		int top = panY + (int)Math.floorDiv(((long)minZ - originZ) * pixelsPerCell, worldBlocksPerCell);
		int right = panX + (int)Math.floorDiv(((long)maxX - originX + 1L) * pixelsPerCell, worldBlocksPerCell);
		int bottom = panY + (int)Math.floorDiv(((long)maxZ - originZ + 1L) * pixelsPerCell, worldBlocksPerCell);
		return new int[]{left, top, right, bottom};
	}

	/** Live preview bounds use the exact selected raster cells, avoiding a world-to-screen round trip. */
	static int[] zoneCellScreenBounds(int pixelsPerCell, int panX, int panY, ZoneSelection selection) {
		if (pixelsPerCell <= 0) throw new IllegalArgumentException("Map cell size must be positive");
		if (selection == null || selection.minCol() > selection.maxCol() || selection.minRow() > selection.maxRow()) {
			throw new IllegalArgumentException("A normalized zone selection is required");
		}
		long left = (long)panX + (long)selection.minCol() * pixelsPerCell;
		long top = (long)panY + (long)selection.minRow() * pixelsPerCell;
		long right = (long)panX + ((long)selection.maxCol() + 1L) * pixelsPerCell;
		long bottom = (long)panY + ((long)selection.maxRow() + 1L) * pixelsPerCell;
		return new int[]{Math.toIntExact(left), Math.toIntExact(top), Math.toIntExact(right), Math.toIntExact(bottom)};
	}

	private static int zoneColor(String kind) {
		return switch (kind) {
			case "farm" -> 0xAAE0D050;
			case "housing" -> 0xAA5090E0;
			case "pasture" -> 0xAA70B860;
			default -> 0xAAD07050;
		};
	}

	private void cancelZoneDrawing() {
		drawingZone = false;
		zoneTool = false;
		if (zoneButton != null) zoneButton.setMessage(Component.literal("Zone zeichnen"));
	}

	private void clampMap() {
		int contentSize = data.size() * mapCell;
		if (contentSize <= MAP_VIEW) {
			mapPanX = (MAP_VIEW - contentSize) / 2;
			mapPanY = (MAP_VIEW - contentSize) / 2;
			return;
		}
		int min = MAP_VIEW - contentSize;
		mapPanX = Math.max(min, Math.min(0, mapPanX));
		mapPanY = Math.max(min, Math.min(0, mapPanY));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		event = scaledMouse(event); dx /= uiScale; dy /= uiScale;
		if (draggingMap && tab == 0) {
			dragCarryX += dx; dragCarryY += dy;
			int moveX = (int)dragCarryX, moveY = (int)dragCarryY;
			dragCarryX -= moveX; dragCarryY -= moveY;
			mapPanX += moveX; mapPanY += moveY; clampMap(); return true;
		}
		if (drawingZone) {
			double[] pointer = resolveZoneDragPointer(zoneDragStartPointerX, zoneDragStartPointerY,
				zonePointerX, zonePointerY, event.x(), event.y(), dx, dy);
			if (pointer == null) {
				zoneNotice = "Mausposition konnte nicht gelesen werden; bitte erneut ziehen.";
				return true;
			}
			zonePointerX = pointer[0]; zonePointerY = pointer[1];
			int[] p = mapGridCell(zonePointerX, zonePointerY, true);
			if (p != null) updateZoneEndFromDrag(p[0], p[1]);
			else zoneNotice = "Kartenfeld kann nicht ermittelt werden; bitte erneut ziehen.";
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		event = scaledMouse(event);
		if (draggingMap) { draggingMap = false; return true; }
		if (drawingZone) {
			if (!zoneReleaseCommits(event.button())) {
				zoneNotice = "Zonenziehen abgebrochen; es wurde nichts gespeichert.";
				cancelZoneDrawing();
				return true;
			}
			double[] pointer = resolveZoneReleasePointer(zoneDragStartPointerX, zoneDragStartPointerY,
				zonePointerX, zonePointerY, event.x(), event.y());
			if (pointer == null) {
				zoneNotice = "Mausposition konnte nicht gelesen werden; es wurde nichts gespeichert.";
				cancelZoneDrawing();
				return true;
			}
			zonePointerX = pointer[0]; zonePointerY = pointer[1];
			int[] end = mapGridCell(zonePointerX, zonePointerY, true);
			if (end == null) {
				zoneNotice = "Endfeld konnte keiner sichtbaren Kartenzelle zugeordnet werden; es wurde nichts gespeichert.";
				cancelZoneDrawing();
				return true;
			}
			ZoneSelection selection = updateZoneEnd(end[0], end[1]);
			if (selection == null) {
				zoneNotice = "Startfeld liegt außerhalb der zulässigen Dorfzone.";
				cancelZoneDrawing();
				return true;
			}
			drawingZone = false;
			int[] origin = mapOrigin();
			if (origin == null) {
				zoneNotice = "Dorfzentrum fehlt auf der Karte. Karte bitte neu öffnen.";
				return true;
			}
			int[] bounds = zoneWorldBounds(origin[0], origin[1], data.cellSize(), selection.minCol(), selection.minRow(),
				selection.maxCol(), selection.maxRow());
			zoneClipped = selection.clipped();
			submitZone(bounds[0], bounds[1], bounds[2], bounds[3], zoneKindId(drawingZoneKind), false);
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		g.pose().pushMatrix(); g.pose().scale(uiScale, uiScale);
		// Holzrahmen, Pergament, Kopfbanner.
		g.fill(left - 4, top - 4, left + W + 4, top + H + 4, 0xFF3A2414);
		g.fill(left - 2, top - 2, left + W + 2, top + H + 2, 0xFF6B4423);
		g.fill(left, top, left + W, top + H, 0xFFE8D9B0);
		g.fill(left, top, left + W, top + 20, 0xFF8C2A22);
		g.fill(left, top + 20, left + W, top + 21, 0xFF5C1712);
		g.fill(left + 6, top + 46, left + W - 6, top + H - 30, 0xFFF3E8C8);
		g.outline(left + 6, top + 46, W - 12, H - 76, 0xFFB79C6B);
		g.pose().popMatrix();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.pose().pushMatrix(); g.pose().scale(uiScale, uiScale);
		mouseX = (int)(mouseX / uiScale); mouseY = (int)(mouseY / uiScale);
		super.extractRenderState(g, mouseX, mouseY, delta);
		String title = renameBox != null && renameBox.visible ? "" : headerTitle(font, data.village(), data.board());
		g.text(font, title, left + HEADER_TEXT_X, top + HEADER_TEXT_Y, 0xFFFFE9A8, false);
		int[] selectedTabBounds = tabButtonBounds(left, top, tab);
		g.fill(selectedTabBounds[0], top + 42, selectedTabBounds[0] + selectedTabBounds[2], top + 44, 0xFFB5381C);
		switch (tab) {
			case 0 -> overview(g, mouseX, mouseY);
			case 1 -> residents(g, mouseX, mouseY);
			case 2 -> lines(g, constructionDisplayLines(data.details(), BoardScreen::localizedMaterialName));
			case 3 -> lines(g, data.expeditions());
			case 4 -> neighbors(g);
			case 5 -> journal(g);
			case 6 -> work(g);
			case 7 -> stock(g);
			case 8 -> talk(g);
		}
		g.pose().popMatrix();
	}

	/** Abreissbare Gebaeude aus den Zusatzzeilen {@code @b|typ|name|x|y|z|laeuft}. */
	private java.util.List<String[]> buildingRows() {
		java.util.List<String[]> out = new java.util.ArrayList<>();
		for (String row : quota) {
			if (row.startsWith("@b|")) {
				String[] p = row.split("\\|");
				if (p.length >= 7) out.add(p);
			}
		}
		return out;
	}

	private String[] selectedBuildingRow() {
		java.util.List<String[]> rows = buildingRows();
		return pickedBuilding >= 0 && pickedBuilding < rows.size() ? rows.get(pickedBuilding) : null;
	}

	private static boolean selectableMapMarker(int code) {
		return code > 0 && code < MARK_NAMES.length && code != 9;
	}

	private int selectedMapMarkerCode() {
		if (pickedMapMarker < 0 || pickedMapMarkerCode == 0) return 0;
		byte[] marks = data.marks();
		if (pickedMapMarker >= marks.length) return 0;
		int current = marks[pickedMapMarker] & 0xFF;
		return current == pickedMapMarkerCode && selectableMapMarker(current) ? current : 0;
	}

	String selectedMapMarkerName() {
		int code = selectedMapMarkerCode();
		return code > 0 ? MARK_NAMES[code] : null;
	}

	String selectedMapMarkerLocation() {
		int size = data.size(), marker = pickedMapMarker;
		int[] origin = mapOrigin();
		if (selectedMapMarkerCode() == 0 || origin == null || size <= 0 || marker < 0 || marker >= size * size || data.cellSize() <= 0) return null;
		int col = marker % size, row = marker / size;
		int worldX = origin[0] + col * data.cellSize() + data.cellSize() / 2;
		int worldZ = origin[1] + row * data.cellSize() + data.cellSize() / 2;
		return "Kartenfeld etwa X " + worldX + " · Z " + worldZ;
	}

	private void clearMapMarkerSelection() {
		pickedMapMarker = -1;
		pickedMapMarkerCode = 0;
	}

	/** Findet auch die Nachbarzelle eines kleinen Markers, damit die Auswahl mit der Maus gut erreichbar bleibt. */
	private int nearbySelectableMapMarker(int col, int row) {
		int size = data.size();
		byte[] marks = data.marks();
		int best = -1, bestDistance = Integer.MAX_VALUE;
		for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
			int x = col + dc, z = row + dr;
			if (x < 0 || z < 0 || x >= size || z >= size) continue;
			int index = z * size + x;
			if (index >= marks.length || !selectableMapMarker(marks[index] & 0xFF)) continue;
			int distance = dc * dc + dr * dr;
			if (distance < bestDistance) {
				best = index;
				bestDistance = distance;
			}
		}
		return best;
	}

	private int[] mapOrigin() {
		for (String row : quota) {
			if (row.startsWith("@o|")) {
				String[] p = row.split("\\|");
				return new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2])};
			}
		}
		return null;
	}

	/** A discovered neighbor that has a real bell identity in the active map dimension. */
	static record NeighborMapPin(String name, String distance, String relationship, String status,
		String dimension, int worldX, int worldZ, int col, int row, long bell) { }

	/** Only the concrete neighbor-link payload can produce a pin; partner history and guest rows have no map position. */
	static List<NeighborMapPin> neighborMapPins(BoardData board, String dimension, int originX, int originZ) {
		if (board == null || dimension == null || dimension.isBlank() || board.neighbors() == null
			|| board.cellSize() <= 0 || board.size() <= 0) return List.of();
		int cellSize = board.cellSize(), size = board.size();
		long extent = (long)cellSize * size;
		List<NeighborMapPin> pins = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String row : board.neighbors()) {
			if (row == null) continue;
			String[] fields = row.split("\\|", -1);
			if (fields.length < 13) continue;
			String name = fields[0].trim(), linkedDimension = fields[11].trim();
			if (name.isEmpty() || !dimension.equals(linkedDimension)) continue;
			try {
				long packedBell = Long.parseLong(fields[12]);
				net.minecraft.core.BlockPos bell = net.minecraft.core.BlockPos.of(packedBell);
				long dx = (long)bell.getX() - originX, dz = (long)bell.getZ() - originZ;
				if (dx < 0 || dz < 0 || dx >= extent || dz >= extent) continue;
				String identity = linkedDimension + "|" + packedBell;
				if (!seen.add(identity)) continue;
				int col = (int)Math.floorDiv(dx, (long)cellSize), mapRow = (int)Math.floorDiv(dz, (long)cellSize);
				String relation = fields[4].trim();
				String distance = fields[1].trim();
				String status = "1".equals(fields[6])
					? ("1".equals(fields[10]) ? "geladen · Rückverbindung aktiv" : "geladen · Rückverbindung fehlt")
					: "nicht geladen";
				pins.add(new NeighborMapPin(name, distance, relation.isEmpty() ? "Verbindung" : relation, status,
					linkedDimension, bell.getX(), bell.getZ(), col, mapRow, packedBell));
			} catch (NumberFormatException ignored) { }
		}
		return List.copyOf(pins);
	}

	/** Exact map-pixel offset for a world coordinate, retaining sub-cell bell position while zooming. */
	static int worldToMapPixelOffset(int worldCoordinate, int origin, int worldCellSize, int mapCellPixels) {
		if (worldCellSize <= 0 || mapCellPixels <= 0) return 0;
		long offset = Math.floorDiv(((long)worldCoordinate - origin) * mapCellPixels, (long)worldCellSize);
		return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, offset));
	}

	static String neighborMapTooltip(NeighborMapPin pin) {
		return pin == null ? "" : pin.name() + " · " + pin.relationship();
	}

	static List<String> neighborMapDetailLines(NeighborMapPin pin) {
		return pin == null ? List.of() : List.of("Beziehung: " + pin.relationship(),
			"Entfernung: " + pin.distance(), "Status: " + pin.status());
	}

	static int neighborMapRelationshipIndex(List<String> relationshipRows, NeighborMapPin pin) {
		if (pin == null) return -1;
		String[] identity = new String[13];
		identity[11] = pin.dimension();
		identity[12] = Long.toString(pin.bell());
		return neighborIndex(relationshipRows, identity);
	}

	/** Returns the nearest visible discovered pin under the pointer; blank/outside viewport never selects a neighbor. */
	static NeighborMapPin neighborMapPinAt(List<NeighborMapPin> pins, double screenX, double screenY,
		int mapLeft, int mapTop, int viewportSize, int panX, int panY, int mapCellPixels,
		int originX, int originZ, int worldCellSize) {
		if (pins == null || pins.isEmpty() || mapCellPixels <= 0 || worldCellSize <= 0
			|| !mapViewportContains(screenX, screenY, mapLeft, mapTop, viewportSize)) return null;
		NeighborMapPin closest = null;
		double closestDistance = 37.0;
		for (NeighborMapPin pin : pins) {
			int pinX = mapLeft + panX + worldToMapPixelOffset(pin.worldX(), originX, worldCellSize, mapCellPixels);
			int pinY = mapTop + panY + worldToMapPixelOffset(pin.worldZ(), originZ, worldCellSize, mapCellPixels);
			if (pinX < mapLeft - 4 || pinY < mapTop - 4
				|| pinX >= mapLeft + viewportSize + 4 || pinY >= mapTop + viewportSize + 4) continue;
			double dx = screenX - pinX, dy = screenY - pinY, distance = dx * dx + dy * dy;
			if (distance < closestDistance) {
				closestDistance = distance;
				closest = pin;
			}
		}
		return closest;
	}

	private void selectNeighborMapPin(NeighborMapPin pin) {
		if (pin == null) {
			clearNeighborMapPinSelection();
			return;
		}
		pickedNeighborMapDimension = pin.dimension();
		pickedNeighborMapBell = pin.bell();
		List<String> rows = relationshipRows(data.neighbors(), data.partners());
		neighbor = neighborMapRelationshipIndex(rows, pin);
		if (neighbor >= 0) neighborNotice = relationshipAvailabilityNotice(rows.get(neighbor).split("\\|", -1), clientDimensionId());
	}

	private void clearNeighborMapPinSelection() {
		pickedNeighborMapDimension = null;
		pickedNeighborMapBell = 0;
	}

	private NeighborMapPin selectedNeighborMapPin(List<NeighborMapPin> pins) {
		if (pickedNeighborMapDimension == null) return null;
		for (NeighborMapPin pin : pins) {
			if (pin.bell() == pickedNeighborMapBell && pin.dimension().equals(pickedNeighborMapDimension)) return pin;
		}
		return null;
	}

	/** Arbeitsmarkt: je Beruf Ist-Zahl, Sollanteil (Prozent) und -/+ zum Einstellen. Das Dorf besetzt sich selbst nach diesen Quoten um. */
	private void work(GuiGraphicsExtractor g) {
		label(g, "Berufe: % Einwohner · autom. Lehrlinge: % Kinder", left + 12, top + 52, INK);
		int rowIndex = 0;
		for (String entry : quota) {
			if (entry.startsWith("@")) continue;
			String[] p = entry.split("\\|");
			if (p.length < 5) continue;
			int lineY = top + 62 + rowIndex * 12;
			label(g, p[1], left + 14, lineY + 2, INK);
			label(g, "Ist: " + p[2], left + 190, lineY + 2, SOFT);
			boolean changed = !p[3].equals(p[4]);
			label(g, "Soll" + (changed ? " *" : ""), left + 250, lineY + 2, changed ? 0xFFB5381C : INK);
			rowIndex++;
		}
		label(g, "* = vom Standard abgewichen · 0 % = keine automatische Zuteilung", left + 14, top + H - 30, SOFT);
	}


	private void requestStock() { ClientPlayNetworking.send(new de.villagerplus.net.RequestStockPolicy(data.board())); }

	private List<String[]> filteredStock() {
		return stockRows.stream().map(row -> row.split("\\|", -1))
			.filter(row -> row.length == 4 && row[1].equals(STOCK_TYPES[stockCategory])).toList();
	}

	private void stock(GuiGraphicsExtractor g) {
		label(g, "Gegenstand", left + 14, top + 72, INK);
		label(g, "Gemeinsam / Soll", left + W - 145, top + 72, INK);
		List<String[]> rows = filteredStock();
		int visible = 12; scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
			String[] row = rows.get(i); int y = top + 85 + (i - scroll) * 11;
			if (row[0].equals(stockItem)) g.fill(left + 10, y - 1, left + W - 10, y + 10, 0xFFE2C98A);
			var id = net.minecraft.resources.Identifier.tryParse(row[0]);
			var item = id == null ? java.util.Optional.<net.minecraft.world.item.Item>empty() : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id);
			String name = item.map(it -> new net.minecraft.world.item.ItemStack(it).getHoverName().getString()).orElse(row[0]);
			label(g, font.plainSubstrByWidth(name, W - 175), left + 14, y, INK);
			label(g, row[2] + " / " + (row[3].equals("-1") ? "unbegrenzt" : row[3]), left + W - 145, y, SOFT);
		}
		if (rows.isEmpty()) label(g, "Noch keine Waren in dieser Lagergruppe.", left + 14, top + 90, SOFT);
	}

	private final class StockSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		StockSlider(int x, int y, int width) { super(x, y, width, 20, Component.literal("Soll: 0"), 0); }
		int target() { return (int)Math.round(value * 4096); }
		void target(int count) { value = Math.max(0, Math.min(4096, count)) / 4096.0; updateMessage(); }
		@Override protected void updateMessage() { setMessage(Component.literal("Soll: " + target())); }
		@Override protected void applyValue() { }
	}

	/** Accessible quota control; keyboard arrows adjust one percentage point, while clicks and drags retain mouse input. */
	private final class QuotaSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		private final String job;
		QuotaSlider(String job, int x, int y, int width, int target) {
			super(x, y, width, 12, Component.literal(target + "%"), Math.max(0, Math.min(40, target)) / 40.0);
			this.job = job;
			updateMessage();
		}
		int target() { return Math.max(0, Math.min(40, (int)Math.round(value * 40))); }
		void setTargetSilently(int target) {
			value = Math.max(0, Math.min(40, target)) / 40.0;
			updateMessage();
		}
		@Override protected void updateMessage() { setMessage(Component.literal(target() + "%")); }
		@Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
			return Component.translatable("gui.narrate.slider", Component.literal(quotaLabel(job) + ": " + target() + "%"));
		}
		@Override protected void applyValue() { setQuotaTarget(job, target()); }
		@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
			if (event.isSelection()) return super.keyPressed(event);
			if (canChangeValue && (event.isLeft() || event.isRight())) {
				int next = target() + (event.isLeft() ? -1 : 1);
				setValue(Math.max(0, Math.min(40, next)) / 40.0);
				return true;
			}
			return super.keyPressed(event);
		}
	}

	/** Discrete profession picker used for both adult job assignment and children's apprenticeship plans. */
	private final class ResidentJobSlider extends net.minecraft.client.gui.components.AbstractSliderButton {
		ResidentJobSlider(int x, int y, int width) {
			super(x, y, width, 20, Component.literal("Beruf"), (JOBS.length - 1) / (double)(JOBS.length - 1));
			updateMessage();
		}
		int selectedJob() { return Math.max(0, Math.min(JOBS.length - 1, (int)Math.round(value * (JOBS.length - 1)))); }
		void select(int job) {
			value = Math.max(0, Math.min(JOBS.length - 1, job)) / (double)(JOBS.length - 1);
			updateMessage();
		}
		@Override protected void updateMessage() { setMessage(Component.literal(JOBS[selectedJob()][1])); }
		@Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
			String role = selectedResidentIsChild() ? "Lehrlingsziel" : "Beruf";
			return Component.translatable("gui.narrate.slider", Component.literal(role + ": " + JOBS[selectedJob()][1]));
		}
		@Override protected void applyValue() { updateMessage(); }
		@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
			if (event.isSelection()) return super.keyPressed(event);
			if (canChangeValue && (event.isLeft() || event.isRight())) {
				int next = selectedJob() + (event.isLeft() ? -1 : 1);
				select(Math.max(0, Math.min(JOBS.length - 1, next)));
				return true;
			}
			return super.keyPressed(event);
		}
	}

	private void label(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font, s, x, y, color, false);
	}

	private static int shade(int argb, int level) {
		float f = 1.0f + 0.07f * (level - 3);
		int r = Math.min(255, (int) (((argb >> 16) & 255) * f)), gg = Math.min(255, (int) (((argb >> 8) & 255) * f)), bb = Math.min(255, (int) ((argb & 255) * f));
		return 0xFF000000 | (r << 16) | (gg << 8) | bb;
	}

	private static int noise(int col, int row, int salt) {
		int h = col * 73856093 ^ row * 19349663 ^ salt * 83492791;
		return (h >>> 5) & 7;
	}

	/** Schmuckpixel je Feldart (Gras-Sprenkel, Baumkronen, Wellen, Ackerfurchen, Dachziegel ...), damit die Karte nicht wie Rasterpapier wirkt. */
	private void drawCell(GuiGraphicsExtractor g, int kind, int shadeLevel, int col, int row, int x, int y, int cell) {
		int base = shade(TERRAIN[Math.max(0, Math.min(TERRAIN.length - 1, kind))], shadeLevel);
		g.fill(x, y, x + cell, y + cell, base);
		int n = noise(col, row, kind);
		switch (kind) {
			case 0, 6, 8, 9 -> {
				if (n < 3) g.fill(x + (n & 1) * 2, y + (n >> 1) % 2 * 2, x + (n & 1) * 2 + 1, y + (n >> 1) % 2 * 2 + 1, shade(TERRAIN[kind], shadeLevel + 1));
			}
			case 1 -> {
				g.fill(x, y + 2, x + cell, y + cell, shade(TERRAIN[1], shadeLevel - 1));
				g.fill(x + 1, y, x + 3, y + 2, shade(TERRAIN[1], shadeLevel + 1));
			}
			case 2 -> g.fill(x, y + 1, x + cell, y + 3, shade(0xFFD8C08A, shadeLevel));
			case 3 -> {
				if (n < 4) g.fill(x + 1, y + 1 + (n & 1), x + 3, y + 2 + (n & 1), 0xFF7FB0F0);
			}
			case 4 -> {
				g.fill(x, y + 1, x + cell, y + 2, shade(0xFF8E9A38, shadeLevel));
				g.fill(x, y + 3, x + cell, y + 4, shade(0xFF8E9A38, shadeLevel));
			}
			case 5 -> {
				g.fill(x, y + cell - 1, x + cell, y + cell, 0xFF5A281C);
				g.fill(x, y, x + cell, y + 1, 0xFFD87A58);
			}
			case 10 -> {
				if (((col + row) & 1) == 0) g.fill(x, y, x + cell, y + cell, shade(0xFFC9C5B8, shadeLevel));
			}
			default -> { }
		}
	}

	private void icon(GuiGraphicsExtractor g, int m, int cx, int cy) {
		int c = MARK_COLORS[m];
		g.fill(cx - 4, cy - 4, cx + 4, cy + 4, 0xFF151008);
		g.fill(cx - 3, cy - 3, cx + 3, cy + 3, c);
		g.fill(cx - 3, cy - 3, cx + 3, cy - 2, 0x66FFFFFF);
		g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFF151008);
		if (m == 1) { // Glocke: goldene Raute
			g.fill(cx, cy - 5, cx + 1, cy + 5, 0xFFFFE04A);
			g.fill(cx - 5, cy, cx + 5, cy + 1, 0xFFFFE04A);
		}
	}

	private void overview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int n = data.size();
		int cell = mapCell;
		int[] viewport = mapViewportBounds(left, top);
		int[] frame = mapFrameBounds(left, top);
		int view = viewport[2];
		clampMap();
		int mx = viewport[0];
		int my = viewport[1];
		// Rahmen aus Holz mit Pergamentkante
		g.fill(frame[0], frame[1], frame[0] + frame[2], frame[1] + frame[3], 0xFF2E1D0E);
		g.fill(mx - 4, my - 4, mx + view + 4, my + view + MAP_FRAME_BOTTOM_PADDING, 0xFF7A5230);
		g.fill(mx - 2, my - 2, mx + view + 2, my + view + MAP_FRAME_BOTTOM_PADDING, 0xFFD9C48F);
		g.enableScissor(mx, my, mx + view, my + view);
		byte[] cells = data.cells();
		byte[] marks = data.marks();
		for (int row = 0; row < n; row++) {
			for (int col = 0; col < n; col++) {
				int i = row * n + col;
				int raw = cells[i] & 0xFF;
				drawCell(g, raw & 15, (raw >> 4) & 7, col, row, mx + mapPanX + col * cell, my + mapPanY + row * cell, cell);
			}
		}
		int hoverMark = 0;
		NeighborMapPin hoverNeighborPin = null;
		int hoverCol = Math.floorDiv(mouseX - mx - mapPanX, cell), hoverRow = Math.floorDiv(mouseY - my - mapPanY, cell);
		boolean inside = insideMap(mouseX, mouseY) && hoverCol >= 0 && hoverRow >= 0 && hoverCol < n && hoverRow < n;
		// Bewohner als kleine Punkte, danach Gebaeudemarker obenauf
		for (int row = 0; row < n; row++) {
			for (int col = 0; col < n; col++) {
				int m = marks[row * n + col];
				if (m == 9) {
					int x = mx + mapPanX + col * cell, y = my + mapPanY + row * cell;
					g.fill(x, y, x + 3, y + 3, 0xFF0C2A0C);
					g.fill(x, y, x + 2, y + 2, MARK_COLORS[9]);
				}
			}
		}
		for (int row = 0; row < n; row++) {
			for (int col = 0; col < n; col++) {
				int m = marks[row * n + col];
				if (m > 0 && m != 9 && m < MARK_COLORS.length) {
					icon(g, m, mx + mapPanX + col * cell + cell / 2, my + mapPanY + row * cell + cell / 2);
				}
			}
		}
		int[] zoneOrigin = mapOrigin();
		net.minecraft.client.multiplayer.ClientLevel clientLevel = net.minecraft.client.Minecraft.getInstance().level;
		String mapDimension = clientLevel == null ? "" : clientLevel.dimension().identifier().toString();
		List<NeighborMapPin> neighborPins = zoneOrigin == null ? List.of()
			: neighborMapPins(data, mapDimension, zoneOrigin[0], zoneOrigin[1]);
		NeighborMapPin selectedNeighborPin = selectedNeighborMapPin(neighborPins);
		if (zoneOrigin != null) {
			g.enableScissor(mx, my, mx + view, my + view);
			for (String row : quota) if (row.startsWith("@z|")) {
				String[] z = row.split("\\|"); if (z.length < 7) continue;
				try {
					int[] rect = zoneScreenBounds(zoneOrigin[0], zoneOrigin[1], data.cellSize(), cell, mapPanX, mapPanY,
						Integer.parseInt(z[3]), Integer.parseInt(z[4]), Integer.parseInt(z[5]), Integer.parseInt(z[6]));
					int x1 = mx + rect[0], y1 = my + rect[1], x2 = mx + rect[2], y2 = my + rect[3];
					int color = zoneColor(z[2]);
					g.fill(x1, y1, x2, y1 + 1, color); g.fill(x1, y2 - 1, x2, y2, color);
					g.fill(x1, y1, x1 + 1, y2, color); g.fill(x2 - 1, y1, x2, y2, color);
				} catch (NumberFormatException ignored) { }
			}
			if (zonePreviewRequest != null && !zonePreviewRequest.delete()) {
				int[] rect = zoneScreenBounds(zoneOrigin[0], zoneOrigin[1], data.cellSize(), cell, mapPanX, mapPanY,
					zonePreviewRequest.minX(), zonePreviewRequest.minZ(), zonePreviewRequest.maxX(), zonePreviewRequest.maxZ());
				int x1 = mx + rect[0], y1 = my + rect[1], x2 = mx + rect[2], y2 = my + rect[3];
				int feedbackColor = zonePreviewAccepted ? 0xFF55C978 : 0xFFFFD45A;
				int previewFill = zonePreviewAccepted ? 0x3A55C978 : 0x38FFD45A;
				g.fill(x1, y1, Math.max(x1 + 1, x2), Math.max(y1 + 1, y2), previewFill);
				g.outline(x1 - 1, y1 - 1, Math.max(3, x2 - x1 + 2), Math.max(3, y2 - y1 + 2), 0xFF21170B);
				g.outline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), feedbackColor);
			}
			if (drawingZone) {
				ZoneSelection selection = currentZoneSelection();
				if (selection != null) {
					int[] rect = zoneCellScreenBounds(cell, mapPanX, mapPanY, selection);
					int x1 = mx + rect[0], y1 = my + rect[1], x2 = mx + rect[2], y2 = my + rect[3];
					int zoneColor = zoneColor(zoneKindId(drawingZoneKind));
					g.fill(x1, y1, Math.max(x1 + 1, x2), Math.max(y1 + 1, y2), (zoneColor & 0x00FFFFFF) | 0x52000000);
					g.outline(x1 - 1, y1 - 1, Math.max(3, x2 - x1 + 2), Math.max(3, y2 - y1 + 2), 0xFF21170B);
					g.outline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), (zoneColor & 0x00FFFFFF) | 0xFF000000);
					int anchorX = mx + mapPanX + zoneStartX * cell + cell / 2;
					int anchorY = my + mapPanY + zoneStartZ * cell + cell / 2;
					int previewEndCol = zoneEndX < zoneStartX ? selection.minCol() : selection.maxCol();
					int previewEndRow = zoneEndZ < zoneStartZ ? selection.minRow() : selection.maxRow();
					int endX = mx + mapPanX + previewEndCol * cell + cell / 2;
					int endY = my + mapPanY + previewEndRow * cell + cell / 2;
					g.fill(anchorX - 2, anchorY - 2, anchorX + 3, anchorY + 3, 0xFF21170B);
					g.fill(anchorX - 1, anchorY - 1, anchorX + 2, anchorY + 2, 0xFFFFE04A);
					g.fill(endX - 2, endY - 2, endX + 3, endY + 3, 0xFF21170B);
					g.fill(endX - 1, endY - 1, endX + 2, endY + 2, 0xFFFFFFFF);
					String preview = font.plainSubstrByWidth(zonePreviewText(drawingZoneKind, selection), view - 16);
					String limit = font.plainSubstrByWidth("Begrenzt · Radius ±64 · max. 4096", view - 16);
					int previewX = mx + 4, previewY = my + 4, previewWidth = Math.max(font.width(preview), selection.clipped() ? font.width(limit) : 0) + 8;
					int previewHeight = selection.clipped() ? 23 : 11;
					g.fill(previewX - 1, previewY - 1, previewX + previewWidth + 1, previewY + previewHeight + 2, 0xFF151008);
					g.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, 0xFFF3E8C8);
					label(g, preview, previewX + 4, previewY + 2, INK);
					if (selection.clipped()) label(g, limit, previewX + 4, previewY + 12, 0xFF8E2C1D);
				} else {
					zoneNotice = "Kartenfeld liegt außerhalb der zulässigen Dorfzone.";
				}
			}
			g.disableScissor();
		}
		if (!neighborPins.isEmpty() && zoneOrigin != null) {
			// Named pins use discovered bell coordinates and stay clipped to the map viewport.
			g.enableScissor(mx, my, mx + view, my + view);
			for (NeighborMapPin pin : neighborPins) {
				int pinX = mx + mapPanX + worldToMapPixelOffset(pin.worldX(), zoneOrigin[0], data.cellSize(), cell);
				int pinY = my + mapPanY + worldToMapPixelOffset(pin.worldZ(), zoneOrigin[1], data.cellSize(), cell);
				g.fill(pinX - 4, pinY - 4, pinX + 4, pinY + 4, 0xFF10221E);
				g.fill(pinX - 3, pinY - 3, pinX + 3, pinY + 3, MARK_COLORS[16]);
				g.fill(pinX - 1, pinY - 1, pinX + 1, pinY + 1, 0xFFFFFFFF);
				if (pin.equals(selectedNeighborPin)) g.outline(pinX - 6, pinY - 6, 12, 12, 0xFFFFFFFF);
			}
			g.disableScissor();
		}
		if (zoneNotice != null) {
			List<String> noticeLines = wrapNoticeLines(zoneNotice, font, view - 14, 4);
			int noticeX = mx + 4, noticeY = my + view - noticeLines.size() * 9 - 4;
			int noticeWidth = noticeLines.stream().mapToInt(font::width).max().orElse(0) + 8;
			int noticeColor = zoneNotice.startsWith("✓ ") ? 0xFF477342
				: zoneNotice.startsWith("✕ ") || zoneNotice.startsWith("Zone nicht gesendet") ? 0xFF8E2C1D : 0xFF9A6818;
			g.fill(noticeX - 1, noticeY - 1, noticeX + noticeWidth + 1, noticeY + noticeLines.size() * 9 + 3, 0xFF5A2118);
			g.fill(noticeX, noticeY, noticeX + noticeWidth, noticeY + noticeLines.size() * 9 + 2, 0xFFFFE5CF);
			for (int i = 0; i < noticeLines.size(); i++) label(g, noticeLines.get(i), noticeX + 4, noticeY + 2 + i * 9, noticeColor);
		}
		if (inside) {
			// Treffer auch auf Nachbarfeldern, damit die 8-Pixel-Icons leicht zu treffen sind
			for (int dr = -1; dr <= 1 && hoverMark == 0; dr++) {
				for (int dc = -1; dc <= 1 && hoverMark == 0; dc++) {
					int cc = hoverCol + dc, rr = hoverRow + dr;
					if (cc >= 0 && rr >= 0 && cc < n && rr < n) {
						int m = marks[rr * n + cc];
						if (m > 0 && m != 9 && m < MARK_NAMES.length) hoverMark = m;
					}
				}
			}
			if (zoneOrigin != null) hoverNeighborPin = neighborMapPinAt(neighborPins, mouseX, mouseY,
				mx, my, view, mapPanX, mapPanY, cell, zoneOrigin[0], zoneOrigin[1], data.cellSize());
		}
		g.disableScissor();
		// Kompass und Massstab
		label(g, "N", mx + view - 9, my + 3, 0xFFFFFFFF);
		g.fill(mx + view - 7, my + 12, mx + view - 6, my + 22, 0xFFFFFFFF);
		g.fill(mx + view - 9, my + 15, mx + view - 4, my + 16, 0xFFFFFFFF);
		g.fill(mx + 4, my + view - 7, mx + 4 + 12, my + view - 6, 0xFFFFFFFF);
		label(g, (12 * data.cellSize() / cell) + " m", mx + 18, my + view - 11, 0xFFFFFFFF);
		String mapHelp = movePending
			? "Ziel auf Karte anklicken · Abbrechen beendet die Zielwahl"
			: drawingZone
				? "Ziehen: Größe · Rechts: Abbruch"
				: zoneTool
					? zoneKind == ZONE_DELETE_KIND
						? "Zone löschen · Rechts: Karte"
						: "Freies Feld ziehen · Rechts: Karte"
					: "Gebäude anklicken · Mausrad: Zoom · Rechts ziehen: Karte";
		label(g, font.plainSubstrByWidth(mapHelp, view), mx, my + view + MAP_FRAME_BOTTOM_PADDING, SOFT);

		int rx = mx + view + MAP_DETAILS_GAP;
		int ry = top + 52;
		String[] selectedRow = selectedBuildingRow();
		String markerName = selectedMapMarkerName();
		String mapTitle = selectedRow != null
			? "Auswahl: " + selectedRow[2] + (selectedRow[6].equals("1") ? " · Abriss läuft" : "")
			: selectedNeighborPin != null ? "Nachbardorf: " + selectedNeighborPin.name()
			: markerName != null ? "Auswahl: " + markerName : "Gebäude zuerst, erneut für Ortsmarker";
		label(g, font.plainSubstrByWidth(mapTitle, W - (rx - left) - 12), rx, ry, INK);
		ry += 12;
		if (selectedNeighborPin != null) {
			for (String detail : neighborMapDetailLines(selectedNeighborPin)) {
				label(g, font.plainSubstrByWidth(detail, W - (rx - left) - 12), rx, ry, SOFT);
				ry += 11;
			}
		} else if (selectedRow == null && markerName != null) {
			String location = selectedMapMarkerLocation();
			if (location != null) {
				label(g, font.plainSubstrByWidth(location, W - (rx - left) - 12), rx, ry, SOFT);
				ry += 11;
			}
		}
		for (int m = 1; m < MARK_NAMES.length; m++) {
			int x = rx + ((m - 1) % 2) * 112;
			int y = ry + ((m - 1) / 2) * 11;
			g.fill(x, y, x + 9, y + 9, 0xFF151008);
			g.fill(x + 1, y + 1, x + 8, y + 8, MARK_COLORS[m]);
			label(g, MARK_NAMES[m], x + 12, y + 1, INK);
		}
		int[] org = mapOrigin();
		if (org != null) {
			java.util.List<String[]> rows = buildingRows();
			g.enableScissor(mx, my, mx + view, my + view);
			for (int i = 0; i < rows.size(); i++) {
				int cx = mx + mapPanX + worldToMapPixelOffset(Integer.parseInt(rows.get(i)[3]), org[0], data.cellSize(), cell);
				int cy = my + mapPanY + worldToMapPixelOffset(Integer.parseInt(rows.get(i)[5]), org[1], data.cellSize(), cell);
				if (cx < mx || cy < my || cx >= mx + view || cy >= my + view) continue;
				boolean picked = i == pickedBuilding;
				g.fill(cx - 1, cy - 1, cx + 1, cy + 1, picked ? 0xFFFFFFFF : 0xFFE8D08A);
				if (picked) {
					g.outline(cx - 5, cy - 5, 10, 10, 0xFFFFFFFF);
				}
			}
			g.disableScissor();
			if (selectedRow != null) drawSelectedBuildingTooltip(g, selectedRow, org, mx, my, view);
			else if (markerName != null) drawSelectedMapMarkerTooltip(g, pickedMapMarker, markerName, selectedMapMarkerLocation(), mx, my, view);
		}
		if (markerName != null && data.size() > 0 && pickedMapMarker >= 0 && pickedMapMarker < data.size() * data.size()) {
			int col = pickedMapMarker % data.size(), row = pickedMapMarker / data.size();
			int cx = mx + mapPanX + col * cell + cell / 2, cy = my + mapPanY + row * cell + cell / 2;
			if (cx >= mx && cy >= my && cx < mx + view && cy < my + view) {
				g.enableScissor(mx, my, mx + view, my + view);
				g.outline(cx - 5, cy - 5, 10, 10, 0xFFFFFFFF);
				g.disableScissor();
			}
		}
		if (hoverNeighborPin != null) {
			String tip = font.plainSubstrByWidth(neighborMapTooltip(hoverNeighborPin), W - 24);
			int tw = font.width(tip) + 8;
			int tx = Math.max(left + 4, Math.min(mouseX + 10, left + W - tw - 4));
			int ty = Math.max(top + 4, Math.min(mouseY - 4, top + H - 16));
			g.fill(tx - 1, ty - 1, tx + tw + 1, ty + 13, 0xFF151008);
			g.fill(tx, ty, tx + tw, ty + 12, 0xFFF3E8C8);
			label(g, tip, tx + 4, ty + 2, INK);
		} else if (hoverMark > 0 && markerName == null) {
			String tip = MARK_NAMES[hoverMark];
			int tw = font.width(tip) + 8;
			int tx = Math.min(mouseX + 10, left + W - tw - 4), ty = mouseY - 4;
			g.fill(tx - 1, ty - 1, tx + tw + 1, ty + 13, 0xFF151008);
			g.fill(tx, ty, tx + tw, ty + 12, 0xFFF3E8C8);
			label(g, tip, tx + 4, ty + 2, INK);
		}
		ry += 11 * ((MARK_NAMES.length - 2) / 2 + 1) + 6;
		label(g, "Kennzahlen", rx, ry, INK);
		ry += 12;
		int avail = left + W - 12 - rx;
		for (String bar : data.bars()) {
			String[] p = bar.split("\\|", 4);
			if (p.length < 4) {
				continue;
			}
			label(g, p[0], rx, ry + 1, INK);
			int barX = rx + 62;
			int barW = Math.max(40, avail - 62);
			int pct = Math.max(0, Math.min(100, parse(p[2])));
			g.fill(barX, ry, barX + barW, ry + 10, 0xFF5A4A30);
			g.fill(barX + 1, ry + 1, barX + barW - 1, ry + 9, 0xFFD8C89E);
			g.fill(barX + 1, ry + 1, barX + 1 + (barW - 2) * pct / 100, ry + 9, 0xFF000000 | parseHex(p[3]));
			label(g, font.plainSubstrByWidth(p[1], barW - 6), barX + 4, ry + 1, INK);
			ry += 12;
		}
	}

	private void drawSelectedBuildingTooltip(GuiGraphicsExtractor g, String[] row, int[] origin, int mx, int my, int view) {
		int markerX = mx + mapPanX + worldToMapPixelOffset(Integer.parseInt(row[3]), origin[0], data.cellSize(), mapCell);
		int markerY = my + mapPanY + worldToMapPixelOffset(Integer.parseInt(row[5]), origin[1], data.cellSize(), mapCell);
		if (markerX < mx || markerY < my || markerX >= mx + view || markerY >= my + view) return;
		int maxTextWidth = Math.max(1, Math.min(140, view - 18));
		String[] lines = {
			font.plainSubstrByWidth(row[2], maxTextWidth),
			"X " + row[3] + "  Y " + row[4] + "  Z " + row[5],
			row[6].equals("1") ? "Abrissauftrag läuft" : "Kein Abrissauftrag"
		};
		int boxWidth = 0;
		for (String line : lines) boxWidth = Math.max(boxWidth, font.width(line));
		boxWidth = Math.min(view - 8, boxWidth + 10);
		int boxHeight = 38;
		int x = markerX + 8;
		if (x + boxWidth > mx + view - 2) x = markerX - boxWidth - 8;
		x = Math.max(mx + 2, Math.min(x, mx + view - boxWidth - 2));
		int y = markerY + 8;
		if (y + boxHeight > my + view - 2) y = markerY - boxHeight - 8;
		y = Math.max(my + 2, Math.min(y, my + view - boxHeight - 2));
		g.fill(x - 1, y - 1, x + boxWidth + 1, y + boxHeight + 1, 0xFF151008);
		g.fill(x, y, x + boxWidth, y + boxHeight, 0xFFF3E8C8);
		label(g, font.plainSubstrByWidth(lines[0], boxWidth - 10), x + 5, y + 3, INK);
		label(g, font.plainSubstrByWidth(lines[1], boxWidth - 10), x + 5, y + 14, SOFT);
		label(g, font.plainSubstrByWidth(lines[2], boxWidth - 10), x + 5, y + 25, SOFT);
	}

	private void drawSelectedMapMarkerTooltip(GuiGraphicsExtractor g, int markerIndex, String name, String location, int mx, int my, int view) {
		int size = data.size();
		if (location == null || markerIndex < 0 || size <= 0 || markerIndex >= size * size) return;
		int col = markerIndex % size, row = markerIndex / size;
		int markerX = mx + mapPanX + col * mapCell + mapCell / 2;
		int markerY = my + mapPanY + row * mapCell + mapCell / 2;
		if (markerX < mx || markerY < my || markerX >= mx + view || markerY >= my + view) return;
		String[] lines = {name, location};
		int maxTextWidth = Math.max(1, Math.min(140, view - 18));
		for (int i = 0; i < lines.length; i++) lines[i] = font.plainSubstrByWidth(lines[i], maxTextWidth);
		int boxWidth = Math.min(view - 8, Math.max(font.width(lines[0]), font.width(lines[1])) + 10);
		int boxHeight = 27;
		int x = markerX + 8;
		if (x + boxWidth > mx + view - 2) x = markerX - boxWidth - 8;
		x = Math.max(mx + 2, Math.min(x, mx + view - boxWidth - 2));
		int y = markerY + 8;
		if (y + boxHeight > my + view - 2) y = markerY - boxHeight - 8;
		y = Math.max(my + 2, Math.min(y, my + view - boxHeight - 2));
		g.fill(x - 1, y - 1, x + boxWidth + 1, y + boxHeight + 1, 0xFF151008);
		g.fill(x, y, x + boxWidth, y + boxHeight, 0xFFF3E8C8);
		label(g, lines[0], x + 5, y + 3, INK);
		label(g, lines[1], x + 5, y + 14, SOFT);
	}

	private int rowTop() {
		return top + 64;
	}

	private static final int FOOTER_LINE_HEIGHT = 11;
	private static final int RESIDENT_ROW_TOP = 64;
	private static final int LIST_ROW_TOP = 52;

	static int headerTitleMaxWidth() {
		return RENAME_BUTTON_X - HEADER_TEXT_X - HEADER_BUTTON_GAP;
	}

	static String headerTitle(net.minecraft.client.gui.Font font, String village, net.minecraft.core.BlockPos board) {
		String title = "Dorftafel  ·  " + village + "  (" + board.getX() + " " + board.getY() + " " + board.getZ() + ")";
		int maxWidth = headerTitleMaxWidth();
		if (font.width(title) <= maxWidth) return title;

		String ellipsis = "…";
		String prefix = font.plainSubstrByWidth(title, maxWidth - font.width(ellipsis)).stripTrailing();
		return prefix + ellipsis;
	}

	static int residentContentBottomOffset(boolean hasSelection) {
		return H - 30 - (hasSelection ? 52 : 0);
	}

	static int residentFooterYOffset(boolean hasSelection, int hintLines) {
		return residentContentBottomOffset(hasSelection) - Math.max(0, hintLines) * FOOTER_LINE_HEIGHT;
	}

	static int residentVisibleRows(boolean hasSelection, int hintLines) {
		return Math.max(0, (residentFooterYOffset(hasSelection, hintLines) - RESIDENT_ROW_TOP) / FOOTER_LINE_HEIGHT);
	}

	static int listFooterYOffset(int hintLines) {
		return H - 30 - Math.max(0, hintLines) * FOOTER_LINE_HEIGHT;
	}

	static int listVisibleRows(int hintLines) {
		return Math.max(0, (listFooterYOffset(hintLines) - LIST_ROW_TOP) / FOOTER_LINE_HEIGHT);
	}

	private List<String> wrapFooterText(String text, int maxWidth) {
		List<String> wrapped = new ArrayList<>();
		String rest = text.strip();
		while (!rest.isEmpty()) {
			String part = font.plainSubstrByWidth(rest, maxWidth);
			if (part.isEmpty()) {
				part = rest.substring(0, Character.charCount(rest.codePointAt(0)));
			}
			if (part.length() < rest.length()) {
				int space = part.lastIndexOf(' ');
				if (space > 0) part = part.substring(0, space);
			}
			wrapped.add(part);
			rest = rest.substring(part.length()).stripLeading();
		}
		return wrapped;
	}

	private List<String> residentHintLines() {
		return wrapFooterText(residentHintText(data.residents().size()), W - 32);
	}

	static String residentHintText(int residentCount) {
		return residentCount + " Einwohner · Auswahl mit Pfeiltasten oder Maus ändern, dann zuteilen";
	}

	private int visibleRows() {
		return residentVisibleRows(selected >= 0, residentHintLines().size());
	}

	private void residents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int y = top + 50;
		label(g, "Name", left + 12, y, INK);
		label(g, "Beruf", left + 110, y, INK);
		label(g, "Tätigkeit", left + 220, y, INK);
		label(g, "Stimmung", left + 348, y, INK);
		g.fill(left + 10, y + 10, left + W - 10, y + 11, 0xFFB79C6B);
		List<String> rows = data.residents();
		int visible = visibleRows();
		scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
		y = rowTop();
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
			String[] p = rows.get(i).split("\\|", 9);
			if (p.length < 5) {
				continue;
			}
			boolean hover = mouseX >= left + 8 && mouseX < left + W - 8 && mouseY >= y - 1 && mouseY < y + 10;
			if (i == selected) {
				g.fill(left + 8, y - 1, left + W - 8, y + 10, 0xFFE2C98A);
			} else if (hover) {
				g.fill(left + 8, y - 1, left + W - 8, y + 10, 0xFFEFE0B8);
			} else if (i % 2 == 0) {
				g.fill(left + 8, y - 1, left + W - 8, y + 10, 0xFFEDE1C0);
			}
			int mood = parse(p[3]);
			label(g, font.plainSubstrByWidth(p[0] + ("ja".equals(p[4]) ? "" : "  (ohne Haus)"), 96), left + 12, y, INK);
			label(g, font.plainSubstrByWidth(p[1], 106), left + 110, y, INK);
			label(g, font.plainSubstrByWidth(p[2], 124), left + 220, y, SOFT);
			g.fill(left + 348, y + 1, left + 348 + 52, y + 8, 0xFF5A4A30);
			g.fill(left + 349, y + 2, left + 349 + 50 * Math.max(0, Math.min(100, mood)) / 100, y + 7,
				mood >= 60 ? 0xFF4FB84F : mood >= 35 ? 0xFFD8B33A : 0xFFC84A4A);
			y += 11;
		}
		if (selected >= 0 && selected < rows.size()) {
			String[] p = rows.get(selected).split("\\|", 9);
			int py = top + H - 30 - 52;
			g.fill(left + 8, py, left + W - 8, py + 50, 0xFFE2C98A);
			boolean child = p.length >= 8 && p[7].equalsIgnoreCase("true");
			label(g, (child ? "Lehrlingsziel für " : "Beruf zuteilen für ") + p[0] + ":", left + 12, py + 3, INK);
		}
		List<String> hint = residentHintLines();
		int hintY = top + residentFooterYOffset(selected >= 0, hint.size());
		for (String line : hint) {
			label(g, line, left + 12, hintY, SOFT);
			hintY += FOOTER_LINE_HEIGHT;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		event = scaledMouse(event);
		if (drawingZone && event.button() != 0) {
			zoneNotice = "Zonenziehen abgebrochen; es wurde nichts gespeichert.";
			cancelZoneDrawing();
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (tab == 0) {
			if (movePending && event.button() == 0 && insideMap(event.x(), event.y())) {
				int[] p = mapBlock(event.x(), event.y());
				String[] row = selectedBuildingRow();
				if (p != null && row != null) {
					ClientPlayNetworking.send(new de.villagerplus.net.BuildingAction(data.board(),
						new net.minecraft.core.BlockPos(Integer.parseInt(row[3]), Integer.parseInt(row[4]), Integer.parseInt(row[5])),
						new net.minecraft.core.BlockPos(p[0], Integer.parseInt(row[4]), p[1]), "move"));
				}
				movePending = false; networkVisibility(); return true;
			}
			if (zoneTool && event.button() == 0 && insideMap(event.x(), event.y())
				&& !hasSelectableMapTarget(event.x(), event.y())) {
				if (zoneKind == ZONE_DELETE_KIND) {
					int[] p = mapBlock(event.x(), event.y());
					if (p == null) { zoneNotice = "Leeres Kartenfeld: Hier liegt keine Zone zum Löschen."; return true; }
					boolean found = false;
					for (String row : quota) if (row.startsWith("@z|")) {
						String[] z = row.split("\\|"); if (z.length >= 7 && p[0] >= parse(z[3]) && p[0] <= parse(z[5]) && p[1] >= parse(z[4]) && p[1] <= parse(z[6])) {
							submitZone(0, 0, 0, 0, z[1], true);
							found = true;
							break;
						}
					}
					if (!found) zoneNotice = "An dieser Kartenstelle liegt keine Zone zum Löschen.";
					zoneTool = false; zoneKind = 0; if (zoneButton != null) zoneButton.setMessage(Component.literal("Zone zeichnen")); return true;
				}
				int[] p = mapGridCell(event.x(), event.y());
				if (p == null) { zoneNotice = "Leeres Kartenfeld: Wähle ein sichtbares Dorf-Kartenfeld."; return true; }
				zoneNotice = null;
				ZoneSelection start = zoneSelectionFor(p[0], p[1], p[0], p[1]);
				if (start == null) {
					zoneNotice = "Startfeld liegt außerhalb der zulässigen Dorfzone.";
					return true;
				}
				zoneStartX = zoneEndX = p[0]; zoneStartZ = zoneEndZ = p[1];
				zoneDragStartPointerX = zonePointerX = event.x();
				zoneDragStartPointerY = zonePointerY = event.y();
				zoneClipped = start.clipped();
				drawingZoneKind = zoneKind;
				drawingZone = true; zoneTool = false;
				if (zoneButton != null) zoneButton.setMessage(Component.literal("Zone zeichnen")); return true;
			}
			if (insideMap(event.x(), event.y()) && (event.button() == 1 || event.button() == 2)) {
				draggingMap = true; dragCarryX = dragCarryY = 0; return true;
			}
			if (event.button() != 0) return false;
			int[] org = mapOrigin();
			int[] viewport = mapViewportBounds(left, top);
			int mx0 = viewport[0], my0 = viewport[1];
			if (insideMap(event.x(), event.y())) {
				int mapPixelX = (int)event.x() - mx0 - mapPanX;
				int mapPixelY = (int)event.y() - my0 - mapPanY;
				int mapCol = Math.floorDiv(mapPixelX, mapCell), mapRow = Math.floorDiv(mapPixelY, mapCell);
				if (mapCol < 0 || mapRow < 0 || mapCol >= data.size() || mapRow >= data.size()) {
					pickedBuilding = -1;
					clearMapMarkerSelection();
					clearNeighborMapPinSelection();
					networkVisibility();
					return true;
				}
			NeighborMapPin neighborPin = neighborMapPinAtPointer(event.x(), event.y(), org);
				int best = -1, bestD = 49;
				if (org != null) {
					int wx = org[0] + mapPixelToWorldOffset(mapPixelX, data.cellSize(), mapCell);
					int wz = org[1] + mapPixelToWorldOffset(mapPixelY, data.cellSize(), mapCell);
					java.util.List<String[]> rows = buildingRows();
					for (int i = 0; i < rows.size(); i++) {
						int dx = Integer.parseInt(rows.get(i)[3]) - wx, dz = Integer.parseInt(rows.get(i)[5]) - wz;
						int dd = dx * dx + dz * dz;
						if (dd < bestD) { bestD = dd; best = i; }
					}
				}
				int markerIndex = nearbySelectableMapMarker(mapCol, mapRow);
				if (insideMap(event.x(), event.y()) && doubleClick && markerIndex < 0 && neighborPin == null) {
					mapCell = MAP_BASE_CELL; clampMap(); return true;
				}
				if (best >= 0 && neighborPin != null && pickedBuilding == best) {
					pickedBuilding = -1;
					clearMapMarkerSelection();
					selectNeighborMapPin(neighborPin);
				} else if (best >= 0 && markerIndex >= 0 && pickedBuilding == best) {
					pickedBuilding = -1;
					clearNeighborMapPinSelection();
					pickedMapMarker = markerIndex;
					pickedMapMarkerCode = data.marks()[markerIndex] & 0xFF;
				} else if (best >= 0) {
					pickedBuilding = best;
					clearMapMarkerSelection();
					clearNeighborMapPinSelection();
				} else if (neighborPin != null) {
					pickedBuilding = -1;
					clearMapMarkerSelection();
					selectNeighborMapPin(neighborPin);
				} else {
					pickedBuilding = -1;
					clearNeighborMapPinSelection();
					if (markerIndex >= 0) {
						pickedMapMarker = markerIndex;
						pickedMapMarkerCode = data.marks()[markerIndex] & 0xFF;
					} else {
						clearMapMarkerSelection();
					}
				}
				networkVisibility();
				return true;
			}
			return false;
		}

		if (tab == 7 && event.button() == 0 && event.x() >= left + 10 && event.x() < left + W - 10
			&& event.y() >= top + 85 && event.y() < top + 217) {
			int index = ((int)event.y() - top - 85) / 11 + scroll;
			List<String[]> rows = filteredStock();
			if (index >= 0 && index < rows.size()) {
				String[] row = rows.get(index); stockItem = row[0]; stockType = row[1];
				stockSlider.target(row[3].equals("-1") ? Integer.parseInt(row[2]) : Integer.parseInt(row[3])); networkVisibility();
			}
			return true;
		}

		if (tab == 6) {
			return false;
		}
		if (tab == 4) {
			List<String> rows = relationshipRows(data.neighbors(), data.partners());
			int row = neighborRowAt(event.x(), event.y(), left, top, scroll, rows.size());
			if (row >= 0) {
				neighbor = row;
				neighborNotice = relationshipAvailabilityNotice(rows.get(row).split("\\|", -1), clientDimensionId());
				networkVisibility();
				return true;
			}
			return false;
		}
		if (tab != 1) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		List<String> rows = data.residents();
		int y = rowTop();
		for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
			if (mx >= left + 8 && mx < left + W - 8 && my >= y - 1 && my < y + 10) {
				selected = selected == i ? -1 : i;
				talkResidentId = selected < 0 ? null : residentIdentity(rows.get(selected));
				if (selected >= 0 && residentJobSlider != null) residentJobSlider.select(jobIndexForResident(rows.get(selected)));
				networkVisibility();
				return true;
			}
			y += 11;
		}
		return false;
	}

	private void ensureQuotaSliders() {
		if (left == 0 && top == 0) return;
		int row = 0;
		for (String entry : quota) {
			if (entry.startsWith("@")) continue;
			String[] fields = entry.split("\\|");
			if (fields.length < 5) continue;
			String job = fields[0];
			QuotaSlider slider = quotaSliders.get(job);
			int y = top + 60 + row * 12;
			if (slider == null) {
				slider = addRenderableWidget(new QuotaSlider(job, left + 330, y, 88, parse(fields[3])));
				quotaSliders.put(job, slider);
			} else {
				slider.setTargetSilently(parse(fields[3]));
			}
			slider.setY(y);
			slider.visible = tab == 6;
			slider.active = tab == 6 && !job.equals("none");
			row++;
		}
	}

	private void setQuotaTarget(String job, int target) {
		String row = quota.stream().filter(q -> !q.startsWith("@") && q.startsWith(job + "|")).findFirst().orElse(null);
		if (row == null) return;
		String[] p = row.split("\\|"); if (p.length < 4) return;
		target = Math.max(0, Math.min(40, target));
		// A stale response can equal the new value while a different request is still in flight.
		ClientPlayNetworking.send(new de.villagerplus.net.SetQuota(data.board(), job, 41 + target));
		List<String> updated = new ArrayList<>(quota);
		for (int i = 0; i < updated.size(); i++) if (updated.get(i).startsWith(job + "|")) {
			String[] fields = updated.get(i).split("\\|"); fields[3] = Integer.toString(target); updated.set(i, String.join("|", fields)); break;
		}
		quota = List.copyOf(updated);
	}

	private String quotaLabel(String job) {
		String row = quota.stream().filter(q -> !q.startsWith("@") && q.startsWith(job + "|")).findFirst().orElse(null);
		if (row == null) return job;
		String[] fields = row.split("\\|");
		return fields.length >= 2 ? fields[1] : job;
	}

	private int jobIndexForResident(String row) {
		String[] fields = row.split("\\|", 9);
		String current = fields.length >= 7 ? fields[6] : "minecraft:none";
		if (fields.length >= 9 && fields[7].equalsIgnoreCase("true") && !fields[8].isBlank()) {
			current = fields[8];
		}
		for (int i = 0; i < JOBS.length; i++) if (JOBS[i][0].equals(current)) return i;
		// Backward compatibility while an older server sends only the human-readable apprenticeship label.
		if (fields.length >= 2 && fields[1].startsWith("Lehrling → ")) {
			String target = fields[1].substring("Lehrling → ".length());
			int xp = target.indexOf(" · ");
			if (xp >= 0) target = target.substring(0, xp);
			for (int i = 0; i < JOBS.length; i++) if (JOBS[i][1].equals(target)) return i;
		}
		return JOBS.length - 1;
	}

	private void assignSelectedResidentJob() {
		if (selected < 0 || selected >= data.residents().size() || residentJobSlider == null) return;
		String[] fields = data.residents().get(selected).split("\\|", 7);
		if (fields.length < 6) return;
		try {
			ClientPlayNetworking.send(new AssignJob(data.board(), UUID.fromString(fields[5]), JOBS[residentJobSlider.selectedJob()][0]));
		} catch (IllegalArgumentException ignored) {
			// Invalid resident UUID: leave the selection unchanged.
		}
	}

	private boolean selectedResidentIsChild() {
		if (selected < 0 || selected >= data.residents().size()) return false;
		String[] fields = data.residents().get(selected).split("\\|", 9);
		return fields.length >= 8 && fields[7].equalsIgnoreCase("true");
	}

	private int[] mapBlock(double x, double y) {
		int[] org = mapOrigin(); if (org == null) return null;
		int[] viewport = mapViewportBounds(left, top);
		int mx = viewport[0], my = viewport[1];
		int pixelX = (int)x - mx - mapPanX, pixelY = (int)y - my - mapPanY;
		int col = Math.floorDiv(pixelX, mapCell), row = Math.floorDiv(pixelY, mapCell);
		if (col < 0 || row < 0 || col >= data.size() || row >= data.size()) return null;
		return new int[]{org[0] + mapPixelToWorldOffset(pixelX, data.cellSize(), mapCell),
			org[1] + mapPixelToWorldOffset(pixelY, data.cellSize(), mapCell)};
	}

	/** Returns the selected terrain cell; zones use full world-cell bounds for both preview and server action. */
	private int[] mapGridCell(double x, double y) {
		return mapGridCell(x, y, false);
	}

	private int[] mapGridCell(double x, double y, boolean clampEndpoint) {
		int[] viewport = mapViewportBounds(left, top);
		return mapViewportGridCell(x, y, viewport[0], viewport[1], viewport[2],
			mapPanX, mapPanY, mapCell, data.size(), clampEndpoint);
	}

	private ZoneSelection currentZoneSelection() {
		return zoneSelectionFor(zoneStartX, zoneStartZ, zoneEndX, zoneEndZ);
	}

	private ZoneSelection zoneSelectionFor(int startCol, int startRow, int endCol, int endRow) {
		int[] origin = mapOrigin();
		if (origin == null || data.size() <= 0 || data.cellSize() <= 0) return null;
		byte[] marks = data.marks();
		for (int i = 0; i < Math.min(marks.length, data.size() * data.size()); i++) {
			if ((marks[i] & 0xFF) != 1) continue;
			int col = i % data.size(), row = i / data.size();
			int bellX = origin[0] + col * data.cellSize() + data.cellSize() / 2;
			int bellZ = origin[1] + row * data.cellSize() + data.cellSize() / 2;
			return boundedZoneSelection(origin[0], origin[1], data.cellSize(), data.size(), bellX, bellZ,
				startCol, startRow, endCol, endRow);
		}
		return null;
	}

	private ZoneSelection updateZoneEnd(int endCol, int endRow) {
		zoneEndX = endCol;
		zoneEndZ = endRow;
		ZoneSelection selection = zoneSelectionFor(zoneStartX, zoneStartZ, endCol, endRow);
		zoneClipped = selection != null && selection.clipped();
		return selection;
	}

	private void updateZoneEndFromDrag(int endCol, int endRow) {
		if (updateZoneEnd(endCol, endRow) == null) zoneNotice = "Auswahl kann nicht innerhalb der Dorfgrenze liegen.";
		else zoneNotice = null;
	}

	private void neighbors(GuiGraphicsExtractor g) {
		label(g, "Handels- und Besuchsverlauf", left + 12, top + 51, INK);
		List<String> caravanRows = new ArrayList<>();
		for (String row : data.partners()) {
			String[] p = row.split("\\|", -1);
			if (p.length < 4) continue;
			if (p[0].equals("@guest")) continue;
			if (confirmedTradeContact(p)) caravanRows.add(caravanContactLabel(p));
		}
		String caravanSummary = caravanRows.isEmpty() ? "keine bestätigten Handelskontakte" : String.join(" · ", caravanRows);
		List<String> guestRows = guestPresenceLabels(data.neighbors(), data.partners());
		String guestSummary = guestRows.isEmpty() ? "keine Gäste anwesend" : String.join(" · ", guestRows);
		label(g, font.plainSubstrByWidth("Handelsorte: " + caravanSummary, W - 24), left + 12, top + 63, SOFT);
		label(g, font.plainSubstrByWidth("Gäste vor Ort: " + guestSummary, W - 24), left + 12, top + 74, 0xFF477342);
		List<String> rows = relationshipRows(data.neighbors(), data.partners());
		if (neighborNotice == null) {
			String prompt = neighborSelectionPrompt(rows);
			label(g, font.plainSubstrByWidth(prompt, W - 24), left + 12, top + 99, INK);
		} else {
			int color = neighborNotice.startsWith("✓ ") ? 0xFF477342 : neighborNotice.startsWith("⏳ ") ? 0xFF9A6A16 : 0xFF9D3023;
			List<String> noticeLines = wrapNoticeLines(neighborNotice, font, W - 24, 2);
			for (int i = 0; i < noticeLines.size(); i++) label(g, noticeLines.get(i), left + 12, top + 84 + i * 10, color);
		}
		scroll = Math.min(scroll, Math.max(0, rows.size() - 3));
		int y = top + 111;
		for (int i = scroll; i < Math.min(rows.size(), scroll + 3); i++) {
			String[] p = rows.get(i).split("\\|", -1);
			if (p.length < 7) continue;
			if (i == neighbor) g.fill(left + 9, y - 1, left + W - 9, y + 35, 0xFFE2C98A);
			if (historicalRelationship(p)) {
				label(g, font.plainSubstrByWidth(p[0] + " · " + p[1] + " · " + p[4], W - 24), left + 12, y, INK);
				label(g, "Nur Verlauf · keine Dorfkarten-Verbindung", left + 12, y + 9, SOFT);
				label(g, "Für Aktionen beide Dörfer mit Dorfkarten verknüpfen.", left + 12, y + 18, 0xFF9D3023);
				label(g, "Auswahl zeigt den Kontakt; sie erstellt keinen Link.", left + 12, y + 27, SOFT);
			} else {
				List<String> info = neighborDisplayLines(p, font, W - 24);
				for (int line = 0; line < info.size(); line++) {
					label(g, info.get(line), left + 12, y + line * 9, line == 0 ? INK : SOFT);
				}
			}
			y += 36;
		}
		if (rows.isEmpty()) label(g, font.plainSubstrByWidth("Noch keine bestätigten Handels- oder Besuchsorte bekannt.", W - 24), left + 12, y, SOFT);
	}

	/** Explains why relationship actions are inactive before a real linked neighbor is selected. */
	static String neighborSelectionPrompt(List<String> rows) {
		if (rows == null || rows.isEmpty()) {
			return "Keine Orte bekannt. Verbinde beide Tafeln mit Dorfkarten.";
		}
		boolean hasLinkedVillage = rows.stream().filter(java.util.Objects::nonNull)
			.map(row -> row.split("\\|", -1))
			.anyMatch(row -> !historicalRelationship(row) && hasNeighborIdentity(row));
		if (!hasLinkedVillage) {
			return "Partnerorte oben sind Handelsverlauf, keine Dorfverbindung. Verbinde beide Tafeln mit Dorfkarten; danach werden die Aktionen aktiv.";
		}
		return "Wähle ein verknüpftes Nachbardorf; verfügbare Aktionen werden freigeschaltet.";
	}

	/** Four compact lines keep the relationship, transport, balance, and project vote visible together. */
	static List<String> neighborDisplayLines(String[] row, net.minecraft.client.gui.Font font, int maxWidth) {
		if (row == null || row.length < 13 || historicalRelationship(row) || font == null || maxWidth <= 0) return List.of();
		String relationship = " · Beziehung " + (row[4].isBlank() ? "unbekannt" : row[4]);
		int nameWidth = maxWidth - font.width(relationship);
		String firstLine = nameWidth > 0
			? font.plainSubstrByWidth(row[0], nameWidth).stripTrailing() + relationship
			: font.plainSubstrByWidth(relationship, maxWidth);
		String rail = row.length >= 10 && !row[9].isBlank() ? row[9] : "unbekannt";
		String route = row.length >= 16 && !row[15].isBlank() ? row[15] : "unbekannt";
		String road = row.length >= 6 && !row[5].isBlank() ? row[5] : "unbekannt";
		String balance = row.length >= 8 && !row[7].isBlank() ? row[7] : "unbekannt";
		String approval = row.length >= 9 && !row[8].isBlank() ? row[8] : "unbekannt";
		approval = approval.replace("dein Ja", "du: Ja").replace("dein Nein", "du: Nein")
			.replace("Nachbar Ja", "Nachbar: Ja").replace("Nachbar Nein", "Nachbar: Nein");
		if (approval.equals("du: Ja / Nachbar: Ja")) approval = "beide zugestimmt";
		return List.of(firstLine,
			fitNeighborLine("Straße: " + road + " · Route: " + route + " · Bahn: " + rail, font, maxWidth),
			fitNeighborLine("Handelsbilanz: " + balance, font, maxWidth),
			fitNeighborLine("Projektzustimmung: " + approval, font, maxWidth));
	}

	private static String fitNeighborLine(String text, net.minecraft.client.gui.Font font, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		String ellipsis = "…";
		return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width(ellipsis))) + ellipsis;
	}

	/** Actionable bell links first, then trade and live guest history; names without a bell remain non-actionable. */
	static List<String> relationshipRows(List<String> linkedRows, List<String> contactRows) {
		List<String> rows = new ArrayList<>();
		Set<String> knownNames = new HashSet<>();
		if (linkedRows != null) for (String row : linkedRows) {
			if (row == null || row.isBlank()) continue;
			String[] fields = row.split("\\|", -1);
			if (!hasNeighborIdentity(fields) || fields[0].isBlank()) continue;
			rows.add(row);
			knownNames.add(relationshipNameKey(fields[0]));
		}
		if (contactRows != null) for (String row : contactRows) {
			if (row == null) continue;
			String[] fields = row.split("\\|", -1);
			if (!confirmedTradeContact(fields)) continue;
			String name = fields[0];
			String key = relationshipNameKey(name);
			if (key.isEmpty() || !knownNames.add(key)) continue;
			rows.add(historicalRelationshipRow(name, "Handel · " + fields[1], "Beziehung " + fields[2] + " · " + fields[3]));
		}
		Map<String, String> guestNames = new LinkedHashMap<>();
		Map<String, Integer> guestCounts = new LinkedHashMap<>();
		if (contactRows != null) for (String row : contactRows) {
			if (row == null) continue;
			String[] fields = row.split("\\|", -1);
			if (fields.length < 3 || !"@guest".equals(fields[0])) continue;
			String name = guestOriginName(fields);
			int count = positiveCount(fields[2]);
			String key = relationshipNameKey(name);
			if (name == null || key.isEmpty() || count == 0 || knownNames.contains(key)) continue;
			guestNames.putIfAbsent(key, name);
			guestCounts.merge(key, count, Integer::sum);
		}
		for (Map.Entry<String, Integer> entry : guestCounts.entrySet()) {
			int count = entry.getValue();
			String visitors = count + (count == 1 ? " Gast aktuell vor Ort" : " Gäste aktuell vor Ort");
			rows.add(historicalRelationshipRow(guestNames.get(entry.getKey()), "Besuch", visitors));
		}
		return List.copyOf(rows);
	}

	/** Keep every named guest origin visible, while spelling out when it has no confirmed bell contact. */
	static List<String> guestPresenceLabels(List<String> linkedRows, List<String> contactRows) {
		Map<String, String> knownPlaces = new LinkedHashMap<>();
		Set<String> ambiguousLinks = new HashSet<>();
		if (linkedRows != null) for (String row : linkedRows) {
			if (row == null) continue;
			String[] fields = row.split("\\|", -1);
			if (!hasNeighborIdentity(fields) || fields[0].isBlank()) continue;
			String key = relationshipNameKey(fields[0]);
			if (knownPlaces.containsKey(key)) ambiguousLinks.add(key);
			else knownPlaces.put(key, fields[0].strip());
		}
		if (contactRows != null) for (String row : contactRows) {
			if (row == null) continue;
			String[] fields = row.split("\\|", -1);
			if (confirmedTradeContact(fields)) knownPlaces.putIfAbsent(relationshipNameKey(fields[0]), fields[0].strip());
		}

		Map<String, Integer> guestCounts = new LinkedHashMap<>();
		Map<String, String> unconfirmedNames = new LinkedHashMap<>();
		Map<String, Integer> unconfirmedCounts = new LinkedHashMap<>();
		Set<String> ambiguousGuests = new HashSet<>();
		int guestsWithoutKnownOrigin = 0;
		if (contactRows != null) for (String row : contactRows) {
			if (row == null) continue;
			String[] fields = row.split("\\|", -1);
			if (fields.length < 3 || !"@guest".equals(fields[0])) continue;
			int count = positiveCount(fields[2]);
			if (count == 0) continue;
			String origin = guestOriginName(fields);
			String key = relationshipNameKey(origin);
			if (origin == null || key.isEmpty()) {
				guestsWithoutKnownOrigin += count;
				continue;
			}
			if (ambiguousLinks.contains(key) || !knownPlaces.containsKey(key)) {
				unconfirmedNames.putIfAbsent(key, origin);
				unconfirmedCounts.merge(key, count, Integer::sum);
				if (ambiguousLinks.contains(key)) ambiguousGuests.add(key);
				continue;
			}
			guestCounts.merge(key, count, Integer::sum);
		}

		List<String> labels = new ArrayList<>();
		for (Map.Entry<String, String> place : knownPlaces.entrySet()) {
			Integer guestCount = guestCounts.get(place.getKey());
			if (guestCount == null) continue;
			String name = place.getValue();
			int count = guestCount;
			labels.add(name + " · " + count + (count == 1 ? " Gast" : " Gäste") + " vor Ort");
		}
		for (Map.Entry<String, Integer> entry : unconfirmedCounts.entrySet()) {
			String status = ambiguousGuests.contains(entry.getKey()) ? "Ortskontakt uneindeutig" : "Besuchsort · keine Dorfverbindung";
			int count = entry.getValue();
			labels.add(unconfirmedNames.get(entry.getKey()) + " · " + count + (count == 1 ? " Gast" : " Gäste") + " (" + status + ")");
		}
		if (guestsWithoutKnownOrigin > 0) {
			labels.add(guestsWithoutKnownOrigin + (guestsWithoutKnownOrigin == 1 ? " Gast ohne bekannten Herkunftsort" : " Gäste ohne bekannten Herkunftsort"));
		}
		return List.copyOf(labels);
	}

	private static String guestOriginName(String[] fields) {
		if (fields == null || fields.length < 3 || fields[1] == null) return null;
		String origin = fields[1].strip();
		if (!origin.startsWith("Gast aus ")) return null;
		origin = origin.substring("Gast aus ".length()).strip();
		return origin.isEmpty() || origin.startsWith("Gast ohne") ? null : origin;
	}

	private static int positiveCount(String value) {
		try {
			int count = Integer.parseInt(value.strip());
			return Math.max(0, count);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static String historicalRelationshipRow(String name, String type, String detail) {
		String[] fields = {relationshipCell(name), relationshipCell(type), "", "", relationshipCell(detail),
			"Nur Verlauf · Dorfkarten-Link fehlt", "0", "Historie", "Dorfkarten-Verbindung fehlt", "—", "0", "", "", HISTORICAL_RELATIONSHIP};
		return String.join("|", fields);
	}

	private static String relationshipCell(String value) {
		return value == null ? "" : value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').strip();
	}

	private static String relationshipNameKey(String value) {
		return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
	}

	static boolean historicalRelationship(String[] row) {
		return row != null && row.length >= 14 && HISTORICAL_RELATIONSHIP.equals(row[13]);
	}

	static String relationshipAvailabilityNotice(String[] row) {
		return relationshipAvailabilityNotice(row, null);
	}

	static String relationshipAvailabilityNotice(String[] row, String currentDimension) {
		if (row == null) return "Wähle unten einen Ort aus, um Voraussetzungen und Aktionen zu sehen.";
		String name = row.length == 0 || row[0].isBlank() ? "Dieser Ort" : row[0];
		if (historicalRelationship(row)) return "✕ " + name + (row.length > 1 && "Besuch".equals(row[1])
			? ": nur als Besuchsort bekannt; Dorfkarten an beiden Tafeln verbinden."
			: ": nur Historie; Dorfkarten an beiden Tafeln verbinden.");
		if (!hasNeighborIdentity(row)) return "✕ " + name + ": Glockenverbindung fehlt oder ist veraltet.";
		if (currentDimension != null && !currentDimension.equals(row[11])) {
			return "✕ " + name + ": Dorfaktionen und Großprojekte brauchen ein Nachbardorf in derselben Dimension.";
		}
		if (row.length >= 15 && !row[14].isBlank()) {
			List<String> labels = new ArrayList<>();
			for (String action : row[14].split(",")) {
				if (action.equals("route_an")) labels.add("Route an");
				else if (action.equals("route_aus")) labels.add("Route aus");
				else if (action.equals("route")) labels.add("Route");
				else if (action.equals("greet")) labels.add("Gruß");
			}
			if (!labels.isEmpty()) return "⏳ " + name + ": " + String.join(" und ", labels)
				+ " vorgemerkt; Abgleich bei geladener Glocke und bestätigter Rückverbindung.";
		}
		if (row.length < 7 || !"1".equals(row[6])) {
			return "⏳ " + name + ": Route/Gruß werden dauerhaft vorgemerkt; Tausch und Gäste brauchen den geladenen Gegenort.";
		}
		if (row.length < 11 || !"1".equals(row[10])) return "✕ " + name + ": Rückverbindung fehlt; Zieltafel verknüpfen.";
		return "✓ " + name + ": geladen und beidseitig verbunden; Aktionen bereit.";
	}

	static String caravanContactLabel(String[] fields) {
		if (!confirmedTradeContact(fields)) return "";
		return fields[0] + " · " + fields[1] + " · Beziehung " + fields[2] + " · " + fields[3];
	}

	static boolean confirmedTradeContact(String[] fields) {
		return fields != null && fields.length >= 4 && fields[0] != null && !fields[0].isBlank()
			&& !"@guest".equals(fields[0]) && !fields[0].startsWith("Unbekannter Ort")
			&& fields[1] != null && !fields[1].isBlank()
			&& fields[3] != null && !fields[3].isBlank() && !fields[3].equalsIgnoreCase("noch nie");
	}

	static String guestOriginLabel(String[] fields) {
		if (fields == null || fields.length < 3) return "";
		String origin = fields[1];
		if (origin.startsWith("Gast aus ")) origin = origin.substring("Gast aus ".length());
		String count = fields[2].equals("1") ? "1 Gast" : fields[2] + " Gäste";
		return origin + " · " + count;
	}

	/** Visible neighbor row hitboxes; returns the exact backing-list index, including the current scroll offset. */
	static int neighborRowAt(double x, double y, int panelLeft, int panelTop, int scrollOffset, int rowCount) {
		if (x < panelLeft + 10 || x >= panelLeft + W - 10 || y < panelTop + 111 || y >= panelTop + 219
			|| scrollOffset < 0 || rowCount <= 0) return -1;
		int visibleRow = (int)Math.floor((y - (panelTop + 111)) / 36.0);
		if (visibleRow < 0 || visibleRow >= 3) return -1;
		int firstRow = Math.min(scrollOffset, Math.max(0, rowCount - 3));
		int index = firstRow + visibleRow;
		return index < rowCount ? index : -1;
	}

	static String neighborActionName(int index) {
		if (index < 0 || index >= NETWORK_ACTIONS.length) throw new IllegalArgumentException("Invalid neighbor action index: " + index);
		return NETWORK_ACTIONS[index];
	}

	private String[] selectedNeighborRow() {
		List<String> rows = relationshipRows(data.neighbors(), data.partners());
		if (neighbor < 0 || neighbor >= rows.size()) return null;
		return rows.get(neighbor).split("\\|", -1);
	}

	static int neighborIndex(List<String> rows, String[] identity) {
		if (rows == null || identity == null) return -1;
		for (int i = 0; i < rows.size(); i++) {
			String[] candidate = rows.get(i).split("\\|", -1);
			if (historicalRelationship(identity) || historicalRelationship(candidate)) {
				if (historicalRelationship(identity) && historicalRelationship(candidate)
					&& relationshipNameKey(identity[0]).equals(relationshipNameKey(candidate[0]))) return i;
			} else if (hasNeighborIdentity(identity) && hasNeighborIdentity(candidate)
				&& identity[11].equals(candidate[11]) && identity[12].equals(candidate[12])) return i;
		}
		return -1;
	}

	private static boolean hasNeighborIdentity(String[] row) {
		if (row == null || row.length < 13 || row[11].isBlank() || row[12].isBlank()) return false;
		try {
			Long.parseLong(row[12]);
			return true;
		} catch (NumberFormatException ignored) {
			return false;
		}
	}

	static String neighborActionFailure(String action, String[] row) {
		return neighborActionFailure(action, row, null);
	}

	static String neighborActionFailure(String action, String[] row, String currentDimension) {
		if ("card".equals(action)) return null;
		if (!"approve".equals(action) && !java.util.Arrays.asList(NETWORK_ACTIONS).contains(action)) return "Unbekannte Nachbaraktion.";
		if (row == null) return "Wähle unten ein Nachbardorf oder einen bekannten Kontakt aus.";
		if (historicalRelationship(row)) return row[0] + (row.length > 1 && "Besuch".equals(row[1])
			? " ist nur als Besuchsort bekannt. Verbinde beide Dörfer mit ihren Dorfkarten."
			: " ist nur als Handels-/Besuchsort bekannt. Verbinde beide Dörfer mit ihren Dorfkarten.");
		if (!hasNeighborIdentity(row)) return (row.length > 0 ? row[0] + ": " : "") + "Glockenverbindung fehlt oder ist veraltet. Dorfkarten neu verbinden.";
		if (currentDimension != null && !currentDimension.equals(row[11])) {
			return row[0] + ": Dorfaktionen und Großprojekte brauchen ein Nachbardorf in derselben Dimension.";
		}
		if (ACTION_GREET.equals(action) && row.length >= 15 && java.util.Arrays.asList(row[14].split(",")).contains("greet")) {
			return row[0] + ": Ein Gruß ist bereits vorgemerkt und wartet auf die geladene Rückverbindung.";
		}
		boolean canQueueRemotely = ACTION_ROUTE.equals(action) || ACTION_GREET.equals(action);
		if (!"approve".equals(action) && (row.length < 7 || !"1".equals(row[6])) && !canQueueRemotely) {
			return row[0] + " ist gerade nicht geladen. Diese Aktion braucht den Gegenort vor Ort.";
		}
		if (java.util.Arrays.asList(NETWORK_ACTIONS).contains(action) && (row.length < 11 || !"1".equals(row[10]))
			&& (!canQueueRemotely || row.length >= 7 && "1".equals(row[6]))) {
			return row[0] + ": Die Rückverbindung fehlt. Verknüpfe das Ausgangsdorf auch an der Zieltafel.";
		}
		return null;
	}

	static boolean networkActionEnabled(String[] row, boolean hasLinkedNeighbor, boolean pending) {
		return !pending && row != null && !historicalRelationship(row) && hasNeighborIdentity(row);
	}

	static boolean neighborActionEnabled(String action, String[] row, String currentDimension, boolean pending) {
		return networkActionEnabled(row, false, pending)
			&& neighborActionFailure(action, row, currentDimension) == null;
	}

	static NetworkAction neighborActionPayload(net.minecraft.core.BlockPos board, String action, int index, String[] row) {
		return neighborActionPayload(board, action, index, row, 1);
	}

	static NetworkAction neighborActionPayload(net.minecraft.core.BlockPos board, String action, int index, String[] row, int requestId) {
		if (board == null || requestId <= 0 || neighborActionFailure(action, row) != null) return null;
		if ("card".equals(action)) return new NetworkAction(board, action, -1, "", net.minecraft.core.BlockPos.ZERO, requestId);
		try {
			return new NetworkAction(board, action, index, row[11], net.minecraft.core.BlockPos.of(Long.parseLong(row[12])), requestId);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	static int nextNetworkActionRequestId(int current) {
		return current <= 0 || current == Integer.MAX_VALUE ? 1 : current + 1;
	}

	static boolean matchesNetworkActionResult(net.minecraft.core.BlockPos board, int requestId, NetworkActionResult result) {
		return board != null && result != null && requestId > 0 && result.requestId() == requestId && board.equals(result.board());
	}

	static String networkActionNotice(boolean success, String message) {
		String safeMessage = message == null || message.isBlank() ? "Keine Rückmeldung vom Dorf erhalten." : message;
		return (success ? "✓ " : "✕ ") + safeMessage;
	}

	/** Wrap server feedback without silently hiding the actionable part of a long response. */
	static List<String> wrapNoticeLines(String message, net.minecraft.client.gui.Font font, int maxWidth, int maxLines) {
		if (message == null || message.isBlank() || font == null || maxWidth <= 0 || maxLines <= 0) return List.of();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String token : message.trim().replace('\n', ' ').split("\\s+")) {
			String remaining = token;
			while (!remaining.isEmpty()) {
				String candidate = line.isEmpty() ? remaining : line + " " + remaining;
				if (font.width(candidate) <= maxWidth) {
					line.setLength(0);
					line.append(candidate);
					break;
				}
				if (!line.isEmpty()) {
					lines.add(line.toString());
					line.setLength(0);
					continue;
				}
				String part = font.plainSubstrByWidth(remaining, maxWidth);
				if (part.isEmpty()) part = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
				lines.add(part);
				remaining = remaining.substring(part.length());
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		if (lines.size() <= maxLines) return List.copyOf(lines);
		List<String> visible = new ArrayList<>(lines.subList(0, maxLines));
		String tail = String.join(" ", lines.subList(maxLines - 1, lines.size()));
		String ellipsis = "…";
		visible.set(maxLines - 1, font.plainSubstrByWidth(tail, Math.max(1, maxWidth - font.width(ellipsis))) + ellipsis);
		return List.copyOf(visible);
	}

	public boolean acceptNetworkActionResult(NetworkActionResult result) {
		if (pendingNetworkAction == null || !matchesNetworkActionResult(data.board(), pendingNetworkActionRequestId, result)) return false;
		neighborNotice = networkActionNotice(result.success(), result.message());
		pendingNetworkAction = null;
		pendingNetworkActionRequestId = 0;
		pendingNetworkActionTicks = 0;
		networkVisibility();
		return true;
	}

	private void sendNeighborAction(String action) {
		String[] row = "card".equals(action) ? null : selectedNeighborRow();
		if (pendingNetworkAction != null) {
			neighborNotice = "Eine Dorfaktion wird noch vom Server geprüft.";
			return;
		}
		String failure = row == null && !"card".equals(action) && data.neighbors().isEmpty()
			? "Kein verknüpftes Dorf ausgewählt. Kontakte unten sind nur Historie; Dorfkarten an beiden Tafeln verwenden."
			: neighborActionFailure(action, row, clientDimensionId());
		if (failure != null) {
			neighborNotice = "✕ " + failure;
			return;
		}
		int requestId = nextNetworkActionRequestId(networkActionRequestSequence);
		NetworkAction payload = neighborActionPayload(data.board(), action, "card".equals(action) ? -1 : neighbor, row, requestId);
		if (payload == null) {
			neighborNotice = "✕ Aktion konnte nicht zugeordnet werden. Dorftafel aktualisieren.";
			return;
		}
		networkActionRequestSequence = requestId;
		pendingNetworkActionRequestId = requestId;
		pendingNetworkActionTicks = 0;
		pendingNetworkAction = action;
		neighborNotice = "⏳ " + ("card".equals(action) ? "Dorfkarte wird vorbereitet."
			: neighborActionLabel(action) + " an " + row[0] + " wird vom Dorf geprüft.");
		networkVisibility();
		try {
			networkActionSender.accept(payload);
		} catch (RuntimeException failureToSend) {
			if (pendingNetworkActionRequestId == requestId) {
				pendingNetworkAction = null;
				pendingNetworkActionRequestId = 0;
				pendingNetworkActionTicks = 0;
				neighborNotice = "✕ Aktion konnte nicht gesendet werden. Verbindung prüfen und erneut versuchen.";
				networkVisibility();
			}
		}
	}

	private static String neighborActionLabel(String action) {
		return switch (action) {
			case "swap" -> "Tauschanfrage";
			case "route" -> "Routenänderung";
			case "invite" -> "Gästeeinladung";
			case "greet" -> "Gruß";
			case "approve" -> "Zustimmung";
			default -> "Anfrage";
		};
	}

	private static String clientDimensionId() {
		net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
		return level == null ? null : level.dimension().identifier().toString();
	}

 private void journal(GuiGraphicsExtractor g) {
  String wanted = new String[]{"Alle","Handel","Befinden","Bauen"}[category];
  String resident=person>=0 && person<data.residents().size()?data.residents().get(person).split("\\|")[0]:"";
  List<String> rows=new ArrayList<>();
  for(String row:data.journal()) {
   String[] p=row.split("\\|",4);if(p.length<4 || (category!=0&&!p[0].equals(wanted)) || (!resident.isEmpty()&&!p[2].equals(resident)&&!p[3].contains(resident)))continue;
   rows.add(p[1]+" · "+p[0]+" · "+p[2]);String rest=p[3];
   while(!rest.isEmpty()) {String line=font.plainSubstrByWidth(rest,W-30);if(line.isEmpty())break;rows.add("  "+line);rest=rest.substring(line.length());} rows.add("");
  }
  int visible=(H-110)/11;scroll=Math.min(scroll,Math.max(0,rows.size()-visible));int y=top+74;
  for(int i=scroll;i<Math.min(rows.size(),scroll+visible);i++){label(g,rows.get(i),left+12,y,rows.get(i).startsWith("  ")?SOFT:INK);y+=11;}
  if(rows.isEmpty())label(g,"Noch keine passenden Ereignisse aufgezeichnet.",left+12,y,SOFT);
 }

	static List<String> constructionDisplayLines(List<String> source, Function<String, String> itemName) {
		if (source == null || source.isEmpty()) return List.of();
		Function<String, String> names = itemName == null ? BoardScreen::friendlyItemName : itemName;
		Map<String, String> aliases = new HashMap<>();
		for (String row : source) {
			MaterialRow material = materialRow(row);
			if (material != null && material.id() != null) {
				String localized = names.apply(material.id());
				if (localized != null && !localized.isBlank()) aliases.put(materialKey(material.name()), localized);
			}
		}

		List<String> shown = new ArrayList<>(source.size() + 2);
		ConstructionNotice notice = null;
		for (String raw : source) {
			String line = raw == null ? "" : raw;
			String content = line.stripLeading();
			boolean projectHeader = content.startsWith("Baustelle LIVE:");
			if (notice != null && (projectHeader || !line.startsWith("  "))) {
				appendNextAction(shown, notice);
				notice = null;
			}
			if (projectHeader) notice = new ConstructionNotice();
			if (notice != null) notice.observe(content);
			shown.add(presentMaterialLine(compactStallLine(line), names, aliases));
		}
		if (notice != null) appendNextAction(shown, notice);
		return List.copyOf(shown);
	}

	private static void appendNextAction(List<String> lines, ConstructionNotice notice) {
		String action = notice.nextAction();
		if (action != null) lines.add("  Nächster Schritt: " + action);
	}

	private static final class ConstructionNotice {
		private boolean materialShortage;
		private boolean waitsForMaterial;
		private boolean waitsForBuilder;
		private boolean hasStall;
		private boolean otherWait;

		void observe(String line) {
			String lower = line.toLowerCase(Locale.ROOT);
			materialShortage |= lower.startsWith("davon fehlen im dorf:");
			waitsForMaterial |= lower.contains("wartet auf baumaterial");
			waitsForBuilder |= lower.contains("wartet auf einen verfügbaren baumeister");
			hasStall |= lower.startsWith("kein baufortschritt seit ") || lower.startsWith("stillstand:");
			otherWait |= lower.startsWith("wartet:");
		}

		String nextAction() {
			if (materialShortage) return "Fehlendes Material im Dorf beschaffen";
			if (waitsForBuilder) return "Baumeister verfügbar machen";
			if (waitsForMaterial) return "Vorhandenes Lager-Material zur Baustelle bringen";
			if (otherWait) return "Wartegrund prüfen";
			if (hasStall) return "Baustelle und Arbeitsweg prüfen";
			return null;
		}
	}

	private record MaterialRow(String label, String name, String amount, String id) { }

	private static MaterialRow materialRow(String raw) {
		if (raw == null) return null;
		String line = raw.stripLeading();
		int colon = line.indexOf(':');
		if (colon < 1) return null;
		String label = line.substring(0, colon + 1);
		String value = line.substring(colon + 1).strip();
		String id = null;
		int technical = value.lastIndexOf(" · ");
		if (technical >= 0) {
			String candidate = value.substring(technical + 3).strip();
			if (net.minecraft.resources.Identifier.tryParse(candidate) != null) {
				id = candidate;
				value = value.substring(0, technical).strip();
			}
		}
		int countMarker = value.lastIndexOf('×');
		if (countMarker < 0) return id == null ? null : new MaterialRow(label, value, "", id);
		String name = value.substring(0, countMarker).strip();
		String amount = value.substring(countMarker + 1).strip().replace(".", "").replace(",", "");
		if (name.isEmpty() || !amount.matches("\\d{1,18}")) return null;
		return new MaterialRow(label, name, amount, id);
	}

	private static String presentMaterialLine(String raw, Function<String, String> names, Map<String, String> aliases) {
		String indent = raw.substring(0, raw.length() - raw.stripLeading().length());
		String concise = compactStallLine(raw.stripLeading());
		MaterialRow material = materialRow(concise);
		if (material == null) return raw.isEmpty() ? raw : indent + concise;
		String shownName = material.name();
		if (material.id() != null) {
			String localized = names.apply(material.id());
			if (localized != null && !localized.isBlank()) shownName = localized;
		} else {
			shownName = aliases.getOrDefault(materialKey(shownName), shownName);
		}
		String quantity = material.amount().isEmpty() ? "" : " ×"
			+ java.text.NumberFormat.getIntegerInstance(Locale.GERMANY).format(Long.parseLong(material.amount()));
		return indent + material.label() + " " + shownName + quantity;
	}

	private static String materialKey(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	static String localizedMaterialName(String id) {
		var identifier = net.minecraft.resources.Identifier.tryParse(id);
		var item = identifier == null ? java.util.Optional.<net.minecraft.world.item.Item>empty()
			: net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(identifier);
		return item.map(value -> new net.minecraft.world.item.ItemStack(value).getHoverName().getString())
			.orElseGet(() -> friendlyItemName(id));
	}

	private static String friendlyItemName(String id) {
		if (id == null || id.isBlank()) return "unbekanntes Material";
		int colon = id.indexOf(':');
		String path = colon >= 0 ? id.substring(colon + 1) : id;
		StringBuilder result = new StringBuilder();
		for (String word : path.replace('_', ' ').split("\\s+")) {
			if (word.isEmpty()) continue;
			if (!result.isEmpty()) result.append(' ');
			result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.isEmpty() ? "unbekanntes Material" : result.toString();
	}

	private static String compactStallLine(String line) {
		String content = line;
		String legacyPrefix = "Kein Baufortschritt seit ";
		if (content.startsWith(legacyPrefix)) content = "Stillstand: " + content.substring(legacyPrefix.length());
		Matcher seconds = RAW_SECONDS.matcher(content);
		StringBuffer normalized = new StringBuffer();
		while (seconds.find()) {
			long value;
			try { value = Long.parseLong(seconds.group(1)); }
			catch (NumberFormatException ignored) { continue; }
			seconds.appendReplacement(normalized, Matcher.quoteReplacement(shortDuration(value)));
		}
		seconds.appendTail(normalized);
		content = normalized.toString();
		int remainder = content.lastIndexOf(" Sek.");
		if (remainder > 0 && (content.contains(" Tg.") || content.contains(" Std.") || content.contains(" Min."))) {
			int colon = content.indexOf(':');
			String label = colon >= 0 ? content.substring(0, colon + 1) + " " : "";
			String duration = colon >= 0 ? content.substring(colon + 1).strip() : content;
			if (!duration.startsWith("ca. ")) duration = "ca. " + duration;
			content = label + duration.substring(0, duration.lastIndexOf(" Sek.")).stripTrailing();
		}
		return content;
	}

	static String shortDuration(long seconds) {
		long safe = Math.max(0L, seconds);
		if (safe < 60L) return safe + " Sek.";
		long roundedMinutes = (safe + 30L) / 60L;
		long days = roundedMinutes / (24L * 60L);
		long hours = roundedMinutes % (24L * 60L) / 60L;
		long minutes = roundedMinutes % 60L;
		List<String> parts = new ArrayList<>(2);
		if (days > 0) parts.add(days + " Tg.");
		if (hours > 0) parts.add(hours + " Std.");
		if (minutes > 0 && parts.size() < 2) parts.add(minutes + " Min.");
		return "ca. " + String.join(" ", parts);
	}

	private void lines(GuiGraphicsExtractor g, List<String> src) {
		List<String> wrapped = new ArrayList<>();
		int maxW = W - 28;
		for (String line : src) {
			String raw = line.replace("===", "");
			boolean indented = raw.length() != raw.stripLeading().length();
			String rest = raw.strip();
			int indentWidth = indented ? 10 : 0;
			boolean first = true;
			if (rest.isEmpty()) {
				wrapped.add("");
			}
			while (!rest.isEmpty()) {
				String part = font.plainSubstrByWidth(rest, maxW - indentWidth - (first ? 0 : 12));
				if (part.length() < rest.length()) {
					int sp = part.lastIndexOf(' ');
					if (sp > 8) {
						part = part.substring(0, sp);
					}
				}
				wrapped.add((indented ? Character.toString(DETAIL_INDENT_MARKER) : "") + (first ? "" : "   ") + part);
				rest = rest.substring(part.length()).stripLeading();
				first = false;
			}
		}
		int defaultVisible = listVisibleRows(0);
		List<String> hint = wrapped.size() > defaultVisible
			? wrapFooterText("Mausrad: blättern", W - 24) : List.of();
		int visible = listVisibleRows(hint.size());
		scroll = Math.min(scroll, Math.max(0, wrapped.size() - visible));
		int y = top + LIST_ROW_TOP;
		for (int i = scroll; i < Math.min(wrapped.size(), scroll + visible); i++) {
			String s = wrapped.get(i);
			boolean indented = !s.isEmpty() && s.charAt(0) == DETAIL_INDENT_MARKER;
			String text = (indented ? s.substring(1) : s).stripLeading();
			int textX = left + 12 + (indented ? 10 : 0);
			int colon = text.indexOf(':');
			if (colon > 0 && colon < 28) {
				// "Titel: Wert" - Titel kraeftig, Wert normal
				String title = text.substring(0, colon + 1);
				int titleColor = detailStatusColor(title);
				label(g, title, textX, y, titleColor < 0 ? 0xFF8C2A22 : titleColor);
				label(g, text.substring(colon + 1), textX + font.width(title), y, INK);
			} else {
				label(g, text, textX, y, INK);
			}
			y += 11;
		}
		if (!hint.isEmpty()) {
			y = top + listFooterYOffset(hint.size());
			for (String line : hint) {
				label(g, line, left + 84, y, SOFT);
				y += FOOTER_LINE_HEIGHT;
			}
		}
	}

	/** Scrollable resident report. Content is built from the selected resident's server snapshot and full live construction list. */
	private void talk(GuiGraphicsExtractor g) {
		lines(g, selectedResidentTalkDisplayLines());
	}

	List<String> selectedResidentTalkDisplayLines() {
		return residentTalkDisplayLines(data.residentTalk(), talkResidentId, data.details(), BoardScreen::localizedMaterialName);
	}

	static List<String> residentTalkDisplayLines(List<String> residentRows, String residentId, List<String> villageDetails,
		Function<String, String> itemName) {
		if (residentId == null || residentId.isBlank()) {
			return List.of("Wähle im Einwohner-Reiter einen Bewohner aus, um seinen Steckbrief zu öffnen.");
		}
		String report = null;
		for (String row : residentRows == null ? List.<String>of() : residentRows) {
			if (residentTalkMatches(row, residentId)) { report = row; break; }
		}
		if (report == null) return List.of("Bewohnerdaten sind nicht mehr verfügbar. Wähle ihn erneut im Einwohner-Reiter aus.");
		String[] fields = report.split("\\|", 10);
		if (fields.length < 10) return List.of("Der Bewohner-Steckbrief ist unvollständig. Öffne die Dorftafel erneut.");

		List<String> lines = new ArrayList<>();
		lines.add("Bewohner: " + fields[1]);
		lines.add("Beruf: " + fields[2]);
		lines.add("Aktueller Auftrag: " + fields[3]);
		lines.add("Standort: " + fields[4]);
		lines.add("Wegziel: " + fields[5]);
		lines.add("Hunger: " + fields[6] + "%");
		lines.add("Stimmung: " + fields[7]);
		lines.add("Private Truhe: " + fields[8]);
		if (fields[8].equals("Inhalt")) {
			String contents = fields[9];
			if (contents.isBlank()) {
				lines.add("  leer");
			} else {
				for (String encoded : contents.split(";", -1)) {
					String[] item = encoded.split("#", 3);
					if (item.length < 3) continue;
					String visibleName = itemName == null ? friendlyItemName(item[1]) : itemName.apply(item[1]);
					lines.add("  Fach " + item[0] + ": " + item[2] + " × " + visibleName);
				}
			}
		} else if (fields[8].equals("leer")) {
			lines.add("  keine Gegenstände eingelagert");
		} else {
			lines.add("  " + fields[8]);
		}

		lines.add("Material für aktive Dorfbaustellen:");
		List<String> materials = (villageDetails == null ? List.<String>of() : villageDetails).stream()
			.filter(BoardScreen::isConstructionMaterialLine).toList();
		if (materials.isEmpty()) {
			lines.add("  kein offener Baustoffbedarf");
		} else {
			List<String> readable = constructionDisplayLines(materials, itemName);
			for (String line : readable) lines.add(line.isBlank() ? line : "  " + line.stripLeading());
		}
		return List.copyOf(lines);
	}

	private static boolean isConstructionMaterialLine(String row) {
		if (row == null) return false;
		String line = row.stripLeading();
		return line.startsWith("Baustelle LIVE:") || line.startsWith("Zur Baustelle:") || line.startsWith("Noch zu beschaffen:")
			|| line.startsWith("Noch zu liefern:") || line.startsWith("Davon fehlen im Dorf:");
	}

	private static boolean residentTalkMatches(String row, String residentId) {
		return row != null && residentId != null && !residentId.isBlank() && row.startsWith(residentId + "|");
	}

	private static String residentIdentity(String residentRow) {
		if (residentRow == null) return null;
		String[] fields = residentRow.split("\\|", 7);
		if (fields.length < 6) return null;
		try {
			return UUID.fromString(fields[5]).toString();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private void requestTalk() {
		if (tab != 8 || talkResidentId == null || talkResidentId.isBlank()) {
			talkRequestId = 0;
			return;
		}
		talkRequestSequence = nextTalkRequestId(talkRequestSequence);
		talkRequestId = talkRequestSequence;
		ClientPlayNetworking.send(new BoardRequest(data.board(), talkResidentId, talkRequestId));
	}

	static int nextTalkRequestId(int current) {
		return current == Integer.MAX_VALUE ? 1 : Math.max(1, current + 1);
	}

	static int detailStatusColor(String title) {
		return switch (title.strip()) {
			case "Stillstand:" -> 0xFFB44A26;
			case "Nächster Schritt:" -> 0xFF3C6B39;
			case "Noch zu liefern:" -> 0xFF9A6818;
			case "Davon fehlen im Dorf:" -> 0xFFC02D24;
			default -> -1;
		};
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int parseHex(String s) {
		try {
			return Integer.parseInt(s.trim(), 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return 0x888888;
		}
	}
}
