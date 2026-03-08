package io.aldefy.rebound.ide.data

import java.time.LocalTime

data class LogEvent(
    val timestamp: LocalTime,
    val level: Level,
    val message: String
) {
    enum class Level { OVER, WARN, STATE, RATE, INFO }
}
