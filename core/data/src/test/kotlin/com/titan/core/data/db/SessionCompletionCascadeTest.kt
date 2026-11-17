package com.titan.core.data.db

import com.titan.core.data.mapper.ExerciseMapper
import com.titan.core.data.mapper.MuscleGroupLinkMapper
import com.titan.core.data.mapper.PersonalRecordMapper
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.TagMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.repository.CollectedItemRepositoryImpl
import com.titan.core.data.repository.ExerciseRepositoryImpl
import com.titan.core.data.repository.GamificationRepositoryImpl
import com.titan.core.data.repository.PersonalRecordRepositoryImpl
import com.titan.core.data.repository.PetRepositoryImpl
import com.titan.core.data.repository.ProgressionRepositoryImpl
import com.titan.core.data.repository.QuestRepositoryImpl
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.data.repository.UnlockRepositoryImpl
import com.titan.core.domain.gamification.Achievements
import com.titan.core.domain.gamification.DropRates
import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.gamification.Quests
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.Rarity
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.usecase.AchievementTriggerUseCase
import com.titan.core.domain.usecase.AwardXpUseCase
import com.titan.core.domain.usecase.EvaluateUnlocksUseCase
import com.titan.core.domain.usecase.GrowPetUseCase
import com.titan.core.domain.usecase.LootRollUseCase
import com.titan.core.domain.usecase.PrDetectionUseCase
import com.titan.core.domain.usecase.QuestCompletionUseCase
import com.titan.core.domain.usecase.QuestProgressUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration

/**
 * The whole post-session cascade, over the database it actually writes to.
 *
 * `SessionCompletionPipelineTest` in `:core:domain` already pins the cascade's three contracts
 * against in-memory fakes, and those fakes are honest: each enforces the part of its interface
 * the cascade leans on. What they cannot enforce is that the *stored* schema does — that an
 * unlock is unrepeatable because a unique index says so rather than because a `Set` did, that
 * a session's XP claim is a row, that a record insert compares against what SQL selected back,
 * and that a quest's period boundary lands on rows a `WHERE` clause actually matched. Those are
 * the joins between four components (#30, #33, #34, #35) that each claim idempotency
 * individually, and #36 asks for them to be claimed together.
 *
 * So this builds the real pipeline over the real repository implementations over a real
 * (Robolectric) SQLite, drives it through [SessionCompletionPipeline] rather than by calling
 * the five use cases by hand, and reads the end state back through [UserProgressDao.observe]
 * and [AchievementDao.getAll] — the same flows the screens subscribe to.
 *
 * ### Everything asserted is computed from the catalogues, not copied out of a run
 *
 * The expected totals below are spelled as counts times named constants — six sets, eight
 * records, two milestones, two quests — so a retune of [XpCalculator]'s starting defaults moves
 * the expectation with the economy, while a change to *how many* of anything the cascade
 * produces still fails. A hand-copied `1035` would have inverted both.
 *
 * The one thing left as a literal is [FINISHED_AT], because the periods are the point: it is a
 * Wednesday, so the week's Monday and the month's first are two different days from the
 * session's own, and a quest paid out of the wrong period would show.
 */
class SessionCompletionCascadeTest : RoomTestBase() {

    // The cascade as `GamificationUseCaseModule` composes it, minus Hilt. Constructed by hand
    // rather than through a test component so the wiring under test is visible in the file
    // that asserts on it — and so the catalogues stay at their production defaults, which is
    // what makes the hand-computed figures below the app's own figures.
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

    private val progression by lazy {
        ProgressionRepositoryImpl(
            setDao = db.workoutSetDao(),
            sessionDao = db.workoutSessionDao(),
            setMapper = WorkoutSetMapper(),
        )
    }

    private val quests by lazy { QuestRepositoryImpl(questCompletionDao = db.questCompletionDao()) }

    private val collectedItems by lazy {
        CollectedItemRepositoryImpl(collectedItemDao = db.collectedItemDao())
    }

