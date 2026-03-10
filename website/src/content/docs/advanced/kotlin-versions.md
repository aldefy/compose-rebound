---
title: Kotlin Version Support
---

# Kotlin Version Support

Rebound supports Kotlin 2.0.x through 2.2.x and beyond. The Gradle plugin automatically selects the correct compiler artifact based on your project's Kotlin version.

## Version Matrix

| Kotlin Version | Compiler Artifact | IR API | Auto-Selected |
|----------------|-------------------|--------|:---:|
| 2.0.x | `rebound-compiler` | `valueParameters`, `putValueArgument` | Yes |
| 2.1.x | `rebound-compiler` | `valueParameters`, `putValueArgument` | Yes |
| 2.2.x+ | `rebound-compiler-kotlin-2.2` | `parameters`, `setArgumentByIndex` | Yes |

## Why Two Compiler Artifacts?

The Kotlin 2.2 release introduced breaking changes to the Kotlin compiler's IR API:

- `IrFunction.valueParameters` was renamed to `IrFunction.parameters`
- `IrMemberAccessExpression.putValueArgument()` was renamed to `setArgumentByIndex()`
- Several other accessor methods were renamed or restructured

These are source-incompatible changes. A single compiler plugin artifact cannot compile against both the 2.1.x and 2.2.x APIs. Rebound maintains two separate modules:

### rebound-compiler

Located at `rebound-compiler/` in the repository. Compiles against the Kotlin 2.0.x/2.1.x compiler API. Contains the full IR transformation logic using the pre-2.2 API surface.

### rebound-compiler-k2

Located at `rebound-compiler-k2/` as a standalone composite build. Contains the same transformation logic adapted to the Kotlin 2.2+ API surface. It is a composite build so it can declare its own Kotlin 2.2 dependency without forcing the rest of the project to use 2.2.

## How Auto-Selection Works

The Gradle plugin detects the Kotlin version at apply time:

```kotlin
// Simplified from ReboundGradlePlugin
val kotlinVersion = project.extensions
    .findByType(KotlinProjectExtension::class.java)
    ?.coreLibrariesVersion

val compilerArtifact = if (kotlinVersion != null &&
    compareVersions(kotlinVersion, "2.2.0") >= 0) {
    "io.aldefy:rebound-compiler-kotlin-2.2:$reboundVersion"
} else {
    "io.aldefy:rebound-compiler:$reboundVersion"
}
```

The correct artifact is then added to the `kotlinCompilerPluginClasspath` configuration for the appropriate build variants.

## Manual Override

In rare cases where auto-detection fails (e.g., custom Kotlin distributions), you can force the compiler artifact:

```kotlin title="build.gradle.kts"
dependencies {
    kotlinCompilerPluginClasspath("io.aldefy:rebound-compiler-kotlin-2.2:0.1.0")
}
```

This bypasses the Gradle plugin's auto-selection. You must also add the runtime dependency manually:

```kotlin
dependencies {
    debugImplementation("io.aldefy:rebound-runtime:0.1.0")
}
```

## Testing Across Versions

Rebound is validated against multiple Kotlin versions in the CI pipeline and on real apps:

| App | Kotlin | Compose Compiler | Status |
|-----|--------|-----------------|--------|
| StickerExplode | 2.1.0 | 1.5.x | Pass |
| HelloDistort | 2.1.0 | 1.5.x | Pass |
| Lumen | 2.0.21 | 1.5.x | Pass |
| Andromeda | 2.2.20 | 2.0.x | Pass |

If you encounter issues with a specific Kotlin version, open an issue on GitHub with the version details and build output.
