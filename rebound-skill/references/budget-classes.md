# Budget Classes

Rebound auto-classifies every `@Composable` into one of 7 budget classes using IR heuristics at compile time. Each class defines a maximum recomposition rate per second — exceeding it triggers a violation.

## The 7 Budget Classes

| Budget Class | Rate/sec | Ordinal | Heuristic |
|-------------|----------|---------|-----------|
| `SCREEN` | 3 | 0 | Name contains `Screen` or `Page` (case-insensitive) |
| `LEAF` | 5 | 5 | No child `@Composable` calls in function body |
| `CONTAINER` | 10 | 1 | Has child `@Composable` calls, or calls `LazyColumn`/`LazyRow`/`LazyVerticalGrid`/`LazyHorizontalGrid` |
| `INTERACTIVE` | 30 | 2 | Default for unclassified composables |
| `LIST_ITEM` | 60 | 3 | Inside a lazy layout scope |
| `ANIMATED` | 120 | 4 | Calls `animate*`, `Animate*`, `*Animation*`, or `*Transition*` APIs |
| `UNKNOWN` | 30 | 6 | Fallback — permissive default to reduce noise |

### Classification Priority

The compiler checks these in order — first match wins:

1. `@ReboundBudget` annotation (explicit override)
2. Name contains `Screen` or `Page` → SCREEN
3. Name starts with `remember` → LEAF
4. Body calls lazy layout APIs → CONTAINER
5. Body calls animation APIs → ANIMATED
6. No child `@Composable` calls → LEAF
7. Has child `@Composable` calls → CONTAINER
8. Fallback → UNKNOWN

## Detailed Descriptions

### SCREEN (3/s)

Full-screen composables — the root of navigation destinations.

Screens should only recompose on navigation events or major state changes. If a screen recomposes more than 3 times per second, state is leaking upward — something frequently-changing (scroll position, animation progress, text input) is being read at the screen level.

**Fix:** Hoist the changing state down to the child that needs it. Use `derivedStateOf` or move reads into a smaller scope.

### LEAF (5/s)

Terminal composables with no child `@Composable` calls — `Text()`, `Icon()`, `Image()`.

Individually cheap but shouldn't thrash. If a leaf recomposes >5/s, something upstream is pushing unnecessary state changes through unstable parameters.

**Fix:** Stabilize the parameters being passed to the leaf. Add `@Stable` or `@Immutable` to data classes, or wrap values in `remember`.

### CONTAINER (10/s)

Layout composables with child `@Composable` calls — `Column`, `Row`, `Box`, Scaffold content slots.

Containers recompose when children's layout changes. Moderate rate expected, but sustained high rates indicate unnecessary invalidation — typically from unstable lambdas or reading state that changes every frame.

**Fix:** Wrap lambdas in `remember { }`. Move state reads to the specific child that uses them.

### INTERACTIVE (30/s)

Composables responding to user input — buttons, text fields, sliders.

Users type and tap fast. Input-driven composables need headroom for responsive UX. This is also the default for unclassified composables.

### LIST_ITEM (60/s)

Items inside `LazyColumn`, `LazyRow`, `LazyGrid`.

During fast scroll, items are recycled at up to 60fps. One recomposition per frame is expected. Exceeding 60/s during scroll means the item is doing unnecessary work beyond recycling.

**Fix:** Move heavy computation to `remember` or ViewModel. Ensure stable keys with `key { }`.

### ANIMATED (120/s)

Composables driven by animation APIs — `animate*AsState`, `AnimatedVisibility`, `Crossfade`, `updateTransition`.

Animations target 60-120fps. This budget gives room for the animation to run without false alarms. If exceeded, the composable is doing more than just animating.

### UNKNOWN (30/s)

Unclassified composables that didn't match any heuristic. Uses the same budget as INTERACTIVE to be permissive and reduce noise.

## Color Coding

The IDE and CLI use traffic-light colors:

| Color | Condition | Status Label |
|-------|-----------|-------------|
| Red | `currentRate > effectiveBudget` | OVER |
| Yellow | `currentRate > 70% of effectiveBudget` | NEAR |
| Green | `currentRate <= 70% of effectiveBudget` | OK |
| Gray | Not actively recomposing (rate = 0) | — |

**Example:** A CONTAINER with budget 10/s:
- Red at 11/s (over budget)
- Yellow at 8/s (70% = 7, so 8 > 7)
- Green at 6/s

## Dynamic Scaling

Budgets scale based on detected interaction state. This prevents false violations during legitimate high-activity periods.

| Interaction State | Multiplier | Trigger Condition | Effect |
|---|---|---|---|
| `IDLE` | 1.0x | Default, no active interaction | Normal budgets |
| `SCROLLING` | 2.0x | LIST_ITEM composable rate > 20/s | All budgets doubled |
| `ANIMATING` | 1.5x | ANIMATED composable rate > 30/s | All budgets increased 50% |
| `USER_INPUT` | 1.5x | INTERACTIVE composable rate > 10/s | All budgets increased 50% |

**Decay:** Returns to IDLE after 500ms with no triggering events.

**Effective budget formula:**
```
effectiveBudget = floor(baseBudgetPerSecond * interactionMultiplier)
```

**Example:** SCREEN during scrolling = `3 * 2.0 = 6/s` effective budget.

## @ReboundBudget Annotation

Override the compiler's heuristic when it gets the classification wrong.

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ReboundBudget(val budgetClass: BudgetClass)
```

### When to use

```kotlin
// This composable uses tilt sensor data — it's not a leaf, it's animated
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun TiltDrivenSticker(offset: Offset) { ... }

// This composable is named "SettingsPage" but it's really a section, not a screen
@ReboundBudget(BudgetClass.CONTAINER)
@Composable
fun SettingsPage(section: SettingsSection) { ... }
```

### When NOT to use

- Don't use it to suppress violations. If SCREEN is recomposing at 20/s, upgrading it to ANIMATED hides the bug.
- Don't annotate everything. The heuristics are correct 90%+ of the time. Only override when the name/structure misleads the classifier.
- Don't use UNKNOWN as a "don't care" — it still has a 30/s budget.
