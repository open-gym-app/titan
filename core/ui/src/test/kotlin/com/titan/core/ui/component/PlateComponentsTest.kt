package com.titan.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.plate.PlateCount
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.ui.theme.TitanTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The barbell and its legend, from hand-built loadings.
 *
 * The shapes are the screenshot tests' business — the previews in `BarbellDiagram.kt` cover the
 * crowded bar, the empty one and both schemes. What is asserted here is the part a picture
 * cannot check: that the two components describe the *same* loading, in words, to somebody who
 * is not looking at either of them.
 */
@RunWith(RobolectricTestRunner::class)
class PlateComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val metricRack = PlateInventory.METRIC.stock.map { it.weight }

    private val benchLoading = listOf(
        PlateCount(weight = 20_000_000, count = 2),
        PlateCount(weight = 1_250_000, count = 1),
    )

    @Test
    fun `the diagram announces the whole loading as one phrase`() {
        composeTestRule.setContent {
            TitanTheme {
                BarbellDiagram(perSide = benchLoading, denominations = metricRack)
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Barbell loaded with 2 plates of 20 kg, 1 plate of 1.25 kg per side")
            .assertIsDisplayed()
    }

    @Test
    fun `a bar with nothing on it says so rather than saying nothing`() {
        composeTestRule.setContent {
            TitanTheme { BarbellDiagram(perSide = emptyList(), denominations = metricRack) }
        }

        composeTestRule.onNodeWithContentDescription("Barbell with no plates on it").assertIsDisplayed()
    }

    @Test
    fun `the diagram renders in the ambient unit system`() {
        // The stamped weight on the plate is the one number the diagram draws itself, and it
        // goes through the same formatter as everything else — so an imperial user sees pounds
        // on the plates without this component knowing anything about the preference.
        composeTestRule.setContent {
            TitanTheme(unitSystem = UnitSystem.IMPERIAL) {
                BarbellDiagram(
                    perSide = listOf(PlateCount(weight = PlateInventory.IMPERIAL.stock.first().weight, count = 1)),
                    denominations = PlateInventory.IMPERIAL.stock.map { it.weight },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Barbell loaded with 1 plate of 45 lb per side")
            .assertIsDisplayed()
    }

    @Test
    fun `the legend lists a row per size, with its count`() {
        composeTestRule.setContent {
            TitanTheme { PlateLegend(perSide = benchLoading, denominations = metricRack) }
        }

        composeTestRule.onNodeWithText("PER SIDE").assertIsDisplayed()
        // Each row is one node to a screen reader, and it says what to pick up rather than
        // reading the multiplication sign aloud.
        composeTestRule.onNodeWithContentDescription("2 plates of 20 kg").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("1 plate of 1.25 kg").assertIsDisplayed()
    }

    @Test
    fun `the legend draws nothing for a bare bar`() {
        composeTestRule.setContent {
            TitanTheme { PlateLegend(perSide = emptyList(), denominations = metricRack) }
        }

        // Uppercased by `Eyebrow`, which is also why the heading is asserted as it is drawn.
        // Not an empty card with a heading over nothing: the screen says "just the bar" itself,
        // where it can also say what the bar weighs.
        composeTestRule.onNodeWithText("PER SIDE").assertDoesNotExist()
    }
}
