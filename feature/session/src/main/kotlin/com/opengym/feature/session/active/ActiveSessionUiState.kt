package com.opengym.feature.session.active

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.component.SessionProgress
import kotlin.time.Duration

/**
 * Everything the active session draws, as one value.
 *
 * The screen is a header and then the workout, whole: every exercise in the order it is
 * performed, each showing every set logged against it and a row to type the next one into.
 *
 * ### There is no "current" exercise
 *
 * There used to be. One exercise was open and accented, the rest were one-line rows, and moving
 * between them was a tap. Two things were wrong with it. A lifter cannot read their own session
 * without navigating around it — which is what a workout is *for* between sets — and, worse, a
 * superset has no representation at all in a model where exactly one exercise is open: two
 * exercises performed back to back are alternated set for set, so a screen that can only accept
 * a set for one of them cannot log the thing it claims to support. Both went away with the
 * concept: [exercises] is the whole workout, and every one of them takes sets.
 *
 * @param header the bar over everything: name, clock, progress. Null while the reads are
 *   outstanding **and** for a session id that no longer resolves — told apart by [isLoading],
 *   the same split the recap makes.
 * @param stats the running figures, from `SessionStatsCalculator` over the sets on disk. They
 *   move the moment a set is written, because the sets are read as a `Flow` rather than counted
 *   into a local tally that would have to be kept in step.
 * @param exercises the workout in the order it is performed. Empty for a session started with
 *   nothing in it, which is legal.
 * @param nameDraft what is in the rename field, or **null** when the dialog is closed. Null is
 *   the read state, so a screen asking "is the dialog up" asks one question — the same shape the
 *   recap's note editor uses.
 * @param isRenaming a rename write is in flight.
 * @param isAddingExercises the picker's answer is being written. The action goes inert, because
 *   a second tap would append the same exercises twice.
 * @param isRegrouping a superset is being formed or broken. One flag for the whole screen rather
 *   than one per join: the write is a rewrite of the *session's* grouping, and a second tap
 *   landing on another join mid-write would be computed against the layout as it was before.
 * @param isPaused whether the session is suspended. The clocks stop and every entry row goes
 *   read-only; `LogSetUseCase` refuses a set logged into a paused session, so a field that
 *   still accepted typing would be collecting keystrokes for a write that cannot happen.
 * @param loggingExerciseId the exercise whose set is being written, or null. An id rather than a
 *   flag because the rows are independent: a slow write against the bench press has no business
 *   disabling the tick on the curls.
 * @param isFinishing the finish is in flight.
 * @param isDiscarding the discard has been confirmed and is in flight.
 * @param confirmingDiscard the discard confirmation is up. A flag rather than a nullable
 *   payload because there is nothing to carry — the question is about the session the screen is
 *   already showing.
 * @param finishedSessionId the session that has just been closed out, which is this screen
 *   saying it is over and the recap's cue to open. Carried in the state rather than pushed down
 *   a channel for the reason the new-session flow gives: the destination is popped on the way
 *   out, so this is the last value the screen ever holds.
 * @param isDiscarded the session was thrown away. The other way the screen ends, and a separate
 *   field because it leads somewhere else — there is no recap of a workout that did not happen.
 * @param isLoading the reads have not come back yet. Distinct from a session id that no longer
 *   resolves, which is [isMissing] — a blank page and a "this is gone" page are two screens.
 * @param message something that happened and needs saying once.
 */
@Immutable
data class ActiveSessionUiState(
    val header: ActiveSessionHeader? = null,
    val stats: SessionStats? = null,
    val exercises: List<SessionExerciseRow> = emptyList(),
    val nameDraft: String? = null,
    val isRenaming: Boolean = false,
    val isAddingExercises: Boolean = false,
    val isRegrouping: Boolean = false,
    val isPaused: Boolean = false,
    val loggingExerciseId: Long? = null,
    val isFinishing: Boolean = false,
    val isDiscarding: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val finishedSessionId: Long? = null,
    val isDiscarded: Boolean = false,
    val isLoading: Boolean = true,
    val message: ActiveSessionMessage? = null,
) {

    /** A session id that no longer resolves to a row — as opposed to one still loading. */
    val isMissing: Boolean get() = !isLoading && header == null

    /** Whether the workout is over, either way. */
    val isFinished: Boolean get() = finishedSessionId != null || isDiscarded

    /**
     * Whether any action on the workout should be inert.
     *
     * One flag for four situations that all mean "a write is in flight or the session is over",
     * so a screen does not have to re-derive the same conjunction at every button.
     */
    val isBusy: Boolean get() = isFinishing || isDiscarding || isFinished
}

