package app.gamenative.agent

import app.gamenative.utils.DebugRunParams
import com.winlator.core.envvars.EnvVars

/**
 * Builds the verbose-logging env of an `agent_debug` launch. Channels follow the same rules as the
 * built-in debug run ([DebugRunParams]): pattern, banned channels, at most
 * [DebugRunParams.MAX_CHANNELS]. Rejected channels never block the launch; they become session warnings.
 */
object AgentDebugEnv {
    const val WARNING_CHANNEL_DROPPED = "wine_debug_channel_dropped"
    const val WARNING_CHANNELS_IGNORED = "wine_debug_channels_ignored_without_agent_debug"

    const val REASON_INVALID = "invalid"
    const val REASON_BANNED = "banned"
    const val REASON_TOO_MANY = "too_many"

    /** Longest raw item echoed back in a warning, so a junk extra cannot bloat the session. */
    private const val MAX_ITEM_IN_WARNING = 32

    data class Dropped(val item: String, val reason: String)

    data class Channels(val accepted: List<String>, val dropped: List<Dropped>)

    /** Splits the comma-separated extra and applies the debug-run channel rules, keeping input order. */
    fun filterChannels(raw: String?): Channels {
        val accepted = mutableListOf<String>()
        val dropped = mutableListOf<Dropped>()
        raw.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { item ->
            val reason = when {
                !DebugRunParams.CHANNEL_PATTERN.matches(item) -> REASON_INVALID
                DebugRunParams.channelName(item) in DebugRunParams.BANNED_CHANNELS -> REASON_BANNED
                else -> null
            }
            if (reason != null) {
                dropped += Dropped(item, reason)
                return@forEach
            }
            val channel = if (item.startsWith("+") || item.startsWith("-")) item else "+$item"
            val name = DebugRunParams.channelName(channel)
            if (accepted.any { DebugRunParams.channelName(it) == name }) return@forEach
            if (accepted.size >= DebugRunParams.MAX_CHANNELS) {
                dropped += Dropped(item, REASON_TOO_MANY)
            } else {
                accepted += channel
            }
        }
        return Channels(accepted, dropped)
    }

    /** Session warnings for [options]: dropped channels, or channels sent without `agent_debug`. */
    fun warningsFor(options: AgentLaunchOptions): List<String> {
        if (options.wineDebugChannels.isNullOrBlank()) return emptyList()
        if (!options.agentDebug) return listOf(WARNING_CHANNELS_IGNORED)
        return filterChannels(options.wineDebugChannels).dropped.map { (item, reason) ->
            "$WARNING_CHANNEL_DROPPED:$reason:${item.take(MAX_ITEM_IN_WARNING)}"
        }
    }

    /** The env an `agent_debug` launch sets; empty when the launch has no `agent_debug`. */
    fun envFor(options: AgentLaunchOptions): Map<String, String> {
        if (!options.agentDebug) return emptyMap()
        val channels = filterChannels(options.wineDebugChannels).accepted
        return linkedMapOf(
            "WINEDEBUG" to DebugRunParams.mergeWineDebug(DebugRunParams.BASE_WINEDEBUG, channels),
            "DXVK_LOG_LEVEL" to "info",
            "DXVK_LOG_PATH" to "none",
            "VKD3D_DEBUG" to "warn",
        )
    }

    /** Writes [envFor] over [envVars]; called after the container env is merged, so it wins over it. */
    fun apply(envVars: EnvVars, options: AgentLaunchOptions) {
        envFor(options).forEach { (name, value) -> envVars.put(name, value) }
    }
}
