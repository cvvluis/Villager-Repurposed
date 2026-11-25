# Changelog

Versions below target Fabric, Minecraft 26.2 and Java 25. The artifact is named `villagers-repurposed-<version>.jar`.

## 0.11.3

See RELEASE_NOTES_0.11.3.md. Fixes the world-entry crash of 0.11.2 (`NoClassDefFoundError: SocialChecks`), adds the `verifyJarRefs` build check and removes per-tick recipe scans.

## 0.11.2 (playtest)

- **Fixes a crash when entering a world (0.11.2 playtest jar).** `Social.tick` called into `SocialChecks`, which the release jar strips, so a villager chat search threw `NoClassDefFoundError: de/villagerplus/job/SocialChecks` and took the world down. The jar now keeps `SocialChecks`, and the build runs `tools/check-jar-refs.py` (task `verifyJarRefs`, part of `build`) which fails when a production class references a class the jar does not contain.

See RELEASE_NOTES_0.11.2.md.

- **Repair goods now prefer the tool chamber and never get stranded; public storage no longer truncates oversized stacks (G04/G05).** Non-damageable public-material items that the loaded item rules accept as an equipment repair ingredient and that are not construction or smelting stock (for example leather) are routed to the toolshed when one is registered. Ingots and nuggets stay ordinary public material because builders, smiths and the anvil recipe also consume them, and the anvil repair already reads every public container. Without a registered chamber, or when it is full, repair goods fall back to public storage; equipment still stays with the carrier. `StockPolicy.insertInto` now inserts in stack-size pieces so a hand-built stack such as two milk buckets can no longer lose an item. Checks in group `stockpolicy` (59 -> 64) cover granary routing of cooked/raw food, meat, crops, eggs, milk, mushrooms and ingredients, the chamber filter, and the missing-chamber fallback. Fixture proof only.

- **House lots no longer wait forever for a shovel.** Levelling natural earth for a house or civic site needs a real shovel in the builder's own inventory, and shovels only came out of the toolshed (which no natural village had finished): in one natural copy (seed 4242) the first house stayed at step 0 from tick 24,200 to the end of the run at 303,000. The plan owner now takes a stored shovel from the toolshed when there is one and otherwise crafts a plain wooden or stone shovel from real public planks/cobblestone and sticks (same recipe path as the plan pickaxe, nothing is conjured). Check group `planstonesupply` (recipe, no-input, toolshed withdrawal, craft from public stock, cursor untouched). Natural copy after the change (n=1): the house reached step 52 by tick 102,600.

- **A resident that keeps climbing back onto a fence post is brought back after three step-offs.** The perch step-off spaced itself out up to 2,400 ticks and the strand watch needs three failed routes from one spot, so a villager who walked from a barrel onto the neighbouring fence post again and again was never rescued (natural runs: 19, 11 and 9 step-offs in a row, 2,500 to 5,600 ticks each). Three step-offs within 1,500 ticks near one spot now count as a loop and use the normal strand rescue (mining window and quiet period still apply). Check group `strandrescue`. Natural copy after the change (n=1): the longest loop was three step-offs / 1,100 ticks.

- **Cadence measurement for natural runs (dev tooling only).** `tools/cloud/cadence.py` summarises sections per day, idle intervals between real steps (working phases only: no night, breaks, lunch or thunderstorm) and what villagers did in the longest gaps; `natural-run.sh` writes per-sprint `villagerplus status` snapshots with `VP_STATUS_DUMP=1`.
- **B01/B05 server-side proof.** A resident of a vanilla profession whose work block is gone (no job-site memory) now fetches the raw materials from the public village chest, crafts the block at a real crafting table and places it (`WorkBlocks`, via the existing `Workstation.ensure`); residents that still own a job site are untouched. New check groups `workblocks` (67 checks over all 13 vanilla work blocks) and `chestscan` (21 checks: stale registration, half-owned double chest, private warehouse registration and a private neighbour never reach carrier scans, rail stores, milestone depots or crafting inputs). Fixture proof only; no client or natural-run evidence.
- **Road gravel is requested in batches and the quarry-to-road loop is balance-checked.** New overland road construction now asks the mason for a bounded gravel batch (8) instead of one block per scan; a single damaged road cell still requests exactly one. The `roadwork` group proves the chain on a fixture: real natural gravel is quarried into the public depot, the road builder withdraws and places it, and every removed gravel block is accounted for as stock, cargo, flint or placed surface.
- **Dry-ravine bridges and recipe coverage (D04, R01; fixture-proven only).** A road lane with a committed bridge plan is no longer scanned for natural trees, so the first spruce pier log in a dry ravine no longer blocks its own bridge; the paid road worker now completes a four-column, seven-block-deep ravine (96 paid blocks, cursor advances past the span). Shapeless crafting recipes with 5, 7 or 8 ingredients (rabbit stew, beetroot soup, honey bottle, netherite ingot, cake from slices) are now placeable through a padded 3x3 grid, and levers are public material stock. New checks: `roadbridge` ravine fixture, `craftrecipes` sweep over every loaded crafting recipe plus lever and cake leftover buckets.

