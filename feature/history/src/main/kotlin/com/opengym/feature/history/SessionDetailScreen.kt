package com.opengym.feature.history

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
 * A logged session, opened from the history list.
 *
 * Scaffolding: #27 builds the summary layout and #24 reuses it here in read-only mode. It
 * exists now because the Sessions tab needs a second level for the shell's back-stack
 * behaviour to be observable at all.
 */
@Composable
fun SessionDetailScreen(
    sessionId: Long,
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
            text = "SESSION · $sessionId",
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Session detail",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "#27's summary layout, in historical view mode.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBack) {
            Text(text = "BACK", style = OpenGymTheme.typography.eyebrow)
        }
    }
}

@Preview(name = "Session detail", showBackground = true)
@Composable
fun SessionDetailScreenPreview() {
    OpenGymTheme {
        SessionDetailScreen(sessionId = 1L, onBack = {})
    }
}
