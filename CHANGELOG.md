# Änderungsprotokoll

Alle genannten Versionen gelten für Fabric, Minecraft 26.2 und Java 25. Die JAR heißt `villagers-repurposed-<version>.jar`.

## Unreleased (0.11.1)

- **Fixes a server crash on startup.** `RecipeIndex.rebuild` assembled every crafting recipe with an empty grid to index its result. The vanilla decorated pot recipe throws `ArrayIndexOutOfBoundsException` for an empty grid, so the server (and a single-player world) crashed while starting with 0.11.0. A recipe that cannot assemble an empty grid is now skipped instead. Found by running the dev server on the 0.11.0 state; not yet confirmed in the game client.

- **Translatable player texts (de_de + en_us).** About 150 hard-coded German `Component.literal` messages (chat and action-bar notices, village map and board headers, trade-network messages, builder/caravan/staffing/mining notices, village board buttons) are now `Component.translatable` keys in `assets/villagerplus/lang/de_de.json` and `en_us.json`; the German texts are unchanged, English texts were added. Keys follow the existing `message.villagerplus.*` / `gui.villagerplus.*` scheme. The chat marker prefix `◆ DORF  › ` stays a literal in `VillageMessages.format`, so the client toast still triggers in every language. New dev check group `langkeys` verifies that both lang files have identical keys and placeholders, that every key used by the compiled classes exists in both files, and that each `Component.translatable` call passes as many arguments as its text has placeholders. **Caveat:** translatable components are resolved by the client; a client without the mod shows raw keys when joining a dedicated server with the mod (the mod is required on the client anyway). Texts built from plain strings (village statistics lines, demolition/relocation results, profession names, sign text, entity names, the debug commands) are not translated yet. Verified on the dev server only, not in the game client.

- **Villagers use cobblestone variants.** Plain cobblestone block steps are paid first with andesite, diorite or granite (largest stock first); foundations, stairs and slabs still need real cobblestone. Miners and stone hands deliver variants too.
- **Mines survive single-worker path failures.** Path failures are tracked per entrance and per worker, expire, and reset on any successful path. A mine closes only when two workers fail or one worker fails repeatedly over time.
- **Masons can dig the whole mine.** The "lived below the village" rescue carried the plan's own mason back to the street whenever he went more than 12 blocks below it, so every mine section beyond number 12 was dug by helpers only (about two sections a day); a mason who works the mine is now left alone. A miner underground also turns back for a bigger load (36) than a resident on the surface (12), which saves most of the round trips through the single-file shaft.
- **Placement and break effects.** Villager-placed blocks play the block's own placement sound with particles; dismantling ends each block with a break sound and particles.
- **Larger walls.** The village wall radius cap is raised from 192 to 384 blocks. Saved plans stay backward compatible.
- **Villager behaviour fixes.** Fence wood and starter seeds are gathered before the first field; guards keep watch from towers; long-route and mine-kit stalls are reported more clearly on the board; recipe, road and rail fixtures stabilised.

## 0.11.0

Dorftafel: fünfte Zonenart **Sperrzone** (`protected`). Darin dürfen Villager nicht bauen, Bäume fällen, graben, minen oder Hauptdorfwege ausweiten. Auf der Karte rot/markiert; Löschen wie bei den anderen Zonen.

- **Bauplatzdiagnose:** Letzte Hausplatz-Suche wird an der Glocke gespeichert und auf der Dorftafel unter den Statistikzeilen zusammengefasst.
- **Weltwechsel:** `WorldCaches` leert Anchor-, Builder-, Job-, Dorf- und Schutz-Zustände beim Serverstopp.
- **Dorfplatz & Tafel:** Suche am Wohnschwerpunkt (Betten/Gebäude), kompakterer Platz (~17×17), Pflaster/Brunnen/Deko sofort, Tafel am Platrand mit biompassender Fahne; Wegweiser (`/villagerplus tafel`/`status`, Actionbar); Schleich-Rechtsklick und Karte-Tab **„Tafel hierher verlegen“** verlegen die Tafel; `/villagerplus platz neu` für zweiten Platz; überpflasterte Dorfwege bleiben im Wegnetz; Bau-Nudge nur für Entities im Fußabdruck an sichere Randplätze; bestehende Plätze werden nachgerüstet, nicht abgerissen.
- **Dorfglocke:** Umzug mit Schleich-Abruch und Bestätigung; alle Dorf-Attachments an der Glocke (Netz, Markt, Journal, Pläne, Straßen, Quoten, …) wandern mit; Koordinaten werden verschoben; andere Dimension wird abgelehnt; Kreativ-Kopien tragen keine zweite Datensatz-Kopie; Explosionsschutz per Mixin.
- **Relief:** Große Dorftafel überspringt Relief-Neuaufbau solange sich das Kartenraster nicht ändert.
- **Release-JAR:** Größtenteils ohne `*Checks` und `de/villagerplus/dev/**`; Produktionspfad-Klassen (`G02SupplyTrace`, `AutoTest`, `G01VillageSpacingChecks`) bleiben in der JAR.

`gradle build` ist auf diesem Stand durchgelaufen.

## 0.10.9

Leistung: ein Modell pro Rollen-Villager statt doppeltem Geometrie-Pass; Bauplan-Spitzhacken-Checks nicht mehr jeden Tick bei unverändertem Dorflager.

