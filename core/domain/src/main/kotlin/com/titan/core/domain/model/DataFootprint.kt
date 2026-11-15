package com.titan.core.domain.model

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
 * because an exercise the user typed in is theirs. The default templates seeded with the
 * catalogue are counted in [templates]: from the moment they are written they are ordinary
 * templates, the user's to edit and delete, so a fresh install's footprint is those and
 * nothing else.
 *
 * ### Zero is a real answer
 *
 * A database with nothing of the user's in it has a footprint of all zeroes rather than
 * nothing to show, so the caller never has to decide what to render "before the counts
 * arrive": there is always a value, and it is the truth about an empty database.
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
    /**
     * Progress photos. Counted for "Delete all data", which removes them, and deliberately left
     * out of [isEmpty]: a restore — the other caller — does not touch them, so they are not
     * something a restore puts at stake. See [nothingToDelete].
     */
    val progressPhotos: Int = 0,

    /**
     * When the oldest session on the device was started, epoch milliseconds UTC, or **null**
     * when nothing has been logged.
     *
     * "Since March 2024" is what makes a backup screen's numbers mean something: 184 sessions
     * over two years and 184 over two months are the same count and not the same history. It
     * is the *created* stamp rather than the completed one, because a session that was started
     * and abandoned still marks when this device began being used.
     *
     * Null and a count of zero always agree — there is no session to take a date from — but
     * the two are separate fields because a caller rendering the date does not want to reach
     * for [sessions] to find out whether it may.
     */
    val firstSessionAt: Long? = null,

    /**
     * How much space the database file itself takes, in bytes.
     *
     * The one figure here that is not a count of something the user made, and it is included
     * for the question a backup screen has to answer before an export: *roughly how big is
     * this going to be?* It is the database rather than the export — a JSON document of the
     * same rows is larger, since it spells out a column name per value — so it is an order of
     * magnitude rather than a promise, which is all a screen should claim.
     *
     * Zero before the first read arrives, and on a device where the file cannot be measured.
     * It is deliberately **not** part of [isEmpty]: a database with nothing in it still has a
     * page of schema in it, so a size is never a reason to say there is something to lose.
     */
    val databaseBytes: Long = 0,
) {
    init {
        require(sessions >= 0 && sets >= 0 && templates >= 0) {
            "a footprint cannot be negative, was $this"
        }
        require(personalRecords >= 0 && customExercises >= 0 && bodyMeasurements >= 0) {
            "a footprint cannot be negative, was $this"
        }
        require(databaseBytes >= 0) { "a database cannot occupy negative space, was $databaseBytes" }
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

    /** Whether "Delete all data" would erase nothing of the user's: no history and no photos. */
    val nothingToDelete: Boolean get() = isEmpty && progressPhotos == 0
}
