package com.opengym.feature.history.template

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.superset.Supersets
import com.opengym.core.domain.template.TemplateDuration
import com.opengym.core.domain.usecase.CreateTemplateUseCase
import com.opengym.core.domain.usecase.UpdateTemplateUseCase
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.ui.component.supersetGroupNames
import com.opengym.core.ui.component.supersetLabels
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.feature.history.navigation.NEW_TEMPLATE
import com.opengym.feature.history.navigation.TemplateEditorDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One template, opened to be changed — or built from nothing: its name, what it trains, and the
 * plan itself.
 *
 * ### Editing and creating are the same screen
 *
 * A template opened with [NEW_TEMPLATE] starts on an empty draft with the naming dialog already
 * up, and SAVE calls [CreateTemplateUseCase] instead of [UpdateTemplateUseCase]. Everything
 * between those two points — the picker, the reorder, the joins, the four figures, what SAVE is
 * gated on and what backing out asks — is the same code, because it is the same question. A
 * separate "new template" screen would be a second copy of all of it that has to keep agreeing
 * with this one.
 *
 * The other way a template comes into existence is a finished workout saved as one, which is
 * `SessionSummaryViewModel`'s and stays there: it has the sets and weights actually performed to
 * seed from, which is not something this screen has or wants.
 *
 * ### The reads are one-shot, unlike every other screen here
 *
 * This screen holds a *draft*. Observing the template and re-seeding from it would throw away
 * whatever the user had typed the moment anything else wrote — and the only thing that writes a
 * template is this screen and the tab behind it, neither of which can act while this one is up.
 * So the template, its exercises and the catalogue are read once, at open, and everything after
 * that is the user's own edit until SAVE.
 *
 * The unit system is read the same way and for a stronger reason: every weight here is *text the
 * user is editing*, and reformatting the column under them mid-edit would rewrite what they had
 * typed. Changing the setting means leaving this screen.
 *
 * ### What SAVE actually writes
 *
 * The whole template. `UpdateTemplateUseCase` takes a name, a category and an exercise list
 * together and replaces the stored body wholesale — see
 * [TemplateRepository.update] for why, and for what it costs: sessions started from this
 * template lose the link from their rows to the template lines they came from. The sessions
 * themselves are untouched.
 *
 * ### Where the superset rules are applied
 *
 * On the way *in* to every edit, not on the way out. A removal normalises — a group left with
 * one member dissolves — a reorder **tears**, because pulling a member away from its partner is
 * what "these are no longer performed back to back" looks like, and the two joins ask
 * [Supersets.join] and [Supersets.split]. What is stored is therefore always a well-formed
 * grouping, and the labels drawn beside the exercises are read off the same normalised list.
 */
