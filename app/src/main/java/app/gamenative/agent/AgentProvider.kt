package app.gamenative.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.agent.commands.ComponentCommands
import app.gamenative.agent.commands.ContainerCommands
import app.gamenative.agent.commands.CustomFolderDeps
import app.gamenative.agent.commands.ContainerWriteDeps
import app.gamenative.agent.commands.DeviceCommands
import app.gamenative.agent.commands.GameCommands
import app.gamenative.agent.commands.SessionCommands
import app.gamenative.events.SteamEvent

/**
 * ADB back channel for AI agents: `adb shell content call --uri content://<package>.agent
 * --method command --arg <base64url(JSON)>` returns a Bundle with the JSON response under `json`.
 *
 * Only the shell (2000) and root (0) UIDs may call it; every other entry point is rejected. The
 * dispatcher itself answers `NOT_READY` before the app finished starting and `DISABLED` while the
 * "Agent API (ADB)" setting is off.
 */
class AgentProvider : ContentProvider() {

    private val dispatcher: AgentDispatcher by lazy { dispatcherFactory(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceCaller()
        val json = if (method == METHOD_COMMAND) {
            dispatcher.dispatch(arg)
        } else {
            AgentResponse.error(AgentErrorCode.BAD_ARGS, "unsupported method; use '$METHOD_COMMAND'").encode()
        }
        return Bundle().apply { putString(KEY_JSON, json) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = reject()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? = reject()

    override fun getType(uri: Uri): String? {
        enforceCaller()
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = reject()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        reject()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = reject()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = reject()

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = reject()

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? = reject()

    private fun reject(): Nothing {
        enforceCaller()
        throw UnsupportedOperationException("the agent provider only supports call(\"$METHOD_COMMAND\")")
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (!isAllowedUid(uid)) throw SecurityException("uid $uid may not use the agent API")
    }

    companion object {
        const val METHOD_COMMAND = "command"
        const val KEY_JSON = "json"

        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000
        private const val PER_USER_RANGE = 100_000

        /** Shell or root, in any Android user (multi-user UIDs are `userId * 100000 + appId`). */
        fun isAllowedUid(uid: Int): Boolean = uid >= 0 && (uid % PER_USER_RANGE).let { it == SHELL_UID || it == ROOT_UID }

        @VisibleForTesting
        @Volatile
        internal var dispatcherFactory: (Context) -> AgentDispatcher = { createDispatcher(it) }

        /**
         * The single place that wires the dispatcher to app state (preferences, runtime singletons).
         * Tests override the parameters to run without PluviaApp.
         */
        internal fun createDispatcher(
            context: Context,
            isEnabled: () -> Boolean = { PrefManager.agentApiEnabled },
            isReady: () -> Boolean = { AgentRuntime.isReady },
            tracker: AgentSessionTracker = AgentRuntime.tracker,
            postToMain: (Runnable, Long) -> Unit = { task, delayMs -> Handler(Looper.getMainLooper()).postDelayed(task, delayMs) },
            environmentReady: () -> Boolean = { PluviaApp.xEnvironment != null },
        ): AgentDispatcher {
            val dispatcher = AgentDispatcher(
                isEnabled = isEnabled,
                isReady = isReady,
                appVersion = BuildConfig.VERSION_NAME,
                packageName = context.packageName,
            )
            SessionCommands(
                tracker = tracker,
                postToMain = postToMain,
                environmentReady = environmentReady,
                forceClose = { PluviaApp.events.emit(SteamEvent.ForceCloseApp) },
            ).register(dispatcher)
            // App state is resolved per call, so building the dispatcher never touches Hilt or the disk.
            DeviceCommands { DeviceCommands.collect(context) }.register(dispatcher)
            GameCommands(
                storeGames = { GameCommands.storeGamesFrom(context) },
                customGames = { GameCommands.customGamesFrom() },
                containerIds = { GameCommands.containerIdsFrom(context) },
                customFolders = CustomFolderDeps.forApp(context),
            ).register(dispatcher)
            ComponentCommands { ComponentCommands.loadSources(context) }.register(dispatcher)
            ContainerCommands(
                loadContainer = { ContainerCommands.load(context, it) },
                writes = ContainerWriteDeps.forApp(context, tracker),
            ).register(dispatcher)
            return dispatcher
        }
    }
}
