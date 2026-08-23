package com.opengym.feature.session.active

import androidx.lifecycle.SavedStateHandle
import com.opengym.core.domain.model.ActiveExercise
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.timer.RestTimerSnapshot
import com.opengym.core.domain.timer.RestTimerState
import com.opengym.core.domain.usecase.AddSessionExercisesUseCase
import com.opengym.core.domain.usecase.DeleteSetUseCase
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.EditSetUseCase
import com.opengym.core.domain.usecase.FinishSessionUseCase
import com.opengym.core.domain.usecase.LinkSupersetUseCase
import com.opengym.core.domain.usecase.LogSetUseCase
import com.opengym.core.domain.usecase.MarkSetCompletedUseCase
import com.opengym.core.domain.usecase.PauseSessionUseCase
import com.opengym.core.domain.usecase.RemoveSessionExerciseUseCase
import com.opengym.core.domain.usecase.RenameSessionUseCase
import com.opengym.core.domain.usecase.ReorderSessionExercisesUseCase
import com.opengym.core.domain.usecase.RestoreSetUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SessionLifecycleLock
import com.opengym.core.domain.usecase.SwapSessionExerciseUseCase
import com.opengym.core.domain.usecase.UnlinkSupersetUseCase
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.feature.session.newsession.FakeExerciseCatalogue
import com.opengym.feature.session.newsession.FakeSessionStateStore
import com.opengym.feature.session.newsession.FakeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the workout screen does to the stores behind it.
 *
 * Whether it *draws* a given state is `ActiveSessionScreenTest`'s question. What is asserted
 * here is that a logged set reaches `workout_set` and comes straight back out of the read that
 * feeds the figures, that the refusals land where the user can act on them, and that pausing,
 * finishing and discarding move the state machine the way the domain says they do.
 *
 * Under Robolectric because the session id is read off the destination, and
 * `SavedStateHandle.toRoute` decodes through a real `Bundle` — the plain JVM stubs refuse to
 * write a `Long` into one. Reading the id the way the app does is worth an Android runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
/*
 * Over the size threshold on purpose, for the reason the ViewModel it covers is over two of its
 * own: this is the one screen a workout can be logged, edited, named, paused, finished and
 * thrown away on, and the tests for those are one subject each — they are already grouped under
 * headings, and none of the groups is long.
 *
 * What a split would cost is the support block at the bottom: `viewModel()` wires seventeen
 * dependencies over four fakes this class owns as fields, and a second class would either
 * duplicate that or hoist it into `ActiveSessionTestDoubles` as a factory taking all four back
 * as parameters. Both are more machinery between a failure and what it means than a heading is.
 */
@Suppress("LargeClass")
class ActiveSessionViewModelTest {

    private val clock = FixedClock()
    private val drafts = FakeDraftStore()
    private val settings = FakeSettings()
    private val catalogue = FakeExerciseCatalogue(exercises = listOf(BENCH, INCLINE, FLY, PUSHDOWN))

    /**
     * The clock `viewModelScope` runs on, held so a test can move it.
     *
     * Needed because one path in the ViewModel is deliberately *not* immediate: the keystroke
     * draft save waits for the typing to stop. Unconfined settles everything that does not
     * wait; this is what settles the one thing that does. See [enter].
     */
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so a launched write has run by the
        // time the call that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── Logging a set ──────────────────────────────────────────────────────────────────

    @Test
    fun `logging a set writes it against the exercise being worked`() = runTest {
        val store = pushDayStore()
        val viewModel = viewModel(store = store)

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        val logged = store.logged.single()
        assertEquals(1L, logged.sessionExerciseId)
        assertEquals(5, logged.reps)
        assertEquals(80_000_000L, logged.weight)
        assertTrue(logged.isCompleted)
    }

    @Test
    fun `the set carries its rating, its type and its note`() = runTest {
        val store = pushDayStore()
        val viewModel = viewModel(store = store)

        viewModel.enter(weight = "40", reps = "12")
        viewModel.onEvent(ActiveSessionEvent.RpeChanged(BENCH_ROW, 75))
        viewModel.onEvent(ActiveSessionEvent.SetTypeChanged(BENCH_ROW, SetType.WARMUP))
        viewModel.onEvent(ActiveSessionEvent.NotesChanged(BENCH_ROW, "  left elbow  "))
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        val logged = store.logged.single()
        assertEquals(75, logged.rpe)
        assertEquals(SetType.WARMUP, logged.setType)
        assertEquals("left elbow", logged.notes)
    }

    @Test
    fun `the figures move as soon as a set is logged`() = runTest {
        // The criterion, stated as a test: the stats are read back out of the store rather than
        // tallied locally, so they cannot be right on screen and wrong on disk.
        val viewModel = viewModel()
        assertEquals(0, viewModel.uiState.value.stats?.totalSets)

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertEquals(1, viewModel.uiState.value.stats?.totalSets)
        assertEquals(400_000_000L, viewModel.uiState.value.stats?.totalVolume)
    }

