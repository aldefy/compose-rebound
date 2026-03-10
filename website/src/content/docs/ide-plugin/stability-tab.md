---
title: Stability Tab
---

# Stability Tab

The Stability tab surfaces parameter-level stability data from the `$changed` bitmask, helping you identify which specific parameters are causing recompositions.

## Parameter Stability Matrix

The top panel displays a table for the selected composable (or all violators):

| Param | Stability | Last State | Change Frequency |
|-------|-----------|------------|------------------|
| `user` | UNSTABLE | DIFFERENT | 12x in 60s |
| `onClick` | STATIC | -- | never |
| `items` | UNCERTAIN | UNCERTAIN | -- |

### Columns explained

- **Param** -- the parameter name as declared in the function signature
- **Stability** -- the parameter type's stability classification (STABLE, UNSTABLE, or UNCERTAIN based on compile-time analysis)
- **Last State** -- the most recent `$changed` bitmask value for this parameter: SAME, DIFFERENT, STATIC, or UNCERTAIN
- **Change Frequency** -- how many times this parameter was marked DIFFERENT in the retention window

### Reading the matrix

Parameters marked DIFFERENT with high change frequency are your primary optimization targets. If `user` is DIFFERENT 12 times in 60 seconds but `onClick` is always STATIC, you know that stabilizing the `user` parameter (via `@Stable` annotation, structural sharing, or debouncing) will have the most impact.

Parameters showing UNCERTAIN usually indicate that the type lacks a `@Stable` or `@Immutable` annotation, so the Compose compiler cannot determine stability at compile time.

## Cascade Impact Tree

The bottom panel shows a tree view of recomposition cascades:

```
HomeScreen (SCREEN, 8/s)
  +-- UserSection (CONTAINER, 8/s)  <- cascading
  |     +-- ProfileHeader (LEAF, 8/s)  <- cascading
  |     +-- StatusBadge (LEAF, 8/s)  <- cascading
  +-- ContentFeed (CONTAINER, 2/s)  <- not affected
```

This answers: "If this composable recomposes, which children also recompose?"

### Blast radius

The cascade tree shows a "blast radius" summary:

```
Blast radius: 4 composables across 3 depth levels
```

This counts how many descendant composables would exceed their budgets if the selected composable recomposes at its current rate. A large blast radius from a single composable is a strong signal that the fix should happen at that composable, not in its children.

### Color coding

- **Red** -- child would exceed its budget if the parent recomposes at the current rate
- **Yellow** -- child would be near its budget
- **Green** -- child would remain within budget
- **Gray** -- child is not affected (different recomposition path)

## Use Cases

### Finding the root cause of a violation

When a LEAF composable shows a violation, the natural instinct is to fix the leaf. But often the real problem is higher in the tree. The cascade impact view shows whether the violation is isolated or part of a cascade from a parent.

### Deciding what to stabilize

The parameter matrix tells you exactly which parameters to focus on. Instead of running Compose Compiler Reports and reading through stability listings, you see runtime data showing which parameters actually change in practice.

### Measuring fix impact

After stabilizing a parameter type with `@Stable`, check the Stability tab to confirm the parameter now shows SAME instead of DIFFERENT. The change frequency should drop to zero or near-zero.
