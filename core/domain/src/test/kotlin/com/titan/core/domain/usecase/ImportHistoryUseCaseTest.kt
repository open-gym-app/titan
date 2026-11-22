package com.titan.core.domain.usecase

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
import com.titan.core.domain.importer.ImportedPhoto
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedTemplate
import com.titan.core.domain.importer.ImportedTemplateExercise
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.ProgressPhoto
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.repository.HistoryImportRepository
import com.titan.core.domain.repository.PhotoSource
import com.titan.core.domain.repository.ProgressPhotoRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset

/**
 * The import engine's sequencing, over the domain's own fakes: what is written, through which
 * repository, in which order, and what every refusal comes back as.
 *
 * The store-level guarantees — one transaction a cancelled collector rolls back, records
 * forgotten by a real `DELETE` — need a real database and are `HistoryImportCascadeTest`'s, in
 * `:core:data`. This suite is what holds the use case's own logic to the domain's coverage floor
 * and runs in milliseconds.
 */
class ImportHistoryUseCaseTest {

    private val chest = MuscleGroup(id = 3, name = "CHEST")
    private val benchPress = Exercise(id = 1, name = "Bench Press", equipmentType = EquipmentType.BARBELL)

    private val sessions = FakeSessionRepository()
    private val exercises = FakeExerciseRepository(listOf(benchPress)).apply {
        // The fake derives its taxonomy from its links, so linking one is how CHEST exists.
        linkMuscles(benchPress.id, ExerciseMuscleGroupLink(chest, isPrimary = true))
    }
    private val templates = FakeTemplateRepository()
    private val measurements = FakeBodyMeasurementRepository()
    private val records = FakePersonalRecordRepository()
    private val gamification = FakeGamificationRepository()
    private val store = FakeHistoryImportRepository()
    private val photos = FakeProgressPhotoRepository().also { it.transaction = store }

    private val pipeline = run {
        val awardXp = AwardXpUseCase(gamification)
        val quests = FakeQuestRepository()
        SessionCompletionPipeline(
            detectPersonalRecords = PrDetectionUseCase(sessions, exercises, records, FakeUserPreferencesRepository()),
            awardXp = awardXp,
            triggerAchievements = AchievementTriggerUseCase(gamification, awardXp),
            completeQuests = QuestCompletionUseCase(
                questProgress = QuestProgressUseCase(
                    FakeProgressionRepository(),
                    records,
                    quests,
                    catalogue = emptyList(),
                ),
                questRepository = quests,
                awardXp = awardXp,
            ),
            rollLoot = LootRollUseCase(
                dropTable = DropTableEvaluator(RandomSource { DropRates.TOTAL_WEIGHT - 1 }, emptyList()),
                collectedItemRepository = FakeCollectedItemRepository(),
                gamificationRepository = gamification,
            ),
            sessionRepository = sessions,
            personalRecordRepository = records,
            gamificationRepository = gamification,
            userPreferencesRepository = FakeUserPreferencesRepository(UserPreferences(weeklyWorkoutGoal = 1)),
        )
    }

    private val backupLock = BackupLock()

    private fun useCase(maxSourceBytes: Long = 1_000) =
        ImportHistoryUseCase(
            historyImportRepository = store,
            exerciseRepository = exercises,
            sessionRepository = sessions,
            templateRepository = templates,
            bodyMeasurementRepository = measurements,
            progressPhotoRepository = photos,
            completeSession = pipeline,
            clock = MutableClock(now = NOW),
            backupLock = backupLock,
            sessionLock = SessionLifecycleLock(),
            maxSourceBytes = maxSourceBytes,
        )

    private val bench = ImportedExercise(name = "bench press")

    private fun workout(
        day: Long,
        weight: Long = KG_100,
    ) = ImportedWorkout(
        startedAt = day * DAY,
        durationSeconds = 1_800,
        name = "Push",
        exercises = listOf(ImportedWorkoutExercise(bench, List(2) { ImportedSet(reps = 5, weight = weight) })),
    )

    private suspend fun import(history: ImportedHistory) = useCase()(history).toList()

