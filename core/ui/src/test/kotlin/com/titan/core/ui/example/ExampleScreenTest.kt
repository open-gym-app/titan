package com.titan.core.ui.example

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The worked behaviour-test example. Copy this shape for a real screen.
 *
 * Four things make it work, and all four are deliberate:
 *
 * - [RobolectricTestRunner] supplies the Android runtime on the JVM, so this runs under
 *   `./gradlew test` with no emulator. The SDK level is pinned once for every module by the
 *   `robolectric.properties` the `titan.android.compose` convention generates — do not add
 *   a per-class `@Config(sdk = ...)`, it will drift from the pin.
 * - `createComposeRule()`, not `createAndroidComposeRule<Activity>()`: a stateless screen
 *   needs no activity, and asking for one drags in a manifest and a theme.
 * - The state is built by hand. No ViewModel, no Hilt graph, no Room. If a state is awkward
 *   to construct here, the UI state model is wrong — see CONTRIBUTING.md.
 * - Events are captured into a list and asserted on. That is the whole contract of the
 *   stateless half: given a state it renders, and given a gesture it emits.
 */
@RunWith(RobolectricTestRunner::class)
class ExampleScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ExampleEvent>()

    private fun setScreen(state: ExampleUiState) {
        composeTestRule.setContent {
            TitanTheme {
                ExampleScreen(state = state, onEvent = events::add)
            }
        }
    }

    @Test
    fun `renders every item in the state`() {
        setScreen(ExampleUiState(title = "Push day", items = listOf("Bench press", "Overhead press")))

        composeTestRule.onNodeWithText("Push day").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench press").assertIsDisplayed()
        composeTestRule.onNodeWithText("Overhead press").assertIsDisplayed()
    }

    @Test
    fun `shows the empty message when there are no items`() {
        setScreen(ExampleUiState(title = "Push day", items = emptyList()))

        composeTestRule.onNodeWithText(EXAMPLE_EMPTY_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `emits ItemClicked with the item that was tapped`() {
        setScreen(ExampleUiState(title = "Push day", items = listOf("Bench press", "Overhead press")))

        composeTestRule.onNodeWithText("Overhead press").performClick()

        assertEquals(listOf(ExampleEvent.ItemClicked("Overhead press")), events)
    }

    @Test
    fun `negative - renders no item text when the state is empty`() {
        setScreen(ExampleUiState(title = "Push day", items = emptyList()))

        composeTestRule.onAllNodesWithText("Bench press").assertCountEquals(0)
        assertEquals(emptyList<ExampleEvent>(), events)
    }
}
