package com.titan.feature.session.summary

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import com.titan.core.domain.model.PaceStyle
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The session recap, driven by hand-built state.
 *
 * The stateless half, per CONTRIBUTING.md § Screens — no ViewModel and no Hilt graph. What is
 * asserted here is what the screen *draws and reports*; whether the figures are the right ones
 * for a given session is `SessionSummaryViewModelTest`'s question.
 *
 * The states come from `previewState()`, the same fixture the `@Preview`s render, so a screen
 * that passes here is the screen the screenshots captured.
 */
@RunWith(RobolectricTestRunner::class)
class SessionSummaryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<SessionSummaryEvent>()
    private var exits = 0

    // ─── The recap ──────────────────────────────────────────────────────────────────────

    @Test
    fun `heads the recap with the session, the day and the window it took`() {
        setContent(previewState())

        composeTestRule.onNodeWithText("SESSION COMPLETE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Push Day · Upper").assertIsDisplayed()
        // The date, both clock times and the duration on one mono line. Matched as a substring
        // so a locale's own date and time formats do not decide whether this passes — what is
        // under test is that the line is assembled, not how a JVM renders April.
        composeTestRule.onNode(hasText("52:24", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `the stat grid carries the muscle count in place of the duration`() {
        setContent(previewState())

        // The duration is already in the header, so the fourth tile is the figure with nowhere
        // else to go. The fixture works four muscle groups.
        composeTestRule.onNodeWithContentDescription("Muscles, 4 worked").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Sets, 17 total").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Duration, 52:24").assertDoesNotExist()
    }

    @Test
    fun `a session that beat something says so in its own section`() {
        setContent(previewState())

        scrollTo("NEW PERSONAL RECORDS")
        composeTestRule.onNodeWithText("NEW PERSONAL RECORDS").assertIsDisplayed()

        // The record's own figure, beside the badge naming the kind of record it is.
        scrollTo("92 kg")
        composeTestRule.onNodeWithText("92 kg").assertIsDisplayed()
        composeTestRule.onNodeWithText("Barbell bench press").assertIsDisplayed()
    }

    @Test
    fun `a timed session celebrates each record once, in its exercise's own terms`() {
        // A run, a ride and a plank (#225). The run's pace and the ride's speed are the same kind
        // of record, stored once; the ride's is drawn as a speed and named for it, and nothing
        // beside it is a second, pace-shaped row for the same achievement.
        setContent(previewState(records = timedRecords()))

        scrollTo("5:27 /km")
        composeTestRule.onNodeWithText("5:27 /km").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("PR Best pace, personal record").assertIsDisplayed()

        scrollTo("30.0 km/h")
        composeTestRule.onAllNodesWithText("30.0 km/h").assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("PR Top speed, personal record").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("2:00 /km").assertCountEquals(0)

        scrollTo("2:30")
        composeTestRule.onNodeWithContentDescription("PR Longest, personal record").assertIsDisplayed()
    }

    @Test
    fun `a session that beat nothing draws no records section at all`() {
        // Absent rather than an empty card: the ordinary session beats nothing, and a card
        // saying so is the screen telling the user what they failed to do.
        setContent(previewState(records = emptyList()))

        composeTestRule.onNodeWithText("NEW PERSONAL RECORDS").assertDoesNotExist()
        composeTestRule.onNodeWithText("PERSONAL RECORDS").assertDoesNotExist()
    }

    @Test
    fun `the muscle breakdown names each muscle with the volume attributed to it`() {
        setContent(previewState())

        // Primary and secondary are distinguished in the spoken form as well as in the bar's
        // tone, which is the half a screen reader gets.
        val chest = hasContentDescription("Chest, 2,480 kg, worked directly")
        scrollTo(chest)
        composeTestRule.onNode(chest).assertIsDisplayed()

        val triceps = hasContentDescription("Triceps, 1,240 kg, also worked")
        scrollTo(triceps)
        composeTestRule.onNode(triceps).assertIsDisplayed()
    }

    @Test
    fun `the breakdown lists every exercise with its sets, rep range and top weight`() {
        setContent(previewState())

        // Asserted through the spoken form rather than the drawn one: a row announces as a
        // single node, so its index, name, figures and badge are one sentence to a screen
        // reader and there are no separate text nodes left to query. The abbreviated line the
        // row *draws* — `4 × 5-10 · 80 kg max` — is what the screenshots hold.
        val bench = hasContentDescription(BENCH_ROW)
        scrollTo(bench)
        composeTestRule.onNode(bench).assertIsDisplayed()

        // Every set at the same rep count reads as one figure, not as a range of one.
        val incline = hasContentDescription("2. Incline dumbbell press, 4 sets, 10 reps, 22 kg max")
        scrollTo(incline)
        composeTestRule.onNode(incline).assertIsDisplayed()
    }

    @Test
    fun `an exercise logging neither reps nor weight draws no figure line`() {
        setContent(previewState())

        // A plank, whose sets carry a duration and nothing this line can show. The row is
        // still there — the stat tile is counting it — with its name and index alone.
        val plank = hasContentDescription("5. Plank, 3 sets")
        scrollTo(plank)
        composeTestRule.onNode(plank).assertIsDisplayed()
    }

    // ─── Notes ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the note is read as prose until the pencil is pressed`() {
        setContent(previewState())

        val pencil = hasContentDescription("Edit the note")
        scrollTo(pencil)
        composeTestRule.onNode(pencil).performClick()

        assertEquals(listOf(SessionSummaryEvent.NoteEditRequested), events)
    }

    @Test
    fun `a session with no note prompts for one rather than drawing an empty card`() {
        setContent(previewState(notes = null))

        val prompt = hasContentDescription("Add a note")
        scrollTo(prompt)
        composeTestRule.onNode(prompt).assertIsDisplayed()
    }

    @Test
    fun `editing the note reports what was typed and saves it`() {
        setContent(previewState(notesDraft = "Felt strong"))

        scrollTo("Felt strong")
        composeTestRule.onNodeWithText("Felt strong").performTextReplacement("Felt strong today")
        scrollTo("Save")
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(
            listOf(
                SessionSummaryEvent.NoteChanged("Felt strong today"),
                SessionSummaryEvent.NoteSaved,
            ),
            events,
        )
    }

    @Test
    fun `an emptied note field still saves, because that is how a note is deleted`() {
        setContent(previewState(notesDraft = "Felt strong"))

        scrollTo("Felt strong")
        composeTestRule.onNodeWithText("Felt strong").performTextClearance()
        scrollTo("Save")
        composeTestRule.onNodeWithText("Save").performClick()

        assertTrue(events.contains(SessionSummaryEvent.NoteSaved))
    }

    // ─── Save as template ───────────────────────────────────────────────────────────────

    @Test
    fun `save as template asks before it writes`() {
        setContent(previewState())

        composeTestRule.onNodeWithText("Save as template").performClick()

        assertEquals(listOf(SessionSummaryEvent.SaveAsTemplateRequested), events)
    }

    @Test
    fun `the dialog confirms with the name that is in it`() {
        setContent(previewState(templateName = "Push day"))

        composeTestRule.onNodeWithText("Push day").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(listOf(SessionSummaryEvent.SaveAsTemplateConfirmed), events)
    }

    @Test
    fun `a template cannot be saved without a name`() {
        setContent(previewState(templateName = ""))

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `the dialog offers a category, and picking one reports it`() {
        setContent(previewState(templateName = "Push day"))

        composeTestRule.onNodeWithText("HYPERTROPHY").performClick()

        assertEquals(
            listOf(SessionSummaryEvent.TemplateCategoryChanged(TemplateCategory.HYPERTROPHY)),
            events,
        )
    }

    @Test
    fun `a name already in use is drawn under the field rather than in a snackbar`() {
        setContent(previewState(templateName = "Push day").copy(templateNameTaken = true))

        composeTestRule.onNodeWithText("You already have a template with that name")
            .assertIsDisplayed()
    }

    @Test
    fun `a session with nothing performed offers no template to save`() {
        // `CreateTemplateUseCase` refuses an empty template, so the action is absent rather
        // than a button whose only outcome is an error the user could not have avoided.
        setContent(previewState().copy(exercises = emptyList()))

        composeTestRule.onNodeWithText("Save as template").assertDoesNotExist()
    }

    // ─── The two modes ──────────────────────────────────────────────────────────────────

    @Test
    fun `the historical view renders the same recap without the celebration`() {
        setContent(previewState(mode = SessionSummaryMode.HISTORICAL))

        // Same stats, same records, same breakdown.
        composeTestRule.onNodeWithContentDescription("Muscles, 4 worked").assertIsDisplayed()
        scrollTo("PERSONAL RECORDS")
        composeTestRule.onNodeWithText("PERSONAL RECORDS").assertIsDisplayed()

        val bench = hasContentDescription(BENCH_ROW)
        scrollTo(bench)
        composeTestRule.onNode(bench).assertIsDisplayed()

        // Nothing was just achieved, so nothing announces one — and the way out is the arrow
        // rather than a button that ends a flow which is not running.
        composeTestRule.onNodeWithText("SESSION COMPLETE").assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Back to the session list").assertIsDisplayed()
    }

    @Test
    fun `the just-finished view has no back arrow, because there is nothing behind it`() {
        setContent(previewState())

        composeTestRule.onNodeWithContentDescription("Back to the session list").assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `done is the flow's exit`() {
        setContent(previewState())

        composeTestRule.onNodeWithText("Done").performClick()

        assertEquals(1, exits)
    }

    @Test
    fun `the back arrow is the historical view's exit`() {
        setContent(previewState(mode = SessionSummaryMode.HISTORICAL))

        composeTestRule.onNodeWithContentDescription("Back to the session list").performClick()

        assertEquals(1, exits)
    }

    // ─── The edges ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a session that no longer exists says so and still offers the way out`() {
        setContent(SessionSummaryUiState(isLoading = false))

        composeTestRule.onNodeWithText("Session not found").assertIsDisplayed()
        // The just-finished mode has no back arrow, so hiding DONE here would strand the user
        // on an error page with no exit but the system gesture.
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `no recap is drawn while the reads are in flight, but the way out is`() {
        setContent(SessionSummaryUiState())

        // A spinner that appears for two frames is worse than a beat of nothing, so the body
        // waits. DONE does not: it needs no data, and it is the only exit this mode has.
        composeTestRule.onNodeWithText("Session not found").assertDoesNotExist()
        composeTestRule.onNodeWithText("Save as template").assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    // ─── The payoff (#111) ──────────────────────────────────────────────────────────────

    @Test
    fun `draws the xp earned with its breakdown rather than one opaque figure`() {
        setContent(previewState(celebration = previewCelebration()))

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("180 experience points earned"))

        // The total, and every source that paid into it. A screen that showed only the total
        // would satisfy "XP is displayed" and none of what the breakdown is for.
        composeTestRule.onNodeWithContentDescription("180 experience points earned").assertIsDisplayed()
        composeTestRule.onNodeWithText("12 sets").assertIsDisplayed()
        composeTestRule.onNodeWithText("Finishing the session").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 personal record").assertIsDisplayed()
    }

    @Test
    fun `a session that crossed a boundary is announced as a level up`() {
        setContent(previewState(celebration = previewLevelUpCelebration()))

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("LEVEL UP"))

        composeTestRule.onNodeWithText("LEVEL UP").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Level 22").onFirst().assertIsDisplayed()
    }

    @Test
    fun `a session that earned points without crossing one is not`() {
        setContent(previewState(celebration = previewCelebration()))

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("180 experience points earned"))

        // The figure is drawn and the banner is not. Levelling up is a different event from
        // earning, and the two must not be readable as the same thing.
        composeTestRule.onNodeWithContentDescription("180 experience points earned").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("LEVEL UP").assertCountEquals(0)
    }

    @Test
    fun `names the milestones the session unlocked`() {
        setContent(previewState(celebration = previewLevelUpCelebration()))

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("A month-long streak"))

        // The name, not just the points it paid: the breakdown above already carries the
        // amount, and which milestone was earned is the part that is worth a section.
        composeTestRule.onNodeWithText("A month-long streak").assertIsDisplayed()
    }

    @Test
    fun `names the quests the session cleared, and which deadline they met`() {
        setContent(previewState(celebration = previewCelebration()))

        composeTestRule.onNode(hasScrollAction())
            // The cadence line, which sits under the name — scrolling to the lower of the two
            // brings both into view, where scrolling to the heading brings neither.
            .performScrollToNode(hasText("This week"))

        // The whole point of the section: before it, clearing a quest was invisible at the
        // moment it happened and the breakdown said only that something called "quests" had
        // paid 60 XP. The name and the cadence are what a lifter actually wants told.
        composeTestRule.onNodeWithText("Train three times").assertIsDisplayed()
        composeTestRule.onNodeWithText("This week").assertIsDisplayed()
    }

    @Test
    fun `the breakdown still carries what the quests paid`() {
        setContent(previewState(celebration = previewCelebration()))

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("180 experience points earned"))

        // The points appear twice on this screen in two roles — a contribution to the total,
        // and a footnote to the thing that was earned. See `SummaryQuestsCard`.
        composeTestRule.onNodeWithText("1 quest").assertIsDisplayed()
    }

    @Test
    fun `a session that cleared nothing draws no quest section`() {
        setContent(previewState(celebration = previewLevelUpCelebration()))

        // Absent, not empty. Most sessions clear nothing, and a heading over an empty card
        // would be the screen listing what the user did not finish.
        composeTestRule.onAllNodesWithText("QUESTS CLEARED").assertCountEquals(0)
    }

    @Test
    fun `a session that earned nothing draws no payoff at all`() {
        setContent(previewState(celebration = null))

        // Absent, not a heading over `0 XP`. See [SummaryCelebration].
        composeTestRule.onAllNodesWithText("XP EARNED").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("LEVEL UP").assertCountEquals(0)
    }

    @Test
    fun `a saved streak says what survived and what it cost`() {
        setContent(previewState(celebration = previewCelebration(), streakSave = previewStreakSave()))

        val headline = "A protection covered your short week. Your streak stands at 13 weeks."
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(headline))

        composeTestRule.onNodeWithText(headline).assertIsDisplayed()

        // The cost, said once. The marks beside this line are cleared for exactly that reason.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("NONE LEFT THIS MONTH"))
        composeTestRule.onNodeWithText("NONE LEFT THIS MONTH").assertIsDisplayed()
    }

    @Test
    fun `the allowance line speaks the counts the silent marks are drawing`() {
        // Not a state a save can leave with one protection a month, but the card is drawn
        // against the allowance rather than assuming its size.
        setContent(previewState(streakSave = SummaryStreakSave(streakWeeks = 13, protectionsRemaining = 1)))

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("1 LEFT THIS MONTH"))

        // Both counts and the word for what they are: the row of marks says none of it out
        // loud, so the line beside it has to. See `StreakSavedCard`.
        composeTestRule.onNodeWithContentDescription("1 of 1 streak protection left this month")
            .assertIsDisplayed()
    }

    @Test
    fun `an exhausted allowance is said in words rather than as a zero`() {
        setContent(previewState(streakSave = previewStreakSave()))

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("NONE LEFT THIS MONTH"))

        composeTestRule.onNodeWithText("NONE LEFT THIS MONTH").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("0 LEFT THIS MONTH").assertCountEquals(0)
        // And spoken with the allowance it is none of, for the same reason as above.
        composeTestRule.onNodeWithContentDescription("Your 1 streak protection is gone for this month")
            .assertIsDisplayed()
    }

    @Test
    fun `a session that spent no protection draws no streak card`() {
        setContent(previewState(celebration = previewCelebration()))

        // Absent, not a card reading "0 protections used". See [SummaryStreakSave].
        composeTestRule.onAllNodesWithText("STREAK SAVED").assertCountEquals(0)
    }

    @Test
    fun `the historical recap celebrates nothing`() {
        // The state a historical recap is built with never carries a celebration — see the
        // ViewModel, which does not collect the hand-off in that mode. This asserts the screen
        // half of the same contract: given one, the mode still draws the session's own figures.
        setContent(previewState(mode = SessionSummaryMode.HISTORICAL, celebration = null))

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("PERSONAL RECORDS"))
        composeTestRule.onNodeWithText("PERSONAL RECORDS").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("XP EARNED").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("LEVEL UP").assertCountEquals(0)
    }

    @Test
    fun `reduced motion draws the finished figures and no confetti`() {
        // The whole payoff, rendered with the user's "do not animate" setting on. The figures
        // are asserted at their *final* values with no frame advanced: under reduced motion the
        // count and the bar land immediately, so what a still screenshot catches is the answer
        // rather than a frame of an animation. See `LocalReducedMotion`.
        // Passed to the theme rather than provided around it: `TitanTheme` provides
        // `LocalReducedMotion` itself, so an outer `CompositionLocalProvider` is shadowed and
        // this would silently assert against the animated rendering.
        composeTestRule.setContent {
            TitanTheme(reducedMotion = true) {
                SessionSummaryScreen(
                    state = previewState(celebration = previewLevelUpCelebration()),
                    onEvent = events::add,
                    onExit = { exits++ },
                )
            }
        }

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("240 experience points earned"))
        composeTestRule.onNodeWithContentDescription("240 experience points earned").assertIsDisplayed()
        composeTestRule.onNodeWithText("LEVEL UP").assertIsDisplayed()

        // The level the user *ended* on, with no frame advanced. The fixture crosses a threshold
        // from level 21, and the sweep plays crossings one at a time — so a build that animated
        // regardless would be caught here showing 21. See `rememberLevelSweep`.
        composeTestRule.onAllNodesWithText("Level 22").onFirst().assertIsDisplayed()
    }

    @Test
    fun `a round level is named a milestone rather than drawn louder in silence`() {
        setContent(previewState(celebration = previewMilestoneCelebration()))

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("MILESTONE REACHED"))

        // The banner says which kind of crossing it was. A round level that only threw more
        // confetti would be the same event with the volume up, which is what naming it avoids.
        composeTestRule.onNodeWithText("MILESTONE REACHED").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("LEVEL UP").assertCountEquals(0)
    }

    /** A 5 km run in 27:14, a 40 km ride in 1:20:00 and a 2:30 plank. */
    private fun timedRecords(): List<SummaryRecord> {
        val record = PersonalRecord(exerciseId = 0, workoutSetId = 0, type = PrType.BEST_PACE, achievedAt = 0)
        return listOf(
            SummaryRecord(
                record = record.copy(id = 21, exerciseId = 21, distance = 500_000, duration = 1_634),
                exerciseName = "Running, Outdoor",
            ),
            SummaryRecord(
                record = record.copy(
                    id = 22,
                    exerciseId = 22,
                    distance = 4_000_000,
                    duration = 4_800,
                    paceStyle = PaceStyle.SPEED,
                ),
                exerciseName = "Bicycling",
            ),
            SummaryRecord(
                record = record.copy(id = 23, exerciseId = 23, type = PrType.MAX_DURATION, duration = 150),
                exerciseName = "Plank",
            ),
        )
    }

    private fun setContent(state: SessionSummaryUiState) {
        composeTestRule.setContent {
            TitanTheme {
                SessionSummaryScreen(
                    state = state,
                    onEvent = events::add,
                    onExit = { exits++ },
                )
            }
        }
    }

    private companion object {

        /** The first breakdown row, as a screen reader hears it. */
        const val BENCH_ROW =
            "1. Barbell bench press, 4 sets, 5 to 10 reps, 80 kg max, PR 1RM, personal record"
    }

    /** The recap is one long scroll, so anything below the fold has to be brought into view. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
    }

    private fun scrollTo(text: String) = scrollTo(hasText(text))
}
