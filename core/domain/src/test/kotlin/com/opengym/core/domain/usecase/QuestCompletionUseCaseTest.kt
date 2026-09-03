package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.QuestCompletion
import com.opengym.core.domain.model.QuestMetric
import com.opengym.core.domain.model.QuestPeriod
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Month

/**
 * The completion pass: what it banks, what it pays, and the two things it must never do twice.
 */
class QuestCompletionUseCaseTest {

    // ─── What completes ─────────────────────────────────────────────────────────────────

    @Test
    fun `a quest whose target is met completes and is paid`() =
        runTest {
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 3)

            val completed = rig.run()

            assertEquals(listOf(THREE_SESSIONS), completed.map(QuestCompletion::quest))
            assertEquals(THREE_SESSIONS.xpReward, completed.single().xp.awarded)
            assertEquals(THREE_SESSIONS.xpReward.toLong(), rig.gamification.totalXp)
        }

    @Test
    fun `a quest short of its target completes nothing and pays nothing`() =
        runTest {
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 2)

            assertEquals(emptyList<QuestCompletion>(), rig.run())
            assertEquals(0L, rig.gamification.totalXp)
        }

    @Test
    fun `both rungs of a ladder complete when one session clears both`() =
        runTest {
            val rig = Rig(listOf(THREE_SESSIONS, FIVE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 5)

            // In catalogue order, so the smaller rung reads as having been earned first.
            assertEquals(listOf(THREE_SESSIONS, FIVE_SESSIONS), rig.run().map(QuestCompletion::quest))
        }

    @Test
    fun `a weekly and a monthly quest can complete on the same session`() =
        runTest {
            val rig = Rig(listOf(THREE_SESSIONS, TWELVE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 12)

            val completed = rig.run()

            assertEquals(listOf(THREE_SESSIONS, TWELVE_SESSIONS), completed.map(QuestCompletion::quest))
            // Into different periods: the week's Monday and the month's first.
            assertEquals(
                listOf(LocalDate.of(2025, Month.SEPTEMBER, 1), LocalDate.of(2025, Month.SEPTEMBER, 1)),
                completed.map(QuestCompletion::periodStart),
            )
        }

    // ─── Never twice ────────────────────────────────────────────────────────────────────

    @Test
    fun `a second pass over the same period completes nothing and pays nothing again`() =
        runTest {
            // The re-entry contract: a cascade run twice for one session must leave what one clean
            // run leaves. The target stays met all week, so nothing but the written row stops it.
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 3)

            rig.run()
            val second = rig.run()

            assertEquals(emptyList<QuestCompletion>(), second)
            assertEquals(THREE_SESSIONS.xpReward.toLong(), rig.gamification.totalXp)
            assertEquals(1, rig.quests.written.size)
        }

    @Test
    fun `a later session in the same week does not complete the quest a second time`() =
        runTest {
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 3)
            rig.run()

            rig.progression.setSessionsOn(WEDNESDAY.plusDays(1), 4)
            val later = rig.run(finishedAt = WEDNESDAY.plusDays(1).atInstant())

            assertEquals(emptyList<QuestCompletion>(), later)
            assertEquals(THREE_SESSIONS.xpReward.toLong(), rig.gamification.totalXp)
        }

    @Test
    fun `the same quest completes again in the next period`() =
        runTest {
            // The whole difference from an achievement: a quest recurs. The completion on file is
            // last week's, and it must not stand in the way of this week's.
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.quests.seedCompleted(THREE_SESSIONS.id, QuestPeriod.WEEKLY.startOf(WEDNESDAY.minusWeeks(1)))
            rig.progression.setSessionsOn(WEDNESDAY, 3)

            val completed = rig.run()

            assertEquals(listOf(THREE_SESSIONS), completed.map(QuestCompletion::quest))
            assertEquals(LocalDate.of(2025, Month.SEPTEMBER, 1), completed.single().periodStart)
        }

    @Test
    fun `a completion already on file is not paid for`() =
        runTest {
            // The insert is what makes the completion unrepeatable, and the award sits behind its
            // answer — so a row that was already there pays nothing, however the board reads.
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.quests.seedCompleted(THREE_SESSIONS.id, QuestPeriod.WEEKLY.startOf(WEDNESDAY))
            rig.progression.setSessionsOn(WEDNESDAY, 9)

            assertEquals(emptyList<QuestCompletion>(), rig.run())
            assertEquals(0L, rig.gamification.totalXp)
        }

    // ─── Which period, and when ─────────────────────────────────────────────────────────

    @Test
    fun `the period comes from the session rather than from the day the pass runs`() =
        runTest {
            // A session finished at 23:50 UTC on a Sunday and processed after a crash on the Monday
            // belongs to the week it was trained in. Read from a clock, this would pay it out of
            // the following week — and leave the week it was earned in unfinished forever.
            val sunday = LocalDate.of(2025, Month.SEPTEMBER, 7)
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(sunday, 3)

            val completed = rig.run(finishedAt = sunday.atInstant(hour = 23, minute = 50))

            assertEquals(LocalDate.of(2025, Month.SEPTEMBER, 1), completed.single().periodStart)
        }

    @Test
    fun `the completion is dated to the workout rather than to the retry`() =
        runTest {
            val finishedAt = WEDNESDAY.atInstant(hour = 18)
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 3)

            rig.run(finishedAt = finishedAt)

            val week = LocalDate.of(2025, Month.SEPTEMBER, 1)
            assertEquals(finishedAt, rig.quests.completedAt(THREE_SESSIONS.id, week))

            // A retry an hour later writes nothing, so the row keeps the workout's own moment.
            rig.run(finishedAt = finishedAt.plusSeconds(3_600))

            assertEquals(finishedAt, rig.quests.completedAt(THREE_SESSIONS.id, week))
        }

    // ─── When the store refuses ─────────────────────────────────────────────────────────

    @Test
    fun `a store that refuses a write throws rather than reporting a completion`() =
        runTest {
            val rig = Rig(listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 3)
            rig.quests.failNext = IllegalStateException("the quest table is not writable")

            assertThrows(IllegalStateException::class.java) { runBlocking { rig.run() } }
            assertEquals(0L, rig.gamification.totalXp)
        }

    @Test
    fun `completions banked before the store refused are still reported`() =
        runTest {
            // Each insert is committed the moment it returns and no later run can reach it again,
            // so a caller that lost the earlier completions to the throw could never recover them:
            // every later pass gets `false` back and reports nothing. The user would hold a quest
            // nothing ever celebrated, against XP already banked.
            val rig = Rig(listOf(THREE_SESSIONS, FIVE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 5)
            // Let the first rung through, refuse the second.
            rig.quests.refuseWriteAfter = 1

            val into = mutableListOf<QuestCompletion>()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { rig.completeQuests(WEDNESDAY.atInstant(), into) }
            }

            assertEquals(listOf(THREE_SESSIONS), into.map(QuestCompletion::quest))
            assertEquals(THREE_SESSIONS.xpReward.toLong(), rig.gamification.totalXp)
        }

    @Test
    fun `an empty catalogue completes nothing without failing`() =
        runTest {
            assertEquals(emptyList<QuestCompletion>(), Rig(emptyList()).run())
        }

    private class Rig(catalogue: List<Quest>) {

        val progression = FakeProgressionRepository()
        val records = FakePersonalRecordRepository()
        val quests = FakeQuestRepository()
        val gamification = FakeGamificationRepository()

        val completeQuests = QuestCompletionUseCase(
            questProgress = QuestProgressUseCase(
                progressionRepository = progression,
                personalRecordRepository = records,
                questRepository = quests,
                catalogue = catalogue,
            ),
            questRepository = quests,
            awardXp = AwardXpUseCase(gamification),
        )

        suspend fun run(finishedAt: Instant = WEDNESDAY.atInstant()): List<QuestCompletion> = completeQuests(finishedAt)
    }

    private companion object {

        /** Wednesday 3 September 2025, in the week of Monday the 1st. */
        val WEDNESDAY: LocalDate = LocalDate.of(2025, Month.SEPTEMBER, 3)

        val THREE_SESSIONS = quest("three_sessions", 3, QuestPeriod.WEEKLY, xpReward = 60)
        val FIVE_SESSIONS = quest("five_sessions", 5, QuestPeriod.WEEKLY, xpReward = 100)
        val TWELVE_SESSIONS = quest("twelve_sessions", 12, QuestPeriod.MONTHLY, xpReward = 250)

        fun quest(
            id: String,
            target: Long,
            period: QuestPeriod,
            xpReward: Int,
        ) = Quest(
            id = id,
            metric = QuestMetric.SESSIONS_FINISHED,
            target = target,
            period = period,
            xpReward = xpReward,
        )

        fun LocalDate.atInstant(
            hour: Int = 12,
            minute: Int = 0,
        ): Instant = Instant.ofEpochMilli(toEpochDay() * 86_400_000L + hour * 3_600_000L + minute * 60_000L)
    }
}
