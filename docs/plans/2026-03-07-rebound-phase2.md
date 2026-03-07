# Rebound Phase 2 — Full Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Evolve Rebound from hack-weekend MVP to production-grade tool with CI regression detection, full KMP support, skip tracking, and empirical budget calibration infrastructure.

**Architecture:** Build on existing 3-module structure (compiler, runtime, gradle). Add iOS native targets to runtime, baseline/snapshot system to gradle, skip counting to compiler, and telemetry infrastructure for research.

**Tech Stack:** Kotlin 2.1.0, Compose Multiplatform 1.7.3, Kotlin IR Plugin API, kotlinx.serialization (for baseline JSON), kotlinx.datetime (for timestamps)

---

## Section A: IR / Compiler

---

### Task 1: Plugin Ordering — Runtime Verification

**Context:** Research confirms there is NO official ordering API in the Kotlin compiler plugin system. Extensions run in classpath order (FIFO). Since Kotlin 2.0, the Compose compiler is embedded in the Kotlin distribution and loads first. vkompose and all other Compose-aware plugins are designed to be order-agnostic.

**Strategy:** Don't try to enforce ordering. Instead, detect at IR time whether the Compose compiler has already run, and warn if not.

**Files:**
- Modify: `rebound-compiler/src/main/kotlin/io/aldefy/rebound/compiler/ReboundIrTransformer.kt`
- Modify: `rebound-compiler/src/main/kotlin/io/aldefy/rebound/compiler/ReboundIrGenerationExtension.kt`

**Step 1: Add Compose compiler detection in ReboundIrGenerationExtension**

```kotlin
class ReboundIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        // Verify Compose compiler has run by checking for the $composer parameter
        // on any @Composable function in the module
        val composeCompilerRan = moduleFragment.files.any { file ->
            file.declarations.filterIsInstance<IrSimpleFunction>().any { fn ->
                fn.hasAnnotation(FqName("androidx.compose.runtime.Composable")) &&
                fn.valueParameters.any { it.name.asString() == "\$composer" }
            }
        }

        if (!composeCompilerRan) {
            // Log warning but continue — the plugin still works, just can't read $changed
            println("WARNING: Rebound IR transformer running but no Compose compiler output detected. " +
                "Ensure the Compose compiler plugin is applied. $changed tracking will be unavailable.")
        }

        moduleFragment.transform(ReboundIrTransformer(pluginContext), null)
    }
}
```

**Step 2: Make transformer gracefully handle missing Compose artifacts**

The transformer already handles missing `$changed` (falls back to `irInt(0)`). Add a counter of how many composables had vs didn't have `$changed` and log a summary at the end.

**Step 3: Run tests**

Run: `./gradlew :rebound-compiler:build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add rebound-compiler/
git commit -m "feat(compiler): add Compose compiler ordering detection with graceful fallback"
```

---

### Task 2: Multi-$changed Support

**Context:** The Compose compiler uses 3 bits per parameter in `$changed`. An `Int` has 32 bits, so bit 0 (force flag) + 10 params x 3 bits = 31 bits max. Functions with >10 params get `$changed1`, `$changed2`, etc.

**Files:**
- Modify: `rebound-compiler/src/main/kotlin/io/aldefy/rebound/compiler/ReboundIrTransformer.kt`
- Modify: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundTracker.kt`
- Modify: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ChangedMaskDecoder.kt`
- Modify: `rebound-runtime/src/commonTest/kotlin/io/aldefy/rebound/ReboundTrackerTest.kt`

**Step 1: Update IR transformer to collect all $changed params**

In `ReboundIrTransformer.visitFunction`, change from finding single `$changed` to collecting all:

```kotlin
// Collect all $changed parameters ($changed, $changed1, $changed2, ...)
val changedParams = function.valueParameters.filter {
    val name = it.name.asString()
    name == "\$changed" || name.matches(Regex("\\$changed\\d+"))
}.sortedBy {
    val name = it.name.asString()
    if (name == "\$changed") 0 else name.removePrefix("\$changed").toInt()
}
```

