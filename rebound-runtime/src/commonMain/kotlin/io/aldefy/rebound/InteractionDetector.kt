package io.aldefy.rebound

import kotlin.concurrent.Volatile

/**
 * Detects interaction context (scroll, animation, user input) from runtime
 * recomposition patterns and provides a dynamic budget multiplier.
 *
 * During active interactions (scrolling, animation), composables naturally
 * recompose more frequently. The multiplier relaxes budgets to avoid
 * false-positive violations during expected high-activity periods.
 */
object InteractionDetector {
    enum class InteractionState {
        IDLE,
        SCROLLING,
        ANIMATING,
        USER_INPUT
    }

    @Volatile
    private var currentState = InteractionState.IDLE
    private var stateStartTimeNs: Long = 0
    private const val DECAY_NS = 500_000_000L // 500ms before returning to IDLE

    /**
     * Called from ReboundTracker.onComposition to update interaction state
     * based on observed recomposition patterns.
     */
    fun updateState(budgetClass: BudgetClass, currentRate: Int, timeNs: Long) {
        val newState = when {
            budgetClass == BudgetClass.LIST_ITEM && currentRate > 20 -> InteractionState.SCROLLING
            budgetClass == BudgetClass.ANIMATED && currentRate > 30 -> InteractionState.ANIMATING
            budgetClass == BudgetClass.INTERACTIVE && currentRate > 10 -> InteractionState.USER_INPUT
            else -> {
                if (timeNs - stateStartTimeNs > DECAY_NS) InteractionState.IDLE
                else currentState
            }
        }
        if (newState != currentState) {
            currentState = newState
            stateStartTimeNs = timeNs
        }
    }

    /** Budget multiplier based on current interaction state. */
    fun budgetMultiplier(): Float = when (currentState) {
        InteractionState.IDLE -> 1.0f
        InteractionState.SCROLLING -> 2.0f
        InteractionState.ANIMATING -> 1.5f
        InteractionState.USER_INPUT -> 1.5f
    }

    fun currentState(): InteractionState = currentState

    fun reset() {
        currentState = InteractionState.IDLE
        stateStartTimeNs = 0
    }
}
