package com.titan.core.domain.usecase

import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.ExerciseRef
import com.titan.core.domain.importer.ExistingTemplate
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportBaseline
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportPlan
import com.titan.core.domain.importer.ImportPlanner
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedPhoto
import com.titan.core.domain.importer.MAX_IMPORT_SETS
import com.titan.core.domain.importer.MAX_SOURCE_BYTES
import com.titan.core.domain.importer.ParseOutcome
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.PlannedWorkout
import com.titan.core.domain.importer.parseSafely
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SessionProgression
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.HistoryImportRepository
import com.titan.core.domain.repository.ProgressPhotoRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Brings a training history from another app into Titan, alongside whatever the user already has.
 *
 * The engine every import source shares: a [HistoryParser] turns one app's file into an
 * [ImportedHistory], and everything after that — validation, duplicates, exercise matching,
 * writing, and working out what the history earned — happens here, identically for every app.
 *
 * ### Merge, not replace
 *
 * The backup restore ([ImportUseCase]) replaces the database with a file, because a backup *is*
 * the user's Titan data. This is the opposite: the file is a second history, and the user's own
 * stays. Nothing on the device is deleted except records the replay is about to derive again —
 * see below — and a workout already on the device is recognised and not written twice, so
 * importing the same file twice is harmless. [ImportPlanner] says how duplicates are recognised.
 *
 * ### Indistinguishable from a logged history
 *
 * Every imported workout is written as a finished session and then handed to
 * [SessionCompletionPipeline] — the same call `FinishSessionUseCase` makes at the end of a
 * workout — with the moment it finished. So its records are detected by the same rules, its XP is
 * paid by the same economy, its week counts towards the streak, and its quests and achievements
 * are completed into the periods it was trained in. No source's own records or points are read:
 * an app that exports records and one that does not end up with the same records here, because
 * both are derived from the sets.
 *
 * The workouts go in **in the order they finished**, each written and replayed before the next is
 * written, because every step of the pipeline reads what is already on file: a record is a record
 * against the ones before it, the streak advances from the last session counted, an achievement
 * counts the sessions finished so far.
 *
 * ### A history older than the device's
 *
 * Usually the import is the user's past and the device holds their present, and replaying the
 * imported workouts in order lands each one after everything it should be judged against. When
 * the file reaches past sessions already on the device, the records those sessions set were
 * judged against a history that was missing the imported part, so they are forgotten
 * ([HistoryImportRepository.forgetRecordsOfSessionsFinishedFrom]) from the earliest imported
 * workout on, and those sessions are replayed too, interleaved with the imported ones in finishing
 * order. Their XP and loot are already claimed and their streak week already counted, so the
 * replay re-derives their records and changes nothing else about them — which is the
 * pipeline's re-entry contract doing exactly what it was built for.
 *
 * A session the device imported from Health Connect is never replayed. It claimed nothing when it
 * came in, because an import from there earns nothing (see [WorkoutSession.healthConnectId]), so
 * the cascade would not find it already paid: it would pay it.
 *
 * Two things a replay cannot rewrite in that case, and does not try to: the streak only advances
 * from the last session counted, so imported weeks older than it do not join a streak; and an
 * achievement already unlocked keeps the date it was first unlocked here.
 *
 * ### A replay step that fails fails the import
 *
 * A finished workout keeps its session when a step of its cascade fails — see
 * [SessionCompletionPipeline]'s failure contract — because each of its writes has committed by
 * then. Inside the import's transaction nothing has, and a write that throws there ends the nested
 * transaction it ran in without success, which leaves SQLite to roll the *whole* import back at the
 * commit without raising anything. Reporting such a run as complete would tell the user their
 * history was in when none of it was. So any step that fails throws out of the transaction instead,
 * and the import reports [HistoryImportError.StorageFailure] with nothing written: a retry starts
 * clean, which a history imported without its records could not.
 *
 * ### One transaction, two locks — and the photos after it
 *
 * The whole history, writes and replay, is one transaction
 * ([HistoryImportRepository.atomically]): it lands entirely or not at all, so a failure or a
 * cancelled collector leaves the device exactly as it was and a retry starts clean.
 *
 * Progress photos are the one exception, and are stored **after** that transaction commits. Each
 * is an image to decode, scale and re-encode, and holding the database's single write transaction
 * across seconds of that would hold every other write in the app behind it. So a photo that will
 * not store is counted in the report rather than undoing a history that is already in — and since
 * a photo already on the device is recognised by when it was taken, running the same import again
 * stores only the ones that did not land. It runs under
 * [BackupLock], so no export, weekly backup, restore or delete-all interleaves with it, and under
 * [SessionLifecycleLock] inside that — the order every other holder of both takes them in — so no
 * session finishes between the counters the replay reads and the rows it writes. A workout in
 * progress is left alone and finishes normally afterwards.
 *
 * Parsing happens before the locks, so a large file does not hold up a backup while it is being
 * read. Planning cannot: it is judged against what the device holds, and that has to be read
 * inside the transaction the plan is written in, or a write landing between the two would be one
 * the plan never saw.
 *
 * ### Nothing escapes untyped
 *
 * As with [ImportUseCase], failures arrive as [HistoryImportProgress.Failed] and the flow
 * completes normally. That includes a parser that throws on a file it did not expect and a store
 * that refuses a write: a hostile file is a refused import, never a crashed app.
 *
 * The parse blocks; collect on a dispatcher where that is fine, as the backup screen does for a
 * restore.
 */
