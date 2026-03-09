# Rebound — Claude Code Instructions

Rebound is a Compose recomposition budget monitor. Compiler plugin + runtime + IDE plugin + CLI.

## Module Map

| Module | What it does |
|--------|-------------|
| `rebound-compiler` | Kotlin compiler plugin (K1/K2, Kotlin 2.0.x–2.1.x) |
| `rebound-compiler-k2` | Compiler plugin for Kotlin 2.2.x (separate composite build) |
| `rebound-compiler-k2-3` | Compiler plugin for Kotlin 2.3.x (separate composite build) |
| `rebound-gradle` | Gradle plugin — auto-wires compiler + runtime by Kotlin version |
| `rebound-runtime` | KMP runtime (Android, iOS, JVM). Tracks recompositions, budget engine, transport. |
| `rebound-ide` | Android Studio / IntelliJ plugin — 5-tab performance cockpit |
| `sample` | Sample Android app for testing |
| `benchmark` | Benchmarks |
| `tools/` | Mac relay server (Swift), build scripts |
| `website/` | Astro static site (docs + landing page) |

## Build Commands

```bash
# Build everything (excludes composite-build modules)
./gradlew build

# Build sample app
./gradlew :sample:installDebug

# Build IDE plugin
./gradlew :rebound-ide:buildPlugin
# Output: rebound-ide/build/distributions/rebound-ide-*.zip

# Build compiler plugin for Kotlin 2.2+
cd rebound-compiler-k2 && ./gradlew build && cd ..

# Build compiler plugin for Kotlin 2.3+
cd rebound-compiler-k2-3 && ./gradlew build && cd ..

# Run iOS simulator tests
./gradlew :rebound-runtime:iosSimulatorArm64Test

# Run Android tests
./gradlew :rebound-runtime:testDebugUnitTest

# Build Mac relay (Swift)
./tools/build-relay.sh

# Build website
cd website && npm install && npm run build && cd ..
```

## Testing with Rebound

Apply to any Compose project:

```kotlin
// settings.gradle.kts — add mavenLocal for SNAPSHOT builds
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

// build.gradle.kts (app module)
plugins {
    id("io.aldefy.rebound") version "0.1.0-SNAPSHOT"
}
```

Publish to mavenLocal for testing:
```bash
./gradlew publishToMavenLocal
cd rebound-gradle && ./gradlew publishToMavenLocal && cd ..
```

## CLI Usage

```bash
./rebound-cli.sh ping       # Health check
./rebound-cli.sh snapshot   # Full JSON metrics
./rebound-cli.sh summary    # Top 10 violators
./rebound-cli.sh watch      # Live updates every 1s
```

Connection auto-detection order:
1. Direct TCP `localhost:18462` (iOS simulator or relay)
2. ADB forward (Android device/emulator)
3. `devicectl` console (iOS physical device, no relay)

## Architecture Notes

- Compiler plugin injects `ReboundTracker.onCompositionEnter/Exit` at IR level into every `@Composable`
- Runtime tracks rates per sliding window, classifies budget violations
- Transport: `LocalServerSocket("rebound")` on Android, TCP `:18462` on iOS simulator, WebSocket relay for iOS physical devices
- IDE connects via `adb forward tcp:18462 localabstract:rebound`
- Budget classes: SCREEN (3/s), CONTAINER (10/s), INTERACTIVE (30/s), LIST_ITEM (60/s), ANIMATED (120/s), LEAF (5/s)

## iOS Physical Device Setup

```bash
# Build and start the relay on your Mac
./tools/build-relay.sh
./tools/rebound-relay
# → TCP :18462 (CLI/IDE), WebSocket :18463 (devices), Bonjour: _rebound._tcp

# Device auto-discovers relay on same WiFi
# Then use CLI normally:
./rebound-cli.sh snapshot
```

## KMP Structure

`rebound-runtime` is KMP with targets:
- `androidMain` — `LocalServerSocket` transport
- `iosMain` — TCP server (simulator) + WebSocket client (physical device) + Bonjour discovery
- `jvmMain` — In-memory metrics (no transport)
- `commonMain` — `ReboundTracker`, budget engine, metrics

## IDE Plugin

Targets Android Studio Meerkat 2024.3.1.14+ (`sinceBuild = "242"`). Uses IntelliJ Platform Gradle Plugin 2.5.0.

5 tabs: Monitor, Hot Spots, Timeline, Stability, History.

## AI Agent Skill

`rebound-skill/` contains an AI agent skill for Rebound-aware assistance. See `rebound-skill/SKILL.md` for the main workflow and `rebound-skill/references/` for topic-specific guides. Works with Claude Code, Gemini CLI, Cursor, Copilot, Codex, Windsurf, Amazon Q, and others.

## Key Files

- `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/ReboundTracker.kt` — Core tracker
- `rebound-runtime/src/commonMain/kotlin/io/aldefy/rebound/BudgetEngine.kt` — Budget classification
- `rebound-runtime/src/androidMain/kotlin/io/aldefy/rebound/ReboundServer.kt` — Android socket server
- `rebound-runtime/src/iosMain/kotlin/io/aldefy/rebound/ReboundServer.kt` — iOS transport (TCP + WebSocket + Bonjour)
- `rebound-compiler/src/main/kotlin/io/aldefy/rebound/compiler/ReboundIrTransformer.kt` — IR instrumentation
- `rebound-gradle/src/main/kotlin/io/aldefy/rebound/gradle/ReboundPlugin.kt` — Gradle plugin
- `rebound-ide/src/main/kotlin/io/aldefy/rebound/ide/` — IDE plugin source
