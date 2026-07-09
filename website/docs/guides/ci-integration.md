---
sidebar_position: 3
title: "CI Integration"
---

# CI Integration

Rebound ships CI budget gates today through the Gradle tasks `reboundSave`, `reboundDiff`, and `reboundGate`.

## Quick start

### 1. Capture a baseline snapshot

Run your app with Rebound active, exercise the UI, then save a snapshot:

```bash
./gradlew reboundSave -Ptag=baseline
```

This writes `.rebound/baseline.json`. Commit it to the repo.

### 2. Gate your PRs

Add one Gradle task to your CI pipeline:

```bash
./gradlew reboundGate -Pbaseline=.rebound/baseline.json
```

This connects to the running app, captures a fresh snapshot, diffs it against the baseline, and fails the build if any composable regressed beyond the threshold.

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `-Pbaseline=<path>` | (required) | Path to committed baseline JSON |
| `-Pthreshold=<int>` | `20` | Minimum % change to flag as regression |
| `-Pport=<int>` | `18462` | Rebound socket port |

## Example GitHub Actions workflow

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
            ./rebound-cli.sh diff ci/rebound-baseline.json rebound-snapshot.json
```

## Output

`reboundGate` and `reboundDiff` print a human-readable report and fail on regression by default.
