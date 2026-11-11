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
