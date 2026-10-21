package com.titan.core.domain.model

import java.time.LocalDate

/**
 * How much the user showed up in the week opening on [weekStart].
 *
 * Two counts, because they answer different questions and the smaller one is the honest
 * answer to "how consistent am I". Somebody who lifts in the morning and runs in the
 * evening logs two sessions and trained on one day; counting the sessions would let a
 * change in how a lifter *splits* their day read as a change in consistency.
 *
 * @property weekStart the Monday (UTC) the bucket opens on.
 * @property trainingDays distinct days in the week on which a session was finished — the
 *   figure the consistency average is built on, one to seven.
 * @property sessionCount finished sessions in the week, which is at least [trainingDays]
 *   and equals it for anybody who trains once a day. Carried for the surfaces that want to
 *   say "9 sessions", not because consistency is measured with it.
 */
data class WeeklyTraining(
    val weekStart: LocalDate,
    val trainingDays: Int,
    val sessionCount: Int,
)
