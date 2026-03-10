---
title: Setup
---

# CLI Setup

The Rebound CLI is a Bash script (`rebound-cli.sh`) that communicates with the app's runtime over an ADB-forwarded socket. No additional dependencies beyond `adb`, `nc` (netcat), and `python3` are required.

## ADB Forward Setup

The app's `ReboundServer` binds a `LocalServerSocket` on the Android abstract namespace. ADB bridges this to a TCP port on your machine:

```bash
adb forward tcp:18462 localabstract:rebound
```

This command maps port 18462 on your machine to the `rebound` Unix domain socket inside the app process. The forward remains active until the app process dies or you explicitly remove it with `adb forward --remove tcp:18462`.

## Socket Protocol

The protocol is request-response over a plain TCP connection:

1. Client connects to `localhost:18462`
2. Client sends a command string (e.g., `"snapshot"`)
3. Server responds with JSON
4. Connection closes

Each request is a fresh TCP connection. There is no persistent connection or streaming protocol.

### Available commands

| Command | Response |
|---------|----------|
| `ping` | `"pong"` -- health check |
| `snapshot` | Full metrics JSON for all instrumented composables |
| `summary` | Top 10 composables by rate, plus violation count |
| `telemetry` | Anonymized aggregate stats (budget class distributions, average rates) |

## Troubleshooting

### "Connection refused" on port 18462

The app is not running or `ReboundServer` has not started yet.

**Fix:** Ensure the app is running in debug mode, then re-run `adb forward tcp:18462 localabstract:rebound`.

### Socket taken by another app

If multiple apps use Rebound simultaneously, the canonical `rebound` socket may be taken. The server falls back to `rebound_<pid>`.

**Fix:** The CLI auto-discovers PID-suffixed sockets by probing `/proc/net/unix`. If auto-discovery fails, find the PID manually:

```bash
adb shell cat /proc/net/unix | grep rebound
```

Then forward the specific socket:

```bash
adb forward tcp:18462 localabstract:rebound_12345
```

### Empty or malformed JSON response

The server may not have collected enough data yet. Wait a few seconds after app launch for metrics to initialize, then retry.

### "nc: command not found"

Install netcat. On macOS it is included by default. On Linux:

```bash
# Debian/Ubuntu
sudo apt install netcat-openbsd

# Fedora
sudo dnf install nmap-ncat
```

### ADB forward disappears

The forward is tied to the ADB connection. If the device disconnects or the ADB server restarts, you need to re-run the forward command. Some IDE configurations restart ADB automatically, which clears forwards.
