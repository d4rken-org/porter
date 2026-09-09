package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import moe.shizuku.manager.R

internal class TvPairingOverlay(private val service: AccessibilityService) {
    private val context = service.tvPairingUiContext()
    private val windows = service.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var result: TextView? = null
    private var completion: TextView? = null
    val isVisible: Boolean get() = panel?.visibility == View.VISIBLE
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun show() {
        if (panel != null) return
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 34, 40))
                cornerRadius = dp(20).toFloat()
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun line(text: String, title: Boolean = false) = TextView(context).apply {
            this.text = text
            textSize = if (title) 22f else 18f
            setTextColor(Color.WHITE)
            if (title) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
            view.addView(this)
        }
        line(context.getString(R.string.porter_tv_pairing_progress_title), title = true)
        line(context.getString(R.string.porter_tv_pairing_wait))
        result = line("").apply { visibility = View.GONE }
        completion = line("").apply { visibility = View.GONE }
        val params = WindowManager.LayoutParams(
            dp(560).coerceAtMost(service.resources.displayMetrics.widthPixels - dp(64)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Placement follows TV Settings; Porter's selected language can have a different direction.
            gravity = Gravity.BOTTOM or Gravity.getAbsoluteGravity(
                Gravity.START, Resources.getSystem().configuration.layoutDirection,
            )
            x = dp(24)
            y = dp(24)
            setTitle("Porter pairing progress")
        }
        windows.addView(view, params)
        panel = view
    }

    fun showResult(success: Boolean) {
        result?.apply {
            setText(if (success) R.string.notification_adb_pairing_succeed_title else R.string.notification_adb_pairing_failed_title)
            visibility = View.VISIBLE
        }
        completion?.apply {
            setText(R.string.porter_tv_pairing_return)
            visibility = View.VISIBLE
        }
    }

    fun dismiss() {
        val view = panel ?: return
        panel = null
        result = null
        completion = null
        try {
            windows.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            Log.w("TvPairingOverlay", "Overlay already removed", e)
        }
    }

    fun setVisible(visible: Boolean) {
        panel?.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

internal fun Context.tvPairingUiContext(): Context {
    if (Build.VERSION.SDK_INT < 33) return this
    val locales = getSystemService(LocaleManager::class.java).applicationLocales
    if (locales.isEmpty) return this
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocales(locales) })
}
