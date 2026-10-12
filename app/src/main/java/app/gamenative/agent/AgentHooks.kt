package app.gamenative.agent

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

/**
 * One-line entry points for the session hooks in existing app code (`setupXEnvironment`, `exit`,
 * `PluviaApp.shutdownEnvironment`, `MainViewModel`, the intent launch path). Every call is wrapped in
 * `runCatching`, so a tracker failure can never break a launch, the exit path or the environment teardown.
 */
object AgentHooks {
    /** Where the hooks find the tracker; replaced in tests. */
    @Volatile
    internal var trackerSource: () -> AgentSessionTracker = { AgentRuntime.tracker }

    /** Where the hooks find the launch options side store; replaced in tests. */
    @Volatile
    internal var launchOptionsSource: () -> AgentLaunchOptionsStore = { AgentRuntime.launchOptions }

    /** Directory under the app's external files dir that holds process logs. */
    const val WINE_LOG_DIR = "wine_logs"

    /**
     * Prefix of the launch error XServerScreen reports when wine setup threw and the environment was
     * torn down; the game cannot start after it, so it ends the session. All other launch errors
     * (unpack, Steam CEG pass, non-zero guest exit) are recorded and the regular exit path follows.
     */
    internal const val FATAL_LAUNCH_ERROR_PREFIX = "Failed to setup wine"

    /** The launch error XServerScreen reports when wine setup threw; built here so it always classifies as fatal. */
    fun wineSetupFailedMessage(detail: String?): String = "$FATAL_LAUNCH_ERROR_PREFIX: $detail"

    /**
     * Every parsed launch intent overwrites the side store for its game, on the cold and the warm path;
     * empty options (no extras, or the Agent API toggle off) clear a stale entry.
     */
    fun onLaunchIntent(appId: String, options: AgentLaunchOptions) {
        runCatching { launchOptionsSource().put(appId, options) }
            .onFailure { Timber.w(it, "Agent hook onLaunchIntent failed") }
    }

    /**
     * The intent launch passed the funnel: takes the stored options once and starts a `launching` session.
     * Returns its id for [AgentPreLaunch.start], or null when a live session was kept (or the tracker failed).
     */
    fun onLaunchRequested(appId: String): String? = runCatching {
        val tracker = trackerSource()
        val options = launchOptionsSource().take(appId)
        val sessionId = tracker.onLaunchRequested(appId, options) ?: return@runCatching null
        AgentDebugEnv.warningsFor(options).forEach(tracker::addWarning)
        sessionId
    }.onFailure { Timber.w(it, "Agent hook onLaunchRequested failed") }.getOrNull()

    fun onLaunchAborted(sessionId: String) = guard("onLaunchAborted") { it.onLaunchAborted(sessionId) }

    /** Drops stored options of a launch that will not happen (game not found, launch error, timeout). */
    fun clearLaunchOptions(appId: String) {
        runCatching { launchOptionsSource().clear(appId) }
            .onFailure { Timber.w(it, "Agent hook clearLaunchOptions failed") }
    }

    /**
     * The dedicated log file of an `agent_debug` session for [appId] (`wine_logs/agent_<appId>.log`), or
     * null when the session has no `agent_debug`. Unlike a debug run's log it is never moved or truncated.
     */
    fun logFileFor(context: Context, appId: String): File? = runCatching {
        if (!trackerSource().optionsFor(appId).agentDebug) return@runCatching null
        File(File(context.getExternalFilesDir(null), WINE_LOG_DIR), "agent_$appId.log")
    }.onFailure { Timber.w(it, "Agent hook logFileFor failed") }.getOrNull()

    fun onStarting(appId: String, container: Container?, logFile: File? = null) =
        guard("onStarting") { it.onStarting(appId, container, logFile?.absolutePath) }

    /** Applies the `agent_debug` env of the session for [appId] over [envVars] (after the container env). */
    fun applyEnv(appId: String, envVars: EnvVars) = guard("applyEnv") {
        AgentDebugEnv.apply(envVars, it.optionsFor(appId))
    }

    /** Called for every process output line; failures are swallowed silently to avoid log spam. */
    fun onLogLine(line: String) {
        runCatching { trackerSource().onLogLine(line) }
    }

    fun onRunning() = guard("onRunning") { it.onRunning() }

    fun onPrerequisitesStarted() = guard("onPrerequisitesStarted") { it.onPrerequisitesStarted() }

    fun onPrerequisitesFinished() = guard("onPrerequisitesFinished") { it.onPrerequisitesFinished() }

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
