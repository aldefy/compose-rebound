package io.aldefy.rebound.ide

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Scrolling timestamped event log with color-coded severity levels.
 */
class EventLogPanel : JPanel(BorderLayout()) {

    enum class Level { OVER, WARN, STATE, RATE, INFO }

    private val textPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    }
    private val doc: StyledDocument = textPane.styledDocument
    private val scrollPane = JBScrollPane(textPane)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val overStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, JBColor(Color(200, 40, 40), Color(255, 80, 80)))
        StyleConstants.setBold(this, true)
    }
    private val warnStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, JBColor(Color(200, 150, 0), Color(230, 180, 50)))
    }
    private val grayStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, JBColor(Color(140, 140, 140), Color(160, 160, 160)))
    }

    private var lineCount = 0
    private val maxLines = 500

    init {
        add(scrollPane, BorderLayout.CENTER)
    }

    fun append(level: Level, message: String) {
        val style = when (level) {
            Level.OVER -> overStyle
            Level.WARN -> warnStyle
            else -> grayStyle
        }
        val tag = when (level) {
            Level.OVER -> "OVER"
            Level.WARN -> "WARN"
            Level.STATE -> "STATE"
            Level.RATE -> "RATE"
            Level.INFO -> "INFO"
        }
        val timestamp = LocalTime.now().format(timeFormat)
        val line = "$timestamp [$tag] $message\n"

        // Evict oldest lines if over limit
        while (lineCount >= maxLines) {
            val text = doc.getText(0, doc.length)
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) {
                doc.remove(0, firstNewline + 1)
                lineCount--
            } else {
                break
            }
        }

        doc.insertString(doc.length, line, style)
        lineCount++

        // Auto-scroll to bottom
        textPane.caretPosition = doc.length
    }

    fun clear() {
        try {
            doc.remove(0, doc.length)
        } catch (_: Exception) {
        }
        lineCount = 0
    }
}
