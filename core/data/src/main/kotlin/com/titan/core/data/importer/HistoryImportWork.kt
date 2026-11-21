package com.titan.core.data.importer

import androidx.work.Data
import androidx.work.WorkInfo
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportRun
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips

/**
 * How an import from another app is written into WorkManager's records, and read back out.
 *
 * The worker and the screen never share memory — the screen may be in a process started long
 * after the worker's ended — so everything the screen draws travels through what WorkManager
 * stores: the request in the work's **tags**, the phase in its **progress**, and the report or
 * the refusal in its **output**. This object is both ends of that, so the two cannot disagree
 * about a key.
 *
 * ### Why the request is in the tags
 *
 * A `WorkInfo` carries a work's tags, progress and output, and not its input. The request is
 * what "run it again" needs after an interrupted import and what every phase's sentence names
 * ("Importing from Strong"), so it rides on the one field present in every state, from enqueued
 * to cancelled. It is in the input too, which is what the worker reads.
 *
 * ### These keys are persisted
 *
 * WorkManager keeps finished work for a day and unfinished work indefinitely, across app
 * updates. A renamed key strands one stored import at worst — it reads as nothing to show — but
 * the strings are literals for the reason notification channel ids are.
 */
internal object HistoryImportWork {

    /**
     * The unique name every import is enqueued under, so there is only ever one. A persisted
     * identifier, like `WorkManagerAutoBackupScheduler.WORK_NAME`.
     */
    const val WORK_NAME: String = "history-import"

    private const val SOURCE_TAG = "history-import-source:"
    private const val DOCUMENT_TAG = "history-import-document:"

    private const val KEY_SOURCE = "source"
    private const val KEY_DOCUMENT = "document"

    private const val KEY_PHASE = "phase"
    private const val KEY_DONE = "done"
    private const val KEY_TOTAL = "total"
    private const val PHASE_READING = "reading"
    private const val PHASE_WRITING = "writing"
    private const val PHASE_PHOTOS = "photos"

    private const val KEY_INTERRUPTED = "interrupted"
    private const val KEY_ERROR = "error"
    private const val KEY_LIMIT_BYTES = "limit_bytes"
    private const val KEY_LIMIT_SETS = "limit_sets"
    private const val KEY_REASON = "reason"
    private const val ERROR_NOT_RECOGNISED = "not_recognised"
    private const val ERROR_MALFORMED = "malformed"
    private const val ERROR_TOO_LARGE = "too_large"
    private const val ERROR_TOO_MANY_SETS = "too_many_sets"
    private const val ERROR_UNREADABLE = "unreadable"
    private const val ERROR_NOTHING = "nothing_to_import"
    private const val ERROR_STORAGE = "storage_failure"

    /**
     * Longest a refusal's English reason is kept in the output. It is for a log, never shown,
     * and WorkManager's `Data` holds 10 KB in all.
     */
    private const val MAX_REASON_LENGTH = 500

    /** The tags that carry [request] on its work. */
    fun tagsOf(request: HistoryImportRequest): Set<String> =
        setOf(SOURCE_TAG + request.source.name, DOCUMENT_TAG + request.document)

    /** The request [tags] carry, or null when they are not an import's. */
    fun requestOf(tags: Set<String>): HistoryImportRequest? {
        val source = tags.firstNotNullOfOrNull { it.removePrefixOrNull(SOURCE_TAG) }?.let(::sourceNamed)
        val document = tags.firstNotNullOfOrNull { it.removePrefixOrNull(DOCUMENT_TAG) }
        return if (source != null && document != null) HistoryImportRequest(source, document) else null
    }

    /** The worker's input for [request]. */
    fun inputOf(request: HistoryImportRequest): Data =
        Data.Builder()
            .putString(KEY_SOURCE, request.source.name)
            .putString(KEY_DOCUMENT, request.document)
            .build()

