package app.gamenative.agent.commands

import android.content.Context
import android.os.Build
import android.os.Environment
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.agent.AgentDispatcher
import app.gamenative.agent.AgentErrorCode
import app.gamenative.agent.AgentException
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.sync.FrontendSyncManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.ContainerManager
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An installed game as the agent API reports it. [gameId] is `null` when the store id is not numeric. */
data class InstalledGame(
    val appId: String,
    val gameId: Int?,
    val source: GameSource,
    val name: String,
)

/** What `games.add_custom` needs from the app; replaced in tests. */
class CustomFolderDeps(
    val hasStorageAccess: (path: String) -> Boolean,
    /** The library entry the folder becomes (a Steam entry when imported as a Steam game), or null when refused. */
    val libraryEntry: (path: String) -> InstalledGame?,
    /** Adds the folder to the manually added custom game folders; false when it was already there. */
    val addFolder: (path: String) -> Boolean,
    /** Tells the library screen to reload after [game] was added. */
    val notifyLibrary: (game: InstalledGame) -> Unit,
    /** The exe the first launch selects by itself, or null when the folder has none or several. */
    val autoExecutable: (path: String) -> String?,
    /** The primary shared storage root (`/storage/emulated/<user>`) that `/sdcard` stands for. */
    val sharedStorageRoot: String,
    /** The adb command that grants the app the storage access [hasStorageAccess] checks. */
    val storageGrantHint: String,
) {
    companion object {
        fun forApp(context: Context) = CustomFolderDeps(
            hasStorageAccess = { CustomGameScanner.hasStoragePermission(context, it) },
            libraryEntry = { path -> CustomGameScanner.createLibraryItemFromFolder(path)?.let(GameCommands::toInstalledGame) },
            addFolder = { path ->
                PrefManager.addCustomGameManualFolder(path).also { CustomGameScanner.invalidateCache() }
            },
            notifyLibrary = { game ->
                game.gameId?.let { PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(it, game.source)) }
            },
            autoExecutable = { CustomGameScanner.findUniqueExeRelativeToFolder(it) },
            sharedStorageRoot = Environment.getExternalStorageDirectory().absolutePath,
            // Below Android 11 CustomGameScanner.hasStoragePermission checks READ_EXTERNAL_STORAGE instead.
            storageGrantHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
            } else {
                "adb shell pm grant ${context.packageName} android.permission.READ_EXTERNAL_STORAGE"
            },
        )
    }
}

/**
 * `games.list {source?}`: installed games from every store that is on in Settings → Stores, plus custom
 * games while that switch is on. Custom folders that were imported as a Steam game share its `STEAM_<id>`
 * and are dropped in favor of the store entry, as the library screen does.
 *
 * `games.add_custom {path}`: adds a folder on the device as a custom game, the way the library's
 * "add custom game" folder picker does. Refused with `STORE_DISABLED` while Custom Games is off.
 */
