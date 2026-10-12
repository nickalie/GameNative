package app.gamenative.agent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/** A single agent command. Throw [AgentException] for expected failures; anything else becomes INTERNAL. */
fun interface AgentCommandHandler {
    fun handle(args: JsonObject): JsonElement
}

/**
 * Routes decoded agent requests to registered command handlers and always produces a response
 * envelope: the caller (the content provider) never sees an exception from here.
 *
 * Every dependency is injected so the dispatcher can be tested without PluviaApp or Hilt.
 */
class AgentDispatcher(
    private val isEnabled: () -> Boolean,
    private val isReady: () -> Boolean,
    private val appVersion: String,
    private val packageName: String,
    private val maxResponseBytes: Int = AgentProtocol.MAX_RESPONSE_BYTES,
) {
    private val handlers = ConcurrentHashMap<String, AgentCommandHandler>()

    init {
        register(CAPABILITIES) { capabilities() }
    }

    fun register(command: String, handler: AgentCommandHandler) {
        require(command.isNotBlank()) { "command name must not be blank" }
        handlers[command] = handler
    }

    fun commands(): List<String> = handlers.keys.sorted()

    /** Handles a raw base64url(JSON) request and returns the encoded JSON response. */
    fun dispatch(arg: String?): String = cap(handle(arg))

    private fun handle(arg: String?): AgentResponse = try {
        when {
            !isReady() -> AgentResponse.error(AgentErrorCode.NOT_READY, "app is still starting")
            !isEnabled() -> AgentResponse.error(AgentErrorCode.DISABLED, "Agent API (ADB) is disabled in settings")
            else -> route(AgentProtocol.decode(arg))
        }
    } catch (e: AgentException) {
        AgentResponse.Failure(e.toError())
    } catch (e: Throwable) {
        internal(e)
    }

    private fun route(request: AgentRequest): AgentResponse {
        if (request.apiVersion != AgentProtocol.API_VERSION) {
            return AgentResponse.error(
                AgentErrorCode.UNSUPPORTED_API_VERSION,
                "api_version ${request.apiVersion} is not supported",
                buildJsonObject { put("supported", buildJsonArray { add(AgentProtocol.API_VERSION) }) },
            )
        }
        val handler = handlers[request.command]
            ?: return AgentResponse.error(AgentErrorCode.UNKNOWN_COMMAND, "unknown command: ${request.command.take(64)}")
        return AgentResponse.ok(handler.handle(request.args))
    }

    private fun cap(response: AgentResponse): String {
        val encoded = try {
            response.encode()
        } catch (e: Throwable) {
            return internal(e).encode()
        }
        val size = encoded.toByteArray(Charsets.UTF_8).size
        if (size <= maxResponseBytes) return encoded
        return AgentResponse.error(
            AgentErrorCode.RESPONSE_TOO_LARGE,
            "response is $size bytes, limit is $maxResponseBytes",
            buildJsonObject {
                put("size", size)
                put("limit", maxResponseBytes)
            },
        ).encode()
    }

    private fun internal(e: Throwable): AgentResponse {
        Timber.w(e, "Agent API command failed")
        val message = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(MAX_INTERNAL_MESSAGE)
        return AgentResponse.error(AgentErrorCode.INTERNAL, message)
    }

    private fun capabilities(): JsonElement = buildJsonObject {
        put("api_version", AgentProtocol.API_VERSION)
        put("app_version", appVersion)
        put("package", packageName)
        put("commands", buildJsonArray { commands().forEach { add(it) } })
    }

    companion object {
        const val CAPABILITIES = "capabilities"
        private const val MAX_INTERNAL_MESSAGE = 500
    }
}
