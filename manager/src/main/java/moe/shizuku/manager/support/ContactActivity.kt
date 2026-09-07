package moe.shizuku.manager.support

import android.content.ComponentName
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarFragmentActivity
import moe.shizuku.manager.databinding.SupportContactBinding
import java.util.Date

class ContactActivity : AppBarFragmentActivity() {
    override fun createFragment(): Fragment = ContactFragment()
}

class ContactFragment : Fragment(R.layout.support_contact) {
    private var binding: SupportContactBinding? = null
    private var selectedId: String? = null
    private var recordedId: String? = null
    private var sessionIds: List<String?> = listOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString("attachment")
        recordedId = savedInstanceState?.getString("recorded")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("attachment", selectedId)
        outState.putString("recorded", recordedId)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = SupportContactBinding.bind(view).also { binding = it }
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            target.setPadding(safe.left, 0, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
        ui.contactCategory.onItemSelectedListener = selectionListener {
            ui.expectedLayout.isVisible = it == 0
        }
        ui.contactRecord.setOnClickListener { RecordingDialogs.toggle(this) }
        ui.contactSend.setOnClickListener { prepareEmail(ui) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DebugRecorder.state.collect { state ->
                    ui.contactRecord.setText(if (state.active) R.string.porter_debug_stop else R.string.porter_debug_start)
                    ui.contactSend.isEnabled = !state.active
                    val sessions = DebugRecorder.sessions(requireContext())
                    if (state.active) recordedId = sessions.firstOrNull { it.active }?.id
                    else recordedId?.let { id ->
                        if (sessions.any { it.id == id && !it.active }) selectedId = id
                        recordedId = null
                    }
                    val saved = sessions.filterNot { it.active }
                    sessionIds = listOf(null) + saved.map { it.id }
                    val labels = listOf(getString(R.string.porter_contact_no_attachment)) + saved.map {
                        DateFormat.getMediumDateFormat(requireContext()).format(Date(it.started)) + " " + DateFormat.getTimeFormat(requireContext()).format(Date(it.started))
                    }
                    ui.contactAttachment.onItemSelectedListener = null
                    ui.contactAttachment.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    ui.contactAttachment.setSelection(sessionIds.indexOf(selectedId).coerceAtLeast(0))
                    ui.contactAttachment.onItemSelectedListener = selectionListener { selectedId = sessionIds.getOrNull(it) }
                }
            }
        }
    }
    private fun prepareEmail(ui: SupportContactBinding) {
        val description = ui.contactDescription.text.toString().trim()
        val expected = ui.contactExpected.text.toString().trim()
        val bug = ui.contactCategory.selectedItemPosition == 0
        ui.descriptionLayout.error = if (description.length < 20) getString(R.string.porter_contact_description_error) else null
        ui.expectedLayout.error = if (bug && expected.isEmpty()) getString(R.string.porter_contact_expected_error) else null
        if (ui.descriptionLayout.error != null || ui.expectedLayout.error != null) return
        val category = arrayOf("Bug", "Question", "Feature request")[ui.contactCategory.selectedItemPosition]
        val attachment = selectedId
        ui.contactSend.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext()
                val uri = attachment?.let { id ->
                    val file = DebugRecorder.export(context, id)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                val intent = Intent(if (uri == null) Intent.ACTION_SENDTO else Intent.ACTION_SEND).apply {
                    if (uri == null) data = Uri.parse("mailto:support@darken.eu") else {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Porter debug log", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("support@darken.eu"))
                    putExtra(Intent.EXTRA_SUBJECT, "[Porter][$category] " + description.lineSequence().first().take(80))
                    putExtra(Intent.EXTRA_TEXT, buildString {
                        appendLine(description)
                        if (bug) appendLine("\nExpected behavior:\n$expected")
                        appendLine("\n${DebugRecorder.deviceDetails()}")
                    })
                }
                val mailtoApps = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:support@darken.eu")), 0,
                )
                val emailPackages = mailtoApps.map { it.activityInfo.packageName }.toSet()
                val handlers = if (uri == null) mailtoApps else context.packageManager.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName in emailPackages }
                val emailApps = handlers.map { result ->
                    Intent(intent).setComponent(ComponentName(result.activityInfo.packageName, result.activityInfo.name))
                }.distinctBy { it.component }
                if (emailApps.isEmpty()) throw android.content.ActivityNotFoundException(getString(R.string.porter_contact_no_email))
                startActivity(Intent.createChooser(emailApps.first(), getString(R.string.porter_contact_send)).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, emailApps.drop(1).toTypedArray())
                })
            } catch (e: Exception) { RecordingDialogs.error(this@ContactFragment, e) }
            finally { binding?.contactSend?.isEnabled = !DebugRecorder.state.value.active }
        }
    }
    private fun selectionListener(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
