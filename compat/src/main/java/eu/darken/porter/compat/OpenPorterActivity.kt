package eu.darken.porter.compat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast

class OpenPorterActivity : Activity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val launch = if (PorterIdentity.isInstalled(this)) packageManager.getLaunchIntentForPackage(PorterIdentity.PACKAGE) else null
        if (launch != null) {
            try {
                startActivity(launch)
                finish()
                return
            } catch (ignored: ActivityNotFoundException) {
            }
        }
        Toast.makeText(this, R.string.porter_missing, Toast.LENGTH_LONG).show()
        finish()
    }
}
