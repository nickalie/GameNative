package app.gamenative.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.utils.DebugRunParams
import com.winlator.core.envvars.EnvVars
import java.io.File
import kotlinx.coroutines.Job
import org.junit.After
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
class AgentDebugEnvTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val debug = AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+d3d,vulkan")

    @After
    fun resetHooks() {
        AgentHooks.trackerSource = { AgentRuntime.tracker }
        AgentHooks.launchOptionsSource = { AgentRuntime.launchOptions }
    }

    private class Hooked(val tracker: AgentSessionTracker, val store: AgentLaunchOptionsStore)

    /** A tracker and store the hooks write to, as `AgentRuntime` would provide them in the app. */
    private fun hooked(): Hooked {
        val tracker = AgentSessionTracker()
        val store = AgentLaunchOptionsStore()
        AgentHooks.trackerSource = { tracker }
        AgentHooks.launchOptionsSource = { store }
        return Hooked(tracker, store)
    }

    private fun agentLogFile(appId: String) =
        File(File(context.getExternalFilesDir(null), "wine_logs"), "agent_$appId.log")

    // --- channel filtering ---

    @Test
    fun `valid channels are normalized and keep their order`() {
        val channels = AgentDebugEnv.filterChannels(" +D3D , vulkan,-fixme ,,")
        assertEquals(listOf("+d3d", "+vulkan", "-fixme"), channels.accepted)
        assertTrue(channels.dropped.isEmpty())
    }

    @Test
    fun `null or blank channels give nothing`() {
        assertEquals(AgentDebugEnv.Channels(emptyList(), emptyList()), AgentDebugEnv.filterChannels(null))
        assertEquals(AgentDebugEnv.Channels(emptyList(), emptyList()), AgentDebugEnv.filterChannels("  , "))
    }

    @Test
    fun `banned channels are dropped`() {
        val channels = AgentDebugEnv.filterChannels("+relay,all,-heap,+snoop,server,file,+d3d")
        assertEquals(listOf("+d3d"), channels.accepted)
        assertEquals(
            listOf("+relay", "all", "-heap", "+snoop", "server", "file").map { AgentDebugEnv.Dropped(it, AgentDebugEnv.REASON_BANNED) },
            channels.dropped,
        )
    }

    @Test
    fun `invalid channels are dropped`() {
        val channels = AgentDebugEnv.filterChannels("d3d;rm -rf,+a_channel_name_longer_than_24,warn+seh,+d3d")
        assertEquals(listOf("+d3d"), channels.accepted)
        assertEquals(
            listOf("d3d;rm -rf", "+a_channel_name_longer_than_24", "warn+seh")
                .map { AgentDebugEnv.Dropped(it, AgentDebugEnv.REASON_INVALID) },
            channels.dropped,
        )
    }

    @Test
    fun `channels beyond the limit are dropped as too_many`() {
        val raw = (1..10).joinToString(",") { "ch$it" }
        val channels = AgentDebugEnv.filterChannels(raw)
        assertEquals(DebugRunParams.MAX_CHANNELS, channels.accepted.size)
        assertEquals((1..8).map { "+ch$it" }, channels.accepted)
        assertEquals(
            listOf("ch9", "ch10").map { AgentDebugEnv.Dropped(it, AgentDebugEnv.REASON_TOO_MANY) },
            channels.dropped,
        )
    }

    @Test
    fun `channels the base WINEDEBUG already sets are dropped as base`() {
        val channels = AgentDebugEnv.filterChannels("-loaddll,+seh,pid,-timestamp,+d3d")
        assertEquals(listOf("+d3d"), channels.accepted)
        assertEquals(
            listOf("-loaddll", "+seh", "pid", "-timestamp").map { AgentDebugEnv.Dropped(it, AgentDebugEnv.REASON_IN_BASE) },
            channels.dropped,
        )
    }

    @Test
    fun `base channels do not take a slot of the limit`() {
        val raw = (listOf("seh", "loaddll", "process", "timestamp", "pid", "tid") + (1..8).map { "ch$it" }).joinToString(",")
        val channels = AgentDebugEnv.filterChannels(raw)
        assertEquals((1..8).map { "+ch$it" }, channels.accepted)
        assertTrue(channels.dropped.none { it.reason == AgentDebugEnv.REASON_TOO_MANY })
        assertEquals(6, channels.dropped.count { it.reason == AgentDebugEnv.REASON_IN_BASE })
    }

    @Test
    fun `duplicate channel names are collapsed without a warning`() {
        val channels = AgentDebugEnv.filterChannels("+d3d,d3d,-d3d")
        assertEquals(listOf("+d3d"), channels.accepted)
        assertTrue(channels.dropped.isEmpty())
    }

    // --- warnings ---

    @Test
    fun `dropped channels become session warnings`() {
        val warnings = AgentDebugEnv.warningsFor(AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+relay,bad!,+d3d"))
        assertEquals(
            listOf("wine_debug_channel_dropped:banned:+relay", "wine_debug_channel_dropped:invalid:bad!"),
            warnings,
        )
    }

    @Test
    fun `a channel that cannot change the base WINEDEBUG becomes a warning`() {
        val options = AgentLaunchOptions(agentDebug = true, wineDebugChannels = "-loaddll")
        assertEquals(listOf("wine_debug_channel_dropped:base:-loaddll"), AgentDebugEnv.warningsFor(options))
        assertEquals(DebugRunParams.BASE_WINEDEBUG, AgentDebugEnv.envFor(options)["WINEDEBUG"])
    }

    @Test
    fun `warning echoes at most 32 characters of a junk item`() {
        val junk = "x".repeat(100) + "!"
        val warning = AgentDebugEnv.warningsFor(AgentLaunchOptions(agentDebug = true, wineDebugChannels = junk)).single()
        assertEquals("wine_debug_channel_dropped:invalid:" + "x".repeat(32), warning)
    }

    @Test
    fun `channels without agent_debug are ignored with a warning`() {
        assertEquals(
            listOf(AgentDebugEnv.WARNING_CHANNELS_IGNORED),
            AgentDebugEnv.warningsFor(AgentLaunchOptions(agentDebug = false, wineDebugChannels = "+d3d")),
        )
        assertTrue(AgentDebugEnv.envFor(AgentLaunchOptions(agentDebug = false, wineDebugChannels = "+d3d")).isEmpty())
    }

    @Test
    fun `no channels means no warnings`() {
        assertTrue(AgentDebugEnv.warningsFor(AgentLaunchOptions(agentDebug = true)).isEmpty())
        assertTrue(AgentDebugEnv.warningsFor(AgentLaunchOptions.NONE).isEmpty())
    }

    // --- env ---

    @Test
    fun `agent_debug env merges channels into the debug-run base`() {
        val env = AgentDebugEnv.envFor(AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+d3d,+relay,+seh"))
        assertEquals(
            mapOf(
                "WINEDEBUG" to "${DebugRunParams.BASE_WINEDEBUG},+d3d",
                "DXVK_LOG_LEVEL" to "info",
                "DXVK_LOG_PATH" to "none",
                "VKD3D_DEBUG" to "warn",
            ),
            env,
        )
    }

    @Test
    fun `agent_debug without channels uses the base WINEDEBUG`() {
        assertEquals(DebugRunParams.BASE_WINEDEBUG, AgentDebugEnv.envFor(AgentLaunchOptions(agentDebug = true))["WINEDEBUG"])
    }

    @Test
    fun `no agent_debug means no env`() {
        assertTrue(AgentDebugEnv.envFor(AgentLaunchOptions.NONE).isEmpty())
    }

    @Test
    fun `agent env wins over WINEDEBUG from the container env`() {
        val envVars = EnvVars()
        envVars.put("WINEDEBUG", "-all") // from the user preference
        envVars.putAll(EnvVars("WINEDEBUG=+all DXVK_LOG_LEVEL=none OTHER=1")) // container.envVars merged later
        AgentDebugEnv.apply(envVars, debug)
        assertEquals("${DebugRunParams.BASE_WINEDEBUG},+d3d,+vulkan", envVars.get("WINEDEBUG"))
        assertEquals("info", envVars.get("DXVK_LOG_LEVEL"))
        assertEquals("1", envVars.get("OTHER"))
    }

    // --- hooks in real call order ---

    @Test
    fun `hooks - agent_debug intent launch gets log file, env and warnings`() {
        val h = hooked()
        val options = AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+d3d,+relay")
        AgentHooks.onLaunchIntent("STEAM_1", options) // MainActivity.handleLaunchIntent
        AgentHooks.onLaunchRequested("STEAM_1") // PluviaMain.launchIntentApp
        val launching = h.tracker.current()!!
        assertEquals(AgentSessionState.LAUNCHING, launching.state)
        assertEquals(options, launching.options)
        assertEquals(listOf("wine_debug_channel_dropped:banned:+relay"), launching.warnings)
        assertEquals(AgentLaunchOptions.NONE, h.store.peek("STEAM_1")) // taken once

        val logFile = AgentHooks.logFileFor(context, "STEAM_1") // setupXEnvironment
        assertEquals(agentLogFile("STEAM_1"), logFile)
        AgentHooks.onStarting("STEAM_1", null, logFile)
        assertEquals(agentLogFile("STEAM_1").absolutePath, h.tracker.current()!!.logFile)
        assertEquals(AgentSessionState.STARTING, h.tracker.current()!!.state)

        val envVars = EnvVars("WINEDEBUG=-all")
        AgentHooks.applyEnv("STEAM_1", envVars)
        assertEquals("${DebugRunParams.BASE_WINEDEBUG},+d3d", envVars.get("WINEDEBUG"))
        assertEquals("warn", envVars.get("VKD3D_DEBUG"))
    }

    @Test
    fun `hooks - options are one-shot and the next ui launch has no agent env`() {
        val h = hooked()
        AgentHooks.onLaunchIntent("STEAM_1", debug)
        AgentHooks.onLaunchRequested("STEAM_1")
        AgentHooks.onStarting("STEAM_1", null, AgentHooks.logFileFor(context, "STEAM_1"))
        AgentHooks.onRunning()
        AgentHooks.onExiting("quick_menu")
        AgentHooks.onExited()

        // UI launch of the same game: setupXEnvironment without an intent
        assertNull(AgentHooks.logFileFor(context, "STEAM_1"))
        AgentHooks.onStarting("STEAM_1", null, null)
        val envVars = EnvVars("WINEDEBUG=-all")
        AgentHooks.applyEnv("STEAM_1", envVars)
        assertEquals("-all", envVars.get("WINEDEBUG"))
        assertFalse(envVars.has("DXVK_LOG_LEVEL"))
        val s = h.tracker.current()!!
        assertFalse(s.options.agentDebug)
        assertNull(s.logFile)
    }

    @Test
    fun `hooks - an extras-less intent overwrites stored options`() {
        val h = hooked()
        AgentHooks.onLaunchIntent("STEAM_1", debug) // deferred agent intent
        AgentHooks.onLaunchIntent("STEAM_1", AgentLaunchOptions.NONE) // a later plain intent
        AgentHooks.onLaunchRequested("STEAM_1")
        assertEquals(AgentLaunchOptions.NONE, h.tracker.current()!!.options)
        assertNull(AgentHooks.logFileFor(context, "STEAM_1"))
    }

    @Test
    fun `hooks - plain intent launch starts a launching session without options`() {
        val h = hooked()
        AgentHooks.onLaunchIntent("STEAM_1", AgentLaunchOptions.NONE)
        AgentHooks.onLaunchRequested("STEAM_1")
        val s = h.tracker.current()!!
        assertEquals(AgentSessionState.LAUNCHING, s.state)
        assertFalse(s.options.agentDebug)
        assertTrue(s.warnings.isEmpty())
    }

    @Test
    fun `hooks - not found clears the stored options`() {
        val h = hooked()
        AgentHooks.onLaunchIntent("STEAM_1", debug)
        AgentHooks.clearLaunchOptions("STEAM_1") // PluviaMain NotFound
        assertEquals(AgentLaunchOptions.NONE, h.store.peek("STEAM_1"))
        assertNull(h.tracker.current())
    }

    @Test
    fun `hooks - another game's setup gets no agent log file`() {
        hooked()
        AgentHooks.onLaunchIntent("STEAM_1", debug)
        AgentHooks.onLaunchRequested("STEAM_1")
        assertNull(AgentHooks.logFileFor(context, "STEAM_2"))
        val envVars = EnvVars()
        AgentHooks.applyEnv("STEAM_2", envVars)
        assertTrue(envVars.isEmpty)
    }

    @Test
    fun `hooks - aborted pre-launch ends the session and a later ui launch has no agent env`() {
        val h = hooked()
        AgentHooks.onLaunchIntent("STEAM_1", debug)
        val sessionId = AgentHooks.onLaunchRequested("STEAM_1") // PluviaMain.launchIntentApp
        val preLaunch = Job()
        AgentHooks.watchPreLaunch(sessionId, preLaunch) { false }
        assertEquals(AgentSessionState.LAUNCHING, h.tracker.current()!!.state)
        preLaunch.complete() // preLaunchApp showed a dialog and returned

        val aborted = h.tracker.current()!!
        assertEquals(sessionId, aborted.sessionId)
        assertEquals(AgentSessionState.EXITED, aborted.state)
        assertEquals(AgentSessionTracker.REASON_LAUNCH_ERROR, aborted.exitReason)

        // the user starts the game from the UI later: a normal launch
        assertNull(AgentHooks.logFileFor(context, "STEAM_1"))
        AgentHooks.onStarting("STEAM_1", null, null)
        val envVars = EnvVars("WINEDEBUG=-all")
        AgentHooks.applyEnv("STEAM_1", envVars)
        assertEquals("-all", envVars.get("WINEDEBUG"))
        val ui = h.tracker.current()!!
        assertNotEquals(sessionId, ui.sessionId)
        assertFalse(ui.options.agentDebug)
    }

    @Test
    fun `hooks - failing pre-launch job ends the session`() {
        val h = hooked()
        val sessionId = AgentHooks.onLaunchRequested("STEAM_1")
        val preLaunch = Job()
        AgentHooks.watchPreLaunch(sessionId, preLaunch) { false }
        preLaunch.completeExceptionally(IllegalStateException("download failed"))
        assertEquals(AgentSessionState.EXITED, h.tracker.current()!!.state)
    }

    @Test
    fun `hooks - pre-launch that never started ends the session`() {
        val h = hooked()
        val sessionId = AgentHooks.onLaunchRequested("STEAM_1")
        AgentHooks.watchPreLaunch(sessionId, null) { false } // e.g. the Steam login wait threw
        assertEquals(AgentSessionState.EXITED, h.tracker.current()!!.state)
    }

    @Test
    fun `hooks - pre-launch that reached the game launch keeps the session`() {
        val h = hooked()
        AgentHooks.onLaunchIntent("STEAM_1", debug)
        val sessionId = AgentHooks.onLaunchRequested("STEAM_1")
        val preLaunch = Job()
        var launched = false
        AgentHooks.watchPreLaunch(sessionId, preLaunch) { launched }
        launched = true // onSuccess -> viewModel.launchApp
        preLaunch.complete()
        assertEquals(AgentSessionState.LAUNCHING, h.tracker.current()!!.state)
        assertEquals(agentLogFile("STEAM_1"), AgentHooks.logFileFor(context, "STEAM_1"))
    }

    @Test
    fun `hooks - repeated intent for a running game keeps its session`() {
        val h = hooked()
        AgentHooks.onLaunchIntent("STEAM_1", debug)
        val sessionId = AgentHooks.onLaunchRequested("STEAM_1")
        AgentHooks.onStarting("STEAM_1", null, AgentHooks.logFileFor(context, "STEAM_1"))
        AgentHooks.onRunning()

        // LAUNCH_GAME again while the game runs (e.g. a GOG game, or Steam not kept alive)
        AgentHooks.onLaunchIntent("STEAM_1", AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+relay"))
        assertNull(AgentHooks.onLaunchRequested("STEAM_1"))
        AgentHooks.watchPreLaunch(null, Job().apply { complete() }) { false }

        val s = h.tracker.current()!!
        assertEquals(sessionId, s.sessionId)
        assertEquals(AgentSessionState.RUNNING, s.state)
        assertEquals(agentLogFile("STEAM_1").absolutePath, s.logFile)
        assertEquals(debug, s.options)
        assertEquals(listOf(AgentSessionTracker.WARNING_LAUNCH_IGNORED), s.warnings) // no channel warnings
        assertEquals(AgentLaunchOptions.NONE, h.store.peek("STEAM_1")) // taken, not left for a later launch
        assertTrue(h.tracker.requestStop()) // sessions.stop still reaches the running game
    }

    @Test
    fun `hooks - failing tracker or store never propagates`() {
        AgentHooks.trackerSource = { throw IllegalStateException("tracker broken") }
        AgentHooks.launchOptionsSource = { throw IllegalStateException("store broken") }
        AgentHooks.onLaunchIntent("STEAM_1", debug)
        assertNull(AgentHooks.onLaunchRequested("STEAM_1"))
        AgentHooks.watchPreLaunch("s1", null) { false }
        AgentHooks.onLaunchAborted("s1")
        AgentHooks.clearLaunchOptions("STEAM_1")
        assertNull(AgentHooks.logFileFor(context, "STEAM_1"))
        AgentHooks.onStarting("STEAM_1", null, agentLogFile("STEAM_1"))
        val envVars = EnvVars("WINEDEBUG=-all")
        AgentHooks.applyEnv("STEAM_1", envVars)
        assertEquals("-all", envVars.get("WINEDEBUG"))
    }
}
