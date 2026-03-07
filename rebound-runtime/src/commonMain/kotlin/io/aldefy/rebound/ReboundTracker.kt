package io.aldefy.rebound

object ReboundTracker {
    private val metrics = concurrentMapOf<String, ComposableMetrics>()
    private val lastViolationTime = concurrentMapOf<String, Long>()
    private val lastLogTime = concurrentMapOf<String, Long>()
    private val lastLogCount = concurrentMapOf<String, Long>()
    private const val TAG = "Rebound"
    private const val VIOLATION_THROTTLE_NS = 5_000_000_000L // 5 seconds between repeated violations
    private const val LOG_THROTTLE_NS = 1_000_000_000L // 1 second between composition logs per composable

    var enabled: Boolean = true
    var logCompositions: Boolean = false

    private var initialized = false

    // --- Deep "Why" tracking ---

    /** Scope name set by onComposition, read by platform state tracker callbacks. */
    @kotlin.concurrent.Volatile
    var currentScopeName: String = ""
        internal set

    /** Last invalidation reason per composable (state label that caused recompose). */
    private val lastInvalidationReason = concurrentMapOf<String, String>()

    /** Recent invalidation events (bounded, best-effort on native — feature is Android-primary). */
    private val invalidationEvents = concurrentMapOf<Long, InvalidationEvent>()
    @kotlin.concurrent.Volatile
    private var eventCounter: Long = 0
    private const val MAX_INVALIDATION_EVENTS = 100

    /** Called by StateTracker when a scope is invalidated by a State change. */
    fun recordInvalidation(composableName: String, stateLabel: String) {
        lastInvalidationReason[composableName] = stateLabel
        val idx = eventCounter++
        invalidationEvents[idx] = InvalidationEvent(composableName, stateLabel, currentTimeNanos())
        // Evict old entries beyond capacity
        val minKey = idx - MAX_INVALIDATION_EVENTS
        invalidationEvents.keys.filter { it <= minKey }.forEach { invalidationEvents.remove(it) }
    }

    /** Get the last invalidation reason for a composable, or empty string. */
    fun getLastInvalidationReason(composableName: String): String =
        lastInvalidationReason[composableName] ?: ""

    /** Get a copy of recent invalidation events. */
    fun recentInvalidations(): List<InvalidationEvent> =
        invalidationEvents.values.sortedBy { it.timestampNs }

    /** Called at the very top of every @Composable — even if Compose will skip the body. */
    fun onEnter(key: String) {
        if (!enabled) return
        val m = metrics.getOrPut(key) { ComposableMetrics(BudgetClass.UNKNOWN) }
        m.recordEnter()
    }

