# CLI Usage

Rebound includes a shell-based CLI (`rebound-cli.sh`) and Gradle tasks for querying live metrics from a running app.

## Commands

| Command | Description |
|---------|-------------|
| `./rebound-cli.sh snapshot` | Full JSON metrics for all tracked composables (default if no arg) |
| `./rebound-cli.sh summary` | Top 10 composables by current recomposition rate (human-readable) |
| `./rebound-cli.sh telemetry` | Anonymized aggregate stats by budget class |
| `./rebound-cli.sh ping` | Health check — expects `pong` response |
| `./rebound-cli.sh watch` | Live updates every 1s (clears screen, re-runs summary) |

### Gradle Tasks

Same commands available as Gradle tasks:

```bash
./gradlew reboundSnapshot
./gradlew reboundSummary
./gradlew reboundPing
./gradlew reboundTelemetry
```

All tasks are in the `rebound` group. Default port: `18462`.

## Connection Auto-Detection

The CLI tries these in order — first success wins:

| Priority | Mode | How |
|----------|------|-----|
| 1 | Direct TCP | `nc localhost 18462` — works for iOS simulator or already-forwarded port |
| 2 | ADB forward | `adb forward tcp:18462 localabstract:rebound` — Android device/emulator |
| 3 | `devicectl` console | Parses `[Rebound:<cmd>]` structured log lines — iOS physical device without relay |

### Direct netcat query

```bash
echo "snapshot" | nc localhost 18462
echo "summary" | nc localhost 18462
echo "ping" | nc localhost 18462
```

### ADB forward (Android)

The CLI runs this automatically, but you can do it manually:

```bash
adb forward tcp:18462 localabstract:rebound
echo "snapshot" | nc localhost 18462
```

## JSON Snapshot Structure

`./rebound-cli.sh snapshot` returns:

```json
{
  "composables": {
    "com.example.HomeScreen": {
      "budgetClass": "SCREEN",
      "budgetPerSecond": 3,
      "totalCompositions": 47,
      "peakRate": 12,
      "currentRate": 8,
      "totalEnters": 120,
      "skipCount": 73,
      "skipRate": 0.608,
      "forcedCount": 30,
      "paramDrivenCount": 17,
      "lastInvalidation": "MutableState<List<Item>>@1a2b3c4",
      "parent": "com.example.MainNavHost",
      "paramStates": "items=DIFFERENT,isLoading=SAME",
      "depth": 1,
      "paramTypes": "unstable,stable"
    }
  }
}
```

### Field Reference

| Field | Type | Description |
|-------|------|-------------|
| `budgetClass` | String | One of: SCREEN, LEAF, CONTAINER, INTERACTIVE, LIST_ITEM, ANIMATED, UNKNOWN |
| `budgetPerSecond` | Int | Base budget for this class (before dynamic scaling) |
| `totalCompositions` | Long | Total non-skip executions since tracking started |
| `peakRate` | Int | Highest recompositions/sec ever recorded |
| `currentRate` | Int | Current recompositions/sec (1-second sliding window) |
| `totalEnters` | Long | Total calls including skipped ones |
| `skipCount` | Long | `totalEnters - totalCompositions` |
| `skipRate` | Float | `0.0` (never skipped) to `1.0` (always skipped) |
| `forcedCount` | Long | Recompositions where bit 0 of `$changed` was set (parent forced) |
| `paramDrivenCount` | Long | Recompositions driven by param changes (not forced) |
| `lastInvalidation` | String | `toString()` of the State object that last triggered recomposition |
| `parent` | String | Fully-qualified name of parent composable, or `""` |
| `paramStates` | String | Per-param state: `"name=DIFFERENT,onClick=STATIC"` |
| `depth` | Int | Call-tree depth (0 = root) |
| `paramTypes` | String | Per-param type classification: `"stable,lambda,unstable"` |

### Filtering with jq

```bash
# Composables over budget
./rebound-cli.sh snapshot | jq '.composables | to_entries[] | select(.value.currentRate > .value.budgetPerSecond)'

# Top 5 by current rate
./rebound-cli.sh snapshot | jq '[.composables | to_entries[] | {name: .key, rate: .value.currentRate}] | sort_by(-.rate) | .[:5]'

# Low skip rate composables (potential optimization targets)
./rebound-cli.sh snapshot | jq '.composables | to_entries[] | select(.value.skipRate < 0.5) | {name: .key, skipRate: .value.skipRate, paramTypes: .value.paramTypes}'

# Forced recomposition hotspots
./rebound-cli.sh snapshot | jq '.composables | to_entries[] | select(.value.forcedCount > .value.paramDrivenCount) | {name: .key, forced: .value.forcedCount, paramDriven: .value.paramDrivenCount}'
```

## Telemetry Output

`./rebound-cli.sh telemetry` returns anonymized aggregate stats (no composable names):

```json
{
  "composableCount": 45,
  "budgetClassDistribution": {
    "SCREEN": 3,
    "CONTAINER": 15,
    "LEAF": 12,
    "INTERACTIVE": 8,
    "ANIMATED": 4,
    "LIST_ITEM": 3
  },
  "violationCount": 5,
  "violationsByClass": { "SCREEN": 1, "CONTAINER": 2, "LEAF": 2 },
  "averageRateByClass": { "SCREEN": 2.3, "CONTAINER": 6.1 },
  "peakRateByClass": { "SCREEN": 12.0, "CONTAINER": 18.0 },
  "skipRateByClass": { "SCREEN": 0.72, "LEAF": 0.45 }
}
```

Telemetry must be explicitly opted in: `ReboundTelemetry.enabled = true`.

## iOS Relay Setup

For iOS physical devices, the runtime connects outbound to a Mac-side WebSocket relay via Bonjour auto-discovery.

```bash
# Build the relay (one-time)
./tools/build-relay.sh

# Start the relay on your Mac
./tools/rebound-relay
# → TCP :18462 (CLI/IDE), WebSocket :18463 (devices), Bonjour: _rebound._tcp

# Device auto-discovers relay on same WiFi
./rebound-cli.sh snapshot   # works transparently through relay
```

Override Bonjour discovery if needed:
```bash
REBOUND_RELAY_HOST=192.168.1.100:18463 ./rebound-cli.sh snapshot
```

## Baseline Comparison

Capture a baseline, make changes, then compare:

```kotlin
// In test or debug setup
val baseline = ReboundTracker.exportSnapshot()
// ... run the scenario ...
val current = ReboundTracker.exportSnapshot()

val regressions = ReboundBaseline.compare(
    baseline = baseline,
    current = current,
    regressionThreshold = 20  // 20% increase = regression
)
```

Each `Regression` contains: `composable`, `budgetClass`, `baselinePeakRate`, `currentPeakRate`, `increasePercent`.
