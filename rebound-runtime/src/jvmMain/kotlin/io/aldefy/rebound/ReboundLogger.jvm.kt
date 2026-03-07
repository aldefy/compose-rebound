package io.aldefy.rebound

actual object ReboundLogger {
    actual fun log(tag: String, message: String) { println("[$tag] $message") }
    actual fun warn(tag: String, message: String) { System.err.println("[$tag] WARN: $message") }
}
