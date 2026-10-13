package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.ui.component.dialog.AccountSignInDialog
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import kotlinx.coroutines.launch

@Composable
fun SettingsGroupGameNativeAccount() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val signedIn by PrefManager.gameNativeSignedIn
    val account by AccountApi.account
    var showSignIn by rememberSaveable { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        AccountApi.loadSignedInState()
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
    }
}
