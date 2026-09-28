package eu.darken.porter.manager.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.porter.manager.R
import eu.darken.porter.manager.updater.AvailableUpdate

internal data class UpdateCardState(
    val currentVersion: String,
    val newVersion: String,
    val releaseName: String?,
    val downloadAvailable: Boolean,
)

/** Tag `v0.7.0-rc0` shows as `0.7.0-rc0`, the form the installed versionName takes. */
internal fun AvailableUpdate.toCardState() = UpdateCardState(
    currentVersion = currentVersion,
    newVersion = release.tag.removePrefix("v"),
    releaseName = release.name?.takeIf { it.isNotBlank() },
    downloadAvailable = release.apk != null,
)

@Composable
internal fun UpdateCard(state: UpdateCardState, onIgnore: () -> Unit, onChangelog: () -> Unit, onDownload: () -> Unit) {
    HomeCard(stringResource(R.string.updater_card_title), Icons.TwoTone.NewReleases,
        tint = MaterialTheme.colorScheme.onTertiaryContainer,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(stringResource(R.string.updater_card_versions, state.newVersion, state.currentVersion))
        state.releaseName?.let { Text(it) }
        HomeCardActions {
            TextButton(onClick = onIgnore) { Text(stringResource(R.string.updater_ignore)) }
            OutlinedButton(onClick = onChangelog) { Text(stringResource(R.string.updater_changelog)) }
            if (state.downloadAvailable) Button(onClick = onDownload) { Text(stringResource(R.string.updater_download)) }
        }
    }
}
