---
name: minecraft-performance-expert
description: "Use when profiling or optimizing a Minecraft 26.2 mod's server ticks, client frame time, memory/GC, networking, chunk/world work, entity AI, or client/server boundaries."
---

# Minecraft Performance Expert

## Activation and evidence

Use when the task is performance, scalability, stutter, memory growth, or thread safety. First determine whether the symptom is client FPS, server MSPT/tick lag, startup, chunk generation, network latency, or memory/GC. Record a reproducible workload, mod list, world/dimension, player/entity/chunk counts, and baseline measurements. Do not optimize by intuition alone.

## Measure first

1. Reproduce with a stable scenario and record baseline (tick/frame time, allocation/heap, affected chunks/entities, packet rate).
2. Use a current profiler compatible with Minecraft 26.2; capture a profile and identify the dominant hot path.
3. Distinguish CPU, allocation/GC, I/O, lock contention, network, GPU, and worldgen bottlenecks.
4. Change one cause at a time, run the same workload, compare distributions and behavior, then retain or revert based on evidence.

## Server tick and chunk practices

- Keep per-tick work bounded. Avoid full-world scans, repeated path searches, large JSON/codec work, disk/network I/O, and allocations inside hot loops.
- Use event-driven invalidation or cached indexes with explicit lifecycle; do not create an unbounded cache keyed by every visited position.
- Spread non-urgent work over ticks with fairness, cancellation, and chunk-loaded checks. Never force-load chunks merely to make background work convenient.
- Batch block changes where semantics allow; respect neighbor updates, block entity lifecycle, drops, and client sync.
- Keep entity AI goals cheap and avoid duplicate path/navigation requests. Profile before reducing behavior frequency.
- For world generation, bound feature search/placement work and test worst-case terrain, not only a flat fixture.

## Memory and concurrency

- Track ownership of mutable world, entity, inventory, and registry state. Treat game world mutation as server-thread work unless 26.2 documentation explicitly permits otherwise.
- Do not pass live game objects to async tasks. Snapshot immutable data, perform only independent computation off-thread, then schedule a validated server-thread apply step.
- Bound task queues, caches, and packet buffers; handle shutdown/world unload; use weak references only when their lifecycle semantics are correct.
- Reduce allocation by measuring object churn first; avoid premature pooling and shared mutable state.

## Networking and side separation

- Send deltas rather than full state when the protocol supports it; batch only when latency remains acceptable.
- Validate and rate-limit client requests on the server. Do not broadcast data to players who cannot use it.
- Keep client-only presentation code out of dedicated-server class loading. Server remains authoritative for gameplay.

## Debugging guide

- **MSPT spikes:** capture a server profile; inspect scheduled tasks, chunk/entity counts, scans, navigation, worldgen, and synchronous persistence.
- **Progressive memory climb:** compare heap dumps after controlled intervals; find retaining paths, static collections, listeners not unregistered, and per-world caches.
- **GC pauses:** inspect allocation profile and GC logs; identify churn before changing heap sizing.
- **Only large worlds lag:** check global scans, index growth, forced chunks, and cost proportional to loaded chunks rather than active targets.
- **Only multiplayer lags:** measure payload frequency/size, server validation, broadcast fanout, and client/server latency separately.
- **Race or rare crash:** capture thread names and lifecycle; find off-thread world access and queued work applied after unload.

## Example: snapshot/compute/apply pattern

The threading API for scheduling the apply step must be verified against the project's actual 26.2 sources; the invariant is that no live world object crosses into the worker.

```java
record CellSnapshot(BlockPos pos, int score) {}

List<CellSnapshot> snapshot = collectOnServerThread(world);
worker.execute(() -> {
    List<BlockPos> result = score(snapshot); // immutable inputs only
    enqueueOnServerThread(() -> applyIfStillValid(world, result));
});
```

Revalidate loaded state and ownership at apply time. Bound work size and cancel on world/server shutdown.

## Optimization checklist

- [ ] Baseline and workload are recorded.
- [ ] A profile identifies the actual hot path.
- [ ] Work scales with relevant loaded/active objects, not total world size.
- [ ] Thread ownership, chunk loading, and lifecycle are safe.
- [ ] Networking and caches are bounded.
- [ ] The same workload shows a measured improvement with no behavior regression.

## References

See [references/performance-sources.md](references/performance-sources.md).
