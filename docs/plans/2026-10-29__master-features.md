---
type: plan
description: Continuation of all open features of the Villages Repurposed master prompt.
status: active
archived: false
work_status: in-progress
chronicle: docs/chronicles/2026-10-29__master-features
---

# Continue the Master Prompt

Reasons: [Decisions](../chronicles/2026-10-29__master-features.md).

## Progress 30.10.2026 – Headless Profession Cycles

- [x] Lamplighter, brewing stand, guard post/melee, stable extension, freight minecart, gravel quarry, crafting table furnace, locomotive shed, sign text and work pace. Evidence: `VP_AUTOTEST=headless`, 125 checks, `runServer` and `build` BUILD SUCCESSFUL.
- [x] Smith rail material (`SmithRailCraft`) and headless **130 checks** incl. `smithrail` 5. `gradlew build` JDK 25 green.
- [ ] `roadwork`/`roadclear` still red on a flat world; CTOV fixture height/`nextConstruction` profile next. Audit 13/62 complete.

## Result and Limits

On 29.10.2026 the user explicitly authorized continuing all open items. Existing Fabric 26.2 code and saved worlds remain the starting point. Older handoff statements are not proof of function. No Git repository; no publication commissioned. Reference images 2–4 are not available in this chat; until then the visual implementation follows existing in-game assets.

## Work

- [x] Load/unload real caravan freight and fix relation/school errors. Files: `village/Caravan.java`, `job/School.java`, `job/JobManager.java`. Evidence: 311 server assertions and a real seed transfer.
- [x] Filter neighboring residents out of overlapping village radii, invalidate the cache on meeting-point change; single-resident hamlets choose an autonomous build helper. Small villages plan granary → tool chamber → school → kitchen from two residents; the station remains a late phase. Evidence: `DevelopmentChecks` and 387 server checks.
- [x] Add symmetrical flower beds to the plaza; center the map on the extended, stored settlement boundary and separate decoration caches by dimension. Evidence: current CTOV map/plaza screenshot and map client check.
- [ ] Persistent construction-site management, actual build phases and scaffold removal. Files: `build/ConstructionSite.java`, `build/Builder.java`, `village/VillageStats.java`. Check: old cursors unchanged, restart/abort, material preservation.
- [ ] Recipe management instead of exclusively fixed recipe lists. Files: `job/Craft.java`, `job/Workshop.java`, new recipe adapters. Check: tags, quantities, leftover containers, missing ingredients, full target stock.
- [ ] Cook/tavern supply. Files: `job/CookJob.java`, `village/Tavern.java`, `job/JobManager.java`, `job/Routine.java`. Check: real ingredients/portions, detected tavern, no duplication of goods.

## Progress 30.10.2026 – Caravan/Delivery Paths

- [x] The general goods deposit no longer loses its timer on every tick; long village paths use `approachLong`.
- [x] Caravan porters choose a free standing spot, next to the donkey/depot, that the bounded path search can reach; entity unload is null-safe before pickup.
- [x] On `run/vp-k04-fresh17-2026-10-30`: JDK 25 build successful, `/vptest090 run` **847 checks PASS**, clean restart + `/vptest090 verify`: **`VP090 persistence PASS`**. World preserved; `server.properties` reset.
- [ ] Follow a natural CTOV porter route including the matching granary through to caravan departure. Seed goods stay undelivered without a registered food depot.
- [ ] Investigate CTOV cave/mine suffocation cases; afterwards continue to follow the milestone sequence and the wall around original development plus new buildings live.
- [ ] Overall audit: still **8/62 complete, 54/62 partial/open**; do not claim full implementation.

## Progress 30.10.2026 – Safe Mine Movement

