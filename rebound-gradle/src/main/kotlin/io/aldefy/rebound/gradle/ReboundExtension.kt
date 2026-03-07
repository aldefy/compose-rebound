package io.aldefy.rebound.gradle

import org.gradle.api.provider.Property

abstract class ReboundExtension {
    abstract val enabled: Property<Boolean>
    abstract val debugOnly: Property<Boolean>

    init {
        enabled.convention(true)
        debugOnly.convention(true) // default: only instrument debug builds
    }
}
