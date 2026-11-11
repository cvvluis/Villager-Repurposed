---
name: minecraft-mod-testing-expert
description: "Use to design or diagnose automated checks, Fabric/Minecraft 26.2 GameTests, unit tests, integration tests, crash analysis, persistence/reload tests, regression coverage, or mod compatibility testing."
---

# Minecraft Mod Testing Expert

## Activation and test scope

Use when correctness must be verified, a crash is reported, or tests/fixtures are being built. Read existing test harnesses, Gradle tasks, dev hooks, and project-specific safety instructions first. State what each test proves: pure logic, registry/bootstrap, in-world fixture, dedicated server, client visual check, multiplayer, or third-party compatibility. Passing one layer does not prove another.

## Test pyramid

- **Pure Java/unit:** deterministic algorithms, codecs, policy, state transitions; no Minecraft process if avoidable.
- **Bootstrap/resource checks:** registry keys, metadata, generated JSON, translations, and missing assets.
- **GameTest/in-world:** block/entity/world behavior with controlled fixtures; verify cleanup and deterministic setup.
- **Integration:** actual client or dedicated server, save/reload, networking, commands, and dependency interactions.
- **Compatibility:** explicit Minecraft/Loader/API/mod versions and scenarios; report only tested combinations.
- **Manual visual:** renderer, animation, accessibility, or emergent world behavior that automation cannot establish.

## Fabric 26.2 approach

Use the current Fabric docs, example mod, and current Loom/Gradle tasks for test wiring; do not assume older Fabric GameTest signatures or Forge/NeoForge test APIs. First inspect project dependencies and task list. Separate test registration/entrypoint from production initialization. Fixtures should use stable IDs, explicit seeds and cleanup, and avoid accidental mutation of user worlds.

For every behavior, test: success, invalid input, boundary, repeated invocation/idempotence, persistence/reload where relevant, and server/client authority. For inventories/economy/world mutation, assert conservation and rollback on failure.

## Test design best practices

- Assert observable outcomes, not only that a method returned.
- Keep fixtures isolated, deterministic, and self-cleaning even on assertion failure.
- Avoid sleep-based timing. Await explicit events or bounded tick conditions with timeout diagnostics.
- Avoid broad test hooks that bypass normal gameplay paths unless the test explicitly isolates that layer.
- Preserve evidence labels: synthetic fixture vs natural world; server only vs client visual; fresh boot vs restart.
- Analyze crashes from the first causal exception, not just the last wrapper. Include mod list, versions, side, world state, and relevant logs.

## Java example: conservation invariant

```java
int before = input.getCount() + output.getCount();
boolean crafted = tryCraft(input, output);
int after = input.getCount() + output.getCount();

assertEquals(before, after, "failed or partial craft must conserve items");
assertFalse(crafted);
```

Adapt the assertion to the intended recipe transformation; assert exact ingredient/output counts rather than a simplistic conservation equation when the recipe legitimately transforms materials.

## Debugging workflow

1. Record exact game, Loader, API, Java, OS, side, and mod versions.
2. Reproduce on the smallest reliable scenario; retain the first exception and complete cause chain.
3. Compare clean launch versus existing world and minimal mod set.
4. Isolate registry/bootstrap, data reload, world tick, networking, rendering, or persistence stage.
5. Add a focused regression check at the lowest layer that reproduces the defect.
6. Re-run the focused check and a relevant integration path; report remaining manual gaps.

## Completion checklist

- [ ] Test layer and claim are explicit.
- [ ] Fixture determinism, cleanup, and restart behavior are sound.
- [ ] Failure/boundary and regression case are included where relevant.
- [ ] Client/server and compatibility coverage are accurately described.
- [ ] Tests run and evidence are reported; no claim exceeds the tested scope.

## References

See [references/testing-sources.md](references/testing-sources.md).