    /**
     * The roll, wired to a table that never pays out.
     *
     * What this suite drives is the cascade over a real database, and a *random* step would make
     * every assertion below depend on a die roll. Pinned to nothing-dropped rather than to a
     * guaranteed drop because that is the common answer on the shipped rates anyway — four
     * triggers in five pay nothing — and because the loot step's own behaviour, including its
     * claim, is `LootRollUseCaseTest`'s subject rather than this one's.
     */
    private val rollLoot by lazy {
        LootRollUseCase(
            dropTable = DropTableEvaluator(RandomSource { DropRates.TOTAL_WEIGHT - 1 }, emptyList()),
            collectedItemRepository = collectedItems,
            gamificationRepository = gamification,
        )
    }

    private val pets by lazy { PetRepositoryImpl(db.petDao()) }

    private val awardXp by lazy { AwardXpUseCase(gamificationRepository = gamification) }

    private val detectPersonalRecords by lazy {
        PrDetectionUseCase(
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
            userPreferencesRepository = CascadePreferences,
        )
    }

    private val pipeline by lazy { pipelineWith(rollLoot) }

    /**
     * The cascade as `GamificationUseCaseModule` composes it, with the roll left open.
     *
     * Every step here is the real implementation over the real database; only the loot step is a
     * parameter, because it is the one whose answer a case may need to force. [pipeline] is this
     * with [rollLoot]'s never-drops table, which is what all but the loot cases want.
     */
    private fun pipelineWith(rollLoot: LootRollUseCase) =
        SessionCompletionPipeline(
            detectPersonalRecords = detectPersonalRecords,
            awardXp = awardXp,
            triggerAchievements = AchievementTriggerUseCase(
                gamificationRepository = gamification,
                awardXp = awardXp,
            ),
            completeQuests = QuestCompletionUseCase(
                questProgress = QuestProgressUseCase(
                    progressionRepository = progression,
                    personalRecordRepository = records,
                    questRepository = quests,
                ),
                questRepository = quests,
                awardXp = awardXp,
            ),
            rollLoot = rollLoot,
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
                gamificationRepository = gamification,
                sessionRepository = sessions,
                sparTally = { 0 },
                unlockRepository = UnlockRepositoryImpl(db.petUnlockDao()),
            ),
            sessionRepository = sessions,
            personalRecordRepository = records,
            gamificationRepository = gamification,
            userPreferencesRepository = CascadePreferences,
        )

    /**
     * The same roll, over a table that always pays out — and always the same thing.
     *
     * [rollLoot] is pinned to nothing-dropped so no assertion elsewhere depends on a die roll,
     * which leaves `collected_item` the one write in this cascade that nothing drives over a real
     * database: `LootRollUseCaseTest` owns the step's logic, but it owns it against
     * `FakeCollectedItemRepository`, and a fake is exactly what this suite exists to not settle
     * for. So the two loot cases below take this instead.
     *
     * Its own single-entry catalogue rather than [com.titan.core.domain.gamification.Collectibles.ALL],
     * the call `DropTableEvaluatorTest` makes: what drops is not the subject here, so a case that
     * named a shipped collectible would fail the day somebody stocked another common.
     */
    private val alwaysDrops by lazy {
        LootRollUseCase(
            dropTable = DropTableEvaluator(RandomSource { 0 }, listOf(ONLY_DROP)),
            collectedItemRepository = collectedItems,
            gamificationRepository = gamification,
        )
    }

    // ------------------------------------------------------------- the full cascade

    @Test
    fun `a first session pays for itself, its records, its milestones and its quests`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            pipeline(sessionId, FINISHED_AT)

            val stored = db.userProgressDao().observe().first()
            assertEquals(CLEAN_RUN_XP, stored?.totalXp)
            assertEquals(XpCalculator.levelForTotalXp(CLEAN_RUN_XP), stored?.currentLevel)
        }

    @Test
    fun `the level the stored total buys is a level the user actually crossed`() =
        runBlocking {
            // The fixture is built to cross several: a first session unlocks two milestones and
            // clears two record quests, which its first-ever records still count towards, and
            // the first levels are cheap. A cascade that banked the total without recomputing
            // the level would read as level 1.
            val sessionId = seedFinishedSession()

            pipeline(sessionId, FINISHED_AT)

            val stored = db.userProgressDao().observe().first()
            assertTrue(
                "the fixture no longer crosses a level boundary, so it stopped testing the crossing",
                (stored?.currentLevel ?: 0) > XpCalculator.FIRST_LEVEL,
            )
            // The one figure written out by hand rather than derived, as a check on the
            // derivation: 50 for the session, 30 for six sets, nothing for eight first-ever
            // records, 200 for two milestones and 380 for two quests is 660, which is past the
            // 480 that level 5 costs and short of the 671 that level 6 does.
            assertEquals(EXPECTED_LEVEL, stored?.currentLevel)
        }

    @Test
    fun `every record the session broke is on file, one per type it has the columns for`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            // Two exercises, four types each: the sets carry a weight and a rep count and
            // nothing else, so distance and pace have no candidate to offer.
            assertEquals(RECORDS_BROKEN, db.personalRecordDao().count())
            assertEquals(
                setOf(PrType.MAX_WEIGHT, PrType.MAX_REPS, PrType.MAX_VOLUME, PrType.ESTIMATED_1RM),
                progression.recordsBroken.map { it.type }.toSet(),
            )
        }

    @Test
    fun `the milestones the session earned are unlocked, and reported in catalogue order`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            // `first_record` is only reachable because detection wrote before the catalogue was
            // evaluated: the metric is a count of rows, and there were none before this session.
            assertEquals(
                listOf(Achievements.FIRST_SESSION.id, Achievements.FIRST_RECORD.id),
                progression.achievementsUnlocked.map { it.achievement.id },
            )
            assertEquals(UNLOCKED_AS_STORED, storedAchievementIds())
        }

    @Test
    fun `an unlock is stamped with the day the workout happened, not the day it was processed`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            pipeline(sessionId, FINISHED_AT)

            assertEquals(
                listOf(FINISHED_AT.toEpochMilli(), FINISHED_AT.toEpochMilli()),
                db.achievementDao().getAll().first().map { it.unlockedAt },
            )
        }

    @Test
    fun `the streak opens at one, in the week the session finished`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            pipeline(sessionId, FINISHED_AT)

            val stored = db.userProgressDao().observe().first()
            assertEquals(1, stored?.currentStreak)
            assertEquals(1, stored?.longestStreak)
            assertEquals(FINISHED_AT.toEpochMilli(), stored?.lastCountedAt)
        }

    @Test
    fun `the quests completed are the ones the week and the month actually met`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            // Eight records clears both record quests. The session and volume quests are not
            // met by one workout of this size, which is what keeps the pair above meaningful:
            // a board that completed everything would assert nothing about the targets.
            assertEquals(
                listOf(Quests.WEEKLY_RECORD.id, Quests.MONTHLY_RECORDS.id),
                progression.questsCompleted.map { it.quest.id },
            )
        }

    @Test
    fun `a quest is banked into the period the session was trained in`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            pipeline(sessionId, FINISHED_AT)

            // The Monday that opens the session's week, not the session's own Wednesday: a
            // completion filed against the wrong day would be re-payable the moment the board
            // is read back for the period it belongs to.
            assertEquals(setOf(Quests.WEEKLY_RECORD.id), quests.observeCompletedIds(WEEK_START).first())
            assertEquals(setOf(Quests.MONTHLY_RECORDS.id), quests.observeCompletedIds(MONTH_START).first())
        }

    @Test
    fun `a drop the cascade rolled is on file, dated to the workout`() =
        runBlocking {
            val sessionId = seedFinishedSession()

            val progression = pipelineWith(alwaysDrops)(sessionId, FINISHED_AT)

            // Reported and stored, and the row is read back through the repository a screen
            // subscribes to rather than off the DAO — so the mapper and the column types are in
            // the round trip too.
            assertEquals(ONLY_DROP, progression.lootDropped)
            val stored = collectedItems.observeRecent(limit = 1).first().single()
            assertEquals(ONLY_DROP.id, stored.collectibleId)
            assertEquals(DropSource.SESSION_FINISHED.key, stored.source)
            // The workout's own moment, not the roll's: `acquired_at` is stored as millis, so a
            // stamp taken from a clock here would be off by however long the cascade ran.
            assertEquals(FINISHED_AT, stored.acquiredAt)
        }

    // --------------------------------------------------------- the re-entry contract

    @Test
    fun `finishing the same session twice pays for it once`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            pipeline(sessionId, FINISHED_AT)

            val second = pipeline(sessionId, FINISHED_AT)

            assertEquals(0, second.xpAwarded)
            assertEquals(CLEAN_RUN_XP, db.userProgressDao().observe().first()?.totalXp)
        }

    @Test
    fun `finishing the same session twice breaks no second record`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            pipeline(sessionId, FINISHED_AT)

            val second = pipeline(sessionId, FINISHED_AT)

            assertTrue("a re-entry beat a record it had already written", second.recordsBroken.isEmpty())
            assertEquals(RECORDS_BROKEN, db.personalRecordDao().count())
        }

    @Test
    fun `finishing the same session twice does not advance the streak`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            pipeline(sessionId, FINISHED_AT)

            pipeline(sessionId, FINISHED_AT)

            val stored = db.userProgressDao().observe().first()
            assertEquals(1, stored?.currentStreak)
            assertEquals(1, stored?.longestStreak)
        }

    @Test
    fun `finishing the same session twice duplicates no unlock and no completion`() =
        runBlocking {
            val sessionId = seedFinishedSession()
            pipeline(sessionId, FINISHED_AT)

            val second = pipeline(sessionId, FINISHED_AT)

            assertTrue("a milestone unlocked twice", second.achievementsUnlocked.isEmpty())
            assertTrue("a quest completed twice", second.questsCompleted.isEmpty())
            assertEquals(UNLOCKED_AS_STORED, storedAchievementIds())
            assertEquals(setOf(Quests.WEEKLY_RECORD.id), quests.observeCompletedIds(WEEK_START).first())
        }

    @Test
    fun `finishing the same session twice rolls no second collectible`() =
        runBlocking {
            // The step whose repeat nothing downstream could catch: `collected_item` carries no
            // unique index, because the same collectible dropping again is a normal thing to own.
            // So `session_loot_claim` is the only thing between a crash-recovery boot and a free
            // pull — and here it is a row the schema holds rather than a `Set` a fake holds.
            val sessionId = seedFinishedSession()
            pipelineWith(alwaysDrops)(sessionId, FINISHED_AT)

            val second = pipelineWith(alwaysDrops)(sessionId, FINISHED_AT)

            assertNull("a second collectible was rolled for one session", second.lootDropped)
            assertEquals(1, collectedItems.observeRecent(limit = 2).first().size)
        }

    // ------------------------------------------------------------- a torn cascade

    @Test
    fun `a cascade torn between detection and the awards finishes what had not run`() =
        runBlocking {
            // The state a process death after PR detection leaves on the disk: the record rows
            // are committed and nothing has been paid. Re-entering has to complete the cascade
            // without repeating the part that already ran.
            val sessionId = seedFinishedSession()
            detectPersonalRecords(sessionId)

            pipeline(sessionId, FINISHED_AT)

            assertEquals(RECORDS_BROKEN, db.personalRecordDao().count())
            assertEquals(1, db.userProgressDao().observe().first()?.currentStreak)
            // The records survive the tear and still count towards `first_record`, because the
            // catalogue is evaluated against what the store holds rather than against what this
            // run happened to do.
            assertEquals(UNLOCKED_AS_STORED, storedAchievementIds())
            assertEquals(
                setOf(Quests.WEEKLY_RECORD.id, Quests.MONTHLY_RECORDS.id),
                quests.observeCompletedIds(WEEK_START).first() + quests.observeCompletedIds(MONTH_START).first(),
            )
        }

    @Test
    fun `a torn cascade over a first session loses nothing, its records being unpaid baselines`() =
        runBlocking {
            // The deliberate half of the pipeline's trade is that the re-entry's detection beats
            // the rows the first pass wrote, so it emits no `PrBroken` to pay for. A first
            // session's records are baselines, which pay nothing in either case, so what the
            // tear would have cost is zero and nothing downstream invents a loss. Under-paying a
            // torn session that did break an earlier figure is the price of not re-paying every
            // session on every crash-recovery boot; see `GamificationRepository.claimSessionXp`.
            val sessionId = seedFinishedSession()
            detectPersonalRecords(sessionId)

            pipeline(sessionId, FINISHED_AT)

            val stored = db.userProgressDao().observe().first()
            assertEquals(CLEAN_RUN_XP, stored?.totalXp)
            assertEquals(XpCalculator.levelForTotalXp(stored!!.totalXp), stored.currentLevel)
        }

    // -------------------------------------------------------------------- the fixture

    /**
     * The unlocks as the screens read them back — [AchievementDao.getAll]'s own order, which is
     * not the catalogue's.
     *
     * One finished session dates every milestone it reached from the session's own timestamp,
     * so `unlocked_at` ties and the query's `achievement_id` tie-break decides. That is the
     * DAO's documented contract and the thing worth pinning here: the cascade's *reporting*
     * order is a separate claim, asserted on the [com.titan.core.domain.model.SessionProgression]
     * it hands back.
     */
    private suspend fun storedAchievementIds(): List<String> =
        db.achievementDao().getAll().first().map { it.achievementId }

    /**
     * One finished session: two exercises, three completed sets each, weights and reps that
     * peak on different sets.
     *
     * The peaks are deliberately split — the heaviest set is not the longest — so a detection
     * that reported one set for every record type would fail rather than coincide. Written
     * through the DAOs rather than through [WorkoutHistoryFixture], because what the figures
     * below are computed from is these exact six sets.
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
                        // Inside the session's own hour, so every record's `achievedAt` falls
                        // in the week and the month the quests are scored against.
                        completedAt = FINISHED_AT.toEpochMilli() - (SETS.size - index) * SET_INTERVAL_MILLIS,
                        reps = set.reps,
                        weight = set.weight,
                    ),
                )
            }
        }
        return sessionId
    }

    /** One set of the fixture: the two columns that decide every record type it can break. */
    private data class SetSpec(
        val reps: Int,
        val weight: Long,
    )

    private companion object {

        /**
         * The one entry [alwaysDrops] can hand out.
         *
         * Its rarity is the commonest band so a roll of zero reaches it, and its id is this
         * file's own rather than a shipped one — what lands is not the subject, that the row
         * survives the round trip is.
         */
        val ONLY_DROP = Collectible(
            id = "cascade_test_drop",
            rarity = Rarity.COMMON,
            source = DropSource.SESSION_FINISHED,
        )

        /**
         * A Wednesday evening.
         *
         * The day matters: the week it belongs to opens on Monday 2026-03-02 and the month on
         * Sunday 2026-03-01, so all three dates are different and a quest banked against the
         * session's own day rather than its period's start would fail
         * `a quest is banked into the period the session was trained in`.
         */
        val FINISHED_AT: Instant = Instant.parse("2026-03-04T19:00:00Z")

        /** The Monday that opens [FINISHED_AT]'s week. */
        val WEEK_START: LocalDate = LocalDate.parse("2026-03-02")

        /** The first of [FINISHED_AT]'s month. */
        val MONTH_START: LocalDate = LocalDate.parse("2026-03-01")

        const val HOUR_MILLIS = 3_600_000L

        /** Ninety seconds between sets, so the session is a contiguous block of work. */
        const val SET_INTERVAL_MILLIS = 90_000L

        const val MILLIGRAMS_PER_KILOGRAM = 1_000_000L

        /**
         * Three working sets, peaking on different columns: the heaviest is the middle one and
         * the longest is the last.
         */
        val SETS = listOf(
            SetSpec(reps = 8, weight = 60 * MILLIGRAMS_PER_KILOGRAM),
            SetSpec(reps = 6, weight = 80 * MILLIGRAMS_PER_KILOGRAM),
            SetSpec(reps = 10, weight = 50 * MILLIGRAMS_PER_KILOGRAM),
        )

        const val EXERCISES = 2

        /** Weight, reps, volume and estimated 1RM — see [RECORDS_BROKEN]. */
        const val RECORD_TYPES_PER_EXERCISE = 4

        /** Six, and every one of them a working set that pays — there are no warm-ups. */
        val COMPLETED_SETS = EXERCISES * SETS.size

        /**
         * Four types per exercise: weight, reps, volume and estimated 1RM. Distance and pace
         * need columns [SETS] does not carry, so they offer no candidate at all.
         */
        const val RECORDS_BROKEN = EXERCISES * RECORD_TYPES_PER_EXERCISE

        /** `first_session` and `first_record`; no streak milestone is reachable on day one. */
        const val MILESTONES = 2

        /**
         * The level [CLEAN_RUN_XP] buys, written out rather than derived.
         *
         * The only figure in this file that does not come off a catalogue, and deliberately:
         * everything else is a count times a constant, which would still agree with a level
         * computed the wrong way. A retune of the economy moves this by hand, on purpose.
         */
        const val EXPECTED_LEVEL = 5

        /** Those two as the database returns them — see [storedAchievementIds]. */
        val UNLOCKED_AS_STORED = listOf(Achievements.FIRST_RECORD.id, Achievements.FIRST_SESSION.id)

        /**
         * What a clean single run leaves on the progress row.
         *
         * Spelled out rather than summed from the result, so this is an expectation and not an
         * echo: the session, its sets, its milestones, and the two record quests eight records
         * clear in one workout. The records themselves are the first of their kind, so they are
         * the one thing that session is not paid for.
         */
        val CLEAN_RUN_XP: Long =
            XpCalculator.XP_PER_SESSION.toLong() +
                XpCalculator.XP_PER_SET * COMPLETED_SETS +
                XpCalculator.XP_PER_ACHIEVEMENT * MILESTONES +
                Quests.WEEKLY_RECORD.xpReward +
                Quests.MONTHLY_RECORDS.xpReward
    }
}

