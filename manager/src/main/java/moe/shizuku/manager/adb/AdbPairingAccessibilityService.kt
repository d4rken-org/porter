package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.utils.EnvironmentUtils

class AdbPairingAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pairing = false
    private var finished = false
    private var overlay: TvPairingOverlay? = null
    private val returnToPorter = Runnable {
        dismissOverlay()
        openPorter()
        disableSelf()
    }
    private val visibilityPoll = object : Runnable {
        override fun run() {
            val panel = overlay ?: return
            rootInActiveWindow?.packageName?.let { panel.setVisible(it == TV_SETTINGS_PACKAGE) }
            handler.postDelayed(this, 1_000)
        }
    }
    internal var pair: suspend (String, Int, String) -> Unit = ::pairAdb
    private val timeout = Runnable {
        finishPairing(false, tvPairingUiContext().getString(R.string.porter_pairing_search_timeout))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!(EnvironmentUtils.isTelevision() && EnvironmentUtils.isTlsSupported())) {
            Toast.makeText(tvPairingUiContext(), R.string.toast_accessibility_tv_only, Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }
        startPairing()
    }

    internal fun startPairing() {
        handler.removeCallbacks(returnToPorter)
        dismissOverlay()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pairing = false
        finished = false
        TvPairingResultStore(ShizukuSettings.getPreferences()).clear()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 60_000)
        openPorter(showInstructions = true)
    }

    private fun openPorter(showInstructions: Boolean = false) {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, showInstructions)
            })
        } catch (e: Exception) {
            Log.w("AdbPairingAccessibility", "Cannot open Porter", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || pairing || finished) return
        val root = rootInActiveWindow ?: event.source ?: return
        if (root.packageName != TV_SETTINGS_PACKAGE) return
        val credentials = readPairingCredentials(root)
        if (credentials != null || isPairingCodeWindow(root)) showOverlay()
        if (credentials == null) return
        pairing = true
        handler.removeCallbacks(timeout)
        scope.launch {
            try {
                pair(credentials.host, credentials.port, credentials.code)
                coroutineContext.ensureActive()
                finishPairing(true, tvPairingUiContext().getString(R.string.notification_adb_pairing_succeed_text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                Log.w("AdbPairingAccessibility", "Pairing failed", e)
                finishPairing(false, tvPairingUiContext().pairingFailureMessage(e))
            }
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        try {
            overlay = TvPairingOverlay(this).also { it.show() }
            handler.postDelayed(visibilityPoll, 1_000)
        } catch (e: Exception) {
            Log.w("AdbPairingAccessibility", "Cannot show pairing progress", e)
            dismissOverlay()
        }
    }

    private fun finishPairing(success: Boolean, message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        TvPairingResultStore(ShizukuSettings.getPreferences()).save(TvPairingResult(success, message))
        if (overlay?.isVisible == true) {
            overlay?.showResult(success)
            handler.postDelayed(returnToPorter, 2_500)
        } else {
            returnToPorter.run()
        }
    }

    private fun dismissOverlay() {
        handler.removeCallbacks(visibilityPoll)
        overlay?.dismiss()
        overlay = null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        dismissOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        dismissOverlay()
        super.onDestroy()
    }
}

internal data class PairingCredentials(val host: String, val port: Int, val code: String)

internal const val TV_SETTINGS_PACKAGE = "com.android.tv.settings"

internal fun isPairingCodeWindow(root: AccessibilityNodeInfo): Boolean {
    if (root.viewIdResourceName == "$TV_SETTINGS_PACKAGE:id/pairing_code") return true
    for (index in 0 until root.childCount) {
        if (root.getChild(index)?.let(::isPairingCodeWindow) == true) return true
    }
    return false
}

internal fun readPairingCredentials(root: AccessibilityNodeInfo): PairingCredentials? {
    var endpoint: Pair<String, Int>? = null
    var code: String? = null
    val address = Regex("((?:[0-9]{1,3}\\.){3}[0-9]{1,3}):([0-9]{2,5})")
    fun visit(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()?.trim().orEmpty()
        address.matchEntire(text)?.let { match ->
            val port = match.groupValues[2].toIntOrNull()
            if (port != null && port in 1..65535) endpoint = match.groupValues[1] to port
        }
        if (text.matches(Regex("[0-9]{6}"))) code = text
        for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it) }
    }
    visit(root)
    val (host, port) = endpoint ?: return null
    return PairingCredentials(host, port, code ?: return null)
}
