# Villagers: Repurposed 0.11.4 playtest

Fabric · Minecraft 26.2 · Java 25

- **Construction workbenches survive a scaffold waiver.** Villagers still pay for and place a crafting table when scaffolding is unavailable. Older active construction sites can add their table without restarting the plan.
- **Stranded residents leave their stale walk target behind.** When a resident is returned to the village, their previous Vanilla walk target is cleared.
- **Rail and toolshed follow-ups.** Bumped rail carts can return to their berth. A toolshed waiting for a site can take priority over a stalled fallback house, and the plan owner can help mine the iron for its anvil.
- **Development measurements.** The natural-run tool can sample construction cursors, materials and cargo every 100 ticks, and allows extra time for initial chunk generation.

## Verification

In three fresh seed 4242 taiga dev-server runs, two granaries finished at ticks 54,476 and 56,177. In the third run, neither civic building finished within 72,000 ticks. A separate run on a copy of an existing stalled save confirmed two real workbenches were added without restarting either plan. Natural completion and long build pauses remain open.

The Java 25 clean build and 652-class jar reference check passed. The integrated dev-server suite passed 37 groups (3,916 checks), with 17 additional building-relocation checks. A separate Fabric 0.20.1 server loaded the locally built 0.11.4 jar and completed 2,400 sprint ticks around a vanilla taiga village. The strict master-prompt audit remains 17 of 62 requirements implemented. No client or full modpack was tested.
