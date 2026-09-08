package com.opengym.feature.session.active

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.timer.RestTimerState
import com.opengym.core.domain.validation.SetDimension
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.ui.component.PreviousSet
import com.opengym.core.ui.component.SessionProgress
import kotlinx.serialization.Serializable
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
 * @param refusedReorders how many reorders the store has turned down this session.
 *
 *   Not drawn anywhere. It exists because a refusal is the one outcome that changes *nothing* a
 *   screen can otherwise observe: the exercises come back in the order they were already in, so
 *   a screen holding a dragged copy of that order has no signal that it should drop it. This is
 *   the signal. A count rather than a flag because two refusals in a row have to read as two
 *   events; see `ActiveSessionScreen`, which keys the dragged order on it.
 * @param isReordering the running order is being written. One flag for the same reason
 *   [isRegrouping] is one: what is being written is the *session's* layout, and a second drag
 *   let through mid-write would be computed against the order as it was before.
 * @param swappingExerciseId the slot whose exercise is being replaced, or null. An id rather
 *   than a flag so the card that is changing is the one that goes inert, matching
 *   [loggingExerciseIds] — but only one at a time, because the picker a swap goes through is a
 *   whole screen and the user is only ever on one of them.
 * @param isPaused whether the session is suspended. The clocks stop and every entry row goes
 *   read-only; `LogSetUseCase` refuses a set logged into a paused session, so a field that
 *   still accepted typing would be collecting keystrokes for a write that cannot happen.
 * @param togglingSetIds the sets whose tick is being written, either way. Ids rather than a flag
 *   for the reason [loggingExerciseIds] is a set: the rows are independent, and a slow write
 *   against one set has no business freezing the tick on the four above it. What it buys is the
 *   second tap: a tick pressed twice in the time one write takes would otherwise send the same
 *   row both ways, and which of the two landed last decides what the set ends up saying.
 * @param loggingExerciseIds the exercises whose sets are being written. Ids rather than a flag
 *   because the rows are independent: a slow write against the bench press has no business
 *   disabling the tick on the curls. A *set* of them rather than one id for the same reason
 *   again — a superset is logged by alternating between two exercises, so two writes really can
 *   be in flight at once, and a single id would have the first one to come back re-arm the tick
 *   on the other while its own insert was still running.
 *
 *   Any of them in flight does stop the workout being *closed out* — see [canFinish] — because
 *   that is a race the row-level independence does not cover.
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
 * @param deletedSet the set that has just been taken out of the workout, held for as long as the
 *   offer to undo stands, or null. The whole row rather than its id, because the row is the only
 *   copy of it left: it is gone from the store, and what puts it back is its values and the
 *   number it had. Carried in the state rather than pushed down a channel for the reason
 *   [finishedSessionId] is — and because the offer has to survive a rotation, which a one-shot
 *   event would not.
 * @param rest the countdown between two sets, straight off `RestTimerController`. Not folded
 *   into [isRestSheetOpen] as one nullable, because they are two questions: the rest goes on
 *   running with the sheet swiped away, which is the whole of what the header's pill is for.
 * @param isRestSheetOpen whether the sheet is up over the workout. Raised by a set being marked
 *   done and lowered by the skip, the swipe and the countdown reaching zero — the last of those
 *   without the timer going idle, so that a rest slept through stays distinguishable from one
 *   the user called off. Never true over a rest that is not [com.opengym.core.domain.timer.RestTimerState.Running]
 *   or `Paused`: the sheet is a thing to look at while time passes.
 * @param restTarget what the next set is expected to be, shown on the sheet so the rest is
 *   worth watching rather than waiting out. Null where nothing has been logged to predict it
 *   from. Carried as quantities rather than as `80 kg × 5`, because the unit is the ambient
 *   one and the "×" is a word — both belong in composition.
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
    val isReordering: Boolean = false,
    val refusedReorders: Int = 0,
    val swappingExerciseId: Long? = null,
    val isPaused: Boolean = false,
    val loggingExerciseIds: Set<Long> = emptySet(),
    val togglingSetIds: Set<Long> = emptySet(),
    val isFinishing: Boolean = false,
    val isDiscarding: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val finishedSessionId: Long? = null,
    val isDiscarded: Boolean = false,
    val deletedSet: WorkoutSet? = null,
    val rest: RestTimerState = RestTimerState.Idle,
    val isRestSheetOpen: Boolean = false,
    val restTarget: RestTarget? = null,
    val isLoading: Boolean = true,
    val message: ActiveSessionMessage? = null,
) {

    /** A session id that no longer resolves to a row — as opposed to one still loading. */
    val isMissing: Boolean get() = !isLoading && header == null

    /** Whether the workout is over, either way. */
    val isFinished: Boolean get() = finishedSessionId != null || isDiscarded

    /**
     * Whether the workout can be changed at all right now.
     *
     * One question asked in three places — the entry rows, the layout controls and the reorder
     * sheet — and one answer, because the domain gives one: every write path refuses a session
     * that is not `Active`, so a control still live over a paused or finished workout is a
     * control whose only outcome is a refusal.
     */
    val isEditable: Boolean get() = !isPaused && !isBusy

    /**
     * Whether any action on the workout should be inert.
     *
     * One flag for four situations that all mean "a write is in flight or the session is over",
     * so a screen does not have to re-derive the same conjunction at every button.
     *
     * [loggingExerciseIds] is deliberately not part of it. A set write in flight against one
     * exercise must not disable the tick on another — that independence is why the rows are
     * keyed at all — and folding it in here would also stop the draft being saved for the
     * length of every write. The one action it does have to stop is the finish, which asks
     * [canFinish] instead.
     */
    val isBusy: Boolean get() = isFinishing || isDiscarding || isFinished

    /**
     * Whether the workout can be closed out right now.
     *
     * A set write in flight is a reason to wait. `LogSetUseCase` reads the session state once,
     * before it inserts, so a finish landing in between writes the set into a session already
     * stamped closed — which the recap then either undercounts or, if it was the only set,
     * refuses as a workout with nothing logged. Neither is a thing the lifter did.
     *
     * Drawn as an inert END rather than as a refusal, because the wait is a fraction of a
     * second and a message about it would outlive what it was about.
     */
    val canFinish: Boolean get() = !isBusy && loggingExerciseIds.isEmpty()
}

