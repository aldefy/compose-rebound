package io.aldefy.rebound.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
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

class ReboundPanel : JPanel(BorderLayout()) {

    private val treeRoot = DefaultMutableTreeNode(ComposableTreeNode("", "App"))
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)
    private val detailPanel = DetailPanel()
    private val statusLabel = JLabel("Stopped")
    private var connection: ReboundConnection? = null

    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop")
    private val clearButton = JButton("Clear")

    init {
        setupTree()
        setupToolbar()

        val splitter = JBSplitter(false, 0.55f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = JBScrollPane(detailPanel)
        }
        add(splitter, BorderLayout.CENTER)
    }

    private fun setupTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ReboundTreeCellRenderer()

        tree.addTreeSelectionListener { e ->
            val node = (e.path?.lastPathComponent as? DefaultMutableTreeNode)
                ?.userObject as? ComposableTreeNode
            detailPanel.showEntry(node?.entry)
        }
    }

    private fun setupToolbar() {
        stopButton.isEnabled = false

        startButton.addActionListener { startCapture() }
        stopButton.addActionListener { stopCapture() }
        clearButton.addActionListener { clearTree() }

        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(startButton)
            add(Box.createHorizontalStrut(4))
            add(stopButton)
            add(Box.createHorizontalStrut(4))
            add(clearButton)
            add(Box.createHorizontalGlue())
            add(statusLabel)
            add(Box.createHorizontalStrut(8))
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }

        add(toolbar, BorderLayout.NORTH)
    }

    private fun startCapture() {
        if (connection?.isRunning == true) return

        connection = ReboundConnection(
            onUpdate = { entries ->
                ApplicationManager.getApplication().invokeLater {
                    updateTree(entries)
                    statusLabel.text = "Live (${entries.size} composables)"
                    statusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
                }
            },
            onError = { message ->
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = message
                    statusLabel.foreground = JBColor.ORANGE
                }
            }
        )
        connection?.start()

        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusLabel.text = "Capturing..."
        statusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
    }

    private fun stopCapture() {
        connection?.stop()
        connection = null

        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusLabel.text = "Stopped"
        statusLabel.foreground = JBColor.GRAY
    }

    private fun clearTree() {
        treeRoot.removeAllChildren()
        treeModel.reload()
        detailPanel.showEntry(null)
    }

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

        // Restore selection
        if (selectedFqn != null) {
            val selectedNode = findNode(treeRoot, selectedFqn)
            if (selectedNode != null) {
                val path = TreePath(treeModel.getPathToRoot(selectedNode))
                tree.selectionPath = path
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

    fun dispose() {
        connection?.stop()
    }

    // --- Tree cell renderer ---

    private class ReboundTreeCellRenderer : DefaultTreeCellRenderer() {

        private val greenColor = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))

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

                if (!selected) {
                    if (entry.isViolation && entry.rate > entry.budget) {
                        foreground = JBColor.RED
                        font = font.deriveFont(Font.BOLD)
                    } else {
                        foreground = tree.foreground
                        font = font.deriveFont(Font.PLAIN)
                    }
                }

                icon = if (entry.isViolation && entry.rate > entry.budget) {
                    UIManager.getIcon("OptionPane.errorIcon")
                } else {
                    UIManager.getIcon("OptionPane.informationIcon")
                }
            } else {
                text = node.simpleName
                icon = UIManager.getIcon("Tree.closedIcon")
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

        private val fqnLabel = JLabel("—")
        private val rateLabel = JLabel("—")
        private val budgetLabel = JLabel("—")
        private val classLabel = JLabel("—")
        private val reasonLabel = JLabel("—")
        private val statusLabel = JLabel("—")
        private val skipLabel = JLabel("—")
        private val peakLabel = JLabel("—")
        private val totalLabel = JLabel("—")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(2, 4, 2, 4)
            }

            var row = 0
            fun addRow(label: String, value: JLabel) {
                gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                add(JLabel("$label:").apply { font = font.deriveFont(Font.BOLD) }, gbc)
                gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
                add(value, gbc)
                row++
            }

            addRow("FQN", fqnLabel)
            addRow("Rate", rateLabel)
            addRow("Budget", budgetLabel)
            addRow("Class", classLabel)
            addRow("Status", statusLabel)
            addRow("Reason", reasonLabel)
            addRow("Skip %", skipLabel)
            addRow("Peak", peakLabel)
            addRow("Total", totalLabel)

            // Filler to push content to top
            gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
            add(JPanel(), gbc)
        }

        fun showEntry(entry: ComposableEntry?) {
            if (entry == null) {
                fqnLabel.text = "—"
                rateLabel.text = "—"
                budgetLabel.text = "—"
                classLabel.text = "—"
                reasonLabel.text = "—"
                statusLabel.text = "—"
                skipLabel.text = "—"
                peakLabel.text = "—"
                totalLabel.text = "—"
                return
            }

            fqnLabel.text = entry.name
            rateLabel.text = "${entry.rate}/s"
            budgetLabel.text = "${entry.budget}/s"
            classLabel.text = entry.budgetClass
            reasonLabel.text = entry.reason
            totalLabel.text = entry.totalCount.toString()
            peakLabel.text = "${entry.peakRate}/s"
            skipLabel.text = if (entry.skipPercent >= 0) "${entry.skipPercent}%" else "—"

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
