---
title: Installation
---

# Installation

## Gradle Plugin Setup

### Step 1: Add the plugin repository

In your `settings.gradle.kts`, add the plugin repository if you are using a snapshot build:

```kotlin title="settings.gradle.kts"
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // For snapshot builds:
        mavenLocal()
    }
}
```

### Step 2: Apply the plugin

In your app module's `build.gradle.kts`:

```kotlin title="app/build.gradle.kts"
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.aldefy.rebound") version "0.1.0"
}
```

That is it. The Gradle plugin handles everything else:

1. **Detects your Kotlin version** via reflection on the `kotlin` extension
2. **Adds the correct compiler artifact** to `kotlinCompilerPluginClasspath` configurations
3. **Adds `rebound-runtime`** as `debugImplementation` (when `debugOnly = true`, the default)
4. **Passes the `enabled` flag** to the compiler plugin

### Step 3 (optional): Configure

```kotlin title="app/build.gradle.kts"
rebound {
    enabled.set(true)       // default: true
    debugOnly.set(true)     // default: true -- release builds get no instrumentation
}
```

## Version Compatibility

The Kotlin compiler IR API changed between 2.1.x and 2.2.x. Rebound ships two compiler artifacts and the Gradle plugin auto-selects the correct one.

| Kotlin Version | Compiler Artifact | Auto-Selected |
|----------------|-------------------|:---:|
| 2.0.x | `rebound-compiler` | Yes |
| 2.1.x | `rebound-compiler` | Yes |
| 2.2.x+ | `rebound-compiler-kotlin-2.2` | Yes |

The `rebound-compiler-k2` module is a standalone composite build that targets the Kotlin 2.2+ IR API (where `valueParameters` became `parameters` and `putValueArgument` became `setArgumentByIndex`). You never need to configure this manually.

### Validated Apps

| App | Kotlin Version | Status |
|-----|---------------|--------|
| StickerExplode | 2.1.0 | Pass |
| HelloDistort | 2.1.0 | Pass |
| Lumen | 2.0.21 | Pass |
| Andromeda | 2.2.20 | Pass (uses rebound-compiler-k2) |

## What Gets Added to Your Build

When `debugOnly = true` (the default), the Gradle plugin adds:

- **Compiler plugin** -- added to `kotlinCompilerPluginClasspath` for debug variants only
- **Runtime** -- added as `debugImplementation "io.aldefy:rebound-runtime:$version"`

Release builds contain no Rebound code. There is zero overhead in production.

When `debugOnly = false`, both artifacts are added to all variants. This is useful for staging or QA builds where you want recomposition monitoring.