Encode as a comma-separated string of int values at the IR level (since we can't pass variable-length arrays easily through a fixed function signature):

```kotlin
// Instead of single changedMask Int, pass a String of comma-separated masks
// "123" for single $changed, "123,456" for $changed + $changed1
val changedMaskStr = if (changedParams.isNotEmpty()) {
    // Build a string concatenation in IR: "$changed" or "$changed,$changed1"
    // For simplicity, just pass the first $changed as Int (covers 10 params)
    // and add a second parameter for overflow
    changedParams.map { builder.irGet(it) }
} else {
    listOf(builder.irInt(0))
}
```

**Alternative (simpler):** Keep the `onComposition` signature stable. Pass only the first `$changed` (covers 10 params). Add a separate `onCompositionExtended` overload for >10 params. In practice, >10-param composables are rare — this is fine for v1.

**Step 2: Update ChangedMaskDecoder**

```kotlin
object ChangedMaskDecoder {
    // Existing decode handles first 10 params from single mask
    // Add overload for multiple masks
    fun decode(changedMasks: List<Int>, paramNames: String): List<Pair<String, ParamState>> {
        if (paramNames.isEmpty()) return emptyList()
        val names = paramNames.split(",")
        return names.mapIndexed { index, name ->
            val maskIndex = index / 10  // which $changed param
            val paramIndex = index % 10  // position within that mask
            val mask = changedMasks.getOrElse(maskIndex) { 0 }
            val shift = (paramIndex + 1) * 3
            val bits = (mask ushr shift) and 0b111
            val state = when {
                bits and 0b100 != 0 -> ParamState.STATIC
                bits and 0b010 != 0 -> ParamState.DIFFERENT
                bits and 0b001 != 0 -> ParamState.SAME
                else -> ParamState.UNCERTAIN
            }
            name.trim() to state
        }
    }
}
```

**Step 3: Add tests for >10 param composables**

Test with a mock 12-param function to verify masks are decoded across boundaries.

**Step 4: Run tests and commit**

Run: `./gradlew :rebound-runtime:jvmTest :rebound-compiler:build`

```bash
git commit -m "feat: multi-\$changed support for composables with >10 parameters"
```

---

### Task 3: Skippability Detection

**Context:** When the Compose compiler determines all params are Same+Stable, it inserts a skip block that calls `$composer.skipToGroupEnd()` and returns early. If the composable is skipped, our injected `onComposition()` call never runs (because it's inside the body after the skip check). This is GOOD — we don't count skips as compositions. But we also can't report the skip RATE, which is valuable information.

**Strategy:** Inject a SECOND tracking call BEFORE the skip check (at the very top of the function, before the Compose compiler's generated code). This call records "entered" vs "executed". The difference is the skip count.

**Files:**
- Modify: `rebound-compiler/src/main/kotlin/io/aldefy/rebound/compiler/ReboundIrTransformer.kt`
- Modify: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundTracker.kt`
- Modify: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ComposableMetrics.kt`

**Step 1: Add `onEnter` to ReboundTracker**

```kotlin
object ReboundTracker {
    // Called at the VERY TOP of every @Composable — even if it will be skipped
    fun onEnter(key: String) {
        if (!enabled) return
        val m = metrics.getOrPut(key) { ComposableMetrics(BudgetClass.UNKNOWN) }
        m.recordEnter()
    }

    // Existing onComposition — only called if the body actually executes
    fun onComposition(key: String, budgetClassOrdinal: Int, changedMask: Int, paramNames: String) {
        // ... existing code ...
    }
}
```

**Step 2: Add enter/skip tracking to ComposableMetrics**

```kotlin
class ComposableMetrics(val budgetClass: BudgetClass) {
    @Volatile private var enterCount: Long = 0
    // ... existing composition tracking ...

    fun recordEnter() { enterCount++ }

    val skipCount: Long get() = enterCount - compositionCount
    val skipRate: Float get() = if (enterCount > 0) skipCount.toFloat() / enterCount else 0f
}
```

**Step 3: Update IR transformer**

The challenge: we need to inject `onEnter()` BEFORE the Compose compiler's skip check. Since Rebound runs after Compose, the skip check is already in the IR. We need to find the skip block and insert before it.

The Compose compiler generates something like:
```
if ($changed and 0b...) {
    $composer.skipToGroupEnd()
    return
}
// actual body starts here — our onComposition is here
```

Two approaches:
- **A) Insert at index 0 of the block body** — this puts `onEnter` before the skip check since Compose wraps the entire body in a group, and the skip check is the first statement inside that group. BUT our current `onComposition` is also at index 0, so we need to be careful about ordering.
- **B) Don't inject onEnter in IR. Instead, use the $composer's currentGroup to detect enter vs execute** — this is cleaner but depends on internal Compose APIs.

**Recommended: Approach A.** Insert `onEnter` as the very first statement (index 0), then `onComposition` as the second statement (index 1). Since the Compose compiler's skip check comes after the group start, `onEnter` will fire before the skip, and `onComposition` will fire after (only if not skipped).

IMPORTANT: The Compose compiler wraps the body in `$composer.startRestartGroup()`...`$composer.endRestartGroup()`. The skip check is inside this group. So we need `onEnter` OUTSIDE the group (truly first), and `onComposition` INSIDE the body (where it already is).

Actually, since both calls need to be inside the function body and the Compose compiler may restructure the body, the safest approach is:

```kotlin
// Inject onEnter at position 0 (before everything including Compose's group start)
// Inject onComposition at position 1 (after onEnter, before Compose's group start)
// The Compose compiler's generated skip check comes after startRestartGroup,
// so onEnter fires even if skipped, but onComposition also fires even if skipped.
```

Wait — this means both would fire. The key insight: we should inject `onEnter` OUTSIDE the function body, or we need to check whether Compose actually restructured the body. Let me reconsider.

**Revised approach:** Since Rebound runs AFTER Compose, the IR already has the skip check. We can scan for the skip pattern and inject `onEnter` before it:

```kotlin
// After Compose compiler, the body looks like:
// 0: $composer.startRestartGroup(key)
// 1: if (... skip check ...) { $composer.skipToGroupEnd(); return }
// 2: [actual body — our onComposition is here]
// 3: $composer.endRestartGroup()?.updateScope { ... }

// We inject onEnter at position 0 (before startRestartGroup)
// This fires for every call, even skipped ones
body.statements.add(0, enterCall)  // fires always
// onComposition stays at its current position (inside the non-skip path)
```

This works because `onEnter` at position 0 runs before Compose's skip check, while `onComposition` (already injected inside the body) only runs if not skipped.

**Step 4: Update summary dump to show skip rate**

```kotlin
ReboundLogger.log(TAG, "  $shortKey: ${m.currentRate()}/s (budget=..., skipped=${m.skipRate * 100}%)")
```

**Step 5: Test and commit**

Run: `./gradlew :rebound-runtime:jvmTest :rebound-compiler:build :sample:assembleDebug`

```bash
git commit -m "feat: skip rate tracking — report how often composables are skipped vs executed"
```

---

### Task 4: iOS / Native Targets

**Context:** The runtime currently has `androidTarget()` and `jvm()` only. Need to add iOS targets for full KMP support. The compiler plugin is JVM-only (runs during Kotlin compilation, which happens on JVM regardless of target platform), so no `rebound-compiler-native` is needed — the existing JVM compiler plugin already instruments iOS-targeted code.

**Clarification:** The IR transformer runs on the HOST machine (JVM) during compilation of ALL targets (including iOS). The compiler plugin JAR doesn't need a native variant. Only the RUNTIME library needs native targets so it can be linked into iOS binaries.

**Files:**
- Modify: `rebound-runtime/build.gradle.kts`
- Create: `rebound-runtime/src/nativeMain/kotlin/io/aldefy/rebound/ConcurrentMap.native.kt`
- Create: `rebound-runtime/src/nativeMain/kotlin/io/aldefy/rebound/ReboundLogger.native.kt`
- Create: `rebound-runtime/src/nativeMain/kotlin/io/aldefy/rebound/CurrentTime.native.kt`

**Step 1: Add iOS targets to build.gradle.kts**

```kotlin
kotlin {
    androidTarget { ... }
    jvm()

    // iOS targets
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Shared native source set
    sourceSets {
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val iosX64Main by getting { dependsOn(nativeMain) }
        val iosArm64Main by getting { dependsOn(nativeMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nativeMain) }
    }
}
```

**Step 2: Implement native actuals**

`ConcurrentMap.native.kt`:
```kotlin
package io.aldefy.rebound

import kotlin.native.concurrent.AtomicReference
// Kotlin/Native has single-threaded by default with new MM,
// regular HashMap is fine since Kotlin/Native's new memory model
// allows shared mutable state
internal actual fun <K, V> concurrentMapOf(): MutableMap<K, V> = mutableMapOf()
```

Note: With Kotlin/Native's new memory model (default since Kotlin 1.7.20), all objects are shared between threads. `mutableMapOf()` is technically not thread-safe, but `@Synchronized` can be added if needed. For development-only tooling, this is acceptable.

`ReboundLogger.native.kt`:
```kotlin
package io.aldefy.rebound

actual object ReboundLogger {
    actual fun log(tag: String, message: String) { println("[$tag] $message") }
    actual fun warn(tag: String, message: String) { println("[$tag] WARN: $message") }
}
```

`CurrentTime.native.kt`:
```kotlin
package io.aldefy.rebound

import kotlin.system.getTimeNanos

internal actual fun currentTimeNanos(): Long = getTimeNanos()
```

**Step 3: Build and verify**

Run: `./gradlew :rebound-runtime:iosSimulatorArm64MainClasses`
Expected: BUILD SUCCESSFUL

**Step 4: Add publishLibraryVariants for iOS**

The iOS targets publish klib by default, no extra config needed.

**Step 5: Commit**

```bash
git commit -m "feat: add iOS targets to rebound-runtime (iosArm64, iosX64, iosSimulatorArm64)"
```

---

## Section B: Runtime

---

### Task 5: @ReboundBudget Annotation

**Context:** IR heuristics can't classify everything correctly. Devs need an escape hatch.

**Files:**
- Create: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundBudget.kt`
- Modify: `rebound-compiler/src/main/kotlin/io/aldefy/rebound/compiler/ReboundIrTransformer.kt`

**Step 1: Create the annotation**

```kotlin
package io.aldefy.rebound

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)  // Survive compilation, available in IR
annotation class ReboundBudget(val budgetClass: BudgetClass)
```

**Step 2: Update IR transformer to check for annotation**

In `inferBudgetClass`, check for the annotation FIRST (highest priority):

```kotlin
private fun inferBudgetClass(function: IrFunction): Int {
    // Priority 1: Explicit @ReboundBudget annotation
    val budgetAnnotation = function.annotations.firstOrNull {
        it.type.classFqName?.asString() == "io.aldefy.rebound.ReboundBudget"
    }
    if (budgetAnnotation != null) {
        val enumEntry = budgetAnnotation.getValueArgument(0)
        // Extract ordinal from the enum entry reference
        // ... return ordinal
    }

    // Priority 2: Existing heuristics
    val name = function.name.asString()
    // ... rest of existing logic
}
```

**Step 3: Test and commit**

```bash
git commit -m "feat: @ReboundBudget annotation for manual budget class override"
```

---

### Task 6: Baseline Mode + JSON Snapshots

**Context:** The killer feature for teams. Capture a baseline of composition rates, compare across runs, fail CI on regressions.

**Files:**
- Create: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundSnapshot.kt`
- Create: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundBaseline.kt`
- Modify: `rebound-runtime/build.gradle.kts` (add kotlinx.serialization)
- Modify: `rebound-gradle/src/main/kotlin/io/aldefy/rebound/gradle/ReboundGradlePlugin.kt`
- Modify: `rebound-gradle/build.gradle.kts` (add kotlinx.serialization)

**Step 1: Add kotlinx.serialization dependency**

In `rebound-runtime/build.gradle.kts`:
```kotlin
plugins {
    // ... existing
    alias(libs.plugins.kotlin.serialization)  // add to version catalog
}
commonMain.dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

