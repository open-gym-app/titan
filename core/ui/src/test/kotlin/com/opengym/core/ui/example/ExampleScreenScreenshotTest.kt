package com.opengym.core.ui.example

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureRoboImage
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The worked hand-written screenshot example.
 *
 * Most screenshot coverage should come from `@Preview`s instead — the Roborazzi plugin
 * generates a test per preview, so a preview is free coverage and this file is not. Write a
 * capture by hand only for what a preview cannot express:
 *
 * - a state reached by *driving* the UI (expanded, scrolled, mid-selection), since a preview
 *   only ever renders its initial composition;
 * - a single node rather than the whole screen, when the screen around it is noisy and its
 *   churn would keep re-recording an unrelated reference.
 *
 * `@GraphicsMode(NATIVE)` is required: Robolectric's legacy renderer returns a blank bitmap,
 * so without it the reference image records successfully and is empty.
 *
 * The file name passed to `captureRoboImage` is resolved against `src/test/screenshots` —
 * see `roborazzi.record.filePathStrategy` in gradle.properties. Reference images are
 * committed; re-record with `make screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExampleScreenScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val populated = ExampleUiState(
        title = "Push day",
        items = listOf("Bench press", "Overhead press"),
    )

    @Test
    fun `captures a single row rather than the whole screen`() {
        composeTestRule.setContent {
            OpenGymTheme {
                ExampleScreen(state = populated, onEvent = {})
            }
        }

        composeTestRule.onNodeWithText("Bench press")
            .captureRoboImage("ExampleScreen_row.png")
    }
}
