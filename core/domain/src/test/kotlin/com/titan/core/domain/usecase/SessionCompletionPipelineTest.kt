package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Achievements
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.DropRates
import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.gamification.FoodDrop
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestPeriod
import com.titan.core.domain.model.Rarity
import com.titan.core.domain.model.SessionProgressionStep
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.StreakResult
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.XpAward
import com.titan.core.domain.model.XpEvent
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * The cascade's three contracts — the order, the failure contract and the re-entry contract —
 * against the real use cases over in-memory stores.
 *
 * Nothing inside `:core:domain` is mocked: the ordering constraints under test are constraints
 * between *what the steps wrote*, and a stubbed detection or a stubbed award would let an
 * ordering that never touched the store pass. The fakes are the repository interfaces the
 * module declares and does not implement, and each enforces the part of its contract the
 * cascade leans on — see [FakeGamificationRepository] in particular, whose streak write keeps
 * the anchor that makes a session count once.
 */
class SessionCompletionPipelineTest {

    private val rig = Rig()

    private val sessions get() = rig.sessions
    private val records get() = rig.records
    private val gamification get() = rig.gamification
    private val inventory get() = rig.inventory
    private val pets get() = rig.pets
    private val pipeline get() = rig.pipeline

    // ---------------------------------------------------------------- the order

    @Test
    fun `the steps run in the order records, xp, streak, achievements, loot`() =
        runTest {
            val journal = mutableListOf<String>()
            val journalled = Rig(journal)
            val sessionId = journalled.finishSession()

            journalled.pipeline(sessionId, FINISHED_AT)

            // Every store is touched more than once — four awards, four records, two unlocks
            // and the achievement XP behind them — so it is the order they are *first* reached
            // in that is the ordering constraint. Loot is the exception: at most one row, and
            // it is last.
            assertEquals(listOf("record", "xp", "streak", "achievement", "loot"), journal.distinct())
        }

    @Test
    fun `a record that beats an earlier one is paid for, which it could not be if xp ran first`() =
        runTest {
            pipeline(rig.finishSession(workingWeight = LIGHT_WEIGHT), FINISHED_AT)
            val later = FINISHED_AT.plusSeconds(DAY_SECONDS)
            val sessionId = rig.finishSession(completedAt = later)

            val progression = pipeline(sessionId, later)

            val broken = progression.recordsBroken.map(PersonalRecord::type).toSet()
            assertTrue(PrType.MAX_WEIGHT in broken)
            assertEquals(
                broken.map { XpEvent.PrBroken(it) }.toSet(),
                progression.xp.map { it.event }.filterIsInstance<XpEvent.PrBroken>().toSet(),
            )
            assertEquals(
                XpCalculator.XP_PER_PR * broken.size,
                progression.xp.filter { it.event is XpEvent.PrBroken }.sumOf { it.award.awarded },
            )
        }

    @Test
    fun `the first figure ever written for an exercise is recorded and not paid for`() =
        runTest {
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(BROKEN_TYPES, progression.recordsBroken.map(PersonalRecord::type).toSet())
            assertTrue(progression.xp.none { it.event is XpEvent.PrBroken })
        }

    @Test
    fun `warm-ups are not paid for`() =
        runTest {
            val sessionId = rig.finishSession(warmUpSets = 6)

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(PAID_SETS, progression.xp.count { it.event == XpEvent.SetLogged })
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
            // Three weeks in, the last of them the week before, so the session under test meets
            // the fourth and the milestone is only reachable by a catalogue evaluated after the
            // streak step, not before it.
            val threshold = Achievements.FOUR_WEEK_STREAK.threshold
            gamification.seedStreak(weeks = threshold - 1, lastCountedAt = FINISHED_AT.minusSeconds(WEEK_SECONDS))
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(threshold, progression.streak?.weeks)
            assertTrue(
                progression.achievementsUnlocked.map { it.achievement.id }
                    .contains(Achievements.FOUR_WEEK_STREAK.id),
            )
        }