- **Rollen-Skins:** Basis `villager.png` und Rollen-Overlay werden beim Client-Ressourcen-Reload zu einer dynamischen Textur zusammengelegt. Berufs- und Biom-Layer entfallen für Rollen-Erwachsene; der alte `RoleSkinLayer` entfällt. Rolle wird einmal in `extractRenderState` ermittelt.
- **Rezepte:** `RecipeCrafting.assign` nutzt einen einmaligen Kisten-Snapshot pro Aufruf. `PlanStoneSupply` cacht negative Spitzhacken-Craft-Checks etwa 4 Sekunden pro Dorf, solange sich das Inventar nicht ändert.

`gradle build` ist auf diesem Stand durchgelaufen. Nicht im Modpack geprüft.

## 0.10.8

Dorfkarte und Relief erzeugen keine Chunks mehr. Das ist der Fix für das Einfrieren beim Betreten eines Dorfs im großen Modpack.

Beim Betreten eines Dorfs hing der Server-Thread in der Relief-Abtastung der großen Dorftafel. `hasChunkAt` an der Zellmitte reichte nicht: `getHeight` und `getBlockState` haben Nachbarspalten synchron geladen und dabei Gelände erzeugt (`getChunkBlocking`). Das Bild blieb stehen, der Server meldete „Can't keep up“.

- Karte, Relief und die Textkarte lesen nur noch Chunks, die bereits vollständig geladen sind. Pro Aufruf werden höchstens 480 Zellen mit je vier Stichproben klassifiziert. Der Rest erscheint in den folgenden Ticks. Ein fertiges Raster bleibt 10 Sekunden gültig, eines mit unbekannten Zellen 5 Sekunden. Eine fehlgeschlagene Abtastung wird nicht mehr alle 20 Ticks wiederholt.
- Dieselbe nicht blockierende Höhenlesung gilt für die übrigen Geländeabtastungen (Wege, Bauplätze, Berufe, Karawanen).
- Der Hänger-Wächter zählt `getChunkBlocking` nicht mehr als Pause, auch wenn derselbe Stapel `managedBlock` oder `pollTask` enthält. Der Client zählt Frames mit einer Schwelle von 3 Sekunden, nicht mehr Spielticks.
- Das Relief nutzt einen RenderType, den Iris unterstützt. `minecraft:pipeline/debug_quads` entfällt.

`gradle build` ist auf diesem Stand durchgelaufen. Im großen Modpack ist 0.10.8 noch nicht gespielt worden.

## 0.10.7

Der Hänger-Bericht des Clients zählt nur noch, solange eine Welt läuft. Das Erzeugen oder Laden einer Welt ist kein Fehlalarm mehr.

## 0.10.6

Friert das Spiel oder der Server länger als 8 Sekunden ein, schreibt die Mod die Thread-Stapel nach `logs/villagerplus-stall-server.txt` bzw. `logs/villagerplus-stall-client.txt` (höchstens drei Berichte je Start). Ein pausierter Einzelspieler-Server zählt nicht als Hänger. In 0.10.6 war das nur Diagnose; die Ursache ist in 0.10.8 behoben.

## 0.10.5

Drei handgebaute Vorlagen: Dorfhaus (`haus_dorf`, 14×13), Rathaus mit Veranda (`rathaus_dorf`, 22×23) und Schmiede mit Wohnhaus (`schmiede_dorf`, 19×15). Die Bauer setzen sie Block für Block aus dem Dorfvorrat. Was das Dorf nicht herstellen kann, wird ersetzt; fehlen Treppen oder Dachziegel, weichen die Treppen auf Bruchsteintreppen aus. Lava, Glocken, Leitern, Töpfe, Teppiche, Schilder und Arbeitsblöcke werden nicht mitgebaut. Im Spiel verwendet wird bisher das Dorfhaus für neue hohe, zweigeschossige Entwürfe. Laufende Pläne behalten ihre Form. Rathaus und Schmiede liegen in der Bibliothek.

## 0.10.4

Das Dorf-Toast fängt Fehler ab und schaltet sich dann selbst aus. Die Chatnachricht bleibt. `-Dvillagerplus.toast=false` schaltet das Toast ganz ab.

## 0.10.3

Keine dauerhaften `/forceload`-Chunks mehr. Das nächste Dorf wird nur mit kurzlebigen Tickets gehalten, nur für bereits geladene Chunks, nicht solange ein Spieler in der Nähe ist und nicht, wenn der Server schon überlastet ist (mittlere Tickzeit über 40 ms). Alte Zwangsladungen aus 0.10.0 bis 0.10.2 werden freigegeben. `-Dvillagerplus.keptVillages=0` schaltet das Festhalten ab.

## 0.10.2

Höchstens ein Dorf pro Dimension bleibt geladen, das dem nächsten Spieler am nächsten liegt. Alle anderen Dörfer, auch alte Zwangsladungen, werden freigegeben und arbeiten nur noch, solange ein Spieler in Reichweite ist. Einstellbar mit `-Dvillagerplus.keptVillages=N` (Standard 1).

## 0.10.1

- Bauern ernten reife Pflanzen im ganzen Dorf und angeln bei Nahrungsmangel. Essen geht vor dem Kornspeicher über eine begrenzte Brücke in die Dorfkiste. Knappe Mittagsportionen gehen an die Hungrigen. Ein nicht startbares Gehege blockiert den Feldplan nicht mehr.
- Untätige Bewohner und freie Maurer arbeiten an Bruchstein, wenn Baupläne auf Stein warten.
- Einwohner ticken ohne Spieler in der Nähe (Chunk-Lease mit Radius 2).

Die Stände 0.10.1 bis 0.10.7 sind auf dem Dev-Server mit Prüfgruppen gelaufen, nicht als vollständiger Modpack-Lauf.
