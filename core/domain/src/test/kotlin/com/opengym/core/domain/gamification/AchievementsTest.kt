package com.opengym.core.domain.gamification

import com.opengym.core.domain.model.Achievement
import com.opengym.core.domain.model.AchievementMetric
import com.opengym.core.domain.model.AchievementProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue as data: what it contains, and that every entry in it is reachable.
 *
 * The fixture below is what "defined as data, not branching logic" buys — it walks
 * [Achievements.ALL] rather than naming six milestones, so an achievement added later is
 * covered by these tests on the line that adds it.
 */
class AchievementsTest {

    // ─── The set itself ─────────────────────────────────────────────────────────────────

    @Test
    fun `the initial set ships six achievements`() {
        assertEquals(6, Achievements.ALL.size)
    }

    @Test
    fun `no two achievements share an id`() {
        assertEquals(Achievements.ALL.size, Achievements.ALL.map(Achievement::id).toSet().size)
    }

    @Test
    fun `the ids are the ones the achievement table holds`() {
        // Pinned, like the persisted enum names: an id is written to
        // `AchievementEntity.achievementId` and a rename orphans every row already under the
        // old spelling — which re-unlocks the achievement and re-awards its XP. A failure
        // here is the reminder that the change needs a migration.
        assertEquals(
            setOf(
                "first_session",
                "ten_sessions",
                "hundred_sessions",
                "seven_day_streak",
                "thirty_day_streak",
                "first_record",
            ),
            Achievements.ALL.map(Achievement::id).toSet(),
        )
    }

    @Test
    fun `every metric the catalogue measures in is actually used`() {
        assertEquals(
            AchievementMetric.entries.toSet(),
            Achievements.ALL.map(Achievement::metric).toSet(),
        )
    }

    @Test
    fun `a ladder's rungs are listed in the order they are climbed`() {
        Achievements.ALL
            .groupBy(Achievement::metric)
            .forEach { (metric, ladder) ->
                val thresholds = ladder.map(Achievement::threshold)
                assertEquals("$metric is not listed cheapest first", thresholds.sorted(), thresholds)
            }
    }

    @Test
    fun `a milestone nobody has to reach is not a milestone`() {
        assertThrows(IllegalArgumentException::class.java) {
            Achievement(id = "free_lunch", metric = AchievementMetric.SESSIONS_FINISHED, threshold = 0)
        }
    }

    // ─── Every entry unlocks, and not before it should ──────────────────────────────────

    @Test
    fun `each achievement unlocks on the figure it names`() {
        Achievements.ALL.forEach { achievement ->
            assertTrue(
                "${achievement.id} did not unlock at its own threshold",
                achievement.isUnlockedBy(progressAt(achievement.metric, achievement.threshold)),
            )
        }
    }

    @Test
    fun `each achievement stays locked one short of it`() {
        Achievements.ALL.forEach { achievement ->
            assertFalse(
                "${achievement.id} unlocked below its threshold",
                achievement.isUnlockedBy(progressAt(achievement.metric, achievement.threshold - 1)),
            )
        }
    }

    @Test
    fun `an achievement stays unlockable long after its threshold is passed`() {
        // Greater-or-equal, not equal: a user who finished their tenth session while the app
        // was killed must still be able to unlock `ten_sessions` on their eleventh.
        Achievements.ALL.forEach { achievement ->
            assertTrue(
                "${achievement.id} is only reachable exactly on its threshold",
                achievement.isUnlockedBy(progressAt(achievement.metric, achievement.threshold + 50)),
            )
        }
    }

    @Test
    fun `an achievement reads its own metric and no other`() {
        // A snapshot standing high on everything *except* the measured metric unlocks nothing.
        Achievements.ALL.forEach { achievement ->
            val progress = AchievementProgress(
                sessionsFinished = 1_000,
                currentStreak = 1_000,
                recordsBroken = 1_000,
            ).copyWith(achievement.metric, 0)

            assertFalse("${achievement.id} unlocked off another metric", achievement.isUnlockedBy(progress))
        }
    }

    /** A snapshot standing at [value] on [metric] and at nothing on any other. */
    private fun progressAt(
        metric: AchievementMetric,
        value: Int,
    ): AchievementProgress = EMPTY_PROGRESS.copyWith(metric, value)

    private fun AchievementProgress.copyWith(
        metric: AchievementMetric,
        value: Int,
    ): AchievementProgress =
        when (metric) {
            AchievementMetric.SESSIONS_FINISHED -> copy(sessionsFinished = value)
            AchievementMetric.STREAK_DAYS -> copy(currentStreak = value)
            AchievementMetric.RECORDS_BROKEN -> copy(recordsBroken = value)
        }

    private companion object {

        val EMPTY_PROGRESS = AchievementProgress(sessionsFinished = 0, currentStreak = 0, recordsBroken = 0)
    }
}
