package com.opengym.core.domain.model

/**
 * How much of the user's own work is on the device, counted per thing they would recognise.
 *
 * It exists for one screen and one sentence: the settings screen's delete-all confirmation
 * has to say *what is lost*, and "184 sessions and 2,140 sets" is a thing a user can weigh
 * where "all your data" is not. #M4-23 requires that dialog to be specific, and a count is
 * the only form of specific that survives the user having no idea what a template or a
 * personal record is called internally.
 *
 * ### Only what the user made
 *
 * The seeded exercise catalogue is not counted and is not lost. It is app content that
 * happens to live in the same database — a thousand rows nobody would miss and everybody
 * would notice the absence of — so counting it here would inflate every figure in the
 * dialog with something the wipe restores anyway. [customExercises] is the exception,
 * because an exercise the user typed in is theirs.
 *
 * ### Zero is a real answer
 *
 * A fresh install has a footprint of all zeroes rather than nothing to show, so the caller
 * never has to decide what to render "before the counts arrive": there is always a value,
 * and it is the truth about an empty database.
 */
data class DataFootprint(
    /** Logged workouts, finished or still in progress. */
    val sessions: Int = 0,

    /** Individual sets across every session — the largest number, and the most convincing. */
    val sets: Int = 0,

    /** Saved workout templates. */
    val templates: Int = 0,

    /** Recorded personal records. */
    val personalRecords: Int = 0,

    /** Exercises the user added themselves; the seeded catalogue is not counted. */
    val customExercises: Int = 0,

    /** Logged body measurement entries — a weigh-in, a set of circumferences, or both. */
    val bodyMeasurements: Int = 0,
) {
    init {
        require(sessions >= 0 && sets >= 0 && templates >= 0) {
            "a footprint cannot be negative, was $this"
        }
        require(personalRecords >= 0 && customExercises >= 0 && bodyMeasurements >= 0) {
            "a footprint cannot be negative, was $this"
        }
    }

    /**
     * Whether there is anything of the user's to lose.
     *
     * The delete-all row reads as available whatever this says — an empty database is not a
     * reason to hide the control that empties it — but the dialog's wording can soften when
     * there is nothing at stake.
     */
    val isEmpty: Boolean
        get() = sessions == 0 && sets == 0 && templates == 0 &&
            personalRecords == 0 && customExercises == 0 && bodyMeasurements == 0
}
