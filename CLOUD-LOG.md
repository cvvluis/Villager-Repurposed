# CLOUD-LOG (Branch claude/village-growth-1111)

Nur belegte Fakten. "In-world geprueft" = Dev-Server-Lauf; "Scratch" = reine Logik in einer Wegwerf-JVM; "kompiliert" = kein Nachweis.

## Umgebung (P0)
- Branch von origin/codex/playtest-2026-11-11 (01db13e); Skripte, Sites.walksBackTo vorhanden.
- JDK 25.0.5 per tools/cloud/setup-env.sh installiert (idempotent, ok). Python 3.13. 4 vCPU / 15 GB.
- ./gradlew build: 1. Versuch scheiterte mit HTTP 429 von repo.maven.apache.org (Ratelimit, kein Hostblock); 2. Versuch BUILD SUCCESSFUL (49 s).
- **BLOCKER: run/eula.txt fehlt** (EULA ist Sache des Nutzers, wird nicht von mir geschrieben). Damit sind alle Dev-Server-Laeufe
  (autotest.sh, natural-run.sh) gesperrt. P0-Referenzwerte (66/140/318/30/22) konnten NICHT geprueft werden.
- tools/cloud/*.sh gelesen: autotest.sh/setup-env.sh unter Linux plausibel (setup-env lief); autotest/natural-run ungetestet (EULA).

## P1 - Rueckholung ohne Route
Geaendert: job/JobUtil.java (StrandWatch: noteRouteFailure/routeStrandSuspected/markRescued, Hooks in approachLong),
job/JobManager.java (rescueStranded: neues Kriterium route, jetzt package-private), job/StrandRescueChecks.java (neue Fixture),
dev/Checks090.java (Gruppe `strandrescue`).
Regel: >= 3 Fehlschlaege (unreachable, Route <= 4 Knoten) in >= 600 Ticks, Luecke <= 400 Ticks, Position +-2 unveraendert; dann
einmal Sites.walksBackTo(Position, villageReturn): nur wenn KEIN Rueckweg -> Teleport zur Dorfstrasse, Cooldown 1200 Ticks.
Ausnahmen: Expedition (Holz), Bergleute (miningUntil+400, insideMine), Karawane (carryingFor). villageReturn wird nur im Verdachtsfall berechnet.
Geprueft: Scratch (reine Zustandslogik) 10 Pruefungen PASS; kompiliert. NICHT in-world geprueft (EULA), Fixture `strandrescue` nicht ausgefuehrt,
Bundles housesearch/roles/mining/miningcycle/woodcuttinggroup nicht gelaufen, kein Naturlauf.
Naechster Schritt: nach EULA `bash tools/cloud/autotest.sh strandrescue` und housesearch.

## P7 - Audit (strenger Maßstab: Code + registrierter In-world-Check; Lese-Agent, NICHT von mir nachgeprüft, Hinweis statt Beweis)
### G (Agent-Bericht, Stichproben offen)
| ID | Status | Kernbeleg | Lücke |
|---|---|---|---|
| G01 | TEIL | HOUSE_MARGIN=12 (houseplot), JigsawHouseSpacingMixin/g01spacing, HouseYardChecks | kein Check einer generierten Siedlung; Bäume nur Setzling im Vorgarten |
| G02 | OK | Builder.nextSpecial, milestones | DevelopmentChecks nur im Monolith-Pfad |
| G03 | OK | StockPolicyChecks (stockpolicy) | - |
| G04 | OK | StockPolicyChecks/FoodChecks | rohes Fleisch nicht explizit geprüft |
| G05 | OK | StockPolicyChecks, EquipmentMaintenanceChecks | Reparaturmaterial im Zentraldepot, nicht in der Kammer |
| G06 | OK | SchoolChecks (school) | - |
| G07 | OK | KitchenFurnisherChecks | Topf/Brett optional (Fallback Barrel) |
| G08 | OK | StationShedChecks | positiver Auslöser nicht geprüft |
| G09 | TEIL | HouseDesignCatalog, TemplateLibraryChecks | keine CTOV-Asset-Palette; 3 Template-Familien |
| G10 | OK | CivicCenter/Plaza/MarketPlanningChecks | - |
| G11 | TEIL | Demolition/BuildingRelocation + Checks | nur spielerausgelöst, keine autonome Erkennung |
| G12 | TEIL | MiningSafetyChecks | Teleport-Fallback in Mining.approachMineStep |
| G13 | OK | MineLifecycleChecks | - |
| G14 | TEIL | NetworkDiscoveryChecks | geladene Nachbarn ohne Handelskontakt verlinkt; Eigenname ohne Check |
| G15 | OK | WallConstructionChecks, developmentwall | keine Wand ab 192 Blöcken; kein Langzeit-Check |
Hinweis: RailMetropolisChecks ohne Aufrufer; Gruppen ctov*, tafelgross, templatebuild u. a. nicht in AutoTest-Sammelarrays.

## P6 - Modpack-Vertraege (Analyse-Agent, Ids teils aus den Jars verifiziert; nichts davon in-world geprueft)
Geaendert: fabric.mod.json `suggests` um farmersdelight, chefsdelight, morevillagers, sereneseasons, t_and_t, village_taverns erweitert (nur Metadaten).
Ist-Stand: CompatChecks prueft nur "Namespace vorhanden". Tiefste Integration: Farmer's Delight (isModLoaded-Guards, aber direkte
vectorwing-Imports in DelightKitchenCrafting -> Klassenladerisiko ohne Mod ungeprueft), Village Taverns (Soft-Dep via Registry, TavernChecks),
CTOV (CtovHeadlessChecks, Lithostitched-Modifier).
Luecken (nach Wirkung): (1) FD-Typen hinter Adapter-Klasse ziehen und Lauf ohne FD pruefen; (2) Serene Seasons: kein Saisoncode, Tags
sereneseasons:*_crops und API SeasonHelper.getSeasonState(Level) verifiziert -> Farmer-Saisonplanung moeglich; (3) More Villagers: Jobs
florist/oceanographer/engineer/iceman/enderian/netherian laufen als Traeger; ACHTUNG Compat.counterpart ersetzt Vanilla-Berufe durch Mod-Berufe
gleicher Taetigkeit (z. B. hunter->butcher, miner->mason): Designentscheidung des Nutzers, bewusst NICHT geaendert; (4) Towns and Towers:
Dorf-Tag village enthaelt towns_and_towers:village_*, Ozeandorf (Schiffe) ungetestet, kein Headless-Modus; (5) bartender (Village Taverns)
ungemappt; (6) EMF/ETF/Fresh Animations: nur Client pruefbar (Overlay-Renderer E04RoleRenderer), Actually 3D Stuff: kein 26.2-Release.
### J/U/A/R/E (Agent-Bericht, unverifiziert)
| ID | Status | Luecke |
|---|---|---|
| J01 | OK | - |
| J02 | OK | FD/Chef's-Pfade nur im Dev-Pack |
| J03 | TEIL | Schlachtung (hurtServer/Drops) ohne Check |
| J04 | OK | - |
| J05 | OK | - |
| J06 | OK | Kiesverbrauch durch Strasse/Schiene ungeprueft |
| J07 | TEIL | keine Depot-Sortierung im Code; FreightLoading.work ohne Check |
| J08 | OK | - |
| J09 | OK | - |
| J10 | OK | LamplighterJob selbst ungeprueft (nur Lighting.*) |
| J11 | OK | Anwendung auf Patienten nicht bestaetigt |
| J12 | TEIL | Lookout und Expedition.tick ohne Check |
| U01 | TEIL | dorftafel_gross ohne 3D-Laternen/Pinnwand; Rendering ungeprueft |
| U02 | OK | Kopf/Button nur manuell (ClientChecks) |
| U03 | TEIL | nur ClientChecks (manuell, nicht registriert) |
| U04 | TEIL | Zuweisen (AssignJob) ohne Server-Check; Klick manuell |
| U05 | TEIL | Drag-and-drop nur ClientChecks |
| U06 | OK | Reiter-UI nur manuell |
| U07 | OK | Slider-Widgets ungeprueft |
| U08 | OK | UI-Darstellung ungeprueft |
| A01 | OK | - |
| A02 | OK | nur im Sammellauf |
| A03 | OK | - |
| A04 | TEIL | kein Survival-/Leerlauf-Nachweis |
| R01 | TEIL | Hebel fehlen im Code; nur Stichproben |
| R02 | OK | - |
| E01 | OK | - |
| E02 | OK | - |
| E03 | OK | Reiter-Darstellung nur manuell |
| E04 | TEIL | Skins nur 4 Rollen; EMF/ETF/FA ungeprueft |
Hinweis: UI-Schicht haengt an ClientChecks (nicht in Checks090/AutoTest registriert, braucht Client).