    /** The request [input] holds, or null if it holds none. */
    fun requestOf(input: Data): HistoryImportRequest? {
        val source = input.getString(KEY_SOURCE)?.let(::sourceNamed)
        val document = input.getString(KEY_DOCUMENT)
        return if (source != null && document != null) HistoryImportRequest(source, document) else null
    }

    /** [progress] as the worker publishes it. Only the three running phases are published. */
    fun progressData(progress: HistoryImportProgress): Data =
        when (progress) {
            HistoryImportProgress.Reading -> Data.Builder().putString(KEY_PHASE, PHASE_READING).build()
            is HistoryImportProgress.Writing -> counted(PHASE_WRITING, progress.workoutsWritten, progress.workoutsTotal)
            is HistoryImportProgress.StoringPhotos -> counted(PHASE_PHOTOS, progress.photosStored, progress.photosTotal)
            is HistoryImportProgress.Complete, is HistoryImportProgress.Failed -> Data.EMPTY
        }

    /** The phase [data] says the import is in, or null before the worker has said anything. */
    fun progressOf(data: Data): HistoryImportProgress? =
        when (data.getString(KEY_PHASE)) {
            PHASE_READING -> HistoryImportProgress.Reading
            PHASE_WRITING -> HistoryImportProgress.Writing(data.getInt(KEY_DONE, 0), data.getInt(KEY_TOTAL, 0))
            PHASE_PHOTOS -> HistoryImportProgress.StoringPhotos(data.getInt(KEY_DONE, 0), data.getInt(KEY_TOTAL, 0))
            else -> null
        }

    /** The output of an import the system stopped before it finished. */
    fun interruptedOutput(): Data = Data.Builder().putBoolean(KEY_INTERRUPTED, true).build()

    /** The output of an import that ended in [error]. */
    fun failureOutput(error: HistoryImportError): Data {
        val builder = Data.Builder()
        when (error) {
            HistoryImportError.NotRecognised -> builder.putString(KEY_ERROR, ERROR_NOT_RECOGNISED)

            is HistoryImportError.Malformed -> builder.putString(KEY_ERROR, ERROR_MALFORMED).putReason(error.reason)

            is HistoryImportError.TooLarge -> builder.putString(
                KEY_ERROR,
                ERROR_TOO_LARGE,
            ).putLong(KEY_LIMIT_BYTES, error.limitBytes)

            is HistoryImportError.TooManySets -> builder.putString(
                KEY_ERROR,
                ERROR_TOO_MANY_SETS,
            ).putInt(KEY_LIMIT_SETS, error.limitSets)

            is HistoryImportError.Unreadable -> builder.putString(KEY_ERROR, ERROR_UNREADABLE).putReason(error.reason)

            HistoryImportError.NothingToImport -> builder.putString(KEY_ERROR, ERROR_NOTHING)

            is HistoryImportError.StorageFailure -> builder.putString(KEY_ERROR, ERROR_STORAGE).putReason(error.reason)
        }
        return builder.build()
    }

    /**
     * The refusal [output] records. An output that names none — written by a build that knew a
     * refusal this one does not — reads as a storage failure: still "nothing was imported", which
     * is the one thing every refusal means.
     */
    fun errorOf(output: Data): HistoryImportError {
        val reason = output.getString(KEY_REASON).orEmpty()
        return when (output.getString(KEY_ERROR)) {
            ERROR_NOT_RECOGNISED -> HistoryImportError.NotRecognised
            ERROR_MALFORMED -> HistoryImportError.Malformed(reason)
            ERROR_TOO_LARGE -> HistoryImportError.TooLarge(output.getLong(KEY_LIMIT_BYTES, 0))
            ERROR_TOO_MANY_SETS -> HistoryImportError.TooManySets(output.getInt(KEY_LIMIT_SETS, 0))
            ERROR_UNREADABLE -> HistoryImportError.Unreadable(reason)
            ERROR_NOTHING -> HistoryImportError.NothingToImport
            else -> HistoryImportError.StorageFailure(reason)
        }
    }

