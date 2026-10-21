package com.titan.core.domain.model

/**
 * What a saved template trains, rendered as the chip on a template row.
 *
 * One category per template, stored in the `workout_template.category` column as this
 * constant's name — a scalar, not a tag set. The alternative considered was a
 * `template_tag` join mirroring `exercise_tag`: it would allow several categories at
 * once and reuse machinery that already exists, at the cost of a join on the one read
 * that lists every template, seeded reference rows whose ids an import has to remap,
 * and no way to say "exactly one" anywhere in the schema. A chip that shows one value
 * does not need any of that.
 *
 * **Nullable, and null is not [GENERAL].** A template saved from a finished session
 * before the user was asked has no category; [GENERAL] is an answer the user gave.
 * A screen renders the first as no chip at all, not as a chip reading "General".
 *
 * **One axis.** The wireframe's chips read "Strength", "Hypertrophy", "Beginner" and
 * "Endurance", but *Beginner* is a difficulty and the other three are goals. Folding
 * both into one column would make a beginner strength template pick a side; difficulty
 * belongs in its own field the day something needs it, as it already does on
 * `exercise` ([DifficultyLevel]).
 *
 * Deliberately **not** [TrainingGoal], which the three shared names invite. That enum
 * is a persisted DataStore key holding what the *user* says they train for
 * (see PERSISTENCE.md), and its constant names are a storage format that cannot be
 * changed without silently resetting the setting. The two vocabularies also diverge
 * from the start: a template can be mobility work, and `CUSTOM` — "the user's own
 * programme" — means nothing as a label on one template.
 */
enum class TemplateCategory {

    STRENGTH,

    HYPERTROPHY,

    ENDURANCE,

    /** Stretching, prehab and warm-up work — a session with no load to speak of. */
    MOBILITY,

    /** Trains a bit of everything, and is the answer for a template that resists the rest. */
    GENERAL,
}
