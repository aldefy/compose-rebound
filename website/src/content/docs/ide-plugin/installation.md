---
title: Installation
---

# IDE Plugin Installation

The Rebound IDE plugin adds a tool window to Android Studio that provides live recomposition monitoring, hot spots analysis, timeline visualization, and editor integration.

## Build from Source

Clone the repository and build the plugin:

```bash
git clone https://github.com/aldefy/compose-rebound.git
cd compose-rebound
./gradlew :rebound-ide:buildPlugin
```

The plugin zip is produced at:

```
rebound-ide/build/distributions/rebound-ide-<version>.zip
```

## Install from Disk

1. Open Android Studio
2. Go to **Settings > Plugins**
3. Click the **gear icon** in the top bar
4. Select **Install Plugin from Disk...**
5. Navigate to the zip file produced by the build
6. Click **OK** and restart Android Studio

## Compatibility

| Requirement | Value |
|------------|-------|
| Android Studio | 2024.2.1.3 (Ladybug) or newer |
| IntelliJ Platform | `sinceBuild = "242"` |
| Upper bound | Unbounded (`untilBuild` not set) |
| Gradle | 8.9 |
| JDK | 17 |

The plugin has been tested on:

- Android Studio Ladybug (2024.2.1.3)
- Android Studio Panda 2 (build 253)

The `untilBuild` is deliberately unbounded so the plugin continues to work with future Android Studio releases without requiring an update. If a future platform version introduces breaking API changes, a new plugin release will be published.

## Verify Installation

After restarting Android Studio:

1. Open a project that uses the Rebound Gradle plugin
2. Look for the **Rebound** tool window in the right sidebar
3. If it does not appear, go to **View > Tool Windows > Rebound**

The tool window should show a toolbar with Start/Stop/Clear buttons and five tabs: Monitor, Hot Spots, Timeline, Stability, and History.

## Plugin Architecture

The plugin is built with IntelliJ Platform Gradle Plugin 2.2.1. Key components:

- **ReboundToolWindowFactory** -- creates the multi-tab tool window
- **ReboundConnection** -- manages the ADB forward and socket polling
- **SessionStore** -- shared data layer that all tabs observe
- **Editor integration** -- gutter icons, inlay hints, and status bar widget (always active when connected)

The plugin connects to the app via `adb forward tcp:18462 localabstract:rebound`. See [First Run](/docs/getting-started/first-run) for setup instructions.
