---
sidebar_position: 4
title: KMP Support
---

# Kotlin Multiplatform Support

The Rebound runtime is a Kotlin Multiplatform (KMP) library. The compiler plugin instruments composable functions regardless of target platform.

## Supported Targets

| Target | Status | Notes |
|--------|--------|-------|
| **Android** | Full support | Socket transport, state tracking, logcat output |
| **JVM (Desktop)** | Core metrics | Rate tracking, violation detection. No socket transport. |
| **iOS arm64** | Core metrics | Rate tracking, violation detection |
| **iOS x64** | Core metrics | Rate tracking, violation detection |
| **iOS Simulator arm64** | Core metrics | Rate tracking, violation detection |
| **Wasm (JS)** | Core metrics | Rate tracking, violation detection |

## What Works Everywhere

The following features are implemented in `commonMain` and work on all targets:

- **Budget class assignment** -- composables are classified at compile time, which is platform-independent
- **Recomposition rate tracking** -- the sliding window rate calculation uses `kotlinx.datetime` or platform time APIs
- **Violation detection** -- budget comparison logic is pure Kotlin
- **Skip rate tracking** -- `onEnter`/`onComposition` counting is platform-independent
- **`$changed` bitmask decoding** -- pure bit manipulation, no platform dependencies
- **Baseline snapshot comparison** -- JSON export and diff logic

## Android-Specific Features

The following features are available only on Android:

- **Socket transport** (`ReboundServer`) -- uses `android.net.LocalServerSocket` for Unix domain socket communication
- **State invalidation tracking** (`StateTracker`) -- uses `androidx.compose.runtime.Snapshot.registerApplyObserver`
- **Logcat output** -- uses `android.util.Log` for violation warnings
- **IDE plugin connectivity** -- the IDE plugin connects via ADB forward, which requires an Android device

## Platform Considerations

### Desktop (JVM)

On Desktop Compose, Rebound tracks metrics but has no built-in transport to export them. You can access metrics programmatically:

```kotlin
// Access metrics directly in your Desktop app
val snapshot = ReboundTracker.exportSnapshot()
println(snapshot.toJson())
```

Future work may add a local TCP server for Desktop targets.

### iOS

On iOS, the compiler plugin instruments composables in Compose Multiplatform shared code. Metrics are tracked at runtime. Export requires programmatic access via `ReboundTracker.exportSnapshot()`.

### Wasm

The Wasm target supports core metrics. Browser-based transport (e.g., WebSocket) is not yet implemented.

## Gradle Configuration for KMP

The Rebound Gradle plugin works with KMP projects. Apply it to the module that contains your composable code:

```kotlin title="shared/build.gradle.kts"
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.aldefy.rebound") version "0.1.0-SNAPSHOT"
}
```

The plugin adds the compiler artifact to all Kotlin compilation tasks and the runtime to the appropriate source sets.
