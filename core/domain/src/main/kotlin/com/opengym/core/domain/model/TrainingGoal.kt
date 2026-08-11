package com.opengym.core.domain.model

/**
 * What the user says they are training for, chosen during onboarding (#M4-22).
 *
 * **Nothing consumes this in v1.** It is stored and shown back in settings, and that is
 * the whole of its job for now — no rep range is suggested from it, no template is
 * filtered by it and no recommendation is derived from it. It is captured early because
 * asking again later is a worse experience than storing an answer that is not yet used;
 * the engines that will read it arrive with the progression work in M5.
 */
enum class TrainingGoal {

    STRENGTH,

    HYPERTROPHY,

    ENDURANCE,

    /** The user's own programme — the escape hatch for anyone the three above do not describe. */
    CUSTOM,
}
