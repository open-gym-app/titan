package com.opengym.core.domain.model

import kotlin.math.abs

/**
 * One row of the records list: a record, what it is called, and what it beat.
 *
 * ### Why the previous figure travels with the record
 *
 * "How much did this improve on the last one" is a question about *two* rows, and the second one
 * is the prior record of the same exercise and kind. Asking for it while scrolling would be a
 * query per visible row; carrying it here means the list is one read whatever its length. The
 * store computes it in the same query that produces the row — see
 * `PersonalRecordDao.getRecordsPage`.
 *
 * ### The figure, not the improvement
 *
 * [previousValue] is what was on file, not the difference. The difference depends on which way
 * "better" runs for the kind — see [PrType.higherIsBetter] — and that is domain knowledge, so it
 * is applied here in [improvement] rather than being baked into SQL that would have to state the
 * rule a second time.
 *
 * @property record the record itself.
 * @property exerciseName what it was set on, or **null** when the exercise has since been removed
 *   from the catalogue. The record still stands — it is a thing the user did — so the row is
 *   drawn with a fallback title rather than dropped, the same call `SummaryRecord` makes.
 * @property previousValue the figure this record beat, in the kind's own unit, or **null** when it
 *   is the first of its kind for that exercise. Null is not zero: a first record improved on
 *   nothing, and drawing it as "+92 kg" would credit the user with beating a record they never
 *   held.
 * @property isRecent whether this was set in the most recently finished session — what the `NEW`
 *   badge marks.
 */
data class PersonalRecordEntry(
    val record: PersonalRecord,
    val exerciseName: String?,
    val previousValue: Long?,
    val isRecent: Boolean,
) {

    /**
     * How much better this record is than the one it replaced, or **null** when there was none —
     * and **null** for a kind whose figure is not what it is ranked on.
     *
     * Always positive when present: every record on file beat what came before it, or it would
     * not have been written, so the difference between two consecutive figures of a kind that is
     * ranked *by* that figure can only run one way. `abs` states that rather than relying on it.
     *
     * [PrType.BEST_PACE] is excluded, and that is the whole reason the rule is asked of
     * [PrType.higherIsBetter] rather than assumed. A pace record is ranked on duration *over
     * distance* — see `PrDetectionUseCase.pace` — while [value] and the previous figure carried
     * beside it are bare durations. A 10 km run at a better pace than a previous 5 km takes more
     * time, not less, so subtracting the two durations would report an improvement of the amount
     * the run got *longer*. Making that honest needs the previous record's distance too, which
     * this row does not carry; until it does, a pace row draws no improvement rather than a
     * wrong one.
     */
    val improvement: Long?
        get() {
            if (!record.type.higherIsBetter) return null
            val previous = previousValue ?: return null
            val current = value ?: return null
            return abs(current - previous)
        }

    /**
     * The record's own figure, in the unit its kind is measured in, or **null** for a row whose
     * `pr_type` and populated column disagree.
     *
     * [PrType.MAX_VOLUME] reads `weight` because that is the column the engine writes a session's
     * volume into — see `PrDetectionUseCase`. It is the one kind whose figure is not named after
     * the column holding it, which is worth saying out loud here rather than leaving as a
     * surprise for the next reader of this `when`.
     */
    val value: Long?
        get() = when (record.type) {
            PrType.MAX_WEIGHT, PrType.MAX_VOLUME -> record.weight
            PrType.MAX_REPS -> record.reps?.toLong()
            PrType.ESTIMATED_1RM -> record.estimated1Rm
            PrType.MAX_DISTANCE -> record.distance
            PrType.BEST_PACE -> record.duration
        }
}

/**
 * A copy of this record carrying [value] in place of its own figure.
 *
 * For rendering a *derived* quantity in the record's own units — the improvement over the one
 * before it — through the same formatter the record itself goes through. The alternative is a
 * second `when` over [PrType] at the call site deciding which unit a kind is measured in, which
 * is the mapping this app already states in three places and does not need a fourth of.
 *
 * Null for a kind whose figure cannot be substituted, which is none of them today; the branch
 * exists so a kind added later cannot silently render its improvement under the wrong unit.
 */
fun PersonalRecord.withValue(value: Long): PersonalRecord? =
    when (type) {
        PrType.MAX_WEIGHT, PrType.MAX_VOLUME -> copy(weight = value)
        PrType.MAX_REPS -> copy(reps = value.toInt())
        PrType.ESTIMATED_1RM -> copy(estimated1Rm = value)
        PrType.MAX_DISTANCE -> copy(distance = value)
        PrType.BEST_PACE -> copy(duration = value)
    }

/**
 * The best record of each kind held in this list, keyed by kind.
 *
 * ### It comes off the ordering, not off a comparison
 *
 * The store hands records over **newest first**, and a record of a given kind is only ever
 * written when it beats the one before it — see `PrDetectionUseCase` — so the first row of a kind
 * *is* that kind's best. Re-deriving "better" here would be a second copy of the engine's
 * comparison rule with nothing keeping the two in step, and it would have to know that a lower
 * pace wins.
 *
 * The caller's ordering is therefore load-bearing: handed a list sorted the other way, this
 * returns each kind's *worst*. Every caller reads `getRecordsForExercise`, whose contract is
 * newest first.
 */
fun List<PersonalRecord>.bestByType(): Map<PrType, PersonalRecord> =
    groupBy(PersonalRecord::type).mapValues { (_, ofType) -> ofType.first() }
