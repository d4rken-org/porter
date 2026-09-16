package eu.darken.porter.manager

import android.app.Application

/** Settings-only application for Robolectric; the production application loads native code and starts services. */
class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PorterSettings.initialize(this)
    }
}
