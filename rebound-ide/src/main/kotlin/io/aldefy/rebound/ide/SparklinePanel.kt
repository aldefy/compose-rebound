package io.aldefy.rebound.ide

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * Mini line chart showing rate-over-time for a selected composable.
 * Green when within budget, red when over. Dashed budget threshold line.
 */
class SparklinePanel : JPanel() {

    private var samples: List<Int> = emptyList()
    private var budget: Int = 0

    private val lineStroke = BasicStroke(1.5f)
    private val budgetStroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
    private val axisFont = Font(Font.SANS_SERIF, Font.PLAIN, 10)

    private val greenLine = JBColor(Color(60, 160, 60), Color(80, 200, 80))
    private val redLine = JBColor(Color(200, 60, 60), Color(255, 80, 80))
    private val budgetColor = JBColor(Color(180, 180, 60), Color(200, 200, 80))
    private val overFill = JBColor(Color(200, 60, 60, 40), Color(255, 80, 80, 40))
    private val emptyColor = JBColor.GRAY

    init {
        preferredSize = Dimension(0, 100)
        minimumSize = Dimension(0, 60)
    }

    fun update(samples: List<Int>, budget: Int) {
        this.samples = samples
        this.budget = budget
        repaint()
    }

    fun clear() {
        this.samples = emptyList()
        this.budget = 0
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val pad = 8
        val labelPadLeft = 36
        val labelPadRight = 8
        val labelPadBottom = 16
        val chartLeft = pad + labelPadLeft
        val chartRight = width - pad - labelPadRight
        val chartTop = pad
        val chartBottom = height - pad - labelPadBottom
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        if (chartWidth < 20 || chartHeight < 20) return

        if (samples.isEmpty()) {
            g2.color = emptyColor
            g2.font = axisFont.deriveFont(12f)
            val msg = "No rate history"
            val fm = g2.fontMetrics
            g2.drawString(msg, (width - fm.stringWidth(msg)) / 2, height / 2)
            return
        }

        val maxRate = maxOf(samples.max(), budget, 1)
        val yScale = chartHeight.toDouble() / maxRate

        fun rateToY(rate: Int): Int = chartBottom - (rate * yScale).toInt()

        // Budget threshold line
        if (budget > 0) {
            g2.color = budgetColor
            g2.stroke = budgetStroke
            val budgetY = rateToY(budget)
            g2.drawLine(chartLeft, budgetY, chartRight, budgetY)
            g2.font = axisFont
            g2.drawString("${budget}/s", pad, budgetY + 4)
        }

        // Rate line + over-budget fill
        val n = samples.size
        val xStep = if (n > 1) chartWidth.toDouble() / (n - 1) else 0.0

        // Fill areas where rate exceeds budget
        if (budget > 0) {
            g2.color = overFill
            val budgetY = rateToY(budget)
            for (i in 0 until n - 1) {
                val x1 = chartLeft + (i * xStep).toInt()
                val x2 = chartLeft + ((i + 1) * xStep).toInt()
                val y1 = rateToY(samples[i])
                val y2 = rateToY(samples[i + 1])
                if (samples[i] > budget || samples[i + 1] > budget) {
                    val poly = java.awt.Polygon()
                    poly.addPoint(x1, minOf(y1, budgetY))
                    poly.addPoint(x2, minOf(y2, budgetY))
                    poly.addPoint(x2, budgetY)
                    poly.addPoint(x1, budgetY)
                    g2.fillPolygon(poly)
                }
            }
        }

        // Draw rate line segments
        g2.stroke = lineStroke
        for (i in 0 until n - 1) {
            val x1 = chartLeft + (i * xStep).toInt()
            val x2 = chartLeft + ((i + 1) * xStep).toInt()
            val y1 = rateToY(samples[i])
            val y2 = rateToY(samples[i + 1])
            val overAtI = budget > 0 && samples[i] > budget
            val overAtNext = budget > 0 && samples[i + 1] > budget
            g2.color = if (overAtI || overAtNext) redLine else greenLine
            g2.drawLine(x1, y1, x2, y2)
        }

        // Axis labels
        g2.color = emptyColor
        g2.font = axisFont
        val fm = g2.fontMetrics
        g2.drawString("${n}s ago", chartLeft, chartBottom + fm.height + 2)
        val nowText = "now"
        g2.drawString(nowText, chartRight - fm.stringWidth(nowText), chartBottom + fm.height + 2)

        // Max rate label on Y axis
        g2.drawString("${maxRate}/s", pad, chartTop + fm.ascent)
    }
}
