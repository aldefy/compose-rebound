package io.aldefy.rebound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposableMetricsJvmTest {
    @Test
    fun currentRateDropsToZeroWhenComposableStopsComposing() {
        val metrics = ComposableMetrics(BudgetClass.SCREEN)

        repeat(5) {
            metrics.recordComposition(currentTimeNanos())
        }

        assertTrue(metrics.currentRate() > 0)

        Thread.sleep(2_100)

        assertEquals(0, metrics.currentRate())
        assertEquals(5, metrics.peakRate())
    }
}
