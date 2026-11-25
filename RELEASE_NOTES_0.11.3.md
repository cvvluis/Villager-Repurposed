# Villagers Repurposed 0.11.3

Fabric · Minecraft 26.2 · Java 25

**Update from 0.11.2: 0.11.2 can crash a world as soon as villagers try to chat. Please use this version.**

## Important fixes

- **Crash when entering a world fixed.** 0.11.2 could drop out of a world with `NoClassDefFoundError: de/villagerplus/job/SocialChecks`, because the release jar did not contain a class the villager chat code needs. The jar now contains it, and the build fails when any class in the jar references a class the jar does not contain (`verifyJarRefs`).
- **Performance.** Workshop and crafting jobs no longer scan all recipes every tick; recipe lookups are cached and an empty search is not repeated until the chests or recipes change. This should help in large modpacks with many recipes.

## Changes since 0.11.2

- **House lots:** the plan owner now takes a stored shovel or crafts one from public stock, so levelling a house site no longer waits for a toolshed that may never finish. Villagers that climbed back and forth between a barrel and a fence post are rescued after three step-offs.
- **Mines:** better material flow from the mine to construction plans.
- **Repair goods** go to the tool chamber with a public-storage fallback; oversized stacks no longer lose items.
- **Roads:** gravel is requested in batches and the quarry-to-road loop is checked; a blocking dry ravine no longer stops road planning; shapeless crafting recipes with 5, 7 or 8 ingredients and lever stock are handled.
- **Work blocks:** professions procure a missing work block (B01); chest scans respect ownership (B05).

## Verification and limits

- Java 25 `./gradlew clean build` passed, including the jar reference check. Dev-server check groups passed for the affected areas (for example `firstbuilding`, `langkeys`, `roles`, `craftrecipes`, `stockpolicy`, `buildhaul`, `roads`, `roadwork`, `roadroute`, `equipment`, `workblocks`, `chestscan`).
- The checks run on a dev server and do not use the built jar, which is how the 0.11.2 crash slipped through. The new jar check closes that gap for missing classes only.
- First civic buildings (granary, storage) still finish only in some natural runs; stone and wood supply remain the limit. The strict audit stays at 17/62.
- Not tested in a game client or a full modpack beyond the crash analysis from a user log.
