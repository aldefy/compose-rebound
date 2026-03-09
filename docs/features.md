# Rebound Features

## Core: Context-Aware Recomposition Budgets

Every `@Composable` function gets a **budget** — a maximum recomposition rate (per second) based on what it does:

| Budget Class | Rate Limit | What It Covers |
|-------------|-----------|----------------|
| `SCREEN` | 3/s | Full screens — `HomeScreen`, `ProfilePage` |
| `LEAF` | 5/s | Terminal composables — `Text`, `Icon`, `remember*` |
| `CONTAINER` | 10/s | Layout wrappers — `Column`, `Box`, `Scaffold` |
| `INTERACTIVE` | 30/s | User input — buttons, text fields |
| `LIST_ITEM` | 60/s | Recycled items in `LazyColumn`/`LazyRow` |
| `ANIMATED` | 120/s | Animation-driven — `animate*`, `Transition` |

A `DraggableSticker` at 10 recompositions/second is fine (ANIMATED budget = 120). A `HomeScreen` at 10/s is a problem (SCREEN budget = 3). Flat thresholds can't tell the difference. Rebound can.

### Automatic Classification

The compiler plugin infers budget class from IR structure — no annotations needed:

- Function named `*Screen` or `*Page` → SCREEN
- Body calls `animate*`/`Transition` → ANIMATED
- Body contains `LazyColumn`/`LazyRow` → CONTAINER
- No child `@Composable` calls → LEAF

### Manual Override

When heuristics get it wrong:

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun PhysicsSticker(offset: Offset) { ... }
```

## Violation Detection

Rebound alerts when a composable exceeds its budget:

```
W/Rebound: [VIOLATION] MyScreen — 8 recomp/s (budget: 3, class: SCREEN)
  → params: items=DIFFERENT, query=SAME
```

### Throttled

One violation per composable per 5 seconds. No logcat flooding.

### Interaction-Aware

Budgets scale during active interaction:

| Context | Multiplier | Detection |
|---------|-----------|-----------|
| Idle | 1.0x | Default |
| Scrolling | 2.0x | LIST_ITEM rate > 20/s |
| Animating | 1.5x | ANIMATED rate > 30/s |
| User Input | 1.5x | INTERACTIVE rate > 10/s |

A composable inside a fast-scrolling `LazyColumn` gets 2x its budget before triggering.

## Parameter Change Tracking (`$changed` Decode)

Rebound decodes the Compose compiler's `$changed` bitmask to tell you *which parameters* caused a recomposition:

```
offset=DIFFERENT, scale=DIFFERENT, label=SAME
```

3 bits per parameter: `UNCERTAIN`, `SAME`, `DIFFERENT`, `STATIC`. Supports multi-mask for >10 parameters.

### Forced vs Param-Driven

- **Forced**: Parent invalidated → all children re-execute (bit 0 of `$changed`)
- **Param-driven**: A specific parameter changed → targeted recomposition

Knowing which type helps you fix the right thing: forced means the parent is the problem, param-driven means the parameter stability is the problem.

## Skip Rate Tracking

```
StickerCanvas: 45 enters, 12 compositions → 73% skip rate
```

The compiler plugin injects `onEnter()` at function entry (always fires) and `onComposition()` only when the body executes (not skipped). The difference = skip rate.

- **High skip rate (>80%)** — Compose is doing its job, parameters are stable
- **Low skip rate (<20%)** — Something is forcing the body to re-execute every time

## Composition Hierarchy

Rebound tracks the parent-child call tree via a thread-local stack. Each composable records who called it and its depth. The IDE plugin renders this as a live tree.

## State Invalidation Tracking

On Android, Rebound hooks into `Snapshot.registerApplyObserver` to intercept `MutableState` changes. When a state object changes, Rebound attributes it to the composable that reads it:

```
HomeScreen: invalidated by "searchQuery" state change
```

## Three Output Channels

### 1. IDE Tool Window (Android Studio)

Live tree view with:
- Composable hierarchy reflecting actual call structure
- Per-composable: rate, budget, skip%, forced count, violation status
- Color coding: red = violation, green = OK, orange = forced, cyan = state-driven
- Start/Stop/Clear controls

Connects via socket: `adb forward tcp:18462 localabstract:rebound`

Targets Android Studio 2024.2.1.3+ (Hedgehog through Panda 2).

### 2. CLI

```bash
./rebound-cli.sh snapshot   # Full JSON metrics
./rebound-cli.sh summary    # Top 10 by rate + violation count
./rebound-cli.sh watch      # Live refresh every 1s
./rebound-cli.sh ping       # Health check
```

Auto-discovers sockets, pretty-prints JSON.

### 3. Logcat

Budget violations appear as warnings (`W/Rebound`). Per-composition logging available but throttled to 1 log/s/composable:

```kotlin
ReboundTracker.logCompositions = true
```

## Baseline Regression Detection

Snapshot metrics at any point, save to JSON, compare later:

```kotlin
// Capture baseline
val baseline = ReboundTracker.exportSnapshot()
File("rebound-baseline.json").writeText(baseline.toJson())

