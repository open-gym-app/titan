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
 * Two things a replay cannot rewrite in that case, and does not try to: the streak only advances
 * from the last session counted, so imported weeks older than it do not join a streak; and an
 * achievement already unlocked keeps the date it was first unlocked here.
 *
 * ### One transaction, two locks
 *
 * The whole import, writes and replay, is one transaction
 * ([HistoryImportRepository.atomically]): it lands entirely or not at all, so a failure or a
 * cancelled collector leaves the device exactly as it was and a retry starts clean. It runs under
 * [BackupLock], so no export, weekly backup, restore or delete-all interleaves with it, and under
 * [SessionLifecycleLock] inside that — the order every other holder of both takes them in — so no
 * session finishes between the counters the replay reads and the rows it writes. A workout in
 * progress is left alone and finishes normally afterwards.
 *
 * Parsing and planning-in-memory happen before the locks, apart from the plan's read of the
 * device, so a large file does not hold up a backup while it is being read.
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
    private val completeSession: SessionCompletionPipeline,
    private val clock: Clock = Clock.SYSTEM,
    private val backupLock: BackupLock = BackupLock.SHARED,
    private val sessionLock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
    private val maxSourceBytes: Long = MAX_SOURCE_BYTES,
) {

    /** Parses [source] with [parser] and imports what it holds. */
    operator fun invoke(
        source: ImportSource,
        parser: HistoryParser,
        context: ImportContext,
    ): Flow<HistoryImportProgress> =
        channelFlow {
            send(HistoryImportProgress.Reading)
            val outcome = parser.parseSafely(source, context, maxSourceBytes)
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
        return try {
            backupLock.exclusively {
                sessionLock.withTransition {
                    historyImportRepository.atomically { write(ImportPlanner.plan(history, baseline())) }
                }
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

    /** What the device holds that the plan has to fit around, read inside the transaction. */
    private suspend fun baseline(): ImportBaseline {
        val templateExercises = templateRepository.getAllExercises().first().groupBy(TemplateExercise::templateId)
        return ImportBaseline(
            exercises = exerciseRepository.getAll().first(),
            muscleGroups = exerciseRepository.getMuscleGroups().first(),
            sessionStarts = sessionRepository.getAll().first().mapTo(HashSet(), WorkoutSession::createdAt),
            measurementTimes = bodyMeasurementRepository.getAll().first().mapTo(HashSet()) { it.createdAt },
            templates = templateRepository.getAll().first().map { template ->
                ExistingTemplate(
                    name = template.name,
                    exerciseIds = templateExercises[template.id].orEmpty().map(TemplateExercise::exerciseId),
                )
            },
            nowMillis = clock.nowMillis(),
        )
    }

    /** Writes [plan] and replays its workouts, answering with what landed. */
    private suspend fun ProducerScope<HistoryImportProgress>.write(plan: ImportPlan): HistoryImportReport {
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
        replay(plan.workouts, tally) { workout -> insert(workout) { it.resolvedId() } }

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
            progressionFailures = tally.failures,
        )
    }

    /**
     * Writes each of [workouts] with [insert] and runs the completion pipeline over it, in
     * finishing order — interleaved with any session already on the device that finished after
     * the earliest of them, whose records are forgotten first. See the class doc.
     */
    private suspend fun ProducerScope<HistoryImportProgress>.replay(
        workouts: List<PlannedWorkout>,
        tally: ProgressionTally,
        insert: suspend (PlannedWorkout) -> Long,
    ) {
        val earliest = workouts.firstOrNull()?.finishedAt ?: return
        val existing = sessionRepository.getAll().first().filter { session ->
            !session.isInProgress && (session.completedAt ?: Long.MIN_VALUE) >= earliest
        }
        if (existing.isNotEmpty()) historyImportRepository.forgetRecordsOfSessionsFinishedFrom(earliest)

        val timeline = workouts.map { Replay.Imported(it) } + existing.map { Replay.Existing(it) }
        var written = 0
        send(HistoryImportProgress.Writing(workoutsWritten = 0, workoutsTotal = workouts.size))
        timeline.sortedWith(compareBy(Replay::finishedAt, Replay::startedAt)).forEach { entry ->
            when (entry) {
                is Replay.Imported -> {
                    val sessionId = insert(entry.workout)
                    tally.add(completeSession(sessionId, Instant.ofEpochMilli(entry.finishedAt)), imported = true)
                    send(HistoryImportProgress.Writing(workoutsWritten = ++written, workoutsTotal = workouts.size))
                }

                is Replay.Existing ->
                    tally.add(
                        completeSession(entry.session.id, Instant.ofEpochMilli(entry.finishedAt)),
                        imported = false,
                    )
            }
        }
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
     */
    private class ProgressionTally {
        var records = 0
        var xp = 0L
        var achievements = 0
        var quests = 0
        var collectibles = 0
        var failures = 0

        fun add(
            progression: SessionProgression,
            imported: Boolean,
        ) {
            if (imported) records += progression.recordsBroken.size
            xp += progression.xpAwarded
            achievements += progression.achievementsUnlocked.size
            quests += progression.questsCompleted.size
            if (progression.lootDropped != null) collectibles++
            if (!progression.isComplete) failures++
        }
    }
}
