package app.gamenative.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.envvars.EnvVars
import java.io.File
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `launching` session of an agent intent launch through `preLaunchApp` runs, as PluviaMain wires them:
 * `launchIntentApp` starts a run, the confirm handlers of relaunching dialogs resume it, and the dialog effect
 * aborts it once a relaunching dialog is gone without its confirm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentPreLaunchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val debug = AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+d3d")
    private val tracker = AgentSessionTracker()
    private val store = AgentLaunchOptionsStore()

    /** Dialogs the pre-launch shows; "conflict" stands for a relaunching one (sync conflict, play anyway). */
    private val shown = mutableListOf<String>()
    private val relaunches: (String) -> Boolean = { it == CONFLICT }

    @Before
    fun setUp() {
        AgentHooks.trackerSource = { tracker }
        AgentHooks.launchOptionsSource = { store }
        AgentPreLaunch.resetForTest()
    }

    @After
    fun tearDown() {
        AgentHooks.trackerSource = { AgentRuntime.tracker }
        AgentHooks.launchOptionsSource = { AgentRuntime.launchOptions }
        AgentPreLaunch.resetForTest()
    }

    /** One pre-launch run that ends at [dialog] (null: reaches the game launch). */
    private fun preLaunch(run: AgentPreLaunch, dialog: String?) {
        val job = Job()
        run.watch(job)
        if (dialog == null) run.onSuccess { _, _ -> }(context, APP) else run.dialogs(shown::add, relaunches)(dialog)
        job.complete()
    }

    private fun intentLaunch(): String {
        AgentHooks.onLaunchIntent(APP, debug)
        return AgentHooks.onLaunchRequested(APP)!!
    }

    private fun assertAgentLaunchContinues(sessionId: String) {
        assertEquals(File(File(context.getExternalFilesDir(null), "wine_logs"), "agent_$APP.log"), AgentHooks.logFileFor(context, APP))
        AgentHooks.onStarting(APP, null, AgentHooks.logFileFor(context, APP))
        val envVars = EnvVars("WINEDEBUG=-all")
        AgentHooks.applyEnv(APP, envVars)
        val s = tracker.current()!!
        assertEquals(sessionId, s.sessionId)
        assertEquals(AgentSessionState.STARTING, s.state)
        assertEquals(debug, s.options)
        assertTrue(envVars.get("WINEDEBUG").contains("+d3d"))
    }

    private fun assertAborted(sessionId: String) {
        val s = tracker.current()!!
        assertEquals(sessionId, s.sessionId)
        assertEquals(AgentSessionState.EXITED, s.state)
        assertEquals(AgentSessionTracker.REASON_LAUNCH_ERROR, s.exitReason)
        assertNull(AgentHooks.logFileFor(context, APP)) // a later UI launch gets no agent_debug
    }

    @Test
    fun `relaunching dialog keeps the session and the confirmed launch continues it with agent_debug`() {
        val sessionId = intentLaunch()
        preLaunch(AgentPreLaunch.start(sessionId), CONFLICT) // e.g. SYNC_CONFLICT, preLaunchApp returned
        assertEquals(listOf(CONFLICT), shown)
        assertEquals(AgentSessionState.LAUNCHING, tracker.current()!!.state)
        assertEquals(sessionId, AgentPreLaunch.pausedSessionId())

        preLaunch(AgentPreLaunch.resume(), null) // the user picks Remote/Local, the relaunch reaches launchApp
        assertNull(AgentPreLaunch.pausedSessionId())
        AgentPreLaunch.abortPaused() // the dialog closes after the confirm
        assertAgentLaunchContinues(sessionId)
    }

    @Test
    fun `relaunching dialog dismissed ends the session`() {
        val sessionId = intentLaunch()
        preLaunch(AgentPreLaunch.start(sessionId), CONFLICT)
        AgentPreLaunch.abortPaused() // Cancel / back: the dialog is gone without its confirm
        assertAborted(sessionId)
        assertNull(AgentPreLaunch.pausedSessionId())
    }

    @Test
    fun `non-relaunching dialog ends the session when the pre-launch returns`() {
        val sessionId = intentLaunch()
        preLaunch(AgentPreLaunch.start(sessionId), "sync_fail")
        assertAborted(sessionId)
        assertNull(AgentPreLaunch.pausedSessionId())
    }

    @Test
    fun `confirmed relaunch that then fails ends the session`() {
        val sessionId = intentLaunch()
        preLaunch(AgentPreLaunch.start(sessionId), CONFLICT)
        preLaunch(AgentPreLaunch.resume(), "sync_fail")
        assertAborted(sessionId)
    }

    @Test
    fun `confirmed relaunch that pauses again keeps the session until the next confirm`() {
        val sessionId = intentLaunch()
        preLaunch(AgentPreLaunch.start(sessionId), CONFLICT) // SYNC_IN_PROGRESS
        preLaunch(AgentPreLaunch.resume(), CONFLICT) // launch anyway -> PENDING_UPLOAD
        assertEquals(AgentSessionState.LAUNCHING, tracker.current()!!.state)
        preLaunch(AgentPreLaunch.resume(), null)
        assertAgentLaunchContinues(sessionId)
    }

    @Test
    fun `confirm before the paused job completes keeps the session`() {
        val sessionId = intentLaunch()
        val run = AgentPreLaunch.start(sessionId)
        val job = Job()
        run.watch(job)
        run.dialogs(shown::add, relaunches)(CONFLICT)
        val resumed = AgentPreLaunch.resume() // tapped before preLaunchApp's job finished returning
        job.complete()
        assertEquals(AgentSessionState.LAUNCHING, tracker.current()!!.state)
        preLaunch(resumed, null)
        assertAgentLaunchContinues(sessionId)
    }

    @Test
    fun `follow ends the session when the pre-launch never starts`() {
        val sessionId = intentLaunch()
        runCatching { AgentPreLaunch.start(sessionId).follow { throw IllegalStateException("Steam login failed") } }
        assertAborted(sessionId)
    }

    @Test
    fun `follow watches the job the pre-launch returns`() {
        val sessionId = intentLaunch()
        val job = Job()
        AgentPreLaunch.start(sessionId).follow { job }
        assertEquals(AgentSessionState.LAUNCHING, tracker.current()!!.state)
        job.complete()
        assertAborted(sessionId)
    }

    @Test
    fun `ui launch runs neither pause nor end a session`() {
        val sessionId = intentLaunch()
        preLaunch(AgentPreLaunch.start(null), CONFLICT) // a UI launch at a sync conflict
        assertNull(AgentPreLaunch.pausedSessionId())
        preLaunch(AgentPreLaunch.resume(), "sync_fail") // its confirm resumes nothing
        AgentPreLaunch.abortPaused()
        assertEquals(sessionId, tracker.current()!!.sessionId)
        assertEquals(AgentSessionState.LAUNCHING, tracker.current()!!.state)
    }

    @Test
    fun `newer intent launch paused at a dialog takes over the slot`() {
        val first = intentLaunch()
        preLaunch(AgentPreLaunch.start(first), CONFLICT)
        val second = intentLaunch() // LAUNCH_GAME again while the dialog is up: replaces the launching session
        assertNotEquals(first, second)
        preLaunch(AgentPreLaunch.start(second), CONFLICT)
        assertEquals(second, AgentPreLaunch.pausedSessionId())
        preLaunch(AgentPreLaunch.resume(), null)
        assertAgentLaunchContinues(second)
    }

    @Test
    fun `failing tracker never propagates`() {
        AgentHooks.trackerSource = { throw IllegalStateException("tracker broken") }
        preLaunch(AgentPreLaunch.start("s1"), CONFLICT)
        AgentPreLaunch.abortPaused()
        preLaunch(AgentPreLaunch.start("s1"), "sync_fail")
        AgentPreLaunch.start("s1").watch(null)
        assertNull(AgentPreLaunch.pausedSessionId())
    }

    private companion object {
        const val APP = "STEAM_1"
        const val CONFLICT = "conflict"
    }
}
