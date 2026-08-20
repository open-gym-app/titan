package com.opengym.feature.history.template

import androidx.lifecycle.SavedStateHandle
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.usecase.UpdateTemplateUseCase
import com.opengym.feature.history.library.FakeExerciseRepository
import com.opengym.feature.history.plate.FakeUserPreferencesRepository
import com.opengym.feature.history.sessions.FakeTemplateStore
import com.opengym.feature.history.sessions.namedTemplate
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
import kotlin.time.Duration.Companion.seconds

/**
 * The template editor's ViewModel against in-memory stores.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background first:
 * without a collector the flow never starts and the state stays at its initial value, which
 * would make every assertion here pass for the wrong reason.
 *
 * Under Robolectric because the ViewModel reads its template id off the destination, and
 * `SavedStateHandle.toRoute` decodes through a real `Bundle` — the same reason
 * `ExerciseDetailViewModelTest` gives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TemplateEditorViewModelTest {

    private val exercises = FakeExerciseRepository(exercises = listOf(BENCH, FLY, PUSHDOWN))
    private val preferences = FakeUserPreferencesRepository(
        UserPreferences(unitSystem = UnitSystem.METRIC, defaultRestDuration = 90.seconds),
    )

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so the load has run by the time the
        // constructor returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── Opening one ────────────────────────────────────────────────────────────────────

    @Test
    fun `the plan is seeded from the stored template, in position order`() = runTest {
        val viewModel = viewModel()

        val state = viewModel.uiState.value
        assertEquals("Push Day", state.name)
        assertEquals(TemplateCategory.STRENGTH, state.category)
        assertEquals(
            listOf("Bench press", "Cable fly", "Tricep pushdown"),
            state.exercises.map { it.exercise?.name },
        )
        assertEquals(listOf("4", "3", "3"), state.exercises.map { it.sets })
        // Milligrams formatted back into the unit the field is typed in.
        assertEquals("80", state.exercises.first().weight)
        assertFalse(state.isLoading)
        assertFalse(state.isDirty)
        assertFalse(state.canSave)
    }

    @Test
    fun `the superset the template was saved with is labelled and joined`() = runTest {
        val viewModel = viewModel()

        val rows = viewModel.uiState.value.exercises
        assertEquals(listOf(null, "A1", "A2"), rows.map { it.supersetLabel })
        assertEquals(listOf(false, true, false), rows.map { it.isLinkedToNext })
    }

    @Test
    fun `an id that resolves to nothing is a dead end rather than an empty editor`() = runTest {
        val viewModel = viewModel(templateId = 404)

        val state = viewModel.uiState.value
        assertTrue(state.isMissing)
        assertFalse(state.isLoading)
    }

    // ─── Editing the plan ───────────────────────────────────────────────────────────────

    @Test
    fun `changing a figure makes the draft dirty and reprices the estimate`() = runTest {
        val viewModel = viewModel()
        val before = viewModel.uiState.value.estimatedDuration

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.SETS, "6"))

        val state = viewModel.uiState.value
        assertEquals("6", state.exercises.first().sets)
        assertTrue(state.isDirty)
        assertTrue(state.canSave)
        assertTrue(state.estimatedDuration > before)
    }

    @Test
    fun `typing a figure back to what it was makes the draft clean again`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.SETS, "6"))
        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.SETS, "4"))

        assertFalse(viewModel.uiState.value.isDirty)
    }

    @Test
    fun `a figure that is not a number is refused on its own field and blocks the save`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.REPS, "8x"))

        val state = viewModel.uiState.value
        assertEquals(setOf(TemplateField.REPS), state.exercises.first().errors)
        assertTrue(state.hasErrors)
        assertFalse(state.canSave)
    }

    @Test
    fun `a blank figure is the template declining to say, not an error`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.WEIGHT, ""))

        val state = viewModel.uiState.value
        assertEquals(emptySet<TemplateField>(), state.exercises.first().errors)
        assertTrue(state.canSave)
    }

    @Test
    fun `removing one member of a superset dissolves the pair`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_FLY))

        val rows = viewModel.uiState.value.exercises
        assertEquals(listOf("Bench press", "Tricep pushdown"), rows.map { it.exercise?.name })
        assertEquals(listOf(null, null), rows.map { it.supersetLabel })
    }

    @Test
    fun `dragging a member away from its partner tears the superset`() = runTest {
        val viewModel = viewModel()

        // The bench dropped between the two members of A.
        viewModel.onEvent(
            TemplateEditorEvent.ExercisesReordered(listOf(KEY_FLY, KEY_BENCH, KEY_PUSHDOWN)),
        )

        val rows = viewModel.uiState.value.exercises
        assertEquals(listOf("Cable fly", "Bench press", "Tricep pushdown"), rows.map { it.exercise?.name })
        assertEquals(listOf(null, null, null), rows.map { it.supersetLabel })
    }

    @Test
    fun `joining two neighbours makes them a superset, and tapping again breaks it`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.SupersetLinked(0))
        assertEquals(
            listOf("A1", "A2", "A3"),
            viewModel.uiState.value.exercises.map { it.supersetLabel },
        )

        viewModel.onEvent(TemplateEditorEvent.SupersetUnlinked(0))
        assertEquals(
            listOf(null, "A1", "A2"),
            viewModel.uiState.value.exercises.map { it.supersetLabel },
        )
    }

    @Test
    fun `picked exercises are appended with no defaults`() = runTest {
        val viewModel = viewModel(
            exercises = listOf(
                TemplateExercise(id = KEY_BENCH, templateId = 1, exerciseId = BENCH.id, position = 0),
            ),
        )

        viewModel.onEvent(TemplateEditorEvent.ExercisesPicked(listOf(FLY.id)))

        val rows = viewModel.uiState.value.exercises
        assertEquals(listOf("Bench press", "Cable fly"), rows.map { it.exercise?.name })
        assertEquals("", rows.last().sets)
        assertTrue(viewModel.uiState.value.isDirty)
    }

    @Test
    fun `an exercise no longer in the catalogue is left out and said out loud`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.ExercisesPicked(listOf(999)))

        val state = viewModel.uiState.value
        assertEquals(3, state.exercises.size)
        assertEquals(TemplateEditorMessage.EXERCISES_UNAVAILABLE, state.message)
    }

    // ─── Naming ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the name dialog changes the draft and writes nothing`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(TemplateEditorEvent.NameEditRequested)
        viewModel.onEvent(TemplateEditorEvent.NameChanged("Push Day · Heavy"))
        viewModel.onEvent(TemplateEditorEvent.CategoryChanged(TemplateCategory.HYPERTROPHY))
        viewModel.onEvent(TemplateEditorEvent.NameConfirmed)

        val state = viewModel.uiState.value
        assertEquals("Push Day · Heavy", state.name)
        assertEquals(TemplateCategory.HYPERTROPHY, state.category)
        assertNull(state.naming)
        assertTrue(state.isDirty)
        assertEquals("Push Day", templates.templates().single { it.id == 1L }.name)
    }

    @Test
    fun `cancelling the name dialog leaves the draft alone`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.NameEditRequested)
        viewModel.onEvent(TemplateEditorEvent.NameChanged("Something else"))
        viewModel.onEvent(TemplateEditorEvent.NameEditCancelled)

        assertEquals("Push Day", viewModel.uiState.value.name)
        assertFalse(viewModel.uiState.value.isDirty)
    }

    // ─── Saving ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `saving writes the whole template and closes the screen`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.SETS, "5"))
        viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_PUSHDOWN))
        viewModel.onEvent(TemplateEditorEvent.SaveRequested)

        val stored = templates.exercisesOf(1)
        assertEquals(listOf(BENCH.id, FLY.id), stored.map(TemplateExercise::exerciseId))
        assertEquals(listOf(0, 1), stored.map(TemplateExercise::position))
        assertEquals(5, stored.first().defaultSets)
        // The pair lost a member, so the group dissolved on the way in.
        assertEquals(listOf(null, null), stored.map(TemplateExercise::supersetGroup))
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `saving a weight writes it back in milligrams`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.WEIGHT, "82.5"))
        viewModel.onEvent(TemplateEditorEvent.SaveRequested)

        assertEquals(82_500_000L, templates.exercisesOf(1).first().defaultWeight)
    }

    @Test
    fun `a name already in use reopens the name dialog with the refusal on it`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(TemplateEditorEvent.NameEditRequested)
        viewModel.onEvent(TemplateEditorEvent.NameChanged("Pull Day"))
        viewModel.onEvent(TemplateEditorEvent.NameConfirmed)
        viewModel.onEvent(TemplateEditorEvent.SaveRequested)

        val naming = viewModel.uiState.value.naming
        assertEquals("Pull Day", naming?.name)
        assertEquals(true, naming?.nameTaken)
        assertFalse(viewModel.uiState.value.isSaved)
        assertEquals("Push Day", templates.templates().single { it.id == 1L }.name)
    }

    @Test
    fun `a template deleted underneath the editor becomes the dead end`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.SETS, "5"))
        templates.delete(1)
        viewModel.onEvent(TemplateEditorEvent.SaveRequested)

        assertTrue(viewModel.uiState.value.isMissing)
        assertFalse(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `a store that cannot be written says so and keeps the draft`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.SETS, "5"))
        templates.writeFailure = IllegalStateException("disk full")
        viewModel.onEvent(TemplateEditorEvent.SaveRequested)

        val state = viewModel.uiState.value
        assertEquals(TemplateEditorMessage.SAVE_FAILED, state.message)
        assertFalse(state.isSaved)
        assertEquals("5", state.exercises.first().sets)
    }

    @Test
    fun `a template edited down to nothing cannot be saved`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_BENCH))
        viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_FLY))
        viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_PUSHDOWN))

        val state = viewModel.uiState.value
        assertTrue(state.exercises.isEmpty())
        assertFalse(state.canSave)
    }

    // ─── Leaving ────────────────────────────────────────────────────────────────────────

    @Test
    fun `backing out of an untouched editor leaves without asking`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(TemplateEditorEvent.BackRequested)

        assertFalse(viewModel.uiState.value.confirmingDiscard)
    }

    @Test
    fun `backing out with edits asks, and discarding leaves them unwritten`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(TemplateEditorEvent.FieldChanged(KEY_BENCH, TemplateField.SETS, "9"))
        viewModel.onEvent(TemplateEditorEvent.BackRequested)
        assertTrue(viewModel.uiState.value.confirmingDiscard)

        viewModel.onEvent(TemplateEditorEvent.DiscardConfirmed)
        assertTrue(viewModel.uiState.value.isSaved)
        assertEquals(4, templates.exercisesOf(1).first().defaultSets)
    }

    /** A push day whose last two exercises are one superset, next to an unrelated template. */
    private fun templateStore(
        exercises: List<TemplateExercise> = pushDayBody(),
    ) = FakeTemplateStore(
        templates = listOf(
            namedTemplate(1, "Push Day", TemplateCategory.STRENGTH),
            namedTemplate(2, "Pull Day"),
        ),
        exercises = exercises,
    )

    private fun pushDayBody() = listOf(
        TemplateExercise(
            id = KEY_BENCH,
            templateId = 1,
            exerciseId = BENCH.id,
            position = 0,
            defaultSets = 4,
            defaultReps = 8,
            defaultWeight = 80_000_000,
        ),
        TemplateExercise(
            id = KEY_FLY,
            templateId = 1,
            exerciseId = FLY.id,
            position = 1,
            supersetGroup = 0,
            defaultSets = 3,
            defaultReps = 12,
        ),
        TemplateExercise(
            id = KEY_PUSHDOWN,
            templateId = 1,
            exerciseId = PUSHDOWN.id,
            position = 2,
            supersetGroup = 0,
            defaultSets = 3,
            defaultReps = 12,
        ),
    )

    /**
     * The ViewModel for one template, with its state already flowing.
     *
     * The id arrives the way it does in the app — decoded off the destination's arguments in the
     * `SavedStateHandle` — rather than as a constructor parameter, so a rename of the route's
     * field is a failure here rather than a blank screen at runtime.
     */
    private fun TestScope.viewModel(
        templateId: Long = 1,
        templates: FakeTemplateStore = templateStore(),
        exercises: List<TemplateExercise>? = null,
    ): TemplateEditorViewModel {
        val store = exercises?.let { templateStore(it) } ?: templates
        val viewModel = TemplateEditorViewModel(
            savedStateHandle = SavedStateHandle(mapOf("templateId" to templateId)),
            templateRepository = store,
            exerciseRepository = this@TemplateEditorViewModelTest.exercises,
            userPreferencesRepository = preferences,
            updateTemplate = UpdateTemplateUseCase(store),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    private companion object {
        val BENCH = Exercise(id = 10, name = "Bench press")
        val FLY = Exercise(id = 11, name = "Cable fly")
        val PUSHDOWN = Exercise(id = 12, name = "Tricep pushdown")

        /** The stored `template_exercise` ids, which are the draft's keys. */
        const val KEY_BENCH = 100L
        const val KEY_FLY = 101L
        const val KEY_PUSHDOWN = 102L
    }
}
