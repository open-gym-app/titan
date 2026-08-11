package com.opengym.feature.history.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The library screen, driven from hand-built states.
 *
 * The stateless half only — no ViewModel, no Hilt, no Room. Every case below is "given this
 * state, is *this* drawn and does tapping it report *that*", which is the whole of what a
 * screen is responsible for once the filtering is somebody else's job (see
 * [ExerciseLibraryGroupingTest] and [ExerciseLibraryViewModelTest] for the somebody else).
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseLibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ExerciseLibraryEvent>()

    /**
     * The library as the Library tab draws it: no back arrow, and the shell's bars around it
     * rather than its own. [setPicker] is the other half.
     */
    private fun setContent(
        state: ExerciseLibraryUiState = browsing(),
        onExerciseClick: ((Long) -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                ExerciseLibraryScreen(
                    state = state,
                    onEvent = events::add,
                    onExerciseClick = onExerciseClick,
                )
            }
        }
    }

    /** The same screen as the picker: its own top bar, a way back, and a confirm bar. */
    private fun setPicker(
        state: ExerciseLibraryUiState = browsing(),
        onExerciseClick: ((Long) -> Unit)? = null,
        onSelectionConfirmed: (List<Long>) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                ExerciseLibraryScreen(
                    state = state,
                    onEvent = events::add,
                    onBack = onBack,
                    selecting = true,
                    onExerciseClick = onExerciseClick,
                    onSelectionConfirmed = onSelectionConfirmed,
                )
            }
        }
    }

    // ─── What is drawn ──────────────────────────────────────────────────────────────────

    @Test
    fun `sections are headed by their muscle group and their count`() {
        setContent()

        // `Eyebrow` uppercases for the caller, so the drawn text is the shouted form. The
        // count is part of the same node, which is also what distinguishes the heading from
        // the muscle chip of the same name.
        composeTestRule.onNodeWithText("CHEST · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench press").assertIsDisplayed()

        // The second section is below the fold on a phone, which is the point of the list
        // being lazy — scroll to it rather than assuming it was composed.
        library().performScrollToNode(hasText("LATS · 1"))
        composeTestRule.onNodeWithText("LATS · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pull-up").assertIsDisplayed()
    }

    @Test
    fun `the count in the header is the size of the catalogue when nothing is filtered`() {
        setContent()

        composeTestRule.onNodeWithText("3 EXERCISES").assertIsDisplayed()
    }

    @Test
    fun `a filtered library says how much of it is left`() {
        setContent(browsing().copy(query = "fly", matchCount = 1))

        composeTestRule.onNodeWithText("1 OF 3").assertIsDisplayed()
    }

    @Test
    fun `a record is drawn on the row it belongs to`() {
        setContent(browsing(record = BENCH_RECORD))

        // `PrValue` replaces its own text with a description, so that a screen reader says
        // what the figure is rather than reading "PR" and "92 kg" as two fragments.
        composeTestRule
            .onNodeWithContentDescription("Personal record, Heaviest, 92 kg")
            .assertIsDisplayed()
    }

    @Test
    fun `an exercise the catalogue links to nothing is still listed`() {
        setContent(
            browsing().copy(
                sections = listOf(
                    ExerciseSection(muscleGroup = null, exercises = listOf(LibraryExercise(BENCH))),
                ),
            ),
        )

        composeTestRule.onNodeWithText("OTHER · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench press").assertIsDisplayed()
    }

    // ─── Search and filters ─────────────────────────────────────────────────────────────

    @Test
    fun `the controls are two buttons rather than a screenful of chrome`() {
        setContent()

        composeTestRule.onNodeWithContentDescription(SEARCH).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(FILTERS).assertIsDisplayed()

        // Neither control costs the list anything until it is asked for: what used to be the
        // first 130dp of the `LazyColumn` is now a glyph each.
        composeTestRule.onNodeWithText("Search exercises…").assertDoesNotExist()
        composeTestRule.onNodeWithText("MUSCLE").assertDoesNotExist()
    }

    @Test
    fun `the search button opens a box that reports what is typed`() {
        setContent()

        composeTestRule.onNodeWithContentDescription(SEARCH).performClick()
        composeTestRule.onNodeWithText("Search exercises…").performTextInput("fly")

        assertEquals(listOf(ExerciseLibraryEvent.QueryChanged("fly")), events)
    }

    @Test
    fun `closing the search box empties it rather than hiding what it holds`() {
        setContent(browsing().copy(query = "fly"))

        composeTestRule.onNodeWithContentDescription(SEARCH).performClick()
        composeTestRule.onNodeWithText("fly").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(SEARCH).performClick()

        // Otherwise the list stays narrowed with the only thing that could say why now shut.
        assertEquals(listOf(ExerciseLibraryEvent.QueryChanged("")), events)
        composeTestRule.onNodeWithText("Search exercises…").assertDoesNotExist()
    }

    @Test
    fun `the filter button opens the sheet with every filter in it`() {
        setContent()

        composeTestRule.onNodeWithContentDescription(FILTERS).performClick()

        // The sheet's own headings. `LibraryFilterPanelTest` covers what the chips do; this
        // is the wiring — the button, the sheet, and the state reaching it.
        composeTestRule.onNodeWithText("MUSCLE").assertIsDisplayed()
        composeTestRule.onNodeWithText("EQUIPMENT").assertIsDisplayed()
        composeTestRule.onNodeWithText("LATS").assertIsDisplayed()
    }

    // ─── Empty states ───────────────────────────────────────────────────────────────────

    @Test
    fun `a filtered library with no matches offers a way out`() {
        setContent(
            browsing().copy(query = "sumo", sections = emptyList(), matchCount = 0),
        )

        composeTestRule.onNodeWithText("Nothing matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("CLEAR FILTERS").performClick()

        // Everything, unlike the filter sheet's own clear: this state has just told the user
        // that nothing matches their search *and* filters, so it drops both.
        assertEquals(listOf(ExerciseLibraryEvent.SearchAndFiltersCleared), events)
    }

    @Test
    fun `an empty catalogue says so rather than blaming the filters`() {
        setContent(ExerciseLibraryUiState(isLoading = false))

        composeTestRule.onNodeWithText("The library is empty").assertIsDisplayed()
    }

    @Test
    fun `nothing is drawn while the first read is in flight`() {
        setContent(ExerciseLibraryUiState())

        composeTestRule.onNodeWithText("The library is empty").assertDoesNotExist()
        composeTestRule.onNodeWithText("Nothing matches").assertDoesNotExist()
    }

    // ─── Browsing ───────────────────────────────────────────────────────────────────────

    @Test
    fun `tapping a row in browse mode opens the exercise`() {
        val opened = mutableListOf<Long>()
        setContent(onExerciseClick = opened::add)

        composeTestRule.onNodeWithText("Bench press").performClick()

        assertEquals(listOf(BENCH.id), opened)
    }

    @Test
    fun `the new exercise action opens the form`() {
        setContent()

        composeTestRule.onNodeWithText("+ NEW").performClick()

        assertEquals(listOf(ExerciseLibraryEvent.NewExerciseRequested), events)
    }

    // ─── Selecting ──────────────────────────────────────────────────────────────────────

    @Test
    fun `tapping a row in selection mode toggles it rather than opening it`() {
        val opened = mutableListOf<Long>()
        setPicker(onExerciseClick = opened::add)

        composeTestRule.onNodeWithText("Bench press").performClick()

        assertEquals(listOf(ExerciseLibraryEvent.ExerciseToggled(BENCH.id)), events)
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun `the confirm bar counts the selection and hands it back in pick order`() {
        val confirmed = mutableListOf<List<Long>>()
        setPicker(
            state = browsing().copy(selection = listOf(PULL_UP.id, BENCH.id)),
            onSelectionConfirmed = confirmed::add,
        )

        composeTestRule.onNodeWithText("Add 2 exercises").performClick()

        assertEquals(listOf(listOf(PULL_UP.id, BENCH.id)), confirmed)
    }

    @Test
    fun `the confirm bar is inert with nothing selected`() {
        setPicker()

        composeTestRule.onNodeWithText("2 SELECTED").assertDoesNotExist()
        composeTestRule.onNodeWithText("Select exercises").assertIsNotEnabled()
    }

    @Test
    fun `selection mode says what it is for`() {
        setPicker(state = browsing().copy(selection = listOf(BENCH.id)))

        composeTestRule.onNodeWithText("Add exercises").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 SELECTED").assertIsDisplayed()
    }

    @Test
    fun `the picker has a way back and the tab does not`() {
        setPicker()

        composeTestRule.onNodeWithContentDescription(BACK).assertIsDisplayed()
    }

    @Test
    fun `negative - the tab draws no back button`() {
        setContent()

        // The library is a tab, and the bottom bar is the way out of it. A back arrow above a
        // tab either does nothing or drops the user out of the tab they just chose.
        composeTestRule.onNodeWithContentDescription(BACK).assertDoesNotExist()
    }

    /**
     * The list itself, which is the first scrollable in the tree — the two after it are the
     * chip rows inside it.
     */
    private fun library() = composeTestRule.onAllNodes(hasScrollAction()).onFirst()

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun browsing(record: PersonalRecord? = null) =
        ExerciseLibraryUiState(
            muscleGroups = TAXONOMY,
            equipmentTypes = listOf(EquipmentType.BARBELL, EquipmentType.CABLE),
            sections = listOf(
                ExerciseSection(
                    muscleGroup = CHEST,
                    exercises = listOf(
                        LibraryExercise(BENCH, CATALOGUE_LINKS.getValue(BENCH.id), record),
                        LibraryExercise(FLY, CATALOGUE_LINKS.getValue(FLY.id)),
                    ),
                ),
                ExerciseSection(
                    muscleGroup = LATS,
                    exercises = listOf(LibraryExercise(PULL_UP, CATALOGUE_LINKS.getValue(PULL_UP.id))),
                ),
            ),
            catalogueSize = CATALOGUE.size,
            matchCount = CATALOGUE.size,
            isLoading = false,
        )

    private companion object {
        /** By the description a screen reader reads, which is the whole of their labelling. */
        const val BACK = "Back"
        const val SEARCH = "Search"
        const val FILTERS = "Filters"
    }
}