**Step 2: Define snapshot data model**

```kotlin
@Serializable
data class ReboundSnapshot(
    val timestamp: String,  // ISO 8601
    val appVersion: String = "",
    val composables: Map<String, ComposableSnapshot>
)

@Serializable
data class ComposableSnapshot(
    val budgetClass: String,
    val budgetPerSecond: Int,
    val totalCompositions: Long,
    val peakRate: Int,
    val skipRate: Float,     // 0.0 to 1.0
    val paramNames: String
)
```

**Step 3: Add snapshot export to ReboundTracker**

```kotlin
object ReboundTracker {
    fun exportSnapshot(appVersion: String = ""): ReboundSnapshot {
        val snap = snapshot()
        return ReboundSnapshot(
            timestamp = currentTimestampIso(),
            appVersion = appVersion,
            composables = snap.mapValues { (_, m) ->
                ComposableSnapshot(
                    budgetClass = m.budgetClass.name,
                    budgetPerSecond = m.budgetClass.baseBudgetPerSecond,
                    totalCompositions = m.totalCount,
                    peakRate = m.peakRate(),
                    skipRate = m.skipRate,
                    paramNames = ""
                )
            }
        )
    }

    fun toJson(): String = Json.encodeToString(exportSnapshot())
}
```

**Step 4: Add baseline comparison**

