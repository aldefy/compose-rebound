package io.aldefy.rebound

/**
 * Opt-in anonymized telemetry for empirical budget calibration research.
 *
 * Collects ONLY aggregate statistics by budget class. NO function names,
 * NO package names, NO app identifiers. Users must explicitly enable this.
 */
object ReboundTelemetry {
    /** Must be explicitly set to true by the app developer. Off by default. */
    var enabled: Boolean = false

    /**
     * Generate an anonymized telemetry report from current tracker state.
     * Contains only statistical distributions — no identifying information.
     */
    fun generateReport(): TelemetryReport {
        val snapshot = ReboundTracker.snapshot()

        val budgetClassDistribution = mutableMapOf<String, Int>()
        val totalRateByClass = mutableMapOf<String, Float>()
        val peakRateByClass = mutableMapOf<String, Float>()
        val skipRateByClass = mutableMapOf<String, Float>()
        val countByClass = mutableMapOf<String, Int>()
        var violationCount = 0
        val violationsByClass = mutableMapOf<String, Int>()

        for ((_, metrics) in snapshot) {
            val className = metrics.budgetClass.name
            budgetClassDistribution[className] = (budgetClassDistribution[className] ?: 0) + 1
            countByClass[className] = (countByClass[className] ?: 0) + 1
            totalRateByClass[className] = (totalRateByClass[className] ?: 0f) + metrics.currentRate()
            peakRateByClass[className] = maxOf(peakRateByClass[className] ?: 0f, metrics.peakRate().toFloat())
            if (metrics.totalEnters > 0) {
                skipRateByClass[className] = (skipRateByClass[className] ?: 0f) + metrics.skipRate
            }
            if (metrics.currentRate() > metrics.budgetClass.baseBudgetPerSecond) {
                violationCount++
                violationsByClass[className] = (violationsByClass[className] ?: 0) + 1
            }
        }

        // Average the rates
        val avgRateByClass = totalRateByClass.mapValues { (cls, total) ->
            val count = countByClass[cls] ?: 1
            if (count > 0) total / count else 0f
        }
        val avgSkipRateByClass = skipRateByClass.mapValues { (cls, total) ->
            val count = countByClass[cls] ?: 1
            if (count > 0) total / count else 0f
        }

        return TelemetryReport(
            composableCount = snapshot.size,
            budgetClassDistribution = budgetClassDistribution,
            violationCount = violationCount,
            violationsByClass = violationsByClass,
            averageRateByClass = avgRateByClass,
            peakRateByClass = peakRateByClass,
            skipRateByClass = avgSkipRateByClass
        )
    }

    /** Export report as JSON string (manual serialization, no external deps). */
    fun toJson(): String {
        if (!enabled) return "{\"error\": \"telemetry not enabled\"}"
        return generateReport().toJson()
    }
}

data class TelemetryReport(
    val composableCount: Int,
    val budgetClassDistribution: Map<String, Int>,
    val violationCount: Int,
    val violationsByClass: Map<String, Int>,
    val averageRateByClass: Map<String, Float>,
    val peakRateByClass: Map<String, Float>,
    val skipRateByClass: Map<String, Float>
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"composableCount\": $composableCount,")
        appendLine("  \"budgetClassDistribution\": {${budgetClassDistribution.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},")
        appendLine("  \"violationCount\": $violationCount,")
        appendLine("  \"violationsByClass\": {${violationsByClass.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},")
        appendLine("  \"averageRateByClass\": {${averageRateByClass.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},")
        appendLine("  \"peakRateByClass\": {${peakRateByClass.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},")
        appendLine("  \"skipRateByClass\": {${skipRateByClass.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}}")
        appendLine("}")
    }
}
