package com.opengym.feature.history.detail

import androidx.lifecycle.SavedStateHandle
import com.opengym.core.domain.model.EquipmentType
import com.opengym.feature.history.exercise.ExerciseDraft
import com.opengym.feature.history.library.CHEST
import com.opengym.feature.history.library.FakeExerciseRepository
import com.opengym.feature.history.library.LATS
import com.opengym.feature.history.library.TAXONOMY
import com.opengym.feature.history.library.TRICEPS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The detail ViewModel against an in-memory catalogue.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow never starts and the state stays at its initial value,
 * which would make every assertion here pass for the wrong reason.
 *
 * Under Robolectric, unlike the library's ViewModel test, because this one reads its exercise
 * id off the destination — and `SavedStateHandle.toRoute` decodes through a real `Bundle`,
 * which the plain JVM stubs refuse to write a `Long` into. Reading the id the way the app
 * does is worth an Android runtime; a constructor parameter would test a seam that does not
 * exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExerciseDetailViewModelTest {

    private val repository = FakeExerciseRepository(
        exercises = listOf(DETAIL_BENCH, DETAIL_CUSTOM),
        links = mapOf(
            DETAIL_BENCH.id to DETAIL_BENCH_LINKS,
            DETAIL_CUSTOM.id to DETAIL_CUSTOM_LINKS,
        ),
        taxonomy = TAXONOMY,
    )

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so a launched write has run by the
        // time the call that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    /**
     * The ViewModel for one exercise, with its state already flowing.
     *
     * The id arrives the way it does in the app — decoded off the destination's arguments in
     * the `SavedStateHandle` — rather than as a constructor parameter, so a rename of the
     * route's field is a failure here rather than a blank screen at runtime.
     */
    private fun TestScope.viewModel(exerciseId: Long): ExerciseDetailViewModel {
        val handle = SavedStateHandle(mapOf("exerciseId" to exerciseId, "addToSession" to false))
        val viewModel = ExerciseDetailViewModel(handle, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    // ─── What loads ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the exercise and its links load, primary movers first`() = runTest {
        val state = viewModel(DETAIL_BENCH.id).uiState.value

        assertFalse(state.isLoading)
        assertEquals(DETAIL_BENCH, state.exercise)
        assertEquals(listOf(CHEST), state.primaryMuscles)
        assertEquals(listOf(TRICEPS), state.secondaryMuscles)
    }

    @Test
    fun `the how-to is split into steps, with blank lines dropped`() = runTest {
        val state = viewModel(DETAIL_BENCH.id).uiState.value

        assertEquals(
            listOf("Unrack the bar.", "Lower it to the chest.", "Press back to lockout."),
            state.instructionSteps,
        )
    }

    @Test
    fun `an id that is not in the catalogue reads as missing, not as loading forever`() = runTest {
        val state = viewModel(NO_SUCH_ID).uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.isMissing)
    }

    @Test
    fun `only a custom exercise may be rewritten`() = runTest {
        assertFalse(viewModel(DETAIL_BENCH.id).uiState.value.canEdit)
        assertTrue(viewModel(DETAIL_CUSTOM.id).uiState.value.canEdit)
    }

    // ─── The note ───────────────────────────────────────────────────────────────────────

    @Test
    fun `editing the note opens it seeded with what is stored`() = runTest {
        val viewModel = viewModel(DETAIL_BENCH.id)

        viewModel.onEvent(ExerciseDetailEvent.NoteEditRequested)

        assertEquals(DETAIL_BENCH.notes, viewModel.uiState.value.notesDraft)
    }

    @Test
    fun `a saved note persists and closes the field`() = runTest {
        val viewModel = viewModel(DETAIL_BENCH.id)

        viewModel.onEvent(ExerciseDetailEvent.NoteEditRequested)
        viewModel.onEvent(ExerciseDetailEvent.NoteChanged("  Seat on 4. Elbows in.  "))
        viewModel.onEvent(ExerciseDetailEvent.NoteSaved)

        // Read back through the repository, not off the state: the point of the write is that
        // it landed, and the state redraws from the store's own re-emission.
        val stored = repository.getById(DETAIL_BENCH.id).first()
        assertEquals("Seat on 4. Elbows in.", stored?.notes)
        assertNull(viewModel.uiState.value.notesDraft)
    }

    @Test
    fun `a blank note clears the column rather than storing whitespace`() = runTest {
        val viewModel = viewModel(DETAIL_BENCH.id)

        viewModel.onEvent(ExerciseDetailEvent.NoteEditRequested)
        viewModel.onEvent(ExerciseDetailEvent.NoteChanged("   "))
        viewModel.onEvent(ExerciseDetailEvent.NoteSaved)

        assertNull(repository.getById(DETAIL_BENCH.id).first()?.notes)
    }

    @Test
    fun `a note the store refuses keeps the field open with the text in it`() = runTest {
        val viewModel = viewModel(DETAIL_BENCH.id)
        repository.updateFailure = IllegalStateException("disk full")

        viewModel.onEvent(ExerciseDetailEvent.NoteEditRequested)
        viewModel.onEvent(ExerciseDetailEvent.NoteChanged("Elbows in."))
        viewModel.onEvent(ExerciseDetailEvent.NoteSaved)

        val state = viewModel.uiState.value
        assertEquals("Elbows in.", state.notesDraft)
        assertFalse(state.isSavingNotes)
        assertEquals(ExerciseDetailMessage.NOTES_SAVE_FAILED, state.message)
    }

    @Test
    fun `cancelling the note leaves what is stored alone`() = runTest {
        val viewModel = viewModel(DETAIL_BENCH.id)

        viewModel.onEvent(ExerciseDetailEvent.NoteEditRequested)
        viewModel.onEvent(ExerciseDetailEvent.NoteChanged("Discarded."))
        viewModel.onEvent(ExerciseDetailEvent.NoteEditCancelled)

        assertNull(viewModel.uiState.value.notesDraft)
        assertEquals(DETAIL_BENCH.notes, repository.getById(DETAIL_BENCH.id).first()?.notes)
    }

    // ─── The whole-exercise edit ────────────────────────────────────────────────────────

    @Test
    fun `the edit form opens on what the exercise currently is`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)

        viewModel.onEvent(ExerciseDetailEvent.EditRequested)

        val draft = requireNotNull(viewModel.uiState.value.editDraft)
        assertEquals(DETAIL_CUSTOM.id, draft.id)
        assertEquals(DETAIL_CUSTOM.name, draft.name)
        assertEquals(EquipmentType.MACHINE, draft.equipment)
        assertEquals(LATS.id, draft.primaryMuscleGroupId)
        assertFalse(draft.isNew)
    }

    @Test
    fun `negative - a seeded exercise cannot be opened for editing`() = runTest {
        val viewModel = viewModel(DETAIL_BENCH.id)

        viewModel.onEvent(ExerciseDetailEvent.EditRequested)

        assertNull(viewModel.uiState.value.editDraft)
    }

    @Test
    fun `a saved edit rewrites the row and replaces its muscle links`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)

        viewModel.onEvent(ExerciseDetailEvent.EditRequested)
        viewModel.onEvent(
            ExerciseDetailEvent.EditChanged(
                viewModel.uiState.value.editDraft!!.copy(
                    name = "Hammer incline press",
                    primaryMuscleGroupId = CHEST.id,
                    secondaryMuscleGroupIds = setOf(TRICEPS.id),
                ),
            ),
        )
        viewModel.onEvent(ExerciseDetailEvent.EditSubmitted)

        val stored = repository.getById(DETAIL_CUSTOM.id).first()
        assertEquals("Hammer incline press", stored?.name)
        // Still the user's own, and still a machine exercise: the form edits four fields and
        // copies the rest of the row rather than rebuilding it.
        assertTrue(stored?.isCustom == true)

        val links = repository.getMuscleGroupLinks(DETAIL_CUSTOM.id).first()
        assertEquals(listOf(CHEST, TRICEPS), links.map { it.muscleGroup })
        assertEquals(listOf(true, false), links.map { it.isPrimary })
        assertNull(viewModel.uiState.value.editDraft)
        assertEquals(ExerciseDetailMessage.EXERCISE_UPDATED, viewModel.uiState.value.message)
    }

    @Test
    fun `an exercise is not a duplicate of itself`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)

        // Nothing changed but the note — which is the ordinary edit, and the one a duplicate
        // check that ignored the draft's own id would refuse.
        viewModel.onEvent(ExerciseDetailEvent.EditRequested)
        viewModel.onEvent(
            ExerciseDetailEvent.EditChanged(viewModel.uiState.value.editDraft!!.copy(notes = "Seat 2")),
        )

        val draft = viewModel.uiState.value.editDraft!!
        assertFalse(draft.isDuplicateName)
        assertTrue(draft.canSave)
    }

    @Test
    fun `a name another exercise already holds is flagged before the write`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)

        viewModel.onEvent(ExerciseDetailEvent.EditRequested)
        viewModel.onEvent(
            ExerciseDetailEvent.EditChanged(
                viewModel.uiState.value.editDraft!!.copy(
                    name = DETAIL_BENCH.name,
                    equipment = EquipmentType.BARBELL,
                ),
            ),
        )

        val draft = viewModel.uiState.value.editDraft!!
        assertTrue(draft.isDuplicateName)
        assertFalse(draft.canSave)
    }

    @Test
    fun `an edit the store refuses keeps the form up and says so on it`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)
        repository.updateFailure = IllegalStateException("unique index")

        viewModel.onEvent(ExerciseDetailEvent.EditRequested)
        viewModel.onEvent(
            ExerciseDetailEvent.EditChanged(viewModel.uiState.value.editDraft!!.copy(name = "Renamed")),
        )
        viewModel.onEvent(ExerciseDetailEvent.EditSubmitted)

        val draft = viewModel.uiState.value.editDraft
        assertEquals("Renamed", draft?.name)
        assertTrue(draft?.saveFailed == true)
        assertFalse(draft?.isSaving == true)
        // On the form, not in a snackbar the sheet would be covering.
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `negative - an incomplete draft is not written`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)

        viewModel.onEvent(ExerciseDetailEvent.EditRequested)
        viewModel.onEvent(ExerciseDetailEvent.EditChanged(ExerciseDraft(id = DETAIL_CUSTOM.id, name = "")))
        viewModel.onEvent(ExerciseDetailEvent.EditSubmitted)

        assertEquals(DETAIL_CUSTOM.name, repository.getById(DETAIL_CUSTOM.id).first()?.name)
    }

    // ─── Deleting ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the delete action confirms before it deletes anything`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)

        viewModel.onEvent(ExerciseDetailEvent.DeleteRequested)

        assertTrue(viewModel.uiState.value.confirmingDelete)
        assertNotNull(repository.getById(DETAIL_CUSTOM.id).first())
    }

    @Test
    fun `a confirmed delete removes the exercise and says the screen should leave`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)

        viewModel.onEvent(ExerciseDetailEvent.DeleteRequested)
        viewModel.onEvent(ExerciseDetailEvent.DeleteConfirmed)

        assertNull(repository.getById(DETAIL_CUSTOM.id).first())
        assertTrue(viewModel.uiState.value.isDeleted)
        // Not the "no longer exists" page on the way out, which is the whole reason the flag
        // exists rather than the row's own disappearance standing for it.
        assertFalse(viewModel.uiState.value.isMissing)
    }

    @Test
    fun `negative - a seeded exercise cannot be deleted`() = runTest {
        val viewModel = viewModel(DETAIL_BENCH.id)

        viewModel.onEvent(ExerciseDetailEvent.DeleteRequested)
        viewModel.onEvent(ExerciseDetailEvent.DeleteConfirmed)

        assertNotNull(repository.getById(DETAIL_BENCH.id).first())
        assertFalse(viewModel.uiState.value.isDeleted)
    }

    @Test
    fun `a delete the schema refuses closes the dialog and says so`() = runTest {
        val viewModel = viewModel(DETAIL_CUSTOM.id)
        repository.deleteFailure = IllegalStateException("foreign key")

        viewModel.onEvent(ExerciseDetailEvent.DeleteRequested)
        viewModel.onEvent(ExerciseDetailEvent.DeleteConfirmed)

        val state = viewModel.uiState.value
        assertFalse(state.isDeleted)
        assertFalse(state.confirmingDelete)
        assertEquals(ExerciseDetailMessage.DELETE_FAILED, state.message)
    }

    private companion object {
        /** Above every fixture's id, so it resolves to nothing. */
        const val NO_SUCH_ID = 9_999L
    }
}
