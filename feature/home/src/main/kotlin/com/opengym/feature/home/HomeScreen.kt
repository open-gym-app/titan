package com.opengym.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The dashboard, and the app's landing destination.
 *
 * Scaffolding: the resume banner, the this-week strip and the quick-start template row are
 * #23's work, and the progression cards on top of them are #110's. What is real here is the
 * shape of the screen's contract — it is the stateless half of the two-composable
 * convention (CONTRIBUTING.md § Screens), it names the *event* rather than the destination,
 * and `:app` decides where "start a session" leads.
 */
@Composable
fun HomeScreen(
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = "TODAY",
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Ready to train",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "The dashboard lands with #23. The shell below it is real: this button " +
                "leaves the tab scaffold for the session flow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onStartSession,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OpenGymTheme.spacing.md),
        ) {
            Text(text = "START SESSION", style = OpenGymTheme.typography.eyebrow)
        }
    }
}

@Preview(name = "Home", showBackground = true)
@Composable
fun HomeScreenPreview() {
    OpenGymTheme {
        HomeScreen(onStartSession = {})
    }
}
