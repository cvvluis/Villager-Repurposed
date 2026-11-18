# Villagers: Repurposed 0.10.0 (Playtest 3)

Minecraft 26.2, Fabric, Java 25. Jar: `villagers-repurposed-0.10.0.jar`
SHA-256: `f093eee054ddf384abb0d3d9bb5621a543b64e2c4f0b4d009f596b2058ca265d`
Source: branch `claude/village-growth-1111` (built on `codex/playtest-2026-11-11`).

**Important:** Tested on the development server only (43 check groups green, run individually, plus natural runs without a player).
**Not** tried in the client or a full modpack. In no natural run (seed 4242, 48,000 ticks) was a building finished; cobblestone and wood are the bottleneck.

## New
- Rescue of residents with no route to the village street (fence posts, hollows); deep terrain counts only after 3,600 ticks or after a fall.
- Quarry and mine: nearest stone cells first, cobblestone delivery on demand, mine without the oak-plank blockade, movement lock only inside the tunnel, trees only with a way back.
- Building sites: terraced site with tree clearing for granary and civic buildings, plans start with an obtainable palette, house search with a saved cursor.
- Professions: the butcher smokes raw meat from the granary; house signs are no longer placed twice; the farmer respects Serene Seasons (the first field may sow wheat outside the season).
- Modpack: runtime checks for More Villagers, Village Taverns, Serene Seasons and Towns and Towers; the server runs without Farmer's Delight, Chef's Delight and Serene Seasons without class-loading errors.

## Known gaps
No finished building in a natural run, jungle granary, client/visuals, full modpack. Details: CLOUD-LOG.md in the branch.
