---
title: Budget Classes
---

# Budget Classes

Budget classes are the core concept in Rebound. Each `@Composable` function is assigned a budget class that defines its maximum acceptable recomposition rate. The budget depends on what the composable does, not on a single flat threshold.

## Why Not a Flat Threshold?

A flat threshold of 10 recompositions per second sounds reasonable. It falls apart immediately:

- An animated composable at 12/s gets flagged. It should not be.
- A screen composable at 8/s passes. It should not.
- A list item at 40/s during fast scroll looks alarming. That is expected.

Any single number you pick is wrong for most of your composables. You either raise it until false positives vanish (and miss real issues) or lower it until real issues surface (and drown in noise).

## The Seven Budget Classes

### SCREEN -- 3 recompositions/second

**What it covers:** Full-screen composables like `HomeScreen`, `ProfilePage`, `SettingsScreen`.

**Why 3/s:** A screen root represents the top of a composable subtree. When it recomposes, the entire subtree below it may re-execute. Screen-level recompositions should be rare -- typically only on navigation or major state changes. Three per second allows for brief transitions while catching sustained over-composition.

**Heuristic:** The compiler assigns SCREEN when the function name contains "Screen" or "Page".

**Common violations:**
- Reading a frequently-changing `MutableState` directly in the screen composable instead of hoisting it to a child
- Observing a `Flow` that emits on every keystroke at the screen level

**Fixes:**
```kotlin
// Bad: screen recomposes on every keystroke
@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val query by viewModel.query.collectAsState()  // changes on every key
    val results by viewModel.results.collectAsState()
    Column {
        SearchBar(query, onQueryChange = viewModel::updateQuery)
        ResultsList(results)
    }
}

// Good: only SearchBar recomposes on keystroke
@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    Column {
        SearchBar(viewModel)    // reads query internally
        ResultsList(viewModel)  // reads results internally
    }
}
```

---

### LEAF -- 5 recompositions/second

**What it covers:** Terminal composables with no child `@Composable` calls. Typically `Text()`, `Icon()`, `Image()`, `Spacer()`, `remember*` blocks, and small wrapper composables.

**Why 5/s:** Leaf composables are cheap individually but numerous. A leaf recomposing more than 5 times per second usually means upstream state is flowing through it unnecessarily.

**Heuristic:** The compiler assigns LEAF when the function body contains no child `@Composable` calls, or the name starts with `remember`.

**Common violations:**
- A sensor pushing continuous updates into a `Text()` composable without debouncing
- A `remember` block that recreates on every composition because its key is unstable

**Fixes:**
```kotlin
// Bad: sensor fires at 60fps, Text recomposes 60/s
@Composable
fun SensorDisplay(sensorValue: Float) {
    Text("Value: $sensorValue")
}

// Good: debounce the input upstream
@Composable
fun SensorDisplay(sensorValue: Float) {
    val debounced by remember { derivedStateOf { sensorValue } }
    Text("Value: %.1f".format(debounced))
}
```

---

### CONTAINER -- 10 recompositions/second

**What it covers:** Layout wrappers that contain child composables: `Column`, `Row`, `Box`, `Scaffold`, `Card`, `Surface`, and composables whose body calls other `@Composable` functions.

**Why 10/s:** Containers act as intermediate nodes. They recompose more often than screens (because children can trigger parent invalidation) but should not recompose as frequently as interactive or animated composables.

**Heuristic:** The compiler assigns CONTAINER when the function body contains child `@Composable` calls or calls `LazyColumn`/`LazyRow`/`LazyGrid`.

**Common violations:**
- A child composable writing to a state that the container reads (backward write loop)
- Passing an unstable lambda that captures a changing value

**Fixes:**
```kotlin
// Bad: unstable lambda causes ExerciseCard to recompose on every timer tick
@Composable
fun ExerciseCard(exercise: Exercise, elapsedMs: Long) {
    Card {
        Text(exercise.name)
        Text("${elapsedMs}ms")  // elapsedMs changes every 16ms
    }
}

// Good: isolate the changing state
@Composable
fun ExerciseCard(exercise: Exercise, elapsedMs: () -> Long) {
    Card {
        Text(exercise.name)
        TimerDisplay(elapsedMs)  // only TimerDisplay recomposes
    }
}
```

---

### INTERACTIVE -- 30 recompositions/second

