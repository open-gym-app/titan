package com.titan.core.domain.timer

import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.UserPreferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long the rest after a set of an exercise lasts.
 *
 * One rule, read by the two places that need it: the workout's rest timer, which runs it, and
 * [com.titan.core.domain.template.TemplateDuration], which prices it. They used to answer
 * separately — the timer from the catalogue and the user's setting, the estimate from a
 * per-exercise figure on the template that nothing else read — and a template's row promised a
 * workout half an hour shorter than the one its own timer then ran.
 */
object RestDurations {

    /**
     * The exercise's own `defaultRest` where it is positive and at most
     * [UserPreferences.MAX_REST_DURATION] — the ceiling the user's own setting has — and
     * [defaultRest], the user's setting, otherwise, including for an exercise the catalogue no
     * longer has.
     *
     * A stored rest of zero or less is treated as no opinion rather than as an instant rest: the
     * column is the user's to fill in, and a zero in it is far likelier to be an empty answer
     * than a request for no rest at all. A rest longer than a day is treated the same way. No
     * screen writes one, so it came from a hand-edited backup, and honouring it would hand the
     * timer and the template estimate a duration that saturates to [Duration.INFINITE], whose
     * arithmetic throws.
     */
    fun of(
        exercise: Exercise?,
        defaultRest: Duration,
    ): Duration =
        exercise?.defaultRest
            ?.takeIf { it > 0 }
            ?.seconds
            ?.takeIf { it <= UserPreferences.MAX_REST_DURATION }
            ?: defaultRest
}