- [x] `Mining.teleport` rejects mine cells when the ground, fluids or the complete villager collision box are unsafe. A blocked descent closes the mine; return paths look for a free surface exit.
- [x] JDK 25 `compileJava` with stable temp build: **BUILD SUCCESSFUL**. World check for this change is still pending.
- [ ] The two logged suffocation cases were on the synthetic test area `[12024, 201, 12000]`, not in a CTOV mine. Cause and possible test-setup blocker remain open; the mine collision protection is not a confirmed fix for it.
- [ ] In the fresh CTOV world run, check safe mine movement and the natural porter/caravan flow; afterwards follow the milestone build chain and the wall around old and new buildings.
- [x] Map zoom, pan and reset in `client/BoardScreen.java`; the previous client event check passed. Visual edge/selection check in the client remains open.
- [ ] Complete the profession matrices: agriculture/butcher expansion, smith repairs, charcoal burner, medic, road builder, guard. Files: `job/*`, `village/Staffing.java`. For each profession check demand, actual work, maintenance and alternative task.
- [ ] Overland construction and rail logistics with two-sided resource/resident conditions, real freight/passenger minecarts. Files: `village/Roads.java`, `village/TradeNetwork.java`, new transport management. Check: restart, both endpoints, capacity, freight balance.
- [x] Farm zones bind farmer cluster planning to the complete plot; without a valid, free zone no outside fallback. Housing/work/pasture zones control building sites; PEN blueprints use `pasture`; animal husbandry counts/feeds only animals inside the pasture zone. Farmer secondary work actually builds a field in the tick. Evidence: twelve building zone cases including PEN search, eight farm planning cases, three farmer cycle cases and eight livestock tick cases in the 740 run; restart persistence passed. Visual zone editing and a natural CTOV farm/animal husbandry remain open.
- [x] Transactional building relocation: twelve in-world checks cover a granary on natural ground, a chest furnished afterwards, material/drop balance, registrations, blocked target, failure after partial placement with full rollback, and an overlapping one-block shift. The board client flow/HOME-JOB_SITE observation remains open.
- [ ] Complete village layout/milestones also for small villages, mine access and terrain lifecycle. Files: `build/Builder.java`, `village/Sites.java`, `job/Mining.java`, `village/Demolition.java`. Check in a fresh CTOV village.
- [ ] Complete role look/board block and modpack integration. Files: `client/*`, `mixin/*`, `assets/villagerplus/*`, `Compat.java`. Check with actually installed mod versions; no blanket EMF/ETF approval without a run.

## Additional Requirement from the Continuation

- [x] Exclusively Villages: Repurposed system/overlay messages receive a gold-colored ◆ and a green `DORF` badge (DORF = "VILLAGE"); the client additionally shows an animated HUD card only for the mod prefix. Vanilla/player chat remains unchanged. Dev client visual check: `run/screenshots/2026-10-30_16.32.56.png`.
- [x] G03/G04 convention tag routing on fresh CTOV world `vp-k04-fresh10-2026-10-30`: 824 checks PASS; afterwards restart check `VP090 persistence PASS`. Set the donkey persistence again on load.
- [ ] G04/G05: add concrete Farmer's/Chef's Delight recipes (currently not in the installed test pack), replacement production and compatible mod equipment end-to-end.
- [x] G05 smith repair: `EquipmentMaintenance` repairs damaged equipment from the registered tool chamber at a real anvil and consumes a compatible part. Three repair checks passed in the fresh world run `vp-k04-fresh11-2026-10-30`.
- [x] Caravan restart fixture corrected: in `Checks090` the removed donkey chunk is loaded deliberately and forced until the restart check; the ItemDisplay is checked after the reload directly via the donkey passengers. Fresh CTOV world `vp-k04-fresh12-2026-10-30`: `/vptest090 run` **828 checks PASS**, clean stop/restart plus `/vptest090 verify`: **`VP090 persistence PASS`**. Three complete JDK 25 Gradle builds after the fixture adjustments successful.
- [ ] The fixture chunk stays forced for the checked restart; normal chunk unloading with later reloading is not proven by this run.

## Integrated Implementation 29.10.2026

- [x] Time lock for emergency profession switches with a persistent, standalone attachment; the original profession/level is restored after one day. `StaffingChecks` in the full run.
- [x] Exhausted mine stays marked as an old mine; signed, waxed barricade and map marker tested.
- [x] Map zones, safe building relocation API, profession quotas, major-project approval and trade balance integrated into the board/network flow; targeted persistence/limit checks.
- [x] Unified road route, two-sided rail approval with metallurgy stock, real freight chest minecarts and physical villager passenger transport implemented; route/freight logic and persistence checked.
- [x] Field demand limit/field cluster, animal feed and eight-animal breeding limit, protected natural wood harvest, charcoal furnace batches and stepwise demolition progress added.
- [x] Integrated JDK 25 build and `/vptest090 run` (740 checks) in `run/vp-master-test-2026-10-29`; `/vptest090 verify` after a clean server restart passed. Contains eight farm planning, twelve housing/work zone, one PEN building site, three farmer cycle, eight livestock zone as well as twelve building relocation world checks; the test world is saved; dev processes stopped.

This work does not fulfill the entire master prompt. In particular physical rail trips, successful building relocation in the board client, multi-part profession end-to-end cycles, complete visible build phases in the game, missing roles/skins and unverified modpack integrations remain open.

## Checks and Current Step

Next step: observe the board client flow of the building relocation including the HOME/JOB_SITE change; afterwards, in a 2–3-resident CTOV village, follow the core building sequence and the wall construction over generated structure plus new buildings in practice through to completion. JDK 25 build successful; integrated test world run **740 checks**, afterwards clean restart and persistence check passed. A single CTOV house was finished building after a lunch break. Part of the CTOV generation has several nearby bells; the board per bell shows only the residents actually assigned to it. Full modpack test and Survival real run are pending.