```kotlin
object ReboundBaseline {
    fun compare(
        baseline: ReboundSnapshot,
        current: ReboundSnapshot,
        regressionThreshold: Float = 0.2f  // 20% increase = regression
    ): List<ReboundRegression> {
        val regressions = mutableListOf<ReboundRegression>()
        for ((key, currentMetrics) in current.composables) {
            val baselineMetrics = baseline.composables[key] ?: continue
            val rateIncrease = if (baselineMetrics.peakRate > 0) {
                (currentMetrics.peakRate - baselineMetrics.peakRate).toFloat() / baselineMetrics.peakRate
            } else 0f
            if (rateIncrease > regressionThreshold) {
                regressions.add(ReboundRegression(
                    composable = key,
                    baselineRate = baselineMetrics.peakRate,
                    currentRate = currentMetrics.peakRate,
                    increase = rateIncrease,
                    budgetClass = currentMetrics.budgetClass
                ))
            }
        }
        return regressions
    }
}

@Serializable
data class ReboundRegression(
    val composable: String,
    val baselineRate: Int,
    val currentRate: Int,
    val increase: Float,
    val budgetClass: String
)
```

**Step 5: Add Gradle tasks (reboundSnapshot and reboundCheck)**

In `ReboundGradlePlugin.kt`:
```kotlin
// Register reboundSnapshot task — runs instrumented tests and saves baseline
target.tasks.register("reboundSnapshot") {
    group = "rebound"
    description = "Capture recomposition baseline snapshot"
    dependsOn("connectedDebugAndroidTest")  // or custom test task
    doLast {
        // Read snapshot from test output, save to rebound-baseline.json
    }
}

// Register reboundCheck task — compare against baseline
target.tasks.register("reboundCheck") {
    group = "rebound"
    description = "Check for recomposition regressions against baseline"
    dependsOn("connectedDebugAndroidTest")
    doLast {
        // Read current snapshot, compare against baseline, fail if regressions
    }
}
```

