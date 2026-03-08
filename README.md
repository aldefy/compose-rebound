# Rebound

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.x%E2%80%932.2.x-purple.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20JVM%20%7C%20iOS%20%7C%20Wasm-green.svg)](https://kotlinlang.org/docs/multiplatform.html)

**Compose recomposition budget monitor** — catch runaway recompositions before they ship.

Rebound is a Kotlin compiler plugin that instruments every `@Composable` function with lightweight tracking calls. At runtime, it monitors recomposition rates against per-composable budgets, detects violations, and reports them via an Android Studio tool window, CLI, or logcat. Zero config required — just apply the Gradle plugin. The IDE plugin provides a 5-tab performance cockpit with live monitoring, hot spots ranking, timeline heatmap, stability analysis, and session history with VCS correlation.

![Rebound IDE plugin](assets/rebound-ide-walkthrough.gif)

## Features

- **Budget classes** — auto-classifies composables (Screen, Container, Interactive, List Item, Animated, Leaf) with appropriate rate budgets
- **Violation detection** — alerts when a composable exceeds its budget, with throttled logging to avoid noise
- **Call-tree hierarchy** — tracks parent-child composition relationships
- **Skip tracking** — monitors skip rate to identify composables that recompose without actual changes
- **Forced vs param-driven** — distinguishes parent-forced recompositions from parameter-change-driven ones
- **Dynamic budget scaling** — multiplies budgets during scrolling (2x), animation (1.5x), and user input (1.5x)
- **`@ReboundBudget` annotation** — override the inferred budget class for any composable
- **Baseline regression detection** — snapshot metrics before/after and compare for regressions
- **IDE tool window** — 5-tab performance cockpit (Monitor, Hot Spots, Timeline, Stability, History) with editor integration (gutter icons, CodeVision inlays, status bar widget) and session persistence
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
| `rebound-compiler` | Kotlin compiler plugin for Kotlin 2.0.x-2.1.x |
| `rebound-compiler-k2` | Kotlin compiler plugin for Kotlin 2.2+ |
| `rebound-gradle` | Gradle plugin — auto-wires compiler + runtime, selects correct artifact |
| `rebound-ide` | Android Studio plugin — 5-tab performance cockpit with editor integration |
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

### Detailed Legend

#### SCREEN (3/s)

- **What:** Full-screen composables — the root of navigation destinations.
- **Heuristic:** Name contains `Screen` or `Page`.
- **Why 3/s:** Screens should only recompose on navigation events or major state changes. If a screen recomposes more than 3 times per second, state is leaking upward.
- **Example violation:** Reading a frequently-changing state (e.g., scroll position, animation progress) at screen level instead of hoisting it down.
- **Fix:** Hoist the changing state to the child that needs it. Use `derivedStateOf` or move reads into a smaller scope.

#### CONTAINER (10/s)

- **What:** Layout composables with child `@Composable` calls — Column, Row, Box, Scaffold content slots.
- **Heuristic:** Has child `@Composable` calls in its body.
- **Why 10/s:** Containers recompose when children's layout changes. Moderate rate expected, but sustained high rates indicate unnecessary invalidation.
- **Example:** A Column that recomposes because a child's size changed or an unstable lambda is being passed.

#### INTERACTIVE (30/s)

- **What:** Composables responding to user input — buttons, text fields, sliders.
- **Heuristic:** Default for unclassified composables.
- **Why 30/s:** Users type and tap fast. Input-driven composables need headroom for responsive UX.

#### LIST_ITEM (60/s)

- **What:** Items inside LazyColumn, LazyRow, LazyGrid.
- **Heuristic:** Inside a lazy layout scope.
- **Why 60/s:** During fast scroll, items are recycled at up to 60fps. One recomposition per frame is expected.

#### ANIMATED (120/s)

- **What:** Composables driven by animation APIs.
- **Heuristic:** Calls `animate*`, `Transition`, `Animation`, or `Animatable` APIs.
- **Why 120/s:** Animations target 60-120fps. This budget gives room for the animation to run without false alarms.

#### LEAF (5/s)

- **What:** Terminal composables with no child `@Composable` calls — `Text()`, `Icon()`, `Image()`.
- **Heuristic:** No child `@Composable` calls in the function body.
- **Why 5/s:** Individually cheap but shouldn't thrash. If a leaf recomposes >5/s, something upstream is pushing unnecessary state changes.

### Color Coding

| Color | Condition | Status |
|-------|-----------|--------|
| Red | rate > budget | OVER |
| Yellow | rate > 70% of budget | NEAR |
| Green | rate <= 70% of budget | OK |
| Gray | not actively recomposing | — |

### Dynamic Scaling

| Interaction State | Multiplier | Effect |
|---|---|---|
| IDLE | 1x | Normal budgets |
| SCROLLING | 2x | Budgets doubled during scroll |
| ANIMATING | 1.5x | Budgets increased during animation |
| USER_INPUT | 1.5x | Budgets increased during input |

### Override with @ReboundBudget

```kotlin
// This composable uses tilt sensor — it's not a leaf, it's animated
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun TiltDrivenSticker(offset: Offset) { ... }
```

## IDE Plugin

The Android Studio plugin (targets 2024.2.1.3+) provides a 5-tab performance cockpit, editor integration, and session persistence. Configure via Preferences > Tools > Rebound.

### Tabs

**Monitor** — Live composable tree with sparkline rate history per node. Scrolling event log at the bottom shows recomposition events, violations, and state changes in real time.

**Hot Spots** — Sortable flat table of all tracked composables, ranked by severity (OVER > NEAR > OK). Summary card at the top shows violation/warning/OK counts at a glance. Click any row to jump to source.

**Timeline** — Composable x time heatmap. Each cell is colored green/yellow/red based on budget status at that moment. Scroll back up to 60 minutes. Useful for correlating recomposition spikes with user interactions.

**Stability** — Parameter stability matrix showing SAME/DIFFERENT/STATIC/UNCERTAIN status per parameter for each composable. Cascade impact tree visualizes how unstable parameters propagate recompositions through the hierarchy.

**History** — Saved sessions stored in `.rebound/sessions/`. Each session is VCS-tagged with branch name and commit hash. Side-by-side comparison view for before/after regression analysis.

### Editor Integration

- **Gutter icons** — Red, yellow, or green dots next to `@Composable` function declarations, reflecting live budget status.
- **CodeVision** — Inline hints above each composable function: `> 12/s | budget: 8/s | OVER | skip: 45%`.
- **Status bar** — Persistent widget at the bottom of the IDE: `Rebound: 45 composables | 3 violations`.

### Connection

The plugin connects via `adb forward tcp:18462 localabstract:rebound`. Install from the `rebound-ide` build output.

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

## Roadmap

- [ ] CI budget gates — fail builds when recomposition budgets regress
- [ ] Flame chart mode in Timeline tab
- [ ] JetBrains Marketplace publication
- [ ] Session export/import for team sharing
- [ ] Baseline snapshots for regression testing
- [ ] ComposeProof integration for LLM-driven analysis

## Documentation

Full documentation at [aldefy.github.io/compose-rebound](https://aldefy.github.io/compose-rebound/).

## Contributing

```bash
# Build all modules
./gradlew build

# Build IDE plugin
./gradlew :rebound-ide:buildPlugin
# Output: rebound-ide/build/distributions/rebound-ide-0.1.0.zip

# Run sample app
./gradlew :sample:installDebug
```

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