## Addendum 30.10.2026 – Milestone Wait Errors
- [x] Prevents the school from endlessly preempting missing granary/tool chamber materials by aborting the prerequisite steps; a full village chest keeps its storage priority. Five real `Builder.tick` checks in `build/MilestoneWaitingChecks.java`.
- [x] JDK 25 build; `/vptest090 run` 745 checks and restart `verify` passed.
- [ ] Observe a natural 2–3-resident CTOV core cycle through to a practically finished kitchen; materials have so far been provided only for a partial granary test.
- [ ] Secure the city wall incl. CTOV stock/new buildings through radius, chunk-loading and column-integrity tests; physical automatic construction remains unchecked.

## Addendum 30.10.2026 – Work Stand and Trader Model
- [x] Construction-site stand decoupled from the anchor Y: up to 13 reachable, dry, supported standing places at the building; no material/cursor loss if none is reachable. Eight world checks and a real `Builder.tick`.
- [x] Standalone caravan trader as a registered entity with client renderer, own model/texture (hat, coat, pack) and DE/EN names; visual Quickplay client check.
- [x] JDK 25 build, `/vptest090 run`: 779 checks; after a clean restart `/vptest090 verify`: `VP090 persistence PASS`.
- [ ] Observe the actual AI running to the new construction-site stand and tool chamber completion in the CTOV hamlet. Check the new caravan look with Fresh Animations/EMF/ETF.

## Addendum 30.10.2026 – Distance Limit and Overall Backlog
- [x] Root cause of the distant builder location found: vanilla `PathNavigation` limits the villager path search to FOLLOW_RANGE=16. `Builder.workPath` uses a separate bounded PathFinder, `continuePlan` gives the brain short reachable waypoints; no permanent change to nav attributes.
- [x] Four world checks for a 59-block route, a reachable waypoint/vanilla recomputation and unchanged state during the approach. JDK 25 build successful; `/vptest090 run`: **783 checks PASS**.
- [x] Live in the saved CTOV test village: mason Ernst walked without teleport from the origin approx. `[18,70,301]` to the building site `[73,65,275]`; the same house plan advanced cursor 0→222.
- [ ] Continue to check house completion, milestone registration as well as the subsequent granary/tool chamber/school/kitchen sequence and wall end-to-end.
- [x] Test world clock continued, saved, cleanly restarted; `/vptest090 verify`: `VP090 persistence PASS`.
- [x] `docs/master-prompt-audit.md` explicitly marked as a complete 62-ID matrix and ordered by coherent implementation stages. Tally: 8 implemented, 54 partial/open.

## Continuation 30.10.2026 – Caravan Persistence
- [x] `vp-k04-fresh12-2026-10-30`: full run with **828 checks PASS**. After a clean stop/restart, `/vptest090 verify` passed with **`VP090 persistence PASS`**; 7 iron remain in the fixture donkey and exactly one freight marker is restored directly as a donkey passenger.
- [x] `run/server.properties` reset to `level-name=vp-master-test-2026-10-30`; test world `fresh12` is preserved; dev server and client terminated.
- [ ] Master prompt open. Next add G04/G05 recipe/tool replacement and afterwards continue following the natural CTOV milestone sequence including wall construction to a practical end-to-end proof.

## Continuation 30.10.2026 – Real Smith Smelting
- [x] `Workshop` no longer produces stone/glass through a synthetic instant conversion without a furnace.
- [x] `SmithSmelting` uses loaded server `SmeltingRecipe` recipes, a real marked kitchen furnace, a persistent expected output and the storage/target-quantity policy. Foreign inputs and open charcoal-burner batches stay protected; with full storage capacity the output stays in the furnace.
- [x] Clean world `run/vp-k04-fresh14-2026-10-30`: JDK 25 Gradle build, after `time set 5000` `/vptest090 run` **835 checks PASS** and after a clean restart `/vptest090 verify`: `VP090 persistence PASS`. The seven furnace checks cover recipe output, claim isolation, full/partial storage capacity and exact remainder delivery. `server.properties` is reset to `vp-master-test-2026-10-30`; world remains saved; server/client stopped.
- [ ] No running smith has yet been observed actually smelting in a CTOV hamlet. Tool replacement, rail/minecart production, cooking pot recipes and additional smoker/blast furnace recipes remain open.
- [ ] Next step: observe a real furnace run in the village, afterwards add tool replacement via loaded crafting recipes and real tool chamber capacity; then back to the natural milestone sequence and wall.

## Continuation 30.10.2026 – Mod Messages in Chat
- [x] System and overlay messages with the exact `◆ DORF  ›` prefix get their own HUD toast card: golden village mark, dark card, slide-in, short display and fade-out. The original formatted system chat is preserved; normal player/vanilla messages do not pass through the toast.
- [x] Fresh dev client visual check with an own test message and screenshot `run/screenshots/2026-10-30_16.32.56.png`; Gradle build successful.
- [ ] The toast stays labeled in German ("DORF" = "VILLAGE", "NEU" = "NEW"); multilingual support and options for position/duration are not included.

