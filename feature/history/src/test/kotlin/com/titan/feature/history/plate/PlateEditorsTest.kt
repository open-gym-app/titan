package com.titan.feature.history.plate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateStock
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.history.plate.component.EquipmentPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two things on the calculator that are opened rather than pressed: the typed target, and
 * the bar-and-plates editor.
 *
 * The dialog is driven through the whole screen, so the assertions cover the wiring as well as
 * the control — a field that draws perfectly and reports its answer to nobody is the failure
 * worth catching. The editor is driven as `EquipmentPanel` rather than through its sheet, for
 * the reason `LibraryFilterPanelTest` gives: a `ModalBottomSheet` puts its content in a window
 * of its own, where a test's touch injection lands nowhere. That the *screen* opens it is
 * covered in `PlateCalculatorScreenTest`.
 */
@RunWith(RobolectricTestRunner::class)
class PlateEditorsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<PlateCalculatorEvent>()

    private fun setContent(
        sheet: PlateCalculatorSheet,
        target: Long = 100_000_000,
        preferences: UserPreferences = UserPreferences(),
    ) {
        composeTestRule.setContent {
            TitanTheme(theme = ThemePreference.LIGHT, unitSystem = preferences.unitSystem) {
                PlateCalculatorScreen(
                    state = PlateCalculatorUiState(
                        target = target,
                        preferences = preferences,
                        sheet = sheet,
                    ),
                    onEvent = events::add,
                    onBack = {},
                )
            }
        }
    }

    /** The editor's contents, with the sheet around them left out. See the note on this class. */
    private fun setEquipmentContent(
        preferences: UserPreferences,
        message: String? = null,
    ) {
        composeTestRule.setContent {
            TitanTheme(theme = ThemePreference.LIGHT, unitSystem = preferences.unitSystem) {
                EquipmentPanel(
                    barWeight = preferences.barWeight,
                    inventory = preferences.plates,
                    onBarWeightChange = { events += PlateCalculatorEvent.BarWeightSelected(it) },
                    onPairsChange = { weight, pairs ->
                        events += PlateCalculatorEvent.PlatePairsSelected(weight, pairs)
                    },
                    onReset = { events += PlateCalculatorEvent.InventoryReset },
                    message = message,
                )
            }
        }
    }

    // ─── Typing a target ────────────────────────────────────────────────────────────────

    @Test
    fun `the target field opens on the current weight and commits on Set`() {
        setContent(PlateCalculatorSheet.TARGET)

        // Seeded, not blank: the common edit is a digit, not a whole new number.
        composeTestRule.onNodeWithText("100").assertIsDisplayed()

        composeTestRule.onNodeWithText("100").performTextReplacement("142.5")
        composeTestRule.onNodeWithText("Set").performClick()

        assertEquals(listOf(PlateCalculatorEvent.TargetEntered(142_500_000)), events)
    }

    @Test
    fun `a target that is not a weight cannot be committed`() {
        setContent(PlateCalculatorSheet.TARGET)

        composeTestRule.onNodeWithText("100").performTextReplacement("heavy")
        composeTestRule.onNodeWithText("Enter a weight above zero").assertIsDisplayed()

        composeTestRule.onNodeWithText("Set").performClick()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `the field reads in the unit the user lifts in`() {
        setContent(
            sheet = PlateCalculatorSheet.TARGET,
            target = 102_058_283,
            preferences = UserPreferences(unitSystem = UnitSystem.IMPERIAL, barWeight = 20_411_657),
        )

        composeTestRule.onNodeWithText("Weight (lb)").assertIsDisplayed()
        composeTestRule.onNodeWithText("225").assertIsDisplayed()
    }

    // ─── The bar and the rack ───────────────────────────────────────────────────────────

    @Test
    fun `the editor lists every size the rack knows, switched on or not`() {
        setEquipmentContent(threeSizeRack())

        composeTestRule.onNodeWithText("BAR").assertIsDisplayed()
        composeTestRule.onNodeWithText("PLATES · PAIRS ON THE RACK").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("One more pair of 25 kg").assertIsDisplayed()
        // Held in no pairs at all and still listed, which is what makes it switchable back on.
        composeTestRule.onNodeWithContentDescription("One more pair of 2.5 kg").assertIsDisplayed()
    }

    @Test
    fun `a plate stepper reports the new count, not the change`() {
        setEquipmentContent(threeSizeRack())

        composeTestRule.onNodeWithContentDescription("One fewer pair of 25 kg").performClick()

        assertEquals(
            listOf(PlateCalculatorEvent.PlatePairsSelected(weight = 25_000_000, pairs = 5)),
            events,
        )
    }

    @Test
    fun `a size switched off cannot go below none`() {
        setEquipmentContent(threeSizeRack())

        composeTestRule.onNodeWithContentDescription("One fewer pair of 2.5 kg").performClick()

        assertEquals(
            listOf(PlateCalculatorEvent.PlatePairsSelected(weight = 2_500_000, pairs = 0)),
            events,
        )
    }

    @Test
    fun `a bar preset is one press, and the stepper covers everything between them`() {
        setEquipmentContent(threeSizeRack())

        composeTestRule.onNodeWithText("15 kg").performClick()
        composeTestRule.onNodeWithContentDescription("Heavier bar").performClick()

        assertEquals(
            listOf(
                PlateCalculatorEvent.BarWeightSelected(15_000_000),
                // From the *stored* weight, which is still 20 kg: the sheet draws what is
                // saved, so a second press before the write lands is not compounded.
                PlateCalculatorEvent.BarWeightSelected(22_500_000),
            ),
            events,
        )
    }

    @Test
    fun `the bar cannot be stepped down to nothing`() {
        setEquipmentContent(threeSizeRack(barWeight = 2_500_000))

        composeTestRule.onNodeWithContentDescription("Lighter bar").performClick()

        // Clamped rather than disabled, and never to zero — `setBarWeight` refuses that, so a
        // button that could reach it would be a button that throws.
        assertEquals(listOf(PlateCalculatorEvent.BarWeightSelected(2_500_000)), events)
    }

    @Test
    fun `a bar lighter than one increment is not made heavier by the lighter button`() {
        // Settings accepts any positive bar, so a 2 kg kids' bar is storable while the metric
        // step is 2.5 kg. Clamping to the step would answer "lighter" with a *heavier* bar.
        setEquipmentContent(threeSizeRack(barWeight = 2_000_000))

        composeTestRule.onNodeWithContentDescription("Lighter bar").performClick()

        assertEquals(listOf(PlateCalculatorEvent.BarWeightSelected(2_000_000)), events)
    }

    @Test
    fun `a failed write is said inside the editor`() {
        // Inside, because the editor is a sheet in a window of its own: the screen's snackbar is
        // drawn behind it and timed out before it can be read. See `EquipmentSheet`.
        setEquipmentContent(threeSizeRack(), message = "Could not save your equipment")

        composeTestRule.onNodeWithText("Could not save your equipment").assertIsDisplayed()
    }

    @Test
    fun `the standard set is one press away`() {
        setEquipmentContent(threeSizeRack())

        composeTestRule.onNodeWithText("Use the standard set").performClick()

        assertEquals(listOf(PlateCalculatorEvent.InventoryReset), events)
    }

    /**
     * A rack of three sizes, one of them switched off.
     *
     * Short enough that the whole sheet fits on screen without scrolling — a control the test
     * has to scroll to is a control it can silently fail to press. The sizes are chosen not to
     * collide with the bar presets (20 / 15 / 10 kg), so `onNodeWithText("15 kg")` can only be
     * the chip.
     */
    private fun threeSizeRack(barWeight: Long = 20_000_000) =
        UserPreferences(
            barWeight = barWeight,
            plateInventory = PlateInventory.of(
                listOf(
                    PlateStock(weight = 25_000_000, pairs = 6),
                    PlateStock(weight = 5_000_000, pairs = 2),
                    PlateStock(weight = 2_500_000, pairs = 0),
                ),
            ),
        )
}
