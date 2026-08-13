package com.opengym.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The Sessions tab — session browsing, which #24 splits into HISTORY / TEMPLATES / CALENDAR.
 *
 * Scaffolding. The one real behaviour is the push onto a session's recap — the screen
 * `:feature:session` draws in its historical mode (#27) — which is what gives this tab depth,
 * and a tab with depth is the only way to show that switching tabs and coming back restores
 * where the user was rather than resetting to the tab's root.
 */
@Composable
fun SessionsScreen(
    onSessionClick: (sessionId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = "HISTORY",
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "The history list, template list and calendar heatmap land with #24.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { onSessionClick(PLACEHOLDER_SESSION_ID) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OpenGymTheme.spacing.md),
        ) {
            Text(text = "OPEN A SESSION", style = OpenGymTheme.typography.eyebrow)
        }
    }
}

/**
 * Stands in for the id of a tapped row until #24 renders real ones. It is never read — the
 * detail screen only echoes it — so it carries no meaning beyond "some session".
 */
private const val PLACEHOLDER_SESSION_ID = 1L

@Preview(name = "Sessions", showBackground = true)
@Composable
fun SessionsScreenPreview() {
    OpenGymTheme {
        SessionsScreen(onSessionClick = {})
    }
}
