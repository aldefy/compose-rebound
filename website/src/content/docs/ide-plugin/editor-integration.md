---
title: Editor Integration
---

# Editor Integration

Rebound integrates directly into the Android Studio editor so you can see recomposition data without switching to the tool window. Research on developer tooling consistently shows that context-switching between profiler windows and source code is where time goes to die.

## Gutter Icons

Colored dots appear next to every `@Composable` function declaration in the editor:

| Icon | Meaning |
|------|---------|
| Red dot | Over budget -- this composable is currently violating its recomposition budget |
| Yellow dot | Near budget -- rate is between 70% and 100% of budget |
| Green dot | Within budget -- the composable is actively recomposing within acceptable limits |
| No dot | Not seen in the current session |

### Click interaction

Clicking a gutter icon opens a popup balloon showing:

- Current rate and budget
- Skip percentage
- Budget class
- Reason for current status
- A "Show in Rebound" link that opens the tool window and selects this composable

### Implementation

The gutter icons are provided by `ReboundLineMarkerProvider`, which implements `LineMarkerProvider`. It scans PSI for `@Composable` annotations and matches the function's fully-qualified name against `SessionStore.currentEntries`. Throttling is handled automatically by `DaemonCodeAnalyzer`.

## CodeVision Inlay Hints

Block inlays appear above `@Composable` function signatures, showing live metrics:

```kotlin
// 12/s | budget: 8/s | OVER | skip: 45%
@Composable
fun ProfileHeader(user: User, avatarUrl: String) {
    // ...
}
```

The inlay is color-coded to match the composable's status (red for violations, yellow for near-budget, green for OK).

Inlay hints are only shown when:

- The Rebound connection is active
- The composable has been seen in the current session
- The setting is enabled (Settings > Inlay Hints > Rebound)

### Implementation

Inlay hints use the `InlayHintsProvider` API. They update when new snapshot data arrives and are automatically throttled by the IntelliJ platform.

## Status Bar Widget

A widget in the bottom-right corner of Android Studio provides a global overview:

```
Rebound: 45 composables | 3 violations
```

The widget includes a colored circle:

| Circle Color | Meaning |
|-------------|---------|
| Green | Connected, no violations |
| Red | Connected, violations present |
| Gray | Not connected |

Clicking the status bar widget opens the Rebound tool window.

### Implementation

The widget is provided by `ReboundStatusBarWidget` via `StatusBarWidgetFactory`, using `StatusBarWidget.TextPresentation` for the display.

## Settings

All editor integration features can be toggled independently:

| Setting | Default | Location |
|---------|---------|----------|
| Show gutter icons | `true` | Settings > Tools > Rebound |
| Show inlay hints | `true` | Settings > Inlay Hints > Rebound |

When disabled, the corresponding visual elements are removed from the editor without affecting data collection or the tool window.
