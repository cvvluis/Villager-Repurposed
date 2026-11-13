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

## P4/P2 Steinkette (Agent claude/stone-supply, gemergt; Diff von mir gelesen)
Geaendert: job/SurfaceStone.java (4 naechste Kandidaten ab Arbeiterposition, Region-Ablehnung Radius 10 fuer 24000 Ticks bei "kein Rueckweg" / 3000 bei "Weg nicht begehbar",
3 Pruefungen je Tick, Retry 40 statt 200 Ticks, Handabbau 160->100 Ticks, Log "Stein gebrochen"), job/Mining.java (METAL ohne Spitzhacke: Werkzeugkammer/PlanStoneSupply.supplyMiningPickaxe),
job/SurfaceStoneChecks.java (Gruppe surfacestone, neu), dev/Checks090.java.
Messung (Agent): im Radius 64 nur 3 erreichbare freiliegende Steinzellen + 10-13 in der Schlucht (alle "kein Rueckweg"); erster Stein 650-1640 Ticks nach Planstart; Mine grub im Naturlauf nie
(Tiefe 0); Bewohner bleiben auf dem Weg zum Mineneingang 700-1750 Ticks stehen (Ursache ungeklaert, vermutlich JobUtil.approach/Laufziel). Holz (27 Bretter, Geruest) fehlt zusaetzlich.
Nach Merge von mir erneut gelaufen (je 1 Lauf): surfacestone 13, planstonesupply 22, strandrescue 15, mining 68, miningcycle 199. Agent-Zahlen: minelifecycle 35, woodcuttinggroup 48, roles 112.
Naturlauf (Agent, 30x2400 Ticks): Planstart 24006, erster Stein 24716, dritter 25336, Schluchtziele verworfen 2 statt 10-13, KEIN Haus fertig -> P4 NICHT geloest. Plan-Starts schwanken stark (24k bis >48k).

## P3 Kornspeicher (Agent claude/granary-site, gemergt)
Ursache (Agent, Zaehler "Sites.SCAN_REJECT"): im Taiga-Dorf scheitern ALLE Kandidaten schon in der Spaltenpruefung (Plan-Pruefung "geprueft"=0); der Entwurf braucht 13x13 mit 9 Hoehe.
Ablehnungen im ebenen Lauf: ~52% echte Spreizung >5, ~12% nur wegen Baumstaemmen; Hanglage ist Hauptgrund. Dschungel gleich.
Regel (nicht Fixture) geaendert: letzter-Ausweg-Pass fuer zivile Sonderbauten: nach flach/tolerant ein terrassierter Pass (3 Bloecke Relief, Anker an hoechster Spalte, tiefere Spalten per fillSupport,
nichts abgegraben), wurzelnde Naturbaeume raeumbar (NaturalSitePreparation.inspect READY/CLEARABLE), Planbesitzer raeumt vor dem Bau, Helfer warten. Hausplatz-/12-Block-/Grubenregeln unveraendert.
Dateien: build/Builder.java, village/Sites.java, village/VillageData.java (optionales Codec-Feld `terraced`, alte Staende laufen), build/BuilderPathChecks.java (+13 Checks, ein Zyklus-Check an die neue Laenge angepasst),
tools/cloud/natural-run.sh (Server-Guard nur noch pro Worktree).
Agent-Naturlaeufe: Granary-Plan startete in 5/5 Laeufen (~Tick 2400, z. B. 123,67,-349), nie fertiggestellt; 2 Abbrueche "kein Weg zum Bauplatz" (Wald). Dschungel: kein Plan.
Nach Merge von mir (je 1 Lauf): builderpaths 79, houseplot 140, builderchecks 318, supply 30, planstonesupply 22, milestones 16, constructionsite 21, surfacestone 13, strandrescue 15.
Offen: Fertigstellung des Kornspeichers, Dschungel, Wirkung auf Schule/Kueche/Station.

## Naturlauf vp-natural111-m1 (frisch, Seed 4242, 10x2400 = ~24k Ticks, gemergter Stand P1+P3+P4)
Bauplan gestartet 2 (granary 122,67,-348 @Tick 2326; storage 84,68,-336 @2900), fertiggestellt 0, Hausplan-Abbruch 0, Langweg unreachable 12 (davon route<=4: 8), gestrandet 4 (1x deep, 3x route=true), Steinbruch-Rueckweg-verworfen 0, Mine-Fehler 0.
P1 natuerlich ausgeloest: 3 Rueckholungen route=true (Ludwig 43, Clara 30, Luise 29 Bloecke von der Dorfkiste; Taetigkeiten Holzsuche fuer den Plan / Farmer-Saatsuche), keine bei Expedition/Bergbau.
ABER: Ludwig (Planbesitzer des Granary) stand kurz danach wieder an derselben Stelle 165,70,-394 (route=1 unreachable zur Dorfkiste und zum Holzziel 186,69,-402) -> Granary "steht seit 3000 Ticks still".
Die Rueckholung heilt das Symptom, nicht die Falle (Holzziel liegt ausserhalb der Rueckweg-Insel); Ziel-/Baumwahl sollte Rueckwege pruefen (wie SurfaceStone.walksBackTo) - offen.

## P4b Laufstillstand / Mine (Agent claude/walk-stall, gemergt) und P4c Baumwahl (Agent claude/tree-walkback, gemergt)
P4b Ursachen (Agent, Fixture + Messung): (1) MineMovementMixin/Mining.allowMineMovement verwarf die ganze Bewegung eines Laeufers mit Minen-Tag auf Eingangshoehe (Gravitationsanteil -0,078 -> "unter dem Eingang"),
Natur-Beleg: 700 Ticks mv=0,00 mit gueltigem Pfad; jetzt Sperre nur noch im Stollenbereich (inMineArea); Terrassen- statt Rampenmodell (routeDeviation, Math.round).
(2) Zaunpfosten-Falle (Kollisionshoehe 1,5, Teich-/Fischer-Pavillons, z. B. 135,68,-343): Vanilla-Pfad 1 Knoten; neu JobUtil.perchStepOff/stepOffPerch (max 2 seitlich, 3 runter).
Gruppen (je einzeln, nach Merge von mir): minewalk 27 (neu), mining 68, miningcycle 199, minelifecycle 35, strandrescue 15, surfacestone 13, roles 112. Agent: a01behavior 20.
Natur (Agent, Einzelketten): in 1 von 2 Ketten Mine Tiefe 7 (Erstgrabung ~20800 Ticks nach Laufstart), mit finalem Code kein Spatenstich: Bergleute pendeln, "holt Stollenmaterial" (ensureMineSupplies, Wartezeit bis 12000 Ticks) ist naechster Blocker.
P4c: Woodcutting.findTree/Builder.selectPlanWoodTree verwerfen Baeume ohne Rueckweg (24000 Ticks Gedaechtnis, in-memory), TreeWalkbackChecks. Gruppen nach Merge (Konflikt in Checks090 von Hand geloest):
treewalkback 32, woodcuttinggroup 48, buildhaul 20, planstonesupply 22, supply 30, strandrescue 15, minewalk 27, a01behavior 20, builderchecks 318, mining 68.
Beide Agents belegen unabhaengig: der Granary-Besitzer sass auf einem Zaunpfosten eines Teich-Pavillons (165,70,-394), Sites.walksBackTo meldet dort true (Modell laeuft ueber das Fass),
Vanilla-Pfad 1 Knoten -> rescueStranded greift nicht; Baumregel griff im Naturlauf nie. Offen: Rettung/Modell fuer Pavillon-Fallen (Sites/JobManager), JobUtil.approachLong WALK_TARGET erst nach Langweg-Urteil,
Farmer-Samensuche (gleicher Typ, FarmerJob), Mine "holt Stollenmaterial".

