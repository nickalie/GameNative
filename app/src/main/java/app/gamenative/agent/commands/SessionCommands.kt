package app.gamenative.agent.commands

import app.gamenative.agent.AgentDispatcher
import app.gamenative.agent.AgentSessionTracker
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** `sessions.*` commands over the session tracker. */
class SessionCommands(private val tracker: AgentSessionTracker) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(CURRENT) { current() }
    }

    /** The active session, or the last finished one; `null` if no session ever existed. */
    fun current(): JsonElement = tracker.current()?.toJson() ?: JsonNull

    companion object {
        const val CURRENT = "sessions.current"
    }
}