    /** Called by the compiler plugin inside the non-skip path of every @Composable function. */
    fun onComposition(
        key: String,
        budgetClassOrdinal: Int,
        changedMask: Int,
        paramNames: String,
        changedMasks: String = ""
    ) {
        if (!enabled) return
        if (!initialized) {
            initialized = true
            platformInit()
        }

        // Publish scope name for state tracking
        currentScopeName = key

        // Consume pending invalidation reason from platform state tracker
        val pendingReason = platformConsumeInvalidationReason()
        if (pendingReason.isNotEmpty()) {
            recordInvalidation(key, pendingReason)
        }

        val budgetClass = BudgetClass.entries.getOrElse(budgetClassOrdinal) { BudgetClass.UNKNOWN }
        val m = metrics.getOrPut(key) { ComposableMetrics(budgetClass) }
        if (m.budgetClass == BudgetClass.UNKNOWN && budgetClass != BudgetClass.UNKNOWN) {
            m.budgetClass = budgetClass
        }
        val now = currentTimeNanos()
        val currentRate = m.recordComposition(now, changedMask)
        InteractionDetector.updateState(budgetClass, currentRate, now)
        val budget = budgetClass.baseBudgetPerSecond

        // Use multi-mask string when available, fall back to single changedMask
        val hasMultiMasks = changedMasks.isNotEmpty()
        val isForced = if (hasMultiMasks) {
            ChangedMaskDecoder.isForcedFromString(changedMasks)
        } else {
            ChangedMaskDecoder.isForced(changedMask)
        }

        if (logCompositions) {
            val lastLog = lastLogTime[key] ?: 0L
            val elapsed = now - lastLog
            if (elapsed >= LOG_THROTTLE_NS) {
                // Compute rate from count delta since last log (reliable, no window race)
                val prevCount = lastLogCount[key] ?: 0L
                val logRate = if (elapsed > 0 && lastLog > 0) {
                    ((m.totalCount - prevCount) * 1_000_000_000L / elapsed).toInt()
                } else {
                    currentRate
                }
                lastLogTime[key] = now
                lastLogCount[key] = m.totalCount
                val changedInfo = if (paramNames.isNotEmpty() && (changedMask != 0 || hasMultiMasks)) {
                    val formatted = if (hasMultiMasks) {
                        ChangedMaskDecoder.formatChangedParamsFromString(changedMasks, paramNames)
                    } else {
                        ChangedMaskDecoder.formatChangedParams(changedMask, paramNames)
                    }
                    " | $formatted"
                } else ""
                val forcedLabel = if (isForced) " [FORCED]" else ""
                val skipPct = if (m.totalEnters > 0) (m.skipRate * 1000).toInt() / 10.0 else 0.0
                val skipInfo = if (m.totalEnters > 0) ", skip=${skipPct}%" else ""
                ReboundLogger.log(TAG, "$key composed (#${m.totalCount}, rate=$logRate/s, budget=$budget/s, class=$budgetClass$skipInfo$forcedLabel$changedInfo)")
            }
        }

        val effectiveBudget = (budget * InteractionDetector.budgetMultiplier()).toInt()
        if (currentRate > effectiveBudget) {
            val lastTime = lastViolationTime[key] ?: 0L
            if (now - lastTime > VIOLATION_THROTTLE_NS) {
                lastViolationTime[key] = now
                val changedInfo = if (paramNames.isNotEmpty() && (changedMask != 0 || hasMultiMasks)) {
                    val formatted = if (hasMultiMasks) {
                        ChangedMaskDecoder.formatChangedParamsFromString(changedMasks, paramNames)
                    } else {
                        ChangedMaskDecoder.formatChangedParams(changedMask, paramNames)
                    }
                    "\n  → params: $formatted"
                } else ""
                val forcedLabel = if (isForced) "\n  → forced recomposition (parent invalidated)" else ""
                ReboundLogger.warn(TAG, "BUDGET VIOLATION: $key rate=$currentRate/s exceeds $budgetClass budget=$effectiveBudget/s (base=$budget/s, interaction=${InteractionDetector.currentState()})$forcedLabel$changedInfo")
            }
        }
    }

    fun reset() {
        metrics.clear()
        lastViolationTime.clear()
        lastLogTime.clear()
        lastLogCount.clear()
        lastInvalidationReason.clear()
        invalidationEvents.clear()
        eventCounter = 0
        InteractionDetector.reset()
    }

    fun snapshot(): Map<String, ComposableMetrics> = metrics.toMap()

    /** Export current metrics as a snapshot for baseline comparison */
    fun exportSnapshot(): ReboundSnapshot {
        val snap = snapshot()
        return ReboundSnapshot(
            composables = snap.mapValues { (key, m) ->
                ReboundSnapshot.ComposableSnapshot(
                    budgetClass = m.budgetClass.name,
                    budgetPerSecond = m.budgetClass.baseBudgetPerSecond,
                    totalCompositions = m.totalCount,
                    peakRate = m.peakRate(),
                    currentRate = m.currentRate(),
                    totalEnters = m.totalEnters,
                    skipCount = m.skipCount,
                    skipRate = m.skipRate,
                    forcedCount = m.forcedRecompositionCount,
                    paramDrivenCount = m.paramDrivenRecompositionCount,
                    lastInvalidation = getLastInvalidationReason(key)
                )
            }
        )
    }

    /** Export as JSON string */
    fun toJson(): String = exportSnapshot().toJson()

    /** Dump a summary of top violators to the log */
    fun dumpSummary() {
        val snap = snapshot()
        if (snap.isEmpty()) {
            ReboundLogger.log(TAG, "No compositions recorded")
            return
        }
        val sorted = snap.entries
            .sortedByDescending { it.value.currentRate() }
            .take(10)

        ReboundLogger.log(TAG, "=== Rebound Summary (top 10 by rate) ===")
        sorted.forEach { (key, m) ->
            val status = if (m.currentRate() > m.budgetClass.baseBudgetPerSecond) "!!OVER!!" else "OK"
            val shortKey = key.substringAfterLast(".")
            val skipPct = (m.skipRate * 1000).toInt() / 10.0  // one decimal place without String.format
            val skipInfo = if (m.totalEnters > 0) ", skips=${m.skipCount}, skipRate=${skipPct}%" else ""
            ReboundLogger.log(TAG, "  $shortKey: ${m.currentRate()}/s (budget=${m.budgetClass.baseBudgetPerSecond}/s, peak=${m.peakRate()}/s, class=${m.budgetClass}, total=${m.totalCount}$skipInfo) [$status]")
        }
        ReboundLogger.log(TAG, "========================================")
    }
}
