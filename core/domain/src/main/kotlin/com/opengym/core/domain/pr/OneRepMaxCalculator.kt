package com.opengym.core.domain.pr

import com.opengym.core.domain.model.OneRepMaxFormula

/**
 * Estimates a one-rep max from a heavier-than-one-rep set, per [OneRepMaxFormula].
 *
 * Pure arithmetic over the two figures a set already carries — no repository, no session —
 * so [com.opengym.core.domain.usecase.PrDetectionUseCase] can call it once per candidate set
 * without paying for anything beyond the multiplication.
 *
 * Returns [Float] rather than the milligram [Long] every other stored figure uses: this is
 * an *estimate*, and rounding it to the nearest milligram here would bake a rounding choice
 * into a pure function a caller might reasonably want at full precision (for a UI that shows
 * "≈102.4 kg" before a PR is actually written, say). Rounding to the stored unit is
 * [PrDetectionUseCase]'s job, once, at the point the value is about to be persisted.
 */
object OneRepMaxCalculator {

    /**
     * Brzycki's denominator, `37 − reps`, reaches zero at 37 reps and goes negative past it —
     * undefined and then nonsensical. [BRZYCKI_FALLBACK_REPS] is where [calculate] stops
     * trusting it and uses [OneRepMaxFormula.EPLEY] instead, per the acceptance criteria this
     * pins: reps ≥ 37 always falls back, never crashes and never returns a negative estimate.
     */
    private const val BRZYCKI_FALLBACK_REPS = 37

    private const val EPLEY_REP_DIVISOR = 30f
    private const val BRZYCKI_NUMERATOR = 36f
    private const val BRZYCKI_DENOMINATOR_BASE = 37f

    /**
     * The estimated one-rep max for a set of [reps] at [weight], in whatever unit [weight]
     * is already in (milligrams, for every real caller — see
     * [com.opengym.core.domain.model.PersonalRecord.estimated1Rm]).
     *
     * @throws IllegalArgumentException if [weight] is not positive or [reps] is not at least 1.
     *   A set with no weight or no reps logged is not a candidate for this at all — that
     *   decision belongs to the caller, before it ever reaches here.
     */
    fun calculate(
        weight: Long,
        reps: Int,
        formula: OneRepMaxFormula,
    ): Float {
        require(weight > 0) { "weight must be positive, was $weight" }
        require(reps >= 1) { "reps must be at least 1, was $reps" }

        return when (formula) {
            OneRepMaxFormula.EPLEY -> epley(weight, reps)

            OneRepMaxFormula.BRZYCKI ->
                if (reps >= BRZYCKI_FALLBACK_REPS) epley(weight, reps) else brzycki(weight, reps)
        }
    }

    private fun epley(
        weight: Long,
        reps: Int,
    ): Float = weight * (1f + reps / EPLEY_REP_DIVISOR)

    private fun brzycki(
        weight: Long,
        reps: Int,
    ): Float = weight * (BRZYCKI_NUMERATOR / (BRZYCKI_DENOMINATOR_BASE - reps))
}
