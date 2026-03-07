@file:Suppress("DEPRECATION_ERROR")

package io.aldefy.rebound.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.name.FqName

class ReboundIrGenerationExtension : IrGenerationExtension {
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val composableAnnotation = FqName("androidx.compose.runtime.Composable")

        // Check if Compose compiler has already run by looking for $composer parameter
        var composableCount = 0
        var composerParamCount = 0
        for (file in moduleFragment.files) {
            for (declaration in file.declarations) {
                if (declaration is IrSimpleFunction && declaration.hasAnnotation(composableAnnotation)) {
                    composableCount++
                    if (declaration.valueParameters.any { it.name.asString() == "\$composer" }) {
                        composerParamCount++
                    }
                }
            }
        }

        val composeCompilerRan = composerParamCount > 0

        if (composableCount > 0 && !composeCompilerRan) {
            println("WARNING: Rebound detected $composableCount @Composable functions but none have \$composer parameter.")
            println("WARNING: The Compose compiler plugin may not have run yet. \$changed tracking will be unavailable.")
            println("WARNING: If using Kotlin <2.0, ensure the Compose compiler plugin is on the classpath before Rebound.")
        }

        moduleFragment.transform(ReboundIrTransformer(pluginContext, composeCompilerRan), null)
    }
}
