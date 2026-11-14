package com.titan.core.domain.importer

/**
 * How far an import from another app has got, and how it ended.
 *
 * Shaped like the backup restore's `ImportProgress`, and for its reason: the stream carries its own
 * failure as [Failed] rather than throwing, because everything an import can refuse is a sentence
 * a screen has to show. A collector that reaches the end has seen exactly one of [Complete] and
 * [Failed].
 *
 * The phases are ordered and do not repeat: [Reading], then [Writing] once per workout, then a
 * terminal. Nothing is written before [Writing], and nothing written is kept unless [Complete]
 * follows — see [com.titan.core.domain.usecase.ImportHistoryUseCase].
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

    /** The file could not be read at all — a revoked permission, a removed card. */
    data class Unreadable(val reason: String) : HistoryImportError

    /** The file was read and holds no workouts, templates or measurements. */
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
 * @property progressionFailures workouts whose replay could not finish one of its steps. The
 *   workout itself is imported regardless — see
 *   [com.titan.core.domain.usecase.SessionCompletionPipeline] for why a refused award never costs
 *   the workout.
 */
data class HistoryImportReport(
    val workoutsImported: Int,
    val setsImported: Int,
    val exercisesMatched: Int,
    val exercisesCreated: Int,
    val templatesImported: Int,
    val measurementsImported: Int,
    val skipped: ImportSkips,
    val recordsSet: Int,
    val xpEarned: Long,
    val achievementsUnlocked: Int,
    val questsCompleted: Int,
    val collectiblesFound: Int,
    val progressionFailures: Int,
)
