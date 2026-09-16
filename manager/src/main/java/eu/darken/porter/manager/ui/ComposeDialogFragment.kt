package eu.darken.porter.manager.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

abstract class ComposeDialogFragment : DialogFragment() {
    @Composable abstract fun Content()
    @Composable open fun Actions() {}
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = ComponentDialog(requireContext()).apply {
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCanceledOnTouchOutside(false)
        setContentView(ComposeView(context).apply {
            setContent {
                PorterTheme {
                    DialogSurface(actions = { this@ComposeDialogFragment.Actions() }) { this@ComposeDialogFragment.Content() }
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

@Composable
fun DialogSurface(
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            actions()
        }
    }
}
