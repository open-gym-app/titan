package com.opengym.feature.history.detail

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The exercise detail screen, driven from hand-built states.
 *
 * The stateless half only — no ViewModel, no Hilt, no Room. The three cases the issue asks
 * for are a fully-populated exercise, a sparse one, and editing the note, and they are the
 * three things this screen can get wrong: dropping a section that is there, drawing a section
 * that is not, and losing what somebody typed.
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ExerciseDetailEvent>()
    private var backs = 0
    private var addsToSession = 0

    private fun setContent(
        state: ExerciseDetailUiState = benchState(),
        addToSession: Boolean = false,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                ExerciseDetailScreen(
                    state = state,
                    onEvent = events::add,
                    onBack = { backs++ },
                    onAddToSession = if (addToSession) {
                        { addsToSession++ }
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** The screen is one long scroll, so anything below the fold has to be brought into view. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
    }

    private fun scrollTo(text: String) = scrollTo(hasText(text))

    // ─── A fully-populated exercise ─────────────────────────────────────────────────────

    @Test
    fun `a seeded exercise shows its name, muscles, metadata and how-to`() {
        setContent()

        composeTestRule.onNodeWithText("Bench press").assertIsDisplayed()

        // The muscles, twice over: as a phrase beside the diagram, and in the diagram's own
        // description. Both are asserted because either one alone can go missing silently.
        scrollTo(hasContentDescription("Primary, Chest"))
        composeTestRule.onNodeWithContentDescription("Primary, Chest").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Secondary, Triceps").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Body diagram. Worked directly: Chest. Also worked: Triceps.")
            .assertIsDisplayed()

        // Below the fold behind a 3:2 banner, so each of these is scrolled to first — a
        // section that renders but never comes into view is still a section that failed.
        scrollTo(hasContentDescription("Equipment, Barbell"))
        composeTestRule.onNodeWithContentDescription("Equipment, Barbell").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Type, Powerlifting").assertIsDisplayed()
        scrollTo(hasContentDescription("Tracked, Weight, reps, RPE"))
        composeTestRule.onNodeWithContentDescription("Tracked, Weight, reps, RPE").assertIsDisplayed()

        scrollTo("Unrack the bar.")
        composeTestRule.onNodeWithText("Unrack the bar.").assertIsDisplayed()
        scrollTo("Press back to lockout.")
        composeTestRule.onNodeWithText("Press back to lockout.").assertIsDisplayed()
    }

    @Test
    fun `the how-to is numbered, one step per line of the stored instructions`() {
        setContent()

        // Three lines in the fixture, so a third step number is the evidence that the split
        // happened rather than the whole blob being drawn as one paragraph.
        scrollTo("Unrack the bar.")
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
        scrollTo("Press back to lockout.")
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `a seeded exercise offers no way to edit or delete it`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Edit exercise").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Delete exercise").assertDoesNotExist()
    }

    // ─── A sparse one ───────────────────────────────────────────────────────────────────

    @Test
    fun `negative - a custom exercise draws no empty how-to section`() {
        setContent(customState())

        // The heading, not the body: an absent section is absent entirely, rather than a
        // heading over a card with nothing in it.
        composeTestRule.onNodeWithText("HOW TO").assertDoesNotExist()
    }

    @Test
    fun `a custom exercise still shows what it is and what it records`() {
        setContent(customState())

        composeTestRule.onNodeWithText("Hammer press").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Equipment, Machine").assertIsDisplayed()
        // Derived from a non-null column, so it is the one fact every exercise has.
        composeTestRule.onNodeWithContentDescription("Tracked, Weight, reps, RPE").assertIsDisplayed()
        composeTestRule.onNodeWithText("YOUR NOTES").assertIsDisplayed()
    }

    @Test
    fun `a custom exercise offers edit and delete`() {
        setContent(customState())

        composeTestRule.onNodeWithContentDescription("Edit exercise").performClick()
        composeTestRule.onNodeWithContentDescription("Delete exercise").performClick()

        assertEquals(
            listOf(ExerciseDetailEvent.EditRequested, ExerciseDetailEvent.DeleteRequested),
            events,
        )
    }

    @Test
    fun `negative - an exercise with no muscle links draws no muscle section`() {
        setContent(customState().copy(muscleLinks = emptyList()))

        composeTestRule.onNodeWithText("MUSCLES WORKED").assertDoesNotExist()
    }

    @Test
    fun `an id that no longer resolves says so rather than drawing an empty exercise`() {
        setContent(ExerciseDetailUiState(isLoading = false))

        composeTestRule.onNodeWithText("Exercise not found").assertIsDisplayed()
    }

    @Test
    fun `negative - nothing is drawn while the read is still in flight`() {
        setContent(ExerciseDetailUiState())

        // Not the "not found" page, which is what an unguarded null exercise would render for
        // the frame before the row arrives.
        composeTestRule.onNodeWithText("Exercise not found").assertDoesNotExist()
    }

    // ─── Editing the note ───────────────────────────────────────────────────────────────

    @Test
    fun `tapping the note opens it for editing`() {
        setContent()

        scrollTo("Seat on 3.")
        composeTestRule.onNodeWithText("Seat on 3.").performClick()

        assertEquals(listOf(ExerciseDetailEvent.NoteEditRequested), events)
    }

    @Test
    fun `typing in the note reports the whole field back, not a diff`() {
        setContent(benchState().copy(notesDraft = "Seat on 3."))

        scrollTo("Seat on 3.")
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("Elbows in.")

        // The field holds no state of its own — what it shows is what it was handed — so the
        // change it reports is the whole contents rather than something to apply to them.
        assertEquals(
            listOf<ExerciseDetailEvent>(ExerciseDetailEvent.NoteChanged("Elbows in.")),
            events,
        )
    }

    @Test
    fun `saving the note reports it, cancelling discards it`() {
        setContent(benchState().copy(notesDraft = "Seat on 3."))

        scrollTo("Save")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(
            listOf(ExerciseDetailEvent.NoteSaved, ExerciseDetailEvent.NoteEditCancelled),
            events,
        )
    }

    @Test
    fun `an empty note can still be saved, because that is how one is deleted`() {
        setContent(benchState().copy(notesDraft = "Seat on 3."))

        scrollTo("Seat on 3.")
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        scrollTo("Save")
        composeTestRule.onNodeWithText("Save").performClick()

        assertTrue(ExerciseDetailEvent.NoteSaved in events)
    }

    @Test
    fun `an exercise with no note invites one rather than showing an empty card`() {
        setContent(customState())

        scrollTo(hasContentDescription("Add a note"))
        composeTestRule.onNodeWithContentDescription("Add a note").assertIsDisplayed()
    }

    // ─── Adding to a session ────────────────────────────────────────────────────────────

    @Test
    fun `the picker's caller gets an add button, and pressing it reports the exercise`() {
        setContent(addToSession = true)

        composeTestRule.onNodeWithText("Add to session").performClick()

        assertEquals(1, addsToSession)
    }

    @Test
    fun `negative - the library's caller gets no add button, having no session to add to`() {
        setContent()

        composeTestRule.onNodeWithText("Add to session").assertDoesNotExist()
    }

    // ─── Leaving ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a deleted exercise leaves the screen rather than drawing the not-found page`() {
        setContent(customState().copy(isDeleted = true))

        assertEquals(1, backs)
    }

    @Test
    fun `the back arrow leaves`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
