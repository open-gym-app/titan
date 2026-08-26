package com.opengym.core.ui.format

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The last acceptance criterion of #M4-17: **changing the unit setting updates every
 * displayed value, with no screen revisit.**
 *
 * That is a claim about recomposition, not about arithmetic, so it cannot be made by a plain
 * JVM test of the formatters — it needs a composition whose `LocalUnitSystem` changes under
 * it. Which is what this is: one `Text` per formatter, none of them handed a [UnitSystem],
 * and a single state change at the root.
 *
 * The failure it guards against is a real one and it is silent. `LocalUnitSystem` is a
 * `compositionLocalOf`; had it been declared `staticCompositionLocalOf` like the spacing and
 * typography locals beside it, everything here would still compile, every unit test would
 * still pass, and the app would render stale units until the screen was navigated away from
 * and back.
 */
@RunWith(RobolectricTestRunner::class)
class AmbientUnitSystemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val unitSystem = mutableStateOf(UnitSystem.METRIC)

    @Test
    fun `every formatter repaints when the unit system changes under it`() {
        withLocale(Locale.UK) {
            composeTestRule.setContent {
                val units by unitSystem
                OpenGymTheme(unitSystem = units) {
                    Column {
                        // Not one `UnitSystem` argument. This is the shape a screen is
                        // supposed to have, and the reason the ambient overloads exist.
                        Text(formatWeight(102_500_000))
                        Text(formatVolume(4_820_000_000))
                        Text(formatDistance(520_000))
                    }
                }
            }

            assertDisplayed("102.5 kg", "4,820 kg", "5.2 km")

            unitSystem.value = UnitSystem.IMPERIAL
            composeTestRule.waitForIdle()

            assertDisplayed("225.97 lb", "10,626 lb", "3.23 mi")
        }
    }

    @Test
    fun `a value shown in the short distance unit follows the switch too`() {
        // The unit is chosen per value, so a switch can change the *unit* as well as the
        // number: a 400 m repeat is 437 yd, not 0.25 mi.
        withLocale(Locale.UK) {
            composeTestRule.setContent {
                val units by unitSystem
                OpenGymTheme(unitSystem = units) { Text(formatDistance(40_000)) }
            }

            assertDisplayed("400 m")

            unitSystem.value = UnitSystem.IMPERIAL
            composeTestRule.waitForIdle()

            assertDisplayed("437 yd")
        }
    }

    private fun assertDisplayed(vararg texts: String) {
        texts.forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }
    }
}
