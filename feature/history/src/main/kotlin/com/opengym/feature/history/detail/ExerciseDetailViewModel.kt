package com.opengym.feature.history.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.feature.history.exercise.ExerciseDraft
import com.opengym.feature.history.exercise.applyTo
import com.opengym.feature.history.exercise.exerciseDraftOf
import com.opengym.feature.history.exercise.isDuplicateIn
import com.opengym.feature.history.exercise.resolveMuscleGroups
import com.opengym.feature.history.navigation.ExerciseDetailDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * One exercise's state: the row, what it trains, and the three things a user may write to it.
 *
 * ### Two writes with two different rules
 *
 * **The note** is the user's own remark on *any* exercise, seeded or not, and goes through
 * `update(exercise)` — the row's own columns and nothing else. **The edit** rewrites a custom
 * exercise wholesale and goes through `update(exercise, muscleGroups)`, because changing what
 * a movement trains is a change to the link table that volume attribution reads. Keeping them
 * apart here is what keeps "anybody may annotate, only the author may rewrite" a property of
 * the code rather than of the screen that happens to draw the buttons — see
 * [ExerciseDetailUiState.canEdit].
 *
 * ### Everything is observed, nothing is cached
 *
 * A save writes and says nothing more. The row re-emits from Room and the screen redraws from
 * that, so there is no local copy to keep in step and no path where the screen shows a note
 * the database does not have. The one thing held locally is what the *user* is in the middle
 * of typing, which the database has no opinion about.
 */