@HiltViewModel
class TemplateEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val templateRepository: TemplateRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val createTemplate: CreateTemplateUseCase,
    private val updateTemplate: UpdateTemplateUseCase,
) : ViewModel() {

    private val templateId: Long = savedStateHandle.toRoute<TemplateEditorDestination>().templateId

    /** Whether this editor is building a template rather than changing one. See [NEW_TEMPLATE]. */
    private val isNew: Boolean = templateId == NEW_TEMPLATE

    private val editorState = MutableStateFlow(EditorState())

    /**
     * The unit every weight on this screen is read and typed in, and the rest every exercise
     * that does not name one is priced at.
     *
     * Both are read once, with the template — see the note on the class. `defaultRest` is not a
     * display value: it is what [TemplateDuration] charges an exercise with no rest of its own,
     * so an estimate computed without it would be the shipped default's estimate on the phone of
     * a user who has changed theirs.
     */
    private var unitSystem: UnitSystem = UnitSystem.METRIC
    private var defaultRest: Duration = Duration.ZERO

    /** The key the next exercise added in this session takes. See [TemplateExerciseRow.key]. */
    private var nextKey: Long = NEW_ROW_FIRST_KEY

    /**
     * Whether the picker now answering is one *this* screen opened.
     *
     * The ids come back through `:app`, which holds them in `rememberSaveable` — so they survive
     * the process being killed while the user is in the picker, and a ViewModel does not. The
     * editor that comes back is therefore a new one, and it has re-read the stored template:
     * appending to that draft hands back a plan that quietly lost every edit the user had made
     * and gained the picked lines on top of the old one, with `isDirty` claiming there is
     * something worth saving. An answer with no request behind it is an answer from a previous
     * life of this screen, and is refused and said out loud instead.
     *
     * A plain field rather than state: it has to survive a rotation, which the ViewModel does,
     * and *not* survive the process, which it also does. [isPickingExercises] cannot stand in —
     * it is cleared on every resume, including the one that brings the answer back.
     */
    private var pickWasRequested = false

    val uiState: StateFlow<TemplateEditorUiState> = editorState
        .map(::build)
        .stateIn(
            scope = viewModelScope,
            // The other screens' number, for the same reasons.
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = TemplateEditorUiState(isNew = isNew),
        )

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Read the template, its plan and the catalogue, and seed the draft from them.
     *
     * A template id that no longer resolves lands on [TemplateEditorUiState.isMissing] rather
     * than an empty editor: an editor for *that* would let the user build a plan that SAVE would
     * then refuse. [NEW_TEMPLATE] is the opposite case and not that one — there is nothing to
     * read, so the draft is seeded empty and the naming dialog opens over it.
     */
    private suspend fun load() {
        val preferences = userPreferencesRepository.observe().first()
        unitSystem = preferences.unitSystem
        defaultRest = preferences.defaultRestDuration

        if (isNew) {
            loadNew()
            return
        }

        val template = templateRepository.getById(templateId).first()
        if (template == null) {
            editorState.update { it.copy(isLoading = false, isMissing = true) }
            return
        }

        // Re-sorted rather than trusted, as `ApplyTemplateUseCase` does and for the same reason:
        // position order is what both the estimate and the superset grouping are read in.
        val exercises = templateRepository.getExercises(templateId).first().sortedBy { it.position }
        val catalogue = exerciseRepository.getAll().first().associateBy(Exercise::id)

        val seed = Draft(
            name = template.name,
            category = template.category,
            // Carried untouched rather than dropped: `UpdateTemplateUseCase` rewrites every
            // field it is given, so a save that omitted this would clear it — and this screen
            // does not draw it, which is exactly why it has to hand it back.
            icon = template.icon,
            rows = exercises.map { exercise ->
                DraftRow(
                    key = exercise.id,
                    exerciseId = exercise.exerciseId,
                    sets = exercise.defaultSets?.toString().orEmpty(),
                    reps = exercise.defaultReps?.toString().orEmpty(),
                    weight = exercise.defaultWeight
                        ?.let { formatWeightValue(it, unitSystem) }
                        .orEmpty(),
                    rest = exercise.restDuration?.toString().orEmpty(),
                    supersetGroup = exercise.supersetGroup,
                    // Not edited here and not dropped either, for the reason `icon` is kept.
                    notes = exercise.notes,
                )
            },
        )

        editorState.update {
            it.copy(
                draft = seed,
                // The same value, and that is the point: `isDirty` is this comparison, so a
                // screen nobody has touched cannot be saved.
                seed = seed,
                catalogue = catalogue,
                isLoading = false,
            )
        }
    }

    /**
     * Open on nothing: an empty plan, no name yet, and the naming dialog already up.
     *
     * The dialog rather than a screen that waits to be asked, because the name is the one thing
     * a template cannot be saved without and the header that opens it is a row with nothing in
     * it at this point — a control that looks like a heading, over a blank. Asking first is also
     * the order the flow actually goes in: a user opens this knowing what the workout is called
     * far more often than they know its fifth exercise.
     *
     * The empty draft is the seed as well as the draft, which is what leaves `isDirty` false: a
     * new editor the user immediately backs out of has nothing to ask about, and cancelling the
     * dialog without typing has to be free.
     */
    private suspend fun loadNew() {
        val catalogue = exerciseRepository.getAll().first().associateBy(Exercise::id)
        val seed = Draft(name = "", category = null, icon = null, rows = emptyList())

        editorState.update {
            it.copy(
                draft = seed,
                seed = seed,
                catalogue = catalogue,
                isLoading = false,
                naming = TemplateNamingDraft(name = "", category = null),
            )
        }
    }

    fun onEvent(event: TemplateEditorEvent) {
        if (onNameEvent(event)) return
        if (onPlanEvent(event)) return

        when (event) {
            TemplateEditorEvent.SaveRequested -> save()

            // Nothing to confirm when nothing has changed: the screen leaves on its own, which
            // it can tell from the same flag SAVE is gated on.
            TemplateEditorEvent.BackRequested ->
                if (build(editorState.value).isDirty && !editorState.value.isSaving) {
                    editorState.update { it.copy(confirmingDiscard = true) }
                }
            TemplateEditorEvent.DiscardConfirmed ->
                editorState.update { it.copy(confirmingDiscard = false, isSaved = true) }
            TemplateEditorEvent.DiscardDismissed ->
                editorState.update { it.copy(confirmingDiscard = false) }

            TemplateEditorEvent.MessageShown -> editorState.update { it.copy(message = null) }
            else -> Unit
        }
    }

    /**
     * The seven events that change the plan itself, handled together.
     *
     * Split off [onEvent] so neither `when` branches over two unrelated subjects: those are about
     * the screen — saving, leaving, a message shown — and these are about what the workout is.
     *
     * @return whether [event] was one of them.
     */
    private fun onPlanEvent(event: TemplateEditorEvent): Boolean {
        when (event) {
            is TemplateEditorEvent.FieldChanged -> editDraft { draft ->
                draft.mapRow(event.key) { it.withField(event.field, event.text) }
            }

            is TemplateEditorEvent.ExerciseRemoved -> editDraft { draft ->
                // Normalised rather than torn: a removal cannot split a group, it can only
                // leave one with a single member, and that dissolves. See `Supersets.normalize`.
                draft.copy(rows = draft.rows.filterNot { it.key == event.key }).normalized()
            }

            is TemplateEditorEvent.ExercisesReordered -> editDraft { draft -> draft.reorder(event.order) }

            is TemplateEditorEvent.SupersetLinked -> editDraft { draft -> draft.regroup(event.key, link = true) }
            is TemplateEditorEvent.SupersetUnlinked -> editDraft { draft -> draft.regroup(event.key, link = false) }

            TemplateEditorEvent.ExercisesPickRequested -> {
                pickWasRequested = true
                editorState.update { it.copy(isPickingExercises = true) }
            }
            TemplateEditorEvent.ExercisesPickClosed ->
                editorState.update { it.copy(isPickingExercises = false) }
            // Consumed, so the same answer cannot be applied twice, and refused when this
            // screen never asked — see [pickWasRequested].
            is TemplateEditorEvent.ExercisesPicked ->
                if (pickWasRequested) {
                    pickWasRequested = false
                    addExercises(event.exerciseIds)
                } else {
                    editorState.update { it.copy(message = TemplateEditorMessage.EDITOR_RESTARTED) }
                }

            else -> return false
        }
        return true
    }

    /**
     * The five events that only concern what the template is *called*, handled together.
     *
     * Split off [onEvent] so neither `when` branches over two unrelated subjects: those are
     * about the plan, and these are about its title.
     *
     * @return whether [event] was one of them.
     */
    private fun onNameEvent(event: TemplateEditorEvent): Boolean {
        when (event) {
            // Refused while a write is out, on the same terms as every other edit — see
            // [editDraft]. The header that raises this is drawn inert then, but the dialog it
            // opens is a second way into the draft and it has to answer to the same rule.
            TemplateEditorEvent.NameEditRequested -> editorState.update { state ->
                val draft = state.draft ?: return@update state
                if (state.isSaving) return@update state
                state.copy(
                    naming = TemplateNamingDraft(name = draft.name, category = draft.category),
                )
            }

            // Typing clears the refusal: it was about the name that *was* in the field, and an
            // error left under a name nobody has claimed reads as one that cannot be fixed.
            is TemplateEditorEvent.NameChanged -> editNaming {
                it.copy(name = event.text, nameTaken = false)
            }

            is TemplateEditorEvent.CategoryChanged -> editNaming { it.copy(category = event.category) }

            // Applied through [editDraft], so a name confirmed while a write is in flight is
            // dropped rather than written into a draft the save has already taken a copy of:
            // the store would keep the old name, the page would show the new one, and the save
            // landing closes the screen over the difference.
            TemplateEditorEvent.NameConfirmed -> {
                val naming = editorState.value.naming ?: return true
                // The name is trimmed on the way into the store as well (`validateTemplate`),
                // so what is kept here is what the user typed.
                editDraft { it.copy(name = naming.name, category = naming.category) }
                editorState.update { state ->
                    if (state.isSaving) state else state.copy(naming = null)
                }
            }

            TemplateEditorEvent.NameEditCancelled -> editorState.update { it.copy(naming = null) }

            else -> return false
        }
        return true
    }

    private fun editNaming(change: (TemplateNamingDraft) -> TemplateNamingDraft) {
        editorState.update { state ->
            val naming = state.naming ?: return@update state
            state.copy(naming = change(naming))
        }
    }

    /** Apply an edit to the plan, ignored while a write is out or before the read has landed. */
    private fun editDraft(change: (Draft) -> Draft) {
        editorState.update { state ->
            val draft = state.draft ?: return@update state
            if (state.isSaving) return@update state
            state.copy(draft = change(draft))
        }
    }

    /**
     * Append what the picker came back with, in the order it was picked.
     *
     * Appended rather than inserted, and with no defaults: a line the user has just added is one
     * they have not priced yet, and inventing three sets of ten for it would be the screen
     * putting numbers in their mouth. Exercises that are no longer in the catalogue are dropped
     * and said out loud — see [TemplateEditorMessage.EXERCISES_UNAVAILABLE].
     *
     * ### The catalogue is re-read here rather than trusted from the open
     *
     * The picker is the library in selecting mode and it can *create* an exercise. An id that is
     * missing from the map this screen loaded is therefore far more likely to be one written a
     * moment ago than one deleted — and judged against that map it would be dropped, with the
     * user told their brand new exercise is "no longer in your library". Re-reading costs one
     * query on a screen that has just come back from another one, and the rows added resolve to
     * their names instead of to the removed-exercise placeholder. It is the same read
     * `NewSessionViewModel.adopt` makes when the same picker answers it, for the same reason.
     */
    private fun addExercises(exerciseIds: List<Long>) {
        if (exerciseIds.isEmpty()) return

        viewModelScope.launch {
            val catalogue = exerciseRepository.getAll().first().associateBy(Exercise::id)
            val known = exerciseIds.filter { it in catalogue }
            // Keyed outside the update, which may run more than once: `nextKey` is this
            // ViewModel's counter, and a side effect inside a compare-and-set loop is one that
            // cannot say how many times it happened.
            val added = known.map { exerciseId -> DraftRow(key = nextKey--, exerciseId = exerciseId) }

            editorState.update { state ->
                val draft = state.draft ?: return@update state
                if (state.isSaving) return@update state

                state.copy(
                    draft = if (added.isEmpty()) draft else draft.copy(rows = draft.rows + added),
                    catalogue = catalogue,
                    message = if (known.size < exerciseIds.size) {
                        TemplateEditorMessage.EXERCISES_UNAVAILABLE
                    } else {
                        state.message
                    },
                )
            }
        }
    }

    /**
     * Write the whole template, leaving the page up and inert while the write is in flight.
     *
     * Two failure channels, because there are two kinds of failure. The use case *returns* its
     * refusals — the name, the body, an id that is not there — and the store *throws* when it
     * cannot be written at all. Catching only the first would leave the second to kill the
     * coroutine with an editor still on screen and nothing said.
     */
    private fun save() {
        val state = editorState.value
        val draft = state.draft ?: return
        // Asked of a state built from what is held rather than of [uiState], which is a
        // `WhileSubscribed` flow and holds its initial value whenever nothing is collecting.
        if (!build(state).canSave) return
        editorState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val outcome = try {
                write(draft)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                editorState.update {
                    it.copy(isSaving = false, message = TemplateEditorMessage.SAVE_FAILED)
                }
                return@launch
            }

            outcome
                .onSuccess { editorState.update { it.copy(isSaving = false, isSaved = true) } }
                .onFailure { failure -> reportSaveFailure(failure, draft) }
        }
    }

    /**
     * The write itself: an insert for a template that does not exist yet, a rewrite otherwise.
     *
     * The two use cases enforce the same rules and refuse in the same currency — see
     * [CreateTemplateUseCase] — so everything around this call is shared, and the only thing
     * either one of them can do that the other cannot is [TemplateError.NotFound], which belongs
     * to the id an insert does not have.
     */
    private suspend fun write(draft: Draft): Result<WorkoutTemplate> = if (isNew) {
        createTemplate(
            name = draft.name,
            exercises = draft.toDrafts(unitSystem),
            category = draft.category,
            icon = draft.icon,
        )
    } else {
        updateTemplate(
            templateId = templateId,
            name = draft.name,
            exercises = draft.toDrafts(unitSystem),
            category = draft.category,
            icon = draft.icon,
        )
    }

    /**
     * How a refused save is drawn.
     *
     * A name already taken reopens the naming dialog with the refusal under the field — the one
     * failure the user can fix in one move, and the field it is about is not otherwise on screen.
     * A template that has gone replaces the page with the dead end, because there is nothing left
     * to save into; only an update can be refused that way, since an insert has no id to be
     * missing. Everything else is said once and leaves the draft exactly where it is.
     */
    private fun reportSaveFailure(failure: Throwable, draft: Draft) {
        when (failure) {
            is TemplateError.DuplicateName -> editorState.update {
                it.copy(
                    isSaving = false,
                    naming = TemplateNamingDraft(
                        name = draft.name,
                        category = draft.category,
                        nameTaken = true,
                    ),
                )
            }

            is TemplateError.NotFound -> editorState.update {
                it.copy(isSaving = false, isMissing = true)
            }

            else -> editorState.update {
                it.copy(isSaving = false, message = TemplateEditorMessage.SAVE_FAILED)
            }
        }
    }

    /** Assemble the screen from the draft, resolving names and re-deriving everything derived. */
    private fun build(state: EditorState): TemplateEditorUiState {
        val draft = state.draft
            ?: return TemplateEditorUiState(
                isNew = isNew,
                isLoading = state.isLoading,
                isMissing = state.isMissing,
                naming = state.naming,
                message = state.message,
            )

        val grouping = draft.rows.map(DraftRow::supersetGroup)
        val labels = supersetLabels(grouping)
        // Beside the labels rather than parsed back out of them — see `supersetGroupNames`.
        val groupNames = supersetGroupNames(grouping)

        return TemplateEditorUiState(
            name = draft.name,
            category = draft.category,
            exercises = draft.rows.mapIndexed { index, row ->
                TemplateExerciseRow(
                    key = row.key,
                    exerciseId = row.exerciseId,
                    exercise = state.catalogue[row.exerciseId],
                    sets = row.sets,
                    reps = row.reps,
                    weight = row.weight,
                    rest = row.rest,
                    supersetGroup = row.supersetGroup,
                    supersetLabel = labels[index],
                    supersetGroupName = groupNames[index],
                    isLinkedToNext = row.supersetGroup != null &&
                        grouping.getOrNull(index + 1) == row.supersetGroup,
                    errors = row.errors(unitSystem),
                )
            },
            estimatedDuration = TemplateDuration.estimate(
                exercises = draft.toDrafts(unitSystem),
                defaultRest = defaultRest,
            ),
            unitSystem = unitSystem,
            naming = state.naming,
            isNew = isNew,
            isLoading = false,
            isMissing = state.isMissing,
            isSaving = state.isSaving,
            isDirty = draft != state.seed,
            isPickingExercises = state.isPickingExercises,
            confirmingDiscard = state.confirmingDiscard,
            isSaved = state.isSaved,
            message = state.message,
        )
    }

    private companion object {

        /**
         * Where the keys of exercises added in this session start, counting *down*.
         *
         * Negative so it cannot collide with a stored `TemplateExercise.id`, which is a positive
         * row id — the two share one key space because the list holds both.
         */
        const val NEW_ROW_FIRST_KEY = -1L

        /** The other screens' number, for the same reasons. */
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}

