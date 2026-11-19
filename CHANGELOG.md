# Changelog

All listed versions target Fabric, Minecraft 26.2 and Java 25. The JAR is named `villagers-repurposed-<version>.jar`.

## 0.11.0

Dorftafel (village board): fifth zone type, **Sperrzone** (restricted zone, `protected`). Villagers may not build, fell trees, dig, mine or widen main village paths inside it. Shown in red/marked on the map; deleted like the other zones.

- **Building-site diagnostics:** The last house-site search is stored at the bell and summarized on the Dorftafel below the statistics lines.
- **World change:** `WorldCaches` clears anchor, builder, job, village and protection state on server stop.
- **Dorfplatz (village square) and board:** The search runs at the residential centroid (beds/buildings), the square is more compact (~17×17), paving/well/decoration are placed immediately, and the board stands at the edge of the square with a biome-matching banner. Wayfinding (`/villagerplus tafel`/`status`, action bar); sneak + right-click and the map tab **"Tafel hierher verlegen"** (move board here) relocate the board; `/villagerplus platz neu` creates a second square; paved-over village paths stay in the path network; the build nudge moves only entities inside the footprint to safe edge spots; existing squares are retrofitted, not demolished.
- **Village bell:** Relocation with sneak-break and confirmation; all village attachments on the bell (network, market, journal, plans, roads, quotas, ...) move with it; coordinates are shifted; another dimension is rejected; creative copies carry no second copy of the data record; explosion protection via mixin.
- **Relief:** The large Dorftafel skips rebuilding the relief as long as the map grid does not change.
- **Release JAR:** Mostly without `*Checks` and `de/villagerplus/dev/**`; production-path classes (`G02SupplyTrace`, `AutoTest`, `G01VillageSpacingChecks`) stay in the JAR.

`gradle build` passed at this state.

## 0.10.9

Performance: one model per role villager instead of a double geometry pass; blueprint pickaxe checks no longer run every tick while the village storage is unchanged.

- **Role skins:** The base `villager.png` and the role overlay are merged into one dynamic texture on client resource reload. Profession and biome layers are dropped for role adults; the old `RoleSkinLayer` is removed. The role is determined once in `extractRenderState`.
- **Recipes:** `RecipeCrafting.assign` uses a one-time chest snapshot per call. `PlanStoneSupply` caches negative pickaxe-craft checks for about 4 seconds per village as long as the inventory does not change.

`gradle build` passed at this state. Not tested in the modpack.

## 0.10.8

The village map and relief no longer generate chunks. This fixes the freeze when entering a village in the large modpack.

When entering a village, the server thread hung in the relief sampling of the large Dorftafel. `hasChunkAt` at the cell center was not enough: `getHeight` and `getBlockState` loaded neighboring columns synchronously and generated terrain in the process (`getChunkBlocking`). The picture froze and the server reported "Can't keep up".

- The map, relief and text map now read only chunks that are already fully loaded. At most 480 cells with four samples each are classified per call. The rest appears over the following ticks. A finished grid stays valid for 10 seconds, one with unknown cells for 5 seconds. A failed sampling is no longer repeated every 20 ticks.
- The same non-blocking height reading applies to the other terrain sampling (paths, building sites, professions, caravans).
- The stall watchdog no longer counts `getChunkBlocking` as a pause, even if the same stack contains `managedBlock` or `pollTask`. The client counts frames with a 3-second threshold instead of game ticks.
- The relief uses a RenderType that Iris supports. `minecraft:pipeline/debug_quads` is gone.

`gradle build` passed at this state. 0.10.8 has not yet been played in the large modpack.

## 0.10.7

The client's stall report now counts only while a world is running. Creating or loading a world is no longer a false alarm.

## 0.10.6

If the game or server freezes for more than 8 seconds, the mod writes the thread stacks to `logs/villagerplus-stall-server.txt` or `logs/villagerplus-stall-client.txt` (at most three reports per launch). A paused single-player server does not count as a stall. In 0.10.6 this was diagnostics only; the cause was fixed in 0.10.8.

## 0.10.5

Three hand-built templates: village house (`haus_dorf`, 14×13), town hall with porch (`rathaus_dorf`, 22×23) and smithy with residence (`schmiede_dorf`, 19×15). The builders place them block by block from the village supply. Whatever the village cannot produce is substituted; if stairs or roof tiles are missing, the stairs fall back to cobblestone stairs. Lava, bells, ladders, pots, carpets, signs and workstations are not built. So far only the village house is used in game, for new tall two-story designs. Running plans keep their shape. Town hall and smithy are in the library.

## 0.10.4

The village toast catches errors and then disables itself. The chat message stays. `-Dvillagerplus.toast=false` disables the toast entirely.

## 0.10.3

No more permanent `/forceload` chunks. The nearest village is held only with short-lived tickets, only for chunks that are already loaded, not while a player is nearby and not when the server is already overloaded (mean tick time above 40 ms). Old forced chunks from 0.10.0 to 0.10.2 are released. `-Dvillagerplus.keptVillages=0` turns off the holding.

## 0.10.2

At most one village per dimension stays loaded, the one closest to the nearest player. All other villages, including old forced chunks, are released and work only while a player is in range. Configurable with `-Dvillagerplus.keptVillages=N` (default 1).

## 0.10.1

- Farmers harvest ripe crops across the whole village and fish when food is short. Food goes through a limited bridge to the village chest ahead of the granary. Scarce midday portions go to the hungry. A pen that cannot be started no longer blocks the field plan.
- Idle residents and free masons work on cobblestone when blueprints are waiting for stone.
- Residents tick without a player nearby (chunk lease with radius 2).

Versions 0.10.1 to 0.10.7 were run on the dev server with test groups, not as a full modpack run.
