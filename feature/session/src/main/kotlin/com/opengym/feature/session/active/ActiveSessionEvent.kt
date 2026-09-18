package com.opengym.feature.session.active

import com.opengym.core.domain.model.SetType
import kotlin.time.Duration

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
    data class WeightChanged(
        val sessionExerciseId: Long,
        val text: String,
    ) : ActiveSessionEvent

    /** The rep field changed. */
    data class RepsChanged(
        val sessionExerciseId: Long,
        val text: String,
    ) : ActiveSessionEvent

    /**
     * The rating changed, or was cleared with null.
     *
     * Null is reachable: the rating menu's last item is "not rated", which is the only way back
     * to an unrated set.
     */
    data class RpeChanged(
        val sessionExerciseId: Long,
        val rpe: Int?,
    ) : ActiveSessionEvent

    /** The kind of set changed. */
    data class SetTypeChanged(
        val sessionExerciseId: Long,
        val setType: SetType,
    ) : ActiveSessionEvent

    /** The per-set note field was opened, or closed again with null. */
    data class NotesChanged(
        val sessionExerciseId: Long,
        val text: String?,
    ) : ActiveSessionEvent

    /** Write this exercise's entry row as a set. */
    data class SetLogged(val sessionExerciseId: Long) : ActiveSessionEvent

    /**
     * Put the exercise's target — see `SessionExerciseRow.target` — into its weight and reps
     * fields, as text, exactly as if the lifter had typed it.
     *
     * Only the fields: the tick is untouched, and the set is still logged by [SetLogged]. The
     * target line is a shortcut for typing two numbers, not for deciding the set happened, and
     * an event that did both would log a set nobody has lifted yet. Ignored for an exercise
     * with no target, which draws no line to raise it from.
     */
    data class TargetFilled(val sessionExerciseId: Long) : ActiveSessionEvent

    /*
     * ── Correcting a set that is already logged ─────────────────────────────────────────
     *
     * There is no open row, so every one of these names its own. A logged set is corrected by
     * typing into the row it is already in — all of them are live, all of the time, and there is
     * no editor to open or to close. What used to be "the open row" is now as many half-typed
     * corrections as the user has started, which is at most one per row and usually none.
     *
     * The four that carry values do not write. What writes is [SetEditCommitted], raised when
     * the row is left — the two picked off a menu are the exception and say why below.
     */

    /** A logged row's weight field changed. Carries text, not a number — see [SetEntry]. */
    data class SetEditWeightChanged(
        val sessionExerciseId: Long,
        val setId: Long,
        val text: String,
    ) : ActiveSessionEvent

    /** A logged row's rep field changed. */
    data class SetEditRepsChanged(
        val sessionExerciseId: Long,
        val setId: Long,
        val text: String,
    ) : ActiveSessionEvent

    /**
     * A logged row's rating changed, or was cleared with null.
     *
     * Written on the spot rather than waiting for [SetEditCommitted]. A rating is picked off a
     * closed scale of nine in a menu — see `RpePicker` — so the pick *is* the answer: there are
     * no half-typed states to pass through and nothing to leave the row to finish. A menu that
     * shut without writing would be a change the user watched happen and would not find later.
     */
    data class SetEditRpeChanged(
        val sessionExerciseId: Long,
        val setId: Long,
        val rpe: Int?,
    ) : ActiveSessionEvent

    /** A logged row's set type changed. Written on the spot, for [SetEditRpeChanged]'s reason. */
    data class SetEditTypeChanged(
        val sessionExerciseId: Long,
        val setId: Long,
        val setType: SetType,
    ) : ActiveSessionEvent

    /**
     * The user has finished with a logged row: its fields lost focus, or the keyboard's DONE was
     * pressed. Write what is in it.
     *
     * This is the whole of "saving a correction". There is no confirm button and no cancel,
     * because there is no panel to put them on — see `LoggedSetTable`. A row whose values are
     * unchanged writes nothing, and one whose values `SetValidator` refuses keeps them, with the
     * reason under it, until they are a set again.
     */
    data class SetEditCommitted(
        val sessionExerciseId: Long,
        val setId: Long,
    ) : ActiveSessionEvent

    /**
     * Take one set out of the workout.
     *
     * Names its set, because what raises it is a drag across a row in the table. Deleting used to
     * be a word under an editor's fields; it is a gesture now, for the reason `SwipeToDelete`
     * gives.
     *
     * Not confirmed, and reversible instead: what follows is an offer to undo, which the
     * screen puts on screen for as long as it stands. See `DeleteSetUseCase`.
     */
    data class SetDeleted(
        val sessionExerciseId: Long,
        val setId: Long,
    ) : ActiveSessionEvent

    /**
     * Tick a logged set off, or take the tick back off it.
     *
     * The tick is the control a set is written with, and it is therefore the control that gets
     * pressed by accident — so it is also the way back, and it is the same control in the same
     * cell. Carries the state it should end up in rather than "toggle": the tap
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

    /*
     * ── The warm-up and the cool-down (#214) ────────────────────────────────────────────
     *
     * Three events for two cards, each naming which card it is about rather than one pair of
     * events per card. The two behave identically — open it, take it, or send it away — and
     * spelling that twice would be six cases the ViewModel has to keep agreeing about.
     */

    /** The card was folded open or shut. */
    data class MobilityExpanded(
        val kind: MobilityKind,
        val expanded: Boolean,
    ) : ActiveSessionEvent

    /**
     * Put the card's suggestion into the workout: its movements, and for the warm-up its
     * ramp-up sets.
     *
     * The one thing on this screen that writes exercises the user did not pick. It is a tap on
     * a card that lists exactly what it will add, which is the whole of why nothing is added
     * without one — see `WarmupSuggestion`.
     */
    data class MobilityAccepted(val kind: MobilityKind) : ActiveSessionEvent

    /**
     * Take the card off this workout's screen.
     *
     * Remembered for this session and no longer: the suggestion is not a setting, and a lifter
     * who skips the warm-up today has not said anything about tomorrow. The setting that does
     * say something about tomorrow is in Settings › Workout.
     */
    data class MobilityDismissed(val kind: MobilityKind) : ActiveSessionEvent

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

    /*
     * ── The rest between two sets ──────────────────────────────────────────────────────
     *
     * None of these names an exercise. There is one rest at a time — it is the gap between the
     * set that has just been logged and the next one, whichever exercise that turns out to be —
     * and `RestTimerController` holds exactly one countdown to match.
     *
     * Nothing here *starts* a rest either. That is not something the user asks for: a rest
     * begins because a set was marked done, which is [SetLogged] and [SetCompletionToggled]
     * doing their own jobs. What is left for this group is the three things a lifter does to a
     * countdown already running, plus the two about whether the sheet is on screen.
     */

    /**
     * Move the countdown by [delta] — `+15s` from the extend button, `-15s` from the shorten
     * one.
     *
     * Signed, and one event rather than two, for the reason `RestTimerSheet` raises it that
     * way: what happens to the timer is the same call either way, and a second event would be a
     * second place to get the sign wrong.
     */
    data class RestAdjusted(val delta: Duration) : ActiveSessionEvent

    /** Hold the countdown where it is, or set it going again. */
    data object RestPauseToggled : ActiveSessionEvent

    /**
     * The rest is over now — the user is back under the bar.
     *
     * Distinct from letting it run out: this returns the timer to idle and rings nothing, which
     * is what `RestTimerController.cancel` is for.
     */
    data object RestSkipped : ActiveSessionEvent

    /**
     * The sheet was swiped away, and the countdown carries on behind it.
     *
     * The gesture is how a lifter gets at the set they logged wrong two minutes ago without
     * throwing away the rest they are in the middle of. The header's pill is what says the
     * timer is still running, and [RestSheetRequested] is the way back.
     */
    data object RestSheetDismissed : ActiveSessionEvent

    /** Bring the sheet back up on a rest that is still going. */
    data object RestSheetRequested : ActiveSessionEvent

    /** End the workout and go to the recap. */
    data object FinishRequested : ActiveSessionEvent

    /** Ask whether to throw the workout away. */
    data object DiscardRequested : ActiveSessionEvent

    /** Yes, throw it away. */
    data object DiscardConfirmed : ActiveSessionEvent

    /** No, keep it. */
    data object DiscardDismissed : ActiveSessionEvent

    /**
     * The workout came on screen — the activity started, or the user navigated back to it.
     *
     * Not a user action, which is why it reads as a fact rather than a request. It exists
     * because one thing about a workout depends on whether anybody is looking at it: the idle
     * nudge, which has nothing to say to somebody who has the session in front of them. See
     * `IdleSessionMonitor`.
     */
    data object ScreenShown : ActiveSessionEvent

    /**
     * The workout went off screen: backgrounded, or the phone locked.
     *
     * The moment the idle nudge becomes worth arming, and the reason it is armed *here* rather
     * than when a set is logged — this is the last thing that happens before a session can be
     * abandoned, and it is also the moment the deadline is finally knowable, since nothing else
     * can happen to the session until the user comes back.
     */
    data object ScreenHidden : ActiveSessionEvent

    /** The transient message has been shown. */
    data object MessageShown : ActiveSessionEvent
}

/**
 * Which of the two suggestion cards an event is about.
 *
 * A warm-up and a cool-down are the same card with different contents and different ends of the
 * workout to land on — see `MobilityCard` — so they are one type with a discriminator rather
 * than two of everything.
 */
enum class MobilityKind {
    /** Before the first exercise. Carries the ramp-up sets; the cool-down has none. */
    WARMUP,

    /** After the last one. */
    COOLDOWN,
}
