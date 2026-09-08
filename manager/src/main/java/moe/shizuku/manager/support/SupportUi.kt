package moe.shizuku.manager.support

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.MessageDialog
import java.io.File
import java.util.Date

internal fun sessionLabel(context: Context, started: Long) =
    DateFormat.getMediumDateFormat(context).format(Date(started)) + " " + DateFormat.getTimeFormat(context).format(Date(started))

internal fun logShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Porter debug log", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, context.getString(R.string.porter_debug_share))
}

@Composable
internal fun SupportDialogs(model: SupportViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            for (intent in model.events) {
                try { context.startActivity(intent) } catch (e: Exception) { model.report(e) }
            }
        }
    }
    val consent by model.consent.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val state by model.recording.collectAsStateWithLifecycle()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.performRecording(false)
    }
    consent?.let { stop ->
        val title = stringResource(if (stop) R.string.porter_debug_stop else R.string.porter_debug_start)
        AlertDialog(onDismissRequest = model::dismissRecording, title = { Text(title) }, text = {
            Text(stringResource(when {
                stop && System.currentTimeMillis() - state.started < 15_000 -> R.string.porter_debug_short
                stop -> R.string.porter_debug_stop_message
                else -> R.string.porter_debug_consent
            }))
        }, confirmButton = { TextButton(enabled = !busy, onClick = {
            if (!stop && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else model.performRecording(stop)
        }) { if (busy) CircularProgressIndicator() else Text(title) } },
            dismissButton = { TextButton(enabled = !busy, onClick = model::dismissRecording) { Text(stringResource(android.R.string.cancel)) } },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy))
    }
    error?.let { MessageDialog(stringResource(R.string.porter_support_error), it, { model.error.value = null }) }
}
