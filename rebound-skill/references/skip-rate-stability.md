# Skip Rate & Stability

## Skip Rate

**Formula:** `skipRate = skipCount / totalEnters`

- `totalEnters` = total times the composable was called (including skips)
- `skipCount` = `totalEnters - totalCompositions` (times Compose skipped the body)
- `totalCompositions` = times the body actually executed

| Skip Rate | Meaning | Action |
|-----------|---------|--------|
| > 80% | Compose is efficiently skipping — params are mostly stable | Usually fine. If rate is still high, parent is forcing re-entry. |
| 50-80% | Mixed — some params changing, some stable | Check `paramStates` to find which params are DIFFERENT |
| < 50% | Most entries result in full recomposition | Params are unstable. This composable needs stability work. |
| < 20% | Almost never skips | Strong skipping banner in IDE. Major stability issue. |

## The `$changed` Bitmask

The Compose compiler generates `$changed` parameters for every `@Composable` function. Rebound reads these to determine exactly why a composable recomposed.

### Bitmask Layout

```
Bit 0:    Force-recompose flag (global, not per-param)
Bits 1-3: Parameter 0 state (3 bits)
Bits 4-6: Parameter 1 state (3 bits)
Bits 7-9: Parameter 2 state (3 bits)
...
```

Each `$changed` mask covers up to **10 parameters** (31 usable bits / 3 bits per param).

For functions with >10 params, Compose generates `$changed`, `$changed1`, `$changed2`, etc. Rebound handles all of them via the `changedMasks` comma-separated string.

### ParamState Values

| State | Bits | Label in snapshot | Meaning |
|-------|------|-------------------|---------|
| UNCERTAIN | `000` | `"uncertain"` | Runtime must check equality (`equals()`) |
| SAME | `001` | `"same"` | Compiler proved unchanged at call site |
| DIFFERENT | `010` | `"CHANGED"` | Compiler proved changed at call site |
| STATIC | `100` | `"static"` | Compile-time constant — never changes |

### Reading paramStates in the snapshot

```json
"paramStates": "items=DIFFERENT,isLoading=SAME,onClick=STATIC"
```

This tells you:
- `items` changed → this triggered the recomposition
- `isLoading` didn't change → wasted work if this composable only uses `isLoading`
- `onClick` is a constant → never contributes to recomposition

### The UNCERTAIN Problem

When `$changed` is `0` for all params, every param shows `uncertain`. This is common — the Compose compiler doesn't statically resolve param changes in many cases (especially with complex expressions, ViewModel state, or mutable collections).

When all params are `uncertain`, Compose falls back to runtime `equals()` checks. If your data class doesn't have correct `equals()`, or uses reference equality on collections, the composable will recompose even when the data is identical.

## Forced vs Param-Driven Recomposition

### Forced (`forcedCount`)

Bit 0 of `$changed` is set. Means a parent composable was invalidated and forced all children to re-enter the composition. The child didn't have any param changes — it was dragged along.

**Fix:** Fix the parent. The child is a victim, not the cause.

### Param-Driven (`paramDrivenCount`)

Bit 0 is NOT set, but other bits indicate param changes. This composable recomposed because its own inputs changed.

**Fix:** Stabilize the changing parameters (see below).

### How to tell

```json
"forcedCount": 45,
"paramDrivenCount": 12
```

If `forcedCount >> paramDrivenCount`, the parent is the problem. Fix the parent first.
If `paramDrivenCount >> forcedCount`, this composable's own params are the problem.

## Param Types

The compiler classifies each parameter into one of three types:

| Type | Classified When | Skip Behavior |
|------|----------------|---------------|
| `stable` | Primitives (`Int`, `Long`, `Float`, `Double`, `Boolean`, `Byte`, `Short`, `Char`), `String`, `Unit`, enums, or class annotated `@Stable`/`@Immutable` | Compose uses `equals()` to skip — works well |
| `unstable` | Everything else (data classes without `@Stable`, collections, interfaces) | Compose may not skip efficiently — always rechecks |
| `lambda` | `FunctionN`, `KFunctionN`, or `@Composable` lambda | New instance every recomposition unless captured correctly |

### Reading paramTypes in the snapshot

```json
"paramTypes": "unstable,stable,lambda"
```

Maps positionally to the function parameters.

## Fix Strategies

### Stabilize data classes

```kotlin
// Before — unstable (List is not stable)
data class UiState(val items: List<Item>, val count: Int)

// After — stable
@Immutable
data class UiState(val items: ImmutableList<Item>, val count: Int)
```

### Extract primitives

```kotlin
// Before — passing unstable object just for one field
@Composable
fun UserAvatar(user: User) {
    Image(url = user.avatarUrl)
}

// After — pass only what you need
@Composable
fun UserAvatar(avatarUrl: String) {
    Image(url = avatarUrl)
}
```

### Remember lambdas

```kotlin
// Before — new lambda every recomposition
Button(onClick = { viewModel.submit() })

// After — stable reference
val onSubmit = remember { { viewModel.submit() } }
Button(onClick = onSubmit)

// Or for lambdas that capture changing values
val onClick = remember(itemId) { { viewModel.onItemClick(itemId) } }
```

### Use derivedStateOf for computed values

```kotlin
// Before — recomposes on every scroll pixel
val showButton = scrollState.value > 100

// After — only recomposes when boolean changes
val showButton by remember { derivedStateOf { scrollState.value > 100 } }
```

### Move reads to draw phase

```kotlin
// Before — recomposes on every animation frame
Box(modifier = Modifier.offset(x = animatedX.dp))

// After — only draw phase updates, no recomposition
Box(modifier = Modifier.offset { IntOffset(animatedX.roundToInt(), 0) })

// Or use graphicsLayer for transforms
Box(modifier = Modifier.graphicsLayer { translationX = animatedX })
```

### Fix parent-forced cascades

If `forcedCount` is high, look at the `parent` field and fix that composable first. Common parent issues:
- Reading state at too high a level
- Passing unstable params down
- Not using `key { }` in lists

## @ReboundBudget for Misclassified Composables

When the heuristic gets it wrong:

```kotlin
// Classified as LEAF because no child @Composable calls,
// but it's actually animation-driven via Canvas
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun ParticleCanvas(particles: List<Particle>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { drawCircle(it.color, it.radius, it.center) }
    }
}
```

Don't use `@ReboundBudget` to suppress violations — fix the root cause instead.