    // ---------------------------------------------------- the value it hands back

    @Test
    fun `the result carries every award, the session's and its achievements'`() =
        runTest {
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            // The session, each working set (the warm-up pays nothing), and the two
            // achievements it unlocks. Its records are the first of their kind, so they are free.
            val expected = XpCalculator.XP_PER_SESSION +
                XpCalculator.XP_PER_SET * PAID_SETS +
                XpCalculator.XP_PER_ACHIEVEMENT * 2
            assertEquals(expected, progression.xpAwarded)
            assertEquals(expected.toLong(), progression.totalXp)
            assertEquals(gamification.totalXp, progression.totalXp)
        }

    @Test
    fun `the result reports the level-ups the awards observed, without a second read`() =
        runTest {
            // A point short of level 2, so the session's first award is the crossing.
            gamification.addXp(XpCalculator.xpRequiredForLevel(2).toInt() - 1, XpCalculator::levelForTotalXp)
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
            // A session with no work in it pays nothing for itself, but still counts as a day and
            // still unlocks the milestone for having finished one.
            assertEquals(XpCalculator.XP_PER_ACHIEVEMENT, progression.xpAwarded)
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
                XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * PAID_SETS +
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
            // The session and the first two of its three sets — the third set's award is refused.
            assertEquals(paid, progression.xp.size)
            assertEquals(XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * 2, progression.xpAwarded)
            assertEquals(torn.gamification.totalXp, progression.totalXp)
        }

