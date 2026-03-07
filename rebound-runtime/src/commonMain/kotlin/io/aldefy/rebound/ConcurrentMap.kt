package io.aldefy.rebound

/**
 * Thread-safe mutable map. Uses ConcurrentHashMap on JVM/Android,
 * synchronized wrapper elsewhere.
 */
internal expect fun <K, V> concurrentMapOf(): MutableMap<K, V>
