package io.aldefy.rebound

actual fun platformInit() {
    ReboundLogger.log("Rebound", "Starting ReboundServer (socket transport)")
    ReboundServer.start()
    StateTracker.install() // Auto-install "why" tracking — no ReboundRoot wrapper needed
}

actual fun platformInstallObserver() {
    // No-op — StateTracker is now auto-installed in platformInit()
}

actual fun platformConsumeInvalidationReason(): String =
    StateTracker.consumePendingReason()
