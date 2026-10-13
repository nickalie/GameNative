package app.gamenative.service

import android.content.Context
import android.content.Intent
import app.gamenative.data.GameSource
import app.gamenative.data.StoreToggles
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Applies a Settings → Stores switch at runtime: persists it in [StoreToggles], then brings the store's
 * service in line with it. On: the service starts (when signed in). Off: the service stops through its
 * normal stop path; one that is downloading or syncing (Steam: also running a game, signing in or importing
 * a component) is watched and stopped once that work ends, if the switch is still off. Stopping never logs
 * out or deletes anything.
 *
 * Each store has one worker that applies the switch's current value, so flipping it off and on quickly ends
 * with the service matching the switch: a start waits for a stop in progress. The watch lasts as long as the
 * process; a store service that Android restarts while its store is off stops itself in `onStartCommand`.
 * The services' own `start` refuses while their store is off.
 */
object StoreServices {

    private const val BUSY_POLL_MS = 5_000L
    private const val STOP_TIMEOUT_MS = 30_000L
    private const val STOP_POLL_MS = 200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var appContext: Context? = null

    /** One conflated signal per store: a switch change during a pass queues exactly one more pass. */
    private val signals: Map<GameSource, Channel<Unit>> by lazy {
        GameSource.entries.associateWith { source ->
            Channel<Unit>(Channel.CONFLATED).also { channel ->
                scope.launch {
                    channel.consumeEach {
                        val context = appContext ?: return@consumeEach
                        try {
                            apply(context, source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "[StoreServices] Could not apply the %s switch", source)
                        }
                    }
                }
            }
        }
    }

    fun setEnabled(context: Context, source: GameSource, enabled: Boolean) {
        StoreToggles.setEnabled(source, enabled)
        Timber.i("[StoreServices] %s turned %s", source, if (enabled) "on" else "off")
        appContext = context.applicationContext
        signals.getValue(source).trySend(Unit)
    }

    /**
     * Reads the switch afresh on every step, so a change while waiting is picked up. After a stop is issued
     * the pass waits for the service to go down whatever the switch says, so an "on" that arrives meanwhile
     * still starts it instead of being skipped as "running". A stop that outlasts [STOP_TIMEOUT_MS] is logged
     * and then polled every [BUSY_POLL_MS].
     */
    private suspend fun apply(context: Context, source: GameSource) {
        var stopWaitMs = -1L
        while (true) {
            val running = isRunning(source)
            if (stopWaitMs >= 0 && running) {
                if (stopWaitMs < STOP_TIMEOUT_MS) {
                    delay(STOP_POLL_MS)
                    stopWaitMs += STOP_POLL_MS
                    if (stopWaitMs >= STOP_TIMEOUT_MS) {
                        Timber.w("[StoreServices] The %s service did not stop in time", source)
                    }
                } else {
                    delay(BUSY_POLL_MS)
                }
                continue
            }
            if (StoreToggles.isEnabled(source)) {
                start(context, source)
                return
            }
            if (!running) return
            if (!isBusy(source)) {
                if (!requestStop(context, source)) return
                stopWaitMs = 0L
                continue
            }
            delay(BUSY_POLL_MS)
        }
    }

    private fun isRunning(source: GameSource): Boolean = when (source) {
        GameSource.STEAM -> SteamService.isRunning
        GameSource.GOG -> GOGService.isRunning
        GameSource.EPIC -> EpicService.isRunning
        GameSource.AMAZON -> AmazonService.isRunning
        GameSource.CUSTOM_GAME -> false
    }

    private fun isBusy(source: GameSource): Boolean = when (source) {
        // The same guards MainActivity.onStop checks before it stops Steam.
        GameSource.STEAM -> SteamService.hasActiveOperations() ||
            SteamService.keepAlive ||
            SteamService.isLoginInProgress ||
            SteamService.isImporting
        GameSource.GOG -> GOGService.hasActiveOperations()
        GameSource.EPIC -> EpicService.hasActiveOperations()
        GameSource.AMAZON -> AmazonService.hasActiveOperations()
        GameSource.CUSTOM_GAME -> false
    }

    private fun start(context: Context, source: GameSource) {
        try {
            when (source) {
                GameSource.STEAM -> if (!SteamService.isRunning) {
                    // Connects and, with a stored session, logs in again by itself.
                    context.startForegroundService(Intent(context, SteamService::class.java))
                }
                GameSource.GOG -> if (GOGService.hasStoredCredentials(context)) GOGService.start(context)
                GameSource.EPIC -> if (EpicService.hasStoredCredentials(context)) EpicService.start(context)
                GameSource.AMAZON -> if (AmazonService.hasStoredCredentials(context)) AmazonService.start(context)
                GameSource.CUSTOM_GAME -> Unit
            }
        } catch (e: IllegalStateException) {
            // A foreground-service start from the background (e.g. an Agent API call) is refused; the app's
            // resume path starts the services of the stores that are on.
            Timber.w(e, "[StoreServices] Could not start the %s service now", source)
        }
    }

    /** Asks the service to stop through its normal stop path; false when the store has no service. */
    private fun requestStop(context: Context, source: GameSource): Boolean {
        when (source) {
            // Through onDestroy, which disconnects a connected client and clears one that is still connecting
            // or waiting to retry; SteamService.stop() leaves the latter's service in the foreground.
            GameSource.STEAM -> context.stopService(Intent(context, SteamService::class.java))
            GameSource.GOG -> GOGService.stop()
            GameSource.EPIC -> EpicService.stop()
            GameSource.AMAZON -> AmazonService.stop()
            GameSource.CUSTOM_GAME -> return false
        }
        return true
    }
}
