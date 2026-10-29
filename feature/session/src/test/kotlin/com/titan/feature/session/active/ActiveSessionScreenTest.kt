package com.titan.feature.session.active

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.SessionStats
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.timer.RestTimerState
import com.titan.core.ui.component.PreviousSet
import com.titan.core.ui.component.SessionProgress
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the workout screen draws, and what it emits when it is touched.
 *
 * Driven through the stateless half with a hand-built state — no ViewModel, no Hilt, no Room —
 * which is the whole reason the two-composable split exists. What the stores end up holding is
 * `ActiveSessionViewModelTest`'s question.
 *
 * ### Why this class sets a screen size
 *
 * Robolectric's default device is 320 x 470 dp, which is smaller than any phone shipped this
 * decade and is not the screen this layout was designed against: the exercise card alone is
 * taller than it, so the set-type chips and the button under them are off the bottom before the
 * test has done anything. A qualifier pinning a mid-range phone is not the same thing as a
 * `@Config(sdk = ...)` — the SDK level is pinned once for every module and must stay that way,
 * while the *viewport* is part of what a layout test is asserting about. Pinned rather than
 * inherited so the assertions do not move when Robolectric's default does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
/*
 * Over the size threshold, for the reason `ActiveSessionViewModelTest` is over its own: this is
 * the one screen a workout is logged, edited, paced, named, finished and thrown away on, and the
 * tests for those are one subject each under a heading of its own.
 *
 * A split would cost the support block at the bottom, which is what makes the failures readable:
 * `state()` builds the push day every test starts from, `scrollTo` composes a node inside a lazy
 * list before touching it, and `hold`/`drag`/`release` carry one gesture across a list that
 * changes shape underneath it. A second class would either duplicate all of that or hoist it
 * somewhere both could reach, which is more machinery between a failure and its meaning than a
 * heading is.
 */
