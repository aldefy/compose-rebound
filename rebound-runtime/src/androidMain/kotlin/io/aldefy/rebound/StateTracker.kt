package io.aldefy.rebound

import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Tracks WHY composables recompose by observing Snapshot state changes.
 *
 * Uses Snapshot.registerApplyObserver to capture which State objects triggered
 * recomposition, then correlates with ReboundTracker.onComposition() calls.
 *
 * Chain: State X written → Snapshot applied → Scope Y invalidated → Composable Z recomposes
 *        ↑ we capture X's label here, attribute to Z in onComposition
 *
 * Android-only — other platforms get a no-op via expect/actual.
 */
object StateTracker {

    @Volatile
    private var installed = false

    private var applyHandle: ObserverHandle? = null

    /** Labels of state objects from the most recent Snapshot apply (pre-recomposition). */
    @Volatile
    private var pendingStateLabels: List<String> = emptyList()

    fun install() {
        if (installed) return
        installed = true

        applyHandle = Snapshot.registerApplyObserver { changedStates, _ ->
            // This fires when modified snapshot states are about to trigger recomposition.
            // Capture labels of what changed — these will be consumed by onComposition calls.
            pendingStateLabels = changedStates.take(5).map { state ->
                truncateLabel(state.toString())
            }
        }

        ReboundLogger.log("Rebound", "StateTracker installed (Snapshot observer for 'why' tracking)")
    }

    fun uninstall() {
        applyHandle?.dispose()
        applyHandle = null
        pendingStateLabels = emptyList()
        installed = false
    }

    /**
     * Called during recomposition (from ReboundTracker.onComposition) to get the
     * likely State that caused this recomposition.
     *
     * Returns a human-readable label, or empty string if unknown.
     * Does NOT clear pending labels — multiple composables may recompose from the same state change.
     */
    fun consumePendingReason(): String {
        val labels = pendingStateLabels
        if (labels.isEmpty()) return ""
        return labels.joinToString(", ")
    }

    private fun truncateLabel(s: String): String =
        if (s.length <= 50) s else s.take(47) + "..."
}
