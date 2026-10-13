package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.api.DebugReportApi
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.dialog.AccountSignInDialog
import app.gamenative.ui.component.dialog.DiscordLinkDialog
import app.gamenative.ui.component.dialog.openAccountUrl
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsGroupGameNativeAccount() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val signedIn by PrefManager.gameNativeSignedIn
    val account by AccountApi.account
    var showSignIn by rememberSaveable { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val discordConnected by PrefManager.discordRelayTokenPresent
    var discordLinkedName by remember { mutableStateOf("") }
    var showDiscordLink by rememberSaveable { mutableStateOf(false) }

    val openDiscordLinkHere: suspend () -> Boolean = {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        PrefManager.discordOauthNonce = nonce
        val accountUrl = if (signedIn) {
            when (val result = SupportApi.startDiscordLink(nonce)) {
                is ApiResult.Success -> result.data
                else -> null
            }
        } else {
            null
        }
        openAccountUrl(context, accountUrl ?: "${DebugReportApi.OAUTH_START_URL}?app_state=$nonce")
    }

    LaunchedEffect(Unit) {
        AccountApi.loadSignedInState()
    }

    LaunchedEffect(discordConnected) {
        withContext(Dispatchers.IO) {
            PrefManager.discordRelayTokenPresent.value = PrefManager.discordRelayToken.isNotEmpty()
            discordLinkedName = PrefManager.discordLinkedName
        }
    }

    LaunchedEffect(signedIn, discordConnected) {
        if (signedIn && discordConnected && withContext(Dispatchers.IO) { PrefManager.discordMergePending }) {
            if (SupportApi.listConversations() is ApiResult.Success) AccountApi.fetchAccount()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadAttempt++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(signedIn, loadAttempt) {
        if (signedIn) {
            loadFailed = false
            loadFailed = AccountApi.fetchAccount() !is ApiResult.Success
        }
    }

    AccountSignInDialog(
        visible = showSignIn,
        onSignedIn = { showSignIn = false },
        onDismiss = { showSignIn = false },
    )

    DiscordLinkDialog(
        visible = showDiscordLink,
        signedIn = signedIn,
        onOpenHere = openDiscordLinkHere,
        onDismiss = { showDiscordLink = false },
    )

    SettingsGroup {
        if (!signedIn) {
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(stringResource(R.string.gamenative_account_sign_in)) },
                subtitle = { Text(stringResource(R.string.gamenative_account_sign_in_subtitle)) },
                icon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                onClick = { showSignIn = true },
            )
        } else {
            val current = account
            val status = when {
                current != null -> null
                loadFailed -> R.string.gamenative_account_load_failed
                else -> R.string.gamenative_account_loading
            }
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(current?.email?.ifBlank { null } ?: stringResource(R.string.gamenative_account_title)) },
                subtitle = status?.let { res -> { Text(stringResource(res)) } },
                icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                onClick = {
                    if (current == null && loadFailed) loadAttempt++
                },
            )

            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(stringResource(R.string.gamenative_account_sign_out)) },
                subtitle = { Text(stringResource(R.string.gamenative_account_sign_out_subtitle)) },
                icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                onClick = {
                    loadFailed = false
                    scope.launch { AccountApi.signOut() }
                },
            )
        }

        val discordName = account?.discordName ?: discordLinkedName.ifBlank { null }
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.gamenative_account_discord)) },
            subtitle = {
                Text(
                    when {
                        !discordConnected -> stringResource(R.string.gamenative_account_discord_not_connected)
                        discordName != null -> stringResource(R.string.gamenative_account_discord_connected_as, discordName)
                        else -> stringResource(R.string.gamenative_account_discord_connected)
                    },
                )
            },
            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
            onClick = {
                if (discordConnected) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            PrefManager.discordRelayToken = ""
                            PrefManager.discordLinkedName = ""
                            PrefManager.discordMergePending = false
                        }
                        discordLinkedName = ""
                    }
                } else {
                    showDiscordLink = true
                }
            },
        )
    }
}
