package moe.shizuku.manager.settings

import androidx.fragment.app.Fragment
import moe.shizuku.manager.app.AppBarFragmentActivity

class SettingsActivity : AppBarFragmentActivity() {

    override fun createFragment(): Fragment = SettingsFragment()
}