## Continuation 30.10.2026 – Fresh CTOV Diagnosis
- [x] Fresh world `run/vp-k04-fresh15-2026-10-30` started; `#minecraft:village` found `ctov:small/village_taiga_fortified` at `[368, ~, 640]`. The assigned village had 13 counted residents, 0 homeless, including a toolsmith and an armorer.
- [x] Builder log checked live: `Planpruefung: Einwohner 13, obdachlos 0` ("plan check: residents 13, homeless 0") followed by `Kein Bauplatz für 'granary' (halbe Kante 4, Höhe 6)` ("no building site for 'granary' (half edge 4, height 6)"). Without a registered granary, tool chamber, school and kitchen, the real smith could not start a kitchen furnace batch. This is a documented building-site blocker, not a confirmed furnace malfunction.
- [x] New world saved; `server.properties` set back to `vp-master-test-2026-10-30`; dev server and dev client terminated cleanly. No source code change and no build in this diagnosis round.
- [x] Systematic building-site search implemented and verified in the same fresh15 village; `/vptest090 run`: 836 checks PASS, real granary plan created with nine material wishes.

## Continuation 30.10.2026 – Systematic Building-Site Search
- [x] Added a bounded deterministic distributed search for loaded chunks; zones, rectangle check and tolerant search on uneven terrain remain taken into account.
- [x] Fresh CTOV world `run/vp-k04-fresh15-2026-10-30`: `/vptest090 run` passed with **836 checks PASS**. The error `Kein Bauplatz für granary` ("no building site for granary") no longer occurred after the fix; the live builder created a granary plan with nine concrete material types in the fortified taiga village.
- [x] World saved, dev server/client terminated and `run/server.properties` reset to `vp-master-test-2026-10-30`.
- [ ] Follow real deliveries and build completion; afterwards check the sequence through tool chamber/school/kitchen, wall perimeter and a reload proof in the same village. `/vptest090 verify` was not executed in this run.
## Codex Continuation 30.10.2026 – Caravan Storage Categories
- The live run on `vp-k04-fresh15-2026-10-30` reproduced: a porter took the emerald from the first donkey slot while 10 requested spruce planks lay in the next slot. The previous delivery bypassed `StockPolicy` and targeted the village chest across the board.
- `Caravan.porterDelivery` now looks for a deliverable slot, ignores `OTHER` for depot transports and routes building material to the village chest or the warehouse, food to the granary and equipment to the tool chamber. The physical commit uses `StockPolicy.insertInto`; the real stack stays on the donkey until arrival.
- Repeatable test platform: `/vptest090 run` now cleans up only the marked units of the synthetic test bell. Fresh world `run/vp-k04-fresh16-2026-10-30`: JDK 25 build successful, **836 checks PASS**, clean server restart plus `/vptest090 verify`: **`VP090 persistence PASS`**. `server.properties` is reset to `vp-master-test-2026-10-30`; both dev processes are stopped.
- The audit stays at **8/62 implemented, 54/62 partial/open**: emeralds still have no safe village treasury. Next step: implement persistent cash booking, then follow transport and build chain again in a natural CTOV village.

## Codex Continuation 30.10.2026 – Persistent Caravan Treasury
- Emerald delivery uses the same visible porter path to the bell; only on safe arrival are the physical pieces removed from the donkey and booked as the village treasury in the persistent `TradeNetwork.tradeBalance`. The material chest stays emerald-free.
- Future caravans and animal purchases can spend the treasury balance in addition to emeralds present in chests. The balance is shown in the Dorftafel (village board) statistics. `Network.of` does not overwrite an existing balance or treasury during partner repair.
- The first run on a fresh CTOV world found and corrected the slot order: deliverable goods go before the emeralds to the treasury.
- `run/vp-k04-fresh17-2026-10-30`: `/vptest090 run` **842 checks PASS**, clean server restart + `/vptest090 verify`: **`VP090 persistence PASS`**, village treasury afterwards exactly 5 emeralds. Purchase check: 32 gravel are loaded for one really booked emerald.
- World preserved, dev server/client stopped and `server.properties` reset to `vp-master-test-2026-10-30`. Next step: observe the complete trader and porter flow in a naturally generated CTOV village through to the caravan's departure.
- Master audit stays 8/62 complete, 54/62 partial/open; overall goal still incomplete.

## Continuation 30.10.2026 – Mine Completion, fresh18
- [x] The shaft is preserved even on early abort; sealing waits for fully loaded, empty main/side tunnels. No living unit is deliberately enclosed. Blocked standing cells/paths use a checked surface exit.
- [x] Eleven new world checks pass; overall suite on the new CTOV world `vp-k04-fresh18-2026-10-30`: **858 checks PASS**. Complete JDK 25 build and existing restart suite **VP090 persistence PASS**.
- [x] fresh18 added to both development world approvals; six idle follow-up fixtures protected against suffocation. The previously logged coordinates match their spawn row.
- [ ] Observe natural CTOV mine movement/terrain and mine state after reload in a targeted way; eleven synthetic world checks do not replace these proofs. G12 stays partial.
- [ ] Next functional step: real caravan delivery with a registered granary through to departure, afterwards milestone build chain/wall. Overall state 8/62 complete, 54/62 partial/open.

