#!/bin/bash
# rebound-cli — query live recomposition data from a running app
#
# Usage:
#   ./rebound-cli.sh snapshot   # full JSON metrics
#   ./rebound-cli.sh summary    # top 10 violators
#   ./rebound-cli.sh telemetry  # anonymized aggregate stats by budget class
#   ./rebound-cli.sh ping       # health check
#   ./rebound-cli.sh watch      # live updates every 1s
#
# Connects to:
#   Android: auto-forwards via adb (device/emulator)
#   iOS simulator: direct TCP on localhost:18462
#   iOS physical device: parses structured console output via devicectl
#
# Works with: Claude Code, Gemini, ComposeProof MCP, any shell tool

PORT=18462
CMD="${1:-snapshot}"
CONNECTION_MODE=""

send_command() {
    (echo "$1"; sleep 0.5) | nc localhost $PORT 2>/dev/null
}

# Try direct TCP first (iOS simulator, or already-forwarded)
try_direct() {
    RESULT=$(send_command "ping")
    [ "$RESULT" = "pong" ]
}

# ADB forward for Android
setup_adb_forward() {
    # Try canonical name first
    adb forward tcp:$PORT localabstract:rebound 2>/dev/null
    RESULT=$(send_command "ping")
    if [ "$RESULT" = "pong" ]; then
        return 0
    fi

    # Discover fallback sockets (rebound_<pid>)
    for SOCK in $(adb shell "cat /proc/net/unix 2>/dev/null" | grep -oE '@rebound_[0-9]+' | sed 's/@//'); do
        adb forward tcp:$PORT localabstract:$SOCK 2>/dev/null
        RESULT=$(send_command "ping")
        if [ "$RESULT" = "pong" ]; then
            return 0
        fi
    done
    return 1
}

# iOS physical device via devicectl console
try_devicectl() {
    DEVICE_ID=$(xcrun devicectl list devices 2>/dev/null | grep -i "connected" | head -1 | awk '{print $NF}')
    [ -n "$DEVICE_ID" ]
}

# Parse structured Rebound logs from devicectl console output
devicectl_command() {
    local CMD="$1"
    local TIMEOUT="${2:-5}"
    # Stream console output and grep for the first matching Rebound tag
    timeout "$TIMEOUT" xcrun devicectl device process launch --console \
        --device "$DEVICE_ID" 2>&1 |
        grep -m1 "\[Rebound:$CMD\]" |
        sed 's/.*\[Rebound:[a-z]*\] //'
}

# Connect: direct TCP first, then ADB, then devicectl
connect() {
    if try_direct; then
        CONNECTION_MODE="tcp"
        return 0
    fi
    if setup_adb_forward; then
        CONNECTION_MODE="adb"
        return 0
    fi
    if try_devicectl; then
        CONNECTION_MODE="devicectl"
        return 0
    fi
    return 1
}

if ! connect; then
    echo "No connection — is the app running with Rebound enabled?" >&2
    echo "  Android: connect device/emulator, launch debug app" >&2
    echo "  iOS simulator: launch app in simulator" >&2
    echo "  iOS device: connect via USB, launch app (uses devicectl console)" >&2
    exit 1
fi

# Execute a command based on connection mode
exec_command() {
    local CMD="$1"
    if [ "$CONNECTION_MODE" = "devicectl" ]; then
        if [ "$CMD" = "ping" ]; then
            echo "pong (devicectl — console mode)"
            return
        fi
        devicectl_command "$CMD"
    else
        send_command "$CMD"
    fi
}

if [ "$CMD" = "watch" ]; then
    while true; do
        clear
        echo "=== Rebound Live ($(date +%H:%M:%S)) [$CONNECTION_MODE] ==="
        RESULT=$(exec_command "summary")
        if [ -z "$RESULT" ]; then
            connect  # re-discover on failure
            echo "No connection — is the app running with Rebound enabled?"
        else
            echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"
        fi
        sleep 1
    done
else
    RESULT=$(exec_command "$CMD")
    if [ -z "$RESULT" ]; then
        echo "No response for '$CMD'. Is the app running?" >&2
        exit 1
    fi
    echo "$RESULT"
fi
