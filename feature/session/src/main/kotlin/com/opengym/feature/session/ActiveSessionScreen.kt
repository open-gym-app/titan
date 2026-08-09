package com.opengym.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The workout itself: log sets, run the rest timer, finish (#26).
 *
 * Scaffolding. The numeric style is here rather than deferred because it is what the screen
 * is made of — a set row is a weight, a rep count and a timer, and all three are mono with
 * tabular figures so nothing shifts as the digits change.
 *
 * @param snackbarHostState where the screen's transient messages appear. Hoisted with a
 *   default so the one message there is today — the blocked back gesture — can be raised by
 *   the destination that swallows it, while `@Preview` still gets a working screen for free.
 */
@Composable
fun ActiveSessionScreen(
    sessionId: Long,
    onFinishSession: (sessionId: Long) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            Text(
                text = "ACTIVE · SESSION $sessionId",
                style = OpenGymTheme.typography.eyebrow,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "00:00",
                style = OpenGymTheme.typography.numericLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Set logging, the rest timer and finish-session land with #26 and #98.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onFinishSession(sessionId) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = OpenGymTheme.spacing.md),
            ) {
                Text(text = "FINISH", style = OpenGymTheme.typography.eyebrow)
            }
        }
    }
}

@Preview(name = "Active session", showBackground = true)
@Composable
fun ActiveSessionScreenPreview() {
    OpenGymTheme {
        ActiveSessionScreen(sessionId = 1L, onFinishSession = {})
    }
}
