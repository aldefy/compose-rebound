package io.aldefy.rebound.ide

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ReboundToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReboundPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Clean up logcat process when tool window is disposed
        toolWindow.contentManager.addContentManagerListener(
            object : com.intellij.ui.content.ContentManagerListener {
                override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                    panel.dispose()
                }
            }
        )
    }
}
