package io.aldefy.rebound.ide.tabs

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import io.aldefy.rebound.ide.ComposableEntry
import io.aldefy.rebound.ide.data.SessionListener
import io.aldefy.rebound.ide.data.SessionStore
import io.aldefy.rebound.ide.data.TimestampedSnapshot
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.JPanel

class TimelinePanel(private val sessionStore: SessionStore) : JPanel(BorderLayout()), SessionListener {

    private val heatmapCanvas = HeatmapCanvas()

    init {
        add(JBScrollPane(heatmapCanvas), BorderLayout.CENTER)
        sessionStore.addListener(this)
    }

    override fun onSnapshot(entries: List<ComposableEntry>) {
        heatmapCanvas.updateData(sessionStore.getSnapshots())
    }

    fun dispose() {
        sessionStore.removeListener(this)
    }

    // ---- Inner heatmap canvas ----

    private inner class HeatmapCanvas : JPanel() {

        private val cellWidth = 4
        private val cellHeight = 20
        private val labelWidth = 180

        private var image: BufferedImage? = null
        private var composableNames: List<String> = emptyList()
        private var snapshotData: List<TimestampedSnapshot> = emptyList()

        private val MAX_SNAPSHOTS = 720 // ~1 hour at 5s intervals

        fun updateData(snapshots: List<TimestampedSnapshot>) {
            // Cap to last MAX_SNAPSHOTS to prevent unbounded memory usage
            snapshotData = if (snapshots.size > MAX_SNAPSHOTS) {
                snapshots.subList(snapshots.size - MAX_SNAPSHOTS, snapshots.size)
            } else {
                snapshots
            }

            // Collect all unique composable names across all snapshots
            val peakRates = mutableMapOf<String, Int>()
            for (snapshot in snapshots) {
                for ((name, entry) in snapshot.entries) {
                    val current = peakRates[name] ?: 0
                    if (entry.peakRate > current) {
                        peakRates[name] = entry.peakRate
                    }
                    if (entry.rate > current) {
                        peakRates[name] = entry.rate
                    }
                }
            }

            // Sort by peak rate descending (hottest on top)
            composableNames = peakRates.entries
                .sortedByDescending { it.value }
                .map { it.key }

            // Calculate preferred size
            val w = labelWidth + snapshots.size * cellWidth + 20
            val h = composableNames.size * cellHeight + 40
            preferredSize = Dimension(w, h)

            revalidate()
            renderImage()
            repaint()
        }

        private fun renderImage() {
            if (snapshotData.isEmpty() || composableNames.isEmpty()) {
                image = null
                return
            }

            val w = preferredSize.width
            val h = preferredSize.height
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()

            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Background
            g2.color = background
            g2.fillRect(0, 0, w, h)

            // Draw row labels
            g2.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            val fm = g2.fontMetrics
            g2.color = foreground

            for ((rowIndex, fqn) in composableNames.withIndex()) {
                val simple = fqn.substringAfterLast('.')
                val label = if (simple.length > 20) simple.take(20) + "..." else simple
                val y = rowIndex * cellHeight + 20 + fm.ascent
                g2.drawString(label, 4, y)
            }

            // Draw heatmap cells
            for ((colIndex, snapshot) in snapshotData.withIndex()) {
                for ((rowIndex, name) in composableNames.withIndex()) {
                    val entry = snapshot.entries[name]
                    val ratio = if (entry != null && entry.budget > 0) {
                        entry.rate.toDouble() / entry.budget.toDouble()
                    } else if (entry != null && entry.rate > 0) {
                        1.0 // No budget but has rate — treat as over
                    } else {
                        0.0
                    }

                    g2.color = ratioToColor(ratio)
                    val x = labelWidth + colIndex * cellWidth
                    val y = rowIndex * cellHeight + 20
                    g2.fillRect(x, y, cellWidth, cellHeight)
                }
            }

            g2.dispose()
            image = img
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image
            if (img != null) {
                g.drawImage(img, 0, 0, null)
            } else {
                g.color = JBColor.GRAY
                val msg = "No timeline data \u2014 start capture to collect snapshots"
                val fm = g.fontMetrics
                val x = (width - fm.stringWidth(msg)) / 2
                val y = height / 2
                g.drawString(msg, x, y)
            }
        }

        private fun ratioToColor(ratio: Double): Color {
            return when {
                ratio <= 0.0 -> JBColor(Color(40, 40, 40), Color(50, 50, 50))
                ratio < 0.5 -> JBColor(Color(60, 160, 60), Color(60, 140, 60))
                ratio < 1.0 -> JBColor(Color(200, 180, 40), Color(180, 160, 40))
                else -> JBColor(Color(200, 50, 50), Color(180, 40, 40))
            }
        }
    }
}
