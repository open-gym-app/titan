package com.titan.feature.session.newsession

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateSummary
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The new-session screen, driven by hand-built state.
 *
 * The stateless half, per CONTRIBUTING.md § Screens — no ViewModel and no Hilt graph. What is
 * asserted here is what the screen *draws and reports*; whether the right rows end up in the
 * database is `NewSessionViewModelTest`'s question.
 */
@RunWith(RobolectricTestRunner::class)
class NewSessionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<NewSessionEvent>()
    private var cancelled = 0
    private var pickerOpened = 0

    // ─── Choosing ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the saved templates list under the quick-start action`() {
        setContent(NewSessionUiState(templates = templates(), isLoading = false))

        composeTestRule.onNodeWithText("Empty session").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(
            "Push Day · Upper, 8 ex · ~55m, Strength",
        ).assertIsDisplayed()
    }

    @Test
    fun `tapping a template reports which one`() {
        setContent(NewSessionUiState(templates = templates(), isLoading = false))

        composeTestRule.onNodeWithContentDescription(
            "Push Day · Upper, 8 ex · ~55m, Strength",
        ).performClick()

        assertEquals(listOf(NewSessionEvent.TemplateSelected(1L)), events)
    }

    @Test
    fun `the quick start both records the step and opens the picker`() {
        setContent(NewSessionUiState(templates = templates(), isLoading = false))

        composeTestRule.onNodeWithText("Empty session").performClick()

        assertEquals(listOf(NewSessionEvent.ExerciseSelectionOpened), events)
        assertEquals(1, pickerOpened)
    }

    @Test
    fun `the quick start is inert while the picker is already open`() {
        // Two taps through a slow transition would otherwise push a second picker over the
        // first, and the first back press then looks like it did nothing.
        setContent(
            NewSessionUiState(
                templates = templates(),
                isLoading = false,
                step = NewSessionStep.ExerciseSelection,
            ),
        )

        composeTestRule.onNodeWithText("Empty session").assertIsNotEnabled()
    }

    @Test
    fun `the back arrow leaves the flow from the template list`() {
        setContent(NewSessionUiState(templates = templates(), isLoading = false))

        composeTestRule.onNodeWithContentDescription("Leave without starting").performClick()

        assertEquals(1, cancelled)
        assertEquals(emptyList<NewSessionEvent>(), events)
    }

    // ─── The empty state ────────────────────────────────────────────────────────────────

    @Test
    fun `with no templates the screen says where templates come from`() {
        setContent(NewSessionUiState(isLoading = false))

        composeTestRule.onNodeWithText("No templates yet").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Finish a workout and save it as a template, and it shows up here ready to run again.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `the empty state points at the action that does work`() {
        setContent(NewSessionUiState(isLoading = false))

        // `EMPTY SESSION`, uppercased, is the empty state's own action — `Eyebrow` shouts for
        // the caller, which is what tells it apart from the quick-start button above it.
        composeTestRule.onNodeWithText("EMPTY SESSION").performClick()

        assertEquals(1, pickerOpened)
    }

    @Test
    fun `nothing is drawn about templates while the read is in flight`() {
        // An empty state with a call to action is a poor thing to flash at a user who has
        // six templates.
        setContent(NewSessionUiState(isLoading = true))

        composeTestRule.onNodeWithText("No templates yet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Templates").assertDoesNotExist()
    }

    // ─── Ready to start ─────────────────────────────────────────────────────────────────

    @Test
    fun `the review panel names the session and lists what it holds`() {
        setContent(NewSessionUiState(isLoading = false, step = readyFromTemplate()))

        composeTestRule.onNodeWithText("Push Day · Upper").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 exercises · ~55m").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench press").assertIsDisplayed()
        composeTestRule.onNodeWithText("SS A1").assertIsDisplayed()
    }

    @Test
    fun `the session's name is a 48dp target however short the name is`() {
        // The clickable used to be the text's own box, 41dp tall at this type size, which
        // Accessibility Scanner reported as a touch-target error on the device.
        setContent(
            NewSessionUiState(
                isLoading = false,
                step = readyFromTemplate().copy(name = "Push"),
            ),
        )

        composeTestRule.onNodeWithText("Push").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `a session built by hand shows its placeholder name and no estimate`() {
        setContent(
            NewSessionUiState(
                isLoading = false,
                step = NewSessionStep.ReadyToStart(
                    name = null,
                    estimatedDuration = null,
                    exercises = listOf(row("Back squat")),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Empty session").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 exercise").assertIsDisplayed()
    }

    @Test
    fun `starting reports the commit`() {
        setContent(NewSessionUiState(isLoading = false, step = readyFromTemplate()))

        composeTestRule.onNodeWithText("Start workout").performClick()

        assertEquals(listOf(NewSessionEvent.SessionStartRequested), events)
    }

    @Test
    fun `start is closed while the session is being written`() {
        setContent(
            NewSessionUiState(isLoading = false, step = readyFromTemplate(), isStarting = true),
        )

        composeTestRule.onNodeWithText("Start workout").assertIsNotEnabled()
    }

    @Test
    fun `start is open once there is something to start`() {
        setContent(NewSessionUiState(isLoading = false, step = readyFromTemplate()))

        composeTestRule.onNodeWithText("Start workout").assertIsEnabled()
    }

    @Test
    fun `dropping an exercise reports its position`() {
        setContent(NewSessionUiState(isLoading = false, step = readyFromTemplate()))

        // The middle row rather than the last: the test window is 470dp tall, which puts the
        // third row under the pinned start bar, and a click there lands on the button.
        composeTestRule.onNodeWithContentDescription("Remove Incline press").performClick()

        assertEquals(listOf(NewSessionEvent.ExerciseRemoved(1)), events)
    }

    @Test
    fun `the back arrow discards the plan rather than leaving the flow`() {
        // The difference that makes the review panel a step and not a place: a user
        // correcting a mistapped template must not be dropped out of the flow entirely.
        setContent(NewSessionUiState(isLoading = false, step = readyFromTemplate()))

        composeTestRule.onNodeWithContentDescription("Back to the template list").performClick()

        assertEquals(listOf(NewSessionEvent.PlanDiscarded), events)
        assertEquals(0, cancelled)
    }

    // ─── The workout already running ────────────────────────────────────────────────────

    @Test
    fun `a start refused by a running workout offers that workout back`() {
        setContent(
            NewSessionUiState(
                isLoading = false,
                step = readyFromTemplate(),
                runningWorkout = RUNNING,
            ),
        )

        // The workout itself, not a paragraph about it: which one, and what is in it.
        composeTestRule.onNodeWithText("Push day").assertIsDisplayed()
        composeTestRule.onNodeWithText("11 SETS LOGGED").assertIsDisplayed()

        composeTestRule.onNodeWithText("Pick it back up").performClick()

        // The only route there is: the running session is not in the history list, and the
        // workout screen is otherwise reachable only by starting one.
        assertEquals(listOf(NewSessionEvent.ResumeRequested), events)
    }

    @Test
    fun `the running workout can be thrown away instead`() {
        setContent(
            NewSessionUiState(
                isLoading = false,
                step = readyFromTemplate(),
                runningWorkout = RUNNING,
            ),
        )

        composeTestRule.onNodeWithText("Discard it and start").performClick()

        assertEquals(listOf(NewSessionEvent.RunningSessionDiscarded), events)
    }

    @Test
    fun `the offer goes inert while the discard is in flight`() {
        setContent(
            NewSessionUiState(
                isLoading = false,
                step = readyFromTemplate(),
                runningWorkout = RUNNING,
                isStarting = true,
            ),
        )

        // A second press would be answered by a session that is already gone.
        composeTestRule.onNodeWithText("Discard it and start").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Pick it back up").assertIsNotEnabled()
    }

    @Test
    fun `the offer can be declined`() {
        setContent(
            NewSessionUiState(
                isLoading = false,
                step = readyFromTemplate(),
                runningWorkout = RUNNING,
            ),
        )

        composeTestRule.onNodeWithText("Not now").performClick()

        assertEquals(listOf(NewSessionEvent.ResumeDismissed), events)
    }

    @Test
    fun `negative - nothing running, nothing offered`() {
        setContent(NewSessionUiState(isLoading = false, step = readyFromTemplate()))

        composeTestRule.onNodeWithText("STILL RUNNING").assertDoesNotExist()
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun setContent(state: NewSessionUiState) {
        composeTestRule.setContent {
            TitanTheme {
                NewSessionScreen(
                    state = state,
                    onEvent = events::add,
                    onCancel = { cancelled++ },
                    onPickExercises = { pickerOpened++ },
                )
            }
        }
    }

    private fun readyFromTemplate() =
        NewSessionStep.ReadyToStart(
            name = "Push Day · Upper",
            estimatedDuration = 55.minutes,
            exercises = listOf(
                row("Bench press"),
                row("Incline press", supersetLabel = "A1"),
                row("Cable fly", supersetLabel = "A2"),
            ),
        )

    private fun row(
        name: String,
        supersetLabel: String? = null,
    ) = PlannedExerciseRow(
        exercise = Exercise(id = name.hashCode().toLong(), name = name),
        supersetLabel = supersetLabel,
    )

    private fun templates(): List<TemplateSummary> =
        listOf(
            summary(1L, "Push Day · Upper", 8, 55.minutes, TemplateCategory.STRENGTH),
            summary(2L, "Leg Day · Heavy", 6, 62.minutes, TemplateCategory.HYPERTROPHY),
        )

    private fun summary(
        id: Long,
        name: String,
        exerciseCount: Int,
        duration: Duration,
        category: TemplateCategory,
    ) = TemplateSummary(
        template = WorkoutTemplate(id = id, name = name, category = category),
        exerciseCount = exerciseCount,
        estimatedDuration = duration,
    )

    private companion object {
        /** The workout in the way, as the refusal hands it to the screen. */
        val RUNNING = RunningWorkout(sessionId = 99L, name = "Push day", loggedSets = 11)
    }
}
