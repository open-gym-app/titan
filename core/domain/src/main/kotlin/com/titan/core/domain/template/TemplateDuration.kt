package com.titan.core.domain.template

import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedExercise
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.superset.Supersets
import com.titan.core.domain.timer.RestDurations
import com.titan.core.domain.validation.SetDimension
import com.titan.core.domain.validation.SetValidator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a template is likely to take, computed from the exercises it holds.
 *
 * **Derived, never stored.** There is no `estimated_duration` column and there must not
 * be one: an estimate written at save time is wrong the moment an exercise is added,
 * a set count is changed or a rest is shortened, and keeping it true would mean a
 * second write on every edit whose only job is to restate what the rows already say.
 * Computing it costs one pass over a list a screen has already loaded, so a template
 * row that renders "8 ex · ~55m" and a template being edited get the same number from
 * the same function.
 *
 * ### The formula
 *
 * For each exercise, in position order:
 *
 * ```
 * work = Σ over its sets (the set's planned duration where its type shows one,
 *        else its reps × SECONDS_PER_REP where its type shows those,
 *        else WORK_PER_SET)
 * rest = sets × RestDurations.of(the catalogue exercise, defaultRest)
 * ```
 *
 * summed over the template, **minus the last rest** — the one nobody stands around
 * for, because the workout is over. `sets` is how many the exercise plans, which is
 * the length of its [PlannedExercise.sets]; an exercise that plans none is not
 * performed and costs nothing.
 *
 * A set's *type* is not read: a warm-up and a drop set are both a set that takes about
 * as long as its reps say, and pricing them apart would need a per-set-type
 * measurement nothing collects. What the list buys the estimate is that it no longer
 * has to guess a count for an exercise whose plan says nothing — a plan that says
 * nothing now plans nothing.
 *
 * A set's *reps* are read, at [SECONDS_PER_REP] each, because they are what makes a set of
 * three heavy doubles shorter than a set of twenty: a flat allowance priced both at the same
 * half-minute. The tempo is the one that turns the eight-to-twelve reps [WORK_PER_SET] was
 * sized for into that same half-minute, so a set of ten costs exactly what it did before
 * reps were read. A set with no reps, or a count outside `SetValidator`'s bounds, keeps the
 * allowance. Like the duration below, reps are read only where the exercise's type draws
 * them — [ExerciseType.STRENGTH], or an exercise the caller has no type for.
 *
 * A set's planned *duration* is read, because it is the one figure that says how long
 * the set takes rather than what it lifts. A 30-minute ride priced at [WORK_PER_SET]
 * would put a cardio template at under two minutes, and `3 × 1:30` of a plank is four
 * and a half minutes of holding, not a rep tempo. A duration outside `SetValidator`'s
 * bounds — which only an import can write — falls back to the allowance rather than pricing
 * the set at nothing, or at a figure that saturates the sum to [Duration.INFINITE].
 *
 * It is read only where the exercise's type draws a time field — [ExerciseType.ENDURANCE]
 * and [ExerciseType.ISOMETRIC]. A set keeps every figure it was planned with whatever the
 * exercise, so a slot can be swapped without being rebuilt; a 30-minute run swapped for
 * bench press therefore still carries its 30 minutes, in a field the strength row does
 * not draw. Pricing that would grow the estimate by a figure nothing on screen shows or
 * lets the user clear, so a [ExerciseType.STRENGTH] set is priced at [WORK_PER_SET]
 * whatever it carries. An exercise the caller has no type for — one gone from the
 * catalogue — has its duration read, since nothing says it is hidden.
 *
 * The rest is **the rest the workout's timer will run**, decided by [RestDurations] — the
 * exercise's own `defaultRest`, or the user's setting — and nothing the template says. A
 * template carries no rest of its own: the timer never read one, and an estimate that priced a
 * figure the session ignores promised a workout far shorter than the one it ran.
 *
 * A superset changes only the rest: its members are performed back to back with the
 * rest taken after the group, so a member that is not the last of its group
 * contributes work and no rest at all ([Supersets.isRestDueAfter] is what decides
 * this, so grouping is read the same way here as when the session asks whether a rest
 * is due). A group whose members plan different set counts runs for as many rounds as
 * its longest member — the shorter ones simply drop out of the later rounds — so the
 * group's rest is priced off that count, and the rest itself off the member the group
 * ends on.
 *
 * ### What it is and is not
 *
 * A pacing figure for a list row, accurate to roughly the nearest five minutes, which
 * is why the wireframes render it with a `~`. It counts no walk to the rack, no warm-up,
 * no queue for the bench and no set that takes twice as long because the last one was
 * hard. [WORK_PER_SET] and [SECONDS_PER_REP] are one number for every movement — a rep of
 * curls and a rep of squats are not the same three seconds, but a per-exercise tempo would
 * need a per-exercise measurement nothing collects yet, and the error mostly cancels across
 * a whole session. The rest timer measures rest itself, and
 * [com.titan.core.domain.stats.SessionStatsCalculator] reports what a session actually took
 * from its own clock.
 *
 * It is also the time a *finished* session kept its user working, by the same rules over the
 * sets that were performed: [com.titan.core.domain.stats.CalorieEstimate] prices it, through
 * [breakdown]. The clock from start to finish would be the wrong figure there, because it
 * also counts every minute spent on the phone between sets.
 */
