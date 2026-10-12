package app.gamenative.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLaunchOptionsTest {

    private val debug = AgentLaunchOptions(agentDebug = true, wineDebugChannels = "+d3d,+vulkan")

    @Test
    fun `isEmpty reflects whether any option is set`() {
        assertTrue(AgentLaunchOptions.NONE.isEmpty)
        assertTrue(AgentLaunchOptions(wineDebugChannels = "  ").isEmpty)
        assertFalse(AgentLaunchOptions(agentDebug = true).isEmpty)
        assertFalse(AgentLaunchOptions(wineDebugChannels = "+d3d").isEmpty)
    }

    @Test
    fun `take returns the stored options once`() {
        val store = AgentLaunchOptionsStore()
        store.put("STEAM_1", debug)
        assertEquals(debug, store.peek("STEAM_1"))
        assertEquals(debug, store.take("STEAM_1"))
        assertEquals(AgentLaunchOptions.NONE, store.take("STEAM_1"))
    }

    @Test
    fun `missing entry yields none`() {
        val store = AgentLaunchOptionsStore()
        assertEquals(AgentLaunchOptions.NONE, store.take("STEAM_1"))
        assertEquals(AgentLaunchOptions.NONE, store.peek("STEAM_1"))
    }

    @Test
    fun `every intent overwrites the entry`() {
        val store = AgentLaunchOptionsStore()
        store.put("STEAM_1", debug)
        val other = AgentLaunchOptions(agentDebug = true)
        store.put("STEAM_1", other)
        assertEquals(other, store.take("STEAM_1"))
    }

    @Test
    fun `extras-less intent clears stale options`() {
        val store = AgentLaunchOptionsStore()
        store.put("STEAM_1", debug)
        store.put("STEAM_1", AgentLaunchOptions.NONE)
        assertEquals(AgentLaunchOptions.NONE, store.take("STEAM_1"))
    }

    @Test
    fun `clear drops the entry after not found or a launch error`() {
        val store = AgentLaunchOptionsStore()
        store.put("STEAM_1", debug)
        store.clear("STEAM_1")
        assertEquals(AgentLaunchOptions.NONE, store.take("STEAM_1"))
        store.clear("STEAM_404") // no-op on a missing entry
    }

    @Test
    fun `entries are kept per app id`() {
        val store = AgentLaunchOptionsStore()
        store.put("STEAM_1", debug)
        store.put("GOG_2", AgentLaunchOptions(agentDebug = true))
        store.put("GOG_2", AgentLaunchOptions.NONE)
        assertEquals(debug, store.take("STEAM_1"))
        assertEquals(AgentLaunchOptions.NONE, store.take("GOG_2"))
    }

    @Test
    fun `runtime exposes a single tracker and store`() {
        assertSame(AgentRuntime.tracker, AgentRuntime.tracker)
        assertSame(AgentRuntime.launchOptions, AgentRuntime.launchOptions)
    }
}
