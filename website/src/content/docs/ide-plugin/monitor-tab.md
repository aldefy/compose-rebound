---
sidebar_position: 2
title: Monitor Tab
---

# Monitor Tab

The Monitor tab is the primary view in the Rebound tool window. It shows a live composable hierarchy with real-time metrics.

## Composable Tree

The left panel displays a tree view reflecting the actual composition call structure. Each node shows:

- **Composable name** -- resolved from the fully-qualified name (anonymous lambdas are resolved to readable names like `HomeScreen.Scaffold{}`)
- **Current rate** -- recompositions per second (rolling 1-second window)
- **Budget** -- the maximum acceptable rate for this composable's budget class
- **Status indicator** -- color-coded icon

### Color coding

| Color | Meaning |
|-------|---------|
| Green | Within budget (rate < 70% of budget) |
| Yellow | Near budget (rate between 70% and 100% of budget) |
| Red | Over budget (violation) |
| Orange | Forced recomposition (parent-driven) |
| Cyan | State-driven recomposition |

## Detail Panel

Selecting a composable in the tree opens the detail panel on the right, showing:

- **Full qualified name** -- e.g., `com.example.ui.HomeScreen`
- **Budget class** -- SCREEN, CONTAINER, LEAF, etc.
- **Current rate / budget** -- e.g., "8/s (budget: 3/s)"
- **Skip rate** -- percentage of calls where the body was skipped
- **Total enters** -- lifetime count of function calls
- **Total compositions** -- lifetime count of body executions
- **Forced count** -- recompositions forced by parent invalidation
- **Param-driven count** -- recompositions driven by parameter changes
- **Peak rate** -- highest rate ever recorded for this composable

## Sparkline Rate Chart

Each composable in the detail view includes a sparkline showing rate history over the last 60 seconds. This provides a visual sense of whether the current rate is a spike or sustained behavior.

The sparkline uses the same color scale: the line is green when within budget, yellow when near, and red when over.

## Event Log

The bottom of the Monitor tab displays a scrolling event log with timestamped entries:

- **Violations** -- red entries showing budget exceedances with parameter details
- **State changes** -- when interaction state transitions (IDLE to SCROLLING, etc.)
- **Connection events** -- socket connect/disconnect

Each log entry includes the timestamp, composable name, and relevant details. The log is capped at a configurable maximum (default: 5000 entries) and automatically trims old entries.

## Toolbar

The toolbar at the top provides:

- **Start** -- begins polling the socket for snapshots (1-second interval)
- **Stop** -- pauses data collection (existing data is preserved)
- **Clear** -- resets all metrics and clears the tree

The connection status is shown in the toolbar: "Connected" (green), "Disconnected" (gray), or "Error" (red) with the error detail.
