package com.titan.core.data.db

import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.data.mapper.ExerciseMapper
import com.titan.core.data.mapper.MuscleGroupLinkMapper
import com.titan.core.data.mapper.PersonalRecordMapper
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.TagMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.repository.BackupRepositoryImpl
import com.titan.core.data.repository.CollectedItemRepositoryImpl
import com.titan.core.data.repository.ExerciseRepositoryImpl
import com.titan.core.data.repository.GamificationRepositoryImpl
import com.titan.core.data.repository.PersonalRecordRepositoryImpl
import com.titan.core.data.repository.PetRepositoryImpl
import com.titan.core.data.repository.ProgressionRepositoryImpl
import com.titan.core.data.repository.QuestRepositoryImpl
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.data.repository.SessionStateRepositoryImpl
import com.titan.core.data.repository.UnlockRepositoryImpl
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.Rarity
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.usecase.AchievementTriggerUseCase
import com.titan.core.domain.usecase.AwardXpUseCase
import com.titan.core.domain.usecase.EvaluateUnlocksUseCase
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.GrowPetUseCase
import com.titan.core.domain.usecase.ImportUseCase
import com.titan.core.domain.usecase.LootRollUseCase
import com.titan.core.domain.usecase.PrDetectionUseCase
import com.titan.core.domain.usecase.QuestCompletionUseCase
import com.titan.core.domain.usecase.QuestProgressUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The second acceptance criterion of #37, and the reason the two claim tables are in the export
 * format at all: **re-running the cascade over a restored session must award nothing.**
 *
 * ### What is actually at stake
 *
 * Four of the cascade's six steps are idempotent by construction — PR detection compares
 * strictly better, an already-counted day leaves the streak alone, an unlock and a quest
 * completion are both `INSERT OR IGNORE` over a natural key. Two are not, and cannot be made
 * so: `addXp` banks whatever it is handed, and a drop-table roll is a decision taken from a
 * random source. What fences those two is a row each — `session_xp_claim` and
 * `session_loot_claim` — so an export that dropped them would restore a history every one of
 * whose sessions could be paid a second time and rolled a second time, silently, the next time
 * anything touched it.
 *
 * That is not something the format tests can see: they can assert the rows are in the JSON.
 * Only running the real pipeline over the restored database says whether the fence still
 * *holds*, which is what this does — the same pipeline `SessionCompletionCascadeTest` builds,
 * over the same real repositories, with an export and an import in the middle.
 */
class BackupIdempotencyTest : RoomTestBase() {

