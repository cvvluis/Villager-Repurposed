## Verification update — 2026-11-24

Strict classification remains **17 implemented / 30 partial / 15 unverified (62 total)**. No requirement is promoted. Code is published on main; this documentation branch intentionally retains its older code. Current evidence below supersedes the historical notes.

Published playtest: [v0.11.2](https://github.com/cvvluis/Villager-Repurposed/releases/tag/v0.11.2) from main `0ee57a8`. [Direct JAR download](https://github.com/cvvluis/Villager-Repurposed/releases/download/v0.11.2/villagers-repurposed-0.11.2.jar), 3,917,028 bytes. SHA-256: `69afb4192c5457f958e7fba1d829aa8409bca5092bc0b6f81561894cc9cc7b69`. [Actions run](https://github.com/cvvluis/Villager-Repurposed/actions/runs/45423597183) built the exact verified source commit after direct cloud upload returned HTTP401; the downloaded release artifact is byte-for-byte identical to the locally verified JAR. No binary was committed. The isolated release-workflow branch is `codex/release-0.11.2`; it is not merged into main.

### Verified changes

- Builders craft missing shapes from already delivered, unreserved site stock at the real loaded crafting table. Direct placement inputs, tag alternatives, capacity and recipe remainders are protected; no remote consumption or other station types. `buildhaul` adds fifteen site-workshop checks.
- Every profession can complete its actual marked construction delivery before taking unrelated work, without advancing a funded placement. Cancelled orders refund real goods. Ordinary public deposits and evening private storage reject construction markers. Six real continuation/retry checks cover delivery/refund and unavailable destinations; morning and evening routine regressions cover the generic deposit and home-storage paths.
- A stalled plan's palette timer accumulates consecutive observed work intervals, excluding gaps longer than 400 ticks. Five new clock checks include overnight gaps and the 3,000-tick threshold. This is an approximation, not exact active-work accounting.
- Existing stone/log loads needed by civic plans receive early hand-in offers. Saved tree work resumes only for civic wood demand (or already earned drops); full inventories and unavailable columns yield. Unconfirmed crown removal first returns to the loaded root stand, preserving pending drops on abort. A live road task retains its fetched material. `stonehands` passes 73 checks, including real scheduler hand-in, marker protection, demand clearing, crown proximity and fetched-log post placement.
- Timber shortage now reads whole loaded crafting recipe batches, shares matching public planks/sticks once, preserves structural-log demand and tracks deficient species through tree selection. Partial stair/fence batches and a stocked-oak/missing-spruce cursor are covered. Fifty stairs require thirteen full batches (78 planks, twenty logs), correcting the former nineteen-log fractional estimate. Early continuation yields once these actual public inputs suffice.
- A real mine load can return before the background stock target when it can fund a live direct cobblestone request. The mine reserve stays intact. Actual item types are checked; variants require a prepared cached above-ground substitutable window, otherwise only cobblestone counts. Unknown/foundation/cleanup/already-funded requests and marked cargo cannot justify the new exception. Existing scalar bulk-delivery APIs are unchanged. Eight new real-container cases cover this policy.
- Mining fixture readiness permits up to 1,200 ticks for cold remote terrain, matching the rail budget. The original 200-tick timeout expired before assertions; the readiness gate and all 73 assertions remain unchanged.

### Verification

Java 25 `./gradlew clean build` passed after the last production change. `constructioncontinuation` passed **37 groups / 3,867 checks**, including all 23 groups requested at takeover. This registered bundle preserves each group's original assertions and readiness gates. Development fixtures are not proof of natural behavior.

Check counts: a01behavior 20, builderchecks 323, builderpaths 88, builderprojects 36, buildhaul 44, caravandelivery 39, caravanr02 49, caravanroad 22, constructionsite 21, craftrecipes 13, demolition 31, firstbuilding 12, langkeys 1936, lightspacing 71, minekit 37, minelifecycle 41, minewalk 35, mining 73, miningcycle 200, planstonesupply 34, railtransport 22, railtransportdispatch 5, railwork 18, recipecrafting 35, roadlighting 27, roads 149, roadwork 34, roles 118, stockpolicy 59, stoneeconomy 20, stonehands 73, strandrescue 27, supply 30, treewalkback 32, wallconstruction 30, woodcuttinggroup 48, woodsupply 15

### Natural observations

Each fresh observation uses seed 4242, the taiga village, the same 100 forced chunks, 30 × 2,400 sprint ticks, no client/player, no agent-injected fixtures or item grants, explicit building orders or manual villager teleports. Runs with different implementations are kept separate. Existing worlds were preserved; continuations used copies.

| Code variant | Fresh runs | First civic completed by the end of the run | Details |
| --- | ---: | ---: | --- |
| Baseline main `265e06b` | 2 | 1/2 | Run 1: granary 269/441, storage 190/266, farm 9/41. Run 2: granary 437/437 finished 56851, storage 103/266. |
| Site workshop + dedicated cargo delivery, before clock/supply changes | 3 | 1/3 | Run 1: granary finished 54084, storage 174/266. Run 2: granary 212/439, storage 224/266. Run 3: granary 269/437, storage 172/266. Farms9/41. |
| Plus inactive-gap clock | 2 | 1/2 | Run 1: granary 269/437, storage 172/266. Run 2: granary finished 57272, storage 171/266. |
| Supply continuation before final review safeguards | 1 | 0/1 | Granary377/437; supply2 interrupted before completing a sprint and supply3 not started, both excluded. |
| First review safeguards, before raw-stock/retry corrections | 3 | 1/3 | Run 1: storage finished 54610, granary 307/437. Run 2: granary 403/437, no civic finish. Run 3: granary 333/437, storage 140/266, house 0/260. |
| Matching processed stock, whole batches, deficient species and unavailable-cargo retry (`f6f2a31`) | 3 | 1/3 | Run 1: granary 370/437, storage 0/266. Run 2: granary 442/442 finished 56114, storage 91/266, farm 9/41. Run 3: granary 393/437, storage 83/266. |
| Plus typed project-funding mine returns (`37be47f`, release candidate) | 3 | 1/3 | Run 1: granary 288/437, storage 194/266, house 0/260. Run 2: granary 204/433, storage 140/266. Run 3: storage finished 54953, granary 293/441. No granary finished in this batch. |

Paired copied-baseline-1 continuations (+24k ticks), **n=1 per variant**: unchanged control storage finished 79155 / granary 357; site-only revision no finish, storage 264 / granary 323; final dedicated-haul revision storage finished 79054 / granary 394. These observations do not isolate a repeatable throughput improvement. Earlier prototypes were discarded and are not evidence for the shipped revision.

Copied final1 +24k continuation, **n=1 per clean variant**: unchanged control granary finished 75305; typed-request treatment finished 75235. The70-tick difference is not a reliable speedup. A prior control run suffered an observer-script parse error after all ten sprints and was excluded from the clean comparison; its world was saved and preserved.

Read-only saved-world inspection found one naturally finished granary containing **one wheat** (pre-clock run 1, n=1). That proves one food arrival, not the complete G04 food-routing contract. First civic reliability, the following tool chamber/milestone sequence, natural roads/rail trips, complete G11 renaturation/replacement and G12 mine safety remain open. No client, visual review, Survival playthrough or full modpack certification. Startup logs contain More Villagers recipe/advancement parse errors and an unavailable authentication-key endpoint.

### Next work

Next, reproduce the loaded-delivery stall on a copy of the latest run 1: eleven spruce stairs remain in public storage, nine marked stairs are carried by a shepherd near 98.8,67,-390.7, while the granary at288/437 still requests stairs. The preserved local source is `run/vp-natural111-nov24-stone1` (ignored evidence, not committed). Trace loaded construction stand selection, path rejection and navigation ownership before changing priorities again. No potential-job-site memory was present on that saved shepherd; do not assume that old pinning bug explains this case. A finished granary later requesting eight glass panes in a repair record also needs an as-built palette check; that cause is not diagnosed. Preserve the strict audit. Client-dependent U items, visuals and full integration require an actual client observation. Do not promote an ID from a passing fixture alone.


# CLOUD-LOG (Branch claude/village-growth-1111)

Documented facts only. "Checked in-world" = dev server run; "scratch" = pure logic in a throwaway JVM; "compiled" = no proof.

## Environment (P0)
- Branch from origin/codex/playtest-2026-11-11 (01db13e); scripts, Sites.walksBackTo present.
- JDK 25.0.5 installed via tools/cloud/setup-env.sh (idempotent, ok). Python 3.13. 4 vCPU / 15 GB.
- ./gradlew build: 1st attempt failed with HTTP 429 from repo.maven.apache.org (rate limit, no host block); 2nd attempt BUILD SUCCESSFUL (49 s).
- **BLOCKER: run/eula.txt is missing** (the EULA is the user's matter, I do not write it). This blocks all dev server runs
  (autotest.sh, natural-run.sh). The P0 reference values (66/140/318/30/22) could NOT be checked.
- Read tools/cloud/*.sh: autotest.sh/setup-env.sh plausible under Linux (setup-env ran); autotest/natural-run untested (EULA).

## P1 - Rescue Without a Route
Changed: job/JobUtil.java (StrandWatch: noteRouteFailure/routeStrandSuspected/markRescued, hooks in approachLong),
job/JobManager.java (rescueStranded: new criterion route, now package-private), job/StrandRescueChecks.java (new fixture),
dev/Checks090.java (group `strandrescue`).
Rule: >= 3 failures (unreachable, route <= 4 nodes) in >= 600 ticks, gap <= 400 ticks, position unchanged +-2; then
once Sites.walksBackTo(position, villageReturn): only if there is NO way back -> teleport to the village street, cooldown 1200 ticks.
Exceptions: expedition (wood), miners (miningUntil+400, insideMine), caravan (carryingFor). villageReturn is computed only in the suspected case.
Checked: scratch (pure state logic) 10 checks PASS; compiled. NOT checked in-world (EULA), fixture `strandrescue` not executed,
bundles housesearch/roles/mining/miningcycle/woodcuttinggroup did not run, no natural run.
Next step: after the EULA, `bash tools/cloud/autotest.sh strandrescue` and housesearch.

## P7 - Audit (strict standard: code + registered in-world check; reading agent, NOT re-checked by me, a hint rather than proof)
### G (agent report, spot checks open; status OK, TEIL = partial)
| ID | Status | Core evidence | Gap |
|---|---|---|---|
| G01 | TEIL | HOUSE_MARGIN=12 (houseplot), JigsawHouseSpacingMixin/g01spacing, HouseYardChecks | no check of a generated settlement; trees only as a sapling in the front yard |
| G02 | OK | Builder.nextSpecial, milestones | DevelopmentChecks only in the monolith path |
| G03 | OK | StockPolicyChecks (stockpolicy) | - |
| G04 | OK | StockPolicyChecks/FoodChecks | raw meat not explicitly checked |
| G05 | OK | StockPolicyChecks, EquipmentMaintenanceChecks | repair material in the central depot, not in the chamber |
| G06 | OK | SchoolChecks (school) | - |
| G07 | OK | KitchenFurnisherChecks | pot/board optional (fallback barrel) |
| G08 | OK | StationShedChecks | positive trigger not checked |
| G09 | TEIL | HouseDesignCatalog, TemplateLibraryChecks | no CTOV asset palette; 3 template families |
| G10 | OK | CivicCenter/Plaza/MarketPlanningChecks | - |
| G11 | TEIL | Demolition/BuildingRelocation + checks | only player-triggered, no autonomous detection |
| G12 | TEIL | MiningSafetyChecks | teleport fallback in Mining.approachMineStep |
| G13 | OK | MineLifecycleChecks | - |
| G14 | TEIL | NetworkDiscoveryChecks | loaded neighbors linked without trade contact; own name without a check |
| G15 | OK | WallConstructionChecks, developmentwall | no wall from 192 blocks; no long-term check |
Note: RailMetropolisChecks has no callers; groups ctov*, tafelgross, templatebuild and others are not in the AutoTest collection arrays.

## P6 - Modpack Contracts (analysis agent, ids partly verified from the jars; none of it checked in-world)
Changed: fabric.mod.json `suggests` extended by farmersdelight, chefsdelight, morevillagers, sereneseasons, t_and_t, village_taverns (metadata only).
Current state: CompatChecks only checks "namespace present". Deepest integration: Farmer's Delight (isModLoaded guards, but direct
vectorwing imports in DelightKitchenCrafting -> class loader risk without the mod unchecked), Village Taverns (soft dependency via registry, TavernChecks),
CTOV (CtovHeadlessChecks, Lithostitched modifier).
Gaps (by impact): (1) move FD types behind an adapter class and check a run without FD; (2) Serene Seasons: no season code, tags
sereneseasons:*_crops and API SeasonHelper.getSeasonState(Level) verified -> farmer season planning possible; (3) More Villagers: jobs
florist/oceanographer/engineer/iceman/enderian/netherian run as carriers; NOTE Compat.counterpart replaces vanilla professions with mod professions
of the same activity (e.g. hunter->butcher, miner->mason): the user's design decision, deliberately NOT changed; (4) Towns and Towers:
village tag contains towns_and_towers:village_*, ocean village (ships) untested, no headless mode; (5) bartender (Village Taverns)
unmapped; (6) EMF/ETF/Fresh Animations: only checkable on the client (overlay renderer E04RoleRenderer), Actually 3D Stuff: no 26.2 release.
### J/U/A/R/E (agent report, unverified)
| ID | Status | Gap |
|---|---|---|
| J01 | OK | - |
| J02 | OK | FD/Chef's paths only in the dev pack |
| J03 | TEIL | slaughter (hurtServer/drops) without a check |
| J04 | OK | - |
| J05 | OK | - |
| J06 | OK | gravel consumption by road/rail unchecked |
| J07 | TEIL | no depot sorting in the code; FreightLoading.work without a check |
| J08 | OK | - |
| J09 | OK | - |
| J10 | OK | LamplighterJob itself unchecked (only Lighting.*) |
| J11 | OK | application to patients not confirmed |
| J12 | TEIL | lookout and Expedition.tick without a check |
| U01 | TEIL | dorftafel_gross without 3D lanterns/pinboard; rendering unchecked |
| U02 | OK | header/button only manual (ClientChecks) |
| U03 | TEIL | only ClientChecks (manual, not registered) |
| U04 | TEIL | assigning (AssignJob) without a server check; click manual |
| U05 | TEIL | drag-and-drop only ClientChecks |
| U06 | OK | tab UI only manual |
| U07 | OK | slider widgets unchecked |
| U08 | OK | UI presentation unchecked |
| A01 | OK | - |
| A02 | OK | only in the collective run |
| A03 | OK | - |
| A04 | TEIL | no Survival/idle proof |
| R01 | TEIL | levers missing in the code; only spot checks |
| R02 | OK | - |
| E01 | OK | - |
| E02 | OK | - |
| E03 | OK | tab presentation only manual |
| E04 | TEIL | skins only 4 roles; EMF/ETF/FA unchecked |
Note: the UI layer depends on ClientChecks (not registered in Checks090/AutoTest, needs a client).
### D/K/B (agent report, unverified)
| ID | Status | Gap |
|---|---|---|
| D01 | OK | - |
| D02 | OK | no own stage "Unbekannt" (Unknown) in stage(), only name obfuscation |
| D03 | TEIL | threshold >=30 never checked as a boundary case (route=true bypasses it); partner builder not proven |
| D04 | OK | only 2 palettes (sand / gravel-cobble) |
| D05 | OK | - |
| D06 | OK | RailMetropolisChecks outdated wrapper |
| D07 | TEIL | Roads.advanceRail runs in the village tick without worker/swing/sound/particles |
| D08 | TEIL | passenger train (passengerDock) without an in-world check; freight only iron proven |
| B01 | TEIL | Workstation only Smith/Cook/Healer/Fletcher; self-procurement unchecked |
| B02 | TEIL | no excavation phase; no end-to-end run of all phases |
| B03 | TEIL | no check for placement sound/particles/swing |
| B04 | TEIL | sign placement unchecked; name/status only on right-click |
| B05 | OK | - |
| K01 | TEIL | CaravanTraderChecks only via ClientChecks (client), not in Checks090 |
| K02 | OK | - |
| K03 | OK | - |
| K04 | OK | surplus sale only in the main run, not as an individual group |

### Count (contradiction 18/62 vs. 30/62)
Overall agent verdict: 39 OK / 23 TEIL / 0 FEHLT (= missing) (G 10 OK, D/K/B 9 OK, J/U/A/R/E 20 OK). This number is too loose: it counts, among others, UI items
(U02/U06/U07/U08/E03) that were only checked manually/on the client, as well as OK with a named gap. Strict = OK without a named gap: **19/62**
(G03, G06, G10, G13, D01, D05, D06, K02, K03, B05, J01, J04, J05, J08, J09, A01, A03, R02, E01), close to the old strict number 18/62.
The 30/62 count is therefore not supported. I did not spot-check the individual evidence; the number remains a hint.
Real code gaps according to the audit: levers (R01), depot sorting (J07), 3D lanterns/pinboard on dorftafel_gross (U01), Survival/idle proof (A04),
passenger train check (D08), autonomous detection of disruptive buildings (G11), teleport fallback in mining (G12).

## Status / Next Steps
Blocker 1: no push (403, Claude GitHub app without write access to cvvluis/Villager-Repurposed; MCP create_branch also 403). Commits exist only locally in branch claude/village-growth-1111.
Blocker 2: run/eula.txt is missing -> no server runs. P0 reference values, the strandrescue group, P2-P5 only make sense with a server (the P2/P3 rejection statistics are
already logged: Mining.create log "Ablehnungen [ungeladen,Hoehe,Boden,Altmine,Bauplan,Vorschau,Eingangsplatz]" (rejections [unloaded,height,ground,old mine,plan,preview,entrance spot]), building-site diagnosis logs).
Next step after approval: `bash tools/cloud/autotest.sh housesearch`, then `strandrescue`, then natural run `natural-run.sh vp-natural111-x 10 2400`.

## Update After EULA (11.11. evening)
- run/eula.txt was written by me on the user's explicit instruction ("Beschreibt die Dateien selbst", i.e. "Write the files yourselves"); consent to the Minecraft EULA therefore lies with the user.
- P0 (dev server, state incl. P1 code, Minecraft 26.2, Java 25.0.5): housesearch PASS: builderpaths 66, houseplot 140, builderchecks 318, supply 30, planstonesupply 22 = reference values, no deviation.
- P1 in-world: `strandrescue` PASS 15 (log: 2 rescues with route=true from the pit, none with mining window/ramp/too few failures).
  Regression (each individually, 1 run each): roles 112, mining 68, miningcycle 199, woodcuttinggroup 48 - all = reference.
  Still open for P1: natural run (count of unreachable route<=4 / stranded), the fixture is synthetic.

## Natural Run vp-natural111-a (seed 4242, without fixtures, forceload as a player substitute; state incl. P1 code)
Run 1: tick 18-24236; run 2 (cont1): 24243-48481. No player check.
| Counter | Run 1 | Run 2 |
|---|---|---|
| Build plan started / completed | 1 (house @166,66,-310, tick 24018) / 0 | 1 (house @48,70,-425, tick 48254) / 0 |
| House plan abort | 0 | 1 ("kein Weg zum Bauplatz" (no path to the building site), step 0, tick 33260) |
| Long path unreachable total / route<=4 | 5 / 0 | 8 / 2 |
| stranded/rescued | 3 (all deep=true, route=false) | 3 (cumulative in the counter; route=true never triggered) |
| Quarry "kein Rueckweg" (no way back) discarded | 0 | 4 |
| "keine Mine anlegbar" (no mine can be created) | 0 | 0 (mine 103,67,-365 was created, mining "von Hand" (by hand)) |
Findings: (1) P1 route rescue was not triggered naturally (route<=4 only 2x, never above the threshold) -> effect proven only via the fixture.
(2) P3: the granary search stays without a hit in both runs (only "fortgesetzt" (continued) lines, no rejection statistics) - agent claude/granary-site.
(3) P4: the house never got its material (21 cobblestone were missing; 3000 ticks of standstill, scaffold fallback); Elsa discards ravine quarries (about 50 blocks north)
one after another, then builds "von Hand" in the mine -> the stone chain is the bottleneck - agent claude/stone-supply. Building sites lie 68-81 blocks from the bell.
(4) Jungle: many miners "keine Spitzhacke" (no pickaxe) (need METAL), otherwise "Bedarf verneint" (need denied).

## P4/P2 Stone Chain (agent claude/stone-supply, merged; diff read by me)
Changed: job/SurfaceStone.java (4 nearest candidates from the worker position, region rejection radius 10 for 24000 ticks on "kein Rueckweg" (no way back) / 3000 on "Weg nicht begehbar" (path not walkable),
3 checks per tick, retry 40 instead of 200 ticks, hand mining 160->100 ticks, log "Stein gebrochen" (stone broken)), job/Mining.java (METAL without a pickaxe: tool chamber/PlanStoneSupply.supplyMiningPickaxe),
job/SurfaceStoneChecks.java (group surfacestone, new), dev/Checks090.java.
Measurement (agent): within radius 64 only 3 reachable exposed stone cells + 10-13 in the ravine (all "kein Rueckweg"); first stone 650-1640 ticks after plan start; the mine never dug in the natural run
(depth 0); residents stay standing 700-1750 ticks on the way to the mine entrance (cause unclear, presumably JobUtil.approach/walk target). Wood (27 planks, scaffold) is also missing.
Re-run by me after the merge (1 run each): surfacestone 13, planstonesupply 22, strandrescue 15, mining 68, miningcycle 199. Agent numbers: minelifecycle 35, woodcuttinggroup 48, roles 112.
Natural run (agent, 30x2400 ticks): plan start 24006, first stone 24716, third 25336, ravine targets discarded 2 instead of 10-13, NO house finished -> P4 NOT solved. Plan starts fluctuate strongly (24k to >48k).

## P3 Granary (agent claude/granary-site, merged)
Cause (agent, counter "Sites.SCAN_REJECT"): in the taiga village ALL candidates already fail in the column check (plan check "geprueft" (checked)=0); the design needs 13x13 with 9 height.
Rejections in the flat run: ~52% real spread >5, ~12% only because of tree trunks; slope is the main reason. Jungle the same.
Rule (not fixture) changed: last-resort pass for civic special buildings: after flat/tolerant a terraced pass (3 blocks of relief, anchor at the highest column, lower columns via fillSupport,
nothing dug away), rooted natural trees clearable (NaturalSitePreparation.inspect READY/CLEARABLE), plan owner clears before building, helpers wait. House plot/12-block/pit rules unchanged.
Files: build/Builder.java, village/Sites.java, village/VillageData.java (optional codec field `terraced`, old states run), build/BuilderPathChecks.java (+13 checks, one cycle check adapted to the new length),
tools/cloud/natural-run.sh (server guard now only per worktree).
Agent natural runs: granary plan started in 5/5 runs (~tick 2400, e.g. 123,67,-349), never completed; 2 aborts "kein Weg zum Bauplatz" (forest). Jungle: no plan.
After the merge by me (1 run each): builderpaths 79, houseplot 140, builderchecks 318, supply 30, planstonesupply 22, milestones 16, constructionsite 21, surfacestone 13, strandrescue 15.
Open: completion of the granary, jungle, effect on school/kitchen/station.

## Natural Run vp-natural111-m1 (fresh, seed 4242, 10x2400 = ~24k ticks, merged state P1+P3+P4)
Build plan started 2 (granary 122,67,-348 @tick 2326; storage 84,68,-336 @2900), completed 0, house plan abort 0, long path unreachable 12 (of which route<=4: 8), stranded 4 (1x deep, 3x route=true), quarry way-back discarded 0, mine errors 0.
P1 triggered naturally: 3 rescues route=true (Ludwig 43, Clara 30, Luise 29 blocks from the village chest; activities wood search for the plan / farmer seed search), none during expedition/mining.
BUT: Ludwig (plan owner of the granary) stood again shortly after at the same spot 165,70,-394 (route=1 unreachable to the village chest and to the wood target 186,69,-402) -> granary "has been standing still for 3000 ticks".
The rescue heals the symptom, not the trap (wood target lies outside the way-back island); target/tree choice should check return paths (like SurfaceStone.walksBackTo) - open.

## P4b Walk Standstill / Mine (agent claude/walk-stall, merged) and P4c Tree Choice (agent claude/tree-walkback, merged)
P4b causes (agent, fixture + measurement): (1) MineMovementMixin/Mining.allowMineMovement discarded the whole movement of a walker with a mine tag at entrance height (gravity component -0.078 -> "below the entrance"),
natural evidence: 700 ticks mv=0.00 with a valid path; now the lock applies only in the tunnel area (inMineArea); terrace instead of ramp model (routeDeviation, Math.round).
(2) Fence post trap (collision height 1.5, pond/fisher pavilions, e.g. 135,68,-343): vanilla path 1 node; new JobUtil.perchStepOff/stepOffPerch (max 2 sideways, 3 down).
Groups (each individually, after the merge by me): minewalk 27 (new), mining 68, miningcycle 199, minelifecycle 35, strandrescue 15, surfacestone 13, roles 112. Agent: a01behavior 20.
Natural (agent, single chains): in 1 of 2 chains mine depth 7 (first digging ~20800 ticks after run start), with the final code not a single spade stroke: miners commute, "holt Stollenmaterial" (fetches tunnel material) (ensureMineSupplies, wait time up to 12000 ticks) is the next blocker.
P4c: Woodcutting.findTree/Builder.selectPlanWoodTree discard trees without a way back (24000 ticks memory, in-memory), TreeWalkbackChecks. Groups after the merge (conflict in Checks090 resolved by hand):
treewalkback 32, woodcuttinggroup 48, buildhaul 20, planstonesupply 22, supply 30, strandrescue 15, minewalk 27, a01behavior 20, builderchecks 318, mining 68.
Both agents independently document: the granary owner sat on a fence post of a pond pavilion (165,70,-394), Sites.walksBackTo reports true there (model runs over the barrel),
vanilla path 1 node -> rescueStranded does not engage; the tree rule never engaged in the natural run. Open: rescue/model for pavilion traps (Sites/JobManager), JobUtil.approachLong WALK_TARGET only after the long-path verdict,
farmer seed search (same type, FarmerJob), mine "holt Stollenmaterial".

## Further Changes by Me (after the agent merges)
- JobManager.rescueStranded: real navigation (Builder.workPath to the street: <=4 nodes, no complete path) beats the block model Sites.walksBackTo. strandrescue-F2: a post with a complete vanilla path
  is left alone (measurement: route true/10 nodes; my first expectation "rescue" was wrong, production code right), a walled-in post is rescued. A real pavilion trap (barrel top, model true / navigation 1 node) NOT rebuilt as a fixture.
- FarmerJob.findSeedGrass: the 3 nearest candidates need a way back (walksBackTo), hollow regions (radius 6, +-2 height) are remembered for 24000 ticks. Reason: Theo/Martha were fetched 2x each from the same hollow (135,65,-352) in natural run m2.
  Fixture finding: the first version (+-6 height) also discarded reachable edge grass -> reduced to +-2. Numbers: strandrescue 19 (15 + F2 x2 + seed grass x2), farmexpand 172.
## Natural Runs on the Final State (each fresh, seed 4242, 2 stages = ~48k ticks; single values, no proof of improvement)
| Run | Plan started (stage 1 / 2) | finished | stranded (route=true / deep) | unreachable route<=4 |
|---|---|---|---|---|
| m2 (before seed grass fix) | granary+storage / (stage 2: 0 plans, 12 rescues, 5x route=true) | 0 | 6 / 12 | 2 / 8 |
| m3 (final state) | granary, storage, granary / storage, farm | 0 | 3 (0x route) / 7 (1x route=true) | 1 / 4 |
Repeated rescue of the same people: m2 Theo 2x, Martha 2x, Heinz 4x (jungle); m3 Hanna 3x (deep=true, taiga).
Result: P4 (finished house without a fixture) NOT reached; in 48k ticks only plan starts (granary 2349, storage 3007, farm 32418), 1 "Stein gebrochen" (stone broken) in m3 stage 2.
Remaining bottlenecks (numbers above and in the agent sections): stone quantity/paths, wood/scaffold, "holt Stollenmaterial" (mine dug in only 1 of 2 agent chains), repeated deep=true rescues (Hanna), jungle without a granary plan.

## P8 / D03 (12.11., me)
Roads.roadLinkActive(Link) replaces 3 inline conditions (route || relation >= 30); behavior unchanged. 2 new checks in NetworkDiscoveryChecks (29/30 boundary, route override, null).
Groups: networkdiscovery 42 (incl. 2 new), roadbuild 53 (1 run each). D03 stays TEIL: no check that the partner village's road builder actually builds its half.

## P5 Stepwise House Search (agent claude/p5-housesearch, merged; diff scope checked by me)
Finding (agent, natural run 38 400 ticks jungle mason plan check): residents 10, beds 17, house need false during the entire run; only granary (2614) and storage (7130) planned. The path needs needHouse (beds < pop or beds==pop with homeless)
AND failure of all random paths (ZoneAwareSites.find/findTolerant/findHouseLot, each up to 4 designs). On seed 4242, house need only arises with population growth (>=17 in earlier runs, tick ~24000+).
Change: Builder.stepHouseSiteSearch/ongoingHouseSiteSearch/dropHouseSiteSearch (testable), cursor (variant, minimum radius, phase seed, next candidate, retryAt, lastUsedAt) in VillageData.Extra `house_site_search` (optional, defaults; old saves load),
restart continues from the cursor (discarded on changed radius/legacy plan/24000 ticks idle), log lines begun/continued (throttled 1200)/found/resumed; the search itself unchanged (budget 96, HOUSE_MARGIN 12).
Dev hooks (only via environment variable, off in normal operation): VP_FORCE_HOUSE_STEP_SEARCH=1, VP_HOUSE_SEARCH_BUDGET=n. New group housesitesearch (36).
Natural (forced via hook): taiga: begun/continued/found, site 97/66/-315 later discarded by prepareHouseLot (gap between site check and lot preparation); jungle: no site at minimum radius 68; real restart: "aus gespeichertem Cursor wiederaufgenommen (Kandidat 456)" (resumed from stored cursor (candidate 456)).
Without the hook NOT triggered naturally (no house need) - the P5 acceptance "check + log in the natural run" is met only with the hook.
After the merge by me (1 run each): housesitesearch 36, houseplot 140, builderchecks 318, builderpaths 79, supply 30, planstonesupply 22, milestones 16, constructionsite 21, strandrescue 19. Agent: houses 12748.

## P6 Runtime Checks (CompatChecks.runStrict, group compat = 41 checks, 1 run, dev pack with all mods except Actually 3D Stuff)
New: professionMapping (all mod professions with Compat.jobKey; expected professions of morevillagers/village_taverns must be registered) and seasonTags (Serene Seasons block tags must be resolvable).
Measured mapping Compat.jobKey: morevillagers:explorer->cartographer, hunter->butcher, miner->mason, woodworker->fletcher; porter (without tag/keyword): enderian, engineer, florist, iceman, netherian, oceanographer,
village_taverns:bartender, chefsdelight:chef/cook (the cook is mapped separately to COOK in JobManager.jobFor, not via jobKey). Serene Seasons tags (members): spring 19, summer 28, autumn 20, winter 11, year_round 8.
Not changed: mapping (the user's design decision), no season behavior in the farmer. Still open: FD adapter (class loader risk without the mod), T&T headless, EMF/ETF client only.

## P2/P4d Mine Kit (agent claude/mine-supplies, merged)
Cause (agent, natural run base2, taiga): the mine kit demanded 1 oak plank per section as a walkway, even for sections without supports/torch; a spruce village cannot craft it -> Rudolf WAITING from tick 33138 (req f/p/w/t=0/0/1/0).
After the 12000-tick fallback, ensureMineSupplies kept running (side effect: leaving the tunnel, depot trip) against the work order -> Jakob commuted at the entrance from tick 51677. Timeline base2: first hand break 27347, first dug block 49827, depth 3 at 51637, then standstill until >=72600.
Change (Mining.java, MineKitChecks.java, Checks090): the walkway never blocks (only supports/beams/torches), one depot visit covers up to 6 sections ahead (only what lies completely in the depot), fallback per section without a resupply path. New group minekit (9).
After the merge by me (1 run each): minekit 9, mining 68, miningcycle 199, minelifecycle 35, minewalk 27, surfacestone 13, planstonesupply 22, strandrescue 19, roles 112. (Agent: mining failed once, afterwards 3/3 green - known flake.)
Natural: no reliable before/after evidence (unforced: no stone need/no mine in 30 sprints; forced: hand digging dominates). Open: torch sections without charcoal wait up to 12000 ticks.
## Pre-existing Failure: Group gravelcycle
FAIL "the next lit section crafts and carries a torch from real public charcoal and stick stock without advancing e..." - verified by me: identical message on the original state 01db13e (own worktree, 1 run) and after all merges. NOT caused by this night, not fixed, cause not investigated.
## gravelcycle Repaired (agent, re-run by me)
Cause (agent, documented): Mining.lightSection(i) = i>0 && i%6==3 (since faca3ca); the fixture in GravelQuarryChecks still used section 12 (and 4/12/20 in the "alternating" check). Production code right, only fixture indices adapted to 9 or 3/9/15.
By me after the merge: gravelcycle 20 (before FAIL, also on 01db13e), mining 3 of 3 runs 68 (the agent saw 2 of 5 failures "three live work-lane leases ... visible=0" in its own worktree; not reproduced by me, flake rate unclear).

## Natural Run vp-natural111-m4 (fresh, seed 4242, 2 stages ~48k ticks, full merge state incl. P5, mine kit, seed way-back; single run)
Stage 1: plan started 2 (granary 122,67,-342 @2335, storage 187,70,-431 @3011), finished 0, stranded 7 (route=true 3, deep 4), unreachable route<=4: 7. Stage 2: plan 1 (granary @24986), finished 0, stranded 0, unreachable<=4: 8, quarry way-back discarded 1, "keine Mine anlegbar" (no mine can be created) 1, "Stein gebrochen" (stone broken) 3, depth>0: 0.
Granary 1 stood still for 3183 ticks (owner Hilde @110,65,-394 'faellt einen Baum' (fells a tree); missing: crafting_table, deepslate_bricks x18, scaffolding x7, chest cobblestone/stone/bricks/charcoal 0), switched to the substitute palette, step 0 aborted
("kein Weg zum Bauplatz ... no free reachable stand after 3 probes", worker 110,65,-391) -> next granary plan @24986. Hilde stands in the pond/pavilion area (cf. P4c traps at 105,65,-393).
Tree choice "strict": 154 of 154 trunks "in village/building" (village bounds cover the entire search area) - the fallback chain loose/extended engages, Hilde fells trees: no wood defect, but the palette (deepslate_bricks x18) is hardly procurable in the taiga village.
Still: no house/building finished in 48k ticks.

## P4e Palette at Plan Start (agent claude/plan-palette, merged)
Cause (agent, natural run palbase): Palette.detect reads the CTOV houses (walls light_blue/lime_terracotta, base deepslate_bricks, windows white_stained_glass_pane) - all four roles not procurable according to Supply.obtainable; the plan starts with the raw palette,
procurability check (Supply.obtainablePalette) only after a caravan visit or after 3000 ticks of standstill. First 3148 ticks (start 2385, fallback ~5533): wish list unchanged (crafting_table 1, deepslate_bricks 18, scaffolding 4, spruce_planks 30), scaffold (bamboo/string) not craftable,
wood: tree 85 blocks away unreachable. Second plan storage stood still for 14614 ticks.
Change: Builder.detectForPlan replaces unprocurable roles at plan start (Supply.lookAlike: deepslate_bricks->stone_bricks, cobbled_deepslate->cobblestone, bricks->stone_bricks ...; terracotta -> biome palette), without a smelting option stone bricks->cobblestone,
variant bit 2 (substitute palette), scaffold discarded immediately if there is neither stock nor recipe (waiveUncraftableScaffolding). Files: build/Supply.java, Palette.java, Builder.java, PaletteFallbackChecks.java (group palettefallback 21), Checks090.
After the merge by me (1 run each): palettefallback 21, builderchecks 318, supply 30, planstonesupply 22, builderpaths 79, houseplot 140, milestones 16, constructionsite 21, housesitesearch 36, strandrescue 19. Agent: houses 12748.
Natural (agent, single runs): palfix2 wish list immediately cobblestone 18 + spruce_planks 30; first stone 2097 ticks after plan start (second plan); NO block placed in all runs; spruce_planks 30 stayed open ~17000 ticks (trees unreachable). Open: wood procurement, terracotta look -> cobblestone.

## Rescue Loops (agent claude/rescue-loops, merged; re-run by me after the merge)
Cause (agent, natural runs loop1-4, diagnostic logs removed): not the task targets, but the rule `deep` (Y < lowest anchor - 8) in JobManager.rescueStranded: rescue at the first look at residents who are only standing on a legitimate terrain step
(jungle: terraces y 85-89, bed y 94; 7 of 8 jungle rescues with target=-: idle stroll/pause/gather). Afterwards the same activity leads down again. Real trap: taiga ravine 151,56,-365 (falls of 5-8 blocks).
Observation run without rescue: deep episodes from 9 to 2350 ticks (outlier 4399 Viktor), residents came up by themselves; walksBackTo alone too strict (Martha/Heinz model=false, came up anyway).
Fix (JobManager, rule only): grace period 3600 ticks of uninterrupted depth (gap <600), task-target exception with cached way back, fall >3.5 blocks within 600 ticks skips the grace period; the log now names the position BEFORE the teleport, depth duration, activity. Route rescue/far/expedition/fisher unchanged.
Groups (by me, 1 run each after the merge): deepstrand 17 (new), strandrescue 19, roles 112, mining 68, a01behavior 20, farmexpand 172, woodcuttinggroup 48, buildhaul 20, miningcycle 199.
Natural (agent, 1 run/stage each): before stage 1 3 deep, stage 2 3 deep (Jakob 3x over 3 stages); after stage 1 1 (route), stage 2 4 (1 deep, 3 route); no resident rescued 2x. Cost: a real pit case without a detected fall now waits up to 3600 ticks (Rosa/Lotte).
Open: fall shortcut never triggered naturally (only fixture); more route=true cases in the final run unexplained; real trap jungle terrace 1505-1509,87,-361 (builder "unreachable route=52").

## Natural Run vp-natural111-m5 (full merge state incl. plan-palette + rescue-loops; stage 1 lost to a foreign server kill, stage "cont1" ran from tick 16 to 24324 = one 24k run)
Plan started 3 (granary @2316 at 118,67,-349, storage @2799, granary @8886 at 115,67,-341; all "startet mit beschaffbarer Palette" (starts with procurable palette), wish list immediately cobblestone#18 + spruce_planks#30, scaffold discarded immediately), finished 0.
granary 1: "Schritt 0 (baut) - Ergebnis false" (step 0 (building) - result false) -> "Schritt 0 wird abgebrochen (Bauplatz nicht erreicht) Stand 118,67,-358" (step 0 is aborted (building site not reached)) within ~400 ticks; granary 2 similar -> agent claude/granary-site (task: reachability of the terraced building site).
stranded 2 (both Matthias, route=true, 136,68,-343 and 159,67,-357, activity 'wartet auf einen freien Pausenplatz' (waits for a free break spot)): pavilion spot (135,68,-343) remains; unreachable total 3 (<=4: 2) - lowest value of all runs (single run, no proof). Quarry 'Stein gebrochen' (stone broken) 3, no mine/depth>0.

## P8 / B04 House Sign (me)
New: village/HousingSignChecks.java (group housesign, 7 checks: bed->home/door, sign in the door wall plane next to the upper door half, farmer symbol 1 + 3 stars, signText "1:1:3", shared house symbol 12 with two residents, exactly 1 sign).
Production error found (not just a check): if a newly moving-in housemate had the smaller UUID, he became the keeper of the shared sign but did not know it (signPos empty) and placed a second sign; the old one stayed with the wrong symbol. 2 of 5 fixture runs failed.
Fix: Housing.updateSign takes over a housemate's sign before placeSign (signPos + block is a village sign). updateSign made package-visible for this. Afterwards housesign 8 of 8 runs PASS 7; roles 112.
Gap note: in the fixture level.canSeeSky reports true on BOTH sides of the wall; "sign outside" is therefore NOT proven (production logic takes the last checked one when two sides are free). B04 stays TEIL (name/status appear only on right-click on the sign).

## P3 Follow-up: Granary Abort at Step 0 (agent claude/granary-site, merged)
Cause (agent, abort lines documented in the natural run): not building site/trees, but the owner's time monitoring in continuePlan (1200 ticks without approach -> rejectPlot + abort, site including cleared trees discarded).
(1) stale path tracking after absence (fetching material/another job): best distance 6.0 stored, worker at 22; 50.1 vs 55.5; 30.6 vs 58.5 -> read as 1200 ticks without progress.
(2) the owner cannot get away from his location: 159,68,-357 (kitchen building, path 1 node) and 137,67,-343 (fenced basin; path nodes=42 reachable, the owner still does not move - cause unclear, storage also affected).
Fix (Builder.java): noteStandVisit resets distance/route/error counters after >200 ticks of absence, own progress stamp for the step-0 abort; handOverStuckOwner (at step 0 not at the stand + Sites.walksBackTo from the plan to the street -> owner pauses 2400 ticks, next resident takes over, max 3 handovers/plan, site stays); diagnosis stand diagnosis/abort line with 11x11 map.
Groups (by me after the merge, 1 run each): builderpaths 86 (79 + 7 new standAbsence fixtures), builderchecks 318, houseplot 140, supply 30, planstonesupply 22, milestones 16, constructionsite 21, housesitesearch 36, palettefallback 21, strandrescue 19, deepstrand 17, housesign 7.
Natural (agent): with absence reset no abort, plans up to step 119/437 and 127/443 (paid blocks placed = first placed blocks at all); with handover 0 granary aborts, Otto takes over (step 137/442). Completed 0 - from step ~119 the plan waits for cobblestone/spruce_planks.
Open: why residents get stuck in the fence basin/kitchen building (bypassed, not fixed); effect on houses/jungle untested.

## Natural Run vp-natural111-m6 (full state incl. granary-site follow-up, 2 stages = 48 437 ticks, single run)
Stage 1: plans started 3 (granary 86,68,-339 @2363; storage 167,66,-312 @2632 and 195,65,-363 @5922), finished 0, stranded 1, unreachable<=4: 2.
Stage 2: no new plan; plan state granary 119/441, storage 75/266 (first placed blocks in a natural run); mine entrance 104,67,-375 dug to depth 11 (Heinz/Elsa, "von Hand" (by hand)); stranded 1; unreachable 21 (<=4: 3); quarry way-back discarded 2.
Bottleneck now: cobblestone. granary "steht seit 15611 Ticks still ... Kiste Bruchstein/Stein/Ziegel/Kohle=0/0/0/22, fehlt cobblestone x18" (has been standing still for 15611 ticks ... chest cobblestone/stone/brick/charcoal=0/0/0/22, missing cobblestone x18); storage is missing cobblestone x20 + spruce_log/planks. Dug stone does not reach the plan -> agent claude/mine-supplies (question: where does the stone go?).

## P6 Shutdown Without Farmer's Delight / Chef's Delight (me, natural run in a throwaway worktree)
Setup: worktree with build.gradle `compileOnly` instead of `implementation` for farmers-delight-refabricated and chefs-delight (mod list: 64 mods, 0 hits for farmersdelight/chefsdelight; ctov, morevillagers, village_taverns, villagerplus loaded).
Result: server starts, fresh world seed 4242, 24 271 ticks (10x2400) without a player: 0x NoClassDefFoundError/ClassNotFoundException/NoSuchMethodError, 0 ERROR/Exception lines (filtered as in natural-run.sh), plans started 2, stranded 0.
Limitation: classes load lazily; cook/kitchen paths (DelightKitchenCrafting, CookJob with cooking pot) were not deliberately triggered in 24k ticks -> "mod missing" is proven for village life, NOT for the complete FD cooking flow. Worktree and world deleted afterwards, nothing committed except this entry.
P6 Towns and Towers: CompatChecks.villageStructures (group compat now 42 checks): 26 towns_and_towers structures in #minecraft:village (registry proof, no world run; no test that bell/palette detection works in a T&T village).

## Where Does the Dug Stone Go? (agent claude/mine-supplies, merged; groups re-run by me)
Correction to m6: "depth 11" was the PLANNED depth; Heinz did not reach the mine (admissionBlocked, rescue, "auf den Wegpunkt gesetzt" (placed on the waypoint), commuting between mine/storage/wood) - Heinz's sequence was NOT individually reproduced by the agent.
Measured (agent, trace, natural run stone4 without fix): 98 cobblestone dug (depth 0-14), only 38 in the public chest (haulers fetched them within 2-150 ticks); miner Benno carried 28-55 cobblestone from tick 30543 and delivered nothing for 22000 ticks:
MasonJob.mine delivers only from 64 items, the plan-owner path in the builder from 12 (PLAN_STONE_BUFFER, depends on supplyMissing). Second finding: deadlock "wartet auf Bruchstein fuer eine sichere Minenterrasse" (waits for cobblestone for a safe mine terrace) (gap repair needs 1 cobblestone, builder path delivered all 12, keep=0): at least 3300 ticks.
Fix (Mining.java, PlanStoneSupply.java, MineKitChecks, Checks090): delivery by plan need (>=12 or the whole shortfall; in the tunnel requestCapacityReturn), 2 cobblestone reserve (COBBLE_RESERVE), patchFloor takes cobblestone, otherwise real dug rock/earth from the inventory. New minekit checks (17).
Groups by me after the merge: minekit 17, mining 68, miningcycle 199, minelifecycle 35, minewalk 27, surfacestone 13, planstonesupply 22, strandrescue 19, roles 112, gravelcycle 20, builderpaths 86.
Natural (agent, fix2, 30 sprints): 54 cobblestone delivered, haulers fetched each within 150 ticks; the plan still stays at step 0 (owner Theo "kommt nicht zum Stand (kein Fortschritt zum Stand 121,67,-334)" (does not reach the stand (no progress to the stand 121,67,-334)), handover 1/3, 2/3) - builder flow, large scatter (in m6 the same plan reached step 119).
Open: the shortfall log still shows cobblestone x48 despite delivery (booking at the building site unclear); builder owner path delivers with keep=0; long pauses of the miners (15000-22000 ticks).

## Natural Run vp-natural111-m7 (final state of all merges, 2 stages = 48 386 ticks, single run)
Plans started 2 (granary 120,67,-348 @2324; storage 84,68,-335 @2862), finished 0, house plan abort 0; stage 1: stranded 2, unreachable 12 (<=4: 5); stage 2: stranded 1, unreachable 8 (<=4: 1). "Stein gebrochen" (stone broken) 3 (stage 1) / 0 (stage 2).
Plan state stays at granary 119/437 and storage 75/266 for >24 000 ticks -> reproducible plateau like m6: cobblestone (granary x18, storage x20) and planks do not arrive.

## Overall State at the End of This Session (13.11.)
Merged into claude/village-growth-1111: P1 rescue (route) + navigation-before-model, quarry (nearest-first, regions), mine movement lock/terrace model/post step-off, mine kit + delivery by need, tree choice with way back, granary pass (terraced) + absence reset + owner handover,
palette at plan start, seed grass way back, deep rescue with grace period, P5 house search (cursor, log, dev hooks), D03 predicate + checks, B04 house sign (duplicate fix), gravelcycle fixture, P6 runtime checks (mapping, Serene Seasons tags, T&T structures), shutdown without FD/CD (natural run).
New/extended check groups: strandrescue 19, surfacestone 13, minewalk 27, minekit 17, treewalkback 32, deepstrand 17, housesitesearch 36, palettefallback 21, housesign 7, compat 42, builderpaths 86, networkdiscovery 42.
Result of natural runs (seed 4242, without a player): plans start early (granary ~tick 2300), first blocks are placed, but in 48k ticks NO building was finished; remaining bottleneck = cobblestone/wood supply for the plans (only ~3 reachable surface stone cells, mine delivers slowly, long pauses), booking of the shortfall at the building site unclear.
Not checked: client/look, full pack, player operation, jungle granary, houses without hook (no house need on seed 4242 in 48k ticks), FD cooking flow without the mod.
Push: consistently 403 (Claude GitHub app without write access) - everything only local.

## P6 Serene Seasons (agent claude/season-compat, merged) + Starter Exception (me)
Agent: compat/SeasonCompat.java (MethodHandles only after isModLoaded("sereneseasons"), no class hard dependency; without the mod/with seasons switched off "no restriction"; API verified with javap; uses the mod rule ModFertility.isCropFertile, fallback registry tags + tropics flag; dev override for checks),
FarmerJob (seasonalSeed, fieldsDormant, CROP step gate, builder compact starter, winter fishing instead of idle), group seasonfarm (SeasonFarmChecks), compat 44. Agent numbers (1 run each): seasonfarm 63, compat 44, farmexpand 172, a01behavior 20, roles 112.
Natural (agent): without Serene Seasons (compileOnly, throwaway worktree) 24 229 ticks without NoClassDefFoundError/ClassNotFoundException/NoSuchMethodError, seasonfarm there 41; with Serene Seasons 10x2400 ticks without NoClassDefFoundError (API resolved, tropical=true, mod fertility rule=true).
Agent finding: new Serene Seasons worlds start late in winter (default starting_sub_season=12, 8 days per sub-season): without an exception the first field would have waited until ~32 game days (~768 000 ticks).
My change: starter exception (FarmerJob.seasonalSeed + CROP gate): if the village has no field yet (farms==0), wheat is sown even out of season when 8 wheat seeds are available (the mod lets out-of-season crops grow slowly by default rather than not at all); a crop in season is still preferred. Further fields stay strict.
Fixture adapted (design change, not error correction): "first field ... only sown in season" -> wheat in every season; winter waiting only without wheat seeds / for further fields. seasonfarm 65. After my change: farmexpand/compat/a01behavior/roles see below.
Not checked: real season changes in a long run; whether the farmer really fishes in winter; temperate case against the real mod rule (only tag matrix); greenhouse glass; builder's own FARM plans.

## P8 Audit Gaps (agent claude/audit-checks, merged; groups by me after the merge, 1 run each)
New groups (synthetic fixtures): freightload 16 (J07 carrier loads a freight minecart from real inventories, preservation, private chests untouched), expedition 27 (J12 state machine, minimum crew 6, isAway, return home, loot in public chest, fallen without loot),
butcherslaughter 13 (J03 selection/strike/drops in inventory, delivery into the granary storage), a04idle 7 (A04 idle: reassignment after >4000 ticks, village fool/experienced never, own reachable break spots ring 4-12), a04lease 2, lamplighter 9 (J10 entry via primary/secondary places exactly 1 torch from public stock).
Regression after the merge: a04survival 5, minetickets 20, roles 112, a01behavior 20, buildhaul 20, housesign 7, strandrescue 19, seasonfarm 65.
Production error found and fixed: village/Anchor.java - the resident lease VILLAGER_WORK had radius 0 (chunk level 33: loaded, but not entity-ticking); without a player, residents never ticked (documented in the fixture over 200 ticks). Now radius 2. Note: MINE_WORK presumably has the same problem (radius 0), not touched.
My natural runs with forceload (entity-ticking) could not show this error. Cost of the larger lease (more loaded chunks) not measured.
Production error NOT fixed (functional design): J03 Craft.run reads only the village chest, the central material chest rejects food (mixin), raw meat goes to the food storage -> the butcher's afternoon cooking never finds raw meat (without a granary he keeps it and retries every 2400 ticks).

## J03 Butcher Smokes From the Granary (agent claude/butcher-cooking, merged) and mealcycle Repaired (me)
Agent: ButcherJob.secondary calls SmithSmelting.cookMeat (new Worker.BUTCHER) after Craft.run: raw meat/raw fish only from the granary into the marked kitchen smoker, fuel only from public stock (no batch without fuel), deposit via StockPolicy.insertVillage back into the granary, only if there is room. Group butchercooking 16 (3 runs stable; negative control: without the ButcherJob change the check fails).
By me after the merge (1 run each): butchercooking 16, butcherslaughter 13, butcher 14, cookdelight 26, stockpolicy 58, smithsmelting 12, roles 112. Unchecked: natural run with a real kitchen/smoker; two butchers or a cook at the same furnace; legacy-world raw meat in the village chest could get lost on write-back (setItem + mixin) (not proven).
mealcycle: FAIL also on the original state 01db13e (verified by me, own worktree). Cause (fixture, production code right): (1) Routine.stopDeliveryApproachIfNeeded clears the walk target only if JobState.target (previous delivery stand) is near the community chest - the fixture set target to null via clearTarget();
(2) the delivery uses distributed standing spots (JobUtil.sharedStandSpot) and is skipped if there is no load-bearing free stand around the chest - the fixture had no ground there, the "aborted route" never began (idleUntil stayed 0).
Fixture correction (HouseholdMealChecks): routine.target = delivery stand; small ground (9x9) around the chest. Afterwards mealcycle PASS 10 (2 runs).
## P4 Cobblestone Economy (agent claude/stone-economy; worktree /tmp/wt-stone2, ports 25566/25576)
Measurement (temporary trace, removed; natural runs seed 4242, 48k ticks each, a day has only 7000 work ticks = 29 %, so 48k ticks are only two full work days):
- Baseline runs (base2/base3/fixa, state before the changes): cobblestone from the village 3 (surface only, by hand 3 stones in ~750 ticks), from the mine 0-3 sections; plan state granary 119/441, storage 75/266 constant; chest cobblestone 0.
- Who works on stone: only the plan owners (supplyMissing) and the masons in MasonJob.mine; in the taiga village that is 2 masons (profession miner/mason), both at the same time owners of a build plan (granary + storage). Day 1 goes to site clearing (owner ~3500 ticks) and hauling the material of the first 119 steps (~1900 ticks); first stone supply only ~tick 8200 (surface stone, 3 stones), mine tunnel only at the end of the day. Both masons fetch wood/planks first only if no tree is free; "wartet auf passendes Bauholz" (waits for suitable building timber) 900-2600 ticks per day (next bottleneck: wood).
- Main loss 1 (mine kit): with a pickaxe (which the owner crafts for the cobblestone wish) the kit applies; section 2 (supports) and 3 (torch, no charcoal in the village) each waited 12000 ticks (game time, ~1.7 work days!), crafting the supports from village planks (competes with the plan for spruce planks) cost 4800 ticks of a work day. Measurement: depth 3 from tick ~4800 to ~25000 (base2), 3 sections in 48k (base3). By hand (no pickaxe) there is no kit.
- Main loss 2 (walking paths): masons stand 600-3300 ticks on "geht zur Mine" (goes to the mine) without moving (door frame of their own house 98,70,-411; terrace y 74-75 above the mine with path=false to all work places; pavilion 135,68,-343). The path cache (builder haul target 94,70,-411 versus mine waypoint) was not resolved further.
- Main loss 3: the mine is closed because of "Hohlraum unter dem Stollen" (cavity below the tunnel) (ravine/cave) at section 5-9 (2 of 6 runs); re-creation then failed 16-20 times ("kein Eingang gefunden" (no entrance found), old-mine lock 24 blocks + build plans).
- No pickaxe problem in the tunnel: a section (3x3x3) is dug with a 24-tick pause anyway, the same by hand or with a pickaxe; 100 ticks/block applies only to the surface quarry (3 reachable cells).
Changes (Mining.java, MineKitChecks, StoneEconomyChecks, Checks090):
1. Kit limit in work time: effort (paths, crafting, waiting) per section max 1800 ticks (step per call max 400, nights do not count), afterwards 12000 ticks timeout for further sections (no renewed kit waiting time per section). Supports/torches are still used if the storage has them; it remains a trade-off against lighting/supports without stock (not evaluated naturally).
2. Walk-to-mine watchdog: no approach within 600 ticks from 8 blocks distance -> resident to the village street (villageReturn), after 3 repetitions the walk is abandoned for 1200 ticks.
3. Free work places without a path (workSlot -1 with a free safe stand): after 900 ticks to the village street.
4. Mine re-creation searches further out after every failure (ring 24 -> up to 48 blocks).
Natural afterwards (final, 48k ticks each, cobblestone from mine+surface): fin1 45 (storage 75->99), fin2 5 (mine closed at section 5 because of a cavity, re-creation failed; earlier version without item 4), fin3 27 (granary 119->140), fin4 21 (granary 119->137), intermediate states fixb 29 (granary 131, storage 89), fixc 39 (granary 149). Before 3 per run. No building finished (wood/planks and further stone missing: granary needs >100 cobblestone).

## P4 Cobblestone Economy: Summary of the Agent Report (claude/stone-economy, merged; groups by me after the merge, 1 run each)
Measured (agent): 48k ticks = only 2 work days of ~7000 work ticks each; in the taiga village only 2 masons, both at the same time plan owners (granary, storage); day 1: site clearing ~3500 ticks + material for steps 0-119 ~1900 ticks, first surface stone ~tick 8200;
biggest lever mine kit: supports (section 2) and torch (section 3, no charcoal in the village) each waited 12000 ticks (~1.7 work days); crafting the supports up to 4800 ticks, competes with the plan for spruce planks; masons stood 600-3300 ticks on "geht zur Mine" (goes to the mine) (house door 98,70,-411: builder haul target and mine waypoint overwrite each other; terrace y74/75 with path=false);
the mine closed in 2 of 6 runs because of "Hohlraum unter dem Stollen" (cavity below the tunnel), re-creation failed 16-20x; a pickaxe in the tunnel is no lever (24 ticks per section break), the 100 ticks/block apply only to the surface quarry.
Change: kit effort per section limited to 1800 work ticks (max 400 per call, nights do not count), afterwards 12000 ticks timeout; path guard (from 8 blocks 600 ticks without approach -> village street, 3 repetitions -> walk abandoned 1200 ticks); lane guard (900 ticks free places without a path); mine re-creation further out (ring 24 to 48).
DECISION FOR THE USER: the shorter kit limit lets sections be dug without supports/torches after the timeout if the storage does not have them (before after 12000 ticks of waiting time, now after 1800 work ticks of effort); safety versus speed not evaluated naturally.
Groups after the merge: stoneeconomy 9 (new), minekit 19, mining 68, miningcycle 199, minelifecycle 35, minewalk 27, surfacestone 13, planstonesupply 22, strandrescue 19, roles 112, gravelcycle 20, builderpaths 86, mealcycle 10. (gravelcycle failed at kit limit 900, green at 1800: little margin.)
Natural (agent, 48k ticks each, seed 4242): before 3 surface stones, 0-3 mine sections; afterwards stone broken 27 (fin3), 21 (fin4) or 45/5/29/39 in intermediate states (scatter 5-45); plan state granary 119->140/137, storage 75->99; NO building finished (granary needs far more than 100 cobblestone); next bottleneck wood/planks (masons wait 900-2600 ticks/day for building timber).

## Playtest Jar (13.11., state 12ff26a)
build/libs/villagers-repurposed-0.9.0.jar (5 333 369 bytes), SHA-256 e5c3da298ea929519d2c67cb5b9eaa3d599b403f656cd9b534f351ed63c32c9a; built with ./gradlew build (BUILD SUCCESSFUL) after the 43-group final run (all PASS, counted individually, see above).
Still contains the dev/*Checks classes (run only in the dev server). Not tried in the client/full pack. Natural final run m8 (48k ticks): no building finished (granary 119/437, storage 75/266).

## Version 0.10.0 (13.11.)
gradle.properties mod_version 0.9.0 -> 0.10.0 (jar name, fabric.mod.json "version" and file name derive from it); README "Neu in 0.10.0" (New in 0.10.0) added, FEATURES.md heading. Old documents (SPEC.md, PLAN.md, tools/*090*.py) deliberately unchanged (historical).
Jar: build/libs/villagers-repurposed-0.10.0.jar, SHA-256 f093eee054ddf384abb0d3d9bb5621a543b64e2c4f0b4d009f596b2058ca265d (state of this commit; code corresponds to the 43-group final run, only version/docs changed).

## Natural Run vp-natural111-m9 (state 80acc73 incl. wood supply, 2 stages = ~48k ticks, single run)
Plans 2 (granary, storage), finished 0; plan state granary 119/440, storage 75/266 (constant). Wish list now ONLY "cobblestone x48" (chest 0) - wood/planks are no longer missing (before spruce_log x13, spruce_planks x15..30): effect of the wood supply visible in the natural run, but single run.
Stone broken 2 / 1 (surface, by hand). Stage 1: "keine Mine anlegbar (create leer)" (no mine can be created (create empty)), rejections [ungeladen,Hoehe,Boden,Altmine,Bauplan,Vorschau,Eingangsplatz]=[0,10,52,0,9,91,38] ([unloaded,height,ground,old mine,plan,preview,entrance spot]), not minable: dirt_path 10, spruce_trapdoor 6, pumpkin 6, cobbled_deepslate 5, deepslate_bricks 4 (village objects in the tunnel area); stage 2: mine created, miners "Lauf MOVING" (run MOVING) 15x, "kam nicht weiter und wurde darauf gesetzt" (got no further and was placed there) 2x.
Wood-supply branch (agent, report lost through a container restart; commit 460c36a checked/merged by me): woodsupply 15, woodcuttinggroup 48, treewalkback 32, buildhaul 20, a01behavior 20, roles 112, farmexpand 172, planstonesupply 22, supply 30, strandrescue 19, cookdelight 26, roadclear 27, mining 68 (1 run each after the merge). Compromise: upper trunk parts start at crack stage 6 (only the trunk base shows all 10 stages), felling pause 16->8 ticks.

## First Finished Building (agent claude/first-building, merged): Goal NOT Reached
Blocker chain (agent, 12 natural runs, seed 4242, 2x24k ticks, n=1 per variant): taiga ~16-21 residents, 2 masons, ~7000 work ticks/day -> 48k ticks = 2 days. granary 437-442 steps (132 free), total need 306 blocks (cobblestone 101, logs 61, planks 44, stairs 92, glass panes 8, door 1); storage 266 steps (81 free), 185 items (cobblestone 42, logs 54, planks 24, stairs 54).
granary: cursor 0/437 over 22-29k ticks (owner does not reach the stand) or 119-131/437, from 119 missing cobblestone x18 + planks x30 (after the wood->stone switch x48), village chest 0-3 cobblestone. storage: 75-121/266, missing cobblestone x20-48. farm (41 steps, only fences+gate): starts only at the end of work day 2 (8 wheat seeds), owner changed profession once.
Discarded: well as the cheapest plan (5x5 with roof, "Kein Bauplatz fuer 'well'" (no building site for 'well')). Evidence without change: masons without their own plan are held by holdForSpecial (build() reports busy, mine()/workshop() never run).
Change (FarmerJob, Builder, JobState): re-check grass if no seeds dropped, wide search radius 80 (at most every 600 ticks), collected seed delivery (from 2 pieces / 300 ticks / field complete), owner crafts fence+gate from real chest stocks. Group firstbuilding 12.
Groups (by me after the merge, 1 run each): firstbuilding 12, farmexpand 172, builderchecks 318, builderpaths 86, supply 30, planstonesupply 22, strandrescue 19, roles 112, seasonfarm 65, woodsupply 15, a01behavior 20.
Natural (agent): seeds until tick ~6000: 2-4 (farm4-6) versus 1-2 until tick 24k in the baseline state (n=1); last run fin1: granary 131/437, storage 121/266 after 48k ticks, 0 finished. Fence crafting never triggered in the natural run (unproven). Open levers: do not hold masons without a plan, path stalls of the farmer collectors, smaller first plan/less stone need, starter threshold 8 seeds.

## More Hands on Cobblestone (agent claude/stone-hands; worktree /tmp/wt-hands)
Measurement (temporary trace every 400 ticks per village, removed): taiga village, 48k ticks, state a1e62e8: both masons stood in the work window without stone work (idle, break spot, waiting at the building-site depot, "geht zur Dorfstrasse und sucht einen Baustand" (goes to the village street and looks for a building stand) = holdForSpecial); 11-17 further adults (hunters, explorers, florists, shepherds, smiths, carriers) reported "hat gerade nichts zu tun" (has nothing to do right now); village chest cobblestone permanently 0.
Change: StoneHands (idle residents help with cobblestone after their own job, max 1 per 16 shortfall, 3 at most, only free mine stands, kit obligation even without a pickaxe, return of every load), need across ALL active plans (PlanStoneSupply.villageCobbleShortage), masons without a mine break surface stone, holdForSpecial no longer holds a planless mason if the milestone is already running, temporary extra mason in Staffing.reassignIdle (only with a mine and shortfall, player's quota unchanged).
Natural (seed 4242, 48k ticks each, taiga): before (n=2) cobblestone picked up 2 / 23, stone-work snapshots 1 / 24 of ~2070; afterwards (n=6 incl. intermediate states) picked up 132 / 148 / 110 / 60 / 15 / 86, snapshots 34 / 48 / 43 / 46 / 61 / 30. Plan state granary before 119-122/437, afterwards 119-241/441 (large scatter, depends on the plan owner's path). Jungle village: never stone (no mine, no surface stone).

## Stone-Hands (agent claude/stone-hands, merged; groups by me after the merge) and Natural Runs m10/m11 (n=2, 48k ticks each)
Agent finding: both masons stood in the work window without stone work (idle, break spot, waiting at the building-site depot, holdForSpecial), up to 11-17 further adults "hat gerade nichts zu tun" (has nothing to do right now); the stone need of a second plan was invisible to others (Mining.needed read only the primary view); jungle: no stone.
Change: job/StoneHands (idle >=200 ticks -> helps with stone; one hand per 16 missing cobblestone, max 3; hand only with a free mine stand; kit rules), PlanStoneSupply.villageCobbleShortage (all active plans), MasonJob (surface stone without a mine), holdForSpecial no longer holds a planless mason, Staffing: temporary extra mason (only if the player's quota > 0 %, slider unchanged), JobManager offers StoneHands as the last task.
Agent measurement: stone-work measuring points before 1/24 (n=2) -> afterwards 34/48/43/46/61/30 (n=6); cobblestone picked up 2/23 -> 132/148/110/60/15/86 (rough). Groups after the merge (by me): stonehands 32, mining 68, miningcycle 199, minelifecycle 35, minewalk 27, minekit 19, stoneeconomy 9, surfacestone 13, planstonesupply 22, builderchecks 318, roles 112, u07quota 27, a01behavior 20, strandrescue 19, firstbuilding 12, woodsupply 15, seasonfarm 65.
My runs (state 4056c01): m10: granary 118,67,-349 @2582, plan state 122 -> 170/441, storage @9361; stone broken 3/0, StoneHands log lines 11/12, mine to depth 9; wish list at the end: cobblestone x9, spruce_log x8, spruce_planks x4. m11: granary 125,67,-348 @1819, plan state 119 -> 191/439, storage @5971; stone broken 0/2, StoneHands 25/17, mine to depth 17; wish list at the end only spruce_door x1 + spruce_log x29.
=> For the first time plan progress beyond step ~120 in both runs (before constant 119/75 in 10 runs). Still NO building finished (granary ~306 blocks total need). In m10 stage 2: "keine Mine anlegbar" (no mine can be created) 10x.

## m11 Extended (state 4056c01, 5 stages = ~145 000 ticks, one village, single run)
Plan state granary 125,67,-348: 119 (24k) -> 191 (48k) -> 227 (72k) -> 269/439 (97k), afterwards constant in stage 4/5; storage 195,65,-363: 75 -> 174 (72k) -> 176/266, afterwards constant. Stage 4/5: 0 broken stones; wish list granary: cobblestone x28 + cobblestone_stairs x20; house plan 55,67,-332 @124301 (house need only arises now; missing cobblestone x48 / spruce_planks x25).
New link in the chain: plan owner "ist hungrig und arbeitet langsam" (is hungry and works slowly) (stage 5, from ~120k ticks): in 145k ticks never a field plan ("Bauplan farm": 0 lines), food of the generated CTOV fields exhausted. Hunger slows work pace (Wellbeing.workQuarters). Still NO building finished.
Reproduction state for agents: world copy /tmp/claude-0/worlds/m11-hunger (tick ~145k, village 15 residents, hungry).

## Food Chain Merged (claude/food-chain, 46dbcbe)
Causes (from the agent report, hunger state m11): farmers did not find the CTOV field (search radius 9, field ~57 blocks away), food could not be deposited anywhere without a granary (material chests reject food), a non-startable pen blocked the field plan (prioritizeInitialPen).
Change: village-wide harvest, pen priority only if startable, capped food bridge (insertFoodBridge), fishing fallback, midday portion for the hungry in case of scarcity, diagnosis /vpfood.
Own run after the merge (1 run each, individually): foodchain 17, farmexpand 172, seasonfarm 65, firstbuilding 12, cookdelight 26, mealcycle 10, butchercooking 16, stockpolicy 58, roles 112, a01behavior 20, builderchecks 318, builderpaths 86 - all PASS.
Natural evidence (agent, n=2 of 48 000 ticks each): hungry 3 -> 0/1; the field plan still does not start (fence wood missing, "no fence kit").
Open / next step: keep a small wood reserve (~8-9 logs) for the starter field in the village chest as long as farms==0 and food shortage (Woodcutting/Builder). Farmer "Ernst" stands permanently at y=316 in the hunger state, cause unclear. Full pack and client untested.

## Strict Recount of the 62 Items (15.11., HEAD be6768a + fix butcherslaughter)
Method: 3 reading agents (G / D,B,K,R / J,U,A,E) read production code AND check bodies per ID and judged A (code + check, wording fully covered), B (gap in the wording), C (client/manual only), D (code missing). I ran all 128 individual groups on HEAD and read 3 A checks (A03WeatherChecks, HealerCareChecks, MarketLifecycleChecks) against it: real behavior assertions.
Result: **A 17, B 35, C 10, D 0** (sum 62). Naturally proven (village without a fixture): **0**.
A: G02 G03 G04 G06 G07 G13 D01 D02 D06 B05 K02 K04 J03 J04 A02 A03 E01. K02/K04 rely partly on the main run (/vptest090 run, needs a dev client, never run in the cloud): strictly without these two 15.
J11 judged A by the agent, set to B by me because HealerCareChecks (group healer) is red.
C (client only): K01 U01-U07 E03 E04 (U02/U04-U07/E03: server part proven).
Earlier reported 19/62 (reading agent, unchecked) and 13/62 (docs audit 30.10.): both not comparable, new and stricter here.
Red groups on HEAD (2 runs each, reproducible; 9 of them also on d7d4044 = before the food chain, so not from the merge): healer, caravanr02, caravanroad, charcoal, craftrecipes, g02 (check "stand": bounded construction search 59 blocks), projectvisibility (readablePause=false), roadbridge, wallconstruction (cost 57 instead of expected, stock 192/135). Causes NOT investigated. Restart pairs (roadbridgeverify, roadclearprepare/verify, woodcutprepare/verify) fail as a single group as expected (they need prepare+restart+verify).
One failure came from the merge: butcherslaughter expected "central chest rejects raw meat without a granary"; the new food bridge deliberately accepts it (up to 48/12 per resident). Check adapted (preservation + bridge takes all 10), group 14 PASS.
Empty groups (0 checks, presumably main run/client only): board, plaza, toast, trader. Not run individually: ctov (needs a CTOV world), headless, verify, performance.
Next step: check the 9 red groups for the cause (fixture red or real production error), then turn the B items with the smallest remainder into A (see master prompt).

## Lag in the Modpack: Village Loading Budget (0.10.2)
See AI-HANDOFF.md (run 16.11.2026). Groups: anchorbudget 6, a04lease 2, a04survival 5, minetickets 20, a04idle 7, roles 112, builderchecks 318, a01behavior 20. Not measured: real tick time in the modpack.
