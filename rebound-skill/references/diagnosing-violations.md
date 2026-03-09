# Diagnosing Violations

## Diagnostic Workflow

Follow this sequence when a composable exceeds its budget:

```
1. Hot Spots    → Which composables are over budget?
2. Timeline     → When do the spikes happen? Correlated with what?
3. Stability    → Are params DIFFERENT/UNCERTAIN/unstable/lambda?
4. Source       → Read the composable code — where is state read?
5. Fix          → Apply the pattern from below, re-verify
```

### Step 1: Get the data

```bash
# CLI
./rebound-cli.sh snapshot | jq '.composables | to_entries[] | select(.value.currentRate > .value.budgetPerSecond)'

# Or use the IDE Hot Spots tab — sorted by severity (OVER > NEAR > OK)
```

### Step 2: Read the snapshot fields

For any violating composable, check these fields:

| Field | What it tells you |
|-------|-------------------|
| `currentRate` vs `budgetPerSecond` | How far over budget |
| `skipRate` | Low (<50%) = params changing. High (>80%) + high rate = forced from parent |
| `forcedCount` vs `paramDrivenCount` | Forced = parent's fault. Param-driven = this composable's inputs |
| `paramStates` | Which params are `DIFFERENT`, `SAME`, `STATIC`, `uncertain` |
| `paramTypes` | `stable`, `unstable`, or `lambda` for each param |
| `parent` | Who's calling this composable |
| `lastInvalidation` | Which `State` object triggered the recomposition |

### Step 3: Match the pattern below and apply the fix

---

## Violation Patterns by Budget Class

### SCREEN violations (>3/s)

**Pattern: Reading frequently-changing state at screen level**

```kotlin
// BAD — screen reads scrollState every frame
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val scrollState = rememberScrollState()
    val showHeader = scrollState.value > 100  // reads scroll position at screen level!

    Column {
        if (showHeader) { Header() }
        ContentList(scrollState)
    }
}
```

```kotlin
// GOOD — derive state, or push read down
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val scrollState = rememberScrollState()

    Column {
        // derivedStateOf converts continuous scroll to boolean change
        val showHeader by remember { derivedStateOf { scrollState.value > 100 } }
        if (showHeader) { Header() }
        ContentList(scrollState)
    }
}
```

**Pattern: Unstable ViewModel state**

```kotlin
// BAD — UiState is a data class with a List (unstable)
data class HomeUiState(
    val items: List<Item>,  // List is unstable
    val isLoading: Boolean
)

@Composable
fun HomeScreen(state: HomeUiState) { ... }
```

```kotlin
// GOOD — mark as Immutable or use ImmutableList
@Immutable
data class HomeUiState(
    val items: ImmutableList<Item>,
    val isLoading: Boolean
)
```

---

### LEAF violations (>5/s)

**Pattern: Unstable parameter passed to a simple composable**

```kotlin
// BAD — UserInfo is unstable, Text recomposes every time parent does
data class UserInfo(val name: String, val avatar: String)

@Composable
fun UserLabel(info: UserInfo) {
    Text(text = info.name)
}
```

```kotlin
// GOOD — pass only the primitive you need
@Composable
fun UserLabel(name: String) {
    Text(text = name)
}

// OR stabilize the class
@Stable
data class UserInfo(val name: String, val avatar: String)
```

**Pattern: Lambda causing recomposition**

```kotlin
// BAD — new lambda instance every recomposition
@Composable
fun ParentComposable(items: List<String>) {
    items.forEach { item ->
        LeafItem(
            text = item,
            onClick = { handleClick(item) }  // new lambda every time
        )
    }
}
```

```kotlin
// GOOD — remember the lambda
@Composable
fun ParentComposable(items: List<String>) {
    items.forEach { item ->
        val onClick = remember(item) { { handleClick(item) } }
        LeafItem(text = item, onClick = onClick)
    }
}
```

---

### CONTAINER violations (>10/s)

**Pattern: Unstable content lambda**

```kotlin
// BAD — content lambda is re-created on every recomposition
@Composable
fun CardContainer(title: String, content: @Composable () -> Unit) {
    Card {
        Text(title)
        content()  // if parent recomposes, this lambda is "new"
    }
}
```

The fix is usually in the **caller** — stabilize what's passed to the container.

