package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreInstallStepGateTest {

    private val calls = mutableListOf<String>()

    @Test
    fun installerFinished_marksStepDoneAndStartsNext() {
        val advanced = advancePreInstallChain(
            exiting = { false },
            completeStep = { calls += "complete" },
            startNext = { calls += "next" },
        )

        assertTrue(advanced)
        assertEquals(listOf("complete", "next"), calls)
    }

    @Test
    fun installerKilledByExit_neitherMarksDoneNorStartsNext() {
        // Teardown (user exit or agent sessions.stop) killed the installer, then its callback fired.
        val advanced = advancePreInstallChain(
            exiting = { true },
            completeStep = { calls += "complete" },
            startNext = { calls += "next" },
        )

        assertFalse(advanced)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun exitBeginningWhileStepCompletes_doesNotStartNext() {
        var exiting = false
        val advanced = advancePreInstallChain(
            exiting = { exiting },
            completeStep = {
                calls += "complete"
                exiting = true
            },
            startNext = { calls += "next" },
        )

        assertFalse(advanced)
        assertEquals(listOf("complete"), calls)
    }
}
