package app.gamenative.ui.screen.xserver

import timber.log.Timber

/**
 * Runs the continuation of the prerequisite installer chain when an installer process ends.
 *
 * Teardown (a user exit or an agent `sessions.stop`) kills the running installer, and its termination
 * callback still fires. Once [exiting] is true the installer did not finish, so [completeStep] (mark the
 * step done, `wineserver -k`) is skipped and the step is installed again on the next launch, and
 * [startNext] (the next installer or the game) is skipped so nothing starts after teardown. [exiting] is
 * checked again before [startNext] because teardown can begin while [completeStep] runs.
 *
 * Returns true when the chain advanced.
 */
internal fun advancePreInstallChain(
    exiting: () -> Boolean,
    completeStep: () -> Unit,
    startNext: () -> Unit,
): Boolean {
    if (exiting()) {
        Timber.i("Prerequisite installer ended during exit; step not marked done, chain stopped")
        return false
    }
    completeStep()
    if (exiting()) {
        Timber.i("Exit began after a prerequisite step finished; next step not started")
        return false
    }
    startNext()
    return true
}