@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val exerciseId = savedStateHandle.toRoute<ExerciseDetailDestination>().exerciseId

    /** What the screen owns, as opposed to what the store does. */
    private val screenState = MutableStateFlow(ScreenState())

    /**
     * The row, boxed, or **null** until the read comes back.
     *
     * `getById` answers `null` for an id that does not exist, which is the same value it has
     * before it has answered at all — and those are two different screens. Boxing is what
     * separates them: a null box is "no answer yet", a box holding null is "there is no such
     * exercise". The alternative, a second boolean updated alongside, is two values that can
     * disagree.
     */
    private val loaded: StateFlow<Loaded?> = exerciseRepository.getById(exerciseId)
        .map(::Loaded)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /**
     * The two lists the edit form needs: the muscle vocabulary, and the catalogue it must not
     * collide with.
     *
     * Combined into one value so the outer combine takes four arguments, and observed rather
     * than fetched when the form opens because the duplicate check has to answer *while the
     * user types* — see [ExerciseDraft.isDuplicateIn]. It is a scan of a table the Library tab
     * already keeps open; the cost is one query, re-run when the catalogue changes.
     */
    private val vocabulary: Flow<Vocabulary> = combine(
        exerciseRepository.getMuscleGroups(),
        exerciseRepository.getAll(),
    ) { taxonomy, catalogue -> Vocabulary(taxonomy = taxonomy, catalogue = catalogue) }

    val uiState: StateFlow<ExerciseDetailUiState> = combine(
        loaded,
        exerciseRepository.getMuscleGroupLinks(exerciseId),
        vocabulary,
        screenState,
    ) { loaded, links, vocabulary, screen ->
        buildState(loaded, links, vocabulary, screen)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ExerciseDetailUiState(),
    )

    fun onEvent(event: ExerciseDetailEvent) {
        when (event) {
            ExerciseDetailEvent.NoteEditRequested -> openNote()
            is ExerciseDetailEvent.NoteChanged ->
                screenState.update { it.copy(noteDraft = event.text) }
            ExerciseDetailEvent.NoteEditCancelled ->
                screenState.update { it.copy(noteDraft = null, isSavingNotes = false) }
            ExerciseDetailEvent.NoteSaved -> saveNote()

            ExerciseDetailEvent.EditRequested -> openEdit()
            is ExerciseDetailEvent.EditChanged ->
                screenState.update { it.copy(editDraft = event.draft.copy(saveFailed = false)) }
            ExerciseDetailEvent.EditSubmitted -> saveEdit()
            ExerciseDetailEvent.EditDismissed -> screenState.update { it.copy(editDraft = null) }

            ExerciseDetailEvent.DeleteRequested -> screenState.update { it.copy(confirmingDelete = true) }
            ExerciseDetailEvent.DeleteConfirmed -> delete()
            ExerciseDetailEvent.DeleteDismissed ->
                screenState.update { it.copy(confirmingDelete = false) }

            ExerciseDetailEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    /** Seed the field from what is stored. Ignored before the row has loaded. */
    private fun openNote() {
        val exercise = loaded.value?.exercise ?: return
        screenState.update { it.copy(noteDraft = exercise.notes.orEmpty()) }
    }

    /**
     * Write the note, leaving the field up and inert while the write is in flight.
     *
     * A blank note clears the column rather than storing whitespace, which is also the only
     * way to delete one: the field's own empty state is the delete affordance, so there is no
     * separate button for it to be inconsistent with.
     *
     * [Throwable] rather than a type, for the reason the library's save catches broadly: a
     * store that cannot be written fails in more than one way and all of them are the same
     * event to somebody looking at a text field.
     */
    private fun saveNote() {
        val exercise = loaded.value?.exercise ?: return
        val text = screenState.value.noteDraft ?: return
        if (screenState.value.isSavingNotes) return

        screenState.update { it.copy(isSavingNotes = true) }

        viewModelScope.launch {
            try {
                exerciseRepository.update(exercise.copy(notes = text.trim().ifEmpty { null }))
                // Closed on success only. The re-read from Room is what draws the new note;
                // nothing is copied into local state to be kept in step with it.
                screenState.update { it.copy(noteDraft = null, isSavingNotes = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The field stays open with the text still in it: it is the only copy of what
                // the user wrote, and a field that closed on failure would lose it.
                screenState.update {
                    it.copy(isSavingNotes = false, message = ExerciseDetailMessage.NOTES_SAVE_FAILED)
                }
            }
        }
    }

    /** Open the form on this exercise. Refused for a seeded one, whatever asked. */
    private fun openEdit() {
        val exercise = loaded.value?.exercise?.takeIf { it.isCustom } ?: return
        val draft = exerciseDraftOf(exercise, uiState.value.muscleLinks)
        screenState.update { it.copy(editDraft = draft) }
    }

    /**
     * Rewrite the exercise and its muscle links together.
     *
     * The guard is [ExerciseDraft.canSave], which is the same predicate the save button is
     * enabled by — restated because an event can arrive from a keyboard action or a double tap
     * dispatched before the button disabled itself, and the second write would collide with
     * the unique index rather than being ignored.
     */
    private fun saveEdit() {
        val exercise = loaded.value?.exercise ?: return
        val draft = screenState.value.editDraft ?: return
        if (!draft.canSave) return

        val links = draft.resolveMuscleGroups(uiState.value.taxonomy)
        screenState.update { it.copy(editDraft = draft.copy(isSaving = true)) }

        viewModelScope.launch {
            try {
                exerciseRepository.update(draft.applyTo(exercise), links)
                screenState.update {
                    it.copy(editDraft = null, message = ExerciseDetailMessage.EXERCISE_UPDATED)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Where the failure is *said* depends on whether the form is still up, and the
                // two places are exclusive: the sheet is drawn over the Scaffold that hosts the
                // snackbar, so a snackbar raised under an open sheet would run its whole
                // duration invisible and be marked shown.
                screenState.update { state ->
                    val open = state.editDraft
                    if (open == null) {
                        state.copy(message = ExerciseDetailMessage.EXERCISE_SAVE_FAILED)
                    } else {
                        state.copy(editDraft = open.copy(isSaving = false, saveFailed = true))
                    }
                }
            }
        }
    }

    /**
     * Delete the exercise, and say so, so the screen can leave.
     *
     * [ExerciseDetailUiState.isDeleted] rather than letting the row's own disappearance stand
     * for it: `getById` re-emits null the moment the delete lands, which would draw the "no
     * longer exists" page for a frame on the way out of a screen the user is deliberately
     * closing.
     *
     * Sessions that already reference the exercise are the database's problem, and it has an
     * answer — see the foreign key on `session_exercise` in PERSISTENCE.md. A delete the
     * schema refuses surfaces here as [ExerciseDetailMessage.DELETE_FAILED] rather than as a
     * crash.
     */
    private fun delete() {
        val exercise = loaded.value?.exercise?.takeIf { it.isCustom } ?: return
        if (screenState.value.isDeleting) return

        screenState.update { it.copy(isDeleting = true) }

        viewModelScope.launch {
            try {
                exerciseRepository.delete(exercise.id)
                screenState.update { it.copy(confirmingDelete = false, isDeleted = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update {
                    it.copy(
                        confirmingDelete = false,
                        isDeleting = false,
                        message = ExerciseDetailMessage.DELETE_FAILED,
                    )
                }
            }
        }
    }

    /**
     * Assemble what the screen draws.
     *
     * The edit draft's duplicate flag is *derived here* rather than stamped on the draft when
     * it changes, so it stays right when the catalogue moves underneath an open form — a name
     * that was free when the user typed it is not free once another device's sync writes it.
     */
    private fun buildState(
        loaded: Loaded?,
        links: List<ExerciseMuscleGroupLink>,
        vocabulary: Vocabulary,
        screen: ScreenState,
    ): ExerciseDetailUiState = ExerciseDetailUiState(
        exercise = loaded?.exercise,
        muscleLinks = links,
        taxonomy = vocabulary.taxonomy,
        notesDraft = screen.noteDraft,
        isSavingNotes = screen.isSavingNotes,
        editDraft = screen.editDraft?.let { it.copy(isDuplicateName = it.isDuplicateIn(vocabulary.catalogue)) },
        confirmingDelete = screen.confirmingDelete,
        isDeleting = screen.isDeleting,
        isDeleted = screen.isDeleted,
        isLoading = loaded == null,
        message = screen.message,
    )

    /** What the user is in the middle of, which the store has no opinion about. */
    private data class ScreenState(
        val noteDraft: String? = null,
        val isSavingNotes: Boolean = false,
        val editDraft: ExerciseDraft? = null,
        val confirmingDelete: Boolean = false,
        val isDeleting: Boolean = false,
        val isDeleted: Boolean = false,
        val message: ExerciseDetailMessage? = null,
    )

    /** The read, boxed so "not yet" and "no such row" are different values. See [loaded]. */
    private data class Loaded(val exercise: Exercise?)

    /** The two catalogue-wide reads the edit form needs, as one value. */
    private data class Vocabulary(
        val taxonomy: List<MuscleGroup>,
        val catalogue: List<Exercise>,
    )

    private companion object {
        /**
         * Long enough to survive a rotation without re-reading, short enough that a
         * backgrounded screen is not holding database observers open. The library's number,
         * for the same reasons.
         */
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
