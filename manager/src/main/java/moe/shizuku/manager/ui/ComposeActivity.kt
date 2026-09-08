package moe.shizuku.manager.ui

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import moe.shizuku.manager.app.AppActivity

abstract class ComposeActivity : AppActivity() {
    protected open val protectTouches = false
    protected open val rejectPartialTouches = false
    protected open val edgeToEdge = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (edgeToEdge) enableEdgeToEdge()
    }

    protected fun porterContent(content: @Composable () -> Unit) {
        setContent { PorterTheme(content) }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val mask = MotionEvent.FLAG_WINDOW_IS_OBSCURED or if (rejectPartialTouches) MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED else 0
        if (protectTouches && event.flags and mask != 0) return true
        return super.dispatchTouchEvent(event)
    }
}
