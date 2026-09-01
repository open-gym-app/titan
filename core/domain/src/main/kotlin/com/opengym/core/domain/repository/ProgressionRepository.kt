package com.opengym.core.domain.repository

import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.MuscleTraining
import com.opengym.core.domain.model.TrainedExercise
import com.opengym.core.domain.model.WeeklyTraining
import com.opengym.core.domain.model.WeeklyVolume
import com.opengym.core.domain.stats.TrainingWeek
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The pre-aggregated history the progression screens are built on.
 *
 * Separate from [SessionRepository], which stores the session aggregate, because nothing
 * here reads a row: every method is a roll-up whose result is one entry per **week**,
 * however many sets or sessions went into it. That is the whole contract —
 * *implementations must aggregate in the database* — and it is what lets a chart cover
 * three years of training without any of it reaching `:core:domain`. A repository that
 * satisfied these by loading history and summing in Kotlin would be correct and would also
 * defeat the point; see `WorkoutSetDao.getVolumeByWeek` for the query shape.
 *
 * ### Weeks, and what a period means
 *
 * Buckets are Monday-to-Sunday UTC, per [TrainingWeek], and each method takes the period as
 * a range of **days**, both ends inclusive. Callers pass a [TrainingWeek.snap]ped range so
 * the days line up with whole buckets; a range that does not is not rejected, it simply
 * returns the buckets its days fall in, with the boundary weeks summed only over the days
 * asked for.
 *
 * ### Absence is not zero
 *
 * A week the user did not train in has **no entry**, rather than a zeroed one. The
 * aggregate is a `GROUP BY` over rows that exist, and manufacturing the gaps would mean a
 * scan of the calendar in SQL to produce rows the caller can derive from the period it
 * already holds. Filling them is the use cases' job, and they do it against the requested
 * period rather than against the first and last rows returned — see
 * [com.opengym.core.domain.usecase.VolumeTrendUseCase].
 */
interface ProgressionRepository {

    /**
     * Volume per week over [days], ascending by week, in milligram-reps.
     *
     * Only completed sets count, the filter every aggregate in this codebase applies —
     * a `workout_set` row can hold planned-but-unperformed work, and counting it would
     * chart weights nobody lifted. Warm-ups **are** included, unlike the PR engine's
     * reading of the same table: a warm-up set is volume the lifter actually moved, and
     * this figure is how much work was done rather than how hard it was.
     *
     * The set's session must also be finished, and the week a set lands in is the week its
     * **session** was finished in — the same rule [getWeeklyTraining] applies, so the two
     * series always agree about which weeks held training. A workout in progress therefore
     * contributes nothing here until it is closed out; see `WorkoutSetDao.getVolumeByWeek`
     * for why that is the side to err on.
     */
    fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>>

    /**
     * Training days and session counts per week over [days], ascending by week.
     *
     * Counts **finished** sessions, by the day they were finished on. A session still in
     * progress is not yet a session the user completed, and a discarded one no longer
     * exists — see [com.opengym.core.domain.usecase.DiscardSessionUseCase], which deletes
     * the row rather than flagging it — so the two states this could have got wrong are
     * one filter and nothing respectively.
     */
    fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>>

    /**
     * Whether the user has ever finished a session, over all of history.
     *
     * The one method here that takes no period, because it answers the one question a
     * period cannot: an empty window says "nothing in this window", and that reads the same
     * for somebody who has never trained and somebody who stopped six months ago. The
     * comparison period tells the two apart only while the lapse is shorter than twice the
     * window. This does not run out.
     *
     * Same filter as [getWeeklyTraining], so it is exactly "the series would return
     * something over a wide enough range" — never true for a user the series are empty for
     * because they have no history, always true for one they are empty for because the
     * window is quiet.
     */
    fun hasTrainingHistory(): Flow<Boolean>

