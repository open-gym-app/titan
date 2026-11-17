package com.titan.core.domain.importer

/**
 * How far an import from another app has got, and how it ended.
 *
 * Shaped like the backup restore's `ImportProgress`, and for its reason: the stream carries its own
 * failure as [Failed] rather than throwing, because everything an import can refuse is a sentence
 * a screen has to show. A collector that reaches the end has seen exactly one of [Complete] and
 * [Failed].
 *
 * The phases are ordered and do not repeat: [Reading], then [Writing] as the workouts land when
 * the file has any, then [StoringPhotos] once per photo when it has any, then a terminal. A
 * [Writing] the collector is too slow to take is dropped rather than waited for, because it is
 * sent from inside the import's transaction, so its counts can skip but never go back. Everything
 * the history holds — exercises, templates and measurements, written just before the first
 * [Writing], and the workouts during it — is kept only if it all commits; the photos are stored
 * after that commit, one at a time — see [com.titan.core.domain.usecase.ImportHistoryUseCase].
 */
sealed interface HistoryImportProgress {

    /** The file is being parsed and the plan worked out. Indeterminate. */
    data object Reading : HistoryImportProgress

    /**
     * Workouts are being written and replayed through the completion pipeline, one at a time.
     *
     * Counts workouts rather than rows because the replay, not the insert, is what takes the
     * time: a workout is a handful of inserts and a few dozen reads for its records, its quests
     * and its achievements.
     */
    data class Writing(
        val workoutsWritten: Int,
        val workoutsTotal: Int,
    ) : HistoryImportProgress

    /**
     * The history has committed and the photos are being stored, one at a time. Counts photos,
     * since each is an image to decode and re-encode and is the slow part of this phase.
     */
    data class StoringPhotos(
        val photosStored: Int,
        val photosTotal: Int,
    ) : HistoryImportProgress

    /** Everything in [report] is on the device. */
    data class Complete(val report: HistoryImportReport) : HistoryImportProgress

    /** Nothing was imported. See [HistoryImportError] for why. */
    data class Failed(val error: HistoryImportError) : HistoryImportProgress
}

/**
 * Why an import from another app wrote nothing. Every case leaves the device exactly as it was.
 */
sealed interface HistoryImportError {

    /** The file is not an export of the app the user said it came from. */
    data object NotRecognised : HistoryImportError

    /**
     * The file is that app's format and could not be read as it.
     *
     * @property reason English, for a log; the screen shows its own sentence.
     */
    data class Malformed(val reason: String) : HistoryImportError

    /** The file is larger than [limitBytes], and was not read past it. */
    data class TooLarge(val limitBytes: Long) : HistoryImportError

    /**
     * The file holds more than [limitSets] sets. The byte cap alone does not bound memory, which
     * follows the number of sets, so a file of very short rows is refused here instead of
     * exhausting the heap — see [MAX_IMPORT_SETS].
     */
    data class TooManySets(val limitSets: Int) : HistoryImportError

    /** The file could not be read at all — a revoked permission, a removed card. */
    data class Unreadable(val reason: String) : HistoryImportError

    /** The file was read and holds no exercises, workouts, templates, measurements or photos. */
    data object NothingToImport : HistoryImportError

    /**
     * The device refused the writes — out of space, or a constraint the plan did not foresee —
     * and every one of them was rolled back.
     */
    data class StorageFailure(val reason: String) : HistoryImportError
}

/**
 * What an import wrote, what it left out, and what the replayed history earned.
 *
 * @property exercisesMatched source exercises filed under one already on the device.
 * @property exercisesCreated custom exercises the import added for the rest.
 * @property recordsSet personal records the imported workouts set, baselines included — the
 *   first figure of each kind an exercise has is a record, as it is for a logged one.
 * @property xpEarned XP banked by the replay: the workouts, their records, and the achievements
 *   and quests they completed.
 * @property photosImported progress photos stored.
 * @property photosFailed photos whose bytes could not be read or were not an image. The history
 *   is imported regardless: photos are stored after it commits.
 */
data class HistoryImportReport(
    val workoutsImported: Int,
    val setsImported: Int,
    val exercisesMatched: Int,
    val exercisesCreated: Int,
    val templatesImported: Int,
    val measurementsImported: Int,
    val photosImported: Int = 0,
    val photosFailed: Int = 0,
    val skipped: ImportSkips,
    val recordsSet: Int,
    val xpEarned: Long,
    val achievementsUnlocked: Int,
    val questsCompleted: Int,
    val collectiblesFound: Int,
)
