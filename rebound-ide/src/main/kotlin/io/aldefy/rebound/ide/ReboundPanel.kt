package io.aldefy.rebound.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class ReboundPanel : JPanel(BorderLayout()) {

    private val tableModel = ReboundTableModel()
    private val table = JBTable(tableModel)
    private val statusLabel = JLabel("Stopped")
    private var connection: ReboundConnection? = null

    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop")
    private val clearButton = JButton("Clear")

    init {
        setupTable()
        setupToolbar()
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    private fun setupTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowSorter = TableRowSorter(tableModel)

        // Name column — show simple name, tooltip shows full FQN
        table.columnModel.getColumn(ReboundTableModel.COL_NAME).cellRenderer =
            object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column
                    )
                    val modelRow = table.convertRowIndexToModel(row)
                    val entry = tableModel.getEntryAt(modelRow)
                    if (entry != null) {
                        toolTipText = entry.name
                    }
                    return comp
                }
            }

        // Status column renderer — red for violations, green for OK
        table.columnModel.getColumn(ReboundTableModel.COL_STATUS).cellRenderer =
            object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column
                    )
                    if (!isSelected) {
                        val text = value?.toString() ?: ""
                        foreground = if (text.contains("OVER")) {
                            JBColor.RED
                        } else {
                            JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
                        }
                    }
                    return comp
                }
            }

        // Rate column — bold red when exceeding budget
        table.columnModel.getColumn(ReboundTableModel.COL_RATE).cellRenderer =
            object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column
                    )
                    if (!isSelected) {
                        val modelRow = table.convertRowIndexToModel(row)
                        val entry = tableModel.getEntryAt(modelRow)
                        if (entry != null && entry.rate > entry.budget) {
                            foreground = JBColor.RED
                            font = font.deriveFont(java.awt.Font.BOLD)
                        } else {
                            foreground = table.foreground
                            font = font.deriveFont(java.awt.Font.PLAIN)
                        }
                    }
                    text = "${value}/s"
                    return comp
                }
            }

        // Reason column — orange for FORCED, default for param changes
        table.columnModel.getColumn(ReboundTableModel.COL_REASON).cellRenderer =
            object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column
                    )
                    if (!isSelected) {
                        val text = value?.toString() ?: ""
                        foreground = when {
                            text.contains("FORCED") -> JBColor.ORANGE
                            text.contains("CHANGED") -> JBColor(
                                java.awt.Color(100, 100, 180),
                                java.awt.Color(140, 140, 220)
                            )
                            text.contains("MutableState") || text.contains("State") -> JBColor(
                                java.awt.Color(0, 128, 128),
                                java.awt.Color(80, 200, 200)
                            )
                            else -> table.foreground
                        }
                    }
                    return comp
                }
            }

        // Budget column — append /s suffix
        table.columnModel.getColumn(ReboundTableModel.COL_BUDGET).cellRenderer =
            object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column
                    )
                    text = "${value}/s"
                    return this
                }
            }
    }

    private fun setupToolbar() {
        stopButton.isEnabled = false

        startButton.addActionListener { startCapture() }
        stopButton.addActionListener { stopCapture() }
        clearButton.addActionListener { clearTable() }

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
                    tableModel.replaceAll(entries)
                    statusLabel.text = "Live (${entries.size} composables)"
                    statusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
                }
            },
            onError = { message ->
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = message
                    statusLabel.foreground = JBColor.ORANGE
                    // Don't disable buttons — transient errors should not lock the UI
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

    private fun clearTable() {
        tableModel.clear()
    }

    fun dispose() {
        connection?.stop()
    }
}
