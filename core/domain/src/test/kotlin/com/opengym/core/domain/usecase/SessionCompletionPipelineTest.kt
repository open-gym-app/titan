package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.gamification.XpCalculator
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SessionProgressionStep
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.StreakResult
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.model.XpEvent
import com.opengym.core.domain.repository.GamificationRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The cascade's three contracts — the order, the failure contract and the re-entry contract —
 * against the real use cases over in-memory stores.
 *
 * Nothing inside `:core:domain` is mocked: the ordering constraints under test are constraints
 * between *what the steps wrote*, and a stubbed detection or a stubbed award would let an
 * ordering that never touched the store pass. The fakes are the repository interfaces the
 * module declares and does not implement, and each enforces the part of its contract the
 * cascade leans on — see [FakeGamificationRepository] in particular, whose streak write is
 * where the same-day rule actually holds.
 */
class SessionCompletionPipelineTest {

    private val rig = Rig()

    private val sessions get() = rig.sessions
    private val records get() = rig.records
    private val gamification get() = rig.gamification
    private val pipeline get() = rig.pipeline

    // ---------------------------------------------------------------- the order

    @Test
    fun `the steps run in the order records, xp, streak, achievements`() =
        runTest {
            val journal = mutableListOf<String>()
            val journalled = Rig(journal)
            val sessionId = journalled.finishSession()

            journalled.pipeline(sessionId, FINISHED_AT)

            // Every store is touched more than once — nine awards, four records, two unlocks
            // and the achievement XP behind them — so it is the order they are *first* reached
            // in that is the ordering constraint.
            assertEquals(listOf("record", "xp", "streak", "achievement"), journal.distinct())
        }

    @Test
    fun `each record broken is paid for, which it could not be if xp ran first`() =
        runTest {
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(BROKEN_TYPES, progression.recordsBroken.map(PersonalRecord::type).toSet())
            assertEquals(
                progression.recordsBroken.map { XpEvent.PrBroken(it.type) }.toSet(),
                progression.xp.map { it.event }.filterIsInstance<XpEvent.PrBroken>().toSet(),
            )
        }

    @Test
    fun `the first-record achievement unlocks on the session that broke the record`() =
        runTest {
            // Only reachable if detection wrote before the catalogue was evaluated: the metric
            // is a lifetime count of rows, and there were none before this session.
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(
                listOf(Achievements.FIRST_SESSION.id, Achievements.FIRST_RECORD.id),
                progression.achievementsUnlocked.map { it.achievement.id },
            )
        }

    @Test
    fun `a streak achievement is evaluated against the streak this session produced`() =
        runTest {
            // Six days in, so the session under test is the seventh and the milestone is only
            // reachable by a catalogue evaluated after the streak step, not before it.
            gamification.seedStreak(days = 6, lastCountedAt = FINISHED_AT.minusSeconds(DAY_SECONDS))
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(7, progression.streak?.days)
            assertTrue(
                progression.achievementsUnlocked.map { it.achievement.id }
                    .contains(Achievements.SEVEN_DAY_STREAK.id),
            )
        }

    // ---------------------------------------------------- the value it hands back

    @Test
    fun `the result carries every award, the session's and its achievements'`() =
        runTest {
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            // 25 for the session, 5 per completed set including the warm-up, 50 per record
            // broken, and 100 per achievement unlocked.
            val expected = XpCalculator.XP_PER_SESSION +
                XpCalculator.XP_PER_SET * COMPLETED_SETS +
                XpCalculator.XP_PER_PR * BROKEN_TYPES.size +
                XpCalculator.XP_PER_ACHIEVEMENT * 2
            assertEquals(expected, progression.xpAwarded)
            assertEquals(expected.toLong(), progression.totalXp)
            assertEquals(gamification.totalXp, progression.totalXp)
        }

    @Test
    fun `the result reports the level-ups the awards observed, without a second read`() =
        runTest {
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(XpCalculator.FIRST_LEVEL, progression.levelBefore)
            assertEquals(XpCalculator.levelForTotalXp(gamification.totalXp), progression.levelAfter)
            assertTrue(progression.leveledUp)
            assertTrue(progression.isComplete)
        }

