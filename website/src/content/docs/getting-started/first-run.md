---
sidebar_position: 3
title: First Run
---

# First Run

After adding the Rebound Gradle plugin, here is how to see it in action.

## 1. Run Your App

Build and run your app in debug mode. Rebound instruments every `@Composable` function at compile time. No code changes are needed.

```bash
./gradlew :app:installDebug
```

## 2. See Logcat Violations

Open Logcat in Android Studio and filter by tag `Rebound`:

```
W/Rebound: [VIOLATION] HomeScreen — 8 recomp/s (budget: 3, class: SCREEN)
  -> params: items=DIFFERENT, query=SAME
  -> forced: 0 | param-driven: 8 | interaction: IDLE
```

Each violation tells you:

- **Which composable** exceeded its budget (`HomeScreen`)
- **Current rate** vs **budget** (8/s vs 3/s)
- **Budget class** assigned by the compiler (SCREEN)
- **Which parameters changed** (`items=DIFFERENT`, `query=SAME`)
- **Whether recomposition was forced** by a parent or **driven by parameter changes**
- **Interaction state** at the time (IDLE, SCROLLING, ANIMATING, USER_INPUT)

Violations are throttled to one per composable per 5 seconds, so logcat does not flood.

## 3. Enable Composition Logging (Optional)

To see every composition event (not just violations), enable verbose logging in your `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReboundTracker.logCompositions = true
    }
}
```

This is throttled to 1 log per composable per second. Useful for understanding normal composition patterns before they become violations.

## 4. Connect the IDE Plugin

The IDE plugin provides a richer experience than logcat: a live composable tree, hot spots table, timeline heatmap, and more.

### Build and install the plugin

```bash
./gradlew :rebound-ide:buildPlugin
```

In Android Studio: **Settings > Plugins > Gear icon > Install Plugin from Disk** and select the zip from `rebound-ide/build/distributions/`.

### Set up ADB forward

The IDE plugin communicates with the app via a Unix domain socket bridged through ADB:

```bash
adb forward tcp:18462 localabstract:rebound
```

This forwards port 18462 on your machine to the `rebound` socket inside the app process.

### Start monitoring

1. Open the **Rebound** tool window (usually in the right sidebar of Android Studio)
2. Click **Start** in the toolbar
3. The composable tree populates with live data

Each composable shows its current recomposition rate, budget, skip percentage, and status (green/yellow/red).

## 5. Use the CLI (Alternative)

If you prefer the terminal:

```bash
# Set up ADB forward
adb forward tcp:18462 localabstract:rebound

# Health check
./rebound-cli.sh ping

# Full metrics snapshot
./rebound-cli.sh snapshot

# Top 10 violators
./rebound-cli.sh summary

# Live updates every 1 second
./rebound-cli.sh watch
```

## Troubleshooting

**No violations in logcat?**
- Verify you are running a debug build. Release builds have no instrumentation when `debugOnly = true`.
- Confirm the plugin is applied: check for `Rebound: compiler plugin applied` in the build log.

**ADB forward fails?**
- Ensure the app is running. The socket is only available while the process is alive.
- If multiple apps use Rebound, the server falls back to `rebound_<pid>`. The CLI auto-discovers these.

**IDE plugin shows "Not connected"?**
- Run `adb forward tcp:18462 localabstract:rebound` after the app starts.
- Click **Start** in the Rebound tool window toolbar.