**Pattern: State read that should be in a child**

```kotlin
// BAD — Box reads animation value, recomposes the whole container
@Composable
fun AnimatedContainer() {
    val alpha by animateFloatAsState(targetValue = if (visible) 1f else 0f)
    Box(modifier = Modifier.alpha(alpha)) {  // Box recomposes every frame
        HeavyContent()
    }
}
```

```kotlin
// GOOD — use graphicsLayer to read animation in draw phase only
@Composable
fun AnimatedContainer() {
    val alpha by animateFloatAsState(targetValue = if (visible) 1f else 0f)
    Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
        HeavyContent()  // never recomposes from animation
    }
}
```

---

### INTERACTIVE violations (>30/s)

**Pattern: TextField with unstable state holder**

```kotlin
// BAD — entire form recomposes on every keystroke
@Composable
fun LoginForm(state: LoginFormState) {
    Column {
        TextField(
            value = state.email,  // LoginFormState is unstable
            onValueChange = { state.email = it }
        )
        TextField(
            value = state.password,
            onValueChange = { state.password = it }
        )
        SubmitButton(state)  // recomposes on every keystroke
    }
}
```

```kotlin
// GOOD — separate state per field, isolate recomposition scope
@Composable
fun LoginForm() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column {
        TextField(value = email, onValueChange = { email = it })
        TextField(value = password, onValueChange = { password = it })
        SubmitButton(email, password)  // only recomposes when both are read
    }
}
```

---

### LIST_ITEM violations (>60/s during scroll)

**Pattern: Heavy computation inside item composable**

```kotlin
// BAD — formatting runs on every recomposition
@Composable
fun MessageItem(message: Message) {
    val formatted = formatTimestamp(message.timestamp)  // expensive, runs every recompose
    val highlighted = highlightMentions(message.text)   // regex, runs every recompose

    Row {
        Text(highlighted)
        Text(formatted)
    }
}
```

```kotlin
// GOOD — remember expensive computations
@Composable
fun MessageItem(message: Message) {
    val formatted = remember(message.timestamp) { formatTimestamp(message.timestamp) }
    val highlighted = remember(message.text) { highlightMentions(message.text) }

    Row {
        Text(highlighted)
        Text(formatted)
    }
}
```

**Pattern: Missing key in LazyColumn**

```kotlin
// BAD — no stable key, items get fully recomposed on scroll
LazyColumn {
    items(messages) { message ->
        MessageItem(message)
    }
}
```

```kotlin
// GOOD — stable key enables item reuse
LazyColumn {
    items(messages, key = { it.id }) { message ->
        MessageItem(message)
    }
}
```

---

### ANIMATED violations (>120/s)

**Pattern: Creating new animation specs every recomposition**

```kotlin
// BAD — new AnimationSpec allocated on every recomposition
@Composable
fun PulsingDot() {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.2f else 1f,
        animationSpec = spring(dampingRatio = 0.3f)  // new Spring() every time
    )
    Box(Modifier.scale(scale))
}
```

```kotlin
// GOOD — remember the spec
@Composable
fun PulsingDot() {
    val spec = remember { spring<Float>(dampingRatio = 0.3f) }
    val scale by animateFloatAsState(
        targetValue = if (active) 1.2f else 1f,
        animationSpec = spec
    )
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
}
```

If an ANIMATED composable exceeds 120/s, it's likely doing non-animation work in its body. Move state reads to `graphicsLayer` or `drawBehind` lambdas to restrict recomposition to the draw phase.

---

## Decision Tree

```
Rate > budget?
├── Yes → Check skipRate
│   ├── skipRate < 50% → Params are changing
│   │   ├── paramTypes has "unstable" → Stabilize: @Stable, @Immutable, or extract primitives
│   │   ├── paramTypes has "lambda" → remember { } the lambda at call site
│   │   └── paramStates all "uncertain" → $changed mask is 0 → runtime equality check needed
│   │       └── Ensure equals() is correct on data classes
│   │
│   └── skipRate > 80% → Being forced by parent
│       ├── Check parent field → Is parent also violating?
│       │   └── Yes → Fix the parent first (violations cascade)
│       └── forcedCount >> paramDrivenCount
│           └── Parent is recomposing unnecessarily, dragging this along
│
└── No → OK, no action needed
```
