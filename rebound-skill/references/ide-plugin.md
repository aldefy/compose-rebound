# IDE Plugin

The Rebound Android Studio plugin provides a 5-tab performance cockpit with editor integration. Targets Android Studio Meerkat 2024.3.1.14+ (`sinceBuild = "242"`), unbounded `untilBuild`.

## Connection Setup

The plugin connects via ADB socket forwarding:

```bash
adb forward tcp:18462 localabstract:rebound
```

This happens automatically when the plugin starts. The status bar widget shows connection state.

## The 5 Tabs

### Monitor

Live composable tree with sparkline rate history per node. Each node shows its current rate, budget class, and color-coded status (green/yellow/red).

Scrolling event log at the bottom shows recomposition events, violations, and state changes in real time.

**Use for:** Getting a live overview of what's recomposing right now.

### Hot Spots

Sortable flat table of all tracked composables, ranked by severity:
- **OVER** (red) sorted first
- **NEAR** (yellow) second
- **OK** (green) third

Summary card at the top: violation count / warning count / OK count.

Click any row to jump to source.

**Use for:** Finding the worst offenders quickly. This is your starting point for diagnosis.

### Timeline

Composable x time heatmap. Each cell is colored green/yellow/red based on budget status at that moment. Scroll back up to 60 minutes.

**Use for:** Correlating recomposition spikes with user interactions. "Did the spike happen during scroll, during navigation, or during data load?"

### Stability

Parameter stability matrix showing per-parameter state for each composable:

| Column | What it shows |
|--------|---------------|
| Param | Parameter name |
| Type | `stable`, `unstable`, or `lambda` |
| Equality | SAME, DIFFERENT, STATIC, or UNCERTAIN |
| Last State | Current `$changed` mask value |
| Change Frequency | How often this param triggers recomposition |
| Advisory | AI-generated fix suggestion |

Advisory examples:
- Unstable + DIFFERENT → "Consider @Stable or remember"
- Lambda + DIFFERENT → "Use remember { }"

**Cascade impact tree** visualizes how unstable parameters propagate recompositions through the hierarchy.

**Strong Skipping banner:** Appears when `skipRate < 20%` and majority of params are unstable/lambda type. Indicates the composable isn't benefiting from Compose's skip optimization.

**Use for:** Understanding exactly which parameters are causing recomposition and what to stabilize.

### History

Saved sessions stored in `.rebound/sessions/`. Each session includes:
- Timestamp
- VCS branch name and commit hash
- Full snapshot data

Side-by-side comparison view for before/after regression analysis.

**Use for:** Comparing performance across commits. "Did my refactor help or hurt?"

## Editor Integration

### Gutter Icons

Red, yellow, or green dots appear next to `@Composable` function declarations, reflecting live budget status. Click to open the Monitor tab filtered to that composable.

### CodeVision Inlays

Inline hints above each composable function:

```
> 12/s | budget: 8/s | OVER | skip: 45%
```

Shows current rate, budget, status, and skip rate at a glance without leaving the editor.

### Status Bar Widget

Persistent widget at the bottom of the IDE:

```
Rebound: 45 composables | 3 violations
```

Click to open the Rebound tool window. Shows connection status (connected/disconnected).

## Configuration

**Preferences > Tools > Rebound:**
- Enable/disable gutter icons
- Enable/disable CodeVision inlays
- Auto-connect on project open
- Session auto-save interval

## Troubleshooting

| Issue | Fix |
|-------|-----|
| "Not connected" in status bar | Run `adb forward tcp:18462 localabstract:rebound` manually, or ensure the app is running with Rebound enabled |
| No composables showing | Check `ReboundTracker.enabled = true` in the app. Check logcat for `Rebound` tag. |
| Gutter icons not updating | Rebound needs active recompositions to report. Interact with the app. |
| Plugin not visible | Verify Android Studio version is 2024.3.1.14+ (Meerkat). Check `sinceBuild = "242"`. |
