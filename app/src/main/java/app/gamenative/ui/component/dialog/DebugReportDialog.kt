package app.gamenative.ui.component.dialog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import app.gamenative.R
import app.gamenative.ui.component.dialog.state.DebugReportDialogState
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.DebugReportUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Shown after a debug run: lets the user share the captured logs or save them as a zip. */
@Composable
fun DebugReportDialog(
    state: DebugReportDialogState,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            saving = false
        } else {
            scope.launch {
                try {
                    saveReport(context, File(state.reportDir), uri)
                } finally {
                    saving = false
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.debug_report_share_log_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (state.noLog) {
                    Text(
                        text = stringResource(R.string.debug_report_no_log),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.close))
                        }
                    }
                    return@Column
                }

                Text(
                    text = stringResource(R.string.debug_report_summary, state.gameName, state.deviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(bottom = 16.dp),
                )

                if (state.preparing) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.debug_report_preparing),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.textMuted,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                val shareFocusRequester = remember { FocusRequester() }
                LaunchedEffect(state.preparing) {
                    if (!state.preparing) runCatching { shareFocusRequester.requestFocus() }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                    TextButton(
                        onClick = {
                            saving = true
                            saveLauncher.launch("debug_${state.appId}.zip")
                        },
                        enabled = !state.preparing && !saving,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                    Button(
                        onClick = { shareReport(context, File(state.reportDir)) },
                        enabled = !state.preparing,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .focusRequester(shareFocusRequester),
                    ) {
                        Text(stringResource(R.string.main_share))
                    }
                }
            }
        }
    }
}

private fun shareReport(context: Context, reportDir: File) {
    val files = DebugReportUtils.exportFiles(reportDir)
    if (files.isEmpty()) return
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
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.debug_report_share_log_title)))
}

private suspend fun saveReport(context: Context, reportDir: File, uri: Uri) {
    val message = try {
        withContext(Dispatchers.IO) {
            try {
                val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open $uri")
                DebugReportUtils.writeZip(reportDir, output)
            } catch (e: Throwable) {
                // Do not leave an empty or truncated zip behind; best-effort, the provider may refuse.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    .onFailure { Timber.w(it, "DebugReportDialog: Failed to delete partial $uri") }
                throw e
            }
        }
        context.getString(R.string.base_app_exported)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "DebugReportDialog: Failed to save debug report")
        context.getString(R.string.base_app_export_failed, e.message ?: "")
    }
    SnackbarManager.show(message)
}
