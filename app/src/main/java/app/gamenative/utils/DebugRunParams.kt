package app.gamenative.utils

/** WINEDEBUG rules shared by debug runs and `agent_debug` launches. */
object DebugRunParams {
    const val BASE_WINEDEBUG = "warn+seh,+loaddll,+process,+timestamp,+pid,+tid"

    internal val CHANNEL_PATTERN = Regex("^[+-]?[a-z0-9_]{1,24}$")
    internal val BANNED_CHANNELS = setOf("all", "relay", "server", "heap", "snoop", "file")
    internal const val MAX_CHANNELS = 8

    fun channelName(item: String): String =
        item.substring(maxOf(item.lastIndexOf('+'), item.lastIndexOf('-')) + 1)

    fun mergeWineDebug(base: String, extra: List<String>): String {
        val items = base.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val names = items.map { channelName(it) }.toMutableSet()
        for (item in extra) {
            val name = channelName(item)
            if (name.isEmpty() || name == "all" || name in names) continue
            items += item
            names += name
        }
        return items.joinToString(",")
    }
}
