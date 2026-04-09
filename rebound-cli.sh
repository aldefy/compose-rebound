#!/bin/bash
# rebound-cli — query live recomposition data from a running app
#
# Usage:
#   ./rebound-cli.sh snapshot          # full JSON metrics
#   ./rebound-cli.sh summary           # top 10 violators
#   ./rebound-cli.sh telemetry         # anonymized aggregate stats by budget class
#   ./rebound-cli.sh ping              # health check
#   ./rebound-cli.sh watch             # live updates every 1s
#   ./rebound-cli.sh save <file>       # save live snapshot to file
#   ./rebound-cli.sh diff <before> <after> [threshold]  # compare two saved snapshots
#
# Connects to:
#   Android: auto-forwards via adb (device/emulator)
#   iOS simulator: direct TCP on localhost:18462
#   iOS physical device: parses structured console output via devicectl
#
# Works with: ComposeProof MCP, any shell tool, AI coding agents

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

# ── diff command: compare two snapshot JSON files (no device needed) ──

do_diff() {
    local BEFORE="$1"
    local AFTER="$2"
    local THRESHOLD="${3:-20}"

    if [ -z "$BEFORE" ] || [ -z "$AFTER" ]; then
        echo "Usage: ./rebound-cli.sh diff <before.json> <after.json> [threshold%]" >&2
        echo "  threshold: minimum % change to show (default: 20)" >&2
        exit 1
    fi
    if [ ! -f "$BEFORE" ]; then
        echo "File not found: $BEFORE" >&2; exit 1
    fi
    if [ ! -f "$AFTER" ]; then
        echo "File not found: $AFTER" >&2; exit 1
    fi

    python3 - "$BEFORE" "$AFTER" "$THRESHOLD" <<'PYEOF'
import json, sys, os

before_file, after_file, threshold = sys.argv[1], sys.argv[2], int(sys.argv[3])

with open(before_file) as f:
    before = json.load(f)["composables"]
with open(after_file) as f:
    after = json.load(f)["composables"]

all_keys = sorted(set(list(before.keys()) + list(after.keys())))

improved = []
regressed = []
new_composables = []
removed = []

for key in all_keys:
    b = before.get(key)
    a = after.get(key)
    short = key.rsplit(".", 1)[-1] if "." in key else key

    if b and not a:
        removed.append(short)
        continue
    if a and not b:
        new_composables.append((short, a))
        continue

    b_peak = b.get("peakRate", 0)
    a_peak = a.get("peakRate", 0)
    b_total = b.get("totalCompositions", 0)
    a_total = a.get("totalCompositions", 0)
    b_skip = b.get("skipRate", 0)
    a_skip = a.get("skipRate", 0)
    b_forced = b.get("forcedCount", 0)
    a_forced = a.get("forcedCount", 0)
    budget_class = a.get("budgetClass", "?")
    budget = a.get("budgetPerSecond", 0)

    if b_peak == 0 and a_peak == 0:
        continue

    if b_peak > 0:
        pct = ((a_peak - b_peak) * 100) // b_peak
    elif a_peak > 0:
        pct = 100
    else:
        pct = 0

    skip_delta = a_skip - b_skip

    entry = {
        "name": short,
        "fqn": key,
        "class": budget_class,
        "budget": budget,
        "before_peak": b_peak,
        "after_peak": a_peak,
        "peak_pct": pct,
        "before_total": b_total,
        "after_total": a_total,
        "before_skip": round(b_skip * 100, 1),
        "after_skip": round(a_skip * 100, 1),
        "skip_delta": round(skip_delta * 100, 1),
        "before_forced": b_forced,
        "after_forced": a_forced,
    }

    if pct < -threshold:
        improved.append(entry)
    elif pct > threshold:
        regressed.append(entry)

improved.sort(key=lambda e: e["peak_pct"])
regressed.sort(key=lambda e: -e["peak_pct"])

before_name = os.path.basename(before_file)
after_name = os.path.basename(after_file)

print(f"=== Rebound Diff: {before_name} -> {after_name} (threshold: {threshold}%) ===")
print()

if regressed:
    print(f"REGRESSIONS ({len(regressed)}):")
    for e in regressed:
        over = " !!OVER!!" if e["after_peak"] > e["budget"] else ""
        print(f"  {e['name']} [{e['class']}]")
        print(f"    peak: {e['before_peak']}/s -> {e['after_peak']}/s (+{e['peak_pct']}%) budget={e['budget']}/s{over}")
        print(f"    skip: {e['before_skip']}% -> {e['after_skip']}% ({'+' if e['skip_delta'] >= 0 else ''}{e['skip_delta']}%)")
        print(f"    forced: {e['before_forced']} -> {e['after_forced']}")
    print()

if improved:
    print(f"IMPROVED ({len(improved)}):")
    for e in improved:
        print(f"  {e['name']} [{e['class']}]")
        print(f"    peak: {e['before_peak']}/s -> {e['after_peak']}/s ({e['peak_pct']}%)")
        print(f"    skip: {e['before_skip']}% -> {e['after_skip']}% ({'+' if e['skip_delta'] >= 0 else ''}{e['skip_delta']}%)")
    print()

if new_composables:
    print(f"NEW ({len(new_composables)}):")
    for name, a in new_composables:
        print(f"  {name} [{a.get('budgetClass','?')}] peak={a.get('peakRate',0)}/s")
    print()

if removed:
    print(f"REMOVED ({len(removed)}):")
    for name in removed:
        print(f"  {name}")
    print()

total_b = len(before)
total_a = len(after)
n_regressed = len(regressed)
n_improved = len(improved)
n_unchanged = total_a - n_regressed - n_improved - len(new_composables)

print(f"Summary: {total_a} composables ({n_improved} improved, {n_regressed} regressed, {n_unchanged} unchanged, {len(new_composables)} new, {len(removed)} removed)")

if n_regressed == 0 and n_improved > 0:
    print("Result: PASS")
    sys.exit(0)
elif n_regressed > 0:
    print("Result: FAIL — regressions detected")
    sys.exit(1)
else:
    print("Result: PASS — no significant changes")
    sys.exit(0)
PYEOF
}

# ── save command: capture live snapshot to file ──

do_save() {
    local FILE="$1"
    if [ -z "$FILE" ]; then
        FILE="rebound-$(date +%Y%m%d-%H%M%S).json"
    fi
    RESULT=$(exec_command "snapshot")
    if [ -z "$RESULT" ]; then
        echo "No response. Is the app running?" >&2
        exit 1
    fi
    echo "$RESULT" > "$FILE"
    # count composables
    COUNT=$(echo "$RESULT" | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('composables',{})))" 2>/dev/null || echo "?")
    echo "Saved $COUNT composables to $FILE"
}

# ── main ──

# Commands that don't need a device connection
if [ "$CMD" = "diff" ]; then
    do_diff "$2" "$3" "$4"
    exit $?
fi

# Commands that need a device connection
if ! connect; then
    echo "No connection — is the app running with Rebound enabled?" >&2
    echo "  Android: connect device/emulator, launch debug app" >&2
    echo "  iOS simulator: launch app in simulator" >&2
    echo "  iOS device: start 'rebound-relay' first, or use devicectl console" >&2
    exit 1
fi

if [ "$CMD" = "save" ]; then
    do_save "$2"
elif [ "$CMD" = "watch" ]; then
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
