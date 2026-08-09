package com.opengym.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * Step one of the session flow: pick a template or start empty (#25).
 *
 * Scaffolding. Note what the shell around it does — this screen is outside the tab
 * scaffold, so the bottom bar is gone from here until the flow is left.
 */
@Composable
fun NewSessionScreen(
    onCancel: () -> Unit,
    onBeginSession: (sessionId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = "NEW SESSION",
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Set up your workout",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Template picker and exercise selection land with #25.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onBeginSession(PLACEHOLDER_SESSION_ID) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OpenGymTheme.spacing.md),
        ) {
            Text(text = "BEGIN", style = OpenGymTheme.typography.eyebrow)
        }
        TextButton(onClick = onCancel) {
            Text(text = "CANCEL", style = OpenGymTheme.typography.eyebrow)
        }
    }
}

/**
 * Stands in for the id `StartSessionUseCase` returns. #25 replaces this with the real one;
 * until then nothing downstream reads it for anything but display.
 */
private const val PLACEHOLDER_SESSION_ID = 1L

@Preview(name = "New session", showBackground = true)
@Composable
fun NewSessionScreenPreview() {
    OpenGymTheme {
        NewSessionScreen(onCancel = {}, onBeginSession = {})
    }
}
