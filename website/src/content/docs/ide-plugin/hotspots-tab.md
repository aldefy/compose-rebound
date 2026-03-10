---
title: Hot Spots Tab
---

# Hot Spots Tab

The Hot Spots tab provides a flat, sortable table of every instrumented composable, ranked by severity. It answers the question: "Which composables need attention right now?"

## Summary Card

At the top of the tab, a summary card provides an at-a-glance overview:

```
3 violations | 12 near budget | 85 OK
```

Each count is color-coded (red, yellow, green) with a badge. This gives you an immediate sense of the app's overall recomposition health.

## Sortable Table

The table displays every composable with 8 columns:

| Column | Type | Description |
|--------|------|-------------|
| **Composable** | String | The simple name (e.g., `ProfileHeader`, `HomeScreen.Scaffold{}`) |
| **Rate/s** | Int | Current recomposition rate (rolling 1-second window) |
| **Budget/s** | Int | Maximum acceptable rate for this budget class |
| **Ratio** | Float | Rate divided by budget. Values above 1.0 indicate a violation. |
| **Skip %** | Float | Percentage of calls where the body was skipped |
| **Peak/s** | Int | Highest rate ever recorded for this composable |
| **Class** | Enum | Budget class: SCREEN, CONTAINER, LEAF, INTERACTIVE, LIST_ITEM, ANIMATED, UNKNOWN |
| **Status** | Icon | Color-coded indicator (red/yellow/green) |

### Default sort

The table sorts by **Rate/s descending** by default, putting the highest recomposition rates at the top. Click any column header to sort by that column.

### Useful sorting strategies

- **Ratio descending** -- surfaces composables closest to or exceeding their budget, regardless of absolute rate
- **Skip % ascending** -- finds composables with the lowest skip rates, where Compose is failing to skip effectively
- **Peak/s descending** -- identifies composables that had the highest burst rates, even if they have since calmed down

## Interactions

### Double-click to navigate

Double-clicking any row navigates to the `@Composable` function in the editor. The plugin performs a PSI lookup by fully-qualified name to find the function declaration.

### Right-click context menu

- **Copy FQN** -- copies the fully-qualified name to the clipboard
- **Show in Monitor Tree** -- switches to the Monitor tab and selects this composable in the tree
- **View History** -- switches to the History tab filtered to this composable (if history data is available)

## Update Behavior

The table updates via `SessionListener.onSnapshot()` each time new data arrives from the socket (every 1 second). Updates only apply when the Hot Spots tab is visible -- inactive tabs do not repaint, avoiding unnecessary UI work.
