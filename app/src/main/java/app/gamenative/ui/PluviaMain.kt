package app.gamenative.ui

import android.content.Context
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.gamenative.BuildConfig
import app.gamenative.MainActivity
import app.gamenative.NetworkMonitor
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.agent.AgentHooks
import app.gamenative.agent.AgentPreLaunch
import app.gamenative.data.GameSource
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.SteamService
import app.gamenative.service.download.SteamDownloadMode
import app.gamenative.service.ea.EaCloudPreference
import app.gamenative.service.ea.EaCloudSavesManager
import app.gamenative.service.ea.EaLaunchSupport
import app.gamenative.service.ea.EaLoginGate
import app.gamenative.service.rockstar.RockstarCloudPreference
import app.gamenative.service.rockstar.RockstarCloudSavesManager
import app.gamenative.service.rockstar.RockstarLaunchSupport
import app.gamenative.service.rockstar.RockstarHelperArchive
import app.gamenative.service.rockstar.RockstarHelperDeployment
import app.gamenative.service.rockstar.RockstarConstants
import app.gamenative.service.rockstar.RockstarLoginGate
import app.gamenative.service.rockstar.RockstarTitleMetadata
import app.gamenative.service.rockstar.RockstarRuntime
import app.gamenative.service.amazon.AmazonService
import app.gamenative.ui.component.AchievementOverlay
import app.gamenative.ui.component.ConnectionStatusBanner
import app.gamenative.ui.component.GameInviteOverlay
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.api.AccountApi
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.dialog.AccountSignInDialog
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.DebugPreRunDialog
import app.gamenative.ui.component.dialog.DebugReportDialog
import app.gamenative.ui.component.dialog.GameFeedbackDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.DebugReportDialogState
import app.gamenative.ui.component.dialog.state.GameFeedbackDialogState
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.components.BootingSplash
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.launch.LaunchReadiness
import app.gamenative.launch.SteamAgreementGate
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.screen.HomeScreen
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.login.UserLoginScreen
import app.gamenative.ui.screen.settings.SettingsScreen
import app.gamenative.ui.screen.support.SnackbarActionContent
import app.gamenative.ui.screen.support.SupportAppliedRun
import app.gamenative.ui.screen.support.SupportReplyEffects
import app.gamenative.ui.screen.support.SupportReportSubmitter
import app.gamenative.ui.screen.support.SupportScreen
import app.gamenative.ui.screen.support.SupportRunFollowUp
import app.gamenative.ui.screen.support.SupportSession
import app.gamenative.ui.screen.support.refusalReasonText
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DebugReportUtils
import app.gamenative.utils.DebugRunParamsHolder
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.GameFeedbackUtils
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.UpdateChecker
import app.gamenative.utils.UpdateInfo
import app.gamenative.utils.UpdateInstaller
import app.gamenative.utils.LaunchDependencies
import app.gamenative.workshop.WorkshopManager
import app.gamenative.workshop.compatibility.SlayTheSpireModTheSpireCompatibility
import com.google.android.play.core.splitcompat.SplitCompat
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.StringUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFSLegacyMigrator
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientObjects.ECloudPendingRemoteOperation
import java.io.File
import java.util.Locale
import java.util.Date
import java.util.EnumSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val PENDING_LAUNCH_TIMEOUT_MS = 10_000L
private const val SNACKBAR_SHOW_TIMEOUT_MS = 15_000L

/** Used to suspend preLaunchApp while the user decides on large workshop updates. */
private var workshopUpdateDeferred: CompletableDeferred<Boolean>? = null

/** Used to suspend preLaunchApp while the user decides on a pending update for a real-Steam launch. */
private var steamUpdateDeferred: CompletableDeferred<Boolean>? = null

/** Valve Windows client tree (build 1788652215, 2026-09-03) + headless steam.exe for Real Steam mode; see extractSteamFiles. */
const val REAL_STEAM_CLIENT_ARCHIVE = "steamhost-20261009.4.tzst"

private fun NavHostController.navigateFromLoginIfNeeded(targetRoute: String, logTag: String) {
    val currentRoute = currentDestination?.route
    if (currentRoute == PluviaScreen.LoginUser.route) {
        Timber.tag(logTag).i("Navigating from LoginUser to $targetRoute")
        // The login screen is opened on top of the library, so replace the whole stack: an
        // offline library left behind it would come back on Back
        navigate(targetRoute) {
            popUpTo(graph.id) {
                inclusive = true
            }
        }
    }
}

/** Start route of the nav graph: always the library, online only when Steam is already logged in. */
internal fun startRoute(isSteamLoggedIn: Boolean): String =
    PluviaScreen.Home.route + "?offline=${!isSteamLoggedIn}"

/** Opens the library as a not-signed-in user and clears the back stack (login screen included). */
private fun NavHostController.navigateToOfflineLibrary() {
    navigate(startRoute(isSteamLoggedIn = false)) {
        popUpTo(graph.id) { inclusive = true }
    }
}

/** Route pattern of the library destination. */
internal val HOME_ROUTE_PATTERN = PluviaScreen.Home.route + "?offline={offline}"

/** `offline` argument of the topmost library entry on the back stack, null when there is none. */
private fun NavHostController.libraryOfflineArg(): Boolean? =
    runCatching { getBackStackEntry(HOME_ROUTE_PATTERN) }.getOrNull()?.arguments?.getBoolean("offline")

/** Replaces the topmost (offline) library entry with the online library. */
private fun NavHostController.switchLibraryOnline() {
    navigate(startRoute(isSteamLoggedIn = true)) {
        popUpTo(HOME_ROUTE_PATTERN) { inclusive = true }
    }
}

/**
 * Whether OnLoggedOut should reset the stack to the offline library. Steam sends LoggedOut twice per
 * sign-out, so skip it when the library is already signed out (keeping a screen opened in between, such
 * as the login form), and never pull the user out of a running game.
 */
internal fun shouldResetToOfflineLibrary(currentRoute: String?, libraryOffline: Boolean?): Boolean =
    currentRoute != PluviaScreen.XServer.route && libraryOffline != true

internal enum class LogonNavigation { LEAVE_LOGIN, SWITCH_LIBRARY_ONLINE, SWITCH_LIBRARY_ONLINE_ON_RETURN, NONE }

/**
 * What a successful Steam logon does to navigation: leave the login screen, or swap the offline library
 * for the online one, right away when it is shown or once it is shown again when another screen covers it.
 */
internal fun logonNavigation(currentRoute: String?, libraryOffline: Boolean?): LogonNavigation = when {
    currentRoute == PluviaScreen.LoginUser.route -> LogonNavigation.LEAVE_LOGIN
    libraryOffline != true -> LogonNavigation.NONE
    currentRoute == HOME_ROUTE_PATTERN -> LogonNavigation.SWITCH_LIBRARY_ONLINE
    else -> LogonNavigation.SWITCH_LIBRARY_ONLINE_ON_RETURN
}

private sealed class GameResolutionResult {
    data class Success(
        val finalAppId: String,
        val gameId: Int,
        val isSteamInstalled: Boolean,
        val isCustomGame: Boolean,
    ) : GameResolutionResult()
    data class NotFound(
        val gameId: Int,
        val originalAppId: String,
    ) : GameResolutionResult()
}

private fun resolveGameAppId(context: Context, appId: String): GameResolutionResult {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
    val isInstalled = when (gameSource) {
        GameSource.STEAM -> {
            // isAppInstalled uses getAppInfoOf internally to derive the game dir name.
            // if the service hasn't started yet (instance==null), getAppInfoOf returns
            // null → empty dir name → marker check fails → false negative.
            // skip the redundant DB query and fall back to container existence.
            if (SteamService.getAppInfoOf(gameId) != null) {
                SteamService.isAppInstalled(gameId)
            } else {
                ContainerUtils.hasContainer(context, appId)
            }
        }

        GameSource.GOG -> {
            GOGService.isGameInstalled(gameId.toString())
        }

        GameSource.EPIC -> {
            EpicService.isGameInstalled(context, gameId)
        }

        GameSource.AMAZON -> {
            AmazonService.isGameInstalledByAppId(context, gameId)
        }

        GameSource.CUSTOM_GAME -> {
            CustomGameScanner.isGameInstalled(gameId)
        }
    }

    if (!isInstalled) {
        return GameResolutionResult.NotFound(
            gameId = gameId,
            originalAppId = appId,
        )
    }

    val isSteamInstalled = gameSource == GameSource.STEAM && isInstalled
    val isCustomGame = gameSource == GameSource.CUSTOM_GAME

    return GameResolutionResult.Success(
        finalAppId = appId,
        gameId = gameId,
        isSteamInstalled = isSteamInstalled,
        isCustomGame = isCustomGame,
    )
}


/** Check if launch should be deferred — Steam needs login, GOG/Epic/Amazon need service startup */
private fun needsDeferLaunch(context: Context, appId: String): Boolean {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    return when (gameSource) {
        GameSource.STEAM -> {
            if (SteamService.isLoggedIn) return false
            // isLoggedIn is network-gated (requires LoggedOnCallback). allow offline launch when
            // saved creds + local container exist — Steam can reconnect in background.
            !(SteamUtils.hasStoredCredentials() && ContainerUtils.hasContainer(context, appId))
        }
        GameSource.GOG -> !GOGService.isRunning
        GameSource.EPIC -> !EpicService.isRunning
        GameSource.AMAZON -> !AmazonService.isRunning
        else -> false
    }
}

/** Show snackbar for a deferred launch based on the game's source. Returns true if shown. */
private fun showDeferredLaunchSnackbar(context: Context, appId: String): Boolean {
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    return when {
        gameSource == GameSource.STEAM && SteamService.isConnected -> {
            SnackbarManager.show(context.getString(R.string.intent_launch_steam_pending))
            true
        }
        gameSource != GameSource.STEAM -> {
            SnackbarManager.show(context.getString(R.string.intent_launch_service_pending))
            true
        }
        else -> false
    }
}

/** Consume pending launch request only if it's a Steam login failure, and show failure snackbar. */
private fun consumePendingSteamLoginError(context: Context) {
    val request = MainActivity.peekPendingLaunchRequest() ?: return
    val gameSource = ContainerUtils.extractGameSourceFromContainerId(request.appId)
    if (gameSource != GameSource.STEAM || SteamService.isLoggedIn) return
    MainActivity.consumePendingLaunchRequest()
    AgentHooks.clearLaunchOptions(request.appId)
    SnackbarManager.show(context.getString(R.string.intent_launch_steam_login_failed))
}

private const val SUPPORT_PROMPT_SIGN_IN = "sign_in"

private fun CoroutineScope.markAppliedReported(context: Context, state: DebugReportDialogState) {
    if (state.phase != DebugReportDialogState.PHASE_COMPOSE && state.phase != DebugReportDialogState.PHASE_ERROR) return
    launch(Dispatchers.IO) { SupportAppliedRun.markReported(context, state.appId) }
}

