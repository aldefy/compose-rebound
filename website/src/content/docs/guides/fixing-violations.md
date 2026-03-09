---
sidebar_position: 2
title: "Fixing Violations"
---

# Fixing Violations

This guide covers common violation patterns organized by budget class, with before/after code examples for each.

## SCREEN violations (budget: 3/s)

Screen composables should recompose rarely -- only on navigation or major state changes. A SCREEN violation almost always means state is being read too broadly.

### Problem: Reading multiple state flows at the screen level

**Before:**

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val user by viewModel.user.collectAsState()
    val feed by viewModel.feed.collectAsState()
    val notifications by viewModel.notifications.collectAsState()

    // HomeScreen recomposes whenever ANY of these three states change
    Scaffold(
        topBar = { TopBar(user = user, notificationCount = notifications.size) },
        content = { FeedList(feed = feed) }
    )
}
```

**After:** Push state reads down to the composables that actually use them:

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    Scaffold(
        topBar = { TopBarWrapper(viewModel) },
        content = { FeedListWrapper(viewModel) }
    )
}

@Composable
private fun TopBarWrapper(viewModel: HomeViewModel) {
    val user by viewModel.user.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    TopBar(user = user, notificationCount = notifications.size)
}

@Composable
private fun FeedListWrapper(viewModel: HomeViewModel) {
    val feed by viewModel.feed.collectAsState()
    FeedList(feed = feed)
}
```

Now `HomeScreen` itself only recomposes on navigation changes. Each wrapper only recomposes when its own state changes.

### Problem: Unstable ViewModel parameter

If you pass a `ViewModel` directly as a parameter, it is inherently unstable. The Compose compiler cannot prove that a ViewModel instance is equal across recompositions.

**Fix:** Either annotate with `@Stable` if the ViewModel is genuinely stable, or restructure to pass only the state values the composable needs.

## CONTAINER violations (budget: 8/s)

Containers (`Column`, `Row`, `Box`, `Card`, `Surface`) sit between screens and leaves. A CONTAINER violation usually means a child is invalidating the parent.

### Problem: Child state read in parent scope

**Before:**

```kotlin
@Composable
fun UserCard(userId: String, repository: UserRepository) {
    val user by repository.getUser(userId).collectAsState(initial = null)

    Card {
        if (user != null) {
            Avatar(url = user!!.avatarUrl)
            Text(user!!.name)
            OnlineIndicator(isOnline = user!!.isOnline)  // Changes every 5 seconds
        }
    }
}
```

The `isOnline` field changes every 5 seconds. Because `user` is read at the `Card` level, the entire card recomposes -- including `Avatar` and `Text` that have not changed.

**After:** Isolate the frequently changing part:

```kotlin
@Composable
fun UserCard(userId: String, repository: UserRepository) {
    val user by repository.getUser(userId).collectAsState(initial = null)

    Card {
        if (user != null) {
            Avatar(url = user!!.avatarUrl)
            Text(user!!.name)
            OnlineStatus(userId = userId, repository = repository)
        }
    }
}

@Composable
private fun OnlineStatus(userId: String, repository: UserRepository) {
    val isOnline by repository.getOnlineStatus(userId).collectAsState(initial = false)
    OnlineIndicator(isOnline = isOnline)
}
```

Now the card only recomposes when the user's name or avatar changes. The online indicator recomposes independently.

### Problem: Key missing in LazyColumn

```kotlin
LazyColumn {
    items(users) { user ->
        UserCard(user = user)  // No key -- recomposes all items on list change
    }
}
```

**Fix:** Always provide a stable key:

```kotlin
LazyColumn {
    items(users, key = { it.id }) { user ->
        UserCard(user = user)
    }
}
```

## LEAF violations (budget: 5/s)

Leaf composables (`Text`, `Icon`, `Image`, custom composables with no children) have tight budgets because they should rarely recompose. A LEAF violation often means upstream state is pushing updates too frequently.

### Problem: Continuous sensor or timer updates

**Before:**

```kotlin
@Composable
fun TemperatureDisplay(sensorFlow: Flow<Float>) {
    val temperature by sensorFlow.collectAsState(initial = 0f)
    Text("${temperature}C")  // Updates 30 times per second from sensor
}
```

**After:** Debounce the upstream state:

```kotlin
@Composable
fun TemperatureDisplay(sensorFlow: Flow<Float>) {
    val temperature by remember(sensorFlow) {
        sensorFlow
            .distinctUntilChanged { old, new -> abs(old - new) < 0.5f }
            .debounce(200)
    }.collectAsState(initial = 0f)

    Text("${temperature}C")  // Updates only on meaningful changes
}
```

### Problem: Formatting in recomposition scope

**Before:**

```kotlin
@Composable
fun PriceTag(amount: Double) {
    val formatted = NumberFormat.getCurrencyInstance().format(amount)  // New object every recomposition
    Text(formatted)
}
```

**After:** Remember the formatted result:

```kotlin
@Composable
fun PriceTag(amount: Double) {
    val formatted = remember(amount) {
        NumberFormat.getCurrencyInstance().format(amount)
    }
    Text(formatted)
}
```

## ANIMATED misclassification (budget: 120/s)

Sometimes a composable that is driven by animation is not classified as ANIMATED because its name does not match animation patterns. It triggers violations against a lower budget.

### Problem: Custom animation composable not recognized

**Before:**

```kotlin
@Composable
fun PulsingDot(progress: Float) {  // Classified as LEAF (5/s), actual rate: 60/s
    val scale = lerp(0.8f, 1.2f, progress)
    Box(
        Modifier
            .size(12.dp)
            .scale(scale)
            .background(Color.Red, CircleShape)
    )
}
```

**After:** Override the budget with `@ReboundBudget`:

```kotlin
@ReboundBudget(BudgetClass.ANIMATED)
@Composable
fun PulsingDot(progress: Float) {  // Now classified as ANIMATED (120/s)
    val scale = lerp(0.8f, 1.2f, progress)
    Box(
        Modifier
            .size(12.dp)
            .scale(scale)
            .background(Color.Red, CircleShape)
    )
}
```

The `@ReboundBudget` annotation tells the compiler plugin to skip heuristic classification and use the specified budget class directly.

### When to override vs. when to fix

- **Override** when the composable genuinely needs a high recomposition rate (animation, gesture tracking, physics simulation).
- **Fix** when the high rate is caused by a bug (unstable parameter, missing `remember`, broad state read).

If you are unsure, check the **Stability** tab. If parameters are marked DIFFERENT on every recomposition, the problem is likely an unstable type -- fix the type rather than raising the budget.

## General principles

1. **Read state as close to where it is used as possible.** The higher in the tree you read state, the larger the subtree that recomposes.
2. **Use stable types.** Prefer `ImmutableList`, `ImmutableMap` from `kotlinx.collections.immutable`. Avoid `List`, `Map`, `Set` as parameters to composables.
3. **Provide keys in lazy layouts.** Without keys, the entire list recomposes on any change.
4. **Debounce high-frequency sources.** Sensors, timers, and network polling should not push raw updates into composition.
5. **Use `derivedStateOf` for computed values.** If a composable depends on a transformation of state, wrap it in `derivedStateOf` to avoid recomposition when the derived value has not changed.

```kotlin
val showButton by remember {
    derivedStateOf { scrollState.firstVisibleItemIndex > 0 }
}
```
