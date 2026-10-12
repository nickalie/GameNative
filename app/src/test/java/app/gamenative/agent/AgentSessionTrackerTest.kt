package app.gamenative.agent

import android.app.Application
import com.winlator.container.Container
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentSessionTrackerTest {

    private var now = 1_000L
    private var nextId = 0
    private val debug = AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+d3d")

    private fun tracker(maxLogLines: Int = AgentSessionTracker.MAX_LOG_LINES) = AgentSessionTracker(
        clock = { now },
        idGenerator = { "s${++nextId}" },
        maxLogLines = maxLogLines,
    )

    private fun AgentSessionTracker.state() = current()?.state

    // --- transitions ---

    @Test
    fun `full agent launch cycle keeps one session and its options`() {
        val t = tracker()
        t.onLaunchRequested("STEAM_1", debug)
        assertEquals(AgentSessionState.LAUNCHING, t.state())
        assertEquals(debug, t.optionsFor("STEAM_1"))
        assertTrue(t.isActive("STEAM_1"))

        now = 2_000
        t.onStarting("STEAM_1", null, "/logs/agent_STEAM_1.log")
        assertEquals(AgentSessionState.STARTING, t.state())
        assertEquals(debug, t.optionsFor("STEAM_1"))

        now = 3_000
        t.onRunning()
        now = 4_000
        t.onExiting("quick_menu")
        assertEquals(AgentSessionState.EXITING, t.state())
        assertTrue(t.isActive("STEAM_1"))
        now = 5_000
        t.onExited()

        val s = t.current()!!
        assertEquals("s1", s.sessionId)
        assertEquals("STEAM_1", s.appId)
        assertEquals(AgentSessionState.EXITED, s.state)
        assertEquals(1_000L, s.startedAt)
        assertEquals(3_000L, s.runningAt)
        assertEquals(5_000L, s.exitedAt)
        assertEquals("quick_menu", s.exitReason)
        assertEquals("/logs/agent_STEAM_1.log", s.logFile)
        assertTrue(s.options.agentDebug)
        assertFalse(t.isActive("STEAM_1"))
    }

    @Test
    fun `ui launch starts a session without launching and without options`() {
        val t = tracker()
        t.onStarting("STEAM_2", null)
        val s = t.current()!!
        assertEquals(AgentSessionState.STARTING, s.state)
        assertEquals(AgentLaunchOptions.NONE, s.options)
        assertEquals(AgentLaunchOptions.NONE, t.optionsFor("STEAM_2"))
        t.onRunning()
        t.onExiting("guest_terminated")
        t.onExited()
        assertEquals("guest_terminated", t.current()!!.exitReason)
    }

    @Test
    fun `starting another game drops the pending launch options`() {
        val t = tracker()
        t.onLaunchRequested("STEAM_1", debug)
        t.onStarting("STEAM_2", null)
        val s = t.current()!!
        assertEquals("STEAM_2", s.appId)
        assertEquals("s2", s.sessionId)
        assertEquals(AgentLaunchOptions.NONE, s.options)
        assertEquals(AgentLaunchOptions.NONE, t.optionsFor("STEAM_1"))
    }

    @Test
    fun `optionsFor another app id returns none`() {
        val t = tracker()
        t.onLaunchRequested("STEAM_1", debug)
        assertEquals(AgentLaunchOptions.NONE, t.optionsFor("STEAM_9"))
    }

    @Test
    fun `onExited is idempotent`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.onExiting("processes_exited")
        now = 2_000
        t.onExited()
        now = 3_000
        t.onExited()
        val s = t.current()!!
        assertEquals(2_000L, s.exitedAt)
        assertEquals("processes_exited", s.exitReason)
    }

    @Test
    fun `onExited without a session or while launching is ignored`() {
        val t = tracker()
        t.onExited()
        assertNull(t.current())

        t.onLaunchRequested("STEAM_1", debug)
        t.onExited() // stale shutdownEnvironment, e.g. from MainActivity.onCreate
        assertEquals(AgentSessionState.LAUNCHING, t.state())
        assertEquals(debug, t.optionsFor("STEAM_1"))
    }

    @Test
    fun `onExited without exit reason reports shutdown`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.onRunning()
        t.onExited()
        assertEquals(AgentSessionTracker.REASON_SHUTDOWN, t.current()!!.exitReason)
    }

    @Test
    fun `new session replaces a finished one and resets the log buffer`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.onLogLine("old line")
        t.onExiting("quick_menu")
        t.onExited()

        t.onLaunchRequested("STEAM_1", AgentLaunchOptions.NONE)
        val s = t.current()!!
        assertEquals("s2", s.sessionId)
        assertEquals(AgentSessionState.LAUNCHING, s.state)
        assertNull(s.exitReason)
        assertTrue(t.logs(200).lines.isEmpty())
    }

    @Test
    fun `force_close after stop request is reported as agent_stop`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.onRunning()
        assertTrue(t.requestStop())
        assertTrue(t.current()!!.stopRequested)
        t.onExiting(AgentSessionTracker.REASON_FORCE_CLOSE)
        t.onExited()
        assertEquals(AgentSessionTracker.REASON_AGENT_STOP, t.current()!!.exitReason)
    }

    @Test
    fun `force_close without stop request stays force_close`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.onExiting(AgentSessionTracker.REASON_FORCE_CLOSE)
        t.onExited()
        assertEquals(AgentSessionTracker.REASON_FORCE_CLOSE, t.current()!!.exitReason)
    }

    @Test
    fun `stop flag does not outlive the session`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.requestStop()
        t.onExiting("quick_menu")
        t.onExited()
        assertEquals("quick_menu", t.current()!!.exitReason)
        assertFalse(t.current()!!.stopRequested)

        t.onStarting("STEAM_1", null)
        t.onExiting(AgentSessionTracker.REASON_FORCE_CLOSE)
        assertEquals(AgentSessionTracker.REASON_FORCE_CLOSE, t.current()!!.exitReason)
    }

    @Test
    fun `requestStop only works while starting or running`() {
        val t = tracker()
        assertFalse(t.requestStop())
        t.onLaunchRequested("STEAM_1", debug)
        assertFalse(t.requestStop())
        t.onStarting("STEAM_1", null)
        assertTrue(t.requestStop())
        assertTrue(t.requestStop())
        t.onExiting("quick_menu")
        assertFalse(t.requestStop())
        t.onExited()
        assertFalse(t.requestStop())
    }

    @Test
    fun `stuck launching times out`() {
        val t = tracker()
        t.onLaunchRequested("STEAM_1", debug)
        now += AgentSessionTracker.LAUNCH_TIMEOUT_MS - 1
        assertEquals(AgentSessionState.LAUNCHING, t.state())
        now += 1
        val s = t.current()!!
        assertEquals(AgentSessionState.EXITED, s.state)
        assertEquals(AgentSessionTracker.REASON_LAUNCH_TIMEOUT, s.exitReason)
        assertFalse(t.isActive("STEAM_1"))
        assertEquals(AgentLaunchOptions.NONE, t.optionsFor("STEAM_1"))

        // A late setup after the timeout is a fresh session without the expired options.
        t.onStarting("STEAM_1", null)
        assertEquals(AgentLaunchOptions.NONE, t.current()!!.options)
        assertEquals("s2", t.current()!!.sessionId)
    }

    @Test
    fun `launch error while launching or starting ends the session`() {
        val t = tracker()
        t.onLaunchRequested("STEAM_1", debug)
        t.onLaunchError("Steam not logged in")
        var s = t.current()!!
        assertEquals(AgentSessionState.EXITED, s.state)
        assertEquals(AgentSessionTracker.REASON_LAUNCH_ERROR, s.exitReason)
        assertEquals("Steam not logged in", s.lastLaunchError)

        t.onStarting("STEAM_1", null)
        t.onLaunchError("Failed to setup wine: boom")
        s = t.current()!!
        assertEquals(AgentSessionState.EXITED, s.state)
        assertEquals("Failed to setup wine: boom", s.lastLaunchError)
    }

    @Test
    fun `launch error while running is recorded without ending the session`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.onRunning()
        t.onLaunchError("late error")
        val s = t.current()!!
        assertEquals(AgentSessionState.RUNNING, s.state)
        assertEquals("late error", s.lastLaunchError)
    }

    @Test
    fun `launch error without a session is ignored`() {
        val t = tracker()
        t.onLaunchError("nothing to attach to")
        assertNull(t.current())
    }

    @Test
    fun `options are one-shot`() {
        val t = tracker()
        t.onLaunchRequested("STEAM_1", debug)
        t.onStarting("STEAM_1", null)
        t.onExiting("quick_menu")
        assertEquals(debug, t.optionsFor("STEAM_1"))
        t.onExited()
        assertEquals(AgentLaunchOptions.NONE, t.optionsFor("STEAM_1"))

        t.onStarting("STEAM_1", null) // next launch from the UI
        assertEquals(AgentLaunchOptions.NONE, t.optionsFor("STEAM_1"))
    }

    @Test
    fun `onRunning and onExiting are ignored in wrong states`() {
        val t = tracker()
        t.onRunning()
        t.onExiting("quick_menu")
        assertNull(t.current())

        t.onLaunchRequested("STEAM_1", debug)
        t.onRunning()
        t.onExiting("quick_menu")
        assertEquals(AgentSessionState.LAUNCHING, t.state())
    }

    @Test
    fun `warnings are deduplicated and only added to active sessions`() {
        val t = tracker()
        t.addWarning("ignored")
        t.onLaunchRequested("STEAM_1", debug)
        t.addWarning("dropped_channel:+all")
        t.addWarning("dropped_channel:+all")
        t.addWarning("other")
        assertEquals(listOf("dropped_channel:+all", "other"), t.current()!!.warnings)
    }

    @Test
    fun `config snapshot is captured from the container`() {
        val container = Container("STEAM_1").apply {
            wineVersion = "proton-9.0-arm64ec"
            containerVariant = "bionic"
            dxWrapper = "dxvk"
            dxWrapperConfig = "version=2.4"
            graphicsDriver = "turnip"
            graphicsDriverConfig = "version=25.1"
            box64Version = "0.3.6"
            emulator = "FEXCore"
        }
        val t = tracker()
        t.onStarting("STEAM_1", container)
        val config = t.current()!!.config!!
        assertEquals("proton-9.0-arm64ec", config.wineVersion)
        assertEquals("bionic", config.containerVariant)
        assertEquals("dxvk", config.dxwrapper)
        assertEquals("version=2.4", config.dxwrapperConfig)
        assertEquals("turnip", config.graphicsDriver)
        assertEquals("version=25.1", config.graphicsDriverConfig)
        assertEquals("0.3.6", config.box64Version)
        assertEquals("FEXCore", config.emulator)
    }

    @Test
    fun `snapshot json has the wire shape`() {
        val t = tracker()
        t.onLaunchRequested("STEAM_1", debug)
        t.onStarting("STEAM_1", Container("STEAM_1"), "/logs/a.log")
        t.addWarning("w1")
        val json = t.current()!!.toJson()
        assertEquals("s1", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("STEAM_1", json["app_id"]!!.jsonPrimitive.content)
        assertEquals("starting", json["state"]!!.jsonPrimitive.content)
        assertEquals(1_000L, json["started_at"]!!.jsonPrimitive.long)
        assertTrue(json["agent_debug"]!!.jsonPrimitive.boolean)
        assertEquals("+d3d", json["wine_debug_channels"]!!.jsonPrimitive.content)
        assertEquals("/logs/a.log", json["log_file"]!!.jsonPrimitive.content)
        assertEquals(listOf("w1"), json["warnings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(json["config"]!!.jsonObject.containsKey("wineVersion"))
        assertFalse(json.containsKey("running_at"))
        assertFalse(json.containsKey("exit_reason"))
        assertFalse(json.containsKey("last_launch_error"))
    }

    // --- log buffer and redaction ---

    @Test
    fun `log buffer keeps the newest lines on overflow`() {
        val t = tracker(maxLogLines = 3)
        t.onStarting("STEAM_1", null)
        (1..5).forEach { t.onLogLine("line $it\n") }
        val logs = t.logs(200)
        assertEquals(listOf("line 3", "line 4", "line 5"), logs.lines)
        assertTrue(logs.truncated)
    }

    @Test
    fun `tail returns the last lines and flags truncation`() {
        val t = tracker()
        t.onStarting("STEAM_1", null, "/logs/a.log")
        (1..5).forEach { t.onLogLine("line $it") }
        val tail = t.logs(2)
        assertEquals(listOf("line 4", "line 5"), tail.lines)
        assertTrue(tail.truncated)
        assertEquals("/logs/a.log", tail.logFile)

        val all = t.logs(200)
        assertEquals(5, all.lines.size)
        assertFalse(all.truncated)

        assertTrue(t.logs(0).lines.isEmpty())
        assertTrue(t.logs(-5).lines.isEmpty())
    }

    @Test
    fun `logs without a session are empty`() {
        val t = tracker()
        t.onLogLine("nobody listens")
        val logs = t.logs(200)
        assertTrue(logs.lines.isEmpty())
        assertFalse(logs.truncated)
        assertNull(logs.logFile)
    }

    @Test
    fun `secrets are removed before lines enter the buffer`() {
        val t = tracker()
        t.onStarting("STEAM_1", null)
        t.onLogLine("exec game.exe -AUTH_LOGIN=unused -AUTH_PASSWORD=abc123secret -AUTH_TYPE=exchangecode")
        t.onLogLine("GET https://api.example.com/x?token=deadbeef&page=2")
        val text = t.logs(200).lines.joinToString("\n")
        assertFalse(text.contains("abc123secret"))
        assertFalse(text.contains("deadbeef"))
        assertTrue(text.contains("-AUTH_PASSWORD=[REDACTED]"))
        assertTrue(text.contains("-AUTH_TYPE=exchangecode"))
        assertTrue(text.contains("page=2"))
    }

    @Test
    fun `redactor covers common secret shapes and leaves ordinary lines alone`() {
        val cases = mapOf(
            "-AUTH_PASSWORD=\"quoted secret\" -x" to "-AUTH_PASSWORD=[REDACTED] -x",
            "-epicovt=/path/ovt.json run" to "-epicovt=[REDACTED] run",
            "access_token=abc.def refresh_token=ghi" to "access_token=[REDACTED] refresh_token=[REDACTED]",
            "{\"refresh_token\": \"xyz\", \"name\": \"ok\"}" to "{\"refresh_token\": \"[REDACTED]\", \"name\": \"ok\"}",
            "Authorization: Bearer abcdefghijkl" to "Authorization: Bearer [REDACTED]",
            "using bearer abcdefghijkl" to "using bearer [REDACTED]",
            "password=hunter2" to "password=[REDACTED]",
            "STEAM_API_KEY=0123" to "STEAM_API_KEY=[REDACTED]",
            "err:d3d:wined3d_device_create device creation failed" to "err:d3d:wined3d_device_create device creation failed",
            "DXVK_HUD=fps WINEDEBUG=+d3d" to "DXVK_HUD=fps WINEDEBUG=+d3d",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, LogRedactor.redact(input)) }
    }

    @Test
    fun `concurrent log lines are all counted`() {
        val t = tracker(maxLogLines = 100_000)
        t.onStarting("STEAM_1", null)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        repeat(4) { worker ->
            pool.submit {
                start.await()
                repeat(1_000) { t.onLogLine("w$worker $it") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(4_000, t.logs(100_000).lines.size)
    }

    @Test
    fun `session ids are unique by default`() {
        val t = AgentSessionTracker()
        t.onStarting("STEAM_1", null)
        val first = t.current()!!.sessionId
        t.onExited()
        t.onStarting("STEAM_1", null)
        assertNotEquals(first, t.current()!!.sessionId)
    }
}