## Continuation 30.10.2026 – fresh20 Persistence
- [x] Pending `/vptest090 verify` on `run/vp-k04-fresh20-2026-10-30` after a real server restart and Quickplay connection: **`VP090 persistence PASS`**. Confirmed are freight donkey (7 iron, one cargo marker), build phase metadata, network links, treasury (5), plaza, shop career and sales.
- [x] JDK 25 `runServer` with stable temp build redirect; `compileJava` was UP-TO-DATE. World saved, dev processes terminated, `run/server.properties` reset to `vp-master-test-2026-10-30`.
- [ ] Next step: observe a natural CTOV caravan delivery with a registered granary through to departure, afterwards check the milestone sequence and wall around old and new buildings. The four Claude scratchpad patches remain to be reviewed before integration.

## Continuation 30.10.2026 – Building Site, House Style and Paths
- [x] Screenshot feedback evaluated: tolerant house/building locations filled up to 3 blocks of slope; copied house paths started at the bed anchor inside; templates were chosen randomly among the nearest six houses; local style scan was not sorted by distance.
- [x] Building-site slope limited to at most one block. The nearest house template is chosen first; local style detection considers the nearest beds.
- [x] The house path starts at the outside stand in front of the front door and searches with a bounded A* for a walkable, load-bearing path around houses, fields, water and steep edges. Local paths clear replaceable plants and accept additional natural ground types.
- [x] Java 25 `compileJava` with stable temp build redirect successful.
- [ ] No in-game/screenshot check after these changes. Next step: check a fresh CTOV settlement live, visually confirm foundation edge and path connection at the finished house; afterwards run a regression for steep building sites and path continuity.

## Continuation 30.10.2026 – R01 Vanilla Thermal Recipes
- [x] Recipe processing extended by device type: the vanilla furnace uses loaded `SMELTING`, the blast furnace `BLASTING`, the smoker `SMOKING` recipes. Smiths process only material/tool outputs; cooks obtain food from the granary and store smoked meals using the storage policy.
- [x] Only thermal devices placed by the village kitchen are marked; batch input/output stay persistently claimed.
- [x] Java 25 `compileJava` successful; the existing `Sites` conflict snapshot was temporarily taken out of the source file list for the compile and afterwards restored unchanged.
- [ ] No game/registry run after this change. R01 stays `partial`: Farmer's/Chef's Delight cooking pot, external mod recipes and remainder/capacity cases still open. Next step: add matching smoker/blast furnace checks and run them in a fresh CTOV world.

## Continuation 30.10.2026 – Paths Through Tree and Water Obstacles
- [x] For tree trunks, `Roads.surface` determines a natural ground root and a nearby tree crown. It clears only connected trunks at least four blocks high; placed wood without these features stays standing.
- [x] Village paths and overland routes now clear detected tree trunks from the surface search. Village paths build a wooden deck with pillars down to solid ground over water; if there is no ground within 24 blocks, the spot stays unchanged.
- [x] Java 25 `compileJava` with stable temp build successful. The existing duplicate `Sites` snapshot was temporarily excluded and renamed back unchanged.
- [x] `/vptest090 roads`: **4 world checks PASS** for a rooted tree trunk, protection of placed wood, a shallow water bridge including pillars and unchanged deep water without reachable ground. Test areas are secured and restored.
- [ ] No natural CTOV visual check. D03/D04/J08 stay `partial`; full 3-block route, ravine geometry, curbs, road builder profession and natural CTOV connection open.

## Continuation 30.10.2026 – Small CTOV House Templates
- `HouseTemplate.capture` no longer discards small houses across the board below 30 blocks: at least 12 building blocks, a bed and a real door suffice. If a jigsaw partial piece does not yield a complete house, the capture tries a bounded connected scan before it takes a generic replacement house.
- Copied houses are accepted only at locations where every block of the lowest foundation layer has load-bearing ground. This prevents overhanging foundation edges, even if the tolerant area search permits a single step.
- JDK 25 `compileJava` successful. `/vptest090 houses`: **4 world checks PASS**; a compact 19-block house is recognized and keeps its spruce palette/roof stairs; a supported area passes, a deliberately unsupported edge is rejected.
- Master audit stays **8/62 complete, 54/62 partial/open**. A screenshot/CTOV visual proof for elevation, style proximity and actual path continuity is missing. Next step: look at the natural CTOV build sequence including door path and foundation edge; afterwards continue with prioritized open master items.