Note: The Gradle tasks are orchestrators. The actual snapshot capture happens in test code that calls `ReboundTracker.toJson()`. The Gradle task reads the output file and compares.

**Step 6: Test and commit**

```bash
git commit -m "feat: baseline mode — JSON snapshots + regression detection + Gradle CI tasks"
```

---

### Task 7: Interaction Context Inference

**Context:** Currently budgets are static (assigned at compile time). The original vision: detect scroll/animation from runtime recomposition patterns and adjust budgets dynamically.

**Files:**
- Create: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/InteractionDetector.kt`
- Modify: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundTracker.kt`

**Step 1: Implement pattern-based detection**

```kotlin
object InteractionDetector {
    enum class InteractionState {
        IDLE,           // Normal usage, static budgets apply
        SCROLLING,      // Detected rapid LIST_ITEM recompositions
        ANIMATING,      // Detected rapid ANIMATED recompositions
        USER_INPUT      // Detected rapid INTERACTIVE recompositions
    }

    private var currentState = InteractionState.IDLE
    private var stateStartTimeNs: Long = 0

    // Call from ReboundTracker.onComposition
    fun updateState(budgetClass: BudgetClass, currentRate: Int, timeNs: Long) {
        val newState = when {
            budgetClass == BudgetClass.LIST_ITEM && currentRate > 20 -> InteractionState.SCROLLING
            budgetClass == BudgetClass.ANIMATED && currentRate > 30 -> InteractionState.ANIMATING
            budgetClass == BudgetClass.INTERACTIVE && currentRate > 10 -> InteractionState.USER_INPUT
            else -> {
                // Decay back to IDLE after 500ms of no interaction signals
                if (timeNs - stateStartTimeNs > 500_000_000L) InteractionState.IDLE
                else currentState
            }
        }
        if (newState != currentState) {
            currentState = newState
            stateStartTimeNs = timeNs
        }
    }

    // Budget multiplier based on interaction state
    fun budgetMultiplier(): Float = when (currentState) {
        InteractionState.IDLE -> 1.0f
        InteractionState.SCROLLING -> 2.0f    // Double budgets during scroll
        InteractionState.ANIMATING -> 1.5f    // 50% increase during animation
        InteractionState.USER_INPUT -> 1.5f   // 50% increase during input
    }
}
```

