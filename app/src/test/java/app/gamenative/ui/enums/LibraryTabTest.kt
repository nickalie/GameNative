package app.gamenative.ui.enums

import app.gamenative.data.GameSource
import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTabTest {
    private val supported = listOf(
        LibraryTab.RECOMMENDED,
        LibraryTab.ALL,
        LibraryTab.FAVORITES,
        LibraryTab.STEAM,
        LibraryTab.GOG,
        LibraryTab.EPIC,
        LibraryTab.AMAZON,
        LibraryTab.LOCAL,
    )

    @Test
    fun normalizeVisibleTabs_usesSupportedTabOrder() {
        val result = LibraryTab.normalizeVisibleTabs("v2:GOG,ALL,STEAM", supported)

        assertEquals(
            listOf(
                LibraryTab.ALL,
                LibraryTab.FAVORITES,
                LibraryTab.STEAM,
                LibraryTab.GOG,
                LibraryTab.LOCAL,
            ),
            result,
        )
    }

    @Test
    fun normalizeVisibleTabs_defaultsToAllSupportedTabs() {
        val result = LibraryTab.normalizeVisibleTabs("", supported)

        assertEquals(supported, result)
    }

    @Test
    fun normalizeVisibleTabs_keepsFixedTabsVisibleAndDropsInvalidValues() {
        val result = LibraryTab.normalizeVisibleTabs("v2:UNKNOWN,STEAM,STEAM", supported)

        assertEquals(
            listOf(
                LibraryTab.ALL,
                LibraryTab.FAVORITES,
                LibraryTab.STEAM,
                LibraryTab.LOCAL,
            ),
            result,
        )
    }

    @Test
    fun normalizeVisibleTabs_migratesHiddenPreferenceFormat() {
        val result = LibraryTab.normalizeVisibleTabs(
            "!RECOMMENDED,!ALL,!FAVORITES,!EPIC,!STEAM,!LOCAL",
            supported,
        )

        assertEquals(
            listOf(
                LibraryTab.ALL,
                LibraryTab.FAVORITES,
                LibraryTab.GOG,
                LibraryTab.AMAZON,
                LibraryTab.LOCAL,
            ),
            result,
        )
    }

    @Test
    fun normalizeVisibleTabs_addsNewTabsWhenMigratingLegacyPreferences() {
        val result = LibraryTab.normalizeVisibleTabs("ALL,STEAM,GOG", supported)

        assertEquals(supported, result)
    }

    @Test
    fun serializeVisibleTabs_roundTripsSelection() {
        val visibleTabs = listOf(
            LibraryTab.RECOMMENDED,
            LibraryTab.ALL,
            LibraryTab.FAVORITES,
            LibraryTab.GOG,
            LibraryTab.LOCAL,
        )

        val restored = LibraryTab.normalizeVisibleTabs(
            LibraryTab.serializeVisibleTabs(visibleTabs),
            supported,
        )

        assertEquals(visibleTabs, restored)
    }

    @Test
    fun configurableEntries_containsRecommendedAndStoreTabs() {
        assertEquals(
            listOf(LibraryTab.RECOMMENDED, LibraryTab.STEAM, LibraryTab.GOG, LibraryTab.EPIC, LibraryTab.AMAZON),
            LibraryTab.configurableEntries,
        )
    }

    @Test
    fun visibleFor_defaultStoresHideTheTabsOfStoresThatAreOff() {
        val visible = LibraryTab.visibleFor(setOf(GameSource.STEAM, GameSource.CUSTOM_GAME))

        // RECOMMENDED shows GOG store recommendations, so it goes with GOG.
        assertEquals(listOf(LibraryTab.ALL, LibraryTab.FAVORITES, LibraryTab.STEAM, LibraryTab.LOCAL), visible)
    }

    @Test
    fun visibleFor_everyStoreOnShowsEveryTab() {
        assertEquals(LibraryTab.entries.toList(), LibraryTab.visibleFor(GameSource.entries.toSet()))
    }

    @Test
    fun visibleFor_everyStoreOffKeepsTheStorelessTabs() {
        assertEquals(listOf(LibraryTab.ALL, LibraryTab.FAVORITES), LibraryTab.visibleFor(emptySet()))
    }

    @Test
    fun everyStoreHasATab() {
        assertEquals(GameSource.entries.toSet(), LibraryTab.entries.mapNotNull { it.store }.toSet())
    }

    @Test
    fun traversalUsesOnlyEffectiveVisibleTabs() {
        val visible = listOf(LibraryTab.ALL, LibraryTab.GOG)

        assertEquals(LibraryTab.GOG, LibraryTab.ALL.next(visible))
        assertEquals(LibraryTab.ALL, LibraryTab.GOG.next(visible))
        assertEquals(LibraryTab.GOG, LibraryTab.ALL.previous(visible))
    }
}