- **Supply helpers resume existing work before changing jobs.** Unclaimed stone/log loads needed by a plan get an early physical hand-in offer. Existing tree cursors resume before unrelated profession tasks, without selecting a second tree; full inventories and unloaded tree targets yield safely. Active mining work and marked construction freight remain reserved. The ordinary wood/stone helper paths also reject marked cargo, and public routine deposits and evening private storage preserve another project's reserved load. Early tree continuation requires civic demand, returns to the root before pending removal, and yields to live road tasks so fetched post material stays reserved.

- **Useful mine loads can return before the stock buffer is full.** A load that can fund a live direct cobblestone request takes the existing physical return route. The mine reserve stays intact; variants count only for a proven prepared above-ground substitutable window. Foundations, unknown plans and marked freight stay protected.

- **Processed timber counts toward supply.** Matching public planks, sticks and finished shapes now pay whole loaded crafting batches before villagers seek more logs. Direct structural logs remain separate. Plan tree selection and early continuation follow the species still missing; partial recipe batches cannot falsely clear demand.

- **Overnight gaps no longer trigger morning palette swaps.** A stalled plan accumulates short, observed supplier-work intervals; gaps longer than 400 ticks do not advance the 3,000-tick fallback clock. Changed demand or cursor signatures reset it. This estimates active work and excludes long routine, weather and unload gaps; it is not an exact work-time meter.

- **Construction stock can be crafted on site.** A plan owner uses the site's paid, loaded crafting table to turn already delivered surplus materials into missing plan blocks through loaded crafting recipes. Materials paying direct placements remain reserved, recipe batches keep their real ingredient costs and leftovers, and crafting is rejected without capacity or a reachable workbench. Actual crafting refreshes progress without treating approach retries as completed work.

- **Construction carriers finish their existing load first.** Every profession resumes its marked construction delivery before starting unrelated work. The real stack goes to its tagged project's persisted site stock even when the next placement is already funded; cancelled or completed orders return the stack physically to public storage. This uses the same inventory and manifest transactions as normal hauling, without changing the save format.

- **E02/E04 integration checks (dev only, no gameplay change).** The Village Taverns world probe no longer ends in SKIP on a world without a tavern: group `tavernservice` now builds a tavern-like room with the mod's real `village_taverns:barrel` marker and proves the marker scan, the reachable-stand search and the rejection of a vanilla barrel (23 checks). **Not proven:** the `StructureStart` lookup of a naturally generated tavern and the evening gathering itself (status stays *partial*). Group `compat` now reports the EMF/ETF versions on the dev classpath; the mod has no EMF/ETF-specific code, and role skins with EMF, ETF or Fresh Animations stay *unverified* until a client has looked at them.

- **New rail track is built by a villager, not by the village tick (D07).** The tick now only records the next affordable rail piece (or the station platform); a road builder collects the rail (and redstone block for powered rail) from the public stock, walks to the site and places it from his inventory, and the saved cursor advances once per placed piece. Repair of existing track is unchanged. Saved worlds keep their cursor; the pending-job marker is an extra key. New checks in `railmetro` (physical fetch, no placement from a distance, carried item consumed). The road groups (`roads`, `roadterrain`, `roadlighting`, `lightspacing`, `roadwork`, `roadroute`, `roadbridge`, `roadclear`, `caravanroad`) were re-run green. Station platform materials are still paid from the village stock at placement. Verified on the dev server only.

