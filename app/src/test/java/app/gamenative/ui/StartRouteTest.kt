package app.gamenative.ui

import app.gamenative.ui.screen.PluviaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StartRouteTest {

    @Test fun signedOutStartsInOfflineLibraryNotLogin() {
        assertEquals(PluviaScreen.Home.route + "?offline=true", startRoute(isSteamLoggedIn = false))
        assertNotEquals(PluviaScreen.LoginUser.route, startRoute(isSteamLoggedIn = false))
    }

    @Test fun loggedInStartsInOnlineLibrary() {
        assertEquals(PluviaScreen.Home.route + "?offline=false", startRoute(isSteamLoggedIn = true))
    }
}
