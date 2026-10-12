package app.gamenative.agent

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDispatcherTest {

    private var enabled = true
    private var ready = true

    private fun dispatcher(maxResponseBytes: Int = AgentProtocol.MAX_RESPONSE_BYTES) = AgentDispatcher(
        isEnabled = { enabled },
        isReady = { ready },
        appVersion = "1.3.0",
        packageName = "app.gamenative",
        maxResponseBytes = maxResponseBytes,
    )

    private fun request(command: String, args: JsonObject? = null, version: Int = AgentProtocol.API_VERSION): String =
        AgentProtocol.encode(
            buildJsonObject {
                put("api_version", version)
                put("command", command)
                args?.let { put("args", it) }
            },
        )

    private fun rawRequest(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun parse(response: String): JsonObject = Json.parseToJsonElement(response).jsonObject

    private fun assertError(response: String, code: AgentErrorCode): JsonObject {
        val json = parse(response)
        assertEquals(AgentProtocol.API_VERSION, json["api_version"]!!.jsonPrimitive.int)
        assertFalse(json["ok"]!!.jsonPrimitive.boolean)
        val error = json["error"]!!.jsonObject
        assertEquals(code.name, error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.isNotEmpty())
        return error
    }

    private fun assertOk(response: String): JsonObject {
        val json = parse(response)
        assertEquals(AgentProtocol.API_VERSION, json["api_version"]!!.jsonPrimitive.int)
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertFalse(json.containsKey("error"))
        return json
    }

    // --- success ---

    @Test
    fun capabilities_listsVersionPackageAndRegisteredCommands() {
        val d = dispatcher()
        d.register("games.list") { JsonArray(emptyList()) }
        d.register("device.info") { JsonObject(emptyMap()) }

        val data = assertOk(d.dispatch(request("capabilities")))["data"]!!.jsonObject

        assertEquals(1, data["api_version"]!!.jsonPrimitive.int)
        assertEquals("1.3.0", data["app_version"]!!.jsonPrimitive.content)
        assertEquals("app.gamenative", data["package"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("capabilities", "device.info", "games.list"),
            data["commands"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun routesCommandAndPassesArgs() {
        val d = dispatcher()
        var seen: JsonObject? = null
        d.register("echo") { args ->
            seen = args
            buildJsonObject { put("source", args["source"]!!) }
        }
        d.register("other") { error("must not be called") }

        val response = d.dispatch(request("echo", buildJsonObject { put("source", "STEAM") }))

        assertEquals("STEAM", seen!!["source"]!!.jsonPrimitive.content)
        assertEquals("STEAM", assertOk(response)["data"]!!.jsonObject["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingArgs_becomeEmptyObject() {
        val d = dispatcher()
        var seen: JsonObject? = null
        d.register("noop") { args ->
            seen = args
            JsonNull
        }

        val json = assertOk(d.dispatch(request("noop")))

        assertEquals(JsonObject(emptyMap()), seen)
        assertEquals(JsonNull, json["data"])
    }

    @Test
    fun responseShape_successHasExactlyVersionOkData() {
        val d = dispatcher()
        d.register("n") { JsonPrimitive(42) }

        val json = assertOk(d.dispatch(request("n")))

        assertEquals(setOf("api_version", "ok", "data"), json.keys)
        assertEquals(42, json["data"]!!.jsonPrimitive.int)
    }

    @Test
    fun paddedBase64_isAccepted() {
        val padded = Base64.getUrlEncoder().encodeToString("""{"api_version":1,"command":"capabilities"}""".toByteArray())
        assertOk(dispatcher().dispatch(padded))
    }

    // --- errors ---

    @Test
    fun brokenBase64_isBadArgs() {
        assertError(dispatcher().dispatch("!!!not base64!!!"), AgentErrorCode.BAD_ARGS)
    }

    @Test
    fun missingArg_isBadArgs() {
        assertError(dispatcher().dispatch(null), AgentErrorCode.BAD_ARGS)
        assertError(dispatcher().dispatch(""), AgentErrorCode.BAD_ARGS)
    }

    @Test
    fun brokenJson_isBadArgs() {
        assertError(dispatcher().dispatch(rawRequest("{\"api_version\":1,")), AgentErrorCode.BAD_ARGS)
    }

    @Test
    fun unquotedLiterals_areBadArgs() {
        val d = dispatcher()
        val bad = listOf(
            """{"api_version":1,"command":"capabilities","args":{"x":garbage}}""",
            """{"api_version":1,"command":"capabilities","args":{"x":[1,{"y":nul}]}}""",
            """{"api_version":1,"command":"capabilities","args":{"x":01}}""",
            """{"api_version":1,"command":"capabilities","args":{"x":1.}}""",
            """{"api_version":1,"command":"capabilities","args":{"x":True}}""",
            """{"api_version":1,"command":capabilities}""",
        )
        bad.forEach { assertError(d.dispatch(rawRequest(it)), AgentErrorCode.BAD_ARGS) }
    }

    @Test
    fun validLiterals_areAccepted() {
        val raw = """{"api_version":1,"command":"capabilities",""" +
            """"args":{"a":true,"b":false,"c":null,"d":[0,-1,2.5,1e3,-0.5E-2],"e":{"f":"garbage"}}}"""
        assertOk(dispatcher().dispatch(rawRequest(raw)))
    }

    @Test
    fun nonObjectJson_isBadArgs() {
        assertError(dispatcher().dispatch(rawRequest("[1,2]")), AgentErrorCode.BAD_ARGS)
    }

    @Test
    fun missingOrMistypedFields_areBadArgs() {
        val d = dispatcher()
        assertError(d.dispatch(rawRequest("""{"command":"capabilities"}""")), AgentErrorCode.BAD_ARGS)
        assertError(d.dispatch(rawRequest("""{"api_version":"1","command":"capabilities"}""")), AgentErrorCode.BAD_ARGS)
        assertError(d.dispatch(rawRequest("""{"api_version":1}""")), AgentErrorCode.BAD_ARGS)
        assertError(d.dispatch(rawRequest("""{"api_version":1,"command":7}""")), AgentErrorCode.BAD_ARGS)
        assertError(d.dispatch(rawRequest("""{"api_version":1,"command":"capabilities","args":[]}""")), AgentErrorCode.BAD_ARGS)
    }

    @Test
    fun oversizedRequest_isBadArgs() {
        val big = "x".repeat(AgentProtocol.MAX_REQUEST_BYTES + 1)
        val response = dispatcher().dispatch(rawRequest("""{"api_version":1,"command":"capabilities","args":{"p":"$big"}}"""))
        assertError(response, AgentErrorCode.BAD_ARGS)
    }

    @Test
    fun foreignVersion_isUnsupported() {
        val error = assertError(dispatcher().dispatch(request("capabilities", version = 2)), AgentErrorCode.UNSUPPORTED_API_VERSION)
        assertEquals(listOf(1), error["details"]!!.jsonObject["supported"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun unknownCommand() {
        assertError(dispatcher().dispatch(request("games.nuke")), AgentErrorCode.UNKNOWN_COMMAND)
    }

    @Test
    fun disabled_rejectsEverythingIncludingCapabilities() {
        enabled = false
        val d = dispatcher()
        assertError(d.dispatch(request("capabilities")), AgentErrorCode.DISABLED)
        assertError(d.dispatch("garbage"), AgentErrorCode.DISABLED)
    }

    @Test
    fun notReady_winsOverDisabledAndDoesNotQueryToggle() {
        ready = false
        val d = AgentDispatcher(
            isEnabled = { error("toggle must not be read before readiness") },
            isReady = { ready },
            appVersion = "1.3.0",
            packageName = "app.gamenative",
        )
        assertError(d.dispatch(request("capabilities")), AgentErrorCode.NOT_READY)
    }

    @Test
    fun handlerException_becomesInternal() {
        val d = dispatcher()
        d.register("boom") { throw IllegalStateException("kaput") }

        val error = assertError(d.dispatch(request("boom")), AgentErrorCode.INTERNAL)

        assertTrue(error["message"]!!.jsonPrimitive.content.contains("kaput"))
    }

    @Test
    fun handlerError_isCapturedToo() {
        val d = dispatcher()
        d.register("oom") { throw StackOverflowError("deep") }
        assertError(d.dispatch(request("oom")), AgentErrorCode.INTERNAL)
    }

    @Test
    fun throwingToggle_becomesInternal() {
        val d = AgentDispatcher(
            isEnabled = { throw UninitializedPropertyAccessException("dataStore") },
            isReady = { true },
            appVersion = "1.3.0",
            packageName = "app.gamenative",
        )
        assertError(d.dispatch(request("capabilities")), AgentErrorCode.INTERNAL)
    }

    @Test
    fun agentException_keepsCodeAndDetails() {
        val d = dispatcher()
        d.register("find") {
            throw AgentException(AgentErrorCode.GAME_NOT_FOUND, "no such game", buildJsonObject { put("app_id", "STEAM_1") })
        }

        val error = assertError(d.dispatch(request("find")), AgentErrorCode.GAME_NOT_FOUND)

        assertEquals("no such game", error["message"]!!.jsonPrimitive.content)
        assertEquals("STEAM_1", error["details"]!!.jsonObject["app_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorWithoutDetails_omitsDetailsField() {
        val error = assertError(dispatcher().dispatch(request("missing")), AgentErrorCode.UNKNOWN_COMMAND)
        assertEquals(setOf("code", "message"), error.keys)
    }

    @Test
    fun oversizedResponse_isReplacedWithResponseTooLarge() {
        val d = dispatcher(maxResponseBytes = 1024)
        d.register("big") { JsonPrimitive("y".repeat(2048)) }
        d.register("small") { JsonPrimitive("y".repeat(100)) }

        val error = assertError(d.dispatch(request("big")), AgentErrorCode.RESPONSE_TOO_LARGE)
        assertEquals(1024, error["details"]!!.jsonObject["limit"]!!.jsonPrimitive.int)
        assertTrue(error["details"]!!.jsonObject["size"]!!.jsonPrimitive.int > 2048)
        assertOk(d.dispatch(request("small")))
    }

    @Test
    fun responseCap_countsUtf8Bytes() {
        val d = dispatcher(maxResponseBytes = 1024)
        // 600 Cyrillic chars = 1200 UTF-8 bytes, although the string is under 1024 chars.
        d.register("ru") { JsonPrimitive("я".repeat(600)) }
        assertError(d.dispatch(request("ru")), AgentErrorCode.RESPONSE_TOO_LARGE)
    }

    @Test
    fun defaultResponseCapIs256Kb() {
        val d = dispatcher()
        d.register("big") { JsonPrimitive("z".repeat(AgentProtocol.MAX_RESPONSE_BYTES)) }
        assertError(d.dispatch(request("big")), AgentErrorCode.RESPONSE_TOO_LARGE)
    }
}
