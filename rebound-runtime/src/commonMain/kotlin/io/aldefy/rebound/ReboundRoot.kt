package io.aldefy.rebound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Optional wrapper — NOT required. The Snapshot observer auto-installs on first composition.
 *
 * Use this only if you need explicit lifecycle control over the observer (e.g., scoping
 * tracking to a specific subtree). For most apps, just apply the Gradle plugin and go.
 */
@Composable
fun ReboundRoot(content: @Composable () -> Unit) {
    DisposableEffect(Unit) {
        StateTracker.install()
        onDispose { /* observer cleanup handled by platform */ }
    }
    content()
}
