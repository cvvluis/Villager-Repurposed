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
### D/K/B (Agent-Bericht, unverifiziert)
| ID | Status | Luecke |
|---|---|---|
| D01 | OK | - |
| D02 | OK | keine eigene Stufe "Unbekannt" in stage(), nur Namensverschleierung |
| D03 | TEIL | Schwelle >=30 nie als Grenzfall geprueft (route=true umgeht sie); Partner-Bauer nicht bewiesen |
| D04 | OK | nur 2 Paletten (Sand / Kies-Cobble) |
| D05 | OK | - |
| D06 | OK | RailMetropolisChecks veralteter Wrapper |
| D07 | TEIL | Roads.advanceRail laeuft im Dorf-Tick ohne Arbeiter/Swing/Sound/Partikel |
| D08 | TEIL | Personenzug (passengerDock) ohne In-world-Check; Fracht nur Eisen belegt |
| B01 | TEIL | Workstation nur Smith/Cook/Healer/Fletcher; Selbstbeschaffung ungeprueft |
| B02 | TEIL | keine Aushub-Phase; kein End-to-End-Lauf aller Phasen |
| B03 | TEIL | kein Check fuer Platzier-Sound/-Partikel/Swing |
| B04 | TEIL | Schild-Platzierung ungeprueft; Name/Status erst per Rechtsklick |
| B05 | OK | - |
| K01 | TEIL | CaravanTraderChecks nur ueber ClientChecks (Client), nicht in Checks090 |
| K02 | OK | - |
| K03 | OK | - |
| K04 | OK | Ueberschuss-Verkauf nur im Hauptlauf, nicht als Einzelgruppe |

### Zaehlung (Widerspruch 18/62 vs. 30/62)
Agenten-Urteil gesamt: 39 OK / 23 TEIL / 0 FEHLT (G 10 OK, D/K/B 9 OK, J/U/A/R/E 20 OK). Diese Zahl ist zu locker: sie zaehlt u. a. UI-Punkte
(U02/U06/U07/U08/E03), die nur manuell/Client geprueft sind, sowie OK mit genannter Luecke. Streng = OK ohne genannte Luecke: **19/62**
(G03, G06, G10, G13, D01, D05, D06, K02, K03, B05, J01, J04, J05, J08, J09, A01, A03, R02, E01), nahe der alten strengen Zahl 18/62.
Die 30/62-Zaehlung ist damit nicht gedeckt. Einzelbelege wurden von mir nicht stichprobenartig nachgeprueft; die Zahl bleibt ein Hinweis.
Echte Code-Luecken laut Audit: Hebel (R01), Depot-Sortierung (J07), 3D-Laternen/Pinnwand am dorftafel_gross (U01), Survival-/Leerlauf-Nachweis (A04),
Personenzug-Check (D08), autonome Stoerbau-Erkennung (G11), Teleport-Fallback im Bergbau (G12).

## Stand / naechste Schritte
Blocker 1: kein Push (403, Claude-GitHub-App ohne Schreibzugriff auf cvvluis/Villager-Repurposed; auch MCP create_branch 403). Commits liegen nur lokal im Branch claude/village-growth-1111.
Blocker 2: run/eula.txt fehlt -> keine Serverlaeufe. P0-Referenzwerte, strandrescue-Gruppe, P2-P5 nur mit Server sinnvoll (P2/P3-Ablehnungsstatistik wird
bereits geloggt: Mining.create-Log "Ablehnungen [ungeladen,Hoehe,Boden,Altmine,Bauplan,Vorschau,Eingangsplatz]", Bauplatz-Diagnose-Logs).
Naechster Schritt nach Freigabe: `bash tools/cloud/autotest.sh housesearch`, dann `strandrescue`, dann Naturlauf `natural-run.sh vp-natural111-x 10 2400`.

## Update nach EULA (11.11. abends)
- run/eula.txt wurde von mir auf ausdrueckliche Anweisung des Nutzers ("Beschreibt die Dateien selbst") geschrieben; Zustimmung zur Minecraft-EULA liegt damit beim Nutzer.
- P0 (Dev-Server, Stand inkl. P1-Code, Minecraft 26.2, Java 25.0.5): housesearch PASS: builderpaths 66, houseplot 140, builderchecks 318, supply 30, planstonesupply 22 = Referenzwerte, keine Abweichung.
- P1 in-world: `strandrescue` PASS 15 (Log: 2 Rueckholungen mit route=true aus der Grube, keine bei Bergbau-Fenster/Rampe/zu wenigen Fehlschlaegen).
  Regression (je einzeln, je 1 Lauf): roles 112, mining 68, miningcycle 199, woodcuttinggroup 48 - alle = Referenz.
  Noch offen fuer P1: Naturlauf (Zahl unreachable route<=4 / gestrandet), Fixture ist synthetisch.

## Natuerlicher Lauf vp-natural111-a (Seed 4242, ohne Fixtures, forceload als Spielerersatz; Stand inkl. P1-Code)
Lauf 1: Tick 18-24236; Lauf 2 (cont1): 24243-48481. Keine Spielerpruefung.
| Zaehler | Lauf 1 | Lauf 2 |
|---|---|---|
| Bauplan gestartet / fertiggestellt | 1 (house @166,66,-310, Tick 24018) / 0 | 1 (house @48,70,-425, Tick 48254) / 0 |
| Hausplan-Abbruch | 0 | 1 ("kein Weg zum Bauplatz", Schritt 0, Tick 33260) |
| Langweg unreachable gesamt / route<=4 | 5 / 0 | 8 / 2 |
| gestrandet/zurueckgeholt | 3 (alle deep=true, route=false) | 3 (kumulativ im Zaehler; route=true nie ausgeloest) |
| Steinbruch "kein Rueckweg" verworfen | 0 | 4 |
| "keine Mine anlegbar" | 0 | 0 (Mine 103,67,-365 wurde angelegt, Abbau "von Hand") |
Befunde: (1) P1-Route-Rueckholung wurde natuerlich nicht ausgeloest (route<=4 nur 2x, ueber Schwelle nie) -> Wirkung nur ueber Fixture belegt.
(2) P3: Granary-Suche bleibt in beiden Laeufen ohne Treffer (nur "fortgesetzt"-Zeilen, keine Ablehnungsstatistik) - Agent claude/granary-site.
(3) P4: Haus bekam nie sein Material (21 Bruchstein fehlten; 3000 Ticks Stillstand, Geruest-Fallback); Elsa verwirft Schluchten-Steinbrueche (ca. 50 Bloecke noerdlich)
reihenweise, baut dann "von Hand" in der Mine -> Steinkette ist der Engpass - Agent claude/stone-supply. Bauplaetze liegen 68-81 Bloecke von der Glocke.
(4) Dschungel: viele Bergleute "keine Spitzhacke" (Bedarf METAL), sonst "Bedarf verneint".
