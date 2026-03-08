package io.aldefy.rebound.ide.data

import io.aldefy.rebound.ide.ComposableEntry
import io.aldefy.rebound.ide.RateHistoryBuffer
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

class SessionStore(private val settings: ReboundSettings) {

    var currentEntries: Map<String, ComposableEntry> = emptyMap()
        private set

    var previousEntries: Map<String, ComposableEntry> = emptyMap()
        private set

    val rateHistory: RateHistoryBuffer = RateHistoryBuffer(settings.state.historyRetentionSeconds)

    private val events: ArrayDeque<LogEvent> = ArrayDeque()
    private val snapshots: ArrayDeque<TimestampedSnapshot> = ArrayDeque()
    private var lastSnapshotMs: Long = 0L

    private val listeners = CopyOnWriteArrayList<SessionListener>()

    var vcsContext: VcsSessionContext? = null

    var isConnected: Boolean = false
        private set

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    fun onSnapshot(entries: List<ComposableEntry>) {
        if (entries.isEmpty()) return

        // 1. Diff and emit events
        try {
            diffAndEmitEvents(entries)
        } catch (e: Exception) {
            // Don't let a bad diff crash the whole snapshot pipeline
        }

        // 2. Record rate history for every entry
        for (entry in entries) {
            rateHistory.record(entry.name, entry.rate)
        }

        // 3. Store periodic full snapshots
        val now = System.currentTimeMillis()
        val intervalMs = settings.state.snapshotIntervalSeconds * 1000L
        if (now - lastSnapshotMs >= intervalMs) {
            val entryMap = entries.associateBy { it.name }
            snapshots.add(TimestampedSnapshot(now, entryMap))
            lastSnapshotMs = now

            // Evict oldest snapshots beyond retention window
            val maxSnapshots = settings.state.historyRetentionSeconds / settings.state.snapshotIntervalSeconds
            while (snapshots.size > maxSnapshots) {
                snapshots.removeFirst()
            }
        }

        // 4. Update previous and current entries
        previousEntries = currentEntries
        currentEntries = entries.associateBy { it.name }

        // 5. Notify listeners
        for (listener in listeners) {
            listener.onSnapshot(entries)
        }
    }

    fun setConnectionState(connected: Boolean) {
        isConnected = connected
        if (!connected) {
            previousEntries = emptyMap()
        }
        for (listener in listeners) {
            listener.onConnectionStateChanged(connected)
        }
    }

    fun clear() {
        currentEntries = emptyMap()
        previousEntries = emptyMap()
        rateHistory.clear()
        events.clear()
        snapshots.clear()
        lastSnapshotMs = 0L
        vcsContext = null
    }

    fun getEvents(): List<LogEvent> = events.toList()

    fun getSnapshots(): List<TimestampedSnapshot> = snapshots.toList()

    fun toSessionData(): SessionData {
        val violations = currentEntries.values.count { it.rate > it.budget && it.budget > 0 }
        return SessionData(
            snapshots = getSnapshots(),
            events = getEvents(),
            composableCount = currentEntries.size,
            violationCount = violations,
            durationMs = if (snapshots.isNotEmpty()) System.currentTimeMillis() - snapshots.first().timestampMs else 0L,
            branch = vcsContext?.branch,
            commitHash = vcsContext?.commitHash
        )
    }

    private fun diffAndEmitEvents(entries: List<ComposableEntry>) {
        for (entry in entries) {
            val fqn = entry.name
            val prev = currentEntries[fqn]

            if (prev == null) {
                // New composable with rate > 0
                if (entry.rate > 0) {
                    emitEvent(LogEvent.Level.INFO, "${entry.simpleName} appeared at ${entry.rate}/s")
                }
            } else {
                // Rate > budget → OVER event
                if (entry.rate > entry.budget && entry.budget > 0) {
                    val paramInfo = if (entry.changedParams.isNotBlank()) " -- ${entry.changedParams}" else ""
                    emitEvent(
                        LogEvent.Level.OVER,
                        "${entry.simpleName} ${entry.rate}/s > ${entry.budgetClass} ${entry.budget}/s$paramInfo"
                    )
                }

                // Rate spike: was 0, now >= 5
                if (prev.rate == 0 && entry.rate >= 5) {
                    emitEvent(LogEvent.Level.RATE, "${entry.simpleName} 0->${entry.rate}/s")
                }

                // Rate drop: was > 0, now 0
                if (prev.rate > 0 && entry.rate == 0) {
                    emitEvent(LogEvent.Level.RATE, "${entry.simpleName} ${prev.rate}/s->0")
                }

                // State change
                if (entry.invalidationReason.contains("State") && !prev.invalidationReason.contains("State")) {
                    emitEvent(LogEvent.Level.STATE, "${entry.simpleName} -- ${entry.invalidationReason}")
                }
            }
        }
    }

    private fun emitEvent(level: LogEvent.Level, message: String) {
        val event = LogEvent(
            timestamp = LocalTime.now(),
            level = level,
            message = message
        )
        events.add(event)

        // Evict oldest if over max
        val max = settings.state.maxEventLogLines
        while (events.size > max) {
            events.removeFirst()
        }

        for (listener in listeners) {
            listener.onEvent(event)
        }
    }
}
