package io.aldefy.rebound.ide

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class ReboundToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReboundPanel(project, toolWindow)
        // ReboundPanel populates toolWindow.contentManager with tabs itself

        // Clean up when all content is removed
        toolWindow.contentManager.addContentManagerListener(
            object : com.intellij.ui.content.ContentManagerListener {
                override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                    if (toolWindow.contentManager.contentCount == 0) {
                        panel.dispose()
                    }
                }
            }
        )
    }
}