object TemplateDuration {

    /**
     * The time one working set is assumed to occupy, rest excluded.
     *
     * 30 seconds: eight to twelve reps at a controlled tempo. Setting up the bar is
     * already inside the rest that precedes the set, so it is not counted twice here.
     * With the three-minute default rest it puts a typical six-exercise, three-set
     * template a little over an hour.
     */
    val WORK_PER_SET: Duration = 30.seconds

    /**
     * The time one rep is assumed to take, for a set whose reps are known.
     *
     * Three seconds: a controlled lift and lower. Chosen so that ten reps — the middle of the
     * range [WORK_PER_SET] was sized for — cost exactly that allowance.
     */
    val SECONDS_PER_REP: Duration = 3.seconds

    /**
     * One exercise's share of the estimate: the [work] of its sets, and the [rest] the workout
     * spends after them.
     *
     * [rest] is already net of the workout's last rest — the one nobody stands around for — so
     * the slices of a template sum to [estimate], and a reader that prices work and rest apart
     * prices exactly the time the estimate counts. A superset member that is not the last of its
     * group rests [Duration.ZERO]; the group's rest is on the member it ends on.
     *
     * @property type the catalogue's type for [exerciseId], or null for an exercise the caller
     *   had no entry for.
     */
    data class ExerciseTime(
        val exerciseId: Long,
        val type: ExerciseType?,
        val work: Duration,
        val rest: Duration,
    )

    /**
     * The estimate for [exercises], which must be **in position order** — the order a
     * repository hands them back, and the order a draft list is already in.
     *
     * [defaultRest] stands in for any exercise with no rest of its own, and is required
     * rather than defaulted: it is the user's configured default
     * ([UserPreferences.defaultRestDuration]), and a parameter that filled itself in
     * would let a screen price every rest at the shipped constant for a user who has
     * changed theirs, which reads as correct everywhere except on their phone.
     *
     * [catalogue] is the catalogue's entry for each exercise id — at least those
     * [exercises] name. Its type decides whether a set's planned duration is read, and its
     * `defaultRest` overrides [defaultRest]; see the class note. It is required for the same
     * reason [defaultRest] is: a caller that left it out would price the hidden duration of
     * every swapped slot and miss every exercise's own rest.
     *
     * An empty template estimates [Duration.ZERO], as does one whose exercises all plan
     * no sets. A non-positive [defaultRest] contributes nothing rather than subtracting time, and
     * one past [UserPreferences.MAX_REST_DURATION] is priced at that ceiling, so the trailing rest
     * subtracted at the end is always finite.
     */
    fun estimate(
        exercises: List<PlannedExercise>,
        defaultRest: Duration,
        catalogue: Map<Long, Exercise>,
    ): Duration =
        breakdown(exercises, defaultRest, catalogue)
            .fold(Duration.ZERO) { total, exercise -> total + exercise.work + exercise.rest }

