package com.opengym.feature.session.summary

import androidx.lifecycle.SavedStateHandle
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.stats.MuscleVolumeCalculator
import com.opengym.core.domain.stats.SessionStatsCalculator
import com.opengym.core.domain.usecase.CreateTemplateUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the recap makes of a session on disk.
 *
 * The screen's own question — does it *draw* this state — is `SessionSummaryScreenTest`'s.
 * What is asserted here is that the figures are the ones the domain would compute, that a
 * record set in this session is told apart from one set in another, and that the two writes
 * land or fail the way the state says they did.
 *
 * The stats and muscle assertions are made against the calculators themselves rather than
 * against literals. That is the acceptance criterion stated as a test: "matches
 * `SessionStatsCalculator`'s output exactly" is a claim about two things agreeing, and a
 * hand-copied number would pass while they disagreed.
 *
 * Under Robolectric because the session id is read off the destination, and
 * `SavedStateHandle.toRoute` decodes through a real `Bundle` — the plain JVM stubs refuse to
 * write a `Long` into one. Reading the id the way the app does is worth an Android runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionSummaryViewModelTest {

    private val catalogue = FakeSummaryCatalogue(
        exercises = listOf(BENCH, PUSHDOWN),
        links = CATALOGUE_LINKS,
    )
    private val templates = FakeTemplateWriteStore()

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so a launched write has run by the
        // time the call that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── The figures ────────────────────────────────────────────────────────────────────

    @Test
    fun `the stats are the ones SessionStatsCalculator computes for the session`() = runTest {
        val sets = pushDaySets()
        val state = viewModel(sets = sets).uiState.value

        assertEquals(
            SessionStatsCalculator.calculate(sets, STARTED_AT, FINISHED_AT),
            state.stats,
        )
    }

    @Test
    fun `the window runs from the session's creation to the moment it was closed out`() = runTest {
        val state = viewModel(sets = pushDaySets()).uiState.value

        assertEquals(STARTED_AT, state.header?.startedAt)
        assertEquals(FINISHED_AT, state.header?.finishedAt)
    }

    @Test
    fun `a session never closed out is measured to when it was last running, not to now`() = runTest {
        // A recap whose duration ticked upwards while it was being read would be a static
        // screen with a live number on it.
        val state = viewModel(
            session = finishedSession(completedAt = null),
            sets = pushDaySets(),
        ).uiState.value

        assertEquals(FINISHED_AT, state.header?.finishedAt)
    }

    @Test
    fun `only completed sets count, matching every other figure in the app`() = runTest {
        val abandoned = workoutSet(
            id = NO_SUCH_SET,
            sessionExerciseId = 1,
            setNumber = 5,
            reps = 10,
            weight = 100_000_000,
            isCompleted = false,
        )
        val state = viewModel(sets = pushDaySets() + abandoned).uiState.value

        assertEquals(
            SessionStatsCalculator.calculate(pushDaySets(), STARTED_AT, FINISHED_AT).totalVolume,
            state.stats?.totalVolume,
        )
    }

    @Test
    fun `the muscle breakdown is MuscleVolumeCalculator's, scaled against the hardest worked`() = runTest {
        val sets = pushDaySets()
        val state = viewModel(sets = sets).uiState.value

        val expected = MuscleVolumeCalculator.volumeByMuscle(
            sets = sets,
            sessionExercises = pushDayExercises(),
            linksByExerciseId = CATALOGUE_LINKS,
        )
        assertEquals(expected.keys.toList(), state.muscles.map { it.muscle })
        assertEquals(expected.values.toList(), state.muscles.map { it.volume })
        // The top muscle is the one the rest are read against, so its bar is full.
        assertEquals(1f, state.muscles.first().share)
    }

    @Test
    fun `primary and secondary are split from the exercises that were actually performed`() = runTest {
        val state = viewModel(sets = pushDaySets()).uiState.value

        // The bench presses the chest and assists the triceps; the pushdown makes the triceps
        // a prime mover too, so it appears in the primary half rather than in both.
        assertEquals(listOf(CHEST, TRICEPS), state.primaryMuscles)
        assertTrue(state.secondaryMuscles.isEmpty())
    }

    @Test
    fun `a muscle nothing was completed for is on neither the diagram nor the bars`() = runTest {
        // A squat laid out and never performed. The attribution ignores it, so the diagram
        // must not light the quads up with no bar beside them.
        val state = viewModel(
            exercises = pushDayExercises() + sessionExercise(id = 3, exerciseId = 12, position = 2),
            sets = pushDaySets(),
        ).uiState.value

        assertFalse(state.muscles.any { it.muscle == QUADS })
        assertFalse(state.primaryMuscles.contains(QUADS))
        assertFalse(state.secondaryMuscles.contains(QUADS))
    }

    // ─── The breakdown ──────────────────────────────────────────────────────────────────

    @Test
    fun `the breakdown is in the order the exercises were performed, with their figures`() = runTest {
        val state = viewModel(sets = pushDaySets()).uiState.value

        assertEquals(listOf("Barbell bench press", "Triceps pushdown"), state.exercises.map { it.name })
        val bench = state.exercises.first()
        assertEquals(3, bench.setCount)
        assertEquals(5, bench.lowestReps)
        assertEquals(10, bench.highestReps)
        assertEquals(80_000_000L, bench.topWeight)
    }

    @Test
    fun `an exercise with no completed set is left out, as it is out of the stats`() = runTest {
        // The tile counts `exerciseBreakdown`, so a longer list than the tile would be the
        // screen contradicting itself.
        val state = viewModel(
            exercises = pushDayExercises() + sessionExercise(id = 3, exerciseId = 12, position = 2),
            sets = pushDaySets(),
        ).uiState.value

        assertEquals(state.stats?.exerciseBreakdown?.size, state.exercises.size)
    }

    @Test
    fun `an exercise deleted from the catalogue keeps its row and loses only its name`() = runTest {
        val state = viewModel(
            catalogue = FakeSummaryCatalogue(exercises = listOf(PUSHDOWN), links = CATALOGUE_LINKS),
            sets = pushDaySets(),
        ).uiState.value

        assertEquals(2, state.exercises.size)
        assertNull(state.exercises.first().name)
    }

    // ─── Records ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a record set on one of this session's sets is reported`() = runTest {
        val state = viewModel(
            sets = pushDaySets(),
            records = mapOf(BENCH.id to record(exerciseId = BENCH.id, workoutSetId = 2)),
        ).uiState.value

        assertEquals(1, state.records.size)
        assertEquals("Barbell bench press", state.records.first().exerciseName)
        // And the row that set it is marked, so the card and the list agree.
        assertEquals(PrType.MAX_WEIGHT, state.exercises.first().record?.type)
    }

    @Test
    fun `a record set in some other session is not this session's`() = runTest {
        // The record points at the set it was read off, which is what tells the two apart —
        // an all-time best on the bench is not something this workout achieved.
        val state = viewModel(
            sets = pushDaySets(),
            records = mapOf(BENCH.id to record(exerciseId = BENCH.id, workoutSetId = NO_SUCH_SET)),
        ).uiState.value

        assertTrue(state.records.isEmpty())
        assertNull(state.exercises.first().record)
    }

    @Test
    fun `a session that beat nothing has no records, which is the ordinary case`() = runTest {
        val state = viewModel(sets = pushDaySets()).uiState.value

        assertTrue(state.records.isEmpty())
    }

    // ─── The note ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the note opens seeded with what is stored and saves what was typed`() = runTest {
        val store = FakeSummarySessionStore(
            session = finishedSession(notes = "Felt strong"),
            exercises = pushDayExercises(),
            sets = pushDaySets(),
        )
        val viewModel = viewModel(store = store)

        viewModel.onEvent(SessionSummaryEvent.NoteEditRequested)
        assertEquals("Felt strong", viewModel.uiState.value.notesDraft)

        viewModel.onEvent(SessionSummaryEvent.NoteChanged("Felt strong, bumped to 80"))
        viewModel.onEvent(SessionSummaryEvent.NoteSaved)

        assertEquals("Felt strong, bumped to 80", store.stored(SESSION_ID)?.notes)
        // Closed on success, and drawn from the re-read rather than from a local copy.
        assertNull(viewModel.uiState.value.notesDraft)
    }

    @Test
    fun `an emptied note clears the column rather than storing whitespace`() = runTest {
        val store = FakeSummarySessionStore(
            session = finishedSession(notes = "Felt strong"),
            exercises = pushDayExercises(),
            sets = pushDaySets(),
        )
        val viewModel = viewModel(store = store)

        viewModel.onEvent(SessionSummaryEvent.NoteEditRequested)
        viewModel.onEvent(SessionSummaryEvent.NoteChanged("   "))
        viewModel.onEvent(SessionSummaryEvent.NoteSaved)

        assertNull(store.stored(SESSION_ID)?.notes)
    }

    @Test
    fun `a note that could not be written leaves the field open with the text in it`() = runTest {
        val store = FakeSummarySessionStore(
            session = finishedSession(),
            exercises = pushDayExercises(),
            sets = pushDaySets(),
        ).apply { writeFailure = IllegalStateException("disk full") }
        val viewModel = viewModel(store = store)

        viewModel.onEvent(SessionSummaryEvent.NoteEditRequested)
        viewModel.onEvent(SessionSummaryEvent.NoteChanged("Felt strong"))
        viewModel.onEvent(SessionSummaryEvent.NoteSaved)

        val state = viewModel.uiState.value
        assertEquals("Felt strong", state.notesDraft)
        assertFalse(state.isSavingNotes)
        assertEquals(SessionSummaryMessage.NOTES_SAVE_FAILED, state.message)
    }

    // ─── Save as template ───────────────────────────────────────────────────────────────

    @Test
    fun `the template is the session's exercises in order, with what was done as defaults`() = runTest {
        val viewModel = viewModel(sets = pushDaySets())

        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateRequested)
        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateConfirmed)

        val saved = templates.stored().single()
        assertEquals("Push Day · Upper", saved.name)
        assertEquals(
            listOf(
                // Three sets, ten reps most often, and the heaviest load.
                TemplateExerciseDraft(
                    exerciseId = BENCH.id,
                    defaultSets = 3,
                    defaultReps = 10,
                    defaultWeight = 80_000_000,
                ),
                TemplateExerciseDraft(
                    exerciseId = PUSHDOWN.id,
                    defaultSets = 2,
                    defaultReps = 12,
                    defaultWeight = 30_000_000,
                ),
            ),
            templates.storedExercises(saved.id),
        )
    }

    @Test
    fun `the dialog opens on the session's own name`() = runTest {
        val viewModel = viewModel(sets = pushDaySets())

        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateRequested)

        assertEquals("Push Day · Upper", viewModel.uiState.value.templateName)
    }

    @Test
    fun `a name already in use is reported and the dialog stays up to fix it`() = runTest {
        templates.seed("Push Day · Upper")
        val viewModel = viewModel(sets = pushDaySets())

        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateRequested)
        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateConfirmed)

        val state = viewModel.uiState.value
        assertEquals(SessionSummaryMessage.TEMPLATE_NAME_TAKEN, state.message)
        // Still open, with the name still in it: the fix is a word, not the whole thing again.
        assertEquals("Push Day · Upper", state.templateName)
        assertEquals(1, templates.stored().size)
    }

    @Test
    fun `a template that could not be written closes the dialog and says so`() = runTest {
        templates.writeFailure = IllegalStateException("disk full")
        val viewModel = viewModel(sets = pushDaySets())

        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateRequested)
        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateConfirmed)

        val state = viewModel.uiState.value
        assertEquals(SessionSummaryMessage.TEMPLATE_SAVE_FAILED, state.message)
        // Nothing a second press would resolve, so the dialog goes rather than sitting there.
        assertNull(state.templateName)
    }

    @Test
    fun `a session with nothing performed has no template to save`() = runTest {
        val viewModel = viewModel(sets = emptyList())

        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateRequested)
        viewModel.onEvent(SessionSummaryEvent.SaveAsTemplateConfirmed)

        assertFalse(viewModel.uiState.value.canSaveAsTemplate)
        assertTrue(templates.stored().isEmpty())
    }

    // ─── The modes, and the edges ───────────────────────────────────────────────────────

    @Test
    fun `the route argument decides which recap this is`() = runTest {
        assertEquals(
            SessionSummaryMode.JUST_FINISHED,
            viewModel(sets = pushDaySets()).uiState.value.mode,
        )
        assertEquals(
            SessionSummaryMode.HISTORICAL,
            viewModel(sets = pushDaySets(), historical = true).uiState.value.mode,
        )
    }

    @Test
    fun `an id that resolves to nothing reads as missing, not as loading forever`() = runTest {
        val state = viewModel(store = FakeSummarySessionStore()).uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.isMissing)
    }

    /**
     * The ViewModel for one session, with its state already flowing.
     *
     * The id arrives the way it does in the app — decoded off the destination's arguments in
     * the `SavedStateHandle` — rather than as a constructor parameter, so a rename of the
     * route's field is a failure here rather than a blank screen at runtime.
     */
    private fun TestScope.viewModel(
        session: WorkoutSession = finishedSession(),
        exercises: List<SessionExercise> = pushDayExercises(),
        sets: List<WorkoutSet> = pushDaySets(),
        store: FakeSummarySessionStore =
            FakeSummarySessionStore(session = session, exercises = exercises, sets = sets),
        catalogue: FakeSummaryCatalogue = this@SessionSummaryViewModelTest.catalogue,
        records: Map<Long, PersonalRecord> = emptyMap(),
        historical: Boolean = false,
    ): SessionSummaryViewModel {
        val handle = SavedStateHandle(mapOf("sessionId" to SESSION_ID, "historical" to historical))
        val viewModel = SessionSummaryViewModel(
            savedStateHandle = handle,
            sessionRepository = store,
            exerciseRepository = catalogue,
            personalRecordRepository = FakeRecordStore(records),
            createTemplate = CreateTemplateUseCase(templates),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    private companion object {

        /** A set id nothing in the session carries. */
        const val NO_SUCH_SET = 999L
    }
}

/** Bench press then triceps pushdown, in that order. */
private fun pushDayExercises() = listOf(
    sessionExercise(id = 1, exerciseId = BENCH.id, position = 0),
    sessionExercise(id = 2, exerciseId = PUSHDOWN.id, position = 1),
)

/**
 * Three sets of bench and two of pushdowns.
 *
 * The bench's reps are `10, 10, 5` on purpose: the modal count is what a template takes as its
 * default, and a fixture where every set agrees could not tell the mode from the maximum.
 */
private fun pushDaySets() = listOf(
    workoutSet(id = 1, sessionExerciseId = 1, setNumber = 1, reps = 10, weight = 60_000_000),
    workoutSet(id = 2, sessionExerciseId = 1, setNumber = 2, reps = 10, weight = 70_000_000),
    workoutSet(id = 3, sessionExerciseId = 1, setNumber = 3, reps = 5, weight = 80_000_000),
    workoutSet(id = 4, sessionExerciseId = 2, setNumber = 1, reps = 12, weight = 30_000_000),
    workoutSet(id = 5, sessionExerciseId = 2, setNumber = 2, reps = 12, weight = 25_000_000),
)