/**
 * What the set after this rest is expected to be.
 *
 * There is no plan on this screen to read it off — a session's exercises carry no target sets,
 * and the wireframe's `TARGET 80 × 5` belongs to a workout started from a template. What stands
 * in for it is the set that has just been logged, which is what the lifter is about to do again:
 * a rest is the gap between two attempts at the same thing, and "do that again" is right far
 * more often than it is wrong. Where it is wrong the user is about to type over it anyway.
 *
 * Never carried for a rest that follows a superset's first exercise, because no rest is started
 * there at all — the next set is the *other* exercise's, and the screen moves straight on. See
 * `Supersets.isRestDueAfter`.
 *
 * @param weight in milligrams, matching `WorkoutSet.weight`, or null for a bodyweight set.
 * @param reps how many, or null for a set that recorded none.
 */
@Immutable
data class RestTarget(
    val weight: Long?,
    val reps: Int?,
)

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
 * @param supersetGroupName what its superset is called — the `A` of `A1` — or null for an
 *   exercise that stands alone. Carried beside [supersetLabel] rather than read back out of it:
 *   the label's format is `SupersetLabels`' own, and the one caller that wants the group by
 *   itself is the join under this card, which names it.
 * @param sets the sets logged against it, in set-number order, each as the fields it is drawn
 *   in. Text rather than the stored `WorkoutSet`, for the reason [SetEntry] gives at length:
 *   every one of these rows is live — a logged set is corrected by typing into the row it is
 *   already in — and a field being typed into does not hold a number.
 * @param entry the set being typed into it. Every exercise has one — there is always a next set
 *   to log, and a screen that hid the fields until an "add set" tap would put a tap in front of
 *   the thing the screen exists to do. Separate from [sets] because it is the one row that is
 *   not a set yet: what writes it is a tick, and what writes the others is leaving them.
 * @param entryPrevious what was done on the *next* set last time, or null where there is
 *   nothing to compare it against. The counterpart of [LoggedSet.previous], which carries the
 *   same fact for the rows above it — one per drawn row, because that is how the table reads
 *   it and because the alignment is not a matter of counting: see [hasHistory].
 * @param hasHistory whether this exercise has ever been performed before. **False is the
 *   "first time" state**, and it is a separate question from whether any particular row has a
 *   previous set: a lifter three sets into a workout that was two sets last time has history
 *   and empty cells, and the two must not be drawn the same way. False also covers an exercise
 *   whose whole history is warm-ups, which is never having performed it — see
 *   `SessionRepository.getPreviousSets`.
 */
