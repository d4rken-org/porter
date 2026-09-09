// For IDE design previews, open ScreenshotContent.kt instead.
package moe.shizuku.manager.screenshots

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// Function names must stay free of underscores: copy_screenshots.sh splits the rendered file name
// at the first underscore to separate the function name from the locale.
//
// Six shots per locale. copy_screenshots.sh maps each name to its fastlane order and knows the
// per-form-factor count; generate_screenshots.sh knows the total. All three have to agree.

@PreviewTest
@PlayStoreLocales
@Composable
fun HomeRunning() = HomeRunningContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun Apps() = AppsContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun HomeCompatibility() = HomeCompatibilityContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun HomeSetup() = HomeSetupContent()

@PreviewTest
@PlayStoreLocalesDark
@Composable
fun Settings() = SettingsContent()

@PreviewTest
@PlayStoreLocalesDark
@Composable
fun AppsDark() = AppsDarkContent()
