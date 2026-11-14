# Minecraft Fabric 26.2 Skill Pack

Seven independently activatable coding-agent skills installed and version-controlled in `.agents/skills/`:

| Skill | Folder | Main scope |
|---|---|---|
| Fabric 26.2 Expert | `fabric-26-2-expert` | Fabric API and gameplay systems, registries, networking, components, data/world generation |
| Mixin & Minecraft Internals Expert | `fabric-26-2-mixin-internals-expert` | Mixins, injection strategy, Class Tweakers/access widening, conflicts |
| Gradle & Fabric Loom Expert | `fabric-loom-gradle-expert` | Java/Gradle/Loom, metadata, build, migration, packaging and CI |
| Minecraft Rendering Expert | `minecraft-rendering-expert` | 26.2 render pipeline, extraction/drawing, GUI/HUD, entities, particles and shaders |
| Minecraft Performance Expert | `minecraft-performance-expert` | Profiling, ticks, memory, threading, networking and chunk costs |
| Minecraft Mod Testing Expert | `minecraft-mod-testing-expert` | Unit/game/integration tests, crashes, persistence and compatibility claims |
| Minecraft Mod Architecture Expert | `minecraft-mod-architecture-expert` | Modules, API boundaries, configuration, persistence and lifecycle |

Every folder contains a `SKILL.md` with explicit activation rules, workflow, practical Java examples, best practices, a debugging guide, and a completion checklist, plus a focused source map under `references/`. The skills refer to one another for cross-cutting tasks and leave the existing broader `minecraft-modding` skill intact.

## Activation routing

- Ask for a gameplay/API implementation such as an item, command, packet, entity, or world feature → **Fabric 26.2 Expert**.
- Ask to inject/overwrite vanilla behavior, use an access widener/Class Tweaker, or diagnose mixin application/conflicts → **Mixin & Minecraft Internals Expert**.
- Ask about `build.gradle`, dependencies, Java toolchains, `fabric.mod.json`, packaging, version migration, or CI → **Gradle & Fabric Loom Expert**.
- Ask for a renderer, HUD/screen, particle, animation, shader, or render pipeline → **Minecraft Rendering Expert**.
- Ask to profile lag, reduce MSPT/frame cost/memory, or fix threading/chunk/network scaling → **Minecraft Performance Expert**.
- Ask for automated/GameTest/integration/compatibility checks or crash analysis → **Minecraft Mod Testing Expert**.
- Ask for package boundaries, public APIs, modularity, config, save format, or lifecycle design → **Minecraft Mod Architecture Expert**.
- For mixed requests, activate the primary domain skill and the smallest relevant companion set; the Fabric expert is the implementation baseline. Existing `minecraft-modding` still routes non-Fabric and cross-loader tasks.

## Version policy

The official Fabric documentation selector contains a Minecraft 26.2 documentation set and the official setup guide specifies JDK 25. The current project was inspected at install time: Minecraft `26.2`, Loader `0.20.1`, Loom `1.19-SNAPSHOT`, Fabric API `0.161.0+26.2`. These project values are time-sensitive; use the Fabric version selector, official Maven metadata, 26.2 docs, and current example mod to verify versions and APIs for future work.

## Validation

Validated on 2026-11-01: seven skill directories, frontmatter fields, domain-specific activation triggers, required debug/checklist/reference sections, local reference-file targets, and 29 official Fabric doc links. This validates the package layout and activation metadata, not a Minecraft build or gameplay behavior.
