package app.gamenative.ui.component.dialog.state

import androidx.compose.runtime.saveable.mapSaver

data class DebugReportDialogState(
    val visible: Boolean,
    val appId: String = "",
    val reportDir: String = "",
    val gameName: String = "",
    val deviceName: String = "",
    val noLog: Boolean = false,
    val preparing: Boolean = false,
) {
    companion object {
        val Saver = mapSaver(
            save = { state ->
                mapOf(
                    "visible" to state.visible,
                    "appId" to state.appId,
                    "reportDir" to state.reportDir,
                    "gameName" to state.gameName,
                    "deviceName" to state.deviceName,
                    "noLog" to state.noLog,
                    "preparing" to state.preparing,
                )
            },
            restore = { savedMap ->
                DebugReportDialogState(
                    visible = savedMap["visible"] as Boolean,
                    appId = savedMap["appId"] as String,
                    reportDir = savedMap["reportDir"] as String,
                    gameName = savedMap["gameName"] as String,
                    deviceName = savedMap["deviceName"] as String,
                    noLog = savedMap["noLog"] as? Boolean ?: false,
                    preparing = savedMap["preparing"] as? Boolean ?: false,
                )
            },
        )
    }
}
