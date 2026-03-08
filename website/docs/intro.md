---
sidebar_position: 1
title: Introduction
---

# Rebound

**Rebound is a Compose recomposition budget monitor.** It assigns each `@Composable` function a recomposition rate budget based on what that composable does, and alerts you when the budget is exceeded.

A `HomeScreen` recomposing 10 times per second is a problem. A gesture-driven composable recomposing 10 times per second is fine. Same number, completely different situation. Flat thresholds cannot distinguish them. Rebound can.

## Quick Start

Three lines in your build file:

```kotlin title="build.gradle.kts"
plugins {
    id("io.aldefy.rebound") version "0.1.0-SNAPSHOT"
}
```

That is the entire setup. The Gradle plugin detects your Kotlin version, adds the correct compiler artifact, and injects the runtime as a `debugImplementation` dependency. No annotations, no wrapping functions, no code changes.

Run your app in debug mode. When a composable exceeds its budget, you will see:

```
W/Rebound: [VIOLATION] HomeScreen — 8 recomp/s (budget: 3, class: SCREEN)
  -> params: items=DIFFERENT, query=SAME
  -> forced: 0 | param-driven: 8 | interaction: IDLE
```

## What Makes Rebound Different

The Compose ecosystem has solid tooling for recomposition analysis. Each tool answers a specific question well:

| Tool | Question It Answers | Gap |
|------|-------------------|-----|
| **Compose Compiler Reports** | Can this composable be skipped? | Static analysis only, no runtime rates |
| **Layout Inspector** | How many times did this recompose? | Counts without context -- 847 means nothing alone |
| **Rebugger** | What argument changed? | Manual per-composable setup, does not scale |
| **ComposeInvestigator** | Why did this recompose? | Tells the cause, not whether the rate is healthy |
| **VKompose** | Which composables are hot right now? | Visual highlighting, not rate-based analysis |
| **Perfetto** | Is the main thread janking? | Too heavy for "is this composable recomposing too much?" |

Rebound answers the question none of them address: **"Given what this composable does, is this recomposition rate acceptable?"**

### Key capabilities

- **7 budget classes** -- SCREEN (3/s), LEAF (5/s), CONTAINER (10/s), INTERACTIVE (30/s), LIST_ITEM (60/s), ANIMATED (120/s), UNKNOWN (30/s)
- **Automatic classification** -- compiler plugin infers budgets from IR structure, zero config
- **`$changed` bitmask decoding** -- surfaces which parameters caused each recomposition
- **Interaction-aware scaling** -- budgets adjust during scroll, animation, and user input
- **IDE plugin** -- live monitoring, hot spots, timeline heatmap, stability analysis, gutter icons
- **CLI** -- `snapshot`, `summary`, `watch`, `ping` commands over ADB socket
- **KMP** -- Android, JVM, iOS (arm64, x64, simulator arm64), Wasm
- **Debug-only by default** -- zero overhead in release builds
