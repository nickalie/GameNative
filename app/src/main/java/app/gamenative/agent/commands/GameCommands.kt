package app.gamenative.agent.commands

import android.content.Context
import app.gamenative.agent.AgentDispatcher
import app.gamenative.data.GameSource
import app.gamenative.sync.FrontendSyncManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.ContainerManager
import dagger.hilt.android.EntryPointAccessors
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

/**
 * `games.list {source?}`: installed games from every store plus custom games. Custom folders that
 * were imported as a Steam game share its `STEAM_<id>` and are dropped in favor of the store entry,
 * as the library screen does.
 */
class GameCommands(
    private val storeGames: () -> List<InstalledGame>,
    private val customGames: () -> List<InstalledGame>,
    private val containerIds: () -> Set<String>,
) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(LIST) { list(it) }
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

    private fun installedGames(): List<InstalledGame> {
        val byAppId = LinkedHashMap<String, InstalledGame>()
        storeGames().forEach { byAppId.putIfAbsent(it.appId, it) }
        customGames().forEach { byAppId.putIfAbsent(it.appId, it) }
        return byAppId.values.toList()
    }

    companion object {
        const val LIST = "games.list"

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

        fun customGamesFrom(): List<InstalledGame> = CustomGameScanner.scanAsLibraryItems().map { item ->
            val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(item.appId) }.getOrNull()
            InstalledGame(item.appId, gameId, item.gameSource, item.name)
        }

        fun containerIdsFrom(context: Context): Set<String> = ContainerManager(context).containers.map { it.id }.toSet()
    }
}
