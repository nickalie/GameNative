package app.gamenative.data

import app.gamenative.PrefManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Stores (game sources) switched on in Settings → Stores. A store that is off is absent from the app:
 * its games, library tab, account entries and background service are hidden or not started, and launch
 * intents for its games are refused. Its installed games, database rows and sign-in are kept, so turning
 * it back on restores everything.
 *
 * Starting and stopping the store services on a switch is [app.gamenative.service.StoreServices]'s job.
 */
object StoreToggles {

    /** On until the user changes them, for new and existing installs alike. */
    val DEFAULT_ENABLED: Set<GameSource> = setOf(GameSource.STEAM, GameSource.CUSTOM_GAME)

    private val state: MutableStateFlow<Set<GameSource>> by lazy { MutableStateFlow(load()) }

    /** The stores that are on; UI collects it to hide and show store entries as the user switches. */
    val enabled: StateFlow<Set<GameSource>> by lazy { state.asStateFlow() }

    fun isEnabled(source: GameSource): Boolean = source in state.value

    /** Persists the switch; callers that should also start or stop the service use StoreServices. */
    fun setEnabled(source: GameSource, enabled: Boolean) {
        PrefManager.setStoreEnabled(source, enabled)
        state.update { if (enabled) it + source else it - source }
    }

    /** Re-reads the switches from [PrefManager]; for tests that swap its backing store. */
    internal fun reload() {
        state.value = load()
    }

    private fun load(): Set<GameSource> = GameSource.entries.filterTo(LinkedHashSet()) { PrefManager.isStoreEnabled(it) }

    /**
     * The sources whose games the library lists: on in [enabled] and, for the account stores (GOG, Epic,
     * Amazon), signed in. Steam rows only exist after a Steam login and custom games need no account, so
     * those two depend on the switch alone.
     */
    fun librarySources(enabled: Set<GameSource>, hasAccount: (GameSource) -> Boolean): Set<GameSource> =
        enabled.filterTo(LinkedHashSet()) { source ->
            source == GameSource.STEAM || source == GameSource.CUSTOM_GAME || hasAccount(source)
        }
}
