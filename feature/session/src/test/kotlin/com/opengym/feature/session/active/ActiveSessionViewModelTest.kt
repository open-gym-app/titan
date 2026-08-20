package com.opengym.feature.session.active

import androidx.lifecycle.SavedStateHandle
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.usecase.AddSessionExercisesUseCase
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.FinishSessionUseCase
import com.opengym.core.domain.usecase.LinkSupersetUseCase
import com.opengym.core.domain.usecase.LogSetUseCase
import com.opengym.core.domain.usecase.PauseSessionUseCase
import com.opengym.core.domain.usecase.RemoveSessionExerciseUseCase
import com.opengym.core.domain.usecase.RenameSessionUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SessionLifecycleLock
import com.opengym.core.domain.usecase.UnlinkSupersetUseCase
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.feature.session.newsession.FakeExerciseCatalogue
import com.opengym.feature.session.newsession.FakeSessionStateStore
import com.opengym.feature.session.newsession.FakeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
    fun `a set the domain refuses keeps what was typed and says why`() = runTest {
        // The screen's own check passes — the shape rule is the exercise type's, and only the
        // use case knows it, since a plank records a duration rather than reps.
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
        assertEquals("80", viewModel.uiState.value.entryOf(BENCH_ROW).weight)
        assertTrue(viewModel.uiState.value.entryOf(BENCH_ROW).error is SetEntryError.Refused)
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

    /** The exercise's row on screen. Fails the test rather than returning null: every one of
     *  these fixtures has the exercise it asks about. */
    private fun ActiveSessionUiState.rowOf(sessionExerciseId: Long): SessionExerciseRow =
        exercises.first { it.sessionExerciseId == sessionExerciseId }

    /** What is half-typed against one exercise. */
    private fun ActiveSessionUiState.entryOf(sessionExerciseId: Long): SetEntry =
        rowOf(sessionExerciseId).entry

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
            addExercises = AddSessionExercisesUseCase(store, state, clock),
            removeExercise = RemoveSessionExerciseUseCase(store, state, clock, lock),
            linkSuperset = LinkSupersetUseCase(store, state, clock, lock),
            unlinkSuperset = UnlinkSupersetUseCase(store, state, clock, lock),
            renameSession = RenameSessionUseCase(store),
            pauseSession = PauseSessionUseCase(store, state, clock, lock),
            resumeSession = ResumeSessionUseCase(store, state, clock, lock),
            finishSession = FinishSessionUseCase(store, state, drafts, clock, lock),
            discardSession = DiscardSessionUseCase(store, state, drafts, lock),
            clock = clock,
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
