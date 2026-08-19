package com.opengym.feature.session.active

import com.opengym.core.domain.model.SetType

/**
 * Everything the user can do to a workout in progress.
 *
 * One sealed type per screen, so the stateless half emits and knows nothing else — see
 * CONTRIBUTING.md § Screens.
 *
 * Every event about a set names the exercise it is for. There is no exercise "open" on this
 * screen — see [ActiveSessionUiState] — so an event that left it implicit would be one the
 * ViewModel had to resolve against a selection that no longer exists.
 */
sealed interface ActiveSessionEvent {

    /** The weight field changed. Carries text, not a number — see [SetEntry]. */
    data class WeightChanged(val sessionExerciseId: Long, val text: String) : ActiveSessionEvent

    /** The rep field changed. */
    data class RepsChanged(val sessionExerciseId: Long, val text: String) : ActiveSessionEvent

    /**
     * The rating changed, or was cleared with null.
     *
     * Null is reachable: the rating menu's last item is "not rated", which is the only way back
     * to an unrated set.
     */
    data class RpeChanged(val sessionExerciseId: Long, val rpe: Int?) : ActiveSessionEvent

    /** The kind of set changed. */
    data class SetTypeChanged(val sessionExerciseId: Long, val setType: SetType) : ActiveSessionEvent

    /** The per-set note field was opened, or closed again with null. */
    data class NotesChanged(val sessionExerciseId: Long, val text: String?) : ActiveSessionEvent

    /** Write this exercise's entry row as a set. */
    data class SetLogged(val sessionExerciseId: Long) : ActiveSessionEvent

    /*
     * ── Correcting a set that is already logged ─────────────────────────────────────────
     *
     * Exactly one set on the whole screen is ever open for correction, and which one is part
     * of the state. So the events that *edit* it name nothing: they are about the open row the
     * way [NameChanged] is about the open dialog, and an id carried on each of them would be a
     * second copy of a fact the state already holds — one that can disagree with it after a
     * table is renumbered under the screen.
     *
     * The three that are not about the open row do name what they are about: [SetEditRequested]
     * is what opens one, [SetDeleted] and [SetCompletionToggled] are gestures on a row that is
     * *not* open — the whole point of both — and [SetDeleteUndone] answers an offer that outlives
     * the row entirely.
     */

    /**
     * Reopen a logged set to correct it.
     *
     * Opening a second one closes the first and drops whatever was typed into it. That is the
     * cost of the row being the affordance — a tap on another set is unambiguous about which
     * set the user now means — and the alternative, a dialog asking about a set they have just
     * looked away from, is worse.
     */
    data class SetEditRequested(val sessionExerciseId: Long, val setId: Long) : ActiveSessionEvent

    /** The open row's weight field changed. Carries text, not a number — see [SetEntry]. */
    data class SetEditWeightChanged(val text: String) : ActiveSessionEvent

    /** The open row's rep field changed. */
    data class SetEditRepsChanged(val text: String) : ActiveSessionEvent

    /** The open row's rating changed, or was cleared with null. */
    data class SetEditRpeChanged(val rpe: Int?) : ActiveSessionEvent

    /** The open row's set type changed. */
    data class SetEditTypeChanged(val setType: SetType) : ActiveSessionEvent

    /** Close the open row without writing. The set keeps the values it had. */
    data object SetEditCancelled : ActiveSessionEvent

    /** Write the open row's values over the set. */
    data object SetEditConfirmed : ActiveSessionEvent

    /**
     * Take one set out of the workout.
     *
     * Names its set, because what raises it is a drag across a row in the table rather than a
     * button in the open editor — there need not be an editor open at all, and if there is, it is
     * as likely to be over another set. Deleting used to be a word under the fields; it is a
     * gesture now, for the reason `SwipeToDelete` gives.
     *
     * Not confirmed, and reversible instead: what follows is an offer to undo, which the
     * screen puts on screen for as long as it stands. See `DeleteSetUseCase`.
     */
    data class SetDeleted(val sessionExerciseId: Long, val setId: Long) : ActiveSessionEvent