## Continuation 30.10.2026 – Town Hall and Market Center
- The plaza search has been switched from a random ring around the bell to a deterministic search from the determined `Development.center`. It checks every complete footprint, house spacing, ground and entities, moves outward when occupied/unsuitable and stops at a large distance.
- The town hall plan now searches first in the middle of the measured village bounds; existing plan-fit, zone, terrain-tolerance and building-collision checks remain authoritative. With a blocked center, the next suitable place in the inner village area follows.
- Java 25 compile and `/vptest090 civic`: **2 world checks PASS** on the CTOV/Fabric development server. A deliberately blocked central point is bypassed; both placements stay in the inner village area. The synthetic world area was restored after the test.
- G10 stays `partial`: no natural CTOV visual check or evidence on later expansion areas. Audit unchanged **8/62 complete, 54/62 partial/open**. The next step remains to compare natural village center, path connections and development room.

## Continuation 30.10.2026 – Path Continuity Around Houses
- `BuilderPathChecks` extends `/vptest090 roads` with a world check for the existing door-supported A* search: a massive building block must be bypassed, both end areas must be reachable, all path blocks must form a continuous 4-neighbor connection and the building stays unchanged.
- JDK 25 `compileJava` successful. CTOV dev server with `/vptest090 roads`: **9 checks PASS** (4 terrain/water and 5 path checks); `/vptest090 houses`: **4 PASS**; `/vptest090 civic`: **2 PASS**. World was saved, server terminated, `server.properties` reset to `vp-master-test-2026-10-30`; the duplicate `Sites` snapshot is restored.
- The new path check proves only the synthetic obstacle bypass and continuity, not style proximity or natural door connections in grown CTOV. G01/D03/D04 stay partial. Next step: observe the house/village path in the natural CTOV settlement picture, then continue checking the overland 3-block route and wall sequence.

## Continuation 30.10.2026 – Physical Road Repair (J08)
- `Roads.nextRepair` checks, in a bounded way and with a persistent scan cursor, already built overland halves including the three lanes. `RoadBuilderJob` fetches exactly one real, component-faithful building material from a public material chest, carries it to the damaged spot and consumes it only on successful installation with arm swing and sound. Profession tags `job/road_builder`, `job/civil_engineer`, `job/rail_builder` and corresponding foreign profession names use the role; vanilla masons maintain roads as side work.
- Java 25 Gradle `build` successful; `/vptest090 roadwork` on the CTOV dev server: **9 checks PASS** (no stock, real pickup, components, no remote installation, exact consumption, intact road, field protection, private chest, missing gravel substrate). A separate synthetic fixture at `[15000,280,15000]` remains in the dev world; test villager removed. Server saved/stopped, default world and `Sites` conflict snapshot restored. No complete `/vptest090 run`/restart or natural navigation/client proof in this round.
- J08 stays partial: new road/rail/bridge construction still runs automatically; rail/bridge repair and physical two-sided construction including material balance are pending. Next concrete step: connect the existing build-progress routine to worker arrival and real road building materials; afterwards add rail/bridge maintenance. Master audit unchanged 8/62 complete, 54/62 partial/open.


## Continuation 30.10.2026 – Material-Paid New Road Construction
- Overland paving now runs via `Roads.nextConstruction` and `RoadBuilderJob.construct/work` instead of from `Roads.tick`. The worker fetches one real paving block per lane and installs it on arrival. A cursor grows only when all three lanes of the cross-section are actually finished; interruptions recognize finished partial lanes from the world blocks. Existing cursors are preserved. New routes start outside recorded village bounds; `:start` protects the omitted feeder from the repair scan and corrects the progress display.
- Complete Java 25 build successful. CTOV dev server: `/vptest090 roadbuild` **15 checks PASS**, `/vptest090 roadwork` **9 PASS**. Checked: no remote construction by the village tick, missing materials, three real pickup/installation operations, exact consumption of 2 cobblestone + 1 gravel, restart of the volatile work state between lanes, blocked follow-up cross-section, no repair before road start. No real server reload or natural navigation/client proof. Synthetic fixture `[16000,280,16000]` is preserved; test villager removed. Server stopped cleanly, default world and Sites conflict snapshot restored.
- D03/D04/J08 stay partial. Transitional state: physical new construction supports free load-bearing land areas with at most one step across the road. Water, natural tree trunks, solid obstacles and strong cross slopes stop the cursor; the earlier free overland automation is no longer called for them. Next concrete step: take over material-paid bridge decks/pillars as well as visible vegetation clearing into the same work cycle, then check longitudinal profile/bypass and a two-sided CTOV run. Rail construction and lamps stay the previous automation; no full-pack proof. Master audit still 8/62 complete and 54/62 partial/open.


