---
sidebar_position: 8
title: Settings
---

# IDE Plugin Settings

The Rebound IDE plugin settings are accessible at **Settings > Tools > Rebound** in Android Studio. Settings are persisted via `PersistentStateComponent` in the project's `.idea/rebound.xml` file.

## All Settings

| Setting | Default | Type | Description |
|---------|---------|------|-------------|
| `historyRetentionSeconds` | `3600` | Int | Maximum duration of rate history samples to retain in memory. 3600 seconds = 1 hour. Increasing this uses more memory (approximately 1.4 MB per 100 composables at 1 hour). |
| `snapshotIntervalSeconds` | `5` | Int | How often a full metric snapshot is stored for timeline and history purposes. Lower values provide finer-grained timeline data but use more memory. |
| `maxStoredSessions` | `20` | Int | Maximum number of session files kept on disk in `.rebound/sessions/`. When exceeded, the oldest session is deleted. |
| `showGutterIcons` | `true` | Boolean | Toggle colored dots next to `@Composable` functions in the editor gutter. |
| `showInlayHints` | `true` | Boolean | Toggle CodeVision-style inlay hints above `@Composable` function signatures. |
| `autoConnect` | `false` | Boolean | When `true`, the plugin automatically starts polling the socket when the project is opened, without requiring a manual click on the Start button. |
| `adbPort` | `18462` | Int | The TCP port used for `adb forward`. Change this if port 18462 conflicts with another service. Must match the port used in the `adb forward` command. |
| `maxEventLogLines` | `5000` | Int | Maximum number of entries in the Monitor tab's event log. Older entries are trimmed when this limit is reached. |

## Memory Impact

The table below estimates memory usage for the default settings with 100 instrumented composables:

| Component | Calculation | Size |
|-----------|------------|------|
| Rate history | 100 composables x 3600 samples x 4 bytes | ~1.4 MB |
| Snapshots | 720 snapshots x 100 entries x ~200 bytes | ~14 MB |
| Event log | 5000 entries x ~100 bytes | ~0.5 MB |
| **Total** | | **~16 MB** |

This is acceptable for a development tool. If memory is a concern (e.g., large apps with 500+ composables), reduce `historyRetentionSeconds` or increase `snapshotIntervalSeconds`.

## Persistence

Settings are stored per-project in `.idea/rebound.xml`. This file can be committed to version control if you want consistent settings across the team, or added to `.gitignore` for per-developer configuration.

Session data (`.rebound/sessions/`) is always local and should be added to `.gitignore`.
