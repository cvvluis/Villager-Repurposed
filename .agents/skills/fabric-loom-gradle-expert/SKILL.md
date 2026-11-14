---
name: fabric-loom-gradle-expert
description: "Use for Minecraft 26.2 Fabric project setup and build engineering: Gradle, Fabric Loom, dependency coordinates, Java toolchains, fabric.mod.json, source sets, run configs, mappings/remapping, mod JARs, migration, CI, and release automation."
---

# Gradle & Fabric Loom Expert

## Activation

Use when creating, repairing, upgrading, building, packaging, or releasing a Fabric mod project. Start with `gradle.properties`, `settings.gradle`, `build.gradle`, wrapper properties, `fabric.mod.json`, source sets, CI files, and the current Git status. Preserve uncommitted user changes. Confirm the exact target and current official versions before changing any version.

## Version and compatibility policy

Fabric's 26.2 setup guide targets JDK 25. This repository's currently inspected values are `minecraft_version=26.2`, `loader_version=0.20.1`, `loom_version=1.19-SNAPSHOT`, and `fabric_api_version=0.161.0+26.2`; these were confirmed in the local project on 2026-11-01, not asserted as universal latest versions. Check Fabric's version selector and Maven metadata before upgrading. Follow the 26.2 example project for Loom configuration, mappings, and remapping behavior. Do not paste 1.21.x intermediary/Yarn/remap recipes into a 26.2 project without explicit evidence.

## Build workflow

1. Check `java -version`, Gradle wrapper, IDE Gradle JVM, and toolchain configuration. Ensure Java 25 is used by Gradle and run tasks.
2. Read the current Fabric example and docs for the target version. Keep dependency versions in one authoritative location.
3. Validate `fabric.mod.json`: `id`, `version`, `environment`, entrypoints, mixin config/AW references, and dependency predicates must agree with packaged files.
4. Configure Loom, repositories, source sets, datagen, and run configs using the version-appropriate template. Avoid unnecessary custom remap tasks.
5. Use the wrapper, not a machine-global Gradle. For diagnostics: `./gradlew --version`, `./gradlew tasks`, `./gradlew dependencies`, and the narrow failing task.
6. Build, inspect `build/libs`, inspect the jar contents/metadata, and calculate SHA-256 if a release artifact is copied elsewhere.

## Practices

- Pin released versions for reproducible builds. A snapshot is acceptable only when deliberately selected and documented.
- Declare dependency scope accurately: compile/runtime, optional, development-only, or bundled. Avoid duplicate embedded libraries and accidental dependency leakage.
- Keep secrets out of Gradle files and logs. Use CI secrets only for publishing credentials.
- Make CI run the wrapper, use Java 25, cache Gradle safely, and publish only the intended remapped/distributable JAR.
- Treat `fabric.mod.json` dependency ranges as compatibility policy. Test the minimum supported loader/API combination rather than using unconstrained ranges.
- For migration, update wrapper, Loom, game, Loader, API, and source incrementally; read the Fabric 26.2 porting guide and verify every API change. Do not run blind mass replacements.
- For release, verify version consistency, changelog, license/assets, JAR metadata, side behavior, and artifact checksum. Publishing is a separate action; never publish unless the user requested it.

## Example: version properties as a single source of truth

```properties
minecraft_version=26.2
loader_version=<verified-loader-version>
loom_version=<verified-loom-version>
fabric_api_version=<verified-api-version>
```

Use verified literal values in a real project; placeholders above intentionally prevent stale versions from being copied. Keep generated metadata tied to these properties through the project's established resource expansion.

## Debugging matrix

- **Wrong Java / unsupported class version:** compare shell Java, Gradle daemon JVM, IDE Gradle JVM, and CI JDK; stop stale daemons after switching.
- **Plugin not found:** inspect `pluginManagement` repositories/version and Gradle wrapper compatibility.
- **Minecraft dependency resolution failure:** verify exact coordinate exists in Fabric Maven; check network/proxy and repository declarations.
- **Mod loads in dev but not from JAR:** inspect archive for `fabric.mod.json`, entrypoint classes, mixin/AW/resource paths, and remapped names.
- **Missing refmap or AW:** align config path and metadata, then inspect Loom output and final jar; use the Mixin companion skill for injection semantics.
- **Duplicate/unsuitable artifacts:** identify classifiers and remapping task outputs; inspect manifest and mod metadata instead of selecting by filename alone.
- **CI only fails:** compare JDK, OS path case, Gradle cache, clean checkout, and environment variables; reproduce with wrapper and no local caches if useful.

## Build/release checklist

- [ ] JDK 25 and wrapper are verified for this exact 26.2 project.
- [ ] Loader, API, and Loom versions resolve from official sources.
- [ ] Metadata paths and dependency predicates match the final JAR.
- [ ] Build and package inspection succeeded; artifact identity/checksum are known.
- [ ] Release claims match checks actually run; no publish occurred without authorization.

## References

See [references/loom-sources.md](references/loom-sources.md).
