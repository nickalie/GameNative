package app.gamenative.agent

import app.gamenative.agent.commands.SessionCommands
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCommandsTest {

    private var nextId = 0
    private val tracker = AgentSessionTracker(clock = { 1_000L }, idGenerator = { "s${++nextId}" })
    private val posted = mutableListOf<Runnable>()
    private val delays = mutableListOf<Long>()
    private var forceCloses = 0
    private var environmentReady = true

    /** Runs the queued main-thread tasks in order, including the ones they queue, up to [limit]. */
    private fun drainMain(limit: Int = 100) {
        var runs = 0
        while (posted.isNotEmpty() && runs < limit) {
            posted.removeAt(0).run()
            runs++
        }
    }

    private fun dispatcher(
        maxLogBytes: Int = SessionCommands.MAX_LOG_BYTES,
        forceClose: () -> Unit = { forceCloses++ },
    ): AgentDispatcher {
        val dispatcher = AgentDispatcher(isEnabled = { true }, isReady = { true }, appVersion = "test", packageName = "app.gamenative")
        SessionCommands(
            tracker,
            postToMain = { task, delayMs ->
                posted += task
                delays += delayMs
            },
            environmentReady = { environmentReady },
            forceClose = forceClose,
            maxLogBytes = maxLogBytes,
        ).register(dispatcher)
        return dispatcher
    }

    private fun call(dispatcher: AgentDispatcher, command: String, args: JsonObject? = null): JsonObject {
        val request = buildJsonObject {
            put("api_version", AgentProtocol.API_VERSION)
            put("command", command)
            args?.let { put("args", it) }
        }
        return Json.parseToJsonElement(dispatcher.dispatch(AgentProtocol.encode(request))).jsonObject
    }

    private fun data(response: JsonObject): JsonObject {
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        return response["data"]!!.jsonObject
    }

    private fun errorCode(response: JsonObject): String {
        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        return response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    }

    private fun tail(n: Int) = buildJsonObject { put("tail", n) }

    private fun JsonObject.lines() = this["lines"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun runningSession(appId: String = "STEAM_123") {
        tracker.onStarting(appId, container = null)
        tracker.onRunning()
    }

    // --- sessions.logs ---

    @Test
    fun logs_withoutSession_isEmpty() {
        val logs = data(call(dispatcher(), SessionCommands.LOGS))

        assertEquals(emptyList<String>(), logs.lines())
        assertFalse(logs["truncated"]!!.jsonPrimitive.boolean)
        assertNull(logs["log_file"])
    }

    @Test
    fun logs_defaultTailIs200() {
        runningSession()
        repeat(250) { tracker.onLogLine("line $it") }

        val logs = data(call(dispatcher(), SessionCommands.LOGS))

        assertEquals((50 until 250).map { "line $it" }, logs.lines())
        assertTrue(logs["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun logs_tailReturnsNewestLines() {
        runningSession()
        repeat(5) { tracker.onLogLine("line $it") }
        val dispatcher = dispatcher()

        val last2 = data(call(dispatcher, SessionCommands.LOGS, tail(2)))
        val all = data(call(dispatcher, SessionCommands.LOGS, tail(100)))

        assertEquals(listOf("line 3", "line 4"), last2.lines())
        assertTrue(last2["truncated"]!!.jsonPrimitive.boolean)
        assertEquals((0 until 5).map { "line $it" }, all.lines())
        assertFalse(all["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun logs_includeLogFileOfSession() {
        tracker.onStarting("STEAM_123", container = null, logFile = "/data/wine_logs/agent_123.log")
        tracker.onLogLine("hello")

        val logs = data(call(dispatcher(), SessionCommands.LOGS))

        assertEquals("/data/wine_logs/agent_123.log", logs["log_file"]!!.jsonPrimitive.content)
    }

    @Test
    fun logs_byteCapDropsOldestLines() {
        runningSession()
        repeat(10) { tracker.onLogLine("x".repeat(98) + it) }
        // Each line encodes to 101 bytes ("…" quotes + 99 chars) plus a separator: room for three.
        val logs = data(call(dispatcher(maxLogBytes = 3 * 102 + 50), SessionCommands.LOGS, tail(10)))

        assertEquals((7 until 10).map { "x".repeat(98) + it }, logs.lines())
        assertTrue(logs["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun logs_byteCapCountsJsonEscapes() {
        runningSession()
        val quoted = "\"".repeat(50) // 50 chars, 102 bytes once escaped and quoted
        repeat(3) { tracker.onLogLine(quoted) }

        val logs = data(call(dispatcher(maxLogBytes = 2 * 103), SessionCommands.LOGS, tail(3)))

        assertEquals(2, logs.lines().size)
        assertTrue(logs["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun logs_defaultCapKeepsResponseUnderLimit() {
        runningSession()
        repeat(AgentSessionTracker.MAX_LOG_LINES) { tracker.onLogLine("y".repeat(1000)) }

        val response = dispatcher().dispatch(
            AgentProtocol.encode(
                buildJsonObject {
                    put("api_version", AgentProtocol.API_VERSION)
                    put("command", SessionCommands.LOGS)
                    put("args", tail(AgentSessionTracker.MAX_LOG_LINES))
                },
            ),
        )

        assertTrue(response.toByteArray().size <= AgentProtocol.MAX_RESPONSE_BYTES)
        val logs = data(Json.parseToJsonElement(response).jsonObject)
        assertTrue(logs.lines().isNotEmpty())
        assertTrue(logs["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun logs_badTail_isBadArgs() {
        val dispatcher = dispatcher()

        assertEquals("BAD_ARGS", errorCode(call(dispatcher, SessionCommands.LOGS, tail(-1))))
        assertEquals("BAD_ARGS", errorCode(call(dispatcher, SessionCommands.LOGS, buildJsonObject { put("tail", "10") })))
        assertEquals("BAD_ARGS", errorCode(call(dispatcher, SessionCommands.LOGS, buildJsonObject { put("tail", 1.5) })))
    }

    // --- sessions.stop ---

    @Test
    fun stop_withoutSession_isNoActiveSession() {
        val response = call(dispatcher(), SessionCommands.STOP)

        assertEquals("NO_ACTIVE_SESSION", errorCode(response))
        assertTrue(posted.isEmpty())
    }

    @Test
    fun stop_whileLaunching_isNoActiveSession() {
        tracker.onLaunchRequested("STEAM_123", AgentLaunchOptions.NONE)

        val response = call(dispatcher(), SessionCommands.STOP)

        assertEquals("NO_ACTIVE_SESSION", errorCode(response))
        assertEquals(JsonPrimitive("launching"), response["error"]!!.jsonObject["details"]!!.jsonObject["state"])
        assertTrue(posted.isEmpty())
    }

    @Test
    fun stop_postsForceCloseViaPoster() {
        runningSession()

        val result = data(call(dispatcher(), SessionCommands.STOP))

        assertEquals("s1", result["session_id"]!!.jsonPrimitive.content)
        assertEquals("STEAM_123", result["app_id"]!!.jsonPrimitive.content)
        assertEquals("running", result["state"]!!.jsonPrimitive.content)
        assertFalse(result["already_requested"]!!.jsonPrimitive.boolean)
        // Nothing is emitted on the binder thread; the event goes out only when the main looper runs it.
        assertEquals(0, forceCloses)
        assertEquals(1, posted.size)
        posted.single().run()
        assertEquals(1, forceCloses)
        assertTrue(tracker.current()!!.stopRequested)
    }

    @Test
    fun stop_whileStarting_isAccepted() {
        tracker.onStarting("STEAM_123", container = null)

        val result = data(call(dispatcher(), SessionCommands.STOP))

        assertEquals("starting", result["state"]!!.jsonPrimitive.content)
        assertEquals(1, posted.size)
    }

    @Test
    fun stop_whileStarting_waitsForTheEnvironmentBeforeForceClosing() {
        tracker.onStarting("STEAM_123", container = null)
        environmentReady = false
        val dispatcher = dispatcher(
            forceClose = {
                forceCloses++
                tracker.onExiting(AgentSessionTracker.REASON_FORCE_CLOSE)
            },
        )

        data(call(dispatcher, SessionCommands.STOP))
        // The setup worker has not published the environment: the stop is re-checked, not emitted.
        repeat(3) { posted.removeAt(0).run() }
        assertEquals(0, forceCloses)
        assertEquals(1, posted.size)
        assertEquals(listOf(0L, SessionCommands.STOP_POLL_MS, SessionCommands.STOP_POLL_MS, SessionCommands.STOP_POLL_MS), delays)
        assertEquals(AgentSessionState.STARTING, tracker.current()!!.state)
        // A retry while the stop is waiting does not queue a second one.
        assertTrue(data(call(dispatcher, SessionCommands.STOP))["already_requested"]!!.jsonPrimitive.boolean)
        assertEquals(1, posted.size)

        environmentReady = true
        drainMain()

        assertEquals(1, forceCloses)
        assertTrue(posted.isEmpty())
        tracker.onExited()
        assertEquals(AgentSessionTracker.REASON_AGENT_STOP, tracker.current()!!.exitReason)
    }

    @Test
    fun stop_whilePrerequisitesRun_forceClosesWithoutWaitingForThem() {
        // A stuck installer must be stoppable: the force close kills it, and the installer chain itself
        // refuses to advance during exit (advancePreInstallChain), so the step stays not done.
        tracker.onStarting("STEAM_123", container = null)
        tracker.onPrerequisitesStarted()
        val dispatcher = dispatcher(
            forceClose = {
                forceCloses++
                tracker.onExiting(AgentSessionTracker.REASON_FORCE_CLOSE)
            },
        )

        data(call(dispatcher, SessionCommands.STOP))
        drainMain()

        assertEquals(1, forceCloses)
        assertEquals(AgentSessionState.EXITING, tracker.current()!!.state)
        assertEquals(AgentSessionTracker.REASON_AGENT_STOP, tracker.current()!!.exitReason)
    }

    @Test
    fun stop_whileStarting_isDroppedWhenTheSessionEndsOtherwise() {
        tracker.onStarting("STEAM_123", container = null)
        environmentReady = false
        val dispatcher = dispatcher()

        data(call(dispatcher, SessionCommands.STOP))
        posted.removeAt(0).run()
        tracker.onLaunchError(AgentHooks.wineSetupFailedMessage("boom"), fatal = true)
        environmentReady = true
        drainMain()

        assertEquals(0, forceCloses)
        assertTrue(posted.isEmpty())
        // The next session's stop is queued again rather than treated as already waiting.
        tracker.onStarting("STEAM_123", container = null)
        data(call(dispatcher, SessionCommands.STOP))
        drainMain()
        assertEquals(1, forceCloses)
    }

    @Test
    fun stop_environmentCheckFailure_keepsWaiting() {
        runningSession()
        var checks = 0
        val dispatcher = AgentDispatcher(isEnabled = { true }, isReady = { true }, appVersion = "test", packageName = "app.gamenative")
        SessionCommands(
            tracker,
            postToMain = { task, _ -> posted += task },
            environmentReady = { if (++checks == 1) error("boom") else true },
            forceClose = { forceCloses++ },
        ).register(dispatcher)

        data(call(dispatcher, SessionCommands.STOP))
        drainMain()

        assertEquals(2, checks)
        assertEquals(1, forceCloses)
    }

    @Test
    fun stop_repeated_isIdempotent() {
        runningSession()
        val dispatcher = dispatcher()

        val first = data(call(dispatcher, SessionCommands.STOP))
        val second = data(call(dispatcher, SessionCommands.STOP))

        assertFalse(first["already_requested"]!!.jsonPrimitive.boolean)
        assertTrue(second["already_requested"]!!.jsonPrimitive.boolean)
        assertEquals(first["session_id"], second["session_id"])
        assertEquals("running", second["state"]!!.jsonPrimitive.content)
        // The first stop is still queued, so the second one does not queue another.
        assertEquals(1, posted.size)
        drainMain()
        assertEquals(1, forceCloses)
        // Once the queued stop ran, a retry queues the force close again.
        data(call(dispatcher, SessionCommands.STOP))
        drainMain()
        assertEquals(2, forceCloses)
        assertTrue(tracker.current()!!.stopRequested)
    }

    @Test
    fun stop_endsSessionAsAgentStop_andLaterStopIsRejected() {
        runningSession()
        // The real force-close listener runs XServerScreen.exit("force_close"), which hooks onExiting.
        val dispatcher = dispatcher(forceClose = { tracker.onExiting(AgentSessionTracker.REASON_FORCE_CLOSE) })

        data(call(dispatcher, SessionCommands.STOP))
        posted.single().run()

        assertEquals("NO_ACTIVE_SESSION", errorCode(call(dispatcher, SessionCommands.STOP)))
        tracker.onExited()
        val session = tracker.current()!!
        assertEquals(AgentSessionState.EXITED, session.state)
        assertEquals(AgentSessionTracker.REASON_AGENT_STOP, session.exitReason)
        assertEquals("NO_ACTIVE_SESSION", errorCode(call(dispatcher, SessionCommands.STOP)))
        assertEquals(1, posted.size)
    }

    @Test
    fun stop_forceCloseFailure_doesNotEscapeThePostedTask() {
        runningSession()

        data(call(dispatcher(forceClose = { error("listener crashed") }), SessionCommands.STOP))

        posted.single().run()
    }

    @Test
    fun register_addsAllSessionCommands() {
        val commands = dispatcher().commands()

        assertTrue(commands.containsAll(listOf("sessions.current", "sessions.stop", "sessions.logs")))
    }
}