## Weitere Aenderungen von mir (nach den Agent-Merges)
- JobManager.rescueStranded: echte Navigation (Builder.workPath zur Strasse: <=4 Knoten, kein kompletter Pfad) schlaegt das Block-Modell Sites.walksBackTo. strandrescue-F2: Pfosten mit komplettem Vanilla-Pfad
  wird in Ruhe gelassen (Messung: route true/10 Knoten; meine erste Erwartung "Rettung" war falsch, Produktivcode richtig), eingemauerter Pfosten wird gerettet. Echte Pavillon-Falle (Fass-Oberseite, Modell true / Navigation 1 Knoten) NICHT als Fixture nachgebaut.
- FarmerJob.findSeedGrass: die 3 naechsten Kandidaten brauchen einen Rueckweg (walksBackTo), Mulden-Regionen (Radius 6, +-2 Hoehe) werden 24000 Ticks gemerkt. Anlass: Theo/Martha wurden im Naturlauf m2 je 2x aus derselben Mulde (135,65,-352) geholt.
  Fixture-Fund: erste Fassung (+-6 Hoehe) verwarf auch erreichbares Randgras -> auf +-2 verkleinert. Zahlen: strandrescue 19 (15 + F2 x2 + Saatgras x2), farmexpand 172.
## Naturlaeufe auf dem Endstand (je frisch, Seed 4242, 2 Etappen = ~48k Ticks; Einzelwerte, kein Beleg fuer Verbesserung)
| Lauf | Plan gestartet (Etappe 1 / 2) | fertig | gestrandet (route=true / deep) | unreachable route<=4 |
|---|---|---|---|---|
| m2 (vor Saatgras-Fix) | granary+storage / (Etappe 2: 0 Plaene, 12 Rueckholungen, 5x route=true) | 0 | 6 / 12 | 2 / 8 |
| m3 (Endstand) | granary, storage, granary / storage, farm | 0 | 3 (0x route) / 7 (1x route=true) | 1 / 4 |
Wiederholte Rueckholung derselben Personen: m2 Theo 2x, Martha 2x, Heinz 4x (Dschungel); m3 Hanna 3x (deep=true, Taiga).
Ergebnis: P4 (fertiges Haus ohne Fixture) NICHT erreicht; in 48k Ticks nur Planstarts (granary 2349, storage 3007, farm 32418), 1 "Stein gebrochen" in m3 Etappe 2.
Verbleibende Engpaesse (Zahlen oben und in den Agent-Abschnitten): Steinmenge/-wege, Holz/Gerüst, "holt Stollenmaterial" (Mine grub nur in 1 von 2 Agent-Ketten), wiederholte deep=true-Rueckholungen (Hanna), Dschungel ohne Granary-Plan.

## P8 / D03 (12.11., ich)
Roads.roadLinkActive(Link) ersetzt 3 inline-Bedingungen (route || relation >= 30); Verhalten unveraendert. 2 neue Checks in NetworkDiscoveryChecks (29/30-Grenze, Routen-Override, null).
Gruppen: networkdiscovery 42 (inkl. 2 neue), roadbuild 53 (je 1 Lauf). D03 bleibt TEIL: kein Check, dass der Strassenbauer des Partnerdorfs seine Haelfte tatsaechlich baut.

## P5 Schrittweise Haussuche (Agent claude/p5-housesearch, gemergt; Diff-Umfang von mir geprueft)
Befund (Agent, Naturlauf 38 400 Ticks Dschungel-Mason-Planpruefung): Einwohner 10, Betten 17, Hausbedarf false im ganzen Lauf; nur granary (2614) und storage (7130) geplant. Pfad braucht needHouse (beds < pop oder beds==pop mit Obdachlosen)
UND Scheitern aller Zufallspfade (ZoneAwareSites.find/findTolerant/findHouseLot, je bis 4 Entwuerfe). Auf Seed 4242 entsteht Hausbedarf erst bei Bevoelkerungswachstum (>=17 in frueheren Laeufen, Tick ~24000+).
Aenderung: Builder.stepHouseSiteSearch/ongoingHouseSiteSearch/dropHouseSiteSearch (testbar), Cursor (Variante, Mindestradius, Phasenseed, naechster Kandidat, retryAt, lastUsedAt) in VillageData.Extra `house_site_search` (optional, Defaults; alte Saves laden),
Neustart setzt aus dem Cursor fort (Verwurf bei geaendertem Radius/Legacy-Plan/24000 Ticks Leerlauf), Logzeilen begonnen/fortgesetzt(gedrosselt 1200)/gefunden/wiederaufgenommen; Suche selbst unveraendert (Budget 96, HOUSE_MARGIN 12).
Dev-Hooks (nur per Umgebungsvariable, im Normalbetrieb aus): VP_FORCE_HOUSE_STEP_SEARCH=1, VP_HOUSE_SEARCH_BUDGET=n. Neue Gruppe housesitesearch (36).
Natur (erzwungen per Hook): Taiga: begonnen/fortgesetzt/gefunden, Standort 97/66/-315 spaeter von prepareHouseLot verworfen (Luecke zwischen Standortpruefung und Lotvorbereitung); Dschungel: kein Standort bei Mindestradius 68; echter Neustart: "aus gespeichertem Cursor wiederaufgenommen (Kandidat 456)".
Ohne Hook natuerlich NICHT ausgeloest (kein Hausbedarf) - P5-Abnahme "Check + Log im Naturlauf" nur mit Hook erfuellt.
Nach Merge von mir (je 1 Lauf): housesitesearch 36, houseplot 140, builderchecks 318, builderpaths 79, supply 30, planstonesupply 22, milestones 16, constructionsite 21, strandrescue 19. Agent: houses 12748.

