package com.titan.feature.history.template

import androidx.lifecycle.SavedStateHandle
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.usecase.CreateTemplateUseCase
import com.titan.core.domain.usecase.UpdateTemplateUseCase
import com.titan.feature.history.afterProcessDeath
import com.titan.feature.history.library.FakeExerciseRepository
import com.titan.feature.history.navigation.NEW_TEMPLATE
import com.titan.feature.history.plate.FakeUserPreferencesRepository
import com.titan.feature.history.sessions.FakeTemplateStore
import com.titan.feature.history.sessions.namedTemplate
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.minutes
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

    private val exercises = FakeExerciseRepository(exercises = listOf(BENCH, FLY, PUSHDOWN, PLANK, RUN, SWIM))
    private val drafts = FakeTemplateDraftRepository()
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
    fun `the plan is seeded from the stored template, in position order`() =
        runTest {
            val viewModel = viewModel()

            val state = viewModel.uiState.value
            assertEquals("Push Day", state.name)
            assertEquals(TemplateCategory.STRENGTH, state.category)
            assertEquals(
                listOf("Bench press", "Cable fly", "Tricep pushdown"),
                state.exercises.map { it.exercise?.name },
            )
            assertEquals(listOf(4, 3, 3), state.exercises.map { it.sets.size })
            // Milligrams formatted back into the unit the field is typed in.
            assertEquals("80", state.exercises.first().sets.first().weight)
            assertFalse(state.isLoading)
            assertFalse(state.isDirty)
            assertFalse(state.canSave)
        }

    @Test
    fun `the superset the template was saved with is labelled and joined`() =
        runTest {
            val viewModel = viewModel()

            val rows = viewModel.uiState.value.exercises
            assertEquals(listOf(null, "A1", "A2"), rows.map { it.supersetLabel })
            assertEquals(listOf(false, true, false), rows.map { it.isLinkedToNext })
        }

    @Test
    fun `an id that resolves to nothing is a dead end rather than an empty editor`() =
        runTest {
            val viewModel = viewModel(templateId = 404)

            val state = viewModel.uiState.value
            assertTrue(state.isMissing)
            assertFalse(state.isLoading)
        }

    // ─── Building one ───────────────────────────────────────────────────────────────────

    @Test
    fun `opening on no template at all is an empty editor asking for a name`() =
        runTest {
            val viewModel = viewModel(templateId = NEW_TEMPLATE)

            val state = viewModel.uiState.value
            assertTrue(state.isNew)
            assertFalse(state.isLoading)
            // Emphatically not the dead end: nothing is missing, there is simply nothing yet.
            assertFalse(state.isMissing)
            assertEquals("", state.name)
            assertEquals(emptyList<TemplateExerciseRow>(), state.exercises)
            // The name is the one thing the use case will not do without, and the row that opens
            // this dialog is blank at this point.
            assertEquals(TemplateNamingDraft(name = ""), state.naming)
            // Nothing typed is nothing to lose: backing straight out has to be free.
            assertFalse(state.isDirty)
        }

    @Test
    fun `a new template is inserted rather than rewriting anything`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templateId = NEW_TEMPLATE, templates = templates)

            viewModel.onEvent(TemplateEditorEvent.NameChanged("Pull Day · Back"))
            viewModel.onEvent(TemplateEditorEvent.CategoryChanged(TemplateCategory.HYPERTROPHY))
            viewModel.onEvent(TemplateEditorEvent.NameConfirmed)
            viewModel.pick(listOf(FLY.id, PUSHDOWN.id))
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            assertTrue(viewModel.uiState.value.isSaved)
            val written = templates.templates().single { it.name == "Pull Day · Back" }
            assertEquals(TemplateCategory.HYPERTROPHY, written.category)
            assertEquals(
                listOf(FLY.id, PUSHDOWN.id),
                templates.exercisesOf(written.id).map(TemplateExercise::exerciseId),
            )
            // The template the editor was *not* opened on is untouched, which is what says this
            // went through the insert and not the rewrite.
            assertEquals(3, templates.exercisesOf(1).size)
        }

    @Test
    fun `a new template cannot be saved until it has both a name and an exercise`() =
        runTest {
            val viewModel = viewModel(templateId = NEW_TEMPLATE)

            viewModel.pick(listOf(FLY.id))
            // Dirty, and still refused: `CreateTemplateUseCase` would answer `EmptyName`, and a live
            // SAVE whose only outcome is a refusal is a button that lies.
            assertTrue(viewModel.uiState.value.isDirty)
            assertFalse(viewModel.uiState.value.canSave)

            viewModel.onEvent(TemplateEditorEvent.NameChanged("Pull Day"))
            viewModel.onEvent(TemplateEditorEvent.NameConfirmed)
            assertTrue(viewModel.uiState.value.canSave)
        }

    @Test
    fun `a new template named after an existing one is refused in the dialog`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templateId = NEW_TEMPLATE, templates = templates)

            viewModel.onEvent(TemplateEditorEvent.NameChanged("Push Day"))
            viewModel.onEvent(TemplateEditorEvent.NameConfirmed)
            viewModel.pick(listOf(FLY.id))
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            assertEquals(true, viewModel.uiState.value.naming?.nameTaken)
            assertFalse(viewModel.uiState.value.isSaved)
            // Nothing was inserted, so the tab still holds the two it started with.
            assertEquals(2, templates.templates().size)
        }

    // ─── Editing the plan ───────────────────────────────────────────────────────────────

    @Test
    fun `adding a set makes the draft dirty and reprices the estimate`() =
        runTest {
            val viewModel = viewModel()
            val before = viewModel.uiState.value.estimatedDuration

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))

            val state = viewModel.uiState.value
            assertEquals(5, state.exercises.first().sets.size)
            assertTrue(state.isDirty)
            assertTrue(state.canSave)
            assertTrue(state.estimatedDuration > before)
        }

    @Test
    fun `an added set copies the one above it rather than starting blank`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(
                TemplateEditorEvent.SetTypeChanged(KEY_BENCH, viewModel.setKeyAt(0, 3), SetType.DROP_SET),
            )
            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))

            val added = viewModel.uiState.value.exercises.first().sets.last()
            assertEquals("80", added.weight)
            assertEquals("8", added.reps)
            assertEquals(SetType.DROP_SET, added.setType)
        }

    @Test
    fun `the first set of an exercise has nothing to copy and starts blank`() =
        runTest {
            val viewModel = viewModel()
            val bench = viewModel.setKeyAt(0, 0)
            repeat(4) {
                viewModel.onEvent(TemplateEditorEvent.SetRemoved(KEY_BENCH, viewModel.setKeyAt(0, 0)))
            }
            assertTrue(viewModel.uiState.value.exercises.first().sets.isEmpty())
            assertEquals(bench, bench)

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))

            val added = viewModel.uiState.value.exercises.first().sets.single()
            assertEquals("", added.weight)
            assertEquals("", added.reps)
            assertEquals(SetType.NORMAL, added.setType)
        }

    @Test
    fun `removing a set leaves the others alone`() =
        runTest {
            val viewModel = viewModel()
            val second = viewModel.setKeyAt(0, 1)

            viewModel.onEvent(TemplateEditorEvent.SetRemoved(KEY_BENCH, second))

            val sets = viewModel.uiState.value.exercises.first().sets
            assertEquals(3, sets.size)
            assertFalse(sets.any { it.key == second })
            assertTrue(viewModel.uiState.value.isDirty)
        }

    @Test
    fun `an exercise with every set removed is still a line of the plan`() =
        runTest {
            // "Do this, sets to be decided" is a legitimate template line, so it does not take the
            // exercise out of the plan and does not block the save.
            val viewModel = viewModel()

            repeat(4) {
                viewModel.onEvent(TemplateEditorEvent.SetRemoved(KEY_BENCH, viewModel.setKeyAt(0, 0)))
            }

            val state = viewModel.uiState.value
            assertEquals(3, state.exercises.size)
            assertTrue(state.exercises.first().sets.isEmpty())
            assertTrue(state.canSave)
        }

    @Test
    fun `each set carries its own type, so a plan can ramp and drop`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(
                TemplateEditorEvent.SetTypeChanged(KEY_BENCH, viewModel.setKeyAt(0, 0), SetType.WARMUP),
            )
            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    KEY_BENCH,
                    viewModel.setKeyAt(0, 0),
                    TemplateSetField.WEIGHT,
                    "40",
                ),
            )
            viewModel.onEvent(
                TemplateEditorEvent.SetTypeChanged(KEY_BENCH, viewModel.setKeyAt(0, 3), SetType.DROP_SET),
            )

            val sets = viewModel.uiState.value.exercises.first().sets
            assertEquals(
                listOf(SetType.WARMUP, SetType.NORMAL, SetType.NORMAL, SetType.DROP_SET),
                sets.map { it.setType },
            )
            assertEquals(listOf("40", "80", "80", "80"), sets.map { it.weight })
        }

    @Test
    fun `typing a figure back to what it was makes the draft clean again`() =
        runTest {
            val viewModel = viewModel()
            val first = viewModel.setKeyAt(0, 0)

            viewModel.onEvent(TemplateEditorEvent.SetFieldChanged(KEY_BENCH, first, TemplateSetField.REPS, "6"))
            viewModel.onEvent(TemplateEditorEvent.SetFieldChanged(KEY_BENCH, first, TemplateSetField.REPS, "8"))

            assertFalse(viewModel.uiState.value.isDirty)
        }

    @Test
    fun `a figure that is not a number is refused on its own set and blocks the save`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    KEY_BENCH,
                    viewModel.setKeyAt(0, 1),
                    TemplateSetField.REPS,
                    "8x",
                ),
            )

            val state = viewModel.uiState.value
            // On the set that was typed into, and on no other.
            assertEquals(
                listOf(emptySet(), setOf(TemplateSetField.REPS), emptySet(), emptySet()),
                state.exercises.first().sets.map { it.errors },
            )
            assertTrue(state.hasErrors)
            assertFalse(state.canSave)
        }

    @Test
    fun `a blank figure is the template declining to say, not an error`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(KEY_BENCH, viewModel.setKeyAt(0, 0), TemplateSetField.WEIGHT, ""),
            )

            val state = viewModel.uiState.value
            assertEquals(emptySet<TemplateSetField>(), state.exercises.first().sets.first().errors)
            assertTrue(state.canSave)
        }

    @Test
    fun `removing one member of a superset dissolves the pair`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_FLY))

            val rows = viewModel.uiState.value.exercises
            assertEquals(listOf("Bench press", "Tricep pushdown"), rows.map { it.exercise?.name })
            assertEquals(listOf(null, null), rows.map { it.supersetLabel })
        }

    @Test
    fun `dragging a member away from its partner tears the superset`() =
        runTest {
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
    fun `joining two neighbours makes them a superset, and tapping again breaks it`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(TemplateEditorEvent.SupersetLinked(KEY_BENCH))
            assertEquals(
                listOf("A1", "A2", "A3"),
                viewModel.uiState.value.exercises.map { it.supersetLabel },
            )

            viewModel.onEvent(TemplateEditorEvent.SupersetUnlinked(KEY_BENCH))
            assertEquals(
                listOf(null, "A1", "A2"),
                viewModel.uiState.value.exercises.map { it.supersetLabel },
            )
        }

    @Test
    fun `a join is resolved against the draft's own order, not the screen's`() =
        runTest {
            val viewModel = viewModel()

            // The pushdown pulled to the front. The screen redraws in the new order a frame before
            // the draft does, so an index would name a different pair on each side of that frame —
            // the key names the same exercise on both.
            viewModel.onEvent(
                TemplateEditorEvent.ExercisesReordered(listOf(KEY_PUSHDOWN, KEY_BENCH, KEY_FLY)),
            )
            viewModel.onEvent(TemplateEditorEvent.SupersetLinked(KEY_BENCH))

            val rows = viewModel.uiState.value.exercises
            assertEquals(listOf("Tricep pushdown", "Bench press", "Cable fly"), rows.map { it.exercise?.name })
            // The bench and the fly, which is what the key asked for.
            assertEquals(listOf(null, "A1", "A2"), rows.map { it.supersetLabel })
        }

    @Test
    fun `a join under an exercise the plan no longer holds does nothing`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_BENCH))
            viewModel.onEvent(TemplateEditorEvent.SupersetLinked(KEY_BENCH))

            // The fly and the pushdown are still the one superset they were, and nothing was
            // joined to a row that is not there.
            assertEquals(
                listOf("A1", "A2"),
                viewModel.uiState.value.exercises.map { it.supersetLabel },
            )
        }

    @Test
    fun `picked exercises are appended with one blank set`() =
        runTest {
            // A table to type into, with nothing in it. Three sets of ten would be the screen
            // putting numbers in the user's mouth; no table at all would put a tap in front of the
            // thing they came here to do.
            val viewModel = viewModel(
                exercises = listOf(
                    TemplateExercise(id = KEY_BENCH, templateId = 1, exerciseId = BENCH.id, position = 0),
                ),
            )

            viewModel.pick(listOf(FLY.id))

            val rows = viewModel.uiState.value.exercises
            assertEquals(listOf("Bench press", "Cable fly"), rows.map { it.exercise?.name })
            val added = rows.last().sets.single()
            assertEquals("", added.weight)
            assertEquals("", added.reps)
            assertEquals(SetType.NORMAL, added.setType)
            assertTrue(viewModel.uiState.value.isDirty)
        }

    @Test
    fun `an answer to a picker this editor never opened is refused`() =
        runTest {
            val viewModel = viewModel()

            // An answer with no request behind it — a redelivery of one already consumed, or one
            // belonging to a screen this is not. Appending would put exercises into a plan nobody
            // asked to put them in. See TemplateEditorViewModel.pickRequest.
            viewModel.onEvent(TemplateEditorEvent.ExercisesPicked(listOf(FLY.id)))

            val state = viewModel.uiState.value
            assertEquals(3, state.exercises.size)
            assertFalse(state.isDirty)
            assertEquals(TemplateEditorMessage.EDITOR_RESTARTED, state.message)
        }

    @Test
    fun `an exercise no longer in the catalogue is left out and said out loud`() =
        runTest {
            val viewModel = viewModel()

            viewModel.pick(listOf(999))

            val state = viewModel.uiState.value
            assertEquals(3, state.exercises.size)
            assertEquals(TemplateEditorMessage.EXERCISES_UNAVAILABLE, state.message)
        }

    @Test
    fun `an exercise written from inside the picker is added rather than reported missing`() =
        runTest {
            val viewModel = viewModel()

            // The picker is the library in selecting mode and it can create one, so the answer
            // can name an id that did not exist when this editor read the catalogue. Judged
            // against that read it would be dropped — and the user told the exercise they had
            // just written was no longer in their library.
            val landmine = exercises.insert(Exercise(name = "Landmine press"))
            viewModel.pick(listOf(landmine))

            val state = viewModel.uiState.value
            assertEquals(4, state.exercises.size)
            assertEquals("Landmine press", state.exercises.last().exercise?.name)
            assertNull(state.message)
        }

    @Test
    fun `a swap changes the movement and leaves the slot it sits in alone`() =
        runTest {
            val viewModel = viewModel()

            viewModel.swap(KEY_FLY, PUSHDOWN.id)

            val rows = viewModel.uiState.value.exercises
            // The middle slot, still the middle slot, still the first half of the superset it was in.
            assertEquals(
                listOf("Bench press", "Tricep pushdown", "Tricep pushdown"),
                rows.map { it.exercise?.name },
            )
            assertEquals("A1", rows[1].supersetLabel)
            assertTrue(viewModel.uiState.value.isDirty)
        }

    @Test
    fun `the sets planned for a slot survive the movement in it changing`() =
        runTest {
            val viewModel = viewModel()

            // Three sets of twelve, written against the fly. A lifter swapping one cable movement for
            // another is nearly always keeping the shape and retyping the weight, and there is no undo
            // on this page — see `TemplateEditorViewModel.swapExercise`.
            val before = viewModel.uiState.value.exercises[1].sets

            viewModel.swap(KEY_FLY, PUSHDOWN.id)

            assertEquals(before, viewModel.uiState.value.exercises[1].sets)
        }

    @Test
    fun `a swap answered with an exercise that is gone changes nothing and says so`() =
        runTest {
            val viewModel = viewModel()

            viewModel.swap(KEY_FLY, 999)

            val state = viewModel.uiState.value
            // Not EXERCISES_UNAVAILABLE: nothing was "left out", the plan is exactly as it was.
            assertEquals(TemplateEditorMessage.EXERCISE_UNAVAILABLE, state.message)
            assertEquals("Cable fly", state.exercises[1].exercise?.name)
            assertFalse(state.isDirty)
        }

    @Test
    fun `an add after a backed-out swap appends rather than filling the slot`() =
        runTest {
            val viewModel = viewModel()

            // The picker opened from ⇄, backed out of, then reopened from ADD EXERCISES. The last
            // control tapped is the one the answer belongs to.
            viewModel.onEvent(TemplateEditorEvent.ExerciseSwapRequested(KEY_FLY))
            viewModel.onEvent(TemplateEditorEvent.ExercisesPickClosed)
            viewModel.pick(listOf(PUSHDOWN.id))

            val rows = viewModel.uiState.value.exercises
            assertEquals(4, rows.size)
            assertEquals("Cable fly", rows[1].exercise?.name)
            assertEquals("Tricep pushdown", rows.last().exercise?.name)
        }

    @Test
    fun `an answer to a swap this editor never asked for is refused`() =
        runTest {
            val viewModel = viewModel()

            // The same unrequested answer reached through the other control: the request is read
            // before the slot is, so there is no slot to rewrite.
            viewModel.onEvent(TemplateEditorEvent.ExercisesPicked(listOf(PUSHDOWN.id)))

            val state = viewModel.uiState.value
            assertEquals("Cable fly", state.exercises[1].exercise?.name)
            assertFalse(state.isDirty)
            assertEquals(TemplateEditorMessage.EDITOR_RESTARTED, state.message)
        }

    // ─── Naming ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the name dialog changes the draft and writes nothing`() =
        runTest {
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
    fun `cancelling the name dialog leaves the draft alone`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(TemplateEditorEvent.NameEditRequested)
            viewModel.onEvent(TemplateEditorEvent.NameChanged("Something else"))
            viewModel.onEvent(TemplateEditorEvent.NameEditCancelled)

            assertEquals("Push Day", viewModel.uiState.value.name)
            assertFalse(viewModel.uiState.value.isDirty)
        }

    @Test
    fun `the name dialog cannot be opened or applied over a write in flight`() =
        runTest {
            val templates = templateStore()
            val gate = CompletableDeferred<Unit>()
            templates.writeGate = gate
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)
            assertTrue(viewModel.uiState.value.isSaving)

            // The header is drawn inert while the write is out, but the event has to be refused
            // here too: the save took its copy of the draft before it launched, so a name applied
            // now would be one the store never sees — and the save landing closes the screen over
            // the difference.
            viewModel.onEvent(TemplateEditorEvent.NameEditRequested)
            assertNull(viewModel.uiState.value.naming)

            gate.complete(Unit)
            assertEquals("Push Day", templates.templates().single { it.id == 1L }.name)
            assertTrue(viewModel.uiState.value.isSaved)
        }

    // ─── Saving ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `saving writes the whole template and closes the screen`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))
            viewModel.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_PUSHDOWN))
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            val stored = templates.exercisesOf(1)
            assertEquals(listOf(BENCH.id, FLY.id), stored.map(TemplateExercise::exerciseId))
            assertEquals(listOf(0, 1), stored.map(TemplateExercise::position))
            assertEquals(5, stored.first().sets.size)
            // The pair lost a member, so the group dissolved on the way in.
            assertEquals(listOf(null, null), stored.map(TemplateExercise::supersetGroup))
            assertTrue(viewModel.uiState.value.isSaved)
        }

    @Test
    fun `a stored rest survives an edit that does not touch it`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            // `UpdateTemplateUseCase` rewrites every field it is given, so a draft that dropped this
            // would clear it — silently repricing a template the user opened to add one set.
            assertEquals(120L, templates.exercisesOf(1).first().restDuration)
        }

    @Test
    fun `saving a weight writes it back in milligrams, on the set it was typed into`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    KEY_BENCH,
                    viewModel.setKeyAt(0, 2),
                    TemplateSetField.WEIGHT,
                    "82.5",
                ),
            )
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            assertEquals(
                listOf(80_000_000L, 80_000_000L, 82_500_000L, 80_000_000L),
                templates.exercisesOf(1).first().sets.map(PlannedSet::weight),
            )
        }

    @Test
    fun `saving writes the plan set for set, in the order it is drawn`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(
                TemplateEditorEvent.SetTypeChanged(KEY_BENCH, viewModel.setKeyAt(0, 0), SetType.WARMUP),
            )
            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    KEY_BENCH,
                    viewModel.setKeyAt(0, 0),
                    TemplateSetField.WEIGHT,
                    "40",
                ),
            )
            viewModel.onEvent(TemplateEditorEvent.SetRemoved(KEY_BENCH, viewModel.setKeyAt(0, 3)))
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            assertEquals(
                listOf(
                    PlannedSet(setType = SetType.WARMUP, reps = 8, weight = 40_000_000),
                    PlannedSet(reps = 8, weight = 80_000_000),
                    PlannedSet(reps = 8, weight = 80_000_000),
                ),
                templates.exercisesOf(1).first().sets,
            )
        }

    @Test
    fun `a name already in use reopens the name dialog with the refusal on it`() =
        runTest {
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
    fun `a template deleted underneath the editor becomes the dead end`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))
            templates.delete(1)
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            assertTrue(viewModel.uiState.value.isMissing)
            assertFalse(viewModel.uiState.value.isSaved)
        }

    @Test
    fun `a store that cannot be written says so and keeps the draft`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))
            templates.writeFailure = IllegalStateException("disk full")
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            val state = viewModel.uiState.value
            assertEquals(TemplateEditorMessage.SAVE_FAILED, state.message)
            assertFalse(state.isSaved)
            assertEquals(5, state.exercises.first().sets.size)
        }

    @Test
    fun `a template edited down to nothing cannot be saved`() =
        runTest {
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
    fun `backing out of an untouched editor leaves without asking`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(TemplateEditorEvent.BackRequested)

            assertFalse(viewModel.uiState.value.confirmingDiscard)
        }

    @Test
    fun `backing out with edits asks, and discarding leaves them unwritten`() =
        runTest {
            val templates = templateStore()
            val viewModel = viewModel(templates = templates)

            viewModel.onEvent(TemplateEditorEvent.SetAdded(KEY_BENCH))
            viewModel.onEvent(TemplateEditorEvent.BackRequested)
            assertTrue(viewModel.uiState.value.confirmingDiscard)

            viewModel.onEvent(TemplateEditorEvent.DiscardConfirmed)
            assertTrue(viewModel.uiState.value.isSaved)
            assertEquals(4, templates.exercisesOf(1).first().sets.size)
        }

    // ─── Timed exercises (#M7-17) ─────────────────────────────────────────────────────────

    @Test
    fun `a planned hold and run are seeded into the fields they are typed in`() =
        runTest {
            val viewModel = viewModel(exercises = timedBody())

            val (plank, run) = viewModel.uiState.value.exercises
            assertEquals(ExerciseType.ISOMETRIC, plank.exerciseType)
            assertEquals(listOf("0:45", "0:45", "0:45"), plank.sets.map(TemplateSetRow::duration))
            assertEquals("5", run.sets.single().distance)
            assertEquals("30:00", run.sets.single().duration)
            // The estimate reads the planned time rather than a rep tempo: three 45 s holds, each
            // followed by the plank's own 30 s rest, and a 30 min run — with no rest after the run,
            // which ends the workout.
            assertEquals(45.seconds * 3 + 30.seconds * 3 + 30.minutes, viewModel.uiState.value.estimatedDuration)
        }

    @Test
    fun `a planned time typed into the editor is saved as seconds`() =
        runTest {
            val templates = templateStore(timedBody())
            val viewModel = viewModel(templates = templates)
            val plank = viewModel.uiState.value.exercises.first()

            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    plank.key,
                    plank.sets.first().key,
                    TemplateSetField.DURATION,
                    "1:00",
                ),
            )
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            assertTrue(viewModel.uiState.value.isSaved)
            val saved = templates.exercisesOf(1)
            assertEquals(listOf(60L, 45L, 45L), saved.first().sets.map(PlannedSet::duration))
            assertEquals(PlannedSet(distance = 500_000, duration = 1_800), saved.last().sets.single())
        }

    @Test
    fun `negative - a planned time that is not a time is marked and blocks the save`() =
        runTest {
            val viewModel = viewModel(exercises = timedBody())
            val plank = viewModel.uiState.value.exercises.first()

            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    plank.key,
                    plank.sets.first().key,
                    TemplateSetField.DURATION,
                    "1.5",
                ),
            )

            assertEquals(
                setOf(TemplateSetField.DURATION),
                viewModel.uiState.value.exercises.first().sets.first().errors,
            )
            assertFalse(viewModel.uiState.value.canSave)
        }

    @Test
    fun `a swap carries a planned distance across as the same distance`() =
        runTest {
            val templates = templateStore(timedBody())
            val viewModel = viewModel(templates = templates)

            // The run's `5` is kilometres, and so is the swim's: every distance field is typed in
            // the same unit, so the text needs no re-expressing.
            viewModel.swap(KEY_FLY, SWIM.id)

            val swim = viewModel.uiState.value.exercises.last()
            assertEquals("5", swim.sets.single().distance)
            assertEquals("30:00", swim.sets.single().duration)
            viewModel.onEvent(TemplateEditorEvent.SaveRequested)
            assertEquals(
                PlannedSet(distance = 500_000, duration = 1_800),
                templates.exercisesOf(1).last().sets.single(),
            )
        }

    @Test
    fun `a swap carries a bare planned time across as the time it was`() =
        runTest {
            val viewModel = viewModel(exercises = timedBody())
            val run = viewModel.uiState.value.exercises.last()

            // A bare `20` in a run's field is twenty minutes; in a plank's it would be twenty seconds.
            viewModel.onEvent(
                TemplateEditorEvent.SetFieldChanged(run.key, run.sets.single().key, TemplateSetField.DURATION, "20"),
            )
            viewModel.swap(KEY_FLY, PLANK.id)

            assertEquals("20:00", viewModel.uiState.value.exercises.last().sets.single().duration)
        }

    /** `3 × 0:45` of a plank and a `5 km` run, the issue's own example plan. */
    private fun timedBody() =
        listOf(
            TemplateExercise(
                id = KEY_BENCH,
                templateId = 1,
                exerciseId = PLANK.id,
                position = 0,
                sets = List(3) { PlannedSet(duration = 45) },
                restDuration = 30,
            ),
            TemplateExercise(
                id = KEY_FLY,
                templateId = 1,
                exerciseId = RUN.id,
                position = 1,
                sets = listOf(PlannedSet(distance = 500_000, duration = 1_800)),
            ),
        )

    /** A push day whose last two exercises are one superset, next to an unrelated template. */
    private fun templateStore(exercises: List<TemplateExercise> = pushDayBody()) =
        FakeTemplateStore(
            templates = listOf(
                namedTemplate(1, "Push Day", TemplateCategory.STRENGTH),
                namedTemplate(2, "Pull Day"),
            ),
            exercises = exercises,
        )

    private fun pushDayBody() =
        listOf(
            TemplateExercise(
                id = KEY_BENCH,
                templateId = 1,
                exerciseId = BENCH.id,
                position = 0,
                sets = List(4) { PlannedSet(reps = 8, weight = 80_000_000) },
                // The editor has never been able to set this and cannot now — it is here because
                // templates written before the field was dropped still hold one, and a save has to
                // hand it back. See `a stored rest survives an edit that does not touch it`.
                restDuration = 120,
            ),
            TemplateExercise(
                id = KEY_FLY,
                templateId = 1,
                exerciseId = FLY.id,
                position = 1,
                supersetGroup = 0,
                sets = List(3) { PlannedSet(reps = 12) },
            ),
            TemplateExercise(
                id = KEY_PUSHDOWN,
                templateId = 1,
                exerciseId = PUSHDOWN.id,
                position = 2,
                supersetGroup = 0,
                sets = List(3) { PlannedSet(reps = 12) },
            ),
        )

    /**
     * The key of one set of one exercise, read off the state rather than assumed.
     *
     * Set keys come from a counter inside the ViewModel — see `TemplateEditorViewModel` — so a
     * test that hard-coded them would be asserting on the order the seed happened to be walked
     * in, which is not a contract.
     */
    private fun TemplateEditorViewModel.setKeyAt(
        exercise: Int,
        set: Int,
    ): Long = uiState.value.exercises[exercise].sets[set].key

    /** Types into one set's rep box, addressed the way the screen addresses it: by both keys. */
    private fun TemplateEditorViewModel.setReps(
        exercise: Int,
        set: Int,
        text: String,
    ) = onEvent(
        TemplateEditorEvent.SetFieldChanged(
            exerciseKey = uiState.value.exercises[exercise].key,
            setKey = setKeyAt(exercise, set),
            field = TemplateSetField.REPS,
            text = text,
        ),
    )

    // ─── Process death (#183) ───────────────────────────────────────────────────────────

    @Test
    fun `a half-built new template comes back after the task itself is gone`() =
        runTest {
            val before = viewModel(templateId = NEW_TEMPLATE)
            before.name("Leg day")
            before.pick(listOf(BENCH.id, FLY.id))
            val setKey = before.setKeyAt(0, 0)
            before.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    exerciseKey = before.uiState.value.exercises.first().key,
                    setKey = setKey,
                    field = TemplateSetField.REPS,
                    text = "12",
                ),
            )

            // Not `afterProcessDeath`: the saved state goes too, which is the case a `Bundle`
            // cannot cover and the whole reason the plan is a file.
            val state = viewModel(templateId = NEW_TEMPLATE, savedState = newTemplateArguments()).uiState.value

            assertEquals("Leg day", state.name)
            assertEquals(listOf("Bench press", "Cable fly"), state.exercises.map { it.exercise?.name })
            assertEquals("12", state.exercises.first().sets.first().reps)
            assertTrue(state.canSave)
        }

    @Test
    fun `an edit to a stored template comes back, and is still measured against what is stored`() =
        runTest {
            val store = templateStore()
            val before = viewModel(templates = store)
            before.onEvent(TemplateEditorEvent.ExerciseRemoved(KEY_PUSHDOWN))

            val restored = viewModel(templates = store, savedState = SavedStateHandle(mapOf("templateId" to 1L)))

            val state = restored.uiState.value
            assertEquals(listOf("Bench press", "Cable fly"), state.exercises.map { it.exercise?.name })
            // The seed is the stored template, not the draft, so backing out still asks.
            assertTrue(state.isDirty)
        }

    @Test
    fun `a restored draft goes on numbering new lines where it left off`() =
        runTest {
            val before = viewModel(templateId = NEW_TEMPLATE)
            before.pick(listOf(BENCH.id, FLY.id))

            val restored = viewModel(templateId = NEW_TEMPLATE, savedState = newTemplateArguments())
            restored.pick(listOf(PUSHDOWN.id))

            val keys = restored.uiState.value.exercises.map { it.key }
            assertEquals("two lines must never share a key: $keys", keys.size, keys.toSet().size)
            val setKeys = restored.uiState.value.exercises.flatMap { row -> row.sets.map { it.key } }
            assertEquals("two sets must never share a key: $setKeys", setKeys.size, setKeys.toSet().size)
        }

    @Test
    fun `a draft written in another unit system keeps the unit its figures were typed in`() =
        runTest {
            val before = viewModel(templateId = NEW_TEMPLATE)
            before.name("Leg day")
            before.pick(listOf(BENCH.id))
            before.onEvent(
                TemplateEditorEvent.SetFieldChanged(
                    exerciseKey = before.uiState.value.exercises.first().key,
                    setKey = before.setKeyAt(0, 0),
                    field = TemplateSetField.WEIGHT,
                    text = "100",
                ),
            )

            // The setting changes while the app is dead. Reformatting the column would turn a
            // hundred kilos into a hundred pounds without anybody touching the field.
            preferences.setUnitSystem(UnitSystem.IMPERIAL)
            val state = viewModel(templateId = NEW_TEMPLATE, savedState = newTemplateArguments()).uiState.value

            assertEquals(UnitSystem.METRIC, state.unitSystem)
            assertEquals("100", state.exercises.first().sets.first().weight)
        }

    @Test
    fun `the naming dialog comes back with what was typed into it`() =
        runTest {
            val handle = SavedStateHandle(mapOf("templateId" to NEW_TEMPLATE))
            val before = viewModel(templateId = NEW_TEMPLATE, savedState = handle)
            before.onEvent(TemplateEditorEvent.NameChanged("Leg d"))
            before.onEvent(TemplateEditorEvent.CategoryChanged(TemplateCategory.HYPERTROPHY))

            val naming = viewModel(templateId = NEW_TEMPLATE, savedState = handle.afterProcessDeath())
                .uiState.value.naming

            assertEquals("Leg d", naming?.name)
            assertEquals(TemplateCategory.HYPERTROPHY, naming?.category)
        }

    @Test
    fun `a picker sent for before the process died still gets its answer`() =
        runTest {
            val handle = SavedStateHandle(mapOf("templateId" to 1L))
            val before = viewModel(savedState = handle)
            before.onEvent(TemplateEditorEvent.ExercisesPickRequested)

            val restored = viewModel(savedState = handle.afterProcessDeath())
            restored.onEvent(TemplateEditorEvent.ExercisesPicked(listOf(PUSHDOWN.id)))

            val state = restored.uiState.value
            assertEquals(4, state.exercises.size)
            assertNull(state.message)
        }

    @Test
    fun `a swap sent for before the process died still lands in the slot it was asked for`() =
        runTest {
            val handle = SavedStateHandle(mapOf("templateId" to 1L))
            val before = viewModel(savedState = handle)
            before.onEvent(TemplateEditorEvent.ExerciseSwapRequested(KEY_BENCH))

            val restored = viewModel(savedState = handle.afterProcessDeath())
            restored.onEvent(TemplateEditorEvent.ExercisesPicked(listOf(PUSHDOWN.id)))

            val rows = restored.uiState.value.exercises
            assertEquals(3, rows.size)
            assertEquals("Tricep pushdown", rows.first().exercise?.name)
        }

    @Test
    fun `a template that has been saved leaves no draft behind`() =
        runTest {
            val viewModel = viewModel(templateId = NEW_TEMPLATE)
            viewModel.name("Leg day")
            viewModel.pick(listOf(BENCH.id))
            assertNotNull("the draft should have been written as it was built", drafts.saved)

            viewModel.onEvent(TemplateEditorEvent.SaveRequested)

            assertNull(drafts.saved)
        }

    @Test
    fun `a discard the user confirmed leaves no draft behind`() =
        runTest {
            val viewModel = viewModel(templateId = NEW_TEMPLATE)
            viewModel.name("Leg day")
            viewModel.pick(listOf(BENCH.id))

            viewModel.onEvent(TemplateEditorEvent.BackRequested)
            viewModel.onEvent(TemplateEditorEvent.DiscardConfirmed)

            assertNull(drafts.saved)
        }

    @Test
    fun `a draft belonging to another template is not restored over this one`() =
        runTest {
            val before = viewModel(templateId = NEW_TEMPLATE)
            before.name("Leg day")

            val state = viewModel(templateId = 1, savedState = SavedStateHandle(mapOf("templateId" to 1L)))
                .uiState.value

            assertEquals("Push Day", state.name)
            assertFalse(state.isDirty)
        }

    @Test
    fun `a restored draft typed back to the stored plan is clean again`() =
        runTest {
            val store = templateStore()
            val before = viewModel(templates = store)
            before.setReps(exercise = 0, set = 0, text = "10")

            val restored = viewModel(templates = store, savedState = SavedStateHandle(mapOf("templateId" to 1L)))
            restored.setReps(exercise = 0, set = 0, text = "8")

            // The seed a restored editor measures against has to be numbered exactly as the
            // first open numbered it, or every key in it differs from the draft's and the
            // comparison says "changed" whatever the user does. Put back, the plan *is* what is
            // stored: SAVE goes inert, backing out asks nothing, and the file goes.
            assertFalse(restored.uiState.value.isDirty)
            assertNull(drafts.saved)
        }

    @Test
    fun `an editor opened on another template leaves the draft where it is`() =
        runTest {
            val before = viewModel(templateId = NEW_TEMPLATE)
            before.name("Leg day")

            viewModel(savedState = SavedStateHandle(mapOf("templateId" to 1L)))

            // The slot holds one plan and the next save overwrites it — but a half-written
            // template is minutes of work that a later visit to *that* editor still restores,
            // and opening a second one to look at it is not the user throwing the first away.
            assertEquals("Leg day", drafts.saved?.name)
        }

    @Test
    fun `a draft store that cannot be written does not take the editor down`() =
        runTest {
            drafts.savesFail = true
            val viewModel = viewModel(templateId = NEW_TEMPLATE)

            viewModel.name("Leg day")
            viewModel.pick(listOf(BENCH.id))

            // The plan is still on screen and still saveable; only the safety net is gone.
            assertEquals("Leg day", viewModel.uiState.value.name)
            assertTrue(viewModel.uiState.value.canSave)
        }

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
        savedState: SavedStateHandle = SavedStateHandle(mapOf("templateId" to templateId)),
        drafts: FakeTemplateDraftRepository = this@TemplateEditorViewModelTest.drafts,
    ): TemplateEditorViewModel {
        val store = exercises?.let { templateStore(it) } ?: templates
        val viewModel = TemplateEditorViewModel(
            savedStateHandle = savedState,
            templateRepository = store,
            exerciseRepository = this@TemplateEditorViewModelTest.exercises,
            userPreferencesRepository = preferences,
            createTemplate = CreateTemplateUseCase(store),
            updateTemplate = UpdateTemplateUseCase(store),
            templateDrafts = drafts,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    /**
     * A pick, delivered the way the screen delivers one: the request that pushes the picker,
     * then the answer that comes back from it. The two are a pair — an answer without its
     * request is one from before a process restart, and is refused.
     */
    private fun TemplateEditorViewModel.pick(exerciseIds: List<Long>) {
        onEvent(TemplateEditorEvent.ExercisesPickRequested)
        onEvent(TemplateEditorEvent.ExercisesPicked(exerciseIds))
    }

    /** The ⇄ on one slot, then the picker's single answer. The screen dispatches the same pair. */
    private fun TemplateEditorViewModel.swap(
        key: Long,
        exerciseId: Long,
    ) {
        onEvent(TemplateEditorEvent.ExerciseSwapRequested(key))
        onEvent(TemplateEditorEvent.ExercisesPicked(listOf(exerciseId)))
    }

    /**
     * The arguments a *new* editor is recreated with after the task record has gone.
     *
     * The route arguments come back because navigation rebuilds the destination from the back
     * stack; nothing else does, which is the difference this exercises.
     */
    private fun newTemplateArguments() = SavedStateHandle(mapOf("templateId" to NEW_TEMPLATE))

    /** Names the template the way the dialog does: type it, then confirm. */
    private fun TemplateEditorViewModel.name(name: String) {
        onEvent(TemplateEditorEvent.NameEditRequested)
        onEvent(TemplateEditorEvent.NameChanged(name))
        onEvent(TemplateEditorEvent.NameConfirmed)
    }

    private companion object {
        val BENCH = Exercise(id = 10, name = "Bench press")
        val FLY = Exercise(id = 11, name = "Cable fly")
        val PUSHDOWN = Exercise(id = 12, name = "Tricep pushdown")
        val PLANK = Exercise(id = 13, name = "Plank", type = ExerciseType.ISOMETRIC)
        val RUN = Exercise(id = 14, name = "Running, Outdoor", type = ExerciseType.ENDURANCE)
        val SWIM = Exercise(id = 15, name = "Swimming, Freestyle", type = ExerciseType.ENDURANCE)

        /** The stored `template_exercise` ids, which are the draft's keys. */
        const val KEY_BENCH = 100L
        const val KEY_FLY = 101L
        const val KEY_PUSHDOWN = 102L
    }
}
