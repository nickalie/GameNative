package app.gamenative.utils

import android.app.Application
import android.content.Intent
import android.net.Uri
import app.gamenative.agent.AgentHooks
import app.gamenative.agent.AgentLaunchOptions
import app.gamenative.agent.AgentLaunchOptionsStore
import app.gamenative.agent.AgentRuntime
import app.gamenative.data.GameSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IntentLaunchManagerTest {

    @After
    fun resetHooks() {
        AgentHooks.launchOptionsSource = { AgentRuntime.launchOptions }
    }

    private fun launchIntent(appId: Int = 123, source: String? = null) = Intent("app.gamenative.LAUNCH_GAME").apply {
        putExtra("app_id", appId)
        source?.let { putExtra("game_source", it) }
    }

    private fun parse(intent: Intent, enabled: Boolean = true) =
        IntentLaunchManager.parseLaunchIntent(intent) { enabled }

    @Test
    fun `agent extras are parsed when the toggle is on`() {
        val intent = launchIntent().putExtra("agent_debug", true).putExtra("wine_debug_channels", " +d3d,+vulkan ")
        val request = parse(intent)!!
        assertEquals("STEAM_123", request.appId)
        assertEquals(AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+d3d,+vulkan"), request.agentOptions)
    }

    @Test
    fun `agent_debug alone is parsed`() {
        val request = parse(launchIntent(source = "custom_game").putExtra("agent_debug", true))!!
        assertEquals("CUSTOM_GAME_123", request.appId)
        assertEquals(AgentLaunchOptions(agentDebug = true), request.agentOptions)
    }

    @Test
    fun `channels alone are kept raw for the env builder`() {
        val request = parse(launchIntent().putExtra("wine_debug_channels", "+relay,bad!"))!!
        assertEquals(AgentLaunchOptions(agentDebug = false, wineDebugChannels = "+relay,bad!"), request.agentOptions)
    }

    @Test
    fun `toggle off ignores agent extras`() {
        val intent = launchIntent().putExtra("agent_debug", true).putExtra("wine_debug_channels", "+d3d")
        val request = parse(intent, enabled = false)!!
        assertEquals("STEAM_123", request.appId)
        assertEquals(AgentLaunchOptions.NONE, request.agentOptions)
    }

    @Test
    fun `no agent extras does not read the toggle`() {
        var toggleRead = false
        val request = IntentLaunchManager.parseLaunchIntent(launchIntent()) {
            toggleRead = true
            true
        }
        assertNotNull(request)
        assertEquals(AgentLaunchOptions.NONE, request!!.agentOptions)
        assertFalse(toggleRead)
    }

    @Test
    fun `blank channels are dropped and long ones capped`() {
        assertEquals(
            AgentLaunchOptions(agentDebug = true),
            parse(launchIntent().putExtra("agent_debug", true).putExtra("wine_debug_channels", "   "))!!.agentOptions,
        )
        val long = "+d3d," + "x".repeat(1000)
        val channels = parse(launchIntent().putExtra("wine_debug_channels", long))!!.agentOptions.wineDebugChannels!!
        assertEquals(256, channels.length)
        assertTrue(channels.startsWith("+d3d,"))
    }

    @Test
    fun `agent_debug false with no channels gives empty options`() {
        val options = parse(launchIntent().putExtra("agent_debug", false))!!.agentOptions
        assertTrue(options.isEmpty)
    }

    @Test
    fun `view uri never carries agent options`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("gamenative://run?appid=123&gamesource=steam"))
            .putExtra("agent_debug", true)
        val request = parse(intent)!!
        assertEquals("STEAM_123", request.appId)
        assertEquals(AgentLaunchOptions.NONE, request.agentOptions)
    }

    @Test
    fun `invalid app id still returns null`() {
        assertNull(parse(launchIntent(appId = -1).putExtra("agent_debug", true)))
    }

    @Test
    fun `an extras-less intent overwrites stored agent options`() {
        val store = AgentLaunchOptionsStore()
        AgentHooks.launchOptionsSource = { store }

        // MainActivity.handleLaunchIntent writes the parsed options for every launch intent
        val agent = parse(launchIntent().putExtra("agent_debug", true))!!
        AgentHooks.onLaunchIntent(agent.appId, agent.agentOptions)
        assertTrue(store.peek("STEAM_123").agentDebug)

        val plain = parse(launchIntent())!!
        AgentHooks.onLaunchIntent(plain.appId, plain.agentOptions)
        assertEquals(AgentLaunchOptions.NONE, store.peek("STEAM_123"))
    }

    @Test
    fun `disabledStoreOf names the store of a game whose store is off`() {
        val enabled = setOf(GameSource.STEAM, GameSource.CUSTOM_GAME)

        assertEquals(GameSource.GOG, IntentLaunchManager.disabledStoreOf("GOG_1207658924") { it in enabled })
        assertEquals(GameSource.AMAZON, IntentLaunchManager.disabledStoreOf("AMAZON_42") { it in enabled })
        assertNull(IntentLaunchManager.disabledStoreOf("STEAM_123") { it in enabled })
        assertNull(IntentLaunchManager.disabledStoreOf("CUSTOM_GAME_7") { it in enabled })
        assertEquals(GameSource.STEAM, IntentLaunchManager.disabledStoreOf("STEAM_123") { false })
    }
}