class GameCommands(
    private val storeGames: () -> List<InstalledGame>,
    private val customGames: () -> List<InstalledGame>,
    private val containerIds: () -> Set<String>,
    private val customFolders: CustomFolderDeps,
    private val isStoreEnabled: (GameSource) -> Boolean,
) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(LIST) { list(it) }
        dispatcher.register(ADD_CUSTOM) { addCustom(it) }
    }

    fun list(args: JsonObject): JsonElement {
        val source = CommandArgs.optString(args, "source")?.let { CommandArgs.gameSource(it) }
        val games = installedGames().filter { source == null || it.source == source }
        val withContainer = if (games.isEmpty()) emptySet() else containerIds()
        return buildJsonArray {
            games.forEach { game ->
                add(
                    buildJsonObject {
                        put("app_id", game.appId)
                        put("game_id", game.gameId)
                        put("game_source", game.source.name)
                        put("name", game.name)
                        put("has_container", game.appId in withContainer)
                    },
                )
            }
        }
    }

    fun addCustom(args: JsonObject): JsonElement {
        val path = folderPath(args)
        if (!isStoreEnabled(GameSource.CUSTOM_GAME)) {
            throw AgentException(
                AgentErrorCode.STORE_DISABLED,
                "Custom Games is turned off in Settings → Stores",
                buildJsonObject { put("source", GameSource.CUSTOM_GAME.name) },
            )
        }
        if (!customFolders.hasStorageAccess(path)) {
            throw AgentException(
                AgentErrorCode.STORAGE_ACCESS_DENIED,
                "the app has no access to $path; grant it with: ${customFolders.storageGrantHint}",
            )
        }
        if (!File(path).isDirectory) throw CommandArgs.badArgs("path is not a directory: $path")
        val game = customFolders.libraryEntry(path) ?: throw CommandArgs.badArgs("the app refused the folder: $path")
        val added = customFolders.addFolder(path)
        if (added) customFolders.notifyLibrary(game)
        return buildJsonObject {
            put("app_id", game.appId)
            put("game_id", game.gameId)
            put("game_source", game.source.name)
            put("name", game.name)
            put("path", path)
            put("added", added)
            put("has_container", game.appId in containerIds())
            put("executable", if (game.source == GameSource.CUSTOM_GAME) customFolders.autoExecutable(path) else null)
        }
    }

    /**
     * The absolute `path` argument, normalized the way the folder picker's paths are: `.`/`..` resolved, no
     * trailing slash, and the `/sdcard` alias spelled as the shared storage root, so both spellings are one
     * folder. Other symlinks are kept: the custom game code compares paths as spelled (`/data/user/0` is
     * the app's own data dir, not `/data/data`), and whitespace is part of a folder name.
     */
    private fun folderPath(args: JsonObject): String {
        val raw = CommandArgs.optString(args, "path")
        if (raw.isNullOrEmpty()) throw CommandArgs.badArgs("path is required")
        if (raw.length > MAX_PATH_CHARS) throw CommandArgs.badArgs("path is longer than $MAX_PATH_CHARS characters")
        if (!raw.startsWith("/") || raw.contains('\u0000')) throw CommandArgs.badArgs("path must be an absolute device path")
        val path = File(raw).normalize().absolutePath
        return when {
            path == SDCARD_ALIAS -> customFolders.sharedStorageRoot
            path.startsWith("$SDCARD_ALIAS/") -> customFolders.sharedStorageRoot + path.removePrefix(SDCARD_ALIAS)
            else -> path
        }
    }

    private fun installedGames(): List<InstalledGame> {
        val byAppId = LinkedHashMap<String, InstalledGame>()
        storeGames().forEach { byAppId.putIfAbsent(it.appId, it) }
        if (isStoreEnabled(GameSource.CUSTOM_GAME)) customGames().forEach { byAppId.putIfAbsent(it.appId, it) }
        return byAppId.values.filter { isStoreEnabled(it.source) }
    }

    companion object {
        const val LIST = "games.list"
        const val ADD_CUSTOM = "games.add_custom"

        private const val MAX_PATH_CHARS = 4096
        private const val SDCARD_ALIAS = "/sdcard"

        /** Installed games from the four store DAOs (the same source the frontend export uses). */
        fun storeGamesFrom(context: Context): List<InstalledGame> {
            val daos = EntryPointAccessors.fromApplication(
                context.applicationContext,
                FrontendSyncManager.FrontendSyncEntryPoint::class.java,
            )
            return runBlocking {
                val steam = daos.steamAppDao().getInstalledGames().map { InstalledGame("STEAM_${it.id}", it.id, GameSource.STEAM, it.name) }
                val epic = daos.epicGameDao().getInstalledGames().map { InstalledGame("EPIC_${it.id}", it.id, GameSource.EPIC, it.title) }
                val gog = daos.gogGameDao().getInstalledGames().map {
                    InstalledGame("GOG_${it.id}", it.id.toIntOrNull(), GameSource.GOG, it.title)
                }
                val amazon = daos.amazonGameDao().getInstalledGames().map {
                    InstalledGame("AMAZON_${it.appId}", it.appId, GameSource.AMAZON, it.title)
                }
                steam + epic + gog + amazon
            }
        }

        fun customGamesFrom(): List<InstalledGame> = CustomGameScanner.scanAsLibraryItems().map(::toInstalledGame)

        internal fun toInstalledGame(item: LibraryItem): InstalledGame {
            val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(item.appId) }.getOrNull()
            return InstalledGame(item.appId, gameId, item.gameSource, item.name)
        }

        fun containerIdsFrom(context: Context): Set<String> = ContainerManager(context).containers.map { it.id }.toSet()
    }
}
