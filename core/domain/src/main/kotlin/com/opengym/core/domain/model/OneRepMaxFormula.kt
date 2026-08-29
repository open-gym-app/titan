package com.opengym.core.domain.model

/**
 * Which formula estimates a true one-rep max from a set that was not one.
 *
 * Two, not one, because neither is simply better: [EPLEY] is the simpler arithmetic and
 * overestimates as the rep count climbs, while [BRZYCKI] tracks a low-rep set more closely
 * but degenerates as the rep count approaches its own denominator — see
 * [com.opengym.core.domain.pr.OneRepMaxCalculator]. A user who trains for strength in
 * singles and triples wants a different answer from one who trains hypertrophy in sets of
 * twelve, which is why this is a setting rather than a fixed choice.
 */
enum class OneRepMaxFormula {

    /** `weight × (1 + reps / 30)`. */
    EPLEY,

    /** `weight × 36 / (37 − reps)`, falling back to [EPLEY] from 37 reps on. */
    BRZYCKI,
}
