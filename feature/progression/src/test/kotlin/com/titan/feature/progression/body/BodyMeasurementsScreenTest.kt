package com.titan.feature.progression.body

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.UnitSystem
import com.titan.core.ui.format.formatLengthValue
import com.titan.core.ui.format.formatWeightValue
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.body.component.MeasurementForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The body screen, driven from hand-built state.
 *
 * The stateless half, with no ViewModel and no Hilt graph behind it — the split
 * CONTRIBUTING.md § Screens exists for. What the cards *look* like is the screenshot
 * references' job; this is about what the screen says, what it announces, and what it emits.
 *
 * The screen is a `LazyColumn`, so nothing below the fold exists in the tree until it is
 * scrolled to — hence [scrollTo] rather than `performScrollTo`, which only reaches a node that
 * is already composed.
 *
 * The form is driven directly rather than through the sheet, for the reason `ExerciseFormTest`
 * drives `ExerciseForm`: a `ModalBottomSheet` brings its own window and animation, and neither
 * is what the assertions here are about.
 */
@RunWith(RobolectricTestRunner::class)
class BodyMeasurementsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<BodyMeasurementsEvent>()
    private val saved = mutableListOf<BodyMeasurement>()
    private var healthConnectOpened = 0

    // ─── The populated screen ───────────────────────────────────────────────────────────

    @Test
    fun `it draws the weight, the parts and the composition`() {
        show(populated())

        composeTestRule.onNodeWithText("78.4").assertIsDisplayed()
        scrollTo(hasContentDescription("Chest, 102 cm")).assertIsDisplayed()
        scrollTo(hasContentDescription("Waist, 82 cm")).assertIsDisplayed()
        scrollTo(hasContentDescription("Body fat, 14.2 %")).assertIsDisplayed()
        // Lean mass is derived rather than stored: 78.4 kg at 14.2% body fat.
        scrollTo(hasContentDescription("Lean mass, 67.27 kg")).assertIsDisplayed()
    }

    @Test
    fun `the weight chart is announced as one readable series rather than as its marks`() {
        show(populated())

        // #144's criterion reaching a third screen: the card's chart carries its own sentence,
        // built from its own series, and the individual points are not announced.
        scrollTo(hasContentDescription("Body weight, Mar to Apr. Latest 78.4 kg.", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a part that has never been measured draws a dash rather than a zero`() {
        show(populated())

        // Nothing in the fixture has taped an arm, and `0 cm` is a reading somebody took.
        scrollTo(hasContentDescription("Arm, —")).assertIsDisplayed()
    }

    @Test
    fun `values follow the unit system`() {
        show(populated(), unitSystem = UnitSystem.IMPERIAL)

        composeTestRule.onNodeWithText("172.84").assertIsDisplayed()
        scrollTo(hasContentDescription("Chest, 40.2 in")).assertIsDisplayed()
    }

    @Test
    fun `the Health Connect row opens the Health Connect screen`() {
        show(populated())

        scrollTo(hasText("Sync with Health Connect")).assertIsEnabled().performClick()

        assertEquals(1, healthConnectOpened)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the Health Connect row is not drawn where Health Connect is not available`() {
        show(populated().copy(healthConnectAvailable = false))

        // The history below it is there to scroll to; the row never is.
        scrollTo(hasText("HISTORY"))
        composeTestRule.onAllNodes(hasText("Sync with Health Connect")).assertCountEquals(0)
    }

    // ─── The empty screen ───────────────────────────────────────────────────────────────

    @Test
    fun `a user who has measured nothing is asked to log something, not shown a chart`() {
        show(BodyMeasurementsUiState(isLoading = false))

        composeTestRule.onNodeWithText("Nothing measured yet").assertIsDisplayed()

        // Uppercase: the action reads in the eyebrow role, which is drawn shouted.
        composeTestRule.onNodeWithText("LOG A MEASUREMENT").performClick()

        assertEquals(listOf(BodyMeasurementsEvent.LogRequested), events)
    }

    @Test
    fun `the Health Connect row is drawn for a user with nothing logged too`() {
        show(BodyMeasurementsUiState(isLoading = false))

        // They are exactly the person deciding between typing measurements in and importing
        // them, which is the question the row answers.
        scrollTo(hasText("Sync with Health Connect")).assertIsDisplayed()
    }

    // ─── The history ────────────────────────────────────────────────────────────────────

    @Test
    fun `a history row reads as what the entry recorded, and tapping it opens the form`() {
        val state = populated()
        show(state)

        scrollTo(hasText("78.4 kg · Chest 102 cm · Waist 82 cm · 14.2%")).performClick()

        assertEquals(listOf(BodyMeasurementsEvent.EditRequested(state.history.first())), events)
    }

    @Test
    fun `a row swiped away is offered back, and the undo reports it`() {
        val state = populated()
        show(state.copy(deleted = state.history.first()))

        composeTestRule.onNodeWithText("deleted", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(BodyMeasurementsEvent.DeleteUndone), events)
    }

    @Test
    fun `a weigh-in with nothing else on it reads as the one figure it holds`() {
        show(populated())

        scrollTo(hasText("79.1 kg")).assertIsDisplayed()
    }

    // ─── The form ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the form opens pre-filled, and one changed figure is all it takes to save`() {
        showForm(latestEntry().copy(id = NEW_ENTRY))

        // Every field arrives carrying the last reading, so logging a single changed value is a
        // tap and a number rather than five measurements retaken.
        composeTestRule.onNodeWithText("Log measurements").assertIsDisplayed()
        composeTestRule.onNodeWithText("102").assertIsDisplayed()

        composeTestRule.onNodeWithText("78.4").performTextReplacement("77.9")
        formButton("Save").performClick()

        val entry = saved.single()
        assertEquals(77_900_000L, entry.weight)
        // Everything else came through untouched, which is the round trip the seed and the
        // parse have to agree on.
        assertEquals(1_020, entry.chest)
        assertEquals(142, entry.bodyFat)
    }

    @Test
    fun `an entry can be logged with one field and every other left blank`() {
        showForm(blankEntry())

        composeTestRule.onNodeWithText("Weight (kg)").performTextReplacement("78.4")
        formButton("Save").performClick()

        val entry = saved.single()
        assertEquals(78_400_000L, entry.weight)
        assertNull(entry.chest)
        assertNull(entry.bodyFat)
        assertNull(entry.notes)
    }

    @Test
    fun `an empty form cannot be saved`() {
        showForm(blankEntry())

        // The one thing `BodyMeasurementValidator` refuses outright: a dated row with no
        // measurement in it. The button is dead rather than the save silent.
        formButton("Save").assertIsNotEnabled()
    }

    @Test
    fun `the save button lives once there is one figure in the form`() {
        showForm(blankEntry())

        composeTestRule.onNodeWithText("Body fat (%)").performTextReplacement("14.2")

        formButton("Save").assertIsEnabled()
    }

    @Test
    fun `clearing every field disables the save again`() {
        showForm(latestEntry())

        listOf("78.4", "102", "82", "14.2").forEach { value ->
            composeTestRule.onNodeWithText(value).performTextClearance()
        }

        formButton("Save").assertIsNotEnabled()
    }

    @Test
    fun `a figure past its bound refuses the save rather than storing a typo`() {
        showForm(latestEntry())

        // A waist typed in millimetres, past the five-metre ceiling the validator holds
        // circumferences to. The bound is read off the validator, not restated in the form.
        composeTestRule.onNodeWithText("82").performTextReplacement("8200")

        formButton("Save").assertIsNotEnabled()
    }

    @Test
    fun `correcting an entry keeps its id and its date, so it is an update rather than a row`() {
        val entry = latestEntry()
        showForm(entry)

        composeTestRule.onNodeWithText("Edit entry").assertIsDisplayed()
        composeTestRule.onNodeWithText("78.4").performTextReplacement("77.9")
        formButton("Save").performClick()

        assertEquals(entry.id, saved.single().id)
        assertEquals(entry.createdAt, saved.single().createdAt)
    }

    @Test
    fun `the form is typed in the user's own units and stored in canonical ones`() {
        showForm(blankEntry(), unitSystem = UnitSystem.IMPERIAL)

        composeTestRule.onNodeWithText("Weight (lb)").performTextReplacement("172.84")
        composeTestRule.onNodeWithText("Chest (in)").performTextReplacement("40.2")
        formButton("Save").performClick()

        // Asserted as the round trip rather than as two magic constants: what the store holds is
        // milligrams and millimetres, and what makes it correct is that it renders back to the
        // figures that were typed.
        val entry = saved.single()
        assertEquals("172.84", formatWeightValue(requireNotNull(entry.weight), UnitSystem.IMPERIAL))
        assertEquals("40.2", formatLengthValue(requireNotNull(entry.chest), UnitSystem.IMPERIAL))
    }

    @Test
    fun `cancelling saves nothing`() {
        showForm(latestEntry())

        formButton("Cancel").performClick()

        assertTrue(saved.isEmpty())
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun show(
        state: BodyMeasurementsUiState,
        unitSystem: UnitSystem = UnitSystem.METRIC,
    ) {
        composeTestRule.setContent {
            TitanTheme(unitSystem = unitSystem) {
                BodyMeasurementsScreen(
                    state = state,
                    onEvent = { events += it },
                    onBack = {},
                    onOpenHealthConnect = { healthConnectOpened++ },
                )
            }
        }
    }

    private fun showForm(
        entry: BodyMeasurement,
        unitSystem: UnitSystem = UnitSystem.METRIC,
    ) {
        composeTestRule.setContent {
            TitanTheme(unitSystem = unitSystem) {
                MeasurementForm(entry = entry, onSave = { saved += it }, onCancel = {})
            }
        }
    }

    // ─── Process death (#183) ───────────────────────────────────────────────────────────

    @Test
    fun `the figures typed into the form survive the state being restored`() {
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            TitanTheme(unitSystem = UnitSystem.METRIC) {
                MeasurementForm(entry = blankEntry(), onSave = { saved += it }, onCancel = {})
            }
        }
        composeTestRule.onNodeWithText("Weight (kg)").performTextReplacement("77.9")
        composeTestRule.onNodeWithText("Chest (cm)").performTextReplacement("102")

        // The activity is recreated with only its saved state: the fields are `rememberSaveable`
        // and this is what proves it, rather than the comment above them saying so.
        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("77.9").assertIsDisplayed()
        composeTestRule.onNodeWithText("102").assertIsDisplayed()
    }

    /**
     * The form's own footer buttons, scrolled into view first.
     *
     * They sit under seven fields, which is below the fold on the screen a Robolectric test
     * runs at — and a click on a node that is off the window lands nowhere at all rather than
     * failing, which is a test that passes for the wrong reason waiting to happen.
     */
    private fun formButton(text: String): SemanticsNodeInteraction = scrollTo(hasText(text))

    /** Brings [matcher]'s node into the scrolling container and hands it back. */
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
        return composeTestRule.onNode(matcher)
    }

    /**
     * Eight weekly weigh-ins over a cut, with the tape out on the most recent — the shape the
     * screen is drawn for: a weight on every entry and the parts on one.
     */
    private fun populated(): BodyMeasurementsUiState {
        val history = WEIGHTS.mapIndexed { index, grams ->
            measurementOn(
                day = TODAY.minusWeeks(index.toLong()),
                id = index + 1L,
                weight = grams * MILLIGRAMS_PER_GRAM,
                chest = if (index == 0) 1_020 else null,
                waist = if (index == 0) 820 else null,
                bodyFat = if (index == 0) 142 else null,
            )
        }

        return BodyMeasurementsUiState(
            isLoading = false,
            history = history,
            window = history.asReversed(),
        )
    }

    /** The newest entry of [populated] — a weigh-in with the tape out. */
    private fun latestEntry(): BodyMeasurement = populated().history.first()

    /** A new entry with nothing in it, as the form opens for a user who has logged nothing. */
    private fun blankEntry(): BodyMeasurement =
        BodyMeasurement(id = NEW_ENTRY, createdAt = TAKEN_AT, updatedAt = TAKEN_AT)

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 4, 28)
        val TAKEN_AT: Long = middayMillis(TODAY)

        /** What a gram is, in the milligrams a weight is stored in. */
        const val MILLIGRAMS_PER_GRAM = 1_000L

        /** Grams, newest first: a cut of a kilo and a half over eight weeks. */
        val WEIGHTS = listOf(78_400L, 79_100L, 78_800L, 79_000L, 79_500L, 80_100L, 79_800L, 80_000L)
    }
}
