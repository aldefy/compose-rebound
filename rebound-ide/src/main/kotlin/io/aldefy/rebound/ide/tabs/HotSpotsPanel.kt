package io.aldefy.rebound.ide.tabs

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.aldefy.rebound.ide.ComposableEntry
import io.aldefy.rebound.ide.data.SessionListener
import io.aldefy.rebound.ide.data.SessionStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class HotSpotsPanel(private val sessionStore: SessionStore) : JPanel(BorderLayout()), SessionListener {

    private val entries = mutableListOf<ComposableEntry>()

    private val violationsLabel = JLabel("0 violations").apply {
        foreground = JBColor.RED
        font = font.deriveFont(Font.BOLD)
    }
    private val nearBudgetLabel = JLabel("0 near budget").apply {
        foreground = JBColor.ORANGE
    }
    private val okLabel = JLabel("0 OK")

    private val tableModel = HotSpotsTableModel()
    private val table = JBTable(tableModel).apply {
        autoCreateRowSorter = true
        fillsViewportHeight = true
    }

    init {
        // Summary card at NORTH
        val summaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            add(violationsLabel)
            add(Box.createHorizontalStrut(16))
            add(nearBudgetLabel)
            add(Box.createHorizontalStrut(16))
            add(okLabel)
            add(Box.createHorizontalGlue())
        }
        add(summaryPanel, BorderLayout.NORTH)

        // Table at CENTER
        table.setDefaultRenderer(Any::class.java, HotSpotsCellRenderer())
        add(JBScrollPane(table), BorderLayout.CENTER)

        sessionStore.addListener(this)
    }

    // --- SessionListener ---

    override fun onSnapshot(entries: List<ComposableEntry>) {
        val sorted = entries.sortedByDescending { it.rate }
        this.entries.clear()
        this.entries.addAll(sorted)
        tableModel.fireTableDataChanged()
        updateSummary(sorted)
    }

    private fun updateSummary(entries: List<ComposableEntry>) {
        var violations = 0
        var nearBudget = 0
        var ok = 0
        for (e in entries) {
            val ratio = if (e.budget > 0) e.rate.toDouble() / e.budget else 0.0
            when {
                e.rate > e.budget && e.budget > 0 -> violations++
                ratio >= 0.7 && e.budget > 0 -> nearBudget++
                else -> ok++
            }
        }
        violationsLabel.text = "$violations violations"
        nearBudgetLabel.text = "$nearBudget near budget"
        okLabel.text = "$ok OK"
    }

    fun dispose() {
        sessionStore.removeListener(this)
    }

    // --- Table model ---

    private inner class HotSpotsTableModel : AbstractTableModel() {

        private val columns = arrayOf("Composable", "Rate/s", "Budget/s", "Ratio", "Skip %", "Peak/s", "Class", "Status")

        override fun getRowCount(): Int = entries.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            1, 2, 5 -> java.lang.Integer::class.java
            else -> String::class.java
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                0 -> entry.simpleName
                1 -> entry.rate
                2 -> entry.budget
                3 -> if (entry.budget > 0) "%.1f".format(entry.rate.toDouble() / entry.budget) else "\u2014"
                4 -> if (entry.skipPercent >= 0) "%.1f%%".format(entry.skipPercent) else "\u2014"
                5 -> entry.peakRate
                6 -> entry.budgetClass
                7 -> entry.status
                else -> ""
            }
        }
    }

    // --- Cell renderer ---

    private inner class HotSpotsCellRenderer : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

            if (isSelected) return component

            val modelRow = table.convertRowIndexToModel(row)
            if (modelRow < 0 || modelRow >= entries.size) return component

            val entry = entries[modelRow]
            val isViolation = entry.rate > entry.budget && entry.budget > 0

            if (isViolation) {
                component.foreground = JBColor.RED
                component.font = if (column == 0) {
                    component.font.deriveFont(Font.BOLD)
                } else {
                    component.font.deriveFont(Font.PLAIN)
                }
            } else {
                component.foreground = table.foreground
                component.font = component.font.deriveFont(Font.PLAIN)
            }

            return component
        }
    }
}
