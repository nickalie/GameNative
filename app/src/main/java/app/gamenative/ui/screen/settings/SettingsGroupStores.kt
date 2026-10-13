package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.StoreToggles
import app.gamenative.service.StoreServices
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.util.storeNameRes
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch

/** Order of the switches in Settings → Stores. */
private val STORE_ORDER = listOf(
    GameSource.STEAM,
    GameSource.GOG,
    GameSource.EPIC,
    GameSource.AMAZON,
    GameSource.CUSTOM_GAME,
)

/** One switch per store; see [StoreToggles] for what turning a store off does. */
@Composable
fun SettingsGroupStores() {
    val context = LocalContext.current
    val enabledStores by StoreToggles.enabled.collectAsState()
    SettingsGroup {
        Text(
            text = stringResource(R.string.settings_stores_description),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        STORE_ORDER.forEach { source ->
            SettingsSwitch(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(source.storeNameRes)) },
                state = source in enabledStores,
                onCheckedChange = { StoreServices.setEnabled(context, source, it) },
            )
        }
    }
}
