package com.titan.core.domain.gym

import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.usecase.AwardXpUseCase
import com.titan.core.domain.usecase.FakeGamificationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GymXpHookTest {

    private val gamification = FakeGamificationRepository()
    private val hook = GymXpHook(FakeGymMilestoneRepository(), AwardXpUseCase(gamification))

    @Test
    fun `the first check-in pays 50 XP into the user's total`() =
        runTest {
            val award = hook.onCheckIn()

            assertEquals(50, award?.awarded)
            assertEquals(50L, gamification.totalXp)
        }

    @Test
    fun `the first review pays 30 XP`() =
        runTest {
            assertEquals(30, hook.onReviewPosted()?.awarded)
            assertEquals(30L, gamification.totalXp)
        }

    @Test
    fun `check-in XP is paid once however often a check-in happens`() =
        runTest {
            assertNotNull(hook.onCheckIn())
            assertNull(hook.onCheckIn())
            assertNull(hook.onCheckIn())

            assertEquals(XpCalculator.XP_PER_FIRST_GYM_CHECK_IN.toLong(), gamification.totalXp)
        }

    @Test
    fun `review XP is paid once however often a review is posted`() =
        runTest {
            assertNotNull(hook.onReviewPosted())
            assertNull(hook.onReviewPosted())

            assertEquals(XpCalculator.XP_PER_FIRST_GYM_REVIEW.toLong(), gamification.totalXp)
        }

    @Test
    fun `the two milestones are independent`() =
        runTest {
            hook.onCheckIn()
            hook.onCheckIn()
            hook.onReviewPosted()

            assertEquals(80L, gamification.totalXp)
        }
}
