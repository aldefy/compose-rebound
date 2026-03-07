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
# Works with: Claude Code, Gemini, ComposeProof MCP, any shell tool

PORT=18462
CMD="${1:-snapshot}"

# Try canonical socket, then discover PID-suffixed fallback
setup_forward() {
    # Try canonical name first
    adb forward tcp:$PORT localabstract:rebound 2>/dev/null
    RESULT=$( (echo "ping"; sleep 0.3) | nc localhost $PORT 2>/dev/null)
    if [ "$RESULT" = "pong" ]; then
        return 0
    fi

    # Discover fallback sockets (rebound_<pid>)
    for SOCK in $(adb shell "cat /proc/net/unix 2>/dev/null" | grep -oE '@rebound_[0-9]+' | sed 's/@//'); do
        adb forward tcp:$PORT localabstract:$SOCK 2>/dev/null
        RESULT=$( (echo "ping"; sleep 0.3) | nc localhost $PORT 2>/dev/null)
        if [ "$RESULT" = "pong" ]; then
            return 0
        fi
    done
    return 1
}

setup_forward

send_command() {
    (echo "$1"; sleep 0.5) | nc localhost $PORT 2>/dev/null
}

if [ "$CMD" = "watch" ]; then
    while true; do
        clear
        echo "=== Rebound Live ($(date +%H:%M:%S)) ==="
        RESULT=$(send_command "summary")
        if [ -z "$RESULT" ]; then
            setup_forward  # re-discover on failure
            echo "No connection — is the app running with Rebound enabled?"
        else
            echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"
        fi
        sleep 1
    done
else
    RESULT=$(send_command "$CMD")
    if [ -z "$RESULT" ]; then
        echo "No connection — is the app running with Rebound enabled?" >&2
        exit 1
    fi
    echo "$RESULT"
fi
