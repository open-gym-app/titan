package com.titan.feature.history.plate

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateStock
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The plate calculator, driven from hand-built states.
 *
 * The stateless half only — no ViewModel, no Hilt, no store. The three cases the issue asks for
 * are a target the rack makes, one it does not, and one under the bar, and they are the three
 * things this screen can get wrong: showing the wrong plates, showing plates for a weight it
 * cannot reach, and pretending an impossible target is fine.
 */
@RunWith(RobolectricTestRunner::class)
class PlateCalculatorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<PlateCalculatorEvent>()
    private var backs = 0

    private fun setContent(
        target: Long = KILO * 100,
        preferences: UserPreferences = UserPreferences(),
        unitSystem: UnitSystem = preferences.unitSystem,
    ) {
        composeTestRule.setContent {
            TitanTheme(theme = ThemePreference.LIGHT, unitSystem = unitSystem) {
                PlateCalculatorScreen(
                    state = PlateCalculatorUiState(target = target, preferences = preferences),
                    onEvent = events::add,
                    onBack = { backs++ },
                )
            }
        }
    }

    /** The screen is one scroll, so anything below the fold has to be brought into view. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
    }

    private fun scrollTo(text: String) = scrollTo(hasText(text))

    // ─── A target the rack makes ────────────────────────────────────────────────────────

    @Test
    fun `a standard target draws the loading and no caveat`() {
        setContent()

        // The figure, its eyebrow and its unit are one node to a screen reader, so the target
        // is asserted through the description that replaces them.
        composeTestRule.onNodeWithContentDescription(TARGET_100).assertIsDisplayed()
        composeTestRule.onNodeWithText("PER SIDE · BAR 20 KG").assertIsDisplayed()

        // 40 kg a side, which the default rack makes as two 20s — the fewest plates that does
        // it. The barbell and the legend are asserted separately because either one can go
        // missing while the other still draws.
        composeTestRule
            .onNodeWithContentDescription("Barbell loaded with 2 plates of 20 kg per side")
            .assertIsDisplayed()
        scrollTo(hasContentDescription("2 plates of 20 kg"))
        composeTestRule.onNodeWithText("PER SIDE").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("2 plates of 20 kg").assertIsDisplayed()

        // No line under the target: the caveat's presence is the signal, so its absence has to
        // be asserted rather than assumed.
        composeTestRule.onNodeWithText("Loads to 100 kg · 0 kg short of your target").assertDoesNotExist()
    }

    // ─── A target it does not ───────────────────────────────────────────────────────────

    @Test
    fun `an unreachable target keeps the number and says what will be on the bar`() {
        // 101 kg is half a kilo a side off the 1.25 kg grid the rack steps in.
        setContent(target = KILO * 101)

        // The target is *not* snapped to something loadable. That is the whole point.
        composeTestRule.onNodeWithContentDescription("Target weight, 101 kg. Tap to type one").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Loads to 100 kg · 1 kg short of your target")
            .assertIsDisplayed()
        // And the barbell shows the loading, not the target.
        composeTestRule
            .onNodeWithContentDescription("Barbell loaded with 2 plates of 20 kg per side")
            .assertIsDisplayed()
    }

    @Test
    fun `a rack that runs out is reported as a shortfall, not as a bare bar`() {
        val homeGym = UserPreferences(
            plateInventory = PlateInventory.of(listOf(PlateStock(weight = KILO * 20, pairs = 1))),
        )
        setContent(target = KILO * 100, preferences = homeGym)

        composeTestRule
            .onNodeWithText("Loads to 60 kg · 40 kg short of your target")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Barbell loaded with 1 plate of 20 kg per side")
            .assertIsDisplayed()
    }

    // ─── A target under the bar ─────────────────────────────────────────────────────────

    @Test
    fun `a target under the bar says so instead of drawing plates`() {
        setContent(target = KILO * 15)

        composeTestRule.onNodeWithContentDescription("Target weight, 15 kg. Tap to type one").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lighter than the empty 20 kg bar").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Barbell with no plates on it").assertIsDisplayed()
        // The legend draws nothing rather than a heading over an empty list.
        composeTestRule.onNodeWithText("PER SIDE").assertDoesNotExist()
    }

    @Test
    fun `the bar on its own is drawn without a caveat`() {
        setContent(target = KILO * 20)

        composeTestRule.onNodeWithContentDescription("Barbell with no plates on it").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lighter than the empty 20 kg bar").assertDoesNotExist()
    }

    // ─── The controls ───────────────────────────────────────────────────────────────────

    @Test
    fun `the steppers move the target by a pair of plates`() {
        setContent()

        composeTestRule.onNodeWithText("+2.5").performClick()
        composeTestRule.onNodeWithText("−10").performClick()

        assertEquals(
            listOf(
                PlateCalculatorEvent.TargetStepped(2_500_000),
                PlateCalculatorEvent.TargetStepped(-10_000_000),
            ),
            events,
        )
    }

    @Test
    fun `the steppers are sized in the unit the plates are stamped in`() {
        // Not conversions of each other: 5 lb is 2.27 kg, which is not a plate that exists.
        setContent(
            target = 102_058_283,
            preferences = UserPreferences(unitSystem = UnitSystem.IMPERIAL, barWeight = 20_411_657),
        )

        composeTestRule.onNodeWithText("+5").assertIsDisplayed()
        composeTestRule.onNodeWithText("−20").assertIsDisplayed()

        composeTestRule.onNodeWithText("+5").performClick()
        assertEquals(listOf(PlateCalculatorEvent.TargetStepped(2_267_962)), events)
    }

    @Test
    fun `the target opens a field, and the equipment button opens the editor`() {
        setContent()

        composeTestRule.onNodeWithContentDescription(TARGET_100).performClick()
        scrollTo("Bar & plates")
        composeTestRule.onNodeWithText("Bar & plates").performClick()

        assertEquals(
            listOf(
                PlateCalculatorEvent.SheetRequested(PlateCalculatorSheet.TARGET),
                PlateCalculatorEvent.SheetRequested(PlateCalculatorSheet.EQUIPMENT),
            ),
            events,
        )
    }

    @Test
    fun `the bar is the way out`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    private companion object {
        /** One kilogram in milligrams, so the targets below read as weights. */
        const val KILO = 1_000_000L

        const val TARGET_100 = "Target weight, 100 kg. Tap to type one"
    }
}
