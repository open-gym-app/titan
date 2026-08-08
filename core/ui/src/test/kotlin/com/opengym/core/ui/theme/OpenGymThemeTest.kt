package com.opengym.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Also the smoke test for the Compose unit-test harness the `opengym.android.compose`
 * convention wires up: if `createComposeRule()` can build and drive a composable here, a
 * feature module's screen tests can too.
 */
@RunWith(RobolectricTestRunner::class)
class OpenGymThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `theme renders its content`() {
        composeTestRule.setContent {
            OpenGymTheme {
                Text("session")
            }
        }

        composeTestRule.onNodeWithText("session").assertIsDisplayed()
    }

    @Test
    fun `dark and light schemes differ`() {
        val captured = mutableStateOf<Color?>(null)
        val darkTheme = mutableStateOf(false)

        composeTestRule.setContent {
            val isDark by darkTheme
            OpenGymTheme(darkTheme = isDark) {
                captured.value = MaterialTheme.colorScheme.background
            }
        }

        val light = captured.value
        darkTheme.value = true
        composeTestRule.waitForIdle()

        assertNotEquals(light, captured.value)
    }
}