## P6 Laufzeit-Checks (CompatChecks.runStrict, Gruppe compat = 41 Checks, 1 Lauf, Dev-Pack mit allen Mods ausser Actually 3D Stuff)
Neu: professionMapping (alle Mod-Berufe mit Compat.jobKey; erwartete Berufe von morevillagers/village_taverns muessen registriert sein) und seasonTags (Serene-Seasons-Block-Tags muessen aufloesbar sein).
Gemessenes Mapping Compat.jobKey: morevillagers:explorer->cartographer, hunter->butcher, miner->mason, woodworker->fletcher; porter (ohne Tag/Schluesselwort): enderian, engineer, florist, iceman, netherian, oceanographer,
village_taverns:bartender, chefsdelight:chef/cook (Koch wird in JobManager.jobFor separat zu COOK, nicht ueber jobKey). Serene Seasons Tags (Members): spring 19, summer 28, autumn 20, winter 11, year_round 8.
Nicht geaendert: Mapping (Designentscheidung des Nutzers), kein Saison-Verhalten im Farmer. Weiter offen: FD-Adapter (Klassenladerisiko ohne Mod), T&T-Headless, EMF/ETF nur Client.

## P2/P4d Mine-Kit (Agent claude/mine-supplies, gemergt)
Ursache (Agent, Naturlauf base2, Taiga): das Mine-Kit verlangte pro Abschnitt 1 Eiche-Brett als Gehweg, auch fuer Abschnitte ohne Stuetzen/Fackel; Fichtendorf kann es nicht herstellen -> Rudolf WAITING ab Tick 33138 (req f/p/w/t=0/0/1/0).
Nach dem 12000-Tick-Fallback lief ensureMineSupplies weiter (Seiteneffekt: Stollen verlassen, Depotweg) gegen den Arbeitsbefehl -> Jakob pendelte ab Tick 51677 am Eingang. Zeitlinie base2: erster Handbruch 27347, erster gegrabener Block 49827, Tiefe 3 bei 51637, dann Stillstand bis >=72600.
Aenderung (Mining.java, MineKitChecks.java, Checks090): Gehweg blockiert nie (nur Stuetzen/Balken/Fackeln), ein Depotbesuch deckt bis 6 Abschnitte voraus ab (nur was vollstaendig im Depot liegt), Fallback pro Abschnitt ohne Nachschubweg. Neue Gruppe minekit (9).
Nach Merge von mir (je 1 Lauf): minekit 9, mining 68, miningcycle 199, minelifecycle 35, minewalk 27, surfacestone 13, planstonesupply 22, strandrescue 19, roles 112. (Agent: mining einmal fehlgeschlagen, danach 3/3 gruen - bekannter Flake.)
Natur: kein belastbarer Vorher/Nachher-Beleg (ungeforcet: kein Steinbedarf/keine Mine in 30 Sprints; erzwungen: Handgrabung dominiert). Offen: Fackelabschnitte ohne Kohle warten bis 12000 Ticks.
## Vorbestehender Fehler: Gruppe gravelcycle
FAIL "the next lit section crafts and carries a torch from real public charcoal and stick stock without advancing e..." - verifiziert von mir: identische Meldung auf dem urspruenglichen Stand 01db13e (eigener Worktree, 1 Lauf) und nach allen Merges. NICHT durch diese Nacht verursacht, nicht behoben, Ursache nicht untersucht.
## gravelcycle repariert (Agent, von mir nachgelaufen)
Ursache (Agent, belegt): Mining.lightSection(i) = i>0 && i%6==3 (seit faca3ca); die Fixture in GravelQuarryChecks nutzte noch Abschnitt 12 (und 4/12/20 beim "alternating"-Check). Produktivcode richtig, nur Fixture-Indizes auf 9 bzw. 3/9/15 angepasst.
Von mir nach dem Merge: gravelcycle 20 (vorher FAIL, auch auf 01db13e), mining 3 von 3 Laeufen 68 (Agent sah im eigenen Worktree 2 von 5 Fehlschlaege "three live work-lane leases ... visible=0"; bei mir nicht reproduziert, Flake-Rate unklar).

## Naturlauf vp-natural111-m4 (frisch, Seed 4242, 2 Etappen ~48k Ticks, voller Merge-Stand inkl. P5, Mine-Kit, Seed-Rueckweg; Einzellauf)
Etappe 1: Plan gestartet 2 (granary 122,67,-342 @2335, storage 187,70,-431 @3011), fertig 0, gestrandet 7 (route=true 3, deep 4), unreachable route<=4: 7. Etappe 2: Plan 1 (granary @24986), fertig 0, gestrandet 0, unreachable<=4: 8, Steinbruch-Rueckweg verworfen 1, "keine Mine anlegbar" 1, "Stein gebrochen" 3, Tiefe>0: 0.
Granary 1 stand 3183 Ticks still (Besitzerin Hilde @110,65,-394 'faellt einen Baum'; fehlt: crafting_table, deepslate_bricks x18, scaffolding x7, Kiste Bruchstein/Stein/Ziegel/Kohle 0), wechselte auf Ersatzpalette, Schritt 0 abgebrochen
("kein Weg zum Bauplatz ... no free reachable stand after 3 probes", worker 110,65,-391) -> naechster granary-Plan @24986. Hilde steht im Teich-/Pavillonbereich (vgl. P4c-Fallen bei 105,65,-393).
Baumwahl "streng": 154 von 154 Staemmen "im Dorf/Gebaeude" (Dorfgrenzen umfassen den ganzen Suchbereich) - die Fallback-Kette locker/erweitert greift, Hilde faellt Baeume: kein Holzdefekt, aber die Palette (deepslate_bricks x18) ist im Taiga-Dorf kaum beschaffbar.
Weiterhin: kein Haus/Bau fertig in 48k Ticks.

