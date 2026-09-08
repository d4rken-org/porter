package moe.shizuku.manager

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Native text measurement for Compose content, state and click behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()
}
