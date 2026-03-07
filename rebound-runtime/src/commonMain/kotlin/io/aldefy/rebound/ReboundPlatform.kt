package io.aldefy.rebound

/** Platform-specific initialization (starts socket server on Android). */
expect fun platformInit()

/** Install platform-specific state observer for "why" tracking. Android: Snapshot observer. Others: no-op. */
expect fun platformInstallObserver()

/** Consume pending invalidation reason from platform state tracker. Returns empty string on non-Android. */
expect fun platformConsumeInvalidationReason(): String
