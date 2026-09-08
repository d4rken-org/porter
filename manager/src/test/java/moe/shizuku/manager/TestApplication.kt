package moe.shizuku.manager

import android.app.Application

/** Settings-only application for Robolectric; the production application loads native code and starts services. */
class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuSettings.initialize(this)
    }
}
