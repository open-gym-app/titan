package com.opengym.feature.session.newsession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionPlan
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.toSessionPlan
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.superset.Supersets
import com.opengym.core.domain.usecase.ApplyTemplateUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.StartPlannedSessionUseCase
import com.opengym.core.ui.component.supersetLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Assembling a workout: the saved templates to choose from, and the plan being put together.
 *
 * ### Nothing here writes until START
 *
 * The plan lives in this ViewModel and nowhere else until
 * [StartPlannedSessionUseCase] is called. A template tapped, an exercise picked, an exercise
 * dropped again — none of it touches the database, so backing out of the flow at any point
 * leaves no session row and no in-progress draft to clean up. That is not an optimisation:
 * a flow that wrote as it went would have to *undo* on the way out, and the undo would have
 * to survive the process being killed on the picker screen.
 *
 * ### Why the plan is resolved once rather than observed
 *
 * The template's exercises and their catalogue rows are read with a `first()` at the moment
 * the plan is built, not held as observed flows. The plan is a snapshot of a decision the
 * user is making right now, and the catalogue cannot change underneath it from here — the
 * only screen that edits an exercise is the library, which is not reachable while this one is
 * up. Observing them would mean a review panel that reshuffled itself mid-read for no
 * benefit.
 *
 * What *is* observed is the template list, because #92's authoring writes to it from
 * elsewhere and a picker showing a stale list is a picker showing templates that are gone.
 */
