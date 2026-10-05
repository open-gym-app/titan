package com.titan.core.domain.gamification

import com.titan.core.domain.model.AchievementProgress
import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestPeriod
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target the XP economy is tuned to: **level 100 after a year** of training to the default
 * weekly goal.
 *
 * Adds up a reference year from the catalogues themselves — [XpCalculator]'s prices, every quest
 * in [Quests] the year clears, every achievement in [Achievements] it reaches — so a retune of
 * any of them that moves the year fails here, with the level it lands on, rather than surfacing
 * as a number on a screenshot.
 *
 * ### The reference lifter
 *
 * [StreakCalculator.DEFAULT_WEEKLY_GOAL] sessions every week for 52 weeks, each the size of one
 * of the default push, pull and legs templates. The records and the volume are the demo
 * dataset's (`tools/demo/`), which is the app's own progression engine run over five months:
 * nearer four records a session over the year than the dozens of the first sessions, and about
 * nine tonnes moved per session.
 */
class XpEconomyTest {

    @Test
    fun `a year of training to the default goal lands on level 100`() {
        val level = XpCalculator.levelForTotalXp(referenceYearXp())

        assertTrue("a reference year reaches level $level, not ~$TARGET_LEVEL", level in TARGET_LEVEL_RANGE)
    }

    @Test
    fun `records are a bonus on top of the training rather than the bulk of it`() {
        val records = SESSIONS_PER_YEAR * RECORDS_PER_SESSION * XpCalculator.XP_PER_PR
        val training = SESSIONS_PER_YEAR * (XpCalculator.XP_PER_SESSION + SETS_PER_SESSION * XpCalculator.XP_PER_SET)

        assertTrue("records pay $records against $training for the training itself", records * 3 < training)
    }

    private fun referenceYearXp(): Long {
        val sessions = SESSIONS_PER_YEAR * XpCalculator.XP_PER_SESSION
        val sets = SESSIONS_PER_YEAR * SETS_PER_SESSION * XpCalculator.XP_PER_SET
        val records = SESSIONS_PER_YEAR * RECORDS_PER_SESSION * XpCalculator.XP_PER_PR
        val weekly = WEEKS_PER_YEAR * Quests.ALL.cleared(QuestPeriod.WEEKLY, sessionsPerPeriod = SESSIONS_PER_WEEK)
        val monthly = MONTHS_PER_YEAR * Quests.ALL.cleared(QuestPeriod.MONTHLY, sessionsPerPeriod = SESSIONS_PER_MONTH)
        val progress = AchievementProgress(
            sessionsFinished = SESSIONS_PER_YEAR,
            currentStreak = WEEKS_PER_YEAR,
            recordsBroken = SESSIONS_PER_YEAR * RECORDS_PER_SESSION,
        )
        val achievements = Achievements.ALL.count { it.isUnlockedBy(progress) } * XpCalculator.XP_PER_ACHIEVEMENT

        return (sessions + sets + records + weekly + monthly + achievements).toLong()
    }

    /** What one [period] of the reference lifter's training pays in quests of that cadence. */
    private fun List<Quest>.cleared(
        period: QuestPeriod,
        sessionsPerPeriod: Int,
    ): Int =
        filter { it.period == period }
            .filter { quest ->
                quest.isMetBy(
                    when (quest.metric) {
                        QuestMetric.SESSIONS_FINISHED -> sessionsPerPeriod.toLong()
                        QuestMetric.RECORDS_BROKEN -> (sessionsPerPeriod * RECORDS_PER_SESSION).toLong()
                        QuestMetric.VOLUME_MILLIGRAMS -> sessionsPerPeriod * VOLUME_PER_SESSION_MILLIGRAMS
                    },
                )
            }.sumOf { it.xpReward }

    private companion object {

        const val TARGET_LEVEL = 100
        val TARGET_LEVEL_RANGE = 95..105

        const val WEEKS_PER_YEAR = 52
        const val MONTHS_PER_YEAR = 12
        const val SESSIONS_PER_WEEK = StreakCalculator.DEFAULT_WEEKLY_GOAL
        const val SESSIONS_PER_YEAR = WEEKS_PER_YEAR * SESSIONS_PER_WEEK
        const val SESSIONS_PER_MONTH = SESSIONS_PER_YEAR / MONTHS_PER_YEAR

        /** The default push, pull and legs templates average nineteen working sets. */
        const val SETS_PER_SESSION = 19

        /** The demo's records over its five months, per session, rounded up. */
        const val RECORDS_PER_SESSION = 4

        /** The demo's volume per session, rounded down: about nine tonnes. */
        const val VOLUME_PER_SESSION_MILLIGRAMS = 9_000_000_000L
    }
}
