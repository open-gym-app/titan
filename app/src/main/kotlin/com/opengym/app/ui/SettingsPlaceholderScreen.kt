package com.opengym.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A stand-in until #97 builds `:feature:settings`.
 *
 * It exists so the gear in the top app bar leads somewhere real and the shell's chrome can
 * be tested end to end. Delete it, and [com.opengym.app.navigation.SettingsDestination]
 * with it, when the feature module lands.
 */
@Composable
fun SettingsPlaceholderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = "SETTINGS",
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Units, theme, rest-timer defaults and backup land with #97.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBack) {
            Text(text = "BACK", style = OpenGymTheme.typography.eyebrow)
        }
    }
}

@Preview(name = "Settings placeholder", showBackground = true)
@Composable
fun SettingsPlaceholderScreenPreview() {
    OpenGymTheme {
        SettingsPlaceholderScreen(onBack = {})
    }
}