- **Caravan porters carry freight by hand (K04).** After leading the donkey to the depot, the porter now walks to it, moves the real stack from the donkey chest into his own hands (shown in his main hand), walks it to the matching chest/granary/tool shed/treasury stand and puts it in. The held stack is the only copy while he carries it; an interruption, unload or death returns it to the donkey chest (or drops it if the chest is full) so goods are never duplicated or lost. Fixture checks (`caravandelivery`) cover the hand-carry, conservation, abort/return, full-chest drop and the emerald purchase (crate first, then treasury) with full reversal when it cannot be paid. The visible look in a client was not observed.

- **Civic center leaves the house expansion pad free (G10).** The plaza site search and the town hall site search now skip any footprint (plus clearance) that touches the prepared house expansion pad. New fixture checks in groups `civic` and `houses` (the latter also replays the home/civic size rotation for the small/medium/multi-story mix, G09). Fixture-level proof only; richer plaza decoration and natural-village placement are not observed in a client.

- **Ruined-building detection (G11).** Villagers identify ruined village buildings and schedule physical demolition. This does not establish the complete natural demolition, renaturation and replacement chain.

- **Mine recovery walks (G12).** Mine recovery uses walking routes instead of teleporting workers. Safe, reliable navigation in every natural mine remains unverified.

- **Timber supply.** Plan demand includes the logs needed for wooden stairs, slabs and other shapes; shapes are crafted in batches. Unclaimed log carriers hand in their loads, and a refused stack no longer prevents other goods in a mixed deposit from being delivered.

- **Development mining checks.** Cold remote fixture chunks have up to 1,200 ticks to become entity-ticking, matching the rail preparation budget. The former 200-tick deadline expired before terrain generation finished in this environment; all mining assertions and readiness gates remain unchanged.

- **Mine terrace repairs use real stock without draining the active plan.** When the current gallery section has a one-block floor gap, miners request just the cobblestone needed to repair that lane from the communal chest, preserving recorded building demand. One natural copied world advanced from mine index 28 to 34 after the repair material arrived; this did not finish the toolshed or reach iron.
- **Anvil projects keep their exact iron demand visible to miners.** Active anvil requirements now account for the vanilla recipe (three iron blocks and four ingots) and iron blocks already in communal stock, even when copper or coal makes the aggregate metal balance look sufficient. A placed anvil elsewhere does not clear an active project's need for an anvil item.
- **Stalled miners choose reachable return pads.** A free, safe waiting spot near a mine is only selected when vanilla navigation finds a path to it. In one copied natural continuation, the mine advanced from index 34 to 35 over 10 × 2,400 ticks; another miner still repeated its surface approach, so regular mine cadence remains unproven.

## 0.11.1

See RELEASE_NOTES_0.11.1.md.

- **Fixes a server crash on startup.** `RecipeIndex.rebuild` assembled every crafting recipe with an empty grid to index its result. The vanilla decorated pot recipe throws `ArrayIndexOutOfBoundsException` for an empty grid, so the server (and a single-player world) crashed while starting with 0.11.0. A recipe that cannot assemble an empty grid is now skipped instead. Found by running the dev server on the 0.11.0 state; not yet confirmed in the game client.

- **Translatable player texts (de_de + en_us).** About 150 hard-coded German `Component.literal` messages (chat and action-bar notices, village map and board headers, trade-network messages, builder/caravan/staffing/mining notices, village board buttons) are now `Component.translatable` keys in `assets/villagerplus/lang/de_de.json` and `en_us.json`; the German texts are unchanged, English texts were added. Keys follow the existing `message.villagerplus.*` / `gui.villagerplus.*` scheme. The chat marker prefix `◆ DORF  › ` stays a literal in `VillageMessages.format`, so the client toast still triggers in every language. New dev check group `langkeys` verifies that both lang files have identical keys and placeholders, that every key used by the compiled classes exists in both files, and that each `Component.translatable` call passes as many arguments as its text has placeholders. **Caveat:** translatable components are resolved by the client; a client without the mod shows raw keys when joining a dedicated server with the mod (the mod is required on the client anyway). Texts built from plain strings (village statistics lines, demolition/relocation results, profession names, sign text, entity names, the debug commands) are not translated yet. Verified on the dev server only, not in the game client.

