package app.gamenative.agent

import app.gamenative.agent.commands.ComponentInstallQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Static entry point for the agent hooks. The hooks live in code that cannot take new parameters
 * (XServerScreen, the launch funnel), so they reach the tracker and the options store through here.
 */
object AgentRuntime {
    val tracker: AgentSessionTracker = AgentSessionTracker()
    val launchOptions: AgentLaunchOptionsStore = AgentLaunchOptionsStore()
    val componentInstalls: ComponentInstallQueue = ComponentInstallQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /**
     * True once the application finished initializing preferences. Content providers are created
     * before `Application.onCreate`, so the agent provider answers `NOT_READY` until this is set.
     */
    @Volatile
    var isReady: Boolean = false
        internal set

    fun markReady() {
        isReady = true
    }
}