    @Test
    fun `an achievement's award appears in the breakdown and beside what earned it`() =
        runTest {
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            val unlockEvents = progression.xp.map { it.event }.filterIsInstance<XpEvent.AchievementUnlocked>()
            assertEquals(
                progression.achievementsUnlocked.map { it.achievement.id },
                unlockEvents.map { it.achievementId },
            )
            assertEquals(
                progression.achievementsUnlocked.map { it.xp },
                progression.xp.filter { it.event is XpEvent.AchievementUnlocked }.map { it.award },
            )
        }

    @Test
    fun `a session that moved nothing reports empty rather than failing`() =
        runTest {
            val sessionId = rig.finishSession(workingSets = 0, warmUpSets = 0)

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(sessionId, progression.sessionId)
            assertTrue(progression.recordsBroken.isEmpty())
            assertTrue(progression.isComplete)
            // The session itself is still worth its own award, and still counts as a day.
            assertEquals(XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_ACHIEVEMENT, progression.xpAwarded)
            assertEquals(StreakResult.Incremented(1), progression.streak?.result)
        }

    // ------------------------------------------------------- the failure contract

    @Test
    fun `a store that refuses the records leaves the rest of the cascade running`() =
        runTest {
            records.insertFailure = IllegalStateException("the records table is not writable")
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.PR_DETECTION), progression.failures.map { it.step })
            assertTrue(progression.recordsBroken.isEmpty())
            // Paid for the session and its sets, and for no record, because none was detected.
            assertEquals(
                XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * COMPLETED_SETS +
                    XpCalculator.XP_PER_ACHIEVEMENT,
                progression.xpAwarded,
            )
            assertEquals(StreakResult.Incremented(1), progression.streak?.result)
            assertEquals(
                listOf(Achievements.FIRST_SESSION.id),
                progression.achievementsUnlocked.map { it.achievement.id },
            )
        }

    @Test
    fun `a store that refuses the xp still moves the streak and unlocks the achievements`() =
        runTest {
            gamification.addXpFailure = IllegalStateException("the progress row is locked")
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            // The achievements step pays its own XP, so it fails for the same reason the XP
            // step did — but the unlocks themselves are written, which is what stops the
            // milestone being re-earned later.
            assertEquals(
                listOf(SessionProgressionStep.XP, SessionProgressionStep.ACHIEVEMENTS),
                progression.failures.map { it.step },
            )
            assertEquals(BROKEN_TYPES.size, progression.recordsBroken.size)
            assertEquals(StreakResult.Incremented(1), progression.streak?.result)
            assertTrue(Achievements.FIRST_SESSION.id in gamification.unlockedAchievements)
        }

    @Test
    fun `awards banked before the store refused are still reported`() =
        runTest {
            // Every award is on file the moment it returns, so a step that failed half way
            // through has still moved the total. A result that reported nothing over it would
            // show "no XP" for a level the user actually crossed — and the claim is spent, so
            // no later run would correct it.
            val paid = 3
            val torn = Rig(decorateGamification = { FailsAfter(it, awards = paid) })
            val sessionId = torn.finishSession()

            val progression = torn.pipeline(sessionId, FINISHED_AT)

            assertEquals(
                listOf(SessionProgressionStep.XP, SessionProgressionStep.ACHIEVEMENTS),
                progression.failures.map { it.step },
            )
            // The session and the first two of its four sets — the fourth award is refused.
            assertEquals(paid, progression.xp.size)
            assertEquals(XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * 2, progression.xpAwarded)
            assertEquals(torn.gamification.totalXp, progression.totalXp)
        }

    @Test
    fun `records written before the store refused are still reported and paid for`() =
        runTest {
            // Detection writes a row per candidate, and a re-entry beats the ones it already
            // wrote rather than re-detecting them. A result that dropped them would leave
            // records the app never mentions and never pays for, on any run.
            val written = 2
            records.refuseWriteAfter = written
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.PR_DETECTION), progression.failures.map { it.step })
            assertEquals(records.inserted, progression.recordsBroken)
            assertEquals(written, progression.recordsBroken.size)
            // And the XP step pays for them: the records that landed are records broken.
            assertEquals(
                XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * COMPLETED_SETS +
                    XpCalculator.XP_PER_PR * written + XpCalculator.XP_PER_ACHIEVEMENT * 2,
                progression.xpAwarded,
            )
        }

    @Test
    fun `achievements unlocked before the store refused are still reported`() =
        runTest {
            // An unlock's insert is what makes it unrepeatable, so the first one is held for
            // good by the time the second one's award is refused. Losing it here would leave a
            // milestone the user owns and no screen ever shows.
            val sessionAwards = 1 + COMPLETED_SETS + BROKEN_TYPES.size
            val torn = Rig(decorateGamification = { FailsAfter(it, awards = sessionAwards + 1) })
            val sessionId = torn.finishSession()

            val progression = torn.pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.ACHIEVEMENTS), progression.failures.map { it.step })
            assertEquals(
                listOf(Achievements.FIRST_SESSION.id),
                progression.achievementsUnlocked.map { it.achievement.id },
            )
            // The second milestone's row is written and unpaid — the gap the trigger's own doc
            // names — so what it must not also be is unreported.
            assertTrue(Achievements.FIRST_RECORD.id in torn.gamification.unlockedAchievements)
            assertEquals(sessionAwards + 1, progression.xp.size)
        }

    @Test
    fun `a streak that cannot be read takes the achievements with it rather than guessing`() =
        runTest {
            gamification.streakFailure = IllegalStateException("the progress row is locked")
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(
                listOf(SessionProgressionStep.STREAK, SessionProgressionStep.ACHIEVEMENTS),
                progression.failures.map { it.step },
            )
            assertNull(progression.streak)
            assertTrue(progression.achievementsUnlocked.isEmpty())
            assertTrue(gamification.unlockedAchievements.isEmpty())
        }

    @Test
    fun `a failed step is reported with its cause rather than swallowed`() =
        runTest {
            val cause = IllegalStateException("the records table is not writable")
            records.insertFailure = cause
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(cause, progression.failures.single().cause)
            assertEquals(false, progression.isComplete)
        }

    @Test
    fun `cancellation is not a failure and is rethrown`() =
        runTest {
            // A caller whose scope went away is not owed a report, and recording a cancelled
            // step as one the store refused would make an ordinary screen teardown look like
            // a database fault.
            gamification.streakFailure = CancellationException("the caller's scope went away")
            val sessionId = rig.finishSession()

            val thrown = runCatching { pipeline(sessionId, FINISHED_AT) }.exceptionOrNull()

            assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
        }

    // ------------------------------------------------------ the re-entry contract

    @Test
    fun `running the cascade twice breaks no second record`() =
        runTest {
            val sessionId = rig.finishSession()
            pipeline(sessionId, FINISHED_AT)

            val second = pipeline(sessionId, FINISHED_AT)

            assertTrue(second.recordsBroken.isEmpty())
            assertEquals(BROKEN_TYPES.size, records.inserted.size)
        }

    @Test
    fun `running the cascade twice awards no second xp`() =
        runTest {
            val sessionId = rig.finishSession()
            val first = pipeline(sessionId, FINISHED_AT)
            val bankedAfterFirst = gamification.totalXp

            val second = pipeline(sessionId, FINISHED_AT)

            assertTrue(first.xpAwarded > 0)
            assertEquals(0, second.xpAwarded)
            assertEquals(bankedAfterFirst, gamification.totalXp)
        }

    @Test
    fun `running the cascade twice does not increment the streak twice`() =
        runTest {
            val sessionId = rig.finishSession()
            pipeline(sessionId, FINISHED_AT)

            val second = pipeline(sessionId, FINISHED_AT)

            assertEquals(StreakUpdate(StreakResult.Unchanged, days = 1), second.streak)
            assertEquals(1, gamification.streakDays)
        }

    @Test
    fun `running the cascade twice unlocks no achievement twice`() =
        runTest {
            val sessionId = rig.finishSession()
            val first = pipeline(sessionId, FINISHED_AT)
            val unlockedAfterFirst = gamification.unlockedAchievements

            val second = pipeline(sessionId, FINISHED_AT)

            assertEquals(2, first.achievementsUnlocked.size)
            assertTrue(second.achievementsUnlocked.isEmpty())
            // Including the timestamps: a re-run must not re-date a milestone to the retry.
            assertEquals(unlockedAfterFirst, gamification.unlockedAchievements)
        }

    @Test
    fun `a cascade torn between detection and the awards completes what had not run`() =
        runTest {
            // The state a process death after PR detection leaves: the rows are written and
            // nothing was paid. Re-entering must finish the job without repeating the part
            // that had — see the pipeline's re-entry contract for what it deliberately does
            // not recover, which is the records' own XP.
            val sessionId = rig.finishSession()
            rig.detectPersonalRecords(sessionId)
            val recordsWritten = records.inserted

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(recordsWritten, records.inserted)
            assertEquals(1, gamification.streakDays)
            assertEquals(
                // The records survive and still count towards `first_record`, because the
                // catalogue is evaluated against what the store holds rather than against what
                // this run happened to do.
                listOf(Achievements.FIRST_SESSION.id, Achievements.FIRST_RECORD.id),
                progression.achievementsUnlocked.map { it.achievement.id },
            )
            assertEquals(
                XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * COMPLETED_SETS +
                    XpCalculator.XP_PER_ACHIEVEMENT * 2,
                progression.xpAwarded,
            )
        }

    @Test
    fun `xp lost to a torn award run is not re-paid on re-entry`() =
        runTest {
            // The claim is taken before the awards, so a store that fails half way through
            // them leaves the rest owed forever. Under-paying one session is the deliberate
            // half of the trade against re-paying every session on every crash-recovery boot.
            //
            // Every milestone is already held so that the only XP in play is the session's
            // own: the achievements step pays behind its own fence, and a first unlock landing
            // on the re-entry would otherwise be read as the session being re-paid.
            Achievements.ALL.forEach { gamification.unlockAchievement(it.id, FINISHED_AT) }
            gamification.addXpFailure = IllegalStateException("the progress row is locked")
            val sessionId = rig.finishSession()
            pipeline(sessionId, FINISHED_AT)
            gamification.addXpFailure = null

            val second = pipeline(sessionId, FINISHED_AT)

            assertEquals(0, second.xpAwarded)
            assertEquals(0L, gamification.totalXp)
        }

    @Test
    fun `a second session on the same day counts once towards the streak`() =
        runTest {
            val morning = rig.finishSession(exerciseId = 10L)
            pipeline(morning, FINISHED_AT)
            // Two hours later, still the same UTC day — which is the zone the rule is read in.
            val laterThatDay = FINISHED_AT.plusSeconds(HOUR_SECONDS * 2)
            val evening = rig.finishSession(exerciseId = 20L, completedAt = laterThatDay)

            val progression = pipeline(evening, laterThatDay)

            // A different session, so its own XP is paid — but a streak counts days shown up.
            assertTrue(progression.xpAwarded > 0)
            assertEquals(StreakResult.Unchanged, progression.streak?.result)
            assertEquals(1, gamification.streakDays)
        }

    /**
     * The stores and the composition under test, bundled so a test that needs a second,
     * independent history — the torn-cascade comparison, the ordering journal — can have one
     * without the fields being rebuilt by hand.
     *
     * @param journal when given, records which store each step reached first; see the ordering
     *   test. The decorators only observe, so the composition under test is the same one every
     *   other test runs.
     * @param decorateGamification wraps the progress store the composition is built over, for
     *   the one test that needs it to fail part way through rather than at the first write.
     *   [gamification] stays the store underneath, so an assertion can still read what was
     *   actually banked.
     */
    private class Rig(
        journal: MutableList<String>? = null,
        decorateGamification: (GamificationRepository) -> GamificationRepository = { it },
    ) {

        val sessions = FakeSessionRepository()
        val records = FakePersonalRecordRepository()
        val gamification = FakeGamificationRepository()

        private val observedRecords: PersonalRecordRepository =
            journal?.let { JournallingRecords(records, it) } ?: records
        private val observedGamification: GamificationRepository =
            decorateGamification(journal?.let { JournallingGamification(gamification, it) } ?: gamification)

        val detectPersonalRecords = PrDetectionUseCase(
            sessionRepository = sessions,
            personalRecordRepository = observedRecords,
            userPreferencesRepository = FakeUserPreferencesRepository(),
        )

        private val awardXp = AwardXpUseCase(observedGamification)

        val pipeline = SessionCompletionPipeline(
            detectPersonalRecords = detectPersonalRecords,
            awardXp = awardXp,
            triggerAchievements = AchievementTriggerUseCase(observedGamification, awardXp),
            sessionRepository = sessions,
            personalRecordRepository = observedRecords,
            gamificationRepository = observedGamification,
        )

        /**
         * A session already closed out, as [FinishSessionUseCase] leaves one: the row stamped
         * and out of progress, holding [warmUpSets] warm-ups and [workingSets] working sets on
         * one exercise.
         *
         * Warm-ups are lighter than the working sets on purpose. They are excluded from
         * detection and included in the XP, which is the one place the two readings of the
         * same rows differ.
         */
        suspend fun finishSession(
            exerciseId: Long = 10L,
            workingSets: Int = 3,
            warmUpSets: Int = 1,
            completedAt: Instant = FINISHED_AT,
        ): Long {
            val millis = completedAt.toEpochMilli()
            val sessionId = sessions.insert(
                WorkoutSession(
                    completedAt = millis,
                    isInProgress = false,
                    lastActiveAt = millis,
                    createdAt = 0L,
                    updatedAt = millis,
                ),
            )
            val sessionExerciseId = sessions.addExercise(sessionId = sessionId, exerciseId = exerciseId)
            repeat(warmUpSets) { addSet(sessionExerciseId, WARM_UP_WEIGHT, millis, SetType.WARMUP) }
            repeat(workingSets) { addSet(sessionExerciseId, WORKING_WEIGHT, millis, SetType.NORMAL) }
            return sessionId
        }

        private fun addSet(
            sessionExerciseId: Long,
            weight: Long,
            completedAt: Long,
            setType: SetType,
        ) {
            sessions.addSet(
                sessionExerciseId,
                WorkoutSet(
                    sessionExerciseId = sessionExerciseId,
                    setNumber = 0,
                    weight = weight,
                    reps = REPS,
                    setType = setType,
                    isCompleted = true,
                    completedAt = completedAt,
                    createdAt = completedAt,
                    updatedAt = completedAt,
                ),
            )
        }
    }

    /** Notes that detection reached the records table, and otherwise changes nothing. */
    private class JournallingRecords(
        private val delegate: PersonalRecordRepository,
        private val journal: MutableList<String>,
    ) : PersonalRecordRepository by delegate {

        override suspend fun insertIfBetter(
            exerciseId: Long,
            type: PrType,
            record: PersonalRecord,
            isBetter: (current: PersonalRecord?) -> Boolean,
        ): PersonalRecord? {
            journal += "record"
            return delegate.insertIfBetter(exerciseId, type, record, isBetter)
        }
    }

    /** The same, for the three writes the other steps make. */
    private class JournallingGamification(
        private val delegate: GamificationRepository,
        private val journal: MutableList<String>,
    ) : GamificationRepository by delegate {

        override suspend fun addXp(
            delta: Int,
            levelForTotalXp: (totalXp: Long) -> Int,
        ): XpAward {
            journal += "xp"
            return delegate.addXp(delta, levelForTotalXp)
        }

        override suspend fun updateStreak(
            finishedAt: Instant,
            computeStreak: (lastCountedAt: Instant?, currentStreak: Int) -> StreakResult,
        ): StreakUpdate {
            journal += "streak"
            return delegate.updateStreak(finishedAt, computeStreak)
        }

        override suspend fun unlockAchievement(
            achievementId: String,
            unlockedAt: Instant,
        ): Boolean {
            journal += "achievement"
            return delegate.unlockAchievement(achievementId, unlockedAt)
        }
    }

    /**
     * Banks [awards] awards and refuses every one after them — the store failing part way
     * through a run, which [FakeGamificationRepository.addXpFailure] cannot express because it
     * refuses from the first write.
     */
    private class FailsAfter(
        private val delegate: GamificationRepository,
        private val awards: Int,
    ) : GamificationRepository by delegate {

        private var banked = 0

        override suspend fun addXp(
            delta: Int,
            levelForTotalXp: (totalXp: Long) -> Int,
        ): XpAward {
            check(banked++ < awards) { "the progress row is locked" }
            return delegate.addXp(delta, levelForTotalXp)
        }
    }

    private companion object {

        /** When the fixture's session finished. A fixed instant, so the streak arithmetic is exact. */
        val FINISHED_AT: Instant = Instant.parse("2026-03-02T18:00:00Z")

        const val DAY_SECONDS = 24L * 60 * 60
        const val HOUR_SECONDS = 60L * 60

        const val WORKING_WEIGHT = 100_000L
        const val WARM_UP_WEIGHT = 40_000L
        const val REPS = 5

        /** Warm-ups included: [XpEvent.SetLogged] prices showing up, not how hard the set was. */
        const val COMPLETED_SETS = 4

        /**
         * What the fixture beats on a fresh install. No distance and no duration are logged, so
         * the two cardio types have no candidate and no row.
         */
        val BROKEN_TYPES = setOf(
            PrType.MAX_WEIGHT,
            PrType.MAX_REPS,
            PrType.MAX_VOLUME,
            PrType.ESTIMATED_1RM,
        )
    }
}
