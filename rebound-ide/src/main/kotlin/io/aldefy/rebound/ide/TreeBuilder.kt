package io.aldefy.rebound.ide

/**
 * Builds a composition hierarchy tree from flat ComposableEntry list.
 * Uses parent/depth fields populated by the runtime's call-stack tracking.
 */
object TreeBuilder {

    fun buildTree(entries: List<ComposableEntry>): ComposableTreeNode {
        val root = ComposableTreeNode("", "App")
        if (entries.isEmpty()) return root

        val nodeMap = mutableMapOf<String, ComposableTreeNode>()

        // Sort by depth so parents are created before children
        val sorted = entries.sortedBy { it.depth }

        for (entry in sorted) {
            val node = ComposableTreeNode(entry.name, entry.simpleName, entry)
            nodeMap[entry.name] = node

            val parentNode = if (entry.parentFqn.isNotEmpty()) {
                nodeMap[entry.parentFqn] ?: root
            } else {
                root
            }
            parentNode.children.add(node)
        }
        return root
    }
}