    private val backup by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }
    private val preferences = InMemoryUserPreferencesRepository()

    private val export by lazy {
        ExportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            clock = { EXPORTED_AT },
        )
    }

    private val import by lazy {
        ImportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            inProgressSessionRepository = CountingInProgressSessionRepository(),
            templateDraftRepository = CountingTemplateDraftRepository(),
            sessionStateRepository = SessionStateRepositoryImpl(),
            backgroundRestTimer = RecordingBackgroundRestTimer(),
            idleSessionMonitor = RecordingIdleSessionMonitor(),
            reminderScheduler = RecordingReminderScheduler(),
        )
    }

    // The cascade as `GamificationUseCaseModule` composes it, over the real repositories — the
    // wiring `SessionCompletionCascadeTest` sets out, with the roll pinned to *always* drop so
    // that a second roll would be visible as a second row rather than as four-in-five nothing.
    private val sessions by lazy {
        SessionRepositoryImpl(
            database = db,
            sessionMapper = SessionMapper(),
            sessionExerciseMapper = SessionExerciseMapper(),
            setMapper = WorkoutSetMapper(),
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
            sessionPetClaimDao = db.sessionPetClaimDao(),
        )
    }

    private val collectedItems by lazy { CollectedItemRepositoryImpl(collectedItemDao = db.collectedItemDao()) }

    private val pets by lazy { PetRepositoryImpl(db.petDao()) }

    private val awardXp by lazy { AwardXpUseCase(gamificationRepository = gamification) }

    private val pipeline by lazy {
        SessionCompletionPipeline(
            detectPersonalRecords = PrDetectionUseCase(
                sessionRepository = sessions,
                // The real catalogue, so a set is judged by its exercise's own logging mode.
                exerciseRepository = ExerciseRepositoryImpl(
                    exerciseDao = db.exerciseDao(),
                    muscleGroupDao = db.muscleGroupDao(),
                    exerciseMapper = ExerciseMapper(),
                    tagMapper = TagMapper(),
                    muscleGroupLinkMapper = MuscleGroupLinkMapper(),
                ),
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
                    questRepository = QuestRepositoryImpl(questCompletionDao = db.questCompletionDao()),
                ),
                questRepository = QuestRepositoryImpl(questCompletionDao = db.questCompletionDao()),
                awardXp = awardXp,
            ),
            rollLoot = LootRollUseCase(
                dropTable = DropTableEvaluator(RandomSource { 0 }, listOf(ONLY_DROP)),
                collectedItemRepository = collectedItems,
                gamificationRepository = gamification,
            ),
            growPet = GrowPetUseCase(
                sessionRepository = sessions,
                exerciseRepository = ExerciseRepositoryImpl(
                    exerciseDao = db.exerciseDao(),
                    muscleGroupDao = db.muscleGroupDao(),
                    exerciseMapper = ExerciseMapper(),
                    tagMapper = TagMapper(),
                    muscleGroupLinkMapper = MuscleGroupLinkMapper(),
                ),
                petRepository = pets,
                gamificationRepository = gamification,
            ),
            evaluateUnlocks = EvaluateUnlocksUseCase(
                petRepository = pets,
                unlockRepository = UnlockRepositoryImpl(db.petUnlockDao()),
            ),
            sessionRepository = sessions,
            personalRecordRepository = records,
            gamificationRepository = gamification,
            userPreferencesRepository = preferences,
        )
    }

    /**
     * The criterion itself. Run the cascade, export, wipe, import, run the cascade again over
     * the same session: no XP, no second drop, no second unlock.
     */
    @Test
    fun `re-running the cascade over a restored session awards nothing`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            val first = pipeline(sessionId, FINISHED_AT)
            val xpAfterFirstRun = db.userProgressDao().observe().first()?.totalXp
            assertTrue("the fixture stopped paying anything, so it stopped testing this", (xpAfterFirstRun ?: 0) > 0)

            restoreThroughAnExport()
            val second = pipeline(sessionId, FINISHED_AT)

            assertEquals(0, second.xpAwarded)
            assertNull("a restored session must not roll a second collectible", second.lootDropped)
            assertEquals(xpAfterFirstRun, db.userProgressDao().observe().first()?.totalXp)
            assertEquals(first.recordsBroken.size, db.personalRecordDao().count())
        }

    /** The fence is a row, and the row is what has to have come back. */
    @Test
    fun `the claim rows are what the restored database is fenced by`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            pipeline(sessionId, FINISHED_AT)

            restoreThroughAnExport()

            assertEquals(1, db.dumpTable(BackupTable.SESSION_XP_CLAIM).size)
            assertEquals(1, db.dumpTable(BackupTable.SESSION_LOOT_CLAIM).size)
            assertEquals(sessionId.toString(), db.dumpTable(BackupTable.SESSION_XP_CLAIM).single()["session_id"])
        }

    /**
     * The inverse, and the reason the test above is not vacuous: a restore that *dropped* the
     * claims would pay twice. Nothing in the app can do that — the export carries them — so it
     * is forced here by deleting them after the restore.
     */
    @Test
    fun `a restore that lost the claims would pay the session twice`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            pipeline(sessionId, FINISHED_AT)
            restoreThroughAnExport()

            db.backupDao().deleteXpClaims()
            db.backupDao().deleteLootClaims()
            val second = pipeline(sessionId, FINISHED_AT)

            assertTrue("without the claims the cascade pays again — which is what they prevent", second.xpAwarded > 0)
        }

    /** One drop before the round trip, and still one after: the inventory is not re-rolled. */
    @Test
    fun `the collectible the session dropped is not dropped again`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            pipeline(sessionId, FINISHED_AT)
            assertEquals(1, db.dumpTable(BackupTable.COLLECTED_ITEM).size)

            restoreThroughAnExport()
            pipeline(sessionId, FINISHED_AT)

            assertEquals(1, db.dumpTable(BackupTable.COLLECTED_ITEM).size)
        }

    /** The pet's fence is the third claim, and it has to travel with the other two. */
    @Test
    fun `a restored session grows the pet nothing, and losing its claim would grow it again`() =
        runBlocking {
            hatchAPet()
            val sessionId = seedFinishedSession()
            val first = pipeline(sessionId, FINISHED_AT)
            val grown = pets.observeActive().first()!!.xp
            assertTrue(
                "the fixture stopped growing the pet, so it stopped testing this",
                (first.pet?.xpGained ?: 0) > 0,
            )

            restoreThroughAnExport()
            val second = pipeline(sessionId, FINISHED_AT)

            assertNull("a restored session must not grow the pet again", second.pet)
            assertEquals(grown, pets.observeActive().first()!!.xp)
            assertEquals(1, db.dumpTable(BackupTable.SESSION_PET_CLAIM).size)

            // The control: without the claim, the same run grows it a second time.
            db.backupDao().deletePetClaims()
            val third = pipeline(sessionId, FINISHED_AT)
            assertTrue("without the claim the pet grows again — which is what it prevents", third.pet != null)
        }

    /** An egg, hatched straight through the store: what the screen would do. */
    private suspend fun hatchAPet() {
        val egg = db.collectedItemDao().insert(
            CollectedItemEntity(collectibleId = "pet_egg", source = "session_finished", acquiredAt = 0),
        )
        pets.hatch(egg, "iron_cub", java.time.Instant.EPOCH)
    }

    /** Exports the database, empties it, and imports the file back — a device move, in one call. */
    private suspend fun restoreThroughAnExport() {
        val text = StringBuilder()
        export(ExportSink { text.append(it) }, APP_VERSION).toList()
        import(ImportSource { text.toString().byteInputStream() }).toList()
    }

    /**
     * A finished session with two exercises and three completed sets each — the shape
     * `SessionCompletionCascadeTest` uses, trimmed to what this suite reads: enough records to
     * break, enough sets to be paid for, and every timestamp inside the session's own hour so
     * the quest periods line up.
     */
    private suspend fun seedFinishedSession(): Long {
        val sessionId = db.workoutSessionDao().insert(
            session(
                isInProgress = false,
                lastActiveAt = FINISHED_AT.toEpochMilli(),
                createdAt = FINISHED_AT.toEpochMilli() - HOUR_MILLIS,
                completedAt = FINISHED_AT.toEpochMilli(),
            ),
        )
        listOf("Bench Press", "Back Squat").forEachIndexed { position, name ->
            val exerciseId = db.exerciseDao().insert(exercise(name = name))
            val sessionExerciseId = db.sessionExerciseDao().insert(
                sessionExercise(sessionId = sessionId, exerciseId = exerciseId, position = position),
            )
            SETS.forEachIndexed { index, set ->
                db.workoutSetDao().insert(
                    workoutSet(
                        sessionExerciseId = sessionExerciseId,
                        setNumber = index + 1,
                        isCompleted = true,
                        completedAt = FINISHED_AT.toEpochMilli() - (SETS.size - index) * SET_INTERVAL_MILLIS,
                        reps = set.first,
                        weight = set.second,
                    ),
                )
            }
        }
        return sessionId
    }

    private companion object {

        /** A Wednesday evening, as in `SessionCompletionCascadeTest` — the periods differ from the day. */
        val FINISHED_AT: Instant = Instant.parse("2026-03-04T19:00:00Z")

        const val EXPORTED_AT = 1_788_686_970_491L
        const val APP_VERSION = "1.0-debug"
        const val HOUR_MILLIS = 3_600_000L
        const val SET_INTERVAL_MILLIS = 90_000L
        const val MILLIGRAMS_PER_KILOGRAM = 1_000_000L

        /** Reps and weight, three working sets peaking on different columns. */
        val SETS = listOf(
            8 to 60 * MILLIGRAMS_PER_KILOGRAM,
            6 to 80 * MILLIGRAMS_PER_KILOGRAM,
            10 to 50 * MILLIGRAMS_PER_KILOGRAM,
        )

        /**
         * The one entry the roll can hand out, so a second roll would be a second row rather
         * than the four-in-five nothing the shipped rates give.
         */
        val ONLY_DROP = Collectible(
            id = "backup_test_drop",
            rarity = Rarity.COMMON,
            source = DropSource.SESSION_FINISHED,
        )
    }
}
