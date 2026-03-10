---
title: Anonymous Lambda Resolution
---

# Anonymous Lambda Resolution

Compose uses lambdas extensively. `Scaffold`, `NavHost`, `Column`, `Row`, `LazyColumn` -- all take `@Composable` lambda parameters. Each lambda is a composable function that Rebound instruments. Without name resolution, the composable tree is 80% `<anonymous>`.

## The Problem

When you inspect the IR, anonymous composable lambdas produce names like:

```
com.example.HomeScreen.<anonymous>
com.example.ComposableSingletons$MainActivityKt.lambda-3.<anonymous>
```

You see a recomposition violation on `<anonymous>` and have no idea if it is the Scaffold content, the NavHost builder, or a Column's children.

## How Layout Inspector Solves It

Layout Inspector reads `sourceInformation()` strings from the Compose slot table. These are compact tags the Compose compiler injects into every composable call site. The tag contains the source file name, line number, and function name. Layout Inspector parses this tag to display readable names.

This works because Layout Inspector has access to the running Compose runtime's slot table, which Rebound does not access (by design -- Rebound uses its own tracking, not the slot table).

## How Rebound Solves It

Rebound resolves names at compile time in the IR transformer. When `ReboundIrTransformer` visits an anonymous composable lambda, it:

1. Identifies the function as anonymous (FQN contains `<anonymous>`)
2. Finds the enclosing non-anonymous composable (e.g., `HomeScreen`)
3. Walks the lambda's function body in the IR
4. Finds the first user-visible `@Composable` call that is not a Compose runtime internal
5. Uses that call's simple name as the key

### Resolution examples

| Raw IR Name | Resolved Name |
|------------|---------------|
| `com.example.HomeScreen.<anonymous>` (body calls `Scaffold`) | `HomeScreen.Scaffold{}` |
| `com.example.ExerciseCard.<anonymous>` (body calls `Column`) | `ExerciseCard.Column{}` |
| `com.example.NavGraph.<anonymous>` (body calls `composable`) | `NavGraph.composable{}` |
| `com.example.ComposableSingletons$MainActivityKt.lambda-3.<anonymous>` | `MainActivity.NavHost{}` |

### The `{}` suffix convention

The `{}` suffix distinguishes a content lambda from the composable function itself:

- `HomeScreen` -- the `HomeScreen` composable function
- `HomeScreen.Scaffold{}` -- the content lambda passed to `Scaffold` inside `HomeScreen`

This makes tree navigation unambiguous. When you see `HomeScreen.Scaffold{}` in the tree, you know it is the Scaffold's content block, not the Scaffold composable itself.

## Implementation

The core resolution logic:

```kotlin
private fun resolveComposableKey(function: IrFunction): String {
    val raw = function.kotlinFqName.asString()
    if (!raw.contains("<anonymous>")) return raw

    val pkg = extractPackage(raw)
    val parentName = findEnclosingName(function)
    val primaryCall = findPrimaryComposableCall(function)

    if (primaryCall != null) {
        return "$pkg$parentName.$primaryCall{}"
    }
    // Fallback: counter-based naming
    val counter = anonymousCounters.getOrPut(parentName) { AtomicInteger(0) }
    return "$pkg$parentName.lambda${counter.incrementAndGet()}{}"
}
```

`findPrimaryComposableCall` walks the function body's IR statements, looking for `IrCall` nodes where the callee is a `@Composable` function and the callee's package is not `androidx.compose.runtime` (to skip internal runtime calls like `remember`, `CompositionLocalProvider`, etc.).

### Fallback

When no suitable composable call is found in the lambda body (e.g., the lambda only contains non-composable expressions), Rebound falls back to a counter-based name: `ParentName.lambda1{}`, `ParentName.lambda2{}`, etc. The counter is per-parent, so names are stable across compilations as long as the lambda order does not change.

## Limitations

- If a lambda body calls multiple composable functions, only the first one is used for the name. This is usually correct (the primary layout composable is typically the first call), but can be misleading in edge cases.
- Composable lambdas that are stored in variables and passed around may not resolve correctly if the variable assignment is in a different scope than the call.
- The resolution is deterministic per compilation but may change if the source code is reordered (the first composable call may change).
