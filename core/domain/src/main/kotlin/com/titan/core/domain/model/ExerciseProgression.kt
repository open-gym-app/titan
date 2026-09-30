package com.titan.core.domain.model

/**
 * One exercise, read over a window: what it has been doing, and what its bests are.
 *
 * What [com.titan.core.domain.usecase.ExerciseProgressionUseCase] assembles and the
 * per-exercise drill-down draws whole. Four figures that answer "am I getting stronger at
 * this", plus the two series behind them.
 *
 * ### The window covers the series, not the records
 *
 * [attempts] and [estimates] are the selected timeframe's; [records] and [totalSets] are
 * **all-time**, and deliberately so. A ninety-day window is a way of looking at a trend, not a
 * claim that the lifter's best ever happened inside it — a card headed `1RM (EST)` that fell
 * to a lower number because the user narrowed the window would be reporting a record they
 * have not lost.
 *
 * @property attempts one entry per session the exercise was performed in, **oldest first** —
 *   the order both charts plot in. The table under them reverses it, because a list of
 *   attempts is read newest first and a series is read left to right.
 * @property estimates the projected one-rep max of each attempt's top set, oldest first. A
 *   subset of [attempts]: one whose top set carries no weight or no reps projects nothing, and
 *   is left out rather than plotted as a zero.
 * @property records the best of each kind on file, all-time, as the PR engine wrote them. At
 *   most one entry per [PrType]; an exercise that has only ever been done for reps has no
 *   [PrType.MAX_WEIGHT] entry and the card that would draw it says so.
 * @property newRecords the kinds among [records] that were set in the most recent attempt —
 *   what the `NEW` badge marks. Empty when the window holds no attempts, which is the honest
 *   answer: nothing was broken in a period nothing happened in.
 * @property totalSets completed working sets of this exercise, all-time. An aggregate rather
 *   than [attempts]`.sumOf { it.sets }`, which would be the window's figure wearing an
 *   all-time label.
 */
data class ExerciseProgression(
    val attempts: List<ExerciseAttempt>,
    val estimates: List<ExerciseEstimate>,
    val records: Map<PrType, PersonalRecord>,
    val newRecords: Set<PrType>,
    val totalSets: Int,
) {

    /**
     * Whether there is a trend here at all: two attempts is the least a line or a bar chart
     * can honestly draw, and the same threshold `:core:ui`'s charts apply to their own series.
     *
     * Asked by the screen to choose between the charts and the "keep logging" prompt, and
     * asked here rather than by counting at the call site so the two charts and the table
     * cannot fall out of step about it.
     */
    val hasTrend: Boolean
        get() = attempts.size >= MIN_TREND_ATTEMPTS

    /** Nothing logged for this exercise in the window at all. */
    val isEmpty: Boolean
        get() = attempts.isEmpty()

    private companion object {
        /**
         * Two, matching `MIN_PLOTTABLE_ENTRIES` in `:core:ui`.
         *
         * One session is not progression: a chart of it draws a single bar filling its own
         * track, which claims a comparison that has not been made. The duplication of the
         * number is deliberate — this decides what the *screen* says, the other decides what a
         * chart draws, and a screen that let the charts decide would show a table and two
         * empty cards.
         */
        const val MIN_TREND_ATTEMPTS = 2
    }
}

/**
 * What one attempt's top set projects to as a single rep.
 *
 * ### Why the top set rather than the best of every set
 *
 * The PR engine takes the *highest* projection across all of a session's sets, because a
 * record is a record however it was earned. A trend line is a different question: it is read
 * to see whether the lift is moving, and a back-off set of twelve that happens to project
 * higher than the heaviest single would draw the lifter's progress from the set they were not
 * trying hardest on. So the line follows the top set, and the `1RM (EST)` card above it —
 * which is the engine's own figure — is allowed to sit above the line's last point.
 *
 * @property finishedAt when the attempt's session was closed out, epoch millis.
 * @property estimate the projection in milligrams, rounded to the stored unit as
 *   [com.titan.core.domain.usecase.PrDetectionUseCase] rounds it, and by the formula the
 *   user chose — see [OneRepMaxFormula].
 */
data class ExerciseEstimate(
    val finishedAt: Long,
    val estimate: Long,
)
