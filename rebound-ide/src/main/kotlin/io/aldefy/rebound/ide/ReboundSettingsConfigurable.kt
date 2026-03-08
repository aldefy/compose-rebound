package io.aldefy.rebound.ide

import com.intellij.openapi.options.Configurable
import io.aldefy.rebound.ide.data.ReboundSettings
import javax.swing.JComponent
import com.intellij.ui.dsl.builder.*

class ReboundSettingsConfigurable : Configurable {
    private val settings = ReboundSettings.getInstance()

    // Local copies for editing
    private var historyRetention = settings.state.historyRetentionSeconds
    private var snapshotInterval = settings.state.snapshotIntervalSeconds
    private var maxSessions = settings.state.maxStoredSessions
    private var showGutter = settings.state.showGutterIcons
    private var showInlays = settings.state.showInlayHints
    private var autoConnect = settings.state.autoConnect
    private var adbPort = settings.state.adbPort
    private var maxLogLines = settings.state.maxEventLogLines

    override fun getDisplayName() = "Rebound"

    override fun createComponent(): JComponent {
        return panel {
            group("Connection") {
                row("ADB port:") {
                    intTextField(1024..65535)
                        .bindIntText(::adbPort)
                }
                row {
                    checkBox("Auto-connect on project open")
                        .bindSelected(::autoConnect)
                }
            }
            group("Data Collection") {
                row("History retention (seconds):") {
                    intTextField(60..86400)
                        .bindIntText(::historyRetention)
                }
                row("Snapshot interval (seconds):") {
                    intTextField(1..60)
                        .bindIntText(::snapshotInterval)
                }
                row("Max event log lines:") {
                    intTextField(100..50000)
                        .bindIntText(::maxLogLines)
                }
            }
            group("Storage") {
                row("Max stored sessions:") {
                    intTextField(1..100)
                        .bindIntText(::maxSessions)
                }
            }
            group("Editor Integration") {
                row {
                    checkBox("Show gutter icons")
                        .bindSelected(::showGutter)
                }
                row {
                    checkBox("Show inlay hints (CodeVision)")
                        .bindSelected(::showInlays)
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return historyRetention != s.historyRetentionSeconds ||
            snapshotInterval != s.snapshotIntervalSeconds ||
            maxSessions != s.maxStoredSessions ||
            showGutter != s.showGutterIcons ||
            showInlays != s.showInlayHints ||
            autoConnect != s.autoConnect ||
            adbPort != s.adbPort ||
            maxLogLines != s.maxEventLogLines
    }

    override fun apply() {
        settings.loadState(ReboundSettings.State(
            historyRetentionSeconds = historyRetention,
            snapshotIntervalSeconds = snapshotInterval,
            maxStoredSessions = maxSessions,
            showGutterIcons = showGutter,
            showInlayHints = showInlays,
            autoConnect = autoConnect,
            adbPort = adbPort,
            maxEventLogLines = maxLogLines
        ))
    }

    override fun reset() {
        val s = settings.state
        historyRetention = s.historyRetentionSeconds
        snapshotInterval = s.snapshotIntervalSeconds
        maxSessions = s.maxStoredSessions
        showGutter = s.showGutterIcons
        showInlays = s.showInlayHints
        autoConnect = s.autoConnect
        adbPort = s.adbPort
        maxLogLines = s.maxEventLogLines
    }
}
