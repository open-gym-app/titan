package com.opengym.feature.session.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.stats.MuscleVolumeCalculator
import com.opengym.core.domain.stats.SessionStatsCalculator
import com.opengym.core.domain.usecase.CreateTemplateUseCase
import com.opengym.core.domain.usecase.RenameSessionUseCase
import com.opengym.core.ui.component.muscleContributions
import com.opengym.feature.session.navigation.SessionSummaryDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * What a session amounted to: the figures, the records, the muscles, the exercises.
 *
 * ### Everything is recomputed, nothing is read back
 *
 * `SessionSummary` — the value `FinishSessionUseCase` produces — is deliberately *not* used
 * here. It is a snapshot of four totals taken at the moment the session closed, and this
 * screen needs the per-exercise and per-muscle breakdowns that were never in it. Reading the
 * sets and running the same calculators the domain runs gives the same totals by
 * construction, which is what "stats match `SessionStatsCalculator` exactly" actually asks
 * for — a second, independently maintained copy of the arithmetic is the thing that would
 * eventually disagree.
 *
 * It is also what makes the historical mode possible at all: a session logged last March has
 * a row and its sets, and no `SessionSummary` anywhere.
 *
 * ### Which records count as "set during this session"
 *
 * A [PersonalRecord] points at the `workout_set` row it was read off, so the records this
 * session set are the ones whose `workoutSetId` is one of this session's sets. That is
 * derived here rather than queried, because the PR engine (#30) has not landed and
 * `PersonalRecordRepository` exposes exactly one read — the headline record per exercise.
 *
 * Two consequences worth naming, both of which go when #30 does:
 *
 * - **at most one record per exercise surfaces**, so a session that set both a heaviest and a
 *   most-reps on the bench shows the heaviest and says nothing about the other;
 * - until the engine writes anything the table is empty, so the section is simply absent —
 *   which is what #27 asks for, rather than blocking on #30.
 *
 * ### Why there is no clock
 *
 * `SessionStatsCalculator` needs an end for its window and takes "now" for a session that is
 * still running. This screen only ever shows one that is not: the just-finished mode is
 * reached after `FinishSessionUseCase` has stamped `completedAt`, and the historical mode
 * shows a logged session. The fallback for a row somehow carrying neither is `lastActiveAt` —
 * the last moment the session is known to have been running — rather than the current time,
 * which would make a static recap's duration tick upwards while it was being read.
 */
@HiltViewModel
class SessionSummaryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    exerciseRepository: ExerciseRepository,
    personalRecordRepository: PersonalRecordRepository,
    private val createTemplate: CreateTemplateUseCase,
    private val renameSession: RenameSessionUseCase,
) : ViewModel() {

    private val destination: SessionSummaryDestination = savedStateHandle.toRoute()

    private val mode: SessionSummaryMode = if (destination.historical) {
        SessionSummaryMode.HISTORICAL
    } else {
        SessionSummaryMode.JUST_FINISHED
    }

    /** What the user is in the middle of, which the store has no opinion about. */
    private val screenState = MutableStateFlow(ScreenState())

    /**
     * The session itself: the row, its exercises and every set logged against it.
     *
     * Three reads combined into one value rather than three arms of the outer combine, so the
     * arithmetic has a single object to be a pure function of — and so `null` means one thing,
     * "no read has come back yet", instead of three flags that can disagree about it.
     *
     * The sets come from `getSetsForSession`, which the repository contract requires to be a
     * single join rather than a query per exercise. They carry their own `sessionExerciseId`,
     * so grouping them by exercise costs nothing further.
     */
    private val loaded: StateFlow<Loaded?> = combine(
        sessionRepository.getById(destination.sessionId),
        sessionRepository.getExercises(destination.sessionId),
        sessionRepository.getSetsForSession(destination.sessionId),
    ) { session, exercises, sets ->
        Loaded(session = session, exercises = exercises, sets = sets)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /**
     * The catalogue-wide reads the recap resolves its rows against.
     *
     * All three are bulk reads keyed by exercise id, which is what keeps a per-row lookup from
     * being a per-row query: a session has a handful of exercises, but the alternative is a
     * `Flow` per exercise to combine, re-subscribed whenever the session's exercise list moves.
     *
     * Null until the reads come back, for the same reason [loaded] is — and it has to be. An
     * empty [Catalogue] as the initial value is indistinguishable from a catalogue that
     * genuinely holds nothing, and the session's own read is the *lighter* of the two: one row
     * and its sets against every exercise and every muscle link in the app. So it routinely
     * lands first, and a recap drawn against an empty catalogue is a whole screen of
     * "Removed from your library" with no muscles and no records, replaced a frame later.
     */
    private val catalogue: StateFlow<Catalogue?> = combine(
        exerciseRepository.getAll(),
        exerciseRepository.getAllMuscleGroupLinks(),
        personalRecordRepository.getHeadlineRecords(),
    ) { exercises, links, records ->
        Catalogue(
            exercises = exercises.associateBy(Exercise::id),
            muscleLinks = links,
            headlineRecords = records,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /**
     * Everything the recap draws that is a function of what was *read*.
     *
     * Its own flow rather than an arm of the state's combine, because it is the expensive
     * half: grouping the sets, attributing volume across every muscle link, scanning the
     * headline records. Combined with [screenState] it would run again on every keystroke in
     * the note field — the note is a character of local state, and re-attributing a session's
     * muscles to record one is arithmetic nobody asked for, on the main dispatcher.
     */
    private val content: StateFlow<SessionSummaryUiState> = combine(
        loaded,
        catalogue,
    ) { loaded, catalogue ->
        buildContent(loaded, catalogue)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SessionSummaryUiState(mode = mode),
    )

    /** The read half, with what the user is in the middle of laid over it. */
    val uiState: StateFlow<SessionSummaryUiState> = combine(
        content,
        screenState,
    ) { content, screen ->
        content.copy(
            notesDraft = screen.noteDraft,
            isSavingNotes = screen.isSavingNotes,
            templateName = screen.templateName,
            templateCategory = screen.templateCategory,
            isSavingTemplate = screen.isSavingTemplate,
            templateNameTaken = screen.templateNameTaken,
            nameDraft = screen.nameDraft,
            isSavingName = screen.isSavingName,
            message = screen.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SessionSummaryUiState(mode = mode),
    )

    fun onEvent(event: SessionSummaryEvent) {
        if (onNameEvent(event)) return

        when (event) {
            SessionSummaryEvent.NoteEditRequested -> openNote()

            is SessionSummaryEvent.NoteChanged ->
                screenState.update { it.copy(noteDraft = event.text) }

            SessionSummaryEvent.NoteEditCancelled ->
                screenState.update { it.copy(noteDraft = null, isSavingNotes = false) }

            SessionSummaryEvent.NoteSaved -> saveNote()

            SessionSummaryEvent.SaveAsTemplateRequested -> openTemplateDialog()

            // Typing clears the refusal: it was about the name that *was* in the field, and an
            // error left under a name nobody has claimed reads as one that cannot be fixed.
            is SessionSummaryEvent.TemplateNameChanged ->
                screenState.update { it.copy(templateName = event.name, templateNameTaken = false) }

            is SessionSummaryEvent.TemplateCategoryChanged ->
                screenState.update { it.copy(templateCategory = event.category) }

            // Ignored while the write is in flight, which is the dialog's own rule — it goes
            // inert rather than closing, so a refusal has somewhere to be reported. Enforced
            // here as well because clearing the flag on the way out would re-arm the action
            // and let a second write race the first: two templates, or a name the first one
            // has just taken.
            SessionSummaryEvent.SaveAsTemplateDismissed ->
                if (!screenState.value.isSavingTemplate) {
                    screenState.update { it.closeTemplateDialog() }
                }

            SessionSummaryEvent.SaveAsTemplateConfirmed -> saveTemplate()

            SessionSummaryEvent.MessageShown -> screenState.update { it.copy(message = null) }

            else -> Unit
        }
    }

    /**
     * The four events that only concern what the session is called, handled together.
     *
     * Split off [onEvent] so that neither `when` branches over two unrelated subjects: the note
     * and the template dialog are about a session's *contents*, and this is about its title.
     *
     * @return whether [event] was one of them.
     */
    private fun onNameEvent(event: SessionSummaryEvent): Boolean {
        when (event) {
            // Seeded from what is stored. Ignored before the row has loaded.
            SessionSummaryEvent.NameEditRequested -> loaded.value?.session?.let { session ->
                screenState.update { it.copy(nameDraft = session.name.orEmpty()) }
            }

            is SessionSummaryEvent.NameChanged ->
                screenState.update { it.copy(nameDraft = event.text) }

            SessionSummaryEvent.NameEditCancelled ->
                if (!screenState.value.isSavingName) {
                    screenState.update { it.copy(nameDraft = null) }
                }

            SessionSummaryEvent.NameConfirmed -> saveName()

            else -> return false
        }
        return true
    }

    /**
     * Write the name, leaving the dialog up and inert while the write is in flight.
     *
     * A session logged months ago is renamed exactly like the one just finished — the recap is
     * one screen with two modes, and what the user can still *do* to a workout does not expire.
     * `RenameSessionUseCase` owns the blank-means-unnamed rule, so there is nothing to check
     * here.
     */
    private fun saveName() {
        val draft = screenState.value.nameDraft ?: return
        if (screenState.value.isSavingName) return
        screenState.update { it.copy(isSavingName = true) }

        viewModelScope.launch {
            renameSession(destination.sessionId, draft)
                // Closed on success only. The new title is drawn from the re-read; nothing is
                // copied into local state to be kept in step with it.
                .onSuccess { screenState.update { it.copy(nameDraft = null, isSavingName = false) } }
                .onFailure {
                    screenState.update {
                        it.copy(isSavingName = false, message = SessionSummaryMessage.NAME_SAVE_FAILED)
                    }
                }
        }
    }

    /** Seed the field from what is stored. Ignored before the row has loaded. */
    private fun openNote() {
        val session = loaded.value?.session ?: return
        screenState.update { it.copy(noteDraft = session.notes.orEmpty()) }
    }

    /**
     * Write the note, leaving the field up and inert while the write is in flight.
     *
     * A blank note clears the column rather than storing whitespace, which is also the only
     * way to delete one: the field's own empty state is the delete affordance, so there is no
     * separate button for it to be inconsistent with.
     *
     * `updatedAt` is left where it is, matching the exercise detail's own note save. The use
     * cases own the clock in this codebase and a ViewModel that stamped one would be the first
     * to; annotating a workout is also not a change to *what was performed*, which is what the
     * session's timestamps are read for.
     *
     * [Throwable] rather than a type, for the same reason: a store that cannot be written
     * fails in more than one way and all of them are the same event to somebody looking at a
     * text field.
     */
    private fun saveNote() {
        val session = loaded.value?.session ?: return
        val text = screenState.value.noteDraft ?: return
        if (screenState.value.isSavingNotes) return

        screenState.update { it.copy(isSavingNotes = true) }

        viewModelScope.launch {
            try {
                sessionRepository.update(session.copy(notes = text.trim().ifEmpty { null }))
                // Closed on success only. The re-read from Room is what draws the new note;
                // nothing is copied into local state to be kept in step with it.
                screenState.update { it.copy(noteDraft = null, isSavingNotes = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The field stays open with the text still in it: it is the only copy of what
                // the user wrote, and a field that closed on failure would lose it.
                screenState.update {
                    it.copy(isSavingNotes = false, message = SessionSummaryMessage.NOTES_SAVE_FAILED)
                }
            }
        }
    }

    /**
     * Raise the dialog on the session's own name, which is the name a user expects to see, and
     * on no category — see [TemplateCategory], where the null is explicitly not `GENERAL`.
     */
    private fun openTemplateDialog() {
        val session = loaded.value?.session ?: return
        screenState.update {
            it.copy(
                templateName = session.name.orEmpty(),
                templateCategory = null,
                templateNameTaken = false,
            )
        }
    }

    /**
     * Write the session out as a template.
     *
     * The drafts are rebuilt from [loaded] rather than read off the state the screen is
     * holding, for the reason the exercise detail re-derives its duplicate flag before
     * writing: the state is what was true when it was last composed, and this is a write.
     *
     * Refusals are reported, not worked around. A name already in use comes back as
     * [TemplateError.DuplicateName] and is said as such — appending a number would save the
     * template under a name the user will not go looking for.
     *
     * Two failure channels, because there are two kinds of failure. The use case *returns* a
     * refusal — the name, the body, the grouping — and the store *throws* when it cannot be
     * written at all. Catching only the first would leave the second to kill the coroutine
     * with the dialog still up and no message under it, which is the one outcome worse than
     * either error.
     */
    private fun saveTemplate() {
        val session = loaded.value ?: return
        val name = screenState.value.templateName ?: return
        if (screenState.value.isSavingTemplate) return

        val exercises = session.templateDrafts()
        // Guarded here as well as by the disabled action, because an event can still arrive
        // from a keyboard's done action on the name field.
        if (exercises.isEmpty()) return

        screenState.update { it.copy(isSavingTemplate = true) }

        viewModelScope.launch {
            val outcome = try {
                createTemplate(
                    name = name,
                    exercises = exercises,
                    category = screenState.value.templateCategory,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The store could not be written at all, which is never one of the use case's
                // own refusals — those come back as a failed `Result`. So there is nothing to
                // inspect and nothing a second press would fix; the dialog goes.
                screenState.update {
                    it.closeTemplateDialog()
                        .copy(message = SessionSummaryMessage.TEMPLATE_SAVE_FAILED)
                }
                return@launch
            }

            outcome
                .onSuccess {
                    screenState.update {
                        it.closeTemplateDialog()
                            .copy(message = SessionSummaryMessage.TEMPLATE_SAVED)
                    }
                }
                .onFailure { failure ->
                    // The dialog stays up on a name collision, with the refusal drawn under the
                    // field it is about, and closes on anything else: the first is something the
                    // user can fix without retyping what they wrote, and the second is not
                    // something a second press would resolve.
                    if (failure is TemplateError.DuplicateName) {
                        screenState.update {
                            it.copy(isSavingTemplate = false, templateNameTaken = true)
                        }
                    } else {
                        screenState.update {
                            it.closeTemplateDialog()
                                .copy(message = SessionSummaryMessage.TEMPLATE_SAVE_FAILED)
                        }
                    }
                }
        }
    }

    /**
     * Assemble the half of the screen the reads decide.
     *
     * The screen-state fields are left at their defaults and laid over this by [uiState]. A
     * read still outstanding is a loading screen; a read that came back with no row is a
     * missing one — see [SessionSummaryUiState.isMissing].
     */
    private fun buildContent(
        loaded: Loaded?,
        catalogue: Catalogue?,
    ): SessionSummaryUiState {
        if (loaded == null || catalogue == null) return SessionSummaryUiState(mode = mode)

        val session = loaded.session
            ?: return SessionSummaryUiState(mode = mode, isLoading = false)

        val records = loaded.recordsSetHere(catalogue.headlineRecords)
        val muscles = loaded.muscleBreakdown(catalogue.muscleLinks)

        return SessionSummaryUiState(
            mode = mode,
            header = SummaryHeader(
                name = session.name,
                startedAt = session.createdAt,
                finishedAt = loaded.finishedAt,
            ),
            stats = SessionStatsCalculator.calculate(
                sets = loaded.sets,
                startedAt = session.createdAt,
                endedAt = loaded.finishedAt,
            ),
            records = records.map { record ->
                SummaryRecord(
                    record = record,
                    exerciseName = catalogue.exercises[record.exerciseId]?.name,
                )
            },
            muscles = muscleContributions(
                volumeByMuscle = muscles.volumeByMuscle,
                primaryMuscleIds = muscles.primaryMuscleIds,
            ),
            primaryMuscles = muscles.primary,
            secondaryMuscles = muscles.secondary,
            exercises = loaded.breakdown(
                names = catalogue.exercises,
                recordsByExercise = records.associateBy(PersonalRecord::exerciseId),
            ),
            notes = session.notes,
            isLoading = false,
        )
    }

    private companion object {
        /**
         * Long enough to survive a rotation without re-reading the session, short enough that
         * a backgrounded screen is not holding database observers open. The other screens'
         * number, for the same reasons.
         */
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}

/** What the user is in the middle of. */
private data class ScreenState(
    val nameDraft: String? = null,
    val isSavingName: Boolean = false,
    val noteDraft: String? = null,
    val isSavingNotes: Boolean = false,
    val templateName: String? = null,
    val templateCategory: TemplateCategory? = null,
    val isSavingTemplate: Boolean = false,
    val templateNameTaken: Boolean = false,
    val message: SessionSummaryMessage? = null,
) {

    /** The dialog gone, and everything it was holding with it. */
    fun closeTemplateDialog(): ScreenState =
        copy(
            templateName = null,
            templateCategory = null,
            isSavingTemplate = false,
            templateNameTaken = false,
        )
}

/** The three catalogue-wide reads, as one value. */
private data class Catalogue(
    val exercises: Map<Long, Exercise> = emptyMap(),
    val muscleLinks: Map<Long, List<ExerciseMuscleGroupLink>> = emptyMap(),
    val headlineRecords: Map<Long, PersonalRecord> = emptyMap(),
)

/**
 * The session as it was read: the row, its exercises and its sets.
 *
 * A type of its own rather than a `Triple`, because everything the recap shows is derived from
 * all three of these together — and the derivations below are what keep that arithmetic out of
 * the ViewModel's body, where it would be four private methods threading the same three lists.
 *
 * @param session null until the read comes back **and** for an id that no longer resolves. The
 *   two are told apart one level up, by whether this value exists at all.
 */
private class Loaded(
    val session: WorkoutSession?,
    val exercises: List<SessionExercise>,
    val sets: List<WorkoutSet>,
) {

    /**
     * When the session ended.
     *
     * `completedAt` for a session that was closed out, which is every session this screen is
     * opened on. See the ViewModel's note for why the fallback is `lastActiveAt` and not now.
     */
    val finishedAt: Long = session?.completedAt ?: session?.lastActiveAt ?: 0L

    /**
     * The exercises that actually contributed, in the order they were performed, each with its
     * completed sets.
     *
     * Computed once rather than behind a getter: four of the five things below start from it,
     * and a getter would regroup every set of the session each time one of them asked.
     *
     * The filter is the same rule `SessionStats.exerciseBreakdown` applies — a completed set or
     * nothing — so this list and the tile counting it cannot disagree. Ordered by `position`
     * rather than by the order the read happened to arrive in, because that is the column the
     * order is stored in.
     */
    val performed: List<PerformedExercise> = run {
        val byExercise = sets.filter(WorkoutSet::isCompleted).groupBy(WorkoutSet::sessionExerciseId)
        exercises
            .sortedBy(SessionExercise::position)
            .mapNotNull { exercise ->
                byExercise[exercise.id]?.let { PerformedExercise(exercise, it) }
            }
    }

    /**
     * The records whose set belongs to this session.
     *
     * See the ViewModel's own note for why this is a filter over the headline records rather
     * than a query, and what that costs until #30 lands.
     */
    fun recordsSetHere(headline: Map<Long, PersonalRecord>): List<PersonalRecord> {
        if (headline.isEmpty()) return emptyList()
        val setIds = sets.mapTo(HashSet(sets.size), WorkoutSet::id)
        return headline.values.filter { it.workoutSetId in setIds }
    }

    /**
     * The per-exercise rows, with each exercise's record where it set one.
     *
     * The record is matched to the row by the *set* it was read off and not by the exercise
     * alone: `session_exercise` is unique on `(session_id, position)` rather than on the
     * exercise, so a session that opened and closed on the bench is two rows of the same
     * exercise — and only one of them contains the set that beat anything. Keyed by exercise
     * alone, both would wear the badge.
     */
    fun breakdown(
        names: Map<Long, Exercise>,
        recordsByExercise: Map<Long, PersonalRecord>,
    ): List<SummaryExerciseRow> =
        performed.map { (exercise, performedSets) ->
            val reps = performedSets.mapNotNull(WorkoutSet::reps)
            SummaryExerciseRow(
                sessionExerciseId = exercise.id,
                name = names[exercise.exerciseId]?.name,
                setCount = performedSets.size,
                lowestReps = reps.minOrNull(),
                highestReps = reps.maxOrNull(),
                topWeight = performedSets.mapNotNull(WorkoutSet::weight).maxOrNull(),
                record = recordsByExercise[exercise.exerciseId]
                    ?.takeIf { record -> performedSets.any { it.id == record.workoutSetId } },
            )
        }

    /**
     * The muscle half of the recap, in one pass.
     *
     * The attribution is `MuscleVolumeCalculator`'s — the same function the progression screens
     * use, and the in-memory twin of `WorkoutSetDao.getVolumeByMuscleGroup` — so the breakdown
     * here and the one on a stats screen are the same figures for the same window.
     *
     * The primary/secondary split is derived from the links of the exercises that *contributed
     * a completed set*, which is the subset the calculator attributes over. Taking it from the
     * whole session instead would light a muscle up on the diagram that no set touched.
     */
    fun muscleBreakdown(links: Map<Long, List<ExerciseMuscleGroupLink>>): MuscleBreakdown {
        val primaryIds = performed
            .flatMap { (exercise, _) -> links[exercise.exerciseId].orEmpty() }
            .filter(ExerciseMuscleGroupLink::isPrimary)
            .mapTo(mutableSetOf()) { it.muscleGroup.id }

        val volumeByMuscle = MuscleVolumeCalculator.volumeByMuscle(
            sets = sets,
            sessionExercises = exercises,
            linksByExerciseId = links,
        )
        // Partitioned over the calculator's own keys rather than over the links, so a group the
        // attribution left out — one linked only to exercises whose sets were never completed —
        // cannot appear on the diagram with no bar beside it.
        val (primary, secondary) = volumeByMuscle.keys.partition { it.id in primaryIds }

        return MuscleBreakdown(
            volumeByMuscle = volumeByMuscle,
            primaryMuscleIds = primaryIds,
            primary = primary,
            secondary = secondary,
        )
    }

    /**
     * The session as the exercises of a template.
     *
     * Each exercise keeps its place and its superset grouping, and carries the sets that were
     * actually performed — set for set, in the order they were done, each with its own type,
     * reps and weight.
     *
     * ### It used to average the workout away
     *
     * A template held three numbers per exercise, so this had to reduce a whole exercise to
     * them: the count of sets, the *modal* rep count, and the heaviest load. That threw away
     * exactly what a lifter saves a workout as a template *for* — the warm-up, the ramp, the
     * drop set at the end all came back as "5 × 10 × 100", which is a workout nobody did.
     * A template is a list of sets now ([PlannedSet]), so what is copied is what happened.
     *
     * Only performed sets are copied, which is [performed]'s own filter: an unticked row is
     * work that did not happen, and planning next week around it would be planning around a
     * set the lifter skipped.
     */
    fun templateDrafts(): List<TemplateExerciseDraft> =
        performed.map { (exercise, performedSets) ->
            TemplateExerciseDraft(
                exerciseId = exercise.exerciseId,
                sets = performedSets.map { set ->
                    PlannedSet(setType = set.setType, reps = set.reps, weight = set.weight)
                },
                supersetGroup = exercise.supersetGroup,
            )
        }
}

/** One exercise of the session and the sets it was actually performed with. */
private data class PerformedExercise(
    val exercise: SessionExercise,
    val sets: List<WorkoutSet>,
)

/** The muscle half of the recap: the bars, and the split the diagram is drawn from. */
private class MuscleBreakdown(
    val volumeByMuscle: Map<MuscleGroup, Long>,
    val primaryMuscleIds: Set<Long>,
    val primary: List<MuscleGroup>,
    val secondary: List<MuscleGroup>,
)
