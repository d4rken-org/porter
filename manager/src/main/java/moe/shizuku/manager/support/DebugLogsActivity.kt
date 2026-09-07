package moe.shizuku.manager.support

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarFragmentActivity
import java.io.File
import java.util.Date

class DebugLogsActivity : AppBarFragmentActivity() {
    override fun createFragment(): Fragment = DebugLogsFragment()
}

internal fun Fragment.shareLog(file: File) {
    val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Porter debug log", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, getString(R.string.porter_debug_share)))
}

class DebugLogsFragment : SupportPreferences() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DebugRecorder.state.collect { refresh() }
            }
        }
    }
    private fun refresh() {
        lifecycleScope.launch {
            try {
                val sessions = DebugRecorder.sessions(requireContext())
                preferenceScreen.removeAll()
                if (sessions.isEmpty()) preferenceScreen.addPreference(Preference(requireContext()).apply {
                    setTitle(R.string.porter_debug_empty)
                    isSelectable = false
                })
                sessions.forEach { session ->
                    preferenceScreen.addPreference(Preference(requireContext()).apply {
                        title = DateFormat.getMediumDateFormat(context).format(Date(session.started)) + " " + DateFormat.getTimeFormat(context).format(Date(session.started))
                        summary = if (session.active) getString(R.string.porter_debug_recording) else Formatter.formatShortFileSize(context, session.size)
                        setOnPreferenceClickListener {
                            if (session.active) RecordingDialogs.toggle(this@DebugLogsFragment) else actions(session.id)
                            true
                        }
                    })
                }
            } catch (e: Exception) { RecordingDialogs.error(this@DebugLogsFragment, e) }
        }
    }
    private fun actions(id: String) {
        MaterialAlertDialogBuilder(requireContext()).setItems(arrayOf(
            getString(R.string.porter_debug_share), getString(R.string.porter_debug_delete),
        )) { _, which ->
            if (which == 0) lifecycleScope.launch {
                try { shareLog(DebugRecorder.export(requireContext(), id)) }
                catch (e: Exception) { RecordingDialogs.error(this@DebugLogsFragment, e) }
            } else MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.porter_debug_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.porter_debug_delete) { _, _ ->
                    lifecycleScope.launch {
                        try { DebugRecorder.delete(requireContext(), id); refresh() }
                        catch (e: Exception) { RecordingDialogs.error(this@DebugLogsFragment, e) }
                    }
                }.show()
        }.show()
    }
}
