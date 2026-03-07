package io.aldefy.rebound

actual fun platformInit() {
    // No socket server on JVM desktop — logcat only
}

actual fun platformInstallObserver() {
    // No state observer on JVM desktop
}

actual fun platformConsumeInvalidationReason(): String = ""
