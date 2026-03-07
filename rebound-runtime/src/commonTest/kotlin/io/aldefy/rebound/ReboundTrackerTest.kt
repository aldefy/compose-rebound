package io.aldefy.rebound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReboundTrackerTest {
    @Test
    fun recordsCompositionCount() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        ReboundTracker.logCompositions = false

        ReboundTracker.onComposition("TestScreen", BudgetClass.SCREEN.ordinal, 0, "")
        ReboundTracker.onComposition("TestScreen", BudgetClass.SCREEN.ordinal, 0, "")

        val snapshot = ReboundTracker.snapshot()
        assertEquals(2L, snapshot["TestScreen"]?.totalCount)
    }

    @Test
    fun differentComposablesTrackedSeparately() {
        ReboundTracker.reset()
        ReboundTracker.onComposition("ScreenA", BudgetClass.SCREEN.ordinal, 0, "")
        ReboundTracker.onComposition("ScreenB", BudgetClass.LEAF.ordinal, 0, "")

        val snapshot = ReboundTracker.snapshot()
        assertEquals(1L, snapshot["ScreenA"]?.totalCount)
        assertEquals(1L, snapshot["ScreenB"]?.totalCount)
        assertEquals(BudgetClass.SCREEN, snapshot["ScreenA"]?.budgetClass)
        assertEquals(BudgetClass.LEAF, snapshot["ScreenB"]?.budgetClass)
    }

    @Test
    fun disabledTrackerDoesNotRecord() {
        ReboundTracker.reset()
        ReboundTracker.enabled = false
        ReboundTracker.onComposition("TestScreen", BudgetClass.SCREEN.ordinal, 0, "")
        assertTrue(ReboundTracker.snapshot().isEmpty())
        ReboundTracker.enabled = true
    }

    @Test
    fun changedMaskDecoderBasics() {
        // Simulate: param0=Different (bits 010 at position 1), param1=Same (bits 001 at position 2)
        // Bit layout: [force:1bit] [param0:3bits] [param1:3bits] ...
        // param0 at bits 1-3: Different = 0b010 << 3 = 0b010_000 (shifted by 3*1=3)
        // param1 at bits 4-6: Same = 0b001 << 6 = 0b001_000_000 (shifted by 3*2=6)
        val mask = (0b010 shl 3) or (0b001 shl 6) // param0=Different, param1=Same
        val decoded = ChangedMaskDecoder.decode(mask, "offset,scale")

        assertEquals(2, decoded.size)
        assertEquals("offset", decoded[0].first)
        assertEquals(ChangedMaskDecoder.ParamState.DIFFERENT, decoded[0].second)
        assertEquals("scale", decoded[1].first)
        assertEquals(ChangedMaskDecoder.ParamState.SAME, decoded[1].second)
    }

    @Test
    fun changedMaskFormatShowsOnlyChanged() {
        val mask = (0b010 shl 3) or (0b001 shl 6) // offset=Different, scale=Same
        val formatted = ChangedMaskDecoder.formatChangedParams(mask, "offset,scale")
        assertEquals("offset=CHANGED", formatted)
    }

    @Test
    fun forcedRecompositionDetected() {
        val mask = 0b1 // force bit set
        assertTrue(ChangedMaskDecoder.isForced(mask))
    }

    @Test
    fun multiChangedMaskDecodesWith12Params() {
        // Simulate a composable with 12 params needing $changed and $changed1
        // $changed covers params 0-9, $changed1 covers params 10-11
        val paramNames = (0 until 12).joinToString(",") { "p$it" }

        // $changed: param0=Different (bits 010 at slot 1), param9=Same (bits 001 at slot 10)
        val mask0 = (0b010 shl 3) or (0b001 shl 30)  // param0=Different, param9=Same

        // $changed1: param10=Static (bits 100 at slot 1), param11=Different (bits 010 at slot 2)
        val mask1 = (0b100 shl 3) or (0b010 shl 6)  // param10=Static, param11=Different

        val decoded = ChangedMaskDecoder.decodeMulti(listOf(mask0, mask1), paramNames)

        assertEquals(12, decoded.size)
        assertEquals("p0", decoded[0].first)
        assertEquals(ChangedMaskDecoder.ParamState.DIFFERENT, decoded[0].second)
        assertEquals("p9", decoded[9].first)
        assertEquals(ChangedMaskDecoder.ParamState.SAME, decoded[9].second)
        assertEquals("p10", decoded[10].first)
        assertEquals(ChangedMaskDecoder.ParamState.STATIC, decoded[10].second)
        assertEquals("p11", decoded[11].first)
        assertEquals(ChangedMaskDecoder.ParamState.DIFFERENT, decoded[11].second)
    }

    @Test
    fun decodeFromStringWithMultipleMasks() {
        // param0=Different in mask0, param10=Static in mask1
        val mask0 = 0b010 shl 3
        val mask1 = 0b100 shl 3
        val masksString = "$mask0,$mask1"
        val paramNames = (0 until 12).joinToString(",") { "p$it" }

        val decoded = ChangedMaskDecoder.decodeFromString(masksString, paramNames)

        assertEquals(12, decoded.size)
        assertEquals(ChangedMaskDecoder.ParamState.DIFFERENT, decoded[0].second)
        assertEquals(ChangedMaskDecoder.ParamState.STATIC, decoded[10].second)
    }

    @Test
    fun formatChangedParamsMultiShowsCorrectParams() {
        val mask0 = 0b010 shl 3  // param0=Different
        val mask1 = 0b010 shl 6  // param11=Different (slot 1 in mask1 = index 10, slot 2 = index 11)
        val paramNames = (0 until 12).joinToString(",") { "p$it" }

        val formatted = ChangedMaskDecoder.formatChangedParamsMulti(listOf(mask0, mask1), paramNames)
        assertTrue(formatted.contains("p0=CHANGED"))
        assertTrue(formatted.contains("p11=CHANGED"))
    }

    @Test
    fun onCompositionWithChangedMasksString() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        ReboundTracker.logCompositions = false

        // Call with the new changedMasks parameter
        val mask0 = 0b010 shl 3  // param0=Different
        val mask1 = 0b100 shl 3  // param10=Static
        ReboundTracker.onComposition(
            "BigComposable",
            BudgetClass.SCREEN.ordinal,
            mask0,
            (0 until 12).joinToString(",") { "p$it" },
            "$mask0,$mask1"
        )

        val snapshot = ReboundTracker.snapshot()
        assertEquals(1L, snapshot["BigComposable"]?.totalCount)
    }

    @Test
    fun isForcedFromStringDetectsForceInAnyMask() {
        // Force bit in second mask
        assertTrue(ChangedMaskDecoder.isForcedFromString("0,1"))
        assertTrue(ChangedMaskDecoder.isForcedFromString("1,0"))
        assertTrue(!ChangedMaskDecoder.isForcedFromString("0,0"))
        assertTrue(!ChangedMaskDecoder.isForcedFromString(""))
    }

    @Test
    fun decodeFromStringEmptyFallsBack() {
        val decoded = ChangedMaskDecoder.decodeFromString("", "a,b")
        assertEquals(2, decoded.size)
        assertEquals(ChangedMaskDecoder.ParamState.UNCERTAIN, decoded[0].second)
        assertEquals(ChangedMaskDecoder.ParamState.UNCERTAIN, decoded[1].second)
    }

    @Test
    fun snapshotExportAndJsonRoundTrip() {
        ReboundTracker.reset()
        ReboundTracker.onComposition("com.example.Screen", BudgetClass.SCREEN.ordinal, 0, "", "")
        ReboundTracker.onComposition("com.example.Screen", BudgetClass.SCREEN.ordinal, 0, "", "")
        ReboundTracker.onComposition("com.example.Button", BudgetClass.INTERACTIVE.ordinal, 0, "", "")

        val snapshot = ReboundTracker.exportSnapshot()
        assertEquals(2, snapshot.composables.size)
        assertEquals(2L, snapshot.composables["com.example.Screen"]?.totalCompositions)

        val json = snapshot.toJson()
        assertTrue(json.contains("com.example.Screen"))
        assertTrue(json.contains("SCREEN"))

        // Round-trip
        val parsed = ReboundSnapshot.fromJson(json)
        assertEquals(2L, parsed.composables["com.example.Screen"]?.totalCompositions)
        assertEquals("SCREEN", parsed.composables["com.example.Screen"]?.budgetClass)
    }

    @Test
    fun onEnterTracksEnterCount() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        // Simulate 5 enters but only 2 actual compositions (3 were skipped)
        repeat(5) { ReboundTracker.onEnter("TestComposable") }
        repeat(2) { ReboundTracker.onComposition("TestComposable", BudgetClass.LEAF.ordinal, 0, "") }

        val snapshot = ReboundTracker.snapshot()
        val m = snapshot["TestComposable"]!!
        assertEquals(5L, m.totalEnters)
        assertEquals(2L, m.totalCount)
        assertEquals(3L, m.skipCount)
        assertEquals(0.6f, m.skipRate, 0.001f)
    }

    @Test
    fun skipRateIsZeroWhenNeverSkipped() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        repeat(3) {
            ReboundTracker.onEnter("AlwaysRecomposed")
            ReboundTracker.onComposition("AlwaysRecomposed", BudgetClass.LEAF.ordinal, 0, "")
        }

        val m = ReboundTracker.snapshot()["AlwaysRecomposed"]!!
        assertEquals(3L, m.totalEnters)
        assertEquals(3L, m.totalCount)
        assertEquals(0L, m.skipCount)
        assertEquals(0f, m.skipRate)
    }

    @Test
    fun skipRateIsOneWhenAlwaysSkipped() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        repeat(10) { ReboundTracker.onEnter("AlwaysSkipped") }
        // No onComposition calls — body was always skipped

        val m = ReboundTracker.snapshot()["AlwaysSkipped"]!!
        assertEquals(10L, m.totalEnters)
        assertEquals(0L, m.totalCount)
        assertEquals(10L, m.skipCount)
        assertEquals(1f, m.skipRate)
    }

    @Test
    fun disabledTrackerIgnoresOnEnter() {
        ReboundTracker.reset()
        ReboundTracker.enabled = false
        ReboundTracker.onEnter("Disabled")
        assertTrue(ReboundTracker.snapshot().isEmpty())
        ReboundTracker.enabled = true
    }

    @Test
    fun snapshotExportIncludesSkipMetrics() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        repeat(4) { ReboundTracker.onEnter("com.example.SkipTest") }
        repeat(1) { ReboundTracker.onComposition("com.example.SkipTest", BudgetClass.SCREEN.ordinal, 0, "") }

        val snapshot = ReboundTracker.exportSnapshot()
        val snap = snapshot.composables["com.example.SkipTest"]!!
        assertEquals(4L, snap.totalEnters)
        assertEquals(3L, snap.skipCount)
        assertEquals(0.75f, snap.skipRate, 0.001f)

        // Round-trip JSON
        val json = snapshot.toJson()
        assertTrue(json.contains("\"totalEnters\": 4"))
        assertTrue(json.contains("\"skipCount\": 3"))
        val parsed = ReboundSnapshot.fromJson(json)
        assertEquals(4L, parsed.composables["com.example.SkipTest"]?.totalEnters)
        assertEquals(3L, parsed.composables["com.example.SkipTest"]?.skipCount)
    }

    @Test
    fun interactionDetectorAdjustsBudgets() {
        InteractionDetector.reset()

        // Simulate scrolling: LIST_ITEM with rate > 20
        InteractionDetector.updateState(BudgetClass.LIST_ITEM, 25, 1_000_000_000L)
        assertEquals(InteractionDetector.InteractionState.SCROLLING, InteractionDetector.currentState())
        assertEquals(2.0f, InteractionDetector.budgetMultiplier())

        // Simulate animation: ANIMATED with rate > 30
        InteractionDetector.updateState(BudgetClass.ANIMATED, 35, 2_000_000_000L)
        assertEquals(InteractionDetector.InteractionState.ANIMATING, InteractionDetector.currentState())
        assertEquals(1.5f, InteractionDetector.budgetMultiplier())

        // Decay to IDLE after 500ms of no interaction signals
        InteractionDetector.updateState(BudgetClass.SCREEN, 1, 2_600_000_000L)
        assertEquals(InteractionDetector.InteractionState.IDLE, InteractionDetector.currentState())
        assertEquals(1.0f, InteractionDetector.budgetMultiplier())
    }

    @Test
    fun forcedVsParamDrivenTracking() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        ReboundTracker.logCompositions = false

        // Forced recomposition (bit 0 set)
        ReboundTracker.onComposition("TestComp", BudgetClass.LEAF.ordinal, 0b1, "a,b")
        // Param-driven recomposition (param changed, not forced)
        ReboundTracker.onComposition("TestComp", BudgetClass.LEAF.ordinal, 0b010_000, "a,b")
        // Another forced
        ReboundTracker.onComposition("TestComp", BudgetClass.LEAF.ordinal, 0b1, "a,b")

        val m = ReboundTracker.snapshot()["TestComp"]!!
        assertEquals(3L, m.totalCount)
        assertEquals(2L, m.forcedRecompositionCount)
        assertEquals(1L, m.paramDrivenRecompositionCount)
    }

    @Test
    fun baselineComparisonDetectsRegressions() {
        val baseline = ReboundSnapshot(mapOf(
            "com.example.Screen" to ReboundSnapshot.ComposableSnapshot("SCREEN", 3, 100, 5, 2),
            "com.example.Button" to ReboundSnapshot.ComposableSnapshot("INTERACTIVE", 30, 50, 10, 5)
        ))
        val current = ReboundSnapshot(mapOf(
            "com.example.Screen" to ReboundSnapshot.ComposableSnapshot("SCREEN", 3, 200, 10, 8),  // 100% increase
            "com.example.Button" to ReboundSnapshot.ComposableSnapshot("INTERACTIVE", 30, 80, 11, 6)  // 10% increase
        ))

        val regressions = ReboundBaseline.compare(baseline, current, regressionThreshold = 20)
        assertEquals(1, regressions.size)
        assertEquals("com.example.Screen", regressions[0].composable)
        assertEquals(100, regressions[0].increasePercent)
    }

    @Test
    fun telemetryReportAggregatesCorrectly() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        ReboundTelemetry.enabled = true

        ReboundTracker.onComposition("com.example.ScreenA", BudgetClass.SCREEN.ordinal, 0, "")
        ReboundTracker.onComposition("com.example.ScreenB", BudgetClass.SCREEN.ordinal, 0, "")
        ReboundTracker.onComposition("com.example.Button", BudgetClass.INTERACTIVE.ordinal, 0, "")

        val report = ReboundTelemetry.generateReport()
        assertEquals(3, report.composableCount)
        assertEquals(2, report.budgetClassDistribution["SCREEN"])
        assertEquals(1, report.budgetClassDistribution["INTERACTIVE"])

        val json = ReboundTelemetry.toJson()
        assertTrue(json.contains("\"composableCount\": 3"))

        ReboundTelemetry.enabled = false
    }
}
