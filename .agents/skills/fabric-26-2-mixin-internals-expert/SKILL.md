---
name: fabric-26-2-mixin-internals-expert
description: "Use when a Fabric 26.2 mod needs SpongePowered Mixin, MixinExtras, an access widener, Minecraft internals, injection-point selection, mixin conflict debugging, or a safe vanilla modification. Do not use for ordinary Fabric API features, which should use fabric-26-2-expert first."
---

# Mixin & Minecraft Internals Expert

## Activation and initial inspection

Activate only when the request touches game internals, Mixins, access wideners, transformation order, or a vanilla behavior with no suitable public API. Confirm MC/Loader/Loom versions, mappings/name namespace, mixin config, refmap or remapping setup, `fabric.mod.json`, access widener path, and all existing mixins targeting the same class/method. Check the resolved Minecraft source and exact target descriptor before editing.

## Decision order

1. Search vanilla public API and Fabric API hooks/events.
2. If access to a member alone is needed, prefer a narrowly scoped access widener over copying or replacing behavior.
3. If behavior must change, prefer a cancellable `@Inject` at a stable semantic point.
4. Use `@ModifyArg`, `@ModifyVariable`, `@Redirect`, or MixinExtras only when the narrower semantic operation requires it and dependency/version availability is verified.
5. Use `@Overwrite` only as a last resort; document why, preserve vanilla invariants, and account for conflict risk.

## Injection rules

- Use target owners and descriptors from the project's actual 26.2 compile sources. Do not infer a descriptor from a web snippet or older mappings.
- Use `@Shadow` for accessed members and `@Unique` for added members with collision-resistant names. Keep mixin classes minimal.
- Make injection intent explicit with `method`, `at`, `cancellable`, and an appropriate `require`/`expect` policy. Avoid `require = 0` as a way to hide a broken target.
- Prefer an injection point that expresses the intended semantic boundary. `HEAD`/`RETURN` may be stable but can be too broad; ordinals and `INVOKE` targets are fragile and need evidence.
- Use callback info only as required by the target's return type and cancellation behavior. Do not cancel unrelated paths.
- Mixin configs declare package, compatibility level, mixin lists, and side separation correctly. Verify the JSON is packaged and named by mod metadata.
- The current 26.2 docs also expose **Class Tweakers** and an Access Widening guide. Inspect the project template and use the mechanism actually supported by its exact Loom/Loader combination; do not assume a legacy `accessWidener` file is the right format. Widen the least visibility possible and document the consumer.

## Java example: defensive cancellable injection

This is an annotated shape, not a copy-ready target: replace class, method, descriptor, and callback type only after checking the 26.2 source.

```java
@Mixin(TargetClass.class)
abstract class TargetClassMixin {
    @Inject(method = "targetMethod", at = @At("HEAD"), cancellable = true)
    private void examplemod$guard(CallbackInfo ci) {
        if (shouldCancelThisSpecificCase()) {
            ci.cancel();
        }
    }
}
```

For a non-void target, use the returnable callback form matching its actual return type. Keep the condition narrow, side-safe, and cheap.

## Debugging and compatibility

- **Mixin not applied:** inspect loader log for config discovery, JSON syntax, class name/package, environment side, refmap/remap settings, and whether the mod jar contains the config.
- **Target not found / invalid descriptor:** inspect 26.2 compiled/decompiled sources and bytecode; confirm overload, owner, return descriptor, and mapping namespace.
- **Injection count failure:** treat it as a compatibility signal. Confirm target changed, then adjust only with evidence; do not blanket-disable required injections.
- **Another mod conflicts:** capture both mixin application logs and target order; test a minimal mod set, then document a narrow compatibility constraint or API-level fallback.
- **Crash after update:** compare target bytecode/source across versions and add a focused regression test or launch fixture.
- **Client-only class resolution on server:** split config/entrypoints and remove client types from common mixin signatures.

Mixin compatibility is empirical. Do not promise universal compatibility from successful compilation. Prefer idempotent, composable transformations and avoid modifying the same instruction as another mod when a public hook exists.

## Safety and review checklist

- [ ] Public API alternatives were checked first.
- [ ] Target signature and injection point are evidenced from the exact 26.2 sources.
- [ ] Injection scope is narrow and explicit; required injections fail loudly when broken.
- [ ] Client/server ownership and load order were considered.
- [ ] Mixins/AW are packaged and tested in a launch, not just compiled.
- [ ] A competing mod scenario or known conflict is recorded when relevant.

## References

See [references/mixin-sources.md](references/mixin-sources.md).
