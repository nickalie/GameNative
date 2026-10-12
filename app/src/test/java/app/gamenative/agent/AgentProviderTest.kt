package app.gamenative.agent

import android.app.Application
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import app.gamenative.agent.commands.SessionCommands
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.PrefManagerTestScope
import app.gamenative.utils.installFakePrefManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentProviderTest {

    private val uri: Uri = Uri.parse("content://app.gamenative.agent")
    private var prefScope: PrefManagerTestScope? = null
    private var wasReady = false
    private val originalFactory = AgentProvider.dispatcherFactory

    @Before
    fun setUp() {
        wasReady = AgentRuntime.isReady
        ShadowBinder.setCallingUid(SHELL_UID)
    }

    @After
    fun tearDown() {
        AgentProvider.dispatcherFactory = originalFactory
        AgentRuntime.isReady = wasReady
        prefScope?.close()
        ShadowBinder.reset()
    }

    private fun provider(): AgentProvider = Robolectric.buildContentProvider(AgentProvider::class.java)
        .create(ProviderInfo().apply { authority = "app.gamenative.agent" })
        .get()

    private fun usePrefs(enabled: Boolean?) {
        val initial = if (enabled == null) {
            preferencesOf()
        } else {
            preferencesOf(booleanPreferencesKey("agent_api_enabled") to enabled)
        }
        prefScope = installFakePrefManager(FakeDataStore(initial))
    }

    private fun useTestDispatcher(tracker: AgentSessionTracker = AgentSessionTracker()) {
        AgentProvider.dispatcherFactory = { context ->
            AgentProvider.createDispatcher(context, isEnabled = { true }, isReady = { true }, tracker = tracker)
        }
    }

    private fun request(command: String): String = AgentProtocol.encode(
        buildJsonObject {
            put("api_version", AgentProtocol.API_VERSION)
            put("command", command)
        },
    )

    private fun callJson(p: AgentProvider, command: String, method: String = AgentProvider.METHOD_COMMAND): JsonObject {
        val bundle = p.call(method, request(command), null)
        return Json.parseToJsonElement(bundle.getString(AgentProvider.KEY_JSON)!!).jsonObject
    }

    private fun errorCode(json: JsonObject): String {
        assertFalse(json["ok"]!!.jsonPrimitive.boolean)
        return json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    }

    private fun allEntryPoints(p: AgentProvider): List<Pair<String, () -> Unit>> = listOf(
        "call" to { p.call(AgentProvider.METHOD_COMMAND, request("capabilities"), null) },
        "call(other)" to { p.call("other", null, null) },
        "query" to { p.query(uri, null, null, null, null) },
        "query(bundle)" to { p.query(uri, null, Bundle(), null) },
        "getType" to { p.getType(uri) },
        "insert" to { p.insert(uri, ContentValues()) },
        "bulkInsert" to { p.bulkInsert(uri, arrayOf(ContentValues())) },
        "update" to { p.update(uri, ContentValues(), null, null) },
        "delete" to { p.delete(uri, null, null) },
        "openFile" to { p.openFile(uri, "r") },
        "openAssetFile" to { p.openAssetFile(uri, "r") },
        "openTypedAssetFile" to { p.openTypedAssetFile(uri, "*/*", null) },
    )

    // --- caller UID ---

    @Test
    fun shellUid_getsResponseBundle() {
        useTestDispatcher()

        val json = callJson(provider(), "capabilities")

        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        val commands = json["data"]!!.jsonObject["commands"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(commands.contains(SessionCommands.CURRENT))
        assertEquals(RuntimeEnvironment.getApplication().packageName, json["data"]!!.jsonObject["package"]!!.jsonPrimitive.content)
    }

    @Test
    fun rootUid_isAllowed() {
        useTestDispatcher()
        ShadowBinder.setCallingUid(0)

        assertTrue(callJson(provider(), "capabilities")["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun isAllowedUid_acceptsShellAndRootInAnyUserOnly() {
        assertTrue(AgentProvider.isAllowedUid(0))
        assertTrue(AgentProvider.isAllowedUid(2000))
        assertTrue(AgentProvider.isAllowedUid(10 * 100_000 + 2000))
        assertFalse(AgentProvider.isAllowedUid(1000))
        assertFalse(AgentProvider.isAllowedUid(2001))
        assertFalse(AgentProvider.isAllowedUid(10_123))
        assertFalse(AgentProvider.isAllowedUid(10 * 100_000 + 10_123))
        assertFalse(AgentProvider.isAllowedUid(-1))
    }

    @Test
    fun foreignUid_isRejectedAtEveryEntryPoint() {
        useTestDispatcher()
        ShadowBinder.setCallingUid(10_123)
        val p = provider()

        allEntryPoints(p).forEach { (name, block) ->
            assertThrows("$name did not reject a foreign uid", SecurityException::class.java) { block() }
        }
    }

    @Test
    fun allowedUid_nonCallEntryPointsAreUnsupported() {
        useTestDispatcher()
        val p = provider()

        assertNull(p.getType(uri))
        allEntryPoints(p)
            .filterNot { (name, _) -> name.startsWith("call") || name == "getType" }
            .forEach { (name, block) -> assertThrows(name, UnsupportedOperationException::class.java) { block() } }
    }

    @Test
    fun unknownMethod_returnsBadArgs() {
        useTestDispatcher()

        assertEquals("BAD_ARGS", errorCode(callJson(provider(), "capabilities", method = "query")))
    }

    // --- readiness and toggle, through the real factory ---

    @Test
    fun beforeReadiness_returnsNotReady() {
        AgentRuntime.isReady = false

        assertEquals("NOT_READY", errorCode(callJson(provider(), "capabilities")))
    }

    @Test
    fun toggleOffByDefault_returnsDisabled() {
        usePrefs(enabled = null)
        AgentRuntime.isReady = true

        assertEquals("DISABLED", errorCode(callJson(provider(), "capabilities")))
    }

    @Test
    fun toggleOff_returnsDisabled() {
        usePrefs(enabled = false)
        AgentRuntime.isReady = true

        assertEquals("DISABLED", errorCode(callJson(provider(), SessionCommands.CURRENT)))
    }

    @Test
    fun toggleOn_dispatchesCommands() {
        usePrefs(enabled = true)
        AgentRuntime.isReady = true

        assertTrue(callJson(provider(), "capabilities")["ok"]!!.jsonPrimitive.boolean)
    }

    // --- sessions.current ---

    @Test
    fun sessionsCurrent_withoutSession_isNull() {
        useTestDispatcher()

        val json = callJson(provider(), SessionCommands.CURRENT)

        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, json["data"])
    }

    @Test
    fun sessionsCurrent_returnsTrackedSession() {
        val tracker = AgentSessionTracker(idGenerator = { "s1" })
        tracker.onLaunchRequested("STEAM_123", AgentLaunchOptions(agentDebug = true))
        useTestDispatcher(tracker)

        val data = callJson(provider(), SessionCommands.CURRENT)["data"]!!.jsonObject

        assertEquals("s1", data["session_id"]!!.jsonPrimitive.content)
        assertEquals("STEAM_123", data["app_id"]!!.jsonPrimitive.content)
        assertEquals("launching", data["state"]!!.jsonPrimitive.content)
        assertTrue(data["agent_debug"]!!.jsonPrimitive.boolean)
    }

    private companion object {
        const val SHELL_UID = 2000
    }
}
