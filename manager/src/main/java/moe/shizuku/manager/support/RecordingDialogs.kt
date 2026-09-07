package moe.shizuku.manager.support

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.shizuku.manager.R

object RecordingDialogs {
    fun toggle(fragment: Fragment) {
        RecordingConsentDialog().show(fragment.parentFragmentManager, "recording")
    }
    fun error(fragment: Fragment, e: Exception) {
        if (e is CancellationException) throw e
        if (fragment.isAdded) MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.porter_support_error).setMessage(e.localizedMessage ?: e.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null).show()
    }
}

class RecordingConsentDialog : DialogFragment() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { perform(false) }
    override fun onCreateDialog(savedInstanceState: android.os.Bundle?): android.app.Dialog {
        val state = DebugRecorder.state.value
        val short = state.active && System.currentTimeMillis() - state.started < 15_000
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (state.active) R.string.porter_debug_stop else R.string.porter_debug_start)
            .setMessage(when {
                short -> R.string.porter_debug_short
                state.active -> R.string.porter_debug_stop_message
                else -> R.string.porter_debug_consent
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (state.active) R.string.porter_debug_stop else R.string.porter_debug_start, null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        if (!state.active && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else perform(state.active)
                    }
                }
            }
    }
    private fun perform(stop: Boolean) {
        val context = requireContext().applicationContext
        val button = (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_POSITIVE)
        button?.isEnabled = false
        isCancelable = false
        lifecycleScope.launch {
            try {
                if (stop) DebugRecorder.stop(context) else DebugRecorder.start(context)
                Toast.makeText(context, if (stop) R.string.porter_debug_finished else R.string.porter_debug_recording, Toast.LENGTH_LONG).show()
                dismiss()
            } catch (e: Exception) {
                RecordingDialogs.error(this@RecordingConsentDialog, e)
                isCancelable = true
                button?.isEnabled = true
            }
        }
    }

}
