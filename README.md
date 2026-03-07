# Rebound

**Compose recomposition budget monitor** — catch runaway recompositions before they ship.

Rebound is a Kotlin compiler plugin that instruments every `@Composable` function with lightweight tracking calls. At runtime, it monitors recomposition rates against per-composable budgets, detects violations, and reports them via an Android Studio tool window, CLI, or logcat. Zero config required — just apply the Gradle plugin.

## Features

- **Budget classes** — auto-classifies composables (Screen, Container, Interactive, List Item, Animated, Leaf) with appropriate rate budgets
- **Violation detection** — alerts when a composable exceeds its budget, with throttled logging to avoid noise
- **Call-tree hierarchy** — tracks parent-child composition relationships
- **Skip tracking** — monitors skip rate to identify composables that recompose without actual changes
- **Forced vs param-driven** — distinguishes parent-forced recompositions from parameter-change-driven ones
- **Dynamic budget scaling** — multiplies budgets during scrolling (2x), animation (1.5x), and user input (1.5x)
- **`@ReboundBudget` annotation** — override the inferred budget class for any composable
- **Baseline regression detection** — snapshot metrics before/after and compare for regressions
- **IDE tool window** — live tree view with detail panel in Android Studio
- **CLI** — `snapshot`, `summary`, `watch`, `ping` commands over ADB socket

## Quick Start

**1. Apply the Gradle plugin**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts (app module)
plugins {
    id("io.aldefy.rebound") version "0.1.0-SNAPSHOT"
}
```

**2. Configure (optional)**

```kotlin
rebound {
    enabled.set(true)    // default: true
    debugOnly.set(true)  // default: true — only instruments debug builds
}
```

**3. Run your app**

Rebound auto-installs on first composition. Violations appear in logcat:

```
W/Rebound: [VIOLATION] MyScreen — 8 recomp/s (budget: 3, class: SCREEN)
```

Connect the IDE plugin or CLI for richer output.

## Architecture

```
┌─────────────────┐     ┌──────────────────┐
│ rebound-compiler │────▶│  rebound-runtime  │
│  (IR transform)  │     │  (tracking core)  │
└─────────────────┘     └────────┬─────────┘
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼             ▼
              ┌──────────┐ ┌──────────┐ ┌──────────┐
              │   IDE    │ │   CLI    │ │  Logcat  │
              │ (socket) │ │ (socket) │ │ (warns)  │
              └──────────┘ └──────────┘ └──────────┘
```

The compiler plugin injects `ReboundTracker.onCompositionEnter/Exit` calls into every `@Composable` function at the IR level. The runtime tracks rates per sliding window and reports violations. The IDE plugin and CLI connect via `LocalServerSocket("rebound")` forwarded through ADB.

## Modules

| Module | Description |
|--------|-------------|
| `rebound-runtime` | KMP runtime — Android, JVM, iOS (arm64, x64, simulatorArm64) |
| `rebound-compiler` | Kotlin compiler plugin for Kotlin 2.0.x–2.1.x |
| `rebound-compiler-k2` | Kotlin compiler plugin for Kotlin 2.2+ |
| `rebound-gradle` | Gradle plugin — auto-wires compiler + runtime, selects correct artifact |
| `rebound-ide` | Android Studio plugin — live tree view + detail panel |
| `sample` | Sample Android app |

## Configuration

```kotlin
rebound {
    enabled.set(true)    // Master switch — set false to disable all instrumentation
    debugOnly.set(true)  // Only instrument debug builds (release builds get no overhead)
}
```

Runtime toggles:

```kotlin
ReboundTracker.enabled = true            // Master on/off at runtime
ReboundTracker.logCompositions = false   // Per-composition logcat (throttled 1/s per composable)
```

## Budget Classes

Each composable is auto-classified by IR heuristics:

| Budget Class | Rate/sec | Heuristic |
|-------------|----------|-----------|
| `SCREEN` | 3 | Name contains `Screen` or `Page` |
| `CONTAINER` | 10 | Has child `@Composable` calls |
| `INTERACTIVE` | 30 | Default for unclassified |
| `LIST_ITEM` | 60 | Inside `LazyColumn`/`LazyRow`/`LazyGrid` |
| `ANIMATED` | 120 | Calls `animate*`/`Transition`/`Animation` APIs |
| `LEAF` | 5 | No child `@Composable` calls |

Override with the annotation:

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun PhysicsSticker(offset: Offset) { ... }
```

## IDE Plugin

The Android Studio plugin (targets 2024.2.1.3+) provides:

- **Tree view** — composable hierarchy with live recomposition counts
- **Detail panel** — rate, skip%, forced vs param-driven, budget class, violation status
- **Socket transport** — connects via `adb forward tcp:18462 localabstract:rebound`

Install from the `rebound-ide` build output.

## CLI

Setup:

```bash
adb forward tcp:18462 localabstract:rebound
```

Commands:

```bash
./rebound-cli.sh snapshot   # Full JSON metrics for all tracked composables
./rebound-cli.sh summary    # Top 10 composables by recomposition rate
./rebound-cli.sh watch      # Live updates every 1 second
./rebound-cli.sh ping       # Health check → "pong"
```

Or query directly:

```bash
echo "snapshot" | nc localhost 18462
```

## Kotlin Version Support

The Gradle plugin auto-selects the correct compiler artifact:

| Kotlin Version | Compiler Artifact |
|---------------|-------------------|
| 2.0.x | `rebound-compiler` |
| 2.1.x | `rebound-compiler` |
| 2.2.x+ | `rebound-compiler-kotlin-2.2` |

No manual configuration needed.

## License

```
Copyright 2025 Adit Lal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