@HiltViewModel
class NewSessionViewModel @Inject constructor(
    getTemplateSummaries: GetTemplateSummariesUseCase,
    private val applyTemplate: ApplyTemplateUseCase,
    private val startPlannedSession: StartPlannedSessionUseCase,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    /** What the screen owns, as opposed to what the stores do. */
    private val screenState = MutableStateFlow(ScreenState())

    /**
     * The saved templates, or **null** until the read comes back.
     *
     * Null rather than an empty list because the two are different states and the screen
     * draws them differently: no templates is a real answer with a call to action attached,
     * and flashing it for the few milliseconds before the first emission tells a user who
     * has six templates that they have none.
     */
    private val templates: StateFlow<List<TemplateSummary>?> = getTemplateSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    val uiState: StateFlow<NewSessionUiState> = combine(
        templates,
        screenState,
    ) { templates, screen ->
        NewSessionUiState(
            templates = templates.orEmpty(),
            isLoading = templates == null,
            step = screen.step(),
            isStarting = screen.isStarting,
            startedSessionId = screen.startedSessionId,
            nameDraft = screen.nameDraft,
            message = screen.message,
        )
    }.stateIn(
        scope = viewModelScope,
        // Long enough to survive a rotation without re-reading the template list, short
        // enough that a backgrounded screen is not holding database observers open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = NewSessionUiState(),
    )

    fun onEvent(event: NewSessionEvent) {
        when (event) {
            NewSessionEvent.ExerciseSelectionOpened ->
                screenState.update { it.copy(selectingExercises = true) }

            NewSessionEvent.ExerciseSelectionClosed ->
                screenState.update { it.copy(selectingExercises = false) }

            is NewSessionEvent.TemplateSelected -> planFromTemplate(event.templateId)
            is NewSessionEvent.ExercisesSelected -> planFromExercises(event.exerciseIds)
            is NewSessionEvent.ExerciseRemoved -> removeExercise(event.index)

            NewSessionEvent.PlanDiscarded ->
                screenState.update { it.copy(plan = null, selectingExercises = false) }

            NewSessionEvent.NameEditRequested ->
                screenState.update { it.copy(nameDraft = it.plan?.session?.name.orEmpty()) }
            is NewSessionEvent.NameChanged ->
                screenState.update { it.copy(nameDraft = event.text) }
            NewSessionEvent.NameEditCancelled ->
                screenState.update { it.copy(nameDraft = null) }
            NewSessionEvent.NameConfirmed -> applyName()

            NewSessionEvent.SessionStartRequested -> start()
            NewSessionEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    /**
     * Reads [templateId] out and moves to ready-to-start.
     *
     * The estimate comes off the template's own summary rather than being recomputed: the
     * list on screen already priced it against the user's default rest, and computing it a
     * second way here is how a row and the panel it opens end up disagreeing by five minutes.
     */
    private fun planFromTemplate(templateId: Long) = viewModelScope.launch {
        val plan = applyTemplate(templateId).getOrElse {
            screenState.update { it.copy(message = NewSessionMessage.TEMPLATE_UNAVAILABLE) }
            return@launch
        }

        adopt(
            session = plan.toSessionPlan(),
            estimatedDuration = templates.value
                ?.firstOrNull { it.template.id == templateId }
                ?.estimatedDuration,
        )
    }

    /**
     * Takes the picker's answer.
     *
     * An empty answer is not how a cancel arrives — the picker's confirm bar is disabled
     * until something is ticked, and a dismissal comes back as
     * [NewSessionEvent.ExerciseSelectionClosed]. It is handled anyway, as the template list
     * rather than a review panel with nothing on it.
     */
    private fun planFromExercises(exerciseIds: List<Long>) {
        if (exerciseIds.isEmpty()) {
            screenState.update { it.copy(selectingExercises = false) }
            return
        }

        viewModelScope.launch {
            // Null, not the shipped default: a hand-picked session has no set counts behind
            // it, so there is nothing to pace and an estimate here would be invented.
            adopt(session = SessionPlan.fromExercises(exerciseIds), estimatedDuration = null)
        }
    }

    /**
     * Resolves [session] against the catalogue and puts the screen on the review panel.
     *
     * A draft whose exercise is not in the catalogue is dropped **from the plan**, not merely
     * from what is drawn. Dropping it at draw time only was two bugs: the visible rows and
     * `plan.session.exercises` then disagreed on what index a row was at, so the close button
     * removed a different exercise than the one it was next to; and the unresolvable draft
     * survived into START, where `session_exercise.exercise_id` is a foreign key and one bad
     * row aborts the whole layout. Cutting it here leaves one list, which the panel numbers
     * and the use case writes.
     *
     * Nothing left after that is reported rather than drawn: an empty review panel is a dead
     * end, and the user needs to know why the template they tapped came back with nothing.
     */
    private suspend fun adopt(session: SessionPlan, estimatedDuration: Duration?) {
        val exercises = exerciseRepository.getAll().first().associateBy(Exercise::id)
        val known = session.exercises.filter { it.exerciseId in exercises }

        if (known.isEmpty()) {
            screenState.update {
                it.copy(selectingExercises = false, message = NewSessionMessage.EXERCISES_UNAVAILABLE)
            }
            return
        }

        val plan = Plan(
            session = session.copy(exercises = known),
            estimatedDuration = estimatedDuration,
            exercises = exercises,
            muscleGroups = exerciseRepository.getAllMuscleGroupLinks().first(),
        )
        screenState.update {
            it.copy(
                plan = plan,
                selectingExercises = false,
                // Said only when something was actually lost. The panel is still worth
                // showing — six of eight exercises is a workout — but it is not the workout
                // that was tapped, and silently starting a shorter one is the part a user
                // would not forgive.
                message = if (known.size < session.exercises.size) {
                    NewSessionMessage.EXERCISES_UNAVAILABLE
                } else {
                    it.message
                },
            )
        }
    }

    /**
     * Drops the exercise at [index], dissolving whatever it leaves behind.
     *
     * [index] is a position in the review panel's list, which is the plan's own list: every
     * draft in it resolved against the catalogue when the plan was adopted, so the two are
     * numbered alike. See [adopt].
     *
     * The estimate goes with the first removal. It was priced against the template's set
     * counts, and the drafts carry none — there is nothing to recompute it from, so the only
     * honest options are to drop it or to keep quoting the figure that priced a workout the
     * user has just changed. "4 exercises · ~55m", where the 55 was for eight, is worse than
     * "4 exercises".
     *
     * Removing the last one clears the plan, which puts the screen back on the template list.
     * The alternative — a review panel showing nothing, with START disabled — is a state
     * whose only exit is the one the back gesture already offers, drawn as though it were a
     * place.
     */
    private fun removeExercise(index: Int) = screenState.update { state ->
        val plan = state.plan ?: return@update state
        if (index !in plan.session.exercises.indices) return@update state

        val remaining = plan.session.exercises.filterIndexed { position, _ -> position != index }
        if (remaining.isEmpty()) {
            state.copy(plan = null)
        } else {
            state.copy(
                plan = plan.copy(
                    session = plan.session.copy(exercises = remaining),
                    estimatedDuration = null,
                ),
            )
        }
    }

    /**
     * Records what the session will be called, on the plan and nowhere else.
     *
     * Nothing is written here — the whole flow's premise is that backing out costs nothing to
     * undo — so this is the same decision as picking a template or dropping an exercise, and it
     * travels to `StartSessionUseCase` on `SessionPlan.name` when the user commits. The
     * blank-means-unnamed rule belongs to `WorkoutSession.normalizeName` rather than to a second
     * copy of it here: the plan keeps what the user typed, and the two places that have to agree
     * about what it *means* — the use case on the way to the store, and [Plan.toStep] on the way
     * to the screen — both ask that one function.
     */
    private fun applyName() = screenState.update { state ->
        val plan = state.plan ?: return@update state.copy(nameDraft = null)
        state.copy(
            plan = plan.copy(session = plan.session.copy(name = state.nameDraft)),
            nameDraft = null,
        )
    }

    /**
     * Writes the plan and reports the session it became.
     *
     * Guarded on [ScreenState.isStarting] rather than only in the UI: a double-tapped START
     * dispatched before recomposition would otherwise reach the use case twice, and the
     * second call would be refused by the session the first one just opened — an error the
     * user did nothing to earn.
     */
    private fun start() {
        val plan = screenState.value.plan ?: return
        if (screenState.value.isStarting) return
        screenState.update { it.copy(isStarting = true) }

        viewModelScope.launch {
            startPlannedSession(plan.session)
                .onSuccess { sessionId ->
                    // isStarting stays true: the screen is on its way out, and re-enabling
                    // the button for the frame before the pop invites a second workout.
                    screenState.update { it.copy(startedSessionId = sessionId) }
                }
                .onFailure { failure ->
                    screenState.update { it.copy(isStarting = false, message = failure.toMessage()) }
                }
        }
    }

    /** What the screen owns while the flow is open. */
    private data class ScreenState(
        val plan: Plan? = null,
        val selectingExercises: Boolean = false,
        val isStarting: Boolean = false,
        val startedSessionId: Long? = null,
        val nameDraft: String? = null,
        val message: NewSessionMessage? = null,
    ) {

        fun step(): NewSessionStep = when {
            plan != null -> plan.toStep()
            selectingExercises -> NewSessionStep.ExerciseSelection
            else -> NewSessionStep.TemplateSelection
        }
    }

    /**
     * The plan, plus the catalogue rows needed to draw it.
     *
     * The catalogue is carried alongside rather than looked up per row at draw time: a
     * lookup inside a list item is a lookup repeated on every scroll pass, and holding it
     * here is what lets [toStep] be a pure function of this value.
     *
     * Every draft in [session] has a row in [exercises] — [adopt] drops the ones that do not
     * before building this — so the panel's rows and the plan's exercises are one list under
     * two names, numbered alike.
     */
    private data class Plan(
        val session: SessionPlan,
        val estimatedDuration: Duration?,
        val exercises: Map<Long, Exercise>,
        val muscleGroups: Map<Long, List<ExerciseMuscleGroupLink>>,
    ) {

        fun toStep(): NewSessionStep.ReadyToStart {
            // Normalised on every build rather than on removal, because a removal is what
            // makes a grouping stale and the labels are read off the repaired one — a
            // superset left with a single member is not a superset and must not be drawn as
            // `SS A1` with no `A2` under it. The use case normalises again on the way to the
            // store; this is the same rule applied to what the user is looking at.
            val grouping = Supersets.normalize(session.exercises.map(SessionExerciseDraft::supersetGroup))
            val labels = supersetLabels(grouping)

            return NewSessionStep.ReadyToStart(
                // The same normalisation `StartSessionUseCase` applies on the way to the store,
                // by the same function, so the panel says "Unnamed session" for exactly the
                // inputs that will be stored as unnamed. Without it, a user who clears the name
                // field — which the dialog's own hint invites — confirms their way to a blank
                // title with no route back to the placeholder.
                name = WorkoutSession.normalizeName(session.name),
                estimatedDuration = estimatedDuration,
                // One row per draft, in order. Unresolvable ids were cut from the plan
                // itself in [adopt] rather than skipped here, so this cannot drop a row and
                // leave the panel numbering exercises the plan numbers differently.
                exercises = session.exercises.mapIndexed { index, draft ->
                    PlannedExerciseRow(
                        exercise = requireNotNull(exercises[draft.exerciseId]) {
                            "draft ${draft.exerciseId} has no catalogue row; adopt() should have cut it"
                        },
                        muscleGroups = muscleGroups[draft.exerciseId].orEmpty(),
                        supersetLabel = labels[index],
                    )
                },
            )
        }
    }

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}

/** Which of [NewSessionMessage] a refused start is. */
private fun Throwable.toMessage(): NewSessionMessage = when (this) {
    is SessionError.SessionAlreadyActive -> NewSessionMessage.SESSION_ALREADY_ACTIVE
    else -> NewSessionMessage.START_FAILED
}
