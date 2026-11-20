package com.titan.feature.progression.body.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
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
 * The Health Connect row: the way from the body screen to the screen that connects Health
 * Connect, says which of weight and body fat is shared, and imports what other apps saved (#350).
 *
 * A link rather than the controls themselves, because the controls already have a home: the
 * conversation that grants access — a rationale, then Health Connect's dialog, then what to do
 * after a refusal — is the settings screen's, and a second copy here would be a second place for
 * the two to drift apart. What the row promises, "in and out", is what that screen does.
 *
 * Drawn for a user with an empty history too. Somebody who has logged nothing is exactly the
 * user deciding whether to type their measurements in by hand or bring them in from the app
 * their scale writes to, and the row is the answer to that question. Not drawn at all where
 * Health Connect cannot be used — the settings row's rule, see
 * [com.titan.feature.progression.body.BodyMeasurementsUiState.healthConnectAvailable].
 *
 * The heart is the one mark on this screen that is purely a mark, and it is drawn in the
 * neutral tile rather than the accent — the accent belongs to the figures above it.
 *
 * @param onOpen opens the Health Connect screen. Hoisted: the destination is another feature's,
 *   and only `:app` may route between features.
 */
@Composable
fun HealthConnectGroup(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceGroup(
        title = stringResource(R.string.body_health_connect_heading),
        modifier = modifier,
    ) {
        PreferenceRow(
            label = stringResource(R.string.body_health_connect_label),
            supporting = stringResource(R.string.body_health_connect_detail),
            leading = Icons.Outlined.MonitorHeart,
            onClick = onOpen,
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Health Connect", showBackground = true, widthDp = 360)
@Composable
fun HealthConnectGroupPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        HealthConnectGroup(onOpen = {})
    }
}

@Preview(name = "Health Connect - dark", showBackground = true, widthDp = 360)
@Composable
fun HealthConnectGroupDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        HealthConnectGroup(onOpen = {})
    }
}
