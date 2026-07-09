# Rebound Sample App

This app is the quickest way to prove Rebound is working against a real Compose UI.

## Run the proof

```bash
./gradlew :sample:installDebug
adb shell am start -n io.aldefy.rebound.sample/.MainActivity

./rebound-cli.sh doctor
./rebound-cli.sh check
./rebound-cli.sh summary
```

Expected signal: `BudgetViolationScreen` is over its `SCREEN` budget because it reads a 60fps ticker at screen scope.

Tap **Fixed** in the app, wait a few seconds, then rerun:

```bash
./rebound-cli.sh summary
```

The fast ticker should now be isolated in `AnimatedTickerBadge`, which is marked with `@ReboundBudget(BudgetClass.ANIMATED)`.

## Why this sample exists

The demo turns the core Rebound claim into a short proof:

- a screen-level state read is noisy and gets flagged
- the same fast-changing state is acceptable when scoped to an animated child
- `doctor -> check -> summary` gives a terminal-first validation path for PRs, agents, and CI logs
