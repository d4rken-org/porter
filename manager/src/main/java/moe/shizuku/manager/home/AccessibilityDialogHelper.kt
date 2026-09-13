package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.ui.ComposeDialogFragment
import moe.shizuku.manager.utils.SettingsPage
import rikka.core.content.asActivity

fun Context.showAccessibilityDialog() {
    val installer = packageManager.getInstallerPackageName(packageName)
    val step = when {
        isAccessibilityEnabled() -> "navigate"
        checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED -> {
            if (enableAccessibilityService()) return
            "permission"
        }
        installer != "com.android.vending" && installer != null && Build.VERSION.SDK_INT <= 34 -> "permission"
        else -> "enable"
    }
    AccessibilityDialogFragment().apply { arguments = Bundle().apply { putString("step", step) } }
        .show(asActivity<FragmentActivity>().supportFragmentManager)
}

class AccessibilityDialogFragment : ComposeDialogFragment() {
    /** Shared by [Content] and [Actions], which compose as siblings and so cannot remember it between them. */
    private var step by mutableStateOf("enable")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getString("step") ?: arguments?.getString("step") ?: "enable"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("step", step)
    }

    @Composable override fun Content() {
        val context = requireContext()
        Text(stringResource(R.string.dialog_adb_pairing_title), style = MaterialTheme.typography.headlineSmall)
        when (step) {
            "permission" -> Text(TextUtils.expandTemplate(stringResource(R.string.dialog_adb_pairing_accessibility_permission),
                "ACCESS_RESTRICTED_SETTINGS", "adb shell cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow").toString())
            "enable" -> Text(stringResource(R.string.dialog_adb_pairing_accessibility_enable))
            "navigate" -> Text(stringResource(R.string.dialog_adb_pairing_accessibility_navigate))
        }
    }

    @Composable override fun Actions() {
        val context = requireContext()
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            when (step) {
                "permission" -> TextButton(onClick = { step = "enable" }) { Text(stringResource(android.R.string.ok)) }
                "enable" -> TextButton(onClick = { SettingsPage.Accessibility.launch(context); dismissAllowingStateLoss() }) { Text(stringResource(R.string.enable)) }
                "navigate" -> TextButton(onClick = { SettingsPage.Developer.HighlightWirelessDebugging.launch(context); dismissAllowingStateLoss() }) { Text(stringResource(R.string.development_settings)) }
            }
            TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}

private fun Context.getEnabledAccessibilityServices(): List<String>? {
    val enabledServices =
        Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    return enabledServices?.split(":")
}

internal fun Context.isAccessibilityEnabled(): Boolean {
    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    return getEnabledAccessibilityServices()?.any { it.equals(accessibilityServiceName) } ?: false
}

private fun Context.enableAccessibilityService(): Boolean {
    if (isAccessibilityEnabled()) return true

    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    val enabledServices = getEnabledAccessibilityServices()
    val newServices =
        if (enabledServices.isNullOrEmpty()) {
            accessibilityServiceName
        } else {
            enabledServices.joinToString(":") + ":$accessibilityServiceName"
        }

    Settings.Secure.putString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        newServices,
    )

    return isAccessibilityEnabled()
}
