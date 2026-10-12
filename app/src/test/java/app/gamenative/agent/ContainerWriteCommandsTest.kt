package app.gamenative.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.agent.commands.ContainerCommands
import app.gamenative.agent.commands.ContainerWriteDeps
import app.gamenative.agent.commands.MissingComponent
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.PrefManagerTestScope
import app.gamenative.utils.installFakePrefManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContainerWriteCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefScope: PrefManagerTestScope
    private lateinit var containerDir: File
    private val tracker = AgentSessionTracker()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val writes = AtomicInteger()
    private val componentChecks = AtomicInteger()

    @Before
    fun setUp() {
        prefScope = installFakePrefManager(FakeDataStore())
        containerDir = Files.createTempDirectory("agent-write").toFile()
        File(containerDir, ".wine").mkdirs()
        File(containerDir, ".wine/user.reg").writeText("REGEDIT4\n")
        Container(APP_ID).apply {
            setRootDir(containerDir)
            dxWrapper = "dxvk"
            dxWrapperConfig = "version=2.4.1,async=1"
            wineVersion = "proton-9.0-arm64ec"
            envVars = "FOO=1"
            execArgs = "-original"
            saveData()
        }
    }

    @After
    fun tearDown() {
        prefScope.close()
        containerDir.deleteRecursively()
    }

    /** A fresh instance read from disk, the way ContainerManager opens it. */
    private fun open(): Container = Container(APP_ID).apply {
        setRootDir(containerDir)
        loadData(JSONObject(File(containerDir, ".container").readText()))
    }

    private fun current(): ContainerData = ContainerUtils.toContainerData(open())

    private fun commands(
        gpuCardIds: () -> Set<Int> = { ContainerUtils.getGPUCards(context).keys },
        missing: List<MissingComponent> = emptyList(),
    ) = ContainerCommands(
        loadContainer = { null },
        writes = ContainerWriteDeps(
            openContainer = { if (it == APP_ID) open() else null },
            readContainer = ContainerUtils::toContainerData,
            writeContainer = { container, data, source ->
                writes.incrementAndGet()
                ContainerWriteDeps.write(context, container, data, source)
            },
            gpuCardIds = gpuCardIds,
            missingComponents = {
                componentChecks.incrementAndGet()
                missing
            },
            isSessionActive = tracker::isActive,
            clock = { clock.addAndGet(1_000) },
        ),
    )

    private fun request(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun set(json: String, cmd: ContainerCommands = commands()): JsonObject =
        cmd.set(request(json.replace("APP", APP_ID))).jsonObject

    private fun restore(json: String, cmd: ContainerCommands = commands()): JsonObject =
        cmd.restore(request(json.replace("APP", APP_ID))).jsonObject

    private fun backups(): List<JsonObject> = commands().backups(request("""{"app_id":"$APP_ID"}""")).jsonArray.map { it.jsonObject }

    private fun failure(block: () -> Unit): AgentException = assertThrows(AgentException::class.java) { block() }

    private fun JsonObject.str(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    private fun dxvkVersion(): String? {
        for (pair in KeyValueSet(current().dxwrapperConfig)) if (pair[0] == "version") return pair[1]
        return null
    }

    @Test
    fun setAThenSetBThenRestoreBaselineReturnsTheOriginal() {
        val original = ContainerBackups.snapshot(current())

        val a = set("""{"app_id":"APP","values":{"dxwrapperConfig.version":"2.5","execArgs":"-a"},"env":{"set":{"DXVK_HUD":"fps"}}}""")
        assertEquals(setOf("dxwrapperConfig.version", "execArgs", "envVars.DXVK_HUD"), a["changed_fields"]!!.strings().toSet())
        assertEquals("2.5", dxvkVersion())
        assertEquals("-a", current().execArgs)
        assertEquals(ContainerCommands.SOURCE_SET, open().configSource)
        assertTrue("markConfigApplied ran", File(containerDir, "applied_config.json").exists())

        val b = set("""{"app_id":"APP","values":{"dxwrapperConfig.version":"2.6","execArgs":"-b"},"env":{"unset":["FOO"]}}""")
        assertEquals("2.6", dxvkVersion())
        assertTrue(b["backup_id"]!!.jsonPrimitive.content != a["backup_id"]!!.jsonPrimitive.content)

        val restored = restore("""{"app_id":"APP","baseline":true}""")

        assertEquals(a.str("backup_id"), restored.str("restored_from"))
        assertEquals(original, ContainerBackups.snapshot(current()))
        assertEquals(ContainerCommands.SOURCE_RESTORE, open().configSource)
        val listed = backups()
        assertEquals(3, listed.size)
        assertEquals(listOf(true, false, false), listed.map { it["baseline"]!!.jsonPrimitive.boolean })
        assertEquals(restored.str("backup_id"), listed.last().str("backup_id"))
    }

    @Test
    fun restoreTouchesOnlyDifferingKeysAndBacksUpFirst() {
        set("""{"app_id":"APP","values":{"dxwrapperConfig.version":"2.5"}}""")
        val beforeRestore = current()
        val baseline = ContainerBackups(containerDir) { 0L }.baseline()!!

        val updates = ContainerBackups.restoreUpdates(beforeRestore, baseline)
        assertEquals(setOf("dxwrapperConfig"), updates.keys)

        val restored = restore("""{"app_id":"APP","baseline":true}""")

        assertEquals(listOf("dxwrapperConfig.version"), restored["changed_fields"]!!.strings())
        val newBackup = ContainerBackups(containerDir) { 0L }.find(restored.str("backup_id")!!)
        assertNotNull("the rollback backed up the config it replaced", newBackup)
        assertEquals(ContainerBackups.snapshot(beforeRestore), newBackup!!.values)
        assertEquals("2.4.1", dxvkVersion())
    }

    @Test
    fun restoreByBackupId() {
        set("""{"app_id":"APP","values":{"execArgs":"-a"}}""")
        val second = set("""{"app_id":"APP","values":{"execArgs":"-b"}}""")
        set("""{"app_id":"APP","values":{"execArgs":"-c"}}""")

        restore("""{"app_id":"APP","backup_id":"${second.str("backup_id")}"}""")

        assertEquals("-a", current().execArgs)
    }

    @Test
    fun restoreWithNothingToChangeWritesNothing() {
        set("""{"app_id":"APP","values":{"execArgs":"-a"}}""")
        set("""{"app_id":"APP","values":{"execArgs":"-original"}}""")
        val count = backups().size
        val writesBefore = writes.get()

        val restored = restore("""{"app_id":"APP","baseline":true}""")

        assertEquals(JsonNull, restored["backup_id"])
        assertEquals(emptyList<String>(), restored["changed_fields"]!!.strings())
        assertEquals(count, backups().size)
        assertEquals(writesBefore, writes.get())
    }

    @Test
    fun rotationNeverRemovesTheBaseline() {
        val first = set("""{"app_id":"APP","values":{"execArgs":"-0"}}""")
        repeat(ContainerBackups.KEEP + 3) { set("""{"app_id":"APP","values":{"execArgs":"-${it + 1}"}}""") }

        val listed = backups()

        assertEquals(ContainerBackups.KEEP + 1, listed.size)
        assertEquals(first.str("backup_id"), listed.first().str("backup_id"))
        assertTrue(listed.first()["baseline"]!!.jsonPrimitive.boolean)
        assertEquals(1, listed.count { it["baseline"]!!.jsonPrimitive.boolean })
        restore("""{"app_id":"APP","baseline":true}""")
        assertEquals("-original", current().execArgs)
    }

    @Test
    fun missingComponentsWritesNothingAndCreatesNoBackup() {
        val configBefore = File(containerDir, ".container").readText()
        val cmd = commands(missing = listOf(MissingComponent("dxvk-9.9", downloadable = true, type = "dxvk"), MissingComponent("x", false)))

        val error = failure { set("""{"app_id":"APP","values":{"dxwrapperConfig.version":"9.9"}}""", cmd) }

        assertEquals(AgentErrorCode.MISSING_COMPONENTS, error.code)
        val details = error.details!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("dxvk-9.9", "x"), details.map { it.str("name") })
        assertEquals(listOf(true, false), details.map { it["downloadable"]!!.jsonPrimitive.boolean })
        assertEquals("dxvk", details[0].str("type"))
        assertEquals(configBefore, File(containerDir, ".container").readText())
        assertFalse(File(containerDir, ContainerBackups.DIR).exists() && File(containerDir, ContainerBackups.DIR).list()!!.isNotEmpty())
        assertEquals(0, writes.get())
    }

    @Test
    fun componentsAreCheckedOnlyWhenComponentKeysChange() {
        val cmd = commands(missing = listOf(MissingComponent("dxvk-9.9", downloadable = true)))

        set("""{"app_id":"APP","values":{"execArgs":"-x"},"env":{"set":{"A":"1"}}}""", cmd)

        assertEquals(0, componentChecks.get())
        assertEquals("-x", current().execArgs)
    }

    @Test
    fun unknownCurrentGpuIsContainerInvalid() {
        val cmd = commands(gpuCardIds = { emptySet() })

        assertEquals(AgentErrorCode.CONTAINER_INVALID, failure { set("""{"app_id":"APP","values":{"execArgs":"-x"}}""", cmd) }.code)
        set("""{"app_id":"APP","values":{"execArgs":"-x"}}""")
        assertEquals(AgentErrorCode.CONTAINER_INVALID, failure { restore("""{"app_id":"APP","baseline":true}""", cmd) }.code)
        assertEquals(1, writes.get())
    }

    @Test
    fun activeSessionBlocksWrites() {
        set("""{"app_id":"APP","values":{"execArgs":"-x"}}""")
        tracker.onLaunchRequested(APP_ID, AgentLaunchOptions())

        assertEquals(AgentErrorCode.SESSION_ACTIVE, failure { set("""{"app_id":"APP","values":{"execArgs":"-y"}}""") }.code)
        assertEquals(AgentErrorCode.SESSION_ACTIVE, failure { restore("""{"app_id":"APP","baseline":true}""") }.code)
        assertEquals("-x", current().execArgs)

        tracker.onLaunchRequested("STEAM_1", AgentLaunchOptions())
        set("""{"app_id":"APP","values":{"execArgs":"-y"}}""")
        assertEquals("another game's session does not block", "-y", current().execArgs)
    }

    @Test
    fun noBackup() {
        assertEquals(AgentErrorCode.NO_BACKUP, failure { restore("""{"app_id":"APP","baseline":true}""") }.code)
        assertEquals(AgentErrorCode.NO_BACKUP, failure { restore("""{"app_id":"APP","backup_id":"123"}""") }.code)
        assertEquals(emptyList<JsonObject>(), backups())
    }

    @Test
    fun badArgsAndMissingContainer() {
        listOf(
            """{"app_id":"APP"}""",
            """{"app_id":"APP","baseline":true,"backup_id":"1"}""",
            """{"app_id":"APP","baseline":"yes"}""",
            """{"app_id":"APP","backup_id":"../x"}""",
        ).forEach { json -> assertEquals(json, AgentErrorCode.BAD_ARGS, failure { restore(json) }.code) }
        assertEquals(AgentErrorCode.BAD_ARGS, failure { set("""{"app_id":"APP","values":{"containerVariant":"glibc"}}""") }.code)

        val other = """{"app_id":"STEAM_2","values":{"execArgs":"-x"}}"""
        assertEquals(AgentErrorCode.NO_CONTAINER, failure { commands().set(request(other)) }.code)
        assertEquals(AgentErrorCode.NO_CONTAINER, failure { commands().backups(request("""{"app_id":"STEAM_2"}""")) }.code)
        assertEquals(0, writes.get())
    }

    @Test
    fun noOpSetWritesNothing() {
        val result = set("""{"app_id":"APP","values":{"execArgs":"-original","dxwrapper":"dxvk"}}""")

        assertEquals(JsonNull, result["backup_id"])
        assertEquals(emptyList<String>(), result["changed_fields"]!!.strings())
        assertEquals(0, writes.get())
        assertEquals(emptyList<JsonObject>(), backups())
    }

    @Test
    fun setReportsWarnings() {
        val result = set("""{"app_id":"APP","values":{"wineVersion":"proton-10.0-arm64ec","unpackFiles":true}}""")

        assertEquals(
            listOf(ContainerEdits.WARNING_WINE_VERSION_CHANGED, ContainerEdits.WARNING_NEEDS_UNPACKING),
            result["warnings"]!!.strings(),
        )
        assertEquals(1, componentChecks.get())
    }

    @Test
    fun providerFactoryRegistersWriteCommands() {
        val dispatcher = AgentProvider.createDispatcher(context, isEnabled = { true }, isReady = { true }, tracker = AgentSessionTracker())

        listOf(ContainerCommands.SET, ContainerCommands.RESTORE, ContainerCommands.BACKUPS).forEach {
            assertTrue("$it registered", it in dispatcher.commands())
        }
    }

    companion object {
        private const val APP_ID = "STEAM_123456"
    }
}
