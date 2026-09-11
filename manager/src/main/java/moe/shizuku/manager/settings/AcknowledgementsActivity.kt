package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.ComposeActivity
import moe.shizuku.manager.ui.MessageDialog
import moe.shizuku.manager.ui.PorterScaffold
import moe.shizuku.manager.ui.SettingsCategory
import moe.shizuku.manager.ui.SettingsItem
import moe.shizuku.manager.utils.CustomTabsHelper

class AcknowledgementsActivity : ComposeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { AcknowledgementsScreen() }
    }

    @Composable
    private fun AcknowledgementsScreen() {
        var licenseFile by rememberSaveable { mutableStateOf<String?>(null) }
        PorterScaffold(stringResource(R.string.porter_acknowledgements), onBack = { finish() }) { padding ->
            LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding)) {
                item { SettingsCategory(stringResource(R.string.porter_acknowledgements_thanks)) }
                items(credits) { credit ->
                    SettingsItem(credit.name, Icons.TwoTone.Favorite, stringResource(credit.description),
                        onClick = { CustomTabsHelper.launchUrlOrCopy(this@AcknowledgementsActivity, credit.url) })
                }
                item { SettingsCategory(stringResource(R.string.porter_licenses)) }
                item {
                    SettingsItem("Porter", Icons.TwoTone.Code, stringResource(R.string.porter_license_porter),
                        onClick = { licenseFile = "porter.txt" })
                }
                item {
                    SettingsItem("Shizuku API", Icons.TwoTone.Code, stringResource(R.string.porter_license_api),
                        onClick = { licenseFile = "shizuku-api.txt" })
                }
                item {
                    SettingsItem("Porter", Icons.TwoTone.Info, stringResource(R.string.porter_license_notice),
                        onClick = { licenseFile = "notice.txt" })
                }
                items(libraries) { library ->
                    SettingsItem(library.name, Icons.TwoTone.Code, library.license,
                        onClick = { CustomTabsHelper.launchUrlOrCopy(this@AcknowledgementsActivity, library.url) })
                }
            }
        }
        licenseFile?.let { file ->
            val text = remember(file) { assets.open("licenses/$file").bufferedReader().use { it.readText() } }
            MessageDialog(stringResource(R.string.porter_licenses), text, onDismiss = { licenseFile = null })
        }
    }

    private data class Credit(val name: String, val description: Int, val url: String)
    private data class Library(val name: String, val license: String, val url: String)

    companion object {
        private val credits = listOf(
            Credit("Shizuku · RikkaApps", R.string.porter_ack_shizuku, "https://github.com/RikkaApps/Shizuku"),
            Credit("Shizuku · thedjchi", R.string.porter_ack_thedjchi, "https://github.com/thedjchi/Shizuku"),
            Credit("Shizuku · symbuzzer", R.string.porter_ack_symbuzzer, "https://github.com/symbuzzer/fork-Shizuku"),
            Credit("ShizukuPlus · thejaustin", R.string.porter_ack_plus, "https://github.com/thejaustin/ShizukuPlus"),
            Credit("Max Patchs", R.string.porter_ack_maxpatchs, "https://x.com/maxpatchs"),
        )
        private val libraries = listOf(
            Library("AndroidX & Jetpack Compose", "Apache License 2.0", "https://source.android.com/docs/setup/about/licenses"),
            Library("Material Icons", "Apache License 2.0", "https://github.com/google/material-design-icons/blob/master/LICENSE"),
            Library("Kotlin", "Apache License 2.0", "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt"),
            Library("Kotlin Coroutines", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt"),
            Library("Kotlin Serialization", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt"),
            Library("RikkaX", "MIT License", "https://github.com/RikkaApps/RikkaX/blob/master/LICENSE"),
            Library("Hidden API", "MIT License", "https://github.com/RikkaW/HiddenApi/blob/master/LICENSE"),
            Library("Hidden API Refine", "MIT License", "https://github.com/RikkaApps/HiddenApiRefinePlugin/blob/main/LICENSE"),
            Library("Hidden API Bypass", "Apache License 2.0", "https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE"),
            Library("libsu", "Apache License 2.0", "https://github.com/topjohnwu/libsu/blob/master/LICENSE"),
            Library("OkHttp", "Apache License 2.0", "https://github.com/square/okhttp/blob/master/LICENSE.txt"),
            Library("Okio", "Apache License 2.0", "https://github.com/square/okio/blob/master/LICENSE.txt"),
            Library("Gson", "Apache License 2.0", "https://github.com/google/gson/blob/main/LICENSE"),
            Library("Bouncy Castle", "Bouncy Castle License (MIT)", "https://www.bouncycastle.org/about/license/"),
            Library("BoringSSL", "ISC / OpenSSL", "https://boringssl.googlesource.com/boringssl/+/refs/heads/main/LICENSE"),
            Library("LLVM libc++", "Apache License 2.0 with LLVM Exceptions", "https://github.com/llvm/llvm-project/blob/main/libcxx/LICENSE.TXT"),
        )
    }
}
