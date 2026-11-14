package com.titan.core.data.db

import com.titan.core.data.mapper.BodyMeasurementMapper
import com.titan.core.data.mapper.ExerciseMapper
import com.titan.core.data.mapper.MuscleGroupLinkMapper
import com.titan.core.data.mapper.PersonalRecordMapper
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.TagMapper
import com.titan.core.data.mapper.TemplateExerciseMapper
import com.titan.core.data.mapper.TemplateMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.repository.BodyMeasurementRepositoryImpl
import com.titan.core.data.repository.CollectedItemRepositoryImpl
import com.titan.core.data.repository.ExerciseRepositoryImpl
import com.titan.core.data.repository.GamificationRepositoryImpl
import com.titan.core.data.repository.HistoryImportRepositoryImpl
import com.titan.core.data.repository.PersonalRecordRepositoryImpl
import com.titan.core.data.repository.ProgressionRepositoryImpl
import com.titan.core.data.repository.QuestRepositoryImpl
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.data.repository.TemplateRepositoryImpl
import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.gamification.DropRates
import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedTemplate
import com.titan.core.domain.importer.ImportedTemplateExercise
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.AchievementTriggerUseCase
import com.titan.core.domain.usecase.AwardXpUseCase
import com.titan.core.domain.usecase.ImportHistoryUseCase
import com.titan.core.domain.usecase.LootRollUseCase
import com.titan.core.domain.usecase.PrDetectionUseCase
import com.titan.core.domain.usecase.QuestCompletionUseCase
import com.titan.core.domain.usecase.QuestProgressUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
import com.titan.core.domain.usecase.SessionLifecycleLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

/**
 * The import engine over the database it writes to, with the completion pipeline it shares with a
 * finished workout.
 *
 * `ImportPlannerTest` pins every rule about *what* an import writes. What only a real store can
 * show is the rest: that the writes and the replay are one transaction a cancelled collector rolls
 * back, that the replay's records, XP and unlocks are the rows the screens read, that a second
 * import of the same file pays nothing, and that a history older than the device's re-derives the
 * device's records in the order the training happened — which is a `DELETE` this suite is the
 * only thing to run.
 */
class HistoryImportCascadeTest : RoomTestBase() {

    private val sessions by lazy {
        SessionRepositoryImpl(
            database = db,
            sessionMapper = SessionMapper(),
            sessionExerciseMapper = SessionExerciseMapper(),
            setMapper = WorkoutSetMapper(),
        )
    }

    private val exercises by lazy {
        ExerciseRepositoryImpl(
            exerciseDao = db.exerciseDao(),
            muscleGroupDao = db.muscleGroupDao(),
            exerciseMapper = ExerciseMapper(),
            tagMapper = TagMapper(),
            muscleGroupLinkMapper = MuscleGroupLinkMapper(),
        )
    }

    private val records by lazy {
        PersonalRecordRepositoryImpl(
            personalRecordDao = db.personalRecordDao(),
            personalRecordMapper = PersonalRecordMapper(),
        )
    }

    private val gamification by lazy {
        GamificationRepositoryImpl(
            userProgressDao = db.userProgressDao(),
            achievementDao = db.achievementDao(),
            sessionXpClaimDao = db.sessionXpClaimDao(),
            sessionLootClaimDao = db.sessionLootClaimDao(),
        )
    }

    private val quests by lazy { QuestRepositoryImpl(questCompletionDao = db.questCompletionDao()) }

    private val preferences = InMemoryUserPreferencesRepository()

    private val pipeline by lazy {
        val awardXp = AwardXpUseCase(gamificationRepository = gamification)
        SessionCompletionPipeline(
            detectPersonalRecords = PrDetectionUseCase(
                sessionRepository = sessions,
                exerciseRepository = exercises,
                personalRecordRepository = records,
                userPreferencesRepository = preferences,
            ),
            awardXp = awardXp,
            triggerAchievements = AchievementTriggerUseCase(gamificationRepository = gamification, awardXp = awardXp),
            completeQuests = QuestCompletionUseCase(
                questProgress = QuestProgressUseCase(
                    progressionRepository = ProgressionRepositoryImpl(
                        setDao = db.workoutSetDao(),
                        sessionDao = db.workoutSessionDao(),
                        setMapper = WorkoutSetMapper(),
                    ),
                    personalRecordRepository = records,
                    questRepository = quests,
                ),
                questRepository = quests,
                awardXp = awardXp,
            ),
            // Never drops, as in `SessionCompletionCascadeTest`: a die roll is not this suite's
            // subject, and an assertion that depended on one would be a flake.
            rollLoot = LootRollUseCase(
                dropTable = DropTableEvaluator(RandomSource { DropRates.TOTAL_WEIGHT - 1 }, emptyList()),
                collectedItemRepository = CollectedItemRepositoryImpl(collectedItemDao = db.collectedItemDao()),
                gamificationRepository = gamification,
            ),
            sessionRepository = sessions,
            personalRecordRepository = records,
            gamificationRepository = gamification,
            userPreferencesRepository = preferences,
        )
    }