// Compare after changes
val current = ReboundTracker.exportSnapshot()
val baseline = ReboundSnapshot.fromJson(savedJson)
val regressions = ReboundBaseline.compare(baseline, current, threshold = 20)
// Returns: composables where rate increased > 20%
```

Designed for CI integration — fail the build if recomposition rates regress.

## Antipattern Benchmark

Rebound ships with a test suite that proves detection of real Compose antipatterns:

### Synthetic Tests (6)
| Test | Signal |
|------|--------|
| Unstable list parameter | totalCount > 50 on LEAF |
| Backward write loop | SCREEN budget tracked |
| Unstable lambda parameter | totalEnters > 50 |
| Sensor spam (60fps updates) | totalCount > 50 on LEAF |
| Stable animation (no false positive) | totalCount < 360 on ANIMATED |
| High skip rate (stable params) | skipRate >= 80% |

### Real-World Antipatterns (6)
From [compose-patterns-playground](https://github.com/aldefy/compose-patterns-playground) (Droidcon India 2025 workshop):

| Antipattern | Broken Signal | Fixed Signal |
|------------|---------------|--------------|
| AP02: Derived state misuse | totalCount > 50 (filter reruns on unrelated state) | Isolated from unrelated changes |
| AP03: Unstable lambda captures | totalCount > 25 (all items recompose) | skipRate >= 80% |
| AP05: Side effect in composition | totalCount > 10 + side effect fires every recomp | Side effect scoped to LaunchedEffect |
| AP07: State read too high | totalCount > 50 (child recomps on every volume change) | totalCount = 1 (only initial) |

All 12 tests pass on CI. Run with:

```bash
./gradlew :sample:connectedDebugAndroidTest
```

## Zero Config Setup

```kotlin
// build.gradle.kts
plugins {
    id("io.aldefy.rebound") version "0.1.0"
}
```

That's it. The Gradle plugin:
1. Detects your Kotlin version (2.0.x through 2.2+)
2. Adds the correct compiler artifact
3. Adds `rebound-runtime` as `debugImplementation`
4. Passes the `enabled` flag to the compiler

No annotations to add. No composables to wrap. No code changes.

## Debug-Only by Default

```kotlin
rebound {
    debugOnly.set(true)  // default — release builds get zero overhead
}
```

The Gradle plugin only adds the compiler artifact and runtime to debug configurations. Release APKs have no Rebound code.

## KMP Support

The runtime works on:
- Android
- JVM (Desktop)
- iOS (arm64, x64, simulator arm64)
- Wasm (JS)

Socket transport and state tracking are Android-specific. Core metrics collection works everywhere.

## Kotlin Version Support

| Kotlin | Compiler Artifact | Auto-Selected |
|--------|-------------------|:---:|
| 2.0.x | `rebound-compiler` | yes |
| 2.1.x | `rebound-compiler` | yes |
| 2.2.x+ | `rebound-compiler-kotlin-2.2` | yes |

The Gradle plugin resolves the version at apply time. No manual configuration.

## Telemetry (Opt-In)

Anonymized aggregate stats — no function names, no identifiers:

```kotlin
ReboundTelemetry.enabled = true  // disabled by default
```

Collects: budget class distributions, average/peak rates, skip rates, violation counts. Useful for empirical calibration of budget numbers.