/**
 * Everything this ViewModel holds.
 *
 * One value rather than a draft flow and a screen-state flow beside it, because unlike the
 * screens that observe a store there is nothing here to combine *with*: the reads are one-shot
 * and land in this too. What that buys is that every edit is one atomic update.
 *
 * @param seed the draft as it was read. Kept whole rather than as a hash, because it is what
 *   `isDirty` compares against and a user who changes a set count back to what it was should get
 *   an inert SAVE again.
 */
private data class EditorState(
    val draft: Draft? = null,
    val seed: Draft? = null,
    val catalogue: Map<Long, Exercise> = emptyMap(),
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val naming: TemplateNamingDraft? = null,
    val isSaving: Boolean = false,
    val isPickingExercises: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val isSaved: Boolean = false,
    val message: TemplateEditorMessage? = null,
)

/** The template as it is being edited. Compared whole against the seed to answer "is it dirty". */
private data class Draft(
    val name: String,
    val category: TemplateCategory?,
    /** Not edited on this screen, and carried so that saving does not clear it. */
    val icon: String?,
    val rows: List<DraftRow>,
)

/** One line of the plan, holding what was typed rather than what it parses to. */
private data class DraftRow(
    val key: Long,
    val exerciseId: Long,
    val sets: String = "",
    val reps: String = "",
    val weight: String = "",
    val rest: String = "",
    val supersetGroup: Int? = null,
    /** Not edited on this screen, and carried so that saving does not clear it. */
    val notes: String? = null,
)