    @Test
    fun `a logged set clears the fields but keeps the set type`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.SetTypeChanged(BENCH_ROW, SetType.WARMUP))
        viewModel.enter(weight = "40", reps = "12")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        val entry = viewModel.uiState.value.entryOf(BENCH_ROW)
        assertEquals("", entry.weight)
        assertEquals("", entry.reps)
        assertNull(entry.rpe)
        assertEquals(SetType.WARMUP, entry.setType)
    }

    @Test
    fun `the weight is read in the unit the user types in`() = runTest {
        val store = pushDayStore()
        val viewModel = viewModel(
            store = store,
            settings = FakeSettings(UserPreferences(unitSystem = UnitSystem.IMPERIAL)),
        )

        viewModel.enter(weight = "225", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        // 225 lb, stored in milligrams. Asserted through the formatter's own round trip rather
        // than against a literal, so this measures agreement rather than restating a constant.
        assertEquals(
            parseWeightMilligrams("225", UnitSystem.IMPERIAL),
            store.logged.single().weight,
        )
    }

    @Test
    fun `a superset's two writes are in flight at once without either logging twice`() = runTest {
        // How a superset is actually logged: the incline and the fly are alternated set for
        // set, so two ticks land within a second of each other and both writes are genuinely
        // open. One flag for the screen — or one id — would have the first write to come back
        // re-arm the other exercise's tick over an insert still running, and the set behind it
        // would be written a second time.
        val store = pushDayStore()
        val incline = CompletableDeferred<Unit>()
        val fly = CompletableDeferred<Unit>()
        store.beforeInsert = { set ->
            when (set.sessionExerciseId) {
                INCLINE_ROW -> incline.await()
                FLY_ROW -> fly.await()
                else -> Unit
            }
        }
        val viewModel = viewModel(store = store)

        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(INCLINE_ROW))
        viewModel.enter(weight = "20", reps = "15", sessionExerciseId = FLY_ROW)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(FLY_ROW))

        assertEquals(setOf(INCLINE_ROW, FLY_ROW), viewModel.uiState.value.loggingExerciseIds)

        // The incline's write lands while the fly's is still open, and a tick on the fly in
        // that window is the double tap the guard is for.
        // Completing resumes the parked write in place — the dispatcher is unconfined, and the
        // scheduler must not be advanced here: the elapsed clock's heartbeat is an unending
        // once-a-second flow, so advancing virtual time until idle never reaches idle.
        incline.complete(Unit)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(FLY_ROW))
        fly.complete(Unit)

        assertEquals(1, store.logged.count { it.sessionExerciseId == FLY_ROW })
        assertEquals(1, store.logged.count { it.sessionExerciseId == INCLINE_ROW })
        assertTrue(viewModel.uiState.value.loggingExerciseIds.isEmpty())
    }

    // ─── Validation ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a rep count outside the bounds is refused as it is typed`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "0")

        val error = viewModel.uiState.value.entryOf(BENCH_ROW).error
        assertTrue("expected a validator refusal, was $error", error is SetEntryError.Refused)
        assertFalse(viewModel.uiState.value.entryOf(BENCH_ROW).canLog)
    }

    @Test
    fun `the refusal is the validator's own wording`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "0")

        assertEquals(
            SetValidator.validate(
                WorkoutSet(
                    sessionExerciseId = 0,
                    setNumber = 1,
                    reps = 0,
                    weight = 80_000_000L,
                    completedAt = 0,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            ).reasonOrNull(),
            (viewModel.uiState.value.entryOf(BENCH_ROW).error as SetEntryError.Refused).reason,
        )
    }

    @Test
    fun `text that is not a number is told apart from a value out of bounds`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "heavy", reps = "5")

        assertEquals(SetEntryError.WeightNotANumber, viewModel.uiState.value.entryOf(BENCH_ROW).error)
    }

    @Test
    fun `an empty field is not an error`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "", reps = "")

        assertNull(viewModel.uiState.value.entryOf(BENCH_ROW).error)
        // Not loggable either: there is nothing to log.
        assertFalse(viewModel.uiState.value.entryOf(BENCH_ROW).canLog)
    }

    @Test
    fun `a refused set is never written`() = runTest {
        val store = pushDayStore()
        val viewModel = viewModel(store = store)

        viewModel.enter(weight = "80", reps = "0")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertTrue(store.logged.isEmpty())
    }

    @Test
    fun `an exercise logged by duration cannot take a set here, and says so rather than refusing one`() =
        runTest {
            // A plank records a duration, and this screen's entry row is a weight and a rep
            // count — so every set logged against it would be refused by `SetValidator`. The
            // tick is not live for it at all: the alternative is a control that looks ready on
            // every set of the exercise and fails on every press of it.
            val store = pushDayStore()
            val viewModel = viewModel(
                store = store,
                catalogue = FakeExerciseCatalogue(
                    exercises = listOf(BENCH.copy(type = ExerciseType.ISOMETRIC)),
                ),
            )

            viewModel.enter(weight = "80", reps = "5")
            viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

            assertTrue(store.logged.isEmpty())
            assertTrue(viewModel.uiState.value.rowOf(BENCH_ROW).isLoggedByDuration)
            assertFalse(viewModel.uiState.value.rowOf(BENCH_ROW).canLogEntry)
            // Nothing the user typed is wrong, so nothing is marked as wrong. What is typed
            // stays put, as it does through any other refusal.
            assertNull(viewModel.uiState.value.entryOf(BENCH_ROW).error)
            assertEquals("80", viewModel.uiState.value.entryOf(BENCH_ROW).weight)
        }

    @Test
    fun `a strength set with no rep count is not loggable, and is not an error either`() = runTest {
        // The dimension a completed STRENGTH set has to record is reps, so a row with only a
        // weight in it is one `LogSetUseCase` would refuse. Half way through typing is not a
        // mistake, though: the tick simply is not live yet, the way it is not on an empty row.
        val store = pushDayStore()
        val viewModel = viewModel(store = store)

        viewModel.enter(weight = "80", reps = "")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertTrue(store.logged.isEmpty())
        assertFalse(viewModel.uiState.value.rowOf(BENCH_ROW).canLogEntry)
        assertNull(viewModel.uiState.value.entryOf(BENCH_ROW).error)
    }

    @Test
    fun `a planned set with nothing in it yet cannot be ticked off`() = runTest {
        // What a template that named an exercise without pricing it lays down: a row in the
        // table, under its number, with no reps. `SetValidator` would refuse it the moment it
        // was marked done, so the tick is not live — the same rule the entry row above follows.
        val store = pushDayStore(
            sets = listOf(
                loggedSet(id = 1, sessionExerciseId = BENCH_ROW, setNumber = 1, isCompleted = false)
                    .copy(reps = null, weight = null),
            ),
        )
        val viewModel = viewModel(store = store)

        assertFalse(viewModel.uiState.value.rowOf(BENCH_ROW).sets.single().canComplete)
    }

    @Test
    fun `typing the missing reps into a planned set arms its tick`() = runTest {
        val store = pushDayStore(
            sets = listOf(
                loggedSet(id = 1, sessionExerciseId = BENCH_ROW, setNumber = 1, isCompleted = false)
                    .copy(reps = null),
            ),
        )
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 1, text = "8"))

        // Armed on the keystroke rather than on the write: the row is the plan being filled in.
        assertTrue(viewModel.uiState.value.rowOf(BENCH_ROW).sets.single().canComplete)
    }

    @Test
    fun `a planned set the template did price can be ticked straight off`() = runTest {
        val store = pushDayStore(
            sets = listOf(
                loggedSet(id = 1, sessionExerciseId = BENCH_ROW, setNumber = 1, isCompleted = false),
            ),
        )
        val viewModel = viewModel(store = store)

        val planned = viewModel.uiState.value.rowOf(BENCH_ROW).sets.single()
        assertTrue(planned.canComplete)
        assertFalse(planned.isCompleted)
        assertEquals("8", planned.entry.reps)
    }

    // ─── Correcting a logged set ────────────────────────────────────────────────────────
    //
    // There is no editor to open and nothing to confirm. Every logged row is live from the
    // moment the table is drawn, and what writes one is *leaving* it — `SetEditCommitted`,
    // raised when its fields lose focus. So these read as: type, leave, assert.

    @Test
    fun `a logged row holds its stored values, in the fields`() = runTest {
        val viewModel = viewModel(store = benchStore())

        // No event first. An untouched row is not empty and is not a mode to enter: it reads
        // itself off the store every time the screen is built.
        val second = viewModel.uiState.value.rowOf(BENCH_ROW).sets[1]
        assertEquals(2L, second.id)
        assertEquals("70", second.entry.weight)
        assertEquals("8", second.entry.reps)
    }

    @Test
    fun `a logged row reads out in the unit the user types in`() = runTest {
        val viewModel = viewModel(
            store = benchStore(),
            settings = FakeSettings(UserPreferences(unitSystem = UnitSystem.IMPERIAL)),
        )

        assertEquals(
            formatWeightValue(60_000_000L, UnitSystem.IMPERIAL),
            viewModel.uiState.value.rowOf(BENCH_ROW).sets[0].entry.weight,
        )
    }

    @Test
    fun `every row is live at once, so two corrections can be half typed`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 1L, text = "62.5"))
        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 2L, text = "72.5"))

        // The old editor could hold one, so typing into a second set threw the first away. There
        // is nothing to throw away now: the typing lives on the row it was typed into.
        val table = viewModel.uiState.value.rowOf(BENCH_ROW).sets
        assertEquals("62.5", table[0].entry.weight)
        assertEquals("72.5", table[1].entry.weight)
        assertEquals("and neither is written until it is left", emptyList<WorkoutSet>(), store.updated)
    }

    @Test
    fun `leaving a row writes what was typed over the set`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 2L, text = "72.5"))
        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 2L, text = "6"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 2L))

        val written = store.updated.single()
        assertEquals(2L, written.id)
        assertEquals(72_500_000L, written.weight)
        assertEquals(6, written.reps)
    }

    @Test
    fun `a rating picked off the menu is written on the spot`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditRpeChanged(BENCH_ROW, setId = 2L, rpe = 95))

        // No commit event. A rating is picked off a closed scale in a menu, so there is nothing
        // half typed to leave the row to finish — see [ActiveSessionEvent.SetEditRpeChanged].
        assertEquals(95, store.updated.single().rpe)
    }

    @Test
    fun `a set type picked off the menu is written on the spot`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(
            ActiveSessionEvent.SetEditTypeChanged(BENCH_ROW, setId = 2L, setType = SetType.DROP_SET),
        )

        assertEquals(SetType.DROP_SET, store.updated.single().setType)
    }

    @Test
    fun `negative - leaving a row nobody typed into writes nothing`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 2L))

        // The commonest case by far: focus passes through rows a lifter is only reading, and a
        // write per glance is a write the store does not need.
        assertEquals(emptyList<WorkoutSet>(), store.updated)
    }

    @Test
    fun `negative - a row typed back to what it said writes nothing`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 2L, text = "70.0"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 2L))

        // Compared as weights rather than as text: `70` and `70.0` are one set. The half-typed
        // copy goes with it, so the row falls back to reading itself off the store.
        assertEquals(emptyList<WorkoutSet>(), store.updated)
        assertEquals("70", viewModel.uiState.value.rowOf(BENCH_ROW).sets[1].entry.weight)
    }

    @Test
    fun `a correction keeps the note the set was logged with`() = runTest {
        val store = FakeActiveSessionStore(
            session = runningSession(),
            exercises = listOf(activeExercise(id = BENCH_ROW, exerciseId = BENCH.id, position = 0)),
            sets = listOf(loggedSet(id = 1L, sessionExerciseId = BENCH_ROW, setNumber = 1).copy(notes = "left elbow")),
        )
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 1L, text = "62.5"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

        // The table has no column for prose, so the draft is built from the stored row: a
        // correction to a weight must not quietly delete what the user wrote about the set.
        assertEquals("left elbow", store.updated.single().notes)
    }

    @Test
    fun `the figures move as soon as a correction is written`() = runTest {
        val viewModel = viewModel(store = benchStore())
        assertEquals(1_160_000_000L, viewModel.uiState.value.stats?.totalVolume)

        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 1L, text = "5"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

        // 60 kg x 5 + 70 kg x 8, straight out of the read flow rather than out of a local tally.
        assertEquals(860_000_000L, viewModel.uiState.value.stats?.totalVolume)
        assertEquals(2, viewModel.uiState.value.stats?.totalSets)
    }

    @Test
    fun `a correction that breaks a bound is refused, and the row keeps what was typed`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 1L, text = "0"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

        // The typing *stays*. It is the only copy of it, and dropping it here would silently
        // undo a correction the moment the user looked away from a typo.
        val refused = viewModel.uiState.value.rowOf(BENCH_ROW).sets[0]
        assertTrue(refused.entry.error is SetEntryError.Refused)
        assertEquals("0", refused.entry.reps)
        assertEquals("nothing is written", emptyList<WorkoutSet>(), store.updated)
    }

    @Test
    fun `a correction that is not a number is refused the same way it is on the entry row`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 1L, text = "eighty"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

        val refused = viewModel.uiState.value.rowOf(BENCH_ROW).sets[0]
        assertEquals(SetEntryError.WeightNotANumber, refused.entry.error)
        assertEquals(emptyList<WorkoutSet>(), store.updated)
    }

    @Test
    fun `a row being written is inert until the write comes back`() = runTest {
        val store = benchStore()
        val write = CompletableDeferred<Unit>()
        store.beforeUpdate = { write.await() }
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 1L, text = "9"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))
        // The same row left a second time — a field blurring after the row already committed.
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))
        write.complete(Unit)

        // One write, not two: the fields are the only copy of what was typed, and a second
        // commit of the same correction is a race with itself.
        assertEquals(1, store.updated.size)
        assertTrue(
            "and the row says so while it is out",
            viewModel.uiState.value.rowOf(BENCH_ROW).sets[0].isSaving.not(),
        )
    }

    @Test
    fun `a pick made while the row is being written is queued rather than dropped`() = runTest {
        val store = benchStore()
        val write = CompletableDeferred<Unit>()
        store.beforeUpdate = { write.await() }
        val viewModel = viewModel(store = store)

        // Two menu picks a moment apart. Both write on the pick — there is nothing to leave the
        // row to finish — so the second lands while the first is still out.
        viewModel.onEvent(ActiveSessionEvent.SetEditRpeChanged(BENCH_ROW, setId = 1L, rpe = 80))
        viewModel.onEvent(
            ActiveSessionEvent.SetEditTypeChanged(BENCH_ROW, setId = 1L, setType = SetType.WARMUP),
        )
        write.complete(Unit)

        // Refusing the second would leave the row showing the type the user picked over a store
        // that never heard about it, until the row was left again.
        val stored = store.stored.first { it.id == 1L }
        assertEquals(80, stored.rpe)
        assertEquals(SetType.WARMUP, stored.setType)
    }

    @Test
    fun `a correction to one row leaves what is being typed into another alone`() = runTest {
        val store = benchStore()
        val write = CompletableDeferred<Unit>()
        store.beforeUpdate = { write.await() }
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 1L, text = "9"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

        // The user moves on to the next set while the first correction is still being written.
        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 2L, text = "72.5"))
        write.complete(Unit)

        assertEquals("72.5", viewModel.uiState.value.rowOf(BENCH_ROW).sets[1].entry.weight)
        assertEquals(9, store.updated.single().reps)
    }

    @Test
    fun `a correction refused for a reason the row cannot show is said in the bar`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        store.editFailure = IllegalStateException("disk full")
        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 1L, text = "9"))
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

        // Nothing about the values is wrong, so there is no inline refusal to put under the row.
        // Silence would be a correction the user believes they made, over a set that still holds
        // what it held.
        assertEquals(ActiveSessionMessage.SET_EDIT_REJECTED, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.rowOf(BENCH_ROW).sets[0].entry.error)
        assertEquals("9", viewModel.uiState.value.rowOf(BENCH_ROW).sets[0].entry.reps)
    }

    @Test
    fun `switching units re-reads the rows rather than leaving the old numbers on them`() = runTest {
        val settings = FakeSettings()
        val viewModel = viewModel(store = benchStore(), settings = settings)

        // Touched, so the row has a copy of its own — seeded in kilograms.
        viewModel.onEvent(ActiveSessionEvent.SetEditRepsChanged(BENCH_ROW, setId = 1L, text = "9"))
        settings.setUnitSystem(UnitSystem.IMPERIAL)

        // The seeded half was text in the unit that has just stopped being the unit, so it goes
        // and the row reads itself off the store again. Leaving it would show `60` on one row
        // and `154` on the next.
        assertEquals(
            formatWeightValue(60_000_000L, UnitSystem.IMPERIAL),
            viewModel.uiState.value.rowOf(BENCH_ROW).sets[0].entry.weight,
        )
    }

    @Test
    fun `a correction still being written when the units change is not read back in the new unit`() =
        runTest {
            val settings = FakeSettings()
            val store = benchStore()
            val write = CompletableDeferred<Unit>()
            store.beforeUpdate = { write.await() }
            val viewModel = viewModel(store = store, settings = settings)

            // `100` typed in kilograms, and still being written when the switch lands.
            viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 1L, text = "100"))
            viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))
            settings.setUnitSystem(UnitSystem.IMPERIAL)
            write.complete(Unit)
            // The row left again, the way it is as the user moves down the table.
            viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

            // Kept over the switch, that `100` would be drawn under a header saying lb and then
            // read back *as* pounds — 45.4 kg written over a set the user last touched as 100.
            assertEquals(100_000_000L, store.stored.first { it.id == 1L }.weight)
            assertEquals("one write, and it is the one the user made", 1, store.updated.size)
            assertEquals(
                formatWeightValue(100_000_000L, UnitSystem.IMPERIAL),
                viewModel.uiState.value.rowOf(BENCH_ROW).sets[0].entry.weight,
            )
        }

    @Test
    fun `a paused workout takes no correction`() = runTest {
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 1L, text = "62.5"))
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)
        viewModel.onEvent(ActiveSessionEvent.SetEditCommitted(BENCH_ROW, setId = 1L))

        // The screen stops the typing — a paused card is read-only — and this is the half under
        // it: `EditSetUseCase` refuses a session that is not running, the same way `LogSetUseCase`
        // refuses a set logged into one.
        assertFalse(viewModel.uiState.value.isEditable)
        assertEquals(emptyList<WorkoutSet>(), store.updated)
        assertEquals(ActiveSessionMessage.SET_EDIT_REJECTED, viewModel.uiState.value.message)
    }

    // ─── Deleting a set, and taking it back ─────────────────────────────────────────────

    @Test
    fun `deleting a set takes it out and closes the numbers up`() = runTest {
        val store = threeSetStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 2L, text = "72.5"))
        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))

        val table = viewModel.uiState.value.rowOf(BENCH_ROW).sets
        assertEquals(listOf(1L, 3L), table.map(LoggedSet::id))
        // The screen draws a set under its place in the table; the stored numbers are closed up
        // behind it, which is the half only the store can be asked about.
        assertEquals(listOf(1, 2), store.stored.map(WorkoutSet::setNumber))
        // And the half-typed correction goes with the row it was typed into: what it was against
        // is on its way out, so holding it would be typing against a set nobody can see.
        assertEquals("75", table[1].entry.weight)
    }

    @Test
    fun `the figures move as soon as a set is deleted`() = runTest {
        val viewModel = viewModel(store = threeSetStore())
        assertEquals(3, viewModel.uiState.value.stats?.totalSets)

        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 3L))

        assertEquals(2, viewModel.uiState.value.stats?.totalSets)
        assertEquals(1_160_000_000L, viewModel.uiState.value.stats?.totalVolume)
    }

    @Test
    fun `a deleted set is offered back rather than confirmed away`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))

        val offered = requireNotNull(viewModel.uiState.value.deletedSet)
        assertEquals(2L, offered.id)
        assertEquals("the number it had is what puts it back", 2, offered.setNumber)
    }

    @Test
    fun `undoing puts the set back where it was`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))
        viewModel.onEvent(ActiveSessionEvent.SetDeleteUndone)

        val table = viewModel.uiState.value.rowOf(BENCH_ROW).sets
        assertEquals(
            "back in the place it had, with the values it was logged with",
            listOf("60", "70", "75"),
            table.map { it.entry.weight },
        )
        assertEquals(3, viewModel.uiState.value.stats?.totalSets)
    }

    @Test
    fun `the offer to undo is consumed by taking it`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))
        viewModel.onEvent(ActiveSessionEvent.SetDeleteUndone)
        // A second undo, the way a second tap on a snackbar that has not left yet would arrive.
        viewModel.onEvent(ActiveSessionEvent.SetDeleteUndone)

        assertNull(viewModel.uiState.value.deletedSet)
        assertEquals(3, viewModel.uiState.value.rowOf(BENCH_ROW).sets.size)
    }

    @Test
    fun `an offer that lapses leaves the workout as it is`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))
        viewModel.onEvent(ActiveSessionEvent.SetDeleteDismissed)

        assertNull(viewModel.uiState.value.deletedSet)
        assertEquals(2, viewModel.uiState.value.rowOf(BENCH_ROW).sets.size)
    }

    @Test
    fun `a delete the store refuses says so and leaves the set alone`() = runTest {
        val store = threeSetStore()
        val viewModel = viewModel(store = store)

        store.editFailure = IllegalStateException("disk full")
        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))

        assertEquals(ActiveSessionMessage.DELETE_SET_FAILED, viewModel.uiState.value.message)
        assertNull("nothing is offered back, because nothing went", viewModel.uiState.value.deletedSet)
        assertEquals(3, viewModel.uiState.value.rowOf(BENCH_ROW).sets.size)
    }

    @Test
    fun `a delete the store half performs still offers the set back`() = runTest {
        val store = threeSetStore()
        val viewModel = viewModel(store = store)

        // The row goes and the renumbering behind it does not — `SetTableNotRenumbered`.
        store.renumberFailure = IllegalStateException("disk full")
        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))

        // The set is gone either way, and the offer is the only copy of it left. A message
        // saying it is still in the workout would be untrue, and dropping the undo with it
        // would cost the user the one way back.
        val offered = requireNotNull(viewModel.uiState.value.deletedSet)
        assertEquals(2L, offered.id)
        assertEquals("the number it had is what puts it back", 2, offered.setNumber)
        assertNull(viewModel.uiState.value.message)
        assertEquals(
            listOf(1L, 3L),
            viewModel.uiState.value.rowOf(BENCH_ROW).sets.map(LoggedSet::id),
        )
    }

    @Test
    fun `a restore the store half performs says where the set went`() = runTest {
        val store = threeSetStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))
        store.renumberFailure = IllegalStateException("disk full")
        viewModel.onEvent(ActiveSessionEvent.SetDeleteUndone)

        // The insert landed, so the set is back — at the end of the table rather than between
        // the two it was between. `RESTORE_SET_FAILED` says to log it again, which over a set
        // that is already there would have the user record it twice.
        assertEquals(ActiveSessionMessage.SET_RESTORED_AT_END, viewModel.uiState.value.message)
        assertEquals(3, viewModel.uiState.value.rowOf(BENCH_ROW).sets.size)
    }

    @Test
    fun `an undo over a workout that has ended is withdrawn rather than ignored`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 2L))
        viewModel.onEvent(ActiveSessionEvent.FinishRequested)

        // The undo is a snackbar action, which going inert does not reach: it stays on screen
        // and pressable over a workout that is already over. `RestoreSetUseCase` refuses one, so
        // the offer is taken away rather than left standing as a button that does nothing.
        viewModel.onEvent(ActiveSessionEvent.SetDeleteUndone)

        assertNull(viewModel.uiState.value.deletedSet)
    }

    @Test
    fun `an undo taken over a deletion still in flight waits for it`() = runTest {
        val store = threeSetStore()
        val write = CompletableDeferred<Unit>()
        val viewModel = viewModel(store = store)

        // The last set swiped away, with the offer to put it back still up ...
        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 3L))
        // ... when the first one is swiped away too, and the offer taken before that write lands.
        store.beforeDelete = { write.await() }
        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 1L))
        viewModel.onEvent(ActiveSessionEvent.SetDeleteUndone)
        write.complete(Unit)

        // Both renumber the same table. Overlapped, the deletion closes the gap it left against
        // a reading taken before the restore, and the set that came back is left stranded on a
        // number nothing leads up to — a table reading 1, 3.
        assertEquals(
            "the restored set is back on the end, and the table reads 1, 2",
            listOf(1 to "70", 2 to "75"),
            store.stored.sortedBy(WorkoutSet::setNumber).map { it.setNumber to it.weightText() },
        )
    }

    @Test
    fun `deleting one set leaves a correction being typed into another alone`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 3L, text = "77.5"))
        viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId = 1L))

        // The drag is on a row of its own: a correction half typed into set 3 is not something
        // deleting set 1 should throw away, even though it moves the row up a place.
        val table = viewModel.uiState.value.rowOf(BENCH_ROW).sets
        assertEquals(listOf(2L, 3L), table.map(LoggedSet::id))
        assertEquals("77.5", table.single { it.id == 3L }.entry.weight)
    }

    // ─── Un-ticking a set ───────────────────────────────────────────────────────────────

    @Test
    fun `un-ticking a set leaves it in the table and takes it out of the figures`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(
            ActiveSessionEvent.SetCompletionToggled(BENCH_ROW, setId = 2L, isCompleted = false),
        )

        val table = viewModel.uiState.value.rowOf(BENCH_ROW).sets
        assertEquals("this is not a deletion", listOf(1L, 2L, 3L), table.map(LoggedSet::id))
        assertFalse(table.single { it.id == 2L }.isCompleted)
        assertEquals(2, viewModel.uiState.value.stats?.totalSets)
    }

    @Test
    fun `the tick goes back on the same way it came off`() = runTest {
        val viewModel = viewModel(store = threeSetStore())

        viewModel.onEvent(
            ActiveSessionEvent.SetCompletionToggled(BENCH_ROW, setId = 2L, isCompleted = false),
        )
        viewModel.onEvent(
            ActiveSessionEvent.SetCompletionToggled(BENCH_ROW, setId = 2L, isCompleted = true),
        )

        // The action is its own undo, which is why there is no offer to undo it: one tap back.
        assertTrue(viewModel.uiState.value.rowOf(BENCH_ROW).sets.single { it.id == 2L }.isCompleted)
        assertEquals(3, viewModel.uiState.value.stats?.totalSets)
    }

    @Test
    fun `an un-ticked set is not mistaken for the row being typed when the draft comes back`() = runTest {
        // A draft whose last set is a written row that has been un-ticked, which is the shape the
        // format could not previously tell from a half-typed set: both are incomplete, and both
        // are last.
        val draft = ActiveSession(
            sessionId = ACTIVE_SESSION_ID,
            startedAt = STARTED_AT,
            exercises = listOf(
                ActiveExercise(
                    exerciseId = BENCH.id,
                    sessionExerciseId = BENCH_ROW,
                    sets = listOf(
                        ActiveSet(id = 1L, reps = 10, weight = 60_000_000L, isCompleted = true, completedAt = 1L),
                        ActiveSet(id = 2L, reps = 8, weight = 70_000_000L, isCompleted = false),
                    ),
                ),
            ),
        )

        val restored = viewModel(store = threeSetStore(), drafts = FakeDraftStore(draft))

        // Told apart by the id: a written row has one, and a set that has only been typed does
        // not. Without that, reopening the screen would put the un-ticked set's values into the
        // entry fields and invite the user to log a second copy of it.
        assertEquals(SetEntry(), restored.uiState.value.entryOf(BENCH_ROW))
    }

    @Test
    fun `a tick the store refuses says so and leaves the set as it was`() = runTest {
        val store = threeSetStore()
        val viewModel = viewModel(store = store)
        store.editFailure = IllegalStateException("disk full")

        viewModel.onEvent(
            ActiveSessionEvent.SetCompletionToggled(BENCH_ROW, setId = 2L, isCompleted = false),
        )

        assertEquals(ActiveSessionMessage.SET_COMPLETION_FAILED, viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.rowOf(BENCH_ROW).sets.single { it.id == 2L }.isCompleted)
    }

    @Test
    fun `an exercise emptied by deleting its sets can then be removed`() = runTest {
        // The refusal `RemoveSessionExerciseUseCase` gives names this as the way past it, so it
        // is worth pinning down that the way past it exists.
        val store = benchStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.ExerciseRemoved(BENCH_ROW))
        assertEquals(ActiveSessionMessage.EXERCISE_HAS_SETS, viewModel.uiState.value.message)

        listOf(1L, 2L).forEach { setId ->
            viewModel.onEvent(ActiveSessionEvent.SetDeleted(BENCH_ROW, setId))
        }
        viewModel.onEvent(ActiveSessionEvent.ExerciseRemoved(BENCH_ROW))

        assertTrue(viewModel.uiState.value.exercises.none { it.sessionExerciseId == BENCH_ROW })
    }

    // ─── The workout on screen ──────────────────────────────────────────────────────────

    @Test
    fun `every exercise of the workout is on the screen, in the order it is performed`() = runTest {
        val state = viewModel().uiState.value

        assertEquals(listOf(BENCH, INCLINE, FLY), state.exercises.map { it.exercise })
    }

    @Test
    fun `each exercise carries the sets logged against it`() = runTest {
        val viewModel = viewModel(
            store = pushDayStore(
                sets = listOf(
                    loggedSet(id = 1, sessionExerciseId = 1, setNumber = 1),
                    loggedSet(id = 2, sessionExerciseId = 3, setNumber = 1),
                ),
            ),
        )

        val state = viewModel.uiState.value
        assertEquals(1, state.rowOf(BENCH_ROW).setCount)
        assertEquals(0, state.rowOf(INCLINE_ROW).setCount)
        assertEquals(1, state.rowOf(FLY_ROW).setCount)
    }

    @Test
    fun `each exercise keeps its own half-typed set`() = runTest {
        // The reason there is no current exercise: a superset is logged by alternating between
        // its members, and one shared entry would be cleared on every switch.
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "5", sessionExerciseId = BENCH_ROW)
        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)

        val state = viewModel.uiState.value
        assertEquals("80", state.entryOf(BENCH_ROW).weight)
        assertEquals("30", state.entryOf(INCLINE_ROW).weight)
        assertEquals(SetEntry(), state.entryOf(FLY_ROW))
    }

    @Test
    fun `logging one exercise's set leaves the others' rows alone`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5", sessionExerciseId = BENCH_ROW)
        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)

        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertEquals(SetEntry(), viewModel.uiState.value.entryOf(BENCH_ROW))
        assertEquals("30", viewModel.uiState.value.entryOf(INCLINE_ROW).weight)
    }

    @Test
    fun `a superset is labelled the way the review panel labelled it`() = runTest {
        val viewModel = viewModel(store = supersetStore())

        assertEquals(
            listOf(null, "A1", "A2"),
            viewModel.uiState.value.exercises.map { it.supersetLabel },
        )
    }

    @Test
    fun `the join between two members of a superset is drawn as joined`() = runTest {
        val viewModel = viewModel(store = supersetStore())

        assertEquals(
            listOf(false, true, false),
            viewModel.uiState.value.exercises.map { it.isLinkedToNext },
        )
    }

    @Test
    fun `progress counts the exercises with something logged against them`() = runTest {
        val viewModel = viewModel(
            store = pushDayStore(sets = listOf(loggedSet(id = 1, sessionExerciseId = 1, setNumber = 1))),
        )

        assertEquals(1, viewModel.uiState.value.header?.progress?.completed)
        assertEquals(3, viewModel.uiState.value.header?.progress?.total)
    }

    @Test
    fun `progress and the recap agree about a set that is logged but not performed`() = runTest {
        // The rail and `SessionStatsCalculator` run over the same sets four lines apart, and
        // only one of them used to filter on `isCompleted`. They agree only while nothing
        // incomplete is ever persisted, which #99's editing is exactly what changes.
        val viewModel = viewModel(
            store = pushDayStore(
                sets = listOf(
                    loggedSet(id = 1, sessionExerciseId = BENCH_ROW, setNumber = 1),
                    loggedSet(
                        id = 2,
                        sessionExerciseId = INCLINE_ROW,
                        setNumber = 1,
                        isCompleted = false,
                    ),
                ),
            ),
        )
        val state = viewModel.uiState.value

        assertEquals(1, state.header?.progress?.completed)
        assertEquals(1, state.stats?.totalSets)
        // The row still draws both, because the table shows what is under the exercise.
        assertEquals(1, state.rowOf(INCLINE_ROW).setCount)
        assertEquals(0, state.rowOf(INCLINE_ROW).completedSetCount)
    }

    @Test
    fun `a superset's group is carried on the row rather than sliced out of its label`() = runTest {
        val viewModel = viewModel(store = supersetStore())
        val state = viewModel.uiState.value

        assertEquals(listOf(null, "A1", "A2"), state.exercises.map { it.supersetLabel })
        assertEquals(listOf(null, "A", "A"), state.exercises.map { it.supersetGroupName })
    }

    // ─── Supersets ──────────────────────────────────────────────────────────────────────

    @Test
    fun `linking an exercise to the next one makes them a superset`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.SupersetLinked(INCLINE_ROW))

        val state = viewModel.uiState.value
        assertEquals(listOf(null, "A1", "A2"), state.exercises.map { it.supersetLabel })
        assertEquals(listOf(false, true, false), state.exercises.map { it.isLinkedToNext })
    }

    @Test
    fun `linking twice from the group's last member makes it a three`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.SupersetLinked(BENCH_ROW))
        viewModel.onEvent(ActiveSessionEvent.SupersetLinked(INCLINE_ROW))

        assertEquals(
            listOf("A1", "A2", "A3"),
            viewModel.uiState.value.exercises.map { it.supersetLabel },
        )
    }

    @Test
    fun `unlinking breaks the one join it was tapped on`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ActiveSessionEvent.SupersetLinked(BENCH_ROW))
        viewModel.onEvent(ActiveSessionEvent.SupersetLinked(INCLINE_ROW))

        viewModel.onEvent(ActiveSessionEvent.SupersetUnlinked(INCLINE_ROW))

        assertEquals(
            listOf("A1", "A2", null),
            viewModel.uiState.value.exercises.map { it.supersetLabel },
        )
    }

    @Test
    fun `a superset survives the screen being reopened, because it is on the row`() = runTest {
        // Not screen state: the grouping is a column on `session_exercise`, so it is read back
        // out of the store the same way the sets are.
        val store = pushDayStore()
        viewModel(store = store).onEvent(ActiveSessionEvent.SupersetLinked(INCLINE_ROW))

        val reopened = viewModel(store = store)

        assertEquals(
            listOf(null, "A1", "A2"),
            reopened.uiState.value.exercises.map { it.supersetLabel },
        )
    }

    @Test
    fun `a superset that cannot be formed is reported and changes nothing`() = runTest {
        // The last exercise has nothing after it to be performed back to back with. The screen
        // draws no join there, so this is the stale-tap path rather than one a user can reach.
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.SupersetLinked(FLY_ROW))

        assertEquals(ActiveSessionMessage.SUPERSET_FAILED, viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.exercises.all { it.supersetLabel == null })
    }

    // ─── Editing the layout ─────────────────────────────────────────────────────────────

    @Test
    fun `exercises picked from the library are appended to the workout`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.ExercisesPicked(listOf(PUSHDOWN.id)))

        assertEquals(
            listOf(BENCH, INCLINE, FLY, PUSHDOWN),
            viewModel.uiState.value.exercises.map { it.exercise },
        )
    }

    @Test
    fun `adding to a workout with nothing in it fills it`() = runTest {
        // The empty-session path: quick-start writes a session with nothing in it, and this is
        // the only way anything ever gets into it.
        val viewModel = viewModel(
            store = FakeActiveSessionStore(session = runningSession(), exercises = emptyList()),
        )
        assertTrue(viewModel.uiState.value.exercises.isEmpty())

        viewModel.onEvent(ActiveSessionEvent.ExercisesPicked(listOf(BENCH.id)))

        assertEquals(listOf(BENCH), viewModel.uiState.value.exercises.map { it.exercise })
    }

    @Test
    fun `the same exercise can be added twice, because a workout may hold it twice`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.ExercisesPicked(listOf(BENCH.id)))

        assertEquals(
            listOf(BENCH, INCLINE, FLY, BENCH),
            viewModel.uiState.value.exercises.map { it.exercise },
        )
    }

    @Test
    fun `an empty pick changes nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.ExercisesPicked(emptyList()))

        assertEquals(3, viewModel.uiState.value.exercises.size)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `an exercise with nothing logged against it can be cut`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.ExerciseRemoved(FLY_ROW))

        assertEquals(listOf(BENCH, INCLINE), viewModel.uiState.value.exercises.map { it.exercise })
    }

    @Test
    fun `cutting an exercise takes its half-typed row with it`() = runTest {
        // Nothing else would ever clear it — the card it belonged to is gone — and a
        // `session_exercise` id is not reused, so it would be written into every later draft.
        val viewModel = viewModel()
        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)

        viewModel.onEvent(ActiveSessionEvent.ExerciseRemoved(INCLINE_ROW))

        assertTrue(drafts.stored?.exercises.orEmpty().all { exercise -> exercise.sets.isEmpty() })
    }

    @Test
    fun `an exercise with a logged set is refused, and the refusal names the way past it`() =
        runTest {
            val viewModel = viewModel()
            viewModel.enter(weight = "80", reps = "5")
            viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

            viewModel.onEvent(ActiveSessionEvent.ExerciseRemoved(BENCH_ROW))

            assertEquals(ActiveSessionMessage.EXERCISE_HAS_SETS, viewModel.uiState.value.message)
            assertEquals(BENCH, viewModel.uiState.value.exercises.first().exercise)
        }

    @Test
    fun `cutting a superset's partner dissolves the grouping on screen`() = runTest {
        val viewModel = viewModel(store = supersetStore())

        viewModel.onEvent(ActiveSessionEvent.ExerciseRemoved(FLY_ROW))

        assertTrue(viewModel.uiState.value.exercises.all { it.supersetLabel == null })
    }

    // ─── Reordering ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a reorder is written, and the workout comes back in the new order`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(FLY_ROW, BENCH_ROW, INCLINE_ROW)),
        )

        assertEquals(
            listOf(FLY, BENCH, INCLINE),
            viewModel.uiState.value.exercises.map { it.exercise },
        )
    }

    @Test
    fun `an order that is not this session's exercises is refused and changes nothing`() = runTest {
        // The refusal `ReorderSessionExercisesUseCase` makes about the list itself, which had no
        // test at all. What it costs the screen is the point: nothing moved, so nothing on the
        // store's side changes to tell the screen to stop drawing the order it dragged.
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.ExercisesReordered(listOf(FLY_ROW, BENCH_ROW)))

        assertEquals(ActiveSessionMessage.REORDER_FAILED, viewModel.uiState.value.message)
        assertEquals(
            listOf(BENCH, INCLINE, FLY),
            viewModel.uiState.value.exercises.map { it.exercise },
        )
        // The count is the only thing about the session that a refusal changes, which is the
        // whole reason it exists: the screen holding a dragged copy of the order has nothing
        // else to notice.
        assertEquals(1, viewModel.uiState.value.refusedReorders)
    }

    @Test
    fun `each refusal counts, so two in a row are two`() = runTest {
        val viewModel = viewModel()

        repeat(2) {
            viewModel.onEvent(ActiveSessionEvent.ExercisesReordered(listOf(FLY_ROW, BENCH_ROW)))
        }

        assertEquals(2, viewModel.uiState.value.refusedReorders)
    }

    @Test
    fun `a reorder that lands does not count as a refusal`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(FLY_ROW, BENCH_ROW, INCLINE_ROW)),
        )

        assertEquals(0, viewModel.uiState.value.refusedReorders)
    }

    @Test
    fun `a reorder of a paused workout is refused and the order stands`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        viewModel.onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(FLY_ROW, BENCH_ROW, INCLINE_ROW)),
        )

        assertEquals(ActiveSessionMessage.REORDER_FAILED, viewModel.uiState.value.message)
        assertEquals(
            listOf(BENCH, INCLINE, FLY),
            viewModel.uiState.value.exercises.map { it.exercise },
        )
    }

    @Test
    fun `a reorder survives the screen being reopened, because it is on the row`() = runTest {
        val store = pushDayStore()
        viewModel(store = store).onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(FLY_ROW, INCLINE_ROW, BENCH_ROW)),
        )

        // A second ViewModel over the same store is what a relaunch is: nothing of the order
        // lives in this object, so what comes back is what `session_exercise.position` holds.
        val reopened = viewModel(store = store)

        assertEquals(
            listOf(FLY, INCLINE, BENCH),
            reopened.uiState.value.exercises.map { it.exercise },
        )
    }

    @Test
    fun `the exercises are renumbered on screen after a reorder`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(FLY_ROW, BENCH_ROW, INCLINE_ROW)),
        )

        assertEquals(listOf(0, 1, 2), viewModel.uiState.value.exercises.map { it.position })
    }

    @Test
    fun `the progress marks travel with the exercises they are about`() = runTest {
        // The rule for reordering an exercise that has already been worked: there is not one,
        // because the indicator does not index the workout. It carries a mark per exercise, so
        // moving the one that is done moves its mark — and the figure it is read as, "1 / 3",
        // is the same either way.
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))
        assertEquals(
            listOf(true, false, false),
            viewModel.uiState.value.header?.progress?.exercises,
        )

        viewModel.onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(INCLINE_ROW, FLY_ROW, BENCH_ROW)),
        )

        val after = viewModel.uiState.value.header?.progress
        assertEquals(listOf(false, false, true), after?.exercises)
        assertEquals(1, after?.completed)
        assertEquals(3, after?.total)
    }

    @Test
    fun `the sets of a reordered exercise move with it`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        viewModel.onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(INCLINE_ROW, FLY_ROW, BENCH_ROW)),
        )

        val bench = viewModel.uiState.value.rowOf(BENCH_ROW)
        assertEquals(2, bench.position)
        assertEquals(1, bench.setCount)
    }

    @Test
    fun `a superset moved as a block keeps its members together`() = runTest {
        val viewModel = viewModel(store = supersetStore())

        viewModel.onEvent(
            ActiveSessionEvent.ExercisesReordered(listOf(INCLINE_ROW, FLY_ROW, BENCH_ROW)),
        )

        val state = viewModel.uiState.value
        assertEquals(listOf(INCLINE, FLY, BENCH), state.exercises.map { it.exercise })
        assertEquals(listOf("A1", "A2", null), state.exercises.map { it.supersetLabel })
    }

    @Test
    fun `an exercise dragged out of a superset leaves it, on screen as well as on the row`() =
        runTest {
            // The rule a drag makes visible: what the user did was take one exercise out from
            // between two others, and the pair it was in is not a pair any more. Nothing is
            // refused, and nothing is lost — the join between two cards puts it back.
            val viewModel = viewModel(store = supersetStore())

            viewModel.onEvent(
                ActiveSessionEvent.ExercisesReordered(listOf(INCLINE_ROW, BENCH_ROW, FLY_ROW)),
            )

            val state = viewModel.uiState.value
            assertNull(state.message)
            assertEquals(listOf(INCLINE, BENCH, FLY), state.exercises.map { it.exercise })
            assertTrue(state.exercises.all { it.supersetLabel == null })
        }

    // ─── Swapping ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a swapped slot holds the new exercise and keeps its place`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.ExerciseSwapped(INCLINE_ROW, PUSHDOWN.id))

        val state = viewModel.uiState.value
        assertEquals(listOf(BENCH, PUSHDOWN, FLY), state.exercises.map { it.exercise })
        assertEquals(1, state.rowOf(INCLINE_ROW).position)
    }

    @Test
    fun `a swap clears what was typed into that slot, and nothing else`() = runTest {
        // The weight standing in the field was typed for the movement that was there.
        val viewModel = viewModel()
        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)
        viewModel.enter(weight = "80", reps = "5", sessionExerciseId = BENCH_ROW)

        viewModel.onEvent(ActiveSessionEvent.ExerciseSwapped(INCLINE_ROW, PUSHDOWN.id))

        assertEquals(SetEntry(), viewModel.uiState.value.entryOf(INCLINE_ROW))
        assertEquals("80", viewModel.uiState.value.entryOf(BENCH_ROW).weight)
    }

    @Test
    fun `a slot with a logged set is refused, and the refusal names the way past it`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        viewModel.onEvent(ActiveSessionEvent.ExerciseSwapped(BENCH_ROW, PUSHDOWN.id))

        assertEquals(ActiveSessionMessage.SWAP_HAS_SETS, viewModel.uiState.value.message)
        assertEquals(BENCH, viewModel.uiState.value.rowOf(BENCH_ROW).exercise)
    }

    @Test
    fun `a swap leaves every other exercise's sets alone`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))
        viewModel.enter(weight = "20", reps = "15", sessionExerciseId = FLY_ROW)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(FLY_ROW))

        viewModel.onEvent(ActiveSessionEvent.ExerciseSwapped(INCLINE_ROW, PUSHDOWN.id))

        val state = viewModel.uiState.value
        assertEquals(1, state.rowOf(BENCH_ROW).setCount)
        assertEquals(1, state.rowOf(FLY_ROW).setCount)
        assertEquals(2, state.stats?.totalSets)
    }

    @Test
    fun `asking for the picker changes nothing on its own`() = runTest {
        // The event exists so the screen can record which slot the answer belongs to; the
        // workout is not touched until the picker comes back with something.
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.ExerciseSwapRequested(INCLINE_ROW))

        assertEquals(
            listOf(BENCH, INCLINE, FLY),
            viewModel.uiState.value.exercises.map { it.exercise },
        )
        assertNull(viewModel.uiState.value.message)
    }

    // ─── Naming ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the rename dialog opens on the name the session already has`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.NameEditRequested)

        assertEquals("Push day · Upper", viewModel.uiState.value.nameDraft)
    }

    @Test
    fun `a session started without a name opens the dialog empty`() = runTest {
        val viewModel = viewModel(
            store = FakeActiveSessionStore(
                session = runningSession(name = null),
                exercises = listOf(activeExercise(id = 1, exerciseId = BENCH.id, position = 0)),
            ),
        )

        viewModel.onEvent(ActiveSessionEvent.NameEditRequested)

        assertEquals("", viewModel.uiState.value.nameDraft)
    }

    @Test
    fun `renaming writes the name and closes the dialog`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.NameEditRequested)
        viewModel.onEvent(ActiveSessionEvent.NameChanged("Leg day"))
        viewModel.onEvent(ActiveSessionEvent.NameConfirmed)

        assertEquals("Leg day", viewModel.uiState.value.header?.name)
        assertNull(viewModel.uiState.value.nameDraft)
    }

    @Test
    fun `clearing the field takes the session back to unnamed`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.NameEditRequested)
        viewModel.onEvent(ActiveSessionEvent.NameChanged("   "))
        viewModel.onEvent(ActiveSessionEvent.NameConfirmed)

        assertNull(viewModel.uiState.value.header?.name)
    }

    @Test
    fun `cancelling the dialog leaves the name alone`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.NameEditRequested)
        viewModel.onEvent(ActiveSessionEvent.NameChanged("Leg day"))
        viewModel.onEvent(ActiveSessionEvent.NameEditCancelled)

        assertNull(viewModel.uiState.value.nameDraft)
        assertEquals("Push day · Upper", viewModel.uiState.value.header?.name)
    }

    // ─── The clock ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the elapsed clock counts from the session's own start`() = runTest {
        clock.advance(23.minutes.inWholeMilliseconds + 14.seconds.inWholeMilliseconds)

        assertEquals(23.minutes + 14.seconds, viewModel().uiState.value.header?.elapsed)
    }

    @Test
    fun `pausing suspends the session and stops the clock`() = runTest {
        val viewModel = viewModel()
        clock.advance(1.minutes.inWholeMilliseconds)

        viewModel.onEvent(ActiveSessionEvent.PauseToggled)
        // Ten minutes standing around. None of it is time spent working out.
        clock.advance(10.minutes.inWholeMilliseconds)

        assertTrue(viewModel.uiState.value.isPaused)
        assertEquals(1.minutes, viewModel.uiState.value.header?.elapsed)
    }

    @Test
    fun `resuming banks the pause rather than jumping the clock forward`() = runTest {
        val viewModel = viewModel()
        clock.advance(1.minutes.inWholeMilliseconds)
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)
        clock.advance(10.minutes.inWholeMilliseconds)

        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        assertFalse(viewModel.uiState.value.isPaused)
        assertEquals(1.minutes, viewModel.uiState.value.header?.elapsed)
    }

    @Test
    fun `a paused session survives being reopened, because the pause is in the draft`() = runTest {
        val viewModel = viewModel()
        clock.advance(1.minutes.inWholeMilliseconds)
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        // The process dies here. The state machine is in memory and goes with it; the draft and
        // the session row do not.
        val restored = viewModel(
            state = FakeSessionStateStore(),
            drafts = FakeDraftStore(drafts.stored),
        )

        assertTrue(restored.uiState.value.isPaused)
        assertEquals(1.minutes, restored.uiState.value.header?.elapsed)
    }

    @Test
    fun `restoring a paused workout pauses the state machine, not just the header`() = runTest {
        // The reattach resumes whatever is running on disk, because that is the transition a
        // process restart needs; the draft then says the workout was paused. Left disagreeing,
        // the screen would draw "paused" over a session every write path considers running.
        val viewModel = viewModel()
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        val state = FakeSessionStateStore()
        viewModel(state = state, drafts = FakeDraftStore(drafts.stored))

        assertTrue(state.current is SessionState.Paused)
    }

    @Test
    fun `a pause that outlived the process ends on the first tap, not the second`() = runTest {
        val viewModel = viewModel()
        clock.advance(1.minutes.inWholeMilliseconds)
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        val state = FakeSessionStateStore()
        val restored = viewModel(state = state, drafts = FakeDraftStore(drafts.stored))
        // Ten minutes with the process dead. None of it is time spent working out, and a tap
        // that paused instead of resuming would bank none of it.
        clock.advance(10.minutes.inWholeMilliseconds)

        restored.onEvent(ActiveSessionEvent.PauseToggled)

        assertFalse(restored.uiState.value.isPaused)
        assertTrue(state.current is SessionState.Active)
        assertEquals(1.minutes, restored.uiState.value.header?.elapsed)
    }

    @Test
    fun `a pause the store cannot write is reported rather than thrown`() = runTest {
        // Room throws on a disk error rather than returning a refusal, and there is no
        // `CoroutineExceptionHandler` on `viewModelScope` — so an unwrapped call here takes the
        // process down over a workout that is still perfectly recoverable.
        val store = pushDayStore()
        val viewModel = viewModel(store = store)
        store.updateFailure = IllegalStateException("disk full")

        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        assertEquals(ActiveSessionMessage.PAUSE_FAILED, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isPaused)
    }

    @Test
    fun `a paused session takes no set-type change`() = runTest {
        // The chips are drawn inert, and this is the event dispatched in the frame before they
        // were: a change that landed would be written into the draft, over a session every
        // write path considers read-only.
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        viewModel.onEvent(ActiveSessionEvent.SetTypeChanged(BENCH_ROW, SetType.WARMUP))
        scheduler.advanceTimeBy(DRAFT_SETTLED)

        assertEquals(SetType.NORMAL, viewModel.uiState.value.entryOf(BENCH_ROW).setType)
        assertEquals(SetType.NORMAL, drafts.stored?.exercises?.first()?.sets?.single()?.setType)
    }

    @Test
    fun `a paused session takes no rating and no keystroke`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        viewModel.onEvent(ActiveSessionEvent.RpeChanged(BENCH_ROW, 85))
        viewModel.onEvent(ActiveSessionEvent.WeightChanged(BENCH_ROW, "100"))

        assertNull(viewModel.uiState.value.entryOf(BENCH_ROW).rpe)
        assertEquals("80", viewModel.uiState.value.entryOf(BENCH_ROW).weight)
    }

    // ─── The rest between two sets ──────────────────────────────────────────────────────

    /*
     * The countdown itself is `RestTimerControllerTest`'s subject and is not re-tested here.
     * What these ask is the ViewModel's half: which events start a rest and which do not, how
     * long it lasts, what the sheet says is coming, and what survives the process — the last of
     * which is the only place on this screen where the draft carries something that goes stale
     * if it is written down wrong.
     *
     * All of it runs on virtual time; see the `timeSource` passed in `viewModel()`.
     */

    @Test
    fun `logging a set starts the rest and raises the sheet`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertEquals(RestTimerState.Running(90.seconds, 90.seconds), viewModel.uiState.value.rest)
        assertTrue(viewModel.uiState.value.isRestSheetOpen)
    }

    @Test
    fun `the rest is the user's setting`() = runTest {
        settings.setDefaultRestDuration(3.minutes)
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertEquals(RestTimerState.Running(3.minutes, 3.minutes), viewModel.uiState.value.rest)
    }

    @Test
    fun `an exercise with a rest of its own overrides the setting`() = runTest {
        // `exercise.default_rest` is seconds, per PERSISTENCE.md's units table.
        val viewModel = viewModel(
            catalogue = FakeExerciseCatalogue(listOf(BENCH.copy(defaultRest = 150L), INCLINE, FLY)),
        )

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertEquals(RestTimerState.Running(150.seconds, 150.seconds), viewModel.uiState.value.rest)
    }

    @Test
    fun `the sheet says what the next set is expected to be`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        // The set just logged, which is what the lifter is about to do again. See `RestTarget`.
        assertEquals(RestTarget(weight = 80_000_000L, reps = 5), viewModel.uiState.value.restTarget)
    }

    @Test
    fun `no rest is started between the members of a superset`() = runTest {
        // The incline and the fly are run back to back: what follows a set on the first of them
        // is a set on the second, not a pause.
        val viewModel = viewModel(store = supersetStore())

        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(INCLINE_ROW))

        assertEquals(RestTimerState.Idle, viewModel.uiState.value.rest)
        assertFalse(viewModel.uiState.value.isRestSheetOpen)
    }

    @Test
    fun `a set logged between two superset members is still written to the draft`() = runTest {
        // The set clears the entry row, and the draft is what the row comes back from after a
        // kill. Starting no rest must not take the save with it.
        val viewModel = viewModel(store = supersetStore())

        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(INCLINE_ROW))

        assertEquals(RestTimerSnapshot.Idle, drafts.stored?.restTimer)
        assertTrue(drafts.stored?.exercises?.any { it.sessionExerciseId == INCLINE_ROW } == true)
    }

    @Test
    fun `the rest comes after the last member of a superset`() = runTest {
        val viewModel = viewModel(store = supersetStore())

        viewModel.enter(weight = "20", reps = "15", sessionExerciseId = FLY_ROW)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(FLY_ROW))

        assertEquals(RestTimerState.Running(90.seconds, 90.seconds), viewModel.uiState.value.rest)
    }

    @Test
    fun `ticking a logged set back on starts the rest, and un-ticking one does not`() = runTest {
        val viewModel = viewModel(store = benchStore())

        viewModel.onEvent(
            ActiveSessionEvent.SetCompletionToggled(BENCH_ROW, setId = 2L, isCompleted = false),
        )

        // An un-tick is a correction to a row logged some time ago, not the end of a set.
        assertEquals(RestTimerState.Idle, viewModel.uiState.value.rest)

        viewModel.onEvent(
            ActiveSessionEvent.SetCompletionToggled(BENCH_ROW, setId = 2L, isCompleted = true),
        )

        assertEquals(RestTimerState.Running(90.seconds, 90.seconds), viewModel.uiState.value.rest)
    }

    @Test
    fun `adjusting moves the remainder and leaves the total alone`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        testScheduler.advanceTimeBy(30.seconds)
        viewModel.onEvent(ActiveSessionEvent.RestAdjusted(15.seconds))

        // 60 left of 90 became 75 left of 90: the bar moves back by what was added and by
        // nothing else, which is what keeping the total is for.
        assertEquals(RestTimerState.Running(75.seconds, 90.seconds), viewModel.uiState.value.rest)
    }

    @Test
    fun `a rest paused from the sheet stops consuming time until it is resumed`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        testScheduler.advanceTimeBy(30.seconds)
        viewModel.onEvent(ActiveSessionEvent.RestPauseToggled)
        testScheduler.advanceTimeBy(5.minutes)

        assertEquals(RestTimerState.Paused(60.seconds, 90.seconds), viewModel.uiState.value.rest)

        viewModel.onEvent(ActiveSessionEvent.RestPauseToggled)

        assertEquals(RestTimerState.Running(60.seconds, 90.seconds), viewModel.uiState.value.rest)
    }

    @Test
    fun `skipping ends the rest, closes the sheet and leaves the timer idle`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        viewModel.onEvent(ActiveSessionEvent.RestSkipped)

        // Idle rather than finished: a rest that was dismissed is not one that was rested
        // through, and nothing should ring for it.
        assertEquals(RestTimerState.Idle, viewModel.uiState.value.rest)
        assertFalse(viewModel.uiState.value.isRestSheetOpen)
        assertEquals(RestTimerSnapshot.Idle, drafts.stored?.restTimer)
    }

    @Test
    fun `a rest left to run out finishes and takes the sheet down with it`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        testScheduler.advanceTimeBy(91.seconds)

        assertEquals(RestTimerState.Finished, viewModel.uiState.value.rest)
        assertFalse(viewModel.uiState.value.isRestSheetOpen)
        // Finished, not idle: what happened is a rest that ended, and the draft has to be able
        // to say which of the two it was.
        assertEquals(RestTimerSnapshot.Finished, drafts.stored?.restTimer)
    }

    @Test
    fun `swiping the sheet away leaves the countdown running, and it comes back on request`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        viewModel.onEvent(ActiveSessionEvent.RestSheetDismissed)
        advanceRestTo(30.seconds)

        assertFalse(viewModel.uiState.value.isRestSheetOpen)
        assertEquals(RestTimerState.Running(60.seconds, 90.seconds), viewModel.uiState.value.rest)

        viewModel.onEvent(ActiveSessionEvent.RestSheetRequested)

        assertTrue(viewModel.uiState.value.isRestSheetOpen)
        assertEquals(RestTimerState.Running(60.seconds, 90.seconds), viewModel.uiState.value.rest)
    }

    @Test
    fun `the draft carries the rest as a deadline rather than as a remainder`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertEquals(
            RestTimerSnapshot.Running(deadline = STARTED_AT + 90_000, total = 90.seconds),
            drafts.stored?.restTimer,
        )
    }

    @Test
    fun `the ticks do not rewrite the draft`() = runTest {
        // The whole reason an instant is persisted rather than a remainder: a deadline does not
        // move as it is counted down to, so ninety seconds of ticking is no writes at all. A
        // remainder would be a store write a second, and each of them would push the deadline
        // out by however stale the last tick was.
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))
        val writes = drafts.saves.size

        testScheduler.advanceTimeBy(30.seconds)

        assertEquals(writes, drafts.saves.size)
    }

    @Test
    fun `a rest survives the process with the time that passed while it was dead taken off`() = runTest {
        // The draft as it would have been written thirty seconds ago, and a clock that has
        // moved on since. Nothing was running to tick the countdown down; the deadline is what
        // does it.
        val draft = restingDraft(deadline = STARTED_AT + 90_000)
        clock.now = STARTED_AT + 30_000

        val restored = viewModel(drafts = FakeDraftStore(draft))

        assertEquals(RestTimerState.Running(60.seconds, 90.seconds), restored.uiState.value.rest)
        // Straight back up: a rest is the one thing on this screen that went on happening while
        // the app was not running.
        assertTrue(restored.uiState.value.isRestSheetOpen)
    }

    @Test
    fun `a rest whose deadline passed while the process was gone comes back finished`() = runTest {
        val draft = restingDraft(deadline = STARTED_AT + 90_000)
        clock.now = STARTED_AT + 10.minutes.inWholeMilliseconds

        val restored = viewModel(drafts = FakeDraftStore(draft))

        // Finished rather than a countdown owing time: `RestTimerState.Running` cannot hold a
        // non-positive remainder, and a sheet over a rest that ended nine minutes ago would be
        // the screen asking the user to wait for something that is already over.
        assertEquals(RestTimerState.Finished, restored.uiState.value.rest)
        assertFalse(restored.uiState.value.isRestSheetOpen)
    }

    @Test
    fun `a rest started while the draft was still being read is not restored over`() = runTest {
        // The read is held open, which is the window the bug lived in: `isLoading` clears on the
        // session store's first emission, so the screen takes taps well before the draft comes
        // back off disk.
        val gated = FakeDraftStore(restingDraft(deadline = STARTED_AT + 90_000)).apply { holdLoad() }
        clock.now = STARTED_AT + 30_000
        val viewModel = viewModel(drafts = gated)

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))
        val started = viewModel.uiState.value.rest

        gated.releaseLoad()
        runCurrent()

        // A fresh 90 of 90, not the draft's 60 of 90. The one the user is watching wins: a
        // restore landing on top of it would cancel the countdown and take its sheet down.
        assertEquals(RestTimerState.Running(90.seconds, 90.seconds), started)
        assertEquals(started, viewModel.uiState.value.rest)
        assertTrue(viewModel.uiState.value.isRestSheetOpen)
    }

    @Test
    fun `a restored rest still knows what set is coming`() = runTest {
        val restored = viewModel(
            store = benchStore(),
            drafts = FakeDraftStore(restingDraft(deadline = STARTED_AT + 90_000)),
        )

        // Off the exercise the draft named, and out of the store: the second set of the bench,
        // 70 x 8. Nothing about the target is written into the draft itself.
        assertEquals(RestTarget(weight = 70_000_000L, reps = 8), restored.uiState.value.restTarget)
    }

    // ─── The draft ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a half-typed set is saved to the draft`() = runTest {
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "5")

        val entry = drafts.stored?.exercises?.first()?.sets?.single()
        assertEquals(80_000_000L, entry?.weight)
        assertEquals(5, entry?.reps)
        assertFalse(entry?.isCompleted ?: true)
    }

    @Test
    fun `every exercise's half-typed set is saved, not just one`() = runTest {
        // What a superset needs from the draft: a lifter alternating two exercises has a weight
        // standing in both, and a format that could only carry one would lose the other.
        val viewModel = viewModel()

        viewModel.enter(weight = "80", reps = "5", sessionExerciseId = BENCH_ROW)
        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)

        assertEquals(
            listOf(80_000_000L, 30_000_000L),
            drafts.stored?.exercises.orEmpty().flatMap { it.sets }.map { it.weight },
        )
    }

    @Test
    fun `the draft is read back when the screen is reopened`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5", sessionExerciseId = BENCH_ROW)
        viewModel.enter(weight = "30", reps = "12", sessionExerciseId = INCLINE_ROW)

        val restored = viewModel(drafts = FakeDraftStore(drafts.stored))

        assertEquals("80", restored.uiState.value.entryOf(BENCH_ROW).weight)
        assertEquals("30", restored.uiState.value.entryOf(INCLINE_ROW).weight)
        assertEquals("12", restored.uiState.value.entryOf(INCLINE_ROW).reps)
    }

    @Test
    fun `a draft belonging to another session is left alone`() = runTest {
        val other = ActiveSession(sessionId = ACTIVE_SESSION_ID + 1, startedAt = STARTED_AT)
        val store = FakeDraftStore(other)

        val viewModel = viewModel(drafts = store)

        assertEquals(BENCH, viewModel.uiState.value.exercises.first().exercise)
        assertEquals(SetEntry(), viewModel.uiState.value.entryOf(BENCH_ROW))
    }

    @Test
    fun `nothing is written to the draft once the workout is over`() = runTest {
        // The layout actions save in their coroutine tail, so one still in flight when END was
        // tapped lands after `FinishSessionUseCase` has cleared the draft — and would write a
        // new one naming a session that is finished, which is the offer-to-resume-a-workout-
        // that-is-over state clearing it exists to prevent.
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))
        viewModel.onEvent(ActiveSessionEvent.FinishRequested)

        viewModel.onEvent(ActiveSessionEvent.ExercisesPicked(listOf(PUSHDOWN.id)))

        assertNotNull(viewModel.uiState.value.finishedSessionId)
        assertNull(drafts.stored)
    }

    @Test
    fun `a draft that cannot be written costs a place, not a set`() = runTest {
        val store = pushDayStore()
        drafts.saveFailure = IllegalStateException("disk full")
        val viewModel = viewModel(store = store)

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertEquals(1, store.logged.size)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `typing a number writes the draft once, not once per keystroke`() = runTest {
        // Each save is a full encode of the session, an fsync and an atomic rename. Typing
        // `75` and then `10` used to be four of them for one set.
        val viewModel = viewModel()

        listOf("7", "75").forEach { viewModel.onEvent(ActiveSessionEvent.WeightChanged(BENCH_ROW, it)) }
        listOf("1", "10").forEach { viewModel.onEvent(ActiveSessionEvent.RepsChanged(BENCH_ROW, it)) }
        assertTrue(drafts.saves.isEmpty())

        scheduler.advanceTimeBy(DRAFT_SETTLED)

        assertEquals(1, drafts.saves.size)
        assertEquals(75_000_000L, drafts.stored?.exercises?.first()?.sets?.single()?.weight)
    }

    @Test
    fun `logging a set writes the draft immediately, without waiting out the typing`() = runTest {
        // The debounce is the keystroke path's alone. What the user has committed — a set, an
        // exercise, a superset, a pause — is on disk before the call returns.
        val viewModel = viewModel()
        viewModel.onEvent(ActiveSessionEvent.WeightChanged(BENCH_ROW, "80"))
        viewModel.onEvent(ActiveSessionEvent.RepsChanged(BENCH_ROW, "5"))

        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        // One save, with no time advanced at all: the two keystrokes above are still inside the
        // quiet period, so this is the log's own write and not theirs arriving late.
        assertEquals(1, drafts.saves.size)

        // And the debounced save they queued is superseded rather than added to, so the older
        // state never lands on top of what the log just wrote.
        scheduler.advanceTimeBy(DRAFT_SETTLED)
        assertEquals(1, drafts.saves.size)
    }

    // ─── Finishing ──────────────────────────────────────────────────────────────────────

    @Test
    fun `finishing a session with sets reports the session to recap`() = runTest {
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        viewModel.onEvent(ActiveSessionEvent.FinishRequested)

        assertEquals(ACTIVE_SESSION_ID, viewModel.uiState.value.finishedSessionId)
    }

    @Test
    fun `ending the workout writes the correction the user never left the row to save`() = runTest {
        val store = benchStore()
        val write = CompletableDeferred<Unit>()
        store.beforeUpdate = { write.await() }
        val viewModel = viewModel(store = store)

        // Typed and not left: END is a button, and tapping one does not take focus off a field,
        // so nothing has asked for this correction to be written.
        viewModel.onEvent(ActiveSessionEvent.SetEditWeightChanged(BENCH_ROW, setId = 1L, text = "72.5"))
        viewModel.onEvent(ActiveSessionEvent.FinishRequested)

        assertNull(
            "the session is not closed out over a correction still going in",
            viewModel.uiState.value.finishedSessionId,
        )
        write.complete(Unit)

        // The recap is built from what the store holds, so a correction left behind here is one
        // the user watched themselves make and would not find.
        assertEquals(72_500_000L, store.stored.first { it.id == 1L }.weight)
        assertNotNull(viewModel.uiState.value.finishedSessionId)
    }

    @Test
    fun `finishing with nothing logged is refused inline and goes nowhere`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.FinishRequested)

        assertEquals(ActiveSessionMessage.NO_SETS_LOGGED, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.finishedSessionId)
        assertFalse(viewModel.uiState.value.isFinishing)
    }

    @Test
    fun `END is inert while a set write is in flight`() = runTest {
        // `LogSetUseCase` reads the session state once, before it inserts. A finish that
        // overtakes the insert leaves the set in a session already stamped closed — which the
        // recap undercounts, or refuses outright as a workout with nothing logged.
        val viewModel = viewModel()
        viewModel.enter(weight = "80", reps = "5")

        assertTrue(viewModel.uiState.value.canFinish)
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))
        assertTrue(viewModel.uiState.value.canFinish)
    }

    @Test
    fun `a finish dispatched during a set write is ignored`() = runTest {
        val viewModel = viewModel(
            store = pushDayStore().apply { insertFailure = IllegalStateException("slow disk") },
        )
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertNull(viewModel.uiState.value.finishedSessionId)
    }

    @Test
    fun `a refused finish leaves the session running`() = runTest {
        val state = FakeSessionStateStore()
        val viewModel = viewModel(state = state)

        viewModel.onEvent(ActiveSessionEvent.FinishRequested)

        assertTrue(state.current is SessionState.Active)
    }

    @Test
    fun `pausing is ignored once the workout is on its way out`() = runTest {
        // The header stays on screen for as long as the finish takes, so a pause can be
        // dispatched behind one. Landing after it, the transition is refused for a session that
        // is over and a "could not change the session" snackbar goes up over a screen that is
        // successfully leaving for the recap; landing before it, it suspends a workout in the
        // middle of being closed out.
        val state = FakeSessionStateStore()
        val viewModel = viewModel(state = state)
        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))
        viewModel.onEvent(ActiveSessionEvent.FinishRequested)

        viewModel.onEvent(ActiveSessionEvent.PauseToggled)

        assertFalse(viewModel.uiState.value.isPaused)
        assertNull(viewModel.uiState.value.message)
        assertTrue(state.current is SessionState.Finished)
    }

    // ─── Discarding ─────────────────────────────────────────────────────────────────────

    @Test
    fun `discarding asks first`() = runTest {
        val store = pushDayStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.DiscardRequested)

        assertTrue(viewModel.uiState.value.confirmingDiscard)
        assertFalse(viewModel.uiState.value.isDiscarded)
    }

    @Test
    fun `confirming a discard deletes the session and everything under it`() = runTest {
        val store = pushDayStore(sets = listOf(loggedSet(id = 1, sessionExerciseId = 1, setNumber = 1)))
        val state = FakeSessionStateStore(
            SessionState.Active(sessionId = ACTIVE_SESSION_ID, startedAt = STARTED_AT),
        )
        val viewModel = viewModel(store = store, state = state)

        viewModel.onEvent(ActiveSessionEvent.DiscardRequested)
        viewModel.onEvent(ActiveSessionEvent.DiscardConfirmed)

        assertTrue(viewModel.uiState.value.isDiscarded)
        assertEquals(SessionState.Idle, state.current)
        assertEquals(1, drafts.clearCount)
    }

    @Test
    fun `a confirmed discard takes the dialog with it`() = runTest {
        // Both of its buttons are inert once `isDiscarding` is set, so a dialog left up would
        // sit over the whole pop transition with nothing to offer.
        val viewModel = viewModel()

        viewModel.onEvent(ActiveSessionEvent.DiscardRequested)
        viewModel.onEvent(ActiveSessionEvent.DiscardConfirmed)

        assertFalse(viewModel.uiState.value.confirmingDiscard)
    }

    @Test
    fun `dismissing the confirmation keeps the workout`() = runTest {
        val store = pushDayStore()
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.DiscardRequested)
        viewModel.onEvent(ActiveSessionEvent.DiscardDismissed)

        assertFalse(viewModel.uiState.value.confirmingDiscard)
        assertFalse(viewModel.uiState.value.isDiscarded)
        assertNotNull(viewModel.uiState.value.header)
    }

    // ─── Previous performance ───────────────────────────────────────────────────────────

    @Test
    fun `each set is shown against the same set of the last session that had this exercise`() = runTest {
        val store = benchStore()
        store.setPreviousSets(mapOf(BENCH.id to previousSets(60_000_000L to 10, 70_000_000L to 8)))

        val row = viewModel(store = store).uiState.value.rowOf(BENCH_ROW)

        assertEquals(
            listOf(60_000_000L to 10, 70_000_000L to 8),
            row.sets.map { it.previous?.weight to it.previous?.reps },
        )
    }

    @Test
    fun `the entry row is shown what the next set was last time`() = runTest {
        // The one row where the column is a prediction rather than a comparison, and the reason
        // it is worth its width: it is the number a lifter decides against before loading a bar.
        val store = benchStore()
        store.setPreviousSets(
            mapOf(BENCH.id to previousSets(60_000_000L to 10, 70_000_000L to 8, 75_000_000L to 6)),
        )

        val row = viewModel(store = store).uiState.value.rowOf(BENCH_ROW)

        assertEquals(75_000_000L, row.entryPrevious?.weight)
        assertEquals(6, row.entryPrevious?.reps)
    }

    @Test
    fun `an exercise never performed says so rather than drawing an empty column`() = runTest {
        val store = benchStore()

        val row = viewModel(store = store).uiState.value.rowOf(BENCH_ROW)

        assertFalse(row.hasHistory)
        assertTrue(row.sets.all { it.previous == null })
        assertNull(row.entryPrevious)
    }

    @Test
    fun `history and a short reference are two different states`() = runTest {
        // Two sets today against one last time. The second row has nothing to show and the
        // exercise still has history — a card that reported "first time" here would be telling
        // the user they have never benched on the strength of one extra set.
        val store = benchStore()
        store.setPreviousSets(mapOf(BENCH.id to previousSets(60_000_000L to 10)))

        val row = viewModel(store = store).uiState.value.rowOf(BENCH_ROW)

        assertTrue(row.hasHistory)
        assertEquals(60_000_000L, row.sets[0].previous?.weight)
        assertNull(row.sets[1].previous)
        assertNull(row.entryPrevious)
    }

    @Test
    fun `a warm-up takes no reference, and the working sets keep theirs`() = runTest {
        // Today's set 1 is a warm-up, so today's set 2 is the *first* working set and reads
        // against last session's first working set. Aligning by row number instead would show
        // last week's opener beside a warm-up and last week's second set beside today's first.
        val store = FakeActiveSessionStore(
            session = runningSession(),
            exercises = listOf(activeExercise(id = BENCH_ROW, exerciseId = BENCH.id, position = 0)),
            sets = listOf(
                loggedSet(id = 1L, sessionExerciseId = BENCH_ROW, setNumber = 1, reps = 12, weight = 40_000_000L)
                    .copy(setType = SetType.WARMUP),
                loggedSet(id = 2L, sessionExerciseId = BENCH_ROW, setNumber = 2, reps = 10, weight = 80_000_000L),
            ),
        )
        store.setPreviousSets(mapOf(BENCH.id to previousSets(75_000_000L to 10, 80_000_000L to 8)))

        val row = viewModel(store = store).uiState.value.rowOf(BENCH_ROW)

        assertNull(row.sets[0].previous)
        assertEquals(75_000_000L, row.sets[1].previous?.weight)
        assertEquals(80_000_000L, row.entryPrevious?.weight)
    }

    @Test
    fun `retyping a row as a warm-up gives up its reference straight away`() = runTest {
        val store = benchStore()
        store.setPreviousSets(mapOf(BENCH.id to previousSets(60_000_000L to 10, 70_000_000L to 8)))
        val viewModel = viewModel(store = store)

        viewModel.onEvent(ActiveSessionEvent.SetEditTypeChanged(BENCH_ROW, 1L, SetType.WARMUP))

        val row = viewModel.uiState.value.rowOf(BENCH_ROW)
        assertNull(row.sets[0].previous)
        // And the set below it moves up onto the reference the warm-up gave back.
        assertEquals(60_000_000L, row.sets[1].previous?.weight)
    }

    @Test
    fun `a ten-exercise workout reads its history once, not once per exercise`() = runTest {
        val store = FakeActiveSessionStore(
            session = runningSession(),
            exercises = (1..10).map { activeExercise(id = it.toLong(), exerciseId = it.toLong(), position = it - 1) },
        )

        val state = viewModel(store = store).uiState.value

        // The whole point of the query's shape. One lookup per exercise would also be ten
        // `Flow`s held open on `workout_set` for the length of a workout, which is the failure
        // this number is standing in for.
        assertEquals(10, state.exercises.size)
        assertEquals(1, store.previousSetsReads)
    }

    // ─── Reattaching ────────────────────────────────────────────────────────────────────

    @Test
    fun `a session running on disk but not in the state machine is picked back up`() = runTest {
        // What a process restart leaves behind: the row is still `is_in_progress = 1` and the
        // in-memory state has gone. Without the reattach the screen would come back read-only,
        // since every write refuses a session that is not active.
        val state = FakeSessionStateStore(SessionState.Idle)
        val store = pushDayStore()
        val viewModel = viewModel(store = store, state = state)

        viewModel.enter(weight = "80", reps = "5")
        viewModel.onEvent(ActiveSessionEvent.SetLogged(BENCH_ROW))

        assertTrue(state.current is SessionState.Active)
        assertEquals(1, store.logged.size)
    }

    // ─── A session that is gone ─────────────────────────────────────────────────────────

    @Test
    fun `a session id that no longer resolves draws as missing rather than as loading`() = runTest {
        val state = viewModel(store = FakeActiveSessionStore()).uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.isMissing)
    }

    // ─── Support ────────────────────────────────────────────────────────────────────────

    /**
     * Types a weight and a rep count into one exercise's entry row, one field at a time, then
     * stops typing for long enough that the draft is written.
     *
     * The wait is the debounce working rather than an artefact of testing it: the keystroke
     * path writes the draft once the typing stops, so a test that asserts on what was written
     * has to stop typing first. Nothing else needs it — a set logged, an exercise added or
     * removed, a superset and a pause all save immediately.
     */
    private fun ActiveSessionViewModel.enter(
        weight: String,
        reps: String,
        sessionExerciseId: Long = BENCH_ROW,
    ) {
        onEvent(ActiveSessionEvent.WeightChanged(sessionExerciseId, weight))
        onEvent(ActiveSessionEvent.RepsChanged(sessionExerciseId, reps))
        scheduler.advanceTimeBy(DRAFT_SETTLED)
    }

    /** A stored set's weight as the table draws it, so a renumbering reads as sets rather
     *  than as milligrams. */
    private fun WorkoutSet.weightText(): String =
        weight?.let { formatWeightValue(it, UnitSystem.METRIC) }.orEmpty()

    /** The exercise's row on screen. Fails the test rather than returning null: every one of
     *  these fixtures has the exercise it asks about. */
    private fun ActiveSessionUiState.rowOf(sessionExerciseId: Long): SessionExerciseRow =
        exercises.first { it.sessionExerciseId == sessionExerciseId }

    /** What is half-typed against one exercise. */
    private fun ActiveSessionUiState.entryOf(sessionExerciseId: Long): SetEntry =
        rowOf(sessionExerciseId).entry

    /**
     * What an exercise did last time, as the store hands it over: completed working sets in
     * the order they were performed, numbered from 1.
     *
     * The `session_exercise` id on them is a workout this screen knows nothing else about,
     * which is exactly the point of the reference being keyed by catalogue id.
     */
    private fun previousSets(vararg sets: Pair<Long, Int>): List<WorkoutSet> =
        sets.mapIndexed { index, (weight, reps) ->
            loggedSet(
                id = 100L + index,
                sessionExerciseId = PREVIOUS_ROW,
                setNumber = index + 1,
                reps = reps,
                weight = weight,
            )
        }

    /** Bench, incline and fly, in that order, with whatever has been logged so far. */
    private fun pushDayStore(sets: List<WorkoutSet> = emptyList()) = FakeActiveSessionStore(
        session = runningSession(),
        exercises = listOf(
            activeExercise(id = BENCH_ROW, exerciseId = BENCH.id, position = 0),
            activeExercise(id = INCLINE_ROW, exerciseId = INCLINE.id, position = 1),
            activeExercise(id = FLY_ROW, exerciseId = FLY.id, position = 2),
        ),
        sets = sets,
    )

    /** The bench alone, with two sets logged against it: 60 x 10 and 70 x 8. */
    private fun benchStore() = FakeActiveSessionStore(
        session = runningSession(),
        exercises = listOf(activeExercise(id = BENCH_ROW, exerciseId = BENCH.id, position = 0)),
        sets = listOf(
            loggedSet(id = 1L, sessionExerciseId = BENCH_ROW, setNumber = 1, reps = 10, weight = 60_000_000L),
            loggedSet(id = 2L, sessionExerciseId = BENCH_ROW, setNumber = 2, reps = 8, weight = 70_000_000L),
        ),
    )

    /** The same, with a third set on the end, so a deletion has a middle to take out of. */
    private fun threeSetStore() = FakeActiveSessionStore(
        session = runningSession(),
        exercises = listOf(activeExercise(id = BENCH_ROW, exerciseId = BENCH.id, position = 0)),
        sets = listOf(
            loggedSet(id = 1L, sessionExerciseId = BENCH_ROW, setNumber = 1, reps = 10, weight = 60_000_000L),
            loggedSet(id = 2L, sessionExerciseId = BENCH_ROW, setNumber = 2, reps = 8, weight = 70_000_000L),
            loggedSet(id = 3L, sessionExerciseId = BENCH_ROW, setNumber = 3, reps = 6, weight = 75_000_000L),
        ),
    )

    /** The same push day, with the incline and the fly already run as a superset. */
    private fun supersetStore() = FakeActiveSessionStore(
        session = runningSession(),
        exercises = listOf(
            activeExercise(id = BENCH_ROW, exerciseId = BENCH.id, position = 0),
            activeExercise(id = INCLINE_ROW, exerciseId = INCLINE.id, position = 1, supersetGroup = 0),
            activeExercise(id = FLY_ROW, exerciseId = FLY.id, position = 2, supersetGroup = 0),
        ),
    )

    /**
     * Advances virtual time so exactly [offset] has passed, *including* the tick scheduled at
     * that instant.
     *
     * `advanceTimeBy` alone stops just before it, which for a countdown is the difference
     * between `1:00` and `1:01`: the remainder that has been published is the one from the
     * previous tick. The domain's own rig makes the same distinction and says so at length —
     * see `RestTimerTestSupport.advanceTimeTo`. Only the tests that read the *published*
     * remainder need this; the ones that command the timer get the live one either way.
     */
    private fun TestScope.advanceRestTo(offset: Duration) {
        testScheduler.advanceTimeBy(offset)
        testScheduler.runCurrent()
    }

    /**
     * A draft written mid-rest: the bench, with one rest in flight counting to [deadline].
     *
     * `currentExerciseIndex` is what carries the exercise the rest belongs to across the
     * restart — see `ActiveSessionViewModel.saveDraft`.
     */
    private fun restingDraft(deadline: Long) = ActiveSession(
        sessionId = ACTIVE_SESSION_ID,
        startedAt = STARTED_AT,
        exercises = listOf(
            ActiveExercise(
                exerciseId = BENCH.id,
                sessionExerciseId = BENCH_ROW,
                sets = listOf(
                    ActiveSet(id = 2L, reps = 8, weight = 70_000_000L, isCompleted = true, completedAt = 1L),
                ),
            ),
        ),
        currentExerciseIndex = 0,
        restTimer = RestTimerSnapshot.Running(deadline = deadline, total = 90.seconds),
    )

    /**
     * The ViewModel for one workout, with its state already flowing.
     *
     * The id arrives the way it does in the app — decoded off the destination's arguments in the
     * `SavedStateHandle` — rather than as a constructor parameter, so a rename of the route's
     * field is a failure here rather than a blank screen at runtime.
     *
     * A lock of this test's own rather than the shared default, matching the domain's own
     * integration tests: a process-wide mutex is not something a test should be sharing with
     * every other suite in the JVM. The layout use cases take it too — the same instance the
     * app gives them — because a removal and a superset link are the same read-modify-write
     * over one session's grouping and have to queue behind each other.
     */
    private fun TestScope.viewModel(
        store: FakeActiveSessionStore = pushDayStore(),
        state: FakeSessionStateStore = FakeSessionStateStore(
            SessionState.Active(sessionId = ACTIVE_SESSION_ID, startedAt = STARTED_AT),
        ),
        drafts: FakeDraftStore = this@ActiveSessionViewModelTest.drafts,
        catalogue: FakeExerciseCatalogue = this@ActiveSessionViewModelTest.catalogue,
        settings: FakeSettings = this@ActiveSessionViewModelTest.settings,
    ): ActiveSessionViewModel {
        val lock = SessionLifecycleLock()
        val viewModel = ActiveSessionViewModel(
            savedStateHandle = SavedStateHandle(mapOf("sessionId" to ACTIVE_SESSION_ID)),
            sessionRepository = store,
            sessionStateRepository = state,
            inProgressSessionRepository = drafts,
            exerciseRepository = catalogue,
            userPreferencesRepository = settings,
            logSet = LogSetUseCase(store, state, clock),
            editSet = EditSetUseCase(store, state, clock),
            deleteSet = DeleteSetUseCase(store, state, clock),
            restoreSet = RestoreSetUseCase(store, state, clock),
            markSetCompleted = MarkSetCompletedUseCase(store, state, clock),
            addExercises = AddSessionExercisesUseCase(store, state, clock),
            removeExercise = RemoveSessionExerciseUseCase(store, state, clock, lock),
            reorderExercises = ReorderSessionExercisesUseCase(store, state, clock, lock),
            swapExercise = SwapSessionExerciseUseCase(store, state, clock, lock),
            linkSuperset = LinkSupersetUseCase(store, state, clock, lock),
            unlinkSuperset = UnlinkSupersetUseCase(store, state, clock, lock),
            renameSession = RenameSessionUseCase(store),
            pauseSession = PauseSessionUseCase(store, state, clock, lock),
            resumeSession = ResumeSessionUseCase(store, state, clock, lock),
            finishSession = FinishSessionUseCase(store, state, drafts, clock, lock),
            discardSession = DiscardSessionUseCase(store, state, drafts, lock),
            clock = clock,
            // Virtual time, so a ninety-second rest costs the suite nothing and the countdown
            // and the scheduler cannot run ahead of each other. The rig `RestTimerTestSupport`
            // sets up in `:core:domain`, wired through the ViewModel that owns the controller.
            timeSource = testScheduler.timeSource,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    private companion object {
        /**
         * The `session_exercise` ids `pushDayStore` writes, in position order.
         *
         * Named because every event on this screen carries one: with no exercise "open", a set,
         * a note and a superset are all aimed at a row by id, and `1L` three lines apart in a
         * test says nothing about which exercise it is.
         */
        const val BENCH_ROW = 1L
        const val INCLINE_ROW = 2L
        const val FLY_ROW = 3L

        /** A `session_exercise` row of some earlier workout, which this screen never resolves. */
        const val PREVIOUS_ROW = 99L

        /**
         * Long enough for the debounced draft save to fire, short enough not to reach a tick.
         *
         * Both bounds matter: under the ViewModel's own quiet period nothing is written and the
         * assertion is about a save that has not happened yet, and over its one-second tick the
         * elapsed clock moves under tests that are not about the clock.
         */
        val DRAFT_SETTLED = 500.milliseconds
    }
}
