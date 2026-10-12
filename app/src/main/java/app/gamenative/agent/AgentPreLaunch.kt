package app.gamenative.agent

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/**
 * Follows the `launching` session of an agent intent launch through one `preLaunchApp` run.
 *
 * `preLaunchApp` returns its job and stops on many paths without telling anyone. A run ends in one of three ways:
 * - it reaches the game launch ([onSuccess]): `setupXEnvironment` continues the session;
 * - it stops at a dialog whose confirm starts `preLaunchApp` again (sync conflict, pending operations, "play
 *   anyway"; [dialogs]): the session is paused, the confirm continues it through [resume] and a dismiss, or the
 *   dialog being replaced, ends it through [abortPaused];
 * - anything else (an error dialog, a failed download, an exception, cancellation): the session ends with
 *   `exit_reason=launch_error` as soon as the job completes, so its options cannot leak into a later UI launch.
 *
 * A run with a null session id (a UI launch, or a repeated intent that kept a live session) does nothing. The
 * `launching` timeout of the tracker stays the backstop for a dialog left open.
 */
class AgentPreLaunch internal constructor(private val sessionId: String?) {
    private val launched = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    /** Wraps the pre-launch success callback; reaching it hands the session over to the game launch. */
    fun onSuccess(block: (Context, String) -> Unit): (Context, String) -> Unit = { context, appId ->
        launched.set(true)
        block(context, appId)
    }

    /**
     * Wraps the dialog setter of the pre-launch: a dialog for which [relaunches] is true pauses the session
     * instead of letting the run end it. The dialog is set first, so a check of the visible dialog never sees
     * the pause before its dialog.
     */
    fun <T> dialogs(setDialog: (T) -> Unit, relaunches: (T) -> Boolean): (T) -> Unit = { state ->
        setDialog(state)
        if (sessionId != null && relaunches(state)) {
            paused.set(true)
            pause(sessionId)
        }
    }

    /** Starts the pre-launch with [start] (null when it never started) and follows the job it returns. */
    inline fun follow(start: () -> Job?) {
        var job: Job? = null
        try {
            job = start()
        } finally {
            watch(job)
        }
    }

    /** Ends the session once [job] completes neither launched nor paused; a null [job] never started. */
    fun watch(job: Job?) {
        val id = sessionId ?: return
        if (job == null) {
            AgentHooks.onLaunchAborted(id)
            return
        }
        job.invokeOnCompletion { if (!launched.get() && !paused.get()) AgentHooks.onLaunchAborted(id) }
    }

    companion object {
        /** The session paused at the relaunching dialog currently shown; there is one message dialog at a time. */
        private val pausedSession = AtomicReference<String?>(null)

        /** A run for the session an intent launch just started ([AgentHooks.onLaunchRequested]). */
        fun start(sessionId: String?): AgentPreLaunch = AgentPreLaunch(sessionId)

        /** The relaunching dialog was confirmed: the pre-launch it starts continues the paused session. */
        fun resume(): AgentPreLaunch = AgentPreLaunch(pausedSession.getAndSet(null))

        /** The relaunching dialog is gone without a relaunch (dismissed or replaced): ends the paused session. */
        fun abortPaused() {
            pausedSession.getAndSet(null)?.let(AgentHooks::onLaunchAborted)
        }

        private fun pause(sessionId: String) {
            val previous = pausedSession.getAndSet(sessionId)
            if (previous != null && previous != sessionId) AgentHooks.onLaunchAborted(previous)
        }

        internal fun pausedSessionId(): String? = pausedSession.get()

        internal fun resetForTest() = pausedSession.set(null)
    }
}
