---
title: History Tab
---

# History Tab

The History tab enables session persistence, VCS-correlated comparison, and regression detection across code changes.

## Session Persistence

Rebound saves session data to disk in the project's `.rebound/sessions/` directory:

```
.rebound/sessions/2026-03-08T14-30-00_main_abc1234.json.gz
```

The filename encodes:

- **Timestamp** -- when the session started
- **Branch** -- the git branch active at session start
- **Commit hash** -- the short commit hash at session start

### Storage format

Sessions are stored as gzipped JSON containing:

- All composable metrics (rates, budgets, skip rates, violation counts)
- Rate history samples at the configured snapshot interval
- Event log entries
- VCS context (branch name, full commit hash)
- Session duration

### Retention

The plugin retains a configurable maximum number of sessions (default: 20). Older sessions are automatically deleted when the limit is reached.

## Session List

The left panel displays past sessions:

| Column | Description |
|--------|-------------|
| **Date** | When the session started |
| **Duration** | How long the session ran |
| **Composables** | Number of instrumented composables seen |
| **Violations** | Total violation count during the session |
| **Branch** | Git branch at session start |
| **Commit** | Short commit hash |

The current session (if active) is highlighted at the top. Sessions load on demand -- the plugin scans the directory and parses filenames for metadata without loading full session data until selected.

## Side-by-Side Comparison

Select two sessions to see a comparison view:

### Per-composable delta

```
ProfileHeader:  3/s -> 18/s  (+500%)
UserList:       5/s -> 12/s  (+140%)
SearchBar:      2/s -> 2/s   (no change)
HomeScreen:     1/s -> 1/s   (no change)
```

Rate changes are color-coded: red for regressions (rate increased), green for improvements (rate decreased), gray for no change.

### VCS context

Each side of the comparison shows:

- Branch name
- Commit hash
- Timestamp

This lets you correlate: "Session A was on `main` at `abc1234`, session B was on `feature/user-profile` at `def5678`."

## Regression Detection

The "What Regressed?" view filters the comparison to show only composables that got worse:

```
UserCard:     3/s -> 18/s  (+500%)  <- UserCard.kt modified in abc1234
ProductList:  5/s -> 12/s  (+140%)  <- ProductList.kt NOT modified (cascade)
```

### VCS cross-reference

The regression view uses IntelliJ VCS APIs to determine which files were modified between the two sessions' commits. It then cross-references:

- Composables in files that were modified -- **direct cause** (the code change likely introduced the regression)
- Composables in files that were NOT modified -- **cascade effect** (the regression is caused by a change in a parent or shared state, not in the composable itself)

This distinction is critical for debugging: a cascade regression means you should look upstream, not at the composable's own code.

## Use Cases

### Catching regressions before code review

After making changes on a feature branch, run the app and compare the new session with the baseline from `main`. Any composables that regressed are flagged immediately, before the PR is even opened.

### Tracking improvements over time

After fixing a recomposition issue, compare sessions to verify the fix worked. The delta view quantifies the improvement: "ProfileHeader went from 18/s to 3/s (-83%)."

### Understanding long-term trends

With 20 sessions retained, you can trace how recomposition patterns evolve across multiple commits and see whether overall app health is improving or degrading.