## P4e Palette beim Planstart (Agent claude/plan-palette, gemergt)
Ursache (Agent, Naturlauf palbase): Palette.detect liest die CTOV-Haeuser (Waende light_blue/lime_terracotta, Sockel deepslate_bricks, Fenster white_stained_glass_pane) - alle vier Rollen laut Supply.obtainable nicht beschaffbar; Plan startet mit der rohen Palette,
Beschaffbarkeitspruefung (Supply.obtainablePalette) nur nach Karawanenbesuch oder nach 3000 Ticks Stillstand. Erste 3148 Ticks (Start 2385, Fallback ~5533): Wunschliste unveraendert (crafting_table 1, deepslate_bricks 18, scaffolding 4, spruce_planks 30), Geruest (Bambus/Schnur) nicht herstellbar,
Holz: Baum 85 Bloecke entfernt unerreichbar. Zweitplan storage stand 14614 Ticks still.
Aenderung: Builder.detectForPlan ersetzt unbeschaffbare Rollen beim Planstart (Supply.lookAlike: deepslate_bricks->stone_bricks, cobbled_deepslate->cobblestone, bricks->stone_bricks ...; Terrakotta -> Biompalette), ohne Schmelzmoeglichkeit Steinziegel->Bruchstein,
Variantenbit 2 (Ersatzpalette), Geruest sofort verworfen wenn weder Vorrat noch Rezept (waiveUncraftableScaffolding). Dateien: build/Supply.java, Palette.java, Builder.java, PaletteFallbackChecks.java (Gruppe palettefallback 21), Checks090.
Nach Merge von mir (je 1 Lauf): palettefallback 21, builderchecks 318, supply 30, planstonesupply 22, builderpaths 79, houseplot 140, milestones 16, constructionsite 21, housesitesearch 36, strandrescue 19. Agent: houses 12748.
Natur (Agent, Einzellaeufe): palfix2 Wunschliste sofort cobblestone 18 + spruce_planks 30; erster Stein 2097 Ticks nach Planstart (Zweitplan); KEIN Block gesetzt in allen Laeufen; spruce_planks 30 blieb ~17000 Ticks offen (Baeume unerreichbar). Offen: Holzbeschaffung, Terrakotta-Optik -> Bruchstein.

## Rueckhol-Schleifen (Agent claude/rescue-loops, gemergt; von mir nach Merge nachgelaufen)
Ursache (Agent, Naturlaeufe loop1-4, Diagnose-Logs entfernt): nicht die Aufgabenziele, sondern die Regel `deep` (Y < tiefster Anker - 8) in JobManager.rescueStranded: Rueckholung beim ersten Blick auf Bewohner, die nur auf einer legitimen Geländestufe stehen
(Dschungel: Terrassen y 85-89, Bett y 94; 7 von 8 Dschungel-Rueckholungen mit Ziel=-: Idle-Stroll/Pause/Gather). Danach fuehrt dieselbe Aktivitaet wieder hinunter. Echte Falle: Taiga-Schlucht 151,56,-365 (Faelle 5-8 Bloecke).
Beobachtungslauf ohne Rueckholung: Tief-Episoden von 9 bis 2350 Ticks (Ausreisser 4399 Viktor), Bewohner kamen selbst hoch; walksBackTo allein zu streng (Martha/Heinz model=false, kamen trotzdem hoch).
Fix (JobManager, nur Regel): Karenzzeit 3600 Ticks ununterbrochene Tiefe (Luecke <600), Aufgabenziel-Ausnahme mit gecachtem Rueckweg, Sturz >3,5 Bloecke in 600 Ticks ueberspringt die Karenz; Log nennt jetzt Position VOR dem Teleport, Tiefendauer, Aktivitaet. Route-Rescue/far/Expedition/Fischer unveraendert.
Gruppen (von mir, je 1 Lauf nach Merge): deepstrand 17 (neu), strandrescue 19, roles 112, mining 68, a01behavior 20, farmexpand 172, woodcuttinggroup 48, buildhaul 20, miningcycle 199.
Natur (Agent, je 1 Lauf/Etappe): vorher Etappe1 3 deep, Etappe2 3 deep (Jakob 3x ueber 3 Etappen); nachher Etappe1 1 (route), Etappe2 4 (1 deep, 3 route); kein Bewohner 2x gerettet. Kosten: echter Grubenfall ohne erkannten Sturz wartet jetzt bis 3600 Ticks (Rosa/Lotte).
Offen: Sturz-Abkuerzung natuerlich nie ausgeloest (nur Fixture); mehr route=true-Faelle im Endlauf ungeklaert; echte Falle Dschungel-Terrasse 1505-1509,87,-361 (Builder "unreachable route=52").