**Step 2: Integrate into ReboundTracker budget check**

```kotlin
if (currentRate > (budget * InteractionDetector.budgetMultiplier()).toInt()) {
    // violation
}
```

**Step 3: Test and commit**

```bash
git commit -m "feat: interaction context inference — dynamic budget adjustment during scroll/animation"
```

---

## Section C: Research / IP

---

### Task 8: Telemetry Infrastructure for Empirical Calibration

**Context:** Current budget numbers (3/5/10/30/60/120) are educated guesses. Need real data from real apps to validate and refine them. This is the research half of the "research + tool hybrid."

**Files:**
- Create: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundTelemetry.kt`

**Step 1: Define anonymized telemetry model**

```kotlin
@Serializable
data class AnonymizedTelemetryReport(
    val sessionDurationMs: Long,
    val composableCount: Int,
    val budgetClassDistribution: Map<String, Int>,  // e.g., {"SCREEN": 3, "CONTAINER": 12}
    val violationCount: Int,
    val violationsByClass: Map<String, Int>,
    val averageRateByClass: Map<String, Float>,     // avg composition rate per class
    val peakRateByClass: Map<String, Float>,
    val skipRateByClass: Map<String, Float>,
    val deviceTier: String  // "low", "mid", "high" based on available memory/cores
)
```

**Step 2: Implement opt-in collection**

```kotlin
object ReboundTelemetry {
    var enabled: Boolean = false  // OPT-IN ONLY

    fun generateReport(): AnonymizedTelemetryReport {
        val snap = ReboundTracker.snapshot()
        // Aggregate by budget class — NO function names, NO package names
        // Only statistical distributions
        // ...
    }

    fun toJson(): String = Json.encodeToString(generateReport())
}
```

Key principle: **NO identifying information.** No function names, no package names, no app identifiers. Only aggregate statistics by budget class. Users must explicitly opt in.

**Step 3: Commit**

```bash
git commit -m "feat: opt-in anonymized telemetry for empirical budget calibration research"
```

---

### Task 9: Budget Formula Refinement Infrastructure

**Context:** The original vision: `Budget = f(TreeDepth, StateReadType, TriggerType)`. Currently using `f(ComposableRole)`. Need infrastructure to collect the additional signals.

**Strategy:** This is a RESEARCH task. Build the data collection, not the formula. The formula comes from analyzing data.

**Signals to collect at IR time:**
- **TreeDepth**: Count nesting depth of @Composable calls (how deep in the UI tree)
- **StateReadCount**: Count `State<*>` reads in the function body (more reads = more recomposition triggers)
- **ChildCount**: Number of direct child @Composable calls
- **HasSideEffects**: Whether the function contains LaunchedEffect/SideEffect/DisposableEffect

**Signals to collect at runtime:**
- **TriggerType**: Was it forced ($changed bit 0) or parameter-driven?
- **BurstPattern**: Is this composable recomposing in bursts (interaction) or steady-state?

**Files:**
- Modify: `rebound-compiler/src/main/kotlin/io/aldefy/rebound/compiler/ReboundIrTransformer.kt`
- Create: `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ComposableProfile.kt`

**Step 1: Collect IR-time signals**

Extend `inferBudgetClass` to also return a profile:

```kotlin
data class ComposableIrProfile(
    val treeDepth: Int,        // estimated from call nesting
    val stateReadCount: Int,   // count of State<*> accesses
    val childCount: Int,       // number of child @Composable calls
    val hasSideEffects: Boolean,
    val hasAnimation: Boolean,
    val hasLazyList: Boolean
)
```

Pass this as an additional encoded string parameter to `onComposition` or as a separate registration call at module init.

**Step 2: Collect runtime signals**

```kotlin
class ComposableMetrics(val budgetClass: BudgetClass) {
    // ... existing fields ...
    var forcedRecompositionCount: Long = 0  // $changed bit 0 set
    var paramDrivenCount: Long = 0         // at least one param DIFFERENT
    var burstCount: Int = 0                // recompositions within 100ms of each other

