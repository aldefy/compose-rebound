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
    var invalidationReason: String = ""
) {
    /** Simple function name, e.g. "StickerCanvas" */
    val simpleName: String
        get() = name.substringAfterLast('.')

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