## Naturlauf vp-natural111-m5 (voller Merge-Stand inkl. plan-palette + rescue-loops; Etappe 1 durch fremden Server-Kill ausgefallen, Etappe "cont1" lief ab Tick 16 bis 24324 = ein 24k-Lauf)
Plan gestartet 3 (granary @2316 bei 118,67,-349, storage @2799, granary @8886 bei 115,67,-341; alle "startet mit beschaffbarer Palette", Wunschliste sofort cobblestone#18 + spruce_planks#30, Geruest sofort verworfen), fertig 0.
granary 1: "Schritt 0 (baut) - Ergebnis false" -> "Schritt 0 wird abgebrochen (Bauplatz nicht erreicht) Stand 118,67,-358" binnen ~400 Ticks; granary 2 aehnlich -> Agent claude/granary-site (Aufgabe: Erreichbarkeit des terrassierten Bauplatzes).
gestrandet 2 (beide Matthias, route=true, 136,68,-343 bzw. 159,67,-357, Aktivitaet 'wartet auf einen freien Pausenplatz'): Pavillon-Fleck (135,68,-343) bleibt; unreachable gesamt 3 (<=4: 2) - niedrigster Wert aller Laeufe (Einzellauf, kein Beleg). Steinbruch 'Stein gebrochen' 3, keine Mine/Tiefe>0.

## P8 / B04 Hausschild (ich)
Neu: village/HousingSignChecks.java (Gruppe housesign, 7 Checks: Bett->Zuhause/Tuer, Schild in Tuerwandebene neben der oberen Tuerhaelfte, Farmer-Symbol 1 + 3 Sterne, signText "1:1:3", gemeinsames Haus-Symbol 12 bei zwei Bewohnern, genau 1 Schild).
Produktivfehler gefunden (nicht nur Check): hatte ein neu einziehender Mitbewohner die kleinere UUID, wurde er Pfleger des gemeinsamen Schilds, kannte es aber nicht (signPos leer) und setzte ein zweites Schild; das alte blieb mit falschem Symbol. 2 von 5 Fixture-Laeufen fielen durch.
Fix: Housing.updateSign uebernimmt vor placeSign das Schild eines Mitbewohners (signPos + Block ist Dorfschild). updateSign dafuer paketsichtbar. Danach housesign 8 von 8 Laeufen PASS 7; roles 112.
Lueckenhinweis: im Fixture meldet level.canSeeSky auf BEIDEN Seiten der Wand true; "Schild aussen" ist deshalb NICHT bewiesen (Produktivlogik nimmt bei zwei freien Seiten die zuletzt gepruefte). B04 bleibt TEIL (Name/Status stehen erst per Rechtsklick auf dem Schild).

## P3 Folge: Granary-Abbruch bei Schritt 0 (Agent claude/granary-site, gemergt)
Ursache (Agent, Abbruchzeilen im Naturlauf belegt): nicht Bauplatz/Baeume, sondern die Zeitueberwachung des Besitzers in continuePlan (1200 Ticks ohne Annaeherung -> rejectPlot + abort, Platz samt geraeumter Baeume verworfen).
(1) veraltete Wegverfolgung nach Abwesenheit (Material holen/anderer Job): bester Abstand 6,0 gespeichert, Arbeiter bei 22; 50,1 vs 55,5; 30,6 vs 58,5 -> las sich als 1200 Ticks ohne Fortschritt.
(2) Besitzer kommt von seinem Standort nicht los: 159,68,-357 (Kuechengebaeude, Pfad 1 Knoten) und 137,67,-343 (eingezaeuntes Becken; Pfad nodes=42 erreichbar, Besitzer bewegt sich trotzdem nicht - Ursache ungeklaert, auch storage betroffen).
Fix (Builder.java): noteStandVisit setzt nach >200 Ticks Abwesenheit Abstands-/Routen-/Fehlerzaehler zurueck, eigener Fortschrittsstempel fuer Schritt-0-Abbruch; handOverStuckOwner (bei Schritt 0 nicht am Stand + Sites.walksBackTo vom Plan zur Strasse -> Besitzer pausiert 2400 Ticks, naechster Bewohner uebernimmt, max 3 Uebergaben/Plan, Platz bleibt); Diagnose Standdiagnose/Abbruchzeile mit 11x11-Karte.
Gruppen (von mir nach Merge, je 1 Lauf): builderpaths 86 (79 + 7 neue standAbsence-Fixtures), builderchecks 318, houseplot 140, supply 30, planstonesupply 22, milestones 16, constructionsite 21, housesitesearch 36, palettefallback 21, strandrescue 19, deepstrand 17, housesign 7.
Natur (Agent): mit Absenz-Reset kein Abbruch, Plaene bis Schritt 119/437 bzw. 127/443 (bezahlte Bloecke gesetzt = erste gesetzte Bloecke ueberhaupt); mit Uebergabe 0 Granary-Abbrueche, Otto uebernimmt (Schritt 137/442). Fertiggestellt 0 - ab Schritt ~119 wartet der Plan auf cobblestone/spruce_planks.
Offen: warum Bewohner im Zaunbecken/Kuechengebaeude feststecken (umgangen, nicht behoben); Wirkung auf Haeuser/Dschungel ungetestet.

## Naturlauf vp-natural111-m6 (voller Stand inkl. granary-site-Folge, 2 Etappen = 48 437 Ticks, Einzellauf)
Etappe 1: Plaene gestartet 3 (granary 86,68,-339 @2363; storage 167,66,-312 @2632 und 195,65,-363 @5922), fertig 0, gestrandet 1, unreachable<=4: 2.
Etappe 2: kein neuer Plan; Planstand granary 119/441, storage 75/266 (erste gesetzte Bloecke in einem Naturlauf); Mine Eingang 104,67,-375 gegraben bis Tiefe 11 (Heinz/Elsa, "von Hand"); gestrandet 1; unreachable 21 (<=4: 3); Steinbruch-Rueckweg verworfen 2.
Engpass jetzt: Bruchstein. granary "steht seit 15611 Ticks still ... Kiste Bruchstein/Stein/Ziegel/Kohle=0/0/0/22, fehlt cobblestone x18"; storage fehlt cobblestone x20 + spruce_log/planks. Gegrabener Stein erreicht den Plan nicht -> Agent claude/mine-supplies (Frage: wohin geht der Stein?).

## P6 Abschaltung ohne Farmer's Delight / Chef's Delight (ich, Naturlauf in Wegwerf-Worktree)
Aufbau: Worktree mit build.gradle `compileOnly` statt `implementation` fuer farmers-delight-refabricated und chefs-delight (Mod-Liste: 64 Mods, 0 Treffer fuer farmersdelight/chefsdelight; ctov, morevillagers, village_taverns, villagerplus geladen).
Ergebnis: Server startet, frische Welt Seed 4242, 24 271 Ticks (10x2400) ohne Spieler: 0x NoClassDefFoundError/ClassNotFoundException/NoSuchMethodError, 0 ERROR/Exception-Zeilen (gefiltert wie natural-run.sh), Plaene gestartet 2, gestrandet 0.
Einschraenkung: Klassen laden lazy; Koch-/Kuechenpfade (DelightKitchenCrafting, CookJob mit Kochtopf) wurden in 24k Ticks nicht gezielt ausgeloest -> "mod fehlt" ist fuer das Dorfleben belegt, NICHT fuer den kompletten FD-Kochfluss. Worktree und Welt danach geloescht, nichts committet ausser diesem Eintrag.
P6 Towns and Towers: CompatChecks.villageStructures (Gruppe compat jetzt 42 Checks): 26 towns_and_towers-Strukturen in #minecraft:village (Registry-Nachweis, kein Weltlauf; kein Test dass die Bell-/Palettenerkennung in einem T&T-Dorf funktioniert).

## Wo bleibt der gegrabene Stein? (Agent claude/mine-supplies, gemergt; Gruppen von mir nachgelaufen)
Korrektur zu m6: "Tiefe 11" war die GEPLANTE Tiefe; Heinz erreichte die Mine nicht (admissionBlocked, Rueckholung, "auf den Wegpunkt gesetzt", Pendeln zwischen Mine/Lager/Holz) - Heinz-Ablauf vom Agent NICHT einzeln nachgestellt.
Gemessen (Agent, Trace, Naturlauf stone4 ohne Fix): 98 Bruchstein gegraben (Tiefe 0-14), nur 38 in der oeffentlichen Kiste (Hauler holten sie binnen 2-150 Ticks); Bergmann Benno trug ab Tick 30543 28-55 Bruchstein und lieferte 22000 Ticks nichts ab:
MasonJob.mine liefert erst ab 64 Items, Planbesitzer-Pfad im Builder ab 12 (PLAN_STONE_BUFFER, haengt an supplyMissing). Zweiter Befund: Deadlock "wartet auf Bruchstein fuer eine sichere Minenterrasse" (Lueckenreparatur braucht 1 Bruchstein, Builder-Pfad lieferte alle 12 ab, keep=0): mind. 3300 Ticks.
Fix (Mining.java, PlanStoneSupply.java, MineKitChecks, Checks090): Lieferung nach Plan-Bedarf (>=12 oder ganzer Fehlbetrag; im Stollen requestCapacityReturn), 2 Bruchstein Reserve (COBBLE_RESERVE), patchFloor nimmt Bruchstein, sonst echtes gegrabenes Gestein/Erde aus dem Inventar. Neue minekit-Checks (17).
Gruppen von mir nach Merge: minekit 17, mining 68, miningcycle 199, minelifecycle 35, minewalk 27, surfacestone 13, planstonesupply 22, strandrescue 19, roles 112, gravelcycle 20, builderpaths 86.
Natur (Agent, fix2, 30 Sprints): 54 Bruchstein geliefert, Hauler holten jeweils binnen 150 Ticks; Plan bleibt trotzdem bei Schritt 0 (Besitzer Theo "kommt nicht zum Stand (kein Fortschritt zum Stand 121,67,-334)", Uebergabe 1/3, 2/3) - Builder-Ablauf, Streuung gross (in m6 kam derselbe Plan bis Schritt 119).
Offen: Fehlbetrag-Log zeigt weiter cobblestone x48 trotz Lieferung (Verbuchung an der Baustelle unklar); Builder-Besitzerpfad liefert mit keep=0; lange Pausen der Bergleute (15000-22000 Ticks).

## Naturlauf vp-natural111-m7 (Endstand aller Merges, 2 Etappen = 48 386 Ticks, Einzellauf)
Plaene gestartet 2 (granary 120,67,-348 @2324; storage 84,68,-335 @2862), fertig 0, Hausplan-Abbruch 0; Etappe 1: gestrandet 2, unreachable 12 (<=4: 5); Etappe 2: gestrandet 1, unreachable 8 (<=4: 1). "Stein gebrochen" 3 (Etappe 1) / 0 (Etappe 2).
Planstand bleibt ueber >24 000 Ticks bei granary 119/437 und storage 75/266 -> reproduzierbares Plateau wie m6: Bruchstein (granary x18, storage x20) und Bretter kommen nicht an.

## Gesamtstand zum Abschluss dieser Sitzung (13.11.)
Gemergt in claude/village-growth-1111: P1 Rueckholung (route) + Navigation-vor-Modell, Steinbruch (nearest-first, Regionen), Mine-Bewegungssperre/Terrassenmodell/Pfosten-Absetzen, Mine-Kit + Lieferung nach Bedarf, Baumwahl mit Rueckweg, Kornspeicher-Pass (terrassiert) + Absenz-Reset + Besitzer-Uebergabe,
Palette beim Planstart, Saatgras-Rueckweg, Tief-Rueckholung mit Karenz, P5 Haussuche (Cursor, Log, Dev-Hooks), D03-Praedikat + Checks, B04 Hausschild (Duplikat-Fix), gravelcycle-Fixture, P6 Laufzeit-Checks (Mapping, Serene-Seasons-Tags, T&T-Strukturen), Abschaltung ohne FD/CD (Naturlauf).
Neue/erweiterte Pruefgruppen: strandrescue 19, surfacestone 13, minewalk 27, minekit 17, treewalkback 32, deepstrand 17, housesitesearch 36, palettefallback 21, housesign 7, compat 42, builderpaths 86, networkdiscovery 42.
Ergebnis Naturlaeufe (Seed 4242, ohne Spieler): Plaene starten frueh (granary ~Tick 2300), erste Bloecke werden gesetzt, aber in 48k Ticks wurde KEIN Bau fertig; verbleibender Engpass = Bruchstein/Holz-Versorgung der Plaene (nur ~3 erreichbare Oberflaechensteinzellen, Mine liefert langsam, lange Pausen), Verbuchung des Fehlbetrags an der Baustelle unklar.
Nicht geprueft: Client/Optik, Vollpack, Spielerbetrieb, Dschungel-Granary, Haeuser ohne Hook (kein Hausbedarf auf Seed 4242 in 48k Ticks), FD-Kochfluss ohne Mod.
Push: durchgehend 403 (Claude-GitHub-App ohne Schreibzugriff) - alles nur lokal.

## P6 Serene Seasons (Agent claude/season-compat, gemergt) + Starter-Ausnahme (ich)
Agent: compat/SeasonCompat.java (MethodHandles erst nach isModLoaded("sereneseasons"), kein Klassen-Hard-Dependency; ohne Mod/ausgeschaltete Jahreszeiten "keine Einschraenkung"; API per javap verifiziert; nutzt die Mod-Regel ModFertility.isCropFertile, Fallback Registry-Tags + Tropen-Flag; Dev-Override fuer Checks),
FarmerJob (seasonalSeed, fieldsDormant, CROP-Schritt-Gate, Builder-Kompaktstarter, Winter-Angeln statt Idle), Gruppe seasonfarm (SeasonFarmChecks), compat 44. Agent-Zahlen (je 1 Lauf): seasonfarm 63, compat 44, farmexpand 172, a01behavior 20, roles 112.
Natur (Agent): ohne Serene Seasons (compileOnly, Wegwerf-Worktree) 24 229 Ticks ohne NoClassDefFoundError/ClassNotFoundException/NoSuchMethodError, seasonfarm dort 41; mit Serene Seasons 10x2400 Ticks ohne NoClassDefFoundError (API aufgeloest, tropical=true, mod fertility rule=true).
Befund des Agents: neue Serene-Seasons-Welten starten spaet im Winter (Default starting_sub_season=12, 8 Tage je Unterjahreszeit): ohne Ausnahme haette das erste Feld bis ~32 Spieltage (~768 000 Ticks) gewartet.
Meine Aenderung: Starter-Ausnahme (FarmerJob.seasonalSeed + CROP-Gate): hat das Dorf noch kein Feld (farms==0), wird bei vorhandenen 8 Weizensamen auch ausser Saison Weizen gesaet (die Mod laesst Ausser-Saison-Kulturen im Default langsam wachsen, nicht gar nicht); bevorzugt wird weiter eine Kultur in Saison. Weitere Felder bleiben streng.
Fixture angepasst (Designaenderung, nicht Fehlerkorrektur): "first field ... only sown in season" -> Weizen in jeder Jahreszeit; Winter-Warten nur noch ohne Weizensamen / fuer weitere Felder. seasonfarm 65. Nach der Aenderung von mir: farmexpand/compat/a01behavior/roles s. u.
Nicht geprueft: echte Jahreszeitwechsel im Langlauf; ob der Bauer im Winter wirklich angelt; temperierter Fall gegen die echte Mod-Regel (nur Tag-Matrix); Gewaechshausglas; Builder-eigene FARM-Plaene.

## P8 Audit-Luecken (Agent claude/audit-checks, gemergt; Gruppen von mir nach Merge, je 1 Lauf)
Neue Gruppen (synthetische Fixtures): freightload 16 (J07 Traeger belaedt Gueterlore aus echten Inventaren, Erhaltung, private Kisten unberuehrt), expedition 27 (J12 Zustandsmaschine, Mindestbesatzung 6, isAway, Heimkehr, Beute in oeffentlicher Kiste, Gefallene ohne Beute),
butcherslaughter 13 (J03 Auswahl/Schlag/Drops im Inventar, Lieferung ins Kornspeicherlager), a04idle 7 (A04 Leerlauf: Umbesetzung nach >4000 Ticks, Dorftrottel/Erfahrene nie, eigene erreichbare Pausenplaetze Ring 4-12), a04lease 2, lamplighter 9 (J10 Eintritt ueber primary/secondary setzt genau 1 Fackel aus oeffentlichem Bestand).
Regression nach Merge: a04survival 5, minetickets 20, roles 112, a01behavior 20, buildhaul 20, housesign 7, strandrescue 19, seasonfarm 65.
Produktionsfehler gefunden und behoben: village/Anchor.java - die Einwohner-Lease VILLAGER_WORK hatte Radius 0 (Chunk-Level 33: geladen, aber nicht entity-ticking); ohne Spieler tickten Einwohner nie (belegt im Fixture ueber 200 Ticks). Jetzt Radius 2. Hinweis: MINE_WORK hat vermutlich dasselbe Problem (Radius 0), nicht angefasst.
Meine Naturlaeufe mit forceload (entity-ticking) konnten diesen Fehler nicht zeigen. Kosten der groesseren Lease (mehr geladene Chunks) nicht gemessen.
Produktionsfehler NICHT behoben (Funktionsentwurf): J03 Craft.run liest nur die Dorfkiste, die zentrale Materialkiste lehnt Nahrung ab (Mixin), Rohfleisch geht ins Nahrungslager -> das nachmittaegliche Garen des Metzgers findet nie Rohfleisch (ohne Kornspeicher behaelt er es und versucht es alle 2400 Ticks erneut).

## J03 Metzger raeuchert aus dem Kornspeicher (Agent claude/butcher-cooking, gemergt) und mealcycle repariert (ich)
Agent: ButcherJob.secondary ruft nach Craft.run SmithSmelting.cookMeat (neues Worker.BUTCHER): Rohfleisch/Rohfisch nur aus dem Kornspeicher in den markierten Kuechen-Raeucherofen, Brennstoff nur aus oeffentlichem Bestand (ohne Brennstoff kein Batch), Ablage ueber StockPolicy.insertVillage zurueck in den Kornspeicher, nur bei Platz. Gruppe butchercooking 16 (3 Laeufe stabil; Negativkontrolle: ohne ButcherJob-Aenderung faellt der Check durch).
Von mir nach Merge (je 1 Lauf): butchercooking 16, butcherslaughter 13, butcher 14, cookdelight 26, stockpolicy 58, smithsmelting 12, roles 112. Ungeprueft: Naturlauf mit echter Kueche/Raeucherofen; zwei Metzger oder Koch am selben Ofen; Altwelt-Rohfleisch in der Dorfkiste koennte beim Zurueckschreiben (setItem + Mixin) verloren gehen (nicht belegt).
mealcycle: FAIL auch auf dem Ursprungsstand 01db13e (von mir verifiziert, eigener Worktree). Ursache (Fixture, Produktivcode richtig): (1) Routine.stopDeliveryApproachIfNeeded loescht das Laufziel nur, wenn JobState.target (bisheriger Lieferstand) nahe der Gemeinschaftskiste liegt - das Fixture setzte target per clearTarget() auf null;
(2) die Lieferung nutzt verteilte Standplaetze (JobUtil.sharedStandSpot) und wird uebersprungen, wenn rund um die Kiste kein tragender freier Stand existiert - das Fixture hatte dort keinen Boden, die "abgebrochene Route" begann nie (idleUntil blieb 0).
Fixture-Korrektur (HouseholdMealChecks): routine.target = Lieferstand; kleiner Boden (9x9) um die Kiste. Danach mealcycle PASS 10 (2 Laeufe).
## P4 Bruchsteinwirtschaft (Agent claude/stone-economy; Worktree /tmp/wt-stone2, Ports 25566/25576)
Messung (temporaerer Trace, entfernt; Naturlaeufe Seed 4242, je 48k Ticks, ein Tag hat nur 7000 Arbeitsticks = 29 %, 48k Ticks sind also nur zwei volle Arbeitstage):
- Baseline-Laeufe (base2/base3/fixa, Stand vor den Aenderungen): Bruchstein aus dem Dorf 3 (nur Oberflaeche, von Hand 3 Steine in ~750 Ticks), aus der Mine 0-3 Abschnitte; Planstand granary 119/441, storage 75/266 konstant; Kiste Bruchstein 0.
- Wer arbeitet am Stein: nur die Plan-Eigentuemer (supplyMissing) und die Maurer in MasonJob.mine; im Taiga-Dorf sind das 2 Maurer (Beruf miner/mason), beide zugleich Besitzer eines Bauplans (granary + storage). Tag 1 geht fuer Baugrundraeumung (Besitzer ~3500 Ticks) und das Heranschaffen des Materials der ersten 119 Schritte (~1900 Ticks) drauf; erster Steinbezug erst ~Tick 8200 (Oberflaechenstein, 3 Steine), Minengang erst am Tagesende. Beide Maurer holen Holz/Bretter zuerst nur wenn kein Baum frei ist; "wartet auf passendes Bauholz" 900-2600 Ticks pro Tag (naechster Engpass: Holz).
- Hauptverlust 1 (Mine-Kit): mit Spitzhacke (die der Besitzer fuer den Bruchstein-Wunsch fertigt) gilt das Kit; Abschnitt 2 (Stuetzen) und 3 (Fackel, keine Kohle im Dorf) warteten je 12000 Ticks (Spielzeit, ~1,7 Arbeitstage!), das Basteln der Stuetzen aus Dorfplanken (konkurriert mit dem Plan um Fichtenbretter) kostete 4800 Ticks eines Arbeitstags. Messung: Tiefe 3 von Tick ~4800 bis ~25000 (base2), 3 Abschnitte in 48k (base3). Von Hand (keine Spitzhacke) gibt es kein Kit.
- Hauptverlust 2 (Laufwege): Maurer stehen 600-3300 Ticks auf "geht zur Mine" ohne sich zu bewegen (Tuerrahmen des eigenen Hauses 98,70,-411; Terrasse y 74-75 ueber der Mine mit path=false zu allen Arbeitsplaetzen; Pavillon 135,68,-343). Der Weg-Cache (Builder-Haulziel 94,70,-411 gegen Mine-Waypoint) wurde nicht weiter aufgeloest.
- Hauptverlust 3: Mine wird wegen "Hohlraum unter dem Stollen" (Schlucht/Hoehle) bei Abschnitt 5-9 geschlossen (2 von 6 Laeufen); Neuanlage scheiterte danach 16-20 Mal ("kein Eingang gefunden", Altminen-Sperre 24 Bloecke + Bauplaene).
- Kein Pickaxe-Problem im Stollen: ein Abschnitt (3x3x3) wird ohnehin mit 24 Ticks Pause gegraben, von Hand oder mit Spitzhacke gleich; 100 Ticks/Block gilt nur fuer den Oberflaechensteinbruch (3 erreichbare Zellen).
Aenderungen (Mining.java, MineKitChecks, StoneEconomyChecks, Checks090):
1. Kit-Grenze in Arbeitszeit: Aufwand (Wege, Basteln, Warten) pro Abschnitt max 1800 Ticks (Schritt pro Aufruf max 400, Nachte zaehlen nicht), danach 12000 Ticks Auszeit fuer weitere Abschnitte (keine erneute Kit-Wartezeit je Abschnitt). Stuetzen/Fackeln werden weiter verwendet, wenn das Lager sie hat; es bleibt eine Abwaegung gegen Beleuchtung/Stuetzen ohne Vorrat (nicht natuerlich bewertet).
2. Weg-zur-Mine-Wachhund: kein Naeherkommen in 600 Ticks ab 8 Bloecken Abstand -> Bewohner auf die Dorfstrasse (villageReturn), nach 3 Wiederholungen Gang fuer 1200 Ticks aufgegeben.
3. Freie Arbeitsplaetze ohne Weg (workSlot -1 mit freiem sicheren Stand): nach 900 Ticks auf die Dorfstrasse.
4. Mine-Neuanlage sucht nach jedem Fehlschlag weiter draussen (Ring 24 -> bis 48 Bloecke).
Natur nachher (final, je 48k Ticks, Bruchstein aus Mine+Oberflaeche): fin1 45 (storage 75->99), fin2 5 (Mine bei Abschnitt 5 wegen Hohlraum geschlossen, Neuanlage scheiterte; Vorversion ohne Punkt 4), fin3 27 (granary 119->140), fin4 21 (granary 119->137), Zwischenstaende fixb 29 (granary 131, storage 89), fixc 39 (granary 149). Vorher 3 je Lauf. Kein Bau fertig (Holz/Bretter und weiterer Stein fehlen: granary braucht >100 Bruchstein).

## P4 Bruchsteinwirtschaft: Zusammenfassung des Agent-Berichts (claude/stone-economy, gemergt; Gruppen von mir nach Merge, je 1 Lauf)
Gemessen (Agent): 48k Ticks = nur 2 Arbeitstage zu je ~7000 Arbeitsticks; im Taiga-Dorf nur 2 Maurer, beide zugleich Planbesitzer (granary, storage); Tag 1: Bauplatzraeumung ~3500 Ticks + Material fuer Schritte 0-119 ~1900 Ticks, erster Oberflaechenstein ~Tick 8200;
groesster Hebel Mine-Kit: Stuetzen (Abschnitt 2) und Fackel (Abschnitt 3, keine Kohle im Dorf) warteten je 12000 Ticks (~1,7 Arbeitstage); Basteln der Stuetzen bis 4800 Ticks, konkurriert mit dem Plan um Fichtenbretter; Maurer standen 600-3300 Ticks auf "geht zur Mine" (Haustuer 98,70,-411: Builder-Haulziel und Mine-Waypoint ueberschreiben sich; Terrasse y74/75 mit path=false);
Mine schloss in 2 von 6 Laeufen wegen "Hohlraum unter dem Stollen", Neuanlage scheiterte 16-20x; Spitzhacke im Stollen kein Hebel (24 Ticks je Abschnittsbruch), die 100 Ticks/Block gelten nur fuer den Oberflaechensteinbruch.
Aenderung: Kit-Aufwand je Abschnitt auf 1800 Arbeitsticks begrenzt (max 400 je Aufruf, Naechte zaehlen nicht), danach 12000 Ticks Auszeit; Weg-Waechter (ab 8 Bloecken 600 Ticks ohne Annaeherung -> Dorfstrasse, 3 Wiederholungen -> Gang 1200 Ticks aufgegeben); Lane-Waechter (900 Ticks freie Plaetze ohne Weg); Minen-Neuanlage weiter draussen (Ring 24 bis 48).
ENTSCHEIDUNG FUER DEN NUTZER: die kuerzere Kit-Grenze laesst Abschnitte nach der Auszeit ohne Stuetzen/Fackeln graben, wenn das Lager sie nicht hat (vorher nach 12000 Ticks Wartezeit, jetzt nach 1800 Arbeitsticks Aufwand); Sicherheit gegen Tempo nicht natuerlich bewertet.
Gruppen nach Merge: stoneeconomy 9 (neu), minekit 19, mining 68, miningcycle 199, minelifecycle 35, minewalk 27, surfacestone 13, planstonesupply 22, strandrescue 19, roles 112, gravelcycle 20, builderpaths 86, mealcycle 10. (gravelcycle fiel bei Kit-Grenze 900 durch, bei 1800 gruen: wenig Spielraum.)
Natur (Agent, je 48k Ticks, Seed 4242): vorher 3 Oberflaechensteine, 0-3 Mine-Abschnitte; nachher Stein gebrochen 27 (fin3), 21 (fin4) bzw. 45/5/29/39 in Zwischenstaenden (Streuung 5-45); Planstand granary 119->140/137, storage 75->99; KEIN Bau fertig (Granary braucht weit ueber 100 Bruchstein); naechster Engpass Holz/Bretter (Maurer warten 900-2600 Ticks/Tag auf Bauholz).
