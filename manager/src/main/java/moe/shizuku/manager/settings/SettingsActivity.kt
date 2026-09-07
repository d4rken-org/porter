package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import moe.shizuku.manager.app.AppBarFragmentActivity

class SettingsActivity : AppBarFragmentActivity() {

    override fun createFragment(): Fragment = SettingsFragment().apply {
        arguments = Bundle().apply {
            putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,
                intent.getStringExtra(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT))
        }
    }
}
