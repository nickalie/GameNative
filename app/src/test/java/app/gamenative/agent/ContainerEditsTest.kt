package app.gamenative.agent

import android.app.Application
import app.gamenative.api.SupportSuggestion
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContainerEditsTest {

    private val live = ContainerData(
        executablePath = "Game\\game.exe",
        envVars = "FOO=1 BAR=2",
        dxwrapper = "dxvk",
        dxwrapperConfig = "version=2.4.1,async=1",
        graphicsDriverConfig = "version=turnip25.1.0,presentMode=mailbox",
        containerVariant = Container.BIONIC,
        wineVersion = "proton-9.0-arm64ec",
        startupSelection = 1,
        unpackFiles = false,
    )

    private fun request(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun edit(json: String): ContainerData = ContainerEdits.apply(live, ContainerEdits.parse(request(json)))

    private fun rejected(json: String): List<JsonObject> {
        val error = assertThrows(AgentException::class.java) { ContainerEdits.parse(request(json)) }
        assertEquals(AgentErrorCode.BAD_ARGS, error.code)
        return error.details!!.jsonObject["rejected"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun kv(value: String): Map<String, String> = buildMap { for (pair in KeyValueSet(value)) put(pair[0], pair[1]) }

    @Test
    fun dottedKeysUpdateOneEntryAndKeepTheRest() {
        val updated = edit("""{"values":{"dxwrapperConfig.version":"2.5","graphicsDriverConfig.version":"turnip26.0.0"}}""")

        assertEquals(mapOf("version" to "2.5", "async" to "1"), kv(updated.dxwrapperConfig))
        assertEquals(mapOf("version" to "turnip26.0.0", "presentMode" to "mailbox"), kv(updated.graphicsDriverConfig))
        assertEquals(
            listOf("graphicsDriverConfig.version", "dxwrapperConfig.version"),
            ContainerEdits.changedFields(live, updated),
        )
    }

    @Test
    fun envSetAndUnset() {
        val updated = edit("""{"env":{"set":{"DXVK_HUD":"fps","BAR":"3"},"unset":["FOO"]}}""")

        val env = EnvVars(updated.envVars)
        assertFalse(env.has("FOO"))
        assertEquals("3", env.get("BAR"))
        assertEquals("fps", env.get("DXVK_HUD"))
        assertEquals(
            setOf("envVars.FOO", "envVars.BAR", "envVars.DXVK_HUD"),
            ContainerEdits.changedFields(live, updated).toSet(),
        )
    }

    @Test
    fun typedValuesAreCoerced() {
        val updated = edit("""{"values":{"startupSelection":2,"useLegacyDRM":true,"dxwrapper":"vkd3d"}}""")

        assertEquals(2.toByte(), updated.startupSelection)
        assertTrue(updated.useLegacyDRM)
        assertEquals("vkd3d", updated.dxwrapper)
        assertEquals(setOf("startupSelection", "useLegacyDRM", "dxwrapper"), ContainerEdits.changedFields(live, updated).toSet())
    }

    @Test
    fun winedebugEnvIsRejected() {
        val rejected = rejected("""{"env":{"set":{"WINEDEBUG":"+all"}}}""")

        assertEquals(1, rejected.size)
        assertEquals(SupportSuggestion.ENV_KEY, rejected[0].str("key"))
        assertEquals("WINEDEBUG", rejected[0].str("name"))
        assertEquals(ContainerEdits.REASON_BLOCKED_ENV, rejected[0].str("reason"))
        assertEquals(ContainerEdits.REASON_BLOCKED_ENV, rejected("""{"env":{"unset":["winedebug"]}}""")[0].str("reason"))
    }

    @Test
    fun containerVariantIsReadOnly() {
        val rejected = rejected("""{"values":{"containerVariant":"glibc"}}""")

        assertEquals(ContainerEdits.REASON_READ_ONLY, rejected.single().str("reason"))
    }

    @Test
    fun everyRejectionIsReportedAtOnce() {
        val rejected = rejected(
            """{"values":{"noSuchKey":"1","dxwrapper":"directx","fexcoreTSOMode":"1","envVars":"A=1","drives.c":"x",
               |"execArgs":null,"wincomponents.direct3d":"2"},"env":{"set":{"1BAD":"x"}}}
            """.trimMargin(),
        ).associate { (it.str("name") ?: it.str("key")!!) to it.str("reason") }

        assertEquals(
            mapOf(
                "noSuchKey" to ContainerEdits.REASON_UNKNOWN_KEY,
                "dxwrapper" to ContainerEdits.REASON_INVALID_VALUE,
                "fexcoreTSOMode" to ContainerEdits.REASON_NOT_EDITABLE,
                "envVars" to ContainerEdits.REASON_NOT_EDITABLE,
                "drives.c" to ContainerEdits.REASON_UNKNOWN_KEY,
                "execArgs" to ContainerEdits.REASON_INVALID_VALUE,
                "wincomponents.direct3d" to ContainerEdits.REASON_INVALID_VALUE,
                "1BAD" to ContainerEdits.REASON_INVALID_NAME,
            ),
            rejected,
        )
    }

    @Test
    fun sameEnvNameSetAndUnsetIsDuplicate() {
        val rejected = rejected("""{"env":{"set":{"FOO":"2"},"unset":["FOO"]}}""")

        assertEquals(listOf(ContainerEdits.REASON_DUPLICATE, ContainerEdits.REASON_DUPLICATE), rejected.map { it.str("reason") })
    }

    @Test
    fun malformedRequestsAreBadArgs() {
        listOf(
            """{}""",
            """{"values":{}}""",
            """{"values":[1]}""",
            """{"env":"FOO=1"}""",
            """{"env":{"add":{"FOO":"1"}}}""",
            """{"env":{"unset":"FOO"}}""",
            """{"env":{"unset":[1]}}""",
        ).forEach { json ->
            val error = assertThrows(json, AgentException::class.java) { ContainerEdits.parse(request(json)) }
            assertEquals(json, AgentErrorCode.BAD_ARGS, error.code)
        }
        // Valid entries, so only the size cap can reject the request.
        val env = JsonObject((0..ContainerEdits.MAX_CHANGES).associate { "V$it" to JsonPrimitive("1") })
        val tooMany = JsonObject(mapOf("env" to JsonObject(mapOf("set" to env))))
        val capped = assertThrows(AgentException::class.java) { ContainerEdits.parse(tooMany) }
        assertEquals(AgentErrorCode.BAD_ARGS, capped.code)
        assertEquals(null, capped.details)
        assertTrue(capped.message!!, capped.message!!.contains("${ContainerEdits.MAX_CHANGES}"))
        val atCap = JsonObject(mapOf("env" to JsonObject(mapOf("set" to JsonObject(env.entries.drop(1).associate { it.toPair() })))))
        assertEquals(ContainerEdits.MAX_CHANGES, ContainerEdits.parse(atCap).size)
    }

    @Test
    fun wineVersionChangeWarns() {
        val updated = edit("""{"values":{"wineVersion":"proton-10.0-arm64ec"}}""")

        assertEquals(listOf(ContainerEdits.WARNING_WINE_VERSION_CHANGED), ContainerEdits.warnings(live, updated))
    }

    @Test
    fun unpackingTriggersWarn() {
        listOf(
            """{"values":{"executablePath":"Game\\other.exe"}}""",
            """{"values":{"unpackFiles":true}}""",
            """{"values":{"launchRealSteam":true}}""",
            """{"values":{"launchBionicSteam":true}}""",
        ).forEach { json ->
            assertEquals(json, listOf(ContainerEdits.WARNING_NEEDS_UNPACKING), ContainerEdits.warnings(live, edit(json)))
        }
        val firstExe = live.copy(executablePath = "")
        val picked = ContainerEdits.apply(firstExe, ContainerEdits.parse(request("""{"values":{"executablePath":"a.exe"}}""")))
        assertEquals("choosing the first executable needs no unpacking", emptyList<String>(), ContainerEdits.warnings(firstExe, picked))
    }

    @Test
    fun unrelatedChangesDoNotWarn() {
        assertEquals(emptyList<String>(), ContainerEdits.warnings(live, edit("""{"values":{"dxwrapper":"vkd3d","execArgs":"-dx11"}}""")))
    }

    @Test
    fun settingTheCurrentValueChangesNothing() {
        val updated = edit("""{"values":{"dxwrapper":"dxvk","dxwrapperConfig.version":"2.4.1"},"env":{"set":{"FOO":"1"}}}""")

        assertEquals(emptyList<String>(), ContainerEdits.changedFields(live, updated))
    }
}
