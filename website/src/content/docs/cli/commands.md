---
sidebar_position: 2
title: Commands
---

# CLI Commands

All commands are run via the `rebound-cli.sh` script from the repository root.

## ping

Health check to verify the connection to the app's `ReboundServer`.

```bash
./rebound-cli.sh ping
```

**Response:** `pong`

If you get `pong`, the socket connection is working and the server is alive. If the connection fails, see [CLI Setup - Troubleshooting](/docs/cli/setup#troubleshooting).

## snapshot

Returns a full JSON snapshot of all instrumented composables with their current metrics.

```bash
./rebound-cli.sh snapshot
```

**Response:** Pretty-printed JSON (via `python3 -m json.tool`):

```json
{
  "composables": [
    {
      "fqn": "com.example.ui.HomeScreen",
      "simpleName": "HomeScreen",
      "budgetClass": "SCREEN",
      "budget": 3,
      "rate": 8,
      "peakRate": 12,
      "totalEnters": 245,
      "totalCount": 189,
      "skipRate": 0.23,
      "forcedCount": 15,
      "paramDrivenCount": 174,
      "parentFqn": null,
      "depth": 0
    }
  ],
  "interactionState": "IDLE",
  "violationCount": 3,
  "composableCount": 45,
  "timestamp": 1709900000000
}
```

This is the same data the IDE plugin receives on each polling cycle. Useful for scripting, logging, or piping into other tools.

## summary

Returns a condensed view of the top 10 composables by recomposition rate, plus overall violation statistics.

```bash
./rebound-cli.sh summary
```

**Response:**

```json
{
  "topByRate": [
    {"name": "HomeScreen", "rate": 8, "budget": 3, "class": "SCREEN", "status": "VIOLATION"},
    {"name": "ProfileHeader", "rate": 11, "budget": 5, "class": "LEAF", "status": "VIOLATION"},
    {"name": "SearchBar", "rate": 6, "budget": 10, "class": "CONTAINER", "status": "OK"}
  ],
  "totalViolations": 2,
  "totalComposables": 45,
  "interactionState": "IDLE"
}
```

This is the quickest way to check whether any composables are currently over budget.

## watch

Live-updating display that refreshes every 1 second. Clears the terminal on each update.

```bash
./rebound-cli.sh watch
```

Press `Ctrl+C` to stop.

The watch command repeatedly calls `summary` and displays the results. Useful for monitoring recomposition behavior while interacting with the app -- scroll a list, type in a field, or trigger a navigation and watch the rates change in real time.

## telemetry

Returns anonymized aggregate statistics. No function names or identifiers are included.

```bash
./rebound-cli.sh telemetry
```

**Response:**

```json
{
  "budgetClassDistribution": {
    "SCREEN": 5,
    "CONTAINER": 18,
    "LEAF": 15,
    "ANIMATED": 3,
    "INTERACTIVE": 2,
    "LIST_ITEM": 1,
    "UNKNOWN": 1
  },
  "averageRates": {
    "SCREEN": 1.2,
    "CONTAINER": 3.5,
    "LEAF": 2.1,
    "ANIMATED": 45.0
  },
  "peakRates": {
    "SCREEN": 8,
    "ANIMATED": 95
  },
  "averageSkipRate": 0.72,
  "totalViolations": 2
}
```

This data is useful for empirical calibration of budget thresholds. If you find that the default budgets are consistently too low or too high for your app's composition patterns, this data helps inform adjustments.

Telemetry is opt-in at runtime:

```kotlin
ReboundTelemetry.enabled = true  // disabled by default
```