    /**
     * What each muscle group was trained over [days], ordered highest volume first.
     *
     * The one aggregate here that is not per week: the muscle map is a reading of the *period*
     * rather than a series across it, so bucketing it would produce a table nobody draws.
     *
     * Same filter and same bucketing as [getWeeklyVolume] — completed sets of finished
     * sessions, placed by the day the session was closed out — so a period's breakdown is built
     * from exactly the workouts its total volume was. Two figures on one screen disagreeing
     * about what "this month" holds is what sharing the filter avoids.
     *
     * A group the period did not touch has no entry, rather than a zeroed one. That is the same
     * rule the series follow, and unlike them there is nothing to fill in: the gaps in a series
     * are weeks the caller can enumerate from the period it asked for, and the gaps here are
     * every muscle group in the catalogue, which is not a list a chart wants.
     */
    fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>>

    /**
     * How long the user spent training over [days], in milliseconds.
     *
     * A session's length is its own row's business — `createdAt` to `completedAt` — so this is
     * summed over the sessions and touches no set; see [com.opengym.core.domain.model.SessionTotals],
     * which leaves duration out of the per-session aggregate for the same reason.
     *
     * A session that opened before [days] and finished inside it contributes its **whole**
     * length, because it is counted whole by [getWeeklyTraining] and [getWeeklyVolume] too. A
     * workout is one event; splitting one across two periods would put half of it in a month
     * the user did not train in.
     *
     * Zero for a period with no training in it, which is the honest figure rather than an
     * absent one.
     */
    fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long>

    /**
     * The day the user's first finished session was closed out on, or **null** when they have
     * none.
     *
     * What an "all time" period opens on. The alternatives are both wrong: a fixed date far
     * enough back is a window that widens every scan for the life of the install, and "the last
     * ten years" is a number somebody picked. Same filter as [hasTrainingHistory], so an
     * all-time period covers exactly the sessions the series can return and never opens on a
     * run of empty weeks before the user had started.
     */
    fun getFirstTrainingDay(): Flow<LocalDate?>

    /**
     * One entry per session [exerciseId] was performed in over [days], **oldest first**.
     *
     * The per-exercise drill-down's whole reading of its window: its volume bars, its
     * one-rep-max line and its recent-attempts table are three drawings of this one list, so
     * none of them can disagree about what a session held.
     *
     * The one method here that is not a period-wide roll-up *or* a weekly bucket: an attempt
     * is a session, and a lifter compares this Tuesday's bench against last Tuesday's rather
     * than against a week's total. Bucketing it by week would merge two sessions of one lift
     * into a figure nothing on the screen is about.
     *
     * Same filter and same bucketing as [getWeeklyVolume] with one difference, stated here
     * because it is load-bearing: **warm-ups are excluded**. This screen reads how hard the
     * lift was rather than how much work the week held, and it is the reading the PR engine
     * takes too — so an attempt's volume is comparable with the `MAX_VOLUME` record drawn
     * above it, and its top set can never be a warm-up.
     *
     * An exercise never performed in the window returns an empty list. That is a window with
     * nothing in it, not a missing exercise; only [countExerciseSets] can tell those apart.
     */
    fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>>

    /**
     * How many working sets of [exerciseId] the user has ever completed.
     *
     * All-time and periodless, like [hasTrainingHistory] and for a related reason: it is the
     * figure a lifter quotes about a movement rather than about a window, and narrowing it
     * with the timeframe selector would make a card headed `all-time` shrink when the user
     * asked to see less.
     *
     * **An aggregate, and implementations must keep it one.** Summing the exercise's history
     * to render a four-digit number is a full load of the heaviest table in the schema; see
     * `WorkoutSetDao.countSetsByExercise`.
     */
    fun countExerciseSets(exerciseId: Long): Flow<Int>

    /**
     * Every exercise the user has logged, most recently trained first.
     *
     * What the drill-down is chosen from when it is opened from the hub, which arrives with no
     * lift in hand. From the history rather than the catalogue: 873 seeded exercises is a
     * search, and the answer is nearly always among the handful trained this week.
     *
     * Same filter as [countExerciseSets], so an entry's set count is the figure its own screen
     * opens with and an exercise only ever warmed up with is not offered as one to drill into.
     */
    fun getTrainedExercises(): Flow<List<TrainedExercise>>
}
