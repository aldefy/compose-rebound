---
title: Custom Budgets
---

# Custom Budgets

The compiler plugin classifies composables automatically based on name patterns and IR structure. When the heuristic gets it wrong, use the `@ReboundBudget` annotation to override.

## The @ReboundBudget Annotation

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun PhysicsSticker(offset: Offset) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
            .size(80.dp)
            .background(Color.Blue, CircleShape)
    )
}
```

The annotation accepts a single `BudgetClass` value:

| BudgetClass | Rate Limit | Use When |
|------------|-----------|----------|
| `SCREEN` | 3/s | Root-level screen composables |
| `LEAF` | 5/s | Terminal composables with no children |
| `CONTAINER` | 10/s | Layout wrappers with children |
| `INTERACTIVE` | 30/s | User input handlers |
| `LIST_ITEM` | 60/s | Items in lazy lists |
| `ANIMATED` | 120/s | Animation or gesture-driven composables |

The annotation always takes priority over heuristic classification (priority 1 in the inference chain).

## When to Override

### Sensor-driven composables

Composables driven by accelerometer, gyroscope, or other sensor data produce continuous updates. The compiler classifies them as LEAF (no child composable calls) with a budget of 5/s, but sensor data arrives at 60+ Hz.

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun TiltIndicator(pitch: Float, roll: Float) {
    Canvas(modifier = Modifier.size(100.dp)) {
        // Draw orientation indicator based on device tilt
        rotate(roll) {
            drawLine(Color.White, Offset(50f, 0f), Offset(50f, 100f), strokeWidth = 2f)
        }
    }
}
```

Without the override, this would violate the LEAF budget on every sensor update. ANIMATED (120/s) is appropriate because the behavior is analogous to animation.

### Physics-based animations

Composables using custom physics (spring, decay, fling) without Compose's built-in `animate*` APIs. The compiler cannot detect these as animations because the IR does not contain `animate*` calls.

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun SpringElement(targetOffset: Offset) {
    var currentOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(targetOffset) {
        // Custom spring physics loop
        val spring = SpringSimulation(stiffness = 200f, damping = 15f)
        while (!spring.isAtRest) {
            currentOffset = spring.advance(16f)
            delay(16)
        }
    }

    Box(modifier = Modifier.offset {
        IntOffset(currentOffset.x.toInt(), currentOffset.y.toInt())
    })
}
```

### Screen composables without "Screen" in the name

The heuristic looks for "Screen" or "Page" in the function name. If your screen composable uses a different naming convention, override it:

```kotlin
@ReboundBudget(BudgetClass.SCREEN)
@Composable
fun Dashboard() {
    // This is a full screen, but the name doesn't contain "Screen" or "Page"
    Scaffold {
        // ...
    }
}
```

### High-frequency containers

Some containers legitimately recompose more than 10/s -- for example, a container that wraps a real-time data feed:

```kotlin
@ReboundBudget(BudgetClass.INTERACTIVE)
@Composable
fun LiveDataContainer(data: List<DataPoint>) {
    Column {
        data.forEach { point ->
            DataRow(point)
        }
    }
}
```

## When NOT to Override

Do not override budgets to silence legitimate violations. If a SCREEN composable is recomposing at 8/s, the fix is to restructure the composition tree, not to change the budget to CONTAINER.

Common mistakes:

- Overriding to ANIMATED just because the composable is fast -- if it is not animation-driven, the high rate is a real problem
- Overriding to SCREEN for composables that are not actually screens -- this will produce false positives from the stricter budget
- Using UNKNOWN as a catch-all -- this defeats the purpose of budget-based monitoring

## Checking Classifications

To see what budget class the compiler assigned to each composable, use the CLI:

```bash
./rebound-cli.sh snapshot | python3 -c "
import json, sys
data = json.load(sys.stdin)
for c in sorted(data['composables'], key=lambda x: x['budgetClass']):
    print(f\"{c['budgetClass']:12} {c['budget']:>3}/s  {c['simpleName']}\")
"
```

This lists every composable with its budget class, making it easy to spot misclassifications.