    fun recordComposition(currentTimeNs: Long, changedMask: Int): Int {
        compositionCount++
        if (changedMask and 0b1 != 0) forcedRecompositionCount++
        if (changedMask and 0b1 == 0 && changedMask != 0) paramDrivenCount++
        // ... existing rate tracking
    }
}
```

**Step 3: Commit**

```bash
git commit -m "feat: budget formula signals — collect TreeDepth, StateReadCount, TriggerType for research"
```

---

### Task 10: Blog Post Updates + Research Appendix

**Context:** The blog post at `docs/blog.md` needs updating with Phase 2 features. Add a research appendix documenting the calibration methodology.

**Files:**
- Modify: `docs/blog.md`
- Create: `docs/research/calibration-methodology.md`

**Step 1: Update blog with new features**

Add sections on:
- Skip rate tracking
- @ReboundBudget annotation
- Baseline mode / CI integration
- iOS support
- Interaction context inference

**Step 2: Write calibration methodology doc**

Document:
- How to collect data (enable telemetry, run app through scenarios)
- What signals are collected (aggregate stats only)
- How the formula will be derived (regression analysis on rate vs jank correlation)
- Call for community participation

**Step 3: Commit**

```bash
git commit -m "docs: update blog + add research calibration methodology"
```

---

## Section D: Publishing / Distribution

---

### Task 11: Debug-Only Stripping

**Context:** Rebound should NOT ship in release builds. The Gradle plugin should only apply the compiler plugin and runtime dependency to debug variants.

**Files:**
- Modify: `rebound-gradle/src/main/kotlin/io/aldefy/rebound/gradle/ReboundGradlePlugin.kt`
- Modify: `rebound-gradle/src/main/kotlin/io/aldefy/rebound/gradle/ReboundExtension.kt`

**Step 1: Add debug-only config to extension**

```kotlin
abstract class ReboundExtension {
    abstract val enabled: Property<Boolean>
    abstract val debugOnly: Property<Boolean>

    init {
        enabled.convention(true)
        debugOnly.convention(true)  // default: only instrument debug builds
    }
}
```

**Step 2: Update plugin to filter configurations**

```kotlin
if (extension.debugOnly.get()) {
    // Only add to debug compiler plugin classpath
    target.configurations
        .filter { it.name.contains("debug", ignoreCase = true) &&
                  it.name.contains("kotlinCompilerPluginClasspath", ignoreCase = true) }
        .forEach { config -> target.dependencies.add(config.name, compilerDep) }
} else {
    // Add to all (existing behavior)
}
```

**Step 3: Commit**

```bash
git commit -m "feat: debug-only mode — strip Rebound instrumentation from release builds"
```

---

### Task 12: Maven Central Publishing Setup

**Files:**
- Modify: `rebound-runtime/build.gradle.kts`
- Modify: `rebound-compiler/build.gradle.kts`
- Modify: `rebound-gradle/build.gradle.kts`
- Create: `gradle/publish.gradle.kts` (shared publishing convention)

Follow the Sonatype publishing pattern from the Lumen project:
- GPG signing with key F30A3C2E
- `localStaging` repository for bundle creation
- .md5, .sha1 checksums + .asc signatures
- POM with project metadata

**Step 1: Add shared publishing config**
**Step 2: Configure Sonatype credentials**
**Step 3: Test with `publishToLocalStaging`**
**Step 4: Upload bundle to Sonatype**

```bash
git commit -m "chore: Maven Central publishing setup for io.aldefy.rebound"
```

---

## Execution Order

Recommended sequence (some tasks can be parallelized):

```
Phase 2a (Quick wins — can parallelize):
  Task 1: Plugin ordering detection     [~30 min]
  Task 2: Multi-$changed support        [~30 min]
  Task 4: iOS native targets            [~30 min]
  Task 5: @ReboundBudget annotation     [~30 min]

Phase 2b (Core features — sequential):
  Task 3: Skippability detection         [~1 hr]
  Task 6: Baseline mode + CI tasks       [~2 hr]
  Task 11: Debug-only stripping          [~30 min]

Phase 2c (Research — can parallelize):
  Task 7: Interaction context inference  [~1 hr]
  Task 8: Telemetry infrastructure       [~1 hr]
  Task 9: Budget formula signals         [~1 hr]

Phase 2d (Ship):
  Task 10: Blog + docs update           [~1 hr]
  Task 12: Maven Central publishing     [~1 hr]
```

Total: ~12 tasks, estimated 10-12 hours of focused work.
