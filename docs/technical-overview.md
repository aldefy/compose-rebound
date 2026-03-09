# Rebound: Why Flat Recomposition Thresholds Are Wrong, and What to Do Instead

*By Adit Lal ([@aldefy](https://github.com/aldefy)) -- March 2026*

---

Compose recomposition is invisible. Your composables silently re-execute dozens of times per second, and you have no idea until your app starts dropping frames. By then, the damage is done -- the problem is buried somewhere in a tree of hundreds of composables, and you are left with Layout Inspector and a prayer.

I built **Rebound** because every existing tool for tracking recompositions makes the same fundamental mistake: they treat all composables the same. A screen-level composable recomposing 10 times per second is alarming. An animated sticker recomposing 10 times per second is perfectly fine. The *same rate* means *different things* depending on the composable's role.

No existing tool accounts for this. Rebound does.

## The Gap in the Compose Toolchain

The Compose team deliberately publishes **no official recomposition budgets or numeric thresholds**. Their guidance is principle-based: "minimize unnecessary recompositions," "use stable types," "hoist state appropriately." This is good advice, but it does not tell you whether 15 recompositions per second for your `ProfileScreen` is a problem or not.

The community has tried to fill this gap:

- **Layout Inspector** (Android Studio): Shows recomposition counts and, as of 2025, highlights *why* recompositions happen. But it is manual, cannot run in CI, and provides no thresholds -- it is a diagnostic tool, not a monitoring tool.
- **compose-stability-analyzer** (by Skydoves): Introduces flat count thresholds -- Green below 10, Yellow between 10-50, Red above 50. A meaningful step forward, but the thresholds are context-free. A `DraggableSticker` at 12 recompositions triggers a warning it does not deserve.
- **vkompose** (by VK): Colored borders on recomposing composables plus a logger. Visually useful, but no budget system and no threshold-based alerting.
- **rebugger**: Tracks which arguments changed between recompositions. Valuable for the "why" question, but it skips recompositions entirely and can be unreliable.
- **Compose compiler reports**: Excellent for static stability and skippability analysis, but they tell you nothing about runtime behavior.

Each of these tools answers a piece of the puzzle. None of them answer the question: *"Is this composable recomposing too much for what it does?"*

## The Insight: Budget = f(ComposableRole)

Consider two composables, both recomposing at 10 times per second:

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    // Full screen. Recomposes on navigation and major state changes.
    // 10/s? Something is very wrong.
}

@Composable
fun DraggableSticker(offset: Offset, rotation: Float, scale: Float) {
    // Animated. Driven by gesture and physics simulation.
    // 10/s? Completely normal -- even low for a drag interaction.
}
```

A flat threshold of 10 recompositions/second flags `HomeScreen` correctly and `DraggableSticker` incorrectly. Or you raise the threshold to avoid the false positive, and then `HomeScreen` slips through.

The fix is obvious in hindsight: **the budget should depend on what the composable does**. A screen has a low budget. An animation-driven composable has a high budget. A list item sitting inside a `LazyColumn` has a moderate-to-high budget because recycling causes frequent recomposition during scroll.

This is Rebound's core idea.

## How Compose's `$changed` Actually Works

Before diving into Rebound's architecture, it is worth understanding the mechanism it hooks into. Every `@Composable` function gets two invisible parameters injected by the Compose compiler: `$composer` and `$changed`.

`$changed` is a bitmask that encodes what the compiler knows about each parameter's stability at the call site. It uses **3 bits per parameter**:

| Bits    | Meaning   | Description |
|---------|-----------|-------------|
| `0b000` | Uncertain | Runtime must check `equals()` |
| `0b001` | Same      | Compiler proved this value has not changed |
| `0b010` | Different | Compiler proved this value *has* changed |
| `0b100` | Static    | Compile-time constant -- will never change |

Bit 0 of the entire mask is a special **force-recompose flag** -- when a parent invalidates, it forces all children to re-execute regardless of parameter state.

The critical optimization: if *all* parameters are `Same` and all types are `Stable`, the composable **skips entirely**. Zero cost. The function body never runs. This is why stability matters so much in Compose -- it is the difference between a composable executing and not executing at all.

For functions with more than 10 parameters, the compiler generates `$changed1`, `$changed2`, and so on. Rebound currently reads the first `$changed` mask, which covers the vast majority of real-world composables.

Rebound decodes this mask at runtime to tell you not just *that* a recomposition happened, but *which parameters changed*:

```kotlin
// From ChangedMaskDecoder.kt
fun decode(changedMask: Int, paramNames: String): List<Pair<String, ParamState>> {
    val names = paramNames.split(",")
    return names.mapIndexed { index, name ->
        val shift = (index + 1) * 3  // bit 0 is force flag, 3 bits per slot
        val bits = (changedMask ushr shift) and 0b111
        val state = when {
            bits and 0b100 != 0 -> ParamState.STATIC
            bits and 0b010 != 0 -> ParamState.DIFFERENT
            bits and 0b001 != 0 -> ParamState.SAME
            else -> ParamState.UNCERTAIN
        }
        name.trim() to state
    }
}
```

When Rebound logs a budget violation, you see output like `offset=CHANGED, scale=CHANGED` -- immediately pointing you at the parameters driving the excessive recomposition.

## Architecture: Three Modules, Zero Config

Rebound is a Kotlin Multiplatform project with three modules:

```
rebound-compiler   — Kotlin IR compiler plugin (runs after Compose compiler)
rebound-runtime    — KMP runtime library (Android, JVM, iOS, Wasm)
rebound-gradle     — Gradle plugin for zero-config setup
```

### The IR Transformer

The compiler plugin is where the work happens. `ReboundIrTransformer` runs in the Kotlin IR (Intermediate Representation) pipeline, *after* the Compose compiler has already done its work. It visits every function in the IR tree, checks for the `@Composable` annotation, and injects a tracking call at the top of the function body:

```kotlin
override fun visitFunction(declaration: IrFunction): IrStatement {
    val function = super.visitFunction(declaration) as IrFunction

    // Only instrument @Composable functions
    if (!function.hasAnnotation(composableAnnotation)) return function

    val body = function.body as? IrBlockBody ?: return function

    // Collect real parameter names (exclude $composer, $changed, $default, $dirty)
    val userParams = function.valueParameters.filter { param ->
        !param.name.asString().startsWith("$")
    }
    val paramNames = userParams.joinToString(",") { it.name.asString() }

    // Find the $changed parameter injected by Compose compiler
    val changedParam = function.valueParameters.firstOrNull {
        it.name.asString() == "\$changed"
    }

    // Infer budget class from IR structure
    val budgetClassOrdinal = inferBudgetClass(function)

    // Inject: ReboundTracker.onComposition(key, budgetClass, changedMask, paramNames)
    val trackCall = builder.irCall(trackerFn).apply {
        putValueArgument(0, builder.irString(fqName))
        putValueArgument(1, builder.irInt(budgetClassOrdinal))
        putValueArgument(2,
            if (changedParam != null) builder.irGet(changedParam) else builder.irInt(0))
        putValueArgument(3, builder.irString(paramNames))
    }
    body.statements.add(0, trackCall)
    return function
}
```

The key detail: the budget class is **inferred at compile time** from the IR structure. No annotations required. No configuration needed.

### Budget Class Inference

The `inferBudgetClass` function analyzes each composable's name and body to assign a budget:

```
SCREEN(3/s)       — Name contains "Screen" or "Page"
LEAF(5/s)         — Name starts with "remember", or no child @Composable calls
CONTAINER(10/s)   — Has child @Composable calls, or contains LazyColumn/LazyRow
INTERACTIVE(30/s) — Buttons, text fields responding to user input
LIST_ITEM(60/s)   — Items in LazyColumn/LazyRow, recycled during scroll
ANIMATED(120/s)   — Body contains animate*/Animation/Transition calls
UNKNOWN(30/s)     — Unclassified, permissive default to reduce false positives
```

The inference walks the IR call tree, checking for specific function call patterns. A composable whose body calls `animateFloatAsState` gets classified as `ANIMATED` with a budget of 120 recompositions/second -- because at 60fps, that is exactly the expected rate.

The rationale for each budget:

- **SCREEN(3/s)**: Full screens should only recompose on navigation events or major state changes. Three per second accommodates initial load, a state transition, and margin.
- **LEAF(5/s)**: Terminal composables (`Text`, `Icon`, `remember*` functions) are cheap individually but should not over-trigger. Five per second catches genuine issues without being noisy.
- **CONTAINER(10/s)**: Layout wrappers like `Column`, `Box`, `Scaffold`. They recompose when children change. Moderate budget.
- **INTERACTIVE(30/s)**: Composables responding to user input -- button presses, text field updates. Users can type fast, but 30/s is generous.
- **LIST_ITEM(60/s)**: `LazyColumn` and `LazyRow` items get recycled during scroll. At fast scroll speeds, items compose and recompose rapidly.
- **ANIMATED(120/s)**: Animation-driven composables. At 120Hz, a composable driven by `animate*` state should recompose once per frame. 120/s is the ceiling.
- **UNKNOWN(30/s)**: The default. Permissive enough to avoid false positives on correctly-behaving composables.

### The Runtime: Rolling Windows and Throttled Violations

`ReboundTracker` is the runtime heart. Every injected `onComposition` call feeds into it:

```kotlin
fun onComposition(key: String, budgetClassOrdinal: Int, changedMask: Int, paramNames: String) {
    if (!enabled) return

    val budgetClass = BudgetClass.entries.getOrElse(budgetClassOrdinal) { BudgetClass.UNKNOWN }
    val m = metrics.getOrPut(key) { ComposableMetrics(budgetClass) }
    val now = currentTimeNanos()
    val currentRate = m.recordComposition(now)
    val budget = budgetClass.baseBudgetPerSecond

    if (currentRate > budget) {
        val lastTime = lastViolationTime[key] ?: 0L
        if (now - lastTime > VIOLATION_THROTTLE_NS) {
            lastViolationTime[key] = now
            ReboundLogger.warn(TAG,
                "BUDGET VIOLATION: $key rate=$currentRate/s exceeds $budgetClass budget=$budget/s")
        }
    }
}
```

Two design decisions matter here:

**Rolling 1-second window**: `ComposableMetrics` counts compositions within a sliding 1-second window. When the window expires, the counter resets. This means the rate reflects *current* behavior, not lifetime averages.

**Violation throttling**: A maximum of one violation per composable per 5 seconds. Without this, a genuinely problematic composable at 60 recompositions/second would flood logcat with 60 warnings per second. The throttle reduces that to one meaningful alert, then silence until the window rolls over.

## The 16ms Frame Budget Connection

Why do recomposition rates matter? Because of the frame budget.

| Refresh Rate | Frame Budget |
|--------------|-------------|
| 60Hz         | 16.6ms      |
| 90Hz         | 11.1ms      |
| 120Hz        | 8.3ms       |

Every frame, Compose runs three phases: **Composition** (rebuild the UI tree), **Layout** (measure and place), and **Draw** (render to canvas). All three must fit within the frame budget.

Composition is typically the most expensive phase. A single recomposition of a large subtree can exceed 16ms by itself, causing a dropped frame. Many small, well-scoped recompositions fit comfortably.

Rebound catches problems at the "composable re-executes" stage -- the earliest possible detection point. By the time Layout Inspector shows you a frame drop, the damage is done. Rebound warns you *while it is happening*, during development.

Think of it this way: **Rebound is the smoke detector. Layout Inspector is the fire investigator.** You want both, but the smoke detector saves you from ever needing the investigator.

## Real-World Validation: StickerExplode

I validated Rebound against [StickerExplode](https://github.com/aldefy/StickerExplode), an app where users drag, rotate, and scale stickers on a canvas with physics-based animations.

### Results

Rebound automatically instrumented **29 composables** with zero configuration. Here is what it found:

**DraggableSticker** -- classified as `ANIMATED(120/s)`:
```
Rebound: DraggableSticker composed (#847, rate=13/s, budget=120/s, class=ANIMATED)
```
At 13 recompositions/second during active dragging, this is well within the ANIMATED budget. No violation. **With a flat threshold of 10/s, this would have been a false positive.**

**rememberTiltState** -- classified as `LEAF(5/s)`:
```
Rebound: BUDGET VIOLATION: rememberTiltState rate=11/s exceeds LEAF budget=5/s
  -> params: sensorX=CHANGED, sensorY=CHANGED
```
This is a **genuine issue**. The tilt sensor is firing continuous updates that drive unnecessary recomposition. The `$changed` mask decode shows exactly which parameters are changing: `sensorX` and `sensorY`. The fix is clear -- debounce the sensor input or use `snapshotFlow` with `distinctUntilChanged`.

**StickerCanvas** -- classified as `CONTAINER(10/s)`:
```
Rebound: StickerCanvas composed (#234, rate=7/s, budget=10/s, class=CONTAINER)
```
Within budget. The container recomposes when child stickers move, but at a manageable rate.

### Before and After Contextual Budgets

When I initially tested with a flat `UNKNOWN(10/s)` budget for all composables, `DraggableSticker` was a constant false positive during any drag interaction. Logcat was flooded with violations that did not represent real problems.

After implementing contextual budget inference, `DraggableSticker` was correctly classified as `ANIMATED(120/s)`, and the false positive disappeared. Meanwhile, `rememberTiltState` -- which *was* a genuine issue -- surfaced clearly.

Violation throttling reduced logcat noise from hundreds of repeated warnings to approximately 5 meaningful, actionable alerts per session.

## Comparison with Existing Tools

| Tool | Detection | Context-Aware | CI Integration | WHY Info | KMP |
|------|-----------|---------------|----------------|----------|-----|
| Layout Inspector | Manual | No | No | Yes (2025) | No |
| compose-stability-analyzer | Count-based | No | No | No | No |
| vkompose | Visual + log | No | Partial | Partial | No |
| rebugger | Arg tracking | No | No | Yes | No |
| Compose compiler reports | Static analysis | No | Yes | Stability only | Yes |
| **Rebound** | **Rate-based** | **Yes** | **Planned** | **Yes ($changed)** | **Yes** |

Rebound is not a replacement for these tools. It fills a specific gap: **automated, context-aware, rate-based recomposition monitoring with parameter-level change tracking**. Use Compose compiler reports for static stability analysis. Use Layout Inspector for deep investigation. Use Rebound as the always-on smoke detector that tells you when something is wrong *before* you notice the stutter.

## Quick Start

### Setup

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal() // or mavenCentral() when published
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    id("io.aldefy.rebound") version "0.1.0"
}

dependencies {
    commonMainImplementation("io.aldefy.rebound:rebound-runtime:0.1.0")
}

rebound {
    enabled = true
}
```

### Enable Logging

```kotlin
// In your Application.onCreate() or MainActivity
ReboundTracker.logCompositions = true
```

That is it. No annotations to add. No composables to wrap. The compiler plugin instruments every `@Composable` function automatically and infers the budget class from the IR structure.

### Reading the Output

Every composition logs in this format:
```
Rebound: <FQN> composed (#<total>, rate=<current>/s, budget=<budget>/s, class=<CLASS>)
```

Budget violations appear as warnings:
```
Rebound: BUDGET VIOLATION: <FQN> rate=<rate>/s exceeds <CLASS> budget=<budget>/s
  -> params: <param>=CHANGED, <param>=CHANGED
```

Use `ReboundTracker.dumpSummary()` to see the top 10 composables by rate at any point:
```
=== Rebound Summary (top 10 by rate) ===
  DraggableSticker: 13/s (budget=120/s, class=ANIMATED, total=847) [OK]
  rememberTiltState: 11/s (budget=5/s, class=LEAF, total=492) [!!OVER!!]
  StickerCanvas: 7/s (budget=10/s, class=CONTAINER, total=234) [OK]
========================================
```

### Skip Rate Tracking

Rebound now tracks how often Compose *skips* your composable vs actually executing the body. The compiler plugin injects two calls:

- `onEnter()` — fires at every function entry, including skips
- `onComposition()` — fires only when the body actually executes (guarded by `$composer.skipping`)

The difference gives you **skip rate** — how effectively Compose is optimizing your UI:

```
StickerCanvas: 45 enters, 12 compositions → 73% skip rate ✓
DraggableSticker: 120 enters, 118 compositions → 2% skip rate ⚠️ (almost never skipped)
```

A low skip rate on a composable that *should* be skipping often indicates unstable parameters.

### @ReboundBudget Annotation

IR heuristics can't classify every composable correctly. Override with an explicit annotation:

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun CustomTransition(/* ... */) {
    // Rebound uses ANIMATED(120/s) budget instead of inferring
}
```

### Interaction-Aware Budgets

Static budgets don't account for scrolling or active animations. Rebound's `InteractionDetector` observes recomposition patterns in real-time:

| State | Multiplier | Trigger |
|-------|-----------|---------|
| IDLE | 1.0x | Default |
| SCROLLING | 2.0x | LIST_ITEM rate > 20/s |
| ANIMATING | 1.5x | ANIMATED rate > 30/s |
| USER_INPUT | 1.5x | INTERACTIVE rate > 10/s |

Budget violations during active scroll are real — but a 2x multiplier avoids noise from expected high-frequency recomposition.

### Baseline Mode & CI Integration

Capture a baseline, then detect regressions automatically:

```kotlin
// After running your test scenario:
val snapshot = ReboundTracker.exportSnapshot()
val json = snapshot.toJson()
// Save to file: rebound-baseline.json

// Later, compare:
val baseline = ReboundSnapshot.fromJson(baselineJson)
val current = ReboundTracker.exportSnapshot()
val regressions = ReboundBaseline.compare(baseline, current, regressionThreshold = 20)
if (regressions.isNotEmpty()) {
    regressions.forEach { println("REGRESSION: ${it.composable} +${it.increasePercent}%") }
}
```

### Debug-Only by Default

Rebound strips itself from release builds automatically. The Gradle plugin's `debugOnly` flag (default: `true`) limits instrumentation to debug build variants only:

```kotlin
rebound {
    enabled = true
    debugOnly = true  // default — no overhead in production
}
```

## What the Research Found

While building Rebound, I dug into the state of recomposition monitoring across the Compose ecosystem. The findings are worth sharing:

- The Compose team has **never published official numeric budgets** for recomposition rates. Their guidance remains principle-based.
- compose-stability-analyzer's thresholds (10/50) are the closest thing to a community standard, but they were chosen pragmatically, not empirically.
- The `@FrequentlyChangingValue` annotation (introduced in 2025) is a soft threshold via lint -- a signal that a value changes often and should not trigger recomposition directly. It is the Compose team's nearest acknowledgment that context matters.
- Strong skipping mode graduated to production in Compose compiler 2.0.20+, reducing unnecessary recompositions by default. But it cannot eliminate all of them -- runtime state changes still trigger legitimate recompositions that may be excessive.
- The community broadly agrees: **context matters more than flat thresholds**. Rebound is, to my knowledge, the first tool to implement contextual budget classification at the compiler level.

## What's Shipped

- Contextual budget model (7 budget classes)
- `$changed` bitmask decoding (single + multi-mask for >10 params)
- IR heuristic classification (name, body analysis, annotation override)
- Skip rate tracking via `$composer.skipping`
- Interaction-aware dynamic budget multiplier
- Baseline snapshots + regression detection
- Debug-only stripping
- iOS/native KMP targets
- Plugin ordering detection with graceful pre-K2 fallback
- Opt-in anonymized telemetry for calibration research

## What's Next

- **Empirical calibration**: Collect real-world data to validate budget numbers
- **Gradle CI tasks**: `reboundSnapshot` and `reboundCheck` for automated regression detection
- **Maven Central**: Publishing to `io.aldefy.rebound`
- **IDE integration**: Android Studio gutter icons for budget violations

## The Philosophy

Rebound ships a tool and a research insight together. The tool is the compiler plugin, the runtime, and the Gradle integration. The insight is that **budget = f(ComposableRole)** -- that the same recomposition rate means fundamentally different things depending on what a composable does.

Flat thresholds are wrong because they ignore context. A `Screen` at 10 recompositions/second has a performance problem. A `DraggableSticker` at 10 recompositions/second is doing exactly what it should. Rebound is the first tool that knows the difference.

Zero config. Context-aware. Always on during development. That is the design.

---

**Rebound** is open source at [github.com/aldefy/compose-rebound](https://github.com/aldefy/compose-rebound).

Package: `io.aldefy.rebound` -- version `0.1.0` available now via `mavenLocal()`.
