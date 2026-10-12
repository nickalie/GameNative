package app.gamenative.agent

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.gamenative.agent.commands.ComponentCommands
import app.gamenative.agent.commands.ComponentInstallDeps
import app.gamenative.agent.commands.ComponentInstallQueue
import app.gamenative.agent.commands.InstallPlan
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestInstallResult
import com.winlator.contents.ContentProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.double
import app.gamenative.agent.commands.ComponentSources
import app.gamenative.agent.commands.ContainerCommands
import app.gamenative.agent.commands.CustomFolderDeps
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

        listOf(DeviceCommands.INFO, GameCommands.LIST, GameCommands.ADD_CUSTOM, ComponentCommands.LIST, ComponentCommands.INSTALL, ContainerCommands.GET).forEach {
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
        folders: FakeCustomFolders = FakeCustomFolders(),
    ) = GameCommands(storeGames = { store }, customGames = { custom }, containerIds = { containers }, customFolders = folders.deps())

    /** Records what `games.add_custom` asks of the app. */
    private class FakeCustomFolders(
        val storageAccess: Boolean = true,
        val entry: (String) -> InstalledGame? = { InstalledGame("CUSTOM_GAME_77", 77, GameSource.CUSTOM_GAME, File(it).name) },
        val alreadyAdded: Boolean = false,
        val executable: String? = "Game.exe",
        val sharedRoot: String = "/storage/emulated/0",
    ) {
        val added = mutableListOf<String>()
        val notified = mutableListOf<InstalledGame>()

        fun deps() = CustomFolderDeps(
            hasStorageAccess = { storageAccess },
            libraryEntry = entry,
            addFolder = { added += it; !alreadyAdded },
            notifyLibrary = { notified += it },
            autoExecutable = { executable },
            sharedStorageRoot = sharedRoot,
            storageGrantHint = GRANT_HINT,
        )
    }

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

    // --- games.add_custom ---

    private fun gameFolder(): File = Files.createTempDirectory("agent-custom").toFile().also { tempDir = it }.absoluteFile

    @Test
    fun addCustom_registersFolderAndReportsTheEntry() {
        val folder = gameFolder()
        val folders = FakeCustomFolders()

        val data = gameCommands(containers = setOf("CUSTOM_GAME_77"), folders = folders)
            .addCustom(args("path" to folder.path)).jsonObject

        assertEquals("CUSTOM_GAME_77", data.str("app_id"))
        assertEquals(77, data["game_id"]!!.jsonPrimitive.int)
        assertEquals("CUSTOM_GAME", data.str("game_source"))
        assertEquals(folder.name, data.str("name"))
        assertEquals(folder.path, data.str("path"))
        assertTrue(data["added"]!!.jsonPrimitive.boolean)
        assertTrue(data["has_container"]!!.jsonPrimitive.boolean)
        assertEquals("Game.exe", data.str("executable"))
        assertEquals(listOf(folder.path), folders.added)
        assertEquals(listOf("CUSTOM_GAME_77"), folders.notified.map { it.appId })
    }

    @Test
    fun addCustom_resolvesThePathBeforeUsingIt() {
        val folder = gameFolder()
        val folders = FakeCustomFolders()

        val data = gameCommands(folders = folders)
            .addCustom(args("path" to "${folder.path}/./../${folder.name}//")).jsonObject

        assertEquals(folder.path, data.str("path"))
        assertEquals(listOf(folder.path), folders.added)
    }

    @Test
    fun addCustom_sdcardAliasIsSpelledAsTheSharedStorageRoot() {
        val folder = gameFolder()
        val folders = FakeCustomFolders(sharedRoot = folder.parent)

        val data = gameCommands(folders = folders).addCustom(args("path" to "/sdcard/${folder.name}")).jsonObject

        assertEquals(folder.path, data.str("path"))
        assertEquals(listOf(folder.path), folders.added)
    }

    @Test
    fun addCustom_keepsSymlinksAndWhitespaceAsSpelled() {
        val root = gameFolder()
        File(root, "My Game").mkdir()
        val spaced = File(root, "My Game ").apply { mkdir() }
        val link = File(root, "link").also { Files.createSymbolicLink(it.toPath(), spaced.toPath()) }
        val folders = FakeCustomFolders()

        val viaSpace = gameCommands(folders = folders).addCustom(args("path" to spaced.path)).jsonObject
        val viaLink = gameCommands(folders = folders).addCustom(args("path" to link.path)).jsonObject

        assertEquals("trailing space is part of the name", spaced.path, viaSpace.str("path"))
        assertEquals("symlinks are not resolved, as the folder picker does not", link.path, viaLink.str("path"))
        assertEquals(listOf(spaced.path, link.path), folders.added)
    }

    @Test
    fun addCustom_folderAlreadyInTheLibraryIsNotAddedAgain() {
        val folders = FakeCustomFolders(alreadyAdded = true)

        val data = gameCommands(folders = folders).addCustom(args("path" to gameFolder().path)).jsonObject

        assertFalse(data["added"]!!.jsonPrimitive.boolean)
        assertTrue("the library did not change", folders.notified.isEmpty())
    }

    @Test
    fun addCustom_folderImportedAsSteamGameHasNoExecutable() {
        val folders = FakeCustomFolders(entry = { InstalledGame("STEAM_10", 10, GameSource.STEAM, "Half-Life") })

        val data = gameCommands(folders = folders).addCustom(args("path" to gameFolder().path)).jsonObject

        assertEquals("STEAM_10", data.str("app_id"))
        assertEquals(JsonNull, data["executable"])
    }

    @Test
    fun addCustom_withoutStorageAccessAddsNothing() {
        val folders = FakeCustomFolders(storageAccess = false)

        val error = failure { gameCommands(folders = folders).addCustom(args("path" to gameFolder().path)) }

        assertEquals(AgentErrorCode.STORAGE_ACCESS_DENIED, error.code)
        assertTrue("the message says how to grant access", error.message!!.contains(GRANT_HINT))
        assertTrue(folders.added.isEmpty())
    }

    @Test
    fun addCustom_badPathsAreBadArgs() {
        val file = File(gameFolder(), "game.exe").apply { writeText("MZ") }
        val folders = FakeCustomFolders()
        val cases = listOf(
            JsonObject(emptyMap()),
            args("path" to "  "),
            args("path" to "Games/PC/Yakuza"),
            args("path" to "/storage/emulated/0/\u0000x"),
            args("path" to "/" + "a".repeat(5000)),
            buildJsonObject { put("path", 1) },
            args("path" to file.path),
            args("path" to File(file.parentFile, "missing").path),
        )

        cases.forEach { case ->
            assertEquals("$case", AgentErrorCode.BAD_ARGS, failure { gameCommands(folders = folders).addCustom(case) }.code)
        }
        assertTrue(folders.added.isEmpty())
    }

    @Test
    fun addCustom_folderTheAppRefusesIsBadArgs() {
        val folders = FakeCustomFolders(entry = { null })

        val error = failure { gameCommands(folders = folders).addCustom(args("path" to gameFolder().path)) }

        assertEquals(AgentErrorCode.BAD_ARGS, error.code)
        assertTrue(folders.added.isEmpty())
    }

    // --- components.install ---

    private class FakeInstalls(
        val fresh: ManifestData = ManifestData.empty(),
        val result: (InstallPlan) -> ManifestInstallResult = { ManifestInstallResult(true, "installed ${it.entry.id}") },
    ) {
        val scope = TestScope()
        val queue = ComponentInstallQueue(scope)
        val ran = mutableListOf<InstallPlan>()
        var gate: CompletableDeferred<Unit>? = null

        fun deps() = ComponentInstallDeps(
            queue = queue,
            freshManifest = { fresh },
            run = { plan, onProgress ->
                ran += plan
                onProgress(0.5f)
                gate?.await()
                result(plan)
            },
        )
    }

    private fun installs(fake: FakeInstalls, manifest: ManifestData = ManifestData.empty()) =
        ComponentCommands(installs = fake.deps()) { sources(manifest) }

    private fun installArgs(target: String, value: String, variant: String? = null) = buildJsonObject {
        put("target", target)
        put("value", value)
        variant?.let { put("variant", it) }
    }

    private fun manifestOf(vararg items: Pair<String, List<ManifestEntry>>) = ManifestData(version = 1, updatedAt = null, items = mapOf(*items))

    @Test
    fun componentsInstall_downloadsInTheBackgroundAndReportsProgress() {
        val fake = FakeInstalls().apply { gate = CompletableDeferred() }
        val commands = installs(fake, manifestOf(ManifestContentTypes.DXVK to listOf(entry("2.3.1-1-gplasync-0"))))
        val request = installArgs(ComponentCommands.TARGET_DXVK, "2.3.1-1-GPLASYNC-0")

        val queued = commands.install(request).jsonObject
        fake.scope.runCurrent()
        val downloading = commands.install(request).jsonObject
        fake.gate!!.complete(Unit)
        fake.scope.runCurrent()
        val done = commands.install(request).jsonObject

        assertEquals("queued", queued.str("state"))
        assertEquals("2.3.1-1-gplasync-0", queued.str("value"))
        assertEquals("downloading", downloading.str("state"))
        assertEquals(0.5, downloading["progress"]!!.jsonPrimitive.double, 0.0)
        assertEquals("installed", done.str("state"))
        assertEquals("installed 2.3.1-1-gplasync-0", done.str("message"))
        val plan = fake.ran.single()
        assertEquals("one download for three requests", ContentProfile.ContentType.CONTENT_TYPE_DXVK, plan.contentType)
        assertFalse(plan.isDriver)
    }

    @Test
    fun componentsInstall_installedVersionIsNotDownloaded() {
        val fake = FakeInstalls()

        val data = installs(fake, manifestOf(ManifestContentTypes.DXVK to listOf(entry("2.7.1"))))
            .install(installArgs(ComponentCommands.TARGET_DXVK, "2.7.1")).jsonObject
        fake.scope.runCurrent()

        assertEquals("installed", data.str("state"))
        assertTrue(fake.ran.isEmpty())
    }

    @Test
    fun componentsInstall_failedDownloadIsAnsweredOnceWithWhyThenRetried() {
        val fake = FakeInstalls(result = { ManifestInstallResult(false, "Download failed: timeout") })
        val commands = installs(fake, manifestOf(ManifestContentTypes.FEXCORE to listOf(entry("2609-0"))))
        val request = installArgs(ComponentCommands.TARGET_FEXCORE, "2609-0")

        commands.install(request)
        fake.scope.runCurrent()
        val failed = commands.install(request).jsonObject
        val retried = commands.install(request).jsonObject
        fake.scope.runCurrent()

        assertEquals("failed", failed.str("state"))
        assertEquals("Download failed: timeout", failed.str("message"))
        assertEquals("the call after the failure retries", "queued", retried.str("state"))
        assertEquals(2, fake.ran.size)
    }

    @Test
    fun componentsInstall_runsOneDownloadAtATime() {
        val fake = FakeInstalls().apply { gate = CompletableDeferred() }
        val manifest = manifestOf(ManifestContentTypes.FEXCORE to listOf(entry("2609-0"), entry("2608-0")))
        val commands = installs(fake, manifest)

        commands.install(installArgs(ComponentCommands.TARGET_FEXCORE, "2609-0"))
        commands.install(installArgs(ComponentCommands.TARGET_FEXCORE, "2608-0"))
        fake.scope.runCurrent()

        assertEquals(listOf("2609-0"), fake.ran.map { it.entry.id })
        assertEquals(ComponentInstallQueue.State.QUEUED, fake.queue.find(ComponentCommands.TARGET_FEXCORE, "2608-0", null)!!.state)
        fake.gate!!.complete(Unit)
        fake.scope.runCurrent()
        assertEquals(listOf("2609-0", "2608-0"), fake.ran.map { it.entry.id })
    }

    @Test
    fun componentsInstall_sharedBox64IdNeedsAVariant() {
        val fake = FakeInstalls()
        val manifest = manifestOf(
            ManifestContentTypes.BOX64 to listOf(entry("0.4.4-1", "bionic")),
            ManifestContentTypes.WOWBOX64 to listOf(entry("0.4.4-1")),
        )
        val commands = installs(fake, manifest)

        val ambiguous = failure { commands.install(installArgs(ComponentCommands.TARGET_BOX64, "0.4.4-1")) }
        commands.install(installArgs(ComponentCommands.TARGET_BOX64, "0.4.4-1", variant = "arm64ec"))
        fake.scope.runCurrent()

        assertEquals(AgentErrorCode.BAD_ARGS, ambiguous.code)
        assertEquals(listOf("bionic", "arm64ec"), ambiguous.details!!.jsonObject["variants"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64, fake.ran.single().contentType)
    }

    @Test
    fun componentsInstall_installedBox64DoesNotStandInForWowBox64() {
        val fake = FakeInstalls()
        val manifest = manifestOf(
            ManifestContentTypes.BOX64 to listOf(entry("0.4.4-1", "bionic")),
            ManifestContentTypes.WOWBOX64 to listOf(entry("0.4.4-1")),
        )
        val base = sources(manifest)
        val withBox64 = base.copy(installed = base.installed.copy(box64 = listOf("0.4.4-1")))
        val commands = ComponentCommands(installs = fake.deps()) { withBox64 }

        val bionic = commands.install(installArgs(ComponentCommands.TARGET_BOX64, "0.4.4-1", variant = "bionic")).jsonObject
        val arm64ec = commands.install(installArgs(ComponentCommands.TARGET_BOX64, "0.4.4-1", variant = "arm64ec")).jsonObject
        val noVariant = failure { commands.install(installArgs(ComponentCommands.TARGET_BOX64, "0.4.4-1")) }
        fake.scope.runCurrent()

        assertEquals("installed Box64 serves bionic", "installed", bionic.str("state"))
        assertEquals("but not WoWBox64", "queued", arm64ec.str("state"))
        assertEquals(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64, fake.ran.single().contentType)
        assertEquals("a shared id still needs a variant", AgentErrorCode.BAD_ARGS, noVariant.code)
    }

    @Test
    fun componentsInstall_looksUpAStaleManifestWhenTheCacheHasNoEntry() {
        val fake = FakeInstalls(
            fresh = manifestOf(
                ManifestContentTypes.PROTON to listOf(entry("proton-11.0-2-arm64ec-1", "bionic")),
                ManifestContentTypes.DRIVER to listOf(entry("Turnip v26.2.0 R4")),
            ),
        )
        val commands = installs(fake)

        commands.install(installArgs(ComponentCommands.TARGET_WINE, "proton-11.0-2-arm64ec-1"))
        commands.install(installArgs(ComponentCommands.TARGET_DRIVER, "Turnip v26.2.0 R4"))
        fake.scope.runCurrent()
        fake.scope.runCurrent()
        val unknown = failure { commands.install(installArgs(ComponentCommands.TARGET_WINE, "proton-99")) }

        assertEquals(ContentProfile.ContentType.CONTENT_TYPE_PROTON, fake.ran[0].contentType)
        assertTrue("drivers install through the driver path", fake.ran[1].isDriver)
        assertEquals(AgentErrorCode.BAD_ARGS, unknown.code)
    }

    @Test
    fun componentsInstall_badArgs() {
        val commands = installs(FakeInstalls())
        val cases = listOf(
            JsonObject(emptyMap()),
            args("target" to "wineVersion"),
            args("target" to "wineVersion", "value" to " "),
            args("target" to "envVars", "value" to "x"),
            buildJsonObject {
                put("target", "wineVersion")
                put("value", 1)
            },
        )

        cases.forEach { case -> assertEquals("$case", AgentErrorCode.BAD_ARGS, failure { commands.install(case) }.code) }
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
        private const val GRANT_HINT = "adb shell appops set app.test MANAGE_EXTERNAL_STORAGE allow"

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