/** The row with one of its four figures replaced. */
private fun DraftRow.withField(field: TemplateField, text: String): DraftRow = when (field) {
    TemplateField.SETS -> copy(sets = text)
    TemplateField.REPS -> copy(reps = text)
    TemplateField.WEIGHT -> copy(weight = text)
    TemplateField.REST -> copy(rest = text)
}

/**
 * Which of this row's figures cannot be stored.
 *
 * A blank field is not an error — it is the template declining to say, which is what the
 * nullable columns are for. Everything else has to be a number inside the bounds
 * [SetValidator] holds a logged set to: a template whose defaults a set could not be logged from
 * is a plan the user would meet a refusal in the middle of.
 */
private fun DraftRow.errors(unitSystem: UnitSystem): Set<TemplateField> = buildSet {
    if (!sets.isValidCount(SET_COUNT_RANGE)) add(TemplateField.SETS)
    if (!reps.isValidCount(REP_COUNT_RANGE)) add(TemplateField.REPS)
    if (!rest.isValidCount(REST_SECONDS_RANGE)) add(TemplateField.REST)

    if (weight.isNotBlank()) {
        val milligrams = parseWeightMilligrams(weight, unitSystem)
        if (milligrams == null || milligrams !in SetValidator.WEIGHT_MIN_MG..SetValidator.WEIGHT_MAX_MG) {
            add(TemplateField.WEIGHT)
        }
    }
}

