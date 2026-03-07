package io.aldefy.rebound

/**
 * Override the inferred budget class for a @Composable function.
 * Use when the IR heuristic misclassifies your composable.
 *
 * Example:
 * ```
 * @ReboundBudget(BudgetClass.ANIMATED)
 * @Composable
 * fun PhysicsSticker(offset: Offset) { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ReboundBudget(val budgetClass: BudgetClass)
