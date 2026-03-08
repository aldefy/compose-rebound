package io.aldefy.rebound.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import io.aldefy.rebound.ide.data.ReboundSettings
import io.aldefy.rebound.ide.data.SessionStore
import io.aldefy.rebound.ide.data.VcsSessionContext
import io.aldefy.rebound.ide.editor.ReboundSessionStoreHolder
import io.aldefy.rebound.ide.data.SessionPersistence
import io.aldefy.rebound.ide.tabs.HotSpotsPanel
import io.aldefy.rebound.ide.tabs.HistoryPanel
import io.aldefy.rebound.ide.tabs.MonitorTab
import io.aldefy.rebound.ide.tabs.StabilityPanel
import io.aldefy.rebound.ide.tabs.TimelinePanel
import java.awt.BorderLayout
import javax.swing.*

class ReboundPanel(private val project: Project, private val toolWindow: ToolWindow) {

    private val settings = ReboundSettings.getInstance()
    val sessionStore = SessionStore(settings)
    private var connection: ReboundConnection? = null

    private val statusLabel = JLabel("Stopped")
    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop")
    private val clearButton = JButton("Clear")

    private val monitorTab = MonitorTab(sessionStore)
    private val hotSpotsPanel = HotSpotsPanel(sessionStore)
    private val timelinePanel = TimelinePanel(sessionStore)
    private val stabilityPanel = StabilityPanel(sessionStore)
    private val historyPanel = HistoryPanel(sessionStore, project)

    init {
        setupButtons()
        setupTabs()
        ReboundSessionStoreHolder.getInstance(project).sessionStore = sessionStore
    }

    private fun setupButtons() {
        stopButton.isEnabled = false
        startButton.addActionListener { startCapture() }
        stopButton.addActionListener { stopCapture() }
        clearButton.addActionListener { clearAll() }
    }

    private fun setupTabs() {
        val contentManager = toolWindow.contentManager

        // Tab 1: Monitor (with toolbar)
        val monitorWrapper = JPanel(BorderLayout()).apply {
            add(createToolbarPanel(), BorderLayout.NORTH)
            add(monitorTab, BorderLayout.CENTER)
        }
        val monitorContent = contentManager.factory.createContent(monitorWrapper, "Monitor", false)
        monitorContent.isCloseable = false
        contentManager.addContent(monitorContent)

        // Tab 2: Hot Spots
        val hotSpotsContent = contentManager.factory.createContent(hotSpotsPanel, "Hot Spots", false)
        hotSpotsContent.isCloseable = false
        contentManager.addContent(hotSpotsContent)

        // Tab 3: Timeline
        val timelineContent = contentManager.factory.createContent(timelinePanel, "Timeline", false)
        timelineContent.isCloseable = false
        contentManager.addContent(timelineContent)

        // Tab 4: Stability
        val stabilityContent = contentManager.factory.createContent(stabilityPanel, "Stability", false)
        stabilityContent.isCloseable = false
        contentManager.addContent(stabilityContent)

        // Tab 5: History
        val historyContent = contentManager.factory.createContent(historyPanel, "History", false)
        historyContent.isCloseable = false
        contentManager.addContent(historyContent)
    }

    private fun createToolbarPanel(): JPanel {
        return JPanel().apply {
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
    }

    private fun startCapture() {
        if (connection?.isRunning == true) return

        connection = ReboundConnection(
            onUpdate = { entries ->
                ApplicationManager.getApplication().invokeLater {
                    sessionStore.onSnapshot(entries)
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
        sessionStore.vcsContext = VcsSessionContext.capture(project)

        sessionStore.setConnectionState(true)
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusLabel.text = "Capturing..."
        statusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
    }

    private fun stopCapture() {
        connection?.stop()
        connection = null

        // Auto-save session to disk on background thread
        try {
            val projectDir = project.basePath?.let { java.io.File(it) }
            if (projectDir != null && sessionStore.currentEntries.isNotEmpty()) {
                val sessionData = sessionStore.toSessionData()
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        SessionPersistence.save(projectDir, sessionData)
                    } catch (_: Exception) {
                        // Save failure should not prevent stop
                    }
                }
            }
        } catch (_: Exception) {
            // Save failure should not prevent stop
        }

        sessionStore.setConnectionState(false)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusLabel.text = "Stopped"
        statusLabel.foreground = JBColor.GRAY
    }

    private fun clearAll() {
        sessionStore.clear()
        monitorTab.clearUI()
    }

    fun dispose() {
        connection?.stop()
        monitorTab.dispose()
        hotSpotsPanel.dispose()
        timelinePanel.dispose()
        stabilityPanel.dispose()
        historyPanel.dispose()
        ReboundSessionStoreHolder.getInstance(project).sessionStore = null
    }
}
