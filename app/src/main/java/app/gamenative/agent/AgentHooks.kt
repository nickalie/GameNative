package app.gamenative.agent

import com.winlator.container.Container
import timber.log.Timber

/**
 * One-line entry points for the session hooks in existing app code (`setupXEnvironment`, `exit`,
 * `PluviaApp.shutdownEnvironment`, `MainViewModel`). Every call is wrapped in `runCatching`, so a
 * tracker failure can never break a launch, the exit path or the environment teardown.
 */
object AgentHooks {
    /** Where the hooks find the tracker; replaced in tests. */
    @Volatile
    internal var trackerSource: () -> AgentSessionTracker = { AgentRuntime.tracker }

    /**
     * Prefix of the launch error XServerScreen reports when wine setup threw and the environment was
     * torn down; the game cannot start after it, so it ends the session. All other launch errors
     * (unpack, Steam CEG pass, non-zero guest exit) are recorded and the regular exit path follows.
     */
    internal const val FATAL_LAUNCH_ERROR_PREFIX = "Failed to setup wine"

    fun onStarting(appId: String, container: Container?) = guard("onStarting") { it.onStarting(appId, container) }

    /** Called for every process output line; failures are swallowed silently to avoid log spam. */
    fun onLogLine(line: String) {
        runCatching { trackerSource().onLogLine(line) }
    }

    fun onRunning() = guard("onRunning") { it.onRunning() }

    fun onExiting(reason: String) = guard("onExiting") { it.onExiting(reason) }

    fun onExited() = guard("onExited") { it.onExited() }

    fun onLaunchError(message: String) = guard("onLaunchError") {
        it.onLaunchError(message, fatal = isFatalLaunchError(message))
    }

    internal fun isFatalLaunchError(message: String): Boolean = message.startsWith(FATAL_LAUNCH_ERROR_PREFIX)

    private inline fun guard(hook: String, block: (AgentSessionTracker) -> Unit) {
        runCatching { block(trackerSource()) }
            .onFailure { Timber.w(it, "Agent hook %s failed", hook) }
    }
}
