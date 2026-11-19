package com.titan.core.ui.component.titan

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.TitanVital
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TitanComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a low vital says LOW in words as well as in colour, and says it aloud`() {
        composeTestRule.setContent {
            TitanTheme(reducedMotion = true) { TitanVitalRow(TitanVital.FUEL, value = 22, alert = true) }
        }

        composeTestRule.onNodeWithContentDescription("Fuel, low, 22 of 100").assertExists()
    }

    @Test
    fun `a vital that is not alerting reads its value`() {
        composeTestRule.setContent {
            TitanTheme(reducedMotion = true) { TitanVitalRow(TitanVital.SPIRIT, value = 80, alert = false) }
        }

        composeTestRule.onNodeWithContentDescription("Spirit, 80 of 100").assertExists()
    }

    @Test
    fun `with reduced motion the bar is at its value from the first frame`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            TitanTheme(reducedMotion = true) { TitanVitalRow(TitanVital.SPIRIT, value = 55, alert = false) }
        }

        composeTestRule.onNodeWithContentDescription("Spirit, 55 of 100")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(55f, 0f..100f),
                ),
            )
    }

    @Test
    fun `with motion the bar counts up to its value`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            TitanTheme(reducedMotion = false) { TitanVitalRow(TitanVital.SPIRIT, value = 55, alert = false) }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        val start = progressOf("Spirit, 55 of 100")

        composeTestRule.mainClock.advanceTimeBy(1_000)

        assertEquals(0f, start, 1f)
        assertEquals(55f, progressOf("Spirit, 55 of 100"), 0.01f)
    }

    @Test
    fun `an item tile says its name, its count and its state, and is a 48dp target`() {
        var taps = 0
        composeTestRule.setContent {
            TitanTheme {
                TitanItemTile(
                    TitanIcons.food("steak"),
                    "Steak",
                    "×1",
                    "1 left",
                    TitanItemState.NEW,
                    Color0,
                    Color0,
                    { taps++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Steak, 1 left, New")
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
            .performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a worn tile is selected, so it is not conveyed by the rule alone`() {
        composeTestRule.setContent {
            TitanTheme {
                TitanItemTile(
                    TitanIcons.of(Gear.HEADBAND),
                    "Headband",
                    "Worn",
                    "worn",
                    TitanItemState.WORN,
                    Color0,
                    Color0,
                    {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Headband, worn, Worn").assertIsSelected()
    }

    @Test
    fun `an effect chip reads as a sentence`() {
        composeTestRule.setContent { TitanTheme { TitanEffectChip(TitanVital.FUEL, 40) } }

        composeTestRule.onNodeWithContentDescription("Fuel plus 40").assertExists()
    }

    private fun progressOf(description: String): Float =
        composeTestRule.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current

    private companion object {
        val Color0 = androidx.compose.ui.graphics.Color.Gray
    }
}
