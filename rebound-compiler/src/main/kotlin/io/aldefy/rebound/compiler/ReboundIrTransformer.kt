@file:Suppress("DEPRECATION_ERROR")

package io.aldefy.rebound.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrStringConcatenationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@OptIn(UnsafeDuringIrConstructionAPI::class)
class ReboundIrTransformer(
    private val pluginContext: IrPluginContext,
    private val composeCompilerRan: Boolean = true
) : IrElementTransformerVoid() {

    private var loggedChangedWarning = false

    private val composableAnnotation = FqName("androidx.compose.runtime.Composable")

    private val trackerClassId = ClassId(
        FqName("io.aldefy.rebound"),
        Name.identifier("ReboundTracker")
    )

    // Resolve ReboundTracker.onComposition at transform time
    private val onCompositionFn: IrSimpleFunctionSymbol? by lazy {
        val trackerClass = pluginContext.referenceClass(trackerClassId)
        trackerClass?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.firstOrNull { it.name.asString() == "onComposition" }
            ?.symbol
    }

    // Resolve ReboundTracker.onEnter at transform time
    private val onEnterFn: IrSimpleFunctionSymbol? by lazy {
        val trackerClass = pluginContext.referenceClass(trackerClassId)
        trackerClass?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.firstOrNull { it.name.asString() == "onEnter" }
            ?.symbol
    }

    // Resolve ReboundTracker.onExit at transform time
    private val onExitFn: IrSimpleFunctionSymbol? by lazy {
        val trackerClass = pluginContext.referenceClass(trackerClassId)
        trackerClass?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.firstOrNull { it.name.asString() == "onExit" }
            ?.symbol
    }

    // Resolve Composer.getSkipping() property getter for skip detection
    private val skippingGetter: IrSimpleFunctionSymbol? by lazy {
        val composerClassId = ClassId(
            FqName("androidx.compose.runtime"),
            Name.identifier("Composer")
        )
        val composerClass = pluginContext.referenceClass(composerClassId)
        composerClass?.owner?.properties
            ?.firstOrNull { it.name.asString() == "skipping" }
            ?.getter?.symbol
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val function = super.visitFunction(declaration) as IrFunction

        // Only instrument @Composable functions
        if (!function.hasAnnotation(composableAnnotation)) return function

        // Skip abstract, expect, or functions without bodies
        val body = function.body as? IrBlockBody ?: return function

        // Resolve the tracker function
        val trackerFn = onCompositionFn ?: return function

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val fqName = function.kotlinFqName.asString()

        // Collect all $changed parameters ($changed, $changed1, $changed2, ...)
        // injected by the Compose compiler. Sorted by suffix index.
        val changedParams = if (composeCompilerRan) {
            function.valueParameters.filter {
                val name = it.name.asString()
                name == "\$changed" || name.matches(Regex("\\${'$'}changed\\d+"))
            }.sortedBy {
                val name = it.name.asString()
                if (name == "\$changed") 0 else name.removePrefix("\$changed").toIntOrNull() ?: 0
            }
        } else {
            if (!loggedChangedWarning) {
                println("Rebound: \$changed tracking unavailable — Compose compiler has not run. Passing changedMask=0.")
                loggedChangedWarning = true
            }
            emptyList()
        }

        val firstChangedParam = changedParams.firstOrNull()

        // Collect real parameter names (exclude Compose-injected $composer, $changed, $default, $dirty)
        val userParams = function.valueParameters.filter { param ->
            val name = param.name.asString()
            !name.startsWith("\$")
        }
        val paramNames = if (composeCompilerRan) {
            userParams.joinToString(",") { it.name.asString() }
        } else ""

        // Infer budget class from IR structure
        val budgetClassOrdinal = inferBudgetClass(function)

        val trackerClass = pluginContext.referenceClass(trackerClassId)

        val trackCall = builder.irCall(trackerFn).apply {
            // arg 0: key (String) — fully qualified function name
            putValueArgument(0, builder.irString(fqName))
            // arg 1: budgetClassOrdinal (Int)
            putValueArgument(1, builder.irInt(budgetClassOrdinal))
            // arg 2: changedMask (Int) — first $changed param for backward compat
            putValueArgument(
                2,
                if (firstChangedParam != null) builder.irGet(firstChangedParam) else builder.irInt(0)
            )
            // arg 3: paramNames (String) — comma-separated user parameter names
            putValueArgument(3, builder.irString(paramNames))
            // arg 4: changedMasks (String) — comma-separated list of all $changed int values
            // For >10-param composables, this carries $changed, $changed1, $changed2, etc.
            if (changedParams.size > 1) {
                putValueArgument(4, buildChangedMasksString(builder, changedParams))
            } else if (changedParams.size == 1) {
                // Single mask — build string from the single $changed value for consistency
                putValueArgument(4, buildChangedMasksString(builder, changedParams))
            } else {
                putValueArgument(4, builder.irString(""))
            }

            // Set the dispatch receiver to the ReboundTracker object instance
            if (trackerClass != null) {
                dispatchReceiver = builder.irGetObject(trackerClass)
            }
        }

        // --- Build onComposition guard (if !$composer.skipping) ---
        val composerParam = function.valueParameters.firstOrNull {
            it.name.asString() == "\$composer"
        }
        val skippingGetterFn = skippingGetter

        val onCompositionStatement = if (composerParam != null && skippingGetterFn != null) {
            val getSkipping = builder.irCall(skippingGetterFn).apply {
                dispatchReceiver = builder.irGet(composerParam)
            }
            val nopBlock = IrBlockImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                pluginContext.irBuiltIns.unitType, null,
                emptyList()
            )
            val compositionBlock = IrBlockImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                pluginContext.irBuiltIns.unitType, null,
                listOf(trackCall)
            )
            val constTrue = IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.booleanType, true)
            IrWhenImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                pluginContext.irBuiltIns.unitType, null,
                listOf(
                    IrBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, getSkipping, nopBlock),
                    IrElseBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, constTrue, compositionBlock)
                )
            )
        } else {
            trackCall
        }

        // --- Build onEnter call ---
        val enterFn = onEnterFn
        val exitFn = onExitFn

        val enterCall = if (enterFn != null) {
            builder.irCall(enterFn).apply {
                putValueArgument(0, builder.irString(fqName))
                if (trackerClass != null) {
                    dispatchReceiver = builder.irGetObject(trackerClass)
                }
            }
        } else null

        // --- Build onExit call for finally block ---
        val exitCall = if (exitFn != null && trackerClass != null) {
            builder.irCall(exitFn).apply {
                putValueArgument(0, builder.irString(fqName))
                dispatchReceiver = builder.irGetObject(trackerClass)
            }
        } else null

        // --- Wrap body in try-finally for onExit ---
        // Structure:
        //   onEnter(key)
        //   try {
        //       if (!$composer.skipping) { onComposition(key, ...) }
        //       ... original body ...
        //   } finally {
        //       onExit(key)
        //   }
        if (exitCall != null) {
            // Collect original body statements
            val originalStatements = body.statements.toList()
            body.statements.clear()

            // Add onEnter before the try block
            if (enterCall != null) {
                body.statements.add(enterCall)
            }

            // Build try body: onComposition guard + original statements
            val tryBodyStatements = mutableListOf<org.jetbrains.kotlin.ir.IrStatement>()
            tryBodyStatements.add(onCompositionStatement)
            tryBodyStatements.addAll(originalStatements)

            val tryBody = IrBlockImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                pluginContext.irBuiltIns.unitType, null,
                tryBodyStatements
            )

            val finallyBlock = IrBlockImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                pluginContext.irBuiltIns.unitType, null,
                listOf(exitCall)
            )

            val tryFinally = IrTryImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                pluginContext.irBuiltIns.unitType,
                tryBody,
                catches = emptyList(),
                finallyExpression = finallyBlock
            )

            body.statements.add(tryFinally)
        } else {
            // Fallback: no onExit available — inject onEnter + onComposition as before
            if (enterCall != null) {
                body.statements.add(0, enterCall)
            }
            val insertPos = if (enterCall != null) 1 else 0
            body.statements.add(insertPos, onCompositionStatement)
        }

        return function
    }

    /**
     * Build an IR expression that produces a comma-separated string of all $changed int values
     * at runtime. Uses IrStringConcatenation to convert each Int param to string.
     *
     * For a single param, produces: "" + $changed  (which yields the int as a string)
     * For multiple params: "" + $changed + "," + $changed1
     */
    private fun buildChangedMasksString(
        builder: DeclarationIrBuilder,
        changedParams: List<IrValueParameter>
    ): IrExpression {
        if (changedParams.isEmpty()) return builder.irString("")

        val arguments = mutableListOf<IrExpression>()
        changedParams.forEachIndexed { index, param ->
            if (index > 0) {
                arguments.add(builder.irString(","))
            }
            arguments.add(builder.irGet(param))
        }

        return IrStringConcatenationImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = pluginContext.irBuiltIns.stringType,
            arguments = arguments
        )
    }

    // Infer budget class from IR structure using heuristics:
    // - Name contains "Screen" or "Page" -> SCREEN (ordinal 0)
    // - Body calls LazyColumn/LazyRow/LazyGrid -> CONTAINER (ordinal 1)
    // - Body calls animate/Animate/Animation/Transition -> ANIMATED (ordinal 4)
    // - Name starts with "remember" -> LEAF (ordinal 5)
    // - No child @Composable calls -> LEAF (ordinal 5)
    // - Has child @Composable calls -> CONTAINER (ordinal 1)
    // - Default -> UNKNOWN (ordinal 6)
    private fun inferBudgetClass(function: IrFunction): Int {
        // Priority 1: Explicit @ReboundBudget annotation
        val budgetAnnotation = function.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == "io.aldefy.rebound.ReboundBudget"
        }
        if (budgetAnnotation != null) {
            val arg = budgetAnnotation.getValueArgument(0)
            if (arg is IrGetEnumValue) {
                val enumName = arg.symbol.owner.name.asString()
                return when (enumName) {
                    "SCREEN" -> 0
                    "CONTAINER" -> 1
                    "INTERACTIVE" -> 2
                    "LIST_ITEM" -> 3
                    "ANIMATED" -> 4
                    "LEAF" -> 5
                    "UNKNOWN" -> 6
                    else -> 6
                }
            }
        }

        val name = function.name.asString()
        val body = function.body as? IrBlockBody ?: return 6 // UNKNOWN

        // Name-based heuristics (high confidence)
        if (name.contains("Screen", ignoreCase = true) || name.contains("Page", ignoreCase = true)) {
            return 0 // SCREEN
        }
        if (name.startsWith("remember", ignoreCase = true)) {
            return 5 // LEAF
        }

        // Scan body for call patterns
        val callNames = collectCallNames(body)
        val hasLazy = callNames.any {
            it.contains("LazyColumn") || it.contains("LazyRow") ||
            it.contains("LazyVerticalGrid") || it.contains("LazyHorizontalGrid")
        }
        val hasAnim = callNames.any {
            it.startsWith("animate") || it.startsWith("Animate") ||
            it.contains("Animation") || it.contains("Transition")
        }
        val hasChildren = hasComposableChildren(body)

        return when {
            hasAnim -> 4       // ANIMATED
            hasLazy -> 1       // CONTAINER (parent of list items)
            !hasChildren -> 5  // LEAF
            else -> 1          // CONTAINER
        }
    }

    /** Collect all function call names in the body (shallow scan — direct calls only). */
    private fun collectCallNames(body: IrBlockBody): Set<String> {
        val names = mutableSetOf<String>()
        val composableFlags = mutableSetOf<String>()
        body.accept(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): org.jetbrains.kotlin.ir.expressions.IrExpression {
                val callee = expression.symbol.owner
                val name = callee.name.asString()
                names.add(name)
                if (callee.hasAnnotation(composableAnnotation)) {
                    composableFlags.add(name)
                }
                return super.visitCall(expression)
            }
        }, null)
        return names
    }

    private fun hasComposableChildren(body: IrBlockBody): Boolean {
        var found = false
        body.accept(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): org.jetbrains.kotlin.ir.expressions.IrExpression {
                if (expression.symbol.owner.hasAnnotation(composableAnnotation)) {
                    found = true
                }
                return super.visitCall(expression)
            }
        }, null)
        return found
    }
}
