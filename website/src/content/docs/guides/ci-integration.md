---
title: "CI Integration"
---

# CI Integration

:::info Future Feature
CI budget gates are on the Rebound roadmap. This page describes the planned approach and how it will work. The implementation is not yet available.
:::

## Vision

Rebound's runtime collects recomposition rates and budget violations during app execution. CI integration will make it possible to export those metrics, compare them across builds, and fail a pull request if budgets regress.

No other tool correlates code changes with runtime recomposition regressions in CI. This is the feature that will make it possible to catch performance problems before they reach production.

## How it will work

### Step 1: Capture a baseline snapshot

Run your app's UI tests (or an instrumented test scenario) with Rebound active. Use the CLI `snapshot` command to export the current state:

```bash
adb forward tcp:18462 localabstract:rebound
./rebound-cli.sh snapshot > rebound-baseline.json
```

The snapshot JSON contains every instrumented composable, its rate, budget class, budget ratio, skip percentage, and violation status.

### Step 2: Compare across builds

On each pull request, run the same test scenario and capture a new snapshot. Compare it against the baseline:

```bash
./rebound-cli.sh snapshot > rebound-pr.json

# Compare (planned CLI command)
./rebound-cli.sh compare --baseline rebound-baseline.json --current rebound-pr.json
```

The comparison will report:

- New violations introduced by this PR
- Existing violations that worsened
- Composables whose budget ratio increased significantly
- Composables that improved (moved from violation to healthy)

### Step 3: Fail the PR on regression

Set a threshold for acceptable regression. If any composable crosses from healthy to violation, or if the total violation count increases, fail the check.

## Example GitHub Actions workflow

This is a conceptual workflow. The exact CLI commands will be finalized when the feature ships.

```yaml
name: Rebound Budget Check

on:
  pull_request:
    branches: [master]

jobs:
  rebound-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Build debug APK
        run: ./gradlew :sample:assembleDebug

      - name: Start emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          script: |
            # Install and launch the app
            adb install sample/build/outputs/apk/debug/sample-debug.apk
            adb shell am start -n com.example.sample/.MainActivity

            # Wait for app to stabilize
            sleep 10

            # Forward the Rebound socket
            adb forward tcp:18462 localabstract:rebound

            # Run a UI test scenario to exercise composables
            ./gradlew :sample:connectedDebugAndroidTest

            # Capture the recomposition snapshot
            ./rebound-cli.sh snapshot > rebound-snapshot.json

            # Compare against baseline (committed in repo)
            ./rebound-cli.sh compare \
              --baseline ci/rebound-baseline.json \
              --current rebound-snapshot.json \
              --fail-on-regression
```

## Planned output format

The comparison output will be structured for both human reading and machine parsing:

```json
{
  "summary": {
    "total_composables": 29,
    "new_violations": 1,
    "worsened": 2,
    "improved": 1,
    "unchanged": 25
  },
  "regressions": [
    {
      "composable": "ProfileHeader",
      "baseline_rate": 3,
      "current_rate": 11,
      "budget": 5,
      "status": "new_violation"
    }
  ],
  "improvements": [
    {
      "composable": "UserCard",
      "baseline_rate": 9,
      "current_rate": 4,
      "budget": 5,
      "status": "resolved"
    }
  ]
}
```

## What you can do today

While CI budget gates are in development, you can still use Rebound in your CI pipeline:

1. **Run the CLI `summary` command** after instrumented tests to log the current state.
2. **Grep for violations** in the output and fail the build if any appear.
3. **Store snapshots as build artifacts** to manually compare across commits.

```bash
# Simple violation check (works today)
adb forward tcp:18462 localabstract:rebound
VIOLATIONS=$(./rebound-cli.sh summary | grep "VIOLATION" | wc -l)
if [ "$VIOLATIONS" -gt 0 ]; then
  echo "Rebound found $VIOLATIONS budget violations"
  ./rebound-cli.sh summary
  exit 1
fi
```

This is a rough approximation. The planned `compare` command will provide proper diffing with baseline awareness, threshold configuration, and structured output.
