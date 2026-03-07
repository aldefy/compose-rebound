package io.aldefy.rebound

/**
 * Compare two snapshots to detect recomposition regressions.
 */
object ReboundBaseline {

    data class Regression(
        val composable: String,
        val budgetClass: String,
        val baselinePeakRate: Int,
        val currentPeakRate: Int,
        val increasePercent: Int
    )

    /**
     * Compare current snapshot against a baseline.
     * @param regressionThreshold percentage increase that counts as regression (default 20%)
     * @return list of regressions, empty if no regressions detected
     */
    fun compare(
        baseline: ReboundSnapshot,
        current: ReboundSnapshot,
        regressionThreshold: Int = 20
    ): List<Regression> {
        val regressions = mutableListOf<Regression>()
        for ((key, currentSnap) in current.composables) {
            val baselineSnap = baseline.composables[key] ?: continue
            if (baselineSnap.peakRate == 0) continue
            val increase = ((currentSnap.peakRate - baselineSnap.peakRate) * 100) / baselineSnap.peakRate
            if (increase > regressionThreshold) {
                regressions.add(Regression(
                    composable = key,
                    budgetClass = currentSnap.budgetClass,
                    baselinePeakRate = baselineSnap.peakRate,
                    currentPeakRate = currentSnap.peakRate,
                    increasePercent = increase
                ))
            }
        }
        return regressions.sortedByDescending { it.increasePercent }
    }

    /** Format regressions as a human-readable report */
    fun formatReport(regressions: List<Regression>): String {
        if (regressions.isEmpty()) return "No regressions detected."
        val sb = StringBuilder()
        sb.appendLine("=== Rebound Regression Report ===")
        sb.appendLine("${regressions.size} regression(s) detected:\n")
        regressions.forEach { r ->
            sb.appendLine("  ${r.composable}")
            sb.appendLine("    class=${r.budgetClass}, baseline=${r.baselinePeakRate}/s -> current=${r.currentPeakRate}/s (+${r.increasePercent}%)")
        }
        sb.appendLine("\n=================================")
        return sb.toString()
    }
}
