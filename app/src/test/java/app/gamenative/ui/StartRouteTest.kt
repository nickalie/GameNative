package app.gamenative.ui

import app.gamenative.ui.screen.PluviaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRouteTest {

    @Test fun signedOutStartsInOfflineLibraryNotLogin() {
        assertEquals(PluviaScreen.Home.route + "?offline=true", startRoute(isSteamLoggedIn = false))
        assertNotEquals(PluviaScreen.LoginUser.route, startRoute(isSteamLoggedIn = false))
    }

    @Test fun loggedInStartsInOnlineLibrary() {
        assertEquals(PluviaScreen.Home.route + "?offline=false", startRoute(isSteamLoggedIn = true))
    }

    @Test fun logoutFromOnlineLibraryResetsToOfflineLibrary() {
        assertTrue(shouldResetToOfflineLibrary(HOME_ROUTE_PATTERN, libraryOffline = false))
        assertTrue(shouldResetToOfflineLibrary(PluviaScreen.Settings.route, libraryOffline = false))
        assertTrue(shouldResetToOfflineLibrary(PluviaScreen.Settings.route, libraryOffline = null))
    }

    @Test fun repeatedLogoutKeepsScreenOpenedOverSignedOutLibrary() {
        // The second LoggedOut of one sign-out must not throw away the login form or settings
        assertFalse(shouldResetToOfflineLibrary(PluviaScreen.LoginUser.route, libraryOffline = true))
        assertFalse(shouldResetToOfflineLibrary(PluviaScreen.Settings.route, libraryOffline = true))
        assertFalse(shouldResetToOfflineLibrary(HOME_ROUTE_PATTERN, libraryOffline = true))
    }

    @Test fun logoutDuringGameLeavesGameAlone() {
        assertFalse(shouldResetToOfflineLibrary(PluviaScreen.XServer.route, libraryOffline = false))
    }

    @Test fun logonOnLoginScreenLeavesIt() {
        assertEquals(LogonNavigation.LEAVE_LOGIN, logonNavigation(PluviaScreen.LoginUser.route, libraryOffline = true))
        assertEquals(LogonNavigation.LEAVE_LOGIN, logonNavigation(PluviaScreen.LoginUser.route, libraryOffline = null))
    }

    @Test fun logonOnOfflineLibrarySwitchesItOnline() {
        assertEquals(LogonNavigation.SWITCH_LIBRARY_ONLINE, logonNavigation(HOME_ROUTE_PATTERN, libraryOffline = true))
    }

    @Test fun logonWhileOfflineLibraryIsCoveredSwitchesItOnReturn() {
        assertEquals(
            LogonNavigation.SWITCH_LIBRARY_ONLINE_ON_RETURN,
            logonNavigation(PluviaScreen.Settings.route, libraryOffline = true),
        )
    }

    @Test fun logonWithOnlineOrNoLibraryChangesNothing() {
        assertEquals(LogonNavigation.NONE, logonNavigation(HOME_ROUTE_PATTERN, libraryOffline = false))
        assertEquals(LogonNavigation.NONE, logonNavigation(PluviaScreen.Settings.route, libraryOffline = false))
        assertEquals(LogonNavigation.NONE, logonNavigation(PluviaScreen.Settings.route, libraryOffline = null))
    }
}
