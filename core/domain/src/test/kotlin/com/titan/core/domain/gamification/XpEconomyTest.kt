package com.titan.core.domain.gamification

import com.titan.core.domain.model.AchievementProgress
import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestPeriod
import org.junit.Assert.assertEquals
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
 * The same year holds the curve's shape: several levels in the first week, one or two a week as
 * the year closes, and at least one a week through the second — the promise [XpCalculator]'s
 * class doc makes, and the reason its curve is a power rather than a line or an exponential.
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
    fun `a new lifter levels up several times in their first week`() {
        val firstWeek = XpCalculator.levelForTotalXp(referenceYearXp() / WEEKS_PER_YEAR)

        assertTrue("the first week reaches level $firstWeek", firstWeek >= FIRST_WEEK_LEVEL_FLOOR)
    }

    @Test
    fun `as the first year closes a level still comes once or twice a week`() {
        // The last four weeks rather than the last one, so a level that straddles a week's end
        // does not decide the answer.
        val year = referenceYearXp()
        val gained = XpCalculator.levelForTotalXp(year) -
            XpCalculator.levelForTotalXp(year * (WEEKS_PER_YEAR - 4) / WEEKS_PER_YEAR)

        assertTrue("the year's last four weeks gain $gained levels", gained in 4..8)
    }

    @Test
    fun `the second year still pays at least a level a week`() {
        // At the first year's rate, which flatters it slightly: a second year unlocks fewer
        // achievements and beats fewer records. The margin is what absorbs that — about 58
        // levels against the 52 asserted.
        val year = referenceYearXp()
        val gained = XpCalculator.levelForTotalXp(year * 2) - XpCalculator.levelForTotalXp(year)

        assertTrue("the second year gains $gained levels", gained >= WEEKS_PER_YEAR)
    }

    @Test
    fun `the last rank opens on the year's target`() {
        // [LevelTitles] places its bands against this same reference year; the top one is the
        // year itself, and a retune of either side that pulls them apart fails here.
        assertEquals(LevelTitles.LEGEND, LevelTitles.ALL.last())
        assertEquals(TARGET_LEVEL, LevelTitles.LEGEND.fromLevel)
    }

    @Test
    fun `records are a bonus on top of the training rather than the bulk of it`() {
        val records = SESSIONS_PER_YEAR * RECORDS_PER_SESSION * XpCalculator.XP_PER_PR
        val training = SESSIONS_PER_YEAR * (XpCalculator.XP_PER_SESSION + SETS_PER_SESSION * XpCalculator.XP_PER_SET)

        assertTrue("records pay $records against $training for the training itself", records * 3 < training)
    }

    @Test
    fun `the reference session sits inside every session cap, so the caps take nothing from the year`() {
        // The year below multiplies a session's sets and records by the prices as if every one
        // were paid. That holds only while [SessionXp]'s floor and ceilings leave the reference
        // session alone; a cap tightened past it would cut the real year and leave this sum
        // reading as if nothing had changed.
        assertTrue(SETS_PER_SESSION >= SessionXp.MIN_SETS_FOR_SESSION_XP)
        assertTrue(SETS_PER_SESSION <= SessionXp.MAX_PAID_SETS)
        assertTrue(RECORDS_PER_SESSION <= SessionXp.MAX_PAID_RECORDS)
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

        /** Several levels in the first week: the reference week reaches 8. */
        const val FIRST_WEEK_LEVEL_FLOOR = 5

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