    /**
     * [estimate], exercise by exercise: one [ExerciseTime] per exercise that plans at least one
     * set, in position order. The arguments are [estimate]'s, with its rules.
     *
     * For a reader that needs to know *what* the time was spent on rather than only how much of
     * it there was — [com.titan.core.domain.stats.CalorieEstimate] prices a minute of running and
     * a minute between sets of curls differently.
     */
    fun breakdown(
        exercises: List<PlannedExercise>,
        defaultRest: Duration,
        catalogue: Map<Long, Exercise>,
    ): List<ExerciseTime> {
        // An exercise planning no sets is not performed, so it is dropped before the
        // grouping is read rather than skipped inside the loop: a superset whose last
        // member plans nothing still takes its rest after the member that *is*
        // performed, and reading the raw grouping would hand that rest to a member
        // that never runs and so lose it entirely.
        val performed = exercises.filter { it.sets.isNotEmpty() }
        val grouping = performed.map(PlannedExercise::supersetGroup)
        val fallbackRest = defaultRest.coerceIn(Duration.ZERO, UserPreferences.MAX_REST_DURATION)

        // The rest that would be taken after the final set of the workout, and whose slice
        // carries it — taken back off once at the end. Tracked as it goes rather than read off
        // the last exercise, whose rest may be one nobody reaches.
        var trailingRest = Duration.ZERO
        var trailingIndex = -1

        val slices = performed.mapIndexedTo(mutableListOf()) { index, exercise ->
            // No catalogue entry at all (the exercise was deleted from the library) still
            // reads the planned time and reps: see "a planned time is priced for an exercise
            // the catalogue no longer has" — only a *known* type excludes either.
            val entry = catalogue[exercise.exerciseId]
            val type = entry?.type
            val dimension = type?.let(SetValidator::requiredDimension)
            val work = exercise.sets.fold(Duration.ZERO) { work, set -> work + workOf(set, dimension) }
            val rest = if (Supersets.isRestDueAfter(grouping, index)) {
                val each = RestDurations.of(entry, fallbackRest)
                trailingRest = each
                trailingIndex = index
                each * rounds(performed, grouping, index)
            } else {
                Duration.ZERO
            }
            ExerciseTime(exerciseId = exercise.exerciseId, type = type, work = work, rest = rest)
        }

        if (trailingIndex >= 0) {
            val last = slices[trailingIndex]
            slices[trailingIndex] = last.copy(rest = last.rest - trailingRest)
        }
        return slices
    }

    /**
     * How long one planned set occupies, rest excluded — its duration where [dimension] draws
     * one, its reps at [SECONDS_PER_REP] where it draws those, each only when `SetValidator`
     * would accept the figure, and the allowance otherwise. A null [dimension] is an exercise
     * of unknown type, which reads both. See the class note.
     */
    private fun workOf(
        set: PlannedSet,
        dimension: SetDimension?,
    ): Duration {
        val timed = set.duration
            ?.takeIf { dimension != SetDimension.REPS }
            ?.takeIf { it in SetValidator.DURATION_MIN_SECONDS..SetValidator.DURATION_MAX_SECONDS }
        if (timed != null) return timed.seconds

        val reps = set.reps
            ?.takeIf { dimension != SetDimension.DURATION }
            ?.takeIf { it in SetValidator.REPS_MIN..SetValidator.REPS_MAX }
        return reps?.let { SECONDS_PER_REP * it } ?: WORK_PER_SET
    }

    /**
     * How many times a rest is taken after the exercise at [last], which is a standalone
     * exercise or the last member of a superset — its own set count in the first case,
     * and the longest of its group's in the second, since the group runs until its
     * longest member is done.
     *
     * Walks back over the group rather than looking it up, which is sound for the same
     * reason [Supersets.isRestDueAfter] may read only the next position: members are
     * contiguous.
     */
    private fun rounds(
        performed: List<PlannedExercise>,
        grouping: List<Int?>,
        last: Int,
    ): Int {
        val group = grouping[last] ?: return performed[last].sets.size

        var rounds = 0
        var index = last
        while (index >= 0 && grouping[index] == group) {
            rounds = maxOf(rounds, performed[index].sets.size)
            index--
        }
        return rounds
    }
}