/** Whether this field holds nothing at all, or a whole number inside [range]. */
private fun String.isValidCount(range: LongRange): Boolean =
    isBlank() || toLongOrNull()?.let { it in range } == true

/** [SetValidator]'s own bounds on a logged set's reps, as the range this field is held to. */
private val REP_COUNT_RANGE = SetValidator.REPS_MIN.toLong()..SetValidator.REPS_MAX.toLong()

/**
 * How many sets a template line may plan.
 *
 * There is no `SetValidator` bound for this — a *session* counts its sets rather than declaring
 * them — so the upper end is a sanity limit rather than a domain rule: ninety-nine sets of one
 * exercise is a typo, and the field is three characters wide.
 */
private val SET_COUNT_RANGE = 1L..99L

/** Rest has a floor in [SetValidator] and no ceiling; a day is the same sanity limit as above. */
private val REST_SECONDS_RANGE = SetValidator.REST_MIN_SECONDS..SetValidator.DURATION_MAX_SECONDS

/** The plan as the drafts a write takes, with every figure parsed and the blanks left null. */
private fun Draft.toDrafts(unitSystem: UnitSystem): List<TemplateExerciseDraft> = rows.map { row ->
    TemplateExerciseDraft(
        exerciseId = row.exerciseId,
        defaultSets = row.sets.toIntOrNull(),
        defaultReps = row.reps.toIntOrNull(),
        defaultWeight = row.weight.takeIf { it.isNotBlank() }
            ?.let { parseWeightMilligrams(it, unitSystem) },
        restDuration = row.rest.toLongOrNull(),
        notes = row.notes,
        supersetGroup = row.supersetGroup,
    )
}

