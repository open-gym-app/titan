package com.opengym.core.domain.model

import com.opengym.core.domain.timer.RestTimerSnapshot
import com.opengym.core.domain.timer.RestTimerState
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Everything needed to put the user back in the middle of a workout after the process
 * that was running it is gone.
 *
 * This is the *draft*: the whole of the in-progress state as one value, written
 * whenever it changes and read once on relaunch. It is deliberately self-contained —
 * a restore reads this and nothing else — which means it overlaps with the session
 * rows on disk rather than replacing them. Where an id is present ([sessionId],
 * [ActiveExercise.sessionExerciseId], [ActiveSet.id]) it points at the row the entry
 * came from, so restoring updates those rows instead of duplicating them; where it is
 * null the entry exists only here, and only this draft was keeping it.
 *
 * The pieces that live *only* here are the ones no table has a column for: which
 * exercise the user was looking at, how long the session has spent paused, and the
 * rest countdown.
 *
 * Timestamps are epoch milliseconds UTC, matching every `*_at` column and the reading
 * [com.opengym.core.domain.time.Clock] gives; intervals are [Duration], matching the
 * rest timer. See [SessionState] for the same split.
 *
 * @property sessionId the `workout_session` row this draft belongs to. A draft is
 *   never written before the session exists — [com.opengym.core.domain.usecase.StartSessionUseCase]
 *   inserts the row first — so this is always a real id.
 * @property startedAt when the session was created, i.e. [SessionState.Active.startedAt].
 * @property pausedDuration how long the session has spent suspended across all of its
 *   *finished* pauses. Subtracted from wall-clock time to get [elapsedAt]. Note this is
 *   a different question from the one [SessionSummary.durationMillis] answers, which
 *   counts paused stretches in on purpose; a running session's on-screen timer is
 *   expected to stop while it is paused, a finished session's total is not.
 * @property pausedAt when the pause currently in flight began, or null when the session
 *   is running. Non-null is what makes this draft restore as [SessionState.Paused], and
 *   the time accrued since is added to [pausedDuration] on the way out of the pause.
 * @property exercises the exercises of the session in the order they are shown, each
 *   with its sets.
 * @property currentExerciseIndex which of [exercises] the user was on. Zero for a
 *   session with no exercises yet, and otherwise a real index — a draft pointing past
 *   the end of its own list would restore onto nothing.
 * @property restTimer the rest countdown, as an absolute deadline rather than a
 *   remainder. See [RestTimerSnapshot] for why that distinction is the whole point.
 *   Independent of [pausedAt]: pausing the *session* does not pause the rest, here or in
 *   [com.opengym.core.domain.usecase.PauseSessionUseCase], so a draft paused for twenty
 *   minutes with a rest in flight restores with its elapsed time frozen and its rest
 *   [RestTimerState.Finished]. That asymmetry is intended — the session clock measures
 *   how long the user has been working out, which a pause suspends, while the rest
 *   measures how long they have been standing still, which it does not. A caller that
 *   wants the countdown held over a pause pauses the timer itself, and the draft then
 *   carries [RestTimerSnapshot.Paused], whose remainder does not go stale.
 */
@Serializable
data class ActiveSession(
    val sessionId: Long,
    val startedAt: Long,
    val pausedDuration: Duration = Duration.ZERO,
    val pausedAt: Long? = null,
    val exercises: List<ActiveExercise> = emptyList(),
    val currentExerciseIndex: Int = 0,
    val restTimer: RestTimerSnapshot = RestTimerSnapshot.Idle,
) {
    init {
        require(sessionId > 0) { "a draft belongs to a session row, was $sessionId" }
        require(pausedDuration >= Duration.ZERO) { "a session cannot owe paused time, was $pausedDuration" }
        require(pausedAt == null || pausedAt >= startedAt) {
            "a session cannot be paused ($pausedAt) before it started ($startedAt)"
        }
        require(currentExerciseIndex >= 0) { "currentExerciseIndex cannot be negative, was $currentExerciseIndex" }
        require(exercises.isEmpty() || currentExerciseIndex in exercises.indices) {
            "currentExerciseIndex ($currentExerciseIndex) is past the end of ${exercises.size} exercises"
        }
    }

    /** True when the session was suspended at the moment this draft was written. */
    val isPaused: Boolean get() = pausedAt != null

    /** The exercise the user was on, or null for a session with no exercises yet. */
    val currentExercise: ActiveExercise? get() = exercises.getOrNull(currentExerciseIndex)

    /** Every set of every exercise the user has ticked off, across the whole draft. */
    val completedSets: List<ActiveSet> get() = exercises.flatMap { it.completedSets }

    /**
     * The lifecycle state this draft restores to, which
     * [com.opengym.core.domain.repository.SessionStateRepository] can be seeded with so
     * the use cases see the session the user left rather than [SessionState.Idle].
     *
     * Never [SessionState.Finished]: a finished session is not in progress, and its
     * draft should have been cleared.
     */
    fun toSessionState(): SessionState = when (pausedAt) {
        null -> SessionState.Active(sessionId = sessionId, startedAt = startedAt)
        else -> SessionState.Paused(sessionId = sessionId, pausedAt = pausedAt)
    }

    /**
     * How long the user has actually been working out as of [nowMillis], epoch millis
     * UTC — wall-clock time since [startedAt], less every stretch the session spent
     * paused.
     *
     * A session that is paused right now stops accruing: the answer is measured to
     * [pausedAt] instead of to `now`, so a draft written before a pause and read an
     * hour into it restores the same elapsed time it was saved with.
     *
     * Floored at zero, which only comes up if the wall clock stepped backwards between
     * the save and the load. A rest that reads as slightly shorter is a better outcome
     * than a negative one.
     */
    fun elapsedAt(nowMillis: Long): Duration {
        val until = pausedAt ?: nowMillis
        return ((until - startedAt).milliseconds - pausedDuration).coerceAtLeast(Duration.ZERO)
    }

    /**
     * The rest timer state to hand [com.opengym.core.domain.timer.RestTimerController.restore]
     * when this draft is read back at [nowMillis], epoch millis UTC.
     *
     * A rest whose deadline has passed comes back [RestTimerState.Finished], not
     * running with time left. See [RestTimerSnapshot.toStateAt].
     */
    fun restTimerStateAt(nowMillis: Long): RestTimerState = restTimer.toStateAt(nowMillis)
}
