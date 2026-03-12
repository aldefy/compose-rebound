package io.aldefy.rebound

import platform.Foundation.NSLog

actual object ReboundLogger {
    actual fun log(tag: String, message: String) { NSLog("[$tag] $message") }
    actual fun warn(tag: String, message: String) { NSLog("[$tag] WARN: $message") }
}
