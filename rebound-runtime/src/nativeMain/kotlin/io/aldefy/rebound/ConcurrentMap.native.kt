package io.aldefy.rebound

// Kotlin/Native new memory model (default since 1.7.20) allows shared mutable state.
// For dev-time tooling, a regular map is acceptable.
internal actual fun <K, V> concurrentMapOf(): MutableMap<K, V> = mutableMapOf()
