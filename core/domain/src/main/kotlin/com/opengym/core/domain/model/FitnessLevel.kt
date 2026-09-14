package com.opengym.core.domain.model

/**
 * How experienced a lifter the user says they are, chosen during onboarding (#M7-11).
 *
 * Asked in the user's own terms — how long they have been training, not what they can lift —
 * because a year in the gym is something anybody can answer on a first-run screen and a
 * one-rep max is not. The three bands are the ones the progression literature draws: a
 * beginner adds load nearly every session, and somebody past that needs a top-of-range
 * performance repeated before the weight goes up. That is what the next-load engine (#M7-15)
 * reads this for: the level decides *how fast* a suggestion progresses, where
 * [TrainingGoal] decides *what rep range* it progresses in.
 *
 * ### Not [Exercise.difficultyLevel]
 *
 * The seed data rates every exercise with a [DifficultyLevel], and its three names nearly
 * match these. They are different facts about different things — that one is how hard a
 * movement is to learn, this one is how long the person has been learning — and a beginner
 * can do an expert-rated lift badly while an advanced lifter warms up on a beginner-rated
 * one. Sharing the enum would let one be handed where the other is meant, so they are kept
 * apart on purpose.
 *
 * The default is [BEGINNER]: the answer that produces the most conservative suggestions,
 * which is the right thing to hand somebody who skipped the question.
 */
enum class FitnessLevel {

    /** Under a year of training. Progresses every session that hits the top of the range. */
    BEGINNER,

    /** One to three years. Progresses after two consecutive top-of-range sessions. */
    INTERMEDIATE,

    /**
     * More than three years. Paced like [INTERMEDIATE] today; a constant of its own so a slower
     * rule can be given to it later without a stored-value migration.
     */
    ADVANCED,
}
