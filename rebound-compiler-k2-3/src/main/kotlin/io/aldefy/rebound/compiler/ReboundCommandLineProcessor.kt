@file:Suppress("DEPRECATION_ERROR")

package io.aldefy.rebound.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

val KEY_ENABLED = CompilerConfigurationKey<Boolean>("enabled")

@OptIn(ExperimentalCompilerApi::class)
class ReboundCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "io.aldefy.rebound"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = "enabled",
            valueDescription = "<true|false>",
            description = "Whether the Rebound plugin is enabled",
            required = false
        )
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            "enabled" -> configuration.put(KEY_ENABLED, value.toBoolean())
        }
    }
}
