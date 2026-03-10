---
title: Dynamic Scaling
---

# Dynamic Scaling

Rebound dynamically adjusts budget thresholds based on the current interaction state. A composable that would violate its budget during idle may be perfectly acceptable during a fast scroll or animation.

## Interaction States

Rebound detects four interaction states:

| State | Multiplier | Meaning |
|-------|-----------|---------|
| **IDLE** | 1.0x | No active interaction. Base budgets apply. |
| **SCROLLING** | 2.0x | The user is actively scrolling a lazy list. |
| **ANIMATING** | 1.5x | An animation or transition is running. |
| **USER_INPUT** | 1.5x | The user is actively interacting (typing, dragging, etc.). |

## How Detection Works

Interaction state is inferred from the recomposition patterns of budget-classified composables:

### SCROLLING detection

When LIST_ITEM composables exceed 20 recompositions per second collectively, Rebound infers that a scroll is in progress. This works because lazy list items only recompose rapidly when the list is being scrolled.

### ANIMATING detection

When ANIMATED composables exceed 30 recompositions per second, Rebound infers that an animation is active. This captures `animate*` transitions, `Animatable` updates, and physics-based animations.

### USER_INPUT detection

When INTERACTIVE composables exceed 10 recompositions per second, Rebound infers that the user is actively interacting. This captures text field input, slider drags, and other high-frequency input events.

### State transitions

The interaction state transitions back to IDLE when the trigger condition is no longer met. There is a brief cooldown period to avoid flickering between states.

## How Budgets Scale

The effective budget for any composable is:

```
effectiveBudget = baseBudget * interactionMultiplier
```

Examples:

| Composable | Base Budget | During SCROLLING (2.0x) | During ANIMATING (1.5x) |
|------------|------------|------------------------|------------------------|
| SCREEN (3/s) | 3/s | 6/s | 4.5/s |
| CONTAINER (10/s) | 10/s | 20/s | 15/s |
| LIST_ITEM (60/s) | 60/s | 120/s | 90/s |
| ANIMATED (120/s) | 120/s | 240/s | 180/s |

## Why This Matters

Without dynamic scaling, scrolling a `LazyColumn` would trigger violations on every list item composable. A list item recomposing at 50/s during a fast scroll is expected behavior -- the item is being composed, scrolled off screen, and recycled. Flagging this as a violation produces noise.

With dynamic scaling, the same 50/s rate during scroll (effective budget 120/s) is clearly within bounds. But 50/s during IDLE (effective budget 60/s) is flagged -- something is causing the list items to recompose rapidly even though nobody is scrolling.

The interaction state is reported in every violation message:

```
W/Rebound: [VIOLATION] ProductCard — 70 recomp/s (budget: 60, class: LIST_ITEM)
  -> interaction: IDLE
```

This `IDLE` annotation tells you the violation is real -- the composable exceeded its budget even without active scrolling.