private fun startDebugRun(
    context: Context,
    viewModel: MainViewModel,
    appId: String,
    isOffline: Boolean,
    setMessageDialogState: (MessageDialogState) -> Unit,
) {
    if (appId.isEmpty()) return
    viewModel.setLaunchedAppId(appId)
    viewModel.setBootToContainer(false)
    viewModel.setTestGraphics(false)
    viewModel.setDiagnostics(false)
    viewModel.setDebugRun(true)
    viewModel.setOffline(isOffline)
    preLaunchApp(
        context = context,
        appId = appId,
        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
        setLoadingProgress = viewModel::setLoadingDialogProgress,
        setLoadingMessage = viewModel::setLoadingDialogMessage,
        setMessageDialogState = setMessageDialogState,
        onSuccess = viewModel::launchApp,
        isOffline = isOffline,
        bootToContainer = false,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluviaMain(
    viewModel: MainViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()

    var msgDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
        mutableStateOf(MessageDialogState(false))
    }
    val setMessageDialogState: (MessageDialogState) -> Unit = { msgDialogState = it }

    var gameFeedbackState by rememberSaveable(stateSaver = GameFeedbackDialogState.Saver) {
        mutableStateOf(GameFeedbackDialogState(false))
    }

    var debugReportState by rememberSaveable(stateSaver = DebugReportDialogState.Saver) {
        mutableStateOf(DebugReportDialogState(false))
    }
    var debugAccountPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var aiDebugOfferAppId by rememberSaveable { mutableStateOf("") }
    var debugPreRunVisible by rememberSaveable { mutableStateOf(false) }
    var debugPreRunAppId by rememberSaveable { mutableStateOf("") }
    var debugPreRunOffline by rememberSaveable { mutableStateOf(false) }

    var hasBack by rememberSaveable { mutableStateOf(navController.previousBackStackEntry?.destination?.route != null) }

    var isConnecting by rememberSaveable { mutableStateOf(false) }
    var shownPendingLaunchSnackbar by rememberSaveable { mutableStateOf(false) }
    // incremented each time a launch is deferred, so the timeout restarts per request
    var pendingLaunchGeneration by rememberSaveable { mutableIntStateOf(0) }

    var gameBackAction by remember { mutableStateOf<() -> Unit?>({}) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    var openContainerConfigForAppId by rememberSaveable { mutableStateOf<String?>(null) }

    // Track if connection banner was dismissed by user
    var connectionBannerDismissed by rememberSaveable { mutableStateOf(false) }

    // suppress CONNECTING banner during first attempt; DISCONNECTED always shows
    var initialConnectDone by rememberSaveable { mutableStateOf(SteamService.isConnected) }

    // Track previous connection state to detect actual changes (not just recomposition)
    val previousConnectionState = remember { mutableStateOf(state.connectionState) }

    // Reset dismissed state only when connection state actually changes
    LaunchedEffect(state.connectionState) {
        if (previousConnectionState.value != state.connectionState) {
            connectionBannerDismissed = false
            previousConnectionState.value = state.connectionState
        }
        // first attempt resolved (connected or failed)
        if (state.connectionState != ConnectionState.CONNECTING) {
            initialConnectDone = true
        }
    }

    // Check for updates on app start
    LaunchedEffect(Unit) {
        if (BuildConfig.MODERN_ANDROID || BuildConfig.XR_BUILD) return@LaunchedEffect
        val checkedUpdateInfo = UpdateChecker.checkForUpdate(context)
        if (checkedUpdateInfo != null) {
            val appVersionCode = BuildConfig.VERSION_CODE
            val serverVersionCode = checkedUpdateInfo.versionCode
            Timber.i("Update check: app versionCode=$appVersionCode, server versionCode=$serverVersionCode")
            if (appVersionCode < serverVersionCode) {
                updateInfo = checkedUpdateInfo
                viewModel.setUpdateInfo(checkedUpdateInfo)
            }
        }
    }

    // shared intent-launch path. resolves isOffline at the call site because intent launches can
    // arrive pre-login (cold-boot via stored creds) and downstream cloud-sync needs a settled answer.
    val launchIntentApp: (resolvedAppId: String, hasTemporaryOverride: Boolean) -> Unit = { resolvedAppId, hasTemporaryOverride ->
        val requestedGameId = runCatching { ContainerUtils.extractGameIdFromContainerId(resolvedAppId) }.getOrNull()
        if (SteamService.keepAlive && requestedGameId != null && ActiveGameRegistry.get()?.appId == requestedGameId) {
            Timber.i("[PluviaMain]: Game $resolvedAppId already running; bringing XServer screen forward")
            AgentHooks.clearLaunchOptions(resolvedAppId)
            if (navController.currentDestination?.route != PluviaScreen.XServer.route) {
                navController.navigate(PluviaScreen.XServer.route)
            }
        } else {
            MainActivity.wasLaunchedViaExternalIntent = true
            val agentLaunch = AgentPreLaunch.start(AgentHooks.onLaunchRequested(resolvedAppId))
            viewModel.setLaunchedAppId(resolvedAppId)
            viewModel.setBootToContainer(false)
            scope.launch(Dispatchers.IO) {
                // ends the agent's launching session if the pre-launch stops before the game launch
                agentLaunch.follow {
                    val gameSource = ContainerUtils.extractGameSourceFromContainerId(resolvedAppId)
                    val isOffline = when {
                        gameSource != GameSource.STEAM -> false
                        SteamService.isLoggedIn -> false
                        !NetworkMonitor.hasInternet.value -> true
                        else -> {
                            viewModel.setLoadingDialogVisible(true)
                            viewModel.setLoadingDialogMessage(context.getString(R.string.connecting_to_steam))
                            viewModel.setLoadingDialogProgress(-1f)
                            !SteamUtils.awaitSteamLogin()
                        }
                    }
                    // sync viewModel — dialog retries + replaceSteamApi read isOffline.value
                    viewModel.setOffline(isOffline)
                    preLaunchApp(
                        context = context,
                        appId = resolvedAppId,
                        useTemporaryOverride = hasTemporaryOverride,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = isOffline,
                    )
                }
            }
        }
    }

    // process pending launch request from cold start (event bus has no replay)
    LaunchedEffect(Unit) {
        MainActivity.consumePendingLaunchRequest()?.let { launchRequest ->
            Timber.i("[PluviaMain]: Processing pending launch request for app ${launchRequest.appId}")
            // defer if service isn't ready yet — will be processed on ServiceReady/OnLogonEnded
            // consume+requeue is safe: both calls are non-suspending, so no other coroutine
            // can interleave between them on the main dispatcher (cooperative scheduling).
            if (needsDeferLaunch(context, launchRequest.appId)) {
                MainActivity.setPendingLaunchRequest(launchRequest)
                pendingLaunchGeneration++
                shownPendingLaunchSnackbar = showDeferredLaunchSnackbar(context, launchRequest.appId)
                return@let
            }
            when (val resolution = resolveGameAppId(context, launchRequest.appId)) {
                is GameResolutionResult.Success -> {
                    if (launchRequest.containerConfig != null) {
                        IntentLaunchManager.applyTemporaryConfigOverride(
                            context, launchRequest.appId, launchRequest.containerConfig,
                        )
                    }
                    launchIntentApp(resolution.finalAppId, launchRequest.containerConfig != null)
                }

                is GameResolutionResult.NotFound -> {
                    val appName = ContainerUtils.resolveGameName(resolution.originalAppId)
                    Timber.w("[PluviaMain]: Game not installed: $appName (${launchRequest.appId})")
                    AgentHooks.clearLaunchOptions(launchRequest.appId)
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.game_not_installed_title),
                        message = context.getString(R.string.game_not_installed_message, appName),
                        dismissBtnText = context.getString(R.string.ok),
                    )
                }
            }
        }
    }

    // timeout stale pending requests — if service never starts, don't hang indefinitely
    // keyed on generation so timeout restarts each time a new request is deferred
    LaunchedEffect(pendingLaunchGeneration) {
        if (pendingLaunchGeneration == 0) return@LaunchedEffect
        delay(PENDING_LAUNCH_TIMEOUT_MS)
        MainActivity.peekPendingLaunchRequest()?.let { request ->
            if (needsDeferLaunch(context, request.appId)) {
                Timber.tag("IntentLaunch").w("Pending launch timed out for ${request.appId}")
                AgentHooks.clearLaunchOptions(request.appId)
                MainActivity.consumePendingLaunchRequest()
                SnackbarManager.show(context.getString(R.string.intent_launch_service_timeout))
            }
        }
    }

    // shared handler for deferred intent launches (Steam login, GOG/Epic/Amazon service startup)
    val processPendingLaunch: (String) -> Unit = { reason ->
        MainActivity.consumePendingLaunchRequest()?.let { launchRequest ->
            Timber.tag("IntentLaunch")
                .i("Processing pending launch for ${launchRequest.appId} ($reason)")
            when (val resolution = resolveGameAppId(context, launchRequest.appId)) {
                is GameResolutionResult.NotFound -> {
                    val appName = ContainerUtils.resolveGameName(resolution.originalAppId)
                    Timber.tag("IntentLaunch").w("Game not installed: $appName (${launchRequest.appId})")
                    AgentHooks.clearLaunchOptions(launchRequest.appId)
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.game_not_installed_title),
                        message = context.getString(R.string.game_not_installed_message, appName),
                        dismissBtnText = context.getString(R.string.ok),
                    )
                }

                is GameResolutionResult.Success -> {
                    if (launchRequest.containerConfig != null) {
                        IntentLaunchManager.applyTemporaryConfigOverride(
                            context, launchRequest.appId, launchRequest.containerConfig,
                        )
                    }
                    val homeRoute = PluviaScreen.Home.route + "?offline={offline}"
                    if (navController.currentDestination?.route != homeRoute) {
                        // Replace the whole stack: the start destination is now an offline library
                        navController.navigate(PluviaScreen.Home.route + "?offline=false") {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                    // finalAppId — track what actually launched (may differ from launchRequest.appId after resolution)
                    launchIntentApp(resolution.finalAppId, launchRequest.containerConfig != null)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                MainViewModel.MainUiEvent.LaunchApp -> {
                    navController.navigate(PluviaScreen.XServer.route)
                }

                is MainViewModel.MainUiEvent.ExternalGameLaunch -> {
                    Timber.i("[PluviaMain]: Received ExternalGameLaunch UI event for app ${event.appId}")

                    // defer if service isn't ready yet
                    if (needsDeferLaunch(context, event.appId)) {
                        // preserve any container config override already applied by handleLaunchIntent
                        MainActivity.setPendingLaunchRequest(
                            IntentLaunchManager.LaunchRequest(
                                appId = event.appId,
                                containerConfig = IntentLaunchManager.getTemporaryOverride(event.appId),
                            )
                        )
                        pendingLaunchGeneration++
                        shownPendingLaunchSnackbar = showDeferredLaunchSnackbar(context, event.appId)
                        return@collect
                    }

                    when (val resolution = resolveGameAppId(context, event.appId)) {
                        is GameResolutionResult.Success -> {
                            Timber.i("[PluviaMain]: Using appId: ${resolution.finalAppId} (original: ${event.appId}, isSteamInstalled: ${resolution.isSteamInstalled}, isCustomGame: ${resolution.isCustomGame})")
                            launchIntentApp(
                                resolution.finalAppId,
                                IntentLaunchManager.hasTemporaryOverride(resolution.finalAppId),
                            )
                        }

                        is GameResolutionResult.NotFound -> {
                            val appName = ContainerUtils.resolveGameName(resolution.originalAppId)
                            Timber.w("[PluviaMain]: Game not installed: $appName (${event.appId})")
                            AgentHooks.clearLaunchOptions(event.appId)
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_FAIL,
                                title = context.getString(R.string.game_not_installed_title),
                                message = context.getString(R.string.game_not_installed_message, appName),
                                dismissBtnText = context.getString(R.string.ok),
                            )
                        }
                    }
                }

                MainViewModel.MainUiEvent.OnBackPressed -> {
                    if (debugReportState.visible) {
                        if (debugReportState.phase != DebugReportDialogState.PHASE_SENDING) {
                            debugReportState = debugReportState.copy(visible = false)
                            SteamService.keepAlive = false
                            scope.markAppliedReported(context, debugReportState)
                        }
                    } else if (SteamService.keepAlive){
                        gameBackAction?.invoke() ?: run { navController.popBackStack() }
                    } else if (hasBack) {
                        // TODO: check if back leads to log out and present confidence modal
                        navController.popBackStack()
                    } else {
                        // TODO: quit app?
                    }
                }

                MainViewModel.MainUiEvent.OnLoggedOut -> {
                    // Clear persisted route so next login starts fresh from Home
                    viewModel.clearPersistedRoute()
                    // Back to the library as a signed-out user; leave a running game alone
                    if (shouldResetToOfflineLibrary(
                            navController.currentDestination?.route,
                            navController.libraryOfflineArg(),
                        )
                    ) {
                        navController.navigateToOfflineLibrary()
                    }
                }

                is MainViewModel.MainUiEvent.OnLogonEnded -> {
                    when (event.result) {
                        LoginResult.Success -> {
                            val pending = MainActivity.peekPendingLaunchRequest()
                            if (pending != null && !needsDeferLaunch(context, pending.appId)) {
                                processPendingLaunch("user is now logged in")
                            } else if (pending == null && PluviaApp.xEnvironment == null) {
                                when (
                                    logonNavigation(
                                        navController.currentDestination?.route,
                                        navController.libraryOfflineArg(),
                                    )
                                ) {
                                    LogonNavigation.LEAVE_LOGIN -> navController.navigateFromLoginIfNeeded(
                                        viewModel.getPersistedRoute() ?: PluviaScreen.Home.route,
                                        "LogonEnded",
                                    )
                                    LogonNavigation.SWITCH_LIBRARY_ONLINE -> navController.switchLibraryOnline()
                                    LogonNavigation.SWITCH_LIBRARY_ONLINE_ON_RETURN ->
                                        viewModel.switchLibraryOnlineOnReturn = true
                                    LogonNavigation.NONE -> Unit
                                }
                            }
                        }

                        LoginResult.Failed -> {
                            Timber.i("Login failed: ${event.result}")
                            consumePendingSteamLoginError(context)
                        }

                        else -> {
                            Timber.i("Received non-result: ${event.result}")
                        }
                    }
                }

                is MainViewModel.MainUiEvent.SteamDisconnected -> {
                    if (event.isTerminal) {
                        shownPendingLaunchSnackbar = false
                        consumePendingSteamLoginError(context)
                    } else if (!shownPendingLaunchSnackbar) {
                        val appId = MainActivity.peekPendingLaunchRequest()?.appId
                        if (appId != null && needsDeferLaunch(context, appId)) {
                            shownPendingLaunchSnackbar = true
                            SnackbarManager.show(context.getString(R.string.intent_launch_steam_pending))
                        }
                    }
                }

                MainViewModel.MainUiEvent.ServiceReady -> {
                    val pending = MainActivity.peekPendingLaunchRequest()
                    if (pending != null && !needsDeferLaunch(context, pending.appId)) {
                        processPendingLaunch("service now ready")
                    }
                }

                is MainViewModel.MainUiEvent.ShowGameFeedbackDialog -> {
                    gameFeedbackState = GameFeedbackDialogState(
                        visible = true,
                        appId = event.appId,
                    )
                }

                is MainViewModel.MainUiEvent.ShowDebugReportDialog -> {
                    val runConversationId = SupportSession.conversationForRun(event.appId)
                    if (runConversationId != null) {
                        SupportRunFollowUp.start(context, event.appId, runConversationId, viewModel.pendingDebugReport(event.appId))
                        SupportSession.pendingConversationId.value = runConversationId
                    } else {
                        debugReportState = DebugReportDialogState(
                            visible = true,
                            appId = event.appId,
                            gameName = withContext(Dispatchers.IO) { ContainerUtils.resolveGameName(event.appId) },
                            deviceName = HardwareUtils.getMachineName(),
                            issueText = withContext(Dispatchers.IO) { SupportAppliedRun.pending(context, event.appId)?.issueText }.orEmpty(),
                            preparing = true,
                        )
                        scope.launch {
                            AccountApi.loadSignedInState()
                            SupportApi.checkAvailability()
                        }
                    }
                }

                is MainViewModel.MainUiEvent.ShowAiDebugOffer -> {
                    aiDebugOfferAppId = event.appId
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.AI_DEBUG_OFFER,
                        title = context.getString(R.string.debug_offer_title),
                        message = context.getString(
                            R.string.debug_offer_message,
                            ContainerUtils.resolveGameName(event.appId),
                        ),
                        confirmBtnText = context.getString(R.string.debug_offer_confirm),
                        dismissBtnText = context.getString(R.string.close),
                    )
                }
            }
        }
    }

    LaunchedEffect(navController) {
        Timber.i("navController changed")

        if (!state.hasLaunched) {
            viewModel.setHasLaunched(true)

            Timber.i("Creating on destination changed listener")

            PluviaApp.onDestinationChangedListener = NavController.OnDestinationChangedListener { _, destination, _ ->
                Timber.i("onDestinationChanged to ${destination.route}")
                // in order not to trigger the screen changed launch effect
                viewModel.setCurrentScreen(destination.route)
            }
            PluviaApp.events.emit(AndroidEvent.StartOrientator)
        } else {
            PluviaApp.onDestinationChangedListener?.let {
                navController.removeOnDestinationChangedListener(it)
            }
        }

        PluviaApp.onDestinationChangedListener?.let {
            navController.addOnDestinationChangedListener(it)
        }
    }

    // TODO merge to VM?
    LaunchedEffect(state.currentScreen) {
        // do the following each time we navigate to a new screen
        if (state.resettedScreen != state.currentScreen) {
            viewModel.setScreen()
            // Log.d("PluviaMain", "Screen changed to $currentScreen, resetting some values")
            // TODO: remove this if statement once XServerScreen orientation change bug is fixed
            if (state.currentScreen != PluviaScreen.XServer) {
                // Hide or show status bar based on if in game or not
                val shouldShowStatusBar = !PrefManager.hideStatusBarWhenNotInGame
                PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(shouldShowStatusBar))

                // reset system ui visibility based on user preference
                // TODO: add option for user to set
                // reset available orientations
                PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.UNSPECIFIED)))
            }
            // find out if back is available
            hasBack = navController.previousBackStackEntry?.destination?.route != null
        }
    }

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Only attempt reconnection if not already connected/connecting and not in offline mode
            val shouldAttemptReconnect = !state.isSteamConnected &&
                !isConnecting &&
                !SteamService.keepAlive

            if (shouldAttemptReconnect) {
                Timber.d("[PluviaMain]: Steam not connected - attempting reconnection")
                isConnecting = true
                viewModel.startConnecting()
                context.startForegroundService(Intent(context, SteamService::class.java))
            }

            // Start GOGService if user has GOG
            if (app.gamenative.service.gog.GOGService.hasStoredCredentials(context) &&
                !app.gamenative.service.gog.GOGService.isRunning
            ) {
                Timber.tag("GOG").d("[PluviaMain]: Starting GOGService for logged-in user")
                app.gamenative.service.gog.GOGService.start(context)
            } else {
                Timber.tag("GOG").d("GOG SERVICE Not going to start: ${app.gamenative.service.gog.GOGService.isRunning}")
            }

            // Start EpicService if user has Epic credentials
            if (app.gamenative.service.epic.EpicService.hasStoredCredentials(context) &&
                !app.gamenative.service.epic.EpicService.isRunning
            ) {
                Timber.d("[PluviaMain]: Starting EpicService for logged-in user")
                app.gamenative.service.epic.EpicService.start(context)
            }

            // Start AmazonService if user has Amazon credentials
            if (AmazonService.hasStoredCredentials(context) &&
                !AmazonService.isRunning
            ) {
                Timber.d("[PluviaMain]: Starting AmazonService for logged-in user")
                AmazonService.start(context)
            }

            // A login screen restored after activity recreation misses OnLogonEnded if Steam logged in
            // meanwhile. Steam only: other platforms' credentials must not close the Steam login form.
            if (SteamService.isLoggedIn && !SteamService.keepAlive) {
                navController.navigateFromLoginIfNeeded(
                    viewModel.getPersistedRoute() ?: startRoute(isSteamLoggedIn = true),
                    "ResumeSession",
                )
            }
        }
    }

    // Listen for connection state changes - reset local isConnecting flag
    LaunchedEffect(state.isSteamConnected) {
        if (state.isSteamConnected) {
            isConnecting = false
        }
    }

    // Listen for save container config prompt
    var pendingSaveAppId by rememberSaveable { mutableStateOf<String?>(null) }
    val onPromptSaveConfig: (AndroidEvent.PromptSaveContainerConfig) -> Unit = { event ->
        pendingSaveAppId = event.appId
        msgDialogState = MessageDialogState(
            visible = true,
            type = DialogType.SAVE_CONTAINER_CONFIG,
            title = context.getString(R.string.save_container_settings_title),
            message = context.getString(R.string.save_container_settings_message),
            confirmBtnText = context.getString(R.string.save),
            dismissBtnText = context.getString(R.string.discard),
        )
    }

    // Listen for game feedback request
    val onShowGameFeedback: (AndroidEvent.ShowGameFeedback) -> Unit = { event ->
        gameFeedbackState = GameFeedbackDialogState(
            visible = true,
            appId = event.appId,
        )
    }

    LaunchedEffect(Unit) {
        PluviaApp.events.on<AndroidEvent.PromptSaveContainerConfig, Unit>(onPromptSaveConfig)
        PluviaApp.events.on<AndroidEvent.ShowGameFeedback, Unit>(onShowGameFeedback)
    }

    DisposableEffect(Unit) {
        onDispose {
            PluviaApp.events.off<AndroidEvent.PromptSaveContainerConfig, Unit>(onPromptSaveConfig)
            PluviaApp.events.off<AndroidEvent.ShowGameFeedback, Unit>(onShowGameFeedback)
        }
    }

    // an agent launch paused at a relaunching dialog ends once that dialog is gone without its confirm
    // (dismissed or replaced); reads the live state, so a newer dialog is never mistaken for the old one
    LaunchedEffect(msgDialogState) {
        if (!relaunchesPreLaunch(msgDialogState)) AgentPreLaunch.abortPaused()
    }

    val onDismissRequest: (() -> Unit)?
    val onDismissClick: (() -> Unit)?
    val onConfirmClick: (() -> Unit)?
    var onActionClick: (() -> Unit)? = null
    when (msgDialogState.type) {
        DialogType.SYNC_CONFLICT -> {
            onConfirmClick = {
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        preferredSave = SaveLocation.Remote,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                    )
                }
                msgDialogState = MessageDialogState(false)
            }
            onDismissClick = {
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        preferredSave = SaveLocation.Local,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                    )
                }
                msgDialogState = MessageDialogState(false)
            }
            onDismissRequest = {
                msgDialogState = MessageDialogState(false)
            }
        }

        DialogType.EA_SYNC_CONFLICT -> {
            onConfirmClick = {
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        eaPreferredSave = SaveLocation.Remote,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = viewModel.isOffline.value,
                        bootToContainer = state.bootToContainer,
                    )
                }
                msgDialogState = MessageDialogState(false)
            }
            onDismissClick = {
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        eaPreferredSave = SaveLocation.Local,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = viewModel.isOffline.value,
                        bootToContainer = state.bootToContainer,
                    )
                }
                msgDialogState = MessageDialogState(false)
            }
            onDismissRequest = {
                msgDialogState = MessageDialogState(false)
            }
        }

        DialogType.ROCKSTAR_SYNC_CONFLICT -> {
            onConfirmClick = {
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        rockstarPreferredSave = SaveLocation.Remote,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = viewModel.isOffline.value,
                        bootToContainer = state.bootToContainer,
                    )
                }
                msgDialogState = MessageDialogState(false)
            }
            onDismissClick = {
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        rockstarPreferredSave = SaveLocation.Local,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = viewModel.isOffline.value,
                        bootToContainer = state.bootToContainer,
                    )
                }
                msgDialogState = MessageDialogState(false)
            }
            onDismissRequest = {
                msgDialogState = MessageDialogState(false)
            }
        }

        DialogType.SYNC_FAIL -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }


        DialogType.EXECUTABLE_NOT_FOUND -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onActionClick = {
                setMessageDialogState(MessageDialogState(false))
                openContainerConfigForAppId = state.launchedAppId
            }
        }

        DialogType.SYNC_IN_PROGRESS -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        skipCloudSync = true,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = viewModel.isOffline.value,
                    )
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.PENDING_UPLOAD_IN_PROGRESS -> {
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
        }

        DialogType.PENDING_UPLOAD -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        ignorePendingOperations = true,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = viewModel.isOffline.value,
                        bootToContainer = state.bootToContainer,
                    )
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.APP_SESSION_ACTIVE -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        ignorePendingOperations = true,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                    )
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.ACCOUNT_SESSION_ACTIVE -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val agentLaunch = AgentPreLaunch.resume()
                viewModel.viewModelScope.launch {
                    agentLaunch.follow {
                        // Kick only the game on the other device and wait briefly for confirmation
                        SteamService.kickPlayingSession(onlyGame = true)
                        preLaunchApp(
                            context = context,
                            appId = state.launchedAppId,
                            setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                            setLoadingProgress = viewModel::setLoadingDialogProgress,
                            setLoadingMessage = viewModel::setLoadingDialogMessage,
                            setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                            onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                            isOffline = viewModel.isOffline.value,
                        )
                    }
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.APP_SESSION_SUSPENDED -> {
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
        }

        DialogType.PENDING_OPERATION_NONE -> {
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = null
        }

        DialogType.MULTIPLE_PENDING_OPERATIONS -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val agentLaunch = AgentPreLaunch.resume()
                agentLaunch.follow {
                    preLaunchApp(
                        context = context,
                        appId = state.launchedAppId,
                        ignorePendingOperations = true,
                        setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                        setLoadingProgress = viewModel::setLoadingDialogProgress,
                        setLoadingMessage = viewModel::setLoadingDialogMessage,
                        setMessageDialogState = agentLaunch.dialogs(setMessageDialogState, ::relaunchesPreLaunch),
                        onSuccess = agentLaunch.onSuccess(viewModel::launchApp),
                        isOffline = viewModel.isOffline.value,
                        bootToContainer = state.bootToContainer,
                    )
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.CRASH -> {
            onDismissClick = null
            onDismissRequest = {
                viewModel.setHasCrashedLastStart(false)
                setMessageDialogState(MessageDialogState(false))
            }
            onConfirmClick = {
                viewModel.setHasCrashedLastStart(false)
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.SAVE_CONTAINER_CONFIG -> {
            onConfirmClick = {
                // Save the container config permanently
                pendingSaveAppId?.let { appId ->
                    IntentLaunchManager.getEffectiveContainerConfig(context, appId)?.let { config ->
                        ContainerUtils.applyToContainer(context, appId, config)
                        Timber.i("[PluviaMain]: Saved container configuration for app $appId")
                    }
                    // Clear the temporary override after saving
                    IntentLaunchManager.clearTemporaryOverride(appId)
                }
                pendingSaveAppId = null
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissClick = {
                // Discard the temporary config and restore original
                pendingSaveAppId?.let { appId ->
                    IntentLaunchManager.restoreOriginalConfiguration(context, appId)
                    IntentLaunchManager.clearTemporaryOverride(appId)
                    Timber.i("[PluviaMain]: Discarded temporary config and restored original for app $appId")
                }
                pendingSaveAppId = null
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                // Treat closing dialog as discard
                pendingSaveAppId?.let { appId ->
                    IntentLaunchManager.restoreOriginalConfiguration(context, appId)
                    IntentLaunchManager.clearTemporaryOverride(appId)
                }
                pendingSaveAppId = null
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.APP_UPDATE -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val updateInfo = viewModel.updateInfo.value
                if (updateInfo != null) {
                    scope.launch {
                        viewModel.setLoadingDialogVisible(true)
                        viewModel.setLoadingDialogMessage("Downloading update...")
                        viewModel.setLoadingDialogProgress(0f)

                        val success = UpdateInstaller.downloadAndInstall(
                            context = context,
                            downloadUrl = updateInfo.downloadUrl,
                            versionName = updateInfo.versionName,
                            onProgress = { progress ->
                                viewModel.setLoadingDialogProgress(progress)
                            },
                        )

                        viewModel.setLoadingDialogVisible(false)
                        if (!success) {
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_FAIL,
                                title = context.getString(R.string.main_update_failed_title),
                                message = context.getString(R.string.main_update_failed_message),
                                dismissBtnText = context.getString(R.string.ok),
                            )
                        }
                    }
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.AI_DEBUG_OFFER -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                if (aiDebugOfferAppId.isNotEmpty()) {
                    SupportSession.clearRun(aiDebugOfferAppId)
                    debugPreRunAppId = aiDebugOfferAppId
                    debugPreRunOffline = viewModel.isOffline.value
                    debugPreRunVisible = true
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.WORKSHOP_UPDATE_PROMPT -> {
            onConfirmClick = {
                workshopUpdateDeferred?.complete(true)
            }
            onDismissClick = {
                workshopUpdateDeferred?.complete(false)
            }
            onDismissRequest = {
                workshopUpdateDeferred?.complete(false)
            }
        }

        DialogType.STEAM_UPDATE_PROMPT -> {
            onConfirmClick = {
                steamUpdateDeferred?.complete(true)
            }
            onDismissClick = {
                steamUpdateDeferred?.complete(false)
            }
            onDismissRequest = {
                steamUpdateDeferred?.complete(false)
            }
        }

        else -> {
            onDismissRequest = null
            onDismissClick = null
            onConfirmClick = null
        }
    }

    val snackbarController = LocalSnackbarHostController.current
    var exitSnackbarVisible by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarController) {
        SnackbarManager.messages.collect { message ->
            if (
                withTimeoutOrNull(SNACKBAR_SHOW_TIMEOUT_MS) {
                    snackbarController.hostState.showSnackbar(message)
                } == null
            ) {
                Timber.w("[Snackbar]: Display timed out before dismissal")
            }
            // snackbar dismissed (timeout or new message) — reset exit flag
            exitSnackbarVisible = false
        }
    }

    SupportReplyEffects(
        gameRunning = state.currentScreen == PluviaScreen.XServer,
        canOpenSupport = state.currentScreen == PluviaScreen.Home ||
            state.currentScreen == PluviaScreen.Settings ||
            state.currentScreen == PluviaScreen.Chat,
        hostState = snackbarController.hostState,
        onOpenConversation = { conversationId ->
            SupportSession.pendingConversationId.value = conversationId
            navController.navigate(PluviaScreen.Support.route) { launchSingleTop = true }
        },
    )

    BackHandler(enabled = state.loadingDialogVisible && !SteamService.keepAlive) {
        // TODO: Make prelaunch/loading operations cancellable so Back can exit safely.
    }

    PluviaTheme(
        isDark = when (state.appTheme) {
            AppTheme.AUTO -> isSystemInDarkTheme()
            AppTheme.DAY -> false
            AppTheme.NIGHT -> true
            AppTheme.AMOLED -> true
        },
        isAmoled = (state.appTheme == AppTheme.AMOLED),
        style = state.paletteStyle,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LoadingDialog(
                visible = state.loadingDialogVisible,
                progress = state.loadingDialogProgress,
                message = state.loadingDialogMessage,
            )

            MessageDialog(
                visible = msgDialogState.visible,
                onDismissRequest = onDismissRequest,
                onConfirmClick = onConfirmClick,
                confirmBtnText = msgDialogState.confirmBtnText,
                onDismissClick = onDismissClick,
                dismissBtnText = msgDialogState.dismissBtnText,
                onActionClick = onActionClick,
                actionBtnText = msgDialogState.actionBtnText,
                icon = msgDialogState.type.icon,
                title = msgDialogState.title,
                message = msgDialogState.message,
            )

            SteamAgreementGate.Prompt()

            val scope = rememberCoroutineScope()
            var containerConfigForDialog by remember(openContainerConfigForAppId) { mutableStateOf<ContainerData?>(null) }
            LaunchedEffect(openContainerConfigForAppId) {
                val appId = openContainerConfigForAppId
                if (appId == null) {
                    containerConfigForDialog = null
                    return@LaunchedEffect
                }
                containerConfigForDialog = withContext(Dispatchers.IO) {
                    val container = ContainerUtils.getOrCreateContainer(context, appId)
                    ContainerUtils.toContainerData(container)
                }
            }
            openContainerConfigForAppId?.let { appId ->
                containerConfigForDialog?.let { config ->
                    ContainerConfigDialog(
                        visible = true,
                        title = context.getString(R.string.container_config_title),
                        initialConfig = config,
                        onDismissRequest = { openContainerConfigForAppId = null },
                        onSave = { newConfig ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ContainerUtils.applyToContainer(context, appId, newConfig)
                                }
                                openContainerConfigForAppId = null
                            }
                        },
                    )
                }
            }

            GameFeedbackDialog(
                state = gameFeedbackState,
                onStateChange = { gameFeedbackState = it },
                onSubmit = { feedbackState ->
                    Timber.d(
                        "GameFeedback: onSubmit called with rating=${feedbackState.rating}, tags=${feedbackState.selectedTags}, text=${
                            feedbackState.feedbackText.take(
                                20,
                            )
                        }",
                    )
                    try {
                        // Get the container for the app
                        val appId = feedbackState.appId
                        Timber.d("GameFeedback: Got appId=$appId")

                        // Submit feedback via worker API
                        Timber.d("GameFeedback: Starting coroutine for submission")
                        viewModel.viewModelScope.launch {
                            Timber.d("GameFeedback: Inside coroutine scope")
                            try {
                                Timber.d("GameFeedback: Calling submitGameFeedback with rating=${feedbackState.rating}")
                                val result = GameFeedbackUtils.submitGameFeedback(
                                    context = context,
                                    appId = appId,
                                    rating = feedbackState.rating,
                                    tags = feedbackState.selectedTags.toList(),
                                    notes = feedbackState.feedbackText.takeIf { it.isNotBlank() },
                                )

                                Timber.d("GameFeedback: Submission returned $result")
                                if (result) {
                                    Timber.d("GameFeedback: Showing success snackbar")
                                    SnackbarManager.show("Thank you for your feedback!")
                                } else {
                                    Timber.d("GameFeedback: Showing failure snackbar")
                                    SnackbarManager.show("Failed to submit feedback")
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "GameFeedback: Error submitting game feedback")
                                SnackbarManager.show("Error submitting feedback")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "GameFeedback: Error preparing game feedback")
                        SnackbarManager.show("Failed to submit feedback")
                    } finally {
                        // Close the dialog regardless of success
                        Timber.d("GameFeedback: Closing dialog")
                        gameFeedbackState = GameFeedbackDialogState(visible = false)
                        viewModel.onGameFeedbackResolved(context, feedbackState.rating, feedbackState.selectedTags)
                    }
                },
                onDismiss = {
                    gameFeedbackState = GameFeedbackDialogState(visible = false)
                    viewModel.onGameFeedbackResolved(context, null)
                },
            )

            val shareDebugLog: () -> Unit = {
                val reportDir = File(debugReportState.reportDir)
                val files = listOf(
                    DebugReportUtils.logFile(reportDir),
                    DebugReportUtils.perfFile(reportDir),
                    DebugReportUtils.logcatFile(reportDir),
                    DebugReportUtils.cpuProfileFile(reportDir),
                )
                    .filter { it.exists() }
                if (files.isNotEmpty()) {
                    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
                    val intent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = if (files.single().name.endsWith(".gz")) "application/gzip" else "application/json"
                            putExtra(Intent.EXTRA_STREAM, uris.single())
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        }
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.debug_report_share_log_title)),
                    )
                }
            }

            val submitDebugReport: () -> Unit = submit@{
                val current = debugReportState
                if (current.reportDir.isEmpty()) return@submit
                if (SupportApi.available.value == false) {
                    SnackbarManager.show(context.getString(R.string.support_problem_unavailable))
                    return@submit
                }
                if (!PrefManager.gameNativeSignedIn.value) {
                    debugAccountPrompt = SUPPORT_PROMPT_SIGN_IN
                    return@submit
                }
                debugReportState = current.copy(visible = true, phase = DebugReportDialogState.PHASE_SENDING)
                scope.launch {
                    val outcome = SupportReportSubmitter.submit(context, current)
                    val composeAgain = { debugReportState = debugReportState.copy(visible = true, phase = DebugReportDialogState.PHASE_COMPOSE) }
                    when (outcome) {
                        is SupportReportSubmitter.Outcome.Sent -> {
                            withContext(Dispatchers.IO) {
                                val dir = File(current.reportDir)
                                val header = DebugReportUtils.readHeader(dir)
                                DebugReportUtils.deleteReport(dir)
                                SupportAppliedRun.recordReportedRun(context, current.appId, header)
                            }
                            debugReportState = debugReportState.copy(visible = false)
                            SteamService.keepAlive = false
                            SupportSession.pendingConversationId.value = outcome.conversationId
                            navController.navigate(PluviaScreen.Support.route) { launchSingleTop = true }
                        }
                        SupportReportSubmitter.Outcome.Unavailable -> {
                            composeAgain()
                            SnackbarManager.show(context.getString(R.string.support_problem_unavailable))
                        }
                        SupportReportSubmitter.Outcome.SignedOut -> {
                            composeAgain()
                            debugAccountPrompt = SUPPORT_PROMPT_SIGN_IN
                        }
                        is SupportReportSubmitter.Outcome.Forbidden -> {
                            composeAgain()
                            SnackbarManager.show(refusalReasonText(context, outcome.reason))
                        }
                        SupportReportSubmitter.Outcome.LimitReached -> {
                            composeAgain()
                            SnackbarManager.show(SupportReportSubmitter.limitReachedText(context))
                        }
                        SupportReportSubmitter.Outcome.RateLimited -> {
                            debugReportState = debugReportState.copy(phase = DebugReportDialogState.PHASE_ERROR)
                            SnackbarManager.show(context.getString(R.string.support_problem_rate_limited))
                        }
                        is SupportReportSubmitter.Outcome.Failed -> {
                            debugReportState = debugReportState.copy(phase = DebugReportDialogState.PHASE_ERROR)
                        }
                    }
                }
            }

            DebugPreRunDialog(
                visible = debugPreRunVisible,
                runParams = DebugRunParamsHolder.get(debugPreRunAppId),
                onStart = {
                    debugPreRunVisible = false
                    startDebugRun(context, viewModel, debugPreRunAppId, debugPreRunOffline, setMessageDialogState)
                },
                onDismiss = {
                    debugPreRunVisible = false
                    DebugRunParamsHolder.clear(debugPreRunAppId)
                },
            )

            LaunchedEffect(debugReportState.preparing, debugReportState.appId) {
                if (!debugReportState.preparing) return@LaunchedEffect
                val appId = debugReportState.appId
                val pending = viewModel.pendingDebugReport(appId)
                val dir = if (pending != null) {
                    pending.await()
                } else {
                    withContext(Dispatchers.IO) { DebugReportUtils.newestReport(context, appId) }
                }
                val header = dir?.let { withContext(Dispatchers.IO) { DebugReportUtils.readHeader(it) } }
                if (debugReportState.appId != appId) return@LaunchedEffect
                debugReportState = if (dir == null) {
                    debugReportState.copy(preparing = false, phase = DebugReportDialogState.PHASE_NO_LOG)
                } else {
                    debugReportState.copy(
                        preparing = false,
                        reportDir = dir.absolutePath,
                        gameName = header?.optString("gameName").takeUnless { it.isNullOrEmpty() } ?: debugReportState.gameName,
                        deviceName = header?.optString("deviceName").takeUnless { it.isNullOrEmpty() } ?: debugReportState.deviceName,
                    )
                }
            }

            LaunchedEffect(debugReportState.visible) {
                if (debugReportState.visible) {
                    SteamService.keepAlive = true
                }
            }

            DebugReportDialog(
                state = debugReportState,
                appChatEnabled = SupportApi.available.value != false,
                sendProgress = SupportReportSubmitter.progress.value,
                onStateChange = { debugReportState = it },
                onSend = submitDebugReport,
                onShare = shareDebugLog,
                onDismiss = {
                    debugReportState = debugReportState.copy(visible = false)
                    scope.markAppliedReported(context, debugReportState)
                    SteamService.keepAlive = false
                },
            )

            AccountSignInDialog(
                visible = debugAccountPrompt == SUPPORT_PROMPT_SIGN_IN,
                onSignedIn = {
                    debugAccountPrompt = null
                    submitDebugReport()
                },
                onDismiss = { debugAccountPrompt = null },
            )

            Box(modifier = Modifier.zIndex(10f)) {
                BootingSplash(
                    visible = state.showBootingSplash,
                    text = state.bootingSplashText,
                    heroImageUrl = state.bootingSplashHeroImageUrl,
                    bootAd = state.bootAd,
                    onAbort = { viewModel.abortBoot() },
                    onDismissAd = { optOut -> viewModel.dismissBootAd(optOut) },
                )
            }

            // Connection status banner (overlay) - dismissible so users can access navigation
            if (state.currentScreen != PluviaScreen.LoginUser && !connectionBannerDismissed && initialConnectDone && !state.isSteamConnected &&
                SteamUtils.hasStoredCredentials()) {
                Box(modifier = Modifier.zIndex(5f)) {
                    ConnectionStatusBanner(
                        connectionState = state.connectionState,
                        connectionMessage = state.connectionMessage,
                        timeoutSeconds = state.connectionTimeoutSeconds,
                        onContinueOffline = {
                            viewModel.continueOffline()
                        },
                        onRetry = {
                            viewModel.retryConnection()
                            context.startForegroundService(Intent(context, SteamService::class.java))
                        },
                        onDismiss = {
                            connectionBannerDismissed = true
                        },
                    )
                }
            }

            // Always start in the library; the login screen is only opened from the in-app
            // "Sign in" entry (onGoOnline). Saved Steam credentials still auto-reconnect.
            val startDestination = rememberSaveable { startRoute(SteamService.isLoggedIn) }

            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                /** Login **/
                composable(route = PluviaScreen.LoginUser.route) {
                    UserLoginScreen(
                        connectionState = state.connectionState,
                        onRetryConnection = viewModel::retryConnection,
                        onContinueOffline = { navController.navigateToOfflineLibrary() },
                        onPlatformSignedIn = { navController.navigateToOfflineLibrary() },
                    )
                }
                /** Library, Downloads **/
                composable(
                    route = PluviaScreen.Home.route + "?offline={offline}",
                    deepLinks = listOf(navDeepLink { uriPattern = "pluvia://home" }),
                    arguments = listOf(
                        navArgument("offline") {
                            type = NavType.BoolType
                            defaultValue = false // default when the query param isn’t present
                        },
                    ),
                ) { backStackEntry ->
                    val isOffline = backStackEntry.arguments?.getBoolean("offline") ?: false

                    // Steam logged in while another screen covered this offline library
                    LaunchedEffect(Unit) {
                        if (viewModel.switchLibraryOnlineOnReturn) {
                            viewModel.switchLibraryOnlineOnReturn = false
                            if (isOffline && SteamService.isLoggedIn) navController.switchLibraryOnline()
                        }
                    }

                    // Show update/crash dialogs when Home is first displayed
                    // Skip when offline with Steam credentials (avoid flash when Steam reconnects)
                    LaunchedEffect(Unit) {
                        val shouldShowDialogs = !isOffline || !SteamUtils.hasStoredCredentials()

                        if (shouldShowDialogs && !state.annoyingDialogShown && PluviaApp.xEnvironment == null && !SteamService.keepAlive && !MainActivity.wasLaunchedViaExternalIntent) {
                            val currentUpdateInfo = updateInfo
                            if (currentUpdateInfo != null) {
                                viewModel.setAnnoyingDialogShown(true)
                                msgDialogState = MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_UPDATE,
                                    title = context.getString(R.string.main_update_available_title),
                                    message = context.getString(
                                        R.string.main_update_available_message,
                                        currentUpdateInfo.versionName,
                                        currentUpdateInfo.releaseNotes?.let { "\n\n$it" } ?: "",
                                    ),
                                    confirmBtnText = context.getString(R.string.main_update_button),
                                    dismissBtnText = context.getString(R.string.main_later_button),
                                )
                            } else if (state.hasCrashedLastStart) {
                                viewModel.setAnnoyingDialogShown(true)
                                msgDialogState = MessageDialogState(
                                    visible = true,
                                    type = DialogType.CRASH,
                                    title = context.getString(R.string.main_recent_crash_title),
                                    message = context.getString(R.string.main_recent_crash_message),
                                    confirmBtnText = context.getString(R.string.ok),
                                )
                            }
                        }
                    }

                    HomeScreen(
                        onClickPlay = { appId, asContainer ->
                            viewModel.setLaunchedAppId(appId)
                            viewModel.setBootToContainer(asContainer)
                            viewModel.setTestGraphics(false)
                            viewModel.setDiagnostics(false)
                            viewModel.setDebugRun(false)
                            viewModel.setOffline(isOffline)
                            preLaunchApp(
                                context = context,
                                appId = appId,
                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                setMessageDialogState = { msgDialogState = it },
                                onSuccess = viewModel::launchApp,
                                isOffline = isOffline,
                                bootToContainer = asContainer,
                            )
                        },
                        onTestGraphics = { appId ->
                            viewModel.setLaunchedAppId(appId)
                            viewModel.setBootToContainer(true)
                            viewModel.setTestGraphics(true)
                            viewModel.setDiagnostics(false)
                            viewModel.setDebugRun(false)
                            viewModel.setOffline(isOffline)
                            preLaunchApp(
                                context = context,
                                appId = appId,
                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                setMessageDialogState = { msgDialogState = it },
                                onSuccess = viewModel::launchApp,
                                isOffline = isOffline,
                                bootToContainer = true,
                            )
                        },
                        onPlayWithDiagnostics = { appId ->
                            viewModel.setLaunchedAppId(appId)
                            viewModel.setBootToContainer(false)
                            viewModel.setTestGraphics(false)
                            viewModel.setDiagnostics(true)
                            viewModel.setDebugRun(false)
                            viewModel.setOffline(isOffline)
                            preLaunchApp(
                                context = context,
                                appId = appId,
                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                setMessageDialogState = { msgDialogState = it },
                                onSuccess = viewModel::launchApp,
                                isOffline = isOffline,
                                bootToContainer = false,
                            )
                        },
                        onAiDebugRun = { appId ->
                            SupportSession.clearRun(appId)
                            debugPreRunAppId = appId
                            debugPreRunOffline = isOffline
                            debugPreRunVisible = true
                        },
                        onClickExit = {
                            if (!PrefManager.warnBeforeExit) {
                                PluviaApp.events.emit(AndroidEvent.EndProcess)
                            } else if (exitSnackbarVisible) {
                                PluviaApp.events.emit(AndroidEvent.EndProcess)
                            } else {
                                exitSnackbarVisible = true
                                SnackbarManager.show(context.getString(R.string.back_press_exit_warning))
                            }
                        },
                        onChat = {
                            navController.navigate(PluviaScreen.Chat.route(it))
                        },
                        onNavigateRoute = {
                            navController.navigate(it)
                        },
                        onLogout = {
                            SteamService.logOut()
                        },
                        onGoOnline = {
                            navController.navigate(
                                if (!SteamService.isLoggedIn) PluviaScreen.LoginUser.route
                                else PluviaScreen.Home.route
                            )
                        },
                        isOffline = isOffline,
                        isSteamConnected = state.isSteamConnected,
                    )
                }

                /** Full Screen Chat **/
                // Chat feature temporarily disabled - screen component removed
                /* composable(
                    route = "chat/{id}",
                    arguments = listOf(
                        navArgument(PluviaScreen.Chat.ARG_ID) {
                            type = NavType.LongType
                        },
                    ),
                ) {
                    val id = it.arguments?.getLong(PluviaScreen.Chat.ARG_ID) ?: throw RuntimeException("Unable to get ID to chat")
                    ChatScreen(
                        friendId = id,
                        onBack = {
                            CoroutineScope(Dispatchers.Main).launch {
                                navController.popBackStack()
                            }
                        },
                    )
                } */

                /** Game Screen **/
                composable(route = PluviaScreen.XServer.route) {
                    val xServerIsOffline by viewModel.isOffline.collectAsStateWithLifecycle()
                    val launchedAppId = state.launchedAppId
                    val hasContainer = remember(launchedAppId) {
                        launchedAppId.isNotEmpty() && ContainerUtils.hasContainer(context, launchedAppId)
                    }
                    if (!hasContainer) {
                        LaunchedEffect(launchedAppId) {
                            Timber.w("XServer route entered without a container for '$launchedAppId', returning home")
                            navController.navigate(PluviaScreen.Home.route + "?offline=$xServerIsOffline") {
                                popUpTo(PluviaScreen.XServer.route) { inclusive = true }
                            }
                        }
                        return@composable
                    }
                    XServerScreen(
                        appId = state.launchedAppId,
                        bootToContainer = state.bootToContainer,
                        testGraphics = state.testGraphics,
                        diagnostics = state.diagnostics,
                        debugRun = state.debugRun,
                        isOffline = xServerIsOffline,
                        registerBackAction = { cb ->
                            Timber.d("registerBackAction called: $cb")
                            gameBackAction = cb
                        },
                        navigateBack = {
                            CoroutineScope(Dispatchers.Main).launch {
                                val currentRoute = navController.currentBackStackEntry
                                    ?.destination
                                    ?.route

                                if (currentRoute == PluviaScreen.XServer.route) {
                                    if (MainActivity.wasLaunchedViaExternalIntent) {
                                        Timber.d("[IntentLaunch]: Finishing activity to return to external launcher")
                                        MainActivity.wasLaunchedViaExternalIntent = false
                                        (context as? android.app.Activity)?.finish()
                                    } else {
                                        navController.popBackStack()
                                    }
                                }
                            }
                        },
                        onWindowMapped = { context, window ->
                            viewModel.onWindowMapped(context, window, state.launchedAppId)
                        },
                        onExit = { onComplete ->
                            viewModel.exitSteamApp(context, state.launchedAppId, onComplete)
                        },
                        onGameLaunchError = { error ->
                            viewModel.onGameLaunchError(error)
                        },
                    )
                }

                /** Settings **/
                composable(route = PluviaScreen.Settings.route) {
                    SettingsScreen(
                        appTheme = state.appTheme,
                        paletteStyle = state.paletteStyle,
                        onAppTheme = viewModel::setTheme,
                        onPaletteStyle = viewModel::setPalette,
                        onBack = { navController.navigateUp() },
                    )
                }

                composable(route = PluviaScreen.Support.route) {
                    SupportScreen(
                        onBack = { navController.navigateUp() },
                        onStartDebugRun = { appId ->
                            startDebugRun(context, viewModel, appId, viewModel.isOffline.value, setMessageDialogState)
                        },
                    )
                }
            }

            if (snackbarController.rootOwnsHost) {
                SnackbarHost(
                    hostState = snackbarController.hostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(bottom = 16.dp),
                ) { data ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 4.dp,
                        ) {
                            val actionLabel = data.visuals.actionLabel
                            if (actionLabel == null) {
                                Text(
                                    text = data.visuals.message,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                SnackbarActionContent(
                                    message = data.visuals.message,
                                    actionLabel = actionLabel,
                                    onAction = { data.performAction() },
                                )
                            }
                        }
                    }
                }
            }

            AchievementOverlay()
            GameInviteOverlay()
        }
    }
}

