package com.opengym.feature.history.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The exercise library's state: the catalogue, narrowed three ways and grouped by what each
 * exercise trains.
 *
 * ### Where each of the three filters is applied, and why they differ
 *
 * The **search** runs in SQLite, through `searchByPartialName`, re-queried as the user types.
 * The **muscle** and **equipment** filters run here, over the rows that came back. That is
 * not an inconsistency: the muscle filter needs the exercise-to-muscle join, which is already
 * loaded in full for the grouping, and pushing it down would mean a second parameterised
 * query returning rows this class already holds. The three still compose as one intersection,
 * because the second two are applied to the first one's output.
 *
 * There is deliberately **no debounce** on the query. The catalogue is 184 rows and the
 * search is an unindexed `LIKE` scan over them, which is faster than the frame it would be
 * drawn in; a debounce would buy nothing measurable and cost the user a visible lag between
 * typing and seeing.
 *
 * ### What is read once and what is observed
 *
 * Four reads back the screen — the catalogue, its muscle links, the taxonomy and the records
 * — and all four are observed rather than fetched, so a custom exercise created in the sheet
 * appears in the list because the catalogue re-emitted, not because anything here inserted it
 * into a local copy. Two of them are also held as [StateFlow]s, because the writes need a
 * *snapshot*: the duplicate-name check reads the catalogue, and saving a draft resolves
 * muscle group ids against the taxonomy.
 */