## Continuation 30.10.2026 – Paid Overland Bridges
- `RoadBridge.plan` plans every water lane without a world change: solid foundation at most 24 blocks below the deck, paid spruce logs from bottom to top, then a paid spruce plank as deck. `Roads.nextConstruction` persists the deck height per cursor/lane. All three lanes must still be finished before the cursor grows.
- The work order contains a separate installation location and a dry standing place at deck height on the shore or on a finished deck. The worker stays above the water; every log/plank goes through real pickup and installation on arrival. Before placement, foundation, free standing cells and living entities in the installation block are checked again. Lava, block entities and missing foundation are rejected.
- Complete Java 25 build successful. CTOV dev server: `/vptest090 roadbridge` **41 PASS** (real balance 6 logs + 3 planks for three lanes, no remote construction, dry work, interruption between blocks, deep water/lava); land new construction **15 PASS**, repair **9 PASS**. After a clean real server restart `/vptest090 roadbridgeverify` **8 PASS** for stored deck heights, pillars/deck and cursor. This checks the finished synthetic bridge, not a natural CTOV water crossing or the reload of a half-finished pillar.
- Synthetic test area `[17000,280,17000]` remains in `run/ctov`; test villager removed. Server saved/stopped, default world setting and Sites conflict snapshot restored. No CUA/client visual check and no full-pack proof.
- D03/D04/J08 stay partial (overall master 8/62 complete, 54/62 partial/open). Next concrete step: integrate visible clearing of natural trees/vegetation into the road orders; afterwards add ravine/longitudinal profile, bridge maintenance, a two-sided CTOV run and physical rail work.


- Additionally observed in the natural CTOV run: `Karawanenwache` (IronGolem; "Karawanenwache" = "caravan guard") died on 30.10.2026 20:39:52 at about `[-361.5,63,-209.5]` from suffocation in a wall. Independent of the synthetic bridge area; cause not yet proven. `Caravan.java:306` spawns the guard unchecked at `at.offset(2,0,-1)`. Next, investigate and secure this spawn/collision check, then continue road clearing.


## Continuation 30.10.2026 – Caravan Spawns and Camp Collision
- `CaravanSites` checks the complete collision boxes of trader, both donkeys and IronGolem. Whole ground area, free body height/width, dry cells, loaded chunks and reserved neighbor places are prerequisites. If the old offset is blocked, a suitable neighboring place is searched; without a complete group the visit is postponed. After the actual spawn it is checked again; on failure the started group is aborted and removed.
- `Caravan.put` places no camp blocks into living units or existing block entities and logs only successfully placed blocks. This in particular rules out tent/post blocks in the guard's body. Individual occupied camp cells are skipped; complete camp construction/retry remains separately open.
- Complete Java 25 build successful; `/vptest090 caravansites`: **17 world checks PASS**. Among them trader-fitting two-block ceiling vs. golem, lateral collision, whole ground, water, reserved places, blocked old offset, rejection without complete formation, real golem placement, camp cells in the body, block-entity protection, no chunk reloading as well as public `Caravan.spawn` with a real trader + 2 donkeys + guard and a free collision box of all four.
- Test on the CTOV dev server in a separate synthetic area `[18000,280,18000]` including an arrival ring; the area is preserved, the generated test group removed. World saved, server stopped, default world and Sites conflict snapshot restored. No natural long-term/combat/camp completion proof and no full-pack test. The cause of the earlier suffocation death remains unproven without a direct reproduction; both identified placement gaps are secured.
- K03 stays partial. Overall master still 8/62 complete, 54/62 partial/open. Next concrete step: visible natural tree/vegetation clearing in the paid road cycle, afterwards road profiles and bridge/rail maintenance; check complete camp setup/teardown and guard combat later in a targeted way.


## Continuation 30.10.2026 – Visible Road Clearing
- `RoadClearing.column` integrates natural trunk/vegetation orders before the paid paving. Detection initially requires 4+ upright natural trunks on natural ground, non-persistent leaves and no nearby building parts/block entities/fields. Root position and original height are stored per road cursor/lane in the existing ROADS attachment; already shortened trunks therefore stay workable. Individual plants and natural leaves in the three-block passage also receive dismantling orders; persistent decorative leaves stay protected.
- `RoadBuilderJob` walks to the dry standing place, uses `Anim.breakStep` with all crack stages 0–9 and produces real loot drops only after successful removal. Remainders with a full inventory are preserved as real items. Logs/saplings/sticks are deliverable as profession output. A target change and new protection objects invalidate the dismantling order. The trunk is removed from top to bottom; no global clearing outside the affected corridor column.
- Registry difference found: in the loaded MC 26.2 installation, the DIRT tag used does not contain `grass_block`. Natural grass/mycelium soils are now explicitly taken into account in the clearing as well as in `Woodcutting.isHarvestableTree`/replanting ground check. A first world run failed because of this; afterwards the diagnosis showed a missing stone substructure of the synthetic gravel lane. The test area received load-bearing ground, the protection conditions were preserved.
- Complete Java 25 build successful; CTOV dev server: `/vptest090 roadclear` **25 PASS**, `roadbuild` **15 PASS**, `roadbridge` **41 PASS**, `roadwork` **9 PASS**. The new run checks all crack stages for 5 trunks, exactly 5 inventory drops, resetting of the work state between trunks, transition to ground paving and protection of building timber/containers/decorative leaves/changed targets. Lumberjack detection on grass passed; actual replanting was not executed separately here.
- Separate synthetic area `[19000,280,19000]` remains; test villager/dismantling cracks removed. Server saved/stopped, default world setting and Sites conflict snapshot restored. No real server restart in the middle of a shortened trunk, no natural CTOV clearing/navigation/visual proof and no full-pack test.
- D03/D04/J05/J08 stay partial; overall master 8/62 complete, 54/62 partial/open. Next concrete step: check a shortened trunk after a real reload, afterwards continue with longitudinal slope/bypass as well as bridge/rail maintenance and two-sided natural expansion.


