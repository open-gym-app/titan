package com.opengym.core.domain.template

import com.opengym.core.domain.model.PlannedExercise
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.superset.Supersets
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
 * work = sets × WORK_PER_SET
 * rest = sets × (the exercise's restDuration, or defaultRest when it has none)
 * ```
 *
 * summed over the template, **minus the last rest** — the one nobody stands around
 * for, because the workout is over. `sets` falls back to [DEFAULT_SETS] when the
 * template does not say.
 *
 * A superset changes only the rest: its members are performed back to back with the
 * rest taken after the group, so a member that is not the last of its group
 * contributes work and no rest at all ([Supersets.isRestDueAfter] is what decides
 * this, so grouping is read the same way here as when the session asks whether a rest
 * is due).
 *
 * ### What it is and is not
 *
 * A pacing figure for a list row, accurate to roughly the nearest five minutes, which
 * is why the wireframes render it with a `~`. It counts no walk to the rack, no
 * warm-up, no queue for the bench and no set that takes twice as long because the last
 * one was hard. [WORK_PER_SET] is one number for every movement — a set of curls and a
 * set of squats are not the same 45 seconds, but a per-exercise allowance would need a
 * per-exercise measurement nothing collects yet, and the error mostly cancels across a
 * whole session. Nothing schedules, bills or paces off this; the rest timer measures
 * rest itself, and [com.opengym.core.domain.stats.SessionStatsCalculator] reports what
 * a session actually took from its own clock.
 */
object TemplateDuration {

    /**
     * The time one working set is assumed to occupy, rest excluded.
     *
     * 45 seconds: enough for eight to twelve reps plus the load and unload around them.
     * With the 90-second default rest it puts a typical eight-exercise, three-set
     * template just under an hour, which is the figure the wireframes were drawn to.
     */
    val WORK_PER_SET: Duration = 45.seconds

    /** The set count assumed for an exercise whose template does not give one. */
    const val DEFAULT_SETS: Int = 3

    /**
     * The estimate for [exercises], which must be **in position order** — the order a
     * repository hands them back, and the order a draft list is already in.
     *
     * [defaultRest] stands in for any exercise with no rest of its own; pass the user's
     * configured default ([UserPreferences.defaultRestDuration]), which is what it
     * falls back to. The catalogue's per-exercise `default_rest` is deliberately not
     * consulted: the template row is the plan, and an estimate that quietly read a
     * different number from a second table would not match the rest the session
     * actually runs.
     *
     * An empty template estimates [Duration.ZERO], as does one whose exercises all plan
     * zero sets. A negative set count or rest — which nothing writes, but an import
     * can — contributes nothing rather than subtracting time.
     */
    fun estimate(
        exercises: List<PlannedExercise>,
        defaultRest: Duration = UserPreferences.DEFAULT_REST_DURATION,
    ): Duration {
        val grouping = exercises.map(PlannedExercise::supersetGroup)
        val fallbackRest = defaultRest.coerceAtLeast(Duration.ZERO)

        var total = Duration.ZERO
        // The rest that would be taken after the final set of the workout, subtracted
        // once at the end. Tracked as it goes rather than read off the last exercise:
        // the last exercise may plan no sets, in which case the workout ends on an
        // earlier one and it is *that* rest which is never taken.
        var trailingRest = Duration.ZERO

        exercises.forEachIndexed { index, exercise ->
            val sets = (exercise.defaultSets ?: DEFAULT_SETS).coerceAtLeast(0)
            if (sets == 0) return@forEachIndexed

            total += WORK_PER_SET * sets
            if (Supersets.isRestDueAfter(grouping, index)) {
                val rest = exercise.restDuration
                    ?.seconds
                    ?.coerceAtLeast(Duration.ZERO)
                    ?: fallbackRest
                total += rest * sets
                trailingRest = rest
            }
        }

        return total - trailingRest
    }
}
