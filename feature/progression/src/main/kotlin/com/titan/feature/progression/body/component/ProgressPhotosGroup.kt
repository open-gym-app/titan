package com.titan.feature.progression.body.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.PreferenceGroup
import com.titan.core.ui.component.PreferenceRow
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R

/**
 * The way into the progress photos (#304), as one row on the body screen.
 *
 * A row rather than a strip of thumbnails, because the body screen is a readout of figures and
 * already long; the photos have a screen of their own, and this is its door. The supporting line
 * says where they are kept — on this phone, and nowhere else — because that is the one thing a
 * user deciding whether to take one wants to know first.
 *
 * Drawn for an empty history too, for the reason [HealthConnectGroup] is: somebody who has logged
 * nothing may well start with a photo.
 */
@Composable
fun ProgressPhotosGroup(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceGroup(
        title = stringResource(R.string.body_photos_heading),
        modifier = modifier,
    ) {
        PreferenceRow(
            label = stringResource(R.string.body_photos_label),
            supporting = stringResource(R.string.body_photos_detail),
            onClick = onOpen,
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Progress photos row", showBackground = true, widthDp = 360)
@Composable
fun ProgressPhotosGroupPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        ProgressPhotosGroup(onOpen = {})
    }
}
