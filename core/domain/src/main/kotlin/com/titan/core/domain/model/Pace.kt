package com.titan.core.domain.model

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
 * up — or **null** unless both are positive. Lower is faster.
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
 */
fun paceOf(
    durationSeconds: Long?,
    distanceCentimetres: Long?,
): Long? {
    val seconds = durationSeconds?.takeIf { it > 0L } ?: return null
    val centimetres = distanceCentimetres?.takeIf { it > 0L } ?: return null
    return (seconds * NANOS_PER_SECOND + centimetres / 2) / centimetres
}
