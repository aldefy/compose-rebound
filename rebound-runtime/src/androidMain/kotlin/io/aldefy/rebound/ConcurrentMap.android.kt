package io.aldefy.rebound

import java.util.concurrent.ConcurrentHashMap

internal actual fun <K, V> concurrentMapOf(): MutableMap<K, V> = ConcurrentHashMap()
