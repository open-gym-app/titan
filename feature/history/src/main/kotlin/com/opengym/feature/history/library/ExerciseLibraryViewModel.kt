package com.opengym.feature.history.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.ui.state.savedScreenState
import com.opengym.feature.history.exercise.ExerciseDraft
import com.opengym.feature.history.exercise.isDuplicateIn
import com.opengym.feature.history.exercise.resolveMuscleGroups
import com.opengym.feature.history.exercise.toExercise
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
 * There is deliberately **no debounce** on the query. The catalogue is the 873 seeded rows
 * plus whatever the user has added, and the search is an unindexed `LIKE` scan over them —
 * still comfortably inside the frame it is drawn in, so a debounce would buy nothing
 * measurable and cost the user a visible lag between typing and seeing. It is the row count
 * that carries this, not the query: the scan is linear, and a catalogue an order of
 * magnitude larger is the point at which this is worth measuring again rather than assumed.
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
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    personalRecordRepository: PersonalRecordRepository,
) : ViewModel() {

    /**
     * What the screen owns, as opposed to what the stores do.
     *
     * Saved, because every field of it is a narrowing the user performed and none of it is
     * re-derivable: a search typed into the box, two filters set from the panel, the selection
     * being assembled for a new session, and a custom exercise being written. The catalogue
     * underneath is a query and is deliberately not saved — see [buildState].
     */
    private val screenState = savedStateHandle.savedScreenState(viewModelScope) { ScreenState() }

    /**
     * The catalogue, or **null** until the read comes back.
     *
     * Null rather than an empty list because the two are different states and the screen draws
     * them differently: an empty catalogue means the seed did not run and says so in alarming
     * terms, which is not a thing to flash at somebody for the few milliseconds before the
     * rows arrive. The other three reads can and do answer first — they are separate queries
     * on Room's executor, and `getHeadlineRecords` over an empty table is trivially fast — so
     * without this the first emission really is a fully-loaded-looking empty library.
     */
    private val allExercises: StateFlow<List<Exercise>?> = exerciseRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    private val muscleGroups: StateFlow<List<MuscleGroup>> = exerciseRepository.getMuscleGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /**
     * The rows the search matches — the whole catalogue when the box is empty.
     *
     * Reusing [allExercises] for the empty case rather than calling `getAll()` again keeps
     * the common state to one observed query instead of two identical ones.
     *
     * Each result **carries the query it answers**, because the two do not arrive together.
     * `screenState` reaches the outer combine the moment the query changes, while the rows for
     * it are still in SQLite; the last result to have arrived is the *previous* query's. That
     * is fine to keep drawing — stale rows for a few milliseconds read as "nothing has happened
     * yet" — but it must not be counted as an answer, or clearing a search that matched nothing
     * would render "the catalogue did not load" on the way back to a full library. [buildState]
     * compares the two and treats a mismatch as still loading.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val matches: Flow<Matches> = screenState
        .map { it.query.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            val rows = if (query.isEmpty()) {
                allExercises
            } else {
                exerciseRepository.searchByPartialName(query)
            }
            rows.map { Matches(query = query, exercises = it) }
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
        // Built from the restored screen state rather than from nothing, and this is what makes
        // the restoration visible rather than merely correct. `combine` holds its first emission
        // until all four catalogue reads have answered; an empty initial value would draw the
        // search box blank and the new-exercise sheet closed for that whole window, and then
        // put both back — a screen that loses what the user typed and returns it a moment later
        // is indistinguishable, at the speed a thumb moves, from one that lost it.
        initialValue = buildState(
            catalogue = Catalogue(exercises = null, links = emptyMap(), taxonomy = emptyList(), records = emptyMap()),
            matches = Matches(query = screenState.value.query.trim(), exercises = null),
            screen = screenState.value,
        ),
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

            is ExerciseLibraryEvent.ExerciseToggled ->
                toggleSelection(event.exerciseId, exclusive = event.exclusive)

            is ExerciseLibraryEvent.ExerciseSelected ->
                select(event.exerciseId, exclusive = event.exclusive)

            ExerciseLibraryEvent.NewExerciseRequested ->
                screenState.update { it.copy(draft = ExerciseDraft()) }

            is ExerciseLibraryEvent.NewExerciseChanged ->
                screenState.update { it.copy(draft = event.draft.withDuplicateCheck()) }

            ExerciseLibraryEvent.NewExerciseSubmitted -> saveDraft()

            ExerciseLibraryEvent.NewExerciseDismissed -> screenState.update { it.copy(draft = null) }

            ExerciseLibraryEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    private fun toggleEquipment(equipment: EquipmentType) =
        screenState.update { state ->
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
     *
     * [exclusive] is the other question the same screen asks — which exercise goes in *this*
     * slot — and there the tap moves the choice rather than adding to it. Not a cap that
     * refuses the second tap: a picker that ignored it would leave the user tapping an
     * exercise that never ticks, with the one blocking it somewhere up a list of 873.
     */
    private fun toggleSelection(
        exerciseId: Long,
        exclusive: Boolean,
    ) = screenState.update { state ->
        val next = when {
            exerciseId in state.selection -> state.selection - exerciseId
            exclusive -> listOf(exerciseId)
            else -> state.selection + exerciseId
        }
        state.copy(selection = next)
    }

    /**
     * Add an exercise to the selection if it is not already there, keeping the picked order.
     *
     * Idempotent, which is what separates it from [toggleSelection] — see
     * [ExerciseLibraryEvent.ExerciseSelected] for the case that made the difference matter.
     */
    private fun select(
        exerciseId: Long,
        exclusive: Boolean,
    ) = screenState.update { state ->
        when {
            exerciseId in state.selection -> state
            exclusive -> state.copy(selection = listOf(exerciseId))
            else -> state.copy(selection = state.selection + exerciseId)
        }
    }

    /**
     * Write the draft, leaving the form up and inert while the write is in flight.
     *
     * The guard is [ExerciseDraft.canSave], which is the same predicate the save button is
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
                // Where the failure is *said* depends on whether the form is still up, and the
                // two places are exclusive. The sheet is drawn over the Scaffold that hosts the
                // snackbar, so a snackbar raised under an open sheet would run its whole
                // duration invisible and be marked shown; and a sheet the user swiped away
                // mid-write must not reappear carrying a draft they already dismissed.
                //
                // Hence reading the *current* draft rather than restoring the captured one:
                // it is the same value (the form is inert while saving) except in the one case
                // that matters, where it is null.
                screenState.update { state ->
                    val open = state.draft
                    if (open == null) {
                        state.copy(message = ExerciseLibraryMessage.SAVE_FAILED)
                    } else {
                        // The draft goes back the way it was, not away: what the user typed is
                        // the only copy of it, and a form that closed on failure would lose it.
                        state.copy(draft = open.copy(isSaving = false, saveFailed = true))
                    }
                }
            }
        }
    }

    /**
     * Flag a name the catalogue already holds for the same equipment — see [isDuplicateIn].
     *
     * Editing anything also clears [ExerciseDraft.saveFailed]: the message is about the write
     * that failed, and the user changing the form is them acting on it.
     */
    private fun ExerciseDraft.withDuplicateCheck(): ExerciseDraft =
        copy(isDuplicateName = isDuplicateIn(allExercises.value.orEmpty()), saveFailed = false)

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
        matches: Matches,
        screen: ScreenState,
    ): ExerciseLibraryUiState {
        // Loading is "no answer for what is on screen yet", not "no read has returned".
        // Either the catalogue itself has not landed, or the rows in hand answer a query the
        // user has already moved on from.
        val isLoading = catalogue.exercises == null ||
            matches.exercises == null ||
            matches.query != screen.query.trim()

        val visible = matches.exercises.orEmpty().filterBy(
            muscleGroupId = screen.muscleGroupId,
            equipment = screen.equipment,
            links = catalogue.links,
        )

        return ExerciseLibraryUiState(
            query = screen.query,
            muscleGroups = catalogue.taxonomy,
            selectedMuscleGroupId = screen.muscleGroupId,
            equipmentTypes = catalogue.exercises.orEmpty().equipmentVocabulary(),
            selectedEquipment = screen.equipment,
            sections = visible.groupIntoSections(catalogue.links, catalogue.records),
            catalogueSize = catalogue.exercises?.size ?: 0,
            matchCount = visible.size,
            isLoading = isLoading,
            selection = screen.selection,
            draft = screen.draft,
            message = screen.message,
        )
    }

    /**
     * What the screen shows and what the stores do not know about.
     *
     * `@Serializable` so it survives the process being killed with the library open — see
     * [savedScreenState]. [message] is the one thing that must not: it is a one-shot the screen
     * shows and reports back, and a snackbar replayed twenty minutes later would be announcing
     * an exercise the user watched appear in the list before they put the phone down.
     */
    @Serializable
    private data class ScreenState(
        val query: String = "",
        val muscleGroupId: Long? = null,
        val equipment: Set<EquipmentType> = emptySet(),
        val selection: List<Long> = emptyList(),
        val draft: ExerciseDraft? = null,
        @Transient
        val message: ExerciseLibraryMessage? = null,
    )

    /**
     * The four observed reads, as one value, so the outer combine takes three arguments.
     *
     * @param exercises null until the catalogue read comes back — see [allExercises].
     */
    private data class Catalogue(
        val exercises: List<Exercise>?,
        val links: Map<Long, List<ExerciseMuscleGroupLink>>,
        val taxonomy: List<MuscleGroup>,
        val records: Map<Long, PersonalRecord>,
    )

    /**
     * A search result and the query it answers.
     *
     * @param exercises null until the read behind [query] comes back.
     */
    private data class Matches(
        val query: String,
        val exercises: List<Exercise>?,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