    private val importHistory by lazy {
        ImportHistoryUseCase(
            historyImportRepository = HistoryImportRepositoryImpl(db),
            exerciseRepository = exercises,
            sessionRepository = sessions,
            templateRepository = TemplateRepositoryImpl(
                templateDao = db.workoutTemplateDao(),
                templateMapper = TemplateMapper(),
                templateExerciseMapper = TemplateExerciseMapper(),
            ),
            bodyMeasurementRepository = BodyMeasurementRepositoryImpl(
                bodyMeasurementDao = db.bodyMeasurementDao(),
                bodyMeasurementMapper = BodyMeasurementMapper(),
            ),
            completeSession = pipeline,
            clock = Clock { NOW },
            backupLock = BackupLock(),
            sessionLock = SessionLifecycleLock(),
        )
    }

    private var benchId = 0L

    @Before
    fun seedCatalogue() =
        runBlocking {
            benchId = db.exerciseDao().insert(exercise(name = "Bench Press"))
            db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST")))
            Unit
        }

    private val bench = ImportedExercise(name = "bench press")

    private fun benchWorkout(
        day: Long,
        weight: Long,
    ) = ImportedWorkout(
        startedAt = day * DAY,
        durationSeconds = 3_600,
        name = "Push",
        exercises = listOf(
            ImportedWorkoutExercise(bench, sets = List(3) { ImportedSet(reps = 5, weight = weight) }),
        ),
    )

    private suspend fun import(history: ImportedHistory): List<HistoryImportProgress> = importHistory(history).toList()

    private fun List<HistoryImportProgress>.report(): HistoryImportReport =
        (last() as HistoryImportProgress.Complete).report

    private suspend fun totalXp(): Long = db.userProgressDao().observe().first()?.totalXp ?: 0L

    // ------------------------------------------------------------------ the whole import

    @Test
    fun `an imported history lands as finished sessions and pays as if it had been logged`() =
        runBlocking {
            val fly = ImportedExercise(name = "Cable Fly", primaryMuscle = "chest")
            val history = ImportedHistory(
                workouts = listOf(
                    benchWorkout(day = 1, weight = KG_100),
                    benchWorkout(day = 8, weight = KG_110).let { workout ->
                        workout.copy(
                            exercises = workout.exercises + ImportedWorkoutExercise(
                                fly,
                                listOf(ImportedSet(reps = 12)),
                            ),
                        )
                    },
                ),
                templates = listOf(
                    ImportedTemplate("Push", listOf(ImportedTemplateExercise(bench, listOf(PlannedSet(reps = 5))))),
                ),
                bodyMeasurements = listOf(BodyMeasurement(weight = 80_000_000, createdAt = DAY, updatedAt = DAY)),
            )

            val progress = import(history)
            val report = progress.report()

            assertEquals(
                listOf(
                    HistoryImportProgress.Reading,
                    HistoryImportProgress.Writing(0, 2),
                    HistoryImportProgress.Writing(1, 2),
                    HistoryImportProgress.Writing(2, 2),
                ),
                progress.dropLast(1),
            )
            assertEquals(2, report.workoutsImported)
            assertEquals(7, report.setsImported)
            assertEquals(1, report.exercisesMatched)
            assertEquals(1, report.exercisesCreated)
            assertEquals(1, report.templatesImported)
            assertEquals(1, report.measurementsImported)

            val stored = sessions.getAll().first()
            assertEquals(2, stored.size)
            assertTrue(stored.all { !it.isInProgress && it.completedAt != null })
            assertEquals(1, countRows("exercise_muscle_group"))

            // The second workout beat the first: the latest heaviest bench is its 110 kg.
            val heaviest = records.getRecordsForExercise(benchId).first().filter { it.type == PrType.MAX_WEIGHT }
            assertEquals(listOf(KG_110, KG_100), heaviest.map { it.weight })

            // Paid through the real cascade, and reported as what the store now holds.
            assertTrue(report.xpEarned > 0)
            assertEquals(report.xpEarned, totalXp())
            assertEquals(2, countRows("session_xp_claim"))
            assertEquals(report.achievementsUnlocked, countRows("achievement"))
            assertEquals(report.questsCompleted, countRows("quest_completion"))
        }

