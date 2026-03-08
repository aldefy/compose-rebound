---
sidebar_position: 1
title: Architecture
---

# Architecture

Rebound consists of five modules that span compile time, runtime, and developer tooling.

## Module Map

```
compose-rebound/
  rebound-compiler/        Kotlin IR compiler plugin (Kotlin 2.0.x-2.1.x)
  rebound-compiler-k2/     Kotlin IR compiler plugin (Kotlin 2.2+, composite build)
  rebound-runtime/         KMP runtime (Android, JVM, iOS arm64/x64/sim, Wasm)
  rebound-gradle/          Gradle plugin (auto-wires compiler + runtime)
  rebound-ide/             Android Studio tool window plugin
  rebound-cli.sh           CLI over ADB socket
  sample/                  Sample app + instrumented test suite
```

### rebound-compiler

The Kotlin compiler plugin that runs after the Compose compiler in the IR pipeline. Entry point: `ReboundCompilerPluginRegistrar` registers `ReboundIrGenerationExtension`, which applies `ReboundIrTransformer` to every file.

The transformer:

1. Visits every function in the IR tree
2. Identifies `@Composable` functions (those with a `$composer` parameter)
3. Infers a budget class from function name and call tree structure
4. Resolves human-readable keys for anonymous lambdas
5. Injects `onEnter`, `onComposition`, and `onExit` tracking calls

### rebound-compiler-k2

A standalone composite build targeting Kotlin 2.2+. The Kotlin 2.2 release changed several IR API method names (`valueParameters` to `parameters`, `putValueArgument` to `setArgumentByIndex`). This module contains the same transformation logic adapted to the new API surface.

### rebound-runtime

The KMP runtime library. Core components:

- **ReboundTracker** -- thread-safe singleton (`ConcurrentHashMap`) that processes tracking events, calculates rates, and detects violations
- **ComposableMetrics** -- per-composable data: rate, peak, skip rate, forced count, param-driven count
- **ReboundServer** (Android) -- `LocalServerSocket` serving JSON snapshots
- **StateTracker** (Android) -- `Snapshot.registerApplyObserver` for state invalidation tracking
- **ReboundSnapshot** -- JSON serialization for baseline comparison
- **ChangedMaskDecoder** -- decodes `$changed` bitmasks into per-parameter state

No external dependencies. JSON serialization is hand-written to avoid pulling in Gson, Moshi, or kotlinx.serialization.

### rebound-gradle

The Gradle plugin that wires everything together at apply time:

1. Detects the project's Kotlin version via reflection on the `kotlin` extension
2. Adds the correct compiler artifact (`rebound-compiler` or `rebound-compiler-kotlin-2.2`) to `kotlinCompilerPluginClasspath` configurations
3. Adds `rebound-runtime` as `debugImplementation` (when `debugOnly = true`) or `implementation`
4. Passes the `enabled` flag to the compiler via `-P plugin:io.aldefy.rebound:enabled=<bool>`

### rebound-ide

The Android Studio plugin built with IntelliJ Platform Gradle Plugin 2.2.1. Architecture:

```
ReboundToolWindowFactory
  +-- SessionStore (shared data layer)
  +-- ReboundConnection (ADB forward + socket polling)
  +-- Tab 1: Monitor (tree + sparkline + event log)
  +-- Tab 2: Hot Spots (sortable flat table)
  +-- Tab 3: Timeline (composable x time heatmap)
  +-- Tab 4: Stability (param matrix + cascade tree)
  +-- Tab 5: History (session persistence + VCS comparison)
  +-- Editor: gutter icons + inlay hints + status bar widget
```

Tabs are lazy-initialized -- only created when first selected. One socket connection serves all tabs via the observer pattern.

## Data Flow

```
1. BUILD:    .kt -> Compose compiler -> Rebound IR transformer -> instrumented .kt
2. RUNTIME:  Composable executes -> onEnter/onComposition/onExit -> ReboundTracker
3. DETECT:   Rolling window rate > budget x interaction multiplier -> violation logged
4. EXPORT:   ReboundServer (socket) -> JSON snapshot
5. DISPLAY:  IDE plugin / CLI / logcat
```

### IR Pipeline Detail

The Compose compiler runs first and transforms `@Composable` functions by injecting `$composer` and `$changed` parameters, wrapping the body in `startRestartGroup`/`endRestartGroup`, and inserting skip logic.

Rebound runs after this transformation. It reads the `$composer` and `$changed` parameters that the Compose compiler already inserted. This ordering is enforced by the Kotlin compiler plugin registration mechanism -- Rebound registers at a lower priority.

### Socket Protocol

```
Client connects to localhost:18462
  -> sends command string ("ping", "snapshot", "summary", "telemetry")
  -> server responds with JSON
  -> connection closes

Each request is a fresh TCP connection (no persistent connections).
```

The ADB bridge: `adb forward tcp:18462 localabstract:rebound`

## Design Principles

| Principle | Implementation |
|-----------|---------------|
| Zero config | Gradle plugin auto-wires everything; IR heuristics classify composables |
| Budget = f(role) | 7 budget classes, not flat thresholds |
| Debug-only | No instrumentation in release builds by default |
| No external deps | Hand-written JSON, no Gson/Moshi/kotlinx |
| Throttled output | 1 violation per composable per 5s; logcat throttled to 1/s/composable |
| KMP | Runtime works on Android, JVM, iOS, Wasm |
| Kotlin version agnostic | Auto-selects correct compiler artifact for 2.0.x through 2.2+ |
