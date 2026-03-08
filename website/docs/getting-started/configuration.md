---
sidebar_position: 2
title: Configuration
---

# Configuration

## Gradle Extension

The `rebound { }` block in your `build.gradle.kts` controls build-time behavior:

```kotlin title="app/build.gradle.kts"
rebound {
    enabled.set(true)       // Whether the compiler plugin instruments composables
    debugOnly.set(true)     // Only instrument debug build variants
}
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | `Boolean` | `true` | Master switch. When `false`, the compiler plugin is a no-op -- no tracking calls are injected. |
| `debugOnly` | `Boolean` | `true` | When `true`, only debug variants are instrumented. Release APKs contain no Rebound code. |

Setting `enabled = false` is useful for temporarily disabling instrumentation without removing the plugin from your build file.

## Runtime Toggles

These are set in your application code and take effect at runtime:

```kotlin
// Toggle tracking on/off at runtime (e.g., only enable for specific screens)
ReboundTracker.enabled = true   // default: true

// Log every composition event to logcat (throttled to 1 per composable per second)
ReboundTracker.logCompositions = true   // default: false
```

### `ReboundTracker.enabled`

Controls whether the runtime actually records metrics when the injected tracking calls fire. Set this to `false` to pause monitoring without rebuilding. Useful for toggling monitoring on specific screens or user flows.

### `ReboundTracker.logCompositions`

When enabled, every composition event is logged to logcat with tag `Rebound`. This produces a lot of output, so logging is throttled to a maximum of one log line per composable per second. Budget violations are always logged regardless of this setting.

## @ReboundBudget Annotation

Override the compiler's automatic budget classification for any composable:

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun PhysicsSticker(offset: Offset) {
    // The compiler would classify this as LEAF (no child composable calls)
    // but it is driven by physics animation, so ANIMATED is correct
    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
            .size(80.dp)
    )
}
```

Available budget classes for annotation:

| Budget Class | Rate Limit | Use When |
|-------------|-----------|----------|
| `SCREEN` | 3/s | Full screens that should rarely recompose |
| `LEAF` | 5/s | Terminal composables with no children |
| `CONTAINER` | 10/s | Layout wrappers with children |
| `INTERACTIVE` | 30/s | User input handlers |
| `LIST_ITEM` | 60/s | Recycled items in lazy lists |
| `ANIMATED` | 120/s | Animation or gesture-driven composables |

The annotation takes priority over all heuristic classification. Use it when the automatic classification does not match the composable's actual role. See [Custom Budgets](/docs/advanced/custom-budgets) for detailed guidance.