// dialogs preLaunchApp stops at whose confirm handler (in PluviaMain) starts preLaunchApp again
private val RELAUNCHING_DIALOGS = setOf(
    DialogType.SYNC_CONFLICT,
    DialogType.EA_SYNC_CONFLICT,
    DialogType.ROCKSTAR_SYNC_CONFLICT,
    DialogType.SYNC_IN_PROGRESS,
    DialogType.PENDING_UPLOAD,
    DialogType.APP_SESSION_ACTIVE,
    DialogType.ACCOUNT_SESSION_ACTIVE,
    DialogType.MULTIPLE_PENDING_OPERATIONS,
)

private fun relaunchesPreLaunch(state: MessageDialogState): Boolean = state.visible && state.type in RELAUNCHING_DIALOGS

fun preLaunchApp(
    context: Context,
    appId: String,
    ignorePendingOperations: Boolean = false,
    preferredSave: SaveLocation = SaveLocation.None,
    eaPreferredSave: SaveLocation = SaveLocation.None,
    rockstarPreferredSave: SaveLocation = SaveLocation.None,
    useTemporaryOverride: Boolean = false,
    skipCloudSync: Boolean = false,
    setLoadingDialogVisible: (Boolean) -> Unit,
    setLoadingProgress: (Float) -> Unit,
    setLoadingMessage: (String) -> Unit,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onSuccess: (Context, String) -> Unit,
    retryCount: Int = 0,
    isOffline: Boolean = false,
    bootToContainer: Boolean = false,
): Job {
    setLoadingDialogVisible(true)
    // TODO: add a way to cancel
    // TODO: add fail conditions

    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)

    // Returns the pre-launch job; it completes without calling onSuccess when the launch stops early.
    return CoroutineScope(Dispatchers.IO).launch {
        if (LaunchReadiness.pending) {
            setLoadingDialogVisible(false)
            (context as? Activity)?.let { LaunchReadiness.resolve(it) }
            return@launch
        }

        if (!bootToContainer && ContainerUtils.extractGameSourceFromContainerId(appId) == GameSource.STEAM &&
            !SteamAgreementGate.confirm(context, gameId, isOffline, setLoadingDialogVisible)
        ) {
            setLoadingDialogVisible(false)
            return@launch
        }

        // create container if it does not already exist
        // TODO: combine somehow with container creation in HomeLibraryAppScreen
        val containerManager = ContainerManager(context)
        val container = if (useTemporaryOverride) {
            ContainerUtils.getOrCreateContainerWithOverride(context, appId)
        } else {
            ContainerUtils.getOrCreateContainer(context, appId)
        }

        // Clear session metadata on every launch to ensure fresh values
        container.clearSessionMetadata()

        // Apply game-specific fixes before anything reads the container config. Some fixes
        // change launch-mode flags (e.g. bionic Steam) that the download/dependency steps
        // below and MainViewModel.launchApp consume, so this must run first.
        try {
            GameFixesRegistry.applyFor(context, appId, container)
        } catch (e: Exception) {
            Timber.tag("GameFixes").w(e, "Game fixes failed in preLaunchApp")
        }

        val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
        val isLocalSavesOnly = ContainerUtils.isLocalSavesOnly(context, appId)

        // Migrate legacy on-disk imagefs layout (e.g. legacy Proton → shared paths) before manifest
        // installs or launch deps — resolveMissingManifestInstallRequests can install Proton too.
        val legacyImageFsRoot = File(context.filesDir, "imagefs")
        val migrationOk = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(
            context,
            legacyImageFsRoot,
            container.wineVersion,
        )
        if (!migrationOk) {
            Timber.tag("preLaunchApp").e(
                "Legacy ImageFS migration failed: ${legacyImageFsRoot.absolutePath}",
            )
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.install_failed_title),
                    message = context.getString(R.string.install_failed_message),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }

        // When "Open container" is used we boot to desktop/file manager only — skip executable check
        if (!bootToContainer) {
            // Verify we have a launch executable for all platforms before proceeding (fail fast, avoid black screen)
            val effectiveExe = when (gameSource) {
                GameSource.STEAM -> SteamService.getLaunchExecutable(appId, container)
                GameSource.GOG -> GOGService.getLaunchExecutable(appId, container)
                GameSource.EPIC -> EpicService.getLaunchExecutable(appId)
                GameSource.CUSTOM_GAME -> CustomGameScanner.getLaunchExecutable(container)
                GameSource.AMAZON -> AmazonService.getLaunchExecutable(appId)
            }
            if (effectiveExe.isBlank()) {
                Timber.tag("preLaunchApp").w("Cannot launch $appId: no executable found (game source: $gameSource)")
                setLoadingDialogVisible(false)
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.EXECUTABLE_NOT_FOUND,
                        title = context.getString(R.string.game_executable_not_found_title),
                        message = context.getString(R.string.game_executable_not_found),
                        dismissBtnText = context.getString(R.string.ok),
                        actionBtnText = context.getString(AppOptionMenuType.EditContainer.title),
                    ),
                )
                return@launch
            }
        }

        // download any manifest components (wine/proton, dxvk, etc.) the container's config
        // references but that aren't installed yet — all sources, including custom games
        try {
            val configJson = Json.parseToJsonElement(container.containerJson).jsonObject
            val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
                context, configJson, "exact_gpu_match",
            )
            for (request in missingRequests) {
                setLoadingMessage(context.getString(R.string.main_downloading_entry, request.entry.name))
                try {
                    ManifestInstaller.installManifestEntry(
                        context, request.entry, request.isDriver, request.contentType,
                    ) { progress -> setLoadingProgress(progress.coerceIn(0f, 1f)) }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to install ${request.entry.name}, continuing")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to install manifest components")
            setLoadingDialogVisible(false)
            return@launch
        }

        // Check if this is a Custom Game and validate executable selection before installing components
        // Skip the check if booting to container (Open Container menu option)
        val isCustomGame = gameSource == GameSource.CUSTOM_GAME

        // set up Ubuntu file system — download required files and install
        SplitCompat.install(context)

        try {
            LaunchDependencies().ensureLaunchDependencies(
                context = context,
                container = container,
                gameSource = gameSource,
                gameId = gameId,
                setLoadingMessage = setLoadingMessage,
                setLoadingProgress = setLoadingProgress,
            )
        } catch (e: Exception) {
            Timber.tag("preLaunchApp").e(e, "ensureLaunchDependencies failed")
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.launch_dependency_failed_title),
                    message = e.message ?: context.getString(R.string.launch_dependency_failed_message),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }

        try {
            if (!SteamService.isImageFsInstallable(context, container.containerVariant)) {
                setLoadingMessage("Downloading first-time files")
                SteamService.downloadImageFs(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    variant = container.containerVariant,
                    context = context,
                ).await()
            }
            if (container.containerVariant.equals(Container.GLIBC) &&
                !SteamService.isFileInstallable(context, "imagefs_patches_gamenative.tzst")
            ) {
                setLoadingMessage("Downloading Wine")
                SteamService.downloadImageFsPatches(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                ).await()
            }

            if (!container.isUseLegacyDRM && !container.isLaunchRealSteam &&
                !SteamService.isFileInstallable(context, "experimental-drm-20260116.tzst")
            ) {
                setLoadingMessage("Downloading extras")
                SteamService.downloadFile(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                    "experimental-drm-20260116.tzst",
                ).await()
            }
            if (gameSource == GameSource.STEAM && container.isLaunchRealSteam && !isOffline && !container.isSteamOfflineMode &&
                SteamService.getInstalledApp(gameId) != null
            ) {
                // The Valve client refuses to start a build behind its own manifest and never
                // downloads itself. Offline launches trust the manifest the app wrote, so they skip this.
                val branch = SteamService.getInstalledApp(gameId)?.branch ?: "public"
                if (SteamService.isUpdatePending(gameId, branch)) {
                    val userChoice = CompletableDeferred<Boolean>()
                    setLoadingDialogVisible(false)
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.STEAM_UPDATE_PROMPT,
                            title = context.getString(R.string.steam_update_required_title),
                            message = context.getString(R.string.steam_update_required_message),
                            confirmBtnText = context.getString(R.string.main_update_button),
                            dismissBtnText = context.getString(R.string.cancel),
                        ),
                    )
                    steamUpdateDeferred = userChoice
                    val update = userChoice.await()
                    steamUpdateDeferred = null
                    setMessageDialogState(MessageDialogState(false))
                    if (update) {
                        val dlcAppIds = SteamService.getInstalledApp(gameId)?.dlcDepots.orEmpty()
                        SteamService.downloadApp(gameId, dlcAppIds, branch = branch, mode = SteamDownloadMode.UPDATE)
                    }
                    return@launch
                }
            }
            if (container.isLaunchHeadlessSteam && !SteamService.isFileInstallable(context, REAL_STEAM_CLIENT_ARCHIVE)) {
                setLoadingMessage(context.getString(R.string.main_downloading_steam))
                SteamService.downloadFile(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                    REAL_STEAM_CLIENT_ARCHIVE,
                ).await()
            }
            if (container.isLaunchHeadlessSteam && gameSource == GameSource.STEAM &&
                EaLaunchSupport.isEaTitle(gameId, File(SteamService.getAppDirPath(gameId)))
            ) {
                setLoadingMessage(context.getString(R.string.ea_preparing))
                app.gamenative.service.ea.EaHelperArchive.download(context) { setLoadingProgress(it) }
                val signIn = EaLoginGate.ensureSignedIn(context)
                if (signIn.isFailure) {
                    Timber.tag("preLaunchApp").w(signIn.exceptionOrNull(), "EA sign-in did not complete")
                    setLoadingDialogVisible(false)
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.SYNC_FAIL,
                            title = context.getString(R.string.ea_login_required_title),
                            message = context.getString(R.string.ea_login_failed, signIn.exceptionOrNull()?.message ?: ""),
                            dismissBtnText = context.getString(R.string.ok),
                        ),
                    )
                    return@launch
                }
                app.gamenative.service.ea.EaSteamLinkGate.offerSteamLink(context)
            }
            /*
             * Rockstar titles sign in through Social Club, and the Windows stub needs the
             * resulting ScAuthToken on disk before the game starts: it mints the ROS ticket with
             * CreateTicketScAuthToken2 and exits when the token file is missing. Minting from
             * Steam ownership instead was tried and refused by the server, so the account sign-in
             * is required rather than a convenience.
             */
            if (gameSource == GameSource.STEAM && (container.isLaunchHeadlessSteam || container.isLaunchBionicSteam) &&
                RockstarLaunchSupport.isRockstarTitle(File(SteamService.getAppDirPath(gameId)))
            ) {
                val rockstarGameDir = File(SteamService.getAppDirPath(gameId))
                setLoadingMessage(context.getString(R.string.rockstar_preparing))
                RockstarHelperArchive.downloadAndExtract(context) { setLoadingProgress(it) }
                val activeTitle = RockstarTitleMetadata.find(rockstarGameDir)?.let { RockstarTitleMetadata.parse(it) }?.titleId
                    ?.takeIf { it.matches(Regex("[a-z0-9_]{1,127}")) } ?: "launcher"
                val activationMarker = File(
                    RockstarHelperArchive.titleDir(rockstarGameDir),
                    "${RockstarHelperDeployment.DIRECTORY}/${RockstarConstants.ACTIVATION_MARKER}",
                )
                val signIn = RockstarLoginGate.ensureSignedIn(context, activeTitle, gameId, activationMarker.isFile)
                if (signIn.isSuccess && RockstarLoginGate.titleValidated) activationMarker.delete()
                if (signIn.isFailure && RockstarLaunchSupport.hasUsableToken(File(SteamService.getAppDirPath(gameId)))) {
                    /* A token is already in place, so carry on rather than block a launch that works. */
                    Timber.tag("preLaunchApp").w("Rockstar sign-in did not complete; using the token already in the game directory")
                } else if (signIn.isFailure) {
                    Timber.tag("preLaunchApp").w(signIn.exceptionOrNull(), "Rockstar sign-in did not complete")
                    setLoadingDialogVisible(false)
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.SYNC_FAIL,
                            title = context.getString(R.string.rockstar_login_required_title),
                            message = context.getString(R.string.rockstar_login_failed, signIn.exceptionOrNull()?.message ?: ""),
                            dismissBtnText = context.getString(R.string.ok),
                        ),
                    )
                    return@launch
                }
                val prefixDriveC = File(container.rootDir, ".wine/drive_c")
                if (!RockstarRuntime.isInstalled(prefixDriveC)) {
                    val installer = RockstarRuntime.installer(rockstarGameDir)
                    if (installer == null) {
                        setLoadingDialogVisible(false)
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_FAIL,
                                title = context.getString(R.string.rockstar_login_required_title),
                                message = context.getString(R.string.rockstar_installer_missing),
                                dismissBtnText = context.getString(R.string.ok),
                            ),
                        )
                        return@launch
                    }
                    setLoadingMessage(context.getString(R.string.rockstar_runtime_installing))
                    withContext(Dispatchers.IO) { RockstarRuntime.install(context, installer, prefixDriveC) { setLoadingProgress(it) } }
                }
                withContext(Dispatchers.IO) { RockstarRuntime.ensureX86(context, RockstarRuntime.installer(rockstarGameDir), prefixDriveC) { setLoadingProgress(it) } }
                withContext(Dispatchers.IO) {
                    check(RockstarLaunchSupport.placeToken(context, rockstarGameDir) || RockstarLaunchSupport.hasUsableToken(rockstarGameDir)) {
                        "Could not place Rockstar sign-in credentials in the game directory"
                    }
                    RockstarHelperDeployment.prepare(context.filesDir, rockstarGameDir)
                }
            }
            if ((container.isLaunchBionicSteam || (container.isLaunchRealSteam && !container.isLaunchHeadlessSteam)) &&
                !SteamService.isFileInstallable(context, "steam.tzst")
            ) {
                setLoadingMessage(context.getString(R.string.main_downloading_steam))
                SteamService.downloadSteam(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                ).await()
            }
            if ((container.isLaunchRealSteam || container.isLaunchBionicSteam) && !SteamService.isFileInstallable(context, "steam-token.tzst")) {
                setLoadingMessage("Downloading steam-token")
                SteamService.downloadFile(
                    onDownloadProgress = { setLoadingProgress(it / 1.0f) },
                    this,
                    context = context,
                    "steam-token.tzst",
                ).await()
            }
        } catch (e: Exception) {
            Timber.tag("preLaunchApp").e(e, "File download failed")
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.download_failed_title),
                    message = e.message ?: context.getString(R.string.download_failed_message),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }

        val loadingMessage = if (container.containerVariant.equals(Container.GLIBC)) {
            context.getString(R.string.main_installing_glibc)
        } else {
            context.getString(R.string.main_installing_bionic)
        }
        setLoadingMessage(loadingMessage)
        val imageFsInstallSuccess =
            ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { progress ->
                setLoadingProgress(progress / 100f)
            }.get()

        if (!imageFsInstallSuccess) {
            Timber.tag("preLaunchApp").e("ImageFS installation failed")
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.install_failed_title),
                    message = context.getString(R.string.install_failed_message),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }

        setLoadingMessage(context.getString(R.string.main_loading))
        setLoadingProgress(-1f)

        // must activate container before downloading save files
        containerManager.activateContainer(container)

        // If another Steam game is running on this account elsewhere, prompt the user.
        // Skip the prompt for apps we don't recognise — non-Steam shortcuts on the remote
        // device report synthetic IDs that aren't kickable.
        val isSteamGame = gameSource == GameSource.STEAM
        if (isSteamGame) {
            try {
                val currentPlaying = SteamService.getSelfCurrentlyPlayingAppId()
                if (!isOffline && currentPlaying != null && currentPlaying != gameId) {
                    val otherApp = SteamService.getAppInfoOf(currentPlaying)
                    if (otherApp != null) {
                        setLoadingDialogVisible(false)
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.ACCOUNT_SESSION_ACTIVE,
                                title = context.getString(R.string.main_app_running_title),
                                message = context.getString(R.string.main_app_running_message, otherApp.name),
                                confirmBtnText = context.getString(R.string.main_play_anyway),
                                dismissBtnText = context.getString(R.string.cancel),
                            ),
                        )
                        return@launch
                    }
                }
            } catch (_: Exception) { /* ignore persona read errors */ }
        }

        // For Custom Games, bypass Steam Cloud operations entirely and proceed to launch
        if (isCustomGame) {
            Timber.tag("preLaunchApp").i("Custom Game detected for $appId — skipping Steam Cloud sync and launching container")
            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For GOG Games, sync cloud saves before launch (executable already verified above via GOGService.getLaunchExecutable)
        val isGOGGame = gameSource == GameSource.GOG
        if (isGOGGame) {
            if (isLocalSavesOnly) {
                Timber.tag("GOG").i("[Cloud Saves] Local saves only enabled for $appId — skipping pre-game cloud sync")
            } else {
                Timber.tag("GOG").i("[Cloud Saves] GOG Game detected for $appId — syncing cloud saves before launch")

                // Map an explicit user choice (from the conflict dialog) to a forced sync direction.
                val gogPreferredAction = when (preferredSave) {
                    SaveLocation.Local -> "forceupload" // "Keep local" -> force local up, past the conflict guard
                    SaveLocation.Remote -> "download" // "Keep remote" -> pull cloud down
                    SaveLocation.None -> null
                }

                // Initial launch (no choice yet): detect a conflict and prompt before syncing.
                if (gogPreferredAction == null) {
                    val conflict = GOGService.detectCloudSaveConflict(context, appId)
                    if (conflict != null) {
                        Timber.tag("GOG").i("[Cloud Saves] Conflict detected for $appId — prompting user")
                        val localDate = Date(conflict.localTimestamp).toString()
                        val remoteDate = Date(conflict.remoteTimestamp).toString()
                        setLoadingDialogVisible(false)
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_CONFLICT,
                                title = context.getString(R.string.main_save_conflict_title),
                                message = context.getString(R.string.main_save_conflict_message, localDate, remoteDate),
                                dismissBtnText = context.getString(R.string.main_keep_local),
                                confirmBtnText = context.getString(R.string.main_keep_remote),
                            ),
                        )
                        // Wait for the user; the dialog handlers re-enter preLaunchApp with a SaveLocation.
                        return@launch
                    }
                }

                // No conflict, or the user already chose a side: perform the sync.
                val action = gogPreferredAction ?: "none"
                Timber.tag("GOG").d("[Cloud Saves] Starting pre-game sync for $appId (action: $action)")
                val syncSuccess = GOGService.syncCloudSaves(
                    context = context,
                    appId = appId,
                    preferredAction = action,
                )

                if (!syncSuccess) {
                    Timber.tag("GOG").w("[Cloud Saves] Pre-game sync failed for $appId, proceeding with launch anyway")
                    // Don't block launch on sync failure - log warning and continue
                } else {
                    Timber.tag("GOG").i("[Cloud Saves] Pre-game sync completed successfully for $appId")
                }
            }

            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For Amazon Games, skip cloud sync entirely (Amazon doesn't support cloud saves)
        val isAmazonGame = gameSource == GameSource.AMAZON
        if (isAmazonGame) {
            Timber.tag("preLaunchApp").i("Amazon Game detected for $appId — skipping cloud sync and launching container")
            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For Epic Games, sync cloud saves before launch (executable already verified above via EpicService.getLaunchExecutable)
        val isEpicGame = gameSource == GameSource.EPIC
        if (isEpicGame) {
            if (isLocalSavesOnly) {
                Timber.tag("Epic").i("[Cloud Saves] Local saves only enabled for $appId — skipping pre-game cloud sync")
            } else {
                // Handle Cloud Saves
                Timber.tag("Epic").i("[Cloud Saves] Epic Game detected for $appId — syncing cloud saves before launch")
                // Sync cloud saves (download latest saves before playing)
                Timber.tag("Epic").d("[Cloud Saves] Starting pre-game download sync for $appId")
                val syncSuccess = app.gamenative.service.epic.EpicCloudSavesManager.syncCloudSaves(
                    context = context,
                    appId = gameId,
                )

                if (!syncSuccess) {
                    Timber.tag("Epic").w("[Cloud Saves] Download sync failed for $appId, proceeding with launch anyway")
                    // Don't block launch on sync failure - log warning and continue
                } else {
                    Timber.tag("Epic").i("[Cloud Saves] Download sync completed successfully for $appId")
                }
            }

            // Delete Ownership Token if exists
            Timber.tag("Epic").i("[Ownership Tokens] Cleaning up launch tokens for Epic games...")
            EpicService.cleanupLaunchTokens(context, container)

            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        if (skipCloudSync || isLocalSavesOnly) {
            if (isLocalSavesOnly) {
                Timber.tag("preLaunchApp").i("Local saves only enabled for $appId — skipping Steam Cloud sync")
            } else {
                Timber.tag("preLaunchApp").w("Skipping Steam Cloud sync for $appId by user request")
            }
            setLoadingDialogVisible(false)
            onSuccess(context, appId)
            return@launch
        }

        // For Steam games, sync save files and check no pending remote operations are running
        val prefixToPath: (String) -> String = { prefix ->
            PathType.from(prefix).toAbsPath(container, gameId, SteamService.userSteamId!!.accountID)
        }

        // Workshop mod sync: check for updates and download if needed
        val appDao = SteamService.instance?.appDao
        val workshopMods = appDao?.getWorkshopMods(gameId) ?: false
        val enabledWorkshopIds = WorkshopManager.parseEnabledIds(
            appDao?.getEnabledWorkshopItemIds(gameId),
        )
        Timber.tag("Workshop").i(
            "Workshop launch check: workshopMods=$workshopMods, enabledIds=${enabledWorkshopIds.size}"
        )
        if (workshopMods && enabledWorkshopIds.isNotEmpty()) {
            try {
                // Skip workshop sync if Steam isn't fully connected yet
                // (can happen if the user taps Play immediately after opening the app).
                if (isOffline || !SteamService.isConnected || !SteamService.isLoggedIn) {
                    Timber.tag("Workshop").w(
                        "Steam not connected/logged in or offline, skipping workshop sync for appId=$gameId"
                    )
                    if (gameId == SlayTheSpireModTheSpireCompatibility.APP_ID) {
                        setLoadingMessage(context.getString(R.string.workshop_processing))
                        setLoadingProgress(-1f)
                        WorkshopManager.configureLocalWorkshopContentForEnabledIds(
                            context = context,
                            appId = gameId,
                            enabledIds = enabledWorkshopIds,
                        )
                    }
                } else {
                // If a background download is still running from the save
                // handler, wait for it to finish so its markers are on disk
                // before we check for updates (avoids false re-download prompt).
                SteamService.getAppDownloadInfo(gameId)?.let { activeDownload ->
                    Timber.tag("Workshop").i(
                        "Active workshop download in progress for appId=$gameId, waiting…"
                    )
                    setLoadingMessage(context.getString(R.string.workshop_checking_mods))
                    setLoadingProgress(-1f)
                    activeDownload.awaitCompletion(timeoutMs = 120_000)
                    Timber.tag("Workshop").i("Active download finished, proceeding with update check")
                }

                setLoadingMessage(context.getString(R.string.workshop_checking_mods))
                setLoadingProgress(-1f)

                val updateCheck = WorkshopManager.checkForWorkshopUpdates(
                    appId = gameId,
                    enabledIds = enabledWorkshopIds,
                    context = context,
                )

                if (updateCheck != null) {
                    val totalBytes = updateCheck.totalUpdateBytes
                    val thresholdBytes = WorkshopManager.getUpdateThresholdBytes()
                    val sizeMB = String.format(Locale.US, "%.0f", totalBytes / 1_048_576.0)
                    Timber.tag("Workshop").i(
                        "${updateCheck.itemsToSync.size} mod update(s), ${sizeMB}MB total"
                    )

                    // Decide whether to download: auto if under threshold, prompt if over
                    val shouldDownload = if (totalBytes > thresholdBytes) {
                        val userChoice = CompletableDeferred<Boolean>()
                        setLoadingDialogVisible(false)
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.WORKSHOP_UPDATE_PROMPT,
                                title = context.getString(R.string.workshop_update_title),
                                message = context.getString(R.string.workshop_update_prompt, sizeMB),
                                confirmBtnText = context.getString(R.string.workshop_download_btn),
                                dismissBtnText = context.getString(R.string.workshop_skip_btn),
                            )
                        )
                        workshopUpdateDeferred = userChoice
                        val result = userChoice.await()
                        workshopUpdateDeferred = null
                        setMessageDialogState(MessageDialogState(false))
                        setLoadingDialogVisible(true)
                        result
                    } else {
                        true // Under threshold, download automatically
                    }

                    var downloadedItems = false

                    if (shouldDownload) {
                        val spaceError = WorkshopManager.checkDiskSpace(
                            updateCheck.workshopContentDir, totalBytes * 2,
                        )
                        if (spaceError != null) {
                            Timber.tag("Workshop").e(spaceError)
                            SnackbarManager.show(spaceError)
                        } else {
                            val steamClient = SteamService.instance?.steamClient
                            if (steamClient != null) {
                                setLoadingMessage("Downloading Workshop Mods (0/${updateCheck.itemsToSync.size})")
                                val licenses = SteamService.getLicensesFromDb()
                                var currentCompleted = 0
                                var currentTitle = updateCheck.itemsToSync.firstOrNull()?.title ?: ""

                                val successCount = WorkshopManager.downloadItems(
                                    items = updateCheck.itemsToSync,
                                    steamClient = steamClient,
                                    licenses = licenses,
                                    workshopContentDir = updateCheck.workshopContentDir,
                                    onItemProgress = { completed, total, title ->
                                        currentCompleted = completed
                                        currentTitle = title
                                        setLoadingMessage(
                                            context.getString(R.string.workshop_downloading, completed, total) +
                                                "\n$title"
                                        )
                                    },
                                    onBytesProgress = { downloaded, total ->
                                        if (total > 0) {
                                            setLoadingProgress(downloaded.toFloat() / total.toFloat())
                                            setLoadingMessage(
                                                context.getString(R.string.workshop_downloading, currentCompleted, updateCheck.itemsToSync.size) +
                                                    "\n$currentTitle\n" +
                                                    "${StringUtils.formatBytes(downloaded)} / ${StringUtils.formatBytes(total)}"
                                            )
                                        }
                                    },
                                )

                                val failedCount = updateCheck.itemsToSync.size - successCount
                                if (failedCount > 0) {
                                    Timber.tag("Workshop").w("$failedCount mod update(s) failed")
                                    SnackbarManager.show(context.getString(R.string.workshop_failed_count, failedCount))
                                }
                                downloadedItems = true
                            }
                        }
                    } else {
                        Timber.tag("Workshop").i("User skipped workshop mod updates")
                    }

                    // Post-processing and symlinks (runs for both download and skip paths)
                    setLoadingMessage(context.getString(R.string.workshop_processing))
                    setLoadingProgress(-1f)
                    WorkshopManager.runPostProcessing(
                        if (downloadedItems) updateCheck.itemsToSync else null,
                        updateCheck.allItems, updateCheck.workshopContentDir,
                    ) { status ->
                        setLoadingMessage(status)
                    }
                    WorkshopManager.configureSymlinksForApp(
                        context, gameId, updateCheck.allItems,
                        updateCheck.winePrefix, updateCheck.workshopContentDir,
                    )
                }
                // If updateCheck is null, checkForWorkshopUpdates already handled everything
                Timber.tag("Workshop").i("Workshop mod sync complete for appId=$gameId")
                } // else (isLoggedIn)
            } catch (e: Exception) {
                Timber.tag("Workshop").e(e, "Workshop mod sync failed, continuing without mods")
            }
        } else if (
            gameId == SlayTheSpireModTheSpireCompatibility.APP_ID &&
            File(SteamService.getAppDirPath(gameId)).isDirectory
        ) {
            withContext(Dispatchers.IO) {
                WorkshopManager.cleanupDisabledWorkshopArtifactsForApp(context, gameId)
            }
        }

        setLoadingMessage("Syncing cloud saves")
        setLoadingProgress(-1f)
        if (container.isLaunchHeadlessSteam && gameSource == GameSource.STEAM) {
            try {
                val eaGameDir = File(SteamService.getAppDirPath(gameId))
                if (EaLaunchSupport.isEaTitle(gameId, eaGameDir)) {
                    val eaPreference = when (eaPreferredSave) {
                        SaveLocation.Local -> EaCloudPreference.LOCAL
                        SaveLocation.Remote -> EaCloudPreference.REMOTE
                        SaveLocation.None -> EaCloudPreference.NONE
                    }
                    val eaPull = EaCloudSavesManager.syncBeforeLaunch(context, container, gameId, eaGameDir, eaPreference)
                    if (eaPull is EaCloudSavesManager.PullResult.Conflict) {
                        Timber.tag("EA").i("Cloud save conflict for $appId, prompting user")
                        val localDate = Date(eaPull.localMillis).toString()
                        val remoteDate = eaPull.remoteMillis?.let { Date(it).toString() }
                            ?: context.getString(R.string.container_storage_source_unknown)
                        setLoadingDialogVisible(false)
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.EA_SYNC_CONFLICT,
                                title = context.getString(R.string.main_save_conflict_title),
                                message = context.getString(R.string.main_save_conflict_message, localDate, remoteDate),
                                dismissBtnText = context.getString(R.string.main_keep_local),
                                confirmBtnText = context.getString(R.string.main_keep_remote),
                            ),
                        )
                        return@launch
                    }
                    Timber.tag("EA").i("Cloud save pull for $appId: $eaPull")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("EA").w("Cloud save pull failed for $appId: ${e.javaClass.simpleName}")
            }
        }

        if (gameSource == GameSource.STEAM && (container.isLaunchHeadlessSteam || container.isLaunchBionicSteam) &&
            !container.isLocalSavesOnly && !bootToContainer && !isOffline
        ) {
            try {
                val rockstarGameDir = File(SteamService.getAppDirPath(gameId))
                if (RockstarLaunchSupport.isRockstarTitle(rockstarGameDir)) {
                    val rockstarPreference = when (rockstarPreferredSave) {
                        SaveLocation.Local -> RockstarCloudPreference.LOCAL
                        SaveLocation.Remote -> RockstarCloudPreference.REMOTE
                        SaveLocation.None -> RockstarCloudPreference.NONE
                    }
                    val rockstarPull = RockstarCloudSavesManager.syncBeforeLaunch(context, container, rockstarGameDir, rockstarPreference)
                    if (rockstarPull is RockstarCloudSavesManager.PullResult.Conflict) {
                        Timber.tag("RockstarCloud").i("Cloud save conflict for $appId, prompting user")
                        val localDate = Date(rockstarPull.localMillis).toString()
                        val remoteDate = rockstarPull.remoteMillis?.let { Date(it).toString() }
                            ?: context.getString(R.string.container_storage_source_unknown)
                        setLoadingDialogVisible(false)
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.ROCKSTAR_SYNC_CONFLICT,
                                title = context.getString(R.string.main_save_conflict_title),
                                message = context.getString(R.string.main_save_conflict_message, localDate, remoteDate),
                                dismissBtnText = context.getString(R.string.main_keep_local),
                                confirmBtnText = context.getString(R.string.main_keep_remote),
                            ),
                        )
                        return@launch
                    }
                    Timber.tag("RockstarCloud").i("Cloud save pull for $appId: $rockstarPull")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("RockstarCloud").w("Cloud save pull failed for $appId: ${e.javaClass.simpleName}")
            }
        } else {
            runCatching { RockstarCloudSavesManager.forgetSession(File(SteamService.getAppDirPath(gameId))) }
        }

        val postSyncInfo = SteamService.beginLaunchApp(
            appId = gameId,
            prefixToPath = prefixToPath,
            ignorePendingOperations = ignorePendingOperations,
            preferredSave = preferredSave,
            parentScope = this,
            isOffline = isOffline,
            onProgress = { message, progress ->
                setLoadingMessage(message)
                setLoadingProgress(if (progress < 0) -1f else progress)
            },
        ).await()

        setLoadingDialogVisible(false)

        when (postSyncInfo.syncResult) {
            SyncResult.Conflict -> {
                val localDate = Date(postSyncInfo.localTimestamp).toString()
                val remoteDate = Date(postSyncInfo.remoteTimestamp).toString()
                val (conflictTitle, conflictMessage) = postSyncInfo.conflictUfsVersion
                    ?.let { v ->
                        val titleId = context.resources.getIdentifier(
                            "main_save_conflict_upgrade_v${v}_title", "string", context.packageName,
                        )
                        val msgId = context.resources.getIdentifier(
                            "main_save_conflict_upgrade_v${v}_message", "string", context.packageName,
                        )
                        if (titleId != 0 && msgId != 0) {
                            context.getString(titleId) to context.getString(msgId, localDate, remoteDate)
                        } else {
                            null
                        }
                    }
                    ?: run {
                        context.getString(R.string.main_save_conflict_title) to
                            context.getString(R.string.main_save_conflict_message, localDate, remoteDate)
                    }
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_CONFLICT,
                        title = conflictTitle,
                        message = conflictMessage,
                        dismissBtnText = context.getString(R.string.main_keep_local),
                        confirmBtnText = context.getString(R.string.main_keep_remote),
                    ),
                )
            }

            SyncResult.InProgress -> {
                if (useTemporaryOverride && retryCount < 5) {
                    // For intent launches, retry after a short delay (max 5 retries = ~10 seconds)
                    Timber.i("Sync in progress for intent launch, retrying in 2 seconds... (attempt ${retryCount + 1}/5)")
                    delay(2000)
                    // joined so this pre-launch job only completes once the retry has finished or launched
                    preLaunchApp(
                        context = context,
                        appId = appId,
                        ignorePendingOperations = ignorePendingOperations,
                        preferredSave = preferredSave,
                        eaPreferredSave = eaPreferredSave,
                        rockstarPreferredSave = rockstarPreferredSave,
                        useTemporaryOverride = useTemporaryOverride,
                        setLoadingDialogVisible = setLoadingDialogVisible,
                        setLoadingProgress = setLoadingProgress,
                        setLoadingMessage = setLoadingMessage,
                        setMessageDialogState = setMessageDialogState,
                        onSuccess = onSuccess,
                        retryCount = retryCount + 1,
                        bootToContainer = bootToContainer,
                    ).join()
                } else {
                    setMessageDialogState(
                        MessageDialogState(
                            visible = true,
                            type = DialogType.SYNC_IN_PROGRESS,
                            title = context.getString(R.string.sync_error_title),
                            message = context.getString(R.string.main_sync_in_progress_launch_anyway_message),
                            confirmBtnText = context.getString(R.string.main_launch_anyway),
                            dismissBtnText = context.getString(R.string.main_wait),
                        ),
                    )
                }
            }

            SyncResult.UnknownFail,
            SyncResult.DownloadFail,
            SyncResult.UpdateFail,
            -> {
                setMessageDialogState(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.sync_error_title),
                        message = context.getString(R.string.main_sync_failed, postSyncInfo.syncResult.toString()),
                        dismissBtnText = context.getString(R.string.ok),
                    ),
                )
            }

            SyncResult.PendingOperations -> {
                Timber.i(
                    "Pending remote operations:${
                        postSyncInfo.pendingRemoteOperations.joinToString("\n") { pro ->
                            "\n\tmachineName: ${pro.machineName}" +
                                "\n\ttimestamp: ${Date(pro.timeLastUpdated * 1000L)}" +
                                "\n\toperation: ${pro.operation}"
                        }
                    }",
                )
                if (postSyncInfo.pendingRemoteOperations.size == 1) {
                    val pro = postSyncInfo.pendingRemoteOperations.first()
                    val gameName = SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(appId))?.name ?: ""
                    val dateStr = Date(pro.timeLastUpdated * 1000L).toString()
                    when (pro.operation) {
                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadInProgress -> {
                            // maybe this should instead wait for the upload to finish and then
                            // launch the app
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_UPLOAD_IN_PROGRESS,
                                    title = context.getString(R.string.main_upload_in_progress_title),
                                    message = context.getString(
                                        R.string.main_upload_in_progress_message,
                                        gameName,
                                        pro.machineName,
                                        dateStr,
                                    ),
                                    dismissBtnText = context.getString(R.string.ok),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadPending -> {
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_UPLOAD,
                                    title = context.getString(R.string.main_pending_upload_title),
                                    message = context.getString(
                                        R.string.main_pending_upload_message,
                                        gameName,
                                        pro.machineName,
                                        dateStr,
                                    ),
                                    confirmBtnText = context.getString(R.string.main_play_anyway),
                                    dismissBtnText = context.getString(R.string.cancel),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionActive -> {
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_SESSION_ACTIVE,
                                    title = context.getString(R.string.main_app_running_title),
                                    message = context.getString(
                                        R.string.main_app_running_other_device,
                                        pro.machineName,
                                        gameName,
                                        dateStr,
                                    ),
                                    confirmBtnText = context.getString(R.string.main_play_anyway),
                                    dismissBtnText = context.getString(R.string.cancel),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionSuspended -> {
                            // I don't know what this means, yet
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_SESSION_SUSPENDED,
                                    title = context.getString(R.string.sync_error_title),
                                    message = context.getString(R.string.main_app_session_suspended),
                                    dismissBtnText = context.getString(R.string.ok),
                                ),
                            )
                        }

                        ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationNone -> {
                            // why are we here
                            setMessageDialogState(
                                MessageDialogState(
                                    visible = true,
                                    type = DialogType.PENDING_OPERATION_NONE,
                                    title = context.getString(R.string.sync_error_title),
                                    message = context.getString(R.string.main_pending_operation_none),
                                    dismissBtnText = context.getString(R.string.ok),
                                ),
                            )
                        }
                    }
                } else {
                    val uploadInProgress = postSyncInfo.pendingRemoteOperations.firstOrNull {
                        it.operation == ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationUploadInProgress
                    }
                    if (uploadInProgress != null) {
                        val gameName = SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(appId))?.name ?: ""
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.PENDING_UPLOAD_IN_PROGRESS,
                                title = context.getString(R.string.main_upload_in_progress_title),
                                message = context.getString(
                                    R.string.main_upload_in_progress_message,
                                    gameName,
                                    uploadInProgress.machineName,
                                    Date(uploadInProgress.timeLastUpdated * 1000L).toString(),
                                ),
                                dismissBtnText = context.getString(R.string.ok),
                            ),
                        )
                    } else {
                        setMessageDialogState(
                            MessageDialogState(
                                visible = true,
                                type = DialogType.MULTIPLE_PENDING_OPERATIONS,
                                title = context.getString(R.string.sync_error_title),
                                message = context.getString(R.string.main_multiple_pending_operations_message),
                                confirmBtnText = context.getString(R.string.main_play_anyway),
                                dismissBtnText = context.getString(R.string.cancel),
                            ),
                        )
                    }
                }
            }

            SyncResult.UpToDate,
            SyncResult.Success,
            -> onSuccess(context, appId)
        }
    }
}
