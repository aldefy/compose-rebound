package io.aldefy.rebound.ide

/**
 * Tree node representing a composable in the composition hierarchy.
 * Root node has empty fqn and null entry.
 */
class ComposableTreeNode(
    val fqn: String,
    val simpleName: String,
    val entry: ComposableEntry? = null,
    val children: MutableList<ComposableTreeNode> = mutableListOf()
) {
    override fun toString(): String = simpleName
}
