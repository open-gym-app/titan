package com.opengym.feature.history.library

import com.opengym.core.domain.model.EquipmentType
import com.opengym.feature.history.exercise.ExerciseDraft
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The library ViewModel against an in-memory catalogue.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow never starts and the state stays at its initial value,
 * which would make every assertion here pass for the wrong reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseLibraryViewModelTest {

    private val exercises = catalogueRepository()
    private val records = FakePersonalRecordRepository()

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so a launched write has run by the
        // time the call that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        exerciseRepository: FakeExerciseRepository = exercises,
        personalRecordRepository: FakePersonalRecordRepository = records,
    ): ExerciseLibraryViewModel {
        val viewModel = ExerciseLibraryViewModel(exerciseRepository, personalRecordRepository)
        // Unconfined *on the test scheduler*, so the flow is started and has emitted by the
        // time this returns — `backgroundScope`'s own dispatcher would queue the collection
        // behind the assertions.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    // ─── The unfiltered list ────────────────────────────────────────────────────────────

    @Test
    fun `the whole catalogue lists, grouped by prime mover`() = runTest {
        val state = viewModel().uiState.value

        assertFalse(state.isLoading)
        assertEquals(listOf("CHEST", "LATS"), state.sections.map { it.muscleGroup?.name })
        assertEquals(listOf("Bench press", "Cable fly"), state.sections[0].exercises.map { it.exercise.name })
        assertEquals(CATALOGUE.size, state.catalogueSize)
        assertEquals(CATALOGUE.size, state.matchCount)
        assertFalse(state.hasFilters)
    }

    @Test
    fun `the library is loading until the catalogue read comes back, not merely empty`() = runTest {
        // The links, the taxonomy and the records answer while the catalogue is still in
        // flight — the ordinary case, since they are separate queries and the records table
        // is empty on a fresh install.
        exercises.withholdCatalogue()
        val viewModel = viewModel()

        val loading = viewModel.uiState.value
        // Without this the screen draws "the bundled exercise catalogue did not load.
        // Reinstalling the app restores it." over a library that is merely still opening.
        assertTrue(loading.isLoading)
        assertTrue(loading.sections.isEmpty())
        assertEquals(0, loading.catalogueSize)

        exercises.releaseCatalogue()

        val loaded = viewModel.uiState.value
        assertFalse(loaded.isLoading)
        assertEquals(CATALOGUE.size, loaded.catalogueSize)
    }

    @Test
    fun `the filter vocabularies come from the taxonomy and from what the catalogue holds`() = runTest {
        val state = viewModel().uiState.value

        assertEquals(TAXONOMY, state.muscleGroups)
        assertEquals(
            listOf(EquipmentType.BARBELL, EquipmentType.BODYWEIGHT, EquipmentType.CABLE),
            state.equipmentTypes,
        )
    }

    @Test
    fun `a record reaches the row it belongs to and no other`() = runTest {
        val viewModel = viewModel(
            personalRecordRepository = FakePersonalRecordRepository(mapOf(BENCH.id to BENCH_RECORD)),
        )

        val rows = viewModel.uiState.value.sections.flatMap { it.exercises }

        assertEquals(BENCH_RECORD, rows.first { it.exercise.id == BENCH.id }.record)
        assertNull(rows.first { it.exercise.id == FLY.id }.record)
    }

    // ─── Search and filters ─────────────────────────────────────────────────────────────

    @Test
    fun `the search narrows the list`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.QueryChanged("fly"))

        val state = viewModel.uiState.value
        assertEquals(listOf("Cable fly"), state.sections.flatMap { it.exercises }.map { it.exercise.name })
        assertEquals(1, state.matchCount)
        // The catalogue's size is what it holds, not what matches — it is the denominator.
        assertEquals(CATALOGUE.size, state.catalogueSize)
        assertTrue(state.hasFilters)
    }

    @Test
    fun `the muscle filter matches secondary movers`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.MuscleFilterSelected(TRICEPS.id))

        // The bench press works triceps without being a triceps exercise — and it still
        // appears under CHEST, because that is what it trains most.
        val state = viewModel.uiState.value
        assertEquals(listOf("CHEST"), state.sections.map { it.muscleGroup?.name })
        assertEquals(listOf("Bench press"), state.sections.single().exercises.map { it.exercise.name })
    }

    @Test
    fun `the equipment filter admits several kinds at once`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.BARBELL))
        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.CABLE))

        assertEquals(2, viewModel.uiState.value.matchCount)
    }

    @Test
    fun `toggling an equipment chip twice clears it`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.BARBELL))
        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.BARBELL))

        assertEquals(emptySet<EquipmentType>(), viewModel.uiState.value.selectedEquipment)
        assertEquals(CATALOGUE.size, viewModel.uiState.value.matchCount)
    }

    @Test
    fun `search, muscle and equipment combine as an intersection`() = runTest {
        val viewModel = viewModel()

        // Each filter alone admits the bench press; together they exclude it, because it is
        // not a cable exercise.
        viewModel.onEvent(ExerciseLibraryEvent.QueryChanged("e"))
        viewModel.onEvent(ExerciseLibraryEvent.MuscleFilterSelected(CHEST.id))
        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.CABLE))

        assertEquals(
            listOf("Cable fly"),
            viewModel.uiState.value.sections.flatMap { it.exercises }.map { it.exercise.name },
        )
    }

    @Test
    fun `an intersection that matches nothing leaves no sections`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.MuscleFilterSelected(LATS.id))
        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.CABLE))

        val state = viewModel.uiState.value
        assertEquals(emptyList<ExerciseSection>(), state.sections)
        assertEquals(0, state.matchCount)
        // Which is what tells the screen to offer a way out rather than to say the library
        // is empty.
        assertTrue(state.hasFilters)
    }

    @Test
    fun `the no-results way out drops all three at once`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.QueryChanged("fly"))
        viewModel.onEvent(ExerciseLibraryEvent.MuscleFilterSelected(CHEST.id))
        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.CABLE))

        viewModel.onEvent(ExerciseLibraryEvent.SearchAndFiltersCleared)

        val state = viewModel.uiState.value
        assertFalse(state.hasFilters)
        assertEquals(CATALOGUE.size, state.matchCount)
    }

    @Test
    fun `the filter sheet's clear drops the chips and keeps the search`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.QueryChanged("fly"))
        viewModel.onEvent(ExerciseLibraryEvent.MuscleFilterSelected(CHEST.id))
        viewModel.onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(EquipmentType.CABLE))

        viewModel.onEvent(ExerciseLibraryEvent.FiltersReset)

        // The sheet does not show the search box, so it does not get to empty it — the user
        // would close it to find their typing gone with nothing on screen saying why.
        val state = viewModel.uiState.value
        assertFalse(state.hasChipFilters)
        assertEquals("fly", state.query)
        assertTrue(state.hasFilters)
    }

    // ─── Selection ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the selection keeps the order it was picked in`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(PULL_UP.id))
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id))

        // Not the order the library lists them in: this becomes the session's exercise order.
        assertEquals(listOf(PULL_UP.id, BENCH.id), viewModel.uiState.value.selection)
    }

    @Test
    fun `tapping a selected exercise again removes it`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id))
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id))

        assertEquals(emptyList<Long>(), viewModel.uiState.value.selection)
    }

    @Test
    fun `an exercise added from its detail screen joins the selection without toggling it`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id))
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseSelected(PULL_UP.id))
        // The case a toggle would get wrong: ticked in the list, opened to be checked, and
        // then *added* from the detail screen. A toggle would take it back out.
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseSelected(BENCH.id))

        assertEquals(listOf(BENCH.id, PULL_UP.id), viewModel.uiState.value.selection)
    }

    @Test
    fun `an exclusive pick moves the choice rather than adding to it`() = runTest {
        val viewModel = viewModel()

        // The swap on a session card: the slot holds one exercise, so the second tap replaces
        // the first rather than leaving an answer behind for the screen to trim.
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id, exclusive = true))
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(PULL_UP.id, exclusive = true))

        assertEquals(listOf(PULL_UP.id), viewModel.uiState.value.selection)
    }

    @Test
    fun `unticking the exclusive pick empties the selection`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id, exclusive = true))
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id, exclusive = true))

        // How somebody backs out of a swap without leaving the screen. A radio group could
        // not express it, which is why the row stays a toggle.
        assertEquals(emptyList<Long>(), viewModel.uiState.value.selection)
    }

    @Test
    fun `an exclusive add from the detail screen replaces the pick too`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id, exclusive = true))
        // Opened to be checked, then added from the detail screen — the same question the
        // picker was opened with, so the same one answer.
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseSelected(PULL_UP.id, exclusive = true))

        assertEquals(listOf(PULL_UP.id), viewModel.uiState.value.selection)
    }

    @Test
    fun `a selection survives the filters that hid it`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.ExerciseToggled(BENCH.id))

        viewModel.onEvent(ExerciseLibraryEvent.QueryChanged("pull"))

        // Searching for the next exercise must not silently drop the one already picked.
        assertEquals(listOf(BENCH.id), viewModel.uiState.value.selection)
    }

    // ─── Creating a custom exercise ─────────────────────────────────────────────────────

    @Test
    fun `the form opens empty and closes on request`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        assertEquals(ExerciseDraft(), viewModel.uiState.value.draft)

        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseDismissed)
        assertNull(viewModel.uiState.value.draft)
    }

    @Test
    fun `a created exercise joins the list and its search results`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        viewModel.onEvent(
            ExerciseLibraryEvent.NewExerciseChanged(
                ExerciseDraft(
                    name = "Hammer incline press",
                    equipment = EquipmentType.MACHINE,
                    primaryMuscleGroupId = CHEST.id,
                ),
            ),
        )

        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseSubmitted)

        val state = viewModel.uiState.value
        assertNull(state.draft)
        assertEquals(ExerciseLibraryMessage.EXERCISE_CREATED, state.message)
        assertEquals(CATALOGUE.size + 1, state.catalogueSize)
        val chest = state.sections.single { it.muscleGroup?.name == "CHEST" }
        assertTrue(chest.exercises.any { it.exercise.name == "Hammer incline press" })
        assertTrue(chest.exercises.single { it.exercise.name == "Hammer incline press" }.exercise.isCustom)
    }

    @Test
    fun `a created exercise is findable by search`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        viewModel.onEvent(
            ExerciseLibraryEvent.NewExerciseChanged(
                ExerciseDraft(name = "Zercher squat", primaryMuscleGroupId = CHEST.id),
            ),
        )
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseSubmitted)

        viewModel.onEvent(ExerciseLibraryEvent.QueryChanged("zercher"))

        assertEquals(1, viewModel.uiState.value.matchCount)
    }

    @Test
    fun `a name the catalogue already holds is flagged before the save is attempted`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)

        viewModel.onEvent(
            ExerciseLibraryEvent.NewExerciseChanged(
                ExerciseDraft(
                    name = "bench press",
                    equipment = EquipmentType.BARBELL,
                    primaryMuscleGroupId = CHEST.id,
                ),
            ),
        )

        // Case-insensitively: "bench press" beside "Bench press" is a duplicate to anyone
        // reading the list, whatever the index thinks.
        val draft = viewModel.uiState.value.draft
        assertNotNull(draft)
        assertTrue(draft!!.isDuplicateName)
        assertFalse(draft.canSave)
    }

    @Test
    fun `the same name on different equipment is not a duplicate`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)

        viewModel.onEvent(
            ExerciseLibraryEvent.NewExerciseChanged(
                ExerciseDraft(
                    name = "Bench press",
                    equipment = EquipmentType.DUMBBELL,
                    primaryMuscleGroupId = CHEST.id,
                ),
            ),
        )

        assertFalse(viewModel.uiState.value.draft!!.isDuplicateName)
    }

    @Test
    fun `an incomplete draft is not written`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseChanged(ExerciseDraft(name = "No muscle")))

        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseSubmitted)

        assertEquals(CATALOGUE.size, viewModel.uiState.value.catalogueSize)
        assertNotNull(viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `a failed write keeps the form up with what was typed still in it`() = runTest {
        exercises.insertFailure = IllegalStateException("disk full")
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        viewModel.onEvent(
            ExerciseLibraryEvent.NewExerciseChanged(
                ExerciseDraft(name = "Zercher squat", primaryMuscleGroupId = CHEST.id),
            ),
        )

        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseSubmitted)

        val state = viewModel.uiState.value
        val draft = state.draft!!
        assertEquals("Zercher squat", draft.name)
        // Said on the draft, not as a snackbar: the sheet covers the snackbar's host, so a
        // message raised there would play out and be marked shown behind it.
        assertTrue(draft.saveFailed)
        assertNull(state.message)
        // Not left spinning: the user has to be able to try again.
        assertFalse(draft.isSaving)
    }

    @Test
    fun `editing after a failed write clears the failure`() = runTest {
        exercises.insertFailure = IllegalStateException("disk full")
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        val draft = ExerciseDraft(name = "Zercher squat", primaryMuscleGroupId = CHEST.id)
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseChanged(draft))
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseSubmitted)
        assertTrue(viewModel.uiState.value.draft!!.saveFailed)

        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseChanged(draft.copy(name = "Zercher squats")))

        assertFalse(viewModel.uiState.value.draft!!.saveFailed)
    }

    @Test
    fun `a form dismissed while the write is in flight does not come back when it fails`() = runTest {
        val gate = CompletableDeferred<Unit>()
        exercises.insertGate = gate
        exercises.insertFailure = IllegalStateException("disk full")
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        viewModel.onEvent(
            ExerciseLibraryEvent.NewExerciseChanged(
                ExerciseDraft(name = "Zercher squat", primaryMuscleGroupId = CHEST.id),
            ),
        )
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseSubmitted)

        // The sheet is swiped away mid-write — `onDismissRequest` is not gated on `isSaving`.
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseDismissed)
        gate.complete(Unit)

        val state = viewModel.uiState.value
        // The form the user threw away stays thrown away...
        assertNull(state.draft)
        // ...and with no sheet in the way, the snackbar is where the failure gets said.
        assertEquals(ExerciseLibraryMessage.SAVE_FAILED, state.message)
    }

    @Test
    fun `a message is shown once and then forgotten`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseRequested)
        viewModel.onEvent(
            ExerciseLibraryEvent.NewExerciseChanged(
                ExerciseDraft(name = "Zercher squat", primaryMuscleGroupId = CHEST.id),
            ),
        )
        viewModel.onEvent(ExerciseLibraryEvent.NewExerciseSubmitted)

        viewModel.onEvent(ExerciseLibraryEvent.MessageShown)

        assertNull(viewModel.uiState.value.message)
    }
}
