package app.gamenative.agent.commands

import app.gamenative.agent.AgentErrorCode
import app.gamenative.agent.AgentException
import app.gamenative.data.GameSource
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Argument parsing shared by the command handlers. Every malformed argument becomes `BAD_ARGS`. */
internal object CommandArgs {

    // Container ids are used as directory names, so only the exact `<SOURCE>_<digits>` shape is accepted.
    private val APP_ID = Regex("^(${GameSource.entries.joinToString("|") { it.name }})_[0-9]{1,10}$")

    fun badArgs(message: String, details: JsonElement? = null) = AgentException(AgentErrorCode.BAD_ARGS, message, details)

    /** The string argument [name], or `null` when it is absent or JSON `null`. */
    fun optString(args: JsonObject, name: String): String? {
        val raw = args[name]
        if (raw == null || raw is JsonNull) return null
        val primitive = raw as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw badArgs("$name must be a string")
        return primitive.content
    }

    fun requireAppId(args: JsonObject): String {
        val appId = optString(args, "app_id")?.trim()
        if (appId.isNullOrEmpty()) throw badArgs("app_id is required")
        if (!APP_ID.matches(appId)) throw badArgs("app_id must look like STEAM_123 (one of ${sourceNames()})")
        return appId
    }

    /** Parses a `game_source` name case-insensitively. */
    fun gameSource(value: String): GameSource =
        GameSource.entries.firstOrNull { it.name == value.trim().uppercase(Locale.ROOT) }
            ?: throw badArgs("unknown source: ${value.take(64)}", JsonObject(mapOf("sources" to JsonArray(sourceNames()))))

    private fun sourceNames(): List<JsonPrimitive> = GameSource.entries.map { JsonPrimitive(it.name) }
}
