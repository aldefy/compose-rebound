---
sidebar_position: 3
title: Violations
---

# Violations

A violation occurs when a composable's recomposition rate exceeds its budget, adjusted for the current interaction state.

## What Triggers a Violation

The violation check runs on every `onComposition` call:

```
effectiveBudget = baseBudget * interactionMultiplier
if (currentRate > effectiveBudget) {
    // violation
}
```

For example, a SCREEN composable (budget 3/s) during IDLE (multiplier 1.0x) violates at any rate above 3/s. The same composable during SCROLLING (multiplier 2.0x) would need to exceed 6/s.

## Reading Violation Messages

A typical violation in logcat looks like:

```
W/Rebound: [VIOLATION] ProfileHeader — 11 recomp/s (budget: 5, class: LEAF)
  -> params: avatarUrl=DIFFERENT, displayName=DIFFERENT
  -> forced: 0 | param-driven: 11 | interaction: IDLE
```

Breaking this down:

| Field | Meaning |
|-------|---------|
| `ProfileHeader` | The composable's resolved name |
| `11 recomp/s` | Current recomposition rate (rolling 1-second window) |
| `budget: 5` | Maximum acceptable rate for this budget class |
| `class: LEAF` | The budget class assigned by the compiler |
| `avatarUrl=DIFFERENT` | The `avatarUrl` parameter changed between compositions |
| `displayName=DIFFERENT` | The `displayName` parameter also changed |
| `forced: 0` | Zero recompositions were forced by a parent |
| `param-driven: 11` | All 11 recompositions were driven by parameter changes |
| `interaction: IDLE` | The app was idle (no scrolling, animation, or input) |

### Parameter states

Each parameter is reported with one of four states decoded from the Compose compiler's `$changed` bitmask:

- **DIFFERENT** -- the parameter value changed since the last composition
- **SAME** -- the parameter value is the same as the last composition
- **STATIC** -- the parameter is known to be constant at compile time
- **UNCERTAIN** -- the compiler could not determine the parameter's stability

### Forced vs param-driven

- **Forced** -- the parent composable was invalidated and all its children re-execute, regardless of whether their parameters changed. The fix is usually in the parent, not the child.
- **Param-driven** -- a specific parameter changed, triggering this composable to recompose. The fix is to stabilize the parameter or debounce the state that produces it.

## Throttling

Violations are throttled in two ways:

1. **Per-composable throttle** -- maximum one violation per composable per 5 seconds. This prevents logcat flooding when a composable is continuously over budget.
2. **Composition logging throttle** -- when `logCompositions = true`, non-violation composition events are logged at most once per composable per second.

## False Positives

Some situations produce violations that are not actual performance problems:

### App startup

During the first few seconds of app launch, many composables compose rapidly as the UI tree is built. These initial compositions can temporarily exceed budgets. Rebound's 1-second rolling window means these violations are short-lived and stop once the UI settles.

### Transition animations

Screen transitions may briefly push SCREEN composables above their budget. If the violation clears within a second, it is likely a transition -- check the Timeline tab to confirm the spike is transient.

### Testing and measurement

Instrumented tests that rapidly drive recompositions via `mutableStateOf` may produce violations. This is expected -- the tests are deliberately stressing the composable.

### Handling false positives

When a violation is a false positive, you have two options:

1. **Override the budget class** with `@ReboundBudget` to assign a more appropriate class
2. **Ignore it** -- transient violations during transitions are not actionable

If you see UNKNOWN budget class violations frequently, add `@ReboundBudget` annotations to classify those composables correctly.
