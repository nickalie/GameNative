package app.gamenative.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import app.gamenative.PrefManager
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.awaitUntil
import app.gamenative.utils.installFakePrefManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTogglesTest {

    @After
    fun resetToDefaults() {
        // StoreToggles is a process-wide object: leave it at the defaults for other tests in this JVM.
        installFakePrefManager(FakeDataStore()).use { StoreToggles.reload() }
    }

    @Test
    fun onlySteamAndCustomGamesAreOnByDefault() {
        installFakePrefManager(FakeDataStore()).use {
            StoreToggles.reload()

            assertEquals(setOf(GameSource.STEAM, GameSource.CUSTOM_GAME), StoreToggles.enabled.value)
            assertFalse(PrefManager.isStoreEnabled(GameSource.GOG))
            assertFalse(PrefManager.isStoreEnabled(GameSource.EPIC))
            assertFalse(PrefManager.isStoreEnabled(GameSource.AMAZON))
        }
    }

    @Test
    fun storedSwitchesWinOverTheDefaults() {
        val stored = preferencesOf(
            booleanPreferencesKey("store_enabled_gog") to true,
            booleanPreferencesKey("store_enabled_steam") to false,
        )
        installFakePrefManager(FakeDataStore(stored)).use {
            StoreToggles.reload()

            assertEquals(setOf(GameSource.CUSTOM_GAME, GameSource.GOG), StoreToggles.enabled.value)
        }
    }

    @Test
    fun setEnabledUpdatesTheStateAndPersists() {
        installFakePrefManager(FakeDataStore()).use {
            StoreToggles.reload()

            StoreToggles.setEnabled(GameSource.EPIC, true)
            StoreToggles.setEnabled(GameSource.STEAM, false)

            assertTrue(StoreToggles.isEnabled(GameSource.EPIC))
            assertFalse(StoreToggles.isEnabled(GameSource.STEAM))
            awaitUntil { PrefManager.isStoreEnabled(GameSource.EPIC) && !PrefManager.isStoreEnabled(GameSource.STEAM) }
            StoreToggles.reload()
            assertEquals(setOf(GameSource.CUSTOM_GAME, GameSource.EPIC), StoreToggles.enabled.value)
        }
    }

    @Test
    fun librarySourcesNeedTheSwitchAndAnAccountForAccountStores() {
        val enabled = setOf(GameSource.STEAM, GameSource.CUSTOM_GAME, GameSource.GOG, GameSource.EPIC)

        val sources = StoreToggles.librarySources(enabled) { it == GameSource.GOG || it == GameSource.AMAZON }

        // EPIC has no account, AMAZON is off; STEAM and CUSTOM_GAME depend on the switch alone.
        assertEquals(setOf(GameSource.STEAM, GameSource.CUSTOM_GAME, GameSource.GOG), sources)
    }

    @Test
    fun librarySourcesAreEmptyWhenEveryStoreIsOff() {
        assertTrue(StoreToggles.librarySources(emptySet()) { true }.isEmpty())
    }
}