    private fun List<HistoryImportProgress>.report(): HistoryImportReport =
        (last() as HistoryImportProgress.Complete).report

    // ------------------------------------------------------------------ writing

    @Test
    fun `workouts are written as finished sessions and replayed, reporting each one as it lands`() =
        runBlocking {
            val progress = import(ImportedHistory(workouts = listOf(workout(day = 2), workout(day = 1))))

            assertEquals(
                listOf(
                    HistoryImportProgress.Reading,
                    HistoryImportProgress.Writing(0, 2),
                    HistoryImportProgress.Writing(1, 2),
                    HistoryImportProgress.Writing(2, 2),
                ),
                progress.dropLast(1),
            )
            val stored = sessions.getAll().first().sortedBy(WorkoutSession::createdAt)
            assertEquals(listOf(DAY, 2 * DAY), stored.map(WorkoutSession::createdAt))
            assertTrue(stored.all { !it.isInProgress && it.completedAt == it.createdAt + 1_800_000 })
            assertEquals(4, stored.sumOf { sessions.getSetsForSession(it.id).first().size })
            assertEquals(1, store.transactions)
        }

    @Test
    fun `the replay detects the records and pays the XP a logged history would earn`() =
        runBlocking {
            val report = import(
                ImportedHistory(workouts = listOf(workout(day = 1), workout(day = 2, weight = KG_110))),
            ).report()

            val heaviest = records.inserted.filter { it.type == PrType.MAX_WEIGHT }.map { it.weight }
            assertEquals(listOf(KG_100, KG_110), heaviest)
            assertEquals(records.inserted.size, report.recordsSet)
            assertTrue(report.xpEarned > 0)
        }

    @Test
    fun `templates, measurements and new exercises are written through their own repositories`() =
        runBlocking {
            val fly = ImportedExercise(name = "Cable Fly", primaryMuscle = "Chest")
            val report = import(
                ImportedHistory(
                    workouts = listOf(
                        workout(day = 1).copy(
                            exercises = listOf(ImportedWorkoutExercise(fly, listOf(ImportedSet(reps = 12)))),
                        ),
                    ),
                    templates = listOf(
                        ImportedTemplate("Chest", listOf(ImportedTemplateExercise(fly, listOf(PlannedSet(reps = 12))))),
                    ),
                    bodyMeasurements = listOf(BodyMeasurement(weight = 80_000_000, createdAt = DAY, updatedAt = DAY)),
                ),
            ).report()

            val created = exercises.getAll().first().single { it.name == "Cable Fly" }
            assertTrue(created.isCustom)
            assertEquals(listOf(chest), exercises.getMuscleGroupLinks(created.id).first().map { it.muscleGroup })
            assertEquals(listOf(created.id), templates.storedExercises(1).map { it.exerciseId })
            assertEquals("Chest", templates.stored(1)?.name)
            assertEquals(listOf(DAY), measurements.getAll().first().map(BodyMeasurement::createdAt))
            assertEquals(1, report.exercisesCreated)
            assertEquals(0, report.exercisesMatched)
            assertEquals(1, report.templatesImported)
            assertEquals(1, report.measurementsImported)
        }

    @Test
    fun `a history newer than the device's leaves the device's records alone`() =
        runBlocking {
            sessions.seedFinished(finished(day = 1))

            import(ImportedHistory(workouts = listOf(workout(day = 5))))

            assertNull(store.forgottenFrom)
        }

    @Test
    fun `a history reaching past the device's forgets the device's records from its earliest workout and replays them`() =
        runBlocking {
            val existing = sessions.seedFinished(finished(day = 10))

            import(ImportedHistory(workouts = listOf(workout(day = 5), workout(day = 20))))

            assertEquals(5 * DAY + 1_800_000, store.forgottenFrom)
            // Replayed in order: the session already on the device was detected again, between the
            // two imported ones — so it set a record of its own against the day-5 baseline.
            val bySession = records.inserted.map { record -> sessions.sessionOfSet(record.workoutSetId) }.distinct()
            assertTrue(existing in bySession)
        }

