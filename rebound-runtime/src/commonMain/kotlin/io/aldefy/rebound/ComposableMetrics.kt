package io.aldefy.rebound

import kotlin.concurrent.Volatile

class ComposableMetrics(var budgetClass: BudgetClass) {
    @Volatile private var enterCount: Long = 0
    @Volatile private var compositionCount: Long = 0
    @Volatile private var windowStartTimeNs: Long = 0
    @Volatile private var windowCount: Int = 0
    @Volatile private var lastWindowCount: Int = 0
    @Volatile private var peakRate: Int = 0
    @Volatile private var forcedCount: Long = 0
    @Volatile private var paramDrivenCount: Long = 0

    /** Last decoded parameter states from $changed bitmask, e.g. "user=DIFFERENT,onClick=STATIC" */
    @Volatile var lastParamStates: String = ""

    val totalCount: Long get() = compositionCount

    val forcedRecompositionCount: Long get() = forcedCount
    val paramDrivenRecompositionCount: Long get() = paramDrivenCount

    /** Total number of times the composable was entered (including skips). */
    val totalEnters: Long get() = enterCount

    /** Number of times the composable was skipped (entered but body not executed). */
    val skipCount: Long get() = enterCount - compositionCount

    /** Fraction of enters that resulted in a skip (0.0 = never skipped, 1.0 = always skipped). */
    val skipRate: Float get() = if (enterCount > 0) skipCount.toFloat() / enterCount else 0f

    /** Record that the composable was entered (fires before Compose's skip check). */
    fun recordEnter() {
        enterCount++
    }

    fun recordComposition(currentTimeNs: Long, changedMask: Int = 0): Int {
        compositionCount++
        if (changedMask and 0b1 != 0) forcedCount++
        else if (changedMask != 0) paramDrivenCount++
        val elapsed = currentTimeNs - windowStartTimeNs
        if (elapsed > 1_000_000_000L) { // 1 second window
            lastWindowCount = windowCount
            windowStartTimeNs = currentTimeNs
            windowCount = 1
        } else {
            windowCount++
        }
        if (windowCount > peakRate) {
            peakRate = windowCount
        }
        return windowCount
    }

    /** Returns the best available rate: current window if it has meaningful data, else last completed window. */
    fun currentRate(): Int = if (windowCount > 1) windowCount else lastWindowCount

    fun peakRate(): Int = peakRate
}