    @Test
    fun `importing the same history twice writes it once and pays once`() =
        runBlocking {
            val history = ImportedHistory(
                workouts = listOf(benchWorkout(day = 1, weight = KG_100), benchWorkout(day = 3, weight = KG_110)),
            )
            import(history)
            val xp = totalXp()

            val again = import(history).report()

            assertEquals(0, again.workoutsImported)
            assertEquals(2, again.skipped.duplicateWorkouts)
            assertEquals(2, countRows("workout_session"))
            assertEquals(xp, totalXp())
        }

    @Test
    fun `a history older than the device's re-derives the device's records in the order they happened`() =
        runBlocking {
            import(ImportedHistory(workouts = listOf(benchWorkout(day = 20, weight = KG_100))))
            val xp = totalXp()

            val older = import(ImportedHistory(workouts = listOf(benchWorkout(day = 5, weight = KG_110)))).report()

            // Judged in order, day 5's 110 kg is the record and day 20's 100 kg beat nothing. Without
            // the re-derivation the older row would sit *below* the newer one as the latest of its
            // kind, and the records screen would show a 100 kg best for a lifter who has lifted 110.
            val heaviest = records.getRecordsForExercise(benchId).first().filter { it.type == PrType.MAX_WEIGHT }
            assertEquals(listOf(KG_110), heaviest.map { it.weight })
            assertEquals(5 * DAY, heaviest.single().achievedAt / DAY * DAY)

            // The session already on the device was replayed for its records only: its XP was
            // already claimed, so the total moved by exactly what the import reports.
            assertEquals(xp + older.xpEarned, totalXp())
            assertEquals(2, countRows("session_xp_claim"))
        }

    @Test
    fun `a cancelled import leaves nothing behind`() =
        runBlocking {
            val history = ImportedHistory(
                workouts = listOf(benchWorkout(day = 1, weight = KG_100), benchWorkout(day = 2, weight = KG_110)),
                bodyMeasurements = listOf(BodyMeasurement(weight = 80_000_000, createdAt = DAY, updatedAt = DAY)),
            )

            // Stop collecting once the first workout is in: the producer is cancelled inside the
            // transaction, and the whole import goes with it.
            importHistory(history).takeWhile { it !is HistoryImportProgress.Writing || it.workoutsWritten < 1 }.toList()

            assertEquals(0, countRows("workout_session"))
            assertEquals(0, countRows("body_measurement"))
            assertEquals(0, countRows("personal_record"))
            assertEquals(0, countRows("user_progress"))
        }

    // ------------------------------------------------------------------ refusals

    private fun parsing(result: () -> ParseResult) =
        object : HistoryParser {
            override fun parse(
                source: ImportSource,
                context: ImportContext,
            ): ParseResult = result()
        }

    private suspend fun importWith(parser: HistoryParser): HistoryImportProgress =
        importHistory(
            source = { "date,exercise".byteInputStream() },
            parser = parser,
            context = ImportContext(ZoneOffset.UTC, UnitSystem.METRIC),
        ).toList().last()

    @Test
    fun `a file the parser does not recognise is refused and writes nothing`() =
        runBlocking {
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.NotRecognised),
                importWith(parsing { ParseResult.NotRecognised }),
            )
            assertEquals(0, countRows("workout_session"))
        }

    @Test
    fun `a parser that throws on a hostile file has it refused rather than crashing`() =
        runBlocking {
            val outcome = importWith(parsing { error("unexpected column") })

            assertEquals(HistoryImportProgress.Failed(HistoryImportError.Malformed("unexpected column")), outcome)
        }

    @Test
    fun `a parsed file goes through the same engine as a parsed history`() =
        runBlocking {
            val outcome = importWith(
                parsing { ParseResult.Parsed(ImportedHistory(workouts = listOf(benchWorkout(1, KG_100)))) },
            )

            assertEquals(1, (outcome as HistoryImportProgress.Complete).report.workoutsImported)
        }

    @Test
    fun `a file with nothing in it is reported as such`() =
        runBlocking {
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.NothingToImport),
                importWith(parsing { ParseResult.Parsed(ImportedHistory()) }),
            )
        }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 100 * DAY
        const val KG_100 = 100_000_000L
        const val KG_110 = 110_000_000L
    }
}
