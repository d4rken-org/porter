package moe.shizuku.manager.settings

import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.preference.Preference.SummaryProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*
import moe.shizuku.manager.app.SnackbarHelper
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.receiver.NotifCancelReceiver
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.util.ResourceUtils
import rikka.html.text.HtmlCompat
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var watchdogPreference: TwoStatePreference
    private lateinit var tcpPortPreference: EditTextPreference
    private lateinit var nightModePreference: IntegerSimpleMenuPreference

    private lateinit var batteryOptimizationListener: ActivityResultLauncher<Intent>
    private var batteryOptimizationContinuation: CancellableContinuation<Boolean>? = null

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isRunning()) {
            tcpPortPreference.icon = maybeGetRestartIcon(KEY_TCP_PORT)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        startOnBootPreference = findPreference(KEY_START_ON_BOOT)!!
        watchdogPreference = findPreference(KEY_WATCHDOG)!!
        tcpPortPreference = findPreference(KEY_TCP_PORT)!!
        nightModePreference = findPreference(KEY_NIGHT_MODE)!!

        batteryOptimizationListener = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val accepted = SettingsHelper.isIgnoringBatteryOptimizations(requireContext())
            batteryOptimizationContinuation?.resume(accepted)
        }

        startOnBootPreference.apply {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                EnvironmentUtils.isTelevision() ||
                EnvironmentUtils.isRooted()
            ) {
                isChecked = ShizukuSettings.getStartOnBoot(context)

                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val doToggle = {
                            maybeToggleBatterySensitiveSetting(newValue) { result ->
                                if (result) {
                                    ShizukuSettings.setStartOnBoot(context, newValue)
                                    isChecked = ShizukuSettings.getStartOnBoot(context)
                                }
                            }
                        }

                        // https://r.android.com/2128832
                        if (
                            newValue &&
                            !EnvironmentUtils.isTelevision() &&
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        ) {
                            MaterialAlertDialogBuilder(context)
                                .setTitle(android.R.string.dialog_alert_title)
                                .setMessage(R.string.settings_start_on_boot_bug)
                                .setPositiveButton(android.R.string.ok) { _, _ -> doToggle() }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> isChecked = !newValue }
                                .show()
                        } else { doToggle() }
                    }
                    false
                }
            } else {
                isEnabled = false
                isChecked = false
                summary = context.getString(R.string.settings_start_on_boot_summary)
            }
        }

        watchdogPreference.apply {
            isChecked = ShizukuSettings.isWatchdogRunning()

            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleBatterySensitiveSetting(newValue) { result ->
                        if (result) {
                            ShizukuSettings.setWatchdog(context, newValue)
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        tcpPortPreference.apply {
            // Only read on TVs without TLS pairing; everywhere else the port comes from adbd itself.
            isVisible = EnvironmentUtils.isTelevision() && !EnvironmentUtils.isTlsSupported()
            icon = maybeGetRestartIcon(KEY_TCP_PORT)

            setOnBindEditTextListener { editText ->
                editText.hint = context.getString(R.string.settings_tcp_port_hint)
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }

            summaryProvider = SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrEmpty()) context.getString(R.string.settings_tcp_port_default) else text
            }

            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                if (port == null || port in 1..65535) {
                    val applyChange: () -> Unit = {
                        ShizukuSettings.setTcpPort(port)
                        text = port?.toString()
                        icon = maybeGetRestartIcon(KEY_TCP_PORT)
                    }
                    maybePromptRestart (KEY_TCP_PORT, port ?: 5555) { applyChange() }
                } else {
                    SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
                }
                false
            }
        }

        nightModePreference.apply {
            value = ShizukuSettings.getNightMode()
            setOnPreferenceChangeListener { _, value ->
                if (value is Int) {
                    ShizukuSettings.getPreferences().edit().putInt(KEY_NIGHT_MODE, value).apply()
                    AppCompatDelegate.setDefaultNightMode(value)
                }
                true
            }
        }

        val style = findPreference<ListPreference>(KEY_THEME_STYLE)!!
        val color = findPreference<ListPreference>(KEY_THEME_COLOR)!!
        if (Build.VERSION.SDK_INT < 31) {
            val supported = style.entryValues.indices.filter { style.entryValues[it] != "MATERIAL_YOU" }
            style.entries = supported.map { style.entries[it] }.toTypedArray()
            style.entryValues = supported.map { style.entryValues[it] }.toTypedArray()
            if (ThemeHelper.getThemeStyle() == "MATERIAL_YOU") {
                ShizukuSettings.getPreferences().edit().putString(KEY_THEME_STYLE, "DEFAULT").apply()
            }
        }
        style.value = ThemeHelper.getThemeStyle()
        color.value = ThemeHelper.getThemeColor()
        color.isEnabled = !ThemeHelper.isUsingSystemColor()
        if (!color.isEnabled) {
            color.summaryProvider = null
            color.summary = getString(R.string.porter_theme_color_system)
        }
        listOf(style, color).forEach { preference ->
            preference.setOnPreferenceChangeListener { _, value ->
                ShizukuSettings.getPreferences().edit().putString(preference.key, value as String).apply()
                activity?.recreate()
                true
            }
        }

        findPreference<Preference>("terminal")!!.setOnPreferenceClickListener {
            startActivity(Intent(context, moe.shizuku.manager.shell.ShellTutorialActivity::class.java))
            true
        }
        findPreference<Preference>("automation")!!.setOnPreferenceClickListener {
            AutomationDialog.show(context)
            true
        }
        findPreference<Preference>("developer_guide")!!.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, Helps.HOME.get())
            true
        }
        findPreference<Preference>("support")!!.setOnPreferenceClickListener {
            startActivity(Intent(context, moe.shizuku.manager.support.SupportActivity::class.java))
            true
        }
        findPreference<Preference>("version")!!.apply {
            summary = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            setOnPreferenceClickListener {
                CustomTabsHelper.launchUrlOrCopy(context, Helps.DOWNLOAD.get())
                true
            }
        }

        findPreference<Preference>("pairing_method")!!.apply {
            isVisible = !EnvironmentUtils.isTelevision() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val choices = resources.getStringArray(R.array.porter_pairing_methods)
            summary = choices[if (ShizukuSettings.getLegacyPairing()) 1 else 0]
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.porter_pairing_method)
                    .setSingleChoiceItems(choices, if (ShizukuSettings.getLegacyPairing()) 1 else 0) { dialog, which ->
                        ShizukuSettings.getPreferences().edit().putBoolean(KEY_LEGACY_PAIRING, which == 1).apply()
                        summary = choices[which]
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        listOf("appearance", "startup", "tools", "support", "version").forEach { key ->
            findPreference<Preference>(key)!!.apply { icon = tint(icon) }
        }

        arguments?.getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT)?.let { key ->
            preferenceScreen = findPreference<PreferenceScreen>(key)!!
        }
    }

    override fun onNavigateToScreen(preferenceScreen: PreferenceScreen) {
        startActivity(Intent(requireContext(), SettingsActivity::class.java)
            .putExtra(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, preferenceScreen.key))
    }

    override fun onResume() {
        super.onResume()
        activity?.title = preferenceScreen.title ?: getString(R.string.settings_title)
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onPause() {
        ShizukuStateMachine.removeListener(stateListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            KEY_WATCHDOG -> watchdogPreference.isChecked = ShizukuSettings.isWatchdogRunning()
        }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val systemBarsInsets = insets.getInsets(Type.systemBars() or Type.displayCutout())
            recyclerView.addItemSpacing(
                left = systemBarsInsets.left.toFloat(),
                right = systemBarsInsets.right.toFloat()
            )
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                systemBarsInsets.bottom
            )
            insets
        }

        recyclerView.fixEdgeEffect()

        return recyclerView
    }

    private fun needsRestart(setting: String, newValue: Any? = null): Boolean {
        val currentPort = EnvironmentUtils.getAdbTcpPort()
        return when (setting) {
            KEY_TCP_PORT -> {
                val newPort = newValue as? Int ?: ShizukuSettings.getTcpPort()
                (currentPort > 0) && (currentPort != newPort)
            }
            else -> false
        }
    }

    private fun maybeGetRestartIcon(setting: String): Drawable? {
        val context = requireContext()
        if (!needsRestart(setting)) return null
        
        val icon = context.getDrawable(R.drawable.ic_server_restart)
        return tint(icon)
    }

    private fun tint(icon: Drawable?): Drawable? {
        val context = requireContext()
        val tintColor = TypedValue()
        context.theme.resolveAttribute(R.attr.colorOnSurfaceVariant, tintColor, true)
        icon?.mutate()?.setTint(tintColor.data)
        return icon
    }

    private fun maybePromptRestart (setting: String, newValue: Any? = null, applyChange: () -> Unit) {
        val context = requireContext()
        if (!ShizukuStateMachine.isRunning() || !needsRestart(setting, newValue)) {
            applyChange()
            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))
        } else {
            val message = context.getString(R.string.settings_restart_dialog_message)

            MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_restart_dialog_title)
            .setMessage(HtmlCompat.fromHtml(message))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyChange()
                ShizukuReceiverStarter.start(context, true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
    }

    private fun maybeToggleBatterySensitiveSetting (
        newValue: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val context = requireContext()
        if (!newValue || SettingsHelper.isIgnoringBatteryOptimizations(context) || EnvironmentUtils.isTelevision()) {
            onResult(true)
            return
        }
            
        lifecycleScope.launch {
            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                batteryOptimizationContinuation = continuation
                SnackbarHelper.show(
                    context,
                    requireView(),
                    msg = context.getString(R.string.snackbar_battery_optimization_settings),
                    duration = 6000,
                    actionText = context.getString(R.string.snackbar_action_fix),
                    action = { SettingsHelper.requestIgnoreBatteryOptimizations(context, batteryOptimizationListener) },
                    onDismiss = { event ->
                        if (event != Snackbar.Callback.DISMISS_EVENT_ACTION && continuation.isActive)
                            continuation.resume(false)
                    }
                )
            }
            onResult(result)
        }
    }

}
