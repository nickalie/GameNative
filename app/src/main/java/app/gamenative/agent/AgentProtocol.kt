package app.gamenative.agent

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Error codes returned by the agent API. Names are part of the wire protocol. */
enum class AgentErrorCode {
    UNKNOWN_COMMAND,
    BAD_ARGS,
    UNSUPPORTED_API_VERSION,
    GAME_NOT_FOUND,
    NO_CONTAINER,
    MISSING_COMPONENTS,
    CONTAINER_INVALID,
    NO_ACTIVE_SESSION,
    SESSION_ACTIVE,
    NO_BACKUP,
    NOT_READY,
    DISABLED,
    RESPONSE_TOO_LARGE,
    INTERNAL,
}

data class AgentError(
    val code: AgentErrorCode,
    val message: String,
    val details: JsonElement? = null,
)

/** Thrown by command handlers (and the request decoder) to produce a structured error response. */
class AgentException(
    val code: AgentErrorCode,
    message: String,
    val details: JsonElement? = null,
) : Exception(message) {
    fun toError(): AgentError = AgentError(code, message ?: code.name, details)
}

data class AgentRequest(
    val apiVersion: Int,
    val command: String,
    val args: JsonObject,
)

sealed class AgentResponse {
    abstract fun toJson(): JsonObject

    fun encode(): String = toJson().toString()

    data class Success(val data: JsonElement) : AgentResponse() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("api_version", AgentProtocol.API_VERSION)
            put("ok", true)
            put("data", data)
        }
    }

    data class Failure(val error: AgentError) : AgentResponse() {
        override fun toJson(): JsonObject = buildJsonObject {
            put("api_version", AgentProtocol.API_VERSION)
            put("ok", false)
            put(
                "error",
                buildJsonObject {
                    put("code", error.code.name)
                    put("message", error.message)
                    error.details?.let { put("details", it) }
                },
            )
        }
    }

    companion object {
        fun ok(data: JsonElement = JsonNull): AgentResponse = Success(data)

        fun error(code: AgentErrorCode, message: String, details: JsonElement? = null): AgentResponse =
            Failure(AgentError(code, message, details))
    }
}

object AgentProtocol {
    const val API_VERSION = 1

    /** Upper bound for the decoded request JSON. */
    const val MAX_REQUEST_BYTES = 64 * 1024

    /** Upper bound for the encoded response, well under the binder transaction limit. */
    const val MAX_RESPONSE_BYTES = 256 * 1024

    // base64 expands by 4/3; allow padding slack on top.
    private const val MAX_ENCODED_CHARS = (MAX_REQUEST_BYTES + 2) / 3 * 4 + 4

    private val json = Json { isLenient = false }

    /**
     * Decodes a base64url(JSON) request. The API version is parsed but not checked here: the
     * dispatcher owns the version check so it can report UNSUPPORTED_API_VERSION.
     */
    fun decode(arg: String?): AgentRequest {
        if (arg.isNullOrBlank()) throw badArgs("missing request argument")
        val trimmed = arg.trim()
        if (trimmed.length > MAX_ENCODED_CHARS) throw badArgs("request exceeds $MAX_REQUEST_BYTES bytes")
        val bytes = try {
            Base64.getUrlDecoder().decode(trimmed)
        } catch (e: IllegalArgumentException) {
            throw badArgs("request is not valid base64url")
        }
        if (bytes.size > MAX_REQUEST_BYTES) throw badArgs("request exceeds $MAX_REQUEST_BYTES bytes")
        val root = try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException.
            throw badArgs("request is not valid JSON")
        }
        if (root !is JsonObject) throw badArgs("request must be a JSON object")

        val version = (root["api_version"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.intOrNull
            ?: throw badArgs("api_version must be an integer")
        val command = (root["command"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw badArgs("command must be a non-empty string")
        val args = when (val raw = root["args"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> throw badArgs("args must be a JSON object")
        }
        return AgentRequest(version, command, args)
    }

    /** Encodes a request the way the host CLI does; used by tests and documentation examples. */
    fun encode(request: JsonObject): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(request.toString().toByteArray(Charsets.UTF_8))

    private fun badArgs(message: String) = AgentException(AgentErrorCode.BAD_ARGS, message)
}
