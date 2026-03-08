package io.aldefy.rebound.ide

data class ComposableEntry(
    val name: String,
    var rate: Int,
    var budget: Int,
    var budgetClass: String,
    var totalCount: Int = 0,
    var isViolation: Boolean = false,
    var isForced: Boolean = false,
    var changedParams: String = "",
    var skipPercent: Double = -1.0,
    var peakRate: Int = 0,
    var invalidationReason: String = "",
    var parentFqn: String = "",
    var depth: Int = 0,
    var paramStates: String = ""
) {
    /** Simple function name, e.g. "StickerCanvas" or "HomeScreen.Scaffold{}" */
    val simpleName: String
        get() {
            val last = name.substringAfterLast('.')
            // For lambda names (λN or Scaffold{}), include the parent for context
            if (last.startsWith("λ") || last.endsWith("{}")) {
                val parts = name.split('.')
                val parentIdx = parts.size - 2
                if (parentIdx >= 0) return "${parts[parentIdx]}.$last"
            }
            return last
        }

    val reason: String
        get() = when {
            invalidationReason.isNotEmpty() -> invalidationReason
            isForced -> "FORCED (parent)"
            changedParams.isNotEmpty() -> changedParams
            skipPercent >= 0 -> "skip ${skipPercent}%"
            else -> "—"
        }

    val status: String
        get() = if (!isViolation || rate <= budget) {
            "OK"
        } else {
            val ratio = rate.toDouble() / budget.toDouble()
            "%.1fx OVER".format(ratio)
        }
}
