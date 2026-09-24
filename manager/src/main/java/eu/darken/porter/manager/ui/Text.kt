package eu.darken.porter.manager.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.core.text.HtmlCompat

fun plainText(html: String): String = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val parsed = AnnotatedString.fromHtml(html)
    Text(parsed.subSequence(0, parsed.text.trimEnd().length), modifier, style = MaterialTheme.typography.bodyMedium)
}

fun copyText(context: Context, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText("Porter", text)
    if (sensitive) {
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
        clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