    @Test
    fun `a workout's calories and rating are written on its session`() =
        runBlocking {
            import(ImportedHistory(workouts = listOf(workout(day = 1).copy(calories = 520, rating = 4))))

            val session = sessions.getAll().first().single()
            assertEquals(520 to 4, session.calories to session.rating)
        }

    @Test
    fun `photos are stored after the history has committed, one at a time`() =
        runBlocking {
            val bytes = PhotoSource { ByteArray(1).inputStream() }
            val progress = import(
                ImportedHistory(
                    workouts = listOf(workout(day = 1)),
                    photos = listOf(
                        ImportedPhoto(takenAt = 2 * DAY, source = bytes),
                        ImportedPhoto(takenAt = DAY, source = bytes),
                    ),
                ),
            )

            assertEquals(
                listOf(
                    HistoryImportProgress.StoringPhotos(0, 2),
                    HistoryImportProgress.StoringPhotos(1, 2),
                    HistoryImportProgress.StoringPhotos(2, 2),
                ),
                progress.filterIsInstance<HistoryImportProgress.StoringPhotos>(),
            )
            // Outside the transaction: none was stored while it was open.
            assertEquals(0, photos.storedInsideTransaction)
            assertEquals(listOf(DAY, 2 * DAY), photos.stored.map { it.second })
            assertEquals(2, progress.report().photosImported)
        }

    @Test
    fun `a photo that will not store is counted, and the history it came with is kept`() =
        runBlocking {
            photos.failOn = setOf(DAY)
            val report = import(
                ImportedHistory(
                    workouts = listOf(workout(day = 1)),
                    photos = listOf(
                        ImportedPhoto(takenAt = DAY, source = PhotoSource { ByteArray(1).inputStream() }),
                        ImportedPhoto(takenAt = 2 * DAY, source = PhotoSource { ByteArray(1).inputStream() }),
                    ),
                ),
            ).report()

            assertEquals(1, report.photosImported)
            assertEquals(1, report.photosFailed)
            assertEquals(1, sessions.getAll().first().size)
        }

    @Test
    fun `a photo whose store throws anything is counted, not reported as a failed import`() =
        runBlocking {
            photos.crashOn = setOf(DAY)
            val report = import(
                ImportedHistory(
                    workouts = listOf(workout(day = 1)),
                    photos = listOf(
                        ImportedPhoto(takenAt = DAY, source = PhotoSource { ByteArray(1).inputStream() }),
                        ImportedPhoto(takenAt = 2 * DAY, source = PhotoSource { ByteArray(1).inputStream() }),
                    ),
                ),
            ).report()

            assertEquals(1, report.photosImported)
            assertEquals(1, report.photosFailed)
        }

    @Test
    fun `an imported photo keeps the time its source declared, which is how a re-run recognises it`() =
        runBlocking {
            import(
                ImportedHistory(
                    photos = listOf(ImportedPhoto(takenAt = DAY, source = PhotoSource { ByteArray(1).inputStream() })),
                ),
            )

            assertEquals(listOf(false), photos.imageTimePreferred)
        }

