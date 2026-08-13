package com.opengym.feature.session.newsession

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.TemplateSummary
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
 *   be refused by [com.opengym.core.domain.usecase.StartSessionUseCase] with an error the
 *   user did nothing to deserve.
 * @param startedSessionId the session that now exists, once START has succeeded — the flow's
 *   way of saying it is over. Carried in the state rather than pushed down a channel because
 *   there is nothing to race: the destination is popped on the way into the workout, so this
 *   is the last value this screen ever holds.
 * @param message something that happened and needs saying once.
 */
@Immutable
data class NewSessionUiState(
    val templates: List<TemplateSummary> = emptyList(),
    val isLoading: Boolean = true,
    val step: NewSessionStep = NewSessionStep.TemplateSelection,
    val isStarting: Boolean = false,
    val startedSessionId: Long? = null,
    val message: NewSessionMessage? = null,
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
     * A state rather than nothing, even though the picker covers the screen that holds it:
     * it is what keeps the quick-start action from pushing a second picker if it is pressed
     * again through a slow transition, and it is how the screen knows a returning empty
     * selection means "cancelled" rather than "never asked".
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
     * @param estimatedDuration the template's estimate, or **null** for a hand-picked
     *   session. Not a missing value: a session with no set counts behind it has nothing to
     *   pace, and an estimate invented from defaults would be a number the user could not
     *   act on.
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
 *   ([com.opengym.core.domain.superset.Supersets]), which a row does not have.
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

    /**
     * A session is already running, so this one was refused.
     *
     * The everyday cause is a session left open by a previous launch. It is reported rather
     * than silently resumed: resuming somebody into a workout they did not ask for, from a
     * screen where they were setting up a different one, is worse than saying why the button
     * did nothing.
     */
    SESSION_ALREADY_ACTIVE,

    /** The session could not be written. Nothing was left behind — see the use case. */
    START_FAILED,

    /** The template could not be read, so there is nothing to start from. */
    TEMPLATE_UNAVAILABLE,
}
