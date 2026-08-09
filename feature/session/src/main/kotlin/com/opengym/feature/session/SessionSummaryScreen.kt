package com.opengym.feature.session

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
 * The payoff screen, shown once `FinishSessionUseCase` completes (#27).
 *
 * Scaffolding. "Done" is the flow's exit: it pops the whole session graph, which is what
 * puts the user back on the tab they started from with the bottom bar restored.
 */
@Composable
fun SessionSummaryScreen(
    sessionId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = "SUMMARY · SESSION $sessionId",
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Session logged",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Stats grid, muscle map and new PRs land with #27; the XP celebration " +
                "with #111.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDone,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OpenGymTheme.spacing.md),
        ) {
            Text(text = "DONE", style = OpenGymTheme.typography.eyebrow)
        }
    }
}

@Preview(name = "Session summary", showBackground = true)
@Composable
fun SessionSummaryScreenPreview() {
    OpenGymTheme {
        SessionSummaryScreen(sessionId = 1L, onDone = {})
    }
}
