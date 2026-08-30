package com.opengym.core.domain.model

/**
 * How many personal records were broken over a period, and across how many exercises.
 *
 * The stats hub's `PRs HIT` tile, which draws them as one figure and its caption — `7` over
 * `across 5 ex.`. One type rather than two reads because they are one question, and because a
 * count of records without the spread behind it flatters a good day on a single lift.
 *
 * @property records rows achieved in the period, counted per **record** and not per session: a
 *   workout that beat its weight, rep and volume bests for one lift broke three, matching the
 *   three [XpEvent.PrBroken] events it emitted. Records are only ever inserted, never replaced
 *   — see [com.opengym.core.domain.repository.PersonalRecordRepository.insertIfBetter] — so the
 *   table is the history and a slice of it is the period's figure.
 * @property exercises distinct exercises those records belong to. Zero exactly when [records]
 *   is, and lower than it whenever one movement carried the period.
 */
data class RecordTally(
    val records: Int,
    val exercises: Int,
)
