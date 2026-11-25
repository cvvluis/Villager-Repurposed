# Villagers Repurposed 0.11.2 — playtest

Builders can craft from delivered site materials, and reserved freight survives interrupted jobs. This is a playtest; first civic building reliability remains unresolved.

## Important fixes

- **Crash when entering a world fixed.** Without this fix the game could drop out of a world with `NoClassDefFoundError: de/villagerplus/job/SocialChecks` as soon as two villagers tried to start a chat, because the release jar did not contain a class the chat code needs. The build now fails when a class in the jar references a class the jar does not contain.
- **Start crash fixed (also in 0.11.0).** The recipe index no longer throws on the vanilla decorated pot recipe when the server starts.

## Changes

- Existing helper loads and unfinished tree work get an early continuation offer before unrelated jobs. Marked construction cargo stays protected from public deposits and evening private storage. Live road tasks retain their fetched materials; resumed tree work requires civic demand and returns to the root before pending removal.
- Miners can physically return a usable load that funds a live direct stone request before filling the background buffer. The repair reserve and material restrictions remain; variants cannot pay foundations.
- Timber demand credits matching public planks, sticks and finished shapes using whole loaded recipe batches. Structural logs remain separate, and plan workers choose only a deficient species.
- Long overnight, routine and unload gaps no longer count toward a plan's material-palette fallback timer. Short gaps up to 400 ticks can still count.

- Builders can process already delivered surplus site materials at the site's real crafting table. Direct placement inputs stay reserved; loaded recipe costs, full-inventory rejection and leftover items are preserved.
- Villagers of every profession resume marked construction freight before unrelated work. Delivery precedes further building, cancelled orders physically return reserved goods to public storage, and unavailable destinations keep reserved freight protected.

- Caravan porters carry real freight from the donkey chest to the matching village depot in their hands. Interrupted deliveries return cargo to the donkey or drop it when its chest is full.
- Villagers fetch new railway pieces from public stock, walk to the route and place them from their inventory. Station platforms still consume public stock at placement.
- Plaza and town hall site selection leaves the house expansion pad clear.
- Villagers detect ruined buildings and schedule demolition; mine recovery walks instead of teleporting workers.
- Wooden shape demand includes its timber inputs, shape crafting uses batches, and unclaimed log loads are handed in. Refused stacks no longer block other goods in mixed deposits.
- Mining fixture preparation allows cold terrain generation to finish before running the unchanged entity-ticking assertions.
- Development fixtures exercise the Village Taverns barrel marker and report installed EMF/ETF versions.
- Miners can withdraw the real cobblestone needed for a one-block floor repair while the active build plan's stock remains reserved. A single natural copied-world continuation advanced six sections after the fill arrived; this is not evidence that mine cadence is reliable.
- Active anvil projects expose their exact vanilla iron recipe shortage to metal miners, including a direct project-item need even if an anvil exists elsewhere near the depot. The fixture group proves this demand path, not natural extraction or toolshed completion.
- Mine recovery only selects a free portal waiting pad when vanilla navigation can reach it. One copied natural run advanced the cursor by one section after 10 × 2,400 ticks, while another miner remained in the overland loop; reliable visible progress remains unproven.

## Verification and limits

- Java 25 `./gradlew clean build` passed, including the new jar reference check (`verifyJarRefs`, 647 classes). The `constructioncontinuation` dev-server bundle passed **37 groups / 3,867 checks**, including all 23 requested baseline groups.
- Latest natural series: three fresh seed 4242 taiga worlds, each with 30 × 2,400 sprint ticks and the same 100 forced chunks, without a client or manual item grants/build orders. A first civic building completed in **1/3** runs: storage at tick 54,953. No granary completed in this batch; their final cursors were 288/437, 204/433 and 293/441. Reliable growth remains unproven.
- Copied-world comparison, **n=1 per variant**: the granary finished at tick 75,305 without the final mine rule and 75,235 with it. The 70-tick difference is not a reliable speedup.
- The strict audit remains **17/62**. No game-client or full-modpack playthrough was performed. The complete G11/G12 requirements, natural tavern gathering and EMF/ETF/Fresh Animations appearance remain unverified or partial. Dev startup still reports More Villagers recipe/advancement parse errors.

See the [current audit and handoff](https://github.com/cvvluis/Villager-Repurposed/blob/claude/village-growth-1111/AI-HANDOFF.md) for individual check counts, separate natural variants and the next concrete investigation.
