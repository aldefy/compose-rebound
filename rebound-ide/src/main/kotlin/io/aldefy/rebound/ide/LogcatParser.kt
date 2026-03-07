package io.aldefy.rebound.ide

object LogcatParser {

    // Matches: "name composed (#N, rate=R/s, budget=B/s, class=C, skip=X% [FORCED] | p=CHANGED)"
    // Groups: 1=name, 2=count, 3=rate, 4=budget, 5=class, 6=rest (skip%, FORCED, changed params)
    private val COMPOSITION_REGEX = Regex(
        """(\S+) composed \(#(\d+), rate=(\d+)/s, budget=(\d+)/s, class=(\w+)(.*)\)"""
    )

    private val SKIP_REGEX = Regex("""skip=([\d.]+)%""")

    // Matches: "BUDGET VIOLATION: name rate=R/s exceeds CLASS budget=B/s ..."
    // Continuation line: "  → params: p=CHANGED" or "  → forced recomposition"
    private val VIOLATION_REGEX = Regex(
        """BUDGET VIOLATION: (\S+) rate=(\d+)/s exceeds (\w+) budget=(\d+)/s"""
    )

    private val VIOLATION_PARAMS_REGEX = Regex(
        """→ params: (.+)"""
    )

    private val VIOLATION_FORCED_REGEX = Regex(
        """→ forced recomposition"""
    )

    // Track last violation for multi-line parsing
    private var lastViolationEntry: ComposableEntry? = null

    fun parse(line: String): ComposableEntry? {
        // Check for violation continuation lines first
        val pending = lastViolationEntry
        if (pending != null) {
            VIOLATION_PARAMS_REGEX.find(line)?.let { match ->
                pending.changedParams = match.groupValues[1].trim()
                return null // already emitted
            }
            VIOLATION_FORCED_REGEX.find(line)?.let {
                pending.isForced = true
                return null
            }
            // Not a continuation — clear tracking
            lastViolationEntry = null
        }

        VIOLATION_REGEX.find(line)?.let { match ->
            val (name, rate, budgetClass, budget) = match.destructured
            val entry = ComposableEntry(
                name = name,
                rate = rate.toInt(),
                budget = budget.toInt(),
                budgetClass = budgetClass,
                isViolation = true,
                isForced = line.contains("forced recomposition"),
                changedParams = extractParamsFromViolation(line)
            )
            lastViolationEntry = entry
            return entry
        }

        COMPOSITION_REGEX.find(line)?.let { match ->
            val (name, count, rate, budget, budgetClass, rest) = match.destructured
            val isForced = rest.contains("[FORCED]")
            val changedParams = extractChangedParams(rest)
            val skipMatch = SKIP_REGEX.find(rest)
            val skipPct = skipMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0
            return ComposableEntry(
                name = name,
                rate = rate.toInt(),
                budget = budget.toInt(),
                budgetClass = budgetClass,
                totalCount = count.toInt(),
                isForced = isForced,
                changedParams = changedParams,
                skipPercent = skipPct
            )
        }

        return null
    }

    /** Extract "param=CHANGED, param2=CHANGED" from the tail of a composition line */
    private fun extractChangedParams(rest: String): String {
        val pipeIdx = rest.indexOf('|')
        if (pipeIdx < 0) return ""
        return rest.substring(pipeIdx + 1).trim()
    }

    /** Extract params from single-line violation (if present) */
    private fun extractParamsFromViolation(line: String): String {
        val idx = line.indexOf("→ params:")
        if (idx < 0) return ""
        return line.substring(idx + "→ params:".length).trim()
    }
}