## Continuation 30.10.2026 – Route Profiles, Lumberjack Restart, Caravan Camp and Board Sliders
- `RoadRoute` adds persistent, reversible A* routes around buildings and a bounded longitudinal profile. Construction, repair, progress and caravan waypoint can use the same canonical route; unknown chunks are not reloaded. `/vptest090 roadroute`: **675 checks PASS** (four-neighbor connection, detour, curve cross-section, cross/longitudinal slope, cursor copy, old paid route, chunk protection and retry cooldown).
- Lumberjack work stores tree root, current trunk, visible crack stage, real drop claims and replanting order. `/vptest090 woodcutting`: **6 PASS**; `/vptest090 woodcutprepare`: **9 PASS**, afterwards a real server stop/restart and `/vptest090 woodcutverify`: **10 PASS**. Repeated verification stays idempotent (**2 PASS**). Active work aborts if new village/building-timber protection areas have meanwhile arisen.
- The caravan camp checks the entire level 7×7 area, places only after a complete check, stores a deletion manifest beforehand and removes only unchanged, own blocks; loaded barrels and foreign changes are preserved. `/vptest090 caravansites`: **32 PASS**, including camp codec/world checks and real spawn collision. No natural camp arrival/departure observed.
- `BoardScreen` uses accessible sliders for quotas (keyboard and drag); the apprentice target is adopted as a draft with explicit assignment. Java 25 build successful, but no connected client/visual test; U07 stays partial.
- Complete Java 25 `build` successful (`test` is `NO-SOURCE`). Server stopped cleanly; `run/server.properties` back to `vp-master-test-2026-10-30`, Sites conflict snapshot restored. A complete `/vptest090 run` is omitted since no dev client was connected; no full-pack or natural CTOV visual proof. Master audit stays **8/62 implemented, 54/62 partial/open**.
- Next step: check a stored bypass route in a natural CTOV connection including longitudinal slope and real construction work; afterwards continue with the open infrastructure/milestone cycle. Operate the town hall slider separately in the connected dev client and re-evaluate U07 afterwards.


## Continuation 30.10.2026 – CTOV House Templates, Caravan Roads and Mine Archive
- New residential houses in `Builder.templated` use the nearest real village/CTOV house template instead of the generic architect house type. The existing ground, zone and foundation-support check remains active. No new house construction in the game yet after the change; ground contact, style and door path need a visual proof.
- `Caravan.roadWaypoint` checks active road links, canonical `RoadRoute` geometry and really paved surfaces. It leads caravans in both directions via waypoints and falls back to vanilla's pathfinding with a missing/old route, an unpaved path or unloaded route sections. `/vptest090 caravanroad`: **7 PASS**; covers outbound/return path, unpaved fallback and stored remote path without reloading.
- Completed mines are archived immediately/idempotently in `extra.oldMines` in `Mining.finishMine`; `d.mine` becomes free while barricade, sign and tunnel are preserved. Existing `done` legacy data is migrated before a follow-up construction. The legend on the map now names `▽ Alte Mine` (Old Mine). `/vptest090 minelifecycle`: **6 PASS** for archive, idempotence, sign/barricade, preserved tunnel and attachment/map round trip.
- Final Java 25 build successful. CTOV dev server: `caravanroad` **7 PASS**, `minelifecycle` **6 PASS**, `roadroute` **675 PASS**, `caravansites` **32 PASS**, `woodcutting` **6 PASS**. Server stopped cleanly; default world setting and Sites conflict snapshot restored. No complete `/vptest090 run`, no client visual test and no full-pack proof.
- G13 stays implemented; G01, G12 and D05 stay partial until a natural visual/flow proof exists. Audit stays **8/62 implemented, 54/62 partial/open**. Next concrete step: observe these CTOV new buildings and trader movement in a natural settlement, afterwards work through further open construction/infrastructure items.

