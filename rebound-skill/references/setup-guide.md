# Setup Guide

## Quick Start (2 lines)

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts (app module)
plugins {
    id("io.aldefy.rebound") version "0.1.0-SNAPSHOT"
}
```

That's it. The Gradle plugin auto-wires the compiler plugin and runtime dependency. No other configuration needed.

## Configuration Options

### Gradle DSL

```kotlin
rebound {
    enabled.set(true)    // Master switch — false disables all instrumentation
    debugOnly.set(true)  // Only instrument debug builds (default: true)
}
```

When `debugOnly = true`, release builds have zero overhead — no compiler instrumentation, no runtime dependency.

### Runtime Toggles

```kotlin
// Master kill switch at runtime
ReboundTracker.enabled = true    // default: true

// Per-composition logcat logging (throttled to 1/s per composable)
ReboundTracker.logCompositions = false  // default: false

// Telemetry (anonymized aggregate stats, must opt in)
ReboundTelemetry.enabled = false  // default: false
```

## Kotlin Version Matrix

The Gradle plugin auto-detects your Kotlin version and selects the correct compiler artifact:

| Kotlin Version | Compiler Artifact | Status |
|---------------|-------------------|--------|
| 2.0.x | `rebound-compiler` | Stable |
| 2.1.x | `rebound-compiler` | Stable |
| 2.2.x | `rebound-compiler-kotlin-2.2` | Stable |
| 2.3.x | `rebound-compiler-kotlin-2.3` | Stable |

No manual configuration needed — just apply the plugin and use any supported Kotlin version.

## KMP Setup

The Gradle plugin detects `KotlinMultiplatformExtension` and adds the runtime to `commonMainImplementation` automatically.

For Android-only projects with `debugOnly = true`, it uses `debugImplementation`.

### KMP-specific notes

- Add `mavenLocal()` in `allprojects { repositories {} }` if using SNAPSHOT builds. Project-level repos override settings-level repos when both exist.
- Plugin ID resolution needs `mavenLocal()` in `pluginManagement { repositories {} }` in settings.gradle.kts.

```kotlin
// settings.gradle.kts for SNAPSHOT builds
pluginManagement {
    repositories {
        mavenLocal()          // for SNAPSHOT
        gradlePluginPortal()
        mavenCentral()
    }
}

// build.gradle.kts (root)
allprojects {
    repositories {
        mavenLocal()          // for SNAPSHOT runtime
        mavenCentral()
    }
}
```

## Platform Support

| Platform | Transport | What to expect |
|----------|-----------|---------------|
| Android (device/emulator) | `LocalServerSocket` + ADB forward | Full support — CLI, IDE, logcat |
| iOS Simulator | Direct TCP on `:18462` | Full support — CLI, IDE |
| iOS Physical Device | WebSocket → Mac relay (Bonjour) | Full support — needs relay running |
| iOS Physical Device (no relay) | Console logging via `devicectl` | One-way — logcat-equivalent only |
| JVM/Desktop | In-memory | Metrics collected but no socket export |

## iOS Simulator

Works out of the box. The runtime starts a TCP server on port 18462 in the simulator.

```bash
./rebound-cli.sh ping   # should return "pong"
./rebound-cli.sh snapshot
```

## iOS Physical Device

Requires the Mac relay. The device discovers the relay via Bonjour on the same WiFi network.

```bash
# Build (one-time)
./tools/build-relay.sh

# Start relay
./tools/rebound-relay
# → TCP :18462 (CLI/IDE), WebSocket :18463 (devices), Bonjour: _rebound._tcp

# Override discovery if different subnet
REBOUND_RELAY_HOST=192.168.1.100:18463 ./rebound-cli.sh snapshot
```

## Publishing to mavenLocal (for development)

```bash
# Publish runtime + compiler
./gradlew publishToMavenLocal

# Publish Gradle plugin
cd rebound-gradle && ./gradlew publishToMavenLocal && cd ..

# Build K2.2 compiler (if needed)
cd rebound-compiler-k2 && ./gradlew build && cd ..

# Build K2.3 compiler (if needed)
cd rebound-compiler-k2-3 && ./gradlew build && cd ..
```

## IDE Plugin Installation

```bash
./gradlew :rebound-ide:buildPlugin
# Output: rebound-ide/build/distributions/rebound-ide-*.zip
```

Install via: Android Studio > Settings > Plugins > Gear icon > Install Plugin from Disk > select the zip.

Requires Android Studio Meerkat 2024.3.1.14+ (build 242).

## Logcat Output

Even without the IDE or CLI, violations appear in logcat:

```
W/Rebound: BUDGET VIOLATION: com.example.HomeScreen rate=8/s exceeds SCREEN budget=3/s (base=3/s, interaction=IDLE)
  → forced recomposition (parent invalidated)
  → params: items=CHANGED, isLoading=uncertain
```

Filter with: `adb logcat -s Rebound:W`
