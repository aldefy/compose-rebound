package io.aldefy.rebound.ide.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import io.aldefy.rebound.ide.ComposableEntry
import io.aldefy.rebound.ide.data.SessionListener

class ReboundStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "ReboundStatusBar"
    override fun getDisplayName() = "Rebound"
    override fun createWidget(project: Project) = ReboundStatusBarWidget(project)
}

class ReboundStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation, SessionListener {

    private var statusBar: StatusBar? = null
    private var text = "Rebound: \u2014"

    override fun ID() = "ReboundStatusBar"
    override fun getPresentation() = this
    override fun getText() = text
    override fun getTooltipText() = "Rebound recomposition monitor. Click to open."
    override fun getAlignment() = java.awt.Component.CENTER_ALIGNMENT

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ReboundSessionStoreHolder.getInstance(project).sessionStore?.addListener(this)
    }

    override fun dispose() {
        ReboundSessionStoreHolder.getInstance(project).sessionStore?.removeListener(this)
    }

    // SessionListener callbacks
    override fun onSnapshot(entries: List<ComposableEntry>) {
        val violations = entries.count { it.rate > it.budget && it.budget > 0 }
        text = if (violations > 0) {
            "Rebound: ${entries.size} | $violations violations"
        } else {
            "Rebound: ${entries.size} composables"
        }
        statusBar?.updateWidget(ID())
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        if (!connected) {
            text = "Rebound: \u2014"
            statusBar?.updateWidget(ID())
        }
    }
}
