package io.aldefy.rebound.ide.data

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "ReboundSettings", storages = [Storage("rebound.xml")])
class ReboundSettings : PersistentStateComponent<ReboundSettings.State> {

    data class State(
        var historyRetentionSeconds: Int = 3600,
        var snapshotIntervalSeconds: Int = 5,
        var maxStoredSessions: Int = 20,
        var showGutterIcons: Boolean = true,
        var showInlayHints: Boolean = true,
        var autoConnect: Boolean = false,
        var adbPort: Int = 18462,
        var maxEventLogLines: Int = 5000,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): ReboundSettings =
            ApplicationManager.getApplication().getService(ReboundSettings::class.java)
    }
}
