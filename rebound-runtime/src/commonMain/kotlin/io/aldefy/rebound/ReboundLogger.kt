package io.aldefy.rebound

expect object ReboundLogger {
    fun log(tag: String, message: String)
    fun warn(tag: String, message: String)
}
