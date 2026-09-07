package moe.shizuku.manager.support

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.launch
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarFragmentActivity
import moe.shizuku.manager.utils.CustomTabsHelper

class SupportActivity : AppBarFragmentActivity() {
    override fun createFragment(): Fragment = SupportFragment()
}

class SupportFragment : SupportPreferences() {
    private lateinit var recording: Preference
    private lateinit var sessions: Preference
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val help = category(R.string.porter_support_title)
        link(help, R.string.porter_documentation, Helps.WEBSITE)
        link(help, R.string.porter_issue_tracker, Helps.SOURCE + "/issues")
        link(help, R.string.porter_discord, "https://discord.gg/5hXXgwKNgm")
        help.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.porter_contact)
            setSummary(R.string.porter_contact_summary)
            setOnPreferenceClickListener {
                startActivity(android.content.Intent(context, ContactActivity::class.java)); true
            }
        })
        val debug = category(R.string.porter_debug_title)
        recording = Preference(requireContext()).apply {
            setOnPreferenceClickListener {
                RecordingDialogs.toggle(this@SupportFragment); true
            }
        }.also(debug::addPreference)
        sessions = Preference(requireContext()).apply {
            setTitle(R.string.porter_debug_saved)
            setOnPreferenceClickListener {
                startActivity(android.content.Intent(context, DebugLogsActivity::class.java)); true
            }
        }.also(debug::addPreference)
        debug.addPreference(Preference(requireContext()).apply {
            setSummary(R.string.porter_debug_storage)
            isSelectable = false
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DebugRecorder.state.collect { state ->
                    recording.setTitle(if (state.active) R.string.porter_debug_stop else R.string.porter_debug_start)
                    recording.summary = state.error ?: getString(if (state.active) R.string.porter_debug_recording_summary else R.string.porter_debug_start_summary)
                    val saved = DebugRecorder.sessions(requireContext()).filterNot { it.active }
                    sessions.summary = getString(R.string.porter_debug_count, saved.size)
                }
            }
        }
    }
    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        setTitle(title)
        preferenceScreen.addPreference(this)
    }
    private fun link(category: PreferenceCategory, title: Int, url: String) {
        category.addPreference(Preference(requireContext()).apply {
            setTitle(title)
            setOnPreferenceClickListener { CustomTabsHelper.launchUrlOrCopy(context, url); true }
        })
    }
}