**What it covers:** Composables responding to direct user input: buttons, text fields, sliders, toggles, drag handles.

**Why 30/s:** User input generates events at a moderate rate. A text field recomposing on every keystroke at 10-20/s is normal. 30/s provides headroom for fast typers and multi-touch gestures without allowing runaway composition.

**Heuristic:** The compiler assigns INTERACTIVE when the function name or parameters suggest user interaction (e.g., `onClick`, `onValueChange`). This class is also used as the base for UNKNOWN when no better classification is available.

**Common violations:**
- A button composable that reads global state unrelated to its click handler
- A slider that recomposes its parent on every drag position update

---

### LIST_ITEM -- 60 recompositions/second

**What it covers:** Items inside `LazyColumn`, `LazyRow`, `LazyGrid`, and other recycling containers.

**Why 60/s:** During fast scrolling, lazy list items are composed and recomposed rapidly as they enter and exit the viewport. 60/s matches the display refresh rate. With dynamic scaling (2x during scroll), the effective budget becomes 120/s.

**Heuristic:** The compiler assigns LIST_ITEM when the composable is inside a `LazyColumn`/`LazyRow` item scope (detected via IR call tree analysis).

**Common violations:**
- List items recomposing at 60+/s during idle (no scrolling) -- usually caused by an upstream state change that invalidates the entire list
- Each item reading shared state that changes frequently

---

### ANIMATED -- 120 recompositions/second

**What it covers:** Composables driven by animations, transitions, or gesture physics.

**Why 120/s:** Animations target 60fps (16ms per frame), and a composable may recompose twice per frame during transitions. 120/s is intentionally generous -- if an animated composable exceeds this, the animation is likely running away.

**Heuristic:** The compiler assigns ANIMATED when the function body calls `animate*`, `Animatable`, `Transition`, `AnimatedVisibility`, `rememberInfiniteTransition`, or similar animation APIs.

**Common violations:**
- An animation that never settles (infinite recomposition without `rememberInfiniteTransition`)
- A physics simulation that does not converge

---

### UNKNOWN -- 30 recompositions/second

**What it covers:** Composables that the compiler could not classify into any of the above categories.

**Why 30/s:** This is a middle-ground default. It matches INTERACTIVE's budget as a reasonable fallback. If a composable regularly violates the UNKNOWN budget, you should add a `@ReboundBudget` annotation to classify it correctly.

**When it appears:**
- The composable does not match any name pattern or IR structure pattern
- The function body is too complex for heuristic analysis
- Fresh server launch before metrics have initialized (timing issue)

## Color Coding

Rebound uses a three-color system throughout the IDE plugin:

| Color | Meaning | Condition |
|-------|---------|-----------|
| **Green** | Within budget | Rate less than 70% of budget |
| **Yellow** | Near budget | Rate between 70% and 100% of budget |
| **Red** | Over budget (violation) | Rate exceeds budget |

This applies to gutter icons, tree nodes, hot spots table rows, and timeline heatmap cells.

## Dynamic Scaling

Budgets are not static. Rebound detects interaction state and scales budgets accordingly:

| Interaction State | Multiplier | Detection Method |
|-------------------|-----------|-----------------|
| **IDLE** | 1.0x | Default state |
| **SCROLLING** | 2.0x | LIST_ITEM composables exceed 20/s |
| **ANIMATING** | 1.5x | ANIMATED composables exceed 30/s |
| **USER_INPUT** | 1.5x | INTERACTIVE composables exceed 10/s |

During fast scrolling, a LIST_ITEM with a base budget of 60/s gets an effective budget of 120/s. The same composable during idle uses the base 60/s budget.

This prevents false positives during expected high-activity periods while still catching problems during idle.

See [Dynamic Scaling](/docs/concepts/dynamic-scaling) for implementation details.

## Overriding Budget Classes

When the compiler's heuristic gets it wrong, use `@ReboundBudget`:

```kotlin
// Compiler would classify as LEAF (no child composable calls)
// but this composable is driven by accelerometer data
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun TiltIndicator(pitch: Float, roll: Float) {
    Canvas(modifier = Modifier.size(100.dp)) {
        // draw orientation indicator
    }
}
```

The annotation always takes priority over heuristic classification. See [Custom Budgets](/docs/advanced/custom-budgets) for more examples.
