# Rebound IDE Plugin — Screen Recording Demo Guide

Step-by-step instructions for recording a demo of all Rebound IDE plugin features.

## Prerequisites

- Android Studio Panda 2 (build 253) or later
- Physical device or emulator connected via ADB
- Rebound plugin installed (build from source: `./gradlew :rebound-ide:buildPlugin`)
- Sample app installed: `./gradlew :sample:installDebug`
- ADB forward set up: `adb forward tcp:18462 localabstract:rebound`

## Recording Setup

- Resolution: 1920x1080 or higher
- Show Android Studio full screen
- Have sample app running on device (visible in a small window or via scrcpy)
- Open a Compose file in the editor (e.g., `OverRecomposingScreen.kt`)

---

## Phase 1: Monitor Tab (Live Recomposition Tracking)

**What to show:** Real-time composable tree with rates, budgets, sparkline, and event log.

1. Open the **Rebound** tool window (bottom panel, or View → Tool Windows → Rebound)
2. You should be on the **Monitor** tab by default
3. Click **Start** — status changes to "Capturing..."
4. On the device, interact with the sample app (scroll, tap counter)
5. **Show the tree:** composables appear with rates, budgets, skip%
   - Point out color coding: red = over budget, yellow = near, green = OK
6. **Click a composable** in the tree → detail panel shows full info on the right
7. **Show the sparkline:** below the detail panel, a 60-second rate history chart appears
   - Green line = within budget, red = over budget
   - Dashed line = budget threshold
   - Red translucent fill where rate exceeds budget
8. **Show the event log:** bottom panel scrolls with timestamped events
   - Red entries = budget violations
   - Gray entries = state changes, new composables appearing
9. Click **Clear** to reset everything
10. Click **Stop** to end capture

**Key talking points:**
- 1-second polling interval, zero-allocation ring buffer
- Sparkline shows trends you'd miss looking at instantaneous rates
- Event log gives you a timeline of what happened and when

---

## Phase 2: Hot Spots Tab (Worst Offenders at a Glance)

**What to show:** Flat sortable table ranking composables by severity.

1. Click **Start** on Monitor tab first (captures data for all tabs)
2. Switch to the **Hot Spots** tab
3. **Show the summary card** at top: "N violations | N near budget | N OK"
4. **Show the table:** columns are Composable, Rate/s, Budget/s, Ratio, Skip%, Peak/s, Class, Status
5. **Click column headers** to sort — default is Rate/s descending
6. **Point out the Status column:** red/yellow/green icons
7. Let it run for 30 seconds, show Peak/s column updating
8. Switch back to Monitor to show data is shared (same session)

**Key talking points:**
- No more scanning a tree to find the worst composable
- Sortable by any metric — ratio (rate/budget) is often most useful
- Summary card gives instant health check

---

## Phase 3: Timeline Heatmap (Composable x Time Visualization)

**What to show:** Heatmap showing recomposition intensity over time.

1. Make sure capture is running (Start on Monitor tab)
2. Let it capture for 30-60 seconds with app interaction
3. Switch to the **Timeline** tab
4. **Show the heatmap:**
   - X-axis = time (scrollable)
   - Y-axis = composables (sorted by peak rate)
   - Color: dark gray (idle) → green (OK) → yellow (near budget) → red (over budget)
5. **Scroll horizontally** to see how patterns change over time
6. **Point out bursts:** red cells clustered together = recomposition storm
7. Hover/click cells to see tooltip with exact values

**Key talking points:**
- See the full picture: which composables spike together
- Identify cascading recomposition patterns
- Rendered to BufferedImage for performance — no lag even with 100+ composables

---

## Phase 4: Editor Integration (Inline Feedback)

**What to show:** Gutter icons, CodeVision inlays, and status bar widget — all live.

### 4a. Gutter Icons

1. Open `OverRecomposingScreen.kt` in the editor
2. Make sure capture is running
3. **Point out colored dots** in the gutter next to `@Composable` functions:
   - Red dot = over budget
   - Yellow dot = approaching budget (>70%)
   - Green dot = within budget
4. **Hover over a dot** → balloon popup shows rate, budget, skip%, status
5. Scroll through other Compose files to show dots appearing everywhere

### 4b. Code Vision (Inlay Hints)

1. In the same file, **point out the text above `@Composable` functions:**
   ```
   ▸ 12/s | budget: 8/s | OVER | skip: 45%
   ```
2. This updates live as the app recomposes
3. Show it on multiple functions — each has its own metrics
4. Mention: toggleable in Settings → Inlay Hints → Rebound

### 4c. Status Bar Widget

1. **Point to bottom-right of Android Studio:**
   ```
   Rebound: 45 composables | 3 violations
   ```
2. Show it updating as violations appear/resolve
3. **Click it** → opens the Rebound tool window
4. Stop capture → widget shows "Rebound: —"

**Key talking points:**
- Never leave your editor to check recomposition health
- Gutter dots give instant visual feedback while coding
- CodeVision shows exact numbers without hovering
- Status bar = global health indicator

---

## Phase 5: End-to-End Workflow Demo

**What to show:** A realistic debugging workflow using all features together.

1. **Start capture** in Monitor tab
2. Interact with the sample app — trigger heavy recompositions (rapid counter increments)
3. **Glance at status bar** — see "3 violations" appear
4. **Switch to Hot Spots** — immediately see the worst offender at the top
5. **Double-click** the top violator (if source navigation is wired) or note its name
6. **Open the source file** — see the red gutter dot and CodeVision inlay
7. **Switch to Timeline** — see the red burst in the heatmap corresponding to your interaction
8. **Switch to Monitor** — select the composable, see the sparkline showing the spike
9. **Check event log** — scroll back to see when the violation started
10. **Stop capture**

**Key talking points:**
- Full observability loop: detect → locate → understand → fix
- Status bar alerts you, Hot Spots ranks severity, Timeline shows when, Editor shows where
- All powered by the same 1-second socket polling — no extra overhead

---

## Sample App Interactions for Triggering Recompositions

The sample app (`io.aldefy.rebound.sample`) has built-in scenarios:

- **Counter screen:** Auto-incrementing counter at ~60fps — guaranteed budget violations
- **StableList:** LazyColumn with items — scroll to trigger recompositions
- **ProblemComposables:** Intentionally unstable composables for testing
- **PlaygroundAntipatterns:** Common Compose antipatterns that cause excessive recomposition

For best demo results:
1. Launch the sample app
2. Wait 2-3 seconds for initial composables to register
3. Start interacting — the counter auto-runs, so violations appear immediately
4. Scroll the list to trigger additional recomposition bursts

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| "Stopped" won't change to "Capturing" | Run `adb forward tcp:18462 localabstract:rebound` |
| No composables in tree | Make sure sample app is in foreground and Rebound runtime is active |
| Gutter dots not appearing | Check Settings → Inlay Hints → Rebound is enabled |
| Plugin not in tool windows | Reinstall: `./gradlew :rebound-ide:buildPlugin`, then install from disk |
| Heatmap is empty | Capture needs 5+ seconds of data before snapshots appear |

## Recording Tips

- Record each phase as a separate segment, edit together later
- Use keyboard shortcuts to switch tabs quickly (no mouse fumbling)
- Keep scrcpy or device screen visible so viewers see what triggers recompositions
- Pause briefly on each feature before moving on — let it sink in
- Total recording time: aim for 3-4 minutes final cut