@HiltViewModel
class ExerciseLibraryViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    personalRecordRepository: PersonalRecordRepository,
) : ViewModel() {

    /** What the screen owns, as opposed to what the stores do. */
    private val screenState = MutableStateFlow(ScreenState())

    private val allExercises: StateFlow<List<Exercise>> = exerciseRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    private val muscleGroups: StateFlow<List<MuscleGroup>> = exerciseRepository.getMuscleGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /**
     * The rows the search matches — the whole catalogue when the box is empty.
     *
     * Reusing [allExercises] for the empty case rather than calling `getAll()` again keeps
     * the common state to one observed query instead of two identical ones.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val matches: Flow<List<Exercise>> = screenState
        .map { it.query.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isEmpty()) allExercises else exerciseRepository.searchByPartialName(query)
        }

    private val catalogue: Flow<Catalogue> = combine(
        allExercises,
        exerciseRepository.getAllMuscleGroupLinks(),
        muscleGroups,
        personalRecordRepository.getHeadlineRecords(),
    ) { exercises, links, taxonomy, records ->
        Catalogue(exercises = exercises, links = links, taxonomy = taxonomy, records = records)
    }

    val uiState: StateFlow<ExerciseLibraryUiState> = combine(
        catalogue,
        matches,
        screenState,
    ) { catalogue, matches, screen ->
        buildState(catalogue, matches, screen)
    }.stateIn(
        scope = viewModelScope,
        // Long enough to survive a rotation without re-reading four queries, short enough
        // that a backgrounded screen is not holding database observers open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ExerciseLibraryUiState(),
    )

    fun onEvent(event: ExerciseLibraryEvent) {
        when (event) {
            is ExerciseLibraryEvent.QueryChanged -> screenState.update { it.copy(query = event.query) }
            is ExerciseLibraryEvent.MuscleFilterSelected ->
                screenState.update { it.copy(muscleGroupId = event.muscleGroupId) }
            is ExerciseLibraryEvent.EquipmentFilterToggled -> toggleEquipment(event.equipment)
            ExerciseLibraryEvent.FiltersReset ->
                screenState.update { it.copy(muscleGroupId = null, equipment = emptySet()) }
            ExerciseLibraryEvent.SearchAndFiltersCleared ->
                screenState.update { it.copy(query = "", muscleGroupId = null, equipment = emptySet()) }
            is ExerciseLibraryEvent.ExerciseToggled -> toggleSelection(event.exerciseId)
            ExerciseLibraryEvent.NewExerciseRequested ->
                screenState.update { it.copy(draft = NewExerciseDraft()) }
            is ExerciseLibraryEvent.NewExerciseChanged ->
                screenState.update { it.copy(draft = event.draft.withDuplicateCheck()) }
            ExerciseLibraryEvent.NewExerciseSubmitted -> saveDraft()
            ExerciseLibraryEvent.NewExerciseDismissed -> screenState.update { it.copy(draft = null) }
            ExerciseLibraryEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    private fun toggleEquipment(equipment: EquipmentType) = screenState.update { state ->
        val next = if (equipment in state.equipment) {
            state.equipment - equipment
        } else {
            state.equipment + equipment
        }
        state.copy(equipment = next)
    }

    /**
     * Add or remove an exercise from the selection, keeping the order it was picked in.
     *
     * Appending rather than inserting matters: the selection becomes the new session's
     * exercise order, so a lifter who taps squat, then bench, gets a session in that order
     * rather than in whatever order the library happened to list them.
     */
    private fun toggleSelection(exerciseId: Long) = screenState.update { state ->
        val next = if (exerciseId in state.selection) {
            state.selection - exerciseId
        } else {
            state.selection + exerciseId
        }
        state.copy(selection = next)
    }

    /**
     * Write the draft, leaving the form up and inert while the write is in flight.
     *
     * The guard is [NewExerciseDraft.canSave], which is the same predicate the save button is
     * enabled by — restated here because an event can arrive from a keyboard action or a
     * double tap dispatched before the button disabled itself, and a second insert of the
     * same name would fail on the unique index rather than being ignored.
     *
     * [Throwable] rather than a type, for the reason the settings screen catches broadly: the
     * write can refuse for reasons with three different types — a name and equipment pair the
     * catalogue already holds, a muscle group id that no longer exists, a store that cannot be
     * written — and all three are the same event to somebody looking at a form.
     */
    private fun saveDraft() {
        val draft = screenState.value.draft ?: return
        if (!draft.canSave) return

        val links = draft.resolveMuscleGroups(muscleGroups.value)
        screenState.update { it.copy(draft = draft.copy(isSaving = true)) }

        viewModelScope.launch {
            try {
                exerciseRepository.insert(draft.toExercise(), links)
                screenState.update {
                    it.copy(draft = null, message = ExerciseLibraryMessage.EXERCISE_CREATED)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The draft goes back the way it was, not away: what the user typed is the
                // only copy of it, and a form that closed on failure would lose it.
                screenState.update {
                    it.copy(
                        draft = draft.copy(isSaving = false),
                        message = ExerciseLibraryMessage.SAVE_FAILED,
                    )
                }
            }
        }
    }

    /**
     * Flag a name the catalogue already holds *for the same equipment*.
     *
     * Equipment is part of the comparison because it is part of the database's uniqueness
     * rule — a barbell row and a dumbbell row are two exercises, and a lifter who has both
     * should be able to say so. Case-insensitively, because the index is not the thing the
     * user is trying to satisfy; "bench press" beside "Bench Press" is a duplicate to anyone
     * reading the list.
     */
    private fun NewExerciseDraft.withDuplicateCheck(): NewExerciseDraft {
        val trimmed = name.trim()
        val duplicate = trimmed.isNotEmpty() && allExercises.value.any { existing ->
            existing.name.equals(trimmed, ignoreCase = true) && existing.equipmentType == equipment
        }
        return copy(isDuplicateName = duplicate)
    }

    /**
     * Narrow, group and count — the whole of what the screen is handed.
     *
     * The muscle filter matches **any** link, primary or secondary, while the sections group
     * on the *first* one, which is the prime mover. Those are deliberately different
     * questions: asking for chest should turn up the dips and the overhead press that work
     * it, and each of them still belongs under the muscle it trains hardest. The result reads
     * as "everything that hits chest, by what it hits most", which is more use than a single
     * flat CHEST section.
     */
    private fun buildState(
        catalogue: Catalogue,
        matches: List<Exercise>,
        screen: ScreenState,
    ): ExerciseLibraryUiState {
        val visible = matches.filterBy(
            muscleGroupId = screen.muscleGroupId,
            equipment = screen.equipment,
            links = catalogue.links,
        )

        return ExerciseLibraryUiState(
            query = screen.query,
            muscleGroups = catalogue.taxonomy,
            selectedMuscleGroupId = screen.muscleGroupId,
            equipmentTypes = catalogue.exercises.equipmentVocabulary(),
            selectedEquipment = screen.equipment,
            sections = visible.groupIntoSections(catalogue.links, catalogue.records),
            catalogueSize = catalogue.exercises.size,
            matchCount = visible.size,
            isLoading = false,
            selection = screen.selection,
            draft = screen.draft,
            message = screen.message,
        )
    }

    /** What the screen shows and what the stores do not know about. */
    private data class ScreenState(
        val query: String = "",
        val muscleGroupId: Long? = null,
        val equipment: Set<EquipmentType> = emptySet(),
        val selection: List<Long> = emptyList(),
        val draft: NewExerciseDraft? = null,
        val message: ExerciseLibraryMessage? = null,
    )

    /** The four observed reads, as one value, so the outer combine takes three arguments. */
    private data class Catalogue(
        val exercises: List<Exercise>,
        val links: Map<Long, List<ExerciseMuscleGroupLink>>,
        val taxonomy: List<MuscleGroup>,
        val records: Map<Long, PersonalRecord>,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