/**
 * The bar over the workout: what it is, and how long it has been going.
 *
 * @param name what the user called it, or **null** for a session started empty and never named.
 *   Not collapsed to a default here, matching `SummaryHeader`: the screen falls back to the
 *   session's date, and a default stored in the state would make "unnamed" indistinguishable
 *   from a session somebody genuinely called that.
 * @param startedAt epoch millis UTC, the session's `createdAt`. Carried so the screen can build
 *   that fallback title without a second read.
 * @param elapsed time actually spent working out — wall clock less every paused stretch, from
 *   `ActiveSession.elapsedAt`. Recomputed once a second while the session runs and frozen while
 *   it is paused.
 * @param progress how many exercises have a logged set against them, out of the whole workout.
 */
@Immutable
data class ActiveSessionHeader(
    val name: String?,
    val startedAt: Long,
    val elapsed: Duration,
    val progress: SessionProgress,
)

/**
 * One exercise of the workout: what has been logged against it, and what is being typed into it.
 *
 * @param sessionExerciseId the `session_exercise` row, which is what a set is written against
 *   and what keys this in a list. Not the catalogue id: one session can hold the same exercise
 *   twice, and they are two rows with two set tables.
 * @param position the exercise's place in the running order, from 0.
 * @param exercise the catalogue row, or **null** for an exercise deleted from the library since
 *   the session started. The workout carries on either way — the sets already logged against it
 *   are a thing the user did — so the card draws a fallback title rather than vanishing
 *   mid-session.
 * @param muscleGroups its links, for the card's meta line.
 * @param supersetLabel the `A1`/`A2` marker, or null for an exercise that stands alone.
 *   Composed in the ViewModel because it is derived from the grouping over the whole session,
 *   which one card cannot see.
 * @param isLinkedToNext whether the exercise after this one is performed back to back with it,
 *   i.e. whether they are members of the same superset. What the control in the gap under this
 *   card is drawn from, and the reason it is a property of the *pair* rather than of the group:
 *   the tap breaks one join, not the whole superset. See `Supersets.split`.
 * @param sets the sets logged against it, in set-number order.
 * @param entry the set being typed into it. Every exercise has one — there is always a next set
 *   to log, and a screen that hid the fields until an "add set" tap would put a tap in front of
 *   the thing the screen exists to do.
 */
@Immutable
data class SessionExerciseRow(
    val sessionExerciseId: Long,
    val position: Int,
    val exercise: Exercise?,
    val muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    val supersetLabel: String? = null,
    val isLinkedToNext: Boolean = false,
    val sets: List<WorkoutSet> = emptyList(),
    val entry: SetEntry = SetEntry(),
) {

    /** How many sets are logged against it, which is what makes it "done" rather than "to do". */
    val setCount: Int get() = sets.size

    /** The number the next set will be shown under, which is its place in the table, from 1. */
    val nextSetNumber: Int get() = sets.size + 1
}

/**
 * The set being entered, before it is a set.
 *
 * One per exercise, because a superset is logged by alternating between two of them: a single
 * entry shared by the whole screen would be cleared every time the user moved from the press to
 * the row and back.
 *
 * ### Why the numbers are text
 *
 * `8.`, `` and `0.` are all states a user passes through on the way to a number and none of
 * them is one, so the fields hold what was typed and the ViewModel parses it. That is the same
 * reason `SetLogRow` takes strings, and the two halves of it have to agree or the screen would
 * be converting on every keystroke. See CONTRIBUTING.md § Using the design system.
 *
 * ### Why it survives rotation
 *
 * It lives in the ViewModel, which outlives the activity — that is the whole of the rotation
 * criterion. Process death is a different question and is answered by the in-progress draft,
 * which carries the *parsed* value: a half-typed `8.` is not something `ActiveSet` can hold,
 * and inventing an encoding for it would be a format decision made for a keystroke.
 *
 * @param weight the weight field, in the ambient unit system's unit.
 * @param reps the rep field.
 * @param rpe the stored rating (× 10), or null for a set not yet rated.
 * @param setType what kind of set this is. Carries over from the previous set of the same
 *   exercise — a lifter logging a warm-up is usually about to log another.
 * @param notes the per-set note, or null when the note field has not been opened. Null and
 *   empty are the same set, and different screens: null draws the "add a note" affordance,
 *   empty draws an open and empty field.
 * @param error why the set as typed would be refused, or null. Recomputed on every keystroke
 *   against `SetValidator`, so the refusal is visible before the tick rather than after it. A
 *   type rather than a string because two of its three cases are the screen's own words and
 *   belong in its resources — see [SetEntryError].
 */
