# Rebound: Contextual Recomposition Budget Model for Compose

**Date:** 2026-03-06
**Status:** Approved

## Core Thesis

Every existing Compose recomposition tool uses flat, context-agnostic thresholds. A `LazyColumn` item recomposing 50 times during scroll is healthy. A `Scaffold` recomposing 50 times on a button tap is a bug. Rebound answers: **"Was this recomposition excessive given its context?"**

## Architecture

```
compose-rebound/
├── rebound-compiler/           # IR plugin (embedded, for JVM/JS/WasmJS)
├── rebound-compiler-native/    # IR plugin (unshaded, for Kotlin/Native)
├── rebound-runtime/            # KMP — counters, budget engine, callbacks
├── rebound-gradle/             # Gradle plugin, DSL, CI tasks
├── rebound-annotations/        # Optional @ReboundBudget hints
├── sample/                     # Demo app with intentional anti-patterns
└── docs/
```

**KMP Targets (v1):** Android, iOS (arm64 + simulatorArm64 + x64), JVM Desktop

## IR Transformer

Runs **after** the Compose compiler. Injects `ReboundTracker.onComposition(key, budgetClass, changedMask)` at the top of every `@Composable` function body.

### Budget Class Inference (compile-time)

| Signal | Detection | Budget Class |
|--------|-----------|-------------|
| No parent @Composable caller in module | Call graph | `SCREEN` |
| Inside LazyListScope.item {} | Lambda parent analysis | `LIST_ITEM` |
| Reads animate*AsState / Animatable | Call site analysis | `ANIMATED` |
| No child @Composable calls | Body analysis | `LEAF` |
| Default | — | `CONTAINER` |

### $changed Mask Passthrough

The Compose compiler's `$changed` parameter encodes why each parameter changed (3 bits per param: Uncertain/Same/Different/Static). Rebound passes this through to the runtime — zero overhead, maximum insight.

## Budget Model

### Two Modes

1. **Budget mode** — flag composables exceeding contextual budgets (heuristic defaults, refined by empirical research)
2. **Baseline mode** — flag composables whose recomposition count regressed vs. last snapshot (purely relative, no absolute thresholds)

### Budget Evaluation

```
Budget(c) = baseBudget(budgetClass) × interactionMultiplier(context) × tolerance
violation = observedRate > Budget(c) for > windowFrames
```

### Base Budgets (per 1-second rolling window)

| Budget Class | Rate (recomp/sec) | Rationale |
|-------------|-------------------|-----------|
| SCREEN | 3 | Navigation + major state shifts |
| CONTAINER | 10 | Child structure changes |
| INTERACTIVE | 30 | 1 per input event |
| LIST_ITEM | 60 | 60fps during scroll |
| ANIMATED | 120 | 60fps + margin |
| LEAF | 5 | Only on data change |

These are heuristic starting points. Phase 2 research calibrates them from empirical data (Equal AI instrumentation + community telemetry).

### Interaction Context (inferred, not detected)

Scroll/animation context is inferred from recomposition patterns:
- LIST_ITEM recomposing >30/sec → scroll active → 3x multiplier for LIST_ITEM
- ANIMATED recomposing >30/sec → animation active → 3x multiplier for ANIMATED

This avoids hooking into PointerInputScope or gesture detection.

## CI Integration

### `./gradlew reboundSnapshot`
Runs test suite with instrumentation, captures per-composable counts, writes `rebound-baseline.json` (committed to git).

### `./gradlew reboundCheck`
Runs tests, compares against baseline, fails CI on regression.

### Gradle DSL

```kotlin
rebound {
    onViolation = ViolationAction.LOG  // LOG, CRASH, ANALYTICS
    ci {
        regressionThreshold = 1.5  // 50% increase = failure
        newComposablePolicy = WARN
    }
    budgets {
        "com.app.ChatScreen" { baseRate = 30 }
    }
}
```

## Build Sequence (Hybrid Approach)

| Sprint | Weeks | Deliverable | Risk Gate |
|--------|-------|------------|-----------|
| 1 | 1-2 | IR transformer spike (Android only) | GO/NO-GO: if blocked, pivot to runtime-only |
| 2 | 2-3 | Runtime library (KMP, parallel with spike) | — |
| 3 | 3-4 | Gradle plugin + CI tasks | — |
| 4 | 4-5 | Budget classification at IR level | Depends on Sprint 1 |
| 5 | 5-7 | Budget model research + data collection | — |
| 6 | 7-8 | Native artifact + iOS/Desktop validation | — |
| 7 | 8+ | Launch: blog post + Maven Central | — |

## Key Risks

1. **Plugin ordering** (HIGH) — must run after Compose compiler
2. **Per-Kotlin-version build matrix** (HIGH) — IR API changes per minor version
3. **Native artifact split** (MEDIUM) — separate JARs for K/N
4. **LazyList scope detection** (MEDIUM) — fragile IR lambda analysis
5. **Base budget calibration** (LOW) — mitigated by baseline mode + research

## Differentiation

| Existing Tool | What It Does | What Rebound Adds |
|--------------|-------------|-------------------|
| vkompose | Visual borders, Android only | KMP, contextual budgets, CI baseline |
| compose-stability-analyzer | Stability analysis + flat thresholds | Budget model, interaction-aware thresholds |
| rebugger | Manual parameter-change logging | Auto-instrumented, CI integration |
| Compose compiler metrics | Binary stable/unstable | Rate-based anomaly detection |

## Research Output

- Blog post: "Beyond Recomposition Counts: A Budget Model for Compose Performance"
- Empirical data from Equal AI + controlled test suite
- Published budget coefficients with methodology
- Conference talk potential: KotlinConf / Droidcon
