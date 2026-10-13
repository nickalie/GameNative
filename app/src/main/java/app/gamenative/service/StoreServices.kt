package app.gamenative.service

import android.content.Context
import android.content.Intent
import app.gamenative.data.GameSource
import app.gamenative.data.StoreToggles
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import timber.log.Timber

/**
 * Applies a Settings → Stores switch at runtime: persists it in [StoreToggles], then starts the store's
 * service (when signed in) or stops it through its normal stop path. Stopping never logs out or deletes
 * anything; a service that is still downloading or syncing is left to finish, and the usual idle stop
 * (app backgrounded) ends it later. The services' own `start` refuses while their store is off.
 */
object StoreServices {

    fun setEnabled(context: Context, source: GameSource, enabled: Boolean) {
        StoreToggles.setEnabled(source, enabled)
        Timber.i("[StoreServices] %s turned %s", source, if (enabled) "on" else "off")
        if (enabled) start(context.applicationContext, source) else stop(source)
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

    private fun stop(source: GameSource) {
        when (source) {
            GameSource.STEAM -> if (SteamService.isRunning && !SteamService.hasActiveOperations() && !SteamService.keepAlive) {
                SteamService.stop()
            }
            GameSource.GOG -> if (GOGService.isRunning && !GOGService.hasActiveOperations()) GOGService.stop()
            GameSource.EPIC -> if (EpicService.isRunning && !EpicService.hasActiveOperations()) EpicService.stop()
            GameSource.AMAZON -> if (AmazonService.isRunning && !AmazonService.hasActiveOperations()) AmazonService.stop()
            GameSource.CUSTOM_GAME -> Unit
        }
    }
}
