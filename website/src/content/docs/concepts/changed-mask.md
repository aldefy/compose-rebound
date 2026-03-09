---
sidebar_position: 5
title: $changed Bitmask
---

# The $changed Bitmask

The Compose compiler injects `$changed` parameters into every `@Composable` function. Rebound decodes these bitmasks at runtime to tell you exactly which parameters caused each recomposition.

## How the Bitmask Works

Each `@Composable` function receives one or more `$changed` integer parameters. The bitmask encodes the stability state of each parameter using a fixed bit layout.

### Bit layout

```
$changed bitmask (up to 10 params per mask):
+------+----------+----------+----------+---+
| bit0 | bits 1-3 | bits 4-6 | bits 7-9 |...|
|force | param 0  | param 1  | param 2  |   |
+------+----------+----------+----------+---+
```

- **Bit 0** -- the force flag. When set, this recomposition was forced by a parent invalidation.
- **Bits 1-3** -- stability state for the first parameter
- **Bits 4-6** -- stability state for the second parameter
- And so on, 3 bits per parameter, up to 10 parameters per `$changed` integer

### Per-parameter bit values

| Bits | Value | Meaning |
|------|-------|---------|
| `000` | UNCERTAIN | The compiler could not determine whether this parameter changed |
| `001` | SAME | The parameter value is the same as the previous composition |
| `010` | DIFFERENT | The parameter value changed since the previous composition |
| `100` | STATIC | The parameter is a compile-time constant and will never change |

### Multiple masks

For composables with more than 10 parameters, the Compose compiler generates additional masks: `$changed1`, `$changed2`, and so on. Rebound collects all masks into a comma-separated string and decodes each one.

## How Rebound Surfaces This Data

### At compile time

The IR transformer extracts parameter names from the function signature and passes them alongside the `$changed` masks:

```kotlin
ReboundTracker.onComposition(
    key = "com.example.ProfileHeader",
    budgetClass = LEAF,
    changedMask = `$changed`,
    paramNames = "avatarUrl,displayName,isOnline",
    changedMasks = "\$changed"
)
```

### At runtime

`ChangedMaskDecoder` extracts the per-parameter state from the integer bitmask:

```kotlin
// For a composable with 3 params and $changed = 0b0_010_001_010
// Bit 0 (force): 0 -> not forced
// Bits 1-3 (param 0 "avatarUrl"): 010 -> DIFFERENT
// Bits 4-6 (param 1 "displayName"): 001 -> SAME
// Bits 7-9 (param 2 "isOnline"): 010 -> DIFFERENT
```

This produces the violation output:

```
params: avatarUrl=DIFFERENT, displayName=SAME, isOnline=DIFFERENT
```

### In the IDE plugin

The **Stability tab** presents a parameter stability matrix showing each parameter's state across compositions. This surfaces patterns like "parameter X is always DIFFERENT" that indicate an unstable type or frequently-changing upstream state.

## Limitations

### Most parameters report UNCERTAIN

In practice, the Compose compiler reports `UNCERTAIN` (bits `000`) for most parameters in many composables. This happens because:

- The parameter type is not annotated with `@Stable` or `@Immutable`
- The compiler cannot statically prove stability
- Strong Skipping Mode may skip the composable at runtime even when the mask says UNCERTAIN, but the mask itself still reads as UNCERTAIN

This is a limitation of the Compose compiler's static analysis, not a Rebound issue. When you see UNCERTAIN, it means the compiler did not have enough information to determine the parameter's state at compile time. The runtime may still skip correctly based on `equals()` checks.

### STATIC is rare

STATIC (bits `100`) only appears for parameters that are compile-time constants, such as string literals or hardcoded values. Most real-world parameters are not STATIC.

### Workaround for UNCERTAIN

If parameter state tracking is important for a specific composable, annotate the parameter types with `@Stable` or `@Immutable`:

```kotlin
@Stable
data class UserProfile(
    val name: String,
    val avatarUrl: String,
    val isOnline: Boolean
)

@Composable
fun ProfileHeader(user: UserProfile) {
    // $changed will now report SAME or DIFFERENT instead of UNCERTAIN
    // because UserProfile is @Stable
}
```

This gives the Compose compiler enough information to produce meaningful bitmask values, which Rebound can then decode and surface.
