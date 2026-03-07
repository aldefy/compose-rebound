package io.aldefy.rebound

import android.util.Log

actual object ReboundLogger {
    actual fun log(tag: String, message: String) { Log.d(tag, message) }
    actual fun warn(tag: String, message: String) { Log.w(tag, message) }
}
