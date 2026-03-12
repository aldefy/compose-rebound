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
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrWhen
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
    private val lambdaCounters = mutableMapOf<String, Int>()

    /** Compose runtime internal calls to skip when scanning for the primary composable call. */
    private val composeInternalCalls = setOf(
        "startRestartGroup", "endRestartGroup",
        "startReplaceableGroup", "endReplaceableGroup",
        "startMovableGroup", "endMovableGroup",
        "startNode", "endNode",
        "skipToGroupEnd", "skipCurrentGroup",
        "sourceInformation", "sourceInformationMarkerStart", "sourceInformationMarkerEnd",
        "isTraceInProgress", "traceEventStart", "traceEventEnd",
        "joinKey", "changed", "updateChangedFlags"
    )

    private val composableAnnotation = FqName("androidx.compose.runtime.Composable")
    private val stableFqName = FqName("androidx.compose.runtime.Stable")
    private val immutableFqName = FqName("androidx.compose.runtime.Immutable")

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
        val fqName = resolveComposableKey(function)

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
        // paramNames and paramTypes don't depend on Compose's $composer/$changed injection —
        // they read the original user-declared parameters which are always present regardless
        // of whether the Compose compiler plugin ran before or after Rebound.
        val paramNames = userParams.joinToString(",") { it.name.asString() }

        // Build paramTypes classification string
        val paramTypes = if (userParams.isNotEmpty()) {
            userParams.joinToString(",") { classifyParamType(it) }
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
            // arg 5: paramTypes (String) — compile-time type classifications
            putValueArgument(5, builder.irString(paramTypes))

            // Set the dispatch receiver to the ReboundTracker object instance
            if (trackerClass != null) {
                dispatchReceiver = builder.irGetObject(trackerClass)
            }
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

        // --- Inject onComposition inside Compose's non-skip branch ---
        // The Compose compiler generates a skip check like:
        //   if ($dirty and mask != expected || !$composer.skipping) {
        //       ... body ...    // NON-SKIP
        //   } else {
        //       $composer.skipToGroupEnd()  // SKIP
        //   }
        // We inject onComposition as the first statement of the non-skip branch.
        // This is the ONLY correct placement because $composer.skipping can be
        // true even when the body executes (parent passes changed params).
        val injectedInNonSkipBranch = injectInComposeNonSkipBranch(body, trackCall)

        // --- Wrap body in try-finally for onExit ---
        // Structure:
        //   onEnter(key)
        //   try {
        //       ... original body (with onComposition in non-skip branch) ...
        //   } finally {
        //       onExit(key)
        //   }
        if (exitCall != null) {
            val originalStatements = body.statements.toList()
            body.statements.clear()

            // Add onEnter before the try block
            if (enterCall != null) {
                body.statements.add(enterCall)
            }

            // If we couldn't find Compose's non-skip branch, prepend onComposition
            val tryBodyStatements = mutableListOf<IrStatement>()
            if (!injectedInNonSkipBranch) {
                tryBodyStatements.add(trackCall)
            }
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
            // Fallback: no onExit available — inject onEnter + onComposition
            if (enterCall != null) {
                body.statements.add(0, enterCall)
            }
            if (!injectedInNonSkipBranch) {
                val insertPos = if (enterCall != null) 1 else 0
                body.statements.add(insertPos, trackCall)
            }
        }

        return function
    }

    /**
     * Find Compose's skip-check `when` expression in the IR body and inject
     * onComposition as the first statement of the non-skip branch.
     *
     * The Compose compiler generates a `when` (if/else) where one branch calls
     * `skipToGroupEnd()` (the skip path) and the other contains the actual body
     * (the non-skip path). We inject into the non-skip branch.
     *
     * Returns true if injection was successful.
     */
    private fun injectInComposeNonSkipBranch(
        body: IrBlockBody,
        onCompositionCall: IrStatement
    ): Boolean {
        // Walk all statements recursively to find the when expression with skipToGroupEnd
        for (statement in body.statements) {
            if (injectInWhenRecursive(statement, onCompositionCall)) {
                return true
            }
        }
        return false
    }

    /**
     * Recursively search for a `when` expression that contains a branch calling
     * `skipToGroupEnd()` and inject onComposition in the OTHER branch.
     */
    private fun injectInWhenRecursive(
        element: org.jetbrains.kotlin.ir.IrElement,
        onCompositionCall: IrStatement
    ): Boolean {
        if (element is IrWhen) {
            // Check if any branch contains skipToGroupEnd
            var skipBranchIndex = -1
            for (i in element.branches.indices) {
                if (containsCall(element.branches[i].result, "skipToGroupEnd")) {
                    skipBranchIndex = i
                    break
                }
            }
            if (skipBranchIndex >= 0) {
                // Found the skip check! Inject onComposition in the non-skip branch.
                for (i in element.branches.indices) {
                    if (i != skipBranchIndex) {
                        val nonSkipBranch = element.branches[i]
                        prependToBlock(nonSkipBranch, onCompositionCall)
                        return true
                    }
                }
            }
        }

        // Recurse into child elements
        when (element) {
            is IrBlock -> {
                for (stmt in element.statements) {
                    if (injectInWhenRecursive(stmt, onCompositionCall)) return true
                }
            }
            is IrWhen -> {
                for (branch in element.branches) {
                    if (injectInWhenRecursive(branch.result, onCompositionCall)) return true
                }
            }
        }
        return false
    }

    /** Prepend a statement to the beginning of a branch's result block. */
    private fun prependToBlock(branch: IrBranch, statement: IrStatement) {
        val result = branch.result
        if (result is IrBlock) {
            (result.statements as MutableList<IrStatement>).add(0, statement)
        } else {
            // Wrap in a block
            branch.result = IrBlockImpl(
                result.startOffset, result.endOffset,
                result.type, null,
                listOf(statement, result)
            )
        }
    }

    /** Check if an IR element contains a call to a function with the given name. */
    private fun containsCall(element: org.jetbrains.kotlin.ir.IrElement, functionName: String): Boolean {
        var found = false
        element.accept(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                if (expression.symbol.owner.name.asString() == functionName) {
                    found = true
                }
                return super.visitCall(expression)
            }
        }, null)
        return found
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

    /**
     * Build a human-readable key for composable functions.
     * Named functions → FQN as-is.
     * Anonymous lambdas → named after the primary composable call inside them,
     * e.g. "com.example.HomeScreen.Scaffold{}" instead of "<anonymous>".
     */
    private fun resolveComposableKey(function: IrFunction): String {
        val raw = function.kotlinFqName.asString()
        if (!raw.contains("<anonymous>")) return raw

        val pkg = extractPackage(raw)
        val parentName = findEnclosingName(function)

        // Try to name after the first user-visible composable call in the body
        val primaryCall = findPrimaryComposableCall(function)
        if (primaryCall != null) {
            return "$pkg$parentName.$primaryCall{}"
        }

        // Fallback: counter-based λN
        val counterKey = "$pkg$parentName"
        val idx = lambdaCounters.getOrPut(counterKey) { 0 } + 1
        lambdaCounters[counterKey] = idx
        return "$pkg$parentName.λ$idx"
    }

    /**
     * Scan the function body for the first composable call that isn't a
     * Compose runtime internal (startRestartGroup, skipToGroupEnd, etc.).
     */
    private fun findPrimaryComposableCall(function: IrFunction): String? {
        val body = function.body as? IrBlockBody ?: return null
        var firstCall: String? = null
        body.accept(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                if (firstCall == null) {
                    val callee = expression.symbol.owner
                    val name = callee.name.asString()
                    if (callee.hasAnnotation(composableAnnotation) &&
                        !name.startsWith("\$") &&
                        !name.startsWith("<get-") &&
                        !name.startsWith("<set-") &&
                        name !in composeInternalCalls
                    ) {
                        firstCall = name
                    }
                }
                return super.visitCall(expression)
            }
        }, null)
        return firstCall
    }

    private fun findEnclosingName(function: IrFunction): String {
        var current: IrDeclarationParent? = (function as? IrDeclaration)?.parent
        while (current != null) {
            when (current) {
                is IrFunction -> {
                    val name = current.name.asString()
                    if (name != "<anonymous>" && !name.startsWith("lambda-")) {
                        return name
                    }
                    current = (current as? IrDeclaration)?.parent
                }
                is IrClass -> {
                    val name = current.name.asString()
                    if (!name.startsWith("ComposableSingletons")) {
                        return name
                    }
                    current = (current as? IrDeclaration)?.parent
                }
                else -> break
            }
        }
        return "lambda"
    }

    private fun extractPackage(fqName: String): String {
        val parts = fqName.split(".")
        val classIdx = parts.indexOfFirst { it[0].isUpperCase() || it.contains("$") || it == "<anonymous>" }
        return if (classIdx > 0) parts.subList(0, classIdx).joinToString(".") + "." else ""
    }

    /**
     * Classify a parameter's type for Strong Skipping Mode awareness.
     * Returns "lambda", "stable", or "unstable".
     */
    private fun classifyParamType(param: IrValueParameter): String {
        val type = param.type
        val classFqn = type.classFqName?.asString() ?: ""

        // Lambda: FunctionN, KFunctionN, or @Composable function type
        if (classFqn.startsWith("kotlin.Function") ||
            classFqn.startsWith("kotlin.reflect.KFunction") ||
            type.hasAnnotation(composableAnnotation)
        ) {
            return "lambda"
        }

        // Stable: primitives, String, Unit, enums, @Stable/@Immutable annotated
        val primitives = setOf(
            "kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double",
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Char",
            "kotlin.String", "kotlin.Unit"
        )
        if (classFqn in primitives) return "stable"

        // Check if the class itself has @Stable or @Immutable
        val classSymbol = type.classFqName?.let {
            pluginContext.referenceClass(ClassId.topLevel(FqName(classFqn)))
        }
        if (classSymbol != null) {
            val classDecl = classSymbol.owner
            if (classDecl.hasAnnotation(stableFqName) ||
                classDecl.hasAnnotation(immutableFqName)
            ) {
                return "stable"
            }
            // Enum classes are stable
            if (classDecl.kind == org.jetbrains.kotlin.descriptors.ClassKind.ENUM_CLASS) {
                return "stable"
            }
        }

        return "unstable"
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
            override fun visitCall(expression: IrCall): IrExpression {
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
            override fun visitCall(expression: IrCall): IrExpression {
                if (expression.symbol.owner.hasAnnotation(composableAnnotation)) {
                    found = true
                }
                return super.visitCall(expression)
            }
        }, null)
        return found
    }
}
