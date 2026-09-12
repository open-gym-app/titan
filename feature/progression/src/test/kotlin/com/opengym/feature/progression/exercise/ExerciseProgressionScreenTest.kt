package com.opengym.feature.progression.exercise

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.opengym.core.domain.model.ExerciseEstimate
import com.opengym.core.domain.model.ExerciseProgression
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.TrainedExercise
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.stats.StatsTimeframe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The per-exercise drill-down, driven from hand-built state.
 *
 * The stateless half, with no ViewModel and no Hilt graph behind it — the split
 * CONTRIBUTING.md § Screens exists for. What the cards *look* like is the screenshot
 * references' job; this is about what the screen says, what it announces, and what it emits.
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseProgressionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ExerciseProgressionEvent>()
    private var backPresses = 0

    // ─── The populated screen ───────────────────────────────────────────────────────────

    @Test
    fun `a lift with a trend draws its name, its records and its two charts`() {
        show(populated())

        composeTestRule.onNodeWithText("Bench Press").assertIsDisplayed()
        // The records are all-time and come off the PR engine's own rows, rendered the way a
        // record renders anywhere else in the app.
        composeTestRule
            .onNodeWithContentDescription("1RM (est), New, 102 kg")
            .performScrollTo()
        composeTestRule
            .onNodeWithContentDescription("Max set, 85 kg")
            .performScrollTo()
        composeTestRule
            .onNodeWithContentDescription("Best vol, 1,400 kg / session")
            .performScrollTo()
        composeTestRule
            .onNodeWithContentDescription("Total sets, 142 all-time")
            .performScrollTo()
    }

    @Test
    fun `a kind of record the lift has none of draws a dash rather than a zero`() {
        show(populated(records = mapOf(PrType.MAX_WEIGHT to MAX_WEIGHT_RECORD)))

        // `0 kg` is a lift somebody made, and nothing here has been — so the caption goes too.
        composeTestRule.onNodeWithContentDescription("1RM (est), —").performScrollTo()
    }

    @Test
    fun `both charts are announced as one readable series rather than as their marks`() {
        show(populated())

        // #144's criterion reaching the screen: each chart carries its own sentence built from
        // its own series, and the individual marks are not announced.
        composeTestRule
            .onNodeWithContentDescription("1RM estimate, Apr to Apr. Latest 102 kg.", substring = true)
            .performScrollTo()
        composeTestRule
            .onNodeWithContentDescription("Volume per session, Apr to Apr. Latest 1,400 kg.", substring = true)
            .performScrollTo()
    }

    @Test
    fun `touching a chart says which session the mark was, by its date`() {
        show(populated())

        // The two charts are per *session*, so their axis labels are both `Apr` and the month they
        // share cannot say which mark a thumb landed on. `ChartEntry.caption` is the day, and
        // this is the wiring of it — the tooltip's own behaviour is `ChartTooltipTest`.
        composeTestRule
            .onNodeWithContentDescription("Volume per session, Apr to Apr.", substring = true)
            .performScrollTo()
            .performTouchInput { click() }

        // Uppercase because the tooltip reads its date in the eyebrow role, which is drawn
        // shouted and announced as written.
        composeTestRule.onNodeWithText("APR 28").assertIsDisplayed()
    }

    @Test
    fun `the table reads newest first and names the session that broke a record`() {
        show(populated())

        // The charts run oldest first and the table the other way up, which is the one thing
        // the two orders have to agree about.
        composeTestRule
            .onNodeWithContentDescription(
                "4 sets, top set 85 kg × 5, personal record, volume 1,400 kg",
                substring = true,
            )
            .performScrollTo()
        composeTestRule
            .onNodeWithContentDescription("4 sets, top set 80 kg × 5, volume 1,200 kg", substring = true)
            .performScrollTo()
    }

    // ─── The timeframe selector ─────────────────────────────────────────────────────────

    @Test
    fun `the window it is reading over is the taken segment, and tapping asks for another`() {
        show(populated(timeframe = StatsTimeframe.QUARTER))

        composeTestRule.onNodeWithContentDescription("Last 90 days").assertIsSelected()
        composeTestRule.onNodeWithText("1Y").performClick()

        assertEquals(
            listOf(ExerciseProgressionEvent.TimeframeSelected(StatsTimeframe.YEAR)),
            events,
        )
    }

    // ─── The empty states ───────────────────────────────────────────────────────────────

    @Test
    fun `one session draws the records and the attempt, and no chart at all`() {
        show(populated(attempts = 1))

        composeTestRule.onNodeWithText("One session so far").performScrollTo()
        // The records survive: a single session is not a reason to stop reporting a best.
        composeTestRule.onNodeWithContentDescription("Total sets, 142 all-time").performScrollTo()
        // And the one attempt is still worth a row.
        composeTestRule
            .onNodeWithContentDescription("4 sets, top set 85 kg × 5", substring = true)
            .performScrollTo()
    }

    @Test
    fun `a window with nothing in it offers a wider one rather than telling a lifter to train`() {
        show(populated(attempts = 0, timeframe = StatsTimeframe.WEEK))

        composeTestRule.onNodeWithText("Nothing in this window").performScrollTo()
        composeTestRule.onNodeWithContentDescription("Total sets, 142 all-time").performScrollTo()

        // By description rather than by text: `Eyebrow` uppercases what it draws and hands a
        // screen reader the sentence-case original, which is the string worth asserting on.
        composeTestRule.onNodeWithContentDescription("Show all time").performScrollTo().performClick()

        assertEquals(
            listOf(ExerciseProgressionEvent.TimeframeSelected(StatsTimeframe.ALL)),
            events,
        )
    }

    @Test
    fun `a lift never logged says so instead of drawing four empty records`() {
        show(populated(attempts = 0, records = emptyMap(), totalSets = 0))

        composeTestRule.onNodeWithText("Not logged yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recent attempts").assertDoesNotExist()
    }

    // ─── The chooser ────────────────────────────────────────────────────────────────────

    @Test
    fun `with no lift chosen it lists what has been trained, and picking one reports it`() {
        show(ExerciseProgressionUiState(isLoading = false, choices = CHOICES))

        composeTestRule.onNodeWithText("Exercise progression").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back Squat").assertIsDisplayed()

        composeTestRule.onNodeWithText("Bench Press").performClick()

        assertEquals(listOf(ExerciseProgressionEvent.ExerciseSelected(BENCH_ID)), events)
    }

    @Test
    fun `the switcher is offered on a lift and not on the chooser`() {
        show(populated())

        composeTestRule.onNodeWithContentDescription("Switch lift").performClick()

        assertEquals(listOf(ExerciseProgressionEvent.ExerciseCleared), events)
    }

    @Test
    fun `a user who has logged nothing is told what to do rather than shown an empty list`() {
        show(ExerciseProgressionUiState(isLoading = false))

        composeTestRule.onNodeWithText("No lifts logged yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your lifts").assertDoesNotExist()
    }

    @Test
    fun `nothing is drawn under the bar while the reads are in flight`() {
        show(ExerciseProgressionUiState())

        // Not a skeleton: it would be grey boxes turning into an empty state for exactly the
        // user most likely to be waiting on a cold database.
        composeTestRule.onNodeWithText("Your lifts").assertDoesNotExist()
        composeTestRule.onNodeWithText("Recent attempts").assertDoesNotExist()
    }

    @Test
    fun `the bar goes back`() {
        show(populated())

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backPresses)
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun show(state: ExerciseProgressionUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                ExerciseProgressionScreen(
                    state = state,
                    onEvent = { events += it },
                    onBack = { backPresses++ },
                )
            }
        }
    }

    /**
     * A bench press with [attempts] sessions behind it in the window, and its all-time bests.
     *
     * The figures are round on purpose — 80 and 85 kilograms, 1,200 and 1,400 kilogram-reps —
     * so the assertions above read as the strings a user sees rather than as arithmetic.
     */
    private fun populated(
        attempts: Int = 2,
        timeframe: StatsTimeframe = StatsTimeframe.DEFAULT,
        records: Map<PrType, com.opengym.core.domain.model.PersonalRecord> = RECORDS,
        totalSets: Int = 142,
    ): ExerciseProgressionUiState {
        val performed = ATTEMPTS.takeLast(attempts)
        return ExerciseProgressionUiState(
            timeframe = timeframe,
            isLoading = false,
            exercise = BENCH,
            choices = CHOICES,
            progression = ExerciseProgression(
                attempts = performed,
                estimates = performed.map { attempt ->
                    ExerciseEstimate(
                        finishedAt = attempt.finishedAt,
                        // Round figures rather than the formula's own output: what is under
                        // test is what the chart says, not what the domain computed.
                        estimate = if (attempt.sessionId == 1L) kg(96) else kg(102),
                    )
                },
                records = records,
                // Only a kind the lift actually has a record of can be a new one.
                newRecords = records.keys intersect setOf(PrType.ESTIMATED_1RM),
                totalSets = totalSets,
            ),
        )
    }

    private companion object {
        /** A Tuesday in April, so both attempts label as `Apr` on the axis. */
        val LAST_SESSION: LocalDate = LocalDate.of(2026, 4, 28)

        val ATTEMPTS = listOf(
            attemptOn(
                LAST_SESSION.minusWeeks(1),
                sessionId = 1,
                volume = kg(1_200),
                weight = kg(80),
                reps = 5,
            ),
            attemptOn(
                LAST_SESSION,
                sessionId = 2,
                volume = kg(1_400),
                weight = kg(85),
                reps = 5,
            ),
        )

        val MAX_WEIGHT_RECORD = recordOf(
            PrType.MAX_WEIGHT,
            achievedAt = ATTEMPTS.last().firstSetAt,
            weight = kg(85),
        )

        val RECORDS = mapOf(
            PrType.ESTIMATED_1RM to recordOf(
                PrType.ESTIMATED_1RM,
                achievedAt = ATTEMPTS.last().firstSetAt,
                estimated1Rm = kg(102),
            ),
            PrType.MAX_WEIGHT to MAX_WEIGHT_RECORD,
            PrType.MAX_VOLUME to recordOf(
                PrType.MAX_VOLUME,
                achievedAt = ATTEMPTS.first().firstSetAt,
                weight = kg(1_400),
            ),
        )

        val CHOICES = listOf(
            TrainedExercise(
                exerciseId = BENCH_ID,
                name = "Bench Press",
                sets = 142,
                lastPerformedAt = ATTEMPTS.last().finishedAt,
            ),
            TrainedExercise(
                exerciseId = SQUAT_ID,
                name = "Back Squat",
                sets = 128,
                lastPerformedAt = ATTEMPTS.first().finishedAt,
            ),
        )
    }
}