    @Test
    fun `a photo already on the device is not stored again`() =
        runBlocking {
            photos.add(PhotoSource { ByteArray(1).inputStream() }, takenAt = DAY, preferImageTime = true)

            val report = import(
                ImportedHistory(
                    photos = listOf(ImportedPhoto(takenAt = DAY, source = PhotoSource { ByteArray(1).inputStream() })),
                ),
            ).report()

            assertEquals(0, report.photosImported)
            assertEquals(1, report.skipped.duplicatePhotos)
        }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `an empty history writes nothing and says so`() =
        runBlocking {
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.NothingToImport),
                import(ImportedHistory()).last(),
            )
            assertEquals(0, store.transactions)
        }

    @Test
    fun `a store that refuses the writes is a storage failure, not a crash`() =
        runBlocking {
            store.failure = IllegalStateException("disk full")

            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.StorageFailure("disk full")),
                import(ImportedHistory(workouts = listOf(workout(day = 1)))).last(),
            )
        }

    @Test
    fun `a replay step that fails is a storage failure, because the store would roll it all back anyway`() =
        runBlocking {
            gamification.streakFailure = IllegalStateException("streak refused")

            val progress = import(ImportedHistory(workouts = listOf(workout(day = 1))))

            val failed = progress.last() as HistoryImportProgress.Failed
            val error = failed.error as HistoryImportError.StorageFailure
            assertTrue(error.reason, "STREAK" in error.reason && "streak refused" in error.reason)
        }

    @Test
    fun `a collector that stops taking updates does not hold the transaction open`() =
        runBlocking {
            // No buffer at all, and a collector that takes the first update and then stops: the
            // screen gone to the background. Every update the replay sends is one nobody takes.
            val stalled = CompletableDeferred<Unit>()
            val seen = mutableListOf<HistoryImportProgress>()
            val collecting = launch {
                useCase()(ImportedHistory(workouts = listOf(workout(day = 1), workout(day = 2))))
                    .buffer(Channel.RENDEZVOUS)
                    .collect {
                        seen += it
                        stalled.await()
                    }
            }

            withTimeout(5_000) {
                while (store.transactions == 0 || store.isOpen) yield()
            }

            assertEquals(2, sessions.getAll().first().size)
            assertEquals(listOf(HistoryImportProgress.Reading), seen)
            stalled.complete(Unit)
            collecting.join()
            assertTrue(seen.last() is HistoryImportProgress.Complete)
        }

    @Test
    fun `the import waits for a backup holding the lock`() =
        runBlocking {
            val history = ImportedHistory(workouts = listOf(workout(day = 1)))
            backupLock.exclusively {
                val pending = async { import(history) }
                repeat(10) { yield() }

                assertTrue(sessions.getAll().first().isEmpty())
                pending
            }.await()

            assertEquals(1, sessions.getAll().first().size)
        }

    private fun parser(read: (ImportSource) -> ParseResult) =
        object : HistoryParser {
            override fun parse(
                source: ImportSource,
                context: ImportContext,
            ): ParseResult = read(source)
        }

    private suspend fun importFile(
        parser: HistoryParser,
        maxSourceBytes: Long = 1_000,
    ): HistoryImportProgress =
        useCase(maxSourceBytes)(
            source = { "a,b,c\n1,2,3\n".byteInputStream() },
            parser = parser,
            context = ImportContext(ZoneOffset.UTC, UnitSystem.METRIC),
        ).toList().last()

    @Test
    fun `a parsed file is imported like a parsed history`() =
        runBlocking {
            val outcome = importFile(
                parser { ParseResult.Parsed(ImportedHistory(workouts = listOf(workout(day = 1)))) },
            )

            assertEquals(1, (outcome as HistoryImportProgress.Complete).report.workoutsImported)
        }

    @Test
    fun `every way a file can fail to parse comes back as its own refusal`() =
        runBlocking {
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.NotRecognised),
                importFile(parser { ParseResult.NotRecognised }),
            )
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.Malformed("row 3")),
                importFile(parser { ParseResult.Malformed("row 3") }),
            )
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.Unreadable("gone")),
                importFile(parser { throw IOException("gone") }),
            )
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.TooLarge(4)),
                importFile(
                    parser { source -> source.open().use { it.readBytes() }.let { ParseResult.NotRecognised } },
                    maxSourceBytes = 4,
                ),
            )
            assertEquals(0, store.transactions)
        }

    private fun finished(day: Long) =
        WorkoutSession(
            completedAt = day * DAY + 60_000,
            isInProgress = false,
            lastActiveAt = day * DAY + 60_000,
            createdAt = day * DAY,
            updatedAt = day * DAY + 60_000,
        )

    /** Seeds [finished]'s session with one bench set, so detection has something to judge. */
    private suspend fun FakeSessionRepository.seedFinished(session: WorkoutSession): Long {
        val id = insert(session)
        val exercise = insertExercises(id, listOf(SessionExerciseDraft(benchPress.id)), session.createdAt).single()
        insertSets(
            listOf(
                WorkoutSet(
                    sessionExerciseId = exercise,
                    setNumber = 1,
                    reps = 5,
                    weight = KG_110,
                    completedAt = session.createdAt + 1,
                    isCompleted = true,
                    createdAt = session.createdAt,
                    updatedAt = session.createdAt,
                ),
            ),
        )
        return id
    }

    private suspend fun FakeSessionRepository.sessionOfSet(setId: Long): Long? =
        getAll().first().firstOrNull { session -> getSetsForSession(session.id).first().any { it.id == setId } }?.id

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 100 * DAY
        const val KG_100 = 100_000_000L
        const val KG_110 = 110_000_000L
    }
}

