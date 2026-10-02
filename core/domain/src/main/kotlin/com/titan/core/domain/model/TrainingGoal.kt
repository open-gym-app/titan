package com.titan.core.domain.model

/**
 * What the user says they are training for, chosen during onboarding (#M4-22).
 *
 * It was stored and shown back in settings for two milestones before anything read it — captured
 * early because asking again later is a worse experience than storing an answer that is not yet
 * used. The reader is the next-load engine (#M7-15): the goal picks the rep range a suggestion
 * targets — heavy and few for [STRENGTH], more and lighter for [ENDURANCE] — and [FitnessLevel]
 * picks how fast the load moves through it. Nothing else consumes it: no template is filtered
 * by it and no rest is set from it.
 */
enum class TrainingGoal {

    STRENGTH,

    HYPERTROPHY,

    ENDURANCE,

    /** The user's own programme — the escape hatch for anyone the three above do not describe. */
    CUSTOM,
}
