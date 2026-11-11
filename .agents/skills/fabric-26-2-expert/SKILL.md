---
name: fabric-26-2-expert
description: "Implement or debug Minecraft Java Edition 26.2 mods using Fabric Loader and Fabric API: registries, events, commands, items, blocks, block entities, entities, menus, networking, data components, world generation, or data generation. Use only after confirming this is a Fabric 26.2 project; defer build tooling, Mixins, rendering internals, performance, testing, and architecture deep dives to the focused companion skills."
---

# Fabric 26.2 Expert

## Mission and activation

Use for gameplay and Fabric API work in a Java Edition **26.2** mod. First inspect `gradle.properties`, `build.gradle`, `settings.gradle`, `fabric.mod.json`, entrypoints, and the current source layout. Confirm the target version and loader. If the task is another Minecraft version, follow that version's official docs and say so; never silently transplant 26.2 signatures. Route dominant subproblems to the Mixin, Loom, Rendering, Performance, Testing, or Architecture companion skill. The broad `minecraft-modding` skill remains useful for non-Fabric or cross-loader routing.

## Version truth and source policy

Minecraft 26.2 is covered by Fabric's current versioned developer documentation. Official 26.2 setup documentation specifies JDK 25. Read `gradle.properties` for the project's actual versions; do not copy version numbers from this skill. Fabric APIs evolve independently, so confirm each class and method in the 26.2 docs and resolved dependency sources. Treat old Yarn/1.21 tutorials, mappings, and generated snippets as historical until verified. For an unlisted topic, inspect the current example mod and source jars, then label uncertainty.

## Workflow

1. Identify client/server/common ownership and current registration patterns.
2. Find the matching official 26.2 guide in `references/fabric-docs.md`; inspect the current example or source when a guide omits edge cases.
3. Prefer vanilla APIs where they cover the requirement, then Fabric API events/hooks. Use Mixins only when no supported API can express the change.
4. Make a narrow change consistent with the repository's ID, registry, package, language, and data conventions.
5. Connect code to assets/data: models, item definitions, blockstates, translations, recipes, loot, tags, sounds, and datagen as appropriate.
6. Verify with the smallest meaningful project check requested by the user, and report the exact command and result. Do not claim in-game behavior from compilation alone.

## Practices by feature

- **Registries:** use namespaced `Identifier`s and the project's established registration helper. Register related item/block/block-entity/entity types in a deterministic bootstrap path before use. Never register from a tick callback or static initializer with hidden side effects.
- **Items and components:** set stack limits and item behavior intentionally. Prefer typed data components and codecs for structured stack data; do not add new ad-hoc NBT keys. Distinguish stack-local data from world/player persistent data.
- **Blocks and block entities:** keep finite state in block states; use a block entity only for mutable per-position state. Mark state dirty when persistent data changes; keep inventory rules, sided access, menu synchronization, and drops consistent. Validate client/server paths.
- **Entities:** register type, attributes, spawn rules, tracking range/update rate, renderer/model, spawn egg, and data in the relevant side-specific paths. Server owns gameplay; client renderer only presents synced state.
- **Events and commands:** subscribe once during initialization. Check event ordering/cancellation and logical side. Commands must enforce permission levels, validate arguments, and return clear success/failure values.
- **Networking:** define typed payloads and codecs using the 26.2 networking guide. Treat every client payload as untrusted; validate ownership, distance, dimension, rate, and requested action on the server. Keep packets small and version contracts deliberately.
- **Menus/screens:** server menu/container is authoritative; client screen is presentation. Validate slot transfers and actions server-side. Close or invalidate menus safely when backing state disappears.
- **World generation:** register configured/placed features and biome/structure integration via the correct static or dynamic registry path. Make placement deterministic and bounded. Test new chunks and avoid mutating already-generated terrain unless explicitly designed.
- **Data generation:** use providers for generated resources and keep generated output reproducible. Review diffs and preserve hand-authored resources. Ensure generated IDs match registered IDs exactly.

## Java pattern: create a registry key before registering an item

The Fabric 26.2 item guide uses a `ResourceKey` tied to the item registry, then assigns that key to the item properties before registration. Keep the helper's mod ID in the project's central ID class.

```java
public static ResourceKey<Item> createItemKey(String path) {
    return ResourceKey.create(Registries.ITEM, ExampleMod.id(path));
}
```

When registering, use the key with the project's 26.2 registration pattern and set it on `Item.Properties` first. Keep client-only classes out of common/server entrypoints. For a specific registry or networking API sample, use the corresponding official 26.2 page rather than guessing a signature.

## Debugging guide

- **Unknown registry key / missing object:** check namespace/path, bootstrap order, entrypoint loading, and whether generated JSON references the same ID.
- **Dedicated server crashes in client class:** inspect imports and static initializers for renderer, screen, key mapping, or client singleton reachability; move wiring to the client entrypoint.
- **Missing model/texture/translation:** verify exact case-sensitive resource path, JSON schema for 26.2, `pack.mcmeta`, and generated-resource task output.
- **Data reload rejects JSON:** read the first parse error and registry key; validate codec shape, tags, and dynamic-registry timing.
- **Feature never generates:** check placed/configured feature registration, biome selection/conditions, placement modifiers, and whether the tested chunk was generated after the change.
- **Client/server disagreement:** identify authoritative state, sync payload/update path, and whether the client prediction is reconciled.

## Completion checklist

- [ ] Target is confirmed as Fabric + Minecraft 26.2; JDK and dependency versions came from the project.
- [ ] APIs/signatures were checked against official 26.2 docs or resolved sources.
- [ ] Registration, side ownership, persistence, synchronization, and resource IDs are complete.
- [ ] Invalid inputs and reload/restart paths are considered.
- [ ] Relevant checks were run and their scope is stated accurately.

## References

See [references/fabric-docs.md](references/fabric-docs.md) for versioned official guides, version verification endpoints, and companion skill routing.