@Suppress("LargeClass")
class ActiveSessionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ActiveSessionEvent>()

    // ─── What is on screen ──────────────────────────────────────────────────────────────

    @Test
    fun `an exercise is drawn with the sets already logged against it`() {
        setScreen(state())

        composeTestRule.onNode(inWorkout("Bench press")).assertIsDisplayed()

        // Each logged set is a row of fields with its values already in them: there is no editor
        // to open, so what is drawn is what can be typed into. See `LoggedSetTable`.
        scrollToField("Weight, set 1", "60").assertIsDisplayed()
        scrollToField("Reps, set 1", "10").assertIsDisplayed()
        scrollToDescription("Weight, set 2").assert(hasText("70"))
        scrollToDescription("Reps, set 2").assert(hasText("8"))
    }

    @Test
    fun `the header carries the elapsed clock and the workout's name`() {
        setScreen(state())

        // One node, name and clock together: the title block is the rename control, and a
        // merged control speaks once — see `SessionTitle` in `:core:ui`.
        composeTestRule.onNodeWithContentDescription("Push day · Upper, Elapsed 0:23:14").assertIsDisplayed()
    }

    @Test
    fun `every exercise of the workout is on the page, whole`() {
        setScreen(state())

        scrollToText("Bench press").assertIsDisplayed()
        scrollToText("Incline dumbbell press").assertIsDisplayed()
        scrollToText("Cable fly").assertIsDisplayed()
    }

    @Test
    fun `an exercise other than the first shows every set logged against it`() {
        // Nothing is tapped first. There is nothing to tap: the fold, and the selection it
        // existed for, are what this asserts are gone.
        setScreen(
            state(
                exercises = listOf(
                    benchRow(),
                    SessionExerciseRow(
                        sessionExerciseId = FLY_ROW,
                        position = 1,
                        exercise = FLY,
                        sets = listOf(
                            loggedRow(id = 5, weight = "20", reps = "12"),
                            loggedRow(id = 6, weight = "22.5", reps = "12"),
                        ),
                    ),
                ),
            ),
        )

        scrollToField("Weight, set 1", "20").assertIsDisplayed()
        scrollToField("Weight, set 2", "22.5").assertIsDisplayed()
    }

    @Test
    fun `every exercise has a row to type its next set into`() {
        // The other half of there being no current exercise, and what makes a superset
        // loggable: two exercises alternated set for set both take sets.
        setScreen(
            state(
                exercises = listOf(
                    benchRow(),
                    SessionExerciseRow(
                        sessionExerciseId = INCLINE_ROW,
                        position = 1,
                        exercise = INCLINE,
                        sets = listOf(loggedRow(id = 5)),
                    ),
                ),
            ),
        )

        // Asserted on each card's own button rather than on a field: every row of every table is
        // a field now, so "Weight, set 2" is on the bench's second logged set as well as on the
        // incline's next one. The button names the set the card is *waiting* for, which is the
        // question here.
        scrollToText("Complete set 3").assertIsDisplayed()
        scrollToText("Complete set 2").assertIsDisplayed()
    }

    @Test
    fun `a superset is drawn with its grouping visible`() {
        setScreen(state(exercises = supersetExercises()))

        scrollToText("SS A1").assertIsDisplayed()
        scrollToText("SS A2").assertIsDisplayed()
    }

    @Test
    fun `a session id that no longer resolves says so instead of drawing an empty workout`() {
        setScreen(ActiveSessionUiState(isLoading = false))

        composeTestRule.onNodeWithText("This session is gone").assertIsDisplayed()
    }

    // ─── Previous performance ───────────────────────────────────────────────────────────

    @Test
    fun `each set row carries what the same set did last time`() {
        setScreen(state(exercises = listOf(benchedBefore())))

        // Announced as a sentence with the unit in it, where the cell itself draws `60×10`: the
        // column is 52dp wide and a screen reader has all the room it needs. See `PreviousCell`.
        scrollToDescription("Previously 60 kg × 10").assertIsDisplayed()
        scrollToDescription("Previously 70 kg × 8").assertIsDisplayed()
    }

    @Test
    fun `an exercise with no history says so instead of drawing an empty column`() {
        setScreen(state(exercises = listOf(benchRow())))

        // Said once beside the name. A column of dashes down a card reads as a fault rather
        // than as a first attempt, which is the whole reason this state has words of its own.
        scrollToText("FIRST TIME").assertIsDisplayed()
        assertMissing(hasContentDescription("Previously", substring = true))
    }

    @Test
    fun `an exercise with history never claims to be a first attempt`() {
        setScreen(state(exercises = listOf(benchedBefore())))

        assertMissing(hasText("FIRST TIME"))
    }

    @Test
    fun `an exercise with a record shows the mark to beat`() {
        setScreen(state(exercises = listOf(benchedBefore().copy(record = benchRecord()))))

        // The number the lifter is trying to pass, not the date they set it — a card mid-set has
        // no room for history it cannot act on. Asserted through the description because the
        // line is one node whose semantics are replaced, the way every record row in the app
        // is — see `PrValue`, whose description string this shares.
        scrollToDescription("Personal record, Heaviest, 92 kg").assertIsDisplayed()
    }

    @Test
    fun `a first attempt says so rather than quoting a record it cannot have`() {
        // The record is *present on the row* and the history is not, which is the only shape
        // that tests the exclusivity: a row with no record at all would draw no line whatever
        // the branch did.
        setScreen(state(exercises = listOf(benchRow().copy(record = benchRecord()))))

        // Mutually exclusive with the badge: an exercise with no history has no record either,
        // and the two stacked would be a line saying "never done" over a line quoting a best.
        scrollToText("FIRST TIME").assertIsDisplayed()
        assertMissing(hasContentDescription("Personal record, Heaviest, 92 kg"))
    }

    @Test
    fun `sets beyond last time's count are left empty rather than repeating it`() {
        // Three sets today against one last time, and the exercise still has history: the two
        // extra rows have nothing to compare against, which is not the same as never having
        // done the movement.
        setScreen(
            state(
                exercises = listOf(
                    benchRow(
                        sets = listOf(
                            loggedRow(id = 1L, weight = "60", reps = "10")
                                .copy(previous = PreviousSet(weight = 60_000_000L, reps = 10)),
                            loggedRow(id = 2L, weight = "70", reps = "8"),
                            loggedRow(id = 3L, weight = "75", reps = "6"),
                        ),
                    ).copy(hasHistory = true),
                ),
            ),
        )

        assertMissing(hasText("FIRST TIME"))
        composeTestRule.onAllNodes(hasContentDescription("Previously", substring = true))
            .assertCountEquals(1)
    }

    // ─── The next target ────────────────────────────────────────────────────────────────

    @Test
    fun `with history, the entry row says what the next set should be and why`() {
        setScreen(state(exercises = listOf(benchedBefore().copy(target = benchTarget()))))

        // One node, the whole sentence, the button role: the figures, how far they moved, the
        // reason, and — from the click label — what a tap does. See `SetTargetLine`.
        scrollTo(targetLine())
            .assertIsDisplayed()
            .assert(expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("has the fill action") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == "Fill in the target"
                },
            )
    }

    @Test
    fun `the target is offered, not typed - the fields stay empty until the line is tapped`() {
        setScreen(state(exercises = listOf(benchedBefore().copy(target = benchTarget()))))

        // The placeholders are drawn in the two fields and nowhere in the tree: the field's
        // own text is still empty, so nothing is logged on the strength of a suggestion, and
        // a reader hears the target once, from the line, rather than twice more on the fields.
        scrollToDescription("Weight, set 3").assert(hasText(""))
        scrollToDescription("Reps, set 3").assert(hasText(""))
        assertMissing(hasText("82.5"))
        composeTestRule.onAllNodes(hasContentDescription("Target ", substring = true)).assertCountEquals(1)
    }

    @Test
    fun `tapping the target line asks for the fields to be filled, and logs nothing`() {
        setScreen(state(exercises = listOf(benchedBefore().copy(target = benchTarget()))))

        scrollTo(targetLine()).performClick()

        // The one event, and not `SetLogged`: the tick is still the lifter's to press.
        assertEquals(listOf<ActiveSessionEvent>(ActiveSessionEvent.TargetFilled(BENCH_ROW)), events)
    }

    @Test
    fun `negative - without history there is no target line, and the row is as it always was`() {
        setScreen(state(exercises = listOf(benchRow())))

        assertMissing(hasContentDescription("Target ", substring = true))
        assertMissing(hasContentDescription("Fill in the target"))
        scrollToDescription("Weight, set 3").assertIsDisplayed()
    }

    @Test
    fun `negative - a paused workout draws the target line inert`() {
        setScreen(state(exercises = listOf(benchedBefore().copy(target = benchTarget()))).copy(isPaused = true))

        scrollTo(targetLine()).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `at twice the font on a narrow phone, the target line is whole and the fields keep their room`() {
        // 320dp is where the previous column gives way — see `setTableFitsPrevious` — and 2x
        // is the scale #40 holds every screen to. The line is not a column and stays.
        setScreen(
            state(exercises = listOf(benchedBefore().copy(target = benchTarget()))),
            fontScale = 2f,
            widthDp = 320,
        )

        val line = scrollTo(targetLine()).assertIsDisplayed().fetchSemanticsNode()
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        // Whole: inside the page sideways, with nothing cut off either edge.
        assertTrue(line.boundsInRoot.left >= root.boundsInRoot.left)
        assertTrue(line.boundsInRoot.right <= root.boundsInRoot.right)
        // The column went, and the fields are still there to type into.
        assertMissing(hasContentDescription("Previously", substring = true))
        scrollToDescription("Weight, set 3").assertIsDisplayed()
        scrollToDescription("Reps, set 3").assertIsDisplayed()
    }

    // ─── Logging a set ──────────────────────────────────────────────────────────────────

    @Test
    fun `typing a weight reports it as text`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("Weight, set 3").performTextReplacement("82.5")

        assertEquals(listOf(ActiveSessionEvent.WeightChanged(BENCH_ROW, "82.5")), events)
    }

    @Test
    fun `typing a rep count reports it`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("Reps, set 3").performTextReplacement("5")

        assertEquals(listOf(ActiveSessionEvent.RepsChanged(BENCH_ROW, "5")), events)
    }

    @Test
    fun `the complete button logs the set`() {
        setScreen(state(entry = SetEntry(weight = "80", reps = "5")))

        scrollToText("Complete set 3").performClick()

        assertEquals(listOf(ActiveSessionEvent.SetLogged(BENCH_ROW)), events)
    }

    @Test
    fun `the row's own tick logs the same set`() {
        setScreen(state(entry = SetEntry(weight = "80", reps = "5")))

        composeTestRule.onNode(tick(setNumber = 3, completed = false)).performClick()

        assertEquals(listOf(ActiveSessionEvent.SetLogged(BENCH_ROW)), events)
    }

    @Test
    fun `an entry with nothing in it cannot be logged`() {
        setScreen(state(entry = SetEntry()))

        scrollToText("Complete set 3").assertIsNotEnabled()
    }

    @Test
    fun `a refused entry shows the reason under the row and blocks the button`() {
        setScreen(
            state(
                entry = SetEntry(
                    weight = "80",
                    reps = "0",
                    error = SetEntryError.Refused("reps must be within 1..10,000, was 0"),
                ),
            ),
        )

        composeTestRule.onNodeWithText("reps must be within 1..10,000, was 0").assertIsDisplayed()
        scrollToText("Complete set 3").assertIsNotEnabled()
    }

    // ─── Timed exercises (#M7-17) ─────────────────────────────────────────────────────────

    @Test
    fun `a plank draws a time field and a stopwatch, and no rep field`() {
        setScreen(state(exercises = listOf(plankRow())))

        scrollToDescription("Time, set 1").assertIsDisplayed()
        scrollToDescription("Added weight, set 1").assertIsDisplayed()
        scrollToDescription("Start timer, set 1").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onAllNodesWithContentDescription("Reps, set 1").assertCountEquals(0)
    }

    @Test
    fun `typing a hold's time reports it, and a typed time arms the tick`() {
        setScreen(state(exercises = listOf(plankRow(entry = SetEntry(duration = "1:30")))))

        scrollToField("Time, set 1", "1:30").performTextReplacement("1:45")

        assertEquals(ActiveSessionEvent.DurationChanged(BENCH_ROW, "1:45"), events.last())
        scrollToText("Complete set 1").assertIsEnabled()
        composeTestRule.onNode(tick(setNumber = 1, completed = false)).assertIsEnabled()
    }

    @Test
    fun `a hold with no time yet leaves the tick inert`() {
        setScreen(state(exercises = listOf(plankRow(entry = SetEntry(weight = "10")))))

        scrollToText("Complete set 1").assertIsNotEnabled()
        composeTestRule.onNode(tick(setNumber = 1, completed = false)).assertIsNotEnabled()
    }

    @Test
    fun `the stopwatch starts on a tap`() {
        setScreen(state(exercises = listOf(plankRow())))
        scrollToDescription("Start timer, set 1").performClick()
        assertEquals(ActiveSessionEvent.StopwatchStarted(BENCH_ROW), events.last())
    }

    @Test
    fun `a running stopwatch shows its reading and the control that stops it`() {
        setScreen(
            state(exercises = listOf(plankRow())).copy(timedExerciseId = BENCH_ROW),
            clocks = SessionClocks(stopwatch = 47.seconds),
        )

        // The figure is drawn and not spoken on its own — the stop control says it, once, which
        // is when it matters. See `HoldStopwatch`.
        scrollToDescription("Stop timer, set 1, 0:47 elapsed")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(ActiveSessionEvent.StopwatchStopped(BENCH_ROW), events.last())
    }

    @Test
    fun `a second passing redraws the stopwatch and the header with the state untouched`() {
        // The clocks are read where they are drawn rather than handed down in the state — see
        // `SessionClocks` — so they have to move without the state they sit beside moving.
        var clocks by mutableStateOf(SessionClocks(elapsed = 10.minutes, stopwatch = 47.seconds))
        val state = state(exercises = listOf(plankRow())).copy(timedExerciseId = BENCH_ROW)
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(state = state, onEvent = events::add, clocks = { clocks })
            }
        }

        clocks = SessionClocks(elapsed = 10.minutes + 1.seconds, stopwatch = 48.seconds)

        scrollToDescription("Stop timer, set 1, 0:48 elapsed").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Elapsed 0:10:01", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a hold timed on another exercise leaves this one's stopwatch inert`() {
        setScreen(
            state(exercises = listOf(plankRow())).copy(timedExerciseId = INCLINE_ROW),
            clocks = SessionClocks(stopwatch = 12.seconds),
        )

        scrollToDescription("Start timer, set 1").assertIsNotEnabled()
    }

    @Test
    fun `a run draws distance and time, a logged run reads back in them, and it has no stopwatch`() {
        val run = benchRow(
            sets = listOf(LoggedSet(id = 1L, entry = SetEntry(distance = "5", duration = "27:14"), isCompleted = true)),
            entry = SetEntry(duration = "30:00"),
        ).copy(exercise = BENCH.copy(name = "Running, Outdoor", type = ExerciseType.ENDURANCE))
        setScreen(state(exercises = listOf(run)))

        scrollToField("Distance in km, set 1", "5").assertIsDisplayed()
        scrollToField("Time, set 1", "27:14").assertIsDisplayed()
        scrollToDescription("Distance in km, set 2").assertIsDisplayed()
        // A time alone is a complete run.
        scrollToText("Complete set 2").assertIsEnabled()
        composeTestRule.onAllNodesWithContentDescription("Start timer, set 2").assertCountEquals(0)
    }

    @Test
    fun `a malformed time is refused in the screen's own words`() {
        setScreen(
            state(
                exercises = listOf(
                    plankRow(entry = SetEntry(duration = "1.5", error = SetEntryError.DurationNotATime)),
                ),
            ),
        )

        scrollToText("That time is not a time — type it as 1.30 or 1.05.30").assertIsDisplayed()
    }

    @Test
    fun `a set with no rep count yet leaves the fields live and the tick inert`() {
        // Half way through typing is not a refusal: the weight field is still the user's to
        // fill in, and `LogSetUseCase` would refuse the set behind the tick.
        setScreen(state(entry = SetEntry(weight = "80")))

        scrollToText("Complete set 3").assertIsNotEnabled()
        composeTestRule.onNode(tick(setNumber = 3, completed = false)).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Weight, set 3").assertIsEnabled()
    }

    @Test
    fun `text that is not a number is refused in the screen's own words`() {
        setScreen(state(entry = SetEntry(weight = "heavy", error = SetEntryError.WeightNotANumber)))

        scrollToText("That weight is not a number").assertIsDisplayed()
    }

    // ─── Rating and set type ────────────────────────────────────────────────────────────

    @Test
    fun `the rating cell opens the scale, and picking one reports it`() {
        setScreen(state())

        scrollToDescription("Rate of perceived exertion, set 3, not set").performClick()
        // Not scrolled to: the menu is a popup, which is the whole reason the scale is one now —
        // it costs the page no room and pushes nothing off the bottom of it.
        composeTestRule.onNodeWithContentDescription("Rate of perceived exertion 8.5").performClick()

        assertEquals(listOf(ActiveSessionEvent.RpeChanged(BENCH_ROW, 85)), events)
    }

    @Test
    fun `the menu's last item is what clears a rating`() {
        setScreen(state(entry = SetEntry(weight = "80", reps = "5", rpe = 85)))

        scrollToDescription("Rate of perceived exertion, set 3, 8.5").performClick()
        composeTestRule.onNodeWithText("Not rated").performClick()

        assertEquals(listOf(ActiveSessionEvent.RpeChanged(BENCH_ROW, null)), events)
    }

    @Test
    fun `picking a set type reports it`() {
        setScreen(state(entry = SetEntry(weight = "80")))

        // The leading cell is the control: it is where the type is read, so it is where it is
        // changed. No chips under the row — see `SetTypePicker`.
        scrollToDescription("Set type, set 3, Standard set").performClick()
        composeTestRule.onNodeWithText("Warm-up").performClick()

        assertEquals(listOf(ActiveSessionEvent.SetTypeChanged(BENCH_ROW, SetType.WARMUP)), events)
    }

    @Test
    fun `a warm-up set is marked as one in the row it is being typed into`() {
        setScreen(state(entry = SetEntry(setType = SetType.WARMUP)))

        composeTestRule
            .onNodeWithContentDescription("Set type, set 3, Warm-up")
            .assertIsDisplayed()
    }

    // ─── Notes ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the note field is offered before it is opened`() {
        setScreen(state(entry = SetEntry(weight = "80")))

        scrollToText("Add a note").performClick()

        assertEquals(listOf(ActiveSessionEvent.NotesChanged(BENCH_ROW, "")), events)
    }

    @Test
    fun `an open note field reports what is typed into it`() {
        setScreen(state(entry = SetEntry(weight = "80", reps = "5", notes = "")))

        scrollToText("Note").performTextReplacement("left elbow")

        assertEquals(listOf(ActiveSessionEvent.NotesChanged(BENCH_ROW, "left elbow")), events)
    }

    // ─── Correcting a logged set ────────────────────────────────────────────────────────
    //
    // There is nothing to open. Every logged row is already the row it is corrected in, so what
    // these assert is the typing itself, and what the row does when it is left.

    @Test
    fun `typing over a logged value reports it against that row`() {
        setScreen(state())

        scrollToDescription("Weight, set 2").performTextReplacement("72.5")

        assertEquals(
            listOf(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 2L, text = "72.5")),
            events,
        )
    }

    @Test
    fun `each logged row reports against its own set`() {
        setScreen(state())

        scrollToField("Reps, set 1", "10").performTextReplacement("9")
        scrollToDescription("Reps, set 2").performTextReplacement("7")

        // The old editor could hold one row, so the events named none. They all name one now,
        // because all of them are live at once. Asserted by membership rather than as the whole
        // list: the rows are driven from outside, so a field whose state does not follow what was
        // typed reports its way back to what it was told to show.
        val reported = events.filterIsInstance<ActiveSessionEvent.SetEditRepsChanged>()
        assertTrue(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, 1L, "9") in reported)
        assertTrue(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, 2L, "7") in reported)
    }

    @Test
    fun `leaving a logged row is what writes it`() {
        setScreen(state())

        scrollToDescription("Weight, set 2").performTextReplacement("72.5")
        // Focus moved off the row, which is the gesture: a tap on another set, on the entry row,
        // or anywhere off the fields.
        scrollToField("Weight, set 1", "60").requestFocus()
        composeTestRule.waitForIdle()

        assertTrue(
            ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 2L) in events,
        )
    }

    @Test
    fun `a tap on the page beside a field puts the keyboard away, and writes what was typed`() {
        setScreen(state())

        scrollToDescription("Weight, set 2").performTextReplacement("72.5")
        // The exercise's title: page, not a control.
        scrollToText("Bench press").performClick()
        composeTestRule.waitForIdle()

        scrollToDescription("Weight, set 2").assertIsNotFocused()
        assertTrue(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 2L) in events)
    }

    @Test
    fun `negative - moving between the two fields of one row does not write it`() {
        setScreen(state())

        scrollToDescription("Weight, set 2").requestFocus()
        scrollToDescription("Reps, set 2").requestFocus()
        composeTestRule.waitForIdle()

        // What has been left is a field, not the row. A commit per field would write the same
        // correction twice on the way across it.
        assertTrue(events.none { it is ActiveSessionEvent.SetEditCommitted })
    }

    @Test
    fun `a row taken out of the composition with typing in it is written on its way out`() {
        // The case focus does not cover. The workout is a lazy list and it collapses to its
        // titles for a reorder, so a card can be taken out of the composition with a half-typed
        // correction still in it — and a row that leaves that way never reports losing focus.
        val corrected = state(
            sets = listOf(
                loggedRow(id = 1L, weight = "60", reps = "10"),
                loggedRow(id = 2L, weight = "72.5", reps = "8").copy(isEdited = true),
            ),
        )
        var present by mutableStateOf(true)
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(
                    state = if (present) corrected else corrected.copy(exercises = emptyList()),
                    onEvent = events::add,
                )
            }
        }

        present = false
        composeTestRule.waitForIdle()

        assertTrue(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 2L) in events)
    }

    @Test
    fun `negative - a table of untouched rows leaving the composition writes nothing`() {
        val untouched = state()
        var present by mutableStateOf(true)
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(
                    state = if (present) untouched else untouched.copy(exercises = emptyList()),
                    onEvent = events::add,
                )
            }
        }

        present = false
        composeTestRule.waitForIdle()

        // A table nobody has typed into leaves the composition several times in an ordinary
        // workout — every scroll, every reorder — and none of those is a correction.
        assertTrue(events.none { it is ActiveSessionEvent.SetEditCommitted })
    }

    @Test
    fun `a logged row's rating is picked from the cell that shows it`() {
        setScreen(state(sets = listOf(loggedRow(id = 1L), loggedRow(id = 2L, rpe = 75))))

        scrollToDescription("Rate of perceived exertion, set 2, 7.5").performClick()
        composeTestRule.onNodeWithContentDescription("Rate of perceived exertion 9").performClick()

        assertEquals(
            listOf(ActiveSessionEvent.SetEditRpeChanged(BENCH_ROW, setId = 2L, rpe = 90)),
            events,
        )
    }

    @Test
    fun `a logged row's set type is picked from the cell that shows it`() {
        setScreen(state())

        scrollToDescription("Set type, set 2, Standard set").performClick()
        composeTestRule.onNodeWithText("Warm-up").performClick()

        assertEquals(
            listOf(
                ActiveSessionEvent.SetEditTypeChanged(
                    sessionExerciseId = BENCH_ROW,
                    setId = 2L,
                    setType = SetType.WARMUP,
                ),
            ),
            events,
        )
    }

    @Test
    fun `the sets around the one being corrected are still readable, and still live`() {
        setScreen(state())

        // The whole argument for there being no editor: a mistyped weight is *noticed* against
        // the sets above and below it — 60, 70, 85, 80 — and nothing on this screen ever covers
        // them or takes them out of reach.
        scrollToField("Weight, set 1", "60").assertIsDisplayed()
        scrollToDescription("Weight, set 2").assert(hasText("70"))
        scrollToDescription("Weight, set 3").assertIsDisplayed()
    }

    @Test
    fun `negative - there is no save, no cancel and no delete on a logged row`() {
        setScreen(state())

        // The three words the editor put under the fields. Saving is leaving the row, the way
        // back from a correction is typing the old number in, and deleting is a drag.
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete set").assertDoesNotExist()
        assertMissing(hasContentDescription("Save set 2"))
    }

    @Test
    fun `dragging a logged row off to the left deletes it`() {
        setScreen(state())

        // Started on the row's leading cell rather than on a value: the two value cells are
        // fields, and a drag across a field is how a cursor gets moved. Dragged well past the
        // left edge — the component clamps, so overshooting is exactly the "off the screen" the
        // gesture is.
        scrollToDescription("Set type, set 2, Standard set").performTouchInput {
            down(centerRight)
            moveBy(Offset(-OFF_THE_SCREEN, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L)), events)
    }

    @Test
    fun `negative - a drag let go part way across a row deletes nothing`() {
        setScreen(state())

        scrollToDescription("Set type, set 2, Standard set").performTouchInput {
            down(centerRight)
            moveBy(Offset(-PART_WAY, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(events.none { it is ActiveSessionEvent.SetDeleted })
    }

    @Test
    fun `the tick on a logged row takes the set back out of the count`() {
        setScreen(state())

        scrollTo(tick(setNumber = 2, completed = true)).performClick()

        assertEquals(
            listOf(
                ActiveSessionEvent.SetCompletionToggled(
                    sessionExerciseId = BENCH_ROW,
                    setId = 2L,
                    isCompleted = false,
                ),
            ),
            events,
        )
    }

    @Test
    fun `an un-ticked row offers the tick back rather than disappearing`() {
        setScreen(
            state(
                sets = listOf(
                    loggedRow(id = 1L, weight = "60", reps = "10"),
                    loggedRow(id = 2L, weight = "70", reps = "8", isCompleted = false),
                ),
            ),
        )

        // The values are still there — this is not a deletion — and the tick is the way back.
        scrollToDescription("Weight, set 2").assert(hasText("70"))
        scrollTo(tick(setNumber = 2, completed = false)).performClick()

        assertEquals(
            listOf(
                ActiveSessionEvent.SetCompletionToggled(
                    sessionExerciseId = BENCH_ROW,
                    setId = 2L,
                    isCompleted = true,
                ),
            ),
            events,
        )
    }

    @Test
    fun `negative - a paused workout's logged rows take neither typing nor a tick nor a drag`() {
        setScreen(state().copy(isPaused = true))

        scrollToDescription("Set type, set 2, Standard set").performTouchInput {
            down(centerRight)
            moveBy(Offset(-OFF_THE_SCREEN, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        // The values stay on screen, because they are still the user's copy of them, and nothing
        // about the row can be written: `EditSetUseCase` refuses a session that is not running,
        // so a field that still took keystrokes would be collecting them for a refusal.
        scrollToDescription("Weight, set 2").assert(hasText("70")).assertIsNotEnabled()
        scrollTo(tick(setNumber = 2, completed = true)).assertIsNotEnabled()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a refused correction is shown on the row that caused it`() {
        setScreen(
            state(
                sets = listOf(
                    loggedRow(id = 1L, weight = "60", reps = "10"),
                    LoggedSet(
                        id = 2L,
                        entry = SetEntry(
                            weight = "70",
                            reps = "0",
                            error = SetEntryError.Refused("reps must be within 1..10,000, was 0"),
                        ),
                        isCompleted = true,
                    ),
                ),
            ),
        )

        scrollToText("reps must be within 1..10,000, was 0").assertIsDisplayed()
        // And what was typed is still in the row: it is the only copy of it, and dropping it
        // would silently undo a correction the moment the user looked away from a typo.
        scrollToDescription("Reps, set 2").assert(hasText("0"))
    }

    @Test
    fun `a deleted set is offered back rather than announced and forgotten`() {
        setScreen(
            state().copy(
                deletedSet = loggedSet(id = 2, sessionExerciseId = 1, setNumber = 2),
            ),
        )

        composeTestRule.onNodeWithText("Set 2 deleted").assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").performClick()

        assertEquals(listOf(ActiveSessionEvent.SetDeleteUndone), events)
    }

    // ─── Supersets ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the gap between two exercises offers to pair them up`() {
        setScreen(state())

        scrollToDescription("Superset. Perform Bench press and Incline dumbbell press back to back")
            .performClick()

        assertEquals(listOf(ActiveSessionEvent.SupersetLinked(BENCH_ROW)), events)
    }

    @Test
    fun `a pair already performed back to back offers to break the join`() {
        setScreen(state(exercises = supersetExercises()))

        scrollToDescription("Superset A. Stop performing Incline dumbbell press and Cable fly back to back")
            .performClick()

        assertEquals(listOf(ActiveSessionEvent.SupersetUnlinked(INCLINE_ROW)), events)
    }

    @Test
    fun `the last exercise has no join under it, because there is nothing after it`() {
        setScreen(state())
        // Scrolled to the end first, so everything under the last card has been composed at all:
        // a lazy list lays out nothing that is off the bottom, and "not composed" would prove
        // nothing about what is drawn there.
        scrollToText("Add more exercises")

        val joinsBelowTheLast = composeTestRule
            .onAllNodes(hasContentDescription("Cable fly and", substring = true))
            .fetchSemanticsNodes()

        assertTrue(joinsBelowTheLast.isEmpty())
    }

    // ─── Editing the layout ─────────────────────────────────────────────────────────────

    @Test
    fun `the add action opens the picker and says so`() {
        val opened = mutableListOf<Boolean>()
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(
                    state = state(),
                    onEvent = events::add,
                    onPickExercises = opened::add,
                )
            }
        }

        scrollToText("Add more exercises").performClick()

        assertEquals(listOf(ActiveSessionEvent.AddExercisesRequested), events)
        // Adding to the workout, which takes as many exercises as the user ticks.
        assertEquals(listOf(false), opened)
    }

    @Test
    fun `each exercise in the list can be cut out`() {
        setScreen(state())

        scrollToDescription("Remove Cable fly from this workout").performClick()

        assertEquals(listOf(ActiveSessionEvent.ExerciseRemoved(sessionExerciseId = 3L)), events)
    }

    @Test
    fun `the swap on a card opens the picker and says which slot asked`() {
        val opened = mutableListOf<Boolean>()
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(
                    state = state(),
                    onEvent = events::add,
                    onPickExercises = opened::add,
                )
            }
        }

        scrollToDescription("Swap Cable fly for another exercise").performClick()

        assertEquals(
            listOf(ActiveSessionEvent.ExerciseSwapRequested(sessionExerciseId = 3L)),
            events,
        )
        // Replacing: the slot holds one exercise, and the picker is opened asking for one
        // rather than for a list it would come back from with an answer to spare.
        assertEquals(listOf(true), opened)
    }

    @Test
    fun `the slot being swapped goes read-only while the write is in flight`() {
        setScreen(state().copy(swappingExerciseId = 2L))

        scrollToDescription("Remove Incline dumbbell press from this workout").assertIsNotEnabled()
        scrollToDescription("Remove Cable fly from this workout").assertIsEnabled()
    }

    @Test
    fun `a slot that cannot be swapped says why`() {
        setScreen(state().copy(message = ActiveSessionMessage.SWAP_HAS_SETS))

        composeTestRule
            .onNodeWithText(
                "That exercise has sets logged against it. " +
                    "Delete them first, or add the new exercise instead.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `holding an exercise collapses the workout to its titles`() {
        setScreen(state())

        hold("Bench press")

        // Every exercise still on the page, and nothing else: no set tables, no stat chips, no
        // way to add more. What is left is what a reorder needs to see — see
        // `CollapsedExerciseRow`.
        composeTestRule.onNodeWithContentDescription("1. Bench press").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("2. Incline dumbbell press").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("3. Cable fly").assertIsDisplayed()
        assertMissing(hasContentDescription("Weight, set 2"))
        assertMissing(hasText("Add more exercises"))
        assertMissing(hasText("5 sets"))
    }

    @Test
    fun `letting go brings the workout back, and reports nothing`() {
        setScreen(state())

        hold("Bench press")
        release()

        scrollToDescription("Weight, set 2").assert(hasText("70"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a press too short to be a hold leaves the workout alone`() {
        setScreen(state())

        composeTestRule.onNode(inWorkout("Bench press")).performTouchInput {
            down(center)
            advanceEventTime(TOO_SHORT)
            up()
        }
        composeTestRule.waitForIdle()

        scrollToDescription("Weight, set 2").assert(hasText("70"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `dragging a held exercise down the list reports the order it was let go in`() {
        setScreen(state())

        hold("Bench press")
        drag(rows = 2.5f)
        release()

        assertEquals(
            listOf(ActiveSessionEvent.ExercisesReordered(listOf(2L, 3L, 1L))),
            events,
        )
    }

    @Test
    fun `the numbers follow the exercises as they are dragged`() {
        setScreen(state())

        hold("Bench press")
        drag(rows = 1.2f)

        // The bench is second now, and the incline it passed is first. The numbers are the
        // order being made rather than the one stored, which has not been written yet.
        composeTestRule.onNodeWithContentDescription("1. Incline dumbbell press").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("2. Bench press").assertIsDisplayed()
    }

    @Test
    fun `a refused reorder puts the workout back in the order the store kept`() {
        // The one outcome the re-seed off `storedOrder` cannot see: the write changed nothing,
        // so the stored order is the list it already was and a `remember` keyed on it does not
        // re-run. Left alone, the screen goes on drawing an order the database rejected, and a
        // lifter works down a list the log disagrees with.
        var refused by mutableStateOf(0)
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(
                    state = state().copy(refusedReorders = refused),
                    onEvent = events::add,
                )
            }
        }

        hold("Bench press")
        drag(rows = 1.2f)
        // The order under the finger: the bench has passed the incline.
        composeTestRule.onNodeWithContentDescription("2. Bench press").assertIsDisplayed()
        release()

        // The store refuses, and its exercises come back exactly as they went in.
        refused = 1
        composeTestRule.waitForIdle()

        // Picked up again — the numbered list is how the drawn order is readable — the workout
        // is in the order the store kept.
        hold("Bench press")
        composeTestRule.onNodeWithContentDescription("1. Bench press").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("2. Incline dumbbell press").assertIsDisplayed()
    }

    @Test
    fun `a reorder that lands is left drawn as it was made`() {
        // The other half of the pair above: anything happening on the screen that is *not* a
        // reorder refusal must not reach in and re-seed the list under a gesture that succeeded.
        var message by mutableStateOf<ActiveSessionMessage?>(null)
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(state = state().copy(message = message), onEvent = events::add)
            }
        }

        hold("Bench press")
        drag(rows = 1.2f)
        release()

        message = ActiveSessionMessage.RENAME_FAILED
        composeTestRule.waitForIdle()

        hold("Bench press")
        composeTestRule.onNodeWithContentDescription("2. Bench press").assertIsDisplayed()
    }

    @Test
    fun `a paused workout cannot be picked up`() {
        setScreen(state().copy(isPaused = true))

        hold("Bench press")

        // Still the whole card: a paused session is read-only, and `ReorderSessionExercisesUseCase`
        // would refuse the write anyway.
        scrollToDescription("Weight, set 2").assert(hasText("70"))
    }

    @Test
    fun `a workout of one exercise cannot be picked up either`() {
        setScreen(state(exercises = listOf(benchRow())))

        hold("Bench press")

        scrollToDescription("Weight, set 2").assert(hasText("70"))
    }

    @Test
    fun `an exercise offers move up and move down without anything being held`() {
        setScreen(state())

        // A drag is a gesture an assistive technology cannot perform, so the same two moves are
        // actions on every exercise, at all times. Two of them here rather than three because
        // the workout is a lazy list and the third card is below the fold — which is the
        // ordinary case, and the reason the moves are on the exercises rather than on a mode.
        val actions = reorderableItems().map { item ->
            item.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        }

        assertEquals(listOf(listOf("Move down"), listOf("Move up", "Move down")), actions)
    }

    @Test
    fun `a move action reports the new order`() {
        setScreen(state())

        val moveDown = reorderableItems()[0].fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == "Move down" }
        composeTestRule.runOnUiThread { moveDown.action() }
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(ActiveSessionEvent.ExercisesReordered(listOf(2L, 1L, 3L))),
            events,
        )
    }

    @Test
    fun `a workout with nothing in it says how to fill it`() {
        setScreen(state(exercises = emptyList()))

        scrollToText("No exercises yet").assertIsDisplayed()
        scrollToText("Add more exercises").assertIsDisplayed()
    }

    @Test
    fun `an exercise that cannot be cut says why on the page it happened`() {
        setScreen(state().copy(message = ActiveSessionMessage.EXERCISE_HAS_SETS))

        composeTestRule
            .onNodeWithText(
                "That exercise has sets logged against it. " +
                    "Delete the sets first, or discard the whole workout.",
            )
            .assertIsDisplayed()
    }

    // ─── Naming ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the header's name is the way to change it`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("Push day · Upper, Elapsed 0:23:14").performClick()

        assertEquals(listOf(ActiveSessionEvent.NameEditRequested), events)
    }

    /*
     * What the dialog does once it is up is `SessionNameDialogTest`'s, in `:core:ui`. It cannot
     * be driven from here: an `AlertDialog` composed over a whole screen does not reach idle
     * under Robolectric — the dialog and the screen behind it remeasure each other until the
     * test JVM runs out of heap. What this screen owes is the tap above and the fallback title
     * below, and both are asserted.
     */

    @Test
    fun `a session with no name falls back to the day it happened`() {
        setScreen(state().copy(header = state().header?.copy(name = null)))

        // The date the fixture's session started, as the merged title block announces it — the
        // eyebrow's uppercase is typography, and the description carries the name as written.
        composeTestRule
            .onNodeWithContentDescription(formatSessionDate(STARTED_AT), substring = true)
            .assertIsDisplayed()
    }

    // ─── Pause ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the pause control reports the toggle`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("Pause the session").performClick()

        assertEquals(listOf(ActiveSessionEvent.PauseToggled), events)
    }

    @Test
    fun `a paused session offers resume and goes read-only`() {
        setScreen(state(entry = SetEntry(weight = "80", reps = "5")).copy(isPaused = true))

        composeTestRule.onNodeWithContentDescription("Resume the session").assertIsDisplayed()
        scrollToText("Complete set 3").assertIsNotEnabled()
    }

    @Test
    fun `a paused session cannot be sent off to add exercises`() {
        // `AddSessionExercisesUseCase` refuses a session that is not active, so a live button
        // here would walk the user through the whole picker to refuse what they chose at the
        // end of it.
        setScreen(state().copy(isPaused = true))

        scrollToText("Add more exercises").assertIsNotEnabled()
    }

    @Test
    fun `a paused session's set-type menu does not open`() {
        // The whole card goes read-only, the leading cell included: `editEntry` saves a draft on
        // every change and has no pause of its own to check, so a live picker is a paused workout
        // still taking edits.
        setScreen(state(entry = SetEntry(weight = "80", reps = "5")).copy(isPaused = true))

        scrollToDescription("Set type, set 3, Standard set").performClick()

        composeTestRule.onNodeWithText("Warm-up").assertDoesNotExist()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a rating menu left open across a pause stops taking ratings`() {
        // The tap that *opens* the menu is withheld while paused, so the only way to be looking
        // at one on a paused session is to have opened it first — which is what a lifter who is
        // interrupted mid-set does. It shuts rather than staying up over a card that can no
        // longer be written to.
        var paused by mutableStateOf(false)
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(
                    state = state(entry = SetEntry(weight = "80", reps = "5")).copy(isPaused = paused),
                    onEvent = events::add,
                )
            }
        }
        scrollToDescription("Rate of perceived exertion, set 3, not set").performClick()

        paused = true
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion 8.5")
            .assertDoesNotExist()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a paused clock is announced as paused`() {
        setScreen(state().copy(isPaused = true))

        // Inside the merged title block — see `SessionTitle` — so by substring.
        composeTestRule.onNodeWithContentDescription("Paused at 0:23:14", substring = true).assertIsDisplayed()
    }

    // ─── Finishing ──────────────────────────────────────────────────────────────────────

    @Test
    fun `END reports the finish`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("End the session").performClick()

        assertEquals(listOf(ActiveSessionEvent.FinishRequested), events)
    }

    @Test
    fun `the end confirmation says what is saved and what is still to go`() {
        setScreen(state().copy(confirmingFinish = true))

        composeTestRule.onNodeWithText("End this workout?").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 logged sets will be saved.").assertIsDisplayed()
    }

    @Test
    fun `confirming the end reports it`() {
        setScreen(state().copy(confirmingFinish = true))

        composeTestRule.onNodeWithText("End workout").performClick()

        assertEquals(listOf(ActiveSessionEvent.FinishConfirmed), events)
    }

    @Test
    fun `keeping training dismisses the end confirmation`() {
        setScreen(state().copy(confirmingFinish = true))

        composeTestRule.onNodeWithText("Keep training").performClick()

        assertEquals(listOf(ActiveSessionEvent.FinishDismissed), events)
    }

    @Test
    fun `the header's controls go inert while the workout is being closed out`() {
        // The bar stays on screen for as long as the write takes. A pause dispatched into that
        // window either mutates a session being closed out or is refused and reported over a
        // screen on its way to the recap.
        setScreen(state().copy(isFinishing = true))

        composeTestRule.onNodeWithContentDescription("Pause the session").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Discard the session").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("End the session").assertIsNotEnabled()
    }

    @Test
    fun `a finish refused for having nothing logged is said on the page, not in a snackbar`() {
        setScreen(
            state(sets = emptyList()).copy(message = ActiveSessionMessage.NO_SETS_LOGGED),
        )

        composeTestRule
            .onNodeWithText("Log at least one set before finishing, or discard the workout.")
            .assertIsDisplayed()
        // Still on the screen: the refusal is a state, not an exit.
        composeTestRule.onNode(inWorkout("Bench press")).assertIsDisplayed()
    }

    // ─── Discarding ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the discard control asks rather than discarding`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("Discard the session").performClick()

        assertEquals(listOf(ActiveSessionEvent.DiscardRequested), events)
    }

    @Test
    fun `the confirmation says what it costs`() {
        setScreen(state().copy(confirmingDiscard = true))

        composeTestRule.onNodeWithText("Discard this workout?").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("2 logged sets will be deleted. This cannot be undone.")
            .assertIsDisplayed()
    }

    @Test
    fun `a workout with nothing logged says there is nothing to lose`() {
        setScreen(
            state(sets = emptyList()).copy(
                confirmingDiscard = true,
                stats = SessionStats(
                    totalSets = 0,
                    totalVolume = 0,
                    durationSeconds = 0,
                    exerciseBreakdown = emptyList(),
                ),
            ),
        )

        composeTestRule
            .onNodeWithText("Nothing has been logged yet, so nothing will be lost.")
            .assertIsDisplayed()
    }

    @Test
    fun `confirming the discard reports it`() {
        setScreen(state().copy(confirmingDiscard = true))

        composeTestRule.onNodeWithText("Discard").performClick()

        assertEquals(listOf(ActiveSessionEvent.DiscardConfirmed), events)
    }

    @Test
    fun `keeping the workout dismisses the confirmation`() {
        setScreen(state().copy(confirmingDiscard = true))

        composeTestRule.onNodeWithText("Keep training").performClick()

        assertEquals(listOf(ActiveSessionEvent.DiscardDismissed), events)
    }

    // ─── Surviving a configuration change ───────────────────────────────────────────────

    @Test
    fun `the screen keeps no entry state of its own, so a restore redraws the fields`() {
        // Rotation is answered in two halves. The ViewModel outlives the activity and is what
        // holds the fields — `ActiveSessionViewModelTest` covers that half. This is the other:
        // that the screen puts nothing of the entry into a plain `remember`, which is the way
        // it *could* still lose a half-typed set across a configuration change. `StateRestorationTester`
        // recreates the composition the way the framework does, and the fields come back because
        // they were never here to begin with.
        val restorationTester = StateRestorationTester(composeTestRule)
        val entered = state(entry = SetEntry(weight = "82.5", reps = "5", rpe = 85))

        restorationTester.setContent {
            TitanTheme {
                ActiveSessionScreen(state = entered, onEvent = events::add)
            }
        }
        composeTestRule.onNodeWithText("82.5").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("82.5").assertIsDisplayed()
        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        scrollToText("Complete set 3").assertIsEnabled()
    }

    // ─── The rest between two sets ──────────────────────────────────────────────────────

    /*
     * The sheet is a `ModalBottomSheet`, which draws into a window of its own — and Robolectric
     * does not lay that window out: its nodes are in the tree, and they are at a rect no finger
     * could reach. So these assert existence rather than display, and press through the
     * semantics action rather than through a touch. What is being asserted is the wiring — which
     * control raises which event — and none of that is about where the sheet ends up on screen.
     * How the sheet itself is drawn is `RestTimerSheetTest`'s subject, in `:core:ui`.
     */

    @Test
    fun `the rest sheet shows the countdown and the set that ends it`() {
        setScreen(resting(), clocks = restingClocks())

        // Twice over: the sheet's figure and the dock's countdown behind it, which have to be the
        // same reading — two clocks for one rest, one of them rounded the other way, is the bug
        // `RestCountdown` and `RestTimerDock` share a formatter to prevent.
        composeTestRule.onAllNodesWithContentDescription("1:23 left").assertCountEquals(2)
        // The supporting line under the figure: how long the rest is, and what is coming.
        composeTestRule.onNodeWithText("of 2:00 · Next 80 kg × 5").assertExists()
    }

    @Test
    fun `the sheet's controls move the countdown, and its button ends it`() {
        setScreen(resting(), clocks = restingClocks())

        composeTestRule.onNodeWithContentDescription("Extend the rest by 15 seconds").invoke()
        composeTestRule.onNodeWithContentDescription("Shorten the rest by 15 seconds").invoke()
        composeTestRule.onNodeWithText("Skip rest").invoke()

        assertEquals(
            listOf(
                ActiveSessionEvent.RestAdjusted(15.seconds),
                ActiveSessionEvent.RestAdjusted((-15).seconds),
                ActiveSessionEvent.RestSkipped,
            ),
            events,
        )
    }

    @Test
    fun `dismissing the sheet asks for it to be hidden, and not for the rest to end`() {
        setScreen(resting(), clocks = restingClocks())

        // The scrim, which is what a tap outside the sheet and a swipe down both land on.
        composeTestRule.onNodeWithContentDescription("Close sheet").invoke()
        composeTestRule.waitForIdle()

        // Dismissed, not skipped. The countdown is untouched by the gesture — which is the
        // whole point of it: what the user is reaching for is the set underneath.
        assertEquals(listOf(ActiveSessionEvent.RestSheetDismissed), events)
    }

    @Test
    fun `the sheet waits for its countdown rather than opening on a zeroed clock`() {
        // The flag is the screen state's and the countdown the clocks', on two flows, so a rest
        // that has just started can raise the one a frame before the other arrives.
        val clocks = mutableStateOf(SessionClocks())
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(state = resting(), onEvent = events::add, clocks = { clocks.value })
            }
        }

        composeTestRule.onNodeWithContentDescription("Extend the rest by 15 seconds").assertDoesNotExist()

        clocks.value = restingClocks()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Extend the rest by 15 seconds").assertExists()
    }

    @Test
    fun `a rest that ends takes the sheet off screen, and does not strand it there`() {
        // The sheet is not dropped straight out of composition any more — it is asked to hide
        // and stays composed until the animation is done, which is what makes a skip and an
        // expiry slide down instead of blinking out. This is the other half of that: a hide
        // that never completed would leave a panel sitting over the workout for good.
        val state = mutableStateOf(resting())
        val clocks = mutableStateOf(restingClocks())
        composeTestRule.setContent {
            TitanTheme {
                ActiveSessionScreen(state = state.value, onEvent = events::add, clocks = { clocks.value })
            }
        }

        state.value = state.value.copy(isRestSheetOpen = false)
        clocks.value = clocks.value.copy(rest = RestTimerState.Finished)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Extend the rest by 15 seconds")
            .assertDoesNotExist()
    }

    @Test
    fun `the dock carries the countdown once the sheet is away, and a tap brings it back`() {
        setScreen(resting().copy(isRestSheetOpen = false), clocks = restingClocks())

        // With the sheet away the dock is the live region, and a live region says the
        // quarter-minute — "1:30", not "1:23" — because what it says is what it announces every
        // time the text changes. See `RestTimerDock`'s `announces`.
        composeTestRule
            .onNodeWithContentDescription("Resting, 1:30 left")
            .assertIsDisplayed()
            .assert(expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .performClick()

        assertEquals(listOf(ActiveSessionEvent.RestWindowRequested), events)
    }

    @Test
    fun `the dock sits at the bottom of the screen, under the workout`() {
        // The whole point of the dock: the thumb's end of the screen, not the header's.
        setScreen(resting().copy(isRestSheetOpen = false), clocks = restingClocks())

        val pill = composeTestRule.onNodeWithContentDescription("Resting, 1:30 left").fetchSemanticsNode()
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        assertTrue(pill.boundsInRoot.top > root.boundsInRoot.height / 2)
    }

    @Test
    fun `between two sets the dock validates the set being typed, on the exercise it belongs to`() {
        setScreen(state(entry = SetEntry(weight = "80", reps = "5")))

        composeTestRule.onNode(dockAction("Complete set 3", "Bench press")).assertIsEnabled().performClick()

        assertEquals(listOf(ActiveSessionEvent.SetLogged(BENCH_ROW)), events)
    }

    @Test
    fun `the dock follows the exercise last worked on`() {
        setScreen(state().copy(workingExerciseId = FLY_ROW))

        composeTestRule.onNode(dockAction("Complete set 1", FLY.name)).assertExists()
    }

    @Test
    fun `reopened with nothing remembered, the dock picks up where the last set was logged`() {
        // The fly holds the newest set, so it is where the lifter was when the process went —
        // not the bench at the top of the workout.
        val fly = SessionExerciseRow(
            sessionExerciseId = FLY_ROW,
            position = 2,
            exercise = FLY,
            sets = listOf(loggedRow(id = 9L, weight = "20", reps = "12")),
        )
        setScreen(state(exercises = listOf(benchRow(), fly)))

        composeTestRule.onNode(dockAction("Complete set 2", FLY.name)).assertExists()
    }

    @Test
    fun `the dock's validate goes inert over a row with nothing typed in it`() {
        setScreen(state().copy(workingExerciseId = BENCH_ROW))

        composeTestRule.onNode(dockAction("Complete set 3", "Bench press")).assertIsNotEnabled()
    }

    @Test
    fun `the dock's validate goes inert over a slot whose exercise is being swapped`() {
        // The card's own button is read-only while the swap is written; a set validated from
        // the dock would land on whichever exercise won the race.
        setScreen(state(entry = SetEntry(weight = "80", reps = "5")).copy(swappingExerciseId = BENCH_ROW))

        composeTestRule.onNode(dockAction("Complete set 3", "Bench press")).assertIsNotEnabled()
    }

    @Test
    fun `between two sets the square beside the dock's action starts a rest`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("Start a rest").assertIsDisplayed().performClick()

        assertEquals(listOf(ActiveSessionEvent.RestWindowRequested), events)
    }

    @Test
    fun `with no exercise to point at, starting a rest is the dock's action`() {
        setScreen(state(exercises = emptyList()))

        composeTestRule.onNodeWithText("Start a rest").assertIsDisplayed().performClick()

        assertEquals(listOf(ActiveSessionEvent.RestWindowRequested), events)
    }

    @Test
    fun `the dock goes inert over a workout on its way out`() {
        setScreen(state().copy(isFinishing = true))

        composeTestRule.onNodeWithContentDescription("Start a rest").performClick()

        assertEquals(emptyList<ActiveSessionEvent>(), events)
    }

    @Test
    fun `the clock goes inert over a workout on its way out, with the dock`() {
        setScreen(state().copy(isFinishing = true))

        composeTestRule.onNodeWithText("0:23:14", useUnmergedTree = true).performClick()

        // The title block around it is inert too, so the tap starts nothing: no rest the finish
        // has already taken down, and no rename of a session already stamped closed.
        assertTrue(ActiveSessionEvent.RestWindowRequested !in events)
        assertTrue(ActiveSessionEvent.NameEditRequested !in events)
    }

    @Test
    fun `the name goes inert over a workout on its way out`() {
        setScreen(state().copy(isFinishing = true))

        composeTestRule.onNodeWithText("Push day · Upper", substring = true, ignoreCase = true, useUnmergedTree = true)
            .performClick()

        assertTrue(ActiveSessionEvent.NameEditRequested !in events)
    }

    @Test
    fun `tapping the clock asks for the rest window between two sets`() {
        setScreen(state())

        composeTestRule.onNodeWithText("0:23:14", useUnmergedTree = true).performClick()

        assertEquals(listOf(ActiveSessionEvent.RestWindowRequested), events)
    }

    @Test
    fun `the clock is a full-size target, edge to edge`() {
        // Measured here because the accessibility gate cannot: the clock's node is cleared into
        // the merged title block, so ATF never sees it. A touch at either edge of its 48dp is
        // the clock's, the top one included — that is the side the rename target sits on.
        setScreen(state())
        val clock = composeTestRule.onNodeWithText("0:23:14", useUnmergedTree = true)

        clock.assertHeightIsAtLeast(48.dp)
        clock.performTouchInput { click(Offset(centerX, top + 1f)) }
        clock.performTouchInput { click(Offset(centerX, bottom - 1f)) }

        assertEquals(List(2) { ActiveSessionEvent.RestWindowRequested }, events)
    }

    @Test
    fun `tapping the name still renames, and does not touch the rest`() {
        setScreen(state())

        composeTestRule.onNodeWithContentDescription("Push day · Upper, Elapsed 0:23:14").performClick()

        assertEquals(listOf(ActiveSessionEvent.NameEditRequested), events)
    }

    @Test
    fun `nothing on the header says rest when no rest is running`() {
        // The pill is the one thing on this screen wearing the accent and changing on its own.
        // One that sat there reading 0:00 between sets would spend that on nothing.
        setScreen(state())

        composeTestRule.onAllNodesWithContentDescription("0:00 left").assertCountEquals(0)
    }

    /** [text] on a card in the workout, rather than on the rest dock under it. */
    private fun inWorkout(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasScrollToNodeAction())

    /** The dock's primary button: the one node carrying both its action and its exercise. */
    private fun dockAction(
        label: String,
        exercise: String,
    ): SemanticsMatcher = hasText(label) and hasText(exercise, substring = true)

    // ─── Support ────────────────────────────────────────────────────────────────────────

    /**
     * Brings [matcher]'s node into view before touching it.
     *
     * The workout is a scrolling list on a phone-sized screen, so the set-type chips, the note
     * and the two lists below the card are all off the bottom of a 360x640 viewport — and a node
     * that is not displayed cannot be clicked or asserted on. Scrolling first is what the user
     * does, so it is what the test does.
     *
     * Two steps, because they do different things. `performScrollToNode` on the outer
     * `LazyColumn` composes the item the node is in — a lazy list does not lay out what is off
     * the bottom, so nothing else can find it first. `performScrollTo` on the node itself then
     * brings the node into view through *every* scrollable above it, which is what the card
     * being taller than the viewport and the set-type chips being a `LazyRow` both need.
     */
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        composeTestRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
        return composeTestRule.onNode(matcher).performScrollTo()
    }

    /**
     * Presses an exercise and keeps holding it, which is what picks it up.
     *
     * The gesture carries on across calls — `hold`, then [drag], then [release] — the way it
     * does under a finger. Compose keeps the pointer state per root, so a list that has changed
     * shape in between is not a new gesture, which is exactly the case this screen is built for.
     */
    private fun hold(exercise: String) {
        // Pressed at the exercise's own position but dispatched through the root, and every
        // step of the gesture after it goes the same way: the card the press lands on stops
        // existing the moment the hold takes — that is the point of it — and a gesture cannot
        // be continued through a node that has gone.
        val target = composeTestRule.onNodeWithText(exercise).fetchSemanticsNode().boundsInRoot
        composeTestRule.onRoot().performTouchInput {
            down(target.center)
            advanceEventTime(HELD_LONG_ENOUGH)
            // A move of nothing, to deliver an event the hold can be measured against.
            moveBy(Offset.Zero)
        }
        composeTestRule.waitForIdle()
    }

    /** Drags the held exercise down by [rows] collapsed rows, one row at a time. */
    private fun drag(rows: Float) {
        val step = collapsedRowHeight()
        var travelled = 0f
        while (travelled < rows) {
            val next = minOf(1f, rows - travelled)
            // One row at a time, with a frame in between: a crossing is only decided once the
            // layout it caused has happened, which on a device is what the frames between two
            // touch events do.
            composeTestRule.onRoot().performTouchInput { moveBy(Offset(0f, step * next)) }
            composeTestRule.waitForIdle()
            travelled += next
        }
    }

    private fun release() {
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
    }

    /** The exercises as they are while one is held, in the order they are drawn. */
    private fun reorderableItems(): List<SemanticsNodeInteraction> {
        // The moves rather than "anything with a custom action": every logged set row carries one
        // of its own now — the delete the drag stands in for — so having actions no longer means
        // being an exercise.
        val matcher = SemanticsMatcher("has a move action") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
                .any { it.label.startsWith("Move ") }
        }
        val count = composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().size
        return (0 until count).map { composeTestRule.onAllNodes(matcher)[it] }
    }

    /** How tall a collapsed row is, which is how far the finger travels to cross one. */
    private fun collapsedRowHeight(): Float = reorderableItems().first().fetchSemanticsNode().size.height.toFloat()

    /** Fails unless nothing on the screen matches, which is how a gone thing is asserted. */
    private fun assertMissing(matcher: SemanticsMatcher) {
        assertTrue(composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty())
    }

    /**
     * The node in the workout with [text] — in the list, so the rest dock under it, which names
     * the set and the exercise being worked, is not a second match.
     */
    private fun scrollToText(text: String): SemanticsNodeInteraction = scrollTo(inWorkout(text))

    private fun scrollToDescription(description: String): SemanticsNodeInteraction =
        scrollTo(hasContentDescription(description))

    /**
     * A set's tick, by its number and its state — "Completed, Set 2, checkbox". The label alone
     * is not unique on a screen of three exercises; the label and the state together name the
     * one row the test means, exactly as the old state-bearing labels did.
     */
    private fun tick(
        setNumber: Int,
        completed: Boolean,
    ): SemanticsMatcher =
        hasContentDescription("Set $setNumber") and
            hasStateDescription(if (completed) "Completed" else "Not completed")

    /**
     * The field called [description] that currently holds [value].
     *
     * A set is named by its number *within its exercise* — "Weight, set 1" — and this screen holds
     * the whole workout, so the low numbers are not unique: an exercise with nothing logged
     * against it puts its entry row under the same name as the first card's first set. Naming the
     * value as well is what picks one out, and it is what these tests mean anyway.
     */
    private fun scrollToField(
        description: String,
        value: String,
    ): SemanticsNodeInteraction = scrollTo(hasContentDescription(description) and hasText(value))

    /**
     * @param fontScale the system font scale the screen is drawn at, for the one bar every
     *   screen is held to — see docs/ACCESSIBILITY.md.
     * @param widthDp narrower than the class's own viewport, for a layout that changes shape
     *   with the width; null draws at the viewport.
     */
    private fun setScreen(
        state: ActiveSessionUiState,
        fontScale: Float = 1f,
        widthDp: Int? = null,
        clocks: SessionClocks = SessionClocks(elapsed = 23.minutes + 14.seconds),
    ) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TitanTheme {
                    Box(modifier = if (widthDp != null) Modifier.width(widthDp.dp) else Modifier) {
                        ActiveSessionScreen(state = state, onEvent = events::add, clocks = { clocks })
                    }
                }
            }
        }
    }

    /** The target line under the bench's entry row, as `benchTarget` reads. */
    private fun targetLine(): SemanticsMatcher =
        hasContentDescription("Target 82.5 kg × 3 · +2.5 kg. Hit the top of the range last time — add 2.5 kg")

    /** What the engine makes of last week's 80 × 6 under the strength range: the step goes on. */
    private fun benchTarget() =
        LoadSuggestion(
            weight = 82_500_000L,
            reps = 3,
            reason = SuggestionReason.INCREASE,
            previousWeight = 80_000_000L,
            basis = PastPerformance(
                listOf(loggedSet(id = 100L, sessionExerciseId = 99L, setNumber = 1, reps = 6, weight = 80_000_000L)),
            ),
        )

    /**
     * A push day two sets into its first exercise, with three exercises in it.
     *
     * [sets] and [entry] belong to the *first* exercise, which is the one most of these tests
     * touch; [exercises] replaces the whole list for the ones that are about the shape of the
     * workout rather than about logging a set.
     */
    private fun state(
        sets: List<LoggedSet> = listOf(
            loggedRow(id = 1L, weight = "60", reps = "10"),
            loggedRow(id = 2L, weight = "70", reps = "8"),
        ),
        entry: SetEntry = SetEntry(),
        exercises: List<SessionExerciseRow> = listOf(
            benchRow(sets = sets, entry = entry),
            SessionExerciseRow(sessionExerciseId = INCLINE_ROW, position = 1, exercise = INCLINE),
            SessionExerciseRow(sessionExerciseId = FLY_ROW, position = 2, exercise = FLY),
        ),
        header: ActiveSessionHeader? = ActiveSessionHeader(
            name = "Push day · Upper",
            startedAt = STARTED_AT,
            progress = SessionProgress.of(completed = 1, total = 3),
        ),
    ) = ActiveSessionUiState(
        header = header,
        stats = SessionStats(
            totalSets = exercises.sumOf { it.setCount },
            totalVolume = 1_160_000_000L,
            durationSeconds = 1_394,
            exerciseBreakdown = emptyList(),
        ),
        exercises = exercises,
        isLoading = false,
    )

    // ─── The warm-up and the cool-down (#214) ───────────────────────────────────────────

    @Test
    fun `the warm-up card lists what it would add`() {
        setScreen(state().copy(warmup = warmupCard()))

        scrollToText("Arm circles").assertIsDisplayed()
        scrollToText("Band pull-apart").assertIsDisplayed()
        // The ramp-up, under the lift it belongs to.
        scrollToText("Bench press ramp-up").assertIsDisplayed()
        scrollToText("40 kg × 5").assertIsDisplayed()
    }

    @Test
    fun `the warm-up heading is a heading, and the fold says which way it is`() {
        setScreen(state().copy(warmup = warmupCard()))

        scrollToText("WARM-UP").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        scrollTo(hasStateDescription("Expanded")).assertIsDisplayed()
    }

    @Test
    fun `adding the warm-up reports it once`() {
        setScreen(state().copy(warmup = warmupCard()))

        scrollToText("Add to session").performClick()

        assertEquals(listOf(ActiveSessionEvent.MobilityAccepted(MobilityKind.WARMUP)), events)
    }

    @Test
    fun `sending the warm-up away reports it`() {
        setScreen(state().copy(warmup = warmupCard()))

        scrollToText("Not now").performClick()

        assertEquals(listOf(ActiveSessionEvent.MobilityDismissed(MobilityKind.WARMUP)), events)
    }

    @Test
    fun `folding the card reports the state it is going to`() {
        setScreen(state().copy(warmup = warmupCard()))

        scrollToText("WARM-UP").performClick()

        assertEquals(
            listOf(ActiveSessionEvent.MobilityExpanded(MobilityKind.WARMUP, expanded = false)),
            events,
        )
    }

    @Test
    fun `a card that has been taken says so and offers nothing more`() {
        setScreen(state().copy(warmup = warmupCard().copy(isAdded = true, isExpanded = false)))

        scrollToText("Added").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Add to session").assertCountEquals(0)
    }

    @Test
    fun `a paused workout cannot take a suggestion`() {
        setScreen(state().copy(warmup = warmupCard(), isPaused = true))

        scrollToText("Add to session").assertIsNotEnabled()
    }

    @Test
    fun `no card is drawn when there is nothing to suggest`() {
        setScreen(state())

        composeTestRule.onAllNodesWithText("WARM-UP").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("COOL-DOWN").assertCountEquals(0)
    }

    /** A push day's warm-up: two movements and an 80 kg bench to ramp into. */
    private fun warmupCard() =
        MobilityCard(
            movements = listOf(
                MobilityMovement(exerciseId = 10L, name = "Arm circles"),
                MobilityMovement(exerciseId = 11L, name = "Band pull-apart"),
            ),
            rampUp = listOf(
                PlannedSet(setType = SetType.WARMUP, reps = 5, weight = 40_000_000L),
                PlannedSet(setType = SetType.WARMUP, reps = 3, weight = 60_000_000L),
            ),
            rampUpExerciseName = "Bench press",
        )

    /**
     * Presses a node through its click *action* rather than through a finger.
     *
     * For the rest sheet, whose window Robolectric leaves at a rect outside the screen — a
     * touch aimed at it is refused before the handler is ever reached. The action is the same
     * one a tap ends up invoking, and the same one a screen reader uses.
     */
    private fun SemanticsNodeInteraction.invoke(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    /** The push day, mid-rest, with the sheet up over it. The countdown is [restingClocks]. */
    private fun resting() =
        state().copy(
            isRestSheetOpen = true,
            restTarget = RestTarget(weight = 80_000_000L, reps = 5),
        )

    /** The countdown [resting] is in the middle of, read beside it as the screen reads it. */
    private fun restingClocks() =
        SessionClocks(
            elapsed = 23.minutes + 14.seconds,
            rest = RestTimerState.Running(remaining = 83.seconds, total = 2.minutes),
        )

    /** The bench's slot holding a plank instead, with nothing logged against it yet. */
    private fun plankRow(entry: SetEntry = SetEntry()) =
        benchRow(
            sets = emptyList(),
            entry = entry,
        ).copy(exercise = BENCH.copy(name = "Plank", type = ExerciseType.ISOMETRIC))

    /** The bench press, first in the workout, two sets in. */
    private fun benchRow(
        sets: List<LoggedSet> = listOf(
            loggedRow(id = 1L, weight = "60", reps = "10"),
            loggedRow(id = 2L, weight = "70", reps = "8"),
        ),
        entry: SetEntry = SetEntry(),
    ) = SessionExerciseRow(
        sessionExerciseId = BENCH_ROW,
        position = 0,
        exercise = BENCH,
        sets = sets,
        entry = entry,
    )

    /**
     * The bench press with last week's numbers against every row of it, entry row included.
     *
     * Separate from [benchRow] rather than a parameter on it, because almost every test on this
     * screen is about a card with nothing behind it and would otherwise carry a reference it
     * never looks at.
     */
    private fun benchedBefore() =
        benchRow(
            sets = listOf(
                loggedRow(id = 1L, weight = "60", reps = "10")
                    .copy(previous = PreviousSet(weight = 60_000_000L, reps = 10)),
                loggedRow(id = 2L, weight = "70", reps = "8")
                    .copy(previous = PreviousSet(weight = 70_000_000L, reps = 8)),
            ),
        ).copy(
            entryPrevious = PreviousSet(weight = 75_000_000L, reps = 6),
            hasHistory = true,
        )

    /** The heaviest bench on record, as the card is handed it. */
    private fun benchRecord() =
        PersonalRecord(
            id = 1,
            exerciseId = 1,
            workoutSetId = 1,
            type = PrType.MAX_WEIGHT,
            weight = 92_000_000,
            achievedAt = 0,
        )

    /** The same three exercises, with the last two run as a superset. */
    private fun supersetExercises() =
        listOf(
            benchRow(),
            SessionExerciseRow(
                sessionExerciseId = INCLINE_ROW,
                position = 1,
                exercise = INCLINE,
                supersetLabel = "A1",
                supersetGroupName = "A",
                isLinkedToNext = true,
            ),
            SessionExerciseRow(
                sessionExerciseId = FLY_ROW,
                position = 2,
                exercise = FLY,
                supersetLabel = "A2",
                supersetGroupName = "A",
            ),
        )

    private companion object {
        /** Past the hold, with room for the frame the timeout lands on. */
        const val HELD_LONG_ENOUGH = 1_400L

        /** A tap, in other words. */
        const val TOO_SHORT = 200L

        /* The `session_exercise` ids this fixture writes, in position order. */

        /**
         * Further than any row is wide, for a drag that is meant to take the row off the screen.
         * `SwipeToDelete` clamps to the row, so overshooting cannot overshoot the threshold.
         */
        const val OFF_THE_SCREEN = 3_000f

        /** Short of `SwipeToDelete`'s commit fraction on any row this screen draws. */
        const val PART_WAY = 80f

        const val BENCH_ROW = 1L
        const val INCLINE_ROW = 2L
        const val FLY_ROW = 3L
    }
}