    /** The output of an import that finished with [report]. */
    fun reportOutput(report: HistoryImportReport): Data =
        Data.Builder()
            .putInt(Report.WORKOUTS, report.workoutsImported)
            .putInt(Report.SETS, report.setsImported)
            .putInt(Report.EXERCISES_MATCHED, report.exercisesMatched)
            .putInt(Report.EXERCISES_CREATED, report.exercisesCreated)
            .putInt(Report.TEMPLATES, report.templatesImported)
            .putInt(Report.MEASUREMENTS, report.measurementsImported)
            .putInt(Report.PHOTOS, report.photosImported)
            .putInt(Report.PHOTOS_FAILED, report.photosFailed)
            .putInt(Report.RECORDS, report.recordsSet)
            .putLong(Report.XP, report.xpEarned)
            .putInt(Report.ACHIEVEMENTS, report.achievementsUnlocked)
            .putInt(Report.QUESTS, report.questsCompleted)
            .putInt(Report.COLLECTIBLES, report.collectiblesFound)
            .putInt(Skipped.DUPLICATE_WORKOUTS, report.skipped.duplicateWorkouts)
            .putInt(Skipped.INVALID_WORKOUTS, report.skipped.invalidWorkouts)
            .putInt(Skipped.INVALID_SETS, report.skipped.invalidSets)
            .putInt(Skipped.DUPLICATE_TEMPLATES, report.skipped.duplicateTemplates)
            .putInt(Skipped.INVALID_TEMPLATES, report.skipped.invalidTemplates)
            .putInt(Skipped.DUPLICATE_MEASUREMENTS, report.skipped.duplicateMeasurements)
            .putInt(Skipped.INVALID_MEASUREMENTS, report.skipped.invalidMeasurements)
            .putInt(Skipped.DUPLICATE_PHOTOS, report.skipped.duplicatePhotos)
            .putInt(Skipped.INVALID_PHOTOS, report.skipped.invalidPhotos)
            .build()

    /** The report [output] holds. */
    fun reportOf(output: Data): HistoryImportReport =
        HistoryImportReport(
            workoutsImported = output.getInt(Report.WORKOUTS, 0),
            setsImported = output.getInt(Report.SETS, 0),
            exercisesMatched = output.getInt(Report.EXERCISES_MATCHED, 0),
            exercisesCreated = output.getInt(Report.EXERCISES_CREATED, 0),
            templatesImported = output.getInt(Report.TEMPLATES, 0),
            measurementsImported = output.getInt(Report.MEASUREMENTS, 0),
            photosImported = output.getInt(Report.PHOTOS, 0),
            photosFailed = output.getInt(Report.PHOTOS_FAILED, 0),
            skipped = ImportSkips(
                duplicateWorkouts = output.getInt(Skipped.DUPLICATE_WORKOUTS, 0),
                invalidWorkouts = output.getInt(Skipped.INVALID_WORKOUTS, 0),
                invalidSets = output.getInt(Skipped.INVALID_SETS, 0),
                duplicateTemplates = output.getInt(Skipped.DUPLICATE_TEMPLATES, 0),
                invalidTemplates = output.getInt(Skipped.INVALID_TEMPLATES, 0),
                duplicateMeasurements = output.getInt(Skipped.DUPLICATE_MEASUREMENTS, 0),
                invalidMeasurements = output.getInt(Skipped.INVALID_MEASUREMENTS, 0),
                duplicatePhotos = output.getInt(Skipped.DUPLICATE_PHOTOS, 0),
                invalidPhotos = output.getInt(Skipped.INVALID_PHOTOS, 0),
            ),
            recordsSet = output.getInt(Report.RECORDS, 0),
            xpEarned = output.getLong(Report.XP, 0),
            achievementsUnlocked = output.getInt(Report.ACHIEVEMENTS, 0),
            questsCompleted = output.getInt(Report.QUESTS, 0),
            collectiblesFound = output.getInt(Report.COLLECTIBLES, 0),
        )

