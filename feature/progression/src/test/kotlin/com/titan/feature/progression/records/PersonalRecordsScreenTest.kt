package com.titan.feature.progression.records

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import com.titan.core.domain.model.PaceStyle
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.paceOf
import com.titan.core.ui.theme.TitanTheme
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
    fun `every record type has a chip, including the timed ones`() {
        setContent(PersonalRecordsUiState(totalRecords = 4, isLoading = false))

        // Eight kinds plus ALL. A record with no chip to find it under is a record the user cannot
        // reach, which is why the wireframe's four are not enough. The row scrolls, so each chip
        // is scrolled to by its key — the kind's name — before it is looked for.
        // Case-insensitively: the chip uppercases what it is given, as an eyebrow does.
        composeTestRule.onNodeWithText("All", ignoreCase = true).assertIsDisplayed()
        // The one list that scrolls sideways: the records under it scroll down.
        val chips = composeTestRule.onNode(
            hasScrollToKeyAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
        )
        mapOf(
            PrType.MAX_WEIGHT to "Heaviest",
            PrType.MAX_REPS to "Most reps",
            PrType.MAX_VOLUME to "Most volume",
            PrType.ESTIMATED_1RM to "1RM",
            PrType.MAX_DISTANCE to "Furthest",
            // The chip covers runs and rides alike, so it is neither "Best pace" nor "Top speed".
            PrType.BEST_PACE to "Fastest",
            PrType.MAX_DURATION to "Longest",
            PrType.MAX_WEIGHTED_HOLD to "Weighted hold",
        ).forEach { (type, label) ->
            chips.performScrollToKey(type.name)
            composeTestRule.onNodeWithText(label, ignoreCase = true).assertIsDisplayed()
        }
    }

    @Test
    fun `a timed record reads in its exercise's own terms, and improves by its pace`() {
        setContent(
            state = PersonalRecordsUiState(totalRecords = 3, isLoading = false),
            entries = timedEntries(),
        )

        // A run's pace beat the last one by seventeen seconds a kilometre — a minus, because a
        // pace beats by going down — though the run that set it was twice as long.
        composeTestRule
            .onNodeWithContentDescription("Running, Outdoor, Best pace, 5:10 /km", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Running, Outdoor", substring = true)
            .assertContentDescriptionContains("\u22120:17 /km", substring = true)
        // The same kind on a ride is its top speed, and nothing on the row is a pace.
        composeTestRule
            .onNodeWithContentDescription("Bicycling, Top speed, 30.0 km/h", substring = true)
            .assertIsDisplayed()
        // A weighted hold is its two parts, never the product.
        composeTestRule
            .onNodeWithContentDescription("Plank, Weighted hold, 1:30 · +20 kg", substring = true)
            .assertIsDisplayed()
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

    /** A run, a ride and a hold, each the newest of its kind (#225). */
    private fun timedEntries(): List<PersonalRecordEntry> {
        fun entry(
            id: Long,
            name: String,
            record: PersonalRecord,
            previous: Long?,
        ) = PersonalRecordEntry(
            record = record.copy(id = id, exerciseId = id, workoutSetId = id, achievedAt = 1_776_729_600_000L),
            exerciseName = name,
            previousValue = previous,
            isRecent = true,
        )
        val blank = PersonalRecord(exerciseId = 0, workoutSetId = 0, type = PrType.BEST_PACE, achievedAt = 0)
        return listOf(
            // 10 km at 5:10 /km, after 5 km at 5:27 /km.
            entry(
                id = 11,
                name = "Running, Outdoor",
                record = blank.copy(distance = 1_000_000, duration = 3_100),
                previous = paceOf(durationSeconds = 1_634, distanceCentimetres = 500_000),
            ),
            // 40 km in 1:20:00, read as a speed.
            entry(
                id = 12,
                name = "Bicycling",
                record = blank.copy(distance = 4_000_000, duration = 4_800, paceStyle = PaceStyle.SPEED),
                previous = null,
            ),
            // 20 kg held for a minute and a half.
            entry(
                id = 13,
                name = "Plank",
                record = blank.copy(type = PrType.MAX_WEIGHTED_HOLD, weight = 20_000_000L * 90, duration = 90),
                previous = null,
            ),
        )
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
            TitanTheme {
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
