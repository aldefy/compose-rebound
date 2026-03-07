package io.aldefy.rebound.ide

import javax.swing.table.AbstractTableModel

class ReboundTableModel : AbstractTableModel() {

    companion object {
        val COLUMNS = arrayOf("Composable", "Rate", "Budget", "Class", "Reason", "Status")
        const val COL_NAME = 0
        const val COL_RATE = 1
        const val COL_BUDGET = 2
        const val COL_CLASS = 3
        const val COL_REASON = 4
        const val COL_STATUS = 5
    }

    private val rows = mutableListOf<ComposableEntry>()
    private val indexMap = HashMap<String, Int>()

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = COLUMNS.size
    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_RATE, COL_BUDGET -> Integer::class.java
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = rows[rowIndex]
        return when (columnIndex) {
            COL_NAME -> entry.simpleName
            COL_RATE -> entry.rate
            COL_BUDGET -> entry.budget
            COL_CLASS -> entry.budgetClass
            COL_REASON -> entry.reason
            COL_STATUS -> entry.status
            else -> ""
        }
    }

    fun upsert(entry: ComposableEntry) {
        val existing = indexMap[entry.name]
        if (existing != null) {
            val row = rows[existing]
            row.rate = entry.rate
            row.budget = entry.budget
            row.budgetClass = entry.budgetClass
            if (entry.totalCount > 0) row.totalCount = entry.totalCount
            if (entry.isViolation) row.isViolation = true
            if (entry.isForced) row.isForced = true
            if (entry.changedParams.isNotEmpty()) row.changedParams = entry.changedParams
            if (entry.skipPercent >= 0) row.skipPercent = entry.skipPercent
            if (entry.invalidationReason.isNotEmpty()) row.invalidationReason = entry.invalidationReason
            // Reset violation flag if rate dropped below budget
            if (row.rate <= row.budget) row.isViolation = false
            fireTableRowsUpdated(existing, existing)
        } else {
            val idx = rows.size
            rows.add(entry)
            indexMap[entry.name] = idx
            fireTableRowsInserted(idx, idx)
        }
    }

    /** Replace all rows from a full snapshot (socket-based updates). */
    fun replaceAll(entries: List<ComposableEntry>) {
        rows.clear()
        indexMap.clear()
        entries.forEachIndexed { idx, entry ->
            rows.add(entry)
            indexMap[entry.name] = idx
        }
        fireTableDataChanged()
    }

    fun clear() {
        val size = rows.size
        if (size > 0) {
            rows.clear()
            indexMap.clear()
            fireTableRowsDeleted(0, size - 1)
        }
    }

    fun getEntryAt(rowIndex: Int): ComposableEntry? = rows.getOrNull(rowIndex)
}
