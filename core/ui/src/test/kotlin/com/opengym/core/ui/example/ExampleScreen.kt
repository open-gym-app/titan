package com.opengym.core.ui.example

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.theme.OpenGymTheme

/** The state [ExampleScreen] renders. Hand-built by the tests; no ViewModel produces it. */
data class ExampleUiState(
    val title: String,
    val items: List<String>,
)

sealed interface ExampleEvent {
    data class ItemClicked(val item: String) : ExampleEvent
    data object RefreshClicked : ExampleEvent
}

const val EXAMPLE_EMPTY_MESSAGE = "Nothing logged yet"

/**
 * The template every screen test in this repository is copied from. It lives in `src/test`
 * on purpose: it is a worked example of the testing patterns, not a component anything is
 * meant to use, so it must not ship in the library.
 *
 * It follows the two-composable screen convention from CONTRIBUTING.md, minus the stateful
 * `XRoute` half — a route wires a ViewModel and is not what a Compose test drives.
 *
 * See [ExampleScreenTest] for the behaviour pattern and [ExampleScreenScreenshotTest] for
 * the hand-written screenshot pattern. The `@Preview`s below are captured automatically:
 * the Roborazzi plugin generates a Robolectric test per preview, so a real screen gets its
 * screenshot coverage from the previews it already carries for the IDE.
 */
@Composable
fun ExampleScreen(
    state: ExampleUiState,
    onEvent: (ExampleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = state.title, style = MaterialTheme.typography.titleLarge)

            if (state.items.isEmpty()) {
                Text(
                    text = EXAMPLE_EMPTY_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                state.items.forEach { item ->
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEvent(ExampleEvent.ItemClicked(item)) }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            Button(
                onClick = { onEvent(ExampleEvent.RefreshClicked) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Refresh")
            }
        }
    }
}

/**
 * One preview per interesting state, each wrapped in [OpenGymTheme] so the captured image
 * is the one the app renders rather than a bare `MaterialTheme` default.
 */
@Preview(name = "Example screen", showBackground = true)
@Composable
fun ExampleScreenPreview() {
    OpenGymTheme {
        ExampleScreen(
            state = ExampleUiState(title = "Push day", items = listOf("Bench press", "Overhead press")),
            onEvent = {},
        )
    }
}

@Preview(name = "Example screen - empty", showBackground = true)
@Composable
fun ExampleScreenEmptyPreview() {
    OpenGymTheme {
        ExampleScreen(
            state = ExampleUiState(title = "Push day", items = emptyList()),
            onEvent = {},
        )
    }
}
