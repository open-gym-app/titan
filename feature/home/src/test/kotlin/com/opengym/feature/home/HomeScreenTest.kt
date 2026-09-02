package com.opengym.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WeeklyVolume
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.ui.component.WeekDay
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What Home draws, and what it reports, driven by hand-built state.
 *
 * The stateless half, per CONTRIBUTING.md § Screens — no ViewModel and no Hilt graph. Whether
 * the right rows come out of the database is `HomeViewModelTest`'s question; this one is about
 * the four things a user can do from this screen and the two blocks that must not appear when
 * there is nothing to put in them.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<HomeEvent>()
    private val resumed = mutableListOf<Long>()
    private val startedFromTemplate = mutableListOf<Long>()
    private var startedEmpty = 0
    private var openedTemplates = 0
    private var openedSessions = 0
    private var openedRecords = 0
    private var openedStats = 0
    private var openedQuestLog = 0

    // ─── The resume banner ──────────────────────────────────────────────────────────────

    @Test
    fun `an open workout is drawn with its clock and where it was left`() {
        setContent(state(resume = RUNNING))

        composeTestRule.onNodeWithText("Push Day · Upper").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench press · set 3").assertIsDisplayed()
    }

    @Test
    fun `the banner is one sentence, not a clock on its own`() {
        setContent(state(resume = RUNNING))

        // Tapping the card makes it a single node to a screen reader, and a merged node with
        // a description in it is announced as that description and nothing else — so the
        // card's own has to carry everything the card draws. See ResumeCard.
        composeTestRule
            .onNodeWithContentDescription("Running · 23:14, Push Day · Upper, Bench press · set 3")
            .assertIsDisplayed()
    }

    @Test
    fun `a paused workout says so rather than looking like a stopped clock`() {
        setContent(state(resume = RUNNING.copy(isPaused = true)))

        composeTestRule
            .onNodeWithContentDescription("Paused · 23:14, Push Day · Upper, Bench press · set 3")
            .assertIsDisplayed()
    }

    @Test
    fun `resuming reports the session to open`() {
        setContent(state(resume = RUNNING))

        composeTestRule.onNodeWithText("RESUME").performClick()

        assertEquals(listOf(7L), resumed)
    }

    @Test
    fun `the whole banner resumes, not only the button on it`() {
        setContent(state(resume = RUNNING))

        // The title is the biggest thing on the screen and was inert; a tap on it is the
        // gesture a dashboard card invites — see ResumeCard.
        composeTestRule.onNodeWithText("Push Day · Upper").performClick()

        assertEquals(listOf(7L), resumed)
    }

    @Test
    fun `nothing running draws no banner`() {
        setContent(state(resume = null))

        composeTestRule.onNodeWithText("Push Day · Upper").assertDoesNotExist()
        composeTestRule.onNodeWithText("RESUME").assertDoesNotExist()
    }

    // ─── Discarding ─────────────────────────────────────────────────────────────────────

    @Test
    fun `discard asks rather than deletes`() {
        setContent(state(resume = RUNNING))

        composeTestRule.onNodeWithText("Discard").performClick()

        // The request, and *only* the request: the screen cannot delete anything, and the
        // dialog is drawn from the state that comes back.
        assertEquals(listOf(HomeEvent.DiscardRequested), events)
    }

    @Test
    fun `the confirmation says what the workout costs`() {
        setContent(state(resume = RUNNING).copy(confirmingDiscard = true))

        composeTestRule.onNodeWithText("Discard this workout?").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("11 logged sets will be deleted. This cannot be undone.")
            .assertIsDisplayed()
    }

    @Test
    fun `confirming the discard reports it`() {
        setContent(state(resume = RUNNING).copy(confirmingDiscard = true))

        composeTestRule.onNode(hasAnyAncestor(isDialog()) and hasText("Discard")).performClick()

        assertEquals(listOf(HomeEvent.DiscardConfirmed), events)
    }

    @Test
    fun `keeping the workout dismisses the question`() {
        setContent(state(resume = RUNNING).copy(confirmingDiscard = true))

        composeTestRule.onNodeWithText("Keep training").performClick()

        assertEquals(listOf(HomeEvent.DiscardDismissed), events)
    }

    @Test
    fun `a discard in flight cannot be asked for twice`() {
        setContent(state(resume = RUNNING).copy(confirmingDiscard = true, isDiscarding = true))

        composeTestRule
            .onNode(hasAnyAncestor(isDialog()) and hasText("Discard"))
            .assertIsNotEnabled()
    }

    // ─── Quick start ────────────────────────────────────────────────────────────────────

    @Test
    fun `the empty-session action reports it`() {
        setContent(state())

        composeTestRule.onNodeWithText("START EMPTY SESSION").performClick()

        assertEquals(1, startedEmpty)
    }

    @Test
    fun `the template action reports it`() {
        setContent(state())

        composeTestRule.onNodeWithText("Start from template").performClick()

        assertEquals(1, openedTemplates)
    }

    @Test
    fun `a quick-start card reports which template it is`() {
        setContent(state())

        composeTestRule
            .onNodeWithContentDescription("Pull Day, 7 exercises, about 50 minutes, Strength")
            .performClick()

        assertEquals(listOf(2L), startedFromTemplate)
    }

    @Test
    fun `a workout already open takes both ways into a new one out of service`() {
        setContent(state(resume = RUNNING))

        // The flow behind them refuses to start a second session — `ResumeSessionDialog` —
        // and Home knows that before the tap rather than a screen later.
        composeTestRule.onNodeWithText("START EMPTY SESSION").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Start from template").assertIsNotEnabled()
        // The strip below them goes the same way, or it becomes the one live control left
        // leading into a refusal.
        composeTestRule
            .onNodeWithContentDescription("Pull Day, 7 exercises, about 50 minutes, Strength")
            .assertIsNotEnabled()
        // And said out loud: dead controls with nothing explaining them are worse than the
        // round trip they save.
        composeTestRule
            .onNodeWithText("Finish or discard the workout above before starting another.")
            // Scrolled to: with the banner and the week above it, the line sits below the fold
            // of the phone-sized window these run in.
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `nothing open leaves them both live`() {
        setContent(state(resume = null))

        composeTestRule.onNodeWithText("START EMPTY SESSION").assertIsEnabled()
        composeTestRule.onNodeWithText("Start from template").assertIsEnabled()
    }

    // ─── The empty screen ───────────────────────────────────────────────────────────────

    @Test
    fun `a first launch is the quick-start actions and nothing else`() {
        setContent(HomeUiState(isLoading = false))

        composeTestRule.onNodeWithText("START EMPTY SESSION").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start from template").assertIsDisplayed()
        // No header, no banner, no week card, no record and no chart of noughts: nothing on
        // this screen renders an empty shell — see HomeUiState.
        composeTestRule.onNodeWithText("RESUME").assertDoesNotExist()
        composeTestRule.onNodeWithText("THIS WEEK").assertDoesNotExist()
        composeTestRule.onNodeWithText("LVL 1").assertDoesNotExist()
        composeTestRule.onNodeWithText("RECENT PR").assertDoesNotExist()
        composeTestRule.onNodeWithText("VOLUME · LAST 8 WEEKS").assertDoesNotExist()
        // One line of prose in place of the cards that have nothing in them — see
        // QuickStartSection.
        composeTestRule
            .onNodeWithText("Nothing logged yet. Start a session and your week fills in here.")
            .assertIsDisplayed()
    }

    @Test
    fun `nothing at all is drawn while the reads are still out`() {
        // The default state, which is what the screen holds for the frame before the queries
        // answer. Drawing the week here would flash "0 sessions" at somebody who has four
        // hundred of them — and drawing the actions alone puts them where the banner and the
        // week are about to appear above them, moving them under a thumb on its way down.
        setContent(HomeUiState())

        composeTestRule.onNodeWithText("THIS WEEK").assertDoesNotExist()
        composeTestRule.onNodeWithText("QUICK START").assertDoesNotExist()
        composeTestRule.onNodeWithText("START EMPTY SESSION").assertDoesNotExist()
        // And not the first-launch line either: an empty database and an unanswered query
        // are different things, and only one of them is worth saying out loud.
        composeTestRule
            .onNodeWithText("Nothing logged yet. Start a session and your week fills in here.")
            .assertDoesNotExist()
    }

    // ─── This week ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the week counts what was logged in it`() {
        setContent(state())

        composeTestRule.onNodeWithText("THIS WEEK").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 sessions").assertIsDisplayed()
    }

    @Test
    fun `a trained day announces the date and the count, a rest day says it was one`() {
        setContent(state())

        // The dates are spelled by the formatter rather than by hand: the wording is
        // `formatSessionDate`'s and its own tests hold it, and a literal here would be an
        // assertion about the locale CI happens to run in.
        val monday = formatSessionDate(WEEK.first().date)
        val thursday = formatSessionDate(WEEK[3].date)

        composeTestRule.onNodeWithContentDescription("$monday, 1 session").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("$thursday, rest day").assertIsDisplayed()
    }

    @Test
    fun `a day the week has not reached is not a rest day`() {
        setContent(state())

        // Nobody has skipped a workout they have not had the chance to do yet, and a strip
        // that says otherwise reads as a week already lost — see WeekStrip.
        val sunday = formatSessionDate(WEEK.last().date)

        composeTestRule.onNodeWithContentDescription("$sunday, upcoming").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("$sunday, rest day").assertDoesNotExist()
    }

    @Test
    fun `the week card opens the sessions it is counting`() {
        setContent(state())

        composeTestRule.onNodeWithText("3 sessions").performClick()

        assertEquals(1, openedSessions)
    }

    // ─── The progression cards ──────────────────────────────────────────────────────────

    @Test
    fun `the header is one sentence, not a level and a bare proportion`() {
        setContent(state())

        // `LVL 14` and `40 / 100 XP` are drawn — the reference images are the evidence for
        // that — and neither is a node of its own: the header clears its subtree and is read
        // as one sentence, or a reader hears the same fact three times. See LevelHeader.
        composeTestRule
            .onNodeWithContentDescription("Level 14, 60 points to the next level")
            .assertIsDisplayed()
    }

    @Test
    fun `nothing banked yet draws no header`() {
        setContent(state().copy(level = null))

        // Not `LVL 1 · 0 / 100 XP` — see HomeUiState.level.
        composeTestRule
            .onNodeWithContentDescription("point to the next level", substring = true)
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription("points to the next level", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a running streak is drawn beside the week's count`() {
        setContent(state())

        composeTestRule.onNodeWithText("3 sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("12-day streak").assertIsDisplayed()
    }

    @Test
    fun `a streak that has lapsed is absent rather than zero`() {
        setContent(state().copy(week = WeekState(days = WEEK, sessionCount = 3, streak = null)))

        composeTestRule.onNodeWithText("3 sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("streak", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the recent record card names the lift, the kind and what it beat`() {
        setContent(state())

        composeTestRule.onNodeWithText("RECENT PR").performScrollTo().assertIsDisplayed()
        // The whole card is one node — the row clears its subtree, as it does in the records
        // list — so the lift, the kind, the figure, the date and the improvement over the
        // record it beat are asserted where a screen reader would meet them.
        composeTestRule
            .onNodeWithContentDescription("Deadlift, 1RM, 142 kg, 27 Apr, +7 kg")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the record card opens the list it is the newest of`() {
        setContent(state())

        composeTestRule
            .onNodeWithContentDescription("Deadlift", substring = true)
            .performScrollTo()
            .performClick()

        assertEquals(1, openedRecords)
    }

    @Test
    fun `the volume chart says what it holds rather than being an image`() {
        setContent(state())

        // The chart is one node carrying a sentence built from its own series — the semantics
        // #144 solves once in `:core:ui` so four screens do not solve it four ways. The bars
        // are a drawing; this is the only way the figures behind them are reachable.
        composeTestRule
            .onNodeWithContentDescription("Latest 28,400 kg", substring = true)
            .assertExists()
    }

    @Test
    fun `the volume card opens the stats behind it`() {
        setContent(state())

        composeTestRule
            .onNodeWithContentDescription("Latest 28,400 kg", substring = true)
            .performScrollTo()
            .performClick()

        assertEquals(1, openedStats)
    }

    @Test
    fun `a fortnight in draws the cards that have something to say and no others`() {
        // The partially-populated state: trained, so there is a week and a streak, but no
        // record broken and too little volume to chart. Each absent card is absent rather than
        // empty, which is the rule the screen is built on.
        setContent(
            HomeUiState(
                level = LevelState(level = 2, xpIntoLevel = 75, xpForLevel = 100),
                week = WeekState(days = WEEK, sessionCount = 3, streak = 3),
                templates = TEMPLATES,
                isLoading = false,
            ),
        )

        composeTestRule
            .onNodeWithContentDescription("Level 2, 25 points to the next level")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("3-day streak").assertIsDisplayed()
        composeTestRule.onNodeWithText("RECENT PR").assertDoesNotExist()
        composeTestRule.onNodeWithText("VOLUME · LAST 8 WEEKS").assertDoesNotExist()
    }

    // ─── Rig ────────────────────────────────────────────────────────────────────────────

    private fun setContent(state: HomeUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                HomeScreen(
                    state = state,
                    onEvent = events::add,
                    onResumeSession = resumed::add,
                    onStartEmptySession = { startedEmpty++ },
                    onStartFromTemplates = { openedTemplates++ },
                    onStartFromTemplate = startedFromTemplate::add,
                    onOpenSessions = { openedSessions++ },
                    onOpenRecords = { openedRecords++ },
                    onOpenStats = { openedStats++ },
                    onOpenQuestLog = { openedQuestLog++ },
                )
            }
        }
    }

    /**
     * A settled Home with every card on it: the standing, a week and a streak, two templates,
     * the last record broken and eight weeks of volume.
     *
     * The fully-populated state #110 asks the tests to cover, and the default here because it
     * is also the strictest: a card drawn when it should not be is only visible against a
     * screen that has all of them.
     */
    private fun state(resume: ResumeState? = null) =
        HomeUiState(
            level = LevelState(level = 14, xpIntoLevel = 40, xpForLevel = 100),
            resume = resume,
            week = WeekState(days = WEEK, sessionCount = 3, streak = 12),
            templates = TEMPLATES,
            recentRecord = RECORD,
            volume = VOLUME,
            isLoading = false,
        )

    private companion object {
        /**
         * The week of Monday 27 April 2026: trained Monday, Wednesday and Saturday.
         *
         * Today is that Saturday, which leaves the Sunday after it as a day the week has not
         * reached — the one cell that must not read as a rest day.
         */
        val WEEK: List<WeekDay> = List(7) { offset ->
            val date = LocalDate.of(2026, 4, 27).plusDays(offset.toLong())
            WeekDay(
                date = date,
                sessionCount = if (offset in listOf(0, 2, 5)) 1 else 0,
                isToday = offset == 5,
                isFuture = offset > 5,
            )
        }

        /** 2026-04-27, midday UTC — safely the same calendar day in every zone CI may run in. */
        const val RECORD_ACHIEVED_AT = 1_777_291_200_000L

        /**
         * A one-rep-max estimate on the deadlift, up 7 kg on the record it beat.
         *
         * `ESTIMATED_1RM` rather than a heaviest lift because it is the kind that carries an
         * improvement worth drawing, and the improvement is the half of this card that the
         * records list also has to get right.
         */
        val RECORD = PersonalRecordEntry(
            record = PersonalRecord(
                id = 1L,
                exerciseId = 1L,
                workoutSetId = 1L,
                type = PrType.ESTIMATED_1RM,
                estimated1Rm = 142_000_000L,
                achievedAt = RECORD_ACHIEVED_AT,
            ),
            exerciseName = "Deadlift",
            previousValue = 135_000_000L,
            isRecent = false,
        )

        /** Eight weeks of volume, one of them a week off, in milligram-reps. */
        val VOLUME: List<WeeklyVolume> = listOf(
            18_400L,
            21_200L,
            19_800L,
            24_600L,
            0L,
            26_100L,
            31_800L,
            28_400L,
        ).mapIndexed { index, kilograms ->
            WeeklyVolume(
                weekStart = LocalDate.of(2026, 4, 27).minusWeeks((7 - index).toLong()),
                totalVolume = kilograms * 1_000_000L,
            )
        }

        val TEMPLATES: List<TemplateSummary> = listOf(
            TemplateSummary(
                template = WorkoutTemplate(
                    id = 1L,
                    name = "Push Day",
                    category = TemplateCategory.STRENGTH,
                ),
                exerciseCount = 8,
                estimatedDuration = 55.minutes,
            ),
            TemplateSummary(
                template = WorkoutTemplate(
                    id = 2L,
                    name = "Pull Day",
                    category = TemplateCategory.STRENGTH,
                ),
                exerciseCount = 7,
                estimatedDuration = 50.minutes,
            ),
        )

        val RUNNING = ResumeState(
            sessionId = 7L,
            name = "Push Day · Upper",
            startedAt = 1_777_803_240_000L,
            elapsed = 23.minutes + 14.seconds,
            loggedSets = 11,
            position = SessionPosition(exerciseName = "Bench press", setNumber = 3),
        )
    }
}