@Immutable
data class SessionExerciseRow(
    val sessionExerciseId: Long,
    val position: Int,
    val exercise: Exercise?,
    val muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    val record: PersonalRecord? = null,
    val supersetLabel: String? = null,
    val supersetGroupName: String? = null,
    val isLinkedToNext: Boolean = false,
    val sets: List<LoggedSet> = emptyList(),
    val entry: SetEntry = SetEntry(),
    val entryPrevious: PreviousSet? = null,
    val hasHistory: Boolean = false,
) {

    /** How many sets sit under it, performed or not, which is what the table draws. */
    val setCount: Int get() = sets.size

    /**
     * How many of them were actually performed, which is what makes the exercise "done".
     *
     * The rule `SessionStats.exerciseBreakdown` applies, restated over the same rows so the
     * header's progress rail and the recap of the same workout cannot disagree. The two counts
     * are the same number only while nothing incomplete is ever persisted, which is not a
     * property this screen has: a set can be logged and left unticked.
     */
    val completedSetCount: Int get() = sets.count(LoggedSet::isCompleted)

    /** The number the next set will be shown under, which is its place in the table, from 1. */
    val nextSetNumber: Int get() = sets.size + 1

    /**
     * Whether the half-typed set can be written as it stands.
     *
     * [SetEntry.canLog] is the half about the *values*; this adds the half about the exercise,
     * which the entry cannot see. `LogSetUseCase` asks `SetValidator` for both, so a tick that
     * was live on the strength of the values alone is a tick whose set is sometimes refused the
     * moment it is pressed — see [records].
     */
    val canLogEntry: Boolean get() = entry.canLog && entry.records(exercise?.type)

    /**
     * Whether this exercise's sets are measured in time rather than in reps.
     *
     * The one dead end on this screen worth saying out loud: the entry row is a weight and a rep
     * count, so there is nowhere to put the duration a completed set of it has to record, and
     * every set logged against it would be refused. The card says so rather than leaving a tick
     * that does nothing.
     */
    val isLoggedByDuration: Boolean
        get() = exercise?.type?.let(SetValidator::requiredDimension) == SetDimension.DURATION
}

/**
 * Whether the entry fills in the dimension an exercise of [exerciseType] is logged in.
 *
 * The question `SetValidator` will ask of the written set, asked of the fields it will be built
 * from — which is what keeps the tick and the write path agreeing about the same set. A missing
 * dimension is silent rather than an error, exactly as an empty row is: a lifter who has typed a
 * weight and not yet the reps has not made a mistake, they are half way through typing, and a
 * red message under the row would say otherwise on every set of every workout.
 *
 * Null — an exercise deleted from the library since the session started — is loggable, matching
 * what `LogSetUseCase` is passed for it: with no catalogue row there is no logging mode to check
 * against, and the bounds still hold.
 */
