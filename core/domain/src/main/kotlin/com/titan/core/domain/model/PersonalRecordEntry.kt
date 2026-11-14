package com.titan.core.domain.model

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
 * @property previousValue the figure this record beat, as [rankedFigure] reads it — the kind's own
 *   unit, or nanoseconds per centimetre for [PrType.BEST_PACE] (see [paceOf]) — or **null** when it
 *   is the first of its kind for that exercise. Null is not zero: a first record improved on nothing, and drawing
 *   it as "+92 kg" would credit the user with beating a record they never held.
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
     * How much better this record is than the one it replaced, in the record's own unit, or
     * **null** when there was none — and **null** for a kind whose difference is not a figure of
     * that kind.
     *
     * Always positive when present: every record on file beat what came before it, or it would
     * not have been written, so the difference between two consecutive figures of a kind that is
     * ranked *by* that figure can only run one way. `abs` states that rather than relying on it.
     *
     * Which kinds have one is [withValue]'s to say, not this property's: a difference is only a
     * figure of the kind if the record can be re-drawn carrying it, and that `when` is exhaustive,
     * so a kind added to [PrType] is decided there once rather than here and at every screen too.
     * Two kinds have none today, each for its own reason:
     *
     * - [PrType.BEST_PACE] is ranked on a ratio, and the difference between two paces is still a
     *   pace — not a duration this record could be re-rendered with. It has [previousPace]
     *   instead, which a screen turns into "−0:17 /km" or "+1.2 km/h" as the exercise reads it.
     * - [PrType.MAX_WEIGHTED_HOLD] is ranked on load times seconds, which is never drawn: a hold
     *   is shown as its two parts, and the difference of two products has no parts to show.
     */
    val improvement: Long?
        get() {
            val previous = previousValue ?: return null
            val current = record.rankedFigure ?: return null
            // Exact, or no figure: two restored rows far enough apart would otherwise wrap into
            // a difference of the wrong sign, which `abs` cannot put right.
            val difference = runCatching { Math.subtractExact(current, previous) }.getOrNull() ?: return null
            return abs(difference).takeIf { it >= 0 && record.withValue(it) != null }
        }

    /**
     * The pace this [PrType.BEST_PACE] record beat, in nanoseconds per centimetre (see [paceOf]),
     * or **null** for a first record or any other kind.
     *
     * The store reads it off the previous record's own distance and duration, so a 10 km run is
     * compared with the 5 km it beat by how fast each went rather than by how long each took — a
     * faster 10 km takes more time, not less.
     */
    val previousPace: Long?
        get() = previousValue.takeIf { record.type == PrType.BEST_PACE }
}

/**
 * The figure this record is ranked on, or **null** for a row whose `pr_type` and populated column
 * disagree.
 *
 * The kind's own column for every kind but two. [PrType.MAX_VOLUME] and
 * [PrType.MAX_WEIGHTED_HOLD] read `weight`, because that is the column the engine writes a
 * session's volume and a hold's load-seconds into — see `PrDetectionUseCase`. [PrType.BEST_PACE]
 * is the one figure no column holds: nanoseconds per centimetre, from its distance and duration —
 * see [pace].
 *
 * One `when` for the engine deciding whether a candidate beats what is on file, and for the list
 * working out what a record beat. The store's improvement query makes the same choice in SQL —
 * see `PersonalRecordDao.getRecordsPage` — and the two agree by inspection, so a kind added here
 * has to be added there too.
 */
val PersonalRecord.rankedFigure: Long?
    get() = when (type) {
        PrType.MAX_WEIGHT, PrType.MAX_VOLUME, PrType.MAX_WEIGHTED_HOLD -> weight
        PrType.MAX_REPS -> reps?.toLong()
        PrType.ESTIMATED_1RM -> estimated1Rm
        PrType.MAX_DISTANCE -> distance
        PrType.MAX_DURATION -> duration
        PrType.BEST_PACE -> pace
    }

/**
 * A copy of this record carrying [value] in place of its own figure.
 *
 * For rendering a *derived* quantity in the record's own units — the improvement over the one
 * before it — through the same formatter the record itself goes through. The alternative is a
 * second `when` over [PrType] at the call site deciding which unit a kind is measured in, which
 * is the mapping this app already states in three places and does not need a fourth of.
 *
 * Null for a kind whose figure cannot be substituted: [PrType.BEST_PACE] and
 * [PrType.MAX_WEIGHTED_HOLD], whose figures are a ratio and a product of two columns rather than
 * one column's value. This is the one place that decision is made —
 * [PersonalRecordEntry.improvement] is null for exactly the kinds this is.
 */
fun PersonalRecord.withValue(value: Long): PersonalRecord? =
    when (type) {
        PrType.MAX_WEIGHT, PrType.MAX_VOLUME -> copy(weight = value)
        PrType.MAX_REPS -> copy(reps = value.toInt())
        PrType.ESTIMATED_1RM -> copy(estimated1Rm = value)
        PrType.MAX_DISTANCE -> copy(distance = value)
        PrType.MAX_DURATION -> copy(duration = value)
        PrType.BEST_PACE, PrType.MAX_WEIGHTED_HOLD -> null
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