@Immutable
data class SetEntry(
    val weight: String = "",
    val reps: String = "",
    val rpe: Int? = null,
    val setType: SetType = SetType.NORMAL,
    val notes: String? = null,
    val error: SetEntryError? = null,
) {

    /**
     * Whether the tick should be live.
     *
     * A set with nothing typed is not an error — the fields start empty and refusing an empty
     * row before it has been filled in would put a red message under every exercise on the
     * screen — but it is not loggable either.
     */
    val canLog: Boolean get() = error == null && (weight.isNotBlank() || reps.isNotBlank())

    /**
     * Whether the user has begun this set, which is what brings out the controls under the row.
     *
     * Wider than [canLog] on purpose: a rating or a note with no weight yet is still a set being
     * worked on, and the set-type chips disappearing again because the weight was cleared would
     * be a layout that flickered under the thumb.
     */
    val isStarted: Boolean
        get() = weight.isNotBlank() || reps.isNotBlank() || rpe != null || notes != null
}

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, matching every other screen in the module: the ViewModel does
 * not reach for a `Context`, and every user-visible word stays in the screen's resources.
 *
 * There is no message for a set that logged successfully. The set appearing in the table above
 * the entry row is the confirmation, and a snackbar per set would be five interruptions an
 * exercise.
 */
enum class ActiveSessionMessage {

    /**
     * Finishing was refused because nothing has been logged.
     *
     * `SessionError.NoSetsLogged`, said in the screen's own words. Shown inline rather than as a
     * snackbar, because the answer to it is on this screen — log a set, or discard — and a
     * message that fades leaves a button that looks broken.
     */
    NO_SETS_LOGGED,

    /** The session could not be closed out. Nothing was written; the workout is still running. */
    FINISH_FAILED,

    /** The session could not be discarded. It is still running, which is the safe failure. */
    DISCARD_FAILED,

    /** The set was refused by the domain after passing the screen's own check. */
    SET_REJECTED,

    /** Pausing or resuming was refused. */
    PAUSE_FAILED,

    /** The picked exercises could not be added. The workout is unchanged. */
    ADD_EXERCISES_FAILED,

    /**
     * The exercise has sets logged against it, so it cannot be cut out.
     *
     * The one refusal on this screen that is a *rule* rather than a failure, and it names the
     * way past itself: the sets go first (#99), or the whole workout does.
     */
    EXERCISE_HAS_SETS,

    /** The exercise could not be removed. It is still in the workout. */
    REMOVE_EXERCISE_FAILED,

    /** The name could not be written. The dialog stays open with the text still in it. */
    RENAME_FAILED,

    /**
     * A superset could not be formed or broken. The workout is paced as it was.
     *
     * One message for both directions: what the user tapped was a join, and "that did not
     * happen" is the whole of what there is to say about a write that changed nothing.
     */
    SUPERSET_FAILED,
}

/**
 * Why the entry as typed cannot be logged.
 *
 * Two of the three are the screen's own words and are resolved from its resources; the third is
 * `SetValidator`'s, which names the field, its bound and the value it saw, and is shown as it
 * stands — a message this screen paraphrased would be a second copy of a rule that lives in the
 * domain.
 */
sealed interface SetEntryError {

    /** The weight field holds something that is not a number. */
    data object WeightNotANumber : SetEntryError

    /** The rep field holds something that is not a number. */
    data object RepsNotANumber : SetEntryError

    /** The values parsed and the domain refused them. [reason] is the validator's wording. */
    data class Refused(val reason: String) : SetEntryError
}
