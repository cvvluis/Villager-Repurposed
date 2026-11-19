# Villagers Repurposed 0.11.0

Fabric · Minecraft 26.2 · Java 25

## Sperrzonen on the Dorftafel

The map now has five zone types: field, residential, work, pasture and **Sperrzone** (restricted zone). The village does not work inside a Sperrzone: no new buildings, no tree felling, no mining, no widening of the main paths between square and bell. The other zones still control *where* planning happens; the Sperrzone protects explicitly marked areas.

## Building-site diagnostics

If the village finds no suitable house site, it remembers a short statistic (slope, zone, blocked plot, ...). This line appears on the Dorftafel (village board) and in the status command alongside the other village data.

## Dorfplatz (village square) and Dorftafel

New villages look for the square at the **residential centroid** (bell, houses, beds), not at the AABB edge. The square is smaller and is immediately designed with palette paving, a well, benches, lanterns and flowers; the **official Dorftafel** stands at the square entrance facing the center, with a **flagpole** beside it. The empty starter chest disappears after the move to the square; its contents move into the new village chest.

**Wayfinding:** `/villagerplus tafel` and `status` show clickable board coordinates (OP: teleport link). Entering the village area shows a throttled action-bar hint (`-Dvillagerplus.boardWayfinding=false` to turn it off). **Moving the board:** sneak + right-click on the Dorftafel, or **"Tafel hierher verlegen"** (move board here) on the Dorftafel map (pins the position; only at a safe location). The **banner color** follows village style/biome. Paved-over paths in the square stay registered in the path network. Squares that are already built are only retrofitted (board/flagpole/decoration), not dug anew. **`/villagerplus platz neu`** (gamemaster/OP) creates an additional square at the new core.

## Village bell and data storage

A village bell with village data breaks on normal mining only after confirmation. With sneaking it is picked up as an item together with **all** persistent village attachments (network, market, journal, blueprints, roads, quotas, zones, ...) and placed again in the same dimension, at a distance from other villages. World coordinates in this data are shifted to the new bell location; neighboring bells in the trade network, Dorftafel blocks and the journal history stay unchanged (note in chat). Creative duplicates of the relocation bell carry no second copy of the data record. Explosions do not destroy managed bells. On server stop, internal caches are reliably cleared.

## Technical

Recipes are indexed at server start. Profession tags and E04 roles use stable tag lookups. The relief map on the large Dorftafel re-renders only when the underlying pixels change.

## Release JAR

The JAR stays much smaller than the full dev state, but includes everything the production code loads directly (`G02SupplyTrace`, `AutoTest`, `G01VillageSpacingChecks`).

## Note

`gradle build` passed at this state. Please check the Sperrzone, the building-site line and the bell relocation once in game against real village data.
