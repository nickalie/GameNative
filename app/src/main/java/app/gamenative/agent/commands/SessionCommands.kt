package app.gamenative.agent.commands

import app.gamenative.agent.AgentDispatcher
import app.gamenative.agent.AgentErrorCode
import app.gamenative.agent.AgentException
import app.gamenative.agent.AgentSessionState
import app.gamenative.agent.AgentSessionTracker
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * `sessions.*` commands over the session tracker.
 *
 * [postToMain] runs a task on the main looper after a delay in milliseconds (0 = as soon as possible) and
 * [forceClose] emits the app's force-close event; the event dispatcher is not thread-safe and its listeners
 * touch the UI, so a stop is always posted instead of emitted from the binder thread. [environmentReady]
 * tells whether the wine environment has been published (`PluviaApp.xEnvironment`); it is read on the main
 * thread only.
 */
class SessionCommands(
    private val tracker: AgentSessionTracker,
    private val postToMain: (Runnable, Long) -> Unit,
    private val environmentReady: () -> Boolean,
    private val forceClose: () -> Unit,
    private val maxLogBytes: Int = MAX_LOG_BYTES,
) {
    /** Session whose stop is queued on the main looper; at most one waiting stop per session. */
    private val pendingStop = AtomicReference<String?>(null)

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(CURRENT) { current() }
        dispatcher.register(STOP) { stop() }
        dispatcher.register(LOGS) { logs(it) }
    }

    /** The active session, or the last finished one; `null` if no session ever existed. */
    fun current(): JsonElement = tracker.current()?.toJson() ?: JsonNull

    /**
     * `sessions.stop` → `{session_id, app_id, state, already_requested}`. Marks the session as stopped by
     * the agent and, on the main thread, emits the force-close event once the wine environment exists.
     * While the session is `starting` the setup worker has not published the environment yet; closing the
     * screen then would tear nothing down while the worker goes on to start wine and the game, so the stop
     * waits (polling every [STOP_POLL_MS]) until the environment is published, or ends unused when the
     * session finishes some other way. Prerequisite installers run after the environment is published and
     * the stop does not wait for them: teardown kills the installer, and the installer chain does not
     * advance once the screen is exiting (`advancePreInstallChain`). Repeating the stop is safe. Any state
     * other than `starting` or `running` → `NO_ACTIVE_SESSION`.
     */
    fun stop(): JsonElement {
        val before = tracker.current()
        if (!tracker.requestStop()) {
            val details = before?.let { buildJsonObject { put("state", it.state.wire) } }
            throw AgentException(AgentErrorCode.NO_ACTIVE_SESSION, "no session is starting or running", details)
        }
        val session = tracker.current()
        if (session != null && pendingStop.getAndSet(session.sessionId) != session.sessionId) {
            postToMain(Runnable { closeWhenReady(session.sessionId) }, 0L)
        }
        return buildJsonObject {
            session?.let {
                put("session_id", it.sessionId)
                put("app_id", it.appId)
                put("state", it.state.wire)
            }
            put("already_requested", before?.stopRequested == true && before.sessionId == session?.sessionId)
        }
    }

    /** Main thread: force-closes [sessionId] once the environment exists; drops the stop once it ended. */
    private fun closeWhenReady(sessionId: String) {
        val session = tracker.current()
        val stillWanted = session != null &&
            session.sessionId == sessionId &&
            session.stopRequested &&
            (session.state == AgentSessionState.STARTING || session.state == AgentSessionState.RUNNING)
        if (!stillWanted) {
            pendingStop.compareAndSet(sessionId, null)
            return
        }
        val ready = runCatching { environmentReady() }
            .onFailure { Timber.w(it, "Agent stop: environment check failed") }
            .getOrDefault(false)
        if (!ready) {
            postToMain(Runnable { closeWhenReady(sessionId) }, STOP_POLL_MS)
            return
        }
        pendingStop.compareAndSet(sessionId, null)
        runCatching { forceClose() }.onFailure { Timber.w(it, "Agent stop: force close failed") }
    }

    /**
     * `sessions.logs {tail?=200}` → `{lines[], truncated, log_file?}`. The newest [tail] buffered lines,
     * dropping the oldest of them once the encoded lines exceed [maxLogBytes]; `truncated` is set when
     * earlier lines of the session are not included for either reason.
     */
    fun logs(args: JsonObject): JsonElement {
        val tail = CommandArgs.optInt(args, "tail") ?: DEFAULT_TAIL
        if (tail < 0) throw CommandArgs.badArgs("tail must not be negative")
        val snapshot = tracker.logs(tail)
        val kept = ArrayDeque<JsonPrimitive>()
        var bytes = 0
        for (line in snapshot.lines.asReversed()) {
            val element = JsonPrimitive(line)
            // Encoded size including quotes, escapes and the separating comma.
            val size = element.toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > maxLogBytes) break
            bytes += size
            kept.addFirst(element)
        }
        return buildJsonObject {
            put("lines", JsonArray(kept.toList()))
            put("truncated", snapshot.truncated || kept.size < snapshot.lines.size)
            snapshot.logFile?.let { put("log_file", it) }
        }
    }

    companion object {
        const val CURRENT = "sessions.current"
        const val STOP = "sessions.stop"
        const val LOGS = "sessions.logs"

        const val DEFAULT_TAIL = 200

        /** Byte budget for the log lines, leaving room for the envelope under the response cap. */
        const val MAX_LOG_BYTES = 192 * 1024

        /** How often a stop waiting for the environment re-checks it on the main thread. */
        const val STOP_POLL_MS = 250L
    }
}
