package com.titan.feature.session.newsession

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.TemplateSummary
import kotlin.time.Duration

/**
 * Everything the new-session screen draws, as one value.
 *
 * The screen is one destination with three faces — see [NewSessionStep] — rather than three
 * destinations, because only one of the three is somewhere the user can be sent back to. A
 * workout that has not started yet is a decision in progress, and the back gesture out of it
 * means "I have not decided", not "take me one step back through the decision".
 *
 * @param templates the saved templates, with the figures a row shows. Empty is a real state
 *   and not a loading one — see [isLoading] — and the screen says so, since templates are
 *   authored from a finished session (#92) and a user who has never done that has none.
 * @param isLoading the template read has not come back yet. Distinct from "there are no
 *   templates", which is what would otherwise be drawn for the moment before the first
 *   emission — an empty state with a call to action is a poor thing to flash at somebody.
 * @param step which of the three faces is showing.
 * @param isStarting the session is being written. Held so the START button can be disabled:
 *   the write is two rows and a state transition, and a second press part way through would
 *   be refused by [com.titan.core.domain.usecase.StartSessionUseCase] with an error the
 *   user did nothing to deserve.
 * @param openSessionId the workout this flow has landed the user on — the flow's way of saying
 *   it is over. Two things set it and the screen does the same thing with both: the session
 *   START has just created, and the one that was already running and has been picked back up
 *   (see [runningSessionId]). Both are the same navigation, because in both cases the answer to
 *   "which workout am I in" is this one.
 *
 *   Carried in the state rather than pushed down a channel because there is nothing to race:
 *   the destination is popped on the way into the workout, so this is the last value this
 *   screen ever holds.
 * @param runningWorkout the workout that refused the start, or null. `StartSessionUseCase`
 *   turns down a second session while one is in progress and names the one in the way, and
 *   this screen offers it back rather than reporting the refusal: the running session is not in
 *   the history list — which shows only what has been finished — so a user told "one is already
 *   running" and left there has no way to reach it, finish it, or start anything else. See
 *   `ResumeSessionDialog`.
 * @param nameDraft what is in the name field, or **null** when the dialog is closed. Null is the
 *   read state, so a screen asking "is the dialog up" asks one question.
 * @param message something that happened and needs saying once.
 */
@Immutable
data class NewSessionUiState(
    val templates: List<TemplateSummary> = emptyList(),
    val isLoading: Boolean = true,
    val step: NewSessionStep = NewSessionStep.TemplateSelection,
    val isStarting: Boolean = false,
    val openSessionId: Long? = null,
    val runningWorkout: RunningWorkout? = null,
    val nameDraft: String? = null,
    val message: NewSessionMessage? = null,
)

/**
 * The workout in the way, as much of it as the offer to pick it back up has to show.
 *
 * Read once, when the start is refused, rather than observed: it describes a session the user
 * is being asked about, and a set count that changed underneath the question would be a
 * different question. Nothing on this screen can change it either — the only two answers both
 * leave the screen.
 *
 * @param name what the user called it, or **null** for one started unnamed, which the dialog
 *   falls back to the date for exactly as the workout's own header does.
 * @param startedAt when it was opened, which is what that fallback is built from.
 * @param loggedSets how much is in it. The one figure that decides the question: a workout with
 *   nothing logged is a mistap to be rid of, and one with eleven sets in it is an afternoon.
 *   It is shown rather than described for the reason `DiscardSessionDialog` gives — "discard
 *   this workout" is a different question when the answer costs eleven sets, and the user is
 *   the only one who knows which of the two they are being asked.
 */
@Immutable
data class RunningWorkout(
    val sessionId: Long,
    val name: String? = null,
    val startedAt: Long = 0L,
    val loggedSets: Int = 0,
)

/**
 * Where the user is in deciding what to train.
 *
 * The three states #25 names. None of them writes anything: a session row exists only once
 * [NewSessionStep.ReadyToStart] is confirmed, which is what makes backing out of any of them
 * free.
 */
sealed interface NewSessionStep {