    /**
     * Tick a logged set off, or take the tick back off it.
     *
     * The tick is the control a set is written with, and it is therefore the control that gets
     * pressed by accident — so it is also the way back, from the row itself rather than from four
     * taps inside an editor. Carries the state it should end up in rather than "toggle": the tap
     * was on a control that was already showing an answer, and a flip computed against whatever
     * the row holds when the event lands is not what the user pressed.
     *
     * An un-ticked set stays in the table and stops counting. See `MarkSetCompletedUseCase`.
     */
    data class SetCompletionToggled(
        val sessionExerciseId: Long,
        val setId: Long,
        val isCompleted: Boolean,
    ) : ActiveSessionEvent

    /** Put the set that was just deleted back, where it was. */
    data object SetDeleteUndone : ActiveSessionEvent

    /** The offer to undo has been on screen long enough. Nothing happens to the workout. */
    data object SetDeleteDismissed : ActiveSessionEvent

    /**
     * Perform this exercise back to back with the one after it — make them a superset.
     *
     * Names the exercise *above* the join, because what the user tapped is the gap between two
     * cards. Whatever either side is already grouped with comes along, so linking the last
     * member of a superset to the exercise below it grows the superset rather than starting a
     * new one; see `LinkSupersetUseCase`.
     */
    data class SupersetLinked(val sessionExerciseId: Long) : ActiveSessionEvent

    /**
     * Stop performing this exercise back to back with the one after it.
     *
     * Breaks the one join, not the whole group: a superset of three split in the middle leaves a
     * pair and a single. See `UnlinkSupersetUseCase`.
     */
    data class SupersetUnlinked(val sessionExerciseId: Long) : ActiveSessionEvent

    /**
     * Open the exercise picker to add more to this workout.
     *
     * The screen only says that it wants one; what opens is a destination in another feature
     * module, which is `:app`'s to resolve — see `ExercisePicker`.
     */
    data object AddExercisesRequested : ActiveSessionEvent

    /** The picker came back with these, in the order they were ticked. */
    data class ExercisesPicked(val exerciseIds: List<Long>) : ActiveSessionEvent

    /**
     * Perform the workout's exercises in this order from now on.
     *
     * Carries the whole order rather than the one exercise that moved, and by id rather than by
     * position — the same reason every other edit here names a row. A drag across four exercises
     * is one of these, emitted when the finger comes up, not four; see `ReorderableItem`.
     */
    data class ExercisesReordered(val orderedSessionExerciseIds: List<Long>) : ActiveSessionEvent

    /**
     * Do a different exercise in this slot — the machine is taken, or it was the wrong pick.
     *
     * Like [AddExercisesRequested], this only says that the picker is wanted; what opens is
     * another feature module's destination and the answer comes back through `:app`. Which of
     * the two asked is remembered by the screen rather than by the ViewModel, because it is a
     * fact about a navigation in flight — see `ActiveSessionRoute`.
     */
    data class ExerciseSwapRequested(val sessionExerciseId: Long) : ActiveSessionEvent

    /**
     * The picker came back with what should go in that slot.
     *
     * Refused once sets have been logged against the slot: they would stay, attached to a
     * movement they were not performed on. See `SwapSessionExerciseUseCase`.
     */
    data class ExerciseSwapped(
        val sessionExerciseId: Long,
        val exerciseId: Long,
    ) : ActiveSessionEvent

    /**
     * Cut this exercise out of the workout.
     *
     * Refused when sets have been logged against it — the cascade would take them with it — and
     * the refusal is reported rather than confirmed away. See `RemoveSessionExerciseUseCase`.
     */
    data class ExerciseRemoved(val sessionExerciseId: Long) : ActiveSessionEvent

    /** Open the rename dialog on whatever the session is called now. */
    data object NameEditRequested : ActiveSessionEvent

    /** The rename field changed. */
    data class NameChanged(val text: String) : ActiveSessionEvent

    /** Close the rename dialog without writing. */
    data object NameEditCancelled : ActiveSessionEvent

    /** Write the name as typed. Blank clears it, which is the only way back to unnamed. */
    data object NameConfirmed : ActiveSessionEvent

    /** Suspend the session, or put it back into play. */
    data object PauseToggled : ActiveSessionEvent

    /** End the workout and go to the recap. */
    data object FinishRequested : ActiveSessionEvent

    /** Ask whether to throw the workout away. */
    data object DiscardRequested : ActiveSessionEvent

    /** Yes, throw it away. */
    data object DiscardConfirmed : ActiveSessionEvent

    /** No, keep it. */
    data object DiscardDismissed : ActiveSessionEvent

    /** The transient message has been shown. */
    data object MessageShown : ActiveSessionEvent
}
