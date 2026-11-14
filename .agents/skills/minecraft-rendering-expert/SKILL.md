---
name: minecraft-rendering-expert
description: "Use for Minecraft 26.2 client rendering: entity and block-entity renderers, render states, custom pipelines/shaders, HUD/GUI drawing, particles, animation presentation, extraction/draw phases, and render performance."
---

# Minecraft Rendering Expert

## Scope and activation

Activate for visual/client rendering or rendering crashes/performance. Confirm 26.2 and inspect existing renderer registration, assets, render pipeline usage, and client entrypoint. Minecraft 26.2 uses an evolving split extraction/preparation and drawing model; follow the versioned docs and source. **Raw OpenGL is unsupported in 26.2** because Minecraft has an optional Vulkan backend: use the Blaze3D abstraction and supported render pipelines.

## Rendering model

- During extraction/preparation, read world/entity/block data and copy only required immutable values into render state.
- During drawing, consume prepared state and issue work through supported render abstractions. Do not retain live mutable world objects for later render-thread access.
- Reuse vanilla pipeline/shader/material where possible. Create a custom pipeline only when the required depth, blend, shader, or vertex behavior cannot be expressed by a suitable vanilla pipeline.
- Client-only registration belongs in the client initializer. Avoid common static references to client renderer classes.
- Keep per-frame work allocation-light; cache stable geometry/assets, batch compatible work, and cull by distance/frustum/visibility.

## Feature guidance

- **Entity renderers:** renderer reads synchronized state; server entity owns gameplay. Keep animation deterministic from synced entity state and partial tick. Confirm model layer registration and resource IDs.
- **Block-entity renderers:** render only transient/world-visible detail; persistent gameplay state lives in the block entity. Respect render bounds, chunk unloads, and distance culling.
- **HUD/screens:** register through supported HUD/screen hooks. Respect GUI scale, safe areas, scissor nesting, ordering, accessibility, and user settings. Do not overwrite unrelated HUD elements.
- **Particles:** send spawn data through the server when gameplay-relevant; keep visual-only particles client-side and bounded. Avoid per-particle expensive world scans.
- **Shaders/pipelines:** use resource identifiers, supported shader/pipeline registration, declared vertex formats and render states. Validate resource reload and device capability. Provide fallback where custom effects are optional.
- **Animation:** interpolate between authoritative snapshots; avoid mutating game state during extraction/drawing.

## Example: immutable extraction snapshot

Illustrative architecture, not a callback signature: exact renderer and state types vary by the registered renderer in 26.2.

```java
record BeaconRenderState(float progress, int argb, boolean active) {}

static BeaconRenderState extract(BeaconBlockEntity blockEntity) {
    return new BeaconRenderState(
        blockEntity.getProgress(),
        blockEntity.getDisplayColor(),
        blockEntity.isActive()
    );
}
```

The draw phase should consume the snapshot, not query the live world. Use the exact 26.2 renderer extraction/draw APIs from the cited guide.

## Debugging

- **Nothing renders:** confirm client entrypoint, renderer type registration, entity/block-entity type identity, resource path, render bounds, and visibility/culling.
- **Broken pipeline/shader:** inspect log's shader/resource reload error, resource namespace/path, vertex format, uniforms, and pipeline state. Test the fallback vanilla pipeline.
- **Crash on Vulkan or another GPU:** remove raw GL calls and unsupported state assumptions; use Blaze3D abstractions and test capability fallback.
- **Flicker/one-frame lag:** inspect extraction snapshot timing, partial tick interpolation, thread ownership, and mutable data sharing.
- **Transparency/depth errors:** verify blend/depth/cull state and sort requirements; compare with a vanilla pipeline.
- **FPS collapse:** profile renderer cost, allocation rate, draw calls, overdraw, and per-frame world lookups; add culling and cache immutable buffers.
- **Reload-only crash:** verify resource lifecycle; rebuild GPU resources in supported reload callbacks and release owned resources.

## Review checklist

- [ ] Uses supported Blaze3D/render pipeline APIs; no raw OpenGL path.
- [ ] Extraction data is safely separated from draw work.
- [ ] Renderer registration and resources are client-only and reload-safe.
- [ ] Culling, bounds, allocation, and device fallback are considered.
- [ ] Visual testing covers GUI scale, resource reload, and at least one supported backend when available.

## References

See [references/rendering-sources.md](references/rendering-sources.md).
