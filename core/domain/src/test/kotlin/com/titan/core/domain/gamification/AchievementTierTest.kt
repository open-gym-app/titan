package com.titan.core.domain.gamification

import com.titan.core.domain.model.Achievement
import com.titan.core.domain.model.AchievementMetric
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The metal a milestone is worth, read off its own ladder.
 *
 * The point of these is the *derivation*, not the shipped answers: a catalogue with a new top rung
 * must move the gold onto it with no edit to [tier], and the case below builds exactly that ladder
 * rather than asserting what today's six happen to be.
 */
class AchievementTierTest {

    @Test
    fun `the shipped catalogue ranks each ladder from its own ends`() {
        assertEquals(AchievementTier.BRONZE, Achievements.FIRST_SESSION.tier())
        assertEquals(AchievementTier.SILVER, Achievements.TEN_SESSIONS.tier())
        assertEquals(AchievementTier.GOLD, Achievements.HUNDRED_SESSIONS.tier())

        // The streak ladder has two rungs, so it has no middle: bottom and top, bronze and gold.
        assertEquals(AchievementTier.BRONZE, Achievements.SEVEN_DAY_STREAK.tier())
        assertEquals(AchievementTier.GOLD, Achievements.THIRTY_DAY_STREAK.tier())
    }

    @Test
    fun `a ladder of one is bronze, because a first is a first`() {
        // `first_record` is the only record milestone this build ships. Calling it the top of its
        // ladder would hand a brand-new user the loudest medal in the set for the easiest thing in
        // it. See the note on [tier].
        assertEquals(AchievementTier.BRONZE, Achievements.FIRST_RECORD.tier())
    }

    @Test
    fun `a new top rung takes the gold, and the one below it drops to silver`() {
        val fiveHundred = Achievement(
            id = "five_hundred_sessions",
            metric = AchievementMetric.SESSIONS_FINISHED,
            threshold = 500,
        )
        val catalogue = Achievements.ALL + fiveHundred

        assertEquals(AchievementTier.GOLD, fiveHundred.tier(catalogue))
        assertEquals(AchievementTier.SILVER, Achievements.HUNDRED_SESSIONS.tier(catalogue))
        assertEquals(AchievementTier.BRONZE, Achievements.FIRST_SESSION.tier(catalogue))
    }

    @Test
    fun `a rung is ranked against its own metric and not against every threshold`() {
        // Seven days is a bigger number than one session and a smaller one than a hundred, and
        // neither comparison means anything: it is the first rung of the streak ladder.
        assertEquals(AchievementTier.BRONZE, Achievements.SEVEN_DAY_STREAK.tier())
    }

    @Test
    fun `a milestone the catalogue does not know is bronze`() {
        // A row from a newer build, read by an older one: it is off the ladder entirely, and the
        // quietest metal is the right answer for something nothing can be compared to.
        val stranger = Achievement(id = "unknown", metric = AchievementMetric.SESSIONS_FINISHED, threshold = 42)

        assertEquals(AchievementTier.BRONZE, stranger.tier())
    }
}