class ImportHistoryUseCase(
    private val historyImportRepository: HistoryImportRepository,
    private val exerciseRepository: ExerciseRepository,
    private val sessionRepository: SessionRepository,
    private val templateRepository: TemplateRepository,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val progressPhotoRepository: ProgressPhotoRepository,
    private val completeSession: SessionCompletionPipeline,
    private val clock: Clock = Clock.SYSTEM,
    private val backupLock: BackupLock = BackupLock.SHARED,
    private val sessionLock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
    private val maxSourceBytes: Long = MAX_SOURCE_BYTES,
    private val maxSets: Int = MAX_IMPORT_SETS,
) {

    /** Parses [source] with [parser] and imports what it holds. */
    operator fun invoke(
        source: ImportSource,
        parser: HistoryParser,
        context: ImportContext,
    ): Flow<HistoryImportProgress> =
        channelFlow {
            send(HistoryImportProgress.Reading)
            val outcome = parser.parseSafely(source, context.copy(maxSets = maxSets), maxSourceBytes)
            val parsed = ((outcome as? ParseOutcome.Read)?.result as? ParseResult.Parsed)?.history
            send(if (parsed != null) import(parsed) else failed(outcome.refusal()))
        }

    /**
     * Imports [history], which a caller has already parsed — the entry point for a source whose
     * data does not arrive as a file.
     */
    operator fun invoke(history: ImportedHistory): Flow<HistoryImportProgress> =
        channelFlow {
            send(HistoryImportProgress.Reading)
            send(import(history))
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<HistoryImportProgress>.import(history: ImportedHistory): HistoryImportProgress {
        if (history.isEmpty) return failed(HistoryImportError.NothingToImport)
        // A parser stops at the limit as it reads; this holds a history handed over already parsed
        // to the same one. See MAX_IMPORT_SETS.
        if (history.setCount > maxSets) return failed(HistoryImportError.TooManySets(maxSets))
        return try {
            backupLock.exclusively {
                val (plan, report) = sessionLock.withTransition {
                    historyImportRepository.atomically {
                        val sessions = sessionRepository.getAll().first()
                        val plan = ImportPlanner.plan(history, baseline(sessions))
                        plan to write(plan, sessions)
                    }
                }
                // After the commit, outside the transaction and the session lock but still under
                // the backup lock: see the class doc. A photo that will not store is counted, not
                // fatal — the history it came with is already in.
                storePhotos(plan.photos, report)
            }.let(HistoryImportProgress::Complete)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // The transaction rolled back before this was thrown. Typed rather than rethrown for
            // the reason the class doc gives: whatever refused the write — a full disk, a
            // constraint the planner did not model — the user is owed "nothing was imported", not
            // a crash.
            failed(HistoryImportError.StorageFailure(failure.message ?: failure::class.java.simpleName))
        }
    }

    /**
     * What the device holds that the plan has to fit around, read inside the transaction.
     * [sessions] is every session on the device, read once by the caller for this and the replay.
     */
    private suspend fun baseline(sessions: List<WorkoutSession>): ImportBaseline {
        val templateExercises = templateRepository.getAllExercises().first().groupBy(TemplateExercise::templateId)
        return ImportBaseline(
            exercises = exerciseRepository.getAll().first(),
            muscleGroups = exerciseRepository.getMuscleGroups().first(),
            sessionStarts = sessions.mapTo(HashSet(), WorkoutSession::createdAt),
            measurementTimes = bodyMeasurementRepository.getAll().first().mapTo(HashSet()) { it.createdAt },
            templates = templateRepository.getAll().first().map { template ->
                ExistingTemplate(
                    name = template.name,
                    exerciseIds = templateExercises[template.id].orEmpty().map(TemplateExercise::exerciseId),
                )
            },
            nowMillis = clock.nowMillis(),
            photoTimes = progressPhotoRepository.getAll().first().mapTo(HashSet()) { it.takenAt },
        )
    }

    /**
     * Stores [photos] one at a time and answers with [report] counting them. A photo the store
     * refuses for any reason — bytes that will not read, or are not an image — is counted in
     * [HistoryImportReport.photosFailed] and the rest carry on.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<HistoryImportProgress>.storePhotos(
        photos: List<ImportedPhoto>,
        report: HistoryImportReport,
    ): HistoryImportReport {
        if (photos.isEmpty()) return report
        var stored = 0
        var failed = 0
        send(HistoryImportProgress.StoringPhotos(photosStored = 0, photosTotal = photos.size))
        photos.forEach { photo ->
            try {
                progressPhotoRepository.add(photo.source, takenAt = photo.takenAt, preferImageTime = false)
                stored++
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Not only an IOException: a source whose permission was revoked throws a
                // SecurityException, a full table a database exception. The history is already
                // committed, so none of them may turn into a Failed that says nothing was imported.
                failed++
            }
            send(HistoryImportProgress.StoringPhotos(photosStored = stored + failed, photosTotal = photos.size))
        }
        return report.copy(photosImported = stored, photosFailed = failed)
    }

    /**
     * Writes [plan] and replays its workouts among [existingSessions], answering with what landed.
     */
    private suspend fun ProducerScope<HistoryImportProgress>.write(
        plan: ImportPlan,
        existingSessions: List<WorkoutSession>,
    ): HistoryImportReport {
        val exerciseIds = plan.newExercises.map { new ->
            exerciseRepository.insert(
                exercise = new.exercise,
                muscleGroups = listOfNotNull(new.primaryMuscle?.let { ExerciseMuscleGroupLink(it, isPrimary = true) }),
            )
        }
        fun ExerciseRef.resolvedId(): Long =
            when (this) {
                is ExerciseRef.Existing -> id
                is ExerciseRef.New -> exerciseIds[index]
            }

        plan.measurements.forEach { bodyMeasurementRepository.insert(it) }
        plan.templates.forEach { template ->
            templateRepository.insert(
                template = WorkoutTemplate(name = template.name),
                exercises = template.exercises.map { line ->
                    TemplateExerciseDraft(
                        exerciseId = line.exercise.resolvedId(),
                        sets = line.sets,
                        notes = line.notes,
                        supersetGroup = line.supersetGroup,
                    )
                },
            )
        }

        val tally = ProgressionTally()
        replay(plan.workouts, existingSessions, tally) { workout -> insert(workout) { it.resolvedId() } }

        return HistoryImportReport(
            workoutsImported = plan.workouts.size,
            setsImported = plan.setCount,
            exercisesMatched = plan.matchedExercises,
            exercisesCreated = plan.newExercises.size,
            templatesImported = plan.templates.size,
            measurementsImported = plan.measurements.size,
            skipped = plan.skipped,
            recordsSet = tally.records,
            xpEarned = tally.xp,
            achievementsUnlocked = tally.achievements,
            questsCompleted = tally.quests,
            collectiblesFound = tally.collectibles,
        )
    }

    /**
     * Writes each of [workouts] with [insert] and runs the completion pipeline over it, in
     * finishing order — interleaved with any of [existingSessions] that finished after the
     * earliest of them, whose records are forgotten first. See the class doc.
     */
    private suspend fun ProducerScope<HistoryImportProgress>.replay(
        workouts: List<PlannedWorkout>,
        existingSessions: List<WorkoutSession>,
        tally: ProgressionTally,
        insert: suspend (PlannedWorkout) -> Long,
    ) {
        val earliest = workouts.firstOrNull()?.finishedAt ?: return
        val existing = existingSessions.filter { session ->
            !session.isInProgress && !session.isImported && (session.completedAt ?: Long.MIN_VALUE) >= earliest
        }
        if (existing.isNotEmpty()) historyImportRepository.forgetRecordsOfSessionsFinishedFrom(earliest)

        val timeline = workouts.map { Replay.Imported(it) } + existing.map { Replay.Existing(it) }
        var written = 0
        progressInTransaction(HistoryImportProgress.Writing(workoutsWritten = 0, workoutsTotal = workouts.size))
        timeline.sortedWith(compareBy(Replay::finishedAt, Replay::startedAt)).forEach { entry ->
            when (entry) {
                is Replay.Imported -> {
                    val sessionId = insert(entry.workout)
                    tally.add(completeSession(sessionId, Instant.ofEpochMilli(entry.finishedAt)), imported = true)
                    progressInTransaction(
                        HistoryImportProgress.Writing(workoutsWritten = ++written, workoutsTotal = workouts.size),
                    )
                }

                is Replay.Existing ->
                    tally.add(
                        completeSession(entry.session.id, Instant.ofEpochMilli(entry.finishedAt)),
                        imported = false,
                    )
            }
        }
    }

    /**
     * Reports [progress] from inside the import's transaction without ever waiting for the
     * collector to take it.
     *
     * `send` suspends while the flow's buffer is full, and a collector stops taking — a screen
     * in the background, a slow frame — whenever it likes. Suspending here would hold SQLite's
     * single write transaction for as long as that lasts, and every write in the app with it:
     * a set ticked off in another session, a reminder rescheduled. So an update that does not
     * fit is dropped instead. Nothing is lost by that: the updates count upwards, the next one
     * that fits says more than the one it replaced, and the phase after the commit is reported
     * with `send`, which may wait, because by then nothing is held but the backup lock.
     */
    private fun ProducerScope<HistoryImportProgress>.progressInTransaction(progress: HistoryImportProgress) {
        trySend(progress)
    }

    /** Writes [workout] as a finished session with its exercises and sets, returning its id. */
    private suspend fun insert(
        workout: PlannedWorkout,
        idOf: (ExerciseRef) -> Long,
    ): Long {
        val sessionId = sessionRepository.insert(
            WorkoutSession(
                name = workout.name,
                completedAt = workout.finishedAt,
                isInProgress = false,
                lastActiveAt = workout.finishedAt,
                notes = workout.notes,
                calories = workout.calories,
                rating = workout.rating,
                createdAt = workout.startedAt,
                updatedAt = workout.finishedAt,
            ),
        )
        val sessionExerciseIds = sessionRepository.insertExercises(
            sessionId = sessionId,
            exercises = workout.exercises.map { line ->
                SessionExerciseDraft(
                    exerciseId = idOf(line.exercise),
                    supersetGroup = line.supersetGroup,
                    notes = line.notes,
                )
            },
            createdAt = workout.startedAt,
        )
        sessionRepository.insertSets(
            workout.exercises.zip(sessionExerciseIds) { line, id -> line.sets.map { it.copy(sessionExerciseId = id) } }
                .flatten(),
        )
        return sessionId
    }

    private fun failed(error: HistoryImportError) = HistoryImportProgress.Failed(error)

    /** Why [this] outcome is not a history to import. Only called on one that is not. */
    private fun ParseOutcome.refusal(): HistoryImportError =
        when (this) {
            is ParseOutcome.TooLarge -> HistoryImportError.TooLarge(limitBytes)

            is ParseOutcome.TooManySets -> HistoryImportError.TooManySets(limitSets)

            is ParseOutcome.Unreadable -> HistoryImportError.Unreadable(reason)

            is ParseOutcome.Read -> when (val read = result) {
                ParseResult.NotRecognised -> HistoryImportError.NotRecognised
                is ParseResult.Malformed -> HistoryImportError.Malformed(read.reason)
                is ParseResult.Parsed -> error("a parsed history is not a refusal")
            }
        }

    /** One entry of the replay's timeline. */
    private sealed interface Replay {
        val finishedAt: Long
        val startedAt: Long

        data class Imported(val workout: PlannedWorkout) : Replay {
            override val finishedAt get() = workout.finishedAt
            override val startedAt get() = workout.startedAt
        }

        /** A finished session already on the device; its `completedAt` is non-null by the filter. */
        data class Existing(val session: WorkoutSession) : Replay {
            override val finishedAt get() = checkNotNull(session.completedAt)
            override val startedAt get() = session.createdAt
        }
    }

    /**
     * The replay's earnings, summed across every pipeline run.
     *
     * Records are counted for imported workouts only: an existing session's are re-derived, not
     * new, and reporting them would tell the user the import set records they had already set.
     * Everything else an existing session's replay can produce *is* new — a quest the imported
     * workouts brought within reach, completed by the session that crossed it — so it counts.
     *
     * A run with a failed step is not added but thrown, which rolls the import back: see the class
     * doc for why a replay cannot keep the failure contract a logged workout does.
     */
    private class ProgressionTally {
        var records = 0
        var xp = 0L
        var achievements = 0
        var quests = 0
        var collectibles = 0

        fun add(
            progression: SessionProgression,
            imported: Boolean,
        ) {
            progression.failures.firstOrNull()?.let { failure ->
                throw IllegalStateException(
                    "replaying session ${progression.sessionId} failed at ${failure.step}: " +
                        (failure.cause.message ?: failure.cause::class.java.simpleName),
                    failure.cause,
                )
            }
            if (imported) records += progression.recordsBroken.size
            xp += progression.xpAwarded
            achievements += progression.achievementsUnlocked.size
            quests += progression.questsCompleted.size
            if (progression.lootDropped != null) collectibles++
        }
    }
}
