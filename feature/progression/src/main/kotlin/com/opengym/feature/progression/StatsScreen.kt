package com.opengym.feature.progression

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The Stats tab — the hub for M5's analytics (#32).
 *
 * Scaffolding, and it will stay that way longer than the other three: the volume
 * aggregations (#31), the PR engine (#30) and the XP model (#33) are all M5, so there is
 * nothing truthful to render here yet. A tab that shows zeroed charts to a user with no
 * history is worse than one that says so.
 */
@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = "PROGRESSION",
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Stats",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Volume, muscle map and personal records arrive with M5 (#32).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Stats", showBackground = true)
@Composable
fun StatsScreenPreview() {
    OpenGymTheme {
        StatsScreen()
    }
}
