# Villagers Repurposed 0.10.9

Fabric · Minecraft 26.2 · Java 25

A performance-focused release: less villager geometry when rendering and less server work in the blueprint pickaxe check.

## Role outfit: one model, one texture

Role villagers used to be drawn twice (base model plus role layer). With shaders such as Iris this also doubled the shadow pass. Now the role is baked into **one** dynamic combined texture (vanilla base plus role overlay, at resource reload). Biome clothing is intentionally dropped for roles, so the outfit looks the same everywhere.

## Blueprint: pickaxe check throttled

When a plan needs cobblestone but no pickaxe exists yet, the server searched all chests and recipes every tick, even with no change to the inventory. The result of the check is now cached for a few seconds, and each call scans the storage only once.

## Note

`gradle build` passed at this state. Not tested in game: role looks as before (without biome texture), pickaxe still arrives on time when materials are delivered.
