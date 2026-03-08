package io.aldefy.rebound.ide.tabs

import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.aldefy.rebound.ide.ComposableEntry
import io.aldefy.rebound.ide.data.SessionListener
import io.aldefy.rebound.ide.data.SessionStore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class StabilityPanel(private val sessionStore: SessionStore) : JPanel(BorderLayout()), SessionListener {

    private var selectedFqn: String? = null
    private var latestEntries: Map<String, ComposableEntry> = emptyMap()

    // --- Top: Param Matrix ---
    private val composableSelector = JComboBox<String>()
    private val paramEntries = mutableListOf<ParamRow>()
    private val paramTableModel = ParamTableModel()
    private val paramTable = JBTable(paramTableModel).apply {
        fillsViewportHeight = true
    }

    // --- Bottom: Cascade Tree ---
    private val cascadeSummaryLabel = JLabel("No composable selected")
    private val rootNode = DefaultMutableTreeNode("(none)")
    private val treeModel = DefaultTreeModel(rootNode)
    private val cascadeTree = JTree(treeModel)

    init {
        val splitter = JBSplitter(true, 0.6f)
        splitter.firstComponent = buildParamMatrixPanel()
        splitter.secondComponent = buildCascadePanel()
        add(splitter, BorderLayout.CENTER)

        composableSelector.addActionListener {
            val selected = composableSelector.selectedItem as? String
            if (selected != null && selected != selectedFqn) {
                selectedFqn = selected
                refreshParamTable()
                refreshCascadeTree()
            }
        }

        paramTable.setDefaultRenderer(Any::class.java, ParamCellRenderer())
        cascadeTree.cellRenderer = CascadeTreeCellRenderer()

        sessionStore.addListener(this)
    }

    private fun buildParamMatrixPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                add(JLabel("Composable: "))
                add(composableSelector)
                add(Box.createHorizontalGlue())
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(paramTable), BorderLayout.CENTER)
        }
    }

    private fun buildCascadePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                add(cascadeSummaryLabel)
                add(Box.createHorizontalGlue())
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(cascadeTree), BorderLayout.CENTER)
        }
    }

    // --- SessionListener ---

    override fun onSnapshot(entries: List<ComposableEntry>) {
        latestEntries = entries.associateBy { it.name }
        updateComboBox(entries)

        // Auto-select worst violator if nothing selected
        if (selectedFqn == null || !latestEntries.containsKey(selectedFqn)) {
            val worst = entries
                .filter { it.budget > 0 && it.rate > it.budget }
                .maxByOrNull { it.rate.toDouble() / it.budget.toDouble() }
            if (worst != null) {
                selectedFqn = worst.name
                composableSelector.selectedItem = worst.name
            }
        }

        refreshParamTable()
        refreshCascadeTree()
    }

    private fun updateComboBox(entries: List<ComposableEntry>) {
        val names = entries.map { it.name }.sorted()
        val current = composableSelector.selectedItem as? String

        composableSelector.removeAllItems()
        for (name in names) {
            composableSelector.addItem(name)
        }
        if (current != null && names.contains(current)) {
            composableSelector.selectedItem = current
        }
    }

    // --- Param Matrix ---

    private fun refreshParamTable() {
        paramEntries.clear()
        val fqn = selectedFqn ?: return
        val entry = latestEntries[fqn] ?: return

        if (entry.paramStates.isBlank()) {
            paramEntries.add(ParamRow("(no stability data)", "\u2014"))
            paramTableModel.fireTableDataChanged()
            return
        }

        // Parse "user=DIFFERENT,onClick=STATIC,items=UNCERTAIN"
        val pairs = entry.paramStates.split(",")
        for (pair in pairs) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) continue
            val paramName = pair.substring(0, eqIdx).trim()
            val stability = pair.substring(eqIdx + 1).trim()
            if (paramName.isNotBlank()) {
                paramEntries.add(ParamRow(paramName, stability))
            }
        }
        if (paramEntries.isEmpty()) {
            paramEntries.add(ParamRow("(no stability data)", "\u2014"))
        }
        paramTableModel.fireTableDataChanged()
    }

    // --- Cascade Tree ---

    private fun refreshCascadeTree() {
        rootNode.removeAllChildren()

        val fqn = selectedFqn
        if (fqn == null) {
            rootNode.userObject = "(none)"
            cascadeSummaryLabel.text = "No composable selected"
            treeModel.reload()
            return
        }

        val entry = latestEntries[fqn] // may be null if composable disappeared between snapshots
        rootNode.userObject = CascadeNodeData(fqn, entry)

        val stats = CascadeStats()
        buildCascadeChildren(rootNode, fqn, 0, stats)

        cascadeSummaryLabel.text = if (stats.totalDescendants > 0) {
            "Cascades to ${stats.totalDescendants} composable${if (stats.totalDescendants != 1) "s" else ""} across ${stats.maxDepth} depth level${if (stats.maxDepth != 1) "s" else ""}"
        } else {
            "No cascade children found"
        }

        treeModel.reload()
        // Expand all nodes
        for (i in 0 until cascadeTree.rowCount) {
            cascadeTree.expandRow(i)
        }
    }

    private fun buildCascadeChildren(
        parentNode: DefaultMutableTreeNode,
        parentFqn: String,
        currentDepth: Int,
        stats: CascadeStats,
        visited: MutableSet<String> = mutableSetOf()
    ) {
        if (currentDepth > 20) return // safety guard against deep recursion

        val children = latestEntries.values.filter { it.parentFqn == parentFqn }
        for (child in children) {
            if (child.name in visited) continue // prevent cycles
            visited.add(child.name)

            stats.totalDescendants++
            val childDepth = currentDepth + 1
            if (childDepth > stats.maxDepth) stats.maxDepth = childDepth

            val childNode = DefaultMutableTreeNode(CascadeNodeData(child.name, child))
            parentNode.add(childNode)
            buildCascadeChildren(childNode, child.name, childDepth, stats, visited)
        }
    }

    fun dispose() {
        sessionStore.removeListener(this)
    }

    // --- Data classes ---

    private data class ParamRow(
        val paramName: String,
        val stability: String
    ) {
        val changeFrequency: String
            get() = when (stability) {
                "DIFFERENT" -> "Every recomposition"
                "STATIC" -> "Never"
                "SAME" -> "Infrequent"
                "UNCERTAIN" -> "Unknown"
                else -> "Unknown"
            }
    }

    private data class CascadeNodeData(val fqn: String, val entry: ComposableEntry?) {
        val simpleName: String get() = entry?.simpleName ?: fqn.substringAfterLast('.')
        val isViolating: Boolean get() = entry != null && entry.budget > 0 && entry.rate > entry.budget

        override fun toString(): String {
            val e = entry ?: return simpleName
            return if (e.budget > 0) {
                "$simpleName (${e.rate}/${e.budget}/s)"
            } else {
                "$simpleName (${e.rate}/s)"
            }
        }
    }

    private class CascadeStats {
        var totalDescendants: Int = 0
        var maxDepth: Int = 0
    }

    // --- Table model ---

    private inner class ParamTableModel : AbstractTableModel() {
        private val columns = arrayOf("Param", "Stability", "Last State", "Change Frequency")

        override fun getRowCount(): Int = paramEntries.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = paramEntries[rowIndex]
            return when (columnIndex) {
                0 -> row.paramName
                1 -> row.stability
                2 -> row.stability // Last State mirrors stability from latest snapshot
                3 -> row.changeFrequency
                else -> ""
            }
        }
    }

    // --- Param cell renderer ---

    private inner class ParamCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (isSelected) return component

            val modelRow = table.convertRowIndexToModel(row)
            if (modelRow < 0 || modelRow >= paramEntries.size) return component

            val entry = paramEntries[modelRow]
            val stabilityColor = stabilityToColor(entry.stability)

            // Color the stability and last-state columns
            if (column == 1 || column == 2) {
                component.foreground = stabilityColor
                component.font = component.font.deriveFont(Font.BOLD)
            } else {
                component.foreground = table.foreground
                component.font = component.font.deriveFont(Font.PLAIN)
            }

            return component
        }
    }

    // --- Cascade tree cell renderer ---

    private inner class CascadeTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            if (sel) return component

            val node = value as? DefaultMutableTreeNode ?: return component
            val data = node.userObject as? CascadeNodeData ?: return component

            if (data.isViolating) {
                component.foreground = JBColor.RED
            }

            return component
        }
    }

    companion object {
        fun stabilityToColor(stability: String): Color = when (stability.uppercase()) {
            "DIFFERENT" -> JBColor(Color(200, 50, 50), Color(220, 80, 80))       // red
            "STATIC" -> JBColor(Color(60, 160, 60), Color(80, 180, 80))          // green
            "SAME" -> JBColor(Color(60, 100, 200), Color(80, 130, 220))          // blue
            "UNCERTAIN" -> JBColor(Color(140, 140, 140), Color(160, 160, 160))   // gray
            else -> JBColor(Color(140, 140, 140), Color(160, 160, 160))
        }
    }
}
