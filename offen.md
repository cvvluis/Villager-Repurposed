# Master Prompt – Open Items

Verbatim open requirements from `docs/master-prompt.md`. Items that are `implemented` under the strict standard of the recount of 19.11.2026 have been removed (G03, G13, D01, D02, D06, B04, K02, A01, A02, A03, J02, J03, J04, J07, J09, J10, J11). Strict status: **17 implemented, 30 partial, 0 missing, 15 unverified** (loose: 53/62). Details, evidence and the reconciliation with the older 8/13/18/30 counts are in [`master-prompt-audit.md`](master-prompt-audit.md). `partial` = a clause is missing or only parts are proven; `unverified` = code and headless checks exist, a client/visual/Survival observation is still required.

## 1. Generation, Buildings, Mine and Identity
- G01 [partial]: Orderly organic village/CTOV generation with larger spacing between houses, front gardens, trees, pastures and plots. Next: Natural run in which several houses are finished; screenshot check of spacing, gardens, trees, pastures.
- G02 [partial]: Every village, even with 2–3 residents, has a step-by-step infrastructure assignment from the start. Next: Fix the cobblestone/plank supply to the plan (see R02), then repeat the natural 72k-tick run until granary and tool chamber are finished.
- G04 [partial]: The granary holds raw/cooked food, crops, meat and ingredients. Next: Finish a granary naturally and observe food arriving in it.
- G05 [partial]: Separate tool/weapon/armor chamber, including repair goods. Next: Natural run that finishes the tool chamber and stocks it.
- G06 [partial]: School with classroom/benches, teaching of children and profession XP. Next: Natural run with a finished school; teaching after a reload.
- G07 [partial]: Kitchen/bakery with cooking pot, cutting board and ovens. Next: Natural run that reaches the kitchen milestone.
- G08 [partial]: Later a train station/locomotive shed as a rail terminal. Next: Reach the station milestone in a grown village and connect two real villages.
- G09 [partial]: Mix of small/medium/multi-story houses and functional buildings; palettes are based on the CTOV assets actually installed. Next: Finish houses in several CTOV biomes and compare visually.
- G10 [partial]: Market and town hall are central and leave expansion areas free; decorate the village center more richly. Next: Client screenshot of market square/town hall in a natural CTOV village.
- G11 [partial]: Physically dismantle disruptive buildings, return the material, level/renaturalize the area and, where appropriate, rebuild suitably. Next: Autonomous detection of disruptive buildings plus rebuild step; board click flow in a client.
- G12 [partial]: Terraced mine entrances, safe navigation without teleportation glitches or getting stuck. Next: Remove or bound the teleport fallback; natural run with zero stranded miners.
- G14 [partial]: Procedural biome-dependent village names; unknown neighbors are permanently discovered only after successful trade contact. Next: Decide: require a completed trade for discovery (change `physicalContact`) or document contact as the intended rule.
- G15 [partial]: Villages keep developing continuously and according to need without a player build assignment; a wall encloses the original buildings and new buildings. Next: Natural run long enough to finish buildings and start the wall; check after reload.

## 2. Diplomacy, Roads and Railway
- D03 [partial]: From Trade Partner on, road builders of both villages build toward each other, clear 3 blocks of width and level the ground. Next: Re-run the road groups on main; natural two-village run at relation >= 30.
- D04 [partial]: Fixed biome-appropriate cobblestone/gravel road with curbs, bridges over rivers/ravines and regular lamps. Next: Green re-run of the lighting groups; ravine profile; natural-biome visual check.
- D05 [partial]: Caravans use completed roads. Next: Observe a natural caravan on a finished road.
- D07 [partial]: Visible construction from real resources: rails, powered rails, redstone supply and roofed stations at both ends. Next: Make new rail construction a worker job; natural two-village run.
- D08 [partial]: Automatic real chest minecarts transport harvest/wood/ingots; passenger trains carry commuters/market visitors. Next: Green re-run of `railtransportdispatch`; freight + passenger trip on a built route including restart.

## 3. Physical Building and Private Ownership
- B01 [partial]: Professions procure raw materials, craft the required work blocks at a crafting table and place them themselves. Next: Add the same proof for the remaining professions' work blocks.
- B02 [partial]: Visible preparation with scaffold/crafting table, foundation excavation/cobblestone, framework/supports, walls/windows/doors, roof, interior furnishing/decoration/scaffold removal. Next: Natural completion of one building and a visual check of all phases.
- B03 [unverified]: Block placement shows tool/arm swing, sound and particles; dismantling shows continuous cracks 0–9 and tool swing. Next: Client check of swing, sounds, particles and cracks during a build and a demolition.
- B05 [partial]: Every resident has a private chest that community carriers do not take from. Next: Systematic check across all chest scans (e.g. via a test hook on `Container` access).

