package app.gamenative.ui.enums

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.StoreToggles

enum class LibraryTab(
    @get:StringRes val labelResId: Int,
    val showCustom: Boolean,
    val showSteam: Boolean,
    val showGoG: Boolean,
    val showEpic: Boolean,
    val showAmazon: Boolean,
    val installedOnly: Boolean,
    val icon: ImageVector? = null,
    /** The store the tab belongs to; hidden while that store is off in Settings → Stores. */
    val store: GameSource? = null,
) {
    RECOMMENDED(
        labelResId = R.string.tab_recommended,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        installedOnly = false,
        icon = Icons.Outlined.Explore,
        // GOG store recommendations seeded from the library
        store = GameSource.GOG,
    ),
    ALL(
        labelResId = R.string.tab_all,
        showCustom = true,
        showSteam = true,
        showGoG = true,
        showEpic = true,
        showAmazon = true,
        installedOnly = false,
    ),
    FAVORITES(
        labelResId = R.string.tab_favorites,
        showCustom = true,
        showSteam = true,
        showGoG = true,
        showEpic = true,
        showAmazon = true,
        installedOnly = false,
        icon = Icons.Rounded.Star,
    ),
    STEAM(
        labelResId = R.string.tab_steam,
        showCustom = false,
        showSteam = true,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        installedOnly = false,
        store = GameSource.STEAM,
    ),
    GOG(
        labelResId = R.string.tab_gog,
        showCustom = false,
        showSteam = false,
        showGoG = true,
        showEpic = false,
        showAmazon = false,
        installedOnly = false,
        store = GameSource.GOG,
    ),
    EPIC(
        labelResId = R.string.tab_epic,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = true,
        showAmazon = false,
        installedOnly = false,
        store = GameSource.EPIC,
    ),
    AMAZON(
        labelResId = R.string.tab_amazon,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = true,
        installedOnly = false,
        store = GameSource.AMAZON,
    ),
    LOCAL(
        labelResId = R.string.tab_local,
        showCustom = true,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        installedOnly = false,
        store = GameSource.CUSTOM_GAME,
    ),
    ;

    companion object {
        val configurableEntries = listOf(RECOMMENDED, STEAM, GOG, EPIC, AMAZON)

        /**
         * Tabs shown in the UI: every tab whose store is on in Settings → Stores. Custom (LOCAL) games
         * work on all flavors: legacy maps folders in place via all-files access, modern imports them
         * into app-owned storage.
         */
        val visibleEntries: List<LibraryTab>
            get() = visibleFor(StoreToggles.enabled.value)

        /** The tabs left when only [enabledStores] are on; tabs without a store always stay. */
        fun visibleFor(enabledStores: Set<GameSource>): List<LibraryTab> =
            entries.filter { tab -> tab.store == null || tab.store in enabledStores }

        fun normalizeVisibleTabs(
            serialized: String,
            supportedTabs: List<LibraryTab> = visibleEntries,
        ): List<LibraryTab> {
            val supported = supportedTabs.distinct()
            if (serialized.isBlank()) return supported

            if (!serialized.startsWith(VISIBLE_TABS_PREFIX)) {
                val hiddenTabs = serialized
                    .split(',')
                    .map { it.trim() }
                    .filter { it.startsWith(HIDDEN_PREFIX) }
                    .map { it.removePrefix(HIDDEN_PREFIX) }
                    .toSet()
                return supported.filter { it !in configurableEntries || it.name !in hiddenTabs }
            }

            val selected = serialized
                .removePrefix(VISIBLE_TABS_PREFIX)
                .split(',')
                .mapNotNull { token ->
                    val value = token.trim()
                    entries.firstOrNull { it.name == value }
                }
                .toSet()

            return supported.filter { it !in configurableEntries || it in selected }
        }

        fun serializeVisibleTabs(tabs: List<LibraryTab>): String =
            tabs.distinct().joinToString(",", prefix = VISIBLE_TABS_PREFIX) { it.name }

        fun LibraryTab.next(visibleTabs: List<LibraryTab> = visibleEntries): LibraryTab {
            val values = visibleTabs.ifEmpty { listOf(ALL) }
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[(index + 1) % values.size]
        }

        fun LibraryTab.previous(visibleTabs: List<LibraryTab> = visibleEntries): LibraryTab {
            val values = visibleTabs.ifEmpty { listOf(ALL) }
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[if (index == 0) values.size - 1 else index - 1]
        }

        private const val HIDDEN_PREFIX = "!"
        private const val VISIBLE_TABS_PREFIX = "v2:"
    }
}
