# Villagers Repurposed 0.10.8

Fabric · Minecraft 26.2 · Java 25

This release summarizes playtests 0.10.1 to 0.10.8. Compared with 0.10.0, the main fix is the freeze when entering a village.

## Freeze when entering a village

In the large modpack the picture froze as soon as you entered a village. Sound and menu kept running, and the server reported "Can't keep up". The cause was the relief sampling of the large Dorftafel (village board): it synchronously generated missing chunks on the server thread.

0.10.8 reads the map, relief and the other terrain sampling only from chunks that are already fully loaded. The map fills in gradually. The stall watchdog recognizes such waits in `getChunkBlocking` and no longer counts them as a harmless pause. The relief works with Iris; the missing `debug_quads` shader is no longer used.

## Since 0.10.0

- **Food (0.10.1).** Village-wide harvest, fishing when short, a limited food bridge to the village chest, scarce portions go to the hungry first. Idle residents and free masons supply cobblestone when plans are waiting.
- **Less constant load (0.10.2 and 0.10.3).** At most one village per dimension is held, only already loaded chunks, no new `/forceload` entries. Old forced chunks are released. Can be turned off with `-Dvillagerplus.keptVillages=0`.
- **Village toast (0.10.4).** Errors disable only the toast, the chat message stays. `-Dvillagerplus.toast=false` turns it off entirely.
- **Template houses (0.10.5).** Village house, town hall and smithy as hand-built templates. New tall houses can use the village house. Town hall and smithy are ready but not yet built as a milestone.
- **Stall report (0.10.6 and 0.10.7).** Thread stacks in `logs/villagerplus-stall-server.txt` and `-client.txt`, at most three reports per launch. The client reports only while a world is running, and since 0.10.8 once per frame.

## Note

`gradle build` passes at this state. 0.10.8 has not yet been played in the large modpack. Please check there: entering a village without a freeze, relief with Iris, the board map fills in afterwards.