    /** The list of saved templates, over the quick-start action. Where the flow opens. */
    data object TemplateSelection : NewSessionStep

    /**
     * The exercise picker is open over this screen.
     *
     * A state rather than nothing, even though the picker covers the screen that holds it: it
     * is what keeps the quick-start action from pushing a second picker if it is pressed again
     * through a slow transition.
     *
     * It lasts exactly as long as the picker does. Leaving it is not the arrival of an answer
     * — a cancelled pick brings none back, whichever way the user left — but the screen being
     * on top again, which is what [NewSessionEvent.ExerciseSelectionClosed] reports.
     */
    data object ExerciseSelection : NewSessionStep

    /**
     * The workout as it will be started, for the user to look at before committing.
     *
     * Reached from both paths — a template tapped, or a selection confirmed — because both
     * end with the same question: is this the session you meant? A template start that went
     * straight into the workout would give a mistap no way back that did not involve
     * finishing or discarding a real session.
     *
     * @param name what the session will be called, or null for one built by hand — which
     *   stays genuinely unnamed until the user names it in the workout (#26).
     * @param estimatedDuration the template's estimate, or **null** for a hand-picked session
     *   and for one whose plan has been edited. Not a missing value: a session with no set
     *   counts behind it has nothing to pace, and an estimate invented from defaults would be
     *   a number the user could not act on. Dropping an exercise puts the panel in exactly
     *   that position — the figure priced the template, the drafts carry no set counts to
     *   reprice it from, and "4 exercises · ~55m" where the 55 was for eight is a lie the
     *   screen would be telling on purpose.
     * @param exercises what will be performed, in order.
     */
    @Immutable
    data class ReadyToStart(
        val name: String?,
        val estimatedDuration: Duration?,
        val exercises: List<PlannedExerciseRow>,
    ) : NewSessionStep {

        /** Whether there is anything left to start. See [NewSessionEvent.ExerciseRemoved]. */
        val canStart: Boolean get() = exercises.isNotEmpty()
    }
}

/**
 * One line of the ready-to-start list: the exercise, and what the plan says about it.
 *
 * The catalogue row is resolved here rather than at draw time for the reason the library's
 * own row type gives — a lookup inside a list item is a lookup repeated on every scroll pass
 * — and because a screen holding only ids could not render the list at all.
 *
 * @param supersetLabel the `A1`/`A2` marker, or null for an exercise that stands alone.
 *   Composed in the ViewModel because it is derived from the *grouping* over the whole list
 *   ([com.titan.core.domain.superset.Supersets]), which a row does not have.
 */
@Immutable
data class PlannedExerciseRow(
    val exercise: Exercise,
    val muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    val supersetLabel: String? = null,
)

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, matching `ExerciseLibraryMessage` and `SettingsMessage`: the
 * ViewModel does not reach for a `Context`, and every user-visible word stays in the
 * screen's resources.
 */
enum class NewSessionMessage {
    /*
     * A refused start used to be one of these, and is not any more: a session already running
     * is not something to say once, it is something to offer — see
     * [NewSessionUiState.runningSessionId]. Said in a snackbar it faded, leaving the user
     * holding a plan they could not start and no longer able to read why.
     */

    /**
     * The workout in the way could not be thrown away, so nothing was started.
     *
     * The offer stays on screen when this happens: the session is still there, still blocking,
     * and picking it back up is still the other way out.
     */
    DISCARD_FAILED,

    /** The session could not be written. Nothing was left behind — see the use case. */
    START_FAILED,

    /** The template could not be read, so there is nothing to start from. */
    TEMPLATE_UNAVAILABLE,

    /**
     * Some of the plan's exercises are no longer in the catalogue and were left out.
     *
     * An exercise deleted from the library after a template was saved, in practice. The plan
     * is shown without them rather than refused — six of eight exercises is still a workout —
     * but not silently: the session about to be started is not the one whose name was tapped,
     * and a lifter who notices halfway through has already lost the choice.
     */
    EXERCISES_UNAVAILABLE,
}
