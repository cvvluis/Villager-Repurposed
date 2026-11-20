# Villagers Repurposed 0.11.1

Fabric · Minecraft 26.2 · Java 25

**Important: 0.11.0 can crash on world start. Please update.**

## Fixes

- **Start crash fixed.** The recipe index assembled every crafting recipe with an empty grid; the vanilla decorated pot recipe throws for that, so the server (and single-player worlds) could fail to start. The index now skips such recipes safely.
- **Stalled builders unstuck.** The village board no longer blocks land paths (builders used to jump against it), workers pinned in house doorways by a stale job-site claim are released, and partly built plans are no longer thrown away when the owner cannot reach the stand.
- **Mines keep running.** Path failures are counted per worker and expire; ore veins no longer dig holes under the gallery; small cave pockets are bridged; masons can dig the whole mine; walking and exit queues in the gallery are much faster, so several times more stone arrives (about 2x on average in test runs, high variance).
- **Better supply for the first buildings.** Cobblestone variants (andesite, diorite, granite) pay plain block steps; idle residents fell trees for plans that lack logs; stray logs are handed in; wooden fences replace glass panes when no glass exists.

## New and improved

- **Sound and particles** for villager-placed blocks and for every dismantled block.
- **Larger village walls:** the wall radius cap is raised from 192 to 384 blocks.
- **Guards keep watch from towers.**
- **More texts are translatable** (de_de and en_us) and a check now fails on new hard-coded texts.
- Clearer stall messages on the village board.

## Known limits

- In natural test runs from a fresh world, the first civic buildings (granary, storage) finish only in some runs within three game days; cobblestone and wood supply is still the limit.
- Tested on a dev server with in-world check groups; not tested in a game client or a full modpack.
