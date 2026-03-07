package io.aldefy.rebound

import kotlin.time.TimeSource

private val epoch = TimeSource.Monotonic.markNow()

internal actual fun currentTimeNanos(): Long = epoch.elapsedNow().inWholeNanoseconds
