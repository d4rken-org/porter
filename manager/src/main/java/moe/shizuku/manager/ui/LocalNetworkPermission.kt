package moe.shizuku.manager.ui

import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import moe.shizuku.manager.R

@Composable
fun LocalNetworkPermission(onReady: () -> Unit): Boolean {
    val context = LocalContext.current
    val permission = when {
        Build.VERSION.SDK_INT >= 37 -> "android.permission.ACCESS_LOCAL_NETWORK"
        Build.VERSION.SDK_INT >= 36 -> android.Manifest.permission.NEARBY_WIFI_DEVICES
        else -> null
    }
    var granted by remember { mutableStateOf(permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    var requested by rememberSaveable { mutableStateOf(false) }
    val ready by rememberUpdatedState(onReady)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) { if (granted) ready() }
    LaunchedEffect(Unit) {
        if (!granted && !requested && permission != null) { requested = true; launcher.launch(permission) }
    }
    if (!granted) {
        Text(stringResource(R.string.porter_local_network_permission))
        TextButton(onClick = { if (permission != null) launcher.launch(permission) }) { Text(stringResource(R.string.enable)) }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
            Text(stringResource(R.string.settings_title))
        }
    }
    return granted
}
