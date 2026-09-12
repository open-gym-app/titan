package com.opengym.feature.progression.records

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.ui.theme.OpenGymTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The records list, driven by hand-built state.
 *
 * The stateless half, per CONTRIBUTING.md § Screens — no ViewModel and no Hilt graph. What is
 * asserted here is what the screen *draws and reports*; whether the rows are the right ones for a
 * given filter is `PersonalRecordsPageDaoTest`'s question, and whether the list pages is
 * `PersonalRecordsPagingSourceTest`'s.
 *
 * The rows come from `previewRecordEntries()`, the same fixture the `@Preview`s render.
 */
@RunWith(RobolectricTestRunner::class)
// A phone's worth of window: Robolectric's default is 320x470dp, on which a four-row list does
// not fit and every assertion becomes a scroll first.
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PersonalRecordsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<PersonalRecordsEvent>()
    private val opened = mutableListOf<Long>()

    @Test
    fun `lists every record with its exercise, kind, figure and improvement`() {
        setContent(PersonalRecordsUiState(totalRecords = 4, isLoading = false))

        // One node per row, spoken as one sentence — see `PersonalRecordEntryRow`. The `NEW` badge is
        // in it: clearing the row's semantics would otherwise leave it visible only to people who
        // can see it.
        composeTestRule
            .onNodeWithContentDescription("Bench press, Heaviest, 92 kg, Apr 21, New, +12 kg")
            .assertIsDisplayed()
    }

    @Test
    fun `a first record of its kind shows no improvement`() {
        setContent(PersonalRecordsUiState(totalRecords = 4, isLoading = false))

        // The most-reps row beat nothing, so its sentence carries no improvement at all. `+0 reps`
        // would be the screen crediting a record the user never held.
        composeTestRule
            .onNodeWithContentDescription("Bench press, Most reps, 12 reps, Apr 21, New")
            .assertIsDisplayed()
    }

    @Test
    fun `a record whose exercise is gone still lists, under a fallback name`() {
        setContent(PersonalRecordsUiState(totalRecords = 4, isLoading = false))

        // Found by the row's spoken sentence rather than by its text: the row replaces its
        // semantics with one description, which is the behaviour being relied on here.
        composeTestRule
            .onNodeWithContentDescription("Removed exercise", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the header counts every record, whatever the filter`() {
        setContent(PersonalRecordsUiState(filter = PrType.MAX_REPS, totalRecords = 4, isLoading = false))

        // Four, not the one the filter would leave: a filter narrows what is listed rather than
        // what the user holds.
        composeTestRule.onNodeWithText("4 RECORDS").assertIsDisplayed()
    }

    @Test
    fun `every record type has a chip, including the cardio ones`() {
        setContent(PersonalRecordsUiState(totalRecords = 4, isLoading = false))

        // Six kinds plus ALL. A record with no chip to find it under is a record the user cannot
        // reach, which is why the wireframe's four are not enough.
        // Case-insensitively: the chip uppercases what it is given, as an eyebrow does.
        listOf("Heaviest", "Most reps", "Most volume", "1RM", "Furthest", "Best pace").forEach { label ->
            composeTestRule.onNodeWithText(label, ignoreCase = true).assertIsDisplayed()
        }
        composeTestRule.onNodeWithText("All", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun `choosing a chip reports the kind it stands for`() {
        setContent(PersonalRecordsUiState(totalRecords = 4, isLoading = false))

        composeTestRule.onNodeWithText("Most reps", ignoreCase = true).performClick()

        assertEquals(listOf(PersonalRecordsEvent.FilterSelected(PrType.MAX_REPS)), events)
    }

    @Test
    fun `the all chip clears the filter`() {
        setContent(PersonalRecordsUiState(filter = PrType.MAX_REPS, totalRecords = 4, isLoading = false))

        composeTestRule.onNodeWithText("All", ignoreCase = true).performClick()

        assertEquals(listOf(PersonalRecordsEvent.FilterSelected(null)), events)
    }

    @Test
    fun `tapping a record opens the exercise it was set on`() {
        setContent(PersonalRecordsUiState(totalRecords = 4, isLoading = false))

        composeTestRule.onNodeWithContentDescription("Back squat", substring = true).performClick()

        assertEquals(listOf(2L), opened)
    }

    @Test
    fun `a user with no records is told to go and train`() {
        setContent(
            state = PersonalRecordsUiState(totalRecords = 0, isLoading = false),
            entries = emptyList(),
        )

        composeTestRule.onNodeWithText("No records yet").assertIsDisplayed()
    }

    @Test
    fun `a filter that matches nothing is a different message`() {
        // Records exist; this kind has none. Sending the user to the gym over a chip they can tap
        // would be the screen misreading its own list.
        setContent(
            state = PersonalRecordsUiState(filter = PrType.BEST_PACE, totalRecords = 4, isLoading = false),
            entries = emptyList(),
        )

        composeTestRule.onNodeWithText("Nothing of that kind yet").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("No records yet").assertCountEquals(0)
    }

    @Test
    fun `a page that could not be read says so, and offers a way to ask again`() {
        // The bug this pins: `itemCount == 0` is also true of a failed load, so a screen that
        // only asks whether the list is empty draws neither empty state and falls through to an
        // empty `LazyColumn` - a blank page with no message and nothing to tap.
        setContent(
            state = PersonalRecordsUiState(totalRecords = 4, isLoading = false),
            entries = emptyList(),
            refresh = LoadState.Error(IllegalStateException("the store said no")),
        )

        composeTestRule.onNodeWithText("Could not load your records").assertIsDisplayed()
        composeTestRule.onNodeWithText("TRY AGAIN").assertIsDisplayed()
        // And it is not mistaken for a filter that matched nothing.
        composeTestRule.onAllNodesWithText("Nothing of that kind yet").assertCountEquals(0)
    }

    @Test
    fun `a user with no records is told to train, even if the page also failed`() {
        // The total comes from its own query. Zero records is zero records whether or not the
        // page read failed, and "go and train" is the truer of the two things to say.
        setContent(
            state = PersonalRecordsUiState(totalRecords = 0, isLoading = false),
            entries = emptyList(),
            refresh = LoadState.Error(IllegalStateException("the store said no")),
        )

        composeTestRule.onNodeWithText("No records yet").assertIsDisplayed()
    }

    @Test
    fun `a page that failed below the rows leaves the rows and adds a footer`() {
        // An append failure without this is indistinguishable from the end of the history: the
        // list simply stops, and the user is told they have no older records rather than that we
        // could not read them.
        setContent(
            state = PersonalRecordsUiState(totalRecords = 4, isLoading = false),
            append = LoadState.Error(IllegalStateException("the store said no")),
        )

        composeTestRule.onNodeWithText("Could not load more records.").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Bench press, Heaviest, 92 kg, Apr 21, New, +12 kg")
            .assertIsDisplayed()
    }

    private fun setContent(
        state: PersonalRecordsUiState,
        entries: List<PersonalRecordEntry> = previewRecordEntries(),
        // Load states stated explicitly: an empty list means "this filter matches nothing" only
        // once the refresh has *settled*, and a fixture that left the state at Loading would be
        // testing the spinner instead. They are parameters because three of this screen's five
        // states are decided by them rather than by the rows.
        refresh: LoadState = LoadState.NotLoading(endOfPaginationReached = true),
        append: LoadState = LoadState.NotLoading(endOfPaginationReached = true),
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                PersonalRecordsScreen(
                    state = state,
                    records = flowOf(
                        PagingData.from(
                            data = entries,
                            sourceLoadStates = LoadStates(
                                refresh = refresh,
                                prepend = LoadState.NotLoading(endOfPaginationReached = true),
                                append = append,
                            ),
                        ),
                    ),
                    onEvent = events::add,
                    onBack = {},
                    onOpenExercise = opened::add,
                )
            }
        }
    }
}
