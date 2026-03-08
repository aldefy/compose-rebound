---
sidebar_position: 1
title: "Debugging Recompositions"
---

# Debugging Recompositions

This guide walks through a complete debugging workflow using Rebound, from noticing a problem to shipping a fix.

## Step 1: Notice the problem

The first signal is the status bar. Rebound's IDE plugin shows a summary in the Android Studio status bar: the number of active violations and the worst offender. If you see `2 violations | ProfileHeader 11/s`, something needs attention.

You do not need to be actively looking at the Rebound tool window. The status bar is always visible.

## Step 2: Open Hot Spots -- find the worst offender

Open the Rebound tool window and switch to the **Hot Spots** tab. This is a flat table of every instrumented composable, sortable by rate, budget ratio, and skip percentage.

Sort by **budget ratio** (rate divided by budget). A ratio above 1.0 means the composable is over budget. The summary card at the top shows: `3 violations | 12 near budget | 85 OK`.

Double-click the worst offender to jump to its source file.

```
Composable         Rate    Budget   Ratio   Skip%
ProfileHeader      11/s    5/s      2.20    12%    <-- VIOLATION
UserList.Item{}    38/s    60/s     0.63    45%
HomeScreen          2/s    3/s      0.67    90%
```

## Step 3: Check Timeline -- sustained or spike?

Switch to the **Timeline** tab. Find the composable in the heatmap. The timeline shows the last 60 minutes of activity, with green, yellow, and red cells.

Ask yourself:

- **Sustained red band:** This composable is continuously over budget. Real problem.
- **Brief red spike during scroll:** Likely a scroll burst. Check if the composable is in a `LazyColumn`. If it calms down after scroll ends, it may be acceptable.
- **Red only during specific interaction:** The issue is tied to a user action. Look at the interaction context (IDLE, SCROLLING, ANIMATING, USER_INPUT).

If the violation only fires during IDLE, it is almost certainly a real bug -- nothing should be recomposing rapidly when the user is not interacting.

## Step 4: Open the source file -- check gutter icons

Navigate to the composable's source file. Rebound places gutter icons next to every `@Composable` function:

- **Green dot:** Under budget. Healthy.
- **Yellow dot:** Approaching budget (above 75% of the limit).
- **Red dot:** Over budget. Violation active.

Click the gutter icon for a popup showing rate, budget, skip percentage, and the top changed parameters.

```kotlin
@Composable
fun ProfileHeader(  // <-- Red gutter dot: 11/s | budget: 5/s | skip: 12%
    user: User,
    avatarUrl: String,
    onEditClick: () -> Unit
) {
    // ...
}
```

## Step 5: Check Stability tab -- which param is unstable?

Switch to the **Stability** tab in the tool window. Find `ProfileHeader` in the list. The stability matrix shows each parameter's status:

```
ProfileHeader
  user: User           UNSTABLE   <-- This is the problem
  avatarUrl: String    STABLE
  onEditClick: () -> Unit  STABLE
```

An unstable parameter means the Compose compiler cannot prove equality between recompositions. Even if the value has not changed semantically, Compose treats it as changed and recomposes.

## Step 6: Apply the fix

The fix depends on what you find. Here are the most common patterns:

### Unstable class parameter

**Before:** The `User` class is a plain data class with a mutable list:

```kotlin
data class User(
    val name: String,
    val bio: String,
    val tags: List<String>  // List is unstable in Compose
)
```

**After:** Use `kotlinx.collections.immutable`:

```kotlin
import kotlinx.collections.immutable.ImmutableList

data class User(
    val name: String,
    val bio: String,
    val tags: ImmutableList<String>  // Stable
)
```

### State hoisting problem

**Before:** State is read at the wrong level:

```kotlin
@Composable
fun ProfileScreen(viewModel: ProfileViewModel) {
    val user by viewModel.user.collectAsState()
    val posts by viewModel.posts.collectAsState()

    Column {
        ProfileHeader(user = user)  // Recomposes when posts change too
        PostList(posts = posts)
    }
}
```

**After:** Hoist each state read to its consumer:

```kotlin
@Composable
fun ProfileScreen(viewModel: ProfileViewModel) {
    Column {
        ProfileHeaderWrapper(viewModel)
        PostListWrapper(viewModel)
    }
}

@Composable
private fun ProfileHeaderWrapper(viewModel: ProfileViewModel) {
    val user by viewModel.user.collectAsState()
    ProfileHeader(user = user)
}

@Composable
private fun PostListWrapper(viewModel: ProfileViewModel) {
    val posts by viewModel.posts.collectAsState()
    PostList(posts = posts)
}
```

Now `ProfileHeader` only recomposes when `user` changes, not when `posts` changes.

### Lambda recreation

**Before:** Lambda recreated on every recomposition:

```kotlin
@Composable
fun ProfileHeader(user: User) {
    Button(onClick = { navigateToEdit(user.id) }) {  // New lambda every recomposition
        Text("Edit")
    }
}
```

**After:** Use `remember` or hoist the callback:

```kotlin
@Composable
fun ProfileHeader(user: User, onEditClick: () -> Unit) {
    Button(onClick = onEditClick) {
        Text("Edit")
    }
}
```

## Step 7: Verify the fix

After applying the fix, rebuild and run. Watch the gutter icon change from red to green. Check the Hot Spots tab to confirm the rate dropped below the budget.

```
Composable         Rate    Budget   Ratio   Skip%
ProfileHeader       1/s    5/s      0.20    95%    <-- Fixed
```

## Summary

The full workflow:

1. Status bar shows violations -- something is wrong.
2. Hot Spots tab -- find the worst offender by budget ratio.
3. Timeline tab -- determine if it is sustained or a temporary spike.
4. Source file gutter icon -- see the problem inline.
5. Stability tab -- identify which parameter is causing recompositions.
6. Fix the root cause: stabilize types, hoist state, or memoize lambdas.
7. Verify the gutter icon turns green.
