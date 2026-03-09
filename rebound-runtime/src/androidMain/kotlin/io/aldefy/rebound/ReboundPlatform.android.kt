package io.aldefy.rebound

actual fun platformInit() {
    ReboundLogger.log("Rebound", "Starting ReboundServer (socket transport)")
    ReboundServer.start()
}
