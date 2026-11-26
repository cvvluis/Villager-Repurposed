# Natural construction cadence probe

The natural-run cadence probe is development-only and opt-in. It writes one `VPCADENCE` log line per active build project every 100 game ticks. It reads villagers' already-known meeting-point bells only after `LoadedColumns.ready`; it does not search for bells, request chunks, or inspect blocks at unloaded positions.

Run it with:

```bash
VP_CADENCE_PROBE=1 bash tools/cloud/natural-run.sh vp-natural111-cadence1 30 2400
python3 tools/cloud/cadence.py run/vp-natural111-cadence1.log
```

The script passes `-Dvillagerplus.devCadence=true` to the server JVM through `JAVA_TOOL_OPTIONS`. The development-only `AutoTest` registration checks this property once at startup and registers the sampler only when it is enabled. A normal production runtime does not register or run the sampler.

Each `VPCADENCE v=1` record contains the dimension and bell, server game tick, overworld clock time and `Phase` enum, project type and origin, persisted plan cursor, current wanted rows, site-stock counts, and cargo manifest. `total=unknown` is intentional: project records persist a cursor, but not a total step count.

Each observed cursor advance can fall anywhere in the preceding 100-tick sample interval. The parser reports closed gaps between successive advances and open tails up to the project's last active sample. It reports the initial interval separately because the plan may have started before the first sample. A missing sample, clock discontinuity, or cursor decrease starts a new interval. Durations count game ticks in the configured WORK_A/WORK_B windows. Weather and villager availability do not change those durations. The `phase` field reports the sampled `Phase` enum.

The probe is observational. It does not mutate or persist project state and it does not report exact in-world block placement, worker activity, or available public-store inventory.
