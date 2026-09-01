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
     * How much better this record is than the one it replaced, or **null** when there was none.
     *
     * Always positive when present, whichever direction the kind runs in: a pace record that took
     * four seconds off reads as an improvement of four seconds, not of minus four. The sign
     * carries no information the row does not already have — every record on file beat what came
     * before it, or it would not have been written — so spending a minus sign on it would only
     * invite reading a *worse* record into a list that cannot contain one.
     */
    val improvement: Long?
        get() {
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
