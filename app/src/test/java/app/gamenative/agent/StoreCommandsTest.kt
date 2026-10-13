package app.gamenative.agent

import app.gamenative.agent.commands.StoreCommands
import app.gamenative.data.GameSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoreCommandsTest {

    private val enabled = mutableSetOf(GameSource.STEAM, GameSource.CUSTOM_GAME)
    private val switched = mutableListOf<Pair<GameSource, Boolean>>()

    private val commands = StoreCommands(
        isEnabled = { it in enabled },
        setEnabled = { source, on ->
            switched += source to on
            if (on) enabled += source else enabled -= source
        },
    )

    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content

    private fun failure(block: () -> Unit): AgentException {
        try {
            block()
        } catch (e: AgentException) {
            return e
        }
        fail("expected an AgentException")
        throw AssertionError()
    }

    @Test
    fun listReportsEverySourceWithItsSwitch() {
        val stores = commands.list().jsonArray.map { it.jsonObject }

        assertEquals(GameSource.entries.map { it.name }, stores.map { it.str("source") })
        val on = stores.filter { it["enabled"]!!.jsonPrimitive.boolean }.map { it.str("source") }
        assertEquals(listOf("STEAM", "CUSTOM_GAME"), on)
    }

    /** `stores.set` args; null leaves the key out. */
    private fun setArgs(source: String?, enabled: Any?) = buildJsonObject {
        source?.let { put("source", it) }
        when (enabled) {
            is Boolean -> put("enabled", enabled)
            is String -> put("enabled", enabled)
        }
    }

    @Test
    fun setTurnsAStoreOnCaseInsensitively() {
        val result = commands.set(setArgs("gog", true)).jsonObject

        assertEquals("GOG", result.str("source"))
        assertTrue(result["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(result["changed"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(GameSource.GOG to true), switched)
    }

    @Test
    fun setToTheCurrentValueChangesNothing() {
        val result = commands.set(setArgs("STEAM", true)).jsonObject

        assertFalse(result["changed"]!!.jsonPrimitive.boolean)
        assertTrue(switched.isEmpty())
    }

    @Test
    fun setTurnsAStoreOff() {
        commands.set(setArgs("STEAM", false))

        assertEquals(listOf(GameSource.STEAM to false), switched)
    }

    @Test
    fun missingOrInvalidArgumentsAreBadArgs() {
        val cases = listOf(
            setArgs(null, true),
            setArgs("GOG", null),
            setArgs("ORIGIN", true),
            setArgs("GOG", "yes"),
        )
        cases.forEach { args ->
            assertEquals(args.toString(), AgentErrorCode.BAD_ARGS, failure { commands.set(args) }.code)
        }
        assertTrue(switched.isEmpty())
    }
}
