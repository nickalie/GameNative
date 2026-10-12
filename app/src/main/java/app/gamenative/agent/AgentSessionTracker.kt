package app.gamenative.agent

import com.winlator.container.Container
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AgentSessionState(val wire: String) {
    LAUNCHING("launching"),
    STARTING("starting"),
    RUNNING("running"),
    EXITING("exiting"),
    EXITED("exited"),
    ;

    val isActive: Boolean get() = this != EXITED
}

/** The launch-relevant part of a container config, captured when the session starts. */
data class AgentConfigSnapshot(
    val wineVersion: String,
    val containerVariant: String,
    val dxwrapper: String,
    val dxwrapperConfig: String,
    val graphicsDriver: String,
    val graphicsDriverConfig: String,
    val box64Version: String,
    val emulator: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("wineVersion", wineVersion)
        put("containerVariant", containerVariant)
        put("dxwrapper", dxwrapper)
        put("dxwrapperConfig", dxwrapperConfig)
        put("graphicsDriver", graphicsDriver)
        put("graphicsDriverConfig", graphicsDriverConfig)
        put("box64Version", box64Version)
        put("emulator", emulator)
    }

    companion object {
        fun from(container: Container): AgentConfigSnapshot = AgentConfigSnapshot(
            wineVersion = container.wineVersion.orEmpty(),
            containerVariant = container.containerVariant.orEmpty(),
            dxwrapper = container.dxWrapper.orEmpty(),
            dxwrapperConfig = container.dxWrapperConfig.orEmpty(),
            graphicsDriver = container.graphicsDriver.orEmpty(),
            graphicsDriverConfig = container.graphicsDriverConfig.orEmpty(),
            box64Version = container.box64Version.orEmpty(),
            emulator = container.emulator.orEmpty(),
        )
    }
}

/** Immutable view of a session, safe to hand to command handlers. */
data class AgentSessionSnapshot(
    val sessionId: String,
    val appId: String,
    val state: AgentSessionState,
    val startedAt: Long,
    val runningAt: Long?,
    val exitedAt: Long?,
    val exitReason: String?,
    val lastLaunchError: String?,
    val warnings: List<String>,
    val options: AgentLaunchOptions,
    val logFile: String?,
    val config: AgentConfigSnapshot?,
    val stopRequested: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("app_id", appId)
        put("state", state.wire)
        put("started_at", startedAt)
        runningAt?.let { put("running_at", it) }
        exitedAt?.let { put("exited_at", it) }
        exitReason?.let { put("exit_reason", it) }
        lastLaunchError?.let { put("last_launch_error", it) }
        put("warnings", buildJsonArray { warnings.forEach { add(it) } })
        put("agent_debug", options.agentDebug)
        options.wineDebugChannels?.takeIf { it.isNotBlank() }?.let { put("wine_debug_channels", it) }
        logFile?.let { put("log_file", it) }
        config?.let { put("config", it.toJson()) }
    }
}

data class AgentLogSnapshot(
    val lines: List<String>,
    val truncated: Boolean,
    val logFile: String?,
)

/**
 * Tracks the single game session the app can run at a time, as seen by the agent API.
 *
 * States: `launching` (agent intent accepted) → `starting` (environment setup) → `running` (first game
 * window) → `exiting` (exit requested) → `exited`. A UI launch skips `launching`. The last session is
 * kept after it exits so the agent can read how it ended. All methods are thread-safe; hooks call them
 * from the main thread, the WineSetup thread and process log threads.
 */
class AgentSessionTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val launchTimeoutMs: Long = LAUNCH_TIMEOUT_MS,
    private val maxLogLines: Int = MAX_LOG_LINES,
) {
    private class Session(
        val id: String,
        val appId: String,
        var state: AgentSessionState,
        val startedAt: Long,
        val options: AgentLaunchOptions,
    ) {
        var runningAt: Long? = null
        var exitedAt: Long? = null
        var exitReason: String? = null
        var lastLaunchError: String? = null
        val warnings = LinkedHashSet<String>()
        var logFile: String? = null
        var config: AgentConfigSnapshot? = null
        var stopRequested = false
        var prerequisitesRunning = false
        val log = ArrayDeque<String>()
        var droppedLines = 0L

        fun snapshot() = AgentSessionSnapshot(
            sessionId = id,
            appId = appId,
            state = state,
            startedAt = startedAt,
            runningAt = runningAt,
            exitedAt = exitedAt,
            exitReason = exitReason,
            lastLaunchError = lastLaunchError,
            warnings = warnings.toList(),
            options = options,
            logFile = logFile,
            config = config,
            stopRequested = stopRequested,
        )
    }

    private val lock = Any()
    private var session: Session? = null

    /**
     * An intent launch passed the launch funnel. Starts a new `launching` session and returns its id, to
     * be passed to [onLaunchAborted] if the pre-launch stops before the game launch.
     *
     * A session that is `starting`, `running` or `exiting` is kept and null is returned: its environment
     * is live, the app brings it forward instead of setting up another one, so no `onStarting` would
     * ever pick up a replacement. The intent's [options] are discarded then (with a warning on the kept
     * session). A finished session or a `launching` one (a repeated intent) is replaced.
     */
    fun onLaunchRequested(appId: String, options: AgentLaunchOptions): String? = synchronized(lock) {
        val current = active()
        if (current != null && current.state != AgentSessionState.LAUNCHING) {
            if (!options.isEmpty) current.warnings += WARNING_LAUNCH_IGNORED
            return null
        }
        newSession(appId, AgentSessionState.LAUNCHING, options).also { session = it }.id
    }

    /**
     * The pre-launch of the intent launch that created [sessionId] ended without launching the game (an
     * error or confirmation dialog, a failed download, a cancel). Ends that session with
     * `exit_reason=launch_error` while it is still `launching`, which also drops its options, so a later
     * UI launch cannot inherit them. Any other session, including a newer `launching` one, is left alone.
     */
    fun onLaunchAborted(sessionId: String) = synchronized(lock) {
        val current = active() ?: return@synchronized
        if (current.id != sessionId || current.state != AgentSessionState.LAUNCHING) return@synchronized
        current.exitReason = REASON_LAUNCH_ERROR
        if (current.lastLaunchError == null) current.lastLaunchError = LAUNCH_ABORTED_MESSAGE
        finish(current)
    }

    /** Launch options of the active session for [appId]; [AgentLaunchOptions.NONE] once it exits. */
    fun optionsFor(appId: String): AgentLaunchOptions = synchronized(lock) {
        val current = active() ?: return AgentLaunchOptions.NONE
        if (current.appId == appId) current.options else AgentLaunchOptions.NONE
    }

    /**
     * Environment setup began. Continues the `launching` session for the same game; anything else
     * (UI launch, another game, a stale session) starts a fresh session without agent options.
     */
    fun onStarting(appId: String, container: Container?, logFile: String? = null) {
        val config = container?.let { AgentConfigSnapshot.from(it) }
        synchronized(lock) {
            val current = active()
            val target = if (current != null && current.appId == appId && current.state == AgentSessionState.LAUNCHING) {
                current.state = AgentSessionState.STARTING
                current
            } else {
                newSession(appId, AgentSessionState.STARTING, AgentLaunchOptions.NONE).also { session = it }
            }
            target.config = config
            if (logFile != null) target.logFile = logFile
        }
    }

    /**
     * Prerequisite installers (VC++ redist and the like) run before the game; their windows must not
     * count as the game window, so [onRunning] is ignored until [onPrerequisitesFinished].
     */
    fun onPrerequisitesStarted() = synchronized(lock) {
        val current = active() ?: return@synchronized
        if (current.state == AgentSessionState.STARTING) current.prerequisitesRunning = true
    }

    /** The last prerequisite finished and the game executable is about to start. */
    fun onPrerequisitesFinished() = synchronized(lock) {
        active()?.prerequisitesRunning = false
        Unit
    }

    /** The first game window was mapped; ignored while prerequisite installers run. */
    fun onRunning() = synchronized(lock) {
        val current = active() ?: return@synchronized
        if (current.state == AgentSessionState.STARTING && !current.prerequisitesRunning) {
            current.state = AgentSessionState.RUNNING
            current.runningAt = clock()
        }
    }

    /** Teardown began; `force_close` after an agent stop request is reported as `agent_stop`. */
    fun onExiting(reason: String) = synchronized(lock) {
        val current = active() ?: return@synchronized
        if (current.state != AgentSessionState.STARTING && current.state != AgentSessionState.RUNNING) return@synchronized
        current.state = AgentSessionState.EXITING
        current.exitReason = if (reason == REASON_FORCE_CLOSE && current.stopRequested) REASON_AGENT_STOP else reason
    }

    /**
     * The environment was shut down. Idempotent, and ignored unless a session is `starting`, `running`
     * or `exiting`: `shutdownEnvironment()` also runs on activity creation and must not end a launch.
     */
    fun onExited() = synchronized(lock) {
        val current = active() ?: return@synchronized
        if (current.state == AgentSessionState.LAUNCHING) return@synchronized
        if (current.exitReason == null) {
            current.exitReason = if (current.stopRequested) REASON_AGENT_STOP else REASON_SHUTDOWN
        }
        finish(current)
    }

    /**
     * The app reported a launch error. Most are non-fatal (a failed unpack or Steam CEG pass, and the
     * game boots anyway), so by default the error is only recorded and the regular exit path closes the
     * session. [fatal] is for a failure that tears the environment down before the game can start
     * (wine setup); it ends a `launching`/`starting` session with `exit_reason=launch_error`.
     */
    fun onLaunchError(message: String, fatal: Boolean = false) = synchronized(lock) {
        val current = active() ?: return@synchronized
        current.lastLaunchError = message.take(MAX_ERROR_LENGTH)
        if (fatal && (current.state == AgentSessionState.LAUNCHING || current.state == AgentSessionState.STARTING)) {
            current.exitReason = REASON_LAUNCH_ERROR
            finish(current)
        }
    }

    fun addWarning(warning: String) = synchronized(lock) {
        active()?.warnings?.add(warning)
        Unit
    }

    /** Marks the session as stopped by the agent. Returns false unless it is `starting` or `running`. */
    fun requestStop(): Boolean = synchronized(lock) {
        val current = active() ?: return false
        if (current.state != AgentSessionState.STARTING && current.state != AgentSessionState.RUNNING) return false
        current.stopRequested = true
        true
    }

    /** Appends a redacted line to the session's ring buffer. */
    fun onLogLine(line: String) {
        val redacted = LogRedactor.redact(line.trimEnd('\n', '\r'))
        synchronized(lock) {
            val current = session ?: return
            if (current.log.size >= maxLogLines) {
                current.log.removeFirst()
                current.droppedLines++
            }
            current.log.addLast(redacted)
        }
    }

    /** The current session, or the last finished one; null if no session ever existed. */
    fun current(): AgentSessionSnapshot? = synchronized(lock) {
        expireStaleLaunch()
        session?.snapshot()
    }

    fun isActive(appId: String): Boolean = synchronized(lock) { active()?.appId == appId }

    /** Last [tail] buffered lines; `truncated` means earlier lines of this session are not included. */
    fun logs(tail: Int): AgentLogSnapshot = synchronized(lock) {
        val current = session ?: return AgentLogSnapshot(emptyList(), false, null)
        val count = tail.coerceIn(0, current.log.size)
        val lines = current.log.toList().takeLast(count)
        AgentLogSnapshot(lines, current.droppedLines > 0 || count < current.log.size, current.logFile)
    }

    private fun newSession(appId: String, state: AgentSessionState, options: AgentLaunchOptions) =
        Session(idGenerator(), appId, state, clock(), options)

    /** The session if it is still active, after applying the `launching` timeout. Caller holds the lock. */
    private fun active(): Session? {
        expireStaleLaunch()
        return session?.takeIf { it.state.isActive }
    }

    private fun expireStaleLaunch() {
        val current = session ?: return
        if (current.state == AgentSessionState.LAUNCHING && clock() - current.startedAt >= launchTimeoutMs) {
            current.exitReason = REASON_LAUNCH_TIMEOUT
            // Expiry is noticed lazily on the next call; report when the timeout actually happened.
            finish(current, at = current.startedAt + launchTimeoutMs)
        }
    }

    private fun finish(current: Session, at: Long = clock()) {
        current.state = AgentSessionState.EXITED
        current.exitedAt = at
        current.stopRequested = false
    }

    companion object {
        const val LAUNCH_TIMEOUT_MS = 5 * 60 * 1000L
        const val MAX_LOG_LINES = 2000
        private const val MAX_ERROR_LENGTH = 1000

        const val REASON_FORCE_CLOSE = "force_close"
        const val REASON_AGENT_STOP = "agent_stop"
        const val REASON_LAUNCH_TIMEOUT = "launch_timeout"
        const val REASON_LAUNCH_ERROR = "launch_error"
        const val REASON_SHUTDOWN = "shutdown"

        /** Warning on a live session when a repeated intent's agent options could not be applied. */
        const val WARNING_LAUNCH_IGNORED = "launch_options_ignored_session_active"

        /** `last_launch_error` of a session whose pre-launch stopped without reporting an error itself. */
        const val LAUNCH_ABORTED_MESSAGE = "Launch aborted before the game started"
    }
}
