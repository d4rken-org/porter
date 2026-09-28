package eu.darken.porter.manager.home

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.PorterTheme
import eu.darken.porter.manager.updater.UpdateInstaller.Operation
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDownloadDialogTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun option(title: Int) = composeTestRule.onNode(hasText(string(title)) and isSelectable())

    private fun showDialog(automaticAvailable: Boolean, confirmed: MutableList<UpdateAction> = mutableListOf()) {
        composeTestRule.setContent {
            PorterTheme(dark = false) {
                UpdateDownloadDialog("porter-0.7.0.apk", automaticAvailable, onConfirm = { confirmed += it }, onDismiss = {})
            }
        }
    }

    private fun showCard(operation: Operation) {
        composeTestRule.setContent {
            PorterTheme(dark = false) {
                UpdateCard(UpdateCardState("0.6.0", "0.7.0", null, downloadAvailable = true, operation = operation), {}, {}, {})
            }
        }
    }

    @Test fun theDialogOffersThreeOptionsWithDownloadSelected() {
        showDialog(automaticAvailable = true)
        composeTestRule.onNodeWithText(string(R.string.updater_dialog_title)).assertIsDisplayed()
        composeTestRule.onAllNodes(isSelectable()).assertCountEquals(3)
        option(R.string.updater_download).assertIsSelected()
        option(R.string.updater_install_manual).assertIsNotSelected()
        option(R.string.updater_install_automatic).assertIsNotSelected()
        composeTestRule.onNodeWithText(string(R.string.updater_download_summary)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.updater_install_manual_summary)).assertIsDisplayed()
    }

    @Test fun anUnavailableAutomaticInstallIsDisabledAndExplained() {
        showDialog(automaticAvailable = false)
        option(R.string.updater_install_automatic).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.updater_install_automatic_requirement)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.updater_install_automatic_summary)).assertCountEquals(0)
        option(R.string.updater_install_automatic).performClick()
        option(R.string.updater_install_automatic).assertIsNotSelected()
        option(R.string.updater_download).assertIsSelected()
    }

    @Test fun anAvailableAutomaticInstallCanBeChosen() {
        val confirmed = mutableListOf<UpdateAction>()
        showDialog(automaticAvailable = true, confirmed)
        option(R.string.updater_install_automatic).assertIsEnabled()
        composeTestRule.onNodeWithText(string(R.string.updater_install_automatic_summary)).assertIsDisplayed()
        option(R.string.updater_install_automatic).performClick()
        option(R.string.updater_install_automatic).assertIsSelected()
        composeTestRule.onNodeWithText(string(R.string.updater_continue)).performClick()
        assertEquals(listOf(UpdateAction.INSTALL_AUTOMATIC), confirmed)
    }

    @Test fun continueReportsTheSelectedAction() {
        val confirmed = mutableListOf<UpdateAction>()
        showDialog(automaticAvailable = false, confirmed)
        composeTestRule.onNodeWithText(string(R.string.updater_continue)).performClick()
        option(R.string.updater_install_manual).performClick()
        composeTestRule.onNodeWithText(string(R.string.updater_continue)).performClick()
        assertEquals(listOf(UpdateAction.DOWNLOAD, UpdateAction.INSTALL_MANUAL), confirmed)
    }

    @Test fun aWorkingCardShowsItsStatusAndDisablesItsButtons() {
        showCard(Operation.Working(Operation.Kind.DOWNLOAD))
        composeTestRule.onNodeWithText(string(R.string.updater_downloading)).assertIsDisplayed()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.updater_ignore)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.updater_changelog)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.updater_download)).assertIsNotEnabled()
    }

    @Test fun anInstallingCardSaysSo() {
        showCard(Operation.Working(Operation.Kind.INSTALL))
        composeTestRule.onNodeWithText(string(R.string.updater_installing)).assertIsDisplayed()
    }

    @Test fun aFailedCardShowsTheMessageAndKeepsItsButtons() {
        showCard(Operation.Failed("Download failed: HTTP 404"))
        composeTestRule.onNodeWithText("Download failed: HTTP 404").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.updater_download)).assertIsEnabled()
        composeTestRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }

    @Test fun aSavedCardNamesTheFile() {
        showCard(Operation.Saved("porter-0.7.0.apk"))
        composeTestRule.onNodeWithText(string(R.string.updater_saved, "porter-0.7.0.apk")).assertIsDisplayed()
    }
}
