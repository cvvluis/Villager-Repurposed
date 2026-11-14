---
name: minecraft-mod-architecture-expert
description: "Use to design or refactor the long-term architecture of a Minecraft 26.2 Fabric mod: Java package boundaries, modular gameplay systems, extension points, public APIs, configuration, persistence, lifecycle, and maintainability."
---

# Minecraft Mod Architecture Expert

## Activation

Use for module boundaries, large feature work, API design, configuration, save data, or maintainability refactors. Inspect actual project packages and entrypoints before proposing structure. Respect current conventions and avoid architecture changes unrelated to the requested outcome. Consult the Fabric Expert for current APIs and the Testing Expert for migration/regression coverage.

## Design principles

- Organize around domain responsibilities (registration, gameplay, persistence, client presentation, networking) rather than one giant initializer or class-per-API.
- Keep Fabric/Minecraft types at integration edges when pure domain logic can be expressed as ordinary Java.
- Use explicit dependencies and lifecycle; avoid static mutable global state and hidden initialization order.
- Keep server authority clear. Client code presents state; network payloads express validated requests or synchronized snapshots.
- Prefer stable, narrow extension points and documented contracts over exposing internal registries or mutable collections.
- Use immutable value objects for messages/config snapshots where practical; validate at boundaries.
- Make configuration typed, defaulted, validated, and safe when keys are missing or malformed. Separate client-only options from gameplay/server policy.
- Version persisted data. Supply defaults and migration behavior; never assume old saves contain newly added fields.
- Give long-running systems explicit cancellation, unload, restart, and failure recovery paths.

## A practical feature slice

1. Define the use case and authoritative side.
2. Define domain state and invariants independent of UI.
3. Add a narrow service/use-case operation.
4. Connect registry/event/command/network adapters.
5. Add persistence/configuration codecs with defaults and version policy.
6. Add client view/rendering only if needed.
7. Test pure rules, lifecycle, save/reload, and network validation.

## Java example: boundary around policy

```java
public record TradeRequest(ItemStack offered, int requestedCount) {}

public interface TradePolicy {
    TradeDecision evaluate(TradeRequest request, TradeContext context);
}
```

Keep `TradeContext` immutable and created from validated server state. The Fabric event/packet handler calls the policy; it should not duplicate the rule. Do not expose mutable internal inventories through a public API.

## Persistence and configuration

- Choose the proper owner: item data component, block entity, entity data, world saved data, or config file.
- Persist only state that must survive restart; derive caches where possible.
- Define codec, defaults, bounds, schema/version migration, and corruption behavior before writing data.
- Mark data dirty on authoritative changes; avoid saving every tick.
- Keep world-specific state out of process-global singleton maps unless keys and lifecycle are rigorously bounded.
- For config reload, parse/validate into a new immutable snapshot and swap atomically on the owning thread.

## Debugging and refactor guidance

- **Initialization-order bugs:** map entrypoints and registration dependencies; replace implicit static initialization with explicit bootstrap methods.
- **State disappears after restart:** verify owning save object, codec fields, dirty marking, and read defaults; test older save shape.
- **Feature cross-coupling:** identify shared state or duplicate rules; create a narrow service boundary and migrate callers incrementally.
- **API breakage:** distinguish public compatibility surface from internal implementation; document versioning and deprecation.
- **Client/server leak:** follow class references from common entrypoints; split presentation from authoritative domain logic.
- **Refactor regression:** add characterization tests before moving behavior; make one boundary change at a time.

## Architecture review checklist

- [ ] Each state value has one clear owner and authoritative side.
- [ ] Initialization and shutdown/unload order are explicit.
- [ ] Public APIs expose minimal immutable contracts.
- [ ] Persistence/config have defaults, validation, and migration/corruption behavior.
- [ ] Async work, caches, listeners, and packets have bounded lifecycle.
- [ ] Tests cover invariants before and after the refactor.

## References

See [references/architecture-sources.md](references/architecture-sources.md).