## 4. Caravans
- K01 [unverified]: Own trader skin with travel clothing, backpack, hat. Next: Client look with and without Fresh Animations/EMF/ETF.
- K03 [partial]: Temporary edge camp with tent, fire, stakes and an armed guard. Next: Observe a natural camp including guard combat.
- K04 [unverified]: Visibly carry real goods from the donkey chest to the village chest; buy surplus for actual emeralds. Next: Observe a natural caravan delivering to a real granary; client look of the carried item.

## 5. Professions: Demand, Threshold, Work Cycle, Expansion, Maintenance
- J01 [partial]: When food < residents × 5, the farmer independently expands fields including water/fences; harvest goes to the food store. Next: Natural run that starts and harvests a field.
- J05 [unverified]: Lumberjack visibly fells real trees, collects drops, replants, protects built-in wood and creates plantations after clear-cutting. Next: Client observation of felling and plantation.
- J06 [partial]: Stonemason/miner works in the active mine with supports/torches and produces gravel for roads/railway. Next: Follow dug gravel into a road construction in a natural run.
- J08 [partial]: Road/rail builder repairs and builds roads, rails, bridges and pillars. Next: Worker-driven rail construction; green road groups; natural two-sided run.
- J12 [partial]: Guard/lookout/adventurer staffs towers, patrols roads/railway, fights mobs and rings the alarm during raids. Next: Trigger a raid at the bell; patrol check along a road.

## 6. Village Board (Dorftafel) and Administration
- U01 [unverified]: In-world block with rustic wooden frame, dynamic village name, uneven pinboard/parchments and 3D lanterns/iron fittings. Next: Client screenshot comparison.
- U02 [unverified]: Header with village name/coordinates and manual renaming. Next: Client check of header and rename dialog.
- U03 [unverified]: Interactive map, operable zoomed/panned and centered on the complete village. Next: Client check at smallest GUI scale.
- U04 [unverified]: Click buildings, assign, demolish and move them; show mine/school/warehouse/construction sites. Next: Board relocation and demolition via the client.
- U05 [unverified]: Mark zones on the map by drag and drop and save them. Next: Client check of drag-and-drop zones.
- U06 [unverified]: Neighbors/contracts tab with relation, road/rail status and trade balance; the player approves major projects. Next: Client check of tab, balance and two-sided approval.
- U07 [unverified]: Sliders for profession quotas and child apprentice assignment. Next: Client check of the sliders with mouse and keyboard.
- U08 [unverified]: Separate storage overviews/filters and target quantities; villagers deliver only up to the target, surplus stays private. Next: Client check of the storage tab and filters.

## 7. Labor Market and Daily Life
- A04 [unverified]: Activation also in Survival; residents do not stand idle in the center permanently. Next: Survival session with a player and an idle-time measurement.

## 8. Crafting and Procurement
- R01 [partial]: Loaded vanilla/Fabric recipe management for all available craftable items including rails/minecarts/levers, correct ingredients/tags/components and leftover containers. Next: Generic sweep over the recipe registry; lever case.
- R02 [partial]: For permanently missing raw materials, a biome-appropriate substitute palette or caravan procurement with visible delivery. Next: Find why the fallback does not fire in the natural run; observe a visible caravan delivery into a plan.

## 9. Market, RPG and Presentation
- E01 [unverified]: Rented market stalls with visible goods; villagers buy food/tools/breeding stock from one another out of their private emerald holdings. Next: Client look at stalls with goods; natural purchase between residents.
- E02 [partial]: Hunger/mood directly affect work speed; end-of-work-day gathering in a real Village Taverns tavern with cooked meals. Next: World with a real tavern and an evening gathering observation.
- E03 [unverified]: Talk tab shows task, target coordinates, hunger/mood, private chest contents and missing building material. Next: Client check of the panel with long inventory and empty chest.
- E04 [partial]: Tools/freight visible in hands; own skins for traders/guards/train drivers/adventurers, compatible with Fresh Animations/EMF/ETF. Next: Client check with the installed EMF/ETF versions.

## Modpack Contracts

Integrations concern CTOV, Farmer's Delight Refabricated, Chef's Delight, More Villagers: Re-employed, Fresh Animations/EMF/ETF, Actually 3D Stuff, Serene Seasons, Towns and Towers and Village Taverns. Existing registry detection alone does not count as a complete in-game compatibility test. The current dev installation contains only CTOV, Lithostitched and Cloth Config.
