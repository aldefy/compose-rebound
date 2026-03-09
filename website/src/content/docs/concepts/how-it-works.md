---
sidebar_position: 2
title: How It Works
---

# How It Works

Rebound operates in three phases: compile-time instrumentation, runtime tracking, and data transport to consumers.

## Phase 1: Compiler Plugin (IR Transformation)

The Rebound compiler plugin runs **after** the Compose compiler in the Kotlin IR pipeline. This ordering is critical -- the Compose compiler injects `$composer` and `$changed` parameters first, then Rebound reads them.

### What the transformer does

For every `@Composable` function in the IR tree, `ReboundIrTransformer` injects three tracking calls:

```
Original:                          Instrumented:

@Composable                        @Composable
fun MyCard(title: String) {        fun MyCard(title: String, $composer, $changed) {
    Text(title)                        ReboundTracker.onEnter("pkg.MyCard")
}                                      try {
                                           if (!$composer.skipping) {
                                               ReboundTracker.onComposition(
                                                   "pkg.MyCard",
                                                   budgetClass = LEAF,
                                                   changedMask = $changed,
                                                   paramNames = "title",
                                                   changedMasks = "$changed"
                                               )
                                               Text(title)
                                           }
                                       } finally {
                                           ReboundTracker.onExit("pkg.MyCard")
                                       }
                                   }
```

### Budget class inference

The compiler classifies each composable by examining the IR in priority order:

1. `@ReboundBudget` annotation (explicit override)
2. Function name contains "Screen" or "Page" -- SCREEN
3. Function name starts with "remember" -- LEAF
4. Body calls `LazyColumn`/`LazyRow`/`LazyGrid` -- CONTAINER
5. Body calls `animate*`/`Animation`/`Transition` -- ANIMATED
6. No child `@Composable` calls -- LEAF
7. Has child `@Composable` calls -- CONTAINER
8. Fallback -- UNKNOWN

### Anonymous lambda resolution

Compose uses lambdas extensively. Without resolution, 80% of the composable tree shows as `<anonymous>`. Rebound resolves names at compile time by walking the lambda's IR body, finding the first user-visible `@Composable` call, and using its name as the key.

A lambda whose body calls `Scaffold(...)` becomes `HomeScreen.Scaffold{}`. The `{}` suffix distinguishes content lambdas from the composable function itself.

## Phase 2: Runtime Tracking

`ReboundTracker` is a thread-safe singleton backed by `ConcurrentHashMap`. It processes three events per composable per invocation cycle:

| Event | When It Fires | What It Records |
|-------|--------------|----------------|
| `onEnter(key)` | Function entered | Increments `totalEnters`, pushes to hierarchy stack |
| `onComposition(key, ...)` | Body executed (not skipped) | Increments `totalCount`, records rate in sliding window, decodes `$changed` mask |
| `onExit(key)` | Function returned | Pops hierarchy stack (try-finally) |

### Sliding window rate calculation

The current recomposition rate is calculated using a 1-second rolling window. Each `onComposition` call records a timestamp. The rate is the count of timestamps within the most recent 1-second window. This reflects current behavior, not lifetime averages.

### Violation detection

```
if (currentRate > budget * interactionMultiplier) {
    if (now - lastViolationTime[key] > 5_seconds) {
        log warning with parameter change details
    }
}
```

Violations are throttled: maximum one violation per composable per 5 seconds.

### Composition hierarchy

`onEnter`/`onExit` maintain a thread-local stack. Each `onComposition` records its parent (the composable that called it) and its depth in the call tree. This builds the hierarchy that the IDE plugin displays.

### State invalidation tracking (Android)

On Android, `StateTracker` registers a `Snapshot.registerApplyObserver` to intercept `MutableState` changes. When a state object changes, the label is recorded and attributed to the next recomposition of composables that read that state.

## Phase 3: Socket Transport

### Why not logcat?

Logcat floods at high recomposition rates, has a buffer limit, and requires string parsing. A socket provides structured JSON data.

### Device side

`ReboundServer` binds a `LocalServerSocket("rebound")` on the Android abstract namespace. The protocol is simple:

```
Client connects -> sends command string -> server responds with JSON -> disconnect

Commands:
  "ping"       -> "pong"
  "snapshot"   -> full metrics JSON
  "summary"    -> top 10 by rate
  "telemetry"  -> anonymized aggregate stats
```

### Host side

ADB bridges the socket:

```bash
adb forward tcp:18462 localabstract:rebound
```

The IDE plugin and CLI connect to `localhost:18462`.

### Socket discovery

If the canonical `rebound` socket is taken (multiple apps using Rebound), the server falls back to `rebound_<pid>`. The IDE plugin and CLI probe `/proc/net/unix` to discover these.

## Architecture Diagram

```
                      BUILD TIME                              RUNTIME
                +-----------------------+              +-----------------------+
                |   Compose Compiler    |              |                       |
  .kt sources ->|   (runs first)        |              |   App Process         |
                |                       |              |                       |
                |   Rebound Compiler    |              |  +------------------+ |
                |   (runs after)        |--- APK ----->|  | ReboundTracker   | |
                +-----------------------+              |  |  (singleton)     | |
                                                       |  +--------+---------+ |
                +-----------------------+              |           |           |
                |   Gradle Plugin       |              |  +--------v---------+ |
                |  (wires everything)   |              |  | ReboundServer    | |
                +-----------------------+              |  | (socket)         | |
                                                       |  +--------+---------+ |
                                                       +-----------|----------+
                                                                   |
                                                       adb forward tcp:18462
                                                       localabstract:rebound
                                                                   |
                                          +------------------------+-------------+
                                          |                        |             |
                                   +------v------+         +------v------+ +----v-----+
                                   |  IDE Plugin  |         |    CLI      | |  Logcat  |
                                   |  (5 tabs)    |         | (snapshot,  | |  (warns) |
                                   |              |         |  watch)     | |          |
                                   +-------------+         +-------------+ +----------+
```

## Data Flow Summary

1. **Build:** `.kt` sources pass through the Compose compiler, then the Rebound IR transformer injects tracking calls
2. **Runtime:** Composable executes, tracking calls fire `onEnter`/`onComposition`/`onExit` into `ReboundTracker`
3. **Detect:** Rolling window rate exceeds `budget x interaction multiplier` -- violation logged
4. **Export:** `ReboundServer` responds to socket commands with JSON snapshots
5. **Display:** IDE plugin, CLI, or logcat presents the data
