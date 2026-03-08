package io.aldefy.rebound.ide.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.aldefy.rebound.ide.data.SessionStore

@Service(Service.Level.PROJECT)
class ReboundSessionStoreHolder {
    var sessionStore: SessionStore? = null

    companion object {
        fun getInstance(project: Project): ReboundSessionStoreHolder =
            project.getService(ReboundSessionStoreHolder::class.java)
    }
}
