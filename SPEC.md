# SPEC – Master Prompt: Feature Extension for “Villages Repurposed” (`villagers-repurposed-0.9.0.jar`)

Source: user prompt (number-one roadmap). Images `image_2` (Dorftafel mockup) and `image_3`/`image_4` (biome building styles) are **only conceptual examples**; the CTOV assets/palettes generated in-game are authoritative.

Extend the existing Fabric mod **Villages Repurposed** (v0.9.0) modularly with in-depth village management, economy, construction and behavior mechanics. The extensions must interact seamlessly with the installed modpack components (among others *ChoiceTheorem's Overhauled Village*, *Farmer’s Delight Refabricated*, *Chef’s Delight*, *More Villagers: Re-employed*, *Fresh Animations / EMF / ETF*, *Actually 3D Stuff*, *Serene Seasons*, *Towns and Towers*, *Village Taverns*).

## 1. Village generation, layout, spacing & milestone buildings
- **Structured, loosened spacing:** Villages (including CTOV structures) generate in an orderly and organic way. Village houses deliberately stand **somewhat farther apart** (front gardens, open areas, trees, small pastures, realistic plots) instead of densely packed.
- **Mandatory milestones for every village** – even a hamlet with 2–3 residents has the algorithmic building order from the start, working step by step toward specialized core buildings:
  1. **Central storage for building materials (village chest):** accepts only building materials (wood, stone, bricks etc.).
  2. **Granary / food storage:** only raw and cooked food, crops, meat, ingredients.
  3. **Tool & armor chamber (tool shed):** new, used, to-be-repaired tools, weapons, armor pieces.
  4. **Village school:** training site for children (classroom/benches), apprenticeship system, XP for professional qualifications.
  5. **Village kitchen / bakery:** cooking pots, cutting boards, ovens for the cook.
  6. **Train station / locomotive shed (late phase):** terminal for rail connections at an advanced civilization level.
- **Biome palettes & dynamic variety:** mix of different building sizes/types (one-room huts, mid-sized residential houses with extensions, multi-story workshops, functional buildings). Primarily oriented on in-game CTOV assets/palettes.
- **Intelligent centering:** market square and town hall look for central hubs and do not block primary expansion areas.
- **Building correction, demolition & renaturation:** buildings that block paths or are placed incorrectly are physically taken down block by block by villagers; the demolition area is leveled (gravel/dirt) and renaturated if no direct new building follows; reusable materials go into the building material store; new building at a suitable place with free space.
- **Mine lifecycle & “closed” mechanic:** mine entrances terraced; teleportation glitches and pathfinding stalls completely eliminated. **Exhausted mines are never demolished**: entrance barricaded with wooden planks/fences, clearly visible sign `[VERSCHLOSSEN - ERSCHÖPFT]` (closed – exhausted). The Dorftafel/map automatically switch `Mine` to `Alte Mine` (old mine).
- **Dynamic naming & trade diplomacy:** procedural, biome-dependent village name. Unknown neighboring villages are initially undiscovered; only on successful trade contact (caravan or player) is the name permanently revealed and registered in the neighborhood overview.

## 2. City diplomacy, overland roads & railway network
- **Relationship system:** each village has a relationship account with all known neighboring settlements (−100…+100). Stages: *Unbekannt (unknown) → Neutral → Handelspartner (trade partner, +30) → Bündnispartner (ally, +60) → Metropolen-Verbund (metropolis federation, +90)*.
- **Stage 1 overland road (from trade partner):** road builders of **both** villages work toward each other from their village borders: 3-block-wide corridor cleared and leveled; fixed route of biome paving stone, gravel and curbstones; simple wood/stone bridges over rivers/gorges; lamplighters place lanterns/posts at regular intervals against mob spawns. Caravans use this road from then on.
- **Stage 2 railway (from metropolis federation & civilization upgrade):** prerequisite: minimum resident count of both villages, sufficient smithing/mining resources (iron/coal), central storage. Rail builders/stonemasons upgrade the route with rails, powered rails and redstone power; roofed stations at the village ends. Automatic freight trains (chest minecarts) transport harvests, wood, ingots; passenger trains for commuters/market visitors.

## 3. Physical construction & mining process (visible, observable, detailed)
- **Independent crafting of workstations:** If a profession needs a station (furnace/cooking pot, anvil/blast furnace, crafting table, stonecutter), it fetches raw materials from the building material chest, crafts the block autonomously at a crafting table and places it.
- **Observable house construction in 5 phases:** preparation (scaffolding, crafting table on site) → phase 1 foundation excavation & cobblestones → phase 2 load-bearing structure & support beams → phase 3 walls, windows & doors → phase 4 roof truss & roofing → phase 5 interior furnishing, decoration & scaffolding removal.
- **Visible placing & breaking:** placing with arm swing, sound, particles; breaking continuously with visible block cracks (stages 0–9) and tool swing.
- **Village signs & private property:** every house a detailed sign (name, function, resident, status); every resident a **private chest** (`Personal Chest`) that community carriers must not touch.

## 4. New caravan design, donkey logistics & rest stops
- **Independent trader design:** own skin for overland traders (travel clothing, backpack, hat), detached from the standard villager.
- **Donkeys with chests (no more llamas):** caravans travel with loaded `Chested Donkeys` on a leash.
- **Caravan camp & rest stop:** temporary camp at the village edge (tarp, fire pit, hitching posts) with an armed escort guard.
- **Visible loading and unloading:** scarce goods physically carried from the donkey chests into the village chest; overproduction bought in cash with emeralds.

## 5. Profession logic matrices & scaling (control loop need → threshold → work cycle → self-expansion → maintenance)
- **Farmer:** crop beds proportional to residents (`food supply < residents × 5`); if the supply falls, they level/expand new field clusters including water ditches and fences; harvest directly into the food storage.
- **Cook / head chef (Farmer’s Delight / Chef’s Delight):** crafts kitchen blocks themselves, fetches ingredients from the food storage, cooks full meals, supplies taverns and households (mood/efficiency bonuses).
- **Butcher:** feeds animals up to the pen cap (>8); at the limit they build visible stable extensions, slaughter in a controlled way, meat into the food storage.
- **Smith:** repairs tools at the anvil, smelts ores, produces replacement tools for the depot, produces rails/minecarts.
- **Woodcutter:** fells trees in a targeted way with visible breaking progress, collects drops, replants saplings, ignores built-in village woods, lays out orderly plantations after clear-cutting.
- **Stonemason / miner:** mines in the active mine, secures tunnels with support beams/torches, breaks gravel for railway/road substructure.
- **Carrier:** building material → village chest; food → food storage; tools → tool chamber; loads transport minecarts at the station.
- **Road & rail builder:** repairs village paths, builds overland roads to allied villages, lays rail routes including bridges/support pillars.
- **Charcoal burner / kiln worker:** leftover wood in the furnace to charcoal; supplies smithies, kitchens, lighting workers, steam locomotives.
- **Lamplighter:** checks light levels (<1) in the village area and along the overland roads, places torches/lanterns.
- **Medic / healer:** heals injured units with brewing stand potions, supplies exhausted villagers with food.
- **Adventurer / guard / lookout:** staffs watchtowers, patrols road connections, secures rail routes, rings the alarm bell during raids.

## 6. Dorftafel: redesign & management
- **Visual redesign (in-world) & UI** (mockup `image_2`): rustic wooden bar with dynamically rendered village name instead of a rigid banner, uneven notice board look with parchment notes/postings, 3D lanterns/iron fittings on the posts, interactive zoomable and pannable map.
- **UI header:** `[Icon] <Dorfname> (Koordinaten) [Umbenennen ✎]` (village name, coordinates, rename) including manual renaming.
- **Map & construction management:** click on buildings (assignments, demolition, relocation); display of mine, school, storages, construction sites; new zones markable via drag & drop.
- **Diplomacy tab (“Nachbarn & Verträge”, neighbors & treaties):** known partner cities with relationship value, route status (Keine / Straße im Bau / Straße fertig / Bahnstrecke aktiv, i.e. none / road under construction / road finished / rail line active), trade balance; player approves large construction projects.
- **Labor market, apprenticeship & storage filter:** sliders for profession quotas, apprentice assignment in the village school; separate overview and stock regulation for building materials, food, tools; threshold regulation (villagers only deliver up to the target value; surpluses stay in the private chest).

## 7. Dynamic labor market, anti-stuck & daily routines
- **Anti-stuck task:** target block not reachable after 3 attempts → cancel task, cooldown, alternative action.
- **Job timeout:** if a profession cannot be practiced for a long time → temporary switch to the most urgent job.
- **Daily routines & bell signal:** in the morning the bell signals the start of work; during storms/thunderstorms stop outdoor work, indoor crafts or shelter.
- **Survival activation guarantee:** villagers in survival worlds must not stand idle in the village center.

## 8. Valid crafting & recipe subsystem
- **Dynamic Fabric recipe check:** access to the vanilla/Fabric recipe registry so that villagers can produce all items (including rails, minecarts, levers) whose ingredients are present; fixing of wrong NBT/tag conditions.
- **Failover:** if raw materials are permanently missing → biome substitute palette or donkey caravan delivers the goods and places them visibly.

## 9. Marketplace economy, RPG values & visuals
- **Market stalls & B2B trade:** villagers rent stalls, display goods physically, buy necessities from each other with private emeralds.
- **Hunger & mood:** influence work speed; end of workday in the tavern (*Village Taverns*), consuming cooked dishes there.
- **Talk tab (extended dialog):** current task, target coordinates, hunger/mood, private chest contents, missing building materials.
- **Visuals:** visible tools/cargo in hands; custom skins (trader, caravan guards, train driver, adventurer); full animation support (Fresh Animations, EMF, ETF).

## Additional wishes of the user (session 29.10.2026)
- Dorftafel = **live tracker** without manual refreshing.
- **Expedition departure visible** (adventurers leave the village on foot).
- **Watch** building **live**, be able to **cancel** a task/building order.
- Villagers should **distribute themselves in groups** at the village square, not stack up.
- **Shops where the player can buy items.**
- Village should **develop automatically** and **distribute jobs automatically**.
- Make the **village map** (board overview) prettier.
