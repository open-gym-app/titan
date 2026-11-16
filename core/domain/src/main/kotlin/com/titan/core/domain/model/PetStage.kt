package com.titan.core.domain.model

/**
 * How grown a pet is — **ordered**, youngest first, and the one table that says where each stage
 * starts.
 *
 * A stage is a named level threshold and nothing more. It is what the drawing changes on, so the
 * thresholds are spread to be crossed a handful of times in a pet's life rather than every week.
 *
 * Not persisted: a pet row holds its XP, the level is read off the user's own curve, and the
 * stage off this table, so a threshold can be retuned without a migration.
 *
 * @property fromLevel the first level at which a pet is at this stage.
 */
enum class PetStage(val fromLevel: Int) {

    /** Fresh out of the egg. */
    HATCHLING(fromLevel = 1),

    /** Past its first weeks of training. */
    JUVENILE(fromLevel = 10),

    /** Fully grown. */
    ADULT(fromLevel = 25),

    /** The last stage, and the product's own word for the companion. */
    TITAN(fromLevel = 50),
}
