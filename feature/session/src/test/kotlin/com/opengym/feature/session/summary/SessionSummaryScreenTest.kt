package com.opengym.feature.session.summary

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.ui.theme.OpenGymTheme
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

    private fun setContent(state: SessionSummaryUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
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
            "1. Barbell bench press, 4 sets, 5 to 10 reps, 80 kg max, Personal record: 1RM"
    }

    /** The recap is one long scroll, so anything below the fold has to be brought into view. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
    }

    private fun scrollTo(text: String) = scrollTo(hasText(text))
}
