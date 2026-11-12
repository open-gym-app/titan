package com.titan.core.domain.model

import com.titan.core.domain.validation.SetValidator

/**
 * How many nanoseconds make a second: the scale a pace is held at.
 *
 * A pace is a ratio of two stored whole numbers — seconds over centimetres — and is never itself
 * stored, but it has to be *compared* (the PR engine), carried as one figure (the records list's
 * improvement, which SQLite computes) and shown. Held as whole nanoseconds per centimetre, the
 * way a weight is held as whole milligrams: fine enough that no difference a user could log is
 * lost, and an integer, so nothing is approximated on the way. See [paceOf].
 */
const val NANOS_PER_SECOND: Long = 1_000_000_000L

/**
 * [durationSeconds] over [distanceCentimetres], in whole nanoseconds per centimetre rounded half
 * up — or **null** unless both are positive and inside `SetValidator`'s bounds. Lower is faster.
 *
 * ### Why nanoseconds per centimetre
 *
 * It is the smallest scale that is still safe in a `Long` at every value `SetValidator` admits:
 * 864,000 s × 10⁹ is under 9 × 10¹⁴. And it is fine enough that rounding never changes an answer
 * a user could notice: one nanosecond per centimetre is a tenth of a millisecond per kilometre,
 * and one second more over the longest distance the validator allows — 10,000 km — is still a
 * whole nanosecond per centimetre slower. A coarser unit (milliseconds per kilometre, say) would
 * tie two marathons a few seconds apart in a pool of rounding.
 *
 * Half up, written as integer division, so that SQLite's `(d * 1000000000 + c / 2) / c` in
 * `PersonalRecordDao` produces the same figure for the same row.
 *
 * A duration or a distance past those bounds reads as no pace at all, in both places. Only a
 * hand-edited backup holds one, and the product above would leave a `Long`: Kotlin would wrap
 * it and SQLite would fall back to floating point, so the two would no longer agree.
 */
fun paceOf(
    durationSeconds: Long?,
    distanceCentimetres: Long?,
): Long? {
    val seconds = durationSeconds?.takeIf { it in 1..SetValidator.DURATION_MAX_SECONDS } ?: return null
    val centimetres = distanceCentimetres?.takeIf { it in 1..SetValidator.DISTANCE_MAX_CM } ?: return null
    return (seconds * NANOS_PER_SECOND + centimetres / 2) / centimetres
}
