package app.gamenative.ui.enums

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
    fun traversalUsesOnlyEffectiveVisibleTabs() {
        val visible = listOf(LibraryTab.ALL, LibraryTab.GOG)

        assertEquals(LibraryTab.GOG, LibraryTab.ALL.next(visible))
        assertEquals(LibraryTab.ALL, LibraryTab.GOG.next(visible))
        assertEquals(LibraryTab.GOG, LibraryTab.ALL.previous(visible))
    }

    @Test
    fun effectiveVisibleTabs_dropsRecommendedWhileRecommendationsAreOff() {
        val persisted = LibraryTab.normalizeVisibleTabs("v2:RECOMMENDED,STEAM", LibraryTab.entries.toList())

        assertEquals(
            listOf(LibraryTab.ALL, LibraryTab.FAVORITES, LibraryTab.STEAM, LibraryTab.LOCAL),
            LibraryTab.effectiveVisibleTabs(persisted, recommendationsEnabled = false),
        )
        assertEquals(
            listOf(LibraryTab.RECOMMENDED, LibraryTab.ALL, LibraryTab.FAVORITES, LibraryTab.STEAM, LibraryTab.LOCAL),
            LibraryTab.effectiveVisibleTabs(persisted, recommendationsEnabled = true),
        )
    }

    @Test
    fun effectiveVisibleTabs_traversalNeverReachesRecommendedWhileOff() {
        val visible = LibraryTab.effectiveVisibleTabs(LibraryTab.entries.toList(), recommendationsEnabled = false)

        assertEquals(LibraryTab.LOCAL, LibraryTab.ALL.previous(visible))
        assertEquals(LibraryTab.ALL, LibraryTab.LOCAL.next(visible))
        // A stale current tab that is no longer visible falls back into the visible list.
        assertEquals(LibraryTab.FAVORITES, LibraryTab.RECOMMENDED.next(visible))
    }

    @Test
    fun availableEntries_gatesOnlyRecommended() {
        assertEquals(LibraryTab.entries.toList(), LibraryTab.availableEntries(recommendationsEnabled = true))
        assertEquals(
            LibraryTab.entries.filter { it != LibraryTab.RECOMMENDED },
            LibraryTab.availableEntries(recommendationsEnabled = false),
        )
    }

    @Test
    fun configurableEntriesFor_hidesRecommendedWhileOff() {
        assertEquals(
            listOf(LibraryTab.STEAM, LibraryTab.GOG, LibraryTab.EPIC, LibraryTab.AMAZON),
            LibraryTab.configurableEntriesFor(recommendationsEnabled = false),
        )
        assertEquals(LibraryTab.configurableEntries, LibraryTab.configurableEntriesFor(recommendationsEnabled = true))
    }
}
