---
title: Skip Tracking
---

# Skip Tracking

Skip rate measures how often the Compose runtime skips a composable's body. It is one of the most revealing metrics for understanding composition efficiency.

## How Skip Rate Is Calculated

Rebound injects two separate tracking calls:

- `onEnter(key)` -- fires every time the composable function is called, even if Compose skips the body
- `onComposition(key, ...)` -- fires only when the body actually executes (not skipped)

The skip rate is:

```
skipRate = 1 - (totalCompositions / totalEnters)
```

For example, if a composable was entered 100 times but only executed 20 times, the skip rate is 80%.

## Interpreting Skip Rates

### High skip rate (above 80%)

A high skip rate means Compose is doing its job. The composable is being called (usually because a parent recomposed), but the runtime determines that none of its parameters changed, so it skips the body. This is the ideal outcome.

```
StickerCanvas: 45 enters, 12 compositions -> 73% skip rate
```

A high skip rate combined with a high enter count suggests the parent is recomposing frequently and cascading calls into this child, but the child's parameters are stable enough that the body is usually skipped.

### Low skip rate (below 20%)

A low skip rate means the body executes almost every time the function is called. This usually indicates one of:

- **Unstable parameters** -- at least one parameter changes on every parent recomposition, defeating skipping
- **Forced recomposition** -- the parent is forcing all children to re-execute
- **Unstable lambda** -- a lambda parameter is recreated on every composition (though Strong Skipping Mode, default since Kotlin 2.0.20, mitigates this for most cases)

### Zero skip rate

A 0% skip rate means the composable body executes on every single call. This is normal for the initial composition but problematic during recomposition. It typically means every parameter is changing every time, or the composable is being forced.

## Forced vs Parameter-Driven Recompositions

Rebound separates recompositions into two categories by decoding the `$changed` bitmask:

### Forced recompositions

When bit 0 of `$changed` is set, the recomposition was forced by a parent invalidation. The parent recomposed, and all its children re-execute regardless of parameter changes. The child had no say in the matter.

**What to fix:** Look at the parent composable. Something is causing it to recompose, and it is dragging all its children along. Hoisting state or restructuring the composable tree usually fixes this.

### Parameter-driven recompositions

When any parameter's bits in `$changed` indicate DIFFERENT, the recomposition was driven by an actual parameter change. The composable received a new value for at least one argument.

**What to fix:** Identify which parameter is changing (Rebound shows this in the violation message) and either stabilize the type, debounce the upstream state, or restructure so the parameter does not change as often.

## Skip Rate in the IDE Plugin

The IDE plugin surfaces skip rate in several places:

- **Monitor tab** -- detail panel shows skip % for the selected composable
- **Hot Spots tab** -- the Skip % column is sortable, making it easy to find composables with low skip rates
- **Gutter icons** -- click a gutter dot to see skip % in the popup

Sorting the Hot Spots table by skip rate (ascending) quickly surfaces composables where Compose cannot skip effectively -- these are usually the most impactful to fix.
