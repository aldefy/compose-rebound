---
title: "CI Integration"
---

# CI Integration

Catch recomposition regressions in your CI pipeline. Rebound captures runtime recomposition profiles, compares them across builds, and fails the pull request if budgets regress.

## Quick start

### 1. Create a baseline (once, on your main branch)

Run your app with Rebound enabled, exercise the UI (manually or via instrumented tests), then save a snapshot:

```bash
./gradlew reboundSave -Ptag=baseline
```

This writes `.rebound/baseline.json`. Commit it to the repo.

### 2. Gate your PRs

Add one Gradle task to your CI pipeline:

```bash
./gradlew reboundGate -Pbaseline=.rebound/baseline.json
```

This connects to the running app, captures a fresh snapshot, diffs it against the baseline, and **fails the build** if any composable's peak recomposition rate regressed beyond the threshold (default: 20%).

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `-Pbaseline=<path>` | (required) | Path to committed baseline JSON |
| `-Pthreshold=<int>` | `20` | Minimum % change to flag as regression |
| `-Pport=<int>` | `18462` | Rebound socket port |

## GitHub Actions example

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

      - name: Start emulator and run budget gate
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          script: |
            # Build and install
            ./gradlew :app:installDebug
            adb shell am start -n com.example.app/.MainActivity
            sleep 10

            # Run UI tests to exercise composables
            ./gradlew :app:connectedDebugAndroidTest

            # Budget gate — fails the build on regression
            ./gradlew reboundGate -Pbaseline=.rebound/baseline.json
```

## Output

```
Captured 29 composables to .rebound/gate-20260409-143022.json

=== Rebound Gate: baseline.json -> gate-20260409-143022.json (threshold: 20%) ===

REGRESSIONS (1):
  ProfileHeader [SCREEN]
    peak: 3/s -> 11/s (+267%) budget=3/s !!OVER!!
    skip: 80.0% -> 45.0% (-35.0%)
    forced: 0 -> 7

IMPROVED (1):
  UserCard [LEAF]
    peak: 9/s -> 4/s (-56%)
    skip: 30.0% -> 85.0% (+55.0%)

Summary: 29 composables (1 improved, 1 regressed, 25 unchanged, 2 new, 0 removed)
Result: FAIL

FAILURE: Build failed with exception.
  Rebound: 1 regression(s) exceeded 20% threshold — build failed
```

## Updating the baseline

When you intentionally change recomposition behavior (e.g., adding animation to a composable that was previously static), update the baseline:

```bash
./gradlew reboundSave -Ptag=baseline
git add .rebound/baseline.json
git commit -m "update rebound baseline"
```

## Alternative: CLI diff

If you prefer shell scripts over Gradle tasks, the CLI works the same way:

```bash
# Save snapshots
./rebound-cli.sh save .rebound/baseline.json
./rebound-cli.sh save .rebound/current.json

# Compare — exits 1 on regression
./rebound-cli.sh diff .rebound/baseline.json .rebound/current.json
```

## Alternative: standalone diff (no device)

Compare two previously saved snapshots without a running app:

```bash
./gradlew reboundDiff \
  -Pbefore=.rebound/baseline.json \
  -Pafter=.rebound/current.json \
  -Pthreshold=10
```

This also fails the build on regression. To print results without failing, add `-PfailOnRegression=false`.
