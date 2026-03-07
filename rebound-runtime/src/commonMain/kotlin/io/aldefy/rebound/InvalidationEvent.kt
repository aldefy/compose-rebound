package io.aldefy.rebound

/**
 * Records a single State invalidation that caused a composable to recompose.
 * Captured by CompositionObserver on Android; not available on other platforms.
 */
data class InvalidationEvent(
    val composableName: String,
    val stateLabel: String,
    val timestampNs: Long
)
