package io.aldefy.rebound.ide.tabs

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import io.aldefy.rebound.ide.ComposableEntry
import io.aldefy.rebound.ide.ComposableTreeNode
import io.aldefy.rebound.ide.EventLogPanel
import io.aldefy.rebound.ide.SparklinePanel
import io.aldefy.rebound.ide.TreeBuilder
import io.aldefy.rebound.ide.data.LogEvent
import io.aldefy.rebound.ide.data.SessionListener
import io.aldefy.rebound.ide.data.SessionStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class MonitorTab(private val sessionStore: SessionStore) : JPanel(BorderLayout()), SessionListener {

    private val treeRoot = DefaultMutableTreeNode(ComposableTreeNode("", "App"))
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)
    private val detailPanel = DetailPanel()
    private val sparkline = SparklinePanel()
    private val eventLog = EventLogPanel()

    init {
        setupTree()

        // Detail + sparkline in a vertical split
        val detailWithSparkline = JPanel(BorderLayout()).apply {
            add(JBScrollPane(detailPanel), BorderLayout.CENTER)
            add(sparkline, BorderLayout.SOUTH)
        }

        // Tree | Detail+Sparkline (horizontal)
        val innerSplitter = JBSplitter(false, 0.55f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = detailWithSparkline
        }

        // (Tree+Detail) / EventLog (vertical, 70/30)
        val outerSplitter = JBSplitter(true, 0.70f).apply {
            firstComponent = innerSplitter
            secondComponent = eventLog
        }

        add(outerSplitter, BorderLayout.CENTER)

        sessionStore.addListener(this)
    }

    private fun setupTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ReboundTreeCellRenderer()

        tree.addTreeSelectionListener { e ->
            val node = (e.path?.lastPathComponent as? DefaultMutableTreeNode)
                ?.userObject as? ComposableTreeNode
            detailPanel.showEntry(node?.entry)
            // Update sparkline for selected composable
            if (node?.entry != null) {
                val samples = sessionStore.rateHistory.getSamples(node.fqn)
                sparkline.update(samples, node.entry.budget)
            } else {
                sparkline.clear()
            }
        }
    }

    // --- SessionListener ---

    override fun onSnapshot(entries: List<ComposableEntry>) {
        updateTree(entries)
    }

    override fun onEvent(event: LogEvent) {
        val level = when (event.level) {
            LogEvent.Level.OVER -> EventLogPanel.Level.OVER
            LogEvent.Level.WARN -> EventLogPanel.Level.WARN
            LogEvent.Level.STATE -> EventLogPanel.Level.STATE
            LogEvent.Level.RATE -> EventLogPanel.Level.RATE
            LogEvent.Level.INFO -> EventLogPanel.Level.INFO
        }
        eventLog.append(level, event.message)
    }

    // --- Tree update logic ---

    private fun updateTree(entries: List<ComposableEntry>) {
        // Save expanded paths
        val expandedFqns = mutableSetOf<String>()
        val expandedEnum = tree.getExpandedDescendants(TreePath(treeRoot))
        if (expandedEnum != null) {
            for (path in expandedEnum) {
                val node = (path.lastPathComponent as? DefaultMutableTreeNode)
                    ?.userObject as? ComposableTreeNode
                if (node != null) expandedFqns.add(node.fqn)
            }
        }

        // Save selected FQN
        val selectedFqn = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            ?.let { (it.userObject as? ComposableTreeNode)?.fqn }

        // Build new tree
        val logicalTree = TreeBuilder.buildTree(entries)
        treeRoot.removeAllChildren()
        buildSwingTree(treeRoot, logicalTree)
        treeModel.reload()

        // Restore expanded paths
        restoreExpansion(treeRoot, expandedFqns)

        // Restore selection and update sparkline
        if (selectedFqn != null) {
            val selectedNode = findNode(treeRoot, selectedFqn)
            if (selectedNode != null) {
                val path = TreePath(treeModel.getPathToRoot(selectedNode))
                tree.selectionPath = path
                // Update sparkline for the selected composable
                val entry = (selectedNode.userObject as? ComposableTreeNode)?.entry
                if (entry != null) {
                    sparkline.update(sessionStore.rateHistory.getSamples(selectedFqn), entry.budget)
                }
            }
        }

        // Auto-expand root children on first update
        if (expandedFqns.isEmpty()) {
            for (i in 0 until treeRoot.childCount) {
                val child = treeRoot.getChildAt(i) as DefaultMutableTreeNode
                tree.expandPath(TreePath(treeModel.getPathToRoot(child)))
            }
        }
    }

    private fun buildSwingTree(parent: DefaultMutableTreeNode, logicalNode: ComposableTreeNode) {
        for (child in logicalNode.children) {
            val swingNode = DefaultMutableTreeNode(child)
            parent.add(swingNode)
            buildSwingTree(swingNode, child)
        }
    }

    private fun restoreExpansion(node: DefaultMutableTreeNode, expandedFqns: Set<String>) {
        val treeNode = node.userObject as? ComposableTreeNode
        if (treeNode != null && treeNode.fqn in expandedFqns) {
            tree.expandPath(TreePath(treeModel.getPathToRoot(node)))
        }
        for (i in 0 until node.childCount) {
            restoreExpansion(node.getChildAt(i) as DefaultMutableTreeNode, expandedFqns)
        }
    }

    private fun findNode(node: DefaultMutableTreeNode, fqn: String): DefaultMutableTreeNode? {
        val treeNode = node.userObject as? ComposableTreeNode
        if (treeNode?.fqn == fqn) return node
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChildAt(i) as DefaultMutableTreeNode, fqn)
            if (found != null) return found
        }
        return null
    }

    fun clearUI() {
        treeRoot.removeAllChildren()
        treeModel.reload()
        detailPanel.showEntry(null)
        eventLog.clear()
        sparkline.clear()
    }

    fun dispose() {
        sessionStore.removeListener(this)
    }

    // --- Tree cell renderer ---

    private class ReboundTreeCellRenderer : DefaultTreeCellRenderer() {

        private val okColor = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
        private val warnColor = JBColor(java.awt.Color(200, 150, 0), java.awt.Color(230, 180, 50))

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

            val node = (value as? DefaultMutableTreeNode)?.userObject as? ComposableTreeNode
                ?: return this
            val entry = node.entry

            if (entry != null) {
                val rateText = "${entry.rate}/s"
                text = "${entry.simpleName}  $rateText"
                toolTipText = entry.name

                val isOver = entry.isViolation && entry.rate > entry.budget
                val isNearBudget = !isOver && entry.budget > 0 && entry.rate.toDouble() / entry.budget >= 0.7

                if (!selected) {
                    when {
                        isOver -> {
                            foreground = JBColor.RED
                            font = font.deriveFont(Font.BOLD)
                        }
                        isNearBudget -> {
                            foreground = warnColor
                            font = font.deriveFont(Font.PLAIN)
                        }
                        else -> {
                            foreground = tree.foreground
                            font = font.deriveFont(Font.PLAIN)
                        }
                    }
                }

                icon = when {
                    isOver -> AllIcons.General.BalloonError
                    isNearBudget -> AllIcons.General.BalloonWarning
                    entry.rate > 0 -> AllIcons.General.BalloonInformation
                    else -> AllIcons.Nodes.EmptyNode
                }
            } else {
                text = node.simpleName
                icon = AllIcons.Nodes.Folder
                if (!selected) {
                    foreground = tree.foreground
                    font = font.deriveFont(Font.PLAIN)
                }
            }

            return this
        }
    }

    // --- Detail panel ---

    private class DetailPanel : JPanel(GridBagLayout()) {

        private val fqnLabel = JLabel("\u2014")
        private val rateLabel = JLabel("\u2014")
        private val budgetLabel = JLabel("\u2014")
        private val classLabel = JLabel("\u2014")
        private val reasonLabel = JLabel("\u2014")
        private val statusLabel = JLabel("\u2014")
        private val skipLabel = JLabel("\u2014")
        private val peakLabel = JLabel("\u2014")
        private val totalLabel = JLabel("\u2014")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(2, 4, 2, 4)
            }

            var row = 0
            fun addRow(label: String, value: JLabel, tooltip: String? = null) {
                gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                val labelComponent = JLabel("$label:").apply {
                    font = font.deriveFont(Font.BOLD)
                    if (tooltip != null) toolTipText = tooltip
                }
                add(labelComponent, gbc)
                gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
                if (tooltip != null) value.toolTipText = tooltip
                add(value, gbc)
                row++
            }

            addRow("FQN", fqnLabel, "Fully qualified name of the composable function")
            addRow("Rate", rateLabel, "Current recompositions per second")
            addRow("Budget", budgetLabel, "Max allowed recompositions per second for this class")
            addRow("Class", classLabel, "Budget class: SCREEN, CONTAINER, INTERACTIVE, LIST_ITEM, ANIMATED, or LEAF")
            addRow("Status", statusLabel, "OK if rate is within budget, Nx OVER if exceeding")
            addRow("Reason", reasonLabel, "What triggered this recomposition: changed params, forced by parent, or state mutation")
            addRow("Skip %", skipLabel, "Percentage of recomposition attempts that were skipped (higher = more efficient)")
            addRow("Peak", peakLabel, "Highest recomposition rate observed during this session")
            addRow("Total", totalLabel, "Total number of recompositions since monitoring started")

            // Filler to push content to top
            gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
            add(JPanel(), gbc)
        }

        fun showEntry(entry: ComposableEntry?) {
            if (entry == null) {
                fqnLabel.text = "\u2014"
                rateLabel.text = "\u2014"
                budgetLabel.text = "\u2014"
                classLabel.text = "\u2014"
                reasonLabel.text = "\u2014"
                statusLabel.text = "\u2014"
                skipLabel.text = "\u2014"
                peakLabel.text = "\u2014"
                totalLabel.text = "\u2014"
                return
            }

            fqnLabel.text = entry.name
            rateLabel.text = "${entry.rate}/s"
            budgetLabel.text = "${entry.budget}/s"
            classLabel.text = entry.budgetClass
            reasonLabel.text = entry.reason
            totalLabel.text = entry.totalCount.toString()
            peakLabel.text = "${entry.peakRate}/s"
            skipLabel.text = if (entry.skipPercent >= 0) "${entry.skipPercent}%" else "\u2014"

            val isOver = entry.isViolation && entry.rate > entry.budget
            statusLabel.text = entry.status
            statusLabel.foreground = if (isOver) JBColor.RED else
                JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
            rateLabel.foreground = if (isOver) JBColor.RED else foreground

            reasonLabel.foreground = when {
                entry.reason.contains("FORCED") -> JBColor.ORANGE
                entry.reason.contains("State") -> JBColor(
                    java.awt.Color(0, 128, 128), java.awt.Color(80, 200, 200)
                )
                else -> foreground
            }
        }
    }
}