/**
 * The shipped defaults with a weekly goal of one, read-only.
 *
 * The cascade reads exactly two preferences — [PrDetectionUseCase] asks for the 1RM formula, the
 * streak step for the weekly goal — and writes none, so a real DataStore here would be a file, a
 * scope and a shutdown in service of two constants. The goal is one so that the single session
 * each case finishes is a week met, and the streak has something to show for it. Every setter
 * throws rather than returning quietly: a cascade that started writing preferences should fail
 * this suite loudly rather than have the write vanish.
 */
private object CascadePreferences : UserPreferencesRepository {

    override fun observe(): Flow<UserPreferences> = flowOf(UserPreferences(weeklyWorkoutGoal = 1))

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = readOnly()

    override suspend fun setTheme(theme: ThemePreference) = readOnly()

    override suspend fun setDefaultRestDuration(duration: Duration) = readOnly()

    override suspend fun setBarWeight(barWeight: Long) = readOnly()

    override suspend fun setPlateInventory(inventory: PlateInventory?) = readOnly()

    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) = readOnly()

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = readOnly()

    override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) = readOnly()

    override suspend fun setCustomRepRange(range: RepRange) = readOnly()

    override suspend fun setCustomIncrement(milligrams: Long?) = readOnly()

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = readOnly()

    override suspend fun setWarmupSuggestions(enabled: Boolean) = readOnly()

    override suspend fun setAutoOpenRestTimer(enabled: Boolean) = readOnly()

    override suspend fun setWeeklyWorkoutGoal(goal: Int) = readOnly()

    override suspend fun clear() = readOnly()

    override suspend fun setOnboardingCompleted(completed: Boolean) = readOnly()

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = readOnly()

    override suspend fun setNotificationPermissionRequested() = readOnly()

    override suspend fun setReminderDay(
        day: DayOfWeek,
        enabled: Boolean,
    ) = readOnly()

    override suspend fun setReminderTime(time: LocalTime) = readOnly()

    private fun readOnly(): Nothing = throw UnsupportedOperationException("the cascade writes no preferences")
}
