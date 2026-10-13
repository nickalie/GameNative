package app.gamenative.agent.commands

import app.gamenative.agent.AgentDispatcher
import app.gamenative.data.GameSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `stores.list`: every store (game source) with its Settings → Stores switch.
 *
 * `stores.set {source, enabled}`: turns a store on or off the way the Settings switch does: the store's
 * service starts (when signed in) or stops, and nothing is logged out or deleted.
 */
class StoreCommands(
    private val isEnabled: (GameSource) -> Boolean,
    private val setEnabled: (GameSource, Boolean) -> Unit,
) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(LIST) { list() }
        dispatcher.register(SET) { set(it) }
    }

    fun list(): JsonElement = buildJsonArray {
        GameSource.entries.forEach { source ->
            add(
                buildJsonObject {
                    put("source", source.name)
                    put("enabled", isEnabled(source))
                },
            )
        }
    }

    fun set(args: JsonObject): JsonElement {
        val source = CommandArgs.gameSource(CommandArgs.optString(args, "source") ?: throw CommandArgs.badArgs("source is required"))
        val enabled = CommandArgs.optBoolean(args, "enabled") ?: throw CommandArgs.badArgs("enabled is required")
        val changed = isEnabled(source) != enabled
        if (changed) setEnabled(source, enabled)
        return buildJsonObject {
            put("source", source.name)
            put("enabled", enabled)
            put("changed", changed)
        }
    }

    companion object {
        const val LIST = "stores.list"
        const val SET = "stores.set"
    }
}
