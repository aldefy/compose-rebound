package io.aldefy.rebound

import android.util.Log

actual object ReboundLogger {
    actual fun log(tag: String, message: String) {
        try { Log.d(tag, message) } catch (_: Throwable) {}
    }
    actual fun warn(tag: String, message: String) {
        try { Log.w(tag, message) } catch (_: Throwable) {}
    }
}