internal fun SetEntry.records(exerciseType: ExerciseType?): Boolean =
    when (exerciseType?.let(SetValidator::requiredDimension)) {
        SetDimension.REPS -> reps.isNotBlank()

        // Nothing the user can type on this screen fills this in. See [SessionExerciseRow.isLoggedByDuration].
        SetDimension.DURATION -> false

        null -> true
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
 *
 * `@Serializable` because a set being typed over an already-logged one is the one piece of this
 * screen's typed input the workout draft does not carry — it rides in saved state instead. See
 * `ActiveSessionViewModel.typedInput`.
 */
@Immutable
@Serializable
data class SetEntry(
    val weight: String = "",
    val reps: String = "",
    val rpe: Int? = null,
    val setType: SetType = SetType.NORMAL,
    val notes: String? = null,
    val error: SetEntryError? = null,
) {

    /**
     * Whether what is typed is enough to log, as far as the values themselves go.
     *
     * A set with nothing typed is not an error — the fields start empty and refusing an empty
     * row before it has been filled in would put a red message under every exercise on the
     * screen — but it is not loggable either.
     *
     * Not the whole of whether the tick is live: which dimension the set *has* to record is the
     * exercise's, not the entry's, so the screen asks [SessionExerciseRow.canLogEntry].
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
 * A set that is already written, as the row it is drawn in.
 *
 * ### Why a logged set carries text
 *
 * Because it is still being typed into. There is no separate editor and no mode: every row of
 * every table on this screen is live from the moment it is drawn, and a correction is written
 * when the user leaves the row — see `LoggedSetTable`. So a logged set needs exactly what the
 * unwritten one needs, which is [SetEntry]: the field contents as typed, and the reason they
 * would be refused. `8.` and `` are states a user passes through on the way to a number, and a
 * `WorkoutSet` cannot hold either of them.
 *
 * The values are seeded from what is stored — the weight formatted back into the unit it will be
 * typed in, the way a restored draft is — until the user touches the row, after which what they
 * typed is what is shown. Only [SetEntry.notes] is unused: the table has no column for prose, and
 * the stored note is carried through the write untouched; see `EditSetUseCase`.
 *
 * @param id the `workout_set` row. The number it is drawn under is *not* carried: that is its
 *   place in the table, which the table already knows and which a deletion elsewhere changes.
 * @param entry the values as they stand, validated against `SetValidator` on every keystroke
 *   exactly as an entry row is.
 * @param isCompleted whether it counts. Kept out of [entry] because it is the one thing about a
 *   logged set that is not typed: the tick writes it on its own, straight away.
 * @param isEdited whether [entry] is the user's typing rather than the stored row read out. False
 *   for almost every row almost always, and what a screen checks before writing one on its way
 *   out: the workout is a lazy list, so a card scrolled far enough away is taken out of the
 *   composition, and a row that leaves that way never reports losing focus. See `LoggedSetTable`.
 * @param isSaving a correction to this row is in flight. The row goes inert — it is the only
 *   copy of what was typed, and a second write of the same correction is a race with itself.
 * @param previous what was done on the matching set last time, or null where there is nothing
 *   to compare this row against — the exercise is new, last time was shorter, or this row is a
 *   warm-up. Carried per row rather than as one list on the exercise because the rows and the
 *   reference are not the same list: warm-ups are skipped on both sides, so which previous set
 *   a row is compared against depends on the *types* of the rows above it, and that is settled
 *   once, where the rows are built, rather than recounted by whatever draws them.
 * @param canComplete whether the tick can be pressed, which is the same narrower question
 *   [SessionExerciseRow.canLogEntry] asks of the row being typed: a set that does not fill in the
 *   dimension its exercise is logged in would be refused by `SetValidator` the moment it was
 *   marked done. It matters here because a row can now arrive **empty** — a template that named
 *   an exercise without pricing it lays down a set with nothing in it — where before this every
 *   stored set had been through a write path that checked it. Always true for a set that is
 *   already ticked, since taking a tick *off* is never refused.
 */
@Immutable
data class LoggedSet(
    val id: Long,
    val entry: SetEntry = SetEntry(),
    val isCompleted: Boolean = false,
    val isEdited: Boolean = false,
    val isSaving: Boolean = false,
    val previous: PreviousSet? = null,
    val canComplete: Boolean = true,
)

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
     * way past itself: the sets go first — tap one and delete it — or the whole workout does.
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

    /** The new running order could not be saved. The workout is in the order it was. */
    REORDER_FAILED,

    /**
     * The slot has sets logged against it, so what is in it cannot be changed.
     *
     * The second refusal on this screen that is a *rule* rather than a failure, and a separate
     * message from [EXERCISE_HAS_SETS] because the way past it is not the same one: a swap can
     * also be had by adding the new exercise and leaving the old one where it is, which a
     * removal has no equivalent of.
     */
    SWAP_HAS_SETS,

    /** The exercise could not be swapped. The slot holds what it held. */
    SWAP_FAILED,

    /** The correction was refused by the domain after passing the screen's own check. */
    SET_EDIT_REJECTED,

    /** The set could not be deleted. It is still in the workout, exactly as it was. */
    DELETE_SET_FAILED,

    /**
     * The tick could not be written. The set is however it was before the tap.
     *
     * One message for both directions, because there is one thing to say about a write that
     * changed nothing — and because the row itself is the other half of the answer: it is still
     * drawn as whatever the store says it is.
     */
    SET_COMPLETION_FAILED,

    /**
     * The deleted set could not be put back.
     *
     * The one message on this screen that reports the loss of something the user did, which is
     * why it says so rather than saying "try again": the offer to undo is gone with it, and what
     * is left is to log the set over.
     */
    RESTORE_SET_FAILED,

    /**
     * The deleted set is back, but at the end of its exercise rather than where it was.
     *
     * `SessionError.SetTableNotRenumbered`, which is the undo half-landing: the row was written
     * and the renumbering that would have slotted it back in was not. A separate message from
     * [RESTORE_SET_FAILED] because the advice in that one — log it again — would have the user
     * record a set that is already in the table a second time. There is nothing to do about the
     * order, so this says where the set went and stops there.
     */
    SET_RESTORED_AT_END,

    /**
     * Opening the screen could not put it back where the workout was left.
     *
     * The only message here about something the user did not do: reattaching the state machine,
     * reading the draft back and realigning the pause all run on open, and all three touch a
     * store. It is said rather than swallowed because what it costs is visible — the half-typed
     * set is gone, or the clock is counting a pause as work — and a screen that looked wrong
     * with no explanation is the worse of the two.
     */
    RESTORE_FAILED,
}

/**
 * Why the entry as typed cannot be logged.
 *
 * Two of the three are the screen's own words and are resolved from its resources; the third is
 * `SetValidator`'s, which names the field, its bound and the value it saw, and is shown as it
 * stands — a message this screen paraphrased would be a second copy of a rule that lives in the
 * domain.
 */
@Serializable
sealed interface SetEntryError {

    /** The weight field holds something that is not a number. */
    @Serializable
    data object WeightNotANumber : SetEntryError

    /** The rep field holds something that is not a number. */
    @Serializable
    data object RepsNotANumber : SetEntryError

    /** The values parsed and the domain refused them. [reason] is the validator's wording. */
    @Serializable
    data class Refused(val reason: String) : SetEntryError
}
