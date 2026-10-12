package app.gamenative.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.agent.commands.ComponentCheck
import app.gamenative.agent.commands.ContainerCommands
import app.gamenative.agent.commands.ContainerWriteDeps
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.PrefManagerTestScope
import app.gamenative.utils.installFakePrefManager
import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceptance checks across the production dispatcher: every v1 command is registered and listed in
 * `capabilities`, the toggle turns every command off, and a secret env value written through
 * `containers.set` never comes back in any container command response.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AcceptanceEdgeCasesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefScope: PrefManagerTestScope
    private lateinit var containerDir: File
    private val clock = AtomicLong(1_700_000_000_000L)

    @Before
    fun setUp() {
        prefScope = installFakePrefManager(FakeDataStore())
        containerDir = Files.createTempDirectory("agent-acceptance").toFile()
        File(containerDir, ".wine").mkdirs()
        File(containerDir, ".wine/user.reg").writeText("REGEDIT4\n")
        Container(APP_ID).apply {
            setRootDir(containerDir)
            dxWrapper = "dxvk"
            dxWrapperConfig = "version=2.4.1,async=1"
            envVars = "FOO=1"
            saveData()
        }
    }

    @After
    fun tearDown() {
        prefScope.close()
        containerDir.deleteRecursively()
    }

    private fun open(): Container = Container(APP_ID).apply {
        setRootDir(containerDir)
        loadData(JSONObject(File(containerDir, ".container").readText()))
    }

    private fun commands() = ContainerCommands(
        loadContainer = { if (it == APP_ID) ContainerUtils.toContainerData(open()) else null },
        writes = ContainerWriteDeps(
            openContainer = { if (it == APP_ID) open() else null },
            readContainer = ContainerUtils::toContainerData,
            writeContainer = { container, data, source -> ContainerWriteDeps.write(context, container, data, source) },
            gpuCardIds = { ContainerUtils.getGPUCards(context).keys },
            checkComponents = { ComponentCheck(emptyList()) },
            isSessionActive = { false },
            clock = { clock.addAndGet(1_000) },
        ),
    )

    private fun request(json: String): JsonObject = Json.parseToJsonElement(json.replace("APP", APP_ID)).jsonObject

    private fun call(dispatcher: AgentDispatcher, command: String): JsonObject {
        val request = buildJsonObject {
            put("api_version", AgentProtocol.API_VERSION)
            put("command", command)
        }
        return Json.parseToJsonElement(dispatcher.dispatch(AgentProtocol.encode(request))).jsonObject
    }

    @Test
    fun capabilitiesListsEveryV1Command() {
        val dispatcher = AgentProvider.createDispatcher(context, isEnabled = { true }, isReady = { true }, tracker = AgentSessionTracker())

        val response = call(dispatcher, AgentDispatcher.CAPABILITIES)

        assertTrue(response["ok"]!!.jsonPrimitive.boolean)
        val listed = response["data"]!!.jsonObject["commands"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("missing: ${V1_COMMANDS - listed}", listed.containsAll(V1_COMMANDS))
    }

    @Test
    fun toggleOffDisablesEveryV1Command() {
        val dispatcher = AgentProvider.createDispatcher(context, isEnabled = { false }, isReady = { true }, tracker = AgentSessionTracker())

        V1_COMMANDS.forEach { command ->
            val response = call(dispatcher, command)
            assertFalse(command, response["ok"]!!.jsonPrimitive.boolean)
            assertEquals(command, AgentErrorCode.DISABLED.name, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun secretEnvValueNeverAppearsInContainerResponses() {
        val cmd = commands()

        val rejected = assertThrows(AgentException::class.java) {
            cmd.set(request("""{"app_id":"APP","values":{"containerVariant":"glibc"},"env":{"set":{"MY_TOKEN":"$SECRET"}}}"""))
        }
        assertEquals(AgentErrorCode.BAD_ARGS, rejected.code)
        assertFalse(rejected.message.orEmpty().contains(SECRET))
        assertFalse(rejected.details.toString().contains(SECRET))

        val setResult = cmd.set(request("""{"app_id":"APP","env":{"set":{"MY_TOKEN":"$SECRET"}}}"""))
        assertTrue("secret was written", ContainerUtils.toContainerData(open()).envVars.contains(SECRET))
        assertFalse(setResult.toString().contains(SECRET))

        val got = cmd.get(request("""{"app_id":"APP"}""")).jsonObject
        assertEquals(ContainerCommands.MASK, got["env"]!!.jsonObject["MY_TOKEN"]!!.jsonPrimitive.content)
        assertFalse(got.toString().contains(SECRET))

        assertFalse(cmd.backups(request("""{"app_id":"APP"}""")).toString().contains(SECRET))

        val restoreResult = cmd.restore(request("""{"app_id":"APP","baseline":true}"""))
        assertFalse(restoreResult.toString().contains(SECRET))
        assertFalse(ContainerUtils.toContainerData(open()).envVars.contains(SECRET))
    }

    private companion object {
        const val APP_ID = "STEAM_424242"
        const val SECRET = "s3cr3tValue42"
        val V1_COMMANDS = setOf(
            "capabilities",
            "device.info",
            "games.list",
            "components.list",
            "containers.get",
            "containers.set",
            "containers.restore",
            "containers.backups",
            "sessions.current",
            "sessions.stop",
            "sessions.logs",
        )
    }
}