/** The draft with one row replaced. A key the plan no longer holds is a no-op. */
private fun Draft.mapRow(key: Long, change: (DraftRow) -> DraftRow): Draft =
    copy(rows = rows.map { if (it.key == key) change(it) else it })

/** The draft with its grouping in canonical form — groups of one dissolved, survivors renumbered. */
private fun Draft.normalized(): Draft = withGrouping(Supersets.normalize(rows.map(DraftRow::supersetGroup)))

/**
 * The draft rearranged into [order], with the grouping torn at whatever the move pulled apart.
 *
 * Keys the plan no longer holds are dropped and ones the order does not mention are appended in
 * the order they already had, so a stale order — one computed against a list an exercise has
 * since been cut from — rearranges what it can rather than losing a line.
 */
private fun Draft.reorder(order: List<Long>): Draft {
    val byKey = rows.associateBy(DraftRow::key)
    val moved = order.mapNotNull(byKey::get)
    val untouched = rows.filterNot { it.key in order }
    val rearranged = copy(rows = moved + untouched)
    return rearranged.withGrouping(Supersets.tear(rearranged.rows.map(DraftRow::supersetGroup)))
}

/**
 * The draft with the join below the row identified by [key] made or broken.
 *
 * The index is resolved *here*, against the draft's own rows, rather than taken from the screen:
 * the page is drawn in the order being dragged and the two lists differ for as long as a reorder
 * is being applied. A key the plan no longer holds — or one that is the last row, with nothing
 * below it to join to — is a no-op rather than a guess.
 *
 * A refused join leaves the draft alone. `Supersets.join` can only refuse a request this screen
 * cannot make — the control is drawn between two neighbours, which is the one ask that is always
 * contiguous — so there is nothing to report, only nothing to do.
 */
private fun Draft.regroup(key: Long, link: Boolean): Draft {
    val index = rows.indexOfFirst { it.key == key }
    if (index < 0 || index == rows.lastIndex) return this

    val grouping = rows.map(DraftRow::supersetGroup)
    val regrouped = if (link) {
        Supersets.join(grouping, index).getOrNull() ?: return this
    } else {
        Supersets.split(grouping, index)
    }
    return withGrouping(regrouped)
}

/** The draft carrying [grouping], which must be one entry per row and in the same order. */
private fun Draft.withGrouping(grouping: List<Int?>): Draft =
    copy(rows = rows.mapIndexed { index, row -> row.copy(supersetGroup = grouping[index]) })
