package app.gamenative.agent

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.gamenative.agent.commands.ComponentCommands
import app.gamenative.agent.commands.ComponentSources
import app.gamenative.agent.commands.ContainerCommands
import app.gamenative.agent.commands.DeviceCommands
import app.gamenative.agent.commands.GameCommands
import app.gamenative.agent.commands.InstalledGame
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DeviceInfo
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestData
import app.gamenative.utils.ManifestEntry
import app.gamenative.utils.ManifestRepository
import app.gamenative.utils.PrefManagerTestScope
import app.gamenative.utils.installFakePrefManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReadCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var prefScope: PrefManagerTestScope? = null
    private var tempDir: File? = null

    @After
    fun tearDown() {
        prefScope?.close()
        tempDir?.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun failure(block: () -> Unit): AgentException = assertThrows(AgentException::class.java) { block() }

    private fun JsonElement.objects(): List<JsonObject> = jsonArray.map { it.jsonObject }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    // --- registration ---

    @Test
    fun providerFactory_registersReadCommands() {
        val dispatcher = AgentProvider.createDispatcher(context, isEnabled = { true }, isReady = { true }, tracker = AgentSessionTracker())

        val commands = dispatcher.commands()

        listOf(DeviceCommands.INFO, GameCommands.LIST, ComponentCommands.LIST, ContainerCommands.GET).forEach {
            assertTrue("$it registered", it in commands)
        }
    }

    // --- device.info ---

    @Test
    fun deviceInfo_convertsValuesToJson() {
        val data = DeviceCommands {
            mapOf("model" to "SM-S928B", "android_sdk" to 34, "modern_build" to true, "soc_model" to null)
        }.info().jsonObject

        assertEquals("SM-S928B", data["model"]!!.jsonPrimitive.content)
        assertEquals(34, data["android_sdk"]!!.jsonPrimitive.int)
        assertTrue(data["modern_build"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, data["soc_model"])
    }

    @Test
    fun deviceInfo_collectSurvivesFailingProbes() {
        val props = DeviceCommands.collect(context, gpuTimeoutMs = 200)

        assertTrue(props.containsKey("model"))
        assertTrue(props.containsKey("android_sdk"))
        assertTrue(props.containsKey("storage_free_bytes"))
    }

    @Test
    fun deviceInfo_stuckGpuProbeIsSharedAcrossRequests() {
        val release = CountDownLatch(1)
        val probes = AtomicInteger()
        DeviceCommands.resetGpuProbe()
        mockkObject(DeviceInfo)
        try {
            every { DeviceInfo.gpuProperties(any()) } answers {
                probes.incrementAndGet()
                release.await()
                mapOf("gpu_name" to "Adreno 750")
            }

            repeat(3) { assertFalse(DeviceCommands.collect(context, gpuTimeoutMs = 50).containsKey("gpu_name")) }
            assertEquals("timed-out probe is reused, not restarted", 1, probes.get())

            release.countDown()
            assertEquals("Adreno 750", DeviceCommands.collect(context, gpuTimeoutMs = 2_000)["gpu_name"])
            assertEquals(1, probes.get())

            assertEquals("Adreno 750", DeviceCommands.collect(context, gpuTimeoutMs = 2_000)["gpu_name"])
            assertEquals("finished probe is dropped, next request probes again", 2, probes.get())
        } finally {
            release.countDown()
            unmockkObject(DeviceInfo)
            DeviceCommands.resetGpuProbe()
        }
    }

    // --- games.list ---

    private fun gameCommands(
        store: List<InstalledGame> = STORE_GAMES,
        custom: List<InstalledGame> = CUSTOM_GAMES,
        containers: Set<String> = setOf("STEAM_10"),
    ) = GameCommands(storeGames = { store }, customGames = { custom }, containerIds = { containers })

    @Test
    fun gamesList_mergesStoresAndCustomGames() {
        val games = gameCommands().list(JsonObject(emptyMap())).objects()

        assertEquals(listOf("STEAM_10", "EPIC_20", "GOG_30", "CUSTOM_GAME_40"), games.map { it.str("app_id") })
        val steam = games.first()
        assertEquals(10, steam["game_id"]!!.jsonPrimitive.int)
        assertEquals("STEAM", steam.str("game_source"))
        assertEquals("Half-Life", steam.str("name"))
        assertTrue(steam["has_container"]!!.jsonPrimitive.boolean)
        assertFalse(games[1]["has_container"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun gamesList_customFolderImportedAsSteamIsDeduplicated() {
        val games = gameCommands().list(JsonObject(emptyMap())).objects()

        val steam = games.filter { it.str("app_id") == "STEAM_10" }
        assertEquals(1, steam.size)
        assertEquals("store entry wins over the custom folder", "Half-Life", steam.single().str("name"))
    }

    @Test
    fun gamesList_filtersBySourceCaseInsensitively() {
        val epic = gameCommands().list(args("source" to "epic")).objects()
        val custom = gameCommands().list(args("source" to "CUSTOM_GAME")).objects()

        assertEquals(listOf("EPIC_20"), epic.map { it.str("app_id") })
        assertEquals(listOf("CUSTOM_GAME_40"), custom.map { it.str("app_id") })
    }

    @Test
    fun gamesList_unknownOrMistypedSourceIsBadArgs() {
        val unknown = failure { gameCommands().list(args("source" to "ORIGIN")) }
        val mistyped = failure { gameCommands().list(buildJsonObject { put("source", 1) }) }

        assertEquals(AgentErrorCode.BAD_ARGS, unknown.code)
        assertTrue(unknown.details!!.jsonObject["sources"]!!.jsonArray.isNotEmpty())
        assertEquals(AgentErrorCode.BAD_ARGS, mistyped.code)
    }

    @Test
    fun gamesList_nonNumericGogIdHasNullGameId() {
        val games = gameCommands(store = listOf(InstalledGame("GOG_x", null, GameSource.GOG, "Odd")), custom = emptyList())
            .list(JsonObject(emptyMap())).objects()

        assertEquals(JsonNull, games.single()["game_id"])
    }

    // --- components.list ---

    private fun sources(manifest: ManifestData = ManifestData.empty()) = ComponentSources(
        wineGlibc = listOf("wine-9.2-x86_64"),
        wineBionic = listOf("proton-9.0-arm64ec", "proton-9.0-x86_64"),
        dxvk = listOf("2.4.1", "async-1.10.3"),
        vkd3d = listOf("2.14.1"),
        box64Glibc = listOf("0.3.6 (Default)"),
        box64Bionic = listOf("0.3.7 (Default)", "0.3.6"),
        wowBox64 = listOf("0.4.2"),
        fexcore = listOf("2605"),
        drivers = listOf("System"),
        installed = ManifestComponentHelper.InstalledContentLists(
            dxvk = listOf("2.7.1"),
            vkd3d = emptyList(),
            box64 = emptyList(),
            wowBox64 = emptyList(),
            fexcore = emptyList(),
            wine = listOf("wine-10.0-arm64ec"),
            proton = emptyList(),
        ),
        installedDrivers = listOf("turnip25.1.0"),
        manifest = manifest,
    )

    private fun entry(id: String, variant: String? = null) =
        ManifestEntry(id = id, name = "name $id", url = "https://example.invalid/$id", variant = variant)

    private fun components(manifest: ManifestData = ManifestData.empty(), target: String? = null): List<JsonObject> =
        ComponentCommands { sources(manifest) }
            .list(if (target == null) JsonObject(emptyMap()) else args("target" to target))
            .objects()

    @Test
    fun componentsList_coversEveryTargetWithVariants() {
        val all = components()

        assertEquals(ComponentCommands.TARGETS.toSet(), all.map { it.str("target") }.toSet())
        val box64 = all.filter { it.str("target") == ComponentCommands.TARGET_BOX64 }
        val glibcDefault = box64.single { it.str("variant") == "glibc" }
        assertEquals("0.3.6", glibcDefault.str("value"))
        assertEquals("0.3.6 (Default)", glibcDefault.str("label"))
        assertTrue(glibcDefault["installed"]!!.jsonPrimitive.boolean)
        assertFalse(glibcDefault["downloadable"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("0.4.2"), box64.filter { it.str("variant") == "arm64ec" }.map { it.str("value") })
        val wine = all.filter { it.str("target") == ComponentCommands.TARGET_WINE }
        assertEquals("bionic", wine.single { it.str("value") == "wine-10.0-arm64ec" }.str("variant"))
        assertEquals("glibc", wine.single { it.str("value") == "wine-9.2-x86_64" }.str("variant"))
    }

    @Test
    fun componentsList_driverIdsStayVerbatim() {
        val drivers = components(target = ComponentCommands.TARGET_DRIVER)

        assertEquals(listOf("System", "turnip25.1.0"), drivers.map { it.str("value") })
        assertNull(drivers.first()["variant"])
    }

    @Test
    fun componentsList_manifestEntriesAreDownloadableUnlessInstalled() {
        val manifest = ManifestData(
            version = 1,
            updatedAt = null,
            items = mapOf(
                "dxvk" to listOf(entry("2.7.1", "bionic"), entry("2.8", "bionic")),
                "wowbox64" to listOf(entry("0.5.0", "bionic")),
            ),
        )

        val dxvk = components(manifest, ComponentCommands.TARGET_DXVK)
        val wow = components(manifest, ComponentCommands.TARGET_BOX64).filter { it.str("variant") == "arm64ec" }

        assertEquals("installed manifest entry listed once", 1, dxvk.count { it.str("value") == "2.7.1" })
        assertTrue(dxvk.single { it.str("value") == "2.7.1" }["installed"]!!.jsonPrimitive.boolean)
        val fresh = dxvk.single { it.str("value") == "2.8" }
        assertFalse(fresh["installed"]!!.jsonPrimitive.boolean)
        assertTrue(fresh["downloadable"]!!.jsonPrimitive.boolean)
        assertEquals("bionic", fresh.str("variant"))
        assertEquals("name 2.8", fresh.str("label"))
        assertEquals(listOf("0.4.2", "0.5.0"), wow.map { it.str("value") })
    }

    @Test
    fun componentsList_filtersByTarget() {
        val vkd3d = components(target = ComponentCommands.TARGET_VKD3D)

        assertEquals(listOf("2.14.1"), vkd3d.map { it.str("value") })
    }

    @Test
    fun componentsList_unknownTargetIsBadArgs() {
        val error = failure { components(target = "dxwrapper") }

        assertEquals(AgentErrorCode.BAD_ARGS, error.code)
        assertEquals(ComponentCommands.TARGETS.size, error.details!!.jsonObject["targets"]!!.jsonArray.size)
    }

    @Test
    fun componentsList_readsCachedManifestWithoutNetwork() {
        val manifestJson =
            """{"version":1,"updatedAt":"x","items":{"fexcore":[{"id":"2699","name":"FEX 2699","url":"https://example.invalid"}]}}"""
        prefScope = installFakePrefManager(FakeDataStore(preferencesOf(stringPreferencesKey("component_manifest_json") to manifestJson)))
        mockkObject(ManifestRepository)
        try {
            val fexcore = ComponentCommands { ComponentCommands.loadSources(context) }
                .list(args("target" to ComponentCommands.TARGET_FEXCORE))
                .objects()

            assertTrue(fexcore.any { it.str("value") == "2699" && it["downloadable"]!!.jsonPrimitive.boolean })
            assertTrue("bundled entries are listed", fexcore.any { it.str("value") == "2605" })
            coVerify(exactly = 0) { ManifestRepository.loadManifest(any()) }
        } finally {
            unmockkObject(ManifestRepository)
        }
    }

    // --- containers.get ---

    private fun containerData(envVars: String) = ContainerData(
        screenSize = "1280x720",
        envVars = envVars,
        dxwrapper = "dxvk",
        dxwrapperConfig = "version=2.4.1,async=1",
        containerVariant = Container.BIONIC,
        wineVersion = "proton-9.0-arm64ec",
        startupSelection = 1,
        unpackFiles = true,
    )

    @Test
    fun containersGet_returnsTypedValuesAndMaskedEnv() {
        val env = EnvVars().apply {
            put("STEAM_TOKEN", "t0ps3cret")
            put("API_KEY", "k")
            put("MY_PASS", "p")
            put("DXVK_ASYNC_BYPASS", "1")
            put("PASSTHROUGH", "on")
            put("MONKEY", "banana")
            put("WINEDLLOVERRIDES", "d3d11=n,b")
        }.toString()

        val data = ContainerCommands { if (it == "STEAM_123") containerData(env) else null }
            .get(args("app_id" to "STEAM_123")).jsonObject

        assertEquals("STEAM_123", data.str("app_id"))
        val values = data["values"]!!.jsonObject
        assertEquals("dxvk", values.str("dxwrapper"))
        assertEquals("version=2.4.1,async=1", values.str("dxwrapperConfig"))
        assertEquals(1, values["startupSelection"]!!.jsonPrimitive.int)
        assertTrue(values["unpackFiles"]!!.jsonPrimitive.boolean)
        assertEquals("bionic", values.str("containerVariant"))
        assertFalse("raw env string is never returned", values.containsKey("envVars"))
        val masked = data["env"]!!.jsonObject
        assertEquals(ContainerCommands.MASK, masked.str("STEAM_TOKEN"))
        assertEquals(ContainerCommands.MASK, masked.str("API_KEY"))
        assertEquals(ContainerCommands.MASK, masked.str("MY_PASS"))
        assertEquals("1", masked.str("DXVK_ASYNC_BYPASS"))
        assertEquals("on", masked.str("PASSTHROUGH"))
        assertEquals("banana", masked.str("MONKEY"))
        assertEquals("d3d11=n,b", masked.str("WINEDLLOVERRIDES"))
        assertFalse(data.toString().contains("t0ps3cret"))
        assertEquals(JsonArray(listOf(JsonPrimitive("containerVariant"))), data["read_only"])
    }

    @Test
    fun containersGet_readsRealContainerData() {
        prefScope = installFakePrefManager(FakeDataStore())
        val dir = Files.createTempDirectory("agent-container").toFile().also { tempDir = it }
        File(dir, ".wine").mkdirs()
        File(dir, ".wine/user.reg").writeText("REGEDIT4\n")
        val container = Container("STEAM_123456").apply {
            setRootDir(dir)
            setEnvVars("AUTH_SECRET=x FOO=bar")
        }

        val data = ContainerCommands { ContainerUtils.toContainerData(container) }
            .get(args("app_id" to "STEAM_123456")).jsonObject

        assertEquals(ContainerCommands.MASK, data["env"]!!.jsonObject.str("AUTH_SECRET"))
        assertEquals("bar", data["env"]!!.jsonObject.str("FOO"))
        assertEquals(container.wineVersion, data["values"]!!.jsonObject.str("wineVersion"))
    }

    @Test
    fun containersGet_noContainer() {
        val injected = failure { ContainerCommands { null }.get(args("app_id" to "STEAM_1")) }

        assertEquals(AgentErrorCode.NO_CONTAINER, injected.code)
        assertNull("a fresh install has no containers", ContainerCommands.load(context, "STEAM_999999"))
    }

    @Test
    fun containersGet_requiresWellFormedAppId() {
        val commands = ContainerCommands { error("loader must not run for bad ids") }

        listOf(JsonObject(emptyMap()), args("app_id" to ""), args("app_id" to "../STEAM_1"), args("app_id" to "STEAM_abc"))
            .forEach { assertEquals(AgentErrorCode.BAD_ARGS, failure { commands.get(it) }.code) }
        assertEquals(AgentErrorCode.BAD_ARGS, failure { commands.get(buildJsonObject { put("app_id", 123) }) }.code)
    }

    companion object {
        private val STORE_GAMES = listOf(
            InstalledGame("STEAM_10", 10, GameSource.STEAM, "Half-Life"),
            InstalledGame("EPIC_20", 20, GameSource.EPIC, "Alan Wake"),
            InstalledGame("GOG_30", 30, GameSource.GOG, "Witcher"),
        )
        private val CUSTOM_GAMES = listOf(
            InstalledGame("STEAM_10", 10, GameSource.STEAM, "half-life folder"),
            InstalledGame("CUSTOM_GAME_40", 40, GameSource.CUSTOM_GAME, "Homebrew"),
        )
    }
}
