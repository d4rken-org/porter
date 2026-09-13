package moe.shizuku.manager.legacy

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*

class LegacyIsNotSupportedActivity : ComposeActivity() {
    override val edgeToEdge = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val callingComponent = callingActivity ?: run { setResult(RESULT_CANCELED); finish(); return }
        val ai = runCatching { packageManager.getApplicationInfo(callingComponent.packageName, PackageManager.GET_META_DATA) }.getOrElse { finish(); return }
        val label = runCatching { ai.loadLabel(packageManager).toString() }.getOrDefault(ai.packageName)
        val v3 = ai.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") == true
        val done = { setResult(1); finish() }
        porterContent {
            BackHandler {}
            DialogSurface(actions = {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = done) { Text(stringResource(android.R.string.ok)) }
                    if (v3) TextButton(onClick = { startActivity(Intent(this@LegacyIsNotSupportedActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); done() }) {
                        Text(stringResource(R.string.dialog_requesting_legacy_button_open_shizuku))
                    }
                }
            }) {
                Text(stringResource(if (v3) R.string.dialog_requesting_legacy_title else R.string.dialog_legacy_not_support_title, label), style = MaterialTheme.typography.headlineSmall)
                HtmlText(stringResource(if (v3) R.string.dialog_requesting_legacy_message else R.string.dialog_legacy_not_support_message, TextUtils.htmlEncode(label)))
            }
        }
    }
}