    /**
     * What [info] — the import's work, or null when there is none — means to the screen.
     *
     * @param runsHere whether [info]'s work started in this process. WorkManager writes
     *   `RUNNING` before the worker starts and nothing rewrites it when the process dies, so a
     *   `RUNNING` record that has already published a phase, with no worker here to have
     *   published it, belongs to a process that is gone, and its import went with it. That is
     *   reported as [HistoryImportRun.Interrupted] at once, rather than as a bar frozen at
     *   whatever it last said until WorkManager gets round to re-running the work — which the
     *   worker then turns into the same answer.
     */
    fun runOf(
        info: WorkInfo?,
        runsHere: Boolean,
    ): HistoryImportRun {
        if (info == null) return HistoryImportRun.None
        val request = requestOf(info.tags) ?: return HistoryImportRun.None
        val published = progressOf(info.progress)
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                HistoryImportRun.Running(request, HistoryImportProgress.Reading)

            WorkInfo.State.RUNNING -> when {
                published == null -> HistoryImportRun.Running(request, HistoryImportProgress.Reading)
                runsHere -> HistoryImportRun.Running(request, published)
                else -> HistoryImportRun.Interrupted(request)
            }

            WorkInfo.State.SUCCEEDED -> HistoryImportRun.Completed(request, reportOf(info.outputData))

            WorkInfo.State.FAILED -> if (info.outputData.getBoolean(KEY_INTERRUPTED, false)) {
                HistoryImportRun.Interrupted(request)
            } else {
                HistoryImportRun.Failed(request, errorOf(info.outputData))
            }

            WorkInfo.State.CANCELLED -> HistoryImportRun.Cancelled(request)
        }
    }

    private fun counted(
        phase: String,
        done: Int,
        total: Int,
    ): Data =
        Data.Builder()
            .putString(KEY_PHASE, phase)
            .putInt(KEY_DONE, done)
            .putInt(KEY_TOTAL, total)
            .build()

    private fun Data.Builder.putReason(reason: String): Data.Builder =
        putString(KEY_REASON, reason.take(MAX_REASON_LENGTH))

    private fun sourceNamed(name: String): HistoryImportSource? =
        HistoryImportSource.entries.firstOrNull { it.name == name }

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix) else null

    /** The report's figures. */
    private object Report {
        const val WORKOUTS = "workouts"
        const val SETS = "sets"
        const val EXERCISES_MATCHED = "exercises_matched"
        const val EXERCISES_CREATED = "exercises_created"
        const val TEMPLATES = "templates"
        const val MEASUREMENTS = "measurements"
        const val PHOTOS = "photos"
        const val PHOTOS_FAILED = "photos_failed"
        const val RECORDS = "records"
        const val XP = "xp"
        const val ACHIEVEMENTS = "achievements"
        const val QUESTS = "quests"
        const val COLLECTIBLES = "collectibles"
    }

    /** What the report says was left out. */
    private object Skipped {
        const val DUPLICATE_WORKOUTS = "skipped_duplicate_workouts"
        const val INVALID_WORKOUTS = "skipped_invalid_workouts"
        const val INVALID_SETS = "skipped_invalid_sets"
        const val DUPLICATE_TEMPLATES = "skipped_duplicate_templates"
        const val INVALID_TEMPLATES = "skipped_invalid_templates"
        const val DUPLICATE_MEASUREMENTS = "skipped_duplicate_measurements"
        const val INVALID_MEASUREMENTS = "skipped_invalid_measurements"
        const val DUPLICATE_PHOTOS = "skipped_duplicate_photos"
        const val INVALID_PHOTOS = "skipped_invalid_photos"
    }
}
