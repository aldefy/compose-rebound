# Rebound iOS Roadmap

## Current Status (March 2026)

### Production-Ready

| Component | Target | Status |
|-----------|--------|--------|
| Compiler Plugin | All KMP targets (Android, iOS, JVM, Wasm) | READY |
| Gradle Plugin | KMP-aware, auto-selects compiler by Kotlin version | READY |
| Runtime (Simulator) | TCP server on `:18462`, full socket API | READY |
| Runtime (Physical Device) | WebSocket relay + console fallback | READY |
| CLI | Simulator (direct TCP), physical (devicectl + relay) | READY |
| WebSocket Relay | Swift CLI, Bonjour discovery, bridges TCP/WS | READY |
| IDE Plugin | Simulator (direct TCP), Android (ADB forward) | READY |

### Validated Apps

| App | Kotlin | iOS | Android |
|-----|--------|-----|---------|
| KMP App (2-target) | 2.0.21 | PASS | PASS |
| StickerExplode | 2.1.0 | — | PASS |
| HelloDistort | 2.1.0 | — | PASS |
| Lumen | 2.0.21 | PASS | PASS |
| Andromeda | 2.2.20 | — | PASS (rebound-compiler-k2) |

---

## Gaps

### IDE plugin cannot connect to physical iOS devices (HIGH)

`ReboundConnection.kt` tries ADB forward or direct TCP. When the relay is running on `:18462`, `tryDirectTcp()` succeeds transparently — so the IDE *does* work with physical devices if the relay is running. The gap is:
- No auto-detection of iOS vs Android context
- Error message doesn't mention relay
- No auto-spawn of relay process

**Fix:** Update error messaging. Optionally auto-detect relay availability and prompt user.

### No DNS resolution in WebSocket client (MEDIUM)

`connectTcp()` in `ReboundServer.kt` only parses dotted-quad IPs and "localhost". `REBOUND_RELAY_HOST=macbook.local:18463` won't resolve.

**Workaround:** Bonjour auto-discovers relay on the network, so this only matters for the env var override path.

**Fix:** Add `getaddrinfo()` call via POSIX cinterop.

### Devicectl NSLog parsing is fragile (MEDIUM)

`rebound-cli.sh` greps for `\[Rebound:$CMD\]` in console output. NSLog format varies by OS version — timestamp prefixes, thread IDs, and subsystem tags can change.

**Fix:** Use `os_log` with a structured subsystem/category, or switch physical device CLI path to relay-only.

### Single-device relay routing (LOW)

Relay routes all CLI commands to the first connected iOS device. No device selection or multi-device support.

**Fix:** Add device ID header to WebSocket messages. CLI gains `--device <id>` flag. Relay routes by ID.

---

## Roadmap

### Phase 3: Polish for Public Release

- [ ] IDE plugin: improve error messages for iOS physical device (mention relay)
- [ ] IDE plugin: detect relay on `:18462` before falling back to ADB
- [ ] DNS resolution via `getaddrinfo()` in `connectTcp()`
- [ ] Harden NSLog parsing in CLI, or deprecate devicectl path in favor of relay
- [ ] Add `./gradlew reboundRelay` task to auto-build and start relay from Gradle
- [ ] Documentation: setup guide for iOS physical device monitoring

### Phase 4: Multi-Device & Scale

- [ ] Multi-device routing in relay (device ID selection)
- [ ] CLI `--device` flag for targeting specific devices
- [ ] IDE device picker (dropdown for connected iOS devices)
- [ ] Session history / metrics persistence in relay
- [ ] TLS support for relay (secure on shared networks)

### Phase 5: Deep iOS Integration

- [ ] Xcode plugin or extension for in-IDE monitoring
- [ ] `os_log` structured logging (replace NSLog for better parsing)
- [ ] Instruments integration (feed recomposition data into Instruments traces)
- [ ] SwiftUI interop tracking (Compose views embedded in SwiftUI)
