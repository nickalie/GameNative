package app.gamenative.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-launch agent options carried by the `LAUNCH_GAME` intent. [wineDebugChannels] is the raw
 * comma-separated value; filtering against the debug-channel rules happens when the env is built.
 */
data class AgentLaunchOptions(
    val agentDebug: Boolean = false,
    val wineDebugChannels: String? = null,
) {
    val isEmpty: Boolean get() = !agentDebug && wineDebugChannels.isNullOrBlank()

    companion object {
        val NONE = AgentLaunchOptions()
    }
}

/**
 * Side store between the intent handler and the real launch, keyed by app id.
 *
 * Every `LAUNCH_GAME` intent overwrites the entry for its app id (empty options remove it), so options
 * from an earlier intent can never leak into a later, extras-less one. The launch takes the entry once.
 */
class AgentLaunchOptionsStore {
    private val entries = ConcurrentHashMap<String, AgentLaunchOptions>()

    /** Called for every launch intent; empty options clear any stale entry. */
    fun put(appId: String, options: AgentLaunchOptions) {
        if (options.isEmpty) entries.remove(appId) else entries[appId] = options
    }

    /** Returns the options for [appId] and removes them, or [AgentLaunchOptions.NONE]. */
    fun take(appId: String): AgentLaunchOptions = entries.remove(appId) ?: AgentLaunchOptions.NONE

    fun peek(appId: String): AgentLaunchOptions = entries[appId] ?: AgentLaunchOptions.NONE

    /** Drops the entry, e.g. when the game was not found or the launch failed. */
    fun clear(appId: String) {
        entries.remove(appId)
    }
}
