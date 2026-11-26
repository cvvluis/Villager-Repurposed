# Villagers: Repurposed 0.11.5 playtest

Fabric · Minecraft 26.2 · Java 25

This playtest builds on 0.11.4 and adds village storage, housing, farming and travel improvements:

- **Give a resident access to a chest.** Use the Villager Chest Access tool on a villager and then a chest. With permission, the resident can eat stored food, withdraw recipe ingredients, return recipe containers, and store eligible personal surplus. Food from an authorized chest contributes to the resident's relationship with its owner and can affect vanilla trade pricing. The tool has a paper-and-emerald recipe.
- **Supply the village directly.** Players can add items to the central village chest. Residents can also collect nearby dropped items. Shared stock rules still protect reserved building, work and food supplies.
- **Improve private home storage.** Verified resident chests receive up to four real food items from the public granary. Residents may move existing miscellaneous personal items into their owner chest without moving food, seeds, tools, building materials or raw smelting supplies. This does not create items when the resident has none.
- **Build larger homes and repair some old ones.** New house plans include two paid beds and room for two residents. A bounded retrofit adds the missing second bed only to eligible mod-built homes with two adults inside; player-built and untracked structures are left alone. House signs avoid doors and windows where a safe wall cell is available.
- **Use natural and established farm plots.** Farmers scan loaded farmland, use reachable ripe crops first, refill empty hydrated cells and avoid sealed or protected plots. They can turn real wheat from the public granary into bread through the indexed recipe path.
- **Build roofed storage.** New storage plans select biome-family NBT warehouse templates with a roof, windows, lights and a paid public chest. Existing saved plans continue using their saved layout.
- **Move the official village board.** Breaking any part drops one village-bound board item. The registered board waits while the item is carried, then pins to a fully validated new position in the same loaded village.
- **Discover villages through their visitors.** A real guest reaching the village center creates a persistent neutral link to its origin; the player does not have to visit that village first.
- **Improve construction and travel.** Material-starved paid steps can use a one-block emergency fallback after 6,000 in-game ticks. Carts recover their rail berth, stalled house plans yield to pending toolsheds, and field builders deliver their paid fence and gate stock.
- **Improve foreign-trader rendering and stock handling.** Model, texture and backed-trade retry paths are present, but the appearance and interaction have not been checked in a game client.

## Verification

- Java 25 `clean build` passed with `verifyJarRefs` checking 673 jar classes.
- The integrated dev-server bundle passed **40 groups / 43,121 checks**. Additional focused groups passed: `foodchain` **21**, `privatechest` **58**, `housesign` **26**, `tafelgross` **135**, `templatebuild` **33** and `farmlandrestore` **12**. The bundle includes `farmexpand` **188** and `langkeys` **2,061**.
- Three fresh seed 4242 taiga dev-server runs completed their first granaries at ticks **56,433**, **72,278** and **81,234** (the last on a continuation of the first run). This is a small single-seed sample, not a reliability guarantee. New NBT storage plans started in all three runs, but none completed by the measured end, with the furthest at **221/651** steps.
- On one continued copy of a saved natural world, the affected granary barrel fell from 8 to 2 wheat after farmers used the new baking path; two residents recorded prepared meals. This does not prove a sustained food reserve, repeated natural harvesting, or successful crop delivery to public food stock.

No game client or full modpack was tested. Trader visuals and player interaction, NBT warehouse appearance, and broad mod compatibility remain unverified. Villager Repurposed, Fabric 0.20.1, Minecraft 26.2 and Java 25 were verified in the local dev-server build only.
