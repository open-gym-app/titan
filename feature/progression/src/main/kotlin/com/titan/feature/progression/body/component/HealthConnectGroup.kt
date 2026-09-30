package com.titan.feature.progression.body.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.PreferenceGroup
import com.titan.core.ui.component.PreferenceSoonRow
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R

/**
 * The Health Connect row, disabled until #54 builds what is behind it.
 *
 * Present, in its final position, and visibly unavailable — the decision #M4-23 recorded for
 * the settings screen, held to here so the two say the same thing in the same shape. The row
 * itself is `:core:ui`'s [PreferenceSoonRow], which is why: a second implementation of "coming
 * later" is a second chance to disagree about what unavailable looks like, and it is what
 * moved the settings rows into the design system in the first place.
 *
 * It is drawn for a user with an empty history too. Somebody who has logged nothing is exactly
 * the user deciding whether to type their measurements in by hand or wait for the sync, and the
 * row is the answer to that question.
 *
 * The heart is the one mark on this screen that is purely a mark, and it is drawn in the
 * neutral tile rather than the accent — the accent belongs to the figures above it.
 */
@Composable
fun HealthConnectGroup(modifier: Modifier = Modifier) {
    PreferenceGroup(
        title = stringResource(R.string.body_health_connect_heading),
        modifier = modifier,
    ) {
        PreferenceSoonRow(
            label = stringResource(R.string.body_health_connect_label),
            supporting = stringResource(R.string.body_health_connect_detail),
            leading = Icons.Outlined.MonitorHeart,
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Health Connect", showBackground = true, widthDp = 360)
@Composable
fun HealthConnectGroupPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        HealthConnectGroup()
    }
}

@Preview(name = "Health Connect - dark", showBackground = true, widthDp = 360)
@Composable
fun HealthConnectGroupDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        HealthConnectGroup()
    }
}
