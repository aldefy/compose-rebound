---
name: rebound
description: >
  Rebound recomposition budget monitor skill for Compose performance analysis.
  Guides budget class interpretation, violation diagnosis, skip rate analysis,
  stability optimization, CLI usage, and IDE plugin workflow. Use this skill
  whenever the user mentions recomposition, budget, violation, Rebound, skip rate,
  jank, performance, composable slow, recompose, @ReboundBudget, budget class,
  recomposition rate, skip percentage, forced recomposition, param-driven, stability,
  unstable parameters, or asks why a composable is recomposing too often. Also trigger
  when the user says "my composable is slow", "too many recompositions", "budget exceeded",
  "recomposition spike", or asks about Compose runtime performance monitoring.
---

# Rebound — Recomposition Budget Monitor Skill

Practical guidance for diagnosing and fixing Compose recomposition performance issues
using Rebound's compiler plugin, runtime, IDE plugin, and CLI tooling.

## Workflow

When helping with Compose recomposition performance, follow this checklist:

### 1. Understand the problem

- Is this a setup question, a violation diagnosis, or a stability optimization?
- Is the user looking at CLI output, IDE data, or logcat warnings?
- What platform? Android, iOS simulator, iOS physical device, or KMP?

### 2. Consult the right reference

Read the relevant reference file(s) from `references/` before answering:

| Topic | Reference File |
|-------|---------------|
| Budget classes, thresholds, color coding, dynamic scaling, `@ReboundBudget` | `references/budget-classes.md` |
| Violation patterns per budget class, diagnostic workflow, code fixes | `references/diagnosing-violations.md` |
| CLI commands, JSON snapshot structure, connection modes, netcat queries | `references/cli-usage.md` |
| IDE 5-tab cockpit, editor integration, connection setup | `references/ide-plugin.md` |
| Gradle plugin setup, config options, KMP, iOS relay, Kotlin version matrix | `references/setup-guide.md` |
| Skip rate formula, `$changed` bitmask, param states, forced vs param-driven, fix strategies | `references/skip-rate-stability.md` |

### 3. Diagnose before fixing

Follow this diagnostic sequence:

1. **Get the data** — Run `./rebound-cli.sh snapshot` or check the IDE Hot Spots tab
2. **Identify the violator** — Which composable exceeds its budget? What's its budget class?
3. **Check skip rate** — Low skip rate (<50%) means parameters keep changing. High skip rate (>80%) with high rate means forced recomposition from parent.
4. **Check param states** — Look at `paramStates` and `paramTypes` in the snapshot. Are params `DIFFERENT`? Are they `unstable` or `lambda`?
5. **Check the call tree** — Is this composable being forced by a parent? Check the `parent` field and `forcedCount`.
6. **Apply the fix** — Consult `references/diagnosing-violations.md` for the pattern that matches.

### 4. Apply and verify

- Write the minimal correct fix — don't over-engineer
- Re-run `./rebound-cli.sh snapshot` or check the IDE to verify the fix reduced the rate
- Flag any anti-patterns you see in the user's existing code

## Key Concepts

1. **Every `@Composable` gets a budget.** Rebound auto-classifies composables into 7 budget
   classes (SCREEN, LEAF, CONTAINER, INTERACTIVE, LIST_ITEM, ANIMATED, UNKNOWN) based on
   IR heuristics. Each has a max recompositions/second threshold.

2. **Budgets scale dynamically.** During scrolling (2x), animation (1.5x), or user input (1.5x),
   budgets increase to avoid false positives.

3. **Skip rate reveals the problem type.** Low skip rate = unstable params causing unnecessary
   work. High skip rate + high enter rate = parent forcing recomposition on children that
   correctly skip.

4. **The `$changed` bitmask tells you exactly which params changed.** 3 bits per parameter:
   UNCERTAIN (000), SAME (001), DIFFERENT (010), STATIC (100). This is the ground truth
   for why a composable recomposed.

5. **Forced vs param-driven matters.** Forced means a parent invalidated and all children
   re-enter. Param-driven means this composable's own inputs changed. Different root causes,
   different fixes.

## Common Quick Fixes

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| SCREEN at >3/s | Reading frequently-changing state at screen level | Hoist state down to the child that needs it |
| LEAF at >5/s | Unstable parameter (data class without `@Stable`) | Add `@Stable`/`@Immutable` or wrap in `remember` |
| CONTAINER at >10/s | Unstable lambda passed as child content | Use `remember { }` around the lambda |
| Low skip rate (<20%) | Most params are `unstable` or `lambda` type | Stabilize data classes, `remember` lambdas |
| High forced count | Parent recomposing unnecessarily | Fix the parent first — violations cascade down |
| LIST_ITEM at >60/s during scroll | Item composable doing heavy work | Move heavy computation to `remember` or ViewModel |