/** [HistoryImportRepository] with no store behind it: it counts transactions and records the cut-off. */
private class FakeHistoryImportRepository : HistoryImportRepository {

    var transactions = 0
        private set

    /** Whether a transaction is open right now, for a check that something happened outside one. */
    var isOpen = false
        private set

    var forgottenFrom: Long? = null
        private set

    /** Thrown out of [atomically] in place of running the block, as a store refusing it would. */
    var failure: Exception? = null

    override suspend fun <T> atomically(block: suspend () -> T): T {
        transactions++
        failure?.let { throw it }
        isOpen = true
        return try {
            block()
        } finally {
            isOpen = false
        }
    }

    override suspend fun forgetRecordsOfSessionsFinishedFrom(finishedAt: Long): Int {
        forgottenFrom = finishedAt
        return 0
    }
}

/** The body measurement history, in memory, newest first as the contract orders it. */
private class FakeBodyMeasurementRepository : BodyMeasurementRepository {

    private val entries = MutableStateFlow<List<BodyMeasurement>>(emptyList())
    private var nextId = 1L

    override fun getAll(): Flow<List<BodyMeasurement>> =
        entries.map { all -> all.sortedByDescending(BodyMeasurement::createdAt) }

    override fun getLatest(): Flow<BodyMeasurement?> = getAll().map { it.firstOrNull() }

    override suspend fun insert(measurement: BodyMeasurement): Long {
        val id = nextId++
        entries.value += measurement.copy(id = id)
        return id
    }

    override suspend fun update(measurement: BodyMeasurement) {
        entries.value = entries.value.map { if (it.id == measurement.id) measurement else it }
    }

    override suspend fun delete(id: Long) {
        entries.value = entries.value.filterNot { it.id == id }
    }
}

/** The photo store, in memory: it records what it was handed, and when relative to the transaction. */
private class FakeProgressPhotoRepository : ProgressPhotoRepository {

    private val photos = MutableStateFlow<List<ProgressPhoto>>(emptyList())

    /** What was stored, as (id, takenAt). */
    val stored = mutableListOf<Pair<Long, Long>>()

    /** Photos refused as though their bytes were not an image, by their `takenAt`. */
    var failOn: Set<Long> = emptySet()

    /** Photos whose store throws something other than an `IOException`, by their `takenAt`. */
    var crashOn: Set<Long> = emptySet()

    /** Whether each store let the image's own time overrule `takenAt`, in order. */
    val imageTimePreferred = mutableListOf<Boolean>()

    var storedInsideTransaction = 0
        private set

    var transaction: FakeHistoryImportRepository? = null

    override fun getAll(): Flow<List<ProgressPhoto>> = photos

    override suspend fun add(
        source: PhotoSource,
        takenAt: Long,
        preferImageTime: Boolean,
    ): Long {
        if (takenAt in failOn) throw IOException("not an image")
        if (takenAt in crashOn) throw IllegalStateException("store failure")
        if (transaction?.isOpen == true) storedInsideTransaction++
        imageTimePreferred += preferImageTime
        val id = stored.size + 1L
        stored += id to takenAt
        photos.value += ProgressPhoto(id = id, takenAt = takenAt, path = "/photos/$id.jpg", createdAt = takenAt)
        return id
    }

    override suspend fun delete(id: Long) {
        photos.value = photos.value.filterNot { it.id == id }
    }
}
