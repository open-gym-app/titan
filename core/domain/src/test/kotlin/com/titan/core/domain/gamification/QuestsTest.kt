package com.titan.core.domain.gamification

import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue as data: what it contains, and that every entry in it is coherent.
 *
 * Walks [Quests.ALL] rather than naming seven quests, the same call [AchievementsTest] makes,
 * so a quest added later is covered by these tests on the line that adds it.
 */
class QuestsTest {

    // ─── The set itself ─────────────────────────────────────────────────────────────────

    @Test
    fun `the initial set ships seven quests`() {
        assertEquals(7, Quests.ALL.size)
    }

    @Test
    fun `no two quests share an id`() {
        assertEquals(Quests.ALL.size, Quests.ALL.map(Quest::id).toSet().size)
    }

    @Test
    fun `the ids are the ones the quest_completion table holds`() {
        // Pinned for the reason the achievement ids are, with one extra consequence: a quest
        // renamed does not just orphan its rows, it becomes completable again in a period it
        // was already paid for. A failure here is the reminder that the change needs a
        // migration.
        assertEquals(
            setOf(
                "weekly_sessions",
                "weekly_sessions_plus",
                "weekly_record",
                "weekly_volume",
                "monthly_sessions",
                "monthly_records",
                "monthly_volume",
            ),
            Quests.ALL.map(Quest::id).toSet(),
        )
    }

    @Test
    fun `every metric the catalogue measures in is actually used`() {
        assertEquals(QuestMetric.entries.toSet(), Quests.ALL.map(Quest::metric).toSet())
    }

    @Test
    fun `every period the catalogue resets on is actually used`() {
        assertEquals(QuestPeriod.entries.toSet(), Quests.ALL.map(Quest::period).toSet())
    }

    @Test
    fun `every metric appears at every cadence`() {
        // The board's shape, asserted rather than left to the reading eye: a lifter who trains
        // often and never breaks a record, and one who trains twice a week and goes heavy,
        // must both have a board that fills in. See the note on [Quests].
        QuestPeriod.entries.forEach { period ->
            val measured = Quests.ALL.filter { it.period == period }.map(Quest::metric).toSet()
            assertEquals("$period does not cover every metric", QuestMetric.entries.toSet(), measured)
        }
    }

    @Test
    fun `weekly quests are listed before monthly ones`() {
        // The order is what a board draws in and what completions are reported in.
        val periods = Quests.ALL.map(Quest::period)
        assertEquals(periods.sortedBy(QuestPeriod::ordinal), periods)
    }

    @Test
    fun `a ladder's rungs are listed in the order they are climbed`() {
        Quests.ALL
            .groupBy { it.period to it.metric }
            .forEach { (ladder, rungs) ->
                val targets = rungs.map(Quest::target)
                assertEquals("$ladder is not listed cheapest first", targets.sorted(), targets)
            }
    }

    @Test
    fun `a harder rung of a ladder pays more than an easier one`() {
        // Otherwise the board would ask for five sessions and pay less than it paid for three,
        // which reads as a bug however it is drawn.
        Quests.ALL
            .groupBy { it.period to it.metric }
            .values
            .forEach { rungs ->
                rungs.zipWithNext { easier, harder ->
                    assertTrue(
                        "${harder.id} is harder than ${easier.id} and pays no more",
                        harder.xpReward > easier.xpReward,
                    )
                }
            }
    }

    @Test
    fun `a month asks for more than a week and pays for it`() {
        // Not a rule the type system can state, and the one that keeps the two cadences from
        // collapsing into each other: a monthly quest a good week clears is not an arc.
        QuestMetric.entries.forEach { metric ->
            val weekly = Quests.ALL.filter { it.period == QuestPeriod.WEEKLY && it.metric == metric }
            val monthly = Quests.ALL.filter { it.period == QuestPeriod.MONTHLY && it.metric == metric }

            val hardestWeekly = weekly.maxOf(Quest::target)
            monthly.forEach { quest ->
                assertTrue(
                    "${quest.id} asks no more of a month than a week already asks",
                    quest.target > hardestWeekly,
                )
                assertTrue(
                    "${quest.id} pays no more than the weekly rungs it outlasts",
                    quest.xpReward > weekly.maxOf(Quest::xpReward),
                )
            }
        }
    }

    @Test
    fun `a weekly quest is worth more than showing up and less than an achievement`() {
        // The prices read against [XpCalculator], which is where the economy they sit in is
        // written down. Below an achievement, because a milestone reached once in an install's
        // life should outweigh a week's board; above a single session, or the training would be
        // the small half of its own reward.
        Quests.ALL.filter { it.period == QuestPeriod.WEEKLY }.forEach { quest ->
            assertTrue("${quest.id} is worth less than one session", quest.xpReward > XpCalculator.XP_PER_SESSION)
            assertTrue(
                "${quest.id} is worth more than an achievement",
                quest.xpReward <= XpCalculator.XP_PER_ACHIEVEMENT,
            )
        }
    }

    @Test
    fun `a monthly quest is worth at least five sessions`() {
        Quests.ALL.filter { it.period == QuestPeriod.MONTHLY }.forEach { quest ->
            assertTrue(
                "${quest.id} does not pay for a month",
                quest.xpReward >= MONTHLY_SESSIONS_WORTH * XpCalculator.XP_PER_SESSION,
            )
        }
    }

    // ─── Entries are coherent ───────────────────────────────────────────────────────────

    @Test
    fun `a quest nobody has to finish is not a quest`() {
        assertThrows(IllegalArgumentException::class.java) {
            Quest(
                id = "free_lunch",
                metric = QuestMetric.SESSIONS_FINISHED,
                target = 0,
                period = QuestPeriod.WEEKLY,
                xpReward = 10,
            )
        }
    }

    @Test
    fun `a quest cannot cost the user points`() {
        assertThrows(IllegalArgumentException::class.java) {
            Quest(
                id = "robbery",
                metric = QuestMetric.SESSIONS_FINISHED,
                target = 1,
                period = QuestPeriod.WEEKLY,
                xpReward = -10,
            )
        }
    }

    @Test
    fun `each quest is met at its own target`() {
        Quests.ALL.forEach { quest ->
            assertTrue("${quest.id} was not met at its own target", quest.isMetBy(quest.target))
        }
    }

    @Test
    fun `each quest is unmet one short of it`() {
        Quests.ALL.forEach { quest ->
            assertFalse("${quest.id} was met below its target", quest.isMetBy(quest.target - 1))
        }
    }

    @Test
    fun `overshooting a target finishes the quest rather than missing it`() {
        // Greater-or-equal, not equal: a lifter who trained six times has done the five the
        // quest asked for, and a board that read 6/5 as unmet would be unwinnable by anyone
        // having a good week.
        Quests.ALL.forEach { quest ->
            assertTrue("${quest.id} is only met exactly on its target", quest.isMetBy(quest.target + 50))
        }
    }

    private companion object {

        /** What a month's quest has to be worth, in sessions — see the note on [Quests]. */
        const val MONTHLY_SESSIONS_WORTH = 5
    }
}
