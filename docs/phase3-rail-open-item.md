# Phase 3 – Rail E2E and CTOV

Status: 31.10.2026, branch `cursor/master-prompt-phase3-ea40`.

## Production fixes

- `RoadRoute`: Manhattan fallback when A* fails; `ensureWithRetry` no longer blocks on an expired retry.
- `Roads.findRailSite` / station access: platform Y plus heightmap; gravel footing instead of grass surface.
- `Roads.isProtectedLaneCell`: village chests are not paved over.
- `RailResources.hasMetallurgyStock` requires rails, so that `railReady` and `canPay` match.
- Resident counting via loaded entities and meeting point at the own bell.

## Evidence

- `./gradlew build` JDK 25 green.
- `VP_AUTOTEST=headless`: **220 PASS** (including `railmetro` 13 and `caravandelivery` 6).
- `VP_AUTOTEST=ctov` on world `vp-ctov-headless`: **37 PASS**, natural CTOV bell `-312, 81, -330`, 14 villagers.
- Restart `VP_AUTOTEST=ctov-verify`: village attachment `planType=ctov-headless` survives.

The station in the rail E2E stands next to the center axis, so that the station chest is not the track bed. The threshold “at least three connected segments” is unchanged.
