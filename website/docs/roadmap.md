---
sidebar_position: 100
title: "Roadmap"
---

# Roadmap

Rebound is under active development. This page tracks planned features and their current status.

## Planned

### CI budget gates

Export recomposition profiles as JSON via the CLI `snapshot` command. Compare snapshots across builds and fail the pull request if budgets regress. This is the single most impactful feature on the roadmap -- it brings runtime recomposition budgets into the CI pipeline where regressions can be caught before they ship.

See [CI Integration](./guides/ci-integration.md) for the planned design.

### Flame chart mode in Timeline tab

Replace the current heatmap grid with an interactive flame chart visualization, built on [bric3/fireplace](https://github.com/bric3/fireplace). This will show nested recomposition call stacks over time, making it easier to see parent-child recomposition relationships and identify which parent is forcing child recompositions.

### JetBrains Marketplace publication

Publish the IDE plugin to the JetBrains Marketplace for one-click installation from Android Studio. Currently the plugin must be built from source or installed from a local ZIP.

### Session export and import

Export a complete monitoring session (composable tree, rates, violations, timeline data) to a file. Share it with teammates for collaborative debugging. Import a session to replay it in the IDE plugin without connecting to a running app.

### Baseline snapshots for regression testing

Capture a "known good" snapshot of your app's recomposition profile and commit it to the repository. On subsequent runs, Rebound compares the current profile against the baseline and highlights regressions. Pairs with CI budget gates for automated enforcement.

### ComposeProof MCP integration

Feed Rebound's socket data into [ComposeProof](https://composeproof.dev) for LLM-driven analysis. ComposeProof can render Compose previews, inspect UI trees, and analyze stability. Combined with Rebound's recomposition metrics, it enables AI-assisted performance diagnosis -- ask "why is ProfileHeader over budget?" and get an answer that considers both the runtime data and the source code.

### Compose Multiplatform Desktop and iOS IDE support

Extend the IDE plugin to connect to Compose Desktop and iOS targets, not just Android. The runtime already supports KMP (Android, JVM, iOS, Wasm). The missing piece is transport -- Android uses `LocalServerSocket`, while Desktop and iOS will need a TCP or WebSocket alternative.

## Completed

- Budget class system with 7 tiers (SCREEN, CONTAINER, LIST_ITEM, LEAF, ANIMATED, INPUT, UNKNOWN)
- Kotlin compiler plugin with IR-level instrumentation
- Anonymous lambda name resolution
- `$changed` bitmask decoding for parameter-level tracking
- Dynamic budget scaling by interaction context (IDLE, SCROLLING, ANIMATING, USER_INPUT)
- IDE plugin with 5 tabs (Monitor, Hot Spots, Timeline, Stability, History)
- Gutter icons with inline performance indicators
- Socket-based transport (replaces logcat)
- CLI tool (`snapshot`, `summary`, `ping`, `watch`)
- `@ReboundBudget` annotation for manual budget overrides
- Kotlin 2.0.x, 2.1.x, and 2.2.x support (separate compiler artifacts)
