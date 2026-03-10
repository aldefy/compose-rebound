---
title: Timeline Tab
---

# Timeline Tab

The Timeline tab displays a composable-by-time heatmap, showing recomposition patterns over time. It reveals temporal patterns that point-in-time snapshots cannot: sustained hotspots, burst patterns, and correlation between composables.

## Heatmap Visualization

The heatmap is a grid where:

- **X-axis** -- time, scrollable, 1 pixel per second. You can scroll back through the session history (up to the configured retention, default 1 hour).
- **Y-axis** -- composables, sorted by peak rate (highest at top), scrollable. Composables that never recomposed are omitted.

### Color scale

Each cell is colored based on the composable's rate relative to its budget at that point in time:

| Color | Condition | Budget Utilization |
|-------|-----------|-------------------|
| Dark gray | No data or zero rate | 0% |
| Green | Well within budget | 0-50% |
| Yellow | Approaching budget | 50-100% |
| Red | Over budget (violation) | >100% |

The color interpolation is continuous, not discrete. A composable at 80% of its budget appears as a yellow-orange, giving a smooth visual gradient.

## Reading Temporal Patterns

### Scroll burst

A horizontal band of yellow/red cells that lasts 2-5 seconds, then returns to green/gray. This is a normal scroll pattern -- list items recompose during the scroll and settle afterward. If the band extends across many composables vertically, the scroll is cascading recompositions up the tree.

### Sustained hotspot

A composable that stays red for an extended period (30+ seconds) while the app is idle. This indicates a continuous recomposition source -- a sensor, a timer, or an upstream state that changes frequently.

### Synchronized spikes

Multiple composables spiking at the same time point. This usually means a single state change at a high level is cascading recompositions through the tree. Look for the top-most composable in the spike -- that is likely where the problem originates.

### Periodic patterns

Regular spikes at fixed intervals (every 1 second, every 5 seconds). This suggests a timer or polling mechanism that triggers recompositions on a schedule.

## Interactions

### Cell tooltip

Click or hover over any cell to see exact details:

- Composable name
- Rate at that timestamp
- Budget and budget class
- Interaction state at that time

### Rendering

The heatmap renders to a `BufferedImage` that is only redrawn when new snapshot data arrives. This avoids continuous repainting for a potentially large grid. Scrolling and zooming operate on the existing image buffer.

## Data Source

The heatmap is populated from `SessionStore.snapshots` -- full metric snapshots stored at a configurable interval (default: every 5 seconds). Each snapshot becomes one column in the heatmap. With the default 5-second interval and 1-hour retention, the heatmap can display up to 720 columns of data.