- **More translatable texts.** The board's zone tool buttons, the "Live" button, profession names on the profession slider, the villager nameplate (profession/apprentice line) and the fallback texts of board result notices are now `Component.translatable` keys (de_de + en_us). The `langkeys` check now also fails when a new `Component.literal` with plain words appears outside a short allowlist. Still German-only on purpose: texts generated as strings on the server (village statistics, villager info, demolition/relocation/network results, board tab lines), entity names and sign text that game logic matches by text (caravan donkey/guard/trader, guests, mine signs), and dev commands. Not visually verified in the game client.

- **Villagers use cobblestone variants.** Plain cobblestone block steps are paid first with andesite, diorite or granite (largest stock first); foundations, stairs and slabs still need real cobblestone. Miners and stone hands deliver variants too.
- **Mines survive single-worker path failures.** Path failures are tracked per entrance and per worker, expire, and reset on any successful path. A mine closes only when two workers fail or one worker fails repeatedly over time.
- **Masons can dig the whole mine.** The "lived below the village" rescue carried the plan's own mason back to the street whenever he went more than 12 blocks below it, so every mine section beyond number 12 was dug by helpers only (about two sections a day); a mason who works the mine is now left alone. A miner underground also turns back for a bigger load (36) than a resident on the surface (12), which saves most of the round trips through the single-file shaft.
- **Mines continue past small cave pockets.** Ore veins no longer take the floor or roof course of the gallery (they left two-block-deep holes under a walk lane that closed the mine as "blocked"), and a dry pocket of up to three blocks under a lane or post footing is bridged with one real block from the miner's pockets. Deeper caves, fluids and containers still close the mine. The replacement-mine search tries all four directions per site (hillsides ruled out most single guesses), tolerates a steeper walk to the entrance after failed rounds and keeps only 16 blocks of clearance around a finished shaft once rounds failed. Deliveries from a mixed inventory pick the depot per kind of goods, so stone no longer stays behind a food stack.
- **Placement and break effects.** Villager-placed blocks play the block's own placement sound with particles; dismantling ends each block with a break sound and particles.
- **Larger walls.** The village wall radius cap is raised from 192 to 384 blocks. Saved plans stay backward compatible.
- **Villager behaviour fixes.** Fence wood and starter seeds are gathered before the first field; guards keep watch from towers; long-route and mine-kit stalls are reported more clearly on the board; recipe, road and rail fixtures stabilised.
- **Faster supply for the first civic plans.** Idle residents now also fell trees for a plan that lacks logs (up to four at a time, wood twin of the stone hands); logs a mason or helper cleared from a building site and kept in his pockets are handed in at the next routine delivery or before building; a plan that has not moved for 2400 ticks gets every mine lane staffed; with several civic projects the one with the least left to pay keeps what its wish list asks for in the public stock (a stalled lead gives it up after 9000 ticks); granary, storage and tool shed use wooden fences for their windows when no glass is in stock, and a hay bale becomes optional; a plan that also lacks cobblestone no longer swaps its planks for even more cobblestone after a wood stall.
- **Several times more stone from the mine.** A stock-flow trace of the natural runs showed the cobblestone limit was mostly walking and waiting, not digging (about 50 ticks per section, but 1000 to 1800 ticks of walking and queueing per trip, and only a few trips a day): the walk inside the gallery is sent up to three verified cells ahead (and a little faster), the three lanes let miners pass each other on the way out instead of waiting in single file, plan owners and stone hands no longer leave a deep gallery with twelve stones (the load that justifies the walk out grows with the depth, up to 192, and the stone of the whole crew in the gallery counts), the short break no longer sends a miner up and down, a plan keeps a modest cobblestone stock (64) in the public chest instead of fetching every batch of 18 with a new trip, a section that lacks only a torch no longer waits when the depot has neither torch nor coal, one supply trip takes the kit of up to 16 sections the depot holds complete (kit waiting time halved), and a walk to the mine that makes no progress is reset after 300 instead of 600 ticks. Saved games and all safety rules of the mine are unchanged.

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
