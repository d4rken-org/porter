package eu.darken.porter.manager.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.launch

abstract class ComposeDialogFragment : DialogFragment() {
    @Composable abstract fun Content()
    @Composable open fun Actions() {}
    /** See [DialogSurface]'s `dpadScroll`. */
    open val dpadScroll: Boolean = false
    /** See [DialogSurface]'s `scrollKey`. Read during composition, so a state-backed value takes effect. */
    open val scrollKey: Any? get() = null
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = ComponentDialog(requireContext()).apply {
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCanceledOnTouchOutside(false)
        setContentView(ComposeView(context).apply {
            setContent {
                PorterTheme {
                    DialogSurface(dpadScroll = dpadScroll, scrollKey = scrollKey, actions = { this@ComposeDialogFragment.Actions() }) {
                        this@ComposeDialogFragment.Content()
                    }
                }
            }
        })
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout((resources.displayMetrics.widthPixels - 48 * resources.displayMetrics.density).toInt().coerceAtMost((560 * resources.displayMetrics.density).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    fun show(fragmentManager: FragmentManager) {
        if (!fragmentManager.isStateSaved && fragmentManager.findFragmentByTag(javaClass.simpleName) == null) show(fragmentManager, javaClass.simpleName)
    }
}

/**
 * @param dpadScroll lets content taller than the dialog take D-pad focus and scroll itself. Only for text: while it
 * has focus, Down scrolls instead of moving into interactive children.
 * @param scrollKey content that changes in place starts again at the top whenever this changes.
 */
@Composable
fun DialogSurface(
    dpadScroll: Boolean = false,
    scrollKey: Any? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = key(scrollKey) { rememberScrollState() }
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 64.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp)) {
            Column(
                Modifier.weight(1f, fill = false)
                    // At a boundary the key stays unhandled for normal focus navigation.
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { event ->
                        if (!focused || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val delta = when {
                            event.key == Key.DirectionDown && scroll.canScrollForward -> step
                            event.key == Key.DirectionUp && scroll.canScrollBackward -> -step
                            else -> return@onKeyEvent false
                        }
                        scope.launch { scroll.animateScrollBy(delta) }
                        true
                    }
                    .focusable(enabled = dpadScroll && scroll.maxValue > 0)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            actions()
        }
    }
}
