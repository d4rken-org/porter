package eu.darken.porter.manager.legacy

import android.os.Bundle
import android.widget.Toast
import eu.darken.porter.manager.app.AppActivity
import eu.darken.porter.manager.shell.ShellBinderRequestHandler

class ShellRequestHandlerActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ShellBinderRequestHandler.handleRequest(this, intent)
        finish()
    }
}
