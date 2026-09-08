package com.opengym.feature.history.template

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.TemplateDraft
import com.opengym.core.domain.model.TemplateDraftExercise
import com.opengym.core.domain.model.TemplateDraftSet
import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.TemplateDraftRepository
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
import com.opengym.core.ui.state.savedScreenState
import com.opengym.feature.history.navigation.NEW_TEMPLATE
import com.opengym.feature.history.navigation.TemplateEditorDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
 * typed. Changing the setting means leaving this screen — and a restored draft keeps the unit it
 * was typed in rather than the one the setting now holds, which is the same promise across a
 * process death instead of across a minute.
 *
 * ### The draft is written down, and the rest of the screen is not (#183)
 *
 * A half-built template is minutes of work that exists in no table, so it goes to
 * [TemplateDraftRepository] — a file, atomically replaced, outside the schema for the reasons
 * PERSISTENCE.md gives for the workout draft beside it. That is deliberately *more* than saved
 * instance state: a `Bundle` goes with the task record, and the task record goes when the user
 * swipes the app out of Recents or the system runs short a day later.
 *
 * Everything else on the screen — the naming dialog and what is typed into it, the discard
 * confirmation, the picker request still out — is [ScreenState] in the [SavedStateHandle]. None
 * of it is work; all of it is *where the user was*, which is exactly the lifetime saved instance
 * state has. Two mechanisms because there are two questions, not because one of them was
 * insufficient.
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
    private val templateDrafts: TemplateDraftRepository,
) : ViewModel() {

    private val templateId: Long = savedStateHandle.toRoute<TemplateEditorDestination>().templateId

    /** Whether this editor is building a template rather than changing one. See [NEW_TEMPLATE]. */
    private val isNew: Boolean = templateId == NEW_TEMPLATE

    private val editorState = MutableStateFlow(EditorState())

    /**
     * The part of the screen that is not the plan: the naming dialog, the discard confirmation,
     * and the picker request still waiting on an answer.
     *
     * Saved state rather than the draft file, and the split is the whole point of this screen's
     * answer to #183. What the user would call *their work* is the plan, and it goes somewhere
     * that outlives the task; a dialog that happens to be up is not work, it is where they were,
     * and where they were is exactly what saved instance state is for. Writing a file every time
     * a confirmation opens would be the wrong tool used out of enthusiasm.
     *
     * The initial value is where a *new* editor's naming dialog comes from, and putting it here
     * rather than in [loadNew] is what makes it correct: the lambda runs only when there is
     * nothing saved to restore, which is precisely when the screen really is starting on its
     * opening question. Raising it from the load instead would reopen it over an editor that had
     * already been asked and answered.
     */
    private val screenState = savedStateHandle.savedScreenState(viewModelScope) {
        ScreenState(naming = TemplateNamingDraft(name = "", category = null).takeIf { isNew })
    }

    /**
     * The unit every weight on this screen is read and typed in, and the rest the estimate
     * prices every exercise at.
     *
     * Both are read once, with the template — see the note on the class. `defaultRest` is not a
     * display value: it is what [TemplateDuration] charges an exercise with no rest of its own,
     * so an estimate computed without it would be the shipped default's estimate on the phone of
     * a user who has changed theirs. This screen plans no rest, so it is what almost every
     * exercise is charged — see [TemplateDraftExercise.restDuration] for the ones that are not.
     */
    private var unitSystem: UnitSystem = UnitSystem.METRIC
    private var defaultRest: Duration = Duration.ZERO

    /** The key the next exercise added in this session takes. See [TemplateExerciseRow.key]. */
    private var nextKey: Long = NEW_ROW_FIRST_KEY

    /**
     * The key the next planned set takes. See [TemplateSetRow.key].
     *
     * Counts *up* from 1 and shares no space with [nextKey], which counts down from -1 over the
     * stored `TemplateExercise.id`s: a set has no stored identity to collide with — the rows are
     * replaced wholesale on every save — so the only thing this has to guarantee is that no two
     * sets of one exercise are ever handed the same key while the draft is open.
     */
    private var nextSetKey: Long = 1

    /**
     * The picker request this screen is still waiting on, or null when it is not waiting on one.
     *
     * Held rather than inferred, and consumed when the answer lands, because the answer comes
     * back through `:app` rather than from the picker directly: the ids sit in a `rememberSaveable`
     * on the nav host while the picker pops. So an answer can arrive that this editor never asked
     * for, and one that has already been applied can be redelivered by a recomposition. Both are
     * refused by the same check.
     *
     * It is [ScreenState]'s rather than a field for the reason the draft is a file's: the request
     * has to outlive the process, or a user who left the app while choosing exercises would come
     * back to an editor that no longer knows what it asked. [ScreenState.isPickingExercises]
     * cannot stand in — it is cleared on every resume, including the one that brings the answer
     * back.
     */
    private val pickRequest: PickRequest? get() = screenState.value.pick

    val uiState: StateFlow<TemplateEditorUiState> = combine(editorState, screenState, ::build)
        .stateIn(
            scope = viewModelScope,
            // The other screens' number, for the same reasons.
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            // Seeded from the restored screen state, so a naming dialog the process died with
            // open is on the first frame rather than appearing once the reads land.
            initialValue = build(EditorState(), screenState.value),
        )

    init {
        viewModelScope.launch {
            load()
            mirrorDraft()
        }
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

        // Read before anything is built from [unitSystem], because a restored draft is what
        // decides it — see [restoredDraft]. A seed formatted in one unit beside a draft typed in
        // another is two columns of numbers that cannot be compared, which is what `isDirty` is.
        val restored = restoredDraft()

        if (isNew) {
            loadNew(restored)
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

        val seed = TemplateDraft(
            templateId = templateId,
            name = template.name,
            category = template.category,
            // Carried untouched rather than dropped: `UpdateTemplateUseCase` rewrites every
            // field it is given, so a save that omitted this would clear it — and this screen
            // does not draw it, which is exactly why it has to hand it back.
            icon = template.icon,
            unitSystem = unitSystem,
            exercises = exercises.map { exercise ->
                TemplateDraftExercise(
                    key = exercise.id,
                    exerciseId = exercise.exerciseId,
                    sets = exercise.sets.map { planned ->
                        TemplateDraftSet(
                            key = nextSetKey++,
                            setType = planned.setType,
                            weight = planned.weight
                                ?.let { formatWeightValue(it, unitSystem) }
                                .orEmpty(),
                            reps = planned.reps?.toString().orEmpty(),
                        )
                    },
                    supersetGroup = exercise.supersetGroup,
                    // Neither of these is edited here, and neither is dropped, for the reason
                    // `icon` is kept.
                    restDuration = exercise.restDuration,
                    notes = exercise.notes,
                )
            },
        )

        editorState.update {
            it.copy(
                // The saved draft when there is one, and the stored template otherwise. The two
                // are the same value on the common path, which is what makes an editor nobody
                // has touched read as clean.
                draft = restored ?: seed,
                // The stored template either way, and that is the point: `isDirty` is this
                // comparison, so a restored draft is dirty exactly where it differs from what
                // the database holds — which is what SAVE is about to be gated on.
                seed = seed,
                catalogue = catalogue,
                isLoading = false,
            )
        }
    }

    /**
     * The saved draft, when there is one for *this* template, with the key counters wound past it.
     *
     * Null covers every way there is nothing to restore: no draft was saved, one was saved and
     * this build cannot read it, or the one on disk belongs to a template the user is not
     * editing. The last is the one worth naming — the draft is a slot rather than a collection
     * (see [TemplateDraftRepository]) and an editor opened on a different template must not be
     * handed somebody else's plan.
     *
     * The unit system comes off the draft rather than off the store, and that is deliberate: the
     * figures in it are *text typed in one unit*, and reformatting a column of numbers under
     * somebody who left the app mid-edit is the thing this whole screen reads the setting once
     * to avoid. See [TemplateDraft.unitSystem].
     *
     * The counters are wound on for the reason they exist. [nextKey] hands out identities for
     * lines added since the last save, counting down from -1 over the stored ids; [nextSetKey]
     * counts up. A restored draft already holds keys from both, and a counter that started over
     * would hand out one the plan is already using — two rows with one identity, and a keystroke
     * that lands in both.
     */
    private suspend fun restoredDraft(): TemplateDraft? {
        val restored = templateDrafts.load()?.takeIf { it.templateId == templateId } ?: return null

        unitSystem = restored.unitSystem
        nextKey = minOf(NEW_ROW_FIRST_KEY, (restored.exercises.minOfOrNull { it.key } ?: 0L) - 1)
        nextSetKey = maxOf(nextSetKey, (restored.exercises.flatMap { it.sets }.maxOfOrNull { it.key } ?: 0L) + 1)

        return restored
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
    private suspend fun loadNew(restored: TemplateDraft?) {
        val catalogue = exerciseRepository.getAll().first().associateBy(Exercise::id)
        val seed = TemplateDraft(templateId = templateId, unitSystem = unitSystem)

        editorState.update {
            it.copy(
                draft = restored ?: seed,
                seed = seed,
                catalogue = catalogue,
                isLoading = false,
            )
        }

        // The one case the opening state cannot know about: the task record went and the draft
        // file did not, so the dialog is up on its first question over a template that already
        // has a name. Put that name in the field rather than asking as though nothing had been
        // written. A dialog the user was actually typing into came back through saved state with
        // their text in it, and is left exactly as it is.
        if (restored != null) {
            screenState.update { state ->
                val naming = state.naming ?: return@update state
                if (naming.name.isEmpty() && naming.category == null) {
                    state.copy(naming = naming.copy(name = restored.name, category = restored.category))
                } else {
                    state
                }
            }
        }
    }

    /**
     * Write the draft down as it changes, and take it away once there is nothing to keep.
     *
     * Started after [load] rather than beside it, and the order is load-bearing: the first thing
     * this sees has to be the seeded state, or a clean editor would clear the very draft [load]
     * is about to restore from.
     *
     * **Dirty is the condition**, not "has been edited". A draft equal to the stored template is
     * one the user would lose nothing by losing, and keeping it would mean every template merely
     * *opened* leaves a file behind for the next editor to reason about. The same clause covers
     * the two ways this screen ends: a save that landed and a discard the user confirmed both
     * set [EditorState.isSaved], and both mean the draft has served its purpose.
     *
     * **The slot is taken, not shared.** A clean editor clears whatever is stored, including a
     * draft left by an editor for a *different* template. That cannot cost anybody anything they
     * could have got back: the editor is one destination, so reaching a second one means leaving
     * the first — which saves or discards its draft on the way out — and a draft that outlived
     * its task record is one no navigation can return to. See [TemplateDraftRepository].
     *
     * `distinctUntilChanged` because [editorState] moves for reasons the draft does not — a
     * dialog opening, a catalogue re-read. Without it every one of those would be a file write.
     *
     * A failure here is swallowed. The draft is a convenience, the editor is still holding
     * everything in memory, and a disk error on a screen somebody is typing into must not be
     * what takes the process down — the argument every write in this app makes.
     */
    private suspend fun mirrorDraft() {
        editorState
            .map { state -> state.draft?.takeUnless { state.isSaved || it == state.seed } }
            .distinctUntilChanged()
            .collect { draft ->
                try {
                    if (draft == null) templateDrafts.clear() else templateDrafts.save(draft)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Nothing to say and nothing to do: see the note above.
                }
            }
    }

    fun onEvent(event: TemplateEditorEvent) {
        if (onNameEvent(event)) return
        if (onSetEvent(event)) return
        if (onPlanEvent(event)) return

        when (event) {
            TemplateEditorEvent.SaveRequested -> save()

            // Nothing to confirm when nothing has changed: the screen leaves on its own, which
            // it can tell from the same flag SAVE is gated on.
            TemplateEditorEvent.BackRequested ->
                if (build(editorState.value, screenState.value).isDirty && !editorState.value.isSaving) {
                    screenState.update { it.copy(confirmingDiscard = true) }
                }

            TemplateEditorEvent.DiscardConfirmed -> {
                screenState.update { it.copy(confirmingDiscard = false) }
                // Which is also what takes the saved draft away — see [mirrorDraft]. A discard
                // the user confirmed is the one case where losing the plan is the point.
                editorState.update { it.copy(isSaved = true) }
            }

            TemplateEditorEvent.DiscardDismissed ->
                screenState.update { it.copy(confirmingDiscard = false) }

            TemplateEditorEvent.MessageShown -> screenState.update { it.copy(message = null) }

            else -> Unit
        }
    }

    /**
     * The four events about one exercise's sets — what is in them, and how many there are.
     *
     * Split off [onPlanEvent] on the same principle that splits *it* off [onEvent]: one `when`
     * per subject. These are about what an exercise asks of the lifter; those are about which
     * exercises the workout holds and in what order.
     *
     * @return whether [event] was one of them.
     */
    private fun onSetEvent(event: TemplateEditorEvent): Boolean {
        when (event) {
            is TemplateEditorEvent.SetFieldChanged -> editDraft { draft ->
                draft.mapSet(event.exerciseKey, event.setKey) { it.withField(event.field, event.text) }
            }

            is TemplateEditorEvent.SetTypeChanged -> editDraft { draft ->
                draft.mapSet(event.exerciseKey, event.setKey) { it.copy(setType = event.setType) }
            }

            // Keyed outside [editDraft], which may run its lambda more than once: the counter is
            // this ViewModel's, and a side effect inside a compare-and-set loop cannot say how
            // many times it happened. See [addExercises], which does the same.
            is TemplateEditorEvent.SetAdded -> {
                val key = nextSetKey++
                editDraft { draft -> draft.mapRow(event.exerciseKey) { it.withSetAdded(key) } }
            }

            is TemplateEditorEvent.SetRemoved -> editDraft { draft ->
                draft.mapRow(event.exerciseKey) { row ->
                    row.copy(sets = row.sets.filterNot { it.key == event.setKey })
                }
            }

            else -> return false
        }
        return true
    }

    /**
     * The seven events that change which exercises the plan holds, and in what order.
     *
     * Split off [onEvent] so neither `when` branches over two unrelated subjects: those are about
     * the screen — saving, leaving, a message shown — and these are about what the workout is.
     * What happens *inside* one exercise is [onSetEvent].
     *
     * @return whether [event] was one of them.
     */
    private fun onPlanEvent(event: TemplateEditorEvent): Boolean {
        when (event) {
            is TemplateEditorEvent.ExerciseRemoved -> editDraft { draft ->
                // Normalised rather than torn: a removal cannot split a group, it can only
                // leave one with a single member, and that dissolves. See `Supersets.normalize`.
                draft.copy(exercises = draft.exercises.filterNot { it.key == event.key }).normalized()
            }

            is TemplateEditorEvent.ExercisesReordered -> editDraft { draft -> draft.reorder(event.order) }

            is TemplateEditorEvent.SupersetLinked -> editDraft { draft -> draft.regroup(event.key, link = true) }

            is TemplateEditorEvent.SupersetUnlinked -> editDraft { draft -> draft.regroup(event.key, link = false) }

            is TemplateEditorEvent.ExerciseSwapRequested ->
                screenState.update { it.copy(pick = PickRequest.Swap(event.key), isPickingExercises = true) }

            // The request replaces whatever was there, so a picker backed out of and reopened
            // from the other control cannot answer the wrong question.
            TemplateEditorEvent.ExercisesPickRequested ->
                screenState.update { it.copy(pick = PickRequest.Add, isPickingExercises = true) }

            // The picker is gone, which is not the same as it having answered: the request
            // stands until the answer lands or another one replaces it.
            TemplateEditorEvent.ExercisesPickClosed ->
                screenState.update { it.copy(isPickingExercises = false) }

            // Consumed, so the same answer cannot be applied twice, and refused when this
            // screen never asked — see [pickRequest].
            is TemplateEditorEvent.ExercisesPicked ->
                when (val request = pickRequest) {
                    null -> screenState.update { it.copy(message = TemplateEditorMessage.EDITOR_RESTARTED) }

                    PickRequest.Add -> {
                        screenState.update { it.copy(pick = null) }
                        addExercises(event.exerciseIds)
                    }

                    is PickRequest.Swap -> {
                        screenState.update { it.copy(pick = null) }
                        // The first of the list rather than the whole of it: a slot holds one
                        // exercise, and a picker opened for a swap is drawn as a single choice —
                        // see `ExercisePickerDestination.replacing`. Read this way all the same,
                        // because this is the one place a second answer would otherwise turn a
                        // swap into an edit of the whole plan.
                        swapExercise(request.key, event.exerciseIds.firstOrNull())
                    }
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
            TemplateEditorEvent.NameEditRequested -> {
                val editor = editorState.value
                val draft = editor.draft
                if (draft != null && !editor.isSaving) {
                    screenState.update {
                        it.copy(naming = TemplateNamingDraft(name = draft.name, category = draft.category))
                    }
                }
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
                val naming = screenState.value.naming ?: return true
                // The name is trimmed on the way into the store as well (`validateTemplate`),
                // so what is kept here is what the user typed.
                editDraft { it.copy(name = naming.name, category = naming.category) }
                if (!editorState.value.isSaving) screenState.update { it.copy(naming = null) }
            }

            TemplateEditorEvent.NameEditCancelled -> screenState.update { it.copy(naming = null) }

            else -> return false
        }
        return true
    }

    private fun editNaming(change: (TemplateNamingDraft) -> TemplateNamingDraft) {
        screenState.update { state ->
            val naming = state.naming ?: return@update state
            state.copy(naming = change(naming))
        }
    }

    /** Apply an edit to the plan, ignored while a write is out or before the read has landed. */
    private fun editDraft(change: (TemplateDraft) -> TemplateDraft) {
        editorState.update { state ->
            val draft = state.draft ?: return@update state
            if (state.isSaving) return@update state
            state.copy(draft = change(draft))
        }
    }

    /**
     * Append what the picker came back with, in the order it was picked.
     *
     * Appended rather than inserted, and with one blank set each: a line the user has just added
     * is one they have not priced yet, so the figures are empty, but the *table* is there —
     * inventing three sets of ten would be the screen putting numbers in their mouth, and
     * offering an empty card with an "add a set" invitation on it would put a tap in front of the
     * thing the screen exists to do. Exercises that are no longer in the catalogue are dropped
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
            val added = known.map { exerciseId ->
                TemplateDraftExercise(
                    key = nextKey--,
                    exerciseId = exerciseId,
                    sets = listOf(TemplateDraftSet(key = nextSetKey++)),
                )
            }

            editorState.update { state ->
                val draft = state.draft ?: return@update state
                if (state.isSaving) return@update state

                state.copy(
                    draft = if (added.isEmpty()) draft else draft.copy(exercises = draft.exercises + added),
                    catalogue = catalogue,
                )
            }

            if (known.size < exerciseIds.size) {
                screenState.update { it.copy(message = TemplateEditorMessage.EXERCISES_UNAVAILABLE) }
            }
        }
    }

    /**
     * Put a different movement in one slot of the plan, leaving everything else about it alone.
     *
     * ### The sets travel with the slot
     *
     * The row keeps its position, its superset, its notes **and its planned sets**. Only the
     * movement changes. That is the one thing this does differently from the workout screen's
     * swap, which refuses outright once anything is logged — see `SwapSessionExerciseUseCase`.
     *
     * The difference is what the sets *are*. A logged set is a record of something that happened,
     * and carrying it onto another movement would make the history say the user benched what they
     * inclined. A planned set is a suggestion the user is in the middle of writing: the figures
     * sit in a table directly under this control, blank is a legal value for every one of them,
     * and a lifter swapping barbell bench for dumbbell bench is nearly always keeping the 4 × 8
     * and retyping the weight. Clearing them on their behalf would be the destructive reading of
     * an edit that is meant to be the mild one, and there is no undo on this page.
     *
     * ### The catalogue is re-read, for [addExercises]' reason
     *
     * The picker can create an exercise, so an id missing from the map this screen opened with is
     * far more likely to be one written a moment ago than one deleted.
     *
     * @param key the slot, by [TemplateDraftExercise.key].
     * @param exerciseId what to put in it, or null for a picker that came back with nothing —
     *   which is not a failure and says nothing: the user backed out.
     */
    private fun swapExercise(
        key: Long,
        exerciseId: Long?,
    ) {
        if (exerciseId == null) return

        viewModelScope.launch {
            val catalogue = exerciseRepository.getAll().first().associateBy(Exercise::id)
            val known = exerciseId in catalogue

            editorState.update { state ->
                val draft = state.draft ?: return@update state
                if (state.isSaving) return@update state

                state.copy(
                    draft = if (known) draft.mapRow(key) { it.copy(exerciseId = exerciseId) } else draft,
                    catalogue = catalogue,
                )
            }

            if (!known) screenState.update { it.copy(message = TemplateEditorMessage.EXERCISE_UNAVAILABLE) }
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
        if (!build(state, screenState.value).canSave) return
        editorState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val outcome = try {
                write(draft)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                editorState.update { it.copy(isSaving = false) }
                screenState.update { it.copy(message = TemplateEditorMessage.SAVE_FAILED) }
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
    private suspend fun write(draft: TemplateDraft): Result<WorkoutTemplate> =
        if (isNew) {
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
    private fun reportSaveFailure(
        failure: Throwable,
        draft: TemplateDraft,
    ) {
        editorState.update { it.copy(isSaving = false, isMissing = failure is TemplateError.NotFound) }

        when (failure) {
            is TemplateError.DuplicateName -> screenState.update {
                it.copy(
                    naming = TemplateNamingDraft(
                        name = draft.name,
                        category = draft.category,
                        nameTaken = true,
                    ),
                )
            }

            // Nothing to say: the page is the dead end now, and it says it.
            is TemplateError.NotFound -> Unit

            else -> screenState.update { it.copy(message = TemplateEditorMessage.SAVE_FAILED) }
        }
    }

    /**
     * Assemble the screen from the draft, resolving names and re-deriving everything derived.
     *
     * Two arguments because the screen is two things with different lifetimes — the plan, which
     * outlives the process in a file, and where the user was in it, which outlives the process in
     * saved state. See [screenState].
     */
    private fun build(
        state: EditorState,
        screen: ScreenState,
    ): TemplateEditorUiState {
        val draft = state.draft
            ?: return TemplateEditorUiState(
                isNew = isNew,
                isLoading = state.isLoading,
                isMissing = state.isMissing,
                naming = screen.naming,
                message = screen.message,
            )

        val grouping = draft.exercises.map(TemplateDraftExercise::supersetGroup)
        val labels = supersetLabels(grouping)
        // Beside the labels rather than parsed back out of them — see `supersetGroupNames`.
        val groupNames = supersetGroupNames(grouping)

        return TemplateEditorUiState(
            name = draft.name,
            category = draft.category,
            exercises = draft.exercises.mapIndexed { index, row ->
                TemplateExerciseRow(
                    key = row.key,
                    exerciseId = row.exerciseId,
                    exercise = state.catalogue[row.exerciseId],
                    sets = row.sets.map { set ->
                        TemplateSetRow(
                            key = set.key,
                            setType = set.setType,
                            weight = set.weight,
                            reps = set.reps,
                            errors = set.errors(unitSystem),
                        )
                    },
                    supersetGroup = row.supersetGroup,
                    supersetLabel = labels[index],
                    supersetGroupName = groupNames[index],
                    isLinkedToNext = row.supersetGroup != null &&
                        grouping.getOrNull(index + 1) == row.supersetGroup,
                )
            },
            estimatedDuration = TemplateDuration.estimate(
                exercises = draft.toDrafts(unitSystem),
                defaultRest = defaultRest,
            ),
            unitSystem = unitSystem,
            naming = screen.naming,
            isNew = isNew,
            isLoading = false,
            isMissing = state.isMissing,
            isSaving = state.isSaving,
            isDirty = draft != state.seed,
            isPickingExercises = screen.isPickingExercises,
            confirmingDiscard = screen.confirmingDiscard,
            isSaved = state.isSaved,
            message = screen.message,
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
    val draft: TemplateDraft? = null,
    val seed: TemplateDraft? = null,
    val catalogue: Map<Long, Exercise> = emptyMap(),
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
)

/**
 * Where the user is on the screen, as opposed to what they have built.
 *
 * Saved instance state, which is the right lifetime for every field here: a dialog that is up, a
 * confirmation that is pending, a picker that has been sent for. None of it is work — losing it
 * costs a tap — and none of it is worth a file. The plan is the other half and is a file; see
 * [TemplateEditorViewModel.screenState] for the split and [TemplateDraftRepository] for why the
 * plan needs more than this.
 *
 * @param naming the name-and-category dialog's contents, or null when it is closed. Typed text,
 *   which is why the dialog being open is not enough to save on its own.
 * @param isPickingExercises the picker is drawn over this screen. Cleared on every resume, which
 *   is what makes it different from [pick] — see `TemplateEditorRoute`.
 * @param pick the picker request still waiting on an answer.
 * @param message a one-shot to show and forget. Not saved: it is about something that happened
 *   before the user put the phone down, and replaying it twenty minutes later would be the
 *   screen reporting an event they already watched.
 */
@Serializable
private data class ScreenState(
    val naming: TemplateNamingDraft? = null,
    val isPickingExercises: Boolean = false,
    val pick: PickRequest? = null,
    @Transient
    val message: TemplateEditorMessage? = null,
    val confirmingDiscard: Boolean = false,
)

/**
 * What the exercise picker was opened to answer.
 *
 * Two questions rather than a nullable key, because "add these to the plan" and "put this one in
 * that slot" are answered differently and by different amounts of the picker's reply — see
 * `ExercisePickerDestination.replacing`.
 */
@Serializable
private sealed interface PickRequest {

    /** Append what comes back to the end of the plan. */
    @Serializable
    data object Add : PickRequest

    /** Put the first thing that comes back into the slot [key] names, and change nothing else. */
    @Serializable
    data class Swap(val key: Long) : PickRequest
}

/** The set with one of its two figures replaced. */
private fun TemplateDraftSet.withField(
    field: TemplateSetField,
    text: String,
): TemplateDraftSet =
    when (field) {
        TemplateSetField.WEIGHT -> copy(weight = text)
        TemplateSetField.REPS -> copy(reps = text)
    }

/**
 * The line with another set on the end of it, copied from the one it follows.
 *
 * The copy is the point: a lifter planning three working sets at the same weight taps ADD SET
 * twice rather than typing six figures, and one planning a drop set after them retypes the two
 * that differ. An exercise with nothing planned yet has nothing to copy and gets a blank set.
 *
 * Refused at the ceiling rather than clamped silently — see [MAX_SETS_PER_EXERCISE]. There is
 * nothing to report: the control is drawn inert at that point, so this only catches a tap that
 * was already in flight.
 */
private fun TemplateDraftExercise.withSetAdded(key: Long): TemplateDraftExercise {
    if (sets.size >= MAX_SETS_PER_EXERCISE) return this
    val copied = sets.lastOrNull()?.copy(key = key) ?: TemplateDraftSet(key = key)
    return copy(sets = sets + copied)
}

/**
 * Which of this set's figures cannot be stored.
 *
 * A blank field is not an error — it is the plan declining to say, which is what the nullable
 * columns are for. Everything else has to be a number inside the bounds [SetValidator] holds a
 * logged set to: a template whose sets a session could not log is a plan the user would meet a
 * refusal in the middle of.
 */
private fun TemplateDraftSet.errors(unitSystem: UnitSystem): Set<TemplateSetField> =
    buildSet {
        if (!reps.isValidCount(REP_COUNT_RANGE)) add(TemplateSetField.REPS)

        if (weight.isNotBlank()) {
            val milligrams = parseWeightMilligrams(weight, unitSystem)
            if (milligrams == null || milligrams !in SetValidator.WEIGHT_MIN_MG..SetValidator.WEIGHT_MAX_MG) {
                add(TemplateSetField.WEIGHT)
            }
        }
    }

/** Whether this field holds nothing at all, or a whole number inside [range]. */
private fun String.isValidCount(range: LongRange): Boolean = isBlank() || toLongOrNull()?.let { it in range } == true

/** [SetValidator]'s own bounds on a logged set's reps, as the range this field is held to. */
private val REP_COUNT_RANGE = SetValidator.REPS_MIN.toLong()..SetValidator.REPS_MAX.toLong()

/** The plan as the drafts a write takes, with every figure parsed and the blanks left null. */
private fun TemplateDraft.toDrafts(unitSystem: UnitSystem): List<TemplateExerciseDraft> =
    exercises.map { row ->
        TemplateExerciseDraft(
            exerciseId = row.exerciseId,
            sets = row.sets.map { set ->
                PlannedSet(
                    setType = set.setType,
                    reps = set.reps.toIntOrNull(),
                    weight = set.weight.takeIf { it.isNotBlank() }
                        ?.let { parseWeightMilligrams(it, unitSystem) },
                )
            },
            restDuration = row.restDuration,
            notes = row.notes,
            supersetGroup = row.supersetGroup,
        )
    }

/** The draft with one row replaced. A key the plan no longer holds is a no-op. */
private fun TemplateDraft.mapRow(
    key: Long,
    change: (TemplateDraftExercise) -> TemplateDraftExercise,
): TemplateDraft = copy(exercises = exercises.map { if (it.key == key) change(it) else it })

/**
 * The draft with one set of one row replaced. A key either list no longer holds is a no-op.
 *
 * Both keys, rather than the set's alone: set keys are unique across the draft as it stands, but
 * they are handed out by a counter that a caller has no contract with, and resolving a keystroke
 * against every exercise at once would make that an invariant the screen depends on.
 */
private fun TemplateDraft.mapSet(
    exerciseKey: Long,
    setKey: Long,
    change: (TemplateDraftSet) -> TemplateDraftSet,
): TemplateDraft =
    mapRow(exerciseKey) { row ->
        row.copy(sets = row.sets.map { if (it.key == setKey) change(it) else it })
    }

/** The draft with its grouping in canonical form — groups of one dissolved, survivors renumbered. */
private fun TemplateDraft.normalized(): TemplateDraft =
    withGrouping(Supersets.normalize(exercises.map(TemplateDraftExercise::supersetGroup)))

/**
 * The draft rearranged into [order], with the grouping torn at whatever the move pulled apart.
 *
 * Keys the plan no longer holds are dropped and ones the order does not mention are appended in
 * the order they already had, so a stale order — one computed against a list an exercise has
 * since been cut from — rearranges what it can rather than losing a line.
 */
private fun TemplateDraft.reorder(order: List<Long>): TemplateDraft {
    val byKey = exercises.associateBy(TemplateDraftExercise::key)
    val moved = order.mapNotNull(byKey::get)
    val untouched = exercises.filterNot { it.key in order }
    val rearranged = copy(exercises = moved + untouched)
    return rearranged.withGrouping(Supersets.tear(rearranged.exercises.map(TemplateDraftExercise::supersetGroup)))
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
private fun TemplateDraft.regroup(
    key: Long,
    link: Boolean,
): TemplateDraft {
    val index = exercises.indexOfFirst { it.key == key }
    if (index < 0 || index == exercises.lastIndex) return this

    val grouping = exercises.map(TemplateDraftExercise::supersetGroup)
    val regrouped = if (link) {
        Supersets.join(grouping, index).getOrNull() ?: return this
    } else {
        Supersets.split(grouping, index)
    }
    return withGrouping(regrouped)
}

/** The draft carrying [grouping], which must be one entry per row and in the same order. */
private fun TemplateDraft.withGrouping(grouping: List<Int?>): TemplateDraft =
    copy(exercises = exercises.mapIndexed { index, row -> row.copy(supersetGroup = grouping[index]) })
