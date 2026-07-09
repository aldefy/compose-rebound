#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CLI="$ROOT_DIR/rebound-cli.sh"
TMP_DIR="$(mktemp -d)"
SERVER_PID=""

cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

assert_contains() {
    local FILE="$1"
    local EXPECTED="$2"
    if ! grep -F "$EXPECTED" "$FILE" >/dev/null; then
        echo "Expected to find: $EXPECTED" >&2
        echo "--- output ---" >&2
        cat "$FILE" >&2
        echo "--------------" >&2
        exit 1
    fi
}

free_port() {
    python3 - <<'PY'
import socket

sock = socket.socket()
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1])
sock.close()
PY
}

start_server() {
    local PORT="$1"
    local REQUEST_COUNT="$2"
    local LOG_FILE="$TMP_DIR/server-$PORT.log"

    python3 -u - "$PORT" "$REQUEST_COUNT" > "$LOG_FILE" 2>&1 <<'PY' &
import json
import socket
import sys

port = int(sys.argv[1])
request_count = int(sys.argv[2])

snapshot = {
    "composables": {
        "com.example.HomeScreen": {
            "budgetClass": "SCREEN",
            "budgetPerSecond": 3,
            "totalCompositions": 10,
            "peakRate": 1,
            "currentRate": 1,
            "skipRate": 0.8,
            "forcedCount": 0,
        }
    }
}
summary = {
    "topByRate": [
        {
            "name": "HomeScreen",
            "rate": 1,
            "budget": 3,
            "class": "SCREEN",
            "status": "OK",
        }
    ],
    "totalViolations": 0,
    "totalComposables": 1,
    "interactionState": "IDLE",
}
telemetry = {
    "budgetClassDistribution": {"SCREEN": 1},
    "averageRates": {"SCREEN": 1.0},
    "peakRates": {"SCREEN": 1},
    "averageSkipRate": 0.8,
    "totalViolations": 0,
}
responses = {
    "ping": "pong\n",
    "snapshot": json.dumps(snapshot) + "\n",
    "summary": json.dumps(summary) + "\n",
    "telemetry": json.dumps(telemetry) + "\n",
}

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", port))
server.listen(8)
print("READY", flush=True)

for _ in range(request_count):
    conn, _addr = server.accept()
    with conn:
        command = conn.recv(1024).decode().strip()
        conn.sendall(responses.get(command, "\n").encode())

server.close()
PY

    SERVER_PID=$!
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if grep -F "READY" "$LOG_FILE" >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.1
    done

    cat "$LOG_FILE" >&2
    fail "fake Rebound server did not start"
}

run_with_server() {
    local REQUEST_COUNT="$1"
    local OUTPUT_FILE="$2"
    shift 2

    local PORT
    PORT="$(free_port)"
    start_server "$PORT" "$REQUEST_COUNT"
    REBOUND_PORT="$PORT" "$CLI" "$@" > "$OUTPUT_FILE" 2>&1
    wait "$SERVER_PID"
    SERVER_PID=""
}

echo "rebound-cli smoke test"

bash -n "$CLI"

run_with_server 1 "$TMP_DIR/doctor.out" doctor
assert_contains "$TMP_DIR/doctor.out" "Result: PASS - Rebound is reachable over direct TCP."

run_with_server 2 "$TMP_DIR/ping.out" ping
assert_contains "$TMP_DIR/ping.out" "pong"

run_with_server 4 "$TMP_DIR/check.out" check
assert_contains "$TMP_DIR/check.out" "snapshot: ok (1 composables)"
assert_contains "$TMP_DIR/check.out" "summary: ok (1 top entries)"
assert_contains "$TMP_DIR/check.out" "Result: PASS"

run_with_server 2 "$TMP_DIR/snapshot.out" snapshot
assert_contains "$TMP_DIR/snapshot.out" "\"composables\""
assert_contains "$TMP_DIR/snapshot.out" "com.example.HomeScreen"

run_with_server 2 "$TMP_DIR/summary.out" summary
assert_contains "$TMP_DIR/summary.out" "\"totalViolations\": 0"

run_with_server 2 "$TMP_DIR/telemetry.out" telemetry
assert_contains "$TMP_DIR/telemetry.out" "\"budgetClassDistribution\""

SNAPSHOT_FILE="$TMP_DIR/saved.json"
run_with_server 2 "$TMP_DIR/save.out" save "$SNAPSHOT_FILE"
assert_contains "$TMP_DIR/save.out" "Saved 1 composables"
test -s "$SNAPSHOT_FILE" || fail "save did not write a snapshot"

cat > "$TMP_DIR/before.json" <<'JSON'
{
  "composables": {
    "com.example.HomeScreen": {
      "budgetClass": "SCREEN",
      "budgetPerSecond": 3,
      "totalCompositions": 10,
      "peakRate": 1,
      "skipRate": 0.8,
      "forcedCount": 0
    }
  }
}
JSON

cat > "$TMP_DIR/after.json" <<'JSON'
{
  "composables": {
    "com.example.HomeScreen": {
      "budgetClass": "SCREEN",
      "budgetPerSecond": 3,
      "totalCompositions": 12,
      "peakRate": 1,
      "skipRate": 0.8,
      "forcedCount": 0
    }
  }
}
JSON

"$CLI" diff "$TMP_DIR/before.json" "$TMP_DIR/after.json" > "$TMP_DIR/diff-pass.out" 2>&1
assert_contains "$TMP_DIR/diff-pass.out" "Result: PASS"

cat > "$TMP_DIR/regressed.json" <<'JSON'
{
  "composables": {
    "com.example.HomeScreen": {
      "budgetClass": "SCREEN",
      "budgetPerSecond": 3,
      "totalCompositions": 40,
      "peakRate": 8,
      "skipRate": 0.2,
      "forcedCount": 5
    }
  }
}
JSON

if "$CLI" diff "$TMP_DIR/before.json" "$TMP_DIR/regressed.json" > "$TMP_DIR/diff-fail.out" 2>&1; then
    cat "$TMP_DIR/diff-fail.out" >&2
    fail "diff should fail on a regression"
fi
assert_contains "$TMP_DIR/diff-fail.out" "Result: FAIL"

echo "PASS"
