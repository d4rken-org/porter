package moe.shizuku.manager.app

import android.content.Context
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import moe.shizuku.manager.R
import rikka.core.res.isNight
import rikka.core.res.resolveColor
import rikka.material.app.MaterialActivity
import rikka.material.app.LocaleDelegate

abstract class AppActivity : MaterialActivity() {

    override fun attachBaseContext(newBase: Context) {
        LocaleDelegate.defaultLocale = Resources.getSystem().configuration.locales[0]
        super.attachBaseContext(newBase)
    }

    override fun onResume() {
        LocaleDelegate.defaultLocale = Resources.getSystem().configuration.locales[0]
        super.onResume()
    }

    override fun computeUserThemeKey(): String {
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor()
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        } else {
            theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
} 