    @Test
    fun `records written before the store refused are still reported`() =
        runTest {
            // Detection writes a row per candidate, and a re-entry beats the ones it already
            // wrote rather than re-detecting them. A result that dropped them would leave
            // records the app never mentions, on any run.
            val written = 2
            records.refuseWriteAfter = written
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.PR_DETECTION), progression.failures.map { it.step })
            assertEquals(records.inserted, progression.recordsBroken)
            assertEquals(written, progression.recordsBroken.size)
            // They are the first of their kind, so the XP step has nothing to pay for them, and
            // they still count towards `first_record`.
            assertEquals(
                XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * PAID_SETS +
                    XpCalculator.XP_PER_ACHIEVEMENT * 2,
                progression.xpAwarded,
            )
        }

    @Test
    fun `achievements unlocked before the store refused are still reported`() =
        runTest {
            // An unlock's insert is what makes it unrepeatable, so the first one is held for
            // good by the time the second one's award is refused. Losing it here would leave a
            // milestone the user owns and no screen ever shows.
            val sessionAwards = 1 + PAID_SETS
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

            assertEquals(StreakResult.Unchanged, second.streak?.result)
            assertEquals(1, second.streak?.weeks)
            assertEquals(1, gamification.streakWeeks)
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
            assertEquals(1, gamification.streakWeeks)
            assertEquals(
                // The records survive and still count towards `first_record`, because the
                // catalogue is evaluated against what the store holds rather than against what
                // this run happened to do.
                listOf(Achievements.FIRST_SESSION.id, Achievements.FIRST_RECORD.id),
                progression.achievementsUnlocked.map { it.achievement.id },
            )
            assertEquals(
                XpCalculator.XP_PER_SESSION + XpCalculator.XP_PER_SET * PAID_SETS +
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
    fun `a second session in a week that met its goal does not count the week twice`() =
        runTest {
            val morning = rig.finishSession(exerciseId = 10L)
            pipeline(morning, FINISHED_AT)
            // Two hours later, still the same UTC week — which is the zone the rule is read in.
            val laterThatDay = FINISHED_AT.plusSeconds(HOUR_SECONDS * 2)
            val evening = rig.finishSession(exerciseId = 20L, completedAt = laterThatDay)

            val progression = pipeline(evening, laterThatDay)

            // A different session, so its own XP is paid and it counts towards the week — but
            // the week is worth one to the streak however many sessions it holds.
            assertTrue(progression.xpAwarded > 0)
            assertEquals(StreakResult.Counted, progression.streak?.result)
            assertEquals(1, gamification.streakWeeks)
        }

    // ─── The quest step ─────────────────────────────────────────────────────────────────

    @Test
    fun `a quest the session completes is reported and paid`() =
        runTest {
            val rig = Rig(questCatalogue = listOf(ONE_SESSION_QUEST))
            rig.progression.setSessionsOn(FINISHED_AT.atZone(ZoneOffset.UTC).toLocalDate(), 1)
            val sessionId = rig.finishSession()

            val progression = rig.pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(ONE_SESSION_QUEST), progression.questsCompleted.map { it.quest })
            // The completion's award joins the ledger, so `xp` is the whole breakdown.
            assertTrue(
                "the quest's award is missing from the ledger",
                progression.xp.any { it.event == XpEvent.QuestCompleted(ONE_SESSION_QUEST.id, QUEST_XP) },
            )
            assertEquals(QUEST_XP, progression.questsCompleted.single().xp.awarded)
        }

    @Test
    fun `a quest that completes on a record sees the record this session broke`() =
        runTest {
            // The ordering that matters: quests run after PR detection, so a quest counting
            // records is evaluated against rows the same cascade has just written. Nothing is
            // seeded here — the records come from detection.
            val rig = Rig(questCatalogue = listOf(ONE_RECORD_QUEST))
            val sessionId = rig.finishSession()

            val progression = rig.pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(ONE_RECORD_QUEST), progression.questsCompleted.map { it.quest })
        }

    @Test
    fun `re-running the cascade completes no quest a second time`() =
        runTest {
            val rig = Rig(questCatalogue = listOf(ONE_SESSION_QUEST))
            rig.progression.setSessionsOn(FINISHED_AT.atZone(ZoneOffset.UTC).toLocalDate(), 1)
            val sessionId = rig.finishSession()

            rig.pipeline(sessionId, FINISHED_AT)
            val again = rig.pipeline(sessionId, FINISHED_AT)

            assertEquals(emptyList<Any>(), again.questsCompleted)
            assertEquals(1, rig.quests.written.size)
        }

    @Test
    fun `a quest store that refuses leaves the workout and every earlier step intact`() =
        runTest {
            // The failure contract, at the last step: the session is closed out and its records,
            // XP and achievements are on file. A quest write that fails is recorded, not thrown.
            val rig = Rig(questCatalogue = listOf(ONE_SESSION_QUEST))
            rig.progression.setSessionsOn(FINISHED_AT.atZone(ZoneOffset.UTC).toLocalDate(), 1)
            val sessionId = rig.finishSession()
            rig.quests.failNext = IllegalStateException("the quest table is not writable")

            val progression = rig.pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.QUESTS), progression.failures.map { it.step })
            assertEquals(emptyList<Any>(), progression.questsCompleted)
            assertTrue("the earlier steps did not run", progression.recordsBroken.isNotEmpty())
            assertTrue("the session was not paid for", progression.xpAwarded > 0)
        }

    @Test
    fun `a streak that could not be read does not stop the quests`() =
        runTest {
            // Unlike achievements, which are fenced by the streak because their snapshot needs
            // it. No quest counts streak weeks, so the board is entirely intact without one.
            val rig = Rig(
                questCatalogue = listOf(ONE_SESSION_QUEST),
                decorateGamification = { FailingStreak(it) },
            )
            rig.progression.setSessionsOn(FINISHED_AT.atZone(ZoneOffset.UTC).toLocalDate(), 1)
            val sessionId = rig.finishSession()

            val progression = rig.pipeline(sessionId, FINISHED_AT)

            assertEquals(
                listOf(SessionProgressionStep.STREAK, SessionProgressionStep.ACHIEVEMENTS),
                progression.failures.map { it.step },
            )
            assertEquals(listOf(ONE_SESSION_QUEST), progression.questsCompleted.map { it.quest })
        }

    /** A store whose streak write refuses, leaving every other write working. */
    private class FailingStreak(private val delegate: GamificationRepository) :
        GamificationRepository by delegate {

        override suspend fun updateStreak(advance: (state: StreakState) -> StreakUpdate): StreakUpdate =
            error("the progress row is not writable")
    }

    // ─── The pet step ───────────────────────────────────────────────────────────────────

    @Test
    fun `with no pet the session grows nothing and that is not a failure`() =
        runTest {
            val progression = pipeline(rig.finishSession(), FINISHED_AT)

            assertNull(progression.pet)
            assertTrue(progression.isComplete)
        }

    @Test
    fun `a finished session grows the active pet once, and a re-run reports nothing`() =
        runTest {
            pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = FINISHED_AT)
            val sessionId = rig.finishSession(workingSets = 3, warmUpSets = 1)

            val first = pipeline(sessionId, FINISHED_AT)
            val again = pipeline(sessionId, FINISHED_AT)

            // Three working sets; the warm-up pays the pet nothing, as it pays the user nothing.
            assertEquals(65L, first.pet?.xpGained)
            assertNull(again.pet)
            assertEquals(65L, pets.held.single().xp)
        }

    @Test
    fun `a failed xp step does not stop the pet growing in full`() =
        runTest {
            pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = FINISHED_AT)
            gamification.addXpFailure = IllegalStateException("the progress row is locked")

            val progression = pipeline(rig.finishSession(workingSets = 3), FINISHED_AT)

            assertTrue(SessionProgressionStep.XP in progression.failures.map { it.step })
            assertEquals(65L, progression.pet?.xpGained)
        }

    @Test
    fun `a run that died before the pet step pays the pet in full on re-entry`() =
        runTest {
            val torn = Rig(decorateGamification = { FailingPetClaim(it, times = 1) })
            torn.pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = FINISHED_AT)
            val sessionId = torn.finishSession(workingSets = 3)

            val first = torn.pipeline(sessionId, FINISHED_AT)
            val reEntered = torn.pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.PET), first.failures.map { it.step })
            assertEquals("the xp claim was spent by the first run", 0, reEntered.xpAwarded)
            assertEquals(65L, reEntered.pet?.xpGained)
            assertEquals(65L, torn.pets.held.single().xp)
        }

    @Test
    fun `a pet step that throws is recorded under PET and leaves every earlier write intact`() =
        runTest {
            val failing = Rig(decorateGamification = { FailingPetClaim(it) })
            failing.pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = FINISHED_AT)
            val sessionId = failing.finishSession()

            val progression = failing.pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.PET), progression.failures.map { it.step })
            assertNull(progression.pet)
            assertTrue(progression.xpAwarded > 0)
            assertEquals(StreakResult.Incremented(1), progression.streak?.result)
            assertEquals(0L, failing.pets.held.single().xp)
        }

    @Test
    fun `with no titan a session drops no food`() =
        runTest {
            assertTrue(pipeline(rig.finishSession(), FINISHED_AT).foodDropped.isEmpty())
        }

    @Test
    fun `with a titan the session's food is reported beside its drop, and a re-run reports none`() =
        runTest {
            pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = FINISHED_AT)
            val sessionId = rig.finishSession(workingSets = 3)

            val first = pipeline(sessionId, FINISHED_AT)
            val again = pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(Collectibles.STEAK), first.foodDropped)
            assertEquals(DROPPED_COLLECTIBLE, first.lootDropped)
            assertTrue(again.foodDropped.isEmpty())
        }

    @Test
    fun `a session that earns nothing unlocks nothing, and that is not a failure`() =
        runTest {
            val progression = pipeline(rig.finishSession(), FINISHED_AT)

            assertTrue(progression.unlocked.isEmpty())
            assertTrue(progression.isComplete)
        }

    /** A store whose pet claim refuses [times] times, leaving every other write working. */
    private class FailingPetClaim(
        private val delegate: GamificationRepository,
        private var times: Int = Int.MAX_VALUE,
    ) : GamificationRepository by delegate {

        override suspend fun claimSessionPet(sessionId: Long): Boolean {
            if (times > 0) {
                times--
                error("the claim table is not writable")
            }
            return delegate.claimSessionPet(sessionId)
        }
    }

    // ─── The loot step ──────────────────────────────────────────────────────────────────

    @Test
    fun `a finished session's drop is reported and stored against the workout's moment`() =
        runTest {
            val sessionId = rig.finishSession()

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(DROPPED_COLLECTIBLE, progression.lootDropped)
            // Dated to the workout rather than to the roll, so a cascade re-entered an hour
            // later would still file the drop under the session that earned it.
            assertEquals(
                listOf(
                    CollectedItem(1L, DROPPED_COLLECTIBLE.id, DropSource.SESSION_FINISHED.key, FINISHED_AT),
                ),
                inventory.recorded,
            )
        }

    @Test
    fun `a session whose roll pays out nothing reports no drop and is not a failure`() =
        runTest {
            val rig = Rig(dropTable = NEVER_DROPS)
            val sessionId = rig.finishSession()

            val progression = rig.pipeline(sessionId, FINISHED_AT)

            assertNull(progression.lootDropped)
            assertTrue(rig.inventory.recorded.isEmpty())
            // The common answer on the shipped rates, and an ordinary one: nothing dropped is
            // not a step that failed.
            assertTrue(progression.isComplete)
        }

    @Test
    fun `re-running the cascade rolls no second drop`() =
        runTest {
            val sessionId = rig.finishSession()
            val first = pipeline(sessionId, FINISHED_AT)

            val second = pipeline(sessionId, FINISHED_AT)

            // The table would drop again given the chance — it always does — so the empty
            // second result is the claim doing its job rather than an unlucky roll.
            assertEquals(DROPPED_COLLECTIBLE, first.lootDropped)
            assertNull(second.lootDropped)
            assertEquals(1, inventory.recorded.size)
        }

    @Test
    fun `a second session rolls on a claim of its own`() =
        runTest {
            val morning = rig.finishSession(exerciseId = 10L)
            pipeline(morning, FINISHED_AT)
            val laterThatDay = FINISHED_AT.plusSeconds(HOUR_SECONDS * 2)
            val evening = rig.finishSession(exerciseId = 20L, completedAt = laterThatDay)

            val progression = pipeline(evening, laterThatDay)

            // A claim is per session, not per day: the streak counts days shown up, the roll
            // does not.
            assertEquals(DROPPED_COLLECTIBLE, progression.lootDropped)
            assertEquals(2, inventory.recorded.size)
        }

    @Test
    fun `an inventory that refuses leaves the workout and every earlier step intact`() =
        runTest {
            // The failure contract at the last step of all: the session is closed out and its
            // records, XP, streak and achievements are on file. A drop that cannot be written
            // is recorded, not thrown.
            val sessionId = rig.finishSession()
            inventory.failNext = IllegalStateException("the inventory is not writable")

            val progression = pipeline(sessionId, FINISHED_AT)

            assertEquals(listOf(SessionProgressionStep.LOOT), progression.failures.map { it.step })
            assertNull(progression.lootDropped)
            assertTrue("the earlier steps did not run", progression.recordsBroken.isNotEmpty())
            assertTrue("the session was not paid for", progression.xpAwarded > 0)
            assertEquals(1, gamification.streakWeeks)
            assertEquals(2, progression.achievementsUnlocked.size)
        }

    @Test
    fun `a drop lost to a refused write is not rolled again on re-entry`() =
        runTest {
            // The tear this deliberately does not recover from, and the same trade the XP claim
            // names: the claim is taken before the write, so a refused row costs that session
            // its collectible outright rather than risking one on every crash-recovery boot.
            val sessionId = rig.finishSession()
            inventory.failNext = IllegalStateException("the inventory is not writable")
            pipeline(sessionId, FINISHED_AT)

            val second = pipeline(sessionId, FINISHED_AT)

            assertNull(second.lootDropped)
            assertTrue(inventory.recorded.isEmpty())
            assertTrue(second.isComplete)
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
        questCatalogue: List<Quest> = emptyList(),
        dropTable: DropTableEvaluator = ALWAYS_DROPS,
    ) {

        val sessions = FakeSessionRepository()
        val records = FakePersonalRecordRepository()
        val gamification = FakeGamificationRepository()
        val quests = FakeQuestRepository()
        val progression = FakeProgressionRepository()
        val inventory = FakeCollectedItemRepository()
        val pets = FakePetRepository()
        val unlocks = FakeUnlockRepository()

        // A goal of one, so every session that counts is a week that met its goal, and the
        // streak moves on each one. The weekly rule itself is `StreakCalculatorTest`'s.
        val preferences = FakeUserPreferencesRepository(UserPreferences(weeklyWorkoutGoal = 1))

        private val observedRecords: PersonalRecordRepository =
            journal?.let { JournallingRecords(records, it) } ?: records
        private val observedGamification: GamificationRepository =
            decorateGamification(journal?.let { JournallingGamification(gamification, it) } ?: gamification)
        private val observedInventory: CollectedItemRepository =
            journal?.let { JournallingInventory(inventory, it) } ?: inventory

        val detectPersonalRecords = PrDetectionUseCase(
            sessionRepository = sessions,
            // Empty: an exercise the catalogue does not hold is judged as a lift, which is what
            // every set in this suite is.
            exerciseRepository = FakeExerciseRepository(),
            personalRecordRepository = observedRecords,
            userPreferencesRepository = preferences,
        )

        private val awardXp = AwardXpUseCase(observedGamification)

        /**
         * The quest step, over an **empty catalogue** unless a test asks for one.
         *
         * Not over [Quests.ALL], deliberately. The quest board reads the same record store PR
         * detection writes to, so the real catalogue would complete `weekly_record` on every
         * session in this suite that breaks one — and every XP total asserted here, in tests
         * about the XP step and the failure contract, would silently become a statement about
         * what a quest costs. An empty catalogue makes the step run and find nothing, which
         * keeps each test about the one thing it is named for; the tests that *are* about
         * quests pass a catalogue in.
         */
        private val completeQuests = QuestCompletionUseCase(
            questProgress = QuestProgressUseCase(
                progressionRepository = progression,
                personalRecordRepository = observedRecords,
                questRepository = quests,
                catalogue = questCatalogue,
            ),
            questRepository = quests,
            awardXp = awardXp,
        )

        /**
         * The loot step, over a **one-entry catalogue that always drops** unless a test says
         * otherwise.
         *
         * The shipped rates would make this suite's assertions about drops flaky by design —
         * four sessions in five roll nothing — and the shipped catalogue would make them
         * statements about whatever it stocks this month. A table that always pays out the same
         * collectible keeps every loot assertion here about the cascade: that the drop is
         * reported, dated and stored, and that a re-entry produces none.
         */
        private val rollLoot = LootRollUseCase(
            dropTable = dropTable,
            collectedItemRepository = observedInventory,
            gamificationRepository = observedGamification,
            petRepository = pets,
            sessionRepository = sessions,
            // Always the first food: what is drawn is `FoodDropTest`'s.
            foodDrop = FoodDrop(RandomSource { 0 }),
        )

        val pipeline = SessionCompletionPipeline(
            detectPersonalRecords = detectPersonalRecords,
            awardXp = awardXp,
            triggerAchievements = AchievementTriggerUseCase(observedGamification, awardXp),
            completeQuests = completeQuests,
            rollLoot = rollLoot,
            growPet = GrowPetUseCase(
                sessionRepository = sessions,
                exerciseRepository = FakeExerciseRepository(),
                petRepository = pets,
                gamificationRepository = observedGamification,
            ),
            evaluateUnlocks = EvaluateUnlocksUseCase(observedGamification, sessions, { 0 }, unlocks),
            sessionRepository = sessions,
            personalRecordRepository = observedRecords,
            gamificationRepository = observedGamification,
            userPreferencesRepository = preferences,
        )

        /**
         * A session already closed out, as [FinishSessionUseCase] leaves one: the row stamped
         * and out of progress, holding [warmUpSets] warm-ups and [workingSets] working sets on
         * one exercise.
         *
         * Warm-ups are lighter than the working sets on purpose, so that detection and the XP
         * step can be seen to leave them both out.
         */
        suspend fun finishSession(
            exerciseId: Long = 10L,
            workingSets: Int = 3,
            warmUpSets: Int = 1,
            completedAt: Instant = FINISHED_AT,
            workingWeight: Long = WORKING_WEIGHT,
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
            repeat(workingSets) { addSet(sessionExerciseId, workingWeight, millis, SetType.NORMAL) }
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

        override suspend fun updateStreak(advance: (state: StreakState) -> StreakUpdate): StreakUpdate {
            journal += "streak"
            return delegate.updateStreak(advance)
        }

        override suspend fun unlockAchievement(
            achievementId: String,
            unlockedAt: Instant,
        ): Boolean {
            journal += "achievement"
            return delegate.unlockAchievement(achievementId, unlockedAt)
        }
    }

    /** The same again, for the one row the loot step writes. */
    private class JournallingInventory(
        private val delegate: CollectedItemRepository,
        private val journal: MutableList<String>,
    ) : CollectedItemRepository by delegate {

        override suspend fun recordDrop(
            collectibleId: String,
            source: String,
            acquiredAt: Instant,
            sessionId: Long?,
        ) {
            journal += "loot"
            delegate.recordDrop(collectibleId, source, acquiredAt, sessionId)
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

        const val QUEST_XP = 60

        /**
         * The whole catalogue this suite rolls against — one common, dropped by a finished
         * session, so no assertion here is a statement about what the shipped table stocks.
         */
        val DROPPED_COLLECTIBLE = Collectible(
            id = "test_chalk",
            rarity = Rarity.COMMON,
            source = DropSource.SESSION_FINISHED,
        )

        /** Lowest roll there is: the first band, and its first entry. See `DropRates`. */
        val ALWAYS_DROPS = DropTableEvaluator(RandomSource { 0 }, listOf(DROPPED_COLLECTIBLE))

        /** Past the last band, which is the table's no-drop remainder. */
        val NEVER_DROPS = DropTableEvaluator(
            RandomSource { DropRates.TOTAL_WEIGHT - 1 },
            listOf(DROPPED_COLLECTIBLE),
        )

        /** Met by the one session these tests finish. */
        val ONE_SESSION_QUEST = Quest(
            id = "one_session",
            metric = QuestMetric.SESSIONS_FINISHED,
            target = 1,
            period = QuestPeriod.WEEKLY,
            xpReward = QUEST_XP,
        )

        /** Met by the records PR detection writes for that session. */
        val ONE_RECORD_QUEST = Quest(
            id = "one_record",
            metric = QuestMetric.RECORDS_BROKEN,
            target = 1,
            period = QuestPeriod.WEEKLY,
            xpReward = QUEST_XP,
        )

        const val DAY_SECONDS = 24L * 60 * 60
        const val WEEK_SECONDS = 7 * DAY_SECONDS
        const val HOUR_SECONDS = 60L * 60

        const val WORKING_WEIGHT = 100_000L
        const val LIGHT_WEIGHT = 80_000L
        const val WARM_UP_WEIGHT = 40_000L
        const val REPS = 5

        /** The fixture's working sets: the warm-up before them pays nothing. */
        const val PAID_SETS = 3

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
