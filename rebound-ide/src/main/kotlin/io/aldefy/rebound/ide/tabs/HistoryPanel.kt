package io.aldefy.rebound.ide.tabs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.aldefy.rebound.ide.data.SessionData
import io.aldefy.rebound.ide.data.SessionPersistence
import io.aldefy.rebound.ide.data.SessionStore
import io.aldefy.rebound.ide.data.SessionSummary
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.io.File
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class HistoryPanel(
    private val sessionStore: SessionStore,
    private val project: Project
) : JPanel(BorderLayout()) {

    private val sessions = mutableListOf<SessionSummary>()
    private val sessionTableModel = SessionTableModel()
    private val sessionTable = JBTable(sessionTableModel).apply {
        fillsViewportHeight = true
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
    }

    private val comparisonRows = mutableListOf<ComparisonRow>()
    private val comparisonTableModel = ComparisonTableModel()
    private val comparisonTable = JBTable(comparisonTableModel).apply {
        fillsViewportHeight = true
    }

    private val summaryLabel = JBLabel("Select two sessions to compare")
    private val placeholderLabel = JBLabel("Select two sessions to compare").apply {
        horizontalAlignment = SwingConstants.CENTER
        font = font.deriveFont(Font.ITALIC)
    }

    private val comparisonPanel = JPanel(BorderLayout())

    init {
        val splitter = JBSplitter(false, 0.35f)
        splitter.firstComponent = buildSessionListPanel()
        splitter.secondComponent = buildComparisonPanel()
        add(splitter, BorderLayout.CENTER)

        sessionTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                onSessionSelectionChanged()
            }
        }

        comparisonTable.setDefaultRenderer(Any::class.java, ComparisonCellRenderer())

        refreshSessions()
    }

    private fun buildSessionListPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val toolbar = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
                val refreshButton = JButton("Refresh")
                refreshButton.addActionListener { refreshSessions() }
                add(refreshButton)
                add(Box.createHorizontalGlue())
            }
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(sessionTable), BorderLayout.CENTER)
        }
    }

    private fun buildComparisonPanel(): JPanel {
        comparisonPanel.apply {
            add(placeholderLabel, BorderLayout.CENTER)
        }
        return comparisonPanel
    }

    private fun refreshSessions() {
        val projectDir = project.basePath?.let { File(it) } ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = SessionPersistence.loadAll(projectDir)
            SwingUtilities.invokeLater {
                sessions.clear()
                sessions.addAll(loaded)
                sessionTableModel.fireTableDataChanged()
            }
        }
    }

    private fun onSessionSelectionChanged() {
        val selectedIndices = sessionTable.selectedRows.map { sessionTable.convertRowIndexToModel(it) }
        if (selectedIndices.size != 2) {
            showPlaceholder()
            return
        }

        val summaryA = sessions.getOrNull(selectedIndices[0]) ?: return
        val summaryB = sessions.getOrNull(selectedIndices[1]) ?: return

        // Load both sessions on background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val dataA = SessionPersistence.load(summaryA.file)
                val dataB = SessionPersistence.load(summaryB.file)
                SwingUtilities.invokeLater {
                    showComparison(dataA, dataB)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    showPlaceholder()
                    summaryLabel.text = "Error loading session: ${e.message}"
                    comparisonPanel.add(summaryLabel, BorderLayout.NORTH)
                    comparisonPanel.revalidate()
                    comparisonPanel.repaint()
                }
            }
        }
    }

    private fun showPlaceholder() {
        comparisonPanel.removeAll()
        comparisonPanel.add(placeholderLabel, BorderLayout.CENTER)
        comparisonRows.clear()
        comparisonPanel.revalidate()
        comparisonPanel.repaint()
    }

    private fun showComparison(dataA: SessionData, dataB: SessionData) {
        // Build comparison from last snapshot of each session
        val entriesA = if (dataA.snapshots.isNotEmpty()) dataA.snapshots.last().entries else emptyMap()
        val entriesB = if (dataB.snapshots.isNotEmpty()) dataB.snapshots.last().entries else emptyMap()

        val allKeys = (entriesA.keys + entriesB.keys).sorted()

        comparisonRows.clear()
        var regressed = 0
        var improved = 0
        var unchanged = 0

        for (key in allKeys) {
            val entryA = entriesA[key]
            val entryB = entriesB[key]
            val rateA = entryA?.rate ?: 0
            val rateB = entryB?.rate ?: 0
            val delta = rateB - rateA

            val change = when {
                delta > 0 -> {
                    regressed++
                    "Regressed"
                }
                delta < 0 -> {
                    improved++
                    "Improved"
                }
                else -> {
                    unchanged++
                    "Unchanged"
                }
            }

            val simpleName = entryA?.simpleName ?: entryB?.simpleName ?: key.substringAfterLast('.')
            comparisonRows.add(ComparisonRow(simpleName, rateA, rateB, delta, change))
        }

        // Sort: regressions first (highest delta), then improved, then unchanged
        comparisonRows.sortWith(compareByDescending { it.delta })

        summaryLabel.text = "$regressed regressed, $improved improved, $unchanged unchanged"

        comparisonPanel.removeAll()
        comparisonPanel.add(summaryLabel, BorderLayout.NORTH)
        comparisonPanel.add(JBScrollPane(comparisonTable), BorderLayout.CENTER)
        comparisonTableModel.fireTableDataChanged()
        comparisonPanel.revalidate()
        comparisonPanel.repaint()
    }

    fun dispose() {
        // No listeners to remove
    }

    // --- Data ---

    private data class ComparisonRow(
        val composable: String,
        val rateA: Int,
        val rateB: Int,
        val delta: Int,
        val change: String
    )

    // --- Session Table Model ---

    private inner class SessionTableModel : AbstractTableModel() {
        private val columns = arrayOf("Date", "Branch", "Commit", "Composables", "Violations")
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        override fun getRowCount(): Int = sessions.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val session = sessions[rowIndex]
            return when (columnIndex) {
                0 -> session.timestamp.format(dateFormat)
                1 -> session.branch ?: "-"
                2 -> session.commitHash?.take(7) ?: "-"
                3 -> "-" // Composable count not available from summary alone
                4 -> "-" // Violation count not available from summary alone
                else -> ""
            }
        }
    }

    // --- Comparison Table Model ---

    private inner class ComparisonTableModel : AbstractTableModel() {
        private val columns = arrayOf("Composable", "Rate (A)", "Rate (B)", "Delta", "Change")

        override fun getRowCount(): Int = comparisonRows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = comparisonRows[rowIndex]
            return when (columnIndex) {
                0 -> row.composable
                1 -> "${row.rateA}/s"
                2 -> "${row.rateB}/s"
                3 -> if (row.delta >= 0) "+${row.delta}" else "${row.delta}"
                4 -> row.change
                else -> ""
            }
        }
    }

    // --- Comparison Cell Renderer ---

    private inner class ComparisonCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (isSelected) return component

            val modelRow = table.convertRowIndexToModel(row)
            if (modelRow < 0 || modelRow >= comparisonRows.size) return component

            val entry = comparisonRows[modelRow]

            // Color delta and change columns
            if (column == 3 || column == 4) {
                when {
                    entry.delta > 0 -> {
                        // Regressed — red
                        component.foreground = JBColor(Color(200, 50, 50), Color(220, 80, 80))
                        component.font = component.font.deriveFont(Font.BOLD)
                    }
                    entry.delta < 0 -> {
                        // Improved — green
                        component.foreground = JBColor(Color(60, 160, 60), Color(80, 180, 80))
                        component.font = component.font.deriveFont(Font.BOLD)
                    }
                    else -> {
                        component.foreground = table.foreground
                        component.font = component.font.deriveFont(Font.PLAIN)
                    }
                }
            } else {
                component.foreground = table.foreground
                component.font = component.font.deriveFont(Font.PLAIN)
            }

            return component
        }
    }
}
