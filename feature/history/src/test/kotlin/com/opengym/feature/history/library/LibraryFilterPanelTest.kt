package com.opengym.feature.history.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.library.component.LibraryFilterPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The filter sheet's contents, driven directly.
 *
 * The panel rather than `LibraryFilterSheet`, for the reason [com.opengym.feature.history.exercise.ExerciseFormTest] drives the
 * form rather than its sheet: what is under test is which chips are drawn and what tapping one
 * reports, and a `ModalBottomSheet`'s animation and window are neither.
 *
 * Every chip is asserted to be *displayed*, not merely to exist, because the whole point of
 * this panel is that the vocabulary wraps instead of scrolling sideways — a version that put
 * these back in a `LazyRow` would compose two chips and pass an existence check.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFilterPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ExerciseLibraryEvent>()
    private var doneCount = 0

    private fun setContent(state: ExerciseLibraryUiState = filters()) {
        composeTestRule.setContent {
            OpenGymTheme {
                LibraryFilterPanel(
                    state = state,
                    onEvent = events::add,
                    onDone = { doneCount++ },
                )
            }
        }
    }

    // ─── What is drawn ──────────────────────────────────────────────────────────────────

    @Test
    fun `both vocabularies are headed and shown whole`() {
        setContent()

        composeTestRule.onNodeWithText("MUSCLE").assertIsDisplayed()
        composeTestRule.onNodeWithText("EQUIPMENT").assertIsDisplayed()

        // The last muscle chip and the last equipment chip, with no scrolling in between:
        // this is the regression the sideways-scrolling rows could not have passed.
        composeTestRule.onNodeWithText("TRICEPS").assertIsDisplayed()
        composeTestRule.onNodeWithText("CABLE").assertIsDisplayed()
    }

    @Test
    fun `the muscle section offers a way back to everything`() {
        setContent(filters().copy(selectedMuscleGroupId = CHEST.id))

        composeTestRule.onNodeWithText("ALL").performClick()

        assertEquals(listOf(ExerciseLibraryEvent.MuscleFilterSelected(null)), events)
    }

    @Test
    fun `the done button says how many exercises are left`() {
        setContent(filters().copy(matchCount = 12))

        composeTestRule.onNodeWithText("Show 12 exercises").performClick()

        assertEquals(1, doneCount)
        // Filters apply as they are tapped, so the button confirms nothing — it is a count
        // that happens to also be the way out.
        assertEquals(emptyList<ExerciseLibraryEvent>(), events)
    }

    // ─── Tapping chips ──────────────────────────────────────────────────────────────────

    @Test
    fun `tapping a muscle chip reports it`() {
        setContent()

        composeTestRule.onNodeWithText("LATS").performClick()

        assertEquals(listOf(ExerciseLibraryEvent.MuscleFilterSelected(LATS.id)), events)
    }

    @Test
    fun `tapping the selected muscle chip clears the filter`() {
        setContent(filters().copy(selectedMuscleGroupId = LATS.id))

        composeTestRule.onNodeWithText("LATS").performClick()

        assertTrue(ExerciseLibraryEvent.MuscleFilterSelected(null) in events)
    }

    @Test
    fun `tapping an equipment chip reports a toggle`() {
        setContent()

        composeTestRule.onNodeWithText("BARBELL").performClick()

        assertEquals(
            listOf(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.BARBELL)),
            events,
        )
    }

    // ─── Clearing ───────────────────────────────────────────────────────────────────────

    @Test
    fun `clearing drops both chip filters and leaves the search alone`() {
        setContent(
            filters().copy(
                query = "bench",
                selectedMuscleGroupId = CHEST.id,
                selectedEquipment = setOf(EquipmentType.BARBELL),
            ),
        )

        composeTestRule.onNodeWithText("CLEAR").performClick()

        // Not `SearchAndFiltersCleared`: this sheet does not show the search box, and emptying
        // it from here would lose the user's typing with nothing on screen saying why.
        assertEquals(listOf(ExerciseLibraryEvent.FiltersReset), events)
    }

    @Test
    fun `negative - there is nothing to clear when no chip is on`() {
        setContent(filters().copy(query = "bench"))

        // A query is not a chip filter. A clear button offered for it would either do nothing
        // visible or silently empty a box this sheet is not showing.
        composeTestRule.onNodeWithText("CLEAR").assertDoesNotExist()
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun filters() = ExerciseLibraryUiState(
        muscleGroups = TAXONOMY,
        equipmentTypes = listOf(EquipmentType.BARBELL, EquipmentType.CABLE),
        catalogueSize = CATALOGUE.size,
        matchCount = CATALOGUE.size,
        isLoading = false,
    )
}
