package io.aldefy.rebound.ide.data

import io.aldefy.rebound.ide.ComposableEntry

interface SessionListener {
    fun onSnapshot(entries: List<ComposableEntry>) {}
    fun onEvent(event: LogEvent) {}
    fun onConnectionStateChanged(connected: Boolean) {}
}
