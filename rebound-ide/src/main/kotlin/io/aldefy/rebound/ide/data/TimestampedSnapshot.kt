package io.aldefy.rebound.ide.data

import io.aldefy.rebound.ide.ComposableEntry

data class TimestampedSnapshot(
    val timestampMs: Long,
    val entries: Map<String, ComposableEntry>
)
