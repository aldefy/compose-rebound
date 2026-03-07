# Budget Calibration Methodology

## Goal

Validate and refine Rebound's budget numbers using real-world recomposition data from production apps.

## Current Budget Values (Educated Estimates)

| Budget Class | Rate (compositions/sec) | Rationale |
|-------------|------------------------|-----------|
| SCREEN | 3/s | Full screens shouldn't recompose often |
| LEAF | 5/s | Stateless leaves are cheap but shouldn't thrash |
| CONTAINER | 10/s | Layout containers have moderate cost |
| INTERACTIVE | 30/s | Buttons/inputs respond to rapid user input |
| LIST_ITEM | 60/s | Scroll-driven, per-frame at 60fps |
| ANIMATED | 120/s | Animation-driven, up to 120fps displays |

## Data Collection

### What We Collect (Telemetry)

- Composable count per budget class
- Average/peak recomposition rates per class
- Skip rates per class
- Violation counts per class
- Forced vs param-driven recomposition ratios

### What We Do NOT Collect

- Function names or package names
- App identifiers
- User identifiers
- Any personally identifiable information

### How to Participate

```kotlin
// In your Application.onCreate():
ReboundTelemetry.enabled = true

// After a test session:
val report = ReboundTelemetry.toJson()
// Submit to: (URL TBD)
```

## Analysis Plan

1. **Collect** aggregate data from 50+ apps across different categories
2. **Correlate** recomposition rates with frame drop data (requires separate profiling)
3. **Cluster** composables by actual behavior patterns vs budget class assignment
4. **Refine** budget thresholds based on the "elbow" where jank starts appearing
5. **Validate** refined budgets against a fresh set of apps

## Key Questions

- Are the current 7 budget classes the right taxonomy?
- Should budgets be device-tier dependent (low-end vs flagship)?
- What's the correlation between skip rate and performance?
- Do forced recompositions need separate budget treatment?
