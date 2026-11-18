# Villagers: Repurposed 0.10.0 – complete feature list

Fabric mod for Minecraft 26.2 (server and client; the Info button needs the mod on the player's side too). Status: `AI-HANDOFF.md` records
what was observed in the test run and what exists only in the code.

## 1. Villagers in everyday life
- **Custom chat messages**: Villages: Repurposed uses a gold-green `◆ DORF ›` marker and its own animated toast card in the HUD (fade in, short dwell, fade out). The formatted message stays in the system chat history; ordinary player and vanilla chat remain unchanged. Visually checked in the dev client.
- German **names** (unique; name tags and names from other mods are kept)
- **Fixed home**: the bed that the vanilla AI assigns is saved; **sign next to the front door** (name, profession, level; with housemates “Haus von …” (house of …))
- **Private chest** per villager in the house; opening/destroying by players costs standing and is passed on by gossip
- **Fixed daily schedule**: morning (breakfast, deliver, chat) → work → break (conversations) → work → midday at the village square → work → break → work → end of workday (dinner, store belongings) → night (sleep)
- **Eating and hunger**, **conversations** with gossip exchange, wages from the village treasury
- **Wellbeing** (satiety, energy, social, health, mood) with real effect: work speed, prices (discount/surcharge), trade refusal when miserable
- **Memory**: reputation/gossip decays 4× slower; remembers house, door, sign, chests, workstations, completed work, gifts per player
- **Gifts** (sneak + right-click with emerald, diamond, bread, golden carrot, cake) permanently improve reputation; empty hand shows short info
- **Skills** with 6 levels (Lehrling … Legende, i.e. apprentice … legend): thresholds 150/500/1200/2600/5000 tasks, per level +12 % speed and extra yields; level-up message with particles
- **Healing during sleep**
- **Children**: grow up 3× slower, go to school during the day and gain experience for later there
- **Profession backfill**: if the last mason, fletcher or farmer drops out, an unemployed or surplus villager takes over

## 2. Professions and their work
- **Farmer**: harvests and replants, extra harvest by skill, bakes bread, lays out new fields
- **Shepherd**: shears sheep, builds beds
- **Butcher**: slaughters surplus, collects drops, cooks meat/fish
- **Fisherman**: fishes at the water
- **Armorer/Weaponsmith/Toolsmith**: repairs iron golems; maintains damaged depot tools at the anvil of the registered tool chamber with a material compatible according to the `ItemStack` repair data; smelts ore and works in the mine (metal). Replacement tools and mod equipment are not yet fully covered.
- **Cleric**: heals villagers, golems and players
- **Mason**: builds houses, wells, town hall, wall, pen, school, storage; places torches; works in the mine (stone)
- **Fletcher**: arrows, sticks, **fells real trees** and replants saplings
- **Leatherworker** (books), **Cartographer** (paper), **Librarian** (bookshelves)
- **Carriers** (unemployed/nitwits): bring items lying around to the village chest
- **Workshop**: planks, stairs, slabs, walls, fences, doors, glass panes, stone bricks, stone, glass from raw materials (also for mod blocks following vanilla naming)
- **Mine**: away from the village, entrance with sign and lantern, stairs down; **real mining** of stone, dirt, gravel, ores together with ore veins; tunnels with varying width/height, **side tunnels, timber supports with ceiling beams, torches**; protection against water, lava, spawners, caves below and falling blocks
- **Balanced supplies**: woodcutters and miners procure what is scarce relative to food/wood/stone/metal (no fixed limits)

## 3. Village and buildings
- **Village detection via the bell** (vanilla and world-mod villages, tested with Overhauled Villages)
- **Village center** with **village chest** (double chest), four lantern posts; an existing double chest is adopted; hilly terrain is leveled
- **Houses on demand** (only on population growth or homeless residents): copying one of the 6 nearest houses block by block (shape, roof, materials), randomly **mirrored and rotated**; standard house in village style as fallback
- **Village style detection** (floor, wall, roof, stairs, door, fence)
- **Well**, **fields** (7×7 → 9×9 → 11×11, up to 6), **animal pen** (9×9 with gate), **town hall**, **school**, **storage shed**
- **Town wall** (only at a suitable village size): 5 blocks high with battlements, lanterns, corner towers and four gates with archway
- **Wealth structures** by village wealth (lantern posts → market stall → monument → fountain → festival ground)
- **Paths** between house and bell
- **Storage**: built when the village chest is full, **expansion by extensions**; masons fetch material from storage; nothing is thrown away
- **Material chain**: wish list → workshop/woodcutter/mine → trader; unobtainable material is bought via the caravan, if necessary **substitute material**
- **Permanently loaded**: village (7×7 chunks) and active mine stay active via force-load, so that everything keeps running even without players

## 4. Economy, trade, visitors
- **Trade caravan** (wandering trader) with “imaginary village”: buys surplus, sells wish list, seeds, saplings, torches, delivers animals for the pen; pays at most 6 emeralds per visit
- **Emeralds stay scarce**: wages from the village treasury, guests rarely bring one
- **Guests from foreign villages** (single or travel party, attire of other biomes): guest gifts, tradable, sometimes settle
- **Animal husbandry**: feeding, breeding, population care (butcher/shepherd)
- **Village wealth** as a score (levels 0–5)
- **Expeditions**: 1–3 brave villagers explore ruins, caves or fortresses; health and skill decide whether they return injured or not at all; **loot** goes to the village chest/storage

## 5. Dorftafel (village board; sign next to the village chest)
- **Übersicht** (overview): colored **map** (4×4-block cells) with development, fields, water, paths, forest, bell/chest/board/school/storage/pen/mine and the villagers, plus **bar charts** (residents, mood, wealth, food/wood/stone/metal)
- **Details**: all numbers (residents, professions, structures, wealth, village chest, storage, animals, construction, mine, wish list, next caravan)
- **Expeditionen** (expeditions): status, health of the members, reports, statistics
- Tabs clickable; sneak + right-click opens the expeditions directly

## 6. Trade menu
- Button **“Info”** shows the profile card on the left (name, profession/level, activity, mood, satiety, energy, social, health, house, possessions, reputation, gifts, work, conversations, price effect), live every 1 s
- **Mood prices** (discount/surcharge), trade refusal when exhausted

## 7. Animations and effects
- Matching item in hand (hoe, shears, pickaxe, axe, fishing rod, sword, book, food, building material …), arm swing
- Particles and sounds: block breaking, fishing, hearts, eating, conversations, level-up
- (Custom body poses such as sitting/hammering do not exist – that would need client models)

## 8. Commands
- `/villagerplus tafel [uebersicht|details|expeditionen]` – Dorftafel
- `/villagerplus status` – villagers and village nearby
- `/villagerplus perf` – time the mod needs per tick

## 9. Compatibility
- Works with villages from world generator mods (tested: Overhauled Villages 4.1.0 + Lithostitched + Cloth Config)
- **Subclasses of Villager** from other mods are managed as well; names from other mods are kept
- **Foreign professions**: datapack tags `villagerplus:job/<farmer|shepherd|butcher|smith|cleric|mason|fisherman|fletcher|leatherworker|cartographer|librarian>` or automatic detection via keywords in the profession name; otherwise carrier
- Entity tag `villagerplus:ignored` excludes villager types
- Wood/wool from other mods recognized as obtainable via vanilla block tags
- Mixins with `@WrapOperation` and `require = 0` (no crash if another mod changes the same spot)

## 10. Performance
- Time budget per tick (2 ms) with continuation in the next tick; cadence automatically becomes 2×/4× less frequent under high server load
- Caches for village search, emerald counting, supply balance and storage chests
- Tree search reads only the surface per column instead of ~59,000 blocks; material check, sign query and mine path computation throttled or without object creation
- Measurement: avg 21–36 µs per tick with 33–155 villagers (see `/villagerplus perf`)

## 11. Settings (`Config.java`)
Cadence, day phases, hunger, conversations, wages, building costs, fields, pen, town hall/wall sizes, caravan interval, expeditions, growth factor of children, school/storage resident count, force-load, time budget.

## 12. New in 0.7.2: building generator and maintenance
- **Architect**: houses, school and storage are freshly designed per village from its real building materials (wall, half-timbering, plinth, roof, windows, fence) – never as a copy. Varied: size, wall height 3–5, roof direction, overhang, half-timbering, windows (symmetrical), door, canopy with lanterns, chimney with smoke, side extension, yard decoration (flower bed, barrel + hay, bench, wood pile), shutters, flower pots, path, path lantern, carpet + table inside, foundation on slopes.
- **Spacing**: New buildings are no longer built into existing ones (2 blocks minimum distance).
- **Maintenance**: masons repair missing blocks on self-built buildings and on the town wall; destroyed buildings (> 50 % missing) are not rebuilt unasked.
- **Mines clean up**: entrance markers (post, lantern, sign) of finished mines disappear; new mine ≥ 24 blocks from old ones.

## 0.9.0 extensions
Trade network and village maps, neighbors menu, arrival with two llamas and player trade, relationships and greeting mood, village shrine/plaza, five blocks of building spacing, market careers and private shops with real purchases, succession, village journal with categories/resident filter/trade ranking, custom two-line nameplates, straight route paths, larger village counting and a reliable management cadence. Details and limits are in the README and the test report.
