package io.aldefy.rebound

actual fun platformInit() {
    // No socket server on native — logcat only
}

actual fun platformInstallObserver() {
    // No state observer on native
}

actual fun platformConsumeInvalidationReason(): String = ""
