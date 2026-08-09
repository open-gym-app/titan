package com.opengym.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.format.formatWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

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
        val preference = mutableStateOf(ThemePreference.LIGHT)

        composeTestRule.setContent {
            val theme by preference
            OpenGymTheme(theme = theme) {
                captured.value = MaterialTheme.colorScheme.background
            }
        }

        val light = captured.value
        preference.value = ThemePreference.DARK
        composeTestRule.waitForIdle()

        assertNotEquals(light, captured.value)
    }

    @Test
    fun `changing the stored theme repaints without the composition being rebuilt`() {
        // #M4-23's "theme changes apply immediately across the app without a restart", at the
        // level it is actually implemented: `:app` re-renders the same `OpenGymTheme` call
        // with a new preference, and everything below it has to follow.
        val captured = mutableStateOf<Color?>(null)
        val preference = mutableStateOf(ThemePreference.DARK)

        composeTestRule.setContent {
            val theme by preference
            OpenGymTheme(theme = theme) {
                captured.value = MaterialTheme.colorScheme.primary
                Text("session")
            }
        }

        val dark = captured.value
        preference.value = ThemePreference.LIGHT
        composeTestRule.waitForIdle()

        assertNotEquals(dark, captured.value)
        composeTestRule.onNodeWithText("session").assertIsDisplayed()
    }

    @Test
    fun `the unit system is published to the tree, and follows the preference`() {
        val captured = mutableStateOf<UnitSystem?>(null)
        val preference = mutableStateOf(UnitSystem.METRIC)

        composeTestRule.setContent {
            val units by preference
            OpenGymTheme(unitSystem = units) {
                captured.value = LocalUnitSystem.current
            }
        }

        assertEquals(UnitSystem.METRIC, captured.value)

        preference.value = UnitSystem.IMPERIAL
        composeTestRule.waitForIdle()

        // The reason `LocalUnitSystem` is a `compositionLocalOf` and not the static variant:
        // a weight on screen has to change unit the moment the setting does.
        assertEquals(UnitSystem.IMPERIAL, captured.value)
    }

    @Test
    fun `the formatters read the published unit system without being handed it`() {
        val preference = mutableStateOf(UnitSystem.METRIC)
        // Pinned, because the assertions below contain a decimal separator and the formatter
        // is deliberately locale-aware — see `WeightFormatTest`.
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.UK)

        composeTestRule.setContent {
            val units by preference
            OpenGymTheme(unitSystem = units) {
                // No `UnitSystem` argument anywhere: this is the claim the local exists to
                // make good on, and the thing a screen would otherwise thread through every
                // composable between the preference and the number.
                Text(formatWeight(20_000_000))
            }
        }

        try {
            composeTestRule.onNodeWithText("20 kg").assertIsDisplayed()

            preference.value = UnitSystem.IMPERIAL
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("44.09 lb").assertIsDisplayed()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `a composable outside the theme still reads metric rather than crashing`() {
        val captured = mutableStateOf<UnitSystem?>(null)

        composeTestRule.setContent { captured.value = LocalUnitSystem.current }

        assertEquals(UnitSystem.METRIC, captured.value)
    }
}
